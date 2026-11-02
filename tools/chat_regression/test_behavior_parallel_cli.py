"""双路 CLI 冻结策略和准备失败清理；不请求真实模型。"""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from behavior_main import ci_run, create_plan, local_selection, parser
from behavior_parallel import local_parallel_run
from mcp_fixture import McpFixtureServer


class ParallelCliTests(unittest.TestCase):
    def args(self, root, *extra):
        return parser().parse_args(["local-run", "--root", str(root), "--model-id", "1",
            "--deployment-revision", "test-revision", "--max-tokens", "1000", "--max-turns", "20",
            "--max-seconds", "60", "--workers", "2", *extra])

    def test_workers_are_frozen_and_cannot_switch_to_one(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.args(root)
            with patch("behavior_parallel.local_parallel_run", return_value=0) as run:
                self.assertEqual(0, ci_run(args, root))
                self.assertEqual(2, run.call_args.args[2]["local_selection"]["workers"])
            args.workers = 1
            with self.assertRaisesRegex(ValueError, "frozen_local_selection_mismatch"):
                ci_run(args, root)

    def test_formal_ci_rejects_parallel_before_fixture_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            args.command = "ci-run"
            args.environment_receipt = Path(directory) / "receipt.json"
            with patch("mcp_fixture.McpFixtureServer") as server:
                with self.assertRaisesRegex(ValueError, "selection_requires_local_run"):
                    ci_run(args, Path(directory))
                server.assert_not_called()

    def test_second_lane_preparation_failure_closes_both_real_listeners(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.args(root)
            plan = create_plan(root, "parallel-tests", local_selection(args))
            servers = []

            def factory(*params):
                server = McpFixtureServer(*params)
                servers.append(server)
                return server

            with patch("behavior_parallel.login", return_value=Mock()), \
                    patch("behavior_parallel.resolve_model", return_value={"id": "1"}), \
                    patch("behavior_parallel.configuration", return_value={"model": "fixed"}), \
                    patch("behavior_parallel.provision", side_effect=[{}, RuntimeError("fixture setup failed")]), \
                    patch("behavior_parallel.McpFixtureServer", side_effect=factory):
                self.assertEqual(1, local_parallel_run(args, root, plan))
            self.assertEqual(2, len(servers))
            self.assertTrue(all(server.socket.fileno() == -1 for server in servers))
            report = json.loads((root / "evaluation.json").read_text(encoding="utf-8"))
            self.assertFalse(report["release_eligible"])
            self.assertEqual(0, report["metrics"]["budget"]["turns"])
            self.assertIn("invalid_environment", [r["code"] for r in report["blocking_reasons"]])
            self.assertFalse((root / "execution.lock").exists())

    def test_lock_conflict_never_overwrites_running_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "execution.lock").write_text("other-owner", encoding="utf-8")
            (root / "batch.json").write_text('"running-evidence"', encoding="utf-8")
            args = self.args(root)
            with self.assertRaises(RuntimeError):
                local_parallel_run(args, root, {})
            self.assertEqual('"running-evidence"', (root / "batch.json").read_text(encoding="utf-8"))
            self.assertFalse((root / "evaluation.json").exists())
