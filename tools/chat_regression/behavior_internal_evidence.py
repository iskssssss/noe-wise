"""显式启用的本机只读证据通道；不增加 HTTP 管理接口，不覆盖公开投影。"""
from copy import deepcopy
import json
import os
from urllib.parse import urlsplit

from behavior_contract import digest, now, require
from client import collect_trace

MODE = "local-postgres-readonly-v1"
DSN_ENV = "SKILLFORGE_EVAL_INTERNAL_DSN"


class InternalEvidenceError(RuntimeError):
    """错误不携带连接凭据、SQL 返回正文或供应商载荷。"""


class InternalEvidenceReader:
    def __init__(self, dsn, tenant_id):
        try:
            import psycopg2
            from psycopg2.extensions import parse_dsn
        except ImportError:
            raise InternalEvidenceError("internal_evidence_driver_required") from None
        try:
            config = parse_dsn(dsn)
            require(set(config) <= {"host", "port", "dbname", "user", "password", "sslmode"},
                    "internal_evidence_dsn_options_rejected")
            require(config.get("host") in {"127.0.0.1", "::1"}, "internal_evidence_requires_loopback_database")
            require(all(config.get(k) for k in ("port", "dbname", "user", "password")), "internal_evidence_explicit_database_required")
            require(1 <= int(config["port"]) <= 65535 and isinstance(tenant_id, str) and bool(tenant_id),
                    "internal_evidence_scope_required")
        except Exception:
            raise InternalEvidenceError("internal_evidence_configuration_invalid") from None
        self._connect, self._config, self.tenant_id = psycopg2.connect, config, tenant_id

    def _connection(self):
        connection = self._connect(**self._config, hostaddr=self._config["host"], connect_timeout=5,
            options="-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=1000",
            application_name="skillforge-behavior-evidence")
        try:
            connection.set_session(readonly=True, isolation_level="REPEATABLE READ", autocommit=False)
        except Exception:
            connection.close()
            raise
        return connection

    def preflight(self):
        """在创建测试资源和模型调用前验证连接、只读属性与固定表权限。"""
        connection = None
        try:
            connection = self._connection()
            with connection, connection.cursor() as cursor:
                cursor.execute("SHOW transaction_read_only")
                require(cursor.fetchone()[0] == "on", "internal_evidence_not_readonly")
                cursor.execute("SELECT set_config('app.tenant_id', %s, true)", (self.tenant_id,))
                cursor.execute("SELECT event_id FROM public.agent_runtime_session_events WHERE false")
                cursor.execute("""SELECT r.run_id,a.action_id,t.attempt_id,m.schema_id,m.canonical_json
                    FROM public.agent_runtime_execution_runs r,
                         public.agent_runtime_execution_actions a,
                         public.agent_runtime_execution_attempts t,
                         public.agent_runtime_execution_materials m WHERE false""")
        except Exception:
            raise InternalEvidenceError("internal_evidence_preflight_failed") from None
        finally:
            if connection is not None:
                connection.close()

    def augment(self, trace, session_id):
        """只读取 API 已授权并返回的事件，按租户、会话、事件和序号核对来源。"""
        from behavior_capture import flatten
        require(isinstance(session_id, str) and bool(session_id), "internal_evidence_session_required")
        _, events = flatten(trace)
        require(len(events) <= 100000, "internal_evidence_event_limit")
        execution_runs = {row["run_id"] for row in events if row.get("event_type", "").startswith("execution.")}
        if execution_runs:
            return self.augment_execution(trace, session_id, execution_runs)
        expected = {row["event_id"]: row for row in events}
        result, internal = deepcopy(trace), {}
        total_bytes = 0
        connection = None
        try:
            if expected:
                connection = self._connection()
                with connection, connection.cursor() as cursor:
                    # 机构只读角色仍受强制 RLS；作用域仅在本事务生效，不借助提权或连接全局状态。
                    cursor.execute("SELECT set_config('app.tenant_id', %s, true)", (self.tenant_id,))
                    ids = list(expected)
                    for offset in range(0, len(ids), 200):
                        cursor.execute("""SELECT event_id, event_type, session_sequence, run_id,
                            payload_schema_id, payload_canonical_json
                            FROM public.agent_runtime_session_events
                            WHERE tenant_id = %s AND session_id = %s AND event_id = ANY(%s)
                            ORDER BY session_sequence""", (self.tenant_id, session_id, ids[offset:offset + 200]))
                        for eid, event_type, sequence, run_id, schema, raw in cursor.fetchall():
                            row = expected.get(eid)
                            require(row is not None and row["event_type"] == event_type
                                    and row["run_id"] == (run_id or "unassigned-run")
                                    and row.get("detail", {}).get("sessionSequence") == sequence
                                    and row.get("detail", {}).get("payloadSchemaId") == schema,
                                    "internal_evidence_source_mismatch")
                            ref = row["content_ref"]
                            require(ref == "session-event:" + eid and ref not in internal,
                                    "internal_evidence_reference_mismatch")
                            require(isinstance(raw, str) and len(raw.encode("utf-8")) <= 16 * 1024 * 1024,
                                    "internal_evidence_payload_limit")
                            total_bytes += len(raw.encode("utf-8"))
                            require(total_bytes <= 256 * 1024 * 1024, "internal_evidence_total_limit")
                            payload = json.loads(raw)
                            internal[ref] = {"availability": "FULL", "schema_id": schema,
                                "value": payload, "source": MODE, "payload_digest": digest(payload)}
                require(len(internal) == len(expected), "internal_evidence_missing_events")
        except Exception:
            raise InternalEvidenceError("internal_evidence_collection_failed") from None
        finally:
            if connection is not None:
                connection.close()
        result["internal_contents"] = internal
        result["internal_evidence"] = {"mode": MODE, "tenant_id": self.tenant_id,
            "session_id": session_id, "collected_at": now(), "event_count": len(internal),
            "content_digest": digest(internal)}
        return result


    def augment_execution(self, trace, session_id, run_ids):
        """当前执行链仅读取 API 已返回 Run 的已关联材料；不扫描历史或回退旧事件表。"""
        from behavior_execution_evidence import collect
        connection = None
        try:
            connection = self._connection()
            with connection, connection.cursor() as cursor:
                cursor.execute("SELECT set_config('app.tenant_id', %s, true)", (self.tenant_id,))
                projected = collect(cursor, self.tenant_id, session_id, run_ids)
        except Exception:
            raise InternalEvidenceError("execution_evidence_collection_failed") from None
        finally:
            if connection is not None:
                connection.close()
        result = deepcopy(trace)
        result["execution_evidence"] = projected
        result["internal_contents"] = deepcopy(trace.get("contents", {}))
        result["internal_evidence"] = {"mode": MODE, "tenant_id": self.tenant_id,
            "session_id": session_id, "collected_at": now(), "execution_digest": digest(projected)}
        return result


def configure_internal_evidence(api, enabled):
    if not enabled:
        return
    endpoint = urlsplit(api.base)
    require(endpoint.scheme == "http" and endpoint.hostname in {"127.0.0.1", "::1", "localhost"},
            "internal_evidence_requires_local_api")
    require(bool(os.environ.get(DSN_ENV)), "internal_evidence_dsn_required")
    principal = api.request("GET", "/auth/me")
    tenant_id = principal.get("tenant_id")
    reader = InternalEvidenceReader(os.environ[DSN_ENV], tenant_id)
    reader.preflight()
    api.internal_evidence_reader = reader


def collect_evaluation_trace(api, session_id, *, collector=None, **kwargs):
    trace = (collector or collect_trace)(api, session_id, **kwargs)
    reader = vars(api).get("internal_evidence_reader")
    if reader is None:
        return trace
    try:
        return reader.augment(trace, session_id)
    except InternalEvidenceError:
        # 已完成调用的公开用量仍需结算；缺失内部事实不得退回公开内容冒称完整。
        result = deepcopy(trace)
        result["internal_contents"] = {}
        result["internal_evidence"] = {"mode": MODE, "error": "internal_evidence_collection_failed"}
        return result
