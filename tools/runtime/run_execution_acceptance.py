"""Run current typed Runtime HTTP fixtures through the existing Gradle lane.

Default is a read-only plan. Resource provisioning belongs to the invoking owner.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]
MODULE = REPO / "backend-java/skillforge-institution-service"
PACKAGE = "com.skillforge.institution.integration."
FUNCTIONAL = (
    "ChatExecutionChainE2ETest", "ChatExecutionKnowledgeE2ETest",
    "ChatExecutionParallelToolsE2ETest", "ChatExecutionWaitingE2ETest",
    "ChatExecutionCompositionE2ETest", "ChatRunBudgetE2ETest",
)
GROUPS = {
    "http": FUNCTIONAL,
    "process": ("RuntimeExecutionProcessRestartE2ETest",),
    "program": ("ChatExecutionProgramE2ETest",),
    "capacity": ("ChatExecutionCapacityE2ETest",),
}
EXTERNAL = ("JDBC_URL", "DB_USER", "DB_PASSWORD", "REDIS_HOST", "REDIS_PORT",
            "FIXTURE_LANE", "FIXTURE_TOKEN", "SCHEMA_VERSION")


def selectors(lane: str) -> list[str]:
    """Select declared @Test methods only; inherited fixtures are not acceptance cases."""
    selected = []
    for name in GROUPS[lane]:
        source = (MODULE / "src/test/java" / Path(*PACKAGE.rstrip(".").split(".")) / f"{name}.java").read_text(encoding="utf-8")
        methods = re.findall(r"@Test\b(?:(?!\bvoid\b).)*?\bvoid\s+(\w+)\s*\(", source, re.S)
        if not methods:
            raise ValueError(f"No declared @Test methods: {name}")
        # Seven warm requests are a diagnostic comparison, not an additional functional contract.
        methods = [m for m in methods if m != "newExecutionChainWarmLatencyWithSevenIndependentSessions"]
        selected.extend(PACKAGE + name + "." + method for method in methods)
    return selected


def preflight(lane: str) -> None:
    if lane == "capacity" and any(os.environ.get(key) == "1" for key in ("SKILLFORGE_PERF_SQL", "SKILLFORGE_PERF_SQL_CALLERS", "SKILLFORGE_PERF_SQL_ROLES")):
        raise ValueError("Formal capacity rejects SQL profiler and caller sampling")
    required = []
    if lane == "process" or any(k.startswith("SKILLFORGE_S03_EXTERNAL_") for k in os.environ):
        required += ["SKILLFORGE_S03_EXTERNAL_" + name for name in EXTERNAL]
    if lane == "process":
        required += ["SKILLFORGE_RUNTIME_PROCESS_RESTART"]
        if os.environ.get("SKILLFORGE_RUNTIME_PROCESS_RESTART") != "1":
            raise ValueError("SKILLFORGE_RUNTIME_PROCESS_RESTART must be 1")
    if lane == "program":
        required += ["SKILLFORGE_RUNTIME_PROGRAM_E2E", "CODE_EXECUTION_SMOKE_ENDPOINT", "CODE_EXECUTION_SMOKE_TOKEN"]
        if os.environ.get("SKILLFORGE_RUNTIME_PROGRAM_E2E") != "1":
            raise ValueError("SKILLFORGE_RUNTIME_PROGRAM_E2E must be 1")
        if len(os.environ.get("CODE_EXECUTION_SMOKE_TOKEN", "")) < 32:
            raise ValueError("CODE_EXECUTION_SMOKE_TOKEN must contain at least 32 characters")
    missing = [name for name in required if not os.environ.get(name, "").strip()]
    if missing:
        raise ValueError("Missing fixture parameters: " + ", ".join(missing))


def verify_xml(directory: Path, expected: list[str]) -> dict:
    actual = {}
    for path in directory.glob("TEST-*.xml"):
        for case in ET.parse(path).getroot().iter("testcase"):
            key = case.attrib["classname"] + "." + case.attrib["name"].removesuffix("()")
            actual[key] = "FAILED" if case.find("failure") is not None or case.find("error") is not None else "SKIPPED" if case.find("skipped") is not None else "PASS"
    results = {key: actual.get(key, "NOT_RUN") for key in expected}
    return {"status": "PASS" if all(v == "PASS" for v in results.values()) else "FAILED", "cases": results}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("lane", choices=GROUPS)
    parser.add_argument("--execute", action="store_true", help="Explicitly start Gradle; default only prints selectors")
    parser.add_argument("--gradle-user-home", required=True, type=Path)
    parser.add_argument("--output", type=Path, help="New private evidence directory; required for execution")
    args = parser.parse_args()
    selected = selectors(args.lane)
    command = [str(REPO / "backend-java/gradlew.bat") if os.name == "nt" else str(REPO / "backend-java/gradlew"),
               "--gradle-user-home", str(args.gradle_user_home.resolve()), "--no-daemon", "--no-parallel", "--max-workers=2",
               "--no-build-cache", "-I", str(REPO / "tools/full_project_testing/force-tests.gradle"),
               ":skillforge-institution-service:runtimeLatencyE2E"]
    for selector in selected:
        command.extend(["--tests", selector])
    print(json.dumps({"lane": args.lane, "selectors": selected, "command": command}, ensure_ascii=False, indent=2))
    if not args.execute:
        return 0
    preflight(args.lane)
    if args.output is None:
        parser.error("--execute requires --output")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ)
    env["SKILLFORGE_RUNTIME_PROCESS_RESTART_EVIDENCE"] = str(output)
    xml = MODULE / "build/test-results/runtimeLatencyE2E"
    previous = {path.name: (path.stat().st_mtime_ns, path.stat().st_size) for path in xml.glob("TEST-*.xml")}
    # Gradle owns XML directory replacement. Capture after its natural exit; never consume stale XML on launch failure.
    with (output / "gradle.log").open("w", encoding="utf-8") as stream:
        completed = subprocess.run(command, cwd=REPO / "backend-java", env=env, stdout=stream, stderr=subprocess.STDOUT)
    captured = output / "junit"
    captured.mkdir()
    for path in xml.glob("TEST-*.xml"):
        if previous.get(path.name) != (path.stat().st_mtime_ns, path.stat().st_size):
            shutil.copy2(path, captured / path.name)
    result = verify_xml(captured, selected)
    if completed.returncode != 0:
        result["status"] = "FAILED"
    result.update({"gradleExitCode": completed.returncode, "gradleStatus": "PASS" if completed.returncode == 0 else "FAILED",
                   "selectors": selected, "resourceOwner": "invoker", "cleanup": "NOT_ASSERTED_BY_RUNNER"})
    (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
