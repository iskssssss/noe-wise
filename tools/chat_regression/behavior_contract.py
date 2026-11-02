"""行为评测的封闭契约、生命周期和发布资格；不执行模型或业务工具。"""
from __future__ import annotations

import hashlib
import json
import math
import re
from datetime import datetime, timezone

VERSION = 1
EVALUATOR_VERSION = "behavior-9"
SUITE_VERSION = "chat-tools-7"
STRESS_SUITE_VERSION = "chat-stress-1"
PRODUCT_SUITE_VERSION = "product-tasks-1"


def suite_case_ids(suite_version):
    require(suite_version in {SUITE_VERSION, STRESS_SUITE_VERSION, PRODUCT_SUITE_VERSION}, "unsupported_suite")
    if suite_version == PRODUCT_SUITE_VERSION:
        return [f"D{i:02d}" for i in range(1, 13)]
    return ([f"B{i:02d}" for i in range(1, 19)] if suite_version == SUITE_VERSION else
            [f"S{i:02d}" for i in range(1, 7)])
KINDS = {"tool_count", "arguments", "number", "equals", "url", "absent",
         "terminal", "order", "any_of", "cancelled", "effects", "explanation", "tool_unknown", "fixture_succeeded", "fixture_failed",
         "tool_attempted", "tool_refused", "execution_succeeded", "execution_failed", "execution_unknown"}
REASONS = {"failed_behavior", "invalid_environment", "invalid_config_drift", "incomplete", "budget_exceeded"}
BINDING_KEYS = ("deployment_revision", "suite_version", "evaluator_version", "config_digest",
                "model_id", "model_version", "settlement_seconds")


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                   separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def version(value, format_name):
    require(isinstance(value, dict) and type(value.get("schema_version")) is int
            and value["schema_version"] == VERSION and value.get("format") == format_name,
            "unsupported_schema:" + format_name)


def validate_assertion(item):
    require(isinstance(item, dict), "assertion_object_required")
    required = {"id", "evaluator", "blocking", "kind", "selector", "expected", "options", "evidence_requirements"}
    require(set(item) == required, "assertion_fields_invalid")
    require(isinstance(item["id"], str) and bool(item["id"]), "assertion_id_required")
    require(item["evaluator"] in {"code", "manual_review", "judge"}, "invalid_evaluator")
    require(type(item["blocking"]) is bool, "blocking_boolean_required")
    require(not item["blocking"] or item["evaluator"] == "code", "semantic_assertion_cannot_block")
    require(item["kind"] in KINDS, "unsupported_assertion_kind")
    require(isinstance(item["selector"], dict) and isinstance(item["options"], dict), "invalid_assertion_options")
    kind, selector, options, expected = (item[k] for k in ("kind", "selector", "options", "expected"))
    call_kinds = {"tool_count", "arguments", "order", "fixture_succeeded", "fixture_failed"}
    execution_kinds = {"tool_attempted", "tool_refused", "execution_succeeded", "execution_failed", "execution_unknown"}
    fields = {"tool"} if kind in execution_kinds else {"tool", "turn", "case_key"} if kind in call_kinds else (
        {"pointer"} if kind in {"number", "equals", "url", "absent"} else set())
    require(set(selector) <= fields, "unknown_assertion_selector")
    require(all(isinstance(v, str) and bool(v) for k, v in selector.items() if k != "turn"), "invalid_selector_value")
    if "turn" in selector:
        require(type(selector["turn"]) is int and selector["turn"] >= 0, "invalid_turn_selector")
    if "pointer" in fields:
        require("pointer" in selector and selector["pointer"].startswith("/")
                and not re.search(r"~(?:[^01]|$)", selector["pointer"]), "invalid_json_pointer")
    allowed_options = {"abs_tol", "rel_tol"} if kind == "number" else (
        {"ignore_query_parameters", "aliases"} if kind == "url" else set())
    require(set(options) <= allowed_options, "unknown_assertion_option")
    require(kind == "explanation" or item["evaluator"] == "code", "deterministic_assertion_requires_code")
    require(kind != "explanation" or item["evaluator"] != "code", "explanation_requires_review")
    if kind in {"tool_count", "effects", "fixture_succeeded", "fixture_failed"} | execution_kinds:
        require(isinstance(expected, dict) and bool(expected) and set(expected) <= {"min", "max"}, "invalid_count_bounds")
        require(all(type(v) is int and v >= 0 for v in expected.values()), "invalid_count_bound")
        require(expected.get("min", 0) <= expected.get("max", float("inf")), "inverted_count_bounds")
        require(kind != "effects" or set(expected) == {"min", "max"}, "effect_bounds_required")
    if kind == "arguments":
        require(isinstance(expected, dict) and bool(expected) and all(
            isinstance(p, str) and p.startswith("/") and not re.search(r"~(?:[^01]|$)", p) for p in expected), "invalid_argument_pointers")
    if kind in {"terminal", "order", "absent"}:
        require(isinstance(expected, list) and bool(expected) and all(isinstance(x, str) and x for x in expected), "invalid_expected_list")
    if kind == "cancelled":
        require(expected is True, "cancelled_expected_true")
    if kind == "tool_unknown":
        require(isinstance(expected, str) and bool(expected), "unknown_reason_required")
    if kind == "url":
        require(isinstance(expected, str) and bool(expected), "invalid_expected_url")
        require(all(isinstance(v, list) and all(isinstance(x, str) and x for x in v) for v in options.values()), "invalid_url_options")
    require(isinstance(item["evidence_requirements"], list)
            and all(x in {"trace", "audit", "terminal", "context", "quiescence"}
                    for x in item["evidence_requirements"]), "unknown_evidence_requirement")
    if item["kind"] == "number":
        from decimal import Decimal
        require(type(expected) in (int, float) and Decimal(str(expected)).is_finite(), "invalid_expected_number")
        for key in ("abs_tol", "rel_tol"):
            tolerance = Decimal(str(item["options"].get(key, 0)))
            require(tolerance.is_finite() and tolerance >= 0, "invalid_numeric_tolerance")
    if item["kind"] == "any_of":
        paths = item["expected"]
        require(isinstance(paths, list) and bool(paths) and all(isinstance(p, list) and p for p in paths), "empty_paths")
        for path in paths:
            for assertion in path:
                validate_assertion(assertion)
                require(assertion["evaluator"] == "code", "path_requires_code_assertions")


