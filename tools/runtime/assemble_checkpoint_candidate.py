#!/usr/bin/env python3
"""从运行基线组装 checkpoint 候选，仅替换明确来源的类与资源；不构建、部署或修改数据库。"""

from __future__ import annotations

import argparse
import copy
import hashlib
import io
import json
import re
import struct
import subprocess
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "agent-runtime-session-spi": {
        "com/skillforge/runtime/session/spi/SessionCheckpointReference.java",
        "com/skillforge/runtime/session/spi/SessionPersistenceContracts.java",
        "com/skillforge/runtime/session/spi/SessionPreparations.java",
    },
    "agent-runtime-session": {
        "com/skillforge/runtime/session/SessionCheckpointCodec.java",
        "com/skillforge/runtime/session/SessionCommitter.java",
    },
    "agent-runtime-adapter-jdbc": {
        "com/skillforge/runtime/adapter/jdbc/session/JdbcSessionSnapshots.java",
        "com/skillforge/runtime/adapter/jdbc/session/JdbcSessionPersistence.java",
        "com/skillforge/runtime/adapter/jdbc/session/SessionPersistenceRows.java",
        "com/skillforge/runtime/adapter/jdbc/session/SessionPersistenceCodec.java",
        "com/skillforge/runtime/adapter/jdbc/session/SessionRunEventCodec.java",
        "com/skillforge/runtime/adapter/jdbc/session/JdbcSessionPreparations.java",
        "com/skillforge/runtime/adapter/jdbc/session/JdbcSessionPreparationViews.java",
        "com/skillforge/runtime/adapter/jdbc/session/JdbcSessionSourceFacts.java",
        "com/skillforge/runtime/adapter/jdbc/session/JdbcSessionCancellationFacts.java",
        "com/skillforge/runtime/adapter/jdbc/session/JdbcSessionTerminalFacts.java",
        "com/skillforge/runtime/adapter/jdbc/session/JdbcWorkflowWakeups.java",
        "com/skillforge/runtime/adapter/jdbc/action/JdbcActionLedgerPort.java",
    },
    "agent-runtime-spring-boot-starter": {
        "com/skillforge/runtime/starter/AgentRuntimeAutoConfiguration.java",
        "com/skillforge/runtime/starter/AgentRuntimeSessionPreparations.java",
    },
    "skillforge-migration": set(),
}
RESOURCES = {
    "agent-runtime-adapter-jdbc": {
        "mapper/AgentRuntimeSessionPersistenceMapper.xml",
        "mapper/AgentRuntimeSessionPreparationsMapper.xml",
        "mapper/AgentRuntimeSessionSnapshotsMapper.xml",
    },
    "skillforge-migration": {
        "db/migration/V118__agent_runtime_session_preparations.sql",
        "db/migration/V262__runtime_immutable_checkpoint_storage.sql",
    },
}
MIGRATION_VERSION = re.compile(r"(?:^|/)V(\d+)(?:__|_)")
SIGNATURE = re.compile(r"^META-INF/[^/]+\.(?:SF|RSA|DSA|EC)$", re.IGNORECASE)


def is_signature(name: str, content: bytes) -> bool:
    # Spring Boot 的空 BOOT.SF 只是归档 marker；真实签名文件继续拒绝，原条目原样保留。
    return bool(SIGNATURE.match(name)) and not (name == "META-INF/BOOT.SF" and content == b"")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class ClassReader:
    def __init__(self, data: bytes):
        self.data = data
        self.offset = 0

    def take(self, length: int) -> bytes:
        end = self.offset + length
        if end > len(self.data):
            raise ValueError("truncated class file")
        value = self.data[self.offset:end]
        self.offset = end
        return value

    def number(self, size: int) -> int:
        return int.from_bytes(self.take(size), "big")


