"""只在全新容器或明确临时目录中创建夹具集群，验证逻辑恢复、文件摘要和WAL时间点恢复。

docker build -f tools/operations/Dockerfile.drill -t skillforge-recovery-drill .
docker run --rm --network none -v <repo>:/repo:ro skillforge-recovery-drill
演练不发布端口，不挂载业务库目录，不读取.env，结束后仅停止自己创建的集群。
"""
from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

import backup_restore as recovery


def call(arguments: list[str]) -> str:
    """演练命令失败保留工具退出码，不回显认证信息。"""
    result = subprocess.run(arguments, capture_output=True, text=True, check=False, timeout=120)
    if result.returncode != 0: raise AssertionError(f"演练工具失败:{arguments[0]}:{result.returncode}")
    return result.stdout.strip()


def sql(port: int, command: str, database: str = "fixture") -> str:
    """仅向当前演练私有端口发送固定测试SQL。"""
    return call(["psql", "-h", "127.0.0.1", "-p", str(port), "-U", "postgres", "-d", database,
                 "-At", "-v", "ON_ERROR_STOP=1", "-c", command])


def main() -> None:
    """创建新的源集群和隔离目标，用真实PostgreSQL工具完成恢复证据。"""
    root = Path(tempfile.mkdtemp(prefix="skillforge-recovery-drill-"))
    data = root / "source"
    archive = root / "archive"; archive.mkdir()
    started: list[Path] = []
    try:
        call(["initdb", "-D", str(data), "--auth=trust", "--username=postgres", "--no-locale"])
        with (data / "postgresql.conf").open("a") as config:
            config.write(f"\narchive_mode=on\narchive_command='test ! -f {archive}/%f && cp %p {archive}/%f'\n")
        call(["pg_ctl", "-D", str(data), "-l", str(root / "source.log"), "-o", "-p 55432 -h 127.0.0.1", "-w", "start"])
        started.append(data)
        sql(55432, "CREATE DATABASE fixture", "postgres")
        sql(55432, "CREATE TABLE evidence(id integer PRIMARY KEY,value text); INSERT INTO evidence VALUES(1,'baseline')")
        files = root / "frozen-files"; files.mkdir(); (files / "artifact.txt").write_text("不可变成果", encoding="utf-8")
        config = root / "deployment.conf"; config.write_text("password=fixture-secret-not-to-copy", encoding="utf-8")
        passfile = root / "passfile"; passfile.write_text("127.0.0.1:*:*:postgres:fixture", encoding="utf-8"); passfile.chmod(0o600)
        common = dict(source_host="127.0.0.1", source_port=55432, source_user="postgres", source_database="fixture",
                      source_confirmation="fixture", passfile=str(passfile), sslmode="disable", bin_dir="/usr/local/bin",
                      snapshot_root=[str(files)], config=[str(config)], wal_archive=str(archive))
        logical = recovery.backup(argparse.Namespace(**common, output=str(root / "logical")))
        restored = recovery.restore(argparse.Namespace(bundle=str(logical), target_host="127.0.0.1", target_port=55432,
                      target_user="postgres", target_database="skillforge_restore_fixture", target_confirmation="skillforge_restore_fixture",
                      target_files=str(root / "restored-files"), passfile=str(passfile), sslmode="disable", bin_dir="/usr/local/bin"))
        assert sql(55432, "SELECT value FROM evidence", restored) == "baseline"
        assert recovery.digest(root / "restored-files/root-0/artifact.txt") == recovery.digest(files / "artifact.txt")
        assert not (root / "restored-files/deployment.conf").exists()
        # 重复恢复必须在createdb之前拒绝，原目标内容不变。
        try:
            recovery.restore(argparse.Namespace(bundle=str(logical), target_host="127.0.0.1", target_port=55432,
                 target_user="postgres", target_database=restored, target_confirmation=restored,
                 target_files=str(root / "another-files"), passfile=str(passfile), sslmode="disable", bin_dir="/usr/local/bin"))
            raise AssertionError("既有目标错误放行")
        except recovery.RecoveryError: pass
        assert sql(55432, "SELECT count(*) FROM evidence", restored) == "1"
        physical = recovery.backup(argparse.Namespace(**common, output=str(root / "physical")), physical=True)
        sql(55432, "INSERT INTO evidence VALUES(2,'target-event')")
        moment = sql(55432, "SELECT clock_timestamp()")
        sql(55432, "INSERT INTO evidence VALUES(3,'after-target')")
        wal_name = sql(55432, "SELECT pg_walfile_name(pg_current_wal_lsn())")
        sql(55432, "SELECT pg_switch_wal()")
        deadline = time.monotonic() + 30
        while not (archive / wal_name).is_file():
            if time.monotonic() > deadline: raise AssertionError("测试WAL未归档")
            time.sleep(0.1)
        recovery.append_wal(argparse.Namespace(bundle=str(physical), wal_archive=str(archive)))
        target = root / "skillforge_restore_physical"
        recovery.stage_physical(argparse.Namespace(bundle=str(physical), target_data=str(target), target_confirmation=str(target),
                    target_port=55433, recovery_target_time=datetime.fromisoformat(moment).isoformat(), bin_dir="/usr/local/bin"))
        # 仅演练夹具容器显式授权私有loopback验证；生产stage默认拒绝连接，操作者需配置认证。
        (target / "pg_hba.conf").write_text("local all all trust\nhost all all 127.0.0.1/32 trust\n", encoding="utf-8")
        call(["pg_ctl", "-D", str(target), "-l", str(root / "target.log"), "-w", "start"]); started.append(target)
        deadline = time.monotonic() + 30
        while sql(55433, "SELECT pg_is_wal_replay_paused()", "postgres") != "t":
            if time.monotonic() > deadline: raise AssertionError("PITR未到达目标时间")
            time.sleep(0.1)
        assert sql(55433, "SELECT string_agg(value,',' ORDER BY id) FROM evidence") == "baseline,target-event"
        assert sql(55433, "SELECT pg_is_in_recovery()", "postgres") == "t"
        print(json.dumps({"logical_restore": "passed", "file_digest": "passed", "config_content_excluded": "passed",
                          "existing_target_rejected": "passed", "wal_pitr": "passed", "after_target_event_excluded": "passed",
                          "network": "none", "business_database_touched": False}, ensure_ascii=False))
    finally:
        for cluster in reversed(started):
            subprocess.run(["pg_ctl", "-D", str(cluster), "-m", "immediate", "-w", "stop"], capture_output=True, timeout=30)
        shutil.rmtree(root)


if __name__ == "__main__": main()
