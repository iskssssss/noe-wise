"""为 D01-D12 产品行为评估准备真实、隔离且可恢复的资源。

本模块只调用已有机构端 API。所有写请求沿用 :mod:`provision` 的
``resources.json`` 写入意图日志；未知写入结果不会自动重试。
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any

from provision import (
    ProvisionError,
    ProvisionUnknownOutcome,
    _JOURNAL_CONTEXT,
    _digest,
    _list_response,
    _mount_from_response,
    _positive_id,
    _publish_agent,
    _read,
    _save_resources,
    _text,
    _write,
    provision,
)


_UNUSED_FIXTURE_URL = "http://127.0.0.1/unused-product-fixture"
_CATEGORIES = tuple(f"D{index:02d}" for index in range(1, 13))
_MATERIAL_REF = re.compile(r"[a-z][a-z0-9_-]{0,47}")
_PRODUCT_CONTRACT_VERSION = "product-resources-4"


def _profiles() -> list[dict[str, Any]]:
    base = "只处理当前合成评估资料；缺少事实时澄清，不猜测结果或伪造工具成功。"
    child_base = ("只处理当前委派提供的合成资料；这是单轮离线核对，没有补充输入轮次。"
                  "不得调用 ask_user 等待补充；未规定或无法核对的事项在最终结果中明确标注，"
                  "完成可核验部分并返回父任务，不猜测缺失事实或伪造成功。")
    specs = [
        ("d01-rules", "比较规则和方案，明确引用适用条件与例外。", [], []),
        ("d02-clarify", "关键信息缺失时先询问，用户改口后采用最新确认值。", [], []),
        ("d03-csv", "使用代码执行读取实际 CSV 后再计算，不手算猜测。", [], ["code_execute"]),
        ("d04-knowledge", "只能依据挂载知识库回答并保留实际引用。", [], []),
        ("d05-long-doc", "使用代码工具载入长文；结果被引用化后必须读取引用正文再回答。", [],
         ["code_execute", "read_context_reference"]),
        ("d06-rewrite", "忠实改写资料，保留数字、日期、限制和例外。", [], []),
        ("d07-skills", "根据用户阶段切换资料收集与总结技能。", [
            {"key": "collect", "instructions": "收集或纠正资料时，以【资料】开头，保留最新确认值且不重复询问已知事实。"},
            {"key": "summarize", "instructions": "总结已确认资料时，以【总结】开头，保留精确数值、日期和例外。"},
        ], []),
        ("d08-reconcile", "使用当前会话上传的两个附件核对差异并说明来源。"
         "必须把附件ID传给代码工具，并根据工具返回的 input_path 从 /input/data 读取真实文件；"
         "不得把附件正文复制到工具参数或回答中。", [],
         ["code_execute", "read_context_reference"]),
        ("d09-report", "使用代码工具创建并按后续要求修订真实报告文件。", [],
         ["code_execute", "list_artifacts"]),
        ("d10-workflow", "调用挂载工作流，在人工确认点等待并根据确认续接。", [], []),
        ("d11-parent", "分别委派规则核对和订单计算两个子任务，完整传递对应资料和核对条件。"
         "订单计算必须按订单ID去重，返回去重明细与总额。保留每次受理成功的 delegation_id；"
         "后续决策格式错误不代表已受理任务失败，不得重新发起已受理的子任务。"
         "等待这两个原始委派全部终结，再依据实际结果汇总；核对去重明细、总额和适用规则是否一致，"
         "证据不足时明确说明，不将未核验数字当作已完成结果。", [], []),
        ("d11-child-a", "完成委派的规则与事实提取子任务，只返回可核验结论。", [], []),
        ("d11-child-b", "完成订单核对：按订单ID去重，同ID且金额相同的重复行只计一次。"
         "同ID金额冲突时说明冲突，不擅自选值。返回去重后的订单ID、金额及 unique_total；"
         "原始行总额、去重总额和折后金额分别标注，不得混用。只返回可核验结论。", [], []),
        ("d12-cancel", "长任务使用代码工具；收到取消后不得声称任务已完成。", [], ["code_execute"]),
    ]
    return [{"key": key, "prompt": (child_base if key.startswith("d11-child-") else base) + prompt,
             "skills": skills, "tools": tools}
            for key, prompt, skills, tools in specs]


def _case_profiles() -> dict[str, str]:
    return {category: f"d{int(category[1:]):02d}-" + {
        "D01": "rules", "D02": "clarify", "D03": "csv", "D04": "knowledge",
        "D05": "long-doc", "D06": "rewrite", "D07": "skills", "D08": "reconcile",
        "D09": "report", "D10": "workflow", "D11": "parent", "D12": "cancel",
    }[category] for category in _CATEGORIES}


def _selected(dataset: dict[str, Any]) -> dict[str, dict[str, Any]]:
    if not isinstance(dataset, dict) or not isinstance(dataset.get("selected"), list):
        raise ProvisionError("product dataset must contain selected candidates")
    result: dict[str, dict[str, Any]] = {}
    for candidate in dataset["selected"]:
        if not isinstance(candidate, dict) or candidate.get("category") not in _CATEGORIES:
            raise ProvisionError("product dataset contains an invalid selected candidate")
        category = candidate["category"]
        if category in result:
            raise ProvisionError(f"product dataset repeats category {category}")
        required = ("candidate_id", "variant", "goal", "user_turns", "materials",
                    "material_refs", "required_capabilities", "provenance")
        if any(field not in candidate for field in required):
            raise ProvisionError(f"product dataset candidate {category} is incomplete")
        if not isinstance(candidate["materials"], list) or not isinstance(candidate["material_refs"], list):
            raise ProvisionError(f"product dataset candidate {category} has invalid materials")
        refs: set[str] = set()
        for material in candidate["materials"]:
            if not isinstance(material, dict) or set(material) != {"ref", "kind", "content"}:
                raise ProvisionError(f"product dataset candidate {category} has an invalid material")
            ref = material["ref"]
            if (not isinstance(ref, str) or not _MATERIAL_REF.fullmatch(ref) or ref in refs
                    or material["kind"] not in ("text", "csv")
                    or not isinstance(material["content"], str) or not material["content"]):
                raise ProvisionError(f"product dataset candidate {category} has an invalid material")
            refs.add(ref)
        if not candidate["material_refs"] or not set(candidate["material_refs"]).issubset(refs):
            raise ProvisionError(f"product dataset candidate {category} has invalid material_refs")
        result[category] = candidate
    missing = sorted(set(_CATEGORIES) - set(result))
    if missing:
        raise ProvisionError("product dataset lacks selected categories: " + ", ".join(missing))
    required_refs = {"D03": {"orders"}, "D04": {"rules"}, "D05": {"handbook"},
                     "D08": {"orders", "ledger"}}
    for category, refs in required_refs.items():
        if not refs.issubset(set(result[category]["material_refs"])):
            raise ProvisionError(
                f"product dataset candidate {category} lacks required material refs: "
                + ", ".join(sorted(refs)))
    return result


def _load_default_dataset(root: Path) -> dict[str, Any]:
    try:
        from behavior_dataset import load_dataset
        return load_dataset(root)
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise ProvisionError("a frozen product dataset is required for resource preparation") from error


def _write_material_fixtures(
    root: Path, selected: dict[str, dict[str, Any]], resources: dict[str, Any], path: Path
) -> None:
    target_root = root / "product-fixtures"
    files = resources.setdefault("files", {})
    for category, candidate in selected.items():
        category_files = files.setdefault(category, {})
        for material in candidate["materials"]:
            ref, content = material["ref"], material["content"]
            suffix = ".csv" if material["kind"] == "csv" else ".txt"
            target = target_root / category.lower() / (ref + suffix)
            encoded = content.encode("utf-8")
            sha256 = hashlib.sha256(encoded).hexdigest()
            if target.exists() and target.read_bytes() != encoded:
                raise ProvisionError(f"existing product fixture differs: {target}")
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists():
                temporary = target.with_suffix(target.suffix + ".tmp")
                temporary.write_bytes(encoded)
                temporary.replace(target)
            binding = {"ref": ref, "kind": material["kind"],
                       "path": target.relative_to(root).as_posix(), "sha256": sha256,
                       "size": len(encoded)}
            if category_files.get(ref) not in (None, binding):
                raise ProvisionError(f"resources checkpoint differs for fixture {category}.{ref}")
            category_files[ref] = binding
    _save_resources(path, resources)


class _MultipartAdapter:
    def __init__(self, api: Any):
        self.api = api

    def request(self, method: str, path: str, body=None, headers=None):
        return _multipart_request(self.api, method, path, body)


def _multipart_request(api: Any, method: str, path: str, body: dict[str, Any]) -> dict[str, Any]:
    custom = getattr(api, "request_multipart", None)
    if callable(custom):
        return custom(method, path, fields={"title": body["title"]},
                      filename=body["filename"], content=body["content"].encode("utf-8"),
                      content_type="text/plain; charset=utf-8")
    base = getattr(api, "base", None)
    base_headers = getattr(api, "headers", None)
    if not isinstance(base, str) or not isinstance(base_headers, dict):
        raise ProvisionError("API client does not support the existing multipart document endpoint")
    refresh = getattr(api, "_refresh_if_due", None)
    if callable(refresh):
        refresh()
    boundary = "skillforge-" + uuid.uuid4().hex
    chunks: list[bytes] = []
    chunks.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"title\"\r\n"
                   f"Content-Type: text/plain; charset=utf-8\r\n\r\n{body['title']}\r\n").encode("utf-8"))
    chunks.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                   f"filename=\"{body['filename']}\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n").encode("utf-8"))
    chunks.append(body["content"].encode("utf-8"))
    chunks.append(f"\r\n--{boundary}--\r\n".encode("ascii"))
    headers = {key: value for key, value in base_headers.items() if key.lower() != "content-type"}
    headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    request = urllib.request.Request(base.rstrip("/") + path, data=b"".join(chunks),
                                     headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=getattr(api, "timeout", 30)) as response:
            payload = response.read(16 * 1024 * 1024 + 1)
    except urllib.error.HTTPError as error:
        raise ProvisionError(f"multipart document upload HTTP {error.code}") from None
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise ProvisionError(f"multipart document upload transport={type(error).__name__}") from None
    if len(payload) > 16 * 1024 * 1024:
        raise ProvisionError("multipart document upload response exceeds 16MiB")
    try:
        value = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ProvisionError("multipart document upload returned invalid JSON") from error
    if base.rstrip("/").endswith("/api/v1"):
        if not isinstance(value, dict) or value.get("code") != "OK" or "data" not in value:
            raise ProvisionError("multipart document upload violates ApiResponse contract")
        value = value["data"]
    if not isinstance(value, dict):
        raise ProvisionError("multipart document upload returned a non-object response")
    return value


def _items(value: Any, label: str) -> list[Any]:
    if isinstance(value, dict):
        value = value.get("items")
    return _list_response(value, label)


def _definition_for_version(api: Any, capability_type: str, version_id: int) -> int:
    definitions = _items(_read(api, "GET",
        f"capabilities/definitions?capability_type={capability_type}&status=PUBLISHED",
        f"{capability_type} definitions"), f"{capability_type} definitions")
    matches: list[int] = []
    for definition in definitions:
        if not isinstance(definition, dict):
            raise ProvisionError(f"{capability_type} definitions contains a non-object")
        definition_id = _positive_id(definition, "id", f"{capability_type} definition")
        versions = _items(_read(api, "GET",
            f"capabilities/definitions/versions?definitionId={definition_id}&status=PUBLISHED",
            f"{capability_type} versions"), f"{capability_type} versions")
        if any(isinstance(version, dict) and str(version.get("id")) == str(version_id)
               for version in versions):
            matches.append(definition_id)
    if len(matches) != 1:
        raise ProvisionError(
            f"{capability_type} capability version {version_id} must resolve to exactly one definition")
    return matches[0]


def _mount_product_capability(
    api: Any, resources: dict[str, Any], path: Path, profile: str, mount_key: str,
    capability_type: str, definition_id: int, version_id: int, config_key: str,
    config: dict[str, Any], priority: int = 0,
) -> None:
    agent = resources["agents"][profile]
    if mount_key in agent["mounts"]:
        return
    response = _write(api, "POST", "agents/capability-mounts", {
        "agent_id": agent["id"], "payload": {
            "capability_type": capability_type,
            "capability_definition_id": str(definition_id),
            "capability_version_id": str(version_id),
            config_key: config,
            "priority": priority,
        }}, f"agent {agent['id']} {mount_key}")
    agent["mounts"][mount_key] = _mount_from_response(
        response, {"definition_id": definition_id, "version_id": version_id},
        capability_type, mount_key)
    _save_resources(path, resources)


def _provision_knowledge(
    api: Any, resources: dict[str, Any], path: Path, batch: str,
    candidate: dict[str, Any], files: dict[str, Any],
) -> None:
    target = resources.setdefault("knowledge", {})
    if "kb_id" not in target:
        response = _write(api, "POST", "knowledge/bases", {
            "name": f"{batch} product knowledge", "description": candidate["goal"][:1000],
            "indexing_mode": "economical", "retrieval_mode": "full_text", "rerank_enabled": False,
        }, "product knowledge base")
        target.update({"kb_id": _positive_id(response, "id", "product knowledge base"), "documents": {}, "configured": True})
        _save_resources(path, resources)
    if not target.get("configured"):
        response = _write(api, "PATCH", "knowledge/bases/update", {
            "kb_id": target["kb_id"], "payload": {
                "indexing_mode": "economical", "retrieval_mode": "full_text",
                "rerank_enabled": False}}, "product knowledge settings")
        if str(response.get("id")) != str(target["kb_id"]):
            raise ProvisionUnknownOutcome("product knowledge settings returned another knowledge base")
        target["configured"] = True
        _save_resources(path, resources)
    by_ref = {material["ref"]: material for material in candidate["materials"]}
    for ref in candidate["material_refs"]:
        document = target["documents"].setdefault(ref, {})
        if "document_id" not in document:
            material = by_ref[ref]
            filename = Path(files[ref]["path"]).name
            request_id = str(uuid.uuid4())
            query = urllib.parse.urlencode({"kb_id": target["kb_id"], "request_id": request_id})
            receipt = _write(_MultipartAdapter(api), "POST", f"knowledge/bases/documents/upload?{query}", {
                "kb_id": target["kb_id"], "title": f"{candidate['candidate_id']} {ref}",
                "filename": filename, "content": material["content"],
                "content_sha256": files[ref]["sha256"],
            }, f"product knowledge document {ref}")
            if (receipt.get("request_id") == request_id and receipt.get("status") == "REJECTED"
                    and isinstance(receipt.get("error_message"), str) and receipt["error_message"]):
                _save_resources(path, resources)
                raise ProvisionError(f"product knowledge document {ref} was rejected: {receipt['error_message']}")
            if (receipt.get("request_id") != request_id or receipt.get("status") != "SUCCEEDED"
                    or not isinstance(receipt.get("document"), dict)):
                raise ProvisionUnknownOutcome(f"product knowledge document {ref} has no final success receipt; query request {request_id}")
            response = receipt["document"]
            document.update({
                "document_id": _positive_id(response, "document_id", f"knowledge document {ref}"),
                "chunks": _positive_id(response, "chunks", f"knowledge document {ref}"),
                "source_version": _text(response, "source_version", f"knowledge document {ref}"),
                "fixture": files[ref],
            })
            _save_resources(path, resources)
    if "revision" not in target:
        response = _write(api, "POST", "knowledge/bases/revisions", {"kb_id": target["kb_id"]},
                          "product knowledge revision")
        target.update({"revision_id": _positive_id(response, "id", "product knowledge revision"),
                       "revision": _positive_id(response, "revision", "product knowledge revision")})
        _save_resources(path, resources)
    if not target.get("ready"):
        response = _write(api, "POST", "knowledge/bases/revisions/ready", {
            "kb_id": target["kb_id"], "revision": target["revision"], "payload": {
                "source_versions": [target["documents"][ref]["source_version"]
                                    for ref in candidate["material_refs"]],
                "policy": {"model_invocable": True, "user_invocable": False,
                           "allowed_operators": ["KEYWORD"], "freshness_policy": "PINNED",
                           "citation_required": True},
            }}, "product knowledge revision ready")
        if response.get("status") != "ready":
            raise ProvisionUnknownOutcome("product knowledge revision did not become ready")
        target["ready"] = True
        _save_resources(path, resources)
    if "capability_version_id" not in target:
        response = _write(api, "POST", "knowledge/bases/revisions/publish", {
            "kb_id": target["kb_id"], "revision": target["revision"]},
            "product knowledge revision publish")
        if response.get("status") != "published":
            raise ProvisionUnknownOutcome("product knowledge revision did not become published")
        target["published_revision_id"] = _positive_id(response, "id", "product knowledge publish")
        target["capability_version_id"] = _positive_id(
            response, "capability_version_id", "product knowledge publish")
        _save_resources(path, resources)
    if "capability_definition_id" not in target:
        target["capability_definition_id"] = _definition_for_version(
            api, "KNOWLEDGE_SOURCE", target["capability_version_id"])
        _save_resources(path, resources)
    _mount_product_capability(
        api, resources, path, "d04-knowledge", "knowledge:product", "KNOWLEDGE_SOURCE",
        target["capability_definition_id"], target["capability_version_id"], "knowledge_config",
        {"mode": "PINNED_REVISION", "pinned_revision_id": str(target["published_revision_id"])})


def _workflow_definition(batch: str) -> dict[str, Any]:
    return {
        "version": "1.0", "entry": "start",
        "meta": {"display_name": f"{batch} product HITL", "capability": {
            "model_invocable": True, "user_invocable": True, "input_schema": {}, "output_schema": {},
            "supports_hitl": True, "export_fields": ["messages"], "timeout_ms": 300_000,
            "concurrency_safe": True}},
        "nodes": [
            {"id": "start", "type": "start", "name": "开始"},
            {"id": "confirm", "type": "human_input", "name": "人工确认", "prompt": "请确认继续生成结果",
             "result_to": "state.confirmation", "timeout_seconds": 300,
             "card": {"card_id": "product_confirm", "type": "confirm", "title": "确认",
                      "body": "请确认继续", "actions": [{"id": "confirm", "label": "确认", "style": "primary"}]}},
            {"id": "output", "type": "output", "name": "完成", "message": "product-workflow-confirmed"},
            {"id": "end", "type": "end", "name": "结束"}],
        "edges": [{"from": "start", "to": "confirm"}, {"from": "confirm", "to": "output"},
                  {"from": "output", "to": "end"}],
    }


def _provision_workflow(api: Any, resources: dict[str, Any], path: Path, batch: str) -> None:
    target = resources.setdefault("workflow", {})
    workflow_id = f"{batch}-product-hitl"
    if "draft_revision" not in target:
        response = _write(api, "POST", "builder/workflows", {
            "workflow_id": workflow_id, "definition": _workflow_definition(batch)}, "product HITL workflow")
        if response.get("workflow_id") != workflow_id or response.get("status") != "draft":
            raise ProvisionUnknownOutcome("product HITL workflow create response is not its draft")
        target.update({"workflow_id": workflow_id,
                       "draft_revision": _positive_id(response, "revision", "product HITL workflow")})
        _save_resources(path, resources)
    if not target.get("validated"):
        response = _write(api, "POST", "builder/workflows/draft/validate",
                          {"workflow_id": workflow_id}, "product HITL workflow validate")
        if response.get("problems") != []:
            raise ProvisionError(f"product HITL workflow validation failed: {response.get('problems')!r}")
        target["validated"] = True
        _save_resources(path, resources)
    if "capability_version_id" not in target:
        response = _write(api, "POST", "builder/workflows/publish", {"workflow_id": workflow_id},
                          "product HITL workflow publish", {"If-Match": str(target["draft_revision"])})
        target["definition_version_id"] = _positive_id(
            response, "definition_version_id", "product HITL workflow publish")
        target["capability_version_id"] = _positive_id(
            response, "capability_version_id", "product HITL workflow publish")
        _save_resources(path, resources)
    if "instance_id" not in target:
        response = _write(api, "POST", "builder/workflows/install", {
            "workflow_id": workflow_id, "payload": {"config": {}, "custom_prompt": "",
                "bindings": {"capabilities": [], "workflows": [], "agents": []}}},
            "product HITL workflow install")
        target["instance_key"] = _text(response, "instance_id", "product HITL workflow install")
        target["installation_revision"] = _positive_id(
            response, "installation_revision", "product HITL workflow install")
        _save_resources(path, resources)
    if "instance_id" not in target:
        rows = _items(_read(api, "GET", "builder/workflows/instances", "product workflow instances"),
                      "product workflow instances")
        matches = [row for row in rows if isinstance(row, dict)
                   and row.get("instance_id") == target["instance_key"]
                   and row.get("workflow_id") == workflow_id]
        if len(matches) != 1:
            raise ProvisionError("installed product workflow must resolve to exactly one instance")
        row = matches[0]
        target["instance_id"] = _positive_id(row, "id", "product HITL workflow instance")
        if _positive_id(row, "definition_version_id", "product HITL workflow instance") != target["definition_version_id"]:
            raise ProvisionError("installed product workflow definition version differs from published version")
        _save_resources(path, resources)
    if "capability_definition_id" not in target:
        target["capability_definition_id"] = _definition_for_version(
            api, "WORKFLOW", target["capability_version_id"])
        _save_resources(path, resources)
    _mount_product_capability(
        api, resources, path, "d10-workflow", "workflow:product-hitl", "WORKFLOW",
        target["capability_definition_id"], target["capability_version_id"], "workflow_config",
        {"workflow_instance_id": str(target["instance_id"]),
         "workflow_instance_revision": target["definition_version_id"]}, priority=100)


def _configure_team_delegation(api: Any, resources: dict[str, Any], path: Path) -> None:
    target = resources.setdefault("team", {})
    parent = resources["agents"]["d11-parent"]
    children = [resources["agents"]["d11-child-a"], resources["agents"]["d11-child-b"]]
    child_ids = [str(child["id"]) for child in children]
    if target.get("delegation_targets") != child_ids:
        response = _write(api, "PATCH", "agents/update", {
            "agent_id": str(parent["id"]), "payload": {
                "delegation_config": {"enabled": True, "target_agent_ids": child_ids}}},
            "product delegation targets")
        if str(response.get("id")) != str(parent["id"]):
            raise ProvisionUnknownOutcome("product delegation update returned another agent")
        target["delegation_targets"] = child_ids
        _save_resources(path, resources)


def _provision_team(api: Any, resources: dict[str, Any], path: Path, batch: str) -> None:
    target = resources.setdefault("team", {})
    parent = resources["agents"]["d11-parent"]
    child_ids = target.get("delegation_targets")
    if not isinstance(child_ids, list) or len(child_ids) != 2:
        raise ProvisionError("product delegation targets are not ready")
    if "id" not in target:
        response = _write(api, "POST", "teams", {
            "name": f"{batch} product team", "description": "D11 two-child delegation evaluation",
            "status": "draft", "revision": 0,
            "members": [{"agent_id": str(parent["id"])}] + [{"agent_id": value} for value in child_ids],
            "primary_agent_id": str(parent["id"]),
        }, "product delegation team")
        target.update({"id": _text(response, "id", "product delegation team"),
                       "draft_revision": _positive_id(response, "revision", "product delegation team")})
        _save_resources(path, resources)
    if not target.get("published"):
        response = _write(api, "POST", "teams/publish", {"id": target["id"]},
                          "product delegation team publish")
        if response.get("status") != "published" or response.get("id") != target["id"]:
            raise ProvisionUnknownOutcome("product delegation team did not become published")
        members = response.get("members")
        if not isinstance(members, list) or {str(item.get("agent_id")) for item in members
                                             if isinstance(item, dict)} != {str(parent["id"]), *child_ids}:
            raise ProvisionUnknownOutcome("published product team lacks the requested members")
        target["published"] = True
        target["revision"] = _positive_id(response, "revision", "product delegation team publish")
        _save_resources(path, resources)


def _require_product_agents_mountable(resources: dict[str, Any]) -> None:
    required_mounts = {
        "d04-knowledge": "knowledge:product",
        "d10-workflow": "workflow:product-hitl",
    }
    for profile, mount_key in required_mounts.items():
        agent = resources["agents"][profile]
        if agent.get("published") and mount_key not in agent["mounts"]:
            raise ProvisionError(
                f"published agent {profile} lacks required Product mount {mount_key}; "
                "prepare a new isolated batch or an explicit Agent draft revision")
    parent = resources["agents"]["d11-parent"]
    child_ids = [
        str(resources["agents"][key]["id"])
        for key in ("d11-child-a", "d11-child-b")
    ]
    if parent.get("published") and resources.get("team", {}).get("delegation_targets") != child_ids:
        raise ProvisionError(
            "published agent d11-parent lacks the required Product delegation targets; "
            "prepare a new isolated batch or an explicit Agent draft revision")


def _publish_product_agents(
    api: Any, resources: dict[str, Any], path: Path, *, include_parent: bool,
) -> None:
    for key in resources["agents"]:
        if (key == "d11-parent") != include_parent:
            continue
        _publish_agent(api, resources, path, key)


def _validate_run_token_budget(run_max_total_tokens: int | None) -> int | None:
    """累计总量是唯一 Token 覆盖；拒绝无效额度，单次输出仍由模型控制。"""
    if (run_max_total_tokens is not None
            and (type(run_max_total_tokens) is not int or run_max_total_tokens <= 0)):
        raise ProvisionError("run_max_total_tokens must be a positive integer")
    return run_max_total_tokens


def _run_execution_config(run_max_total_tokens: int | None) -> dict[str, int] | None:
    return None if run_max_total_tokens is None else {"max_total_tokens": run_max_total_tokens}


def _require_token_budget_checkpoint(
    resources: dict[str, Any], run_max_total_tokens: int | None,
) -> None:
    if not resources:
        return
    if resources.get("run_max_total_tokens") != run_max_total_tokens:
        raise ProvisionError(
            "resources checkpoint run_max_total_tokens differs from this request; "
            "prepare a new isolated batch")
    expected = _run_execution_config(run_max_total_tokens)
    if expected is None:
        return
    agents = resources.get("agents")
    if not isinstance(agents, dict):
        return
    for key, agent in agents.items():
        if not isinstance(agent, dict):
            continue
        recorded = agent.get("execution_config")
        if agent.get("published") and recorded != expected:
            raise ProvisionError(
                f"published agent {key} lacks the requested token budget record; "
                "prepare a new isolated batch")
        if recorded not in (None, expected):
            raise ProvisionError(
                f"resources checkpoint token budget differs for agent {key}; "
                "prepare a new isolated batch")


def _configure_agent_token_budget(
    api: Any, resources: dict[str, Any], path: Path, run_max_total_tokens: int | None,
) -> None:
    expected = _run_execution_config(run_max_total_tokens)
    if expected is None:
        return
    _require_token_budget_checkpoint(resources, run_max_total_tokens)
    for key, agent in resources["agents"].items():
        if agent.get("execution_config") == expected:
            continue
        if agent.get("published"):
            raise ProvisionError(
                f"published agent {key} lacks the requested token budget record; "
                "prepare a new isolated batch")
        if "id" not in agent:
            raise ProvisionError(f"agent {key} is not ready for token budget configuration")
        response = _write(api, "PATCH", "agents/update", {
            "agent_id": str(agent["id"]),
            "payload": {"execution_config": expected},
        }, f"agents.{key}.run_max_total_tokens")
        if str(response.get("id")) != str(agent["id"]):
            raise ProvisionUnknownOutcome(
                f"agents.{key}.run_max_total_tokens returned another agent")
        agent["execution_config"] = dict(expected)
        _save_resources(path, resources)


def _capability_mapping(resources: dict[str, Any]) -> dict[str, Any]:
    knowledge_mount = resources["agents"]["d04-knowledge"]["mounts"].get("knowledge:product", {})
    workflow_mount = resources["agents"]["d10-workflow"]["mounts"].get("workflow:product-hitl", {})
    return {
        "code_execute": {"kind": "platform_tool", "endpoint": "capabilities/platform-tools",
                         "resource": resources["capabilities"]["platform_tools"].get("code_execute")},
        "list_artifacts": {"kind": "platform_tool", "endpoint": "capabilities/platform-tools",
                           "resource": resources["capabilities"]["platform_tools"].get("list_artifacts")},
        "read_context_reference": {"kind": "platform_tool", "endpoint": "capabilities/platform-tools",
                                   "resource": resources["capabilities"]["platform_tools"].get("read_context_reference")},
        "activate_skill": {"kind": "skill_mount", "profiles": ["d07-skills"],
                           "resources": [resources["capabilities"]["skills"].get("d07-skills:collect"),
                                         resources["capabilities"]["skills"].get("d07-skills:summarize")]},
        "knowledge_search": {"kind": "knowledge_mount", "profile": "d04-knowledge",
                             "handle": "knowledge:" + str(knowledge_mount.get("mount_id", "")),
                             "resource": resources.get("knowledge")},
        "workflow": {"kind": "workflow_mount", "profile": "d10-workflow",
                     "handle": "workflow:" + str(workflow_mount.get("mount_id", "")),
                     "resource": resources.get("workflow")},
        "delegation": {"kind": "team_and_agent_delegation", "profile": "d11-parent",
                       "resource": resources.get("team")},
        "cancel": {"kind": "runtime_control", "endpoint": "/chat/runs/cancel",
                   "profile": "d12-cancel"},
    }


def _instruction(candidate: dict[str, Any]) -> str:
    prefix = f"这是本地冻结合成评估。目标：{candidate['goal']}"
    # 当前用户表达由 task_message 按轮提供。只有引用化运输场景可在首轮携带原始资料，
    # 其余场景不能从 instruction 提前看到后续改口，也不能绕过挂载知识能力。
    if candidate["category"] == "D05":
        return prefix + "\n" + _reference_materials(candidate) + (
            "\n必须实际调用 code_execute：把 handbook 的逐字内容作为 input_files 中的 handbook.txt，"
            "读取该文件并输出完整正文，再输出 5000 个句点作为无语义传输填充。"
            "代码结果超过宿主 4096 字符引用阈值后，必须使用结果预览中的 reference_id 调用 "
            "read_context_reference 分页回读正文，再回答；不得只依据本消息中的预览作答。")
    if candidate["category"] == "D08":
        return prefix + (
            "\norders.csv 与 ledger.csv 已作为当前 Session 的真实附件上传。"
            "必须在同一次 code_execute 调用中通过 attachment_ids 选择这两个附件，"
            "根据返回的附件元数据 input_path 从 /input/data/<input_path> 读取文件，"
            "直接计算 orders 按 id 去重后的总额及与 ledger 的差额。"
            "不得把两份 CSV 正文复制到 input_files、其他工具参数或最终回答中。")
    return prefix


def _reference_materials(candidate: dict[str, Any]) -> str:
    by_ref = {material["ref"]: material for material in candidate["materials"]}
    return "\n\n".join(
        f"<material ref=\"{ref}\" kind=\"{by_ref[ref]['kind']}\">\n"
        f"{by_ref[ref]['content']}\n</material>" for ref in candidate["material_refs"])


def _build_cases(
    resources: dict[str, Any], selected: dict[str, dict[str, Any]]
) -> dict[str, dict[str, Any]]:
    profiles = _case_profiles()
    cases: dict[str, dict[str, Any]] = {}
    for index, category in enumerate(_CATEGORIES):
        candidate, profile = selected[category], profiles[category]
        bindings = [resources["files"][category][ref] for ref in candidate["material_refs"]]
        value = {
            "category": category, "candidate_id": candidate["candidate_id"],
            "variant": candidate["variant"], "profile": profile,
            "agent_id": str(resources["agents"][profile]["id"]),
            "user_id": str(resources["users"][index]["id"]),
            "task": {"goal": candidate["goal"], "user_turns": candidate["user_turns"]},
            "instruction": _instruction(candidate),
            "material_bindings": bindings,
            "required_capabilities": candidate["required_capabilities"],
            "capability_resources": {
                name: resources["capability_mapping"][name]
                for name in candidate["required_capabilities"]},
            "provenance": candidate["provenance"],
        }
        if category == "D04":
            value["knowledge"] = {key: resources["knowledge"][key] for key in
                                  ("kb_id", "published_revision_id", "capability_version_id")}
            value["knowledge"]["handle"] = resources["capability_mapping"]["knowledge_search"]["handle"]
            value["knowledge"]["documents"] = {
                ref: {"document_id": resources["knowledge"]["documents"][ref]["document_id"],
                      "source_version": resources["knowledge"]["documents"][ref]["source_version"]}
                for ref in candidate["material_refs"]}
            value["instruction"] += (
                "\n必须使用知识能力 " + value["knowledge"]["handle"]
                + " 检索已发布 revision，并在回答中保留实际知识证据。")
        elif category == "D05":
            value["reference_transport"] = {
                "source": "code_execute_result", "automatic_threshold_characters": 4096,
                "padding_characters": 5000, "reader": "read_context_reference",
                "source_complete": True,
            }
            value["supporting_capabilities"] = ["code_execute"]
        elif category == "D08":
            value["attachment_transport"] = {
                "source": "session_attachment",
                "upload_endpoint": "/chat/sessions/attachments/upload",
                "list_endpoint": "/chat/sessions/attachments",
                "refs": [binding["ref"] for binding in bindings],
            }
        elif category == "D10":
            value["workflow"] = {key: resources["workflow"][key] for key in
                                 ("workflow_id", "instance_id", "definition_version_id")}
            value["workflow"]["handle"] = resources["capability_mapping"]["workflow"]["handle"]
            value["instruction"] += (
                "\n必须调度工作流 " + value["workflow"]["handle"]
                + "，在 human_input 暂停后只用下一轮真实确认续接。")
        elif category == "D11":
            value["team"] = {"id": resources["team"]["id"],
                             "child_agent_ids": resources["team"]["delegation_targets"]}
        cases[category] = value
    return cases


def _readiness(resources: dict[str, Any]) -> dict[str, Any]:
    mapping = resources["capability_mapping"]
    for name in ("code_execute", "list_artifacts", "read_context_reference"):
        resource = mapping[name]["resource"]
        if not isinstance(resource, dict) or not resource.get("published") or not resource.get("deployment_key"):
            raise ProvisionError(f"required real platform capability is not ready: {name}")
    skills = mapping["activate_skill"]["resources"]
    if len(skills) != 2 or any(not isinstance(item, dict) or not item.get("published") for item in skills):
        raise ProvisionError("required real Skill capabilities are not ready")
    if "knowledge:product" not in resources["agents"]["d04-knowledge"]["mounts"]:
        raise ProvisionError("required real knowledge capability is not mounted")
    if "workflow:product-hitl" not in resources["agents"]["d10-workflow"]["mounts"]:
        raise ProvisionError("required real Workflow capability is not mounted")
    if not resources.get("team", {}).get("published") or len(resources["team"]["delegation_targets"]) != 2:
        raise ProvisionError("required real two-child delegation team is not ready")
    if resources["agents"]["d01-rules"]["mounts"]:
        raise ProvisionError("D01 rules comparison agent must have no mounted capabilities")
    return {
        "status": "ready", "categories": list(_CATEGORIES),
        "checks": {
            "isolated_users": len(resources["users"]) >= 12,
            "isolated_agents": all(case["agent_id"] for case in resources["cases"].values()),
            "platform_tools": ["code_execute", "list_artifacts", "read_context_reference"],
            "skills": ["collect", "summarize"], "knowledge_revision": "published",
            "workflow_hitl": "mounted", "delegation_children": 2,
            "cancel_endpoint": "/chat/runs/cancel", "material_files": sum(len(v) for v in resources["files"].values()),
            "reference_transport": {"source": "code_execute_result",
                                    "automatic_threshold_characters": 4096,
                                    "cases": ["D05"]},
            "attachment_transport": {"source": "session_attachment",
                                     "upload_endpoint": "/chat/sessions/attachments/upload",
                                     "list_endpoint": "/chat/sessions/attachments",
                                     "cases": ["D08"]},
        },
    }


def prepare_product(
    api: Any, platform: Any, root: Path, batch: str, model_id: str,
    dataset: dict[str, Any] | None = None, *, run_max_total_tokens: int | None = None,
) -> dict[str, Any]:
    """准备 D01-D12 所需真实资源，并返回显式 case/resource/readiness 映射。"""
    run_max_total_tokens = _validate_run_token_budget(run_max_total_tokens)
    root = Path(root)
    dataset = dataset if dataset is not None else _load_default_dataset(root)
    selected = _selected(dataset)
    checkpoint_path = root / "resources.json"
    checkpoint = json.loads(checkpoint_path.read_text(encoding="utf-8")) if checkpoint_path.exists() else {}
    if checkpoint.get("cases") and checkpoint.get("product_contract_version") != _PRODUCT_CONTRACT_VERSION:
        raise ProvisionError("legacy product resources cannot be reused; create a new attachment batch")
    if checkpoint.get("pending_operations"):
        raise ProvisionUnknownOutcome("resources.json contains an unresolved product write intent")
    _require_token_budget_checkpoint(checkpoint, run_max_total_tokens)
    # 知识库创建会冻结机构 Embedding 引用，即使采用关键词检索也必须先配置。
    # 在创建 Agent/用户之前检查，避免缺配置时留下整批无用资源。
    if not checkpoint.get("knowledge", {}).get("kb_id"):
        selections = _read(api, "GET", "settings/model-selections", "institution model selections")
        if not isinstance(selections, dict) or not selections.get("embedding_model_config_id"):
            raise ProvisionError("product knowledge requires an institution Embedding model selection")
    resources = provision(
        api, platform, root, _profiles(), batch, model_id, _UNUSED_FIXTURE_URL,
        user_count=12, publish_agents=False)
    resources_path = root / "resources.json"
    _JOURNAL_CONTEXT.set((resources, resources_path))
    if resources.get("pending_operations"):
        pending = resources["pending_operations"][0]
        raise ProvisionUnknownOutcome(
            "resources.json contains an unresolved product write intent: "
            f"{pending.get('label', 'unknown operation')}")
    if resources.get("product_contract_version") not in (None, _PRODUCT_CONTRACT_VERSION):
        raise ProvisionError("product resource contract version differs; create a new attachment batch")
    resources["product_contract_version"] = _PRODUCT_CONTRACT_VERSION
    _save_resources(resources_path, resources)
    budget_checkpoint_changed = False
    if "run_max_total_tokens" not in resources:
        resources["run_max_total_tokens"] = run_max_total_tokens
        budget_checkpoint_changed = True
    if budget_checkpoint_changed:
        _save_resources(resources_path, resources)
    _require_token_budget_checkpoint(resources, run_max_total_tokens)
    _require_product_agents_mountable(resources)
    signature = _digest({"dataset": dataset.get("fingerprint"), "selected": dataset["selected"],
                          "profiles": _profiles()})
    if resources.get("product_signature") not in (None, signature):
        raise ProvisionError("resources checkpoint product_signature does not match this dataset")
    resources["product_signature"] = signature
    _write_material_fixtures(root, selected, resources, resources_path)
    _provision_knowledge(api, resources, resources_path, batch, selected["D04"], resources["files"]["D04"])
    _provision_workflow(api, resources, resources_path, batch)
    _configure_agent_token_budget(
        api, resources, resources_path, run_max_total_tokens)
    # 委派目标必须先发布；父 Agent 保持草稿，待目标发布并写入委派配置后再发布。
    _publish_product_agents(api, resources, resources_path, include_parent=False)
    _configure_team_delegation(api, resources, resources_path)
    _publish_product_agents(api, resources, resources_path, include_parent=True)
    _provision_team(api, resources, resources_path, batch)
    resources["capability_mapping"] = _capability_mapping(resources)
    resources["cases"] = _build_cases(resources, selected)
    resources["readiness"] = _readiness(resources)
    _save_resources(resources_path, resources)
    return resources


__all__ = ["prepare_product"]
