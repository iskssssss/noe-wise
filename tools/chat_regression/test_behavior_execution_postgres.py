"""显式启用的独立 PostgreSQL 材料验收，自动创建并清理专属空白容器。"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).parent))
from behavior_execution_evidence import collect, MODEL_MANIFEST, MODEL_MESSAGE, MODEL_TOOLS
from behavior_internal_evidence import InternalEvidenceReader
from test_behavior_execution_evidence import model, refusal, tool


@unittest.skipUnless(os.environ.get("RUN_EXECUTION_EVIDENCE_POSTGRES") == "1", "explicit isolated PostgreSQL opt-in required")
class ExecutionPostgresTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import psycopg2
        cls.name = "sf-eval-evidence-" + uuid.uuid4().hex[:12]
        cls.password = secrets.token_hex(20)
        cls.addClassCleanup(lambda: subprocess.run(["docker", "rm", "--force", cls.name], capture_output=True, timeout=30))
        subprocess.run(["docker", "run", "--rm", "-d", "--name", cls.name,
                        "-e", "POSTGRES_PASSWORD=" + cls.password, "-p", "127.0.0.1::5432", "postgres:16-alpine"],
                       check=True, capture_output=True, timeout=60)
        port = subprocess.check_output(["docker", "port", cls.name, "5432/tcp"], text=True, timeout=10).strip().rsplit(":", 1)[1]
        cls.config = {"host": "127.0.0.1", "port": port, "dbname": "postgres", "user": "postgres", "password": cls.password}
        deadline = time.monotonic() + 30
        while True:
            try:
                admin = psycopg2.connect(**cls.config)
                break
            except psycopg2.OperationalError:
                if time.monotonic() >= deadline: raise
                time.sleep(0.2)
        with admin, admin.cursor() as cursor:
            migration = Path(__file__).resolve().parents[2] / "backend-java/skillforge-migration/src/main/resources/db/migration"
            for name in ("V247__execution_budget_ledger.sql", "V292__typed_runtime_execution_store.sql"):
                cursor.execute((migration / name).read_text(encoding="utf-8"))
            cursor.execute("CREATE ROLE evidence_reader LOGIN PASSWORD %s NOSUPERUSER NOBYPASSRLS", (cls.password,))
            cursor.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO evidence_reader")
            for tenant, session, run in (("tenant", "session", "run"), ("foreign", "session", "foreign-run"), ("tenant", "other", "other-run")):
                cls.seed(cursor, tenant, session, run)
        admin.close()
        cls.reader = InternalEvidenceReader("host=127.0.0.1 port=" + port + " dbname=postgres user=evidence_reader password=" + cls.password, "tenant")

    @classmethod
    def seed(cls, cursor, tenant, session, run):
        cursor.execute("SELECT set_config('app.tenant_id',%s,true)", (tenant,))
        cursor.execute("INSERT INTO agent_runtime_budget_roots(tenant_id,root_id,scenario,source_version,active_limit_seconds) VALUES(%s,%s,'TEST','v1',60)", (tenant, run))
        cursor.execute("INSERT INTO agent_runtime_budget_runs VALUES(%s,%s,%s,'PRIMARY','v1',60)", (tenant, run, run))
        cursor.execute("INSERT INTO agent_runtime_execution_sessions(tenant_id,session_id,target_kind,target_id,end_user_id) VALUES(%s,%s,'AGENT','agent',1)", (tenant, session))
        cursor.execute("INSERT INTO agent_runtime_execution_materials VALUES(%s,%s,'application/json','{}')", (tenant, run + ':base'))
        cursor.execute("""INSERT INTO agent_runtime_execution_runs(tenant_id,run_id,session_id,target_kind,target_id,end_user_id,agent_id,
            input_id,trace_id,span_id,root_input_id,identity_ref,actor_verification_ref,configuration_ref,input_ref,initial_context_ref,
            admission_fingerprint,status,phase,revision,context_ref,created_second,created_nano,updated_second,updated_nano)
            VALUES(%s,%s,%s,'AGENT','agent',1,'agent',%s,'trace','span','root','identity','actor','config',%s,%s,
                   'fingerprint','COMPLETED','FINISHED',1,%s,1,0,1,0)""", (tenant, run, session, run, run + ':base', run + ':base', run + ':base'))
        # 一次拒绝后继续模型调用；另一个工具的已采纳失败证明拒绝和失败分开。
        rows = [model(rid=run), model(2, rid=run, messages=refusal(), calls=[])]
        failed = list(tool("FAILED", rid=run)); failed[2] = 3
        request = json.loads(failed[10]); request["wireName"] = "failed_tool"; failed[10] = json.dumps(request)
        rows.append(tuple(failed))
        for _, action, ordinal, kind, status, selected, attempt, adopted, result_kind, qs, qr, zs, zr in rows:
            action, attempt = run + ':' + action, run + ':' + attempt
            qref, zref = action + ':q', action + ':z'
            if kind == "MODEL":
                frozen = json.loads(json.loads(qr)["request"]["canonicalJson"])
                message_refs = []
                for index, message in enumerate(frozen.pop("messages")):
                    reference = action + ':message:' + str(index)
                    cursor.execute("INSERT INTO agent_runtime_execution_materials VALUES(%s,%s,%s,%s)",
                                   (tenant, reference, MODEL_MESSAGE, json.dumps({"message": message})))
                    message_refs.append(reference)
                tools_ref = action + ':tools'
                cursor.execute("INSERT INTO agent_runtime_execution_materials VALUES(%s,%s,%s,%s)",
                               (tenant, tools_ref, MODEL_TOOLS, json.dumps({"tools": frozen.pop("tools", [])})))
                qs = MODEL_MANIFEST
                qr = json.dumps({"request": frozen, "messageRefs": message_refs, "toolsRef": tools_ref,
                                 "transformations": [], "supplementalIncludedThrough": "0", "output": None})
            cursor.execute("INSERT INTO agent_runtime_execution_materials VALUES(%s,%s,%s,%s),(%s,%s,%s,%s)", (tenant, qref, qs, qr, tenant, zref, zs, zr))
            cursor.execute("INSERT INTO agent_runtime_budget_attempts(tenant_id,attempt_id,run_id,fingerprint,category,closing,state) VALUES(%s,%s,%s,'fp','BUSINESS_MODEL',false,'SETTLED')", (tenant, attempt, run))
            cursor.execute("""INSERT INTO agent_runtime_execution_actions(tenant_id,action_id,run_id,action_ordinal,kind,target_handle,
                capability_ref,request_ref,fingerprint,status,current_attempt,selected_attempt)
                VALUES(%s,%s,%s,%s,%s,'target','cap',%s,'fp',%s,%s,%s)""", (tenant, action, run, ordinal, kind, qref, status, attempt, attempt))
            cursor.execute("""INSERT INTO agent_runtime_execution_attempts(tenant_id,attempt_id,action_id,run_id,fence,request_ref,
                fingerprint,result_kind,result_ref,adopted) VALUES(%s,%s,%s,%s,1,%s,'fp',%s,%s,true)""", (tenant, attempt, action, run, qref, result_kind, zref))

    def test_real_sql_material_chain_and_readonly_transaction(self):
        connection = self.reader._connection()
        self.addCleanup(connection.close)
        with connection, connection.cursor() as cursor:
            cursor.execute("SHOW transaction_read_only")
            self.assertEqual("on", cursor.fetchone()[0])
            cursor.execute("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user")
            self.assertEqual((False, False), cursor.fetchone())
            cursor.execute("SELECT set_config('app.tenant_id',%s,true)", ("tenant",))
            projected = collect(cursor, "tenant", "session", {"run"})
            calls = projected["runs"]["run"]["calls"]
            self.assertTrue(next(c for c in calls if c["state"] == "attempted")["refused"])
            self.assertEqual("failed_tool", next(c for c in calls if c["state"] == "failed")["tool"])

    def test_real_rls_session_and_run_scope_exclude_other_materials(self):
        connection = self.reader._connection()
        self.addCleanup(connection.close)
        with connection, connection.cursor() as cursor:
            cursor.execute("SELECT set_config('app.tenant_id',%s,true)", ("tenant",))
            for tenant, session, run in (("foreign", "session", "foreign-run"), ("tenant", "session", "other-run"), ("tenant", "other", "run")):
                self.assertEqual([], collect(cursor, tenant, session, {run})["runs"][run]["calls"])

    def test_real_reader_augment_execution_uses_existing_readonly_connection(self):
        projected = self.reader.augment_execution({"contents": {}, "pages": []}, "session", {"run"})["execution_evidence"]
        self.assertEqual(2, len(projected["runs"]["run"]["calls"]))


if __name__ == "__main__":
    unittest.main()
