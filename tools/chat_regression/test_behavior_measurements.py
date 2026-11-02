"""冻结行为证据的报告计量回归。"""
from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parent))

from behavior_measurements import enrich_report


def _report(status="passed"):
    return {"schema_version": 1, "format": "skillforge-behavior-evaluation", "lifecycle": "finalized",
            "binding": {"model_id": "m", "model_version": "v1", "config_digest": "cfg",
                        "prompt_hash": "prompt", "tool_schema_version": "fixture-script-3"},
            "behavior_status": status, "blocking_reasons": [], "release_eligible": status == "passed",
            "cases": [{"case_id": "B01", "repetition": 1, "behavior_status": status,
                       "blocking_reasons": [], "assertions": [], "metrics": {}}],
            "metrics": {"planned": 1, "executed": 1, "evaluated": 1}}


class BehaviorMeasurementsTest(unittest.TestCase):
    def _root(self):
        directory = tempfile.TemporaryDirectory()
        root = Path(directory.name)
        (root / "cases/B01/1").mkdir(parents=True)
        (root / "plan.json").write_text(json.dumps({"repetitions": 1,
            "profiles": [{"key": "p"}], "cases": [{"id": "B01", "profile": "p",
            "tool_schema_version": "fixture-script-3"}]}), encoding="utf-8")
        (root / "batch.json").write_text(json.dumps({"budget": {"turns": 1}}), encoding="utf-8")
        (root / "cases/B01/1/state.json").write_text(json.dumps({"turns": {"0": {}}}), encoding="utf-8")
        return directory, root

    def test_deduplicates_attempt_usage_and_keeps_subsets_out_of_total(self):
        directory, root = self._root()
        self.addCleanup(directory.cleanup)
        usage = {"eventId": "usage-1", "usageKnown": True, "tokens": 12, "inputTokens": 7,
                 "outputTokens": 5, "cachedInputTokens": 2, "cacheWriteInputTokens": 1,
                 "reasoningOutputTokens": 3, "totalAmount": "1.25", "currency": "USD",
                 "pricingSnapshotVersion": "price-1", "usageSource": "PROVIDER_REPORTED"}
        evidence = {"case_id": "B01", "repetition": 1, "events": [], "terminal": {},
                    "metrics": {"attempts": [{"attemptId": "a", "usage": [usage]},
                                                {"attemptId": "a", "usage": [usage]}]}}
        (root / "cases/B01/1/evidence.json").write_text(json.dumps(evidence), encoding="utf-8")
        original = _report()
        enriched = enrich_report(root, original)
        self.assertEqual(original["behavior_status"], enriched["behavior_status"])
        self.assertEqual(1, enriched["metrics"]["usage"]["usage_events"])
        self.assertEqual(1, enriched["metrics"]["usage"]["samples_with_complete_usage"])
        self.assertEqual(12, enriched["metrics"]["usage"]["total_tokens"])
        self.assertEqual(2, enriched["metrics"]["usage"]["cached_input_tokens"])
        self.assertEqual("1.25", enriched["metrics"]["cost"]["total_amount_by_currency"]["USD"])
        self.assertEqual("1.25", enriched["metrics"]["cost"]["cost_per_success_by_currency"]["USD"])
        self.assertEqual("known", enriched["metrics"]["cache_measurement"]["status"])
        self.assertEqual(1, enriched["metrics"]["attempt_diagnostics"]["unique_attempts"])

    def test_unknown_cost_stays_unknown_and_failed_cost_is_included_per_success(self):
        directory, root = self._root()
        self.addCleanup(directory.cleanup)
        (root / "cases/B01/1/evidence.json").write_text(json.dumps({"case_id": "B01", "repetition": 1,
            "events": [], "terminal": {}, "metrics": {"attempts": [{"attemptId": "known", "usage": [
                {"usageKnown": True, "eventId": "known", "totalAmount": "2.00", "currency": "USD"}]},
                {"attemptId": "unknown", "usage": [{"usageKnown": False, "eventId": "unknown", "currency": "USD"}]}]}}), encoding="utf-8")
        report = _report()
        report["cases"].append({"case_id": "B02", "repetition": 1, "behavior_status": "failed",
            "blocking_reasons": [], "assertions": [], "metrics": {}})
        enriched = enrich_report(root, report)
        self.assertEqual("unknown", enriched["metrics"]["cost"]["status"])
        self.assertIsNone(enriched["metrics"]["cost"]["cost_per_success_by_currency"])
        self.assertEqual(1, enriched["metrics"]["sample_status"]["failed"])
        self.assertEqual(1, enriched["metrics"]["sample_status_by_case"]["B02"]["missing_evidence"])
        self.assertEqual(0, enriched["metrics"]["usage"]["samples_with_complete_usage"])

    def test_missing_attempt_usage_is_not_covered_by_another_known_attempt(self):
        directory, root = self._root()
        self.addCleanup(directory.cleanup)
        (root / "cases/B01/1/evidence.json").write_text(json.dumps({"case_id": "B01", "repetition": 1,
            "metrics": {"attempts": [{"attemptId": "known", "usage": [{"usageKnown": True, "tokens": 10}]},
                                     {"attemptId": "missing", "usage": []}]}}), encoding="utf-8")
        result = enrich_report(root, _report())
        self.assertEqual(10, result["metrics"]["usage"]["total_tokens"])
        self.assertEqual(0, result["metrics"]["usage"]["samples_with_complete_usage"])

    def test_timing_never_mixes_client_elapsed_with_server_timestamps(self):
        directory, root = self._root()
        self.addCleanup(directory.cleanup)
        (root / "cases/B01/1/turn-00.sse.jsonl").write_text(
            json.dumps({"event": "message.delta", "data": {"content": "hello"}, "elapsed_seconds": 0.5}) + "\n",
            encoding="utf-8")
        events = [{"event_id": "model", "event_type": "agent.runtime-diagnostic",
                   "payload": {"metrics": {"milestone": "FIRST_MODEL_EVENT", "elapsedNanos": "603318701"}}},
                  {"event_id": "done", "event_type": "run.completed", "run_id": "run", "occurred_at": "2026-01-01T00:00:02Z"},
                  {"event_id": "cancelled", "event_type": "run.cancelled", "run_id": "run", "occurred_at": "2026-01-01T00:00:03Z"}]
        (root / "cases/B01/1/evidence.json").write_text(json.dumps({"case_id": "B01", "repetition": 1,
            "events": events, "terminal": {"run_id": "run", "status": "completed"}, "metrics": {"attempts": []}}), encoding="utf-8")
        timing = enrich_report(root, _report())["metrics"]["timing"][0]
        self.assertEqual(0.5, timing["client_first_nonempty_message_delta"]["seconds"])
        self.assertEqual(603318701, timing["model_first_event"]["elapsed_nanos"])
        self.assertEqual("turn-00.sse.jsonl", timing["client_first_nonempty_message_delta"]["source_file"])
        self.assertEqual("quiescence_not_observed_at_freeze", timing["quiescence_upper_bound"]["reason"])
        self.assertEqual("observed", timing["cancellation_confirmed"]["status"])
        self.assertEqual("cancel_not_requested", timing["cancel_requested"]["reason"])

    def test_unreliable_first_delta_or_event_time_is_reported_missing(self):
        directory, root = self._root()
        self.addCleanup(directory.cleanup)
        (root / "cases/B01/1/turn-00.sse.jsonl").write_text(
            json.dumps({"event": "message.delta", "data": {"content": "first"}, "elapsed_seconds": None}) + "\n" +
            json.dumps({"event": "message.delta", "data": {"content": "later"}, "elapsed_seconds": 1}) + "\n",
            encoding="utf-8")
        (root / "cases/B01/1/evidence.json").write_text(json.dumps({"case_id": "B01", "repetition": 1,
            "events": [{"event_id": "model", "event_type": "agent.runtime-diagnostic",
                        "payload": {"metrics": {"milestone": "FIRST_MODEL_EVENT", "elapsedNanos": "not-a-number"}}}],
            "terminal": {}, "metrics": {"attempts": []}}), encoding="utf-8")
        timing = enrich_report(root, _report())["metrics"]["timing"][0]
        self.assertEqual("sse_elapsed_seconds_invalid", timing["client_first_nonempty_message_delta"]["reason"])
        self.assertEqual("first_model_event_elapsed_nanos_invalid", timing["model_first_event"]["reason"])

    def test_model_result_or_attempt_cannot_substitute_first_model_milestone(self):
        directory, root = self._root()
        self.addCleanup(directory.cleanup)
        (root / "cases/B01/1/evidence.json").write_text(json.dumps({"case_id": "B01", "repetition": 1,
            "events": [{"event_id": "result", "event_type": "agent.model-result", "occurred_at": "2026-01-01T00:00:01Z",
                        "detail": {"attempts": [{"callKind": "MODEL_STREAM", "attemptId": "a"}]}}],
            "terminal": {}, "metrics": {"attempts": []}}), encoding="utf-8")
        timing = enrich_report(root, _report())["metrics"]["timing"][0]
        self.assertEqual("first_model_event_milestone_not_recorded", timing["model_first_event"]["reason"])

    def test_sse_turn_files_are_ordered_numerically_and_quiescence_is_only_an_upper_bound(self):
        directory, root = self._root()
        self.addCleanup(directory.cleanup)
        for turn, elapsed in ((10, 10), (2, 2)):
            (root / f"cases/B01/1/turn-{turn:02d}.sse.jsonl").write_text(
                json.dumps({"event": "message.delta", "data": {"content": "x"}, "elapsed_seconds": elapsed}) + "\n",
                encoding="utf-8")
        (root / "cases/B01/1/evidence.json").write_text(json.dumps({"case_id": "B01", "repetition": 1,
            "events": [], "terminal": {}, "frozen_at": "2026-01-01T00:00:03Z",
            "completeness": {"quiescence": True}, "metrics": {"attempts": []}}), encoding="utf-8")
        timing = enrich_report(root, _report())["metrics"]["timing"][0]
        self.assertEqual(2, timing["client_first_nonempty_message_delta"]["turn"])
        self.assertEqual("observed_upper_bound", timing["quiescence_upper_bound"]["status"])


if __name__ == "__main__":
    unittest.main()
