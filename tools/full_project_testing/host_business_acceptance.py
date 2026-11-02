"""Owned, nonpaid host business acceptance on the current isolated A/B/C services.

Every HTTP attempt has an intent/completion receipt. Writes are never retried.
Fixtures use public APIs, independent expected sets, exact error codes and fresh
consumer logins. Setup failures block dependent cases instead of passing them.
Only receipted prefixed entities are cleaned; member/announcement tombstones are
retained because the public contract has no deletion endpoint for them.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from run_suite import REPO, load_environment, save

ACCESS = "/api/v1/tenant/foundation/access"
CATALOG = "/api/v1/tenant/foundation/catalog"
MESSAGING = "/api/v1/tenant/foundation/messaging"
PASSWORD = "Host-Acceptance-20261012!"
PLANNED_CASE_IDS = [
    "access.formal_fixture_A",
    "access.formal_fixture_B",
    "access.formal_fixture_C",
    "access.department.successful_update",
    "access.department.stale_preserves",
    "access.department.cycle",
    "access.departments.reference_delete_protection",
    "access.positions.reference_delete_protection",
    "access.roles.reference_delete_protection",
    "access.position.successful_update_and_stale",
    "access.member.successful_update_and_stale",
    "access.role.successful_update_and_stale",
    "access.cross_tenant_department_role_rejection",
    "access.role.invalid_specified_scope",
    "access.scope.self",
    "access.scope.department",
    "access.scope.descendants",
    "access.scope.specified_plus_self",
    "access.scope.department_specified_union",
    "access.scope.all",
    "access.live_role_revocation_consumer_and_device",
    "access.resource.formal_agent_draft_setup",
    "access.resource.unshared_consumer_denied",
    "access.resource.member_grant_consumer",
    "access.resource.role_grant_consumer",
    "access.resource.department_grant_consumer",
    "access.resource.stale_preserves",
    "access.resource.foreign_grantee_rejected",
    "access.resource.live_grant_revocation",
    "catalog.dictionary.formal_crud_setup_sort_tenant_isolation",
    "catalog.dictionary.disabled_parent_historical_values",
    "catalog.dictionary.value_delete_stale",
    "catalog.dictionary.successful_value_and_parent_delete",
    "catalog.navigation.controlled_routes_formal_create",
    "catalog.navigation.current_permission_projection",
    "catalog.navigation.cycle_unregistered_route_parent_delete",
    "catalog.navigation.update_disabled_parent_stale",
    "catalog.navigation.successful_children_and_parent_delete",
    "messaging.publish_24_complex_audiences_history_cursor",
    "messaging.exact_audience_inbox_cursor_unread_tenant_isolation",
    "messaging.mark_read_idempotent_member_tenant_separation",
    "messaging.publish_replay_normalized_conflict",
    "messaging.withdraw_history_unread_repeat_foreign",
    "messaging.cursor_and_limit_exact_invalid_contract",
    "access.departments.successful_delete_and_missing",
    "access.positions.successful_delete_and_missing",
    "access.roles.successful_delete_and_missing",
    "access.last_existing_administrator_and_builtin_mutation",
    "catalog.dictionary.trusted_consumer_reference_delete",
]
READ_PERMS = ["foundation.access.read", "foundation.agents.read",
              "foundation.resource.agent.read", "foundation.messaging.inbox"]
SOURCES = [
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/access/AccessEndpoints.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/access/FoundationOrganizationService.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/access/FoundationAccess.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/catalog/dictionary/DictionaryService.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/catalog/navigation/NavigationService.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/catalog/navigation/ControlledRoutes.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/messaging/application/NotificationService.java",
    "backend-java/skillforge-institution-service/src/main/java/com/skillforge/institution/controller/AgentController.java",
]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """认证请求不跟随重定向，避免凭据外发和未知写入的自动重发。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def now():
    return datetime.now(timezone.utc).isoformat()


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def redact(value):
    if isinstance(value, dict):
        return {k: ("[REDACTED]" if any(x in k.lower() for x in
                    ("password", "token", "secret", "credential")) else redact(v))
                for k, v in value.items()}
    if isinstance(value, list):
        return [redact(x) for x in value]
    return value