def class_origin(data: bytes) -> tuple[str, str | None]:
    """读取封闭 class-file 结构，只解释内部类名与 SourceFile 属性。"""
    reader = ClassReader(data)
    if reader.take(4) != b"\xca\xfe\xba\xbe":
        raise ValueError("invalid class magic")
    reader.take(4)
    count = reader.number(2)
    pool: list[object] = [None] * count
    index = 1
    while index < count:
        tag = reader.number(1)
        if tag == 1:
            # 类与来源名称使用 ASCII；其余 modified-UTF8 常量不需要解码。
            pool[index] = reader.take(reader.number(2))
        elif tag == 7:
            pool[index] = reader.number(2)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            reader.take(4)
        elif tag in (5, 6):
            reader.take(8)
            index += 1
        elif tag in (8, 16, 19, 20):
            reader.take(2)
        elif tag == 15:
            reader.take(3)
        else:
            raise ValueError(f"unsupported class constant-pool tag: {tag}")
        index += 1

    def text(constant: int) -> str:
        value = pool[constant]
        if not isinstance(value, bytes):
            raise ValueError("invalid class UTF8 reference")
        return value.decode("utf-8")

    reader.take(2)
    this_class = reader.number(2)
    name_index = pool[this_class]
    if not isinstance(name_index, int):
        raise ValueError("invalid class name reference")
    internal_name = text(name_index)
    reader.take(2)
    reader.take(reader.number(2) * 2)

    def attributes() -> list[tuple[int, bytes]]:
        values = []
        for _ in range(reader.number(2)):
            attribute_name = reader.number(2)
            values.append((attribute_name, reader.take(reader.number(4))))
        return values

    for _ in range(2):
        for _ in range(reader.number(2)):
            reader.take(6)
            attributes()
    source = None
    for attribute_name, value in attributes():
        if text(attribute_name) == "SourceFile":
            if source is not None or len(value) != 2:
                raise ValueError("invalid SourceFile attribute")
            source = text(int.from_bytes(value, "big"))
    if reader.offset != len(data):
        raise ValueError("trailing class file bytes")
    return internal_name, source


def source_path(data: bytes) -> str | None:
    internal_name, source = class_origin(data)
    if source is None:
        return None
    return str(PurePosixPath(internal_name).parent / source)


def unique_entries(archive: zipfile.ZipFile) -> list[zipfile.ZipInfo]:
    entries = archive.infolist()
    if len({entry.filename for entry in entries}) != len(entries):
        raise ValueError("duplicate ZIP entry names")
    return entries


