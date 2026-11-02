"""配置 Skill 的 Schema 约束、真实 JSON/YAML 文件与拒绝回归。"""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import yaml

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / "official-skills/configuration"
SCRIPT = PACKAGE / "scripts/generate_configuration.py"


class ConfigurationSkillTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="configuration-skill-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        spec = importlib.util.spec_from_file_location("configuration_skill", SCRIPT)
        self.skill = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.skill)
        self.skill.INPUT_DATA = self.root / "input/data"
        self.skill.INPUT_ARTIFACTS = self.root / "input/artifacts"
        self.skill.OUTPUT = self.root / "workspace/output"
        self.skill.INPUT_DATA.mkdir(parents=True)
        self.skill.INPUT_ARTIFACTS.mkdir(parents=True)
        self.schema = json.loads((PACKAGE / "examples/schema.json").read_text(encoding="utf-8"))
        self.schema_file = self.skill.INPUT_DATA / "schema.json"
        self.write_schema()
        self.data = json.loads((PACKAGE / "examples/configuration.json").read_text(encoding="utf-8"))
        self.data["schema_file"] = self.schema_file.as_posix()

    def write_schema(self):
        self.schema_file.write_text(json.dumps(self.schema, ensure_ascii=False), encoding="utf-8")

    def run_skill(self, data=None, control=None):
        target = control or self.skill.INPUT_DATA / "configuration.json"
        target.write_text(json.dumps(self.data if data is None else data, ensure_ascii=False), encoding="utf-8")
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            self.skill.main()
        self.assertEqual("", stdout.getvalue())
        result = json.loads((self.skill.OUTPUT / "result.json").read_text(encoding="utf-8"))
        return json.loads(result["results"][0]["text"])

    def rejected(self, code, data=None):
        with self.assertRaises(self.skill.ConfigurationError) as raised:
            self.run_skill(data)
        self.assertEqual(code, raised.exception.report["code"])
        self.assertFalse(self.skill.OUTPUT.exists(), "校验失败不能创建候选输出")
        return raised.exception.report

    def file_input(self, extension, text):
        source = self.skill.INPUT_DATA / ("source" + extension)
        source.write_text(text, encoding="utf-8")
        self.data.pop("document")
        self.data["document_file"] = source.as_posix()
        return source

    def test_valid_yaml_output_internal_ref_and_no_default_zip(self):
        report = self.run_skill()
        self.assertTrue(report["valid"])
        self.assertEqual("provided_schema", report["validation_scope"])
        self.assertFalse(report["applied"])
        self.assertEqual(self.data["document"], yaml.safe_load((self.skill.OUTPUT / "configuration.yaml").read_text(encoding="utf-8")))
        self.assertEqual({"configuration.yaml", "result.json"}, {path.name for path in self.skill.OUTPUT.iterdir()})

    def test_cli_returns_safe_field_diagnostic_on_stderr(self):
        self.data["document"]["service"]["port"] = "secret-canary"
        (self.skill.INPUT_DATA / "configuration.json").write_text(json.dumps(self.data), encoding="utf-8")
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            self.assertEqual(2, self.skill.cli())
        self.assertEqual("", stdout.getvalue())
        report = json.loads(stderr.getvalue())
        self.assertEqual("/service/port", report["errors"][0]["instance_path"])
        self.assertNotIn("secret-canary", stderr.getvalue())
        self.assertFalse(self.skill.OUTPUT.exists())

    def test_json_output_and_explicit_zip_only_contains_configuration(self):
        self.data.update(format="json", bundle_zip=True)
        report = self.run_skill()
        target = self.skill.OUTPUT / "configuration.json"
        self.assertEqual(self.data["document"], json.loads(target.read_text(encoding="utf-8")))
        self.assertEqual("configuration.zip", report["bundle"])
        with ZipFile(self.skill.OUTPUT / "configuration.zip") as archive:
            self.assertEqual(["configuration.json"], archive.namelist())
            self.assertEqual(target.read_bytes(), archive.read("configuration.json"))

    def test_yaml_source_and_yaml_schema_are_explicitly_read(self):
        original = (PACKAGE / "examples/source.yaml").read_text(encoding="utf-8")
        source = self.file_input(".yaml", original)
        self.schema_file = self.skill.INPUT_DATA / "schema.yaml"
        self.schema_file.write_text(yaml.safe_dump(self.schema), encoding="utf-8")
        self.data["schema_file"] = self.schema_file.as_posix()
        self.data["format"] = "json"
        self.run_skill()
        self.assertEqual(original, source.read_text(encoding="utf-8"))
        self.assertEqual(yaml.safe_load(original), json.loads((self.skill.OUTPUT / "configuration.json").read_text(encoding="utf-8")))

    def test_artifact_control_schema_and_json_source(self):
        artifact = self.skill.INPUT_ARTIFACTS / "artifact-1"
        artifact.mkdir()
        source = artifact / "source.json"
        source.write_text(json.dumps(self.data.pop("document")), encoding="utf-8")
        schema = artifact / "schema.json"
        schema.write_text(json.dumps(self.schema), encoding="utf-8")
        self.data.update(document_file=source.as_posix(), schema_file=schema.as_posix())
        report = self.run_skill(control=artifact / "configuration.json")
        self.assertTrue(report["valid"])

    def test_type_enum_range_required_and_additional_fields(self):
        mutations = [
            (lambda data: data["document"]["service"].update(port="8080"), "type", "/service/port"),
            (lambda data: data["document"]["service"].update(port=True), "type", "/service/port"),
            (lambda data: data["document"]["service"].update(port=70000), "maximum", "/service/port"),
            (lambda data: data["document"]["service"].update(environment="unknown"), "enum", "/service/environment"),
            (lambda data: data["document"]["features"].pop("artifacts"), "required", "/features"),
            (lambda data: data["document"]["service"].update(password="secret-canary"), "additionalProperties", "/service"),
        ]
        for mutation, keyword, path in mutations:
            with self.subTest(keyword=keyword, path=path):
                data = copy.deepcopy(self.data)
                mutation(data)
                report = self.rejected("configuration_invalid", data)
                self.assertTrue(any(error["keyword"] == keyword and error["instance_path"] == path for error in report["errors"]))
                self.assertNotIn("secret-canary", json.dumps(report))

    def test_null_is_valid_when_schema_explicitly_allows_it(self):
        self.schema["$defs"]["service"]["properties"]["name"] = {"type": ["string", "null"]}
        self.write_schema()
        self.data["document"]["service"]["name"] = None
        self.assertTrue(self.run_skill()["valid"])

    def test_json_pointer_escaping_arrays_and_no_secret_values(self):
        self.schema = {"$schema": self.schema["$schema"], "type": "object", "properties": {"a/b~c": {"type": "array", "items": {"type": "integer"}}}}
        self.write_schema()
        self.data["document"] = {"a/b~c": ["secret-canary"]}
        report = self.rejected("configuration_invalid")
        self.assertEqual("/a~1b~0c/0", report["errors"][0]["instance_path"])
        self.assertNotIn("secret-canary", json.dumps(report))

    def test_error_limit_is_explicit(self):
        self.schema = {"$schema": self.schema["$schema"], "type": "object", "properties": {"values": {"type": "array", "items": {"type": "integer"}}}}
        self.write_schema()
        self.data["document"] = {"values": ["bad"] * 25}
        report = self.rejected("configuration_invalid")
        self.assertEqual(20, len(report["errors"]))
        self.assertTrue(report["errors_truncated"])

    def test_draft7_and_2019_schema_and_annotation_values(self):
        for dialect in ("http://json-schema.org/draft-07/schema#", "https://json-schema.org/draft/2019-09/schema"):
            with self.subTest(dialect=dialect):
                schema = {"$schema": dialect, "type": "object", "default": {"$ref": "https://must-not-fetch.invalid", "format": "business-value"},
                          "properties": {"name": {"type": "string", "default": "must-not-insert"}}}
                document = {}
                self.skill.validate_configuration(document, schema, self.data["schema_source"])
                self.assertEqual({}, document, "Schema default 不能自动写入配置")

    def test_remote_file_relative_refs_never_fetch(self):
        for reference in ("https://must-not-fetch.invalid/schema", "file:///etc/passwd", "other.json#/$defs/a"):
            with self.subTest(reference=reference), patch("urllib.request.urlopen", side_effect=AssertionError("网络获取禁止")) as network:
                self.schema["properties"]["service"] = {"$ref": reference}
                self.write_schema()
                self.rejected("external_schema_ref")
                network.assert_not_called()

    def test_unreachable_dynamic_ref_is_still_rejected(self):
        self.schema["$defs"]["unused"] = {"$dynamicRef": "https://must-not-fetch.invalid"}
        self.write_schema()
        self.rejected("external_schema_ref")

    def test_missing_and_recursive_fragment_fail_without_network(self):
        for reference in ("#/$defs/missing", "#"):
            with self.subTest(reference=reference), patch("urllib.request.urlopen", side_effect=AssertionError("网络获取禁止")) as network:
                self.schema["properties"]["service"] = {"$ref": reference}
                if reference == "#":
                    self.schema = {"$schema": self.schema["$schema"], "$ref": "#"}
                self.write_schema()
                self.rejected("schema_reference")
                network.assert_not_called()

    def test_invalid_schema_and_unsupported_dialect_are_not_success(self):
        self.schema["required"] = "secret-canary"
        self.write_schema()
        report = self.rejected("schema_invalid")
        self.assertNotIn("secret-canary", json.dumps(report))
        self.schema.pop("required")
        self.schema["$schema"] = "https://unknown.invalid/schema"
        self.write_schema()
        self.rejected("schema_dialect")

    def test_supported_format_is_checked_unsupported_format_fails_closed(self):
        self.schema["$defs"]["service"]["properties"]["name"]["format"] = "ipv4"
        self.write_schema()
        self.rejected("configuration_invalid")
        self.schema["$defs"]["service"]["properties"]["name"]["format"] = "vendor-format"
        self.write_schema()
        self.rejected("unsupported_format")

    def test_formal_schema_source_requires_traceable_reference(self):
        self.data["schema_source"]["kind"] = "target_system"
        self.rejected("schema_source")
        self.data["schema_source"]["reference"] = "演示合同 v1；测试夹具"
        self.assertEqual("target_system", self.run_skill()["schema_source"]["kind"])

    def test_schema_and_document_outside_mount_or_parent_escape_are_rejected(self):
        outside = self.root / "outside.json"
        outside.write_text(json.dumps(self.schema), encoding="utf-8")
        for source in (outside.as_posix(), (self.skill.INPUT_DATA / "../data/schema.json").as_posix(), "schema.json"):
            with self.subTest(source=source):
                self.data["schema_file"] = source
                self.rejected("input_path")

    def test_document_and_file_are_exclusive_and_legacy_required_keys_rejected(self):
        self.data["document_file"] = self.schema_file.as_posix()
        self.rejected("document_input")
        self.data.pop("document_file")
        self.data["required_keys"] = ["service.name"]
        self.rejected("control_input")

    def test_invalid_zip_type_is_rejected_before_any_file_is_written(self):
        self.data["bundle_zip"] = "true"
        self.rejected("control_input")

    def test_control_field_types_fail_with_safe_reports(self):
        self.data["format"] = ["secret-canary"]
        report = self.rejected("control_input")
        self.assertNotIn("secret-canary", json.dumps(report))
        self.data["format"] = "yaml"
        self.data["schema_source"]["kind"] = ["secret-canary"]
        report = self.rejected("schema_source")
        self.assertNotIn("secret-canary", json.dumps(report))

    def test_yaml_duplicate_non_json_tags_merge_and_nonfinite_are_rejected(self):
        examples = [("name: secret-canary\nname: other\n", "duplicate_key"),
                    ("1: secret-canary\n", "non_json_value"),
                    ("name: 2026-10-08\n", "non_json_value"),
                    ("name: .nan\n", "non_json_value"),
                    ("a: &base {name: value}\nb: {<<: *base}\n", "yaml_merge"),
                    ("name: !!python/object:unsafe {}\n", "input_parse")]
        self.data.pop("document")
        self.data["document_file"] = (self.skill.INPUT_DATA / "source.yaml").as_posix()
        for source, code in examples:
            with self.subTest(code=code):
                Path(self.data["document_file"]).write_text(source, encoding="utf-8")
                report = self.rejected(code)
                self.assertNotIn("secret-canary", json.dumps(report))

    def test_duplicate_json_and_nonfinite_do_not_leak_input(self):
        source = self.file_input(".json", '{"name":"secret-canary","name":"other"}')
        report = self.rejected("duplicate_key")
        self.assertNotIn("secret-canary", json.dumps(report))
        source.write_text('{"name":NaN}', encoding="utf-8")
        self.rejected("non_json_value")

    def test_duplicate_control_mount_and_package_example_are_not_selected(self):
        artifact = self.skill.INPUT_ARTIFACTS / "other"
        artifact.mkdir()
        (artifact / "configuration.json").write_text("{}", encoding="utf-8")
        self.rejected("control_input")

    def test_schema_mount_cannot_follow_symlink_outside_input(self):
        outside = self.root / "outside.json"
        outside.write_text(json.dumps(self.schema), encoding="utf-8")
        link = self.skill.INPUT_DATA / "link.json"
        try:
            link.symlink_to(outside)
        except OSError:
            self.skipTest("当前 Windows 用户未启用创建符号链接权限")
        self.data["schema_file"] = link.as_posix()
        self.rejected("input_path")


if __name__ == "__main__":
    unittest.main(verbosity=2)
