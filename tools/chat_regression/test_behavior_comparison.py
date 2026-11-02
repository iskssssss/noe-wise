"""通过冻结批次与真实 CLI 验证比较边界；不调用模型、服务或数据库。"""
from copy import deepcopy
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from behavior_comparison import freeze_experiment, compare_experiment, transitions, sha_file
from behavior_contract import digest
from behavior_main import main
from behavior_suite import make_plan
from runner import read, save


class ComparisonTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.left, self.right = self.base / "left", self.base / "right"
        self.write_batch(self.left, "baseline", "old")
        self.write_batch(self.right, "candidate", "new")

    def write_batch(self, root, batch_name, prompt):
        canary = "SF-PRIVATE-" + batch_name
        plan = make_plan(batch_name, canary=canary)
        plan["profiles"][0]["prompt"] = prompt + " " + canary
        plan["local_selection"] = {"case_ids": ["B01", "B02"], "repetitions": 2,
                                   "turn_timeout_seconds": 120, "workers": 1}
        plan["fingerprint"] = digest({k: v for k, v in plan.items() if k != "fingerprint"})
        config = {"model": {"id": "model", "model_id": "version", "temperature": 0},
                  "agents": {p["key"]: {"system_prompt": p["prompt"], "role_description": "assistant",
                                        "mounts": [], "execution_config": {"max_steps": 10}}
                             for p in plan["profiles"]}, "system_agents": [], "system_agent_models": {}}
        binding = {"suite_version": plan["suite_version"], "model_id": "model", "model_version": "version",
                   "deployment_revision": "same-deployment", "evaluator_version": "behavior-7",
                   "evidence_mode": "public_trace", "settlement_seconds": 60, "config_digest": digest(config)}
        batch = {"lifecycle": "finalized", "binding": binding,
                 "budget": {"max_tokens": 10000, "max_turns": 20, "max_seconds": 1200}}
        rows = [{"case_id": cid, "repetition": rep, "behavior_status": "passed",
                 "assertions": [{"id": "task_completed", "status": "pass"}], "blocking_reasons": []}
                for cid in ("B01", "B02") for rep in (1, 2)]
        report = {"lifecycle": "finalized", "binding": {**binding, "plan_fingerprint": plan["fingerprint"]},
                  "cases": rows, "blocking_reasons": [], "metrics": {}}
        for name, data in (("plan", plan), ("batch", batch), ("configuration", config), ("evaluation", report),
                           ("private", {"sensitive_values": [canary]})):
            save(root / (name + ".json"), data)

    def compare(self, **options):
        freeze_experiment(self.left, self.right, self.base / "experiment", "prompt", ["agent:behavior:system_prompt"])
        return compare_experiment(self.base / "experiment/experiment.json", self.base / "comparison", **options)

    def test_declared_prompt_and_canary_change_compares_quality_with_unknown_cache(self):
        baseline = read(self.left / "evaluation.json")
        baseline["cases"][0].update(behavior_status="failed", assertions=[{"id": "task_completed", "status": "fail"}])
        save(self.left / "evaluation.json", baseline)
        before = sha_file(self.left / "evaluation.json")
        result = self.compare()
        self.assertTrue(result["quality"]["comparable"], result)
        delta = result["quality"]["results"]
        self.assertEqual(1, delta["counts"]["improved"])
        self.assertEqual(4, delta["baseline"]["planned"])
        self.assertEqual("fail", delta["cases"][0]["assertions"][0]["baseline"])
        self.assertIn("cache_condition_unknown", result["measurements"]["cost"]["reasons"])
        self.assertEqual(before, sha_file(self.left / "evaluation.json"))
        self.assertFalse(result["release_eligible"])

    def test_configuration_model_tool_permission_changes_are_not_prompt_effects(self):
        for field, value in (("model", {"id": "another"}), ("mounts", [{"id": "new-tool"}]),
                             ("execution_config", {"allow_write": True})):
            with self.subTest(field=field):
                folder = self.base / field
                self.write_batch(folder, "candidate", "new")
                config = read(folder / "configuration.json")
                if field == "model": config[field] = value
                else: config["agents"]["behavior"][field] = value
                save(folder / "configuration.json", config)
                for name in ("batch", "evaluation"):
                    data = read(folder / (name + ".json"))
                    data["binding"]["config_digest"] = digest(config)
                    save(folder / (name + ".json"), data)
                freeze_experiment(self.left, folder, self.base / (field + "-experiment"), "prompt", ["agent:behavior:system_prompt"])
                result = compare_experiment(self.base / (field + "-experiment/experiment.json"), self.base / (field + "-out"))
                self.assertFalse(result["quality"]["comparable"])
                self.assertIn("non_prompt_controls_changed", result["quality"]["reasons"])

    def test_undeclared_prompt_change_and_missing_budget_block_comparison(self):
        freeze_experiment(self.left, self.right, self.base / "repeat")
        result = compare_experiment(self.base / "repeat/experiment.json", self.base / "repeat-out")
        self.assertIn("undeclared_or_missing_prompt_change", result["quality"]["reasons"])
        data = read(self.right / "batch.json")
        data.pop("budget")
        save(self.right / "batch.json", data)
        self.assertIn("budget_controls_missing", self.compare()["quality"]["reasons"])

    def test_source_mutation_and_output_overlap_rejected(self):
        with self.assertRaisesRegex(ValueError, "separate"):
            freeze_experiment(self.left, self.right, self.left / "nested")
        freeze_experiment(self.left, self.right, self.base / "experiment", "prompt", ["agent:behavior:system_prompt"])
        save(self.right / "cases/B01/1/evidence.json", {"case_id": "B01", "repetition": 1})
        with self.assertRaisesRegex(ValueError, "input_changed"):
            compare_experiment(self.base / "experiment/experiment.json", self.base / "comparison")
        self.assertFalse((self.base / "comparison").exists())

    def test_unknown_missing_and_aggregate_placeholders_keep_full_denominator(self):
        left = read(self.left / "evaluation.json")
        right = deepcopy(left)
        right["cases"].pop()
        right["cases"][0]["behavior_status"] = "unknown"
        right["cases"][1]["behavior_status"] = "unknown"
        right["cases"][1]["blocking_reasons"] = [{"code": "incomplete", "detail": "experiment_not_run"}]
        matrix = [{k: row[k] for k in ("case_id", "repetition")} for row in left["cases"]]
        result = transitions(left, right, matrix)
        self.assertEqual({"planned": 4, "passed": 1, "failed": 0, "unknown": 1, "not_run": 2, "pass_rate": .25}, result["candidate"])
        self.assertEqual(3, result["counts"]["unresolved"])
        self.assertIsNone(result["repeated_observations"][0]["candidate"]["all_pass"])
        right["cases"].append(right["cases"][0])
        with self.assertRaisesRegex(ValueError, "duplicate_sample"):
            transitions(left, right, matrix)

    def test_changed_data_and_deployment_are_not_prompt_effects(self):
        plan = read(self.right / "plan.json")
        plan["cases"][0]["turns"] = ["new task"]
        plan["fingerprint"] = digest({k: v for k, v in plan.items() if k != "fingerprint"})
        save(self.right / "plan.json", plan)
        report = read(self.right / "evaluation.json")
        report["binding"]["plan_fingerprint"] = plan["fingerprint"]
        save(self.right / "evaluation.json", report)
        self.assertIn("non_prompt_controls_changed", self.compare()["quality"]["reasons"])

    def test_dropped_assertion_cannot_appear_as_an_improvement(self):
        report = read(self.right / "evaluation.json")
        report["cases"][0]["assertions"] = []
        save(self.right / "evaluation.json", report)
        result = self.compare()
        self.assertIn("evaluated_assertion_inventory_changed_or_empty", result["quality"]["reasons"])
        self.assertNotIn("results", result["quality"])

    def test_deployment_change_or_missing_capture_mode_stays_explicit(self):
        for name in ("batch", "evaluation"):
            report = read(self.right / (name + ".json"))
            report["binding"]["deployment_revision"] = "changed-code"
            report["binding"].pop("evidence_mode")
            save(self.right / (name + ".json"), report)
        result = self.compare()
        self.assertIn("binding_mismatch:deployment_revision", result["quality"]["reasons"])
        self.assertIn("binding_missing:evidence_mode", result["quality"]["reasons"])

    def test_cli_freeze_compare_does_not_connect_and_returns_noncomparable_exit(self):
        with patch("behavior_main.private_root", side_effect=lambda p: p.resolve()), patch("client.Api.request", side_effect=AssertionError("network")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, main(["experiment-freeze", "--baseline", str(self.left), "--root", str(self.right),
                                      "--out", str(self.base / "experiment")]))
            self.assertEqual(1, main(["experiment-compare", "--experiment", str(self.base / "experiment/experiment.json"),
                                      "--out", str(self.base / "comparison")]))


if __name__ == "__main__":
    unittest.main()
