"""使用临时真实仓库验证创建、迁移核对和只读失败边界。"""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("worktree.py").resolve()
PROJECT = SCRIPT.parents[2]


class WorktreeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        default = tempfile.gettempdir()
        cls.test_root = Path(os.environ.get("SKILLFORGE_WORKTREE_TEST_ROOT", default)).resolve()
        cls.test_root.mkdir(parents=True, exist_ok=True)

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="worktree-test-", dir=self.test_root)
        self.root = Path(self.temporary.name).resolve()
        self.addCleanup(self.cleanup_fixture)
        self.repo = self.root / "source"
        self.repo.mkdir()
        self.target = self.root / "target"
        self.env = {
            **os.environ,
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_OPTIONAL_LOCKS": "0",
        }
        self.git(self.repo, "init", "-b", "main")
        self.git(self.repo, "config", "user.name", "Worktree Test")
        self.git(self.repo, "config", "user.email", "worktree-test@example.invalid")
        self.git(self.repo, "config", "commit.gpgSign", "false")
        self.git(self.repo, "config", "core.autocrlf", "true")
        (self.repo / ".gitattributes").write_bytes((PROJECT / ".gitattributes").read_bytes())
        (self.repo / "text.txt").write_bytes(b"original\n")
        (self.repo / "run.cmd").write_bytes(b"@echo off\n")
        (self.repo / "image.bin").write_bytes(b"\x00\x01\r\n\xff")
        self.git(self.repo, "add", ".gitattributes", "text.txt", "run.cmd", "image.bin")
        self.git(self.repo, "commit", "-m", "fixture")
        self.head = self.git(self.repo, "rev-parse", "HEAD").decode().strip()

    def cleanup_fixture(self) -> None:
        # 临时库中也含 Git 目录；递归清理前确认最终路径仍在明确的测试根目录内。
        if self.root == self.test_root or not self.root.resolve().is_relative_to(self.test_root):
            raise ValueError("测试清理路径越界")
        self.temporary.cleanup()

    def git(self, repo: Path, *args: str, input_bytes: bytes | None = None) -> bytes:
        return subprocess.run(
            ["git", "-C", str(repo), *args],
            env=self.env,
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=True,
        ).stdout

    def command(self, *args: str, succeeds: bool = True) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            [sys.executable, "-X", "utf8", "-B", str(SCRIPT), *args],
            env=self.env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            encoding="utf-8",
        )
        self.assertEqual(result.returncode, 0 if succeeds else 1, result.stdout + result.stderr)
        return result

    def create(self, *extra: str) -> None:
        self.command("create", "--repo", str(self.repo), "--path", str(self.target), "--branch", "codex/test", "--ref", self.head, *extra)

    def test_create_checks_out_selected_commit_with_lf_text_and_crlf_batch(self) -> None:
        self.create()
        self.assertTrue((self.target / ".git").is_file())
        self.assertEqual(self.git(self.target, "rev-parse", "HEAD").decode().strip(), self.head)
        self.assertEqual((self.target / "text.txt").read_bytes(), b"original\n")
        self.assertEqual((self.target / "run.cmd").read_bytes(), b"@echo off\r\n")
        self.assertEqual((self.target / "image.bin").read_bytes(), b"\x00\x01\r\n\xff")
        self.command("check", "--repo", str(self.target), "--expected-head", self.head, "--clean")

    def test_dirty_source_requires_explicit_committed_only(self) -> None:
        (self.repo / "text.txt").write_bytes(b"staged\n")
        self.git(self.repo, "add", "text.txt")
        (self.repo / "text.txt").write_bytes(b"working\n")
        (self.repo / "new.txt").write_bytes(b"untracked\n")
        index_before = (self.repo / ".git" / "index").read_bytes()
        result = self.command("create", "--repo", str(self.repo), "--path", str(self.target), "--branch", "codex/test", "--ref", self.head, succeeds=False)
        self.assertIn("--committed-only", result.stderr)
        self.assertFalse(self.target.exists())
        self.assertEqual((self.repo / ".git" / "index").read_bytes(), index_before)
        self.create("--committed-only")
        self.assertEqual((self.target / "text.txt").read_bytes(), b"original\n")
        self.assertFalse((self.target / "new.txt").exists())
        self.assertEqual((self.repo / "text.txt").read_bytes(), b"working\n")
        self.assertEqual((self.repo / ".git" / "index").read_bytes(), index_before)

    def test_index_lock_is_reported_and_preserved(self) -> None:
        lock = self.repo / ".git" / "index.lock"
        lock.write_bytes(b"")
        index_before = (self.repo / ".git" / "index").read_bytes()
        result = self.command("check", "--repo", str(self.repo), succeeds=False)
        self.assertIn("索引锁", result.stderr)
        self.assertTrue(lock.exists())
        self.assertEqual((self.repo / ".git" / "index").read_bytes(), index_before)

    def test_empty_diff_status_is_rejected_without_refreshing_index(self) -> None:
        (self.repo / "text.txt").write_bytes(b"original\r\n")
        self.assertEqual(self.git(self.repo, "diff", "--name-only"), b"")
        index_before = (self.repo / ".git" / "index").read_bytes()
        result = self.command("check", "--repo", str(self.repo), succeeds=False)
        self.assertIn("实际差异为空", result.stderr)
        self.assertEqual((self.repo / ".git" / "index").read_bytes(), index_before)

    def test_migration_checks_staged_working_and_untracked_bytes(self) -> None:
        self.create()
        (self.repo / "text.txt").write_bytes(b"staged\n")
        self.git(self.repo, "add", "text.txt")
        staged_patch = self.git(self.repo, "diff", "--cached", "--binary", "--full-index")
        (self.repo / "text.txt").write_bytes(b"working\n")
        (self.repo / "new.txt").write_bytes(b"new\x00\r\n")
        (self.target / "text.txt").write_bytes((self.repo / "text.txt").read_bytes())
        (self.target / "new.txt").write_bytes((self.repo / "new.txt").read_bytes())
        result = self.command("check", "--repo", str(self.target), "--source", str(self.repo), succeeds=False)
        self.assertIn("暂存内容", result.stderr)
        self.git(self.target, "apply", "--cached", "-", input_bytes=staged_patch)
        self.command("check", "--repo", str(self.target), "--source", str(self.repo))
        (self.target / "new.txt").write_bytes(b"new\x00\n")
        result = self.command("check", "--repo", str(self.target), "--source", str(self.repo), succeeds=False)
        self.assertIn("文件字节", result.stderr)

    def test_expected_commit_mismatch_is_rejected(self) -> None:
        (self.repo / "text.txt").write_bytes(b"next\n")
        self.git(self.repo, "add", "text.txt")
        self.git(self.repo, "commit", "-m", "next fixture")
        result = self.command("check", "--repo", str(self.repo), "--expected-head", self.head, succeeds=False)
        self.assertIn("指定提交不一致", result.stderr)

    def test_invalid_ref_creates_neither_directory_nor_branch(self) -> None:
        self.command("create", "--repo", str(self.repo), "--path", str(self.target), "--branch", "codex/test", "--ref", "missing-ref", succeeds=False)
        self.assertFalse(self.target.exists())
        self.assertEqual(self.git(self.repo, "branch", "--list", "codex/test"), b"")



if __name__ == "__main__":
    unittest.main()
