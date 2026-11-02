"""聊天过程产品证据的失败关闭校验。

本模块不发起 API 调用，也不根据助手最终回答猜测 UI 已成功。调用方必须提供
持久会话与两个浏览器客户端捕获的不可变、脱敏快照；每项断言独立报告，证据
不完整时只能得到 ``unknown``。

The compact input contract is intentionally transport-neutral::

    {
      "session": {"session_id": "..."},
      "artifacts": {
        "processes": [{"process_id", "run_id", "root_run_id", "root_input_id",
                         "type", "sequence", "ordinal"}, ...],
        "delivery": [{"event_id", "process_id", "arrival", "kind"}, ...],
        "waiting": {"status": "waiting_input", "visible": true},
        "collaboration": {"parent_session_id": "...", "children": [...]},
        "history": {"institution": {...}, "chat": {...}}
      },
      "browser": {"institution": live-client, "chat": live-client},
      "privacy": {"marker_digests": [sha256], "marker_count": 1,
                  "public_snapshots": [redacted DOM/response facts],
                  "exposure_detected": false}
    }

实时客户端须有 ``mode: live``、相同的 ``session_id`` 与 ``operations`` 映射。
每项操作须带非空 ``reference``、状态和实际 ``observed`` 观察值。所需操作名
由 :data:`CLIENT_OPERATIONS` 给出。标记为禁止的值只用于判断是否泄露，绝不
复制到返回行。
"""
from __future__ import annotations

from typing import Any


PASSED = "passed"
FAILED = "failed"
UNKNOWN = "unknown"
_STATUSES = {PASSED, FAILED, UNKNOWN}

# 标签保持稳定，浏览器采集器无需耦合某个 Playwright 实现。
CLIENT_OPERATIONS = (
    "process_interleave",
    "duplicate_replay",
    "out_of_order_replay",
    "final_before_completion",
    "auto_collapse_once",
    "manual_expand",
    "waiting_input_visible",
    "parent_child_status",
    "history_read",
)

_CASE_OPERATIONS = {
    "D09": ("process_interleave", "duplicate_replay", "out_of_order_replay"),
    "D10": ("final_before_completion", "auto_collapse_once", "manual_expand"),
    "D11": ("waiting_input_visible", "parent_child_status"),
    "D12": ("history_read",),
}


def _reference(value: Any, pointer: str = "") -> list[dict[str, str]]:
    if isinstance(value, str) and value:
        return [{"reference": value, "pointer": pointer}]
    if isinstance(value, dict):
        ref = value.get("reference")
        if isinstance(ref, str) and ref:
            return [{"reference": ref, "pointer": pointer}]
    return []


def _row(assertion_id: str, status: str, reason: str, *references: Any) -> dict[str, Any]:
    refs: list[dict[str, str]] = []
    for reference in references:
        refs.extend(_reference(reference))
    return {"id": assertion_id, "status": status, "reason": reason, "references": refs}


def _object(value: Any) -> dict[str, Any] | None:
    return value if isinstance(value, dict) else None


def _list(value: Any) -> list[Any] | None:
    return value if isinstance(value, list) else None


def _operation(client: dict[str, Any] | None, name: str) -> tuple[str, str, list[dict[str, str]]]:
    """缺失或格式不完整的操作一律为 unknown。"""
    if not client:
        return UNKNOWN, "live_client_missing", []
    operations = _object(client.get("operations"))
    operation = _object((operations or {}).get(name))
    if not operation:
        return UNKNOWN, "operation_missing:" + name, []
    status = operation.get("status")
    refs = _reference(operation)
    if status not in _STATUSES or not refs or not _object(operation.get("observed")):
        return UNKNOWN, "operation_evidence_incomplete:" + name, refs
    return status, "operation_" + status + ":" + name, refs


def _require_live_client(client: Any, session_id: str, label: str) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
    client = _object(client)
    if client is None:
        return None, _row("D09." + label + ".live", UNKNOWN, "live_client_missing")
    refs = _reference(client)
    if client.get("mode") != "live":
        return client, _row("D09." + label + ".live", UNKNOWN, "client_not_live", client)
    if not session_id:
        return client, _row("D09." + label + ".live", UNKNOWN, "session_identity_unavailable", client)
    if client.get("session_id") != session_id:
        return client, _row("D09." + label + ".live", FAILED, "client_session_mismatch", client)
    if not refs:
        return client, _row("D09." + label + ".live", UNKNOWN, "client_reference_missing")
    return client, _row("D09." + label + ".live", PASSED, "live_client_bound", client)


