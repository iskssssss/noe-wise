"""三轮实时验收共享预算预留；新证据目录不能重置配额，未知用量停止新派发。"""
from __future__ import annotations
import argparse
import json
import math
import hashlib
from pathlib import Path
from datetime import datetime, timezone
from contextlib import contextmanager
import uuid
from run_suite import REPO, save

RUN_ID = "full-project-test-20261012"
# 根线程全部初跑/诊断/重跑共用此身份，证据目录和CLI参数不能创建第二本账。
LEDGER_PATH = REPO / ".runtime-logs" / RUN_ID / "live-budget-ledger.json"

# 单轮上限冻结自已选套件；预留不会自动提升供应商/套件内部上限。
LIMITS = {
    "core": {"max_input_turns": 200, "max_tokens": 2000000, "max_seconds": 7200},
    "stress": {"max_input_turns": 200, "max_tokens": 2000000, "max_seconds": 7200},
    "product": {"max_input_turns": 60, "max_tokens": 2000000, "max_seconds": 10800},
    "skills": {"max_input_turns": 60, "max_tokens": 2000000, "max_seconds": 10800},
    "memory": {"max_attempts": 1200, "max_tokens": 1000000, "max_seconds": 7200},
    "knowledge": {"max_embedding_calls": 20, "max_rerank_calls": 10, "max_embedding_tokens": 50000, "max_rerank_tokens": 50000, "max_tokens": 100000, "max_seconds": 2700},
    "conversation": {"max_attempts": 2, "max_tokens": 20000, "max_seconds": 600},
    "tool-conversation": {"max_attempts": 3, "max_tokens": 40000, "max_seconds": 600},
    "chat-browser": {"max_attempts": 1, "max_tokens": 20000, "max_seconds": 600},
    "prompt-cache": {"max_attempts": 18, "max_tokens": 400000, "max_seconds": 600},
    "operational-config": {"max_attempts": 6, "max_tokens": 400000, "max_seconds": 480},
    "capability": {"max_attempts": 16, "max_tokens": 160000, "max_seconds": 1800},
    "public": {"max_attempts": 64, "max_tokens": 3000000, "max_seconds": 7200},
    "vision": {"max_attempts": 6, "max_tokens": 60000, "max_seconds": 600},
    "dataset-generation": {"max_attempts": 24, "max_tokens": 300000, "max_seconds": 1800},
    "provider-smoke": {"max_attempts": 4, "max_tokens": 40000, "max_seconds": 600},
    "provider-cache": {"max_attempts": 24, "max_tokens": 800000, "max_seconds": 1800},
}
APPENDABLE_SCOPES = {key: dict(LIMITS[key]) for key in ("provider-smoke", "provider-cache")}


def canonical_path(path):
    """只有主线程固定账本有写权，换cwd/证据目录/文件名均不能重置预算。"""
    resolved = Path(path).resolve()
    if resolved != LEDGER_PATH.resolve():
        raise ValueError("single_supervisor_ledger_required")
    return resolved


@contextmanager
def ledger_lock(path):
    """单paid lane的跨进程排他锁；遗留锁须人工查证，不能自动猜测未派发。"""
    lock = path.with_suffix(path.suffix + ".lock")
    lock.parent.mkdir(parents=True, exist_ok=True)
    try:
        handle = lock.open("x", encoding="utf-8")
    except FileExistsError:
        raise ValueError("supervisor_ledger_locked_no_parallel_dispatch") from None
    try:
        with handle:
            handle.write(str(uuid.uuid4()))
            handle.flush()
            yield
    finally:
        lock.unlink()


