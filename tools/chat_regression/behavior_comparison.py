"""冻结实验与离线效果比较。只消费原批次文件，绝不重放任务或改写原始结论。"""
from __future__ import annotations

from collections import Counter
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re

from behavior_contract import digest, require, suite_case_ids


def sha_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _write_new(destination, result, name, text):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    (destination / (name + ".json")).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    (destination / (name + ".md")).write_text(text, encoding="utf-8")


def _separate(destination, roots):
    destination = Path(destination).resolve()
    require(not destination.exists(), "comparison_destination_exists")
    require(all(not destination.is_relative_to(root) and not root.is_relative_to(destination) for root in roots),
            "comparison_destination_must_be_separate")
    return destination


def _snapshot(root):
    root = Path(root).resolve()
    require(not (root / "execution.lock").exists(), "comparison_source_running")
    names = ("plan.json", "batch.json", "evaluation.json", "configuration.json", "private.json")
    files = {name: sha_file(root / name) for name in names}
    for path in (root / "cases").glob("*/*/*"):
        if path.name in {"state.json", "evidence.json"} or re.fullmatch(r"turn-\d+\.sse\.jsonl", path.name):
            files[path.relative_to(root).as_posix()] = sha_file(path)
    plan, batch, report, config, private = (read(root / name) for name in names)
    # 历史 Product 候选不能重新套用今天的生成语义校验；这里只核对冻结契约与身份。
    require(plan.get("format") == "skillforge-behavior-plan" and plan.get("schema_version") == 1
            and plan.get("fingerprint") == digest({k: v for k, v in plan.items() if k != "fingerprint"}),
            "comparison_plan_not_bound")
    case_ids = [c["id"] for c in plan["cases"]]
    require(len(case_ids) == len(set(case_ids)) and set(case_ids) == set(suite_case_ids(plan["suite_version"])),
            "comparison_plan_cases_invalid")
    require(batch.get("lifecycle") == report.get("lifecycle") == "finalized", "comparison_source_not_finalized")
    require(report.get("binding", {}).get("plan_fingerprint") == plan["fingerprint"]
            and all(report["binding"].get(k) == v for k, v in batch.get("binding", {}).items()),
            "comparison_report_not_bound")
    require(report["binding"].get("config_digest") == digest(config), "comparison_configuration_not_bound")
    local = plan.get("local_selection", {})
    selected = local.get("case_ids", [c["id"] for c in plan["cases"]])
    repetitions = local.get("repetitions", plan["repetitions"])
    require(type(repetitions) is int and repetitions > 0 and bool(selected)
            and len(selected) == len(set(selected)) and set(selected) <= {c["id"] for c in plan["cases"]}, "comparison_matrix_invalid")
    matrix = [{"case_id": cid, "repetition": r} for cid in selected for r in range(1, repetitions + 1)]
    # 随机 canary 仅按私有准备收据规范化；业务事实、任务和硬断言不做宽松匹配。
    cases = [deepcopy(c) for c in plan["cases"] if c["id"] in selected]
    canaries = [x for x in private.get("sensitive_values", []) if isinstance(x, str) and x.startswith("SF-PRIVATE-")]
    def normalise(value):
        if isinstance(value, str):
            for canary in canaries:
                value = value.replace(canary, "<SYNTHETIC-CANARY>")
            return value
        if isinstance(value, list):
            return [normalise(x) for x in value]
        if isinstance(value, dict):
            return {k: normalise(v) for k, v in value.items()}
        return value
    # 实际配置正文独立于 plan 中的 profile 文本；只规范化准备器生成的私密 canary。
    prompts, controls = {}, normalise(deepcopy(config))
    for key, agent in controls.get("agents", {}).items():
        for field in ("system_prompt", "role_description"):
            if isinstance(agent.get(field), str):
                source = "agent:" + key + ":" + field
                prompts[source] = digest(agent[field])
                agent[field] = {"prompt_source": source}
    profile_controls = deepcopy(plan.get("profiles", []))
    for profile in profile_controls:
        # profile.prompt 是准备器的配置输入副本；校验配置正文后才能排除该副本。
        if "prompt" in profile:
            key = profile.get("key")
            require(profile["prompt"] == config.get("agents", {}).get(key, {}).get("system_prompt"),
                    "comparison_profile_prompt_not_bound")
            profile["prompt"] = {"prompt_source": "agent:" + str(key) + ":system_prompt"}
    # scheduler 由已校验的计划和批次策略导出；双 lane 根配置不冒充单 Agent 配置。
    from behavior_measurements import enrich_report
    measured = enrich_report(root, report)
    context = measured["metrics"]["comparison_context"]
    missing = []
    if not config.get("agents") or not config.get("model"):
        missing.append("configuration_controls_missing")
    budgets = {k: batch.get("budget", {}).get(k) for k in ("max_tokens", "max_turns", "max_seconds")}
    if any(type(v) is not int or v <= 0 for v in budgets.values()):
        missing.append("budget_controls_missing")
    frozen = {"configuration": controls, "cases": normalise(cases), "profiles": normalise(profile_controls),
              "selection": {"case_ids": selected, "repetitions": repetitions, "matrix": matrix},
              "execution": context["execution"], "local_selection": local, "budget_limits": budgets}
    return {"root": root, "files": files, "report": report, "plan": plan, "private": private,
            "prompts": prompts, "controls_digest": digest(frozen), "selection": frozen["selection"],
            "configuration": config, "measurements": measured, "missing_controls": missing}


