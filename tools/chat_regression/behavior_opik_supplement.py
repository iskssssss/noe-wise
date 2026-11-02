"""将单个只读补证投影为独立 Opik bundle，保留父候选原始结论。"""
from __future__ import annotations

from copy import deepcopy
import os
from pathlib import Path
import re
import shutil
import tempfile
from typing import Any

from behavior_contract import digest, require
from behavior_opik_export import (
    FORMAT,
    VERSION,
    atomic_json,
    encoded,
    export_bundle,
    load_bundle,
    project_trial,
    sanitise,
    sha_file,
)
from behavior_reporting import _assert_no_secret, write_reports
from runner import read


_REPO = Path(__file__).resolve().parents[2]
_SHA256 = re.compile(r"[0-9a-f]{64}")


def _lineage_path(value: Any) -> Path:
    require(isinstance(value, str) and value, "opik_supplement_lineage_path_required")
    path = Path(value.replace("\\", "/"))
    return (path if path.is_absolute() else _REPO / path).resolve()


def _require_hash(path: Path, expected: Any, label: str) -> str:
    require(isinstance(expected, str) and _SHA256.fullmatch(expected) is not None,
            f"{label}_sha256_invalid")
    require(path.is_file(), f"{label}_missing")
    actual = sha_file(path)
    require(actual == expected, f"{label}_sha256_mismatch")
    return actual


def _validate_lineage(
    lineage: dict[str, Any], parent_root: Path, parent_evidence: Path,
) -> dict[str, Any] | None:
    require(type(lineage.get("new_model_inputs")) is int and lineage["new_model_inputs"] == 0,
            "opik_supplement_requires_zero_model_inputs")
    require(lineage.get("original_report_unchanged") is True,
            "opik_supplement_requires_unchanged_parent_report")
    require(lineage.get("release_eligible") is False,
            "opik_supplement_cannot_be_release_eligible")
    if "parent_root" in lineage:
        require(_lineage_path(lineage["parent_root"]) == parent_root,
                "opik_supplement_parent_root_mismatch")

    expected_parent_hash = sha_file(parent_evidence)
    source_hashes = lineage.get("source_hashes")
    if source_hashes is not None:
        require(isinstance(source_hashes, dict) and source_hashes,
                "opik_supplement_source_hashes_required")
        matched_parent = False
        for raw_path, expected in source_hashes.items():
            path = _lineage_path(raw_path)
            actual = _require_hash(path, expected, "opik_supplement_source")
            if path == parent_evidence:
                require(actual == expected_parent_hash,
                        "opik_supplement_parent_evidence_sha256_mismatch")
                matched_parent = True
        require(matched_parent, "opik_supplement_parent_evidence_hash_required")
    else:
        # D12 的补证采用两个显式来源，而不是通用 source_hashes 映射。
        declared_parent = _lineage_path(lineage.get("parent_evidence"))
        require(declared_parent == parent_evidence,
                "opik_supplement_parent_evidence_mismatch")
        actual = _require_hash(
            declared_parent, lineage.get("parent_sha256"),
            "opik_supplement_parent_evidence")
        require(actual == expected_parent_hash,
                "opik_supplement_parent_evidence_sha256_mismatch")
        privacy = _lineage_path(lineage.get("privacy_readback"))
        _require_hash(
            privacy, lineage.get("privacy_sha256"),
            "opik_supplement_privacy_readback")

    deployment = lineage.get("deployment")
    if deployment is None:
        return None
    require(isinstance(deployment, dict), "opik_supplement_deployment_invalid")
    artifact_sha256 = deployment.get("artifact_sha256")
    require(isinstance(artifact_sha256, str)
            and _SHA256.fullmatch(artifact_sha256) is not None,
            "opik_supplement_deployment_sha256_invalid")
    # 路径、PID 和本机日志目录不进入脱敏导出。
    return {"artifact_sha256": artifact_sha256}


def _parent_source_unchanged(parent_root: Path, files: dict[str, Any]) -> None:
    require(isinstance(files, dict) and files,
            "opik_supplement_parent_source_files_required")
    for relative, expected in files.items():
        require(isinstance(relative, str), "opik_supplement_parent_source_path_invalid")
        path = (parent_root / relative).resolve()
        require(path.is_relative_to(parent_root),
                "opik_supplement_parent_source_path_escape")
        _require_hash(path, expected, "opik_supplement_parent_source")


