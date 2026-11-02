"""追加绑定真实退出码、时间窗口及完整 JUnit 用例的纠正证据，不改原结果。"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]
TIME_TOLERANCE_SECONDS = 2


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def instant(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("timestamp_has_no_timezone")
    return parsed.astimezone(timezone.utc)


def write_new(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2)


def parse_junit(path: Path, started: datetime, finished: datetime) -> dict:
    """读取每个 testcase；suite 宣称数量与实际子节点不符时拒绝通过。"""
    root = ET.parse(path).getroot()
    if root.tag not in ("testsuite", "testsuites"):
        raise ValueError("invalid_junit_root")
    issues = []
    modified = datetime.fromtimestamp(path.stat().st_mtime, timezone.utc)
    low = started.timestamp() - TIME_TOLERANCE_SECONDS
    high = finished.timestamp() + TIME_TOLERANCE_SECONDS
    if not low <= modified.timestamp() <= high:
        issues.append("xml_not_fresh_for_command_window")
    cases = []
    identities = set()
    for case in root.iter("testcase"):
        name, classname = case.get("name", ""), case.get("classname", "")
        identity = (classname, name)
        if not name or not classname:
            issues.append("testcase_missing_identity")
        if identity in identities:
            issues.append("duplicate_testcase_identity")
        identities.add(identity)
        children = {tag: case.find(tag) for tag in ("failure", "error", "skipped")}
        outcomes = [tag for tag, child in children.items() if child is not None]
        if len(outcomes) > 1:
            issues.append("testcase_has_conflicting_outcomes")
        cases.append({"name": name, "classname": classname, "time": case.get("time"),
                      "outcome": outcomes[0] if outcomes else "passed",
                      "details": {tag: {"attributes": dict(child.attrib), "text": child.text or ""}
                                  for tag, child in children.items() if child is not None}})
    for suite in root.iter():
        if suite.tag not in ("testsuite", "testsuites"):
            continue
        descendants = list(suite.iter("testcase"))
        actual = {"tests": len(descendants),
                  "failures": sum(c.find("failure") is not None for c in descendants),
                  "errors": sum(c.find("error") is not None for c in descendants),
                  "skipped": sum(c.find("skipped") is not None for c in descendants)}
        if "tests" not in suite.attrib:
            issues.append("suite_missing_declared_test_count")
        for field, count in actual.items():
            if field in suite.attrib:
                try:
                    declared = int(suite.get(field, ""))
                except ValueError:
                    declared = -1
                if declared != count:
                    issues.append(f"suite_{field}_count_mismatch")
        if suite.tag == "testsuite":
            timestamp = suite.get("timestamp")
            if not timestamp:
                issues.append("suite_missing_timestamp")
            else:
                try:
                    if not low <= instant(timestamp).timestamp() <= high:
                        issues.append("suite_timestamp_outside_command_window")
                except ValueError:
                    issues.append("suite_invalid_timestamp")
    if not cases or all(c["outcome"] == "skipped" for c in cases):
        issues.append("no_executed_testcases")
    return {"sha256": digest(path), "modified_at": modified.isoformat(),
            "tests": len(cases), "failures": sum(c["outcome"] in ("failure", "error") for c in cases),
            "skipped": sum(c["outcome"] == "skipped" for c in cases),
            "testcases": cases, "issues": sorted(set(issues))}


def collect_lane(lane: Path, destination: Path, xml_relative: str) -> dict:
    """仅补采原命令目录的新鲜 XML，原 UNKNOWN/FAIL 与原始命令日志保持不变。"""
    destination.mkdir(parents=True, exist_ok=False)
    source_result = lane / "result.json"
    record = json.loads(source_result.read_text(encoding="utf-8-sig"))
    result = {"schema_version": 1, "source_lane": str(lane.resolve()),
              "original_status": record.get("status"), "id": record.get("id"),
              "command": record.get("command"), "cwd": record.get("cwd"),
              "pid": record.get("pid"), "exit_code": record.get("exit_code"),
              "started_at": record.get("started_at"), "finished_at": record.get("finished_at"),
              "original_result_sha256": digest(source_result),
              "collected_at": datetime.now(timezone.utc).isoformat(), "issues": [], "junit": []}
    issues = result["issues"]
    code = record.get("exit_code")
    if type(code) is not int or record.get("status") == "RUNNING":
        issues.append("command_has_no_final_exit_code")
    if not isinstance(record.get("command"), list) or not record.get("command"):
        issues.append("command_missing")
    try:
        started, finished = instant(record["started_at"]), instant(record["finished_at"])
        if finished < started:
            raise ValueError("finish_before_start")
    except (KeyError, TypeError, ValueError):
        issues.append("command_time_window_invalid")
        started = finished = datetime.fromtimestamp(0, timezone.utc)
    low = started.timestamp() - TIME_TOLERANCE_SECONDS
    high = finished.timestamp() + TIME_TOLERANCE_SECONDS
    if not low <= source_result.stat().st_mtime <= high:
        issues.append("result_file_outside_command_window")
    log = lane / "command.log"
    if not log.is_file() or log.stat().st_size == 0:
        issues.append("command_log_missing_or_empty")
    else:
        result["command_log_sha256"] = digest(log)
        if not low <= log.stat().st_mtime <= high:
            issues.append("command_log_outside_command_window")
        shutil.copy2(log, destination / "command.log")
    xml = (lane / xml_relative).resolve()
    if not xml.is_relative_to(lane.resolve()):
        raise ValueError("XML must belong to the exact original lane")
    if not xml.is_file():
        issues.append("junit_missing")
    else:
        shutil.copy2(xml, destination / "junit.xml")
        try:
            row = parse_junit(xml, started, finished)
            row["source_path"] = str(xml)
            result["junit"].append(row)
            issues.extend(row["issues"])
        except (ET.ParseError, ValueError) as failure:
            issues.append(f"junit_invalid:{failure}")
    count = sum(row["tests"] for row in result["junit"])
    failures = sum(row["failures"] for row in result["junit"])
    skipped = sum(row["skipped"] for row in result["junit"])
    result.update({"test_count": count, "failures": failures, "skipped": skipped,
                   "issues": sorted(set(issues)),
                   "status": "FAIL" if type(code) is int and code != 0 or failures else
                             "UNKNOWN" if issues or not count or count == skipped else "PASS"})
    write_new(destination / "correction.json", result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--suite", action="append", required=True)
    parser.add_argument("--xml-relative", default="junit.xml")
    args = parser.parse_args()
    source, output = args.source_root.resolve(), args.output.resolve()
    private = (REPO / ".runtime-logs").resolve()
    if not source.is_relative_to(private) or not output.is_relative_to(private):
        parser.error("来源与追加证据必须位于 .runtime-logs")
    output.mkdir(parents=True, exist_ok=False)
    rounds = sorted(source.glob("round-*"))
    if not rounds:
        parser.error("来源没有正式轮次")
    results = []
    for round_root in rounds:
        for suite in args.suite:
            if not (round_root / suite / "result.json").is_file():
                parser.error(f"来源套件缺失: {round_root.name}/{suite}")
            results.append({"round": round_root.name, **collect_lane(
                round_root / suite, output / round_root.name / suite, args.xml_relative)})
    write_new(output / "correction-summary.json", results)
    print(json.dumps([{key: row[key] for key in ("round", "id", "original_status", "status", "test_count", "failures", "issues")}
                      for row in results], ensure_ascii=False, indent=2))
    return 1 if any(row["status"] != "PASS" for row in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
