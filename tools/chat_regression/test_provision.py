"""provision 的离线契约检查，不调用真实 HTTP 服务。"""

import tempfile
import unittest
from pathlib import Path

from provision import ProvisionError, ProvisionUnknownOutcome, provision, _mcp_descriptor, _validate_profiles, _mount_from_response


class FakeApi:
    def __init__(self, fail_first_write=False):
        self.calls = []
        self.next_id = 100
        self.fail_first_write = fail_first_write

    def request(self, method, path, body=None, headers=None):
        self.calls.append((method, path, body, headers))
        if not path.startswith("/"):
            raise AssertionError("API path must start with /")
        if self.fail_first_write and method == "POST":
            self.fail_first_write = False
            raise TimeoutError("simulated timeout")
        if path == "/admin/models":
            return [{"id": "7", "kind": "LLM", "enabled": True}]
        if path == "/settings/available-models":
            return [{"id": "7", "kind": "LLM"}]
        if path == "/admin/system-agents":
            return [{"id": "task-context-agent", "purpose": "task-context",
                     "status": "published", "revision": 3, "published_version": 2,
                     "published": {"name": "Task context", "system_prompt": "preserve task"}}]
        if path == "/capabilities/platform-tools":
            return []
        if path.startswith("/connectors/instances/mcp-tools?"):
            return {"items": [{"name": "query_record", "description": "query",
                                "input_schema": {"type": "object"}}]}
        if path.startswith("/capabilities/definitions/detail?"):
            return {"revision": 1}
        if path == "/capabilities/definitions/versions/validate":
            return {"valid": True, "errors": []}
        if method == "POST" and path == "/chat/end-users":
            return {"id": self._id(), "display_name": body["display_name"]}
        if method == "POST" and path == "/connectors/instances":
            return {"id": self._id(), "instance_id": "inst-fixture"}
        if method == "POST" and path == "/capabilities/definitions":
            return {"id": self._id(), "revision": 1}
        if path == "/capabilities/definitions/skill-versions/import-directory":
            return {"id": self._id()}
        if path == "/capabilities/definitions/versions":
            return {"id": self._id()}
        if path == "/capabilities/definitions/versions/publish":
            return {"id": body["version_id"]}
        if path == "/capabilities/deployments":
            return {"deployment_key": body["deployment_key"], "revision": 1}
        if path == "/agents":
            return {"id": self._id()}
        if path == "/agents/capability-mounts":
            payload = body["payload"]
            for field in ("capability_definition_id", "capability_version_id"):
                if not isinstance(payload[field], str):
                    raise AssertionError(f"{field} must be a decimal string")
            return {"mounts": [{"mount_id": str(self._id()), "revision": 1,
                                "capability_type": payload["capability_type"],
                                "capability_definition_id": payload["capability_definition_id"],
                                "capability_version_id": payload["capability_version_id"]}]}
        if path == "/agents/publish":
            return {"agent_id": body["agent_id"]}
        raise AssertionError(f"unexpected request {method} {path}")

    def _id(self):
        self.next_id += 1
        return self.next_id


def profiles():
    return [{"key": f"p{index}", "prompt": f"prompt {index}",
             "skills": ([{"key": "collect", "instructions": "Collect precise facts."}]
                        if index == 0 else []),
             "tools": ["query_record"]}
            for index in range(10)]


