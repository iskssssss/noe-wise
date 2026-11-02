"""分别捕获内部执行事实与公开输出，不以私有推理替代用户可见证据。"""
from __future__ import annotations

import json
import math
import re
from datetime import datetime

from behavior_contract import VERSION, digest

TERMINALS = {"completed", "failed", "cancelled", "terminated", "loop_terminated"}


def flatten(trace, *, internal=False):
    observed, events = 0, {}
    for page in trace.get("pages", []):
        for round_ in page.get("rounds", []):
            for run in round_.get("runs", []):
                for step in run.get("steps", []):
                    for phase in step.get("phases", []):
                        # 服务端为页边界/不适用阶段生成的占位行不是事件，不参与 eventId 去重。
                        if not phase.get("event_id"):
                            continue
                        observed += 1
                        eid = phase["event_id"]
                        content = trace.get("contents", {}).get(phase.get("content_ref"), {})
                        if internal and ("internal_contents" in trace or "internal_evidence" in trace):
                            content = trace.get("internal_contents", {}).get(phase.get("content_ref"), {})
                        row = {**phase, "run_id": run["run_id"], "action_id": step.get("action_id"),
                               "payload": content.get("value"), "availability": content.get("availability")}
                        if eid in events and events[eid].get("payload") != row.get("payload"):
                            raise ValueError("conflicting_event_identity")
                        events[eid] = row
    return observed, sorted(events.values(), key=lambda e: e.get("detail", {}).get("sessionSequence", 0))


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("ambiguous_answer_key")
        result[key] = value
    return result


def _finite_number(text):
    value = float(text)
    if not math.isfinite(value):
        raise ValueError("nonfinite_answer_number")
    return value


def _reject_constant(text):
    raise ValueError("invalid_json_constant")


def answer_from(events, run_id):
    completed = [e for e in events if e["event_type"] == "run.completed" and e["run_id"] == run_id]
    if not completed:
        return None, "", None
    event = completed[-1]
    payload = event.get("payload") or {}
    text = payload.get("outcome", {}).get("report", {}).get("output", "")
    if isinstance(text, dict):
        return text, json.dumps(text, ensure_ascii=False), event["event_id"]
    try:
        # 仅接受完整 JSON 对象或包住整个答复的一层展示围栏；原始证据保持不变。
        source = text.strip() if isinstance(text, str) else text
        if isinstance(source, str):
            fence = re.fullmatch(r"```(?:json)?[ \t]*\r?\n(.*?)\r?\n```", source, re.DOTALL)
            if fence:
                source = fence.group(1)
        answer = json.loads(source, object_pairs_hook=_unique_object,
                            parse_float=_finite_number, parse_constant=_reject_constant)
        return answer if isinstance(answer, dict) else None, text, event["event_id"]
    except (TypeError, ValueError):
        return None, str(text), event["event_id"]


def turn_answers_from(events, turns):
    """按持久Run身份和该轮截止序号绑定回答，HITL早期轮不能借用恢复后的回答。"""
    answers, event_ids = [], []
    for index in range(max((int(k) for k in turns), default=-1) + 1):
        terminal = turns.get(str(index), {}).get("result", {}).get("terminal", {})
        # 宿主 Outbox 与 Runtime 独立分配序号，只使用 Runtime 事件锚点确定截止。
        anchor = next((e for e in events if e.get("event_id") == terminal.get("runtime_event_id")
                       and e.get("run_id") == terminal.get("run_id")), None)
        cutoff = (anchor or {}).get("detail", {}).get("sessionSequence")
        visible = [e for e in events if cutoff is not None
                   and isinstance(e.get("detail", {}).get("sessionSequence"), int)
                   and e["detail"]["sessionSequence"] <= cutoff]
        answer, _, event_id = answer_from(visible, terminal.get("run_id"))
        answers.append(answer)
        event_ids.append(event_id)
    return answers, event_ids


def sequence_turn(sequence, ranges):
    """只认单一已闭合的审计序号区间；重叠、未闭合和迟到调用不猜测轮次。"""
    matches = [int(index) for index, bounds in ranges.items()
               if len(bounds) == 2 and bounds[1] is not None and bounds[0] < sequence <= bounds[1]]
    return matches[0] if len(matches) == 1 else None


