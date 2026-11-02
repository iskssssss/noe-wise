"""Consumer negative controls for genuine config drift versus exact Run/Native variations."""
import copy
import json
from types import SimpleNamespace
import unittest

from behavior_contract import digest
from behavior_model_contract import validate_model_requests


EXPECTED = {"model_id": "model-1", "max_output_tokens": 4096, "temperature": 0.2, "reasoning": "DISABLED"}


def snapshot(run="run:a", operation="a", closing=False):
    handle = "tool:mount-1:input:" + run
    schema = {"schemaId": "application/json", "canonicalJson": '{"type":"object","properties":{"value":{"type":"integer"}}}'}
    tool = {"toolHandle": handle, "wireName": "sf_tool_m1", "description": "real published tool",
            "kind": "TOOL_CALL", "strict": True, "inputSchema": schema}
    request = {"target": {"effectiveModelId": "model-1", "binding": {"tenantId": "tenant-1", "bindingId": "binding-1", "revision": "1"},
                           "resolutionRef": "model-resolution:fact1", "timeout": "PT120S", "capabilities": {
                               "capabilityFactRef": "model-capability:fact1", "prefixCapabilities": {"retainToolsWithToolChoiceNone": "SUPPORTED"}}},
               "generation": {"maxOutputTokens": "4096", "temperature": "0.2"}, "reasoning": "DISABLED",
               "requestedStrategy": {"strategyId": "chat", "strategyVersion": "v1"},
               "tools": [tool], "toolChoice": "AUTO",
               "response": {"format": {"kind": "NATIVE_CONVERSATION", "schema": None}, "enforcement": "STRICT",
                            "negotiationReasonCode": "native_agent_conversation"}}
    payload = {"modelRequest": request, "contextScope": "AGENT_INPUT", "evidenceRefs": [],
               "operation": {"runIdentity": {"runRef": {"runId": run, "tenantId": "tenant-1"}, "owner": {"sessionRef": {"tenantId": "tenant-1"}}}},
               "viewVersion": {"policyRef": "profile-1", "policyVersion": "v1"}}
    def event(prefix, kind, value):
        return {"event_id": prefix + ":" + operation, "event_type": kind, "run_id": run, "availability": "FULL", "payload": value}
    context = event("model-context-snapshot", "agent.context-snapshot", payload)
    events = [context, event("preparation-controls", "agent.preparation-controls", {"identities": {handle: "tool:mount-1"},
              "schemas": {handle: {"inputSchema": copy.deepcopy(schema), "outputSchema": copy.deepcopy(schema), "bindingDigest": "opaque-run-bound"}},
              "targetDigests": {handle: "f" * 64}}),
              event("preparation-instructions", "agent.preparation-instructions", {"profileRef": "profile-1", "profileVersion": "v1",
                    }),
              event("preparation-budget", "agent.preparation-budget", {"phase": "CLOSING" if closing else "BUSINESS", "toolHandles": [handle]})]
    value = {"events": events, "model_inputs": [{"event_id": context["event_id"], "request": request}], "blocking_reasons": []}
    refresh(value)
    return value


def refresh(value):
    value["model_inputs"][0]["model_input_hash"] = digest(value["events"][0]["payload"])


