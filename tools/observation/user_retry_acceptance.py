"""原失败样本的用户下一轮只读重试；只提交一个全新输入，不恢复或重放旧Run。

OBSERVATION_PRIOR_BATCH指向已完成的原批；OBSERVATION_OUTPUT_DIR必须是新ignored目录。
沿用正式平台口令环境、原机构private.json及原ProviderGuard同tenant累计64次上界。
"""
from __future__ import annotations

from datetime import datetime
import json
import os
from pathlib import Path
import re
import socket
import threading
import urllib.parse

from persistent_acceptance import (Acceptance, Host, Journal, LONG_TOOL, MAX_CALLS, McpFixtureServer,
    ProviderGuard, REPO, Stopped, digest, query, require, require_answer, save)


def load_prior(folder):
    root = Path(folder).resolve()
    require(root.is_relative_to(REPO / ".runtime-logs") and root.is_dir(), "EXACT_PRIOR_IGNORED_BATCH_REQUIRED")
    state = json.loads((root / "state.json").read_text(encoding="utf-8"))
    require(state.get("state") == "FAILED" and state.get("failureCode") == "REAL_LONG_TOOL_FAILURE_RECOVERY_REQUIRED",
            "PRIOR_AUTONOMOUS_RECOVERY_FAILURE_REQUIRED")
    cases = state.get("cases", [])
    require(len(cases) == 4 and all(row.get("state") == "COMPLETED" and row.get("answerHash") for row in cases),
            "FOUR_COMPLETED_PRIOR_ROOTS_REQUIRED")
    require(cases[0]["sessionId"] == cases[1]["sessionId"] and cases[0]["rootInputId"] != cases[1]["rootInputId"],
            "PRIOR_TWO_TURN_SESSION_REQUIRED")
    receipts = state.get("toolReceipts", [])
    require(len(receipts) == 1 and receipts[0].get("toolName") == LONG_TOOL
            and receipts[0].get("isError") is True and receipts[0].get("errorCode") == "unavailable",
            "PRIOR_ONE_KNOWN_FAILED_MCP_CALL_REQUIRED")
    require(state["provider"]["pending"] == 0 and state["provider"]["unknown"] == 0
            and state["provider"]["failedModels"] == 0 and state["provider"]["missingUsage"] == 0
            and state["provider"]["calls"] < MAX_CALLS, "PRIOR_PROVIDER_MUST_BE_SETTLED")
    password = json.loads((root / "private.json").read_text(encoding="utf-8"))["institutionalPassword"]
    require(isinstance(password, str) and password, "PRIOR_PRIVATE_INSTITUTION_PASSWORD_REQUIRED")
    return root, state, password


def exact_fixture_port(connector):
    uri = urllib.parse.urlsplit(connector.get("base_url", ""))
    require(connector.get("executor_type") == "mcp" and connector.get("status") == "active"
            and connector.get("auth_type") == "none" and uri.scheme == "http" and uri.hostname == "127.0.0.1"
            and uri.port and uri.path == "/mcp" and not uri.query and not uri.fragment and not uri.username,
            "PRIOR_OWNED_LOOPBACK_MCP_ENDPOINT_REQUIRED")
    return uri.port


class ExclusiveMcpServer(McpFixtureServer):
    allow_reuse_address = False

    def server_bind(self):
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()


def stopped_fixture(port, batch):
    try:
        # Windows 对关闭的回环端口约两秒后才返回 WSAECONNREFUSED。
        connection = socket.create_connection(("127.0.0.1", port), timeout=5)
    except ConnectionRefusedError:
        pass
    except OSError:
        raise Stopped("PRIOR_MCP_PORT_STATE_UNCONFIRMED") from None
    else:
        connection.close()
        raise Stopped("PRIOR_MCP_PORT_STILL_OWNED_BY_RUNNING_PROCESS")
    try:
        return ExclusiveMcpServer(port, batch, query_alias=LONG_TOOL)
    except OSError:
        raise Stopped("PRIOR_MCP_PORT_CANNOT_BE_EXCLUSIVELY_REBOUND") from None


