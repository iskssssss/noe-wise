"""产品行为资源准备的离线 API 契约测试。"""
from __future__ import annotations

import json
import io
from copy import deepcopy
from pathlib import Path
import sys
import tempfile
import unittest
from urllib.parse import parse_qs, urlsplit
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))

from behavior_dataset import CATEGORY_IDS, source_fixture
from behavior_main import main
from behavior_product_resources import _instruction, prepare_product, _multipart_request, _profiles
from provision import ProvisionError, ProvisionUnknownOutcome


def dataset() -> dict:
    selected = []
    required = {
        "D01": [], "D02": [], "D03": ["code_execute"], "D04": ["knowledge_search"],
        "D05": ["read_context_reference"], "D06": [], "D07": ["activate_skill"],
        "D08": ["code_execute", "read_context_reference"],
        "D09": ["code_execute", "list_artifacts"], "D10": ["workflow"],
        "D11": ["delegation"], "D12": ["code_execute", "cancel"],
    }
    refs = {"D03": ["orders"], "D04": ["rules"], "D05": ["handbook"],
            "D08": ["orders", "ledger"], "D09": ["rules"]}
    for index, category in enumerate(CATEGORY_IDS, 1):
        source = source_fixture(category, (index - 1) % 5 + 1)
        turns = ([f"执行 {category} 第一轮", f"执行 {category} 未来轮"]
                 if category in {"D02", "D09"} else [f"执行 {category} 任务"])
        selected.append({
            "candidate_id": f"{category}-candidate", "category": category,
            "variant": source["variant"], "goal": f"完成 {category} 合成评估",
            "user_turns": turns, "materials": source["materials"],
            "material_refs": refs.get(category, ["rules"]),
            "required_capabilities": required[category],
            "provenance": {"model_id": "model", "model_config_digest": "config",
                           "prompt_version": "product-generator-1", "call_identity": f"call-{category}",
                           "source_digest": f"source-{category}"},
        })
    return {"fingerprint": "dataset-fingerprint", "selected": selected}


def fake_base_provision(
    _api, _platform, root, profiles, batch, model_id, fixture_url, *,
    user_count, publish_agents=True,
):
    path = Path(root) / "resources.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    platform_tools = {}
    for index, name in enumerate(("code_execute", "list_artifacts", "read_context_reference"), 1):
        platform_tools[name] = {"definition_id": 10 + index, "version_id": 20 + index,
                                "published": True, "deployment_key": f"builtin-{name}",
                                "deployment_revision": 1}
    skills = {
        "d07-skills:collect": {"definition_id": 31, "version_id": 41, "published": True},
        "d07-skills:summarize": {"definition_id": 32, "version_id": 42, "published": True},
    }
    agents = {}
    for index, profile in enumerate(profiles, 1):
        mounts = {f"tool:platform_tools:{name}": {"mount_id": f"tool-{index}-{name}", "revision": 1}
                  for name in profile["tools"]}
        mounts.update({f"skill:{skill['key']}": {"mount_id": f"skill-{index}-{skill['key']}", "revision": 1}
                       for skill in profile["skills"]})
        agents[profile["key"]] = {"id": 100 + index, "profile": profile, "mounts": mounts}
        if publish_agents:
            agents[profile["key"]]["published"] = True
    return {
        "schema_version": 1, "batch": batch, "model_id": model_id, "fixture_url": fixture_url,
        "profile_signature": "fake", "user_count": user_count,
        "users": [{"key": f"user-{index:03d}", "id": 1000 + index,
                   "display_name": f"user {index}"} for index in range(1, user_count + 1)],
        "agents": agents,
        "capabilities": {"skills": skills, "mcp_tools": {}, "platform_tools": platform_tools},
        "connector": {}, "validations": {}, "pending_operations": [],
    }


