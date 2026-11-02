"""Product 高层事实只接受真实事件身份与顺序的离线契约测试。"""
from __future__ import annotations

import json
import hashlib
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parent))

from behavior_product_facts import derive_facts


def event(identity, kind, sequence, payload, *, detail=None, run_id="run:one"):
    return {"event_id": identity, "event_type": kind, "occurred_at": f"2026-09-29T00:00:{sequence:02d}Z",
            "payload": payload, "detail": {"sessionSequence": sequence, **(detail or {})}, "run_id": run_id}


def trace(*rows):
    runs, contents = {}, {}
    for row in rows:
        ref = "session-event:" + row["event_id"]
        phases = runs.setdefault(row["run_id"], [])
        phases.append({key: row[key] for key in ("event_id", "event_type", "occurred_at", "detail")} |
                      {"content_ref": ref})
        contents[ref] = {"availability": "FULL", "value": row["payload"]}
    return {"pages": [{"rounds": [{"runs": [{"run_id": run_id,
             "steps": [{"action_id": None, "phases": phases}]} for run_id, phases in runs.items()]}]}],
            "contents": contents, "internal_contents": contents,
            "internal_evidence": {"mode": "local-postgres-readonly-v1"}}


def tool_payload(handle, output=None):
    envelope = None if output is None else {"schemaId": "skillforge.runtime.tool-result.v1",
        "canonicalJson": json.dumps({"content": [], "structuredContent": output,
                                      "isError": False, "resultType": "complete"}, ensure_ascii=False)}
    return {"toolHandle": handle, "status": "SUCCEEDED", "output": envelope}


def resources_for(category):
    profile = {"D04": "d04", "D05": "d05", "D07": "d07", "D08": "d08",
               "D10": "d10", "D11": "d11", "D12": "d12"}[category]
    value = {"profile": profile, "agent_id": "agent-" + category,
             "user_id": "user-" + category}
    resources = {"cases": {category: value}, "agents": {profile: {"mounts": {}}}}
    return resources, value


class FakeApi:
    def __init__(self, sessions):
        self.sessions = sessions
        self.calls = []

    def request(self, method, path):
        self.calls.append((method, path))
        return self.sessions


