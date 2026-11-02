"""只读 Provider 用量：按 attempt 归并累计报告，未知值不替换为零。"""
from __future__ import annotations

from collections import defaultdict
import re


class UsageUnavailable(RuntimeError):
    """仅携带固定诊断码，不包含 DSN、SQL 错误或供应商正文。"""


def natural(value):
    """保留 bigint 精度；布尔、小数、负数和缺失均不是实测用量。"""
    if type(value) is int and value >= 0:
        return value
    if isinstance(value, str) and re.fullmatch(r"[0-9]+", value):
        return int(value)
    raise UsageUnavailable("provider_usage_nonnegative_integer_required")


def summarize(attempts, records):
    """Provider 累计快照逐字段取最新，不累加流式 revisions 或 cache 子集。"""
    by_attempt = {}
    for attempt in attempts:
        identity = attempt.get("attempt_id")
        if not isinstance(identity, str) or not identity or identity in by_attempt:
            raise UsageUnavailable("provider_attempt_identity_invalid")
        if attempt.get("status") not in {"STARTED", "SUCCEEDED", "FAILED", "UNRESOLVED"}:
            raise UsageUnavailable("provider_attempt_status_invalid")
        by_attempt[identity] = attempt
    grouped, seen = defaultdict(list), set()
    for row in records:
        identity = row.get("id")
        if not isinstance(identity, str) or not identity or identity in seen:
            raise UsageUnavailable("provider_usage_event_identity_invalid")
        seen.add(identity)
        if row.get("attempt_id") not in by_attempt:
            raise UsageUnavailable("provider_usage_without_owned_attempt")
        if row.get("usage_event_kind") in {"measured", "usage_report"}:
            grouped[row["attempt_id"]].append(row)
    rows, total, pending, unknown = [], 0, 0, 0
    for identity, attempt in by_attempt.items():
        values, sources = {}, []
        for row in sorted(grouped[identity], key=lambda item: (
                natural(item["report_revision"]) if item.get("report_revision") is not None else -1,
                natural(item["id"]))):
            if row.get("provider") != attempt.get("provider"):
                raise UsageUnavailable("provider_usage_owner_mismatch")
            sources.append(row["id"])
            if row.get("usage_source") != "PROVIDER_REPORTED":
                continue
            for field in ("tokens", "prompt_tokens", "completion_tokens"):
                if row.get(field) is not None:
                    values[field] = natural(row[field])
        if attempt.get("operation_type") == "model.chat":
            complete = "prompt_tokens" in values and "completion_tokens" in values
            tokens = values.get("prompt_tokens", 0) + values.get("completion_tokens", 0)
        else:
            complete = "tokens" in values
            tokens = values.get("tokens", values.get("prompt_tokens", 0))
        # 已结束但缺少完整 Provider 报告、明确UNRESOLVED均阻止后续派发。
        in_flight = attempt["status"] == "STARTED"
        known = complete and not in_flight and attempt["status"] != "UNRESOLVED"
        pending += in_flight
        unknown += not known and not in_flight
        total += tokens
        rows.append({**attempt, "provider_event_ids": sources, "provider_values": values,
                     "tokens_lower_bound": tokens, "usage_known": known, "in_flight": in_flight})
    return {"usage_known": pending == 0 and unknown == 0, "tokens": total,
            "pending_attempts": pending, "unknown_attempts": unknown, "attempts": rows,
            "source": "local_postgres_readonly_provider_reported_cumulative_by_attempt"}


