"""将冻结证据投影为脱敏的 Opik 导入包；不执行 Agent，不重评或改写原报告。"""
from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import re

from behavior_contract import digest, require
from behavior_reporting import _redact, _assert_no_secret, write_reports
from runner import read

FORMAT = "skillforge-opik-export"
VERSION = 1


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = path.with_suffix(path.suffix + ".pending")
    pending.write_bytes(encoded(value))
    pending.replace(path)


def sha_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def sanitise(value, secrets, scope):
    """别名仅在一个导出包内关联；额外识别 camelCase 凭据字段。"""
    identities = {}
    identity_keys = {"userid", "tenantid", "sessionid", "organizationid", "username", "email"}
    key = hashlib.sha256((scope + "|" + "|".join(secrets)).encode()).digest()

    def collect(node):
        if isinstance(node, dict):
            for name, child in node.items():
                normalized = re.sub(r"[^a-z]", "", str(name).lower())
                if normalized in identity_keys and isinstance(child, (str, int)) and str(child):
                    original = str(child)
                    identities[original] = "entity-" + hmac.new(key, original.encode(), hashlib.sha256).hexdigest()[:16]
                collect(child)
        elif isinstance(node, list):
            for child in node:
                collect(child)
    collect(value)

    def clean(node):
        if isinstance(node, dict):
            result = {}
            for name, child in node.items():
                normalized = re.sub(r"[^a-z]", "", str(name).lower())
                credential = normalized in {"apikey", "accesstoken", "refreshtoken", "clientsecret", "password",
                                             "authorization", "cookie", "setcookie", "credential", "credentials", "downloadticket"}
                if credential:
                    result[name] = "[REDACTED]"
                elif normalized in identity_keys and str(child) in identities:
                    result[name] = identities[str(child)]
                else:
                    result[name] = clean(child)
            return result
        if isinstance(node, list):
            return [clean(child) for child in node]
        if isinstance(node, str):
            # 短数字身份只在标识字段中改名，不能破坏业务数值及日期。
            for original, alias in sorted(identities.items(), key=lambda pair: -len(pair[0])):
                if len(original) >= 8:
                    node = node.replace(original, alias)
        return node
    result = _redact(clean(value), secrets)
    _assert_no_secret({"opik-export.json": encoded(result).decode()}, secrets)
    return result


def _time(value):
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value, timezone.utc).isoformat()
    require(isinstance(value, str), "opik_missing_event_timestamp")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(parsed.tzinfo is not None, "opik_naive_timestamp")
    return parsed.astimezone(timezone.utc).isoformat()


