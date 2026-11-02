"""回归候选不改写历史、不复制身份、不冒称失败已复现。"""
from pathlib import Path
import tempfile
import unittest

from behavior_contract import digest
from behavior_regression import regression_plan, validate_regression_lineage
from behavior_suite import make_plan
from runner import read, save


def source_fixture(root, status="failed"):
    canary = "SF-PRIVATE-source-test-marker"
    plan = make_plan("source-test", canary=canary)
    binding = {"plan_fingerprint": plan["fingerprint"], "suite_version": plan["suite_version"]}
    for name, value in {
        "plan.json": plan, "private.json": {"sensitive_values": [canary]},
        "batch.json": {"lifecycle": "finalized", "binding": binding},
        "evaluation.json": {"lifecycle": "finalized", "binding": binding,
            "cases": [{"case_id": "B18", "repetition": 2, "behavior_status": status},
                      {"case_id": "B04", "repetition": 1, "behavior_status": "passed"}]},
    }.items():
        save(root / name, value)


class RegressionPlanTests(unittest.TestCase):
    def test_current_failure_creates_fresh_plan_and_keeps_missing_evidence_unknown(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, target = Path(tmp) / "source", Path(tmp) / "target"
            source_fixture(source, "unknown")
            before = {p.name: p.read_bytes() for p in source.iterdir()}
            result = regression_plan(source, target, "new-regression")
            self.assertEqual(["B18"], result["case_ids"])
            self.assertEqual([], result["changed_cases"])
            plan, lineage = read(target / "plan.json"), read(target / "regression.json")
            self.assertEqual(3, plan["local_selection"]["repetitions"])
            self.assertFalse(lineage["source_trials"][0]["evidence_present"])
            self.assertEqual("unknown", lineage["source_trials"][0]["source_status"])
            self.assertFalse(lineage["reproduction_claim"])
            self.assertNotEqual(read(source / "private.json"), read(target / "private.json"))
            self.assertEqual(before, {p.name: p.read_bytes() for p in source.iterdir()})
            validate_regression_lineage(target, plan)
            plan["local_selection"]["case_ids"] = ["B04"]
            plan["fingerprint"] = digest({k: v for k, v in plan.items() if k != "fingerprint"})
            with self.assertRaisesRegex(ValueError, "target_plan_mismatch"):
                validate_regression_lineage(target, plan)

    def test_historical_prompt_and_assertion_drift_are_explicit(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, target = Path(tmp) / "source", Path(tmp) / "target"
            source_fixture(source)
            plan = read(source / "plan.json")
            plan["suite_version"] = "chat-tools-5"
            plan["profiles"][0]["prompt"] = "historical instruction"
            plan["cases"][-1]["assertions"][0]["expected"] = ["historical"]
            plan["fingerprint"] = digest({k: v for k, v in plan.items() if k != "fingerprint"})
            save(source / "plan.json", plan)
            for name in ("batch.json", "evaluation.json"):
                value = read(source / name)
                value["binding"].update(plan_fingerprint=plan["fingerprint"], suite_version=plan["suite_version"])
                save(source / name, value)
            regression_plan(source, target, "new-regression")
            changes = read(target / "regression.json")["case_changes"][0]
            self.assertIn("assertions", changes["changed_fields"])
            self.assertIn("profile_definition", changes["changed_fields"])

    def test_unfrozen_mismatched_or_passed_source_cannot_be_promoted(self):
        for failure in ("running", "mismatch", "passed"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as tmp:
                source, target = Path(tmp) / "source", Path(tmp) / "target"
                source_fixture(source, "passed" if failure == "passed" else "failed")
                batch = read(source / "batch.json")
                if failure == "running": batch["lifecycle"] = "running"
                if failure == "mismatch": batch["binding"]["plan_fingerprint"] = "wrong"
                save(source / "batch.json", batch)
                with self.assertRaises(ValueError):
                    regression_plan(source, target, "new-regression")
                self.assertFalse(target.exists())

    def test_destination_and_trial_identity_are_guarded(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, target = Path(tmp) / "source", Path(tmp) / "target"
            source_fixture(source)
            with self.assertRaisesRegex(ValueError, "separate"):
                regression_plan(source, source / "nested", "new-regression")
            with self.assertRaisesRegex(ValueError, "new_batch"):
                regression_plan(source, target, "source-test")
            report = read(source / "evaluation.json")
            report["cases"][0]["case_id"] = "../../private"
            save(source / "evaluation.json", report)
            with self.assertRaisesRegex(ValueError, "invalid_trial"):
                regression_plan(source, target, "new-regression")
            self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
