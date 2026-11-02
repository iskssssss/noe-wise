#!/usr/bin/env python3
"""生成并校验 SkillForge 本地候选验证清单。"""

from __future__ import annotations

import argparse
import codecs
import contextvars
import functools
import fnmatch
import hashlib
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import time
import traceback
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable


SCHEMA_VERSION = 1
DOMAINS = ("source", "docs", "integration")
FORBIDDEN_PREFIXES = (".claude", ".git", ".agent-work-state")
SENSITIVE_PREFIXES = (".claude", ".git")
STATE_DIRECTORY = ".agent-work-state"
TOOLCHAIN_ENVIRONMENT_KEYS = ("JAVA_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "GRADLE_OPTS", "GRADLE_USER_HOME")
SAFE_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
REVIEW_POLICY_VERSION = 2
SKILL_ROOT = Path(__file__).resolve().parents[1]
LOGGER = logging.getLogger("skillforge.verification_manifest")
# 独立 stderr 通道防止调试信息混入 CLI 的 JSON stdout；级别沿用标准 logging 配置。
if not LOGGER.handlers:
    LOGGER.addHandler(logging.StreamHandler())
LOGGER.propagate = False
TRACE_ID: contextvars.ContextVar[str | None] = contextvars.ContextVar("verification_trace_id", default=None)


def trace_operation(operation: Callable[..., Any]) -> Callable[..., Any]:
    """为修改的方法统一添加安全入口/出口；参数与返回值原样传递，异常原样抛出。

    日志只含函数名、随机追踪标识、结果和耗时，不检查参数、返回值或异常载荷。
    DEBUG 关闭时直接执行；开启时嵌套调用共享上下文，退出后恢复原上下文。
    """
    LOGGER.debug("注册操作开始 operation=%s", operation.__name__)

    @functools.wraps(operation)
    def traced(*args: Any, **kwargs: Any) -> Any:
        """调用被包装方法并在成功、失败或中断时记录出口，finally 恢复追踪上下文。"""
        if not LOGGER.isEnabledFor(logging.DEBUG):
            return operation(*args, **kwargs)
        token = TRACE_ID.set(uuid.uuid4().hex) if TRACE_ID.get() is None else None
        trace_id = TRACE_ID.get()
        started = time.perf_counter()
        outcome = "failed"
        try:
            LOGGER.debug("操作开始 operation=%s trace_id=%s", operation.__name__, trace_id)
            result = operation(*args, **kwargs)
            # 正常返回可能是 CLI 的非零退出码，不把返回动作解释为 Gate 验收通过。
            outcome = "returned"
            return result
        finally:
            try:
                LOGGER.debug("操作结束 operation=%s trace_id=%s result=%s duration_ms=%.3f",
                             operation.__name__, trace_id, outcome, (time.perf_counter() - started) * 1000)
            finally:
                if token is not None:
                    TRACE_ID.reset(token)

    LOGGER.debug("注册操作结束 operation=%s result=registered", operation.__name__)
    return traced


class ManifestError(RuntimeError):
    """表示候选、环境或证据不满足复用约束。"""


def utc_now() -> str:
    """返回无微秒的 UTC ISO-8601 时间。"""
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def sha256_bytes(value: bytes) -> str:
    """计算大写 SHA-256，便于与 JDK 工具输出对照。"""
    return hashlib.sha256(value).hexdigest().upper()


def json_hash(value: dict[str, Any]) -> str:
    """对 JSON 语义而非排版计算稳定 hash。"""
    return sha256_bytes(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def load_json(path: Path) -> dict[str, Any]:
    """读取 JSON 对象并把解析错误转换为稳定诊断。"""
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ManifestError(f"无法读取 JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ManifestError(f"JSON 根节点必须是对象: {path}")
    return value


def write_json(path: Path, value: dict[str, Any]) -> None:
    """用同目录临时文件原子写入 UTF-8 JSON。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    content = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(content, encoding="utf-8", newline="\n")
    os.replace(temporary, path)


@trace_operation
def validate_review_findings(entry: dict[str, Any], evidence_name: str) -> None:
    """校验评审证据的显式阻断数与可选分级；合法零阻断返回 None，否则抛 ManifestError。

    evidence_name 是已校验的 Gate 标识，仅用于定位。分级描述不代表是否阻断；
    缺失、布尔值或非法计数不能被解释为零，不根据旧四级统计推导通过。
    """
    blocking = entry.get("blockingFindings")
    if type(blocking) is not int or blocking < 0:
        raise ManifestError(f"review blockingFindings 必须是非负整数: {evidence_name}")
    if "findings" in entry:
        findings = entry["findings"]
        if not isinstance(findings, dict) or any(
            not isinstance(level, str) or not level.strip() or type(count) is not int or count < 0
            for level, count in findings.items()
        ):
            raise ManifestError(f"review findings 必须是非空等级名称到非负整数的映射: {evidence_name}")
    if blocking != 0:
        raise ManifestError(f"review 存在未解决阻断项: {evidence_name}")


def output_encoding(value: Any) -> str:
    """验证显式输出编码，避免解码差异成为未声明的证据输入。"""
    if not isinstance(value, str) or not value:
        raise ManifestError("outputEncoding 必须是有效编码名称")
    try:
        name = codecs.lookup(value).name
        b" ".decode(name, errors="replace")
        return name
    except (LookupError, TypeError, UnicodeError, ValueError) as exc:
        raise ManifestError(f"不支持的 outputEncoding: {value}") from exc


def run_process(repo: Path, command: list[str], environment: dict[str, str] | None = None, *, encoding: str = "utf-8") -> subprocess.CompletedProcess[str]:
    """二进制捕获输出后在主线程解码，避免 Windows 读取线程异常丢失输出。"""
    encoding = output_encoding(encoding)
    process_environment = os.environ.copy()
    if environment:
        process_environment.update(environment)
    try:
        completed = subprocess.run(
            command,
            cwd=repo,
            env=process_environment,
            check=False,
            capture_output=True,
        )
    except OSError as exc:
        raise ManifestError(f"命令无法执行: {command[0]}: {exc}") from exc

    def decode_output(data: bytes) -> str:
        """保持文本模式换行语义，缺少 BOM 等结构错误转为明确的验证失败。"""
        try:
            decoder = codecs.getincrementaldecoder(encoding)(errors="replace")
            text = decoder.decode(data, final=True)
        except (UnicodeError, ValueError) as exc:
            raise ManifestError(f"命令输出无法按 {encoding} 解码: {command[0]}") from exc
        return text.replace("\r\n", "\n").replace("\r", "\n")

    return subprocess.CompletedProcess(
        completed.args, completed.returncode,
        decode_output(completed.stdout), decode_output(completed.stderr),
    )


def git_value(repo: Path, *arguments: str) -> str:
    """读取 Git 元数据，不修改索引或工作树。"""
    completed = run_process(repo, ["git", "-c", f"safe.directory={repo.as_posix()}", *arguments])
    if completed.returncode != 0:
        raise ManifestError(completed.stderr.strip() or "Git 元数据读取失败")
    return completed.stdout.strip()


def normalize_relative(repo: Path, path: Path) -> str:
    """把路径转换为仓库相对 POSIX 路径并拒绝越界。"""
    try:
        return path.resolve().relative_to(repo.resolve()).as_posix()
    except ValueError as exc:
        raise ManifestError(f"路径越过仓库边界: {path}") from exc


def is_forbidden(relative: str) -> bool:
    """识别无论配置如何都禁止读取的目录。"""
    lowered = relative.lower().strip("/")
    return any(lowered == prefix or lowered.startswith(prefix + "/") for prefix in FORBIDDEN_PREFIXES)


def ensure_safe_local_path(repo: Path, path: Path, purpose: str, require_state_directory: bool = False) -> str:
    """拒绝仓库外、治理目录和非本地状态输出路径。"""
    relative = normalize_relative(repo, path)
    lowered = relative.lower()
    if any(lowered == prefix or lowered.startswith(prefix + "/") for prefix in SENSITIVE_PREFIXES):
        raise ManifestError(f"{purpose} 不得位于禁止目录: {relative}")
    if require_state_directory and not (lowered == STATE_DIRECTORY or lowered.startswith(STATE_DIRECTORY + "/")):
        raise ManifestError(f"{purpose} 必须位于 {STATE_DIRECTORY}/: {relative}")
    return relative


def is_link_like(path: Path) -> bool:
    """跨平台识别符号链接和 Windows junction/reparse point。"""
    if path.is_symlink():
        return True
    is_junction = getattr(path, "is_junction", None)
    return bool(is_junction and is_junction())


def require_safe_identifier(value: Any, purpose: str) -> str:
    """限制会进入日志或工件路径的名称，阻断路径穿越。"""
    if not isinstance(value, str) or not SAFE_IDENTIFIER.fullmatch(value):
        raise ManifestError(f"{purpose} 必须是安全标识符: {value}")
    return value


def clean_specification(specification: str) -> str:
    """只移除完整的相对路径前缀，保留隐藏目录的前导点。"""
    normalized = specification.replace("\\", "/")
    while normalized.startswith("./"):
        normalized = normalized[2:]
    return normalized.rstrip("/")


def matches(relative: str, specification: str) -> bool:
    """按路径 segment 匹配 glob，只有双星号可以跨目录。"""
    normalized = clean_specification(specification)
    if not normalized:
        return False
    if not any(character in normalized for character in "*?["):
        return relative == normalized or relative.startswith(normalized + "/")
    path_segments = relative.split("/")
    pattern_segments = normalized.split("/")

    def match_segments(path_index: int, pattern_index: int) -> bool:
        """递归处理 `**` 的零段或多段语义。"""
        if pattern_index == len(pattern_segments):
            return path_index == len(path_segments)
        pattern = pattern_segments[pattern_index]
        if pattern == "**":
            return match_segments(path_index, pattern_index + 1) or (
                path_index < len(path_segments) and match_segments(path_index + 1, pattern_index)
            )
        return path_index < len(path_segments) and fnmatch.fnmatchcase(path_segments[path_index], pattern) and match_segments(path_index + 1, pattern_index + 1)

    return match_segments(0, 0)


def validate_specs(specifications: list[str], label: str, allow_forbidden: bool = False) -> None:
    """拒绝空值、非字符串和禁止目录范围。"""
    if not isinstance(specifications, list) or not all(isinstance(item, str) for item in specifications):
        raise ManifestError(f"{label} 必须是字符串数组")
    for specification in specifications:
        normalized = clean_specification(specification)
        prefix = normalized.split("*", 1)[0].split("?", 1)[0].rstrip("/")
        if not normalized or (is_forbidden(prefix) and not allow_forbidden):
            raise ManifestError(f"{label} 包含禁止路径: {specification}")


def repository_universe(repo: Path, base_sha: str) -> dict[str, dict[str, Any]]:
    """合并 base tree、index 和 untracked，保留 staged deletion 身份。"""
    universe: dict[str, dict[str, Any]] = {}
    base_tree = subprocess.run(
        ["git", "-c", f"safe.directory={repo.as_posix()}", "ls-tree", "-r", "-z", "--full-tree", base_sha],
        cwd=repo,
        check=False,
        capture_output=True,
    )
    if base_tree.returncode != 0:
        raise ManifestError("无法枚举 base tree")
    for raw_entry in base_tree.stdout.split(b"\0"):
        if not raw_entry:
            continue
        metadata, raw_path = raw_entry.split(b"\t", 1)
        mode, object_type, object_id = metadata.decode("ascii").split()
        relative = raw_path.decode("utf-8").replace("\\", "/")
        universe[relative] = {"tracked": False, "baseTracked": True, "mode": mode, "objectId": object_id, "objectType": object_type}
    staged = subprocess.run(
        ["git", "-c", f"safe.directory={repo.as_posix()}", "ls-files", "-z", "--stage"],
        cwd=repo,
        check=False,
        capture_output=True,
    )
    if staged.returncode != 0:
        raise ManifestError("无法枚举 Git 候选文件")
    for raw_entry in staged.stdout.split(b"\0"):
        if not raw_entry:
            continue
        metadata, raw_path = raw_entry.split(b"\t", 1)
        mode, object_id, stage = metadata.decode("ascii").split()
        if stage != "0":
            raise ManifestError(f"候选包含未解决的 index stage: {raw_path.decode('utf-8', errors='replace')}")
        relative = raw_path.decode("utf-8").replace("\\", "/")
        universe[relative] = {"tracked": True, "baseTracked": universe.get(relative, {}).get("baseTracked", False), "mode": mode, "objectId": object_id, "objectType": "commit" if mode == "160000" else "blob"}
    others = subprocess.run(
        ["git", "-c", f"safe.directory={repo.as_posix()}", "ls-files", "-z", "--others", "--exclude-standard"],
        cwd=repo,
        check=False,
        capture_output=True,
    )
    if others.returncode != 0:
        raise ManifestError("无法枚举未跟踪候选文件")
    for raw_path in others.stdout.split(b"\0"):
        if raw_path:
            relative = raw_path.decode("utf-8").replace("\\", "/")
            existing = universe.get(relative, {})
            universe[relative] = {"tracked": False, "baseTracked": existing.get("baseTracked", False), "mode": None, "objectId": None, "objectType": None}
    return universe


def default_governance_files(repo: Path, universe: dict[str, dict[str, Any]]) -> list[str]:
    """发现项目入口与同目录技能规则，保留已跟踪删除的身份。"""
    patterns = ["AGENTS.md", "CLAUDE.md", "docs/AGENTS.md", ".agents/notes/AGENTS.md"]
    try:
        skill_relative = SKILL_ROOT.relative_to(repo.resolve()).as_posix()
    except ValueError:
        # 从仓库外调用工具时，仓库内规则由显式 governanceFiles 补充。
        skill_relative = None
    if skill_relative is not None:
        # 项目规则按同级技能拆分，默认上下文指纹仍覆盖完整规则集合。
        skills_relative = Path(skill_relative).parent.as_posix()
        patterns.extend([
            f"{skills_relative}/*/SKILL.md",
            f"{skills_relative}/*/references/**/*.md",
            f"{skills_relative}/*/scripts/{Path(__file__).name}",
        ])
    return sorted(relative for relative in universe if not is_forbidden(relative) and any(matches(relative, pattern) for pattern in patterns))


def expand_paths(repo: Path, universe: dict[str, dict[str, Any]], specifications: list[str], excluded: list[str], allow_empty: bool = False) -> list[dict[str, Any]]:
    """展开候选范围并保留已删除 tracked 文件的标记。"""
    validate_specs(specifications, "候选路径")
    validate_specs(excluded, "排除路径", allow_forbidden=True)
    selected: list[dict[str, Any]] = []
    for relative, git_metadata in sorted(universe.items()):
        if is_forbidden(relative) or any(matches(relative, pattern) for pattern in excluded):
            continue
        if not any(matches(relative, specification) for specification in specifications):
            continue
        path = repo / relative
        if is_link_like(path):
            raise ManifestError(f"候选路径不得是符号链接或 reparse point: {relative}")
        if path.exists():
            resolved_relative = normalize_relative(repo, path)
            if resolved_relative != relative or is_forbidden(resolved_relative):
                raise ManifestError(f"候选路径解析到禁止或不同位置: {relative} -> {resolved_relative}")
        if git_metadata["mode"] == "160000":
            raise ManifestError(f"候选暂不支持 gitlink/submodule: {relative}")
        else:
            status = "file" if path.is_file() else "deleted"
        selected.append({
            "path": relative,
            "status": status,
            "tracked": git_metadata["tracked"],
            "baseTracked": git_metadata["baseTracked"],
            "mode": git_metadata["mode"],
            "objectId": git_metadata["objectId"] if status == "gitlink" else None,
            "pathObject": path,
        })
    for specification in specifications:
        has_magic = any(character in specification for character in "*?[")
        if not has_magic and not any(matches(item["path"], specification) for item in selected):
            raise ManifestError(f"候选显式路径不存在且未被 Git 跟踪: {specification}")
    if specifications and not selected and not allow_empty:
        raise ManifestError("候选 glob 没有匹配文件；显式设置 allowEmpty=true 才能允许空域")
    return selected


def hash_entries(entries: list[dict[str, Any]]) -> str:
    """用路径、状态、长度和原始字节生成无歧义 hash。"""
    digest = hashlib.sha256()
    for entry in entries:
        data = entry["pathObject"].read_bytes() if entry["status"] == "file" else (entry.get("objectId") or "").encode("ascii")
        metadata = json.dumps({"status": entry["status"], "tracked": entry["tracked"], "baseTracked": entry["baseTracked"], "mode": entry["mode"]}, sort_keys=True).encode("utf-8")
        for value in (entry["path"].encode("utf-8"), metadata, str(len(data)).encode("ascii"), data):
            digest.update(value)
            digest.update(b"\0")
    return digest.hexdigest().upper()


def public_entries(entries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """移除内部 Path 对象，生成可序列化的精确文件集合。"""
    return [{"path": entry["path"], "status": entry["status"], "tracked": entry["tracked"], "baseTracked": entry["baseTracked"], "mode": entry["mode"]} for entry in entries]


def toolchain_snapshot(repo: Path, state: dict[str, Any]) -> dict[str, Any]:
    """执行配置的版本命令并对输出和环境声明取指纹。"""
    configured = state.get("toolchains", [])
    if not isinstance(configured, list):
        raise ManifestError("toolchains 必须是数组")
    result: dict[str, Any] = {}
    for item in configured:
        if not isinstance(item, dict) or not isinstance(item.get("name"), str):
            raise ManifestError("每个 toolchain 必须包含 name")
        command = item.get("command")
        environment = item.get("environment", {})
        # 可执行文件身份单独绑定；PATH 仅在显式声明或覆盖时参与指纹。
        environment_keys = item.get("environmentKeys", list(TOOLCHAIN_ENVIRONMENT_KEYS))
        encoding = output_encoding(item.get("outputEncoding", "utf-8"))
        if not isinstance(command, list) or not command or not all(isinstance(part, str) for part in command):
            raise ManifestError(f"toolchain {item['name']} 的 command 无效")
        if not isinstance(environment, dict) or not all(isinstance(key, str) and isinstance(value, str) for key, value in environment.items()):
            raise ManifestError(f"toolchain {item['name']} 的 environment 无效")
        if not isinstance(environment_keys, list) or not all(isinstance(key, str) and key and "=" not in key and "\0" not in key for key in environment_keys):
            raise ManifestError(f"toolchain {item['name']} 的 environmentKeys 无效")
        merged_environment = os.environ.copy()
        merged_environment.update(environment)
        executable = shutil.which(command[0], path=merged_environment.get("PATH"))
        if executable is None:
            candidate = repo / command[0]
            executable = str(candidate.resolve()) if candidate.exists() else None
        if executable is None:
            raise ManifestError(f"toolchain 可执行文件不存在: {item['name']}")
        # 显式覆盖始终影响指纹，不能因遗漏 environmentKeys 而复用错误环境的证据。
        effective_keys = sorted(set(environment_keys) | set(environment))
        effective_environment = {key: merged_environment.get(key, "") for key in effective_keys}
        completed = run_process(repo, command, environment, encoding=encoding)
        if completed.returncode != 0:
            raise ManifestError(f"toolchain 指纹命令失败: {item['name']}")
        output = (completed.stdout + completed.stderr).strip()
        executable_path = Path(executable)
        executable_hash = sha256_bytes(executable_path.read_bytes()) if executable_path.is_file() else None
        fingerprint_input = json.dumps({"command": command, "environment": effective_environment, "output": output, "outputEncoding": encoding, "executable": str(executable_path), "executableHash": executable_hash}, sort_keys=True).encode("utf-8")
        result[item["name"]] = {
            "command": command,
            "environmentKeys": effective_keys,
            "outputEncoding": encoding,
            "resolvedExecutable": str(executable_path),
            "executableHash": executable_hash,
            "output": output,
            "fingerprint": sha256_bytes(fingerprint_input),
        }
    return result


@trace_operation
def candidate_snapshot(repo: Path, state: dict[str, Any]) -> dict[str, Any]:
    """由仓库与状态声明计算治理、工具链、候选域和 Gate 身份快照。

    返回值用于预冻结及复用判断；路径、归域、身份或工具链不合法时抛 ManifestError。
    评审策略只参与 review Gate 身份，命令 Gate 的既有计算方式保持不变。
    """
    if state.get("schemaVersion") != SCHEMA_VERSION:
        raise ManifestError("state.schemaVersion 不受支持")
    actual_branch = git_value(repo, "branch", "--show-current")
    if state.get("branch") != actual_branch:
        raise ManifestError(f"state.branch 与当前分支不一致: {state.get('branch')} != {actual_branch}")
    if Path(str(state.get("worktree", ""))).resolve() != repo.resolve():
        raise ManifestError("state.worktree 与当前仓库不一致")
    base_sha = state.get("baseSha")
    if not isinstance(base_sha, str) or not base_sha:
        raise ManifestError("state.baseSha 不能为空")
    base_check = run_process(repo, ["git", "-c", f"safe.directory={repo.as_posix()}", "cat-file", "-e", f"{base_sha}^{{commit}}"])
    if base_check.returncode != 0:
        raise ManifestError("state.baseSha 不是可解析 commit")
    ancestor_check = run_process(repo, ["git", "-c", f"safe.directory={repo.as_posix()}", "merge-base", "--is-ancestor", base_sha, "HEAD"])
    if ancestor_check.returncode != 0:
        raise ManifestError("state.baseSha 不是当前 HEAD 的祖先")
    universe = repository_universe(repo, base_sha)
    excluded = state.get("excludedPaths", [])
    allowed = state.get("allowedPaths", [])
    validate_specs(allowed, "allowedPaths")
    domains = state.get("domains")
    if not isinstance(domains, dict):
        raise ManifestError("domains 必须是对象")
    seen: dict[str, str] = {}
    domain_snapshots: dict[str, Any] = {}
    for domain in DOMAINS:
        value = domains.get(domain)
        if not isinstance(value, dict):
            raise ManifestError(f"domains.{domain} 必须是对象")
        paths = value.get("paths", [])
        allow_empty = value.get("allowEmpty", False)
        if not isinstance(allow_empty, bool):
            raise ManifestError(f"domains.{domain}.allowEmpty 必须是 bool")
        entries = expand_paths(repo, universe, paths, excluded, allow_empty=allow_empty)
        for entry in entries:
            if not any(matches(entry["path"], specification) for specification in allowed):
                raise ManifestError(f"候选文件超出 allowedPaths: {entry['path']}")
            previous = seen.get(entry["path"])
            if previous:
                raise ManifestError(f"候选文件重复归域: {entry['path']} 同时属于 {previous} 与 {domain}")
            seen[entry["path"]] = domain
        domain_snapshots[domain] = {"hash": hash_entries(entries), "files": public_entries(entries)}

    allowed_entries = expand_paths(repo, universe, allowed, excluded)
    allowed_set = {entry["path"] for entry in allowed_entries}
    domain_set = set(seen)
    if allowed_set != domain_set:
        raise ManifestError(f"allowedPaths 必须被候选域精确覆盖; missing={sorted(allowed_set - domain_set)}, unexpected={sorted(domain_set - allowed_set)}")

    governance_specs = state["governanceFiles"] if "governanceFiles" in state else default_governance_files(repo, universe)
    governance_entries = expand_paths(repo, universe, governance_specs, excluded)
    toolchains = toolchain_snapshot(repo, state)
    gate_snapshots: dict[str, Any] = {}
    gates = state.get("gates", {})
    if not isinstance(gates, dict):
        raise ManifestError("gates 必须是对象")
    for name, definition in sorted(gates.items()):
        require_safe_identifier(name, "Gate 名称")
        if not isinstance(definition, dict):
            raise ManifestError(f"gate {name} 定义必须是对象")
        gate_domains = definition.get("domains", [])
        gate_tools = definition.get("toolchains", [])
        definition_files = definition.get("definitionFiles", [])
        input_paths = definition.get("inputPaths", [])
        gate_kind = definition.get("kind")
        gate_version = definition.get("version")
        gate_command = definition.get("command")
        if not gate_domains or set(gate_domains) - set(DOMAINS):
            raise ManifestError(f"gate {name} 的 domains 无效")
        if not gate_tools or set(gate_tools) - set(toolchains):
            raise ManifestError(f"gate {name} 引用了未声明 toolchain")
        if gate_kind not in {"command", "review"} or not isinstance(gate_version, (str, int)) or isinstance(gate_version, bool):
            raise ManifestError(f"gate {name} 必须声明 kind 和 version")
        if gate_kind == "command" and (not isinstance(gate_command, list) or not gate_command or not all(isinstance(part, str) and part for part in gate_command)):
            raise ManifestError(f"command gate {name} 必须声明精确 argv 数组")
        if gate_kind == "review" and gate_command is not None:
            raise ManifestError(f"review gate {name} 不得声明 command")
        output_encoding(definition.get("outputEncoding", "utf-8"))
        required = definition.get("required", True)
        if not isinstance(required, bool):
            raise ManifestError(f"gate {name}.required 必须是 bool")
        definition_entries = expand_paths(repo, universe, definition_files, excluded)
        input_entries = expand_paths(repo, universe, input_paths, excluded)
        for entry in input_entries:
            if seen.get(entry["path"]) not in gate_domains:
                raise ManifestError(f"Gate 输入未归入声明域: {name}: {entry['path']}")
        # 全局治理指纹用于恢复上下文；仅显式相关的规则参与单个 Gate 的失效判断。
        gate_governance_specs = definition.get("governanceFiles", [])
        gate_governance_entries = expand_paths(repo, universe, gate_governance_specs, excluded)
        canonical_definition = json.loads(json.dumps(definition, ensure_ascii=False, sort_keys=True))
        identity = {
            "name": name,
            "definition": canonical_definition,
            "baseSha": state.get("baseSha"),
            "branch": actual_branch,
            "worktree": repo.as_posix(),
            "domains": {domain: domain_snapshots[domain]["hash"] for domain in sorted(set(gate_domains))},
            "definitionHash": hash_entries(definition_entries),
            "inputHash": hash_entries(input_entries),
            "governanceHash": hash_entries(gate_governance_entries),
            "toolchains": {tool: toolchains[tool]["fingerprint"] for tool in sorted(set(gate_tools))},
        }
        # 评审策略单独进入 review 身份，不使无关 command Gate 和全局状态失效。
        if gate_kind == "review":
            identity["reviewPolicyVersion"] = REVIEW_POLICY_VERSION
        gate_snapshots[name] = {
            "key": sha256_bytes(json.dumps(identity, sort_keys=True).encode("utf-8")),
            "identity": identity,
            "definitionFiles": public_entries(definition_entries),
            "inputFiles": public_entries(input_entries),
            "required": required,
        }

    combined = {domain: domain_snapshots[domain]["hash"] for domain in DOMAINS}
    return {
        "capturedAt": utc_now(),
        "headSha": git_value(repo, "rev-parse", "HEAD"),
        "branch": actual_branch,
        "worktree": repo.as_posix(),
        "governanceHash": hash_entries(governance_entries),
        "governanceFiles": public_entries(governance_entries),
        "toolchains": toolchains,
        "candidateHash": sha256_bytes(json.dumps(combined, sort_keys=True).encode("utf-8")),
        "domains": domain_snapshots,
        "gates": gate_snapshots,
    }


def find_repo(state_path: Path, explicit_repo: str | None) -> Path:
    """解析仓库根并保证状态文件位于仓库内。"""
    if explicit_repo:
        repo = Path(explicit_repo).resolve()
    else:
        completed = subprocess.run(["git", "rev-parse", "--show-toplevel"], cwd=Path.cwd(), check=False, capture_output=True, text=True, encoding="utf-8")
        if completed.returncode != 0:
            raise ManifestError("无法定位 Git 仓库；请传入 --repo")
        repo = Path(completed.stdout.strip()).resolve()
    actual_root = Path(git_value(repo, "rev-parse", "--show-toplevel")).resolve()
    if actual_root != repo:
        raise ManifestError(f"--repo 必须是 Git 顶层目录: {actual_root}")
    ensure_safe_local_path(repo, state_path, "state", require_state_directory=True)
    return repo


def junit_summary(path: Path) -> dict[str, int]:
    """从 JUnit XML 自动汇总 suite 和测试结果。"""
    totals = {"suites": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    if is_link_like(path):
        raise ManifestError(f"JUnit 目录不得是符号链接或 reparse point: {path}")
    xml_files = sorted(path.rglob("TEST-*.xml"))
    if not xml_files:
        raise ManifestError(f"未找到 JUnit XML: {path}")
    for xml_file in xml_files:
        if is_link_like(xml_file):
            raise ManifestError(f"JUnit 报告不得是符号链接: {xml_file}")
        try:
            root = ET.parse(xml_file).getroot()
        except (ET.ParseError, OSError, ValueError) as exc:
            raise ManifestError(f"JUnit XML 无效: {xml_file}: {exc}") from exc
        suites = list(root) if root.tag == "testsuites" else [root]
        if root.tag not in {"testsuites", "testsuite"} or not suites:
            raise ManifestError(f"JUnit 根结构不受支持: {xml_file}")
        for suite in suites:
            if suite.tag != "testsuite":
                raise ManifestError(f"JUnit suite 结构不受支持: {xml_file}")
            if suite.find("testsuite") is not None:
                raise ManifestError(f"JUnit 不支持嵌套 suite: {xml_file}")
            # 以 testcase 实际结果为准，拒绝计数声明掩盖失败或虚增测试数。
            cases = suite.findall("testcase")
            measured = {"tests": len(cases), "failures": 0, "errors": 0, "skipped": 0}
            for case in cases:
                for field, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                    measured[field] += int(case.find(tag) is not None)
            for field, measured_value in measured.items():
                try:
                    declared = int(suite.attrib.get(field, str(measured_value)))
                except ValueError as exc:
                    raise ManifestError(f"JUnit 计数不是整数: {xml_file}: {field}") from exc
                if declared < 0 or declared != measured_value:
                    raise ManifestError(f"JUnit 计数与 testcase 不一致: {xml_file}: {field}")
            totals["suites"] += 1
            for field in ("tests", "failures", "errors", "skipped"):
                totals[field] += measured[field]
    return totals


def copy_immutable(repo: Path, source: Path, destination: Path) -> None:
    """复制本次证据且拒绝覆盖内容不同的既有快照。"""
    ensure_safe_local_path(repo, source, "证据源")
    ensure_safe_local_path(repo, destination, "证据目标", require_state_directory=True)
    if is_link_like(source) or (destination.exists() and is_link_like(destination)):
        raise ManifestError("证据源或目标不得是符号链接/junction")
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if not destination.is_file() or sha256_bytes(destination.read_bytes()) != sha256_bytes(source.read_bytes()):
            raise ManifestError(f"不可覆盖已有证据快照: {destination}")
        return
    shutil.copy2(source, destination)


def report_snapshot(repo: Path, reports: list[str], artifact_directory: Path) -> list[dict[str, str]]:
    """把真实报告复制到候选专属目录并记录内容 hash。"""
    snapshots: list[dict[str, str]] = []
    if not isinstance(reports, list) or not all(isinstance(report, str) for report in reports):
        raise ManifestError("reports 必须是字符串数组")
    for index, report in enumerate(reports):
        path = Path(report)
        path = path if path.is_absolute() else repo / path
        ensure_safe_local_path(repo, path, "report")
        if path.is_symlink() or not path.is_file():
            raise ManifestError(f"证据报告不存在: {report}")
        destination = artifact_directory / "reports" / f"{index:03d}-{path.name}"
        copy_immutable(repo, path, destination)
        snapshots.append({"path": normalize_relative(repo, destination), "hash": sha256_bytes(destination.read_bytes())})
    return snapshots


def output_fingerprint(repo: Path, definition: dict[str, Any]) -> dict[str, tuple[int, int, str]]:
    """在 Gate 前后记录声明报告与 JUnit XML 的时间和内容。"""
    paths: list[Path] = []
    reports = definition.get("reports", [])
    if not isinstance(reports, list) or not all(isinstance(report, str) for report in reports):
        raise ManifestError("Gate reports 必须是字符串数组")
    for report in reports:
        path = Path(report)
        paths.append(path if path.is_absolute() else repo / path)
    policy = definition.get("junitPolicy")
    if policy is not None:
        if not isinstance(policy, dict) or not isinstance(policy.get("directory"), str):
            raise ManifestError("Gate junitPolicy 必须声明 directory")
        directory = Path(policy["directory"])
        directory = directory if directory.is_absolute() else repo / directory
        ensure_safe_local_path(repo, directory, "JUnit 目录")
        if directory.exists() and is_link_like(directory):
            raise ManifestError("JUnit 目录不得是符号链接/junction")
        paths.extend(sorted(directory.rglob("TEST-*.xml")) if directory.exists() else [])
    fingerprints: dict[str, tuple[int, int, str]] = {}
    for path in paths:
        relative = ensure_safe_local_path(repo, path, "Gate 输出")
        if path.exists():
            if is_link_like(path) or not path.is_file():
                raise ManifestError(f"Gate 输出必须是普通文件: {relative}")
            stat = path.stat()
            fingerprints[relative] = (stat.st_mtime_ns, stat.st_size, sha256_bytes(path.read_bytes()))
    return fingerprints


def require_fresh_outputs(repo: Path, definition: dict[str, Any], before: dict[str, tuple[int, int, str]], started_ns: int) -> None:
    """拒绝成功命令复用本次运行前遗留的报告。"""
    after = output_fingerprint(repo, definition)
    expected_reports = definition.get("reports", [])
    for report in expected_reports:
        path = Path(report)
        path = path if path.is_absolute() else repo / path
        relative = ensure_safe_local_path(repo, path, "Gate 输出")
        if relative not in after:
            raise ManifestError(f"Gate 未生成声明报告: {relative}")
    policy = definition.get("junitPolicy")
    if policy is not None and not any(path.endswith(".xml") for path in after):
        raise ManifestError("Gate 未生成 JUnit XML")
    changed = {path for path, fingerprint in after.items() if before.get(path) != fingerprint}
    declared_outputs = set(after)
    if declared_outputs - changed:
        raise ManifestError(f"Gate 包含运行前旧报告: {sorted(declared_outputs - changed)}")
    tolerance_ns = 2_000_000_000
    stale = [path for path in changed if after[path][0] < started_ns - tolerance_ns]
    if stale:
        raise ManifestError(f"Gate 输出时间早于本次运行: {stale}")


def junit_snapshot(repo: Path, directory: str, artifact_directory: Path, policy: dict[str, Any]) -> dict[str, Any]:
    """复制精确 JUnit XML 集合并执行测试数与 skipped 策略。"""
    source = Path(directory)
    source = source if source.is_absolute() else repo / source
    ensure_safe_local_path(repo, source, "JUnit 目录")
    summary = junit_summary(source)
    minimum_tests = policy.get("minTests", 1)
    maximum_skipped = policy.get("maxSkipped", 0)
    if not isinstance(minimum_tests, int) or isinstance(minimum_tests, bool) or minimum_tests < 1:
        raise ManifestError("junitPolicy.minTests 必须是正整数")
    if not isinstance(maximum_skipped, int) or isinstance(maximum_skipped, bool) or maximum_skipped < 0:
        raise ManifestError("junitPolicy.maxSkipped 必须是非负整数")
    if summary["failures"] or summary["errors"] or summary["tests"] < minimum_tests or summary["skipped"] > maximum_skipped:
        raise ManifestError(f"JUnit 结果不满足 Gate policy: {summary}")
    files: list[dict[str, str]] = []
    for xml_file in sorted(source.rglob("TEST-*.xml")):
        relative = xml_file.relative_to(source)
        destination = artifact_directory / "junit" / relative
        ensure_safe_local_path(repo, xml_file, "JUnit XML")
        copy_immutable(repo, xml_file, destination)
        files.append({"path": normalize_relative(repo, destination), "hash": sha256_bytes(destination.read_bytes())})
    return {"root": normalize_relative(repo, artifact_directory / "junit"), "summary": summary, "files": files}


@trace_operation
def bind_evidence(repo: Path, evidence: dict[str, Any], snapshot: dict[str, Any], artifact_root: Path, require_complete: bool = True) -> list[dict[str, Any]]:
    """把 evidence 绑定到 snapshot 并返回校验后的列表，工件写入 artifact_root。

    require_complete 决定是否检查全部必要 Gate；命令须有真实结果，评审须按当前
    策略显式声明零阻断，任何身份、内容或覆盖冲突抛 ManifestError，不推导旧结论。
    """
    entries = evidence.get("evidence")
    if not isinstance(entries, list) or not entries:
        raise ManifestError("evidence 必须是非空数组")
    bound: list[dict[str, Any]] = []
    seen_names: set[str] = set()
    seen_gates: set[str] = set()
    for raw_entry in entries:
        if not isinstance(raw_entry, dict):
            raise ManifestError("每条 evidence 必须是对象")
        entry = dict(raw_entry)
        name = require_safe_identifier(entry.get("name"), "evidence 名称")
        gate = entry.get("gate")
        kind = entry.get("kind")
        if name != gate or gate not in snapshot["gates"]:
            raise ManifestError(f"证据名称或 gate 无效: {name}")
        if name in seen_names or gate in seen_gates:
            raise ManifestError(f"证据名称和 gate 必须唯一: {name}/{gate}")
        seen_names.add(name)
        seen_gates.add(gate)
        gate_definition = snapshot["gates"][gate]["identity"]["definition"]
        if kind != gate_definition["kind"]:
            raise ManifestError(f"证据 kind 与 Gate 定义不一致: {name}")
        if entry.get("gateKey") != snapshot["gates"][gate]["key"]:
            raise ManifestError(f"证据没有绑定预冻结 Gate key: {name}")
        if "status" not in entry or entry["status"] != "pass":
            raise ManifestError(f"失败或 partial 证据不能记录: {name}")
        if kind == "command":
            exit_code = entry.get("exitCode")
            if not isinstance(exit_code, int) or isinstance(exit_code, bool) or exit_code != 0:
                raise ManifestError(f"命令证据必须显式记录 exitCode=0: {name}")
            if entry.get("command") != gate_definition["command"]:
                raise ManifestError(f"命令证据与 Gate 定义不一致: {name}")
            reports = entry.get("reports", [])
            result = entry.get("result")
            if not reports and (not isinstance(result, dict) or not result):
                raise ManifestError(f"命令证据必须包含 reports 或结构化 result: {name}")
            entry_artifacts = artifact_root / snapshot["gates"][gate]["key"] / name
            entry["reports"] = report_snapshot(repo, reports, entry_artifacts)
        elif kind == "review":
            policy_version = snapshot["gates"][gate]["identity"].get("reviewPolicyVersion")
            if type(policy_version) is not int or policy_version != REVIEW_POLICY_VERSION:
                raise ManifestError(f"review 必须绑定当前评审策略: {name}")
            validate_review_findings(entry, name)
        else:
            raise ManifestError(f"未知证据 kind: {kind}")
        policy = gate_definition.get("junitPolicy")
        if policy is not None:
            if kind != "command" or not isinstance(policy, dict) or not isinstance(policy.get("directory"), str):
                raise ManifestError(f"Gate junitPolicy 无效: {gate}")
            entry["junit"] = junit_snapshot(repo, policy["directory"], artifact_root / snapshot["gates"][gate]["key"] / name, policy)
        bound.append(entry)
    required_gates = {name for name, gate in snapshot["gates"].items() if gate["required"]}
    if require_complete and seen_gates != required_gates:
        missing = sorted(required_gates - seen_gates)
        unexpected = sorted(seen_gates - required_gates)
        raise ManifestError(f"证据未精确覆盖 required Gates; missing={missing}, unexpected={unexpected}")
    return bound


def init_state(args: argparse.Namespace) -> None:
    """初始化最小状态，由主线程显式填写候选范围与 Gate。"""
    state_path = Path(args.state).resolve()
    repo = find_repo(state_path, args.repo)
    if state_path.exists() and not args.force:
        raise ManifestError(f"状态文件已存在；使用 --force 才能覆写: {state_path}")
    state = {
        "schemaVersion": SCHEMA_VERSION,
        "goal": args.goal,
        "stackItem": args.stack_item,
        "status": "planning",
        "baseSha": args.base_sha or git_value(repo, "rev-parse", "HEAD"),
        "branch": args.branch or git_value(repo, "branch", "--show-current"),
        "worktree": repo.as_posix(),
        "allowedPaths": [],
        "excludedPaths": [".claude/**", ".git/**", ".agent-work-state/**"],
        "factSources": [],
        "openBlockers": [],
        "toolchains": [{"name": "python", "command": [sys.executable, "--version"], "environmentKeys": ["PYTHONHOME", "PYTHONPATH"]}],
        "domains": {domain: {"paths": []} for domain in DOMAINS},
        "gates": {},
    }
    write_json(state_path, state)
    print(state_path)


def snapshot_state(args: argparse.Namespace) -> None:
    """刷新状态中的候选快照。"""
    state_path = Path(args.state).resolve()
    repo = find_repo(state_path, args.repo)
    state = load_json(state_path)
    state["snapshot"] = candidate_snapshot(repo, state)
    blockers = state.get("openBlockers")
    if not isinstance(blockers, list):
        raise ManifestError("openBlockers 必须是数组")
    state["status"] = "blocked" if blockers else "frozen"
    write_json(state_path, state)
    print(json.dumps(state["snapshot"], ensure_ascii=False, indent=2))


def frozen_candidate(state_path: Path, explicit_repo: str | None) -> tuple[Path, dict[str, Any], dict[str, Any]]:
    """加载预冻结候选并拒绝 blocker 或 snapshot 后漂移。"""
    repo = find_repo(state_path, explicit_repo)
    state = load_json(state_path)
    blockers = state.get("openBlockers")
    if not isinstance(blockers, list) or blockers:
        raise ManifestError("存在 openBlockers，不能执行或记录绿色 Gate")
    frozen = state.get("snapshot")
    if state.get("status") not in {"frozen", "verifying", "failed", "verified"} or not isinstance(frozen, dict):
        raise ManifestError("必须先运行 snapshot 冻结候选")
    current = candidate_snapshot(repo, state)
    frozen_keys = {name: value.get("key") for name, value in frozen.get("gates", {}).items()}
    current_keys = {name: value.get("key") for name, value in current.get("gates", {}).items()}
    if frozen_keys != current_keys or frozen.get("candidateHash") != current["candidateHash"]:
        raise ManifestError("候选在 snapshot 后发生变化，必须重新冻结并重跑 Gate")
    return repo, state, current


def write_text_atomic(path: Path, content: str) -> None:
    """原子写入命令日志，避免中断留下半份证据。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(content, encoding="utf-8", newline="\n")
    os.replace(temporary, path)


def checkpoint_content(manifest: dict[str, Any]) -> dict[str, Any]:
    """仅用于重复发布比较；时间字段仍受完整 checkpoint hash 校验。"""
    content = {key: value for key, value in manifest.items() if key != "verifiedAt"}
    content["snapshot"] = {key: value for key, value in manifest["snapshot"].items() if key != "capturedAt"}
    return content


def persist_manifest(state_path: Path, repo: Path, state: dict[str, Any], snapshot: dict[str, Any], bound: list[dict[str, Any]], manifest_path: Path) -> None:
    """逐 Gate 保存校验后的证据；不可变 checkpoint 先于状态指针发布。"""
    ensure_safe_local_path(repo, manifest_path, "manifest", require_state_directory=True)
    validate_bound_entries(repo, bound, snapshot)
    # 包括证据复制期间的输入漂移也必须阻断状态发布。
    current = candidate_snapshot(repo, state)
    if current["candidateHash"] != snapshot["candidateHash"] or {name: gate["key"] for name, gate in current["gates"].items()} != {name: gate["key"] for name, gate in snapshot["gates"].items()}:
        raise ManifestError("发布 checkpoint 前候选发生变化")
    required = {name for name, gate in snapshot["gates"].items() if gate["required"]}
    complete = bool(required) and {entry["gate"] for entry in bound} == required
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "goal": state.get("goal"),
        "stackItem": state.get("stackItem"),
        "baseSha": state.get("baseSha"),
        "verifiedAt": utc_now(),
        "status": "verified" if complete else "verifying",
        "snapshot": snapshot,
        "evidence": bound,
    }
    previous = load_checkpoint(repo, state)
    # 相同冻结候选及证据沿用首次验证时间，避免每次缓存命中生成新文件。
    # 完整 manifest（含时间戳）仍参与 hash，不能靠忽略字段掩盖内容篡改。
    if previous is not None and checkpoint_content(previous) == checkpoint_content(manifest):
        manifest = previous
        snapshot = manifest["snapshot"]
    state["snapshot"] = snapshot
    state["status"] = manifest["status"]
    manifest_hash = json_hash(manifest)
    checkpoint_path = state_path.parent / "checkpoints" / (manifest_hash + ".json")
    ensure_safe_local_path(repo, checkpoint_path, "checkpoint", require_state_directory=True)
    # 内容寻址的 checkpoint 不覆盖旧状态所指文件，崩溃后仍能恢复上一份完整证据。
    if checkpoint_path.exists():
        if load_json(checkpoint_path) != manifest:
            raise ManifestError("已有 checkpoint 内容与 hash 不符")
    else:
        write_json(checkpoint_path, manifest)
    write_json(manifest_path, manifest)
    pointer = {"manifest": normalize_relative(repo, manifest_path), "checkpoint": normalize_relative(repo, checkpoint_path), "manifestHash": manifest_hash, "candidateHash": snapshot["candidateHash"], "verifiedAt": manifest["verifiedAt"], "gateKeys": {entry["gate"]: entry["gateKey"] for entry in bound}}
    state["evidenceCheckpoint"] = pointer
    if complete:
        state["lastGreen"] = dict(pointer)
        state.pop("lastFailure", None)
    write_json(state_path, state)
    print(json.dumps({"status": state["status"], **pointer}, ensure_ascii=False))


@trace_operation
def load_checkpoint(repo: Path, state: dict[str, Any]) -> dict[str, Any] | None:
    """加载 state 指定的不可变 checkpoint；无指针返回 None，损坏或身份冲突抛错。

    先校验内容 hash、状态身份、冻结 Gate 与证据绑定。旧策略评审仅作为过期记录
    保留在返回容器，由当前 Gate key 排除；命令证据与当前策略评审仍须严格校验。
    """
    pointer = state.get("evidenceCheckpoint")
    if pointer is None:
        return None
    if not isinstance(pointer, dict) or not isinstance(pointer.get("checkpoint"), str):
        raise ManifestError("evidenceCheckpoint 结构无效")
    path = repo / pointer["checkpoint"]
    ensure_safe_local_path(repo, path, "checkpoint", require_state_directory=True)
    manifest = load_json(path)
    if pointer.get("manifestHash") != json_hash(manifest):
        raise ManifestError("checkpoint hash 与 state 指针不一致")
    if manifest.get("schemaVersion") != SCHEMA_VERSION or any(manifest.get(field) != state.get(field) for field in ("goal", "stackItem", "baseSha")):
        raise ManifestError("checkpoint 与 state 身份不一致")
    recorded = manifest.get("snapshot")
    if not isinstance(recorded, dict) or not isinstance(recorded.get("gates"), dict):
        raise ManifestError("checkpoint 缺少冻结候选")
    # 先确认冻结身份与 key 自洽，禁止删掉策略字段后把损坏的当前证据伪装为旧缓存。
    for gate in recorded["gates"].values():
        if not isinstance(gate, dict) or not isinstance(gate.get("identity"), dict):
            raise ManifestError("checkpoint Gate 身份无效")
        identity_key = sha256_bytes(json.dumps(gate["identity"], sort_keys=True).encode("utf-8"))
        if gate.get("key") != identity_key:
            raise ManifestError("checkpoint Gate key 与身份不一致")
    validate_bound_entries(repo, manifest.get("evidence"), recorded, allow_stale_reviews=True)
    if pointer.get("candidateHash") != recorded.get("candidateHash") or pointer.get("gateKeys") != {entry["gate"]: entry["gateKey"] for entry in manifest["evidence"]}:
        raise ManifestError("checkpoint 候选/Gate 指针不一致")
    return manifest


@trace_operation
def run_gates(args: argparse.Namespace) -> None:
    """执行 args 指定候选并发布实际证据；失败保留可信部分后抛 ManifestError。

    相同 Gate key 的命令和当前评审可复用；策略升级后的旧评审需要新证据，不能
    借此重跑未变命令或把候选提前标为 verified。CLI 结果仍只写 JSON stdout。
    """
    state_path = Path(args.state).resolve()
    repo, state, snapshot = frozen_candidate(state_path, args.repo)
    manifest_path = Path(args.manifest).resolve()
    ensure_safe_local_path(repo, manifest_path, "manifest", require_state_directory=True)
    required_definitions = {name: state["gates"][name] for name, gate in snapshot["gates"].items() if gate["required"]}
    if not required_definitions:
        raise ManifestError("候选必须声明至少一个 required Gate")
    previous = load_checkpoint(repo, state)
    # 按 Gate key 而非整个 candidateHash 复用，文档变更仍可保留独立 source Gate。
    bound = [entry for entry in previous["evidence"] if entry["gate"] in required_definitions and entry["gateKey"] == snapshot["gates"][entry["gate"]]["key"]] if previous else []
    cached_names = {entry["gate"] for entry in bound}
    all_review_names = {name for name, definition in required_definitions.items() if definition["kind"] == "review"}
    review_names = {name for name, definition in required_definitions.items() if definition["kind"] == "review" and name not in cached_names}
    review_entries: list[dict[str, Any]] = []
    if review_names and not args.review_evidence:
        raise ManifestError(f"required review Gate 需要 --review-evidence: {sorted(review_names)}")
    if args.review_evidence:
        review_path = Path(args.review_evidence).resolve()
        ensure_safe_local_path(repo, review_path, "review evidence", require_state_directory=True)
        loaded = load_json(review_path).get("evidence")
        supplied_names = {entry.get("gate") for entry in loaded if isinstance(entry, dict)} if isinstance(loaded, list) else set()
        if not isinstance(loaded, list) or not loaded or not review_names.issubset(supplied_names) or not supplied_names.issubset(all_review_names):
            raise ManifestError("review evidence 未精确覆盖 required review Gates")
        # 原样重试允许再次传入已缓存 review，但矛盾内容不能替换此前审查。
        validated_reviews = bind_evidence(repo, {"evidence": loaded}, snapshot, state_path.parent / "artifacts", require_complete=False)
        cached_reviews = {entry["gate"]: entry for entry in bound if entry["kind"] == "review"}
        for entry in validated_reviews:
            if entry["gate"] in cached_reviews and entry != cached_reviews[entry["gate"]]:
                raise ManifestError("review evidence 与缓存审查不一致")
        review_entries = [entry for entry in validated_reviews if entry["gate"] not in cached_names]

    # 执行尝试独立目录，未发布的孤儿工件不会阻止同 Gate key 的故障恢复。
    attempt_id = uuid.uuid4().hex
    artifact_root = state_path.parent / "artifacts" / attempt_id
    # 审查证据先按部分集合校验，任何失败都不能把全部候选标为 verified。
    if review_entries:
        bound.extend(bind_evidence(repo, {"evidence": review_entries}, snapshot, artifact_root, require_complete=False))
        persist_manifest(state_path, repo, state, snapshot, bound, manifest_path)
    for name, definition in sorted(required_definitions.items()):
        if name in cached_names:
            LOGGER.debug("复用验证证据 operation=run_gates trace_id=%s gate=%s result=cache_hit",
                         TRACE_ID.get(), name)
            continue
        if definition["kind"] != "command":
            continue
        gate_key = snapshot["gates"][name]["key"]
        command = definition["command"]
        environment = definition.get("environment", {})
        if not isinstance(environment, dict) or not all(isinstance(key, str) and isinstance(value, str) for key, value in environment.items()):
            raise ManifestError(f"Gate environment 无效: {name}")
        before_outputs = output_fingerprint(repo, definition)
        started_ns = time.time_ns()
        completed = run_process(repo, command, environment, encoding=definition.get("outputEncoding", "utf-8"))
        log_path = state_path.parent / "runs" / attempt_id / gate_key / f"{name}.log"
        log_content = "$ " + subprocess.list2cmdline(command) + "\n\n" + completed.stdout + completed.stderr
        write_text_atomic(log_path, log_content)
        if completed.returncode != 0:
            state["status"] = "failed"
            state["lastFailure"] = {"gate": name, "gateKey": gate_key, "exitCode": completed.returncode, "log": normalize_relative(repo, log_path), "failedAt": utc_now()}
            write_json(state_path, state)
            raise ManifestError(f"Gate 执行失败: {name} (exit {completed.returncode})")
        require_fresh_outputs(repo, definition, before_outputs, started_ns)
        after = candidate_snapshot(repo, state)
        frozen_keys = {gate_name: gate["key"] for gate_name, gate in snapshot["gates"].items()}
        after_keys = {gate_name: gate["key"] for gate_name, gate in after["gates"].items()}
        if after["candidateHash"] != snapshot["candidateHash"] or after_keys != frozen_keys:
            raise ManifestError(f"Gate 执行期间冻结候选发生变化: {name}")
        reports = [normalize_relative(repo, log_path), *definition.get("reports", [])]
        entry = {
            "name": name,
            "gate": name,
            "gateKey": gate_key,
            "kind": "command",
            "status": "pass",
            "exitCode": 0,
            "command": command,
            "reports": reports,
            "result": {"outputHash": sha256_bytes(log_path.read_bytes())},
        }
        bound.extend(bind_evidence(repo, {"evidence": [entry]}, snapshot, artifact_root, require_complete=False))
        persist_manifest(state_path, repo, state, snapshot, bound, manifest_path)
    # 全缓存命中也重新核对候选；随后用 check 的同一证据验证器发布最终状态。
    frozen_candidate(state_path, args.repo)
    persist_manifest(state_path, repo, state, snapshot, bound, manifest_path)
    check_manifest(args)


@trace_operation
def validate_bound_entries(repo: Path, entries: Any, current: dict[str, Any], require_complete: bool = False, *, allow_stale_reviews: bool = False) -> None:
    """按 current 冻结定义验证证据，成功返回 None；身份、内容或必要覆盖错误均拒绝。

    require_complete 控制是否要求全部必要 Gate；allow_stale_reviews 仅供历史容器
    恢复，允许过期评审跳过新策略计数校验，但不跳过 Gate 绑定、报告或完整性检查。
    """
    evidence_status: list[dict[str, Any]] = []
    if not isinstance(entries, list) or not entries:
        raise ManifestError("manifest.evidence 必须是非空数组")
    seen_gates: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise ManifestError("manifest evidence 必须是对象")
        gate = entry.get("gate")
        name = require_safe_identifier(entry.get("name"), "evidence 名称")
        if name != gate or gate not in current["gates"]:
            raise ManifestError(f"manifest evidence 名称/Gate 无效: {name}/{gate}")
        if gate in seen_gates:
            raise ManifestError(f"manifest 重复 Gate: {gate}")
        seen_gates.add(gate)
        gate_definition = current["gates"][gate]["identity"]["definition"]
        key_matches = entry.get("gateKey") == current["gates"][gate]["key"]
        kind_matches = entry.get("kind") == gate_definition["kind"]
        if entry.get("status") != "pass" or not kind_matches:
            raise ManifestError(f"manifest evidence 状态/kind 无效: {name}")
        if gate_definition["kind"] == "command":
            exit_code = entry.get("exitCode")
            if not isinstance(exit_code, int) or isinstance(exit_code, bool) or exit_code != 0 or entry.get("command") != gate_definition["command"]:
                raise ManifestError(f"manifest command/exitCode 无效: {name}")
            expected_report_count = 1 + len(gate_definition.get("reports", []))
            if not isinstance(entry.get("reports"), list) or len(entry["reports"]) != expected_report_count:
                raise ManifestError(f"manifest reports 未精确覆盖 Gate 输出: {name}")
            result_value = entry.get("result")
            if not isinstance(result_value, dict) or not isinstance(result_value.get("outputHash"), str):
                raise ManifestError(f"manifest result/outputHash 缺失: {name}")
        else:
            identity = current["gates"][gate]["identity"]
            policy_version = identity.get("reviewPolicyVersion")
            legacy_policy = "reviewPolicyVersion" not in identity or (type(policy_version) is int and policy_version == 1)
            if legacy_policy and allow_stale_reviews:
                # 仅恢复已验证完整性与绑定的历史容器，不认可其中旧评审通过。
                # run 按当前 Gate key 排除它；直接 check 仍按当前策略严格验证。
                LOGGER.debug("旧评审待替换 operation=validate_bound_entries trace_id=%s gate=%s result=stale",
                             TRACE_ID.get(), name)
            elif type(policy_version) is not int or policy_version != REVIEW_POLICY_VERSION:
                raise ManifestError(f"review 策略过期或不受支持: {name}")
            else:
                validate_review_findings(entry, name)
        reports_match = True
        for report in entry.get("reports", []):
            if not isinstance(report, dict) or not isinstance(report.get("path"), str) or not isinstance(report.get("hash"), str):
                raise ManifestError(f"manifest report 结构无效: {name}")
            path = repo / report["path"]
            ensure_safe_local_path(repo, path, "manifest report", require_state_directory=True)
            reports_match = reports_match and not is_link_like(path) and path.is_file() and sha256_bytes(path.read_bytes()) == report.get("hash")
        if gate_definition["kind"] == "command":
            reports_match = reports_match and entry["reports"][0].get("hash") == entry["result"]["outputHash"]
        junit_matches = True
        junit = entry.get("junit")
        junit_policy = gate_definition.get("junitPolicy")
        if (junit_policy is None) != (junit is None):
            raise ManifestError(f"manifest JUnit 与 Gate policy 不一致: {name}")
        if junit is not None:
            if not isinstance(junit, dict) or not isinstance(junit.get("files"), list):
                raise ManifestError(f"manifest JUnit 结构无效: {entry.get('name')}")
            for report in junit["files"]:
                if not isinstance(report, dict) or not isinstance(report.get("path"), str) or not isinstance(report.get("hash"), str):
                    raise ManifestError(f"manifest JUnit 文件结构无效: {name}")
                path = repo / report["path"]
                ensure_safe_local_path(repo, path, "manifest JUnit", require_state_directory=True)
                junit_matches = junit_matches and not is_link_like(path) and path.is_file() and sha256_bytes(path.read_bytes()) == report.get("hash")
            junit_root = repo / junit.get("root", "")
            ensure_safe_local_path(repo, junit_root, "manifest JUnit root", require_state_directory=True)
            actual_files = {normalize_relative(repo, path) for path in junit_root.rglob("TEST-*.xml")}
            recorded_files = {report.get("path") for report in junit["files"]}
            summary = junit_summary(junit_root)
            junit_matches = junit_matches and actual_files == recorded_files and summary == junit.get("summary")
            junit_matches = junit_matches and len(recorded_files) == len(junit["files"]) and summary["tests"] >= junit_policy.get("minTests", 1) and summary["failures"] == 0 and summary["errors"] == 0 and summary["skipped"] <= junit_policy.get("maxSkipped", 0)
        reusable = key_matches and reports_match and junit_matches and entry.get("status") == "pass"
        evidence_status.append({"name": entry.get("name"), "gate": gate, "reusable": reusable, "keyMatches": key_matches, "reportsMatch": reports_match, "junitMatches": junit_matches})
    required_gates = {name for name, gate in current["gates"].items() if gate["required"]}
    coverage_matches = seen_gates == required_gates
    if not seen_gates.issubset(required_gates) or (require_complete and not coverage_matches) or not all(item["reusable"] for item in evidence_status):
        raise ManifestError("一个或多个证据已失效")


@trace_operation
def check_manifest(args: argparse.Namespace) -> None:
    """校验 args 指定的最终清单，全部必要 Gate 通过后仅输出 JSON 结果。

    候选须无漂移和实际阻断，评审必须满足当前策略；指针、证据或覆盖错误抛
    ManifestError，不因历史缓存可恢复就认定当前候选已经完成。
    """
    state_path = Path(args.state).resolve()
    repo, state, current = frozen_candidate(state_path, args.repo)
    manifest_path = Path(args.manifest).resolve()
    ensure_safe_local_path(repo, manifest_path, "manifest", require_state_directory=True)
    manifest = load_checkpoint(repo, state)
    pointer = state.get("evidenceCheckpoint")
    if state.get("status") != "verified" or manifest is None or manifest.get("status") != "verified" or state.get("lastGreen") != pointer:
        raise ManifestError("manifest 未由当前 state 的受控绿色指针绑定")
    if pointer.get("manifest") != normalize_relative(repo, manifest_path) or load_json(manifest_path) != manifest:
        raise ManifestError("manifest 与已发布 checkpoint 不一致")
    validate_bound_entries(repo, manifest["evidence"], current, require_complete=True)
    print(json.dumps({"allReusable": True, "candidateHash": current["candidateHash"], "gates": sorted(pointer["gateKeys"])}, ensure_ascii=False))


def build_parser() -> argparse.ArgumentParser:
    """构建 init、snapshot、run 和 check 子命令。"""
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--state", required=True)
    common.add_argument("--repo")

    init_parser = subparsers.add_parser("init", parents=[common])
    init_parser.add_argument("--goal", required=True)
    init_parser.add_argument("--stack-item", required=True)
    init_parser.add_argument("--base-sha")
    init_parser.add_argument("--branch")
    init_parser.add_argument("--force", action="store_true")
    init_parser.set_defaults(handler=init_state)

    snapshot_parser = subparsers.add_parser("snapshot", parents=[common])
    snapshot_parser.set_defaults(handler=snapshot_state)

    run_parser = subparsers.add_parser("run", parents=[common])
    run_parser.add_argument("--manifest", required=True)
    run_parser.add_argument("--review-evidence")
    run_parser.set_defaults(handler=run_gates)

    check_parser = subparsers.add_parser("check", parents=[common])
    check_parser.add_argument("--manifest", required=True)
    check_parser.set_defaults(handler=check_manifest)
    return parser


@trace_operation
def main() -> int:
    """执行 CLI 并返回退出码；预期验证失败返回 2，其他异常保留原原因链。

    最终处理边界只记录一次安全栈帧，不记录异常载荷、参数、局部变量或源码。
    """
    try:
        args = build_parser().parse_args()
        args.handler(args)
        return 0
    except ManifestError as exc:
        frames = [f"{frame.f_code.co_name}:{line}" for frame, line in traceback.walk_tb(exc.__traceback__)]
        LOGGER.error("验证失败 operation=main trace_id=%s result=failed error_type=ManifestError frames=%s",
                     TRACE_ID.get(), frames)
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
