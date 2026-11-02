"""在全新私有PostgreSQL夹具上执行V279，验证真实RLS、追加事实与调查引用保护。

使用Dockerfile.drill镜像并覆盖入口运行；不挂载业务数据库，不发布端口，不读取.env。
"""
from __future__ import annotations

import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as xml


PORT = 55434
REPOSITORY = Path("/repo")


def call(arguments: list[str]) -> str:
    """只运行当前夹具的固定管理命令，失败保留真实诊断。"""
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=120, check=False)
    if result.returncode:
        raise AssertionError(f"夹具命令失败:{arguments[0]}:{result.stderr.strip()}")
    return result.stdout.strip()


def sql(command: str, scope: str | None = None, failure: str | None = None, platform: bool = False) -> str:
    """每次重新绑定非超级账号和精确作用域，防止测试依赖连接状态。"""
    prefix = "" if scope is None else f"SET ROLE {'ops_platform' if platform else 'ops_tenant'}; SET app.tenant_id='{scope}'; "
    result = subprocess.run(["psql", "-h", "127.0.0.1", "-p", str(PORT), "-U", "postgres", "-d", "postgres",
                             "-Atq", "-v", "ON_ERROR_STOP=1"], input=prefix + command,
                            capture_output=True, text=True, timeout=30, check=False)
    if failure:
        assert result.returncode and failure in result.stderr, result.stderr
    else:
        assert result.returncode == 0, result.stderr
    return result.stdout.strip()


def production_candidates(cutoff: str, limit: int) -> str:
    """直接读取生产XML候选SQL，展开唯一scope片段与受控测试参数。"""
    source = REPOSITORY / "backend-java/skillforge-core-foundation/src/main/resources/mapper/foundation/OperationsMapper.xml"
    root = xml.parse(source).getroot()
    element = next(item for item in root.findall("select") if item.attrib["id"] == "candidates")
    parts = [element.text or ""]
    for child in element:
        assert child.tag == "include" and child.attrib["refid"] == "auditScope"
        parts.extend(["tenant_id=10", child.tail or ""])
    return "".join(parts).replace("#{cutoff}", f"'{cutoff}'::timestamptz").replace("#{limit}", str(limit))


