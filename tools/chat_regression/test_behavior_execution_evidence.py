"""Execution SQL/材料固定夹具，不访问数据库或模型。"""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parent))
from behavior_execution_evidence import collect, project, MODEL_REQUEST, MODEL_INNER, MODEL_OUTCOME, OUTCOME, TOOL_REQUEST
from behavior_eval import evaluate_assertion
from behavior_suite import assertion
from behavior_internal_evidence import InternalEvidenceReader
from test_behavior_internal_evidence import _Cursor, _Connection


def typed(schema, value):
    return {"schemaId": schema, "canonicalJson": json.dumps(value)}


def model(ordinal=1, *, rid="run", messages=None, calls=None, adopted=True):
    if calls is None:
        calls = [{"type": "tool_call", "callId": "call-1", "wireName": "blocked_tool", "argumentsJson": '{"id":1}'}]
    request = {"request": typed(MODEL_INNER, {"messages": messages or []})}
    result = {"callKind": "MODEL_GENERATE", "outcomeType": "Completed",
              "outcome": typed(MODEL_OUTCOME, {"outcomeType": "Completed", "assistantTurn": {"contents": calls}})}
    return (rid, "model-" + str(ordinal), ordinal, "MODEL", "SUCCEEDED", "attempt-" + str(ordinal),
            "attempt-" + str(ordinal), adopted, "SUCCEEDED", MODEL_REQUEST, json.dumps(request), OUTCOME, json.dumps(result))


def refusal():
    return [{"messageId": "execution-assistant-1", "role": "ASSISTANT", "toolCalls": [
                {"callId": "call-1", "wireName": "blocked_tool", "arguments": typed("application/json", {"id": 1})}]},
            {"messageId": "execution-admission-rejected:1:call-1", "role": "TOOL", "toolCallId": "call-1",
             "contents": [{"type": "text", "text": "execution owned rejection"}]}]


def tool(status="SUCCEEDED", *, adopted=True, rid="run"):
    return (rid, "tool-action", 2, "TOOL", status, "tool-attempt", "tool-attempt", adopted, status,
            TOOL_REQUEST, json.dumps({"wireName": "blocked_tool", "callId": "call-1"}), "unused", "{}")


