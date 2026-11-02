"""C06 正式 Workflow HITL 夹具；仅 runner 显式调用时才请求正式 API。"""
from __future__ import annotations

import json
from pathlib import Path

from behavior_product_resources import _provision_workflow
from client import collect_trace
from provision import _JOURNAL_CONTEXT
from runner import events_for, save

PROFILE = {"key": "c06-workflow", "skills": [], "tools": [], "prompt":
           "只处理当前合成验收。必须运行挂载的人工确认工作流；在人工确认前等待，"
           "不得声称已完成。只在原执行恢复并返回结果后汇报，不重新运行工作流。"}
MARKER = "product-workflow-confirmed"


def prepare(api, resources, root):
    """复用正式创建/验证/发布/安装/挂载 API；调用方随后发布该 Agent。"""
    root = Path(root)
    agent = resources["agents"][PROFILE["key"]]
    path = root / "c06" / "resources.json"
    case = resources.get("c06_workflow")
    if case is None:
        case = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {
            "pending_operations": [], "agents": {"d10-workflow": agent}}
        resources["c06_workflow"] = case
    if str(case["agents"]["d10-workflow"]["id"]) != str(agent["id"]):
        raise ValueError("c06_workflow_agent_drift")
    agent.setdefault("mounts", {}).update(case["agents"]["d10-workflow"].get("mounts", {}))
    case["agents"]["d10-workflow"] = agent
    token = _JOURNAL_CONTEXT.set((case, path))
    try:
        _provision_workflow(api, case, path, str(resources.get("batch", root.name)) + "-c06")
    finally:
        _JOURNAL_CONTEXT.reset(token)
    return {"profile": PROFILE, "workflow": case["workflow"], "mounts": agent["mounts"],
            "agent_publish_required": True, "resources_path": str(path)}


def _status(turn):
    return str((turn.get("terminal") or {}).get("status") or turn.get("status", "")).lower()


def hitl_response(pending, text):
    """与当前 PendingForm 一致：Workflow 使用原动作及类型化响应，不伪造 pending_ref。"""
    if pending.get("workflow_execution_id"):
        keys = ("execution_id", "run_revision", "checkpoint_revision", "workflow_execution_id",
                "suspend_ref", "expected_revision", "resume_action_key")
        if any(pending.get(key) is None for key in keys):
            raise ValueError("c06_workflow_hitl_identity_missing")
        return {**{key: pending[key] for key in keys}, "action": pending["resume_action_key"],
                "response": {"schema_id": "application/json", "canonical_json": json.dumps(
                    {"action": "confirm", "response": text}, ensure_ascii=False, separators=(",", ":"))}}
    keys = ("execution_id", "pending_ref", "checkpoint_revision")
    if any(pending.get(key) is None for key in keys):
        raise ValueError("c06_hitl_identity_missing")
    return {**{key: pending[key] for key in keys}, "response": text}


def _workflow(events):
    return {event["event_id"]: event for event in events
            if event.get("event_type") == "agent.workflow-result" and event.get("event_id")
            and isinstance(event.get("payload"), dict)}


def validate_case(evidence):
    """只用持久事件与回执判定；答案正文不证明工作流执行。"""
    turns = evidence.get("turns", [])
    if len(turns) != 2:
        return {"status": "UNKNOWN", "reason": "c06_two_turn_evidence_missing"}
    first, last = turns
    pending = first.get("pending_hitl") or {}
    try:
        hitl_response(pending, "确认继续")
    except ValueError:
        return {"status": "FAILED", "reason": "c06_did_not_wait_for_confirmation"}
    if _status(first) != "waiting_hitl":
        return {"status": "FAILED", "reason": "c06_did_not_wait_for_confirmation"}
    if (_status(last) != "completed" or not first.get("run_id")
            or pending["execution_id"] != first["run_id"] or first["run_id"] != last.get("run_id")):
        return {"status": "FAILED", "reason": "c06_did_not_resume_original_run"}
    before, after = _workflow(first.get("rich_events", [])), _workflow(last.get("rich_events", []))
    if any(row["payload"].get("status") == "COMPLETED" for row in before.values()):
        return {"status": "FAILED", "reason": "c06_completed_before_confirmation"}
    all_events = {**before, **after}
    completed = [row for row in all_events.values() if row["payload"].get("status") == "COMPLETED"]
    ids = {(row["payload"].get("executionRef") or {}).get("executionId") for row in all_events.values()}
    ids.discard(None)
    if len(completed) != 1 or len(ids) != 1:
        return {"status": "FAILED" if completed or len(ids) > 1 else "UNKNOWN", "reason": "c06_workflow_execution_not_exactly_once"}
    if MARKER not in json.dumps(completed[0]["payload"], ensure_ascii=False):
        return {"status": "UNKNOWN", "reason": "c06_expected_workflow_output_unproven"}
    replay = evidence.get("replay") or {}
    if not replay.get("receipt") or not replay.get("same_request") or not replay.get("snapshot_verified"):
        return {"status": "UNKNOWN", "reason": "c06_duplicate_confirmation_unproven"}
    if any(not isinstance(replay.get(key), list) or not replay[key] for key in
           ("before_runs", "after_runs", "before_workflow", "after_workflow")):
        return {"status": "UNKNOWN", "reason": "c06_duplicate_snapshot_missing"}
    if replay["receipt"].get("execution_id") != first["run_id"]:
        return {"status": "FAILED", "reason": "c06_duplicate_receipt_run_mismatch"}
    if replay.get("before_runs") != replay.get("after_runs") or replay.get("before_workflow") != replay.get("after_workflow"):
        return {"status": "FAILED", "reason": "c06_duplicate_confirmation_created_execution"}
    return {"status": "PASS", "reason": "c06_wait_resume_and_duplicate_receipt_verified",
            "wrong_revision_and_rejection": "DETERMINISTIC_LAYER_ONLY", "external_write": "NOT_IN_FIXTURE"}


