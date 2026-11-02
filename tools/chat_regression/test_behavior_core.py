"""发布矩阵、证据边界与预算熔断的离线回归，不调用模型。"""
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
import zipfile

sys.path.insert(0, str(Path(__file__).parent))
from behavior_capture import evidence, flatten
from behavior_contract import aggregate, release_check, validate_assertion, validate_plan, experiments
from behavior_eval import evaluate_assertion, evaluate_case, normalize_url
from behavior_release import verify_ci
from behavior_runner import ExecutionBudget, StopBatch, execute_case, read_run_status, cancellation_chat, monitored_chat
from behavior_suite import assertion, make_plan, materialize
from behavior_main import ci_run, parser, create_plan, prepare, outcome_exit_code, main


def trace(tokens=5):
    phases, contents = [], {}
    for eid, kind, payload in [
        ("ctx", "agent.context-snapshot", {"modelRequest": {"target": {"effectiveModelId": "test"}, "generation": {"temperature": "0"}}}),
        ("done", "run.completed", {"outcome": {"report": {"output": '{"status":"success","value":100}'}}})]:
        phases.append({"event_id": eid, "event_type": kind, "content_ref": eid, "detail": {"sessionSequence": len(phases) + 1}})
        contents[eid] = {"value": payload, "availability": "FULL"}
    phases.append({"event_id": None, "availability": "NOT_APPLICABLE"})
    return {"pages": [{"rounds": [{"runs": [{"run_id": "r", "steps": [{"action_id": "a", "phases": phases}]}]}]}],
            "contents": contents, "usage": {"tokens": tokens, "unknown_usage_calls": 0}}


def observed(case):
    return evidence(case, 1, trace(), {"case_key": "k", "calls": [], "write_events": []},
                    {"run_id": "r", "status": "completed"}, [[0, 0]])


