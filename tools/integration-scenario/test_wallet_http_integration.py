"""独立钱包与适配器进程的真实HTTP、提交边界、丢回执和分别重启验证。"""
from __future__ import annotations

import concurrent.futures
import json
import os
import queue
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid
from contextlib import closing
from pathlib import Path

from settlement import SettlementAdapter
from settlement_contract import (WalletClient, WalletFailure, instruction_identity,
                                 object_identity)
from state import State

HERE = Path(__file__).resolve().parent
WALLET = HERE.parent / "integration-wallet"
PRICE = {"revision": "sales-v1", "currency": "CNY", "input_cents_per_thousand_tokens": "100",
         "output_cents_per_thousand_tokens": "200", "tool_cents_per_call": "5"}
BINDINGS = {"7": {"wallet_account_id": "rich", "currency": "CNY"},
            "8": {"wallet_account_id": "limited", "currency": "CNY"}}

WALLET_RUNNER = '''import json,os,sys
from pathlib import Path
sys.path.insert(0,sys.argv[1])
from wallet_service import create_server
config=json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
fault_path=Path(sys.argv[4])
def fault(stage,result):
 if fault_path.exists() and fault_path.read_text(encoding="utf-8")==stage:
  fault_path.unlink()
  os._exit(77)
server=create_server(0,Path(sys.argv[3]),config,fault=fault)
print(json.dumps({"port":server.server_port}),flush=True)
try:server.serve_forever()
finally:server.server_close()
'''

ADAPTER_RUNNER = '''import json,os,sys,time
from pathlib import Path
sys.path.insert(0,sys.argv[1])
from state import State
from settlement import SettlementAdapter
from settlement_contract import WalletClient
arg=json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
state=State(Path(arg["state"]))
adapter=SettlementAdapter(state.db,arg["pricing"],arg["accounts"],clock=lambda:time.time()+arg.get("clock_offset",0))
def fault(stage):
 if stage==arg.get("fault"):os._exit(77)
try:
 if arg["operation"]=="consume":
  result=adapter.consume_usage(arg["stream_key"],arg["page"],arg.get("expected_cursor"),fault=fault)
 else:
  client=WalletClient(arg["wallet_origin"],os.environ["GI10_TEST_WALLET_TOKEN"],timeout_seconds=2)
  result=adapter.dispatch(client,worker_id=arg["worker"],fault=fault)
 print(json.dumps(result),flush=True)
finally:state.close()
'''


def event(number, amount=1000, *, tenant="7", attempt="actual-attempt", revision=1, **extra):
    return {"event_id": tenant + ":fact-" + str(number), "cursor": "opaque:" + str(number),
            "tenant_id": tenant, "event_type": "llm.chat", "kind": "usage_report", "revision": revision,
            "attempt_id": attempt, "prompt_tokens": amount, "completion_tokens": 0,
            "usage_source": "PROVIDER_REPORTED", **extra}


def page(*items, cursor=None):
    return {"items": list(items), "next_cursor": cursor or items[-1]["cursor"], "has_more": False}


class WalletHttpProcessIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="gi10-http-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.adapter_path = self.root / "adapter.sqlite3"
        self.wallet_path = self.root / "wallet.sqlite3"
        self.fault_path = self.root / "wallet-fault"
        self.wallet_runner = self.root / "wallet_runner.py"
        self.wallet_runner.write_text(WALLET_RUNNER, encoding="utf-8")
        self.adapter_runner = self.root / "adapter_runner.py"
        self.adapter_runner.write_text(ADAPTER_RUNNER, encoding="utf-8")
        self.config_path = self.root / "wallet.json"
        self.token = uuid.uuid4().hex
        self.environment = dict(os.environ, GI10_TEST_WALLET_TOKEN=self.token, PYTHONDONTWRITEBYTECODE="1")
        config = {"accounts": [
            {"wallet_account_id": "rich", "execution_tenant_id": "7", "currency": "CNY", "name": "机构甲",
             "initial_balance_cents": "1000", "overdraft_limit_cents": "0"},
            {"wallet_account_id": "limited", "execution_tenant_id": "8", "currency": "CNY", "name": "机构乙",
             "initial_balance_cents": "90", "overdraft_limit_cents": "0"}],
            "callers": [{"caller_service_id": "integration-adapter", "token_env": "GI10_TEST_WALLET_TOKEN",
                         "allowed_account_ids": ["rich", "limited"]}]}
        self.config_path.write_text(json.dumps(config, ensure_ascii=False), encoding="utf-8")
        self.process = None
        self.addCleanup(self.stop_wallet)
        self.start_wallet()

    def start_wallet(self):
        """由实际子进程原子绑定端口0并回报readiness，不猜测可用端口。"""
        log = open(self.root / ("wallet-" + uuid.uuid4().hex + ".log"), "wb")
        self.addCleanup(log.close)
        self.process = subprocess.Popen([sys.executable, "-u", str(self.wallet_runner), str(WALLET),
            str(self.config_path), str(self.wallet_path), str(self.fault_path)], env=self.environment,
            stdout=subprocess.PIPE, stderr=log, text=True, encoding="utf-8")
        lines = queue.Queue()
        reader = threading.Thread(target=lambda: lines.put(self.process.stdout.readline()), daemon=True)
        reader.start()
        try:
            ready = json.loads(lines.get(timeout=15))
        except (queue.Empty, json.JSONDecodeError) as failure:
            self.stop_wallet()
            raise AssertionError("独立钱包未返回明确readiness") from failure
        reader.join(timeout=2)
        self.assertFalse(reader.is_alive())
        self.origin = "http://127.0.0.1:" + str(ready["port"])
        self.client = WalletClient(self.origin, self.token, timeout_seconds=2)

    def stop_wallet(self):
        if self.process is not None:
            if self.process.poll() is None:
                self.process.terminate()
            self.process.wait(timeout=10)
            self.process.stdout.close()
            self.process = None

    def run_adapter(self, operation, *, fault=None, clock_offset=0, price=None, **args):
        command = {"state": str(self.adapter_path), "pricing": price or PRICE, "accounts": BINDINGS,
                   "operation": operation, "wallet_origin": self.origin, "worker": uuid.uuid4().hex,
                   "clock_offset": clock_offset, "fault": fault, **args}
        path = self.root / ("adapter-" + uuid.uuid4().hex + ".json")
        path.write_text(json.dumps(command), encoding="utf-8")
        result = subprocess.run([sys.executable, "-u", str(self.adapter_runner), str(HERE), str(path)],
                                env=self.environment, capture_output=True, text=True, encoding="utf-8", timeout=20)
        if fault is not None:
            self.assertEqual(77, result.returncode, result.stderr)
            return None
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)

    def snapshot(self):
        state = State(self.adapter_path)
        try:
            return SettlementAdapter(state.db, PRICE, BINDINGS).snapshot()
        finally:
            state.close()

    def balance(self, account="rich"):
        return self.client.request("GET", "/api/v1/accounts/detail",
                                   query={"wallet_account_id": account, "currency": "CNY"})["balance_cents"]

    def wallet_count(self, table):
        self.assertIn(table, {"wallet_instruction_receipts", "wallet_ledger"})
        with closing(sqlite3.connect(self.wallet_path)) as db:
            return db.execute("SELECT count(*) FROM " + table).fetchone()[0]

    def test_real_http_prices_revisions_cross_views_and_frozen_rates(self):
        first = page(event(1))
        self.run_adapter("consume", stream_key="platform", page=first)
        # 不同授权视图游标不同，稳定原事实只能产生一份应收与一条首指令。
        other = page(first["items"][0] | {"cursor": "headquarters:opaque"}, cursor="hq:next")
        self.run_adapter("consume", stream_key="headquarters", page=other)
        self.assertEqual(1, len(self.snapshot()["instructions"]))
        self.run_adapter("dispatch")
        self.assertEqual("900", self.balance())
        first_instruction = self.snapshot()["instructions"][0]["instruction_id"]
        self.run_adapter("consume", stream_key="platform", page=page(event(2, 800, revision=2)),
                         expected_cursor="opaque:1", price=PRICE | {"revision": "sales-v2", "input_cents_per_thousand_tokens": "999"})
        self.run_adapter("dispatch")
        self.assertEqual("920", self.balance())
        snapshot = self.snapshot()
        self.assertEqual("sales-v1", snapshot["objects"][0]["pricing_revision"])
        self.assertEqual("80", snapshot["objects"][0]["confirmed_net_amount"])
        self.assertEqual("-20", snapshot["instructions"][1]["receipt"]["delta"])
        self.assertEqual(first_instruction, snapshot["instructions"][1]["payload"]["previous_instruction_id"])
        # 原回执仍是原入账结果；当前余额变动不重写过去回执。
        self.assertEqual("100", self.client.lookup(first_instruction)["delta"])

    def test_adapter_crash_before_and_after_commit_recovers_original_instruction(self):
        first = page(event(1))
        self.run_adapter("consume", stream_key="stream", page=first, fault="before_commit")
        self.assertEqual(0, self.snapshot()["consumed_events"])
        self.assertEqual([], self.snapshot()["instructions"])
        self.assertEqual("1000", self.balance())
        self.run_adapter("consume", stream_key="stream", page=first, fault="after_commit_before_send")
        committed = self.snapshot()
        self.assertEqual(1, committed["consumed_events"])
        self.assertEqual("opaque:1", committed["cursors"]["stream"])
        identity = committed["instructions"][0]["instruction_id"]
        self.assertEqual(0, self.wallet_count("wallet_ledger"))
        self.run_adapter("dispatch")
        self.assertEqual(identity, self.snapshot()["instructions"][0]["instruction_id"])
        self.assertEqual("900", self.balance())
        self.run_adapter("consume", stream_key="stream", page=first)
        self.run_adapter("dispatch")
        self.assertEqual(1, self.wallet_count("wallet_ledger"))

    def test_wallet_crash_before_commit_and_lost_committed_response(self):
        for stage in ("before_commit", "after_commit"):
            with self.subTest(stage=stage):
                number = 1 if stage == "before_commit" else 2
                self.run_adapter("consume", stream_key=stage, page=page(event(number, attempt="attempt-" + stage)))
                self.fault_path.write_text(stage, encoding="utf-8")
                result = self.run_adapter("dispatch")
                self.assertGreaterEqual(result["unknown"], 1)
                self.process.wait(timeout=10)
                self.assertEqual(77, self.process.returncode)
                self.stop_wallet()
                before = self.snapshot()
                instruction_ids = [row["instruction_id"] for row in before["instructions"]]
                self.start_wallet()
                self.run_adapter("dispatch")
                self.assertEqual(instruction_ids, [row["instruction_id"] for row in self.snapshot()["instructions"]])
                self.assertTrue(all(row["status"] == "applied" for row in self.snapshot()["instructions"]))
        self.assertEqual("800", self.balance())
        self.assertEqual(2, self.wallet_count("wallet_ledger"))
        self.assertEqual(2, self.wallet_count("wallet_instruction_receipts"))

    def test_adapter_lost_ack_lease_expiry_and_two_workers_do_not_double_debit(self):
        self.run_adapter("consume", stream_key="stream", page=page(event(1), event(2, 800, revision=2)))
        self.run_adapter("dispatch", fault="after_http_before_ack")
        self.assertEqual("900", self.balance())
        blocked = self.run_adapter("dispatch")
        self.assertEqual(0, blocked["attempted"])
        self.assertEqual("pending", self.snapshot()["instructions"][1]["status"])
        # 注入下一组件的受控时钟，只证明原租约到期；不改写持久账本或固定sleep。
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(lambda _: self.run_adapter("dispatch", clock_offset=31), range(2)))
        self.assertEqual("920", self.balance())
        self.assertEqual(2, self.wallet_count("wallet_ledger"))
        self.assertTrue(all(row["status"] == "applied" for row in self.snapshot()["instructions"]))
        self.assertLessEqual(sum(row["applied"] for row in results), 2)

    def test_rejected_debit_does_not_become_refund_and_protocol_rejects_identity_changes(self):
        self.run_adapter("consume", stream_key="limited", page=page(event(1, tenant="8", attempt="limited-attempt")))
        self.run_adapter("dispatch")
        self.assertEqual("90", self.balance("limited"))
        rejected = self.snapshot()["instructions"][0]
        self.assertEqual("rejected", rejected["status"])
        self.run_adapter("consume", stream_key="limited", page=page(event(2, 800, tenant="8", attempt="limited-attempt", revision=2)),
                         expected_cursor="opaque:1")
        self.run_adapter("dispatch")
        second = self.snapshot()["instructions"][1]
        self.assertEqual("80", second["receipt"]["delta"])
        self.assertEqual("10", self.balance("limited"))
        self.assertEqual("rejected", self.client.lookup(rejected["instruction_id"])["outcome"])
        with self.assertRaises(WalletFailure) as conflict:
            self.client.settle(second["payload"] | {"target_amount": "70"})
        self.assertEqual(409, conflict.exception.status)
        with self.assertRaises(WalletFailure) as foreign:
            self.client.settle(second["payload"] | {"instruction_id": "foreign", "execution_tenant_id": "7"})
        self.assertEqual(403, foreign.exception.status)

    def test_wallet_waits_for_predecessor_and_concurrent_limits_hold(self):
        obj = object_identity("8", "limited", "CNY", "attempt:ordered")
        first_id = instruction_identity(obj, 1)
        second_id = instruction_identity(obj, 2)
        first = {"instruction_id": first_id, "object_id": obj, "execution_tenant_id": "8", "wallet_account_id": "limited",
                 "currency": "CNY", "metering_object_ref": "attempt:ordered", "sequence": 1,
                 "previous_instruction_id": None, "target_amount": "100", "pricing_revision": "sales-v1"}
        second = first | {"instruction_id": second_id, "sequence": 2, "previous_instruction_id": first_id, "target_amount": "80"}
        self.assertEqual("waiting_predecessor", self.client.settle(second)["outcome"])
        self.assertEqual("waiting_predecessor", self.client.lookup(second_id)["outcome"])
        self.assertEqual("rejected", self.client.settle(first)["outcome"])
        self.assertEqual("80", self.client.settle(second)["delta"])
        self.assertEqual("10", self.balance("limited"))
        # 同账户不同对象同时扣款，事务余额下限不因并发准入或读取旧余额而被突破。
        requests = []
        for index in range(2):
            key = object_identity("8", "limited", "CNY", "attempt:parallel-" + str(index))
            requests.append(first | {"instruction_id": instruction_identity(key, 1), "object_id": key,
                                     "metering_object_ref": "attempt:parallel-" + str(index), "target_amount": "8"})
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            receipts = list(executor.map(self.client.settle, requests))
        self.assertEqual(["applied", "rejected"], sorted(row["outcome"] for row in receipts))
        self.assertEqual("2", self.balance("limited"))

    def test_actual_runtime_export_settles_once_across_restart_and_authorized_views(self):
        """显式传入本轮真实HTTP报告；没有该事实源时不以手工事件替代Runtime验收。"""
        report_path = os.environ.get("GI10_ACTUAL_RUNTIME_USAGE_REPORT")
        if not report_path:
            self.skipTest("需显式提供本轮BusinessWorkflowHttpIntegrationTest的真实报告")
        report = json.loads(Path(report_path).read_text(encoding="utf-8"))
        self.assertEqual("PASS", report["state"])
        # 正式交付要求解释也沿父Run计量：6次模型（含11/7 Token辅助解释）和5次工具。
        self.assertEqual(11, report["usage_summary"]["invocation_count"])
        self.assertEqual(66, int(report["usage_summary"]["prompt_tokens"]))
        self.assertEqual(42, int(report["usage_summary"]["completion_tokens"]))
        tenant = report["tenant_id"]
        self.stop_wallet()
        config = json.loads(self.config_path.read_text(encoding="utf-8"))
        config["accounts"].append({"wallet_account_id": "actual-runtime", "execution_tenant_id": tenant,
            "currency": "CNY", "name": "真实Runtime用量消费者", "initial_balance_cents": "10000", "overdraft_limit_cents": "0"})
        config["callers"][0]["allowed_account_ids"].append("actual-runtime")
        self.config_path.write_text(json.dumps(config, ensure_ascii=False), encoding="utf-8")
        self.start_wallet()
        binding = {tenant: {"wallet_account_id": "actual-runtime", "currency": "CNY"}}
        original = report["usage"]
        self.run_adapter("consume", stream_key="actual-execution", page=original, accounts=binding)
        self.run_adapter("dispatch", accounts=binding)
        # 总部与执行机构是同一批真实源事实，只改变读取授权与游标。
        self.run_adapter("consume", stream_key="actual-headquarters", page=report["headquarters_usage"], accounts=binding)
        self.stop_wallet()
        self.start_wallet()
        self.run_adapter("dispatch", accounts=binding)
        snapshot = self.snapshot()
        self.assertEqual(len(original["items"]), snapshot["consumed_events"])
        self.assertEqual(11, len(snapshot["objects"]))
        self.assertTrue(all(row["status"] == "applied" for row in snapshot["instructions"]))
        self.assertEqual("9960", self.balance("actual-runtime"))
        self.assertEqual(11, self.wallet_count("wallet_ledger"))
        self.assertEqual(11, self.wallet_count("wallet_instruction_receipts"))
        print("GI10_ACTUAL_RUNTIME_SETTLEMENT " + json.dumps({"source_report": Path(report_path).parent.name,
            "tenant_id": tenant, "source_event_count": len(original["items"]), "invocations": 11,
            "customer_receivable_cents": "40", "wallet_balance_cents": "9960",
            "wallet_ledger_entries": 11, "supplier_and_business_simulated": True}, ensure_ascii=False))

    def test_actual_group_background_usage_keeps_unknown_and_recovers_original_http_wallet_instruction(self):
        """仅消费本轮真实群模型报告；HTTP钱包和进程恢复不替代上游供应商真实性。"""
        report_path = os.environ.get("GI10_ACTUAL_GROUP_USAGE_REPORT")
        if not report_path:
            self.skipTest("需显式提供本轮含群后台提取公开原始事件的真实HTTP报告")
        report = json.loads(Path(report_path).read_text(encoding="utf-8"))
        self.assertEqual("PASS", report["state"])
        source = report["group_memory_usage"]
        provisional, completed, headquarters = source["unknown_usage"], source["usage"], source["headquarters_usage"]
        tenant = report["tenant_id"]
        self.assertTrue(provisional["items"])
        self.assertTrue(completed["items"])
        self.assertEqual({item["event_id"] for item in completed["items"]},
                         {item["event_id"] for item in headquarters["items"]})
        attempts = {item["attempt_id"] for item in completed["items"]}
        self.assertEqual(1, len(attempts))
        original = completed["items"][0]
        self.assertIsNotNone(original["group_binding_id"])
        self.assertEqual("SYSTEM", original["requester_kind"])
        self.assertEqual(original["actor_ref"], original["requester_ref"])
        self.assertIsNotNone(original["end_user_id"])
        self.assertNotEqual(original["agent_id"], original["responsibility_agent_id"])
        source_fields = ("group_binding_id", "requester_kind", "requester_ref", "origin_session_id", "origin_event_id",
                         "responsibility_agent_id", "system_agent_run_id", "system_agent_configuration_ref", "system_agent_invocation_ref")
        for item in provisional["items"] + completed["items"] + headquarters["items"]:
            self.assertEqual(tenant, item["tenant_id"])
            for field in source_fields:
                self.assertEqual(original[field], item[field], field)
        self.assertTrue(all(item["kind"] == "unknown" and item.get("tokens") is None for item in provisional["items"]))
        measured = [item for item in completed["items"] if item["kind"] == "measured"]
        self.assertEqual(1, len(measured))
        self.assertEqual((13, 5), (measured[0]["prompt_tokens"], measured[0]["completion_tokens"]))

        self.stop_wallet()
        config = json.loads(self.config_path.read_text(encoding="utf-8"))
        config["accounts"].append({"wallet_account_id": "actual-group", "execution_tenant_id": tenant,
            "currency": "CNY", "name": "真实群后台用量消费者", "initial_balance_cents": "10000", "overdraft_limit_cents": "0"})
        config["callers"][0]["allowed_account_ids"].append("actual-group")
        self.config_path.write_text(json.dumps(config, ensure_ascii=False), encoding="utf-8")
        self.start_wallet()
        binding = {tenant: {"wallet_account_id": "actual-group", "currency": "CNY"}}
        self.run_adapter("consume", stream_key="actual-group", page=provisional, accounts=binding)
        unknown = self.snapshot()
        self.assertEqual(1, len(unknown["objects"]))
        self.assertIsNone(unknown["objects"][0]["target_amount"])
        self.assertEqual([], unknown["instructions"])
        self.assertEqual("10000", self.balance("actual-group"))

        changed_price = PRICE | {"revision": "sales-after-unknown", "input_cents_per_thousand_tokens": "999"}
        self.run_adapter("consume", stream_key="actual-group", page=completed, accounts=binding,
                         expected_cursor=provisional["next_cursor"], price=changed_price, fault="after_commit_before_send")
        committed = self.snapshot()
        self.assertEqual(1, len(committed["objects"]))
        self.assertEqual("sales-v1", committed["objects"][0]["pricing_revision"])
        self.assertEqual("2.3", committed["objects"][0]["target_amount"])
        self.assertEqual(1, len(committed["instructions"]))
        instruction = committed["instructions"][0]["instruction_id"]
        self.assertEqual(0, self.wallet_count("wallet_ledger"))

        self.fault_path.write_text("after_commit", encoding="utf-8")
        dispatched = self.run_adapter("dispatch", accounts=binding)
        self.assertEqual(1, dispatched["unknown"])
        self.process.wait(timeout=10)
        self.assertEqual(77, self.process.returncode)
        self.stop_wallet()
        self.assertEqual(1, self.wallet_count("wallet_ledger"))
        self.assertEqual("unknown", self.snapshot()["instructions"][0]["status"])
        self.start_wallet()
        self.run_adapter("dispatch", accounts=binding)
        recovered = self.snapshot()
        self.assertEqual(instruction, recovered["instructions"][0]["instruction_id"])
        self.assertEqual("applied", recovered["instructions"][0]["status"])
        self.assertEqual("2.3", recovered["objects"][0]["confirmed_net_amount"])
        self.assertEqual("9997.7", self.balance("actual-group"))

        reread = self.run_adapter("consume", stream_key="actual-group-headquarters", page=headquarters, accounts=binding)
        self.assertEqual(0, reread["inserted"])
        self.assertEqual(len(completed["items"]), reread["duplicates"])
        self.run_adapter("consume", stream_key="actual-group", page=completed, accounts=binding)
        self.run_adapter("dispatch", accounts=binding)
        self.assertEqual(1, self.wallet_count("wallet_ledger"))
        self.assertEqual(1, self.wallet_count("wallet_instruction_receipts"))
        self.assertEqual(len(completed["items"]), self.snapshot()["consumed_events"])
        with closing(sqlite3.connect(self.adapter_path)) as db:
            stored = json.loads(db.execute("SELECT payload FROM usage WHERE event_id=?", (original["event_id"],)).fetchone()[0])
        for field in source_fields:
            self.assertEqual(original[field], stored[field], field)
        print("GI10_ACTUAL_GROUP_SETTLEMENT " + json.dumps({"source_report": Path(report_path).parent.name,
            "tenant_id": tenant, "group_binding_id": original["group_binding_id"],
            "system_agent_run_id": original["system_agent_run_id"], "source_event_count": len(completed["items"]),
            "customer_receivable_cents": "2.3", "wallet_balance_cents": "9997.7", "wallet_ledger_entries": 1,
            "supplier_and_wallet_simulated": True}, ensure_ascii=False))


if __name__ == "__main__":
    unittest.main()
