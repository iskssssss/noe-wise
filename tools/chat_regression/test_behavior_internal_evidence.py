"""本机内部证据桥接的只读、来源绑定与公开视图回归。"""
from __future__ import annotations

import copy
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parent))

from behavior_capture import evidence
from behavior_contract import digest
from behavior_runner import ExecutionBudget, StopBatch, execute_case
from behavior_suite import make_plan
from behavior_internal_evidence import (
    DSN_ENV,
    InternalEvidenceError,
    InternalEvidenceReader,
    collect_evaluation_trace,
    configure_internal_evidence,
)


class _Cursor:
    def __init__(self, *, one=("on",), rows=()):
        self.one = one
        self.rows = list(rows)
        self.executed = []

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, statement, parameters=None):
        self.executed.append((statement, parameters))

    def fetchone(self):
        return self.one

    def fetchall(self):
        return list(self.rows)


class _Connection:
    def __init__(self, cursor):
        self.cursor_value = cursor
        self.sessions = []
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def cursor(self):
        return self.cursor_value

    def set_session(self, **kwargs):
        self.sessions.append(kwargs)

    def close(self):
        self.closed = True


class _Psycopg2:
    def __init__(self, parsed, connections=()):
        self.parsed = parsed
        self.connections = list(connections)
        self.created_connections = []
        self.connect_calls = []

    def parse_dsn(self, _):
        return dict(self.parsed)

    def connect(self, **kwargs):
        self.connect_calls.append(kwargs)
        if not self.connections:
            raise AssertionError("unexpected_database_connection")
        connection = self.connections.pop(0)
        self.created_connections.append(connection)
        return connection

    def modules(self):
        package = types.ModuleType("psycopg2")
        extensions = types.ModuleType("psycopg2.extensions")
        package.connect = self.connect
        extensions.parse_dsn = self.parse_dsn
        package.extensions = extensions
        return {"psycopg2": package, "psycopg2.extensions": extensions}


def _dsn(host="127.0.0.1"):
    return {"host": host, "port": "5432", "dbname": "skillforge", "user": "reader", "password": "secret"}


def _trace(*, availability="REDACTED"):
    def phase(event_id, event_type, sequence, schema):
        return {"event_id": event_id, "event_type": event_type, "content_ref": "session-event:" + event_id,
                "occurred_at": "2026-09-28T00:00:00Z",
                "detail": {"sessionSequence": sequence, "payloadSchemaId": schema}}

    return {"contents": {
        "session-event:snapshot": {"availability": availability,
                                   "value": {"modelRequest": "PUBLIC_REQUEST"}},
        "session-event:completed": {"availability": availability,
                                    "value": {"outcome": {"report": {"output": "PUBLIC_ANSWER"}}}},
        "session-event:model-result": {"availability": availability,
                                       "value": {"provider": "PUBLIC_MODEL_OUTPUT"}},
    }, "pages": [{"rounds": [{"runs": [{"run_id": "run-1", "steps": [{"action_id": "action-1", "phases": [
        phase("snapshot", "agent.context-snapshot", 10, "context-v1"),
        phase("completed", "run.completed", 20, "outcome-v1"),
        phase("model-result", "agent.model-result", 30, "model-v1"),
    ]}]}]}]}]}


def _private_payloads():
    return {
        "snapshot": {"modelRequest": "PRIVATE_REQUEST", "system": "do-not-publish"},
        "completed": {"outcome": {"report": {"output": '{"value":7}'}}},
        "model-result": {"provider": "PRIVATE_MODEL_OUTPUT", "raw": "do-not-publish"},
    }


def _rows(payloads=None, *, override=None):
    payloads = payloads or _private_payloads()
    rows = [
        ("snapshot", "agent.context-snapshot", 10, "run-1", "context-v1", json.dumps(payloads["snapshot"])),
        ("completed", "run.completed", 20, "run-1", "outcome-v1", json.dumps(payloads["completed"])),
        ("model-result", "agent.model-result", 30, "run-1", "model-v1", json.dumps(payloads["model-result"])),
    ]
    if override is not None:
        rows[override[0]] = override[1]
    return rows


