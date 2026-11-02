#!/usr/bin/env python3
"""在转换前生成独立压缩备份；只读指定数据库，失败保留 partial，不修改原库。"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
from datetime import datetime, timezone
from unittest.mock import Mock, patch
import uuid


ROOT = Path(__file__).resolve().parents[2]
GIB = 1024**3
MIN_FREE_GIB = 6.0


class BackupFailure(Exception):
    def __init__(self, reason: str, exit_code: int | None = None):
        self.reason = reason
        self.exit_code = exit_code
        super().__init__(reason)


def stop_process(process: subprocess.Popen) -> int:
    """结束本工具拥有的客户端进程并取得退出状态，不操作数据库或其他容器进程。"""
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
    return process.returncode


def monitor(process: subprocess.Popen, directory: Path, floor: int, interval: float) -> tuple[int, int]:
    observed = shutil.disk_usage(directory).free
    try:
        while True:
            observed = min(observed, shutil.disk_usage(directory).free)
            if observed < floor:
                raise BackupFailure("free_space_floor", stop_process(process))
            code = process.poll()
            if code is not None:
                return code, observed
            time.sleep(interval)
    except BaseException:
        stop_process(process)
        raise


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def object_count(path: Path) -> int:
    # pg_restore --list 的正文可能包含业务对象名称，只返回有编号的 TOC 条目计数。
    with path.open("r", encoding="utf-8", errors="replace") as source:
        return sum(bool(re.match(r"^\d+;\s", line)) for line in source)


def validate_output(directory: Path, name: str) -> None:
    if not directory.is_relative_to(ROOT):
        raise BackupFailure("output_must_be_repository_ignored_directory")
    relative = (directory / f"{name}.receipt.json").relative_to(ROOT)
    ignored = subprocess.run(
        ["git", "-C", str(ROOT), "check-ignore", "--quiet", "--", relative.as_posix()],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
    )
    if ignored.returncode != 0:
        raise BackupFailure("output_must_be_repository_ignored_directory")


def backup(args: argparse.Namespace) -> int:
    directory = Path(args.output_dir).resolve()
    name = args.name or datetime.now(timezone.utc).strftime("runtime-%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    validate_output(directory, name)
    directory.mkdir(parents=True, exist_ok=True)
    paths = {kind: directory / f"{name}{suffix}" for kind, suffix in {
        "partial": ".dump.partial", "archive": ".dump", "list": ".list",
        "dump_error": ".dump.stderr", "list_error": ".list.stderr", "receipt": ".receipt.json",
    }.items()}
    if any(path.exists() for path in paths.values()):
        raise BackupFailure("output_already_exists")
    compression_method, compression_level = args.compression.split(":", 1)
    receipt = {"status": "FAILED", "reason": "not_completed", "archive_usable": False,
               "format": "custom", "compression_method": compression_method,
               "compression_level": int(compression_level), "restoration_verified": False,
               "verification_scope": "dump_exit_and_archive_object_list",
               "min_free_bytes": int(args.min_free_gib * GIB)}
    process = None
    try:
        if shutil.disk_usage(directory).free < receipt["min_free_bytes"]:
            raise BackupFailure("free_space_floor")
        # 禁止 shell 拼接与 PTY：docker stdout 直接写二进制文件，凭据只使用容器现有配置。
        command = ["docker", "exec", args.container, "pg_dump", "-Fc",
                   f"--compress={compression_method}:level={compression_level}", "--no-password",
                   f"--username={args.user}", f"--dbname={args.database}"]
        with paths["partial"].open("xb") as archive, paths["dump_error"].open("xb") as error:
            process = subprocess.Popen(command, stdout=archive, stderr=error)
            code, observed = monitor(process, directory, receipt["min_free_bytes"], args.poll_seconds)
            receipt.update(dump_exit_code=code, minimum_observed_free_bytes=observed)
            if code != 0:
                raise BackupFailure("pg_dump_failed", code)
        if paths["partial"].stat().st_size == 0:
            raise BackupFailure("empty_archive")
        with paths["partial"].open("rb") as archive, paths["list"].open("xb") as listing, paths["list_error"].open("xb") as error:
            process = subprocess.Popen(["docker", "exec", "-i", args.container, "pg_restore", "--list"],
                                       stdin=archive, stdout=listing, stderr=error)
            code, observed = monitor(process, directory, receipt["min_free_bytes"], args.poll_seconds)
            receipt.update(list_exit_code=code,
                           minimum_observed_free_bytes=min(receipt["minimum_observed_free_bytes"], observed))
            if code != 0:
                raise BackupFailure("pg_restore_list_failed", code)
        count = object_count(paths["list"])
        if count == 0:
            raise BackupFailure("empty_archive_object_list")
        receipt.update(size_bytes=paths["partial"].stat().st_size, sha256=sha256(paths["partial"]), object_count=count)
        paths["partial"].rename(paths["archive"])
        receipt.update(status="VERIFIED_ARCHIVE", reason="dump_and_archive_list_verified", archive_usable=True)
    except BackupFailure as failure:
        receipt.update(reason=failure.reason, failed_stage_exit_code=failure.exit_code)
    except KeyboardInterrupt:
        if process is not None:
            stop_process(process)
        receipt.update(reason="interrupted")
    except OSError:
        if process is not None:
            stop_process(process)
        receipt.update(reason="local_or_process_io_failed")
    finally:
        if paths["partial"].exists():
            receipt["partial_size_bytes"] = paths["partial"].stat().st_size
        receipt["completed_at_utc"] = datetime.now(timezone.utc).isoformat()
        # receipt 与 stdout 不带数据库、容器、用户名、命令或 stderr；完整对象清单仅存忽略目录。
        with paths["receipt"].open("x", encoding="utf-8") as output:
            json.dump(receipt, output, ensure_ascii=False, indent=2)
            output.write("\n")
        print(json.dumps(receipt, ensure_ascii=False, sort_keys=True))
    return 0 if receipt["archive_usable"] else 1


def self_check() -> int:
    """轻量自检不连接 Docker/数据库，只核对文件摘要、TOC 计数和低空间停止行为。"""
    with tempfile.TemporaryDirectory(prefix="runtime-backup-check-") as temporary:
        directory = Path(temporary)
        body = directory / "body"
        body.write_bytes(b"abc")
        assert sha256(body) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        listing = directory / "toc"
        listing.write_text("; Archive\n1; 0 1 TABLE public sample owner\n2; 0 1 TABLE DATA public sample owner\n", encoding="utf-8")
        assert object_count(listing) == 2
        process = Mock()
        process.poll.side_effect = [None, -15]
        process.returncode = -15
        with patch.object(shutil, "disk_usage", return_value=Mock(free=5 * GIB)):
            try:
                monitor(process, directory, 6 * GIB, 3)
                raise AssertionError("low-space process must be stopped")
            except BackupFailure as failure:
                assert failure.reason == "free_space_floor"
                process.terminate.assert_called_once()
    print(json.dumps({"self_check": "PASSED", "database_accessed": False}))
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--container")
    parser.add_argument("--database")
    parser.add_argument("--user")
    parser.add_argument("--output-dir")
    parser.add_argument("--name", help="只允许字母、数字、点、下划线与短横线；已有备份拒绝覆盖")
    parser.add_argument("--compression", choices=("gzip:6", "zstd:9"), default="zstd:9",
                        help="使用 PostgreSQL 原生归档压缩；默认 zstd:9，要求 pg_dump 构建支持 zstd")
    parser.add_argument("--min-free-gib", type=float, default=MIN_FREE_GIB)
    parser.add_argument("--poll-seconds", type=float, default=3)
    args = parser.parse_args()
    if args.self_check:
        return args
    if not all((args.container, args.database, args.user, args.output_dir)):
        parser.error("--container, --database, --user, --output-dir must be explicit")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", args.container):
        parser.error("container must be a literal container name or ID")
    if args.name and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", args.name):
        parser.error("name must be a literal filename stem")
    if not (MIN_FREE_GIB <= args.min_free_gib < float("inf")):
        parser.error("min-free-gib must be finite and at least 6")
    if not (1 <= args.poll_seconds <= 10):
        parser.error("poll-seconds must be between 1 and 10")
    return args


if __name__ == "__main__":
    arguments = parse_args()
    try:
        raise SystemExit(self_check() if arguments.self_check else backup(arguments))
    except BackupFailure as failure:
        print(json.dumps({"status": "FAILED", "archive_usable": False, "reason": failure.reason}))
        raise SystemExit(1)
