"""行为评测报告的确定性校验、脱敏与公开产物生成。

本模块不读取环境变量、不调用网络，也不在导入时写入文件。调用方先完成
行为评测，再将最终报告传给 :func:`write_reports` 生成可以分发的公开副本。
"""
from __future__ import annotations

from copy import deepcopy
import html
import json
from pathlib import Path
import re
from typing import Any
import xml.etree.ElementTree as ET


SCHEMA_VERSION = 1
REPORT_FORMAT = "skillforge-behavior-evaluation"
LIFECYCLE = "finalized"
BEHAVIOR_STATUSES = frozenset(("passed", "failed", "unknown"))
ASSERTION_STATUSES = frozenset(("pass", "fail", "unknown"))
VERDICTS = frozenset(("pass", "partial", "fail"))
SENSITIVE_KEY_PARTS = frozenset((
    "password", "api_key", "authorization", "token", "download_ticket", "secret", "alias_mapping",
))
_KEY_PARTS = re.compile(r"[^a-z0-9]+")
_BEARER_CREDENTIAL = re.compile(r"\bBearer\s+[^\s<>\"']+", re.IGNORECASE)
_API_KEY_CREDENTIAL = re.compile(r"\bsk-[A-Za-z0-9][A-Za-z0-9_-]{3,}\b", re.IGNORECASE)
_URL_SECRET_QUERY = re.compile(
    r"([?&][^=&?#\s<>\"'\\{}]*(?:token|ticket)[^=&?#\s<>\"'\\{}]*=)[^&#\s<>\"'\\{}]*", re.IGNORECASE)
_UNREDACTED_BEARER = re.compile(r"\bBearer\s+(?!\[REDACTED\])[^\s<>\"']+", re.IGNORECASE)
_UNREDACTED_API_KEY = re.compile(r"\bsk-[A-Za-z0-9][A-Za-z0-9_-]{3,}\b", re.IGNORECASE)
_UNREDACTED_URL_QUERY = re.compile(
    r"[?&][^=&?#\s<>\"'\\{}]*(?:token|ticket)[^=&?#\s<>\"'\\{}]*=(?!\[REDACTED\])[^&#\s<>\"'\\{}]+", re.IGNORECASE)


class BehaviorReportError(ValueError):
    """报告的结构、证据或公开脱敏规则不满足契约。"""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise BehaviorReportError(message)


def _is_sensitive_key(key: str) -> bool:
    """按完整键段匹配，``tokens`` 这类计量字段不会被 ``token`` 规则误伤。"""
    lowered = key.lower()
    if lowered in SENSITIVE_KEY_PARTS:
        return True
    parts = [part for part in _KEY_PARTS.split(lowered) if part]
    return "token" in parts or "password" in parts or "authorization" in parts or "secret" in parts


