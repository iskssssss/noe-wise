"""付费账本负控：不发模型请求，不让非法计量/旧证据/新目录绕过共享预算。"""
from pathlib import Path
import json
import hashlib
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
import budget_ledger


class BudgetLedgerTest(unittest.TestCase):
    """每例私有账本绑定，真实文件和排他锁验证崩溃安全状态。"""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.path = self.root / "supervisor" / "ledger.json"
        binding = patch.object(budget_ledger, "LEDGER_PATH", self.path)
        binding.start(); self.addCleanup(binding.stop)

    def reserve(self, suite="conversation", round_number=1):
        """测试预留真实写盘，batch和证据位置显式。"""
        return budget_ledger.reserve(self.path, suite, round_number, "synthetic-batch", self.root / "evidence")

    def actual(self, suite="conversation"):
        """独立实测材料，计数/时间只取事先声明的合法值。"""
        keys = {"max_attempts": "observed_attempts", "max_tokens": "observed_tokens", "max_seconds": "observed_elapsed_seconds",
                "max_input_turns": "observed_input_turns", "max_embedding_calls": "observed_embedding_calls",
                "max_rerank_calls": "observed_rerank_calls", "max_embedding_tokens": "observed_embedding_tokens", "max_rerank_tokens": "observed_rerank_tokens"}
        return {"usage_known": True, **{keys[key]: 1.25 if key == "max_seconds" else 1 for key in budget_ledger.LIMITS[suite]}}

    def evidence(self, receipt, actual, name="measurement.json", **overrides):
        """证据身份和计量放在同一文件；附原始provider引用不能替代顶层实测。"""
        path = self.root / name
        path.write_text(json.dumps({"reservation_id": receipt["reservation_id"], "batch_id": receipt["batch_id"],
                                    **actual, "provider_records": [{"event_id": "event-1", "usage_source": "PROVIDER_REPORTED"}], **overrides}), encoding="utf-8")
        return path

    def legacy_provider_extension(self, active=False, stopped=False):
        """独立旧计划保留真实既有reserve/settle事实，移除尚未授权追加的scope。"""
        receipt = self.reserve()
        if not active:
            actual = self.actual()
            budget_ledger.settle(self.path, receipt["reservation_id"], actual, self.evidence(receipt, actual))
        ledger = json.loads(self.path.read_text(encoding="utf-8"))
        ledger["limits"] = {k: v for k, v in ledger["limits"].items() if k not in ("provider-smoke", "provider-cache")}
        ledger["stop_new_dispatch"] = stopped
        self.path.write_text(json.dumps(ledger), encoding="utf-8")
        return ledger, hashlib.sha256(self.path.read_bytes()).hexdigest()

    def test_append_two_exact_scopes_preserves_every_original_cap_reservation_actual_and_history(self):
        """新覆盖scope不重置余额或历史，可正常预留新suite且不能重复追加。"""
        before, sha = self.legacy_provider_extension()
        receipt_path = self.root / "mutation-receipt.json"
        receipt = budget_ledger.append_provider_scopes(self.path, {
            "provider-smoke": {"max_attempts": 4, "max_tokens": 40000, "max_seconds": 600},
            "provider-cache": {"max_attempts": 24, "max_tokens": 800000, "max_seconds": 1800}}, sha, receipt_path)
        after = budget_ledger.load_ledger(self.path)
        self.assertEqual(before["reservations"], after["reservations"])
        self.assertEqual(before["limits"], {k: v for k, v in after["limits"].items() if k in before["limits"]})
        self.assertFalse(after["stop_new_dispatch"])
        self.assertEqual(2, after["configuration_revision"])
        self.assertEqual(sha, receipt["old_sha256"])
        self.assertEqual(hashlib.sha256(self.path.read_bytes()).hexdigest(), receipt["new_sha256"])
        self.assertEqual("APPLIED", json.loads(receipt_path.read_text())["status"])
        frozen = self.path.read_bytes()
        with self.assertRaisesRegex(ValueError, "existing_caps_immutable"):
            budget_ledger.append_provider_scopes(self.path, budget_ledger.APPENDABLE_SCOPES, hashlib.sha256(frozen).hexdigest(), self.root / "repeat.json")
        self.assertEqual(frozen, self.path.read_bytes())
        reservation = self.reserve("provider-smoke")
        self.assertEqual((4, 40000, 600), (reservation["max_attempts"], reservation["max_tokens"], reservation["max_seconds"]))

    def test_append_cannot_modify_old_cap_or_choose_new_arbitrary_cap(self):
        """既有core扩容与新scope提高上限都必须原子拒绝。"""
        _, sha = self.legacy_provider_extension()
        frozen = self.path.read_bytes()
        for additions in [{"core": {"max_input_turns": 999}},
                          {**budget_ledger.APPENDABLE_SCOPES, "provider-cache": {"max_attempts": 999, "max_tokens": 800000, "max_seconds": 1800}}]:
            with self.assertRaisesRegex(ValueError, "only_exact_new_provider_caps"):
                budget_ledger.append_provider_scopes(self.path, additions, sha, self.root / "bad-cap.json")
            self.assertEqual(frozen, self.path.read_bytes())
        self.assertFalse((self.root / "bad-cap.json").exists())

    def test_append_requires_no_active_reservation_and_does_not_reset_stop(self):
        """活动RESERVED或停止新派发时追加无副作用，不能借新scope绕过串行或UNKNOWN。"""
        for active, stopped in [(True, False), (False, True)]:
            with self.subTest(active=active, stopped=stopped):
                _, sha = self.legacy_provider_extension(active, stopped)
                frozen = self.path.read_bytes()
                with self.assertRaisesRegex(ValueError, "no_active_and_known_settled"):
                    budget_ledger.append_provider_scopes(self.path, budget_ledger.APPENDABLE_SCOPES, sha, self.root / "active.json")
                self.assertEqual(frozen, self.path.read_bytes())
                self.assertFalse((self.root / "active.json").exists())
                self.path.unlink()

    def test_append_old_sha_and_new_receipt_identity_are_mandatory(self):
        """读取后漂移或复用历史收据时禁止修改账本。"""
        _, sha = self.legacy_provider_extension()
        frozen = self.path.read_bytes()
        with self.assertRaisesRegex(ValueError, "old_sha_mismatch"):
            budget_ledger.append_provider_scopes(self.path, budget_ledger.APPENDABLE_SCOPES, "0" * 64, self.root / "stale.json")
        occupied = self.root / "occupied.json"; occupied.write_text("original")
        with self.assertRaisesRegex(ValueError, "fresh_owned_receipt"):
            budget_ledger.append_provider_scopes(self.path, budget_ledger.APPENDABLE_SCOPES, sha, occupied)
        self.assertEqual(frozen, self.path.read_bytes())
        self.assertEqual("original", occupied.read_text())

    def test_pending_reservation_blocks_every_other_lane_and_round_after_restart(self):
        """进程在派发前后崩溃均留下唯一RESERVED，不允许下一lane猜测无消耗。"""
        first = self.reserve()
        before = self.path.read_bytes()
        for suite, number in [("conversation", 2), ("core", 1), ("vision", 3)]:
            with self.assertRaisesRegex(ValueError, "unsettled_reservation"):
                self.reserve(suite, number)
        self.assertEqual(before, self.path.read_bytes())
        self.assertEqual(first, budget_ledger.check_reservation(self.path, first["reservation_id"], query_only=True))

    def test_canonical_ledger_cannot_be_reset_with_new_evidence_directory_or_filename(self):
        """复制证据或更换ledger文件路径不能再获得同一预算。"""
        self.reserve()
        alternative = self.root / "new-evidence" / "ledger.json"
        with self.assertRaisesRegex(ValueError, "single_supervisor_ledger_required"):
            budget_ledger.reserve(alternative, "conversation", 1, "new-batch", alternative.parent)
        self.assertFalse(alternative.exists())

    def test_negative_boolean_fraction_text_nan_and_infinity_actual_are_rejected_atomically(self):
        """坏计数不能当0或取整，非法时间不能以NaN绕过上限比较。"""
        receipt = self.reserve(); before = self.path.read_bytes()
        for field, value in [("observed_tokens", -1), ("observed_tokens", True), ("observed_tokens", 1.5),
                             ("observed_tokens", "1"), ("observed_attempts", None), ("observed_elapsed_seconds", -0.25),
                             ("observed_elapsed_seconds", float("nan")), ("observed_elapsed_seconds", float("inf")),
                             ("observed_elapsed_seconds", True)]:
            with self.subTest(field=field, value=str(value)):
                actual = self.actual(); actual[field] = value
                with self.assertRaisesRegex(ValueError, "finite_nonnegative"):
                    budget_ledger.settle(self.path, receipt["reservation_id"], actual, self.evidence(receipt, actual))
                self.assertEqual(before, self.path.read_bytes())

    def test_usage_known_requires_boolean_and_all_metrics_and_no_unknown_fields(self):
        """已知必须明确全部cap对应材料；少字段和任意键不能进入账本。"""
        receipt = self.reserve(); before = self.path.read_bytes()
        invalid = [None, [], {"usage_known": "true"}, {"usage_known": True}, {**self.actual(), "tokens": 0}]
        for actual in invalid:
            with self.subTest(actual=actual), self.assertRaises(ValueError):
                budget_ledger.settle(self.path, receipt["reservation_id"], actual, self.root / "absent.json")
        self.assertEqual(before, self.path.read_bytes())

    def test_missing_non_json_and_foreign_identity_evidence_never_settle(self):
        """文件存在本身不证明计量；异批次、异reservation和非法JSON均拒绝。"""
        receipt = self.reserve(); actual = self.actual(); before = self.path.read_bytes()
        plain = self.root / "plain.txt"; plain.write_text("not JSON", encoding="utf-8")
        files = [self.root / "missing.json", plain,
                 self.evidence(receipt, actual, "foreign.json", reservation_id="other"),
                 self.evidence(receipt, actual, "foreign-batch.json", batch_id="another-batch")]
        for file in files:
            with self.subTest(file=file.name), self.assertRaises(ValueError):
                budget_ledger.settle(self.path, receipt["reservation_id"], actual, file)
        self.assertEqual(before, self.path.read_bytes())

    def test_usage_known_and_each_actual_metric_must_match_evidence(self):
        """不能用UNKNOWN证据结算KNOWN，不能手填比实际记录更小的tokens。"""
        receipt = self.reserve(); actual = self.actual(); before = self.path.read_bytes()
        for overrides in [{"usage_known": False}, {"observed_tokens": 2}, {"observed_attempts": True}, {"observed_elapsed_seconds": 1}]:
            with self.subTest(overrides=overrides), self.assertRaisesRegex(ValueError, "evidence_inconsistent"):
                budget_ledger.settle(self.path, receipt["reservation_id"], actual, self.evidence(receipt, actual, **overrides))
        self.assertEqual(before, self.path.read_bytes())

    def test_all_suite_limits_accept_exact_cap_and_keep_one_reservation_at_a_time(self):
        """包含knowledge多种计量单位，所有冻结suite均可精确结算到cap。"""
        for suite in budget_ledger.LIMITS:
            receipt = self.reserve(suite)
            actual = {"usage_known": True, **{("observed_elapsed_seconds" if key == "max_seconds" else key.replace("max_", "observed_", 1)): value
                                               for key, value in budget_ledger.LIMITS[suite].items()}}
            result = budget_ledger.settle(self.path, receipt["reservation_id"], actual, self.evidence(receipt, actual))
            self.assertEqual("SETTLED", result["status"])
            self.assertEqual(actual, result["actual"])
        self.assertFalse(json.loads(self.path.read_text(encoding="utf-8"))["stop_new_dispatch"])

    def test_unknown_preserves_known_partial_usage_and_stops_new_dispatch(self):
        """未知用量不填0，不释放下一lane；原identity查询仍可恢复。"""
        receipt = self.reserve(); actual = {"usage_known": False, "observed_attempts": 1}
        result = budget_ledger.settle(self.path, receipt["reservation_id"], actual, self.evidence(receipt, actual))
        self.assertEqual(("UNKNOWN", actual), (result["status"], result["actual"]))
        self.assertNotIn("observed_tokens", result["actual"])
        with self.assertRaisesRegex(ValueError, "stops_new_dispatch"): self.reserve("core", 1)
        with self.assertRaisesRegex(ValueError, "query_only"): budget_ledger.check_reservation(self.path, receipt["reservation_id"])
        self.assertEqual("UNKNOWN", budget_ledger.check_reservation(self.path, receipt["reservation_id"], query_only=True)["status"])

    def test_unknown_cannot_omit_known_evidence_metrics_or_hide_exceeded_usage(self):
        """整体未知也必须保留证据中的已知维度，不能漏掉已确认超额的Tokens。"""
        receipt = self.reserve(); actual = {"usage_known": False}; before = self.path.read_bytes()
        for tokens in (1, 20_001):
            with self.subTest(tokens=tokens), self.assertRaisesRegex(ValueError, "evidence_inconsistent"):
                budget_ledger.settle(self.path, receipt["reservation_id"], actual,
                                     self.evidence(receipt, actual, observed_tokens=tokens))
        self.assertEqual(before, self.path.read_bytes())
        self.assertEqual("RESERVED", budget_ledger.check_reservation(self.path, receipt["reservation_id"])["status"])

    def test_over_cap_retains_actual_fact_and_blocks_following_dispatch(self):
        """费用实际超限必须保留超额值，不截断为cap或把known改unknown掩盖。"""
        for field, value in [("observed_tokens", 20_001), ("observed_attempts", 3), ("observed_elapsed_seconds", 600.5)]:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "ledger.json"
                with patch.object(budget_ledger, "LEDGER_PATH", path):
                    receipt = budget_ledger.reserve(path, "conversation", 1, "batch", Path(directory))
                    actual = self.actual(); actual[field] = value
                    result = budget_ledger.settle(path, receipt["reservation_id"], actual, self.evidence(receipt, actual))
                    self.assertEqual("EXCEEDED", result["status"]); self.assertTrue(result["usage_known"])
                    self.assertEqual(value, result["actual"][field]); self.assertEqual(value, result["exceeded"][field]["actual"])
                    with self.assertRaisesRegex(ValueError, "stops_new_dispatch"):
                        budget_ledger.reserve(path, "core", 1, "next", Path(directory))

    def test_unknown_usage_that_already_exceeds_cap_keeps_exceeded_fact(self):
        """计量整体未知但已确认部分超限，同时保留unknown属性和EXCEEDED终态。"""
        receipt = self.reserve(); actual = {"usage_known": False, "observed_tokens": 20_001}
        result = budget_ledger.settle(self.path, receipt["reservation_id"], actual, self.evidence(receipt, actual))
        self.assertEqual("EXCEEDED", result["status"]); self.assertFalse(result["usage_known"])

    def test_same_suite_round_cannot_be_reissued_or_settled_twice(self):
        """诊断和重跑共享同预留，不能通过另一batch重复购买预算。"""
        receipt = self.reserve(); actual = self.actual(); evidence = self.evidence(receipt, actual)
        budget_ledger.settle(self.path, receipt["reservation_id"], actual, evidence)
        before = self.path.read_bytes()
        with self.assertRaisesRegex(ValueError, "already_settled"):
            budget_ledger.settle(self.path, receipt["reservation_id"], actual, evidence)
        with self.assertRaisesRegex(ValueError, "suite_round_already_reserved"):
            self.reserve()
        self.assertEqual(before, self.path.read_bytes())

    def test_exclusive_lock_does_not_delete_another_owner_or_reset_ledger(self):
        """已有排他锁包括崩溃遗留锁，调用者无权自动清理后续发送。"""
        self.path.parent.mkdir(parents=True)
        lock = self.path.with_suffix(".json.lock"); lock.write_text("prior-owner", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "no_parallel_dispatch"): self.reserve()
        self.assertEqual("prior-owner", lock.read_text(encoding="utf-8")); self.assertFalse(self.path.exists())

    def test_frozen_plan_drift_or_unknown_reservation_id_is_rejected(self):
        """扩大cap、异run账本和错误identity不进入结算。"""
        receipt = self.reserve(); actual = self.actual(); evidence = self.evidence(receipt, actual)
        with self.assertRaisesRegex(ValueError, "reservation_not_found"):
            budget_ledger.settle(self.path, "missing", actual, evidence)
        ledger = json.loads(self.path.read_text(encoding="utf-8")); ledger["limits"]["conversation"]["max_tokens"] += 1
        self.path.write_text(json.dumps(ledger), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "frozen_budget_plan_drift"):
            budget_ledger.check_reservation(self.path, receipt["reservation_id"], query_only=True)

    def known_core(self):
        """真实0input设施失败可复用原身份；设施墙钟仍计入累计额度。"""
        receipt = self.reserve("core")
        actual = {"usage_known": True, "observed_input_turns": 0, "observed_tokens": 0, "observed_elapsed_seconds": 4.0}
        budget_ledger.settle(self.path, receipt["reservation_id"], actual, self.evidence(receipt, actual))
        return receipt, actual

    def diagnostic_evidence(self, receipt, actual):
        """reopen只预留，wrapper随后建立新的strict-child目录与delta证据。"""
        path = Path(receipt["active_evidence_root"]) / "measurement.json"
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps({"reservation_id":receipt["reservation_id"], "batch_id":receipt["batch_id"], **actual}),encoding="utf-8")
        return path

    def test_known_diagnostic_keeps_identity_history_and_cumulative_budget_across_two_reopens(self):
        receipt, prior = self.known_core()
        identity = receipt["reservation_id"]
        first_history = budget_ledger.check_reservation(self.path, identity, query_only=True)["settlement_history"][0]
        for sequence in (1, 2):
            child = self.root / "evidence" / ("diagnostic-" + str(sequence))
            opened = budget_ledger.reopen_known_diagnostic(self.path, identity, child)
            self.assertEqual(identity, opened["reservation_id"]); self.assertEqual(sequence, opened["diagnostic_sequence"])
            self.assertEqual(receipt["evidence_root"],opened["evidence_root"]); self.assertFalse(child.exists())
            self.assertEqual(2_000_000 - 20*(sequence-1),opened["remaining_limits"]["max_tokens"])
            self.assertEqual(7200 - 4.0 - 3.0*(sequence-1),opened["remaining_limits"]["max_seconds"])
            with self.assertRaisesRegex(ValueError, "unsettled_reservation"): self.reserve("vision")
            delta = {"usage_known":True,"observed_input_turns":2,"observed_tokens":20,"observed_elapsed_seconds":3.0}
            settled = budget_ledger.settle(self.path,identity,delta,self.diagnostic_evidence(opened,delta))
            self.assertEqual(20*sequence,settled["actual"]["observed_tokens"])
            self.assertEqual(4.0 + 3.0*sequence,settled["actual"]["observed_elapsed_seconds"])
            self.assertEqual(first_history,settled["settlement_history"][0])
            self.assertEqual(sequence+1,len(settled["settlement_history"]))
            self.assertEqual(delta,settled["actual_delta"])
        self.assertEqual(1,len(json.loads(self.path.read_text(encoding="utf-8"))["reservations"]))

    def test_diagnostic_requires_fresh_strict_child_and_preserves_ledger_on_rejection(self):
        receipt,_ = self.known_core(); identity=receipt["reservation_id"]
        used=self.root/"evidence"/"already-exists"; used.mkdir(parents=True)
        before=self.path.read_bytes()
        for child in (self.root/"other",self.root/"evidence",used):
            with self.subTest(child=child),self.assertRaisesRegex(ValueError,"diagnostic_evidence"):
                budget_ledger.reopen_known_diagnostic(self.path,identity,child)
        self.assertEqual(before,self.path.read_bytes())

    def test_unknown_exceeded_or_other_pending_lane_cannot_reopen_diagnostic(self):
        for actual in ({"usage_known":False}, {"usage_known":True,"observed_attempts":3,"observed_tokens":1,"observed_elapsed_seconds":1.0}):
            with self.subTest(actual=actual),tempfile.TemporaryDirectory() as directory:
                path=Path(directory)/"ledger.json"
                with patch.object(budget_ledger,"LEDGER_PATH",path):
                    receipt=budget_ledger.reserve(path,"conversation",1,"batch",Path(directory)/"owner")
                    budget_ledger.settle(path,receipt["reservation_id"],actual,self.evidence(receipt,actual))
                    before=path.read_bytes()
                    with self.assertRaisesRegex(ValueError,"known_settled"):
                        budget_ledger.reopen_known_diagnostic(path,receipt["reservation_id"],Path(directory)/"owner"/"diag")
                    self.assertEqual(before,path.read_bytes())
        receipt,_=self.known_core(); self.reserve("vision")
        with self.assertRaisesRegex(ValueError,"unsettled_reservation"):
            budget_ledger.reopen_known_diagnostic(self.path,receipt["reservation_id"],self.root/"evidence"/"diag")

    def test_diagnostic_settlement_cannot_use_original_or_foreign_evidence(self):
        receipt,_=self.known_core(); opened=budget_ledger.reopen_known_diagnostic(self.path,receipt["reservation_id"],self.root/"evidence"/"diag")
        actual=self.actual("core"); before=self.path.read_bytes()
        with self.assertRaisesRegex(ValueError,"outside_active_child"):
            budget_ledger.settle(self.path,receipt["reservation_id"],actual,self.evidence(opened,actual))
        self.assertEqual(before,self.path.read_bytes())

    def test_diagnostic_cumulative_over_cap_keeps_actual_and_unknown_keeps_prior_known_dimensions(self):
        receipt,prior=self.known_core(); opened=budget_ledger.reopen_known_diagnostic(self.path,receipt["reservation_id"],self.root/"evidence"/"diag")
        actual={"usage_known":False,"observed_tokens":2_000_001}
        result=budget_ledger.settle(self.path,receipt["reservation_id"],actual,self.diagnostic_evidence(opened,actual))
        self.assertEqual("EXCEEDED",result["status"]); self.assertFalse(result["usage_known"])
        self.assertEqual(2_000_001,result["actual"]["observed_tokens"])
        self.assertEqual(prior["observed_elapsed_seconds"],result["actual"]["observed_elapsed_seconds"])
        self.assertEqual(prior["observed_input_turns"],result["actual"]["observed_input_turns"])
        with self.assertRaisesRegex(ValueError,"known_settled"):
            budget_ledger.reopen_known_diagnostic(self.path,receipt["reservation_id"],self.root/"evidence"/"diag2")

    def test_diagnostic_cannot_restore_exhausted_known_budget_or_reuse_settled_child(self):
        receipt,_=self.known_core(); child=self.root/"evidence"/"diag"
        opened=budget_ledger.reopen_known_diagnostic(self.path,receipt["reservation_id"],child)
        actual=self.actual("core")
        budget_ledger.settle(self.path,receipt["reservation_id"],actual,self.diagnostic_evidence(opened,actual))
        with self.assertRaisesRegex(ValueError,"already_used"):
            budget_ledger.reopen_known_diagnostic(self.path,receipt["reservation_id"],child)
        opened=budget_ledger.reopen_known_diagnostic(self.path,receipt["reservation_id"],self.root/"evidence"/"diag2")
        actual={"usage_known":True,"observed_tokens":1_999_999,"observed_input_turns":1,"observed_elapsed_seconds":1.0}
        budget_ledger.settle(self.path,receipt["reservation_id"],actual,self.diagnostic_evidence(opened,actual))
        with self.assertRaisesRegex(ValueError,"budget_exhausted"):
            budget_ledger.reopen_known_diagnostic(self.path,receipt["reservation_id"],self.root/"evidence"/"diag3")


if __name__ == "__main__":
    unittest.main()
