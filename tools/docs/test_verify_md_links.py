"""使用临时 Markdown 文件验证链接校验的实际结果。"""

import contextlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify_md_links


class VerifyMarkdownLinksTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.source = self.root / "source.md"

    def check_links(self, markdown):
        self.source.write_text(markdown, encoding="utf-8")
        output = io.StringIO()
        with patch.object(verify_md_links, "ROOT", self.root), patch.object(
            verify_md_links.sys, "argv", ["verify_md_links.py", str(self.source)]
        ), contextlib.redirect_stdout(output):
            result = verify_md_links.main()
        return result, output.getvalue()

    def test_existing_same_file_anchor(self):
        result, output = self.check_links(
            "# 1. 概述与边界\n[本节](#1-概述与边界)\n"
            "[编码锚点](#1-%E6%A6%82%E8%BF%B0%E4%B8%8E%E8%BE%B9%E7%95%8C)\n"
        )
        self.assertEqual(result, 0, output)
        self.assertIn("2 条链接", output)

    def test_missing_same_file_anchor(self):
        result, output = self.check_links("# Existing\n[缺失](#missing)\n")
        self.assertEqual(result, 1, output)
        self.assertIn("source.md:2  同文件锚点不存在: #missing", output)

    def test_repeated_headings_use_one_based_suffixes(self):
        result, output = self.check_links(
            "# Section\n# Section\n# Section\n"
            "[首节](#section)\n[次节](#section-1)\n[末节](#section-2)\n"
        )
        self.assertEqual(result, 0, output)
        result, output = self.check_links("# Section\n# Section\n[错误](#section-0)\n")
        self.assertEqual(result, 1, output)

    def test_existing_cross_file_anchor(self):
        (self.root / "target.md").write_text("# Target Section\n", encoding="utf-8")
        result, output = self.check_links("[目标](target.md#target-section)\n")
        self.assertEqual(result, 0, output)
        self.assertIn("1 条链接", output)

    def test_missing_cross_file_anchor(self):
        (self.root / "target.md").write_text("# Existing\n", encoding="utf-8")
        result, output = self.check_links("[缺失](target.md#missing)\n")
        self.assertEqual(result, 1, output)
        self.assertIn("source.md:1  锚点不存在: target.md#missing", output)

    def test_repeated_target_is_read_once_and_each_error_keeps_its_line(self):
        target = self.root / "target.md"
        target.write_text("# Section\n# Section\n", encoding="utf-8")
        with patch.object(verify_md_links, "read_lines", wraps=verify_md_links.read_lines) as reads:
            result, output = self.check_links(
                "[首节](target.md#section)\n[次节](./target.md#section-1)\n"
                "[缺失](target.md#missing)\n[缺失](./target.md#missing)\n")
        self.assertEqual(result, 1, output)
        self.assertEqual(sum(call.args[0] == target for call in reads.call_args_list), 1)
        self.assertIn("source.md:3  锚点不存在", output)
        self.assertIn("source.md:4  锚点不存在", output)

    def test_target_cache_does_not_survive_a_command(self):
        target = self.root / "target.md"
        target.write_text("# Before\n", encoding="utf-8")
        result, output = self.check_links("[目标](target.md#before)\n")
        self.assertEqual(result, 0, output)
        target.write_text("# After\n", encoding="utf-8")
        result, output = self.check_links("[目标](target.md#before)\n")
        self.assertEqual(result, 1, output)

    def test_external_urls_are_skipped(self):
        targets = (
            "http://example.invalid/page#missing",
            "https://example.invalid/page#missing",
            "//example.invalid/page#missing",
            "mailto:reader@example.invalid",
            "tel:+123456789",
            "data:text/plain,example",
        )
        result, output = self.check_links(
            "\n".join(f"[外部]({target})" for target in targets)
        )
        self.assertEqual(result, 0, output)
        self.assertIn("0 条链接", output)

    def test_empty_anchor_is_skipped(self):
        result, output = self.check_links("[顶部](#)\n")
        self.assertEqual(result, 0, output)
        self.assertIn("0 条链接", output)


if __name__ == "__main__":
    unittest.main()
