"""本批免费空知识库治理/API边界；仅明确allowlist，绝不调用上传、索引、重建或检索。"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import json
import re
import secrets
from pathlib import Path
import sys
from urllib.parse import urlparse
import uuid

from run_suite import REPO, load_environment

sys.path.insert(0, str(REPO / "tools/chat_regression"))
from client import Api, ApiError

ROOT = REPO / ".runtime-logs/full-project-test-20261012"
SOURCE = ROOT / "environment"
OUTPUT = ROOT / "knowledge-resource-api-v2"
ALLOWED = {
    ("POST", "/auth/login"), ("GET", "/settings/model-selections"),
    ("PUT", "/settings/model-selections"), ("GET", "/knowledge/bases"),
    ("POST", "/knowledge/bases"), ("GET", "/knowledge/bases/detail"),
    ("GET", "/knowledge/bases/revisions"), ("POST", "/knowledge/bases/revisions"),
    ("POST", "/knowledge/bases/revisions/ready"), ("POST", "/knowledge/bases/revisions/publish"),
    ("PATCH", "/knowledge/bases/update"), ("DELETE", "/knowledge/bases/delete"),
    ("GET", "/memories"), ("GET", "/memories/reviews"),
}
POLICY = {"model_invocable": False, "user_invocable": True,
          "allowed_operators": ["search"], "freshness_policy": "frozen",
          "citation_required": True}


def require(condition, code):
    if not condition:
        raise ValueError(code)


def save_new(path, value):
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2,
                  default=lambda item: item.isoformat() if isinstance(item, datetime) else str(item))


def pg_time(value):
    """PostgreSQL timestamptz 微秒精度四舍五入；不能直接忽略 createdAt。"""
    result = datetime.fromisoformat(value)
    fraction = re.search(r"\.(\d+)", value)
    if fraction and len(fraction.group(1)) > 6 and int(fraction.group(1)[6]) >= 5:
        result += timedelta(microseconds=1)
    return result.astimezone(timezone.utc)


def draft_identity(value):
    if not isinstance(value, dict):
        return None
    fields = ("id", "kb_id", "revision", "status", "source_versions", "policy", "ready_at", "published_at",
              "capability_version_id", "capability_content_hash")
    return {key: value.get(key) for key in fields}


def prepare_actors(env, provision, state):
    """通过正式平台API创建三专用actor；私有密码只留本批私有目录，不重复创建。"""
    directory = OUTPUT / "actors"
    directory.mkdir(parents=True, exist_ok=False)
    platform_base = state["services"]["platform-service"]["base_url"] + "/api/v1"
    require(platform_base == provision["platform"] and urlparse(platform_base).hostname == "127.0.0.1",
            "owned_platform_api_required")
    api = Api(platform_base)
    auth = api.request("POST", "/platform/auth/login", {"username": env["SF_PLATFORM_USER"], "password": env["SF_PLATFORM_PASSWORD"]}, _rate_retry=True)
    api = Api(platform_base, auth["access_token"], True)
    actors = {}
    for row in provision["tenants"]:
        slug = row["slug"]
        username = "knowledge-resource-admin-20261012"
        password = secrets.token_urlsafe(24)
        body = {"tenant_id": str(row["id"]), "payload": {"username": username, "password": password,
                "roles": ["tenant_admin"], "email": f"knowledge-resource-{slug}@example.test"}}
        with api.open("POST", "/admin/tenants/members", body, _rate_retry=True) as response:
            require(response.status == 201, "owned_actor_create_status")
            value = json.loads(response.read(1024 * 1024))
        require(value.get("code") == "OK", "owned_actor_create_envelope")
        member = value["data"]
        save_new(directory / f"{slug}-create.json", {"method": "POST", "path": "/admin/tenants/members",
            "http_status": 201, "request": redact(body), "response": redact(value)})
        actors[slug] = {"username": username, "password": password, "member_id": str(member["id"]), "tenant_id": str(row["id"])}
        # 每创建一次保存不可覆盖的原owner；后续失败也不猜测重新创建。
        save_new(directory / f"{slug}.private.json", actors[slug])
    save_new(directory / "actors.private.json", actors)
    print("READY dedicated Knowledge API actors; credentials retained privately; no model request", flush=True)


def redact(value):
    if isinstance(value, dict):
        return {key: "[REDACTED]" if any(word in key.lower() for word in
                ("password", "token", "credential", "secret", "api_key")) else redact(item)
                for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


class Round:
    def __init__(self, number, env, provision, state):
        self.number = number
        self.env, self.provision = env, provision
        self.tenants = {row["slug"]: row for row in provision["tenants"]}
        require(set(self.tenants) == {"full-test-a", "full-test-b", "full-test-c"}, "fixed_test_tenants_required")
        self.base = state["services"]["institution-service"]["base_url"] + "/api/v1"
        url = urlparse(self.base)
        require(self.base == provision["api"] and url.hostname == "127.0.0.1"
                and url.scheme == "http" and url.port not in (80, 8080, 8081, 8082), "owned_isolated_api_required")
        self.directory = OUTPUT / f"round-{number}"
        self.directory.mkdir(parents=True, exist_ok=False)
        self.prefix = f"knowledge-resource-api-r{number}-{uuid.uuid4().hex[:10]}"
        self.clients, self.selections, self.installed, self.owned, self.checks, self.traces = {}, {}, {}, {}, [], {}
        self.drafts, self.before_db, self.findings = {}, {}, []
        self.actors = json.loads((OUTPUT / "actors/actors.private.json").read_text(encoding="utf-8"))
        self.cleaning_up = False
        self.sequence = 0
        self.scope = {"round": number, "prefix": self.prefix, "api": self.base,
            "tenant_ids": {slug: str(row["id"]) for slug, row in self.tenants.items()},
            "source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "started_at": datetime.now(timezone.utc).isoformat(),
            "allowed_endpoints": sorted([list(row) for row in ALLOWED]),
            "coverage_limits": ["empty resources only; no upload/search/index/rebuild",
                "base list has no server pagination/filter parameters",
                "source-referenced deletion protection requires ingestion and is not covered",
                "pending Memory reads do not prove extraction/retrieval/semantic quality"],
            "paid_model_requests_dispatched": 0}
        save_new(self.directory / "scope.json", self.scope)

    def check(self, label, condition, detail=None):
        self.checks.append({"case": label, "outcome": "PASS" if condition else "FAIL", "detail": detail})

    def call(self, slug, label, method, path, body=None, expected=200, client=None):
        bare = path.split("?", 1)[0]
        require((method, bare) in ALLOWED, "endpoint_outside_free_allowlist")
        if bare.startswith("/knowledge/") and method in {"PATCH", "DELETE", "POST"} and bare != "/knowledge/bases":
            require(isinstance(body, dict) and str(body.get("kb_id")) in {str(value) for value in self.owned.values()},
                    "mutation_requires_this_round_owned_resource")
        self.sequence += 1
        receipt = {"sequence": self.sequence, "tenant_slug": slug, "method": method, "path": path,
            "started_at": datetime.now(timezone.utc).isoformat(), "request": redact(body), "expected_status": expected}
        try:
            api = client or self.clients[slug]
            with api.open(method, path, body, _rate_retry=True) as response:
                receipt["http_status"] = response.status
                raw = response.read(1024 * 1024 + 1)
            require(len(raw) <= 1024 * 1024, "response_bound_exceeded")
            value = json.loads(raw)
            receipt["response_sha256"] = hashlib.sha256(raw).hexdigest()
            if bare.startswith("/memories"):
                data = value.get("data")
                receipt["response"] = {"code": value.get("code"), "trace_id": value.get("trace_id"),
                    "items": [{"tenant_id": row.get("ref", {}).get("tenant_id"), "status": row.get("status"),
                               "scope": row.get("scope")} for row in data] if isinstance(data, list) else None}
            else:
                receipt["response"] = redact(value)
            trace = value.get("trace_id")
            if isinstance(trace, str) and trace:
                self.traces.setdefault(slug, []).append(trace)
            result = value.get("data") if value.get("code") == "OK" else None
            self.check(label, response.status == expected and (expected != 200 or value.get("code") == "OK"),
                       {"status": response.status, "code": value.get("code")})
        except ApiError as error:
            receipt["http_status"] = error.status_code
            self.check(label, error.status_code == expected, {"status": error.status_code})
            result = None
        receipt["finished_at"] = datetime.now(timezone.utc).isoformat()
        save_new(self.directory / f"{self.sequence:03}-{slug}-{label}.json", receipt)
        if receipt["http_status"] == 429 and not self.cleaning_up:
            raise ValueError("dedicated_actor_rate_limit_stops_round")
        return result

    def prepare(self):
        for slug in self.tenants:
            actor = self.actors[slug]
            require(actor["tenant_id"] == str(self.tenants[slug]["id"]), "owned_actor_tenant_identity")
            auth = self.call(slug, "login", "POST", "/auth/login", {
                "tenant_slug": slug, "username": actor["username"], "password": actor["password"]}, client=Api(self.base))
            require(isinstance(auth, dict) and auth.get("access_token"), "isolated_tenant_login_failed")
            self.clients[slug] = Api(self.base, auth["access_token"])
            self.clients[slug].interval = 1.0
            original = self.call(slug, "selections-before", "GET", "/settings/model-selections")
            require(isinstance(original, dict), "original_selection_receipt_required")
            self.selections[slug] = original
            selected = {**original, "embedding_model_config_id": str(self.provision["models"]["embedding"]["id"])}
            if original != selected:
                observed = self.call(slug, "select-embedding-fixture", "PUT", "/settings/model-selections", selected)
                self.installed[slug] = selected
                require(observed == selected, "embedding_selection_write_receipt_mismatch")
            created = self.call(slug, "create-empty-base", "POST", "/knowledge/bases", {
                "name": f"{self.prefix}-{slug}", "description": "owned free API governance fixture"})
            require(isinstance(created, dict) and created.get("id"), "owned_base_create_failed")
            self.owned[slug] = str(created["id"])

    def exercise(self):
        for slug, identity in self.owned.items():
            detail = self.call(slug, "empty-detail", "GET", f"/knowledge/bases/detail?kbId={identity}")
            self.check(f"{slug}:empty-counts", isinstance(detail, dict) and detail.get("document_count") == 0
                       and detail.get("chunk_count") == 0 and detail.get("active_revision_id") is None)
            listing = self.call(slug, "own-list", "GET", "/knowledge/bases")
            ids = {str(row["id"]) for row in listing.get("items", [])} if isinstance(listing, dict) else set()
            self.check(f"{slug}:list-total-identity", isinstance(listing, dict)
                       and listing.get("total") == len(listing.get("items", [])) and identity in ids
                       and not (set(self.owned.values()) - {identity}) & ids)
            draft = self.call(slug, "open-draft", "POST", "/knowledge/bases/revisions", {"kb_id": identity})
            reused = self.call(slug, "reuse-draft", "POST", "/knowledge/bases/revisions", {"kb_id": identity})
            self.check(f"{slug}:draft-identity-reused", isinstance(draft, dict) and draft_identity(reused) == draft_identity(draft)
                       and draft.get("status") == "draft" and draft.get("revision") == 1)
            require(isinstance(draft, dict) and draft.get("revision"), "draft_identity_required")
            self.drafts[slug] = draft
            self.check(f"{slug}:draft-created-at-pg-precision", isinstance(reused, dict)
                       and pg_time(draft["created_at"]) == pg_time(reused["created_at"]))
            number = draft["revision"]
            self.call(slug, "publish-before-ready", "POST", "/knowledge/bases/revisions/publish",
                      {"kb_id": identity, "revision": number}, expected=409)
            self.call(slug, "reject-empty-manifest", "POST", "/knowledge/bases/revisions/ready",
                      {"kb_id": identity, "revision": number, "payload": {"source_versions": [], "policy": POLICY}}, expected=400)
            self.call(slug, "reject-missing-source", "POST", "/knowledge/bases/revisions/ready",
                      {"kb_id": identity, "revision": number, "payload": {"source_versions": [self.prefix], "policy": POLICY}}, expected=422)
            revisions = self.call(slug, "revision-after-refusals", "GET", f"/knowledge/bases/revisions?kbId={identity}")
            self.check(f"{slug}:refusals-preserve-draft", isinstance(revisions, dict)
                       and revisions.get("total") == 1 and len(revisions.get("items", [])) == 1
                       and draft_identity(revisions["items"][0]) == draft_identity(draft)
                       and pg_time(revisions["items"][0]["created_at"]) == pg_time(draft["created_at"]))
            changed = self.call(slug, "rename-settings", "PATCH", "/knowledge/bases/update", {"kb_id": identity,
                "payload": {"name": f"{self.prefix}-{slug}-updated", "chunk_size": 400, "chunk_overlap": 50,
                            "retrieval_mode": "full_text", "indexing_mode": "economical", "top_k": 3, "rerank_enabled": False}})
            self.check(f"{slug}:settings-persisted", isinstance(changed, dict) and changed.get("name", "").endswith("-updated")
                       and changed.get("chunk_size") == 400 and changed.get("chunk_overlap") == 50
                       and changed.get("retrieval_mode") == "full_text" and changed.get("top_k") == 3 and changed.get("rerank_enabled") is False)
            self.call(slug, "reject-invalid-status", "PATCH", "/knowledge/bases/update",
                      {"kb_id": identity, "payload": {"status": "invalid"}}, expected=400)
            archived = self.call(slug, "archive", "PATCH", "/knowledge/bases/update",
                                 {"kb_id": identity, "payload": {"status": "archived"}})
            self.check(f"{slug}:archived-state", isinstance(archived, dict) and archived.get("status") == "archived")
            self.call(slug, "archive-draft-refused", "POST", "/knowledge/bases/revisions", {"kb_id": identity}, expected=409)
            restored = self.call(slug, "restore", "PATCH", "/knowledge/bases/update", {"kb_id": identity, "payload": {"status": "active"}})
            self.check(f"{slug}:restored-state", isinstance(restored, dict) and restored.get("status") == "active")
            memory = self.call(slug, "memory-combined-filter", "GET", "/memories?status=ACTIVE&scope=USER_AGENT&type=preference&limit=1")
            self.check(f"{slug}:memory-read-tenant-bound", isinstance(memory, list) and len(memory) <= 1
                       and all(str(row.get("ref", {}).get("tenant_id", {}).get("value")) == str(self.tenants[slug]["id"]) for row in memory))
            self.call(slug, "memory-pending", "GET", "/memories/reviews?limit=1")
            for foreign, foreign_id in self.owned.items():
                if foreign == slug:
                    continue
                self.call(slug, f"foreign-{foreign}-read", "GET", f"/knowledge/bases/detail?kbId={foreign_id}", expected=403)
                self.call(slug, f"foreign-{foreign}-write", "PATCH", "/knowledge/bases/update",
                          {"kb_id": foreign_id, "payload": {"name": "must-not-write"}}, expected=403)
                self.call(slug, f"foreign-{foreign}-delete", "DELETE", "/knowledge/bases/delete", {"kb_id": foreign_id}, expected=403)
        for slug, identity in self.owned.items():
            detail = self.call(slug, "owner-after-cross-tenant-denials", "GET", f"/knowledge/bases/detail?kbId={identity}")
            self.check(f"{slug}:foreign-denials-preserve-owner", isinstance(detail, dict)
                       and detail.get("name") == f"{self.prefix}-{slug}-updated" and detail.get("status") == "active")

    def database_receipt(self, phase):
        """只读、精确 tenant+本批resource/trace；不读正文，不查询未归属对象。"""
        import psycopg2
        url = urlparse(self.env["SPRING_DATASOURCE_URL"].removeprefix("jdbc:"))
        require(url.scheme == "postgresql" and url.hostname == "127.0.0.1" and url.port not in (5432, 5433)
                and not url.query, "isolated_readonly_database_required")
        with psycopg2.connect(host=url.hostname, port=url.port, dbname=url.path[1:],
                user=self.env["SPRING_DATASOURCE_USERNAME"], password=self.env["SPRING_DATASOURCE_PASSWORD"]) as connection:
            connection.set_session(readonly=True)
            with connection.cursor() as cursor:
                cursor.execute("SELECT current_setting('transaction_read_only'),rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user")
                mode = cursor.fetchone()
                rows = {"read_only": mode[0] == "on", "role_superuser": mode[1], "role_bypass_rls": mode[2], "tenants": {}}
                for slug, identity in self.owned.items():
                    tenant = str(self.tenants[slug]["id"])
                    cursor.execute("SELECT set_config('app.tenant_id',%s,true)", (tenant,))
                    item = {"tenant_id": tenant, "kb_id": identity, "http_trace_count": len(set(self.traces.get(slug, [])))}
                    cursor.execute("SELECT id,tenant_id,status,embedding_model_config_id,embedding_model_revision,embedding_dimensions FROM nw_knowledge_bases WHERE tenant_id=%s AND id=%s", (tenant, identity))
                    item["base_identity"] = cursor.fetchall()
                    cursor.execute("SELECT id,tenant_id,kb_id,revision,status,created_at FROM nw_knowledge_base_revisions WHERE tenant_id=%s AND kb_id=%s", (tenant, identity))
                    item["revision_identities"] = cursor.fetchall()
                    traces = list(set(self.traces.get(slug, [])))
                    cursor.execute("SELECT count(*) FROM nw_external_invocations WHERE tenant_id=%s AND trace_id=ANY(%s)", (tenant, traces))
                    item["external_invocations_for_exact_http_traces"] = cursor.fetchone()[0]
                    cursor.execute("SELECT count(*) FROM nw_usage_events u WHERE u.tenant_id=%s AND (u.request_id=ANY(%s) OR EXISTS(SELECT 1 FROM nw_external_invocations e WHERE e.tenant_id=u.tenant_id AND e.attempt_id=u.attempt_id AND e.trace_id=ANY(%s)))", (tenant, traces, traces))
                    item["usage_events_for_trace_request_or_exact_attempt_join"] = cursor.fetchone()[0]
                    rows["tenants"][slug] = item
                    self.check(f"{slug}:db-{phase}-no-external-dispatch", item["external_invocations_for_exact_http_traces"] == 0)
                    if phase == "before-delete":
                        self.check(f"{slug}:db-owned-base-identity", len(item["base_identity"]) == 1
                            and str(item["base_identity"][0][1]) == tenant and len(item["revision_identities"]) == 1
                            and item["revision_identities"][0][4] == "draft")
                        if slug in self.drafts and item["revision_identities"]:
                            self.check(f"{slug}:draft-created-at-matches-real-db", item["revision_identities"][0][5].astimezone(timezone.utc)
                                       == pg_time(self.drafts[slug]["created_at"]))
                        self.before_db[slug] = item["revision_identities"]
                    else:
                        self.check(f"{slug}:db-base-deleted", item["base_identity"] == [])
                        self.check(f"{slug}:retained-draft-not-mutated", item["revision_identities"] == []
                                   or item["revision_identities"] == self.before_db.get(slug))
                        if item["revision_identities"]:
                            self.findings.append({"tenant_slug": slug, "kb_id": identity,
                                "fact": "empty deleted base retains its original unpublished draft revision",
                                "contract_defect_decision": "UNKNOWN; current delete contract only guarantees base deletion and source retention; no explicit draft cleanup requirement found",
                                "http_read_or_publish_after_delete": "must remain denied; see strict cleanup API receipts"})
                save_new(self.directory / f"database-{phase}.json", rows)

    def cleanup(self):
        self.cleaning_up = True
        for slug, identity in self.owned.items():
            self.call(slug, "delete-owned-empty", "DELETE", "/knowledge/bases/delete", {"kb_id": identity})
            self.call(slug, "deleted-detail-denied", "GET", f"/knowledge/bases/detail?kbId={identity}", expected=403)
            self.call(slug, "deleted-revisions-denied", "GET", f"/knowledge/bases/revisions?kbId={identity}", expected=403)
            self.call(slug, "deleted-draft-reopen-denied", "POST", "/knowledge/bases/revisions", {"kb_id": identity}, expected=403)
            if slug in self.drafts:
                self.call(slug, "deleted-draft-publish-denied", "POST", "/knowledge/bases/revisions/publish",
                          {"kb_id": identity, "revision": self.drafts[slug]["revision"]}, expected=403)
            listing = self.call(slug, "list-after-delete", "GET", "/knowledge/bases")
            self.check(f"{slug}:deleted-list-absent", isinstance(listing, dict)
                       and identity not in {str(row["id"]) for row in listing.get("items", [])})
        for slug, selected in self.installed.items():
            current = self.call(slug, "selections-before-restore", "GET", "/settings/model-selections")
            if current != selected:
                self.check(f"{slug}:selection-concurrent-drift-stop-restore", False)
                continue
            restored = self.call(slug, "restore-original-selections", "PUT", "/settings/model-selections", self.selections[slug])
            self.check(f"{slug}:selection-restore-receipt", restored == self.selections[slug])
        for slug, original in self.selections.items():
            current = self.call(slug, "selections-restored-readback", "GET", "/settings/model-selections")
            self.check(f"{slug}:original-selections-exact-readback", current == original)

    def run(self):
        problem = None
        try:
            self.prepare()
            self.exercise()
            self.database_receipt("before-delete")
        except Exception as error:
            problem = type(error).__name__
            self.check("execution-infrastructure", False, {"error_type": problem})
        finally:
            try:
                self.cleanup()
                self.database_receipt("after-delete")
            except Exception as error:
                self.check("cleanup-infrastructure", False, {"error_type": type(error).__name__})
        summary = {**self.scope, "owned_resources": self.owned, "checks": self.checks,
            "owned_actor_ids": {slug: value["member_id"] for slug, value in self.actors.items()}, "findings": self.findings,
            "passed": sum(row["outcome"] == "PASS" for row in self.checks),
            "failed": sum(row["outcome"] == "FAIL" for row in self.checks), "http_requests": self.sequence,
            "finished_at": datetime.now(timezone.utc).isoformat()}
        summary["outcome"] = "FAIL" if summary["failed"] else "PASS"
        save_new(self.directory / "summary.json", summary)
        print(json.dumps({key: summary[key] for key in ("round", "outcome", "passed", "failed", "http_requests", "paid_model_requests_dispatched")}), flush=True)
        return 1 if summary["failed"] else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--round", type=int, choices=(1, 2, 3), required=True)
    parser.add_argument("--execute-api", action="store_true")
    parser.add_argument("--prepare-actors", action="store_true")
    args = parser.parse_args()
    env = load_environment(SOURCE / "isolated.env")
    provision = json.loads((SOURCE / "provision.json").read_text(encoding="utf-8"))
    state = json.loads((SOURCE / "environment.json").read_text(encoding="utf-8"))
    if args.prepare_actors:
        require(not args.execute_api, "prepare_actors_is_separate_from_api_round")
        prepare_actors(env, provision, state)
        return 0
    if not args.execute_api:
        print(f"READY free Knowledge governance round={args.round}; no API/DB/mutation/paid dispatch")
        return 0
    return Round(args.round, env, provision, state).run()


if __name__ == "__main__":
    sys.exit(main())
