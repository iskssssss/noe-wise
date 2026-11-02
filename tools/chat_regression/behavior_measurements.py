"""从已冻结的行为评测证据构造展示与导出用计量。

本模块只读取 ``plan.json``、``batch.json``、case state/evidence 和 SSE 记录。它
不重新查询运行时，也不改变评测的断言、阻断原因或发布结论。
"""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime
from decimal import Decimal, InvalidOperation
import json
import math
from pathlib import Path
import re
from typing import Any


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return None


def _number(value: Any) -> int | float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        return None
    return value


def _timestamp(event: dict[str, Any], missing: str) -> dict[str, Any]:
    value = event.get("occurred_at")
    if not isinstance(value, str) or not value:
        return {"status": "missing", "reason": missing}
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return {"status": "missing", "reason": "event_timestamp_invalid"}
    # 时间字符串归属服务端时钟，不能和客户端 SSE elapsed_seconds 相减。
    return {"status": "observed", "at": value, "event_id": event.get("event_id")}


def _client_delta(folder: Path) -> dict[str, Any]:
    def turn_file_key(path: Path) -> tuple[int, int, str]:
        match = re.fullmatch(r"turn-(\d+)\.sse\.jsonl", path.name)
        return (0, int(match.group(1)), path.name) if match else (1, 0, path.name)

    paths = sorted(folder.glob("turn-*.sse.jsonl"), key=turn_file_key)
    if not paths:
        return {"status": "missing", "reason": "sse_not_recorded"}
    saw_delta = False
    for path in paths:
        match = re.fullmatch(r"turn-(\d+)\.sse\.jsonl", path.name)
        location = {"source_file": path.name, "turn": int(match.group(1)) if match else None}
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except OSError:
            return {"status": "missing", "reason": "sse_file_unreadable", **location}
            continue
        for line in lines:
            try:
                row = json.loads(line)
            except ValueError:
                return {"status": "missing", "reason": "sse_record_invalid", **location}
            if row.get("event") != "message.delta":
                continue
            saw_delta = True
            data = row.get("data")
            if not isinstance(data, dict) or not data.get("content"):
                continue
            elapsed = _number(row.get("elapsed_seconds"))
            if elapsed is None or elapsed < 0:
                return {"status": "missing", "reason": "sse_elapsed_seconds_invalid", **location}
            return {"status": "observed", "seconds": elapsed, **location}
    return {"status": "missing", "reason": "no_nonempty_message_delta" if saw_delta else "message_delta_not_recorded"}


def _first_model_event(events: list[Any]) -> dict[str, Any]:
    """只接受 Runtime 的 FIRST_MODEL_EVENT 里程碑，不能从尝试或结果事件倒推。"""
    event = next((item for item in events if isinstance(item, dict)
                  and item.get("event_type") == "agent.runtime-diagnostic"
                  and isinstance(item.get("payload"), dict)
                  and isinstance(item["payload"].get("metrics"), dict)
                  and item["payload"]["metrics"].get("milestone") == "FIRST_MODEL_EVENT"), None)
    if event is None:
        return {"status": "missing", "reason": "first_model_event_milestone_not_recorded"}
    elapsed = event["payload"]["metrics"].get("elapsedNanos")
    if isinstance(elapsed, bool) or not isinstance(elapsed, (int, str)):
        return {"status": "missing", "reason": "first_model_event_elapsed_nanos_missing",
                "event_id": event.get("event_id")}
    try:
        elapsed = int(elapsed)
    except ValueError:
        return {"status": "missing", "reason": "first_model_event_elapsed_nanos_invalid",
                "event_id": event.get("event_id")}
    if elapsed < 0:
        return {"status": "missing", "reason": "first_model_event_elapsed_nanos_invalid",
                "event_id": event.get("event_id")}
    return {"status": "observed", "elapsed_nanos": elapsed, "event_id": event.get("event_id"),
            "source": "agent.runtime-diagnostic:FIRST_MODEL_EVENT"}


