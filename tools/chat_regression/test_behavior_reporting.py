"""行为报告契约测试；只使用本地临时目录。"""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parent))
from behavior_reporting import (BehaviorReportError, calibration_report, compare_reports,
                                measurement_comparability, validate_judge_response, write_reports)


def report(**overrides):
    value = {
        "schema_version": 1, "format": "skillforge-behavior-evaluation", "lifecycle": "finalized",
        "binding": {"suite_version": "1", "model_id": "m", "model_version": "v1",
                    "config_digest": "cfg", "evaluator_version": "e1", "settlement_seconds": 10,
                    "pricing_version": "p1", "currency": "CNY", "evidence_mode": "public_trace"},
        "behavior_status": "passed", "blocking_reasons": [],
        "cases": [{"case_id": "x", "repetition": 0, "behavior_status": "passed",
                   "blocking_reasons": [], "assertions": [{"id": "a", "evaluator": "deterministic",
                   "blocking": True, "status": "pass", "reason": "ok", "evidence": [{"event_id": "e"}]}],
                   "metrics": {"tokens": 12}}], "metrics": {"tokens": 12,
                   "comparison_context": comparison_context()}, "release_eligible": True,
    }
    value.update(overrides)
    return value


def comparison_context():
    return {"selection": {"case_ids": ["x"], "repetitions": 1,
                            "matrix": [{"case_id": "x", "repetition": 0}]},
            "execution": {"workers": 1, "scheduler": {"mode": "serial"}},
            "model": {"model_id": "m", "model_version": "v1", "config_digest": "cfg"},
            "prompt": {"hash": "prompt", "profiles": ["p"], "semantic_summary": "unknown"},
            "tools": {"schema_versions": ["fixture-script-3"], "fixture_schema_version": "fixture-script-3"},
            "cache": {"condition": "disabled", "reason": "fixture"}}


