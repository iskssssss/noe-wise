#!/usr/bin/env python3
"""从已验证归档创建无 Runtime 历史副本；保留全部结构和业务数据，不修改源库。"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shutil
import subprocess
import uuid

from runtime_conversion_backup import BackupFailure, GIB, MIN_FREE_GIB, monitor, object_count, sha256, validate_output


MANIFEST = Path(__file__).with_name("runtime_history_reset_tables.json")
TOC = re.compile(r"^\d+;\s+\d+\s+\d+\s+(TABLE DATA|SEQUENCE SET)\s+(\S+)\s+(\S+)\s+.+$")


def filter_toc(archive: Path, source_database: str) -> tuple[str, dict]:
    receipt_path = archive.with_suffix(".receipt.json")
    listing = archive.with_suffix(".list")
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    if receipt.get("status") != "VERIFIED_ARCHIVE" or receipt.get("archive_usable") is not True:
        raise BackupFailure("backup_receipt_not_verified")
    if archive.stat().st_size != receipt.get("size_bytes") or sha256(archive) != receipt.get("sha256"):
        raise BackupFailure("archive_size_or_sha256_mismatch")
    if object_count(listing) != receipt.get("object_count"):
        raise BackupFailure("backup_object_list_count_mismatch")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("source_database") != source_database:
        raise BackupFailure("fixed_manifest_source_database_mismatch")
    names = manifest["runtime_history_tables"] + manifest["host_history_tables"]
    tables = set(names)
    sequences = set(manifest["excluded_history_sequence_sets"])
    if len(names) != 161 or len(tables) != 161 or len(sequences) != 10:
        raise BackupFailure("fixed_manifest_counts_mismatch")
    schema = manifest["database_schema"]
    preserved = set(manifest["preserved_tables"])
    table_seen, sequence_seen = Counter(), Counter()
    result = []
    text = listing.read_text(encoding="utf-8")
    # 清单本身的来源头必须与显式源库一致，不能把其他归档套用到固定清理授权。
    databases = re.findall(r"^;\s+dbname:\s*(.*?)\s*$", text, re.MULTILINE)
    if databases != [source_database]:
        raise BackupFailure("archive_list_source_database_mismatch")
    for line in text.splitlines(keepends=True):
        match = TOC.match(line)
        if match:
            kind, item_schema, name = match.groups()
            if kind == "TABLE DATA":
                if item_schema == schema and name in tables:
                    table_seen[name] += 1
                    result.append("; excluded history: " + line)
                    continue
                if name.startswith(("agent_runtime_", "runtime_")) and (item_schema != schema or name not in preserved):
                    raise BackupFailure("unrecognized_runtime_table_data")
            elif item_schema == schema and name in sequences:
                sequence_seen[name] += 1
                result.append("; excluded history sequence: " + line)
                continue
        result.append(line)
    if set(table_seen) != tables or any(count != 1 for count in table_seen.values()):
        raise BackupFailure("fixed_history_table_data_coverage_mismatch")
    if set(sequence_seen) != sequences or any(count != 1 for count in sequence_seen.values()):
        raise BackupFailure("fixed_history_sequence_set_coverage_mismatch")
    return "".join(result), {
        "archive_size_bytes": receipt["size_bytes"], "archive_sha256": receipt["sha256"],
        "backup_object_count": receipt["object_count"], "excluded_table_data_count": 161,
        "excluded_sequence_set_count": 10, "source_list_sha256": sha256(listing),
        "manifest_sha256": sha256(MANIFEST),
    }


def psql(args: argparse.Namespace, sql: str, error, floor: int, directory: Path) -> str:
    command = ["docker", "exec", "-i", args.container, "psql", "-X", "-At", "--no-password",
               "--set=ON_ERROR_STOP=1", f"--username={args.user}", "--dbname=postgres"]
    # 管理查询只有存在性计数/CREATE结果，原库正文与凭据不进入 stdout。
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=error)
    try:
        stdout, _ = process.communicate(sql.encode("utf-8"), timeout=30)
    except BaseException:
        process.kill()
        process.wait()
        raise
    if process.returncode != 0:
        raise BackupFailure("target_database_preparation_failed", process.returncode)
    if shutil.disk_usage(directory).free < floor:
        raise BackupFailure("free_space_floor")
    return stdout.decode("utf-8").strip()


def restore(args: argparse.Namespace) -> int:
    archive = Path(args.archive).resolve()
    directory = Path(args.output_dir).resolve()
    stem = "runtime-reset-copy-" + uuid.uuid4().hex
    validate_output(directory, stem)
    directory.mkdir(parents=True, exist_ok=True)
    receipt = {"status": "FAILED", "restore_success": False, "target_created": False,
               "source_modified": False, "dry_run": args.dry_run,
               "min_free_bytes": int(args.min_free_gib * GIB)}
    receipt_file = directory / f"{stem}.receipt.json"
    filter_file = directory / f"{stem}.list"
    error_file = directory / f"{stem}.stderr"
    try:
        filtered, evidence = filter_toc(archive, args.source_database)
        receipt.update(evidence)
        with filter_file.open("x", encoding="utf-8", newline="\n") as output:
            output.write(filtered)
        receipt["filtered_list_sha256"] = sha256(filter_file)
        if args.dry_run:
            receipt.update(status="DRY_RUN_VALIDATED", reason="archive_and_fixed_toc_filter_verified")
            return 0
        if shutil.disk_usage(directory).free < receipt["min_free_bytes"]:
            raise BackupFailure("free_space_floor")
        with error_file.open("xb") as error:
            # 参数只允许标准标识符；仍分别用 SQL literal 与双引号标识符编码。
            literal = "'" + args.target_database.replace("'", "''") + "'"
            identifier = '"' + args.target_database.replace('"', '""') + '"'
            found = psql(args, "SELECT count(*) FROM pg_database WHERE datname = " + literal + ";\n",
                         error, receipt["min_free_bytes"], directory)
            if found != "0":
                raise BackupFailure("target_database_must_not_exist")
            receipt["target_creation_attempted"] = True
            receipt["target_created"] = None
            psql(args, "CREATE DATABASE " + identifier + ";\n", error, receipt["min_free_bytes"], directory)
            receipt["target_created"] = True
            container_list = f"/tmp/{stem}.list"
            copied = subprocess.run(["docker", "cp", str(filter_file), f"{args.container}:{container_list}"],
                                    stdout=subprocess.DEVNULL, stderr=error, check=False)
            if copied.returncode != 0:
                raise BackupFailure("filter_copy_failed", copied.returncode)
            with archive.open("rb") as source:
                process = subprocess.Popen([
                    "docker", "exec", "-i", args.container, "pg_restore", "--exit-on-error",
                    "--single-transaction", "--no-password", f"--username={args.user}",
                    f"--dbname={args.target_database}", f"--use-list={container_list}",
                ], stdin=source, stdout=subprocess.DEVNULL, stderr=error)
                code, observed = monitor(process, directory, receipt["min_free_bytes"], args.poll_seconds)
                receipt.update(restore_exit_code=code, minimum_observed_free_bytes=observed)
                if code != 0:
                    raise BackupFailure("restore_failed_target_retained", code)
        receipt.update(status="RESTORED_RESET_COPY", restore_success=True, reason="single_transaction_restore_completed")
    except BackupFailure as failure:
        receipt.update(reason=failure.reason, failed_stage_exit_code=failure.exit_code)
    except KeyboardInterrupt:
        receipt.update(reason="interrupted_target_retained")
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        receipt.update(reason="archive_or_local_process_rejected_target_retained")
    finally:
        receipt["completed_at_utc"] = datetime.now(timezone.utc).isoformat()
        with receipt_file.open("x", encoding="utf-8") as output:
            json.dump(receipt, output, ensure_ascii=False, indent=2)
            output.write("\n")
        print(json.dumps(receipt, ensure_ascii=False, sort_keys=True))
    return 0 if receipt["restore_success"] else 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source-database", "target-database", "container", "user", "archive", "output-dir"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--dry-run", action="store_true", help="核验归档和固定TOC过滤，不连接数据库")
    parser.add_argument("--min-free-gib", type=float, default=MIN_FREE_GIB)
    parser.add_argument("--poll-seconds", type=float, default=3)
    args = parser.parse_args()
    if args.source_database == args.target_database:
        parser.error("source and target databases must differ")
    for name in (args.source_database, args.target_database):
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,62}", name):
            parser.error("database names must be literal PostgreSQL identifiers")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", args.container):
        parser.error("container must be a literal container name or ID")
    if not (MIN_FREE_GIB <= args.min_free_gib < float("inf")):
        parser.error("min-free-gib must be finite and at least 6")
    if not 1 <= args.poll_seconds <= 10:
        parser.error("poll-seconds must be between 1 and 10")
    return args


if __name__ == "__main__":
    try:
        raise SystemExit(restore(parse_args()))
    except BackupFailure as failure:
        print(json.dumps({"status": "FAILED", "restore_success": False, "reason": failure.reason}))
        raise SystemExit(1)
