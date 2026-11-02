"""沙箱验收的显式隔离连接与受理分类，不读取仓库环境或重放未知提交。"""
from __future__ import annotations
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

ADMISSION_CODES = {"INVALID_ARTIFACT_ARCHIVE", "INVALID_EXECUTION_MANIFEST", "INVALID_EXECUTION_POLICY", "EXECUTION_DEADLINE_EXPIRED"}
ACTIVE_STATES = {"queued", "running"}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """控制凭据不允许随重定向转发。"""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("SANDBOX_REDIRECT_REJECTED")


def configuration(service_config: Path | None, batch_id: str) -> tuple[str, str]:
    """只接受本批显式loopback控制面；token只来自私有文件或进程环境。"""
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{2,70}", batch_id or ""):
        raise ValueError("EXPLICIT_BATCH_ID_REQUIRED")
    values = json.loads(service_config.read_text(encoding="utf-8-sig")) if service_config else {
        "endpoint": os.environ.get("SKILL_SANDBOX_ENDPOINT"), "token": os.environ.get("SKILL_SANDBOX_TOKEN")}
    if not isinstance(values, dict):
        raise ValueError("INVALID_PRIVATE_SERVICE_CONFIG")
    endpoint, token = values.get("endpoint"), values.get("token")
    if not isinstance(endpoint, str) or not isinstance(token, str) or len(token) < 32:
        raise ValueError("EXPLICIT_SANDBOX_ENDPOINT_AND_TOKEN_REQUIRED")
    parsed = urllib.parse.urlsplit(endpoint)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or not parsed.port or parsed.path not in ("", "/") or parsed.query or parsed.fragment or parsed.username or parsed.password:
        raise ValueError("ISOLATED_LOOPBACK_SANDBOX_REQUIRED")
    return endpoint.rstrip("/"), token


def admission_failure(error: urllib.error.HTTPError) -> str:
    """仅严格匹配类型化回执可认定未提交；其余HTTP错误保存unknown。"""
    try:
        payload = json.loads(error.read(16385))
    except (ValueError, UnicodeError):
        return "unknown"
    return "rejected" if (isinstance(payload, dict) and set(payload) == {"code", "message", "submission"}
                           and payload.get("submission") == "NOT_SUBMITTED" and payload.get("code") in ADMISSION_CODES
                           and isinstance(payload.get("message"), str)) else "unknown"
