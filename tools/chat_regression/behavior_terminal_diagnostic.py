"""冻结证据的离线终态诊断；不执行模型、不评分、不输出模型正文。"""
import argparse
from collections import Counter
from pathlib import Path
import json
import re

from behavior_capture import flatten
from behavior_contract import digest, require
from behavior_opik_export import sha_file
from runner import read, save


def _code(value):
    return value if isinstance(value, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,95}", value) else None


def terminal_facts(events, evidence):
    """传输成功不等于业务成功；只输出显式字段，不从模型答复猜测执行。"""
    terminals, rejections, delegations = [], [], []
    for event in events:
        payload = event.get("payload")
        if not isinstance(payload, dict):
            continue
        ref = {"event_id": event["event_id"], "sequence": (event.get("detail") or {}).get("sessionSequence")}
        kind = event.get("event_type")
        if kind in {"run.terminated", "run.failed", "run.completed", "run.cancelled"}:
            outcome = payload.get("outcome")
            outcome = outcome if isinstance(outcome, dict) else {}
            terminals.append({**ref, "event_type": kind, "reason": _code(outcome.get("reason"))})
        elif kind == "agent.output-rejection":
            rejections.append({**ref, "reason": _code(payload.get("reasonCode"))})
        elif kind == "agent.delegation":
            command, result = payload.get("command"), payload.get("result")
            if isinstance(command, str):
                try:
                    command = json.loads(command)
                except ValueError:
                    continue
            if not isinstance(command, dict) or not isinstance(result, dict):
                continue
            operation = command.get("operation")
            if operation not in {"targets", "start", "wait", "send", "cancel"}:
                continue
            rows = result.get("results")
            delegations.append({**ref, "operation": operation,
                                "accepted": result.get("status") == "accepted",
                                "returned_results": len(rows) if isinstance(rows, list) else None})
    tools = []
    for tool in evidence.get("tools", []):
        result = tool.get("result")
        body = result.get("structuredContent") if isinstance(result, dict) else None
        # 缺业务状态保持 unknown，不把 SUCCEEDED 直接推成业务成功。
        success = body.get("success") if isinstance(body, dict) else None
        business = "failed" if success is False else "passed" if success is True else "unknown"
        tools.append({"event_id": tool.get("event_id"), "transport_status": _code(tool.get("status")),
                      "business_status": business,
                      "reason": _code(body.get("error")) if isinstance(body, dict) else None})
    return {"terminal_events": terminals, "output_rejections": rejections,
            "rejection_counts": dict(Counter(r["reason"] for r in rejections if r["reason"])),
            "tool_outcomes": tools, "delegations": delegations,
            "final_answer_available": bool(evidence.get("answer") and evidence.get("answer_event_id")),
            "diagnosis_scope": "observed_facts_only_not_root_cause_or_rescore"}


def export_terminal_diagnostic(root, case_id, destination):
    root, destination = Path(root).resolve(), Path(destination).resolve()
    require(re.fullmatch(r"D\d{2}", case_id) is not None, "diagnostic_case_invalid")
    require(not destination.exists() and not destination.is_relative_to(root)
            and not root.is_relative_to(destination), "diagnostic_destination_invalid")
    require(not (root / "execution.lock").exists(), "diagnostic_source_running")
    folder = root / "cases" / case_id / "1"
    paths = [root / name for name in ("plan.json", "batch.json", "evaluation.json")]
    paths += [folder / name for name in ("evidence.json", "trace-private.json")]
    require(all(p.resolve().is_relative_to(root) for p in paths), "diagnostic_source_path_escape")
    hashes = {p.relative_to(root).as_posix(): sha_file(p) for p in paths}
    plan, batch, report = (read(p) for p in paths[:3])
    require(plan.get("fingerprint") == digest({k: v for k, v in plan.items() if k != "fingerprint"}),
            "diagnostic_plan_changed")
    require(batch.get("lifecycle") == report.get("lifecycle") == "finalized", "diagnostic_source_not_frozen")
    require(report["binding"]["plan_fingerprint"] == plan["fingerprint"]
            and all(report["binding"].get(k) == v for k, v in batch["binding"].items()), "diagnostic_binding_mismatch")
    trial = next((t for t in report["cases"] if t["case_id"] == case_id and t["repetition"] == 1), None)
    require(trial is not None, "diagnostic_trial_missing")
    evidence = read(folder / "evidence.json")
    require(evidence.get("case_id") == case_id and evidence.get("repetition") == 1, "diagnostic_evidence_mismatch")
    _, events = flatten(read(folder / "trace-private.json"), internal=True)
    result = {"format": "skillforge-terminal-diagnostic", "schema_version": 1,
              "source_batch": plan["batch"], "source_files": hashes, "case_id": case_id,
              "source_behavior_status": trial["behavior_status"], **terminal_facts(events, evidence),
              "new_model_calls": 0, "reproduction_claim": False, "release_eligible": False}
    require(all(sha_file(root / n) == h for n, h in hashes.items()), "diagnostic_source_changed")
    destination.mkdir(parents=True)
    save(destination / "diagnostic.json", result)
    return result


if __name__ == "__main__":
    from behavior_main import private_root
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--case", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = export_terminal_diagnostic(private_root(args.root), args.case, private_root(args.out))
    print(json.dumps({k: result[k] for k in ("source_batch", "source_behavior_status", "rejection_counts", "new_model_calls")}))
