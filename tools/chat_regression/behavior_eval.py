"""只从捕获证据判定行为；缺少事实返回 unknown，不从最终文本反推工具执行。"""
from __future__ import annotations

import json
import re
from decimal import Decimal, InvalidOperation
from urllib.parse import urlsplit, urlunsplit, unquote_plus

from behavior_contract import issue, require, validate_assertion, version

MISSING = object()


def pointer(value, path):
    if path == "":
        return value
    if not isinstance(path, str) or not path.startswith("/"):
        return MISSING
    try:
        for part in path[1:].split("/"):
            key = part.replace("~1", "/").replace("~0", "~")
            value = value[int(key)] if isinstance(value, list) else value[key]
        return value
    except (KeyError, TypeError, ValueError, IndexError):
        return MISSING


def normalize_url(value, ignored=()):
    if not isinstance(value, str):
        raise ValueError("url_string_required")
    p = urlsplit(value)
    if p.scheme.lower() not in {"http", "https"} or not p.hostname or p.username or p.password:
        raise ValueError("invalid_source_url")
    host = p.hostname.lower()
    host = "[" + host + "]" if ":" in host else host
    port = p.port
    if port and (p.scheme.lower(), port) not in {("http", 80), ("https", 443)}:
        host += ":" + str(port)
    # 未声明参数保持原字节顺序和编码；不把路径/fragment 归一化掉。
    query = "&".join(part for part in p.query.split("&") if unquote_plus(part.split("=", 1)[0]) not in ignored)
    return urlunsplit((p.scheme.lower(), host, p.path, query, p.fragment))


def _calls(evidence, selector):
    return [c for c in evidence.get("calls", [])
            if (not selector.get("tool") or c.get("tool") == selector["tool"])
            and ("turn" not in selector or c.get("turn") == selector["turn"])
            and ("case_key" not in selector or (isinstance(c.get("arguments"), dict)
                                               and c["arguments"].get("case_key") == selector["case_key"]))]


def _refs(calls):
    return [{"event_id": c["event_id"], "pointer": "/arguments"} for c in calls if c.get("event_id")]


def _answer_refs(evidence, field):
    # 原始回答可能根本不是 JSON；引用已存在的输出正文，再标注被检查的规范化字段。
    turn = re.fullmatch(r"/turn_answers/(\d+)(?:/.*)?", field)
    if turn:
        ids = evidence.get("turn_answer_event_ids", [])
        index = int(turn[1])
        event_id = ids[index] if index < len(ids) else None
        return ([{"event_id": event_id, "pointer": "/outcome/report/output", "field": field}]
                if event_id and any(e["event_id"] == event_id for e in evidence.get("events", [])) else [])
    return ([{"event_id": evidence["answer_event_id"], "pointer": "/outcome/report/output", "field": field}]
            if evidence.get("answer_event_id") else [])


def _json_equal(actual, expected):
    if type(actual) is not type(expected):
        return False
    if isinstance(actual, dict):
        return actual.keys() == expected.keys() and all(_json_equal(actual[k], expected[k]) for k in actual)
    if isinstance(actual, list):
        return len(actual) == len(expected) and all(_json_equal(a, b) for a, b in zip(actual, expected))
    return actual == expected