class ContractTests(unittest.TestCase):
    def test_query_only_rejects_extra_write_dispatch_even_without_a_persisted_effect(self):
        case, key = materialize(make_plan("tests")["cases"][1], "tests", 1)
        snapshot = observed(case)
        snapshot["calls"] = [{"tool": "query_record", "arguments": {"case_key": key},
                              "turn": 0, "event_id": "fixture:query", "completed_at": "done"}]
        self.assertEqual("passed", evaluate_case(case, snapshot)["behavior_status"])
        snapshot["calls"].append({"tool": "write_record", "arguments": {"case_key": key, "value": 100},
                                  "turn": 0, "event_id": "fixture:write", "completed_at": "denied"})
        self.assertEqual([], snapshot["write_events"])
        result = evaluate_case(case, snapshot)
        self.assertEqual("failed", result["behavior_status"])
        self.assertEqual("fail", next(a for a in result["assertions"] if a["id"] == "attempt_limit")["status"])

    def test_unknown_write_neither_claims_success_nor_redispatches_after_one_effect(self):
        case, key = materialize(make_plan("tests")["cases"][13], "tests", 1)
        snapshot = observed(case)
        snapshot["calls"] = [{"tool": "write_record", "arguments": {"case_key": key},
                              "turn": 0, "event_id": "fixture:write", "completed_at": "unknown"}]
        snapshot["write_events"] = [{"case_key": key, "request_key": key + ":write"}]
        checks = {a["id"]: a for a in case["assertions"]}
        self.assertEqual("pass", evaluate_assertion(checks["one_effect"], snapshot)["status"])
        self.assertEqual("fail", evaluate_assertion(checks["no_false_success"], snapshot)["status"])
        snapshot["answer"] = {"status": "unavailable", "value": None}
        self.assertEqual("passed", evaluate_case(case, snapshot)["behavior_status"])
        snapshot["calls"].append({**snapshot["calls"][0], "event_id": "fixture:write-again"})
        # 业务 request_key 去重只保证一个效果；第二次发送仍违反未知结果不能重发的契约。
        self.assertEqual("pass", evaluate_assertion(checks["one_effect"], snapshot)["status"])
        self.assertEqual("fail", evaluate_assertion(checks["single_write"], snapshot)["status"])
        self.assertEqual("failed", evaluate_case(case, snapshot)["behavior_status"])

    def test_explicit_requery_requires_a_new_tool_call_even_with_correct_cached_value(self):
        case = make_plan("tests")["cases"][3]
        query_count = next(a for a in case["assertions"] if a["id"] == "query_count")
        snapshot = observed(case)
        snapshot["calls"] = [{"tool": "query_record", "arguments": {"case_key": "tests:B04:r1"},
                              "turn": 0, "event_id": "fixture:first"}]
        self.assertEqual(100, snapshot["answer"]["value"])
        self.assertEqual("fail", evaluate_assertion(query_count, snapshot)["status"])
        snapshot["calls"].append({"tool": "query_record", "arguments": {"case_key": "tests:B04:r1"},
                                  "turn": 1, "event_id": "fixture:second"})
        self.assertEqual("pass", evaluate_assertion(query_count, snapshot)["status"])

    def test_answer_envelope_preserves_original_evidence_and_numeric_fidelity(self):
        case = make_plan("tests")["cases"][1]
        for value in (100, 1000):
            for envelope in ('{}', '```json\n{}\n```', '```\n{}\n```', ' \n```json\r\n{}\r\n``` \n'):
                output = envelope.format(json.dumps({"status": "success", "value": value}))
                with self.subTest(value=value, envelope=envelope):
                    t = trace()
                    t["contents"]["done"]["value"]["outcome"]["report"]["output"] = output
                    e = evidence(case, 1, t, {"case_key": "k", "calls": [], "write_events": []},
                                 {"run_id": "r", "status": "completed"}, [[0, 0]])
                    self.assertEqual(output, e["observable_output"]["answer"])
                    self.assertEqual("done", e["answer_event_id"])
                    result = evaluate_assertion(assertion("n", "number", 100, path="/answer/value"), e)
                    self.assertEqual("pass" if value == 100 else "fail", result["status"])
                    self.assertIn({"event_id": "done", "pointer": "/outcome/report/output",
                                   "field": "/answer/value"}, result["evidence"])

    def test_ambiguous_or_non_json_answer_envelopes_are_rejected(self):
        case = make_plan("tests")["cases"][1]
        for output in ('说明 {"value":100}', '{"value":100} 完成',
                       '```json\n{"value":100}\n```\n```json\n{"value":1000}\n```',
                       '```json\n{"value":100}\n``` extra', 'prefix\n```json\n{"value":100}\n```',
                       '```python\n{"value":100}\n```', '```json\n{"value":100,}\n```',
                       '[{"value":100}]', 'null', '{"value":100,"value":1000}',
                       '{"nested":{"a":1,"a":2},"value":100}', '{"value":NaN}',
                       '{"value":Infinity}', '{"value":1e999}', '```json\n{"value":100}'):
            with self.subTest(output=output):
                t = trace()
                t["contents"]["done"]["value"]["outcome"]["report"]["output"] = output
                e = evidence(case, 1, t, {"case_key": "k", "calls": [], "write_events": []},
                             {"run_id": "r", "status": "completed"}, [[0, 0]])
                self.assertIsNone(e["answer"])
                self.assertEqual(output, e["observable_output"]["answer"])
                self.assertEqual("done", e["answer_event_id"])
                self.assertEqual("fail", evaluate_assertion(
                    assertion("n", "number", 100, path="/answer/value"), e)["status"])

    def test_closed_assertions_and_versions(self):
        good = assertion("a", "tool_count", {"max": 0})
        for key, value in [("selector", {"typo": "query"}), ("options", {"ignore": True}), ("expected", {"mx": 0})]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_assertion({**good, key: value})
        plan = make_plan("tests")
        plan["schema_version"] = 0
        with self.assertRaises(ValueError):
            validate_plan(plan)

    def test_numeric_url_and_multiple_legal_paths(self):
        e = observed(make_plan("tests")["cases"][0])
        a = assertion("n", "number", 100, path="/answer/value")
        self.assertEqual("pass", evaluate_assertion(a, e)["status"])
        e["answer"]["value"] = 100.0
        self.assertEqual("pass", evaluate_assertion(a, e)["status"])
        for bad in (1000, "约100", "100 元", True):
            e["answer"]["value"] = bad
            result = evaluate_assertion(a, e)
            self.assertEqual("fail", result["status"])
            self.assertEqual("/outcome/report/output", result["evidence"][0]["pointer"])
        self.assertEqual("https://example.com/A?x=1", normalize_url("HTTPS://EXAMPLE.COM:443/A?x=1&utm=2", ["utm"]))
        self.assertNotEqual(normalize_url("https://example.com/A"), normalize_url("https://example.com/a/"))
        paths = assertion("paths", "any_of", [[assertion("one", "equals", 1, path="/answer/value")],
                                              [assertion("two", "equals", 2, path="/answer/value")]])
        for value in (1, 2):
            e["answer"]["value"] = value
            self.assertEqual("pass", evaluate_assertion(paths, e)["status"])

    def test_complete_but_non_json_answer_fails_instead_of_missing_evidence(self):
        e = observed(make_plan("tests")["cases"][0])
        e["answer"] = None
        for a in [assertion("n", "number", 100, path="/answer/value"),
                  assertion("s", "equals", "unavailable", path="/answer/status"),
                  assertion("u", "url", "https://example.invalid", path="/answer/url")]:
            self.assertEqual("fail", evaluate_assertion(a, e)["status"])
            incomplete = copy.deepcopy(e)
            incomplete["completeness"]["trace"] = False
            self.assertEqual("unknown", evaluate_assertion(a, incomplete)["status"])

    def test_pagination_requires_full_fetch_and_requested_last_page_value(self):
        case = make_plan("tests")["cases"][6]
        e = observed(case)
        e["calls"] = [{"tool": "query_record", "arguments": {"case_key": "k"},
                       "event_id": "page1", "turn": 0}]
        count = next(a for a in case["assertions"] if a["id"] == "query_count")
        value = next(a for a in case["assertions"] if a["id"] == "complete_value")
        self.assertEqual("fail", evaluate_assertion(count, e)["status"])
        e["calls"].append({**e["calls"][0], "event_id": "page2"})
        self.assertEqual("pass", evaluate_assertion(count, e)["status"])
        for actual in (40, [40, 100], 140, "100", 100):
            with self.subTest(actual=actual):
                e["answer"]["value"] = actual
                self.assertEqual("pass" if actual == 100 else "fail", evaluate_assertion(value, e)["status"])

    def test_quiescence_required_and_manual_never_blocks(self):
        case = make_plan("tests")["cases"][0]
        e = observed(case)
        self.assertEqual("passed", evaluate_case(case, e)["behavior_status"])
        e["metrics"]["model_steps"] = 100
        inefficient = evaluate_case(case, e)
        self.assertEqual("passed", inefficient["behavior_status"])
        self.assertEqual(["model_step_budget_exceeded"], inefficient["metrics"]["efficiency_warnings"])
        e["completeness"]["quiescence"] = False
        result = evaluate_case(case, e)
        self.assertEqual("unknown", result["behavior_status"])
        self.assertIn("incomplete", [r["code"] for r in result["blocking_reasons"]])
        e["case_id"] = "B02"
        with self.assertRaises(ValueError):
            evaluate_case(case, e)

    def test_malformed_result_requires_explanation_or_exact_unknown_fact_before_controlled_stop(self):
        case = make_plan("tests")["cases"][5]
        a = next(v for v in case["assertions"] if v["id"] == "malformed_safe_outcome")
        e = observed(case)
        e["answer"] = {"status": "unavailable", "value": None}
        self.assertEqual("pass", evaluate_assertion(a, e)["status"])
        e["terminal"]["status"] = "cancelled"
        e["answer"] = None
        self.assertEqual("fail", evaluate_assertion(a, e)["status"])
        unknown = {"event_id": "unknown", "event_type": "action.outcome_unknown", "run_id": "r", "action_id": "a",
                   "payload": {"state": "OUTCOME_UNKNOWN", "reasonCode": "mcp_outcome_unknown",
                               "actionRef": {"actionId": "a"}, "outcomeFactRef": "fact", "invocationId": "i"},
                   "detail": {"currentActionState": "OUTCOME_UNKNOWN", "callKind": "TOOL_INVOKE", "attemptId": "t",
                              "attempts": [{"callKind": "TOOL_INVOKE", "status": "UNRESOLVED", "factRef": "fact",
                                            "invocationId": "i", "attemptId": "t", "outcomeType": "OutcomeUnknown",
                                            "outcomeResolution": "OUTCOME_UNKNOWN"}]}}
        e["events"].append(unknown)
        result = evaluate_assertion(a, e)
        self.assertEqual("pass", result["status"])
        self.assertIn({"event_id": "unknown", "pointer": "/outcomeFactRef"}, result["evidence"])
        for location, key, value in [(unknown, "run_id", "other"), (unknown["payload"], "outcomeFactRef", "wrong"),
                                     (unknown["detail"], "attemptId", "wrong")]:
            original = location[key]
            location[key] = value
            self.assertEqual("fail", evaluate_assertion(a, e)["status"])
            location[key] = original
        e["answer"] = {"status": "success", "value": 100}
        self.assertEqual("fail", evaluate_assertion(a, e)["status"])
        e["completeness"]["trace"] = False
        self.assertEqual("unknown", evaluate_assertion(a, e)["status"])

    def test_full_matrix_and_assertion_inventory(self):
        plan = make_plan("tests")
        rows = [{"case_id": c["id"], "repetition": r, "behavior_status": "passed", "blocking_reasons": [], "metrics": {},
                 "assertions": [{"id": a["id"], "evaluator": a["evaluator"], "blocking": a["blocking"],
                                 "status": "pass" if a["blocking"] else "unknown"} for a in c["assertions"]]}
                for c in plan["cases"] for r in range(1, 4)]
        binding = {"deployment_revision": "rev", "suite_version": plan["suite_version"], "evaluator_version": "behavior-1",
                   "config_digest": "cfg", "model_id": "test", "model_version": None, "settlement_seconds": 60}
        report = aggregate(plan, rows, binding)
        expected = report["binding"]
        self.assertTrue(release_check(report, expected, plan)["qualified"])
        report["cases"][0]["assertions"].pop(0)
        self.assertFalse(release_check(report, expected, plan)["qualified"])
        report["cases"].pop()
        self.assertIn("incomplete_matrix", release_check(report, expected, plan)["problems"])


