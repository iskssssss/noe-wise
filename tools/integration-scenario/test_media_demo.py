"""验证 Demo 消费者的真实证据门槛；模拟事件不作为 Runtime 验收结果。"""
from __future__ import annotations

import io
import hashlib
import json
import tempfile
import threading
import unittest
from unittest.mock import patch
from pathlib import Path

from business_client import BusinessClient
from business_service import create_server
from client import Client, Credential
from media_demo import MediaDemo, media_run_identity, require_media_execution
from scenario import Scenario
from state import State, TERMINAL


class MediaDemoConsumerTest(unittest.TestCase):
    def test_terminal_states_match_formal_runtime_status(self):
        """公开查询直接返回正式 RunStatus，terminated 必须结束而挂起/恢复必须保留。"""
        self.assertEqual({"completed", "terminated", "failed", "cancelled"}, TERMINAL)
        for status in ("queued", "running", "suspended", "resuming"):
            self.assertNotIn(status, TERMINAL)

    def test_failed_terminal_media_run_stops_polling_and_preserves_evidence(self):
        """替代宿主的失败终态立刻结束，只保存原 receipt/history，不等待180秒或再次执行。"""
        for terminal in ("terminated", "failed", "cancelled"):
            with self.subTest(terminal=terminal), tempfile.TemporaryDirectory() as directory:
                state = State(Path(directory) / "scenario.sqlite3")
                requests = []

                class ExecutionClient:
                    def request(self, method, path, body=None, **kwargs):
                        requests.append((method, path))
                        if path.endswith("/ensure"):
                            return {"end_user_id": "123"}
                        if path == "/open-platform/sessions":
                            return {"session_id": "session-1"}
                        if path.endswith("/messages"):
                            return {"run_id": "run-1", "input_id": "input-1"}
                        if path.endswith("/status"):
                            return {"status": terminal, "pending_interactions": []}
                        if path.endswith("/history"):
                            return {"messages": [], "processes": []}
                        raise AssertionError("UNEXPECTED_FIXTURE_REQUEST")

                try:
                    demo = object.__new__(MediaDemo)
                    demo.namespace, demo.attempt_ref = "test-namespace", "mcp-v3"
                    demo.tenant_id = "2"
                    demo.config, demo.state = {"run_timeout_seconds": 180}, state
                    demo.evidence = {"runs": []}
                    demo.tenant_client = lambda tenant: ExecutionClient()
                    demo.pull_events = lambda client, session, deadline=None: None
                    demo.usage = lambda: None
                    demo.business = lambda path, body=None: {"admitted": True} if path.endswith("/admit") else {"media_receipts": []}
                    state.put("step:deployment:media:assistant", {"state": "done", "data": {"agent_id": "3"}})
                    with patch("media_demo.time.sleep", side_effect=AssertionError("TERMINAL_MUST_NOT_SLEEP")):
                        with self.assertRaisesRegex(AssertionError, "MEDIA_RUN_AND_FINAL_MUST_COMPLETE"):
                            demo.conversation("media_transcribe")
                    self.assertEqual(1, sum(path.endswith("/status") for _, path in requests))
                    self.assertEqual(2, sum(path.endswith("/messages") for _, path in requests))
                    # 第二次messages是相同输入的明确幂等read-back，而不是新调用或终态重发。
                    run = demo.evidence["runs"][0]
                    self.assertEqual(terminal, run["status"]["status"])
                    self.assertEqual("mcp-v3", run["attempt_ref"])
                    self.assertEqual({"run_id": "run-1", "input_id": "input-1"}, run["receipt"])
                    self.assertEqual({"messages": [], "processes": []}, run["history"])
                    self.assertNotIn("execution_proof", run)
                    self.assertTrue((Path(directory) / "media-evidence.json").exists())
                finally:
                    state.close()

    def test_demo_budget_inherits_active_time_and_reserves_enough_model_iterations(self):
        """Demo 限制工具次数，活跃时间继承正式根预算，不再低于模型最小收尾超时。"""
        demo = object.__new__(MediaDemo)
        demo.config = {"model_config_id": "model-1"}
        self.assertEqual({"max_iterations": 8, "max_tool_calls": 1}, demo.definition()["execution_config"])

    def test_default_attempt_preserves_original_input_and_session_identity(self):
        """未配置尝试标识保持原重放键，不能由于预算修正隐式重发旧消息。"""
        identity = media_run_identity("namespace", "media_transcribe")
        self.assertIsNone(identity["attempt_ref"])
        self.assertEqual("media-" + hashlib.sha256(b"namespace:media_transcribe").hexdigest()[:32], identity["request_id"])
        self.assertEqual("media:message:media_transcribe", identity["message_step"])
        self.assertEqual("media:session:media_transcribe", identity["session_step"])
        self.assertEqual("media-demo:media_transcribe", identity["conversation_ref"])

    def test_explicit_attempt_gets_independent_deterministic_checkpoints_and_conversation(self):
        """显式新 ref 改变全部新输入身份，相同 ref 的消费者重启仍只重放相同输入。"""
        initial = media_run_identity("namespace", "media_transcribe")
        retry = media_run_identity("namespace", "media_transcribe", "budget-v2")
        self.assertEqual(retry, media_run_identity("namespace", "media_transcribe", "budget-v2"))
        self.assertEqual("budget-v2", retry["attempt_ref"])
        for key in ("request_id", "session_step", "message_step", "conversation_ref", "status_key"):
            self.assertNotEqual(initial[key], retry[key])
        self.assertEqual("media:message:media_transcribe:budget-v2", retry["message_step"])
        self.assertEqual("media-demo:media_transcribe:budget-v2", retry["conversation_ref"])
        self.assertNotEqual(retry["request_id"], media_run_identity("namespace", "video_analyze", "budget-v2")["request_id"])

    def test_attempt_ref_rejects_unbounded_and_unsafe_identifiers(self):
        """拒绝空值、中文、空白、分隔符和越界 ref，避免 checkpoint 身份歧义。"""
        for value in ("", "中文", "a b", "a:b", "../a", "a/b", "a\\b", "a" * 33, 7, False):
            with self.subTest(value=value), self.assertRaises(ValueError):
                media_run_identity("namespace", "media_transcribe", value)
        self.assertEqual("a" * 32, media_run_identity("namespace", "media_transcribe", "a" * 32)["attempt_ref"])

    def proof(self):
        """构造明确的替代证据，只用于消费门槛的正反例。"""
        arguments = {"request_id": "media-probe", "media_ref": "fixture:voice:sample"}
        receipt = {"run_id": "run-1", "input_id": "input-1"}
        event = {"event": "process.finished", "event_id": "out:1", "data": {
            "type": "tool", "run_id": "run-1", "process_id": "tool-1", "status": "succeeded",
            "runtime_event_id": "runtime:1", "finished_at": "2026-10-11T16:00:00+08:00"}}
        mock = {"name": "media_transcribe", "request_id": "media-probe", "arguments": arguments,
                "result": {"simulated": True, "text": "模拟转写"}, "simulated": True}
        return receipt, [event], {"media_receipts": [mock]}, arguments

    def test_exact_runtime_success_and_mock_receipt_required(self):
        """匹配同 Run 的成功公开过程及精确 tool 参数回执，才返回消费证据。"""
        receipt, events, snapshot, arguments = self.proof()
        result = require_media_execution(receipt, events, snapshot, "media_transcribe", arguments)
        self.assertTrue(result["runtime_tool_executed"])
        self.assertTrue(result["business_result_simulated"])

    def test_answer_text_or_direct_mock_call_cannot_prove_runtime_execution(self):
        """只有回执、回答文本或另一 Run 的事件都不能宣称工具经过实际 Runtime。"""
        receipt, events, snapshot, arguments = self.proof()
        for candidate in ([], [{"event": "message.completed", "data": {"content": "已转写"}}],
                          [events[0] | {"data": events[0]["data"] | {"run_id": "another-run"}}]):
            with self.subTest(candidate=candidate), self.assertRaises(AssertionError):
                require_media_execution(receipt, candidate, snapshot, "media_transcribe", arguments)

    def test_failed_or_unknown_process_and_changed_mock_arguments_are_rejected(self):
        """失败/未知不能由回答覆盖，模拟回执参数与最初请求也必须一致。"""
        receipt, events, snapshot, arguments = self.proof()
        for status in ("failed", "outcome_unknown", "running"):
            changed = events[0] | {"data": events[0]["data"] | {"status": status}}
            with self.subTest(status=status), self.assertRaises(AssertionError):
                require_media_execution(receipt, [events[0], changed], snapshot, "media_transcribe", arguments)
        snapshot["media_receipts"][0]["arguments"] = arguments | {"media_ref": "another-media"}
        with self.assertRaises(AssertionError):
            require_media_execution(receipt, events, snapshot, "media_transcribe", arguments)

    def test_member_publication_headers_do_not_mix_authentication(self):
        """机构管理令牌与 CAS 修订被真实 Request 编码；不能混入治理身份。"""
        requests = []

        class Response(io.BytesIO):
            status = 200

        class Opener:
            def open(self, request, timeout):
                requests.append(request)
                return Response(json.dumps({"code": "OK", "data": {"status": "PUBLISHED"}}).encode())

        client = Client("http://127.0.0.1:8000", institution_token="fixture-institution-token")
        client.opener = Opener()
        client.request("POST", "/capabilities/definitions/versions/publish", {"version_id": "1"}, if_match=7)
        headers = {key.lower(): value for key, value in requests[0].header_items()}
        self.assertEqual("Bearer fixture-institution-token", headers["authorization"])
        self.assertEqual("7", headers["if-match"])
        self.assertNotIn("x-platform-token", headers)
        self.assertNotIn("x-api-key", headers)
        with self.assertRaises(ValueError):
            Client("http://127.0.0.1:8000", Credential("key", "secret"), institution_token="token")
        with self.assertRaises(ValueError):
            client.request("POST", "/publish", {}, if_match=0)

    def test_media_consumer_publishes_mcp_write_without_runtime_replay_claim(self):
        """消费端实际发布请求保留幂等业务参数，但 TTS 不声明 MCP 崩溃重放。"""
        requests = []

        class Member:
            def request(self, method, path, body=None, **kwargs):
                requests.append((method, path, body, kwargs))
                if path.endswith("/definitions"):
                    return {"id": "1"}
                if path.endswith("/versions"):
                    return {"id": "2"}
                if path.endswith("/validate"):
                    return {"valid": True, "definition_revision": 3}
                if path.endswith("/publish"):
                    return {"id": "2", "status": "PUBLISHED"}
                if path.endswith("/detail"):
                    return {"deployment_key": kwargs["query"]["deploymentKey"], "provider_ref": "connector-1",
                            "status": "ACTIVE", "tool_version_id": "2", "revision": 1}
                return {"id": "4"}

        demo = object.__new__(MediaDemo)
        demo.namespace, demo.member = "generic-test", Member()
        demo.once = lambda key, operation: operation()
        discovered = {"description": "模拟语音合成", "input_schema": {"type": "object", "properties": {
            "request_id": {"type": "string"}, "text": {"type": "string"}}, "required": ["request_id", "text"]}}
        demo.publish_tool("media_synthesize", discovered, "connector-1")
        publication = next(request[2]["payload"] for request in requests if request[1].endswith("/versions"))
        self.assertEqual("NON_IDEMPOTENT_WRITE", publication["descriptor"]["effect"])
        self.assertFalse(publication["descriptor"]["idempotent"])
        self.assertEqual(discovered["input_schema"], publication["descriptor"]["input_schema"])
        deployment = next(request[2] for request in requests if request[1].endswith("/deployments"))
        self.assertEqual("PRODUCTION", deployment["environment"])
        self.assertEqual("MCP", deployment["provider_type"])
        self.assertEqual("2", deployment["tool_version_id"])

    def test_media_mount_normalizes_exact_integer_catalog_ids_to_decimal_strings(self):
        """Python大整数读数无损，绑定面仍显式发十进制字符串，满足正式严格ID协议。"""
        definition_id,version_id=9007199254740993123,9007199254740993456

        class Catalog:
            def request(self,method,path,body=None,**kwargs):
                """只替代能力目录传输，以大于JS精度上限的真实整数形态检验消费者映射。"""
                if path.endswith("/definitions"):
                    return {"id":definition_id}
                if path.endswith("/versions"):
                    return {"id":version_id}
                if path.endswith("/validate"):
                    return {"valid":True,"definition_revision":3}
                if path.endswith("/publish"):
                    return {"id":version_id,"status":"PUBLISHED"}
                if path.endswith("/detail"):
                    return {"deployment_key":kwargs["query"]["deploymentKey"],"provider_ref":"connector",
                            "status":"ACTIVE","tool_version_id":version_id,"revision":7}
                return {}

        demo=object.__new__(MediaDemo)
        demo.namespace,demo.member="decimal-mount-test",Catalog()
        demo.once=lambda key,operation:operation()
        mount,_=demo.publish_tool("media_transcribe",{"description":"测试目录","input_schema":{"type":"object"}},"connector")
        self.assertEqual(str(definition_id),mount["capability_definition_id"])
        self.assertEqual(str(version_id),mount["capability_version_id"])
        self.assertEqual(7,mount["tool_config"]["tool_deployment_revision"])

    def test_business_origin_has_no_remote_or_implicit_ws_target(self):
        """在任何模型或创建操作前拒绝不受支持的业务 HTTP/WS 地址。"""
        for origin in ("https://production.example", "https://127.0.0.1:8098", "http://127.0.0.1", "http://[::1]:8098"):
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                BusinessClient(origin)

    def test_core_run_delivers_to_configured_external_mock_service(self):
        """可选外部服务收到真实 WS 和投递，不再落入另一个短生命周期数据库。"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            server = create_server(0, path / "external.sqlite3")
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            state = State(path / "scenario.sqlite3")
            try:
                config = {"scenario_namespace": "generic-test", "platform_origin": "http://127.0.0.1:8100",
                    "institution_origin": "http://127.0.0.1:8000",
                    "business_origin": "http://127.0.0.1:" + str(server.server_port)}
                scenario = Scenario(config, state)
                final = {"session_id": "s", "message_id": "m", "message_version": 1, "session_sequence": 1,
                         "content": "这是模拟消息联调结果。", "phase": "final", "state": "completed", "run_id": "r"}
                state.consume_event("s", {"event": "message.completed", "event_id": "out:1", "data": final})
                scenario.verify_business_outputs("institution-a", "assistant", "s", "delivery-1", final)
                external = State(path / "external.sqlite3")
                try:
                    self.assertEqual(final["content"], external.get("delivery:delivery-1")["request"]["content"])
                    self.assertFalse(external.get("pause:institution-a:room-1"))
                    self.assertEqual(final, external.get("ws:institution-a:room-1:assistant:s:m"))
                    self.assertIsNone(external.get("delivery:delivery-1:pause-probe"))
                finally:
                    external.close()
                self.assertFalse((path / "business.sqlite3").exists())
            finally:
                state.close()
                server.shutdown()
                server.server_close()
                thread.join(timeout=5)

    def test_core_wallet_registration_matches_real_association_and_stable_admission(self):
        """准入使用真实关联的机构名称，避免与用量同步器登记产生幂等异参冲突。"""
        with tempfile.TemporaryDirectory() as directory:
            state = State(Path(directory) / "scenario.sqlite3")
            try:
                billing = {"pricing": {"revision": "v1", "currency": "CNY",
                    "input_cents_per_thousand_tokens": "1", "output_cents_per_thousand_tokens": "2", "tool_cents_per_call": "0.1"},
                    "accounts": {"institution-a": {"wallet_account_id": "account-456", "currency": "CNY"}}}
                scenario = Scenario({"scenario_namespace": "generic-test", "platform_origin": "http://127.0.0.1:8100",
                    "institution_origin": "http://127.0.0.1:8000", "billing": billing}, state)
                state.put("step:tenant:institution-a", {"state": "done", "data": {"tenant_id": "456", "tenant_name": "真实演示机构"}})
                requests = []

                class Wallet:
                    def request(self, path, body=None):
                        if path == "/api/snapshot":
                            return {"billing": billing | {"accounts": {"456": billing["accounts"]["institution-a"]}}}
                        requests.append((path, body))
                        return {"admitted": False} if path.endswith("admit") else {}

                scenario.business_client = Wallet()
                with self.assertRaisesRegex(RuntimeError, "INSUFFICIENT_BALANCE"):
                    scenario.admit("institution-a", "stable-request-1")
                self.assertEqual("真实演示机构", requests[0][1]["name"])
                self.assertEqual({"tenant_id": "456", "request_id": "stable-request-1"}, requests[1][1])
            finally:
                state.close()


if __name__ == "__main__":
    unittest.main()
