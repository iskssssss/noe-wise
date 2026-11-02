"""保留历史证据，仅派生 D06/D11 的直接业务请求；不调用模型。"""
from copy import deepcopy
from pathlib import Path

from behavior_contract import digest, require
from behavior_dataset import (BUSINESS_REQUEST_REPAIR, CATEGORY_IDS, DERIVATION_FORMAT,
                              candidate_expected, freeze_dataset, source_fixture,
                              validate_candidate, validate_candidates)
from runner import read, save

_TEXTS = {
    "D06": (
        "将业务规则改写成面向业务用户的简洁通知，保持事实与例外条件准确。",
        "请把 rules 资料改写为一份可直接发给业务同事的简洁通知。保留预算、截止日期、起订量、"
        "普通订单优惠和加急例外，说明资料没有规定的国际订单运费，不补充未经提供的承诺。"),
    "D11": (
        "分别委派规则核对和订单金额汇总，等待两个结果后形成业务核对结论。",
        "请让两个子助手分别核对 rules 业务规则、计算 orders 订单金额，收到两个结果后再汇总。"
        "规则核对说明预算、截止日期和适用例外；订单按 id 去重，同 id 同金额只计一次，"
        "遇到同 id 金额冲突请明确列出。这里只汇总表中已列金额，不分配普通或加急方案，"
        "不推算折后应付款。最终给出规则核对结论、去重总额及资料限制。"),
}
_FILES = ("dataset.json", "candidates.json", "validation.json", "dataset-derivation.json",
          "all-attempts.json", "generation-cost.json", "generation-summary.json")


def _parent_snapshot(receipt):
    """校验历史文件绑定，不将已被新规则拒绝的历史题目重新判为可执行。"""
    files = receipt["parent_files"]
    require(isinstance(files, dict) and set(files) <= set(_FILES)
            and {"dataset.json", "candidates.json", "validation.json"} <= set(files),
            "repair_parent_files_invalid")
    require(receipt["parent_file_digests"] == {k: digest(v) for k, v in files.items()},
            "repair_parent_files_changed")
    dataset, candidates = files["dataset.json"], files["candidates.json"]
    require(dataset["fingerprint"] == digest({k: v for k, v in dataset.items() if k != "fingerprint"})
            and dataset["fingerprint"] == receipt["parent_dataset_fingerprint"],
            "repair_parent_fingerprint_changed")
    require(dataset["candidates_digest"] == digest(candidates)
            and dataset["validation_digest"] == digest(files["validation.json"])
            and dataset.get("attempts_digest") == (
                digest(files["all-attempts.json"]) if "all-attempts.json" in files else None)
            and dataset.get("derivation_digest") == (
                digest(files["dataset-derivation.json"]) if "dataset-derivation.json" in files else None),
            "repair_parent_binding_changed")
    require(len(candidates) == 60 and len({c["candidate_id"] for c in candidates}) == 60
            and all(sum(c["category"] == cid for c in candidates) == 5 for cid in CATEGORY_IDS),
            "repair_parent_pool_invalid")
    require([c["category"] for c in dataset["selected"]] == list(CATEGORY_IDS)
            and all(c in candidates for c in dataset["selected"])
            and dataset["expected"] == {c["category"]: candidate_expected(c) for c in dataset["selected"]},
            "repair_parent_oracle_changed")
    return candidates


def _repaired(parent, fingerprint):
    row = deepcopy(parent)
    if row["category"] not in _TEXTS:
        return row
    require("derivation" not in parent, "repair_parent_already_repaired")
    row["candidate_id"] += "-" + BUSINESS_REQUEST_REPAIR
    row["goal"], turn = _TEXTS[row["category"]]
    row["user_turns"] = [turn]
    row["reference_answer"] = "本地业务请求修订后的参考草稿待人工评审；硬预期由原始资料独立计算。"
    row["derivation"] = {
        "transform_version": BUSINESS_REQUEST_REPAIR,
        "parent_candidate_digest": digest(parent),
        "parent_dataset_fingerprint": fingerprint,
        "source_digest": digest(source_fixture(row["category"], row["variant"])),
    }
    return row


def validate_repair_candidate(candidate, receipt):
    parents = receipt["parent_files"]["candidates.json"]
    matches = [p for p in parents if p["category"] == candidate["category"]
               and p["variant"] == candidate["variant"]]
    require(len(matches) == 1 and candidate == _repaired(matches[0], receipt["parent_dataset_fingerprint"]),
            "repair_candidate_changed")


def validate_repair_receipt(receipt, candidates):
    require(set(receipt) == {"schema_version", "format", "transform_version", "parent_files",
                            "parent_file_digests", "parent_dataset_fingerprint", "generation_requests",
                            "authoring_method"}
            and receipt["schema_version"] == 1 and receipt["format"] == DERIVATION_FORMAT
            and receipt["transform_version"] == BUSINESS_REQUEST_REPAIR
            and receipt["generation_requests"] == 0
            and receipt["authoring_method"] == "local_contract_rewrite_not_new_llm_generation",
            "invalid_repair_receipt")
    parents = _parent_snapshot(receipt)
    expected = [_repaired(p, receipt["parent_dataset_fingerprint"]) for p in parents]
    require(candidates == expected, "repair_candidate_changed")
    for row in candidates:
        validate_candidate(row)
    return receipt


def repair_dataset(parent_root, target, version):
    parent_root, target = Path(parent_root), Path(target)
    require(parent_root.resolve() != target.resolve(), "repair_target_is_parent")
    require(not target.exists(), "repair_target_exists")
    files = {name: read(parent_root / name) for name in _FILES if (parent_root / name).exists()}
    receipt = {
        "schema_version": 1, "format": DERIVATION_FORMAT,
        "transform_version": BUSINESS_REQUEST_REPAIR, "parent_files": files,
        "parent_file_digests": {k: digest(v) for k, v in files.items()},
        "parent_dataset_fingerprint": files["dataset.json"]["fingerprint"],
        "generation_requests": 0, "authoring_method": "local_contract_rewrite_not_new_llm_generation",
    }
    parents = _parent_snapshot(receipt)
    candidates = [_repaired(p, receipt["parent_dataset_fingerprint"]) for p in parents]
    validate_repair_receipt(receipt, candidates)
    report = validate_candidates(candidates, receipt)
    require(not report["rejected"] and not report["gaps"], "repair_candidates_invalid")
    target.mkdir(parents=True, exist_ok=False)
    save(target / "candidates.json", candidates)
    save(target / "dataset-derivation.json", receipt)
    for name in ("all-attempts.json", "generation-cost.json", "generation-summary.json"):
        if name in files:
            save(target / name, files[name])
    return freeze_dataset(target, version)
