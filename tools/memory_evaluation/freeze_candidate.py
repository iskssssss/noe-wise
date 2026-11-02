#!/usr/bin/env python3
"""冻结记忆评测的源码和已编译产物摘要，不读取环境文件或业务数据。"""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def freeze(repo: Path) -> dict:
    """用相对路径和文件摘要绑定共享工作区中的实际评测候选。"""
    backend = repo / "backend-java"
    candidates = set(backend.glob("*.gradle.kts"))
    candidates.add(backend / "gradle.properties")
    candidates.update(backend.glob("*/build.gradle.kts"))
    for module in backend.iterdir():
        if not module.is_dir():
            continue
        for directory in (module / "src/main", module / "build/classes/java/main", module / "build/resources/main"):
            if directory.is_dir():
                candidates.update(path for path in directory.rglob("*") if path.is_file())
    live = backend / "skillforge-institution-service/src/liveMemoryAcceptance"
    candidates.update(path for path in live.rglob("*") if path.is_file())
    live_classes = backend / "skillforge-institution-service/build/classes/java/liveMemoryAcceptance"
    candidates.update(path for path in live_classes.rglob("*") if path.is_file())
    test_base = backend / "skillforge-institution-service/src/test/java/com/skillforge/institution"
    for relative in ("integration/AbstractIntegrationTest.java", "integration/MemoryProductizationIntegrationTest.java",
                     "runtime/MemoryEvaluationSourcePolicyTest.java", "memory/MemoryEvaluationCandidateGovernanceTest.java"):
        candidates.add(test_base / relative)
    for directory in (repo / "tools/memory_evaluation", repo / "frontend-admin/src"):
        candidates.update(path for path in directory.rglob("*")
                          if path.is_file() and "__pycache__" not in path.parts and "results" not in path.parts)
    for relative in ("frontend-admin/e2e/memory-governance.spec.ts", "frontend-admin/playwright.memory.config.ts"):
        candidates.add(repo / relative)
    files = {path.relative_to(repo).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
             for path in sorted(candidates) if path.is_file()}
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
    return {"schemaVersion": 1, "createdAt": datetime.now(timezone.utc).isoformat(),
            "candidateSha256": hashlib.sha256(encoded).hexdigest(), "files": files}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    value = freeze(Path(__file__).resolve().parents[2])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"path": str(args.output.resolve()), "files": len(value["files"]),
                      "candidateSha256": value["candidateSha256"]}))


if __name__ == "__main__":
    main()
