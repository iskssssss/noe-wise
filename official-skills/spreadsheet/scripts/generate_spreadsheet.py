"""生成或定向修改普通 XLSX；公式仅显式写入，并通过独立 LibreOffice 进程重算。"""
import copy
import datetime as dt
import json
import math
import os
import re
import shutil
import signal
import subprocess
import tempfile
import unicodedata
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath
from zipfile import ZIP_DEFLATED, ZipFile

from openpyxl import Workbook, load_workbook
from openpyxl.chart import BarChart, LineChart, PieChart, Reference
from openpyxl.formula.tokenizer import Tokenizer, TokenizerError
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter, range_boundaries
from openpyxl.workbook.properties import CalcProperties

INPUT_DATA = Path("/input/data")
INPUT_ARTIFACTS = Path("/input/artifacts")
OUTPUT = Path("/workspace/output")
WORK = Path("/workspace/work")
MAX_JSON_BYTES = 2 * 1024 * 1024
MAX_XLSX_BYTES = 20 * 1024 * 1024
MAX_EXPANDED_BYTES = 64 * 1024 * 1024
MAX_CELLS = 100_000
MAX_SHEETS = 32
CELL_TYPES = {"text", "number", "integer", "boolean", "date", "datetime", "formula", "blank"}
# 不开放能触发外部数据、DDE、宏或外部链接的函数；不在此实现另一套公式求值器。
FUNCTIONS = {"SUM", "SUMIF", "SUMIFS", "AVERAGE", "AVERAGEIF", "AVERAGEIFS", "MIN", "MAX", "COUNT",
             "COUNTA", "COUNTBLANK", "COUNTIF", "COUNTIFS", "ROUND", "ROUNDUP", "ROUNDDOWN", "ABS",
             "IF", "IFERROR", "AND", "OR", "NOT", "INT", "MOD", "PRODUCT", "SUBTOTAL", "SUMPRODUCT"}
CELL_REF = re.compile(r"\$?[A-Za-z]{1,3}\$?[1-9][0-9]*(?::\$?[A-Za-z]{1,3}\$?[1-9][0-9]*)?")
SHEET_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"


def locate_input(filename):
    """只读取声明挂载的单一 JSON；同名输入歧义和越界软链接均拒绝。"""
    if not isinstance(filename, str) or Path(filename).name != filename or "\\" in filename or ":" in filename:
        raise ValueError("输入文件名必须是单一安全文件名")
    matches = []
    direct = INPUT_DATA / filename
    if direct.is_file():
        if not direct.resolve().is_relative_to(INPUT_DATA.resolve()):
            raise ValueError("输入文件越过挂载边界")
        matches.append(direct)
    if INPUT_ARTIFACTS.is_dir():
        for artifact in sorted(INPUT_ARTIFACTS.iterdir()):
            candidate = artifact / filename
            if artifact.is_dir() and candidate.is_file():
                if not candidate.resolve().is_relative_to(INPUT_ARTIFACTS.resolve()):
                    raise ValueError("输入文件越过挂载边界")
                matches.append(candidate)
    if len(matches) != 1:
        raise FileNotFoundError(f"/input/data 或 /input/artifacts 中需要且只能提供一个 {filename} 输入文件")
    return matches[0]


def object_fields(value, fields, label):
    if not isinstance(value, dict) or set(value) - set(fields):
        raise ValueError(f"{label} 必须是对象且不得包含未知字段")
    return value


def boolean(value, label):
    if not isinstance(value, bool):
        raise ValueError(f"{label} 必须是布尔值")
    return value


def text(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} 必须是非空字符串")
    if any(ord(char) < 32 and char not in "\t\n\r" for char in value) or len(value) > 32767:
        raise ValueError(f"{label} 含不支持的控制字符或超过 Excel 单元格长度")
    return value


def integer(value, low, high, label):
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise ValueError(f"{label} 必须是 {low}–{high} 范围内的整数")
    return value


def address(value):
    if not isinstance(value, str) or not CELL_REF.fullmatch(value) or ":" in value:
        raise ValueError("cell 必须是单个 A1 单元格地址")
    coordinate = value.replace("$", "").upper()
    min_col, min_row, _, _ = range_boundaries(coordinate)
    if min_col > 16384 or min_row > 1048576:
        raise ValueError("cell 超出 XLSX 行列边界")
    return coordinate


def local_range(value):
    if not isinstance(value, str) or not CELL_REF.fullmatch(value):
        raise ValueError("range 必须是 A1 或 A1:C20 的本地有限范围")
    bounds = range_boundaries(value.replace("$", "").upper())
    left, top, right, bottom = bounds
    if not 1 <= left <= right <= 16384 or not 1 <= top <= bottom <= 1048576:
        raise ValueError("range 超出 XLSX 行列边界")
    if (right - left + 1) * (bottom - top + 1) > MAX_CELLS:
        raise ValueError("range 超过单次处理单元格预算")
    return bounds