def load_ledger(path, create=False, expected_limits=None):
    """冻结计划、唯一预留和文件身份必须来自同一当前账本。"""
    if not path.exists():
        if not create:
            raise ValueError("supervisor_ledger_missing")
        return {"schema_version": 1, "run_id": RUN_ID, "rounds": 3,
                "limits": LIMITS, "reservations": [], "stop_new_dispatch": False}
    ledger = json.loads(path.read_text(encoding="utf-8"))
    plan = LIMITS if expected_limits is None else expected_limits
    if (ledger.get("schema_version") != 1 or ledger.get("run_id") != RUN_ID
            or ledger.get("limits") != plan or ledger.get("rounds") != 3
            or not isinstance(ledger.get("reservations"), list)
            or type(ledger.get("stop_new_dispatch")) is not bool):
        raise ValueError("frozen_budget_plan_drift")
    identities, rounds, outstanding = set(), set(), 0
    for row in ledger["reservations"]:
        if (not isinstance(row, dict) or row.get("suite") not in plan
                or type(row.get("round")) is not int or row["round"] not in (1, 2, 3)
                or row.get("status") not in ("RESERVED", "SETTLED", "UNKNOWN", "EXCEEDED")
                or row.get("run_id") != RUN_ID or row.get("ledger_path") != str(path)
                or any(row.get(key) != value for key, value in plan[row["suite"]].items())):
            raise ValueError("reservation_plan_or_owner_drift")
        try:
            uuid.UUID(row["reservation_id"])
        except (KeyError, TypeError, ValueError):
            raise ValueError("reservation_identity_invalid") from None
        identity, suite_round = row["reservation_id"], (row["suite"], row["round"])
        if identity in identities or suite_round in rounds:
            raise ValueError("reservation_identity_or_round_duplicate")
        identities.add(identity); rounds.add(suite_round)
        outstanding += row["status"] == "RESERVED"
    if outstanding > 1:
        raise ValueError("parallel_outstanding_reservations_invalid")
    return ledger


