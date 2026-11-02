"""外部模拟业务系统的真实 HTTP 权限、确认、事务与原回执恢复验证。"""
from __future__ import annotations

import http.client
import json
import os
import sqlite3
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from business_service import create_server
from business_workflow import (BUSINESS_TOOLS, BusinessAuthorization, BusinessAuthorizations,
                               BusinessError, BusinessWorkflow, load_business_config)


class BusinessWorkflowHttpTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "business.sqlite3"
        self.now = datetime(2026, 10, 22, 8, tzinfo=timezone.utc)
        self.grant = BusinessAuthorization("token-a", "tenant", "store-a", "operator-a", frozenset({"patient-a"}))
        self.other_store = BusinessAuthorization("token-b", "tenant", "store-b", "operator-b", frozenset({"patient-b"}))
        self.other_operator = BusinessAuthorization("token-c", "tenant", "store-a", "operator-c", frozenset({"patient-a"}))
        self.authority = BusinessAuthorizations([self.grant, self.other_store, self.other_operator])
        self.start()

    def start(self):
        self.server = create_server(0, self.path, business_authorizations=self.authority,
                                    business_clock=lambda: self.now)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.origin = "http://127.0.0.1:" + str(self.server.server_port) + "/mcp"

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.assertFalse(self.thread.is_alive())

    def tearDown(self):
        self.stop()
        self.directory.cleanup()

    def rpc(self, method, params=None, token="token-a", expected=200):
        headers = {"Content-Type": "application/json", "MCP-Protocol-Version": "2026-07-28"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        body = json.dumps({"jsonrpc": "2.0", "id": "business-test", "method": method, "params": params or {}}).encode()
        request = Request(self.origin, data=body, headers=headers)
        if expected != 200:
            with self.assertRaises(HTTPError) as caught:
                urlopen(request, timeout=5)
            self.assertEqual(expected, caught.exception.code)
            with caught.exception as response:
                return json.load(response)
        with urlopen(request, timeout=5) as response:
            packet = json.load(response)
        self.assertEqual("business-test", packet["id"])
        return packet["result"]

    def invoke(self, name, arguments, **kwargs):
        return self.rpc("tools/call", {"name": name, "arguments": arguments}, **kwargs)

    def appointment(self, request_id="appointment-1", patient="patient-a"):
        return {"request_id": request_id, "patient_ref": patient,
                "patient_time": "2026-10-24T09:00:00+08:00", "appointment_time": "2026-10-24T10:00:00+08:00"}

    def confirmation(self, arguments, name="appointment_create", **kwargs):
        waiting = self.invoke(name, arguments, **kwargs)
        self.assertEqual("input_required", waiting["resultType"])
        self.assertEqual({"confirm_business"}, set(waiting["inputRequests"]))
        self.assertEqual("elicitation/create", waiting["inputRequests"]["confirm_business"]["method"])
        message = waiting["inputRequests"]["confirm_business"]["params"]["message"]
        self.assertIn(arguments["patient_ref"], message)
        self.assertIn("expires_at", message)
        self.assertIn("operator-a" if kwargs.get("token", "token-a") == "token-a" else "operator-b", message)
        return waiting

    def resume(self, arguments, waiting, name="appointment_create", response=None, **kwargs):
        return self.rpc("tools/call", {"name": name, "arguments": arguments,
                        "requestState": waiting["requestState"], "inputResponses": {
                            "confirm_business": response or {"action": "accept", "content": {"confirmed": True}}}}, **kwargs)

    def count(self, status=None):
        with closing(sqlite3.connect(self.path)) as db:
            return db.execute("SELECT count(*) FROM business_operations" + (" WHERE status=?" if status else ""),
                              (status,) if status else ()).fetchone()[0]

    def test_discovery_and_business_identity_are_credential_bound(self):
        tools = self.rpc("tools/list")["tools"]
        self.assertEqual(8, len(tools))
        for tool in BUSINESS_TOOLS:
            self.assertFalse({"tenant_ref", "store_ref", "operator_ref"} & set(tool["inputSchema"]["properties"]))
        self.assertEqual(3, len(self.rpc("tools/list", token=None)["tools"]))
        self.assertEqual("BUSINESS_AUTHENTICATION_REQUIRED", self.invoke("appointment_query", {"patient_ref": "patient-a"}, token=None, expected=401)["error"])
        self.assertEqual("BUSINESS_AUTHENTICATION_REQUIRED", self.rpc("tools/list", token="forged", expected=401)["error"])
        self.assertEqual("BUSINESS_PATIENT_FORBIDDEN", self.invoke("appointment_create", self.appointment(patient="patient-b"), expected=403)["error"])
        for field in ("tenant_ref", "store_ref", "operator_ref"):
            self.assertEqual("DECLARED_BUSINESS_ARGUMENTS_REQUIRED", self.invoke("appointment_create", self.appointment() | {field: "forged"}, expected=422)["error"])
        self.assertEqual(0, self.count())

    def test_query_and_evaluation_are_read_only_and_preserve_patient_time(self):
        self.assertEqual([], self.invoke("appointment_query", {"patient_ref": "patient-a"})["structuredContent"]["appointments"])
        args = self.appointment()
        evaluated = self.invoke("appointment_evaluate", {key: value for key, value in args.items() if key != "request_id"})["structuredContent"]
        self.assertFalse(evaluated["submitted"])
        self.assertFalse(evaluated["time_matches"])
        self.assertEqual(args["patient_time"], evaluated["patient_time"])
        self.assertEqual(args["appointment_time"], evaluated["appointment_time"])
        self.assertEqual(0, self.count())

    def test_appointment_confirmation_is_bound_to_original_object_and_parameters(self):
        args = self.appointment()
        waiting = self.confirmation(args)
        self.assertEqual(waiting, self.invoke("appointment_create", args))
        self.assertEqual(0, self.count("created"))
        receipt = self.resume(args, waiting)["structuredContent"]
        self.assertEqual("created", receipt["status"])
        self.assertEqual("operator-a", receipt["operator_ref"])
        self.assertEqual("store-a", receipt["store_ref"])
        self.assertTrue(receipt["submitted"])
        self.assertNotIn("request_state", receipt)
        self.assertEqual(receipt, self.invoke("business_receipt", {"request_id": args["request_id"]})["structuredContent"])
        self.assertEqual(receipt, self.invoke("appointment_create", args)["structuredContent"])
        self.assertEqual(receipt, self.resume(args, waiting)["structuredContent"])
        self.assertEqual([receipt], self.invoke("appointment_query", {"patient_ref": "patient-a"})["structuredContent"]["appointments"])
        self.assertEqual(1, self.count("created"))

    def test_followup_uses_the_same_confirmation_contract_without_creating_an_appointment(self):
        args = {"request_id": "followup-1", "patient_ref": "patient-a", "scheduled_time": "2026-10-25T09:00:00+08:00", "content": "询问恢复情况"}
        waiting = self.confirmation(args, "followup_create")
        receipt = self.resume(args, waiting, "followup_create")["structuredContent"]
        self.assertEqual("followup", receipt["card_type"])
        self.assertEqual(args["content"], receipt["fields"]["content"])
        self.assertEqual([], self.invoke("appointment_query", {"patient_ref": "patient-a"})["structuredContent"]["appointments"])

    def test_changed_arguments_state_or_response_never_commit(self):
        args = self.appointment()
        waiting = self.confirmation(args)
        changed = args | {"appointment_time": "2026-10-24T11:00:00+08:00"}
        self.assertEqual("BUSINESS_IDEMPOTENCY_CONFLICT", self.resume(changed, waiting, expected=409)["error"])
        self.assertEqual("BUSINESS_CONFIRMATION_STATE_MISMATCH", self.resume(args, waiting | {"requestState": "forged"}, expected=409)["error"])
        for response in ({"action": "accept"}, {"action": "accept", "content": {"confirmed": "true"}},
                         {"action": "accept", "content": {"confirmed": True, "operator_ref": "forged"}}):
            self.assertEqual("EXPLICIT_BUSINESS_CONFIRMATION_REQUIRED", self.resume(args, waiting, response=response, expected=422)["error"])
        self.assertEqual(0, self.count("created"))

    def test_expired_or_declined_confirmation_has_no_business_write(self):
        args = self.appointment()
        waiting = self.confirmation(args)
        self.now += timedelta(seconds=300)
        expired = self.resume(args, waiting)["structuredContent"]
        self.assertEqual("expired", expired["status"])
        self.assertFalse(expired["submitted"])
        declined_args = self.appointment("declined")
        declined_wait = self.confirmation(declined_args)
        declined = self.resume(declined_args, declined_wait, response={"action": "decline"})["structuredContent"]
        self.assertEqual("declined", declined["status"])
        self.assertEqual("declined", self.resume(declined_args, declined_wait)["structuredContent"]["status"])
        self.assertEqual(0, self.count("created"))

    def test_current_revocation_blocks_continuation_and_original_receipt(self):
        args = self.appointment()
        waiting = self.confirmation(args)
        narrowed = BusinessAuthorization("token-a", "tenant", "store-a", "operator-a", frozenset({"different-patient"}))
        self.authority.replace([narrowed])
        self.assertEqual("BUSINESS_PATIENT_FORBIDDEN", self.resume(args, waiting, expected=403)["error"])
        self.assertEqual("BUSINESS_PATIENT_FORBIDDEN", self.invoke("business_receipt", {"request_id": args["request_id"]}, expected=403)["error"])
        self.authority.replace([])
        self.assertEqual("BUSINESS_AUTHENTICATION_REQUIRED", self.resume(args, waiting, expected=401)["error"])
        self.assertEqual(0, self.count("created"))

    def test_confirmation_waiting_for_sqlite_write_lock_rechecks_expiry_after_acquisition(self):
        args = self.appointment()
        waiting = self.confirmation(args)
        workflow = BusinessWorkflow(self.path, self.authority, clock=lambda: self.now)
        begin_attempted = threading.Event()
        original_connect = sqlite3.connect

        def traced_connect(*values, **options):
            connection = original_connect(*values, **options)
            connection.set_trace_callback(lambda statement: begin_attempted.set()
                                          if statement == "BEGIN IMMEDIATE" else None)
            return connection

        with closing(original_connect(self.path)) as blocker:
            blocker.execute("BEGIN IMMEDIATE")
            with patch("business_workflow.sqlite3.connect", side_effect=traced_connect), ThreadPoolExecutor(max_workers=1) as workers:
                pending = workers.submit(workflow.invoke, "appointment_create", args, "Bearer token-a",
                                         {"confirm_business": {"action": "accept", "content": {"confirmed": True}}},
                                         waiting["requestState"])
                try:
                    # SQLite trace 在实际取锁前发出；独立连接仍持锁，续答已进入真实写锁等待。
                    self.assertTrue(begin_attempted.wait(timeout=5))
                    self.assertFalse(pending.done())
                    self.now += timedelta(seconds=300)
                finally:
                    blocker.rollback()
                receipt = pending.result(timeout=5)["structuredContent"]
        self.assertEqual("expired", receipt["status"])
        self.assertFalse(receipt["submitted"])
        self.assertEqual(0, self.count("created"))

    def test_request_identity_and_confirmation_cannot_cross_store_or_operator(self):
        args = self.appointment()
        waiting = self.confirmation(args)
        self.assertEqual("not_found", self.invoke("business_receipt", {"request_id": args["request_id"]}, token="token-c")["structuredContent"]["status"])
        self.assertEqual("BUSINESS_CONFIRMATION_NOT_FOUND", self.resume(args, waiting, token="token-c", expected=409)["error"])
        self.assertEqual("not_found", self.invoke("business_receipt", {"request_id": args["request_id"]}, token="token-b")["structuredContent"]["status"])
        other = self.appointment(patient="patient-b")
        other_waiting = self.confirmation(other, token="token-b")
        self.assertEqual("BUSINESS_CONFIRMATION_STATE_MISMATCH", self.resume(other, waiting, token="token-b", expected=409)["error"])
        self.assertEqual("store-b", self.resume(other, other_waiting, token="token-b")["structuredContent"]["store_ref"])
        self.assertEqual(1, self.count("created"))

    def test_parallel_confirmation_reuses_one_original_business_receipt(self):
        args = self.appointment()
        waiting = self.confirmation(args)
        with ThreadPoolExecutor(max_workers=4) as pool:
            receipts = list(pool.map(lambda _: self.resume(args, waiting)["structuredContent"], range(8)))
        self.assertTrue(all(receipt == receipts[0] for receipt in receipts))
        self.assertEqual(1, self.count("created"))
        self.assertEqual("BUSINESS_IDEMPOTENCY_CONFLICT", self.invoke("appointment_create", args | {"patient_time": "2026-10-24T08:00:00+08:00"}, expected=409)["error"])

    def test_lost_result_recovers_by_reading_original_receipt_after_service_restart(self):
        args = self.appointment()
        waiting = self.confirmation(args)
        body = json.dumps({"jsonrpc": "2.0", "id": "lost-response", "method": "tools/call", "params": {
            "name": "appointment_create", "arguments": args, "requestState": waiting["requestState"],
            "inputResponses": {"confirm_business": {"action": "accept", "content": {"confirmed": True}}}}}).encode()
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        connection.request("POST", "/mcp", body, {"Content-Type": "application/json", "Authorization": "Bearer token-a", "Connection": "close"})
        response = connection.getresponse()
        self.assertEqual(200, response.status)
        # 丢弃正文，客户端没有读取到业务结果；只查询原 request_id，不自动再提交。
        response.close()
        connection.close()
        self.stop()
        self.start()
        receipt = self.invoke("business_receipt", {"request_id": args["request_id"]})["structuredContent"]
        self.assertEqual("created", receipt["status"])
        self.assertEqual(1, self.count("created"))
        self.assertEqual(receipt, self.invoke("business_receipt", {"request_id": args["request_id"]})["structuredContent"])
        missing = self.invoke("business_receipt", {"request_id": "unknown-original"})["structuredContent"]
        self.assertEqual("not_found", missing["status"])
        self.assertFalse(missing["replay_authorized"])

    def test_untrusted_metadata_cannot_change_fixed_authorization(self):
        args = self.appointment()
        waiting = self.rpc("tools/call", {"name": "appointment_create", "arguments": args,
            "_meta": {"extraParameters": {"tenant_ref": "forged", "operator_ref": "forged", "patient_ref": "patient-b"}}})
        receipt = self.resume(args, waiting)["structuredContent"]
        self.assertEqual("tenant", receipt["tenant_ref"])
        self.assertEqual("operator-a", receipt["operator_ref"])
        self.assertEqual("patient-a", receipt["fields"]["patient_ref"])

    def test_time_and_string_validation_preserves_no_side_effect(self):
        for time in ("2026-10-24", "2026-10-24T10:00:00", "invalid", 123, None):
            self.invoke("appointment_create", self.appointment() | {"appointment_time": time}, expected=422)
        self.assertEqual("BUSINESS_TARGET_TIME_EXPIRED", self.invoke("appointment_create", self.appointment() | {
            "appointment_time": "2026-10-20T10:00:00+08:00"}, expected=409)["error"])
        self.assertEqual(0, self.count())


class BusinessConfigTest(unittest.TestCase):
    def test_environment_credential_reference_is_required_and_not_in_repr(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "business.json"
            path.write_text(json.dumps({"authorizations": [{"token_env": "GI08_TEST_TOKEN", "tenant_ref": "tenant", "store_ref": "store",
                "operator_ref": "service-operator", "patient_refs": ["patient"]}], "confirmation_ttl_seconds": 120}), encoding="utf-8")
            with patch.dict(os.environ, {"GI08_TEST_TOKEN": "synthetic-secret"}):
                authority, ttl = load_business_config(path)
                self.assertEqual(120, ttl)
                with authority.authorized("Bearer synthetic-secret") as grant:
                    self.assertNotIn("synthetic-secret", repr(grant))
            with patch.dict(os.environ, {}, clear=True):
                with self.assertRaises(BusinessError):
                    load_business_config(path)

    def test_invalid_ttl_cannot_activate_workflow(self):
        with tempfile.TemporaryDirectory() as directory:
            for ttl in (0, True, "300", 3601):
                with self.assertRaisesRegex(BusinessError, "TTL_INVALID"):
                    BusinessWorkflow(Path(directory) / "business.sqlite3", BusinessAuthorizations(), ttl)


if __name__ == "__main__":
    unittest.main()