def _delivery_row(delivery: Any, processes: list[dict[str, Any]]) -> dict[str, Any]:
    events = _list(delivery)
    if events is None:
        return _row("D09.delivery", UNKNOWN, "delivery_evidence_missing")
    by_event: dict[str, dict[str, Any]] = {}
    arrivals: list[int] = []
    has_duplicate = has_out_of_order = False
    known_processes = {item.get("process_id") for item in processes}
    process_order = {item.get("process_id"): index for index, item in enumerate(
        sorted(processes, key=lambda item: (item["ordinal"], item["sequence"], item["process_id"]))) }
    for event in events:
        if not isinstance(event, dict) or not isinstance(event.get("event_id"), str) or not event["event_id"]:
            return _row("D09.delivery", UNKNOWN, "delivery_identity_missing", event)
        event_id = event["event_id"]
        prior = by_event.get(event_id)
        if prior is not None:
            # 完全相同的重放是预期行为；同一事件身份出现不同内容是持久会话完整性失败。
            if {k: v for k, v in prior.items() if k != "reference"} != {k: v for k, v in event.items() if k != "reference"}:
                return _row("D09.delivery", FAILED, "conflicting_delivery_identity", prior, event)
            has_duplicate = True
            continue
        by_event[event_id] = event
        if event.get("kind") == "duplicate":
            has_duplicate = True
        if event.get("kind") == "out_of_order":
            has_out_of_order = True
        if not isinstance(event.get("process_id"), str) or event.get("process_id") not in known_processes:
            return _row("D09.delivery", FAILED, "delivery_references_unknown_process", event)
        if type(event.get("arrival")) is not int:
            return _row("D09.delivery", UNKNOWN, "delivery_arrival_missing", event)
        arrivals.append(event["arrival"])
    if not has_duplicate or not has_out_of_order:
        return _row("D09.delivery", UNKNOWN, "duplicate_or_reorder_not_exercised")
    if len(arrivals) != len(set(arrivals)):
        return _row("D09.delivery", FAILED, "delivery_arrival_not_unique")
    delivered = sorted(by_event.values(), key=lambda event: event["arrival"])
    actual_reorder = any(process_order[earlier["process_id"]] > process_order[later["process_id"]]
                         for index, earlier in enumerate(delivered) for later in delivered[index + 1:])
    if not actual_reorder:
        return _row("D09.delivery", FAILED, "out_of_order_delivery_not_observed", *delivered)
    return _row("D09.delivery", PASSED, "duplicate_and_reordered_delivery_captured", *by_event.values())


def _process_row(processes: Any) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    records = _list(processes)
    if records is None:
        return [], _row("D09.process", UNKNOWN, "process_snapshot_missing")
    if not records:
        return [], _row("D09.process", UNKNOWN, "process_snapshot_empty")
    required = ("process_id", "run_id", "root_run_id", "root_input_id", "type", "sequence", "ordinal")
    ids: set[str] = set()
    normalized: list[dict[str, Any]] = []
    for item in records:
        if (not isinstance(item, dict)
                or any(not isinstance(item.get(key), str) or not item[key] for key in required[:5])):
            return [], _row("D09.process", UNKNOWN, "process_identity_incomplete", item)
        if type(item["sequence"]) is not int or type(item["ordinal"]) is not int:
            return [], _row("D09.process", UNKNOWN, "process_order_incomplete", item)
        if item["process_id"] in ids:
            return [], _row("D09.process", FAILED, "duplicate_process_identity", item)
        ids.add(item["process_id"])
        normalized.append(item)
    ordered = sorted(normalized, key=lambda item: (item["ordinal"], item["sequence"], item["process_id"]))
    root = ordered[0]["root_run_id"]
    input_id = ordered[0]["root_input_id"]
    if any(item["root_run_id"] != root or item["root_input_id"] != input_id for item in ordered):
        return [], _row("D09.process", FAILED, "process_root_identity_mismatch", *ordered)
    kinds = [item["type"] for item in ordered]
    commentary = [index for index, kind in enumerate(kinds) if kind == "commentary"]
    steps = [index for index, kind in enumerate(kinds) if kind != "commentary"]
    interleaved = any(c < s for c in commentary for s in steps) and any(s < c for c in commentary for s in steps)
    if not interleaved:
        return ordered, _row("D09.process", FAILED, "commentary_steps_not_interleaved", *ordered)
    return ordered, _row("D09.process", PASSED, "stable_commentary_step_interleave", *ordered)


