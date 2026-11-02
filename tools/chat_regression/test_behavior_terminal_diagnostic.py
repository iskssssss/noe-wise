import json
from pathlib import Path
import tempfile
import unittest

from behavior_contract import digest
from behavior_terminal_diagnostic import terminal_facts, export_terminal_diagnostic
from runner import save


class TerminalDiagnosticTests(unittest.TestCase):
    def event(self, kind, payload, seq=1):
        return {"event_id": "e" + str(seq), "event_type": kind,
                "detail": {"sessionSequence": seq}, "payload": payload}

    def test_transport_success_is_not_business_success(self):
        facts = terminal_facts([], {"tools": [
            {"status": "SUCCEEDED", "result": {"structuredContent": {"success": False, "error": "OUTPUT_CONTRACT_CHANGED"}}},
            {"status": "SUCCEEDED", "result": {}},
            {"status": "SUCCEEDED", "result": {"structuredContent": {"success": True}}}]})
        self.assertEqual(["failed", "unknown", "passed"], [t["business_status"] for t in facts["tool_outcomes"]])

    def test_terminal_reasons_remain_separate(self):
        events = [self.event("run.terminated", {"outcome": {"reason": "INPUT_TOKEN_BUDGET_EXCEEDED"}}),
                  self.event("run.completed", {}, 2)]
        facts = terminal_facts(events, {})
        self.assertEqual("INPUT_TOKEN_BUDGET_EXCEEDED", facts["terminal_events"][0]["reason"])
        self.assertEqual(2, len(facts["terminal_events"]))
        self.assertFalse(facts["final_answer_available"])

    def test_private_content_and_unknown_text_not_exported(self):
        secret = "PRIVATE_REASONING_OR_TOKEN"
        events = [self.event("agent.model-result", {"reasoningContent": secret}),
                  self.event("agent.output-rejection", {"feedback": secret, "reasonCode": "bad code " + secret}),
                  self.event("run.terminated", {"outcome": {"reason": "bad code " + secret, "partialReport": secret}})]
        self.assertNotIn(secret, json.dumps(terminal_facts(events, {"answer": secret})))

    def test_rejections_counted_without_feedback(self):
        events = [self.event("agent.output-rejection", {"reasonCode": "TASK_OUTPUT_RESOLUTION_REQUIRED"}, i) for i in range(6)]
        self.assertEqual({"TASK_OUTPUT_RESOLUTION_REQUIRED": 6}, terminal_facts(events, {})["rejection_counts"])

    def test_child_results_do_not_imply_parent_completion(self):
        events = [self.event("agent.delegation", {"command": '{"operation":"wait"}', "result": {"results": [{}, {}]}})]
        facts = terminal_facts(events, {})
        self.assertEqual(2, facts["delegations"][0]["returned_results"])
        self.assertFalse(facts["final_answer_available"])
        self.assertEqual([], facts["terminal_events"])

    def test_malformed_payload_stays_absent(self):
        events = [self.event("agent.delegation", {"command": "invalid", "result": {}}), self.event("run.terminated", None)]
        facts = terminal_facts(events, {})
        self.assertEqual([], facts["delegations"])
        self.assertEqual([], facts["terminal_events"])

    def test_export_binds_frozen_source_and_rejects_plan_tamper(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder) / "source"
            plan = {"batch": "test-d06"}
            plan["fingerprint"] = digest(plan)
            binding = {"plan_fingerprint": plan["fingerprint"]}
            save(root / "plan.json", plan)
            save(root / "batch.json", {"lifecycle": "finalized", "binding": binding})
            save(root / "evaluation.json", {"lifecycle": "finalized", "binding": binding,
                 "cases": [{"case_id": "D06", "repetition": 1, "behavior_status": "failed"}]})
            save(root / "cases/D06/1/evidence.json", {"case_id": "D06", "repetition": 1})
            save(root / "cases/D06/1/trace-private.json", {"pages": []})
            before = (root / "evaluation.json").read_bytes()
            result = export_terminal_diagnostic(root, "D06", Path(folder) / "out")
            self.assertEqual("failed", result["source_behavior_status"])
            self.assertEqual(5, len(result["source_files"]))
            self.assertEqual(before, (root / "evaluation.json").read_bytes())
            plan["batch"] = "tampered"
            save(root / "plan.json", plan)
            with self.assertRaisesRegex(ValueError, "diagnostic_plan_changed"):
                export_terminal_diagnostic(root, "D06", Path(folder) / "bad")


if __name__ == "__main__":
    unittest.main()