def validate_plan(plan):
    version(plan, "skillforge-behavior-plan")
    if plan.get("suite_version") == PRODUCT_SUITE_VERSION:
        from behavior_product_suite import validate_product_plan
        return validate_product_plan(plan)
    require(set(plan) - {"local_selection"} == {"schema_version", "format", "suite_version", "batch", "repetitions", "settlement_seconds",
                          "profiles", "cases", "fingerprint"}, "invalid_plan_fields")
    wanted_cases = suite_case_ids(plan.get("suite_version"))
    require(type(plan.get("repetitions")) is int and plan["repetitions"] == 3, "suite_requires_three_repetitions")
    require(type(plan.get("settlement_seconds")) is int and 0 < plan["settlement_seconds"] <= 600, "invalid_settlement_window")
    require(isinstance(plan.get("cases"), list) and len(plan["cases"]) == len(wanted_cases), "suite_case_count_mismatch")
    seen = set()
    for case in plan["cases"]:
        require(isinstance(case, dict) and set(case) == {"id", "name", "profile", "tool_schema_version", "turns",
                                                       "value", "script", "max_attempts", "cancel", "stop_on_unknown", "assertions"}, "invalid_case_fields")
        require(type(case["cancel"]) is bool and type(case["stop_on_unknown"]) is bool
                and not (case["cancel"] and case["stop_on_unknown"]), "invalid_case_stop_policy")
        require(case.get("id") not in seen and isinstance(case.get("id"), str), "duplicate_case")
        seen.add(case["id"])
        require(bool(case.get("tool_schema_version")), "tool_schema_version_required")
        require(isinstance(case.get("turns"), list) and bool(case["turns"]), "case_turns_required")
        require(type(case.get("max_attempts")) is int and case["max_attempts"] > 0, "invalid_attempt_limit")
        ids = set()
        for assertion in case["assertions"]:
            validate_assertion(assertion)
            require(assertion["id"] not in ids, "duplicate_assertion")
            ids.add(assertion["id"])
        require(any(a["blocking"] for a in case["assertions"]), "case_requires_hard_assertion")
    require(seen == set(wanted_cases), "suite_case_identity_mismatch")
    if "local_selection" in plan:
        selection = plan["local_selection"]
        require(isinstance(selection, dict) and set(selection) - {"workers"} == {"case_ids", "repetitions", "turn_timeout_seconds"}, "invalid_local_selection")
        require(type(selection.get("workers", 1)) is int and selection.get("workers", 1) in (1, 2), "invalid_local_workers")
        ids = selection["case_ids"]
        require(isinstance(ids, list) and bool(ids) and all(isinstance(i, str) and i in seen for i in ids)
                and len(ids) == len(set(ids)), "invalid_selected_cases")
        require(type(selection["repetitions"]) is int and 1 <= selection["repetitions"] <= 100, "invalid_local_repetitions")
        require(type(selection["turn_timeout_seconds"]) is int and 1 <= selection["turn_timeout_seconds"] <= 540,
                "invalid_local_turn_timeout")
    frozen = {k: v for k, v in plan.items() if k != "fingerprint"}
    require(plan.get("fingerprint") == digest(frozen), "plan_fingerprint_mismatch")
    return plan


def experiments(plan):
    selection = plan.get("local_selection", {})
    selected = selection.get("case_ids", [c["id"] for c in plan["cases"]])
    return [(cid, repetition) for cid in selected
            for repetition in range(1, selection.get("repetitions", plan["repetitions"]) + 1)]


def issue(code, detail, **refs):
    require(code in REASONS, "unknown_blocking_reason")
    return {"code": code, "detail": detail, **refs}


