#!/usr/bin/env python3
"""从固定 manifest 生成原开发库历史清理 SQL；本工具不连接或修改数据库。"""

from __future__ import annotations

import sys

from runtime_history_verify_sql import literal, load_manifest


def generate() -> str:
    manifest = load_manifest()
    history = manifest["runtime_history_tables"] + manifest["host_history_tables"]
    names = ",\n    ".join("public." + name for name in history)
    history_array = "ARRAY[" + ",".join(literal(name) for name in history) + "]::text[]"
    return f"""-- 由 runtime_history_clear_sql.py 与固定 runtime_history_reset_tables.json 生成。
-- 仅在新库切换成功、原库停写且完整备份验收后执行；保留业务/配置序列值。
\\set ON_ERROR_STOP on
\\if :{{?expected_database}}
\\else
\\echo '必须通过 -v expected_database=确切原库名 指定对象'
\\quit 3
\\endif
SELECT current_database() = :'expected_database'
       AND current_database() = {literal(manifest['source_database'])} AS history_reset_target_ok
\\gset
\\if :history_reset_target_ok
\\else
\\echo '数据库对象不匹配固定清单，退出'
\\quit 3
\\endif
SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user
               AND (rolsuper OR rolbypassrls)) AS history_reset_role_ok
\\gset
\\if :history_reset_role_ok
\\else
\\echo '清理和完整核验要求 superuser 或 BYPASSRLS，退出'
\\quit 3
\\endif
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';
LOCK TABLE
    {names}
IN ACCESS EXCLUSIVE MODE;
DO $verify_closure$
DECLARE unexpected text;
BEGIN
    WITH RECURSIVE selected AS (
        SELECT to_regclass(format('public.%I', name)) AS oid FROM unnest({history_array}) name
    ), closure(oid) AS (
        SELECT oid FROM selected
        UNION
        SELECT fk.conrelid FROM pg_constraint fk JOIN closure parent ON parent.oid=fk.confrelid
        WHERE fk.contype='f'
    ) SELECT string_agg(oid::regclass::text, ',' ORDER BY oid::regclass::text) INTO unexpected
        FROM closure WHERE oid NOT IN (SELECT oid FROM selected);
    IF unexpected IS NOT NULL THEN RAISE EXCEPTION 'runtime_history_unlisted_fk_dependents:%', unexpected; END IF;
END;
$verify_closure$;
TRUNCATE TABLE
    {names}
CONTINUE IDENTITY RESTRICT;
DO $verify_empty$
DECLARE table_name text; nonempty boolean;
BEGIN
    FOREACH table_name IN ARRAY {history_array} LOOP
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM public.%I)', table_name) INTO nonempty;
        IF nonempty THEN RAISE EXCEPTION 'runtime_history_not_empty:%', table_name; END IF;
    END LOOP;
END;
$verify_empty$;
COMMIT;
\\echo '固定{len(history)}表历史清理完成；业务配置及序列保持，另运行只读核验对比配置摘要和trigger状态'
"""


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    print(generate(), end="")
