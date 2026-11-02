"""多轮证据必须绑定当轮持久事件，不能用末轮答案覆盖中途错误。"""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from behavior_capture import evidence, sequence_turn, turn_answers_from
from behavior_contract import digest, validate_plan, SUITE_VERSION, STRESS_SUITE_VERSION, aggregate
from behavior_eval import evaluate_assertion
from behavior_main import ci_run, create_plan, local_selection, parser
from behavior_suite import assertion, make_plan
from test_behavior_core import observed, trace


class TurnEvidenceTests(unittest.TestCase):
    def test_rejected_calls_use_independent_sequence_ranges(self):
        case = make_plan("tests")["cases"][1]
        call = {"sequence": 1, "tool": "query_record", "arguments": {}, "completed_at": 1}
        snapshot = evidence(case, 1, trace(), {"case_key": "k", "calls": [call]},
            {"run_id": "r", "status": "completed"}, [[0, 1], [1, 1]],
            unassigned=[call], unassigned_turn_ranges={"0": [0, 0], "1": [0, 1]})
        self.assertEqual([0, 1], [c["turn"] for c in snapshot["calls"]])
        self.assertEqual(2, len({c["event_id"] for c in snapshot["calls"]}))
        check = assertion("no_tools", "tool_count", {"max": 0}, turn=1)
        self.assertEqual("fail", evaluate_assertion(check, snapshot)["status"])

    def test_unclosed_overlapping_and_late_ranges_do_not_guess(self):
        for ranges in ({"0": [0, None]}, {"0": [0, 2], "1": [1, 3]}, {"0": [0, 1]}, {}):
            self.assertIsNone(sequence_turn(2, ranges))

    def test_unattributed_calls_cannot_make_turn_assertion_pass(self):
        snapshot = observed(make_plan("tests")["cases"][1])
        snapshot["calls"] = [{"tool": "query_record", "turn": None, "arguments": {}, "event_id": "rejected"}]
        scoped = assertion("no_tools", "tool_count", {"max": 0}, turn=3)
        result = evaluate_assertion(scoped, snapshot)
        self.assertEqual(("unknown", "tool_turn_evidence_missing"), (result["status"], result["reason"]))
        self.assertEqual("fail", evaluate_assertion(assertion("global", "tool_count", {"max": 0}), snapshot)["status"])
        other = assertion("no_write", "tool_count", {"max": 0}, turn=3, tool="write_record")
        self.assertEqual("pass", evaluate_assertion(other, snapshot)["status"])

    def test_runner_persists_rejected_range_before_dispatch_and_attributes_only_current_case(self):
        from behavior_runner import execute_case, ExecutionBudget
        plan = make_plan("tests")
        case = plan["cases"][3]  # 两轮，不因首轮拒绝丢失归属。
        rejected = [{"sequence": 10, "tool": "query_record", "arguments": {}, "completed_at": 1}]
        api = Mock()
        api.request.return_value = {"session_id": "s"}
        def fixture_request(method, path, *args):
            return {"complete": True, "case_key": "k", "write_events": [],
                    "calls": copy.deepcopy(rejected) if path == "/audit-unassigned" else []}
        fixture = Mock(request=Mock(side_effect=fixture_request))
        resources = {"agents": {case["profile"]: {"id": "agent"}},
                     "users": [{"id": str(i)} for i in range(54)], "resolved_model": {"model_id": "test"}}
        monitor = Mock(check=Mock(return_value=True), request_fingerprints={})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def dispatch(*args, **kwargs):
                state = json.loads((root / "cases/B04/1/state.json").read_text())
                index = str(len(state["turns"]) - 1)
                self.assertEqual([10 if index == "0" else 11, None], state["unassigned_turn_ranges"][index])
                if index == "0":
                    rejected.append({"sequence": 11, "tool": "query_record", "arguments": {"case_key": "bad"}, "completed_at": 1})
                return {"terminal": {"run_id": "r", "status": "completed"}, "seconds": 1}
            with patch("behavior_runner.monitored_chat", side_effect=dispatch), \
                    patch("behavior_runner.collect_evaluation_trace", return_value=trace()):
                execute_case(api, fixture, plan, case, 1, resources, root, ExecutionBudget(100, 5, 60), monitor)
            frozen = json.loads((root / "cases/B04/1/evidence.json").read_text())
            self.assertEqual([(11, 0)], [(c["sequence"], c["turn"]) for c in frozen["calls"]])

    def event(self, run, seq, value):
        return {"event_id": f"event-{seq}", "run_id": run, "event_type": "run.completed",
                "detail": {"sessionSequence": seq}, "payload": {"outcome": {"report": {"output": value}}}}

    def test_hitl_early_turn_cannot_borrow_later_completion(self):
        paused = {"event_id": "paused", "run_id": "run-a", "event_type": "run.suspended",
                  "detail": {"sessionSequence": 10}}
        events = [paused, self.event("run-a", 20, '{"value":100}'), self.event("run-b", 40, '{"value":200}')]
        turns = {"0": {"result": {"terminal": {"run_id": "run-a", "session_sequence": 100, "runtime_event_id": "paused"}}},
                 "1": {"result": {"terminal": {"run_id": "run-a", "session_sequence": 2, "runtime_event_id": "event-20"}}},
                 "2": {"result": {"terminal": {"run_id": "run-b", "session_sequence": 3, "runtime_event_id": "event-40"}}}}
        answers, ids = turn_answers_from(events, turns)
        self.assertEqual([None, {"value": 100}, {"value": 200}], answers)
        self.assertEqual([None, "event-20", "event-40"], ids)

    def test_missing_or_wrong_run_anchor_has_no_answer_evidence(self):
        events = [self.event("run-a", 20, '{"value":100}')]
        for terminal in ({"run_id": "run-a"}, {"run_id": "run-b", "runtime_event_id": "event-20"},
                         {"run_id": "run-a", "runtime_event_id": "absent"}):
            self.assertEqual(([None], [None]), turn_answers_from(events, {"0": {"result": {"terminal": terminal}}}))

    def test_early_wrong_answer_fails_even_when_final_answer_is_correct(self):
        snapshot = observed(make_plan("tests")["cases"][1])
        snapshot["events"] += [self.event("old", 10, '{"value":1000}'), self.event("new", 20, '{"value":100}')]
        snapshot["turn_answers"] = [{"value": 1000}, {"value": 100}]
        snapshot["turn_answer_event_ids"] = ["event-10", "event-20"]
        first = evaluate_assertion(assertion("first", "number", 100, path="/turn_answers/0/value"), snapshot)
        self.assertEqual("fail", first["status"])
        self.assertIn({"event_id": "event-10", "pointer": "/outcome/report/output", "field": "/turn_answers/0/value"}, first["evidence"])
        self.assertEqual("pass", evaluate_assertion(assertion("last", "number", 100, path="/turn_answers/1/value"), snapshot)["status"])
        snapshot["turn_answer_event_ids"][0] = "does-not-exist"
        self.assertEqual("unknown", evaluate_assertion(assertion("first", "number", 100, path="/turn_answers/0/value"), snapshot)["status"])

    def test_nested_json_types_cannot_substitute_false_with_zero(self):
        snapshot = observed(make_plan("tests")["cases"][1])
        snapshot["answer"]["value"] = {"items": [False]}
        check = assertion("typed", "equals", {"items": [0]}, path="/answer/value")
        self.assertEqual("fail", evaluate_assertion(check, snapshot)["status"])
        snapshot["answer"]["value"] = {"items": [0]}
        self.assertEqual("pass", evaluate_assertion(check, snapshot)["status"])

    def test_stress_cli_freezes_distinct_matrix_and_rejects_suite_switch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = parser().parse_args(["local-run", "--root", directory, "--suite", "stress", "--workers", "2",
                "--model-id", "1", "--deployment-revision", "test", "--max-tokens", "10000", "--max-turns", "200", "--max-seconds", "600"])
            with patch("behavior_parallel.local_parallel_run", return_value=0) as run:
                self.assertEqual(0, ci_run(args, root))
                plan = run.call_args.args[2]
            self.assertEqual(STRESS_SUITE_VERSION, plan["suite_version"])
            self.assertEqual([f"S{i:02d}" for i in range(1, 7)], plan["local_selection"]["case_ids"])
            args.suite = "core"
            with self.assertRaisesRegex(ValueError, "frozen_suite_mismatch"):
                ci_run(args, root)

    def test_extension_plan_cannot_be_reported_as_release_matrix(self):
        from behavior_stress_suite import make_stress_plan
        plan = make_stress_plan("tests")
        rows = [{"case_id": c["id"], "repetition": n, "behavior_status": "passed", "blocking_reasons": [],
                 "assertions": [], "metrics": {}} for c in plan["cases"] for n in range(1, 4)]
        self.assertFalse(aggregate(plan, rows, {})["release_eligible"])
        plan["suite_version"] = SUITE_VERSION
        plan["fingerprint"] = digest({k: v for k, v in plan.items() if k != "fingerprint"})
        with self.assertRaisesRegex(ValueError, "suite_case_count_mismatch"):
            validate_plan(plan)
