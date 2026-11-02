"""合成候选的本地契约、独立预期和不可覆盖的冻结版本。"""
from __future__ import annotations

import csv
from copy import deepcopy
from difflib import SequenceMatcher
import hashlib
from io import StringIO
import json
from pathlib import Path
import re
import shutil
import unicodedata

from behavior_contract import digest, require
from runner import read, save

CATEGORY_IDS = tuple(f"D{i:02d}" for i in range(1, 13))
PROMPT_VERSION = "product-generator-3"
SUITE_VERSION = "product-tasks-1"
DERIVATION_FORMAT = "skillforge-product-dataset-derivation"
LONG_RECONCILIATION = "long-reconciliation-1"
BUSINESS_REQUEST_REPAIR = "direct-business-request-1"
TASKS = {
    "D01": ("阅读业务规则并比较方案", [], 1),
    "D02": ("需求澄清与用户改口", [], 3),
    "D03": ("CSV 分析与异常识别", ["code_execute"], 1),
    "D04": ("知识库问答与引用", ["knowledge_search"], 1),
    "D05": ("长文档信息提取", ["code_execute", "read_context_reference"], 1),
    "D06": ("材料改写与摘要", [], 1),
    "D07": ("资料收集与总结 Skill 切换", ["activate_skill"], 2),
    "D08": ("两个数据源核对与汇总", ["code_execute", "read_context_reference"], 1),
    "D09": ("文件报告生成与修改", ["code_execute", "list_artifacts"], 2),
    "D10": ("Workflow 人工确认与续接", ["workflow"], 2),
    "D11": ("两个子任务委派与父任务汇总", ["delegation"], 1),
    "D12": ("长任务刷新、断流与取消", ["code_execute", "cancel"], 1),
}
BUSINESSES = ("采购", "客户服务", "活动运营", "库存管理", "项目交付")
INPUTS = ("自然文本", "表格", "引用资料", "长文档", "文件")
INTERACTIONS = ("单轮", "澄清", "改口", "续接", "取消")
ANOMALIES = ("无", "缺失证据", "重复记录", "条件例外", "输入变化")
REQUIRED_MATERIALS = {"D01": ["rules"], "D02": ["rules"], "D03": ["orders"], "D04": ["rules"],
                      "D05": ["handbook"], "D06": ["rules"], "D07": ["rules"], "D08": ["orders", "ledger"],
                      "D09": ["rules"], "D10": ["rules"], "D11": ["rules", "orders"], "D12": ["orders"]}


def source_fixture(category, variant, transform_version=None):
    """业务原始数值由夹具给定，模型只能扩展表达，不能修改可执行条件。"""
    require(category in TASKS and type(variant) is int and 1 <= variant <= 5, "invalid_source_identity")
    require(transform_version is None or (category == "D08" and transform_version == LONG_RECONCILIATION),
            "invalid_source_transform")
    base = 100 * variant
    rows = [{"id": "A", "amount": base}, {"id": "B", "amount": base + 20},
            {"id": "B", "amount": base + 20}, {"id": "C", "amount": base + 50}]
    ledger_rows = [{"id": "A", "amount": base}, {"id": "B", "amount": base + 20},
                   {"id": "C", "amount": base + 40}]
    if transform_version == LONG_RECONCILIATION:
        additions = [{"id": f"TX{i:04d}", "amount": base + i} for i in range(1, 401)]
        rows += additions
        ledger_rows += deepcopy(additions)
    facts = {"business": BUSINESSES[variant - 1], "budget": base + 500,
             "deadline": f"2026-11-{10 + variant:02d}", "minimum": 10 + variant,
             "exception": "加急订单不享受折扣", "discount_percent": 5,
             "initial_quantity": 20 + variant, "latest_quantity": 30 + variant,
             "rows": rows, "missing_fact": "国际订单运费"}
    rules = (f"合成{facts['business']}资料（不对应真实客户）。预算{facts['budget']}元；截止日期{facts['deadline']}；"
             f"起订量{facts['minimum']}件；普通订单优惠{facts['discount_percent']}%；{facts['exception']}。"
             "方案甲为普通订单，方案乙为加急订单。资料未规定国际订单运费，不得编造。")
    orders_csv = "id,amount\n" + "\n".join(f"{r['id']},{r['amount']}" for r in rows)
    ledger_csv = "id,amount\n" + "\n".join(f"{r['id']},{r['amount']}" for r in ledger_rows)
    if transform_version == LONG_RECONCILIATION:
        require(len(orders_csv) > 4096 and len(ledger_csv) > 4096, "derived_csv_not_long")
    long_doc = rules + "\n" + "\n".join(f"第{i}节：本节为合成业务背景，不变更订单规则。" for i in range(1, 121))
    long_doc += f"\n补充条款：原数量{facts['initial_quantity']}件，最新确认数量{facts['latest_quantity']}件。"
    materials = [{"ref": "rules", "kind": "text", "content": rules},
                 {"ref": "orders", "kind": "csv", "content": orders_csv},
                 {"ref": "ledger", "kind": "csv", "content": ledger_csv},
                 {"ref": "handbook", "kind": "text", "content": long_doc}]
    return {"category": category, "variant": variant, "facts": facts, "materials": materials}


