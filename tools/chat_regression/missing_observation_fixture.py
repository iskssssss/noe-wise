"""从私有冻结请求构造缺失业务观测夹具；只处理本地 JSON，不调用模型或数据库。"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path


def document(value: object) -> str:
    """输出稳定的 UTF-8 JSON 文本，供摘要与离线预算绑定。"""
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def message(role: str, identifier: str, text: str, *, calls=None, result_for=None) -> dict:
    """构造冻结请求消息；历史工具调用仅表示合成上下文。"""
    return {"role": role, "messageId": identifier,
            "contents": [{"type": "text", "text": text}],
            "reasoningContent": None, "toolCalls": calls or [], "toolCallId": result_for}


def call(identifier: str, name: str, arguments: dict) -> dict:
    """构造有明确配对标识的合成历史调用。"""
    return {"callId": identifier, "wireName": name,
            "arguments": {"canonicalJson": json.dumps(arguments, ensure_ascii=False),
                          "schemaId": "application/json"}}


def finalize_instruction(source: dict) -> str:
    """从最后一条含 finalizing 结构化回执的 TOOL 消息原样提取指令，歧义时拒绝。"""
    for item in reversed(source.get("messages", [])):
        if item.get("role") != "TOOL":
            continue
        candidates = []
        for content in item.get("contents", []):
            text = content.get("text", "")
            start = text.find("{")
            if start < 0:
                continue
            try:
                receipt = json.loads(text[start:])
            except json.JSONDecodeError:
                continue
            if not isinstance(receipt, dict):
                continue
            structured = receipt.get("output", {}).get("structuredContent", {})
            if structured.get("state") == "finalizing":
                candidates.append(structured.get("instruction"))
        if candidates:
            if len(candidates) != 1 or not isinstance(candidates[0], str) or not candidates[0].strip():
                raise ValueError("source_finalize_instruction_ambiguous_or_empty")
            return candidates[0]
    raise ValueError("source_finalize_instruction_missing")


def build_fixture(source: dict) -> dict:
    """保留调用契约和首条 SYSTEM/answer-phase，替换所有业务历史；拒绝非最终请求。"""
    if (source.get("toolChoice") != "NONE" or source.get("tools") != []
            or source.get("response", {}).get("format", {}).get("kind") != "NATIVE_CONVERSATION"):
        raise ValueError("source_native_answer_without_tools_required")
    messages = source.get("messages", [])
    if not messages or messages[0].get("role") != "SYSTEM":
        raise ValueError("source_system_missing")
    phases = [m for m in messages if m.get("messageId") == "answer-phase" and m.get("role") == "SYSTEM"]
    if len(phases) != 1:
        raise ValueError("source_answer_phase_missing_or_ambiguous")
    instruction = finalize_instruction(source)
    fixture = copy.deepcopy(source)
    code = """import csv, json
from pathlib import Path
orders = list(csv.DictReader(Path('/input/data/orders.csv').read_text().splitlines()))
ledger = list(csv.DictReader(Path('/input/data/ledger.csv').read_text().splitlines()))
unique = {row['id']: float(row['total']) for row in orders}
report = {'raw_total': sum(float(row['total']) for row in orders),
          'unique_total': sum(unique.values()),
          'ledger_total': sum(float(row['total']) for row in ledger)}
