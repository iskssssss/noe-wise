"""业务请求修订保留原资料、来源及硬预期，冻结后不可漂移。"""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest

from behavior_contract import digest
from behavior_dataset import (CATEGORY_IDS, candidate_expected, derive_dataset,
                              freeze_dataset, load_dataset)
from behavior_dataset_repair import repair_dataset, validate_repair_receipt
from behavior_product_suite import make_product_plan
from runner import read, save
from test_behavior_dataset import candidate


class DatasetRepairTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.parent, self.target = base / "parent", base / "new"
        original = base / "original"
        original.mkdir()
        rows = [candidate(cid, variant) for cid in CATEGORY_IDS for variant in range(1, 6)]
        save(original / "candidates.json", rows)
        freeze_dataset(original, "original")
        derive_dataset(original, self.parent, "long-d08")
        # 构造曾冻结但被当前规则拒绝的旧池；历史文件完整绑定，当前不再放行。
        rows = read(self.parent / "candidates.json")
        for row in rows:
            if row["category"] in {"D06", "D11"}:
                row["goal"] = "生成测试任务"
                row["user_turns"] = ["请生成测试用例。"]
        save(self.parent / "candidates.json", rows)
        ds = read(self.parent / "dataset.json")
        ds["candidates_digest"] = digest(rows)
        by_id = {c["candidate_id"]: c for c in rows}
        ds["selected"] = [by_id[c["candidate_id"]] for c in ds["selected"]]
        ds["fingerprint"] = digest({k: v for k, v in ds.items() if k != "fingerprint"})
        save(self.parent / "dataset.json", ds)

    def test_repair_preserves_parent_materials_expectations_and_d08(self):
        before = {p.name: p.read_bytes() for p in self.parent.iterdir()}
        frozen = repair_dataset(self.parent, self.target, "business-v5")
        self.assertEqual(frozen, load_dataset(self.target))
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.parent.iterdir()})
        parents = read(self.parent / "candidates.json")
        children = read(self.target / "candidates.json")
        self.assertEqual(60, len(children))
        for old, new in zip(parents, children):
            self.assertEqual(candidate_expected(old), candidate_expected(new))
            self.assertEqual(old["materials"], new["materials"])
            self.assertEqual(old["provenance"], new["provenance"])
            if old["category"] not in {"D06", "D11"}:
                self.assertEqual(old, new)
            else:
                self.assertNotEqual(old["user_turns"], new["user_turns"])
                self.assertEqual(digest(old), new["derivation"]["parent_candidate_digest"])
        self.assertEqual(12, len(make_product_plan("offline", frozen)["cases"]))
        receipt = read(self.target / "dataset-derivation.json")
        self.assertEqual(0, receipt["generation_requests"])

    def test_reject_parent_tamper_before_writing(self):
        rows = read(self.parent / "candidates.json")
        rows[0]["goal"] = "篡改"
        save(self.parent / "candidates.json", rows)
        with self.assertRaisesRegex(ValueError, "parent_binding_changed"):
            repair_dataset(self.parent, self.target, "invalid")
        self.assertFalse(self.target.exists())

    def test_reject_changes_to_rewritten_or_untouched_candidates(self):
        repair_dataset(self.parent, self.target, "business-v5")
        receipt = read(self.target / "dataset-derivation.json")
        rows = read(self.target / "candidates.json")
        for index in (0, 25, 50):
            changed = deepcopy(rows)
            changed[index]["user_turns"] = ["修改后任务"]
            with self.assertRaisesRegex(ValueError, "repair_candidate_changed"):
                validate_repair_receipt(receipt, changed)
        receipt["parent_files"]["candidates.json"][0]["goal"] = "篡改来源"
        with self.assertRaisesRegex(ValueError, "parent_files_changed"):
            validate_repair_receipt(receipt, rows)

    def test_frozen_receipt_tamper_overwrite_and_repeat_repair_rejected(self):
        repair_dataset(self.parent, self.target, "business-v5")
        with self.assertRaisesRegex(ValueError, "target_exists"):
            repair_dataset(self.parent, self.target, "another")
        with self.assertRaisesRegex(ValueError, "already_repaired"):
            repair_dataset(self.target, self.target.parent / "again", "another")
        receipt = read(self.target / "dataset-derivation.json")
        receipt["generation_requests"] = 1
        save(self.target / "dataset-derivation.json", receipt)
        with self.assertRaisesRegex(ValueError, "dataset_derivation_changed"):
            load_dataset(self.target)


if __name__ == "__main__":
    unittest.main()
