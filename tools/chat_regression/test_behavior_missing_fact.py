"""缺失事实声明必须绑定主题；含糊与矛盾不能因命中否定词而通过。"""
import unittest

from behavior_dataset import independent_expected
from behavior_product_eval import evaluate_product


class MissingFactTests(unittest.TestCase):
    def check_answer(self, text, expected, bound=True):
        case = {"id": "D04", "expected": independent_expected("D04", 1),
                "candidate": {"candidate_id": "D04-test"}}
        evidence = {"answer": text, "answer_event_id": "answer" if bound else None}
        checks = evaluate_product(case, evidence)["assertions"]
        check = next(c for c in checks if c["id"] == "missing_fact_not_invented")
        self.assertEqual(expected, check["status"], text)
        self.assertTrue(check["blocking"])

    def test_explicit_absence_variants(self):
        for text in ("国际订单运费无任何规定，不得编造。", "国际订单运费无证据支持，不予推定。",
                     "国际订单运费没有规定。", "国际订单运费未明确规定。",
                     "**国际订单运费**：暂无资料。", "国际订单运费无法确定。",
                     "国际订单运费缺乏证据。", "国际订单运费没有任何证据。"):
            with self.subTest(text=text):
                self.check_answer(text, "pass")

    def test_observed_answer_clauses_with_unrelated_amounts(self):
        self.check_answer("预算：700元；截止日期：2026-11-12。\n"
                          "- 国际订单运费无任何规定，不得编造。\n"
                          "未提供单价，无法精确核算。国际订单运费无证据支持，不予推定。", "pass")

    def test_unsupported_or_unrelated_negation_is_unknown(self):
        for text in ("没有资料说明折扣。", "国际订单运费并非没有规定。", "国际订单运费不是无法确定。",
                     "国际订单运费无证据支持，但可以按惯例推定。", "国际订单运费不明确，或许另有规定。",
                     "国际订单运费无证据支持。该费用按照订单金额计算。",
                     "国际订单运费没有规定。按20元收取。", "国际订单运费没有规定。免费。",
                     "", "国内订单运费没有规定。"):
            with self.subTest(text=text):
                self.check_answer(text, "unknown")

    def test_invented_amount_or_contradictory_claim_blocks(self):
        for text in ("国际订单运费20元。", "国际订单运费免费。",
                     "国际订单运费无证据支持，但运费为20元。",
                     "国际订单运费无证据支持。运费20元。",
                     "国际订单运费无证据支持。该费用为20元。",
                     "国际订单运费包邮。国际订单运费没有规定。"):
            with self.subTest(text=text):
                self.check_answer(text, "fail")

    def test_unbound_answer_remains_unknown(self):
        self.check_answer("国际订单运费没有规定。", "unknown", bound=False)


if __name__ == "__main__":
    unittest.main()