def sheet_name(value):
    name = text(value, "sheet.name")
    if len(name) > 31 or re.search(r"[\\/*?:\[\]]", name) or name.startswith("'") or name.endswith("'"):
        raise ValueError("工作表名称不符合 XLSX 规则")
    return name


def split_reference(value, current_sheet, names):
    if "!" in value:
        name, ref = value.rsplit("!", 1)
        if name.startswith("'") and name.endswith("'"):
            name = name[1:-1].replace("''", "'")
        if name not in names:
            raise ValueError("公式引用了不存在的工作表")
    else:
        name, ref = current_sheet, value
    local_range(ref)
    return name, ref.replace("$", "").upper()


def validate_formula(value, current_sheet, names):
    formula = text(value, "formula")
    if not formula.startswith("=") or len(formula) > 8192:
        raise ValueError("显式 formula 必须以 = 开头且不超过 8192 字符")
    try:
        tokens = Tokenizer(formula).items
    except TokenizerError:
        raise ValueError("公式语法不受支持或包含外部执行表达式") from None
    for token in tokens:
        if token.type == "FUNC" and token.subtype == "OPEN":
            if token.value[:-1].upper() not in FUNCTIONS:
                raise ValueError("公式函数不在本地安全支持范围内")
        elif token.type == "OPERAND" and token.subtype == "RANGE":
            split_reference(token.value, current_sheet, names)
        elif token.type == "OPERAND" and token.subtype == "ERROR":
            raise ValueError("公式包含错误引用")
        elif token.type in {"ARRAY", "UNKNOWN"}:
            raise ValueError("不支持数组或未知公式语法")
    return formula


def formula_signature(value, sheet, names):
    signature = []
    for token in Tokenizer(value).items:
        if token.type == "WHITE-SPACE":
            continue
        item = token.value
        if token.type == "OPERAND" and token.subtype == "RANGE":
            item = split_reference(item, sheet, names)
        elif token.type == "FUNC":
            item = item.upper()
        signature.append((token.type, token.subtype, item))
    return signature


def typed_value(raw, column_type=None, trim_strings=False):
    if isinstance(raw, dict):
        object_fields(raw, {"type", "value"}, "typed cell")
        kind = raw.get("type")
        value = raw.get("value")
    else:
        value = raw
        kind = column_type or ("blank" if raw is None else "boolean" if isinstance(raw, bool)
                               else "number" if isinstance(raw, (int, float)) else "text")
    if kind not in CELL_TYPES:
        raise ValueError("单元格 type 不受支持")
    if kind == "blank":
        if value is not None:
            raise ValueError("blank 单元格的 value 只能是 null")
        return kind, None
    if value is None:
        return "blank", None
    if kind in {"text", "formula"}:
        if not isinstance(value, str):
            raise ValueError(f"{kind} 的 value 必须是字符串")
        if len(value) > 32767 or any(ord(c) < 32 and c not in "\t\n\r" for c in value):
            raise ValueError("文本超过长度限制或含非法控制字符")
        return kind, value.strip() if kind == "text" and trim_strings else value
    if kind in {"number", "integer"}:
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            raise ValueError("数值必须是有限 JSON 数字，不能用字符串或布尔值代替")
        if kind == "integer" and not float(value).is_integer():
            raise ValueError("integer 不能含小数")
        return kind, int(value) if kind == "integer" else value
    if kind == "boolean":
        return kind, boolean(value, "boolean.value")
    if not isinstance(value, str):
        raise ValueError("日期必须是 ISO 格式字符串")
    parsed = dt.date.fromisoformat(value) if kind == "date" else dt.datetime.fromisoformat(value)
    if isinstance(parsed, dt.datetime) and parsed.tzinfo is not None:
        raise ValueError("Excel datetime 不支持时区；先按用户明确时区转换为本地时间")
    return kind, parsed


def write_value(cell, raw, names, column_type=None, trim_strings=False):
    kind, value = typed_value(raw, column_type, trim_strings)
    if kind == "formula":
        value = validate_formula(value, cell.parent.title, names)
    cell.value = value
    # openpyxl 会把 = 开头字符串自动分类为公式；普通用户文本必须显式纠正为字符串。
    if kind == "text":
        cell.data_type = "s"
    if kind == "date":
        cell.number_format = "yyyy-mm-dd"
    elif kind == "datetime":
        cell.number_format = "yyyy-mm-dd hh:mm:ss"


def display_width(value):
    return sum(2 if unicodedata.east_asian_width(char) in {"W", "F"} else 1 for char in str(value))