def _normalise_report(report: dict[str, Any]) -> dict[str, Any]:
    _require(isinstance(report, dict), "report 必须是对象")
    expected = {
        "schema_version", "format", "lifecycle", "binding", "behavior_status",
        "blocking_reasons", "cases", "metrics", "release_eligible",
    }
    _require(set(report) == expected, "report 顶层字段必须与 schema 完全一致")
    _require(report["schema_version"] == SCHEMA_VERSION, "不支持的 schema_version")
    _require(report["format"] == REPORT_FORMAT, "不支持的 format")
    _require(report["lifecycle"] == LIFECYCLE, "报告必须处于 finalized 生命周期")
    _require(isinstance(report["binding"], dict), "binding 必须是对象")
    _require(isinstance(report["behavior_status"], str) and report["behavior_status"] in BEHAVIOR_STATUSES,
             "behavior_status 无效")
    _require(isinstance(report["blocking_reasons"], list), "blocking_reasons 必须是列表")
    _require(isinstance(report["cases"], list), "cases 必须是列表")
    _require(isinstance(report["metrics"], dict), "metrics 必须是对象")
    _require(isinstance(report["release_eligible"], bool), "release_eligible 必须是布尔值")

    reasons: list[dict[str, Any]] = []
    for index, reason in enumerate(report["blocking_reasons"]):
        allowed_reason_fields = {"code", "detail", "case_id", "repetition"}
        _require(isinstance(reason, dict) and {"code", "detail"}.issubset(reason)
                 and set(reason).issubset(allowed_reason_fields),
                 f"blocking_reasons[{index}] 必须包含 code、detail 及可选定位字段")
        _require(isinstance(reason["code"], str) and isinstance(reason["detail"], str),
                 f"blocking_reasons[{index}] 字段必须是字符串")
        _require("case_id" not in reason or (isinstance(reason["case_id"], str) and reason["case_id"]),
                 f"blocking_reasons[{index}].case_id 无效")
        _require("repetition" not in reason or (isinstance(reason["repetition"], int)
                 and not isinstance(reason["repetition"], bool) and reason["repetition"] >= 0),
                 f"blocking_reasons[{index}].repetition 无效")
        reasons.append(deepcopy(reason))

    cases: list[dict[str, Any]] = []
    case_ids: set[tuple[str, int]] = set()
    for case_index, case in enumerate(report["cases"]):
        _require(isinstance(case, dict) and set(case) == {
            "case_id", "repetition", "behavior_status", "blocking_reasons", "assertions", "metrics",
        }, f"cases[{case_index}] 字段不符合 schema")
        _require(isinstance(case["case_id"], str) and case["case_id"],
                 f"cases[{case_index}].case_id 无效")
        _require(isinstance(case["repetition"], int) and not isinstance(case["repetition"], bool)
                 and case["repetition"] >= 0, f"cases[{case_index}].repetition 无效")
        _require(isinstance(case["behavior_status"], str) and case["behavior_status"] in BEHAVIOR_STATUSES,
                 f"cases[{case_index}].behavior_status 无效")
        _require(isinstance(case["blocking_reasons"], list),
                 f"cases[{case_index}].blocking_reasons 必须是列表")
        _require(isinstance(case["assertions"], list), f"cases[{case_index}].assertions 必须是列表")
        _require(isinstance(case["metrics"], dict), f"cases[{case_index}].metrics 必须是对象")
        identity = (case["case_id"], case["repetition"])
        _require(identity not in case_ids, f"重复的 case/repetition: {identity!r}")
        case_ids.add(identity)

        case_reasons: list[dict[str, Any]] = []
        for reason_index, reason in enumerate(case["blocking_reasons"]):
            _require(isinstance(reason, dict) and set(reason) == {"code", "detail"},
                     f"cases[{case_index}].blocking_reasons[{reason_index}] 无效")
            _require(isinstance(reason["code"], str) and isinstance(reason["detail"], str),
                     f"cases[{case_index}].blocking_reasons[{reason_index}] 字段必须是字符串")
            case_reasons.append(deepcopy(reason))

        assertions: list[dict[str, Any]] = []
        assertion_ids: set[str] = set()
        for assertion_index, assertion in enumerate(case["assertions"]):
            _require(isinstance(assertion, dict) and set(assertion) == {
                "id", "evaluator", "blocking", "status", "reason", "evidence",
            }, f"cases[{case_index}].assertions[{assertion_index}] 字段不符合 schema")
            _require(isinstance(assertion["id"], str) and assertion["id"],
                     f"cases[{case_index}].assertions[{assertion_index}].id 无效")
            _require(assertion["id"] not in assertion_ids,
                     f"cases[{case_index}] 内 assertion id 重复")
            assertion_ids.add(assertion["id"])
            _require(isinstance(assertion["evaluator"], str), "assertion.evaluator 必须是字符串")
            _require(isinstance(assertion["blocking"], bool), "assertion.blocking 必须是布尔值")
            _require(isinstance(assertion["status"], str) and assertion["status"] in ASSERTION_STATUSES,
                     "assertion.status 无效")
            _require(isinstance(assertion["reason"], str), "assertion.reason 必须是字符串")
            _require(isinstance(assertion["evidence"], list), "assertion.evidence 必须是列表")
            _require(all(isinstance(item, dict) for item in assertion["evidence"]),
                     "assertion.evidence 项必须是对象")
            assertions.append(deepcopy(assertion))

        cases.append({
            "case_id": case["case_id"], "repetition": case["repetition"],
            "behavior_status": case["behavior_status"], "blocking_reasons": case_reasons,
            "assertions": assertions, "metrics": deepcopy(case["metrics"]),
        })
    return {
        "schema_version": SCHEMA_VERSION, "format": REPORT_FORMAT, "lifecycle": LIFECYCLE,
        "binding": deepcopy(report["binding"]), "behavior_status": report["behavior_status"],
        "blocking_reasons": reasons, "cases": cases, "metrics": deepcopy(report["metrics"]),
        "release_eligible": report["release_eligible"],
    }


