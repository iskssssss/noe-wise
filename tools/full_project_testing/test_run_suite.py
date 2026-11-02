"""执行器的证据负控：旧XML、零测试、skip与失败都不能误报为通过。"""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
import run_suite


class EvidenceTest(unittest.TestCase):
    """使用独立临时根与真子进程验证状态转换，不启动业务服务。"""

    def setUp(self):
        """每个测试独立证据目录，失败也回收。"""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def execute(self, code, **extra):
        """执行一个明确输出的本地子进程，不继承模型凭据。"""
        return run_suite.execute(self.root, {"id": "lane", "command": [sys.executable, "-c", code], **extra}, {})

    def test_zero_exit_without_tests_is_unknown(self):
        """只完成命令不提供测试数，必须保持UNKNOWN。"""
        self.assertEqual("UNKNOWN", self.execute("print('BUILD SUCCESSFUL')")["status"])

    def test_nonzero_is_fail_even_with_success_message(self):
        """输出成功数量不能覆盖进程非零状态。"""
        self.assertEqual("FAIL", self.execute("print('Ran 12 tests'); raise SystemExit(1)")["status"])

    def test_unittest_count_is_collected(self):
        """机械统计独立Python套件，不依靠Agent手写通过数。"""
        result = self.execute("print('Ran 37 tests')")
        self.assertEqual(("PASS", 37), (result["status"], result["test_count"]))

    def test_old_xml_is_not_reused(self):
        """历史报告不能被新一轮exit0冒用。"""
        source = self.root / "old.xml"
        source.write_text('<testsuite><testcase name="old"/></testsuite>', encoding="utf-8")
        with patch.object(run_suite, "REPO", self.root):
            self.assertEqual([], run_suite.collect_xml(source.stat().st_mtime + 10, self.root / "copy", ["*.xml"]))

    def test_case_children_override_false_suite_attributes(self):
        """不信任testsuite聚合属性，读取实际testcase及失败节点。"""
        source = self.root / "fresh.xml"
        source.write_text('<testsuite tests="1000" failures="0"><testcase name="bad"><failure message="wrong"/></testcase><testcase name="skip"><skipped/></testcase></testsuite>', encoding="utf-8")
        with patch.object(run_suite, "REPO", self.root):
            result = run_suite.collect_xml(0, self.root / "copy", ["fresh.xml"])[0]
        self.assertEqual((2, 1, 1), (result["tests"], result["failures"], result["skipped"]))

    def test_lane_cannot_overwrite_previous_result(self):
        """再次执行相同证据identity必须拒绝覆盖首轮失败。"""
        self.execute("print('Ran 1 test');raise SystemExit(1)")
        with self.assertRaises(FileExistsError):
            self.execute("print('Ran 1 test')")


if __name__ == "__main__":
    unittest.main()