def independent_expected(category, variant, transform_version=None):
    source = source_fixture(category, variant, transform_version)
    facts = source["facts"]
    orders = _csv_material_rows(source, "orders")
    ledger = _csv_material_rows(source, "ledger")
    unique = {row["id"]: row["amount"] for row in orders}
    ledger_unique = {row["id"]: row["amount"] for row in ledger}
    counts = {row["id"]: 0 for row in orders}
    for row in orders:
        counts[row["id"]] += 1
    return {"source_digest": digest(source), "budget": facts["budget"], "deadline": facts["deadline"],
            "minimum": facts["minimum"], "exception": facts["exception"],
            "latest_quantity": facts["latest_quantity"], "raw_total": sum(r["amount"] for r in orders),
            "unique_total": sum(unique.values()),
            "duplicate_ids": sorted(identity for identity, count in counts.items() if count > 1),
            "difference": sum(unique.values()) - sum(ledger_unique.values()),
            "missing_fact": facts["missing_fact"], "report_name": "business-report.txt",
            "report_marker": f"SF-{category}-V{variant}", "reference_is_gold": False}


def _csv_material_rows(source, ref):
    material = next((item for item in source["materials"] if item["ref"] == ref), None)
    require(material is not None and material.get("kind") == "csv", "source_csv_missing")
    reader = csv.DictReader(StringIO(material["content"]))
    require(reader.fieldnames == ["id", "amount"], "source_csv_header_invalid")
    rows = []
    for row in reader:
        require(isinstance(row.get("id"), str) and bool(row["id"]) and re.fullmatch(r"\d+", row.get("amount", "")),
                "source_csv_row_invalid")
        rows.append({"id": row["id"], "amount": int(row["amount"])})
    require(rows, "source_csv_empty")
    return rows


def candidate_expected(candidate):
    """候选自己的派生版本决定本地预期，原生成 provenance 不参与改写。"""
    transform = candidate.get("derivation", {}).get("transform_version")
    if transform == BUSINESS_REQUEST_REPAIR:
        transform = None
    return independent_expected(candidate["category"], candidate["variant"], transform)