def _operation_row(assertion_id: str, clients: list[dict[str, Any] | None], names: tuple[str, ...]) -> dict[str, Any]:
    if not clients:
        return _row(assertion_id, UNKNOWN, "browser_operation_evidence_missing")
    states: list[tuple[str, str, list[dict[str, str]]]] = []
    for client in clients:
        for name in names:
            states.append(_operation(client, name))
    references = [ref for _, _, refs in states for ref in refs]
    if any(state == FAILED for state, _, _ in states):
        return {"id": assertion_id, "status": FAILED,
                "reason": next(reason for state, reason, _ in states if state == FAILED), "references": references}
    if any(state == UNKNOWN for state, _, _ in states):
        return {"id": assertion_id, "status": UNKNOWN,
                "reason": next(reason for state, reason, _ in states if state == UNKNOWN), "references": references}
    return {"id": assertion_id, "status": PASSED, "reason": "operations_passed", "references": references}


def _deterministic_row(case_id: str, evidence: Any) -> dict[str, Any]:
    """确定性回放必须说明由浏览器运行器产生，不能凭 mode 字段自行通过。"""
    document = _object(evidence)
    if not document or document.get("runner") != "playwright":
        return _row(case_id + ".deterministic", UNKNOWN, "deterministic_browser_capture_missing", document)
    operations = _object(document.get("operations"))
    if operations is None:
        return _row(case_id + ".deterministic", UNKNOWN, "deterministic_operations_missing", document)
    # 复用操作格式校验，但不把调用方填写的 live 布尔值作为确定性通过条件。
    return _operation_row(case_id + ".deterministic", [{"operations": operations}], _CASE_OPERATIONS[case_id])


def _history_row(history: Any, session_id: str, clients: list[dict[str, Any] | None]) -> dict[str, Any]:
    history = _object(history)
    institution, chat = _object((history or {}).get("institution")), _object((history or {}).get("chat"))
    if not institution or not chat:
        return _row("D12.history", UNKNOWN, "paired_history_missing")
    for snapshot in (institution, chat):
        if snapshot.get("mode") != "live":
            return _row("D12.history", UNKNOWN, "history_not_live", snapshot)
        if snapshot.get("session_id") != session_id:
            return _row("D12.history", FAILED, "history_session_mismatch", snapshot)
        if not _reference(snapshot):
            return _row("D12.history", UNKNOWN, "history_reference_missing", snapshot)
    fields = ("process_ids", "final_message_ids")
    if any(not isinstance(institution.get(field), list) or not institution.get(field) for field in fields):
        return _row("D12.history", UNKNOWN, "institution_history_identity_incomplete", institution)
    if any(not isinstance(chat.get(field), list) or not chat.get(field) for field in fields):
        return _row("D12.history", UNKNOWN, "chat_history_identity_incomplete", chat)
    if any(institution[field] != chat[field] for field in fields):
        return _row("D12.history", FAILED, "paired_history_diverged", institution, chat)
    operation = _operation_row("D12.history_read", clients, ("history_read",))
    if operation["status"] != PASSED:
        return operation
    return _row("D12.history", PASSED, "same_live_session_history_read", institution, chat)


