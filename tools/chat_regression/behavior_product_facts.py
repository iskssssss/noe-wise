"""从 Runtime 原事实派生 Product 套件的高层断言；证据不足时保持未知。"""
from __future__ import annotations

import json
from pathlib import Path
import re
from urllib.parse import urlencode
from typing import Any

from behavior_capture import flatten


_SUCCESSFUL_SKILLS = {"ACTIVATED", "ALREADY_ACTIVE"}


def _sequence(event: dict[str, Any]) -> int | None:
    value = (event.get("detail") or {}).get("sessionSequence")
    return value if type(value) is int else None


def _events(trace: dict[str, Any]) -> list[dict[str, Any]]:
    try:
        _, rows = flatten(trace, internal=True)
    except (KeyError, TypeError, ValueError):
        return []
    return [row for row in rows if isinstance(row, dict)]


def _object(value: Any) -> dict[str, Any] | None:
    if isinstance(value, dict):
        return value
    if not isinstance(value, str):
        return None
    try:
        parsed = json.loads(value)
    except (TypeError, ValueError):
        return None
    return parsed if isinstance(parsed, dict) else None


def _canonical(payload: Any) -> dict[str, Any] | None:
    if not isinstance(payload, dict):
        return None
    envelope = payload.get("output")
    if not isinstance(envelope, dict):
        return None
    return _object(envelope.get("canonicalJson"))


def _tool_output(event: dict[str, Any]) -> dict[str, Any] | None:
    value = _canonical(event.get("payload"))
    if not isinstance(value, dict):
        return None
    structured = value.get("structuredContent")
    return structured if isinstance(structured, dict) else value


def _arguments(event: dict[str, Any]) -> dict[str, Any] | None:
    value = (event.get("detail") or {}).get("arguments")
    return _object(value)


def _case_resource(case: dict[str, Any], resources: dict[str, Any]) -> dict[str, Any]:
    return resources.get("cases", {}).get(case.get("id"), {})


def _mount_handle(resources: dict[str, Any], resource: dict[str, Any], prefix: str,
                  key: str) -> str | None:
    profile = resource.get("profile")
    mounts = resources.get("agents", {}).get(profile, {}).get("mounts", {})
    mount = mounts.get(key)
    identity = mount.get("mount_id") if isinstance(mount, dict) else None
    return prefix + str(identity) if identity not in (None, "") else None


def _run_id(state: dict[str, Any]) -> str | None:
    turns = state.get("turns")
    if not isinstance(turns, dict) or not turns:
        return None
    last = list(turns.values())[-1]
    terminal = (last.get("result") or {}).get("terminal") if isinstance(last, dict) else None
    value = terminal.get("run_id") if isinstance(terminal, dict) else None
    return value if isinstance(value, str) and value else None


def _model_decision(event: dict[str, Any]) -> dict[str, Any] | None:
    if event.get("event_type") != "agent.model-result":
        return None
    return _canonical(event.get("payload"))