class ProductFactTest(unittest.TestCase):
    def test_knowledge_requires_cited_item_from_the_published_document(self):
        resources, value = resources_for("D04")
        resources["knowledge"] = {"kb_id": 230, "capability_definition_id": 231}
        value["knowledge"] = {"handle": "knowledge:k1", "documents": {
            "rules": {"document_id": 202, "source_version": "source-v1"}}}
        observation = event("knowledge-1", "agent.knowledge-observation", 1, {
            "knowledgeHandle": "knowledge:k1", "injectedItemIds": ["item-1"],
            "bundle": {"items": [{"itemId": "item-1", "sourceId": "230",
                "sourceVersion": "source-v1", "provenanceRefs": ["knowledge-document:202"]}]}})
        decision = event("model-1", "agent.model-result", 2, {"output": {
            "canonicalJson": json.dumps({"type": "respond", "output": "answer",
                                         "evidence_item_ids": ["item-1"]})}})

        facts = derive_facts({"id": "D04"}, resources, {}, trace(observation, decision), {})

        self.assertEqual([{"source_verified": True, "event_id": "knowledge-1"}], facts["citations"])
        self.assertEqual("knowledge_search", facts["tools"][0]["name"])
        self.assertEqual("SUCCEEDED", facts["tools"][0]["status"])

        item = observation["payload"]["bundle"]["items"][0]
        for field, wrong in (("sourceId", "231"), ("sourceId", "foreign-kb"),
                             ("sourceVersion", "other-version"),
                             ("provenanceRefs", ["knowledge-document:999"])):
            original = item[field]
            item[field] = wrong
            rejected = derive_facts({"id": "D04"}, resources, {}, trace(observation, decision), {})
            self.assertFalse(rejected["citations"][0]["source_verified"])
            item[field] = original
        del resources["knowledge"]["kb_id"]
        unknown = derive_facts({"id": "D04"}, resources, {}, trace(observation, decision), {})
        self.assertEqual([], unknown["citations"])
        resources["knowledge"]["kb_id"] = 230

        decision["payload"]["output"]["canonicalJson"] = json.dumps(
            {"type": "respond", "output": "answer", "evidence_item_ids": []})
        missing = derive_facts({"id": "D04"}, resources, {}, trace(observation, decision), {})
        self.assertFalse(missing["citations"][0]["source_verified"])

    def test_d08_attachment_evidence_binds_two_hashes_and_numbers_to_one_code_result(self):
        resources, value = resources_for("D08")
        ids = {"orders": "11111111-1111-1111-1111-111111111111",
               "ledger": "22222222-2222-2222-2222-222222222222"}
        contents = {"orders": "id,total\nA,100\nC,60", "ledger": "id,total\nA,100\nC,50"}
        bindings = [{"ref": ref, "kind": "csv", "path": f"product-fixtures/d08/{ref}.csv",
                     "size": len(content.encode()), "sha256": hashlib.sha256(content.encode()).hexdigest()}
                    for ref, content in contents.items()]
        value["material_bindings"] = bindings
        value["attachment_transport"] = {"refs": ["orders", "ledger"]}
        resources["agents"]["d08"]["mounts"] = {
            "tool:platform_tools:code_execute": {"mount_id": "code"},
        }
        metadata = {ref: {"id": ids[ref], "name": f"{ref}.csv",
                          "input_path": f"attachments/{ids[ref]}/input.csv",
                          "size": binding["size"], "sha256": binding["sha256"],
                          "expires_at": "2099-01-01T00:00:00Z"}
                    for ref, binding in zip(("orders", "ledger"), bindings)}
        state = {"attachment_uploads": {ref: {"upload_id": ids[ref], "status": "ready",
                                               "metadata": metadata[ref]}
                                        for ref in ids},
                 "turns": {"0": {"result": {"terminal": {"run_id": "run:one"}}}}}
        case = {"id": "D08", "expected": {"unique_total": 160, "difference": 10}}
        output = {"success": True, "stdout": "unique_total=160\ndifference=10",
                  "attachment_inputs": [metadata["orders"], metadata["ledger"]]}
        code = event("calculation", "agent.tool-result", 1, tool_payload("tool:code", output),
                     detail={"arguments": {"attachment_ids": [ids["orders"], ids["ledger"]]}})

        facts = derive_facts(case, resources, state, trace(code), {})

        self.assertTrue(facts["attachment_calculation_verified"])
        self.assertEqual("calculation", facts["attachment_calculation_event_id"])
        self.assertEqual({"orders", "ledger"}, {row["ref"] for row in facts["attachment_inputs"]
                                                  if row["verified"] is True})
        self.assertEqual({binding["sha256"] for binding in bindings},
                         {row["sha256"] for row in facts["attachment_inputs"]})

        output["attachment_inputs"][0] = dict(output["attachment_inputs"][0], sha256="0" * 64)
        code["payload"] = tool_payload("tool:code", output)
        self.assertFalse(derive_facts(case, resources, state, trace(code), {})["attachment_calculation_verified"])
        output["attachment_inputs"] = [metadata["orders"]]
        code["payload"] = tool_payload("tool:code", output)
        self.assertFalse(derive_facts(case, resources, state, trace(code), {})["attachment_calculation_verified"])
        output["attachment_inputs"] = [metadata["orders"], metadata["ledger"]]
        code["payload"] = tool_payload("tool:code", output)
        code["detail"]["arguments"]["input_files"] = {"orders.csv": contents["orders"]}
        self.assertFalse(derive_facts(case, resources, state, trace(code), {})["attachment_calculation_verified"])
        code["detail"]["arguments"].pop("input_files")
        code["detail"]["arguments"]["attachment_ids"] = [ids["orders"],
                                                            "33333333-3333-3333-3333-333333333333"]
        self.assertFalse(derive_facts(case, resources, state, trace(code), {})["attachment_calculation_verified"])
        code["detail"]["arguments"]["attachment_ids"] = [{"forged": ids["orders"]}, ids["ledger"]]
        output["attachment_inputs"] = [dict(metadata["orders"], id={"forged": ids["orders"]}),
                                       metadata["ledger"]]
        code["payload"] = tool_payload("tool:code", output)
        self.assertFalse(derive_facts(case, resources, state, trace(code), {})["attachment_calculation_verified"])

    def test_two_skill_identities_and_read_only_user_isolation(self):
        resources, value = resources_for("D07")
        resources["agents"]["d07"]["mounts"] = {
            "skill:d07-skills:collect": {"mount_id": "skill-collect"},
            "skill:d07-skills:summarize": {"mount_id": "skill-summarize"},
        }
        resources["cases"]["D01"] = {"agent_id": "another-agent", "user_id": "another-user"}
        first = event("skill-1", "skill.activation", 1, {"results": [
            {"handle": "skill-collect", "status": "ACTIVATED"}]})
        second = event("skill-2", "skill.activation", 2, {"results": [
            {"handle": "skill-summarize", "status": "ACTIVATED"}]})
        api = FakeApi([{"sessionId": "session-one"}])

        facts = derive_facts({"id": "D07"}, resources, {"session_id": "session-one"},
                             trace(first, second), {}, api=api)

        self.assertEqual({"skill-collect", "skill-summarize"},
                         {row["handle"] for row in facts["skill_identities"]})
        self.assertTrue(facts["isolation"]["verified"])
        self.assertEqual("GET", api.calls[0][0])

        unknown = derive_facts({"id": "D07"}, resources, {"session_id": "session-one"},
                               trace(first, second), {}, api=None)
        self.assertIsNone(unknown["isolation"]["verified"])

    def test_workflow_effect_is_one_terminal_execution_after_hitl(self):
        resources, value = resources_for("D10")
        value["workflow"] = {"handle": "workflow:w1"}
        suspended = event("suspended", "run.suspended", 1, {
            "outcome": {"reason": "WORKFLOW_HITL"}})
        workflow = event("workflow-result", "agent.workflow-result", 2, {
            "workflowHandle": "workflow:w1", "status": "COMPLETED",
            "executionRef": {"executionId": "workflow-execution:one"}})
        state = {"turns": {
            "0": {"result": {"terminal": {"status": "waiting_hitl", "run_id": "run:one"}}},
            "1": {"result": {"terminal": {"status": "completed", "run_id": "run:one"}}},
        }}

        facts = derive_facts({"id": "D10"}, resources, state, trace(suspended, workflow), {})

        self.assertTrue(facts["workflow_effect_once"])
        self.assertEqual({"suspended", "workflow-result"}, set(facts["workflow_refs"]))

    def test_delegation_proves_two_owned_results_and_parent_wait(self):
        resources, value = resources_for("D11")
        value["team"] = {"child_agent_ids": ["child-a", "child-b"]}
        start_a = event("start-a", "agent.delegation", 1, {
            "command": json.dumps({"operation": "start", "target_handle": "member:child-a"}),
            "result": {"status": "accepted", "delegation_id": "delegation-a"}, "wait_id": ""})
        start_b = event("start-b", "agent.delegation", 2, {
            "command": json.dumps({"operation": "start", "target_handle": "member:child-b"}),
            "result": {"status": "accepted", "delegation_id": "delegation-b"}, "wait_id": ""})
        wait = event("wait", "agent.delegation", 3, {
            "command": json.dumps({"operation": "wait", "delegation_ids": ["delegation-a", "delegation-b"]}),
            "result": {"status": "waiting"}, "wait_id": "delegation-wait:one"})
        suspended = event("suspended", "run.suspended", 4, {"outcome": {"reason": "DELEGATED"}})
        result = event("results", "agent.delegation", 5, {
            "command": json.dumps({"operation": "wait", "delegation_ids": ["delegation-a", "delegation-b"]}),
            "result": {"results": [
                {"delegation_id": "delegation-a", "state": "completed"},
                {"delegation_id": "delegation-b", "state": "completed"}]}, "wait_id": ""})
        completed = event("completed", "run.completed", 6, {})

        facts = derive_facts({"id": "D11"}, resources, {},
                             trace(start_a, start_b, wait, suspended, result, completed), {})["delegation"]

        self.assertTrue(facts["two_owned_results"])
        self.assertTrue(facts["parent_waited"])

        # 受理回执和持久化等待都不是子任务完成证据。
        accepted = derive_facts({"id": "D11"}, resources, {},
                               trace(start_a, start_b, wait, suspended), {})["delegation"]
        self.assertIsNone(accepted["two_owned_results"])
        self.assertIsNone(accepted["parent_waited"])
        for child_state in ("accepted", "waiting", "running", "failed"):
            with self.subTest(child_state=child_state):
                result["payload"]["result"]["results"][1]["state"] = child_state
                incomplete = derive_facts({"id": "D11"}, resources, {},
                                         trace(start_a, start_b, wait, suspended, result, completed), {})["delegation"]
                self.assertFalse(incomplete["two_owned_results"])
        result["payload"]["result"]["results"][1]["state"] = "completed"

        # 顺序相符的其它 Run 事件也不能证明父任务已等待并完成。
        for foreign in (suspended, completed):
            with self.subTest(foreign_event=foreign["event_id"]):
                foreign["run_id"] = "run:foreign"
                mixed = derive_facts({"id": "D11"}, resources, {},
                                    trace(start_a, start_b, wait, suspended, result, completed), {})["delegation"]
                self.assertIsNone(mixed["parent_waited"])
                foreign["run_id"] = "run:one"

        # 终态身份明确时，另一 Run 的结果和完成不能补齐当前父任务的缺失事实。
        state = {"turns": {"0": {"result": {"terminal": {"run_id": "run:one"}}}}}
        result["run_id"] = completed["run_id"] = "run:foreign"
        mixed = derive_facts({"id": "D11"}, resources, state,
                            trace(start_a, start_b, wait, suspended, result, completed), {})["delegation"]
        self.assertIsNone(mixed["two_owned_results"])
        self.assertIsNone(mixed["parent_waited"])
        ambiguous = derive_facts({"id": "D11"}, resources, {},
                                trace(start_a, start_b, wait, suspended, result, completed), {})["delegation"]
        self.assertIsNone(ambiguous["two_owned_results"])
        self.assertIsNone(ambiguous["parent_waited"])

    def test_cancellation_counts_real_post_cancel_claims_and_detects_no_reexecution(self):
        resources, _ = resources_for("D12")
        claimed = event("claim", "action.claimed", 1, {}, detail={
            "callKind": "TOOL_INVOKE", "invocationId": "invocation-1",
            "attempts": [{"authorization": "EXECUTE", "attemptId": "attempt-1"}]})
        requested = event("cancel", "run.cancel_requested", 2, {"cancelRequestId": "cancel-1"})
        cancelled = event("cancelled", "run.cancelled", 3, {})
        state = {"turns": {"0": {"result": {
            "terminal": {"status": "cancelled", "run_id": "run:one"},
            "deliberate_disconnect": True}}}}

        facts = derive_facts({"id": "D12"}, resources, state,
                             trace(claimed, requested, cancelled), {})

        self.assertTrue(facts["cancellation"]["cancel_persisted"])
        self.assertEqual(0, facts["cancellation"]["post_cancel_dispatches"])
        self.assertTrue(facts["no_reexecution_after_reconnect"])

        repeated = event("claim-again", "action.claimed", 4, {}, detail={
            "authorization": "EXECUTE",
            "callKind": "TOOL_INVOKE", "invocationId": "invocation-1",
            "attempts": [{"authorization": "EXECUTE", "attemptId": "attempt-2"}]})
        failed = derive_facts({"id": "D12"}, resources, state,
                              trace(claimed, requested, cancelled, repeated), {})
        self.assertEqual(1, failed["cancellation"]["post_cancel_dispatches"])
        self.assertFalse(failed["no_reexecution_after_reconnect"])

        repeated["detail"]["authorization"] = "FINALIZE_KNOWN"
        repeated["detail"]["attempts"][0]["attemptId"] = "attempt-1"
        finalized = derive_facts({"id": "D12"}, resources, state,
                                 trace(claimed, requested, cancelled, repeated), {})
        self.assertEqual(0, finalized["cancellation"]["post_cancel_dispatches"])

    def test_missing_trace_never_uses_final_answer_as_execution_proof(self):
        resources, value = resources_for("D10")
        value["workflow"] = {"handle": "workflow:w1"}
        case = {"id": "D10", "candidate": {"reference_answer": "workflow succeeded"}}

        facts = derive_facts(case, resources, {}, {"pages": []}, {})

        self.assertIsNone(facts["workflow_effect_once"])
        self.assertEqual([], facts["workflow_refs"])


if __name__ == "__main__":
    unittest.main()
