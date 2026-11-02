"""双路本地调度的离线回归：竞争、收口和 lane 隔离均不调用模型。"""
from __future__ import annotations

from contextlib import nullcontext
from http.client import HTTPConnection
import json
from pathlib import Path
from types import SimpleNamespace
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent))

import behavior_parallel as parallel  # noqa: E402
from behavior_runner import StopBatch  # noqa: E402
from mcp_fixture import McpFixtureServer, PROTOCOL_VERSION  # noqa: E402


class _Monitor:
    def __init__(self, reasons=None):
        self.reasons = list(reasons or [])
        self.checked = 0

    def check(self):
        self.checked += 1
        return not self.reasons


class _StartedMonitor(_Monitor):
    """local_parallel_run 所需的最小、可观察的监测器替身。"""
    def __init__(self, reasons=None):
        super().__init__(reasons)
        self.samples = []
        self.thread = SimpleNamespace(ident=1)

    def start(self):
        self.checked += 1

    def close(self):
        self.checked += 1


class ParallelBehaviorTests(unittest.TestCase):
    def test_shared_budget_allows_only_one_competing_last_input(self):
        budget = parallel.SharedBudget(100, 1, 60)
        gate = threading.Barrier(2)
        outcomes = []
        outcome_lock = threading.Lock()

        def claim_turn():
            gate.wait(1)
            try:
                budget.before_turn()
                value = "accepted"
            except StopBatch as error:
                value = error.reason
            with outcome_lock:
                outcomes.append(value)

        threads = [threading.Thread(target=claim_turn) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(1)
            self.assertFalse(thread.is_alive())

        self.assertEqual(1, outcomes.count("accepted"))
        rejected = next(item for item in outcomes if item != "accepted")
        self.assertEqual("budget_exceeded", rejected["code"])
        self.assertEqual(1, budget.state()["turns"])
        self.assertEqual(rejected, budget.state()["stop_reason"])

    def test_shared_budget_settles_concurrent_sessions_after_token_stop(self):
        budget = parallel.SharedBudget(10, 10, 60)
        gate = threading.Barrier(2)
        outcomes = []
        outcome_lock = threading.Lock()

        def observe(session, tokens):
            gate.wait(1)
            try:
                budget.observe(session, {"tokens": tokens, "unknown_usage_calls": 0})
                result = "settled"
            except StopBatch as error:
                result = error.reason
            with outcome_lock:
                outcomes.append(result)

        threads = [threading.Thread(target=observe, args=("session-a", 5)),
                   threading.Thread(target=observe, args=("session-b", 6))]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(1)
            self.assertFalse(thread.is_alive())

        self.assertEqual(1, outcomes.count("settled"))
        self.assertEqual("budget_exceeded", budget.state()["stop_reason"]["code"])
        # 触线后仍接受迟到结算，避免把已发起请求的消耗遗失。
        with self.assertRaises(StopBatch):
            budget.observe("session-a", {"tokens": 7, "unknown_usage_calls": 0})
        self.assertEqual({"session-a": 7, "session-b": 6}, budget.state()["sessions"])

    def test_shared_budget_state_is_a_stable_snapshot_and_deadline_stops_dispatch(self):
        clock = [0]
        budget = parallel.SharedBudget(20, 3, 5, clock=lambda: clock[0])
        budget.observe("session", {"tokens": 3, "unknown_usage_calls": 0})
        snapshot = budget.state()
        another_snapshot = budget.state()
        another_snapshot["sessions"]["session"] = 999
        budget.observe("session", {"tokens": 4, "unknown_usage_calls": 0})
        self.assertEqual({"session": 4}, budget.state()["sessions"])
        self.assertEqual({"session": 3}, snapshot["sessions"])

        clock[0] = 5
        with self.assertRaises(StopBatch) as stopped:
            budget.before_turn()
        self.assertEqual("budget_exceeded", stopped.exception.reason["code"])
        self.assertEqual(stopped.exception.reason, budget.state()["stop_reason"])

    def test_waves_preserve_every_experiment_once_and_isolate_serial_cases(self):
        matrix = [("B01", 1), ("B02", 1), ("B06", 1), ("B03", 1),
                  ("B14", 2), ("B04", 1), ("B05", 1)]
        with patch.object(parallel, "experiments", return_value=matrix):
            result = list(parallel.waves({}))

        self.assertEqual([["B01", "B02"], ["B06"], ["B03"], ["B14"], ["B04", "B05"]],
                         [[case_id for case_id, _ in wave] for wave in result])
        self.assertEqual(matrix, [experiment for wave in result for experiment in wave])
        self.assertTrue(all(len(wave) == 1 for wave in result if wave[0][0] in parallel.SERIAL_CASES))

    def test_run_waves_overlaps_two_lanes_then_waits_before_next_wave(self):
        first_started = threading.Event()
        first_entered = 0
        first_entered_lock = threading.Lock()
        release_first = threading.Event()
        second_started = threading.Event()
        finished = threading.Event()
        errors = []
        completed = []

        def invoke(lane, experiment):
            nonlocal first_entered
            if experiment in {("B01", 1), ("B02", 1)}:
                with first_entered_lock:
                    first_entered += 1
                    if first_entered == 2:
                        first_started.set()
                self.assertTrue(release_first.wait(1))
            else:
                second_started.set()
            return {"lane": lane, "experiment": experiment}

        def run():
            try:
                parallel.run_waves({}, parallel.SharedBudget(100, 10, 60), invoke,
                                   lambda experiment, result: completed.append((experiment, result)))
            except Exception as error:  # pragma: no cover - asserted below
                errors.append(error)
            finally:
                finished.set()

        with patch.object(parallel, "waves", return_value=[[("B01", 1), ("B02", 1)], [("B03", 1)]]):
            thread = threading.Thread(target=run)
            thread.start()
            # 两个 worker 都到达屏障才说明确实有并发运行。
            self.assertTrue(first_started.wait(1))
            self.assertFalse(second_started.is_set())
            release_first.set()
            self.assertTrue(second_started.wait(1))
            self.assertTrue(finished.wait(1))
            thread.join(1)

        self.assertFalse(thread.is_alive())
        self.assertEqual([], errors)
        self.assertEqual({("B01", 1), ("B02", 1), ("B03", 1)}, {item[0] for item in completed})

    def test_worker_failure_stops_future_dispatch_and_waits_for_other_inflight_settlement(self):
        both_started = threading.Barrier(2)
        budget_stopped = threading.Event()
        settle_other = threading.Event()
        other_settled = threading.Event()
        later_dispatched = threading.Event()
        finished = threading.Event()
        errors = []
        budget = parallel.SharedBudget(100, 10, 60)
        original_stop = budget.stop

        def record_stop(reason):
            original_stop(reason)
            budget_stopped.set()

        budget.stop = record_stop

        def invoke(_lane, experiment):
            if experiment == ("B01", 1):
                both_started.wait(1)
                raise RuntimeError("worker failed")
            if experiment == ("B02", 1):
                both_started.wait(1)
                self.assertTrue(settle_other.wait(1))
                other_settled.set()
                return {"behavior_status": "passed"}
            later_dispatched.set()
            return {"behavior_status": "passed"}

        def run():
            try:
                parallel.run_waves({}, budget, invoke, lambda _experiment, _result: None)
            except Exception as error:
                errors.append(error)
            finally:
                finished.set()

        with patch.object(parallel, "waves", return_value=[[("B01", 1), ("B02", 1)], [("B03", 1)]]):
            thread = threading.Thread(target=run)
            thread.start()
            # 观察 work() 实际广播熔断，不依赖 invoke 在 raise 前的调度时序。
            self.assertTrue(budget_stopped.wait(1))
            self.assertFalse(later_dispatched.is_set())
            self.assertFalse(finished.is_set())
            self.assertEqual("invalid_environment", budget.state()["stop_reason"]["code"])
            settle_other.set()
            self.assertTrue(other_settled.wait(1))
            self.assertTrue(finished.wait(1))
            thread.join(1)

        self.assertFalse(thread.is_alive())
        self.assertEqual(1, len(errors))
        self.assertIsInstance(errors[0], RuntimeError)
        self.assertFalse(later_dispatched.is_set())

    def test_lane_monitor_stops_all_lanes_when_any_lane_drifts(self):
        clean = _Monitor()
        drift = _Monitor([{"code": "invalid_config_drift", "detail": "lane_one_changed"}])
        lane_zero = parallel.LaneMonitor(clean, [clean, drift])

        self.assertFalse(lane_zero.check())
        self.assertEqual(1, clean.checked)
        self.assertEqual(drift.reasons, lane_zero.reasons)

    def test_local_parallel_provisions_isolated_lane_resources_and_assigns_each_experiment_once(self):
        plan = {"batch": "parallel-unit", "suite_version": "suite", "settlement_seconds": 1,
                "profiles": [{"key": "reader"}],
                "cases": [{"id": "B01", "profile": "reader"}, {"id": "B02", "profile": "reader"}]}
        args = SimpleNamespace(command="local-run", max_tokens=100, max_turns=10, max_seconds=60,
                               deployment_revision="local", model_id="model", api="api", platform="platform")
        provision_calls, executed = [], []

        class Server:
            next_port = 31000

            def __init__(self, port, batch, token):
                self.server_address = ("127.0.0.1", Server.next_port)
                Server.next_port += 1
                self.batch = batch

            def serve_forever(self):
                return None

            def shutdown(self):
                return None

            def server_close(self):
                return None

        def make_resources(_api, _platform, lane_root, _profiles, batch, _model_id, endpoint, *, user_count):
            lane = int(Path(lane_root).name)
            provision_calls.append((lane, batch, endpoint, user_count))
            return {"model_id": "model-id", "agents": {"reader": {"id": f"agent-{lane}"}},
                    "users": [{"id": f"lane-{lane}-user-{index}"} for index in range(user_count)]}

        api_client = SimpleNamespace()
        platform_client = SimpleNamespace()

        def execute(_api, fixture, _plan, case, repetition, resources, root, _budget, _monitor):
            executed.append((case["id"], repetition, resources["agents"]["reader"]["id"], fixture.base))
            folder = Path(root) / "cases" / case["id"] / str(repetition)
            folder.mkdir(parents=True, exist_ok=True)
            (folder / "evidence.json").write_text(json.dumps({"completeness": {"quiescence": True}}), encoding="utf-8")
            return {"behavior_status": "passed", "blocking_reasons": []}

        with tempfile.TemporaryDirectory() as directory, \
                patch.object(parallel, "batch_lock", side_effect=lambda _root: nullcontext()), \
                patch.object(parallel, "McpFixtureServer", Server), \
                patch.object(parallel, "Api", side_effect=lambda base, _token: SimpleNamespace(base=base)), \
                patch.object(parallel, "login", side_effect=[api_client, platform_client]), \
                patch.object(parallel, "resolve_model", return_value={"id": "model-id", "model_id": "model"}), \
                patch.object(parallel, "provision", side_effect=make_resources), \
                patch.object(parallel, "configuration", side_effect=lambda *_args: {"config": "frozen"}), \
                patch.object(parallel, "ConfigurationMonitor", side_effect=lambda *_args: _StartedMonitor()), \
                patch.object(parallel, "execute_case", side_effect=execute), \
                patch.object(parallel, "experiments", return_value=[("B01", 1), ("B02", 1)]), \
                patch.object(parallel, "final_report", return_value={"behavior_status": "passed", "blocking_reasons": []}), \
                patch("behavior_main.publish"):
            self.assertEqual(0, parallel.local_parallel_run(args, Path(directory), plan))

        self.assertEqual([(0, "parallel-unit-lane0", "http://127.0.0.1:31000/mcp", 2),
                          (1, "parallel-unit-lane1", "http://127.0.0.1:31001/mcp", 2)], provision_calls)
        self.assertEqual({("B01", 1), ("B02", 1)}, {(case, repetition) for case, repetition, _, _ in executed})
        self.assertEqual(2, len({agent for _, _, agent, _ in executed}))
        self.assertEqual(2, len({endpoint for _, _, _, endpoint in executed}))
        self.assertTrue(callable(api_client.dispatch_gate))

    def test_two_real_http_fixtures_keep_unassigned_audits_on_their_own_lane(self):
        servers = [McpFixtureServer(0, "lane-zero"), McpFixtureServer(0, "lane-one")]
        threads = [threading.Thread(target=server.serve_forever, name=f"fixture-{index}")
                   for index, server in enumerate(servers)]
        for thread in threads:
            thread.start()
        self.addCleanup(self._close_servers, servers, threads)

        arguments = [{"case_key": "outside-zero:record"}, {"case_key": "outside-one:record"}]
        for server, values in zip(servers, arguments):
            status, _ = self._rpc(server.server_address[1], values)
            self.assertEqual(200, status)

        audits = [self._json_request(server.server_address[1], "GET", "/audit-unassigned")[1] for server in servers]
        self.assertEqual(["lane-zero", "lane-one"], [audit["batch"] for audit in audits])
        self.assertEqual([[argument] for argument in arguments],
                         [[call["arguments"] for call in audit["calls"]] for audit in audits])
        self.assertTrue(all(audit["complete"] for audit in audits))

    @staticmethod
    def _rpc(port, arguments):
        return ParallelBehaviorTests._json_request(port, "POST", "/mcp", {
            "jsonrpc": "2.0", "id": "unassigned", "method": "tools/call",
            "params": {"name": "query_record", "arguments": arguments},
        }, {"MCP-Protocol-Version": PROTOCOL_VERSION})[:2]

    @staticmethod
    def _json_request(port, method, path, body=None, headers=None):
        connection = HTTPConnection("127.0.0.1", port, timeout=2)
        payload = None if body is None else json.dumps(body).encode("utf-8")
        supplied = {"Content-Type": "application/json"} if payload else {}
        supplied.update(headers or {})
        try:
            connection.request(method, path, payload, supplied)
            response = connection.getresponse()
            raw = response.read()
            return response.status, json.loads(raw) if raw else None, dict(response.getheaders())
        finally:
            connection.close()

    @staticmethod
    def _close_servers(servers, threads):
        for server in servers:
            server.shutdown()
            server.server_close()
        for thread in threads:
            thread.join(2)
            if thread.is_alive():
                raise AssertionError("fixture_thread_not_quiescent")


if __name__ == "__main__":
    unittest.main()