class ProvisionTest(unittest.TestCase):
    def test_agents_publish_after_mounts_by_default(self):
        with tempfile.TemporaryDirectory() as directory:
            api = FakeApi()
            resources = provision(
                api, api, Path(directory), profiles()[:1], "ordered", "7",
                "http://fixture/mcp", user_count=1)
            mount_index = next(index for index, call in enumerate(api.calls)
                               if call[1] == "/agents/capability-mounts")
            publish_index = next(index for index, call in enumerate(api.calls)
                                 if call[1] == "/agents/publish")
            self.assertLess(mount_index, publish_index)
            self.assertTrue(resources["agents"]["p0"]["published"])

    def test_agents_can_remain_draft_for_suite_specific_mounts(self):
        with tempfile.TemporaryDirectory() as directory:
            api = FakeApi()
            resources = provision(
                api, api, Path(directory), profiles()[:1], "delayed", "7",
                "http://fixture/mcp", user_count=1, publish_agents=False)
            self.assertFalse(any(path == "/agents/publish" for _, path, *_ in api.calls))
            self.assertNotIn("published", resources["agents"]["p0"])

    def test_small_suite_freezes_user_count_and_resumes_without_duplicate_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            api = FakeApi()
            args = (api, api, Path(directory), profiles()[:1], "small", "7", "http://fixture/mcp")
            resources = provision(*args, user_count=3)
            self.assertEqual(3, resources["user_count"])
            self.assertEqual(3, len(resources["users"]))
            self.assertEqual(1, len(resources["agents"]))
            count = sum(method == "POST" for method, *_ in api.calls)
            provision(*args, user_count=3)
            self.assertEqual(count, sum(method == "POST" for method, *_ in api.calls))
            with self.assertRaisesRegex(ProvisionError, "user_count"):
                provision(*args, user_count=4)

    def test_toolless_judge_does_not_create_or_discover_tool_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            api = FakeApi()
            resources = provision(api, api, Path(directory), [{"key": "judge", "prompt": "Score evidence",
                                  "skills": [], "tools": []}], "judge", "7", "http://fixture/mcp", user_count=1)
            self.assertEqual({}, resources["connector"])
            self.assertEqual({}, resources["agents"]["judge"]["mounts"])
            self.assertFalse(any("/connectors/" in path or path == "/capabilities/platform-tools"
                                 for _, path, *_ in api.calls))

    def test_invalid_size_and_empty_profiles_are_rejected_before_api_calls(self):
        api = FakeApi()
        for count in (0, -1, True, 1.5):
            with self.assertRaises(ProvisionError):
                provision(api, api, Path("unused"), profiles(), "test", "7", "http://fixture/mcp", user_count=count)
        with self.assertRaises(ProvisionError):
            _validate_profiles([])
        self.assertEqual([], api.calls)

    def test_reference_reader_is_a_supported_builtin_profile_tool(self):
        specs = profiles()
        specs[1]["tools"] = ["query_record", "read_context_reference"]
        normalized = _validate_profiles(specs)
        self.assertEqual(["query_record", "read_context_reference"], normalized[1]["tools"])

    def test_write_fixture_does_not_claim_runtime_replay_support(self):
        descriptor = _mcp_descriptor({"name":"write_record", "input_schema":{"type":"object"}})
        self.assertEqual("NON_IDEMPOTENT_WRITE", descriptor["effect"])
        self.assertFalse(descriptor["idempotent"])

    def test_parallel_safety_comes_from_host_policy_not_remote_metadata(self):
        query = _mcp_descriptor({"name": "query_record", "input_schema": {"type": "object"},
                                 "parallel_safe": False, "annotations": {"parallel_safe": False}})
        write = _mcp_descriptor({"name": "write_record", "input_schema": {"type": "object"},
                                 "parallel_safe": True, "annotations": {"parallel_safe": True}})
        fault = _mcp_descriptor({"name": "fault_probe", "input_schema": {"type": "object"},
                                 "parallel_safe": True})
        self.assertTrue(query["parallel_safe"])
        self.assertFalse(write["parallel_safe"])
        self.assertFalse(fault["parallel_safe"])
        self.assertEqual({"parallel_safe": False}, query["annotations"])
        self.assertEqual({"parallel_safe": True}, write["annotations"])

    def test_multi_skill_switch_is_not_provisioned_as_mutual_exclusion(self):
        specs = profiles()
        specs[0]["skills"].append({"key":"summarize", "instructions":"Summarize confirmed facts."})
        with tempfile.TemporaryDirectory() as directory:
            api = FakeApi()
            provision(api, api, Path(directory), specs, "chatreg-test", "7", "http://fixture/mcp")
        imports = [body["payload"] for _,path,body,_ in api.calls if path == "/capabilities/definitions/skill-versions/import-directory"]
        self.assertEqual(2,len(imports))
        self.assertTrue(all(payload["conflict_group"] is None for payload in imports))

    def test_success_uses_snake_case_skill_and_mount_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            api = FakeApi()
            api.next_id = 9_007_199_254_740_992
            resources = provision(api, api, Path(directory), profiles(), "chatreg-test", "7", "http://fixture/mcp")
        self.assertEqual(50, len(resources["users"]))
        skill_request = next(body for _, path, body, _ in api.calls
                             if path == "/capabilities/definitions/skill-versions/import-directory")
        self.assertEqual("ON_DEMAND", skill_request["payload"]["activation_mode"])
        mount_request = next(body for _, path, body, _ in api.calls
                             if path == "/agents/capability-mounts" and body["payload"]["capability_type"] == "SKILL")
        self.assertIn("capability_definition_id", mount_request["payload"])
        self.assertEqual("ON_DEMAND", mount_request["payload"]["skill_config"]["activation_mode"])
        for _, path, body, _ in api.calls:
            if path == "/agents/capability-mounts":
                for field in ("capability_definition_id", "capability_version_id"):
                    self.assertRegex(body["payload"][field], r"^[1-9][0-9]*$")
                    self.assertGreater(int(body["payload"][field]), 9_007_199_254_740_992)

    def test_mount_response_requires_exact_decimal_string_identities(self):
        capability = {"definition_id": 9_007_199_254_740_993, "version_id": 9_223_372_036_854_775_807}
        mount = {"mount_id": "70", "revision": 1, "capability_type": "SKILL",
                 "capability_definition_id": "9007199254740993",
                 "capability_version_id": "9223372036854775807"}
        self.assertEqual({"mount_id": "70", "revision": 1}, _mount_from_response(
            {"mounts": [mount]}, capability, "SKILL", "fixture"))
        for field in ("capability_definition_id", "capability_version_id"):
            numeric = {**mount, field: int(mount[field])}
            with self.subTest(field=field), self.assertRaises(ProvisionUnknownOutcome):
                _mount_from_response({"mounts": [numeric]}, capability, "SKILL", "fixture")

    def test_unresolved_write_intent_blocks_second_prepare(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            api = FakeApi(fail_first_write=True)
            with self.assertRaises(ProvisionUnknownOutcome):
                provision(api, api, root, profiles(), "chatreg-test", "7", "http://fixture/mcp")
            writes = sum(method == "POST" for method, *_ in api.calls)
            with self.assertRaises(ProvisionUnknownOutcome):
                provision(api, api, root, profiles(), "chatreg-test", "7", "http://fixture/mcp")
            self.assertEqual(writes, sum(method == "POST" for method, *_ in api.calls))


if __name__ == "__main__":
    unittest.main()