def generation_payload(category):
    name, capabilities, turns = TASKS[category]
    sources = [source_fixture(category, i) for i in range(1, 6)]
    for source in sources:
        source["materials"] = [m for m in source["materials"] if m["ref"] in REQUIRED_MATERIALS[category]]
        for material in source["materials"]:
            if material["ref"] == "handbook":
                material["content"] = material["content"].split("\n")[0] + "\n[中间120节无语义背景；完整资料由本地夹具注入]\n" + material["content"].split("\n")[-1]
    return {"prompt_version": PROMPT_VERSION, "category": category, "task": name,
            "supported_capabilities": capabilities, "count": 5, "turn_count": turns,
            "sources": sources,
            "constraints": ["你负责生成候选，但候选中的用户是在办理业务：直接请求分析、核对、改写或交付，不得要求助手生成测试任务、测试用例或提示词。",
                            "goal 写业务目标；user_turns 写可直接执行的业务请求；不输出代码、API、资源ID、执行步骤或断言。",
                            "不要输出 materials；本地按 variant 注入原始合成资料，material_refs 只能引用这些资料。",
                            "不得改变资料中的数字、日期、规则和例外；参考答案仅供人工辅助，不是金标准。",
                            "每个 variant 生成一个候选；user_turns 为用户消息字符串数组；tags每项从允许值中选一个字符串。",
                            "D02第一轮不提供数量并请求先澄清，第二轮提供原数量，第三轮改成最新数量。",
                            "D09第二轮修改报告为最新数量；D10第二轮为人工确认；D12任务允许中途取消。",
                            "D11要求核对规则和汇总订单已列金额，分别委派后汇总；按订单id去重，不要求分配普通/加急方案或计算折后应付款。"],
            "response_schema": {"type": "object", "required": ["candidates"], "additionalProperties": False,
                "properties": {"candidates": {"type": "array", "minItems": 5, "maxItems": 5, "items": {
                    "type": "object", "additionalProperties": False,
                    "required": ["category", "variant", "goal", "user_turns", "material_refs", "required_capabilities", "tags", "reference_answer"],
                    "properties": {"category": {"const": category}, "variant": {"type": "integer", "minimum": 1, "maximum": 5},
                        "goal": {"type": "string", "description": "具体业务目标，不能输出字段说明或占位词"},
                        "user_turns": {"type": "array", "minItems": turns, "maxItems": turns,
                                       "items": {"type": "string", "description": "可直接发送给真实业务助手的完整用户请求，逐轮推进，不泄露后续改口"}},
                        "material_refs": {"const": REQUIRED_MATERIALS[category]}, "required_capabilities": {"const": capabilities},
                        "tags": {"type": "object", "additionalProperties": False, "required": ["business", "input", "interaction", "anomaly"],
                                 "properties": {k: {"type": "string", "enum": list(v)} for k, v in
                                                (("business", BUSINESSES), ("input", INPUTS), ("interaction", INTERACTIONS), ("anomaly", ANOMALIES))}},
                        "reference_answer": {"type": "string", "description": "基于给定资料的参考草稿，仅供人工评审"}}}}}}}


def _canonical(value):
    if isinstance(value, str):
        return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value)).strip()
    if isinstance(value, list):
        return [_canonical(v) for v in value]
    if isinstance(value, dict):
        return {k: _canonical(v) for k, v in value.items()}
    return value


