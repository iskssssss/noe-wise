#!/usr/bin/env python3
"""mermaid 围栏基本健康检查（仅告警，不设硬门禁）。

检查项：
- 围栏是否闭合（开而不闭）
- 围栏内括号/引号配对粗查（不完整渲染的常见原因）

用法：python tools/docs/verify_mermaid.py [docs_dir]
退出码：恒为 0（发现问题时打印告警但不失败，避免存量图阻塞）。
"""
from __future__ import annotations

import sys
from pathlib import Path

from markdown_util import read_lines

ROOT = Path(__file__).resolve().parent.parent.parent
DOCS = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 and not sys.argv[1].startswith("-") \
    else ROOT / "docs"


def main() -> int:
    warnings: list[str] = []
    for f in sorted(DOCS.rglob("*.md")):
        lines = read_lines(f)
        fences: list[int] = []
        for i, line in enumerate(lines):
            if line.lstrip().startswith(("```", "~~~")):
                fences.append(i)
        # 配对
        for j in range(0, len(fences) - 1, 2):
            start, end = fences[j], fences[j + 1]
            if not lines[start].lstrip().lower().startswith("```mermaid") \
               and not lines[start].lstrip().lower().startswith("~~~mermaid"):
                continue
            body = "\n".join(lines[start + 1:end])
            if lines[start].startswith("```") and not lines[end].startswith("```"):
                warnings.append(f"{f.relative_to(ROOT)}:{start + 1}  mermaid 围栏未闭合")
                continue
            for ch_open, ch_close in (("(", ")"), ("[", "]"), ("{", "}")):
                if body.count(ch_open) != body.count(ch_close):
                    warnings.append(
                        f"{f.relative_to(ROOT)}:{start + 1}  mermaid 括号 {ch_open}{ch_close} 数量不配对 "
                        f"({body.count(ch_open)} vs {body.count(ch_close)})"
                    )
                    break
        # 奇数个围栏 → 最后一条未闭合
        if len(fences) % 2 == 1:
            warnings.append(f"{f.relative_to(ROOT)}:{fences[-1] + 1}  存在未闭合的代码围栏")

    if warnings:
        print(f"[WARN] mermaid 检查：{len(warnings)} 条告警（不阻塞，请修复）：")
        for w in warnings:
            print("  " + w)
    else:
        print("[OK] mermaid 检查：所有围栏闭合且括号配对")
    return 0


if __name__ == "__main__":
    sys.exit(main())
