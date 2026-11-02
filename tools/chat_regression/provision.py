"""为 chat 回归压测准备隔离的 API 资源。

导入本模块不会发起网络请求。调用方负责提供已认证的机构 API 与平台 API
客户端；调用 :func:`provision` 时才会执行准备请求。
"""

from __future__ import annotations

import base64
import contextvars
import hashlib
import json
import os
import pathlib
import re
from typing import Any, Protocol


class ApiClient(Protocol):
    """回归工具所需的最小 HTTP 客户端契约。"""

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> Any: ...


class ProvisionError(RuntimeError):
    """准备请求未能安全续接时抛出。"""


class ProvisionUnknownOutcome(ProvisionError):
    """写请求的结果未记录，禁止自动重试以免创建重复资源。"""


_BUILTIN_TOOLS = frozenset({"code_execute", "list_artifacts", "read_context_reference"})
_MCP_TOOLS = frozenset({"query_record", "write_record", "fault_probe"})
_BATCH_PATTERN = re.compile(r"[a-z0-9][a-z0-9-]{0,47}")
_JOURNAL_CONTEXT: contextvars.ContextVar[tuple[dict[str, Any], pathlib.Path] | None] = (
    contextvars.ContextVar("chat_regression_provision_journal", default=None)
)


def provision(
    api: ApiClient,
    platform: ApiClient,
    root: pathlib.Path,
    profiles: list[dict[str, Any]],
    batch: str,
    model_id: str,
    fixture_url: str,
    *,
    user_count: int = 50,
    publish_agents: bool = True,
) -> dict[str, Any]:
    """按所选套件创建终端用户和 profile 对应 Agent 的测试配置。

    所有可写资源以 ``root/resources.json`` 为检查点。每一个成功响应都会先校验
    所需标识再写入检查点；如果请求返回了无法识别的结果，或网络层在写请求后
    断开，函数会失败而不是猜测资源是否已创建并重试。
    """

    normalized_profiles = _validate_profiles(profiles)
    if isinstance(user_count, bool) or not isinstance(user_count, int) or user_count < 1:
        raise ProvisionError("user_count must be a positive integer")
    _validate_inputs(batch, model_id, fixture_url)
    root = pathlib.Path(root)
    resources_path = root / "resources.json"
    resources = _load_resources(resources_path, batch, model_id, fixture_url, normalized_profiles, user_count)
    _JOURNAL_CONTEXT.set((resources, resources_path))
    if resources["pending_operations"]:
        pending = resources["pending_operations"][0]
        raise ProvisionUnknownOutcome(
            "resources.json contains an unresolved write intent; reconcile it before prepare: "
            f"{pending.get('label', 'unknown operation')}")

    _validate_platform_model(platform, model_id, resources, resources_path)
    _validate_tenant_model(api, model_id, resources, resources_path)
    _validate_published_system_agents(platform, resources, resources_path)

    existing_users = {
        user.get("key")
        for user in resources["users"]
        if isinstance(user, dict) and isinstance(user.get("key"), str)
    }
    for index in range(1, user_count + 1):
        user_key = f"user-{index:03d}"
        if user_key in existing_users:
            continue
        response = _write(api, "POST", "chat/end-users", {
            "display_name": f"{batch} end user {index:03d}",
        }, f"users.{user_key}")
        resources["users"].append({
            "key": user_key,
            "id": _positive_id(response, "id", f"users.{user_key}"),
            "display_name": _text(response, "display_name", f"users.{user_key}"),
        })
        existing_users.add(user_key)
        _save_resources(resources_path, resources)

    required_mcp = sorted({
        tool
        for profile in normalized_profiles
        for tool in profile["tools"]
        if tool in _MCP_TOOLS
    })
    connector = None
    discovered_tools = {}
    if required_mcp:
        connector = _provision_mcp_connector(api, resources, resources_path, batch, fixture_url)
        discovered_tools = _discover_mcp_tools(api, resources, resources_path, connector)
    missing_mcp = sorted(set(required_mcp) - set(discovered_tools))
    if missing_mcp:
        raise ProvisionError(
            "fixture MCP discovery lacks requested tools: " + ", ".join(missing_mcp))

    required_builtin = sorted({
        tool
        for profile in normalized_profiles
        for tool in profile["tools"]
        if tool in _BUILTIN_TOOLS
    })
    builtin = _load_builtin_tools(api, resources, resources_path) if required_builtin else {}
    missing_builtin = sorted(set(required_builtin) - set(builtin))
    if missing_builtin:
        raise ProvisionError(
            "published platform catalog lacks requested tools: " + ", ".join(missing_builtin))

    for tool_name in required_mcp:
        _provision_mcp_tool(
            api, resources, resources_path, batch, connector, discovered_tools[tool_name])
    for tool_name in required_builtin:
        _provision_builtin_tool(api, resources, resources_path, batch, builtin[tool_name])

    for profile in normalized_profiles:
        _provision_agent(
            api, resources, resources_path, batch, model_id, profile,
            publish_agent=publish_agents)

    return resources


