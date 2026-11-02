"""调用者数据集必须明确且摘要匹配；缺配置不能产生网络或账本副作用。"""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import live_behavior as live
import live_behavior_inputs as inputs
import live_behavior_preflight as preflight
from behavior_dataset import CATEGORY_IDS, freeze_dataset
from test_behavior_dataset import candidate


class ExplicitProductDatasetTest(unittest.TestCase):
    def test_missing_configuration_stops_main_before_environment_or_network(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(live, "load_environment") as environment, \
                patch.object(live, "login") as login, patch.object(live.ledger, "reserve") as reserve:
            with self.assertRaisesRegex(ValueError, "explicit_product_dataset_and_fingerprint_required"):
                live.main(["--suite", "product", "--ready-receipt", "not-read.json"])
        environment.assert_not_called()
        login.assert_not_called()
        reserve.assert_not_called()

    def test_missing_configuration_stops_direct_round_before_login(self):
        with patch.object(live, "login") as login, patch.object(live.ledger, "reserve") as reserve:
            with self.assertRaisesRegex(ValueError, "explicit_product_dataset_and_fingerprint_required"):
                live.run_round("product", 1, {}, {}, "candidate")
        login.assert_not_called()
        reserve.assert_not_called()

    def test_missing_configuration_stops_preflight_before_network_or_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            output = repository / ".runtime-logs/full-project-test-20261012/preflight"
            with patch.dict(os.environ, {}, clear=True), patch.object(preflight, "REPO", repository), \
                    patch.object(preflight, "load_environment") as environment, patch.object(preflight, "Api") as api:
                with self.assertRaisesRegex(ValueError, "explicit_product_dataset_and_fingerprint_required"):
                    preflight.main(["--output", str(output), "--product-ui-diagnostic", "1"])
            environment.assert_not_called()
            api.assert_not_called()
            self.assertFalse(output.exists())

    def test_optional_dataset_receipt_arrival_changes_frozen_batch_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ("plan.json", "private.json", "resources.json", "configuration.json",
                         "browser-resources-private.json", *inputs.PRODUCT_FILES[:3]):
                (root / name).write_text("{}", encoding="utf-8")
            monitor = inputs.InputManifestMonitor(lambda: {"fingerprint": "fixed"}, root)
            monitor.freeze_batch(product=True)
            self.assertTrue(monitor.check())
            (root / "generation-summary.json").write_text("{}", encoding="utf-8")
            self.assertFalse(monitor.check())

    def test_explicit_frozen_dataset_copies_bytes_and_rejects_fingerprint_drift(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, destination = root / "source", root / "copy"
            source.mkdir()
            destination.mkdir()
            (source / "candidates.json").write_text(json.dumps([candidate(cid) for cid in CATEGORY_IDS]), encoding="utf-8")
            frozen = freeze_dataset(source, "explicit-test-v1")
            environment = {"FULL_TEST_PRODUCT_DATASET": str(source),
                           "FULL_TEST_PRODUCT_FINGERPRINT": frozen["fingerprint"]}
            self.assertEqual((source.resolve(), frozen), inputs.product_dataset(environment))
            with patch.dict(os.environ, environment, clear=True):
                live.copy_product_dataset(destination)
            for name in ("dataset.json", "candidates.json", "validation.json"):
                self.assertEqual((source / name).read_bytes(), (destination / name).read_bytes())
            receipt = json.loads((destination / "dataset-source-receipt.json").read_text(encoding="utf-8"))
            self.assertEqual(frozen["fingerprint"], receipt["fingerprint"])
            self.assertEqual(0, receipt["new_generation_requests"])
            environment["FULL_TEST_PRODUCT_FINGERPRINT"] = "0" * 64
            with self.assertRaisesRegex(ValueError, "explicit_product_dataset_fingerprint_mismatch"):
                inputs.product_dataset(environment)


if __name__ == "__main__":
    unittest.main()
