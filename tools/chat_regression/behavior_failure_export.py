"""冻结失败回流与独立 FIX 任务说明；不重评、不执行、不修改产品。"""
from pathlib import Path
import re
import shutil

from behavior_contract import digest, require
from behavior_opik_export import sha_file
from runner import read, save

CLASSIFICATIONS = {
    "A": "产品行为缺陷", "B": "测试基础设施缺陷", "C": "Case/Assertion 缺陷",
    "D": "环境问题", "E": "证据不足", "F": "模型随机行为",
}


def export_failures(source, destination, triage):
    source, destination = Path(source).resolve(), Path(destination).resolve()
    require(not destination.exists(), "failure_destination_exists")
    require(not destination.is_relative_to(source) and not source.is_relative_to(destination),
            "failure_destination_must_be_separate")
    require(not (source / "execution.lock").exists(), "failure_source_running")
    require(isinstance(triage, dict), "failure_triage_invalid")
    names = {"plan.json", "batch.json", "evaluation.json"}
    hashes = {name: sha_file(source / name) for name in names}
    plan, batch, report = (read(source / name) for name in ("plan.json", "batch.json", "evaluation.json"))
    require(plan["fingerprint"] == digest({k: v for k, v in plan.items() if k != "fingerprint"}),
            "failure_plan_changed")
    require(batch.get("lifecycle") == report.get("lifecycle") == "finalized", "failure_source_not_frozen")
    require(report.get("binding", {}).get("plan_fingerprint") == plan["fingerprint"]
            and all(report["binding"].get(k) == v for k, v in batch["binding"].items()),
            "failure_binding_mismatch")
    cases = {c["id"]: c for c in plan["cases"]}
    records, seen = [], set()
    for trial in report["cases"]:
        cid, repetition, status = trial["case_id"], trial["repetition"], trial["behavior_status"]
        require(isinstance(cid, str) and re.fullmatch(r"[BSD]\d{2}", cid) and cid in cases
                and type(repetition) is int and repetition > 0, "failure_trial_invalid")
        key = f"{cid}/{repetition}"
        require(key not in seen and status in {"passed", "failed", "unknown"}, "failure_trial_invalid")
        seen.add(key)
        if status == "passed":
            continue
        details = triage.get(key, {"classification": "E", "problem": "待分类：保留失败/未知结果，根因尚未确认。"})
        require(isinstance(details, dict) and details.get("classification") in CLASSIFICATIONS,
                "failure_classification_invalid")
        classification = details["classification"]
        folder = source / "cases" / cid / str(repetition)
        evidence_present = (folder / "evidence.json").is_file()
        if classification == "A":
            require(evidence_present and details.get("infrastructure_checked") is True,
                    "product_failure_requires_evidence_and_infrastructure_check")
            require(all(isinstance(details.get(k), str) and details[k].strip() for k in
                        ("problem", "actual", "expected", "modules", "impact", "suggested_verification", "classification_basis")),
                    "product_failure_details_required")
        if folder.exists():
            for path in sorted(folder.rglob("*")):
                if path.is_file():
                    require(path.resolve().is_relative_to(source), "failure_evidence_path_escape")
                    hashes[path.relative_to(source).as_posix()] = sha_file(path)
        task_id = ("FIX-" if classification == "A" else "TEST-") + digest(
            [plan["fingerprint"], cid, repetition, classification])[:12]
        records.append({
            "task_id": task_id, "task_type": "FIX" if classification == "A" else "TEST",
            "classification": classification, "classification_name": CLASSIFICATIONS[classification],
            "source_status": status, "case_id": cid, "source_repetition": repetition,
            "case_digest": digest(cases[cid]), "source_case_pointer": f"snapshot/plan.json#cases/{cid}",
            "evidence_present": evidence_present, "evidence_path": f"snapshot/cases/{cid}/{repetition}",
            "failed_assertions": [{"id": a["id"], "status": a["status"]} for a in trial.get("assertions", [])
                                  if a.get("status") in {"fail", "unknown"}],
            "triage": details, "regression_status": "waiting_for_fix" if classification == "A" else "needs_triage_or_test_repair",
            "execution_status": "not_started", "reproduction_claim": False,
        })
    require(set(triage) <= {f"{r['case_id']}/{r['source_repetition']}" for r in records}, "failure_triage_unknown_trial")
    require(records, "failure_no_failed_or_unknown_trials")
    require(all(sha_file(source / n) == h for n, h in hashes.items()), "failure_source_changed")
    manifest = {"format": "skillforge-failure-candidates", "schema_version": 1,
                "source_batch": plan["batch"], "source_binding": report["binding"],
                "source_files": hashes, "records": records, "release_eligible": False,
                "execution_status": "not_started", "reproduction_claim": False}
    manifest["fingerprint"] = digest(manifest)
    destination.mkdir(parents=True, exist_ok=False)
    for name, expected in hashes.items():
        target = destination / "snapshot" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / name, target)
        require(sha_file(target) == expected, "failure_source_changed")
    save(destination / "failure-candidates.json", manifest)
    for record in records:
        details = record["triage"]
        lines = [f"# {record['task_id']}", "", "仅为本地任务说明，未启动产品修复。原始证据仅保存在私有目录。", "",
                 f"分类：{record['classification']} — {record['classification_name']}",
                 f"原结果：{record['source_status']}；执行状态：not_started；未声明真实复现。", ""]
        for title, field in (("问题", "problem"), ("实际行为", "actual"), ("预期行为", "expected"),
                             ("涉及模块", "modules"), ("初步影响范围", "impact"),
                             ("分类依据", "classification_basis"), ("建议验证集合", "suggested_verification")):
            lines += [f"## {title}", "", str(details.get(field, "待确认，不能从缺失证据推断。")), ""]
        lines += ["## 复现 Case 与失败断言", "", f"{record['case_id']}/{record['source_repetition']}；"
                  f"输入和原断言见 `{record['source_case_pointer']}`。", "",
                  *[f"- {a['id']}: {a['status']}" for a in record["failed_assertions"]], "",
                  "## 证据位置", "", f"`{record['evidence_path']}`；完整摘要见 failure-candidates.json。", "",
                  "## 后续边界", "", "FIX 在独立产品任务完成。TEST 仅接收候选版本，冻结新批次并定向 VERIFY；不得降低原断言或覆盖旧报告。", ""]
        (destination / (record["task_id"] + ".md")).write_text("\n".join(lines), encoding="utf-8")
    return {"records": len(records), "fix_tasks": sum(r["task_type"] == "FIX" for r in records),
            "execution_status": "not_started", "reproduction_claim": False}


def verify_failure_bundle(root):
    root = Path(root)
    manifest = read(root / "failure-candidates.json")
    require(manifest["fingerprint"] == digest({k: v for k, v in manifest.items() if k != "fingerprint"}),
            "failure_manifest_changed")
    for name, expected in manifest["source_files"].items():
        path = (root / "snapshot" / name).resolve()
        require(path.is_relative_to((root / "snapshot").resolve()), "failure_evidence_path_escape")
        require(sha_file(path) == expected, "failure_snapshot_changed")
    return manifest
