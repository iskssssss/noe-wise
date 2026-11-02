"""Tests for strict, redaction-safe product process evidence assessment."""
import json
import unittest

from behavior_product_process import (PASSED, FAILED, UNKNOWN, CLIENT_OPERATIONS,
                                      validate_live_case_evidence, validate_process_evidence)


def ref(name):
    return {"reference": "artifact:" + name}


def operation(name, status=PASSED):
    return {"status": status, "reference": "browser:" + name, "observed": {"capture": name}}


def client(name):
    return {"mode": "live", "session_id": "session-1", "reference": "browser:" + name,
            "operations": {operation_name: operation(name + ":" + operation_name)
                           for operation_name in CLIENT_OPERATIONS}}


def evidence():
    processes = [
        {**ref("commentary-1"), "process_id": "commentary-1", "run_id": "run-1", "root_run_id": "run-1",
         "root_input_id": "input-1", "type": "commentary", "sequence": 1, "ordinal": 1},
        {**ref("tool-1"), "process_id": "tool-1", "run_id": "run-1", "root_run_id": "run-1",
         "root_input_id": "input-1", "type": "tool", "sequence": 2, "ordinal": 2},
        {**ref("commentary-2"), "process_id": "commentary-2", "run_id": "run-1", "root_run_id": "run-1",
         "root_input_id": "input-1", "type": "commentary", "sequence": 2, "ordinal": 3},
        {**ref("tool-2"), "process_id": "tool-2", "run_id": "run-1", "root_run_id": "run-1",
         "root_input_id": "input-1", "type": "tool", "sequence": 3, "ordinal": 4},
    ]
    delivery = [
        {**ref("event-1"), "event_id": "event-1", "process_id": "commentary-1", "arrival": 1},
        {**ref("event-2"), "event_id": "event-2", "process_id": "tool-1", "arrival": 2},
        {**ref("event-2-replay"), "event_id": "event-2", "process_id": "tool-1", "arrival": 2},
        {**ref("event-4"), "event_id": "event-4", "process_id": "tool-2", "arrival": 3, "kind": "out_of_order"},
        {**ref("event-3"), "event_id": "event-3", "process_id": "commentary-2", "arrival": 4},
    ]
    history = {
        name: {**ref("history-" + name), "mode": "live", "session_id": "session-1",
               "process_ids": [item["process_id"] for item in processes], "final_message_ids": ["final-1"]}
        for name in ("institution", "chat")
    }
    return {
        "session": {**ref("session-1"), "session_id": "session-1"},
        "artifacts": {"processes": processes, "delivery": delivery, "history": history,
                      "waiting": {**ref("waiting"), "status": "waiting_input", "visible": True},
                      "collaboration": {**ref("collaboration"), "parent_session_id": "session-1",
                                        "children": [{"session_id": "child-1", "status": "running",
                                                      "visible_in_parent": "summary"}]}},
        "browser": {"institution": client("institution"), "chat": client("chat")},
        "privacy": {**ref("privacy"), "marker_digests": ["a" * 64], "marker_count": 1,
                    "public_snapshots": [
                        {"source": "institution:dom", "kind": "dom", "observed": True, "content_sha256": "b" * 64,
                         "character_count": 12, "contains_forbidden": False},
                        {"source": "institution:response", "kind": "response", "observed": True, "content_sha256": "c" * 64,
                         "character_count": 12, "contains_forbidden": False},
                        {"source": "chat:dom", "kind": "dom", "observed": True, "content_sha256": "d" * 64,
                         "character_count": 12, "contains_forbidden": False},
                        {"source": "chat:response", "kind": "response", "observed": True, "content_sha256": "e" * 64,
                         "character_count": 12, "contains_forbidden": False},
                    ], "exposure_detected": False},
    }