def _effective_case_status(case: dict[str, Any]) -> str:
    """只让硬断言影响行为观测；评审分歧仍保留在 assertions 明细中。"""
    hard_statuses = {
        assertion["status"] for assertion in case["assertions"] if assertion["blocking"]
    }
    if case["behavior_status"] == "failed" or "fail" in hard_statuses:
        return "failed"
    if case["behavior_status"] == "unknown" or "unknown" in hard_statuses:
        return "unknown"
    return "passed"


def _finalise_status(report: dict[str, Any]) -> None:
    case_statuses = [_effective_case_status(case) for case in report["cases"]]
    for case, status in zip(report["cases"], case_statuses):
        case["behavior_status"] = status
    if report["behavior_status"] == "failed" or "failed" in case_statuses:
        report["behavior_status"] = "failed"
    elif (report["behavior_status"] == "passed" and case_statuses
          and all(status == "passed" for status in case_statuses)):
        report["behavior_status"] = "passed"
    else:
        report["behavior_status"] = "unknown"
    has_release_blocker = bool(report["blocking_reasons"]) or any(
        case["blocking_reasons"] for case in report["cases"]
    )
    report["release_eligible"] = (report["release_eligible"] and report["behavior_status"] == "passed"
                                   and not has_release_blocker)


def _redact_string(value: str, sensitive_values: tuple[str, ...]) -> str:
    redacted = value
    for secret in sensitive_values:
        redacted = redacted.replace(secret, "[REDACTED]")
    redacted = _BEARER_CREDENTIAL.sub("Bearer [REDACTED]", redacted)
    redacted = _API_KEY_CREDENTIAL.sub("[REDACTED]", redacted)
    redacted = _URL_SECRET_QUERY.sub(r"\1[REDACTED]", redacted)
    return redacted


def _redact(value: Any, sensitive_values: tuple[str, ...]) -> Any:
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for raw_key, raw_value in value.items():
            key = _redact_string(str(raw_key), sensitive_values)
            result[key] = "[REDACTED]" if _is_sensitive_key(str(raw_key)) else _redact(raw_value, sensitive_values)
        return result
    if isinstance(value, list):
        return [_redact(item, sensitive_values) for item in value]
    if isinstance(value, tuple):
        return [_redact(item, sensitive_values) for item in value]
    if isinstance(value, str):
        return _redact_string(value, sensitive_values)
    return value


def _assert_no_secret(serialized: dict[str, str], sensitive_values: tuple[str, ...]) -> None:
    for name, payload in serialized.items():
        decoded = html.unescape(payload)
        for secret in sensitive_values:
            if secret in payload or secret in decoded:
                raise BehaviorReportError(f"公开 {name} 仍包含敏感值")
        if (_UNREDACTED_BEARER.search(decoded) or _UNREDACTED_API_KEY.search(decoded)
                or _UNREDACTED_URL_QUERY.search(decoded)):
            raise BehaviorReportError(f"公开 {name} 仍包含未脱敏的常见凭据")


