#!/usr/bin/env python3
"""维护当前数据库表名映射；仅处理源码文件，从不连接数据库。"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tokenize
import io
import tempfile
import time


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("table_names.json")
MIGRATIONS = "backend-java/skillforge-migration/src/main/resources/db/migration"
SDK_SCHEMA = "backend-java/agent-runtime-adapter-jdbc/src/main/resources/com/skillforge/runtime/adapter/jdbc/schema/migration"
SQLITE_SCHEMA = "backend-java/agent-runtime-adapter-sqlite/src/main/resources"
METADATA = {"flyway_schema_history", "agent_runtime_schema_history"}
# 固定旧库及其备份/清理材料保留当时名称，不能当作当前 schema 的输入。
HISTORICAL_FILES = {
    "tools/runtime/runtime_history_reset_tables.json",
    "tools/runtime/clear_runtime_history.sql",
    "tools/runtime/prepare_checkpoint_storage_after_runtime_reset.sql",
    "tools/runtime/RuntimeCheckpointMigration.java",
    "tools/runtime/runtime_business_archive.py",
    "tools/runtime/runtime_conversion_backup.py",
    "tools/runtime/restore_runtime_reset_copy.py",
    "tools/runtime/assemble_checkpoint_candidate.py",
}
INDEPENDENT_SCHEMAS = {"tools/integration-scenario/group_sender.py": "SenderStore 本地供应商模拟库以自身 DDL 为权威，非平台 PostgreSQL 或 Runtime SQLite，碰巧同名不代表同一表"}
SCOPED_OBJECT_REFERENCES: list[dict] = []
TABLE_DDL = re.compile(
    r"\b(CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?|"
    r"DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?|"
    r"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?)"
    r"(?:public\.)?([a-z_][a-z_0-9]*)(?![.a-z_0-9])"
    r"(?:\s+RENAME\s+TO\s+([a-z_][a-z_0-9]*))?", re.I)
TOKEN = re.compile(
    r"--[^\r\n]*|/\*[\s\S]*?\*/|\$(?:[a-zA-Z_][a-zA-Z_0-9]*)?\$|"
    r"'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|[a-zA-Z_][a-zA-Z_0-9]*|[^\s]", re.M)
JAVA_LITERAL = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"')
JAVA_TOKENS = re.compile(JAVA_LITERAL.pattern + r"|//[^\r\n]*|/\*[\s\S]*?\*/|[a-zA-Z_][a-zA-Z_0-9]*|[(),{}\[\]]")
SQL_START = re.compile(
    r"\b(?:SELECT\s|INSERT\s+INTO|UPDATE\s|DELETE\s+FROM|CREATE\s+(?:TABLE|INDEX|UNIQUE|TRIGGER|POLICY|FUNCTION|SEQUENCE)|"
    r"ALTER\s+(?:TABLE|INDEX|SEQUENCE)|DROP\s+(?:TABLE|INDEX|TRIGGER|POLICY|FUNCTION|SEQUENCE)|TRUNCATE\s|COMMENT\s+ON|"
    r"REFERENCES\s|FROM\s+(?:public\.)?[a-z_]|JOIN\s+(?:public\.)?[a-z_]|"
    r"LOCK\s+TABLE|GRANT\s|REVOKE\s|pragma_table_info\s*\(|(?:table_name|relname)\s*(?:=|LIKE|IN\b))", re.I)


OBJECT_DDL = re.compile(
    r"\b(?:CREATE\s+(?:OR\s+REPLACE\s+)?(?:UNIQUE\s+|CONSTRAINT\s+)?"
    r"(INDEX|TRIGGER|POLICY|FUNCTION|SEQUENCE)|(?:ADD\s+|DROP\s+)?(CONSTRAINT)|"
    r"COMMENT\s+ON\s+(INDEX|TRIGGER|POLICY|FUNCTION|SEQUENCE|CONSTRAINT))"
    r"\s+(?:IF\s+(?:NOT\s+)?EXISTS\s+)?(?:public\.)?([a-z_][a-z_0-9]*)(?![.a-z_0-9])", re.I)
OBJECT_ALTER = re.compile(r"\bALTER\s+(INDEX|SEQUENCE|FUNCTION|POLICY|TRIGGER)\s+(?:IF\s+EXISTS\s+)?(?:public\.)?([a-z_][a-z_0-9]*)(?:\s+RENAME\s+TO\s+([a-z_][a-z_0-9]*))?", re.I)


def target_name(name: str, owner: str) -> str:
    if name.startswith("nw_") or name in METADATA:
        return name
    if name.startswith("skillforge_"):
        return "nw_" + name[len("skillforge_"):]
    if name.startswith("agent_runtime_"):
        return name if owner == "runtime" else "nw_runtime_" + name[len("agent_runtime_"):]
    if name.startswith("runtime_budget_"):
        return "agent_" + name
    if name.startswith("desktop_"):
        return "agent_runtime_" + name
    if name in {"plans", "invoices"} or name.startswith("invoice_"):
        return "nw_billing_" + name
    if name == "sessions" or name.startswith("session_"):
        return "nw_chat_" + name
    return "nw_" + name


def bounded_identifier(name: str) -> str:
    """显式对象名按原始完整目标取摘要，保留确定性并消除截断碰撞。"""
    return name if len(name.encode("utf-8")) <= 63 else name[:52] + "_" + hashlib.sha256(name.encode("utf-8")).hexdigest()[:10]


def object_target(name: str, table_mapping: dict[str, str]) -> str:
    """对象的所属表前缀只替换一次；后缀中的字段名和其他语义保持原值。"""
    prefix = re.match(r"^(?:pk|fk|idx|ck|uq|trg|chk|check|unique|guard|protect|validate|reject|assert|record|ensure)_", name)
    start = prefix.end() if prefix else 0
    for source in sorted(table_mapping, key=len, reverse=True):
        if name[start:] == source or name[start:].startswith(source + "_"):
            return bounded_identifier(name[:start] + table_mapping[source] + name[start + len(source):])
    return bounded_identifier(name)


def java_table_arguments(text: str) -> dict[int, str]:
    """仅标记具名 table/tableName 参数位置；同调用的其他业务字符串不是表名。"""
    helpers = {}
    for declaration in re.finditer(r"\b([a-zA-Z_][a-zA-Z_0-9]*)\s*\(([^()]*)\)\s*(?:throws\s+[\w., ]+)?\s*\{", text):
        positions = {}
        depth = 0
        parts = []
        start = 0
        parameters = declaration.group(2)
        for index, char in enumerate(parameters):
            if char == "<": depth += 1
            elif char == ">": depth -= 1
            elif char == "," and depth == 0:
                parts.append(parameters[start:index]); start = index + 1
        parts.append(parameters[start:])
        for index, parameter in enumerate(parts):
            match = re.search(r"\bString\s+(table|tableName|constraint|constraintName|indexName|triggerName)\s*$", parameter)
            if match:
                positions[index] = "table" if match.group(1) in {"table", "tableName"} else "object"
        if positions:
            helpers[declaration.group(1)] = positions
    stack = []
    previous = ""
    result = {}
    for token in JAVA_TOKENS.finditer(text):
        value = token.group()
        if value.startswith(("//", "/*")):
            continue
        if value.startswith('"'):
            if stack and stack[-1][0] in helpers:
                kind = helpers[stack[-1][0]].get(stack[-1][1])
                if kind:
                    result[token.start()] = kind
            previous = "<literal>"
        elif value in {"(", "{", "["}:
            stack.append([previous if value == "(" else "", 0]); previous = value
        elif value in {")", "}", "]"}:
            if stack: stack.pop()
            previous = value
        elif value == ",":
            if stack: stack[-1][1] += 1
            previous = value
        else:
            previous = value
    return result


def inventory() -> dict:
    entries: dict[str, dict] = {}
    live: set[str] = set()
    migrations = sorted((ROOT / MIGRATIONS).glob("V*.sql"), key=lambda p: int(p.name.split("__")[0][1:]))
    sources = migrations + sorted((ROOT / SDK_SCHEMA).glob("*.sql")) + sorted((ROOT / SQLITE_SCHEMA).rglob("*Mapper.xml"))
    sdk_owned = set()
    for path in (ROOT / SDK_SCHEMA).glob("*.sql"):
        sdk_owned.update(re.findall(r"^CREATE TABLE public\.([a-z_0-9]+)", path.read_text(encoding="utf-8-sig"), re.M))
    sqlite_owned = set()
    for path in (ROOT / SQLITE_SCHEMA).rglob("*SchemaMapper.xml"):
        sqlite_owned.update(re.findall(r"CREATE TABLE IF NOT EXISTS ([a-z_0-9]+)", path.read_text(encoding="utf-8-sig")))
    sealed_sql = (ROOT / MIGRATIONS / "V294__seal_legacy_runtime_execution.sql").read_text(encoding="utf-8-sig")
    sealed = set(re.findall(r"'(agent_runtime_[a-z_0-9]+)'", sealed_sql[:sealed_sql.index("] LOOP")]))
    production = {path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8-sig")
                  for path in (ROOT / "backend-java").glob("*/src/main/**/*") if path.suffix in {".xml", ".java"}}
    for path in sources:
        relative = path.relative_to(ROOT).as_posix()
        text = path.read_text(encoding="utf-8-sig")
        for match in TABLE_DDL.finditer(text):
            operation, name, renamed = match.groups()
            if name in METADATA:
                continue
            names = [name] + ([renamed] if renamed else [])
            for item in names:
                if item.endswith("_policy_v2"):
                    continue
                owner = "runtime" if item in sdk_owned or item in sqlite_owned else "platform"
                entry = entries.setdefault(item, {
                    "source": item, "target": target_name(item, owner), "owner": owner,
                    "engines": [], "state": "current", "physical_state": "present", "definitions": [],
                    "reference_candidates": [], "confirmed_owner_sources": [],
                    "reason": "独立 SDK 自有结构或 SQLite 适配结构" if owner == "runtime" else "平台所属业务结构，不能按旧 agent_runtime 前缀误判为 SDK",
                })
                engine = "sqlite" if SQLITE_SCHEMA in relative else "postgresql"
                if engine not in entry["engines"]:
                    entry["engines"].append(engine)
                if operation.upper().startswith("CREATE") or item == renamed:
                    if relative not in entry["definitions"]:
                        entry["definitions"].append(relative)
            if relative.startswith(MIGRATIONS):
                if operation.upper().startswith("CREATE"):
                    live.add(name)
                elif operation.upper().startswith("DROP"):
                    live.discard(name)
                elif renamed:
                    live.discard(name)
                    live.add(renamed)
    for name, entry in entries.items():
        present = name in live or name in sdk_owned or name in sqlite_owned
        entry["physical_state"] = "present" if present else "removed"
        if not present or name in sealed:
            entry["state"] = "historical"
        if name in sealed:
            entry["reason"] += "；V294 保留事实且封写，不代表已 DROP"
        entry["engines"].sort()
    references = re.compile(r"(?<![a-z_0-9])(?:" + "|".join(re.escape(name) for name in sorted(entries, key=len, reverse=True)) + r")(?![a-z_0-9])")
    for path, text in production.items():
        for name in {match.group() for match in references.finditer(text)}:
            entries[name]["reference_candidates"].append(path)
            if re.search(r"\b(?:FROM|JOIN|INTO|UPDATE|TABLE|REFERENCES)\s+(?:public\.)?" + re.escape(name) + r"\b", text, re.I) or '@TableName("' + name + '"' in text:
                entries[name]["confirmed_owner_sources"].append(path)
    # 未改名的完整表名也参与最长匹配，防止把 agent_runtime_sessions 中的 sessions 当作平台表。
    table_mapping = {row["source"]: row["target"] for row in entries.values()}
    object_kinds: dict[str, set[str]] = {}
    for path in sources:
        source = path.read_text(encoding="utf-8-sig")
        for match in OBJECT_DDL.finditer(source):
            kind, constraint, comment_kind, name = match.groups()
            object_kinds.setdefault(name, set()).add((kind or constraint or comment_kind).lower())
        for match in OBJECT_ALTER.finditer(source):
            kind, name, renamed = match.groups()
            object_kinds.setdefault(name, set()).add(kind.lower())
            if renamed:
                object_kinds.setdefault(renamed, set()).add(kind.lower())
    objects = []
    for name, kinds in sorted(object_kinds.items()):
        target = object_target(name, table_mapping)
        if target != name:
            objects.append({"source": name, "target": target, "kinds": sorted(kinds)})
    return {
        "format": 1,
        "scope": "目标完整迁移链，仅供新建或另行授权重建环境；不升级既有数据库",
        "reference_candidates_note": "原始文本命中的候选可能仅为变量名或 JSON；confirmed_owner_sources 才是明确 SQL 或 @TableName 的归属证据",
        "metadata_exceptions": sorted(METADATA),
        "historical_artifacts": sorted(HISTORICAL_FILES),
        "object_rule": "只替换附属对象所属表前缀一次，保留字段名后缀；超过 63 字节用前 52 字符加下划线和 SHA-256 前 10 位确定性缩短；不复制共享触发器函数",
        "objects": objects,
        "tables": sorted(entries.values(), key=lambda row: row["source"]),
    }


def load_mapping() -> tuple[dict, dict[str, str], set[str]]:
    global SCOPED_OBJECT_REFERENCES
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    SCOPED_OBJECT_REFERENCES = data.get("implicit_object_reference_rules", [])
    tables = data["tables"]
    for field in ("source", "target"):
        values = [row[field] for row in tables]
        if len(values) != len(set(values)):
            raise ValueError("表名映射不唯一: " + field)
        if any(not re.fullmatch(r"[a-z][a-z_0-9]*", value) or len(value.encode("utf-8")) > 63 for value in values):
            raise ValueError("表名格式或 PostgreSQL 标识符长度非法: " + field)
    for row in tables:
        if not row["target"].startswith("agent_runtime_" if row["owner"] == "runtime" else "nw_"):
            raise ValueError("表名与权威所有权不一致: " + row["source"])
        if row["state"] not in {"current", "historical"} or row["physical_state"] not in {"present", "removed"}:
            raise ValueError("表生命周期分类非法: " + row["source"])
    object_targets = [row["target"] for row in data["objects"]]
    if len(object_targets) != len(set(object_targets)):
        raise ValueError("附属对象的目标名称冲突")
    if any(len(name.encode("utf-8")) > 63 for name in object_targets):
        raise ValueError("附属对象超过 PostgreSQL 标识符长度")
    mapping = {row["source"]: row["target"] for row in tables if row["source"] != row["target"]}
    for row in tables:
        for previous in row.get("previous_targets", []):
            if previous in mapping and mapping[previous] != row["target"]:
                raise ValueError("中间表名映射冲突: " + previous)
            mapping[previous] = row["target"]
    object_names = set()
    for row in data["objects"]:
        mapping[row["source"]] = row["target"]
        object_names.add(row["source"])
        for previous in row.get("previous_targets", []):
            mapping[previous] = row["target"]
            object_names.add(previous)
    # 旧预算策略重建的临时表也属于 Runtime，未作为当前业务表单列。
    mapping.update({name + "_policy_v2": target + "_policy_v2" for name, target in list(mapping.items()) if name.startswith("runtime_budget_")})
    mapping["skillforge_runtime_%"] = "nw_runtime_%"
    return data, mapping, object_names


def validate_declarations(data: dict) -> list[str]:
    """独立于重写器核对声明全集，新增/漏改表不能靠重写器幂等蒙混过关。"""
    targets = {row["target"] for row in data["tables"]}
    targets.update(name + "_policy_v2" for name in list(targets) if name.startswith("agent_runtime_budget_"))
    targets.update(data["metadata_exceptions"])
    errors = []
    declarations: dict[str, set[str]] = {}
    schema_sources = []
    for root in (MIGRATIONS, SDK_SCHEMA, SQLITE_SCHEMA):
        for path in (ROOT / root).rglob("*"):
            if path.suffix not in {".sql", ".xml"}:
                continue
            relative = path.relative_to(ROOT).as_posix()
            names = set()
            source = path.read_text(encoding="utf-8-sig")
            schema_sources.append(source)
            if relative.startswith(MIGRATIONS):
                for number, line in enumerate(source.splitlines(), 1):
                    if re.search(r"\b(?:relname|table_name)\s+LIKE\s+'agent_runtime_%'", line, re.I) and "LIKE 'nw_runtime_%'" not in line:
                        errors.append(f"{relative}:{number}: Runtime 来源图筛选遗漏平台所属 nw_runtime_ 表")
            for match in TABLE_DDL.finditer(source):
                operation, name, renamed = match.groups()
                if operation.upper().startswith("CREATE") or renamed:
                    actual = renamed or name
                    names.add(actual)
                    if actual not in targets:
                        errors.append(relative + ": 未纳管的表声明 " + actual)
            declarations[relative] = names
    for row in data["tables"]:
        for relative in row["definitions"]:
            if row["target"] not in declarations.get(relative, set()):
                errors.append(relative + ": 缺少清单表声明 " + row["target"])
    all_ddl = "\n".join(re.sub(r"--[^\r\n]*|/\*[\s\S]*?\*/", "", source) for source in schema_sources)
    for row in data.get("objects", []):
        target = row["target"]
        if re.search(r"_[a-f0-9]{10}$", target) and not re.search(r"(?:\bCONSTRAINT\s+|\bCREATE\s+(?:UNIQUE\s+)?(?:INDEX|POLICY|FUNCTION|SEQUENCE|TRIGGER)\s+(?:public\.)?)" + re.escape(target) + r"\b", all_ddl, re.I):
            errors.append("缩短的附属对象名必须显式创建，不能只改 COMMENT/引用: " + target)
    return errors


def sql(text: str, mapping: dict[str, str], object_names: set[str] | None = None) -> str:
    """改 SQL 标识符和明确的 catalog/动态 SQL 表名，不改业务值及 JSON 键。"""
    def declarations(block: re.Match) -> str:
        return re.sub(r"\b([a-z_][a-z_0-9]*\s+)(public\.)?([a-z_][a-z_0-9]*)(?=\s*(?:;|:=|DEFAULT\b|%ROWTYPE))",
                      lambda item: item.group(1) + (item.group(2) or "") + mapping.get(item.group(3), item.group(3)), block.group(), flags=re.I)
    text = re.sub(r"\bDECLARE\b[\s\S]*?\bBEGIN\b", declarations, text, flags=re.I)
    tokens = list(TOKEN.finditer(text))
    # 别名和 CTE 只在所属语句内有效；忽略字面量及 dollar 函数正文里的分号。
    cuts = []
    skip_until = -1
    for token in tokens:
        if token.start() < skip_until:
            continue
        value = token.group()
        if value.startswith("$") and value.endswith("$"):
            closing = text.find(value, token.end())
            if closing >= 0:
                skip_until = closing + len(value)
        elif value == ";":
            cuts.append(token.end())
    if cuts and cuts[-1] < len(text):
        cuts.append(len(text))
    if len(cuts) > 1:
        starts = [0] + cuts[:-1]
        return "".join(sql(text[start:end], mapping, object_names) for start, end in zip(starts, cuts))
    changes = []
    significant: list[str] = []
    depth = 0
    relation_lists: dict[int, bool] = {}
    reserved = {"WHERE", "JOIN", "LEFT", "RIGHT", "FULL", "INNER", "CROSS", "ON", "SET", "VALUES", "RETURNING", "FOR", "ORDER", "GROUP", "UNION", "LIMIT", "OFFSET", "USING", "WHEN", "FROM"}
    identifier_pattern = r'(?:"(?:""|[^\"])*"|[a-z_][a-z_0-9]*)'
    def identity(value: str) -> str:
        return value[1:-1].replace('""','"') if value.startswith('"') else value.lower()
    aliases = {identity(item.group(1)) for item in re.finditer(
        r"\b(?:FROM|JOIN|UPDATE|INTO)\s+(?:public\.)?" + identifier_pattern + r"\s+(?:AS\s+)?(" + identifier_pattern + r")", text, re.I)
        if item.group(1).startswith('"') or item.group(1).upper() not in reserved}
    ctes = {identity(item.group(1)) for item in re.finditer(
        r"(?:\bWITH\s+(?:RECURSIVE\s+)?|,)\s*(" + identifier_pattern + r")\s*(?:\([^()]*\)\s*)?AS\s*\(", text, re.I)}
    index = 0
    while index < len(tokens):
        match = tokens[index]
        value = match.group()
        if value.startswith(("--", "/*")):
            index += 1
            continue
        if value.startswith("$") and value.endswith("$"):
            end = text.find(value, match.end())
            if end >= 0:
                inner = text[match.end():end]
                rewritten = sql(inner, mapping, object_names)
                if inner != rewritten:
                    changes.append((match.end(), end, rewritten))
                while index + 1 < len(tokens) and tokens[index + 1].start() < end + len(value):
                    index += 1
                index += 1
                continue
        if value.startswith("'"):
            inner = value[1:-1].replace("''", "'")
            # 完整 SDK owned 清单超过 5 KiB，不能用短窗口漏掉 IN/ARRAY 中的尾项。
            before = text[max(0, match.start() - 16000):match.start()]
            after = text[match.end():match.end() + 40]
            metadata = bool(re.search(r"(?:TG_TABLE_NAME|table_name|tablename|relname|proname|conname|tgname|polname)\s*(?:=|<>|!=|LIKE|IN\s*\([^;]*|NOT\s+IN\s*\([^;]*)\s*$", before, re.I | re.S))
            call = bool(re.search(r"(?:to_regclass|pragma_table_info)\s*\(\s*$", before, re.I))
            cast = bool(re.match(r"\s*::\s*(?:regclass|regtype)", after, re.I))
            table_array = bool(re.search(r"(?:FOREACH\s+(?:\w*table\w*|relation_name|name|t)\s+IN\s+ARRAY|(?:table_name|tablename|relname)\s*=\s*ANY)\s*(?:ARRAY)?\s*\[[^;]*$", before, re.I | re.S))
            if SQL_START.search(inner):
                rewritten = sql(inner, mapping, object_names)
            elif inner in mapping and (metadata or call or cast or table_array):
                rewritten = mapping[inner]
            elif inner.startswith("public."):
                rewritten = re.sub(r"(?<=public\.)[a-z_][a-z_0-9]*", lambda item: mapping.get(item.group(), item.group()), inner)
            else:
                rewritten = inner
            if inner != rewritten:
                changes.append((match.start(), match.end(), "'" + rewritten.replace("'", "''") + "'"))
            significant.append("<literal>")
            index += 1
            continue
        quoted = value.startswith('"')
        identifier = value[1:-1].replace('""', '"') if quoted else value
        # PostgreSQL 未加引号的标识符折叠为小写；双引号标识符必须保持精确大小写。
        lookup_identifier = identifier if quoted else identifier.lower()
        previous = significant[-1].upper() if significant else ""
        previous2 = significant[-2].upper() if len(significant) >= 2 else ""
        following = tokens[index + 1].group() if index + 1 < len(tokens) else ""
        # 限定表位置和表限定列；普通字段、局部变量和 JSON 属性不在此列。
        table_position = previous in {"FROM", "JOIN", "UPDATE", "INTO", "REFERENCES", "TABLE", "TRUNCATE"}
        table_position |= previous in {"EXISTS", "ONLY"} and any(t.upper() == "TABLE" for t in significant[-4:])
        table_position |= previous == "." and previous2 == "PUBLIC"
        table_position |= following == "." and lookup_identifier not in aliases and lookup_identifier not in ctes
        table_position |= previous == "ON" and bool(re.search(r"\b(?:CREATE|DROP|ALTER|GRANT|REVOKE|COMMENT)\b[^;]*$", text[max(0, match.start() - 1400):match.start()], re.I))
        table_position |= previous == "TO" and previous2 == "RENAME"
        table_position |= previous == "LIKE" and bool(re.search(r"\bCREATE\s+TABLE\b[^;]*\([^;]*$", text[:match.start()], re.I))
        table_position |= previous == "USING" and bool(re.search(r"\bDELETE\b[^;]*$", text[:match.start()], re.I))
        table_position |= previous == "," and relation_lists.get(depth, False)
        table_position |= previous in {"RETURNS", "SETOF"} or (previous == ":" and previous2 == ":") or following == "%"
        table_position |= previous == "," and bool(re.search(r"\b(?:LOCK\s+TABLE|TRUNCATE)\b[^;]*$", text[max(0, match.start() - 5000):match.start()], re.I))
        table_position |= previous == "," and bool(re.search(r"\b(?:GRANT|REVOKE)\b[^;]*\bON\b[^;]*$", text[max(0, match.start() - 5000):match.start()], re.I))
        if lookup_identifier in ctes and previous in {"FROM", "JOIN"}:
            table_position = False
        if lookup_identifier in mapping and (table_position or lookup_identifier in (object_names or set())):
            replacement = mapping[lookup_identifier]
            if quoted:
                replacement = '"' + replacement + '"'
            changes.append((match.start(), match.end(), replacement))
        significant.append(identifier)
        upper = identifier.upper()
        if upper == "FROM" or (upper == "USING" and re.search(r"\bDELETE\b", text[:match.start()], re.I)):
            relation_lists[depth] = True
        elif upper in {"WHERE", "SET", "ON", "RETURNING", "GROUP", "ORDER", "LIMIT", "HAVING", "UNION", "EXCEPT", "INTERSECT"}:
            relation_lists[depth] = False
        elif value == "(":
            depth += 1
        elif value == ")":
            relation_lists.pop(depth, None)
            depth = max(0, depth - 1)
        index += 1
    for start, end, replacement in reversed(changes):
        text = text[:start] + replacement + text[end:]
    return text


def rewrite(path: Path, text: str, mapping: dict[str, str], object_names: set[str] | None = None) -> str:
    relative = path.relative_to(ROOT).as_posix() if path.is_absolute() else path.as_posix()
    for rule in SCOPED_OBJECT_REFERENCES:
        if rule["path"] != relative:
            continue
        prefix = r"(\bDROP\s+CONSTRAINT\s+(?:IF\s+EXISTS\s+)?)" if rule["operation"] == "drop_constraint" else r"(\bCOMMENT\s+ON\s+INDEX\s+(?:public\.)?)"
        text = re.sub(prefix + re.escape(rule["source"]) + r"\b", lambda match: match.group(1) + rule["target"], text, flags=re.I)
    if path.suffix == ".sql":
        return sql(text, mapping, object_names)
    if path.suffix == ".xml":
        def table_fragment(match: re.Match) -> str:
            parts = re.split(r"(<[^>]+>)", match.group(2))
            body = "".join(part if part.startswith("<") else re.sub(r"^(\s*)([a-z_][a-z_0-9]*)(\s*)$", lambda item: item.group(1) + mapping.get(item.group(2), item.group(2)) + item.group(3), part) for part in parts)
            return match.group(1) + body + match.group(3)
        text = re.sub(r'(<sql\b[^>]*\bid="[^\"]*[Tt]able"[^>]*>)([\s\S]*?)(</sql>)', table_fragment, text)
        # XML 元素和属性保持原值，仅处理 SQL 文本与 CDATA。
        parts = re.split(r"(<!\[CDATA\[[\s\S]*?\]\]>|<[^>]+>)", text)
        return "".join("<![CDATA[" + sql(part[9:-3], mapping, object_names) + "]]>" if part.startswith("<![CDATA[") else part if part.startswith("<") else sql(part, mapping, object_names) for part in parts)
    if path.suffix == ".java":
        previous_end = -1
        previous_sql = ""
        table_arguments = java_table_arguments(text)
        def literal(match: re.Match) -> str:
            nonlocal previous_end, previous_sql
            value = match.group()
            inner = value[3:-3] if value.startswith('"""') else value[1:-1]
            connected = bool(previous_sql) and bool(re.fullmatch(r"\s*\+\s*", text[previous_end:match.start()]))
            if SQL_START.search(inner) or connected:
                marker = "/*schema_literal_boundary*/"
                rewritten = sql(previous_sql + marker + inner, mapping, object_names).split(marker, 1)[1] if connected else sql(inner, mapping, object_names)
                previous_sql = previous_sql + inner if connected else inner
                previous_end = match.end()
                return rewritten.join(('"""', '"""')) if value.startswith('"""') else '"' + rewritten + '"'
            previous_sql = ""
            previous_end = match.end()
            preceding = text[max(0, match.start() - 4000):match.start()]
            dynamic_relation_list = inner.lstrip().startswith(",") and bool(re.fullmatch(r"[,\s_a-zA-Z0-9]+", inner)) and bool(re.search(r'"(?:TRUNCATE\s|LOCK\s+TABLE\s|GRANT\s+[^";]*\sON\s)[^";]*"\s*\+[^;]*$', preceding, re.I))
            if dynamic_relation_list:
                return '"' + re.sub(r"[a-z_][a-z_0-9]*", lambda item: mapping.get(item.group(), item.group()), inner) + '"'
            if re.search(r"@TableName\s*\(\s*(?:value\s*=\s*)?$", preceding) and value[1:-1] in mapping:
                return '"' + mapping[value[1:-1]] + '"'
            table_list = bool(re.search(r"(?:\b[a-zA-Z_]*[Tt][Aa][Bb][Ll][Ee][Ss]?\w*\s*=\s*(?:java\.util\.)?(?:List|Set)\.of\(|\bfor\s*\([^)]*\btable\s*:\s*(?:java\.util\.)?(?:List|Set)\.of\()[^;]*$", preceding, re.S))
            following = text[match.end():match.end() + 4000]
            table_list |= bool(re.match(r"(?:[^;]*)\.contains\(table\)", following)) and bool(re.search(r"\bSet\.of\([^;]*$", preceding))
            helper_kind = table_arguments.get(match.start())
            helper = helper_kind == "table" or (helper_kind == "object" and inner in (object_names or set()))
            helper |= bool(re.search(r"\b(?:table|tableName)\.equals\(\s*$", preceding))
            sql_assertion = bool(re.search(r"\b(?:sql|xml|executed|statements|statement)\b[^;]*\.contains\(\s*$", preceding))
            if inner in mapping and (table_list or helper or sql_assertion):
                return '"' + mapping[inner] + '"'
            if inner.endswith("_") and inner[:-1] in mapping and re.search(r"\b(?:constraint|index|trigger)\s*=\s*$", preceding, re.I):
                return '"' + mapping[inner[:-1]] + '_"'
            return value
        return JAVA_LITERAL.sub(literal, text)
    if path.suffix == ".py":
        lines = text.splitlines(keepends=True)
        offsets = [0]
        for line in lines:
            offsets.append(offsets[-1] + len(line))
        changes = []
        for token in tokenize.generate_tokens(io.StringIO(text).readline):
            if token.type != tokenize.STRING:
                continue
            quoted = re.match(r"(?i)[rubf]*(\"\"\"|'''|\"|')", token.string)
            if not quoted:
                continue
            delimiter = quoted.group(1)
            inner = token.string[quoted.end():-len(delimiter)]
            if not SQL_START.search(inner):
                continue
            updated = sql(inner, mapping, object_names)
            if updated != inner:
                start = offsets[token.start[0] - 1] + token.start[1]
                end = offsets[token.end[0] - 1] + token.end[1]
                changes.append((start, end, token.string[:quoted.end()] + updated + delimiter))
        for start, end, replacement in reversed(changes):
            text = text[:start] + replacement + text[end:]
        return text
    return text


