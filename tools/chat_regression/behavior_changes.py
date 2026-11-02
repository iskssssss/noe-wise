"""只读分析代码变更，生成离线检查与真实评测建议；不执行评测或修改资源。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess

from behavior_policy import selection_metadata
from behavior_contract import SUITE_VERSION

PROMPTS = "backend-java/skillforge-prompts/"


def analyse(paths):
    paths = sorted(set(path.replace("\\", "/") for path in paths))
    checks, impacts, recommendations = set(), set(), []
    for path in paths:
        if path.endswith(".md") and not path.startswith(PROMPTS + "src/main/resources/"):
            continue
        if path.startswith(PROMPTS):
            checks.update(("behavior", "java-prompts"))
            if "/resources/" in path and path.endswith(".md"):
                name = Path(path).name
                if name in {"memory-extraction.md", "profile-merge.md"}:
                    checks.add("memory")
                    recommendations.append({"path": path, "suite": "memory", "scope": "extraction-retrieval-answer"})
                else:
                    impacts.add("prompt")
                    if "/delegation/" in path:
                        recommendations.append({"path": path, "suite": "product", "case_ids": ["D11"]})
            elif not path.endswith(".md"):
                impacts.add("prompt")
        elif path.startswith("tools/memory_evaluation/"):
            checks.add("memory")
        elif path.startswith("tools/skills/evaluation/") or path == "tools/skills/test_skill_evaluation.py":
            checks.add("skill")
        elif path.startswith(("tools/chat_regression/", "tools/runtime/conversation_eval", "tools/runtime/test_conversation_eval")):
            checks.add("behavior")
        elif path == ".github/workflows/agent-behavior-eval.yml":
            checks.update(("behavior", "memory", "skill"))
        elif path.endswith(".md"):
            continue
        else:
            # 无法确定消费者时只建议完整核心套件；不将未知变化当成无影响。
            checks.add("behavior")
            impacts.add("all")
    return {"schema_version": 1, "changed_paths": paths, "offline_checks": sorted(checks),
            "selection": selection_metadata(tuple(sorted(impacts)), SUITE_VERSION) if impacts else None,
            "recommendations": recommendations, "live_evidence_status": "NOT_RUN",
            "live_execution_automatic": False}


def markdown(result):
    cases = result["selection"]["case_ids"] if result["selection"] else []
    return "\n".join(["# 变更评测建议", "", "离线检查：" + ", ".join(result["offline_checks"]), "",
                      "建议核心场景：" + (", ".join(cases) or "无新增核心场景建议"), "",
                      *["- " + item["suite"] + ": " + item["path"] for item in result["recommendations"]], "",
                      "真实效果证据：NOT_RUN。此计划不发起模型请求；离线检查通过不证明行为改善。", ""])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    repo = Path(__file__).resolve().parents[2]
    refs = [subprocess.check_output(["git", "rev-parse", "--verify", "--end-of-options", ref + "^{commit}"], cwd=repo,
                                    encoding="utf-8").strip() for ref in (args.base, args.head)]
    raw = subprocess.check_output(["git", "diff", "--name-only", "-z", *refs, "--"], cwd=repo).decode("utf-8")
    result = analyse([p for p in raw.split("\0") if p])
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "plan.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    (args.output / "summary.md").write_text(markdown(result), encoding="utf-8")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as handle:
            for check in ("behavior", "memory", "skill"):
                handle.write(f"{check}={str(check in result['offline_checks']).lower()}\n")
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
