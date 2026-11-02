"""群桥接的固定身份、正式wire、独立权限和UNKNOWN原请求持久化。"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlparse
from urllib.request import Request, urlopen
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "integration-scenario"))
from client import ApiFailure
from group_console import Context, ConsoleFailure, GroupConsole, SectionState, WriteState
from group_contracts import Operation
from group_sender_http import LOCAL_INTENTS, DELIVERIES, SENDER
from group_sender import digest as sender_digest
from demo import create_server

GROUP = "9223372036854775806"
USER = "9223372036854775807"
AGENT = "9223372036854775805"


class Remote:
    """只替代远端业务API；不替代本地命令持久化和HTTP边界。"""
    def __init__(self):
        self.calls, self.failures = [], {}
        self.write_result = None
        self.authorized = True
        self.blocked = self.release = None

    def request(self, method, path, body=None, query=None, idempotency_key=None):
        self.calls.append((method, path, body, query, idempotency_key))
        if path in self.failures:
            raise self.failures[path]
        if path.endswith("/authorize"):
            return {"group_binding_id": GROUP, "action": body["action"], "authorized": self.authorized}
        if path.endswith("/controls"):
            if self.blocked:
                self.blocked.set()
                self.release.wait(5)
            if self.write_result is not None:
                return self.write_result
            return {"group_binding_id": GROUP, "command_id": body["command_id"], "result_kind": "accepted", "status": "PARTIAL", "control_revision": 1}
        if path.endswith("/controls/detail"):
            return {"group_binding_id": GROUP, "command_id": query["command_id"], "result_kind": "accepted", "status": "APPLIED"}
        if path.endswith("/members/update"):
            return {"group_binding_id": GROUP, "end_user_id": body["end_user_id"], "revision": body["expected_revision"] + 1}
        if path.endswith("/agents/update"):
            return {"group_binding_id": GROUP, "agent_id": body["agent_id"], "revision": body["expected_revision"] + 1}
        if path.endswith("/memories/review"):
            return {"group_binding_id": GROUP, "fact_id": body["fact_id"], "revision": 2, "status": "ACTIVE"}
        if path.endswith("/memories/extraction-policy"):
            return {"group_binding_id": GROUP, "enabled": False, "extractor_agent_id": None, "revision": 0}
        if path.endswith("/runs/respond"):
            return {"group_binding_id": GROUP, "run_id": body["run_id"], "input_id": body["input_id"], "status": "resuming", "replayed": False}
        if path.endswith("/members") or path.endswith("/agents"):
            return {"items": [], "has_more": True, "next_after": USER}
        if path.endswith("/memories"):
            return []
        return {"group_binding_id": GROUP, "items": [], "next_cursor": None, "has_more": False}


class GroupConsoleTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "groups.sqlite3"
        self.context = Context("a", "真实群", "http://127.0.0.1:8081", GROUP, USER, AGENT, "GROUP_KEY", "GROUP_SECRET")
        self.remote = Remote()
        self.console = GroupConsole((self.context,), self.path, client_factory=lambda context: self.remote)

    def tearDown(self):
        self.directory.cleanup()

    def control(self):
        return {"command_id": "pause-1", "expected_revision": 0, "desired_state": "paused", "scopes": ["execution", "send"]}

    def writes(self):
        return [call for call in self.remote.calls if call[1].endswith("/controls")]

    def test_snapshot_keeps_forbidden_sections_distinct_and_pagination_exact(self):
        self.remote.failures["/open-platform/groups/memories"] = ApiFailure(403, "MEMORY_DENIED")
        self.remote.failures["/open-platform/groups/history"] = RuntimeError("HTTP_OUTCOME_UNKNOWN")
        snapshot = self.console.snapshot("a", members_after=AGENT, agents_after=USER)
        self.assertEqual(SectionState.FORBIDDEN, snapshot["memories"]["state"])
        self.assertIsNone(snapshot["memories"]["data"])
        self.assertEqual("MEMORY_DENIED", snapshot["memories"]["error_code"])
        self.assertEqual(SectionState.FAILED, snapshot["history"]["state"])
        self.assertEqual(SectionState.READY, snapshot["control"]["state"])
        self.assertTrue(snapshot["members"]["data"]["has_more"])
        self.assertEqual(USER, snapshot["members"]["data"]["next_after"])
        self.assertEqual("SENDER_NOT_CONFIGURED", snapshot["sender"]["error_code"])
        members = next(call for call in self.remote.calls if call[1].endswith("/members"))
        self.assertEqual(AGENT, members[3]["after_id"])
        self.assertEqual(GROUP, members[3]["group_binding_id"])
        safe = json.dumps(self.console.listing())
        for secret in ("GROUP_KEY", "GROUP_SECRET", "origin", "api_key_env", "signing_secret_env"):
            self.assertNotIn(secret, safe)

    def test_fixed_server_context_and_strict_boolean_are_not_browser_overridable(self):
        for field in ("tenant_id", "principal_id", "group_binding_id", "operator_end_user_id", "agent_id", "extractor_agent_id"):
            with self.assertRaises(ConsoleFailure):
                self.console.submit("a", "control", self.control() | {field: "forged"}, "pause-1")
        with self.assertRaises(ConsoleFailure):
            self.console.submit("a", "memory-review", {"fact_id": "f1", "expected_revision": 1, "approved": "true"}, "review")
        self.assertEqual([], self.writes())

    def test_current_authorization_precedes_first_control_dispatch_and_acceptance_is_not_applied(self):
        self.remote.authorized = False
        with self.assertRaises(ConsoleFailure) as denied:
            self.console.submit("a", "control", self.control(), "pause-1")
        self.assertEqual(403, denied.exception.status)
        self.assertEqual([], self.writes())
        self.remote.authorized = True
        status, receipt = self.console.submit("a", "control", self.control(), "pause-1")
        self.assertEqual(200, status)
        self.assertEqual(WriteState.CONFIRMED, receipt.state)
        self.assertEqual("PARTIAL", receipt.data["status"])
        self.assertEqual("accepted", receipt.data["result_kind"])
        self.assertEqual(GROUP, self.writes()[0][2]["group_binding_id"])
        self.assertEqual("pause-1", self.writes()[0][4])

    def test_agent_management_target_is_distinct_from_fixed_memory_actor(self):
        target = "9223372036854775804"
        command = {"agent_id": target, "status": "active", "expected_revision": 0,
                   "source_event_id": "add-agent", "source_revision": 1}
        status, receipt = self.console.submit("a", "agent", command, "add-agent")
        self.assertEqual((200, target), (status, receipt.data["agent_id"]))
        call = next(call for call in self.remote.calls if call[1].endswith("/agents/update"))
        self.assertEqual(target, call[2]["agent_id"])
        self.assertEqual(GROUP, call[2]["group_binding_id"])
        self.assertEqual(AGENT, self.console.listing()["items"][0]["agent_id"])

    def test_cached_memory_receipt_requires_current_reader_agent_scope(self):
        body = {"fact_id": "f1", "expected_revision": 1, "approved": True}
        self.assertEqual(WriteState.CONFIRMED, self.console.submit("a", "memory-review", body, "review")[1].state)
        self.remote.failures["/open-platform/groups/memories"] = ApiFailure(403, "MEMORY_READ_REVOKED")
        with self.assertRaises(ApiFailure) as denied:
            self.console.submit("a", "memory-review", body, "review")
        self.assertEqual(403, denied.exception.status)
        read = self.remote.calls[-1]
        self.assertEqual(("GET", "/open-platform/groups/memories"), read[:2])
        self.assertEqual((GROUP, USER, AGENT), tuple(read[3][name] for name in ("group_binding_id", "reader_end_user_id", "agent_id")))
        self.assertEqual(1, sum(call[1].endswith("/memories/review") for call in self.remote.calls))

    def test_unknown_survives_restart_and_original_command_read_resolves_without_redispatch(self):
        self.remote.failures["/open-platform/groups/controls"] = RuntimeError("HTTP_OUTCOME_UNKNOWN")
        status, original = self.console.submit("a", "control", self.control(), "pause-1")
        self.assertEqual((202, WriteState.UNKNOWN), (status, original.state))
        recovered = GroupConsole((self.context,), self.path, client_factory=lambda context: self.remote)
        self.assertEqual(WriteState.UNKNOWN, recovered.submit("a", "control", self.control(), "pause-1")[1].state)
        self.assertEqual(1, len(self.writes()))
        with self.assertRaises(ConsoleFailure) as conflict:
            recovered.submit("a", "control", self.control() | {"desired_state": "running"}, "pause-1")
        self.assertEqual(409, conflict.exception.status)
        self.assertEqual("APPLIED", recovered.command("a", "pause-1")["status"])
        replay = recovered.submit("a", "control", self.control(), "pause-1")[1]
        self.assertEqual(WriteState.CONFIRMED, replay.state)
        self.assertTrue(replay.replayed)
        self.assertEqual(1, len(self.writes()))

    def test_damaged_success_or_wrong_scope_remains_unknown(self):
        for result in ({}, {"group_binding_id": "other", "command_id": "pause-1", "result_kind": "accepted", "status": "PARTIAL", "control_revision": 1}):
            with self.subTest(result=result), tempfile.TemporaryDirectory() as directory:
                self.remote.write_result = result
                console = GroupConsole((self.context,), Path(directory) / "requests.sqlite3", client_factory=lambda context: self.remote)
                self.assertEqual(WriteState.UNKNOWN, console.submit("a", "control", self.control(), "pause-1")[1].state)

    def test_concurrent_duplicate_request_does_not_acquire_another_dispatch(self):
        self.remote.blocked, self.remote.release = threading.Event(), threading.Event()
        with ThreadPoolExecutor(max_workers=2) as executor:
            first = executor.submit(self.console.submit, "a", "control", self.control(), "pause-1")
            self.assertTrue(self.remote.blocked.wait(5))
            status, duplicate = self.console.submit("a", "control", self.control(), "pause-1")
            self.assertEqual((202, WriteState.UNKNOWN), (status, duplicate.state))
            self.remote.release.set()
            self.assertEqual(WriteState.CONFIRMED, first.result(5)[1].state)
        self.assertEqual(1, len(self.writes()))

    def test_exact_hitl_reply_preserves_wait_and_injects_current_responder(self):
        payload = {"run_id": "run-1", "input_id": "reply-1", "hitl_response": {
            "execution_id": "run-1", "run_revision": "7", "tool_input": {"wait": {"action_id": "a1", "generation": "2"},
            "responses": {"confirm_business": {"method": "elicitation/create", "result": {"schema_id": "application/json", "canonical_json": '{"action":"accept","content":{"confirmed":true}}'}}}}}}
        self.console.submit("a", "run-respond", payload, "reply-1")
        call = next(call for call in self.remote.calls if call[1].endswith("/runs/respond"))
        self.assertEqual(USER, call[2]["responder_end_user_id"])
        self.assertEqual(payload["hitl_response"], call[2]["hitl_response"])
        with self.assertRaises(ConsoleFailure):
            self.console.submit("a", "run-respond", payload | {"run_id": "other"}, "reply-2")

    def test_sender_intent_paths_delegate_existing_a103_protocol_without_fabricating_release(self):
        context = Context("sender", "发送对账", "http://127.0.0.1:8081", GROUP, USER, AGENT,
                          "GROUP_KEY", "GROUP_SECRET", "http://127.0.0.1:8097", "SENDER_TOKEN")
        calls = []
        class Sender:
            def request(self, method, path, body=None, key=None):
                calls.append((method, path, body, key))
                if path == SENDER + "/snapshot":
                    return {"intents": [], "endpoints": [], "deliveries": [], "audit": []}
                if path == DELIVERIES + "/reconcile":
                    return {"group_id": GROUP, "delivery_id": body["delivery_id"], "status": "UNKNOWN"}
                return {"intent_id": body.get("intent_id", body.get("local_intent_id")), "group_id": GROUP,
                        "revision": 1, "active": True, "status": "REMOTE_UNKNOWN", "attempts": []}
        console = GroupConsole((context,), self.path, client_factory=lambda context: self.remote, sender_factory=lambda context: Sender())
        console.submit("sender", "sender-intent/create", {"local_intent_id": "intent", "expected_revision": 0, "scopes": ["send"]}, "create")
        console.submit("sender", "sender-intent/reconcile", {"intent_id": "intent", "expected_revision": 1, "action": "query"}, "reconcile")
        console.submit("sender", "sender-intent/release", {"intent_id": "intent", "expected_revision": 2, "resume_command_id": "resume"}, "release")
        console.submit("sender", "sender-delivery-reconcile", {"delivery_id": "delivery"}, "delivery-reconcile")
        self.assertEqual([LOCAL_INTENTS, LOCAL_INTENTS + "/reconcile", LOCAL_INTENTS + "/release", DELIVERIES + "/reconcile"], [call[1] for call in calls])
        self.assertEqual(GROUP, calls[0][2]["group_binding_id"])
        self.assertEqual({"intent_id": "intent", "expected_revision": 2, "resume_command_id": "resume"}, calls[2][2])

    def test_unknown_sender_reads_exact_original_receipt_without_redispatch(self):
        context = Context("sender", "发送对账", "http://127.0.0.1:8081", GROUP, USER, AGENT,
                          "GROUP_KEY", "GROUP_SECRET", "http://127.0.0.1:8097", "SENDER_TOKEN")
        calls = []
        response = None
        class Sender:
            def request(self, method, path, body=None, key=None):
                calls.append((method, path, body, key))
                if method == "POST":
                    raise RuntimeError("RESPONSE_LOST_AFTER_COMMIT")
                if response is None:
                    raise ApiFailure(404, "LOCAL_COMMAND_RECEIPT_NOT_FOUND")
                if isinstance(response, Exception):
                    raise response
                return response
        console = GroupConsole((context,), self.path, client_factory=lambda context: self.remote, sender_factory=lambda context: Sender())
        body = {"intent_id": "intent", "expected_revision": 1, "action": "query"}
        self.assertEqual(WriteState.UNKNOWN, console.submit("sender", "sender-intent/reconcile", body, "request-1")[1].state)
        self.assertEqual(WriteState.UNKNOWN, console.submit("sender", "sender-intent/reconcile", body, "request-1")[1].state)
        data = {"intent_id": "intent", "group_id": GROUP, "revision": 2, "active": True, "status": "RECONCILIATION_REQUIRED", "attempts": []}
        response = {"operation": "intent.reconcile", "request_key": "request-1", "fingerprint": "wrong", "receipt": data}
        status, receipt = console.submit("sender", "sender-intent/reconcile", body, "request-1")
        self.assertEqual((202, WriteState.UNKNOWN, "GROUP_SENDER_COMMAND_RECEIPT_MISMATCH"), (status, receipt.state, receipt.error_code))
        response["fingerprint"] = sender_digest(body)
        status, receipt = console.submit("sender", "sender-intent/reconcile", body, "request-1")
        self.assertEqual((200, WriteState.CONFIRMED), (status, receipt.state))
        self.assertEqual("RECONCILIATION_REQUIRED", receipt.data["status"])
        self.assertEqual(1, sum(call[0] == "POST" for call in calls))
        self.assertEqual({"operation": ["intent.reconcile"], "request_key": ["request-1"]}, parse_qs(urlparse(calls[-1][1]).query))
        self.assertTrue(console.submit("sender", "sender-intent/reconcile", body, "request-1")[1].replayed)
        response = ApiFailure(403, "CURRENT_RECONCILE_REVOKED")
        with self.assertRaises(ApiFailure) as denied:
            console.submit("sender", "sender-intent/reconcile", body, "request-1")
        self.assertEqual(403, denied.exception.status)
        self.assertEqual(1, sum(call[0] == "POST" for call in calls))


class GroupConsoleHttpTest(unittest.TestCase):
    def test_real_hmac_snapshot_and_same_origin_bridge_keep_credentials_on_server(self):
        requests, failures = [], []
        class Upstream(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def handle_api(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                expected = hmac.new(b"test-secret", "\n".join((self.headers["X-Timestamp"], self.command, self.path,
                                hashlib.sha256(body).hexdigest())).encode(), hashlib.sha256).hexdigest()
                if self.headers.get("X-Api-Key") != "test-key" or not hmac.compare_digest(expected, self.headers.get("X-Signature", "")):
                    failures.append("hmac_mismatch")
                requests.append((self.command, self.path, body))
                parsed = urlparse(self.path)
                if parsed.path.endswith("/authorize"):
                    result = {"group_binding_id": GROUP, "action": json.loads(body)["action"], "authorized": True}
                elif parsed.path.endswith("/members") or parsed.path.endswith("/agents"):
                    result = {"items": [], "next_after": None, "has_more": False}
                elif parsed.path.endswith("/memories"):
                    result = []
                else:
                    result = {"group_binding_id": GROUP, "items": []}
                raw = json.dumps({"code": "OK", "data": result}).encode()
                self.send_response(200); self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
            do_GET = do_POST = handle_api
        upstream = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        thread = threading.Thread(target=upstream.serve_forever, daemon=True); thread.start()
        demo = demo_thread = None
        try:
            with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"GROUP_KEY": "test-key", "GROUP_SECRET": "test-secret"}):
                path = Path(directory)
                context = Context("a", "群大ID", "http://127.0.0.1:" + str(upstream.server_port), GROUP, USER, AGENT, "GROUP_KEY", "GROUP_SECRET")
                config = path / "groups.json"; config.write_text(json.dumps({"contexts": [asdict(context)]}), encoding="utf-8")
                with patch("demo.DemoState"):
                    demo = create_server(0, path / "demo.sqlite3", {}, config)
                demo_thread = threading.Thread(target=demo.serve_forever, daemon=True); demo_thread.start()
                origin = "http://127.0.0.1:" + str(demo.server_port)
                with urlopen(origin + "/api/groups/contexts") as response:
                    listing = json.load(response)
                self.assertEqual(GROUP, listing["items"][0]["group_binding_id"])
                self.assertNotIn("test-secret", json.dumps(listing))
                with urlopen(origin + "/api/groups/a/snapshot?members_after=" + AGENT) as response:
                    snapshot = json.load(response)
                self.assertEqual("ready", snapshot["group"]["state"])
                self.assertEqual("failed", snapshot["sender"]["state"])
                self.assertEqual([], failures)
                query = parse_qs(urlparse(next(path for method, path, body in requests if path.startswith("/api/v1/open-platform/groups/members?"))).query)
                self.assertEqual([AGENT], query["after_id"])
                for target, body, headers, expected in (
                    ("/api/groups/a/control", {"group_binding_id": "forged"}, {"Content-Type": "application/json", "Origin": origin}, 422),
                    ("/api/groups/a/control", {}, {"Content-Type": "application/json", "Origin": "https://outside.invalid"}, 403),
                ):
                    with self.assertRaises(HTTPError) as rejected:
                        urlopen(Request(origin + target, data=json.dumps(body).encode(), headers=headers | {"Idempotency-Key": "key"}, method="POST"))
                    self.assertEqual(expected, rejected.exception.code)
                    rejected.exception.close()
                for target, data in (("/api/groups/a/control", b"{"), ("/api/groups/a/snapshot?broken", None)):
                    with self.assertRaises(HTTPError) as rejected:
                        urlopen(Request(origin + target, data=data, headers={"Content-Type": "application/json"}, method="POST" if data else "GET"))
                    self.assertEqual(400, rejected.exception.code)
                    rejected.exception.close()
        finally:
            if demo:
                demo.shutdown(); demo.server_close(); demo_thread.join(5)
            upstream.shutdown(); upstream.server_close(); thread.join(5)


if __name__ == "__main__":
    unittest.main()
