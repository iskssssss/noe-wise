#!/usr/bin/env python3
"""导出固定清单内的业务配置归档；排除历史正文，保留完整结构，源库仅只读访问。"""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess

from runtime_conversion_backup import BackupFailure, GIB, monitor, sha256, validate_output


MANIFEST = Path(__file__).with_name("runtime_history_reset_tables.json")
ENTRY = re.compile(r"^\d+;\s+\d+\s+\d+\s+(TABLE DATA|SEQUENCE SET|TABLE)\s+(\S+)\s+(\S+)\s+.+$")


def validate_toc(text: str, manifest: dict) -> tuple[str, dict]:
    history = set(manifest["runtime_history_tables"] + manifest["host_history_tables"])
    protected = set(manifest["preserved_tables"])
    sequences = set(manifest["excluded_history_sequence_sets"])
    data, structure, sequence_seen = Counter(), Counter(), Counter()
    filtered = []
    if re.findall(r"^;\s+dbname:\s*(.*?)\s*$", text, re.MULTILINE) != [manifest["source_database"]]:
        raise BackupFailure("toc_source_database_mismatch")
    for line in text.splitlines(keepends=True):
        entry = ENTRY.match(line)
        if entry:
            kind, schema, name = entry.groups()
            if schema == manifest["database_schema"]:
                if kind == "TABLE DATA":
                    if name in history:
                        raise BackupFailure("history_data_found_in_business_archive")
                    if name.startswith(("agent_runtime_", "runtime_")) and name not in protected:
                        raise BackupFailure("unrecognized_runtime_table_data")
                    data[name] += 1
                elif kind == "TABLE":
                    structure[name] += 1
                elif name in sequences:
                    sequence_seen[name] += 1
                    filtered.append("; excluded history sequence: " + line)
                    continue
        filtered.append(line)
    if any(data[name] != 1 for name in protected):
        raise BackupFailure("protected_table_data_coverage_mismatch")
    if any(structure[name] != 1 for name in history | protected):
        raise BackupFailure("table_structure_coverage_mismatch")
    if set(sequence_seen) != sequences or any(count != 1 for count in sequence_seen.values()):
        raise BackupFailure("history_sequence_set_coverage_mismatch")
    return "".join(filtered), {"excluded_history_table_count": len(history),
                                "history_table_data_count": 0,
                                "protected_table_data_count": len(protected),
                                "preserved_table_structure_count": len(history | protected),
                                "filtered_history_sequence_set_count": len(sequence_seen)}


def run(args: argparse.Namespace) -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    history = manifest["runtime_history_tables"] + manifest["host_history_tables"]
    if manifest["source_database"] != args.database or manifest["schema_version"] != 1:
        raise BackupFailure("fixed_manifest_database_mismatch")
    if len(history) != 161 or len(set(history)) != 161 or len(manifest["preserved_tables"]) != 56:
        raise BackupFailure("fixed_manifest_counts_mismatch")
    directory = Path(args.output_dir).resolve()
    validate_output(directory, args.name)
    directory.mkdir(parents=True, exist_ok=True)
    paths = {kind: directory / f"{args.name}{suffix}" for kind, suffix in {
        "partial": ".dump.partial", "archive": ".dump", "list": ".list",
        "filter": ".filtered.list", "error": ".stderr", "receipt": ".receipt.json",
    }.items()}
    if any(path.exists() for path in paths.values()):
        raise BackupFailure("output_already_exists")
    receipt = {"status": "FAILED", "archive_usable": False, "source_modified": False,
               "purpose": "business_configuration_copy", "compression_method": "zstd", "compression_level": 9,
               "min_free_bytes": 6 * GIB, "manifest_sha256": sha256(MANIFEST), "restoration_verified": False}
    try:
        command = ["docker", "exec", args.container, "pg_dump", "-Fc", "--compress=zstd:level=9",
                   "--no-password", f"--username={args.user}", f"--dbname={args.database}"]
        command.extend(f"--exclude-table-data={manifest['database_schema']}.{name}" for name in history)
        with paths["partial"].open("xb") as archive, paths["error"].open("xb") as error:
            process = subprocess.Popen(command, stdout=archive, stderr=error)
            code, observed = monitor(process, directory, receipt["min_free_bytes"], 3)
            receipt.update(dump_exit_code=code, minimum_observed_free_bytes=observed)
            if code:
                raise BackupFailure("business_pg_dump_failed", code)
        with paths["partial"].open("rb") as archive, paths["list"].open("xb") as listing, paths["error"].open("ab") as error:
            process = subprocess.Popen(["docker", "exec", "-i", args.container, "pg_restore", "--list"],
                                       stdin=archive, stdout=listing, stderr=error)
            code, observed = monitor(process, directory, receipt["min_free_bytes"], 3)
            receipt.update(list_exit_code=code,
                           minimum_observed_free_bytes=min(receipt["minimum_observed_free_bytes"], observed))
            if code:
                raise BackupFailure("business_archive_list_failed", code)
        filtered, evidence = validate_toc(paths["list"].read_text(encoding="utf-8"), manifest)
        receipt.update(evidence)
        with paths["filter"].open("x", encoding="utf-8", newline="\n") as output:
            output.write(filtered)
        receipt.update(size_bytes=paths["partial"].stat().st_size, sha256=sha256(paths["partial"]),
                       source_list_sha256=sha256(paths["list"]), filtered_list_sha256=sha256(paths["filter"]))
        paths["partial"].rename(paths["archive"])
        receipt.update(status="VERIFIED_ARCHIVE", archive_usable=True, reason="business_archive_and_fixed_toc_verified")
    except BackupFailure as failure:
        receipt.update(reason=failure.reason, failed_stage_exit_code=failure.exit_code)
    except KeyboardInterrupt:
        receipt.update(reason="interrupted")
    except (OSError, ValueError, KeyError):
        receipt.update(reason="archive_io_or_manifest_failed")
    finally:
        if paths["partial"].exists():
            receipt["partial_size_bytes"] = paths["partial"].stat().st_size
        receipt["completed_at_utc"] = datetime.now(timezone.utc).isoformat()
        with paths["receipt"].open("x", encoding="utf-8") as output:
            json.dump(receipt, output, indent=2, ensure_ascii=False)
            output.write("\n")
        print(json.dumps(receipt, sort_keys=True, ensure_ascii=False))
    return 0 if receipt["archive_usable"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("container", "database", "user", "output-dir", "name"):
        parser.add_argument("--" + name, required=True)
    arguments = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", arguments.container):
        parser.error("container must be literal")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", arguments.name):
        parser.error("name must be a literal filename stem")
    try:
        raise SystemExit(run(arguments))
    except BackupFailure as failure:
        print(json.dumps({"status": "FAILED", "archive_usable": False, "reason": failure.reason}))
        raise SystemExit(1)