def validate_candidate(candidate, derivation_receipt=None):
    required = {"candidate_id", "category", "variant", "goal", "user_turns", "materials", "material_refs",
                "required_capabilities", "tags", "reference_answer", "provenance"}
    require(isinstance(candidate, dict)
            and (set(candidate) == required or set(candidate) == required | {"derivation"}),
            "invalid_candidate_structure")
    category, variant = candidate["category"], candidate["variant"]
    derivation = candidate.get("derivation")
    transform = None
    if derivation is not None:
        require(category in {"D06", "D08", "D11"} and isinstance(derivation, dict)
                and set(derivation) == {"transform_version", "parent_candidate_digest",
                                        "parent_dataset_fingerprint", "source_digest"},
                "invalid_candidate_derivation")
        require(derivation.get("transform_version") == (
                    LONG_RECONCILIATION if category == "D08" else BUSINESS_REQUEST_REPAIR)
                and all(isinstance(derivation.get(key), str)
                        and re.fullmatch(r"[0-9a-f]{64}", derivation[key])
                        for key in ("parent_candidate_digest", "parent_dataset_fingerprint", "source_digest")),
                "invalid_candidate_derivation")
        transform = derivation["transform_version"] if category == "D08" else None
    source = source_fixture(category, variant, transform)
    require(isinstance(candidate["candidate_id"], str) and re.fullmatch(category + r"-[a-zA-Z0-9-]+", candidate["candidate_id"]), "invalid_candidate_id")
    if derivation is not None:
        require(candidate["candidate_id"].endswith("-" + derivation["transform_version"]), "invalid_candidate_derivation")
        require(derivation["source_digest"] == digest(source), "derived_source_drift")
    require(candidate["materials"] == source["materials"], "contradictory_or_modified_source")
    require(candidate["required_capabilities"] == TASKS[category][1], "unsupported_or_missing_capability")
    refs = candidate["material_refs"]
    require(isinstance(refs, list) and refs and all(isinstance(r, str) and r in {m["ref"] for m in source["materials"]} for r in refs), "invalid_material_reference")
    require(refs == REQUIRED_MATERIALS[category], "required_materials_missing")
    turns = candidate["user_turns"]
    require(isinstance(turns, list) and len(turns) == TASKS[category][2] and
            all(isinstance(t, str) and 1 <= len(t) <= 6000 for t in turns), "invalid_executable_turns")
    require(not any(t.strip() in {"用户请求", "用户输入", "任务目标", "用户消息", "string"} for t in turns), "template_placeholder_not_business_input")
    require(all(isinstance(candidate[k], str) and 0 < len(candidate[k]) <= 10000 for k in ("goal", "reference_answer")), "invalid_candidate_text")
    text = candidate["goal"] + "\n" + "\n".join(turns)
    # 拦截生成器把自己的工作复述为用户任务；不能靠执行时附加约束掩盖冲突。
    # 这是明确元任务表达的静态筛选，不代替业务语义的人工抽评。
    require(not re.search(
        r"(?:生成|设计|编写|构造|创建|产出)[^。！？\n；;]{0,40}"
        r"(?:测试(?:任务|用例|案例)|测试资料任务表达|评测(?:任务|用例)|提示词)"
        r"|\b(?:generate|design|create|write)\s+(?:\w+\s+){0,5}"
        r"(?:test\s+(?:cases?|tasks?)|evaluation\s+tasks?|prompts?)\b",
        unicodedata.normalize("NFKC", text), re.I), "meta_task_not_business_input")
    require(not re.search(r"```|https?://|\b(?:curl|wget|eval|exec)\s*[( ]|(?:断言|通过标准|expected)\s*[:=]", text, re.I), "executable_or_evaluator_instruction")
    # 自由文本不能引入未提供的新数字、日期或资源句柄。
    allowed_numbers = set(re.findall(r"\d+", json.dumps(source, ensure_ascii=False))) | {"0", "1", "2", "3", "5", "10", "100", "120"}
    require(set(re.findall(r"\d+", text)) <= allowed_numbers, "contradictory_numeric_condition")
    for pattern, key in ((r"预算(?:为|是|上限|[:： ])*(\d+)", "budget"),
                         (r"起订(?:量)?(?:为|是|[:： ])*(\d+)", "minimum")):
        require(all(int(v) == source["facts"][key] for v in re.findall(pattern, text)), "contradictory_labeled_condition")
    require(not re.search(r"加急订单(?:也|同样|仍然|可以|可)?享受(?:折扣|优惠)", text), "contradictory_rule_exception")
    tags = candidate["tags"]
    require(isinstance(tags, dict) and set(tags) == {"business", "input", "interaction", "anomaly"}, "invalid_coverage_tags")
    for key, choices in (("business", BUSINESSES), ("input", INPUTS), ("interaction", INTERACTIONS), ("anomaly", ANOMALIES)):
        require(isinstance(tags[key], str) and tags[key] in choices, "invalid_coverage_label")
    require(tags["business"] == source["facts"]["business"], "business_source_mismatch")
    provenance = candidate["provenance"]
    require(isinstance(provenance, dict) and set(provenance) == {"model_id", "model_config_digest", "prompt_version", "call_identity", "source_digest"}
            and all(isinstance(v, str) and bool(v) for v in provenance.values()), "invalid_generation_provenance")
    original_source = source_fixture(category, variant)
    require(provenance["prompt_version"] in {"product-generator-1", "product-generator-2", PROMPT_VERSION}
            and provenance["source_digest"] == digest(original_source), "generation_source_drift")
    if derivation_receipt is not None and derivation is not None:
        _require_candidate_parent(candidate, derivation_receipt)
    return candidate