def _timing(case_id: str, repetition: int, folder: Path, evidence: dict[str, Any]) -> dict[str, Any]:
    events = evidence.get("events") if isinstance(evidence.get("events"), list) else []
    terminal = evidence.get("terminal") if isinstance(evidence.get("terminal"), dict) else {}
    run_id = terminal.get("run_id")
    terminal_event = next((event for event in events if isinstance(event, dict)
                           and event.get("event_type") == "run." + str(terminal.get("status"))
                           and event.get("run_id") == run_id), None)
    cancelled = next((event for event in events if isinstance(event, dict)
                      and event.get("event_type") == "run.cancel_requested"), None)
    cancellation_confirmed = next((event for event in events if isinstance(event, dict)
                                   and event.get("event_type") == "run.cancelled"), None)
    completeness = evidence.get("completeness") if isinstance(evidence.get("completeness"), dict) else {}
    frozen_at = evidence.get("frozen_at")
    if completeness.get("quiescence") is True and isinstance(frozen_at, str) and frozen_at:
        quiescence = {"status": "observed_upper_bound", "at": frozen_at,
                      "reason": "quiescence_observed_at_evidence_freeze"}
    elif completeness.get("quiescence") is True:
        quiescence = {"status": "missing", "reason": "evidence_frozen_at_not_recorded"}
    else:
        quiescence = {"status": "missing", "reason": "quiescence_not_observed_at_freeze"}
    return {"case_id": case_id, "repetition": repetition,
            "client_first_nonempty_message_delta": _client_delta(folder),
            "model_first_event": _first_model_event(events),
            "terminal_event": _timestamp(terminal_event, "terminal_event_timestamp_missing") if terminal_event else
                              {"status": "missing", "reason": "terminal_event_not_recorded"},
            "cancel_requested": _timestamp(cancelled, "cancel_timestamp_missing") if cancelled else
                                {"status": "missing", "reason": "cancel_not_requested"},
            "cancellation_confirmed": _timestamp(cancellation_confirmed, "cancel_confirmation_timestamp_missing")
                                      if cancellation_confirmed else
                                      {"status": "missing", "reason": "cancel_not_confirmed"},
            "quiescence_upper_bound": quiescence}


def _usage_rows(evidence: dict[str, Any], seen: set[str]) -> tuple[list[dict[str, Any]], int]:
    attempts = evidence.get("metrics", {}).get("attempts", []) if isinstance(evidence.get("metrics"), dict) else []
    rows, unidentified = [], 0
    if not isinstance(attempts, list):
        return rows, unidentified
    for attempt in attempts:
        if not isinstance(attempt, dict):
            continue
        attempt_id = attempt.get("attemptId") or attempt.get("attempt_id")
        usages = attempt.get("usage", [])
        if not isinstance(usages, list):
            continue
        for usage in usages:
            if not isinstance(usage, dict):
                unidentified += 1
                continue
            event_id = usage.get("eventId") or usage.get("event_id")
            identity = "event:" + str(event_id) if event_id else (
                "attempt:" + str(attempt_id) if attempt_id else None)
            if identity is None:
                unidentified += 1
                continue
            if identity in seen:
                continue
            seen.add(identity)
            rows.append({**usage, "_attempt_id": attempt_id, "_identity": identity})
    return rows, unidentified


def _attempt_diagnostics(evidence: dict[str, Any], seen: set[str]) -> dict[str, Any]:
    """按 attempt 身份统计调用与重试，不从重复轨迹快照重复计数。"""
    attempts = evidence.get("metrics", {}).get("attempts", []) if isinstance(evidence.get("metrics"), dict) else []
    calls: dict[str, int] = {}
    retry_sources: dict[str, int] = {}
    without_identity = 0
    if not isinstance(attempts, list):
        return {"calls_by_kind": calls, "unique_attempts": 0, "attempts_without_identity": 0,
                "retry_attempts": 0, "retry_sources": retry_sources, "retry_source_unknown": 0}
    unique, retries, retry_unknown = 0, 0, 0
    for attempt in attempts:
        if not isinstance(attempt, dict):
            without_identity += 1
            continue
        attempt_id = attempt.get("attemptId") or attempt.get("attempt_id")
        if not isinstance(attempt_id, str) or not attempt_id:
            without_identity += 1
            continue
        if attempt_id in seen:
            continue
        seen.add(attempt_id)
        unique += 1
        kind = attempt.get("callKind") if isinstance(attempt.get("callKind"), str) else "UNKNOWN"
        calls[kind] = calls.get(kind, 0) + 1
        retry_number = attempt.get("attemptNumber", attempt.get("attempt_number"))
        is_retry = bool(attempt.get("retryOfAttemptId") or attempt.get("retry_of_attempt_id")
                        or attempt.get("retrySource") or attempt.get("retry_source")
                        or attempt.get("retryReason") or attempt.get("retry_reason")
                        or (isinstance(retry_number, int) and retry_number > 1))
        if is_retry:
            retries += 1
            source = (attempt.get("retrySource") or attempt.get("retry_source")
                      or attempt.get("retryReason") or attempt.get("retry_reason"))
            if not isinstance(source, str) or not source:
                retry_unknown += 1
            else:
                retry_sources[source] = retry_sources.get(source, 0) + 1
    return {"calls_by_kind": dict(sorted(calls.items())), "unique_attempts": unique,
            "attempts_without_identity": without_identity, "retry_attempts": retries,
            "retry_sources": dict(sorted(retry_sources.items())), "retry_source_unknown": retry_unknown}