def _knowledge(case: dict[str, Any], resources: dict[str, Any],
               events: list[dict[str, Any]]) -> dict[str, Any]:
    resource = _case_resource(case, resources)
    expected_handle = (resource.get("knowledge") or {}).get("handle")
    documents = (resource.get("knowledge") or {}).get("documents")
    source_id = resources.get("knowledge", {}).get("kb_id")
    if not expected_handle or not source_id or not isinstance(documents, dict) or not documents:
        return {"citations": []}

    observations = []
    item_sources: dict[str, list[tuple[bool, str]]] = {}
    for event in events:
        payload = event.get("payload")
        if event.get("event_type") != "agent.knowledge-observation" or not isinstance(payload, dict):
            continue
        if payload.get("knowledgeHandle") != expected_handle:
            continue
        observations.append(event)
        injected = payload.get("injectedItemIds")
        items = (payload.get("bundle") or {}).get("items") if isinstance(payload.get("bundle"), dict) else None
        if not isinstance(injected, list) or not isinstance(items, list):
            continue
        injected_ids = {value for value in injected if isinstance(value, str)}
        for item in items:
            if not isinstance(item, dict) or not isinstance(item.get("itemId"), str):
                continue
            matches = []
            for document in documents.values():
                if not isinstance(document, dict):
                    continue
                provenance = item.get("provenanceRefs")
                matches.append(
                    item["itemId"] in injected_ids
                    and str(item.get("sourceId")) == str(source_id)
                    and item.get("sourceVersion") == document.get("source_version")
                    and isinstance(provenance, list)
                    and "knowledge-document:" + str(document.get("document_id")) in provenance)
            item_sources.setdefault(item["itemId"], []).append((matches.count(True) == 1, event["event_id"]))

    decisions = []
    for event in events:
        decision = _model_decision(event)
        if isinstance(decision, dict) and decision.get("type") == "respond":
            ids = decision.get("evidence_item_ids")
            if isinstance(ids, list):
                decisions.append((event, ids))
    if not observations or not decisions:
        citations: list[dict[str, Any]] = []
    else:
        decision_event, cited = decisions[-1]
        if not cited:
            citations = [{"source_verified": False, "event_id": decision_event["event_id"]}]
        else:
            citations = []
            for item_id in cited:
                sources = item_sources.get(item_id, []) if isinstance(item_id, str) else []
                verified = len(sources) == 1 and sources[0][0]
                citations.append({"source_verified": verified,
                                  "event_id": sources[0][1] if len(sources) == 1
                                  else decision_event["event_id"]})

    knowledge_tools = [{
        "event_id": event["event_id"], "name": "knowledge_search", "status": "SUCCEEDED",
        "arguments": None, "result": None, "sequence": _sequence(event),
        "started_at": event.get("occurred_at"), "finished_at": event.get("occurred_at"),
    } for event in observations]
    return {"citations": citations, "tools": knowledge_tools}


def _materials(case: dict[str, Any]) -> dict[str, dict[str, str]]:
    candidate = case.get("candidate") or {}
    selected = set(candidate.get("material_refs") or [])
    result = {}
    for material in candidate.get("materials") or []:
        if (isinstance(material, dict) and material.get("ref") in selected
                and isinstance(material.get("content"), str)
                and material.get("kind") in {"text", "csv"}):
            result[material["ref"]] = material
    return result


def _contains_material(rendered: str, material: str) -> bool:
    if material in rendered:
        return True
    escaped = json.dumps(material, ensure_ascii=False)[1:-1]
    return escaped in rendered


