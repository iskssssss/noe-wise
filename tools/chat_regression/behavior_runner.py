"""真实 HTTP 运行器：独立实验、配置监测、取消屏障及有界结算。"""
from __future__ import annotations

import concurrent.futures
import json
from pathlib import Path
import time
import uuid

from behavior_capture import TERMINALS, evidence, flatten, cancellation_facts, turn_answers_from
from behavior_contract import VERSION, EVALUATOR_VERSION, aggregate, digest, issue, now, transition, require, experiments
from behavior_environment import ConfigurationMonitor, configuration
from behavior_eval import evaluate_case
from behavior_measurements import enrich_report
from behavior_internal_evidence import collect_evaluation_trace
from behavior_model_contract import validate_model_requests
from behavior_suite import materialize
from client import ApiError, collect_trace, query, ModelQuotaStop
from runner import chat, save, read


class StopBatch(RuntimeError):
    def __init__(self, reason):
        self.reason = reason
        super().__init__(reason["code"])


class ExecutionBudget:
    def __init__(self, tokens, turns, seconds, clock=time.monotonic):
        require(all(type(n) is int and n > 0 for n in (tokens, turns, seconds)), "positive_budgets_required")
        self.max_tokens, self.max_turns, self.max_seconds = tokens, turns, seconds
        self.clock, self.started = clock, clock()
        self.turns = 0
        self.sessions = {}
        self.stopped = None

    def before_turn(self):
        if self.stopped:
            raise StopBatch(self.stopped)
        if self.turns >= self.max_turns or sum(self.sessions.values()) >= self.max_tokens or self.remaining() <= 0:
            raise StopBatch(issue("budget_exceeded", "new_input_budget_exhausted"))
        self.turns += 1

    def remaining(self):
        return self.max_seconds - (self.clock() - self.started)

    def observe(self, session, usage):
        if usage.get("unknown_usage_calls", 0) or type(usage.get("tokens")) is not int or usage["tokens"] < 0:
            self.stopped = issue("incomplete", "token_budget_observability_missing")
            raise StopBatch(self.stopped)
        self.sessions[session] = max(self.sessions.get(session, 0), usage["tokens"])
        if sum(self.sessions.values()) > self.max_tokens or self.remaining() <= 0:
            self.stopped = issue("budget_exceeded", "observed_budget_exceeded")
            raise StopBatch(self.stopped)

    def state(self):
        return {"turns": self.turns, "sessions": self.sessions, "max_tokens": self.max_tokens,
                "max_turns": self.max_turns, "max_seconds": self.max_seconds,
                "stop_reason": self.stopped,
                "elapsed_seconds": self.clock() - self.started}


def cancel(api, session, run_id):
    return api.request("POST", "/chat/runs/cancel", {"session_id": session, "run_id": run_id})


def read_run_status(api, session, run_id):
    """查询接口使用 execution_id，证据统一使用经身份校验的 run_id。"""
    status = api.request("GET", query("/chat/runs/status", session_id=session, run_id=run_id))
    require(status.get("execution_id") == run_id and status.get("session_id") == session,
            "status_response_identity_mismatch")
    return {**status, "run_id": run_id}