def style_cell(cell, style):
    object_fields(style, {"bold", "font_color", "fill", "number_format", "wrap_text", "horizontal"}, "cell.style")
    font = copy.copy(cell.font)
    if "bold" in style:
        font.bold = boolean(style["bold"], "style.bold")
    if "font_color" in style:
        font.color = color(style["font_color"])
    cell.font = font
    if "fill" in style:
        cell.fill = PatternFill("solid", fgColor=color(style["fill"]))
    if "number_format" in style:
        cell.number_format = text(style["number_format"], "style.number_format")
    alignment = copy.copy(cell.alignment)
    if "wrap_text" in style:
        alignment.wrap_text = boolean(style["wrap_text"], "style.wrap_text")
    if "horizontal" in style:
        if style["horizontal"] not in {"left", "center", "right", "general"}:
            raise ValueError("style.horizontal 不受支持")
        alignment.horizontal = style["horizontal"]
    cell.alignment = alignment


def color(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9A-Fa-f]{6}", value):
        raise ValueError("颜色必须是六位 RGB 十六进制字符串")
    return value.upper()


def apply_cells(worksheet, entries, names):
    if not isinstance(entries, list) or len(entries) > MAX_CELLS:
        raise ValueError("cells 必须是受限数组")
    seen = set()
    for entry in entries:
        object_fields(entry, {"cell", "value", "style"}, "cell entry")
        coordinate = address(entry.get("cell"))
        if coordinate in seen or not ({"value", "style"} & entry.keys()):
            raise ValueError("cells 不得重复指定单元格且必须包含 value 或 style")
        seen.add(coordinate)
        cell = worksheet[coordinate]
        if "value" in entry:
            write_value(cell, entry["value"], names)
        if "style" in entry:
            style_cell(cell, entry["style"])


def apply_print(worksheet, options):
    object_fields(options, {"orientation", "area", "repeat_rows", "fit_width", "fit_height"}, "print")
    orientation = options.get("orientation", "landscape")
    if orientation not in {"landscape", "portrait"}:
        raise ValueError("print.orientation 不受支持")
    worksheet.page_setup.orientation = orientation
    worksheet.page_setup.paperSize = worksheet.PAPERSIZE_A4
    worksheet.page_setup.fitToWidth = integer(options.get("fit_width", 1), 0, 10, "print.fit_width")
    worksheet.page_setup.fitToHeight = integer(options.get("fit_height", 0), 0, 10, "print.fit_height")
    worksheet.sheet_properties.pageSetUpPr.fitToPage = True
    worksheet.page_margins.left = worksheet.page_margins.right = 0.3
    area = options.get("area", worksheet.dimensions)
    local_range(area)
    worksheet.print_area = area
    if "repeat_rows" in options:
        repeat = options["repeat_rows"]
        if not isinstance(repeat, str) or not re.fullmatch(r"[1-9][0-9]*:[1-9][0-9]*", repeat):
            raise ValueError("print.repeat_rows 必须是 1:1 形式")
        first, last = map(int, repeat.split(":"))
        if not 1 <= first <= last <= 1048576:
            raise ValueError("print.repeat_rows 行号非法")
        worksheet.print_title_rows = repeat


def chart(worksheet, spec, workbook):
    object_fields(spec, {"type", "title", "sheet", "data", "categories", "anchor", "title_from_data", "width", "height", "axis_min"}, "chart")
    chart_type = {"bar": BarChart, "line": LineChart, "pie": PieChart}.get(spec.get("type"))
    if chart_type is None:
        raise ValueError("chart.type 只能是 bar、line 或 pie")
    source = workbook[spec.get("sheet", worksheet.title)]
    bounds = local_range(spec.get("data"))
    categories = local_range(spec.get("categories"))
    if categories[0] != categories[2]:
        raise ValueError("chart.categories 必须是单列范围")
    title_from_data = boolean(spec.get("title_from_data", True), "chart.title_from_data")
    if bounds[3] - bounds[1] + 1 - int(title_from_data) != categories[3] - categories[1] + 1:
        raise ValueError("图表数据行数与分类行数不一致")
    if chart_type is PieChart and bounds[0] != bounds[2]:
        raise ValueError("饼图只支持单列数据系列")
    result = chart_type()
    result.title = text(spec.get("title"), "chart.title")
    if "axis_min" in spec:
        minimum = spec["axis_min"]
        if chart_type is PieChart or isinstance(minimum, bool) or not isinstance(minimum, (int, float)) or not math.isfinite(minimum):
            raise ValueError("axis_min 仅支持柱形/折线图的有限数字")
        result.y_axis.scaling.min = minimum
    result.add_data(Reference(source, min_col=bounds[0], min_row=bounds[1], max_col=bounds[2], max_row=bounds[3]),
                    titles_from_data=title_from_data)
    result.set_categories(Reference(source, min_col=categories[0], min_row=categories[1], max_row=categories[3]))
    for field, default in (("width", 18), ("height", 10)):
        value = spec.get(field, default)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not 3 <= value <= 40:
            raise ValueError("图表宽高必须在 3–40 厘米之间")
        setattr(result, field, value)
    worksheet.add_chart(result, address(spec.get("anchor", "G2")))


