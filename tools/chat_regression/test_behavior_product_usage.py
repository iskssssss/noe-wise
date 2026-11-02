"""产品父子 Session 用量结算的租户绑定、去重与失败关闭。"""
from __future__ import annotations

from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parent))

from behavior_product_usage import ProductUsageError, collect_product_usage


def usage(tokens, *, calls=1, prompt=0, completion=0, unknown=0, amounts=None):
    return {"calls": calls, "tokens": tokens, "prompt_tokens": prompt,
            "completion_tokens": completion, "amount_by_currency": amounts or {},
            "unknown_usage_calls": unknown}


def task(identity):
    return {"task_id": identity, "run_id": "child:" + identity}


class Cursor:
    def __init__(self, delegations=(), nested=None, usage_rows=(), *, readonly=True, fail=False):
        self.delegations = list(delegations)
        self.nested = nested
        self.usage_rows = list(usage_rows)
        self.readonly = readonly
        self.fail = fail
        self.executed = []
        self.current = None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, statement, parameters=None):
        self.executed.append((statement, parameters))
        if self.fail:
            raise PermissionError("private database detail")
        if statement.startswith("SHOW"):
            self.current = "readonly"
        elif "SELECT parent_run_id, delegation_id" in statement:
            self.current = "delegations"
        elif "SELECT parent_session_id, delegation_id" in statement:
            self.current = "nested"
        elif "SELECT session_id, event_type" in statement:
            self.current = "usage"
        else:
            self.current = "set_config"

    def fetchone(self):
        if self.current == "readonly":
            return ("on",) if self.readonly else ("off",)
        if self.current == "nested":
            return self.nested
        return None

    def fetchall(self):
        if self.current == "delegations":
            return list(self.delegations)
        if self.current == "usage":
            return list(self.usage_rows)
        raise AssertionError("unexpected_fetchall")


class Connection:
    def __init__(self, cursor):
        self.cursor_value = cursor
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def cursor(self):
        return self.cursor_value

    def close(self):
        self.closed = True


def api_with(cursor, items):
    api = Mock()
    api.request.return_value = {"items": items}
    connection = Connection(cursor)
    api.internal_evidence_reader = Mock(tenant_id="7", _connection=Mock(return_value=connection))
    return api, connection


