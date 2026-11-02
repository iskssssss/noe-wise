#!/usr/bin/env python3
"""仅供聊天回归测试使用的本地 Streamable HTTP MCP 服务。"""

from __future__ import annotations

import argparse
import copy
import hmac
import json
import os
import re
import socket
import threading
import time
from urllib.parse import parse_qs, urlsplit
from collections import Counter
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


PROTOCOL_VERSION = "2026-07-28"
MAXIMUM_BODY_BYTES = 1_048_576
SCRIPT_MODES = frozenset({"success", "empty", "malformed", "partial", "permission_denied",
                          "unauthorized", "unavailable", "timeout", "rate_limit", "barrier", "lost_reply"})


class LostReply(Exception):
    """效果已落地但回执丢失，处理器必须断开连接而非返回成功。"""


@dataclass
class MalformedValue:
    text: str


class FixtureError(ValueError):
    """夹具请求可预期地被拒绝。"""

    def __init__(self, code: str, status: int = HTTPStatus.BAD_REQUEST) -> None:
        super().__init__(code)
        self.code = code
        self.status = status


@dataclass
class FixtureStore:
    """进程内、按 batch 前缀隔离的确定性测试事实。"""

    batch: str
    records: dict[str, Any] = field(default_factory=dict)
    request_keys: dict[tuple[str, str], Any] = field(default_factory=dict)
    call_counts: dict[str, Counter[str]] = field(default_factory=dict)
    write_events: dict[str, list[dict[str, Any]]] = field(default_factory=dict)
    calls: dict[str, list[dict[str, Any]]] = field(default_factory=dict)
    unassigned_calls: list[dict[str, Any]] = field(default_factory=list)
    scripts: dict[str, list[dict[str, Any]]] = field(default_factory=dict)
    positions: Counter[str] = field(default_factory=Counter)
    barriers: dict[str, threading.Event] = field(default_factory=dict)
    entered: dict[str, threading.Event] = field(default_factory=dict)
    closing: threading.Event = field(default_factory=threading.Event)
    barrier_timeout: float = 30.0
    lock: threading.Lock = field(default_factory=threading.Lock)

    @property
    def prefix(self) -> str:
        return f"{self.batch}:"

    def require_case_key(self, case_key: Any) -> str:
        if not isinstance(case_key, str) or not case_key.startswith(self.prefix) or len(case_key) == len(self.prefix):
            raise FixtureError("case_key_outside_batch", HTTPStatus.FORBIDDEN)
        return case_key

    def create(self, case_key: Any, value: Any, script: Any = None) -> dict[str, Any]:
        key = self.require_case_key(case_key)
        if script is not None and (not isinstance(script, list) or not script or any(
                not isinstance(item, dict) or set(item) - {"mode", "value"}
                or item.get("mode") not in SCRIPT_MODES for item in script)):
            raise FixtureError("fixture_script_invalid")
        with self.lock:
            if key in self.records:
                raise FixtureError("case_key_already_exists", HTTPStatus.CONFLICT)
            self.records[key] = value
            self.call_counts[key] = Counter()
            self.write_events[key] = []
            self.calls[key] = []
            self.scripts[key] = copy.deepcopy(script or [{"mode": "success"}])
            self.barriers[key] = threading.Event()
            self.entered[key] = threading.Event()
            return {"case_key": key, "value": value}

    def begin_call(self, key: Any, name: Any, arguments: dict[str, Any]) -> dict[str, Any]:
        key = self.require_case_key(key)
        with self.lock:
            if key not in self.records:
                raise FixtureError("case_key_not_found", HTTPStatus.NOT_FOUND)
            if isinstance(name, str):
                self._count(key, name)
            return self._begin_event(self.calls[key], name, arguments)

    def begin_unassigned_call(self, name: Any, arguments: Any, *, arguments_present: bool = True,
                              params: Any = None, params_present: bool = False) -> dict[str, Any]:
        """记录无法安全归属到 case 的 tools/call，避免拒绝路径脱离审计。"""
        with self.lock:
            event = self._begin_event(self.unassigned_calls, name, arguments)
            if not arguments_present:
                event["arguments_present"] = False
            if params_present:
                event["params"] = copy.deepcopy(params)
            return event

    def finish_call(self, event: dict[str, Any], result: Any, status: str) -> None:
        with self.lock:
            event.update(result=copy.deepcopy(result), status=status, completed_at=time.time())

    @staticmethod
    def _begin_event(events: list[dict[str, Any]], name: Any, arguments: Any) -> dict[str, Any]:
        event = {"sequence": len(events) + 1, "tool": copy.deepcopy(name),
                 "arguments": copy.deepcopy(arguments), "result": None, "status": "in_flight",
                 "received_at": time.time(), "completed_at": None}
        events.append(event)
        return event

    def _step(self, key: str) -> dict[str, Any]:
        # 每次实际派发消费一项，耗尽后保持最后一项，避免意外变成成功。
        with self.lock:
            if key not in self.records:
                raise FixtureError("case_key_not_found", HTTPStatus.NOT_FOUND)
            script = self.scripts[key]
            step = script[min(self.positions[key], len(script) - 1)]
            self.positions[key] += 1
            return copy.deepcopy(step)

    def _apply_step(self, key: str, step: dict[str, Any]) -> None:
        mode = step["mode"]
        errors = {"permission_denied": 403, "unauthorized": 401, "unavailable": 503,
                  "timeout": 504, "rate_limit": 429}
        if mode in errors:
            raise FixtureError(mode, errors[mode])
        if mode == "barrier":
            self.entered[key].set()
            if not self.barriers[key].wait(self.barrier_timeout):
                raise FixtureError("barrier_timeout", HTTPStatus.GATEWAY_TIMEOUT)
            if self.closing.is_set():
                raise FixtureError("fixture_closed", HTTPStatus.SERVICE_UNAVAILABLE)

    def barrier(self, case_key: Any, *, release: bool = False) -> dict[str, Any]:
        key = self.require_case_key(case_key)
        with self.lock:
            if key not in self.records:
                raise FixtureError("case_key_not_found", HTTPStatus.NOT_FOUND)
            if release:
                self.barriers[key].set()
            return {"case_key": key, "entered": self.entered[key].is_set(),
                    "released": self.barriers[key].is_set()}

    def close(self) -> None:
        self.closing.set()
        with self.lock:
            for barrier in self.barriers.values():
                barrier.set()

    def query(self, case_key: Any) -> dict[str, Any] | MalformedValue:
        key = self.require_case_key(case_key)
        step = self._step(key)
        self._apply_step(key, step)
        if step["mode"] == "malformed":
            return MalformedValue(str(step.get("value", "{invalid-json")))
        if step["mode"] == "lost_reply":
            raise LostReply()
        with self.lock:
            if key not in self.records:
                raise FixtureError("case_key_not_found", HTTPStatus.NOT_FOUND)
            result = {"case_key": key, "value": step.get("value", self.records[key]), "complete": True}
            if step["mode"] == "empty":
                result["value"] = None
            if step["mode"] == "partial":
                result.update(complete=False, truncated=True, next_cursor="next-page")
            return result

    def write(self, case_key: Any, value: Any, request_key: Any) -> dict[str, Any]:
        key = self.require_case_key(case_key)
        if not isinstance(request_key, str) or not request_key:
            raise FixtureError("request_key_invalid")
        step = self._step(key)
        self._apply_step(key, step)
        with self.lock:
            if key not in self.records:
                raise FixtureError("case_key_not_found", HTTPStatus.NOT_FOUND)
            identity = (key, request_key)
            if identity in self.request_keys:
                if self.request_keys[identity] != value:
                    raise FixtureError("request_key_conflict", HTTPStatus.CONFLICT)
                return {"case_key": key, "value": self.records[key], "request_key": request_key,
                        "written": False}
            self.records[key] = value
            self.request_keys[identity] = copy.deepcopy(value)
            event = {"case_key": key, "value": value, "request_key": request_key}
            self.write_events[key].append(event)
            if step["mode"] == "lost_reply":
                raise LostReply()
            return {**event, "written": True}

    def audit(self, case_key: Any) -> dict[str, Any]:
        key = self.require_case_key(case_key)
        with self.lock:
            if key not in self.records:
                raise FixtureError("case_key_not_found", HTTPStatus.NOT_FOUND)
            counts = self.call_counts[key]
            return {
                "case_key": key,
                "complete": True,
                "call_counts": {name: counts[name] for name in sorted(counts)},
                "write_events": list(self.write_events[key]),
                "calls": copy.deepcopy(self.calls[key]),
            }

    def audit_unassigned(self) -> dict[str, Any]:
        with self.lock:
            return {
                "batch": self.batch,
                "complete": True,
                "calls": copy.deepcopy(self.unassigned_calls),
            }

    def _count(self, key: str, name: str) -> None:
        self.call_counts.setdefault(key, Counter())[name] += 1


