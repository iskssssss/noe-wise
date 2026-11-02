#!/usr/bin/env python3
"""从冻结批次生成离线记忆评测报告；不连接数据库、服务或模型。"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


# 六个维度分别统计适用场景；整体分母始终是套件预先声明的全部样本。
DIMENSIONS = {
    "EXTRACTION": "提取",
    "RETRIEVAL": "检索",
    "ANSWER": "回答使用",
    "GOVERNANCE": "治理",
    "SAFETY": "安全与隔离",
    "RELIABILITY": "可靠性",
}
SCENARIO_DIMENSIONS = {
    "M01": ["EXTRACTION"], "M02": ["EXTRACTION"],
    "M03": ["EXTRACTION", "RETRIEVAL", "ANSWER"],
    "M04": ["EXTRACTION", "RETRIEVAL", "ANSWER"],
    "M05": ["EXTRACTION", "ANSWER"],
    "M06": ["EXTRACTION", "RETRIEVAL", "ANSWER"], "M07": ["ANSWER"],
    "M08": ["EXTRACTION", "RETRIEVAL", "ANSWER"],
    "M09": ["EXTRACTION", "RETRIEVAL", "ANSWER"],
    "M10": ["RETRIEVAL", "SAFETY"], "M11": ["RETRIEVAL", "SAFETY"],
    "M12": ["EXTRACTION", "RETRIEVAL", "ANSWER", "SAFETY"],
    "M13": ["RETRIEVAL", "ANSWER"], "M14": ["RETRIEVAL", "ANSWER"],
    "M15": ["RETRIEVAL", "ANSWER"],
    "M16": ["GOVERNANCE", "RETRIEVAL", "ANSWER"],
    "M17": ["GOVERNANCE", "RETRIEVAL", "ANSWER"],
    "M18": ["GOVERNANCE", "RETRIEVAL", "ANSWER"],
    "M19": ["RETRIEVAL", "SAFETY"], "M20": ["RETRIEVAL", "SAFETY"],
    "M21": ["EXTRACTION", "GOVERNANCE", "RETRIEVAL", "ANSWER", "SAFETY"],
    "M22": ["ANSWER", "SAFETY"],
    "M23": ["EXTRACTION", "RETRIEVAL", "ANSWER", "RELIABILITY"],
    "M24": ["EXTRACTION", "ANSWER", "RELIABILITY"],
}
OUTCOMES = ("PASS", "FAIL", "UNKNOWN", "NOT_RUN")
METHODS = {"DETERMINISTIC", "TEXT_HEURISTIC", "SEMANTIC_REVIEW_REQUIRED"}
REDACTED = "[已脱敏]"
SECRET_KEY = re.compile(
    r"(?:authorization|password|passwd|credential|api[_-]?key|access[_-]?token|"
    r"refresh[_-]?token|secret|private[_-]?key|cookie|headers|rawpayload|"
    r"responsecontent|requestbody|responsebody|^payload$|^raw$)", re.I)
SECRET_TEXT = [
    re.compile(r"SYNTHETIC-SECRET-[A-Za-z0-9_.-]+", re.I),
    re.compile(r"\bBearer\s+[A-Za-z0-9._~+/=-]+", re.I),
    re.compile(r"\bsk-[A-Za-z0-9_-]{8,}"),
    re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"),
    re.compile(r"(?i)(?:password|passwd|api[_-]?key|access[_-]?token|"
               r"secret)\s*[:=]\s*[\"']?[^\s\"',;，。]+"),
    re.compile(r"(?:密码|密钥|口令|令牌)(?:\s*是|\s*为|\s*[:：=])\s*[^\s，。；;]+"),
    re.compile(r"-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----", re.S),
]


class ReportError(ValueError):
    """输入证据不完整、绑定不匹配或可能覆盖历史时拒绝生成报告。"""


def sha256(path: Path) -> str:
    """按文件实际字节计算输入绑定，避免重排 JSON 后冒用原证据。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    """读取 UTF-8 JSON 对象，允许 Windows 写出的 BOM。"""
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ReportError(f"输入必须是 JSON 对象：{path.name}")
    return value


def public(value: Any) -> Any:
    """递归脱敏公开材料；内部原始载荷仅保留在原始私有证据中。"""
    if isinstance(value, dict):
        return {key: REDACTED if SECRET_KEY.search(str(key)) else public(item)
                for key, item in value.items()}
    if isinstance(value, list):
        return [public(item) for item in value]
    if isinstance(value, str):
        for pattern in SECRET_TEXT:
            value = pattern.sub(REDACTED, value)
        # URL 只公开主路径；查询和用户信息可能携带认证信息。
        value = re.sub(r"(https?://)[^\s/@]+:[^\s/@]+@", r"\1" + REDACTED + "@", value)
        value = re.sub(r"(https?://[^\s?]+)\?[^\s]+", r"\1?" + REDACTED, value)
    return value