def live_case(case_id):
    bundle = evidence()
    if case_id == "D11":
        bundle["artifacts"]["history"]["institution"]["final_message_ids"] = []
        bundle["artifacts"]["history"]["chat"]["final_message_ids"] = []
    for client_name in ("institution", "chat"):
        bundle["browser"][client_name]["operations"]["history_read"] = {
            "status": PASSED, "reference": "browser:" + client_name + ":history", "observed": {"refreshed": True}}
    requests = {
        "D09": [
            {"kind": "message", "session_id": "session-1", "run_id": "run-1", "input_id": "input-1", "terminal_event": "execution.completed"},
            {"kind": "message", "session_id": "session-1", "run_id": "run-2", "input_id": "input-2", "terminal_event": "execution.completed"},
        ],
        "D10": [
            {"kind": "message", "session_id": "session-1", "run_id": "run-1", "input_id": "input-1", "terminal_event": "hitl.required"},
            {"kind": "hitl", "session_id": "session-1", "run_id": "run-1", "input_id": "input-2", "terminal_event": "execution.completed"},
        ],
        "D11": [
            {"kind": "message", "session_id": "session-1", "run_id": "run-1", "input_id": "input-1", "terminal_event": "hitl.required"},
        ],
        "D12": [
            {"kind": "message", "session_id": "session-1", "run_id": "run-1", "input_id": "input-1", "terminal_event": "execution.completed"},
            {"kind": "cancel", "session_id": "session-1", "run_id": "run-1", "terminal_event": "execution.completed", "terminal_status": "cancelled"},
        ],
    }
    bundle["network_requests"] = requests[case_id]
    operations = bundle["browser"]["institution"]["operations"]
    if case_id == "D09":
        operations["artifact_read"] = operation("artifact-read")
        operations["artifact_read"]["observed"] = {"artifact_count": 2}
        operations["artifact_download"] = operation("artifact-download")
        operations["artifact_download"]["observed"] = {"downloaded": True}
    if case_id == "D10":
        operations["waiting_input_visible"] = operation("waiting")
        operations["waiting_input_visible"]["observed"] = {"visible": True}
    if case_id == "D12":
        operations["cancel"] = operation("cancel")
        operations["cancel"]["observed"] = {"terminal_event": "execution.completed", "status": "cancelled"}
    return bundle


