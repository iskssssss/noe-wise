"""Query-only settlement of an interrupted owned live run; never dispatch or replay."""
from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path

import budget_ledger as ledger
from live_behavior_usage import natural
from run_suite import save


def owner_is_alive(pid):
    if type(pid) is not int or pid <= 0:
        raise ValueError("settle_only_owner_identity_required")
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.GetExitCodeProcess.argtypes = (wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD))
        kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            if ctypes.get_last_error() == 87:
                return False
            raise ValueError("settle_only_owner_status_unavailable")
        try:
            status = wintypes.DWORD()
            if not kernel.GetExitCodeProcess(handle, ctypes.byref(status)):
                raise ValueError("settle_only_owner_status_unavailable")
            return status.value == 259
        finally:
            kernel.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        raise ValueError("settle_only_owner_status_unavailable") from None


def read_record(path):
    raw = path.read_bytes()
    return json.loads(raw), hashlib.sha256(raw).hexdigest()


def settle_only(root, api, provider, *, now=None):
    """Use the original boundary and receipts. Missing receipts remain UNKNOWN."""
    root = Path(root).resolve()
    receipt, receipt_hash = read_record(root / "supervisor-reservation.json")
    row = ledger.check_reservation(ledger.LEDGER_PATH, receipt["reservation_id"], query_only=True)
    if row["status"] != "RESERVED":
        raise ValueError("settle_only_requires_original_active_reservation")
    for key in ("reservation_id", "batch_id", "suite", "round", "evidence_root", "ledger_path",
                "diagnostic_sequence", "active_evidence_root"):
        if receipt.get(key) != row.get(key):
            raise ValueError("settle_only_receipt_owner_mismatch")
    if Path(row.get("active_evidence_root", row["evidence_root"])).resolve() != root:
        raise ValueError("settle_only_active_root_mismatch")
    journal, journal_hash = read_record(root / "guard/dispatch-journal.json")
    if (journal.get("reservation_id") != row["reservation_id"] or journal.get("batch_id") != row["batch_id"]
            or not isinstance(journal.get("intents"), list)):
        raise ValueError("settle_only_journal_identity_invalid")
    pid = journal.get("owner_pid")
    if pid is None:
        stopped, _ = read_record(root / "owned-stop.json")
        if stopped.get("action") != "stop_invalid_contract_inputs":
            raise ValueError("settle_only_legacy_stop_receipt_required")
        pid = stopped.get("pid")
    if owner_is_alive(pid):
        raise ValueError("settle_only_dispatch_owner_still_alive")
    boundary, boundary_hash = read_record(root / "provider-boundary.json")
    if set(boundary) != {"invocation_id_upper_bound", "usage_id_upper_bound"}:
        raise ValueError("settle_only_original_boundary_invalid")
    for value in boundary.values():
        natural(value)
    provider.boundary = boundary  # Never take a new preflight boundary after the interrupted run.
    sessions, unresolved, run_facts = set(), [], []
    unknown_receipts = False
    for index, item in enumerate(journal["intents"], 1):
        if (item.get("sequence") != index or type(item.get("is_input")) is not bool
                or item.get("phase") not in {"intent", "receipt"}
                or item["is_input"] != (item.get("method") == "POST" and item.get("path") == "/api/v1/chat")):
            raise ValueError("settle_only_journal_record_invalid")
        unknown_receipts |= item["phase"] != "receipt"
        if not item["is_input"]:
            continue
        session = item.get("session_id")
        if not isinstance(session, str) or not session:
            raise ValueError("settle_only_input_session_identity_invalid")
        sessions.add(session)
        if item["phase"] != "receipt":
            unresolved.append(index)
            continue
        if (item.get("run_id") != item.get("expected_run_id")
                or not isinstance(item.get("input_id"), str) or not item["input_id"]):
            raise ValueError("settle_only_input_receipt_identity_invalid")
        try:
            from client import query
            state = api.request("GET", query("/chat/runs/status", session_id=session, run_id=item["run_id"]))
            fact = {"sequence": index, "session_id": state.get("session_id"),
                    "run_id": state.get("execution_id"), "status": state.get("status")}
            ready = (fact["session_id"] == session and fact["run_id"] == item["run_id"]
                     and fact["status"] in {"completed", "failed", "cancelled", "terminated"})
            run_facts.append({**fact, "terminal_identity_confirmed": ready})
            if not ready:
                unresolved.append(index)
        except Exception:
            unresolved.append(index)
            run_facts.append({"sequence": index, "status_read_failed": True})
    snapshot, read_failed = None, False
    try:
        snapshot = provider.snapshot(sessions)
    except Exception:
        read_failed = True
        latest = root / "guard/provider-latest.json"
        if latest.is_file():
            snapshot, _ = read_record(latest)
            if snapshot.get("boundary") != boundary:
                snapshot = None
    started = datetime.fromisoformat(row.get("diagnostic_reopened_at", row["issued_at"]))
    ended = now or datetime.now(timezone.utc)
    if started.tzinfo is None or ended.tzinfo is None or ended < started:
        raise ValueError("settle_only_original_elapsed_identity_invalid")
    actual = {"usage_known": bool(snapshot is not None and snapshot["usage_known"] and not read_failed
                                   and not unknown_receipts and not unresolved),
              "observed_input_turns": sum(item["is_input"] for item in journal["intents"]),
              "observed_elapsed_seconds": (ended - started).total_seconds()}
    if snapshot is not None:
        actual["observed_tokens"] = natural(snapshot["tokens"])
    evidence = {**actual, "reservation_id": row["reservation_id"], "batch_id": row["batch_id"],
                "mode": "settle_only_no_dispatch_no_replay", "provider_usage": snapshot,
                "provider_read_failed": read_failed, "dispatch_receipts_unknown": unknown_receipts,
                "unresolved_run_sequences": unresolved, "run_facts": run_facts,
                "dispatch_owner_pid": pid, "dispatch_owner_terminated": True,
                "original_artifact_sha256": {"reservation": receipt_hash, "boundary": boundary_hash,
                                             "journal": journal_hash},
                "elapsed_source": "conservative_wall_clock_original_issue_or_diagnostic_reopen_to_recovery",
                "started_at": started.isoformat(), "recovered_at": ended.isoformat(),
                "behavior_results": "unchanged; interrupted or absent evaluations are not successful"}
    path = root / "guard/settlement-only-evidence.json"
    # Preserve prior evidence; an interrupted recovery can be inspected instead of overwritten.
    with path.open("x", encoding="utf-8") as handle:
        json.dump(evidence, handle, ensure_ascii=False, indent=2)
    result = ledger.settle(ledger.LEDGER_PATH, row["reservation_id"], actual, path)
    save(root / "settlement-only-result.json", result)
    return result