def validate_candidates(candidates, derivation_receipt=None):
    require(isinstance(candidates, list) and len(candidates) <= 120, "invalid_candidate_pool")
    accepted, rejected, near, normalizations, ids, exact = [], [], [], [], set(), set()
    for index, candidate in enumerate(candidates):
        identity = candidate.get("candidate_id", f"invalid-{index}") if isinstance(candidate, dict) else f"invalid-{index}"
        try:
            candidate = deepcopy(candidate)
            # 单元素枚举数组与枚举标量含义相同；只做无歧义表示归一，不修补业务事实。
            tags = candidate.get("tags") if isinstance(candidate, dict) else None
            if isinstance(tags, dict):
                for key, value in tags.items():
                    if isinstance(value, list) and len(value) == 1 and isinstance(value[0], str):
                        tags[key] = value[0]
                        normalizations.append({"candidate_id": identity, "field": "tags." + key,
                                               "rule": "singleton_enum_to_scalar"})
            validate_candidate(candidate, derivation_receipt)
            require(identity not in ids, "duplicate_candidate_id")
            content = {k: v for k, v in candidate.items() if k not in {"candidate_id", "provenance", "reference_answer"}}
            fingerprint = digest(_canonical(content))
            require(fingerprint not in exact, "duplicate_candidate_content")
            for other in accepted:
                if other["category"] != candidate["category"]:
                    continue
                ratio = SequenceMatcher(None, " ".join(other["user_turns"]), " ".join(candidate["user_turns"])).ratio()
                if ratio >= .85 or (other["variant"] == candidate["variant"] and other["tags"] == candidate["tags"]):
                    near.append({"candidate_id": identity, "similar_to": other["candidate_id"], "reason": "same_facts_or_near_wording"})
            ids.add(identity)
            exact.add(fingerprint)
            accepted.append(deepcopy(candidate))
        except (ValueError, TypeError, KeyError) as exc:
            rejected.append({"candidate_id": identity, "reasons": [str(exc) if isinstance(exc, ValueError) else "invalid_candidate_type"]})
    coverage, selected = set(), []
    for category in CATEGORY_IDS:
        options = [c for c in accepted if c["category"] == category]
        if not options:
            continue
        def combinations(c):
            return {(c["tags"]["business"], c["tags"]["input"], c["tags"]["interaction"])}
        options.sort(key=lambda c: (-len(combinations(c) - coverage), c["candidate_id"]))
        selected.append(options[0]["candidate_id"])
        coverage |= combinations(options[0])
    return {"accepted": accepted, "rejected": rejected, "near_duplicates": near, "selected": selected,
            "normalizations": normalizations,
            "gaps": [cat for cat in CATEGORY_IDS if not any(c["category"] == cat for c in accepted)],
            "coverage": [list(c) for c in sorted(coverage)], "reference_answers_are_gold": False}


def _require_candidate_parent(candidate, receipt):
    if receipt.get("transform_version") == BUSINESS_REQUEST_REPAIR:
        from behavior_dataset_repair import validate_repair_candidate
        return validate_repair_candidate(candidate, receipt)
    parent_id = receipt["derived_from"].get(candidate["candidate_id"])
    parent = next((row for row in receipt["parent_candidates"]
                   if row.get("candidate_id") == parent_id), None)
    require(parent is not None and parent["category"] == "D08"
            and parent["variant"] == candidate["variant"], "derived_parent_candidate_missing")
    require(candidate["derivation"]["parent_candidate_digest"] == digest(parent)
            and candidate["derivation"]["parent_dataset_fingerprint"] == receipt["parent_dataset_fingerprint"],
            "derived_parent_candidate_mismatch")


