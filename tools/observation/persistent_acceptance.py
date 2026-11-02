"""显式创建持久联调轨迹；正式HTTP写入只发送一次，模型/工具均走现有宿主。

输入只取环境：SF_PLATFORM_USER/PASSWORD、OBSERVATION_MODEL_ID、OBSERVATION_OUTPUT_DIR、
OBSERVATION_PG_CONTAINER/PG_USER/PG_DATABASE；URL可覆盖8000/8100，不安装数据库客户端依赖。
新机构随机口令仅保存在ignored批次的private.json；ready.json无口令、token、正文。
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools" / "chat_regression"))
from mcp_fixture import McpFixtureServer  # noqa: E402

MAX_CALLS, MAX_TOKENS, MAX_SECONDS, TURN_RESERVATION = 64, 400_000, 1800, 16
LONG_TOOL = "read_observation_acceptance_record_with_recovery_exact_identity"
TERMINALS = {"completed", "failed", "cancelled", "terminated", "loop_terminated"}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """认证请求不跟随重定向，避免凭据外发和未知写入的自动重发。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Stopped(RuntimeError):
    """只暴露固定安全原因，不携带响应正文、URL查询或凭据。"""


def require(value, code):
    if not value:
        raise Stopped(code)
    return value


def save(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def intent_identity(value):
    """凭据不进入公开请求摘要，连可供离线猜测的口令派生hash也不保存。"""
    if isinstance(value, dict):
        return {key: "REDACTED" if any(part in key.lower() for part in ("password", "credential", "token", "secret"))
                else intent_identity(item) for key, item in value.items()}
    if isinstance(value, list):
        return [intent_identity(item) for item in value]
    return value






def require_answer(answer, expected):
    require(isinstance(answer, str) and answer.strip(), "PERSISTED_PUBLIC_ANSWER_REQUIRED")
    for term in expected:
        require(re.search(r"(?<!\d)" + re.escape(term) + r"(?!\d)", answer) if term.isdigit() else term in answer,
                "PERSISTED_PUBLIC_ANSWER_FACT_MISMATCH")


def safe_tool_receipts(calls):
    """只保存实际回执的有限诊断字段；参数、正文与原始错误消息永不进入公开状态。"""
    result = []
    for call in calls:
        response = call.get("result")
        structured = response.get("structuredContent") if isinstance(response, dict) else None
        code = structured.get("error") if isinstance(structured, dict) else None
        code = code if isinstance(code, str) and re.fullmatch(r"[a-z][a-z0-9_]{0,79}", code) else None
        name = call.get("tool")
        name = name if isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", name) else "UNRECOGNIZED_TOOL"
        result.append({"sequence": call.get("sequence"), "toolName": name,
            "isError": response.get("isError") if isinstance(response, dict) and isinstance(response.get("isError"), bool) else None,
            "errorCode": code, "resultHash": digest(response) if response is not None else None})
    return result


def host_url(value):
    uri = urllib.parse.urlsplit(value)
    require(uri.scheme == "http" and uri.hostname in {"127.0.0.1", "localhost"} and uri.port
            and uri.path.rstrip("/") == "/api/v1" and not uri.query and not uri.fragment and not uri.username,
            "LOCAL_HOST_URL_REQUIRED")
    return value.rstrip("/")


class Journal:
    def __init__(self, root):
        self.root = Path(root).resolve()
        require(self.root.is_relative_to(REPO / ".runtime-logs") and not self.root.exists(), "NEW_IGNORED_BATCH_REQUIRED")
        self.root.mkdir(parents=True, exist_ok=False)
        self.value = {"version": 1, "state": "PREPARING", "operations": [], "cases": [], "provider": None,
                      "budget": {"maximumCalls": MAX_CALLS, "maximumTokens": MAX_TOKENS,
                                 "maximumSeconds": MAX_SECONDS, "perTurnReservation": TURN_RESERVATION}}
        self.flush()

    def flush(self):
        save(self.root / "state.json", self.value)

    def intent(self, method, path, body):
        item = {"number": len(self.value["operations"]) + 1, "method": method,
                "path": path.split("?", 1)[0], "requestHash": digest(intent_identity(body)), "state": "DISPATCHING"}
        self.value["operations"].append(item); self.flush()
        return item


class Host:
    def __init__(self, base, journal, platform=False):
        self.base, self.journal, self.platform = host_url(base), journal, platform
        self.token = None
        self.guard = None
        self.next_request = 0.0

    def request(self, method, path, body=None, headers=None):
        require(path.startswith("/") and not path.startswith("//"), "RELATIVE_API_PATH_REQUIRED")
        if method != "GET" and self.guard:
            self.guard.check(observe=path in {"/chat/runs/cancel", "/chat/work/controls"}, new_input=path == "/chat")
        item = self.journal.intent(method, path, body) if method != "GET" else None
        wait = max(0, self.next_request - time.monotonic())
        if wait:
            time.sleep(wait)
        self.next_request = time.monotonic() + 0.6
        actual_headers = {"Content-Type": "application/json", **(headers or {})}
        if self.token:
            actual_headers["X-Platform-Token" if self.platform else "Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(self.base + path, data=None if body is None else json.dumps(body).encode(),
                                         headers=actual_headers, method=method)
        try:
            for attempt in range(2):
                try:
                    with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
                        raw = response.read(8 * 1024 * 1024 + 1)
                        require(len(raw) <= 8 * 1024 * 1024, "API_RESPONSE_TOO_LARGE")
                        envelope = json.loads(raw)
                        require(envelope.get("code") == "OK", "API_ENVELOPE_REJECTED")
                        value = envelope["data"]
                        if item:
                            item["state"] = "CONFIRMED"
                            if isinstance(value, dict):
                                item["receipt"] = {key: value[key] for key in ("id", "session_id", "execution_id", "instance_id", "revision", "published_revision")
                                                   if key in value and isinstance(value[key], (str, int))}
                            self.journal.flush()
                        return value
                except urllib.error.HTTPError as failure:
                    retry = failure.headers.get("Retry-After", "")
                    failure.close()
                    if method == "GET" and attempt == 0 and failure.code == 429 and retry.isdigit() and self.guard:
                        seconds = int(retry)
                        if seconds + 30 < self.guard.remaining():
                            for _ in range(seconds):
                                time.sleep(1); self.guard.check(observe=True)
                            continue
                    raise Stopped("HTTP_%s_%s" % (method, failure.code)) from None
        except Exception as failure:
            if item:
                item["state"] = "UNCONFIRMED"; self.journal.flush()
            if isinstance(failure, Stopped):
                raise
            raise Stopped("HTTP_OUTCOME_UNCONFIRMED") from None

    def login(self, username, password, slug=None):
        body = {"username": username, "password": password}
        if not self.platform:
            body["tenant_slug"] = slug
        value = self.request("POST", "/platform/auth/login" if self.platform else "/auth/login", body)
        self.token = require(value.get("access_token"), "LOGIN_TOKEN_MISSING")
        return value


class ProviderGuard:
    """每次读取短只读事务，按新机构精确隔离；所有辅助及委派模型尝试均计入。"""
    def __init__(self, tenant, journal):
        self.tenant, self.journal = int(tenant), journal
        require(self.tenant > 0, "POSITIVE_TENANT_REQUIRED")
        self.container, self.user, self.database = (os.environ[key] for key in
            ("OBSERVATION_PG_CONTAINER", "OBSERVATION_PG_USER", "OBSERVATION_PG_DATABASE"))
        require(all(re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", value) for value in
                    (self.container, self.user, self.database)), "EXPLICIT_POSTGRES_TARGET_REQUIRED")
        self.started = time.monotonic()

    def remaining(self):
        return max(0, MAX_SECONDS - (time.monotonic() - self.started))

    def snapshot(self):
        try:
            # 所有插值只来自已校验正整数tenant；schema/table固定，不接受任意SQL或原提示。
            sql = f"""BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
                SET LOCAL statement_timeout='15s';
                SET LOCAL app.tenant_id = '{self.tenant}';
                SELECT json_build_object('invocations', COALESCE((SELECT json_agg(row_to_json(i)) FROM
                  (SELECT attempt.attempt_id,
                          CASE WHEN action.kind='MODEL' THEN 'model.chat' ELSE lower(action.kind) END AS operation_type,
                          CASE WHEN attempt.result_kind IS NULL THEN 'STARTED'
                               WHEN attempt.result_kind='UNKNOWN' THEN 'UNRESOLVED'
                               ELSE attempt.result_kind END AS status
                     FROM agent_runtime_execution_attempts attempt
                     JOIN agent_runtime_execution_actions action
                       ON action.tenant_id=attempt.tenant_id AND action.action_id=attempt.action_id
                    WHERE attempt.tenant_id='{self.tenant}'
                    ORDER BY attempt.observation_sequence LIMIT 129) i),'[]'::json),
                  'auxiliary', COALESCE((SELECT json_agg(row_to_json(i)) FROM
                  (SELECT invocation.attempt_id,invocation.operation_type,invocation.status
                     FROM agent_runtime_budget_attempts budget
                     JOIN agent_runtime_execution_runs run
                       ON run.tenant_id=budget.tenant_id AND run.run_id=budget.run_id
                     JOIN nw_external_invocations invocation
                       ON invocation.tenant_id::text=budget.tenant_id AND invocation.attempt_id=budget.attempt_id
                    WHERE budget.tenant_id='{self.tenant}' AND budget.category='AUXILIARY_MODEL'
                      AND invocation.operation_type IN ('model.chat','model.embedding','model.rerank')
                      AND NOT EXISTS(SELECT 1 FROM agent_runtime_execution_attempts core
                        WHERE core.tenant_id=budget.tenant_id AND core.attempt_id=budget.attempt_id)
                    ORDER BY invocation.id LIMIT 129) i),'[]'::json),
                  'usage', COALESCE((SELECT json_agg(row_to_json(u)) FROM
                  (SELECT attempt_id,prompt_tokens,completion_tokens,model FROM observation_effective_model_usage
                   WHERE tenant_id={self.tenant} ORDER BY id LIMIT 65) u),'[]'::json));
                ROLLBACK;"""
            process = subprocess.run(["docker", "exec", "-i", self.container, "psql", "-X", "-qAt",
                "-v", "ON_ERROR_STOP=1", "-U", self.user, "-d", self.database], input=sql,
                capture_output=True, text=True, encoding="utf-8", timeout=20, check=False)
            require(process.returncode == 0, "PROVIDER_FACTS_QUERY_FAILED")
            facts = json.loads(process.stdout.strip())
            auxiliary = facts.get("auxiliary", [])
            require(len(facts["invocations"]) < 129 and len(auxiliary) < 129, "INVOCATION_FACT_BOUND_EXCEEDED")
            # 同一显式租户内完整 Attempt 身份唯一；业务来源优先，辅助重复不得重复计费。
            indexed = {row["attempt_id"]: (row["attempt_id"], row["operation_type"], row["status"])
                       for row in facts["invocations"]}
            for row in auxiliary:
                indexed.setdefault(row["attempt_id"], (row["attempt_id"], row["operation_type"], row["status"]))
            rows = list(indexed.values())
            require(all(row.get("model") == "deepseek-flash" for row in facts["usage"]), "DEEPSEEK_FLASH_USAGE_REQUIRED")
            usage = [(row["attempt_id"], row["prompt_tokens"], row["completion_tokens"]) for row in facts["usage"]]
            require(len(rows) < 129 and len(usage) < 65, "INVOCATION_FACT_BOUND_EXCEEDED")
            models = [row for row in rows if row[1] in {"model.chat", "model.embedding", "model.rerank"}]
            by_attempt = {}
            for attempt, prompt, completion in usage:
                require(attempt not in by_attempt, "MODEL_USAGE_IDENTITY_CONFLICT")
                by_attempt[attempt] = (prompt, completion)
            attempts = []
            for attempt, operation, status in models:
                prompt, completion = by_attempt.get(attempt, (None, None))
                attempts.append({"attemptId": attempt, "operation": operation, "status": status,
                                 "promptTokens": prompt, "completionTokens": completion})
            receipt = {"calls": len(models), "tokens": sum((r["promptTokens"] or 0) + (r["completionTokens"] or 0) for r in attempts),
                       "pending": sum(r["status"] == "STARTED" for r in attempts),
                       "unknown": sum(row[2] == "UNRESOLVED" for row in rows),
                       "failedModels": sum(r["status"] == "FAILED" for r in attempts),
                       "missingUsage": sum(r["status"] == "SUCCEEDED" and (r["promptTokens"] is None or r["completionTokens"] is None) for r in attempts),
                       "attempts": attempts}
            self.journal.value["provider"] = receipt; self.journal.flush()
            return receipt
        except Stopped:
            raise
        except Exception:
            raise Stopped("PROVIDER_FACTS_UNAVAILABLE") from None

    def storage_snapshot(self):
        # 仅扫描本批独立机构的有界行；不以全库物理尺寸冒充本批归属。
        tables = ("agent_runtime_execution_runs", "agent_runtime_execution_actions",
                  "agent_runtime_execution_attempts", "agent_runtime_execution_events",
                  "agent_runtime_execution_materials")
        columns = []
        for table in tables:
            columns.append("'%s',(SELECT json_build_object('rows',count(*),'rowBytes',COALESCE(sum(pg_column_size(r)),0)) FROM (SELECT * FROM %s WHERE tenant_id='%s' LIMIT 10001) r)" % (table, table, self.tenant))
        sql = "BEGIN READ ONLY; SET LOCAL statement_timeout='15s'; SET LOCAL app.tenant_id='%s'; SELECT json_build_object(%s); ROLLBACK;" % (self.tenant, ",".join(columns))
        result = subprocess.run(["docker", "exec", "-i", self.container, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                                 "-U", self.user, "-d", self.database], input=sql, capture_output=True,
                                text=True, encoding="utf-8", timeout=20, check=False)
        require(result.returncode == 0, "STORAGE_FACTS_QUERY_FAILED")
        facts = json.loads(result.stdout.strip())
        require(all(value["rows"] <= 10000 for value in facts.values()), "STORAGE_FACT_BOUND_EXCEEDED")
        return facts

    def check(self, *, observe=False, new_input=False):
        facts = self.snapshot()
        require(not facts["unknown"] and not facts["failedModels"] and not facts["missingUsage"], "SUPPLIER_FAILED_OR_UNKNOWN")
        require(facts["calls"] <= MAX_CALLS and facts["tokens"] <= MAX_TOKENS and self.remaining() > 0, "BATCH_BUDGET_EXHAUSTED")
        if not observe:
            require(facts["pending"] == 0, "PREVIOUS_MODEL_ATTEMPT_PENDING")
        if new_input:
            require(facts["calls"] + TURN_RESERVATION <= MAX_CALLS, "CONSERVATIVE_CALL_RESERVATION_UNAVAILABLE")
        return facts


def query(path, **parameters):
    return path + "?" + urllib.parse.urlencode(parameters)


class Acceptance:
    def __init__(self, journal):
        self.journal = journal
        self.platform = Host(os.environ.get("OBSERVATION_PLATFORM_URL", "http://127.0.0.1:8100/api/v1"), journal, True)
        self.institution = Host(os.environ.get("OBSERVATION_INSTITUTION_URL", "http://127.0.0.1:8000/api/v1"), journal)
        self.batch = "obs-" + time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
        self.journal.value["batch"] = self.batch; self.journal.flush()
        self.model = os.environ["OBSERVATION_MODEL_ID"]
        self.server = None
        self.answers = {}
        self.guard = None
        self.ready = {"version": 1, "institutionBackendURL": self.institution.base.removesuffix("/api/v1"),
                      "platformBackendURL": self.platform.base.removesuffix("/api/v1"), "cases": []}

    def prepare(self):
        self.platform.login(os.environ["SF_PLATFORM_USER"], os.environ["SF_PLATFORM_PASSWORD"])
        models = self.platform.request("GET", "/admin/models")
        selected = next((value for value in models if str(value.get("id")) == self.model), None)
        require(selected and selected.get("kind") == "LLM" and selected.get("enabled") is True
                and selected.get("published_revision") and selected.get("credential_configured") is True,
                "PUBLISHED_REAL_MODEL_REQUIRED")
        require(selected.get("has_unpublished_changes") is False, "PUBLISHED_MODEL_DRAFT_DRIFT")
        require(selected.get("model_id") == "deepseek-flash", "DEEPSEEK_FLASH_REQUIRED")
        self.model_revision = selected["published_revision"]
        self.journal.value["model"] = {key: selected.get(key) for key in
            ("id", "published_revision", "provider_id", "model_id", "max_output_tokens", "reasoning")}
        self.journal.flush()
        password, username = "Obs" + secrets.token_urlsafe(24) + "7", self.batch + "-admin"
        save(self.journal.root / "private.json", {"institutionalPassword": password})
        tenant = self.platform.request("POST", "/admin/tenants", {"tenant_name": "持久观测验收 " + self.batch,
            "tenant_slug": self.batch, "plan_code": "free", "admin_username": username, "admin_password": password})
        self.tenant = str(tenant["id"])
        self.guard = ProviderGuard(self.tenant, self.journal)
        require(self.guard.check()["calls"] == 0, "NEW_TENANT_MODEL_BASELINE_REQUIRED")
        self.platform.guard = self.institution.guard = self.guard
        self.institution.login(username, password, self.batch)
        self.ready.update(tenantId=self.tenant, tenantSlug=self.batch, tenantUsername=username,
                          platformUsername=os.environ["SF_PLATFORM_USER"])
        self.institution.request("PUT", "/settings/model-selections", {"llm_model_config_id": self.model,
            "embedding_model_config_id": None, "rerank_model_config_id": None})
        self.user = str(self.institution.request("POST", "/chat/end-users", {"display_name": self.batch + " 终端用户"})["id"])
        self.server = McpFixtureServer(0, self.batch, query_alias=LONG_TOOL)
        self.thread = threading.Thread(target=self.server.serve_forever, name="observation-owned-mcp", daemon=False)
        self.thread.start()
        self.case_key = self.batch + ":recovery"
        self.server.store.create(self.case_key, {"verified_value": 391, "marker": "OBSERVATION-RECOVERED"},
                                 [{"mode": "unavailable"}, {"mode": "success"}])
        connector = self.institution.request("POST", "/connectors/instances", {"executor_type": "mcp",
            "name": self.batch + " 长名称只读工具", "base_url": "http://127.0.0.1:%d/mcp" % self.server.server_address[1],
            "auth_type": "none", "capability_mapping": {}, "headers": {}})
        self.connector = connector["instance_id"]
        discovery = self.institution.request("GET", query("/connectors/instances/mcp-tools", instanceId=self.connector))
        tool = next((row for row in discovery["items"] if row.get("name") == LONG_TOOL), None)
        require(tool and len(LONG_TOOL) <= 64, "LONG_MCP_TOOL_NOT_DISCOVERED")
        definition = self.institution.request("POST", "/capabilities/definitions", {"capability_type": "TOOL",
            "capability_key": self.batch + ":long-query", "name": LONG_TOOL, "description": tool["description"]})
        descriptor = {"tool_name": LONG_TOOL, "model_invocable": True, "user_invocable": False,
            "input_schema": tool["input_schema"], "output_schema": tool["output_schema"], "effect": "READ_ONLY",
            "idempotent": True, "confirmation_policy": "NEVER", "timeout_ms": 30_000,
            "sensitive_fields": [], "provider_requirements": [], "parallel_safe": False}
        version = self.institution.request("POST", "/capabilities/definitions/versions", {"definition_id": definition["id"],
            "payload": {"version_key": self.batch + ":v1", "name": LONG_TOOL, "description": tool["description"],
                        "content_hash": digest(descriptor), "descriptor": descriptor}})
        validation = self.institution.request("POST", "/capabilities/definitions/versions/validate",
                                              {"definition_id": definition["id"], "version_id": version["id"]})
        require(validation.get("valid") is True, "LONG_TOOL_VALIDATION_FAILED")
        current = self.institution.request("GET", query("/capabilities/definitions/detail", definitionId=definition["id"]))
        self.institution.request("POST", "/capabilities/definitions/versions/publish",
            {"definition_id": definition["id"], "version_id": version["id"]}, {"If-Match": str(current["revision"])})
        deployment = self.institution.request("POST", "/capabilities/deployments", {"deployment_key": self.batch + "-query",
            "tool_version_id": version["id"], "provider_type": "MCP", "provider_ref": self.connector,
            "environment": "TEST", "status": "ACTIVE"})
        self.mount = {"capability_type": "TOOL", "capability_definition_id": str(definition["id"]),
            "capability_version_id": str(version["id"]), "tool_config": {"tool_deployment_id": deployment["deployment_key"],
            "tool_deployment_revision": deployment["revision"]}, "priority": 0}
        self.journal.value["resources"] = {"tenantId": self.tenant, "endUserId": self.user, "modelId": self.model,
            "connectorId": self.connector, "definitionId": str(definition["id"]), "versionId": str(version["id"])}
        self.journal.flush()


    def agent(self, label, prompt, *, mount=False, delegate=None):
        row = self.institution.request("POST", "/agents", {"agent_key": self.batch + "-" + label,
            "name": "观测验收 " + label, "system_prompt": prompt, "model_config_id": self.model,
            "config": {"memory": {"automatic_extraction": False}}})
        agent = str(row["id"])
        payload = {"execution_config": {"max_business_model_calls": 12, "max_tool_calls": 12,
            "max_knowledge_calls": 1, "max_total_tokens": 150_000, "max_wall_time_seconds": 600}}
        if delegate:
            payload["delegation_config"] = {"enabled": True, "target_agent_ids": [delegate]}
        self.institution.request("PATCH", "/agents/update", {"agent_id": agent, "payload": payload})
        if mount:
            self.institution.request("POST", "/agents/capability-mounts", {"agent_id": agent, "payload": self.mount})
        frozen = self.institution.request("GET", query("/agents/detail", agentId=agent))
        require(frozen["model_config_id"] == self.model and all(frozen["execution_config"].get(key) == value
                for key, value in payload["execution_config"].items()), "AGENT_BUDGET_SOURCE_MISMATCH")
        if delegate:
            require(frozen["delegation_config"]["enabled"] is True
                    and frozen["delegation_config"]["target_agent_ids"] == [delegate], "AGENT_DELEGATION_SOURCE_MISMATCH")
        self.institution.request("POST", "/agents/publish", {"agent_id": agent})
        return agent

    def session(self, agent):
        return str(self.institution.request("POST", "/chat/sessions", {"agent_id": agent, "end_user_id": self.user})["session_id"])

    def turn(self, session, agent, text, expected=(), *, approve_plan=False, cancel_plan=False):
        selected = self.platform.request("GET", query("/admin/models/detail", id=self.model))
        require(selected["published_revision"] == self.model_revision, "PUBLISHED_MODEL_SOURCE_DRIFT")
        message = str(uuid.uuid4())
        accepted = self.institution.request("POST", "/chat", {"session_id": session, "agent_id": agent,
            "client_message_id": message, "content": [{"type": "text", "text": text}], "context": {"end_user_id": self.user}})
        run = require(accepted.get("execution_id"), "CHAT_RUN_RECEIPT_REQUIRED")
        root = {"rootInputId": message, "runId": run}
        self.journal.value["cases"].append({"sessionId": session, **root, "state": "ACCEPTED"}); self.journal.flush()
        deadline = min(time.monotonic() + 360, self.guard.started + MAX_SECONDS)
        controlled = False
        approved = None
        while time.monotonic() < deadline:
            if (approve_plan or cancel_plan) and not controlled:
                work = self.institution.request("GET", query("/chat/work", session_id=session, work_id=message))
                if work and work.get("plan_revision", 0) > 0:
                    require(work.get("approval_required") is True and not work.get("approved_revision"), "UNAPPROVED_PLAN_REQUIRED")
                    require(all(not task.get("run_id") for task in work["tasks"] if task.get("executor_type") == "MEMBER"),
                            "MEMBER_STARTED_BEFORE_APPROVAL")
                    operation = "cancel" if cancel_plan else "approve_plan"
                    self.institution.request("POST", "/chat/work/controls", {"session_id": session, "input_id": str(uuid.uuid4()),
                        "work_id": message, "task_id": "", "operation": operation, "content": "",
                        "plan_revision": work["plan_revision"], "pending_id": "", "parameters_version": "", "agent_id": agent})
                    approved = work["plan_revision"]
                    controlled = True
                    self.journal.value["cases"][-1]["control"] = {"operation": operation, "planRevision": approved}
                    self.journal.flush()
            status = self.institution.request("GET", query("/chat/runs/status", session_id=session, run_id=run))
            facts = self.guard.check(observe=True)
            if status.get("status") in TERMINALS:
                if cancel_plan:
                    require(controlled and status["status"] in {"cancelled", "terminated"}, "PLAN_CANCEL_NOT_SETTLED")
                    require(facts["pending"] == 0, "CANCELLED_PROVIDER_ATTEMPT_PENDING")
                    self.journal.value["cases"][-1]["state"] = "CANCELLED"; self.journal.flush()
                    return root, []
                require(status["status"] == "completed", "BUSINESS_RUN_NOT_COMPLETED")
                if approve_plan:
                    require(controlled, "PLAN_APPROVAL_NOT_EXERCISED")
                    work = self.institution.request("GET", query("/chat/work", session_id=session, work_id=message))
                    if work.get("stage") != "finished":
                        time.sleep(1)
                        continue
                    tasks = work["tasks"]
                    require(work.get("approved_revision") == approved and work.get("stage") == "finished"
                        and len(tasks) >= 2 and any(task.get("depends_on") for task in tasks)
                        and all(task["status"] == "completed" for task in tasks), "APPROVED_DAG_NOT_CLOSED")
                    self.journal.value["cases"][-1]["dag"] = {"taskCount": len(tasks), "revision": work["revision"]}
                if facts["pending"] == 0:
                    self.journal.value["cases"][-1]["state"] = "COMPLETED"; self.journal.flush()
                    return root, self.nodes(session, message, expected)
            time.sleep(1)
        raise Stopped("RUN_WAIT_EXPIRED_NO_REPLAY")

    def nodes(self, session, root, expected=()):
        detail = self.institution.request("GET", query("/chat/trajectories/detail", session_id=session, root_input_id=root))
        require(detail["summary"]["coverage"]["status"] == "COMPLETE", "TRAJECTORY_COVERAGE_INCOMPLETE")
        answer = detail["summary"].get("output")
        require_answer(answer, expected)
        self.answers[(session, root)] = answer
        self.journal.value["cases"][-1]["answerHash"] = digest(answer); self.journal.flush()
        snapshot, cursor, result = detail["snapshot"], None, []
        for _ in range(10):
            page = self.institution.request("GET", query("/chat/trajectories/nodes", session_id=session,
                root_input_id=root, snapshot=snapshot, page_size=200, **({"cursor": cursor} if cursor else {})))
            require(page["snapshot"] == snapshot and page["coverage"] == "COMPLETE", "TRAJECTORY_SNAPSHOT_INCOMPLETE")
            result.extend(page["items"])
            next_cursor = page.get("next_cursor")
            if not next_cursor:
                return result
            require(next_cursor != cursor, "TRAJECTORY_CURSOR_STALLED"); cursor = next_cursor
        raise Stopped("TRAJECTORY_PAGE_BOUND_EXCEEDED")

    def execute(self):
        self.prepare(); self.journal.value["state"] = "RUNNING"
        self.journal.value["storageBefore"] = self.guard.storage_snapshot(); self.journal.flush()
        direct = self.agent("multi-round", "你是简洁可靠的中文助手，记住同会话用户给出的代号，依据已给信息回答，不编造工具结果。")
        session = self.session(direct)
        first, _ = self.turn(session, direct, "请记住本会话的核验代号是青松17，仅确认已记住。")
        second, _ = self.turn(session, direct, "请回答刚才的核验代号，并解释17加23等于多少。简短回答。", ("青松17", "40"))
        self.ready["cases"].append({"id": "multi-round", "kind": "MULTI_ROUND", "sessionId": session, "roots": [first, second]})
        child = self.agent("member", "你是授权协作成员，完成主助手给出的简单算术并简短报告，不伪称调用工具。")
        parent = self.agent("coordinator", "用户要求委派时必须真实委派一次给已授权成员，持久等待成员完成后简短汇总；不要自己代算。", delegate=child)
        session = self.session(parent)
        delegated, nodes = self.turn(session, parent, "请委派给已授权成员计算45乘12，等待真实成员完成后给出汇总。", ("540",))
        children = [node for node in nodes if node["kind"] == "AGENT" and node["run_id"] != delegated["runId"]]
        require(children and all(row["status"] == "COMPLETED" for row in children), "REAL_DELEGATED_CHILD_REQUIRED")
        self.ready["cases"].append({"id": "delegation", "kind": "DELEGATION", "sessionId": session, "roots": [delegated],
            "expectedChildAgentId": child, "expectedChildSessionId": children[0]["source_session_id"]})
        recovery = self.agent("tool-recovery", "按用户授权执行纯只读查询。首次返回mcp_tool_unavailable时属于已确认读取失败，"
            "并非UNKNOWN；必须保持相同case_key再实际查询一次，最多共两次。只有真实成功回执才能提供verified_value和marker。"
            "UNKNOWN、超时或无法确认执行时停止，不重试。不能猜测结果、把失败当成功或在已授权的已知失败重试前直接结束。", mount=True)
        session = self.session(recovery)
        recovered, nodes = self.turn(session, recovery, "请使用已挂载的长名称只读查询工具读取case_key=" + self.case_key
            + "。本次明确授权纯只读故障探测：首次mcp_tool_unavailable表示本次读取已确认失败，不是未知结果；"
            "请用相同case_key再次实际调用一次，最多共两次。必须获得真实成功回执后，再报告其中的verified_value和marker；"
            "不能猜测或从错误中编造结果。若实际结果是UNKNOWN、超时或无法确认执行，则停止且不重试。")
        audit = self.capture_mcp_receipts()
        tools = sorted((node for node in nodes if node["kind"] == "TOOL"), key=lambda node: int(node["sequence"]))
        require(len(tools) == 2 and tools[0]["name"] == tools[1]["name"] and tools[0]["status"] == "FAILED"
                and tools[1]["status"] == "SUCCEEDED" and 48 <= len(tools[0]["name"]) <= 64, "REAL_LONG_TOOL_FAILURE_RECOVERY_REQUIRED")
        require(len(audit["calls"]) == 2 and all(row["tool"] == LONG_TOOL for row in audit["calls"])
                and audit["calls"][0]["result"].get("isError") is True and audit["calls"][1]["result"].get("isError") is False,
                "MCP_FAILURE_SUCCESS_RECEIPTS_REQUIRED")
        require_answer(self.answers[(session, recovered["rootInputId"])], ("391", "OBSERVATION-RECOVERED"))
        for kind in ("TOOL_RECOVERY", "LONG_TOOL_NAME"):
            self.ready["cases"].append({"id": kind.lower(), "kind": kind, "sessionId": session, "roots": [recovered],
                "toolName": tools[0]["name"], "logicalToolName": LONG_TOOL})
        planned = self.agent("approved-dag", "使用计划模式处理任务：必须提交至少两步有依赖的分工，第一步委派已授权成员计算，第二步读取第一步成果并汇总。先等待用户批准，禁止批准前执行。", delegate=child)
        session = self.session(planned)
        self.institution.request("POST", "/chat/runs/plan-mode", {"session_id": session, "command_id": str(uuid.uuid4()), "active": True})
        planned_root, _ = self.turn(session, planned, "请先制定需要用户批准的两步依赖计划：第一步让成员计算45乘12，第二步使用第一步结果加10。提交计划后等待批准。", ("550",), approve_plan=True)
        self.ready["cases"].append({"id": "approved-dag", "kind": "APPROVED_DAG", "sessionId": session, "roots": [planned_root]})
        cancelled_session = self.session(planned)
        self.institution.request("POST", "/chat/runs/plan-mode", {"session_id": cancelled_session, "command_id": str(uuid.uuid4()), "active": True})
        cancelled, _ = self.turn(cancelled_session, planned, "请制定同样的两步依赖计划，提交后等待用户批准，不要直接执行。", cancel_plan=True)
        self.institution.request("POST", "/chat/runs/plan-mode", {"session_id": cancelled_session, "command_id": str(uuid.uuid4()), "active": False})
        resumed, _ = self.turn(cancelled_session, planned, "上一轮已经取消。这是一条新的独立指令：请只回答17加23的结果，不执行之前的计划。", ("40",))
        require(cancelled["runId"] != resumed["runId"], "CANCEL_RECOVERY_NEW_RUN_REQUIRED")
        self.ready["cases"].append({"id": "cancel-new-input", "kind": "CANCEL_NEW_INPUT", "sessionId": cancelled_session, "roots": [cancelled, resumed]})
        self.guard.check()
        after = self.guard.storage_snapshot()
        before = self.journal.value["storageBefore"]
        self.journal.value["storageAfter"] = after
        self.journal.value["storageDelta"] = {table: {key: after[table][key] - before[table][key]
            for key in ("rows", "rowBytes")} for table in after}
        require(self.journal.value["storageDelta"]["agent_runtime_execution_runs"]["rows"] > 0, "EXECUTION_STORAGE_GROWTH_REQUIRED")
        self.journal.flush()
        save(self.journal.root / "ready.json", self.ready)
        self.journal.value["state"] = "PASSED"; self.journal.flush()

    def capture_mcp_receipts(self):
        audit = self.server.store.audit(self.case_key)
        self.journal.value["toolReceipts"] = safe_tool_receipts(audit["calls"])
        self.journal.value["unassignedToolReceipts"] = safe_tool_receipts(self.server.store.audit_unassigned()["calls"])
        self.journal.flush()
        return audit

    def close(self):
        if self.server:
            try:
                if hasattr(self, "case_key"):
                    self.capture_mcp_receipts()
            finally:
                if self.thread.is_alive():
                    self.server.shutdown()
                self.server.server_close(); self.thread.join(timeout=10)
                require(not self.thread.is_alive(), "OWNED_MCP_THREAD_NOT_STOPPED")


def main():
    journal = Journal(os.environ["OBSERVATION_OUTPUT_DIR"])
    run = Acceptance(journal)
    try:
        run.execute()
    except Exception as failure:
        journal.value["state"] = "FAILED"
        journal.value["failureCode"] = str(failure) if isinstance(failure, Stopped) else "HARNESS_EXCEPTION_" + type(failure).__name__
        journal.flush()
        print(json.dumps({"state": "FAILED", "failureCode": journal.value["failureCode"]}))
        return 1
    finally:
        run.close()
    print(json.dumps({"state": "PASSED", "ready": str(journal.root / "ready.json"), "calls": journal.value["provider"]["calls"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