def evidence(case, repetition, trace, audit, terminal, turn_ranges, cancellation=None, blockers=(), visible=None, unassigned=None,
             unassigned_turn_ranges=None):
    observed, events = flatten(trace, internal=True)
    _, public_events = flatten(trace)
    answer, answer_text, answer_event = answer_from(events, terminal.get("run_id"))
    _, public_answer_text, _ = answer_from(public_events, terminal.get("run_id"))
    calls = []
    for call in audit.get("calls", []):
        turn = sequence_turn(call["sequence"], dict(enumerate(turn_ranges)))
        calls.append({**call, "turn": turn, "event_id": f"fixture:{audit['case_key']}:{call['sequence']}",
                      "evidence_source": "mcp_fixture_audit"})
    for call in unassigned or []:
        turn = sequence_turn(call["sequence"], unassigned_turn_ranges or {})
        calls.append({**call, "turn": turn, "event_id": f"fixture:unassigned:{call['sequence']}",
                      "evidence_source": "mcp_fixture_audit"})
    snapshots = [e for e in events if e["event_type"] == "agent.context-snapshot"]
    terminal_events = [e for e in events if e["event_type"] == "run." + str(terminal.get("status"))
                       and e["run_id"] == terminal.get("run_id")]
    full = bool(events) and all(e["availability"] == "FULL" and e["payload"] is not None for e in events)
    full = full and bool(public_events) and all(e["payload"] is not None and
            e["availability"] in {"FULL", "REDACTED"} for e in public_events)
    # 只有已结束的调用才提供静止证据；在途调用不能因 API 返回终态而被丢弃。
    quiet = (terminal.get("status") in TERMINALS and all(c.get("completed_at") is not None for c in calls)
             and all(e.get("detail", {}).get("currentActionState") in
                     {None, "SUCCEEDED", "FAILED", "OUTCOME_UNKNOWN"} for e in events))
    usage = trace.get("usage", {})
    model_inputs = [{"event_id": e["event_id"], "model_input_hash": digest(e["payload"]),
                     "request": (e["payload"] or {}).get("modelRequest")} for e in snapshots if e["payload"]]
    attempts = {a["attemptId"]: a for e in events for a in e.get("detail", {}).get("attempts", []) if a.get("attemptId")}
    quiet = quiet and all(a.get("usage") and all(u.get("usageKnown") is True for u in a["usage"])
                          for a in attempts.values() if a.get("callKind") in {"MODEL_GENERATE", "MODEL_STREAM"})
    priced = [u for a in attempts.values() for u in a.get("usage", [])]
    return {"schema_version": VERSION, "format": "skillforge-behavior-evidence", "case_id": case["id"],
            "repetition": repetition, "terminal": terminal, "answer": answer,
            "answer_event_id": answer_event, "calls": calls, "write_events": audit.get("write_events", []),
            "events": events, "model_inputs": model_inputs,
            "execution_evidence": trace.get("execution_evidence", {}),
            "evidence_source": trace.get("internal_evidence", {"mode": "public_trace"}),
            "observable_output": {"answer": public_answer_text, "visible_events": visible or [],
                                  "arguments": [c["arguments"] for c in calls] +
                                               [e["detail"]["arguments"] for e in public_events if "arguments" in e.get("detail", {})],
                                  "public_trace": public_events,
                                  "model_outputs": [e["payload"] for e in public_events if e["event_type"] in
                                                    {"agent.model-result", "agent.decision-decode-failure", "agent.tool-result"}]},
            "cancellation": cancellation or {}, "blocking_reasons": list(blockers),
            "completeness": {"trace": full, "audit": "calls" in audit, "terminal": bool(terminal_events),
                             "context": bool(snapshots) and all((e["payload"] or {}).get("modelRequest") for e in snapshots),
                             "quiescence": quiet},
            "metrics": {"raw_observations": observed, "unique_events": len(events), "external_tool_attempts": len(calls),
                        "model_steps": len(snapshots), "usage": usage,
                        "attempts": list(attempts.values()),
                        "pricing_versions": sorted({str(u["pricingSnapshotVersion"]) for u in priced if u.get("pricingSnapshotVersion")}),
                        "pricing_complete": bool(priced) and all(u.get("pricingSnapshotVersion") for u in priced)}}


def cancellation_facts(trace, audit, barrier):
    _, events = flatten(trace, internal=True)
    requested = [e for e in events if e["event_type"] == "run.cancel_requested"]
    if not requested:
        return {"barrier_entered": bool(barrier.get("entered")), "cancel_persisted": False}
    request = requested[-1]
    seq = request["detail"]["sessionSequence"]
    timestamp = datetime.fromisoformat(request["occurred_at"].replace("Z", "+00:00")).timestamp()
    later = [e for e in events if e["detail"]["sessionSequence"] > seq]
    # 已发出调用的返回不是新决策；只检查新的准备快照及真实夹具入站。
    return {"barrier_entered": bool(barrier.get("entered")), "barrier_released": bool(barrier.get("released")),
            "cancel_persisted": True, "cancel_event_id": request["event_id"],
            "post_cancel_dispatches": sum(c["received_at"] > timestamp for c in audit["calls"]),
            "post_cancel_decisions": sum(e["event_type"] == "agent.context-snapshot" for e in later),
            "quiescent": all(c.get("completed_at") is not None for c in audit["calls"])
                         and any(e["event_type"] == "run.cancelled" for e in later)}