def _snapshot(api, session_id, message_id, run_id):
    trace = collect_trace(api, session_id, full_content=True)
    runs = sorted({run["run_id"] for page in trace["pages"] for round_ in page["rounds"] for run in round_["runs"]})
    events = events_for(trace, message_id, run_id)
    return {"runs": runs, "workflow": sorted(_workflow(events)), "events": events}


def run_case(api, *, agent_id, end_user_id, root, send_turn, session_id=None):
    """两次真实派发后，仅重放已完成确认的同一 POST 正文，不再调用模型 runner。"""
    folder = Path(root) / "c06"
    path = folder / "state.json"
    state = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"turns": []}
    if state.get("pending"):
        raise RuntimeError("c06_unresolved_write_outcome_do_not_retry")
    if state.get("agent_id", agent_id) != agent_id or state.get("end_user_id", end_user_id) != end_user_id:
        raise ValueError("c06_owner_drift")
    state.update(agent_id=agent_id, end_user_id=end_user_id)
    if session_id is not None:
        if state.get("session_id", session_id) != session_id: raise ValueError("c06_session_drift")
        state["session_id"] = session_id
    if "session_id" not in state:
        state["pending"] = "create_session"; save(path, state)
        session = api.request("POST", "/chat/sessions", {"agent_id": agent_id, "end_user_id": end_user_id})
        state.update(session_id=session["session_id"], pending=None); save(path, state)
    for index, text in enumerate(("运行已挂载的人工确认工作流，等待我的确认后才完成。", "确认继续")):
        if len(state["turns"]) > index: continue
        options = {}
        if index:
            pending = state["turns"][0].get("pending_hitl") or {}
            options["hitl_response"] = hitl_response(pending, text)
        state["pending"] = "turn:" + str(index); save(path, state)
        turn = send_turn(session_id=state["session_id"], agent_id=agent_id, end_user_id=end_user_id,
                         text=text, turn=index, **options)
        state["turns"].append(turn); state["pending"] = None; save(path, state)
        if index == 0 and _status(turn) != "waiting_hitl": break
    if len(state["turns"]) == 2 and not state.get("replay") and _status(state["turns"][1]) == "completed":
        last = state["turns"][1]
        request_path = Path(last.get("folder", folder)) / "request.json"
        if request_path.exists():
            body = json.loads(request_path.read_text(encoding="utf-8"))
            if body.get("session_id") != state["session_id"] or body.get("hitl_response", {}).get("execution_id") != last.get("run_id"):
                raise ValueError("c06_original_confirmation_request_mismatch")
            before = _snapshot(api, state["session_id"], last["message_id"], last["run_id"])
            state["pending"] = "replay_confirmation"; save(path, state)
            receipt = api.request("POST", "/chat", body)
            state["pending"] = None
            state["replay"] = {"receipt": receipt, "same_request": True, "snapshot_verified": False}; save(path, state)
            after = _snapshot(api, state["session_id"], last["message_id"], last["run_id"])
            state["replay"].update(snapshot_verified=True, before_runs=before["runs"], after_runs=after["runs"],
                                   before_workflow=before["workflow"], after_workflow=after["workflow"])
            save(path, state)
    result = validate_case(state); save(folder / "result.json", result)
    return {**result, "evidence": str(path), "session_id": state["session_id"]}