def _validate_derivation_receipt(receipt, candidates):
    if isinstance(receipt, dict) and receipt.get("transform_version") == BUSINESS_REQUEST_REPAIR:
        from behavior_dataset_repair import validate_repair_receipt
        return validate_repair_receipt(receipt, candidates)
    required = {"schema_version", "format", "transform_version", "parent_dataset_version",
                "parent_dataset_fingerprint", "parent_dataset_file_sha256",
                "parent_candidates_file_sha256", "parent_candidates", "derived_from",
                "generation_requests"}
    require(isinstance(receipt, dict) and set(receipt) == required
            and receipt.get("schema_version") == 1 and receipt.get("format") == DERIVATION_FORMAT
            and receipt.get("transform_version") == LONG_RECONCILIATION
            and receipt.get("generation_requests") == 0
            and isinstance(receipt.get("parent_dataset_version"), str)
            and bool(receipt["parent_dataset_version"]), "invalid_dataset_derivation")
    for key in ("parent_dataset_fingerprint", "parent_dataset_file_sha256", "parent_candidates_file_sha256"):
        require(isinstance(receipt.get(key), str) and re.fullmatch(r"[0-9a-f]{64}", receipt[key]),
                "invalid_dataset_derivation")
    parents = receipt.get("parent_candidates")
    require(isinstance(parents, list) and len(parents) == 60, "invalid_dataset_derivation")
    parent_by_id = {}
    for parent in parents:
        validate_candidate(parent)
        require("derivation" not in parent and parent["candidate_id"] not in parent_by_id,
                "invalid_dataset_derivation")
        parent_by_id[parent["candidate_id"]] = parent
    require(all(sum(parent["category"] == category for parent in parents) == 5 for category in CATEGORY_IDS),
            "invalid_dataset_derivation")

    mapping = receipt.get("derived_from")
    require(isinstance(mapping, dict) and len(mapping) == 5
            and all(isinstance(key, str) and isinstance(value, str) for key, value in mapping.items()),
            "invalid_dataset_derivation")
    require(isinstance(candidates, list) and len(candidates) == len(parents), "invalid_dataset_derivation")
    targets = {candidate.get("candidate_id"): candidate for candidate in candidates if isinstance(candidate, dict)}
    require(len(targets) == len(candidates), "invalid_dataset_derivation")
    for parent_id, parent in parent_by_id.items():
        if parent["category"] != "D08":
            require(targets.get(parent_id) == parent, "derived_non_target_candidate_changed")
    derived = [candidate for candidate in candidates if candidate.get("derivation") is not None]
    require(len(derived) == 5 and set(mapping) == {candidate["candidate_id"] for candidate in derived}
            and {mapping[candidate["candidate_id"]] for candidate in derived}
                == {parent["candidate_id"] for parent in parents if parent["category"] == "D08"},
            "invalid_dataset_derivation")
    for candidate in derived:
        _require_candidate_parent(candidate, receipt)
    return receipt