class ExecutionEvidenceTests(unittest.TestCase):
    def snapshot(self, *rows):
        return {"terminal": {"run_id": "run"}, "execution_evidence": project(rows, {"run"}), "completeness": {}}

    def check(self, kind, snapshot, tool_name="blocked_tool"):
        return evaluate_assertion(assertion("e", kind, {"min": 1, "max": 1}, tool=tool_name, requirements=[]), snapshot)

    def test_actual_native_request_and_matching_execution_owned_refusal_with_zero_execution(self):
        captured = self.snapshot(model(), model(2, messages=refusal(), calls=[]))
        self.assertEqual("pass", self.check("tool_attempted", captured)["status"])
        result = self.check("tool_refused", captured)
        self.assertEqual("pass", result["status"])
        self.assertEqual(["execution-admission-rejected:1:call-1"], result["evidence"][0]["receipt_ids"])
        self.assertEqual([], [c for c in captured["execution_evidence"]["runs"]["run"]["calls"] if c["state"] == "succeeded"])
        self.assertEqual("unknown", self.check("execution_failed", captured)["status"])

    def test_no_successor_or_missing_receipt_is_unknown_for_refusal(self):
        for rows in [(model(),), (model(), model(2, calls=[]))]:
            self.assertEqual("unknown", self.check("tool_refused", self.snapshot(*rows))["status"])

    def test_historical_message_identity_cannot_prove_current_refusal(self):
        captured = self.snapshot(model(messages=refusal()), model(2, messages=refusal(), calls=[]))
        self.assertEqual("pass", self.check("tool_attempted", captured)["status"])
        self.assertEqual("unknown", self.check("tool_refused", captured)["status"])

    def test_wrong_call_name_arguments_role_or_action_ordinal_cannot_bind_refusal(self):
        for change in ("name", "arguments", "role", "call_id", "ordinal"):
            messages = refusal()
            if change == "name": messages[0]["toolCalls"][0]["wireName"] = "other"
            if change == "arguments": messages[0]["toolCalls"][0]["arguments"] = typed("application/json", {"id": 2})
            if change == "role": messages[1]["role"] = "USER"
            if change == "call_id": messages[1]["toolCallId"] = "other"
            if change == "ordinal": messages[1]["messageId"] = "execution-admission-rejected:9:call-1"
            with self.subTest(change=change):
                self.assertEqual("unknown", self.check("tool_refused", self.snapshot(model(), model(2, messages=messages, calls=[])))["status"])

    def test_success_failure_and_known_unknown_come_from_adopted_attempts(self):
        for status, kind in [("SUCCEEDED", "execution_succeeded"), ("FAILED", "execution_failed"), ("UNKNOWN", "execution_unknown")]:
            with self.subTest(status=status):
                result = self.check(kind, self.snapshot(model(), tool(status)))
                self.assertEqual("pass", result["status"])
                self.assertEqual("tool-attempt", result["evidence"][0]["attempt_id"])

    def test_late_unadopted_result_does_not_prove_execution_outcome(self):
        captured = self.snapshot(model(), tool(adopted=False))
        self.assertEqual("unknown", self.check("execution_succeeded", captured)["status"])

    def test_unadopted_model_and_missing_native_turn_do_not_prove_requests(self):
        self.assertEqual("unknown", self.check("tool_attempted", self.snapshot(model(adopted=False)))["status"])
        row = list(model())
        value = json.loads(row[-1]); value["outcome"] = typed(MODEL_OUTCOME, {"outcomeType": "Completed", "assistantTurn": None})
        row[-1] = json.dumps(value)
        self.assertEqual("unknown", self.check("tool_attempted", self.snapshot(tuple(row)))["status"])

    def test_other_run_evidence_and_same_tool_name_are_not_current_run_facts(self):
        captured = {"terminal": {"run_id": "current"}, "execution_evidence": project([model(rid="history")], {"history"})}
        self.assertEqual("unknown", self.check("tool_attempted", captured)["status"])

    def test_model_text_is_not_tool_request_and_zero_matches_remain_unknown(self):
        captured = self.snapshot(model(calls=[{"type": "text", "text": "I called blocked_tool and was denied"}]))
        self.assertEqual("unknown", self.check("tool_attempted", captured)["status"])
        self.assertEqual("unknown", self.check("tool_refused", captured)["status"])

    def test_sql_scope_joins_material_through_exact_attempt_and_session(self):
        cursor = _Cursor(rows=[model()])
        projected = collect(cursor, "tenant", "session", {"run"})
        sql, parameters = cursor.executed[0]
        self.assertEqual(("tenant", "session", ["run"]), parameters)
        self.assertIn("t.action_id=a.action_id", sql)
        self.assertIn("q.reference=t.request_ref", sql)
        self.assertEqual("attempted", projected["runs"]["run"]["calls"][0]["state"])

    def test_manifest_collect_batches_leaves_once_and_preserves_message_order(self):
        from behavior_execution_evidence import MODEL_MANIFEST, MODEL_MESSAGE, MODEL_TOOLS
        first = list(model()); first[9] = MODEL_MANIFEST
        first[10] = json.dumps({"request": {}, "messageRefs": ["original"], "toolsRef": "tools"})
        second = list(model(2, calls=[])); second[9] = MODEL_MANIFEST
        second[10] = json.dumps({"request": {}, "messageRefs": ["original", "assistant", "receipt"], "toolsRef": "tools"})
        leaves = [("tools", MODEL_TOOLS, json.dumps({"tools": []})),
                  ("receipt", MODEL_MESSAGE, json.dumps({"message": refusal()[1]})),
                  ("original", MODEL_MESSAGE, json.dumps({"message": {"messageId": "original", "role": "USER"}})),
                  ("assistant", MODEL_MESSAGE, json.dumps({"message": refusal()[0]}))]
        class ManifestCursor(_Cursor):
            def fetchall(self):
                return [tuple(first), tuple(second)] if len(self.executed) == 1 else leaves
        cursor = ManifestCursor()
        projected = collect(cursor, "tenant", "session", {"run"})
        self.assertTrue(projected["runs"]["run"]["calls"][0]["refused"])
        self.assertEqual(2, len(cursor.executed))
        self.assertEqual(("tenant", ["assistant", "original", "receipt", "tools"]), cursor.executed[1][1])
        self.assertIn("tenant_id=%s", cursor.executed[1][0])

    def test_manifest_missing_or_wrong_schema_leaf_fails_without_partial_evidence(self):
        from behavior_execution_evidence import MODEL_MANIFEST, MODEL_MESSAGE, MODEL_TOOLS
        row = list(model()); row[9] = MODEL_MANIFEST
        row[10] = json.dumps({"request": {}, "messageRefs": ["message"], "toolsRef": "tools"})
        with self.assertRaises(ValueError): project([tuple(row)], {"run"}, {})
        with self.assertRaises(ValueError):
            project([tuple(row)], {"run"}, {"message": (MODEL_TOOLS, '{"message":{}}'), "tools": (MODEL_TOOLS, '{"tools":[]}')})

    def test_reader_execution_path_preserves_public_trace_and_closes_readonly_connection(self):
        cursor = _Cursor(rows=[model(), model(2, messages=refusal(), calls=[])])
        connection = _Connection(cursor)
        reader = object.__new__(InternalEvidenceReader)
        reader.tenant_id = "tenant"
        reader._connection = lambda: connection
        original = {"pages": [], "contents": {"safe": {"value": "public"}}}
        result = reader.augment_execution(original, "session", {"run"})
        self.assertEqual(original["contents"], result["contents"])
        self.assertNotIn("execution_evidence", original)
        self.assertTrue(connection.closed)
        self.assertNotIn("execution owned rejection", json.dumps(result["execution_evidence"]))
        self.assertNotIn("argumentsJson", json.dumps(result["execution_evidence"]))

    def test_execution_api_event_routes_to_execution_materials_without_legacy_event_query(self):
        from test_behavior_internal_evidence import _trace
        trace = _trace()
        for phase in trace["pages"][0]["rounds"][0]["runs"][0]["steps"][0]["phases"]:
            phase["event_type"] = "execution.model-result"
        cursor = _Cursor(rows=[model(rid="run-1")])
        reader = object.__new__(InternalEvidenceReader)
        reader.tenant_id = "tenant"
        reader._connection = lambda: _Connection(cursor)
        result = reader.augment(trace, "session")
        self.assertIn("run-1", result["execution_evidence"]["runs"])
        self.assertFalse(any("agent_runtime_session_events" in sql for sql, _ in cursor.executed))

    def test_conflicting_or_wrong_schema_material_is_rejected(self):
        row = list(model()); row[9] = "wrong-schema"
        with self.assertRaises(ValueError): project([tuple(row)], {"run"})
        with self.assertRaises(ValueError): project([model(rid="foreign")], {"run"})


if __name__ == "__main__":
    unittest.main()