def _xml_for(report: dict[str, Any]) -> str:
    cases = report["cases"]
    failures = sum(case["behavior_status"] == "failed" for case in cases)
    skipped = sum(case["behavior_status"] == "unknown" for case in cases)
    suite = ET.Element("testsuite", {
        "name": "skillforge.behavior-evaluation", "tests": str(len(cases)),
        "failures": str(failures), "errors": "0", "skipped": str(skipped),
    })
    for case in cases:
        testcase = ET.SubElement(suite, "testcase", {
            "classname": "skillforge.behavior", "name": f"{case['case_id']}#{case['repetition']}",
        })
        reason = "; ".join(item["detail"] for item in case["blocking_reasons"])
        if case["behavior_status"] == "failed":
            ET.SubElement(testcase, "failure", {"message": reason or "behavior assertion failed"})
        elif case["behavior_status"] == "unknown":
            ET.SubElement(testcase, "skipped", {"message": reason or "behavior evidence incomplete"})
    return "<?xml version='1.0' encoding='utf-8'?>\n" + ET.tostring(suite, encoding="unicode") + "\n"


def _html_for(report: dict[str, Any]) -> str:
    """生成可扫描的公开概览，同时保留完整脱敏后的原报告。"""
    esc = lambda value: html.escape(str(value), quote=True)
    metrics = report.get("metrics", {})
    sample_status = metrics.get("sample_status", {}) if isinstance(metrics, dict) else {}
    rows = []
    failures = []
    for case in report["cases"]:
        assertions = ", ".join(f"{item['id']}:{item['status']}" for item in case["assertions"])
        reasons = "; ".join(f"{item['code']}: {item['detail']}" for item in case["blocking_reasons"])
        rows.append("<tr><td>" + esc(case["case_id"]) + "</td><td>" + esc(case["repetition"])
                    + "</td><td>" + esc(case["behavior_status"]) + "</td><td>" + esc(assertions)
                    + "</td><td>" + esc(reasons) + "</td></tr>")
        if case["behavior_status"] != "passed" or case["blocking_reasons"]:
            failures.append("<li><strong>" + esc(case["case_id"]) + "#" + esc(case["repetition"])
                            + " — " + esc(case["behavior_status"]) + "</strong>: "
                            + esc(reasons or assertions or "无断言明细") + "</li>")
    top_reasons = "; ".join(f"{item['code']}: {item['detail']}" for item in report["blocking_reasons"])
    content = html.escape(json.dumps(report, ensure_ascii=False, indent=2), quote=True)
    overview = ("状态=" + esc(report["behavior_status"]) + "；发布资格=" + esc(report["release_eligible"])
                + "；计划/执行/已评估=" + esc(metrics.get("planned", "—")) + "/"
                + esc(metrics.get("executed", "—")) + "/" + esc(metrics.get("evaluated", "—"))
                + "；样本通过/失败/未知=" + esc(sample_status.get("passed", "—")) + "/"
                + esc(sample_status.get("failed", "—")) + "/" + esc(sample_status.get("unknown", "—")))
    return ("<!doctype html><html><head><meta charset=\"utf-8\"><title>行为评测报告</title>"
            "<style>body{font:14px system-ui;margin:24px;color:#18212f}table{border-collapse:collapse;width:100%}"
            "th,td{border:1px solid #ccd5df;padding:7px;text-align:left;vertical-align:top}th{background:#f2f5f8}"
            "pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f7f8fa;padding:12px}</style></head><body>"
            "<h1>行为评测报告</h1><p>" + overview + "</p><p>批次阻断：" + esc(top_reasons or "无")
            + "</p><h2>场景结果</h2><table><thead><tr><th>场景</th><th>重复</th><th>状态</th><th>断言</th><th>阻断原因</th>"
            + "</tr></thead><tbody>" + "".join(rows) + "</tbody></table><h2>失败与未完成明细</h2><ul>"
            + ("".join(failures) if failures else "<li>无</li>")
            + "</ul><details><summary>完整脱敏报告</summary><pre>" + content
            + "</pre></details></body></html>\n")


def _write_checked_files(directory: Path, serialized: dict[str, str], sensitive_values: tuple[str, ...]) -> None:
    _assert_no_secret(serialized, sensitive_values)
    directory.mkdir(parents=True, exist_ok=True)
    temporary: list[tuple[Path, Path]] = []
    try:
        for name, content in serialized.items():
            target = directory / name
            temp = directory / f".{name}.pending"
            temp.write_text(content, encoding="utf-8")
            temporary.append((temp, target))
        # 从磁盘复读，避免内存检查和实际待发布内容不一致。
        _assert_no_secret({temp.name: temp.read_text(encoding="utf-8") for temp, _ in temporary}, sensitive_values)
        for temp, target in temporary:
            temp.replace(target)
    finally:
        for temp, _ in temporary:
            if temp.exists():
                temp.unlink()


