import importlib.util
import json
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("conversation_eval.py")
SPEC = importlib.util.spec_from_file_location("conversation_eval", MODULE_PATH)
conversation_eval = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = conversation_eval
SPEC.loader.exec_module(conversation_eval)


CASES = json.loads(Path(__file__).with_name("eval_cases.json").read_text(encoding="utf-8"))["cases"]


def process(process_id, kind="tool", status="succeeded", name=None):
    item = {"process_id": process_id, "type": kind, "status": status}
    if name:
        item["detail"] = {"tool_handle": name}
    return item


def statuses(trace):
    return {case["id"]: case["status"] for case in conversation_eval.evaluate_trace(trace, CASES)["cases"]}


class ConversationEvalTest(unittest.TestCase):
    def test_page_generation_rejects_extra_query(self):
        report = statuses({"evaluation_context": {"scenario": "page_generation"}, "processes": [process("p1", name="search")]})
        self.assertEqual("fail", report["page-generation-no-extra-queries"])

    def test_refresh_accepts_minimal_set(self):
        report = statuses({"evaluation_context": {"scenario": "refresh"}, "processes": [process("p1", name="get_latest_reset")]})
        self.assertEqual("pass", report["refresh-minimal-tool-set"])

    def test_pagination_is_allowed_only_when_history_is_incomplete(self):
        complete = statuses({"evaluation_context": {"scenario": "history", "history": {"complete": True}}, "processes": [process("p1", name="paginate_history")]})
        incomplete = statuses({"evaluation_context": {"scenario": "history", "history": {"complete": False}}, "processes": [process("p1", name="paginate_history")]})
        self.assertEqual("fail", complete["paginate-only-for-incomplete-history"])
        self.assertEqual("unknown", incomplete["paginate-only-for-incomplete-history"])

    def test_failure_needs_structured_fact_review(self):
        unknown = statuses({"evaluation_context": {"scenario": "failure"}, "processes": [process("failed", status="failed", name="list_artifacts")]})
        passed = statuses({"evaluation_context": {"scenario": "failure", "fact_reviews": [{"failed_process_id": "failed", "outcome": "reported"}]}, "processes": [process("failed", status="failed", name="list_artifacts")]})
        self.assertEqual("unknown", unknown["failure-fact-review"])
        self.assertEqual("pass", passed["failure-fact-review"])

    def test_json_message_does_not_create_a_tool_call(self):
        report = conversation_eval.evaluate_trace({"evaluation_context": {"scenario": "json_wrapper"}, "messages": [{"role": "assistant", "content": "{\"render\": \"card\"}"}], "processes": []}, CASES)
        self.assertEqual(0, report["metrics"]["tool_calls"]["value"])
        self.assertEqual("pass", next(case for case in report["cases"] if case["id"] == "json-wrapper-is-not-tool-call")["status"])

    def test_plan_rejects_write_before_approval(self):
        report = statuses({"evaluation_context": {"scenario": "plan", "plan": {"approved": False}, "tool_effects": {"publish_page": "write"}}, "processes": [process("write", name="publish_page")]})
        self.assertEqual("fail", report["plan-has-no-write-before-approval"])

    def test_metrics_do_not_invent_missing_usage_or_latency(self):
        metrics = conversation_eval.evaluate_trace({"processes": [process("m", kind="reasoning")]}, CASES)["metrics"]
        self.assertEqual("unavailable", metrics["first_body_latency"]["status"])
        self.assertEqual("unavailable", metrics["tokens"]["total_tokens"]["status"])


if __name__ == "__main__":
    unittest.main()