def populate_sheet(worksheet, spec, workbook):
    object_fields(spec, {"name", "columns", "rows", "cells", "charts", "trim_strings", "deduplicate_by", "freeze_panes", "autofilter", "print"}, "sheet")
    columns = spec.get("columns", [])
    rows = spec.get("rows", [])
    if not isinstance(columns, list) or not isinstance(rows, list) or (rows and not columns):
        raise ValueError("columns、rows 必须是数组；rows 必须有 columns")
    if (len(rows) + 1) * max(1, len(columns)) > MAX_CELLS:
        raise ValueError("表格超过单次单元格预算")
    keys, titles = [], []
    for column in columns:
        object_fields(column, {"key", "title", "type", "number_format", "width"}, "column")
        key = text(column.get("key"), "column.key")
        title = text(column.get("title", key), "column.title")
        if key in keys or title in titles or column.get("type", "text") not in CELL_TYPES:
            raise ValueError("列键、标题不得重复且 type 必须有效")
        keys.append(key)
        titles.append(title)
    trim = boolean(spec.get("trim_strings", False), "trim_strings")
    dedup = spec.get("deduplicate_by")
    if dedup is not None and (not isinstance(dedup, list) or not dedup or any(key not in keys for key in dedup) or len(set(dedup)) != len(dedup)):
        raise ValueError("deduplicate_by 必须显式引用唯一列键")
    clean_rows, seen = [], set()
    for row in rows:
        object_fields(row, keys, "row")
        values = [typed_value(row.get(key), column.get("type", "text"), trim) for key, column in zip(keys, columns)]
        if dedup is not None:
            marker = tuple(values[keys.index(key)] for key in dedup)
            if marker in seen:
                continue
            seen.add(marker)
        clean_rows.append(row)
    for position, title in enumerate(titles, start=1):
        header = worksheet.cell(1, position)
        write_value(header, title, workbook.sheetnames)
        header.font = Font(bold=True, color="FFFFFF", name="Noto Sans CJK SC", size=11)
        header.fill = PatternFill("solid", fgColor="1F4E78")
    for row_number, row in enumerate(clean_rows, start=2):
        for position, (key, column) in enumerate(zip(keys, columns), start=1):
            write_value(worksheet.cell(row_number, position), row.get(key), workbook.sheetnames, column.get("type", "text"), trim)
    for position, column in enumerate(columns, start=1):
        letter = get_column_letter(position)
        measured = max([display_width(titles[position - 1])] + [display_width(worksheet.cell(row, position).value or "") for row in range(2, len(clean_rows) + 2)])
        width = column.get("width", max(12, min(48, measured + 3)))
        if isinstance(width, bool) or not isinstance(width, (int, float)) or not 6 <= width <= 80:
            raise ValueError("column.width 必须在 6–80 之间")
        worksheet.column_dimensions[letter].width = width
        for row_number in range(2, len(clean_rows) + 2):
            cell = worksheet.cell(row_number, position)
            if "number_format" in column:
                cell.number_format = text(column["number_format"], "column.number_format")
    apply_cells(worksheet, spec.get("cells", []), workbook.sheetnames)
    if worksheet.max_row * worksheet.max_column > MAX_CELLS:
        raise ValueError("工作表有效范围超过单次处理预算")
    for position in range(len(columns) + 1, worksheet.max_column + 1):
        values = (str(worksheet.cell(row, position).value or "") for row in range(1, worksheet.max_row + 1))
        width = max((display_width(line) for value in values for line in value.split("\n")), default=0)
        worksheet.column_dimensions[get_column_letter(position)].width = max(12, min(48, width + 3))
    for row in worksheet.iter_rows():
        lines = 1
        for cell in row:
            width = worksheet.column_dimensions[cell.column_letter].width
            alignment = copy.copy(cell.alignment)
            alignment.vertical = alignment.vertical or "top"
            if alignment.wrap_text is None:
                alignment.wrap_text = True
            cell.alignment = alignment
            font = copy.copy(cell.font)
            font.name = "Noto Sans CJK SC"
            cell.font = font
            wrapped = sum(max(1, math.ceil(display_width(part) / max(1, width - 3))) for part in str(cell.value or "").split("\n"))
            lines = max(lines, wrapped)
        worksheet.row_dimensions[row[0].row].height = min(409, max(24, lines * 17 + 8))
    worksheet.sheet_view.zoomScale = 90
    freeze = spec.get("freeze_panes", "A2" if columns else None)
    if freeze is not None:
        worksheet.freeze_panes = address(freeze)
    if boolean(spec.get("autofilter", bool(columns)), "autofilter"):
        worksheet.auto_filter.ref = f"A1:{get_column_letter(len(columns))}{len(clean_rows) + 1}" if columns else worksheet.dimensions
    apply_print(worksheet, spec.get("print", {"repeat_rows": "1:1"} if columns else {}))
    charts = spec.get("charts", [])
    if not isinstance(charts, list) or len(charts) > 16:
        raise ValueError("charts 必须是最多 16 项的数组")
    for spec_chart in charts:
        chart(worksheet, spec_chart, workbook)
    return {"sheet": worksheet.title, "input_rows": len(rows), "output_rows": len(clean_rows), "duplicates_removed": len(rows) - len(clean_rows)}