class CaptureBudgetTests(unittest.TestCase):
    def test_unknown_write_stop_waits_for_recorded_action_and_never_redispatches(self):
        api, monitor = Mock(), Mock()
        monitor.reasons = []
        api.request.side_effect = [None, {"session_id": "s", "execution_id": "run:m", "status": "cancelled"}]
        started, unknown = trace(), trace()
        started["pages"][0]["rounds"][0]["runs"][0]["steps"][0]["phases"][0]["detail"]["currentActionState"] = "CLAIMED"
        unknown["pages"][0]["rounds"][0]["runs"][0]["steps"][0]["phases"][0]["detail"]["currentActionState"] = "OUTCOME_UNKNOWN"
        future = Mock()
        future.result.side_effect = [TimeoutError(), TimeoutError(), TimeoutError(), TimeoutError()]
        future.done.side_effect = [False, False, True]
        with patch("behavior_runner.concurrent.futures.ThreadPoolExecutor") as pool, \
                patch("behavior_runner.collect_trace", side_effect=[started, unknown]) as collect:
            pool.return_value.__enter__.return_value.submit.return_value = future
            timing = monitored_chat(api, {"session_id": "s", "client_message_id": "m"}, ExecutionBudget(100, 3, 60),
                                    monitor, Mock(), stop_on_unknown=True)
        self.assertEqual(2, collect.call_count)
        self.assertEqual(["POST", "GET"], [c.args[0] for c in api.request.call_args_list])
        self.assertEqual("cancel_after_recorded_tool_unknown", timing["stop_policy"])
        self.assertEqual("cancelled", timing["terminal"]["status"])

    def test_cancel_barrier_uses_metadata_and_closed_stream_can_settle_by_verified_status(self):
        released = threading.Event()
        api, fixture = Mock(), Mock()
        api.request.return_value = {"session_id": "s", "execution_id": "run:m", "status": "cancelled"}
        def fixture_call(method, path, body=None):
            if path == "/barrier/release":
                released.set()
            return {"entered": True, "released": released.is_set()}
        fixture.request.side_effect = fixture_call
        def closed_chat(*args):
            if not released.wait(2):
                raise AssertionError("barrier not released")
            raise TimeoutError("closed after cancellation")
        metadata = trace()
        metadata["pages"][0]["rounds"][0]["runs"][0]["steps"][0]["phases"][0]["event_type"] = "run.cancel_requested"
        with patch("behavior_runner.chat", side_effect=closed_chat), patch("behavior_runner.collect_trace", return_value=metadata) as collect:
            timing, barrier = cancellation_chat(api, fixture, {"session_id": "s", "client_message_id": "m"}, "key", Path("unused"), 5)
        collect.assert_called_once_with(api, "s", include_content=False)
        self.assertTrue(barrier["released"])
        self.assertEqual("cancelled", timing["terminal"]["status"])
        self.assertEqual("run:m", timing["terminal"]["run_id"])

    def test_status_query_correlates_execution_id_and_rejects_another_run(self):
        api = Mock()
        api.request.return_value = {"session_id": "s", "execution_id": "run:r", "status": "cancelled"}
        terminal = read_run_status(api, "s", "run:r")
        self.assertEqual("run:r", terminal["run_id"])
        self.assertEqual("cancelled", terminal["status"])
        api.request.return_value["execution_id"] = "run:another"
        with self.assertRaisesRegex(ValueError, "status_response_identity_mismatch"):
            read_run_status(api, "s", "run:r")

    def test_placeholders_dedup_and_intermediate_leak(self):
        t = trace()
        t["pages"].append(copy.deepcopy(t["pages"][0]))
        raw, events = flatten(t)
        self.assertEqual((4, 2), (raw, len(events)))
        case = make_plan("tests", canary="CANARY")["cases"][17]
        e = evidence(case, 1, t, {"case_key": "k", "calls": [], "write_events": []},
                     {"run_id": "r", "status": "completed"}, [], visible=[{"content": "CANARY"}])
        check = next(a for a in case["assertions"] if a["id"] == "no_secret")
        self.assertEqual("fail", evaluate_assertion(check, e)["status"])

    def test_unknown_usage_stops_future_inputs_and_keeps_actual_consumption(self):
        b = ExecutionBudget(10, 5, 60)
        b.observe("s", {"tokens": 5})
        with self.assertRaises(StopBatch):
            b.observe("s", {"tokens": 0, "unknown_usage_calls": 1})
        with self.assertRaises(StopBatch):
            b.before_turn()
        b.observe("s", {"tokens": 8})
        self.assertEqual(8, b.state()["sessions"]["s"])
        with self.assertRaises(StopBatch):
            b.before_turn()

    def test_settlement_usage_can_trip_budget_and_frozen_result_is_not_rewritten(self):
        plan = make_plan("tests")
        case = plan["cases"][0]
        api = Mock()
        api.request.return_value = {"session_id": "s"}
        fixture = Mock()
        fixture.request.return_value = {"case_key": "k", "calls": [], "write_events": [], "complete": True}
        monitor = Mock()
        monitor.check.return_value = True
        monitor.request_fingerprints = {}
        resources = {"agents": {"behavior": {"id": "a"}}, "users": [{"id": str(i)} for i in range(54)],
                     "resolved_model": {"model_id": "test"}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch("behavior_runner.chat", return_value={"terminal": {"run_id": "r", "status": "completed"}}), \
                    patch("behavior_runner.collect_trace", side_effect=[trace(5), trace(11)]):
                with self.assertRaises(StopBatch):
                    execute_case(api, fixture, plan, case, 1, resources, root, ExecutionBudget(10, 5, 60), monitor)
            frozen = (root / "cases/B01/1/evaluation.json").read_bytes()
            result = execute_case(api, fixture, plan, case, 1, resources, root, ExecutionBudget(10, 5, 60), monitor)
            self.assertIn("budget_exceeded", [r["code"] for r in result["blocking_reasons"]])
            self.assertEqual(11, result["metrics"]["usage"]["tokens"])
            self.assertEqual(frozen, (root / "cases/B01/1/evaluation.json").read_bytes())


class ProvenanceTests(unittest.TestCase):
    def test_authentic_artifact_required(self):
        report = json.dumps({"binding": {"ci_run_id": "7", "ci_repository": "org/repo", "ci_run_attempt": 1}}).encode()
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("summary.json", report)
        values = [
            {"status": "completed", "conclusion": "success", "event": "workflow_dispatch", "head_sha": "rev", "run_attempt": 1,
             "path": ".github/workflows/agent-behavior-eval.yml", "repository": {"full_name": "org/repo"}},
            {"protection_rules": [{"type": "required_reviewers", "reviewers": [{"id": 1}]}]},
            [{"state": "approved", "environments": [{"name": "agent-eval"}]}],
            {"total_count": 1, "artifacts": [{"id": 2, "name": "agent-behavior-evaluation-public", "expired": False,
                                               "workflow_run": {"head_sha": "rev"}}]}, stream.getvalue()]
        self.assertTrue(verify_ci(report, "org/repo", "7", "rev", Mock(side_effect=values))["verified"])
        with self.assertRaisesRegex(ValueError, "ci_artifact_content_mismatch"):
            verify_ci(report + b" ", "org/repo", "7", "rev", Mock(side_effect=values))
        with self.assertRaisesRegex(ValueError, "ci_environment_not_protected"):
            verify_ci(report, "org/repo", "7", "rev", Mock(side_effect=[values[0], {"protection_rules": []}]))


class CliTests(unittest.TestCase):
    def test_local_run_never_claims_verified_environment_or_release_eligibility(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = parser().parse_args(["local-run", "--root", directory, "--model-id", "test",
                "--deployment-revision", "local-unverified", "--max-tokens", "100", "--max-turns", "10", "--max-seconds", "60"])
            batch = {"lifecycle": "settling", "binding": {}, "budget": {}, "blocking_reasons": []}
            with patch("behavior_main.verify_environment") as verify, \
                    patch("behavior_main.prepare", return_value=(Mock(), Mock(), {})), \
                    patch("behavior_main.run_batch", return_value=batch), \
                    patch("mcp_fixture.McpFixtureServer") as server:
                server.return_value.server_address = ("127.0.0.1", 1234)
                self.assertEqual(1, ci_run(args, root))
                verify.assert_not_called()
            report = json.loads((root / "public/summary.json").read_text(encoding="utf-8"))
            self.assertEqual("local", report["binding"]["validation_scope"])
            self.assertFalse(report["release_eligible"])
            self.assertNotIn("invalid_environment", [r["code"] for r in report["blocking_reasons"]])

    def test_selected_local_success_exits_zero_but_never_qualifies_for_release(self):
        with tempfile.TemporaryDirectory() as directory:
            selection = {"case_ids": ["B02", "B15"], "repetitions": 1, "turn_timeout_seconds": 90}
            plan = create_plan(Path(directory), "selected", selection)
            self.assertEqual([("B02", 1), ("B15", 1)], experiments(plan))
            binding = {"deployment_revision": "rev", "suite_version": plan["suite_version"], "evaluator_version": "behavior-2",
                       "config_digest": "cfg", "model_id": "test", "model_version": None,
                       "settlement_seconds": 60, "validation_scope": "local"}
            rows = [{"case_id": cid, "repetition": rep, "behavior_status": "passed", "blocking_reasons": [],
                     "metrics": {}, "assertions": []} for cid, rep in experiments(plan)]
            report = aggregate(plan, rows, binding)
            self.assertEqual(0, outcome_exit_code(report, local=True))
            self.assertEqual(1, outcome_exit_code(report))
            self.assertFalse(report["release_eligible"])
            self.assertIn("local_evidence_not_release_qualified", release_check(report, report["binding"], plan)["problems"])
            self.assertEqual(2, report["metrics"]["planned"])
            self.assertIn("B01", report["metrics"]["out_of_scope"])
            missing = aggregate(plan, rows[:1], binding)
            self.assertEqual(1, outcome_exit_code(missing, local=True))
            self.assertEqual(["B15"], [r["case_id"] for r in missing["blocking_reasons"]])
            rows[0].update(behavior_status="failed", blocking_reasons=[{"code": "failed_behavior", "detail": "wrong_argument"}])
            self.assertEqual(1, outcome_exit_code(aggregate(plan, rows, binding), local=True))

    def test_selected_plan_provisions_only_selected_users_and_profiles(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan = create_plan(root, "selected", {"case_ids": ["B02"], "repetitions": 1, "turn_timeout_seconds": 90})
            args = parser().parse_args(["local-run", "--root", directory, "--model-id", "test"])
            fixture = Mock()
            fixture.request.return_value = {"batch": "selected"}
            with patch("behavior_main.login"), patch("behavior_main.resolve_model", return_value={"id": 1}), \
                    patch("behavior_main.provision", return_value={}) as provision:
                prepare(args, root, plan, fixture)
                self.assertEqual(1, provision.call_args.kwargs["user_count"])
                selected_profile = plan["cases"][1]["profile"]
                self.assertEqual([selected_profile], [p["key"] for p in provision.call_args.args[3]])

    def test_local_release_check_rejects_before_requesting_ci_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan = create_plan(root, "selected", {"case_ids": ["B02"], "repetitions": 1, "turn_timeout_seconds": 90})
            binding = {"deployment_revision": "rev", "suite_version": plan["suite_version"], "evaluator_version": "behavior-2",
                       "config_digest": "cfg", "model_id": "test", "model_version": None,
                       "settlement_seconds": 60, "validation_scope": "local"}
            report = aggregate(plan, [], binding)
            (root / "public").mkdir()
            (root / "public/summary.json").write_text(json.dumps(report), encoding="utf-8")
            (root / "expected.json").write_text(json.dumps(report["binding"]), encoding="utf-8")
            output = io.StringIO()
            with patch("behavior_main.private_root", return_value=root), patch("behavior_release.verify_ci") as verify, \
                    patch("sys.stdout", output):
                self.assertEqual(1, main(["release-check", "--root", directory, "--expected-binding", str(root / "expected.json")]))
                verify.assert_not_called()
            self.assertIn("local_evidence_not_release_qualified", output.getvalue())

    def test_selection_is_frozen_and_formal_ci_rejects_it_before_provision(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            create_plan(root, "selected", {"case_ids": ["B02"], "repetitions": 1, "turn_timeout_seconds": 90})
            common = ["--root", directory, "--model-id", "test", "--deployment-revision", "rev",
                      "--max-tokens", "100", "--max-turns", "10", "--max-seconds", "60"]
            with patch("behavior_main.prepare") as prepare:
                args = parser().parse_args(["local-run", *common, "--cases", "B03"])
                with self.assertRaisesRegex(ValueError, "frozen_local_selection_mismatch"):
                    ci_run(args, root)
                args = parser().parse_args(["ci-run", *common, "--environment-receipt", "receipt.json"])
                with self.assertRaisesRegex(ValueError, "ci_requires_full_release_plan"):
                    ci_run(args, root)
                prepare.assert_not_called()

    def test_environment_failure_writes_explicit_report_without_provision_or_model(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = parser().parse_args(["ci-run", "--root", directory, "--model-id", "test",
                "--deployment-revision", "rev", "--environment-receipt", str(root / "absent.json"),
                "--max-tokens", "100", "--max-turns", "10", "--max-seconds", "60"])
            with patch("behavior_main.verify_environment", side_effect=ValueError("invalid_environment")), \
                    patch("behavior_main.prepare") as prepare:
                self.assertEqual(1, ci_run(args, root))
                prepare.assert_not_called()
            report = json.loads((root / "public/summary.json").read_text(encoding="utf-8"))
            self.assertFalse(report["release_eligible"])
            self.assertEqual(0, report["metrics"]["executed"])
            self.assertIn("invalid_environment", [r["code"] for r in report["blocking_reasons"]])
            with self.assertRaisesRegex(ValueError, "batch_already_started"):
                ci_run(args, root)

    def test_prepare_failure_freezes_environment_failure_without_claiming_behavior_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = parser().parse_args(["local-run", "--root", directory, "--model-id", "test",
                "--deployment-revision", "local-unverified", "--cases", "B02", "--repetitions", "1",
                "--max-tokens", "100", "--max-turns", "10", "--max-seconds", "60"])
            with patch("behavior_main.prepare", side_effect=PermissionError("must-not-leak")), \
                    patch("behavior_main.run_batch") as run, patch("mcp_fixture.McpFixtureServer") as server:
                server.return_value.server_address = ("127.0.0.1", 1234)
                self.assertEqual(1, ci_run(args, root))
                run.assert_not_called()
            report = json.loads((root / "public/summary.json").read_text(encoding="utf-8"))
            self.assertEqual("finalized", report["lifecycle"])
            self.assertEqual("unknown", report["behavior_status"])
            self.assertEqual(0, report["metrics"]["executed"])
            self.assertIn("prepare_or_run_failed:PermissionError", [r["detail"] for r in report["blocking_reasons"]])


if __name__ == "__main__":
    unittest.main()
