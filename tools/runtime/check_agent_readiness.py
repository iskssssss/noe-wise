#!/usr/bin/env python3
"""只读核对目标 Agent 的 Tool 组合是否可发布。

必需 Tool 由重复的 ``--required-tool`` 显式给出；脚本不假设所有 Agent
共享同一能力集合。它通过机构服务的 Agent、CapabilityMount、平台 Tool、
CapabilityDefinition/Version 和 ToolDeployment 只读接口，区分 Agent/部署未
发布、目录与已发布版本的 schema 漂移、以及缺少或失效的挂载。Skill、
Workflow 和 Knowledge 挂载不属于此检查。
"""

from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
import sys
from typing import Any

ROOT = Path(__file__).resolve().parents[2]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """认证请求不跟随重定向，避免凭据外发和未知写入的自动重发。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class ReadinessError(RuntimeError):
    """不包含远端响应正文的可公开错误。"""


class HttpFailure(ReadinessError):
    def __init__(self, method: str, path: str, status: int | str):
        super().__init__(f"{method} {path.split('?', 1)[0]} failed: {status}")


def load_environment(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if path.exists():
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            key, value = key.strip(), value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
                value = value[1:-1]
            values[key] = value
    values.update(os.environ)
    return values


class Api:
    def __init__(self, base: str, token: str | None = None, platform: bool = False):
        self.base = base.rstrip("/")
        self.headers = {"Content-Type": "application/json"}
        if token:
            header = "X-Platform-Token" if platform else "Authorization"
            self.headers[header] = "Bearer " + token

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> Any:
        if not path.startswith("/") or path.startswith("//"):
            raise ReadinessError("API path must be an absolute relative path")
        request = urllib.request.Request(
            self.base + path,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={**self.headers, **(headers or {})},
            method=method,
        )
        try:
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
                data = response.read(32 * 1024 * 1024 + 1)
                if len(data) > 32 * 1024 * 1024:
                    raise ReadinessError(f"{method} {path.split('?', 1)[0]} response exceeds 32MiB")
                if not data:
                    return None
                envelope = json.loads(data)
                if not isinstance(envelope, dict) or envelope.get("code") != "OK" or "data" not in envelope:
                    raise ReadinessError("Host API response violates ApiResponse contract")
                return envelope["data"]
        except urllib.error.HTTPError as failure:
            failure.read(8192)
            raise HttpFailure(method, path, failure.code) from None
        except (urllib.error.URLError, TimeoutError, OSError) as failure:
            raise HttpFailure(method, path, type(failure).__name__) from None


def api_path(path: str, **parameters: Any) -> str:
    query = urllib.parse.urlencode({key: value for key, value in parameters.items() if value is not None})
    return path if not query else f"{path}?{query}"


def require_secret(environment: dict[str, str], key: str) -> str:
    value = environment.get(key)
    if not value:
        raise ReadinessError(f"missing secret environment variable: {key}")
    return value


def require_text(value: Any, key: str, label: str) -> str:
    if not isinstance(value, dict) or not isinstance(value.get(key), str) or not value[key]:
        raise ReadinessError(f"{label} lacks {key}")
    return value[key]


def require_positive(value: Any, key: str, label: str) -> int | str:
    identity = value.get(key) if isinstance(value, dict) else None
    if not ((type(identity) is int and identity > 0) or
            (isinstance(identity, str) and identity.isascii() and identity.isdigit() and int(identity) > 0)):
        raise ReadinessError(f"{label} lacks positive {key}")
    return identity


def mounts(institution: Api, agent_id: str) -> dict[str, Any]:
    value = institution.request(
        "GET", api_path("/agents/capability-mounts", agentId=agent_id)
    )
    if not isinstance(value, dict) or not isinstance(value.get("mounts"), list):
        raise ReadinessError("target Agent mount response is invalid")
    return value


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument(
        "--agent-id", action="append", required=True,
        help="待核对的机构 Agent ID；可重复传入",
    )
    parser.add_argument(
        "--required-tool", action="append", default=[],
        help="本次发布组合必须具备的平台注册 Tool；可重复传入",
    )
    arguments = parser.parse_args(argv)
    for agent_id in arguments.agent_id:
        if not agent_id.isascii() or not agent_id.isdigit() or int(agent_id) <= 0:
            parser.error("--agent-id must be a positive decimal ID")
    for name in arguments.required_tool:
        if not name or name.strip() != name:
            parser.error("--required-tool must be a non-empty tool name")
    return arguments


def login_institution(environment: dict[str, str]) -> Api:
    base = environment.get(
        "SKILLFORGE_INSTITUTION_API",
        f"http://localhost:{environment.get('INSTITUTION_SERVICE_PORT', '8000')}/api/v1",
    )
    token = environment.get("SKILLFORGE_INSTITUTION_TOKEN")
    if token:
        return Api(base, token)
    login = Api(base).request(
        "POST", "/auth/login",
        {
            "username": require_secret(environment, "SF_ADMIN_USER"),
            "password": require_secret(environment, "SF_ADMIN_PASSWORD"),
            "tenant_slug": environment.get("SF_TENANT", "demo"),
        },
    )
    return Api(base, require_text(login, "access_token", "institution login"))


def issue(category: str, subject: str, detail: str) -> dict[str, str]:
    return {"category": category, "subject": subject, "detail": detail}


def rows(value: Any, label: str) -> list[dict[str, Any]]:
    if not isinstance(value, list) or not all(isinstance(row, dict) for row in value):
        raise ReadinessError(f"{label} response is invalid")
    return value


def one(
    values: list[dict[str, Any]], predicate: Any, label: str
) -> dict[str, Any] | None:
    matched = [value for value in values if predicate(value)]
    if len(matched) > 1:
        raise ReadinessError(f"{label} expected at most one match, got {len(matched)}")
    return matched[0] if matched else None


def platform_tool_targets(
    institution: Api, required_tools: tuple[str, ...]
) -> tuple[dict[str, dict[str, Any]], list[dict[str, Any]]]:
    catalog = rows(institution.request("GET", "/capabilities/platform-tools"), "platform tools")
    catalog_by_name = {item.get("name"): item for item in catalog if isinstance(item.get("name"), str)}
    if len(catalog_by_name) != len(catalog):
        raise ReadinessError("platform tools contains missing or repeated names")
    definitions = rows(
        institution.request(
            "GET", api_path(
                "/capabilities/definitions", capability_type="TOOL", status="PUBLISHED"
            )
        ),
        "published tool definitions",
    )
    targets: dict[str, dict[str, Any]] = {}
    issues: list[dict[str, Any]] = []
    for name in required_tools:
        catalog_tool = catalog_by_name.get(name)
        if catalog_tool is None:
            issues.append(issue("version_or_schema_drift", f"tool:{name}",
                                "platform tool catalog does not contain the required tool"))
            continue
        definition = one(
            definitions,
            lambda row: row.get("capability_key") == f"tool:{name}",
            f"published definition for tool {name}",
        )
        if definition is None:
            issues.append(issue("unpublished", f"tool:{name}",
                                "no published Tool capability definition"))
            continue
        expected_hash = catalog_tool.get("content_hash")
        if not isinstance(expected_hash, str) or not expected_hash:
            raise ReadinessError(f"platform tool {name} lacks content_hash")
        version_rows = rows(
            institution.request(
                "GET", api_path(
                    "/capabilities/definitions/versions",
                    definitionId=definition.get("id"), status="PUBLISHED",
                )
            ),
            f"published versions for tool {name}",
        )
        if not version_rows:
            issues.append(issue("unpublished", f"tool:{name}",
                                "Tool definition has no published version"))
            continue
        # default_version_id 属于 Skill 默认版本选择；Tool 绑定精确发布版本与部署。
        version = one(
            version_rows,
            lambda row: row.get("content_hash") == expected_hash,
            f"catalog-backed published version for tool {name}",
        )
        if version is None:
            issues.append(issue("version_or_schema_drift", f"tool:{name}",
                                "no published Tool version matches the catalog hash"))
            continue
        version_id = require_positive(version, "id", f"published Tool version {name}")
        targets[name] = {
            "definition_id": definition.get("id"),
            "version_id": version_id,
            "version_key": version.get("version_key"),
            "content_hash": expected_hash,
        }
    return targets, issues


def deployment_for_mount(
    deployments_by_key: dict[str, dict[str, Any]], mount: dict[str, Any]
) -> tuple[dict[str, Any] | None, str | None]:
    config = mount.get("tool_config")
    if not isinstance(config, dict):
        return None, "mount lacks tool_config"
    key = config.get("tool_deployment_id")
    revision = config.get("tool_deployment_revision")
    if not isinstance(key, str) or not key or not isinstance(revision, int) or revision <= 0:
        return None, "mount lacks deployment key or positive revision"
    deployment = deployments_by_key.get(key)
    if deployment is None:
        return None, "referenced ToolDeployment does not exist"
    if deployment.get("status") != "ACTIVE":
        return None, "referenced ToolDeployment is not ACTIVE"
    if deployment.get("tool_version_id") != mount.get("capability_version_id"):
        return None, "ToolDeployment version differs from mounted capability version"
    if deployment.get("revision") != revision:
        return None, "ToolDeployment revision differs from mounted revision"
    return deployment, None


def check_agent_readiness(
    institution: Api, agent_ids: tuple[str, ...], required_tools: tuple[str, ...]
) -> dict[str, Any]:
    unique_agents = tuple(dict.fromkeys(agent_ids))
    unique_required_tools = tuple(dict.fromkeys(required_tools))
    targets, catalog_issues = platform_tool_targets(institution, unique_required_tools)
    deployments = rows(institution.request("GET", "/capabilities/deployments"), "deployments")
    deployments_by_key = {
        row.get("deployment_key"): row for row in deployments
        if isinstance(row.get("deployment_key"), str)
    }
    if len(deployments_by_key) != len(deployments):
        raise ReadinessError("deployments contains missing or repeated deployment_key")

    agents: list[dict[str, Any]] = []
    ready = not catalog_issues
    for agent_id in unique_agents:
        detail = institution.request("GET", api_path("/agents/detail", agentId=agent_id))
        if not isinstance(detail, dict):
            raise ReadinessError(f"target Agent {agent_id} detail response is invalid")
        mount_set = mounts(institution, agent_id)
        mount_rows = rows(mount_set["mounts"], f"target Agent {agent_id} mounts")
        issues = list(catalog_issues)
        if detail.get("status") != "published":
            issues.append(issue("unpublished", f"agent:{agent_id}",
                                f"Agent status is {detail.get('status')!r}, expected 'published'"))

        tool_mounts = [mount for mount in mount_rows if mount.get("capability_type") == "TOOL"]
        mounted_by_definition: dict[Any, list[dict[str, Any]]] = {}
        for mount in tool_mounts:
            mounted_by_definition.setdefault(mount.get("capability_definition_id"), []).append(mount)

        required = []
        for name in unique_required_tools:
            target = targets.get(name)
            if target is None:
                continue
            definition_id = target["definition_id"]
            matching = mounted_by_definition.get(definition_id, [])
            item: dict[str, Any] = {"name": name, **target, "mount_id": None}
            required.append(item)
            if not matching:
                issues.append(issue("missing_mount", f"tool:{name}",
                                    "target Agent has no mount for the required Tool definition"))
                continue
            if len(matching) > 1:
                issues.append(issue("version_or_schema_drift", f"tool:{name}",
                                    "target Agent has duplicate Tool mounts for one definition"))
                continue
            mount = matching[0]
            item["mount_id"] = mount.get("mount_id")
            if mount.get("status") != "ACTIVE":
                issues.append(issue("missing_mount", f"tool:{name}",
                                    "required Tool mount is not ACTIVE"))
            if mount.get("capability_version_id") != target["version_id"]:
                issues.append(issue("version_or_schema_drift", f"tool:{name}",
                                    "mounted Tool version differs from the catalog-backed published version"))
            deployment, problem = deployment_for_mount(deployments_by_key, mount)
            if deployment is not None:
                item["deployment_key"] = deployment.get("deployment_key")
                item["deployment_revision"] = deployment.get("revision")
            if problem is not None:
                category = "unpublished" if problem.endswith("is not ACTIVE") else "version_or_schema_drift"
                issues.append(issue(category, f"tool:{name}", problem))
            elif not (
                deployment.get("provider_type") == "PLATFORM"
                and deployment.get("provider_ref") == name
                and deployment.get("environment") == "PRODUCTION"
            ):
                issues.append(issue("version_or_schema_drift", f"tool:{name}",
                                    "mounted Tool deployment is not the production platform deployment"))
        required_definition_ids = {target["definition_id"] for target in targets.values()}
        for mount in tool_mounts:
            if mount.get("capability_definition_id") in required_definition_ids:
                continue
            subject = f"mount:{mount.get('mount_id')}"
            if mount.get("status") != "ACTIVE":
                issues.append(issue("unpublished", subject, "mounted Tool is not ACTIVE"))
            _, problem = deployment_for_mount(deployments_by_key, mount)
            if problem is not None:
                category = "unpublished" if problem.endswith("is not ACTIVE") else "version_or_schema_drift"
                issues.append(issue(category, subject, problem))
        agents.append({
            "agent_id": agent_id,
            "agent_status": detail.get("status"),
            "mount_set_generation": mount_set.get("generation"),
            "required_tools": required,
            "issues": issues,
            "ready": not issues,
        })
        ready = ready and not issues
    return {
        "agent_ids": unique_agents,
        "required_tools": unique_required_tools,
        "scope": "tool_composition_only",
        "ready": ready,
        "agents": agents,
    }


def main() -> int:
    arguments = parse_args()
    environment = load_environment(arguments.env_file)
    try:
        report = check_agent_readiness(
            login_institution(environment),
            tuple(arguments.agent_id),
            tuple(arguments.required_tool),
        )
    except Exception as failure:
        print(f"agent readiness check failed: {type(failure).__name__}: {failure}", file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["ready"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
