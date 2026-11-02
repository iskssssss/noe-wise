"""验证官方 Skill 只从显式输入目录定位单个输入文件。"""
from __future__ import annotations

import json
import importlib.util
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SKILLS_ROOT = REPOSITORY_ROOT / "official-skills"
PYTHON_SKILLS = {
    "code-delivery": ("scripts/package_delivery.py", "delivery.json"),
    "configuration": ("scripts/generate_configuration.py", "configuration.json"),
    "document": ("scripts/generate_document.py", "document.json"),
    "spreadsheet": ("scripts/generate_spreadsheet.py", "spreadsheet.json"),
    "web-app": ("scripts/generate_web_app.py", "site.json"),
}


def load_locator(script: Path, data_root: Path, artifacts_root: Path):
    """使用当前入口及其路径边界检查，避免只摘取函数而漏掉真实依赖。"""
    namespace = load_python_skill(script, data_root, artifacts_root, data_root.parent / "output")
    return namespace["locate_input"]


def load_python_skill(script: Path, data_root: Path, artifacts_root: Path, output_root: Path):
    """载入 Python Skill 并替换固定挂载点，不触发脚本入口。"""
    namespace = {"__name__": "official_skill_test", "__file__": str(script)}
    exec(compile(script.read_text(encoding="utf-8"), str(script), "exec"), namespace)
    namespace["INPUT_DATA"] = data_root
    namespace["INPUT_ARTIFACTS"] = artifacts_root
    namespace["OUTPUT"] = output_root
    return namespace


