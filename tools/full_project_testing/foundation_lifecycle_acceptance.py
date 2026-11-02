"""Free real-HTTP Foundation lifecycles using new owned tenants for every round.

No model execution, shared tenant configuration, SQL, service restart or automatic
write replay. Every request has append-only intent/completion evidence. Public
job/file retention and disabled tenant tombstones are reported explicitly.
"""
from __future__ import annotations

import argparse
from collections import Counter
import csv
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request

from run_suite import REPO, load_environment, save
from host_business_acceptance import check, redact, now

F = "/api/v1/tenant/foundation/files"
J = "/api/v1/tenant/foundation/jobs"
C = "/api/v1/tenant/foundation/catalog"
A = "/api/v1/tenant/foundation/access"
O = "/api/v1/tenant/foundation/operations"
PASSWORD = "Foundation-Lifecycle-20261012!"
READ = ["foundation.files.read", "foundation.jobs.read", "foundation.dictionary.read",
        "foundation.preferences.manage", "foundation.settings.read", "foundation.operations.audit_read"]
SOURCES = [
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/assets/FoundationFileService.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/assets/FoundationFilePolicy.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/assets/web/FoundationFilesEndpoint.java",
    "backend-java/skillforge-core-shared/src/main/resources/mapper/foundation/FoundationFileMapper.xml",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/jobs/FoundationJobService.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/jobs/FoundationCsv.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/catalog/preferences/PreferenceService.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/security/FoundationSecurityService.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/operations/FoundationAuditService.java",
    "backend-java/skillforge-core-shared/src/main/java/com/skillforge/core/shared/foundation/operations/FoundationOperationsStatus.java",
    "backend-java/skillforge-platform-service/src/main/java/com/skillforge/platform/controller/SystemSettingsController.java",
    "backend-java/skillforge-institution-service/src/main/java/com/skillforge/institution/controller/ModelSelectionController.java",
]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """认证请求不跟随重定向，避免凭据外发和未知写入的自动重发。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Api:
    def __init__(self, state, stream):
        self.services = {k: v["base_url"].rstrip("/") for k, v in state["services"].items()}
        for base in self.services.values():
            parsed = urllib.parse.urlsplit(base)
            check(parsed.scheme == "http" and parsed.hostname == "127.0.0.1" and parsed.port,
                  "Only manifest loopback services are permitted")
        self.stream, self.calls, self.case = stream, 0, "preflight"

    def receipt(self, value):
        with self.stream.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(redact(value), ensure_ascii=False) + "\n")

    def call(self, method, path, token=None, body=None, query=None, status=200, code="OK",
             platform=False, binary=False, upload=None, fast=False):
        if platform:
            check(path in ("/api/v1/platform/auth/login", "/api/v1/admin/tenants",
                           "/api/v1/admin/tenants/suspend", "/api/v1/admin/tenants/detail",
                           "/api/v1/admin/system-settings", "/api/v1/admin/system-settings/history",
                           "/api/v1/admin/system-settings/context-policy"), "Platform route outside allowlist")
            check(not path.startswith("/api/v1/admin/system-settings") or method == "GET",
                  "Global runtime settings are read-only")
            host = "platform-service"
        else:
            check(path.startswith((F, J, C, A, O)) or path in ("/api/v1/auth/login",
                  "/api/v1/settings/available-models", "/api/v1/settings/model-selections"),
                  "Institution route outside free Foundation allowlist")
            check(not (method != "GET" and path.startswith(C) and path not in
                       (C + "/dictionaries", C + "/dictionary-values", C + "/branding", C + "/preferences")),
                  "Shared plan/features/quota writes are prohibited")
            check(not (method != "GET" and path.startswith(O) and path != O + "/audit/export"),
                  "This suite does not mutate retention policies or shared facts")
            host = "institution-service"
        self.calls += 1
        target = self.services[host] + path
        if query:
            target += "?" + urllib.parse.urlencode(query)
        headers = {"Accept": "application/json", "Content-Type": "application/json"}
        if token:
            headers["X-Platform-Token" if platform else "Authorization"] = "Bearer " + token
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        summary = body
        if upload is not None:
            name, content, claimed = upload
            boundary = "sf-" + secrets.token_hex(12)
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
            data = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                    f'Content-Type: {claimed}\r\n\r\n').encode("utf-8") + content + f"\r\n--{boundary}--\r\n".encode()
            summary = {"filename": name, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest(),
                       "untrusted_claimed_mime": claimed}
        self.receipt({"event": "intent", "call": self.calls, "at": now(), "case": self.case,
                      "host": host, "method": method, "path": path, "query": query, "body": summary})
        if not fast:
            time.sleep(.56)
        try:
            response = urllib.request.build_opener(NoRedirect()).open(urllib.request.Request(target, data=data, headers=headers, method=method), timeout=25)
        except urllib.error.HTTPError as failure:
            response = failure
        except (OSError, TimeoutError) as failure:
            self.receipt({"event": "completion", "call": self.calls, "at": now(), "effects": "unknown",
                          "transport_error": type(failure).__name__})
            raise AssertionError("Transport unknown; writes are never replayed") from None
        raw = response.read()
        try:
            parsed = json.loads(raw)
        except (ValueError, UnicodeError):
            parsed = None
        public_headers = {k.lower(): v for k, v in response.headers.items() if k.lower() in
                          ("content-type", "content-length", "content-disposition", "x-content-type-options",
                           "content-security-policy", "cache-control", "x-export-rows")}
        self.receipt({"event": "completion", "call": self.calls, "at": now(), "status": response.status,
                      "code": parsed.get("code") if isinstance(parsed, dict) else "BINARY",
                      "headers": public_headers, "response_sha256": hashlib.sha256(raw).hexdigest(),
                      "response": parsed if isinstance(parsed, dict) else {"byte_count": len(raw)}})
        check(response.status == status, f"{method} {path}: expected HTTP {status}, got {response.status}")
        if binary and status == 200:
            return raw, public_headers
        check(isinstance(parsed, dict) and parsed.get("code") == code,
              f"{method} {path}: expected code {code}, got {parsed.get('code') if isinstance(parsed, dict) else 'BINARY'}")
        return parsed.get("data")


class Round:
    def __init__(self, api, root, prefix, env, model):
        self.api, self.root, self.prefix, self.env, self.model = api, root, prefix, env, model
        self.cases, self.ready, self.owned, self.cleanup_rows = [], set(), [], []
        self.t, self.snapshots, self.files, self.jobs = {}, {}, [], []
        self.login_counts = {"main": 0, "foreign": 0}
        self.platform_token = None

    def own(self, kind, value):
        self.owned.append({"kind": kind, "entity": value})
        save(self.root / "owned-entities.json", redact(self.owned))
        return value

    def case(self, name, action, needs=("fixture",), ready=None):
        self.api.case = name
        start = self.api.calls
        row = {"id": name, "category": "fixture" if name == "fixture.new_owned_tenants" else "business",
               "started_at": now(), "dependencies": list(needs)}
        missing = [x for x in needs if x not in self.ready]
        if missing:
            row.update(status="BLOCKED", reason="Missing prerequisites: " + ", ".join(missing))
        else:
            try:
                action()
                row["status"] = "PASS"
                if ready:
                    self.ready.add(ready)
            except Exception as failure:
                row.update(status="FAIL", reason=type(failure).__name__ + ": " + str(failure)[:800])
        row.update(ended_at=now(), http_calls=self.api.calls-start, first_call=start+1 if self.api.calls > start else None,
                   last_call=self.api.calls)
        self.cases.append(row)
        save(self.root / "cases.json", self.cases)
        print(self.prefix, name, row["status"], row.get("reason", ""), flush=True)

    def call(self, method, path, body=None, query=None, actor="admin", tenant="main", **kwargs):
        token = self.t[tenant][actor + "_token"]
        return self.api.call(method, path, token, body, query, **kwargs)

    def login(self, tenant="main", actor="admin"):
        item = self.t[tenant]
        data = self.api.call("POST", "/api/v1/auth/login", body={"tenant_slug": item["slug"],
            "username": item[actor + "_username"], "password": PASSWORD})
        item[actor + "_token"] = data["access_token"]
        self.login_counts[tenant] += 1
        return data

    def fixture(self):
        auth = self.api.call("POST", "/api/v1/platform/auth/login", platform=True,
            body={"username": self.env["SF_PLATFORM_USER"], "password": self.env["SF_PLATFORM_PASSWORD"]})
        self.platform_token = auth["access_token"]
        for label in ("main", "foreign"):
            slug = self.prefix + "-" + label
            tenant = self.api.call("POST", "/api/v1/admin/tenants", self.platform_token, platform=True, status=201,
                body={"tenant_name": "Foundation 独立验收 " + slug, "tenant_slug": slug, "plan_code": "free",
                      "admin_username": self.prefix + "_admin", "admin_password": PASSWORD,
                      "admin_email": self.prefix + "-" + label + "@example.test"})
            self.own("tenant", tenant)
            self.t[label] = {"id": str(tenant["tenant"]["id"] if "tenant" in tenant else tenant["id"]),
                             "slug": slug, "admin_username": self.prefix + "_admin"}
            self.login(label)
            entitlements = self.call("GET", C + "/tenant-entitlements", tenant=label)
            check(all(x in entitlements["features"] for x in ("members", "foundation.files", "foundation.jobs")),
                  "Required free features must actually be enabled")
            save(self.root / (label + "-entitlements.json"), entitlements)
        self.role = self.call("POST", A + "/role", {"code": self.prefix + "_reader", "name": "Foundation scoped reader",
                         "data_scope": "self", "permissions": READ, "department_ids": []})
        self.own("role", self.role)
        member = self.call("POST", A + "/member", {"username": self.prefix + "_viewer",
            "display_name": "独立资料消费者", "email": None, "password": PASSWORD, "department_id": None,
            "position_id": None, "role_ids": [self.role["id"]]})
        self.own("member", member)
        self.t["main"]["viewer_username"] = member["username"]
        self.viewer = member
        self.login(actor="viewer")

    def upload(self, name, content, mime="application/x-untrusted"):
        item = self.call("POST", F + "/upload", upload=(name, content, mime))
        self.files.append(item)
        self.own("file", item)
        return item

    def download(self, item):
        raw, headers = self.call("GET", F + "/download", query={"file_id": item["id"]}, binary=True)
        check(hashlib.sha256(raw).hexdigest() == item["sha256"] and len(raw) == item["size_bytes"], "Actual bytes match independent hash and size")
        check(headers["x-content-type-options"] == "nosniff" and headers["content-security-policy"] == "sandbox; default-src 'none'"
              and headers["cache-control"] == "private, no-store" and headers["content-disposition"].startswith("attachment;"),
              "Every download has exact controlled security headers")
        check(headers["content-type"].split(";")[0] == item["mime_type"], "Download MIME equals server-detected type")
        (self.root / ("download-" + item["id"] + ".bin")).write_bytes(raw)
        return raw

    def files_formats(self):
        formats = [("说明,中文.txt", '中文\r\nline, "quote"\tEND'.encode(), "text/plain"),
                   ("values.csv", 'key,label\r\na,"中文,逗号"\r\n'.encode(), "text/csv"),
                   ("settings.json", '{"中文":[true,null,3],"x":"\\n"}'.encode(), "application/json"),
                   ("sample.pdf", b"%PDF-1.7\nFoundation signature sample\n%%EOF", "application/pdf"),
                   ("sample.png", b"\x89PNG\r\n\x1a\nFoundation signature sample", "image/png"),
                   ("sample.jpeg", b"\xff\xd8\xffFoundation signature sample", "image/jpeg"),
                   ("sample.webp", b"RIFF\x18\0\0\0WEBPFoundation signature sample", "image/webp")]
        for name, raw, expected in formats:
            item = self.upload(self.prefix + "-" + name, raw)
            check(item["name"] == self.prefix + "-" + name and item["mime_type"] == expected and
                  item["size_bytes"] == len(raw) and item["sha256"] == hashlib.sha256(raw).hexdigest()
                  and item["reference_count"] == 0, "Exact filename/MIME/bytes/hash/reference projection")
            check(self.download(item) == raw, "Downloaded raw content equals generated fixture")
        first = self.call("GET", F, query={"page": 1, "page_size": 3})
        pages = [first] + [self.call("GET", F, query={"page": p, "page_size": 3}) for p in (2, 3)]
        actual = [x["id"] for page in pages for x in page["items"]]
        check(first["total"] == 7 and len(actual) == 7 and set(actual) == {x["id"] for x in self.files}, "Independent exact paginated file set")

    def files_invalid(self):
        for name, raw in [("empty.txt", b""), ("wrong.png", b"plain text"), ("bad.txt", b"\xff\xfe"),
                          ("active.txt", b"<html>run</html>"), ("active.json", b"<svg>run</svg>"),
                          ("null.txt", b"a\0b"), ("../escape.txt", b"text")]:
            self.call("POST", F + "/upload", upload=(name, raw, "image/png"), status=400, code="file_type_invalid")
        self.call("GET", F, query={"page": 0}, status=400, code="VALIDATION_ERROR")
        self.call("GET", F + "/download", query={"file_id": "../outside"}, status=400, code="VALIDATION_ERROR")
        check(self.call("GET", F)["total"] == 7, "Rejected uploads leave no public rows")

    def files_foreign_delete(self):
        item = self.files[0]
        self.call("GET", F + "/download", query={"file_id": item["id"]}, tenant="foreign", status=404, code="NOT_FOUND")
        self.call("POST", F + "/delete", {"file_id": item["id"]}, tenant="foreign", status=404, code="NOT_FOUND")
        check(self.call("GET", F, tenant="foreign")["total"] == 0, "Foreign file list is empty")
        self.call("POST", F + "/delete", {"file_id": item["id"]})
        self.call("GET", F + "/download", query={"file_id": item["id"]}, status=404, code="NOT_FOUND")
        check(self.call("GET", F)["total"] == 6, "Owned zero-reference deletion removes current list row")

    def files_permission(self):
        self.call("POST", F + "/upload", upload=("forbidden.txt", b"text", "text/plain"), actor="viewer",
                  status=403, code="foundation.permission.denied")
        self.call("POST", F + "/delete", {"file_id": self.files[1]["id"]}, actor="viewer", status=403, code="foundation.permission.denied")
        self.call("GET", F + "/download", query={"file_id": self.files[1]["id"]}, actor="viewer", binary=True)

    def submit(self, handler, operation, key, file=None, options=None, fast=False):
        command = {"handler": handler, "operation": operation, "file_id": None if file is None else file["id"],
                   "options": options or {}, "idempotency_key": self.prefix + ":" + key}
        result = self.call("POST", J + "/submit", command, fast=fast)
        self.jobs.append(result)
        self.own("job", {"view": result, "command": command})
        return result, command

    def terminal(self, job):
        seen = []
        for _ in range(65):
            item = self.call("GET", J + "/detail", query={"job_id": job["id"]})
            seen.append(item["state"])
            if item["state"] in ("succeeded", "failed", "cancelled", "expired"):
                job.update(item)
                save(self.root / ("job-" + job["id"] + "-states.json"), seen)
                return item
            time.sleep(.4)
        raise AssertionError("Accepted job never reached public terminal state")

    def job_registry(self):
        handlers = self.call("GET", J + "/handlers")
        by = {x["code"]: x for x in handlers}
        check(set(by) == {"dictionary_values", "member_profiles", "security_audit"}, "Actual built-in handler set")
        check(by["dictionary_values"]["columns"] == ["code", "label", "sort_order", "enabled"] and
              by["security_audit"]["can_export"] and not by["security_audit"]["can_import"], "Exact declared operation and CSV contract")
        view = self.call("GET", J + "/handlers", actor="viewer")
        check([x["code"] for x in view] == ["dictionary_values"] and view[0]["can_export"] and not view[0]["can_import"],
              "Handler consumer projects only currently permitted domain operations")
        self.dictionary = self.call("PUT", C + "/dictionaries", {"code": self.prefix + "_csv", "name": "复杂 CSV " + self.prefix,
                                      "enabled": True, "expected_revision": 0})
        self.own("dictionary", self.dictionary)
        self.rows = [["alpha", '中文,逗号\n与"双引号"', "20", "true"], ["formula", "=SUM(1,2)", "0", "true"],
                     ["disabled", "停用值 中文", "10", "false"]]
        buffer = io.StringIO(newline="")
        writer = csv.writer(buffer, lineterminator="\r\n")
        writer.writerow(["code", "label", "sort_order", "enabled"])
        writer.writerows(self.rows)
        self.input_file = self.upload(self.prefix + "-import.csv", ("\ufeff" + buffer.getvalue()).encode())
        self.options = {"dictionary_code": self.dictionary["code"]}

    def job_preflight(self):
        view = self.call("POST", J + "/preflight", {"handler": "dictionary_values", "file_id": self.input_file["id"], "options": self.options})
        check(view == {"row_count": 3, "valid": True, "errors": []}, "All complex valid rows preflight")
        check(self.call("GET", C + "/dictionary-values", query=self.options) == [], "Preflight has zero domain writes")
        raw = b"code,label,sort_order,enabled\r\na,A,-1,true\r\nb,B,0,TRUE\r\n"
        bad = self.upload(self.prefix + "-invalid.csv", raw)
        self.invalid_file = bad
        result = self.call("POST", J + "/preflight", {"handler": "dictionary_values", "file_id": bad["id"], "options": self.options})
        check(not result["valid"] and result["row_count"] == 2 and {x["row_number"] for x in result["errors"]} == {2, 3},
              "Every rejected row has exact CSV data-row number")
        self.call("POST", J + "/submit", {"handler": "dictionary_values", "operation": "import", "file_id": bad["id"],
            "options": self.options, "idempotency_key": self.prefix + ":bad"}, status=400, code="import_preflight_failed")
        check(self.call("GET", C + "/dictionary-values", query=self.options) == [] and self.call("GET", J)["total"] == 0,
              "Rejected admission has neither domain write nor accepted job")

    def job_import(self):
        self.import_job, self.import_command = self.submit("dictionary_values", "import", "import", self.input_file, self.options)
        result = self.terminal(self.import_job)
        check(result["state"] == "succeeded" and result["total_rows"] == 3 and result["completed_rows"] == 3
              and result["error_rows"] == 0 and result["result_file_id"] is None, "Import public terminal receipt is complete")
        values = self.call("GET", C + "/dictionary-values", query=self.options)
        expected = [{"code": x[0], "label": x[1], "sort_order": int(x[2]), "enabled": x[3] == "true"} for x in self.rows]
        check([{k: x[k] for k in expected[0]} for x in values] == sorted(expected, key=lambda x: (x["sort_order"], x["code"])),
              "Independent expected values, ordering, multiline Chinese, quotes and disabled flag")
        self.call("POST", F + "/delete", {"file_id": self.input_file["id"]}, status=409, code="file_referenced")

    def job_export(self):
        self.export_job, self.export_command = self.submit("dictionary_values", "export", "export", options=self.options)
        result = self.terminal(self.export_job)
        check(result["state"] == "succeeded" and result["total_rows"] == 3 and result["result_file_id"], "Export reaches succeeded with actual file")
        expected = sorted(self.rows, key=lambda x: (int(x[2]), x[0]))
        expected = [[x[0], "'" + x[1] if x[1].lstrip().startswith(("=", "+", "-", "@")) else x[1], x[2], x[3]] for x in expected]
        expected_rows = [["code", "label", "sort_order", "enabled"]] + expected
        buffer = io.StringIO(newline="")
        csv.writer(buffer, lineterminator="\r\n", quoting=csv.QUOTE_ALL).writerows(expected_rows)
        expected_raw = ("\ufeff" + buffer.getvalue()).encode("utf-8")
        self.output_file = {"id": result["result_file_id"], "mime_type": "text/csv", "size_bytes": len(expected_raw),
                            "sha256": hashlib.sha256(expected_raw).hexdigest(), "generated": True,
                            "metadata_basis": "independently encoded expected CSV; not a public FileView projection"}
        self.files.append(self.output_file)
        self.own("generated_file_expectation", self.output_file)
        listed = self.call("GET", F)["items"]
        check(not any(x["id"] == result["result_file_id"] for x in listed), "Ordinary upload list deliberately excludes domain-owned job output")
        downloaded = self.download(self.output_file)
        parsed = list(csv.reader(io.StringIO(downloaded.decode("utf-8-sig"), newline="")))
        check(downloaded == expected_raw and parsed == expected_rows, "Actual CSV has independent exact bytes, contents and formula escaping")
        self.call("POST", F + "/delete", {"file_id": self.output_file["id"]}, status=409, code="file_referenced")

    def job_replay(self):
        replay = self.call("POST", J + "/submit", self.export_command)
        check(replay["id"] == self.export_job["id"] and replay["result_file_id"] == self.output_file["id"], "Same request replays actual existing terminal result")
        conflict = dict(self.export_command, handler="member_profiles", options={})
        self.call("POST", J + "/submit", conflict, status=409, code="job_idempotency_conflict")
        check(self.call("GET", J)["total"] == 2, "Idempotency consumes no extra accepted jobs")

    def job_cancel(self):
        job, _ = self.submit("dictionary_values", "export", "cancel", options=self.options, fast=True)
        cancelled = self.call("POST", J + "/cancel", {"job_id": job["id"]}, fast=True)
        check(cancelled["state"] in ("cancelled", "cancel_requested"), "Public cancellation was accepted before terminal success")
        final = self.terminal(job)
        check(final["state"] == "cancelled", "Actual cancellation terminal receipt, not just admission")
        repeated = self.call("POST", J + "/cancel", {"job_id": job["id"]})
        check(repeated["state"] == "cancelled", "Cancellation of the same terminal task is idempotent")
        check(self.call("GET", J + "/errors", query={"job_id": job["id"], "page": 1, "page_size": 2}) == [], "Cancelled export has no fabricated row errors")

    def job_foreign_invalid(self):
        for suffix in ("/detail", "/errors"):
            self.call("GET", J + suffix, query={"job_id": self.export_job["id"]}, tenant="foreign", status=404, code="NOT_FOUND")
        self.call("POST", J + "/cancel", {"job_id": self.export_job["id"]}, tenant="foreign", status=404, code="NOT_FOUND")
        self.call("GET", F + "/download", query={"file_id": self.output_file["id"]}, tenant="foreign", status=404, code="NOT_FOUND")
        check(self.call("GET", J, tenant="foreign")["total"] == 0, "Foreign job queue projection is empty")
        self.call("GET", J + "/errors", query={"job_id": self.export_job["id"], "page_size": 201}, status=400, code="VALIDATION_ERROR")
        self.call("POST", J + "/submit", dict(self.export_command, handler="unregistered", idempotency_key=self.prefix + ":unknown"),
                  status=400, code="job_input_invalid")
        self.call("POST", J + "/submit", dict(self.export_command, options={"tenant_id": self.t["foreign"]["id"]}),
                  status=400, code="catalog_invalid")

    def role_update(self, permissions):
        command = {k: self.role[k] for k in ("id", "name", "data_scope", "permissions", "department_ids", "enabled", "lock_version")}
        command["permissions"] = permissions
        self.role.update(self.call("PUT", A + "/role", command))

    def job_permission_live(self):
        self.call("GET", F + "/download", query={"file_id": self.output_file["id"]}, actor="viewer", binary=True)
        self.call("POST", J + "/submit", self.export_command, actor="viewer", status=403, code="foundation.permission.denied")
        self.role_update([x for x in READ if x != "foundation.dictionary.read"])
        try:
            self.call("GET", F + "/download", query={"file_id": self.output_file["id"]}, actor="viewer", status=401, code="AUTH_SESSION_INVALID")
            self.login(actor="viewer")
            self.call("GET", F + "/download", query={"file_id": self.output_file["id"]}, actor="viewer", status=403, code="foundation.permission.denied")
            check(self.call("GET", J + "/detail", query={"job_id": self.export_job["id"]}, actor="viewer")["id"] == self.export_job["id"],
                  "jobs.read alone reads job projection but cannot download domain-owned artifact")
        finally:
            self.role_update(READ)
            self.login(actor="viewer")
        self.call("GET", F + "/download", query={"file_id": self.output_file["id"]}, actor="viewer", binary=True)

    def branding_save(self):
        self.snapshots["branding"] = self.call("GET", C + "/branding")
        self.snapshots["foreign_branding"] = self.call("GET", C + "/branding", tenant="foreign")
        save(self.root / "original-values.json", self.snapshots)
        command = {"display_name": "  灵知 中文 品牌「" + self.prefix + "」  ", "logo_url": "https://example.test/logo.png?q=%E4%B8%AD%E6%96%87",
                   "primary_color": "#A1B2C3", "contact_email": "support+" + self.prefix + "@example.test",
                   "support_url": "https://example.test/help?lang=zh-CN#section", "expected_revision": self.snapshots["branding"]["revision"]}
        self.brand = self.call("PUT", C + "/branding", command)
        expected = {k: v for k, v in command.items() if k != "expected_revision"}
        expected.update(display_name=command["display_name"].strip(), primary_color="#a1b2c3", revision=command["expected_revision"] + 1)
        check(self.brand == expected and self.call("GET", C + "/branding") == expected, "Full normalized complex branding is current consumer value")

    def branding_cas_validation(self):
        command = {k: v for k, v in self.brand.items() if k != "revision"}
        self.call("PUT", C + "/branding", dict(command, expected_revision=self.brand["revision"] - 1), status=409, code="catalog_revision_conflict")
        for updates in ({"primary_color": "#12"}, {"logo_url": "http://example.test/a"},
                        {"support_url": "https://user:pass@example.test/a"}, {"contact_email": "bad-email"}):
            self.call("PUT", C + "/branding", dict(command, **updates, expected_revision=self.brand["revision"]), status=400, code="catalog_invalid")
        check(self.call("GET", C + "/branding") == self.brand, "Invalid/stale writes preserve exact prior state")
        self.call("PUT", C + "/branding", dict(command, expected_revision=self.brand["revision"]), actor="viewer", status=403, code="foundation.permission.denied")
        check(self.call("GET", C + "/branding", tenant="foreign") == self.snapshots["foreign_branding"], "Other tenant branding is exact original")
        self.brand = self.call("PUT", C + "/branding", dict(command, logo_url=None, support_url=None, contact_email=None,
                                                               expected_revision=self.brand["revision"]))
        check(all(self.brand[x] is None for x in ("logo_url", "support_url", "contact_email")), "Explicit null clears all optional display values")

    def preferences_save(self):
        self.snapshots["preferences"] = self.call("GET", C + "/preferences")
        self.snapshots["viewer_preferences"] = self.call("GET", C + "/preferences", actor="viewer")
        save(self.root / "original-values.json", self.snapshots)
        self.pref = self.call("PUT", C + "/preferences", {"theme": "dark", "locale": "zh-CN", "page_size": 100,
                                      "compact_navigation": True, "expected_revision": self.snapshots["preferences"]["revision"]})
        check(self.pref == {"theme": "dark", "locale": "zh-CN", "page_size": 100, "compact_navigation": True, "revision": 1}, "Complete personal preference projection")
        check(self.call("GET", C + "/preferences", actor="viewer") == self.snapshots["viewer_preferences"], "Personal settings remain isolated by member")

    def preferences_validation(self):
        command = {k: v for k, v in self.pref.items() if k != "revision"}
        self.call("PUT", C + "/preferences", dict(command, expected_revision=0), status=409, code="catalog_revision_conflict")
        for update in ({"theme": "sepia"}, {"locale": "en-US"}, {"page_size": 25}):
            self.call("PUT", C + "/preferences", dict(command, **update, expected_revision=1), status=400, code="catalog_invalid")
        check(self.call("GET", C + "/preferences") == self.pref, "Stale/invalid preferences preserve current complete value")
        for theme, size, compact in (("light", 10, False), ("system", 50, True), ("dark", 20, False)):
            self.pref = self.call("PUT", C + "/preferences", {"theme": theme, "locale": "zh-CN", "page_size": size,
                              "compact_navigation": compact, "expected_revision": self.pref["revision"]})
            check(self.call("GET", C + "/preferences") == self.pref, "Every allowed value combination is a current consumer fact")

    def models_save_restore_scope(self):
        self.snapshots["model_selections"] = self.call("GET", "/api/v1/settings/model-selections")
        self.snapshots["foreign_models"] = self.call("GET", "/api/v1/settings/model-selections", tenant="foreign")
        save(self.root / "original-values.json", self.snapshots)
        available = self.call("GET", "/api/v1/settings/available-models")
        check(any(str(x["id"]) == str(self.model) for x in available), "Existing published test model is actually selectable")
        command = dict(self.snapshots["model_selections"], llm_model_config_id=self.model)
        result = self.call("PUT", "/api/v1/settings/model-selections", command)
        check(result == command and self.call("GET", "/api/v1/settings/model-selections") == command, "Metadata-only model selection consumer persists exact owned-tenant references")
        self.call("PUT", "/api/v1/settings/model-selections", command, actor="viewer", status=403, code="foundation.permission.denied")
        check(self.call("GET", "/api/v1/settings/model-selections", tenant="foreign") == self.snapshots["foreign_models"], "Foreign model selections remain exact prior values")

    def ops_status(self):
        one = self.call("GET", O + "/status")
        two = self.call("GET", O + "/status")
        check(one["observed_at"] < two["observed_at"] and two["uptime_millis"] > one["uptime_millis"] and
              0 < two["heap_used_bytes"] <= two["heap_max_bytes"] and two["threads"] > 0, "Actual current JVM consumer values progress")
        probes = {x["name"]: x for x in two["probes"]}
        check(set(probes) == {"postgresql", "redis", "metrics"} and probes["postgresql"]["status"] == "up" and probes["redis"]["status"] == "up"
              and probes["metrics"]["status"] in ("up", "unavailable") and all(x["duration_millis"] >= 0 and x["observed_at"] for x in probes.values()),
              "Actual dependency probes preserve up/down/unavailable contract")
        check(all(x not in json.dumps(two).lower() for x in ("jdbc:", "redis://", "password", "bearer ")), "Status public diagnostics omit connection/secret material")
        save(self.root / "runtime-status.json", two)
        self.call("GET", O + "/status", actor="viewer", status=403, code="foundation.permission.denied")

    def ops_audit(self):
        query = {"action": "auth.login", "outcome": "success", "page": 1, "page_size": 100}
        rows = self.call("GET", O + "/audit", query=query)
        check(rows["total"] == self.login_counts["main"] and rows["items"] and all(x["tenant_id"] == self.t["main"]["id"] and x["action"] == "auth.login"
                 and x["outcome"] == "success" for x in rows["items"]), "Owned tenant exact authentication facts obey filters")
        check(not any(x in json.dumps(rows).lower() for x in (PASSWORD.lower(), self.env["SF_PLATFORM_PASSWORD"].lower(), "access_token", "bearer ")),
              "Audit consumer redacts secrets")
        foreign = self.call("GET", O + "/audit", query=query, tenant="foreign")
        check(all(x["tenant_id"] == self.t["foreign"]["id"] for x in foreign["items"]) and
              not ({x["id"] for x in foreign["items"]} & {x["id"] for x in rows["items"]}), "Independent tenant audit identities never overlap")
        raw, headers = self.call("POST", O + "/audit/export", query, binary=True)
        csv_rows = list(csv.reader(io.StringIO(raw.decode("utf-8-sig"), newline="")))
        check(csv_rows[0] == ["id", "tenant_id", "actor_id", "action", "category", "outcome", "resource_type", "resource_id", "message", "trace_id", "created_at"]
              and int(headers["x-export-rows"]) == len(csv_rows)-1 == rows["total"] and
              {x[0] for x in csv_rows[1:]} == {x["id"] for x in rows["items"]}, "Actual synchronous CSV is exact filtered event set and declared row count")
        (self.root / "security-audit.csv").write_bytes(raw)
        self.call("POST", O + "/audit/export", query, actor="viewer", status=403, code="foundation.permission.denied")
        for invalid, expected in [({"page_size": 101}, "operations.audit.pagination_invalid"), ({"category": "invalid"}, "operations.audit.category_invalid"),
                                  ({"outcome": "unknown"}, "operations.audit.outcome_invalid"), ({"action": " "}, "operations.audit.filter_invalid"),
                                  ({"from": "2026-10-13T00:00:00Z", "to": "2026-10-12T00:00:00Z"}, "operations.audit.time_invalid")]:
            self.call("GET", O + "/audit", query=invalid, status=400, code=expected)

    def ops_retention_lists(self):
        policy = self.call("GET", O + "/retention")
        check(policy == {"resource": "audit", "retention_days": 180, "enabled": False, "version": 0}, "Fresh isolated tenant has exact default disabled retention policy at revision zero")
        check(self.call("GET", O + "/retention/runs") == [] and self.call("GET", O + "/retention/holds") == [], "Fresh isolated tenant has exact empty cleanup/hold consumer lists")
        self.call("GET", O + "/retention", actor="viewer", status=403, code="foundation.permission.denied")

    def global_settings_read(self):
        groups = self.api.call("GET", "/api/v1/admin/system-settings", self.platform_token, platform=True)
        check(isinstance(groups, list) and groups and all(x["revision"] >= 1 and isinstance(x["values"], dict) for x in groups), "Live operational settings are versioned typed groups")
        group = groups[0]["key"]
        history = self.api.call("GET", "/api/v1/admin/system-settings/history", self.platform_token, query={"group": group}, platform=True)
        check(isinstance(history, list) and history, "Current global group has real immutable history consumer")
        policy = self.api.call("GET", "/api/v1/admin/system-settings/context-policy", self.platform_token, platform=True)
        check(isinstance(policy["version"], str) and policy["version"], "Code context policy identity is visible")
        save(self.root / "global-settings-read-only.json", redact({"groups": groups, "history": history, "context_policy": policy}))

    def cleanup_one(self, name, action):
        self.api.case = "owned_cleanup." + name
        row = {"id": name, "started_at": now()}
        try:
            result = action()
            row.update(status="PASS", result=result)
        except Exception as failure:
            row.update(status="FAIL", reason=type(failure).__name__ + ": " + str(failure)[:800])
        row["ended_at"] = now()
        self.cleanup_rows.append(row)
        save(self.root / "cleanup.json", self.cleanup_rows)

    def cleanup(self):
        if "main" in self.t:
            for endpoint, key in ((C + "/branding", "branding"), (C + "/preferences", "preferences"),
                                  ("/api/v1/settings/model-selections", "model_selections")):
                if key in self.snapshots:
                    def restore(endpoint=endpoint, key=key):
                        current = self.call("GET", endpoint)
                        command = dict(self.snapshots[key])
                        if "revision" in command:
                            command.pop("revision")
                            command["expected_revision"] = current["revision"]
                        result = self.call("PUT", endpoint, command)
                        expected = {k: v for k, v in self.snapshots[key].items() if k != "revision"}
                        check({k: v for k, v in result.items() if k != "revision"} == expected and
                              {k: v for k, v in self.call("GET", endpoint).items() if k != "revision"} == expected, "Exact original display/model values restored")
                        return {"original": self.snapshots[key], "restored": result,
                                "revision_row_retained": "revision" in result}
                    self.cleanup_one("restore." + key, restore)
            if hasattr(self, "role"):
                self.cleanup_one("restore.reader_permissions", lambda: self.role_update(READ))
            for job in self.jobs:
                def settle(job=job):
                    current = self.call("GET", J + "/detail", query={"job_id": job["id"]})
                    if current["state"] not in ("succeeded", "failed", "cancelled", "expired"):
                        self.call("POST", J + "/cancel", {"job_id": job["id"]})
                        current = self.terminal(job)
                    check(current["state"] in ("succeeded", "failed", "cancelled", "expired"), "Owned queue settled before tenant suspension")
                    return {"id": job["id"], "terminal_state": current["state"], "retention": "public job history/reference retained"}
                self.cleanup_one("settle.job." + job["id"], settle)
            for file in self.files:
                def remove(file=file):
                    page = self.call("GET", F, query={"page_size": 200})
                    current = next((x for x in page["items"] if x["id"] == file["id"]), None)
                    if file.get("generated"):
                        check(current is None, "Job output remains excluded from ordinary upload list")
                        self.call("POST", F + "/delete", {"file_id": file["id"]}, status=409, code="file_referenced")
                        return {"id": file["id"], "retained_for_job": True,
                                "public_delete_receipt": "409/file_referenced", "reference_count": "not publicly projected"}
                    if current is None:
                        return {"id": file["id"], "absent": True}
                    if current["reference_count"]:
                        self.call("POST", F + "/delete", {"file_id": file["id"]}, status=409, code="file_referenced")
                        return {"id": file["id"], "retained_for_job": True, "references": current["reference_count"]}
                    self.call("POST", F + "/delete", {"file_id": file["id"]})
                    self.call("GET", F + "/download", query={"file_id": file["id"]}, status=404, code="NOT_FOUND")
                    return {"id": file["id"], "deleted_and_absence_verified": True}
                self.cleanup_one("file." + file["id"], remove)
            if hasattr(self, "dictionary"):
                def remove_dictionary():
                    values = self.call("GET", C + "/dictionary-values", query={"dictionary_code": self.dictionary["code"]})
                    for item in values:
                        self.call("DELETE", C + "/dictionaries", {"dictionary_code": self.dictionary["code"], "code": item["code"], "expected_revision": item["revision"]})
                    current = next(x for x in self.call("GET", C + "/dictionaries") if x["code"] == self.dictionary["code"])
                    self.call("DELETE", C + "/dictionaries", {"dictionary_code": current["code"], "code": None, "expected_revision": current["revision"]})
                    check(not any(x["code"] == self.dictionary["code"] for x in self.call("GET", C + "/dictionaries")), "Owned dictionary absent after exact latest revision cleanup")
                self.cleanup_one("dictionary", remove_dictionary)
        for label, item in self.t.items():
            def suspend(item=item):
                check(item["slug"].startswith(self.prefix + "-"), "Cleanup exact owned tenant slug")
                result = self.api.call("POST", "/api/v1/admin/tenants/suspend", self.platform_token,
                                  {"tenant_id": int(item["id"])}, platform=True)
                detail = self.api.call("GET", "/api/v1/admin/tenants/detail", self.platform_token,
                                      query={"tenantId": item["id"]}, platform=True)
                tenant = detail.get("tenant", detail)
                check(tenant["status"] == "suspended", "Owned tenant is suspended by real public account lifecycle")
                return {"tenant_id": item["id"], "slug": item["slug"], "status": tenant["status"],
                        "retention": "No public delete tenant/member/job history; owned tombstones remain"}
            self.cleanup_one("tenant." + label, suspend)

    def run(self):
        try:
            self.case("fixture.new_owned_tenants", self.fixture, needs=(), ready="fixture")
            self.case("files.seven_detected_formats_exact_bytes_headers_pagination", self.files_formats, ready="files")
            self.case("files.invalid_content_path_uuid_page_preserve", self.files_invalid, needs=("files",))
            self.case("files.foreign_refusal_zero_reference_delete", self.files_foreign_delete, needs=("files",))
            self.case("files.current_permission_consumer", self.files_permission, needs=("files",))
            self.case("jobs.registry_domain_permissions_complex_csv_setup", self.job_registry, ready="csv")
            self.case("jobs.preflight_all_errors_no_writes_rejected_admission", self.job_preflight, needs=("csv",))
            self.case("jobs.import_actual_terminal_domain_values_input_protection", self.job_import, needs=("csv",), ready="import")
            self.case("jobs.export_actual_terminal_hash_exact_csv_formula_protection", self.job_export, needs=("import",), ready="export")
            self.case("jobs.idempotent_replay_conflict_preserves_result", self.job_replay, needs=("export",))
            self.case("jobs.cancel_actual_terminal_idempotent_errors", self.job_cancel, needs=("export",))
            self.case("jobs.foreign_ids_unregistered_options_errors_page", self.job_foreign_invalid, needs=("export",))
            self.case("jobs.artifact_current_domain_permission_live_revoke", self.job_permission_live, needs=("export",))
            self.case("branding.complex_full_value_normalized_current_consumer", self.branding_save, ready="brand")
            self.case("branding.cas_validation_permission_null_clear_tenant_isolation", self.branding_cas_validation, needs=("brand",))
            self.case("preferences.complete_value_member_isolation", self.preferences_save, ready="pref")
            self.case("preferences.cas_invalid_values_all_allowed_combinations", self.preferences_validation, needs=("pref",))
            self.case("settings.owned_model_selection_metadata_permission_isolation", self.models_save_restore_scope)
            self.case("operations.actual_status_probes_jvm_permission_public_diagnostics", self.ops_status)
            self.case("operations.actual_audit_filter_isolation_export_csv_validation", self.ops_audit)
            self.case("operations.scoped_retention_policy_runs_holds_permissions", self.ops_retention_lists)
            self.case("settings.global_versioned_groups_history_context_read_only", self.global_settings_read)
        finally:
            self.cleanup()
        result = {"prefix": self.prefix, "counts": dict(Counter(x["status"] for x in self.cases)),
                  "business_counts": dict(Counter(x["status"] for x in self.cases if x["category"] == "business")),
                  "cleanup_counts": dict(Counter(x["status"] for x in self.cleanup_rows)), "http_calls": self.api.calls,
                  "owned_tenants": len(self.t), "provider_calls": 0, "smtp_calls": 0,
                  "retained_contract_limits": ["Files rename has no implemented public route", "No public unlink of trusted job file references",
                  "Terminal job file references retained until configured expiry", "New tenant/member tombstones retained after public suspension",
                  "Original display values restored with new CAS revisions; revision-0 rows cannot be physically removed by public API",
                  "Platform global runtime settings read-only; shared value mutation not claimed"]}
        save(self.root / "summary.json", result)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--rounds", type=int, default=3, choices=(1, 3))
    args = parser.parse_args()
    root, output = args.environment.resolve(), args.output.resolve()
    check(root.is_relative_to((REPO / ".runtime-logs/full-project-test-20261012").resolve()) and
          output.is_relative_to((REPO / ".runtime-logs/full-project-test-20261012").resolve()), "Only this acceptance batch private roots")
    check(not output.exists(), "Evidence output must be fresh; prior diagnostics cannot be overwritten")
    state = json.loads((root / "environment.json").read_text(encoding="utf-8"))
    check(state["batch"] == "sf-fulltest-349ef3c037" and all(x["status"] == "READY" for x in state["services"].values()), "Exact live isolated batch and service readiness")
    env = load_environment(root / "isolated.env")
    model = json.loads((root / "provision.json").read_text(encoding="utf-8"))["models"]["llm"]["id"]
    output.mkdir(parents=True)
    binding = {"runner": {"path": str(Path(__file__).resolve()), "sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()},
               "sources": [{"path": x, "sha256": hashlib.sha256((REPO / x).read_bytes()).hexdigest()} for x in SOURCES],
               "services": [{"service": k, "jar": v["jar"], "sha256": hashlib.sha256(Path(v["jar"]).read_bytes()).hexdigest()} for k, v in state["services"].items()]}
    save(output / "source-binding.json", binding)
    results = []
    common = "hf" + secrets.token_hex(4)
    for number in range(1, args.rounds + 1):
        round_root = output / ("round-" + str(number))
        round_root.mkdir()
        result = Round(Api(state, round_root / "operations.jsonl"), round_root, common + "r" + str(number), env, model).run()
        results.append(result)
        save(output / "summary.json", {"rounds": results, "provider_calls": 0, "smtp_calls": 0})
    return 1 if any(r["counts"].get("FAIL") or r["cleanup_counts"].get("FAIL") for r in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
