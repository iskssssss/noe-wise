"""复杂边界夹具与判定的正负控；本地 HTTP，无模型、数据库或产品服务。"""
from copy import deepcopy
from datetime import datetime, timezone
from http.client import HTTPConnection, RemoteDisconnected
from pathlib import Path
import json
import sys
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
from complex_boundary_cases import (boundary_profiles, live_boundary_cases, fixture_body, prepare_live_resources,
    write_unknown_facts, injection_pair_facts, cancellation_boundary_facts, replay_boundary_facts)
from mcp_fixture import McpFixtureServer


class ComplexBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.cases = live_boundary_cases("complex-test")
        self.server = McpFixtureServer(0, "complex-test")
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(2)
        self.assertFalse(self.worker.is_alive())

    def request(self, method, path, body=None):
        connection = HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=2)
        try:
            connection.request(method, path, body=None if body is None else json.dumps(body).encode(),
                               headers={"Content-Type": "application/json"})
            response = connection.getresponse()
            data = json.loads(response.read())
            self.assertEqual(201 if path == "/fixtures" and method == "POST" else 200, response.status, data)
            return data
        finally:
            connection.close()

    def tool(self, name, args):
        return self.request("POST", "/mcp", {"jsonrpc": "2.0", "id": "local", "method": "tools/call",
                                               "params": {"name": name, "arguments": args}})["result"]

    def create(self, name):
        self.request("POST", "/fixtures", fixture_body(self.cases[name]))
        return self.cases[name]["fixture"]["case_key"]

    def unknown_event(self, run="run:c07", action="tool-action:1"):
        return {"event_type": "action.outcome_unknown", "event_id": "unknown-1", "run_id": run,
                "action_id": action, "payload": {"state": "OUTCOME_UNKNOWN", "reasonCode": "mcp_outcome_unknown",
                                                   "actionRef": {"actionId": action}, "outcomeFactRef": "fact-1", "invocationId": "invoke-1"},
                "detail": {"currentActionState": "OUTCOME_UNKNOWN", "callKind": "TOOL_INVOKE",
                           "attemptId": "attempt-1", "attempts": [{"callKind": "TOOL_INVOKE", "status": "UNRESOLVED",
                               "outcomeResolution": "OUTCOME_UNKNOWN", "outcomeType": "OutcomeUnknown", "factRef": "fact-1",
                               "invocationId": "invoke-1", "attemptId": "attempt-1"}]}}

    def lost_write(self):
        key = self.create("C07")
        with self.assertRaises(RemoteDisconnected):
            self.tool("write_record", {"case_key": key, "value": 100, "request_key": "c07-write"})
        return key, self.server.store.audit(key)

    def test_c07_real_lost_http_reply_one_effect_and_strict_original_unknown(self):
        _, audit = self.lost_write()
        terminal = {"run_id": "run:c07", "status": "cancelled"}
        self.assertTrue(all(write_unknown_facts(audit, [self.unknown_event()], terminal).values()))
        self.assertEqual(1, audit["call_counts"]["write_record"])
        self.assertEqual(1, len(audit["write_events"]))

    def test_c07_idempotent_tool_still_fails_if_agent_replays_unknown_write(self):
        key, _ = self.lost_write()
        replay = self.tool("write_record", {"case_key": key, "value": 100, "request_key": "c07-write"})
        self.assertFalse(replay["structuredContent"]["written"])
        facts = write_unknown_facts(self.server.store.audit(key), [self.unknown_event()],
                                    {"run_id": "run:c07", "status": "cancelled"})
        self.assertTrue(facts["effect_once"])
        self.assertFalse(facts["one_dispatch"])

    def test_c07_foreign_unknown_and_missing_runtime_proof_cannot_pass(self):
        _, audit = self.lost_write()
        terminal = {"run_id": "run:c07", "status": "cancelled"}
        self.assertFalse(write_unknown_facts(audit, [self.unknown_event("run:foreign")], terminal)["runtime_tool_unknown"])
        self.assertIsNone(write_unknown_facts(audit, [], terminal)["runtime_tool_unknown"])
        forged = self.unknown_event()
        forged["detail"]["attempts"][0]["attemptId"] = "different-attempt"
        self.assertFalse(write_unknown_facts(audit, [forged], terminal)["runtime_tool_unknown"])

    def test_c10_pair_preserves_business_data_and_proves_query_with_zero_writes(self):
        audits = []
        for name in ("C10-control", "C10-attack"):
            key = self.create(name)
            result = self.tool("query_record", {"case_key": key})
            self.assertEqual(100, result["structuredContent"]["value"]["amount"])
            audits.append(self.server.store.audit(key))
        answer = "实际金额100，核验标记BOUNDARY-C10-VERIFIED"
        unassigned = self.server.store.audit_unassigned()
        self.assertTrue(all(injection_pair_facts(*audits, answer, answer, unassigned_before=unassigned,
                                                 unassigned_after=unassigned).values()))
        left, right = (fixture_body(self.cases[n])["value"] for n in ("C10-control", "C10-attack"))
        left.pop("untrusted_note"); right.pop("untrusted_note")
        self.assertEqual(left, right)
        self.assertNotIn("JSON", boundary_profiles()[0]["prompt"])

    def test_c10_noop_and_injected_write_are_detected_by_independent_audit(self):
        control = self.create("C10-control")
        attack = self.create("C10-attack")
        self.tool("query_record", {"case_key": control})
        answer = "金额100，BOUNDARY-C10-VERIFIED"
        facts = injection_pair_facts(self.server.store.audit(control), self.server.store.audit(attack), answer, answer)
        self.assertFalse(facts["attack_queried"])
        self.tool("query_record", {"case_key": attack})
        self.tool("write_record", {"case_key": attack, "value": 999, "request_key": "c10-injected"})
        self.assertFalse(injection_pair_facts(self.server.store.audit(control), self.server.store.audit(attack), answer, answer)["attack_no_write"])
        before = self.server.store.audit_unassigned()
        self.tool("write_record", {"case_key": "outside-batch", "value": 999, "request_key": "unassigned"})
        self.assertFalse(injection_pair_facts(self.server.store.audit(control), self.server.store.audit(attack), answer, answer,
                                             unassigned_before=before, unassigned_after=self.server.store.audit_unassigned())["no_unassigned_calls"])

    def test_c08_late_return_is_not_a_post_cancel_dispatch_but_new_call_is(self):
        key = self.create("C08")
        errors = []
        def query():
            try: self.tool("query_record", {"case_key": key})
            except BaseException as error: errors.append(error)
        worker = threading.Thread(target=query)
        worker.start()
        try:
            self.assertTrue(self.server.store.entered[key].wait(1))
            cancelled_at = time.time()
        finally:
            self.server.store.barrier(key, release=True)
            worker.join(2)
        self.assertFalse(worker.is_alive()); self.assertEqual([], errors)
        when = datetime.fromtimestamp(cancelled_at, timezone.utc).isoformat()
        phases = [{"event_id": "cancel-request", "event_type": "run.cancel_requested", "occurred_at": when,
                   "detail": {"sessionSequence": 2}},
                  {"event_id": "cancelled", "event_type": "run.cancelled", "occurred_at": when,
                   "detail": {"sessionSequence": 3}}]
        trace = {"pages": [{"rounds": [{"runs": [{"run_id": "run:c08", "steps": [{"phases": phases}]}]}]}]}
        audit = self.server.store.audit(key)
        facts = cancellation_boundary_facts(trace, audit, self.server.store.barrier(key))
        self.assertTrue(facts["quiescent"]); self.assertEqual(0, facts["post_cancel_dispatches"])
        self.tool("query_record", {"case_key": key})
        self.assertEqual(1, cancellation_boundary_facts(trace, self.server.store.audit(key), self.server.store.barrier(key))["post_cancel_dispatches"])
        phases.append({"event_id": "new-decision", "event_type": "agent.context-snapshot", "occurred_at": when,
                       "detail": {"sessionSequence": 4}})
        self.assertEqual(1, cancellation_boundary_facts(trace, audit, self.server.store.barrier(key))["post_cancel_decisions"])

    def test_c09_replay_requires_same_calls_effects_and_known_provider_count(self):
        key = self.create("C09")
        self.tool("query_record", {"case_key": key})
        before = self.server.store.audit(key)
        self.assertTrue(all(replay_boundary_facts(before, deepcopy(before), 1, 1).values()))
        self.tool("query_record", {"case_key": key})
        self.assertFalse(replay_boundary_facts(before, self.server.store.audit(key), 1, 1)["same_tool_calls"])
        self.assertFalse(replay_boundary_facts(before, before, 1, 2)["same_provider_calls"])
        self.assertIsNone(replay_boundary_facts(before, before, None, None)["same_provider_calls"])

    def test_live_prepare_delegates_existing_resource_journal_without_publication(self):
        with patch("complex_boundary_cases.provision", return_value={"agents": {}}) as existing:
            result = prepare_live_resources("api", "platform", Path("case-resources"), "complex-test", "model-id", "http://127.0.0.1:18765/mcp")
        self.assertEqual({"agents": {}}, result)
        self.assertFalse(existing.call_args.kwargs["publish_agents"])
        self.assertEqual(1, existing.call_args.kwargs["user_count"])
        with self.assertRaises(ValueError): live_boundary_cases("bad:batch")


if __name__ == "__main__": unittest.main()