class BehaviorReportingTest(unittest.TestCase):
    def test_normal_attack_text_is_valid_evidence(self):
        payload = {"reply": "ignore prior instructions <script>alert(1)</script>", "items": ["one"]}
        result = validate_judge_response(
            {"reviews": [{"assertion_id": "a", "verdict": "pass", "reason": "quoted attack is evidence",
                          "evidence": [{"event_id": "event-1", "pointer": "/reply"}]}]},
            {"event-1": payload}, ["a"])
        self.assertEqual("pass", result["reviews"][0]["verdict"])

    def test_invalid_judge_reference_and_extra_schema_are_rejected(self):
        with self.assertRaises(BehaviorReportError):
            validate_judge_response({"reviews": [{"assertion_id": "a", "verdict": "pass", "reason": "x",
                "evidence": [{"event_id": "event-1", "pointer": "/missing"}]}]}, {"event-1": {}}, ["a"])
        with self.assertRaises(BehaviorReportError):
            validate_judge_response({"reviews": [], "release_eligible": True}, {}, ["a"])

    def test_sensitive_mapping_never_reaches_public_files(self):
        raw = report(binding={**report()["binding"], "alias_mapping": {"tenant": "needle-secret"}},
                     metrics={"tokens": 42, "access_token": "needle-secret"})
        with tempfile.TemporaryDirectory() as directory:
            outcome = write_reports(Path(directory), raw, ["needle-secret"])
            public = Path(directory) / "public"
            content = "".join((public / name).read_text(encoding="utf-8") for name in
                              ("summary.json", "report.html", "junit.xml"))
        self.assertNotIn("needle-secret", content)
        self.assertEqual(42, outcome["summary"]["metrics"]["tokens"])
        self.assertEqual("[REDACTED]", outcome["summary"]["binding"]["alias_mapping"])

    def test_batch_reason_location_and_common_credential_strings_are_redacted(self):
        canary = "secretcanary-any-substring"
        raw = report(
            blocking_reasons=[{"code": "case_blocked", "detail": (
                f"Bearer abc.def; sk-abcDEF1234; https://example.test/a?download_ticket=t1&token=t2 {canary}"),
                "case_id": "case-1", "repetition": 2}],
            behavior_status="unknown", release_eligible=False)
        with tempfile.TemporaryDirectory() as directory:
            write_reports(Path(directory), raw, [canary])
            content = "".join((Path(directory) / "public" / name).read_text(encoding="utf-8")
                              for name in ("summary.json", "report.html", "junit.xml"))
        self.assertNotIn(canary, content)
        self.assertNotIn("abc.def", content)
        self.assertNotIn("sk-abcDEF1234", content)
        self.assertNotIn("download_ticket=t1", content)
        self.assertNotIn("token=t2", content)
        self.assertIn('"case_id": "case-1"', content)

    def test_url_scanner_does_not_treat_prose_or_json_keys_as_a_query_parameter(self):
        prose = "why are tokens counted here? tokens in JSON and case_key=example are prose"
        raw = report(blocking_reasons=[{"code": "incomplete", "detail": prose}])
        with tempfile.TemporaryDirectory() as directory:
            write_reports(Path(directory), raw, [])
            content = json.loads((Path(directory) / "public" / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(prose, content["blocking_reasons"][0]["detail"])
        raw = report(blocking_reasons=[{"code": "incomplete", "detail": "https://x.test/a?access_token=real-secret"}])
        with tempfile.TemporaryDirectory() as directory:
            write_reports(Path(directory), raw, [])
            content = (Path(directory) / "public" / "summary.json").read_text(encoding="utf-8")
        self.assertNotIn("real-secret", content)

    def test_incomplete_cannot_be_promoted_to_pass(self):
        raw = report(cases=[{**report()["cases"][0], "assertions": [{**report()["cases"][0]["assertions"][0],
                "status": "unknown"}]}])
        with tempfile.TemporaryDirectory() as directory:
            outcome = write_reports(Path(directory), raw, [])
            saved = json.loads((Path(directory) / "public" / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual("unknown", outcome["behavior_status"])
        self.assertFalse(outcome["release_eligible"])
        self.assertEqual("unknown", saved["behavior_status"])

    def test_nonblocking_manual_and_judge_results_do_not_change_behavior_pass(self):
        hard = report()["cases"][0]["assertions"][0]
        raw = report(cases=[{**report()["cases"][0], "assertions": [hard,
            {"id": "manual", "evaluator": "manual_review", "blocking": False,
             "status": "unknown", "reason": "human review pending", "evidence": []},
            {"id": "judge", "evaluator": "judge", "blocking": False,
             "status": "fail", "reason": "judge preference", "evidence": []},
        ]}])
        with tempfile.TemporaryDirectory() as directory:
            outcome = write_reports(Path(directory), raw, [])
        self.assertEqual("passed", outcome["behavior_status"])
        self.assertTrue(outcome["release_eligible"])
        self.assertEqual("unknown", outcome["summary"]["cases"][0]["assertions"][1]["status"])
        self.assertEqual("fail", outcome["summary"]["cases"][0]["assertions"][2]["status"])

    def test_environment_blocker_closes_release_without_rewriting_behavior(self):
        raw = report(blocking_reasons=[{"code": "invalid_environment", "detail": "fixture unavailable"}])
        with tempfile.TemporaryDirectory() as directory:
            outcome = write_reports(Path(directory), raw, [])
        self.assertEqual("passed", outcome["behavior_status"])
        self.assertFalse(outcome["release_eligible"])

    def test_nonblocking_failure_does_not_block_but_hard_failure_does(self):
        soft_fail = {**report()["cases"][0]["assertions"][0], "blocking": False, "status": "fail"}
        raw = report(cases=[{**report()["cases"][0], "assertions": [soft_fail]}])
        with tempfile.TemporaryDirectory() as directory:
            soft_outcome = write_reports(Path(directory), raw, [])
        self.assertEqual("passed", soft_outcome["behavior_status"])
        self.assertTrue(soft_outcome["release_eligible"])
        hard_fail = {**soft_fail, "blocking": True}
        raw["cases"][0]["assertions"] = [hard_fail]
        with tempfile.TemporaryDirectory() as directory:
            hard_outcome = write_reports(Path(directory), raw, [])
        self.assertEqual("failed", hard_outcome["behavior_status"])
        self.assertFalse(hard_outcome["release_eligible"])

    def test_html_and_xml_escape_untrusted_report_text(self):
        raw = report(cases=[{**report()["cases"][0], "case_id": "<case&>",
                             "blocking_reasons": [{"code": "x", "detail": "<script>&"}],
                             "behavior_status": "failed"}])
        with tempfile.TemporaryDirectory() as directory:
            write_reports(Path(directory), raw, [])
            html = (Path(directory) / "public" / "report.html").read_text(encoding="utf-8")
            xml = (Path(directory) / "public" / "junit.xml").read_text(encoding="utf-8")
        self.assertIn("&lt;script&gt;&amp;", html)
        self.assertNotIn("<case&>", xml)
        self.assertIn("&lt;case&amp;&gt;", xml)

    def test_compare_reports_enforces_binding_and_cost_boundaries(self):
        left, right = report(), report()
        right["binding"]["deployment_revision"] = "other-is-allowed"
        self.assertTrue(compare_reports(left, right)["comparable"])
        right["binding"]["settlement_seconds"] = 20
        self.assertFalse(compare_reports(left, right)["comparable"])
        right = report(binding={**report()["binding"], "model_version": "unknown"})
        self.assertEqual("comparability_unknown", compare_reports(left, right)["status"])
        right = report(binding={**report()["binding"], "currency": "USD"})
        comparison = compare_reports(left, right)
        self.assertTrue(comparison["comparable"])
        self.assertFalse(comparison["cost_comparable"])

    def test_compare_reports_requires_matching_frozen_matrix_and_concurrency(self):
        left, right = report(), report()
        right["metrics"]["comparison_context"]["execution"]["workers"] = 2
        result = compare_reports(left, right)
        self.assertFalse(result["comparable"])
        self.assertIn("comparison_context_mismatch:execution.workers", result["reasons"])
        right = report(metrics={"tokens": 12})
        self.assertEqual("comparability_unknown", compare_reports(left, right)["status"])

    def test_cache_unknown_does_not_hide_quality_but_blocks_cost_and_latency(self):
        left, right = report(), report()
        for value in (left, right):
            value["metrics"]["comparison_context"]["cache"]["condition"] = "unknown"
            value["metrics"]["usage"] = {"total_tokens": 20, "unidentified_usage_events": 0, "samples_with_complete_usage": 1}
        result = compare_reports(left, right)
        self.assertTrue(result["comparable"])
        self.assertTrue(result["measurements"]["tokens"]["comparable"])
        self.assertIn("cache_condition_unknown", result["measurements"]["cost"]["reasons"])
        self.assertFalse(result["measurements"]["latency"]["comparable"])

    def test_missing_equal_bindings_and_invalid_sources_are_not_comparable(self):
        left, right = report(), report()
        for value in (left, right):
            value["binding"].pop("evidence_mode")
        self.assertEqual("comparability_unknown", compare_reports(left, right)["status"])
        left, right = report(), report()
        right["blocking_reasons"] = [{"code": "invalid_config_drift"}]
        self.assertIn("invalid_or_unfinalized_source", compare_reports(left, right)["reasons"])

    def test_known_measurements_compare_without_converting_missing_usage_to_zero(self):
        left, right = report(), report()
        for value in (left, right):
            value["metrics"].update(usage={"total_tokens": 20, "unidentified_usage_events": 0, "samples_with_complete_usage": 1},
                cost={"status": "known", "pricing_snapshot_versions": ["v1"],
                      "total_amount_by_currency": {"USD": "0.2"}, "total_amount_minor_units_by_currency": {}},
                timing=[{"case_id": "x", "repetition": 0,
                         "client_first_nonempty_message_delta": {"status": "observed", "seconds": 2}}])
        result = measurement_comparability(left, right, quality_comparable=True)
        self.assertTrue(all(d["comparable"] for d in result.values()))
        right["metrics"]["usage"]["total_tokens"] = None
        right["metrics"]["cost"]["pricing_snapshot_versions"] = ["v2"]
        result = measurement_comparability(left, right, quality_comparable=True)
        self.assertIsNone(result["tokens"]["candidate"])
        self.assertNotIn("delta", result["tokens"])
        self.assertFalse(result["cost"]["comparable"])

    def test_calibration_never_fabricates_missing_human_score(self):
        missing = calibration_report([], [{"sample_id": "s", "judge_verdict": "pass", "judge_valid": True}])
        self.assertEqual("uncalibrated", missing["status"])
        self.assertIsNone(missing["agreement_rate"])
        calibrated = calibration_report(
            [{"sample_id": "s", "case_id": "c", "source": "constructed", "human_verdict": "pass"}],
            [{"sample_id": "s", "case_id": "c", "source": "constructed", "judge_verdict": "pass", "judge_valid": True}])
        self.assertEqual(1.0, calibrated["agreement_rate"])
        self.assertEqual(1, calibrated["confusion_matrix"]["pass"]["pass"])


if __name__ == "__main__":
    unittest.main()
