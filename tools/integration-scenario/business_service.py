"""本地模拟业务服务：MCP 业务卡片与媒体 Connector、暂停和企微发送。"""
from __future__ import annotations

import argparse
import json
import logging
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from state import State
from websocket_transport import accept_key, read_frame, send_frame
from client import ApiFailure
from group_sender import SenderError
from group_sender_http import MAX_REQUEST_BYTES, SenderApplication
from business_workflow import (BUSINESS_TOOL_NAMES, CONFIRMATION_TTL_SECONDS,
                               BusinessAuthorizations, BusinessError, BusinessWorkflow, load_business_config)

LOG = logging.getLogger(__name__)
MCP_PROTOCOL_VERSION = "2026-07-28"
MCP_HANDSHAKE_VERSION = "2025-11-25"
TOOLS = [
    {"name": "media_transcribe", "description": "模拟STT Connector；返回明确模拟的转写，不请求原生媒体服务。",
     "inputSchema": {"type": "object", "properties": {"request_id": {"type": "string"}, "media_ref": {"type": "string"}},
                      "required": ["request_id", "media_ref"], "additionalProperties": False}},
    {"name": "media_synthesize", "description": "模拟TTS Connector；返回业务媒体引用，实际媒体供应商由Connector替换。",
     "inputSchema": {"type": "object", "properties": {"request_id": {"type": "string"}, "text": {"type": "string"}},
                      "required": ["request_id", "text"], "additionalProperties": False}},
    {"name": "video_analyze", "description": "模拟视频分析Connector，返回业务结果声明。",
     "inputSchema": {"type": "object", "properties": {"request_id": {"type": "string"}, "media_ref": {"type": "string"}},
                      "required": ["request_id", "media_ref"], "additionalProperties": False}},
]


def mcp_protocol(request: dict, headers) -> str:
    """声明的协议必须属于正式支持版本；在业务副作用之前拒绝冲突或未知版本。"""
    LOG.debug("核对模拟MCP协议: phase=entry")
    params = request.get("params") or {}
    if not isinstance(params, dict) or not isinstance(params.get("_meta", {}), dict):
        raise ValueError("MCP_PROTOCOL_METADATA_REQUIRED")
    header = headers.get("MCP-Protocol-Version")
    declared = (params.get("protocolVersion") if request.get("method") == "initialize"
                else params.get("_meta", {}).get("io.modelcontextprotocol/protocolVersion"))
    if header and declared and header != declared:
        raise ValueError("MCP_PROTOCOL_DECLARATIONS_CONFLICT")
    version = header or declared or MCP_PROTOCOL_VERSION
    if version not in {MCP_PROTOCOL_VERSION, MCP_HANDSHAKE_VERSION}:
        raise ValueError("MCP_PROTOCOL_UNSUPPORTED")
    LOG.debug("核对模拟MCP协议: phase=exit")
    return version


def business_tool(state: State, name: str, arguments: dict) -> dict:
    """显式声明的工具使用稳定请求身份幂等，不从Assistant正文解析控制动作。"""
    LOG.debug("调用模拟业务工具: phase=entry tool=%s", name)
    schema = next((tool["inputSchema"] for tool in TOOLS if tool["name"] == name), None)
    if not schema or set(arguments) - set(schema["properties"]) or any(not arguments.get(key) for key in schema["required"]):
        raise ValueError("DECLARED_TOOL_ARGUMENTS_REQUIRED")
    key = "tool:" + name + ":" + arguments["request_id"]
    if name == "media_transcribe":
        result = {"type": "transcription", "text": "模拟语音转写：我想预约明天的门诊。", "simulated": True}
    elif name == "media_synthesize":
        result = {"type": "media_reference", "media_ref": "fixture:tts:" + arguments["request_id"], "simulated": True}
    else:
        result = {"type": "video_analysis", "summary": "模拟视频分析完成", "simulated": True}
    result = state.business_result(key, arguments, lambda: (result, True))
    LOG.debug("调用模拟业务工具: phase=exit tool=%s", name)
    return result


