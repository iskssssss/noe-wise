"""Product 套件固定过程契约；生成数据不能修改工具、步骤和硬断言。"""
from __future__ import annotations

from behavior_contract import digest, require, VERSION, PRODUCT_SUITE_VERSION
from behavior_dataset import CATEGORY_IDS, TASKS, validate_candidate, candidate_expected


PRODUCT_TOOL_SCHEMA_VERSION = "product-capabilities-2"


def make_product_plan(batch, dataset):
    cases = []
    for candidate in dataset["selected"]:
        cid = candidate["category"]
        cases.append({"id": cid, "name": TASKS[cid][0], "profile": cid,
                      "tool_schema_version": PRODUCT_TOOL_SCHEMA_VERSION, "turns": candidate["user_turns"],
                      "candidate": candidate, "expected": candidate_expected(candidate),
                      "assertions": [{"id": "objective_business_and_process", "kind": "terminal", "evaluator": "code",
                                      "blocking": True, "selector": {}, "expected": ["cancelled" if cid == "D12" else "completed"],
                                      "options": {}, "evidence_requirements": ["trace", "terminal"]}]})
    plan = {"schema_version": VERSION, "format": "skillforge-behavior-plan", "suite_version": PRODUCT_SUITE_VERSION,
            "batch": batch, "repetitions": 1, "settlement_seconds": 30,
            "dataset_fingerprint": dataset["fingerprint"], "dataset_version": dataset["dataset_version"],
            "profiles": [{"key": cid, "capabilities": TASKS[cid][1]} for cid in CATEGORY_IDS], "cases": cases,
            "local_selection": {"case_ids": list(CATEGORY_IDS), "repetitions": 1, "workers": 1, "turn_timeout_seconds": 540}}
    plan["fingerprint"] = digest(plan)
    return validate_product_plan(plan)


def validate_product_plan(plan):
    require(set(plan) == {"schema_version", "format", "suite_version", "batch", "repetitions", "settlement_seconds",
                          "dataset_fingerprint", "dataset_version", "profiles", "cases", "local_selection", "fingerprint"}, "invalid_product_plan_fields")
    require(plan["repetitions"] == 1 and plan["settlement_seconds"] == 30, "invalid_product_defaults")
    require([c["id"] for c in plan["cases"]] == list(CATEGORY_IDS), "product_category_gap")
    for case in plan["cases"]:
        require(set(case) == {"id", "name", "profile", "tool_schema_version", "turns", "candidate", "expected", "assertions"}, "invalid_product_case_fields")
        require(case["tool_schema_version"] == PRODUCT_TOOL_SCHEMA_VERSION,
                "unsupported_product_tool_schema_use_new_batch")
        validate_candidate(case["candidate"])
        require(case["candidate"]["category"] == case["id"] and case["turns"] == case["candidate"]["user_turns"], "product_candidate_identity_mismatch")
        require(case["expected"] == candidate_expected(case["candidate"]), "product_oracle_drift")
    selection = plan["local_selection"]
    require(set(selection) == {"case_ids", "repetitions", "workers", "turn_timeout_seconds"}
            and selection["workers"] == 1 and type(selection["repetitions"]) is int
            and selection["repetitions"] in (1, 3), "product_requires_serial_single_or_repeat")
    require(isinstance(selection["case_ids"], list) and selection["case_ids"]
            and len(set(selection["case_ids"])) == len(selection["case_ids"])
            and set(selection["case_ids"]) <= set(CATEGORY_IDS), "invalid_product_selection")
    require(type(selection["turn_timeout_seconds"]) is int and 0 < selection["turn_timeout_seconds"] <= 540, "invalid_product_timeout")
    require(plan["fingerprint"] == digest({k: v for k, v in plan.items() if k != "fingerprint"}), "plan_fingerprint_mismatch")
    return plan


def task_message(case, index, resource):
    """把模型生成的用户表达与本地可信场景要求合并，不解释生成内容为命令。"""
    cid, expected = case["id"], case["expected"]
    text = case["turns"][index]
    requirements = {
        "D01": "根据规则比较普通与加急方案，明确预算、截止日期、起订量及折扣例外，不调用工具。",
        "D02": ("先询问未指定的数量，不擅自决定。" if index == 0 else
                f"本轮数量以 {case['candidate']['variant'] + (20 if index == 1 else 30)} 件为准，采用本轮最新条件。"),
        "D03": "实际执行代码分析 orders CSV，报告原始总额、按id去重总额及重复id，金额使用十进制数字。",
        "D04": "实际检索挂载知识库，引用来源并说明预算、截止日期、起订量及加急例外；国际订单运费没有证据时明确说明。",
        "D05": "实际读取 handbook 全文，提取预算、截止日期、起订量以及文末最新数量，注明位置。",
        "D06": "改写规则为面向业务用户的简洁通知，保留预算、日期、起订量及加急例外。",
        "D07": "先激活资料收集 Skill 保存业务规则。" if index == 0 else "切换总结 Skill，沿用上一轮已收集事实报告预算、日期、起订量。",
        "D08": "使用当前会话已上传的 orders.csv 与 ledger.csv，通过代码读取两个真实附件；orders按id去重，指出C记录差额，报告去重总额。",
        "D09": f"实际执行代码{'更新' if index else '生成'}可下载的 business-report.txt；文件包含标记 {expected['report_marker']}、预算 {expected['budget']}、日期 {expected['deadline']}、数量 {expected['latest_quantity'] if index else 20 + case['candidate']['variant']}。返回本轮产物引用。",
        "D10": "调用挂载的人工审批 Workflow，必须等待确认再完成。" if index == 0 else "确认执行，继续原流程。",
        "D11": "将规则核对和订单金额计算分别委派给两个子Agent，等待两者完成后汇总预算、日期、去重总额。",
        "D12": "执行可取消的长任务：先用代码计算订单总额，再分多轮代码核对不同记录，每轮报告公开进度；收到取消立即停止。",
    }
    parts = [text, "场景约束：" + requirements[cid]]
    if index == 0:
        # 检索与长资料场景不能把答案直接放进消息，必须通过资源引用读取。
        refs = {"D01": ["rules"], "D02": ["rules"], "D03": ["orders"], "D06": ["rules"],
                "D07": ["rules"], "D09": ["rules"], "D11": ["rules", "orders"], "D12": ["orders"]}.get(cid, [])
        parts += [m["ref"] + "：\n" + m["content"] for m in case["candidate"]["materials"] if m["ref"] in refs]
        if resource.get("instruction"):
            parts.append(resource["instruction"])
    return "\n\n".join(parts)