def parse_time(value: str) -> datetime:
    """要求显式时区，防止报告新鲜度判断依赖执行宿主的本地时区。"""
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ReportError("时间必须包含时区")
    return result.astimezone(timezone.utc)


def outcome(values: list[str], absent: str = "UNKNOWN") -> str:
    """确定失败优先，缺失或未完成不能被已有通过掩盖。"""
    if not values:
        return absent
    if "FAIL" in values:
        return "FAIL"
    if "UNKNOWN" in values:
        return "UNKNOWN"
    if "NOT_RUN" in values:
        return "NOT_RUN" if all(item == "NOT_RUN" for item in values) else "UNKNOWN"
    return "PASS"


def counts(values: list[str]) -> dict[str, Any]:
    """同时给出完整分母和已执行样本分母，未知不计为通过。"""
    counter = Counter(values)
    result = {key: counter[key] for key in OUTCOMES}
    planned = len(values)
    attempted = planned - counter["NOT_RUN"]
    result.update(planned=planned, attempted=attempted,
                  passRate=counter["PASS"] / planned if planned else None,
                  attemptedPassRate=counter["PASS"] / attempted if attempted else None)
    return result


def safe_child(root: Path, relative: str) -> Path:
    """证据路径必须留在本批次内，拒绝绝对路径、上级逃逸和符号链接逃逸。"""
    path = (root / relative).resolve()
    if Path(relative).is_absolute() or not path.is_relative_to(root.resolve()):
        raise ReportError("样本路径必须位于批次目录内")
    return path


def load_batch(batch_path: Path, suite_path: Path) -> dict[str, Any]:
    """校验套件、计划分母及每个样本身份，并保留未产出样本的位置。"""
    batch, suite = load_json(batch_path), load_json(suite_path)
    if batch.get("schemaVersion") != 1 or suite.get("schemaVersion") != 1:
        raise ReportError("仅支持 schemaVersion=1")
    digest = sha256(suite_path)
    if batch.get("suiteId") != suite.get("suiteId") or batch.get("suiteHash") != digest:
        raise ReportError("套件身份或字节摘要与批次不一致")
    repeats = suite.get("repeats")
    if not isinstance(repeats, int) or isinstance(repeats, bool) or repeats <= 0:
        raise ReportError("套件 repeats 必须是正整数")
    scenarios = suite.get("scenarios", [])
    scenario_ids = [item["id"] for item in scenarios]
    if len(set(scenario_ids)) != len(scenario_ids):
        raise ReportError("场景 id 重复")
    planned = len(scenarios) * repeats
    if batch.get("plannedSamples") != planned:
        raise ReportError("批次 plannedSamples 与套件完整矩阵不一致")
    samples: dict[tuple[str, int], dict[str, Any]] = {}
    sample_ids: set[str] = set()
    for entry in batch.get("samples", []):
        if entry.get("outcome") not in OUTCOMES:
            raise ReportError("样本目录 outcome 非法")
        path = safe_child(batch_path.parent, entry["path"])
        if not path.exists():
            if entry["outcome"] != "NOT_RUN":
                raise ReportError("已执行样本缺少证据文件")
            continue
        sample = load_json(path)
        scenario = sample.get("scenario")
        scenario_id = scenario.get("id") if isinstance(scenario, dict) else scenario
        repeat = sample.get("repeat")
        key = (scenario_id, repeat)
        if (scenario_id not in scenario_ids or not isinstance(repeat, int)
                or isinstance(repeat, bool) or repeat not in range(1, repeats + 1)):
            raise ReportError("样本不属于套件矩阵")
        if sample.get("schemaVersion") != 1 or sample.get("sampleId") != entry.get("sampleId"):
            raise ReportError("样本版本或身份与批次目录不一致")
        if sample.get("outcome") not in OUTCOMES:
            raise ReportError("样本 outcome 非法")
        if key in samples or sample["sampleId"] in sample_ids:
            raise ReportError("样本身份或场景重复次数重复")
        sample_ids.add(sample["sampleId"])
        samples[key] = {"data": sample, "path": path, "hash": sha256(path),
                        "relativePath": entry["path"]}
    return {"batch": batch, "suite": suite, "samples": samples,
            "batchHash": sha256(batch_path), "suiteHash": digest}


def assertion_key(sample_id: str, assertion: dict[str, Any], index: int = 0) -> tuple[str, str, str]:
    """同编码的多阶段断言按位置区分；收据绑定样本字节，重排后不能复用。"""
    identity = hashlib.sha256((assertion["dimension"] + "\0" + assertion["code"] + "\0" + str(index)).encode("utf-8")).hexdigest()
    return sample_id, assertion["dimension"], identity


def validate_assertions(sample: dict[str, Any]) -> None:
    """拒绝未知断言类型；同编码的不同阶段检查由索引和样本摘要区分。"""
    for assertion in sample.get("assertions", []):
        if (assertion.get("dimension") not in DIMENSIONS
                or assertion.get("outcome") not in OUTCOMES[:3]
                or assertion.get("method") not in METHODS or not assertion.get("code")):
            raise ReportError("断言维度、结果、方法或编码非法")