def evaluate_assertion(a, e):
    validate_assertion(a)
    result = {"id": a["id"], "evaluator": a["evaluator"], "blocking": a["blocking"],
              "status": "unknown", "reason": "manual_review_required", "evidence": []}
    if a["evaluator"] != "code":
        return result
    missing = [r for r in a["evidence_requirements"] if e.get("completeness", {}).get(r) is not True]
    if missing:
        return {**result, "reason": "missing_evidence:" + ",".join(missing)}
    kind, expected, options, selector = a["kind"], a["expected"], a["options"], a["selector"]
    execution_states = {"tool_attempted": "attempted", "tool_refused": "refused",
                        "execution_succeeded": "succeeded", "execution_failed": "failed", "execution_unknown": "unknown"}
    if kind in execution_states:
        captured = e.get("execution_evidence", {})
        run = captured.get("runs", {}).get(e.get("terminal", {}).get("run_id"), {})
        if captured.get("source") != "execution-adopted-materials-v1" or not run:
            return {**result, "reason": "execution_evidence_missing"}
        selected = [c for c in run["calls"] if not selector.get("tool") or c["tool"] == selector["tool"]]
        requested = [c for c in selected if c["state"] == "attempted"]
        state = execution_states[kind]
        complete = run["models_complete"] if state in {"attempted", "refused"} else run["tools_complete"]
        if state == "refused":
            complete = complete and bool(requested) and all(c["refused"] for c in requested)
            matches = [c for c in requested if c["refused"]]
        else:
            matches = [c for c in selected if c["state"] == state]
        # 零匹配不能证明没有请求/拒绝/执行；缺失、未采纳与无后继材料保留未知。
        if not complete or not matches:
            return {**result, "reason": "execution_evidence_incomplete"}
        refs = [{"event_id": c["event_id"], "pointer": c["pointer"], "source": c["source"],
                 "run_id": c["run_id"], "action_id": c["action_id"], "attempt_id": c["attempt_id"],
                 "call_id": c["call_id"], "receipt_ids": c.get("receipt_ids", [])} for c in matches]
        passed = expected.get("min", 0) <= len(matches) <= expected.get("max", len(matches))
        return {**result, "status": "pass" if passed else "fail",
                "reason": "satisfied" if passed else "constraint_mismatch", "evidence": refs}
    calls = _calls(e, selector)
    refs = _refs(calls)
    if "turn" in selector and kind in {"tool_count", "arguments", "order", "fixture_succeeded", "fixture_failed"}:
        candidates = _calls(e, {k: v for k, v in selector.items() if k != "turn"})
        unresolved = [c for c in candidates if c.get("turn") is None]
        if unresolved:
            return {**result, "reason": "tool_turn_evidence_missing", "evidence": _refs(unresolved)}
    actual = pointer(e, selector.get("pointer", ""))
    if selector.get("pointer", "").startswith("/turn_answers/") and not _answer_refs(e, selector["pointer"]):
        return {**result, "reason": "turn_answer_evidence_missing"}
    passed = False
    reason = "constraint_mismatch"
    try:
        if kind == "tool_count":
            actual = len(calls)
            passed = expected.get("min", 0) <= actual <= expected.get("max", actual)
        elif kind in {"fixture_succeeded", "fixture_failed"}:
            # 夹具回执证明 MCP 入站执行结果，不证明 Runtime 已采用输出或业务任务成功。
            if e.get("completeness", {}).get("audit") is not True:
                return {**result, "reason": "missing_evidence:audit"}
            unresolved = [c for c in calls if c.get("evidence_source") != "mcp_fixture_audit"
                          or c.get("completed_at") is None or c.get("status") not in {"completed", "error"}]
            if unresolved:
                return {**result, "reason": "tool_result_evidence_missing", "evidence": [
                    {"event_id": c["event_id"], "pointer": "/status", "source": c.get("evidence_source")}
                    for c in unresolved if c.get("event_id")]}
            wanted = "completed" if kind == "fixture_succeeded" else "error"
            matched = [c for c in calls if c["status"] == wanted]
            actual = len(matched)
            passed = expected.get("min", 0) <= actual <= expected.get("max", actual)
            refs = [{"event_id": c["event_id"], "pointer": "/status",
                     "source": "mcp_fixture_audit"} for c in calls if c.get("event_id")]
        elif kind == "arguments":
            actual = [c.get("arguments") for c in calls]
            passed = bool(calls) and all(all(pointer(c.get("arguments"), p) == v for p, v in expected.items()) for c in calls)
        elif kind == "number":
            if actual is MISSING:
                return {**result, "status": "fail", "reason": "invalid_answer_field",
                        "evidence": _answer_refs(e, selector["pointer"])}
            if type(actual) not in (int, float, Decimal):
                passed = False
            else:
                number, target = Decimal(str(actual)), Decimal(str(expected))
                passed = number.is_finite() and target.is_finite() and abs(number - target) <= max(
                    Decimal(str(options.get("abs_tol", 0))), Decimal(str(options.get("rel_tol", 0))) * abs(target))
        elif kind == "equals":
            if actual is MISSING:
                return {**result, "status": "fail", "reason": "invalid_answer_field",
                        "evidence": _answer_refs(e, selector["pointer"])}
            passed = _json_equal(actual, expected)
        elif kind == "url":
            if actual is MISSING:
                return {**result, "status": "fail", "reason": "invalid_answer_field",
                        "evidence": _answer_refs(e, selector["pointer"])}
            ignored = options.get("ignore_query_parameters", [])
            allowed = [expected, *options.get("aliases", [])]
            passed = normalize_url(actual, ignored) in {normalize_url(x, ignored) for x in allowed}
        elif kind == "absent":
            if actual is MISSING:
                return {**result, "reason": "missing_target"}
            text = json.dumps(actual, ensure_ascii=False, allow_nan=False)
            passed = all(token not in text for token in expected)
            # 私密值只在受限证据中存在，判定文本不回显 expected/actual。
        elif kind == "terminal":
            actual = e.get("terminal", {}).get("status")
            passed = actual in expected
        elif kind == "tool_unknown":
            # 只接受本 Run 的终态事件及其精确未知 fact 链；模型正文中的“未知”不构成事实。
            matches = []
            for event in e.get("events", []):
                payload, detail = event.get("payload") or {}, event.get("detail") or {}
                if (event.get("event_type") != "action.outcome_unknown"
                        or event.get("run_id") != e.get("terminal", {}).get("run_id")
                        or payload.get("state") != "OUTCOME_UNKNOWN" or payload.get("reasonCode") != expected
                        or payload.get("actionRef", {}).get("actionId") != event.get("action_id")
                        or detail.get("currentActionState") != "OUTCOME_UNKNOWN"
                        or detail.get("callKind") != "TOOL_INVOKE"):
                    continue
                if any(a.get("callKind") == "TOOL_INVOKE" and a.get("status") == "UNRESOLVED"
                       and a.get("outcomeResolution") == "OUTCOME_UNKNOWN"
                       and a.get("outcomeType") == "OutcomeUnknown"
                       and a.get("factRef") and a["factRef"] == payload.get("outcomeFactRef")
                       and a.get("invocationId") and a["invocationId"] == payload.get("invocationId")
                       and a.get("attemptId") == detail.get("attemptId")
                       for a in detail.get("attempts", [])):
                    matches.append(event)
            passed = bool(matches)
            refs = [{"event_id": v["event_id"], "pointer": "/outcomeFactRef"} for v in matches]
        elif kind == "effects":
            actual = len(e.get("write_events", []))
            passed = expected["min"] <= actual <= expected["max"]
        elif kind == "order":
            actual = [c["tool"] for c in calls]
            cursor = 0
            for tool in actual:
                if cursor < len(expected) and tool == expected[cursor]:
                    cursor += 1
            passed = cursor == len(expected)
        elif kind == "any_of":
            paths = [[evaluate_assertion(child, e) for child in path] for path in expected]
            winner = next((p for p in paths if all(c["status"] == "pass" for c in p)), None)
            if winner is not None:
                passed = True
                refs = [r for child in winner for r in child["evidence"]]
            elif any(all(c["status"] != "fail" for c in p) for p in paths):
                return {**result, "reason": "path_evidence_incomplete"}
        elif kind == "cancelled":
            cancellation = e.get("cancellation", {})
            required = ("barrier_entered", "cancel_persisted", "barrier_released", "quiescent")
            if not all(cancellation.get(k) is True for k in required):
                return {**result, "reason": "cancel_evidence_incomplete"}
            passed = (e.get("terminal", {}).get("status") == "cancelled"
                      and cancellation.get("post_cancel_dispatches") == 0
                      and cancellation.get("post_cancel_decisions") == 0)
        else:
            raise ValueError("unsupported_code_assertion:" + kind)
    except (ValueError, InvalidOperation, TypeError):
        reason = "invalid_observed_value"
        passed = False
    if selector.get("pointer"):
        refs.extend(_answer_refs(e, selector["pointer"]))
    return {**result, "status": "pass" if passed else "fail", "reason": "satisfied" if passed else reason, "evidence": refs}


