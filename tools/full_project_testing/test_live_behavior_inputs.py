"""Manifest/serial-entry negative controls; only temporary files and fake providers."""
from __future__ import annotations
from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

repo = next(parent for parent in Path(__file__).resolve().parents
            if (parent / "tools/full_project_testing/run_suite.py").is_file())
sys.path.insert(0, str(repo / "tools/full_project_testing"))
sys.path.insert(0, str(Path(__file__).parent))
import live_behavior_inputs as inputs
import live_behavior as live


class InputManifestControls(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name)
        self.env_root = self.repo / ".runtime-logs/full-project-test-20261012/environment"
        self.env_root.mkdir(parents=True)
        self.tools = self.repo / "tools/full_project_testing"
        self.tools.mkdir(parents=True)
        self.chat = self.repo / "tools/chat_regression"; self.chat.mkdir()
        (self.tools / "live_behavior.py").write_text("import dependency\ndef f():\n    import nested\n", encoding="utf-8")
        (self.tools / "live_behavior_preflight.py").write_text("import dependency\n", encoding="utf-8")
        (self.tools / "dependency.py").write_text("VALUE=1\n", encoding="utf-8")
        (self.chat / "nested.py").write_text("VALUE=1\n", encoding="utf-8")
        (self.chat / "behavior_extra.py").write_text("VALUE=1\n", encoding="utf-8")
        (self.chat / "test_unrelated.py").write_text("VALUE=1\n", encoding="utf-8")
        (self.env_root / "isolated.env").write_text("SF_ADMIN_PASSWORD=SECRET\n", encoding="utf-8")
        (self.env_root / "provision.json").write_text("{}", encoding="utf-8")
        jars = {}
        for service in ("institution-service", "platform-service", "gateway"):
            jar = self.repo / (service + ".jar"); jar.write_bytes(b"actual deployed bytes")
            jars[service] = {"jar": str(jar)}
        (self.env_root / "environment.json").write_text(json.dumps({"services": jars}), encoding="utf-8")
        self.environment = {"SF_ADMIN_PASSWORD": "SECRET", "MODE": "test"}
        self.root = self.repo / "private-case"; self.root.mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def read(self):
        return inputs.capture_inputs(self.repo, "core", self.environment, runtime=False)

    def test_transitive_function_import_dynamic_family_and_no_unrelated_tests(self):
        value = self.read(); files = value["files_sha256"]
        self.assertIn("tools/full_project_testing/dependency.py", files)
        self.assertIn("tools/chat_regression/nested.py", files)
        self.assertIn("tools/chat_regression/behavior_extra.py", files)
        self.assertNotIn("tools/chat_regression/test_unrelated.py", files)
        self.assertNotIn("SECRET", json.dumps(value))
        self.assertEqual(set(value["external_inputs_sha256"]), {"deployed_jar:institution-service", "deployed_jar:platform-service", "deployed_jar:gateway"})

    def test_source_deployed_jar_environment_file_and_effective_environment_drift(self):
        baseline = self.read()
        for path in (self.tools / "dependency.py", self.chat / "nested.py", self.chat / "behavior_extra.py",
                     self.env_root / "isolated.env", self.repo / "institution-service.jar"):
            with self.subTest(path=path.name):
                old = path.read_bytes(); path.write_bytes(old + b"changed")
                changed = self.read()
                self.assertNotEqual(changed["fingerprint"], baseline["fingerprint"])
                self.assertTrue(inputs.changed_inputs(baseline, changed)); path.write_bytes(old)
        self.environment["MODE"] = "changed"
        self.assertEqual(inputs.changed_inputs(baseline, self.read()), ["effective_environment_sha256"])

    def test_added_dynamic_source_and_deleted_required_input_detected(self):
        baseline = self.read()
        (self.chat / "behavior_new.py").write_text("NEW=1", encoding="utf-8")
        self.assertNotEqual(baseline["fingerprint"], self.read()["fingerprint"])
        (self.env_root / "provision.json").unlink()
        with self.assertRaisesRegex(ValueError, "private_execution_input_missing"):
            self.read()

    def test_real_before_after_file_and_complete_batch_input_binding(self):
        monitor = inputs.InputManifestMonitor(self.read, self.root, context={"reservation_id": "same-owner"})
        for name in ("plan.json", "private.json", "resources.json", "configuration.json"):
            (self.root / name).write_text("{}", encoding="utf-8")
        monitor.freeze_batch(); self.assertTrue(monitor.check())
        (self.root / "configuration.json").write_text('{"revision":2}', encoding="utf-8")
        self.assertFalse(monitor.check()); self.assertFalse(monitor.close())
        checks = json.loads((self.root / "input-manifest-checks.json").read_text(encoding="utf-8"))
        self.assertEqual(checks["status"], "INVALID_INPUT_DRIFT")
        self.assertIn("batch_input/configuration.json", str(checks))
        self.assertTrue((self.root / "input-manifest-start.json").is_file())
        self.assertTrue((self.root / "input-manifest-end.json").is_file())

    def test_end_rehash_detects_same_size_change_and_does_not_write_historical_files(self):
        monitor = inputs.InputManifestMonitor(self.read, self.root)
        (self.tools / "dependency.py").write_text("VALUE=2\n", encoding="utf-8")
        self.assertFalse(monitor.close())
        with self.assertRaises(FileExistsError):
            inputs.InputManifestMonitor(self.read, self.root)

    def test_unavailable_input_records_only_safe_failure_and_never_zero(self):
        monitor = inputs.InputManifestMonitor(self.read, self.root)
        monitor.read = Mock(side_effect=OSError("SECRET path and private credential"))
        self.assertFalse(monitor.check()); self.assertFalse(monitor.close())
        checks = json.loads((self.root / "input-manifest-checks.json").read_text(encoding="utf-8"))
        self.assertNotIn("SECRET", json.dumps(checks))
        self.assertEqual(json.loads((self.root / "input-manifest-end.json").read_text(encoding="utf-8"))["status"], "UNAVAILABLE")

    def test_product_source_helpers_and_actual_frontend_tree_are_bound(self):
        frontend = self.repo / "tools/frontend"; frontend.mkdir()
        (frontend / "check-product-sessions.mjs").write_text("import { f } from '../full_project_testing/helper.mjs';", encoding="utf-8")
        (self.tools / "helper.mjs").write_text("export function f() {}", encoding="utf-8")
        (self.tools / "live_behavior_frontend.mjs").write_text("// real vite entry", encoding="utf-8")
        for name in ("frontend-admin", "frontend-chat", "frontend-shared"):
            root = self.repo / name; (root / "src").mkdir(parents=True)
            (root / "package.json").write_text("{}", encoding="utf-8")
            (root / "src/View.vue").write_text("<template />", encoding="utf-8")
        (self.repo / "pnpm-lock.yaml").write_text("lockfileVersion:9", encoding="utf-8")
        dataset = self.repo / "explicit-product-dataset"; dataset.mkdir(parents=True)
        for name in inputs.PRODUCT_FILES:
            (dataset / name).write_text("{}", encoding="utf-8")
        dataset_patch = patch.object(inputs, "product_dataset", return_value=(dataset, {"fingerprint": "a" * 64}))
        dataset_patch.start()
        self.addCleanup(dataset_patch.stop)
        first = inputs.capture_inputs(self.repo, "product", self.environment, runtime=False)
        self.assertIn("tools/full_project_testing/helper.mjs", first["files_sha256"])
        self.assertIn("frontend-chat/src/View.vue", first["files_sha256"])
        self.assertIn("pnpm-lock.yaml", first["files_sha256"])
        self.assertIn("product_dataset:dataset.json", first["external_inputs_sha256"])
        (self.repo / "frontend-chat/src/View.vue").write_text("<template>changed</template>", encoding="utf-8")
        self.assertNotEqual(first["fingerprint"], inputs.capture_inputs(self.repo, "product", self.environment, runtime=False)["fingerprint"])

    def test_ready_requires_exact_input_and_required_model_published_config(self):
        current = self.read()
        config = {"model": {"id": "10", "revision": 1}, "system_agents": [{"id": "20", "purpose": "task-context",
                  "published": {"system_prompt": "prompt", "model_config_id": "10"}}], "system_agent_models": {"10": {"revision": 1}}}
        ready = {"schema_version": 1, "run_id": "full-project-test-20261012", "status": "READY", "paid_dispatches": 0,
                 "input_fingerprints": {"core": current["fingerprint"], "product": current["fingerprint"]},
                 "configuration_binding": inputs.ready_binding(config), "actual_product_ui": {"status": "PASS"}}
        file = self.root / "ready.json"; file.write_text(json.dumps(ready), encoding="utf-8")
        inputs.verify_ready(file, "core", current, config)
        for changed in ({**current, "fingerprint": "old"},):
            with self.assertRaisesRegex(ValueError, "binding_drift"):
                inputs.verify_ready(file, "core", changed, config)
        for change in ("model", "system_agents", "system_agent_models"):
            modified = deepcopy(config); modified[change] = {"changed": True}
            with self.assertRaisesRegex(ValueError, "binding_drift"):
                inputs.verify_ready(file, "core", current, modified)
        ready["actual_product_ui"]["status"] = "NOT_READY"; file.write_text(json.dumps(ready), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "actual_product_bridge"):
            inputs.verify_ready(file, "product", current, config)

    def test_actual_node_ancestor_graph_and_exact_browser_revision_files_bound(self):
        modules = self.repo / "frontend-admin/node_modules"
        for name in ("vite", "vue", "@playwright/test"):
            root = modules / name; root.mkdir(parents=True)
            deps = {"playwright": "1"} if name == "@playwright/test" else {}
            (root / "package.json").write_text(json.dumps({"name": name, "version": "1", "dependencies": deps}), encoding="utf-8")
        playwright = modules / "@playwright/test/node_modules/playwright"; playwright.mkdir(parents=True)
        (playwright / "package.json").write_text(json.dumps({"name": "playwright", "version": "1", "dependencies": {"playwright-core": "1"}}), encoding="utf-8")
        core = playwright / "node_modules/playwright-core"; core.mkdir(parents=True)
        (core / "package.json").write_text(json.dumps({"name": "playwright-core", "version": "1"}), encoding="utf-8")
        (core / "core.js").write_text("actual runtime", encoding="utf-8")
        (core / "browsers.json").write_text(json.dumps({"browsers": [{"name": "chromium-headless-shell", "revision": "1"}]}), encoding="utf-8")
        cache = self.repo / "browser-cache"; current = cache / "chromium_headless_shell-1"; current.mkdir(parents=True)
        (current / "chrome-headless-shell.exe").write_bytes(b"binary")
        (current / "resources.pak").write_bytes(b"runtime resource")
        old = cache / "chromium_headless_shell-0"; old.mkdir()
        (old / "chrome-headless-shell.exe").write_bytes(b"unused older revision")
        env = {"PLAYWRIGHT_BROWSERS_PATH": str(cache)}
        first = inputs.installed_javascript_inputs(self.repo, env)
        self.assertIn("installed_package:playwright-core@1/core.js", first)
        self.assertIn("browser_runtime:chromium_headless_shell-1/resources.pak", first)
        self.assertNotIn("browser_runtime:chromium_headless_shell-0/chrome-headless-shell.exe", first)
        (current / "resources.pak").write_bytes(b"changed runtime resource")
        self.assertNotEqual(first, inputs.installed_javascript_inputs(self.repo, env))
        (core / "core.js").write_text("changed runtime", encoding="utf-8")
        self.assertNotEqual(first["installed_package:playwright-core@1/core.js"],
                            inputs.installed_javascript_inputs(self.repo, env)["installed_package:playwright-core@1/core.js"])

    def test_missing_actual_installed_dependency_refuses_manifest(self):
        with self.assertRaisesRegex(ValueError, "installed_frontend_dependency_missing"):
            inputs.installed_javascript_inputs(self.repo, {})

    def test_guard_source_drift_stops_before_provider_or_intent_and_unknown_remains_query_only(self):
        receipt = {"reservation_id": "same-reservation", "batch_id": "same-batch", "suite": "core",
                   "max_tokens": 100, "max_seconds": 600, "max_input_turns": 10}
        provider, api = Mock(), Mock()
        guard = live.DispatchGuard(receipt, provider, api, self.root)
        guard.input_monitor = Mock(); guard.input_monitor.check.return_value = False
        with patch.object(live.ledger, "check_reservation", return_value=receipt):
            with self.assertRaisesRegex(live.DispatchStopped, "execution_input_manifest_changed"):
                guard.before("POST", "/api/v1/capabilities/definitions", b"{}")
        provider.snapshot.assert_not_called(); api.request.assert_not_called()
        self.assertEqual(guard.intents, [])
        with patch.object(live.ledger, "check_reservation", side_effect=ValueError("unknown_query_only")):
            with self.assertRaisesRegex(ValueError, "unknown_query_only"):
                guard.before("POST", "/api/v1/chat", b"{}")
        self.assertEqual(guard.intents, [])

    def test_cli_core_selection_is_explicit_and_uses_remaining_same_reservation_caps(self):
        receipt = {"reservation_id": "same-reservation", "batch_id": "same-batch", "suite": "core",
                   "max_tokens": 2000000, "max_seconds": 7200, "max_input_turns": 200,
                   "remaining_limits": {"max_tokens": 100, "max_seconds": 59.9, "max_input_turns": 4}}
        refs = {"platform": "http://127.0.0.1:2/api/v1", "models": {"llm": {"id": "10"}}}
        proxy = Mock(base="http://127.0.0.1:1")
        args = live.cli_arguments("core", self.root, receipt, refs, "revision", proxy, cases=["B15", "B16", "B17", "B18"])
        self.assertEqual(args[args.index("--cases") + 1], "B15,B16,B17,B18")
        self.assertEqual(args[args.index("--max-turns") + 1], "4")
        self.assertEqual(args[args.index("--max-tokens") + 1], "100")
        self.assertEqual(args[args.index("--max-seconds") + 1], "59")
        self.assertEqual(args[args.index("--repetitions") + 1], "1")
        self.assertEqual(receipt["reservation_id"], "same-reservation")
        with self.assertRaisesRegex(ValueError, "only_for_core"):
            live.cli_arguments("stress", self.root, receipt, refs, "revision", proxy, cases=["B15"])
        receipt["remaining_limits"]["max_seconds"] = .5
        with self.assertRaisesRegex(ValueError, "no_remaining"):
            live.cli_arguments("core", self.root, receipt, refs, "revision", proxy)


if __name__ == "__main__":
    unittest.main()
