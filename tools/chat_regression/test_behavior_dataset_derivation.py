"""D08 长 CSV 确定性派生、来源绑定与冻结回归。"""
from __future__ import annotations

import csv
from copy import deepcopy
from io import StringIO
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parent))

from behavior_contract import digest
from behavior_dataset import (CATEGORY_IDS, LONG_RECONCILIATION, candidate_expected,
                              derive_dataset, freeze_dataset, load_dataset,
                              source_fixture, validate_candidate)
from runner import read, save
from test_behavior_dataset import candidate


def csv_rows(candidate_value, ref):
    content = next(material["content"] for material in candidate_value["materials"]
                   if material["ref"] == ref)
    return content, [{"id": row["id"], "amount": int(row["amount"])}
                     for row in csv.DictReader(StringIO(content))]


class DatasetDerivationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        base = Path(self.directory.name)
        self.parent, self.target = base / "parent", base / "derived"
        self.parent.mkdir()
        self.parent_candidates = [candidate(category, variant)
                                  for category in CATEGORY_IDS for variant in range(1, 6)]
        save(self.parent / "candidates.json", self.parent_candidates)
        save(self.parent / "all-attempts.json", {"attempts": ["original-model-call"]})
        save(self.parent / "generation-cost.json", {"tokens": 1234})
        save(self.parent / "generation-summary.json", {"requests": 12})
        self.parent_dataset = freeze_dataset(self.parent, "product-v2")

    def derive(self):
        return derive_dataset(self.parent, self.target, "product-v3-d08-long")

    def test_old_frozen_dataset_stays_unchanged_and_loadable(self):
        before = deepcopy(self.parent_dataset)

        frozen = self.derive()

        self.assertEqual(before, load_dataset(self.parent))
        self.assertNotIn("derivation_digest", before)
        self.assertEqual("product-v3-d08-long", frozen["dataset_version"])
        self.assertEqual(frozen, load_dataset(self.target))

    def test_all_five_d08_candidates_use_real_long_csv_and_independent_totals(self):
        self.derive()
        derived = read(self.target / "candidates.json")
        d08 = [row for row in derived if row["category"] == "D08"]

        self.assertEqual(5, len(d08))
        for row in d08:
            with self.subTest(variant=row["variant"]):
                orders_content, orders = csv_rows(row, "orders")
                ledger_content, ledger = csv_rows(row, "ledger")
                self.assertGreater(len(orders_content), 4096)
                self.assertGreater(len(ledger_content), 4096)
                self.assertEqual(404, len(orders))
                self.assertEqual(403, len(ledger))
                self.assertEqual("TX0001", orders[4]["id"])
                self.assertEqual("TX0400", orders[-1]["id"])
                self.assertEqual(orders[4:], ledger[3:])
                unique_orders = {item["id"]: item["amount"] for item in orders}
                unique_ledger = {item["id"]: item["amount"] for item in ledger}
                expected = candidate_expected(row)
                self.assertEqual(sum(item["amount"] for item in orders), expected["raw_total"])
                self.assertEqual(sum(unique_orders.values()), expected["unique_total"])
                self.assertEqual(sum(unique_orders.values()) - sum(unique_ledger.values()),
                                 expected["difference"])
                self.assertEqual(10, expected["difference"])
                self.assertEqual(digest(source_fixture("D08", row["variant"], LONG_RECONCILIATION)),
                                 expected["source_digest"])
                self.assertNotRegex(row["goal"] + "\n" + "\n".join(row["user_turns"]),
                                    "预算|截止|起订")
                self.assertNotIn("9999999", row["reference_answer"])

    def test_non_d08_candidates_and_generation_accounts_are_preserved(self):
        self.derive()
        derived = read(self.target / "candidates.json")
        original = {row["candidate_id"]: row for row in self.parent_candidates if row["category"] != "D08"}

        self.assertEqual(list(original.values()), [row for row in derived if row["category"] != "D08"])
        for name in ("all-attempts.json", "generation-cost.json", "generation-summary.json"):
            self.assertEqual(read(self.parent / name), read(self.target / name))
        receipt = read(self.target / "dataset-derivation.json")
        self.assertEqual(0, receipt["generation_requests"])
        self.assertEqual(self.parent_dataset["fingerprint"], receipt["parent_dataset_fingerprint"])
        self.assertEqual(60, len(receipt["parent_candidates"]))

    def test_modified_material_and_derivation_hashes_are_rejected(self):
        self.derive()
        derived = next(row for row in read(self.target / "candidates.json") if row["category"] == "D08")
        receipt = read(self.target / "dataset-derivation.json")

        changed = deepcopy(derived)
        changed["materials"][1]["content"] += "\nTX9999,1"
        with self.assertRaisesRegex(ValueError, "modified_source"):
            validate_candidate(changed, receipt)
        changed = deepcopy(derived)
        changed["derivation"]["source_digest"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "derived_source_drift"):
            validate_candidate(changed, receipt)
        changed = deepcopy(derived)
        changed["derivation"]["parent_candidate_digest"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "parent_candidate_mismatch"):
            validate_candidate(changed, receipt)

    def test_derivation_receipt_is_bound_into_frozen_fingerprint(self):
        self.derive()
        receipt = read(self.target / "dataset-derivation.json")
        receipt["parent_candidates"][0]["reference_answer"] = "事后篡改"
        save(self.target / "dataset-derivation.json", receipt)

        with self.assertRaisesRegex(ValueError, "dataset_derivation_changed"):
            load_dataset(self.target)

    def test_repeated_derivation_and_existing_target_are_rejected(self):
        self.derive()
        with self.assertRaisesRegex(ValueError, "target_exists"):
            derive_dataset(self.parent, self.target, "another-version")
        another = self.target.parent / "derived-again"
        with self.assertRaisesRegex(ValueError, "parent_already_derived"):
            derive_dataset(self.target, another, "another-version")
        with self.assertRaisesRegex(ValueError, "target_is_parent"):
            derive_dataset(self.parent, self.parent, "another-version")


if __name__ == "__main__":
    unittest.main()
