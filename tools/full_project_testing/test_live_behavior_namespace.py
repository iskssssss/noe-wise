"""Fixture names must differ without changing canonical budget ownership."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parent))
from live_behavior_namespace import fixture_namespace
from live_behavior import cli_arguments


class FixtureNamespaceTest(unittest.TestCase):
    def test_initial_and_diagnostics_are_distinct_with_one_owner(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original = {"reservation_id": "original-id", "batch_id": "original-batch", "evidence_root": str(root)}
            values = [fixture_namespace(original)]
            for sequence in (1, 2, 3):
                values.append(fixture_namespace({**original, "diagnostic_sequence": sequence,
                                                "active_evidence_root": str(root / f"diagnostic-{sequence}")}))
            self.assertEqual(len({row["fixture_batch_id"] for row in values}), 4)
            self.assertEqual({row["canonical_budget_batch_id"] for row in values}, {"original-batch"})
            self.assertEqual({row["reservation_id"] for row in values}, {"original-id"})
            self.assertTrue(all(not row["new_budget_reservation"] for row in values))

    def test_wrong_child_and_uncontrolled_sequence_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original = {"reservation_id": "original-id", "batch_id": "original-batch", "evidence_root": str(root)}
            for sequence in (-1, True, "1", 1.0):
                with self.subTest(sequence=sequence), self.assertRaisesRegex(ValueError, "namespace_identity"):
                    fixture_namespace({**original, "diagnostic_sequence": sequence})
            with self.assertRaisesRegex(ValueError, "child_identity"):
                fixture_namespace({**original, "diagnostic_sequence": 1,
                                   "active_evidence_root": str(root / "diagnostic-2")})
            with self.assertRaisesRegex(ValueError, "child_identity"):
                fixture_namespace({**original, "diagnostic_sequence": 1,
                                   "active_evidence_root": str(root / "nested" / "diagnostic-1")})

    def test_runner_gets_distinct_fixture_batch_but_original_identity_and_limits_stay(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            receipt = {"reservation_id": "original-id", "batch_id": "original-batch", "evidence_root": str(root),
                       "diagnostic_sequence": 2, "active_evidence_root": str(root / "diagnostic-2"),
                       "max_tokens": 999, "max_seconds": 200, "max_input_turns": 100,
                       "remaining_limits": {"max_tokens": 43, "max_seconds": 12.9, "max_input_turns": 2}}
            before = dict(receipt)
            class Proxy:
                base = "http://127.0.0.1:2"
            args = cli_arguments("core", root / "diagnostic-2", receipt,
                                 {"platform": "http://127.0.0.1:1", "models": {"llm": {"id": "model"}}}, "rev", Proxy())
            self.assertEqual(args[args.index("--batch") + 1], "original-batch-diagnostic-2")
            self.assertEqual(args[args.index("--max-tokens") + 1], "43")
            self.assertEqual(args[args.index("--max-seconds") + 1], "12")
            self.assertEqual(args[args.index("--max-turns") + 1], "2")
            self.assertEqual(receipt, before)

    def test_diagnostic_batch_respects_actual_provision_identifier_contract(self):
        from provision import _validate_inputs
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for batch in ("full-core-r1-12345678", "a" * 48, "a" * 47 + "b"):
                namespace = fixture_namespace({"reservation_id": "original-id", "batch_id": batch,
                                               "evidence_root": str(root), "diagnostic_sequence": 3,
                                               "active_evidence_root": str(root / "diagnostic-3")})
                _validate_inputs(namespace["fixture_batch_id"], "1", "http://127.0.0.1:1/mcp")
                self.assertLessEqual(len(namespace["fixture_batch_id"]), 48)


if __name__ == "__main__":
    unittest.main()
