#!/usr/bin/env python3
"""校验 docs/ 下所有 Markdown 相对链接（文件目标 + 中文锚点）。

用法：
    python tools/docs/verify_md_links.py          # 校验 docs/（默认）
    python tools/docs/verify_md_links.py [dir...] # 指定目录/文件

规则：
- 相对路径目标必须真实存在（允许指向仓库内任意位置：docs/、workflows/、backend-java/…）
- 目录目标：目录存在即有效
- `#锚点`：同文件标题 slug 必须存在（GitHub 风格，支持中文）
- `路径#锚点`：目标文件存在且锚点存在
- 跳过：http(s)/mailto/data/相对协议、空锚点 `#`
- 反斜杠路径按 `/` 归一（宽容处理），仍指向必须存在的文件
- 次级检查：行内代码（反引号）中以仓库根目录开头的裸路径
  （`docs/`、`workflows/`、`backend-java/`、`deploy/`、`tools/`…）必须存在
  ——本仓库习惯用反引号写文件路径，此类引用是漂移重灾区

退出码：存在死链时为 1（CI 用）。
"""
from __future__ import annotations

import re
import sys
import urllib.parse
from pathlib import Path

from markdown_util import (collect_anchors, extract_links, fenced_regions,
                           in_any_region, read_lines)

ROOT = Path(__file__).resolve().parent.parent.parent  # tools/docs -> 仓库根

# 次级检查：反引号内裸路径的已知根目录前缀（相对仓库根）
BARE_PREFIXES = ("docs/", "workflows/", "backend-java/", "frontend-admin/",
                 "frontend-platform/", "frontend-shared/", "deploy/", "tools/",
                 ".github/", "Makefile")
BARE_RE = re.compile(r"`([^`]+)`")
# 反引号内非路径术语豁免：
# - JSON-RPC 方法名（tools/list）
# - 历史部署路径说明（deploy/kubernetes/）
# - 已随 Python 后端移除的脚本名（任务执行文档有意引用其历史名）
BARE_EXEMPT = {"tools/list", "deploy/kubernetes/",
               "deploy/ci/generate-task-summary.py",
               "deploy/ci/audit-task-status.py"}
# 省略缩写（含 ...）、glob（含 *）、带行号（:数字 结尾）的路径不校验
def _is_bare_exempt(p: str) -> bool:
    if p in BARE_EXEMPT:
        return True
    if "..." in p or "*" in p:
        return True
    if re.search(r":\d+$", p):
        return True
    return False


def resolve_target(base_dir: Path, target: str) -> Path:
    """把链接目标解析为仓库内路径（存在与否由调用方判断）。"""
    t = target.replace("\\", "/")
    if t.startswith("/"):
        t = t.lstrip("/")
        return (ROOT / t).resolve()
    return (base_dir / t).resolve()


def main() -> int:
    targets = [a for a in sys.argv[1:] if not a.startswith("-")] or ["docs"]
    errors: list[str] = []
    checked = 0
    # 单次命令复用同一目标的标题索引；下一次 main 调用重新读取文件。
    anchor_cache: dict[Path, set[str]] = {}

    for t in targets:
        path = Path(t).resolve()
        files = sorted(path.rglob("*.md")) if path.is_dir() else [path]
        for f in files:
            lines = read_lines(f)
            regions = fenced_regions(lines)
            anchors = anchor_cache.get(f)
            if anchors is None:
                anchors = collect_anchors(lines, regions)
                anchor_cache[f] = anchors
            base = f.parent
            for target, lineno in extract_links(lines, regions):
                target = target.strip()
                if not target or target == "#" or target.startswith(
                    ("http://", "https://", "mailto:", "tel:", "data:", "//")
                ):
                    continue
                checked += 1
                if "#" in target:
                    path_part, _, anchor_part = target.partition("#")
                else:
                    path_part, anchor_part = target, ""
                anchor_part = urllib.parse.unquote(anchor_part)

                if path_part:
                    resolved = resolve_target(base, path_part)
                    if not resolved.exists():
                        errors.append(f"{f.relative_to(ROOT)}:{lineno}  链接目标不存在: {target}")
                        continue
                    if anchor_part:
                        if resolved.is_file():
                            ranchors = anchor_cache.get(resolved)
                            if ranchors is None:
                                rlines = read_lines(resolved)
                                rregions = fenced_regions(rlines)
                                ranchors = collect_anchors(rlines, rregions)
                                anchor_cache[resolved] = ranchors
                            if anchor_part not in ranchors:
                                errors.append(
                                    f"{f.relative_to(ROOT)}:{lineno}  锚点不存在: {target} "
                                    f"(文件存在，标题 {anchor_part!r} 未找到)"
                                )
                        else:
                            errors.append(
                                f"{f.relative_to(ROOT)}:{lineno}  目录目标带锚点: {target}"
                            )
                else:  # 纯锚点，同文件
                    if anchor_part not in anchors:
                        errors.append(
                            f"{f.relative_to(ROOT)}:{lineno}  同文件锚点不存在: {target}"
                        )
            # 次级检查：反引号内仓库根路径必须存在
            for i, line in enumerate(lines):
                if in_any_region(i, regions):
                    continue
                for m in BARE_RE.finditer(line):
                    p = m.group(1).strip()
                    if not any(p.startswith(pre) for pre in BARE_PREFIXES):
                        continue
                    if _is_bare_exempt(p):
                        continue
                    q = p.replace("\\", "/")
                    if q.endswith("/"):
                        cand = ROOT / q
                    else:
                        cand = ROOT / q if (ROOT / q).exists() else ROOT / (q + ".md")
                    if not cand.exists():
                        errors.append(
                            f"{f.relative_to(ROOT)}:{i + 1}  反引号路径不存在: {p}"
                        )

    if errors:
        print(f"[FAIL] 链接校验失败：{len(errors)} 处死链（共检查 {checked} 条链接）")
        for e in errors:
            print("  " + e)
        return 1
    print(f"[OK] 链接校验通过：{checked} 条链接全部可解析")
    return 0


if __name__ == "__main__":
    sys.exit(main())