class ProviderReader:
    """只读取本隔离机构、当前预留边界之后的 Provider 尝试和原始计量字段。"""

    def __init__(self, reader):
        self.reader = reader
        self.tenant_id = reader.tenant_id
        self.boundary = None

    def _cursor(self, connection):
        cursor = connection.cursor()
        cursor.execute("SHOW transaction_read_only")
        if cursor.fetchone() != ("on",):
            raise UsageUnavailable("provider_connection_not_readonly")
        cursor.execute("SELECT set_config('app.tenant_id',%s,true)", (self.tenant_id,))
        return cursor

    def preflight(self):
        """派发前验证固定表权限，冻结全机构边界；不读取凭证或业务正文。"""
        connection = None
        try:
            connection = self.reader._connection()
            with connection:
                with self._cursor(connection) as cursor:
                    cursor.execute("""SELECT COUNT(*) FROM public.nw_external_invocations WHERE tenant_id=%s
                        AND operation_type IN ('model.chat','model.embedding','model.rerank')
                        AND status IN ('STARTED','UNRESOLVED')""", (int(self.tenant_id),))
                    if cursor.fetchone()[0]:
                        raise UsageUnavailable("provider_prior_pending_or_unresolved_attempt")
                    cursor.execute("SELECT COALESCE(MAX(id),0) FROM public.nw_external_invocations WHERE tenant_id=%s",
                                   (int(self.tenant_id),))
                    invocation = int(cursor.fetchone()[0])
                    cursor.execute("SELECT COALESCE(MAX(id),0) FROM public.nw_usage_events WHERE tenant_id=%s",
                                   (int(self.tenant_id),))
                    usage = int(cursor.fetchone()[0])
                    cursor.execute("SELECT attempt_id FROM public.observation_effective_model_usage WHERE false")
                    cursor.execute("SELECT delegation_id FROM public.agent_runtime_delegations WHERE false")
            self.boundary = {"invocation_id_upper_bound": str(invocation), "usage_id_upper_bound": str(usage)}
            return self.boundary
        except UsageUnavailable:
            raise
        except Exception:
            raise UsageUnavailable("provider_usage_preflight_failed") from None
        finally:
            if connection is not None:
                connection.close()

    def snapshot(self, sessions=()):
        if self.boundary is None:
            raise UsageUnavailable("provider_usage_boundary_required")
        connection = None
        try:
            connection = self.reader._connection()
            with connection:
                with self._cursor(connection) as cursor:
                    cursor.execute("""SELECT attempt_id,invocation_id,operation_type,status,provider,
                        metadata->>'model' AS model,root_input_id,source_event_id
                        FROM public.nw_external_invocations WHERE tenant_id=%s AND id>%s
                        AND operation_type IN ('model.chat','model.embedding','model.rerank') ORDER BY id""",
                        (int(self.tenant_id), int(self.boundary["invocation_id_upper_bound"])))
                    keys = ("attempt_id", "invocation_id", "operation_type", "status", "provider", "model", "root_input_id", "source_event_id")
                    attempts = [dict(zip(keys, row)) for row in cursor.fetchall()]
                    cursor.execute("""SELECT id::text,event_id,attempt_id,event_type,usage_event_kind,
                        report_revision,provider,model,session_id,run_id,tokens,prompt_tokens,completion_tokens,
                        usage_source,total_amount_cents,currency FROM public.nw_usage_events
                        WHERE tenant_id=%s AND id>%s AND event_type IN ('llm.chat','llm.embedding','llm.rerank')
                        ORDER BY id""", (int(self.tenant_id), int(self.boundary["usage_id_upper_bound"])))
                    keys = ("id", "event_id", "attempt_id", "event_type", "usage_event_kind", "report_revision", "provider", "model",
                            "session_id", "run_id", "tokens", "prompt_tokens", "completion_tokens", "usage_source", "total_amount_cents", "currency")
                    records = [dict(zip(keys, row)) for row in cursor.fetchall()]
                    # 保存安全计量字段，排除metadata/请求/响应payload；bigint按十进制字符串存档。
                    for row in records:
                        for key in ("report_revision", "tokens", "prompt_tokens", "completion_tokens", "total_amount_cents"):
                            if row[key] is not None:
                                row[key] = str(row[key])
                    children, frontier, seen = [], list(sessions), set(sessions)
                    for _ in range(10):
                        if not frontier:
                            break
                        cursor.execute("""SELECT parent_session_id,parent_run_id,delegation_id,child_run_id,state
                            FROM public.agent_runtime_delegations WHERE tenant_id=%s AND parent_session_id=ANY(%s)
                            ORDER BY parent_session_id,delegation_id""", (self.tenant_id, frontier))
                        child_keys = ("parent_session_id", "parent_run_id", "delegation_id", "child_run_id", "state")
                        current = [dict(zip(child_keys, row)) for row in cursor.fetchall()]
                        children += current
                        frontier = ["delegation:" + row["delegation_id"] for row in current
                                    if "delegation:" + row["delegation_id"] not in seen]
                        seen.update(frontier)
                    else:
                        raise UsageUnavailable("provider_delegation_depth_limit")
            result = summarize(attempts, records)
            result.update(boundary=self.boundary, tenant_id=self.tenant_id, provider_records=records,
                          delegation_records=children, owned_parent_sessions=sorted(set(sessions)))
            return result
        except UsageUnavailable:
            raise
        except Exception:
            raise UsageUnavailable("provider_usage_collection_failed") from None
        finally:
            if connection is not None:
                connection.close()


def normalize_public_usage(value):
    """仅适配公开契约的bigint表示，不把缺失或估算声明为Provider实测。"""
    result = dict(value)
    for field in ("calls", "unknown_usage_calls"):
        result[field] = natural(result.get(field))
    for field in ("tokens", "prompt_tokens", "completion_tokens"):
        if result.get(field) is None:
            if result["calls"] == 0:
                result[field] = 0
            else:
                result["unknown_usage_calls"] = max(1, result["unknown_usage_calls"])
        else:
            result[field] = natural(result[field])
    return result