class ProductUsageTests(unittest.TestCase):
    def collect(self, api, parent=usage(10, calls=2, prompt=6, completion=4, amounts={"CNY": 3})):
        with patch("behavior_product_usage.collect_trace", return_value={"usage": parent}) as trace:
            result = collect_product_usage(api, "parent-session")
        trace.assert_called_once_with(api, "parent-session", include_content=False)
        return result

    def test_no_direct_task_returns_parent_usage_without_database_access(self):
        api = Mock()
        api.request.return_value = {"items": [{"run_id": "parent-run", "tasks": [
            {"task_id": "dag-task", "run_id": "child:other"}]}]}

        result = self.collect(api)

        self.assertEqual(usage(10, calls=2, prompt=6, completion=4, amounts={"CNY": 3}),
                         {key: value for key, value in result.items() if key != "child_sessions"})
        self.assertEqual([], result["child_sessions"])
        self.assertNotIn("internal_evidence_reader", vars(api))

    def test_counts_parent_and_children_once_and_matches_session_trace_usage_rules(self):
        a, b = "delegation:a", "delegation:b"
        cursor = Cursor(
            delegations=[("parent-run", a, "child:" + a, "COMPLETED"),
                         ("parent-run", b, "child:" + b, "COMPLETED")],
            usage_rows=[
                ("delegation:" + a, "llm.chat", 5, 3, 2, "PROVIDER_REPORTED", 2, "CNY"),
                ("delegation:" + a, "request", None, None, None, None, None, None),
                ("delegation:" + b, "llm.chat", 7, 4, 3, "ESTIMATED", 1, "CNY"),
            ])
        api, connection = api_with(cursor, [{"run_id": "parent-run", "tasks": [task(a), task(b)]}])

        result = self.collect(api)

        self.assertEqual(22, result["tokens"])
        self.assertEqual(4, result["calls"])
        self.assertEqual(13, result["prompt_tokens"])
        self.assertEqual(9, result["completion_tokens"])
        self.assertEqual({"CNY": 6}, result["amount_by_currency"])
        self.assertEqual(0, result["unknown_usage_calls"])
        self.assertEqual(["delegation:delegation:a", "delegation:delegation:b"],
                         [child["session_id"] for child in result["child_sessions"]])
        self.assertTrue(connection.closed)
        self.assertEqual(("7", "parent-session"), cursor.executed[2][1])
        self.assertEqual((7, ["delegation:" + a, "delegation:" + b]), cursor.executed[4][1])

    def test_duplicate_authorized_task_is_deduplicated_before_settlement(self):
        delegation = "delegation:a"
        cursor = Cursor(
            delegations=[("parent-run", delegation, "child:" + delegation, "COMPLETED")],
            usage_rows=[("delegation:" + delegation, "llm.chat", 5, 3, 2,
                         "PROVIDER_REPORTED", None, None)])
        item = {"run_id": "parent-run", "tasks": [task(delegation)]}
        api, _ = api_with(cursor, [item, item])

        result = self.collect(api)

        self.assertEqual(15, result["tokens"])
        self.assertEqual(1, len(result["child_sessions"]))
        self.assertEqual(("7", "parent-session"), cursor.executed[2][1])

    def test_missing_authorized_task_cannot_hide_database_child_usage(self):
        cursor = Cursor(delegations=[("parent-run", "delegation:a", "child:delegation:a", "COMPLETED")])
        api, _ = api_with(cursor, [])
        with self.assertRaisesRegex(ProductUsageError, "product_child_usage_scope_mismatch"):
            self.collect(api)

    def test_database_identity_must_match_authorized_history_exactly(self):
        delegation = "delegation:a"
        cursor = Cursor(delegations=[("another-parent", delegation, "child:" + delegation, "COMPLETED")])
        api, connection = api_with(cursor, [{"run_id": "parent-run", "tasks": [task(delegation)]}])

        with self.assertRaisesRegex(ProductUsageError, "product_child_usage_scope_mismatch"):
            self.collect(api)

        self.assertTrue(connection.closed)

    def test_unknown_child_usage_remains_unknown_in_aggregate(self):
        delegation = "delegation:a"
        cursor = Cursor(
            delegations=[("parent-run", delegation, "child:" + delegation, "FAILED")],
            usage_rows=[("delegation:" + delegation, "llm.chat", 99, 50, 49,
                         "UNKNOWN", 4, "USD")])
        api, _ = api_with(cursor, [{"run_id": "parent-run", "tasks": [task(delegation)]}])

        result = self.collect(api)

        self.assertEqual(10, result["tokens"])
        self.assertEqual(1, result["unknown_usage_calls"])
        self.assertEqual({"USD": 4}, result["child_sessions"][0]["usage"]["amount_by_currency"])

    def test_nested_delegation_fails_closed(self):
        delegation = "delegation:a"
        cursor = Cursor(
            delegations=[("parent-run", delegation, "child:" + delegation, "RUNNING")],
            nested=("delegation:" + delegation, "delegation:grandchild"))
        api, _ = api_with(cursor, [{"run_id": "parent-run", "tasks": [task(delegation)]}])

        with self.assertRaisesRegex(ProductUsageError, "product_child_usage_nested_delegation"):
            self.collect(api)

    def test_missing_reader_or_database_failure_is_explicit(self):
        delegation = "delegation:a"
        api = Mock()
        api.request.return_value = {"items": [{"run_id": "parent-run", "tasks": [task(delegation)]}]}
        with self.assertRaisesRegex(ProductUsageError, "product_child_usage_requires_internal_evidence"):
            self.collect(api)

        cursor = Cursor(fail=True)
        api, connection = api_with(cursor, [{"run_id": "parent-run", "tasks": [task(delegation)]}])
        with self.assertRaisesRegex(ProductUsageError, "product_child_usage_collection_failed"):
            self.collect(api)
        self.assertTrue(connection.closed)


if __name__ == "__main__":
    unittest.main()