def candidates(paths: list[str]) -> list[Path]:
    roots = [ROOT / value for value in paths] if paths else [ROOT / "backend-java", ROOT / "tools"]
    results = set()
    for root in roots:
        paths = [root] if root.is_file() else []
        if root.is_dir():
            for directory, subdirectories, files in os.walk(root):
                subdirectories[:] = [name for name in subdirectories if name not in {"build", ".git", "node_modules"} and not name.startswith(".gradle")]
                paths.extend(Path(directory) / name for name in files)
        for path in paths:
            if path.suffix not in {".sql", ".java", ".xml", ".py"} or not path.is_file():
                continue
            relative = path.relative_to(ROOT).as_posix()
            if relative in HISTORICAL_FILES or relative in INDEPENDENT_SCHEMAS or relative.startswith("tools/database/"):
                continue
            results.add(path)
    return sorted(results)


def write_source(path: Path, text: str) -> None:
    """同目录原子替换源码；Windows 短暂文件占用不能留下已截断的半份源码。"""
    if not path.resolve().is_relative_to(ROOT.resolve()):
        raise ValueError("源码目标不在当前仓库: " + str(path))
    descriptor, temporary_name = tempfile.mkstemp(prefix=".schema-names-", suffix=".tmp", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(text.encode("utf-8"))
        for attempt in range(3):
            try:
                os.replace(temporary, path)
                return
            except OSError as failure:
                if attempt == 2 or failure.errno not in {13, 22}:
                    raise
                time.sleep(0.1)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--inventory", action="store_true", help="首次生成映射供审查；不能在改名后覆盖旧名称事实")
    action.add_argument("--check", action="store_true", help="只读检查可识别的旧 SQL 表标识符")
    action.add_argument("--apply", action="store_true", help="改写指定源码目录/文件，不连接数据库")
    parser.add_argument("paths", nargs="*", help="仓库相对路径；默认后端与正式 SDK 消费工程")
    parser.add_argument("--exclude", action="append", default=[], help="本次并发写入者拥有的仓库相对文件/目录；仅跳过当前调用")
    args = parser.parse_args()
    if args.inventory:
        if MANIFEST.exists():
            raise SystemExit("映射已存在；请审查差量，不能从改名后的源码覆盖旧名事实")
        data = inventory()
        MANIFEST.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(dict(Counter((row["owner"], row["state"]).__str__() for row in data["tables"])), ensure_ascii=False))
        return 0
    data, mapping, object_names = load_mapping()
    errors = validate_declarations(data) if args.check and not args.paths else []
    changed = []
    for path in candidates(args.paths):
        relative = path.relative_to(ROOT).as_posix()
        if any(relative == value.rstrip("/") or relative.startswith(value.rstrip("/") + "/") for value in args.exclude):
            continue
        original = path.read_bytes().decode("utf-8-sig")
        updated = rewrite(path, original, mapping, object_names)
        if original != updated:
            changed.append(relative)
            if args.apply:
                write_source(path, updated)
    for path in changed:
        print(path)
    for error in errors:
        print(error)
    print(f"{'已改写' if args.apply else '待改写'}文件: {len(changed)}")
    return 1 if (changed or errors) and args.check else 0


if __name__ == "__main__":
    sys.exit(main())