def review_template(loaded: dict[str, Any]) -> dict[str, Any]:
    """创建明确未复核的模板；不写入虚构评分、审阅者或时间。"""
    entries = []
    for item in loaded["samples"].values():
        sample = item["data"]
        validate_assertions(sample)
        for index, assertion in enumerate(sample.get("assertions", [])):
            if assertion["method"] != "DETERMINISTIC":
                entries.append({"sampleId": sample["sampleId"], "sampleSha256": item["hash"],
                                "dimension": assertion["dimension"], "assertionCode": assertion["code"],
                                "assertionId": assertion_key(sample["sampleId"], assertion, index)[2],
                                "assertionIndex": index,
                                "outcome": "UNKNOWN", "reviewer": None, "reviewedAt": None,
                                "reason": None, "evidenceRefs": []})
    return {"schemaVersion": 1, "batchId": loaded["batch"].get("batchId"),
            "batchSha256": loaded["batchHash"], "suiteHash": loaded["suiteHash"], "reviews": entries}


def load_review(path: Path | None, loaded: dict[str, Any]) -> dict[tuple[str, str, str], dict[str, Any]]:
    """仅采纳与冻结批次和样本字节匹配、有说明及证据引用的语义复核。"""
    if path is None:
        return {}
    review = load_json(path)
    if (review.get("schemaVersion") != 1 or review.get("batchId") != loaded["batch"].get("batchId")
            or review.get("batchSha256") != loaded["batchHash"]
            or review.get("suiteHash") != loaded["suiteHash"]):
        raise ReportError("复核收据与批次绑定不一致")
    known = {}
    for item in loaded["samples"].values():
        sample = item["data"]
        validate_assertions(sample)
        for index, assertion in enumerate(sample.get("assertions", [])):
            known[assertion_key(sample["sampleId"], assertion, index)] = (assertion, item)
    result = {}
    for entry in review.get("reviews", []):
        key = (entry.get("sampleId"), entry.get("dimension"), entry.get("assertionId"))
        if key not in known or key in result:
            raise ReportError("复核断言不存在或重复")
        assertion, item = known[key]
        if entry.get("sampleSha256") != item["hash"] or assertion["method"] == "DETERMINISTIC":
            raise ReportError("复核样本摘要不匹配或试图改写确定性断言")
        if entry.get("outcome") not in OUTCOMES[:3]:
            raise ReportError("复核结果非法")
        if entry["outcome"] != "UNKNOWN":
            if not all(entry.get(field) for field in ("reviewer", "reviewedAt", "reason", "evidenceRefs")):
                raise ReportError("已完成复核必须记录审阅者、时间、说明和证据引用")
            parse_time(entry["reviewedAt"])
            for pointer in entry["evidenceRefs"]:
                resolve_pointer(item["data"], pointer)
        result[key] = entry
    return result


def resolve_pointer(value: Any, pointer: str) -> Any:
    """验证样本内 JSON 指针真实存在；复核不能只填不可解析的引用。"""
    if not isinstance(pointer, str) or not pointer.startswith("#/"):
        raise ReportError("证据引用必须是样本内 #/ JSON 指针")
    try:
        for part in pointer[2:].split("/"):
            part = part.replace("~1", "/").replace("~0", "~")
            value = value[int(part)] if isinstance(value, list) else value[part]
    except (KeyError, IndexError, TypeError, ValueError) as error:
        raise ReportError("复核证据引用不存在") from error
    return value


def evidence_index(item: dict[str, Any]) -> dict[str, Any]:
    """索引实际捕获证据，公开报告不复制事件、作业或供应商原始载荷。"""
    sample = item["data"]
    refs = []
    for index, stage in enumerate(sample.get("stages", [])):
        for field, kind in (("events", "SESSION_EVENT"), ("jobs", "EXTRACTION_JOB"),
                            ("facts", "PERSISTED_FACT"), ("retrievals", "RETRIEVAL_PERFORMED")):
            values = stage.get(field, [])
            if isinstance(values, dict):
                refs.append({"kind": kind, "pointer": f"#/stages/{index}/{field}",
                             "stageKind": stage.get("kind")})
                continue
            for position, _value in enumerate(values):
                refs.append({"kind": kind, "pointer": f"#/stages/{index}/{field}/{position}",
                             "stageKind": stage.get("kind")})
        if stage.get("answer") is not None:
            refs.append({"kind": "ANSWER", "pointer": f"#/stages/{index}/answer",
                         "stageKind": stage.get("kind")})
    for index, call in enumerate(sample.get("wireCalls", [])):
        if call.get("responseContent") is not None:
            category = str(call.get("attempt", {}).get("category", ""))
            kind = "EXTRACTION_CANDIDATE_RESPONSE" if re.search("memory|extract", category, re.I) else "MODEL_RESPONSE"
            refs.append({"kind": kind, "pointer": f"#/wireCalls/{index}/responseContent",
                         "category": category})
    for index, _call in enumerate(sample.get("runtimeCalls", [])):
        refs.append({"kind": "RUNTIME_CALL", "pointer": f"#/runtimeCalls/{index}"})
    for index, assertion in enumerate(sample.get("assertions", [])):
        refs.append({"kind": "ASSERTION_OBSERVATION", "pointer": f"#/assertions/{index}/observed",
                     "assertionId": assertion_key(sample["sampleId"], assertion, index)[2]})
    return {"sampleId": sample["sampleId"], "sourcePath": item["relativePath"],
            "sourceSha256": item["hash"], "refs": refs}