def monitored_chat(api, body, budget, monitor, invoke, stop_on_unknown=False):
    """在途阶段只回读用量；触线即请求取消，原请求继续结算且不得重新派发。"""
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(invoke)
        stopped_on_unknown = False
        started = time.monotonic()
        run_id = body.get("hitl_response", {}).get("execution_id") or "run:" + body["client_message_id"]
        def settled_unknown():
            return {"terminal": read_run_status(api, body["session_id"], run_id),
                    "seconds": time.monotonic() - started, "stop_policy": "cancel_after_recorded_tool_unknown"}
        while True:
            try:
                return future.result(timeout=min(3, max(0.1, budget.remaining())))
            except concurrent.futures.TimeoutError:
                if future.done():
                    try:
                        return future.result()
                    except (TimeoutError, ApiError):
                        if stopped_on_unknown:
                            return settled_unknown()
                        raise
                try:
                    trace = collect_trace(api, body["session_id"], include_content=False)
                    budget.observe(body["session_id"], trace.get("usage", {}))
                    if monitor.reasons:
                        raise StopBatch(monitor.reasons[0])
                    if stop_on_unknown and not stopped_on_unknown and any(
                            e.get("detail", {}).get("currentActionState") == "OUTCOME_UNKNOWN"
                            for e in flatten(trace)[1]):
                        # 套件已声明的控制动作：保留未知效果，再取消；绝不重发写操作。
                        cancel(api, body["session_id"], run_id)
                        stopped_on_unknown = True
                except StopBatch:
                    cancel(api, body["session_id"], body.get("hitl_response", {}).get("execution_id") or "run:" + body["client_message_id"])
                    raise
            except ApiError:
                if stopped_on_unknown:
                    return settled_unknown()
                raise


def cancellation_chat(api, fixture, body, case_key, folder, timeout):
    """只在屏障进入和取消日志提交后释放响应，不用 sleep 推断竞争窗口。"""
    started = time.monotonic()
    deadline = started + timeout
    barrier = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(chat, api, body, folder / "turn-00.sse.jsonl", timeout)
        try:
            while time.monotonic() < deadline:
                barrier = fixture.request("GET", query("/barrier", case_key=case_key))
                if barrier.get("entered"):
                    break
                if future.done():
                    return future.result(), barrier
                time.sleep(0.1)
            require(barrier.get("entered") is True, "cancel_barrier_not_entered")
            run_id = "run:" + body["client_message_id"]
            cancel(api, body["session_id"], run_id)
            persisted = False
            while time.monotonic() < deadline:
                trace = collect_trace(api, body["session_id"], include_content=False)
                persisted = any(e["event_type"] == "run.cancel_requested" for e in flatten(trace)[1])
                if persisted:
                    break
                time.sleep(0.1)
            require(persisted, "cancel_not_persisted")
        finally:
            barrier = fixture.request("POST", "/barrier/release", {"case_key": case_key})
        try:
            return future.result(timeout=max(1, deadline - time.monotonic())), barrier
        except (TimeoutError, ApiError):
            # 取消可能先关闭 SSE；取消已持久化后允许用同一 Run 的状态与完整轨迹结算。
            terminal = read_run_status(api, body["session_id"], run_id)
            return {"terminal": terminal, "seconds": time.monotonic() - started,
                    "transport_error": "cancel_stream_closed_status_fallback"}, barrier


