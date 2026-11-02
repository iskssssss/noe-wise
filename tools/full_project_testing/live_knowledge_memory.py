"""本批 Knowledge/Memory 的单 lane 付费包装器；默认只校验，原始失败从不重跑覆盖。"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

import budget_ledger
from run_suite import REPO, save

sys.path.insert(0, str(REPO / "tools/memory_evaluation"))
import reporting
import run_acceptance as memory_launcher

RUN_ROOT = REPO / ".runtime-logs" / budget_ledger.RUN_ID
LIVE_ROOT = RUN_ROOT / "live-knowledge-memory"
ENV_FILE = RUN_ROOT / "environment/isolated.env"
SUITE_FILE = REPO / "tools/memory_evaluation/suite.json"
SOURCE_FILES = (
    "tools/full_project_testing/live_knowledge_memory.py",
    "tools/full_project_testing/budget_ledger.py",
    "tools/memory_evaluation/reporting.py",
    "tools/memory_evaluation/suite.json",
    "tools/memory_evaluation/run_acceptance.py",
    "backend-java/skillforge-institution-service/src/liveMemoryAcceptance/java/com/skillforge/institution/integration/MemoryLiveAcceptance.java",
    "backend-java/skillforge-institution-service/src/liveMemoryAcceptance/java/com/skillforge/institution/integration/LiveMemoryBudgets.java",
    "backend-java/skillforge-institution-service/src/liveMemoryAcceptance/java/com/skillforge/institution/integration/MemoryRecordingHttpClient.java",
    "backend-java/skillforge-institution-service/src/liveKnowledgeAcceptance/java/com/skillforge/institution/integration/KnowledgeLiveProviderAcceptanceTest.java",
    "backend-java/skillforge-institution-service/src/liveKnowledgeAcceptance/java/com/skillforge/institution/integration/KnowledgeLiveMeteredProxy.java",
)


class EvidenceError(ValueError):
    """只含固定错误码；不把凭据、数据库地址或供应商正文带到控制台。"""


def require(condition, code):
    if not condition:
        raise EvidenceError(code)


def natural(value, code="nonnegative_integer_required", positive=False):
    require(type(value) is int and value >= (1 if positive else 0), code)
    return value


def seconds(value):
    require(type(value) in (int, float) and math.isfinite(value) and value >= 0,
            "finite_elapsed_seconds_required")
    return value


def text(value, code="identity_text_required"):
    require(isinstance(value, str) and bool(value) and value == value.strip(), code)
    return value


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate_json_field")
        result[key] = value
    return result


def decode(raw):
    try:
        return json.loads(raw, object_pairs_hook=unique_keys,
                          parse_constant=lambda _value: (_ for _ in ()).throw(EvidenceError("nonfinite_json_number")))
    except (UnicodeError, json.JSONDecodeError):
        raise EvidenceError("evidence_json_invalid") from None


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_new(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def read_artifact(root, relative, artifacts):
    path = reporting.safe_child(root, relative)
    require(path.is_file(), "evidence_artifact_missing")
    raw = path.read_bytes()
    require(len(raw) <= 64 * 1024 * 1024, "evidence_artifact_too_large")
    artifacts.append({"path": relative, "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)})
    return decode(raw), raw


def identity(receipt, suite, root):
    require(receipt.get("schema_version") == 1 and receipt.get("run_id") == budget_ledger.RUN_ID
            and receipt.get("suite") == suite and receipt.get("round") in (1, 2, 3)
            and type(receipt.get("round")) is int, "reservation_owner_invalid")
    require(Path(receipt["evidence_root"]).resolve() == root.resolve(), "reservation_evidence_root_mismatch")
    require(root.resolve() == (LIVE_ROOT / f"round-{receipt['round']}" / suite).resolve(), "fixed_lane_directory_required")
    require(Path(receipt["ledger_path"]).resolve() == budget_ledger.LEDGER_PATH.resolve(), "reservation_ledger_mismatch")
    try:
        uuid.UUID(receipt["reservation_id"])
    except (ValueError, TypeError, KeyError):
        raise EvidenceError("reservation_identity_invalid") from None
    for key, value in budget_ledger.LIMITS[suite].items():
        require(receipt.get(key) == value, "reservation_cap_drift")


def base_measurement(receipt, execution):
    return {"schema_version": 1, "run_id": budget_ledger.RUN_ID,
            "reservation_id": receipt["reservation_id"], "batch_id": receipt["batch_id"],
            "suite": receipt["suite"], "round": receipt["round"],
            "evidence_root": receipt["evidence_root"], "ledger_path": receipt["ledger_path"],
            "usage_known": False, "observed_elapsed_seconds": seconds(execution["elapsed_seconds"]),
            "execution": execution, "artifacts": [], "issues": [], "acceptance_outcome": "UNKNOWN",
            "execution_complete": False, "stop_new_dispatch": True,
            "monetary_amount": None, "monetary_status": "NOT_CALCULATED_FROM_TOKENS",
            "semantic_review": "NOT_PERFORMED", "human_review_performed": False}


def parse_knowledge(root, receipt, execution, binding=None):
    """逐 reserved→response→metering→原始成功回执关联；11次只作为完整成功分母。"""
    root = Path(root).resolve()
    identity(receipt, "knowledge", root)
    measured = base_measurement(receipt, execution)
    artifacts = measured["artifacts"]
    started, _ = read_artifact(root, "execution-started.json", artifacts)
    for key, value in {"embedding_max_calls": 20, "embedding_max_tokens": 50_000,
                       "rerank_max_calls": 10, "rerank_max_billed_tokens": 50_000}.items():
        require(started.get(key) == value, "knowledge_internal_cap_drift")
    started_at = reporting.parse_time(started["started"])
    require(started_at >= reporting.parse_time(execution["started_at"]), "knowledge_stale_execution_evidence")
    ledger_path = root / "external-attempts.jsonl"
    require(ledger_path.is_file(), "knowledge_attempt_ledger_missing")
    raw = ledger_path.read_bytes()
    require(len(raw) <= 1_048_576 and (not raw or raw.endswith(b"\n")), "knowledge_ledger_truncated_or_large")
    artifacts.append({"path": ledger_path.name, "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)})
    events = [decode(line) for line in raw.splitlines()]
    attempts = {}
    for row in events:
        require(isinstance(row, dict) and row.get("event") in {"reserved", "response", "metering_receipt", "unknown"},
                "knowledge_event_schema_invalid")
        attempt = text(row.get("attempt"))
        require(row.get("kind") in {"embedding", "rerank"}, "knowledge_kind_invalid")
        stamp = reporting.parse_time(row["time"])
        require(stamp >= started_at, "knowledge_event_before_execution")
        if execution.get("finished_at"):
            require(stamp <= reporting.parse_time(execution["finished_at"]), "knowledge_event_after_owned_process")
        if row["event"] == "reserved":
            require(attempt not in attempts, "knowledge_duplicate_reservation")
            natural(row.get("reserved_tokens"), positive=True)
            require(row["reserved_tokens"] <= 5_000, "knowledge_attempt_bound_exceeded")
            attempts[attempt] = {"reserved": row, "events": {}, "last_time": stamp}
        else:
            require(attempt in attempts, "knowledge_orphan_attempt_event")
            item = attempts[attempt]
            require(row["kind"] == item["reserved"]["kind"] and stamp >= item["last_time"], "knowledge_attempt_owner_or_time_drift")
            require(row["event"] not in item["events"], "knowledge_duplicate_attempt_event")
            item["events"][row["event"]] = row
            item["last_time"] = stamp
    totals, counts, reserved = Counter(), Counter(), Counter()
    known_counts, rows, unknown = Counter(), [], False
    for attempt, item in attempts.items():
        reserve, events = item["reserved"], item["events"]
        kind = reserve["kind"]
        counts[kind] += 1
        reserved[kind] += reserve["reserved_tokens"]
        status = {"attempt": attempt, "kind": kind, "reserved_tokens": reserve["reserved_tokens"], "usage_known": False}
        response, meter = events.get("response"), events.get("metering_receipt")
        if "unknown" in events or response is None or meter is None or response.get("http_status") != 200:
            unknown = True
            measured["issues"].append("knowledge_attempt_terminal_or_usage_unknown")
            rows.append(status)
            continue
        require(meter.get("reserved_tokens") == reserve["reserved_tokens"], "knowledge_meter_reservation_mismatch")
        for flag in ("payload_complete", "usage_complete", "usage_within_reservation", "complete_success"):
            require(type(meter.get(flag)) is bool, "knowledge_meter_boolean_required")
        provider, provider_bytes = read_artifact(root, "response-" + attempt + ".json", artifacts)
        require(response.get("response_bytes") == len(provider_bytes), "knowledge_response_size_mismatch")
        usage = provider.get("usage") or {}
        token = usage.get("total_tokens")
        prompt = usage.get("prompt_tokens") if kind == "embedding" else None
        complete = type(token) is int and token > 0 and (kind != "embedding" or type(prompt) is int and 0 < prompt <= token)
        require(meter["usage_complete"] == complete, "knowledge_usage_complete_conflict")
        if not complete:
            unknown = True
            measured["issues"].append("knowledge_supplier_usage_missing")
            rows.append(status)
            continue
        require(meter.get("total_tokens") == token and (kind != "embedding" or meter.get("prompt_tokens") == prompt),
                "knowledge_supplier_meter_usage_conflict")
        within = token <= reserve["reserved_tokens"]
        require(meter["usage_within_reservation"] == within and meter["complete_success"] == (meter["payload_complete"] and within),
                "knowledge_meter_flags_conflict")
        payload = provider.get("data") if kind == "embedding" else (provider.get("output") or {}).get("results")
        shape = isinstance(payload, list) and bool(payload)
        if shape:
            indices = [value.get("index") if isinstance(value, dict) else None for value in payload]
            shape = all(type(index) is int for index in indices) and set(indices) == set(range(len(payload))) and len(set(indices)) == len(payload)
        if shape and kind == "embedding":
            shape = all(isinstance(v.get("embedding"), list) and len(v["embedding"]) == 1024
                        and all(type(n) in (int, float) and math.isfinite(n) for n in v["embedding"]) for v in payload)
        elif shape:
            shape = all(type(v.get("relevance_score")) in (int, float) and math.isfinite(v["relevance_score"])
                        for v in payload)
        require(not meter["payload_complete"] or shape, "knowledge_supplier_payload_flag_conflict")
        totals[kind] += token
        known_counts[kind] += 1
        if prompt is not None:
            totals["embedding_prompt"] += prompt
        status.update(usage_known=True, total_tokens=token, payload_complete=meter["payload_complete"], within_reservation=within)
        if prompt is not None:
            status["prompt_tokens"] = prompt
        if not within or not meter["payload_complete"]:
            measured["issues"].append("knowledge_known_usage_result_failed")
        rows.append(status)
    require(bool(attempts), "knowledge_no_observed_attempts")
    measured.update(observed_embedding_calls=counts["embedding"], observed_rerank_calls=counts["rerank"],
                    attempt_rows=rows, reserved_token_totals=dict(reserved), confirmed_usage_attempts=sum(known_counts.values()))
    for kind in ("embedding", "rerank"):
        if known_counts[kind] or counts[kind] == 0:
            measured["observed_" + kind + "_tokens"] = totals[kind]
    if sum(known_counts.values()):
        measured["observed_tokens"] = totals["embedding"] + totals["rerank"]
        measured["embedding_prompt_tokens"] = totals["embedding_prompt"]
    measured["usage_known"] = not unknown
    measured["token_metrics_are_lower_bounds"] = unknown
    if (root / "external-failure.json").exists():
        marker, _ = read_artifact(root, "external-failure.json", artifacts)
        measured["failure_marker"] = {k: marker.get(k) for k in ("attempt", "outcome", "time")}
        measured["issues"].append("knowledge_external_failure_marker")
    for failure in sorted(root.glob("handler-failure-*.json")):
        read_artifact(root, failure.name, artifacts)
        measured["issues"].append("knowledge_handler_failure")
        measured["usage_known"] = False
    success = None
    if (root / "acceptance-success.json").exists():
        success, _ = read_artifact(root, "acceptance-success.json", artifacts)
    exact = counts == Counter(embedding=10, rerank=1)
    if success is not None:
        require(success.get("status") == "PASS" and success.get("attempts") == len(attempts), "knowledge_success_receipt_conflict")
        require(exact and success.get("rerank_first_index") == 0, "knowledge_success_denominator_conflict")
    measured["execution_complete"] = (success is not None and exact and not measured["issues"]
                                      and not execution.get("timed_out") and execution.get("return_code") == 0)
    measured["acceptance_outcome"] = "PASS" if measured["execution_complete"] else "FAIL"
    measured["stop_new_dispatch"] = not measured["execution_complete"] or not measured["usage_known"]
    if execution.get("timed_out"):
        measured["usage_known"] = False
        measured["issues"].append("owned_process_timeout")
    return measured


def token_pair(wire):
    raw = wire.get("rawUsage")
    require(isinstance(raw, dict), "memory_raw_usage_missing")
    values = []
    for aliases in (("usage.prompt_tokens", "usage.input_tokens"), ("usage.completion_tokens", "usage.output_tokens")):
        supplied = [raw[k] for k in aliases if raw.get(k) is not None]
        if not supplied:
            return None
        require(all(type(v) is int and v >= 0 for v in supplied) and len(set(supplied)) == 1, "memory_wire_token_alias_conflict")
        values.append(supplied[0])
    if raw.get("usage.total_tokens") is not None:
        require(type(raw["usage.total_tokens"]) is int and raw["usage.total_tokens"] == sum(values), "memory_wire_total_conflict")
    return values


def parse_memory(root, receipt, execution, binding):
    """费用优先用全批runtimeCall与wire交叉核验；72样本的语义结果独立于费用结算。"""
    root = Path(root).resolve()
    identity(receipt, "memory", root)
    measured = base_measurement(receipt, execution)
    artifacts = measured["artifacts"]
    batch, _ = read_artifact(root, "batch.json", artifacts)
    loaded = reporting.load_batch(root / "batch.json", SUITE_FILE)
    require(batch.get("suiteHash") == binding["suite_sha256"], "memory_source_suite_drift")
    text(batch.get("batchId"), "memory_native_batch_identity_missing")
    measured["native_batch_id"] = batch["batchId"]
    source = batch.get("model") or {}
    require(str(source.get("sourceId")) == binding["memory_model_id"] and source.get("sourceRevision") == binding["memory_model_revision"]
            and source.get("modelId") == "deepseek-flash" and source.get("sourceReadOnly") is True,
            "memory_frozen_source_model_drift")
    require(reporting.parse_time(batch["startedAt"]) >= reporting.parse_time(execution["started_at"]), "memory_stale_execution_evidence")
    if batch.get("finishedAt") and execution.get("finished_at"):
        require(reporting.parse_time(batch["finishedAt"]) <= reporting.parse_time(execution["finished_at"]), "memory_batch_after_owned_process")
    budget = batch.get("budget")
    require(isinstance(budget, dict), "memory_budget_missing")
    for key, value in {"maximumAttempts": 1200, "maximumTokens": 1_000_000, "maximumSeconds": 7200}.items():
        require(budget.get(key) == value, "memory_internal_budget_drift")
    attempts = budget.get("attempts")
    runtimes, wires = batch.get("runtimeCalls"), batch.get("wireCalls")
    require(all(isinstance(v, list) for v in (attempts, runtimes, wires)), "memory_attempt_lists_missing")
    by_attempt, by_runtime, by_wire = {}, {}, {}
    for item in attempts:
        key = (text(item.get("runId")), text(item.get("attemptId")))
        require(key not in by_attempt and type(item.get("sent")) is bool, "memory_duplicate_or_invalid_budget_attempt")
        natural(item.get("reservedTokens"), positive=True)
        require(item.get("category") in {"BUSINESS_MODEL", "AUXILIARY_MODEL"}, "memory_model_category_invalid")
        by_attempt[key] = item
    for call in runtimes:
        key = (text(call.get("runId")), text(call.get("attemptId")))
        require(key not in by_runtime and key in by_attempt and by_attempt[key]["sent"], "memory_duplicate_or_orphan_runtime")
        require(call.get("category") == by_attempt[key]["category"], "memory_runtime_category_drift")
        text(call.get("tenantId"))
        by_runtime[key] = call
    sequences = set()
    for wire in wires:
        context = wire.get("attempt") or {}
        key = (text(context.get("runId")), text(context.get("attemptId")))
        require(key not in by_wire and key in by_runtime, "memory_duplicate_or_orphan_wire")
        require(context.get("category") == by_runtime[key]["category"], "memory_wire_category_drift")
        sequence = natural(wire.get("requestSequence"), positive=True)
        require(sequence not in sequences, "memory_wire_sequence_duplicate")
        sequences.add(sequence)
        by_wire[key] = wire
    sent = {k for k, v in by_attempt.items() if v["sent"]}
    require(sent == set(by_runtime), "memory_sent_runtime_matrix_mismatch")
    require(budget.get("reservedSlots") == len(attempts) and budget.get("sentAttempts") == len(sent), "memory_budget_count_conflict")
    total, input_total, output_total, known, unknown, charged, reported, rows = 0, 0, 0, 0, 0, 0, 0, []
    for key, attempt in by_attempt.items():
        reported_value = attempt.get("reportedTokens")
        if reported_value is not None:
            natural(reported_value)
            reported += reported_value
        charged += reported_value if reported_value is not None else attempt["reservedTokens"]
        if not attempt["sent"]:
            require(reported_value is None, "memory_unsent_attempt_claims_usage")
            continue
        call, wire = by_runtime[key], by_wire.get(key)
        row = {"run_id": key[0], "attempt_id": key[1], "category": attempt["category"], "usage_known": False}
        terminal = call.get("terminal")
        require(terminal in {"STARTED", "SUCCEEDED", "FAILED", "UNRESOLVED"}, "memory_runtime_terminal_invalid")
        if wire is None or terminal in {"STARTED", "UNRESOLVED"}:
            unknown += 1
            measured["issues"].append("memory_terminal_or_wire_unknown")
            rows.append(row)
            continue
        require(attempt.get("terminal") == terminal and wire.get("terminalStatus") == terminal, "memory_terminal_cross_boundary_conflict")
        wire_tokens = token_pair(wire)
        runtime_usage = call.get("usage") or {}
        pair = [runtime_usage.get("inputTokens"), runtime_usage.get("outputTokens")]
        if wire_tokens is None or any(type(v) is not int or v < 0 for v in pair) or reported_value is None:
            unknown += 1
            measured["issues"].append("memory_provider_usage_missing")
            rows.append(row)
            continue
        require(pair == wire_tokens and sum(pair) == reported_value, "memory_runtime_wire_budget_usage_conflict")
        if wire.get("transportFailure") is not None or wire.get("observationFailure") is not None or wire.get("bodyCompleteMillis") is None:
            unknown += 1
            measured["issues"].append("memory_wire_observation_incomplete")
            rows.append(row)
            continue
        seconds(wire["bodyCompleteMillis"])
        require(type(wire.get("httpStatus")) is int and 100 <= wire["httpStatus"] <= 599, "memory_wire_http_status_invalid")
        input_total += pair[0]
        output_total += pair[1]
        total += sum(pair)
        known += 1
        row.update(usage_known=True, input_tokens=pair[0], output_tokens=pair[1], terminal=terminal, wire_sequence=wire["requestSequence"])
        if terminal != "SUCCEEDED" or wire["httpStatus"] != 200 or reported_value > attempt["reservedTokens"]:
            measured["issues"].append("memory_known_usage_supplier_result_failed")
        rows.append(row)
    require(budget.get("reportedTokens") == reported and budget.get("chargedOrReservedTokens") == charged,
            "memory_budget_token_totals_conflict")
    require(budget.get("unknownUsageAttempts") == sum(v["sent"] and v.get("reportedTokens") is None for v in attempts), "memory_budget_unknown_count_conflict")
    measured.update(observed_attempts=len(wires), confirmed_usage_attempts=known, attempt_rows=rows,
                    reserved_slots=len(attempts), charged_or_reserved_tokens=charged,
                    usage_known=unknown == 0, token_metrics_are_lower_bounds=unknown > 0,
                    native_elapsed_seconds=seconds(batch.get("elapsedSeconds")))
    if len(attempts) > budget["maximumAttempts"] or charged > budget["maximumTokens"]:
        measured["issues"].append("memory_internal_cap_exceeded")
    if known or not sent:
        measured.update(observed_tokens=total, input_tokens=input_total, output_tokens=output_total)
    expected = {(f"M{i:02d}", repeat) for i in range(1, 25) for repeat in (1, 2, 3)}
    counts, infrastructure = Counter(), 0
    require(len(batch.get("samples", [])) == 72, "memory_sample_index_denominator_invalid")
    index = {entry["sampleId"]: entry for entry in batch["samples"]}
    require(len(index) == 72, "memory_sample_index_duplicate")
    for sample in loaded["samples"].values():
        value = sample["data"]
        require(index[value["sampleId"]]["outcome"] == value["outcome"], "memory_sample_index_outcome_conflict")
        reporting.validate_assertions(value)
        counts[value["outcome"]] += 1
        infrastructure += sum(failure.get("category") == "INFRASTRUCTURE" for failure in value.get("failures", []))
        artifacts.append({"path": sample["relativePath"], "sha256": sample["hash"], "bytes": sample["path"].stat().st_size})
    counts["NOT_RUN"] += len(expected - loaded["samples"].keys())
    measured.update(planned_samples=72, sample_counts=dict(counts), infrastructure_failures=infrastructure)
    complete = (expected == loaded["samples"].keys() and batch.get("outcome") == "COMPLETED"
                and batch.get("stopCode") is None and budget.get("stopCode") is None and infrastructure == 0
                and execution.get("return_code") == 0 and not execution.get("timed_out"))
    measured["execution_complete"] = complete
    measured["acceptance_outcome"] = "FAIL" if counts["FAIL"] else "UNKNOWN" if counts["UNKNOWN"] or counts["NOT_RUN"] else "PASS"
    # 语义质量失败不改写费用，也不遮住其他独立测试。设施/供应商/安全停止仍封门。
    measured["stop_new_dispatch"] = not complete or not measured["usage_known"] or bool(measured["issues"])
    if execution.get("timed_out"):
        measured["usage_known"] = False
        measured["issues"].append("owned_process_timeout")
    return measured


def environment_and_binding(env_file):
    """相关来源键只首写摘要绑定；新增无关环境键不会把已完成的source变成另一批。"""
    require(Path(env_file).resolve() == ENV_FILE.resolve(), "single_isolated_source_env_required")
    source = memory_launcher.read_source_env(ENV_FILE)
    keys = set(memory_launcher.SOURCE_KEYS + memory_launcher.MODEL_KEYS) | {"DASHSCOPE_API_KEY"}
    selected = {}
    for line in ENV_FILE.read_text(encoding="utf-8-sig").splitlines():
        if "=" not in line or line.lstrip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if key not in keys:
            continue
        require(key not in selected, "duplicate_bound_source_env_key")
        if len(value) > 1 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        selected[key] = value
    require(all(selected.get(key) for key in keys), "knowledge_memory_source_key_missing")
    binding = {"schema_version": 1, "run_id": budget_ledger.RUN_ID,
               "ledger_path": str(budget_ledger.LEDGER_PATH.resolve()), "live_root": str(LIVE_ROOT.resolve()),
               "env_file": str(ENV_FILE.resolve()),
               "source_fingerprint": hashlib.sha256(json.dumps(selected, sort_keys=True).encode()).hexdigest(),
               "memory_model_id": source["LIVE_MEMORY_SOURCE_MODEL_ID"],
               "memory_model_revision": int(source["LIVE_MEMORY_SOURCE_MODEL_REVISION"]),
               "suite_sha256": digest(SUITE_FILE),
               "source_files": {name: digest(REPO / name) for name in SOURCE_FILES},
               "supplier_models": {"memory": "deepseek-flash", "embedding": "qwen3.7-text-embedding-flash", "rerank": "qwen3.7-text-rerank"}}
    return selected, binding


def bind_source(binding):
    """固定目录首次exclusive写入，不支持通过另一个root或删目录创建第二份预算。"""
    path = LIVE_ROOT / "source-binding.json"
    if path.exists():
        require(decode(path.read_bytes()) == binding, "knowledge_memory_source_binding_drift")
    else:
        # 主ledger已存在本lane预留但source记录消失时，禁止重建来源绑定。
        if budget_ledger.LEDGER_PATH.exists():
            with budget_ledger.ledger_lock(budget_ledger.LEDGER_PATH):
                ledger = budget_ledger.load_ledger(budget_ledger.LEDGER_PATH)
                require(not any(row["suite"] in {"memory", "knowledge"} for row in ledger["reservations"]),
                        "source_binding_missing_for_existing_reservation")
        write_new(path, binding)


def stop_gate(receipt, reason):
    """未知、超时或设施失败封住主账本；保留reservation和原实测，不修改费用事实。"""
    path = budget_ledger.canonical_path(receipt["ledger_path"])
    with budget_ledger.ledger_lock(path):
        ledger = budget_ledger.load_ledger(path)
        budget_ledger.reservation(ledger, receipt["reservation_id"])
        ledger["stop_new_dispatch"] = True
        ledger.setdefault("dispatch_stop_reasons", []).append({"reservation_id": receipt["reservation_id"],
             "reason": reason, "at": datetime.now(timezone.utc).isoformat()})
        save(path, ledger)


def build_dispatch(suite, root, selected):
    """用固定任务与参数列表构造命令；credentials仅进入子进程环境。"""
    if suite == "memory":
        command = [sys.executable, "-X", "utf8", "-B", str(REPO / "tools/memory_evaluation/run_acceptance.py"),
                   "live", "--execute-live", "--env-file", str(ENV_FILE.resolve()), "--evidence-dir", str(root),
                   "--gradle-user-home", str(REPO / ".gradle-user-home")]
        environment = memory_launcher.safe_environment(None, None, SUITE_FILE, "offline")
        return command, environment, REPO
    environment = memory_launcher.safe_environment(None, None, SUITE_FILE, "offline")
    environment.update(DASHSCOPE_API_KEY=selected["DASHSCOPE_API_KEY"], LIVE_KNOWLEDGE_ACCEPTANCE="true",
                       LIVE_KNOWLEDGE_BATCH_DIR=str(root))
    wrapper = REPO / "backend-java" / ("gradlew.bat" if os.name == "nt" else "gradlew")
    require(wrapper.is_file(), "gradle_wrapper_missing")
    if os.name == "nt":
        require(not memory_launcher.UNSAFE_BATCH_ARGUMENT.search(str(wrapper)), "unsafe_batch_wrapper_path")
    return [str(wrapper), "--gradle-user-home", str(REPO / ".gradle-user-home"), ":skillforge-institution-service:liveKnowledgeAcceptance",
            "--no-parallel", "--max-workers=1", "--console=plain", "--no-daemon"], environment, REPO / "backend-java"


def run_owned_process(command, environment, cwd, control, receipt):
    """只有本次Popen创建的进程树可终止；timeout先封门，不猜测外调未派发。"""
    started_at = datetime.now(timezone.utc).isoformat()
    begun = time.monotonic()
    value = {"started_at": started_at, "elapsed_seconds": 0, "return_code": None, "timed_out": False,
             "command": command, "cwd": str(cwd), "reservation_id": receipt["reservation_id"]}
    write_new(control / "dispatch-start.json", value)
    try:
        with (control / "command.log").open("x", encoding="utf-8") as log:
            options = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
            process = subprocess.Popen(command, cwd=cwd, env=environment, stdout=log, stderr=subprocess.STDOUT, shell=False, **options)
            write_new(control / "owned-process.json", {"pid": process.pid, "reservation_id": receipt["reservation_id"]})
            try:
                value["return_code"] = process.wait(timeout=receipt["max_seconds"])
            except subprocess.TimeoutExpired:
                value["timed_out"] = True
                stop_gate(receipt, "owned_process_timeout")
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=log, stderr=log, check=False)
                else:
                    os.killpg(process.pid, signal.SIGKILL)
                try:
                    value["return_code"] = process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    value["termination_unconfirmed"] = True
    except OSError:
        value["launch_error"] = "owned_process_start_failed"
    value.update(elapsed_seconds=time.monotonic() - begun, finished_at=datetime.now(timezone.utc).isoformat())
    write_new(control / "dispatch-finish.json", value)
    return value


def collect_and_settle(receipt, execution, binding, control):
    """生成与主ledger schema一致的独立结算证据，逐artifact绑定不可变字节。"""
    held = budget_ledger.check_reservation(budget_ledger.LEDGER_PATH, receipt["reservation_id"], query_only=True)
    require(held == receipt, "reservation_receipt_ledger_drift")
    root = Path(receipt["evidence_root"])
    try:
        parser = parse_memory if receipt["suite"] == "memory" else parse_knowledge
        measured = parser(root, receipt, execution, binding)
    except (EvidenceError, reporting.ReportError, OSError, KeyError, TypeError, ValueError) as failure:
        measured = base_measurement(receipt, execution)
        code = str(failure) if isinstance(failure, EvidenceError) else "knowledge_memory_evidence_incomplete_or_invalid"
        measured["issues"] = [code]
    if execution.get("return_code") is None or execution.get("timed_out") or execution.get("termination_unconfirmed"):
        measured.update(usage_known=False, stop_new_dispatch=True)
    caps = budget_ledger.LIMITS[receipt["suite"]]
    fields = {"observed_elapsed_seconds" if key == "max_seconds" else key.replace("max_", "observed_", 1) for key in caps}
    actual = {"usage_known": measured["usage_known"], **{key: measured[key] for key in fields if key in measured}}
    metrics = {"observed_elapsed_seconds" if key == "max_seconds" else key.replace("max_", "observed_", 1): value for key, value in caps.items()}
    if any(key in actual and actual[key] > cap for key, cap in metrics.items()):
        measured["stop_new_dispatch"] = True
        measured["issues"].append("supervisor_actual_cap_exceeded")
    measured["source_binding_sha256"] = digest(LIVE_ROOT / "source-binding.json")
    path = control / "settlement-evidence.json"
    if path.exists():
        require(decode(path.read_bytes()) == measured, "original_settlement_evidence_drift")
    else:
        write_new(path, measured)
    # 在释放RESERVED前封住已确认的设施/结果失败，防止中途退出留下可派发窗口。
    if measured["stop_new_dispatch"]:
        stop_gate(receipt, "knowledge_memory_usage_or_execution_gate")
    result = budget_ledger.settle(budget_ledger.LEDGER_PATH, receipt["reservation_id"], actual, path)
    write_new(control / "settlement-result.json", {"status": result["status"], "actual": result["actual"],
              "reservation_id": receipt["reservation_id"], "evidence": str(path),
              "acceptance_outcome": measured["acceptance_outcome"], "execution_complete": measured["execution_complete"],
              "stop_new_dispatch": measured["stop_new_dispatch"], "monetary_amount": None})
    return result, measured


def execute_lane(suite, number, selected, binding, settle_only=False):
    """每次仅一个suite/round，主线程可分六次串行调度，与其他Gradle资源协调。"""
    budget_ledger.canonical_path(budget_ledger.LEDGER_PATH)
    bind_source(binding)
    root = (LIVE_ROOT / f"round-{number}" / suite).resolve()
    control = root.with_name(suite + "-control")
    receipt_path = control / "reservation.json"
    if settle_only:
        require(receipt_path.is_file(), "original_reservation_required_for_settle_only")
        receipt = decode(receipt_path.read_bytes())
        identity(receipt, suite, root)
        require(receipt["round"] == number, "reservation_round_mismatch")
        live = budget_ledger.check_reservation(budget_ledger.LEDGER_PATH, receipt["reservation_id"], query_only=True)
        require(live == receipt, "reservation_receipt_ledger_drift")
        if (control / "dispatch-finish.json").is_file():
            execution = decode((control / "dispatch-finish.json").read_bytes())
        else:
            execution = decode((control / "dispatch-start.json").read_bytes())
            execution.update(elapsed_seconds=seconds((datetime.now(timezone.utc) - reporting.parse_time(execution["started_at"])).total_seconds()),
                             timed_out=True, return_code=None)
            stop_gate(receipt, "original_dispatch_terminal_unconfirmed")
    else:
        require(not root.exists() and not control.exists(), "fresh_lane_evidence_required_no_automatic_retry")
        command, environment, cwd = build_dispatch(suite, root, selected)
        control.mkdir(parents=True, exist_ok=False)
        receipt = budget_ledger.reserve(budget_ledger.LEDGER_PATH, suite, number,
                   f"{budget_ledger.RUN_ID}:{suite}:round-{number}", root)
        write_new(receipt_path, receipt)
        budget_ledger.check_reservation(budget_ledger.LEDGER_PATH, receipt["reservation_id"])
        execution = run_owned_process(command, environment, cwd, control, receipt)
    return collect_and_settle(receipt, execution, binding, control)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=("memory", "knowledge"), required=True)
    parser.add_argument("--round", type=int, choices=(1, 2, 3), required=True)
    parser.add_argument("--env-file", type=Path, default=ENV_FILE)
    parser.add_argument("--execute-live", action="store_true", help="仅主线程在共享Gradle executor空闲且授权派发时使用")
    parser.add_argument("--settle-only", action="store_true", help="只核对原reservation/原目录，不启动进程或付费重试")
    args = parser.parse_args(argv)
    try:
        require(not (args.execute_live and args.settle_only), "execute_and_settle_only_exclusive")
        selected, binding = environment_and_binding(args.env_file)
        if not args.execute_live and not args.settle_only:
            print(f"READY {args.suite} round={args.round}; validation only, no reservation/Gradle/provider dispatch")
            return 0
        result, measured = execute_lane(args.suite, args.round, selected, binding, args.settle_only)
        print(json.dumps({"suite": args.suite, "round": args.round, "status": result["status"],
               "acceptance_outcome": measured["acceptance_outcome"], "execution_complete": measured["execution_complete"],
               "stop_new_dispatch": measured["stop_new_dispatch"]}))
        return 0 if result["status"] == "SETTLED" and not measured["stop_new_dispatch"] else 3
    except (EvidenceError, memory_launcher.LaunchError, ValueError, OSError, KeyError):
        print("knowledge_memory_wrapper_preflight_or_settlement_rejected", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