def token_value(value: Any) -> int | None:
    """缺失或非法计量保持未知；零仅接受供应商明确报告的合法零。"""
    return value if isinstance(value, int) and not isinstance(value, bool) and value >= 0 else None


def usage_summary(items: list[dict[str, Any]], prices: dict[str, Any] | None,
                  batch: dict[str, Any] | None = None) -> dict[str, Any]:
    """优先采用完整批次调用账本，包含停止时在途请求；wire 观测不重复计费。"""
    calls, missing_lists = [], 0
    for item in items:
        sample = item["data"]
        if sample.get("outcome") == "NOT_RUN":
            continue
        if "runtimeCalls" not in sample or not isinstance(sample["runtimeCalls"], list):
            missing_lists += 1
        else:
            calls.extend(sample["runtimeCalls"])
    call_scope = "SAMPLES"
    if batch is not None and isinstance(batch.get("runtimeCalls"), list):
        calls, missing_lists, call_scope = batch["runtimeCalls"], 0, "BATCH"
    unique, identities = [], {}
    for call in calls:
        identity = call.get("attemptId")
        if identity is not None and identity in identities:
            if identities[identity] != call:
                raise ReportError("同一 Runtime attemptId 出现冲突计量")
            continue
        if identity is not None:
            identities[identity] = call
        unique.append(call)
    calls = unique
    inputs = [token_value((call.get("usage") or {}).get("inputTokens")) for call in calls]
    outputs = [token_value((call.get("usage") or {}).get("outputTokens")) for call in calls]
    unknown = missing_lists > 0 or (not items and call_scope != "BATCH")
    input_total = None if unknown or None in inputs else sum(inputs)
    output_total = None if unknown or None in outputs else sum(outputs)
    cost = None
    if prices is not None:
        if (not prices.get("currency") or any(not isinstance(prices.get(key), (int, float))
                or isinstance(prices.get(key), bool) or prices[key] < 0 or not math.isfinite(prices[key])
                for key in ("inputPerMillion", "outputPerMillion"))):
            raise ReportError("价格必须声明币种及非负的每百万输入/输出 Token 单价")
        if input_total is not None and output_total is not None:
            cost = (input_total * prices["inputPerMillion"] + output_total * prices["outputPerMillion"]) / 1_000_000
    wires = batch.get("wireCalls") if batch is not None else None
    return {"callScope": call_scope, "runtimeCallCount": len(calls), "missingRuntimeCallLists": missing_lists,
            "unknownInputCalls": inputs.count(None), "unknownOutputCalls": outputs.count(None),
            "knownInputSubtotal": sum(value for value in inputs if value is not None),
            "knownOutputSubtotal": sum(value for value in outputs if value is not None),
            "inputTokens": input_total, "outputTokens": output_total,
            "totalTokens": input_total + output_total if input_total is not None and output_total is not None else None,
            "wireCallCount": len(wires) if isinstance(wires, list) else sum(len(item["data"].get("wireCalls", [])) for item in items),
            "cost": cost, "currency": prices.get("currency") if prices else None,
            "pricingStatus": "CONFIGURED" if prices else "UNKNOWN"}


def percentile(values: list[float], percent: float) -> float | None:
    """使用线性插值给出描述统计，空样本不返回虚构耗时。"""
    if not values:
        return None
    values = sorted(values)
    position = (len(values) - 1) * percent
    lower = int(position)
    upper = min(lower + 1, len(values) - 1)
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def latency_summary(items: list[dict[str, Any]], batch: dict[str, Any] | None = None) -> dict[str, Any]:
    """仅统计实际 wire 调用完成耗时，并单列未报告值。"""
    result = {}
    wires = batch.get("wireCalls") if batch is not None else None
    calls = wires if isinstance(wires, list) else [call for item in items for call in item["data"].get("wireCalls", [])]
    for field in ("ttftMillis", "latencyMillis", "bodyCompleteMillis"):
        values, missing = [], 0
        for call in calls:
            value = call.get(field)
            if isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0 and math.isfinite(value):
                values.append(value)
            else:
                missing += 1
        result[field] = {"count": len(values), "unknown": missing,
                         "median": percentile(values, .5), "p95": percentile(values, .95)}
    return result


