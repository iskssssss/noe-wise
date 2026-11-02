"""表格 Skill 的数据、边界、修订保留及真实 LibreOffice 计算验证。"""
import contextlib
import copy
import datetime as dt
import hashlib
import importlib.util
import io
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile

from openpyxl import Workbook, load_workbook
from openpyxl.styles import Font, PatternFill

ROOT = Path(__file__).resolve().parents[2]
SKILL = ROOT / "official-skills/spreadsheet"
ARTIFACT_ID = "00000000-0000-0000-0000-000000000001"
HAS_LIBREOFFICE = bool(shutil.which("libreoffice") or shutil.which("soffice"))


class SpreadsheetSkillTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="spreadsheet-skill-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        spec = importlib.util.spec_from_file_location("spreadsheet_skill", SKILL / "scripts/generate_spreadsheet.py")
        self.skill = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.skill)
        self.skill.INPUT_DATA = self.root / "input/data"
        self.skill.INPUT_ARTIFACTS = self.root / "input/artifacts"
        self.skill.OUTPUT = self.root / "output"
        self.skill.WORK = self.root / "work"
        self.skill.INPUT_DATA.mkdir(parents=True)

    def example(self, name="spreadsheet.json"):
        return json.loads((SKILL / "examples" / name).read_text(encoding="utf-8"))

    def run_main(self, payload):
        (self.skill.INPUT_DATA / "spreadsheet.json").write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            self.skill.main()
        report = json.loads((self.skill.OUTPUT / "result.json").read_text(encoding="utf-8"))
        self.assertEqual("text", report["results"][0]["kind"])
        return json.loads(report["results"][0]["text"])

    def read(self, name="report.xlsx", cached=False):
        workbook = load_workbook(self.skill.OUTPUT / name, data_only=cached)
        self.addCleanup(workbook.close)
        return workbook

    def mount_workbook(self, workbook=None):
        target = self.skill.INPUT_ARTIFACTS / ARTIFACT_ID / "original.xlsx"
        target.parent.mkdir(parents=True)
        if workbook is None:
            workbook = Workbook()
            workbook.active.title = "数据"
            workbook.active["A1"] = "原件"
        workbook.save(target)
        workbook.close()
        return target, {"artifact_id": ARTIFACT_ID, "path": "original.xlsx"}

    def test_named_workbook_bundle_and_typed_result_share_the_actual_basename(self):
        payload = self.example()
        payload.update(output_name="department-expenses.xlsx", bundle_zip=True)
        result = self.run_main(payload)
        self.assertEqual("department-expenses.xlsx", result["output"])
        self.assertTrue((self.skill.OUTPUT / result["output"]).is_file())
        self.assertFalse((self.skill.OUTPUT / "report.xlsx").exists())
        self.assertEqual("流水", self.read(result["output"]).active.title)
        with ZipFile(self.skill.OUTPUT / result["bundle"]) as package:
            self.assertEqual([result["output"]], package.namelist())
            self.assertEqual((self.skill.OUTPUT / result["output"]).read_bytes(), package.read(result["output"]))

    def test_named_edit_keeps_original_bytes_and_formula_structure(self):
        workbook = Workbook()
        workbook.active.title = "数据"
        workbook.active["A1"] = "=SUM(B1:B2)"
        workbook.active["B1"] = 2
        workbook.active["B2"] = 3
        original, source = self.mount_workbook(workbook)
        before = original.read_bytes()
        result = self.run_main({"operation": "edit", "source": source, "recalculate": False,
                               "output_name": "revised.xlsx", "updates": [{"sheet": "数据", "cells": [{"cell": "B2", "value": 4}]}]})
        self.assertEqual(before, original.read_bytes())
        self.assertEqual("=SUM(B1:B2)", self.read(result["output"])["数据"]["A1"].value)
        self.assertEqual(4, self.read(result["output"])["数据"]["B2"].value)
        self.assertFalse(result["formula_cache_verified"])

    def test_output_name_rejects_unsafe_noncanonical_or_wrong_extension_before_writing(self):
        for name in ("../x.xlsx", "a/x.xlsx", "x.pdf", "CON.xlsx", "COM1.xlsx", "x.xlsx\n",
                     "x\u0080.xlsx", "x\u0085.xlsx", "x\u009f.xlsx", "Ａ.xlsx", "报" * 84 + ".xlsx"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                payload = self.example()
                payload["output_name"] = name
                self.skill.generate(payload)
        self.assertFalse(self.skill.OUTPUT.exists())

    def test_explicit_input_only_and_ambiguity(self):
        examples = self.root / "examples/spreadsheet.json"
        examples.parent.mkdir()
        examples.write_text("{}", encoding="utf-8")
        with self.assertRaises(FileNotFoundError):
            self.skill.locate_input("spreadsheet.json")
        direct = self.skill.INPUT_DATA / "spreadsheet.json"
        direct.write_text("{}", encoding="utf-8")
        self.assertEqual(direct, self.skill.locate_input("spreadsheet.json"))
        duplicate = self.skill.INPUT_ARTIFACTS / ARTIFACT_ID / "spreadsheet.json"
        duplicate.parent.mkdir(parents=True)
        duplicate.write_text("{}", encoding="utf-8")
        with self.assertRaises(FileNotFoundError):
            self.skill.locate_input("spreadsheet.json")

    def test_x01_duplicates_types_and_text_are_preserved(self):
        report = self.run_main(self.example())
        sheet = self.read()["流水"]
        self.assertEqual(4, sheet.max_row)
        self.assertEqual(0, report["sheets"][0]["duplicates_removed"])
        self.assertEqual("T001", sheet["A2"].value)
        self.assertEqual(sheet["A2"].value, sheet["A3"].value)
        self.assertEqual(dt.datetime(2026, 10, 1), sheet["B2"].value)
        self.assertEqual(120.5, sheet["C2"].value)
        self.assertEqual("#,##0.00", sheet["C2"].number_format)
        self.assertEqual("s", sheet["D2"].data_type)
        self.assertEqual("=这是用户的普通文本", sheet["D2"].value)
        self.assertEqual("A2", sheet.freeze_panes)
        self.assertEqual("$1:$1", sheet.print_title_rows)
        self.assertEqual("landscape", sheet.page_setup.orientation)
        self.assertEqual(1, sheet.page_setup.fitToWidth)
        self.assertFalse((self.skill.OUTPUT / "report.zip").exists())

    def test_deduplication_and_trim_require_explicit_options(self):
        payload = {"sheets": [{"name": "数据", "columns": [{"key": "id"}],
                                "rows": [{"id": " 001 "}, {"id": "001"}, {"id": "002"}]}]}
        payload["sheets"][0].update({"trim_strings": True, "deduplicate_by": ["id"]})
        report = self.run_main(payload)
        sheet = self.read()["数据"]
        self.assertEqual(3, sheet.max_row)
        self.assertEqual("001", sheet["A2"].value)
        self.assertEqual("s", sheet["A2"].data_type)
        self.assertEqual(1, report["sheets"][0]["duplicates_removed"])

    def test_long_chinese_text_has_readable_wrapping(self):
        requirement = "采用企业本地部署，基础设施由企业提供，需验收网络隔离、权限配置与备份恢复。"
        self.run_main({"sheets": [{"name": "需求", "columns": [{"key": "id"}, {"key": "description"}],
                                   "rows": [{"id": "REQ-01", "description": requirement}]}]})
        sheet = self.read().active
        self.assertEqual(requirement, sheet["B2"].value)
        self.assertTrue(sheet["B2"].alignment.wrap_text)
        self.assertGreater(sheet.row_dimensions[2].height, 30)
        self.assertGreater(sheet.column_dimensions["B"].width, sheet.column_dimensions["A"].width)

    def test_x04_plain_equals_text_is_not_formula(self):
        result = self.run_main({"recalculate": False, "sheets": [{"name": "混合", "cells": [
            {"cell": "A1", "value": "=SUM(B1:B2)"}, {"cell": "B1", "value": 2}, {"cell": "B2", "value": 3},
            {"cell": "A2", "value": {"type": "formula", "value": "=SUM(B1:B2)"}}]}]})
        workbook = self.read()
        self.assertEqual("s", workbook.active["A1"].data_type)
        self.assertEqual("f", workbook.active["A2"].data_type)
        self.assertEqual(1, result["formula_count"])
        self.assertFalse(result["formula_cache_verified"])
        self.assertIsNone(self.read(cached=True).active["A2"].value)

    def test_formula_external_and_unapproved_functions_rejected(self):
        for formula in ("=WEBSERVICE(\"https://example.com\")", "=HYPERLINK(\"https://example.com\")",
                        "='[other.xlsx]数据'!A1", "=MissingName", "=SUM(数据!A:A)", "='不存在'!A1", "=cmd|' /C calc'!A1"):
            with self.subTest(formula=formula), self.assertRaises((ValueError, KeyError)):
                self.skill.generate({"recalculate": False, "sheets": [{"name": "数据", "cells": [
                    {"cell": "A1", "value": {"type": "formula", "value": formula}}]}]})
        self.assertFalse(self.skill.OUTPUT.exists())

    def test_type_unknown_field_and_resource_failures_do_not_publish(self):
        for cell in ({"type": "number", "value": "12"}, {"type": "number", "value": True},
                     {"type": "integer", "value": 1.5}, {"type": "datetime", "value": "2026-10-01T10:00:00+08:00"},
                     {"type": "text", "value": "a\x00b"}, {"type": "text", "value": "x" * 32768}):
            with self.subTest(cell=cell.get("type")), self.assertRaises(ValueError):
                self.skill.generate({"sheets": [{"name": "数据", "cells": [{"cell": "A1", "value": cell}]}]})
        for payload in ({"sheets": [{"name": "数据", "cells": [{"cell": "A100001", "value": 1}]}]},
                        {"sheets": [{"name": "数据", "surprise": True}]},
                        {"sheets": [{"name": "数据"}, {"name": "数据"}]},
                        {"sheets": [{"name": "数据"}], "bundle_zip": "yes"}):
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                self.skill.generate(payload)
        self.assertFalse(self.skill.OUTPUT.exists())

    def test_x03_edit_preserves_other_sheets_formulas_styles_and_original(self):
        original = Workbook()
        original.active.title = "流水"
        original.active["A1"] = "金额"
        original.active["A2"] = 20
        original.active["A2"].number_format = "#,##0.00"
        untouched = original.create_sheet("说明")
        untouched["A1"] = "保留内容"
        untouched["A1"].font = Font(name="Noto Sans CJK SC", bold=True, size=14)
        untouched["A1"].fill = PatternFill("solid", fgColor="FFDD88")
        untouched.merge_cells("A1:C1")
        untouched["B3"] = "=SUM('流水'!A2:A2)"
        path, source = self.mount_workbook(original)
        before = hashlib.sha256(path.read_bytes()).hexdigest()
        self.run_main({"operation": "edit", "source": source, "recalculate": False,
                       "updates": [{"sheet": "流水", "cells": [{"cell": "A2", "value": 30}]}]})
        result = self.read()
        self.assertEqual(["流水", "说明"], result.sheetnames)
        self.assertEqual(30, result["流水"]["A2"].value)
        self.assertEqual("#,##0.00", result["流水"]["A2"].number_format)
        self.assertEqual("保留内容", result["说明"]["A1"].value)
        self.assertTrue(result["说明"]["A1"].font.bold)
        self.assertEqual("00FFDD88", result["说明"]["A1"].fill.fgColor.rgb)
        self.assertEqual(["A1:C1"], [str(item) for item in result["说明"].merged_cells.ranges])
        self.assertEqual("=SUM('流水'!A2:A2)", result["说明"]["B3"].value)
        self.assertEqual(before, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_inspect_is_read_only_and_marks_partial_preview(self):
        workbook = Workbook()
        workbook.active.title = "数据"
        for row in range(1, 25):
            workbook.active.cell(row, 1, row)
        path, source = self.mount_workbook(workbook)
        before = path.read_bytes()
        report = self.run_main({"operation": "inspect", "source": source})
        self.assertTrue(report["sheets"][0]["preview_is_partial"])
        self.assertEqual(20, len(report["sheets"][0]["preview"]))
        self.assertFalse(report["recalculated"])
        self.assertEqual(before, path.read_bytes())
        self.assertFalse((self.skill.OUTPUT / "report.xlsx").exists())

    def test_source_paths_and_existing_output_are_not_overwritten(self):
        path, source = self.mount_workbook()
        for invalid in ("../original.xlsx", "/original.xlsx", "nested/../../original.xlsx", "original.xlsx\\extra", "C:/original.xlsx"):
            with self.subTest(path=invalid), self.assertRaises(ValueError):
                self.skill.source_path({"artifact_id": ARTIFACT_ID, "path": invalid})
        self.skill.OUTPUT.mkdir()
        target = self.skill.OUTPUT / "report.xlsx"
        target.write_bytes(b"existing candidate")
        with self.assertRaises(FileExistsError):
            self.skill.generate(self.example())
        self.assertEqual(b"existing candidate", target.read_bytes())
        self.assertEqual("原件", load_workbook(path).active["A1"].value)

    def test_macro_external_relationship_and_special_objects_rejected(self):
        path, source = self.mount_workbook()
        base = path.read_bytes()
        for name, content in (("xl/vbaProject.bin", b"macro"), ("xl/connections.xml", b"<connections/>"), ("xl/media/image1.png", b"picture"),
                              ("xl/custom.xml", b'<root><extLst/></root>'),
                              ("xl/_rels/external.rels", b'<Relationships><Relationship TargetMode="External" Target="https://example.com"/></Relationships>')):
            with self.subTest(name=name):
                path.write_bytes(base)
                with ZipFile(path, "a") as package:
                    package.writestr(name, content)
                modified = path.read_bytes()
                with self.assertRaises(ValueError):
                    self.skill.generate({"operation": "inspect", "source": source})
                self.assertEqual(modified, path.read_bytes())
        self.assertFalse(self.skill.OUTPUT.exists())

    def test_missing_calculator_stops_formula_result_delivery(self):
        payload = {"sheets": [{"name": "数据", "cells": [{"cell": "A1", "value": {"type": "formula", "value": "=1+2"}}]}]}
        with patch.object(self.skill.shutil, "which", return_value=None), self.assertRaisesRegex(RuntimeError, "缺少 LibreOffice"):
            self.skill.generate(payload)
        self.assertFalse((self.skill.OUTPUT / "report.xlsx").exists())

    def test_macro_type_and_renamed_project_relation_are_rejected(self):
        path, source = self.mount_workbook()
        with ZipFile(path) as package:
            original_parts = {name: package.read(name) for name in package.namelist()}
        for mode in ("content_type", "renamed_project_relation"):
            with self.subTest(mode=mode):
                parts = dict(original_parts)
                if mode == "content_type":
                    standard = b"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
                    self.assertIn(standard, parts["[Content_Types].xml"])
                    parts["[Content_Types].xml"] = parts["[Content_Types].xml"].replace(
                        standard, b"application/vnd.ms-excel.sheet.macroEnabled.main+xml")
                else:
                    parts["xl/_rels/unusual.rels"] = b'<Relationships><Relationship Type="http://schemas.microsoft.com/office/2006/relationships/vbaProject" Target="innocent.bin"/></Relationships>'
                    parts["xl/innocent.bin"] = b"renamed macro project"
                with ZipFile(path, "w") as package:
                    for name, content in parts.items():
                        package.writestr(name, content)
                before = path.read_bytes()
                with self.assertRaisesRegex(ValueError, "类型或关系声明包含宏"):
                    self.skill.generate({"operation": "inspect", "source": source})
                self.assertEqual(before, path.read_bytes())
        self.assertFalse(self.skill.OUTPUT.exists())

    def test_explicit_value_check_fails_before_candidate_publication(self):
        payload = self.example()
        payload["checks"] = [{"sheet": "流水", "cell": "C2", "expected": 999}]
        with self.assertRaisesRegex(ValueError, "缓存值与显式校验不符"):
            self.skill.generate(payload)
        self.assertFalse((self.skill.OUTPUT / "report.xlsx").exists())

    def test_explicit_bundle_contains_only_workbook(self):
        payload = self.example()
        payload["bundle_zip"] = True
        self.run_main(payload)
        with ZipFile(self.skill.OUTPUT / "report.zip") as package:
            self.assertEqual(["report.xlsx"], package.namelist())
            self.assertIsNone(package.testzip())

    @unittest.skipUnless(HAS_LIBREOFFICE, "需要实际 LibreOffice Calc；在受管 Python 镜像运行此用例")
    def test_x02_real_cross_sheet_recalculation_and_chart_cache(self):
        payload = self.example("spreadsheet-formulas.json")
        payload["output_name"] = "calculated.xlsx"
        report = self.run_main(payload)
        self.assertTrue(report["recalculated"])
        self.assertTrue(report["formula_cache_verified"])
        self.assertTrue(report["chart_cache_verified"])
        self.assertEqual(3, report["cached_formula_count"])
        source = self.read(report["output"])
        cached = self.read(report["output"], cached=True)
        self.assertEqual("f", source["汇总"]["B5"].data_type)
        self.assertEqual(241, cached["汇总"]["B2"].value)
        self.assertEqual(490, cached["汇总"]["B5"].value)
        self.assertEqual(1, len(source["汇总"]._charts))
        self.assertEqual(0, source["汇总"]._charts[0].y_axis.scaling.min)

    @unittest.skipUnless(HAS_LIBREOFFICE, "需要实际 LibreOffice Calc；在受管 Python 镜像运行此用例")
    def test_real_recalculation_preserves_equals_text_and_updates_original(self):
        payload = self.example("spreadsheet-formulas.json")
        payload["sheets"][0]["cells"] = [{"cell": "D2", "value": "=WEBSERVICE(\"https://example.com\")"}]
        self.skill.generate(payload)
        original = self.skill.OUTPUT / "report.xlsx"
        mounted = self.skill.INPUT_ARTIFACTS / ARTIFACT_ID / "original.xlsx"
        mounted.parent.mkdir(parents=True)
        shutil.copyfile(original, mounted)
        before = mounted.read_bytes()
        self.skill.OUTPUT = self.root / "edited"
        edit = self.example("spreadsheet-edit.json")
        edit["source"]["path"] = "original.xlsx"
        report = self.run_main(edit)
        self.assertTrue(report["formula_cache_verified"])
        self.assertEqual(541, self.read(cached=True)["汇总"]["B5"].value)
        self.assertEqual("s", self.read()["流水"]["D2"].data_type)
        self.assertEqual("=WEBSERVICE(\"https://example.com\")", self.read()["流水"]["D2"].value)
        self.assertEqual(before, mounted.read_bytes())
        self.assertEqual(1, len(self.read()["汇总"]._charts))

    @unittest.skipUnless(HAS_LIBREOFFICE, "需要实际 LibreOffice Calc；在受管 Python 镜像运行此用例")
    def test_real_error_formula_is_rejected_without_candidate(self):
        payload = {"sheets": [{"name": "数据", "cells": [{"cell": "A1", "value": {"type": "formula", "value": "=1/0"}}]}]}
        with self.assertRaisesRegex(ValueError, "错误"):
            self.skill.generate(payload)
        self.assertFalse((self.skill.OUTPUT / "report.xlsx").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
