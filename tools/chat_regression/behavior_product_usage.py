"""产品行为批次的父子 Session 用量只读结算。"""
from __future__ import annotations

from collections import defaultdict

from client import collect_trace, query


_KNOWN_USAGE_SOURCES = {"PROVIDER_REPORTED", "ESTIMATED"}
_USAGE_FIELDS = ("calls", "tokens", "prompt_tokens", "completion_tokens", "unknown_usage_calls")


class ProductUsageError(RuntimeError):
    """不携带数据库异常正文、连接信息或私有模型内容的结算错误。"""


def collect_product_usage(api, parent_session_id):
    """返回父 Session 与已授权直接委派子 Session 的合计用量。"""
    if not isinstance(parent_session_id, str) or not parent_session_id:
        raise ProductUsageError("product_usage_parent_session_required")
    parent_trace = collect_trace(api, parent_session_id, include_content=False)
    try:
        parent_usage = _normalize_usage(parent_trace["usage"])
    except (KeyError, TypeError, ValueError, ProductUsageError):
        raise ProductUsageError("product_usage_parent_trace_invalid") from None

    history = api.request("GET", query("/chat/work/history", session_id=parent_session_id))
    expected = _direct_tasks(history)
    reader = vars(api).get("internal_evidence_reader")
    if not expected and reader is None:
        return {**parent_usage, "child_sessions": []}
    if reader is None or not callable(getattr(reader, "_connection", None)):
        raise ProductUsageError("product_child_usage_requires_internal_evidence")
    if not isinstance(getattr(reader, "tenant_id", None), str) or not reader.tenant_id:
        raise ProductUsageError("product_child_usage_scope_invalid")

    children = _collect_children(reader, parent_session_id, expected)
    aggregate = _merge_usage([parent_usage, *(child["usage"] for child in children)])
    return {**aggregate, "child_sessions": children}


def _direct_tasks(history):
    if not isinstance(history, dict) or not isinstance(history.get("items"), list):
        raise ProductUsageError("product_usage_work_history_invalid")
    expected = {}
    for work in history["items"]:
        if not isinstance(work, dict) or not isinstance(work.get("tasks", []), list):
            raise ProductUsageError("product_usage_work_history_invalid")
        parent_run = work.get("run_id")
        for task in work.get("tasks", []):
            if not isinstance(task, dict):
                raise ProductUsageError("product_usage_work_history_invalid")
            delegation_id = task.get("task_id")
            child_run = task.get("run_id")
            direct_id = isinstance(delegation_id, str) and delegation_id.startswith("delegation:")
            direct_run = isinstance(child_run, str) and child_run.startswith("child:delegation:")
            if not direct_id and not direct_run:
                continue
            if (not direct_id or child_run != "child:" + delegation_id
                    or not isinstance(parent_run, str) or not parent_run):
                raise ProductUsageError("product_usage_delegation_identity_invalid")
            identity = (parent_run, delegation_id, child_run)
            previous = expected.setdefault(delegation_id, identity)
            if previous != identity:
                raise ProductUsageError("product_usage_delegation_identity_invalid")
    return set(expected.values())


