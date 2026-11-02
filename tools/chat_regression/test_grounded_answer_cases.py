"""验证新增场景的独立预期和冻结条件，不调用模型。"""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch
from complex_artifact_case import provision_case, run_case
from grounded_answer_cases import CASES, QUESTIONS, expectations


class GroundedAnswerCasesTest(unittest.TestCase):
    def test_zero_and_negative_duplicates_have_different_numeric_effects(self):
        zero = expectations(CASES["P1-ZERO"])
        negative = expectations(CASES["P1-NEGATIVE"])
        self.assertEqual([0, 0], zero["deduplication_changes"])
        self.assertEqual([30, 30], negative["deduplication_changes"])
        self.assertEqual([150, 100], [r["raw_total"] for r in zero["reports"]])
        self.assertEqual([90, 40], [r["raw_total"] for r in negative["reports"]])
        self.assertEqual([120, 70], [r["unique_total"] for r in negative["reports"]])
        for item in (zero, negative):
            self.assertEqual({"raw_total": -50, "unique_total": -50,
                              "ledger_total": -40, "difference": -10}, item["revision_changes"])

    def test_fixture_bytes_and_question_drift_stop_before_a_new_dispatch(self):
        with TemporaryDirectory() as folder:
            api, sender = Mock(), Mock(side_effect=TimeoutError("unresolved"))
            with patch("complex_artifact_case.ensure_d08_attachments", return_value=["a", "b"]):
                with self.assertRaises(TimeoutError):
                    run_case(api, agent_id="a", end_user_id="u", root=folder, send_turn=sender,
                             session_id="s", contents=CASES["P1-ZERO"], turns=QUESTIONS)
                with self.assertRaisesRegex(ValueError, "frozen_case_drift"):
                    run_case(api, agent_id="a", end_user_id="u", root=folder, send_turn=sender,
                             session_id="s", contents=CASES["P1-ZERO"], turns=["changed", QUESTIONS[1]])
                with self.assertRaisesRegex(ValueError, "fixture_drift"):
                    provision_case(folder, contents=CASES["P1-NEGATIVE"])
            self.assertEqual(1, sender.call_count)
            self.assertEqual(QUESTIONS, json.loads((Path(folder)/"c02/case.json").read_text(encoding="utf-8"))["turns"])


if __name__ == "__main__": unittest.main()
