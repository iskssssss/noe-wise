"""本地业务 Demo 的钱包事务、消费者重启、消息暂停和真实 HTTP 契约验证。"""
from __future__ import annotations

import json
import sys
import subprocess
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

from demo import DemoState, create_server
from business_service import deliver
from state import State
from scenario import Scenario
import sync as demo_sync
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "integration-wallet"))
from wallet_service import create_server as create_wallet_server


class IntegrationDemoTest(unittest.TestCase):
    def setUp(self):
        """独立钱包和Demo各持有自己的SQLite，真实loopback HTTP，秘密仅放环境。"""
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "demo.sqlite3"
        self.environment = patch.dict("os.environ", {"GI10_TEST_WALLET_TOKEN": "private-wallet-token"})
        self.environment.start()
        self.wallet_fault = None
        config = {"accounts": [{"wallet_account_id": "account-" + tenant, "execution_tenant_id": tenant,
                               "currency": "CNY", "initial_balance_cents": "10", "overdraft_limit_cents": "0",
                               "name": "机构 " + tenant} for tenant in ("7", "8")],
                  "callers": [{"caller_service_id": "demo", "token_env": "GI10_TEST_WALLET_TOKEN",
                               "allowed_account_ids": ["account-7", "account-8"]}]}
        self.wallet_server = create_wallet_server(0, Path(self.directory.name) / "wallet.sqlite3", config,
                                                 fault=lambda stage, result: self.wallet_fault(stage, result) if self.wallet_fault else None)
        self.wallet_thread = threading.Thread(target=self.wallet_server.serve_forever, daemon=True)
        self.wallet_thread.start()
        self.billing = {"wallet_origin": "http://127.0.0.1:" + str(self.wallet_server.server_port),
                        "wallet_token_env": "GI10_TEST_WALLET_TOKEN", "pricing": {
                            "revision": "customer-v1", "currency": "CNY", "input_cents_per_thousand_tokens": "2",
                            "output_cents_per_thousand_tokens": "3", "tool_cents_per_call": "0.1"},
                        "accounts": {tenant: {"wallet_account_id": "account-" + tenant, "currency": "CNY"}
                                     for tenant in ("7", "8")}}
        self.state = DemoState(self.path, self.billing)
        for tenant in ("7", "8"):
            self.state.register_wallet({"tenant_id": tenant,
                                        "name": "机构 " + tenant, "initial_balance_cents": "10"})

    def tearDown(self):
        """关闭连接和临时目录，不接触真实宿主数据库。"""
        self.state.close()
        self.wallet_server.shutdown()
        self.wallet_server.server_close()
        self.wallet_thread.join(timeout=5)
        self.environment.stop()
        self.directory.cleanup()

    def event(self, cursor, kind="measured", tokens=1000, tenant="7", **extra):
        """仅生成消费者测试事件，不将这些模拟事实标记为真实 Runtime 验收。"""
        return {"tenant_id": tenant, "cursor": str(cursor),
                "event_id": tenant + ":event-" + str(cursor), "attempt_id": "attempt-1", "kind": kind,
                "prompt_tokens": tokens, "completion_tokens": 0 if tokens is not None else None,
                "usage_source": "PROVIDER_REPORTED", "revision": cursor, "event_type": "llm.chat", **extra}

    def page(self, *events):
        """生成按发布序列排列的消费页，游标必须对应最后事件。"""
        return {"items": list(events), "next_cursor": events[-1]["cursor"], "has_more": False}

    def cli(self, *arguments):
        """实际独立CLI进程只访问本例独立钱包和Demo DB，不启动宿主或运行模型。"""
        config_path = Path(self.directory.name) / "billing.json"
        config_path.write_text(json.dumps(self.billing), encoding="utf-8")
        result = subprocess.run([sys.executable, "-B", str(Path(__file__).with_name("demo.py")),
            "--billing-config", str(config_path), "--state", str(self.path), *arguments],
            capture_output=True, text=True, encoding="utf-8", timeout=15, check=True)
        return json.loads(result.stdout)

    def test_cumulative_debit_restart_and_supplier_revisions(self):
        """累计报告补差、最终测量与成本修订不会重扣；消费者重启保留同一客户账。"""
        page = self.page(self.event(1, "usage_report", None), self.event(2, "usage_report", 500),
                         self.event(3, "usage_report", 1000), self.event(4, tokens=1000),
                         self.event(5, "confirmation", amount_cents="9000"),
                         self.event(6, "adjustment", amount_cents="-100"))
        self.state.consume("900", page)
        self.assertEqual("8", self.state.wallet("7")["balance_cents"])
        self.assertEqual("10", self.state.wallet("8")["balance_cents"])
        self.state.close()
        self.state = DemoState(self.path, self.billing)
        self.state.consume("900", page)
        snapshot = self.state.snapshot()
        self.assertEqual("2", snapshot["settlement"]["objects"][0]["target_amount"])
        self.assertEqual("2", snapshot["settlement"]["objects"][0]["confirmed_net_amount"])
        self.assertEqual("0", snapshot["settlement"]["objects"][0]["settlement_gap"])
        self.assertEqual(6, len(snapshot["usage_events"]))
        self.assertEqual("6", self.state.get("usage_cursor:900"))

    def test_admission_is_independent_and_idempotent(self):
        """客户钱包只准入有余额的当前机构；同请求重放读取既有决定，新的请求复查余额。"""
        request = {"tenant_id": "7", "request_id": "input-1"}
        first = self.state.admit(request)
        self.assertTrue(first["admitted"])
        self.state.consume("900", self.page(self.event(1, tokens=5000)))
        self.assertEqual(first, self.state.admit(request))
        self.assertFalse(self.state.admit(request | {"request_id": "input-2"})["admitted"])
        self.assertTrue(self.state.admit(request | {"tenant_id": "8", "request_id": "input-other"})["admitted"])
        long_request = request | {"tenant_id": "8", "request_id": "r" * 256}
        self.assertEqual(self.state.admit(long_request), self.state.admit(long_request))
        with self.assertRaises(ValueError):
            self.state.admit(request | {"request_id": "r" * 257})

    def test_sync_readonly_source_scoped_opaque_cursor_and_secret_exclusion(self):
        """同步器只读机构 checkpoint，经平台管理HMAC读当前目录，游标与秘密不进入钱包身份。"""
        source_path = Path(self.directory.name) / "scenario.sqlite3"
        source = State(source_path)
        source.put("step:tenant:main", {"state": "done", "data": {"tenant_id": "7"}})
        source.close()
        original_source = source_path.read_bytes()
        requests = []

        class UsageClient:
            def __init__(inner, origin, management_credential=None, platform_token=None):
                """仅替换宿主传输；Demo端使用短生命周期真实HTTP。"""
                self.assertEqual("http://127.0.0.1:8090", origin)
                self.assertEqual("private-key", management_credential.management_key)
                self.assertIsNone(platform_token)

            def request(inner, method, path, query):
                """当前目录和用量页按真实协议形态返回，测试不作为Runtime成功证据。"""
                requests.append((path, query.copy()))
                self.assertEqual("GET", method)
                if path == "/platform/institutions":
                    self.assertEqual({"tenant_id": "7", "page": 1, "page_size": 200}, query)
                    return {"items": [{"tenant_id": "7", "tenant_name": "机构 7"}]}
                self.assertEqual("/platform/usage/events", path)
                self.assertEqual("7", query["tenant_id"])
                if query["cursor"] is None:
                    return self.page(self.event(1)) | {"next_cursor": "opaque:tail", "has_more": True}
                self.assertEqual("opaque:tail", query["cursor"])
                return {"items": [], "next_cursor": "opaque:tail", "has_more": False}

        server = create_server(0, self.path, self.billing)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        origin = "http://127.0.0.1:" + str(server.server_port)
        config = {"billing": self.billing, "platform_origin": "http://127.0.0.1:8090"}
        try:
            with patch.dict("os.environ", {"SKILLFORGE_SCENARIO_MANAGEMENT_KEY": "private-key",
                                          "SKILLFORGE_SCENARIO_MANAGEMENT_SECRET": "private-signing"}), \
                    patch.object(demo_sync, "Client", UsageClient):
                self.assertEqual(1, demo_sync.sync(config, source_path, origin)["consumed_events"])
                self.assertEqual(0, demo_sync.sync(config, source_path, origin)["consumed_events"])
            self.assertEqual(original_source, source_path.read_bytes())
            self.assertEqual("opaque:tail", self.state.get("usage_cursor:platform:7"))
            self.assertEqual("8", self.state.wallet("7")["balance_cents"])
            snapshot = json.dumps(self.state.snapshot())
            self.assertNotIn("private-key", snapshot)
            persisted = " ".join(str(row) for row in self.state.db.execute("SELECT value FROM kv"))
            self.assertNotIn("private-signing", persisted)
            cursors = [query["cursor"] for path, query in requests if path == "/platform/usage/events"]
            self.assertEqual([None, "opaque:tail", "opaque:tail"], cursors)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_invalid_page_rolls_back_charge_and_checkpoint(self):
        """后续错误事件导致整页回滚，不能先扣款再丢失游标。"""
        with self.assertRaises(ValueError):
            self.state.consume("900", self.page(self.event(1), self.event(2, tenant="999")))
        self.assertEqual([], self.state.snapshot()["settlement"]["objects"])
        self.assertEqual([], self.state.snapshot()["settlement"]["instructions"])
        self.assertEqual([], self.state.snapshot()["usage_events"])
        self.assertIsNone(self.state.get("usage_cursor:900"))
        self.assertEqual("10", self.state.wallet("7")["balance_cents"])

    def scenario_usage(self, use_demo):
        """同一真实钱包传输覆盖Demo owner及无Demo本地owner，宿主只替换已导出的用量事实。"""
        scenario_state = State(Path(self.directory.name) / "scenario.sqlite3")
        server = None
        thread = None
        try:
            config = {"scenario_namespace": "gi10", "platform_origin": "http://127.0.0.1:8090",
                      "institution_origin": "http://127.0.0.1:8091", "billing": self.billing | {"accounts": {
                          "main": self.billing["accounts"]["7"], "8": self.billing["accounts"]["8"]}}}
            if use_demo:
                server = create_server(0, self.path, self.billing)
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                config["business_origin"] = "http://127.0.0.1:" + str(server.server_port)
            scenario_state.put("step:tenant:main", {"state": "done", "data": {"tenant_id": "7"}})
            scenario = Scenario(config, scenario_state)
            calls = []

            class UsageSource:
                def request(inner, method, path, query):
                    self.assertEqual("GET", method)
                    self.assertEqual("/platform/usage/events", path)
                    self.assertEqual("7", query["tenant_id"])
                    calls.append(query["cursor"])
                    return (self.page(self.event(1)) | {"next_cursor": "opaque:scenario"}
                            if query["cursor"] is None else {"items": [], "next_cursor": "opaque:scenario", "has_more": False})

            scenario.management_client = lambda: UsageSource()
            scenario.usage()
            scenario.usage()
            self.assertEqual([None, "opaque:scenario"], calls)
            self.assertEqual("8", self.state.wallet("7")["balance_cents"])
            self.assertEqual("opaque:scenario", scenario_state.get("usage_cursor:platform:7"))
            self.assertEqual(1, scenario_state.db.execute("SELECT count(*) FROM usage").fetchone()[0])
            status = scenario.billing_status()
            self.assertEqual("8", status["wallet_balances_cents"]["7"])
            self.assertEqual("2", status["customer_receivable_cents"]["7"])
            if use_demo:
                self.assertIsNone(scenario_state.billing)
                self.assertEqual({}, scenario_state.totals())
                self.assertEqual([], scenario_state.db.execute("SELECT name FROM sqlite_master WHERE name LIKE 'settlement_%'").fetchall())
                self.assertEqual(1, len(self.state.snapshot()["settlement"]["objects"]))
            else:
                self.assertIsNotNone(scenario_state.billing)
                self.assertEqual([], self.state.snapshot()["settlement"]["objects"])
        finally:
            scenario_state.close()
            if server is not None:
                server.shutdown()
                server.server_close()
                thread.join(timeout=5)

    def test_scenario_business_demo_is_only_settlement_owner(self):
        """有业务Demo时Scenario只观察事实，不建立第二套结算表。"""
        self.scenario_usage(True)

    def test_scenario_without_demo_uses_one_adapter_and_independent_wallet(self):
        """无Demo时Scenario显式配置一个Adapter，经独立钱包HTTP扣款。"""
        self.scenario_usage(False)

    def test_concurrent_duplicate_pages_charge_once(self):
        """两个独立连接并发消费同页，由写锁及既有去重共同保证一次客户扣款。"""
        page = self.page(self.event(1))

        def consume():
            """在工作线程拥有并释放自己的 SQLite 连接。"""
            state = DemoState(self.path, self.billing)
            try:
                return state.consume("900", page)
            finally:
                state.close()

        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [executor.submit(consume) for _ in range(2)]
            self.assertEqual(futures[0].result(timeout=10), futures[1].result(timeout=10))
        self.assertEqual("8", self.state.wallet("7")["balance_cents"])
        self.assertEqual(1, len(self.state.snapshot()["settlement"]["instructions"]))

    def test_pause_and_delivery_inbox_reuse_business_idempotency(self):
        """暂停不占投递身份；恢复后同 final 重放只保留一个收件箱回执。"""
        request = {"tenant_ref": "institution-a", "room_ref": "room-1", "delivery_id": "delivery-1",
                   "session_id": "session-1", "run_status": "completed", "phase": "final", "content": "最终结果"}
        self.state.put("pause:institution-a:room-1", True)
        self.assertFalse(deliver(self.state, request)["sent"])
        self.assertEqual([], self.state.snapshot()["inbox"])
        self.state.put("pause:institution-a:room-1", False)
        self.assertEqual(deliver(self.state, request), deliver(self.state, request))
        self.assertEqual(1, len(self.state.snapshot()["inbox"]))
        with self.assertRaises(ValueError):
            deliver(self.state, request | {"run_status": "waiting_hitl"})

    def test_directional_correction_refunds_confirmed_wallet_net(self):
        """输入输出按各自价计算；更新修订下调累计量，由同一对象连续指令退款。"""
        self.state.consume("900", self.page(self.event(1, tokens=1000, completion_tokens=1000)))
        self.assertEqual("5", self.state.wallet("7")["balance_cents"])
        self.state.consume("900", self.page(self.event(2, "usage_report", 500, completion_tokens=500)), expected_cursor="1")
        self.assertEqual("7.5", self.state.wallet("7")["balance_cents"])
        snapshot = self.state.snapshot()["settlement"]
        self.assertEqual("2.5", snapshot["objects"][0]["confirmed_net_amount"])
        self.assertEqual("0", snapshot["objects"][0]["settlement_gap"])
        self.assertEqual(["5", "2.5"], [item["payload"]["target_amount"] for item in snapshot["instructions"]])
        self.assertEqual("-2.5", snapshot["instructions"][1]["receipt"]["delta"])

    def test_wallet_commit_response_loss_recovers_original_instruction_after_restart(self):
        """钱包commit后回执不可交付保持UNKNOWN；重启显式dispatch只查询原身份，不二次扣款。"""
        def lose_first_response(stage, _result):
            if stage == "after_commit":
                self.wallet_fault = None
                raise RuntimeError("TEST_DROP_COMMITTED_RESPONSE")

        self.wallet_fault = lose_first_response
        self.state.consume("900", self.page(self.event(1)))
        first = self.state.snapshot()["settlement"]
        self.assertEqual("8", self.state.wallet("7")["balance_cents"])
        self.assertEqual("unknown", first["instructions"][0]["status"])
        self.assertEqual("0", first["objects"][0]["confirmed_net_amount"])
        self.assertEqual("2", first["objects"][0]["settlement_gap"])
        original_id = first["instructions"][0]["instruction_id"]
        self.state.close()
        self.state = DemoState(self.path, self.billing)
        self.assertEqual(1, self.cli("--dispatch-only")["applied"])
        settled = self.state.snapshot()["settlement"]
        self.assertEqual(original_id, settled["instructions"][0]["instruction_id"])
        self.assertEqual(1, len(settled["instructions"]))
        self.assertEqual("0", settled["objects"][0]["settlement_gap"])
        self.assertEqual("8", self.state.wallet("7")["balance_cents"])

    def test_rejected_receivable_requires_explicit_reconciliation_after_funds_return(self):
        """硬拒绝保留应收缺口且不自动重发；其它对象退款后，本地显式授权才重结算。"""
        self.state.consume("900", self.page(self.event(1, tokens=4000, attempt_id="first")))
        self.state.consume("900", self.page(self.event(2, tokens=2000, attempt_id="second")), expected_cursor="1")
        before = self.state.snapshot()["settlement"]
        rejected = next(item for item in before["instructions"] if item["status"] == "rejected")
        rejected_object = next(item for item in before["objects"] if item["object_id"] == rejected["object_id"])
        self.assertEqual("4", rejected_object["settlement_gap"])
        self.assertEqual("2", self.state.wallet("7")["balance_cents"])
        self.state.consume("900", self.page(self.event(3, tokens=0, attempt_id="first")), expected_cursor="2")
        self.assertEqual("10", self.state.wallet("7")["balance_cents"])
        self.assertEqual(0, self.state.dispatch()["attempted"])
        result = self.cli("--reconcile-object", rejected["object_id"], "--reconcile-rejection",
                          rejected["instruction_id"], "--authorization-ref", "operator:ticket-7")
        self.assertEqual(1, result["dispatch"]["applied"])
        self.assertEqual("6", self.state.wallet("7")["balance_cents"])
        replay = self.state.reconcile_rejected(rejected["object_id"], rejected["instruction_id"], "operator:ticket-7")
        self.assertEqual(result["instruction_id"], replay["instruction_id"])
        self.assertEqual(0, replay["dispatch"]["attempted"])
        self.assertEqual("6", self.state.wallet("7")["balance_cents"])

    def test_managed_http_reconciliation_auth_cas_idempotency_and_original_receipt(self):
        """当前独立管理token及CAS才允许显式重结算，同key异参409且原拒绝回执不可变。"""
        self.state.consume("900", self.page(self.event(1, tokens=4000, attempt_id="first")))
        self.state.consume("900", self.page(self.event(2, tokens=2000, attempt_id="second")), expected_cursor="1")
        original = self.state.snapshot()["settlement"]
        rejected = next(item for item in original["instructions"] if item["status"] == "rejected")
        obj = next(item for item in original["objects"] if item["object_id"] == rejected["object_id"])
        self.state.consume("900", self.page(self.event(3, tokens=0, attempt_id="first")), expected_cursor="2")
        payload = {"object_id": obj["object_id"], "expected_revision": obj["revision"],
                   "rejected_instruction_id": rejected["instruction_id"], "reason": "余额恢复，经人工核验后重结算"}
        managed_billing = self.billing | {"reconciliation_token_env": "GI10_TEST_MANAGEMENT_TOKEN"}
        server = create_server(0, self.path, managed_billing)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        target = "http://127.0.0.1:" + str(server.server_port) + "/api/v1/settlement-reconciliations"

        def post(body, key="managed-ticket-7", token=None):
            headers = {"Content-Type": "application/json", "Idempotency-Key": key}
            if token is not None:
                headers["Authorization"] = "Bearer " + token
            try:
                with urlopen(Request(target, data=json.dumps(body).encode(), headers=headers), timeout=5) as response:
                    return json.load(response)
            except HTTPError as failure:
                failure.close()
                raise

        try:
            with patch.dict("os.environ", {"GI10_TEST_MANAGEMENT_TOKEN": "private-management-token"}):
                snapshot_url = target.rsplit("/api/v1/", 1)[0] + "/api/snapshot"
                with urlopen(snapshot_url, timeout=5) as response:
                    self.assertTrue(json.load(response)["capabilities"]["settlement_reconciliation"])
                with patch.dict("os.environ", {"GI10_TEST_MANAGEMENT_TOKEN": "private-wallet-token"}):
                    with urlopen(snapshot_url, timeout=5) as response:
                        self.assertFalse(json.load(response)["capabilities"]["settlement_reconciliation"])
                for token in (None, "private-wallet-token", "wrong"):
                    with self.subTest(token=token), self.assertRaises(HTTPError) as denied:
                        post(payload, token=token)
                    self.assertEqual(403, denied.exception.code)
                with self.assertRaises(HTTPError) as stale:
                    post(payload | {"expected_revision": obj["revision"] - 1}, key="stale-ticket", token="private-management-token")
                self.assertEqual(409, stale.exception.code)
                first = post(payload, token="private-management-token")
                self.assertEqual(1, first["dispatch"]["applied"])
                replay = post(payload, token="private-management-token")
                self.assertEqual(first["instruction_id"], replay["instruction_id"])
                with self.assertRaises(HTTPError) as conflict:
                    post(payload | {"reason": "同键改写原因"}, token="private-management-token")
                self.assertEqual(409, conflict.exception.code)
                with patch.dict("os.environ", {"GI10_TEST_MANAGEMENT_TOKEN": "rotated-management-token"}):
                    with self.assertRaises(HTTPError) as rotated:
                        post(payload, token="private-management-token")
                    self.assertEqual(403, rotated.exception.code)
                    self.assertEqual(first["instruction_id"], post(payload, token="rotated-management-token")["instruction_id"])
                with urlopen(target.rsplit("/api/v1/", 1)[0] + "/api/snapshot", timeout=5) as response:
                    raw = response.read().decode()
                    self.assertNotIn("private-management-token", raw)
                    self.assertNotIn("GI10_TEST_MANAGEMENT_TOKEN", raw)
            after = self.state.snapshot()["settlement"]
            persisted = next(item for item in after["instructions"] if item["instruction_id"] == rejected["instruction_id"])
            self.assertEqual(rejected["receipt"], persisted["receipt"])
            self.assertEqual("rejected", persisted["status"])
            self.assertEqual("6", self.state.wallet("7")["balance_cents"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
    def test_frozen_customer_price_and_wallet_config(self):
        """现有 attempt 不随启动参数重计价，重复钱包配置不能冒充充值。"""
        self.state.consume("900", self.page(self.event(1)))
        self.state.close()
        changed = self.billing | {"pricing": self.billing["pricing"] | {"revision": "customer-v2",
                                  "input_cents_per_thousand_tokens": "3"}}
        self.state = DemoState(self.path, changed)
        self.state.consume("900", self.page(self.event(2, tokens=2000)), expected_cursor="1")
        self.assertEqual("6", self.state.wallet("7")["balance_cents"])
        obj = self.state.snapshot()["settlement"]["objects"][0]
        self.assertEqual("customer-v1", obj["pricing_revision"])
        with self.assertRaises(ValueError):
            self.state.register_wallet({"tenant_id": "7",
                                        "name": "机构 7", "initial_balance_cents": "20"})
        with self.assertRaises(ValueError):
            DemoState(Path(self.directory.name) / "wrong.sqlite3", self.billing | {"accounts": {
                "9": {"wallet_account_id": "account-7", "currency": "CNY"}}})

    def test_real_http_page_media_receipts_and_secret_exclusion(self):
        """短生命周期 HTTP 提供真实页面与 MCP 传输，安全展示不导出通用凭据 kv。"""
        self.state.put("step:tenant-credential:main", {"api_key": "must-not-display", "signing_secret": "must-not-display"})
        server = create_server(0, self.path, self.billing)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        origin = "http://127.0.0.1:" + str(server.server_port)
        try:
            with urlopen(origin, timeout=5) as response:
                self.assertIn("多机构业务", response.read().decode())
            payload = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {
                "name": "media_transcribe", "arguments": {"request_id": "stt-1", "media_ref": "fixture:audio"}}}
            for _ in range(2):
                with urlopen(Request(origin + "/mcp", data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json", "MCP-Protocol-Version": "2026-07-28"}), timeout=5) as response:
                    result = json.load(response)["result"]
                    self.assertEqual("complete", result["resultType"])
                    self.assertFalse(result["isError"])
                    self.assertTrue(result["structuredContent"]["simulated"])
                    self.assertEqual(result["structuredContent"], json.loads(result["content"][0]["text"]))
            consume = {"stream_key": "900", "page": self.page(self.event(1))}
            for _ in range(2):
                with urlopen(Request(origin + "/api/usage/consume", data=json.dumps(consume).encode(),
                                     headers={"Content-Type": "application/json"}), timeout=5) as response:
                    self.assertEqual("1", json.load(response)["next_cursor"])
            with urlopen(origin + "/api/snapshot", timeout=5) as response:
                raw = response.read().decode()
                self.assertNotIn("must-not-display", raw)
                self.assertNotIn("private-wallet-token", raw)
                self.assertNotIn("GI10_TEST_WALLET_TOKEN", raw)
                snapshot = json.loads(raw)
            self.assertEqual(1, len(snapshot["media_receipts"]))
            self.assertEqual("stt-1", snapshot["media_receipts"][0]["request_id"])
            self.assertEqual("8", snapshot["wallets"][0]["balance_cents"])
            with self.assertRaises(HTTPError) as unmanaged:
                urlopen(Request(origin + "/api/v1/settlement-reconciliations", data=b'{}', headers={
                    "Content-Type": "application/json", "Authorization": "Bearer anything", "Idempotency-Key": "unmanaged"}), timeout=5)
            self.assertEqual(403, unmanaged.exception.code)
            unmanaged.exception.close()
            with patch.dict("os.environ", {"GI10_TEST_WALLET_TOKEN": "revoked-wallet-token"}):
                with self.assertRaises(HTTPError) as unavailable:
                    urlopen(origin + "/api/snapshot", timeout=5)
                self.assertEqual(401, unavailable.exception.code)
                unavailable.exception.close()
            with self.assertRaises(HTTPError) as denied:
                urlopen(Request(origin + "/delivery/pause", data=b'{}', headers={
                    "Content-Type": "application/json", "Origin": "http://unrelated.example"}), timeout=5)
            self.assertEqual(403, denied.exception.code)
            denied.exception.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