def recovery_times(failed, succeeded):
    require(failed.get("finished_at") and succeeded.get("started_at"), "RECOVERY_TOOL_TIMES_REQUIRED")
    before = datetime.fromisoformat(failed["finished_at"].replace("Z", "+00:00"))
    after = datetime.fromisoformat(succeeded["started_at"].replace("Z", "+00:00"))
    require(before.tzinfo and after.tzinfo and before <= after, "USER_RETRY_MUST_FOLLOW_ORIGINAL_FAILURE")


def known_failed_tool(original, nodes):
    failed = [node for node in nodes if node["kind"] == "TOOL"]
    require(len(failed) == 1 and failed[0]["status"] == "FAILED"
            and failed[0]["run_id"] == original["runId"] and failed[0]["root_input_id"] == original["rootInputId"]
            and failed[0]["source_session_id"] == original["sessionId"]
            and re.fullmatch("sf_" + re.escape(LONG_TOOL[:28]) + "_[a-f0-9]{32}", failed[0]["name"]),
            "PRIOR_ONE_KNOWN_FAILED_TOOL_REQUIRED")
    # summary不是原因码契约；已知unavailable由load_prior核验父批实际MCP回执证明。
    return failed[0]


class UserRetry(Acceptance):
    def __init__(self, journal, prior_folder):
        self.journal = journal
        self.prior_root, self.prior, self.password = load_prior(prior_folder)
        self.batch = self.prior["batch"]
        resources = self.prior["resources"]
        self.tenant, self.user, self.model = resources["tenantId"], resources["endUserId"], resources["modelId"]
        self.model_revision = self.prior["model"]["published_revision"]
        self.platform = Host(os.environ.get("OBSERVATION_PLATFORM_URL", "http://127.0.0.1:8100/api/v1"), journal, True)
        self.institution = Host(os.environ.get("OBSERVATION_INSTITUTION_URL", "http://127.0.0.1:8000/api/v1"), journal)
        self.guard = ProviderGuard(self.tenant, journal)
        self.platform.guard = self.institution.guard = self.guard
        self.server = None
        self.mcp_fixture_created = False
        self.answers = {}
        self.case_key = self.batch + ":recovery"
        journal.value.update(scope="USER_NEXT_INPUT", priorBatch=str(self.prior_root), priorFailureCode=self.prior["failureCode"],
                             batch=self.batch, resources=resources, model=self.prior["model"])
        journal.flush()
        self.ready = {"version": 1, "institutionBackendURL": self.institution.base.removesuffix("/api/v1"),
            "platformBackendURL": self.platform.base.removesuffix("/api/v1"), "tenantId": self.tenant,
            "tenantSlug": self.batch, "tenantUsername": self.batch + "-admin",
            "platformUsername": os.environ["SF_PLATFORM_USER"], "cases": []}

    def read_prior_root(self, item):
        session, root = item["sessionId"], item["rootInputId"]
        detail = self.institution.request("GET", query("/chat/trajectories/detail", session_id=session, root_input_id=root))
        summary = detail["summary"]
        require(summary["status"] == "COMPLETED" and summary["coverage"]["status"] == "COMPLETE"
                and digest(summary.get("output")) == item["answerHash"], "PRIOR_PERSISTED_ROOT_EVIDENCE_DRIFT")
        nodes, cursor = [], None
        for _ in range(10):
            parameters = {"session_id": session, "root_input_id": root, "snapshot": detail["snapshot"], "page_size": 200}
            if cursor:
                parameters["cursor"] = cursor
            page = self.institution.request("GET", query("/chat/trajectories/nodes", **parameters))
            require(page["snapshot"] == detail["snapshot"] and page["coverage"] == "COMPLETE", "PRIOR_NODES_SNAPSHOT_DRIFT")
            nodes.extend(page["items"])
            following = page.get("next_cursor")
            if not following:
                return nodes
            require(following != cursor, "PRIOR_NODES_CURSOR_STALLED"); cursor = following
        raise Stopped("PRIOR_NODES_PAGE_BOUND_EXCEEDED")

    def checked_agent(self, session):
        detail = self.institution.request("GET", query("/chat/sessions/detail", sessionId=session))
        require(detail["session"]["session_id"] == session and detail.get("agent"), "PRIOR_SESSION_AGENT_REQUIRED")
        agent_id = str(detail["agent"]["id"])
        agent = self.institution.request("GET", query("/agents/detail", agentId=agent_id))
        budget = agent["execution_config"]
        require(agent["model_config_id"] == self.model and agent.get("published_revision")
                and agent.get("has_draft_changes") is False and budget["max_business_model_calls"] == 3
                and budget["max_tool_calls"] == 4 and budget["max_total_tokens"] == 64_000,
                "PRIOR_AGENT_SOURCE_OR_BUDGET_DRIFT")
        return agent_id, agent

    def execute(self):
        baseline = self.guard.check()
        require(baseline["calls"] == self.prior["provider"]["calls"], "SAME_PARENT_BATCH_PROVIDER_BOUNDARY_REQUIRED")
        self.journal.value["parentCallsBeforeSupplement"] = baseline["calls"]; self.journal.flush()
        self.platform.login(os.environ["SF_PLATFORM_USER"], os.environ["SF_PLATFORM_PASSWORD"])
        self.institution.login(self.ready["tenantUsername"], self.password, self.batch)
        selected = self.platform.request("GET", query("/admin/models/detail", id=self.model))
        require(selected["published_revision"] == self.model_revision and selected.get("credential_configured") is True,
                "PRIOR_PUBLISHED_REAL_MODEL_SOURCE_DRIFT")
        prior_cases = self.prior["cases"]
        old_nodes = [self.read_prior_root(item) for item in prior_cases]
        _, parent = self.checked_agent(prior_cases[2]["sessionId"])
        targets = parent["delegation_config"]["target_agent_ids"]
        children = [node for node in old_nodes[2] if node["kind"] == "AGENT" and node["run_id"] != prior_cases[2]["runId"]]
        require(len(targets) == 1 and children and all(row["status"] == "COMPLETED" for row in children), "PRIOR_REAL_DELEGATION_REQUIRED")
        agent, recovery = self.checked_agent(prior_cases[3]["sessionId"])
        mounts = [mount for mount in recovery["mounts"] if mount["capability_type"] == "TOOL"]
        require(len(mounts) == 1 and mounts[0]["capability_definition_id"] == self.prior["resources"]["definitionId"]
                and mounts[0]["capability_version_id"] == self.prior["resources"]["versionId"]
                and mounts[0]["status"] == "ACTIVE", "PRIOR_TOOL_MOUNT_SOURCE_DRIFT")
        tool_config = mounts[0]["tool_config"]
        deployment = self.institution.request("GET", query("/capabilities/deployments/detail", deploymentKey=tool_config["tool_deployment_id"]))
        require(deployment["provider_type"] == "MCP" and deployment["provider_ref"] == self.prior["resources"]["connectorId"]
                and deployment["revision"] == tool_config["tool_deployment_revision"]
                and str(deployment["tool_version_id"]) == self.prior["resources"]["versionId"], "PRIOR_MCP_DEPLOYMENT_SOURCE_DRIFT")
        failed = known_failed_tool(prior_cases[3], old_nodes[3])
        connector = self.institution.request("GET", query("/connectors/instances/detail", instanceId=self.prior["resources"]["connectorId"]))
        port = exact_fixture_port(connector)
        self.server = stopped_fixture(port, self.batch)
        self.server.store.create(self.case_key, {"verified_value": 391, "marker": "OBSERVATION-RECOVERED"}, [{"mode": "success"}])
        self.mcp_fixture_created = True
        self.thread = threading.Thread(target=self.server.serve_forever, name="observation-user-retry-owned-mcp", daemon=False)
        self.thread.start()
        self.journal.value["state"] = "RUNNING"; self.journal.flush()
        original = prior_cases[3]
        retried, nodes = self.turn(original["sessionId"], agent, "服务已恢复。现请重新读取同一case_key=" + self.case_key
            + "，本轮只实际调用一次已挂载的长名称纯只读工具，报告该次真实成功回执中的verified_value和marker，不要沿用上一轮失败结果或猜测数值。")
        audit = self.capture_mcp_receipts()
        succeeded = [node for node in nodes if node["kind"] == "TOOL"]
        require(len(succeeded) == 1 and succeeded[0]["status"] == "SUCCEEDED" and succeeded[0]["name"] == failed["name"],
                "ONE_NEW_SUCCESSFUL_SAME_TOOL_REQUIRED")
        require(len(audit["calls"]) == 1 and audit["calls"][0]["tool"] == LONG_TOOL
                and audit["calls"][0]["result"].get("isError") is False
                and not self.journal.value["unassignedToolReceipts"], "ONE_NEW_MCP_SUCCESS_RECEIPT_REQUIRED")
        recovery_times(failed, succeeded[0])
        require(retried["rootInputId"] != original["rootInputId"] and retried["runId"] != original["runId"], "USER_RETRY_REQUIRES_NEW_INPUT")
        require_answer(self.answers[(original["sessionId"], retried["rootInputId"])], ("391", "OBSERVATION-RECOVERED"))
        root = lambda item: {"rootInputId": item["rootInputId"], "runId": item["runId"]}
        self.ready["cases"] = [
            {"id": "multi-round", "kind": "MULTI_ROUND", "sessionId": prior_cases[0]["sessionId"], "roots": [root(prior_cases[0]), root(prior_cases[1])]},
            {"id": "delegation", "kind": "DELEGATION", "sessionId": prior_cases[2]["sessionId"], "roots": [root(prior_cases[2])],
             "expectedChildAgentId": targets[0], "expectedChildSessionId": children[0]["source_session_id"]},
            {"id": "user-next-input-recovery", "kind": "TOOL_RECOVERY", "sessionId": original["sessionId"],
             "roots": [root(original), retried], "toolName": succeeded[0]["name"], "recoveryMode": "USER_NEXT_INPUT"},
            {"id": "long-tool-name", "kind": "LONG_TOOL_NAME", "sessionId": original["sessionId"], "roots": [retried], "toolName": succeeded[0]["name"]}]
        final = self.guard.check()
        self.journal.value["supplementModelCalls"] = final["calls"] - baseline["calls"]
        self.journal.value["state"] = "PASSED"; self.journal.flush()
        save(self.journal.root / "ready.json", self.ready)

    def close(self):
        if self.server is None:
            return
        try:
            if self.mcp_fixture_created:
                self.capture_mcp_receipts()
        finally:
            thread = getattr(self, "thread", None)
            try:
                if thread is not None and thread.is_alive():
                    self.server.shutdown()
            finally:
                self.server.server_close()
            if thread is not None and thread.ident is not None:
                thread.join(timeout=10)
                require(not thread.is_alive(), "OWNED_MCP_THREAD_NOT_STOPPED")


def main():
    journal = Journal(os.environ["OBSERVATION_OUTPUT_DIR"])
    run = None
    try:
        run = UserRetry(journal, os.environ["OBSERVATION_PRIOR_BATCH"])
        run.execute()
    except Exception as failure:
        journal.value["state"] = "FAILED"
        journal.value["failureCode"] = str(failure) if isinstance(failure, Stopped) else "SUPPLEMENT_EXCEPTION_" + type(failure).__name__
        journal.flush()
        print(json.dumps({"state": "FAILED", "failureCode": journal.value["failureCode"]}))
        return 1
    finally:
        if run:
            run.close()
    print(json.dumps({"state": "PASSED", "ready": str(journal.root / "ready.json"),
                      "supplementCalls": journal.value["supplementModelCalls"], "sameTenantCumulativeCalls": journal.value["provider"]["calls"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