def _reference_reads(case: dict[str, Any], resources: dict[str, Any], state: dict[str, Any],
                     events: list[dict[str, Any]]) -> dict[str, Any]:
    resource = _case_resource(case, resources)
    profile = resource.get("profile")
    code_handle = _mount_handle(resources, resource, "tool:", "tool:platform_tools:code_execute")
    reader_handle = _mount_handle(resources, resource, "tool:", "tool:platform_tools:read_context_reference")
    materials = _materials(case)
    if not materials or not code_handle or not reader_handle or not profile:
        return {"source_reads": [], "read_before_calculation": None}

    code_events = [event for event in events
                   if event.get("event_type") == "agent.tool-result"
                   and isinstance(event.get("payload"), dict)
                   and event["payload"].get("toolHandle") == code_handle
                   and event["payload"].get("status") == "SUCCEEDED"]
    code_bindings: dict[str, set[str]] = {}
    for event in code_events:
        arguments = _arguments(event)
        files = arguments.get("input_files") if isinstance(arguments, dict) else None
        if not isinstance(files, dict):
            continue
        matched = set()
        for ref, material in materials.items():
            suffix = ".csv" if material["kind"] == "csv" else ".txt"
            supplied = files.get(ref + suffix)
            # 文本文件允许常规行尾与一个终止换行，正文、空格和内部空行仍须完全一致。
            normalize = lambda text: text.replace("\r\n", "\n").removesuffix("\n")
            if isinstance(supplied, str) and normalize(supplied) == normalize(material["content"]):
                matched.add(ref)
        code_bindings[event["event_id"]] = matched

    indexes: dict[str, dict[str, Any]] = {}
    for event in events:
        payload = event.get("payload")
        if event.get("event_type") != "agent.context-reference" or not isinstance(payload, dict):
            continue
        source = payload.get("sourceEventId")
        try:
            characters = int(payload.get("characters"))
        except (TypeError, ValueError):
            continue
        if (source in code_bindings and payload.get("sourceType") == "agent.tool-result"
                and payload.get("sourceSchema") == "skillforge.runtime.agent.tool-result.v1"
                and payload.get("sourceComplete") is True
                and characters > int((resource.get("reference_transport") or {}).get(
                    "automatic_threshold_characters", 4096))):
            indexes[event["event_id"]] = {"source": source, "characters": characters}

    pages: dict[str, list[tuple[int, int, str, bool, str, int]]] = {}
    for event in events:
        payload = event.get("payload")
        if event.get("event_type") != "agent.tool-result" or not isinstance(payload, dict):
            continue
        if payload.get("toolHandle") != reader_handle or payload.get("status") != "SUCCEEDED":
            continue
        arguments, output = _arguments(event), _tool_output(event)
        if not isinstance(arguments, dict) or not isinstance(output, dict):
            continue
        reference = arguments.get("reference_id")
        source = arguments.get("source_event_id")
        if reference is None and isinstance(source, str):
            matches = [key for key, value in indexes.items() if value["source"] == source]
            reference = matches[0] if len(matches) == 1 else None
        if reference not in indexes or output.get("success") is not True:
            continue
        try:
            offset = int(arguments.get("offset", 0))
            next_offset = int(output.get("next_offset"))
            sequence = _sequence(event)
        except (TypeError, ValueError):
            continue
        content = output.get("content")
        if (type(sequence) is not int or not isinstance(content, str) or offset < 0
                or next_offset < offset or next_offset - offset != len(content)):
            # Runtime uses Unicode code points. Python len has the same result for non-surrogate text.
            continue
        if output.get("reference_id") != reference or output.get("source_complete") is not True:
            continue
        pages.setdefault(reference, []).append(
            (offset, next_offset, content, output.get("end") is True, event["event_id"], sequence))

    rendered: dict[str, tuple[str, bool, list[tuple[str, int]]]] = {}
    for reference, values in pages.items():
        ordered = sorted(values, key=lambda value: value[0])
        cursor, chunks, proof, complete = 0, [], [], False
        for offset, next_offset, content, end, event_id, sequence in ordered:
            if offset != cursor:
                break
            chunks.append(content)
            proof.append((event_id, sequence))
            cursor = next_offset
            if end:
                complete = cursor == indexes[reference]["characters"]
                break
        rendered[reference] = ("".join(chunks), complete, proof)

    reads = []
    read_sequences = []
    for ref, material in materials.items():
        bound_sources = {source for source, refs in code_bindings.items() if ref in refs}
        candidates = [(reference, value) for reference, value in rendered.items()
                      if indexes[reference]["source"] in bound_sources]
        matches = [(reference, value) for reference, value in candidates
                   if _contains_material(value[0], material["content"])]
        if len(matches) == 1 and matches[0][1][2]:
            proof = matches[0][1][2]
            reads.append({"ref": ref, "verified": True, "event_id": proof[-1][0]})
            read_sequences.append(proof[-1][1])
        elif candidates and all(value[1] for _, value in candidates):
            proof = candidates[-1][1][2]
            reads.append({"ref": ref, "verified": False,
                          "event_id": proof[-1][0] if proof else indexes[candidates[-1][0]]["source"]})
        elif code_events and not bound_sources:
            reads.append({"ref": ref, "verified": False, "event_id": code_events[-1]["event_id"]})
        else:
            reads.append({"ref": ref, "verified": None, "event_id": None})

    ordering = None
    if reads and all(item["verified"] is True for item in reads):
        run_id = _run_id(state)
        finals = [event for event in events if event.get("event_type") == "run.completed"
                  and (run_id is None or event.get("run_id") == run_id) and _sequence(event) is not None]
        if len(finals) == 1 and read_sequences:
            after_reads = [event for event in code_events if _sequence(event) is not None
                           and max(read_sequences) < _sequence(event) < _sequence(finals[0])]
            ordering = bool(after_reads)
    elif any(item["verified"] is False for item in reads):
        ordering = False
    return {"source_reads": reads, "read_before_calculation": ordering}


def _contains_number(value: Any, expected: Any) -> bool:
    rendered = json.dumps(value, ensure_ascii=False, sort_keys=True)
    return bool(re.search(
        r"(?<![\d.])" + re.escape(str(expected)) + r"(?:\.0+)?(?![\d.])",
        rendered.replace(",", "")))