class OfficialSkillInputLocationTest(unittest.TestCase):
    def test_python_skills_reject_missing_and_multiple_and_ignore_examples(self):
        for skill, (relative_script, filename) in PYTHON_SKILLS.items():
            with self.subTest(skill=skill), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                data = root / "input/data"
                artifacts = root / "input/artifacts"
                example = root / "input/examples" / filename
                example.parent.mkdir(parents=True)
                example.write_text("example", encoding="utf-8")
                locator = load_locator(SKILLS_ROOT / skill / relative_script, data, artifacts)

                def locate():
                    # configuration控制文件身份由正式入口固定，不接收任意文件名。
                    return locator() if skill == "configuration" else locator(filename)

                failure_type = ValueError if skill == "configuration" else FileNotFoundError
                with self.assertRaises(failure_type) as missing:
                    locate()
                if skill == "configuration":
                    self.assertEqual("control_input", missing.exception.report["code"])

                real = data / filename
                real.parent.mkdir(parents=True)
                real.write_text("real", encoding="utf-8")
                self.assertEqual(real, locate())

                artifact = artifacts / "artifact-1" / filename
                artifact.parent.mkdir(parents=True)
                artifact.write_text("artifact", encoding="utf-8")
                with self.assertRaises(ValueError if skill == "web-app" else failure_type) as duplicate:
                    locate()
                if skill == "configuration":
                    self.assertEqual("control_input", duplicate.exception.report["code"])

    def test_presentation_reader_rejects_missing_and_multiple_and_ignores_examples(self):
        node = shutil.which("node")
        if node is None:
            self.skipTest("Node.js 不可用")
        script = SKILLS_ROOT / "presentation/scripts/generate_presentation.mjs"
        source = script.read_text(encoding="utf-8")
        start = source.index("export async function readPresentationInput")
        end = source.index("async function resolveImage", start)
        reader = source[start:end].replace("export ", "")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).as_posix()
            runner = Path(directory) / "verify-reader.mjs"
            runner.write_text(
                "import { mkdir, readFile, readdir, realpath, stat, writeFile } from 'node:fs/promises';\n"
                + "import { join, relative, sep, isAbsolute } from 'node:path';\n"
                + reader
                + "\nconst filename = 'presentation.json';\n"
                + "const expectFailure = async () => { let failed = false; try { await readPresentationInput('input'); } catch { failed = true; } if (!failed) throw new Error('expected failure'); };\n"
                + "await mkdir('input/examples', { recursive: true });\n"
                + "await writeFile('input/examples/' + filename, 'example');\n"
                + "await expectFailure();\n"
                + "await mkdir('input/data', { recursive: true });\n"
                + "await writeFile('input/data/' + filename, JSON.stringify({title:'real'}));\n"
                + "if ((await readPresentationInput('input')).title !== 'real') throw new Error('did not use data input');\n"
                + "await mkdir('input/artifacts/artifact-1', { recursive: true });\n"
                + "await writeFile('input/artifacts/artifact-1/' + filename, 'artifact');\n"
                + "await expectFailure();\n",
                encoding="utf-8",
            )
            completed = subprocess.run([node, runner.name], cwd=directory, text=True, capture_output=True, check=False)
            self.assertEqual(0, completed.returncode, completed.stderr)

    def test_document_defaults_to_markdown_without_bundle(self):
        script = SKILLS_ROOT / "document/scripts/generate_document.py"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data_root = root / "input/data"
            data_root.mkdir(parents=True)
            (data_root / "document.json").write_text(json.dumps({
                "title": "周报", "subtitle": "第 1 周",
                "sections": [{"heading": "进展", "blocks": [{"type":"paragraph", "text":"完成实现"}, {"type":"bullets", "items":["验证通过"]}]}],
            }, ensure_ascii=False), encoding="utf-8")
            output = root / "output"
            module = load_python_skill(script, data_root, root / "input/artifacts", output)
            module["main"]()
            self.assertEqual("# 周报\n\n第 1 周\n\n## 进展\n\n完成实现\n\n- 验证通过\n", (output / "document.md").read_text(encoding="utf-8"))
            self.assertFalse((output / "document.docx").exists())
            self.assertFalse((output / "document.zip").exists())

    def test_document_pdf_request_uses_docx_candidate_without_markdown(self):
        script = SKILLS_ROOT / "document/scripts/generate_document.py"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data_root = root / "input/data"
            data_root.mkdir(parents=True)
            (data_root / "document.json").write_text(json.dumps({
                "title": "导出", "format": "pdf",
                "sections": [{"heading": "正文", "blocks": [{"type":"paragraph", "text":"内容"}]}],
            }, ensure_ascii=False), encoding="utf-8")
            output = root / "output"
            module = load_python_skill(script, data_root, root / "input/artifacts", output)
            module["generate_docx"] = lambda target, *_: target.write_text("DOCX 候选", encoding="utf-8")
            module["main"]()
            self.assertTrue((output / "document.docx").exists())
            self.assertFalse((output / "document.md").exists())

    def test_web_app_defaults_to_self_contained_html_without_bundle(self):
        script = SKILLS_ROOT / "web-app/scripts/generate_web_app.py"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data_root = root / "input/data"
            data_root.mkdir(parents=True)
            (data_root / "site.json").write_text(json.dumps({
                "html": '<!doctype html><html lang="zh-CN"><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>项目页</title><style>body{color:#123}</style></head><body><article>进行中</article></body></html>',
            }, ensure_ascii=False), encoding="utf-8")
            output = root / "output"
            module = load_python_skill(script, data_root, root / "input/artifacts", output)
            module["main"]()
            page = (output / "index.html").read_text(encoding="utf-8")
            self.assertIn("<style>", page)
            self.assertIn("<article>进行中</article>", page)
            self.assertNotIn("styles.css", page)
            self.assertNotIn("app.js", page)
            self.assertNotIn("data.json", page)
            self.assertNotIn("fetch(", page)
            self.assertEqual({"index.html"}, {path.name for path in output.iterdir()})

    def test_code_delivery_only_creates_zip_when_explicitly_requested(self):
        script = SKILLS_ROOT / "code-delivery/scripts/package_delivery.py"
        payload = {
            "package_name": "hello",
            "files": [{"path": "src/hello.py", "content": "print('hello')\n"}],
            "verification": [{"check": "语法", "result": "passed", "evidence": "已执行"}],
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data_root = root / "input/data"
            data_root.mkdir(parents=True)
            (data_root / "delivery.json").write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            output = root / "output"
            module = load_python_skill(script, data_root, root / "input/artifacts", output)
            module["main"]()
            self.assertEqual("print('hello')\n", (output / "src/hello.py").read_text(encoding="utf-8"))
            self.assertFalse((output / "source-package.zip").exists())
            self.assertFalse((output / "manifest.json").exists())

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data_root = root / "input/data"
            data_root.mkdir(parents=True)
            payload["bundle_zip"] = True
            (data_root / "delivery.json").write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            output = root / "output"
            module = load_python_skill(script, data_root, root / "input/artifacts", output)
            module["main"]()
            with zipfile.ZipFile(output / "hello.zip") as archive:
                self.assertEqual(["manifest.json", "src/hello.py"], archive.namelist())
                manifest = json.loads(archive.read("manifest.json"))
                self.assertEqual("src/hello.py", manifest["files"][0]["path"])

    def test_code_delivery_package_name_controls_real_zip_and_rejects_unsafe_collisions(self):
        script = SKILLS_ROOT / "code-delivery/scripts/package_delivery.py"
        for package_name in [None, "calculator", "中文源码"]:
            with self.subTest(package_name=package_name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                data_root = root / "input/data"
                data_root.mkdir(parents=True)
                payload = {"files": [{"path": "calc.py", "content": "def add(a, b):\n    return a + b\n"}],
                           "verification": [{"check": "existing", "result": "passed", "evidence": "original evidence"}],
                           "bundle_zip": True}
                if package_name is not None: payload["package_name"] = package_name
                (data_root / "delivery.json").write_text(json.dumps(payload), encoding="utf-8")
                output = root / "output"
                load_python_skill(script, data_root, root / "input/artifacts", output)["main"]()
                expected = (package_name or "source-package") + ".zip"
                self.assertEqual([expected], [p.name for p in output.glob("*.zip")])
                with zipfile.ZipFile(output / expected) as archive:
                    self.assertIsNone(archive.testzip())
                    self.assertEqual(payload["files"][0]["content"].encode(), archive.read("calc.py"))
                    manifest = json.loads(archive.read("manifest.json"))
                    self.assertTrue(manifest["includes_originals"])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module = load_python_skill(script, root / "input/data", root / "input/artifacts", root / "output")
            for name in ["", ".", "..", "../bad", "/bad", "a/b", "a\\b", "C:bad", "bad\n", "CON", "con.txt",
                         "bad.", "bad ", "Ａ", "already.zip", "a" * 197, None, 1]:
                with self.subTest(unsafe=name), self.assertRaises(ValueError):
                    module["bundle_filename"](name)
            data_root = root / "input/data"
            data_root.mkdir(parents=True)
            for source in ["calculator.zip", "CALCULATOR.ZIP", "calculator.zip/source.py"]:
                payload = {"package_name": "calculator", "bundle_zip": True,
                           "files": [{"path": source, "content": "must not be overwritten"}],
                           "verification": [{"check": "existing", "result": "passed", "evidence": "original"}]}
                (data_root / "delivery.json").write_text(json.dumps(payload), encoding="utf-8")
                with self.subTest(collision=source), self.assertRaises(ValueError): module["main"]()
                self.assertFalse((root / "output").exists())

    def test_skill_workflows_and_manifests_describe_default_delivery(self):
        skills = ("document", "spreadsheet", "presentation", "web-app", "code-delivery", "configuration")
        for skill in skills:
            with self.subTest(skill=skill):
                content = (SKILLS_ROOT / skill / "SKILL.md").read_text(encoding="utf-8")
                # 明确批准版本，不能从被测包自身计算期望版本掩盖漂移。
                expected_version = {"document": "2.1.6", "spreadsheet": "2.0.5", "presentation": "2.1.8",
                                    "web-app": "2.1.8", "code-delivery": "1.1.5", "configuration": "2.0.5"}[skill]
                self.assertIn("version: " + expected_version, content)
                for obsolete in ("plan_task_outputs", "decision_source", "source_message_id"):
                    self.assertNotIn(obsolete, content)
                # 官方包独立于宿主提示，不要求每包重复平台成果冻结协议。
                manifest = json.loads((SKILLS_ROOT / skill / "manifest.json").read_text(encoding="utf-8"))
                entry = manifest["entry"]["script_path"]
                self.assertTrue((SKILLS_ROOT / skill / entry).is_file())
                self.assertIn(entry, content)
        document_outputs = json.loads((SKILLS_ROOT / "document/manifest.json").read_text(encoding="utf-8"))["outputs"]
        web_outputs = json.loads((SKILLS_ROOT / "web-app/manifest.json").read_text(encoding="utf-8"))["outputs"]
        self.assertIn("document.md", document_outputs["markdown"])
        self.assertEqual(["index.html"], web_outputs["html"])

    def test_configuration_existing_yaml_delivery_still_matches_user_values(self):
        import yaml
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            data=root/'input/data'
            data.mkdir(parents=True)
            payload=json.loads((SKILLS_ROOT/'configuration/examples/configuration.json').read_text(encoding='utf-8'))
            # example仅说明沙箱路径；真实fixture必须显式挂载当前Schema而非漏掉它。
            schema = data / 'schema.json'
            shutil.copyfile(SKILLS_ROOT/'configuration/examples/schema.json', schema)
            payload['schema_file'] = str(schema.resolve())
            (data/'configuration.json').write_text(json.dumps(payload),encoding='utf-8')
            output=root/'output'
            module=load_python_skill(SKILLS_ROOT/'configuration/scripts/generate_configuration.py',data,root/'input/artifacts',output)
            module['main']()
            self.assertEqual(payload['document'],yaml.safe_load((output/'configuration.yaml').read_text(encoding='utf-8')))
            self.assertEqual({'configuration.yaml', 'result.json'}, {p.name for p in output.iterdir()})
            self.assertIsInstance(json.loads((output/'result.json').read_text(encoding='utf-8'))['results'], list)

    def test_spreadsheet_preserves_long_chinese_requirements_and_readable_layout(self):
        from openpyxl import load_workbook
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = root / "input/data"
            data.mkdir(parents=True)
            text = "采用企业本地部署，基础设施由企业提供，需验收网络隔离、权限配置与备份恢复。"
            (data / "spreadsheet.json").write_text(json.dumps({
                "sheets": [{"name":"需求", "columns":[{"key":"编号","title":"编号","type":"text","width":12}, {"key":"需求描述","title":"需求描述","type":"text","width":38}, {"key":"状态","title":"状态","type":"text","width":14}],
                            "rows": [{"编号": "REQ-01", "需求描述": text, "状态": "待确认"}]}]
            }, ensure_ascii=False), encoding="utf-8")
            output = root / "output"
            module = load_python_skill(SKILLS_ROOT / "spreadsheet/scripts/generate_spreadsheet.py",
                                       data, root / "input/artifacts", output)
            module["main"]()
            workbook = load_workbook(output / "report.xlsx")
            sheet = workbook.active
            self.assertEqual(text, sheet["B2"].value)
            self.assertGreater(sheet.column_dimensions["B"].width, sheet.column_dimensions["A"].width)
            self.assertTrue(sheet["B2"].alignment.wrap_text)
            self.assertGreater(sheet.row_dimensions[2].height, 30)
            self.assertEqual(1, sheet.page_setup.fitToWidth)
            self.assertEqual("landscape", sheet.page_setup.orientation)
            self.assertEqual("A2", sheet.freeze_panes)
            workbook.close()

    @unittest.skipUnless(importlib.util.find_spec("docx"), "需要已安装 python-docx")
    def test_word_template_produces_declared_docx_instead_of_default_markdown(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = root / "input/data"
            data.mkdir(parents=True)
            shutil.copyfile(SKILLS_ROOT / "document/examples/document-word.json", data / "document.json")
            output = root / "output"
            module = load_python_skill(SKILLS_ROOT / "document/scripts/generate_document.py",
                                       data, root / "input/artifacts", output)
            module["main"]()
            self.assertFalse((output / "document.md").exists())
            with zipfile.ZipFile(output / "document.docx") as package:
                self.assertIsNone(package.testzip())
                self.assertIn("项目方案示例", package.read("word/document.xml").decode("utf-8"))


if __name__ == "__main__":
    unittest.main()
