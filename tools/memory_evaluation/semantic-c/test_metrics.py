"""指标分母与 UNKNOWN 边界；不产生人工语义标签或模型效果证据。"""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from metrics import metric, summarize, reporting


class MetricsTest(unittest.TestCase):
    def test_unknown_never_becomes_zero_failure_rate(self):
        observed = metric(["PASS", "UNKNOWN"], .1)
        self.assertEqual("UNKNOWN", observed["outcome"])
        self.assertIsNone(observed["failureRate"])
        self.assertEqual(2, observed["denominator"])

    def test_critical_failure_cannot_be_hidden_by_unrun_positions(self):
        self.assertEqual("FAIL", metric(["FAIL", "UNKNOWN"], 0)["outcome"])

    def test_empty_population_is_not_perfect_recall(self):
        self.assertEqual("NOT_APPLICABLE", metric([], .1)["outcome"])
        self.assertIsNone(metric([], .1)["failureRate"])

    def test_frozen_rate_boundary(self):
        self.assertEqual("PASS", metric(["PASS"] * 9 + ["FAIL"], .1)["outcome"])
        self.assertEqual("FAIL", metric(["PASS"] * 8 + ["FAIL"] * 2, .1)["outcome"])

    def test_unrun_batch_retains_all_expected_populations_as_unknown(self):
        suite_file = Path(__file__).parent / "heldout.json"
        suite_bytes = suite_file.read_bytes()
        suite = json.loads(suite_bytes)
        with tempfile.TemporaryDirectory() as directory:
            batch_path = Path(directory) / "batch.json"
            batch = {"schemaVersion": 1, "suiteId": suite["suiteId"], "batchId": "protocol-only",
                     "suiteHash": hashlib.sha256(suite_bytes).hexdigest(), "plannedSamples": 16,
                     "outcome": "STOPPED", "samples": [{"sampleId": item["id"] + "-1", "path": "sample-" + item["id"] + "-1.json",
                                                           "outcome": "NOT_RUN"} for item in suite["scenarios"]]}
            batch_path.write_text(json.dumps(batch), encoding="utf8")
            loaded = reporting.load_batch(batch_path, suite_file)
            result = summarize(loaded, {})
            self.assertEqual("UNKNOWN", result["metrics"]["stableOmission"]["outcome"])
            expected_stable = sum(fact["temporalKind"] == "stable" for item in suite["scenarios"] for fact in item["expected"]["requiredFacts"])
            self.assertEqual(expected_stable, result["metrics"]["stableOmission"]["denominator"])
            self.assertEqual("UNKNOWN", result["metrics"]["sourceSupport"]["outcome"])
            self.assertEqual("UNKNOWN", result["systemAgentUsage"]["status"])


if __name__ == "__main__":
    unittest.main()