def _attachment_inputs(case: dict[str, Any], resources: dict[str, Any], state: dict[str, Any],
                       events: list[dict[str, Any]]) -> dict[str, Any]:
    """把 D08 结果绑定到同一次真实代码调用、上传状态及两份冻结文件摘要。"""
    resource = _case_resource(case, resources)
    code_handle = _mount_handle(resources, resource, "tool:", "tool:platform_tools:code_execute")
    refs = (resource.get("attachment_transport") or {}).get("refs")
    bindings = resource.get("material_bindings")
    uploads = state.get("attachment_uploads")
    if (refs != ["orders", "ledger"] or not isinstance(bindings, list)
            or not isinstance(uploads, dict) or not code_handle):
        return {"attachment_inputs": [], "attachment_calculation_verified": None,
                "attachment_calculation_event_id": None}
    by_ref = {item.get("ref"): item for item in bindings if isinstance(item, dict)}
    if set(by_ref) != set(refs):
        return {"attachment_inputs": [], "attachment_calculation_verified": None,
                "attachment_calculation_event_id": None}
    expected_uploads = {}
    for ref in refs:
        entry = uploads.get(ref)
        metadata = entry.get("metadata") if isinstance(entry, dict) else None
        binding = by_ref[ref]
        if (not isinstance(entry, dict) or entry.get("status") != "ready"
                or not isinstance(metadata, dict)):
            expected_uploads[ref] = None
            continue
        expected_uploads[ref] = {
            "id": entry.get("upload_id"), "name": Path(binding["path"]).name,
            "input_path": metadata.get("input_path"), "size": binding.get("size"),
            "sha256": binding.get("sha256"), "expires_at": metadata.get("expires_at"),
        }

    run_id = _run_id(state)
    code_events = [event for event in events
                   if event.get("event_type") == "agent.tool-result"
                   and isinstance(event.get("payload"), dict)
                   and event["payload"].get("toolHandle") == code_handle
                   and event["payload"].get("status") == "SUCCEEDED"
                   and (run_id is None or event.get("run_id") == run_id)]
    observations = []
    for event in code_events:
        arguments, output = _arguments(event), _tool_output(event)
        ids = arguments.get("attachment_ids") if isinstance(arguments, dict) else None
        input_files = arguments.get("input_files") if isinstance(arguments, dict) else None
        ids_are_strings = (isinstance(ids, list) and len(ids) == 2
                           and all(isinstance(value, str) for value in ids))
        ids_exact = (ids_are_strings and len(set(ids)) == 2
                     and set(ids) == {value["id"] for value in expected_uploads.values()
                                     if isinstance(value, dict)})
        no_inline = input_files in (None, {})
        result_inputs = output.get("attachment_inputs") if isinstance(output, dict) else None
        result_by_id = {}
        if isinstance(result_inputs, list):
            for row in result_inputs:
                identity = row.get("id") if isinstance(row, dict) else None
                if isinstance(identity, str):
                    result_by_id[identity] = row
        exact_result_set = (len(result_inputs) == 2 and len(result_by_id) == 2
                            if isinstance(result_inputs, list) else False)
        rows = []
        for ref in refs:
            expected = expected_uploads[ref]
            actual = result_by_id.get(expected.get("id")) if isinstance(expected, dict) else None
            verified = (isinstance(actual, dict) and isinstance(actual.get("expires_at"), str)
                        and all(actual.get(key) == expected.get(key)
                                for key in ("id", "name", "input_path", "size", "sha256"))
                        and exact_result_set
                        and expected.get("id") in ids) if ids_exact else False
            rows.append({"ref": ref, "id": expected.get("id") if isinstance(expected, dict) else None,
                         "sha256": by_ref[ref].get("sha256"), "verified": verified,
                         "event_id": event["event_id"]})
        expected = case.get("expected") or {}
        numbers = (isinstance(output, dict)
                   and _contains_number(output, expected.get("unique_total"))
                   and _contains_number(output, expected.get("difference")))
        verified = (isinstance(output, dict) and output.get("success") is True
                    and ids_exact and no_inline and all(row["verified"] for row in rows)
                    and numbers)
        observations.append((verified, rows, event["event_id"]))

    if not observations:
        rows = [{"ref": ref, "id": (expected_uploads[ref] or {}).get("id"),
                 "sha256": by_ref[ref].get("sha256"), "verified": None, "event_id": None}
                for ref in refs]
        return {"attachment_inputs": rows, "attachment_calculation_verified": None,
                "attachment_calculation_event_id": None}
    chosen = next((row for row in reversed(observations) if row[0]), observations[-1])
    return {"attachment_inputs": chosen[1], "attachment_calculation_verified": chosen[0],
            "attachment_calculation_event_id": chosen[2]}