def execute_case(api, fixture, plan, original, repetition, resources, root, budget, monitor):
    case, key = materialize(original, plan["batch"], repetition)
    folder = root / "cases" / case["id"] / str(repetition)
    folder.mkdir(parents=True, exist_ok=True)
    result_path = folder / "evaluation.json"
    if result_path.exists():
        return read(result_path)
    state_path = folder / "state.json"
    state = read(state_path) if state_path.exists() else {"turns": {}, "turn_ranges": []}
    profile = resources["agents"][case["profile"]]
    user_index = experiments(plan).index((case["id"], repetition))
    user = resources["users"][user_index]
    if "session_id" not in state:
        require(not state.get("session_creating"), "session_creation_outcome_unknown")
        state["session_creating"] = True
        save(state_path, state)
        value = api.request("POST", "/chat/sessions", {"agent_id": profile["id"], "end_user_id": user["id"]})
        state.update(session_id=value["session_id"], session_creating=False)
        save(state_path, state)
    sid = state["session_id"]
    if not state.get("fixture_created"):
        require(not state.get("fixture_creating"), "fixture_creation_outcome_unknown")
        state["fixture_creating"] = True
        save(state_path, state)
        fixture.request("POST", "/fixtures", {"case_key": key, "value": case["value"], "script": case["script"]})
        state.update(fixture_created=True, fixture_creating=False)
        save(state_path, state)
    else:
        fixture.request("GET", query("/audit", case_key=key))
    terminal, barrier, reasons = {}, {}, []
    content_cache = {}
    unassigned_start = fixture.request("GET", "/audit-unassigned")
    require(unassigned_start.get("complete") is True, "unassigned_audit_incomplete")
    unassigned_before = state.setdefault("unassigned_before",
        max((c["sequence"] for c in unassigned_start["calls"]), default=0))
    state.setdefault("unassigned_turn_ranges", {})
    save(state_path, state)
    for index, message in enumerate(case["turns"]):
        step = state["turns"].get(str(index))
        if step and step.get("result"):
            terminal = step["result"]["terminal"]
            continue
        if not monitor.check():
            raise StopBatch(monitor.reasons[0])
        budget.before_turn()
        body = step["request"] if step else {"content": [{"type": "text", "text": message}], "session_id": sid, "agent_id": profile["id"],
                 "client_message_id": uuid.uuid4().hex, "context": {"end_user_id": user["id"]}}
        if not step and index:
            previous = state["turns"][str(index - 1)]["result"]
            if previous.get("pending_hitl"):
                pending = previous["pending_hitl"]
                body["hitl_response"] = {k: pending[k] for k in ("execution_id", "pending_ref", "checkpoint_revision")}
                body["hitl_response"]["response"] = message
        before = len(fixture.request("GET", query("/audit", case_key=key))["calls"])
        rejected_before = fixture.request("GET", "/audit-unassigned")
        require(rejected_before.get("complete") is True, "unassigned_audit_incomplete")
        state["unassigned_turn_ranges"].setdefault(str(index),
            [max((c["sequence"] for c in rejected_before["calls"]), default=0), None])
        state["turns"][str(index)] = {"request": body, "dispatching": True}
        save(state_path, state)
        timeout = max(1, min(plan.get("local_selection", {}).get("turn_timeout_seconds", 540), budget.remaining()))
        active_run_id = body.get("hitl_response", {}).get("execution_id") or "run:" + body["client_message_id"]
        try:
            if case["cancel"] and not step:
                timing, barrier = monitored_chat(api, body, budget, monitor,
                    lambda: cancellation_chat(api, fixture, body, key, folder, timeout))
            else:
                timing = monitored_chat(api, body, budget, monitor,
                    lambda: chat(api, body, folder / f"turn-{index:02d}.sse.jsonl", timeout, resume=bool(step)),
                    stop_on_unknown=case["stop_on_unknown"])
            terminal = timing["terminal"]
            state["turns"][str(index)]["result"] = timing
            after = len(fixture.request("GET", query("/audit", case_key=key))["calls"])
            state["turn_ranges"].append([before, after])
            rejected_after = fixture.request("GET", "/audit-unassigned")
            require(rejected_after.get("complete") is True, "unassigned_audit_incomplete")
            state["unassigned_turn_ranges"][str(index)][1] = max(
                (c["sequence"] for c in rejected_after["calls"]), default=0)
            save(state_path, state)
        except ModelQuotaStop:
            raise StopBatch(issue("invalid_environment", "model_quota_or_rate_limit"))
        except StopBatch as stop:
            reasons.append(stop.reason)
            terminal = {"run_id": active_run_id, "status": "unknown"}
            break
        except (TimeoutError, OSError):
            cancel(api, sid, active_run_id)
            reasons.append(issue("budget_exceeded" if budget.remaining() <= 0 else "incomplete", "input_observation_deadline"))
            terminal = {"run_id": active_run_id, "status": "unknown"}
            break
        trace = collect_evaluation_trace(api, sid, collector=collect_trace, full_content=True, content_cache=content_cache)
        save(folder / f"turn-{index:02d}.trace.json", trace)
        try:
            budget.observe(sid, trace.get("usage", {}))
        except StopBatch as stop:
            reasons.append(stop.reason)
            break
        if trace.get("internal_evidence", {}).get("error"):
            reasons.append(issue("invalid_environment", "internal_evidence_collection_failed"))
            break
    # 结算截止独立于执行预算；只查询、收集已发调用，不再触发收费请求。
    deadline = time.monotonic() + plan["settlement_seconds"]
    snapshot = None
    while True:
        trace = collect_evaluation_trace(api, sid, collector=collect_trace, full_content=True, content_cache=content_cache)
        try:
            budget.observe(sid, trace.get("usage", {}))
        except StopBatch as stop:
            if stop.reason not in reasons:
                reasons.append(stop.reason)
        audit = fixture.request("GET", query("/audit", case_key=key))
        batch_audit = fixture.request("GET", "/audit-unassigned")
        unassigned = [c for c in batch_audit["calls"] if c["sequence"] > unassigned_before]
        if unassigned and not any(r["detail"] == "unattributed_tool_dispatch" for r in reasons):
            reasons.append(issue("failed_behavior", "unattributed_tool_dispatch"))
        if batch_audit.get("complete") is not True:
            reasons.append(issue("incomplete", "unassigned_audit_incomplete"))
        if terminal.get("run_id") and terminal.get("status") not in TERMINALS:
            terminal = read_run_status(api, sid, terminal["run_id"])
        cancellation = cancellation_facts(trace, audit, barrier) if case["cancel"] else None
        visible = [json.loads(line) for path in sorted(folder.glob("turn-*.sse.jsonl"))
                   for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
        snapshot = evidence(case, repetition, trace, audit, terminal, state["turn_ranges"], cancellation, reasons, visible, unassigned,
                            unassigned_turn_ranges=state["unassigned_turn_ranges"])
        if trace.get("internal_evidence", {}).get("error"):
            reason = issue("invalid_environment", "internal_evidence_collection_failed")
            if reason not in reasons:
                reasons.append(reason)
                snapshot["blocking_reasons"] = list(reasons)
            if snapshot["completeness"]["quiescence"]:
                break
        if all(snapshot["completeness"].values()) or time.monotonic() >= deadline:
            break
        time.sleep(0.25)
    snapshot["frozen_at"] = now()
    snapshot["turn_answers"], snapshot["turn_answer_event_ids"] = turn_answers_from(snapshot["events"], state["turns"])
    snapshot["metrics"]["unresolved_at_cutoff"] = sum(c.get("completed_at") is None for c in audit["calls"])
    snapshot["metrics"]["latency_seconds"] = sum(t.get("result", {}).get("seconds", 0) for t in state["turns"].values())
    save(folder / "trace.json", trace)
    # 保留真实模型/配置漂移；只接纳不可变准备来源证明的 Run 身份、预算和最终回答变化。
    validate_model_requests(snapshot, case["profile"], resources["resolved_model"], monitor, profile["id"])
    save(folder / "evidence.json", snapshot)
    result = evaluate_case(case, snapshot)
    save(result_path, result)
    if budget.stopped:
        raise StopBatch(budget.stopped)
    if any(r["code"] in {"budget_exceeded", "invalid_config_drift", "invalid_environment"} for r in result["blocking_reasons"]):
        raise StopBatch(next(r for r in result["blocking_reasons"] if r["code"] in {"budget_exceeded", "invalid_config_drift", "invalid_environment"}))
    return result


def run_batch(api, platform, fixture, plan, resources, root, revision, max_tokens, max_turns, max_seconds):
    root = Path(root)
    require(not (root / "batch.json").exists(), "batch_already_started_use_new_batch_after_diagnosis")
    require(len(resources["users"]) == len(experiments(plan)) and not resources.get("pending_operations"), "resources_incomplete")
    baseline = configuration(api, platform, resources)
    save(root / "configuration.json", baseline)
    budget = ExecutionBudget(max_tokens, max_turns, max_seconds)
    batch = {"lifecycle": "planned", "blocking_reasons": [], "started_at": now(), "budget": budget.state()}
    binding = {"deployment_revision": revision, "suite_version": plan["suite_version"],
               "evaluator_version": EVALUATOR_VERSION, "config_digest": digest(baseline),
               "model_id": str(resources["model_id"]), "model_version": None,
               "settlement_seconds": plan["settlement_seconds"], "prompt_hash": digest(plan["profiles"]),
               "tool_schema_version": "fixture-script-3",
               "evidence_mode": "local-postgres-readonly-v1" if vars(api).get("internal_evidence_reader") else "public_trace"}
    monitor = ConfigurationMonitor(lambda: configuration(api, platform, resources), baseline)
    transition(batch, "preparing")
    save(root / "batch.json", batch)
    results = []
    try:
        monitor.start()
        transition(batch, "running")
        for case_id, repetition in experiments(plan):
            case = next(c for c in plan["cases"] if c["id"] == case_id)
            if monitor.reasons:
                raise StopBatch(monitor.reasons[0])
            result = execute_case(api, fixture, plan, case, repetition, resources, root, budget, monitor)
            results.append(result)
            batch["budget"] = budget.state()
            save(root / "batch.json", batch)
            print(case["id"], repetition, result["behavior_status"], flush=True)
    except StopBatch as stop:
        batch["blocking_reasons"].append(stop.reason)
    except Exception as exc:
        status = getattr(exc, "status_code", None)
        detail = "runner_error:" + type(exc).__name__ + (":http_" + str(status) if isinstance(status, int) else "")
        batch["blocking_reasons"].append(issue("invalid_environment", detail))
    finally:
        transition(batch, "settling")
        monitor.close()
        batch["blocking_reasons"].extend(monitor.reasons)
        batch["budget"] = budget.state()
        batch["binding"] = binding
        batch["config_samples"] = monitor.samples
        batch["configuration_failures"] = monitor.failures
        save(root / "configuration-checks.json", {"samples": monitor.samples, "reasons": monitor.reasons,
                                                 "failures": monitor.failures})
        save(root / "batch.json", batch)
    # 调用方确认其拥有的夹具已静止后才能 finalized。
    return batch


def execution_metrics(root, report, batch):
    """将冻结证据的展示计量写入报告副本，不触碰其判定字段。"""
    enriched = enrich_report(root, report)
    report["metrics"] = enriched["metrics"]


def final_report(root, plan, batch, cleanup_verified):
    root = Path(root)
    if not cleanup_verified:
        batch["blocking_reasons"].append(issue("invalid_environment", "fixture_cleanup_not_verified"))
    if batch["lifecycle"] == "settling":
        transition(batch, "finalized")
    save(root / "batch.json", batch)
    results = [read(path) for path in sorted((root / "cases").glob("*/*/evaluation.json"))]
    report = aggregate(plan, results, batch["binding"], batch["blocking_reasons"])
    execution_metrics(root, report, batch)
    save(root / "evaluation.json", report)
    return report


def collect_late(api, root):
    """冻结后的补充观察永不覆盖证据和结论；迟到数量按 eventId 集合差记录。"""
    root = Path(root)
    batch = read(root / "batch.json")
    require(batch["lifecycle"] == "finalized", "late_collection_requires_finalized_batch")
    rows = []
    for path in sorted((root / "cases").glob("*/*/evidence.json")):
        frozen = read(path)
        state = read(path.parent / "state.json")
        trace = collect_evaluation_trace(api, state["session_id"], collector=collect_trace, full_content=True)
        require(not trace.get("internal_evidence", {}).get("error"), "internal_evidence_collection_failed")
        _, events = flatten(trace, internal=True)
        known = {e["event_id"] for e in frozen["events"]}
        late = [e for e in events if e["event_id"] not in known]
        rows.append({"case_id": frozen["case_id"], "repetition": frozen["repetition"],
                     "frozen_at": frozen["frozen_at"], "late_events": late,
                     "late_event_count": len(late), "late_rate": len(late) / len(events) if events else None,
                     "latest_session_usage": trace.get("usage", {})})
    result = {"observed_at": now(), "binding": batch["binding"], "cases": rows,
              "verdict_unchanged": True}
    save(root / "late" / (uuid.uuid4().hex + ".json"), result)
    return result