def deliver(state: State, request: dict) -> dict:
    """业务端保留实际投递；人工暂停时不发送，确认最终Run后按delivery_id幂等。"""
    LOG.debug("模拟业务投递: phase=entry")
    if (not request.get("delivery_id") or request.get("run_status") != "completed"
            or request.get("phase") != "final" or not request.get("tenant_ref") or not request.get("session_id")):
        raise ValueError("CONFIRMED_FINAL_DELIVERY_REQUIRED")
    if "group_binding_id" in request:
        raise ValueError("GROUP_DELIVERY_REQUIRES_FORMAL_SENDER_QUALIFICATION")
    room_key = request["tenant_ref"] + ":" + request["room_ref"]
    key = "delivery:" + request["delivery_id"]

    def produce():
        """在业务写锁内复查暂停，模拟最终投递与幂等落盘处于同一事务。"""
        LOG.debug("检查模拟投递暂停: phase=entry")
        if state.get("pause:" + room_key, False):
            result, persist = {"status": "paused", "sent": False}, False
        else:
            result, persist = {"status": "delivered", "sent": True, "simulated": True}, True
        LOG.debug("检查模拟投递暂停: phase=exit")
        return result, persist

    result = state.business_result(key, request, produce)
    LOG.debug("模拟业务投递: phase=exit")
    return result