def validate_package(path):
    if path.suffix.lower() != ".xlsx" or not path.is_file() or path.stat().st_size > MAX_XLSX_BYTES:
        raise ValueError("原件必须是大小不超过 20 MiB 的普通 XLSX")
    with ZipFile(path) as package:
        entries = package.infolist()
        if len(entries) > 2048 or sum(item.file_size for item in entries) > MAX_EXPANDED_BYTES:
            raise ValueError("XLSX 解压资源超过预算")
        names = [item.filename for item in entries]
        if len(set(names)) != len(names):
            raise ValueError("XLSX 含重复包路径")
        rejected = ("vbaproject", "externallinks/", "connections.xml", "querytables/", "pivottables/", "pivotcache/",
                    "slicers/", "slicercaches/", "activex/", "embeddings/", "customui/", "vmldrawing", "customxml/", "xl/media/")
        for item in entries:
            parts = PurePosixPath(item.filename)
            if item.flag_bits & 1 or parts.is_absolute() or ".." in parts.parts or "\\" in item.filename:
                raise ValueError("XLSX 含加密或不安全包路径")
            if any(token in item.filename.lower() for token in rejected):
                raise ValueError("不支持宏、外部连接、透视表、图片、嵌入对象或特殊扩展；原件保持不变")
            if item.filename.endswith((".xml", ".rels")):
                source = package.read(item.filename)
                if b"<!DOCTYPE" in source.upper() or b"<!ENTITY" in source.upper():
                    raise ValueError("XLSX XML 不得含 DTD 或实体")
                root = ET.fromstring(source)
                for element in root.iter():
                    local = element.tag.rsplit("}", 1)[-1]
                    # 包路径可被伪装；同时按 OOXML 声明角色拒绝宏和可执行嵌入对象。
                    declared_type = (element.attrib.get("ContentType", "") + " " + element.attrib.get("Type", "")).lower()
                    if any(role in declared_type for role in ("macroenabled", "vbaproject", "oleobject", "activex")):
                        raise ValueError("XLSX 类型或关系声明包含宏或可执行嵌入对象；原件保持不变")
                    if element.attrib.get("TargetMode", "").lower() == "external":
                        raise ValueError("XLSX 不支持外部链接，包括外部图片和超链接")
                    # LibreOffice 自身生成的两个序列化提示不携带业务对象；其余扩展仍拒绝。
                    if local == "extLst" and supported_lo_metadata(element, item.filename):
                        continue
                    if local in {"extLst", "AlternateContent", "oleObject", "control", "legacyDrawing", "sp", "grpSp", "cxnSp", "contentPart"}:
                        raise ValueError(f"XLSX 含无法安全保留的对象或扩展：{item.filename} ({local})")
        if "xl/workbook.xml" not in names:
            raise ValueError("输入不是有效的 XLSX 工作簿")


def supported_lo_metadata(element, part):
    allowed = {
        ("xl/workbook.xml", "{7626C862-2A13-11E5-B345-FEFF819CDC9F}"):
            ("{http://schemas.libreoffice.org/}extCalcPr", {"stringRefSyntax": "ExcelA1"}),
    }
    if re.fullmatch(r"xl/charts/chart[0-9]+\.xml", part):
        allowed[(part, "{CE6537A1-D6FC-4f65-9D91-7224C49458BB}")] = (
            "{http://schemas.microsoft.com/office/drawing/2012/chart}showLeaderLines", {"val": "1"})
    extensions = list(element)
    if not extensions:
        return False
    for extension in extensions:
        expected = allowed.get((part, extension.attrib.get("uri")))
        children = list(extension)
        if not expected or len(children) != 1 or children[0].tag != expected[0] or children[0].attrib != expected[1] or list(children[0]):
            return False
    return True


