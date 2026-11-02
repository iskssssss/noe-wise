"""真实 HTTP/SSE 客户端：不重试写操作，不将 HTTP 200 等同于运行成功。"""
import json
import os
import time
import threading
from contextlib import nullcontext
import urllib.error
import urllib.parse
import urllib.request


class ApiError(RuntimeError):
    """客户端可安全暴露的失败信息，不保留服务端响应正文。"""

    def __init__(self, message, *, status_code=None, method=None, path=None, transport_kind=None, response_kind=None):
        super().__init__(message)
        self.status_code = status_code
        self.method = method
        self.path = path
        self.transport_kind = transport_kind
        self.response_kind = response_kind


class ModelQuotaStop(ApiError):
    """额度或限流信号：停止批次，不重试付费请求。"""


def failure_diagnostic(error):
    """只记录可枚举的失败字段，拒绝异常正文、查询参数及任意端点内容。"""
    result = {"kind": "unexpected_failure"}
    if isinstance(error, ApiError):
        result["kind"] = "quota_or_rate_limit" if isinstance(error, ModelQuotaStop) else "api_failure"
        if type(error.status_code) is int and 100 <= error.status_code <= 599:
            result["http_status"] = error.status_code
        if error.method in ("GET", "POST", "PUT", "PATCH", "DELETE"):
            result["method"] = error.method
        path = error.path.split("?", 1)[0] if isinstance(error.path, str) else None
        allowed = {"/agents/detail", "/admin/models", "/admin/system-agents", "/auth/refresh",
                   "/platform/auth/refresh", "/chat/history", "/chat/work/history", "/chat/trace",
                   "/chat/runs/status", "/chat/runs/cancel"}
        if path in allowed:
            result["endpoint"] = path
        if error.transport_kind in {"URLError", "TimeoutError", "OSError", "ConnectionError"}:
            result["transport_kind"] = error.transport_kind
        if error.response_kind in {"invalid_json", "invalid_envelope", "oversized_response"}:
            result["response_kind"] = error.response_kind
    return result