def append_provider_scopes(path, additions, expected_sha256, receipt_path):
    """授权的两个新scope一次性追加；旧cap/预留/actual不可修改，锁内绑定旧SHA。"""
    path = canonical_path(path)
    receipt_path = Path(receipt_path).resolve()
    if (not isinstance(additions, dict) or set(additions) != set(APPENDABLE_SCOPES)
            or additions != APPENDABLE_SCOPES):
        raise ValueError("scope_append_only_exact_new_provider_caps")
    if receipt_path == path or receipt_path.exists():
        raise ValueError("scope_append_requires_fresh_owned_receipt")
    with ledger_lock(path):
        original = path.read_bytes()
        old_sha = hashlib.sha256(original).hexdigest()
        if old_sha != expected_sha256:
            raise ValueError("scope_append_old_sha_mismatch")
        raw = json.loads(original)
        if set(additions) & set(raw.get("limits", {})):
            raise ValueError("scope_append_existing_caps_immutable")
        prior_plan = {key: value for key, value in LIMITS.items() if key not in additions}
        ledger = load_ledger(path, expected_limits=prior_plan)
        if ledger["stop_new_dispatch"] or any(row["status"] != "SETTLED" or row.get("usage_known") is not True
                                              for row in ledger["reservations"]):
            raise ValueError("scope_append_requires_no_active_and_known_settled_reservations")
        reservation_json = json.dumps(ledger["reservations"], sort_keys=True, ensure_ascii=False).encode("utf-8")
        prior_revision = ledger.get("configuration_revision", 1)
        if type(prior_revision) is not int or prior_revision < 1:
            raise ValueError("scope_append_configuration_revision_invalid")
        history = {"operation": "append_fixed_provider_scopes", "at": datetime.now(timezone.utc).isoformat(),
                   "prior_configuration_revision": prior_revision, "configuration_revision": prior_revision + 1,
                   "old_sha256": old_sha, "added_scopes": json.loads(json.dumps(additions)),
                   "preserved_reservations_sha256": hashlib.sha256(reservation_json).hexdigest(),
                   "preserved_reservations": len(ledger["reservations"]), "owner_receipt": str(receipt_path)}
        ledger["limits"] = {**ledger["limits"], **json.loads(json.dumps(additions))}
        ledger["configuration_revision"] = prior_revision + 1
        ledger["configuration_history"] = [*ledger.get("configuration_history", []), history]
        # Prepare the external receipt before the sole ledger mutation, then bind its final SHA.
        receipt_path.parent.mkdir(parents=True, exist_ok=True)
        with receipt_path.open("x", encoding="utf-8") as stream:
            json.dump({**history, "status": "PREPARED", "ledger_path": str(path)}, stream, ensure_ascii=False, indent=2)
        save(path, ledger)
        receipt = {**history, "status": "APPLIED", "ledger_path": str(path),
                   "new_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        save(receipt_path, receipt)
        return receipt


def reservation(ledger, identity):
    """精确身份寻址，未知ID不能落到另一预留。"""
    row = next((item for item in ledger["reservations"] if item["reservation_id"] == identity), None)
    if row is None:
        raise ValueError("reservation_not_found")
    return row


def check_reservation(path, identity, query_only=False):
    """恢复查询可读取stopped/终态；新发送必须仍持有唯一有效预留。"""
    path = canonical_path(path)
    with ledger_lock(path):
        ledger = load_ledger(path)
        row = reservation(ledger, identity)
        if not query_only and (ledger["stop_new_dispatch"] or row["status"] != "RESERVED"):
            raise ValueError("stopped_or_settled_reservation_query_only")
        return dict(row)


def reserve(path, suite, round_number, batch_id, evidence_root):
    """一次唯一预留占据一个suite/round；前预留未结算时不派发下一lane。"""
    path = canonical_path(path)
    if suite not in LIMITS or type(round_number) is not int or round_number not in (1, 2, 3):
        raise ValueError("suite_or_round_invalid")
    if not isinstance(batch_id, str) or not batch_id.strip():
        raise ValueError("batch_identity_required")
    with ledger_lock(path):
        ledger = load_ledger(path, create=True)
        if ledger["stop_new_dispatch"]:
            raise ValueError("unknown_or_budget_failure_stops_new_dispatch")
        if any(row["status"] == "RESERVED" for row in ledger["reservations"]):
            raise ValueError("unsettled_reservation_blocks_next_paid_lane")
        if any(row["suite"] == suite and row["round"] == round_number for row in ledger["reservations"]):
            raise ValueError("suite_round_already_reserved_diagnostics_share_original_reservation")
        receipt = {"schema_version": 1, "run_id": ledger["run_id"], "suite": suite, "round": round_number,
                   "status": "RESERVED", "usage_known": True, "issued_at": datetime.now(timezone.utc).isoformat(),
                   "reservation_id": str(uuid.uuid4()), "batch_id": batch_id,
                   "evidence_root": str(Path(evidence_root).resolve()), "ledger_path": str(path), **LIMITS[suite]}
        ledger["reservations"].append(receipt)
        save(path, ledger)
        return receipt


def reopen_known_diagnostic(path, identity, diagnostic_evidence_root):
    """已知结算的设施诊断复用原身份与剩余额度；保留历史，未知/超额禁止重开。"""
    path = canonical_path(path)
    with ledger_lock(path):
        ledger = load_ledger(path)
        row = reservation(ledger, identity)
        if ledger["stop_new_dispatch"] or row["status"] != "SETTLED" or row.get("usage_known") is not True:
            raise ValueError("diagnostic_requires_known_settled_reservation")
        if any(value["status"] == "RESERVED" for value in ledger["reservations"]):
            raise ValueError("unsettled_reservation_blocks_diagnostic_reopen")
        owner_root, active_root = Path(row["evidence_root"]).resolve(), Path(diagnostic_evidence_root).resolve()
        if owner_root == active_root or owner_root not in active_root.parents:
            raise ValueError("diagnostic_evidence_must_be_new_owner_child")
        used = row.get("diagnostic_evidence_roots", [])
        if str(active_root) in used or active_root.exists():
            raise ValueError("diagnostic_evidence_already_used")
        prior = dict(row["actual"])
        metrics = {"observed_elapsed_seconds" if cap == "max_seconds" else cap.replace("max_", "observed_", 1): cap
                   for cap in LIMITS[row["suite"]]}
        remaining = {cap: row[cap] - prior[metric] for metric, cap in metrics.items()}
        if any(value <= 0 for value in remaining.values()):
            raise ValueError("diagnostic_cumulative_budget_exhausted")
        if not row.get("settlement_history"):
            row["settlement_history"] = [{"diagnostic_sequence": 0, "actual_delta": dict(prior),
                                          "cumulative_actual": dict(prior), "evidence": row["evidence"],
                                          "settled_at": row["settled_at"], "status": "SETTLED"}]
        row.update({"status": "RESERVED", "prior_actual": prior, "active_evidence_root": str(active_root),
                    "diagnostic_sequence": row.get("diagnostic_sequence", 0) + 1,
                    "diagnostic_evidence_roots": [*used, str(active_root)], "remaining_limits": remaining,
                    "diagnostic_reopened_at": datetime.now(timezone.utc).isoformat()})
        save(path, ledger)
        return dict(row)


def settle(path, identity, actual, evidence):
    """只登记一致实测证据；超额真实值保留为EXCEEDED，UNKNOWN/超额阻止后续付费。"""
    path = canonical_path(path)
    with ledger_lock(path):
        ledger = load_ledger(path)
        row = reservation(ledger, identity)
        if row["status"] != "RESERVED":
            raise ValueError("already_settled")
        if not isinstance(actual, dict) or type(actual.get("usage_known")) is not bool:
            raise ValueError("actual_usage_schema_invalid")
        metrics = {"observed_elapsed_seconds" if cap == "max_seconds" else cap.replace("max_", "observed_", 1): cap
                   for cap in LIMITS[row["suite"]]}
        if set(actual) - ({"usage_known"} | set(metrics)):
            raise ValueError("actual_usage_field_invalid")
        if actual["usage_known"] and set(actual) != ({"usage_known"} | set(metrics)):
            raise ValueError("known_usage_requires_every_capped_metric")
        for key, value in actual.items():
            if key == "usage_known":
                continue
            valid = ((type(value) is int and value >= 0) or (type(value) is float and math.isfinite(value) and value >= 0)) if key == "observed_elapsed_seconds" else (type(value) is int and value >= 0)
            if not valid:
                raise ValueError("actual_usage_must_be_finite_nonnegative")
        evidence_path = Path(evidence).resolve()
        if row.get("diagnostic_sequence", 0):
            active_root = Path(row["active_evidence_root"]).resolve()
            if active_root not in evidence_path.parents:
                raise ValueError("diagnostic_settlement_evidence_outside_active_child")
        if not evidence_path.is_file():
            raise ValueError("settlement_evidence_missing")
        try:
            measured = json.loads(evidence_path.read_text(encoding="utf-8"))
        except (ValueError, UnicodeError):
            raise ValueError("settlement_evidence_invalid") from None
        if (not isinstance(measured, dict) or measured.get("reservation_id") != identity
                or measured.get("batch_id") != row["batch_id"]
                or type(measured.get("usage_known")) is not bool
                or measured["usage_known"] != actual["usage_known"]
                or (set(measured) & set(metrics)) != (set(actual) & set(metrics))
                or any(key not in measured or type(measured[key]) is not type(value) or measured[key] != value for key, value in actual.items())):
            raise ValueError("actual_usage_and_evidence_inconsistent")
        prior = row.get("prior_actual", {})
        cumulative = {"usage_known": actual["usage_known"],
                      **{key: prior.get(key, 0) + value for key, value in actual.items() if key != "usage_known"}}
        # 本轮未知维度仍保留此前已经实测的累计值；没有任何已知材料的维度不填0。
        for key, value in prior.items():
            if key != "usage_known" and key not in cumulative:
                cumulative[key] = value
        exceeded = {key: {"actual": value, "limit": row[metrics[key]]} for key, value in cumulative.items()
                    if key != "usage_known" and value > row[metrics[key]]}
        settled_at = datetime.now(timezone.utc).isoformat()
        status = "EXCEEDED" if exceeded else "SETTLED" if actual["usage_known"] else "UNKNOWN"
        history = {"diagnostic_sequence": row.get("diagnostic_sequence", 0), "actual_delta": dict(actual),
                   "cumulative_actual": cumulative, "evidence": str(evidence_path), "settled_at": settled_at, "status": status}
        row.update({"actual": cumulative, "actual_delta": dict(actual), "evidence": str(evidence_path),
                    "settlement_history": [*row.get("settlement_history", []), history],
                    "settled_at": settled_at, "usage_known": actual["usage_known"], "status": status})
        if exceeded:
            row["exceeded"] = exceeded
        ledger["stop_new_dispatch"] |= not row["usage_known"] or bool(exceeded)
        save(path, ledger)
        return dict(row)


def main():
    """该CLI只创建主线程显式预留，不发起模型或业务请求。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--suite", choices=LIMITS, required=True)
    parser.add_argument("--round", type=int, required=True)
    parser.add_argument("--batch-id", required=True)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    if args.receipt.exists():
        raise ValueError("receipt_already_exists")
    save(args.receipt, reserve(args.ledger, args.suite, args.round, args.batch_id, args.evidence_root))
    print(f"RESERVED {args.suite} round={args.round}")


if __name__ == "__main__":
    main()