def rewrite_module(original: bytes, sources: set[str], replacements: dict[str, bytes]) -> tuple[bytes, list[str]]:
    output = io.BytesIO()
    removed = []
    with zipfile.ZipFile(io.BytesIO(original)) as archive, zipfile.ZipFile(output, "w") as candidate:
        candidate.comment = archive.comment
        for entry in unique_entries(archive):
            content = archive.read(entry)
            if is_signature(entry.filename, content):
                raise ValueError("cannot patch a signed module JAR")
            selected = entry.filename.endswith(".class") and source_path(content) in sources
            if selected:
                removed.append(entry.filename)
            if selected or entry.filename in replacements:
                continue
            candidate.writestr(copy.copy(entry), content)
        for name, content in sorted(replacements.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            candidate.writestr(info, content)
    return output.getvalue(), sorted(removed)


def load_replacements(repo: Path) -> tuple[dict[str, dict[str, bytes]], dict[str, dict]]:
    replacements = {}
    evidence = {}
    for module, allowed in SOURCES.items():
        directory = repo / "backend-java" / module
        content: dict[str, bytes] = {}
        origins: dict[str, list[str]] = {source: [] for source in sorted(allowed)}
        for path in sorted((directory / "build/classes/java/main").rglob("*.class")):
            data = path.read_bytes()
            internal_name, source = class_origin(data)
            origin = str(PurePosixPath(internal_name).parent / source) if source is not None else None
            if origin not in allowed:
                continue
            entry = path.relative_to(directory / "build/classes/java/main").as_posix()
            if entry != internal_name + ".class":
                raise ValueError(f"compiled class path mismatch: {path}")
            content[entry] = data
            origins[origin].append(entry)
        for source, classes in origins.items():
            if not classes:
                raise ValueError(f"no compiled classes for whitelisted source: {module}/{source}")
        for resource in sorted(RESOURCES.get(module, set())):
            data = (directory / "build/resources/main" / resource).read_bytes()
            source_data = (directory / "src/main/resources" / resource).read_bytes()
            if data != source_data:
                raise ValueError(f"compiled resource differs from source: {module}/{resource}")
            content[resource] = data
        replacements[module] = content
        evidence[module] = {
            "sources": [{
                "path": (directory / "src/main/java" / source).relative_to(repo).as_posix(),
                "sha256": file_sha(directory / "src/main/java" / source),
                "classes": classes,
            } for source, classes in origins.items()],
            "replacements": [{"entry": name, "sha256": sha(data)} for name, data in sorted(content.items())],
        }
    return replacements, evidence


def check_migration_base(data: bytes) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for entry in unique_entries(archive):
            match = MIGRATION_VERSION.search(entry.filename)
            if match and int(match.group(1)) >= 263:
                raise ValueError(f"base contains unrelated newer migration: {entry.filename}")
        if "db/migration/V118__agent_runtime_session_preparations.sql" not in archive.namelist():
            raise ValueError("base migration JAR lacks V118")


def assemble(base: Path, output: Path, replacements: dict[str, dict[str, bytes]], evidence: dict[str, dict]) -> dict:
    base_hash = file_sha(base)
    modules = []
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists() or output.resolve() == base.resolve():
        raise ValueError(f"output already exists or equals base: {output}")
    # 基线读取完成前只创建临时文件，组装失败不留下可误用的候选 JAR。
    with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".checkpoint-", suffix=".tmp", delete=False) as temporary:
        temporary_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(base) as archive:
            entries = unique_entries(archive)
            matches = {}
            for module in SOURCES:
                # session 的名字也是 session-spi 的前缀，必须同时识别版本起始位。
                pattern = re.compile(rf"^BOOT-INF/lib/{re.escape(module)}-\d[^/]*\.jar$")
                found = [entry.filename for entry in entries if pattern.fullmatch(entry.filename)]
                if len(found) != 1:
                    raise ValueError(f"expected exactly one nested {module} JAR, got {len(found)}")
                matches[found[0]] = module
            prefix_length = min(entry.header_offset for entry in entries)
            with base.open("rb") as stream:
                prefix = stream.read(prefix_length)
            with temporary_path.open("wb") as stream:
                stream.write(prefix)
                with zipfile.ZipFile(stream, "w") as candidate:
                    candidate.comment = archive.comment
                    for entry in entries:
                        content = archive.read(entry)
                        if is_signature(entry.filename, content):
                            raise ValueError("cannot patch a signed fat JAR")
                        if entry.filename in matches:
                            module = matches[entry.filename]
                            if module == "skillforge-migration":
                                check_migration_base(content)
                            updated, removed = rewrite_module(content, SOURCES[module], replacements[module])
                            modules.append({
                                "module": module, "entry": entry.filename,
                                "base_sha256": sha(content), "candidate_sha256": sha(updated),
                                "removed_source_classes": removed, **evidence[module],
                            })
                            content = updated
                        metadata = copy.copy(entry)
                        if entry.filename.startswith("BOOT-INF/lib/") and entry.filename.endswith(".jar"):
                            metadata.compress_type = zipfile.ZIP_STORED
                        candidate.writestr(metadata, content)
        with zipfile.ZipFile(temporary_path) as candidate:
            if candidate.testzip() is not None:
                raise ValueError("candidate ZIP CRC check failed")
            for entry in candidate.infolist():
                if entry.filename.startswith("BOOT-INF/lib/") and entry.filename.endswith(".jar") and entry.compress_type != zipfile.ZIP_STORED:
                    raise ValueError(f"nested JAR is not STORED: {entry.filename}")
        if file_sha(base) != base_hash:
            raise ValueError("base JAR changed during assembly")
        temporary_path.replace(output)
    finally:
        temporary_path.unlink(missing_ok=True)
    return {
        "assembled_at_utc": datetime.now(timezone.utc).isoformat(),
        "base_jar": str(base), "base_sha256": base_hash,
        "candidate_jar": str(output), "candidate_sha256": file_sha(output),
        "preserved_launcher_prefix_bytes": prefix_length,
        "scope": "checkpoint storage classes, three JDBC mappers, V118 and V262 only",
        "modules": sorted(modules, key=lambda module: module["module"]),
    }


def synthetic_class(name: str, source: str) -> bytes:
    def utf8(value: str) -> bytes:
        text = value.encode("utf-8")
        return b"\x01" + struct.pack(">H", len(text)) + text
    constants = utf8(name) + b"\x07\x00\x01" + utf8("SourceFile") + utf8(source)
    return b"\xca\xfe\xba\xbe\x00\x00\x00\x41\x00\x05" + constants + struct.pack(">7H", 0, 2, 0, 0, 0, 0, 1) + struct.pack(">HIH", 3, 2, 4)


