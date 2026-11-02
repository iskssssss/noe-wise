"""业务事实和真实执行分开判定；证据缺失保留 unknown。"""
from __future__ import annotations

import json
import re

from behavior_contract import issue
from runner import successful_skill_activations

EVALUATOR_VERSION = "product-code-5"


def _missing_fact_disclosed(text, subject):
    """只接受绑定缺失事实的明确声明；无法可靠解析的表述交人工，不能猜测通过。"""
    if not subject:
        return None
    normalized = text.replace("*", "").replace("`", "")
    # 运费的省略主语、后续改口也要检查，避免一句否定掩盖另一句编造。
    sentences = re.split(r"[。！？!?\n；;]", normalized)
    relevant = [s.strip(" -\t") for s in sentences
                if subject in s or "运费" in s or "该费用" in s]
    if not relevant:
        return None
    accepted = False
    # 省略主题的收费结论无法归属时仍阻断通过，不把它默认为其他费用。
    uncertain = any(re.search(r"(?:按|收取|收费|费用为).{0,12}\d+(?:\.\d+)?\s*元|免费|包邮", s)
                    for s in sentences if s.strip(" -\t") not in relevant)
    topic = re.escape(subject)
    absence = r"(?:无(?:任何)?(?:规定|证据支持|证据)|没有(?:任何)?(?:规定|证据)|未(?:明确)?(?:规定|说明|提供)|缺乏证据|暂无资料|无法确定)"
    suffix = r"(?:[，,](?:不得编造|不予推定|不能推定|需进一步确认))*"
    pattern = rf"(?:\d+[.、]\s*)?{topic}[：:\s]*{absence}{suffix}"
    for sentence in relevant:
        # 明确给出运费金额或免费结论时阻断；双重否定等不做语义猜测。
        if re.search(r"(?:运费|该费用).{0,24}(?:[0-9０-９]+(?:\.[0-9]+)?\s*(?:元|美元|人民币)|免费|包邮)", sentence):
            return False
        if re.fullmatch(pattern, sentence):
            accepted = True
        else:
            uncertain = True
    return True if accepted and not uncertain else None


def assertion(identity, condition, refs=(), reason=None, blocking=True):
    status = "unknown" if condition is None else ("pass" if condition else "fail")
    return {"id": identity, "evaluator": "code", "blocking": blocking, "status": status,
            "reason": reason or ("evidence_missing" if condition is None else "matched" if condition else "mismatch"),
            "evidence": [ref if isinstance(ref, dict) else {"event_id": ref} for ref in refs if ref]}


def _number(text, value):
    return bool(re.search(r"(?<![\d.])" + re.escape(str(value)) + r"(?:\.0+)?(?![\d.])", text.replace(",", "")))


def _labeled_number(text, labels, value):
    normalized = text.replace(",", "").replace("*", "").replace("`", "")
    number = re.escape(str(value)) + r"(?:\.0+)?"
    prose = "\n".join(line for line in normalized.splitlines() if not line.strip().startswith("|"))
    if re.search("(?:" + labels + r")[^\d\n]{0,16}" + number + r"(?![\d.])", prose):
        return True
    # 表格中的计算式与结果分列；只采用明确金额列，不能从算式任意挑中一个数字。
    amount_columns = []
    for line in normalized.splitlines():
        if not line.strip().startswith("|"):
            amount_columns = []
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        headers = [i for i, cell in enumerate(cells) if cell in {"金额", "总额", "合计", "结果"}]
        if headers:
            amount_columns = headers
            continue
        if any(re.search(labels, cell) for cell in cells):
            if any(i < len(cells) and re.fullmatch(number + r"\s*(?:元|CNY|人民币)?", cells[i])
                   for i in amount_columns):
                return True
    return False