class ProductProcessEvidenceTests(unittest.TestCase):
    def test_complete_live_two_client_bundle_passes_all_rows(self):
        result = validate_process_evidence(evidence())
        self.assertEqual(PASSED, result["status"])
        self.assertTrue(result["assertions"])
        self.assertTrue(all(row["status"] == PASSED for row in result["assertions"]))
        self.assertTrue(all(row["references"] for row in result["assertions"]))

    def test_missing_live_client_is_unknown_not_a_mock_substitute(self):
        bundle = evidence()
        bundle["browser"]["chat"]["mode"] = "fixture"
        result = validate_process_evidence(bundle)
        self.assertEqual(UNKNOWN, result["status"])
        self.assertIn("client_not_live", {row["reason"] for row in result["assertions"]})

    def test_status_and_reference_without_an_observed_browser_fact_are_unknown(self):
        bundle = evidence()
        del bundle["browser"]["institution"]["operations"]["manual_expand"]["observed"]
        result = validate_process_evidence(bundle)
        row = next(row for row in result["assertions"] if row["id"] == "D10.lifecycle")
        self.assertEqual(UNKNOWN, row["status"])

    def test_case_scoped_deterministic_evidence_only_requires_its_own_operations(self):
        bundle = evidence()
        bundle.update(case_id="D10", deterministic={"runner": "playwright", "operations": {
            name: operation("deterministic:" + name) for name in ("final_before_completion", "auto_collapse_once", "manual_expand")}})
        bundle.pop("browser")
        result = validate_process_evidence(bundle)
        self.assertEqual(PASSED, result["status"])
        self.assertEqual(["D10.deterministic"], [row["id"] for row in result["assertions"]])

    def test_d12_live_case_does_not_require_unrelated_transition_scenarios(self):
        bundle = evidence()
        bundle["case_id"] = "D12"
        for client_name in ("institution", "chat"):
            operations = bundle["browser"][client_name]["operations"]
            bundle["browser"][client_name]["operations"] = {"history_read": operations["history_read"]}
        result = validate_process_evidence(bundle)
        self.assertEqual(PASSED, result["status"])
        self.assertNotIn("D10.lifecycle", {row["id"] for row in result["assertions"]})

    def test_conflicting_duplicate_event_fails_closed(self):
        bundle = evidence()
        bundle["artifacts"]["delivery"][2]["process_id"] = "tool-2"
        result = validate_process_evidence(bundle)
        self.assertEqual(FAILED, result["status"])
        self.assertIn("conflicting_delivery_identity", {row["reason"] for row in result["assertions"]})

    def test_both_live_clients_must_observe_duplicate_and_out_of_order_replay(self):
        bundle = evidence()
        bundle["browser"]["chat"]["operations"]["out_of_order_replay"] = operation("reorder", FAILED)
        result = validate_process_evidence(bundle)
        row = next(row for row in result["assertions"] if row["id"] == "D09.browser")
        self.assertEqual((FAILED, "operation_failed:out_of_order_replay"), (row["status"], row["reason"]))

    def test_reorder_label_without_actual_reordered_arrival_fails(self):
        bundle = evidence()
        bundle["artifacts"]["delivery"][3]["arrival"] = 4
        bundle["artifacts"]["delivery"][4]["arrival"] = 3
        result = validate_process_evidence(bundle)
        row = next(row for row in result["assertions"] if row["id"] == "D09.delivery")
        self.assertEqual((FAILED, "out_of_order_delivery_not_observed"), (row["status"], row["reason"]))

    def test_final_delay_and_manual_expand_are_explicit_browser_operations(self):
        bundle = evidence()
        del bundle["browser"]["institution"]["operations"]["manual_expand"]
        bundle["browser"]["chat"]["operations"]["final_before_completion"] = operation("late-final", FAILED)
        result = validate_process_evidence(bundle)
        lifecycle = next(row for row in result["assertions"] if row["id"] == "D10.lifecycle")
        self.assertEqual(FAILED, lifecycle["status"])
        self.assertEqual("operation_failed:final_before_completion", lifecycle["reason"])

    def test_waiting_and_child_sessions_have_to_be_visible_and_summary_only(self):
        bundle = evidence()
        bundle["artifacts"]["waiting"]["visible"] = False
        bundle["artifacts"]["collaboration"]["children"][0]["visible_in_parent"] = "full_transcript"
        result = validate_process_evidence(bundle)
        rows = {row["id"]: row for row in result["assertions"]}
        self.assertEqual(FAILED, rows["D11.waiting"]["status"])
        self.assertEqual(FAILED, rows["D11.parent_child"]["status"])

    def test_history_requires_the_same_live_session_and_identity_lists(self):
        bundle = evidence()
        bundle["artifacts"]["history"]["chat"]["final_message_ids"] = ["another-final"]
        result = validate_process_evidence(bundle)
        row = next(row for row in result["assertions"] if row["id"] == "D12.history")
        self.assertEqual((FAILED, "paired_history_diverged"), (row["status"], row["reason"]))

    def test_privacy_failure_does_not_echo_sensitive_value_in_report(self):
        bundle = evidence()
        bundle["privacy"]["forbidden_values"] = ["SF-PRIVATE-canary"]
        bundle["privacy"]["public_snapshots"][0]["contains_forbidden"] = True
        bundle["privacy"]["exposure_detected"] = True
        bundle["session"]["reference"] = "trace:SF-PRIVATE-canary"
        result = validate_process_evidence(bundle)
        row = next(row for row in result["assertions"] if row["id"] == "D12.privacy")
        self.assertEqual((FAILED, "forbidden_value_exposed"), (row["status"], row["reason"]))
        self.assertNotIn("SF-PRIVATE-canary", json.dumps(result, ensure_ascii=False))

    def test_empty_or_malformed_evidence_never_passes(self):
        for bundle in (None, {}, {"session": {"session_id": "session-1"}}):
            with self.subTest(bundle=bundle):
                self.assertEqual(UNKNOWN, validate_process_evidence(bundle)["status"])

    def test_live_cases_require_real_two_client_history_and_case_requests(self):
        for case_id in ("D09", "D10", "D11", "D12"):
            with self.subTest(case_id=case_id):
                self.assertEqual(PASSED, validate_live_case_evidence(live_case(case_id), case_id)["status"])

    def test_live_d10_requires_hitl_resume_on_the_original_run(self):
        bundle = live_case("D10")
        bundle["network_requests"][1]["run_id"] = "run-other"
        result = validate_live_case_evidence(bundle, "D10")
        row = next(item for item in result["assertions"] if item["id"] == "D10.requests")
        self.assertEqual((FAILED, "hitl_resume_run_mismatch"), (row["status"], row["reason"]))

    def test_live_d09_needs_counted_ui_artifact_and_download_observation(self):
        bundle = live_case("D09")
        del bundle["browser"]["institution"]["operations"]["artifact_download"]
        result = validate_live_case_evidence(bundle, "D09")
        row = next(item for item in result["assertions"] if item["id"] == "D09.artifact_download")
        self.assertEqual(UNKNOWN, row["status"])

    def test_live_d12_requires_refresh_and_cancel_observations(self):
        bundle = live_case("D12")
        bundle["browser"]["chat"]["operations"]["history_read"]["observed"]["refreshed"] = False
        result = validate_live_case_evidence(bundle, "D12")
        row = next(item for item in result["assertions"] if item["id"] == "D12.history")
        self.assertEqual((UNKNOWN, "history_refresh_not_observed"), (row["status"], row["reason"]))

    def test_d12_accepts_scoped_browser_history_after_refresh(self):
        bundle = live_case("D12")
        message, cancel = bundle["network_requests"]
        del message["terminal_event"]
        message["reconnected"] = True
        cancel.update(terminal_event="history.run_terminal", terminal_source="browser_history_response")
        self.assertEqual(PASSED, validate_live_case_evidence(bundle, "D12")["status"])
        for key, value, expected in (("terminal_source", "run_status_readback", UNKNOWN),
                                     ("terminal_event", "hitl.required", FAILED),
                                     ("terminal_status", "completed", FAILED),
                                     ("run_id", "other-run", FAILED)):
            changed = live_case("D12")
            changed["network_requests"][1].update(cancel)
            changed["network_requests"][1][key] = value
            result = validate_live_case_evidence(changed, "D12")
            row = next(item for item in result["assertions"] if item["id"] == "D12.requests")
            self.assertEqual(expected, row["status"])

    def test_d12_privacy_requires_nonempty_private_marker_digests_and_both_public_sources(self):
        bundle = live_case("D12")
        bundle["privacy"]["marker_digests"] = []
        bundle["privacy"]["marker_count"] = 0
        result = validate_live_case_evidence(bundle, "D12")
        row = next(item for item in result["assertions"] if item["id"] == "D12.privacy")
        self.assertEqual((UNKNOWN, "privacy_contract_incomplete"), (row["status"], row["reason"]))

    def test_malformed_private_marker_digest_remains_unknown(self):
        bundle = live_case("D12")
        bundle["privacy"]["marker_digests"] = [{}]
        result = validate_live_case_evidence(bundle, "D12")
        row = next(item for item in result["assertions"] if item["id"] == "D12.privacy")
        self.assertEqual(UNKNOWN, row["status"])


if __name__ == "__main__":
    unittest.main()
