"""冻结失败到当前套件回归计划的离线桥接；不执行模型、不宣称复现。"""
from pathlib import Path
import re
import secrets
import shutil

from behavior_contract import digest, require, validate_plan, version
from behavior_opik_export import sha_file
from behavior_suite import make_plan
from runner import read, save


def _normalise(value, canaries):
    if isinstance(value, str):
        for canary in canaries:
            value = value.replace(canary, "<SYNTHETIC-CANARY>")
        return value
    if isinstance(value, list):
        return [_normalise(v, canaries) for v in value]
    if isinstance(value, dict):
        return {k: _normalise(v, canaries) for k, v in value.items()}
    return value


def regression_plan(source, destination, batch, *, dataset_root=None, case_ids=None):
    """创建新资源身份的当前版本计划；旧任务或断言漂移写入独立来源清单。"""
    source, destination = Path(source).resolve(), Path(destination).resolve()
    require(not destination.is_relative_to(source) and not source.is_relative_to(destination),
            "regression_destination_must_be_separate")
    require(not destination.exists(), "regression_destination_exists")
    require(isinstance(batch, str) and re.fullmatch(r"[a-z0-9][a-z0-9-]{0,39}", batch), "invalid_batch_name")
    require(not (source / "execution.lock").exists(), "regression_source_running")
    paths = [source / name for name in ("plan.json", "batch.json", "evaluation.json", "private.json")]
    hashes = {p.relative_to(source).as_posix(): sha_file(p) for p in paths}
    original, frozen, report, private = (read(p) for p in paths)
    version(original, "skillforge-behavior-plan")
    require(original.get("fingerprint") == digest({k: v for k, v in original.items() if k != "fingerprint"}),
            "regression_source_fingerprint_mismatch")
    require(frozen.get("lifecycle") == report.get("lifecycle") == "finalized", "regression_source_not_frozen")
    require(report.get("binding", {}).get("plan_fingerprint") == original["fingerprint"],
            "regression_report_plan_mismatch")
    require(all(report["binding"].get(k) == v for k, v in frozen["binding"].items()),
            "regression_source_binding_mismatch")
    require(batch != original["batch"], "regression_requires_new_batch")
    old_cases = {c["id"]: c for c in original["cases"]}
    require(len(old_cases) == len(original["cases"]), "regression_duplicate_case")
    rows, seen = [], set()
    for row in report["cases"]:
        cid, repetition = row["case_id"], row["repetition"]
        require(isinstance(cid, str) and re.fullmatch(r"[BSD][0-9]{2}", cid) and cid in old_cases
                and type(repetition) is int and repetition > 0, "regression_invalid_trial")
        require((cid, repetition) not in seen, "regression_duplicate_trial")
        seen.add((cid, repetition))
        require(row["behavior_status"] in {"passed", "failed", "unknown"}, "regression_invalid_status")
        if row["behavior_status"] == "passed":
            continue
        evidence = source / "cases" / cid / str(repetition) / "evidence.json"
        if evidence.exists():
            hashes[evidence.relative_to(source).as_posix()] = sha_file(evidence)
        rows.append({"case_id": cid, "source_repetition": repetition, "source_status": row["behavior_status"],
                     "evidence_present": evidence.exists()})
    require(rows, "regression_no_failed_or_unknown_trials")
    selected = sorted({r["case_id"] for r in rows})
    if case_ids is not None:
        require(isinstance(case_ids, list) and bool(case_ids)
                and all(isinstance(cid, str) for cid in case_ids)
                and len(set(case_ids)) == len(case_ids) and set(case_ids) <= set(selected),
                "regression_selection_requires_failed_or_unknown")
        selected = sorted(case_ids)
        rows = [row for row in rows if row["case_id"] in selected]
    require(len({c[0] for c in selected}) == 1,
            "regression_mixed_suites")
    canary = "SF-PRIVATE-" + secrets.token_hex(16)
    dataset_files = {}
    product = selected[0].startswith("D")
    require(dataset_root is None or product, "regression_dataset_requires_product")
    if product:
        from behavior_dataset import load_dataset
        from behavior_product_suite import make_product_plan
        dataset_root = Path(dataset_root or source).resolve()
        require(not destination.is_relative_to(dataset_root) and not dataset_root.is_relative_to(destination),
                "regression_dataset_destination_must_be_separate")
        dataset = load_dataset(dataset_root)
        plan = make_product_plan(batch, dataset)
        dataset_files = {name: sha_file(dataset_root / name) for name in (
            "dataset.json", "candidates.json", "validation.json", "dataset-derivation.json",
            "all-attempts.json", "generation-cost.json", "generation-summary.json")
            if (dataset_root / name).exists()}
    elif selected[0].startswith("S"):
        from behavior_stress_suite import make_stress_plan
        plan = make_stress_plan(batch, canary=canary)
    else:
        plan = make_plan(batch, canary=canary)
    current = {c["id"]: c for c in plan["cases"]}
    require(set(selected) <= set(current), "regression_case_removed")
    # 仅规范化本测试生成的随机 canary，业务参数、Prompt 和断言保持逐字段比较。
    canaries = [v for v in private["sensitive_values"] if isinstance(v, str) and v.startswith("SF-PRIVATE-")]
    canaries.append(canary)
    changes = []
    for cid in selected:
        old, new = _normalise(old_cases[cid], canaries), _normalise(current[cid], canaries)
        fields = sorted(k for k in set(old) | set(new) if old.get(k) != new.get(k))
        before = next(p for p in original["profiles"] if p["key"] == old_cases[cid]["profile"])
        after = next(p for p in plan["profiles"] if p["key"] == current[cid]["profile"])
        if _normalise(before, canaries) != _normalise(after, canaries):
            fields.append("profile_definition")
        changes.append({"case_id": cid, "changed_fields": fields, "case_contract_equal": not fields})
    repetitions = 1 if product else 3
    plan["local_selection"] = {"case_ids": selected, "repetitions": repetitions, "turn_timeout_seconds": 540, "workers": 1}
    plan["fingerprint"] = digest({k: v for k, v in plan.items() if k != "fingerprint"})
    validate_plan(plan)
    lineage = {"format": "skillforge-regression-lineage", "schema_version": 1,
               "kind": "current_suite_regression", "execution_status": "not_started",
               "source_batch": original["batch"], "source_suite_version": original["suite_version"],
               "source_files": hashes, "source_trials": rows, "case_changes": changes,
               "target_plan_fingerprint": plan["fingerprint"], "target_suite_version": plan["suite_version"],
               "reproduction_claim": False, "release_eligible": False}
    if product:
        lineage["dataset_files"] = dataset_files
        lineage["target_dataset_fingerprint"] = dataset["fingerprint"]
        lineage["source_dataset_fingerprint"] = original.get("dataset_fingerprint")
        require(all(sha_file(dataset_root / name) == value for name, value in dataset_files.items()),
                "regression_dataset_changed")
    require(all(sha_file(source / name) == value for name, value in hashes.items()), "regression_source_changed")
    destination.mkdir(parents=True, exist_ok=False)
    for name, expected_hash in dataset_files.items():
        shutil.copy2(dataset_root / name, destination / name)
        require(sha_file(destination / name) == expected_hash, "regression_dataset_changed")
    save(destination / "private.json", {"sensitive_values": [canary]})
    save(destination / "regression.json", lineage)
    save(destination / "plan.json", plan)
    return {"batch": batch, "case_ids": selected, "experiments": len(selected) * repetitions,
            "changed_cases": [c["case_id"] for c in changes if c["changed_fields"]],
            "execution_status": "not_started", "reproduction_claim": False}


def validate_regression_lineage(root, plan):
    """执行前绑定候选计划，来源清单不是豁免环境校验或发布门禁的凭据。"""
    path = Path(root) / "regression.json"
    if path.exists():
        lineage = read(path)
        version(lineage, "skillforge-regression-lineage")
        require(lineage.get("target_plan_fingerprint") == plan["fingerprint"]
                and lineage.get("target_suite_version") == plan["suite_version"]
                and lineage.get("kind") == "current_suite_regression"
                and "local_selection" in plan, "regression_target_plan_mismatch")
