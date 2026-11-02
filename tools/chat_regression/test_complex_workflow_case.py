"""C06 离线判据与资源接线自测，不代表真实 Workflow/模型验收通过。"""
import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from complex_workflow_case import MARKER, prepare, validate_case, hitl_response
from provision import _JOURNAL_CONTEXT


class ComplexWorkflowCaseTest(unittest.TestCase):
    def test_workflow_confirmation_uses_typed_response_and_original_workflow_identity(self):
        pending = {"execution_id": "parent", "run_revision": 8, "checkpoint_revision": 8,
                   "workflow_execution_id": "workflow:one", "suspend_ref": "wait:one",
                   "expected_revision": 4, "resume_action_key": "wait:one"}
        response = hitl_response(pending, "确认继续")
        self.assertNotIn("pending_ref", response)
        self.assertEqual("wait:one", response["action"])
        self.assertEqual({"action": "confirm", "response": "确认继续"}, __import__("json").loads(response["response"]["canonical_json"]))
        self.assertEqual(4, response["expected_revision"])
        pending.pop("expected_revision")
        with self.assertRaises(ValueError): hitl_response(pending, "确认")
    def setUp(self):
        self.event = {"event_id": "workflow:done", "event_type": "agent.workflow-result", "payload": {
            "status": "COMPLETED", "executionRef": {"executionId": "workflow:one"}, "output": MARKER}}
        self.evidence = {"turns": [
            {"run_id": "parent", "terminal": {"status": "waiting_hitl"}, "pending_hitl": {
                "execution_id": "parent", "pending_ref": "pending:one", "checkpoint_revision": "7"}, "rich_events": []},
            {"run_id": "parent", "terminal": {"status": "completed"}, "rich_events": [self.event]}],
            "replay": {"receipt": {"execution_id": "parent"}, "same_request": True, "snapshot_verified": True,
                       "before_runs": ["parent"], "after_runs": ["parent"],
                       "before_workflow": ["workflow:done"], "after_workflow": ["workflow:done"]}}

    def test_exact_wait_resume_and_same_request_replay_pass(self):
        self.assertEqual("PASS", validate_case(self.evidence)["status"])

    def test_answer_claim_without_workflow_fact_cannot_pass(self):
        self.evidence["turns"][1]["rich_events"] = []
        self.evidence["turns"][1]["answer"] = MARKER
        self.assertEqual("UNKNOWN", validate_case(self.evidence)["status"])

    def test_before_confirmation_completion_or_different_parent_fails(self):
        for changed in ("early", "new_run"):
            value = copy.deepcopy(self.evidence)
            if changed == "early": value["turns"][0]["rich_events"] = [self.event]
            else: value["turns"][1]["run_id"] = "replacement"
            self.assertEqual("FAILED", validate_case(value)["status"])

    def test_duplicate_workflow_or_new_run_after_replay_fails(self):
        other = copy.deepcopy(self.event); other["event_id"] = "workflow:duplicate"
        self.evidence["turns"][1]["rich_events"].append(other)
        self.assertEqual("FAILED", validate_case(self.evidence)["status"])
        self.evidence["turns"][1]["rich_events"] = [self.event]
        self.evidence["replay"]["after_runs"].append("new-run")
        self.assertEqual("FAILED", validate_case(self.evidence)["status"])

    def test_missing_duplicate_proof_is_unknown(self):
        self.evidence.pop("replay")
        self.assertEqual("UNKNOWN", validate_case(self.evidence)["status"])

    def test_prepare_reuses_formal_provisioner_and_restores_journal(self):
        agent = {"id": "123", "mounts": {}}
        resources = {"batch": "isolated", "agents": {"c06-workflow": agent}}
        prior = _JOURNAL_CONTEXT.get()
        def provision(api, case, path, batch):
            self.assertIs(agent, case["agents"]["d10-workflow"])
            self.assertEqual((case, path), _JOURNAL_CONTEXT.get())
            self.assertEqual("isolated-c06", batch)
            case["workflow"] = {"instance_id": 17, "definition_version_id": 23}
        with tempfile.TemporaryDirectory() as folder, patch("complex_workflow_case._provision_workflow", provision):
            result = prepare(object(), resources, Path(folder))
        self.assertTrue(result["agent_publish_required"])
        self.assertEqual(17, result["workflow"]["instance_id"])
        self.assertIs(prior, _JOURNAL_CONTEXT.get())


if __name__ == "__main__":
    unittest.main()