def observation(value: Any, pointer: str) -> Any:
    """复杂断言观测仅公开结构、摘要与指针，避免绕过原始载荷输出边界。"""
    if isinstance(value, (dict, list)):
        encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return {"type": "OBJECT" if isinstance(value, dict) else "ARRAY", "size": len(value),
                "sha256": hashlib.sha256(encoded).hexdigest(), "evidenceRef": pointer}
    return value


def record_count(value: Any) -> int:
    """单事实对象计一条，数组计实际条数，缺失字段不生成记录。"""
    return len(value) if isinstance(value, list) else (1 if isinstance(value, dict) else 0)


def junit_summary(directories: list[Path], names: list[str], not_before: str | None,
                  files: list[Path] | None = None) -> dict[str, Any]:
    """只读取显式目录、精确 suite 名称和本次时间界内的 JUnit，零测试不算通过。"""
    if not directories and not files:
        return {"outcome": "NOT_RUN", "suites": [], "note": "未指定本次 JUnit 证据"}
    if not names or not not_before:
        raise ReportError("收集 JUnit 必须显式提供 --junit-suite 和 --junit-not-before")
    boundary = parse_time(not_before).timestamp()
    accepted, seen = [], set()
    paths = list(files or [])
    for directory in directories:
        if not directory.is_dir():
            raise ReportError("指定 JUnit 目录不存在")
        paths.extend(directory.glob("TEST-*.xml"))
    for path in sorted({path.resolve() for path in paths}):
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
        for suite in suites:
            name = suite.get("name")
            if name not in names:
                continue
            if path.stat().st_mtime < boundary:
                raise ReportError("目标 JUnit 报告早于本次验证开始时间")
            if name in seen:
                raise ReportError("精确 JUnit suite 在多个报告中重复，无法确定本次权威证据")
            seen.add(name)
            cases = []
            for case in suite.findall("testcase"):
                status = "FAIL" if case.find("failure") is not None or case.find("error") is not None else (
                    "NOT_RUN" if case.find("skipped") is not None else "PASS")
                cases.append({"name": case.get("name"), "className": case.get("classname"), "outcome": status})
            declared = int(suite.get("tests", "0"))
            status = outcome([case["outcome"] for case in cases])
            if declared != len(cases) or not cases:
                status = "UNKNOWN"
            accepted.append({"name": name, "sourcePath": str(path.resolve()), "sourceSha256": sha256(path),
                             "modifiedAt": datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat(),
                             "declaredTests": declared, "counts": counts([case["outcome"] for case in cases]),
                             "outcome": status, "cases": cases})
    missing = sorted(set(names) - seen)
    return {"outcome": outcome([entry["outcome"] for entry in accepted] + (["UNKNOWN"] if missing else [])),
            "suites": accepted, "missingSuites": missing, "notBefore": not_before}


def engineering_summary(paths: list[Path], directories: list[Path], names: list[str],
                        not_before: str | None, files: list[Path] | None = None) -> dict[str, Any]:
    """读取本次精确复制的工程摘要并核对原 XML，摘要本身不能代替测试证据。"""
    expected, sources = {}, []
    directories, names = list(directories), list(names)
    for path in paths:
        rows = json.loads(path.read_text(encoding="utf-8-sig"))
        if not isinstance(rows, list) or not rows:
            raise ReportError("工程摘要必须是非空数组")
        sources.append({"sourcePath": str(path.resolve()), "sourceSha256": sha256(path)})
        for row in rows:
            name = row["class"]
            if name in expected:
                raise ReportError("工程摘要重复声明 suite")
            expected[name] = row
            directory = safe_child(path.parent, row["module"])
            xml = safe_child(directory, f"TEST-{name}.xml")
            if not xml.is_file():
                raise ReportError("工程摘要缺少同批次精确 XML")
            if directory not in directories:
                directories.append(directory)
            if name not in names:
                names.append(name)
    result = junit_summary(directories, names, not_before, files)
    for suite in result["suites"]:
        if suite["name"] not in expected:
            continue
        row, actual = expected[suite["name"]], suite["counts"]
        errors_or_failures = int(row.get("failures", 0)) + int(row.get("errors", 0))
        if (int(row.get("tests", -1)) != actual["planned"] or errors_or_failures != actual["FAIL"]
                or int(row.get("skipped", -1)) != actual["NOT_RUN"]):
            raise ReportError("工程摘要计数与实际 XML testcase 不一致")
    result["summaryInputs"] = sources
    return result