def _selected_dataset(parent: dict[str, Any], case_id: str) -> dict[str, Any]:
    dataset = deepcopy(parent)
    cases = [case for case in dataset.get("cases", []) if case.get("id") == case_id]
    require(len(cases) == 1, "opik_supplement_parent_dataset_case_required")
    profile_key = cases[0].get("profile")
    profiles = [profile for profile in dataset.get("profiles", [])
                if profile.get("key") == profile_key]
    require(len(profiles) == 1, "opik_supplement_parent_dataset_profile_required")
    dataset["cases"] = cases
    dataset["profiles"] = profiles
    return dataset


def export_supplement(
    parent_root: Path | str,
    supplement_dir: Path | str,
    destination: Path | str,
) -> dict[str, Any]:
    """导出一个单 case 只读补证，不修改父报告或伪装成完整 Runtime 执行。"""
    parent_root = Path(parent_root).resolve()
    supplement_dir = Path(supplement_dir).resolve()
    destination = Path(destination).resolve()
    require(parent_root.is_dir(), "opik_supplement_parent_root_missing")
    require(supplement_dir.is_dir(), "opik_supplement_directory_missing")
    require(not destination.exists(), "opik_export_destination_exists")
    require(not destination.is_relative_to(parent_root)
            and not parent_root.is_relative_to(destination),
            "opik_export_must_be_separate")
    require(not destination.is_relative_to(supplement_dir)
            and not supplement_dir.is_relative_to(destination),
            "opik_supplement_export_must_be_separate")

    supplement_paths = {
        name: supplement_dir / name
        for name in ("evaluation.json", "evidence.json", "lineage.json")
    }
    require(all(path.is_file() for path in supplement_paths.values()),
            "opik_supplement_files_required")
    supplement_hashes = {
        name: sha_file(path) for name, path in supplement_paths.items()
    }
    result = read(supplement_paths["evaluation.json"])
    evidence = read(supplement_paths["evidence.json"])
    lineage = read(supplement_paths["lineage.json"])
    require(isinstance(result, dict) and isinstance(evidence, dict)
            and isinstance(lineage, dict), "opik_supplement_documents_invalid")
    require("cases" not in result and isinstance(result.get("case_id"), str)
            and type(result.get("repetition")) is int and result["repetition"] > 0,
            "opik_supplement_single_case_required")
    case_id, repetition = result["case_id"], result["repetition"]
    require(re.fullmatch(r"[BSD][0-9]{2}", case_id) is not None,
            "opik_supplement_case_identity_invalid")
    require(evidence.get("case_id") == case_id
            and evidence.get("repetition") == repetition,
            "opik_supplement_evidence_identity_mismatch")
    require(result.get("metrics", {}).get("human_quality_status") == "pending",
            "opik_supplement_human_quality_must_remain_pending")

    parent_evidence = parent_root / "cases" / case_id / str(repetition) / "evidence.json"
    parent_state = parent_root / "cases" / case_id / str(repetition) / "state.json"
    require(parent_evidence.is_file() and parent_state.is_file(),
            "opik_supplement_parent_case_files_required")
    original_evidence = read(parent_evidence)
    require(isinstance(evidence.get("session_id"), (str, int))
            and str(evidence["session_id"])
            == str(original_evidence.get("session_id")),
            "opik_supplement_session_identity_mismatch")
    readback_deployment = _validate_lineage(
        lineage, parent_root, parent_evidence.resolve())

    parent_report_path = parent_root / "evaluation.json"
    parent_report_sha256 = sha_file(parent_report_path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix=".opik-supplement-", dir=destination.parent,
    ) as temporary:
        temporary = Path(temporary)
        parent_export = temporary / "parent"
        export_bundle(parent_root, parent_export)
        parent_manifest, parent_trials = load_bundle(parent_export)
        matches = [case for case in parent_manifest["report"]["cases"]
                   if case["case_id"] == case_id
                   and case["repetition"] == repetition]
        require(len(matches) == 1, "opik_supplement_parent_trial_required")
        require(sum(trial["case_id"] == case_id
                    and trial["repetition"] == repetition
                    for trial in parent_trials) == 1,
                "opik_supplement_parent_bundle_trial_required")
        parent_case_status = matches[0]["behavior_status"]
        _parent_source_unchanged(parent_root, parent_manifest["source"]["files"])

        source_files = dict(parent_manifest["source"]["files"])
        prefix = f"supplemental/{case_id}-{repetition}"
        for name, value in supplement_hashes.items():
            key = f"{prefix}/{name}"
            require(key not in source_files, "opik_supplement_source_path_conflict")
            source_files[key] = value
        bundle_id = digest({"source": source_files, "version": VERSION})
        execution_binding = deepcopy(parent_manifest["report"]["binding"])
        supplemental = {
            "parent_bundle_id": parent_manifest["bundle_id"],
            "parent_batch": parent_manifest["source"]["batch"],
            "parent_report_sha256": parent_report_sha256,
            "parent_case_status": parent_case_status,
            "original_result_unchanged": True,
            "new_model_inputs": 0,
            "execution_binding": execution_binding,
            "readback_deployment": readback_deployment,
            "source_lineage_sha256": supplement_hashes["lineage.json"],
        }

        plan = read(parent_root / "plan.json")
        plan_cases = [case for case in plan["cases"] if case["id"] == case_id]
        require(len(plan_cases) == 1, "opik_supplement_parent_plan_case_required")
        batch = read(parent_root / "batch.json")
        trial = project_trial(
            plan_cases[0], result, evidence, read(parent_state),
            evidence.get("frozen_at") or batch["started_at"])
        trace_metadata = trial["trace"].setdefault("metadata", {})
        trace_metadata["supplemental"] = supplemental
        trace_metadata["human_quality_status"] = "pending"
        tags = trial["trace"].setdefault("tags", [])
        if "supplemental-readback" not in tags:
            tags.append("supplemental-readback")

        secrets = list(read(parent_root / "private.json")["sensitive_values"])
        secrets += [value for key, value in os.environ.items()
                    if len(value) >= 8 and any(token in key.upper()
                                               for token in ("PASSWORD", "API_KEY", "TOKEN", "SECRET"))]
        secrets = tuple(sorted(set(secrets), key=len, reverse=True))
        trial = sanitise(trial, secrets, bundle_id)
        trial_name = f"trials/{case_id}-{repetition}.json"

        report = deepcopy(parent_manifest["report"])
        report["cases"] = [result]
        report["behavior_status"] = result["behavior_status"]
        report["blocking_reasons"] = deepcopy(result["blocking_reasons"])
        report["release_eligible"] = False
        # 补证不是父批次重跑，不继承其全套样本统计或重复记账。
        report["metrics"] = {
            "evaluated": 1, "passed": int(result["behavior_status"] == "passed"),
            "supplemental_readback": True, "new_model_inputs": 0,
            "human_quality_status": "pending",
        }
        report = sanitise(report, secrets, bundle_id)
        dataset = _selected_dataset(parent_manifest["dataset"], case_id)
        risk_annotations = [
            annotation for annotation in parent_manifest.get("risk_annotations", [])
            if annotation.get("case_id") == case_id
        ]

        stage = temporary / "supplement"
        stage.mkdir()
        atomic_json(stage / trial_name, trial)
        supplement_batch = (
            f"{parent_manifest['source']['batch']}-supplement-"
            f"{case_id.lower()}-{bundle_id[:8]}"
        )
        manifest = {
            "format": FORMAT,
            "schema_version": VERSION,
            "bundle_id": bundle_id,
            "source": {"batch": supplement_batch,
                       "files": source_files},
            "dataset": dataset,
            "dataset_digest": digest(dataset),
            "report": report,
            "risk_annotations": risk_annotations,
            "trials": [{"path": trial_name,
                        "sha256": sha_file(stage / trial_name),
                        "key": trial["key"]}],
            "redaction": parent_manifest["redaction"],
            "authority": "supplemental_readback",
            "supplemental": sanitise(supplemental, secrets, bundle_id),
        }
        _assert_no_secret({"manifest": encoded(manifest).decode()}, secrets)
        write_reports(stage, report, list(secrets))
        atomic_json(stage / "bundle.json", manifest)
        load_bundle(stage)

        require(parent_report_sha256 == sha_file(parent_report_path),
                "opik_supplement_parent_report_changed")
        _parent_source_unchanged(parent_root, parent_manifest["source"]["files"])
        require(all(sha_file(supplement_paths[name]) == value
                    for name, value in supplement_hashes.items()),
                "opik_supplement_source_changed_during_export")
        _validate_lineage(lineage, parent_root, parent_evidence.resolve())
        # Windows 临时目录有专属 ACL；移动它会阻止后续正常用户读取导入包。
        # 内容校验完成后复制字节，让目标继承调用者指定父目录的访问规则。
        shutil.copytree(stage, destination, copy_function=shutil.copyfile)

    return {
        "bundle_id": bundle_id,
        "trials": 1,
        "destination": str(destination),
        "authority": "supplemental_readback",
        "parent_bundle_id": parent_manifest["bundle_id"],
    }


__all__ = ["export_supplement"]
