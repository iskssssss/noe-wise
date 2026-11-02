"""候选校验、独立预期及冻结后漂移的回归测试。"""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest

from behavior_contract import digest, validate_plan, aggregate
from behavior_dataset import (CATEGORY_IDS, PROMPT_VERSION, TASKS, REQUIRED_MATERIALS, source_fixture,
                              independent_expected, validate_candidates, freeze_dataset, load_dataset)
from behavior_product_suite import make_product_plan
from behavior_product_eval import evaluate_product
from runner import save, read


def candidate(category="D01", variant=1):
    source = source_fixture(category, variant)
    return {"candidate_id": f"{category}-{variant:02d}", "category": category, "variant": variant,
            "goal": "完成合成业务任务", "user_turns": ["请处理这份资料。"] * TASKS[category][2],
            "materials": source["materials"], "material_refs": REQUIRED_MATERIALS[category],
            "required_capabilities": TASKS[category][1], "reference_answer": "这个草稿故意写错：总额9999999。",
            "tags": {"business": source["facts"]["business"], "input": "自然文本", "interaction": "单轮", "anomaly": "无"},
            "provenance": {"model_id": "generator-id", "model_config_digest": digest({"model": "generator"}),
                           "prompt_version": PROMPT_VERSION, "call_identity": "run:test:" + category,
                           "source_digest": digest(source)}}


