"""在受控临时目录消费当前 SDK 正式发布，保留来源、严格锁、原生日志与非零测试证据。"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def sha256(path):
    """绑定实际文件字节，而不是声明的坐标或目录名。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fresh_directory(path):
    """只建立本次独占输出；目录非空时拒绝，不清理或覆盖历史证据。"""
    if not path.is_absolute() or (path.exists() and any(path.iterdir())):
        raise ValueError(f"absolute_new_empty_directory_required:{path}")
    path.mkdir(parents=True, exist_ok=True)


def main():
    """复制受控源到独立工程，执行现有消费范围并按实际退出/测试结果记录。"""
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("sdk-repository", "sdk-version", "work-dir", "reports-dir", "gradle-executable", "gradle-user-home"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--write-locks", action="store_true", help="Only prepare a lock for review; never validate or auto-update committed locks")
    arguments = parser.parse_args()
    if not re.fullmatch(r"[1-9][0-9]*\.[0-9]+\.[0-9]+", arguments.sdk_version):
        raise ValueError("exact_stable_publication_version_required")
    template = Path(__file__).resolve().parent / "project"
    work = Path(arguments.work_dir).resolve()
    reports = Path(arguments.reports_dir).resolve()
    repository = Path(arguments.sdk_repository).resolve()
    # 保留宿主显式提供的缓存路径；Windows Junction 解析会把英文入口还原成含中文的用户目录。
    cache = Path(arguments.gradle_user_home).absolute()
    gradle = Path(arguments.gradle_executable).resolve()
    if not repository.is_dir() or not gradle.is_file():
        raise ValueError("existing_isolated_repository_and_wrapper_required")
    if template == work or template in work.parents or work == reports or work in reports.parents or reports in work.parents:
        raise ValueError("source_work_and_reports_must_be_separate")
    if not arguments.write_locks and not (template / "gradle.lockfile").is_file():
        raise ValueError("reviewed_gradle_lockfile_required_before_validation")
    fresh_directory(work)
    fresh_directory(reports)
    source_rows = []
    for source in sorted(template.rglob("*")):
        if any(part in (".gradle", "build") for part in source.relative_to(template).parts):
            continue
        if source.is_symlink():
            raise ValueError("consumer_source_symlink_forbidden")
        if not source.is_file():
            continue
        destination = work / source.relative_to(template)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        source_rows.append({"path": source.relative_to(template).as_posix(), "sha256": sha256(source)})
    properties = "sdkVersion=" + arguments.sdk_version + "\nsdkRepository=" + repository.as_posix() + "\n"
    (work / "gradle.properties").write_text(properties, encoding="utf-8")
    environment = dict(os.environ)
    # Wrapper 在解析 Gradle 参数之前选择分发缓存；子进程环境必须与显式缓存参数一致。
    environment["GRADLE_USER_HOME"] = str(cache)
    temporary = work / "temp"
    temporary.mkdir()
    environment["TEMP"] = environment["TMP"] = environment["TMPDIR"] = str(temporary)
    cache.mkdir(parents=True, exist_ok=True)
    tasks = ["writeConsumerLocks", "verifyPublishedResolution", "--write-locks"] if arguments.write_locks else [
        "verifyPublishedResolution", "verifyConsumerStartup", "check", "verifyConsumerDatabase"]
    command = [str(gradle), "--no-daemon", "--no-parallel", "--max-workers=1", "--console=plain",
               "--gradle-user-home", str(cache), "-p", str(work)] + tasks
    if os.name == "nt":
        command = ["cmd.exe", "/d", "/c"] + command
    binding = {"sdk_version": arguments.sdk_version, "sdk_repository": str(repository),
               "consumer_source": source_rows, "work_directory": str(work), "gradle_user_home": str(cache),
               "properties_sha256": sha256(work / "gradle.properties"),
               "gradle_executable": str(gradle), "gradle_executable_sha256": sha256(gradle),
               "lock_mode": "STRICT", "operation": "LOCK_PREPARATION" if arguments.write_locks else "PUBLISHED_CONSUMPTION",
               "execution_status": "STARTED", "workflow_status": "NOT_ASSERTED_BY_LOCAL_COMMAND"}
    result_path = reports / "result.json"
    result_path.write_text(json.dumps(binding, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (reports / "gradle.log").open("wb") as log:
        completed = subprocess.run(command, env=environment, stdout=log, stderr=subprocess.STDOUT, check=False)
    binding["native_exit_code"] = completed.returncode
    binding["execution_status"] = "FAILED" if completed.returncode else "LOCKS_GENERATED" if arguments.write_locks else "PASSED"
    generated_reports = work / "build" / "reports"
    if generated_reports.exists():
        shutil.copytree(generated_reports, reports / "native-reports")
    native_test_results = work / "build" / "test-results"
    if native_test_results.exists():
        shutil.copytree(native_test_results, reports / "native-test-results")
    if arguments.write_locks:
        lock = work / "gradle.lockfile"
        if completed.returncode == 0 and not lock.is_file():
            binding["execution_status"] = "FAILED"
            binding["failure"] = "dependency_lock_not_generated"
        elif lock.is_file():
            shutil.copyfile(lock, reports / "proposed-gradle.lockfile")
            binding["lock_sha256"] = sha256(lock)
    elif completed.returncode == 0:
        suites = [ET.parse(path).getroot() for path in native_test_results.glob("*/TEST-*.xml")]
        binding["tests_by_task"] = {
            task: sum(int(ET.parse(path).getroot().get("tests", "0"))
                      for path in (native_test_results / task).glob("TEST-*.xml"))
            for task in ("test", "sqliteTest")
        }
        binding["published_fixture_contract_tests"] = sum(int(suite.get("tests", "0"))
                for suite in suites if suite.get("name", "").endswith("PublishedExecutionStoreContractTest"))
        binding["tests"] = sum(int(suite.get("tests", "0")) for suite in suites)
        binding["failures"] = sum(int(suite.get("failures", "0")) + int(suite.get("errors", "0")) for suite in suites)
        binding["skipped"] = sum(int(suite.get("skipped", "0")) for suite in suites)
        output = (reports / "gradle.log").read_text(encoding="utf-8", errors="replace")
        if (binding["tests"] < 5 or binding["published_fixture_contract_tests"] < 3 or binding["failures"] or binding["skipped"]
                or "SDK_PUBLISHED_HOST_STARTUP_COMPLETE" not in output
                 or not any("SDK_PUBLISHED_AGENT_CONSUMPTION_COMPLETE model=2 tool=1 rebuild=1"
                            in (suite.findtext("system-out") or "") for suite in suites)
                or any(count < 1 for count in binding["tests_by_task"].values())):
            binding["execution_status"] = "FAILED"
            binding["failure"] = "required_native_consumer_evidence_missing"
        binding["lock_sha256"] = sha256(work / "gradle.lockfile")
    result_path.write_text(json.dumps(binding, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"execution_status": binding["execution_status"], "result": str(result_path)}, ensure_ascii=False))
    return completed.returncode or (1 if binding["execution_status"] == "FAILED" else 0)


if __name__ == "__main__":
    sys.exit(main())