def build_report(loaded: dict[str, Any], reviews: dict[tuple[str, str, str], dict[str, Any]],
                 prices: dict[str, Any] | None = None, junit: dict[str, Any] | None = None) -> dict[str, Any]:
    """以真实断言和复核生成结果，最终回答词面不产生工具调用或检索成功证据。"""
    rows, indexes, dimension_values = [], [], {key: [] for key in DIMENSIONS}
    for scenario in loaded["suite"]["scenarios"]:
        scenario_id = scenario["id"]
        for repeat in range(1, loaded["suite"]["repeats"] + 1):
            item = loaded["samples"].get((scenario_id, repeat))
            relevant = set(scenario.get("dimensions", SCENARIO_DIMENSIONS.get(scenario_id, [])))
            if not relevant or relevant - DIMENSIONS.keys():
                raise ReportError("场景必须声明可识别的评测维度")
            row = {"sampleId": f"{scenario_id}-{repeat}", "scenarioId": scenario_id,
                   "scenarioName": scenario.get("name"), "repeat": repeat,
                   "lane": "PRESET_RETRIEVAL_DIAGNOSTIC" if scenario.get("presetFacts") else "REAL_EXTRACTION_END_TO_END",
                   "outcome": "NOT_RUN", "assertions": [], "dimensions": {}, "stages": []}
            if item is not None:
                sample = item["data"]
                validate_assertions(sample)
                row.update(sampleId=sample["sampleId"], sourceOutcome=sample["outcome"],
                           sourcePath=item["relativePath"], sourceSha256=item["hash"],
                           lane=sample.get("lane", row["lane"]),
                           failures=sample.get("failures", []))
                for assertion_index, assertion in enumerate(sample.get("assertions", [])):
                    review = reviews.get(assertion_key(sample["sampleId"], assertion, assertion_index))
                    effective = assertion["outcome"] if assertion["method"] == "DETERMINISTIC" else (
                        review["outcome"] if review else "UNKNOWN")
                    row["assertions"].append({"dimension": assertion["dimension"], "code": assertion["code"],
                                              "method": assertion["method"], "sourceOutcome": assertion["outcome"],
                                              "outcome": effective, "expected": assertion.get("expected"),
                                              "observed": observation(assertion.get("observed"), f"#/assertions/{assertion_index}/observed"),
                                              "reviewed": bool(review and review["outcome"] != "UNKNOWN")})
                    relevant.add(assertion["dimension"])
                row["outcome"] = "NOT_RUN" if sample["outcome"] == "NOT_RUN" else outcome(
                    [assertion["outcome"] for assertion in row["assertions"]])
                for stage in sample.get("stages", []):
                    row["stages"].append({"kind": stage.get("kind"), "terminal": stage.get("terminal"),
                                          "input": stage.get("input"), "answer": stage.get("answer"),
                                          "eventCount": len(stage.get("events", [])),
                                          "jobCount": len(stage.get("jobs", [])),
                                          "factCount": record_count(stage.get("facts")),
                                          "retrievalPerformedCount": len(stage.get("retrievals", []))})
                indexes.append(evidence_index(item))
            for dimension in sorted(relevant):
                values = [assertion["outcome"] for assertion in row["assertions"]
                          if assertion["dimension"] == dimension]
                status = outcome(values, "NOT_RUN" if row["outcome"] == "NOT_RUN" else "UNKNOWN")
                row["dimensions"][dimension] = status
                dimension_values[dimension].append(status)
            # 声明适用的维度没有断言时不能把样本升级为全能力通过。
            row["outcome"] = outcome(list(row["dimensions"].values()))
            rows.append(row)
    batch = loaded["batch"]
    template = review_template(loaded)
    batch_refs = [{"kind": "RUNTIME_CALL", "pointer": f"#/runtimeCalls/{index}"}
                  for index, _call in enumerate(batch.get("runtimeCalls", []))]
    batch_refs.extend({"kind": "MODEL_RESPONSE", "pointer": f"#/wireCalls/{index}/responseContent"}
                      for index, call in enumerate(batch.get("wireCalls", [])) if call.get("responseContent") is not None)
    return public({"schemaVersion": 1, "suiteId": loaded["suite"]["suiteId"], "batchId": batch.get("batchId"),
                   "generatedAt": datetime.now(timezone.utc).isoformat(), "startedAt": batch.get("startedAt"),
                   "binding": {"batchSha256": loaded["batchHash"], "suiteHash": loaded["suiteHash"],
                               "model": batch.get("model"), "prompt": batch.get("prompt")},
                   "stopCode": batch.get("stopCode"), "budget": batch.get("budget"),
                   "overall": counts([row["outcome"] for row in rows]),
                   "lanes": {lane: counts([row["outcome"] for row in rows if row["lane"] == lane])
                             for lane in sorted({row["lane"] for row in rows})},
                   "dimensions": {key: {"label": DIMENSIONS[key], **counts(values)}
                                  for key, values in dimension_values.items()},
                   "usage": usage_summary(list(loaded["samples"].values()), prices, batch),
                   "latency": latency_summary(list(loaded["samples"].values()), batch),
                   "review": {"required": len(template["reviews"]),
                              "completed": sum(entry["outcome"] != "UNKNOWN" for entry in reviews.values()),
                              "pending": len(template["reviews"]) - sum(entry["outcome"] != "UNKNOWN" for entry in reviews.values())},
                   "engineering": junit or {"outcome": "NOT_RUN", "suites": []},
                   "samples": rows, "evidenceIndex": indexes, "batchEvidenceRefs": batch_refs,
                   "limitations": ["结果仅适用于本套合成用例和冻结配置。",
                                   "词面启发式不证明语义正确，未复核项保持 UNKNOWN。",
                                   "检索次数只来自实际 retrieval.performed 证据，回答内容不证明调用工具。",
                                   "Runtime 用量与 wire 观测不重复累计，缺失计量和价格保持 UNKNOWN。",
                                   "公开报告保留证据索引，原始内部载荷不复制到公开材料。"]})