report['difference'] = report['unique_total'] - report['ledger_total']
assert all(isinstance(value, (int, float)) for value in report.values())
Path('/workspace/output').mkdir(parents=True, exist_ok=True)
Path('/workspace/output/reconciliation-report.json').write_text(json.dumps(report))
"""
    receipt = {"status": "SUCCEEDED", "output": {"structuredContent": {
        "success": True, "state": "completed", "exit_code": 0,
        "logs": [], "artifacts": [{"id": "fixture-artifact", "name": "reconciliation-report.json", "status": "ready"}],
        "public_presentation": {"summary": "文件已生成，脚本未报告异常", "results": []}}}}
    frozen = {"status": "SUCCEEDED", "output": {"structuredContent": {
        "success": True, "state": "finalizing", "files": ["对账报告.json"],
        "instruction": instruction}}}
    fixture["messages"] = [copy.deepcopy(messages[0]),
        message("SYSTEM", "fixture-context", "附件正文未在对话中显示。附件元数据：00000000-0000-4000-8000-000000000001 的 input_path 为 orders.csv；00000000-0000-4000-8000-000000000002 的 input_path 为 ledger.csv。上一版成果已正式发布；本次修改尚无当前修订的正式发布回执。"),
        message("USER", "fixture-user", "给出本次对账报告的结果，说明合计和差额。"),
        message("ASSISTANT", "fixture-code-call", "读取附件并生成对账报告。", calls=[
            call("fixture-code", "code_execute", {"commentary": "读取附件并生成对账报告。", "arguments": {
                "mode": "inline", "language": "python", "code": code,
                "attachment_ids": ["00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002"],
                "output_files": ["reconciliation-report.json"]}})]),
        message("TOOL", "fixture-code-result", "工具结果：\n" + json.dumps(receipt, ensure_ascii=False), result_for="fixture-code"),
        message("ASSISTANT", "fixture-finalize-call", "提交最终文件清单。", calls=[
            call("fixture-finalize", "finalize_task_outputs", {"commentary": "提交最终文件清单。", "arguments": {"deliverables": [{"key": "reconciliation-report", "representations": [{"format": "json", "files": [{"artifact_id": "fixture-artifact", "path": "reconciliation-report.json"}]}]}]}})]),
        message("TOOL", "fixture-finalize-result", "工具结果：\n" + json.dumps(frozen, ensure_ascii=False), result_for="fixture-finalize"),
        copy.deepcopy(phases[0])]
    return fixture


def prepare_budget(fixture: dict, *, max_tokens=50000, max_seconds=120, max_output_tokens=4096) -> dict:
    """绑定单次、无自动重试的独立预算；字节估算只是保守预留，不是实际用量。"""
    raw = document(fixture).encode("utf-8")
    budget = {"fixture_sha256": hashlib.sha256(raw).hexdigest(), "max_attempts": 1,
              "automatic_retry": False, "max_tokens": max_tokens,
              "max_output_tokens": max_output_tokens, "max_seconds": max_seconds}
    validate_fixture(fixture, budget, raw)
    return budget


def validate_fixture(fixture: dict, budget: dict, raw: bytes) -> None:
    """离线拒绝错配工具回执、非正文收尾请求、摘要漂移与超预算；不证明模型语义。"""
    if (fixture.get("toolChoice") != "NONE" or fixture.get("tools") != []
            or fixture.get("response", {}).get("format", {}).get("kind") != "NATIVE_CONVERSATION"):
        raise ValueError("fixture_native_answer_without_tools_required")
    if json.loads(raw) != fixture or hashlib.sha256(raw).hexdigest() != budget.get("fixture_sha256"):
        raise ValueError("fixture_digest_mismatch")
    pending, seen = set(), set()
    for item in fixture["messages"]:
        if pending and item["role"] != "TOOL":
            raise ValueError("tool_result_missing")
        for invocation in item.get("toolCalls", []):
            identifier = invocation["callId"]
            if item["role"] != "ASSISTANT" or identifier in seen:
                raise ValueError("tool_call_invalid")
            seen.add(identifier)
            pending.add(identifier)
        if item["role"] == "TOOL":
            identifier = item.get("toolCallId")
            if identifier not in pending:
                raise ValueError("tool_result_unpaired")
            pending.remove(identifier)
    if pending:
        raise ValueError("tool_result_missing")
    output = int(fixture["generation"]["maxOutputTokens"])
    if (budget.get("max_attempts") != 1 or budget.get("automatic_retry") is not False
            or not 1 <= budget.get("max_seconds", 0) <= 180
            or not 1 <= output <= budget.get("max_output_tokens", 0)
            or len(raw) + 4096 + output > budget.get("max_tokens", 0)):
        raise ValueError("budget_invalid_or_exceeded")


def main() -> None:
    """仅写入新目录；生成物继承私有 SYSTEM/绑定，禁止纳入 Git。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--max-tokens", type=int, default=50000)
    parser.add_argument("--max-seconds", type=int, default=120)
    args = parser.parse_args()
    source_bytes = args.source.read_bytes()
    source = json.loads(source_bytes)
    fixture = build_fixture(source)
    budget = prepare_budget(fixture, max_tokens=args.max_tokens, max_seconds=args.max_seconds)
    args.output.mkdir(parents=True, exist_ok=False)
    # 按摘要使用的精确字节写入，避免 Windows 文本换行转换使预算绑定失效。
    (args.output / "fixture.json").write_bytes(document(fixture).encode("utf-8"))
    (args.output / "budget.json").write_bytes(document(budget).encode("utf-8"))
    manifest = {"kind": "controlled_final_request_fixture", "status": "PREPARED_OFFLINE",
                "source_sha256": hashlib.sha256(source_bytes).hexdigest(),
                "fixture_sha256": budget["fixture_sha256"], "provider_calls": 0,
                "semantic_review": "NOT_RUN", "production_release_acceptance": False,
                "preserved_exactly": ["messages[0]", "SYSTEM messageId=answer-phase",
                                      "last finalizing TOOL output.structuredContent.instruction",
                                      "all non-messages frozen request fields"],
                "mutations": ["all other messages replaced by synthetic history without business values"],
                "boundary": "Offline preparation only; synthetic history was not executed. No Runtime, sandbox, publication, UI, ModelPort admission or billing-ledger acceptance."}
    (args.output / "fixture-manifest.json").write_bytes(document(manifest).encode("utf-8"))
    print("PREPARED_OFFLINE provider_calls=0; semantic_review=NOT_RUN")


if __name__ == "__main__":
    main()
