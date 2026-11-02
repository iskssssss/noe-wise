"""docs 校验共享解析工具（纯 stdlib）。

能力：
- 统一读行（CRLF 安全）
- 围栏代码块识别（``` 与 ~~~）
- 标题提取 + GitHub 风格锚点 slug（支持中文）
- 行内链接 / 引用式链接提取（跳过围栏与行内代码）
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

FENCE_RE = re.compile(r"^(`{3,}|~{3,})(.*)$")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
# 行内链接 [text](target) 或 [text](target "title")；target 不含空格与右括号
LINK_RE = re.compile(r"\[[^\[\]]*\]\(\s*([^)\s]+)(?:\s+[\"'][^)]*[\"'])?\s*\)")
# 引用式链接定义 [ref]: target
REFDEF_RE = re.compile(r"^\[[^\]]+\]:\s*(\S+)")
INLINE_CODE_RE = re.compile(r"`[^`]*`")


def read_lines(path: Path) -> list[str]:
    """读取 md 文件按行返回，剥离 CR（兼容 CRLF/CRLF 混合）。"""
    text = path.read_text(encoding="utf-8", errors="replace")
    return text.splitlines()


def fenced_regions(lines: list[str]) -> list[tuple[int, int]]:
    """返回围栏代码块的行区间（含围栏行本身）。未闭合围栏按到文件尾。"""
    regions: list[tuple[int, int]] = []
    start = -1
    char = ""
    length = 0
    for i, line in enumerate(lines):
        m = FENCE_RE.match(line)
        if not m:
            continue
        chars = m.group(1)
        if start < 0:
            start, char, length = i, chars[0], len(chars)
        elif chars[0] == char and len(chars) >= length:
            regions.append((start, i))
            start = -1
    if start >= 0:
        regions.append((start, len(lines) - 1))
    return regions


def in_any_region(idx: int, regions: list[tuple[int, int]]) -> bool:
    for start, end in regions:
        if start <= idx <= end:
            return True
    return False


def heading_slug(text: str) -> str:
    """GitHub 风格锚点 slug：小写、去标点（保留 CJK 字母数字与 _-）、空格转 -。

    CJK 由 str.isalnum() 命中（Unicode 字母数字），无需 \\p{L} 支持。
    """
    t = text.strip().lower()
    slug = "".join(c for c in t if c.isalnum() or c in " _-")
    return slug.replace(" ", "-")


@dataclass
class Heading:
    level: int
    text: str
    slug: str


def extract_headings(lines: list[str], regions: list[tuple[int, int]]) -> list[Heading]:
    """提取标题；重复标题 slug 追加 -1/-2（与 GitHub 渲染一致）。"""
    headings: list[Heading] = []
    seen: dict[str, int] = {}
    for i, line in enumerate(lines):
        if in_any_region(i, regions):
            continue
        m = HEADING_RE.match(line)
        if not m:
            continue
        base = heading_slug(m.group(2))
        if not base:
            continue
        if base in seen:
            seen[base] += 1
            slug = f"{base}-{seen[base]}"
        else:
            seen[base] = 0
            slug = base
        headings.append(Heading(level=len(m.group(1)), text=m.group(2), slug=slug))
    return headings


def collect_anchors(lines: list[str], regions: list[tuple[int, int]]) -> set[str]:
    return {h.slug for h in extract_headings(lines, regions)}


def extract_links(lines: list[str], regions: list[tuple[int, int]]) -> list[tuple[str, int]]:
    """返回 (目标, 1 基行号) 列表：行内链接 + 引用式链接定义，跳过围栏与行内代码。"""
    links: list[tuple[str, int]] = []
    for i, line in enumerate(lines):
        if in_any_region(i, regions):
            continue
        # 去掉行内代码片段，避免误匹配代码里的括号文本
        cleaned = INLINE_CODE_RE.sub("", line)
        for m in LINK_RE.finditer(cleaned):
            links.append((m.group(1), i + 1))
        m = REFDEF_RE.match(cleaned)
        if m:
            links.append((m.group(1), i + 1))
    return links
