"""用户下一轮重试补充器的离线来源/端口所有权/时序边界；不运行服务或模型。"""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parent))
import user_retry_acceptance as retry


class UserRetryAcceptanceTest(unittest.TestCase):
    def test_prior_failure_and_private_password_are_read_without_rewriting_old_evidence(self):
        with tempfile.TemporaryDirectory(dir=retry.REPO / ".runtime-logs") as folder:
            root = Path(folder)
            cases = [{"sessionId": "s" if index < 2 else "s%d" % index, "rootInputId": "input%d" % index,
                      "runId": "run%d" % index, "state": "COMPLETED", "answerHash": "a" * 64} for index in range(4)]
            state = {"state": "FAILED", "failureCode": "REAL_LONG_TOOL_FAILURE_RECOVERY_REQUIRED", "cases": cases,
                     "toolReceipts": [{"toolName": retry.LONG_TOOL, "isError": True, "errorCode": "unavailable"}],
                     "provider": {"pending": 0, "unknown": 0, "failedModels": 0, "missingUsage": 0, "calls": 13}}
            original = json.dumps(state).encode()
            (root / "state.json").write_bytes(original)
            (root / "private.json").write_text(json.dumps({"institutionalPassword": "isolated-password"}), encoding="utf-8")

            resolved, observed, password = retry.load_prior(root)

            self.assertEqual(root.resolve(), resolved); self.assertEqual("FAILED", observed["state"])
            self.assertEqual("isolated-password", password); self.assertEqual(original, (root / "state.json").read_bytes())

    def test_only_the_exact_unauthenticated_loopback_mcp_endpoint_can_be_rebound(self):
        connector = {"executor_type": "mcp", "status": "active", "auth_type": "none", "base_url": "http://127.0.0.1:18765/mcp"}
        self.assertEqual(18765, retry.exact_fixture_port(connector))
        for url in ("http://example.com:18765/mcp", "http://127.0.0.1:18765/mcp?token=private", "http://user@127.0.0.1:18765/mcp"):
            with self.assertRaises(retry.Stopped):
                retry.exact_fixture_port({**connector, "base_url": url})

    def test_a_live_port_is_never_stolen_and_an_unconfirmed_bind_stops(self):
        connection = Mock()
        with patch.object(retry.socket, "create_connection", return_value=connection), patch.object(retry, "ExclusiveMcpServer") as create:
            with self.assertRaisesRegex(retry.Stopped, "STILL_OWNED"):
                retry.stopped_fixture(18765, "parent-batch")
        connection.close.assert_called_once(); create.assert_not_called()
        with patch.object(retry.socket, "create_connection", side_effect=ConnectionRefusedError), \
                patch.object(retry, "ExclusiveMcpServer", side_effect=OSError):
            with self.assertRaisesRegex(retry.Stopped, "CANNOT_BE_EXCLUSIVELY_REBOUND"):
                retry.stopped_fixture(18765, "parent-batch")

    def test_user_retry_success_must_follow_original_known_failure(self):
        failed = {"finished_at": "2026-10-25T02:00:00Z"}
        retry.recovery_times(failed, {"started_at": "2026-10-25T02:00:01Z"})
        with self.assertRaisesRegex(retry.Stopped, "MUST_FOLLOW"):
            retry.recovery_times(failed, {"started_at": "2026-10-25T01:59:59Z"})
        with self.assertRaisesRegex(retry.Stopped, "TIMES_REQUIRED"):
            retry.recovery_times(failed, {})

    def test_exact_failed_tool_identity_does_not_depend_on_nullable_node_summary(self):
        original = {"sessionId": "session", "rootInputId": "input", "runId": "run"}
        node = {"kind": "TOOL", "status": "FAILED", "summary": None, "run_id": "run",
                "root_input_id": "input", "source_session_id": "session", "name": "sf_" + retry.LONG_TOOL[:28] + "_" + "a" * 32}
        self.assertIs(node, retry.known_failed_tool(original, [node]))
        for changed in ({"root_input_id": "other"}, {"source_session_id": "other"}, {"name": "other_tool"}, {"status": "UNKNOWN"}):
            with self.assertRaisesRegex(retry.Stopped, "ONE_KNOWN_FAILED_TOOL_REQUIRED"):
                retry.known_failed_tool(original, [{**node, **changed}])

    def test_store_creation_failure_before_thread_assignment_still_closes_owned_server(self):
        run = retry.UserRetry.__new__(retry.UserRetry)
        run.server, run.mcp_fixture_created = Mock(), False
        run.close()
        run.server.server_close.assert_called_once(); run.server.shutdown.assert_not_called()


if __name__ == "__main__":
    unittest.main()
