#!/usr/bin/env python3
"""Offline evaluator for exported Agent Runtime conversations.

The evaluator deliberately consumes captured data only.  It does not replay a
conversation, call a model, or invoke business tools.  It keeps observations
separate from human semantic scoring so a plausible string match can never be
reported as model correctness.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable


UNAVAILABLE = {"value": None, "status": "unavailable"}


def _read_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as stream:
        return json.load(stream)


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def _get_path(value: Any, path: str) -> Any:
    current = value
    for part in path.split("."):
        if not isinstance(current, dict) or part not in current:
            return None
        current = current[part]
    return current


def _parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def _tool_name(process: dict[str, Any]) -> str | None:
    for key in ("tool_name", "tool_handle", "name", "title"):
        value = process.get(key)
        if isinstance(value, str) and value:
            return value
    detail = process.get("detail")
    if isinstance(detail, dict):
        for key in ("tool_name", "tool_handle", "name"):
            value = detail.get(key)
            if isinstance(value, str) and value:
                return value
    return None


def _is_tool(process: dict[str, Any]) -> bool:
    return process.get("type") == "tool" or str(process.get("reference_id", "")).startswith("tool-action:")


def _is_model(process: dict[str, Any]) -> bool:
    kind = str(process.get("type", "")).lower()
    reference = str(process.get("reference_id", ""))
    return kind in {"model", "llm", "reasoning"} or reference.startswith("model-action:")


def _usage_objects(value: Any) -> Iterable[dict[str, Any]]:
    if isinstance(value, dict):
        for key, nested in value.items():
            if key in {"usage", "token_usage"} and isinstance(nested, dict):
                yield nested
            yield from _usage_objects(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from _usage_objects(nested)


def _token_totals(model_processes: list[dict[str, Any]]) -> dict[str, dict[str, int | None | str]]:
    aliases = {
        "input_tokens": ("input_tokens", "prompt_tokens"),
        "output_tokens": ("output_tokens", "completion_tokens"),
        "total_tokens": ("total_tokens",),
    }
    totals: dict[str, int] = Counter()
    found: set[str] = set()
    for process in model_processes:
        for usage in _usage_objects(process):
            for label, names in aliases.items():
                values = [usage[name] for name in names if isinstance(usage.get(name), int)]
                if values:
                    totals[label] += values[0]
                    found.add(label)
    return {label: {"value": totals[label], "status": "available"} if label in found else dict(UNAVAILABLE) for label in aliases}


@dataclass(frozen=True)
class Observation:
    trace: dict[str, Any]
    processes: list[dict[str, Any]]
    tool_processes: list[dict[str, Any]]
    model_processes: list[dict[str, Any]]
    context: dict[str, Any]

    @classmethod
    def from_trace(cls, trace: dict[str, Any], context: dict[str, Any] | None = None) -> "Observation":
        processes = [item for item in trace.get("processes", []) if isinstance(item, dict)]
        merged_context = dict(trace.get("evaluation_context") or {})
        merged_context.update(context or {})
        return cls(trace, processes, [item for item in processes if _is_tool(item)], [item for item in processes if _is_model(item)], merged_context)

    def metrics(self) -> dict[str, Any]:
        messages = [item for item in self.trace.get("messages", []) if isinstance(item, dict)]
        first_user = next((_parse_time(item.get("created_at")) for item in messages if item.get("role") == "user"), None)
        first_body = next((_parse_time(item.get("created_at")) for item in messages if item.get("role") == "assistant" and item.get("content")), None)
        latency = dict(UNAVAILABLE)
        if first_user is not None and first_body is not None and (first_user.tzinfo is None) == (first_body.tzinfo is None):
            latency = {"value": max(0.0, (first_body - first_user).total_seconds()), "status": "available", "unit": "seconds"}
        return {
            "model_calls": {"value": len(self.model_processes), "status": "available"},
            "tool_calls": {"value": len(self.tool_processes), "status": "available"},
            "first_body_latency": latency,
            "tokens": _token_totals(self.model_processes),
        }


def _applies(case: dict[str, Any], observation: Observation) -> bool:
    condition = case.get("applies_when")
    if not condition:
        return True
    value = _get_path(observation.context, str(condition.get("path", "")))
    return value is not None and value == condition.get("equals")


def _known_tool_effect(process: dict[str, Any], observation: Observation) -> str | None:
    detail = process.get("detail") if isinstance(process.get("detail"), dict) else {}
    value = process.get("effect") or detail.get("effect")
    if isinstance(value, str):
        return value
    effects = observation.context.get("tool_effects")
    if isinstance(effects, dict):
        return effects.get(_tool_name(process))
    return None


def _assertion(assertion: dict[str, Any], observation: Observation) -> tuple[str, list[str], str]:
    """Return outcome, stable evidence identifiers, and an explanation."""
    kind = assertion["kind"]
    tools = observation.tool_processes
    names = [_tool_name(item) for item in tools]
    if kind == "no_tools_named":
        wanted = set(assertion["tool_names"])
        if any(name is None for name in names):
            return "unknown", [], "工具标识缺失，无法判定是否属于受限集合"
        matches = [item.get("process_id", "unknown") for item, name in zip(tools, names) if name in wanted]
        return ("pass", [], "未出现受限工具") if not matches else ("fail", matches, "出现了受限工具")
    if kind == "only_tools_named":
        allowed = set(assertion["tool_names"])
        if any(name is None for name in names):
            return "unknown", [], "工具标识缺失，无法核验最小集合"
        extras = [item.get("process_id", "unknown") for item, name in zip(tools, names) if name not in allowed]
        return ("pass", [], "工具调用均在允许集合内") if not extras else ("fail", extras, "存在允许集合外的工具调用")
    if kind == "no_pagination_when_complete":
        complete = _get_path(observation.context, assertion.get("complete_path", "history.complete"))
        if complete is not True:
            return "unknown", [], "未提供历史是否完整的结构化事实"
        pagination = set(assertion["tool_names"])
        if any(name is None for name in names):
            return "unknown", [], "工具标识缺失，无法判定分页调用"
        matches = [item.get("process_id", "unknown") for item, name in zip(tools, names) if name in pagination]
        return ("pass", [], "完整历史未触发分页") if not matches else ("fail", matches, "完整历史仍触发了分页")
    if kind == "failure_fact_review":
        failed = [item for item in tools if str(item.get("status", "")).lower() == "failed"]
        if not failed:
            return "unknown", [], "没有可核验的失败工具事实"
        reviews = observation.context.get("fact_reviews")
        if not isinstance(reviews, list):
            return "unknown", [item.get("process_id", "unknown") for item in failed], "缺少失败事实复盘的结构化记录；不得用文本关键词推断"
        reviewed = {entry.get("failed_process_id") for entry in reviews if isinstance(entry, dict) and entry.get("outcome") in {"reported", "resolved"}}
        missing = [item.get("process_id", "unknown") for item in failed if item.get("process_id") not in reviewed]
        return ("pass", [], "每个失败工具都有事实复盘") if not missing else ("fail", missing, "失败工具缺少事实复盘")
    if kind == "no_write_before_approval":
        approved = _get_path(observation.context, assertion.get("approval_path", "plan.approved"))
        if approved is True:
            return "unknown", [], "已批准，前置批准约束不适用"
        effects = [_known_tool_effect(item, observation) for item in tools]
        if any(effect is None for effect in effects):
            return "unknown", [], "缺少工具副作用分类，不能把未知工具当作只读"
        writes = [item.get("process_id", "unknown") for item, effect in zip(tools, effects) if effect == "write"]
        return ("pass", [], "批准前没有写副作用") if not writes else ("fail", writes, "批准前发生写副作用")
    if kind == "declared_tool_count":
        expected = assertion["equals"]
        actual = len(tools)
        return ("pass", [], "工具数来自声明的 tool process") if actual == expected else ("fail", [], f"声明的工具数为 {actual}，预期 {expected}")
    raise ValueError(f"unsupported assertion kind: {kind}")


def evaluate_trace(trace: dict[str, Any], cases: list[dict[str, Any]], context: dict[str, Any] | None = None) -> dict[str, Any]:
    observation = Observation.from_trace(trace, context)
    results: list[dict[str, Any]] = []
    for case in cases:
        if not _applies(case, observation):
            results.append({"id": case["id"], "status": "unknown", "reason": "缺少或不匹配适用场景事实", "assertions": [], "manual_gold": case["manual_gold"]})
            continue
        assertion_results = []
        for assertion in case["assertions"]:
            status, evidence, reason = _assertion(assertion, observation)
            assertion_results.append({"kind": assertion["kind"], "status": status, "evidence_process_ids": evidence, "reason": reason})
        statuses = {item["status"] for item in assertion_results}
        status = "fail" if "fail" in statuses else "unknown" if "unknown" in statuses else "pass"
        results.append({"id": case["id"], "status": status, "assertions": assertion_results, "manual_gold": case["manual_gold"]})
    return {"format": "skillforge-conversation-eval/v1", "offline_only": True, "metrics": observation.metrics(), "cases": results}


def merge_manual_scores(report: dict[str, Any], scores: dict[str, Any]) -> dict[str, Any]:
    result = json.loads(json.dumps(report))
    by_id = scores.get("cases", scores)
    if not isinstance(by_id, dict):
        raise ValueError("人工评分必须是 case id 到评分对象的 JSON 对象")
    for case in result["cases"]:
        score = by_id.get(case["id"])
        if score is not None:
            if not isinstance(score, dict) or score.get("score") not in {"pass", "partial", "fail"}:
                raise ValueError(f"{case['id']} 的人工评分必须为 pass、partial 或 fail")
            case["manual_score"] = score
    return result


def _load_report_or_trace(path: Path, cases: list[dict[str, Any]], context: dict[str, Any] | None) -> dict[str, Any]:
    source = _read_json(path)
    if isinstance(source, dict) and source.get("format") == "skillforge-conversation-eval/v1":
        return source
    if not isinstance(source, dict):
        raise ValueError(f"{path} 不是 JSON 对象")
    return evaluate_trace(source, cases, context)


def compare_reports(baseline: dict[str, Any], candidate: dict[str, Any]) -> dict[str, Any]:
    baseline_cases = {item["id"]: item for item in baseline["cases"]}
    candidate_cases = {item["id"]: item for item in candidate["cases"]}
    comparisons = []
    for case_id in sorted(set(baseline_cases) | set(candidate_cases)):
        left, right = baseline_cases.get(case_id), candidate_cases.get(case_id)
        comparisons.append({
            "id": case_id,
            "baseline_status": left.get("status") if left else "unknown",
            "candidate_status": right.get("status") if right else "unknown",
            "baseline_manual_score": left.get("manual_score", {}).get("score") if left else None,
            "candidate_manual_score": right.get("manual_score", {}).get("score") if right else None,
        })
    return {"format": "skillforge-conversation-eval-comparison/v1", "offline_only": True, "baseline_metrics": baseline["metrics"], "candidate_metrics": candidate["metrics"], "cases": comparisons}


def main() -> None:
    parser = argparse.ArgumentParser(description="离线评测导出的 Agent Runtime 会话，不调用模型或业务工具。")
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--trace", type=Path, help="单个真实导出记录或已捕获评测报告")
    inputs.add_argument("--baseline", type=Path, help="比较基线的真实导出记录或已捕获评测报告")
    parser.add_argument("--candidate", type=Path, help="比较候选的真实导出记录或已捕获评测报告")
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("eval_cases.json"))
    parser.add_argument("--context", type=Path, help="可选的脱敏结构化场景和工具副作用标注")
    parser.add_argument("--manual-scores", type=Path, help="人工 gold 评分 JSON，仅与单记录结果合并")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.baseline and not args.candidate:
        parser.error("--baseline 必须与 --candidate 一起使用")
    if args.candidate and not args.baseline:
        parser.error("--candidate 必须与 --baseline 一起使用")
    if args.manual_scores and args.baseline:
        parser.error("比较前请先分别合并人工评分，再传入已捕获报告")
    case_doc = _read_json(args.cases)
    cases = case_doc["cases"] if isinstance(case_doc, dict) else case_doc
    context = _read_json(args.context) if args.context else None
    if args.trace:
        report = _load_report_or_trace(args.trace, cases, context)
        if args.manual_scores:
            report = merge_manual_scores(report, _read_json(args.manual_scores))
        _write_json(args.out, report)
    else:
        baseline = _load_report_or_trace(args.baseline, cases, context)
        candidate = _load_report_or_trace(args.candidate, cases, context)
        _write_json(args.out, compare_reports(baseline, candidate))


if __name__ == "__main__":
    main()
