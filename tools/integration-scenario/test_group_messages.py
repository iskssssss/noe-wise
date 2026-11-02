"""群来源/触发客户端边界；替代HTTP传输，不连接宿主、不执行模型。"""
import copy
import unittest

from group_messages import run_messages, validate_plan


class GroupMessagesTest(unittest.TestCase):
    def setUp(self):
        self.message = {"idempotency_key": "record-key", "source_event_id": "event-1", "source_revision": 1,
                        "author": {"kind": "end_user", "end_user_id": "9007199254740993101"},
                        "content": [{"type": "text", "text": "普通记录 @助手"}]}
        self.plan = {"group_binding_id": "opaque/group", "messages": [copy.deepcopy(self.message)]}
        self.receipt = {"group_binding_id": "opaque/group", "session_id": "opaque/session", "source_message_id": "opaque/source",
                        "source_sequence": 1, "source_event_id": "event-1", "source_revision": 1}
        self.trigger = {"group_binding_id": "opaque/group", "session_id": "opaque/session", "trigger_id": "opaque/trigger",
                        "trigger_sequence": 1, "source_message_id": "opaque/source", "status": "queued", "input_id": None,
                        "run_id": None, "requester_end_user_id": "9007199254740993101", "agent_id": "9007199254740993102", "rejection_code": None}

    def triggering_plan(self):
        plan = copy.deepcopy(self.plan)
        plan["messages"][0]["trigger"] = {"idempotency_key": "trigger-key", "requester_end_user_id": "9007199254740993101", "agent_id": "9007199254740993102"}
        return plan

    def test_record_only_has_no_trigger_or_run_even_when_text_mentions_agent(self):
        calls = []
        receipt = self.receipt

        class Client:
            def request(self, method, path, body, **kwargs):
                calls.append((method, path, body, kwargs))
                return copy.deepcopy(receipt)

        evidence = run_messages(Client(), self.plan, evidence_kind="client_fixture")
        self.assertEqual("PASS", evidence["status"])
        self.assertEqual(1, len(calls))
        self.assertEqual("/open-platform/groups/messages", calls[0][1])
        self.assertNotIn("trigger", calls[0][2])
        self.assertNotIn("agent_id", calls[0][2])
        self.assertNotIn("run_id", evidence["steps"][0]["response"])
        self.assertEqual("NOT_RUN", evidence["run_completion"])

    def test_trigger_reuses_committed_source_and_current_read_does_not_mutate_original_receipt(self):
        receipt, trigger = self.receipt, self.trigger
        calls = []

        class Client:
            def request(self, method, path, body, **kwargs):
                calls.append((method, path, body, kwargs))
                if path.endswith("/messages"):
                    return copy.deepcopy(receipt)
                if method == "POST":
                    return copy.deepcopy(trigger)
                return {**copy.deepcopy(trigger), "status": "accepted", "input_id": "opaque/input", "run_id": "opaque/run"}

        evidence = run_messages(Client(), self.triggering_plan(), evidence_kind="client_fixture")
        self.assertEqual("PASS", evidence["status"])
        self.assertEqual(["record-key", "trigger-key", None], [call[3]["idempotency_key"] for call in calls])
        self.assertEqual(receipt["source_message_id"], calls[1][2]["source_message_id"])
        self.assertEqual("9007199254740993101", calls[1][2]["requester_end_user_id"])
        self.assertEqual({"group_binding_id": "opaque/group", "trigger_id": "opaque/trigger"}, calls[2][3]["query"])
        self.assertEqual("queued", evidence["steps"][1]["response"]["status"])
        self.assertEqual("accepted", evidence["steps"][2]["response"]["status"])

    def test_unknown_trigger_retains_record_receipt_and_original_command_without_reading_or_resubmitting(self):
        receipt = self.receipt
        calls = []

        class Client:
            def request(self, method, path, body, **kwargs):
                calls.append(path)
                if path.endswith("/messages"):
                    return copy.deepcopy(receipt)
                raise TimeoutError()

        evidence = run_messages(Client(), self.triggering_plan(), evidence_kind="client_fixture")
        self.assertEqual("UNKNOWN", evidence["status"])
        self.assertEqual(2, len(calls))
        self.assertEqual("PASS", evidence["steps"][0]["status"])
        self.assertEqual("trigger-key", evidence["steps"][-1]["idempotency_key"])
        self.assertEqual(receipt["source_message_id"], evidence["steps"][-1]["body"]["source_message_id"])

    def test_record_receipt_cannot_pretend_that_recording_created_a_run(self):
        receipt = {**self.receipt, "run_id": "fake-run"}

        class Client:
            def request(self, *args, **kwargs):
                return receipt

        evidence = run_messages(Client(), self.triggering_plan(), evidence_kind="client_fixture")
        self.assertEqual("FAILED", evidence["status"])
        self.assertEqual("GROUP_MESSAGE_SOURCE_RECEIPT_INVALID", evidence["code"])
        self.assertEqual(1, len(evidence["steps"]))

    def test_requester_requires_original_author_and_non_user_authors_require_source_proof(self):
        plan = self.triggering_plan()
        plan["messages"][0]["trigger"]["requester_end_user_id"] = "9007199254740993103"
        with self.assertRaisesRegex(ValueError, "GROUP_TRIGGER_REQUIRES_ORIGINAL"):
            validate_plan(plan)
        plan = copy.deepcopy(self.plan)
        for author in ({"kind": "agent", "agent_id": "1", "run_id": "run"}, {"kind": "system"}, {"kind": "tool", "agent_id": "1"}):
            plan["messages"][0]["author"] = author
            with self.assertRaisesRegex(ValueError, "GROUP_MESSAGE_AUTHOR_INVALID"):
                validate_plan(plan)

    def test_rejected_current_trigger_stops_before_next_message(self):
        receipt, trigger = self.receipt, self.trigger

        class Client:
            def request(self, method, path, body, **kwargs):
                if path.endswith("/messages"):
                    return copy.deepcopy(receipt)
                if method == "POST":
                    return copy.deepcopy(trigger)
                return {**copy.deepcopy(trigger), "status": "rejected", "rejection_code": "member_left"}

        plan = self.triggering_plan()
        plan["messages"].append(copy.deepcopy(self.message))
        evidence = run_messages(Client(), plan, evidence_kind="client_fixture")
        self.assertEqual("FAILED", evidence["status"])
        self.assertEqual("GROUP_TRIGGER_REJECTED", evidence["code"])
        self.assertEqual(3, len(evidence["steps"]))

    def test_wait_response_forwards_original_chat_payload_without_creating_a_message_or_replacing_identity(self):
        hitl = {"execution_id": "original/run", "run_revision": "9007199254740993101",
                "human_input": {"wait": {"action_id": "original/action", "generation": "9007199254740993102"}, "text": "确认"}}
        plan = {"group_binding_id": "opaque/group", "responses": [{"run_id": "original/run", "input_id": "original/response-input",
                "responder_end_user_id": "9007199254740993103", "hitl_response": hitl}]}
        calls = []

        class Client:
            def request(self, method, path, body, **kwargs):
                calls.append((method, path, body, kwargs))
                if method == "GET":
                    return {"group_binding_id": "opaque/group", "session_id": "opaque/session", "run_id": "original/run",
                            "agent_id": "9007199254740993102", "status": "suspended", "phase": "WAITING",
                            "revision": hitl["run_revision"], "pending_interactions": []}
                return {"group_binding_id": body["group_binding_id"], "session_id": "opaque/session", "run_id": body["run_id"],
                        "input_id": body["input_id"], "status": "running", "replayed": False}

        evidence = run_messages(Client(), plan, evidence_kind="client_fixture")
        self.assertEqual("PASS", evidence["status"])
        self.assertEqual(["GET", "POST", "GET"], [call[0] for call in calls])
        self.assertEqual("/open-platform/groups/runs/respond", calls[1][1])
        self.assertIs(hitl, calls[1][2]["hitl_response"])
        self.assertEqual("9007199254740993101", calls[1][2]["hitl_response"]["run_revision"])
        self.assertEqual("9007199254740993102", calls[1][2]["hitl_response"]["human_input"]["wait"]["generation"])
        self.assertEqual("original/response-input", calls[1][3]["idempotency_key"])
        self.assertEqual("original/response-input", calls[1][2]["input_id"])
        self.assertFalse(any(call[1].endswith("/messages") or call[1].endswith("/triggers") for call in calls))

    def test_cancel_and_steer_target_original_run_with_input_as_command_key(self):
        base = {"run_id": "original/run", "operator_end_user_id": "9007199254740993103"}
        commands = [{**base, "input_id": "steer-input", "action": "steer", "text": "补充"},
                    {**base, "input_id": "cancel-input", "action": "cancel_execution"}]
        plan = {"group_binding_id": "opaque/group", "commands": commands}
        calls = []

        class Client:
            def request(self, method, path, body, **kwargs):
                calls.append((method, path, body, kwargs))
                if method == "GET":
                    return {"group_binding_id": "opaque/group", "run_id": "original/run"}
                return {"group_binding_id": body["group_binding_id"], "session_id": "opaque/session", "run_id": body["run_id"],
                        "input_id": body["input_id"], "status": "cancelling" if body["action"] == "cancel_execution" else "running", "replayed": False}

        evidence = run_messages(Client(), plan, evidence_kind="client_fixture")
        self.assertEqual("PASS", evidence["status"])
        posts = [call for call in calls if call[0] == "POST"]
        self.assertEqual(2, len(posts))
        for index, (_, path, body, options) in enumerate(posts):
            self.assertEqual("/open-platform/groups/runs/commands", path)
            self.assertEqual({"group_binding_id": "opaque/group", **commands[index]}, body)
            self.assertEqual(body["input_id"], options["idempotency_key"])
            self.assertNotIn("source_message_id", body)
            self.assertNotIn("requester_end_user_id", body)
        self.assertNotIn("text", posts[1][2])
        self.assertEqual("NOT_RUN", evidence["run_completion"])

    def test_steer_requires_text_and_cancel_forbids_text_before_any_http(self):
        command = {"run_id": "original/run", "input_id": "command-input", "operator_end_user_id": "1"}
        for invalid in ({**command, "action": "cancel_execution", "text": "不得夹带补充"},
                        {**command, "action": "steer"}, {**command, "action": "steer", "text": "  "}):
            with self.assertRaisesRegex(ValueError, "GROUP_RUN_COMMAND_TEXT_INVALID"):
                validate_plan({"group_binding_id": "opaque/group", "commands": [invalid]})


if __name__ == "__main__":
    unittest.main()