def _schema(properties: dict[str, Any], required: list[str]) -> dict[str, Any]:
    return {"type": "object", "properties": properties, "required": required,
            "additionalProperties": False}


TOOLS = [
    {
        "name": "query_record",
        "description": "按 case_key 查询测试记录，成功响应包含 case_key、value 和 complete。complete=false 时再次查询同一 case_key 获取下一页；complete=true 仅表示本次分页已到末页，不再继续翻页；用户后续明确要求重新查询时，应按新请求再次查询。",
        "inputSchema": _schema({"case_key": {"type": "string", "minLength": 1}}, ["case_key"]),
        "outputSchema": _schema({"case_key": {"type": "string"}, "value": {},
                                 "complete": {"type": "boolean"}, "truncated": {"type": "boolean"},
                                 "next_cursor": {"type": "string"}}, ["case_key", "value", "complete"]),
    },
    {
        "name": "write_record",
        "description": "使用 request_key 对 case_key 的测试记录进行幂等写入。",
        "inputSchema": _schema({
            "case_key": {"type": "string", "minLength": 1},
            "value": {},
            "request_key": {"type": "string", "minLength": 1},
        }, ["case_key", "value", "request_key"]),
    },
    {
        "name": "fault_probe",
        "description": "显式注入有限延迟或可预期错误，不访问业务数据。",
        "inputSchema": _schema({
            "case_key": {"type": "string", "minLength": 1},
            "mode": {"type": "string", "enum": ["error", "delay"]},
            "delay_seconds": {"type": "integer", "minimum": 0, "maximum": 10},
        }, ["case_key", "mode", "delay_seconds"]),
    },
]


