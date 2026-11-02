"""复杂真实入口的本地正负控；不连接服务、数据库或模型。"""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parent))
import complex_live as live


def usage(count=0, tokens=0, known=True):
    return {"usage_known": known, "tokens": tokens, "attempts": [{"attempt_id": str(i)} for i in range(count)],
            "pending_attempts": 0, "unknown_attempts": int(not known), "delegation_records": []}


class ComplexLiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve() / "batch-one"
        self.root.mkdir()
        self.root_patch = patch.object(live, "ACCEPTANCE_PARENT", self.root.parent)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.addCleanup(self.temp.cleanup)
        self.api = Mock()
        self.api.request.return_value = {"items": []}
        self.reader = Mock()
        self.reader.augment.side_effect = lambda trace, sid: trace
        self.provider = Mock()
        self.provider.preflight.return_value = {"invocation_id_upper_bound": "10", "usage_id_upper_bound": "20"}
        self.provider.snapshot.return_value = usage()
        self.chat_calls = []
        def invoke(api, body, path, timeout, **kwargs):
            self.chat_calls.append((dict(body), kwargs))
            return {"terminal": {"status": "completed", "run_id": "run:" + body["client_message_id"]}}
        self.chat_patch = patch.object(live, "chat", side_effect=invoke)
        self.chat_patch.start(); self.addCleanup(self.chat_patch.stop)
        self.trace_patch = patch.object(live, "collect_trace", return_value={"pages": [], "contents": {}})
        self.trace_patch.start(); self.addCleanup(self.trace_patch.stop)
        self.run = live.ComplexLiveRun(self.api, self.reader, self.provider, self.root, {})

    def send(self, run=None, **kwargs):
        return (run or self.run).send_turn("session-1", "agent-1", "user-1", "调查问题", case_id="C01", turn=0, **kwargs)

    def test_explicit_begin_and_frozen_boundary_survive_restart(self):
        with self.assertRaises(live.ComplexLiveStopped): self.send()
        first = self.run.begin()
        another = live.ComplexLiveRun(self.api, self.reader, self.provider, self.root, {})
        self.assertEqual(first["started_epoch"], another.begin()["started_epoch"])
        self.provider.preflight.assert_called_once()
        self.assertEqual(first["boundary"], self.provider.boundary)

    def test_new_batch_keeps_previous_closed_window_and_budget_unchanged(self):
        self.run.begin()
        state = json.loads(self.run.journal.read_text(encoding="utf-8"))
        state["stop_reason"] = "dispatch_window_elapsed"
        live.save(self.run.journal, state)
        original = self.run.journal.read_bytes()
        fresh = live.ComplexLiveRun(self.api, self.reader, self.provider, self.root.parent / "batch-two", {})
        fresh.begin()
        self.assertIsNone(fresh.state["stop_reason"])
        self.assertEqual(original, self.run.journal.read_bytes())
        self.assertEqual(2, self.provider.preflight.call_count)

    def test_intent_precedes_dispatch_and_restart_only_observes_original(self):
        self.run.begin()
        def check(api, body, path, timeout, **kwargs):
            journal = json.loads(self.run.journal.read_text(encoding="utf-8"))
            self.assertEqual("DISPATCHING", journal["intents"][0]["status"])
            self.assertTrue((path.parent / "intent.json").exists())
            return {"terminal": {"status": "completed", "run_id": "run:" + body["client_message_id"]}}
        live.chat.side_effect = check
        first = self.send()
        live.chat.side_effect = lambda api, body, path, timeout, **kw: {"terminal": {"status": "completed", "run_id": first["run_id"]}}
        other = live.ComplexLiveRun(self.api, self.reader, self.provider, self.root, {})
        second = self.send(other)
        self.assertEqual(first["message_id"], second["message_id"])
        self.assertTrue(live.chat.call_args.kwargs["resume"])
        self.assertEqual(1, len(json.loads(self.run.journal.read_text(encoding="utf-8"))["intents"]))
        self.assertTrue((Path(first["folder"]) / "after.json").exists())

    def test_conflicting_repeat_cannot_reuse_or_send(self):
        self.run.begin(); self.send()
        with self.assertRaisesRegex(live.ComplexLiveStopped, "content_conflict"):
            self.run.send_turn("session-1", "agent-1", "user-1", "换正文", case_id="C01", turn=0)
        self.assertEqual(1, live.chat.call_count)

    def test_unknown_usage_blocks_next_input_and_preserves_completed_status(self):
        self.run.begin()
        self.provider.snapshot.side_effect = [usage(), usage(2, 11, False), usage(2, 11, False)]
        result = self.send()
        self.assertEqual("completed", result["status"])
        self.assertFalse(result["usage_known"])
        with self.assertRaises(live.ComplexLiveStopped):
            self.run.send_turn("session-2", "agent-1", "user-1", "后继", case_id="C02", turn=0)
        self.assertEqual(1, live.chat.call_count)

    def test_interrupted_request_is_not_reposted_when_no_provider_attempt_is_visible(self):
        self.run.begin()
        live.chat.side_effect = TimeoutError("private transport text")
        result = self.send()
        self.assertEqual("UNKNOWN", result["status"])
        self.assertEqual("input_outcome_unknown", result["stop_reason"])
        live.chat.side_effect = lambda api, body, path, timeout, **kw: {"terminal": {"status": "completed", "run_id": result["run_id"]}}
        self.send()
        self.assertTrue(live.chat.call_args.kwargs["resume"])
        with self.assertRaises(live.ComplexLiveStopped): self.run.check()
        self.assertNotIn("private transport text", (self.root / "cases/C01/turn-00/timing.json").read_text(encoding="utf-8"))

    def test_child_and_summary_attempts_count_against_shared_reservation(self):
        self.run.begin()
        self.provider.snapshot.return_value = usage(80, 1000)
        with self.assertRaisesRegex(live.ComplexLiveStopped, "reservation"):
            self.run.check(reserve_attempts=24, reserve_tokens=540000)
        live.chat.assert_not_called()

    def test_actual_tokens_are_checked_with_conservative_headroom(self):
        self.run.begin()
        self.provider.snapshot.return_value = usage(2, 1_400_000)
        with self.assertRaisesRegex(live.ComplexLiveStopped, "reservation"): self.run.check()
        live.chat.assert_not_called()

    def test_window_is_frozen_from_begin_and_queries_still_work_after_expiry(self):
        self.run.begin()
        state = json.loads(self.run.journal.read_text(encoding="utf-8")); state["started_epoch"] -= 3601
        live.save(self.run.journal, state)
        with self.assertRaisesRegex(live.ComplexLiveStopped, "window"): self.run.check()
        self.assertTrue(self.run.snapshot()["usage_known"])

    def test_waiting_hitl_keeps_batch_open_and_next_input_binds_original_run(self):
        self.run.begin()
        pending = {"execution_id": "run:original", "pending_ref": "pending-1", "checkpoint_revision": 2, "question": "确认?"}
        live.chat.side_effect = lambda api, body, path, timeout, **kw: {"terminal": {"status": "waiting_hitl", "run_id": "run:original"}, "pending_hitl": pending}
        # The initial request uses a normal deterministic run identity.
        def wait(api, body, path, timeout, **kw):
            rid = "run:" + body["client_message_id"]
            return {"terminal": {"status": "waiting_hitl", "run_id": rid}, "pending_hitl": {**pending, "execution_id": rid}}
        live.chat.side_effect = wait
        first = self.send()
        self.assertEqual("waiting_hitl", first["status"])
        self.assertIsNone(first["stop_reason"])
        response = {**first["pending_hitl"], "response": "批准"}; response.pop("question")
        live.chat.side_effect = lambda api, body, path, timeout, **kw: {"terminal": {"status": "completed", "run_id": body["hitl_response"]["execution_id"]}}
        second = self.run.send_turn("session-1", "agent-1", "user-1", "批准", case_id="C01", turn=1, hitl_response=response)
        self.assertEqual(first["run_id"], second["run_id"])
        self.assertFalse(live.chat.call_args.kwargs["resume"])

    def test_attachment_ids_use_the_real_chat_context_contract(self):
        self.run.begin()
        self.send(attachment_ids=["attachment-1"])
        body = live.chat.call_args.args[1]
        self.assertEqual(["attachment-1"], body["context"]["attachment_ids"])
        self.assertNotIn("attachment_ids", body)

    def test_workflow_hitl_preserves_current_identity_without_pending_ref(self):
        self.run.begin()
        response = {"execution_id": "run:original", "run_revision": 8, "checkpoint_revision": 8,
                    "workflow_execution_id": "workflow:one", "suspend_ref": "wait:one", "expected_revision": 4,
                    "resume_action_key": "wait:one", "action": "wait:one",
                    "response": {"schema_id": "application/json", "canonical_json": '{"action":"confirm"}'}}
        live.chat.side_effect = lambda api, body, path, timeout, **kw: {"terminal": {
            "status": "completed", "run_id": body["hitl_response"]["execution_id"]}}
        self.send(hitl_response=response)
        actual = live.chat.call_args.args[1]["hitl_response"]
        self.assertEqual(response, actual)
        self.assertNotIn("pending_ref", actual)

    def test_raw_internal_events_and_rich_detail_are_returned(self):
        self.run.begin()
        def invoke(api, body, path, timeout, **kw):
            rid = "run:" + body["client_message_id"]
            phase = {"event_id": "e1", "event_type": "run.completed", "content_ref": "session-event:e1", "detail": {"sessionSequence": 1}}
            trace = {"pages": [{"rounds": [{"runs": [{"run_id": rid, "steps": [{"phases": [phase]}]}]}]}],
                     "internal_contents": {"session-event:e1": {"availability": "FULL", "value": {"outcome": {"report": {"output": "已确认"}}}}}}
            live.collect_trace.return_value = trace
            return {"terminal": {"status": "completed", "run_id": rid}}
        live.chat.side_effect = invoke
        result = self.send()
        self.assertEqual("已确认", result["reply"])
        self.assertEqual(1, result["events"][0]["sequence"])
        self.assertEqual("e1", result["rich_events"][0]["event_id"])
        self.reader.augment.assert_called_once()

    def test_drift_path_traversal_and_secrets_in_budget_are_rejected_before_dispatch(self):
        with self.assertRaises(ValueError): live.ComplexLiveRun(self.api, self.reader, self.provider, self.root / "other", {})
        with self.assertRaises(ValueError): live.ComplexLiveRun(self.api, self.reader, self.provider, self.root, {"password": "secret"})
        self.run.begin()
        with self.assertRaises(ValueError): self.run.send_turn("s", "a", "u", "t", case_id="../C01", turn=0)
        changed = live.ComplexLiveRun(self.api, self.reader, self.provider, self.root, {"reserve_tokens": 200000})
        with self.assertRaisesRegex(live.ComplexLiveStopped, "drift"): changed.begin()
        live.chat.assert_not_called()

    def test_missing_provider_receipt_blocks_and_never_leaks_error_text(self):
        self.run.begin(); self.provider.snapshot.side_effect = RuntimeError("dsn=password=secret")
        with self.assertRaisesRegex(live.ComplexLiveStopped, "provider_usage_unavailable"): self.run.check()
        self.assertNotIn("secret", (self.root / "snapshot-error.json").read_text(encoding="utf-8"))
        live.chat.assert_not_called()


if __name__ == "__main__": unittest.main()
