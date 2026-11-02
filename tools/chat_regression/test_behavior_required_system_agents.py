"""Required purpose/model consumer controls; no actual service or paid request."""
from copy import deepcopy
import json
import unittest
from urllib.parse import parse_qs, urlsplit
from behavior_contract import digest
import behavior_environment as candidate


def model(model_id="10"):
    return {"id": model_id, "name": "model-" + model_id, "model_id": "provider-" + model_id,
            "enabled": True, "kind": "LLM", "credential_configured": True, "revision": 1,
            "published_revision": 1, "temperature": 0, "max_output_tokens": 500,
            "timeout_seconds": 30, "reasoning": None}


def system(purpose, agent_id, *, model_id="10"):
    return {"id": agent_id, "agent_key": purpose, "purpose": purpose, "execution_mode": "MODEL_TASK",
            "revision": 3, "published_version": 1, "status": "published",
            "published": {"name": purpose, "description": "host purpose binding", "system_prompt": "prompt",
                          "model_config_id": model_id, "timeout_seconds": 30, "max_steps": 1, "mounts": []},
            "draft": {"system_prompt": "unpublished draft"}, "updated_at": "unrelated clock"}


class Api:
    def __init__(self, agents):
        self.agents = agents
        self.calls = []

    def request(self, method, path):
        self.calls.append((method, path))
        assert method == "GET" and urlsplit(path).path == "/agents/detail"
        agent_id = parse_qs(urlsplit(path).query)["agentId"][0]
        return deepcopy(self.agents[agent_id])


class Platform:
    def __init__(self, systems, models):
        self.systems, self.models, self.calls = systems, models, []

    def request(self, method, path):
        self.calls.append((method, path))
        assert method == "GET" and path in {"/admin/models", "/admin/system-agents"}
        return deepcopy(self.models if path == "/admin/models" else self.systems)


