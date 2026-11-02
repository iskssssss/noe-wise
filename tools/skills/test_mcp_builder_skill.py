"""MCP 生成器真实源码、官方 SDK stdio 协议与失败不交付回归。"""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / "official-skills/mcp-builder"


class McpBuilderTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="mcp-builder-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        spec = importlib.util.spec_from_file_location("mcp_builder", PACKAGE / "scripts/build_mcp_server.py")
        self.builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.builder)
        self.builder.INPUT_DATA = self.directory / "input/data"
        self.builder.INPUT_ARTIFACTS = self.directory / "input/artifacts"
        self.builder.OUTPUT = self.directory / "output"
        self.builder.WORK = self.directory / "work"
        self.builder.INPUT_DATA.mkdir(parents=True)
        self.builder.INPUT_ARTIFACTS.mkdir(parents=True)
        self.data = json.loads((PACKAGE / "examples/mcp-server.json").read_text(encoding="utf-8"))

    def run_builder(self, data):
        (self.builder.INPUT_DATA / "mcp-server.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            self.builder.main()
        return json.loads(stdout.getvalue())

    def test_real_protocol_schema_results_pagination_and_invalid_arguments(self):
        report = self.run_builder(self.data)
        self.assertTrue(report["unknown_tool_error"])
        self.assertEqual(len(report["checks"]), 6)
        self.assertTrue(all(c["passed"] for c in report["checks"]))
        self.assertEqual(report["transport"], "stdio")
        self.assertFalse(report["external_service_tested"])
        self.assertIn("mcp-server/server.py", report["output_files"])
        self.assertNotIn("result.json", report["output_files"])
        self.assertFalse((self.builder.OUTPUT / "mcp-server.zip").exists())
        public = json.loads((self.builder.OUTPUT / "result.json").read_text(encoding="utf-8"))
        self.assertEqual(public["results"][0]["kind"], "text")
        self.assertEqual(public["results"][0]["format"], "plain")
        self.assertEqual(json.loads(public["results"][0]["text"]), report)
        self.assertEqual(list(self.builder.WORK.iterdir()), [])

    def test_wrong_expected_result_fails_without_candidate_source(self):
        self.data["tests"][0]["expected_json"]["sum"] = 999
        with self.assertRaises(BaseException):
            self.run_builder(self.data)
        self.assertFalse((self.builder.OUTPUT / "mcp-server").exists())

    def test_custom_python_source_compiles_and_executes_real_protocol(self):
        self.data["source_files"] = self.builder.sources(self.data)
        report = self.run_builder(self.data)
        self.assertEqual(report["compiled_sources"], ["server.py"])
        self.assertEqual(report["listed_tools"], ["department_lookup", "department_sum"])

    def test_syntax_error_fails_before_deliverable(self):
        self.data["source_files"] = {"server.py": "def broken(:\n"}
        with self.assertRaises(Exception):
            self.run_builder(self.data)
        self.assertFalse((self.builder.OUTPUT / "mcp-server").exists())

    def test_runtime_cannot_replace_compiled_frozen_source(self):
        source = self.builder.sources(self.data)["server.py"]
        source = source.replace("SPEC = json.loads", "from pathlib import Path\nPath(__file__).write_text('broken syntax =', encoding='utf-8')\nSPEC = json.loads")
        self.data["source_files"] = {"server.py": source}
        with self.assertRaisesRegex(ValueError, "冻结源码"):
            self.run_builder(self.data)
        self.assertFalse((self.builder.OUTPUT / "mcp-server").exists())

    def test_path_traversal_and_duplicate_input_rejected(self):
        bad = copy.deepcopy(self.data)
        bad["source_files"] = {"server.py": "pass", "../escape.py": "pass"}
        with self.assertRaisesRegex(ValueError, "路径"):
            self.run_builder(bad)
        second = self.builder.INPUT_ARTIFACTS / "duplicate/mcp-server.json"
        second.parent.mkdir()
        second.write_text(json.dumps(self.data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "只能"):
            self.builder.locate_input()

    def test_all_tools_need_positive_test_and_zip_is_explicit(self):
        bad = copy.deepcopy(self.data)
        bad["tests"] = bad["tests"][:2]
        with self.assertRaisesRegex(ValueError, "每个工具"):
            self.builder.validate(bad)
        self.data["bundle_zip"] = True
        report = self.run_builder(self.data)
        self.assertIn("mcp-server.zip", report["output_files"])
        with ZipFile(self.builder.OUTPUT / "mcp-server.zip") as archive:
            self.assertEqual(set(archive.namelist()), {"mcp-server/server.py", "mcp-server/requirements.txt", "mcp-server/README.md"})
            self.assertNotIn("result.json", archive.namelist())


if __name__ == "__main__":
    unittest.main()