def _validate_inputs(batch: str, model_id: str, fixture_url: str) -> None:
    if not isinstance(batch, str) or not _BATCH_PATTERN.fullmatch(batch):
        raise ProvisionError("batch must be a lowercase hyphenated identifier of at most 48 characters")
    if not isinstance(model_id, str) or not model_id.isdecimal() or int(model_id) <= 0:
        raise ProvisionError("model_id must be a positive platform model ID")
    if not isinstance(fixture_url, str) or not fixture_url.startswith(("http://", "https://")):
        raise ProvisionError("fixture_url must be an absolute HTTP(S) URL")


def _validate_profiles(profiles: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not isinstance(profiles, list) or not profiles:
        raise ProvisionError("profiles must contain at least one entry")
    result: list[dict[str, Any]] = []
    seen_profiles: set[str] = set()
    for raw in profiles:
        if not isinstance(raw, dict):
            raise ProvisionError("each profile must be an object")
        key = raw.get("key")
        prompt = raw.get("prompt")
        skills = raw.get("skills")
        tools = raw.get("tools")
        if not isinstance(key, str) or not _BATCH_PATTERN.fullmatch(key):
            raise ProvisionError("profile key must be a lowercase hyphenated identifier")
        if key in seen_profiles:
            raise ProvisionError(f"profile key is duplicated: {key}")
        if not isinstance(prompt, str) or not prompt.strip():
            raise ProvisionError(f"profile {key} requires a non-empty prompt")
        if not isinstance(skills, list) or not isinstance(tools, list):
            raise ProvisionError(f"profile {key} requires skills and tools lists")
        tool_names: list[str] = []
        for tool in tools:
            if not isinstance(tool, str) or tool not in _BUILTIN_TOOLS | _MCP_TOOLS:
                raise ProvisionError(f"profile {key} has unsupported tool: {tool!r}")
            if tool in tool_names:
                raise ProvisionError(f"profile {key} repeats tool {tool}")
            tool_names.append(tool)
        skill_values: list[dict[str, str]] = []
        seen_skills: set[str] = set()
        for skill in skills:
            if not isinstance(skill, dict):
                raise ProvisionError(f"profile {key} has a non-object skill")
            skill_key = skill.get("key")
            instructions = skill.get("instructions")
            if not isinstance(skill_key, str) or not _BATCH_PATTERN.fullmatch(skill_key):
                raise ProvisionError(f"profile {key} has an invalid skill key")
            if skill_key in seen_skills:
                raise ProvisionError(f"profile {key} repeats skill {skill_key}")
            if not isinstance(instructions, str) or not instructions.strip():
                raise ProvisionError(f"profile {key} skill {skill_key} has no instructions")
            seen_skills.add(skill_key)
            skill_values.append({"key": skill_key, "instructions": instructions})
        seen_profiles.add(key)
        result.append({"key": key, "prompt": prompt, "skills": skill_values, "tools": tool_names})
    return result


def _load_resources(
    path: pathlib.Path,
    batch: str,
    model_id: str,
    fixture_url: str,
    profiles: list[dict[str, Any]],
    user_count: int,
) -> dict[str, Any]:
    signature = _digest(profiles)
    if not path.exists():
        return {
            "schema_version": 1,
            "batch": batch,
            "model_id": model_id,
            "fixture_url": fixture_url,
            "profile_signature": signature,
            "user_count": user_count,
            "users": [],
            "agents": {},
            "capabilities": {"skills": {}, "mcp_tools": {}, "platform_tools": {}},
            "connector": {},
            "validations": {},
            "pending_operations": [],
        }
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ProvisionError(f"cannot read existing resources checkpoint: {path}") from error
    if not isinstance(value, dict):
        raise ProvisionError("resources checkpoint must be a JSON object")
    expected = {
        "schema_version": 1,
        "batch": batch,
        "model_id": model_id,
        "fixture_url": fixture_url,
        "profile_signature": signature,
        "user_count": user_count,
    }
    for field, wanted in expected.items():
        if value.get(field) != wanted:
            raise ProvisionError(f"resources checkpoint {field} does not match this provision request")
    if not isinstance(value.get("users"), list):
        raise ProvisionError("resources checkpoint users must be a list")
    for field, empty in {"agents": {}, "connector": {}, "validations": {}}.items():
        if not isinstance(value.get(field), dict):
            raise ProvisionError(f"resources checkpoint {field} must be an object")
        value.setdefault(field, empty)
    if not isinstance(value.get("pending_operations", []), list):
        raise ProvisionError("resources checkpoint pending_operations must be a list")
    value.setdefault("pending_operations", [])
    capabilities = value.get("capabilities")
    if not isinstance(capabilities, dict):
        raise ProvisionError("resources checkpoint capabilities must be an object")
    for field in ("skills", "mcp_tools", "platform_tools"):
        if not isinstance(capabilities.get(field), dict):
            raise ProvisionError(f"resources checkpoint capabilities.{field} must be an object")
    return value


def _save_resources(
    path: pathlib.Path, resources: dict[str, Any], *, clear_pending: bool = True
) -> None:
    if clear_pending:
        resources["pending_operations"] = []
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(
        json.dumps(resources, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def _validate_platform_model(
    platform: ApiClient, model_id: str, resources: dict[str, Any], path: pathlib.Path
) -> None:
    if resources["validations"].get("platform_model") == model_id:
        return
    response = _read(platform, "GET", "admin/models", "platform model validation")
    rows = _list_response(response, "platform model validation")
    model = next((item for item in rows if str(item.get("id")) == model_id), None)
    if not isinstance(model, dict) or model.get("kind") != "LLM" or model.get("enabled") is not True:
        raise ProvisionError(f"platform model {model_id} is not an enabled LLM")
    resources["validations"]["platform_model"] = model_id
    _save_resources(path, resources)


def _validate_tenant_model(
    api: ApiClient, model_id: str, resources: dict[str, Any], path: pathlib.Path
) -> None:
    if resources["validations"].get("tenant_model") == model_id:
        return
    response = _read(api, "GET", "settings/available-models", "tenant model validation")
    rows = _list_response(response, "tenant model validation")
    if not any(isinstance(item, dict) and str(item.get("id")) == model_id
               and item.get("kind") == "LLM" for item in rows):
        raise ProvisionError(f"model {model_id} is not available to this tenant")
    resources["validations"]["tenant_model"] = model_id
    _save_resources(path, resources)


def _validate_published_system_agents(
    platform: ApiClient, resources: dict[str, Any], path: pathlib.Path
) -> None:
    if resources["validations"].get("task_context_system_agent"):
        return
    rows = _list_response(
        _read(platform, "GET", "admin/system-agents", "task-context system agent validation"),
        "task-context system agent validation")
    agent = next((item for item in rows if isinstance(item, dict)
                  and item.get("purpose") == "task-context"
                  and item.get("status") == "published"
                  and item.get("published") is not None), None)
    if not isinstance(agent, dict):
        raise ProvisionError(
            "no published task-context system agent is available; configure one before prepare")
    resources["validations"]["task_context_system_agent"] = {
        "id": _text(agent, "id", "task-context system agent"),
        "revision": _positive_id(agent, "revision", "task-context system agent"),
        "published_version": _positive_id(agent, "published_version", "task-context system agent"),
    }
    _save_resources(path, resources)


def _provision_mcp_connector(
    api: ApiClient,
    resources: dict[str, Any],
    path: pathlib.Path,
    batch: str,
    fixture_url: str,
) -> dict[str, Any]:
    connector = resources["connector"]
    if connector.get("instance_id"):
        return connector
    credential = os.environ.get("MCP_FIXTURE_TOKEN")
    body: dict[str, Any] = {
        "executor_type": "mcp",
        "name": f"{batch} regression MCP fixture",
        "base_url": fixture_url,
        "auth_type": "bearer" if credential else "none",
        "capability_mapping": {},
        "headers": {},
    }
    if credential:
        body["auth_credential"] = credential
    response = _write(api, "POST", "connectors/instances", body, "connector")
    connector.update({
        "id": _positive_id(response, "id", "connector"),
        "instance_id": _text(response, "instance_id", "connector"),
        "fixture_url": fixture_url,
    })
    _save_resources(path, resources)
    return connector


def _discover_mcp_tools(
    api: ApiClient,
    resources: dict[str, Any],
    path: pathlib.Path,
    connector: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    discovery = connector.get("discovery")
    if not isinstance(discovery, dict):
        discovery = _read(
            api,
            "GET",
            f"connectors/instances/mcp-tools?instanceId={connector['instance_id']}",
            "MCP tool discovery",
        )
        if not isinstance(discovery, dict):
            raise ProvisionUnknownOutcome("MCP tool discovery returned a non-object response")
        connector["discovery"] = discovery
        _save_resources(path, resources)
    items = _list_response(discovery.get("items"), "MCP tool discovery items")
    result: dict[str, dict[str, Any]] = {}
    for item in items:
        if not isinstance(item, dict):
            raise ProvisionError("MCP tool discovery contains a non-object tool")
        name = item.get("name")
        if not isinstance(name, str) or not name:
            raise ProvisionError("MCP tool discovery returned a tool without name")
        if name in result:
            raise ProvisionError(f"MCP tool discovery returned duplicate tool {name}")
        result[name] = item
    return result


def _load_builtin_tools(
    api: ApiClient, resources: dict[str, Any], path: pathlib.Path
) -> dict[str, dict[str, Any]]:
    cached = resources["validations"].get("platform_tools")
    if not isinstance(cached, list):
        cached = _read(api, "GET", "capabilities/platform-tools", "platform tool catalog")
        if not isinstance(cached, list):
            raise ProvisionUnknownOutcome("platform tool catalog returned a non-list response")
        resources["validations"]["platform_tools"] = cached
        _save_resources(path, resources)
    result: dict[str, dict[str, Any]] = {}
    for item in cached:
        if not isinstance(item, dict) or not isinstance(item.get("name"), str):
            raise ProvisionError("platform tool catalog contains an invalid entry")
        result[item["name"]] = item
    return result


def _provision_mcp_tool(
    api: ApiClient,
    resources: dict[str, Any],
    path: pathlib.Path,
    batch: str,
    connector: dict[str, Any],
    discovered: dict[str, Any],
) -> None:
    name = _text(discovered, "name", "MCP tool")
    target = resources["capabilities"]["mcp_tools"].setdefault(name, {})
    if "definition_id" not in target:
        definition = _create_definition(api, batch, "TOOL", f"mcp-{name}",
                                        discovered.get("title") or name,
                                        discovered.get("description") or name)
        target.update({"definition_id": definition["id"], "definition_revision": definition["revision"]})
        _save_resources(path, resources)
    if "version_id" not in target:
        descriptor = _mcp_descriptor(discovered)
        version = _create_tool_version(
            api, target["definition_id"], f"{batch}:mcp:{name}:v1",
            discovered.get("title") or name, discovered.get("description") or name, descriptor,
            f"capabilities.mcp_tools.{name}")
        target["version_id"] = version["id"]
        _save_resources(path, resources)
    _publish_version(api, target, resources, path, f"capabilities.mcp_tools.{name}")
    if "deployment_key" not in target:
        deployment = _create_deployment(
            api, f"{batch}-mcp-{name}", target["version_id"], "MCP", connector["instance_id"])
        target.update({"deployment_key": deployment["deployment_key"], "deployment_revision": deployment["revision"]})
        _save_resources(path, resources)


def _provision_builtin_tool(
    api: ApiClient,
    resources: dict[str, Any],
    path: pathlib.Path,
    batch: str,
    catalog_entry: dict[str, Any],
) -> None:
    name = _text(catalog_entry, "name", "platform tool")
    target = resources["capabilities"]["platform_tools"].setdefault(name, {})
    if "definition_id" not in target:
        definition = _create_definition(
            api, batch, "TOOL", f"platform-{name}", name,
            _text(catalog_entry, "description", f"platform tool {name}"))
        target.update({"definition_id": definition["id"], "definition_revision": definition["revision"]})
        _save_resources(path, resources)
    if "version_id" not in target:
        descriptor = catalog_entry.get("descriptor")
        if not isinstance(descriptor, dict):
            raise ProvisionError(f"platform tool {name} has no descriptor")
        version = _create_tool_version(
            api, target["definition_id"], f"{batch}:platform:{name}:v1", name,
            _text(catalog_entry, "description", f"platform tool {name}"), descriptor,
            f"capabilities.platform_tools.{name}",
            _text(catalog_entry, "content_hash", f"platform tool {name}"))
        target["version_id"] = version["id"]
        _save_resources(path, resources)
    _publish_version(api, target, resources, path, f"capabilities.platform_tools.{name}")
    if "deployment_key" not in target:
        deployment = _create_deployment(api, f"{batch}-platform-{name}", target["version_id"], "PLATFORM", name)
        target.update({"deployment_key": deployment["deployment_key"], "deployment_revision": deployment["revision"]})
        _save_resources(path, resources)


def _provision_agent(
    api: ApiClient,
    resources: dict[str, Any],
    path: pathlib.Path,
    batch: str,
    model_id: str,
    profile: dict[str, Any],
    *,
    publish_agent: bool,
) -> None:
    key = profile["key"]
    target = resources["agents"].setdefault(key, {"profile": profile, "mounts": {}})
    if target.get("profile") != profile:
        raise ProvisionError(f"resources checkpoint profile differs for agent {key}")
    if "id" not in target:
        response = _write(api, "POST", "agents", {
            "agent_key": f"{batch}-{key}",
            "name": f"{batch} {key}",
            "system_prompt": profile["prompt"],
            "model_config_id": model_id,
            "config": {},
        }, f"agents.{key}")
        target["id"] = _positive_id(response, "id", f"agents.{key}")
        _save_resources(path, resources)

    for skill in profile["skills"]:
        capability = resources["capabilities"]["skills"].get(_skill_resource_key(key, skill["key"]))
        if not capability or "definition_id" not in capability or "version_id" not in capability:
            _provision_skill(
                api, resources, path, batch, key, skill, None)
            capability = resources["capabilities"]["skills"][_skill_resource_key(key, skill["key"])]
        _mount_skill(api, target, capability, f"skill:{skill['key']}", path, resources)

    for tool in profile["tools"]:
        collection = "platform_tools" if tool in _BUILTIN_TOOLS else "mcp_tools"
        capability = resources["capabilities"][collection][tool]
        _mount_tool(api, target, capability, f"tool:{collection}:{tool}", path, resources)

    if publish_agent:
        _publish_agent(api, resources, path, key)


def _publish_agent(
    api: ApiClient,
    resources: dict[str, Any],
    path: pathlib.Path,
    key: str,
) -> None:
    target = resources["agents"].get(key)
    if not isinstance(target, dict) or "id" not in target:
        raise ProvisionError(f"agent {key} is not ready to publish")
    if target.get("published"):
        return
    response = _write(
        api, "POST", "agents/publish", {"agent_id": target["id"]},
        f"agents.{key}.publish")
    if str(response.get("agent_id")) != str(target["id"]):
        raise ProvisionUnknownOutcome(f"agents.{key}.publish returned another agent")
    target["published"] = True
    _save_resources(path, resources)


def _provision_skill(
    api: ApiClient,
    resources: dict[str, Any],
    path: pathlib.Path,
    batch: str,
    profile_key: str,
    skill: dict[str, str],
    conflict_group: str | None,
) -> None:
    resource_key = _skill_resource_key(profile_key, skill["key"])
    target = resources["capabilities"]["skills"].setdefault(resource_key, {})
    if "definition_id" not in target:
        definition = _create_definition(
            api, batch, "SKILL", f"{profile_key}-{skill['key']}",
            f"{profile_key} {skill['key']}", skill["instructions"][:1000])
        target.update({"definition_id": definition["id"], "definition_revision": definition["revision"]})
        _save_resources(path, resources)
    if "version_id" not in target:
        package_name = _skill_package_name(batch, profile_key, skill["key"])
        skill_md = _skill_markdown(package_name, skill["instructions"])
        version = _write(api, "POST", "capabilities/definitions/skill-versions/import-directory", {
            "definition_id": target["definition_id"],
            "payload": {
                "version_key": "v1",
                "model_invocable": True,
                "user_invocable": False,
                "activation_mode": "ON_DEMAND",
                "conflict_group": conflict_group,
                "required_capabilities": [],
                "files": {"SKILL.md": base64.b64encode(skill_md.encode("utf-8")).decode("ascii")},
            },
        }, f"capabilities.skills.{resource_key}.version")
        target["version_id"] = _positive_id(version, "id", f"capabilities.skills.{resource_key}.version")
        _save_resources(path, resources)
    _publish_version(api, target, resources, path, f"capabilities.skills.{resource_key}")


def _create_definition(
    api: ApiClient, batch: str, capability_type: str, suffix: str, name: str, description: str
) -> dict[str, Any]:
    response = _write(api, "POST", "capabilities/definitions", {
        "capability_type": capability_type,
        "capability_key": _capability_key(batch, capability_type.lower(), suffix),
        "name": name[:255],
        "description": description[:4000],
    }, f"create {capability_type} definition {suffix}")
    return {
        "id": _positive_id(response, "id", f"create {capability_type} definition"),
        "revision": _positive_id(response, "revision", f"create {capability_type} definition"),
    }


def _create_tool_version(
    api: ApiClient,
    definition_id: int,
    version_key: str,
    name: str,
    description: str,
    descriptor: dict[str, Any],
    label: str,
    content_hash: str | None = None,
) -> dict[str, Any]:
    content_hash = content_hash or _digest(descriptor)
    response = _write(api, "POST", "capabilities/definitions/versions", {
        "definition_id": definition_id,
        "payload": {
            "version_key": version_key[:128],
            "name": name[:255],
            "description": description[:4000],
            "content_hash": content_hash,
            "descriptor": descriptor,
        },
    }, f"{label}.version")
    return {"id": _positive_id(response, "id", f"{label}.version")}


def _publish_version(
    api: ApiClient,
    target: dict[str, Any],
    resources: dict[str, Any],
    path: pathlib.Path,
    label: str,
) -> None:
    if target.get("published"):
        return
    definition = _read(
        api,
        "GET",
        f"capabilities/definitions/detail?definitionId={target['definition_id']}",
        f"{label} definition revision",
    )
    revision = _positive_id(definition, "revision", f"{label} definition revision")
    validation = _write(api, "POST", "capabilities/definitions/versions/validate", {
        "definition_id": target["definition_id"], "version_id": target["version_id"],
    }, f"{label}.validate")
    if validation.get("valid") is not True:
        raise ProvisionError(f"{label} descriptor validation failed: {validation.get('errors')!r}")
    _save_resources(path, resources)
    response = _write(
        api,
        "POST",
        "capabilities/definitions/versions/publish",
        {"definition_id": target["definition_id"], "version_id": target["version_id"]},
        f"{label}.publish",
        {"If-Match": str(revision)},
    )
    if _positive_id(response, "id", f"{label}.publish") != target["version_id"]:
        raise ProvisionUnknownOutcome(f"{label}.publish returned another version")
    target["published"] = True
    target["definition_revision"] = revision + 1
    _save_resources(path, resources)


def _create_deployment(
    api: ApiClient, deployment_key: str, version_id: int, provider_type: str, provider_ref: str
) -> dict[str, Any]:
    response = _write(api, "POST", "capabilities/deployments", {
        "deployment_key": deployment_key[:128],
        "tool_version_id": version_id,
        "provider_type": provider_type,
        "provider_ref": provider_ref,
        "environment": "PRODUCTION",
        "status": "ACTIVE",
    }, f"deployment {deployment_key}")
    return {
        "deployment_key": _text(response, "deployment_key", f"deployment {deployment_key}"),
        "revision": _positive_id(response, "revision", f"deployment {deployment_key}"),
    }


def _resolve_platform_tool(api: ApiClient, catalog_entry: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    name = _text(catalog_entry, "name", "platform tool")
    definitions = _read(
        api, "GET", "capabilities/definitions?capability_type=TOOL&status=PUBLISHED",
        f"platform tool {name} definitions")
    rows = _list_response(definitions, f"platform tool {name} definitions")
    definition = next((item for item in rows if isinstance(item, dict)
                       and item.get("capability_key") == f"tool:{name}"), None)
    if not isinstance(definition, dict):
        raise ProvisionError(f"platform tool {name} has no published capability definition")
    definition_id = _positive_id(definition, "id", f"platform tool {name} definition")
    versions = _read(
        api,
        "GET",
        f"capabilities/definitions/versions?definitionId={definition_id}&status=PUBLISHED",
        f"platform tool {name} versions",
    )
    expected_hash = _text(catalog_entry, "content_hash", f"platform tool {name}")
    version = next((item for item in _list_response(versions, f"platform tool {name} versions")
                    if isinstance(item, dict) and item.get("content_hash") == expected_hash), None)
    if not isinstance(version, dict):
        raise ProvisionError(f"platform tool {name} has no published version matching catalog hash")
    return definition, version


def _mount_skill(
    api: ApiClient,
    agent: dict[str, Any],
    capability: dict[str, Any],
    mount_key: str,
    path: pathlib.Path,
    resources: dict[str, Any],
) -> None:
    if mount_key in agent["mounts"]:
        return
    response = _write(api, "POST", "agents/capability-mounts", {
        "agent_id": agent["id"],
        "payload": {
            "capability_type": "SKILL",
            "capability_definition_id": str(capability["definition_id"]),
            "capability_version_id": str(capability["version_id"]),
            "skill_config": {"activation_mode": "ON_DEMAND", "token_budget": 4096},
            "priority": 0,
        },
    }, f"agent {agent['id']} {mount_key}")
    agent["mounts"][mount_key] = _mount_from_response(response, capability, "SKILL", mount_key)
    _save_resources(path, resources)


def _mount_tool(
    api: ApiClient,
    agent: dict[str, Any],
    capability: dict[str, Any],
    mount_key: str,
    path: pathlib.Path,
    resources: dict[str, Any],
) -> None:
    if mount_key in agent["mounts"]:
        return
    response = _write(api, "POST", "agents/capability-mounts", {
        "agent_id": agent["id"],
        "payload": {
            "capability_type": "TOOL",
            "capability_definition_id": str(capability["definition_id"]),
            "capability_version_id": str(capability["version_id"]),
            "tool_config": {
                "tool_deployment_id": capability["deployment_key"],
                "tool_deployment_revision": capability["deployment_revision"],
            },
            "priority": 0,
        },
    }, f"agent {agent['id']} {mount_key}")
    agent["mounts"][mount_key] = _mount_from_response(response, capability, "TOOL", mount_key)
    _save_resources(path, resources)


def _mount_from_response(
    response: Any, capability: dict[str, Any], capability_type: str, label: str
) -> dict[str, Any]:
    if not isinstance(response, dict):
        raise ProvisionUnknownOutcome(f"{label} mount returned a non-object response")
    mounts = _list_response(response.get("mounts"), f"{label} mount response")
    matching = [mount for mount in mounts if isinstance(mount, dict)
                and mount.get("capability_type") == capability_type
                and mount.get("capability_definition_id") == str(capability["definition_id"])
                and mount.get("capability_version_id") == str(capability["version_id"])]
    if len(matching) != 1:
        raise ProvisionUnknownOutcome(f"{label} mount response cannot identify its created mount")
    mount = matching[0]
    return {
        "mount_id": _text(mount, "mount_id", f"{label} mount"),
        "revision": _positive_id(mount, "revision", f"{label} mount"),
    }


def _mcp_descriptor(discovered: dict[str, Any]) -> dict[str, Any]:
    name = _text(discovered, "name", "MCP tool")
    input_schema = discovered.get("input_schema")
    if not isinstance(input_schema, dict):
        raise ProvisionError(f"MCP tool {name} has no object input_schema")
    effect, idempotent = {
        "query_record": ("READ_ONLY", True),
        # request_key 的业务去重由夹具实现，不向 Runtime 声明 MCP 崩溃重放能力。
        "write_record": ("NON_IDEMPOTENT_WRITE", False),
        "fault_probe": ("NON_IDEMPOTENT_WRITE", False),
    }[name]
    descriptor: dict[str, Any] = {
        "model_invocable": True,
        "user_invocable": False,
        "tool_name": name,
        "input_schema": input_schema,
        "effect": effect,
        "idempotent": idempotent,
        "confirmation_policy": "NEVER",
        # 测试夹具的只读查询由宿主显式授予资格，不依赖远端 annotations。
        "parallel_safe": name == "query_record",
        "timeout_ms": 30_000,
        "sensitive_fields": [],
        "provider_requirements": [],
    }
    for source, target in (("title", "title"), ("output_schema", "output_schema"),
                           ("annotations", "annotations")):
        value = discovered.get(source)
        if value is not None:
            descriptor[target] = value
    icons = discovered.get("icons")
    if icons is not None:
        if not isinstance(icons, list):
            raise ProvisionError(f"MCP tool {name} icons must be a list")
        descriptor["icons"] = [_codec_icon(icon, name) for icon in icons]
    return descriptor


def _codec_icon(icon: Any, tool_name: str) -> dict[str, Any]:
    if not isinstance(icon, dict) or not isinstance(icon.get("src"), str):
        raise ProvisionError(f"MCP tool {tool_name} has an invalid icon")
    value: dict[str, Any] = {"src": icon["src"]}
    if icon.get("mime_type") is not None:
        value["mimeType"] = icon["mime_type"]
    if icon.get("sizes") is not None:
        value["sizes"] = icon["sizes"]
    if icon.get("theme") is not None:
        value["theme"] = icon["theme"]
    return value


def _skill_markdown(name: str, instructions: str) -> str:
    description = instructions.strip().replace("\r", " ").replace("\n", " ")[:1024]
    return (
        f"---\nname: {name}\ndescription: {json.dumps(description, ensure_ascii=False)}\n"
        f"metadata:\n  version: v1\n---\n\n{instructions.strip()}\n")


def _skill_resource_key(profile_key: str, skill_key: str) -> str:
    return f"{profile_key}:{skill_key}"


def _skill_package_name(batch: str, profile_key: str, skill_key: str) -> str:
    value = f"{batch}-{profile_key}-{skill_key}"
    return value if len(value) <= 64 else value[:55].rstrip("-") + "-" + _digest(value)[:8]


def _capability_key(batch: str, kind: str, suffix: str) -> str:
    value = f"{batch}:{kind}:{suffix}"
    return value if len(value) <= 128 else value[:119] + ":" + _digest(value)[:8]


def _read(api: ApiClient, method: str, path: str, label: str) -> Any:
    try:
        return api.request(method, _api_path(path))
    except Exception as error:
        raise ProvisionError(f"{label} request failed: {error}") from error


def _write(
    api: ApiClient,
    method: str,
    path: str,
    body: dict[str, Any],
    label: str,
    headers: dict[str, str] | None = None,
) -> dict[str, Any]:
    journal = _JOURNAL_CONTEXT.get()
    if journal is None:
        raise ProvisionError("write request is outside an active provision journal")
    resources, resources_path = journal
    if resources["pending_operations"]:
        raise ProvisionUnknownOutcome(
            "a previous write intent is unresolved; do not issue another write request")
    request_path = _api_path(path)
    resources["pending_operations"] = [{
        "label": label,
        "method": method,
        "path": request_path,
        "body_sha256": _digest(body),
    }]
    _save_resources(resources_path, resources, clear_pending=False)
    try:
        response = api.request(method, request_path, body=body, headers=headers)
    except Exception as error:
        raise ProvisionUnknownOutcome(
            f"{label} write request failed; do not retry until resources.json is reconciled: {error}") from error
    if not isinstance(response, dict):
        raise ProvisionUnknownOutcome(f"{label} write returned a non-object response")
    return response


def _positive_id(response: dict[str, Any], field: str, label: str) -> int:
    value = response.get(field)
    try:
        result = int(value)
    except (TypeError, ValueError) as error:
        raise ProvisionUnknownOutcome(f"{label} response has no positive {field}") from error
    if result <= 0:
        raise ProvisionUnknownOutcome(f"{label} response has no positive {field}")
    return result


def _text(response: dict[str, Any], field: str, label: str) -> str:
    value = response.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ProvisionUnknownOutcome(f"{label} response has no non-empty {field}")
    return value


def _list_response(response: Any, label: str) -> list[Any]:
    if not isinstance(response, list):
        raise ProvisionError(f"{label} response must be a list")
    return response


def _digest(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _api_path(path: str) -> str:
    if not isinstance(path, str) or not path:
        raise ProvisionError("API path must be non-empty")
    return path if path.startswith("/") else "/" + path