class Api:
    def __init__(self, base, stream):
        u = urllib.parse.urlsplit(base)
        check(u.scheme == "http" and u.hostname == "127.0.0.1" and u.port,
              "Only the manifest loopback institution service is permitted")
        self.base, self.stream = base.rstrip("/"), stream
        self.calls, self.case = 0, "preflight"

    def receipt(self, value):
        with self.stream.open("a", encoding="utf-8") as output:
            output.write(json.dumps(value, ensure_ascii=False) + "\n")

    def call(self, method, path, token=None, body=None, query=None, status=200, code="OK"):
        check(path.startswith((ACCESS, CATALOG, MESSAGING, "/api/v1/agents", "/api/v1/auth/login")),
              "Endpoint outside the nonpaid host allowlist")
        check(not (path.startswith("/api/v1/agents") and path not in ("/api/v1/agents", "/api/v1/agents/detail", "/api/v1/agents/delete")),
              "Agent publish/run/provider endpoints are excluded")
        self.calls += 1
        number = self.calls
        target = self.base + path
        if query:
            target += "?" + urllib.parse.urlencode({k: str(v).lower() if isinstance(v, bool) else v
                                                   for k, v in query.items() if v is not None})
        self.receipt({"event": "intent", "at": now(), "call": number, "case": self.case,
                      "method": method, "path": path, "query": query, "body": redact(body)})
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(target, data=data, headers=headers, method=method)
        time.sleep(0.56)  # Dedicated actors remain below the actual 120/minute limit.
        try:
            response = urllib.request.build_opener(NoRedirect()).open(request, timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        except (OSError, TimeoutError) as error:
            self.receipt({"event": "completion", "at": now(), "call": number,
                          "transport_error": type(error).__name__, "effects": "unknown"})
            raise AssertionError("Transport failure; no write replay: " + type(error).__name__) from None
        raw = response.read()
        try:
            parsed = json.loads(raw)
        except ValueError:
            parsed = {"code": "NON_JSON"}
        self.receipt({"event": "completion", "at": now(), "call": number,
                      "status": response.status, "code": parsed.get("code"),
                      "response_sha256": hashlib.sha256(raw).hexdigest(), "response": redact(parsed)})
        if status is not None:
            check(response.status == status and parsed.get("code") == code,
                  f"{method} {path}: expected {status}/{code}, actual {response.status}/{parsed.get('code')}")
        return parsed.get("data") if status is not None else (response.status, parsed)


class Round:
    def __init__(self, api, root, prefix, credentials, tenants):
        self.api, self.root, self.prefix = api, root, prefix
        self.credentials, self.tenants = credentials, tenants
        self.cases, self.ready, self.owned, self.cleanup_receipts = [], set(), [], []
        self.t = {label: {"slug": "full-test-" + label.lower(), "departments": [],
                          "positions": [], "roles": [], "members": [], "agents": [],
                          "dictionaries": [], "navigation": [], "announcements": [], "actors": []}
                  for label in "ABC"}

    def own(self, tenant, kind, value):
        self.t[tenant][kind].append(value)
        self.owned.append({"tenant": tenant, "kind": kind, "entity": value})
        save(self.root / "owned-entities.json", redact(self.owned))
        return value

    def case(self, name, action, depends=(), ready=None):
        self.api.case = name
        start = self.api.calls
        row = {"id": name, "started_at": now(), "dependencies": list(depends),
               "category": "fixture" if name.startswith("access.formal_fixture_") else "business"}
        missing = [d for d in depends if d not in self.ready]
        if missing:
            row.update(status="BLOCKED", reason="Failed or unentered prerequisite: " + ", ".join(missing))
        else:
            try:
                action()
                row["status"] = "PASS"
                if ready:
                    self.ready.add(ready)
            except Exception as error:
                row.update(status="FAIL", reason=f"{type(error).__name__}: {str(error)[:700]}")
        row.update(ended_at=now(), http_calls=self.api.calls - start,
                   first_call=start + 1 if self.api.calls > start else None, last_call=self.api.calls)
        self.cases.append(row)
        save(self.root / "cases.json", self.cases)
        print(f"{self.prefix} {name} {row['status']} {row.get('reason', '')}", flush=True)

    def blocked(self, name, reason):
        self.cases.append({"id": name, "category": "unentered", "status": "BLOCKED", "reason": reason, "http_calls": 0})
        save(self.root / "cases.json", self.cases)

    def login(self, tenant, member=None):
        item = self.t[tenant]
        body = {"tenant_slug": item["slug"],
                "username": member["username"] if member else self.credentials[0],
                "password": PASSWORD if member else self.credentials[1]}
        token = self.api.call("POST", "/api/v1/auth/login", body=body)["access_token"]
        if member:
            member["current_token"] = token
        else:
            item["token"] = token
        return token

    def call(self, tenant, method, endpoint, body=None, query=None, **kwargs):
        if method != "GET" and endpoint in {ACCESS + "/" + x for x in ("departments", "positions", "members", "roles")}:
            endpoint = endpoint[:-1]  # AccessEndpoints declares singular mutation routes.
        return self.api.call(method, endpoint, self.t[tenant]["token"], body, query, **kwargs)

    def member(self, tenant, index):
        old = self.t[tenant]["members"][index]
        page = self.call(tenant, "GET", ACCESS + "/members", query={"keyword": old["username"], "page_size": 100})
        found = next(x for x in page["items"] if x["id"] == old["id"])
        old.update(found)
        return old

    def member_command(self, item, **change):
        return dict({k: item[k] for k in ("id", "display_name", "email", "department_id",
                                          "position_id", "status", "lock_version")}, **change)

    def role_command(self, item, **change):
        return dict({k: item[k] for k in ("id", "name", "data_scope", "permissions",
                                          "department_ids", "enabled", "lock_version")}, **change)

    def department_command(self, item, **change):
        return dict({k: item[k] for k in ("id", "name", "parent_id", "enabled", "lock_version")}, **change)

    def setup(self, tenant):
        item = self.t[tenant]
        self.bootstrap_actor(tenant)
        ent = self.call(tenant, "GET", CATALOG + "/tenant-entitlements")
        count = 12 if tenant == "A" else 2
        usage = next(x for x in ent["usage"] if x["kind"] == "MEMBER")
        check(usage["limit"] is None or int(usage["used"]) + count <= int(usage["limit"]), "Member quota insufficient")
        check("members" in ent["features"], "Members feature disabled")
        for index, parent in enumerate([None, 0, 1, 2, 0]):
            value = self.call(tenant, "POST", ACCESS + "/departments", {
                "code": f"{self.prefix}_{tenant.lower()}_d{index}", "name": f"{self.prefix} {tenant} department {index}",
                "parent_id": None if parent is None else item["departments"][parent]["id"]})
            self.own(tenant, "departments", value)
            check(value["lock_version"] == 1 and value["enabled"], "Fresh department projection")
        position = self.call(tenant, "POST", ACCESS + "/positions", {
            "code": f"{self.prefix}_{tenant.lower()}_p", "name": self.prefix + " position"})
        self.own(tenant, "positions", position)
        for scope in ["self", "department", "descendants", "specified", "all"]:
            role = self.call(tenant, "POST", ACCESS + "/roles", {
                "code": f"{self.prefix}_{tenant.lower()}_{scope}", "name": self.prefix + " " + scope,
                "data_scope": scope, "permissions": READ_PERMS,
                "department_ids": [item["departments"][4]["id"]] if scope == "specified" else []})
            self.own(tenant, "roles", role)
            check(not role["built_in"] and not role["administrator"] and role["lock_version"] == 1,
                  "Custom role must not become administrator")
        for index in range(count):
            department = index % 5
            member = self.call(tenant, "POST", ACCESS + "/members", {
                "username": f"{self.prefix}_{tenant.lower()}_m{index:02}",
                "display_name": f"{self.prefix} {tenant} member {index:02}", "email": None,
                "password": PASSWORD, "department_id": item["departments"][department]["id"],
                "position_id": position["id"], "role_ids": [item["roles"][0]["id"]]})
            self.own(tenant, "members", member)
            check(member["status"] == "active" and member["department_id"] == item["departments"][department]["id"],
                  "Formal member create projection")
        ids = {x["id"] for x in item["members"]}
        pages = [self.call(tenant, "GET", ACCESS + "/members", query={"keyword": self.prefix + "_" + tenant.lower() + "_m",
                 "page": p, "page_size": 3}) for p in range(1, (count + 2) // 3 + 1)]
        actual = [x["id"] for p in pages for x in p["items"]]
        check(all(p["total"] == count and p["page_size"] == 3 for p in pages), "Exact independent pagination totals")
        check(set(actual) == ids and len(actual) == count, "Pagination exhaustive and duplicate-free")
        save(self.root / (tenant + "-capacity.json"), ent)

    def bootstrap_actor(self, tenant):
        item = self.t[tenant]
        item["bootstrap_token"] = self.login(tenant)
        roles = self.call(tenant, "GET", ACCESS + "/roles")
        admin = next(x for x in roles if x["code"] == "tenant_admin" and x["administrator"])
        actor = self.call(tenant, "POST", ACCESS + "/members", {
            "username": self.prefix + "_" + tenant.lower() + "_actor", "display_name": self.prefix + " owned administrator",
            "email": None, "password": PASSWORD, "department_id": None, "position_id": None, "role_ids": [admin["id"]]})
        self.own(tenant, "actors", actor)
        item["token"] = self.login(tenant, actor)

    def organization(self):
        item = self.t["A"]
        d, p, r = item["departments"][3], item["positions"][0], item["roles"][0]
        def department_update():
            original = dict(d)
            value = self.call("A", "PUT", ACCESS + "/departments", self.department_command(d, name=self.prefix + " renamed"))
            d.update(value)
            check(d["name"].endswith("renamed") and d["lock_version"] == original["lock_version"] + 1, "Department update CAS")
        self.case("access.department.successful_update", department_update, ["A"])
        def department_stale():
            self.call("A", "PUT", ACCESS + "/departments", self.department_command(d, name="stale", lock_version=1),
                      status=409, code="foundation.access.version_conflict")
            check(next(x for x in self.call("A", "GET", ACCESS + "/departments") if x["id"] == d["id"]) == d,
                  "Stale department preserves exact persisted projection")
        self.case("access.department.stale_preserves", department_stale, ["A"])
        def cycle():
            root = item["departments"][0]
            self.call("A", "PUT", ACCESS + "/departments", self.department_command(root, parent_id=d["id"]),
                      status=400, code="foundation.access.invalid")
            check(next(x for x in self.call("A", "GET", ACCESS + "/departments") if x["id"] == root["id"]) == root,
                  "Cycle refusal preserves root")
        self.case("access.department.cycle", cycle, ["A"])
        def reference(kind, entity):
            self.call("A", "DELETE", ACCESS + "/" + kind, {"id": entity["id"], "lock_version": entity["lock_version"]},
                      status=409, code="CONFLICT")
            check(any(x["id"] == entity["id"] for x in self.call("A", "GET", ACCESS + "/" + kind)), "Referenced object retained")
        for kind, entity in [("departments", item["departments"][0]), ("positions", p), ("roles", r)]:
            self.case("access." + kind + ".reference_delete_protection", lambda k=kind, e=entity: reference(k, e), ["A"])
        def position_update():
            old = dict(p)
            p.update(self.call("A", "PUT", ACCESS + "/positions", {
                "id": p["id"], "name": self.prefix + " position updated", "enabled": True, "lock_version": p["lock_version"]}))
            check(p["lock_version"] == old["lock_version"] + 1 and p["name"].endswith("updated"), "Position successful CAS update")
            self.call("A", "PUT", ACCESS + "/positions", {"id": p["id"], "name": "stale", "enabled": True, "lock_version": old["lock_version"]},
                      status=409, code="foundation.access.version_conflict")
            check(next(x for x in self.call("A", "GET", ACCESS + "/positions") if x["id"] == p["id"]) == p, "Stale position preserves")
        self.case("access.position.successful_update_and_stale", position_update, ["A"])
        def member_update():
            m = self.member("A", 11); old = dict(m)
            m.update(self.call("A", "PUT", ACCESS + "/members", self.member_command(m, display_name=self.prefix + " edited member")))
            check(m["display_name"].endswith("edited member") and m["lock_version"] == old["lock_version"] + 1,
                  "Member profile successful CAS update")
            self.call("A", "PUT", ACCESS + "/members", self.member_command(old, display_name="stale"),
                      status=409, code="foundation.access.version_conflict")
            check(self.member("A", 11)["display_name"] == m["display_name"], "Stale member preserves persisted name")
        self.case("access.member.successful_update_and_stale", member_update, ["A"])
        def role_update():
            target = item["roles"][4]; old = dict(target)
            target.update(self.call("A", "PUT", ACCESS + "/roles", self.role_command(target, name=self.prefix + " all renamed")))
            check(target["lock_version"] == old["lock_version"] + 1 and target["name"].endswith("renamed"), "Role successful update")
            self.call("A", "PUT", ACCESS + "/roles", self.role_command(old, name="stale"),
                      status=409, code="foundation.access.version_conflict")
            check(next(x for x in self.call("A", "GET", ACCESS + "/roles") if x["id"] == target["id"]) == target, "Stale role retains definition")
        self.case("access.role.successful_update_and_stale", role_update, ["A"])
        def foreign_ids():
            foreign = self.t["B"]["departments"][0]
            self.call("A", "POST", ACCESS + "/departments", {"code": self.prefix + "_foreign", "name": "foreign", "parent_id": foreign["id"]},
                      status=404, code="NOT_FOUND")
            m = self.member("A", 10)
            self.call("A", "PUT", ACCESS + "/members", self.member_command(m, department_id=foreign["id"]), status=404, code="NOT_FOUND")
            self.call("A", "POST", ACCESS + "/member-roles", {"member_id": m["id"], "role_ids": [self.t["B"]["roles"][0]["id"]], "lock_version": m["lock_version"]}, status=404, code="NOT_FOUND")
            check(self.member("A", 10)["department_id"] == m["department_id"], "Foreign assignment preserves member")
        self.case("access.cross_tenant_department_role_rejection", foreign_ids, ["A", "B"])
        def invalid_role():
            before = [x for x in self.call("A", "GET", ACCESS + "/roles") if x["code"].startswith(self.prefix)]
            self.call("A", "POST", ACCESS + "/roles", {"code": self.prefix + "_bad", "name": "bad", "data_scope": "specified",
                      "permissions": READ_PERMS, "department_ids": []}, status=400, code="foundation.access.invalid")
            check([x for x in self.call("A", "GET", ACCESS + "/roles") if x["code"].startswith(self.prefix)] == before,
                  "Invalid role does not persist")
        self.case("access.role.invalid_specified_scope", invalid_role, ["A"])

    def scope_case(self, scope_indices, expected_indices):
        item = self.t["A"]; m = self.member("A", 1)
        m.update(self.call("A", "POST", ACCESS + "/member-roles", {"member_id": m["id"], "role_ids": [item["roles"][i]["id"] for i in scope_indices], "lock_version": m["lock_version"]}))
        token = self.login("A", m)
        expected = {item["members"][i]["id"] for i in expected_indices}
        data = self.api.call("GET", ACCESS + "/members", token, query={"keyword": self.prefix + "_a_m", "page_size": 100})
        check(data["total"] == len(expected) and {x["id"] for x in data["items"]} == expected,
              f"Independent exact data-scope union: expected {sorted(expected)}")
        snapshot = self.api.call("GET", ACCESS + "/snapshot", token)
        check(set(snapshot["roles"]) == {item["roles"][i]["code"] for i in scope_indices}, "Role projection matches assigned exact set")

    def scopes(self):
        for name, roles, expected in [
            ("self", [0], [1]), ("department", [1], [1, 6, 11]),
            ("descendants", [2], [1, 2, 3, 6, 7, 8, 11]),
            ("specified_plus_self", [3], [1, 4, 9]),
            ("department_specified_union", [1, 3], [1, 4, 6, 9, 11]),
            ("all", [4], list(range(12)))]:
            self.case("access.scope." + name, lambda r=roles, e=expected: self.scope_case(r, e), ["A"])
        def revocation():
            item = self.t["A"]; m = self.member("A", 1)
            m.update(self.call("A", "POST", ACCESS + "/member-roles", {"member_id": m["id"], "role_ids": [item["roles"][0]["id"]], "lock_version": m["lock_version"]}))
            old_token = self.login("A", m)
            before = self.api.call("GET", ACCESS + "/snapshot", old_token)
            target = item["roles"][0]
            target.update(self.call("A", "PUT", ACCESS + "/roles", self.role_command(target, permissions=[p for p in READ_PERMS if p != "foundation.agents.read"])))
            status, raw = self.api.call("GET", "/api/v1/agents", old_token, status=None)
            check(status == 401, f"Old device token must be revoked immediately, actual {status}/{raw.get('code')}")
            fresh = self.login("A", m)
            self.api.call("GET", "/api/v1/agents", fresh, status=403, code="foundation.permission.denied")
            after = self.api.call("GET", ACCESS + "/snapshot", fresh)
            check("foundation.agents.read" not in after["permissions"] and after["authorization_version"] > before["authorization_version"],
                  "Live snapshot permissions/version reflect role revocation")
            target.update(self.call("A", "PUT", ACCESS + "/roles", self.role_command(target, permissions=READ_PERMS)))
        self.case("access.live_role_revocation_consumer_and_device", revocation, ["A"])

    def resource_setup(self):
        value = self.call("A", "POST", "/api/v1/agents", {"agent_key": self.prefix + "_resource", "name": self.prefix + " resource",
                 "system_prompt": "Owned nonpaid test fixture", "model_config_id": None, "config": {}})
        self.own("A", "agents", value)
        check(str(value["status"]).lower() == "draft", "Consumer fixture is draft; no publish/provider call")
        self.resource_id = str(value["id"])
        self.resource = self.call("A", "GET", ACCESS + "/resource", query={"kind": "agent", "resource_id": self.resource_id})

    def grant(self, grants):
        cmd = {k: self.resource[k] for k in ("kind", "resource_id", "owner_member_id", "department_id", "lock_version")}
        cmd["grants"] = grants
        self.resource = self.call("A", "PUT", ACCESS + "/resource-authorization", cmd)
        expected = {(x["grantee_type"], x["grantee_id"], x["action"]) for x in grants}
        check({(x["grantee_type"], x["grantee_id"], x["action"]) for x in self.resource["grants"]} == expected,
              "Persisted full resource grant replacement")

    def consumer(self, member_index, allowed):
        token = self.login("A", self.member("A", member_index))
        value = self.api.call("GET", "/api/v1/agents/detail", token, query={"agentId": self.resource_id},
                              status=200 if allowed else 403, code="OK" if allowed else "foundation.resource.denied")
        if allowed:
            check(str(value["id"]) == self.resource_id, "Actual Agent detail consumer returns shared resource")

    def resources(self):
        self.case("access.resource.formal_agent_draft_setup", self.resource_setup, ["A"], "resource")
        self.case("access.resource.unshared_consumer_denied", lambda: self.consumer(0, False), ["resource"])
        def member_grant():
            self.grant([{"grantee_type": "member", "grantee_id": self.t["A"]["members"][0]["id"], "action": "read"}])
            self.consumer(0, True); self.consumer(2, False)
        self.case("access.resource.member_grant_consumer", member_grant, ["resource"])
        def role_grant():
            self.grant([{"grantee_type": "role", "grantee_id": self.t["A"]["roles"][0]["id"], "action": "read"}])
            self.consumer(0, True); self.consumer(2, True)
        self.case("access.resource.role_grant_consumer", role_grant, ["resource"])
        def department_grant():
            self.grant([{"grantee_type": "department", "grantee_id": self.t["A"]["departments"][2]["id"], "action": "read"}])
            self.consumer(2, True); self.consumer(7, True); self.consumer(0, False)
        self.case("access.resource.department_grant_consumer", department_grant, ["resource"])
        def resource_cas():
            before = dict(self.resource)
            cmd = {k: before[k] for k in ("kind", "resource_id", "owner_member_id", "department_id", "lock_version")}
            cmd.update(grants=[], lock_version=max(1, before["lock_version"] - 1))
            self.call("A", "PUT", ACCESS + "/resource-authorization", cmd, status=409, code="foundation.access.version_conflict")
            check(self.call("A", "GET", ACCESS + "/resource", query={"kind": "agent", "resource_id": self.resource_id}) == before, "Stale resource command preserves exact grants")
        self.case("access.resource.stale_preserves", resource_cas, ["resource"])
        def foreign():
            cmd = {k: self.resource[k] for k in ("kind", "resource_id", "owner_member_id", "department_id", "lock_version")}
            cmd["grants"] = [{"grantee_type": "member", "grantee_id": self.t["B"]["members"][0]["id"], "action": "read"}]
            self.call("A", "PUT", ACCESS + "/resource-authorization", cmd, status=404, code="NOT_FOUND")
            check(self.call("A", "GET", ACCESS + "/resource", query={"kind": "agent", "resource_id": self.resource_id}) == self.resource, "Foreign grant preserves resource")
        self.case("access.resource.foreign_grantee_rejected", foreign, ["resource", "B"])
        def revoke():
            self.grant([]); self.consumer(2, False)
        self.case("access.resource.live_grant_revocation", revoke, ["resource"])

    def dictionaries(self):
        code = self.prefix + "_dict"
        def create():
            for tenant in "ABC":
                parent = self.call(tenant, "PUT", CATALOG + "/dictionaries", {"code": code, "name": self.prefix + " dictionary " + tenant, "enabled": True, "expected_revision": 0})
                self.own(tenant, "dictionaries", parent)
            for value, order, enabled in [("z", 2, True), ("a", 2, True), ("hidden", 0, False)]:
                self.call("A", "PUT", CATALOG + "/dictionary-values", {"dictionary_code": code, "code": value,
                          "label": '中文,"quoted"\nline ' + value, "sort_order": order, "enabled": enabled, "expected_revision": 0})
            values = self.call("A", "GET", CATALOG + "/dictionary-values", query={"dictionary_code": code})
            options = self.call("A", "GET", CATALOG + "/dictionary-options", query={"dictionary_code": code})
            check([x["code"] for x in values] == ["hidden", "a", "z"] and [x["code"] for x in options] == ["a", "z"], "Exact stable sort/filter")
            check(values[1]["label"] == '中文,"quoted"\nline a', "Multiline Unicode label preserved")
            check(self.call("B", "GET", CATALOG + "/dictionary-values", query={"dictionary_code": code}) == [], "Same code B has no A values")
        self.case("catalog.dictionary.formal_crud_setup_sort_tenant_isolation", create, ["A", "B", "C"], "dictionary")
        def parent_disabled():
            parent = self.t["A"]["dictionaries"][0]
            parent.update(self.call("A", "PUT", CATALOG + "/dictionaries", {"code": code, "name": parent["name"], "enabled": False, "expected_revision": parent["revision"]}))
            check(self.call("A", "GET", CATALOG + "/dictionary-options", query={"dictionary_code": code}) == [], "Disabled parent options empty")
            check(len(self.call("A", "GET", CATALOG + "/dictionary-values", query={"dictionary_code": code})) == 3, "Disabled parent historical values retained")
        self.case("catalog.dictionary.disabled_parent_historical_values", parent_disabled, ["dictionary"])
        def stale():
            before = self.call("A", "GET", CATALOG + "/dictionary-values", query={"dictionary_code": code})
            self.call("A", "DELETE", CATALOG + "/dictionaries", {"dictionary_code": code, "code": "a", "expected_revision": 0}, status=409, code="catalog_revision_conflict")
            check(self.call("A", "GET", CATALOG + "/dictionary-values", query={"dictionary_code": code}) == before, "Stale delete preserves exact values")
        self.case("catalog.dictionary.value_delete_stale", stale, ["dictionary"])
        def delete():
            values = self.call("A", "GET", CATALOG + "/dictionary-values", query={"dictionary_code": code})
            for v in values:
                self.call("A", "DELETE", CATALOG + "/dictionaries", {"dictionary_code": code, "code": v["code"], "expected_revision": v["revision"]})
            check(self.call("A", "GET", CATALOG + "/dictionary-values", query={"dictionary_code": code}) == [], "Successful value deletion persists")
            p = self.t["A"]["dictionaries"][0]
            self.call("A", "DELETE", CATALOG + "/dictionaries", {"dictionary_code": code, "code": None, "expected_revision": p["revision"]})
            check(not any(x["code"] == code for x in self.call("A", "GET", CATALOG + "/dictionaries")), "Successful whole dictionary deletion")
            self.call("A", "GET", CATALOG + "/dictionary-options", query={"dictionary_code": code}, status=404, code="catalog_not_found")
        self.case("catalog.dictionary.successful_value_and_parent_delete", delete, ["dictionary"])

    def nav_command(self, value, **changes):
        cmd = {k: value[k] for k in ("code", "parent_code", "title", "route_key", "sort_order", "enabled")}
        cmd["expected_revision"] = value["revision"]
        cmd.update(changes)
        return cmd

    def navigation(self):
        def create():
            routes = self.call("A", "GET", CATALOG + "/navigation/routes")
            agents = next(x for x in routes if x["key"] == "agents")
            check(agents["path"] == "/admin/agents" and agents["permission"] == "foundation.agents.read" and agents["platform"] is False,
                  "Controlled route exact consumer path and permission")
            for suffix, parent, route, order in [("root", None, None, 80), ("agent", "root", "agents", 1), ("skills", "root", "skills", 2)]:
                value = self.call("A", "PUT", CATALOG + "/navigation", {"code": self.prefix + "_" + suffix,
                         "parent_code": None if parent is None else self.prefix + "_" + parent, "title": self.prefix + " " + suffix,
                         "route_key": route, "sort_order": order, "enabled": True, "expected_revision": 0})
                self.own("A", "navigation", value)
            check(not any(x["code"].startswith(self.prefix) for x in self.call("B", "GET", CATALOG + "/navigation")), "Owned tenant navigation does not leak")
        self.case("catalog.navigation.controlled_routes_formal_create", create, ["A", "B"], "navigation")
        def projection():
            token = self.login("A", self.member("A", 0))
            menu = self.api.call("GET", CATALOG + "/navigation/current", token)
            own = next(x for x in menu if x["code"] == self.prefix + "_root")
            check([x["code"] for x in own["children"]] == [self.prefix + "_agent"], "Current permissions filter exact owned children")
            check(own["children"][0]["path"] == "/admin/agents", "Actual menu consumer static path")
        self.case("catalog.navigation.current_permission_projection", projection, ["navigation"])
        def invalid():
            root = self.t["A"]["navigation"][0]
            before = [x for x in self.call("A", "GET", CATALOG + "/navigation") if x["code"].startswith(self.prefix)]
            self.call("A", "PUT", CATALOG + "/navigation", self.nav_command(root, parent_code=self.prefix + "_agent"), status=400, code="catalog_invalid")
            self.call("A", "PUT", CATALOG + "/navigation", self.nav_command(root, route_key="unregistered_route"), status=400, code="catalog_invalid")
            self.call("A", "DELETE", CATALOG + "/navigation", {"code": root["code"], "expected_revision": root["revision"]}, status=400, code="catalog_invalid")
            check([x for x in self.call("A", "GET", CATALOG + "/navigation") if x["code"].startswith(self.prefix)] == before, "Cycle/route/referenced-parent rejection preserves tree")
        self.case("catalog.navigation.cycle_unregistered_route_parent_delete", invalid, ["navigation"])
        def update_disable():
            root = self.t["A"]["navigation"][0]; old = dict(root)
            root.update(self.call("A", "PUT", CATALOG + "/navigation", self.nav_command(root, enabled=False)))
            check(root["revision"] == old["revision"] + 1, "Navigation update CAS")
            menu = self.api.call("GET", CATALOG + "/navigation/current", self.login("A", self.member("A", 0)))
            check(not any(x["code"] == root["code"] for x in menu), "Disabled parent cuts complete subtree")
            self.call("A", "PUT", CATALOG + "/navigation", self.nav_command(old, title="stale"), status=409, code="catalog_revision_conflict")
            check(next(x for x in self.call("A", "GET", CATALOG + "/navigation") if x["code"] == root["code"]) == root, "Stale update preserves disabled subtree")
        self.case("catalog.navigation.update_disabled_parent_stale", update_disable, ["navigation"])
        def delete():
            for n in reversed(self.t["A"]["navigation"]):
                self.call("A", "DELETE", CATALOG + "/navigation", {"code": n["code"], "expected_revision": n["revision"]})
            check(not any(x["code"].startswith(self.prefix) for x in self.call("A", "GET", CATALOG + "/navigation")), "Successful complete own navigation deletion")
        self.case("catalog.navigation.successful_children_and_parent_delete", delete, ["navigation"])

    def traverse(self, endpoint, token, limit=5, unread=False):
        cursor = None; rows = []; cursors = set()
        for _ in range(100):
            page = self.api.call("GET", endpoint, token, query={"cursor": cursor, "limit": limit,
                                 **({"unread": unread} if endpoint.endswith("inbox") else {})})
            check(len(page["items"]) <= limit, "Cursor bounded page")
            rows.extend(page["items"])
            if not page["has_more"]:
                check(page["next_cursor"] is None, "Terminal cursor null")
                break
            cursor = page["next_cursor"]
            check(cursor and cursor not in cursors, "Cursor must progress")
            cursors.add(cursor)
        else:
            raise AssertionError("Cursor did not terminate")
        check(len({x["id"] for x in rows}) == len(rows), "Complete cursor traversal no duplicate IDs")
        return rows

    def messaging(self):
        def publish():
            item = self.t["A"]
            # Normalize one actor to self after scope cases; all other fixtures are self.
            m = self.member("A", 1)
            m.update(self.call("A", "POST", ACCESS + "/member-roles", {"member_id": m["id"], "role_ids": [item["roles"][0]["id"]], "lock_version": m["lock_version"]}))
            for index in range(24):
                if index < 8:
                    audience, target, expected = "members", [item["members"][0]["id"], item["members"][1]["id"]], {0, 1}
                elif index < 16:
                    audience, target, expected = "departments", [item["departments"][0]["id"], item["departments"][1]["id"]], {0, 1, 5, 6, 10, 11}
                else:
                    audience, target, expected = "roles", [item["roles"][0]["id"]], set(range(12))
                cmd = {"title": f"{self.prefix} announcement {index:02}", "body": f"body 中文 {index:02}", "audience": audience,
                       "recipient_ids": target, "email": False, "deduplication_key": f"{self.prefix}_announcement_{index:02}"}
                announcement_id = self.call("A", "POST", MESSAGING + "/announcements/publish", cmd)
                self.own("A", "announcements", {"id": announcement_id, "command": cmd, "expected_indices": sorted(expected)})
            rows = [x for x in self.traverse(MESSAGING + "/announcements", item["token"], 7) if x["title"].startswith(self.prefix)]
            check(len(rows) == 24 and {x["id"] for x in rows} == {x["id"] for x in item["announcements"]}, "All 24 announcements exact history traversal")
            expected_counts = {a["id"]: len(a["expected_indices"]) for a in item["announcements"]}
            check(all(x["recipients"] == expected_counts[x["id"]] for x in rows), "Independent exact member/department/role recipient counts")
        self.case("messaging.publish_24_complex_audiences_history_cursor", publish, ["A"], "messages")
        def inbox():
            self.inboxes = {}
            for index in [0, 1, 2, 5, 11]:
                token = self.login("A", self.member("A", index))
                rows = self.traverse(MESSAGING + "/inbox", token, 5)
                expected_titles = {a["command"]["title"] for a in self.t["A"]["announcements"] if index in a["expected_indices"]}
                check({x["title"] for x in rows} == expected_titles and len(rows) == len(expected_titles), "Exact audience membership and no extra inbox messages")
                check(self.api.call("GET", MESSAGING + "/unread", token) == len(expected_titles), "Independent exact unread count")
                self.inboxes[index] = rows
            for tenant in "BC":
                token = self.login(tenant, self.member(tenant, 0))
                check(self.traverse(MESSAGING + "/inbox", token) == [] and self.api.call("GET", MESSAGING + "/unread", token) == 0,
                      "Cross-tenant fresh member has no A messages")
        self.case("messaging.exact_audience_inbox_cursor_unread_tenant_isolation", inbox, ["messages", "B", "C"], "inboxes")
        def read():
            own = self.inboxes[0][0]; token = self.login("A", self.member("A", 0))
            before = self.api.call("GET", MESSAGING + "/unread", token)
            self.api.call("POST", MESSAGING + "/read", token, {"id": own["id"]})
            first = self.traverse(MESSAGING + "/inbox", token)
            read_at = next(x["read_at"] for x in first if x["id"] == own["id"])
            check(read_at is not None and self.api.call("GET", MESSAGING + "/unread", token) == before - 1, "Mark read decrements exactly once")
            self.api.call("POST", MESSAGING + "/read", token, {"id": own["id"]})
            second = self.traverse(MESSAGING + "/inbox", token)
            check(next(x["read_at"] for x in second if x["id"] == own["id"]) == read_at, "Repeated markRead retains first timestamp")
            check(self.api.call("GET", MESSAGING + "/unread", token) == before - 1, "MarkRead idempotent unread")
            check(len(self.traverse(MESSAGING + "/inbox", token, unread=True)) == before - 1, "Unread cursor filters read item")
            self.api.call("POST", MESSAGING + "/read", self.login("A", self.member("A", 1)), {"id": own["id"]}, status=404, code="NOTIFICATION_NOT_FOUND")
            self.api.call("POST", MESSAGING + "/read", self.login("B", self.member("B", 0)), {"id": own["id"]}, status=404, code="NOTIFICATION_NOT_FOUND")
        self.case("messaging.mark_read_idempotent_member_tenant_separation", read, ["inboxes"])
        def replay():
            original = self.t["A"]["announcements"][0]
            token = self.login("A", self.member("A", 1)); before = self.api.call("GET", MESSAGING + "/unread", token)
            cmd = dict(original["command"], recipient_ids=list(reversed(original["command"]["recipient_ids"])))
            check(self.call("A", "POST", MESSAGING + "/announcements/publish", cmd) == original["id"], "Normalized identical request replay exact original ID")
            self.call("A", "POST", MESSAGING + "/announcements/publish", dict(cmd, body="changed"), status=409, code="MESSAGING_CONFLICT")
            check(self.api.call("GET", MESSAGING + "/unread", token) == before, "Replay and conflict create no inbox effects")
        self.case("messaging.publish_replay_normalized_conflict", replay, ["messages"])
        def withdraw():
            original = self.t["A"]["announcements"][0]
            token = self.login("A", self.member("A", 1)); before = self.api.call("GET", MESSAGING + "/unread", token)
            self.call("A", "POST", MESSAGING + "/announcements/withdraw", {"id": original["id"]})
            check(self.api.call("GET", MESSAGING + "/unread", token) == before - 1, "Withdrawal removes exact unread notification")
            check(not any(x["title"] == original["command"]["title"] for x in self.traverse(MESSAGING + "/inbox", token)), "Withdrawn body absent from inbox")
            history = self.traverse(MESSAGING + "/announcements", self.t["A"]["token"])
            check(next(x["status"] for x in history if x["id"] == original["id"]) == "withdrawn", "Withdrawn history retained")
            self.call("A", "POST", MESSAGING + "/announcements/withdraw", {"id": original["id"]}, status=409, code="MESSAGING_CONFLICT")
            self.call("B", "POST", MESSAGING + "/announcements/withdraw", {"id": original["id"]}, status=409, code="MESSAGING_CONFLICT")
        self.case("messaging.withdraw_history_unread_repeat_foreign", withdraw, ["messages", "B"])
        def invalid_cursor():
            token = self.login("A", self.member("A", 0))
            self.api.call("GET", MESSAGING + "/inbox", token, query={"cursor": "invalid", "limit": 5}, status=400, code="MESSAGING_INVALID_INPUT")
            self.api.call("GET", MESSAGING + "/inbox", token, query={"limit": 101}, status=400, code="MESSAGING_INVALID_INPUT")
        self.case("messaging.cursor_and_limit_exact_invalid_contract", invalid_cursor, ["A"])

    def successful_deletes(self):
        def create_delete(kind, body):
            value = self.call("A", "POST", ACCESS + "/" + kind, body)
            self.own("A", kind, value)
            self.call("A", "DELETE", ACCESS + "/" + kind, {"id": value["id"], "lock_version": value["lock_version"]})
            check(not any(x["id"] == value["id"] for x in self.call("A", "GET", ACCESS + "/" + kind)), "Successful delete persists absence")
            self.call("A", "DELETE", ACCESS + "/" + kind, {"id": value["id"], "lock_version": value["lock_version"]}, status=404, code="NOT_FOUND")
        for kind, body in [("departments", {"code": self.prefix + "_scratch_d", "name": "scratch department", "parent_id": None}),
                           ("positions", {"code": self.prefix + "_scratch_p", "name": "scratch position"}),
                           ("roles", {"code": self.prefix + "_scratch_r", "name": "scratch role", "data_scope": "self", "permissions": READ_PERMS, "department_ids": []})]:
            self.case("access." + kind + ".successful_delete_and_missing", lambda k=kind, b=body: create_delete(k, b), ["A"])

    def cleanup_one(self, tenant, kind, entity, action):
        key = entity.get("code", entity.get("username", entity.get("agent_key", entity.get("id"))))
        owned = any(x["tenant"] == tenant and x["kind"] == kind and x["entity"] is entity for x in self.owned)
        check(owned, "Cleanup must have exact owned receipt")
        if kind not in ("announcements",):
            check(str(key).startswith(self.prefix), "Cleanup target must retain owned prefix")
        try:
            action()
            row = {"tenant": tenant, "kind": kind, "key": key, "status": "PASS"}
        except Exception as error:
            row = {"tenant": tenant, "kind": kind, "key": key, "status": "FAIL", "reason": str(error)[:700]}
        self.cleanup_receipts.append(row)
        save(self.root / "cleanup.json", self.cleanup_receipts)

    def cleanup(self):
        self.api.case = "owned_cleanup"
        for tenant in "ABC":
            item = self.t[tenant]
            if not item.get("token"):
                continue
            history_by_id = {}
            if item["announcements"]:
                try:
                    history_by_id = {x["id"]: x for x in self.traverse(MESSAGING + "/announcements", item["token"], 100)}
                except Exception:
                    pass  # Every owned withdrawal still records its own failure below.
            for a in item["announcements"]:
                def withdraw(a=a):
                    current = history_by_id[a["id"]]
                    check(current["title"].startswith(self.prefix), "Announcement ownership title")
                    if current["status"] != "withdrawn":
                        self.call(tenant, "POST", MESSAGING + "/announcements/withdraw", {"id": a["id"]})
                self.cleanup_one(tenant, "announcements", a, withdraw)
            for a in item["agents"]:
                def delete_agent(a=a):
                    self.call(tenant, "DELETE", "/api/v1/agents/delete", {"agent_id": str(a["id"])})
                self.cleanup_one(tenant, "agents", a, delete_agent)
            for m in item["members"]:
                def disable(m=m):
                    page = self.call(tenant, "GET", ACCESS + "/members", query={"keyword": m["username"], "page_size": 100})
                    current = next(x for x in page["items"] if x["id"] == m["id"])
                    check(current["username"].startswith(self.prefix), "Member cleanup receipt exact username")
                    if current["role_ids"]:
                        current = self.call(tenant, "POST", ACCESS + "/member-roles", {"member_id": current["id"], "role_ids": [], "lock_version": current["lock_version"]})
                    self.call(tenant, "PUT", ACCESS + "/members", self.member_command(current, status="disabled", department_id=None, position_id=None))
                    final = next(x for x in self.call(tenant, "GET", ACCESS + "/members", query={"keyword": m["username"]})["items"] if x["id"] == m["id"])
                    check(final["status"] == "disabled" and final["role_ids"] == [] and final["department_id"] is None and final["position_id"] is None,
                          "Owned member tombstone detached and disabled")
                self.cleanup_one(tenant, "members", m, disable)
            for kind in ["roles", "positions", "departments"]:
                for entity in reversed(item[kind]):
                    def remove(kind=kind, entity=entity):
                        current = next((x for x in self.call(tenant, "GET", ACCESS + "/" + kind) if x["id"] == entity["id"]), None)
                        if current:
                            check(current["code"].startswith(self.prefix), "Access cleanup current ownership")
                            self.call(tenant, "DELETE", ACCESS + "/" + kind, {"id": current["id"], "lock_version": current["lock_version"]})
                        check(not any(x["id"] == entity["id"] for x in self.call(tenant, "GET", ACCESS + "/" + kind)), "Cleanup deletion persisted")
                    self.cleanup_one(tenant, kind, entity, remove)
            for n in reversed(item["navigation"]):
                def remove_nav(n=n):
                    current = next((x for x in self.call(tenant, "GET", CATALOG + "/navigation") if x["code"] == n["code"]), None)
                    if current:
                        self.call(tenant, "DELETE", CATALOG + "/navigation", {"code": current["code"], "expected_revision": current["revision"]})
                self.cleanup_one(tenant, "navigation", n, remove_nav)
            for d in item["dictionaries"]:
                def remove_dict(d=d):
                    current = next((x for x in self.call(tenant, "GET", CATALOG + "/dictionaries") if x["code"] == d["code"]), None)
                    if not current:
                        return
                    for v in self.call(tenant, "GET", CATALOG + "/dictionary-values", query={"dictionary_code": d["code"]}):
                        self.call(tenant, "DELETE", CATALOG + "/dictionaries", {"dictionary_code": d["code"], "code": v["code"], "expected_revision": v["revision"]})
                    self.call(tenant, "DELETE", CATALOG + "/dictionaries", {"dictionary_code": d["code"], "code": None, "expected_revision": current["revision"]})
                self.cleanup_one(tenant, "dictionaries", d, remove_dict)
            for actor in item["actors"]:
                def disable_actor(actor=actor):
                    # Original admin was never edited; it completes only this owned actor's cleanup.
                    item["token"] = item["bootstrap_token"]
                    current = next(x for x in self.call(tenant, "GET", ACCESS + "/members", query={"keyword": actor["username"]})["items"] if x["id"] == actor["id"])
                    check(current["username"].startswith(self.prefix), "Owned administrator cleanup identity")
                    current = self.call(tenant, "POST", ACCESS + "/member-roles", {"member_id": current["id"], "role_ids": [], "lock_version": current["lock_version"]})
                    final = self.call(tenant, "PUT", ACCESS + "/members", self.member_command(current, status="disabled", department_id=None, position_id=None))
                    check(final["status"] == "disabled" and final["role_ids"] == [], "Owned administrator disabled and roles revoked")
                self.cleanup_one(tenant, "actors", actor, disable_actor)

    def run(self):
        try:
            for tenant in "ABC":
                self.case("access.formal_fixture_" + tenant, lambda t=tenant: self.setup(t), ready=tenant)
            if "A" in self.ready:
                self.organization(); self.scopes(); self.resources()
                self.dictionaries(); self.navigation(); self.messaging(); self.successful_deletes()
            else:
                entered = {x["id"] for x in self.cases}
                for identity in PLANNED_CASE_IDS[:-2]:
                    if identity not in entered:
                        self.blocked(identity, "A formal API fixture failed; dependent business scenario was not entered")
            self.blocked("access.last_existing_administrator_and_builtin_mutation", "Existing tenant-admin and built-in roles are shared nonowned fixtures; no request that could disable or alter them. Existing complex HTTP JUnit covers rejection branches.")
            self.blocked("catalog.dictionary.trusted_consumer_reference_delete", "Dictionary reference/unreference has no public HTTP materialization contract; existing Postgres/JUnit coverage is retained, no database fixture injection authorized.")
        finally:
            self.cleanup()
        counts = Counter(x["status"] for x in self.cases)
        operations = {}
        for line in self.api.stream.read_text(encoding="utf-8").splitlines():
            receipt = json.loads(line)
            if receipt["event"] != "intent":
                continue
            identity = receipt["method"] + " " + receipt["path"]
            row = operations.setdefault(identity, {"operation": identity, "business_cases": [], "fixture_calls": 0,
                                                  "business_calls": 0, "cleanup_calls": 0})
            category = "cleanup" if receipt["case"] == "owned_cleanup" else "fixture" if receipt["case"].startswith("access.formal_fixture_") else "business"
            row[category + "_calls"] += 1
            if category == "business" and receipt["case"] not in row["business_cases"]:
                row["business_cases"].append(receipt["case"])
        save(self.root / "operation-matrix.json", sorted(operations.values(), key=lambda x: x["operation"]))
        summary = {"prefix": self.prefix, "cases": len(self.cases), "counts": dict(counts), "http_calls": self.api.calls,
                   "business_counts": dict(Counter(x["status"] for x in self.cases if x["category"] == "business")),
                   "fixture_counts": dict(Counter(x["status"] for x in self.cases if x["category"] == "fixture")),
                   "cleanup_counts": dict(Counter(x["status"] for x in self.cleanup_receipts)),
                   "retained": {"disabled_detached_member_tombstones": sum(len(t["members"]) + len(t["actors"]) for t in self.t.values()),
                                "withdrawn_announcement_history": len(self.t["A"]["announcements"]),
                                "tenant_navigation_initialization": "First write clones the authorized tenant template; no HTTP deinitialization contract."},
                   "provider_calls": 0, "completed_at": now()}
        save(self.root / "summary.json", summary)
        return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--cleanup-from", type=Path, help="Append correction cleanup for receipted owned diagnostic fixtures only")
    args = parser.parse_args()
    check(1 <= args.rounds <= 3, "One to three serial rounds")
    manifest = json.loads((args.environment / "environment.json").read_text(encoding="utf-8-sig"))
    provision = json.loads((args.environment / "provision.json").read_text(encoding="utf-8-sig"))
    check(manifest["batch"] == "sf-fulltest-349ef3c037", "Only the approved current isolated batch")
    check({x["slug"] for x in provision["tenants"]} == {"full-test-a", "full-test-b", "full-test-c"}, "Approved A/B/C only")
    service = manifest["services"]["institution-service"]
    check(service["status"] == "READY" and Path(service["jar"]).resolve().parent == args.environment.resolve(), "Isolated READY service artifact")
    env = load_environment(args.environment / "isolated.env")
    credentials = env.get("SF_ADMIN_USER", "tenant-admin"), env.get("SF_ADMIN_PASSWORD", env.get("SF_PASSWORD"))
    check(credentials[1], "Private isolated tenant credentials missing")
    args.output.mkdir(parents=True, exist_ok=False)
    save(args.output / "source-binding.json", {"batch": manifest["batch"], "institution_base": service["base_url"],
         "institution_jar_sha256": hashlib.sha256(Path(service["jar"]).read_bytes()).hexdigest(),
         "source_sha256": {s: hashlib.sha256((REPO / s).read_bytes()).hexdigest() for s in SOURCES},
         "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), "started_at": now(),
         "scope": "Public API only; no SQL, paid calls, production changes, service restarts, or shared actor mutations."})
    results = []
    if args.cleanup_from:
        old = json.loads((args.cleanup_from / "summary.json").read_text(encoding="utf-8"))
        owned = json.loads((args.cleanup_from / "owned-entities.json").read_text(encoding="utf-8"))
        root = args.output / "owned-cleanup-correction"
        root.mkdir()
        api = Api(service["base_url"], root / "operations.jsonl")
        correction = Round(api, root, old["prefix"], credentials, provision["tenants"])
        for receipt in owned:
            correction.t[receipt["tenant"]][receipt["kind"]].append(receipt["entity"])
            correction.owned.append(receipt)
        for tenant in "ABC":
            correction.case("cleanup.bootstrap_" + tenant, lambda t=tenant: correction.bootstrap_actor(t))
        correction.cleanup()
        save(args.output / "summary.json", {"original_evidence": str(args.cleanup_from.resolve()), "cleanup_counts": dict(Counter(x["status"] for x in correction.cleanup_receipts)), "cases": correction.cases, "http_calls": api.calls})
        return 1 if any(x["status"] != "PASS" for x in correction.cleanup_receipts) else 0
    batch_tag = secrets.token_hex(4)
    for index in range(1, args.rounds + 1):
        root = args.output / f"round-{index}"
        root.mkdir()
        api = Api(service["base_url"], root / "operations.jsonl")
        summary = Round(api, root, f"hbiz_{batch_tag}_r{index}", credentials, provision["tenants"]).run()
        results.append(summary)
        save(args.output / "summary.json", {"rounds": results, "completed_rounds": len(results),
             "counts": dict(sum((Counter(r["counts"]) for r in results), Counter())), "provider_calls": 0})
    return 1 if any(r["counts"].get("FAIL") or r["cleanup_counts"].get("FAIL") for r in results) else 0


if __name__ == "__main__":
    sys.exit(main())
