"""失败分类、证据快照与产品回归的离线闭环。"""
from pathlib import Path
import tempfile
import unittest

from behavior_contract import digest
from behavior_dataset import CATEGORY_IDS, freeze_dataset, load_dataset
from behavior_failure_export import export_failures, verify_failure_bundle
from behavior_product_suite import make_product_plan
from behavior_regression import regression_plan, validate_regression_lineage
from behavior_reporting import compare_reports
from runner import read, save
from test_behavior_dataset import candidate


class FailureFlowTests(unittest.TestCase):
    def test_product_comparison_rejects_changed_or_unbound_dataset(self):
        binding = {"suite_version": "product-tasks-1", "model_version": "known-model",
                   "dataset_fingerprint": "old", "harness_digest": "harness"}
        left = {"binding": binding}
        right = {"binding": {**binding, "dataset_fingerprint": "new"}}
        result = compare_reports(left, right)
        self.assertFalse(result["comparable"])
        self.assertIn("binding_mismatch:dataset_fingerprint", result["reasons"])
        del right["binding"]["dataset_fingerprint"]
        self.assertEqual("comparability_unknown", compare_reports(left, right)["status"])

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.source, self.target = base / "source", base / "target"
        save(self.source / "candidates.json", [candidate(c) for c in CATEGORY_IDS])
        dataset = freeze_dataset(self.source, "offline-fixture")
        plan = make_product_plan("source-test", dataset)
        self.binding = {"plan_fingerprint": plan["fingerprint"], "suite_version": plan["suite_version"]}
        save(self.source / "plan.json", plan)
        save(self.source / "private.json", {"sensitive_values": []})
        save(self.source / "batch.json", {"lifecycle": "finalized", "binding": self.binding})
        save(self.source / "evaluation.json", {"lifecycle": "finalized", "binding": self.binding,
             "cases": [{"case_id": "D04", "repetition": 1, "behavior_status": "failed",
                        "assertions": [{"id": "retrieved_source_cited", "status": "fail"}]},
                       {"case_id": "D06", "repetition": 1, "behavior_status": "unknown"},
                       {"case_id": "D12", "repetition": 1, "behavior_status": "passed"}]})
        save(self.source / "cases/D04/1/evidence.json", {"private_reasoning": "MUST_NOT_APPEAR_IN_TASK_TEXT"})

    def test_preserve_failed_unknown_and_keep_raw_content_out_of_task_text(self):
        before = (self.source / "evaluation.json").read_bytes()
        result = export_failures(self.source, self.target, {})
        self.assertEqual(0, result["fix_tasks"])
        bundle = verify_failure_bundle(self.target)
        self.assertEqual(["failed", "unknown"], [r["source_status"] for r in bundle["records"]])
        self.assertFalse(bundle["records"][1]["evidence_present"])
        self.assertFalse(bundle["reproduction_claim"])
        self.assertEqual(before, (self.source / "evaluation.json").read_bytes())
        for path in self.target.glob("*.md"):
            self.assertNotIn("MUST_NOT_APPEAR", path.read_text(encoding="utf-8"))

    def test_product_task_requires_explicit_classification_and_check(self):
        details = {"classification": "A", "infrastructure_checked": True,
                   **{k: "已核对" for k in ("problem", "actual", "expected", "modules", "impact",
                                          "suggested_verification", "classification_basis")}}
        result = export_failures(self.source, self.target, {"D04/1": details})
        self.assertEqual(1, result["fix_tasks"])
        self.assertEqual(1, len(list(self.target.glob("FIX-*.md"))))
        with self.assertRaisesRegex(ValueError, "requires_evidence"):
            export_failures(self.source, self.target.parent / "invalid", {"D06/1": details})

    def test_corrupt_snapshot_unfrozen_source_and_unknown_triage_rejected(self):
        with self.assertRaisesRegex(ValueError, "unknown_trial"):
            export_failures(self.source, self.target, {"D12/1": {"classification": "F"}})
        export_failures(self.source, self.target, {})
        save(self.target / "snapshot/cases/D04/1/evidence.json", {})
        with self.assertRaisesRegex(ValueError, "snapshot_changed"):
            verify_failure_bundle(self.target)
        value = read(self.source / "evaluation.json")
        value["lifecycle"] = "running"
        save(self.source / "evaluation.json", value)
        with self.assertRaisesRegex(ValueError, "not_frozen"):
            export_failures(self.source, self.target.parent / "invalid", {})

    def test_product_regression_uses_current_dataset_and_single_repetition(self):
        result = regression_plan(self.source, self.target, "new-regression", dataset_root=self.source)
        self.assertEqual(["D04", "D06"], result["case_ids"])
        self.assertEqual(2, result["experiments"])
        self.assertEqual(load_dataset(self.source), load_dataset(self.target))
        plan = read(self.target / "plan.json")
        validate_regression_lineage(self.target, plan)
        plan["local_selection"]["case_ids"] = ["D04"]
        plan["fingerprint"] = digest({k: v for k, v in plan.items() if k != "fingerprint"})
        with self.assertRaisesRegex(ValueError, "target_plan_mismatch"):
            validate_regression_lineage(self.target, plan)

    def test_product_changed_input_is_not_same_input_reproduction(self):
        current = self.target.parent / "current"
        rows = [candidate(c) for c in CATEGORY_IDS]
        rows[3]["user_turns"] = ["请查询给定业务规则，注明来源。"]
        save(current / "candidates.json", rows)
        freeze_dataset(current, "new-data")
        result = regression_plan(self.source, self.target, "new-regression", dataset_root=current)
        self.assertEqual(["D04"], result["changed_cases"])
        self.assertFalse(result["reproduction_claim"])
        self.assertNotEqual(read(self.target / "regression.json")["source_dataset_fingerprint"],
                            read(self.target / "regression.json")["target_dataset_fingerprint"])

    def test_targeted_regression_keeps_other_failures_in_original_report(self):
        before = (self.source / "evaluation.json").read_bytes()
        for selection in ([], ["D12"], ["D04", "D04"], ["D99"]):
            with self.assertRaisesRegex(ValueError, "selection_requires_failed_or_unknown"):
                regression_plan(self.source, self.target, "targeted", case_ids=selection)
        result = regression_plan(self.source, self.target, "targeted", case_ids=["D04"])
        self.assertEqual(["D04"], result["case_ids"])
        self.assertEqual(["D04"], [r["case_id"] for r in read(self.target / "regression.json")["source_trials"]])
        self.assertEqual(before, (self.source / "evaluation.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