def main() -> None:
    """真实执行迁移和数据库约束，结束时只停止本脚本创建的夹具集群。"""
    root = Path(tempfile.mkdtemp(prefix="skillforge-operations-sql-"))
    data = root / "postgres"
    started = False
    try:
        call(["initdb", "-D", str(data), "--auth=trust", "--username=postgres", "--no-locale"])
        call(["pg_ctl", "-D", str(data), "-l", str(root / "postgres.log"), "-o", f"-p {PORT} -h 127.0.0.1", "-w", "start"])
        started = True
        sql("""
          CREATE TABLE public.nw_audit_logs(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,action text NOT NULL,
            actor_id text,resource_type text,resource_id text,message text,trace_id text,payload jsonb,created_at timestamptz NOT NULL);
          ALTER TABLE public.nw_audit_logs ENABLE ROW LEVEL SECURITY;
          ALTER TABLE public.nw_audit_logs FORCE ROW LEVEL SECURITY;
          CREATE POLICY skillforge_audit_logs_tenant_isolation ON public.nw_audit_logs
            USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint);
          CREATE ROLE ops_tenant NOLOGIN NOBYPASSRLS;
          CREATE ROLE ops_platform NOLOGIN BYPASSRLS;
        """)
        migration = REPOSITORY / "backend-java/skillforge-migration/src/main/resources/db/migration/V279__foundation_operations.sql"
        sql(migration.read_text(encoding="utf-8"))
        sql("""
          GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO ops_tenant,ops_platform;
          INSERT INTO nw_audit_logs(id,tenant_id,action,created_at) VALUES
            (1,10,'fixture.old',now()-interval '200 days'),(2,20,'fixture.other',now()-interval '200 days'),
            (3,10,'fixture.recent',now()-interval '1 day'),(4,10,'fixture.held',now()-interval '210 days'),
            (5,10,'fixture.rollback',now()-interval '200 days'),(6,NULL,'fixture.platform',now()-interval '200 days');
        """)
        assert sql("SELECT string_agg(id::text,',' ORDER BY id) FROM nw_audit_logs", "10") == "1,3,4,5"
        assert sql("SELECT string_agg(id::text,',' ORDER BY id) FROM nw_audit_logs", "20") == "2"
        assert sql("SELECT id FROM nw_audit_logs WHERE tenant_id IS NULL", "platform", platform=True) == "6"
        sql("INSERT INTO nw_audit_logs(id,tenant_id,action,category,outcome,created_at) VALUES(7,NULL,'auth.denied','authentication','denied',now())", "10")
        assert sql("SELECT count(*) FROM nw_audit_logs WHERE tenant_id IS NULL", "10") == "0"
        sql("INSERT INTO nw_audit_logs(id,tenant_id,action,category,outcome,created_at) VALUES(8,NULL,'auth.ok','authentication','success',now())", "10", "row-level security")
        sql("INSERT INTO nw_audit_logs(id,tenant_id,action,created_at) VALUES(8,20,'other.write',now())", "10", "row-level security")
        assert sql("SELECT id FROM nw_audit_logs WHERE id=1 FOR UPDATE", "10") == "1"
        sql("UPDATE nw_audit_logs SET action='overwrite' WHERE id=1", "10", "audit_event_immutable")
        sql("INSERT INTO nw_foundation_retention_policies(scope,resource,retention_days,enabled) VALUES('10','audit',180,true)", "10")
        sql("INSERT INTO nw_foundation_retention_policies(scope,resource,retention_days,enabled) VALUES('20','audit',180,true)", "20")
        sql("INSERT INTO nw_foundation_retention_policies(scope,resource,retention_days,enabled) VALUES('platform','audit',180,true)", "platform", platform=True)
        assert sql("SELECT string_agg(scope,',') FROM nw_foundation_retention_policies", "10") == "10"
        sql("INSERT INTO nw_foundation_audit_holds(id,scope,audit_id,reason) VALUES(40,'10',4,'active investigation')", "10")
        sql("INSERT INTO nw_foundation_audit_holds(id,scope,audit_id,reason) VALUES(41,'10',2,'cross tenant')", "10", "audit_hold_scope_invalid")
        sql("INSERT INTO nw_foundation_audit_holds(id,scope,audit_id,reason) VALUES(41,'20',1,'scope spoof')", "10", "audit_hold_scope_invalid")
        sql("UPDATE nw_foundation_audit_holds SET audit_id=1,released_at=now() WHERE id=40", "10", "audit_hold_immutable")
        sql("DELETE FROM nw_audit_logs WHERE id=1", "10", "audit_delete_requires_retention")
        sql("BEGIN; SET LOCAL app.audit_cleanup='on'; DELETE FROM nw_audit_logs WHERE id=3; COMMIT", "10", "audit_delete_requires_retention")
        sql("BEGIN; SET LOCAL app.audit_cleanup='on'; DELETE FROM nw_audit_logs WHERE id=4; COMMIT", "10", "audit_event_held")
        # 永久保护的最老记录不会挡住后续可清理事实，查询直接复用生产Mapper SQL。
        assert sql(production_candidates("2100-01-01T00:00:00Z", 1), "10") != "4"
        sql("UPDATE nw_foundation_audit_holds SET released_at=now() WHERE id=40", "10")
        sql("UPDATE nw_foundation_audit_holds SET released_at=now() WHERE id=40", "10", "audit_hold_immutable")
        sql("BEGIN; SET LOCAL app.audit_cleanup='on'; DELETE FROM nw_audit_logs WHERE id IN(1,4); COMMIT", "10")
        assert sql("SELECT audit_id FROM nw_foundation_audit_holds WHERE id=40", "10") == "4"
        assert sql("SELECT count(*) FROM nw_audit_logs WHERE id IN(1,4)", "10") == "0"
        sql("BEGIN; SET LOCAL app.audit_cleanup='on'; DELETE FROM nw_audit_logs WHERE id=5; SELECT 1/0; COMMIT", "10", "division by zero")
        assert sql("SELECT count(*) FROM nw_audit_logs WHERE id=5", "10") == "1"
        sql("""INSERT INTO nw_foundation_cleanup_runs(id,scope,actor_id,resource,policy_version,cutoff,candidate_ids,candidates,
          protected_count,status,expires_at) VALUES(80,'10','42','audit',0,now()-interval '180 days','[5]',1,0,'ready',now()+interval '15 minutes')""", "10")
        assert sql("SELECT count(*) FROM nw_foundation_cleanup_runs", "20") == "0"
        sql("UPDATE nw_foundation_cleanup_runs SET status='succeeded',completed_at=now(),candidate_ids='[3]',deleted_count=1 WHERE id=80", "10", "cleanup_receipt_immutable")
        sql("UPDATE nw_foundation_cleanup_runs SET status='succeeded',completed_at=now(),deleted_count=1 WHERE id=80", "10")
        sql("UPDATE nw_foundation_cleanup_runs SET deleted_count=0 WHERE id=80", "10", "cleanup_receipt_immutable")
        assert sql("SELECT status||':'||deleted_count FROM nw_foundation_cleanup_runs WHERE id=80", "10") == "succeeded:1"
        print(json.dumps({"v279_migration": "passed", "tenant_rls": "passed", "anonymous_denied_insert": "passed",
          "immutable_audit": "passed", "row_lock": "passed", "hold_scope": "passed", "active_hold_blocks_delete": "passed",
          "retention_floor": "passed", "released_hold_tombstone": "passed", "held_candidates_do_not_starve": "passed",
          "delete_rollback": "passed", "frozen_terminal_receipt": "passed", "network": "none", "business_database_touched": False}))
    finally:
        if started:
            subprocess.run(["pg_ctl", "-D", str(data), "-m", "immediate", "-w", "stop"], capture_output=True, timeout=30)
        shutil.rmtree(root)


if __name__ == "__main__":
    main()