def project_trial(case, result, evidence, state, fallback_time):
    """Run 范围和事件点各自保留计时含义；平台评分不重新解释硬断言。"""
    key = f"{result['case_id']}:{result['repetition']}"
    events = evidence.get("events", [])
    times = [_time(e["occurred_at"]) for e in events if e.get("occurred_at")]
    start, end = (min(times), max(times)) if times else (_time(fallback_time), _time(fallback_time))
    trace = {"name": key, "start_time": start, "end_time": end,
             "input": {"turns": case["turns"]}, "output": {"skillforge_result": result, "answer": evidence.get("answer")},
             "metadata": {"case_id": result["case_id"], "repetition": result["repetition"],
                          "time_basis": "observed_event_range" if times else "freeze_time_only",
                          "evidence_available": bool(evidence), "completeness": evidence.get("completeness", {}),
                          "cancellation": evidence.get("cancellation", {}), "source_event_count": len(events)},
             "tags": ["skillforge", result["behavior_status"]], "source": "experiment"}
    if "candidate" in case:
        trace["metadata"]["candidate_provenance"] = case["candidate"]["provenance"]
        trace["metadata"]["candidate_id"] = case["candidate"]["candidate_id"]
        if "derivation" in case["candidate"]:
            trace["metadata"]["candidate_derivation"] = case["candidate"]["derivation"]
        trace["metadata"]["reference_answer_authority"] = "unverified_draft_not_gold"
        trace["metadata"]["business_status"] = result.get("metrics", {}).get("business_status")
        trace["metadata"]["process_status"] = result.get("metrics", {}).get("process_status")
        trace["metadata"]["human_quality_status"] = result.get("metrics", {}).get("human_quality_status", "pending")
    spans = []
    runs = defaultdict(list)
    for event in events:
        runs[event.get("run_id") or "unassigned"].append(event)
    for run_id, observations in runs.items():
        stamps = [_time(e["occurred_at"]) for e in observations]
        run_key = "run:" + run_id
        spans.append({"key": run_key, "parent": None, "name": run_key, "type": "general",
                      "start_time": min(stamps), "end_time": max(stamps),
                      "metadata": {"source_run_id": run_id, "time_basis": "observed_event_range"}})
        seen = {}
        for event in observations:
            event_id = event["event_id"]
            if event_id in seen:
                require(seen[event_id] == event, "opik_conflicting_event_identity")
                continue
            seen[event_id] = event
            stamp = _time(event["occurred_at"])
            spans.append({"key": "event:" + event_id, "parent": run_key, "name": event["event_type"],
                          "type": "general", "start_time": stamp, "end_time": stamp,
                          "output": event, "metadata": {"source_event_id": event_id, "time_basis": "event_instant"}})
    for index, call in enumerate(evidence.get("calls", [])):
        terminal = state.get("turns", {}).get(str(call.get("turn")), {}).get("result", {}).get("terminal") or {}
        run_id = terminal.get("run_id")
        span = {"key": "tool:" + str(call.get("event_id", index)),
                "parent": "run:" + run_id if run_id in runs else None,
                "name": call.get("tool", call.get("tool_name", "tool")), "type": "tool",
                "start_time": _time(call["received_at"]), "input": call.get("arguments", {}), "output": call,
                "metadata": {"time_basis": "fixture_receive_to_complete"}}
        if call.get("completed_at") is not None:
            span["end_time"] = _time(call["completed_at"])
        spans.append(span)
    scores = [{"name": "sf." + a["id"], "value": 1 if a["status"] == "pass" else 0,
               "source": "sdk", "reason": a["reason"]}
              for a in result["assertions"] if a["evaluator"] == "code" and a["status"] in {"pass", "fail"}]
    return {"key": key, "case_id": result["case_id"], "repetition": result["repetition"],
            "trace": trace, "spans": spans, "scores": scores}


def export_bundle(root, destination):
    from behavior_measurements import enrich_report
    from behavior_policy import risk_annotations
    root, destination = Path(root).resolve(), Path(destination).resolve()
    require(not destination.is_relative_to(root) and not root.is_relative_to(destination), "opik_export_must_be_separate")
    require(not destination.exists(), "opik_export_destination_exists")
    require(not (root / "execution.lock").exists(), "opik_source_is_running")
    plan, batch, original = (read(root / f) for f in ("plan.json", "batch.json", "evaluation.json"))
    require(batch["lifecycle"] == original["lifecycle"] == "finalized", "opik_requires_frozen_batch")
    require(original["binding"] == {**original["binding"], **batch["binding"]}, "opik_binding_mismatch")
    paths = [root / name for name in ("plan.json", "batch.json", "evaluation.json", "private.json", "configuration.json", "selection.json",
                                     "dataset.json", "dataset-derivation.json", "candidates.json", "validation.json") if (root / name).exists()]
    paths += sorted((root / "cases").glob("*/*/evidence.json")) + sorted((root / "cases").glob("*/*/state.json"))
    paths += sorted((root / "cases").glob("*/*/turn-*.sse.jsonl"))
    hashes = {p.relative_to(root).as_posix(): sha_file(p) for p in paths}
    bundle_id = digest({"source": hashes, "version": VERSION})
    secrets = list(read(root / "private.json")["sensitive_values"])
    secrets += [v for k, v in os.environ.items() if len(v) >= 8 and any(t in k.upper() for t in ("PASSWORD", "API_KEY", "TOKEN", "SECRET"))]
    secrets = tuple(sorted(set(secrets), key=len, reverse=True))
    report = enrich_report(root, original)
    payload = {"report": report, "profiles": plan["profiles"], "cases": plan["cases"], "risk_annotations": risk_annotations(plan)}
    public = sanitise(payload, secrets, bundle_id)
    destination.mkdir(parents=True)
    trials = []
    for result in report["cases"]:
        require(re.fullmatch(r"[BSD][0-9]{2}", result["case_id"]) is not None
                and type(result["repetition"]) is int and result["repetition"] > 0, "opik_invalid_trial_identity")
        case = next(c for c in plan["cases"] if c["id"] == result["case_id"])
        folder = root / "cases" / result["case_id"] / str(result["repetition"])
        evidence = read(folder / "evidence.json") if (folder / "evidence.json").exists() else {}
        state = read(folder / "state.json") if (folder / "state.json").exists() else {}
        trial = project_trial(case, result, evidence, state, evidence.get("frozen_at") or batch["started_at"])
        trial = sanitise(trial, secrets, bundle_id)
        name = f"trials/{result['case_id']}-{result['repetition']}.json"
        atomic_json(destination / name, trial)
        trials.append({"path": name, "sha256": sha_file(destination / name), "key": trial["key"]})
    require(all(sha_file(root / name) == value for name, value in hashes.items()), "opik_source_changed_during_export")
    selected = {r["case_id"] for r in report["cases"]}
    dataset = {"suite_version": plan["suite_version"], "profiles": public["profiles"],
               "cases": [c for c in public["cases"] if c["id"] in selected]}
    if plan.get("suite_version") == "product-tasks-1":
        from behavior_dataset import load_dataset
        frozen_dataset = load_dataset(root)
        require(frozen_dataset["fingerprint"] == plan["dataset_fingerprint"], "opik_product_dataset_drift")
        dataset.update(dataset_version=frozen_dataset["dataset_version"], dataset_fingerprint=frozen_dataset["fingerprint"],
                       reference_answers_are_gold=False)
    manifest = {"format": FORMAT, "schema_version": VERSION, "bundle_id": bundle_id,
                "source": {"batch": plan["batch"], "files": hashes}, "dataset": dataset,
                "dataset_digest": digest(dataset), "report": public["report"], "risk_annotations": public["risk_annotations"],
                "trials": trials, "redaction": "known-secrets-and-credentials-v1", "authority": "frozen_skillforge_evidence"}
    _assert_no_secret({"manifest": encoded(manifest).decode()}, secrets)
    write_reports(destination, public["report"], list(secrets))
    atomic_json(destination / "bundle.json", manifest)
    return {"bundle_id": bundle_id, "trials": len(trials), "destination": str(destination)}


