"""影响标签选择和断言风险标注；不改变冻结 Plan 的契约。"""
from __future__ import annotations

from behavior_contract import STRESS_SUITE_VERSION, SUITE_VERSION, require, suite_case_ids


IMPACT_LABELS = frozenset({"permissions", "cancellation", "tool-contract", "context", "prompt", "model", "all"})
_IMPACT_CASES = {
    "permissions": {
        SUITE_VERSION: ("B08", "B16", "B17", "B18"),
        STRESS_SUITE_VERSION: ("S03", "S04", "S05"),
    },
    "cancellation": {
        SUITE_VERSION: ("B06", "B14", "B15"),
        STRESS_SUITE_VERSION: (),
    },
    "tool-contract": {
        SUITE_VERSION: tuple(f"B{number:02d}" for number in range(2, 19)),
        STRESS_SUITE_VERSION: tuple(f"S{number:02d}" for number in range(1, 7)),
    },
}
_FULL_SUITE_IMPACTS = frozenset({"context", "prompt", "model", "all"})
SELECTION_FORMAT = "skillforge-behavior-impact-selection"
SELECTION_VERSION = 1


def parse_impacts(value):
    """解析稳定顺序的逗号标签，拒绝空项、重复项和未知标签。"""
    require(isinstance(value, str) and bool(value), "impact_required")
    impacts = tuple(part.strip() for part in value.split(","))
    require(all(impacts) and all(impact in IMPACT_LABELS for impact in impacts), "invalid_impact_label")
    require(len(impacts) == len(set(impacts)), "duplicate_impact_label")
    return impacts


def select_case_ids(impacts, suite_version):
    """多标签取并集，并始终按 Suite 的既有顺序返回。"""
    require(suite_version in {SUITE_VERSION, STRESS_SUITE_VERSION}, "unsupported_suite")
    require(isinstance(impacts, tuple) and impacts and all(impact in IMPACT_LABELS for impact in impacts),
            "invalid_impact_label")
    selected = set()
    for impact in impacts:
        if impact in _FULL_SUITE_IMPACTS:
            selected.update(suite_case_ids(suite_version))
        else:
            selected.update(_IMPACT_CASES[impact][suite_version])
    return [case_id for case_id in suite_case_ids(suite_version) if case_id in selected]


def selection_metadata(impacts, suite_version):
    """标签策略属于运行选择的侧文件，不能混入 Plan schema。"""
    require(isinstance(impacts, tuple) and impacts, "impact_required")
    return {"format": SELECTION_FORMAT, "schema_version": SELECTION_VERSION,
            "suite_version": suite_version, "impact": list(impacts),
            "case_ids": select_case_ids(impacts, suite_version)}


def validate_selection_metadata(value, suite_version=None):
    require(isinstance(value, dict) and set(value) == {"format", "schema_version", "suite_version", "impact", "case_ids"},
            "invalid_impact_selection")
    require(value["format"] == SELECTION_FORMAT and value["schema_version"] == SELECTION_VERSION,
            "invalid_impact_selection")
    selected_suite = value["suite_version"]
    require(selected_suite in {SUITE_VERSION, STRESS_SUITE_VERSION}
            and (suite_version is None or selected_suite == suite_version), "frozen_impact_suite_mismatch")
    impacts = value["impact"]
    require(isinstance(impacts, list) and impacts, "invalid_impact_selection")
    parsed = parse_impacts(",".join(impacts))
    require(list(parsed) == impacts and value["case_ids"] == select_case_ids(parsed, selected_suite),
            "invalid_impact_selection")
    return value


def _risk(assertion):
    """以断言自身表达的风险分类，避免把 Case 的最高风险覆盖所有断言。"""
    if assertion["evaluator"] != "code" or assertion["kind"] == "explanation":
        return "P2"
    selector = assertion["selector"]
    assertion_id = assertion["id"]
    if (assertion["kind"] in {"absent", "effects", "cancelled"}
            or selector.get("tool") == "write_record"
            or assertion_id in {"attempt_limit", "bounded_query", "denied_status"}
            or assertion_id.startswith(("no_write", "no_unauthorized", "no_injected"))):
        return "P0"
    return "P1"


def _require_projection_assertion(assertion):
    """风险投影只读取这些字段；不把当前 Suite 的执行资格带进导出。"""
    require(isinstance(assertion, dict) and isinstance(assertion.get("id"), str) and bool(assertion["id"])
            and isinstance(assertion.get("evaluator"), str) and isinstance(assertion.get("kind"), str)
            and isinstance(assertion.get("selector"), dict), "invalid_risk_annotation_assertion")
    if assertion["kind"] == "any_of":
        require(isinstance(assertion.get("expected"), list)
                and all(isinstance(alternative, list) for alternative in assertion["expected"]),
                "invalid_risk_annotation_assertion")


def _annotation_rows(case_id, assertion, path):
    _require_projection_assertion(assertion)
    yield {"case_id": case_id, "assertion_id": assertion["id"], "assertion_path": path,
           "risk": _risk(assertion)}
    if assertion["kind"] == "any_of":
        for alternative, assertions in enumerate(assertion["expected"]):
            for index, nested in enumerate(assertions):
                yield from _annotation_rows(case_id, nested, f"{path}/any_of/{alternative}/{index}")


def risk_annotations(plan):
    """返回可供导出器消费的逐断言风险 metadata，包含历史冻结 Plan 的嵌套断言。"""
    require(isinstance(plan, dict) and isinstance(plan.get("cases"), list), "invalid_risk_annotation_plan")
    for case in plan["cases"]:
        require(isinstance(case, dict) and isinstance(case.get("id"), str) and bool(case["id"])
                and isinstance(case.get("assertions"), list), "invalid_risk_annotation_plan")
    return [row for case in plan["cases"] for index, assertion in enumerate(case["assertions"])
            for row in _annotation_rows(case["id"], assertion, f"/assertions/{index}")]