def quota_signal(value):
    text = str(value).lower()
    return any(code in text for code in ("insufficient_quota", "insufficient_balance", "insufficient credit",
        "quota_exceeded", "quota exhausted", "credit balance", "余额不足", "额度不足", "配额耗尽"))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """重定向不是目标服务的受理回执，禁止转发凭据或重复发送请求。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Api:
    _REFRESH_LEEWAY_SECONDS = 30

    def __init__(self, base, token=None, platform=False, timeout=30, *, expires_in=None, refresh_token=None):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(NoRedirect())
        self.headers = {"Content-Type": "application/json"}
        self.platform = platform
        self.timeout = timeout
        self.interval = float(os.environ.get("SF_TEST_REQUEST_INTERVAL", "0.55")) if self.base.endswith("/api/v1") else 0
        self._pace_lock = threading.Lock()
        self._next_request = 0.0
        self._auth_lock = threading.Lock()
        self._refresh_failure = None
        self.dispatch_gate = None
        self._refresh_token = refresh_token if isinstance(refresh_token, str) and refresh_token else None
        self._access_expires_at = self._expires_at(expires_in)
        if token:
            self._set_access_token(token)

    def _expires_at(self, expires_in):
        if isinstance(expires_in, bool) or not isinstance(expires_in, (int, float)) or expires_in <= 0:
            return None
        return time.monotonic() + expires_in

    def _set_access_token(self, token):
        header = "X-Platform-Token" if self.platform else "Authorization"
        self.headers[header] = "Bearer " + token

    def _refresh_due(self):
        return self._access_expires_at is not None and time.monotonic() >= (
            self._access_expires_at - self._REFRESH_LEEWAY_SECONDS)

    def _refresh_if_due(self):
        if not self._refresh_due():
            return
        with self._auth_lock:
            if self._refresh_failure is not None:
                raise self._refresh_failure
            if not self._refresh_due():
                return
            if not self._refresh_token:
                self._refresh_failure = ApiError("access token refresh is unavailable")
                raise self._refresh_failure
            # 刷新端点不携带旧访问令牌，也不会进入当前实例的刷新检查。
            refresh_api = Api(self.base, timeout=self.timeout)
            try:
                data = refresh_api.request(
                    "POST", "/platform/auth/refresh" if self.platform else "/auth/refresh",
                    {"refresh_token": self._refresh_token}, _rate_retry=True)
                access_token = data["access_token"]
                refresh_token = data["refresh_token"]
                expires_in = data["expires_in"]
                if (not isinstance(access_token, str) or not access_token or
                        not isinstance(refresh_token, str) or not refresh_token or
                        isinstance(expires_in, bool) or not isinstance(expires_in, (int, float)) or expires_in <= 0):
                    raise ValueError("invalid auth response")
            except ApiError as error:
                self._refresh_failure = ApiError("access token refresh failed", status_code=error.status_code,
                                                 method=error.method, path=error.path)
                raise self._refresh_failure from None
            except (KeyError, TypeError, ValueError):
                self._refresh_failure = ApiError("access token refresh returned invalid response")
                raise self._refresh_failure from None
            self._set_access_token(access_token)
            self._refresh_token = refresh_token
            self._access_expires_at = time.monotonic() + expires_in

    def open(self, method, path, body=None, headers=None, timeout=None, _rate_retry=False):
        if not path.startswith("/") or path.startswith("//"):
            raise ValueError("仅允许相对于配置服务的绝对路径")
        self._refresh_if_due()
        safe_path = path.split("?", 1)[0]
        req = urllib.request.Request(self.base + path,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode(),
            headers={**self.headers, **(headers or {})}, method=method)
        with self._pace_lock:
            wait = max(0, self._next_request - time.monotonic())
            self._next_request = time.monotonic() + wait + self.interval
        if wait: time.sleep(wait)
        try:
            # 付费批次在节流之后、实际发送之前与全局熔断仲裁；重试也经过同一入口。
            gate = self.dispatch_gate(method, safe_path) if self.dispatch_gate else nullcontext()
            with gate:
                return self.opener.open(req, timeout=timeout or self.timeout)
        except urllib.error.HTTPError as e:
            error_body = e.read(8192).decode("utf-8", errors="replace")
            # 此宿主拦截器在Controller受理前拒绝，明确不产生Run；只对此精确契约允许一次等待重试。
            if e.code == 429 and not _rate_retry:
                try: local_limit = json.loads(error_body).get("code") == "RATE_LIMITED" and e.headers.get("Retry-After") is not None
                except ValueError: local_limit = False
                if local_limit:
                    time.sleep(min(60, max(1, int(e.headers.get("Retry-After", "60")))))
                    return self.open(method,path,body,headers,timeout,_rate_retry=True)
            if e.code in (402, 429) or quota_signal(error_body):
                raise ModelQuotaStop(f"HTTP {e.code}: quota_or_rate_limit; batch stopped",
                                     status_code=e.code, method=method, path=safe_path) from None
            # 不输出供应商正文或请求头中的凭据。
            raise ApiError(f"{method} {safe_path} HTTP {e.code}", status_code=e.code,
                           method=method, path=safe_path) from None
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise ApiError(f"{method} {safe_path} transport={type(e).__name__}", method=method,
                           path=safe_path, transport_kind=type(e).__name__) from None

    def request(self, method, path, body=None, headers=None, _rate_retry=False):
        with self.open(method, path, body, headers, _rate_retry=_rate_retry) as r:
            data = r.read(16 * 1024 * 1024 + 1)
            if len(data) > 16 * 1024 * 1024:
                raise ApiError("JSON响应超过16MiB上限", method=method, path=path,
                               response_kind="oversized_response")
            if not data:
                return None
            try:
                value = json.loads(data)
            except (ValueError, UnicodeError):
                raise ApiError("JSON响应无法解析", method=method, path=path, response_kind="invalid_json") from None
            # 业务宿主采用信封，独立 MCP HTTP 夹具保持其自身协议。
            if self.base.endswith("/api/v1"):
                if not isinstance(value, dict) or value.get("code") != "OK" or "data" not in value:
                    raise ApiError("Host API response violates ApiResponse contract", method=method,
                                   path=path, response_kind="invalid_envelope")
                return value["data"]
            return value


def login(base, platform=False):
    prefix = "SF_PLATFORM" if platform else "SF_ADMIN"
    body = {"username": os.environ[prefix + "_USER"], "password": os.environ[prefix + "_PASSWORD"]}
    if not platform:
        body["tenant_slug"] = os.environ.get("SF_TENANT", "demo")
    data = Api(base).request("POST", "/platform/auth/login" if platform else "/auth/login", body)
    return Api(base, data["access_token"], platform, expires_in=data.get("expires_in"),
               refresh_token=data.get("refresh_token"))


def query(path, **params):
    return path + "?" + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})


def sse_events(response, deadline):
    """保留事件ID，支持注释和多行data；不把残缺事件当作完整事件。"""
    event, eid, data, size = "message", None, [], 0
    while True:
        if time.monotonic() >= deadline:
            raise TimeoutError("SSE deadline")
        raw = response.readline(1024 * 1024 + 1)
        if not raw:
            if data:
                raise ApiError("SSE incomplete event")
            return
        size += len(raw)
        if len(raw) > 1024 * 1024 or size > 8 * 1024 * 1024:
            raise ApiError("SSE event too large")
        line = raw.decode("utf-8").rstrip("\r\n")
        if not line:
            if data:
                yield {"event": event, "id": eid, "data": json.loads("\n".join(data))}
            event, eid, data, size = "message", None, [], 0
        elif line.startswith(":"):
            continue
        else:
            key, _, value = line.partition(":")
            value = value[1:] if value.startswith(" ") else value
            if key == "event": event = value
            elif key == "id": eid = value
            elif key == "data": data.append(value)


def collect_trace(api, session_id, previous=None, *, include_content=True, content_cache=None, full_content=False):
    # 使用持久sequence增量采集，避免长对话每一轮重复下载全部内容引用。
    previous = previous or {}
    full_content = bool(full_content and include_content)
    pages = list(previous.get("pages", []))
    # 非 FULL 的内容是可变投影；下次观察必须重新读取，不能把它当作事件正文缓存。
    contents = ({ref: value for ref, value in previous.get("contents", {}).items()
                 if isinstance(value, dict) and value.get("availability") == "FULL"}
                if include_content else {})
    seen = set()
    cursor = previous.get("cursor")
    last_sequence = int(cursor or 0)
    for _ in range(500):
        page = api.request("GET", query("/chat/sessions/trace", sessionId=session_id, cursor=cursor, page_size=200))
        pages.append(page)
        # 组成、阶段等指标已在页面的类型化 diagnostics 中完整返回；只有回读断言需要事件正文。
        reference_events = {item["event_id"] for item in page.get("diagnostics", {}).get("runtime_reference_read_measurements", [])}
        for round_ in page["rounds"]:
            for run in round_["runs"]:
                for step in run["steps"]:
                    last_sequence = max(last_sequence, int(step.get("last_sequence", 0)))
                    for phase in step["phases"]:
                        ref = phase.get("content_ref")
                        needed = phase.get("event_type") in {"run.completed","run.suspended","run.failed","run.terminated","run.cancelled",
                            "agent.task-context","agent.model-result","agent.tool-result","skill.activation","agent.decision-decode-failure"}
                        needed = full_content or needed or phase.get("event_id") in reference_events
                        if not include_content or not ref or not needed:
                            continue
                        cached = contents.get(ref)
                        if not (isinstance(cached, dict) and cached.get("availability") == "FULL"):
                            cached = content_cache.get(ref) if content_cache is not None else None
                            if not (isinstance(cached, dict) and cached.get("availability") == "FULL"):
                                cached = api.request("GET", query("/chat/sessions/trace/content", sessionId=session_id, ref=ref))
                                if (content_cache is not None and isinstance(cached, dict)
                                        and cached.get("availability") == "FULL"):
                                    content_cache[ref] = cached
                            contents[ref] = cached
        next_ = page.get("next_cursor")
        if full_content and next_ and next_ == cursor:
            raise ApiError("trace cursor did not advance; evidence incomplete")
        if not next_ or next_ == cursor:
            return {"pages": pages, "contents": contents, "usage": page.get("usage_summary", {}), "cursor": str(last_sequence)}
        if next_ in seen:
            raise ApiError("trace cursor cycle")
        seen.add(next_)
        cursor = next_
    raise ApiError("trace分页超过预算；证据不完整")


def is_execution_terminal(event_type, data):
    """completed 是驱动回执名；委派等待时仍可为 running，只有收口或受控 HITL 才结束观察。"""
    return event_type == "execution.completed" and data.get("status") in {
        "completed", "failed", "cancelled", "waiting_hitl"
    }


def poll_terminal(api, session_id, message_id, deadline, emit, cursor=None,
                  expected_run=None, accepted=False):
    expected_run = expected_run or "run:" + message_id
    # 未记录本次 accepted 时从头寻找输入边界；只读回放不会重新提交任务。
    if not accepted:
        cursor = None
    while time.monotonic() < deadline:
        page = api.request("GET", query("/chat/events", session_id=session_id, cursor=cursor, limit=500))
        for event in page["events"]:
            data = event["data"]
            if (event["event"] == "execution.accepted" and data.get("run_id") == expected_run
                    and data.get("input_id") == message_id):
                accepted = True
            if accepted and data.get("run_id") == expected_run:
                emit({"event": event["event"], "id": event.get("event_id"), "data": data, "transport": "outbox"})
                if is_execution_terminal(event["event"], data):
                    return data
        next_ = page.get("next_cursor")
        if next_ and next_ != cursor:
            cursor = next_
            continue
        time.sleep(min(1, max(0, deadline - time.monotonic())))
    raise TimeoutError("未观察到原请求终态；保留消息ID，禁止盲目重发")