def _waiting_and_child_rows(artifacts: dict[str, Any], clients: list[dict[str, Any] | None], session_id: str) -> list[dict[str, Any]]:
    waiting = _object(artifacts.get("waiting"))
    if waiting is None:
        waiting_row = _row("D11.waiting", UNKNOWN, "waiting_snapshot_missing")
    elif waiting.get("status") != "waiting_input" or waiting.get("visible") is not True:
        waiting_row = _row("D11.waiting", FAILED, "waiting_input_not_visible", waiting)
    elif not _reference(waiting):
        waiting_row = _row("D11.waiting", UNKNOWN, "waiting_reference_missing", waiting)
    else:
        waiting_row = _row("D11.waiting", PASSED, "waiting_input_visible", waiting)
    collaboration = _object(artifacts.get("collaboration"))
    children = _list((collaboration or {}).get("children"))
    if not collaboration or collaboration.get("parent_session_id") != session_id or not children:
        child_row = _row("D11.parent_child", UNKNOWN, "parent_child_snapshot_missing", collaboration)
    elif any(not isinstance(child, dict) or not child.get("session_id") or not child.get("status")
             or child.get("session_id") == session_id or child.get("visible_in_parent") != "summary" for child in children):
        child_row = _row("D11.parent_child", FAILED, "parent_child_visibility_or_identity_invalid", collaboration)
    elif not _reference(collaboration):
        child_row = _row("D11.parent_child", UNKNOWN, "parent_child_reference_missing", collaboration)
    else:
        child_row = _row("D11.parent_child", PASSED, "child_session_status_is_summary_only", collaboration)
    operations = _operation_row("D11.browser", clients, ("waiting_input_visible", "parent_child_status"))
    return [waiting_row, child_row, operations]


def _privacy_row(privacy: Any, artifacts: dict[str, Any], browser: Any) -> dict[str, Any]:
    privacy = _object(privacy)
    if privacy is None:
        return _row("D12.privacy", UNKNOWN, "privacy_evidence_missing")
    markers = _list(privacy.get("marker_digests"))
    snapshots = _list(privacy.get("public_snapshots"))
    if (not markers or any(not isinstance(value, str) or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value) for value in markers)
        or len(markers) != len(set(markers)) or privacy.get("marker_count") != len(markers) or snapshots is None):
        return _row("D12.privacy", UNKNOWN, "privacy_contract_incomplete", privacy)
    required = {"institution:dom", "institution:response", "chat:dom", "chat:response"}
    observed = set()
    exposed = False
    for snapshot in snapshots:
        if not isinstance(snapshot, dict) or set(snapshot) != {"source", "kind", "observed", "content_sha256", "character_count", "contains_forbidden"}:
            return _row("D12.privacy", UNKNOWN, "privacy_snapshot_contract_incomplete", privacy)
        source = snapshot.get("source")
        if source not in required or snapshot.get("kind") not in {"dom", "response"} or source.split(":", 1)[1] != snapshot["kind"]:
            return _row("D12.privacy", UNKNOWN, "privacy_snapshot_source_invalid", privacy)
        if snapshot.get("observed") is not True or not isinstance(snapshot.get("content_sha256"), str) \
                or len(snapshot["content_sha256"]) != 64 or type(snapshot.get("character_count")) is not int \
                or snapshot["character_count"] < 0 or type(snapshot.get("contains_forbidden")) is not bool:
            return _row("D12.privacy", UNKNOWN, "privacy_snapshot_observation_incomplete", privacy)
        observed.add(source)
        exposed |= snapshot["contains_forbidden"]
    if observed != required:
        return _row("D12.privacy", UNKNOWN, "privacy_public_snapshot_missing", privacy)
    if privacy.get("exposure_detected") is not exposed:
        return _row("D12.privacy", FAILED, "privacy_exposure_summary_mismatch", privacy)
    if exposed:
        return _row("D12.privacy", FAILED, "forbidden_value_exposed", privacy)
    if not _reference(privacy):
        return _row("D12.privacy", UNKNOWN, "privacy_reference_missing", privacy)
    return _row("D12.privacy", PASSED, "public_dom_and_response_redacted", privacy)


def _strip_forbidden_references(rows: list[dict[str, Any]], privacy: Any) -> None:
    """即使调用方把标记写入引用，失败报告也不泄露该标记。"""
    forbidden = _list((_object(privacy) or {}).get("forbidden_values")) or []
    markers = [value for value in forbidden if isinstance(value, str) and value]
    if not markers:
        return
    for row in rows:
        row["references"] = [reference for reference in row["references"]
                             if all(marker not in reference["reference"] for marker in markers)]