def _sha256_file(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def freeze_dataset(root, version):
    root = Path(root)
    require(isinstance(version, str) and re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}", version), "invalid_dataset_version")
    target = root / "dataset.json"
    require(not target.exists(), "dataset_already_frozen_use_new_version")
    candidates = read(root / "candidates.json")
    receipt_path = root / "dataset-derivation.json"
    receipt = read(receipt_path) if receipt_path.exists() else None
    require(receipt is not None or not any(isinstance(candidate, dict) and "derivation" in candidate
                                           for candidate in candidates), "dataset_derivation_receipt_required")
    if receipt is not None:
        _validate_derivation_receipt(receipt, candidates)
    report = validate_candidates(candidates, receipt)
    save(root / "validation.json", report)
    require(not report["gaps"], "dataset_category_gaps")
    chosen = [next(c for c in report["accepted"] if c["candidate_id"] == cid) for cid in report["selected"]]
    frozen = {"schema_version": 1, "format": "skillforge-product-dataset", "dataset_version": version,
              "suite_version": SUITE_VERSION, "prompt_version": PROMPT_VERSION,
              "candidates_digest": digest(candidates), "validation_digest": digest(report),
              "selected": chosen, "expected": {c["category"]: candidate_expected(c) for c in chosen}}
    frozen["attempts_digest"] = digest(read(root / "all-attempts.json")) if (root / "all-attempts.json").exists() else None
    if receipt is not None:
        frozen["derivation_digest"] = digest(receipt)
    frozen["fingerprint"] = digest(frozen)
    # 独占创建避免两个冻结进程覆盖已存在版本。
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("x", encoding="utf-8") as stream:
        json.dump(frozen, stream, ensure_ascii=False, indent=2)
    return frozen


def load_dataset(root):
    root = Path(root)
    value = read(root / "dataset.json")
    require(value.get("format") == "skillforge-product-dataset" and value.get("schema_version") == 1
            and value.get("suite_version") == SUITE_VERSION, "invalid_dataset_format")
    require(value.get("fingerprint") == digest({k: v for k, v in value.items() if k != "fingerprint"}), "dataset_fingerprint_mismatch")
    candidates = read(root / "candidates.json")
    receipt_path = root / "dataset-derivation.json"
    receipt = read(receipt_path) if receipt_path.exists() else None
    require((receipt is None) == ("derivation_digest" not in value), "dataset_derivation_binding_missing")
    if receipt is not None:
        require(value["derivation_digest"] == digest(receipt), "dataset_derivation_changed")
        _validate_derivation_receipt(receipt, candidates)
    report = validate_candidates(candidates, receipt)
    require(value["candidates_digest"] == digest(candidates) and value["validation_digest"] == digest(report), "frozen_candidates_changed")
    attempts_digest = digest(read(root / "all-attempts.json")) if (root / "all-attempts.json").exists() else None
    require(value.get("attempts_digest") == attempts_digest, "frozen_generation_attempts_changed")
    selected = [next(c for c in report["accepted"] if c["candidate_id"] == cid) for cid in report["selected"]]
    require(value["selected"] == selected and not report["gaps"], "frozen_selection_changed")
    require(value["expected"] == {c["category"]: candidate_expected(c) for c in selected}, "frozen_oracle_changed")
    return value


def derive_dataset(parent_root, target, version):
    """从已冻结原始数据集生成唯一的 D08 长对账版本，不发起新的模型请求。"""
    parent_root, target = Path(parent_root), Path(target)
    require(parent_root.resolve() != target.resolve(), "derived_dataset_target_is_parent")
    require(not target.exists(), "derived_dataset_target_exists")
    parent_dataset = load_dataset(parent_root)
    parent_candidates = read(parent_root / "candidates.json")
    require(len(parent_candidates) == 60
            and all(sum(candidate["category"] == category for candidate in parent_candidates) == 5
                    for category in CATEGORY_IDS), "dataset_derivation_requires_complete_parent_pool")
    require("derivation_digest" not in parent_dataset
            and not (parent_root / "dataset-derivation.json").exists()
            and all("derivation" not in candidate for candidate in parent_candidates),
            "dataset_derivation_parent_already_derived")

    derived, mapping = [], {}
    for parent in parent_candidates:
        candidate = deepcopy(parent)
        if candidate["category"] == "D08":
            candidate["candidate_id"] += "-" + LONG_RECONCILIATION
            candidate["goal"] = "核对订单明细与账本明细，识别重复记录并汇总两份数据的差额。"
            candidate["user_turns"] = [
                "请读取订单和账本两份CSV，按ID核对，识别订单中的重复记录，分别计算订单原始总额、订单按ID去重总额和账本总额，并说明差额。只依据资料作答。"
            ]
            candidate["materials"] = source_fixture(
                "D08", candidate["variant"], LONG_RECONCILIATION)["materials"]
            candidate["reference_answer"] = "本地确定性派生后的参考答案未重新验证；请以两份CSV独立计算结果为准。"
            candidate["derivation"] = {
                "transform_version": LONG_RECONCILIATION,
                "parent_candidate_digest": digest(parent),
                "parent_dataset_fingerprint": parent_dataset["fingerprint"],
                "source_digest": digest(source_fixture(
                    "D08", candidate["variant"], LONG_RECONCILIATION)),
            }
            mapping[candidate["candidate_id"]] = parent["candidate_id"]
        derived.append(candidate)

    receipt = {
        "schema_version": 1,
        "format": DERIVATION_FORMAT,
        "transform_version": LONG_RECONCILIATION,
        "parent_dataset_version": parent_dataset["dataset_version"],
        "parent_dataset_fingerprint": parent_dataset["fingerprint"],
        "parent_dataset_file_sha256": _sha256_file(parent_root / "dataset.json"),
        "parent_candidates_file_sha256": _sha256_file(parent_root / "candidates.json"),
        "parent_candidates": deepcopy(parent_candidates),
        "derived_from": mapping,
        "generation_requests": 0,
    }
    _validate_derivation_receipt(receipt, derived)
    report = validate_candidates(derived, receipt)
    require(not report["rejected"] and not report["gaps"], "dataset_derivation_invalid")

    target.mkdir(parents=True, exist_ok=False)
    save(target / "candidates.json", derived)
    save(target / "dataset-derivation.json", receipt)
    for name in ("all-attempts.json", "generation-cost.json", "generation-summary.json"):
        source = parent_root / name
        if source.exists():
            shutil.copy2(source, target / name)
    return freeze_dataset(target, version)
