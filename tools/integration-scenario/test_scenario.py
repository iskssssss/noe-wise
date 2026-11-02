"""验证业务接入的消费与幂等语义；真实SkillForge链路由scenario.py执行。"""
from __future__ import annotations

import io
import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from business_service import business_tool, create_server, deliver
from client import Client, Credential, ManagementCredential, read_sse
from state import State
from websocket_transport import roundtrip


class ScenarioConsumerTest(unittest.TestCase):
    def setUp(self):
        """每例使用独立接入数据库，重启测试复用同一临时文件。"""
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "consumer.sqlite3"
        self.state = State(self.path)
        self.pricing = {"revision": "customer-v1", "currency": "CNY",
                        "input_cents_per_thousand_tokens": "2", "output_cents_per_thousand_tokens": "3",
                        "tool_cents_per_call": "0.1"}
        self.accounts = {"7": {"wallet_account_id": "account-7", "currency": "CNY"}}
        self.state.configure_billing(self.pricing, self.accounts)

    def tearDown(self):
        """释放业务端连接和测试临时目录。"""
        self.state.close()
        self.directory.cleanup()

    def page(self, *events):
        """生成明确的模拟消费事件；这些事件不作为真实Runtime验收事实。"""
        return {"items": list(events), "next_cursor": str(events[-1]["cursor"]), "has_more": False}

    def usage(self, cursor, kind="measured", tokens=100, **kwargs):
        """固定同attempt，便于检验累计report和供应商成本修订的销售扣款边界。"""
        return {"cursor": str(cursor), "event_id": "7:" + str(cursor), "event_type": "llm.chat",
                "tenant_id": "7", "kind": kind, "prompt_tokens": tokens, "completion_tokens": 0,
                "usage_source": "PROVIDER_REPORTED", "revision": cursor, "attempt_id": "attempt", **kwargs}

    def test_usage_replay_restart_and_cost_changes_never_double_charge(self):
        """客户计价忽略供应商金额；去重和checkpoint在重启后仍成立。"""
        page = self.page(self.usage(1, amount_cents="900000"), self.usage(2, "confirmation", amount_cents="123"),
                         self.usage(3, "adjustment", amount_cents="-5", adjustment_of_event_id="tenant:2"))
        self.state.consume_usage("app", page)
        self.assertEqual({"7": "0.2"}, self.state.totals())
        self.state.close()
        self.state = State(self.path)
        self.state.configure_billing(self.pricing, self.accounts)
        self.state.consume_usage("app", page)
        self.assertEqual({"7": "0.2"}, self.state.totals())
        self.assertEqual("3", self.state.get("usage_cursor:app"))

    def test_unknown_then_cumulative_reports_charge_only_latest_attempt_total(self):
        """未知不计零元，report累积计量只补差；迟到旧量不会重复扣款。"""
        self.state.consume_usage("app", self.page(self.usage(1, "usage_report", None, completion_tokens=None)))
        self.assertEqual({}, self.state.totals())
        self.state.consume_usage("app", self.page(self.usage(2, "usage_report", 100), self.usage(3, "usage_report", 200),
                                                 self.usage(4, "measured", 200), self.usage(5, "usage_report", 100, revision=2)), expected_cursor="1")
        self.assertEqual({"7": "0.4"}, self.state.totals())

    def test_duplicate_event_in_page_rolls_back_every_fact(self):
        """异常页不能先扣费后停游标；全部事实以同一事务回滚。"""
        with self.assertRaises(ValueError):
            self.state.consume_usage("app", self.page(self.usage(1), self.usage(1)))
        self.assertEqual({}, self.state.totals())
        self.assertIsNone(self.state.get("usage_cursor:app"))

    def test_request_counter_has_no_model_attempt_or_token_charge(self):
        """真实增量流含请求计数；它独立于模型attempt，不能要求attempt或按token扣款。"""
        request = self.usage(1, tokens=0, event_type="request", attempt_id=None, invocation_id=None)
        self.state.consume_usage("app", self.page(request, self.usage(2)))
        self.assertEqual({"7": "0.2"}, self.state.totals())
        self.assertEqual("2", self.state.get("usage_cursor:app"))

    def test_immutable_event_cannot_change_on_replay(self):
        """同event_id内容变更会被拒绝，不重写已消费的客户账。"""
        self.state.consume_usage("app", self.page(self.usage(1)))
        with self.assertRaises(ValueError):
            self.state.consume_usage("app", self.page(self.usage(1, tokens=200)))
        self.assertEqual({"7": "0.2"}, self.state.totals())

    def test_opaque_cursors_and_cross_stream_deduplication(self):
        """授权流游标只原样保存；同一事实被另一授权流读到不能再扣客户钱包。"""
        first = self.page(self.usage(1)) | {"next_cursor": "signed:opaque/+=="}
        self.state.consume_usage("main", first)
        other = first | {"items": [first["items"][0] | {"cursor": "other-signed-cursor"}]}
        self.state.consume_usage("other", other)
        second = self.page(self.usage(2, tokens=200)) | {"next_cursor": "older-lexically"}
        self.state.consume_usage("main", second, expected_cursor="signed:opaque/+==")
        self.state.consume_usage("main", first)
        self.assertEqual("older-lexically", self.state.get("usage_cursor:main"))
        self.assertEqual({"7": "0.4"}, self.state.totals())
        with self.assertRaisesRegex(ValueError, "CHECKPOINT_CONFLICT"):
            self.state.consume_usage("main", self.page(self.usage(3)), expected_cursor="stale")

    def test_observer_preserves_usage_without_a_second_price_owner(self):
        """Demo拥有结算时，场景仅保存原事实和checkpoint；普通State拒绝计价消费。"""
        observer = State(Path(self.directory.name) / "observer.sqlite3")
        try:
            page = self.page(self.usage(1))
            observer.observe_usage("app", page)
            observer.observe_usage("app", page)
            self.assertEqual({}, observer.totals())
            self.assertIsNone(observer.billing)
            self.assertEqual(1, observer.db.execute("SELECT count(*) FROM usage").fetchone()[0])
            self.assertEqual("1", observer.get("usage_cursor:app"))
            self.assertEqual([], observer.db.execute("SELECT name FROM sqlite_master WHERE name LIKE 'settlement_%'").fetchall())
            with self.assertRaisesRegex(ValueError, "BILLING_CONFIGURATION_REQUIRED"):
                observer.consume_usage("app", page)
        finally:
            observer.close()

    def test_management_and_execution_credentials_cannot_cross_api_boundaries(self):
        """签名身份各有独立头，客户端在发出HTTP前拒绝跨控制平面使用。"""
        execution = Client("http://127.0.0.1:1", credential=Credential("execution", "secret"))
        management = Client("http://127.0.0.1:1", management_credential=ManagementCredential("management", "secret"))
        with self.assertRaisesRegex(ValueError, "EXECUTION_CREDENTIAL_REQUIRES"):
            execution.request("GET", "/platform/institutions")
        with self.assertRaisesRegex(ValueError, "MANAGEMENT_CREDENTIAL_REQUIRES"):
            management.request("GET", "/open-platform/sessions/events")
        headers = management.management_credential.headers("GET", "/api/v1/platform/institutions", b"")
        self.assertIn("X-Management-Key", headers)
        self.assertNotIn("X-Api-Key", headers)

    def test_message_cumulative_versions_and_waiting_are_not_completion(self):
        """新版覆盖累计正文；旧事件和重复事件不会拼接文本或改Run终态。"""
        for index, version, content in ((1, 1, "你"), (2, 2, "你好"), (3, 1, "你")):
            self.state.consume_event("session", {"event": "message.delta", "event_id": str(index),
                "data": {"session_id": "session", "event_id": str(index), "session_sequence": index,
                         "message_id": "message", "message_version": version, "content": content, "phase": "commentary"}})
        self.assertEqual("你好", self.state.messages("session")[0]["content"])
        self.state.consume_event("session", {"event": "execution.completed", "event_id": "4", "data": {
            "session_id": "session", "session_sequence": 4, "run_id": "run", "status": "waiting_hitl"}})
        self.assertEqual("waiting_hitl", self.state.get("run:session:run")["status"])
        self.assertEqual("4", self.state.get("event_cursor:session"))

    def test_media_and_manual_pause_preserve_idempotent_business(self):
        """媒体独立保留；不可信旧预约工具已移除，暂停时不投递，恢复后同id只发一次。"""
        args = {"request_id": "media-1", "media_ref": "fixture:voice"}
        result = business_tool(self.state, "media_transcribe", args)
        self.assertEqual(result, business_tool(self.state, "media_transcribe", args))
        with self.assertRaises(ValueError):
            business_tool(self.state, "appointment_create", {"tenant_ref": "forged"})
        request = {"delivery_id": "d", "tenant_ref": "t", "room_ref": "r", "session_id": "s",
                   "run_status": "completed", "phase": "final", "content": "预约已创建"}
        self.state.put("pause:t:r", True)
        self.assertFalse(deliver(self.state, request)["sent"])
        self.state.put("pause:t:r", False)
        self.assertEqual(deliver(self.state, request), deliver(self.state, request))
        with self.assertRaises(ValueError):
            deliver(self.state, request | {"run_status": "waiting_hitl"})

    def test_sse_multiline_and_comments(self):
        """SSE保留服务端id并正确忽略keepalive，跨行JSON可解析。"""
        response = io.BytesIO(b': ping\nid: out:1\nevent: message.delta\ndata: {"content":\ndata: "hello"}\n\n')
        self.assertEqual([{"event": "message.delta", "event_id": "out:1", "data": {"content": "hello"}}], list(read_sse(response)))

    def test_production_origin_is_rejected_before_http(self):
        """场景写操作只能针对显式loopback环境。"""
        with self.assertRaises(ValueError):
            Client("https://production.example")

    def test_business_connector_is_discoverable_over_real_http(self):
        """HTTP MCP初始化、发现和实际工具调用使用真实传输；并发请求持久幂等。"""
        server = create_server(0, self.path)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def invoke(method, params=None, protocol=None):
            """调用本地真实MCP HTTP，不把此模拟证据算作SkillForge Runtime执行。"""
            request_id = "mcp-consumer:" + method
            body = json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}}).encode()
            headers = {"Content-Type": "application/json"}
            if protocol:
                headers["MCP-Protocol-Version"] = protocol
            with urlopen(Request("http://127.0.0.1:" + str(server.server_port) + "/mcp", data=body,
                                 headers=headers), timeout=5) as response:
                packet = json.load(response)
                self.assertEqual("2.0", packet["jsonrpc"])
                self.assertEqual(request_id, packet["id"])
                return packet["result"]

        try:
            self.assertEqual("2026-07-28", invoke("initialize")["protocolVersion"])
            self.assertEqual(3, len(invoke("tools/list")["tools"]))
            params = {"name": "media_transcribe", "arguments": {"request_id": "stt-1", "media_ref": "fixture:voice"}}
            self.assertEqual(invoke("tools/call", params), invoke("tools/call", params))
            modern = invoke("tools/call", params | {"_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28"}}, "2026-07-28")
            self.assertEqual("complete", modern["resultType"])
            self.assertFalse(modern["isError"])
            self.assertTrue(modern["structuredContent"]["simulated"])
            self.assertEqual(modern["structuredContent"], json.loads(modern["content"][0]["text"]))
            self.assertEqual("2025-11-25", invoke("initialize", {"protocolVersion": "2025-11-25"})["protocolVersion"])
            handshake = invoke("tools/call", params, "2025-11-25")
            self.assertNotIn("resultType", handshake)
            self.assertEqual(modern["structuredContent"], handshake["structuredContent"])
            blocked = {"name": "media_synthesize", "arguments": {"request_id": "blocked-write", "text": "模拟消息"}}
            with self.assertRaises(HTTPError) as unsupported:
                invoke("tools/call", blocked, "2024-11-05")
            self.assertEqual(422, unsupported.exception.code)
            with self.assertRaises(HTTPError) as conflict:
                invoke("tools/call", blocked | {"_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28"}}, "2025-11-25")
            self.assertEqual(422, conflict.exception.code)
            self.assertIsNone(self.state.get("tool:media_synthesize:blocked-write"))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_business_websocket_uses_real_frames_and_separate_agent_lanes(self):
        """同房间不同Agent分别投影；真实mask文本帧中的旧版本不会覆盖累计正文。"""
        server = create_server(0, self.path)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        request = {"tenant_ref": "t", "room_ref": "room", "agent_ref": "assistant", "session_id": "s",
                   "event": {"event": "message.delta", "event_id": "1", "data": {"session_id": "s",
                             "message_id": "m", "message_version": 2, "content": "你好", "phase": "commentary"}}}
        try:
            self.assertEqual("你好", roundtrip(server.server_port, request)["message"]["content"])
            old = request | {"event": request["event"] | {"data": request["event"]["data"] | {"message_version": 1, "content": "你"}}}
            self.assertEqual("你好", roundtrip(server.server_port, old)["message"]["content"])
            other = old | {"agent_ref": "service"}
            self.assertEqual("你", roundtrip(server.server_port, other)["message"]["content"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