def _token_total(rows: list[dict[str, Any]], names: tuple[str, ...], *, derive_total: bool = False) -> int | None:
    values: list[int] = []
    for row in rows:
        if row.get("usageKnown") is not True:
            return None
        value = next((row.get(name) for name in names if row.get(name) is not None), None)
        if derive_total and value is None:
            left, right = _number(row.get("inputTokens", row.get("promptTokens"))), _number(row.get("outputTokens", row.get("completionTokens")))
            value = left + right if isinstance(left, int) and isinstance(right, int) else None
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            return None
        values.append(value)
    return sum(values) if rows else None


def _decimal(value: Any) -> Decimal | None:
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None
    return result if result.is_finite() else None


def _string_amount(value: Decimal) -> str:
    return format(value, "f")


def _cost(rows: list[dict[str, Any]], passed: int, unidentified: int) -> dict[str, Any]:
    amounts: dict[str, Decimal] = {}
    minor_units: dict[str, int] = {}
    unknown, priced = unidentified, 0
    prices, sources = set(), set()
    for row in rows:
        source = row.get("usageSource")
        if isinstance(source, str) and source:
            sources.add(source)
        price = row.get("pricingSnapshotVersion")
        if isinstance(price, str) and price:
            prices.add(price)
        currency = row.get("currency")
        if not isinstance(currency, str) or not currency:
            unknown += 1
            continue
        amount = _decimal(row.get("totalAmount")) if row.get("totalAmount") is not None else None
        minor = row.get("totalAmountCents")
        if amount is not None:
            amounts[currency] = amounts.get(currency, Decimal(0)) + amount
            priced += 1
        elif isinstance(minor, int) and not isinstance(minor, bool):
            minor_units[currency] = minor_units.get(currency, 0) + minor
            priced += 1
        else:
            unknown += 1
    total = {currency: _string_amount(value) for currency, value in sorted(amounts.items())}
    per_success = ({currency: _string_amount(value / passed) for currency, value in sorted(amounts.items())}
                   if passed and unknown == 0 else None)
    minor_per_success = ({currency: value / passed for currency, value in sorted(minor_units.items())}
                         if passed and unknown == 0 else None)
    if not rows and unidentified == 0:
        status, reason = "missing", "usage_not_recorded"
    elif unknown:
        status, reason = "unknown", "unpriced_or_unknown_usage"
    elif passed == 0:
        status, reason = "known", "no_successful_samples"
    else:
        status, reason = "known", None
    return {"status": status, "reason": reason, "priced_usage_events": priced,
            "unpriced_or_unknown_usage_events": unknown, "pricing_snapshot_versions": sorted(prices),
            "usage_sources": sorted(sources), "total_amount_by_currency": total,
            "total_amount_minor_units_by_currency": dict(sorted(minor_units.items())),
            "cost_per_success_by_currency": per_success,
            "cost_per_success_minor_units_by_currency": minor_per_success}