def evaluate_case(case, evidence):
    version(evidence, "skillforge-behavior-evidence")
    require(evidence.get("case_id") == case["id"] and type(evidence.get("repetition")) is int
            and 1 <= evidence["repetition"] <= 100, "evidence_identity_mismatch")
    require(isinstance(evidence.get("completeness"), dict) and
            set(evidence["completeness"]) == {"trace", "audit", "terminal", "context", "quiescence"}
            and all(type(v) is bool for v in evidence["completeness"].values()), "invalid_completeness_contract")
    require(all(isinstance(evidence.get(k), list) for k in ("calls", "events", "write_events", "blocking_reasons")), "invalid_evidence_lists")
    require(all(isinstance(evidence.get(k), dict) for k in ("terminal", "observable_output", "metrics")), "invalid_evidence_objects")
    event_ids = [e.get("event_id") for e in evidence["events"]]
    require(all(isinstance(i, str) and i for i in event_ids) and len(event_ids) == len(set(event_ids)), "invalid_event_identity")
    results = [evaluate_assertion(a, evidence) for a in case["assertions"]]
    hard = [r for r in results if r["blocking"]]
    failed = any(r["status"] == "fail" for r in hard) or any(r["code"] == "failed_behavior" for r in evidence["blocking_reasons"])
    unknown = any(r["status"] == "unknown" for r in hard) or not all(evidence["completeness"].values())
    blockers = list(evidence.get("blocking_reasons", []))
    if failed:
        blockers.append(issue("failed_behavior", "hard_assertion_failed"))
    if unknown:
        blockers.append(issue("incomplete", "required_assertion_evidence_missing"))
    metrics = dict(evidence.get("metrics", {}))
    metrics["efficiency_warnings"] = (["model_step_budget_exceeded"]
        if metrics.get("model_steps", 0) > max(2 * case["max_attempts"] + 4,
                                             len(case["turns"]) + 2 * case["max_attempts"]) else [])
    return {"case_id": case["id"], "repetition": evidence["repetition"],
            "behavior_status": "failed" if failed else "unknown" if unknown else "passed",
            "blocking_reasons": blockers, "assertions": results, "metrics": metrics}