class InternalEvidenceReaderTests(unittest.TestCase):
    def _reader(self, *, parsed=None, cursors=()):
        driver = _Psycopg2(parsed or _dsn(), [_Connection(cursor) for cursor in cursors])
        patches = patch.dict(sys.modules, driver.modules())
        patches.start()
        self.addCleanup(patches.stop)
        return InternalEvidenceReader("postgresql://ignored-by-fake", "tenant-1"), driver

    def test_requires_an_explicit_numeric_loopback_dsn_and_rejects_libpq_indirection(self):
        for parsed in (
                {"host": "127.0.0.1", "port": "5432", "dbname": "db", "user": "u"},
                _dsn("localhost"), _dsn("10.0.0.8"),
                {**_dsn(), "service": "production"}, {**_dsn(), "options": "-c role=admin"},
                {**_dsn(), "hostaddr": "127.0.0.1"}):
            with self.subTest(parsed=parsed), self.assertRaisesRegex(
                    InternalEvidenceError, "internal_evidence_configuration_invalid"):
                self._reader(parsed=parsed)

        reader, _ = self._reader(parsed=_dsn("::1"))
        self.assertEqual("tenant-1", reader.tenant_id)

    def test_driver_absence_is_reported_as_an_explicit_configuration_error(self):
        missing = {"psycopg2": None, "psycopg2.extensions": None}
        with patch.dict(sys.modules, missing):
            with self.assertRaisesRegex(InternalEvidenceError, "internal_evidence_driver_required"):
                InternalEvidenceReader("postgresql://127.0.0.1:5432/db", "tenant-1")

    def test_preflight_uses_read_only_transaction_and_checks_source_table_permission(self):
        cursor = _Cursor()
        reader, driver = self._reader(cursors=[cursor])

        reader.preflight()

        self.assertEqual([{"readonly": True, "isolation_level": "REPEATABLE READ", "autocommit": False}],
                         driver.created_connections[0].sessions)
        self.assertTrue(driver.created_connections[0].closed)
        # 连接已由 reader 关闭；连接参数本身构成只读 preflight 的不可变约束。
        self.assertEqual(1, len(driver.connect_calls))
        call = driver.connect_calls[0]
        self.assertEqual("127.0.0.1", call["host"])
        self.assertEqual("127.0.0.1", call["hostaddr"])
        self.assertEqual(5, call["connect_timeout"])
        self.assertIn("default_transaction_read_only=on", call["options"])
        self.assertIn("statement_timeout=10000", call["options"])
        self.assertEqual([( "SHOW transaction_read_only", None),
                          ("SELECT set_config('app.tenant_id', %s, true)", ("tenant-1",)),
                          ("SELECT event_id FROM public.agent_runtime_session_events WHERE false", None),
                          ("""SELECT r.run_id,a.action_id,t.attempt_id,m.schema_id,m.canonical_json
                    FROM public.agent_runtime_execution_runs r,
                         public.agent_runtime_execution_actions a,
                         public.agent_runtime_execution_attempts t,
                         public.agent_runtime_execution_materials m WHERE false""", None)],
                         cursor.executed)

    def test_preflight_rejects_non_readonly_connection_or_unreadable_source_table(self):
        reader, _ = self._reader(cursors=[_Cursor(one=("off",))])
        with self.assertRaisesRegex(InternalEvidenceError, "internal_evidence_preflight_failed"):
            reader.preflight()

        class FailingCursor(_Cursor):
            def execute(self, statement, parameters=None):
                super().execute(statement, parameters)
                if statement.startswith("SELECT"):
                    raise PermissionError("denied")

        reader, _ = self._reader(cursors=[FailingCursor()])
        with self.assertRaisesRegex(InternalEvidenceError, "internal_evidence_preflight_failed"):
            reader.preflight()

    def test_augment_binds_each_internal_payload_to_public_tenant_session_event_identity(self):
        cursor = _Cursor(rows=_rows())
        reader, _ = self._reader(cursors=[cursor])
        public = _trace()

        augmented = reader.augment(public, "session-1")

        self.assertEqual(("SELECT set_config('app.tenant_id', %s, true)", ("tenant-1",)), cursor.executed[0])
        statement, parameters = cursor.executed[1]
        self.assertIn("tenant_id = %s", statement)
        self.assertIn("session_id = %s", statement)
        self.assertIn("event_id = ANY(%s)", statement)
        self.assertEqual(("tenant-1", "session-1", ["snapshot", "completed", "model-result"]), parameters)
        self.assertEqual(public, _trace())
        self.assertEqual("PUBLIC_ANSWER", augmented["contents"]["session-event:completed"]["value"]
                         ["outcome"]["report"]["output"])
        self.assertEqual(_private_payloads()["snapshot"], augmented["internal_contents"]["session-event:snapshot"]["value"])
        self.assertEqual(digest(_private_payloads()["snapshot"]),
                         augmented["internal_contents"]["session-event:snapshot"]["payload_digest"])
        self.assertEqual("local-postgres-readonly-v1", augmented["internal_evidence"]["mode"])
        self.assertEqual(3, augmented["internal_evidence"]["event_count"])

    def test_scope_setup_failure_does_not_read_events_or_bypass_rls(self):
        class RejectedScope(_Cursor):
            def execute(self, statement, parameters=None):
                super().execute(statement, parameters)
                if "set_config" in statement: raise PermissionError("scope rejected")
        cursor = RejectedScope(rows=_rows())
        reader, driver = self._reader(cursors=[cursor])
        with self.assertRaisesRegex(InternalEvidenceError, "internal_evidence_collection_failed"):
            reader.augment(_trace(), "session-1")
        self.assertEqual(1, len(cursor.executed))
        self.assertEqual("reader", driver.connect_calls[0]["user"])
        self.assertTrue(driver.created_connections[0].closed)

    def test_augment_rejects_missing_duplicate_and_mismatched_source_rows(self):
        variants = [
            _rows()[:-1],
            _rows() + [_rows()[0]],
            _rows(override=(0, ("snapshot", "agent.model-result", 10, "run-1", "context-v1", "{}"))),
            _rows(override=(1, ("completed", "run.completed", 999, "run-1", "outcome-v1", "{}"))),
            _rows(override=(2, ("model-result", "agent.model-result", 30, "wrong-run", "model-v1", "{}"))),
            _rows(override=(0, ("snapshot", "agent.context-snapshot", 10, "run-1", "wrong-schema", "{}"))),
        ]
        for rows in variants:
            with self.subTest(rows=rows):
                reader, _ = self._reader(cursors=[_Cursor(rows=rows)])
                with self.assertRaisesRegex(InternalEvidenceError, "internal_evidence_collection_failed"):
                    reader.augment(_trace(), "session-1")