def cell(value: Any) -> str:
    """转义 Markdown 表格单元格，避免回答内容破坏报告结构。"""
    return str(value).replace("|", "\\|").replace("\r", " ").replace("\n", "<br>")


def percentage(value: float | None) -> str:
    """未知比例明确显示 UNKNOWN。"""
    return "UNKNOWN" if value is None else f"{value * 100:.1f}%"


def markdown(report: dict[str, Any]) -> str:
    """生成中文报告，呈现完整分母、实际证据、未知项和失败定位。"""
    overall = report["overall"]
    lines = ["# 灵知 记忆评测报告", "", f"批次：`{cell(report['batchId'])}`；套件：`{cell(report['suiteId'])}`。", "",
             f"计划 {overall['planned']} 个样本：通过 {overall['PASS']}、失败 {overall['FAIL']}、UNKNOWN {overall['UNKNOWN']}、未完成 {overall['NOT_RUN']}。完整计划通过率 **{percentage(overall['passRate'])}**；已执行样本通过率 {percentage(overall['attemptedPassRate'])}（分母 {overall['attempted']}）。", "",
             f"停止原因：`{cell(report.get('stopCode') or 'NONE')}`。语义复核 {report['review']['completed']}/{report['review']['required']} 项，待复核 {report['review']['pending']} 项。", "",
             "## 冻结输入", "", f"- 批次文件 SHA-256：`{report['binding']['batchSha256']}`。",
             f"- 套件文件 SHA-256：`{report['binding']['suiteHash']}`。",
             f"- 模型：`{cell(json.dumps(report['binding']['model'], ensure_ascii=False))}`。",
             f"- 提示词：`{cell(json.dumps(report['binding']['prompt'], ensure_ascii=False))}`。", "",
             "## 能力维度", "", "| 维度 | 适用样本 | 通过 | 失败 | UNKNOWN | 未完成 | 完整分母通过率 |",
             "|---|---:|---:|---:|---:|---:|---:|"]
    for entry in report["dimensions"].values():
        lines.append(f"| {entry['label']} | {entry['planned']} | {entry['PASS']} | {entry['FAIL']} | {entry['UNKNOWN']} | {entry['NOT_RUN']} | {percentage(entry['passRate'])} |")
    lines.extend(["", "预设事实检索诊断与真实提取端到端样本分别统计：", "",
                  "| 评测路径 | 计划样本 | 通过 | 失败 | UNKNOWN | 未完成 |", "|---|---:|---:|---:|---:|---:|"])
    for lane, metrics in report["lanes"].items():
        lines.append(f"| {lane} | {metrics['planned']} | {metrics['PASS']} | {metrics['FAIL']} | {metrics['UNKNOWN']} | {metrics['NOT_RUN']} |")
    usage = report["usage"]
    lines.extend(["", "## 用量和实际调用", "",
                  f"Runtime 调用 {usage['runtimeCallCount']} 次；wire 观测 {usage['wireCallCount']} 次。输入 Tokens：{usage['inputTokens'] if usage['inputTokens'] is not None else 'UNKNOWN'}；输出 Tokens：{usage['outputTokens'] if usage['outputTokens'] is not None else 'UNKNOWN'}；合计：{usage['totalTokens'] if usage['totalTokens'] is not None else 'UNKNOWN'}。", "",
                  f"已知用量小计：输入 {usage['knownInputSubtotal']}、输出 {usage['knownOutputSubtotal']}；输入未知调用 {usage['unknownInputCalls']}、输出未知调用 {usage['unknownOutputCalls']}、缺少 Runtime 调用清单的样本 {usage['missingRuntimeCallLists']}。费用：{cell(str(usage['cost']) + ' ' + str(usage['currency'])) if usage['cost'] is not None else 'UNKNOWN'}。", "",
                  "| 实际 wire 耗时字段（毫秒） | 有值调用 | 未知 | 中位数 | P95 |", "|---|---:|---:|---:|---:|"])
    for field, metrics in report["latency"].items():
        lines.append(f"| {field} | {metrics['count']} | {metrics['unknown']} | {metrics['median'] if metrics['median'] is not None else 'UNKNOWN'} | {metrics['p95'] if metrics['p95'] is not None else 'UNKNOWN'} |")
    lines.extend(["", "## 工程验证证据", "", f"结果：`{report['engineering']['outcome']}`。仅收集显式指定且满足本次时间界的精确 JUnit suite。", ""])
    for suite in report["engineering"].get("suites", []):
        lines.append(f"- `{cell(suite['name'])}`：{suite['outcome']}；测试 {suite['counts']['planned']}；SHA-256 `{suite['sourceSha256']}`。")
    if report["engineering"].get("missingSuites"):
        lines.append(f"缺少：{cell(', '.join(report['engineering']['missingSuites']))}。")
    lines.extend(["", "## 全部样本", "", "| 样本 | 场景 | 结果 | 失败编码 |", "|---|---|---|---|"])
    for sample in report["samples"]:
        failures = ", ".join(assertion["code"] for assertion in sample["assertions"] if assertion["outcome"] == "FAIL")
        lines.append(f"| {cell(sample['sampleId'])} | {cell(sample['scenarioName'])} | {sample['outcome']} | {cell(failures or '—')} |")
    lines.extend(["", "## 代表案例与复现入口", "", "每个结果关联同批次样本文件、其 SHA-256 及实际 JSON 指针。公开报告不包含原始事件载荷或供应商正文。", ""])
    selected = []
    for status in ("PASS", "FAIL", "UNKNOWN"):
        selected.extend([sample for sample in report["samples"] if sample["outcome"] == status][:3])
    for sample in selected:
        lines.extend([f"### {cell(sample['sampleId'])}：{cell(sample['scenarioName'])}（{sample['outcome']}）", "",
                      f"样本文件：`{cell(sample.get('sourcePath', 'UNKNOWN'))}`；SHA-256：`{sample.get('sourceSha256', 'UNKNOWN')}`。", ""])
        for assertion in sample["assertions"]:
            if assertion["outcome"] != "PASS":
                lines.append(f"- `{cell(assertion['code'])}`：{assertion['outcome']}；检查方法 {assertion['method']}；原始检查结果 {assertion['sourceOutcome']}。")
        for stage in sample["stages"]:
            if stage["kind"] in ("NATURAL_PROBE", "DIAGNOSTIC"):
                lines.extend(["", f"{stage['kind']}：实际检索轨迹 {stage['retrievalPerformedCount']} 条；输入：{cell(stage.get('input'))}；回答：{cell(stage.get('answer'))}。", ""])
    lines.extend(["## 证据边界", ""] + [f"- {item}" for item in report["limitations"]] + [""])
    return "\n".join(lines)


