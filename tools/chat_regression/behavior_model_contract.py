"""Compare static model facts while proving the narrow Native Run/phase variations.

Sources: JdbcSessionCapabilities.toolHandle, SessionPreparationControls,
SessionPreparationMaterializer.generation/modelTools and SessionFinalAnswerAdmission.
No request or frozen verdict is rewritten; this produces validation evidence only.
"""
from __future__ import annotations

import copy
import json
import re

from behavior_contract import digest, issue, require


def natural(value):
    require(type(value) is int and value >= 0 or isinstance(value, str) and re.fullmatch(r"0|[1-9][0-9]*", value),
            "model_request_numeric_fact_invalid")
    return int(value)


def source(snapshot, event_id, kind, run_id=None):
    rows = [e for e in snapshot["events"] if e.get("event_id") == event_id]
    require(len(rows) == 1 and rows[0].get("event_type") == kind
            and rows[0].get("availability") == "FULL" and isinstance(rows[0].get("payload"), dict),
            "model_request_preparation_source_missing")
    if run_id is not None:
        require(rows[0].get("run_id") == run_id, "model_request_preparation_run_mismatch")
    return rows[0]


def _stable_identity(handle, identity, run_id):
    require(isinstance(handle, str) and isinstance(identity, str), "model_request_tool_identity_invalid")
    if handle.startswith("tool:host-output:"):
        require(handle == identity, "model_request_tool_identity_invalid")
    else:
        # Owner-frozen mapping supplies the mount identity. Do not parse an opaque caller handle.
        require(identity.startswith("tool:") and ":input:" not in identity
                and handle == identity + ":input:" + run_id, "model_request_tool_run_mismatch")
    return identity


def _canonical_tool(tool):
    result = copy.deepcopy(tool)
    schema = result.get("inputSchema")
    if isinstance(schema, dict) and isinstance(schema.get("canonicalJson"), str):
        schema["canonicalJson"] = json.dumps(json.loads(schema["canonicalJson"]), ensure_ascii=False,
                                             sort_keys=True, separators=(",", ":"))
    return result


