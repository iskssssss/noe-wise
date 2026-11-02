"""依据真实 OpenAPI 清单逐操作验证认证拒绝；仅证明认证边界，不宣称业务语义通过。"""
from __future__ import annotations
import argparse
import hashlib
import json
import re
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import unittest
from run_suite import save

# 明确的无 JWT 入口由 SaTokenConfiguration 声明；这些入口另有凭据和专门验收。
PUBLIC = {
    "institution": {"/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/refresh",
        "/api/v1/auth/recovery/request", "/api/v1/auth/recovery/complete", "/api/v1/auth/invite/activate",
        "/api/v1/agent/ws", "/api/v1/runtime/callbacks/deliveries",
        "/api/v1/chat/artifact-content/detail", "/api/v1/chat/deliverable-content/detail"},
    "platform": {"/api/v1/platform/auth/login", "/api/v1/platform/auth/refresh",
        "/api/v1/platform/auth/recovery/request", "/api/v1/platform/auth/recovery/complete",
        "/api/v1/platform/auth/invite/activate"},
}
VERBS = {"get", "post", "put", "patch", "delete"}


def inventory(environment: Path):
    state = json.loads((environment / "environment.json").read_text(encoding="utf-8"))
    rows, excluded, sources = [], [], []
    for service in ("institution", "platform"):
        path = environment / f"{service}-openapi.json"
        content = path.read_bytes()
        document = json.loads(content)
        sources.append({"path": str(path.resolve()), "sha256": hashlib.sha256(content).hexdigest()})
        base = state["services"][service + "-service"]["base_url"]
        for route, methods in sorted(document["paths"].items()):
            for verb, definition in sorted(methods.items()):
                if verb not in VERBS:
                    continue
                row = {"service": service, "method": verb.upper(), "route": route,
                       "summary": definition.get("summary"), "tags": definition.get("tags", []),
                       "operation_id": definition.get("operationId"), "base_url": base,
                       "request_media_types": sorted(definition.get("requestBody", {}).get("content", {})),
                       "response_media_types": sorted(definition.get("responses", {}).get("200", {}).get("content", {}))}
                if not route.startswith("/api/v1/") or route in PUBLIC[service] or route.startswith("/api/v1/callbacks/"):
                    excluded.append({**row, "reason": "public_or_separately_authenticated_entry"})
                else:
                    rows.append(row)
    return rows, excluded, sources


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--failed-results", type=Path, help="只补测原设施失配操作，原已通过证据保持有效")
    args = parser.parse_args()
    args.root.mkdir(parents=True, exist_ok=False)
    rows, excluded, sources = inventory(args.environment)
    if args.failed_results:
        previous = json.loads(args.failed_results.read_text(encoding="utf-8"))
        selected = {(row["service"], row["method"], row["route"]) for row in previous["operations"] if not row.get("passed")}
        rows = [row for row in rows if (row["service"], row["method"], row["route"]) in selected]
        if not rows:
            raise ValueError("no_failed_operations_to_reverify")
    save(args.root / "inventory.json", {"operations": rows, "excluded": excluded, "sources": sources,
         "scope": "All protected documented operations, absent and forged credentials; business semantics require separate evidence"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    all_results = []
    for number in (1, 2, 3):
        round_rows = []

        class AuthBoundary(unittest.TestCase):
            """真实隔离服务拒绝无凭据及伪造主体请求。"""

        for index, row in enumerate(rows):
            for mode in ("absent", "forged"):
                def verify(self, selected=row, selected_mode=mode):
                    route = re.sub(r"\{[^}]+\}", "00000000-0000-0000-0000-000000000000", selected["route"])
                    headers = {"Content-Type": "application/json", "X-Request-ID": f"auth-census-r{number}-{index}-{selected_mode}"}
                    if selected["response_media_types"]:
                        headers["Accept"] = ", ".join(selected["response_media_types"])
                    if selected_mode == "forged":
                        headers.update({"Authorization": "Bearer sf.invalid.signature", "X-Platform-Token": "sf.invalid.signature",
                            "X-Tenant-ID": "858530973937669", "X-User-ID": "1", "X-Role": "super_admin"})
                    body = None
                    if selected["method"] in {"POST", "PUT", "PATCH", "DELETE"}:
                        body = json.dumps({"tenant_id": "858530973937669", "user_id": "1", "role": "super_admin",
                            "expected_revision": 2147483647, "permissions": ["*"], "enabled": True,
                            "records": [{"index": n, "name": f"跨租户伪造记录{n}", "roles": ["platform_admin", "owner"],
                                         "metadata": {"null": None, "unicode": "引号\"与换行\n中文"}} for n in range(24)]}, ensure_ascii=False).encode()
                        if "multipart/form-data" in selected["request_media_types"]:
                            boundary = "sfFullTestAuthenticationBoundary"
                            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
                            csv = "index,department,amount\r\n" + "".join(f'{n},"复杂部门{n % 6}",{12550+n}\r\n' for n in range(24))
                            body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"auth-census.csv\"\r\n"
                                    "Content-Type: text/csv; charset=utf-8\r\n\r\n" + csv +
                                    f"--{boundary}\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n" + body.decode() +
                                    f"\r\n--{boundary}--\r\n").encode()
                    request = urllib.request.Request(selected["base_url"] + route, data=body, headers=headers, method=selected["method"])
                    response = None
                    result = {"service": selected["service"], "method": selected["method"], "route": selected["route"], "mode": selected_mode}
                    try:
                        try:
                            response = opener.open(request, timeout=20)
                        except urllib.error.HTTPError as failure:
                            response = failure
                        result["status"] = response.code
                        result["response_sha256"] = hashlib.sha256(response.read(65536)).hexdigest()
                        result["passed"] = response.code == 401
                        self.assertEqual(response.code, 401, f"{selected['service']} {selected['method']} {selected['route']} {selected_mode}")
                    except (OSError, ValueError) as error:
                        result.update(passed=False, transport_error=type(error).__name__)
                        raise
                    finally:
                        if response is not None:
                            response.close()
                        round_rows.append(result)
                setattr(AuthBoundary, f"test_{index:04d}_{mode}_{row['method']}", verify)
        outcome = unittest.TextTestRunner(verbosity=1).run(unittest.defaultTestLoader.loadTestsFromTestCase(AuthBoundary))
        result = {"round": number, "tests": outcome.testsRun, "failures": len(outcome.failures),
                  "errors": len(outcome.errors), "passed": outcome.wasSuccessful(), "operations": round_rows}
        save(args.root / f"round-{number}.json", result)
        all_results.append({key: value for key, value in result.items() if key != "operations"})
        save(args.root / "summary.json", all_results)
    return 0 if all(row["passed"] for row in all_results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