def _verify_unchanged(snapshot):
    require(not (snapshot["root"] / "execution.lock").exists(), "comparison_source_running")
    current = {p.relative_to(snapshot["root"]).as_posix() for p in (snapshot["root"] / "cases").glob("*/*/*")
               if p.name in {"state.json", "evidence.json"} or re.fullmatch(r"turn-\d+\.sse\.jsonl", p.name)}
    require(current == {n for n in snapshot["files"] if n.startswith("cases/")}, "comparison_source_changed")
    require(all(sha_file(snapshot["root"] / name) == value for name, value in snapshot["files"].items()),
            "comparison_source_changed")


def freeze_experiment(baseline_root, candidate_root, destination, mode="repeat", prompt_sources=()):
    require(mode in {"repeat", "prompt"}, "comparison_mode_invalid")
    left, right = _snapshot(baseline_root), _snapshot(candidate_root)
    destination = _separate(destination, (left["root"], right["root"]))
    sources = list(prompt_sources)
    require(len(sources) == len(set(sources)), "duplicate_prompt_source")
    require(bool(sources) == (mode == "prompt"), "prompt_sources_required_only_for_prompt_experiment")
    require(all(s in left["prompts"] and s in right["prompts"] for s in sources), "unknown_prompt_source")
    changes = [{"source": s, "baseline_hash": left["prompts"][s], "candidate_hash": right["prompts"][s]} for s in sorted(sources)]
    require(all(c["baseline_hash"] != c["candidate_hash"] for c in changes), "declared_prompt_unchanged")
    result = {"schema_version": 1, "format": "skillforge-evaluation-experiment", "mode": mode,
              "baseline": {"root": str(left["root"]), "files": left["files"]},
              "candidate": {"root": str(right["root"]), "files": right["files"]}, "changes": changes}
    result["fingerprint"] = digest(result)
    _verify_unchanged(left); _verify_unchanged(right)
    _write_new(destination, result, "experiment", "# 冻结评测实验\n\n仅声明比较输入和提示词变量；不证明可比、改善或发布资格。\n")
    return result


def _rows(report):
    rows = {}
    for row in report.get("cases", []):
        key = (row["case_id"], row["repetition"])
        require(key not in rows, "comparison_duplicate_sample")
        require(row.get("behavior_status") in {"passed", "failed", "unknown"}, "comparison_invalid_status")
        rows[key] = row
    return rows


def _status(row):
    if row is None or any(r.get("detail") == "experiment_not_run" for r in row.get("blocking_reasons", [])):
        return "not_run"
    return row["behavior_status"]