def contract_facts(snapshot, entry, expected, expected_agent=None):
    context = source(snapshot, entry["event_id"], "agent.context-snapshot")
    payload, request = context["payload"], entry.get("request")
    require(isinstance(request, dict) and request == payload.get("modelRequest")
            and entry.get("model_input_hash") == digest(payload), "model_request_context_identity_mismatch")
    run_id = context.get("run_id")
    identity = payload["operation"]["runIdentity"]
    require(isinstance(run_id, str) and run_id.startswith("run:")
            and identity["runRef"]["runId"] == run_id, "model_request_context_run_mismatch")
    if expected_agent is not None:
        require(identity.get("agentId") == str(expected_agent), "model_request_actual_agent_mismatch")
    target = request["target"]
    require(target["binding"]["tenantId"] == identity["runRef"]["tenantId"]
            == identity["owner"]["sessionRef"]["tenantId"], "model_request_target_owner_mismatch")
    require(target.get("effectiveModelId") == expected.get("model_id"), "effective_model_mismatch")
    if expected_agent is not None and expected.get("id") is not None:
        revision = expected.get("published_revision") or expected["revision"]
        binding_prefix = "skillforge.host.chat.agent-" + str(expected_agent) + ".model-" + str(expected["id"]) + "-" + str(revision) + ".timeout-"
        require(target["binding"]["bindingId"].startswith(binding_prefix)
                and re.fullmatch(r"[1-9][0-9]*", target["binding"]["bindingId"][len(binding_prefix):]),
                "model_request_published_model_revision_mismatch")
    suffix = entry["event_id"].removeprefix("model-context-snapshot:")
    require(entry["event_id"] == "model-context-snapshot:" + suffix and bool(suffix),
            "model_request_operation_identity_invalid")
    controls = source(snapshot, "preparation-controls:" + suffix, "agent.preparation-controls", run_id)["payload"]
    instructions = source(snapshot, "preparation-instructions:" + suffix, "agent.preparation-instructions", run_id)["payload"]
    budget = source(snapshot, "preparation-budget:" + suffix, "agent.preparation-budget", run_id)["payload"]
    identities, schemas, targets = controls["identities"], controls["schemas"], controls["targetDigests"]
    require(set(identities) == set(schemas) == set(targets), "model_request_tool_catalog_identity_invalid")
    catalog, normalized = {}, []
    for handle, stable in identities.items():
        _stable_identity(handle, stable, run_id)
        require(stable not in catalog and isinstance(targets[handle], str) and len(targets[handle]) == 64,
                "model_request_tool_catalog_identity_invalid")
        catalog[stable] = {k: schemas[handle][k] for k in ("inputSchema", "outputSchema")}
    for tool in request["tools"]:
        value = _canonical_tool(tool)
        handle = value["toolHandle"]
        if handle.startswith("runtime:agent-action:"):
            require(value.get("kind") == "AGENT_ACTION" and handle == "runtime:agent-action:" + value["wireName"],
                    "model_request_action_kind_invalid")
            require(value["wireName"] not in {"prepare_answer", "final_answer"}, "model_request_action_kind_invalid")
        else:
            require(handle in identities, "model_request_tool_catalog_identity_invalid")
            value["toolHandle"] = _stable_identity(handle, identities[handle], run_id)
        require(not any(row["toolHandle"] == value["toolHandle"] for row in normalized),
                "model_request_tool_catalog_identity_invalid")
        normalized.append(value)
    generation = request["generation"]
    cap, actual = natural(expected["max_output_tokens"]), natural(generation["maxOutputTokens"])
    require(cap > 0 and 0 < actual <= cap, "model_request_output_cap_invalid")
    # The host profile comes from this immutable published model revision; remaining Run quota
    # may lower its per-call max. The evaluator validates the bound, not an unobserved exact quota.
    require(generation.get("temperature") == (str(expected["temperature"]) if expected.get("temperature") is not None else None),
            "model_request_temperature_changed")
    require(request["reasoning"] == expected["reasoning"], "model_request_reasoning_changed")
    response, choice = request["response"], request["toolChoice"]
    native = payload.get("contextScope") == "AGENT_INPUT" and response["format"]["kind"] == "NATIVE_CONVERSATION"
    require(native and response.get("negotiationReasonCode") == "native_agent_conversation"
            and choice == ("AUTO" if normalized else "NONE"), "model_request_phase_invalid")
    if budget["phase"] == "CLOSING":
        require(all(handle in identities for handle in budget["toolHandles"]), "model_request_closing_catalog_identity_invalid")
    static_response = {k: v for k, v in response.items() if k != "negotiationReasonCode"}
    static = {"target": target, "requestedStrategy": request["requestedStrategy"],
              "reasoning": request["reasoning"], "temperature": generation.get("temperature"),
              "configuredMaxOutputTokens": cap, "response": static_response,
              "catalog": catalog, "viewVersion": payload["viewVersion"],
              "profileRef": instructions["profileRef"], "profileVersion": instructions["profileVersion"]}
    return static, normalized, {"event_id": entry["event_id"], "run_id": run_id,
                               "closing": budget["phase"] == "CLOSING", "closing_handles": budget["toolHandles"],
                               "output_limit": actual, "configured_output_cap": cap,
                               "exact_remaining_quota_observed": False}


def validate_model_requests(snapshot, profile, expected, monitor, expected_agent=None):
    fingerprints = getattr(monitor, "request_fingerprints", {})
    receipts = snapshot.setdefault("model_request_validation", [])
    for entry in snapshot.get("model_inputs", []):
        try:
            static, tools, receipt = contract_facts(snapshot, entry, expected, expected_agent)
            frozen = fingerprints.setdefault(profile, {"static": digest(static), "tools": None})
            require(frozen["static"] == digest(static), "frozen_model_request_changed")
            if receipt["closing"]:
                require(frozen["tools"] is not None, "model_request_closing_catalog_source_missing")
                allowed = {handle if handle.startswith("tool:host-output:") else handle.removesuffix(":input:" + receipt["run_id"])
                           for handle in receipt["closing_handles"]}
                for tool in tools:
                    original = next((row for row in frozen["tools"] if row["toolHandle"] == tool["toolHandle"]), None)
                    require(original is not None, "frozen_model_request_changed")
                    require(tool["toolHandle"] in allowed
                            and tool == original, "frozen_model_request_changed")
            else:
                if frozen["tools"] is None:
                    frozen["tools"] = tools
                require(frozen["tools"] == tools, "frozen_model_request_changed")
            receipts.append({**receipt, "static_digest": digest(static), "validation": "matched"})
        except (KeyError, TypeError, ValueError) as error:
            detail = str(error) if isinstance(error, ValueError) and str(error).startswith(("model_request_", "frozen_model_", "effective_model_")) else "model_request_preparation_source_missing"
            category = "incomplete" if "source_missing" in detail else "invalid_config_drift"
            reason = issue(category, detail)
            if reason not in snapshot["blocking_reasons"]:
                snapshot["blocking_reasons"].append(reason)
            receipts.append({"event_id": entry.get("event_id"), "validation": "unverified", "reason": detail})
    monitor.request_fingerprints = fingerprints