class FakeApi:
    def __init__(self, fail_path=None, missing_definition=None, mismatched_output_budget=False):
        self.calls = []
        self.fail_path = fail_path
        self.missing_definition = missing_definition
        self.mismatched_output_budget = mismatched_output_budget
        self.next_mount = 0
        self.published_agent_ids = set()
        self.delegation_targets = []
        self.output_budgets = {}

    def request(self, method, path, body=None, headers=None):
        self.calls.append((method, path, body, headers))
        if path == "/settings/model-selections":
            return {"embedding_model_config_id": "900"}
        if method != "GET" and path == self.fail_path:
            raise TimeoutError("unknown write outcome")
        if path == "/knowledge/bases" and method == "POST":
            return {"id": 201, "name": body["name"]}
        if path == "/knowledge/bases/update":
            return {"id": 201}
        if path == "/knowledge/bases/revisions":
            return {"id": 211, "revision": 1, "status": "draft"}
        if path == "/knowledge/bases/revisions/ready":
            return {"id": 211, "revision": 1, "status": "ready"}
        if path == "/knowledge/bases/revisions/publish":
            return {"id": 211, "revision": 1, "status": "published", "capability_version_id": 221}
        if path == "/capabilities/definitions?capability_type=KNOWLEDGE_SOURCE&status=PUBLISHED":
            return [] if self.missing_definition == "KNOWLEDGE_SOURCE" else [{"id": 231}]
        if path == "/capabilities/definitions/versions?definitionId=231&status=PUBLISHED":
            return [{"id": 221}]
        if path == "/builder/workflows" and method == "POST":
            return {"workflow_id": body["workflow_id"], "status": "draft", "revision": 1,
                    "definition": body["definition"], "problems": []}
        if path == "/builder/workflows/draft/validate":
            return {"revision": 1, "problems": []}
        if path == "/builder/workflows/publish":
            return {"workflow_id": body["workflow_id"], "draft_revision": 1,
                    "definition_version_id": 301, "definition_version": 1,
                    "capability_version_id": 302}
        if path == "/builder/workflows/install":
            return {"status": "installed", "workflow_id": body["workflow_id"], "tenant_id": "1",
                    "instance_id": "installed-product-workflow", "installation_revision": 1}
        if path == "/builder/workflows/instances":
            return {"total": 1, "items": [{"id": 303, "instance_id": "installed-product-workflow",
                    "workflow_id": "batch-product-hitl", "definition_version_id": 301,
                    "installation_revision": 1, "capability_version_id": 302, "status": "published"}]}
        if path == "/capabilities/definitions?capability_type=WORKFLOW&status=PUBLISHED":
            return [] if self.missing_definition == "WORKFLOW" else [{"id": 304}]
        if path == "/capabilities/definitions/versions?definitionId=304&status=PUBLISHED":
            return [{"id": 302}]
        if path == "/agents/capability-mounts":
            self.next_mount += 1
            payload = body["payload"]
            for field in ("capability_definition_id", "capability_version_id"):
                if not isinstance(payload[field], str):
                    raise AssertionError(f"{field} must be a decimal string")
            return {"mounts": [{"mount_id": f"mount-{self.next_mount}", "revision": 1,
                "capability_type": payload["capability_type"],
                "capability_definition_id": payload["capability_definition_id"],
                "capability_version_id": payload["capability_version_id"]}]}
        if path == "/agents/publish":
            self.published_agent_ids.add(str(body["agent_id"]))
            return {"agent_id": body["agent_id"]}
        if path == "/agents/update":
            payload = body["payload"]
            if "execution_config" in payload:
                agent_id = str(body["agent_id"])
                if agent_id in self.published_agent_ids:
                    raise AssertionError("output budget must be configured before publish")
                self.output_budgets[agent_id] = payload["execution_config"]
                return {"id": "999999" if self.mismatched_output_budget else body["agent_id"],
                        "status": "draft"}
            targets = payload["delegation_config"]["target_agent_ids"]
            if not set(targets).issubset(self.published_agent_ids):
                raise AssertionError("delegation targets must be published first")
            if str(body["agent_id"]) in self.published_agent_ids:
                raise AssertionError("product parent must still be draft during delegation update")
            self.delegation_targets = targets
            return {"id": body["agent_id"], "status": "draft"}
        if path == "/teams":
            member_ids = {str(item["agent_id"]) for item in body["members"]}
            if not member_ids.issubset(self.published_agent_ids):
                raise AssertionError("team members must be published first")
            if set(self.delegation_targets) != member_ids - {str(body["primary_agent_id"])}:
                raise AssertionError("team members must match parent delegation targets")
            return {"id": "team-product", "name": body["name"], "status": "draft", "revision": 1,
                    "members": body["members"], "primary_agent_id": body["primary_agent_id"]}
        if path == "/teams/publish":
            parent = "111"
            return {"id": "team-product", "status": "published", "revision": 2,
                    "members": [{"agent_id": parent}, {"agent_id": "112"}, {"agent_id": "113"}],
                    "primary_agent_id": parent}
        raise AssertionError(f"unexpected request {method} {path}")

    def request_multipart(self, method, path, *, fields, filename, content, content_type):
        self.calls.append((method, path, {"fields": fields, "filename": filename,
                                         "content": content, "content_type": content_type}, None))
        if self.fail_path and path.startswith(self.fail_path):
            raise TimeoutError("unknown multipart outcome")
        return {"request_id": parse_qs(urlsplit(path).query)["request_id"][0], "status": "SUCCEEDED",
                "document": {"document_id": "202", "chunks": 1, "source_version": "source-v1"}}


