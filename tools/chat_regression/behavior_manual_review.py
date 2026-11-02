"""从冻结 Product 结果生成只含公开回答的人工抽评包，不代替人工评分。"""
import argparse
from pathlib import Path

from behavior_contract import digest, require
from behavior_opik_export import sha_file
from behavior_reporting import _redact
from runner import read, save

DIMENSIONS = ("完整性", "清晰度", "实用性", "限制说明")


def export_manual_review(requests, destination):
    destination = Path(destination).resolve()
    require(not destination.exists(), "manual_review_destination_exists")
    require(isinstance(requests, list) and bool(requests), "manual_review_requests_required")
    entries, identities, sources = [], set(), {}
    for request in requests:
        root = Path(request["root"]).resolve()
        require(not destination.is_relative_to(root) and not root.is_relative_to(destination),
                "manual_review_destination_must_be_separate")
        require(not (root / "execution.lock").exists(), "manual_review_source_running")
        names = ("plan.json", "evaluation.json", "batch.json", "private.json")
        before = {name: sha_file(root / name) for name in names}
        plan, report, batch, private = (read(root / name) for name in names)
        require(plan["fingerprint"] == digest({k: v for k, v in plan.items() if k != "fingerprint"})
                and plan["suite_version"] == "product-tasks-1", "manual_review_plan_invalid")
        require(report["lifecycle"] == batch["lifecycle"] == "finalized"
                and report["binding"]["plan_fingerprint"] == plan["fingerprint"]
                and all(report["binding"].get(k) == v for k, v in batch["binding"].items()),
                "manual_review_source_not_bound")
        for cid in request["case_ids"]:
            require(isinstance(cid, str) and cid in {c["id"] for c in plan["cases"]}, "manual_review_case_invalid")
            case = next(c for c in plan["cases"] if c["id"] == cid)
            trials = [r for r in report["cases"] if r["case_id"] == cid]
            require(bool(trials), "manual_review_trial_missing")
            for trial in trials:
                repetition = trial["repetition"]
                require(type(repetition) is int and repetition > 0, "manual_review_trial_invalid")
                identity = f"{plan['batch']}:{cid}:{repetition}"
                require(identity not in identities, "manual_review_duplicate_trial")
                identities.add(identity)
                name = f"cases/{cid}/{repetition}/evidence.json"
                evidence = read(root / name) if (root / name).is_file() else {}
                if evidence:
                    before[name] = sha_file(root / name)
                    require(evidence.get("case_id") == cid and evidence.get("repetition") == repetition,
                            "manual_review_evidence_mismatch")
                # 不遍历 events/trace/commentary；没有可绑定最终回答时明确保留缺失。
                available = evidence.get("privacy_verified") is True and bool(evidence.get("answer_event_id"))
                answer = evidence.get("answer") if available else None
                available = isinstance(answer, str) and bool(answer.strip())
                entry = {
                    "id": identity, "source_batch": plan["batch"], "source_plan_fingerprint": plan["fingerprint"],
                    "case_id": cid, "repetition": repetition, "candidate_id": case["candidate"]["candidate_id"],
                    "source_behavior_status": trial["behavior_status"],
                    "goal": case["candidate"]["goal"], "user_turns": case["turns"],
                    "request_scope": "frozen_candidate_wording_only_not_reconstructed_runtime_request",
                    "materials": [m for m in case["candidate"]["materials"]
                                  if m["ref"] in case["candidate"]["material_refs"]],
                    "final_answer": answer if available else None,
                    "answer_status": "available" if available else "missing_or_unverified",
                    "reference_answer_draft": case["candidate"]["reference_answer"], "reference_is_gold": False,
                    "assertions": [{"id": a["id"], "status": a["status"]} for a in trial.get("assertions", [])],
                    "scores": {d: None for d in DIMENSIONS}, "reviewer": None, "status": "pending",
                }
                entries.append(_redact(entry, tuple(private.get("sensitive_values", []))))
        require(all(sha_file(root / n) == h for n, h in before.items()), "manual_review_source_changed")
        sources[str(root)] = before
    pack = {"format": "skillforge-manual-review-pack", "schema_version": 1, "sources": sources,
            "entries": entries, "score_range": [0, 2], "judge_enabled": False,
            "human_quality_status": "pending", "release_eligible": False}
    pack["fingerprint"] = digest(pack)
    destination.mkdir(parents=True, exist_ok=False)
    save(destination / "review-pack.json", pack)
    save(destination / "scores-template.json", {"pack_fingerprint": pack["fingerprint"],
         "entries": [{k: e[k] for k in ("id", "scores", "reviewer", "status")} for e in entries]})
    lines = ["# 人工抽评包", "", "各维度 0–2 分：0 未满足，1 部分满足，2 充分满足。未评留空；参考草稿不是金标准。",
             "缺少最终答复时只记录缺失，不把进展、工具输出或私有推理补成最终答案。评分不覆盖硬断言。", ""]
    for entry in entries:
        lines += ["## " + entry["id"], "", f"原结果：{entry['source_behavior_status']}；人工状态：pending", "",
                  "目标：" + entry["goal"], "", "冻结候选表达（不冒充包含全部场景约束的实际发送消息）：", "",
                  *entry["user_turns"], "", "资料：", ""]
        for material in entry["materials"]:
            lines += [material["ref"], "", material["content"], ""]
        lines += ["最终回答：", "", entry["final_answer"] or "【缺少可验证的最终回答】", "",
                  "参考草稿（未验证）：", "", entry["reference_answer_draft"], ""]
    (destination / "review.md").write_text("\n".join(lines), encoding="utf-8")
    return pack


if __name__ == "__main__":
    from behavior_main import private_root
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--requests", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    pack = export_manual_review(read(args.requests), private_root(args.out))
    print(f"manual_review_entries={len(pack['entries'])}; human_quality_status=pending")