def load_bundle(folder):
    folder = Path(folder).resolve()
    manifest = read(folder / "bundle.json")
    require(manifest.get("format") == FORMAT and manifest.get("schema_version") == VERSION, "opik_export_version_mismatch")
    require(digest(manifest["dataset"]) == manifest["dataset_digest"], "opik_dataset_digest_mismatch")
    require(digest({"source": manifest["source"]["files"], "version": VERSION}) == manifest["bundle_id"], "opik_source_digest_mismatch")
    trials = []
    for item in manifest["trials"]:
        path = (folder / item["path"]).resolve()
        require(path.is_relative_to(folder) and sha_file(path) == item["sha256"], "opik_trial_digest_mismatch")
        trial = read(path)
        require(trial["key"] == item["key"], "opik_trial_identity_mismatch")
        require(len({s["key"] for s in trial["spans"]}) == len(trial["spans"]), "opik_duplicate_span_identity")
        trials.append(trial)
    require(len({t["key"] for t in trials}) == len(trials), "opik_duplicate_trial_identity")
    expected = {(r["case_id"], r["repetition"]) for r in manifest["report"]["cases"]}
    require(expected == {(t["case_id"], t["repetition"]) for t in trials}, "opik_trial_matrix_mismatch")
    if manifest.get("authority") == "supplemental_readback":
        supplement = manifest.get("supplemental", {})
        require(len(trials) == 1 and len(manifest["report"]["cases"]) == 1,
                "opik_supplement_single_case_required")
        require(supplement.get("original_result_unchanged") is True
                and type(supplement.get("new_model_inputs")) is int
                and supplement["new_model_inputs"] == 0
                and manifest["report"].get("release_eligible") is False,
                "opik_supplement_authority_invalid")
        for field in ("parent_bundle_id", "parent_report_sha256", "source_lineage_sha256"):
            require(isinstance(supplement.get(field), str)
                    and re.fullmatch(r"[0-9a-f]{64}", supplement[field]) is not None,
                    "opik_supplement_parent_binding_invalid")
        require(trials[0]["trace"]["metadata"].get("supplemental") == supplement
                and trials[0]["trace"]["metadata"].get("human_quality_status") == "pending",
                "opik_supplement_trace_binding_invalid")
    else:
        require("supplemental" not in manifest, "opik_supplement_authority_missing")
    return manifest, trials