def evaluate_product(case, evidence):
    cid, expected = case["id"], case["expected"]
    events = evidence.get("events", [])
    complete = evidence.get("completeness", {}).get("trace", False)
    terminal = evidence.get("terminal", {})
    final = evidence.get("answer", "")
    final = final if isinstance(final, str) else ""
    final_ref = evidence.get("answer_event_id")
    checks = [assertion("terminal", (terminal.get("status") == ("cancelled" if cid == "D12" else "completed"))
                        if terminal else None, [terminal.get("runtime_event_id")]),
              assertion("execution_evidence", True if complete else None, [e.get("event_id") for e in events])]
    if cid != "D12":
        checks.append(assertion("final_answer_bound_to_run", bool(final and final_ref) if complete else None, [final_ref]))
    tools = evidence.get("tools", [])
    def tool(name):
        matches = [t for t in tools if t.get("name") == name and t.get("status") == "SUCCEEDED"]
        checks.append(assertion("executed_" + name, bool(matches) if complete else None, [t.get("event_id") for t in matches]))
        return matches
    if cid in {"D01", "D02", "D06"}:
        checks.append(assertion("no_irrelevant_tool", not tools if complete else None, [final_ref]))
    if cid in {"D01", "D04", "D05", "D06", "D07", "D11"}:
        checks.append(assertion("budget_and_date", _number(final, expected["budget"]) and expected["deadline"] in final
                                if final_ref else None, [final_ref]))
    if cid in {"D01", "D04", "D05", "D06"}:
        checks.append(assertion("minimum_quantity", _number(final, expected["minimum"]) if final_ref else None, [final_ref]))
    if cid in {"D01", "D04", "D06"}:
        checks.append(assertion("urgent_order_exception", bool(re.search(r"加急.{0,25}(?:不|无|取消).{0,12}(?:折扣|优惠)", final))
                                if final_ref else None, [final_ref]))
    if cid == "D02":
        first = evidence.get("turns", [{}])[0]
        question = (first.get("pending_hitl") or {}).get("question", "") or first.get("answer", "")
        checks += [assertion("requested_clarification", bool(re.search(r"数量|多少|几件", question)) if question else None,
                             [first.get("answer_event_id")]),
                   assertion("latest_requirement", _number(final, expected["latest_quantity"]) if final_ref else None, [final_ref])]
    if cid in {"D03", "D08", "D09", "D12"}:
        tool("code_execute")
    if cid == "D03":
        checks += [assertion("raw_total", _labeled_number(final, "原始总额|原始合计|未去重总额", expected["raw_total"]) if final_ref else None, [final_ref]),
                   assertion("unique_total", _labeled_number(final, "去重总额|去重后总额|去重后合计", expected["unique_total"]) if final_ref else None, [final_ref]),
                   assertion("duplicate_record", bool(re.search(r"(?:重复.{0,20}B|B.{0,20}重复)", final)) if final_ref else None, [final_ref])]
        outputs = [t for t in tools if t.get("name") == "code_execute" and t.get("status") == "SUCCEEDED" and t.get("result")]
        checks.append(assertion("computed_totals_in_tool_result", any(_number(json.dumps(t["result"], ensure_ascii=False), expected["raw_total"])
                                and _number(json.dumps(t["result"], ensure_ascii=False), expected["unique_total"]) for t in outputs)
                                if outputs else None, [t.get("event_id") for t in outputs]))
    if cid == "D04":
        searches = tool("knowledge_search")
        citations = evidence.get("citations", [])
        checks += [assertion("retrieved_source_cited", all(c.get("source_verified") is True for c in citations) if citations else None,
                             [c.get("event_id") for c in citations]),
                   assertion("missing_fact_not_invented", _missing_fact_disclosed(final, expected.get("missing_fact"))
                             if final_ref else None, [final_ref])]
    if cid == "D05":
        tool("read_context_reference")
        reads = evidence.get("source_reads", [])
        wanted = {"handbook"}
        checks.append(assertion("required_source_reads", wanted <= {r.get("ref") for r in reads if r.get("verified") is True}
                                if reads else None, [r.get("event_id") for r in reads]))
    if cid == "D05":
        checks.append(assertion("long_document_tail_fact", _number(final, expected["latest_quantity"]) if final_ref else None, [final_ref]))
    if cid == "D07":
        activations = successful_skill_activations(events)
        checks.append(assertion("two_skill_activations", len(activations) >= 2 if complete else None,
                                [e.get("event_id") for e in events if e.get("event_type") == "skill.activation"]))
        isolation = evidence.get("isolation")
        checks.append(assertion("other_user_isolated", isolation.get("verified") if isolation else None,
                                (isolation or {}).get("refs", [])))
    if cid in {"D08", "D11"}:
        checks.append(assertion("unique_total", _number(final, expected["unique_total"]) if final_ref else None, [final_ref]))
    if cid == "D08":
        checks.append(assertion("reconciliation_difference", _number(final, expected["difference"]) if final_ref else None, [final_ref]))
        attachments = evidence.get("attachment_inputs", [])
        verified = [row for row in attachments if isinstance(row, dict) and row.get("verified") is True]
        hash_condition = (None if not attachments or all(isinstance(row, dict) and row.get("verified") is None
                                                          for row in attachments)
                          else ({row.get("ref") for row in verified} == {"orders", "ledger"}
                                and len(verified) == len(attachments) == 2
                                and all(isinstance(row.get("sha256"), str)
                                        and re.fullmatch(r"[0-9a-f]{64}", row["sha256"])
                                        for row in verified)))
        hash_refs = [{"event_id": row.get("event_id"), "ref": row.get("ref"),
                      "sha256": row.get("sha256")} for row in attachments if isinstance(row, dict)]
        checks.append(assertion("required_attachment_hashes", hash_condition, hash_refs))
        checks.append(assertion("attachment_calculation_result",
                                evidence.get("attachment_calculation_verified"),
                                [evidence.get("attachment_calculation_event_id")]))
    if cid == "D09":
        artifacts = evidence.get("artifacts", [])
        checks.append(assertion("two_downloaded_artifact_versions", len(artifacts) == 2 and all(a.get("valid") is True for a in artifacts)
                                if artifacts else None, [a.get("id") for a in artifacts]))
        checks.append(assertion("updated_artifact_reference", evidence.get("updated_artifact_reference"), [final_ref]))
        checks.append(assertion("updated_from_original_artifact", evidence.get("updated_from_original"), [a.get("id") for a in artifacts]))
    if cid == "D10":
        turns = evidence.get("turns", [])
        checks.append(assertion("suspend_and_resume_same_run", len(turns) == 2 and turns[0]["terminal"].get("status") == "waiting_hitl"
                                and turns[0]["terminal"].get("run_id") == turns[1]["terminal"].get("run_id") if len(turns) == 2 else None,
                                [t.get("terminal", {}).get("runtime_event_id") for t in turns]))
        checks.append(assertion("workflow_no_duplicate_effect", evidence.get("workflow_effect_once"), evidence.get("workflow_refs", [])))
    if cid == "D11":
        children = evidence.get("delegation", {})
        checks += [assertion("two_owned_child_results", children.get("two_owned_results"), children.get("refs", [])),
                   assertion("parent_waits_for_children", children.get("parent_waited"), children.get("refs", []))]
    if cid == "D12":
        cancellation = evidence.get("cancellation", {})
        checks += [assertion("cancel_persisted", cancellation.get("cancel_persisted"), [cancellation.get("cancel_event_id")]),
                   assertion("no_dispatch_after_cancel", cancellation.get("post_cancel_dispatches") == 0
                             if "post_cancel_dispatches" in cancellation else None, [cancellation.get("cancel_event_id")]),
                   assertion("no_reexecution_after_reconnect", evidence.get("no_reexecution_after_reconnect"), [terminal.get("runtime_event_id")])]
    if cid in {"D09", "D10", "D11", "D12"}:
        from behavior_product_process import validate_live_case_evidence
        live = validate_live_case_evidence(evidence.get("browser_bundle"), cid)
        for row in live["assertions"]:
            checks.append({"id": "browser_" + row["id"], "evaluator": "code", "blocking": True,
                           "status": {"passed": "pass", "failed": "fail", "unknown": "unknown"}[row["status"]],
                           "reason": row["reason"], "evidence": row["references"]})
    checks.append(assertion("public_privacy", evidence.get("privacy_verified"), evidence.get("public_refs", [])))
    reasons = [issue("failed_behavior" if a["status"] == "fail" else "incomplete", a["id"] + ":" + a["reason"])
               for a in checks if a["blocking"] and a["status"] != "pass"]
    status = "failed" if any(a["status"] == "fail" for a in checks) else "unknown" if reasons else "passed"
    return {"case_id": cid, "repetition": evidence.get("repetition", 1), "behavior_status": status,
            "blocking_reasons": reasons, "assertions": checks,
            "metrics": {"business_status": status, "process_status": "pending" if any(a["status"] == "unknown" for a in checks) else status,
                        "human_quality_status": "pending", "candidate_id": case["candidate"]["candidate_id"],
                        "usage": evidence.get("metrics", {}).get("usage", {})}}


def manual_queue(plan, results):
    selected = {"D01", "D04", "D06", "D11"} | {r["case_id"] for r in results if r["behavior_status"] != "passed"}
    return [{"case_id": c["id"], "candidate_id": c["candidate"]["candidate_id"], "status": "pending",
             "reference_answer_draft": c["candidate"]["reference_answer"], "reference_is_gold": False,
             "rubric": {k: {"score": None, "range": [0, 2]} for k in ("完整性", "清晰度", "实用性", "限制说明")}}
            for c in plan["cases"] if c["id"] in selected]