def create_server(port: int, state_path: Path, sender_application=None, *, business_authorizations=None,
                  confirmation_ttl_seconds=CONFIRMATION_TTL_SECONDS, business_clock=None) -> ThreadingHTTPServer:
    """只监听loopback；真实Runtime可把 /mcp 注册为已授权Connector。"""
    LOG.debug("创建模拟业务HTTP服务: phase=entry")
    workflow = None if business_authorizations is None else BusinessWorkflow(
        state_path, business_authorizations if isinstance(business_authorizations, BusinessAuthorizations)
        else BusinessAuthorizations(business_authorizations), confirmation_ttl_seconds, business_clock)

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def sender_request(self, method):
            """群发送新协议只通过本地令牌映射与正式SF权限；旧room模拟入口不获得群发送权。"""
            status, output = 200, None
            try:
                if sender_application is None:
                    raise SenderError("GROUP_SENDER_NOT_CONFIGURED", 503)
                origin = self.headers.get("Origin")
                if origin:
                    from urllib.parse import urlparse
                    if urlparse(origin).netloc != self.headers.get("Host"):
                        raise SenderError("SAME_ORIGIN_REQUIRED", 403)
                body = None
                if method == "POST":
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= MAX_REQUEST_BYTES or self.headers.get_content_type() != "application/json":
                        raise SenderError("BOUNDED_JSON_REQUIRED", 400)
                    body = json.loads(self.rfile.read(size))
                result = sender_application.request(method, self.path, self.headers.get("Authorization"), body, self.headers.get("Idempotency-Key"))
                output = {"code": "OK", "data": result}
            except (SenderError, ApiFailure) as failure:
                status, output = failure.status, {"code": failure.code, "data": None}
            except (ValueError, KeyError, TypeError):
                status, output = 400, {"code": "INVALID_SENDER_REQUEST", "data": None}
            except (RuntimeError, OSError, sqlite3.Error):
                status, output = 503, {"code": "SENDER_AUTHORITY_OR_OUTCOME_UNAVAILABLE", "data": None}
            raw = json.dumps(output, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, format, *args):
            """业务模拟日志只记录状态，不输出请求、患者标识或任意路径query。"""
            LOG.debug("模拟业务HTTP处理完成")

        def do_GET(self):
            """真实WebSocket演示业务累计消息合并；模拟服务只暴露loopback。"""
            LOG.debug("接收模拟WebSocket: phase=entry")
            if self.path.startswith("/api/v1/"):
                return self.sender_request("GET")
            if self.path != "/ws" or self.headers.get("Upgrade", "").lower() != "websocket":
                self.send_error(404)
                return
            try:
                accepted = accept_key(self.headers["Sec-WebSocket-Key"])
            except (ValueError, TypeError):
                self.send_error(400)
                return
            self.send_response(101)
            self.send_header("Upgrade", "websocket")
            self.send_header("Connection", "Upgrade")
            self.send_header("Sec-WebSocket-Accept", accepted)
            self.end_headers()
            self.close_connection = True
            state = State(state_path)
            try:
                while True:
                    opcode, payload = read_frame(self.rfile, True)
                    if opcode == 8:
                        send_frame(self.wfile, b"", False, opcode=8)
                        break
                    if opcode == 9:
                        send_frame(self.wfile, payload, False, opcode=10)
                    elif opcode == 1:
                        output = state.websocket_snapshot(json.loads(payload))
                        send_frame(self.wfile, json.dumps(output, ensure_ascii=False).encode(), False)
                    else:
                        raise ValueError("WEBSOCKET_OPCODE_UNSUPPORTED")
            except (EOFError, OSError, ValueError, KeyError, TypeError):
                LOG.debug("模拟WebSocket结束: incomplete_or_closed=true")
            finally:
                state.close()
                LOG.debug("接收模拟WebSocket: phase=exit")

        def do_POST(self):
            """处理固定业务及MCP路径；每请求持有自己的SQLite连接。"""
            LOG.debug("接收模拟业务请求: phase=entry")
            if self.path.startswith("/api/v1/"):
                return self.sender_request("POST")
            state = State(state_path)
            request = None
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 1024 * 1024:
                    raise ValueError("BOUNDED_JSON_BODY_REQUIRED")
                request = json.loads(self.rfile.read(length))
                if self.path == "/mcp":
                    method = request.get("method")
                    protocol = mcp_protocol(request, self.headers)
                    if method == "initialize":
                        result = {"protocolVersion": protocol, "capabilities": {"tools": {}},
                                  "serverInfo": {"name": "integration-business-fixture", "version": "1.0.0"}}
                    elif method == "notifications/initialized":
                        self.send_response(202)
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    elif method == "tools/list":
                        business_tools = workflow.tools(self.headers.get("Authorization")) if workflow is not None and self.headers.get("Authorization") else []
                        result = {"tools": TOOLS + business_tools}
                    elif method == "tools/call":
                        params = request["params"]
                        if params["name"] in BUSINESS_TOOL_NAMES:
                            if workflow is None:
                                raise BusinessError("BUSINESS_AUTHORIZATION_NOT_CONFIGURED", 403)
                            if protocol != MCP_PROTOCOL_VERSION:
                                raise BusinessError("BUSINESS_CONFIRMATION_REQUIRES_MODERN_MCP")
                            result = workflow.invoke(params["name"], params.get("arguments", {}),
                                                     self.headers.get("Authorization"), params.get("inputResponses"),
                                                     params.get("requestState"))
                        else:
                            output = business_tool(state, params["name"], params.get("arguments", {}))
                            result = {"content": [{"type": "text", "text": json.dumps(output, ensure_ascii=False)}],
                                      "structuredContent": output, "isError": False}
                            if protocol == MCP_PROTOCOL_VERSION:
                                result["resultType"] = "complete"
                    else:
                        raise ValueError("MCP_METHOD_UNSUPPORTED")
                    response = {"jsonrpc": "2.0", "id": request.get("id"), "result": result}
                elif self.path == "/delivery/pause":
                    if sender_application is not None:
                        raise ValueError("GROUP_SENDER_REQUIRES_AUTHENTICATED_CONTROL_PROTOCOL")
                    state.put("pause:" + request["tenant_ref"] + ":" + request["room_ref"], bool(request["paused"]))
                    response = {"status": "updated"}
                elif self.path == "/delivery/wecom":
                    if sender_application is not None:
                        raise ValueError("GROUP_SENDER_REQUIRES_FORMAL_QUALIFICATION")
                    response = deliver(state, request)
                else:
                    self.send_response(404)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                raw = json.dumps(response, ensure_ascii=False).encode()
                self.send_response(200)
            except BusinessError as failure:
                raw = json.dumps({"error": failure.code}).encode()
                self.send_response(failure.status)
            except (ValueError, KeyError, TypeError) as failure:
                raw = json.dumps({"error": type(failure).__name__}).encode()
                self.send_response(422)
            finally:
                state.close()
                LOG.debug("接收模拟业务请求: phase=exit")
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    LOG.debug("创建模拟业务HTTP服务: phase=exit")
    return server


def main():
    """独立Connector模拟入口；正式注册、授权与发布由SkillForge管理API负责。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--state", type=Path, default=Path(__file__).resolve().parent / ".state" / "business.sqlite3")
    parser.add_argument("--sender-config", type=Path, help="GI-04显式本地端点、环境凭据引用与操作者映射")
    parser.add_argument("--business-config", type=Path, help="GI-08门店Connector的环境凭据引用、固定服务操作者与患者范围")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    sender = SenderApplication.from_config(args.sender_config, args.state) if args.sender_config else None
    authority, ttl = load_business_config(args.business_config) if args.business_config else (None, CONFIRMATION_TTL_SECONDS)
    server = create_server(args.port, args.state, sender, business_authorizations=authority, confirmation_ttl_seconds=ttl)
    if authority:
        print(json.dumps({"event": "business_workflow_ready", "port": server.server_port,
                          "business_services_simulated": True}), flush=True)
    elif sender:
        print(json.dumps({"event": "group_sender_ready", "port": server.server_port, "business_services_simulated": True}), flush=True)
    else:
        print("模拟业务Connector已启动: http://127.0.0.1:" + str(server.server_port) + "/mcp", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
