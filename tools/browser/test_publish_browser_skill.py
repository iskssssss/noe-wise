"""验证固定上游包的 transport 适配，不连接平台或发布 Skill。"""
import importlib.util
import base64
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).with_name("publish_browser_skill.py")
SPEC = importlib.util.spec_from_file_location("publish_browser_skill", SCRIPT)
PUBLISH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISH)


class PublishBrowserSkillTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="browser-skill-transport-")
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name)
        self.package = self.source / "crates/bsk-cli/skill"
        self.package.mkdir(parents=True)
        (self.package / "references").mkdir()
        (self.package / "SKILL.md").write_text(
            "---\nname: browser-skill\ndescription: upstream fixture\n---\n"
            "# BrowserSkill\n\nOfficial page and tab semantics stay unchanged.\n"
            "[Tabs](references/tabs-and-profiles.md)\n"
            "[Files](references/files.md)\n", encoding="utf-8")
        (self.package / "references/tabs-and-profiles.md").write_text("Official tabs contract.\n", encoding="utf-8")
        (self.package / "references/files.md").write_text("Local filesystem transport.\n", encoding="utf-8")

    def prepare(self):
        with patch.object(PUBLISH.subprocess, "check_output", side_effect=[PUBLISH.PIN + "\n", ""]):
            return PUBLISH.prepare(self.source, "browser-navigation-v1")

    def test_generated_package_preserves_pinned_body_and_declares_real_readback_tool(self):
        files = self.prepare()
        skill = files["SKILL.md"].decode("utf-8")
        self.assertIn("Official page and tab semantics stay unchanged.", skill)
        self.assertIn("[Tabs](references/tabs-and-profiles.md)", skill)
        self.assertNotIn("references/files.md", skill)
        self.assertNotIn("references/files.md", files)
        self.assertEqual(b"Official tabs contract.\n", files["references/tabs-and-profiles.md"])
        allowed = next(line for line in skill.splitlines() if line.startswith("allowed-tools:"))
        self.assertIn("browser_tasks", allowed)
        self.assertIn("read_context_reference", allowed)
        self.assertIn(f'upstream-commit: "{PUBLISH.PIN}"', skill)
        PUBLISH.validate(files)

    def test_transport_retains_closed_commands_and_separates_task_navigation_and_unknown(self):
        transport = json.loads(self.prepare()["references/skillforge-transport.json"])
        source_contract = json.loads((PUBLISH.ROOT / "backend-java/skillforge-execution-contract/src/main/resources/browser-cli-contract.json").read_text(encoding="utf-8"))
        self.assertEqual(source_contract["operations"], transport["operations"])
        call = transport["browser_call"]
        self.assertIsInstance(call["parameters"]["command"]["parameters"], dict)
        for example in call["examples"]:
            self.assertEqual({"task", "command"}, example.keys())
            command = example["command"]
            self.assertEqual({"operation", "parameters"}, command.keys())
            self.assertIsInstance(command["parameters"], dict)
            fields = {field["name"] for field in source_contract["operations"][command["operation"]]["fields"]}
            self.assertLessEqual(command["parameters"].keys(), fields)
        self.assertEqual({}, transport["browser_tasks"]["parameters"])
        self.assertNotIn("tool_version", transport["browser_start"],
                         "offline Skill packages cannot assert the caller's frozen Tool version")
        self.assertIn("TOOL_INPUT", transport["browser_start"]["connection"])
        self.assertIn("same invocation", transport["browser_start"]["connection"])
        self.assertIn("structuredContent.items", transport["browser_tasks"]["result"])
        self.assertIn("personal task directory", transport["browser_tasks"]["result"])
        self.assertIn("Complete page results must be read from their original tool source", transport["browser_tasks"]["result"])
        self.assertIn("binding.session_id", transport["continuation"]["task"])
        self.assertIn("browser_tasks", transport["continuation"]["missing_task"])
        self.assertIn("source_event_id", transport["continuation"]["complete_source"])
        self.assertIn("resultRef", transport["continuation"]["complete_source"])
        self.assertIn("result.navigation", transport["navigation"]["after_action"])
        self.assertIn("not selected", transport["navigation"]["after_action"])
        self.assertIn("Do not reuse old-page element refs", transport["navigation"]["fresh_observation"])
        self.assertIn("never repeat the action", transport["navigation"]["observation_failure"])
        self.assertIn("tab-borrow", transport["navigation"]["authorization"])
        self.assertEqual("query receipts; never replay an unknown side effect", transport["unknown"])

    def test_unpinned_or_modified_upstream_is_rejected_before_package_generation(self):
        with patch.object(PUBLISH.subprocess, "check_output", return_value="f" * 40):
            with self.assertRaisesRegex(ValueError, "frozen BrowserSkill version"):
                PUBLISH.prepare(self.source, "browser-navigation-v1")
        with patch.object(PUBLISH.subprocess, "check_output", side_effect=[PUBLISH.PIN, " M crates/bsk-cli/skill/SKILL.md"]):
            with self.assertRaisesRegex(ValueError, "without local modifications"):
                PUBLISH.prepare(self.source, "browser-navigation-v1")

    def publication_fixture(self):
        content = {
            "version_key": "old-v1", "files": {"SKILL.md": base64.b64encode(b"old package").decode("ascii")},
            "model_invocable": True, "user_invocable": True, "activation_mode": "ON_DEMAND",
            "conflict_group": None, "required_capabilities": [],
        }
        active = dict(copy.deepcopy(content), id=901, definition_id=51, base_version_id=701,
                      revision=2, published_version_id=701)
        frozen = dict(copy.deepcopy(content), id=701, definition_id=51, base_version_id=701,
                      revision=0, published_version_id=701)
        definition = {"id": 51, "tenant_id": None, "capability_type": "SKILL", "revision": 9}
        return active, frozen, definition

    def test_unchanged_published_draft_can_be_saved_as_new_version_with_original_revision(self):
        active, frozen, definition = self.publication_fixture()
        original_active = copy.deepcopy(active)
        original_frozen = copy.deepcopy(frozen)
        files = {"SKILL.md": b"new package"}
        encoded = {name: base64.b64encode(data).decode("ascii") for name, data in files.items()}
        api = Mock()
        api.request.side_effect = [definition, active, frozen,
                                   [{"id": 701, "version_key": "old-v1", "status": "PUBLISHED"}],
                                   {"id": 901, "revision": 3}, {"valid": True}, definition,
                                   {"version_id": 702}, {"version_key": "new-v2", "files": encoded}]

        result = PUBLISH.publish(api, files, "new-v2", "51")

        self.assertEqual({"definition_id": "51", "version_id": 702, "status": "published"}, result)
        calls = api.request.call_args_list
        self.assertEqual(("GET", "/skill-drafts/version?versionId=701"), calls[2].args)
        mutations = [call for call in calls if call.args[0] != "GET"]
        self.assertEqual([("PUT", "/skill-drafts"), ("POST", "/skill-drafts/validate"),
                          ("POST", "/skill-drafts/publish")], [call.args[:2] for call in mutations])
        save = mutations[0]
        self.assertEqual({"revision": 2}, save.kwargs)
        self.assertEqual(901, save.args[2]["draft_id"])
        self.assertEqual("new-v2", save.args[2]["version_key"])
        self.assertEqual(encoded, save.args[2]["files"])
        self.assertEqual({"draft_id": 901, "revision": 3}, mutations[1].args[2])
        self.assertEqual(original_active, active)
        self.assertEqual(original_frozen, frozen)

    def test_unpublished_draft_is_protected_without_any_mutation(self):
        active, _, definition = self.publication_fixture()
        active["published_version_id"] = None
        api = Mock()
        api.request.side_effect = [definition, active]
        original = copy.deepcopy(active)

        with self.assertRaisesRegex(ValueError, "active editing draft"):
            PUBLISH.publish(api, {"SKILL.md": b"new package"}, "new-v2", "51")

        self.assertEqual(2, api.request.call_count)
        self.assertTrue(all(call.args[0] == "GET" for call in api.request.call_args_list))
        self.assertEqual(original, active)

    def test_published_pointer_does_not_authorize_overwriting_any_edited_content_field(self):
        changes = {
            "version_key": "user-edit", "files": {"SKILL.md": "dXNlciBlZGl0"},
            "model_invocable": False, "user_invocable": False, "activation_mode": "ALWAYS",
            "conflict_group": "user-group", "required_capabilities": [{"type": "SKILL", "id": "user-choice"}],
        }
        for field, value in changes.items():
            with self.subTest(field=field):
                active, frozen, definition = self.publication_fixture()
                active[field] = value
                original = copy.deepcopy(active)
                api = Mock()
                api.request.side_effect = [definition, active, frozen]

                with self.assertRaisesRegex(ValueError, "active editing draft"):
                    PUBLISH.publish(api, {"SKILL.md": b"new package"}, "new-v2", "51")

                self.assertEqual(3, api.request.call_count)
                self.assertTrue(all(call.args[0] == "GET" for call in api.request.call_args_list))
                self.assertEqual(original, active)
if __name__ == "__main__":
    unittest.main()
