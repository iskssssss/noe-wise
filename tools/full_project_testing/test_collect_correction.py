"""补采器负控：不能把旧、截断、重复、跳过或命令失败的 XML 填成 PASS。"""
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import tempfile
import unittest

import collect_correction


class CorrectionEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.lane = self.root / "original"
        self.lane.mkdir()
        self.now = datetime.now(timezone.utc)
        self.record = {"id": "browser", "status": "UNKNOWN", "exit_code": 0,
                       "command": ["corepack.cmd", "pnpm", "test:e2e:mock"], "cwd": ".", "pid": 731,
                       "started_at": (self.now - timedelta(seconds=3)).isoformat(),
                       "finished_at": (self.now + timedelta(seconds=1)).isoformat()}
        self.original = self.lane / "result.json"
        self.original.write_text(json.dumps(self.record), encoding="utf-8")
        (self.lane / "command.log").write_text("1 passed (3s)\n", encoding="utf-8")

    def xml(self, cases='<testcase name="writes_once" classname="complex.spec"/>', tests=1):
        path = self.lane / "junit.xml"
        path.write_text(f'<testsuites tests="{tests}"><testsuite tests="{tests}" timestamp="{self.now.isoformat()}">{cases}</testsuite></testsuites>', encoding="utf-8")
        return path

    def collect(self):
        before = self.original.read_bytes()
        result = collect_correction.collect_lane(self.lane, self.root / "correction", "junit.xml")
        self.assertEqual(before, self.original.read_bytes())
        return result

    def test_complete_fresh_xml_is_append_only_pass_with_all_cases(self):
        self.xml('<testcase name="writes_once" classname="complex.spec"/><testcase name="denies_old_identity" classname="complex.spec"/>', 2)
        result = self.collect()
        self.assertEqual(("UNKNOWN", "PASS", 2), (result["original_status"], result["status"], result["test_count"]))
        self.assertEqual(["writes_once", "denies_old_identity"], [case["name"] for case in result["junit"][0]["testcases"]])
        with self.assertRaises(FileExistsError):
            collect_correction.collect_lane(self.lane, self.root / "correction", "junit.xml")

    def test_old_xml_and_future_xml_are_not_reused(self):
        path = self.xml()
        for seconds in (-20, 20):
            modified = self.now.timestamp() + seconds
            os.utime(path, (modified, modified))
            result = collect_correction.parse_junit(path, self.now - timedelta(seconds=3), self.now + timedelta(seconds=1))
            self.assertIn("xml_not_fresh_for_command_window", result["issues"])

    def test_truncated_counts_duplicate_cases_and_missing_identity_stay_unknown(self):
        self.xml('<testcase name="duplicate" classname="x"/><testcase name="duplicate" classname="x"/><testcase name="missing-class"/>', 137)
        result = self.collect()
        self.assertEqual("UNKNOWN", result["status"])
        self.assertTrue({"suite_tests_count_mismatch", "duplicate_testcase_identity", "testcase_missing_identity"}.issubset(result["issues"]))

    def test_process_nonzero_with_passing_junit_is_fail(self):
        self.record["exit_code"] = 1
        self.original.write_text(json.dumps(self.record), encoding="utf-8")
        self.xml()
        result = self.collect()
        self.assertEqual(("FAIL", 0), (result["status"], result["failures"]))

    def test_junit_failure_with_zero_exit_preserves_failure(self):
        self.xml('<testcase name="CAS" classname="x"><failure message="lost draft">exact failure</failure></testcase>')
        result = self.collect()
        self.assertEqual(("FAIL", 1), (result["status"], result["failures"]))
        self.assertEqual("exact failure", result["junit"][0]["testcases"][0]["details"]["failure"]["text"])

    def test_all_skipped_missing_exit_and_unfinished_window_cannot_pass(self):
        self.record.pop("exit_code")
        self.record.pop("finished_at")
        self.original.write_text(json.dumps(self.record), encoding="utf-8")
        self.xml('<testcase name="not-executed" classname="x"><skipped/></testcase>')
        result = self.collect()
        self.assertEqual("UNKNOWN", result["status"])
        self.assertTrue({"command_has_no_final_exit_code", "command_time_window_invalid", "no_executed_testcases"}.issubset(result["issues"]))


if __name__ == "__main__":
    unittest.main()