def validate_process_evidence(evidence: Any) -> dict[str, Any]:
    """校验聊天过程证据，缺失产品事实时不猜测。

    返回的 ``assertions`` 只使用 ``passed``、``failed``、``unknown``。任一
    失败会使整包失败；没有失败但存在未知时整包为未知。引用是调用方给出的不透明
    标识，且返回内容不含被校验的公开文本或禁止数据。
    """
    document = _object(evidence)
    if document is None:
        rows = [_row("D09.identity", UNKNOWN, "evidence_document_missing")]
        return {"status": UNKNOWN, "assertions": rows, "references": []}
    session = _object(document.get("session"))
    session_id = session.get("session_id") if session else None
    if not isinstance(session_id, str) or not session_id:
        identity = _row("D09.identity", UNKNOWN, "session_identity_missing", session)
    elif not _reference(session):
        identity = _row("D09.identity", UNKNOWN, "session_reference_missing", session)
    else:
        identity = _row("D09.identity", PASSED, "session_identity_bound", session)
    artifacts = _object(document.get("artifacts")) or {}
    browser = _object(document.get("browser")) or {}
    case_id = document.get("case_id")
    if case_id in {"D09", "D10", "D11"}:
        processes, process_row = _process_row(artifacts.get("processes"))
        rows = [_deterministic_row(case_id, document.get("deterministic"))]
        if case_id == "D09": rows.extend([process_row, _delivery_row(artifacts.get("delivery"), processes)])
        if case_id == "D11":
            rows.extend(row for row in _waiting_and_child_rows(artifacts, [], session_id or "") if row["id"] != "D11.browser")
        _strip_forbidden_references(rows, document.get("privacy"))
        references = [reference for row in rows for reference in row["references"]]
        status = FAILED if any(row["status"] == FAILED for row in rows) else (
            UNKNOWN if any(row["status"] == UNKNOWN for row in rows) else PASSED)
        return {"status": status, "assertions": rows, "references": references}
    institution, institution_live = _require_live_client(browser.get("institution"), session_id or "", "institution")
    chat, chat_live = _require_live_client(browser.get("chat"), session_id or "", "chat")
    if case_id == "D12":
        rows = [identity, institution_live, chat_live,
                _history_row(artifacts.get("history"), session_id or "", [institution, chat]),
                _privacy_row(document.get("privacy"), artifacts, browser)]
        _strip_forbidden_references(rows, document.get("privacy"))
        references = [reference for row in rows for reference in row["references"]]
        status = FAILED if any(row["status"] == FAILED for row in rows) else (
            UNKNOWN if any(row["status"] == UNKNOWN for row in rows) else PASSED)
        return {"status": status, "assertions": rows, "references": references}
    processes, process_row = _process_row(artifacts.get("processes"))
    rows = [identity, institution_live, chat_live, process_row,
            _delivery_row(artifacts.get("delivery"), processes),
            _operation_row("D09.browser", [institution, chat],
                           ("process_interleave", "duplicate_replay", "out_of_order_replay")),
            _operation_row("D10.lifecycle", [institution, chat],
                           ("final_before_completion", "auto_collapse_once", "manual_expand"))]
    rows.extend(_waiting_and_child_rows(artifacts, [institution, chat], session_id or ""))
    rows.append(_history_row(artifacts.get("history"), session_id or "", [institution, chat]))
    rows.append(_privacy_row(document.get("privacy"), artifacts, browser))
    _strip_forbidden_references(rows, document.get("privacy"))
    references = [reference for row in rows for reference in row["references"]]
    status = FAILED if any(row["status"] == FAILED for row in rows) else (
        UNKNOWN if any(row["status"] == UNKNOWN for row in rows) else PASSED)
    return {"status": status, "assertions": rows, "references": references}


def _complete(rows: list[dict[str, Any]], privacy: Any) -> dict[str, Any]:
    """汇总断言并在返回前删除可能包含受限标记的引用。"""
    _strip_forbidden_references(rows, privacy)
    references = [reference for row in rows for reference in row["references"]]
    state = FAILED if any(row["status"] == FAILED for row in rows) else (
        UNKNOWN if any(row["status"] == UNKNOWN for row in rows) else PASSED)
    return {"status": state, "assertions": rows, "references": references}