def self_check() -> None:
    assert not is_signature("META-INF/BOOT.SF", b"")
    assert is_signature("META-INF/BOOT.SF", b"Signature-Version: 1.0\n")
    assert is_signature("META-INF/OTHER.SF", b"")
    assert is_signature("META-INF/BOOT.RSA", b"")
    assert is_signature("META-INF/BOOT.DSA", b"")
    assert is_signature("META-INF/BOOT.EC", b"")
    primary = synthetic_class("example/Owner", "Owner.java")
    nested = synthetic_class("example/Owner$Nested", "Owner.java")
    package_private = synthetic_class("example/Hidden", "Owner.java")
    other = synthetic_class("example/Other", "Other.java")
    assert class_origin(primary) == ("example/Owner", "Owner.java")
    assert source_path(nested) == source_path(package_private) == "example/Owner.java"
    try:
        class_origin(primary[:-1])
    except ValueError:
        pass
    else:
        raise AssertionError("truncated class accepted")
    original = io.BytesIO()
    with zipfile.ZipFile(original, "w") as archive:
        for name, content in {"example/Owner.class": primary, "example/Owner$Old.class": nested, "example/Hidden.class": package_private, "example/Other.class": other, "unchanged.txt": b"unchanged", "META-INF/BOOT.SF": b""}.items():
            archive.writestr(name, content)
    updated, removed = rewrite_module(original.getvalue(), {"example/Owner.java"}, {"example/Owner.class": primary, "example/Owner$Nested.class": nested, "example/Hidden.class": package_private, "mapper/new.xml": b"new"})
    assert removed == ["example/Hidden.class", "example/Owner$Old.class", "example/Owner.class"]
    with zipfile.ZipFile(io.BytesIO(updated)) as archive:
        assert archive.read("example/Other.class") == other
        assert archive.read("unchanged.txt") == b"unchanged"
        assert archive.read("META-INF/BOOT.SF") == b""
        assert archive.read("example/Hidden.class") == package_private
        assert "example/Owner$Old.class" not in archive.namelist()
        assert archive.testzip() is None
    print("SourceFile, nested/package-private selection, unrelated entry preservation and ZIP self-check passed")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-jar", action="append", type=Path, help="明确的当前运行 fat JAR；institution/platform 分别传入，可重复")
    parser.add_argument("--output-dir", type=Path, help="仓库内 Git 忽略的执行目录；默认 .agent-work-state/checkpoint-candidate/<UTC>")
    parser.add_argument("--self-check", action="store_true", help="仅运行 class/ZIP 最小自检，无构建、部署或数据库操作")
    args = parser.parse_args()
    if args.self_check:
        if args.base_jar or args.output_dir:
            parser.error("--self-check cannot be combined with assembly arguments")
        self_check()
        return 0
    if not args.base_jar:
        parser.error("at least one explicit --base-jar is required")
    output_dir = (args.output_dir or ROOT / ".agent-work-state/checkpoint-candidate" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")).resolve()
    try:
        relative = output_dir.relative_to(ROOT).as_posix()
    except ValueError:
        parser.error("output directory must be inside this repository")
    ignored = subprocess.run(["git", "-c", f"safe.directory={ROOT.as_posix()}", "check-ignore", "--quiet", "--", relative + "/receipt.json"], cwd=ROOT, check=False)
    if ignored.returncode != 0:
        parser.error("output directory must be ignored by Git")
    bases = [path.resolve(strict=True) for path in args.base_jar]
    if len(set(bases)) != len(bases) or len({path.stem for path in bases}) != len(bases):
        parser.error("base JAR paths and names must be distinct")
    replacements, evidence = load_replacements(ROOT)
    receipts = []
    for base in bases:
        output = output_dir / f"{base.stem}-checkpoint-candidate.jar"
        receipt = assemble(base, output, replacements, evidence)
        receipt_path = output.with_suffix(".receipt.json")
        receipt_path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        receipts.append({"candidate": str(output), "receipt": str(receipt_path), "sha256": receipt["candidate_sha256"]})
    print(json.dumps({"candidates": receipts}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
