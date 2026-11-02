"""发送域定向测试：真实临时SQLite及受控远端回执，不替代组合HTTP/厂商送达验收。"""
import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from client import ApiFailure
from group_sender import (AttemptStatus, DeliveryStatus, Endpoint, GroupSender, IntentStatus,
                          SenderError, SenderStore, UnknownSend, digest)
from business_service import create_server
from group_sender_http import LocalActor, SenderApplication
from group_sender_client import GroupSenderClient


class Remote:
    def __init__(self):
        self.allowed = {"write", "read", "manage", "ack", "reconcile", "release"}
        self.snapshot = {"group_binding_id": "41", "control_revision": 0, "states": [], "latest_command": None}
        self.commands, self.blocked, self.failure = {}, [], None
        self.qualifications = 0

    def authorize(self, group, action):
        if action not in self.allowed:
            raise ApiFailure(403, "FORBIDDEN")
        return {"group_binding_id": group, "action": action, "authorized": True, "control_revision": self.snapshot["control_revision"]}

    def current(self, group):
        return json.loads(json.dumps(self.snapshot))

    def submit(self, command):
        if self.failure:
            raise self.failure
        old = self.commands.get(command["command_id"])
        if old:
            return old
        if command["expected_revision"] != self.snapshot["control_revision"]:
            raise ApiFailure(409, "CONFLICT")
        receipt = command | {"control_revision": command["expected_revision"] + 1, "status": "APPLIED",
                             "result_kind": "accepted", "fingerprint": digest(command), "result_ref": "accepted:" + command["command_id"]}
        self.commands[command["command_id"]] = receipt
        self.snapshot = {"group_binding_id": "41", "control_revision": receipt["control_revision"], "states": [command], "latest_command": receipt}
        return receipt

    def detail(self, group, command):
        return self.commands[command]

    def terminalize(self, command, key):
        if command["command_id"] in self.commands:
            return self.commands[command["command_id"]]
        boundary = "terminal-boundary:" + command["local_intent_id"]
        self.blocked = [boundary]
        return {"result_kind": "terminal_not_accepted", "result_ref": "terminal:" + key,
                "boundary_ref": boundary, "through_trigger_sequence": 3}

    def resume(self, scopes=None):
        return self.submit({"command_id": "resume:" + str(self.snapshot["control_revision"]), "group_binding_id": "41", "run_id": None,
                            "expected_revision": self.snapshot["control_revision"], "desired_state": "running", "scopes": scopes or ["send"]})

    def register(self, command):
        return command | {"status": "REGISTERING"}

    def retire(self, command):
        return command | {"status": "RETIRED"}

    def ack(self, command):
        return command

    def qualify(self, command):
        self.qualifications += 1
        return command | {"eligible": not self.blocked, "blocked_boundary_refs": list(self.blocked), "root_trigger_id": "root-1", "trigger_sequence": 1,
                          "control_revision": self.snapshot["control_revision"], "content": "正式final", "content_digest": "public-content-digest"}


class Sink:
    def __init__(self):
        self.sent, self.receipts, self.unknown = [], {}, False

    def send(self, request):
        self.sent.append(request)
        receipt = {"delivery_id": request["delivery_id"], "status": "delivered", "result_ref": "sink:" + request["delivery_id"]}
        self.receipts[request["delivery_id"]] = receipt
        if self.unknown:
            raise UnknownSend("RESPONSE_LOST_AFTER_EFFECT")
        return receipt

    def receipt(self, delivery_id):
        return self.receipts.get(delivery_id)


class SenderTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "sender.sqlite3"
        self.store = SenderStore(self.path)
        self.remote, self.sink = Remote(), Sink()
        self.endpoint = Endpoint("41", "sender-1", 1, 0)
        self.sender = GroupSender(self.store, self.endpoint, self.remote, self.sink)
        self.sender.start()
        self.delivery = {"group_binding_id": "41", "run_id": "run-1", "message_id": "message-1", "message_version": 1, "delivery_id": "delivery-1"}

    def tearDown(self):
        self.store.close()
        self.directory.cleanup()

    def intent(self, name="intent-1"):
        return {"local_intent_id": name, "group_binding_id": "41", "scopes": ["send"], "expected_revision": 0}

    def test_qualification_owns_final_and_same_delivery_does_not_send_twice(self):
        with self.assertRaises(SenderError):
            self.sender.deliver(self.delivery | {"run_status": "completed", "phase": "final", "content": "伪造"})
        first = self.sender.deliver(self.delivery)
        second = self.sender.deliver(self.delivery)
        self.assertEqual(DeliveryStatus.DELIVERED, first["status"])
        self.assertEqual(first, second)
        self.assertEqual("正式final", self.sink.sent[0]["content"])
        self.assertEqual(1, len(self.sink.sent))
        with self.assertRaises(SenderError):
            self.sender.deliver(self.delivery | {"message_version": 2})

    def test_local_pause_precedes_remote_failure_and_survives_reopen(self):
        self.remote.failure = RuntimeError("UNKNOWN")
        original = self.sender.create_intent("original-operator", self.intent(), "create")
        self.assertEqual(original, self.sender.create_intent("original-operator", self.intent(), "create"))
        self.assertEqual(AttemptStatus.UNKNOWN, self.store.intent("intent-1")["attempts"][0]["status"])
        self.assertEqual(DeliveryStatus.BLOCKED, self.sender.deliver(self.delivery)["status"])
        self.store.close()
        self.store = SenderStore(self.path)
        restored = GroupSender(self.store, self.endpoint, self.remote, self.sink)
        self.assertEqual(DeliveryStatus.BLOCKED, restored.deliver(self.delivery)["status"])
        self.assertEqual([], self.sink.sent)

    def test_unknown_delivery_keeps_identity_and_only_queries_original_receipt(self):
        self.sink.unknown = True
        unknown = self.sender.deliver(self.delivery)
        self.assertEqual(DeliveryStatus.UNKNOWN, unknown["status"])
        self.assertEqual(DeliveryStatus.UNKNOWN, self.sender.deliver(self.delivery)["status"])
        self.assertEqual(1, len(self.sink.sent))
        reconciled = self.sender.reconcile_delivery("delivery-1")
        self.assertEqual(DeliveryStatus.DELIVERED, reconciled["status"])
        self.assertEqual(1, len(self.sink.sent))

    def test_untrusted_sink_receipt_stays_unknown_under_original_delivery(self):
        self.sink.send = lambda request: ["untrusted-receipt"]
        self.assertEqual(DeliveryStatus.UNKNOWN, self.sender.deliver(self.delivery)["status"])
        self.assertEqual(DeliveryStatus.UNKNOWN, self.sender.deliver(self.delivery)["status"])

    def test_persisted_generation_fences_old_process_and_preserves_unknown(self):
        self.store.enqueue(self.endpoint, self.delivery)
        self.assertTrue(self.store.qualify_dispatch(self.endpoint, "delivery-1", self.remote.qualify(self.delivery)))
        newer_store = SenderStore(self.path)
        try:
            newer = GroupSender(newer_store, Endpoint("41", "sender-1", 2, 1), self.remote, self.sink)
            recovered = newer.start()
            self.assertEqual(DeliveryStatus.UNKNOWN, newer_store.delivery(newer.endpoint, "delivery-1")["status"])
            self.assertEqual([{"delivery_id": "delivery-1", "status": "UNKNOWN", "result_ref": None}], recovered["local"]["in_flight"])
            with self.assertRaisesRegex(SenderError, "ENDPOINT_FENCED"):
                self.sender.deliver(self.delivery | {"delivery_id": "delivery-2"})
            self.assertEqual([], self.sink.sent)
            self.assertEqual(self.store.ledger_ref, newer_store.ledger_ref)
        finally:
            newer_store.close()

    def test_known_403_after_unknown_does_not_prove_original_not_accepted(self):
        self.remote.failure = RuntimeError("UNKNOWN")
        self.sender.create_intent("first", self.intent(), "create")
        self.remote.failure = ApiFailure(403, "FORBIDDEN")
        self.sender.submit_intent("intent-1", "first")
        intent = self.store.intent("intent-1")
        self.assertEqual([AttemptStatus.UNKNOWN, AttemptStatus.REJECTED], [row["status"] for row in intent["submissions"]])
        self.assertTrue(intent["active"])
        self.assertEqual(DeliveryStatus.BLOCKED, self.sender.deliver(self.delivery)["status"])

    def test_abandon_needs_terminal_proof_and_old_output_still_needs_exact_release(self):
        self.remote.failure = RuntimeError("UNKNOWN")
        self.sender.create_intent("first", self.intent(), "create")
        intent = self.store.intent("intent-1")
        abandoned = self.sender.reconcile_intent("successor", {"intent_id": "intent-1", "expected_revision": intent["revision"], "action": "abandon"}, "abandon")
        self.assertEqual(IntentStatus.ABANDONED, abandoned["status"])
        self.assertFalse(abandoned["active"])
        self.assertEqual(DeliveryStatus.BLOCKED, self.sender.deliver(self.delivery)["status"])
        # 模拟中央仅对原消息/version/delivery精确release后的正式资格结果。
        self.remote.blocked = []
        self.assertEqual(DeliveryStatus.DELIVERED, self.sender.deliver(self.delivery)["status"])
        self.assertEqual(1, len(self.sink.sent))

    def test_resubmit_uses_new_command_only_after_terminalization_and_keeps_actor_audit(self):
        self.remote.failure = ApiFailure(409, "CONFLICT")
        self.sender.create_intent("first", self.intent(), "create")
        before = self.store.intent("intent-1")
        self.remote.failure = None
        self.sender.reconcile_intent("successor", {"intent_id": "intent-1", "expected_revision": before["revision"], "action": "resubmit"}, "resubmit")
        after = self.store.intent("intent-1")
        self.assertEqual(2, len(after["attempts"]))
        self.assertNotEqual(after["attempts"][0]["command_id"], after["attempts"][1]["command_id"])
        self.assertEqual(AttemptStatus.TERMINAL_NOT_ACCEPTED, after["attempts"][0]["status"])
        self.assertEqual(["first", "successor"], [row["actor_id"] for row in after["attempts"]])

    def test_terminalization_unknown_reuses_original_body_key_and_keeps_submission_history(self):
        self.remote.failure = RuntimeError("CONTROL_UNKNOWN")
        self.sender.create_intent("first", self.intent(), "create")
        before = self.store.intent("intent-1")
        terminate = self.remote.terminalize
        calls = []

        def terminalize(command, key):
            calls.append((command, key))
            if len(calls) == 1:
                raise RuntimeError("TERMINALIZATION_UNKNOWN")
            return terminate(command, key)

        self.remote.terminalize = terminalize
        request = {"intent_id": "intent-1", "expected_revision": before["revision"], "action": "abandon"}
        with self.assertRaises(RuntimeError):
            self.sender.reconcile_intent("first", request, "original-terminalization")
        self.remote.snapshot["control_revision"] = 5
        result = self.sender.reconcile_intent("successor", request, "successor-local-key")
        self.assertEqual(calls[0], calls[1])
        self.assertEqual(IntentStatus.ABANDONED, result["status"])
        self.assertEqual([AttemptStatus.UNKNOWN, AttemptStatus.TERMINAL_NOT_ACCEPTED], [item["status"] for item in result["terminalizations"]])
        self.assertEqual(["first", "successor"], [item["actor_id"] for item in result["terminalizations"]])

    def test_resume_cannot_release_other_intent_or_be_used_after_permission_revocation(self):
        self.sender.create_intent("first", self.intent(), "create")
        self.remote.failure = RuntimeError("UNKNOWN")
        self.sender.create_intent("other", self.intent("intent-2"), "other-create")
        self.remote.failure = None
        resumed = self.remote.resume()
        self.sender.apply()
        intent = self.store.intent("intent-1")
        request = {"intent_id": "intent-1", "expected_revision": intent["revision"], "resume_command_id": resumed["command_id"]}
        self.remote.allowed.remove("release")
        with self.assertRaises(ApiFailure):
            self.sender.release_intent("first", request, "release")
        self.assertTrue(self.store.intent("intent-1")["active"])
        self.remote.allowed.add("release")
        self.sender.release_intent("successor", request, "release")
        self.assertFalse(self.store.intent("intent-1")["active"])
        self.assertTrue(self.store.intent("intent-2")["active"])
        self.assertEqual(DeliveryStatus.BLOCKED, self.sender.deliver(self.delivery)["status"])

    def test_pause_wins_against_a_previously_fetched_qualification(self):
        authorization = self.remote.qualify(self.delivery)
        self.store.enqueue(self.endpoint, self.delivery)
        self.remote.failure = RuntimeError("UNKNOWN")
        self.sender.create_intent("first", self.intent(), "create")
        self.assertFalse(self.store.qualify_dispatch(self.endpoint, "delivery-1", authorization))
        self.assertEqual(DeliveryStatus.BLOCKED, self.store.delivery(self.endpoint, "delivery-1")["status"])
        self.assertEqual([], self.sink.sent)

    def test_dispatch_wins_and_later_pause_cannot_retract_the_in_flight_result(self):
        entered, finish = threading.Event(), threading.Event()
        failures = []

        class HeldSink(Sink):
            def send(inner, request):
                entered.set()
                if not finish.wait(5):
                    raise UnknownSend("TEST_COMPLETION_NOT_RELEASED")
                return super().send(request)

        held = HeldSink()

        def dispatch():
            store = SenderStore(self.path)
            try:
                GroupSender(store, self.endpoint, self.remote, held).deliver(self.delivery)
            except Exception as error:
                failures.append(type(error).__name__)
            finally:
                store.close()

        thread = threading.Thread(target=dispatch)
        thread.start()
        try:
            self.assertTrue(entered.wait(5))
            self.sender.create_intent("first", self.intent(), "create")
            self.assertEqual(DeliveryStatus.DISPATCHING, self.store.delivery(self.endpoint, "delivery-1")["status"])
            applied = self.sender.apply()
            self.assertEqual([{"delivery_id": "delivery-1", "status": "DISPATCHING", "result_ref": None}], applied["local"]["in_flight"])
        finally:
            finish.set()
            thread.join(5)
        self.assertFalse(thread.is_alive())
        self.assertEqual([], failures)
        self.assertEqual(DeliveryStatus.DELIVERED, self.store.delivery(self.endpoint, "delivery-1")["status"])
        self.assertTrue(self.store.intent("intent-1")["active"])
        self.assertEqual(applied, self.sender.apply())

    def test_same_generation_start_replays_the_original_durable_ack(self):
        first = self.sender.start()
        self.assertEqual([], first["local"]["in_flight"])
        self.assertEqual(first, self.sender.start())
        self.assertEqual(1, self.store.endpoint(self.endpoint)["fence_revision"])

    def test_old_ack_and_cross_actor_create_cannot_override_current_authority(self):
        self.remote.snapshot["control_revision"] = 2
        self.sender.apply()
        with self.assertRaisesRegex(SenderError, "STALE_CONTROL_REVISION"):
            self.store.apply_control(self.endpoint, {"group_binding_id": "41", "control_revision": 1, "states": []})
        self.sender.create_intent("first", self.intent(), "create")
        with self.assertRaisesRegex(SenderError, "EXPLICIT_RECONCILIATION"):
            self.sender.create_intent("other", self.intent(), "cross-actor-create")

    def test_local_release_replay_does_not_clear_a_later_independent_pause(self):
        self.sender.create_intent("first", self.intent(), "create")
        resumed = self.remote.resume()
        self.sender.apply()
        before = self.store.intent("intent-1")
        request = {"intent_id": "intent-1", "expected_revision": before["revision"], "resume_command_id": resumed["command_id"]}
        original = self.sender.release_intent("first", request, "release")
        self.sender.create_intent("other", self.intent("intent-2"), "later-pause")
        self.assertEqual(original, self.sender.release_intent("first", request, "release"))
        self.assertTrue(self.store.intent("intent-2")["active"])


class SenderHttpTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "sender.sqlite3"
        self.remote, self.sink = Remote(), Sink()
        application = SenderApplication(self.path, Endpoint("41", "sender-1", 1, 0), self.remote, self.sink,
                                        (LocalActor("operator", "local-fixture-token", self.remote),))
        self.server = create_server(0, self.path, application)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.origin = "http://127.0.0.1:" + str(self.server.server_port)
        self.client = GroupSenderClient(self.origin, "local-fixture-token")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)
        self.assertFalse(self.thread.is_alive())
        self.directory.cleanup()

    def test_http_token_and_current_remote_scope_precede_any_side_effect(self):
        with self.assertRaises(ApiFailure) as missing:
            GroupSenderClient(self.origin, "wrong-token").request("POST", "/api/v1/group-sender/endpoints/start", {})
        self.assertEqual(401, missing.exception.status)
        self.assertFalse(self.path.exists())
        self.client.request("POST", "/api/v1/group-sender/endpoints/start", {})
        self.remote.allowed.remove("write")
        with self.assertRaises(ApiFailure) as revoked:
            self.client.request("POST", "/api/v1/local-control-intents", {"local_intent_id": "i", "group_binding_id": "41", "scopes": ["send"], "expected_revision": 0}, "create")
        self.assertEqual(403, revoked.exception.status)
        snapshot = self.client.request("GET", "/api/v1/group-sender/snapshot")
        self.assertEqual([], snapshot["intents"])
        self.assertNotIn("local-fixture-token", json.dumps(snapshot))

    def test_configured_group_sender_cannot_fall_back_to_anonymous_room_delivery(self):
        forged = {"delivery_id": "d", "tenant_ref": "t", "room_ref": "r", "session_id": "s", "run_status": "completed", "phase": "final", "content": "伪造final"}
        with self.assertRaises(HTTPError) as old_route:
            urlopen(Request(self.origin + "/delivery/wecom", data=json.dumps(forged).encode(), headers={"Content-Type": "application/json"}), timeout=5)
        self.assertEqual(422, old_route.exception.code)
        old_route.exception.close()
        self.assertEqual([], self.sink.sent)
        self.client.request("POST", "/api/v1/group-sender/endpoints/start", {})
        with self.assertRaises(ApiFailure):
            self.client.request("POST", "/api/v1/group-deliveries", forged)
        snapshot = self.client.request("GET", "/api/v1/group-sender/snapshot")
        self.assertEqual([], snapshot["deliveries"])

    def test_original_local_command_receipt_requires_current_reconcile_and_exact_actor_group(self):
        body = {"local_intent_id": "i", "group_binding_id": "41", "scopes": ["send"], "expected_revision": 0}
        original = self.client.request("POST", "/api/v1/local-control-intents", body, "create")
        path = "/api/v1/local-control-intents/commands/detail?" + urlencode({"operation": "intent.create", "request_key": "create"})
        self.remote.allowed.remove("read")
        found = self.client.request("GET", path)
        self.assertEqual({"operation": "intent.create", "request_key": "create", "fingerprint": digest(body), "receipt": original}, found)
        self.assertEqual("BLOCKING", found["receipt"]["status"])
        self.assertEqual(1, len(self.remote.commands))
        self.remote.allowed.remove("reconcile")
        with self.assertRaises(ApiFailure) as denied:
            self.client.request("GET", path)
        self.assertEqual(403, denied.exception.status)
        self.remote.allowed.add("reconcile")
        store = SenderStore(self.path)
        try:
            with store.transaction():
                store.remember("other", "intent.reconcile", "other-actor", {}, {"group_id": "41"})
                store.remember("operator", "intent.release", "other-group", {}, {"group_id": "42"})
        finally:
            store.close()
        for operation, key, expected in (("intent.create", "missing", 404), ("intent.reconcile", "other-actor", 404),
                                         ("intent.release", "other-group", 403), ("intent.delete", "create", 400)):
            with self.subTest(operation=operation, key=key), self.assertRaises(ApiFailure) as rejected:
                self.client.request("GET", "/api/v1/local-control-intents/commands/detail?" + urlencode({"operation": operation, "request_key": key}))
            self.assertEqual(expected, rejected.exception.status)


if __name__ == "__main__":
    unittest.main()