def source_path(reference):
    object_fields(reference, {"artifact_id", "path"}, "source")
    artifact_id = reference.get("artifact_id")
    relative = reference.get("path")
    if not isinstance(artifact_id, str) or not re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", artifact_id):
        raise ValueError("source.artifact_id 必须来自真实产物引用")
    if not isinstance(relative, str) or relative.startswith("/") or "\\" in relative or ":" in relative or any(ord(c) < 32 for c in relative) or any(part in {"", ".", ".."} for part in relative.split("/")):
        raise ValueError("source.path 必须是安全相对路径")
    root = INPUT_ARTIFACTS / artifact_id
    candidate = root / relative
    if not candidate.resolve().is_relative_to(root.resolve()) or not candidate.resolve().is_relative_to(INPUT_ARTIFACTS.resolve()):
        raise ValueError("原件越过 artifact 挂载边界")
    validate_package(candidate)
    return candidate


def workbook_snapshot(workbook):
    if len(workbook.worksheets) > MAX_SHEETS or any(sheet.max_row * sheet.max_column > MAX_CELLS for sheet in workbook):
        raise ValueError("工作簿工作表数或有效范围超过处理预算")
    if sum(sheet.max_row * sheet.max_column for sheet in workbook) > MAX_CELLS:
        raise ValueError("工作簿总单元格范围超过处理预算")
    snapshot = {}
    for sheet in workbook:
        values = {}
        for row in sheet:
            for cell in row:
                if cell.data_type == "e":
                    raise ValueError("工作簿含错误单元格")
                if cell.value is not None and cell.value != "":
                    if cell.data_type == "f":
                        validate_formula(cell.value, sheet.title, workbook.sheetnames)
                    values[cell.coordinate] = (cell.data_type, cell.value)
        snapshot[sheet.title] = {"values": values, "charts": len(sheet._charts), "images": len(sheet._images),
                                 "merged": sorted(str(item) for item in sheet.merged_cells.ranges), "state": sheet.sheet_state}
    return snapshot


def verify_saved(path, expected):
    workbook = load_workbook(path, data_only=False, keep_links=False)
    try:
        actual = workbook_snapshot(workbook)
        if list(actual) != list(expected):
            raise ValueError("保存或重算改变了工作表结构")
        for name in expected:
            if any(actual[name][field] != expected[name][field] for field in ("charts", "images", "merged", "state")):
                raise ValueError("保存或重算改变了图表、图片、合并范围或工作表状态")
            before, after = expected[name]["values"], actual[name]["values"]
            if set(before) != set(after):
                raise ValueError("保存或重算导致非空单元格丢失或新增")
            for coordinate, (kind, value) in before.items():
                actual_kind, actual_value = after[coordinate]
                if kind == "f":
                    equal = actual_kind == "f" and formula_signature(value, name, expected) == formula_signature(actual_value, name, expected)
                elif isinstance(value, dt.date):
                    normalized = actual_value.date() if isinstance(actual_value, dt.datetime) and not isinstance(value, dt.datetime) else actual_value
                    equal = value == normalized
                else:
                    equal = actual_kind == kind and value == actual_value
                if not equal:
                    raise ValueError(f"保存或重算改变了单元格内容：{name}!{coordinate}")
    finally:
        workbook.close()


def cached_formula_count(path):
    count = 0
    with ZipFile(path) as package:
        for name in package.namelist():
            if name.startswith("xl/worksheets/") and name.endswith(".xml"):
                root = ET.fromstring(package.read(name))
                for cell in root.iter(SHEET_NS + "c"):
                    if cell.find(SHEET_NS + "f") is not None:
                        cached = cell.find(SHEET_NS + "v")
                        if cell.attrib.get("t") == "e" or cached is None or (cached.text is None and cell.attrib.get("t") != "str"):
                            raise ValueError("公式重算未生成有效缓存值或出现错误单元格")
                        count += 1
    return count


def verify_chart_caches(path, chart_count):
    if not chart_count:
        return
    namespace = "{http://schemas.openxmlformats.org/drawingml/2006/chart}"
    with ZipFile(path) as package:
        charts = [name for name in package.namelist() if name.startswith("xl/charts/chart") and name.endswith(".xml")]
        if len(charts) != chart_count:
            raise ValueError("图表数量与重算前不一致")
        for name in charts:
            root = ET.fromstring(package.read(name))
            for series in root.iter(namespace + "ser"):
                caches = list(series.iter(namespace + "numCache"))
                if not caches or not any(list(cache.iter(namespace + "pt")) for cache in caches):
                    raise ValueError("图表重算未生成数据缓存，不能交付空白图表")