def _live_client_row(case_id: str, client: Any, session_id: str, label: str) -> tuple[dict[str, Any] | None, dict[str, Any]]:
    """确认客户端确由实时浏览器绑定到目标会话。"""
    value = _object(client)
    assertion_id = f"{case_id}.{label}.live"
    if value is None:
        return None, _row(assertion_id, UNKNOWN, "live_client_missing")
    if value.get("mode") != "live":
        return value, _row(assertion_id, UNKNOWN, "client_not_live", value)
    if not session_id:
        return value, _row(assertion_id, UNKNOWN, "session_identity_unavailable", value)
    if value.get("session_id") != session_id:
        return value, _row(assertion_id, FAILED, "client_session_mismatch", value)
    if not _reference(value):
        return value, _row(assertion_id, UNKNOWN, "client_reference_missing")
    return value, _row(assertion_id, PASSED, "live_client_bound", value)


def _live_history_row(case_id: str, history: Any, session_id: str,
                      clients: list[dict[str, Any] | None], require_refresh: bool) -> dict[str, Any]:
    """核对两端读取的是同一实时会话且快照身份完全一致。"""
    paired = _object(history)
    institution = _object((paired or {}).get("institution"))
    chat = _object((paired or {}).get("chat"))
    if not institution or not chat:
        return _row(f"{case_id}.history", UNKNOWN, "paired_history_missing")
    for snapshot in (institution, chat):
        if snapshot.get("mode") != "live":
            return _row(f"{case_id}.history", UNKNOWN, "history_not_live", snapshot)
        if snapshot.get("session_id") != session_id:
            return _row(f"{case_id}.history", FAILED, "history_session_mismatch", snapshot)
        if not _reference(snapshot):
            return _row(f"{case_id}.history", UNKNOWN, "history_reference_missing", snapshot)
    for field in ("process_ids", "final_message_ids"):
        if not isinstance(institution.get(field), list) or (field == "process_ids" and not institution[field]):
            return _row(f"{case_id}.history", UNKNOWN, "institution_history_identity_incomplete", institution)
        if not isinstance(chat.get(field), list) or (field == "process_ids" and not chat[field]):
            return _row(f"{case_id}.history", UNKNOWN, "chat_history_identity_incomplete", chat)
        if institution[field] != chat[field]:
            return _row(f"{case_id}.history", FAILED, "paired_history_diverged", institution, chat)
    for client in clients:
        state, reason, refs = _operation(client, "history_read")
        if state != PASSED:
            return {"id": f"{case_id}.history", "status": state, "reason": reason, "references": refs}
        observed = _object((_object(client.get("operations")) or {}).get("history_read", {}).get("observed"))
        if require_refresh and observed.get("refreshed") is not True:
            return _row(f"{case_id}.history", UNKNOWN, "history_refresh_not_observed", client)
    return _row(f"{case_id}.history", PASSED, "same_live_session_history_read", institution, chat)


def _live_requests_row(case_id: str, requests: Any, session_id: str, institution: Any) -> dict[str, Any]:
    """验证机构端真实提交回执的动作序列和运行关联。"""
    expected = {
        "D09": ("message", "message"),
        "D10": ("message", "hitl"),
        "D11": ("message",),
        "D12": ("message", "cancel"),
    }[case_id]
    records = _list(requests)
    if records is None:
        return _row(f"{case_id}.requests", UNKNOWN, "institution_network_requests_missing", institution)
    if any(not isinstance(record, dict) for record in records):
        return _row(f"{case_id}.requests", UNKNOWN, "institution_network_request_malformed", institution)
    kinds = tuple(record.get("kind") for record in records)
    if kinds != expected:
        return _row(f"{case_id}.requests", FAILED, "institution_network_request_sequence_mismatch", institution)
    for record in records:
        if record.get("session_id") != session_id or not isinstance(record.get("run_id"), str) or not record["run_id"]:
            return _row(f"{case_id}.requests", UNKNOWN, "institution_network_request_identity_incomplete", institution)
        if record.get("kind") != "cancel" and (not isinstance(record.get("input_id"), str) or not record["input_id"]):
            return _row(f"{case_id}.requests", UNKNOWN, "institution_network_input_identity_incomplete", institution)
        terminal = record.get("terminal_event")
        refreshed_inflight = case_id == "D12" and record.get("kind") == "message" and record.get("reconnected") is True
        history_terminal = terminal == "history.run_terminal" and record.get("terminal_source") == "browser_history_response" \
            and record.get("terminal_status") in {"completed", "failed", "cancelled"}
        if terminal not in {"execution.completed", "hitl.required"} and not history_terminal and not refreshed_inflight:
            return _row(f"{case_id}.requests", UNKNOWN, "institution_terminal_observation_missing", institution)
    if case_id == "D10" and records[0]["run_id"] != records[1]["run_id"]:
        return _row(f"{case_id}.requests", FAILED, "hitl_resume_run_mismatch", institution)
    if case_id == "D12":
        if records[0]["run_id"] != records[1]["run_id"]:
            return _row(f"{case_id}.requests", FAILED, "cancel_run_mismatch", institution)
        if records[1].get("terminal_event") not in {"execution.completed", "history.run_terminal"} \
                or records[1].get("terminal_status") != "cancelled":
            return _row(f"{case_id}.requests", FAILED, "cancel_terminal_not_observed", institution)
    return _row(f"{case_id}.requests", PASSED, "institution_network_requests_observed", institution)