def _cache_measurement(rows: list[dict[str, Any]], unidentified: int) -> dict[str, Any]:
    """缓存 token 是 input 的子集；缺失时明确说明而非以零代替。"""
    reasons: list[str] = []
    if not rows:
        reasons.append("usage_not_recorded")
    if unidentified:
        reasons.append("usage_identity_missing")
    for row in rows:
        if row.get("usageKnown") is not True:
            reasons.append("usage_unknown")
        elif row.get("cachedInputTokens") is None:
            reasons.append("cached_input_tokens_not_recorded")
        elif row.get("cacheWriteInputTokens") is None:
            reasons.append("cache_write_input_tokens_not_recorded")
    unique_reasons = sorted(set(reasons))
    return {"status": "known" if not unique_reasons else "unknown",
            "unknown_reasons": unique_reasons,
            "cached_input_is_input_subset": True,
            "cache_write_input_is_input_subset": True}


def _comparison_context(plan: dict[str, Any], batch: dict[str, Any], report: dict[str, Any]) -> dict[str, Any]:
    valid_cases = isinstance(plan.get("cases"), list)
    cases = plan.get("cases", []) if valid_cases else []
    local = plan.get("local_selection", {}) if isinstance(plan.get("local_selection"), dict) else {}
    selected = local.get("case_ids") if isinstance(local.get("case_ids"), list) else [
        case.get("id") for case in cases if isinstance(case, dict) and isinstance(case.get("id"), str)]
    repetitions = local.get("repetitions", plan.get("repetitions"))
    matrix = [{"case_id": case_id, "repetition": repetition}
              for case_id in selected if isinstance(case_id, str)
              for repetition in range(1, repetitions + 1)] if valid_cases and isinstance(repetitions, int) and repetitions > 0 else None
    binding = report.get("binding", {}) if isinstance(report.get("binding"), dict) else {}
    policy = binding.get("execution_policy") if isinstance(binding.get("execution_policy"), dict) else {}
    workers = policy.get("workers", local.get("workers", 1))
    tool_versions = sorted({str(case.get("tool_schema_version")) for case in cases
                            if isinstance(case, dict) and case.get("id") in selected and case.get("tool_schema_version")})
    # profile 中可能有动态 prompt 或私密业务文本；没有明确可去除的随机身份时不伪造语义等价。
    profile_keys = sorted({str(case.get("profile")) for case in cases
                           if isinstance(case, dict) and case.get("id") in selected and case.get("profile")})
    return {"selection": {"case_ids": list(selected) if valid_cases else None,
                          "repetitions": repetitions if valid_cases else None, "matrix": matrix},
            "execution": {"workers": workers, "scheduler": policy if policy else {"mode": "serial"}},
            "model": {"model_id": binding.get("model_id"), "model_version": binding.get("model_version"),
                      "config_digest": binding.get("config_digest")},
            "prompt": {"hash": binding.get("prompt_hash"), "profiles": profile_keys,
                       "semantic_summary": "unknown"},
            "tools": {"schema_versions": tool_versions,
                      "fixture_schema_version": binding.get("tool_schema_version")},
            # 计划未冻结缓存策略或缓存 key 条件时，不能把成本/时延当作可比。
            "cache": {"condition": "unknown", "reason": "cache_condition_not_frozen"}}


