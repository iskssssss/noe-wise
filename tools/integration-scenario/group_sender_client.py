"""独立sender的本地鉴权客户端；只发一次原命令并保存安全验收证据。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, build_opener

from client import ApiFailure, Client, NoRedirect
from group_sender import identity
from group_sender_http import HTTP_TIMEOUT_SECONDS


class GroupSenderClient:
    def __init__(self, origin: str, token: str):
        self.origin, self._token = Client(origin).origin, identity(token, "local_token")
        self.opener = build_opener(NoRedirect())

    def request(self, method: str, path: str, body=None, key: str | None = None):
        parsed = urlparse(path)
        if parsed.scheme or parsed.netloc or parsed.fragment or not parsed.path.startswith("/api/v1/") or method not in {"GET", "POST"}:
            raise ValueError("EXPLICIT_LOCAL_SENDER_PATH_REQUIRED")
        headers = {"Authorization": "Bearer " + self._token, "Accept": "application/json"}
        raw = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        if raw is not None:
            headers["Content-Type"] = "application/json"
        if key is not None:
            headers["Idempotency-Key"] = identity(key, "idempotency_key")
        try:
            with self.opener.open(Request(self.origin + path, data=raw, method=method, headers=headers), timeout=HTTP_TIMEOUT_SECONDS) as response:
                envelope = json.load(response)
                if envelope.get("code") != "OK":
                    raise ApiFailure(response.status, str(envelope.get("code", "INVALID_ENVELOPE")))
                return envelope["data"]
        except HTTPError as failure:
            try:
                code = json.load(failure).get("code", "INVALID_ENVELOPE")
            except (ValueError, AttributeError):
                code = "INVALID_ENVELOPE"
            finally:
                failure.close()
            raise ApiFailure(failure.code, str(code)) from None
        except (URLError, OSError, TimeoutError):
            raise RuntimeError("LOCAL_COMMAND_OUTCOME_UNKNOWN: query the original identity before retry") from None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", required=True)
    parser.add_argument("--token-env", required=True)
    parser.add_argument("--method", choices=("GET", "POST"), required=True)
    parser.add_argument("--path", required=True)
    parser.add_argument("--request", type=Path)
    parser.add_argument("--idempotency-key")
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    token = os.environ.get(args.token_env)
    if not token:
        parser.error("local token environment is required")
    body = json.loads(args.request.read_text(encoding="utf-8")) if args.request else None
    evidence = {"method": args.method, "path": args.path, "request": body, "idempotency_key": args.idempotency_key,
                "status": "UNKNOWN", "business_services_simulated": True}
    args.evidence.parent.mkdir(parents=True, exist_ok=True)

    def persist():
        args.evidence.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")

    persist()
    try:
        evidence["response"] = GroupSenderClient(args.origin, token).request(args.method, args.path, body, args.idempotency_key)
        evidence["status"] = "PASS"
    except ApiFailure as error:
        evidence.update(status="UNKNOWN" if error.status >= 500 else "FAILED", http_status=error.status, code=error.code)
    except RuntimeError as error:
        evidence["error_type"] = type(error).__name__
    finally:
        persist()
    return 0 if evidence["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
