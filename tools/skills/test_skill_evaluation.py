"""评测工具的未知受理、冻结版本、触发事实和未知用量边界回归。"""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("skill_evaluation", ROOT / "tools/skills/evaluation/run_evaluation.py")
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)


class RecordingApi:
    def __init__(self, snapshot, fail=None):
        self.snapshot = snapshot
        self.fail = fail
        self.calls = []

    def request(self, route, method="GET", body=None, **kwargs):
        self.calls.append((route, method, body))
        if route.startswith("/agents/detail"):
            return self.snapshot["agent"]
        if route.startswith("/agents/capability-mounts"):
            return {"mounts": self.snapshot["mounts"]}
        if route.startswith("/agents/revisions/detail"):
            return self.snapshot["published"]
        if route == self.fail:
            raise RuntimeError("OUTCOME_UNKNOWN")
        if route == "/chat/sessions":
            return {"session_id": "222"}
        if route == "/chat":
            return {"session_id": "222", "execution_id": "run:abc", "input_id": "abc", "status": "queued"}
        raise AssertionError(route)


class SkillEvaluationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="skill-evaluation-")
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name)
        self.variant = {"id": "candidate", "agent_id": "111", "expected_skill_versions": {"10": "20"}}
        self.case = {"id": "positive", "prompt": "实际生成文件", "target_skill_definition_id": "10", "should_activate": True,
                     "assertions": [{"type": "answer_contains", "value": "真实结果"}]}
        self.snapshot = {"agent": {"id": "111", "status": "published", "model_config_id": "31", "system_prompt": "fixed"},
                         "mounts": [{"mount_id": "123", "capability_type": "SKILL", "capability_definition_id": 10, "capability_version_id": 20, "status": "ACTIVE"}]}
        self.snapshot["agent"].update(published_revision=1, has_draft_changes=False, mounts=copy.deepcopy(self.snapshot["mounts"]))
        self.snapshot["published"] = copy.deepcopy(self.snapshot["agent"])
        self.frozen = {"suite": {"end_user_id": "999", "variants": [self.variant], "cases": [self.case]}, "suite_sha256": "frozen",
                       "agents": {"candidate": self.snapshot}}
        self.receipt = {"response": {"execution_id": "run:abc"}, "request": {"session_id": "222"}, "accepted_at": evaluation.now()}
        self.history = {"activities": [{"node_id": "run:abc", "output": "真实结果"}, {"node_id": "run:other", "output": "无关结果"}]}

    def assessment(self, observation, state="completed"):
        return evaluation.assess(self.frozen, self.variant, self.case, self.receipt, {"status": state}, self.history, [], [], observation, self.output)

    def test_unknown_session_creation_and_chat_acceptance_never_resubmitted(self):
        for route in ("/chat/sessions", "/chat"):
            with self.subTest(route=route), tempfile.TemporaryDirectory() as directory:
                output = Path(directory)
                api = RecordingApi(self.snapshot, fail=route)
                with self.assertRaises(RuntimeError):
                    evaluation.submit(api, self.frozen, output, self.variant, self.case, 0)
                before = len(api.calls)
                with self.assertRaisesRegex(ValueError, "未知受理"):
                    evaluation.submit(api, self.frozen, output, self.variant, self.case, 0)
                self.assertEqual(len(api.calls), before)
                path = evaluation.run_directory(output, self.variant, self.case, 0) / "receipt.json"
                self.assertIn(evaluation.load(path)["state"], ("creating_session", "submitting"))

    def test_submitted_receipt_is_reused_without_new_network_write(self):
        api = RecordingApi(self.snapshot)
        first = evaluation.submit(api, self.frozen, self.output, self.variant, self.case, 0)
        before = len(api.calls)
        second = evaluation.submit(api, self.frozen, self.output, self.variant, self.case, 0)
        self.assertEqual(first, second)
        self.assertEqual(len(api.calls), before)

    def test_frozen_skill_version_and_agent_config_drift_block_before_session(self):
        snapshot = copy.deepcopy(self.snapshot)
        snapshot["agent"]["system_prompt"] = "changed"
        api = RecordingApi(snapshot)
        with self.assertRaisesRegex(ValueError, "不一致|变化"):
            evaluation.submit(api, self.frozen, self.output, self.variant, self.case, 0)
        self.assertFalse(any(method == "POST" for _, method, _ in api.calls))
        snapshot["mounts"][0]["capability_version_id"] = 99
        with self.assertRaisesRegex(ValueError, "不一致|挂载集合"):
            evaluation.agent_snapshot(api, self.variant)

    def test_activation_requires_successful_exact_handle_result(self):
        observation = {"availability": "FULL", "usage": None, "activation_events": [{"value": {"results": [
            {"handle": "skill:123", "status": "BUDGET_EXCEEDED"}, {"handle": "skill:other", "status": "ACTIVATED"}]}}]}
        result = self.assessment(observation)
        self.assertFalse(result["activated"])
        self.assertFalse(result["trigger_correct"])
        observation["activation_events"][0]["value"]["results"].append({"handle": "skill:123", "status": "ACTIVATED"})
        self.assertTrue(self.assessment(observation)["trigger_correct"])

    def test_missing_observation_unknown_usage_and_running_not_success(self):
        unknown = {"availability": "UNKNOWN", "usage": None, "activation_events": []}
        result = self.assessment(unknown, "running")
        self.assertIsNone(result["activated"])
        self.assertIsNone(result["trigger_correct"])
        self.assertIsNone(result["usage"])
        self.assertFalse(result["success"])

    def test_report_preserves_unknown_tokens_and_missing_samples(self):
        result = self.assessment({"availability": "UNKNOWN", "usage": None, "activation_events": []})
        path = evaluation.run_directory(self.output, self.variant, self.case, 0) / "assessment.json"
        evaluation.save(path, result)
        self.frozen["suite"]["repetitions"] = 2
        report = evaluation.report(self.frozen, self.output)
        self.assertFalse(report["complete"])
        self.assertEqual(report["observed_runs"], 1)
        token = report["variants"][0]["tokens"]["prompt_tokens"]
        self.assertEqual(token["unknown_runs"], 1)
        self.assertIsNone(token["mean"])
        self.assertIsNone(report["variants"][0]["trigger_accuracy"])

    def test_download_fact_required_for_deliverable_assertion(self):
        self.case["assertions"] = [{"type": "deliverable_format", "value": "python"}]
        result = self.assessment({"availability": "UNKNOWN", "usage": None, "activation_events": []})
        self.assertFalse(result["success"])
        self.assertFalse(result["assertions"][0]["passed"])

    def trajectory(self, result=None, call_id="call-1"):
        wire = "sf_query_frozen_hash"
        arguments = {"aggregates": [{"function": "count", "alias": "total"}]}
        request = {"tools": [{"wire_name": wire, "description": 'logical_name="business_data_query"\n'}], "messages": [
            {"role": "assistant", "tool_calls": [{"call_id": "call-1", "wire_name": wire, "arguments": json.dumps(arguments)}]},
            {"role": "tool", "tool_call_id": call_id, "content": "total=17", "contents": [{"type": "text", "text": "total=17"}]}]}
        result = result or {"isError": False, "resultType": "complete", "structuredContent": {"rows": [{"total": 17}], "truncated": False}, "content": [{"type": "text", "text": "total=17"}]}
        values = {"request": request, "outcome": {"assistant_turn": [{"call_id": "call-1", "wire_name": wire, "arguments_json": json.dumps(arguments)}]}, "arguments": arguments, "result": result}
        return {"run_id": "run:abc", "nodes": [
            {"kind": "LLM", "sequence": "1", "iteration": "1", "input": {"ref": "request"}, "output": {"ref": "outcome"}},
            {"kind": "TOOL", "sequence": "2", "iteration": "1", "name": wire, "status": "SUCCEEDED", "action_id": "tool-action", "input": {"ref": "arguments"}, "output": {"ref": "result"}}],
            "contents": {k: {"availability": "FULL", "value": json.dumps(v)} for k, v in values.items()}}

    def tool_assertion(self):
        return {"type": "tool_call", "tool_name": "business_data_query", "arguments": {"aggregates": [{"function": "count", "alias": "total"}]},
                "arguments_absent_paths": ["aggregates.0.field"], "result": {"rows": [{"total": 17}]}, "max_executions": 1}

    def test_structured_tool_assertion_requires_paired_actual_result_and_exact_parameters(self):
        assertion = self.tool_assertion()
        self.assertTrue(evaluation.tool_check(assertion, self.trajectory())["passed"])
        self.assertFalse(evaluation.tool_check(assertion, self.trajectory(call_id="other-call"))["passed"])
        assertion["arguments"]["aggregates"][0]["function"] = "count_distinct"
        self.assertFalse(evaluation.tool_check(assertion, self.trajectory())["passed"])
        assertion = self.tool_assertion()
        trajectory = self.trajectory()
        request = json.loads(trajectory["contents"]["request"]["value"])
        request["messages"][0]["tool_calls"][0]["arguments"] = json.dumps({"aggregates": [{"function": "count", "alias": "total", "field": "nullableField"}]})
        trajectory["contents"]["request"]["value"] = json.dumps(request)
        self.assertFalse(evaluation.tool_check(assertion, trajectory)["passed"])

    def test_failed_truncated_or_unavailable_tool_evidence_cannot_pass_completed_run(self):
        for field, value in (("isError", True), ("resultType", "partial")):
            trajectory = self.trajectory()
            result = json.loads(trajectory["contents"]["result"]["value"])
            result[field] = value
            trajectory["contents"]["result"]["value"] = json.dumps(result)
            self.assertFalse(evaluation.tool_check(self.tool_assertion(), trajectory)["passed"])
        trajectory = self.trajectory()
        result = json.loads(trajectory["contents"]["result"]["value"])
        result["structuredContent"]["truncated"] = True
        trajectory["contents"]["result"]["value"] = json.dumps(result)
        self.assertFalse(evaluation.tool_check(self.tool_assertion(), trajectory)["passed"])
        trajectory["contents"]["request"]["availability"] = "NOT_RECORDED"
        self.assertEqual(evaluation.tool_check(self.tool_assertion(), trajectory)["availability"], "UNKNOWN")

    def test_reused_call_has_distinct_pairing_but_no_second_execution(self):
        trajectory = self.trajectory()
        request = json.loads(trajectory["contents"]["request"]["value"])
        call = copy.deepcopy(request["messages"][0]); call["tool_calls"][0]["call_id"] = "call-2"
        request["messages"] += [call, {"role": "tool", "tool_call_id": "call-2", "content": "本次工具与参数和之前的调用相同，以下复用已有结果，未重新执行工具。total=17"}]
        trajectory["contents"]["request"]["value"] = json.dumps(request)
        assertion = self.tool_assertion() | {"min_calls": 2, "max_calls": 2}
        result = evaluation.tool_check(assertion, trajectory)
        self.assertTrue(result["passed"])
        self.assertEqual(result["executions"], 1)
        self.assertTrue(result["evidence"][1]["reused"])

    def test_report_recomputes_tool_verdict_instead_of_trusting_pass_summary(self):
        self.case["assertions"] = [self.tool_assertion()]
        directory = evaluation.run_directory(self.output, self.variant, self.case, 0)
        observation = {"availability": "UNKNOWN", "usage": None, "activation_events": []}
        forged = self.assessment(observation) | {"success": True}
        evaluation.save(directory / "assessment.json", forged)
        for name, value in {"receipt": self.receipt, "status": {"status": "completed"}, "history": self.history,
                            "deliverables": [], "downloads": [], "observation": observation}.items():
            evaluation.save(directory / (name + ".json"), value)
        report = evaluation.report(self.frozen, self.output)
        self.assertEqual(report["variants"][0]["successful_runs"], 0)

    def test_compacted_last_request_keeps_earlier_call_evidence_and_conflicts_fail(self):
        trajectory = self.trajectory()
        trajectory["nodes"].append({"kind": "LLM", "sequence": "3", "iteration": "2", "input": {"ref": "compacted"}, "output": {"ref": "final"}})
        trajectory["contents"]["compacted"] = {"availability": "FULL", "value": json.dumps({"tools": [], "messages": []})}
        trajectory["contents"]["final"] = {"availability": "FULL", "value": json.dumps({"assistant_turn": []})}
        self.assertTrue(evaluation.tool_check(self.tool_assertion(), trajectory)["passed"])
        conflicting = json.loads(trajectory["contents"]["request"]["value"])
        conflicting["messages"][0]["tool_calls"][0]["arguments"] = '{"changed":true}'
        trajectory["contents"]["compacted"]["value"] = json.dumps(conflicting)
        result = evaluation.tool_check(self.tool_assertion(), trajectory)
        self.assertFalse(result["passed"])
        self.assertEqual(result["reason"], "conflicting_call_identity")

    def test_mcp_single_text_json_object_is_structured_result_without_guessing_multiple_texts(self):
        result = {"isError": False, "resultType": "complete", "content": [{"type": "text", "text": '{"rows":[{"total":17}],"truncated":false}'}]}
        verdict = evaluation.tool_check(self.tool_assertion(), self.trajectory(result=result))
        self.assertTrue(verdict["passed"])
        self.assertEqual(verdict["evidence"][0]["result_source"], "single_text_json")
        for content in ([{"type": "text", "text": "not JSON"}], [{"type": "text", "text": "[]"}], result["content"] * 2):
            verdict = evaluation.tool_check(self.tool_assertion(), self.trajectory(result=result | {"content": content}))
            self.assertFalse(verdict["passed"])
            self.assertEqual(verdict["availability"], "UNKNOWN")
        result["structuredContent"] = {"rows": [{"total": 99}], "truncated": False}
        self.assertFalse(evaluation.tool_check(self.tool_assertion(), self.trajectory(result=result))["passed"])

    def test_recorded_failed_tool_and_failure_reuse_are_full_evidence_not_unknown(self):
        trajectory = self.trajectory(result={"status": "FAILED", "reason_code": "mcp_tool_rejected"})
        trajectory["nodes"][1]["status"] = "FAILED"
        request = json.loads(trajectory["contents"]["request"]["value"])
        call = copy.deepcopy(request["messages"][0]); call["tool_calls"][0]["call_id"] = "call-2"
        request["messages"] += [call, {"role": "tool", "tool_call_id": "call-2", "content": "本次工具与参数和之前的调用相同，以下复用已有结果，未重新执行工具。工具失败"}]
        trajectory["contents"]["request"]["value"] = json.dumps(request)
        result = evaluation.tool_check(self.tool_assertion() | {"min_calls": 2, "max_calls": 2}, trajectory)
        self.assertFalse(result["passed"])
        self.assertEqual(result["availability"], "FULL")
        self.assertEqual(result["reason"], "actual_tool_failed")
        self.assertEqual(result["executions"], 1)
        self.assertTrue(result["evidence"][1]["reused"])
        self.assertEqual(result["evidence"][1]["actual_status"], "FAILED")
        error = evaluation.tool_check(self.tool_assertion(), self.trajectory(result={"isError": True, "content": []}))
        self.assertEqual(error["reason"], "mcp_tool_error")
        self.assertEqual(error["availability"], "FULL")

    def test_report_labels_automatic_scope_and_required_semantic_review(self):
        self.case["manual_review_required"] = True
        report = evaluation.report(self.frozen, self.output)
        self.assertEqual(report["success_scope"], "automatic_assertions_only")
        self.assertEqual(report["manual_review_required_runs"], 1)
        self.assertIn("不代表业务或完整语义验收通过", (self.output / "report.md").read_text(encoding="utf-8"))

    def test_unknown_model_with_null_assistant_turn_retains_prior_calls_and_known_failed_tool(self):
        trajectory = self.trajectory(result={"status": "FAILED", "reason_code": "mcp_tool_rejected"})
        trajectory["nodes"][1]["status"] = "FAILED"
        trajectory["nodes"].append({"kind": "LLM", "sequence": "3", "iteration": "2", "action_id": "unknown-model",
                                    "input": {"ref": "request"}, "output": {"ref": "unknown"}})
        trajectory["contents"]["unknown"] = {"availability": "FULL", "value": json.dumps({
            "status": "OUTCOME_UNKNOWN", "model": None, "finish_reason": None, "output": None,
            "assistant_turn": None, "reasoning_content": None, "usage": None,
            "reason_code": "model_outcome_unknown", "retry_mode": "NONE", "retry_at": None})}
        result = evaluation.tool_check(self.tool_assertion(), trajectory)
        self.assertFalse(result["passed"])
        self.assertEqual(result["availability"], "FULL")
        self.assertEqual(result["reason"], "actual_tool_failed")
        self.assertEqual(result["requested_calls"], 1)
        self.assertEqual(result["evidence"][0]["actual_status"], "FAILED")
        self.assertEqual(result["unknown_model_outcomes"][0]["status"], "OUTCOME_UNKNOWN")
        trajectory["nodes"][1]["status"] = "SUCCEEDED"
        trajectory["contents"]["result"] = self.trajectory()["contents"]["result"]
        uncertain = evaluation.tool_check(self.tool_assertion(), trajectory)
        self.assertFalse(uncertain["passed"])
        self.assertEqual(uncertain["availability"], "UNKNOWN")
        self.assertEqual(uncertain["reason"], "model_outcome_unknown")
        self.assertEqual(uncertain["evidence"][0]["actual_status"], "SUCCEEDED")
        self.assertFalse(self.assessment({"availability": "UNKNOWN", "usage": None, "activation_events": []}, "suspended")["success"])

    def test_draft_agent_rejected_and_always_skill_not_false_negative(self):
        self.snapshot["agent"]["has_draft_changes"] = True
        with self.assertRaisesRegex(ValueError, "已发布不可变"):
            evaluation.agent_snapshot(RecordingApi(self.snapshot), self.variant)
        self.snapshot["mounts"][0]["skill_config"] = {"activation_mode": "ALWAYS"}
        result = self.assessment({"availability": "FULL", "usage": None, "activation_events": []})
        self.assertIsNone(result["trigger_correct"])

    def test_publication_display_timestamp_precision_does_not_change_frozen_execution(self):
        self.snapshot["agent"]["published_at"] = "2026-10-27T22:47:12.931667+08:00"
        self.snapshot["published"]["published_at"] = "2026-10-27T22:47:12.9316669+08:00"
        self.assertEqual(evaluation.agent_snapshot(RecordingApi(self.snapshot), self.variant)["agent"]["published_revision"], 1)
        self.snapshot["published"]["model_config_id"] = "different"
        with self.assertRaisesRegex(ValueError, "不一致"):
            evaluation.agent_snapshot(RecordingApi(self.snapshot), self.variant)


if __name__ == "__main__":
    unittest.main()