class InternalEvidenceCaptureTests(unittest.TestCase):
    def test_configure_reads_tenant_from_authenticated_principal_before_preflight(self):
        api = Mock(base="http://127.0.0.1:8000/api/v1")
        api.request.return_value = {"tenant_id": "tenant-from-auth", "id": "user-1"}
        reader = Mock()
        with patch.dict("os.environ", {DSN_ENV: "postgresql://127.0.0.1:5432/db"}, clear=False), \
                patch("behavior_internal_evidence.InternalEvidenceReader", return_value=reader) as factory:
            configure_internal_evidence(api, True)
        api.request.assert_called_once_with("GET", "/auth/me")
        factory.assert_called_once_with("postgresql://127.0.0.1:5432/db", "tenant-from-auth")
        reader.preflight.assert_called_once_with()
        self.assertIs(api.internal_evidence_reader, reader)

    def test_collect_wrapper_augments_only_when_the_explicit_reader_is_configured(self):
        raw = _trace()
        enriched = {**raw, "internal_evidence": {"mode": "local-postgres-readonly-v1"}}
        api = types.SimpleNamespace()
        collector = Mock(return_value=raw)
        self.assertIs(raw, collect_evaluation_trace(api, "session-1", collector=collector, include_content=True))
        collector.assert_called_once_with(api, "session-1", include_content=True)

        api.internal_evidence_reader = Mock(augment=Mock(return_value=enriched))
        collector.reset_mock()
        self.assertIs(enriched, collect_evaluation_trace(api, "session-1", collector=collector, include_content=False))
        collector.assert_called_once_with(api, "session-1", include_content=False)
        api.internal_evidence_reader.augment.assert_called_once_with(raw, "session-1")

    def test_collection_failure_keeps_public_usage_but_marks_internal_evidence_incomplete(self):
        raw = _trace()
        raw["usage"] = {"tokens": 37, "unknown_usage_calls": 0}
        api = types.SimpleNamespace(
            internal_evidence_reader=Mock(augment=Mock(side_effect=InternalEvidenceError("db unavailable"))))
        collector = Mock(return_value=raw)

        captured = collect_evaluation_trace(api, "session-1", collector=collector, include_content=True)
        result = evidence({"id": "B01"}, 1, captured,
                          {"case_key": "case", "calls": [], "write_events": []},
                          {"run_id": "run-1", "status": "completed"}, [])

        self.assertEqual(raw["contents"], captured["contents"])
        self.assertEqual(raw["usage"], captured["usage"])
        self.assertEqual({}, captured["internal_contents"])
        self.assertEqual({"mode": "local-postgres-readonly-v1", "error": "internal_evidence_collection_failed"},
                         captured["internal_evidence"])
        self.assertFalse(result["completeness"]["trace"])
        self.assertIsNone(result["answer"])
        self.assertEqual([], result["model_inputs"])
        self.assertEqual("PUBLIC_ANSWER", result["observable_output"]["answer"])

    def test_evidence_uses_internal_facts_but_observable_output_stays_public(self):
        public = _trace()
        private = _private_payloads()
        public["internal_contents"] = {
            "session-event:" + event_id: {"availability": "FULL", "value": value}
            for event_id, value in private.items()
        }
        public["internal_evidence"] = {"mode": "local-postgres-readonly-v1"}

        result = evidence({"id": "B01"}, 1, public, {"case_key": "case", "calls": [], "write_events": []},
                          {"run_id": "run-1", "status": "completed"}, [], visible=[])

        self.assertEqual({"value": 7}, result["answer"])
        self.assertEqual("PRIVATE_REQUEST", result["model_inputs"][0]["request"])
        self.assertEqual("PUBLIC_ANSWER", result["observable_output"]["answer"])
        self.assertEqual([{"provider": "PUBLIC_MODEL_OUTPUT"}], result["observable_output"]["model_outputs"])
        self.assertNotIn("PRIVATE_REQUEST", json.dumps(result["observable_output"], ensure_ascii=False))
        self.assertTrue(result["completeness"]["trace"])
        self.assertTrue(result["completeness"]["context"])

    def test_missing_or_partial_internal_facts_never_produce_complete_evidence(self):
        public_only = _trace(availability="FULL")
        public_only["internal_evidence"] = {"mode": "local-postgres-readonly-v1"}
        partial = copy.deepcopy(public_only)
        partial["internal_contents"] = {
            "session-event:snapshot": {"availability": "FULL", "value": _private_payloads()["snapshot"]},
            "session-event:completed": {"availability": "FULL", "value": _private_payloads()["completed"]},
        }
        partial["internal_evidence"] = {"mode": "local-postgres-readonly-v1"}
        for trace in (public_only, partial):
            with self.subTest(trace="partial" if trace is partial else "public_only"):
                result = evidence({"id": "B01"}, 1, trace,
                                  {"case_key": "case", "calls": [], "write_events": []},
                                  {"run_id": "run-1", "status": "completed"}, [])
                self.assertFalse(result["completeness"]["trace"])
                self.assertFalse(all(result["completeness"].values()))


