"""验证探针本身的判定与恢复边界，不调用真实模型。"""
import io
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from collections import Counter
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
from cases import generate
from client import ApiError, ModelQuotaStop, quota_signal, sse_events, collect_trace, poll_terminal
from runner import Budget, evaluate, events_for, chat, run_case, save, phase_times, failure_codes_for, has_tool_error
from report import build
from main import batch_lock


class RunnerTest(unittest.TestCase):
    def test_typed_tool_failure_without_output(self):
        def event(status, reason):
            return {"event_type":"agent.tool-result", "payload":{"status":status,"reasonCode":reason,"output":None}}
        self.assertTrue(has_tool_error([event("FAILED","mcp_tool_rejected")]))
        self.assertFalse(has_tool_error([event("FAILED","tool_binding_missing")]))
        self.assertFalse(has_tool_error([event("SUCCEEDED",None)]))

    def test_matrix_is_reproducible_and_exact(self):
        plan = generate()
        self.assertEqual(plan, generate())
        self.assertEqual(1000, len(plan["cases"]))
        self.assertEqual(1000, len({c["id"] for c in plan["cases"]}))
        self.assertEqual({100}, set(Counter(c["profile"] for c in plan["cases"]).values()))
        self.assertEqual({20}, set(Counter(c["user"] for c in plan["cases"]).values()))
        counts = Counter(c["category"] for c in plan["cases"])
        self.assertEqual(80, counts["long_context"])
        self.assertEqual(60, counts["cross_session"])
        self.assertEqual(158, counts["direct"])
        self.assertEqual(1, counts["parallel_read"])
        self.assertEqual(1, counts["reference_read"])
        self.assertEqual(1, counts["pause_resume"])
        self.assertTrue(all(len(c["turns"]) > 25 for c in plan["cases"] if c["category"] == "long_context"))
        self.assertEqual(set(counts) - {"reference_read"}, {c["category"] for c in plan["cases"][:100]})

        reference = next(case for case in plan["cases"] if case["id"] == "u11-query-1")
        self.assertEqual("reference_read", reference["category"])
        marker = reference["turns"][0]["expect"]["contains"][0]
        value = reference["fixture"]["value"]
        self.assertGreater(len(value), 30_000)
        self.assertEqual(1, value.count(marker))
        self.assertFalse(value.startswith(marker))
        self.assertFalse(value.endswith(marker))
        self.assertEqual(value.index(marker), len(value) - value.index(marker) - len(marker))
        query_profile = next(profile for profile in plan["profiles"] if profile["key"] == "query")
        self.assertIn("read_context_reference", query_profile["tools"])
        parallel = next(case for case in plan["cases"] if case["category"] == "parallel_read")
        records = [parallel["fixture"], *parallel["additional_fixtures"]]
        self.assertEqual(2, len({record["case_key"] for record in records}))
        self.assertEqual(2, len({record["value"] for record in records}))
        self.assertEqual([record["value"] for record in records], parallel["turns"][0]["expect"]["contains"])
        for record in records:
            self.assertIn(record["case_key"], parallel["turns"][0]["message"])

    def test_reference_read_requires_persisted_runtime_diagnostic(self):
        completion = {"event_type": "run.completed", "payload": {
            "outcome": {"report": {"output": "REF-MIDDLE-CODE"}}}}
        tools = [
            {"event_type": "agent.tool-result", "payload": {"status": "SUCCEEDED"}},
            {"event_type": "agent.tool-result", "payload": {"status": "SUCCEEDED"}},
        ]
        diagnostic = {"event_type": "agent.runtime-diagnostic", "payload": {"kind": "REFERENCE_READ"}}
        expect = {"status": "completed", "contains": ["REF-MIDDLE-CODE"], "min_tools": 2,
                  "runtime_diagnostic_kind": "REFERENCE_READ"}

        accepted = evaluate(expect, {"status": "completed"}, [completion, *tools, diagnostic])
        rejected = evaluate(expect, {"status": "completed"}, [completion, *tools])
        wrong_kind = evaluate(expect, {"status": "completed"}, [completion, *tools, {
            "event_type": "agent.runtime-diagnostic", "payload": {"kind": "REFERENCE_WRITE"}}])

        self.assertEqual([], accepted["errors"])
        self.assertIn("missing_runtime_diagnostic:REFERENCE_READ", rejected["errors"])
        self.assertIn("missing_runtime_diagnostic:REFERENCE_READ", wrong_kind["errors"])

    def test_parallel_tools_require_same_parent_operation_and_distinct_invocations(self):
        operation = {"runIdentity": {"runRef": {"tenantId": "tenant", "runId": "run"}},
                     "loopId": "loop", "iteration": "1"}
        facts = [
            {"event_type": "run.completed", "payload": {"outcome": {"report": {"output": "done"}}}},
            {"event_type": "agent.tool-result", "payload": {
                "status": "SUCCEEDED", "operation": operation,
                "invocationId": "tool-invocation:first"}},
            {"event_type": "agent.tool-result", "payload": {
                "status": "SUCCEEDED", "operation": dict(operation),
                "invocationId": "tool-invocation:second"}},
        ]
        result = evaluate({"status": "completed", "min_tools": 2, "min_parallel_tools": 2},
                          {"status": "completed"}, facts)
        self.assertNotIn("missing_same_operation_tool_batch", result["errors"])
        for fact in facts[1:]:
            fact["payload"].pop("operation")
        missing_parent = evaluate({"status": "completed", "min_parallel_tools": 2},
                                  {"status": "completed"}, facts)
        self.assertIn("missing_same_operation_tool_batch", missing_parent["errors"])

    def test_serial_tool_rounds_cannot_satisfy_parallel_batch_expectation(self):
        def tool(iteration, invocation):
            return {"event_type": "agent.tool-result", "payload": {
                "status": "SUCCEEDED",
                "operation": {"runIdentity": {"runRef": {"tenantId": "tenant", "runId": "run"}},
                              "loopId": "loop", "iteration": str(iteration)},
                "invocationId": invocation}}
        facts = [
            {"event_type": "run.completed", "payload": {"outcome": {"report": {"output": "done"}}}},
            tool(1, "tool-invocation:first"),
            tool(2, "tool-invocation:second"),
        ]
        result = evaluate({"status": "completed", "min_tools": 2, "min_parallel_tools": 2},
                          {"status": "completed"}, facts)
        self.assertIn("missing_same_operation_tool_batch", result["errors"])

    def test_sse_multiline_and_failed_terminal(self):
        data = b': heartbeat\r\nevent: execution.completed\r\nid: e1\r\ndata: {"status":\r\ndata: "failed"}\r\n\r\n'
        event = list(sse_events(io.BytesIO(data), time.monotonic()+2))[0]
        self.assertEqual("e1", event["id"])
        self.assertEqual("failed", event["data"]["status"])
        result = evaluate({"status":"completed"}, event["data"], [])
        self.assertTrue(result["errors"])

    def test_truncated_sse_is_not_success(self):
        with self.assertRaises(ApiError):
            list(sse_events(io.BytesIO(b'data: {"status":"completed"}\n'), time.monotonic()+2))

    def test_assertions_use_committed_output(self):
        facts = [{"event_type":"run.completed","payload":{"outcome":{"report":{"output":'{"code":"A","quantity":2}'}}}}]
        self.assertFalse(evaluate({"status":"completed","json_equals":{"code":"A","quantity":2}}, {"status":"completed"}, facts)["errors"])
        self.assertTrue(evaluate({"status":"completed","json_equals":{"code":"B","quantity":2}}, {"status":"completed"}, facts)["errors"])
        self.assertIn("unexpected_tool_call", evaluate({"status":"completed","max_tools":0}, {"status":"completed"}, facts + [{"event_type":"agent.tool-result","payload":{"status":"SUCCEEDED"}}])["errors"])

    def test_skill_minimum_requires_successful_activation_result(self):
        rejected = [{"event_type":"skill.activation", "payload":{"results":[{"handle":"skill:collect", "status":"CONFLICT"}]}}]
        accepted = [{"event_type":"skill.activation", "payload":{"results":[{"handle":"skill:collect", "status":"ACTIVATED"}]}}]
        expect = {"status":"completed", "min_skills":1}
        self.assertIn("missing_successful_skill_activation", evaluate(expect, {"status":"completed"}, rejected)["errors"])
        self.assertNotIn("missing_successful_skill_activation", evaluate(expect, {"status":"completed"}, accepted)["errors"])

    def test_trace_pagination_and_current_run_filter(self):
        phase = {"event_id":"a","event_type":"run.completed","content_ref":"ref"}
        class Fake:
            def request(self, method, path):
                if "/content?" in path: return {"availability":"FULL","value":{"ok":True}}
                if "cursor=next" in path: return {"rounds":[],"next_cursor":None,"usage_summary":{"tokens":20}}
                return {"next_cursor":"next","rounds":[{"runs":[{"run_id":"run:x","steps":[{"phases":[phase,phase]}]}]}]}
        trace = collect_trace(Fake(), "s")
        self.assertEqual(2,len(trace["pages"]))
        self.assertEqual(1,len(events_for(trace,"x")))
        self.assertEqual([],events_for(trace,"other"))

    def test_reference_diagnostic_loads_source_without_refetching_typed_stage_metrics(self):
        calls = []
        class Fake:
            def request(self, method, path):
                calls.append(path)
                if "/content?" in path:
                    self.assert_reference(path)
                    return {"availability":"FULL","value":{"kind":"REFERENCE_READ","metrics":{"characters":256}}}
                return {"next_cursor":None,"diagnostics":{"runtime_reference_read_measurements":[{"event_id":"read"}],
                        "runtime_stage_measurements":[{"event_id":"stage","elapsed_nanos":123}]},
                        "rounds":[{"runs":[{"run_id":"run:m","steps":[{"phases":[
                            {"event_id":key,"event_type":"agent.runtime-diagnostic","content_ref":key}
                            for key in ("stage","read")]}]}]}]}
            def assert_reference(self, path):
                if "ref=read" not in path: raise AssertionError("阶段指标不应重复下载正文")
        trace = collect_trace(Fake(), "s")
        facts = events_for(trace, "m")
        self.assertEqual(2, len(calls))
        self.assertEqual("REFERENCE_READ", next(f["payload"]["kind"] for f in facts if f["event_id"] == "read"))
        self.assertEqual(123, trace["pages"][0]["diagnostics"]["runtime_stage_measurements"][0]["elapsed_nanos"])

    def test_resume_never_posts_chat(self):
        class Fake:
            def open(self, *args, **kwargs): raise AssertionError("不得重发POST")
            def request(self, method, path, body=None, headers=None):
                if method != "GET" or not path.startswith("/chat/events?") or body is not None:
                    raise AssertionError("恢复只能读取 /chat/events")
                return {"next_cursor":"output:m:2","events":[
                    {"event":"execution.accepted", "event_id":"output:m:1",
                     "data":{"input_id":"m", "run_id":"run:m"}},
                    {"event":"execution.completed", "event_id":"output:m:2",
                     "data":{"input_id":"m", "run_id":"run:m", "status":"completed"}}]}
        with tempfile.TemporaryDirectory() as d:
            result = chat(Fake(), {"session_id":"s","client_message_id":"m"}, Path(d)/"sse", 2, resume=True)
        self.assertTrue(result["resumed"])
        self.assertEqual("completed", result["terminal"]["status"])

    def test_unknown_session_creation_does_not_retry(self):
        class Fake:
            def request(self,*a,**kw): raise AssertionError("不得重发建会话请求")
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            save(root/"cases"/"x"/"state.json", {"session_creating":True,"turns":{}})
            with self.assertRaisesRegex(RuntimeError,"session_creation_outcome_unknown"):
                run_case(Fake(),Fake(),{"id":"x","category":"greeting","phase":0},{},root,2,Budget(10,10,100,3))

    def test_unexpected_eof_remains_a_transport_failure_after_recovery(self):
        class Fake:
            def open(self, method, path, body=None, headers=None, timeout=None):
                if method != "GET" or not path.startswith("/chat/events?") or body is not None:
                    raise AssertionError("SSE 只能 GET /chat/events")
                return io.BytesIO(b'')
            def request(self, method, path, body=None, headers=None):
                if method == "POST" and path == "/chat":
                    return {"execution_id":"run:m"}
                if method != "GET" or not path.startswith("/chat/events?") or body is not None:
                    raise AssertionError("恢复只能读取 /chat/events")
                return {"next_cursor":"output:m:2","events":[
                    {"event":"execution.accepted", "event_id":"output:m:1",
                     "data":{"input_id":"m", "run_id":"run:m"}},
                    {"event":"execution.completed", "event_id":"output:m:2",
                     "data":{"input_id":"m", "run_id":"run:m", "status":"completed"}}]}
        with tempfile.TemporaryDirectory() as d:
            result = chat(Fake(),{"session_id":"s","client_message_id":"m"},Path(d)/"sse",2)
        self.assertEqual("completed",result["terminal"]["status"])
        self.assertEqual("eof_without_terminal",result["transport_error"])

    def test_budget_fails_closed_and_lock_excludes_second_runner(self):
        budget = Budget(10,1,100,2)
        budget.acquire()
        with self.assertRaises(RuntimeError): budget.acquire()
        with tempfile.TemporaryDirectory() as d:
            with batch_lock(Path(d)):
                with self.assertRaises(RuntimeError):
                    with batch_lock(Path(d)): pass
            self.assertFalse((Path(d)/"execution.lock").exists())

    def test_report_counts_unrun_not_as_passed(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            save(root/"plan.json",generate())
            result = build(root)
            self.assertEqual(1000,result["counts"]["not_run"])
            self.assertIsNone(result["completed_pass_rate"])
            self.assertIsNone(result["latency_seconds"]["p95"])

    def test_external_timings_deduplicate_attempts(self):
        event = {"detail":{"attempts":[{"attemptId":"a", "callKind":"MODEL_STREAM",
                  "externalStartedAt":"2026-09-23T01:00:00Z", "externalEndedAt":"2026-09-23T01:00:02Z"}]}}
        self.assertEqual({"MODEL_STREAM":2.0}, phase_times([event,event])["external_seconds_by_call_kind"])

    def test_quota_stop_prevents_next_request(self):
        budget = Budget(10,100,10000,10)
        budget.stopped.set()
        with self.assertRaises(ModelQuotaStop): budget.acquire()
        self.assertTrue(quota_signal("insufficient_quota"))
        self.assertTrue(quota_signal("余额不足"))
        self.assertFalse(quota_signal("task_turn_scope_violation"))

    def test_quota_http_is_not_swallowed_by_stream_recovery(self):
        class Fake:
            def open(self,*a,**kw): raise AssertionError("额度错误不能进入SSE恢复")
            def request(self, method, path, body=None, headers=None):
                if (method, path) != ("POST", "/chat"):
                    raise AssertionError("额度错误不能转为自动重试")
                raise ModelQuotaStop("HTTP 402")
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ModelQuotaStop):
                chat(Fake(),{"session_id":"s","client_message_id":"m"},Path(d)/"sse",2)

    def test_model_stop_persists_turn_usage_and_unique_codes_before_interrupting(self):
        class Fake:
            def open(self, method, path, body=None, *args, **kwargs):
                if method != "GET" or not path.startswith("/chat/events?") or body is not None:
                    raise AssertionError("SSE 只能 GET /chat/events")
                accepted = {"input_id":self.message_id, "run_id":"run:" + self.message_id}
                terminal = {**accepted, "status":"failed", "reason":"model_unavailable"}
                return io.BytesIO((
                    "event: execution.accepted\ndata: " + json.dumps(accepted) + "\n\n"
                    + "event: execution.completed\ndata: " + json.dumps(terminal) + "\n\n").encode())
            def request(self, method, path, body=None, headers=None):
                if path == "/chat/sessions":
                    return {"session_id":"s"}
                if method == "POST" and path == "/chat":
                    self.message_id = body["client_message_id"]
                    return {"execution_id":"run:" + self.message_id}
                if path.startswith("/chat/sessions/trace?"):
                    return {"rounds":[{"runs":[{"run_id":"run:" + self.message_id, "steps":[{"phases":[{
                        "event_id":"failed", "event_type":"run.failed",
                        "detail":{"reasonCode":"MODEL_UNAVAILABLE", "attempts":[
                            {"externalErrorCode":"insufficient_quota"}, {"externalErrorCode":"insufficient_quota"}]}}]}]}]}],
                        "usage_summary":{"tokens":9}}
                raise AssertionError(path)
        class Fixture:
            def request(self, method, path, body=None, headers=None):
                if method == "POST": return body
                if path.startswith("/audit?"): return {"call_counts":{}, "write_events":[]}
                raise AssertionError(path)
        case = {"id":"x", "category":"greeting", "profile":"social", "user":0,
                "fixture":{"case_key":"batch:x", "value":"v"},
                "turns":[{"message":"hello", "expect":{"status":"completed"}}]}
        resources = {"agents":{"social":{"id":"a"}}, "users":{0:{"id":"u"}}}
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            with self.assertRaises(ModelQuotaStop):
                run_case(Fake(), Fixture(), case, resources, root, 2, Budget(10, 10, 100, 3))
            turn = json.loads((root / "cases" / "x" / "turn-00.json").read_text(encoding="utf-8"))
            stop = json.loads((root / "stop.json").read_text(encoding="utf-8"))
        self.assertEqual(9, turn["usage"]["tokens"])
        self.assertIn("model_failure_or_quota_requires_review", turn["errors"])
        self.assertEqual(["insufficient_quota", "MODEL_UNAVAILABLE"], stop["codes"])
        self.assertEqual(["insufficient_quota", "MODEL_UNAVAILABLE"], failure_codes_for([
            {"detail":{"reasonCode":"MODEL_UNAVAILABLE", "attempts":[
                {"externalErrorCode":"insufficient_quota"}, {"externalErrorCode":"insufficient_quota"}]}}]))

    def test_hitl_requires_explicit_expectation_and_committed_suspend(self):
        terminal = {"status":"waiting_hitl", "run_id":"run:original"}
        pending = {"execution_id":"run:original", "pending_ref":"p", "checkpoint_revision":12, "question":"【资料】项目A，请补充"}
        facts = [{"event_type":"run.suspended", "payload":{}}]
        expect = {"status":"completed", "allow_hitl":True, "contains":["【资料】", "项目A"]}
        self.assertFalse(evaluate(expect, terminal, facts, pending)["errors"])
        self.assertTrue(evaluate(expect, terminal, [], pending)["errors"])
        self.assertTrue(evaluate(expect, terminal, facts, {**pending,"execution_id":"wrong"})["errors"])
        self.assertTrue(evaluate({"status":"completed"}, terminal, facts, pending)["errors"])

    def test_hitl_resume_targets_original_run_and_ignores_old_outbox_terminal(self):
        class Fake:
            def open(self,*a,**kw): raise AssertionError("恢复探针不能重新提交")
            def request(self,method,path,body=None,headers=None):
                if method != "GET" or body is not None:
                    raise AssertionError("恢复只能读取 outbox")
                self.path = path
                return {"events":[
                    {"event":"execution.completed", "event_id":"output:old:7",
                     "data":{"input_id":"old", "run_id":"run:old","status":"waiting_hitl"}},
                    {"event":"execution.accepted", "event_id":"output:reply:1",
                     "data":{"input_id":"reply", "run_id":"run:old"}},
                    {"event":"execution.completed", "event_id":"output:reply:2",
                     "data":{"input_id":"reply", "run_id":"run:old","status":"completed"}}]}
        api = Fake()
        with tempfile.TemporaryDirectory() as d:
            result=chat(api,{"session_id":"s","client_message_id":"reply","hitl_response":{"execution_id":"run:old"}},Path(d)/"sse",2,resume=True,output_cursor="output:old:7")
        self.assertEqual("completed",result["terminal"]["status"])
        self.assertNotIn("cursor=",api.path)

    def test_sse_waits_through_delegation_running_receipt_for_parent_final_answer(self):
        class Fake:
            writes = 0

            def request(self, method, path, body=None):
                if method == "POST" and path == "/chat":
                    self.writes += 1
                    return {"execution_id": "run:m"}
                raise AssertionError("完整 SSE 不应回退轮询或重发")

            def open(self, method, path, **kwargs):
                self_path = path
                if method != "GET" or not self_path.startswith("/chat/events?"):
                    raise AssertionError(path)
                frames = [
                    ("execution.accepted", {"input_id": "m", "run_id": "run:m"}),
                    ("execution.completed", {"run_id": "run:m", "status": "running"}),
                    ("execution.completed", {"run_id": "run:m", "status": "queued"}),
                    ("message.delta", {"run_id": "run:m", "content": "子任务已返回，父任务完成"}),
                    ("execution.completed", {"run_id": "run:m", "status": "completed"}),
                ]
                return io.BytesIO("".join("event: " + name + "\ndata: " + json.dumps(data) + "\n\n"
                                          for name, data in frames).encode())

        api = Fake()
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "sse"
            result = chat(api, {"session_id": "s", "client_message_id": "m"}, path, 2)
            observed = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        self.assertEqual("completed", result["terminal"]["status"])
        self.assertIsNotNone(result["first_output_seconds"])
        self.assertIsNone(result["transport_error"])
        self.assertEqual(1, api.writes)
        self.assertEqual(["running", "queued", "completed"],
                         [event["data"]["status"] for event in observed if event["event"] == "execution.completed"])


if __name__ == "__main__": unittest.main()