def _skill_facts(case: dict[str, Any], resources: dict[str, Any], state: dict[str, Any],
                 events: list[dict[str, Any]], api: Any) -> dict[str, Any]:
    resource = _case_resource(case, resources)
    profile = resource.get("profile")
    mounts = resources.get("agents", {}).get(profile, {}).get("mounts", {})
    expected = {str(value.get("mount_id")) for key, value in mounts.items()
                if key.startswith("skill:") and isinstance(value, dict) and value.get("mount_id")}
    observed: dict[str, str] = {}
    for event in events:
        if event.get("event_type") != "skill.activation" or not isinstance(event.get("payload"), dict):
            continue
        for result in event["payload"].get("results") or []:
            if (isinstance(result, dict) and result.get("handle") in expected
                    and result.get("status") in _SUCCESSFUL_SKILLS):
                observed[result["handle"]] = event["event_id"]
    identities = [{"handle": handle, "event_id": observed.get(handle)} for handle in sorted(expected)]
    refs = [value for value in observed.values()]

    cases = resources.get("cases", {})
    user_id, agent_id = resource.get("user_id"), resource.get("agent_id")
    isolated_resources = bool(user_id and agent_id and len(expected) == 2
        and sum(value.get("user_id") == user_id for value in cases.values() if isinstance(value, dict)) == 1
        and sum(value.get("agent_id") == agent_id for value in cases.values() if isinstance(value, dict)) == 1)
    verified = False if not isolated_resources else None
    sid = state.get("session_id")
    if isolated_resources and api is not None and isinstance(sid, str) and sid:
        path = "/chat/sessions?" + urlencode({"agentId": agent_id, "end_user_id": user_id})
        try:
            sessions = api.request("GET", path)
        except Exception:  # 只读通道不可用时不能把隔离性猜成失败。
            sessions = None
        if isinstance(sessions, list):
            verified = sum(
                isinstance(item, dict)
                and (item.get("sessionId") == sid or item.get("session_id") == sid)
                for item in sessions
            ) == 1
            refs.append("session:" + sid)
    return {"skill_identities": identities,
            "isolation": {"verified": verified, "refs": list(dict.fromkeys(refs))}}


def _workflow(case: dict[str, Any], resources: dict[str, Any], state: dict[str, Any],
              events: list[dict[str, Any]]) -> dict[str, Any]:
    resource = _case_resource(case, resources)
    handle = (resource.get("workflow") or {}).get("handle")
    matches = [event for event in events if event.get("event_type") == "agent.workflow-result"
               and isinstance(event.get("payload"), dict)
               and event["payload"].get("workflowHandle") == handle]
    refs = [event["event_id"] for event in matches]
    turns = state.get("turns")
    turn_results = [value.get("result", {}) for value in turns.values()
                    if isinstance(value, dict)] if isinstance(turns, dict) else []
    hitl = (len(turn_results) >= 2
            and (turn_results[0].get("terminal") or {}).get("status") == "waiting_hitl"
            and (turn_results[-1].get("terminal") or {}).get("status") == "completed"
            and (turn_results[0].get("terminal") or {}).get("run_id")
                == (turn_results[-1].get("terminal") or {}).get("run_id"))
    suspended = [event for event in events if event.get("event_type") == "run.suspended"
                 and isinstance(event.get("payload"), dict)
                 and (event["payload"].get("outcome") or {}).get("reason") == "WORKFLOW_HITL"]
    refs += [event["event_id"] for event in suspended]
    if not matches:
        effect = None
    else:
        identities = [(event["payload"].get("executionRef") or {}).get("executionId")
                      for event in matches]
        completed = [event for event in matches if event["payload"].get("status") == "COMPLETED"]
        if len(completed) > 1 or len({value for value in identities if value}) > 1:
            effect = False
        elif len(completed) == 1 and identities[0] and hitl and suspended:
            effect = True
        elif any(event["payload"].get("status") in {"FAILED", "TERMINATED", "CANCELLED"}
                 for event in matches):
            effect = False
        else:
            effect = None
    return {"workflow_effect_once": effect, "workflow_refs": list(dict.fromkeys(refs))}