def _json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def tool_result(value: dict[str, Any] | MalformedValue, is_error: bool = False) -> dict[str, Any]:
    """text 与 structuredContent 使用同一个业务对象，避免回归断言有两套事实。"""
    if isinstance(value, MalformedValue):
        return {"resultType": "complete", "content": [{"type": "text", "text": value.text}],
                "isError": False}
    return {
        "resultType": "complete",
        "content": [{"type": "text", "text": _json_text(value)}],
        "structuredContent": value,
        "isError": is_error,
    }


def rpc_error(request_id: Any, code: int, message: str) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


class McpFixtureHandler(BaseHTTPRequestHandler):
    server: "McpFixtureServer"
    protocol_version = "HTTP/1.1"

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(5)

    def log_message(self, _format: str, *_args: Any) -> None:
        # 回归夹具不把请求载荷或可选 token 输出到控制台。
        return

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler hook
        if not self._authorized():
            self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return
        path = urlsplit(self.path)
        if path.path == "/health":
            self._send_json(HTTPStatus.OK, {"status": "ok", "batch": self.server.store.batch})
            return
        if path.path == "/audit-unassigned":
            if path.query:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "audit_request_invalid"})
                return
            self._send_json(HTTPStatus.OK, self.server.store.audit_unassigned())
            return
        if path.path in {"/audit", "/barrier"}:
            query = parse_qs(path.query, keep_blank_values=True)
            if set(query) != {"case_key"} or len(query["case_key"]) != 1:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "audit_request_invalid"})
                return
            try:
                read = self.server.store.audit if path.path == "/audit" else self.server.store.barrier
                self._send_json(HTTPStatus.OK, read(query["case_key"][0]))
            except FixtureError as error:
                self._send_json(error.status, {"error": error.code})
            return
        self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler hook
        if not self._authorized():
            self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return
        try:
            payload = self._read_json()
            if self.path == "/fixtures":
                self._fixtures(payload)
            elif self.path == "/mcp":
                self._mcp(payload)
            elif self.path == "/barrier/release":
                if not isinstance(payload, dict) or set(payload) != {"case_key"}:
                    raise FixtureError("barrier_request_invalid")
                self._send_json(HTTPStatus.OK, self.server.store.barrier(payload["case_key"], release=True))
            else:
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
        except FixtureError as error:
            self._send_json(error.status, {"error": error.code})
        except LostReply:
            self.close_connection = True
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def _fixtures(self, payload: Any) -> None:
        if (not isinstance(payload, dict) or not {"case_key", "value"}.issubset(payload)
                or set(payload) - {"case_key", "value", "script"}):
            raise FixtureError("fixture_request_invalid")
        self._send_json(HTTPStatus.CREATED, self.server.store.create(
            payload["case_key"], payload["value"], payload.get("script")))

    def _mcp(self, payload: Any) -> None:
        requests = payload if isinstance(payload, list) else [payload]
        if not requests:
            self._send_json(HTTPStatus.BAD_REQUEST, rpc_error(None, -32600, "Invalid Request"))
            return
        responses: list[dict[str, Any]] = []
        for request in requests:
            response = self._dispatch_rpc(request)
            if response is not None:
                responses.append(response)
        if not responses:
            self.send_response(HTTPStatus.ACCEPTED)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self._send_json(HTTPStatus.OK, responses if isinstance(payload, list) else responses[0])

    def _dispatch_rpc(self, request: Any) -> dict[str, Any] | None:
        if not isinstance(request, dict) or request.get("jsonrpc") != "2.0" or not isinstance(request.get("method"), str):
            response = rpc_error(request.get("id") if isinstance(request, dict) else None, -32600, "Invalid Request")
            if isinstance(request, dict) and request.get("method") == "tools/call":
                event = self._begin_tool_call(request.get("params", {}))
                self.server.store.finish_call(event, response, "error")
            return response
        is_notification = "id" not in request
        request_id = request.get("id")
        method = request["method"]
        params = request.get("params", {})
        if not isinstance(params, dict):
            if method == "tools/call":
                event = self._begin_tool_call(params)
                response = rpc_error(request_id, -32602, "Invalid params")
                self.server.store.finish_call(event, response, "error")
                return None if is_notification else response
            return None if is_notification else rpc_error(request_id, -32602, "Invalid params")
        if method == "notifications/initialized":
            return None if is_notification else {"jsonrpc": "2.0", "id": request_id, "result": {}}
        if method == "initialize":
            result = {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "skillforge-chat-regression-fixture", "version": "1"},
            }
            return None if is_notification else {"jsonrpc": "2.0", "id": request_id, "result": result}
        if method == "tools/list":
            result = {"tools": self.server.tools, "cacheScope": "private", "ttlMs": 0}
            return None if is_notification else {"jsonrpc": "2.0", "id": request_id, "result": result}
        if method == "tools/call":
            result = self._call_tool(params)
            return None if is_notification else {"jsonrpc": "2.0", "id": request_id, "result": result}
        return None if is_notification else rpc_error(request_id, -32601, "Method not found")

    def _call_tool(self, params: dict[str, Any]) -> dict[str, Any]:
        event = self._begin_tool_call(params)
        try:
            result = self._execute_tool(params)
        except LostReply:
            self.server.store.finish_call(event, None, "lost_reply")
            raise
        self.server.store.finish_call(event, result, "error" if result.get("isError") else "completed")
        return result

    def _begin_tool_call(self, params: Any) -> dict[str, Any]:
        if not isinstance(params, dict):
            return self.server.store.begin_unassigned_call(
                None, None, arguments_present=False, params=params, params_present=True)
        arguments_present = "arguments" in params
        arguments = params.get("arguments")
        name = params.get("name")
        if isinstance(arguments, dict):
            try:
                event = self.server.store.begin_call(arguments.get("case_key"), name, arguments)
            except FixtureError:
                event = self.server.store.begin_unassigned_call(
                    name, arguments, arguments_present=arguments_present)
        else:
            return self.server.store.begin_unassigned_call(
                name, arguments, arguments_present=arguments_present)
        return event

    def _execute_tool(self, params: dict[str, Any]) -> dict[str, Any]:
        if set(params) - {"name", "arguments", "_meta"} or not isinstance(params.get("name"), str):
            return tool_result({"error": "tool_call_invalid"}, True)
        arguments = params.get("arguments", {})
        if not isinstance(arguments, dict):
            return tool_result({"error": "tool_arguments_invalid"}, True)
        name = params["name"]
        try:
            if name == "query_record" or name == self.server.query_alias:
                self._exact_arguments(arguments, {"case_key"})
                return tool_result(self.server.store.query(arguments["case_key"]))
            if name == "write_record":
                self._exact_arguments(arguments, {"case_key", "value", "request_key"})
                return tool_result(self.server.store.write(arguments["case_key"], arguments["value"], arguments["request_key"]))
            if name == "fault_probe":
                self._exact_arguments(arguments, {"case_key", "mode", "delay_seconds"})
                key = self.server.store.require_case_key(arguments["case_key"])
                mode = arguments["mode"]
                delay = arguments["delay_seconds"]
                if mode not in {"error", "delay"}:
                    raise FixtureError("fault_mode_invalid")
                if isinstance(delay, bool) or not isinstance(delay, int) or not 0 <= delay <= 10:
                    raise FixtureError("delay_seconds_invalid")
                with self.server.store.lock:
                    if key not in self.server.store.records:
                        raise FixtureError("case_key_not_found", HTTPStatus.NOT_FOUND)
                if mode == "delay":
                    if delay:
                        time.sleep(delay)
                    return tool_result({"case_key": key, "mode": mode, "delay_seconds": delay})
                return tool_result({"case_key": key, "mode": mode, "error": "fault_probe_error"}, True)
            return tool_result({"error": "tool_not_found", "name": name}, True)
        except FixtureError as error:
            return tool_result({"error": error.code, "status": error.status}, True)

    @staticmethod
    def _exact_arguments(arguments: dict[str, Any], allowed: set[str], required: set[str] | None = None) -> None:
        required = allowed if required is None else required
        if set(arguments) - allowed or not required.issubset(arguments):
            raise FixtureError("tool_arguments_invalid")

    def _read_json(self) -> Any:
        length_text = self.headers.get("Content-Length")
        try:
            length = int(length_text or "")
        except ValueError as error:
            raise FixtureError("content_length_invalid") from error
        if length < 0 or length > MAXIMUM_BODY_BYTES:
            raise FixtureError("request_too_large", HTTPStatus.REQUEST_ENTITY_TOO_LARGE)
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise FixtureError("json_invalid") from error

    def _authorized(self) -> bool:
        token = self.server.token
        if token is None:
            return True
        return hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {token}")

    def _send_json(self, status: int, payload: Any) -> None:
        body = _json_text(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("MCP-Protocol-Version", PROTOCOL_VERSION)
        self.end_headers()
        self.wfile.write(body)


class McpFixtureServer(ThreadingHTTPServer):
    """只监听 loopback 的线程安全 HTTP 夹具。"""

    daemon_threads = False

    def __init__(self, port: int, batch: str, token: str | None = None, *, query_alias: str | None = None) -> None:
        if not batch or ":" in batch:
            raise ValueError("batch must be non-empty and must not contain ':'")
        if query_alias is not None and (not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", query_alias)
                or query_alias in {tool["name"] for tool in TOOLS}):
            raise ValueError("query_alias_invalid")
        super().__init__(("127.0.0.1", port), McpFixtureHandler)
        self.store = FixtureStore(batch)
        self.token = token
        self.query_alias = query_alias
        self.tools = copy.deepcopy(TOOLS)
        if query_alias is not None:
            alias = copy.deepcopy(TOOLS[0]); alias["name"] = query_alias
            self.tools.append(alias)

    def server_close(self) -> None:
        self.store.close()
        super().server_close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18765, help="监听端口；0 由系统分配")
    parser.add_argument("--batch", default=os.environ.get("MCP_FIXTURE_BATCH", "chat-regression"),
                        help="允许 case_key 使用的前缀（实际前缀为 batch:）")
    args = parser.parse_args()
    token = os.environ.get("MCP_FIXTURE_TOKEN") or None
    server = McpFixtureServer(args.port, args.batch, token)
    actual_port = server.server_address[1]
    print(f"MCP_FIXTURE_PORT={actual_port}", flush=True)
    print(f"MCP_FIXTURE_URL=http://127.0.0.1:{actual_port}/mcp", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
