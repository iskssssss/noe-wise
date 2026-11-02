"""实时入口负控：仅本机假上游/临时账本，不连接模型、真实服务或数据库。"""
from __future__ import annotations

from copy import deepcopy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

sys.path.insert(0, str(Path(__file__).parent))
import budget_ledger
import live_behavior as live
from live_behavior_usage import natural, normalize_public_usage, summarize, UsageUnavailable


def attempt(identity="a1", status="SUCCEEDED", operation="model.chat"):
    return {"attempt_id": identity, "status": status, "provider": "synthetic-provider",
            "operation_type": operation}


def report(identity="1", attempt_id="a1", revision=None, prompt="20", completion="3", source="PROVIDER_REPORTED"):
    return {"id": identity, "attempt_id": attempt_id, "usage_event_kind": "usage_report" if revision is not None else "measured",
            "report_revision": revision, "provider": "synthetic-provider", "usage_source": source,
            "prompt_tokens": prompt, "completion_tokens": completion, "tokens": None}


class ProviderAccountingTest(unittest.TestCase):
    def test_decimal_bigint_is_exact_and_invalid_measurement_never_becomes_zero(self):
        self.assertEqual(natural("9223372036854775807"), 9223372036854775807)
        for value in (True, False, 1.0, -1, "-1", "1.2", None, "", " 1"):
            with self.subTest(value=value), self.assertRaises(UsageUnavailable):
                natural(value)

    def test_cumulative_revisions_and_measured_do_not_sum_or_double_count_cache(self):
        rows = [report("1", prompt="10", completion="2"), report("2", revision="1", prompt="20", completion=None),
                report("3", revision="2", prompt=None, completion="5")]
        rows[-1].update(cached_input_tokens="10", reasoning_output_tokens="3")
        value = summarize([attempt()], rows)
        self.assertTrue(value["usage_known"])
        self.assertEqual(value["tokens"], 25)
        self.assertEqual(value["attempts"][0]["provider_event_ids"], ["1", "2", "3"])

    def test_cancelled_failure_can_settle_provider_report_without_measured(self):
        value = summarize([attempt(status="FAILED")], [report(revision="3")])
        self.assertTrue(value["usage_known"])
        self.assertEqual(value["tokens"], 23)

    def test_started_and_unresolved_never_claim_complete_usage(self):
        for status, pending, unknown in (("STARTED", 1, 0), ("UNRESOLVED", 0, 1)):
            with self.subTest(status=status):
                value = summarize([attempt(status=status)], [report()])
                self.assertFalse(value["usage_known"])
                self.assertEqual((value["pending_attempts"], value["unknown_attempts"]), (pending, unknown))
                self.assertEqual(value["tokens"], 23)

    def test_estimated_unknown_partial_and_absent_reports_are_incomplete(self):
        variants = [[], [report(source="ESTIMATED")], [report(source="UNKNOWN")], [report(completion=None)]]
        for rows in variants:
            with self.subTest(rows=rows):
                value = summarize([attempt()], rows)
                self.assertFalse(value["usage_known"])
                self.assertEqual(value["unknown_attempts"], 1)

    def test_duplicate_orphan_provider_mismatch_is_not_silent(self):
        variants = [([attempt(), attempt()], [report()]), ([attempt()], [report(), report()]),
                    ([attempt()], [report(attempt_id="other")]),
                    ([attempt()], [{**report(), "provider": "different"}])]
        for attempts, rows in variants:
            with self.subTest(rows=rows), self.assertRaises(UsageUnavailable):
                summarize(attempts, rows)

    def test_auxiliary_embedding_total_is_counted_and_missing_is_unknown(self):
        row = report(); row["tokens"] = "41"
        self.assertEqual(summarize([attempt(operation="model.embedding")], [row])["tokens"], 41)
        row["tokens"] = None
        self.assertFalse(summarize([attempt(operation="model.embedding")], [row])["usage_known"])

    def test_public_null_usage_with_calls_is_not_complete_zero(self):
        value = normalize_public_usage({"calls": "1", "unknown_usage_calls": "0", "tokens": None,
                                        "prompt_tokens": None, "completion_tokens": None})
        self.assertEqual(value["unknown_usage_calls"], 1)
        self.assertIsNone(value["tokens"])
        no_calls = normalize_public_usage({"calls": 0, "unknown_usage_calls": 0})
        self.assertEqual(no_calls["tokens"], 0)


