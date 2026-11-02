"""Nonpaid Team/Agent/empty Session directory lifecycles on fresh owned tenants."""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request

import foundation_lifecycle_acceptance as foundation
from host_business_acceptance import check, now, redact
from run_suite import REPO, load_environment, save

G = "/api/v1/agents"
T = "/api/v1/teams"
S = "/api/v1/chat/sessions"
U = "/api/v1/chat/end-users"
READ = foundation.READ + ["foundation.teams.read", "foundation.agents.read", "foundation.resource.agent.read", "foundation.sessions.read"]
ROUTES = {
    G: {"GET", "POST"}, G + "/detail": {"GET"}, G + "/update": {"PATCH"}, G + "/publish": {"POST"},
    G + "/revisions": {"GET"}, G + "/revisions/detail": {"GET"}, G + "/templates": {"GET"},
    G + "/deployments": {"GET"}, G + "/deployments/detail": {"GET"},
    T: {"GET", "POST"}, T + "/update": {"PUT"}, T + "/publish": {"POST"}, T + "/revisions": {"GET"},
    S: {"GET", "POST"}, S + "/detail": {"GET"}, S + "/workspace": {"GET"}, S + "/close": {"POST"}, U: {"GET", "POST"},
}
SOURCES = [
    "backend-java/skillforge-institution-service/src/main/java/com/skillforge/institution/team/TeamController.java",
    "backend-java/skillforge-institution-service/src/main/java/com/skillforge/institution/team/TeamService.java",
    "backend-java/skillforge-institution-service/src/main/java/com/skillforge/institution/team/TeamDefinition.java",
    "backend-java/skillforge-institution-service/src/main/java/com/skillforge/institution/team/TeamMemberProfiles.java",
    "backend-java/skillforge-core-agent/src/main/java/com/skillforge/core/agent/AgentPublicationService.java",
    "backend-java/skillforge-institution-service/src/main/java/com/skillforge/institution/controller/AgentController.java",
    "backend-java/skillforge-institution-service/src/main/java/com/skillforge/institution/controller/AgentDeploymentController.java",
    "backend-java/skillforge-institution-service/src/main/java/com/skillforge/institution/service/AgentManagementService.java",
    "backend-java/skillforge-institution-service/src/main/java/com/skillforge/institution/workspace/SessionWorkspaceService.java",
    "backend-java/skillforge-institution-service/src/main/java/com/skillforge/institution/controller/ChatEndUserController.java",
    "backend-java/skillforge-core-chat/src/main/java/com/skillforge/core/chat/ChatSessionService.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/web/HostExceptionHandler.java",
]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """认证请求不跟随重定向，避免凭据外发和未知写入的自动重发。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Api(foundation.Api):
    def call(self, method, path, token=None, body=None, query=None, status=200, code="OK", platform=False, **kwargs):
        if platform or path not in ROUTES:
            return super().call(method, path, token, body, query, status, code, platform=platform, **kwargs)
        check(method in ROUTES[path], "No execution/chat/provider submission route is allowed")
        self.calls += 1
        target = self.services["institution-service"] + path
        if query:
            target += "?" + urllib.parse.urlencode(query)
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        self.receipt({"event": "intent", "at": now(), "call": self.calls, "case": self.case,
                      "host": "institution-service", "method": method, "path": path, "query": query, "body": body})
        time.sleep(.56)
        try:
            response = urllib.request.build_opener(NoRedirect()).open(urllib.request.Request(target, data=data, headers=headers, method=method), timeout=25)
        except urllib.error.HTTPError as failure:
            response = failure
        except (OSError, TimeoutError) as failure:
            self.receipt({"event": "completion", "at": now(), "call": self.calls, "transport_error": type(failure).__name__, "effects": "unknown"})
            raise AssertionError("Transport unknown; no automatic write replay") from None
        raw = response.read()
        try:
            parsed = json.loads(raw)
        except (ValueError, UnicodeError):
            parsed = {}
        self.receipt({"event": "completion", "at": now(), "call": self.calls, "status": response.status,
                      "code": parsed.get("code"), "response_sha256": hashlib.sha256(raw).hexdigest(), "response": parsed})
        check(response.status == status and parsed.get("code") == code,
              f"{method} {path}: expected {status}/{code}, actual {response.status}/{parsed.get('code')}")
        return parsed.get("data")


class Round(foundation.Round):
    def __init__(self, *args):
        super().__init__(*args)
        self.agents, self.teams, self.sessions, self.end_users = [], [], [], []

    def fixture(self):
        super().fixture()
        self.role_update(READ)
        self.login(actor="viewer")

    def create_agent(self, suffix, tenant="main", prompt=None):
        body = {"agent_key": self.prefix + "_" + suffix, "name": "中文 Agent " + suffix + " " + self.prefix,
                "system_prompt": prompt if prompt is not None else self.prefix + " PRIVATE 未执行提示词\n第二行",
                "model_config_id": self.model, "config": {"memory": {"automatic_extraction": False}, "labels": ["中文", "逗号,引号\""]}}
        item = self.call("POST", G, body, tenant=tenant)
        item["owned_tenant"] = tenant
        self.agents.append(item)
        self.own("agent", item)
        return item

    def detail(self, agent, tenant="main", actor="admin"):
        return self.call("GET", G + "/detail", query={"agentId": agent["id"]}, tenant=tenant, actor=actor)

    def agents_setup(self):
        self.primary = self.create_agent("primary")
        self.child = self.create_agent("child")
        self.unpublished = self.create_agent("unpublished", prompt="")
        self.foreign_agent = self.create_agent("foreign", tenant="foreign")
        check(self.detail(self.primary)["config"]["memory"]["automatic_extraction"] is False, "Complete non-executing draft config consumer")
        before = self.call("GET", G)
        self.call("POST", G, {"agent_key": self.primary["agent_key"], "name": "duplicate", "model_config_id": self.model}, status=409, code="CONFLICT")
        check(self.call("GET", G) == before, "Duplicate key rejection preserves full owned Agent directory")

    def agents_publish(self):
        self.call("POST", G + "/publish", {"agent_id": str(self.unpublished["id"])}, status=422, code="VALIDATION_ERROR")
        check(self.detail(self.unpublished)["status"] == "draft", "Missing prompt stays unpublisheable draft")
        self.call("POST", G + "/publish", {"agent_id": str(self.child["id"])})
        self.call("PATCH", G + "/update", {"agent_id": str(self.primary["id"]), "payload": {
            "role_description": "主要协调者 中文", "delegation_config": {"enabled": True, "target_agent_ids": [str(self.child["id"])]}}})
        self.call("POST", G + "/publish", {"agent_id": str(self.primary["id"])})
        for agent in (self.primary, self.child):
            check(self.detail(agent)["status"] == "published", "Actual independent published metadata, no execution")
        self.original_primary = self.call("GET", G + "/revisions/detail", query={"agentId": self.primary["id"], "revision": 1})

    def agents_revision(self):
        self.call("PATCH", G + "/update", {"agent_id": str(self.primary["id"]), "payload": {"name": "第二发布名 " + self.prefix,
             "system_prompt": self.prefix + " PRIVATE second prompt\n完整冻结", "role_description": "新版公开职责"}})
        current = self.detail(self.primary)
        check(current["has_draft_changes"] and current["revision"] == 1, "Working changes do not replace published revision")
        self.call("POST", G + "/publish", {"agent_id": str(self.primary["id"])})
        history = self.call("GET", G + "/revisions", query={"agentId": self.primary["id"]})
        check(history["total"] == 2 and {x["revision"] for x in history["items"]} == {1, 2} and sum(x["current"] for x in history["items"]) == 1,
              "Exact immutable publish history and one current revision")
        check(self.call("GET", G + "/revisions/detail", query={"agentId": self.primary["id"], "revision": 1}) == self.original_primary,
              "Original full published definition still byte-equivalent JSON values")
        second = self.call("GET", G + "/revisions/detail", query={"agentId": self.primary["id"], "revision": 2})
        check(second["name"] == "第二发布名 " + self.prefix and second["system_prompt"].endswith("完整冻结"), "Current exact publication consumer")

    def agent_catalog_access(self):
        own = self.call("GET", G)
        check(own["total"] == 3 and {str(x["id"]) for x in own["items"]} == {str(x["id"]) for x in (self.primary, self.child, self.unpublished)}, "Complete exact Agent directory, no unsupported pagination")
        foreign = self.call("GET", G, tenant="foreign")
        check(foreign["total"] == 1 and str(foreign["items"][0]["id"]) == str(self.foreign_agent["id"]), "Foreign exact owned Agent directory")
        check(self.call("GET", G, actor="viewer") == {"total": 0, "items": []}, "Scope filter precedes directory totals for unshared viewer")
        self.call("GET", G + "/detail", query={"agentId": self.primary["id"]}, actor="viewer", status=403, code="foundation.resource.denied")
        self.call("GET", G + "/detail", query={"agentId": self.primary["id"]}, tenant="foreign", status=403, code="foundation.resource.denied")
        resource = self.call("GET", foundation.A + "/resource", query={"kind": "agent", "resource_id": str(self.primary["id"])})
        command = {k: resource[k] for k in ("kind", "resource_id", "owner_member_id", "department_id", "lock_version")}
        command["grants"] = [{"grantee_type": "member", "grantee_id": self.viewer["id"], "action": "read"}]
        self.call("PUT", foundation.A + "/resource-authorization", command)
        visible = self.call("GET", G, actor="viewer")
        check(visible["total"] == 1 and str(visible["items"][0]["id"]) == str(self.primary["id"]), "One exact shared Agent becomes selectable, no other resource leak")
        self.call("POST", G, {"name": "forbidden"}, actor="viewer", status=403, code="foundation.permission.denied")
        self.call("POST", G + "/publish", {"agent_id": str(self.primary["id"])}, actor="viewer", status=403, code="foundation.permission.denied")

    def team_body(self, name=None, members=None, primary=None, revision=0, status="draft"):
        return {"name": name or "复杂 Team 中文 " + self.prefix, "description": "多行说明\n逗号,与\"引号\"",
                "status": status, "revision": revision, "members": members if members is not None else
                [{"agent_id": str(self.primary["id"])}, {"agent_id": str(self.child["id"])}],
                "primary_agent_id": primary or str(self.primary["id"])}

    def teams_create(self):
        self.team = self.call("POST", T, self.team_body())
        self.teams.append(self.team)
        self.own("team", self.team)
        check(self.team["status"] == "draft" and self.team["revision"] == 1 and [x["agent_id"] for x in self.team["members"]] ==
              [str(self.primary["id"]), str(self.child["id"])], "Exact independent draft Team member order and identity")
        for number in range(3):
            team = self.call("POST", T, self.team_body(name=f"目录 {number} {self.prefix}", members=[{"agent_id": str(self.primary["id"])}]))
            self.teams.append(team)
            self.own("team", team)
        self.team_original = dict(self.team)

    def teams_update(self):
        payload = self.team_body(name="修订 Team " + self.prefix, revision=1)
        self.team = self.call("PUT", T + "/update", {"id": self.team["id"], "payload": payload})
        check(self.team["revision"] == 2 and self.team["name"] == payload["name"], "Team full successful expected revision update")
        before = self.call("GET", T)
        self.call("PUT", T + "/update", {"id": self.team["id"], "payload": payload}, status=409, code="CONFLICT")
        check(self.call("GET", T) == before, "Stale Team update preserves entire directory")

    def teams_publish(self):
        first = self.call("POST", T + "/publish", {"id": self.team["id"]})
        check(first["revision"] == 3 and first["status"] == "published" and all(x["agent_version"] for x in first["members"]), "Team metadata publication freezes real member versions")
        check(not any(x in json.dumps(first) for x in ("PRIVATE", "system_prompt", "model_config_id", "execution_config")), "Public Team directory omits private execution definition")
        payload = self.team_body(name="第二 Team 中文 " + self.prefix, revision=3, status="published")
        updated = self.call("PUT", T + "/update", {"id": first["id"], "payload": payload})
        second = self.call("POST", T + "/publish", {"id": first["id"]})
        third = self.call("POST", T + "/publish", {"id": first["id"]})
        revisions = self.call("GET", T + "/revisions", query={"id": first["id"]})
        check(updated["revision"] == 4 and second["revision"] == 5 and third["revision"] == 6 and
              {x["revision"] for x in revisions} == {3, 5, 6} and next(x for x in revisions if x["revision"] == 3) == first,
              "Publish creates new revision each time; original complete public snapshot remains immutable")
        self.team = third

    def bad_team(self, change):
        before = self.call("GET", T)
        try:
            self.call("POST", T, self.team_body(**change), status=400, code="VALIDATION_ERROR")
        finally:
            check(self.call("GET", T) == before, "Malformed Team rejected without a partial directory row")

    def teams_scope_permission(self):
        listing = self.call("GET", T)
        check(len(listing) == 4 and {x["id"] for x in listing} == {x["id"] for x in self.teams}, "Complete four-Team directory, no fictional page contract")
        check(self.call("GET", T, tenant="foreign") == [] and self.call("GET", T, actor="viewer") == listing,
              "Team read consumer is public summary within its fixed tenant scope")
        self.call("GET", T + "/revisions", query={"id": self.team["id"]}, tenant="foreign", status=404, code="NOT_FOUND")
        self.call("PUT", T + "/update", {"id": self.team["id"], "payload": self.team_body(revision=self.team["revision"])}, tenant="foreign", status=404, code="NOT_FOUND")
        self.call("POST", T + "/publish", {"id": self.team["id"]}, tenant="foreign", status=404, code="NOT_FOUND")
        for method, path, body in [("POST", T, self.team_body()), ("PUT", T + "/update", {"id": self.team["id"], "payload": self.team_body(revision=self.team["revision"])}),
                                  ("POST", T + "/publish", {"id": self.team["id"]})]:
            self.call(method, path, body, actor="viewer", status=403, code="foundation.permission.denied")
        self.call("POST", T, self.team_body(members=[{"agent_id": str(self.foreign_agent["id"])}], primary=str(self.foreign_agent["id"])), status=422, code="VALIDATION_ERROR")
        # Profiles consume actual publications before delegation validation.
        self.call("POST", T, self.team_body(members=[{"agent_id": str(self.primary["id"])}, {"agent_id": str(self.unpublished["id"])}]), status=404, code="NOT_FOUND")
        self.call("POST", T, self.team_body(members=[{"agent_id": str(self.child["id"])}, {"agent_id": str(self.primary["id"])}],
                  primary=str(self.child["id"])), status=422, code="VALIDATION_ERROR")
        check(self.call("GET", T) == listing, "All foreign/permission/delegation failures preserve exact complete Team definitions")

    def deployment_directory(self):
        for tenant in ("main", "foreign"):
            check(self.call("GET", G + "/deployments", tenant=tenant) == {"total": 0, "items": []}, "Independent Agents do not become public application deployments")
        check(self.call("GET", G + "/deployments", actor="viewer") == {"total": 0, "items": []}, "Deployment scope directory remains exact empty")
        self.call("GET", G + "/deployments/detail", query={"agentId": self.primary["id"]}, status=404, code="NOT_FOUND")
        self.call("GET", G + "/deployments/detail", query={"agentId": self.foreign_agent["id"]}, status=403, code="foundation.resource.denied")
        self.role_update([x for x in READ if x != "foundation.agents.read"])
        try:
            self.login(actor="viewer")
            self.call("GET", G + "/deployments", actor="viewer", status=403, code="foundation.permission.denied")
        finally:
            self.role_update(READ)
            self.login(actor="viewer")
        templates = self.call("GET", G + "/templates")
        check(templates["total"] == len(templates["items"]), "Current read-only selectable template projection has exact total")

    def sessions_setup(self):
        for number in range(3):
            user = self.call("POST", U, {"display_name": self.prefix + " 用户「" + str(number) + "」"})
            self.end_users.append(user)
            self.own("end_user", user)
            target = self.primary if number != 1 else self.child
            for _ in range(2 if number == 0 else 1):
                session = self.call("POST", S, {"agent_id": str(target["id"]), "end_user_id": user["id"],
                                   "scope_id": self.prefix + "-scope-" + str(number), "scope_type": "host-free-directory"})
                session.update(agent_id=str(target["id"]), end_user_id=str(user["id"]))
                self.sessions.append(session)
                self.own("empty_session", session)
        users = self.call("GET", U)
        check({str(x["id"]) for x in users} == {str(x["id"]) for x in self.end_users}, "Exact formally registered end-user selectable directory")
        listing = self.call("GET", S)
        check({x["session_id"] for x in listing} == {x["session_id"] for x in self.sessions} and all(x["message_count"] == 0 for x in listing),
              "Only four owned empty Sessions, no Run or message execution")
        for query, expected in [({"agentId": self.primary["id"]}, {x["session_id"] for x in self.sessions if x["agent_id"] == str(self.primary["id"])}),
                                ({"end_user_id": self.end_users[0]["id"]}, {x["session_id"] for x in self.sessions if x["end_user_id"] == str(self.end_users[0]["id"])}),
                                ({"agentId": self.child["id"], "end_user_id": self.end_users[0]["id"]}, set())]:
            check({x["session_id"] for x in self.call("GET", S, query=query)} == expected, "Agent/end-user filters use independent exact Session identity sets")

    def workspace_consumer(self):
        for session in self.sessions:
            query = {"sessionId": session["session_id"]}
            check(self.call("GET", S + "/workspace", query=query) == {"workspaces": []} and
                  self.call("GET", S + "/workspace", query=query) == {"workspaces": []}, "Actual query is read-only and never implicitly creates a workspace")
        one = self.sessions[0]
        self.call("GET", S + "/workspace", query={"sessionId": one["session_id"]}, actor="viewer", status=403, code="foundation.session.denied")
        self.call("GET", S + "/workspace", query={"sessionId": one["session_id"]}, tenant="foreign", status=403, code="foundation.session.denied")
        check(self.call("GET", S, actor="viewer") == [] and self.call("GET", S, tenant="foreign") == [], "Shared Agent does not grant another member's Session history or workspace")
        self.call("GET", S + "/workspace", query={"session_id": one["session_id"]}, status=400, code="VALIDATION_ERROR")
        self.call("GET", S + "/workspace", query={"sessionId": self.prefix + "-absent"}, status=403, code="foundation.session.denied")

    def cleanup(self):
        if "main" in self.t:
            for session in self.sessions:
                def close(session=session):
                    body = {"session_id": session["session_id"], "payload": {"reason": "Owned free directory acceptance complete"}}
                    first = self.call("POST", S + "/close", body)
                    check(first["status"] == "closed" and self.call("POST", S + "/close", body) == first, "Owned empty Session actual closed and idempotent receipt")
                    return {"id": session["session_id"], "state": "closed", "history_retained": True}
                self.cleanup_one("empty_session." + session["session_id"], close)
            for team in self.teams:
                def disable(team=team):
                    current = next(x for x in self.call("GET", T) if x["id"] == team["id"])
                    payload = {k: current[k] for k in ("name", "description", "status", "revision", "members", "primary_agent_id")}
                    payload["members"] = [{"agent_id": x["agent_id"]} for x in current["members"]]
                    payload["status"] = "disabled"
                    result = self.call("PUT", T + "/update", {"id": team["id"], "payload": payload})
                    check(result["status"] == "disabled" and next(x for x in self.call("GET", T) if x["id"] == team["id"])["status"] == "disabled", "Owned Team actually disabled with current revision")
                    return {"id": team["id"], "state": "disabled", "history_retained": True}
                self.cleanup_one("team." + team["id"], disable)
            for agent in self.agents:
                def archive(agent=agent):
                    result = self.call("PATCH", G + "/update", {"agent_id": str(agent["id"]), "payload": {"status": "archived"}}, tenant=agent["owned_tenant"])
                    check(result["status"] == "archived" and self.detail(agent, tenant=agent["owned_tenant"])["status"] == "archived", "Published Agent correctly archived rather than violating deletion protection")
                    return {"id": str(agent["id"]), "state": "archived", "history_retained": True}
                self.cleanup_one("agent." + str(agent["id"]), archive)
        super().cleanup()

    def run(self):
        try:
            self.case("fixture.new_owned_tenants", self.fixture, needs=(), ready="fixture")
            self.case("agents.formal_complex_drafts_duplicate_key_atomic", self.agents_setup, ready="agents")
            self.case("agents.publish_actual_metadata_ready_delegation", self.agents_publish, needs=("agents",), ready="published")
            self.case("agents.republish_exact_current_and_original_revision", self.agents_revision, needs=("published",))
            self.case("agents.selectable_directory_exact_scope_share_permissions", self.agent_catalog_access, needs=("agents",))
            self.case("teams.complex_two_member_and_complete_directory_setup", self.teams_create, needs=("published",), ready="teams")
            self.case("teams.cas_success_stale_preserves_complete_directory", self.teams_update, needs=("teams",), ready="team_updated")
            self.case("teams.publication_frozen_public_profiles_history", self.teams_publish, needs=("team_updated",), ready="team_published")
            self.case("teams.malformed_duplicate_members_client_error_atomic", lambda: self.bad_team({"members": [{"agent_id": str(self.primary["id"])}]*2}), needs=("teams",))
            self.case("teams.malformed_primary_outside_members_client_error_atomic", lambda: self.bad_team({"primary": str(self.unpublished["id"])}), needs=("teams",))
            self.case("teams.malformed_empty_members_client_error_atomic", lambda: self.bad_team({"members": []}), needs=("teams",))
            self.case("teams.current_summary_read_foreign_permission_delegation", self.teams_scope_permission, needs=("teams",))
            self.case("agents.deployment_catalog_independent_not_public_scoped", self.deployment_directory, needs=("agents",))
            self.case("sessions.formal_end_users_empty_sessions_exact_selection_filters", self.sessions_setup, needs=("published",), ready="sessions")
            self.case("workspace.actual_empty_read_query_current_ownership_foreign", self.workspace_consumer, needs=("sessions",))
        finally:
            self.cleanup()
        result = {"prefix": self.prefix, "counts": dict(Counter(x["status"] for x in self.cases)),
                  "business_counts": dict(Counter(x["status"] for x in self.cases if x["category"] == "business")),
                  "cleanup_counts": dict(Counter(x["status"] for x in self.cleanup_rows)), "http_calls": self.api.calls,
                  "owned_tenants": len(self.t), "provider_calls": 0, "smtp_calls": 0, "runtime_input_submissions": 0,
                  "limits": ["Team and Agent catalog have no implemented pagination contract", "Workspace positive read is actual empty Session projection, not sandbox directory contents",
                             "Public application deployment success/lifecycle is linked to existing explicit HTTP integration evidence, not independent Agent creation",
                             "Owned published Agents archived, Teams disabled, Sessions closed, tenant/account/version history retained"]}
        save(self.root / "summary.json", result)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--rounds", type=int, default=3, choices=(1, 3))
    args = parser.parse_args()
    root, output = args.environment.resolve(), args.output.resolve()
    check(root.is_relative_to((REPO / ".runtime-logs/full-project-test-20261012").resolve()) and output.is_relative_to((REPO / ".runtime-logs/full-project-test-20261012").resolve()), "Only current private acceptance batch")
    check(not output.exists(), "Preserve all prior evidence; output must be fresh")
    state = json.loads((root / "environment.json").read_text(encoding="utf-8"))
    check(state["batch"] == "sf-fulltest-349ef3c037" and all(x["status"] == "READY" for x in state["services"].values()), "Exact isolated manifest")
    env = load_environment(root / "isolated.env")
    model = json.loads((root / "provision.json").read_text(encoding="utf-8"))["models"]["llm"]["id"]
    output.mkdir(parents=True)
    source_paths = SOURCES + ["tools/full_project_testing/foundation_lifecycle_acceptance.py"]
    save(output / "source-binding.json", {"runner": {"path": str(Path(__file__).resolve()), "sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()},
        "sources": [{"path": x, "sha256": hashlib.sha256((REPO / x).read_bytes()).hexdigest()} for x in source_paths],
        "services": [{"service": k, "jar": v["jar"], "sha256": hashlib.sha256(Path(v["jar"]).read_bytes()).hexdigest()} for k, v in state["services"].items()]})
    results = []
    prefix = "ht" + secrets.token_hex(4)
    for number in range(1, args.rounds+1):
        path = output / ("round-" + str(number))
        path.mkdir()
        results.append(Round(Api(state, path / "operations.jsonl"), path, prefix + "r" + str(number), env, model).run())
        save(output / "summary.json", {"rounds": results, "provider_calls": 0, "runtime_input_submissions": 0})
    return 1 if any(x["counts"].get("FAIL") or x["cleanup_counts"].get("FAIL") for x in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