def _observed_operation_row(case_id: str, client: dict[str, Any] | None, name: str,
                            observed_key: str, expected: Any, reason: str) -> dict[str, Any]:
    """要求操作状态和其浏览器观察值同时成立。"""
    state, operation_reason, refs = _operation(client, name)
    if state != PASSED:
        return {"id": f"{case_id}.{name}", "status": state, "reason": operation_reason, "references": refs}
    operation = _object((_object(client.get("operations")) or {}).get(name))
    observed = _object((operation or {}).get("observed"))
    matches = callable(expected) and expected(observed.get(observed_key)) if observed is not None else False
    if not callable(expected):
        matches = observed is not None and observed.get(observed_key) == expected
    if not matches:
        return _row(f"{case_id}.{name}", UNKNOWN, reason, operation)
    return _row(f"{case_id}.{name}", PASSED, "browser_observation_confirmed", operation)


def validate_live_case_evidence(bundle: Any, case_id: str) -> dict[str, Any]:
    """验证单个业务场景的真实双端产品证据。

    确定性过程回放由 :func:`validate_process_evidence` 独立处理；本函数不把
    过程回放标签或调用方自填的布尔值当作真实浏览器验收结论。
    """
    document = _object(bundle)
    if case_id not in {"D09", "D10", "D11", "D12"}:
        return _complete([_row("live.case", UNKNOWN, "live_case_id_unsupported")], None)
    if document is None:
        return _complete([_row(f"{case_id}.identity", UNKNOWN, "evidence_document_missing")], None)
    session = _object(document.get("session"))
    session_id = session.get("session_id") if session else None
    if not isinstance(session_id, str) or not session_id:
        identity = _row(f"{case_id}.identity", UNKNOWN, "session_identity_missing", session)
    elif not _reference(session):
        identity = _row(f"{case_id}.identity", UNKNOWN, "session_reference_missing", session)
    else:
        identity = _row(f"{case_id}.identity", PASSED, "session_identity_bound", session)
    browser = _object(document.get("browser")) or {}
    institution, institution_live = _live_client_row(case_id, browser.get("institution"), session_id or "", "institution")
    chat, chat_live = _live_client_row(case_id, browser.get("chat"), session_id or "", "chat")
    artifacts = _object(document.get("artifacts")) or {}
    rows = [identity, institution_live, chat_live,
            _live_history_row(case_id, artifacts.get("history"), session_id or "", [institution, chat], case_id == "D12"),
            _live_requests_row(case_id, document.get("network_requests"), session_id or "", institution)]
    if case_id == "D09":
        rows.extend([
            _observed_operation_row(case_id, institution, "artifact_read", "artifact_count",
                                    lambda value: isinstance(value, int) and value > 0, "artifact_ui_count_not_observed"),
            _observed_operation_row(case_id, institution, "artifact_download", "downloaded", True, "artifact_download_not_observed"),
        ])
    if case_id == "D10":
        rows.append(_observed_operation_row(case_id, institution, "waiting_input_visible", "visible", True,
                                            "waiting_input_not_observed"))
    if case_id == "D12":
        rows.append(_observed_operation_row(case_id, institution, "cancel", "status", "cancelled",
                                            "cancel_terminal_not_observed"))
        rows.append(_privacy_row(document.get("privacy"), artifacts, browser))
    return _complete(rows, document.get("privacy"))
