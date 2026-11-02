"""Result gates must reject missing/skipped/failed native JUnit cases."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from run_execution_acceptance import preflight, verify_xml


class AcceptanceResultTest(unittest.TestCase):
    def test_native_outcomes_remain_distinct_and_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "TEST-current.xml").write_text(
                '<testsuite><testcase classname="x" name="ok()"/>'
                '<testcase classname="x" name="skip()"><skipped/></testcase>'
                '<testcase classname="x" name="fail()"><failure/></testcase>'
                '<testcase classname="x" name="error()"><error/></testcase></testsuite>', encoding="utf-8")
            self.assertEqual("PASS", verify_xml(root, ["x.ok"])["status"])
            for case, expected in (("skip", "SKIPPED"), ("fail", "FAILED"), ("error", "FAILED"), ("missing", "NOT_RUN")):
                result = verify_xml(root, ["x.ok", "x." + case])
                self.assertEqual("FAILED", result["status"])
                self.assertEqual(expected, result["cases"]["x." + case])

    def test_formal_capacity_refuses_diagnostic_profiler(self):
        for key in ("SKILLFORGE_PERF_SQL", "SKILLFORGE_PERF_SQL_CALLERS", "SKILLFORGE_PERF_SQL_ROLES"):
            with patch.dict(os.environ, {key: "1"}, clear=True), self.assertRaises(ValueError):
                preflight("capacity")

    def test_process_cannot_silently_skip_on_absent_enable_flag(self):
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(ValueError):
            preflight("process")


if __name__ == "__main__":
    unittest.main()