def transitions(left, right, matrix):
    before, after = _rows(left), _rows(right)
    planned = {(row["case_id"], row["repetition"]) for row in matrix}
    require(set(before) <= planned and set(after) <= planned, "comparison_sample_outside_matrix")
    counts, cases = Counter(), []
    for item in matrix:
        key = (item["case_id"], item["repetition"])
        old, new = before.get(key), after.get(key)
        a, b = _status(old), _status(new)
        state = ({("passed", "failed"): "regressed", ("failed", "passed"): "improved",
                  ("passed", "passed"): "stable_pass", ("failed", "failed"): "stable_fail"}.get((a, b), "unresolved"))
        counts[state] += 1
        assertions = []
        indexes = []
        for row in (old, new):
            values = (row or {}).get("assertions", [])
            index = {x["id"]: x for x in values}
            require(len(index) == len(values), "comparison_duplicate_assertion")
            require(all(x.get("status") in {"pass", "fail", "unknown", "not_applicable"} for x in values),
                    "comparison_invalid_assertion_status")
            indexes.append(index)
        for identity in sorted(set(indexes[0]) | set(indexes[1])):
            assertions.append({"id": identity, "baseline": indexes[0].get(identity, {}).get("status", "missing"),
                               "candidate": indexes[1].get(identity, {}).get("status", "missing")})
        cases.append({**item, "baseline": a, "candidate": b, "transition": state, "assertions": assertions})
    def summary(rows):
        values = Counter(_status(rows.get(key)) for key in planned)
        return {"planned": len(planned), **{s: values[s] for s in ("passed", "failed", "unknown", "not_run")},
                "pass_rate": values["passed"] / len(planned) if planned else None}
    repeated = []
    for cid in sorted({key[0] for key in planned}):
        samples = [c for c in cases if c["case_id"] == cid]
        record = {"case_id": cid, "planned_repetitions": len(samples)}
        for side in ("baseline", "candidate"):
            values = [s[side] for s in samples]
            record[side] = {"passed": values.count("passed"), "all_pass": all(v == "passed" for v in values)
                           if all(v in {"passed", "failed"} for v in values) else None}
        repeated.append(record)
    return {"counts": {k: counts[k] for k in ("improved", "regressed", "stable_pass", "stable_fail", "unresolved")},
            "baseline": summary(before), "candidate": summary(after), "cases": cases, "repeated_observations": repeated,
            "interpretation": "observed_samples_only_not_population_reliability"}


