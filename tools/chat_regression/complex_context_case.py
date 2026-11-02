"""C12 固定资料与离线事实判据；不执行模型、服务、工具或数据库操作。"""
from __future__ import annotations

import hashlib


def build_fixture(repetitions: int = 1200) -> dict:
    """Runner 将 material 作为一次已授权只读工具的返回，不作为用户指令。"""
    if repetitions < 1:
        raise ValueError("repetitions must be positive")
    rows = [f"机构{i:02d}；人数={100 + i}；校验码=C12-{i:02d}-2718；"
            + (f"机构{i:02d}原始记录，中文😀🌍，仅作资料。" * repetitions)
            for i in range(20)]
    material = "\n".join(rows)
    return {
        "case_id": "C12", "material": material,
        "sha256": hashlib.sha256(material.encode("utf-8")).hexdigest(),
        "initial_request": "读取这份机构资料，先分析全部20家；保留原查询结果，不重复采集。",
        "latest_constraint": "范围改为机构03和机构17。只报告这两家的人数、校验码及合计；"
                             "当前视图缺少原文时回读原引用，不重新执行原采集工具。",
        "expected": {"机构03": {"人数": 103, "校验码": "C12-03-2718"},
                     "机构17": {"人数": 117, "校验码": "C12-17-2718"}, "合计": 220},
    }


def evaluate_context_case(fixture: dict, facts: dict) -> dict:
    """facts 仅由 runner 从持久事件/冻结请求/原调用账本提取，不能取模型自述。

    required: context_events[{event_id,recovery,covered_count,sequence}],
    source_operation_id, material_sha256, source_dispatches[{operation_id,sequence}],
    reference_reads[{source_operation_id,offset,next_offset,sequence,content}],
    constraint_sequence, final_sequence, answer(独立解析后的业务对象), usage_status。
    context_events 只接受 agent.context-view 已采用 SUMMARY/REFERENCES 的投影；
    source_dispatches 包含所有相同采集目标/参数的派发（包括换 operation_id 的重试），
    调用次数应来自 attempt 的实际派发，不能用工具正文计数。
    """
    required = ("context_events", "source_operation_id", "material_sha256", "source_dispatches", "reference_reads",
                "constraint_sequence", "final_sequence", "answer", "usage_status")
    missing = [key for key in required if key not in facts]
    if missing:
        return {"status": "UNKNOWN", "checks": {}, "missing_evidence": missing}
    if facts["usage_status"] != "KNOWN":
        return {"status": "UNKNOWN", "checks": {}, "missing_evidence": ["known_usage"]}
    events = [event for event in facts["context_events"] if event.get("event_id")
              and event.get("recovery") in ("SUMMARY", "REFERENCES")
              and event.get("covered_count", 0) > 0
              and event.get("sequence", facts["final_sequence"]) < facts["final_sequence"]]
    if not events:
        return {"status": "NOT_RUN", "checks": {"context_triggered": False},
                "missing_evidence": ["adopted_context_recovery"]}
    source = facts["source_operation_id"]
    dispatches = facts["source_dispatches"]
    first_recovery = min(item["sequence"] for item in events)
    original_reads = [item for item in facts["reference_reads"] if item.get("source_operation_id") == source
                      and 0 <= item.get("sequence", -1) < facts["final_sequence"]
                      and item.get("next_offset", 0) > item.get("offset", 0) >= 0]
    reads = [item for item in original_reads
             if max(first_recovery, facts["constraint_sequence"]) < item["sequence"]
             and ("find" not in item or (isinstance(item["find"], str) and bool(item["find"])
                  and item.get("match_found") is True and item.get("content", "").startswith(item["find"])))]
    # 纠正后仍须真正回读；已从同一原文读到的其他目标不要求重复读。
    recovered_text = "\n".join(item.get("content", "") for item in original_reads)
    answer = facts["answer"]
    if isinstance(answer, dict) and isinstance(answer.get("合计"), dict) and set(answer["合计"]) == {"人数"}:
        answer = {**answer, "合计": answer["合计"]["人数"]}
    checks = {"context_triggered": True, "latest_scope_and_values": answer == fixture["expected"],
              "exact_material": facts["material_sha256"] == fixture["sha256"],
              "single_original_dispatch": len(dispatches) == 1 and dispatches[0]["operation_id"] == source,
              "reference_read_after_recovery_and_correction": bool(reads),
              "required_original_values_read": all(
                  f"{name}；人数={value['人数']}；校验码={value['校验码']}；" in recovered_text
                  for name, value in fixture["expected"].items() if isinstance(value, dict)),
              "correction_before_answer": facts["constraint_sequence"] < facts["final_sequence"]}
    return {"status": "PASS" if all(checks.values()) else "FAILED", "checks": checks,
            "missing_evidence": []}
