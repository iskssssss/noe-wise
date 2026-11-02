#!/usr/bin/env python3
"""报告文档长度与历史参考值（budgets.json），不设置篇幅门禁。

口径：`\\S+` 非空白序列数（与 wc -w 近似；代码围栏计入）。
参考值选择：精确文件条目 > 目录档位（最长匹配）> default 兜底。
该口径不是汉字数或 Token 数，不能据此判断文档质量。
文档质量和信息完整性优先；不要求为匹配参考值删减内容或调整空格。

用法：python tools/docs/verify_budgets.py [docs_dir]
退出码：统计完成即为 0，超出参考值仅输出信息；读取或配置错误仍会失败。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
DOCS = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 and not sys.argv[1].startswith("-") \
    else ROOT / "docs"
BUDGETS = Path(__file__).resolve().parent / "budgets.json"


def word_count(path: Path) -> int:
    text = path.read_text(encoding="utf-8", errors="replace")
    return len(re.findall(r"\S+", text))


def main() -> int:
    budgets = json.loads(BUDGETS.read_text(encoding="utf-8"))
    files_budget = budgets.get("files", {})
    dirs_budget = budgets.get("dirs", {})
    default = budgets.get("default", 8000)

    def reference_for(rel: str) -> int:
        if rel in files_budget:
            return files_budget[rel]
        best = None
        for d, cap in dirs_budget.items():
            if rel.startswith(d) and (best is None or len(d) > len(best[0])):
                best = (d, cap)
        return best[1] if best else default

    over = []
    for f in sorted(DOCS.rglob("*.md")):
        rel = f.relative_to(ROOT).as_posix()
        if "/generated/" in rel:
            continue  # 生成式文件由所属生成器维护，不参与手写文档长度统计。
        w = word_count(f)
        cap = reference_for(rel)
        if w > cap:
            over.append((rel, w, cap))

    if over:
        print(f"[INFO] 文档长度统计：{len(over)} 份超过历史参考值（仅供参考，不阻断检查，不要求删减内容）")
        print("  计数 / 参考值  文档")
        for rel, w, cap in sorted(over, key=lambda x: -x[1]):
            print(f"  {w:>5} / {cap:<5}  {rel}")
    else:
        print("[INFO] 文档长度统计：无文件超过历史参考值（不作为质量判定或验收条件）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
