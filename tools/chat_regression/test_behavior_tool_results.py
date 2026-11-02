"""MCP 回执与模型请求、准入拒绝和 Runtime 采纳保持独立。"""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parent))
from behavior_capture import evidence
from behavior_eval import evaluate_assertion
from behavior_suite import assertion
from test_behavior_core import trace


class ToolResultEvidenceTests(unittest.TestCase):
    def capture(self, calls):
        return evidence({"id": "fixed"}, 1, trace(),
                        {"case_key": "current", "calls": calls, "write_events": []},
                        {"run_id": "r", "status": "completed"}, [[10, 20]])

    def call(self, status="completed", **updates):
        return {"sequence": 11, "tool": "query_record", "arguments": {"case_key": "current"},
                "status": status, "completed_at": 123, "result": {"isError": status == "error"}, **updates}

    def check(self, kind, snapshot, expected=None):
        return evaluate_assertion(assertion("result", kind, expected or {"min": 1, "max": 1},
                                            tool="query_record", turn=0, requirements=["audit"]), snapshot)

    def test_success_uses_fixture_receipt_and_does_not_require_successful_answer(self):
        snapshot = self.capture([self.call()])
        snapshot["answer"] = {"status": "unavailable"}
        result = self.check("fixture_succeeded", snapshot)
        self.assertEqual("pass", result["status"])
        self.assertEqual([{"event_id": "fixture:current:11", "pointer": "/status",
                           "source": "mcp_fixture_audit"}], result["evidence"])
        self.assertEqual("fail", self.check("fixture_failed", snapshot)["status"])

    def test_expected_tool_failure_is_not_success_even_when_answer_claims_success(self):
        snapshot = self.capture([self.call("error")])
        self.assertEqual("success", snapshot["answer"]["status"])
        self.assertEqual("fail", self.check("fixture_succeeded", snapshot)["status"])
        self.assertEqual("pass", self.check("fixture_failed", snapshot)["status"])
        self.assertEqual("pass", self.check("tool_count", snapshot)["status"])

    def test_forbidden_tool_zero_execution_does_not_prove_request_or_admission_refusal(self):
        snapshot = self.capture([])
        snapshot["answer"] = {"status": "denied", "message": "I attempted the forbidden tool and was refused"}
        self.assertEqual("pass", self.check("tool_count", snapshot, {"max": 0})["status"])
        self.assertEqual("fail", self.check("fixture_succeeded", snapshot)["status"])
        self.assertEqual("fail", self.check("fixture_failed", snapshot)["status"])
        self.assertNotIn("tool_requests", snapshot)
        self.assertNotIn("tool_refusals", snapshot)

    def test_inflight_and_missing_result_status_remain_unknown(self):
        for call in (self.call("in_flight", completed_at=None), self.call(status=None), self.call(completed_at=None)):
            with self.subTest(call=call):
                snapshot = self.capture([call])
                for kind in ("fixture_succeeded", "fixture_failed"):
                    result = self.check(kind, snapshot)
                    self.assertEqual(("unknown", "tool_result_evidence_missing"), (result["status"], result["reason"]))

    def test_old_or_unattributed_evidence_cannot_claim_result_success(self):
        snapshot = self.capture([self.call()])
        del snapshot["calls"][0]["evidence_source"]
        self.assertEqual("unknown", self.check("fixture_succeeded", snapshot)["status"])

    def test_history_in_trace_is_not_fixture_execution(self):
        snapshot = self.capture([])
        snapshot["events"].append({"event_id": "old-success", "event_type": "agent.tool-result", "run_id": "history",
                                   "payload": {"toolHandle": "query_record", "status": "SUCCEEDED"}})
        self.assertEqual("fail", self.check("fixture_succeeded", snapshot)["status"])

    def test_history_audit_turn_cannot_satisfy_current_turn(self):
        snapshot = self.capture([self.call(sequence=5)])
        result = self.check("fixture_succeeded", snapshot)
        self.assertEqual(("unknown", "tool_turn_evidence_missing"), (result["status"], result["reason"]))

    def test_audit_completeness_required_even_when_assertion_omits_requirement(self):
        snapshot = self.capture([self.call()])
        snapshot["completeness"]["audit"] = False
        a = assertion("result", "fixture_succeeded", {"min": 1}, requirements=[])
        self.assertEqual("unknown", evaluate_assertion(a, snapshot)["status"])

    def test_runtime_unknown_still_requires_exact_fact_chain(self):
        snapshot = self.capture([self.call("in_flight", completed_at=None)])
        snapshot["answer"] = {"message": "OUTCOME_UNKNOWN"}
        a = assertion("unknown", "tool_unknown", "tool_outcome_unknown", requirements=[])
        self.assertEqual("fail", evaluate_assertion(a, snapshot)["status"])


if __name__ == "__main__":
    unittest.main()
