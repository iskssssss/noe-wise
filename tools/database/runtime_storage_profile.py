"""只读统计 Runtime 存储压力；仅输出大小、数量和字段名，不读取业务正文。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit


def environment(path: Path) -> dict[str, str]:
    values = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        match = re.fullmatch(r"\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)", line)
        if match:
            value = match[2].strip()
            if len(value) > 1 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            values[match[1]] = value
    return values | dict(os.environ)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--container", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    values = environment(args.env_file)
    target = urlsplit(values["SPRING_DATASOURCE_URL"].removeprefix("jdbc:"))
    if target.hostname not in {"localhost", "127.0.0.1"}:
        parser.error("容器统计只接受已有本地 PostgreSQL 环境")
    mapped = subprocess.check_output(["docker", "port", args.container, "5432/tcp"], text=True)
    if not any(line.rsplit(":", 1)[-1] == str(target.port or 5432) for line in mapped.splitlines()):
        parser.error("容器端口与环境数据库不一致")
    process_env = dict(os.environ, PGPASSWORD=values["PLATFORM_DATASOURCE_PASSWORD"], PGCLIENTENCODING="UTF8")
    command = ["docker", "exec", "-i", "-e", "PGPASSWORD", "-e", "PGCLIENTENCODING", args.container,
               "psql", "-X", "-qAt", "-w", "-h", "127.0.0.1", "-U", values["PLATFORM_DATASOURCE_USERNAME"],
               "-d", target.path.removeprefix("/"), "-v", "ON_ERROR_STOP=1"]

    def query(sql):
        # 每次独立只读快照，避免长时间持有全库清理水位；没有业务参数或正文输出。
        result = subprocess.run(command, input="BEGIN READ ONLY; SET LOCAL statement_timeout='120s';\n" + sql + "\nROLLBACK;",
                                text=True, encoding="utf-8", capture_output=True, env=process_env, timeout=130)
        if result.returncode:
            # 查询仅有固定聚合 SQL；数据库诊断可定位权限/结构错误，凭据值继续移除。
            reason = result.stderr.replace(values["PLATFORM_DATASOURCE_PASSWORD"], "<redacted>")
            raise RuntimeError("只读存储统计失败：" + reason.strip()[:800])
        return json.loads(result.stdout.strip())

    report = {"database": target.path.removeprefix("/"), "read_only": True, "tables": [], "large_fields": []}
    report["tables"] = query("""SELECT coalesce(json_agg(t ORDER BY total_bytes DESC),'[]') FROM
      (SELECT c.relname AS name, c.reltuples::bigint AS estimated_rows,
        pg_table_size(c.oid) AS table_bytes, pg_indexes_size(c.oid) AS index_bytes,
        pg_total_relation_size(c.oid) AS total_bytes,
        coalesce(s.n_dead_tup,0) AS estimated_dead_rows
      FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      LEFT JOIN pg_stat_user_tables s ON s.relid=c.oid
      WHERE n.nspname='public' AND c.relkind='r'
        AND (c.relname LIKE 'agent_runtime_%' OR c.relname LIKE 'nw_%')) t;""")
    fields = query("""SELECT coalesce(json_agg(t),'[]') FROM
      (SELECT table_name,column_name,data_type FROM information_schema.columns
       WHERE table_schema='public' AND table_name LIKE 'agent_runtime_%'
       AND (data_type IN ('json','jsonb','bytea') OR column_name IN
         ('canonical_json','body','payload','snapshot','content','document_text','search_text'))
       ORDER BY table_name,ordinal_position) t;""")
    for field in fields:
        table, column = field["table_name"], field["column_name"]
        if not all(re.fullmatch(r"[a-z][a-z0-9_]*", item) for item in (table, column)):
            raise RuntimeError("数据库元数据标识不受支持")
        table_sql, column_sql = '"' + table + '"', '"' + column + '"'
        # octet_length(c::text) 用于逻辑大小，pg_column_size 用于数据库实际字段大小，两者不能混报。
        sample = query(f"""SELECT row_to_json(t) FROM (SELECT count(*) AS rows,
          coalesce(sum(pg_column_size({column_sql})),0) AS stored_bytes,
          coalesce(sum(octet_length({column_sql}::text)),0) AS logical_bytes,
          coalesce(max(octet_length({column_sql}::text)),0) AS max_logical_bytes,
          coalesce(count(*) FILTER (WHERE octet_length({column_sql}::text)>1048576),0) AS over_1mib_rows
          FROM public.{table_sql}) t;""")
        if sample["logical_bytes"]:
            report["large_fields"].append(dict(field, **sample))
    report["large_fields"].sort(key=lambda row: row["logical_bytes"], reverse=True)
    report["inline_model_components"] = query("""WITH requests AS (
        SELECT tenant_id,(canonical_json::jsonb #>> '{request,canonicalJson}')::jsonb AS request
        FROM agent_runtime_execution_materials
        WHERE schema_id='skillforge.runtime.execution.prepared-model-request.v2'
      ), messages AS MATERIALIZED (
        SELECT tenant_id,sha256(convert_to(message::text,'UTF8')) AS digest,
          octet_length(message::text) AS bytes
        FROM requests CROSS JOIN LATERAL jsonb_array_elements(request->'messages') message
      ), tool_sets AS MATERIALIZED (
        SELECT tenant_id,sha256(convert_to((request->'tools')::text,'UTF8')) AS digest,
          octet_length((request->'tools')::text) AS bytes FROM requests
      ), unique_messages AS (SELECT tenant_id,digest,max(bytes) AS bytes FROM messages GROUP BY tenant_id,digest),
      unique_tools AS (SELECT tenant_id,digest,max(bytes) AS bytes FROM tool_sets GROUP BY tenant_id,digest)
      SELECT json_build_object('requests',(SELECT count(*) FROM requests),
        'message_occurrences',(SELECT count(*) FROM messages),
        'unique_messages',(SELECT count(*) FROM unique_messages),
        'uniqueness_basis','tenant and SHA-256 of JSONB text',
        'repeated_message_bytes',coalesce((SELECT sum(bytes) FROM messages),0),
        'unique_message_bytes',coalesce((SELECT sum(bytes) FROM unique_messages),0),
        'tool_set_occurrences',(SELECT count(*) FROM tool_sets),
        'unique_tool_sets',(SELECT count(*) FROM unique_tools),
        'repeated_tool_bytes',coalesce((SELECT sum(bytes) FROM tool_sets),0),
        'unique_tool_bytes',coalesce((SELECT sum(bytes) FROM unique_tools),0));""")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"tables": len(report["tables"]), "fields": len(report["large_fields"]),
                      "total_bytes": sum(row["total_bytes"] for row in report["tables"]), "report": str(args.output)}))


if __name__ == "__main__":
    main()