def _delegation_payload(event: dict[str, Any]) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
    payload = event.get("payload")
    if event.get("event_type") != "agent.delegation" or not isinstance(payload, dict):
        return None, None
    command, result = _object(payload.get("command")), payload.get("result")
    return command, result if isinstance(result, dict) else None


def _delegation(case: dict[str, Any], resources: dict[str, Any], state: dict[str, Any],
                events: list[dict[str, Any]]) -> dict[str, Any]:
    resource = _case_resource(case, resources)
    child_ids = {str(value) for value in (resource.get("team") or {}).get("child_agent_ids", [])}
    run_id = _run_id(state)
    if run_id is None:
        # 离线材料未保存终态身份时，只接受唯一父 Run；混合历史不能拼成一次成功委派。
        candidates = {event.get("run_id") for event in events
                      if event.get("event_type") == "agent.delegation" and event.get("run_id")}
        if len(candidates) != 1:
            return {"delegation": {"two_owned_results": None, "parent_waited": None, "refs": []}}
        run_id = candidates.pop()
    events = [event for event in events if event.get("run_id") == run_id]
    starts: dict[str, tuple[str, str, int | None]] = {}
    waiting, results = [], []
    for event in events:
        command, result = _delegation_payload(event)
        if command is None or result is None:
            continue
        operation = command.get("operation")
        if (operation == "start" and result.get("status") == "accepted"
                and isinstance(result.get("delegation_id"), str)
                and isinstance(command.get("target_handle"), str)):
            starts[result["delegation_id"]] = (
                command["target_handle"], event["event_id"], _sequence(event))
        elif operation == "wait":
            ids = command.get("delegation_ids")
            if not isinstance(ids, list) or any(not isinstance(value, str) for value in ids):
                continue
            if result.get("status") == "waiting" and event.get("payload", {}).get("wait_id"):
                waiting.append((event, ids))
            if isinstance(result.get("results"), list):
                results.append((event, ids, result["results"]))

    owned = None
    result_event = None
    if results:
        result_event, waited_ids, rows = results[-1]
        row_ids = [row.get("delegation_id") for row in rows if isinstance(row, dict)]
        terminal = all(isinstance(row, dict) and row.get("state") == "completed" for row in rows)
        targets = {starts[value][0].removeprefix("member:") for value in row_ids if value in starts}
        owned = (len(rows) == len(row_ids) == len(set(row_ids)) == 2
                 and set(waited_ids) == set(row_ids) == set(starts)
                 and targets == child_ids and terminal and len(child_ids) == 2)
    elif starts and len(starts) != 2:
        owned = False

    parent_waited = None
    refs = [value[1] for value in starts.values()]
    if result_event is not None:
        refs.append(result_event["event_id"])
    if waiting:
        wait_event, wait_ids = waiting[-1]
        refs.append(wait_event["event_id"])
        suspensions = [event for event in events if event.get("event_type") == "run.suspended"
                       and isinstance(event.get("payload"), dict)
                       and (event["payload"].get("outcome") or {}).get("reason") == "DELEGATED"]
        completions = [event for event in events if event.get("event_type") == "run.completed"]
        if result_event is not None and suspensions and completions:
            order = [_sequence(wait_event), _sequence(suspensions[-1]),
                     _sequence(result_event), _sequence(completions[-1])]
            if all(type(value) is int for value in order):
                parent_waited = (order == sorted(order) and len(set(order)) == 4
                                  and set(wait_ids) == set(starts))
                refs += [suspensions[-1]["event_id"], completions[-1]["event_id"]]
    elif result_event is not None:
        # 子任务已在首次 wait 前完成时不必挂起父任务，但汇总仍必须晚于两个真实结果。
        completions = [event for event in events if event.get("event_type") == "run.completed"
                       and event.get("run_id") == result_event.get("run_id")]
        if owned is True and completions and _sequence(result_event) is not None:
            parent_waited = all(_sequence(event) is not None and _sequence(event) > _sequence(result_event)
                                for event in completions)
            refs += [event["event_id"] for event in completions]
    return {"delegation": {"two_owned_results": owned, "parent_waited": parent_waited,
                           "refs": list(dict.fromkeys(refs))}}


