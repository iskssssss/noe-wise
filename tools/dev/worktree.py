#!/usr/bin/env python3
"""创建工作树并只读核对迁移结果，不复制 Git 元数据或自动清理锁。"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import subprocess
import sys


@dataclass(frozen=True)
class FileChange:
    path: str
    status: str
    sha256: str | None


@dataclass(frozen=True)
class WorkspaceState:
    root: Path
    head: str
    index_entries: bytes
    index_flags: bytes
    changes: tuple[FileChange, ...]


def git(repo: Path, *args: str) -> bytes:
    # 状态检查不得隐式刷新真实索引；显式 worktree add 仍由 Git 正常写入。
    return subprocess.run(
        ["git", "-c", "core.quotepath=false", "-C", str(repo), *args],
        env={**os.environ, "GIT_OPTIONAL_LOCKS": "0"},
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout


def resolve_commit(repo: Path, ref: str) -> str:
    return git(repo, "rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}").decode().strip()



def inspect(repo: Path) -> WorkspaceState:
    root = Path(git(repo, "rev-parse", "--show-toplevel").decode("utf-8").strip()).resolve()
    lock = Path(git(root, "rev-parse", "--git-path", "index.lock").decode("utf-8").strip())
    if not lock.is_absolute():
        lock = root / lock
    if lock.exists():
        raise ValueError(f"发现 Git 索引锁，请先核实活动操作，不会自动删除：{lock}")

    rows = git(root, "status", "--porcelain=v1", "-z", "--no-renames", "--untracked-files=all")
    actual = set(git(root, "diff", "--name-only", "-z", "--no-ext-diff", "--no-renames").split(b"\0"))
    changes = []
    unexplained = []
    for row in rows.split(b"\0"):
        if not row:
            continue
        status, name = row[:2].decode("ascii"), row[3:].decode("utf-8")
        if status[1] == "M" and row[3:] not in actual:
            unexplained.append(name)
        path = root / name
        if path.is_symlink():
            contents = os.readlink(path).encode("utf-8")
        elif path.is_file():
            contents = path.read_bytes()
        elif not path.exists():
            contents = None
        else:
            raise ValueError(f"变更不是普通文件或符号链接，请单独核对：{name}")
        changes.append(FileChange(name, status, hashlib.sha256(contents).hexdigest() if contents is not None else None))
    if unexplained:
        sample = "、".join(unexplained[:5])
        raise ValueError(f"存在 {len(unexplained)} 个显示修改但实际差异为空的文件：{sample}")

    return WorkspaceState(
        root,
        resolve_commit(root, "HEAD"),
        git(root, "ls-files", "--stage", "-z"),
        git(root, "ls-files", "-v", "-z"),
        tuple(changes),
    )


def check(repo: Path, expected_head: str | None, source: Path | None, clean: bool) -> WorkspaceState:
    state = inspect(repo)
    if expected_head is not None and state.head != resolve_commit(state.root, expected_head):
        raise ValueError("当前基础提交与指定提交不一致")
    if clean and state.changes:
        raise ValueError(f"应为空白工作树，但发现 {len(state.changes)} 个变更文件")
    if source is not None:
        original = inspect(source)
        if state.head != original.head:
            raise ValueError("迁移目标与来源的基础提交不一致")
        if (state.index_entries, state.index_flags) != (original.index_entries, original.index_flags):
            raise ValueError("迁移目标与来源的暂存内容或索引标记不一致")
        if {c.path: c for c in state.changes} != {c.path: c for c in original.changes}:
            raise ValueError("迁移目标与来源的变更清单、状态或文件字节不一致")
    return state


def create(args: argparse.Namespace) -> WorkspaceState:
    destination = args.path
    if not destination.is_absolute():
        raise ValueError("新工作树必须使用绝对路径")
    destination = destination.resolve()
    original = inspect(args.repo)
    if original.changes and not args.committed_only:
        raise ValueError("来源有未提交修改；如只需指定提交，请显式添加 --committed-only；需迁移修改时按开发流程另行迁入")
    if destination.exists():
        raise ValueError(f"目标目录已存在，不会覆盖：{destination}")
    commit = resolve_commit(original.root, args.ref)
    git(original.root, "check-ref-format", "--branch", args.branch)
    destination.parent.mkdir(parents=True, exist_ok=True)
    git(original.root, "worktree", "add", "-b", args.branch, "--", str(destination), commit)
    # 验收失败保留现场，不自动删除已创建的分支或目录。
    print(f"已创建：{destination}")
    return check(destination, commit, None, True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    new = commands.add_parser("create", help="从指定提交创建独立工作树")
    new.add_argument("--repo", type=Path, required=True, help="已有 Git 仓库")
    new.add_argument("--path", type=Path, required=True, help="不存在的新工作树绝对路径")
    new.add_argument("--branch", required=True, help="新分支名，默认约定使用 codex/ 前缀")
    new.add_argument("--ref", required=True, help="明确指定基础提交或引用")
    new.add_argument("--committed-only", action="store_true", help="明确只取指定提交，不迁入来源的未提交修改")
    verify = commands.add_parser("check", help="只读检查工作树，可与已暂停修改的迁移来源逐项比较")
    verify.add_argument("--repo", type=Path, default=Path.cwd())
    verify.add_argument("--expected-head", help="要求使用的基础提交或引用")
    verify.add_argument("--source", type=Path, help="迁移来源，比较基础提交、暂存内容及变更文件原始字节")
    verify.add_argument("--clean", action="store_true", help="要求工作树没有任何变更")
    args = parser.parse_args()
    try:
        state = create(args) if args.command == "create" else check(args.repo, args.expected_head, args.source, args.clean)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        detail = error.stderr.decode("utf-8", errors="replace").strip() if isinstance(error, subprocess.CalledProcessError) else str(error)
        print(f"检查未通过：{detail}", file=sys.stderr)
        return 1
    print(f"检查通过：{state.root}，基础提交 {state.head}，变更文件 {len(state.changes)} 个")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
