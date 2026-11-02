"""C12 判据自测，不代表真实模型或 Runtime 场景通过。"""
import copy
import unittest

from complex_context_case import build_fixture, evaluate_context_case


class ComplexContextCaseTest(unittest.TestCase):
    def setUp(self):
        self.fixture = build_fixture(1)
        self.facts = {"context_events": [{"event_id": "view:1", "recovery": "REFERENCES",
                                          "covered_count": 20, "sequence": 10}],
                      "source_operation_id": "original", "material_sha256": self.fixture["sha256"], "source_dispatches": [
                          {"operation_id": "original", "sequence": 2}],
                      "reference_reads": [{"source_operation_id": "original", "offset": 0,
                                           "next_offset": 100, "sequence": 13,
                                           "content": "机构03；人数=103；校验码=C12-03-2718；\n机构17；人数=117；校验码=C12-17-2718；"}],
                      "constraint_sequence": 12, "final_sequence": 15,
                      "answer": self.fixture["expected"], "usage_status": "KNOWN"}

    def test_requires_actual_recovery_not_long_material_or_correct_text(self):
        self.facts["context_events"] = []
        self.assertEqual("NOT_RUN", evaluate_context_case(self.fixture, self.facts)["status"])

    def test_missing_or_unknown_evidence_cannot_pass(self):
        self.assertEqual("UNKNOWN", evaluate_context_case(self.fixture, {})["status"])
        self.facts["usage_status"] = "UNKNOWN"
        self.assertEqual("UNKNOWN", evaluate_context_case(self.fixture, self.facts)["status"])

    def test_exact_scope_read_and_original_dispatch_are_required(self):
        self.assertEqual("PASS", evaluate_context_case(self.fixture, self.facts)["status"])
        for field, value in (("answer", {"合计": 220}), ("reference_reads", []),
                             ("source_dispatches", self.facts["source_dispatches"] * 2)):
            with self.subTest(field=field):
                changed = copy.deepcopy(self.facts)
                changed[field] = value
                self.assertEqual("FAILED", evaluate_context_case(self.fixture, changed)["status"])

    def test_read_before_correction_does_not_prove_latest_constraint_recovery(self):
        self.facts["reference_reads"][0]["sequence"] = 11
        self.assertEqual("FAILED", evaluate_context_case(self.fixture, self.facts)["status"])

    def test_previously_read_target_and_corrected_target_do_not_require_duplicate_reads(self):
        original = self.facts["reference_reads"][0]
        self.facts["reference_reads"] = [
            {**original, "sequence": 11, "content": "机构03；人数=103；校验码=C12-03-2718；"},
            {**original, "sequence": 13, "offset": 500, "next_offset": 550,
             "content": "机构17；人数=117；校验码=C12-17-2718；", "find": "机构17；", "match_found": True}]
        self.facts["answer"] = {**self.fixture["expected"], "合计": {"人数": 220}}
        self.assertEqual("PASS", evaluate_context_case(self.fixture, self.facts)["status"])
        for change in ("missing_original", "wrong_source", "empty_page", "wrong_total", "extra_scope", "extra_total_field", "find_miss", "wrong_find"):
            changed = copy.deepcopy(self.facts)
            if change == "missing_original": changed["reference_reads"] = changed["reference_reads"][1:]
            if change == "wrong_source": changed["reference_reads"][1]["source_operation_id"] = "other"
            if change == "empty_page": changed["reference_reads"][1]["next_offset"] = 500
            if change == "wrong_total": changed["answer"]["合计"]["人数"] = 221
            if change == "extra_scope": changed["answer"]["机构04"] = {"人数": 104}
            if change == "extra_total_field": changed["answer"]["合计"]["other"] = 220
            if change == "find_miss": changed["reference_reads"][1]["match_found"] = False
            if change == "wrong_find": changed["reference_reads"][1]["find"] = "机构18；"
            with self.subTest(change=change):
                self.assertEqual("FAILED", evaluate_context_case(self.fixture, changed)["status"])


if __name__ == "__main__":
    unittest.main()