class ProductResourceTest(unittest.TestCase):
    def test_rejects_legacy_reference_resources_before_api_access(self):
        with tempfile.TemporaryDirectory() as directory, patch("behavior_product_resources.provision") as base:
            root = Path(directory)
            (root / "resources.json").write_text(json.dumps({
                "cases": {"D08": {"reference_transport": {"source": "code_execute_result"}}},
                "pending_operations": []}), encoding="utf-8")
            api = FakeApi()
            with self.assertRaisesRegex(ProvisionError, "legacy product resources"):
                prepare_product(api, api, root, "batch", "7", dataset())
            base.assert_not_called()
            self.assertEqual([], api.calls)

    def test_applies_frozen_output_budget_before_publish_and_reentry_has_no_new_writes(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch("behavior_product_resources.provision", side_effect=fake_base_provision):
            api = FakeApi()
            root = Path(directory)
            resources = prepare_product(
                api, api, root, "batch", "7", dataset(), run_max_total_tokens=8192)

            mounts = [index for index, call in enumerate(api.calls)
                      if call[1] == "/agents/capability-mounts"]
            output_updates = [
                (index, call) for index, call in enumerate(api.calls)
                if call[1] == "/agents/update"
                and "execution_config" in call[2]["payload"]
            ]
            publishes = [index for index, call in enumerate(api.calls)
                         if call[1] == "/agents/publish"]
            self.assertEqual(len(_profiles()), len(output_updates))
            self.assertLess(max(mounts), min(index for index, _ in output_updates))
            self.assertLess(max(index for index, _ in output_updates), min(publishes))
            self.assertTrue(all(
                call[2]["payload"] == {"execution_config": {"max_total_tokens": 8192}}
                for _, call in output_updates))
            self.assertEqual(8192, resources["run_max_total_tokens"])
            self.assertTrue(all(
                agent["execution_config"] == {"max_total_tokens": 8192}
                for agent in resources["agents"].values()))

            writes = len([call for call in api.calls if call[0] != "GET"])
            resumed = prepare_product(
                api, api, root, "batch", "7", dataset(), run_max_total_tokens=8192)
            self.assertEqual(writes, len([call for call in api.calls if call[0] != "GET"]))
            self.assertEqual(8192, resumed["run_max_total_tokens"])

    def test_rejects_output_budget_drift_before_new_writes(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch("behavior_product_resources.provision", side_effect=fake_base_provision):
            api = FakeApi()
            root = Path(directory)
            prepare_product(api, api, root, "batch", "7", dataset(), run_max_total_tokens=8192)
            writes = len([call for call in api.calls if call[0] != "GET"])

            with self.assertRaisesRegex(ProvisionError, "run_max_total_tokens differs.*isolated batch"):
                prepare_product(api, api, root, "batch", "7", dataset(), run_max_total_tokens=4096)
            self.assertEqual(writes, len([call for call in api.calls if call[0] != "GET"]))

    def test_rejects_invalid_output_budget_before_provisioning(self):
        for value in (0, -1, 1.5, True, "8192"):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory, \
                    patch("behavior_product_resources.provision") as base:
                api = FakeApi()
                with self.assertRaisesRegex(ProvisionError, "positive integer"):
                    prepare_product(
                        api, api, Path(directory), "batch", "7", dataset(),
                        run_max_total_tokens=value)
                base.assert_not_called()
                self.assertEqual([], api.calls)

    def test_cli_total_budget_is_positive_and_product_local_only(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch("behavior_main.private_root", return_value=Path(directory)), \
                patch("behavior_product_runner.run_product", return_value=0) as run:
            valid = ["local-run", "--root", directory, "--suite", "product", "--run-max-total-tokens", "98304"]
            self.assertEqual(0, main(valid))
            self.assertEqual(98304, run.call_args.args[0].run_max_total_tokens)
            for suite, total in (("core", "98304"), ("product", "0"), ("product", "-1")):
                run.reset_mock()
                with patch("sys.stderr", io.StringIO()):
                    self.assertEqual(2, main(["local-run", "--root", directory, "--suite", suite,
                                           "--run-max-total-tokens", total]))
                run.assert_not_called()

    def test_rejects_published_agent_without_output_budget_record_before_writes(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch("behavior_product_resources.provision") as base:
            root = Path(directory)
            resources = fake_base_provision(
                None, None, root, _profiles(), "batch", "7", "fixture",
                user_count=12, publish_agents=False)
            resources["run_max_total_tokens"] = 8192
            resources["agents"]["d01-rules"]["published"] = True
            (root / "resources.json").write_text(
                json.dumps(resources, ensure_ascii=False), encoding="utf-8")
            api = FakeApi()

            with self.assertRaisesRegex(ProvisionError, "published agent d01-rules.*isolated batch"):
                prepare_product(
                    api, api, root, "batch", "7", dataset(), run_max_total_tokens=8192)
            base.assert_not_called()
            self.assertEqual([], api.calls)

    def test_output_budget_unknown_or_mismatched_result_is_journaled_and_not_retried(self):
        for api in (FakeApi(fail_path="/agents/update"), FakeApi(mismatched_output_budget=True)):
            with self.subTest(api=type(api).__name__), tempfile.TemporaryDirectory() as directory, \
                    patch("behavior_product_resources.provision", side_effect=fake_base_provision):
                root = Path(directory)
                with self.assertRaises(ProvisionUnknownOutcome):
                    prepare_product(
                        api, api, root, "batch", "7", dataset(), run_max_total_tokens=8192)
                calls = len(api.calls)
                checkpoint = json.loads((root / "resources.json").read_text(encoding="utf-8"))
                self.assertEqual(
                    "agents.d01-rules.run_max_total_tokens",
                    checkpoint["pending_operations"][0]["label"])

                with self.assertRaisesRegex(ProvisionUnknownOutcome, "unresolved product write intent"):
                    prepare_product(
                        api, api, root, "batch", "7", dataset(), run_max_total_tokens=8192)
                self.assertEqual(calls, len(api.calls))

    def test_d08_uses_session_attachments_without_inline_or_reference_transport(self):
        original = next(candidate for candidate in dataset()["selected"] if candidate["category"] == "D08")
        derived = deepcopy(original)
        derived["derivation"] = {"transform_version": "long-reconciliation-1"}

        for value in (_instruction(original), _instruction(derived)):
            self.assertIn("Session 的真实附件", value)
            self.assertIn("attachment_ids", value)
            self.assertIn("/input/data/<input_path>", value)
            self.assertNotIn("<material", value)
            self.assertNotIn("reference_id", value)
            self.assertNotIn("input_files 实际读取", value)

        selected = dataset()
        next(candidate for candidate in selected["selected"] if candidate["category"] == "D08").update(
            derivation={"transform_version": "long-reconciliation-1"})
        with tempfile.TemporaryDirectory() as directory, \
                patch("behavior_product_resources.provision", side_effect=fake_base_provision):
            resources = prepare_product(FakeApi(), FakeApi(), Path(directory), "batch", "7", selected)
        d08 = resources["cases"]["D08"]
        self.assertNotIn("reference_transport", d08)
        self.assertEqual({"source": "session_attachment",
                          "upload_endpoint": "/chat/sessions/attachments/upload",
                          "list_endpoint": "/chat/sessions/attachments",
                          "refs": ["orders", "ledger"]}, d08["attachment_transport"])
        self.assertEqual(5000, resources["cases"]["D05"]["reference_transport"]["padding_characters"])
        self.assertEqual(["D05"], resources["readiness"]["checks"]["reference_transport"]["cases"])
        self.assertEqual(["D08"], resources["readiness"]["checks"]["attachment_transport"]["cases"])

    def test_multipart_uses_query_identity_and_text_title_for_real_controller(self):
        from types import SimpleNamespace
        from unittest.mock import MagicMock
        api = SimpleNamespace(base="http://localhost:8000/api/v1", headers={}, timeout=30)
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b'{"code":"OK","data":{"document_id":1}}'
        with patch("behavior_product_resources.urllib.request.OpenerDirector.open", return_value=response) as opened:
            _multipart_request(api, "POST", "/knowledge/bases/documents/upload?kb_id=123&request_id=fixture", {
                "kb_id": 123, "title": "synthetic", "filename": "rules.txt", "content": "rules"})
        body = opened.call_args.args[0].data
        self.assertNotIn(b'name="kb_id"', body)
        self.assertIn(b'name="title"\r\nContent-Type: text/plain; charset=utf-8', body)

    def test_missing_embedding_stops_before_creating_any_resources(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch("behavior_product_resources.provision") as base:
            api = FakeApi()
            with patch.object(api, "request", return_value={"embedding_model_config_id": None}):
                with self.assertRaisesRegex(ProvisionError, "Embedding model selection"):
                    prepare_product(api, api, Path(directory), "batch", "7", dataset())
            base.assert_not_called()
            self.assertFalse((Path(directory) / "resources.json").exists())

    def test_prepares_real_resource_mapping_and_reuses_checkpoint_without_duplicate_writes(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch("behavior_product_resources.provision", side_effect=fake_base_provision) as base:
            api = FakeApi()
            resources = prepare_product(api, api, Path(directory), "batch", "7", dataset())

            self.assertFalse(base.call_args.kwargs["publish_agents"])
            product_mounts = [index for index, call in enumerate(api.calls)
                              if call[1] == "/agents/capability-mounts"]
            expected_identities = {"KNOWLEDGE_SOURCE": ("231", "221"), "WORKFLOW": ("304", "302")}
            for index in product_mounts:
                payload = api.calls[index][2]["payload"]
                self.assertEqual(expected_identities[payload["capability_type"]], (
                    payload["capability_definition_id"], payload["capability_version_id"]))
            publishes = [index for index, call in enumerate(api.calls)
                         if call[1] == "/agents/publish"]
            delegation_update = next(index for index, call in enumerate(api.calls)
                                     if call[1] == "/agents/update")
            parent_publish = next(index for index, call in enumerate(api.calls)
                                  if call[1] == "/agents/publish" and str(call[2]["agent_id"]) == "111")
            team_create = next(index for index, call in enumerate(api.calls)
                               if call[1] == "/teams")
            child_publishes = [index for index, call in enumerate(api.calls)
                               if call[1] == "/agents/publish"
                               and str(call[2]["agent_id"]) in {"112", "113"}]
            self.assertLess(max(product_mounts), min(publishes))
            self.assertLess(max(child_publishes), delegation_update)
            self.assertLess(delegation_update, parent_publish)
            self.assertLess(parent_publish, team_create)

            self.assertEqual(set(CATEGORY_IDS), set(resources["cases"]))
            self.assertEqual({}, resources["agents"]["d01-rules"]["mounts"])
            self.assertEqual(["handbook"], [item["ref"] for item in resources["cases"]["D05"]["material_bindings"]])
            self.assertEqual(["orders", "ledger"],
                             [item["ref"] for item in resources["cases"]["D08"]["material_bindings"]])
            self.assertIn("5000 个句点", resources["cases"]["D05"]["instruction"])
            self.assertIn("input_files", resources["cases"]["D08"]["instruction"])
            self.assertIn("<material ref=\"handbook\"", resources["cases"]["D05"]["instruction"])
            for category in set(CATEGORY_IDS) - {"D05", "D08"}:
                self.assertNotIn("<material", resources["cases"][category]["instruction"])
                self.assertFalse(any(turn in resources["cases"][category]["instruction"]
                                     for turn in resources["cases"][category]["task"]["user_turns"]))
            self.assertNotIn("未来轮", resources["cases"]["D02"]["instruction"])
            self.assertNotIn("未来轮", resources["cases"]["D09"]["instruction"])
            self.assertNotIn("预算", resources["cases"]["D04"]["instruction"])
            self.assertIn("knowledge:mount-1", resources["cases"]["D04"]["instruction"])
            self.assertEqual(4096, resources["cases"]["D05"]["reference_transport"]["automatic_threshold_characters"])
            self.assertTrue((Path(directory) / resources["cases"]["D05"]["material_bindings"][0]["path"]).is_file())
            self.assertEqual(2, len(resources["cases"]["D11"]["team"]["child_agent_ids"]))
            self.assertEqual("ready", resources["readiness"]["status"])
            self.assertEqual("/chat/runs/cancel", resources["capability_mapping"]["cancel"]["endpoint"])
            self.assertEqual("knowledge:mount-1", resources["cases"]["D04"]["knowledge"]["handle"])
            self.assertEqual("source-v1", resources["cases"]["D04"]["knowledge"]["documents"]["rules"]["source_version"])
            uploads = [call for call in api.calls if call[1].startswith("/knowledge/bases/documents/upload?")]
            self.assertEqual(1, len(uploads))
            self.assertIn("预算", uploads[0][2]["content"].decode("utf-8"))

            writes = len([call for call in api.calls if call[0] != "GET"])
            prepare_product(api, api, Path(directory), "batch", "7", dataset())
            self.assertEqual(writes, len([call for call in api.calls if call[0] != "GET"]))

    def test_rejects_published_parent_without_product_delegation_before_writes(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch("behavior_product_resources.provision", side_effect=fake_base_provision):
            root = Path(directory)
            resources = fake_base_provision(
                None, None, root, _profiles(),
                "batch", "7", "fixture", user_count=12, publish_agents=False)
            resources["agents"]["d04-knowledge"]["mounts"]["knowledge:product"] = {}
            resources["agents"]["d10-workflow"]["mounts"]["workflow:product-hitl"] = {}
            resources["agents"]["d11-parent"]["published"] = True
            (root / "resources.json").write_text(
                json.dumps(resources, ensure_ascii=False), encoding="utf-8")
            api = FakeApi()

            with self.assertRaisesRegex(ProvisionError, "d11-parent.*delegation targets"):
                prepare_product(api, api, root, "batch", "7", dataset())
            self.assertFalse(any(method != "GET" for method, *_ in api.calls))

    def test_unknown_write_is_journaled_and_never_blindly_retried(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch("behavior_product_resources.provision", side_effect=fake_base_provision):
            api = FakeApi(fail_path="/knowledge/bases")
            with self.assertRaises(ProvisionUnknownOutcome):
                prepare_product(api, api, Path(directory), "batch", "7", dataset())
            writes = len(api.calls)
            checkpoint = json.loads((Path(directory) / "resources.json").read_text(encoding="utf-8"))
            self.assertEqual("product knowledge base", checkpoint["pending_operations"][0]["label"])

            with self.assertRaisesRegex(ProvisionUnknownOutcome, "unresolved product write intent"):
                prepare_product(api, api, Path(directory), "batch", "7", dataset())
            self.assertEqual(writes, len(api.calls))

    def test_unknown_upload_preserves_request_id_and_never_reposts(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch("behavior_product_resources.provision", side_effect=fake_base_provision):
            api = FakeApi(fail_path="/knowledge/bases/documents/upload?")
            with self.assertRaises(ProvisionUnknownOutcome):
                prepare_product(api, api, Path(directory), "batch", "7", dataset())
            checkpoint = json.loads((Path(directory) / "resources.json").read_text(encoding="utf-8"))
            operation = checkpoint["pending_operations"][0]
            self.assertEqual("product knowledge document rules", operation["label"])
            self.assertTrue(parse_qs(urlsplit(operation["path"]).query)["request_id"][0])
            calls = len(api.calls)
            with self.assertRaises(ProvisionUnknownOutcome):
                prepare_product(api, api, Path(directory), "batch", "7", dataset())
            self.assertEqual(calls, len(api.calls))

    def test_upload_rejection_is_not_recorded_as_unknown(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch("behavior_product_resources.provision", side_effect=fake_base_provision):
            api = FakeApi()
            def rejected(_method, path, **_kwargs):
                return {"request_id": parse_qs(urlsplit(path).query)["request_id"][0],
                        "status": "REJECTED", "error_message": "文件不符合要求"}
            with patch.object(api, "request_multipart", side_effect=rejected):
                with self.assertRaisesRegex(ProvisionError, "was rejected"):
                    prepare_product(api, api, Path(directory), "batch", "7", dataset())
            checkpoint = json.loads((Path(directory) / "resources.json").read_text(encoding="utf-8"))
            self.assertEqual([], checkpoint["pending_operations"])

    def test_missing_published_capability_definition_fails_instead_of_fabricating_identity(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch("behavior_product_resources.provision", side_effect=fake_base_provision):
            with self.assertRaisesRegex(ProvisionError, "exactly one definition"):
                prepare_product(FakeApi(missing_definition="KNOWLEDGE_SOURCE"), FakeApi(),
                                Path(directory), "batch", "7", dataset())

    def test_rejects_dataset_that_does_not_cover_every_category_before_product_writes(self):
        value = dataset()
        value["selected"] = value["selected"][:-1]
        with tempfile.TemporaryDirectory() as directory, \
                patch("behavior_product_resources.provision", side_effect=fake_base_provision) as base:
            with self.assertRaisesRegex(ProvisionError, "lacks selected categories"):
                prepare_product(FakeApi(), FakeApi(), Path(directory), "batch", "7", value)
            base.assert_not_called()


if __name__ == "__main__":
    unittest.main()
