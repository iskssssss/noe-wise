"""Owned fixture cleanup must never delete a foreign container or infer absence from daemon failure."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from execution_fixture import LABEL, captured_text, cleanup


class OwnedCleanupTest(unittest.TestCase):
    def test_failed_native_encoding_and_missing_stream_preserve_redacted_error(self):
        result = subprocess.CompletedProcess([], 1, None, b"native error \xd6\xd0 password-secret")
        logged = captured_text(result, ("password-secret",))
        self.assertIn("native error", logged)
        self.assertIn(r"\xd6", logged)
        self.assertIn("<redacted>", logged)
        self.assertNotIn("password-secret", logged)

    def test_foreign_owner_is_rejected_before_removal(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "fixture.json").write_text(json.dumps({"owner": "ours", "containers": ["fixture-pg"]}))
            inspected = subprocess.CompletedProcess([], 0, json.dumps([{"Id": "foreign-id", "Config": {"Labels": {LABEL: "someone-else"}}}]), "")
            with patch("execution_fixture.subprocess.run", return_value=inspected), patch("execution_fixture.docker") as docker:
                with self.assertRaisesRegex(RuntimeError, "ownership"):
                    cleanup(root)
                docker.assert_not_called()

    def test_daemon_failure_cannot_be_reported_as_not_found(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "fixture.json").write_text(json.dumps({"owner": "ours", "containers": ["fixture-pg"]}))
            failed = subprocess.CompletedProcess([], 1, "", "daemon unavailable")
            with patch("execution_fixture.subprocess.run", return_value=failed):
                with self.assertRaises(RuntimeError):
                    cleanup(root)
            self.assertNotEqual("CLEANED", json.loads((root / "fixture.json").read_text()).get("status"))


class DataDirectoryCleanupTest(unittest.TestCase):
    def state(self, evidence, data_root, directory, owner="test-owner"):
        (evidence / "fixture.json").write_text(json.dumps({
            "containers": [], "owner": owner, "postgresDataRoot": str(data_root),
            "postgresDataDirectory": str(directory),
        }), encoding="utf-8")

    def test_removes_only_owned_child_and_preserves_data_root_and_neighbor(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            owned = root / "owned"
            owned.mkdir()
            (owned / "fixture-owner.txt").write_text("test-owner", encoding="utf-8")
            (owned / "data").mkdir()
            neighbor = root / "unrelated.txt"
            neighbor.write_text("keep", encoding="utf-8")
            self.state(root, root, owned)
            self.assertTrue(cleanup(root))
            self.assertFalse(owned.exists())
            self.assertEqual("keep", neighbor.read_text())
            self.assertEqual("CLEANED", json.loads((root / "fixture.json").read_text())["status"])

    def test_wrong_owner_is_not_removed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            owned = root / "owned"
            owned.mkdir()
            marker = owned / "fixture-owner.txt"
            marker.write_text("another-task", encoding="utf-8")
            self.state(root, root, owned)
            with self.assertRaisesRegex(RuntimeError, "ownership mismatch"):
                cleanup(root)
            self.assertEqual("another-task", marker.read_text())

    def test_root_itself_and_directory_outside_root_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data_root = root / "data-root"
            data_root.mkdir()
            for candidate in (data_root, root / "outside"):
                self.state(root, data_root, candidate)
                with self.assertRaisesRegex(RuntimeError, "outside owned root"):
                    cleanup(root)
                self.assertTrue(data_root.exists())



if __name__ == "__main__":
    unittest.main()
