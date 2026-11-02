"""Interrupted-lane recovery controls; temporary ledger and synthetic read-only API."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
import live_behavior
import live_behavior_recovery as recovery
import budget_ledger as ledger
from run_suite import save
from test_live_behavior import FakeAdmin, FakeProvider, attempt, report, summarize


class SettlementOnlyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.path = self.root / "ledger.json"
        binding = patch.object(ledger, "LEDGER_PATH", self.path)
        binding.start(); self.addCleanup(binding.stop)
        self.receipt = ledger.reserve(self.path, "core", 1, "synthetic", self.root)
        save(self.root / "supervisor-reservation.json", self.receipt)
        self.boundary = {"invocation_id_upper_bound": "8", "usage_id_upper_bound": "9"}
        save(self.root / "provider-boundary.json", self.boundary)
        self.item = {"sequence": 1, "method": "POST", "path": "/api/v1/chat", "is_input": True,
                     "phase": "receipt", "session_id": "s1", "run_id": "run:m1",
                     "expected_run_id": "run:m1", "input_id": "m1"}
        self.journal = {"reservation_id": self.receipt["reservation_id"], "batch_id": "synthetic",
                        "owner_pid": 123, "intents": [self.item]}
        self.provider, self.admin = FakeProvider(), FakeAdmin()
        self.provider.value = {**summarize([attempt()], [report()]), "boundary": self.boundary}
        self.calls = []
        request = self.admin.request
        def readonly(method, path):
            self.assertEqual(method, "GET")
            self.calls.append(path)
            return request(method, path)
        self.admin.request = readonly
        binding = patch.object(recovery, "owner_is_alive", return_value=False)
        binding.start(); self.addCleanup(binding.stop)
        self.now = datetime.fromisoformat(self.receipt["issued_at"]) + timedelta(seconds=40)

    def run_recovery(self):
        save(self.root / "guard/dispatch-journal.json", self.journal)
        return recovery.settle_only(self.root, self.admin, self.provider, now=self.now)

    def test_uses_original_boundary_exact_turns_terminal_readback_and_elapsed(self):
        row = self.run_recovery()
        self.assertEqual(row["status"], "SETTLED")
        self.assertEqual(row["actual"], {"usage_known": True, "observed_tokens": 23,
                                       "observed_input_turns": 1, "observed_elapsed_seconds": 40.0})
        self.assertEqual(self.provider.boundary, self.boundary)
        self.assertEqual(len(self.calls), 1)
        original = self.root / "guard/dispatch-journal.json"
        self.assertEqual(json.loads(original.read_text()), self.journal)

    def test_missing_receipt_is_unknown_without_reading_or_replaying_input(self):
        self.item["phase"] = "intent"
        row = self.run_recovery()
        self.assertEqual(row["status"], "UNKNOWN")
        self.assertEqual(self.calls, [])
        self.assertEqual(row["actual"]["observed_input_turns"], 1)

    def test_running_or_suspended_is_unknown_even_with_complete_provider_usage(self):
        for state in ("running", "suspended", "waiting_hitl"):
            with self.subTest(state=state):
                self.admin.status = state
                # One recovery owns this reservation; separate controls use the fresh setUp.
                if state != "running": self.setUp()
                self.admin.status = state
                self.assertEqual(self.run_recovery()["status"], "UNKNOWN")

    def test_provider_unavailable_preserves_lower_bound_and_never_sets_known(self):
        self.provider.fail = True
        save(self.root / "guard/provider-latest.json", self.provider.value)
        row = self.run_recovery()
        self.assertEqual(row["status"], "UNKNOWN")
        self.assertEqual(row["actual"]["observed_tokens"], 23)

    def test_provider_unavailable_without_snapshot_omits_tokens(self):
        self.provider.fail = True
        row = self.run_recovery()
        self.assertEqual(row["status"], "UNKNOWN")
        self.assertNotIn("observed_tokens", row["actual"])

    def test_owner_alive_rejects_before_any_query_or_settlement(self):
        with patch.object(recovery, "owner_is_alive", return_value=True):
            with self.assertRaisesRegex(ValueError, "owner_still_alive"):
                self.run_recovery()
        self.assertEqual(self.calls, [])
        self.assertEqual(ledger.check_reservation(self.path, self.receipt["reservation_id"], True)["status"], "RESERVED")

    def test_boundary_and_receipt_identity_drift_are_not_replaced(self):
        save(self.root / "provider-boundary.json", {"new": "10"})
        with self.assertRaisesRegex(ValueError, "original_boundary_invalid"):
            self.run_recovery()
        self.assertEqual(self.calls, [])

    def test_second_settlement_cannot_double_add_or_overwrite(self):
        row = self.run_recovery()
        with self.assertRaisesRegex(ValueError, "original_active"):
            self.run_recovery()
        self.assertEqual(ledger.check_reservation(self.path, self.receipt["reservation_id"], True)["actual"], row["actual"])


if __name__ == "__main__":
    unittest.main()
