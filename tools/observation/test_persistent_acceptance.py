"""离线验证持久验收器的不可重放、精确只读计量和MCP真实错误回执。"""
import io
import json
import os
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import urllib.error

sys.path.insert(0, str(Path(__file__).parent))
import persistent_acceptance as live
from mcp_fixture import FixtureStore, McpFixtureHandler, McpFixtureServer, tool_result


class PersistentAcceptanceTest(unittest.TestCase):
    def journal(self):
        journal = live.Journal.__new__(live.Journal)
        journal.value = {"operations": [], "provider": None}
        journal.flush = Mock()
        return journal

    def test_plan_approval_uses_exact_revision_and_checks_dependency_closure(self):
        acceptance = live.Acceptance.__new__(live.Acceptance)
        acceptance.model, acceptance.model_revision, acceptance.user = "m", 7, "u"
        acceptance.platform = Mock()
        acceptance.platform.request.return_value = {"published_revision": 7}
        acceptance.guard = Mock(started=live.time.monotonic())
        acceptance.guard.check.return_value = {"pending": 0}
        acceptance.journal = self.journal(); acceptance.journal.value["cases"] = []
        acceptance.nodes = Mock(return_value=[])
        work = {"plan_revision": 3, "approval_required": True, "approved_revision": 0,
                "tasks": [{"executor_type": "MEMBER", "run_id": None}]}
        closed = {"approved_revision": 3, "stage": "finished", "revision": 8,
                  "tasks": [{"status": "completed", "depends_on": []},
                            {"status": "completed", "depends_on": ["T1"]}]}
        acceptance.institution = Mock()
        acceptance.institution.request.side_effect = [{"execution_id": "run"}, work, {}, {"status": "completed"}, closed]
        acceptance.turn("session", "agent", "task", approve_plan=True)
        control = acceptance.institution.request.call_args_list[2]
        self.assertEqual("/chat/work/controls", control.args[1])
        self.assertEqual(3, control.args[2]["plan_revision"])
        self.assertEqual("approve_plan", control.args[2]["operation"])
        self.assertNotEqual("approve", control.args[2]["operation"])
        self.assertEqual(2, acceptance.journal.value["cases"][0]["dag"]["taskCount"])

    def test_plan_cancel_uses_cancel_and_rejects_completed_as_cancelled(self):
        acceptance = live.Acceptance.__new__(live.Acceptance)
        acceptance.model, acceptance.model_revision, acceptance.user = "m", 7, "u"
        acceptance.platform = Mock()
        acceptance.platform.request.return_value = {"published_revision": 7}
        acceptance.guard = Mock(started=live.time.monotonic())
        acceptance.guard.check.return_value = {"pending": 0}
        acceptance.journal = self.journal(); acceptance.journal.value["cases"] = []
        acceptance.nodes = Mock(return_value=[])
        work = {"plan_revision": 3, "approval_required": True, "approved_revision": 0,
                "tasks": [{"executor_type": "MEMBER", "run_id": None}]}
        closed = {"approved_revision": 3, "stage": "finished", "revision": 8,
                  "tasks": [{"status": "completed", "depends_on": []},
                            {"status": "completed", "depends_on": ["T1"]}]}
        acceptance.institution = Mock()
        acceptance.institution.request.side_effect = [{"execution_id": "run"}, work, {}, {"status": "cancelled"}]
        acceptance.turn("session", "agent", "task", cancel_plan=True)
        control = acceptance.institution.request.call_args_list[2]
        self.assertEqual("/chat/work/controls", control.args[1])
        self.assertEqual(3, control.args[2]["plan_revision"])
        self.assertEqual("cancel", control.args[2]["operation"])
        self.assertEqual("CANCELLED", acceptance.journal.value["cases"][0]["state"])
        acceptance.institution.request.side_effect = [{"execution_id": "run2"}, work, {}, {"status": "completed"}]
        with self.assertRaisesRegex(live.Stopped, "PLAN_CANCEL_NOT_SETTLED"):
            acceptance.turn("session", "agent", "task", cancel_plan=True)

    def test_typed_attempt_query_and_provider_model_are_enforced(self):
        guard = self.guard()
        guard.tenant = "123"
        guard.container, guard.user, guard.database = "pg", "owner", "test"
        guard.journal = self.journal()
        facts = {"invocations": [{"attempt_id": "a", "operation_type": "model.chat", "status": "SUCCEEDED"}],
                 "usage": [{"attempt_id": "a", "prompt_tokens": 12, "completion_tokens": 3, "model": "deepseek-flash"}]}
        with patch.object(live.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(facts))) as run:
            self.assertEqual(15, guard.snapshot()["tokens"])
            sql = run.call_args.kwargs["input"]
            self.assertIn("agent_runtime_execution_attempts", sql)
            self.assertIn("nw_external_invocations", sql)
            self.assertIn("budget.category='AUXILIARY_MODEL'", sql)
            self.assertIn("core.tenant_id=budget.tenant_id AND core.attempt_id=budget.attempt_id", sql)
        facts["usage"][0]["model"] = "other-model"
        with patch.object(live.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(facts))):
            with self.assertRaisesRegex(live.Stopped, "DEEPSEEK_FLASH_USAGE_REQUIRED"):
                guard.snapshot()

    def test_auxiliary_only_supplier_call_is_counted(self):
        facts = {"invocations": [], "auxiliary": [{"attempt_id": "aux", "operation_type": "model.chat", "status": "SUCCEEDED"}],
                 "usage": [{"attempt_id": "aux", "prompt_tokens": 12, "completion_tokens": 3, "model": "deepseek-flash"}]}
        with patch.object(live.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(facts))):
            result = self.guard().check()
        self.assertEqual((1, 15), (result["calls"], result["tokens"]))

    def test_business_and_auxiliary_supplier_calls_share_batch_budget(self):
        facts = {"invocations": [{"attempt_id": "business", "operation_type": "model.chat", "status": "SUCCEEDED"}],
                 "auxiliary": [{"attempt_id": "aux", "operation_type": "model.chat", "status": "SUCCEEDED"}],
                 "usage": [{"attempt_id": key, "prompt_tokens": 12, "completion_tokens": 3, "model": "deepseek-flash"}
                           for key in ("business", "aux")]}
        with patch.object(live.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(facts))):
            result = self.guard().check()
        self.assertEqual((2, 30), (result["calls"], result["tokens"]))

    def test_same_attempt_is_not_counted_again_from_auxiliary_source(self):
        row = {"attempt_id": "same", "operation_type": "model.chat", "status": "SUCCEEDED"}
        facts = {"invocations": [row], "auxiliary": [row, row],
                 "usage": [{"attempt_id": "same", "prompt_tokens": 12, "completion_tokens": 3, "model": "deepseek-flash"}]}
        with patch.object(live.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(facts))):
            result = self.guard().check()
        self.assertEqual((1, 15), (result["calls"], result["tokens"]))

    def test_auxiliary_pending_unknown_and_failed_preserve_stop_boundary(self):
        for status, reason in (("STARTED", "PREVIOUS_MODEL_ATTEMPT_PENDING"),
                               ("UNRESOLVED", "SUPPLIER_FAILED_OR_UNKNOWN"),
                               ("FAILED", "SUPPLIER_FAILED_OR_UNKNOWN")):
            with self.subTest(status=status):
                facts = {"invocations": [], "auxiliary": [{"attempt_id": "aux", "operation_type": "model.chat", "status": status}], "usage": []}
                with patch.object(live.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(facts))):
                    with self.assertRaisesRegex(live.Stopped, reason):
                        self.guard().check()

    def test_auxiliary_tokens_alone_can_exhaust_batch_budget(self):
        facts = {"invocations": [], "auxiliary": [{"attempt_id": "aux", "operation_type": "model.chat", "status": "SUCCEEDED"}],
                 "usage": [{"attempt_id": "aux", "prompt_tokens": live.MAX_TOKENS, "completion_tokens": 1, "model": "deepseek-flash"}]}
        with patch.object(live.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(facts))):
            with self.assertRaisesRegex(live.Stopped, "BATCH_BUDGET_EXHAUSTED"):
                self.guard().check()

    def test_credentials_do_not_enter_even_public_request_hash(self):
        journal = self.journal()
        first = journal.intent("POST", "/auth/login", {"username": "admin", "password": "first-secret"})
        second = journal.intent("POST", "/auth/login", {"username": "admin", "password": "other-secret"})
        self.assertEqual(first["requestHash"], second["requestHash"])
        self.assertNotIn("secret", json.dumps(journal.value))

    def test_post_429_is_never_replayed_or_waited(self):
        journal = self.journal()
        host = live.Host("http://127.0.0.1:8000/api/v1", journal)
        response = urllib.error.HTTPError("http://127.0.0.1/api/v1/chat", 429, "limited", {"Retry-After": "60"}, io.BytesIO(b"{}"))
        with patch.object(live.urllib.request.OpenerDirector, "open", side_effect=response) as send, patch.object(live.time, "sleep") as wait:
            with self.assertRaisesRegex(live.Stopped, "HTTP_POST_429"):
                host.request("POST", "/chat", {"client_message_id": "original"})
        send.assert_called_once(); wait.assert_not_called()
        self.assertEqual("UNCONFIRMED", journal.value["operations"][0]["state"])

    def test_get_429_retries_once_and_never_creates_a_write_intent(self):
        host = live.Host("http://127.0.0.1:8000/api/v1", self.journal())
        host.guard = SimpleNamespace(remaining=lambda: 100, check=Mock())
        response = urllib.error.HTTPError("http://127.0.0.1/api/v1/chat/runs/status", 429, "limited", {"Retry-After": "0"}, io.BytesIO(b"{}"))
        success = io.BytesIO(json.dumps({"code": "OK", "data": {"status": "completed"}}).encode())
        with patch.object(live.urllib.request.OpenerDirector, "open", side_effect=[response, success]) as send:
            self.assertEqual({"status": "completed"}, host.request("GET", "/chat/runs/status"))
        self.assertEqual(2, send.call_count); self.assertEqual([], host.journal.value["operations"])

    def guard(self):
        environment = {"OBSERVATION_PG_CONTAINER": "owned-postgres", "OBSERVATION_PG_USER": "readonly",
                       "OBSERVATION_PG_DATABASE": "target_db"}
        with patch.dict(os.environ, environment):
            return live.ProviderGuard("42", self.journal())

    def test_provider_reads_are_bounded_readonly_and_exact_tenant(self):
        guard = self.guard()
        facts = {"invocations": [{"attempt_id": "model", "operation_type": "model.chat", "status": "SUCCEEDED"}],
                 "usage": [{"attempt_id": "model", "prompt_tokens": 0, "completion_tokens": 0, "model": "deepseek-flash"}]}
        with patch.object(live.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(facts))) as execute:
            result = guard.check()
        self.assertEqual(1, result["calls"]); self.assertEqual(0, result["tokens"]); self.assertEqual(0, result["missingUsage"])
        arguments = execute.call_args
        self.assertEqual(["docker", "exec", "-i", "owned-postgres", "psql"], arguments.args[0][:5])
        sql = arguments.kwargs["input"]
        self.assertIn("READ ONLY", sql); self.assertIn("tenant_id=42", sql)
        self.assertIn("LIMIT 129", sql); self.assertIn("LIMIT 65", sql); self.assertNotIn("INSERT", sql)

    def test_tool_unknown_and_missing_supplier_usage_block_all_new_posts(self):
        for operation, status, usage in (("tool.call", "UNRESOLVED", []), ("model.chat", "SUCCEEDED", [])):
            facts = {"invocations": [{"attempt_id": "original", "operation_type": operation, "status": status}], "usage": usage}
            with patch.object(live.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(facts))):
                with self.assertRaisesRegex(live.Stopped, "SUPPLIER_FAILED_OR_UNKNOWN"):
                    self.guard().check(observe=True)

    def test_per_turn_conservative_reservation_cannot_exceed_sixty_four(self):
        guard = self.guard()
        guard.snapshot = Mock(return_value={"calls": 49, "tokens": 1, "pending": 0, "unknown": 0, "failedModels": 0, "missingUsage": 0})
        with self.assertRaisesRegex(live.Stopped, "CONSERVATIVE_CALL_RESERVATION_UNAVAILABLE"):
            guard.check(new_input=True)

    def test_long_mcp_alias_is_discovered_and_returns_real_error_then_success(self):
        server = McpFixtureServer(0, "offline", query_alias=live.LONG_TOOL)
        try:
            handler = McpFixtureHandler.__new__(McpFixtureHandler); handler.server = server
            listed = handler._dispatch_rpc({"jsonrpc": "2.0", "id": "list", "method": "tools/list"})
            self.assertIn(live.LONG_TOOL, [tool["name"] for tool in listed["result"]["tools"]])
            self.assertLessEqual(len(live.LONG_TOOL), 64)
            server.store.create("offline:recovery", 391, [{"mode": "unavailable"}, {"mode": "success"}])
            results = [handler._dispatch_rpc({"jsonrpc": "2.0", "id": str(index), "method": "tools/call",
                       "params": {"name": live.LONG_TOOL, "arguments": {"case_key": "offline:recovery"}}})["result"]
                       for index in range(2)]
            self.assertTrue(results[0]["isError"]); self.assertFalse(results[1]["isError"])
            self.assertEqual(503, results[0]["structuredContent"]["status"])
            self.assertEqual(391, results[1]["structuredContent"]["value"])
            self.assertEqual([live.LONG_TOOL, live.LONG_TOOL], [row["tool"] for row in server.store.audit("offline:recovery")["calls"]])
        finally:
            server.server_close()





    def test_failed_recovery_always_freezes_safe_receipts_before_owned_mcp_close(self):
        run = live.Acceptance.__new__(live.Acceptance)
        run.case_key, run.journal = "offline:recovery", self.journal()
        run.journal.value["state"] = "FAILED"
        run.journal.value["failureCode"] = "REAL_LONG_TOOL_FAILURE_RECOVERY_REQUIRED"
        store = FixtureStore("offline")
        store.create(run.case_key, {"private": "must-not-be-saved"})
        event = store.begin_call(run.case_key, live.LONG_TOOL, {"case_key": run.case_key, "secret": "private-argument"})
        store.finish_call(event, tool_result({"error": "unavailable", "status": 503}, True), "error")
        sequence = []
        run.journal.flush.side_effect = lambda: sequence.append("receipt_saved")
        run.server = SimpleNamespace(store=store, shutdown=Mock(), server_close=Mock(side_effect=lambda: sequence.append("server_closed")))
        run.thread = SimpleNamespace(is_alive=Mock(return_value=False), join=Mock())

        run.close()

        self.assertEqual(["receipt_saved", "server_closed"], sequence)
        self.assertEqual("FAILED", run.journal.value["state"])
        receipt = run.journal.value["toolReceipts"][0]
        self.assertEqual(live.LONG_TOOL, receipt["toolName"]); self.assertTrue(receipt["isError"])
        self.assertEqual("unavailable", receipt["errorCode"]); self.assertEqual(64, len(receipt["resultHash"]))
        self.assertEqual({"sequence", "toolName", "isError", "errorCode", "resultHash"}, set(receipt))
        self.assertNotIn("private-argument", json.dumps(run.journal.value)); self.assertNotIn("must-not-be-saved", json.dumps(run.journal.value))


if __name__ == "__main__":
    unittest.main()