class ModelContractTest(unittest.TestCase):
    def setUp(self):
        self.monitor = SimpleNamespace(request_fingerprints={})

    def check(self, value, expected=None):
        refresh(value)
        validate_model_requests(value, "behavior", expected or EXPECTED, self.monitor)
        return value["blocking_reasons"]

    def test_owner_proven_run_suffix_changes_only_static_mount_is_compared(self):
        self.assertEqual([], self.check(snapshot()))
        self.assertEqual([], self.check(snapshot("run:b", "b")))

    def test_first_request_must_use_actual_agent_and_published_model_revision(self):
        value = snapshot()
        value["events"][0]["payload"]["operation"]["runIdentity"]["agentId"] = "agent-1"
        expected = {**EXPECTED, "id": "config-1", "revision": 7, "published_revision": 6}
        value["model_inputs"][0]["request"]["target"]["binding"]["bindingId"] = "skillforge.host.chat.agent-agent-1.model-config-1-6.timeout-120"
        refresh(value)
        validate_model_requests(value, "behavior", expected, self.monitor, "agent-1")
        self.assertEqual([], value["blocking_reasons"])
        for agent, revision in (("other-agent", 6), ("agent-1", 7)):
            wrong = copy.deepcopy(value)
            wrong["blocking_reasons"] = []
            wrong["model_inputs"][0]["request"]["target"]["binding"]["bindingId"] = f"skillforge.host.chat.agent-agent-1.model-config-1-{revision}.timeout-120"
            refresh(wrong)
            validate_model_requests(wrong, "behavior", expected, self.monitor, agent)
            self.assertEqual("invalid_config_drift", wrong["blocking_reasons"][0]["code"])

    def test_wrong_run_suffix_and_owner_are_rejected(self):
        for field in ("handle", "tenant", "run"):
            with self.subTest(field=field):
                value = snapshot()
                if field == "handle": value["model_inputs"][0]["request"]["tools"][0]["toolHandle"] += ":untrusted"
                if field == "tenant": value["model_inputs"][0]["request"]["target"]["binding"]["tenantId"] = "other-tenant"
                if field == "run": value["events"][0]["payload"]["operation"]["runIdentity"]["runRef"]["runId"] = "run:other"
                self.assertEqual("invalid_config_drift", self.check(value)[0]["code"])

    def test_model_revision_security_target_and_schema_drift_remain_rejected(self):
        for mutate in (lambda q: q["target"]["binding"].update(revision="2"),
                       lambda q: q["target"].update(resolutionRef="other-resolution"),
                       lambda q: q["target"]["capabilities"].update(capabilityFactRef="other-fact"),
                       lambda q: q["tools"][0].update(wireName="other-name"),
                       lambda q: q["tools"][0].update(description="changed"),
                       lambda q: q["tools"][0]["inputSchema"].update(canonicalJson='{"type":"string"}')):
            with self.subTest(mutate=mutate):
                self.monitor.request_fingerprints = {}
                self.check(snapshot())
                value = snapshot("run:b", "b")
                mutate(value["model_inputs"][0]["request"])
                self.assertTrue(self.check(value))

    def test_catalog_schema_change_during_closing_is_rejected(self):
        self.check(snapshot())
        value = snapshot("run:b", "b", closing=True)
        schema = next(iter(value["events"][1]["payload"]["schemas"].values()))
        schema["outputSchema"]["canonicalJson"] = '{"type":"string"}'
        self.assertEqual("frozen_model_request_changed", self.check(value)[0]["detail"])

    def test_actual_output_limit_can_decrease_with_frozen_model_cap(self):
        self.check(snapshot())
        value = snapshot("run:b", "b")
        value["model_inputs"][0]["request"]["generation"]["maxOutputTokens"] = "23"
        self.assertEqual([], self.check(value))
        receipt = value["model_request_validation"][0]
        self.assertEqual((23, 4096, False), (receipt["output_limit"], receipt["configured_output_cap"], receipt["exact_remaining_quota_observed"]))

    def test_output_cap_overflow_temperature_reasoning_and_configured_cap_change_rejected(self):
        for mutation in ("overflow", "zero", "temperature", "reasoning", "configured"):
            with self.subTest(mutation=mutation):
                self.monitor.request_fingerprints = {}
                self.check(snapshot())
                value, expected = snapshot("run:b", "b"), dict(EXPECTED)
                request = value["model_inputs"][0]["request"]
                if mutation == "overflow": request["generation"]["maxOutputTokens"] = "4097"
                if mutation == "zero": request["generation"]["maxOutputTokens"] = "0"
                if mutation == "temperature": request["generation"]["temperature"] = "0.3"
                if mutation == "reasoning": request["reasoning"] = "ENABLED"
                if mutation == "configured": expected["max_output_tokens"] = 8192
                self.assertTrue(self.check(value, expected))

    def test_native_tool_choice_uses_auto_or_none_and_rejects_old_format(self):
        for mutation in ("none_with_tools", "required", "old_phase", "old_format"):
            with self.subTest(mutation=mutation):
                self.monitor.request_fingerprints = {}
                value = snapshot()
                request = value["model_inputs"][0]["request"]
                if mutation == "none_with_tools": request["toolChoice"] = "NONE"
                if mutation == "required": request["toolChoice"] = "REQUIRED"
                if mutation == "old_phase": request["response"]["negotiationReasonCode"] = "native_final_answer_admitted"
                if mutation == "old_format": request["response"]["format"]["kind"] = "NATIVE_DECISION"
                self.assertTrue(self.check(value))
        self.monitor.request_fingerprints = {}
        no_tools = snapshot()
        no_tools["model_inputs"][0]["request"].update(tools=[], toolChoice="NONE")
        self.assertEqual([], self.check(no_tools))

    def test_closing_can_answer_without_tools_and_preserves_knowledge_evidence_refs(self):
        self.check(snapshot())
        value = snapshot("run:a", "b", closing=True)
        value["events"][0]["payload"]["evidenceRefs"] = [{"itemId": "knowledge-1"}]
        value["model_inputs"][0]["request"].update(tools=[], toolChoice="NONE")
        self.assertEqual([], self.check(value))
        self.assertEqual([{"itemId": "knowledge-1"}], value["events"][0]["payload"]["evidenceRefs"])

    def test_terminal_tools_are_forbidden_even_when_declared_with_valid_action_identity(self):
        for name in ("final_answer", "prepare_answer"):
            with self.subTest(name=name):
                value = snapshot()
                value["model_inputs"][0]["request"]["tools"].append({
                    "toolHandle": "runtime:agent-action:" + name, "wireName": name,
                    "description": "obsolete terminal tool", "kind": "AGENT_ACTION", "strict": True,
                    "inputSchema": {"schemaId": "application/json", "canonicalJson": '{"type":"object"}'}})
                self.assertTrue(self.check(value))

    def test_missing_or_duplicate_preparation_is_unknown_not_pass(self):
        for duplicate in (False, True):
            value = snapshot()
            if duplicate: value["events"].append(copy.deepcopy(value["events"][1]))
            else: value["events"].pop(1)
            self.assertEqual("incomplete", self.check(value)[0]["code"])

    def test_closing_accepts_only_proven_tool_handles_with_identical_descriptor(self):
        self.check(snapshot())
        self.assertEqual([], self.check(snapshot("run:a", "b", closing=True)))
        value = snapshot("run:a", "c", closing=True)
        value["events"][3]["payload"]["toolHandles"] = []
        self.assertTrue(self.check(value))


if __name__ == "__main__":
    unittest.main()
