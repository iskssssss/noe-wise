"""在冻结Python镜像运行真实Docling转换；不下载依赖或模型，不调用远程服务。"""
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "official-skills/document-read/scripts/read_document.py"
spec = importlib.util.spec_from_file_location("document_read_skill", SCRIPT)
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)


def pdf(path, pages=1):
    """生成带真实PDF文本层和网格的确定性材料，不依赖第二个PDF解析器。"""
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>", b"", b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    references = []
    for number in range(pages):
        page_no = len(objects) + 1; references.append(page_no)
        stream = (f"BT /F1 20 Tf 72 710 Td (Quarterly report page {number + 1}) Tj ET\n"
                  "BT /F1 12 Tf 72 670 Td (Budget approval confirmed by finance.) Tj ET\n"
                  "72 620 m 440 620 l 440 530 l 72 530 l h S\n"
                  "72 590 m 440 590 l S 72 560 m 440 560 l S 270 620 m 270 530 l S\n"
                  "BT /F1 12 Tf 80 600 Td (Department) Tj 210 0 Td (Amount) Tj ET\n"
                  "BT /F1 12 Tf 80 570 Td (Engineering) Tj 210 0 Td (120) Tj ET\n"
                  "BT /F1 12 Tf 80 540 Td (Support) Tj 210 0 Td (80) Tj ET\n").encode("ascii")
        objects.extend([f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 500 760] /Resources << /Font << /F1 3 0 R >> >> /Contents {page_no + 1} 0 R >>".encode(),
                        f"<< /Length {len(stream)} >>\nstream\n".encode() + stream + b"endstream"])
    objects[1] = f"<< /Type /Pages /Count {pages} /Kids [{' '.join(f'{x} 0 R' for x in references)}] >>".encode()
    content = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"); offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(content)); content.extend(f"{index} 0 obj\n".encode() + obj + b"\nendobj\n")
    xref = len(content)
    content.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for offset in offsets[1:]: content.extend(f"{offset:010d} 00000 n \n".encode())
    content.extend(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    path.write_bytes(content)


class DocumentReadTest(unittest.TestCase):
    def run(self, result=None):
        # 转换/OCR、受管POSIX路径、固定模型与字体属于Linux运行镜像契约。
        # Windows主机运行完整实际测试类，按每个原test ID回传容器事实，不跳过或mock转换器。
        if os.name == 'nt':
            from python_runtime_acceptance import delegate_runtime_test
            return delegate_runtime_test(self, result)
        return super().run(result)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="document-read-")
        self.root = Path(self.temporary.name)
        self.original = (reader.INPUT_DATA, reader.INPUT_ARTIFACTS, reader.OUTPUT, reader.WORK)
        reader.INPUT_DATA = self.root / "data"; reader.INPUT_DATA.mkdir()
        reader.INPUT_ARTIFACTS = self.root / "artifacts"; reader.INPUT_ARTIFACTS.mkdir()
        reader.OUTPUT = self.root / "output"; reader.WORK = self.root / "work"

    def tearDown(self):
        reader.INPUT_DATA, reader.INPUT_ARTIFACTS, reader.OUTPUT, reader.WORK = self.original
        self.temporary.cleanup()

    def read(self, source, **options):
        (reader.INPUT_DATA / "document-read.json").write_text(json.dumps({"source_path": str(source), **options}), encoding="utf-8")
        reader.main()
        results = json.loads((reader.OUTPUT / "result.json").read_text(encoding="utf-8"))["results"]
        return json.loads(results[0]["text"]), results[1]["text"]

    def test_native_office_reads_real_text_and_tables_without_print_page_claim(self):
        from docx import Document
        from pptx import Presentation
        from openpyxl import Workbook
        document = Document(); document.add_heading("季度报告", 0); document.add_paragraph("预算审批已完成，禁止编造原文。")
        table = document.add_table(rows=2, cols=2)
        table.cell(0, 0).text = "部门"; table.cell(0, 1).text = "预算"
        table.cell(1, 0).text = "技术部"; table.cell(1, 1).text = "120"
        word = reader.INPUT_DATA / "report.docx"; document.save(word)
        status, text = self.read(word)
        self.assertIn("预算审批已完成", text); self.assertIn("技术部", text)
        self.assertGreaterEqual(status["tables_found"], 1); self.assertIsNone(status["pages_total"])
        self.assertEqual(list(reader.OUTPUT.iterdir()), [reader.OUTPUT / "result.json"])

        slides = Presentation(); slide = slides.slides.add_slide(slides.slide_layouts[1])
        slide.shapes.title.text = "季度报告"; slide.placeholders[1].text = "预算审批已完成"
        powerpoint = reader.INPUT_DATA / "report.pptx"; slides.save(powerpoint)
        status, text = self.read(powerpoint)
        self.assertIn("预算审批已完成", text); self.assertEqual(status["pages_total"], 1)

        workbook = Workbook(); sheet = workbook.active; sheet.append(["部门", "预算"]); sheet.append(["技术部", 120])
        excel = reader.INPUT_DATA / "report.xlsx"; workbook.save(excel)
        status, text = self.read(excel, action="export", format="json")
        self.assertIn("技术部", text); self.assertIn("120", text); self.assertGreaterEqual(status["tables_found"], 1)
        exported = json.loads((reader.OUTPUT / "document.json").read_text(encoding="utf-8"))
        self.assertIn("tables", exported["document"])

    def test_native_pdf_page_range_and_text_offsets_are_explicit(self):
        source = reader.INPUT_DATA / "native.pdf"; pdf(source, 2)
        status, text = self.read(source, pdf_pipeline="native", page_start=2, page_end=2, text_limit=256)
        self.assertIn("Quarterly report page 2", text); self.assertNotIn("page 1", text)
        self.assertEqual(status["pages_total"], 2); self.assertFalse(status["page_content_complete"])
        self.assertFalse(status["ocr_enabled"])
        self.assertEqual(status["pages_selected"], [2, 2])

    def test_structured_pdf_reconstructs_a_real_table_under_default_resource_limit(self):
        source = reader.INPUT_DATA / "layout.pdf"; pdf(source)
        status, text = self.read(source, action="export", format="markdown")
        self.assertIn("Budget approval", text); self.assertIn("Engineering", text)
        self.assertGreaterEqual(status["tables_found"], 1)
        self.assertIn("120", (reader.OUTPUT / "document.md").read_text(encoding="utf-8"))

    def test_chinese_scan_uses_local_ocr_and_no_remote_model(self):
        from PIL import Image, ImageDraw, ImageFont
        font = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 64)
        image = Image.new("RGB", (1400, 500), "white")
        draw = ImageDraw.Draw(image); draw.text((80, 70), "季度报告", fill="black", font=font)
        draw.text((80, 210), "预算审批已完成", fill="black", font=font)
        source = reader.INPUT_DATA / "scan.png"; image.save(source)
        status, text = self.read(source, tables=False, force_ocr=True)
        self.assertTrue(status["ocr_enabled"]); self.assertIn("预算", text)
        self.assertIn("审批", text)

    def test_rejects_foreign_paths_and_unbounded_sheet_before_conversion(self):
        with self.assertRaises(ValueError): reader.source_file("https://external.invalid/report.pdf")
        with self.assertRaises(ValueError): reader.source_file(str(reader.INPUT_DATA / ".." / "outside.pdf"))
        from openpyxl import Workbook
        workbook = Workbook(); workbook.active["XFD1048576"] = "outside supported range"
        source = reader.INPUT_DATA / "oversized-range.xlsx"; workbook.save(source)
        with self.assertRaisesRegex(ValueError, "范围"): self.read(source)
        self.assertFalse(reader.OUTPUT.exists())

    def test_rejects_utf16_office_dtd_before_library_conversion(self):
        from zipfile import ZipFile
        source = reader.INPUT_DATA / "entities.docx"
        for encoding in ("utf-8", "utf-16", "utf-16-be"):
            with self.subTest(encoding=encoding):
                declared = "UTF-8" if encoding == "utf-8" else "UTF-16"
                xml = ('<?xml version="1.0" encoding="' + declared + '"?>'
                       '<!DOCTYPE document [<!ENTITY injected "untrusted expanded text">]>'
                       '<document>&injected;</document>')
                with ZipFile(source, "w") as archive:
                    archive.writestr("[Content_Types].xml", '<Types/>')
                    payload = xml.encode(encoding)
                    if encoding == "utf-16-be": payload = b"\xfe\xff" + payload
                    archive.writestr("word/document.xml", payload)
                with self.assertRaisesRegex(ValueError, "DTD或实体"):
                    self.read(source)
                self.assertFalse(reader.OUTPUT.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