def compare_experiment(experiment_path, destination):
    experiment_path = Path(experiment_path).resolve()
    experiment = read(experiment_path)
    require(experiment.get("format") == "skillforge-evaluation-experiment" and experiment.get("schema_version") == 1
            and experiment.get("fingerprint") == digest({k: v for k, v in experiment.items() if k != "fingerprint"}),
            "comparison_experiment_invalid")
    left, right = (_snapshot(experiment[s]["root"]) for s in ("baseline", "candidate"))
    destination = _separate(destination, (left["root"], right["root"], experiment_path.parent))
    for side, snapshot in (("baseline", left), ("candidate", right)):
        require(snapshot["files"] == experiment[side]["files"], "comparison_experiment_input_changed")
    mode = experiment.get("mode")
    require(mode in {"repeat", "prompt"}, "comparison_mode_invalid")
    declared = {c["source"]: c for c in experiment["changes"]}
    require(len(declared) == len(experiment["changes"]) and bool(declared) == (mode == "prompt"), "comparison_changes_invalid")
    changed = {s for s in set(left["prompts"]) | set(right["prompts"]) if left["prompts"].get(s) != right["prompts"].get(s)}
    reasons = left["missing_controls"] + right["missing_controls"]
    if changed != set(declared): reasons.append("undeclared_or_missing_prompt_change")
    for s, change in declared.items():
        require(change["baseline_hash"] == left["prompts"].get(s) and change["candidate_hash"] == right["prompts"].get(s),
                "comparison_prompt_hash_mismatch")
    if left["controls_digest"] != right["controls_digest"]: reasons.append("non_prompt_controls_changed")
    binding_keys = ("suite_version", "evaluator_version", "model_id", "model_version", "settlement_seconds", "evidence_mode", "deployment_revision", "harness_digest", "dataset_fingerprint")
    for key in binding_keys:
        if left["report"]["binding"].get(key) != right["report"]["binding"].get(key): reasons.append("binding_mismatch:" + key)
    for snapshot in (left, right):
        for key in ("deployment_revision", "suite_version", "evaluator_version", "model_id", "settlement_seconds", "evidence_mode"):
            if not snapshot["report"]["binding"].get(key): reasons.append("binding_missing:" + key)
        if snapshot["plan"]["suite_version"] == "product-tasks-1":
            for key in ("dataset_fingerprint", "harness_digest"):
                if not snapshot["report"]["binding"].get(key): reasons.append("binding_missing:" + key)
            if snapshot["report"]["binding"].get("dataset_fingerprint") != snapshot["plan"].get("dataset_fingerprint"):
                reasons.append("dataset_not_bound")
        if not snapshot["report"]["binding"].get("model_version") or snapshot["report"]["binding"].get("model_version") == "unknown":
            reasons.append("model_version_unknown")
        if any(r["code"] in {"invalid_environment", "invalid_config_drift"} for r in snapshot["report"].get("blocking_reasons", [])):
            reasons.append("invalid_source_batch")
    before, after = _rows(left["report"]), _rows(right["report"])
    for key in set(before) & set(after):
        a, b = before[key], after[key]
        if _status(a) in {"passed", "failed"} and _status(b) in {"passed", "failed"}:
            ids = [{r["id"] for r in row.get("assertions", [])} for row in (a, b)]
            if not ids[0] or ids[0] != ids[1]: reasons.append("evaluated_assertion_inventory_changed_or_empty")
    result = {"schema_version": 1, "format": "skillforge-evaluation-comparison", "experiment_fingerprint": experiment["fingerprint"],
              "source_files": {"baseline": left["files"], "candidate": right["files"]},
              "quality": {"comparable": not reasons, "reasons": sorted(set(reasons))}, "release_eligible": False}
    if not reasons:
        result["quality"]["results"] = transitions(left["report"], right["report"], left["selection"]["matrix"])
    from behavior_reporting import measurement_comparability, _redact
    result["measurements"] = measurement_comparability(left["measurements"], right["measurements"], quality_comparable=not reasons)
    result = _redact(result, tuple(left["private"].get("sensitive_values", []) + right["private"].get("sensitive_values", [])))
    _verify_unchanged(left); _verify_unchanged(right)
    lines = ["# 提示词与行为对比", "", "质量可比：" + str(not reasons), "",
             "原因：" + (", ".join(sorted(set(reasons))) or "冻结控制条件一致"), ""]
    if not reasons:
        delta = result["quality"]["results"]
        lines += ["| 场景 | 重复 | 基线 | 候选 | 变化 |", "|---|---:|---|---|---|"]
        lines += [f"| {r['case_id']} | {r['repetition']} | {r['baseline']} | {r['candidate']} | {r['transition']} |" for r in delta["cases"]]
        lines += ["", "硬断言差异：", "", "| 场景 / 重复 | 断言 | 基线 | 候选 |", "|---|---|---|---|"]
        def cell(value):
            return str(value).replace("|", "&#124;").replace("\n", " ").replace("\r", " ").replace("<", "&lt;")
        lines += [f"| {r['case_id']} / {r['repetition']} | {cell(a['id'])} | {a['baseline']} | {a['candidate']} |"
                  for r in delta["cases"] for a in r["assertions"] if a["baseline"] != a["candidate"]]
        lines += ["", "完整分母与重复观察：", "", "```json", json.dumps({k: delta[k] for k in ("baseline", "candidate", "counts")}, ensure_ascii=False, indent=2), "```"]
    lines += ["", "费用、延迟、用量可比性：", "", "```json", json.dumps(result["measurements"], ensure_ascii=False, indent=2), "```",
              "", "仅反映当前样本；未知和未运行不算通过。本报告不授予发布资格。", ""]
    _write_new(destination, result, "comparison", "\n".join(lines))
    return result
