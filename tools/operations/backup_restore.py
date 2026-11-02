"""SkillForge 显式源备份和隔离恢复工具，不读取项目 .env 或自动选择当前业务库。

认证仅经操作者指定的 libpq passfile；错误输出不回显连接串/凭据。
logical-restore 仅创建全新的 skillforge_restore_ 前缀数据库，不支持覆盖或 --clean。
physical-stage 只生成离线隔离恢复目录，绝不启动数据库或连接原主库。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
import zipfile


class RecoveryError(RuntimeError):
    """安全预检或外部工具失败，消息不包含认证材料。"""


def digest(path: Path) -> str:
    """流式计算文件摘要，避免把文件正文载入日志或内存。"""
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def contained(path: Path, root: Path) -> bool:
    """以已解析绝对路径判断包含关系，拒绝软链接逃逸。"""
    return path == root or root in path.parents


def new_directory(value: str) -> Path:
    """目标必须显式、绝对且不存在；失败不删除或覆盖已有内容。"""
    requested = Path(value)
    if not requested.is_absolute():
        raise RecoveryError("目标目录必须为绝对路径")
    target = requested.resolve()
    if target.exists():
        raise RecoveryError("目标目录已存在，拒绝覆盖")
    return target


def environment(passfile: str, sslmode: str) -> dict[str, str]:
    """只给 PostgreSQL 子进程传必要环境，不继承业务秘密或PGPASSWORD。"""
    supplied = Path(passfile)
    path = supplied.resolve(strict=True)
    if not path.is_file() or supplied.is_symlink():
        raise RecoveryError("passfile必须为明确的普通文件")
    if os.name != "nt" and path.stat().st_mode & 0o077:
        raise RecoveryError("passfile权限必须限制为当前用户")
    result = {key: os.environ[key] for key in ("PATH", "SystemRoot", "WINDIR", "TEMP", "TMP") if key in os.environ}
    result.update(PGPASSFILE=str(path), PGCONNECT_TIMEOUT="10", PGSSLMODE=sslmode)
    return result


def run(arguments: list[str], env: dict[str, str], timeout: int = 3600) -> str:
    """使用参数数组执行工具，失败只返回工具名和退出码，不输出stderr中的秘密。"""
    try:
        process = subprocess.run(arguments, env=env, capture_output=True, text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RecoveryError(f"{Path(arguments[0]).name}未能完成") from error
    if process.returncode != 0:
        raise RecoveryError(f"{Path(arguments[0]).name}失败，退出码{process.returncode}")
    return process.stdout


def connection(args: argparse.Namespace, target: bool = False) -> list[str]:
    """连接位置来自显式参数，数据库标识经过白名单，不拼接Shell或连接串。"""
    prefix = "target_" if target else "source_"
    host, port, user = (getattr(args, prefix + field) for field in ("host", "port", "user"))
    if not host or any(character in host for character in "\r\n\x00") or not 1 <= port <= 65535:
        raise RecoveryError("主机或端口无效")
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]{0,62}", user):
        raise RecoveryError("数据库用户格式无效")
    return ["--host", host, "--port", str(port), "--username", user, "--no-password"]


def database_name(value: str) -> str:
    """只接受不会成为SQL或CLI选项的数据库名。"""
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,62}", value):
        raise RecoveryError("数据库名格式无效")
    return value


def snapshot_files(args: argparse.Namespace, output: Path) -> dict:
    """只归档显式冻结文件根；配置仅登记摘要，不复制含凭据的配置正文。"""
    manifest: dict = {"files": [], "configs": []}
    forbidden = re.compile(r"(^\.env($|\.)|pass(word|file)?|credential|secret|token|\.pem$|\.key$)", re.I)
    with zipfile.ZipFile(output / "files.zip", "x", zipfile.ZIP_DEFLATED) as archive:
        for index, value in enumerate(args.snapshot_root):
            supplied = Path(value)
            root = supplied.resolve(strict=True)
            if not root.is_dir() or contained(output, root) or supplied.is_symlink():
                raise RecoveryError("冻结文件根必须为独立普通目录")
            for candidate in sorted(root.rglob("*")):
                resolved = candidate.resolve(strict=True)
                if candidate.is_symlink() or not contained(resolved, root):
                    raise RecoveryError("冻结文件清单含链接或越界路径")
                if not candidate.is_file():
                    continue
                if forbidden.search(candidate.name):
                    raise RecoveryError("冻结文件包含凭据名称，须先准备无秘密的冻结目录")
                relative = f"root-{index}/{candidate.relative_to(root).as_posix()}"
                before = digest(candidate)
                archive.write(candidate, relative)
                if digest(candidate) != before:
                    raise RecoveryError("备份期间文件发生变化，必须冻结写入后重试")
                manifest["files"].append({"path": relative, "bytes": candidate.stat().st_size, "sha256": before})
        for value in args.config:
            path = Path(value).resolve(strict=True)
            if not path.is_file():
                raise RecoveryError("配置清单只能包含具体文件")
            manifest["configs"].append({"name": path.name, "sha256": digest(path), "bytes": path.stat().st_size,
                                        "content_included": False})
    return manifest


def write_manifest(output: Path, data: dict) -> None:
    """最后写入完整清单；没有清单的目录不视为成功备份。"""
    data.update(format="skillforge-recovery-v1", created_at=datetime.now(timezone.utc).isoformat())
    (output / "manifest.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def backup(args: argparse.Namespace, physical: bool = False) -> Path:
    """备份显式来源并验证真实工具结果；不对业务库迁移、停服或删除。"""
    source = database_name(args.source_database)
    if args.source_confirmation != source:
        raise RecoveryError("source-confirmation必须精确匹配来源数据库名")
    output = new_directory(args.output)
    env = environment(args.passfile, args.sslmode)
    output.mkdir(parents=True)
    base = connection(args)
    if physical:
        # -X stream 包含恢复一致基线所需WAL；长期PITR额外需要连续归档，清单明确记录边界。
        run([str(Path(args.bin_dir) / "pg_basebackup"), *base, "--pgdata", str(output / "base"),
             "--format=plain", "--wal-method=stream", "--checkpoint=fast", "--manifest-checksums=SHA256"], env)
        run([str(Path(args.bin_dir) / "pg_verifybackup"), str(output / "base")], env)
        wal_files = []
        wal_target = output / "wal"
        wal_target.mkdir()
        if args.wal_archive:
            root = Path(args.wal_archive).resolve(strict=True)
            if not root.is_dir() or contained(output, root):
                raise RecoveryError("WAL归档根无效")
            for path in sorted(root.iterdir()):
                if path.is_symlink():
                    raise RecoveryError("WAL归档不允许链接")
                if path.is_file() and re.fullmatch(r"[0-9A-F]{24}(\.backup|\.partial)?|[0-9A-F]{8}\.history", path.name):
                    before = digest(path)
                    shutil.copy2(path, wal_target / path.name)
                    if digest(path) != before or digest(wal_target / path.name) != before:
                        raise RecoveryError("WAL归档复制过程中变化")
                    wal_files.append({"name": path.name, "sha256": before})
        materials = snapshot_files(args, output)
        write_manifest(output, {"kind": "physical", "source": {"host": args.source_host, "port": args.source_port,
                        "database": source}, "wal": wal_files, "pitr_requires_continuous_archive": True,
                        "base_manifest_sha256": digest(output / "base" / "backup_manifest"), **materials})
    else:
        path = output / "database.dump"
        run([str(Path(args.bin_dir) / "pg_dump"), *base, "--dbname", source, "--format=custom", "--file", str(path)], env)
        listing = run([str(Path(args.bin_dir) / "pg_restore"), "--list", str(path)], env)
        if not path.is_file() or path.stat().st_size == 0 or not listing.strip():
            raise RecoveryError("备份为空或恢复清单为空")
        (output / "database.list").write_text(listing, encoding="utf-8")
        materials = snapshot_files(args, output)
        write_manifest(output, {"kind": "logical", "source": {"host": args.source_host, "port": args.source_port,
                        "database": source}, "database_sha256": digest(path), **materials})
    return output


def read_bundle(value: str, kind: str) -> tuple[Path, dict]:
    """恢复只接受已完成并有匹配版本的真实备份包。"""
    root = Path(value).resolve(strict=True)
    data = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    if data.get("format") != "skillforge-recovery-v1" or data.get("kind") != kind:
        raise RecoveryError("备份包格式或种类不匹配")
    return root, data


def restore_files(root: Path, manifest: dict, target: Path) -> None:
    """只向全新隔离根解压登记的具体文件，拒绝ZIP穿越、未登记成员和摘要不匹配。"""
    entries = {item["path"]: item for item in manifest["files"]}
    with zipfile.ZipFile(root / "files.zip") as archive:
        if set(archive.namelist()) != set(entries):
            raise RecoveryError("文件包与清单不匹配")
        for member in archive.infolist():
            if member.is_dir() or member.external_attr >> 16 & 0o170000 == 0o120000:
                raise RecoveryError("恢复文件不是普通文件")
            destination = (target / member.filename).resolve()
            if not contained(destination, target) or Path(member.filename).is_absolute() or ".." in Path(member.filename).parts:
                raise RecoveryError("恢复文件路径越界")
            item = entries[member.filename]
            if member.file_size != item["bytes"]:
                raise RecoveryError("恢复文件大小不匹配")
            destination.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(member) as source, destination.open("xb") as output:
                shutil.copyfileobj(source, output)
            if digest(destination) != item["sha256"]:
                raise RecoveryError("恢复文件摘要不匹配")


def restore(args: argparse.Namespace) -> str:
    """创建全新隔离库后恢复，既有目标和来源数据库始终拒绝。"""
    root, manifest = read_bundle(args.bundle, "logical")
    target = database_name(args.target_database)
    if not target.startswith("skillforge_restore_") or args.target_confirmation != target:
        raise RecoveryError("隔离数据库必须以skillforge_restore_开头，且confirmation精确匹配")
    source = manifest["source"]
    if target == source["database"]:
        raise RecoveryError("隔离目标不得与来源数据库同名")
    file_target = new_directory(args.target_files)
    if contained(file_target, root) or contained(root, file_target):
        raise RecoveryError("恢复文件目标与备份包必须独立")
    dump = root / "database.dump"
    if digest(dump) != manifest["database_sha256"]:
        raise RecoveryError("数据库备份摘要不匹配")
    env = environment(args.passfile, args.sslmode)
    base = connection(args, target=True)
    exists = run([str(Path(args.bin_dir) / "psql"), *base, "--dbname", "postgres", "--tuples-only", "--no-align",
                  "--set", "ON_ERROR_STOP=1", "--command", f"SELECT 1 FROM pg_database WHERE datname='{target}'"], env)
    if exists.strip():
        raise RecoveryError("目标数据库已存在，拒绝覆盖")
    # createdb自行处理并发存在竞争；不执行DROP、--clean或对当前数据库修改。
    run([str(Path(args.bin_dir) / "createdb"), *base, "--maintenance-db", "postgres", target], env)
    run([str(Path(args.bin_dir) / "pg_restore"), *base, "--dbname", target, "--exit-on-error", "--single-transaction",
         "--no-owner", "--no-privileges", str(dump)], env)
    file_target.mkdir(parents=True)
    restore_files(root, manifest, file_target)
    identity = run([str(Path(args.bin_dir) / "psql"), *base, "--dbname", target, "--tuples-only", "--no-align",
                    "--set", "ON_ERROR_STOP=1", "--command", "SELECT current_database()"], env).strip()
    if identity != target:
        raise RecoveryError("恢复后数据库身份验证失败")
    (file_target / "restore-receipt.json").write_text(json.dumps({"database": target, "files_verified": len(manifest["files"]),
                "source_manifest_sha256": digest(root / "manifest.json"), "external_execution_enabled": False}), encoding="utf-8")
    return target


def stage_physical(args: argparse.Namespace) -> Path:
    """准备明确目录内的离线PITR恢复；恢复到时间目标，禁止自动启动或回连业务主库。"""
    root, manifest = read_bundle(args.bundle, "physical")
    target = new_directory(args.target_data)
    if args.target_confirmation != str(target) or not target.name.startswith("skillforge_restore_"):
        raise RecoveryError("物理隔离目录名称须以skillforge_restore_开头，confirmation须匹配其绝对路径")
    if contained(target, root) or contained(root, target) or "deploy/data" in target.as_posix().lower():
        raise RecoveryError("物理恢复目标不能是业务数据路径或备份路径")
    try:
        moment = datetime.fromisoformat(args.recovery_target_time.replace("Z", "+00:00"))
    except ValueError as error:
        raise RecoveryError("PITR目标时间必须为ISO8601") from error
    if moment.tzinfo is None or not 1024 <= args.target_port <= 65535:
        raise RecoveryError("PITR目标必须带时区且使用独立非特权端口")
    if digest(root / "base" / "backup_manifest") != manifest["base_manifest_sha256"]:
        raise RecoveryError("物理基线清单摘要不匹配")
    if any(path.is_symlink() for path in (root / "base").rglob("*")):
        raise RecoveryError("物理基线含外部链接，须采用明确tablespace映射备份")
    for wal in manifest["wal"]:
        if digest(root / "wal" / wal["name"]) != wal["sha256"]:
            raise RecoveryError("WAL摘要不匹配")
    env = {key: os.environ[key] for key in ("PATH", "SystemRoot", "TEMP", "TMP") if key in os.environ}
    run([str(Path(args.bin_dir) / "pg_verifybackup"), str(root / "base")], env)
    shutil.copytree(root / "base", target, symlinks=False)
    # 复制基线后清除主库连接指令，仅使用包内的离线WAL；PostgreSQL配置中的单引号必须倍增。
    auto = target / "postgresql.auto.conf"
    existing = auto.read_text(encoding="utf-8") if auto.exists() else ""
    lines = [line for line in existing.splitlines() if not re.match(r"\s*(primary_conninfo|primary_slot_name|restore_command|recovery_target_.*|archive_command|archive_library|listen_addresses|port)\s*=", line)]
    arguments = [sys.executable, str(Path(__file__).resolve()), "wal-fetch", "--archive", str(root / "wal"), "--name", "%f", "--destination", "%p"]
    if os.name == "nt":
        command = subprocess.list2cmdline(arguments)
    else:
        import shlex
        command = shlex.join(arguments)
    escaped = command.replace("'", "''")
    lines.extend(["listen_addresses='127.0.0.1'", f"port={args.target_port}", "archive_mode='off'", "primary_conninfo=''",
                  f"restore_command='{escaped}'", f"recovery_target_time='{moment.isoformat()}'", "recovery_target_action='pause'"])
    auto.write_text("\n".join(lines) + "\n", encoding="utf-8")
    # 不继承生产include、archive_command或共享预加载库；恢复实例只接受本机读验证。
    (target / "postgresql.conf").write_text("hot_standby=on\n", encoding="utf-8")
    (target / "pg_hba.conf").write_text("local all all reject\nhost all all 127.0.0.1/32 reject\n", encoding="utf-8")
    (target / "recovery.signal").touch()
    standby = target / "standby.signal"
    if standby.exists():
        standby.unlink()
    return target


def wal_fetch(args: argparse.Namespace) -> Path:
    """PostgreSQL恢复回调只获取严格命名的包内WAL；缺失时失败，绝不从主库拉取。"""
    if not re.fullmatch(r"[0-9A-F]{24}|[0-9A-F]{8}\.history", args.name):
        raise RecoveryError("WAL请求名称无效")
    archive = Path(args.archive).resolve(strict=True)
    source = archive / args.name
    if source.is_symlink() or not source.is_file() or not contained(source.resolve(), archive):
        raise RecoveryError("WAL文件缺失或越界")
    destination = Path(args.destination).resolve()
    shutil.copyfile(source, destination)
    return destination


def append_wal(args: argparse.Namespace) -> Path:
    """向物理包追加连续归档片段；同名异摘要拒绝，不覆盖已有恢复证据。"""
    root, manifest = read_bundle(args.bundle, "physical")
    archive = Path(args.wal_archive).resolve(strict=True)
    if not archive.is_dir() or contained(root, archive):
        raise RecoveryError("WAL归档根无效")
    known = {item["name"]: item for item in manifest["wal"]}
    for source in sorted(archive.iterdir()):
        if source.is_symlink(): raise RecoveryError("WAL归档不允许链接")
        if not source.is_file() or not re.fullmatch(r"[0-9A-F]{24}|[0-9A-F]{8}\.history", source.name): continue
        value = digest(source)
        if source.name in known:
            if known[source.name]["sha256"] != value: raise RecoveryError("同名WAL摘要发生变化")
            continue
        destination = root / "wal" / source.name
        if destination.exists(): raise RecoveryError("WAL目标已有未登记文件")
        shutil.copy2(source, destination)
        if digest(source) != value or digest(destination) != value: raise RecoveryError("WAL复制过程中变化")
        known[source.name] = {"name": source.name, "sha256": value}
    manifest["wal"] = sorted(known.values(), key=lambda item: item["name"])
    # 原清单仅在全部追加片段验证后原子替换，失败不会宣称完整。
    temporary = root / "manifest.json.new"
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(root / "manifest.json")
    return root


def parser() -> argparse.ArgumentParser:
    """每个操作都要求显式来源/目标，默认不执行任何外部动作。"""
    cli = argparse.ArgumentParser(description=__doc__)
    commands = cli.add_subparsers(dest="command", required=True)
    for name in ("logical-backup", "physical-backup"):
        item = commands.add_parser(name)
        item.add_argument("--source-host", required=True); item.add_argument("--source-port", type=int, required=True)
        item.add_argument("--source-user", required=True); item.add_argument("--source-database", required=True)
        item.add_argument("--source-confirmation", required=True); item.add_argument("--output", required=True)
        item.add_argument("--passfile", required=True); item.add_argument("--sslmode", choices=("require", "verify-full", "disable"), default="require")
        item.add_argument("--bin-dir", required=True); item.add_argument("--snapshot-root", action="append", default=[])
        item.add_argument("--config", action="append", default=[])
        if name == "physical-backup": item.add_argument("--wal-archive")
    item = commands.add_parser("logical-restore")
    item.add_argument("--bundle", required=True); item.add_argument("--target-host", required=True)
    item.add_argument("--target-port", type=int, required=True); item.add_argument("--target-user", required=True)
    item.add_argument("--target-database", required=True); item.add_argument("--target-confirmation", required=True)
    item.add_argument("--target-files", required=True); item.add_argument("--passfile", required=True)
    item.add_argument("--bin-dir", required=True); item.add_argument("--sslmode", choices=("require", "verify-full", "disable"), default="require")
    item = commands.add_parser("physical-stage")
    item.add_argument("--bundle", required=True); item.add_argument("--target-data", required=True)
    item.add_argument("--target-confirmation", required=True); item.add_argument("--target-port", type=int, required=True)
    item.add_argument("--recovery-target-time", required=True); item.add_argument("--bin-dir", required=True)
    item = commands.add_parser("wal-fetch")
    item.add_argument("--archive", required=True); item.add_argument("--name", required=True); item.add_argument("--destination", required=True)
    item = commands.add_parser("wal-append")
    item.add_argument("--bundle", required=True); item.add_argument("--wal-archive", required=True)
    return cli


def main() -> int:
    """只输出安全结果路径或脱敏失败，不打印子进程认证材料。"""
    args = parser().parse_args()
    try:
        if args.command == "logical-backup": result = backup(args)
        elif args.command == "physical-backup": result = backup(args, physical=True)
        elif args.command == "logical-restore": result = restore(args)
        elif args.command == "physical-stage": result = stage_physical(args)
        elif args.command == "wal-append": result = append_wal(args)
        else: result = wal_fetch(args)
        print(json.dumps({"status": "complete", "result": str(result)}, ensure_ascii=False))
        return 0
    except (RecoveryError, OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        message = str(error) if isinstance(error, RecoveryError) else "备份材料或文件操作失败"
        print(json.dumps({"status": "failed", "reason": message}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