def recalculate(path, expected, directory):
    executable = shutil.which("libreoffice") or shutil.which("soffice")
    if not executable:
        raise RuntimeError("缺少 LibreOffice；公式已写入但不能交付为已计算结果")
    profile = directory / "profile"
    output = directory / "calculated"
    output.mkdir()
    command = [executable, f"-env:UserInstallation={profile.as_uri()}", "--headless", "--nologo", "--nodefault", "--norestore",
               "--convert-to", "xlsx:Calc MS Excel 2007 XML", "--outdir", str(output), str(path)]
    environment = dict(os.environ)
    environment["HOME"] = str(directory)
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               env=environment, start_new_session=os.name != "nt")
    try:
        _, stderr = process.communicate(timeout=60)
    except subprocess.TimeoutExpired as failure:
        if os.name != "nt":
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
        process.communicate()
        raise RuntimeError("LibreOffice 重算超时，受控进程已终止") from failure
    calculated = output / path.name
    if process.returncode != 0 or not calculated.is_file():
        raise RuntimeError("LibreOffice 重算失败；未发布候选工作簿")
    verify_saved(calculated, expected)
    count = cached_formula_count(calculated)
    errors = load_workbook(calculated, data_only=True, keep_links=False)
    try:
        if any(cell.data_type == "e" for sheet in errors for row in sheet for cell in row):
            raise ValueError("重算后的工作簿含错误值")
    finally:
        errors.close()
    return calculated, count


def inspect_workbook(path):
    workbook = load_workbook(path, data_only=False, keep_links=False)
    cached = load_workbook(path, data_only=True, keep_links=False)
    try:
        snapshot = workbook_snapshot(workbook)
        result = {"operation": "inspect", "recalculated": False, "formula_cache_verified": False, "sheets": []}
        for sheet in workbook:
            preview = []
            for row in sheet.iter_rows(max_row=min(20, sheet.max_row), max_col=min(16, sheet.max_column)):
                preview.append([{"cell": cell.coordinate, "type": cell.data_type, "value": str(cell.value)[:500] if cell.value is not None else None,
                                 "cached": str(cached[sheet.title][cell.coordinate].value)[:500] if cell.data_type == "f" else None} for cell in row])
            result["sheets"].append({"name": sheet.title, "rows": sheet.max_row, "columns": sheet.max_column, "charts": snapshot[sheet.title]["charts"],
                                     "preview": preview, "preview_is_partial": sheet.max_row > 20 or sheet.max_column > 16})
        return result
    finally:
        workbook.close()
        cached.close()


def check_values(path, checks):
    if not isinstance(checks, list) or len(checks) > 1000:
        raise ValueError("checks 必须是最多 1000 项的数组")
    workbook = load_workbook(path, data_only=True, keep_links=False)
    try:
        for item in checks:
            object_fields(item, {"sheet", "cell", "expected", "tolerance"}, "check")
            value = workbook[item["sheet"]][address(item.get("cell"))].value
            expected = item.get("expected")
            tolerance = item.get("tolerance", 0)
            if isinstance(tolerance, bool) or not isinstance(tolerance, (float, int)) or not 0 <= tolerance <= 1:
                raise ValueError("check.tolerance 必须是 0–1 的数字")
            equal = value == expected
            if isinstance(value, (float, int)) and not isinstance(value, bool) and isinstance(expected, (float, int)) and not isinstance(expected, bool):
                equal = math.isclose(value, expected, rel_tol=0, abs_tol=tolerance)
            if not equal:
                raise ValueError(f"缓存值与显式校验不符：{item['sheet']}!{item['cell']}")
    finally:
        workbook.close()


def output_filename(data):
    name = data.get("output_name", "report.xlsx")
    if not isinstance(name, str) or not name or name != name.strip() or unicodedata.normalize("NFKC", name) != name:
        raise ValueError("output_name 必须是规范化的安全文件名")
    if any(ord(char) < 32 or 127 <= ord(char) <= 159 or char in '\\/:*?"<>|' for char in name):
        raise ValueError("output_name 不能包含路径或控制字符")
    if len(name) > 200 or len(name.encode("utf-8")) > 255 or not name.lower().endswith(".xlsx"):
        raise ValueError("output_name 长度或 XLSX 扩展名不匹配")
    stem = name.rsplit(".", 1)[0].rstrip(" .").lower()
    if stem in {"con", "prn", "aux", "nul", *["com" + str(i) for i in range(1, 10)], *["lpt" + str(i) for i in range(1, 10)]}:
        raise ValueError("output_name 不能使用保留名")
    return name


