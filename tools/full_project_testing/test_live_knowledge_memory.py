"""Knowledge/Memory funded wrapper负控；全部离线合成回执，无Gradle/网络/费用。"""
from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch, MagicMock
import subprocess

sys.path.insert(0, str(Path(__file__).parent))
import budget_ledger
import live_knowledge_memory as live


class LiveKnowledgeMemoryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.ledger = self.root / "supervisor/ledger.json"
        self.live_root = self.root / "fixed-live"
        self.env_file = self.root / "owned.env"
        self.env_file.write_text("\n".join(("SPRING_DATASOURCE_URL=jdbc:postgresql://localhost/owned",
            "PLATFORM_DATASOURCE_USERNAME=source-reader", "PLATFORM_DATASOURCE_PASSWORD=private",
            "SECRET_KEY=private", "LIVE_MEMORY_SOURCE_MODEL_ID=123", "LIVE_MEMORY_SOURCE_MODEL_REVISION=1",
            "DASHSCOPE_API_KEY=private")), encoding="utf-8")
        for target, name, value in ((budget_ledger, "LEDGER_PATH", self.ledger), (live, "LIVE_ROOT", self.live_root), (live, "ENV_FILE", self.env_file)):
            binding = patch.object(target, name, value)
            binding.start()
            self.addCleanup(binding.stop)
        self.source, self.binding = live.environment_and_binding(self.env_file)
        live.bind_source(self.binding)
        self.execution = {"started_at": "2026-10-12T01:00:00+00:00", "finished_at": "2026-10-12T01:01:00+00:00",
                          "elapsed_seconds": 60.0, "return_code": 0, "timed_out": False}

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")

    def receipt(self, suite, number=1):
        root = self.live_root / f"round-{number}" / suite
        return budget_ledger.reserve(self.ledger, suite, number, f"synthetic-{suite}-{number}", root)

    def knowledge(self, receipt):
        root = Path(receipt["evidence_root"])
        root.mkdir(parents=True)
        self.write(root / "execution-started.json", {"started": "2026-10-12T01:00:01Z", "embedding_max_calls": 20,
                   "embedding_max_tokens": 50_000, "rerank_max_calls": 10, "rerank_max_billed_tokens": 50_000})
        events = []
        for i in range(11):
            kind = "embedding" if i < 10 else "rerank"
            attempt = f"attempt-{i}"
            provider = {"usage": {"total_tokens": 10}}
            if kind == "embedding":
                provider["usage"]["prompt_tokens"] = 10
                provider["data"] = [{"index": 0, "embedding": [0.1] * 1024}]
            else:
                provider["output"] = {"results": [{"index": i, "relevance_score": .9 - .1 * i} for i in range(3)]}
            path = root / f"response-{attempt}.json"
            self.write(path, provider)
            events.extend([
                {"event": "reserved", "attempt": attempt, "kind": kind, "reserved_tokens": 100, "time": "2026-10-12T01:00:02Z"},
                {"event": "response", "attempt": attempt, "kind": kind, "http_status": 200, "response_bytes": path.stat().st_size, "time": "2026-10-12T01:00:03Z"},
                {"event": "metering_receipt", "attempt": attempt, "kind": kind, "reserved_tokens": 100,
                 "payload_complete": True, "usage_complete": True, "usage_within_reservation": True, "complete_success": True,
                 "total_tokens": 10, **({"prompt_tokens": 10} if kind == "embedding" else {}), "time": "2026-10-12T01:00:04Z"}])
        self.events(root, events)
        self.write(root / "acceptance-success.json", {"status": "PASS", "attempts": 11, "rerank_first_index": 0})
        return root, events

    def events(self, root, events):
        (root / "external-attempts.jsonl").write_text("".join(json.dumps(e) + "\n" for e in events), encoding="utf-8")

    def memory(self, receipt):
        root = Path(receipt["evidence_root"])
        root.mkdir(parents=True)
        attempts, runtimes, wires = [], [], []
        for i, category in enumerate(("BUSINESS_MODEL", "AUXILIARY_MODEL")):
            attempts.append({"runId": f"run-{i}", "attemptId": f"attempt-{i}", "category": category,
                             "sent": True, "reservedTokens": 100, "reportedTokens": 10, "terminal": "SUCCEEDED"})
            runtimes.append({"sequence": i + 1, "runId": f"run-{i}", "attemptId": f"attempt-{i}", "tenantId": "1", "category": category,
                             "terminal": "SUCCEEDED", "usage": {"inputTokens": 7, "outputTokens": 3}})
            wires.append({"requestSequence": i + 1, "attempt": {"runId": f"run-{i}", "attemptId": f"attempt-{i}", "category": category},
                          "httpStatus": 200, "terminalStatus": "SUCCEEDED", "bodyCompleteMillis": 10.0,
                          "transportFailure": None, "observationFailure": None,
                          "rawUsage": {"usage.prompt_tokens": 7, "usage.completion_tokens": 3, "usage.total_tokens": 10}})
        suite = json.loads(live.SUITE_FILE.read_text(encoding="utf-8"))
        index = []
        for scenario in suite["scenarios"]:
            for repeat in (1, 2, 3):
                sample_id = f"{scenario['id']}-{repeat}"
                sample = {"schemaVersion": 1, "sampleId": sample_id, "scenario": scenario, "repeat": repeat,
                          "outcome": "PASS", "failures": [], "assertions": []}
                self.write(root / f"sample-{sample_id}.json", sample)
                index.append({"sampleId": sample_id, "path": f"sample-{sample_id}.json", "outcome": "PASS"})
        batch = {"schemaVersion": 1, "batchId": "native-memory-id", "suiteId": suite["suiteId"], "suiteHash": self.binding["suite_sha256"],
                 "plannedSamples": 72, "samples": index, "outcome": "COMPLETED", "stopCode": None,
                 "startedAt": "2026-10-12T01:00:01Z", "elapsedSeconds": 59,
                 "model": {"sourceId": "123", "sourceRevision": 1, "modelId": "deepseek-flash", "sourceReadOnly": True},
                 "budget": {"maximumAttempts": 1200, "maximumTokens": 1_000_000, "maximumSeconds": 7200,
                            "reservedSlots": 2, "sentAttempts": 2, "reportedTokens": 20, "chargedOrReservedTokens": 20,
                            "unknownUsageAttempts": 0, "stopCode": None, "attempts": attempts},
                 "runtimeCalls": runtimes, "wireCalls": wires}
        self.write(root / "batch.json", batch)
        return root, batch

    def test_knowledge_eleven_attempts_settle_exact_identity_with_hashes(self):
        receipt = self.receipt("knowledge")
        root, _ = self.knowledge(receipt)
        measured = live.parse_knowledge(root, receipt, self.execution, self.binding)
        self.assertTrue(measured["usage_known"])
        self.assertEqual((10, 1, 100, 10, 110), tuple(measured[k] for k in (
            "observed_embedding_calls", "observed_rerank_calls", "observed_embedding_tokens", "observed_rerank_tokens", "observed_tokens")))
        self.assertEqual("PASS", measured["acceptance_outcome"])
        result, _ = live.collect_and_settle(receipt, self.execution, self.binding, root.with_name("knowledge-control"))
        self.assertEqual("SETTLED", result["status"])
        self.assertEqual(14, len(measured["artifacts"]))
        self.assertFalse(json.loads(self.ledger.read_text())["stop_new_dispatch"])

    def test_knowledge_missing_meter_keeps_partial_usage_and_stops_global_dispatch(self):
        receipt = self.receipt("knowledge")
        root, events = self.knowledge(receipt)
        self.events(root, events[:-1])
        result, measured = live.collect_and_settle(receipt, self.execution, self.binding, root.with_name("knowledge-control"))
        self.assertEqual("UNKNOWN", result["status"])
        self.assertEqual(100, result["actual"]["observed_tokens"])
        self.assertNotIn("observed_rerank_tokens", result["actual"])
        self.assertTrue(measured["token_metrics_are_lower_bounds"])
        with self.assertRaisesRegex(ValueError, "stops_new_dispatch"):
            self.receipt("memory")

    def test_knowledge_known_usage_payload_failure_is_settled_and_result_stops(self):
        receipt = self.receipt("knowledge")
        root, events = self.knowledge(receipt)
        events[-1].update(payload_complete=False, complete_success=False)
        self.events(root, events)
        self.write(root / "external-failure.json", {"attempt": "attempt-10", "outcome": "incomplete_receipt", "time": "2026-10-12T01:00:04Z"})
        (root / "acceptance-success.json").unlink()
        result, measured = live.collect_and_settle(receipt, self.execution, self.binding, root.with_name("knowledge-control"))
        self.assertEqual("SETTLED", result["status"])
        self.assertEqual(110, result["actual"]["observed_tokens"])
        self.assertEqual("FAIL", measured["acceptance_outcome"])
        self.assertTrue(json.loads(self.ledger.read_text())["stop_new_dispatch"])

    def test_knowledge_non200_unknown_has_no_fabricated_zero_usage(self):
        receipt = self.receipt("knowledge")
        root, events = self.knowledge(receipt)
        events = events[:2]
        events[1]["http_status"] = 401
        self.events(root, events)
        (root / "acceptance-success.json").unlink()
        measured = live.parse_knowledge(root, receipt, self.execution, self.binding)
        self.assertFalse(measured["usage_known"])
        self.assertNotIn("observed_tokens", measured)
        self.assertNotIn("observed_embedding_tokens", measured)
        self.assertEqual(1, measured["observed_embedding_calls"])
        self.assertIsNone(measured["monetary_amount"])

    def test_knowledge_duplicate_orphan_truncation_and_conflicting_usage_reject(self):
        receipt = self.receipt("knowledge")
        root, original = self.knowledge(receipt)
        mutations = [original + [original[0]], original[1:], deepcopy(original), deepcopy(original)]
        mutations[2][-1]["total_tokens"] = 9
        mutations[3][-1]["kind"] = "embedding"
        for events in mutations:
            with self.subTest(events=len(events)):
                self.events(root, events)
                with self.assertRaises(live.EvidenceError):
                    live.parse_knowledge(root, receipt, self.execution, self.binding)
        self.events(root, original)
        path = root / "external-attempts.jsonl"
        path.write_bytes(path.read_bytes()[:-1])
        with self.assertRaisesRegex(live.EvidenceError, "truncated"):
            live.parse_knowledge(root, receipt, self.execution, self.binding)

    def test_knowledge_foreign_directory_cannot_borrow_reservation(self):
        receipt = self.receipt("knowledge")
        root, _ = self.knowledge(receipt)
        with self.assertRaisesRegex(live.EvidenceError, "root_mismatch"):
            live.parse_knowledge(root.with_name("another-directory"), receipt, self.execution, self.binding)

    def test_knowledge_timeout_forces_unknown_even_complete_provider_receipts(self):
        receipt = self.receipt("knowledge")
        root, _ = self.knowledge(receipt)
        result, _ = live.collect_and_settle(receipt, {**self.execution, "timed_out": True}, self.binding, root.with_name("knowledge-control"))
        self.assertEqual("UNKNOWN", result["status"])
        self.assertEqual(110, result["actual"]["observed_tokens"])
        self.assertTrue(json.loads(self.ledger.read_text())["stop_new_dispatch"])

    def test_knowledge_invalid_vector_cannot_use_success_flag(self):
        receipt = self.receipt("knowledge")
        root, events = self.knowledge(receipt)
        path = root / "response-attempt-0.json"
        provider = json.loads(path.read_text())
        provider["data"][0]["embedding"] = [True] * 1024
        self.write(path, provider)
        events[1]["response_bytes"] = path.stat().st_size
        self.events(root, events)
        with self.assertRaisesRegex(live.EvidenceError, "payload_flag"):
            live.parse_knowledge(root, receipt, self.execution, self.binding)

    def test_memory_complete_matrix_cross_checks_both_model_categories(self):
        receipt = self.receipt("memory")
        root, _ = self.memory(receipt)
        measured = live.parse_memory(root, receipt, self.execution, self.binding)
        self.assertTrue(measured["usage_known"])
        self.assertTrue(measured["execution_complete"])
        self.assertFalse(measured["stop_new_dispatch"])
        self.assertEqual((72, 20, 2), (measured["sample_counts"]["PASS"], measured["observed_tokens"], measured["observed_attempts"]))
        self.assertEqual(73, len(measured["artifacts"]))

    def test_memory_semantic_failure_settles_usage_and_permits_next_independent_lane(self):
        receipt = self.receipt("memory")
        root, batch = self.memory(receipt)
        path = root / "sample-M13-1.json"
        sample = json.loads(path.read_text())
        sample.update(outcome="FAIL", assertions=[{"dimension": "ANSWER", "code": "ANSWER_FORMAT", "method": "TEXT_HEURISTIC", "outcome": "FAIL"}])
        self.write(path, sample)
        next(entry for entry in batch["samples"] if entry["sampleId"] == "M13-1")["outcome"] = "FAIL"
        self.write(root / "batch.json", batch)
        result, measured = live.collect_and_settle(receipt, self.execution, self.binding, root.with_name("memory-control"))
        self.assertEqual("SETTLED", result["status"])
        self.assertEqual("FAIL", measured["acceptance_outcome"])
        self.assertFalse(measured["stop_new_dispatch"])
        self.assertIsNone(measured["monetary_amount"])
        self.assertEqual("RESERVED", self.receipt("knowledge")["status"])

    def test_memory_incomplete_matrix_does_not_hide_known_fees(self):
        receipt = self.receipt("memory")
        root, batch = self.memory(receipt)
        (root / "sample-M24-3.json").unlink()
        batch["samples"][-1]["outcome"] = "NOT_RUN"
        batch["outcome"] = "STOPPED"
        self.write(root / "batch.json", batch)
        result, measured = live.collect_and_settle(receipt, self.execution, self.binding, root.with_name("memory-control"))
        self.assertEqual("SETTLED", result["status"])
        self.assertEqual(20, result["actual"]["observed_tokens"])
        self.assertEqual(1, measured["sample_counts"]["NOT_RUN"])
        self.assertTrue(measured["stop_new_dispatch"])

    def test_memory_pending_attempt_preserves_known_partial_usage(self):
        receipt = self.receipt("memory")
        root, batch = self.memory(receipt)
        batch["runtimeCalls"][1]["terminal"] = "STARTED"
        batch["runtimeCalls"][1]["usage"] = {"inputTokens": None, "outputTokens": None}
        batch["budget"]["attempts"][1].update(reportedTokens=None, terminal=None)
        batch["budget"].update(reportedTokens=10, chargedOrReservedTokens=110, unknownUsageAttempts=1)
        self.write(root / "batch.json", batch)
        result, measured = live.collect_and_settle(receipt, self.execution, self.binding, root.with_name("memory-control"))
        self.assertEqual("UNKNOWN", result["status"])
        self.assertEqual(10, result["actual"]["observed_tokens"])
        self.assertTrue(measured["token_metrics_are_lower_bounds"])

    def test_memory_wire_mismatch_duplicate_foreign_source_and_stale_batch_reject(self):
        receipt = self.receipt("memory")
        root, original = self.memory(receipt)
        for mode in ("wire_tokens", "duplicate", "source", "stale"):
            batch = deepcopy(original)
            if mode == "wire_tokens":
                batch["wireCalls"][0]["rawUsage"]["usage.prompt_tokens"] = 8
                batch["wireCalls"][0]["rawUsage"]["usage.total_tokens"] = 11
            elif mode == "duplicate":
                batch["runtimeCalls"].append(deepcopy(batch["runtimeCalls"][0]))
            elif mode == "source":
                batch["model"]["sourceRevision"] = 2
            else:
                batch["startedAt"] = "2026-10-11T01:00:01Z"
            self.write(root / "batch.json", batch)
            with self.subTest(mode=mode), self.assertRaises(live.EvidenceError):
                live.parse_memory(root, receipt, self.execution, self.binding)

    def test_memory_infrastructure_failure_settles_usage_and_closes_gate(self):
        receipt = self.receipt("memory")
        root, _ = self.memory(receipt)
        path = root / "sample-M01-1.json"
        sample = json.loads(path.read_text())
        sample["failures"] = [{"category": "INFRASTRUCTURE", "code": "test_fixture_unavailable"}]
        self.write(path, sample)
        result, measured = live.collect_and_settle(receipt, self.execution, self.binding, root.with_name("memory-control"))
        self.assertEqual("SETTLED", result["status"])
        self.assertEqual(1, measured["infrastructure_failures"])
        self.assertTrue(json.loads(self.ledger.read_text())["stop_new_dispatch"])

    def test_source_binding_cannot_change_model_or_credential_or_reset_after_reservation(self):
        original = self.env_file.read_text()
        for new in (original.replace("SOURCE_MODEL_REVISION=1", "SOURCE_MODEL_REVISION=2"), original.replace("DASHSCOPE_API_KEY=private", "DASHSCOPE_API_KEY=changed")):
            self.env_file.write_text(new)
            _, changed = live.environment_and_binding(self.env_file)
            with self.assertRaisesRegex(live.EvidenceError, "binding_drift"):
                live.bind_source(changed)
        self.env_file.write_text(original)
        self.receipt("memory")
        (self.live_root / "source-binding.json").unlink()
        with self.assertRaisesRegex(live.EvidenceError, "binding_missing"):
            live.bind_source(self.binding)

    def test_dry_run_never_reserves_or_starts_process_and_duplicate_source_is_rejected(self):
        with patch.object(live, "execute_lane", side_effect=AssertionError("must not dispatch")):
            self.assertEqual(0, live.main(["--suite", "memory", "--round", "1", "--env-file", str(self.env_file)]))
        self.assertFalse(self.ledger.exists())
        with self.env_file.open("a") as stream:
            stream.write("\nDASHSCOPE_API_KEY=other\n")
        with self.assertRaisesRegex(live.EvidenceError, "duplicate"):
            live.environment_and_binding(self.env_file)

    def test_suite_round_cannot_automatically_restart_original_paid_attempts(self):
        receipt = self.receipt("memory")
        control = Path(receipt["evidence_root"]).with_name("memory-control")
        control.mkdir(parents=True)
        with patch.object(live, "run_owned_process", side_effect=AssertionError("must not dispatch")):
            with self.assertRaisesRegex(live.EvidenceError, "fresh_lane"):
                live.execute_lane("memory", 1, self.source, self.binding)

    def test_json_duplicate_keys_and_bool_usage_cannot_become_known(self):
        with self.assertRaisesRegex(live.EvidenceError, "duplicate_json"):
            live.decode('{"usage_known":false,"usage_known":true}')
        with self.assertRaises(live.EvidenceError):
            live.token_pair({"rawUsage": {"usage.prompt_tokens": True, "usage.completion_tokens": 3}})
        with self.assertRaises(live.EvidenceError):
            live.seconds(float("nan"))

    def test_forged_receipt_cannot_change_root_or_batch_under_same_reservation(self):
        receipt = self.receipt("knowledge")
        root, _ = self.knowledge(receipt)
        before = self.ledger.read_bytes()
        for overrides in ({"evidence_root": str(root.with_name("borrowed"))}, {"batch_id": "borrowed-batch"}, {"max_tokens": 1}):
            with self.subTest(overrides=overrides), self.assertRaisesRegex(live.EvidenceError, "receipt_ledger_drift"):
                live.collect_and_settle({**receipt, **overrides}, self.execution, self.binding, root.with_name("knowledge-control"))
        self.assertEqual(before, self.ledger.read_bytes())

    def test_settlement_crash_reuses_original_evidence_without_replaying_paid_suite(self):
        receipt = self.receipt("knowledge")
        root, _ = self.knowledge(receipt)
        control = root.with_name("knowledge-control")
        with patch.object(budget_ledger, "settle", side_effect=SystemExit("synthetic interruption")):
            with self.assertRaises(SystemExit):
                live.collect_and_settle(receipt, self.execution, self.binding, control)
        original = (control / "settlement-evidence.json").read_bytes()
        result, _ = live.collect_and_settle(receipt, self.execution, self.binding, control)
        self.assertEqual("SETTLED", result["status"])
        self.assertEqual(original, (control / "settlement-evidence.json").read_bytes())

    def test_changed_raw_receipt_cannot_replace_original_settlement_evidence(self):
        receipt = self.receipt("knowledge")
        root, events = self.knowledge(receipt)
        control = root.with_name("knowledge-control")
        with patch.object(budget_ledger, "settle", side_effect=SystemExit("synthetic interruption")):
            with self.assertRaises(SystemExit):
                live.collect_and_settle(receipt, self.execution, self.binding, control)
        path = root / "response-attempt-0.json"
        provider = json.loads(path.read_text())
        provider["usage"].update(total_tokens=11, prompt_tokens=11)
        self.write(path, provider)
        events[1]["response_bytes"] = path.stat().st_size
        events[2].update(total_tokens=11, prompt_tokens=11)
        self.events(root, events)
        with self.assertRaisesRegex(live.EvidenceError, "settlement_evidence_drift"):
            live.collect_and_settle(receipt, self.execution, self.binding, control)
        self.assertEqual("RESERVED", budget_ledger.check_reservation(self.ledger, receipt["reservation_id"], query_only=True)["status"])

    def test_known_failure_closes_gate_before_releasing_reservation(self):
        receipt = self.receipt("knowledge")
        root, _ = self.knowledge(receipt)
        (root / "acceptance-success.json").unlink()
        with patch.object(budget_ledger, "settle", side_effect=SystemExit("synthetic interruption")):
            with self.assertRaises(SystemExit):
                live.collect_and_settle(receipt, self.execution, self.binding, root.with_name("knowledge-control"))
        ledger = json.loads(self.ledger.read_text())
        self.assertTrue(ledger["stop_new_dispatch"])
        self.assertEqual("RESERVED", ledger["reservations"][0]["status"])

    def test_timeout_only_terminates_this_popen_tree_and_closes_gate_first(self):
        receipt = self.receipt("knowledge")
        control = Path(receipt["evidence_root"]).with_name("knowledge-control")
        control.mkdir(parents=True)
        process = MagicMock()
        process.pid = 987654
        process.wait.side_effect = [subprocess.TimeoutExpired("synthetic", 1), -9]
        gate_before_kill = []
        def kill(*args, **kwargs):
            gate_before_kill.append(json.loads(self.ledger.read_text())["stop_new_dispatch"])
        with patch.object(subprocess, "Popen", return_value=process), patch.object(subprocess, "run", side_effect=kill), patch.object(live.os, "killpg", side_effect=kill, create=True):
            result = live.run_owned_process(["synthetic-owned-command"], {}, self.root, control, receipt)
        self.assertTrue(result["timed_out"])
        self.assertEqual([True], gate_before_kill)
        self.assertEqual(-9, result["return_code"])

    def test_complete_unknown_semantic_review_does_not_close_fee_gate(self):
        receipt = self.receipt("memory")
        root, batch = self.memory(receipt)
        path = root / "sample-M04-1.json"
        sample = json.loads(path.read_text())
        sample["outcome"] = "UNKNOWN"
        self.write(path, sample)
        next(entry for entry in batch["samples"] if entry["sampleId"] == "M04-1")["outcome"] = "UNKNOWN"
        self.write(root / "batch.json", batch)
        result, measured = live.collect_and_settle(receipt, self.execution, self.binding, root.with_name("memory-control"))
        self.assertEqual("SETTLED", result["status"])
        self.assertEqual("UNKNOWN", measured["acceptance_outcome"])
        self.assertFalse(measured["stop_new_dispatch"])


if __name__ == "__main__":
    unittest.main()
