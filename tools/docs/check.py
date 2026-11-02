#!/usr/bin/env python3
"""跨平台执行 NoeWise 的文档检查与非阻断长度统计。"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent.parent
CHECKS = (
    ("docs links", "tools/docs/verify_md_links.py", "docs"),
    ("harness links", "tools/docs/verify_md_links.py", "harness-design"),
    ("project and operations links", "tools/docs/verify_md_links.py", "README.md", "AGENTS.md",
     "CLAUDE.md", "CONTRIBUTING.md", "SECURITY.md", ".agents/skills", "deploy", "tools"),
    ("structure", "tools/docs/verify_structure.py"),
    ("docs length statistics", "tools/docs/verify_budgets.py", "docs"),
    ("harness length statistics", "tools/docs/verify_budgets.py", "harness-design"),
    ("agent rules length statistics", "tools/docs/verify_budgets.py", ".agents"),
    ("module graph freshness", "tools/docs/gen_module_graph.py", "--check"),
    ("docs mermaid", "tools/docs/verify_mermaid.py", "docs"),
    ("harness mermaid", "tools/docs/verify_mermaid.py", "harness-design"),
    ("agent rules mermaid", "tools/docs/verify_mermaid.py", ".agents"),
)


def main() -> int:
    """串行执行全部检查并汇总失败项，避免依赖 GNU Make。"""
    failures: list[str] = []
    for name, *arguments in CHECKS:
        print(f"\n== {name} ==", flush=True)
        completed = subprocess.run(
            [sys.executable, *arguments],
            cwd=ROOT,
            check=False,
        )
        if completed.returncode != 0:
            failures.append(f"{name} (exit {completed.returncode})")
    if failures:
        print("\n[FAIL] docs-check: " + ", ".join(failures))
        return 1
    print(f"\n[OK] docs-check: {len(CHECKS)} checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
