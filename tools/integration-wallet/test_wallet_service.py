"""独立钱包真实 HTTP/SQLite 验证；本模块不读取消费适配器数据库。"""
from __future__ import annotations

import json
import os
import sqlite3
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

from wallet_service import (POLICY, SettlementError, WalletApplication, WalletPrincipal,
                            WalletStore, create_server)
from settlement_contract import WalletClient, WalletFailure


def account(account_id="wallet-7", tenant="7", unit_currency="CNY", initial="500", overdraft="0"):
    return {"wallet_account_id": account_id, "execution_tenant_id": tenant, "currency": unit_currency,
            "name": "模拟机构钱包 " + account_id, "initial_balance_cents": initial, "overdraft_limit_cents": overdraft}


def configuration(initial="500", overdraft="0"):
    return {"accounts": [account(initial=initial, overdraft=overdraft), account("wallet-8", "8"), account("wallet-usd", "7", "USD")],
            "callers": [{"caller_service_id": "adapter-service", "token_env": "GI10_WALLET_TEST_A", "allowed_account_ids": ["wallet-7", "wallet-usd"]},
                        {"caller_service_id": "other-service", "token_env": "GI10_WALLET_TEST_B", "allowed_account_ids": ["wallet-8"]}]}


class WalletHttpTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "wallet.sqlite3"
        self.config = configuration()
        self.environ = patch.dict(os.environ, {"GI10_WALLET_TEST_A": "synthetic-wallet-a", "GI10_WALLET_TEST_B": "synthetic-wallet-b"})
        self.environ.start()
        self.addCleanup(self.environ.stop)
        self.running = []
        self.addCleanup(self.stop_all)
        self.start()

    def start(self, config=None, fault=None):
        server = create_server(0, self.path, config or self.config, fault=fault)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.running.append((server, thread))
        self.server = server
        self.origin = "http://127.0.0.1:" + str(server.server_port)
        self.client = WalletClient(self.origin, "synthetic-wallet-a")

    def stop_all(self):
        while self.running:
            server, thread = self.running.pop()
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
            self.assertFalse(thread.is_alive())

    def instruction(self, instruction_id="instruction-1", object_id="object-1", target="100", sequence=1,
                    previous=None, **changes):
        return {"instruction_id": instruction_id, "object_id": object_id, "execution_tenant_id": "7", "wallet_account_id": "wallet-7",
                "currency": "CNY", "metering_object_ref": "attempt:" + object_id, "sequence": sequence,
                "previous_instruction_id": previous, "target_amount": target, "pricing_revision": "price-1", **changes}

    def balance(self):
        return self.client.request("GET", "/api/v1/accounts/detail", query={"wallet_account_id": "wallet-7", "currency": "CNY"})

    def count(self, table):
        with closing(sqlite3.connect(self.path)) as db:
            return db.execute("SELECT count(*) FROM " + table).fetchone()[0]

    def admission(self, admission_id="admit-1", **changes):
        return {"admission_id": admission_id, "execution_tenant_id": "7", "wallet_account_id": "wallet-7", "currency": "CNY",
                "business_root_ref": "input-1", "policy": POLICY, **changes}

    def request(self, method, path, body=None, token="synthetic-wallet-a", key=None, expected=200, raw=None):
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        if key is not None:
            headers["Idempotency-Key"] = key
        payload = raw if raw is not None else None if body is None else json.dumps(body).encode()
        request = Request(self.origin + path, data=payload, headers=headers, method=method)
        opener = build_opener(ProxyHandler({}))
        try:
            with opener.open(request, timeout=5) as response:
                status, envelope = response.status, json.load(response)
        except HTTPError as response:
            with response:
                status, envelope = response.status, json.load(response)
        self.assertEqual(expected, status)
        self.assertEqual({"code", "message", "data", "trace_id"}, set(envelope))
        self.assertTrue(envelope["trace_id"])
        return envelope

    def test_authentication_account_scope_and_queries_never_leak_other_accounts(self):
        items = self.client.request("GET", "/api/v1/accounts")["items"]
        self.assertEqual({"wallet-7", "wallet-usd"}, {item["wallet_account_id"] for item in items})
        self.request("GET", "/api/v1/accounts", token=None, expected=401)
        self.request("GET", "/api/v1/accounts", token="forged", expected=401)
        self.request("GET", "/api/v1/accounts/detail?wallet_account_id=wallet-8&currency=CNY", expected=403)
        self.request("POST", "/api/v1/settlements", self.instruction(execution_tenant_id="8"), key="instruction-1", expected=403)
        self.request("POST", "/api/v1/settlements", self.instruction(wallet_account_id="wallet-8", execution_tenant_id="8"), key="instruction-1", expected=403)
        self.request("POST", "/api/v1/settlements", self.instruction(currency="USD"), key="instruction-1", expected=403)
        self.assertEqual(0, self.count("wallet_instruction_receipts"))
        self.assertEqual(0, self.count("wallet_settlement_objects"))

    def test_success_then_lower_target_refunds_only_previously_confirmed_net(self):
        first = self.client.settle(self.instruction())
        self.assertEqual("applied", first["outcome"])
        self.assertEqual("100", first["delta"])
        self.assertEqual("100", first["confirmed_net_amount"])
        self.assertEqual("400", first["balance_cents"])
        second = self.client.settle(self.instruction("instruction-2", target="80", sequence=2, previous="instruction-1"))
        self.assertEqual("-20", second["delta"])
        self.assertEqual("80", second["confirmed_net_amount"])
        self.assertEqual("420", self.balance()["balance_cents"])
        third = self.client.settle(self.instruction("instruction-3", target="0", sequence=3, previous="instruction-2"))
        self.assertEqual("-80", third["delta"])
        self.assertEqual("500", self.balance()["balance_cents"])
        self.assertEqual(first, self.client.lookup("instruction-1"))
        self.assertEqual(3, self.count("wallet_ledger"))

    def test_rejection_is_terminal_without_balance_or_net_change_and_next_target_is_still_due(self):
        refused = self.client.settle(self.instruction(target="600"))
        self.assertEqual("rejected", refused["outcome"])
        self.assertEqual("INSUFFICIENT_BALANCE", refused["reason"])
        self.assertEqual("0", refused["delta"])
        self.assertEqual("0", refused["confirmed_net_amount"])
        self.assertEqual(1, refused["last_terminal_sequence"])
        self.assertEqual("500", self.balance()["balance_cents"])
        next_receipt = self.client.settle(self.instruction("instruction-2", target="80", sequence=2, previous="instruction-1"))
        self.assertEqual("80", next_receipt["delta"])
        self.assertEqual("420", self.balance()["balance_cents"])
        self.assertEqual(refused, self.client.settle(self.instruction(target="600")))
        self.assertEqual(1, self.count("wallet_ledger"))

    def test_out_of_order_and_unknown_predecessor_wait_without_refunding(self):
        second_request = self.instruction("instruction-2", target="80", sequence=2, previous="instruction-1")
        waiting = self.request("POST", "/api/v1/settlements", second_request, key="instruction-2", expected=202)["data"]
        self.assertEqual("waiting_predecessor", waiting["outcome"])
        self.assertIsNone(waiting["delta"])
        self.assertEqual("500", self.balance()["balance_cents"])
        self.assertEqual(0, self.count("wallet_instruction_receipts"))
        self.assertEqual(0, self.count("wallet_ledger"))
        self.assertEqual("waiting_predecessor", self.client.lookup("instruction-2")["outcome"])
        self.client.settle(self.instruction())
        self.assertEqual("-20", self.client.settle(second_request)["delta"])
        self.assertEqual("420", self.balance()["balance_cents"])
        self.assertEqual(0, self.count("wallet_pending_instructions"))

    def test_skip_sequence_and_same_sequence_other_instruction_cannot_change_balance(self):
        third = self.instruction("instruction-3", target="80", sequence=3, previous="instruction-2")
        self.request("POST", "/api/v1/settlements", third, key="instruction-3", expected=202)
        competing = third | {"instruction_id": "different-third"}
        self.request("POST", "/api/v1/settlements", competing, key="different-third", expected=409)
        self.client.settle(self.instruction())
        self.request("POST", "/api/v1/settlements", self.instruction("different-first"), key="different-first", expected=409)
        self.client.settle(self.instruction("instruction-2", target="90", sequence=2, previous="instruction-1"))
        self.assertEqual("-10", self.client.settle(third)["delta"])
        self.assertEqual("420", self.balance()["balance_cents"])

    def test_same_key_parameter_conflict_and_object_scope_are_immutable(self):
        original = self.client.settle(self.instruction())
        for changed in (self.instruction(target="101"), self.instruction(pricing_revision="price-2"),
                        self.instruction(metering_object_ref="different-attempt")):
            self.request("POST", "/api/v1/settlements", changed, key="instruction-1", expected=409)
        changed_scope = self.instruction("instruction-2", target="80", sequence=2, previous="instruction-1", metering_object_ref="different-attempt")
        self.request("POST", "/api/v1/settlements", changed_scope, key="instruction-2", expected=409)
        semantic_duplicate = self.instruction("new-first", object_id="new-object", metering_object_ref="attempt:object-1")
        self.request("POST", "/api/v1/settlements", semantic_duplicate, key="new-first", expected=409)
        self.assertEqual(original, self.client.lookup("instruction-1"))
        self.assertEqual(1, self.count("wallet_ledger"))

    def test_receipts_require_current_account_scope_and_unknown_lookup_is_not_acceptance_proof(self):
        self.assertIsNone(self.client.lookup("missing-instruction"))
        self.client.settle(self.instruction())
        other = WalletClient(self.origin, "synthetic-wallet-b")
        with self.assertRaises(WalletFailure) as forbidden_receipt:
            other.lookup("instruction-1")
        self.assertEqual(403, forbidden_receipt.exception.status)
        with self.assertRaises(WalletFailure) as failure:
            other.request("GET", "/api/v1/accounts/detail", query={"wallet_account_id": "wallet-7", "currency": "CNY"})
        self.assertEqual(403, failure.exception.status)

    def test_two_authorized_callers_share_one_economic_object_and_original_receipt(self):
        self.stop_all()
        self.config["callers"][1]["allowed_account_ids"] = ["wallet-7"]
        self.start()
        other = WalletClient(self.origin, "synthetic-wallet-b")
        gate = threading.Barrier(2)
        def submit(client):
            gate.wait(timeout=5)
            return client.settle(self.instruction())
        with ThreadPoolExecutor(max_workers=2) as workers:
            receipts = list(workers.map(submit, [self.client, other]))
        self.assertEqual(receipts[0], receipts[1])
        self.assertEqual(receipts[0], other.lookup("instruction-1"))
        self.assertEqual("400", self.balance()["balance_cents"])
        self.assertEqual(1, self.count("wallet_settlement_objects"))
        self.assertEqual(1, self.count("wallet_instruction_receipts"))
        self.assertEqual(1, self.count("wallet_ledger"))
        alias = self.instruction("alias-instruction", object_id="alias-object", metering_object_ref="attempt:object-1")
        with self.assertRaises(WalletFailure) as conflict:
            other.settle(alias)
        self.assertEqual(409, conflict.exception.status)
        second = other.settle(self.instruction("instruction-2", target="80", sequence=2, previous="instruction-1"))
        self.assertEqual("-20", second["delta"])
        self.assertEqual("420", self.balance()["balance_cents"])
        self.assertEqual(second, self.client.lookup("instruction-2"))
        self.assertEqual(1, self.count("wallet_settlement_objects"))

    def test_reconciliation_reference_requires_original_same_object_rejection(self):
        self.client.settle(self.instruction(target="600"))
        invalid = self.instruction("instruction-2", target="80", sequence=2, previous="instruction-1", reconciles_rejection_id="missing")
        self.request("POST", "/api/v1/settlements", invalid, key="instruction-2", expected=400)
        reconciled = self.client.settle(invalid | {"reconciles_rejection_id": "instruction-1"})
        self.assertEqual("80", reconciled["delta"])
        self.assertEqual("instruction-1", reconciled["reconciles_rejection_id"])

    def test_parallel_duplicate_instructions_debit_once_and_parallel_objects_respect_balance(self):
        with ThreadPoolExecutor(max_workers=4) as workers:
            receipts = list(workers.map(lambda _: self.client.settle(self.instruction()), range(8)))
        self.assertTrue(all(receipt == receipts[0] for receipt in receipts))
        self.assertEqual(1, self.count("wallet_ledger"))
        gate = threading.Barrier(2)
        def debit(number):
            gate.wait(timeout=5)
            return self.client.settle(self.instruction("parallel-" + str(number), "parallel-" + str(number), "300"))
        with ThreadPoolExecutor(max_workers=2) as workers:
            results = list(workers.map(debit, range(2)))
        self.assertEqual({"applied", "rejected"}, {receipt["outcome"] for receipt in results})
        self.assertEqual("100", self.balance()["balance_cents"])

    def test_precise_38_digit_balance_arithmetic_and_tiny_refund_survive_http(self):
        self.stop_all()
        # 新独立钱包实验不覆盖其他测试状态；所有字节使用正式HTTP往返。
        self.path = Path(self.directory.name) / "precise.sqlite3"
        self.config = configuration(initial="12345678901234567890123456.123456789012")
        self.start()
        receipt = self.client.settle(self.instruction(target="0.000000000001"))
        self.assertEqual("12345678901234567890123456.123456789011", receipt["balance_cents"])
        refund = self.client.settle(self.instruction("instruction-2", target="0", sequence=2, previous="instruction-1"))
        self.assertEqual("-0.000000000001", refund["delta"])
        self.assertEqual(self.config["accounts"][0]["initial_balance_cents"], self.balance()["balance_cents"])

    def test_restart_keeps_balance_pending_instruction_and_original_receipt(self):
        original = self.client.settle(self.instruction())
        pending = self.instruction("instruction-3", target="80", sequence=3, previous="instruction-2")
        self.request("POST", "/api/v1/settlements", pending, key="instruction-3", expected=202)
        self.stop_all()
        self.start()
        self.assertEqual("400", self.balance()["balance_cents"])
        self.assertEqual(original, self.client.settle(self.instruction()))
        self.assertEqual("waiting_predecessor", self.client.lookup("instruction-3")["outcome"])
        changed = configuration(initial="1000")
        with self.assertRaises(SettlementError) as conflict:
            WalletApplication(self.path, changed)
        self.assertEqual(409, conflict.exception.status)
        self.assertEqual("400", self.balance()["balance_cents"])

    def test_before_commit_failure_rolls_back_every_wallet_fact(self):
        self.stop_all()
        calls = []
        def fault(stage, result):
            calls.append(stage)
            if stage == "before_commit":
                raise RuntimeError("synthetic-before-commit")
        self.start(fault=fault)
        self.request("POST", "/api/v1/settlements", self.instruction(), key="instruction-1", expected=503)
        self.assertEqual(["before_commit"], calls)
        self.assertEqual("500", self.balance()["balance_cents"])
        for table in ("wallet_settlement_objects", "wallet_pending_instructions", "wallet_instruction_receipts", "wallet_ledger"):
            self.assertEqual(0, self.count(table), table)
        self.assertIsNone(self.client.lookup("instruction-1"))

    def test_after_commit_lost_response_queries_original_and_same_id_never_double_debits(self):
        self.stop_all()
        calls = []
        def fault(stage, result):
            calls.append(stage)
            if stage == "after_commit":
                raise RuntimeError("synthetic-lost-response")
        self.start(fault=fault)
        self.request("POST", "/api/v1/settlements", self.instruction(), key="instruction-1", expected=503)
        self.assertEqual(["before_commit", "after_commit"], calls)
        original = self.client.lookup("instruction-1")
        self.assertEqual("applied", original["outcome"])
        self.assertEqual("400", self.balance()["balance_cents"])
        self.assertEqual(original, self.client.settle(self.instruction()))
        self.assertEqual(1, self.count("wallet_ledger"))
        self.assertEqual(["before_commit", "after_commit"], calls)

    def test_bounded_overdraft_admission_is_not_a_reservation_and_release_never_refunds(self):
        self.stop_all()
        self.path = Path(self.directory.name) / "overdraft.sqlite3"
        self.config = configuration(initial="10", overdraft="5")
        self.start()
        request = self.admission()
        admission = self.client.request("POST", "/api/v1/admissions", request, idempotency_key="admit-1")
        self.assertTrue(admission["admitted"])
        self.assertEqual("0", admission["reserved_amount"])
        self.assertEqual("10", self.balance()["balance_cents"])
        first = self.client.settle(self.instruction(target="15"))
        self.assertEqual("-5", first["balance_cents"])
        refused_admission = self.admission("admit-2")
        rejected = self.client.request("POST", "/api/v1/admissions", refused_admission, idempotency_key="admit-2")
        self.assertFalse(rejected["admitted"])
        self.assertEqual(admission, self.client.request("POST", "/api/v1/admissions", request, idempotency_key="admit-1"))
        released = self.client.request("POST", "/api/v1/admissions/release", request, idempotency_key="admit-1")
        self.assertIsNotNone(released["released_at"])
        self.assertFalse(released["releases_fees"])
        self.assertEqual("-5", self.balance()["balance_cents"])
        self.assertEqual(released, self.client.request("POST", "/api/v1/admissions/release", request, idempotency_key="admit-1"))
        self.client.settle(self.instruction("instruction-2", target="14", sequence=2, previous="instruction-1"))
        self.assertEqual(rejected, self.client.request("POST", "/api/v1/admissions", refused_admission, idempotency_key="admit-2"))
        next_admission = self.admission("admit-3")
        self.assertTrue(self.client.request("POST", "/api/v1/admissions", next_admission, idempotency_key="admit-3")["admitted"])

    def test_admission_scope_idempotency_and_unsupported_policy_fail_closed(self):
        request = self.admission()
        self.client.request("POST", "/api/v1/admissions", request, idempotency_key="admit-1")
        self.request("POST", "/api/v1/admissions", request | {"business_root_ref": "different"}, key="admit-1", expected=409)
        self.request("POST", "/api/v1/admissions", self.admission("other", wallet_account_id="wallet-8", execution_tenant_id="8"), key="other", expected=403)
        self.request("POST", "/api/v1/admissions", self.admission("other", policy="reserve"), key="other", expected=400)
        self.request("POST", "/api/v1/admissions/release", self.admission("missing"), key="missing", expected=404)
        self.assertEqual("500", self.balance()["balance_cents"])

    def test_duplicate_json_invalid_amounts_and_illegal_instruction_closure_do_not_write(self):
        self.request("POST", "/api/v1/settlements", key="instruction-1", raw=b'{"instruction_id":"x","instruction_id":"y"}', expected=400)
        for changes in ({"target_amount": 1.1}, {"target_amount": "NaN"}, {"target_amount": "0.0000000000001"},
                        {"target_amount": "-1"}, {"sequence": True}, {"previous_instruction_id": "forged"},
                        {"sequence": 2, "previous_instruction_id": None}, {"execution_tenant_id": 7}, {"fingerprint": "caller-forged"}):
            self.request("POST", "/api/v1/settlements", self.instruction(**changes), key="instruction-1", expected=400)
        self.request("POST", "/api/v1/settlements", self.instruction(), key="different", expected=400)
        self.assertEqual(0, self.count("wallet_settlement_objects"))
        self.assertEqual(0, self.count("wallet_instruction_receipts"))


class WalletConfigurationTest(unittest.TestCase):
    def test_missing_environment_secret_refuses_activation_and_principal_repr_does_not_contain_token(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(SettlementError):
                WalletApplication(Path(directory) / "wallet.sqlite3", configuration())
        principal = WalletPrincipal("service", frozenset({"account"}))
        self.assertNotIn("Bearer", repr(principal))

    def test_invalid_account_and_duplicate_config_refuse_startup(self):
        with tempfile.TemporaryDirectory() as directory:
            for accounts in ([account(overdraft="-1")], [account(initial="-1", overdraft="0")], [account(), account()]):
                with self.assertRaises(SettlementError):
                    WalletStore(Path(directory) / "wallet.sqlite3", accounts)


if __name__ == "__main__":
    unittest.main()
