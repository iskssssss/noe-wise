"""群身份客户端的请求身份、未知结果和首错停止；不连接宿主或模型。"""
import copy
import unittest

from client import ApiFailure
from group_identity import run_identity, validate_plan


class GroupIdentityTest(unittest.TestCase):
    def setUp(self):
        self.plan = {
            "ensure_key": "ensure-key",
            "ensure": {"namespace": "wecom", "external_group_ref": "room/1", "lifecycle_ref": "life/1", "business_scope_id": "101"},
            "members": [{"end_user_id": "9007199254740993101", "role": "manager", "status": "active", "expected_revision": 0,
                         "source_event_id": "member-1", "source_revision": 7, "idempotency_key": "member-key"}],
            "agents": [{"agent_id": "9007199254740993102", "status": "active", "expected_revision": 0,
                        "source_event_id": "agent-1", "source_revision": 8, "idempotency_key": "agent-key"}],
        }
        self.group = {"group_binding_id": "opaque/group", "session_id": "opaque/session", "subject": {"kind": "group", "group_binding_id": "opaque/group"}}

    def test_group_flow_preserves_original_keys_sources_and_exact_ids(self):
        calls = []
        group = self.group

        class Client:
            def request(self, method, path, body, **kwargs):
                calls.append((method, path, body, kwargs))
                if path.endswith("/update"):
                    return {**{key: value for key, value in body.items() if key != "expected_revision"}, "revision": 1}
                return copy.deepcopy(group)

        first = run_identity(Client(), self.plan, evidence_kind="client_fixture")
        second = run_identity(Client(), self.plan, evidence_kind="client_fixture")
        self.assertEqual("PASS", first["status"])
        self.assertEqual(first, second)
        self.assertEqual(calls[:4], calls[4:])
        self.assertEqual(["ensure-key", None, "member-key", "agent-key"], [call[3]["idempotency_key"] for call in calls[:4]])
        self.assertEqual("9007199254740993101", calls[2][2]["end_user_id"])
        self.assertEqual(7, calls[2][2]["source_revision"])
        self.assertEqual(0, calls[2][2]["expected_revision"])
        self.assertEqual({"group_binding_id": "opaque/group"}, calls[1][3]["query"])
        self.assertFalse(any("sessions" in call[1] or "messages" in call[1] or "triggers" in call[1] for call in calls))

    def test_network_unknown_stops_without_retry_or_next_operation(self):
        for failure in (TimeoutError(), ApiFailure(500, "INTERNAL_ERROR")):
            with self.subTest(error=type(failure).__name__):
                calls = []

                class Client:
                    def request(self, *args, **kwargs):
                        calls.append((args, kwargs))
                        raise failure

                evidence = run_identity(Client(), self.plan, evidence_kind="client_fixture")
                self.assertEqual("UNKNOWN", evidence["status"])
                self.assertEqual(1, len(calls))
                self.assertEqual("ensure-key", evidence["steps"][0]["idempotency_key"])
                self.assertEqual(self.plan["ensure"], evidence["steps"][0]["body"])

    def test_cas_conflict_retains_failure_and_stops_before_agent_or_close(self):
        group = self.group
        calls = []

        class Client:
            def request(self, method, path, body, **kwargs):
                calls.append(path)
                if path.endswith("members/update"):
                    raise ApiFailure(409, "CONFLICT")
                return copy.deepcopy(group)

        plan = {**self.plan, "close": {"expected_revision": 1, "idempotency_key": "close-key"}}
        evidence = run_identity(Client(), plan, evidence_kind="client_fixture")
        self.assertEqual("FAILED", evidence["status"])
        self.assertEqual(3, len(calls))
        self.assertEqual(409, evidence["steps"][-1]["http_status"])
        self.assertEqual("CONFLICT", evidence["steps"][-1]["code"])

    def test_close_requires_explicit_plan_and_uses_exact_binding_cas(self):
        group = self.group
        calls = []

        class Client:
            def request(self, method, path, body, **kwargs):
                calls.append((path, body, kwargs))
                if path.endswith("/update"):
                    return {**{key: value for key, value in body.items() if key != "expected_revision"}, "revision": 1}
                if path.endswith("/close"):
                    return {**copy.deepcopy(group), "status": "closed", "revision": body["expected_revision"] + 1}
                return copy.deepcopy(group)

        plan = {**self.plan, "close": {"expected_revision": 2, "idempotency_key": "close-key"}}
        evidence = run_identity(Client(), plan, evidence_kind="client_fixture")
        self.assertEqual("PASS", evidence["status"])
        self.assertEqual(("/open-platform/groups/close", {"group_binding_id": "opaque/group", "expected_revision": 2}), calls[-1][:2])
        self.assertEqual("close-key", calls[-1][2]["idempotency_key"])
        for value in (True, 0.5, "1"):
            plan["members"][0]["expected_revision"] = value
            with self.assertRaisesRegex(ValueError, "GROUP_EXACT_REVISIONS"):
                validate_plan(plan)


if __name__ == "__main__":
    unittest.main()