def _collect_children(reader, parent_session_id, expected):
    connection = None
    try:
        connection = reader._connection()
        with connection, connection.cursor() as cursor:
            cursor.execute("SHOW transaction_read_only")
            if cursor.fetchone() != ("on",):
                raise ProductUsageError("product_child_usage_connection_not_readonly")
            cursor.execute("SELECT set_config('app.tenant_id',%s,true)", (reader.tenant_id,))
            cursor.execute("""SELECT parent_run_id, delegation_id, child_run_id, state
                FROM public.agent_runtime_delegations
                WHERE tenant_id = %s AND parent_session_id = %s
                ORDER BY created_epoch_second, created_nano, delegation_id, parent_run_id""",
                (reader.tenant_id, parent_session_id))
            rows = cursor.fetchall()
            actual = {(parent_run, delegation_id, child_run)
                      for parent_run, delegation_id, child_run, _ in rows}
            if len(actual) != len(rows) or actual != expected:
                raise ProductUsageError("product_child_usage_scope_mismatch")
            if not rows:
                return []

            child_sessions = ["delegation:" + delegation_id
                              for _, delegation_id, _, _ in rows]
            cursor.execute("""SELECT parent_session_id, delegation_id
                FROM public.agent_runtime_delegations
                WHERE tenant_id = %s AND parent_session_id = ANY(%s)
                LIMIT 1""", (reader.tenant_id, child_sessions))
            if cursor.fetchone() is not None:
                raise ProductUsageError("product_child_usage_nested_delegation")

            cursor.execute("""SELECT session_id, event_type, tokens, prompt_tokens,
                       completion_tokens, usage_source, total_amount_cents, currency
                FROM public.nw_usage_events
                WHERE tenant_id = %s AND session_id = ANY(%s)
                ORDER BY session_id, id""", (int(reader.tenant_id), child_sessions))
            usage_rows = cursor.fetchall()
        usage = _usage_by_session(usage_rows, set(child_sessions))
        states = {(parent_run, delegation_id, child_run): state
                  for parent_run, delegation_id, child_run, state in rows}
        return [{"session_id": "delegation:" + delegation_id,
                 "delegation_id": delegation_id,
                 "parent_run_id": parent_run,
                 "run_id": child_run,
                 "state": states[(parent_run, delegation_id, child_run)],
                 "usage": usage["delegation:" + delegation_id]}
                for parent_run, delegation_id, child_run in sorted(expected)]
    except ProductUsageError:
        raise
    except Exception:
        raise ProductUsageError("product_child_usage_collection_failed") from None
    finally:
        if connection is not None:
            connection.close()


def _usage_by_session(rows, sessions):
    grouped = defaultdict(list)
    for row in rows:
        if not isinstance(row, (tuple, list)) or len(row) != 8 or row[0] not in sessions:
            raise ProductUsageError("product_child_usage_scope_mismatch")
        grouped[row[0]].append(row[1:])
    return {session: _summarize_usage(grouped[session]) for session in sessions}


def _summarize_usage(rows):
    usage = _empty_usage()
    for event_type, tokens, prompt, completion, source, amount, currency in rows:
        if event_type != "request":
            usage["calls"] += 1
        if source in _KNOWN_USAGE_SOURCES:
            usage["tokens"] += _database_number(tokens)
            usage["prompt_tokens"] += _database_number(prompt)
            usage["completion_tokens"] += _database_number(completion)
        elif event_type != "request":
            usage["unknown_usage_calls"] += 1
        if amount is not None:
            if type(amount) is not int:
                raise ProductUsageError("product_child_usage_invalid")
            key = currency if isinstance(currency, str) and currency else "UNKNOWN"
            usage["amount_by_currency"][key] = usage["amount_by_currency"].get(key, 0) + amount
    return usage


def _normalize_usage(usage):
    if not isinstance(usage, dict):
        raise ProductUsageError("product_usage_invalid")
    normalized = _empty_usage()
    for field in _USAGE_FIELDS:
        value = usage.get(field)
        if type(value) is not int or value < 0:
            raise ProductUsageError("product_usage_invalid")
        normalized[field] = value
    amounts = usage.get("amount_by_currency", {})
    if not isinstance(amounts, dict):
        raise ProductUsageError("product_usage_invalid")
    for currency, amount in amounts.items():
        if not isinstance(currency, str) or not currency or type(amount) is not int:
            raise ProductUsageError("product_usage_invalid")
        normalized["amount_by_currency"][currency] = amount
    return normalized


def _merge_usage(usages):
    merged = _empty_usage()
    for usage in usages:
        for field in _USAGE_FIELDS:
            merged[field] += usage[field]
        for currency, amount in usage["amount_by_currency"].items():
            merged["amount_by_currency"][currency] = merged["amount_by_currency"].get(currency, 0) + amount
    return merged


def _empty_usage():
    return {"calls": 0, "tokens": 0, "prompt_tokens": 0, "completion_tokens": 0,
            "amount_by_currency": {}, "unknown_usage_calls": 0}


def _database_number(value):
    if value is None:
        return 0
    if type(value) is not int or value < 0:
        raise ProductUsageError("product_child_usage_invalid")
    return value
