"""固定官方 daemon 的双授权协议验收；仅使用独立 loopback 服务和空测试 owner。"""
import argparse
import concurrent.futures
import hashlib
import json
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """认证请求不跟随重定向，避免凭据外发和未知写入的自动重发。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service-config", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.service_config.read_text(encoding="utf-8-sig"))
    endpoint, token = config["endpoint"], config["token"]
    parsed = urllib.parse.urlsplit(endpoint)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1":
        raise ValueError("isolated_loopback_service_required")
    args.evidence.mkdir(parents=True, exist_ok=False)
    tenant, user = str(int(time.time() * 1000)), str(secrets.randbelow(10**12) + 1)
    owner_tag = hashlib.sha256(f"{tenant}:{user}".encode()).hexdigest()
    checks, controls, grants = [], [], []
    result = {"scope": "official_daemon_protocol_without_browser_ui", "owner_tag": owner_tag,
              "status": "started", "checks": checks, "controls": controls, "paid_model_calls": 0}
    receipt = args.evidence / "receipt.json"
    receipt.write_text(json.dumps(result, indent=2), encoding="utf-8")

    def http(path, body=None, credential=None):
        headers = {"Content-Type": "application/json"}
        if credential is None:
            headers["X-Browser-Service-Token"] = token
        else:
            headers["Authorization"] = "Bearer " + credential
        request = urllib.request.Request(endpoint + path, headers=headers,
                    data=None if body is None else json.dumps(body).encode())
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=75) as response:
            return json.load(response)

    def control(operation, device=None):
        request_id = str(uuid.uuid4())
        entry = {"operation": operation, "request_id": request_id, "status": "submitted"}
        controls.append(entry)
        try:
            response = http("/internal/browser/" + operation,
                            {"tenant": tenant, "user": user, "device": device, "request_id": request_id})
            entry["status"] = "succeeded"
            return response
        except Exception:
            entry["status"] = "unknown"
            raise

    def devices(owner_user=user):
        return http(f"/internal/browser/devices?tenant={tenant}&user={owner_user}")["items"]

    def pair(label):
        link = control("pair")["pairing_url"]
        url = urllib.parse.urlsplit(link)
        if url.hostname != parsed.hostname or url.port != parsed.port:
            raise ValueError("pair_endpoint_mismatch")
        credential = secrets.token_urlsafe(32)
        grant = http(url.path + "/authorize", {"action": "pair", "next_token": credential, "label": label}, url.fragment)
        return {"id": grant["device_id"], "path": url.path, "credential": credential}

    def renew(grant):
        next_token = secrets.token_urlsafe(32)
        response = http(grant["path"] + "/authorize", {"action": "renew", "next_token": next_token}, grant["credential"])
        assert response["device_id"] == grant["id"]
        grant["credential"] = next_token

    def check(name, condition):
        assert condition, name
        checks.append({"name": name, "passed": True})

    try:
        check("isolated_owner_initially_empty", devices() == [])
        # 两个并发配对与续期通过真实官方HTTP协议；不伪装成Chrome/Edge页面操作证据。
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
            futures = [workers.submit(pair, label) for label in ("protocol-A", "protocol-B")]
            for future in futures:
                grants.append(future.result())
            check("two_distinct_grants_retained", {d["device_id"] for d in devices()} == {g["id"] for g in grants})
            for future in [workers.submit(renew, grant) for grant in grants]:
                future.result()
        check("concurrent_renewals_retain_both_grants", {d["device_id"] for d in devices()} == {g["id"] for g in grants})
        check("other_owner_cannot_read_grants", devices(str(int(user) + 1)) == [])
        control("revoke", grants[0]["id"])
        grants[0]["revoked"] = True
        check("revoke_one_preserves_other", {d["device_id"] for d in devices()} == {grants[1]["id"]})
        try:
            renew(grants[0])
            raise AssertionError("revoked_renewal_was_accepted")
        except urllib.error.HTTPError as rejected:
            check("revoked_credential_rejected", rejected.code == 401)
        renew(grants[1])
        check("remaining_grant_can_renew", {d["device_id"] for d in devices()} == {grants[1]["id"]})
        result["status"] = "passed"
    except Exception as failure:
        result["status"] = "failed"
        result["failure_type"] = type(failure).__name__
        if isinstance(failure, urllib.error.HTTPError):
            result["http_status"] = failure.code
    finally:
        result["cleanup"] = []
        for grant in grants:
            if grant.get("revoked"):
                result["cleanup"].append("revoke_already_confirmed")
                continue
            try:
                control("revoke", grant["id"])
                result["cleanup"].append("revoke_confirmed")
            except Exception:
                result["cleanup"].append("unknown")
        try:
            result["owner_empty_after_cleanup"] = devices() == []
        except Exception:
            result["owner_empty_after_cleanup"] = False
        if not result["owner_empty_after_cleanup"]:
            result["status"] = "failed"
        receipt.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({"status": result["status"], "checks": len(checks), "receipt": str(receipt)}))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
