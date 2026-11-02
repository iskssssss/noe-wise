"""全项目测试执行器：冻结输入、串行运行、保留每轮原始失败和新鲜证据。"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
import time
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]


def save(path, value):
    """原子替换本执行器的状态文件，不覆盖历史用例证据。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def git(*args):
    """查询工作区事实；凭据和忽略目录不进入快照。"""
    return subprocess.check_output(["git", "-c", f"safe.directory={REPO.as_posix()}", *args], cwd=REPO)


def snapshot():
    """同 HEAD 的未提交改动仍逐文件绑定，防止复用错误候选。"""
    paths = sorted(set(git("ls-files", "-z").decode("utf-8").split("\0") +
                       git("ls-files", "--others", "--exclude-standard", "-z").decode("utf-8").split("\0")))
    files = {}
    for name in paths:
        path = REPO / name
        if name and path.is_file() and not name.startswith(".agent-work-state/"):
            files[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return {"head": git("rev-parse", "HEAD").decode().strip(),
            "status": git("status", "--short").decode("utf-8"), "files": files,
            "digest": hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(),
            "captured_at": datetime.now(timezone.utc).isoformat()}


def load_environment(path):
    """显式本地环境只注入本次子进程，禁止命令文本插值和配置值输出。"""
    environment = dict(os.environ)
    if path:
        for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
            match = re.fullmatch(r"\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)", line)
            if match:
                value = match[2].strip()
                if len(value) > 1 and value[0] == value[-1] and value[0] in "\"'":
                    value = value[1:-1]
                environment[match[1]] = value
    return environment


def collect_xml(since, destination, patterns):
    """只复制本次命令实际更新的 XML，跳过旧报告；按 testcase 统计。"""
    rows = []
    for pattern in patterns:
        for source in sorted(REPO.glob(pattern)):
            if not source.is_file() or source.stat().st_mtime < since - 1:
                continue
            relative = source.relative_to(REPO)
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            root = ET.parse(source).getroot()
            cases = list(root.iter("testcase"))
            rows.append({"path": relative.as_posix(), "tests": len(cases),
                         "failures": sum(x.find("failure") is not None for x in cases),
                         "errors": sum(x.find("error") is not None for x in cases),
                         "skipped": sum(x.find("skipped") is not None for x in cases),
                         "failed_cases": [{"name": x.get("name"), "class": x.get("classname"),
                                          "message": (x.find("failure") if x.find("failure") is not None else x.find("error")).get("message", "")[:1500]}
                                         for x in cases if x.find("failure") is not None or x.find("error") is not None]})
    return rows


def execute(root, specification, environment):
    """一次命令是一条不可隐式重试的历史记录，失败后继续独立套件。"""
    lane = root / specification["id"]
    lane.mkdir(parents=True, exist_ok=False)
    environment = dict(environment)
    substitutions = {"{suite_root}": str(lane.resolve()), "{round_root}": str(root.resolve()), "{repo}": str(REPO)}
    for name, raw in specification.get("environment", {}).items():
        value = str(raw)
        for placeholder, target in substitutions.items():
            value = value.replace(placeholder, target)
        environment[name] = value
    started = time.time()
    record = {"id": specification["id"], "command": specification["command"],
              "cwd": specification.get("cwd", "."), "started_at": datetime.now(timezone.utc).isoformat(),
              "status": "RUNNING"}
    save(lane / "result.json", record)
    print(f"START {specification['id']}", flush=True)
    command = list(specification["command"])
    if os.name == "nt" and command[0].lower().endswith((".cmd", ".bat")):
        command[0] = str((REPO / specification.get("cwd", ".") / command[0]).resolve()) if "/" in command[0] or "\\" in command[0] else (shutil.which(command[0]) or command[0])
    with (lane / "command.log").open("w", encoding="utf-8") as stream:
        process = subprocess.Popen(command, cwd=REPO / specification.get("cwd", "."), env=environment,
                                   stdout=stream, stderr=subprocess.STDOUT, shell=False)
        record["pid"] = process.pid
        save(lane / "result.json", record)
        try:
            code = process.wait(timeout=specification.get("timeout_seconds", 7200))
        except subprocess.TimeoutExpired:
            # 仅终止本命令拥有的进程树，保留所有测试失败文件。
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=stream, stderr=stream)
            else:
                process.kill()
            code = process.wait(timeout=30)
            record["timed_out"] = True
    rows = collect_xml(started, lane / "evidence", specification.get("xml", []))
    output = (lane / "command.log").read_text(encoding="utf-8", errors="replace")
    count = sum(x["tests"] for x in rows)
    if not rows:
        matches = re.findall(r"Ran (\d+) tests?", output)
        if matches:
            count = sum(map(int, matches))
        elif "# tests " in output:
            count = sum(map(int, re.findall(r"# tests (\d+)", output)))
    failures = sum(x["failures"] + x["errors"] for x in rows)
    skips = sum(x["skipped"] for x in rows)
    if not rows:
        failures = sum(map(int, re.findall(r"(?:failures|errors)=(\d+)", output)))
        skips = sum(map(int, re.findall(r"skipped=(\d+)", output)))
        if "# tests " in output:
            failures += sum(map(int, re.findall(r"# fail (\d+)", output)))
            skips += sum(map(int, re.findall(r"# skipped (\d+)", output)))
    record.update({"exit_code": code, "elapsed_seconds": round(time.time() - started, 2),
                   "finished_at": datetime.now(timezone.utc).isoformat(), "test_count": count,
                   "failures": failures, "skipped": skips, "junit": rows,
                   "status": "FAIL" if code != 0 or failures else
                             "UNKNOWN" if specification.get("requires_tests", True) and (count == 0 or count == skips) else "PASS"})
    if specification.get("xml") and not rows:
        record["evidence_issue"] = "no_fresh_junit_xml"
    save(lane / "result.json", record)
    print(f"END {specification['id']} {record['status']} tests={count} failures={failures} skipped={skips}", flush=True)
    return record


def main():
    """默认三轮，轮次和输入冻结由本次显式 JSON 计划定义。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--snapshot-only", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    if not root.is_relative_to((REPO / ".runtime-logs").resolve()):
        parser.error("证据必须放在仓库 .runtime-logs 私有子目录")
    root.mkdir(parents=True, exist_ok=True)
    frozen = snapshot()
    save(root / "candidate-before.json", frozen)
    if args.snapshot_only:
        print(json.dumps({"head": frozen["head"], "files": len(frozen["files"]), "digest": frozen["digest"]}))
        return
    plan = json.loads(args.plan.read_text(encoding="utf-8-sig"))
    save(root / "plan.json", plan)
    environment = load_environment(args.env_file)
    results = []
    for number in range(1, plan.get("rounds", 3) + 1):
        for specification in plan["suites"]:
            results.append({"round": number, **execute(root / f"round-{number}", specification, environment)})
            save(root / "summary.json", results)
    after = snapshot()
    save(root / "candidate-after.json", after)
    save(root / "input-binding.json", {"before": frozen["digest"], "after": after["digest"],
         "unchanged": frozen["digest"] == after["digest"],
         "changed_paths": [p for p in frozen["files"].keys() | after["files"].keys() if frozen["files"].get(p) != after["files"].get(p)]})
    sys.exit(1 if any(r["status"] != "PASS" for r in results) else 0)


if __name__ == "__main__":
    main()
