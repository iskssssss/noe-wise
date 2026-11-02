"""只读汇总正式运行证据；原批次与修正批次分别保留，不读取私有环境文件。"""
from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

from run_suite import REPO, save


ROOT = REPO / ".runtime-logs/full-project-test-20261012"
FAMILIES = (
    "backend", "backend-correction", "backend-fixture-diagnostic-1", "backend-final",
    "compile-correction-1", "compile-correction-2", "compile-correction-3",
    "frontend", "frontend-platform-correction", "frontend-browser-xml-correction",
    "frontend-platform-unit-xml-correction",
    "tools", "tools-correction", "tools-final", "sandbox", "sandbox-lifecycle",
    "sandbox-lifecycle-binding-correction", "process-recovery",
)


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def collect():
    """直接读取每个正式lane原收据；RUNNING缺完整结果，不能推算其通过数。"""
    lanes, vectors, bindings = [], defaultdict(dict), []
    for family in FAMILIES:
        folder = ROOT / family
        binding = folder / "input-binding.json"
        if binding.exists():
            value = read(binding)
            bindings.append({"family": family, **value, "source": str(binding.relative_to(REPO))})
        for path in sorted(folder.glob("round-*/*/result.json")):
            value = read(path)
            if "id" not in value or "status" not in value:
                continue
            number = int(path.parent.parent.name.removeprefix("round-"))
            lane = {"family": family, "round": number, "suite": value["id"],
                    "status": value["status"], "tests": value.get("test_count"),
                    "failures": value.get("failures"), "skipped": value.get("skipped"),
                    "exit_code": value.get("exit_code"), "elapsed_seconds": value.get("elapsed_seconds"),
                    "started_at": value.get("started_at"), "finished_at": value.get("finished_at"),
                    "source": path.relative_to(REPO).as_posix(),
                    "source_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "evidence_issue": value.get("evidence_issue")}
            if lane["status"] != "RUNNING" and lane["tests"] is not None:
                lane["passed"] = lane["tests"] - lane["failures"] - lane["skipped"]
            else:
                lane["passed"] = None
            case_counts = defaultdict(int)
            for row in value.get("junit", []):
                xml = path.parent / "evidence" / row["path"]
                if not xml.is_file():
                    lane.setdefault("missing_evidence", []).append(row["path"])
                    continue
                for case in ET.parse(xml).getroot().iter("testcase"):
                    key = (case.get("classname", ""), case.get("name", ""))
                    case_counts[key] += 1
                    identity = (family, value["id"], *key, case_counts[key])
                    status = ("FAIL" if case.find("failure") is not None or case.find("error") is not None
                              else "SKIP" if case.find("skipped") is not None else "PASS")
                    vectors[identity][number] = status
            lanes.append(lane)
    cases = []
    for identity, rounds in sorted(vectors.items()):
        family, suite, classname, name, repetition = identity
        statuses = [rounds.get(number, "NOT_RUN") for number in (1, 2, 3)]
        # Same input and environment are additional requirements; this flag is only a candidate.
        fluctuation = "PASS" in statuses and "FAIL" in statuses
        cases.append({"family": family, "suite": suite, "class": classname, "case": name,
                      "occurrence": repetition, "round_status": statuses,
                      "fluctuation_candidate_only": fluctuation})
    return {"schema_version": 1, "captured_at": datetime.now(timezone.utc).isoformat(),
            "scope": "formal_run_suite_receipts_only_not_all_business_coverage",
            "original_and_correction_counts_must_not_be_merged": True,
            "lanes": lanes, "input_bindings": bindings, "testcase_vectors": cases}


def markdown(value):
    lines = ["# 自动化测试执行快照", "", f"采集时间：{value['captured_at']}。",
             "", "本快照只汇总正式执行器的原始收据。原批次、设施修正和补测分别统计；真实行为、浏览器网络、Linux、视觉、接口业务及费用结算另有专门收据。没有完整结果的轮次保持 RUNNING/NOT_RUN。", "",
             "| 批次 | 套件 | 轮次 | 状态 | 已执行 | 通过 | 失败 | skip | exit | 秒 |",
             "|---|---|---:|---|---:|---:|---:|---:|---:|---:|"]
    for row in value["lanes"]:
        fields = [row.get(key) for key in ("family", "suite", "round", "status", "tests", "passed",
                                           "failures", "skipped", "exit_code", "elapsed_seconds")]
        lines.append("| " + " | ".join("—" if item is None else str(item) for item in fields) + " |")
    changed = [row for row in value["input_bindings"] if row.get("unchanged") is not True]
    lines += ["", f"正式XML中的测试执行身份共 {len(value['testcase_vectors'])} 个；该数包含不同批次身份，不能作为全项目独立功能数。", "",
              f"输入变化批次：{len(changed)}。全仓摘要变化还需对该套件的实际输入范围核对，不能直接归为概率性故障。"]
    for row in changed:
        lines += ["", f"- {row['family']}：" + "、".join(row.get("changed_paths", []))]
    candidates = [row for row in value["testcase_vectors"] if row["fluctuation_candidate_only"]]
    lines += ["", f"相同case身份出现 PASS/FAIL 混合的候选：{len(candidates)}。仍需核对输入、前置和资源条件，不能自动认定模型或生产概率问题。"]
    for row in candidates:
        lines += ["", f"- {row['family']}/{row['suite']}：{row['class']}.{row['case']} [{row['occurrence']}]：" + "/".join(row["round_status"])]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    output = args.root.resolve()
    if not output.is_relative_to(ROOT.resolve()) or output.exists():
        parser.error("snapshot requires a fresh directory within the fixed evidence root")
    value = collect()
    save(output / "execution-snapshot.json", value)
    (output / "execution-snapshot.md").write_text(markdown(value), encoding="utf-8")
    print(json.dumps({"root": str(output), "formal_lanes": len(value["lanes"]),
                      "xml_case_identities": len(value["testcase_vectors"])}))


if __name__ == "__main__":
    main()
