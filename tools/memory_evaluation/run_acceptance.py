#!/usr/bin/env python3
"""安全启动独立记忆验收任务；默认离线，显式 live 冻结来源与模型修订。"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
import reporting


# 来源数据库只读读取模型配置；其余环境键不从 .env 注入隔离服务。
SOURCE_KEYS = ("SPRING_DATASOURCE_URL", "PLATFORM_DATASOURCE_USERNAME",
               "PLATFORM_DATASOURCE_PASSWORD", "SECRET_KEY")
MODEL_KEYS = ("LIVE_MEMORY_SOURCE_MODEL_ID", "LIVE_MEMORY_SOURCE_MODEL_REVISION")
TASKS = {"offline": "memoryEvaluationOfflineTest", "live": "liveMemoryAcceptance",
         "browser": "memoryGovernanceBrowserAcceptance"}
# 只传运行工具链和容器客户端所需环境，拒绝偶然继承业务连接及付费入口开关。
HOST_KEYS = {"PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP",
             "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "HOME", "APPDATA", "LOCALAPPDATA",
             "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)", "JAVA_HOME", "GRADLE_HOME",
             "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY",
             "NODE_BINARY", "PLAYWRIGHT_BROWSERS_PATH", "LANG", "LC_ALL", "LC_CTYPE"}
DEFAULT_GRADLE_HOME = str(Path(__file__).resolve().parents[2] / ".gradle-user-home")
UNSAFE_BATCH_ARGUMENT = re.compile(r'["\r\n&|<>^%!]' )


class LaunchError(ValueError):
    """启动前置不满足时只报告安全字段名或错误编码，不携带配置值。"""


def read_source_env(path: Path) -> dict[str, str]:
    """解析四个来源键和两个模型选择键，不插值、不反转义、不执行内容。"""
    result = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key not in SOURCE_KEYS + MODEL_KEYS:
            continue
        if key in result:
            raise LaunchError("重复字段：" + key)
        value = value.strip()
        if value.startswith(('"', "'")):
            if len(value) < 2 or value[-1] != value[0]:
                raise LaunchError("引号格式错误字段：" + key)
            value = value[1:-1]
        result[key] = value
    missing = [key for key in SOURCE_KEYS + MODEL_KEYS if not result.get(key) or not result[key].strip()]
    if missing:
        raise LaunchError("缺少字段：" + ", ".join(missing))
    for key, upper in zip(MODEL_KEYS, (2**63 - 1, 2**31 - 1)):
        value = result[key]
        if not re.fullmatch(r"[1-9][0-9]{0,18}", value) or int(value) > upper:
            raise LaunchError("无效模型选择字段：" + key)
    return result


def safe_environment(source: dict[str, str] | None, evidence: Path | None,
                     suite: Path, command: str, host: dict[str, str] | None = None,
                     gradle_home: Path | None = None) -> dict[str, str]:
    """重建进程环境，显式冻结 live 模型修订和套件，非 live 不拥有来源凭据。"""
    values = os.environ if host is None else host
    result = {key: value for key, value in values.items() if key.upper() in HOST_KEYS}
    # 保留 ASCII 目录联接别名；resolve 会穿过 Junction 回到中文用户目录。
    result["GRADLE_USER_HOME"] = str((gradle_home or Path(DEFAULT_GRADLE_HOME)).absolute())
    if command in ("live", "browser"):
        result["LIVE_MEMORY_EVIDENCE_DIR"] = str(evidence)
    if command == "live":
        if source is None:
            raise LaunchError("live 缺少显式来源配置")
        result.update({key: source[key] for key in SOURCE_KEYS + MODEL_KEYS})
        result.update(SKILLFORGE_LIVE_MEMORY_ACCEPTANCE="1", LIVE_MEMORY_SUITE_FILE=str(suite))
    return result


def build_command(repo: Path, command: str, gradle_home: Path) -> list[str]:
    """Wrapper 参数均为固定任务和受检路径，不使用 shell 拼接或从环境构造代码。"""
    wrapper = repo / "backend-java" / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        raise LaunchError("仓库 Wrapper 不存在")
    if os.name == "nt" and any(UNSAFE_BATCH_ARGUMENT.search(str(value)) for value in (wrapper, gradle_home)):
        raise LaunchError("Wrapper 或 Gradle 缓存路径含不安全批处理字符")
    return [str(wrapper), "--gradle-user-home", str(gradle_home),
            ":skillforge-institution-service:" + TASKS[command], "--console=plain",
            "--no-parallel", "--max-workers=1", "--no-daemon"]


def write_receipt(path: Path, value: dict[str, Any]) -> None:
    """新批次只写安全启动元数据，不写来源连接、密钥或环境文件内容。"""
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def execution_summary(evidence: Path, suite: Path) -> dict[str, Any]:
    """校验完整冻结矩阵与基础设施回执；语义 UNKNOWN 保留给离线复核。"""
    try:
        loaded = reporting.load_batch(evidence / "batch.json", suite)
        batch = loaded["batch"]
        expected = {(scenario["id"], repeat) for scenario in loaded["suite"]["scenarios"]
                    for repeat in range(1, loaded["suite"]["repeats"] + 1)}
        counts = {outcome: 0 for outcome in reporting.OUTCOMES}
        infrastructure_failures = 0
        for sample in loaded["samples"].values():
            value = sample["data"]
            counts[value["outcome"]] += 1
            infrastructure_failures += sum(failure.get("category") == "INFRASTRUCTURE"
                                           for failure in value.get("failures", []))
        counts["NOT_RUN"] += len(expected - loaded["samples"].keys())
        complete = (expected == loaded["samples"].keys() and batch.get("outcome") == "COMPLETED"
                    and batch.get("stopCode") is None and infrastructure_failures == 0)
        return {"plannedSamples": len(expected), "sampleCounts": counts,
                "infrastructureFailures": infrastructure_failures, "executionComplete": complete,
                "acceptanceOutcome": "FAIL" if counts["FAIL"] else (
                    "UNKNOWN" if counts["UNKNOWN"] or counts["NOT_RUN"] else "PASS")}
    except (reporting.ReportError, OSError, ValueError, TypeError, KeyError):
        return {"plannedSamples": 72, "executionComplete": False,
                "acceptanceOutcome": "UNKNOWN", "evidenceFailureCode": "LIVE_MEMORY_BATCH_EVIDENCE_INVALID"}


def main(argv: list[str] | None = None) -> int:
    """启动一次验收；真实入口必须显式启用，失败不自动重试收费请求。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs="?", choices=TASKS, default="offline")
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--evidence-dir", type=Path)
    parser.add_argument("--gradle-user-home", type=Path, default=Path(DEFAULT_GRADLE_HOME))
    parser.add_argument("--execute-live", action="store_true")
    parser.add_argument("--dry-run", action="store_true", help="只验证启动参数，不运行 Gradle 或创建证据目录")
    args = parser.parse_args(argv)
    try:
        if args.command == "live" and not args.execute_live:
            raise LaunchError("live 需要显式 --execute-live")
        if args.command == "live" and args.env_file is None:
            raise LaunchError("live 需要显式 --env-file")
        if args.command != "live" and args.env_file is not None:
            raise LaunchError("只有 live 可使用 --env-file")
        if args.command != "live" and args.execute_live:
            raise LaunchError("--execute-live 只用于 live")
        if args.command in ("live", "browser") and args.evidence_dir is None:
            raise LaunchError("live/browser 需要新 --evidence-dir")
        if args.command == "offline" and args.evidence_dir is not None:
            raise LaunchError("offline 不使用 --evidence-dir")
        evidence = args.evidence_dir.resolve() if args.evidence_dir else None
        if evidence is not None and evidence.exists():
            raise LaunchError("证据目录必须不存在，历史批次禁止覆盖或自动重跑")
        repo = Path(__file__).resolve().parents[2]
        suite = Path(__file__).resolve().with_name("suite.json")
        if not suite.is_file():
            raise LaunchError("固定套件不存在")
        source = read_source_env(args.env_file) if args.command == "live" else None
        # Gradle Worker 的参数文件路径必须使用调用者的目录别名，不解析其实际目标。
        gradle_home = args.gradle_user_home.absolute()
        environment = safe_environment(source, evidence, suite, args.command, gradle_home=gradle_home)
        command = build_command(repo, args.command, gradle_home)
        if args.dry_run:
            print("启动参数有效：" + TASKS[args.command] + "；未执行")
            return 0
        if evidence is None:
            return subprocess.run(command, cwd=repo / "backend-java", env=environment, check=False).returncode
        evidence.mkdir(parents=True, exist_ok=False)
        receipt = {"schemaVersion": 1, "command": args.command, "task": TASKS[args.command],
                   "startedAt": datetime.now(timezone.utc).isoformat(), "suite": str(suite),
                   "model": {"id": source[MODEL_KEYS[0]], "revision": int(source[MODEL_KEYS[1]])} if args.command == "live" else None,
                   "sourceKeys": list(SOURCE_KEYS) if source else [], "evidenceDir": str(evidence)}
        receipt["gradleUserHome"] = str(gradle_home)
        write_receipt(evidence / "launcher-start.json", receipt)
        print("执行 " + TASKS[args.command] + "；日志与证据：" + str(evidence), flush=True)
        with (evidence / "gradle.log").open("x", encoding="utf-8") as log:
            completed = subprocess.run(command, cwd=repo / "backend-java", env=environment,
                                       stdout=log, stderr=subprocess.STDOUT, check=False)
        summary = execution_summary(evidence, suite) if args.command == "live" else {}
        return_code = completed.returncode or (3 if summary and not summary["executionComplete"] else 0)
        write_receipt(evidence / "launcher-finish.json", {"schemaVersion": 1, "returnCode": return_code,
                       "gradleReturnCode": completed.returncode, "finishedAt": datetime.now(timezone.utc).isoformat(), **summary})
        print("验收退出码：" + str(return_code))
        return return_code
    except LaunchError as error:
        print(str(error), file=sys.stderr)
        return 2
    except (OSError, UnicodeError):
        # 原始异常可能包含来源字符串，启动器只输出分类，不透传值。
        print("无法读取配置、创建证据或启动 Wrapper；请检查本地文件和工具链", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