class FakeProvider:
    def __init__(self):
        self.value = summarize([], [])
        self.fail = False

    def snapshot(self, sessions=()):
        if self.fail:
            raise UsageUnavailable("synthetic_database_unavailable")
        return deepcopy(self.value)


class FakeAdmin:
    def __init__(self):
        self.status = "completed"
        self.pending = []

    def request(self, method, path):
        from urllib.parse import parse_qs, urlsplit
        params = parse_qs(urlsplit(path).query)
        if urlsplit(path).path == "/chat/history":
            return {"session_id": params["session_id"][0], "pending_interactions": self.pending}
        return {"session_id": params["session_id"][0], "execution_id": params["run_id"][0], "status": self.status}


class DispatchGuardTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.path = self.root / "ledger.json"
        binding = patch.object(budget_ledger, "LEDGER_PATH", self.path)
        binding.start(); self.addCleanup(binding.stop)
        self.receipt = budget_ledger.reserve(self.path, "core", 1, "synthetic-core", self.root)
        self.provider, self.admin = FakeProvider(), FakeAdmin()
        self.clock = lambda: 5.0
        self.guard = live.DispatchGuard(self.receipt, self.provider, self.admin, self.root, clock=self.clock)
        self.body = json.dumps({"session_id": "session-1", "client_message_id": "message-1",
                               "content": [{"type": "text", "text": "synthetic"}]}).encode()

    def accept(self):
        record = self.guard.before("POST", "/api/v1/chat", self.body)
        response = json.dumps({"code": "OK", "data": {"session_id": "session-1", "execution_id": "run:message-1", "input_id": "input-1"}}).encode()
        self.guard.after(record, 200, response)
        return record

    def test_pending_provider_blocks_forward_even_with_complete_partial_report(self):
        self.provider.value = summarize([attempt(status="STARTED")], [report()])
        with self.assertRaisesRegex(live.DispatchStopped, "pending_or_unknown"):
            self.guard.before("POST", "/api/v1/chat", self.body)
        self.assertEqual(self.guard.intents, [])

    def test_previous_run_not_terminal_blocks_next_input_even_before_attempt_exists(self):
        self.accept(); self.admin.status = "running"
        with self.assertRaisesRegex(live.DispatchStopped, "not_terminal"):
            self.guard.before("POST", "/api/v1/chat", self.body)
        self.assertEqual(len(self.guard.intents), 1)

    def test_waiting_hitl_allows_next_input_with_complete_usage(self):
        self.accept(); self.admin.status = "waiting_hitl"
        self.provider.value = summarize([attempt()], [report()])
        record = self.guard.before("POST", "/api/v1/chat", self.body)
        self.assertEqual(record["sequence"], 2)

    def test_suspended_run_requires_its_actual_pending_interaction(self):
        self.accept(); self.admin.status = "suspended"
        item = self.guard.intents[0]
        self.assertFalse(self.guard.run_ready(item))
        self.admin.pending = [{"execution_id": "run:other"}]
        self.assertFalse(self.guard.run_ready(item))
        self.admin.pending = [{"execution_id": item["run_id"]}]
        self.assertTrue(self.guard.run_ready(item))

    def test_diagnostic_dispatch_uses_remaining_budget_without_reset_or_double_add(self):
        self.guard.active_limits = {"max_tokens": 5, "max_input_turns": 2, "max_seconds": 50}
        self.provider.value = summarize([attempt()], [report(prompt="5", completion="0")])
        with self.assertRaisesRegex(live.DispatchStopped, "budget_exhausted"):
            self.guard.before("POST", "/api/v1/chat", self.body)
        self.assertEqual(self.guard.intents, [])
        row = self.guard.settlement()
        self.assertEqual(row["actual"]["observed_tokens"], 5)

    def test_real_diagnostic_reopen_keeps_first_receipt_and_accumulates_delta_once(self):
        self.accept()
        self.provider.value = summarize([attempt()], [report()])
        first = self.guard.settlement()
        child = self.root / "diagnostic-1"
        receipt = budget_ledger.reopen_known_diagnostic(self.path, self.receipt["reservation_id"], child)
        self.assertEqual(receipt["remaining_limits"]["max_tokens"], 2000000 - 23)
        self.assertEqual(receipt["evidence_root"], str(self.root))
        child.mkdir()
        provider = FakeProvider()
        guard = live.DispatchGuard(receipt, provider, self.admin, child, clock=self.clock)
        raw = json.dumps({"session_id": "session-2", "client_message_id": "message-2",
                          "content": [{"type": "text", "text": "synthetic"}]}).encode()
        record = guard.before("POST", "/api/v1/chat", raw)
        guard.after(record, 200, b'{"code":"OK","data":{"session_id":"session-2","execution_id":"run:message-2","input_id":"input-2"}}')
        provider.value = summarize([attempt()], [report(prompt="5", completion="2")])
        final = guard.settlement()
        self.assertEqual(final["actual"]["observed_tokens"], 30)
        self.assertEqual(final["actual"]["observed_input_turns"], 2)
        self.assertEqual(final["settlement_history"][0]["actual_delta"], first["actual"])
        self.assertEqual(final["settlement_history"][1]["actual_delta"]["observed_tokens"], 7)

    def test_persisted_intent_missing_receipt_stops_replay_and_new_round(self):
        self.guard.before("POST", "/api/v1/chat", self.body)
        with self.assertRaisesRegex(live.DispatchStopped, "receipt_unknown"):
            self.guard.before("POST", "/api/v1/chat", self.body)
        with self.assertRaisesRegex(live.DispatchStopped, "query_only_no_replay"):
            live.DispatchGuard(self.receipt, self.provider, self.admin, self.root)
        with self.assertRaisesRegex(ValueError, "unsettled"):
            budget_ledger.reserve(self.path, "stress", 1, "new-root", self.root / "new")
        settled = self.guard.settlement()
        self.assertEqual(settled["status"], "UNKNOWN")
        self.assertEqual(settled["actual"]["observed_input_turns"], 1)

    def test_legacy_message_is_refused_before_forward_intent_or_input_charge(self):
        raw = json.dumps({"session_id": "session-1", "client_message_id": "message-1",
                          "message": "synthetic legacy input"}).encode()
        with self.assertRaisesRegex(live.DispatchStopped, "formal_input_content"):
            self.guard.before("POST", "/api/v1/chat", raw)
        self.assertEqual(self.guard.intents, [])
        self.assertEqual(self.guard.settlement()["actual"]["observed_input_turns"], 0)

    def test_mismatched_actual_receipt_identity_stops_and_preserves_intent(self):
        record = self.guard.before("POST", "/api/v1/chat", self.body)
        with self.assertRaisesRegex(live.DispatchStopped, "receipt_unknown"):
            self.guard.after(record, 200, b'{"code":"OK","data":{"session_id":"wrong"}}')
        self.assertEqual(record["phase"], "intent")

    def test_known_provider_usage_settles_and_fresh_root_cannot_reuse_round(self):
        self.accept()
        self.provider.value = summarize([attempt()], [report()])
        row = self.guard.settlement()
        self.assertEqual(row["status"], "SETTLED")
        self.assertEqual(row["actual"]["observed_tokens"], 23)
        with self.assertRaisesRegex(ValueError, "already_reserved"):
            budget_ledger.reserve(self.path, "core", 1, "new-root", self.root / "new")

    def test_unknown_with_confirmed_over_cap_tokens_retains_actual_and_stops(self):
        self.provider.value = summarize([attempt(status="UNRESOLVED")], [report(prompt="2000001", completion=None)])
        row = self.guard.settlement()
        self.assertEqual(row["status"], "EXCEEDED")
        self.assertFalse(row["actual"]["usage_known"])
        self.assertEqual(row["actual"]["observed_tokens"], 2000001)
        with self.assertRaisesRegex(ValueError, "stops_new_dispatch"):
            budget_ledger.reserve(self.path, "stress", 1, "new", self.root / "new")

    def test_unavailable_usage_metric_is_omitted_instead_of_filled_zero(self):
        self.provider.fail = True
        row = self.guard.settlement()
        self.assertEqual(row["status"], "UNKNOWN")
        self.assertNotIn("observed_tokens", row["actual"])
        self.assertEqual(set(row["actual"]), {"usage_known", "observed_input_turns", "observed_elapsed_seconds"})

    def test_inflight_observation_uses_lower_bound_but_closed_unknown_stops(self):
        budget = live.behavior_runner.ExecutionBudget(2000000, 200, 7200)
        self.provider.value = summarize([attempt(status="STARTED")], [report()])
        with live.guarded_consumers(self.guard):
            budget.observe("session-1", {"tokens": None, "unknown_usage_calls": 1})
            self.assertEqual(budget.sessions, {"reservation_provider_total": 23})
            self.provider.value = summarize([attempt(status="FAILED")], [])
            with self.assertRaises(live.behavior_runner.StopBatch):
                budget.observe("session-1", {"tokens": "0"})

    def test_three_independent_outer_rounds_do_not_multiply_inner_repetitions(self):
        counts = live.experiment_counts()
        self.assertEqual(counts, {"rounds": 3, "inner_repetitions": 1, "core": 54, "stress": 18, "product": 36})
        references = {"platform": "http://127.0.0.1:1/api/v1", "models": {"llm": {"id": "model-1"}}}
        class Proxy:
            base = "http://127.0.0.1:2"
        arguments = live.cli_arguments("core", self.root, self.receipt, references, "revision", Proxy())
        self.assertEqual(arguments[arguments.index("--repetitions") + 1], "1")
        self.assertEqual(arguments[arguments.index("--workers") + 1], "1")

    def test_transparent_proxy_forwards_exact_auth_body_and_real_receipt_once(self):
        captured = []
        body = self.body
        class Upstream(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_POST(self):
                raw = self.rfile.read(int(self.headers["Content-Length"]))
                captured.append((self.path, self.headers.get("Authorization"), raw))
                payload = b'{"code":"OK","data":{"session_id":"session-1","execution_id":"run:message-1","input_id":"input-1"}}'
                self.send_response(200); self.send_header("Content-Length", str(len(payload))); self.end_headers()
                self.wfile.write(payload)
        server = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        self.addCleanup(server.server_close); self.addCleanup(server.shutdown)
        with live.GuardProxy(f"http://127.0.0.1:{server.server_port}/api/v1", self.guard) as proxy:
            request = urllib.request.Request(proxy.base + "/api/v1/chat", data=body,
                headers={"Authorization": "Bearer synthetic-test-only", "Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(request) as response:
                value = json.load(response)
            self.assertEqual(value["data"]["input_id"], "input-1")
            self.admin.status = "running"
            with self.assertRaises(urllib.error.HTTPError) as failure:
                urllib.request.urlopen(request)
            self.assertEqual(failure.exception.code, 503)
        self.assertEqual(captured, [("/api/v1/chat", "Bearer synthetic-test-only", body)])


if __name__ == "__main__":
    unittest.main()