def write_reports(root: Path, report: dict[str, Any], sensitive_values: list[str]) -> dict[str, Any]:
    """生成脱敏后的 JSON、HTML 与 JUnit 公开报告，并返回其确定性摘要。

    任何公开文件替换前都对所有待发布内容扫描敏感值；不完整的断言证据会把
    ``passed`` 降为 ``unknown``，因而不能误放行发布资格。
    """
    _require(isinstance(sensitive_values, list) and all(isinstance(value, str) and value for value in sensitive_values),
             "sensitive_values 必须是非空字符串列表")
    secrets = tuple(sorted(set(sensitive_values), key=len, reverse=True))
    public_report = _normalise_report(report)
    _finalise_status(public_report)
    public_report = _redact(public_report, secrets)
    serialized = {
        "summary.json": json.dumps(public_report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        "report.html": _html_for(public_report),
        "junit.xml": _xml_for(public_report),
    }
    public_root = Path(root) / "public"
    _write_checked_files(public_root, serialized, secrets)
    return {
        "behavior_status": public_report["behavior_status"],
        "release_eligible": public_report["release_eligible"],
        "summary": public_report,
        "paths": {name: str(public_root / name) for name in serialized},
    }


def _binding_value(report: dict[str, Any], field: str) -> Any:
    binding = report.get("binding")
    return binding.get(field) if isinstance(binding, dict) else None


def _unknown_model_version(value: Any) -> bool:
    return value is None or (isinstance(value, str) and value.strip().lower() in {"", "unknown"})


def _quality_comparability(left: dict[str, Any], right: dict[str, Any]) -> dict[str, Any]:
    """判断两个最终报告能否横向比较；部署修订版本刻意不参与绑定。"""
    fields = ("suite_version", "model_id", "model_version", "config_digest", "evaluator_version", "settlement_seconds", "evidence_mode")
    _require(isinstance(left, dict) and isinstance(right, dict), "报告必须是对象")
    if _unknown_model_version(_binding_value(left, "model_version")) or _unknown_model_version(_binding_value(right, "model_version")):
        return {"status": "comparability_unknown", "comparable": False,
                "reasons": ["model_version_unknown"], "cost_comparable": False}
    if any(_binding_value(report, "suite_version") == "product-tasks-1" for report in (left, right)):
        # 数据修订和采集器变化不能被同名场景掩盖，更不能算作同输入修复通过。
        product_fields = ("dataset_fingerprint", "harness_digest")
        missing = [field for field in product_fields
                   if any(not _binding_value(report, field) for report in (left, right))]
        if missing:
            return {"status": "comparability_unknown", "comparable": False,
                    "reasons": ["product_binding_missing:" + field for field in missing], "cost_comparable": False}
        fields += product_fields
    mismatches = [field for field in fields if _binding_value(left, field) != _binding_value(right, field)]
    if mismatches:
        return {"status": "not_comparable", "comparable": False,
                "reasons": [f"binding_mismatch:{field}" for field in mismatches], "cost_comparable": False}
    missing_binding = [field for field in fields if any(_binding_value(report, field) in (None, "") for report in (left, right))]
    if missing_binding:
        return {"status": "comparability_unknown", "comparable": False,
                "reasons": ["binding_missing:" + field for field in missing_binding]}
    if any(r.get("lifecycle") != "finalized" or any(
            b.get("code") in {"invalid_environment", "invalid_config_drift"} for b in r.get("blocking_reasons", []))
            for r in (left, right)):
        return {"status": "not_comparable", "comparable": False, "reasons": ["invalid_or_unfinalized_source"]}
    required_context = (
        ("selection", "case_ids"), ("selection", "repetitions"), ("selection", "matrix"),
        ("execution", "workers"), ("execution", "scheduler"), ("model", "model_id"),
        ("model", "config_digest"), ("prompt", "hash"), ("tools", "schema_versions"),
        ("tools", "fixture_schema_version"),
    )
    contexts = []
    missing = []
    for side, report in (("left", left), ("right", right)):
        metrics = report.get("metrics")
        context = metrics.get("comparison_context") if isinstance(metrics, dict) else None
        if not isinstance(context, dict):
            missing.append(side + ":comparison_context")
            contexts.append({})
            continue
        contexts.append(context)
        for group, field in required_context:
            if not isinstance(context.get(group), dict) or context[group].get(field) is None:
                missing.append(side + ":comparison_context." + group + "." + field)
    if missing:
        return {"status": "comparability_unknown", "comparable": False,
                "reasons": ["comparison_context_missing:" + item for item in missing], "cost_comparable": False}
    left_context, right_context = contexts
    context_mismatches = [group + "." + field for group, field in required_context
                          if left_context[group][field] != right_context[group][field]]
    if context_mismatches:
        return {"status": "not_comparable", "comparable": False,
                "reasons": ["comparison_context_mismatch:" + field for field in context_mismatches],
                "cost_comparable": False}
    return {"status": "comparable", "comparable": True, "reasons": []}


def measurement_comparability(left, right, *, quality_comparable):
    """逐维度核对实际计量；缓存未知仅阻止费用和时延比较，不改变行为结论。"""
    from behavior_measurements import _number

    metrics = [r.get("metrics", {}) for r in (left, right)]
    contexts = [m.get("comparison_context", {}) for m in metrics]
    common = [] if quality_comparable else ["quality_controls_not_comparable"]
    matrices = [c.get("selection", {}).get("matrix") for c in contexts]
    if not matrices[0] or matrices[0] != matrices[1]:
        common.append("measurement_matrix_missing_or_different")
    for report, matrix in zip((left, right), matrices):
        expected = {(r["case_id"], r["repetition"]) for r in matrix or []}
        rows = report.get("cases", [])
        observed = {(r["case_id"], r["repetition"]) for r in rows}
        if not expected or observed != expected or len(rows) != len(observed) or any(
                r.get("behavior_status") not in {"passed", "failed"} for r in rows):
            common.append("measurement_samples_incomplete")
    caches = [c.get("cache", {}).get("condition") for c in contexts]
    cache_reasons = []
    if any(v in (None, "", "unknown") for v in caches):
        cache_reasons.append("cache_condition_unknown")
    elif caches[0] != caches[1]:
        cache_reasons.append("cache_condition_mismatch")

    tokens = [m.get("usage", {}).get("total_tokens") for m in metrics]
    token_reasons = list(common)
    usage_reasons = []
    if any(m.get("usage", {}).get("samples_with_complete_usage") != len(matrix or [])
           for m, matrix in zip(metrics, matrices)):
        usage_reasons.append("usage_sample_coverage_incomplete")
    token_reasons += usage_reasons
    if any(_number(v) is None or v < 0 for v in tokens) or any(
            m.get("usage", {}).get("unidentified_usage_events") != 0 for m in metrics):
        token_reasons.append("token_usage_missing_or_unknown")

    costs = [m.get("cost", {}) for m in metrics]
    cost_reasons = common + cache_reasons + usage_reasons
    if any(c.get("status") != "known" for c in costs):
        cost_reasons.append("cost_missing_or_unknown")
    prices = [c.get("pricing_snapshot_versions") for c in costs]
    if not prices[0] or prices[0] != prices[1]:
        cost_reasons.append("pricing_snapshot_missing_or_mismatch")
    money_keys = ("total_amount_by_currency", "total_amount_minor_units_by_currency")
    if any(not any(c.get(k) for k in money_keys) for c in costs) or any(
            set(costs[0].get(k, {})) != set(costs[1].get(k, {})) for k in money_keys):
        cost_reasons.append("currency_or_amount_unit_missing_or_mismatch")

    latency_reasons = common + cache_reasons
    latencies = []
    for m, matrix in zip(metrics, matrices):
        timing = m.get("timing", [])
        expected = {(r["case_id"], r["repetition"]) for r in matrix or []}
        samples = {(r["case_id"], r["repetition"]): r.get("client_first_nonempty_message_delta", {}) for r in timing}
        values = [v.get("seconds") for v in samples.values()]
        valid = (expected and set(samples) == expected and len(timing) == len(samples)
                 and all(v.get("status") == "observed" for v in samples.values())
                 and all(_number(v) is not None and v >= 0 for v in values))
        latencies.append(sum(values) / len(values) if valid else None)
    if any(v is None for v in latencies):
        latency_reasons.append("client_first_delta_timing_incomplete")

    def dimension(reasons, observations, unit):
        result = {"comparable": not reasons, "reasons": sorted(set(reasons)),
                  "baseline": observations[0], "candidate": observations[1], "unit": unit}
        if not reasons and all(_number(v) is not None for v in observations):
            result["delta"] = observations[1] - observations[0]
        return result

    return {"tokens": dimension(token_reasons, tokens, "total_tokens"),
            "cost": dimension(cost_reasons, [{k: c.get(k) for k in money_keys} for c in costs], "by_currency_and_recorded_unit"),
            "latency": dimension(latency_reasons, latencies, "mean_client_first_nonempty_delta_seconds")}


def compare_reports(left, right):
    """同配置报告比较；刻意改变提示词应使用绑定真实配置的冻结实验。"""
    result = _quality_comparability(left, right)
    measurements = measurement_comparability(left, right, quality_comparable=result["comparable"])
    result["measurements"] = measurements
    result["cost_comparable"] = measurements["cost"]["comparable"]
    return result


def _pointer_exists(payload: Any, pointer: str) -> bool:
    if not isinstance(pointer, str) or (pointer and not pointer.startswith("/")):
        return False
    current = payload
    if not pointer:
        return True
    for token in pointer[1:].split("/"):
        if re.search(r"~(?:[^01]|$)", token):
            return False
        token = token.replace("~1", "/").replace("~0", "~")
        if isinstance(current, dict):
            if token not in current:
                return False
            current = current[token]
        elif isinstance(current, list):
            if not re.fullmatch(r"0|[1-9][0-9]*", token):
                return False
            index = int(token)
            if index >= len(current):
                return False
            current = current[index]
        else:
            return False
    return True


def validate_judge_response(value: Any, allowed_evidence: dict[str, Any], assertion_ids: list[str]) -> dict[str, Any]:
    """验证裁判响应完全覆盖断言，并且每个裁决锚定到允许事件的真实 JSON Pointer。"""
    _require(isinstance(allowed_evidence, dict), "allowed_evidence 必须是 event_id 到 payload 的映射")
    _require(isinstance(assertion_ids, list) and all(isinstance(item, str) and item for item in assertion_ids),
             "assertion_ids 必须是非空字符串列表")
    _require(len(assertion_ids) == len(set(assertion_ids)), "assertion_ids 不可重复")
    _require(isinstance(value, dict) and set(value) == {"reviews"} and isinstance(value["reviews"], list),
             "裁判响应必须仅包含 reviews 列表")
    seen: set[str] = set()
    normalised: list[dict[str, Any]] = []
    for index, review in enumerate(value["reviews"]):
        _require(isinstance(review, dict) and set(review) == {"assertion_id", "verdict", "reason", "evidence"},
                 f"reviews[{index}] 字段不符合 schema")
        assertion_id = review["assertion_id"]
        _require(isinstance(assertion_id, str) and assertion_id in assertion_ids and assertion_id not in seen,
                 f"reviews[{index}].assertion_id 无效或重复")
        seen.add(assertion_id)
        _require(isinstance(review["verdict"], str) and review["verdict"] in VERDICTS
                 and isinstance(review["reason"], str),
                 f"reviews[{index}] verdict 或 reason 无效")
        _require(isinstance(review["evidence"], list) and review["evidence"],
                 f"reviews[{index}] 必须至少包含一条证据")
        evidence_rows: list[dict[str, str]] = []
        for evidence_index, evidence in enumerate(review["evidence"]):
            _require(isinstance(evidence, dict) and set(evidence) == {"event_id", "pointer"},
                     f"reviews[{index}].evidence[{evidence_index}] 字段无效")
            event_id, pointer = evidence["event_id"], evidence["pointer"]
            _require(isinstance(event_id, str) and event_id in allowed_evidence and isinstance(pointer, str)
                     and _pointer_exists(allowed_evidence[event_id], pointer),
                     f"reviews[{index}].evidence[{evidence_index}] 引用不存在")
            evidence_rows.append({"event_id": event_id, "pointer": pointer})
        normalised.append({"assertion_id": assertion_id, "verdict": review["verdict"],
                           "reason": review["reason"], "evidence": evidence_rows})
    _require(seen == set(assertion_ids), "裁判响应必须唯一且完整覆盖 assertion_ids")
    return {"reviews": normalised}


def _records_by_sample(records: list[dict[str, Any]], label: str) -> dict[str, dict[str, Any]]:
    _require(isinstance(records, list), f"{label} 必须是列表")
    indexed: dict[str, dict[str, Any]] = {}
    for index, record in enumerate(records):
        _require(isinstance(record, dict) and isinstance(record.get("sample_id"), str) and record["sample_id"],
                 f"{label}[{index}].sample_id 无效")
        _require(record["sample_id"] not in indexed, f"{label} 存在重复 sample_id")
        _require("case_id" not in record or isinstance(record["case_id"], str),
                 f"{label}[{index}].case_id 无效")
        _require("source" not in record or (isinstance(record["source"], str)
                 and record["source"] in {"constructed", "live"}),
                 f"{label}[{index}].source 无效")
        _require("human_verdict" not in record or (isinstance(record["human_verdict"], str)
                 and record["human_verdict"] in VERDICTS),
                 f"{label}[{index}].human_verdict 无效")
        _require("judge_verdict" not in record or (isinstance(record["judge_verdict"], str)
                 and record["judge_verdict"] in VERDICTS),
                 f"{label}[{index}].judge_verdict 无效")
        _require("judge_valid" not in record or isinstance(record["judge_valid"], bool),
                 f"{label}[{index}].judge_valid 无效")
        indexed[record["sample_id"]] = record
    return indexed


def calibration_report(gold: list[dict[str, Any]], judged: list[dict[str, Any]]) -> dict[str, Any]:
    """基于人工金标计算裁判一致率；没有可对齐人工标签时显式返回未校准。"""
    gold_by_id = _records_by_sample(gold, "gold")
    judged_by_id = _records_by_sample(judged, "judged")
    aligned_ids = sorted(set(gold_by_id) & set(judged_by_id))
    annotated = [sample_id for sample_id in aligned_ids if gold_by_id[sample_id].get("human_verdict") in VERDICTS]
    invalid = [sample_id for sample_id in judged_by_id if judged_by_id[sample_id].get("judge_valid") is not True]
    invalid_rate = len(invalid) / len(judged_by_id) if judged_by_id else None
    base = {
        "aligned_samples": len(aligned_ids), "human_annotated_samples": len(annotated),
        "invalid_reference_rate": invalid_rate, "invalid_reference_samples": invalid,
    }
    if not annotated:
        return {"status": "uncalibrated", "agreement_rate": None, "confusion_matrix": {}, **base}

    matrix: dict[str, dict[str, int]] = {human: {judge: 0 for judge in VERDICTS} for human in VERDICTS}
    scored = 0
    agreements = 0
    for sample_id in annotated:
        human = gold_by_id[sample_id]["human_verdict"]
        judge = judged_by_id[sample_id].get("judge_verdict")
        if judged_by_id[sample_id].get("judge_valid") is not True or judge not in VERDICTS:
            continue
        matrix[human][judge] += 1
        scored += 1
        agreements += int(human == judge)
    if not scored:
        return {"status": "uncalibrated", "agreement_rate": None, "confusion_matrix": {}, **base}
    return {"status": "calibrated", "agreement_rate": agreements / scored, "confusion_matrix": matrix,
            "scored_samples": scored, **base}