class DatasetTests(unittest.TestCase):
    def test_reject_generator_meta_tasks_in_goal_or_any_turn(self):
        contaminated = [
            "请基于合成库存管理资料生成测试任务：预算900元。",
            "生成库存管理类合成测试资料任务表达，核对规则。",
            "请编写一组测试用例。", "请创建用于评测任务的提示词。",
            "Please generate a business test case.",
        ]
        for text in contaminated:
            for field in ("goal", "user_turns"):
                with self.subTest(text=text, field=field):
                    row = candidate("D11", 4)
                    row[field] = [text] if field == "user_turns" else text
                    report = validate_candidates([row])
                    self.assertEqual(["meta_task_not_business_input"], report["rejected"][0]["reasons"])
                    self.assertIn("D11", report["gaps"])
        row = candidate("D02")
        row["user_turns"][-1] = "请生成测试任务。"
        self.assertEqual("meta_task_not_business_input",
                         validate_candidates([row])["rejected"][0]["reasons"][0])

    def test_business_deliverables_and_draft_reference_remain_valid(self):
        row = candidate("D11")
        row["goal"] = "核对业务规则与订单金额"
        row["user_turns"] = ["请分别委派规则核对和订单金额汇总，等待结果后生成核对报告。"]
        row["reference_answer"] = "生成测试任务（无效参考草稿不能成为金标准）"
        self.assertEqual([], validate_candidates([row])["rejected"])
        row = candidate("D09")
        row["user_turns"] = ["请生成测试报告文件。", "请修改报告数量。"]
        self.assertEqual([], validate_candidates([row])["rejected"])

    def test_meta_task_cannot_be_frozen_or_planned(self):
        rows = [candidate(cid) for cid in CATEGORY_IDS]
        rows[10]["user_turns"] = ["请生成测试任务。"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            save(root / "candidates.json", rows)
            with self.assertRaisesRegex(ValueError, "dataset_category_gaps"):
                freeze_dataset(root, "rejected-meta-task")
            self.assertFalse((root / "dataset.json").exists())
            self.assertIn("D11", read(root / "validation.json")["gaps"])
        with self.assertRaisesRegex(ValueError, "meta_task_not_business_input"):
            make_product_plan("rejected-meta-task", {
                "selected": rows, "fingerprint": "unused", "dataset_version": "unused"})

    def test_wrong_reference_is_not_gold(self):
        rows = [candidate(cid) for cid in CATEGORY_IDS]
        report = validate_candidates(rows)
        self.assertFalse(report["gaps"])
        self.assertFalse(report["reference_answers_are_gold"])
        self.assertEqual(370, independent_expected("D03", 1)["unique_total"])

    def test_reject_invalid_structure_capability_sources_and_conditions(self):
        changes = [{"goal": None}, {"required_capabilities": ["unimplemented_magic"]},
                   {"materials": []}, {"material_refs": ["unknown"]},
                   {"user_turns": ["预算为120元"]}, {"user_turns": ["加急订单也享受折扣"]},
                   {"user_turns": ["curl https://example.invalid"]}, {"assertions": []}]
        for change in changes:
            with self.subTest(change=change):
                row = candidate()
                row.update(change)
                self.assertEqual(1, len(validate_candidates([row])["rejected"]))

    def test_duplicate_and_paraphrase_marking(self):
        first = candidate()
        duplicate = deepcopy(first)
        duplicate["candidate_id"] = "D01-extra"
        near = deepcopy(first)
        near["candidate_id"] = "D01-near"
        near["user_turns"] = ["麻烦处理这份资料。"]
        report = validate_candidates([first, duplicate, near])
        self.assertEqual("duplicate_candidate_content", report["rejected"][0]["reasons"][0])
        self.assertEqual("D01-near", report["near_duplicates"][0]["candidate_id"])

    def test_selection_deterministic_and_gaps_not_padded(self):
        report = validate_candidates([candidate("D01", 2), candidate("D01", 1)])
        self.assertEqual(["D01-01"], report["selected"])
        self.assertEqual(11, len(report["gaps"]))

    def test_freeze_rejects_overwrite_and_tamper(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            save(root / "candidates.json", [candidate(cid) for cid in CATEGORY_IDS])
            frozen = freeze_dataset(root, "test-v1")
            self.assertEqual(frozen, load_dataset(root))
            plan = make_product_plan("test-product", frozen)
            validate_plan(plan)
            self.assertEqual(1, plan["repetitions"])
            self.assertFalse(aggregate(plan, [], {"validation_scope": "local"})["release_eligible"])
            with self.assertRaisesRegex(ValueError, "already_frozen"):
                freeze_dataset(root, "test-v2")
            data = read(root / "dataset.json")
            data["expected"]["D03"]["unique_total"] = 999
            data["fingerprint"] = digest({k: v for k, v in data.items() if k != "fingerprint"})
            save(root / "dataset.json", data)
            with self.assertRaisesRegex(ValueError, "oracle_changed"):
                load_dataset(root)

    def test_product_plan_uses_attachment_contract_and_rejects_legacy_schema(self):
        plan = make_product_plan("test-product", {
            "selected": [candidate(cid) for cid in CATEGORY_IDS],
            "fingerprint": "dataset-fingerprint", "dataset_version": "dataset-v1"})
        self.assertTrue(all(row["tool_schema_version"] == "product-capabilities-2"
                            for row in plan["cases"]))
        legacy = deepcopy(plan)
        legacy["cases"][0]["tool_schema_version"] = "product-capabilities-1"
        legacy["fingerprint"] = digest({key: value for key, value in legacy.items() if key != "fingerprint"})
        with self.assertRaisesRegex(ValueError, "unsupported_product_tool_schema_use_new_batch"):
            validate_plan(legacy)

    def test_frozen_pool_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = [candidate(cid) for cid in CATEGORY_IDS]
            save(root / "candidates.json", rows)
            freeze_dataset(root, "test-v1")
            rows[0]["reference_answer"] = "事后编辑"
            save(root / "candidates.json", rows)
            with self.assertRaisesRegex(ValueError, "candidates_changed"):
                load_dataset(root)

    def test_final_text_cannot_prove_tool_execution(self):
        c = candidate("D03")
        case = {"id": "D03", "candidate": c, "expected": independent_expected("D03", 1)}
        result = evaluate_product(case, {"answer": "原始总额490；去重总额370；重复B", "answer_event_id": "done",
                                         "terminal": {"status": "completed"}})
        self.assertEqual("unknown", result["behavior_status"])
        self.assertEqual("unknown", next(a["status"] for a in result["assertions"] if a["id"] == "executed_code_execute"))

    def test_swapped_totals_do_not_pass_presence_checks(self):
        c = candidate("D03")
        result = evaluate_product({"id": "D03", "candidate": c, "expected": independent_expected("D03", 1)},
                                  {"answer": "原始总额370；去重总额490；重复B", "answer_event_id": "done"})
        self.assertEqual("failed", result["behavior_status"])
        self.assertTrue(all(a["status"] == "fail" for a in result["assertions"] if a["id"] in {"raw_total", "unique_total"}))

    def test_d08_blocks_on_two_attachment_hashes_and_same_call_calculation(self):
        c = candidate("D08")
        expected = independent_expected("D08", 1)
        evidence = {"answer": f"去重总额 {expected['unique_total']}，差额 {expected['difference']}",
                    "answer_event_id": "done", "terminal": {"status": "completed", "runtime_event_id": "terminal"},
                    "events": [], "completeness": {"trace": True},
                    "tools": [{"name": "code_execute", "status": "SUCCEEDED", "event_id": "code"}],
                    "attachment_inputs": [
                        {"ref": "orders", "id": "one", "sha256": "a" * 64,
                         "verified": True, "event_id": "code"},
                        {"ref": "ledger", "id": "two", "sha256": "b" * 64,
                         "verified": True, "event_id": "code"}],
                    "attachment_calculation_verified": True,
                    "attachment_calculation_event_id": "code", "privacy_verified": True}

        result = evaluate_product({"id": "D08", "candidate": c, "expected": expected}, evidence)
        assertions = {row["id"]: row for row in result["assertions"]}

        self.assertEqual("passed", result["behavior_status"])
        self.assertNotIn("executed_read_context_reference", assertions)
        self.assertNotIn("required_source_reads", assertions)
        self.assertNotIn("read_before_calculation", assertions)
        self.assertEqual("pass", assertions["required_attachment_hashes"]["status"])
        self.assertEqual("pass", assertions["attachment_calculation_result"]["status"])
        evidence["attachment_inputs"][1]["verified"] = False
        failed = evaluate_product({"id": "D08", "candidate": c, "expected": expected}, evidence)
        self.assertEqual("fail", next(row["status"] for row in failed["assertions"]
                                      if row["id"] == "required_attachment_hashes"))


if __name__ == "__main__":
    unittest.main()
