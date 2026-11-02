"""有界并发执行；以用户为串行单元保持跨会话测试前置条件。"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import threading
import time
import uuid
from datetime import datetime
import urllib.request
from urllib.parse import urlsplit, urljoin

from client import Api, ApiError, ModelQuotaStop, quota_signal, collect_trace, is_execution_terminal, poll_terminal, query, sse_events


SUCCESSFUL_SKILL_STATUSES = frozenset(("ACTIVATED", "ALREADY_ACTIVE"))


def save(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(path)


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def events_for(trace, mid, run_id=None, after_sequence=0):
    found = {}
    for page in trace["pages"]:
        for r in page["rounds"]:
            for run in r["runs"]:
                if run["run_id"] != (run_id or "run:" + mid):
                    continue
                for step in run["steps"]:
                    for phase in step["phases"]:
                        if int(phase.get("detail", {}).get("sessionSequence", 0)) <= after_sequence and after_sequence:
                            continue
                        eid = phase.get("event_id")
                        if eid:
                            content = trace["contents"].get(phase.get("content_ref"), {})
                            found[eid] = {**phase, "payload": content.get("value"),
                                          "content_availability": content.get("availability")}
    return list(found.values())


def successful_skill_activations(facts):
    """只计入持久激活结果确认成功的 Skill，不以事件存在代替成功。"""
    activations = []
    for event in facts:
        if event["event_type"] != "skill.activation":
            continue
        payload = event.get("payload")
        if not isinstance(payload, dict):
            continue
        for result in payload.get("results", []):
            if isinstance(result, dict) and result.get("status") in SUCCESSFUL_SKILL_STATUSES:
                activations.append(result)
    return activations


def failure_codes_for(facts):
    """保留稳定且非空的外部失败码，避免停机证据出现空值或重复项。"""
    codes = []
    for fact in facts:
        detail = fact.get("detail", {})
        for attempt in detail.get("attempts", []):
            codes.append(attempt.get("externalErrorCode"))
        codes.append(detail.get("reasonCode"))
    return list(dict.fromkeys(str(code).strip() for code in codes if str(code or "").strip()))


def has_tool_error(facts):
    """MCP 错误可以是类型化 FAILED 回执，此时没有 output 正文。"""
    for fact in facts:
        if fact["event_type"] != "agent.tool-result":
            continue
        payload = fact.get("payload") or {}
        if payload.get("status") == "FAILED" and payload.get("reasonCode") == "mcp_tool_rejected":
            return True
        output = (payload.get("output") or {}).get("canonicalJson")
        if output and json.loads(output).get("isError") is True:
            return True
    return False


def evaluate(expect, terminal, facts, pending=None):
    errors = []
    waiting = terminal.get("status") == "waiting_hitl" and expect.get("allow_hitl", False)
    if terminal.get("status") != expect["status"] and not waiting:
        errors.append("terminal:" + str(terminal))
    finals = [e["payload"] for e in facts if e["event_type"] == "run.completed" and e["payload"]]
    if waiting:
        valid = pending and pending.get("execution_id") == terminal.get("run_id") and pending.get("pending_ref") and pending.get("checkpoint_revision") is not None and pending.get("question")
        if not valid or not any(e["event_type"] == "run.suspended" for e in facts):
            errors.append("missing_committed_hitl")
    elif not finals:
        errors.append("missing_committed_completion")
    reply = finals[-1].get("outcome", {}).get("report", {}).get("output", "") if finals else ""
    if waiting: reply = (pending or {}).get("question", "")
    if not isinstance(reply, str): reply = json.dumps(reply, ensure_ascii=False)
    tools = [e for e in facts if e["event_type"] == "agent.tool-result"]
    skills = successful_skill_activations(facts)
    if "turn_mode" in expect:
        modes = [e["payload"].get("turnMode") for e in facts if e["event_type"] == "agent.task-context" and e["payload"]]
        if not modes or any(mode != expect["turn_mode"] for mode in modes): errors.append("turn_mode_mismatch")
    for token in expect.get("contains", []):
        if token not in reply: errors.append("missing_literal:" + token)
    for token in expect.get("excludes", []):
        if token in reply: errors.append("forbidden_literal:" + token)
    if len(tools) < expect.get("min_tools", 0): errors.append("missing_tool_call")
    if "runtime_diagnostic_kind" in expect:
        kinds = {
            event["payload"].get("kind")
            for event in facts
            if event["event_type"] == "agent.runtime-diagnostic"
            and isinstance(event.get("payload"), dict)
        }
        if expect["runtime_diagnostic_kind"] not in kinds:
            errors.append("missing_runtime_diagnostic:" + expect["runtime_diagnostic_kind"])
    if "min_parallel_tools" in expect:
        groups = {}
        for event in tools:
            payload = event.get("payload") or {}
            operation = payload.get("operation")
            if not isinstance(operation, dict) or not operation:
                continue
            key = json.dumps(operation, sort_keys=True)
            groups.setdefault(key, set()).add(payload.get("invocationId"))
        if max((len(values - {None}) for values in groups.values()), default=0) < expect["min_parallel_tools"]:
            errors.append("missing_same_operation_tool_batch")
    if len(tools) > expect.get("max_tools", 10**9): errors.append("unexpected_tool_call")
    if len(skills) < expect.get("min_skills", 0): errors.append("missing_successful_skill_activation")
    if "json_equals" in expect:
        try:
            if json.loads(reply) != expect["json_equals"]: errors.append("json_value_mismatch")
        except (ValueError, TypeError): errors.append("invalid_json_reply")
    if "fault_mode" not in expect:
        for e in tools:
            p = e["payload"] or {}
            if p.get("status") != "SUCCEEDED": errors.append("tool_not_succeeded")
    return {"errors": errors, "reply": reply, "tool_results": len(tools), "skill_activations": len(skills)}


def phase_times(facts):
    """仅汇总有真实起止时间的外调；按attempt去重，不混为端到端耗时。"""
    attempts = {}
    for event in facts:
        for attempt in event.get("detail", {}).get("attempts", []):
            if attempt.get("attemptId"):
                attempts[attempt["attemptId"]] = attempt
    totals = {}
    unknown = 0
    for attempt in attempts.values():
        start, end = attempt.get("externalStartedAt"), attempt.get("externalEndedAt")
        if not start or not end:
            unknown += 1
            continue
        elapsed = (datetime.fromisoformat(end.replace("Z", "+00:00")) - datetime.fromisoformat(start.replace("Z", "+00:00"))).total_seconds()
        kind = attempt.get("callKind", "UNKNOWN")
        totals[kind] = totals.get(kind, 0) + max(0, elapsed)
    return {"external_seconds_by_call_kind": totals, "attempts_without_external_timing": unknown}


class Budget:
    """上限在轮次边界生效；在途请求保留证据，不伪装为被取消。"""
    def __init__(self, seconds, max_turns, max_tokens, failure_limit):
        self.deadline = time.monotonic() + seconds
        self.max_turns, self.max_tokens = max_turns, max_tokens
        self.failure_limit = failure_limit
        self.lock = threading.Lock()
        self.turns = 0
        self.tokens = {}
        self.failures = 0
        self.stopped = threading.Event()

    def acquire(self):
        with self.lock:
            if self.stopped.is_set(): raise ModelQuotaStop("batch stopped")
            if time.monotonic() >= self.deadline or self.turns >= self.max_turns or sum(self.tokens.values()) >= self.max_tokens or self.failures >= self.failure_limit:
                raise RuntimeError("batch_budget_or_failure_limit")
            self.turns += 1

    def observe(self, session, usage, failed):
        with self.lock:
            if "tokens" in usage:
                self.tokens[session] = usage["tokens"]
            self.failures += int(failed)


def chat(api, body, path, timeout, disconnect=False, resume=False, output_cursor=None):
    started = time.monotonic()
    deadline = started + timeout
    first_output = None
    terminal = None
    disconnected = False
    transport_error = None
    pending = None
    expected_run = body.get("hitl_response", {}).get("execution_id") or "run:" + body["client_message_id"]
    accepted = False
    def emit(event):
        nonlocal first_output, pending
        if event["event"] == "hitl.required" and event["data"].get("execution_id") == expected_run:
            pending = event["data"]
        row = {"elapsed_seconds": round(time.monotonic() - started, 4), **event}
        if event["event"] == "message.delta" and event["data"].get("content") and first_output is None:
            first_output = row["elapsed_seconds"]
        with path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    if not resume:
        try:
            receipt = api.request("POST", "/chat", body)
            expected_run = receipt.get("execution_id") or expected_run
            headers = {"Accept": "text/event-stream"}
            if output_cursor:
                headers["Last-Event-ID"] = output_cursor
            with api.open("GET", query("/chat/events", session_id=body["session_id"]),
                          headers=headers, timeout=timeout) as response:
                for event in sse_events(response, deadline):
                    if event.get("id"):
                        output_cursor = event["id"]
                    if (event["event"] == "execution.accepted"
                            and event["data"].get("input_id") == body["client_message_id"]
                            and event["data"].get("run_id") == expected_run):
                        accepted = True
                    if not accepted or event["data"].get("run_id") != expected_run:
                        continue
                    emit(event)
                    if is_execution_terminal(event["event"], event["data"]):
                        terminal = event["data"]
                        break
                    if disconnect and event.get("id"):
                        disconnected = True
                        break
        except ModelQuotaStop:
            raise
        except (ApiError, OSError, TimeoutError, ValueError) as e:
            transport_error = type(e).__name__
            emit({"event": "probe.transport_error", "data": {"type": type(e).__name__}})
        if terminal is None and not disconnected and transport_error is None:
            transport_error = "eof_without_terminal"
    if terminal is None:
        terminal = poll_terminal(api, body["session_id"], body["client_message_id"], deadline, emit,
                                 cursor=output_cursor, expected_run=expected_run, accepted=accepted)
    return {"terminal": terminal, "seconds": round(time.monotonic() - started, 4),
            "first_output_seconds": first_output, "deliberate_disconnect": disconnected, "resumed": resume,
            "transport_error": transport_error, "pending_hitl": pending}


def verify_artifact(api, sid, mid, expected):
    artifacts = api.request("GET", query("/chat/sessions/artifacts", sessionId=sid))
    for art in artifacts:
        if art["run_id"] != "run:" + mid: continue
        for entry in art["files"]:
            if entry["path"] != expected["path"]: continue
            ticket = api.request("POST", "/chat/artifacts/download-ticket", {"id": art["id"], "payload": {"path": entry["path"], "zip": False}})
            url = urlsplit(ticket["url"])
            target = urlsplit(urljoin(api.base, ticket["url"]))
            if target.scheme not in ("http", "https") or target.hostname not in ("localhost", "127.0.0.1", "::1"):
                raise ApiError("本地测试仅下载loopback产物；不转发机构认证头")
            # 票据自身授权，独立下载端口不接收机构 Bearer。
            with urllib.request.urlopen(target.geturl(), timeout=30) as response:
                data = response.read(1024 * 1024 + 1)
            digest = hashlib.sha256(data).hexdigest()
            return {"id": art["id"], "path": entry["path"], "sha256": digest,
                    "valid": digest == entry["sha256"] and len(data) == entry["size"] and data == expected["text"].encode()}
    return {"valid": False, "reason": "expected_artifact_missing"}


def run_case(api, fixture, case, resources, root, timeout, budget):
    folder = root / "cases" / case["id"]
    folder.mkdir(parents=True, exist_ok=True)
    statefile = folder / "state.json"
    state = read(statefile) if statefile.exists() else {"turns": {}}
    if (folder / "result.json").exists(): return read(folder / "result.json")
    if case["category"] == "cross_session" and case["phase"] == 1:
        prerequisite = root / "cases" / (case["id"][:-1] + "0") / "result.json"
        if not prerequisite.exists() or read(prerequisite)["status"] != "passed":
            result = {"case_id":case["id"], "category":case["category"], "profile":case["profile"],
                      "status":"blocked", "reason":"prerequisite_not_passed", "turns":[]}
            save(folder / "result.json", result)
            return result
    if state.get("session_creating"):
        raise RuntimeError("session_creation_outcome_unknown: inspect resources before resuming")
    if "session_id" not in state:
        state["session_creating"] = True
        save(statefile, state)
        session = api.request("POST", "/chat/sessions", {"agent_id": resources["agents"][case["profile"]]["id"], "end_user_id": resources["users"][case["user"]]["id"]})
        state.update(session_id=session["session_id"], session_creating=False)
        save(statefile, state)
    sid = state["session_id"]
    if not state.get("fixture_created"):
        fixture.request("POST", "/fixtures", case["fixture"])
        state["fixture_created"] = True
        save(statefile, state)
    else:
        # 夹具为进程内状态；重启丢失审计后禁止继续发出聊天再伪造基线。
        fixture.request("GET", query("/audit", case_key=case["fixture"]["case_key"]))
    for extra in case.get("additional_fixtures", []):
        created = state.setdefault("additional_fixtures_created", [])
        if extra["case_key"] not in created:
            fixture.request("POST", "/fixtures", extra)
            created.append(extra["case_key"])
            save(statefile, state)
        else:
            fixture.request("GET", query("/audit", case_key=extra["case_key"]))
    reports = []
    for index, spec in enumerate(case["turns"]):
        key = str(index)
        resultfile = folder / f"turn-{index:02d}.json"
        if resultfile.exists():
            reports.append(read(resultfile))
            if reports[-1]["errors"]: break
            continue
        budget.acquire()
        existing = state["turns"].get(key)
        mid = existing["client_message_id"] if existing else str(uuid.uuid4())
        body = {"content": [{"type": "text", "text": spec["message"]}], "session_id": sid,
                "agent_id": resources["agents"][case["profile"]]["id"], "client_message_id": mid,
                "context": {"end_user_id": resources["users"][case["user"]]["id"]}}
        if reports and reports[-1].get("pending_hitl"):
            pending = reports[-1]["pending_hitl"]
            body["hitl_response"] = {k:pending[k] for k in ("execution_id", "pending_ref", "checkpoint_revision")}
            body["hitl_response"]["response"] = spec["message"]
        if existing: body = existing["request"]
        state["turns"][key] = {"client_message_id": mid, "request": body, "state": "dispatching"}
        save(statefile, state)
        timing = chat(api, body, folder / f"turn-{index:02d}.sse.jsonl", min(timeout,max(1,budget.deadline-time.monotonic())), spec["expect"].get("disconnect", False), resume=bool(existing), output_cursor=reports[-1]["terminal"].get("event_id") if reports else None)
        previous_path = folder / f"turn-{index-1:02d}.trace.json"
        trace = collect_trace(api, sid, read(previous_path) if previous_path.exists() else None)
        save(folder / f"turn-{index:02d}.trace.json", trace)
        previous_trace = read(previous_path) if previous_path.exists() else {}
        facts = events_for(trace, mid, run_id=timing["terminal"].get("run_id"), after_sequence=int(previous_trace.get("cursor", 0)))
        assessment = evaluate(spec["expect"], timing["terminal"], facts, timing.get("pending_hitl"))
        if timing["transport_error"]:
            assessment["errors"].append("unexpected_transport_error:" + timing["transport_error"])
        # 宿主可能隐藏供应商错误正文；模型/任务维护失败也先停，核实后再决定是否恢复。
        failure_codes = failure_codes_for(facts)
        reason = str(timing["terminal"].get("reason", ""))
        if any(quota_signal(c) for c in failure_codes + [reason]) or (
            timing["terminal"].get("status") == "failed" and any(k in reason.lower() for k in ("model", "maintenance"))):
            assessment["errors"].append("model_failure_or_quota_requires_review")
            assessment.update(timing, message_id=mid, session_id=sid, index=index, usage=trace["usage"], phase_timing=phase_times(facts))
            save(resultfile, assessment)
            budget.stopped.set()
            save(root / "stop.json", {"reason":"model_failure_or_quota_requires_review", "case_id":case["id"],
                                      "message_id":mid,"terminal":timing["terminal"],"codes":failure_codes})
            raise ModelQuotaStop("模型失败或额度信号，已停止后续聊天")
        audit = fixture.request("GET", query("/audit", case_key=case["fixture"]["case_key"]))
        assessment["fixture_audit"] = audit
        if case.get("additional_fixtures"):
            assessment["additional_fixture_audits"] = [
                fixture.request("GET", query("/audit", case_key=extra["case_key"]))
                for extra in case["additional_fixtures"]
            ]
        if "fault_mode" in spec["expect"]:
            if audit["call_counts"].get("fault_probe", 0) < 1:
                assessment["errors"].append("fault_probe_not_executed")
            if spec["expect"]["fault_mode"] == "error":
                if not has_tool_error(facts):
                    assessment["errors"].append("expected_tool_error_missing")
                if not any(word in assessment["reply"] for word in ["失败", "错误", "异常", "无法"]):
                    assessment["errors"].append("tool_error_not_reported")
        if "write_value" in spec["expect"]:
            writes = audit["write_events"]
            if len(writes) != 1 or writes[0]["value"] != spec["expect"]["write_value"]:
                assessment["errors"].append("write_effect_mismatch")
        if "artifact" in spec["expect"]:
            assessment["artifact"] = verify_artifact(api, sid, mid, spec["expect"]["artifact"])
            if not assessment["artifact"]["valid"]: assessment["errors"].append("artifact_invalid")
        if spec["expect"].get("duplicate"):
            before = fixture.request("GET", query("/audit", case_key=case["fixture"]["case_key"]))
            replay = chat(api, body, folder / f"turn-{index:02d}.duplicate.sse.jsonl", timeout)
            after = fixture.request("GET", query("/audit", case_key=case["fixture"]["case_key"]))
            assessment["duplicate"] = {"before": before, "after": after, "terminal": replay["terminal"]}
            if before != after: assessment["errors"].append("duplicate_request_reexecuted_tool")
            if replay["terminal"].get("status") != timing["terminal"].get("status"):
                assessment["errors"].append("duplicate_terminal_mismatch")
        assessment.update(timing, message_id=mid, session_id=sid, index=index, usage=trace["usage"], phase_timing=phase_times(facts))
        save(resultfile, assessment)
        reports.append(assessment)
        budget.observe(sid, trace["usage"], bool(assessment["errors"]))
        if assessment["errors"]: break
    result = {"case_id": case["id"], "category": case["category"], "profile": case["profile"],
              "session_id": sid, "status": "passed" if len(reports) == len(case["turns"]) and all(not r["errors"] for r in reports) else "failed",
              "planned_turns": len(case["turns"]), "turns": reports}
    result["fixture_audit"] = fixture.request("GET", query("/audit", case_key=case["fixture"]["case_key"]))
    save(folder / "result.json", result)
    return result


def execute(api, fixture, plan, resources, root, limit, concurrency, timeout, budget):
    # 同一用户跨智能体及跨会话按计划顺序执行，其他用户可并行。
    users = {}
    # 断点续跑时先恢复已观察的会话用量，避免每次运行重新获得全额token预算。
    for path in (root / "cases").glob("*/turn-*.json"):
        if path.name.endswith(".trace.json"): continue
        previous = read(path)
        if "session_id" in previous:
            sid = previous["session_id"]
            budget.tokens[sid] = max(budget.tokens.get(sid, 0), previous.get("usage", {}).get("tokens", 0))
    for case in plan["cases"][:limit]: users.setdefault(case["user"], []).append(case)
    def lane(cases):
        for case in cases:
            if budget.stopped.is_set(): break
            try:
                result = run_case(api, fixture, case, resources, root, timeout, budget)
                print(case["id"], result["status"], flush=True)
            except Exception as e:
                if isinstance(e, ModelQuotaStop):
                    budget.stopped.set()
                    if not (root / "stop.json").exists():
                        save(root / "stop.json", {"reason":"quota_or_rate_limit", "case_id":case["id"],"error":str(e)})
                with budget.lock:
                    budget.failures += 1
                # 未知结果保持 state；续跑只查询原消息，不重发。
                save(root / "cases" / case["id"] / "interruption.json", {"type": type(e).__name__, "reason": str(e)[:300]})
                print(case["id"], "interrupted", type(e).__name__, flush=True)
                break
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        list(pool.map(lane, users.values()))
