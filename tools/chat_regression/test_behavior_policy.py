"""影响标签选择、冻结侧文件与断言风险标注。"""
import io
import json
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
import tempfile
import unittest

from behavior_contract import STRESS_SUITE_VERSION, SUITE_VERSION
from behavior_main import (create_plan, impact_selection, local_selection, main,
                           parser, validate_frozen_impact_selection)
from behavior_policy import (parse_impacts, risk_annotations, select_case_ids,
                             selection_metadata)


class BehaviorPolicyTests(unittest.TestCase):
    def test_impact_mapping_union_and_empty_stress_preview(self):
        self.assertEqual(["B08", "B16", "B17", "B18"],
                         select_case_ids(parse_impacts("permissions"), SUITE_VERSION))
        self.assertEqual(["B02", "B03", "B04", "B05", "B06", "B07", "B08", "B09", "B10", "B11", "B12", "B13", "B14", "B15", "B16", "B17", "B18"],
                         select_case_ids(parse_impacts("cancellation,tool-contract"), SUITE_VERSION))
        self.assertEqual([], select_case_ids(parse_impacts("cancellation"), STRESS_SUITE_VERSION))
        self.assertEqual([f"S{number:02d}" for number in range(1, 7)],
                         select_case_ids(parse_impacts("model"), STRESS_SUITE_VERSION))

    def test_invalid_labels_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "invalid_impact_label"):
            parse_impacts("permissions,unknown")
        with self.assertRaisesRegex(ValueError, "duplicate_impact_label"):
            parse_impacts("permissions,permissions")

    def test_select_previews_without_root(self):
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertEqual(0, main(["select", "--suite", "stress", "--impact", "cancellation"]))
        self.assertEqual([], json.loads(output.getvalue())["case_ids"])

    def test_local_run_rejects_empty_impact_and_cases_combination(self):
        args = parser().parse_args(["local-run", "--root", "unused", "--suite", "stress",
                                    "--impact", "cancellation"])
        with self.assertRaisesRegex(ValueError, "impact_selection_empty"):
            local_selection(args)
        errors = io.StringIO()
        with redirect_stderr(errors):
            self.assertEqual(2, main(["local-run", "--root", str(Path(__file__).parent / "runs" / "policy-test"),
                                      "--impact", "permissions", "--cases", "B08"]))
        self.assertIn("impact_and_cases_are_mutually_exclusive", errors.getvalue())

    def test_impact_selection_sidecar_is_frozen_with_selected_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = selection_metadata(parse_impacts("permissions"), SUITE_VERSION)
            plan = create_plan(root, "impact-tests", {"case_ids": metadata["case_ids"],
                               "repetitions": 3, "turn_timeout_seconds": 540}, impact_selection=metadata)
            self.assertEqual(metadata, json.loads((root / "selection.json").read_text(encoding="utf-8")))
            validate_frozen_impact_selection(root, plan, metadata)
            with self.assertRaisesRegex(ValueError, "frozen_impact_selection_mismatch"):
                validate_frozen_impact_selection(root, plan, selection_metadata(parse_impacts("model"), SUITE_VERSION))

    def test_risk_annotations_are_per_assertion_and_include_nested_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            plan = create_plan(Path(directory), "risk-tests")
        annotations = risk_annotations(plan)
        indexed = {(row["case_id"], row["assertion_id"], row["assertion_path"]): row["risk"] for row in annotations}
        self.assertEqual("P0", next(row["risk"] for row in annotations
                                    if row["case_id"] == "B08" and row["assertion_id"] == "denied_status"))
        self.assertEqual("P1", next(row["risk"] for row in annotations
                                    if row["case_id"] == "B08" and row["assertion_id"] == "query_count"))
        self.assertEqual("P2", next(row["risk"] for row in annotations
                                    if row["case_id"] == "B08" and row["assertion_id"] == "explanation"))
        self.assertTrue(any(case_id == "B06" and "/any_of/" in path
                            for case_id, _, path in indexed))

    def test_risk_annotations_project_historical_frozen_suite_without_execution_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            plan = create_plan(Path(directory), "historical-risk-tests")
        plan["suite_version"] = "chat-tools-5"
        plan["fingerprint"] = "historical-fingerprint"
        annotations = risk_annotations(plan)
        self.assertTrue(any(row["case_id"] == "B08" and row["assertion_id"] == "denied_status"
                            and row["risk"] == "P0" for row in annotations))


if __name__ == "__main__":
    unittest.main()
