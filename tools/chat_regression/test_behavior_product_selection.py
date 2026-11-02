"""定向复验不能改变冻结题目、重复预算或已开始的场景范围。"""
import unittest

from behavior_contract import experiments
from behavior_dataset import CATEGORY_IDS
from behavior_product_runner import select_product_cases
from behavior_product_suite import make_product_plan
from test_behavior_dataset import candidate


class ProductSelectionTests(unittest.TestCase):
    def setUp(self):
        self.plan = make_product_plan("selection", {
            "selected": [candidate(cid) for cid in CATEGORY_IDS],
            "fingerprint": "dataset-fingerprint", "dataset_version": "dataset-v1"})

    def test_new_candidate_selects_only_failed_cases_without_changing_questions(self):
        selected = select_product_cases(self.plan, "D12,D04,D08", started=False)
        self.assertEqual([("D04", 1), ("D08", 1), ("D12", 1)], list(experiments(selected)))
        self.assertEqual(self.plan["cases"], selected["cases"])
        self.assertEqual(list(CATEGORY_IDS), self.plan["local_selection"]["case_ids"])
        self.assertEqual(self.plan["dataset_fingerprint"], selected["dataset_fingerprint"])

    def test_resume_accepts_same_selection_but_rejects_new_scope(self):
        selected = select_product_cases(self.plan, "D04,D08", started=False)
        self.assertEqual(selected, select_product_cases(selected, "D08,D04", started=True))
        self.assertEqual(selected, select_product_cases(selected, None, started=True))
        with self.assertRaisesRegex(ValueError, "product_selection_drift"):
            select_product_cases(selected, "D04,D09", started=True)

    def test_rejects_duplicate_unknown_and_empty_ids(self):
        for value in ("", "D04,D04", "D13", "D04,", "D04,,D08"):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "invalid_product_selection"):
                select_product_cases(self.plan, value, started=False)


if __name__ == "__main__":
    unittest.main()
