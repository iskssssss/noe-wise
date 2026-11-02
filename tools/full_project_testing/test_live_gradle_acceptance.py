"""Offline negative controls for live Gradle settlement; no Gradle/provider/real ledger."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import budget_ledger
import live_gradle_acceptance as live


class LiveGradleEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "lane"
        self.root.mkdir()
        self.environment = Path(self.temp.name) / "isolated.env"
        self.environment.write_text("", encoding="utf-8")
        environment_patch = patch.object(live, "ENV_FILE", self.environment)
        environment_patch.start()
        self.addCleanup(environment_patch.stop)
        self.ledger = Path(self.temp.name) / "live-budget-ledger.json"
        self.override = patch.object(budget_ledger, "LEDGER_PATH", self.ledger)
        self.override.start()
        self.receipt = budget_ledger.reserve(self.ledger, "conversation", 1, "synthetic-batch", self.root)
        self.execution = {"started_at": "2026-10-12T00:00:00+00:00", "finished_at": "2026-10-12T00:00:10+00:00",
                          "elapsed_seconds": 10.0, "return_code": 0}
        self.owner = {"schema_version": 1, "run_id": budget_ledger.RUN_ID, "suite": "conversation", "round": 1,
                      "reservation_id": self.receipt["reservation_id"], "batch_id": self.receipt["batch_id"]}
        self.document = {**self.owner, "caps": budget_ledger.LIMITS["conversation"], "collection_complete": True,
            "source": "owned_testcontainer_jdbc", "captured_at": "2026-10-12T00:00:09+00:00", "acceptance_outcome": "PASS",
            "attempts": [{"attempt_id": "attempt-1", "invocation_id": "invocation-1", "operation_type": "model.chat",
                          "status": "SUCCEEDED", "provider": "synthetic", "model": "synthetic-model"}],
            "provider_records": [self.usage("1", "1", "5", "3"), self.usage("2", "2", "10", "4")]}
        self.events = [self.event(1, "reserved", reserved_input_tokens=100, reserved_output_tokens=100),
                       self.event(2, "sent"), self.event(3, "usage_report", revision=1),
                       self.event(4, "usage_report", revision=2), self.event(5, "succeeded")]
        (self.root / "junit").mkdir()
        (self.root / "junit/TEST-synthetic.xml").write_text('<testsuite><testcase name="synthetic"/></testsuite>', encoding="utf-8")

    def tearDown(self):
        self.override.stop()
        self.temp.cleanup()

    def usage(self, identity, revision, prompt, completion):
        return {"id": identity, "event_id": "event-" + identity, "attempt_id": "attempt-1", "usage_event_kind": "usage_report",
                "report_revision": revision, "provider": "synthetic", "model": "synthetic-model",
                "prompt_tokens": prompt, "completion_tokens": completion, "usage_source": "PROVIDER_REPORTED"}

    def event(self, sequence, event, **fields):
        return {**self.owner, "sequence": sequence, "time": "2026-10-12T00:00:05+00:00", "attempt_id": "attempt-1",
                "run_id_owned": "run-synthetic", "event": event, **fields}

    def write(self):
        (self.root / "attempt-evidence.json").write_text(json.dumps(self.document), encoding="utf-8")
        (self.root / "attempts.jsonl").write_text("".join(json.dumps(e) + "\n" for e in self.events), encoding="utf-8")

    def parse(self):
        self.write()
        return live.standard_measurement(self.root, self.receipt, self.execution)

    def test_latest_cumulative_usage_is_not_summed(self):
        measured = self.parse()
        self.assertTrue(measured["usage_known"])
        self.assertEqual(1, measured["observed_attempts"])
        self.assertEqual(14, measured["observed_tokens"])

    def test_cache_and_reasoning_subsets_do_not_add_to_actual_tokens(self):
        self.document["provider_records"][-1].update(cache_read_tokens="9", reasoning_tokens="3")
        self.assertEqual(14, self.parse()["observed_tokens"])

    def test_partial_usage_does_not_become_complete_zero(self):
        for row in self.document["provider_records"]:
            row["completion_tokens"] = None
        measured = self.parse()
        self.assertFalse(measured["usage_known"])
        self.assertEqual(10, measured["observed_tokens"])

    def test_unresolved_is_unknown_even_when_a_partial_terminal_usage_exists(self):
        self.events[-1]["event"] = "unresolved"
        self.document["attempts"][0]["status"] = "UNRESOLVED"
        self.assertFalse(self.parse()["usage_known"])

    def test_two_attempt_revisions_count_as_one_send(self):
        self.assertEqual(1, self.parse()["observed_attempts"])

    def test_duplicate_provider_attempt_rejects(self):
        self.document["attempts"].append(dict(self.document["attempts"][0]))
        with self.assertRaisesRegex(live.EvidenceError, "duplicate_provider_attempt"):
            self.parse()

    def test_orphan_usage_rejects(self):
        self.document["provider_records"][0]["attempt_id"] = "foreign"
        with self.assertRaisesRegex(live.EvidenceError, "provider_attempt_usage_contract_invalid"):
            self.parse()

    def test_pre_dispatch_journal_identity_required(self):
        self.events[0]["reservation_id"] = "foreign"
        with self.assertRaisesRegex(live.EvidenceError, "artifact_reservation_owner_mismatch"):
            self.parse()

    def test_duplicate_send_and_sequence_gap_reject(self):
        self.events[2] = self.event(3, "sent")
        with self.assertRaisesRegex(live.EvidenceError, "attempt_sent_twice"):
            self.parse()
        self.events[2] = self.event(4, "usage_report", revision=1)
        with self.assertRaisesRegex(live.EvidenceError, "attempt_journal_sequence_gap"):
            self.parse()

    def test_stale_snapshot_rejects(self):
        self.document["captured_at"] = "2026-10-11T00:00:00+00:00"
        with self.assertRaisesRegex(live.EvidenceError, "stale_or_unowned_snapshot"):
            self.parse()

    def test_missing_snapshot_settles_unknown_and_blocks_all_next_lanes(self):
        result = live.settle_owned(self.receipt, self.execution, self.root)
        self.assertEqual("UNKNOWN", result["status"])
        self.assertNotIn("observed_tokens", result["actual"])
        with self.assertRaisesRegex(ValueError, "stops_new_dispatch"):
            budget_ledger.reserve(self.ledger, "vision", 1, "another", self.root / "next")

    def test_actual_excess_is_preserved_and_settled_exceeded(self):
        self.events[0]["reserved_input_tokens"] = 22000
        self.document["provider_records"] = [self.usage("3", "3", "21000", "1")]
        self.write()
        result = live.settle_owned(self.receipt, self.execution, self.root)
        self.assertEqual("EXCEEDED", result["status"])
        self.assertEqual(21001, result["actual"]["observed_tokens"])
        self.assertTrue(budget_ledger.load_ledger(self.ledger)["stop_new_dispatch"])

    def test_timeout_cannot_settle_known_success(self):
        self.write()
        self.execution["timed_out"] = True
        self.assertEqual("UNKNOWN", live.settle_owned(self.receipt, self.execution, self.root)["status"])

    def test_zero_send_requires_complete_collector_and_empty_journal(self):
        self.document.update(attempts=[], provider_records=[])
        self.events = []
        measured = self.parse()
        self.assertTrue(measured["usage_known"])
        self.assertEqual(0, measured["observed_attempts"])
        self.document["collection_complete"] = False
        with self.assertRaisesRegex(live.EvidenceError, "attempt_collection_incomplete"):
            self.parse()

    def test_default_ready_planner_never_launches_any_command(self):
        with patch.object(live.subprocess, "Popen", side_effect=AssertionError("no dispatch")):
            value = live.prepare_ready()
        self.assertFalse(value["gradle_launched"])
        self.assertEqual(0, value["provider_calls_sent"])
        self.assertEqual(10, len(value["suites"]))

    def test_provider_commands_select_only_the_exact_adapter_class(self):
        for suite in ("provider-smoke", "provider-cache"):
            with self.subTest(suite=suite):
                command = live.gradle_command(suite)
                self.assertEqual(":agent-runtime-adapter-model:test", command[-3])
                self.assertEqual("--tests", command[-2])
                self.assertEqual(live.SUITES[suite]["test_class"], command[-1])
                self.assertNotIn(":skillforge-institution-service:test", command)
                self.assertEqual(live.REPO / live.PROVIDER_ROOT / "build/test-results/test",
                                 live.selected_junit_root(suite))

    def test_provider_environment_uses_true_gate_and_disables_other_paid_lanes(self):
        supplied = {spec["gate"]: "true" for spec in live.SUITES.values()}
        supplied.update(SKILLFORGE_LIVE_BAILIAN="true", SKILLFORGE_LIVE_MEMORY_ACCEPTANCE="1",
                        LIVE_KNOWLEDGE_ACCEPTANCE="1")
        with patch.object(live, "load_environment", return_value=supplied):
            for suite in ("provider-smoke", "provider-cache"):
                with self.subTest(suite=suite):
                    environment = live.lane_environment(suite, 2, self.root)
                    for gate, value in live.SUITES[suite]["gates"].items():
                        self.assertEqual(value, environment[gate])
                    for gate in {spec["gate"] for spec in live.SUITES.values()} - live.SUITES[suite]["gates"].keys():
                        self.assertNotIn(gate, environment)
                    self.assertNotIn("SKILLFORGE_LIVE_MEMORY_ACCEPTANCE", environment)
                    self.assertNotIn("LIVE_KNOWLEDGE_ACCEPTANCE", environment)
                    self.assertEqual("2", environment["LIVE_GRADLE_ROUND"])
                    self.assertEqual(str(live.suite_caps(suite)["max_attempts"]), environment["LIVE_GRADLE_MAX_ATTEMPTS"])
                    if suite == "provider-cache":
                        self.assertEqual(str(self.root.resolve()), environment["SKILLFORGE_PROMPT_CACHE_EVIDENCE_DIR"])

    def test_provider_readiness_requires_its_own_wire_meter(self):
        suite = "provider-smoke"
        environment = {name: "synthetic" for name in live.SUITES[suite]["required"]}
        value = live.preflight_suite(suite, environment, {live.HELPER: "institution-helper-present"})
        self.assertEqual("BLOCKED", value["status"])
        self.assertIn("standard_attempt_meter_not_connected", value["issues"])
        self.assertFalse(any(issue.startswith("missing_env:SECRET_KEY") or "DATASOURCE" in issue
                             for issue in value["issues"]))
        self.assertEqual(live.SUITES[suite]["test_class"], value["test_class"])

    def test_unappended_provider_scope_cannot_become_ready(self):
        suite = "provider-smoke"
        limits = {key: value for key, value in budget_ledger.LIMITS.items() if key != suite}
        environment = {name: "synthetic" for name in live.SUITES[suite]["required"]}
        with patch.object(budget_ledger, "LIMITS", limits):
            value = live.preflight_suite(suite, environment, {})
        self.assertIn("suite_not_yet_appended_to_frozen_ledger", value["issues"])
        self.assertEqual("BLOCKED", value["status"])
        self.assertEqual({"max_attempts": 4, "max_tokens": 40000, "max_seconds": 600}, value["caps"])

    def test_provider_cache_credential_precheck_is_readonly_and_sanitized(self):
        suite = "provider-cache"
        credentials = Path(self.temp.name) / "synthetic-credentials.json"
        credentials.write_text(json.dumps({"deepseek": {"api_key": "synthetic-secret"},
                                           "bailian": {"api_key": "synthetic-secret"}}), encoding="utf-8")
        before = credentials.read_bytes()
        value = live.preflight_suite(suite, {"SKILLFORGE_PROMPT_CACHE_CREDENTIALS_FILE": str(credentials)}, {})
        self.assertNotIn("provider_cache_credentials_readonly_precheck_failed", value["issues"])
        self.assertEqual(before, credentials.read_bytes())
        self.assertNotIn("synthetic-secret", json.dumps(value))
        credentials.write_text('{"deepseek":{"api_key":"synthetic-secret"}}', encoding="utf-8")
        value = live.preflight_suite(suite, {"SKILLFORGE_PROMPT_CACHE_CREDENTIALS_FILE": str(credentials)}, {})
        self.assertIn("provider_cache_credentials_readonly_precheck_failed", value["issues"])
        self.assertNotIn("synthetic-secret", json.dumps(value))

    def test_meter_name_in_comments_or_stub_helper_is_not_a_consumer_gate(self):
        synthetic_repo = Path(self.temp.name) / "source"
        helper = synthetic_repo / live.PROVIDER_HELPER
        consumer = synthetic_repo / live.SUITES["provider-smoke"]["sources"][0]
        helper.parent.mkdir(parents=True)
        consumer.parent.mkdir(parents=True, exist_ok=True)
        helper.write_text('class LiveProviderAttemptMeter { /* no actual gate */ }', encoding="utf-8")
        consumer.write_text('// LiveProviderAttemptMeter.prepare(\n// LiveProviderAttemptMeter.client(\n', encoding="utf-8")
        with patch.object(live, "REPO", synthetic_repo):
            issues = live.instrumentation_issues("provider-smoke", {live.PROVIDER_HELPER: "synthetic-digest"})
        self.assertIn("standard_meter_persistence_or_send_gate_incomplete", issues)
        self.assertIn("standard_meter_consumer_hooks_incomplete", issues)

    def test_java_comment_filter_keeps_sql_and_literal_comment_text(self):
        path = Path(self.temp.name) / "Fixture.java"
        path.write_text('String sql="SELECT /* inside literal */ 1"; // outside\n/* outside */ class Gate {}', encoding="utf-8")
        code = live.java_code(path)
        self.assertIn("/* inside literal */", code)
        self.assertNotIn("outside", code)
        self.assertIn("class Gate", code)

    def test_private_credential_file_replacement_changes_ready_fingerprint_without_exposing_values(self):
        path = Path(self.temp.name) / "private.json"
        path.write_text('{"api_key":"synthetic-first-secret"}', encoding="utf-8")
        environment = {"SKILLFORGE_PROMPT_CACHE_CREDENTIALS_FILE": str(path)}
        before = live.private_input_manifest(environment)
        path.write_text('{"api_key":"synthetic-second-secret"}', encoding="utf-8")
        after = live.private_input_manifest(environment)
        self.assertNotEqual(before, after)
        self.assertNotIn("secret", json.dumps(before))
        self.assertNotIn("private.json", json.dumps(before))

    def test_provider_wire_snapshot_cannot_be_substituted_with_host_snapshot(self):
        suite = "provider-smoke"
        caps = live.SUITES[suite]["planned_caps"]
        self.receipt["suite"] = suite
        self.owner["suite"] = suite
        self.document.update(suite=suite, caps=caps, source="provider_wire_audit")
        for event in self.events:
            event["suite"] = suite
        with patch.dict(budget_ledger.LIMITS, {suite: caps}):
            self.assertEqual(14, self.parse()["observed_tokens"])
            self.document["source"] = "owned_testcontainer_jdbc"
            with self.assertRaisesRegex(live.EvidenceError, "actual_provider_source_required"):
                self.parse()


class LiveGradleSourceBindingTests(unittest.TestCase):
    """Synthetic source trees only: drift must reject before ledger admission or send."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name) / "repo"
        self.repo.mkdir()
        self.environment = self.repo / "private.env"
        self.environment.write_text("SYNTHETIC=true\n", encoding="utf-8")
        self.repo_override = patch.object(live, "REPO", self.repo)
        self.env_override = patch.object(live, "ENV_FILE", self.environment)
        self.repo_override.start()
        self.env_override.start()

    def tearDown(self):
        self.env_override.stop()
        self.repo_override.stop()
        self.temp.cleanup()

    def source(self, relative, value="original"):
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding="utf-8")
        return path

    def ready(self):
        path = self.repo / "synthetic-ready.json"
        path.write_text(json.dumps({"run_id": budget_ledger.RUN_ID,
            "source_files": live.source_manifest(), "direct_source_files": live.direct_source_manifest(),
            "environment_sha256": live.digest(self.environment), "private_input_sha256": {},
            "suites": [{"suite": "conversation", "status": "READY"}]}), encoding="utf-8")
        return path

    def assert_old_ready_rejected(self, ready):
        with patch.object(budget_ledger, "reserve", side_effect=AssertionError("no ledger admission")), \
                patch.object(live.subprocess, "Popen", side_effect=AssertionError("no subprocess")):
            with self.assertRaisesRegex(live.EvidenceError, "ready_input_fingerprint_drift"):
                live.dispatch("conversation", 1, ready)

    def test_unlisted_consumed_java_resource_migration_and_build_inputs_bind_ready(self):
        inputs = ("backend-java/another-module/src/main/java/example/Permission.java",
                  "backend-java/another-module/src/test/java/example/SharedFixture.java",
                  "backend-java/another-module/src/main/resources/application.yml",
                  "backend-java/another-module/src/main/resources/db/migration/V999__fixture.sql",
                  "backend-java/another-module/build.gradle.kts",
                  "backend-java/gradle/libs.versions.toml",
                  "backend-java/buildSrc/src/main/kotlin/BuildPolicy.kt")
        for relative in inputs:
            self.source(relative)
        before = live.source_manifest()
        for relative in inputs:
            with self.subTest(relative=relative):
                self.assertIn(relative, before)
                self.assertNotIn(relative, live.direct_source_manifest())
                ready = self.ready()
                path = self.source(relative, "changed")
                self.assertNotEqual(before[relative], live.source_manifest()[relative])
                self.assert_old_ready_rejected(ready)
                path.write_text("original", encoding="utf-8")

    def test_consumed_source_addition_and_deletion_invalidate_ready(self):
        relative = "backend-java/new-module/src/main/java/example/Added.java"
        ready = self.ready()
        path = self.source(relative)
        self.assert_old_ready_rejected(ready)
        ready = self.ready()
        path.unlink()
        self.assert_old_ready_rejected(ready)

    def test_runner_force_and_isolation_entrypoints_bind_ready(self):
        inputs = ("tools/full_project_testing/run_suite.py", "tools/full_project_testing/force-tests.gradle",
                  "tools/full_project_testing/isolate-integration-tests.gradle",
                  "tools/full_project_testing/isolated_environment.py",
                  "tools/full_project_testing/connect_services.py",
                  "tools/full_project_testing/frozen_sandbox_endpoint.py",
                  "backend-java/gradlew.bat", "backend-java/gradle/wrapper/gradle-wrapper.jar")
        for relative in inputs:
            with self.subTest(relative=relative):
                self.source(relative)
                ready = self.ready()
                self.assertIsNotNone(live.direct_source_manifest()[relative])
                self.source(relative, "changed")
                self.assert_old_ready_rejected(ready)

    def test_generated_output_gradle_caches_and_runtime_logs_do_not_change_binding(self):
        self.source("backend-java/module/src/main/java/example/Permission.java")
        before = live.source_manifest()
        ignored = ("backend-java/module/build/src/Generated.java",
                   "backend-java/.gradle/src/Cached.java", "backend-java/.gradle-ci/gradle/cache.jar",
                   "backend-java/.gradle-home/src/Cached.java", "backend-java/module/out/src/Output.java",
                   "backend-java/module/target/src/Output.java", "backend-java/.runtime-source.log")
        for relative in ignored:
            self.source(relative, "generated")
        self.assertEqual(before, live.source_manifest())

    def test_resource_fixtures_with_log_extensions_are_consumed_inputs(self):
        relative = "backend-java/module/src/test/resources/provider-transcript.log"
        self.source(relative)
        self.assertIn(relative, live.source_manifest())
        ready = self.ready()
        self.source(relative, "changed-fixture")
        self.assert_old_ready_rejected(ready)

    def test_direct_sources_remain_explicit_subset_of_complete_manifest(self):
        selected = live.SUITES["conversation"]["sources"][0]
        self.source(selected, "selected-test")
        self.source("backend-java/module/src/main/resources/db/migration/V999__fixture.sql")
        direct, complete = live.direct_source_manifest(), live.source_manifest()
        self.assertEqual(set(live.COMMON) | {p for suite in live.SUITES.values() for p in suite["sources"]},
                         set(direct))
        self.assertEqual(direct[selected], complete[selected])
        self.assertLess(set(direct), set(complete))


if __name__ == "__main__":
    unittest.main()