def transition(batch, target):
    allowed = {"planned": {"preparing", "settling"}, "preparing": {"running", "settling"},
               "running": {"settling"}, "settling": {"finalized"}, "finalized": set()}
    require(target in allowed.get(batch["lifecycle"], set()), "invalid_lifecycle_transition")
    batch["lifecycle"] = target
    batch.setdefault("lifecycle_events", []).append({"state": target, "at": now()})


def aggregate(plan, cases, binding, reasons=(), lifecycle="finalized"):
    validate_plan(plan)
    indexed = {}
    matrix = experiments(plan)
    blockers = list(reasons)
    for result in cases:
        key = (result["case_id"], result["repetition"])
        require(key not in indexed, "duplicate_experiment")
        require(key in matrix, "unknown_experiment")
        indexed[key] = result
    rows = []
    for case_id, repetition in matrix:
        result = indexed.get((case_id, repetition))
        if result is None:
            result = {"case_id": case_id, "repetition": repetition, "behavior_status": "unknown",
                      "blocking_reasons": [issue("incomplete", "experiment_not_run")], "assertions": [], "metrics": {}}
        rows.append(result)
    for row in rows:
        blockers.extend({**r, "case_id": row["case_id"], "repetition": row["repetition"]}
                        for r in row["blocking_reasons"])
    groups = []
    for case in plan["cases"]:
        sample = [r for r in rows if r["case_id"] == case["id"]]
        if not sample:
            continue
        complete = all(r["behavior_status"] in {"passed", "failed"} and
                       not any(x["code"] != "failed_behavior" for x in r["blocking_reasons"]) for r in sample)
        successes = sum(r["behavior_status"] == "passed" for r in sample)
        groups.append({"case_id": case["id"], "n": len(sample), "passed": successes,
                       "sample_pass_rate": successes / len(sample) if complete else None,
                       "any_pass": successes > 0 if complete else None,
                       "all_pass": successes == len(sample) if complete else None})
    status = "failed" if any(r["behavior_status"] == "failed" for r in rows) else (
        "passed" if all(r["behavior_status"] == "passed" for r in rows) else "unknown")
    return {"schema_version": VERSION, "format": "skillforge-behavior-evaluation", "lifecycle": lifecycle,
            "binding": {**binding, "plan_fingerprint": plan["fingerprint"]}, "behavior_status": status,
            "blocking_reasons": blockers, "cases": rows, "metrics": {"planned": len(matrix), "executed": len(indexed),
                "out_of_scope": [c["id"] for c in plan["cases"] if not any(cid == c["id"] for cid, _ in matrix)],
                "evaluated": sum(r["behavior_status"] in {"passed", "failed"} for r in rows),
                "passed": sum(r["behavior_status"] == "passed" for r in rows), "samples": groups},
            "release_eligible": lifecycle == "finalized" and status == "passed" and not blockers
                                and plan["suite_version"] == SUITE_VERSION
                                and "local_selection" not in plan and binding.get("validation_scope") != "local"}


def release_check(report, expected, plan):
    """仅验证冻结证据；受保护 CI 来源由调用工作流额外核实，不能凭手填布尔值授权。"""
    version(report, "skillforge-behavior-evaluation")
    validate_plan(plan)
    require(isinstance(expected, dict) and all(k in expected for k in BINDING_KEYS), "expected_binding_incomplete")
    problems = []
    if plan["suite_version"] != SUITE_VERSION:
        problems.append("extension_suite_not_release_matrix")
    if "local_selection" in plan or report.get("binding", {}).get("validation_scope") == "local":
        problems.append("local_evidence_not_release_qualified")
    for key in (*BINDING_KEYS, "plan_fingerprint"):
        if key not in expected or (key != "model_version" and not expected[key]) or report.get("binding", {}).get(key) != expected[key]:
            problems.append("binding_mismatch:" + key)
    if expected.get("plan_fingerprint") != plan["fingerprint"]:
        problems.append("trusted_plan_mismatch")
    if report.get("lifecycle") != "finalized" or report.get("blocking_reasons"):
        problems.append("batch_not_qualified")
    rows = report.get("cases", [])
    wanted = {(f"B{i:02d}", r) for i in range(1, 19) for r in range(1, 4)}
    if len(rows) != 54 or {(r.get("case_id"), r.get("repetition")) for r in rows} != wanted:
        problems.append("incomplete_matrix")
    for row in rows:
        spec = next((c for c in plan["cases"] if c["id"] == row.get("case_id")), None)
        inventory = [(a.get("id"), a.get("evaluator"), a.get("blocking")) for a in row.get("assertions", [])]
        wanted_inventory = [(a["id"], a["evaluator"], a["blocking"]) for a in spec["assertions"]] if spec else []
        if sorted(inventory) != sorted(wanted_inventory):
            problems.append("assertion_inventory_mismatch:" + str(row.get("case_id")))
        hard = [a for a in row.get("assertions", []) if a.get("blocking") is True]
        if row.get("behavior_status") != "passed" or row.get("blocking_reasons") or not hard or any(
                a.get("evaluator") != "code" or a.get("status") != "pass" for a in hard):
            problems.append("experiment_not_qualified:" + str(row.get("case_id")))
    return {"qualified": not problems, "problems": problems}