def write_json(path: Path, value: Any) -> None:
    """只创建新文件，不覆盖原始证据、历史结论或已有复核。"""
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(public(value), stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")


def main(argv: list[str] | None = None) -> int:
    """命令行只读冻结输入，输出到新的离线结果目录或复核模板。"""
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for command in ("report", "review-template"):
        subparser = subparsers.add_parser(command)
        subparser.add_argument("--batch", type=Path, required=True)
        subparser.add_argument("--suite", type=Path, default=Path(__file__).with_name("suite.json"))
        subparser.add_argument("--output", type=Path, required=True)
        if command == "report":
            subparser.add_argument("--review", type=Path)
            subparser.add_argument("--prices", type=Path)
            subparser.add_argument("--junit-dir", action="append", type=Path, default=[])
            subparser.add_argument("--junit-file", action="append", type=Path, default=[])
            subparser.add_argument("--junit-suite", action="append", default=[])
            subparser.add_argument("--junit-not-before")
            subparser.add_argument("--engineering-summary", action="append", type=Path, default=[])
    args = parser.parse_args(argv)
    try:
        loaded = load_batch(args.batch.resolve(), args.suite.resolve())
        template = review_template(loaded)
        if args.command == "review-template":
            args.output.parent.mkdir(parents=True, exist_ok=True)
            write_json(args.output, template)
        else:
            if args.output.exists():
                raise ReportError("输出目录必须不存在；离线重评请指定新的目录")
            reviews = load_review(args.review, loaded)
            prices = load_json(args.prices) if args.prices else None
            junit = engineering_summary(args.engineering_summary, args.junit_dir,
                                        args.junit_suite, args.junit_not_before, args.junit_file)
            report = build_report(loaded, reviews, prices, junit)
            args.output.mkdir(parents=True)
            write_json(args.output / "report.json", report)
            write_json(args.output / "evidence-index.json", {"schemaVersion": 1, "batchId": report["batchId"],
                                                          "binding": report["binding"], "batchRefs": report["batchEvidenceRefs"],
                                                          "samples": report["evidenceIndex"]})
            write_json(args.output / "review-template.json", template)
            if args.review:
                write_json(args.output / "review-receipt.json", {"schemaVersion": 1,
                           "sourceSha256": sha256(args.review), "binding": report["binding"],
                           "reviews": list(reviews.values())})
            (args.output / "report.md").write_text(markdown(report), encoding="utf-8", newline="\n")
        print(str(args.output.resolve()))
        return 0
    except (ReportError, OSError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        print(f"无法生成报告：{public(str(error))}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