def enrich_report(root: Path | str, report: dict[str, Any]) -> dict[str, Any]:
    """返回只扩展 ``metrics`` 的副本，绝不改写冻结的评测事实或结论。"""
    enriched = deepcopy(report)
    metrics = enriched.setdefault("metrics", {})
    if not isinstance(metrics, dict):
        return enriched
    root = Path(root)
    plan = _read_json(root / "plan.json")
    batch = _read_json(root / "batch.json")
    plan = plan if isinstance(plan, dict) else {}
    batch = batch if isinstance(batch, dict) else {}
    statuses = {"passed": 0, "failed": 0, "unknown": 0}
    for case in enriched.get("cases", []):
        if isinstance(case, dict) and case.get("behavior_status") in statuses:
            statuses[case["behavior_status"]] += 1
    timings, usage, seen, unidentified = [], [], set(), 0
    attempts_seen: set[str] = set()
    diagnostics = {"calls_by_kind": {}, "unique_attempts": 0, "attempts_without_identity": 0,
                   "retry_attempts": 0, "retry_sources": {}, "retry_source_unknown": 0}
    evidence_keys: set[tuple[str, int]] = set()
    started, collected = 0, 0
    usage_complete_samples = set()
    for folder in sorted((root / "cases").glob("*/*")) if (root / "cases").exists() else []:
        state = _read_json(folder / "state.json")
        if isinstance(state, dict) and state.get("turns"):
            started += 1
        evidence = _read_json(folder / "evidence.json")
        if not isinstance(evidence, dict):
            continue
        collected += 1
        case_id, repetition = evidence.get("case_id"), evidence.get("repetition")
        if isinstance(case_id, str) and isinstance(repetition, int):
            timings.append(_timing(case_id, repetition, folder, evidence))
            evidence_keys.add((case_id, repetition))
        rows, absent_identity = _usage_rows(evidence, seen)
        usage.extend(rows)
        unidentified += absent_identity
        attempts = evidence.get("metrics", {}).get("attempts", []) if isinstance(evidence.get("metrics"), dict) else []
        # 一个样本没有任何计量或某次调用没有 usage 时，其他调用的已知用量不能补齐它。
        if (isinstance(attempts, list) and attempts and all(
                isinstance(a, dict) and (a.get("attemptId") or a.get("attempt_id"))
                and isinstance(a.get("usage"), list) and a["usage"]
                and all(isinstance(u, dict) and u.get("usageKnown") is True for u in a["usage"])
                for a in attempts)):
            usage_complete_samples.add((case_id, repetition))
        detail = _attempt_diagnostics(evidence, attempts_seen)
        diagnostics["unique_attempts"] += detail["unique_attempts"]
        diagnostics["attempts_without_identity"] += detail["attempts_without_identity"]
        diagnostics["retry_attempts"] += detail["retry_attempts"]
        diagnostics["retry_source_unknown"] += detail["retry_source_unknown"]
        for key in ("calls_by_kind", "retry_sources"):
            for name, count in detail[key].items():
                diagnostics[key][name] = diagnostics[key].get(name, 0) + count
    # aggregate() 的 executed 是已写入 evaluation 的行数；运行事实另以 state/evidence
    # 保存，避免把缺失 evidence 的评估行说成已采集。
    metrics["evidence_collected"] = collected
    metrics["executed"] = max(started, metrics.get("executed", 0)
                              if isinstance(metrics.get("executed"), int) else 0)
    if isinstance(batch.get("budget"), dict):
        metrics["budget"] = deepcopy(batch["budget"])
    metrics["sample_status"] = statuses
    by_case: dict[str, dict[str, int]] = {}
    for case in enriched.get("cases", []):
        if not isinstance(case, dict) or not isinstance(case.get("case_id"), str):
            continue
        summary = by_case.setdefault(case["case_id"], {"passed": 0, "failed": 0, "unknown": 0,
                                                        "missing_evidence": 0})
        status = case.get("behavior_status")
        if status in statuses:
            summary[status] += 1
        if (case["case_id"], case.get("repetition")) not in evidence_keys:
            summary["missing_evidence"] += 1
    metrics["sample_status_by_case"] = dict(sorted(by_case.items()))
    metrics["timing"] = timings
    metrics["usage"] = {"usage_events": len(usage), "unidentified_usage_events": unidentified,
                        "samples_with_complete_usage": len(usage_complete_samples),
                        "total_tokens": _token_total(usage, ("tokens", "totalTokens"), derive_total=True),
                        "input_tokens": _token_total(usage, ("inputTokens", "promptTokens")),
                        "output_tokens": _token_total(usage, ("outputTokens", "completionTokens")),
                        # 以下两项分别是输入/输出的子集，绝不并入 total_tokens。
                        "cached_input_tokens": _token_total(usage, ("cachedInputTokens",)),
                        "cache_write_input_tokens": _token_total(usage, ("cacheWriteInputTokens",)),
                        "reasoning_output_tokens": _token_total(usage, ("reasoningOutputTokens",)),
                        "input_tokens_include_cache_subsets": True,
                        "output_tokens_include_reasoning_subset": True}
    metrics["cache_measurement"] = _cache_measurement(usage, unidentified)
    metrics["attempt_diagnostics"] = diagnostics
    metrics["cost"] = _cost(usage, statuses["passed"], unidentified)
    metrics["comparison_context"] = _comparison_context(plan, batch, enriched)
    return enriched