def _cancellation(state: dict[str, Any], events: list[dict[str, Any]]) -> dict[str, Any]:
    run_id = _run_id(state)
    requests = [event for event in events if event.get("event_type") == "run.cancel_requested"
                and (run_id is None or event.get("run_id") == run_id) and _sequence(event) is not None]
    if not requests:
        return {"cancellation": {"cancel_persisted": None, "cancel_event_id": None,
                                  "post_cancel_dispatches": None},
                "no_reexecution_after_reconnect": None}
    request = requests[-1]
    sequence = _sequence(request)
    before_claims = [event for event in events if event.get("event_type") == "action.claimed"
                     and event.get("run_id") == request.get("run_id")
                     and _sequence(event) is not None and _sequence(event) < sequence
                     and str((event.get("detail") or {}).get("callKind", "")).startswith("TOOL")]
    later_claims = [event for event in events if event.get("event_type") == "action.claimed"
                    and event.get("run_id") == request.get("run_id")
                    and _sequence(event) is not None and _sequence(event) > sequence
                    and str((event.get("detail") or {}).get("callKind", "")).startswith("TOOL")]
    cancelled = [event for event in events if event.get("event_type") == "run.cancelled"
                 and event.get("run_id") == request.get("run_id")
                 and _sequence(event) is not None and _sequence(event) > sequence]
    persisted = True if before_claims and cancelled else False if cancelled else None

    turns = state.get("turns")
    results = [value.get("result", {}) for value in turns.values()
               if isinstance(value, dict)] if isinstance(turns, dict) else []
    reconnected = any(result.get("deliberate_disconnect") is True or result.get("resumed") is True
                      for result in results)
    execute_attempts: dict[str, set[str]] = {}
    post_cancel_attempts: set[str] = set()
    post_cancel_known = True
    from datetime import datetime
    def instant(value):
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00")) if isinstance(value, str) else None
        except ValueError:
            return None
    cancel_time = instant(request.get("occurred_at"))
    known = False
    for event in events:
        if event.get("run_id") != request.get("run_id"):
            continue
        detail = event.get("detail") or {}
        invocation = detail.get("invocationId")
        attempts = detail.get("attempts")
        if not isinstance(invocation, str) or not isinstance(attempts, list):
            continue
        for attempt in attempts:
            if not isinstance(attempt, dict) or attempt.get("authorization") != "EXECUTE":
                continue
            attempt_id = attempt.get("attemptId")
            if isinstance(attempt_id, str):
                known = True
                execute_attempts.setdefault(invocation, set()).add(attempt_id)
                started = instant(attempt.get("startedAt"))
                if cancel_time is not None and started is not None:
                    if started > cancel_time:
                        post_cancel_attempts.add(attempt_id)
                elif event in later_claims and detail.get("authorization") == "EXECUTE":
                    post_cancel_attempts.add(attempt_id)
                elif event in later_claims and detail.get("authorization") != "FINALIZE_KNOWN":
                    post_cancel_known = False
    repeated = any(len(values) > 1 for values in execute_attempts.values())
    no_repeat = (not repeated if reconnected and cancelled and known else None)
    return {"cancellation": {"cancel_persisted": persisted,
                              "cancel_event_id": request["event_id"],
                              "post_cancel_dispatches": len(post_cancel_attempts) if cancelled and known and post_cancel_known else None,
                              "dispatch_before_cancel": bool(before_claims)},
            "no_reexecution_after_reconnect": no_repeat}


def derive_facts(case: dict[str, Any], resources: dict[str, Any], state: dict[str, Any],
                 trace: dict[str, Any], history: dict[str, Any], api: Any = None) -> dict[str, Any]:
    """返回可直接合并进 evidence 的派生事实；绝不从最终答复正文推断执行成功。"""
    del history  # 当前优先事实均来自持久 Runtime trace；保留参数作为稳定调用契约。
    events = _events(trace)
    category = case.get("id")
    if category == "D04":
        return _knowledge(case, resources, events)
    if category == "D05":
        return _reference_reads(case, resources, state, events)
    if category == "D08":
        return _attachment_inputs(case, resources, state, events)
    if category == "D07":
        return _skill_facts(case, resources, state, events, api)
    if category == "D10":
        return _workflow(case, resources, state, events)
    if category == "D11":
        return _delegation(case, resources, state, events)
    if category == "D12":
        return _cancellation(state, events)
    return {}