def generate(data):
    object_fields(data, {"operation", "sheets", "source", "updates", "recalculate", "checks", "bundle_zip", "output_name"}, "spreadsheet")
    operation = data.get("operation", "create")
    if operation not in {"create", "edit", "inspect"}:
        raise ValueError("operation 只能是 create、edit 或 inspect")
    bundle = boolean(data.get("bundle_zip", False), "bundle_zip")
    if operation == "inspect":
        if set(data) - {"operation", "source"}:
            raise ValueError("inspect 只接受 source，不执行修改或重算")
        return inspect_workbook(source_path(data.get("source")))
    output_name = output_filename(data)
    target = OUTPUT / output_name
    if target.exists() or (bundle and (OUTPUT / "report.zip").exists()):
        raise FileExistsError("候选目标已存在；不会覆盖原件或候选文件")
    reports = []
    if operation == "create":
        if "source" in data or "updates" in data:
            raise ValueError("create 不接受 source 或 updates")
        sheets = data.get("sheets")
        if not isinstance(sheets, list) or not 1 <= len(sheets) <= MAX_SHEETS:
            raise ValueError("sheets 必须是 1–32 项的数组")
        names = [sheet_name(spec.get("name")) for spec in sheets if isinstance(spec, dict)]
        if len(names) != len(sheets) or len({name.casefold() for name in names}) != len(names):
            raise ValueError("sheets 必须是对象且名称不得重复")
        workbook = Workbook()
        workbook.remove(workbook.active)
        for name in names:
            workbook.create_sheet(name)
        for spec in sheets:
            reports.append(populate_sheet(workbook[spec["name"]], spec, workbook))
    else:
        if "sheets" in data:
            raise ValueError("edit 不接受 sheets；使用 updates 定向修改原工作表")
        original = source_path(data.get("source"))
        workbook = load_workbook(original, data_only=False, keep_links=False)
        workbook_snapshot(workbook)
        updates = data.get("updates")
        if not isinstance(updates, list) or not updates or len(updates) > MAX_SHEETS:
            raise ValueError("edit 必须包含非空 updates")
        seen = set()
        for update in updates:
            object_fields(update, {"sheet", "cells", "print", "freeze_panes"}, "update")
            name = update.get("sheet")
            if name not in workbook.sheetnames or name in seen:
                raise ValueError("update 必须指定存在且不重复的工作表")
            seen.add(name)
            apply_cells(workbook[name], update.get("cells", []), workbook.sheetnames)
            if "print" in update:
                apply_print(workbook[name], update["print"])
            if "freeze_panes" in update:
                workbook[name].freeze_panes = address(update["freeze_panes"]) if update["freeze_panes"] else None
    try:
        expected = workbook_snapshot(workbook)
        formulas = sum(kind == "f" for item in expected.values() for kind, _ in item["values"].values())
        chart_count = sum(item["charts"] for item in expected.values())
        compute = boolean(data.get("recalculate", bool(formulas or chart_count)), "recalculate")
        if formulas and data.get("checks") and not compute:
            raise ValueError("含公式的缓存校验必须启用 recalculate")
        workbook.calculation = CalcProperties(calcId=0, fullCalcOnLoad=True, forceFullCalc=True)
        WORK.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="spreadsheet-", dir=WORK) as scratch:
            directory = Path(scratch).resolve()
            staged = directory / "report.xlsx"
            workbook.save(staged)
            verify_saved(staged, expected)
            computed_path, cached = recalculate(staged, expected, directory) if compute and (formulas or chart_count) else (staged, 0)
            if compute:
                verify_chart_caches(computed_path, chart_count)
            check_values(computed_path, data.get("checks", []))
            OUTPUT.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as writer, computed_path.open("rb") as reader:
                shutil.copyfileobj(reader, writer)
        if bundle:
            with ZipFile(OUTPUT / "report.zip", "x", compression=ZIP_DEFLATED) as package:
                package.write(target, arcname=output_name)
        return {"operation": operation, "output": output_name, "bundle": "report.zip" if bundle else None,
                "sheets": reports or [{"sheet": name} for name in workbook.sheetnames], "formula_count": formulas,
                "recalculated": bool((formulas or chart_count) and compute), "formula_cache_verified": bool(formulas and compute),
                "chart_cache_verified": bool(chart_count and compute),
                "cached_formula_count": cached, "explicit_value_checks": len(data.get("checks", []))}
    finally:
        workbook.close()


def main():
    input_path = locate_input("spreadsheet.json")
    if input_path.stat().st_size > MAX_JSON_BYTES:
        raise ValueError("spreadsheet.json 超过 2 MiB 输入预算")
    data = json.loads(input_path.read_text(encoding="utf-8"), parse_constant=lambda _: (_ for _ in ()).throw(ValueError("JSON 不接受非有限数字")))
    result = generate(data)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    # 公开结果与候选文件分开；正文通过固定 result.json 自动到达模型，不依赖 stdout。
    with (OUTPUT / "result.json").open("x", encoding="utf-8") as report:
        json.dump({"results": [{"kind": "text", "format": "plain", "text": json.dumps(result, ensure_ascii=False)}]}, report, ensure_ascii=False)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