class RequiredSystemAgentControls(unittest.TestCase):
    def setUp(self):
        self.systems = [system(purpose, str(100 + i))
                        for i, purpose in enumerate(candidate.REQUIRED_SYSTEM_AGENT_PURPOSES)]
        self.models = [model()]
        self.agent = {"system_prompt": "business prompt", "role_description": "role",
                      "execution_config": {"limit": 10}, "model_config_id": "10", "config": {},
                      "mount_set_generation": 2, "mounts": [{"id": "mount", "revision": 1}],
                      "status": "published", "delegation_config": {"enabled": False}}
        self.api = Api({"20": self.agent})
        self.platform = Platform(self.systems, self.models)
        self.resources = {"model_id": "10", "agents": {"business": {"id": "20"}}}

    def snapshot(self, module=candidate):
        return module.configuration(self.api, self.platform, self.resources)

    def test_only_get_and_one_model_directory_for_all_dependencies(self):
        value = self.snapshot()
        self.assertEqual(self.platform.calls, [("GET", "/admin/models"), ("GET", "/admin/system-agents")])
        self.assertEqual(len(self.api.calls), 1)
        self.assertEqual(set(value["system_agent_models"]), {"10"})
        self.assertEqual(value["system_agent_models"]["10"], value["model"])

    def test_unrelated_directory_lifecycle_and_unknown_purpose_do_not_change_digest(self):
        baseline = digest(self.snapshot())
        unrelated = system(None, "999")
        for purpose in (None, "unrelated-maintenance"):
            for status in ("draft", "published", "disabled", "archived"):
                with self.subTest(purpose=purpose, status=status):
                    self.platform.systems = deepcopy(self.systems) + [{**unrelated, "purpose": purpose, "status": status,
                        "revision": 47, "published_version": 12, "published": {"system_prompt": "arbitrary",
                        "model_config_id": "unresolved-unused-model"}}]
                    self.assertEqual(digest(self.snapshot()), baseline)
        self.platform.systems = deepcopy(self.systems)
        self.assertEqual(digest(self.snapshot()), baseline)

    def test_directory_order_has_no_effect(self):
        baseline = digest(self.snapshot())
        self.platform.systems.reverse()
        self.platform.models.extend([model("unused")])
        self.platform.models.reverse()
        self.assertEqual(digest(self.snapshot()), baseline)

    def test_missing_required_binding_rejected(self):
        for missing in candidate.REQUIRED_SYSTEM_AGENT_PURPOSES:
            with self.subTest(missing=missing):
                self.platform.systems = [deepcopy(row) for row in self.systems if row["purpose"] != missing]
                with self.assertRaisesRegex(ValueError, "must_resolve_exactly_one"):
                    self.snapshot()

    def test_ambiguity_rejected_before_status_filtering(self):
        for duplicate_status in ("published", "draft", "disabled", "archived"):
            with self.subTest(status=duplicate_status):
                duplicate = deepcopy(self.systems[0]); duplicate.update(id="998", status=duplicate_status)
                self.platform.systems = deepcopy(self.systems) + [duplicate]
                with self.assertRaisesRegex(ValueError, "must_resolve_exactly_one"):
                    self.snapshot()

    def test_unpublished_required_binding_rejected(self):
        for purpose in candidate.REQUIRED_SYSTEM_AGENT_PURPOSES:
            for status in ("draft", "disabled", "archived", None):
                with self.subTest(purpose=purpose, status=status):
                    self.platform.systems = deepcopy(self.systems)
                    next(row for row in self.platform.systems if row["purpose"] == purpose)["status"] = status
                    with self.assertRaisesRegex(ValueError, "not_published"):
                        self.snapshot()

    def test_published_configuration_and_binding_facts_required(self):
        for key, bad in (("published", None), ("published", []), ("id", ""), ("revision", 0),
                         ("revision", True), ("published_version", None), ("published_version", -1)):
            with self.subTest(key=key, bad=bad):
                self.platform.systems = deepcopy(self.systems)
                self.platform.systems[0][key] = bad
                with self.assertRaises(ValueError):
                    self.snapshot()
        self.platform.systems = deepcopy(self.systems)
        self.platform.systems[0]["published"].pop("model_config_id")
        with self.assertRaisesRegex(ValueError, "model_binding_missing"):
            self.snapshot()

    def test_binding_identity_revision_version_and_execution_mode_drift_detected(self):
        baseline = digest(self.snapshot())
        for key, value in (("id", "991"), ("revision", 4), ("published_version", 2),
                           ("agent_key", "replacement"), ("execution_mode", "AGENT_RUN")):
            with self.subTest(key=key):
                self.platform.systems = deepcopy(self.systems)
                self.platform.systems[0][key] = value
                self.assertNotEqual(digest(self.snapshot()), baseline)
        self.platform.systems = deepcopy(self.systems)
        self.platform.systems[0]["purpose"] = "replacement-purpose"
        with self.assertRaisesRegex(ValueError, "must_resolve_exactly_one"):
            self.snapshot()

    def test_entire_published_payload_is_frozen(self):
        baseline = digest(self.snapshot())
        changes = {"system_prompt": "changed prompt", "name": "renamed", "description": "changed",
                   "timeout_seconds": 31, "max_steps": 2, "mounts": [{"capability_definition_id": "mount-2"}],
                   "future_execution_contract": {"enabled": True}}
        for key, value in changes.items():
            with self.subTest(key=key):
                self.platform.systems = deepcopy(self.systems)
                self.platform.systems[0]["published"][key] = value
                self.assertNotEqual(digest(self.snapshot()), baseline)

    def test_referenced_system_model_binding_and_revision_drift_detected(self):
        baseline = digest(self.snapshot())
        self.platform.models = deepcopy(self.models) + [model("11")]
        self.platform.systems = deepcopy(self.systems)
        self.platform.systems[0]["published"]["model_config_id"] = "11"
        dependent_baseline = self.snapshot()
        self.assertNotEqual(digest(dependent_baseline), baseline)
        self.platform.models[1]["revision"] = 2
        changed = self.snapshot()
        self.assertEqual(changed["model"], dependent_baseline["model"])
        self.assertEqual(changed["system_agents"], dependent_baseline["system_agents"])
        self.assertNotEqual(digest(changed), digest(dependent_baseline))
        self.assertEqual(set(changed["system_agent_models"]), {"10", "11"})

    def test_unavailable_or_ambiguous_dependent_model_rejected(self):
        self.platform.systems = deepcopy(self.systems)
        self.platform.systems[0]["published"]["model_config_id"] = "11"
        for rows in ([model()], [model(), {**model("11"), "enabled": False}],
                     [model(), {**model("11"), "kind": "EMBEDDING"}],
                     [model(), {**model("11"), "credential_configured": False}],
                     [model(), model("11"), model("11")]):
            with self.subTest(rows=len(rows)):
                self.platform.models = rows
                with self.assertRaises(ValueError):
                    self.snapshot()

    def test_main_model_drift_and_every_existing_agent_field_still_detected(self):
        baseline = digest(self.snapshot())
        for key, value in (("revision", 2), ("temperature", 0.5), ("max_output_tokens", 400),
                           ("reasoning", {"mode": "on"}), ("published_revision", 2)):
            with self.subTest(model=key):
                self.platform.models = deepcopy(self.models)
                self.platform.models[0][key] = value
                self.assertNotEqual(digest(self.snapshot()), baseline)
        self.platform.models = deepcopy(self.models)
        for key in self.agent:
            with self.subTest(agent=key):
                self.api.agents["20"] = {**deepcopy(self.agent), key: {"changed": True}}
                self.assertNotEqual(digest(self.snapshot()), baseline)

    def test_malformed_directories_rejected_without_post(self):
        for rows in (None, {}, [None], ["invalid"]):
            with self.subTest(rows=rows):
                self.platform.systems = rows
                with self.assertRaisesRegex(ValueError, "directory_response_invalid"):
                    self.snapshot()
        self.platform.systems = deepcopy(self.systems)
        self.platform.models = {}
        with self.assertRaisesRegex(ValueError, "model_directory_response_invalid"):
            self.snapshot()
        self.assertTrue(all(method == "GET" for method, _ in self.api.calls + self.platform.calls))



    def test_monitor_detects_required_drift_and_accepts_unrelated_lifecycle(self):
        monitor = candidate.ConfigurationMonitor(self.snapshot, self.snapshot())
        self.platform.systems.append(system(None, "999"))
        self.assertTrue(monitor.check())
        self.platform.systems[0]["published"]["system_prompt"] = "different real prompt"
        self.assertFalse(monitor.check())
        self.assertEqual(monitor.reasons[0], {"code": "invalid_config_drift", "detail": "observed_configuration_changed"})
        self.assertNotIn("different real prompt", json.dumps(monitor.reasons))


if __name__ == "__main__":
    unittest.main()
