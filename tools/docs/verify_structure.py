#!/usr/bin/env python3
"""校验 docs/ 体系结构一致性（漂移检查）。

检查项：
1. 根 README 是否写死文档计数（应与实际一致；"以实际为准"表述放行）
2. docs/README 目录树标注的 ADR 范围（001-016）与实际文件一致
3. ADR 索引完整性：05-架构决策/ 每个 ADR 文件在 索引.md 有行，且反之亦然；
   编号连续性（允许已在索引中注明的缺口）
4. 单一事实源表（docs/README §3）中的链接全部可解析

用法：python tools/docs/verify_structure.py [docs_dir]  （默认 docs/）
退出码：存在不一致时为 1。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

from markdown_util import read_lines

ROOT = Path(__file__).resolve().parent.parent.parent
DOCS = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 and not sys.argv[1].startswith("-") \
    else ROOT / "docs"
README = DOCS / "README.md"
ROOT_README = ROOT / "README.md"
ADR_DIR = DOCS / "05-架构决策"
ADR_INDEX = ADR_DIR / "索引.md"


def main() -> int:
    errors: list[str] = []

    # ---- 1. 根 README 文档计数 ----
    if ROOT_README.exists():
        lines = read_lines(ROOT_README)
        for i, line in enumerate(lines):
            m = re.search(r"共\s*(\d+)\s*份", line)
            if m:
                errors.append(
                    f"README.md:{i + 1}  写死文档计数「共 {m.group(1)} 份」，"
                    "文档数随体系演进，应改为「以 docs/ 实际为准」"
                )

    # ---- 2. docs/README 目录树 ADR 范围标注 ----
    if README.exists():
        for i, line in enumerate(read_lines(README)):
            m = re.search(r"ADR[（(]?\s*(\d{3})-(\d{3})", line)
            if m:
                lo, hi = int(m.group(1)), int(m.group(2))
                nums = sorted({int(p.stem[:3]) for p in ADR_DIR.glob("*.md")
                               if p.stem != "索引" and p.stem[:3].isdigit()})
                if not nums:
                    continue
                if lo != nums[0] or hi != nums[-1]:
                    errors.append(
                        f"docs/README.md:{i + 1}  目录树 ADR 标注 {lo:03d}-{hi:03d} "
                        f"与实际 {nums[0]:03d}-{nums[-1]:03d} 不一致"
                    )

    # ---- 3. ADR 索引完整性 ----
    adr_files = sorted(p for p in ADR_DIR.glob("*.md") if p.stem != "索引")
    if ADR_INDEX.exists():
        idx_lines = read_lines(ADR_INDEX)
        idx_text = "\n".join(idx_lines)
        for p in adr_files:
            stem = p.stem
            if f"({stem}.md)" not in idx_text and f"]({stem}.md)" not in idx_text:
                errors.append(f"05-架构决策/{stem}.md 未在 索引.md 中登记")
        # 编号连续性：每个编号必须出现（允许索引中注明缺口的编号）
        nums = sorted({int(p.stem[:3]) for p in adr_files if p.stem[:3].isdigit()})
        for n in range(nums[0], nums[-1] + 1) if nums else ():
            if n not in nums:
                # 缺口须在索引中出现（缺 015 等），否则报
                if not any(f"ADR-{n:03d}" in line or f"ADR-{n:03d}" in l2
                           for line in idx_lines for l2 in [line]):
                    if not any(f"{n:03d}" in line for line in idx_lines):
                        errors.append(f"ADR 编号 {n:03d} 缺失且索引未注明")
        # 反向：索引行引用的文件必须存在
        for m in re.finditer(r"\]\((\d{3}-[^)]+\.md)\)", idx_text):
            if not (ADR_DIR / m.group(1)).exists():
                errors.append(f"索引.md 引用不存在的 ADR 文件: {m.group(1)}")

    # ---- 4. 单一事实源表链接可解析 ----
    if README.exists():
        in_table = False
        for i, line in enumerate(read_lines(README)):
            if line.startswith("## 3.") or "单一事实源" in line and line.startswith("##"):
                in_table = True
                continue
            if in_table and line.startswith("## ") and "单一事实源" not in line:
                in_table = False
            if not in_table:
                continue
            for m in re.finditer(r"\]\(([^)]+)\)", line):
                t = m.group(1).replace("\\", "/")
                if t.startswith("http"):
                    continue
                if "#" in t:
                    t = t.partition("#")[0]
                target = (README.parent / t).resolve() if t else README.parent
                if not target.exists():
                    errors.append(f"docs/README.md:{i + 1}  事实源表链接目标不存在: {m.group(1)}")

    if errors:
        print(f"[FAIL] 结构校验失败：{len(errors)} 处不一致")
        for e in errors:
            print("  " + e)
        return 1
    print("[OK] 结构校验通过：README 计数 / ADR 范围 / 索引 / 事实源表均一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