class InternalEvidenceRunnerTests(unittest.TestCase):
    @staticmethod
    def _failed_internal_trace(tokens, action_state):
        trace = _trace(availability="FULL")
        trace["usage"] = {"tokens": tokens, "unknown_usage_calls": 0}
        trace["internal_contents"] = {}
        trace["internal_evidence"] = {"mode": "local-postgres-readonly-v1",
                                      "error": "internal_evidence_collection_failed"}
        phases = trace["pages"][0]["rounds"][0]["runs"][0]["steps"][0]["phases"]
        for phase in phases:
            phase["detail"]["currentActionState"] = action_state
        return trace

    def test_internal_collection_error_stops_next_turn_but_settles_public_usage_until_quiet(self):
        plan = make_plan("tests")
        case = next(item for item in plan["cases"] if item["id"] == "B04")
        api = Mock()
        api.request.return_value = {"session_id": "session-1"}
        fixture = Mock()
        fixture.request.return_value = {"case_key": "case", "calls": [], "write_events": [], "complete": True}
        monitor = Mock(check=Mock(return_value=True), reasons=[], request_fingerprints={})
        resources = {"agents": {"behavior": {"id": "agent-1"}},
                     "users": [{"id": str(index)} for index in range(54)],
                     "resolved_model": {"model_id": "test"}}
        observations = [self._failed_internal_trace(5, "RUNNING"),
                        self._failed_internal_trace(6, "RUNNING"),
                        self._failed_internal_trace(7, "SUCCEEDED")]
        budget = ExecutionBudget(100, 10, 60)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch("behavior_runner.chat", return_value={"terminal": {"run_id": "run-1", "status": "completed"}}) as chat, \
                    patch("behavior_runner.collect_evaluation_trace", side_effect=observations) as collect, \
                    patch("behavior_runner.time.sleep") as sleep:
                with self.assertRaises(StopBatch) as stopped:
                    execute_case(api, fixture, plan, case, 1, resources, root, budget, monitor)

            frozen = json.loads((root / "cases/B04/1/evidence.json").read_text(encoding="utf-8"))
            self.assertEqual("invalid_environment", stopped.exception.reason["code"])
            self.assertEqual(3, collect.call_count)
            chat.assert_called_once()
            sleep.assert_called_once_with(0.25)
            self.assertEqual(7, budget.state()["sessions"]["session-1"])
            self.assertEqual(1, budget.state()["turns"])
            self.assertFalse(frozen["completeness"]["trace"])
            self.assertFalse(frozen["completeness"]["context"])
            self.assertIn({"code": "invalid_environment", "detail": "internal_evidence_collection_failed"},
                          frozen["blocking_reasons"])


if __name__ == "__main__":
    unittest.main()
