"""派发线性化与异常结算的确定性回归，无真实模型调用。"""
from contextlib import nullcontext
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
from unittest.mock import Mock, patch

from behavior_contract import issue
from behavior_parallel import SharedBudget, guarded_case, settle_experiment
from behavior_runner import StopBatch
from client import Api, ApiError
from runner import read, save


class ParallelSafetyTests(unittest.TestCase):
    def api(self, budget):
        api = Api("http://127.0.0.1:1/api/v1")
        api.dispatch_gate = lambda method, path: budget.dispatch() if (method, path) == ("POST", "/chat") else nullcontext()
        return api

    def test_request_stopped_during_pacing_is_never_sent(self):
        budget = SharedBudget(100, 2, 60)
        budget.before_turn()
        api = self.api(budget)
        api._next_request = __import__("time").monotonic() + 10
        paced, release = threading.Event(), threading.Event()
        failures = []

        def wait(_seconds):
            paced.set()
            self.assertTrue(release.wait(3))

        def send():
            try:
                api.request("POST", "/chat", {"message": "test"})
            except Exception as exc:
                failures.append(exc)

        with patch("client.time.sleep", side_effect=wait), patch("client.urllib.request.OpenerDirector.open") as transport:
            worker = threading.Thread(target=send)
            worker.start()
            try:
                self.assertTrue(paced.wait(3))
                budget.stop(issue("budget_exceeded", "other_lane_exhausted"))
            finally:
                release.set()
                worker.join(3)
            self.assertFalse(worker.is_alive())
            transport.assert_not_called()
        self.assertEqual(1, len(failures))
        self.assertIsInstance(failures[0], StopBatch)

    def test_retry_after_host_rejection_checks_global_stop_again(self):
        budget = SharedBudget(100, 2, 60)
        api = self.api(budget)
        api.interval = 0
        error = urllib.error.HTTPError(api.base + "/chat", 429, "limited", {"Retry-After": "1"},
                                       io.BytesIO(b'{"code":"RATE_LIMITED"}'))
        with patch("client.urllib.request.OpenerDirector.open", side_effect=error) as transport, \
                patch("client.time.sleep", side_effect=lambda _: budget.stop(issue("budget_exceeded", "other_lane_stopped"))):
            with self.assertRaises(StopBatch):
                api.request("POST", "/chat", {})
        self.assertEqual(1, transport.call_count)

    def test_api_error_cancels_original_run_and_settles_usage(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = root / "cases/B01/1"
            save(folder / "state.json", {"session_id": "session", "turns": {"0": {
                "request": {"session_id": "session", "client_message_id": "message"}}}})
            api, fixture = Mock(), Mock()
            fixture.request.return_value = {"complete": True, "calls": []}
            budget = SharedBudget(100, 2, 60)
            with patch("behavior_parallel.execute_case", side_effect=ApiError("transport failed")), \
                    patch("behavior_parallel.read_run_status", side_effect=[{"status": "running"}, {"status": "cancelled"}]), \
                    patch("behavior_parallel.collect_trace", side_effect=[{"pages": [], "usage": {"tokens": 5}},
                                                                         {"pages": [], "usage": {"tokens": 8}}]), \
                    patch("behavior_parallel.time.sleep"):
                with self.assertRaises(ApiError):
                    guarded_case(api, fixture, {"batch": "test", "settlement_seconds": 10},
                                 {"id": "B01"}, 1, {}, root, budget, Mock())
            api.request.assert_called_once_with("POST", "/chat/runs/cancel", {"session_id": "session", "run_id": "run:message"})
            self.assertEqual(8, budget.state()["sessions"]["session"])
            self.assertFalse(read(folder / "interruption-settlement.json")["unresolved"])
            self.assertIsNotNone(budget.stopped)
            self.assertFalse((folder / "evaluation.json").exists())

    def test_incomplete_quiescence_closes_dispatch_and_preserves_frozen_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = root / "cases/B01/1"
            evidence = {"completeness": {"quiescence": False}}
            save(folder / "evidence.json", evidence)
            budget = SharedBudget(100, 2, 60)
            with patch("behavior_parallel.execute_case", return_value={"behavior_status": "unknown", "blocking_reasons": []}), \
                    patch("behavior_parallel.settle_experiment") as settle:
                with self.assertRaises(StopBatch):
                    guarded_case(Mock(), Mock(), {"batch": "test", "settlement_seconds": 10},
                                 {"id": "B01"}, 1, {}, root, budget, Mock())
                settle.assert_called_once()
            self.assertEqual("incomplete", budget.stopped["code"])
            self.assertEqual(evidence, read(folder / "evidence.json"))
            with self.assertRaises(StopBatch):
                with budget.dispatch():
                    self.fail("must not dispatch")

    def test_missing_run_is_unresolved_and_never_resent(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            save(folder / "state.json", {"session_id": "s", "turns": {"0": {
                "request": {"session_id": "s", "client_message_id": "m"}}}})
            api, fixture = Mock(), Mock()
            fixture.request.return_value = {"complete": True, "calls": []}
            budget = SharedBudget(100, 2, 60)
            with patch("behavior_parallel.read_run_status", side_effect=ApiError("not found", status_code=404)), \
                    patch("behavior_parallel.collect_trace", return_value={"pages": [], "usage": {"tokens": 0}}):
                settle_experiment(api, fixture, folder, budget, 0, "test:B01:r1")
            api.request.assert_not_called()
            self.assertTrue(read(folder / "interruption-settlement.json")["unresolved"])
