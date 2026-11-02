"""文档 Skill 的真实文件生成、OOXML 修改及输入拒绝回归。"""
import base64
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile, ZIP_DEFLATED

from docx import Document
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Pt

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / "official-skills/document"
SCRIPT = PACKAGE / "scripts/generate_document.py"
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")


class DocumentSkillTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="document-skill-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        spec = importlib.util.spec_from_file_location("document_skill", SCRIPT)
        self.skill = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.skill)
        self.skill.INPUT_DATA = self.root / "input/data"
        self.skill.INPUT_ARTIFACTS = self.root / "input/artifacts"
        self.skill.TEMPLATES = self.root / "package/templates"
        self.skill.OUTPUT = self.root / "workspace/output"
        for directory in (self.skill.INPUT_DATA, self.skill.INPUT_ARTIFACTS, self.skill.TEMPLATES):
            directory.mkdir(parents=True)

    def run_skill(self, data):
        (self.skill.INPUT_DATA / "document.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            self.skill.main()
        return json.loads(stdout.getvalue())

    def word_data(self):
        return json.loads((PACKAGE / "examples/document-word.json").read_text(encoding="utf-8"))

    def source_document(self):
        source = self.skill.INPUT_ARTIFACTS / "source/document.docx"
        source.parent.mkdir()
        document = Document()
        document.styles["Normal"].font.size = Pt(12)
        paragraph = document.add_paragraph(style="Heading 2")
        paragraph.add_run("合同").bold = True
        paragraph.add_run("金额").italic = True
        paragraph.add_run("待确认；其他文字保留")
        document.add_paragraph("原文必须保留")
        table = document.add_table(rows=2, cols=2)
        table.style = "Table Grid"
        table.cell(0, 0).text, table.cell(0, 1).text = "事项", "状态"
        table.cell(1, 0).text, table.cell(1, 1).text = "验收", "待确认"
        document.sections[0].header.paragraphs[0].text = "示例公司"
        image = self.skill.INPUT_DATA / "image.png"
        image.write_bytes(PNG)
        document.add_picture(str(image))
        document.save(source)
        return source

    def rewrite_package(self, source, transform, additions=None):
        with ZipFile(source) as archive:
            contents = {entry.filename: (entry, archive.read(entry.filename)) for entry in archive.infolist()}
        with ZipFile(source, "w") as result:
            for name, (entry, payload) in contents.items():
                result.writestr(entry, transform(name, payload))
            for name, payload in (additions or {}).items():
                result.writestr(name, payload)

    def test_explicit_output_name_is_actual_file_position_independent_of_title(self):
        data = self.word_data()
        data["output_name"] = "expense-review.docx"
        result = self.run_skill(data)
        self.assertEqual("expense-review.docx", result["output"])
        self.assertTrue((self.skill.OUTPUT / "expense-review.docx").is_file())
        self.assertFalse((self.skill.OUTPUT / "document.docx").exists())
        self.assertEqual(data["title"], Document(self.skill.OUTPUT / "expense-review.docx").paragraphs[0].text)

    def test_output_name_checks_extension_and_safe_basename(self):
        self.assertEqual("summary.md", self.skill.output_filename({"output_name": "summary.md"}, "markdown"))
        for name in ("../x.docx", "a/x.docx", "x.pdf", "CON.docx", "x.docx\n", "Ａ.docx", "报" * 84 + ".docx", "report\u0080.docx", "report\u0085.docx", "report\u009f.docx"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.skill.output_filename({"output_name": name}, "docx")

    def test_formal_docx_structure_fonts_and_pagination(self):
        data = self.word_data()
        data["sections"][0]["blocks"][1]["rows"] += [[f"项目 {index}", f"说明 {index}"] for index in range(60)]
        result = self.run_skill(data)
        self.assertEqual("docx", result["candidate_format"])
        document = Document(self.skill.OUTPUT / "document.docx")
        self.assertEqual("项目方案示例", document.paragraphs[0].text)
        self.assertEqual("Heading 2", document.paragraphs[-2].style.name)
        self.assertTrue(next(p for p in document.paragraphs if p.text == "待确认事项").paragraph_format.page_break_before)
        self.assertEqual(63, len(document.tables[0].rows))
        normal = document.styles["Normal"]
        self.assertEqual("Noto Sans CJK SC", normal.element.rPr.rFonts.get(qn("w:eastAsia")))
        self.assertEqual(11, normal.font.size.pt)
        self.assertIn("项目方案示例", document.sections[0].header.paragraphs[0].text)
        with ZipFile(self.skill.OUTPUT / "document.docx") as archive:
            root = self.skill.parse_xml(archive.read("word/document.xml"))
            self.assertIsNotNone(root.find(".//w:tblHeader", self.skill.NS))
            self.assertEqual(63, len(root.findall(".//w:cantSplit", self.skill.NS)))
            footer = self.skill.parse_xml(archive.read("word/footer1.xml"))
            self.assertEqual("PAGE", footer.find(".//w:fldSimple", self.skill.NS).get(qn("w:instr")))

    def test_markdown_table_image_assets_and_explicit_zip(self):
        image = self.skill.INPUT_DATA / "image.png"
        image.write_bytes(PNG)
        data = json.loads((PACKAGE / "examples/document.json").read_text(encoding="utf-8"))
        data["bundle_zip"] = True
        data["sections"][0]["blocks"].append({"type": "image", "path": image.as_posix(), "alt": "图示", "caption": "说明"})
        data["sections"][0]["blocks"][2]["rows"].append(["字段|含分隔符", "<script>"])
        result = self.run_skill(data)
        self.assertEqual(["assets/image-1.png"], result["assets"])
        markdown = (self.skill.OUTPUT / "document.md").read_text(encoding="utf-8")
        self.assertIn("字段\\|含分隔符", markdown)
        self.assertIn("\\<script\\>", markdown)
        self.assertIn("![图示](assets/image-1.png)", markdown)
        with ZipFile(self.skill.OUTPUT / "document.zip") as bundle:
            self.assertEqual(["document.md", "assets/image-1.png"], bundle.namelist())
            self.assertEqual(PNG, bundle.read("assets/image-1.png"))

    def test_docx_embeds_image_alt_and_caption(self):
        image = self.skill.INPUT_DATA / "image.png"
        image.write_bytes(PNG)
        data = self.word_data()
        data["sections"][0]["blocks"].append({"type": "image", "path": image.as_posix(), "alt": "示意图", "width_mm": 50, "caption": "图片说明"})
        self.run_skill(data)
        document = Document(self.skill.OUTPUT / "document.docx")
        self.assertEqual(1, len(document.inline_shapes))
        self.assertEqual("示意图", document.inline_shapes[0]._inline.docPr.get("descr"))
        self.assertIn("图片说明", [paragraph.text for paragraph in document.paragraphs])

    def test_modify_preserves_styles_media_relationships_and_original(self):
        source = self.source_document()
        original = source.read_bytes()
        self.run_skill({"action": "modify", "format": "docx", "source_path": source.as_posix(), "operations": [
            {"type": "replace_text", "find": "合同金额待确认", "replace": "合同金额已确认", "expected_count": 1},
            {"type": "set_table_cell", "table": 0, "row": 1, "cell": 1, "expected_text": "待确认", "text": "已安排"}
        ]})
        self.assertEqual(original, source.read_bytes())
        modified = self.skill.OUTPUT / "document.docx"
        document = Document(modified)
        self.assertEqual("合同金额已确认；其他文字保留", document.paragraphs[0].text)
        self.assertEqual("原文必须保留", document.paragraphs[1].text)
        self.assertEqual("Heading 2", document.paragraphs[0].style.name)
        self.assertTrue(document.paragraphs[0].runs[0].bold)
        self.assertTrue(document.paragraphs[0].runs[1].italic)
        self.assertEqual("已安排", document.tables[0].cell(1, 1).text)
        with ZipFile(source) as before, ZipFile(modified) as after:
            self.assertEqual(before.namelist(), after.namelist())
            for name in before.namelist():
                if name != "word/document.xml":
                    self.assertEqual(before.read(name), after.read(name), name)

    def test_header_only_modify_preserves_body_bytes(self):
        source = self.source_document()
        self.run_skill({"action": "modify", "format": "docx", "source_path": source.as_posix(), "operations": [
            {"type": "replace_text", "scope": "headers", "find": "示例公司", "replace": "新公司", "expected_count": 1}
        ]})
        with ZipFile(source) as before, ZipFile(self.skill.OUTPUT / "document.docx") as after:
            for name in before.namelist():
                if name != "word/header1.xml":
                    self.assertEqual(before.read(name), after.read(name), name)

    def test_repeated_cross_run_replacement_preserves_all_matches(self):
        paragraph = Document().add_paragraph()
        for text in ["目", "标 目", "标 目", "标"]:
            paragraph.add_run(text)
        self.assertEqual(3, self.skill.replace_in_paragraph(paragraph._p, "目标", "替换后的新值"))
        self.assertEqual("替换后的新值 替换后的新值 替换后的新值", paragraph.text)

    def test_modify_requires_exact_count_and_no_candidate_on_failure(self):
        source = self.source_document()
        with self.assertRaisesRegex(ValueError, "实际匹配 1 次"):
            self.run_skill({"action": "modify", "format": "docx", "source_path": source.as_posix(), "operations": [
                {"type": "replace_text", "find": "合同金额待确认", "replace": "更新", "expected_count": 2}
            ]})
        self.assertFalse((self.skill.OUTPUT / "document.docx").exists())

    def test_inspect_returns_readable_text_without_candidate(self):
        source = self.source_document()
        self.run_skill({"action": "inspect", "source_path": source.as_posix()})
        public = json.loads((self.skill.OUTPUT / "result.json").read_text(encoding="utf-8"))
        details = json.loads(public["results"][0]["text"])
        self.assertEqual("合同金额待确认；其他文字保留", details["paragraphs"][0]["text"])
        self.assertEqual(["验收", "待确认"], details["tables"][0]["rows"][1])
        self.assertFalse((self.skill.OUTPUT / "document.docx").exists())

    def test_template_append_keeps_existing_content_and_styles(self):
        source = self.source_document()
        template = self.skill.TEMPLATES / "report.docx"
        template.write_bytes(source.read_bytes())
        data = self.word_data()
        data.pop("styles")
        data.pop("page")
        data["sections"][0]["blocks"][1].pop("column_widths_mm")
        data["template_path"] = "templates/report.docx"
        self.run_skill(data)
        document = Document(self.skill.OUTPUT / "document.docx")
        self.assertEqual("合同金额待确认；其他文字保留", document.paragraphs[0].text)
        self.assertEqual(12, document.styles["Normal"].font.size.pt)
        self.assertEqual(2, len(document.tables))
        self.assertEqual(1, len(document.inline_shapes))

    def test_pdf_only_creates_docx_intermediate(self):
        data = self.word_data()
        data["format"] = "pdf"
        result = self.run_skill(data)
        self.assertEqual("pdf", result["format"])
        self.assertEqual("docx", result["candidate_format"])
        self.assertEqual(["document.docx"], [item.name for item in self.skill.OUTPUT.iterdir()])

    def test_template_partial_configuration_keeps_page_and_linked_footer(self):
        source = self.source_document()
        document = Document(source)
        document.styles["Normal"].font.name = "Existing Font"
        document.add_section()
        document.save(source)
        original = Document(source)
        data = self.word_data()
        data["template_path"] = source.as_posix()
        data["styles"] = {"body_size_pt": 13}
        data["page"] = {"page_numbers": True}
        data["sections"][0]["blocks"][1].pop("column_widths_mm")
        self.run_skill(data)
        output = self.skill.OUTPUT / "document.docx"
        modified = Document(output)
        self.assertEqual("Existing Font", modified.styles["Normal"].font.name)
        self.assertEqual(13, modified.styles["Normal"].font.size.pt)
        self.assertEqual(original.sections[0].page_width, modified.sections[0].page_width)
        self.assertEqual(original.sections[0].left_margin, modified.sections[0].left_margin)
        with ZipFile(output) as archive:
            footer = self.skill.parse_xml(archive.read("word/footer1.xml"))
            self.assertEqual(1, len(footer.findall(".//w:fldSimple", self.skill.NS)))

    def test_rejects_input_escape_remote_paths_and_missing_ambiguous_input(self):
        for value in ("https://example.com/data.docx", "templates/../report.docx", (self.root / "outside.docx").as_posix(), "C:\\host\\report.docx"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.skill.resolve_input(value, "source", allow_template=True)
        with self.assertRaises(FileNotFoundError):
            self.skill.locate_input("document.json")
        (self.skill.INPUT_DATA / "document.json").write_text("{}", encoding="utf-8")
        second = self.skill.INPUT_ARTIFACTS / "other"
        second.mkdir()
        (second / "document.json").write_text("{}", encoding="utf-8")
        with self.assertRaisesRegex(FileNotFoundError, "且只能提供一个"):
            self.skill.locate_input("document.json")

    def test_rejects_symlink_escape_when_platform_allows_symlinks(self):
        outside = self.root / "outside.docx"
        outside.write_bytes(b"outside")
        link = self.skill.INPUT_DATA / "linked.docx"
        try:
            link.symlink_to(outside)
        except OSError as error:
            self.skipTest(f"宿主未允许符号链接创建: {error}")
        with self.assertRaisesRegex(ValueError, "超出允许输入根"):
            self.skill.resolve_input(link.as_posix(), "source")

    def test_rejects_macro_objects_and_unsupported_structure(self):
        source = self.source_document()
        baseline = source.read_bytes()
        for addition in ("word/vbaProject.bin", "word/embeddings/object.bin"):
            source.write_bytes(baseline)
            self.rewrite_package(source, lambda name, value: value, {addition: b"test"})
            with self.subTest(addition=addition), self.assertRaisesRegex(ValueError, "宏、嵌入对象"):
                self.skill.check_docx(source)
        for tag in ("ins", "sdt", "altChunk"):
            source.write_bytes(baseline)
            def add_element(name, payload):
                if name == "word/document.xml":
                    root = self.skill.parse_xml(payload)
                    root.find("w:body", self.skill.NS).append(OxmlElement(f"w:{tag}"))
                    from lxml import etree
                    return etree.tostring(root)
                return payload
            self.rewrite_package(source, add_element)
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "修订追踪"):
                self.skill.check_docx(source)

    def test_rejects_complex_fields_and_external_media(self):
        source = self.source_document()
        baseline = source.read_bytes()
        def add_field(name, payload):
            if name == "word/document.xml":
                root = self.skill.parse_xml(payload)
                field = OxmlElement("w:fldSimple")
                field.set(qn("w:instr"), "INCLUDETEXT https://example.com/private")
                root.find(".//w:p", self.skill.NS).append(field)
                from lxml import etree
                return etree.tostring(root)
            return payload
        self.rewrite_package(source, add_field)
        with self.assertRaisesRegex(ValueError, "复杂域"):
            self.skill.check_docx(source)
        source.write_bytes(baseline)
        def external_media(name, payload):
            if name == "word/_rels/document.xml.rels":
                root = self.skill.parse_xml(payload)
                from lxml import etree
                etree.SubElement(root, "{http://schemas.openxmlformats.org/package/2006/relationships}Relationship", Id="rId100", Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image", TargetMode="External", Target="https://example.com/picture.png")
                return etree.tostring(root)
            return payload
        self.rewrite_package(source, external_media)
        with self.assertRaisesRegex(ValueError, "外链媒体"):
            self.skill.check_docx(source)

    def test_rejects_merged_cells_and_stale_cell_text(self):
        source = self.source_document()
        operation = {"type": "set_table_cell", "table": 0, "row": 1, "cell": 1, "expected_text": "不匹配", "text": "更新"}
        with self.assertRaisesRegex(ValueError, "原文与 expected_text 不符"):
            self.run_skill({"action": "modify", "format": "docx", "source_path": source.as_posix(), "operations": [operation]})
        document = Document(source)
        document.tables[0].cell(1, 0).merge(document.tables[0].cell(1, 1))
        document.save(source)
        operation["cell"] = 0
        with self.assertRaisesRegex(ValueError, "合并单元格"):
            self.run_skill({"action": "modify", "format": "docx", "source_path": source.as_posix(), "operations": [operation]})

    def test_rejects_target_with_inline_picture_without_losing_original(self):
        source = self.source_document()
        document = Document(source)
        document.paragraphs[0].add_run().add_picture(str(self.skill.INPUT_DATA / "image.png"))
        document.save(source)
        original = source.read_bytes()
        with self.assertRaisesRegex(ValueError, "非文本 run"):
            self.run_skill({"action": "modify", "format": "docx", "source_path": source.as_posix(), "operations": [
                {"type": "replace_text", "find": "合同", "replace": "更新", "expected_count": 1}
            ]})
        self.assertEqual(original, source.read_bytes())
        self.assertFalse((self.skill.OUTPUT / "document.docx").exists())

    def test_rejects_zip_traversal_duplicate_and_decompression_bomb(self):
        source = self.source_document()
        baseline = source.read_bytes()
        for name, payload in (("../outside.bin", b"x"), ("word/bomb.bin", b"x" * (2 * 1024 * 1024))):
            source.write_bytes(baseline)
            with ZipFile(source, "a", compression=ZIP_DEFLATED) as archive:
                archive.writestr(name, payload)
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.skill.check_docx(source)
        source.write_bytes(baseline)
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with ZipFile(source, "a") as archive:
                archive.writestr("word/document.xml", b"duplicate")
        with self.assertRaisesRegex(ValueError, "重名项"):
            self.skill.check_docx(source)

    def test_rejects_dtd_in_utf8_and_utf16(self):
        for encoding in ("utf-8", "utf-16"):
            xml = '<?xml version="1.0" encoding="' + encoding + '"?><!DOCTYPE x [<!ENTITY e "hidden">]><x>&e;</x>'
            with self.subTest(encoding=encoding), self.assertRaisesRegex(ValueError, "DTD"):
                self.skill.parse_xml(xml.encode(encoding))

    def test_validation_rejects_bad_blocks_widths_and_dependency_formats(self):
        data = self.word_data()
        invalid_blocks = [{"type": "paragraph", "text": "", "runs": [{"text": "x"}]},
                          {"type": "table", "headers": ["A", "B"], "rows": [["only one"]]},
                          {"type": "unsupported"}, {"type": "paragraph", "text": "text", "keep_together": "true"}]
        for block in invalid_blocks:
            with self.subTest(block=block), self.assertRaises(ValueError):
                self.skill.validate_block(block)
        wide = copy.deepcopy(data)
        wide["sections"][0]["blocks"][1]["column_widths_mm"] = [100, 100]
        with self.assertRaisesRegex(ValueError, "超过页面可用宽度"):
            self.run_skill(wide)
        invalid = self.skill.INPUT_DATA / "fake.png"
        invalid.write_bytes(b"not an image")
        with self.assertRaisesRegex(ValueError, "内容与 PNG/JPEG 格式不符"):
            self.skill.validate_block({"type": "image", "path": invalid.as_posix(), "alt": "测试"})
        data["format"], data["bundle_zip"] = "pdf", True
        with self.assertRaisesRegex(ValueError, "render_artifact"):
            self.run_skill(data)


if __name__ == "__main__":
    unittest.main()
