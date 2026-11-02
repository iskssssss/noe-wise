#!/usr/bin/env python3
"""为固定历史重置清单生成只读核验 SQL；不连接数据库或执行清理。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys


MANIFEST = Path(__file__).with_name("runtime_history_reset_tables.json")


def literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def load_manifest() -> dict:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for key in ("runtime_history_tables", "host_history_tables", "optional_new_history_tables", "preserved_tables"):
        names = manifest[key]
        if len(names) != len(set(names)) or any(not re.fullmatch(r"[a-z][a-z0-9_]*", name) for name in names):
            raise ValueError(f"invalid_fixed_manifest:{key}")
    history = manifest["runtime_history_tables"] + manifest["host_history_tables"]
    if len(history) != 161 or len(set(history)) != 161 or set(history) & set(manifest["preserved_tables"]):
        raise ValueError("invalid_fixed_manifest:history_scope")
    return manifest


def generate(database: str, mode: str) -> str:
    manifest = load_manifest()
    required = manifest["runtime_history_tables"] + manifest["host_history_tables"]
    history = required + manifest["optional_new_history_tables"]
    history_array = "ARRAY[" + ",".join(literal(name) for name in history) + "]::text[]"
    required_array = "ARRAY[" + ",".join(literal(name) for name in required + manifest["preserved_tables"]) + "]::text[]"
    fingerprint_queries = [
        f"SELECT {literal(name)} AS table_name, count(*) AS row_count, "
        "md5(coalesce(string_agg(row_digest, '' ORDER BY row_digest), '')) AS content_digest "
        f"FROM (SELECT md5(row_to_json(value)::text) AS row_digest FROM public.{name} value) hashed"
        for name in manifest["preserved_tables"]
    ]
    # 默认基线只核对配置、依赖与触发器；空库模式逐表断言历史行已移除。
    empty_check = ""
    if mode == "empty":
        empty_check = f"""
DO $verify_empty$
DECLARE table_name text; nonempty boolean;
BEGIN
    FOREACH table_name IN ARRAY {history_array} LOOP
        IF to_regclass(format('public.%I', table_name)) IS NOT NULL THEN
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM public.%I)', table_name) INTO nonempty;
            IF nonempty THEN RAISE EXCEPTION 'runtime_history_not_empty:%', table_name; END IF;
        END IF;
    END LOOP;
END;
$verify_empty$;
"""
    return f"""\\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
DO $verify_target$
DECLARE table_name text;
BEGIN
    IF current_database() <> {literal(database)} THEN RAISE EXCEPTION 'runtime_verify_database_mismatch'; END IF;
    FOREACH table_name IN ARRAY {required_array} LOOP
        IF to_regclass(format('public.%I', table_name)) IS NULL THEN
            RAISE EXCEPTION 'runtime_verify_required_table_missing:%', table_name;
        END IF;
    END LOOP;
END;
$verify_target$;
{empty_check}
DO $verify_closure$
DECLARE unexpected text;
BEGIN
    WITH RECURSIVE selected AS (
        SELECT to_regclass(format('public.%I', name)) AS oid FROM unnest({history_array}) name
        WHERE to_regclass(format('public.%I', name)) IS NOT NULL
    ), closure(oid) AS (
        SELECT oid FROM selected
        UNION
        SELECT fk.conrelid FROM pg_constraint fk JOIN closure parent ON parent.oid=fk.confrelid
        WHERE fk.contype='f'
    ) SELECT string_agg(oid::regclass::text, ',' ORDER BY oid::regclass::text) INTO unexpected
        FROM closure WHERE oid NOT IN (SELECT oid FROM selected);
    IF unexpected IS NOT NULL THEN RAISE EXCEPTION 'runtime_verify_unlisted_fk_dependents:%', unexpected; END IF;
END;
$verify_closure$;
WITH RECURSIVE selected AS (
    SELECT to_regclass(format('public.%I', name)) AS oid FROM unnest({history_array}) name
    WHERE to_regclass(format('public.%I', name)) IS NOT NULL
), closure(oid) AS (
    SELECT oid FROM selected
    UNION
    SELECT fk.conrelid FROM pg_constraint fk JOIN closure parent ON parent.oid=fk.confrelid
    WHERE fk.contype='f'
), configuration AS (
    {(' UNION ALL ' + chr(10)).join(fingerprint_queries)}
)
SELECT jsonb_build_object(
    'mode', {literal(mode)},
    'history_table_count', (SELECT count(*) FROM selected),
    'unexpected_fk_dependents', coalesce((SELECT jsonb_agg(oid::regclass::text ORDER BY oid::regclass::text)
        FROM closure WHERE oid NOT IN (SELECT oid FROM selected)), '[]'::jsonb),
    'preserved', (SELECT jsonb_object_agg(table_name, jsonb_build_object('count', row_count, 'md5', content_digest)) FROM configuration),
    'triggers', coalesce((SELECT jsonb_agg(jsonb_build_object('table', c.relname, 'name', t.tgname, 'enabled', t.tgenabled) ORDER BY c.relname,t.tgname)
        FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
        WHERE NOT t.tgisinternal AND c.oid IN (SELECT oid FROM selected)), '[]'::jsonb)
);
COMMIT;
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True, help="核验对象的确切数据库名；SQL 内再次校验")
    parser.add_argument("--mode", choices=("baseline", "empty"), default="baseline")
    args = parser.parse_args()
    if not args.database.strip() or "\x00" in args.database:
        parser.error("database must be explicit")
    sys.stdout.reconfigure(encoding="utf-8")
    print(generate(args.database, args.mode), end="")


if __name__ == "__main__":
    main()
