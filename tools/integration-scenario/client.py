"""真实本地 SkillForge HTTP 客户端；签名与服务端 requestTarget 完全一致。"""
from __future__ import annotations

import hashlib
import hmac
import json
import logging
import time
from email.utils import parsedate_to_datetime
from dataclasses import dataclass
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

LOG = logging.getLogger(__name__)


class ApiFailure(RuntimeError):
    """保留可诊断状态和错误码，不把响应载荷或凭据加入异常。"""

    def __init__(self, status: int, code: str, retry_after_seconds: float | None = None):
        LOG.debug("构造接入错误: phase=entry status=%s", status)
        self.status, self.code = status, code
        self.retry_after_seconds = retry_after_seconds
        super().__init__(f"HTTP_{status}:{code}")
        LOG.debug("构造接入错误: phase=exit status=%s", status)


class NoRedirect(HTTPRedirectHandler):
    """禁止签名凭据随 HTTP 重定向泄漏到另一个地址。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        LOG.debug("拒绝接入重定向: status=%s", code)
        return None


@dataclass(frozen=True)
class Credential:
    """固定机构执行凭据；签名头不能换成平台管理身份。"""
    api_key: str
    signing_secret: str

    def headers(self, method: str, target: str, body: bytes) -> dict[str, str]:
        """对实际发送的 UTF-8 body 和带原始 query 的路径签名，不签解码后的参数。"""
        LOG.debug("构造接入签名: phase=entry method=%s", method)
        timestamp = str(int(time.time()))
        canonical = "\n".join((timestamp, method, target, hashlib.sha256(body).hexdigest()))
        signature = hmac.new(self.signing_secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()
        LOG.debug("构造接入签名: phase=exit method=%s", method)
        return {"X-Api-Key": self.api_key, "X-Timestamp": timestamp, "X-Signature": signature}


@dataclass(frozen=True)
class ManagementCredential:
    """平台管理主体的一次签发凭据，仅用于平台管理 API。"""
    management_key: str
    signing_secret: str

    def headers(self, method: str, target: str, body: bytes) -> dict[str, str]:
        """使用独立管理头绑定原始请求目标和正文，与执行凭据不互换。"""
        timestamp = str(int(time.time()))
        canonical = "\n".join((timestamp, method, target, hashlib.sha256(body).hexdigest()))
        signature = hmac.new(self.signing_secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()
        return {"X-Management-Key": self.management_key, "X-Timestamp": timestamp, "X-Signature": signature}


class Client:
    def __init__(self, origin: str, credential: Credential | None = None, platform_token: str | None = None,
                 institution_token: str | None = None, management_credential: ManagementCredential | None = None):
        """示例只允许明确的 loopback origin，避免误对生产系统执行建机构和模型调用。"""
        LOG.debug("初始化接入HTTP客户端: phase=entry")
        parsed = urlparse(origin)
        if (parsed.scheme not in {"http", "https"} or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}
                or parsed.username or parsed.password or parsed.path not in {"", "/"} or parsed.query or parsed.fragment):
            raise ValueError("EXPLICIT_LOOPBACK_ORIGIN_REQUIRED")
        if sum(bool(value) for value in (credential, platform_token, institution_token, management_credential)) > 1:
            raise ValueError("EXACTLY_ONE_AUTHENTICATION_KIND_REQUIRED")
        self.origin, self.credential, self.platform_token = origin.rstrip("/"), credential, platform_token
        self.institution_token = institution_token
        self.management_credential = management_credential
        self.opener = build_opener(NoRedirect())
        LOG.debug("初始化接入HTTP客户端: phase=exit")

    def request(self, method: str, path: str, body=None, query=None, idempotency_key=None, stream=False,
                if_match: int | None = None, read_deadline: float | None = None):
        """发起一次真实 HTTP；网络未知结果不重发，稳定输入的重试由上层日志控制。"""
        LOG.debug("调用接入HTTP: phase=entry method=%s path=%s", method, path)
        target = "/api/v1" + path
        if query:
            target += "?" + urlencode({key: value for key, value in query.items() if value is not None})
        raw = b"" if body is None else json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
        headers = {"Accept": "text/event-stream" if stream else "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if self.credential:
            if not path.startswith("/open-platform/"):
                raise ValueError("EXECUTION_CREDENTIAL_REQUIRES_OPEN_PLATFORM_PATH")
            headers.update(self.credential.headers(method, target, raw))
        if self.management_credential:
            if not path.startswith("/platform/"):
                raise ValueError("MANAGEMENT_CREDENTIAL_REQUIRES_PLATFORM_PATH")
            headers.update(self.management_credential.headers(method, target, raw))
        if self.platform_token:
            headers["X-Platform-Token"] = "Bearer " + self.platform_token
        if self.institution_token:
            headers["Authorization"] = "Bearer " + self.institution_token
        if if_match is not None:
            if isinstance(if_match, bool) or not isinstance(if_match, int) or if_match < 1:
                raise ValueError("POSITIVE_DEFINITION_REVISION_REQUIRED")
            headers["If-Match"] = str(if_match)
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        timeout = 180 if stream else 90
        if read_deadline is not None:
            if method != "GET":
                raise ValueError("READ_DEADLINE_REQUIRES_GET")
            remaining = read_deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError("RUN_READ_BUDGET_EXHAUSTED: preserve the original Run and cursor")
            timeout = min(timeout, remaining)
        try:
            response = self.opener.open(Request(self.origin + target, data=raw if body is not None else None,
                                                headers=headers, method=method), timeout=timeout)
            if stream:
                if "text/event-stream" not in response.headers.get("Content-Type", ""):
                    response.close()
                    raise ApiFailure(response.status, "SSE_CONTENT_TYPE_REQUIRED")
                return response
            with response:
                if response.status == 204:
                    return None
                envelope = json.loads(response.read())
            if envelope.get("code") != "OK":
                raise ApiFailure(response.status, str(envelope.get("code", "INVALID_ENVELOPE")))
            return envelope["data"]
        except HTTPError as failure:
            try:
                code = json.loads(failure.read()).get("code", "INVALID_ENVELOPE")
            except (ValueError, AttributeError):
                code = "INVALID_ENVELOPE"
            retry_after = retry_after_seconds(failure.headers.get("Retry-After")) if failure.code == 429 else None
            raise ApiFailure(failure.code, str(code), retry_after) from None
        except (URLError, TimeoutError, OSError):
            raise RuntimeError("HTTP_OUTCOME_UNKNOWN: inspect persisted operation before retry") from None
        finally:
            LOG.debug("调用接入HTTP: phase=exit method=%s path=%s", method, path)


def retry_after_seconds(value: str | None) -> float | None:
    """只采用服务端明确的Retry-After秒数或HTTP日期，缺失或非法时不发明重试政策。"""
    if not value:
        return None
    try:
        value = value.strip()
        if value.isascii() and value.isdigit():
            return float(int(value))
        parsed = parsedate_to_datetime(value)
        if parsed.tzinfo is None:
            return None
        return max(0.0, parsed.timestamp() - time.time())
    except (ValueError, TypeError, OverflowError):
        return None


def read_until(client: Client, path: str, query: dict, deadline: float):
    """仅GET限流可按明确Retry-After重读；等待与HTTP都使用原monotonic预算，写入从不自动重放。"""
    while time.monotonic() < deadline:
        try:
            return client.request("GET", path, query=query, read_deadline=deadline)
        except ApiFailure as failure:
            if failure.status != 429 or failure.retry_after_seconds is None:
                raise
            remaining = deadline - time.monotonic()
            if failure.retry_after_seconds >= remaining:
                raise RuntimeError("RUN_READ_BUDGET_EXHAUSTED: Retry-After exceeds the original Run budget") from None
            LOG.info("公开只读请求受限，按Retry-After等待再读 path=%s seconds=%s", path, failure.retry_after_seconds)
            time.sleep(failure.retry_after_seconds)
    raise RuntimeError("RUN_READ_BUDGET_EXHAUSTED: preserve the original Run and cursor")


def read_sse(response):
    """解析 SSE 的 id/event/data；忽略 keepalive，跨行 data 保持协议语义。"""
    LOG.debug("读取接入SSE: phase=entry")
    fields, data = {}, []
    try:
        for raw in response:
            line = raw.decode("utf-8").rstrip("\r\n")
            if not line:
                if data:
                    yield {"event": fields.get("event", "message"), "event_id": fields.get("id"),
                           "data": json.loads("\n".join(data))}
                fields, data = {}, []
            elif not line.startswith(":"):
                name, _, value = line.partition(":")
                value = value[1:] if value.startswith(" ") else value
                if name == "data":
                    data.append(value)
                elif name in {"event", "id"}:
                    fields[name] = value
    finally:
        LOG.debug("读取接入SSE: phase=exit")
