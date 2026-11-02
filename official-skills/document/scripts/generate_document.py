"""生成结构化文档，或定向修改明确挂载的 DOCX；不重建原件。"""
import unicodedata
import json
import math
import re
from pathlib import Path, PurePosixPath
from zipfile import ZIP_DEFLATED, ZipFile

INPUT_DATA = Path("/input/data")
INPUT_ARTIFACTS = Path("/input/artifacts")
TEMPLATES = Path(__file__).resolve().parent.parent / "templates"
OUTPUT = Path("/workspace/output")
WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
NS = {"w": WORD_NS}
MAX_SOURCE_BYTES = 32 * 1024 * 1024
MAX_EXPANDED_BYTES = 128 * 1024 * 1024


def require_text(value, label, allow_empty=False):
    if not isinstance(value, str) or (not allow_empty and not value.strip()):
        raise ValueError(f"{label} 必须是{'字符串' if allow_empty else '非空字符串'}")
    return value


def require_number(value, label, minimum, maximum):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not minimum <= value <= maximum:
        raise ValueError(f"{label} 必须在 {minimum} 与 {maximum} 之间")
    return value


def require_bool(value, label):
    if not isinstance(value, bool):
        raise ValueError(f"{label} 必须是布尔值")
    return value


def resolve_input(value, label, allow_template=False):
    """先检查词法路径再解析符号链接，避免读取挂载根以外的文件。"""
    require_text(value, label)
    if "\\" in value or "\x00" in value or ".." in PurePosixPath(value).parts:
        raise ValueError(f"{label} 路径不合法")
    candidate = Path(value)
    if allow_template and not candidate.is_absolute() and PurePosixPath(value).parts[:1] == ("templates",):
        candidate = TEMPLATES / Path(*PurePosixPath(value).parts[1:])
    elif not candidate.is_absolute():
        raise ValueError(f"{label} 必须是明确挂载的绝对路径或 templates/ 包内路径")
    roots = [INPUT_DATA.resolve(), INPUT_ARTIFACTS.resolve()]
    if allow_template:
        roots.append(TEMPLATES.resolve())
    resolved = candidate.resolve()
    if not any(resolved.is_relative_to(root) and resolved != root for root in roots):
        raise ValueError(f"{label} 超出允许输入根")
    if not resolved.is_file():
        raise FileNotFoundError(f"{label} 指定文件不存在")
    if resolved.stat().st_size > MAX_SOURCE_BYTES:
        raise ValueError(f"{label} 超过 32 MiB 限制")
    return resolved


def locate_input(filename):
    matches = []
    direct = INPUT_DATA / filename
    if direct.is_file():
        matches.append(direct)
    if INPUT_ARTIFACTS.is_dir():
        matches.extend(artifact / filename for artifact in sorted(INPUT_ARTIFACTS.iterdir())
                       if artifact.is_dir() and (artifact / filename).is_file())
    if len(matches) != 1:
        raise FileNotFoundError(f"/input/data 或 /input/artifacts 中需要且只能提供一个 {filename} 输入文件")
    return resolve_input(matches[0].as_posix(), filename)


def parse_xml(payload):
    from lxml import etree
    if b"<!DOCTYPE" in payload.upper() or b"<!ENTITY" in payload.upper():
        raise ValueError("DOCX XML 不支持 DTD 或实体声明")
    root = etree.fromstring(payload, etree.XMLParser(resolve_entities=False, no_network=True))
    if root.getroottree().docinfo.doctype:
        raise ValueError("DOCX XML 不支持 DTD 或实体声明")
    return root


def check_docx(path):
    """验证包资源和不支持结构；超范围时在写候选前失败。"""
    if path.suffix.lower() != ".docx":
        raise ValueError("原件和模板只支持 .docx，不支持宏或其他格式")
    unsupported = {"ins", "del", "moveFrom", "moveTo", "sdt", "altChunk", "object"}
    with ZipFile(path) as archive:
        entries = archive.infolist()
        names = [item.filename for item in entries]
        if len(entries) > 2048 or len(names) != len(set(names)) or sum(item.file_size for item in entries) > MAX_EXPANDED_BYTES:
            raise ValueError("DOCX 包超出资源限制或包含重名项")
        if "[Content_Types].xml" not in names or "word/document.xml" not in names:
            raise ValueError("DOCX 缺少正文或内容类型")
        for item in entries:
            name = item.filename
            if name.startswith("/") or "\\" in name or ".." in PurePosixPath(name).parts or item.flag_bits & 1 or (item.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("DOCX 包含非法路径或加密项")
            if item.file_size > 32 * 1024 * 1024 or (item.file_size > 1024 * 1024 and item.file_size > max(item.compress_size, 1) * 200):
                raise ValueError("DOCX 包超出单项大小或压缩率限制")
            lower_name = name.lower()
            if any(token in lower_name for token in ("vbaproject", "word/embeddings/", "word/activex/")):
                raise ValueError("DOCX 不支持宏、嵌入对象或 ActiveX")
            if name.endswith(".xml") or name.endswith(".rels"):
                root = parse_xml(archive.read(name))
                if name == "word/document.xml" and (root.tag != f"{{{WORD_NS}}}document" or root.find("w:body", NS) is None):
                    raise ValueError("DOCX 正文不是有效的 WordprocessingML 文档")
                if name == "[Content_Types].xml" and any("macroenabled" in str(node.get("ContentType", "")).lower() for node in root):
                    raise ValueError("DOCX 不支持宏内容类型")
                if name.endswith(".rels"):
                    for node in root:
                        if node.get("TargetMode") == "External" and not node.get("Type", "").endswith("/hyperlink"):
                            raise ValueError("DOCX 不支持外链媒体、模板或连接")
                if name.startswith("word/") and name.endswith(".xml"):
                    if any(node.tag == f"{{{WORD_NS}}}{tag}" for node in root.iter() for tag in unsupported):
                        raise ValueError("DOCX 不支持修订追踪、内容控件、altChunk 或嵌入对象")
                    for node in root.findall(".//w:instrText", NS):
                        if (node.text or "").strip() not in {"PAGE", "NUMPAGES"}:
                            raise ValueError("DOCX 不支持复杂域，仅保留 PAGE/NUMPAGES 页码域")
                    for node in root.findall(".//w:fldSimple", NS):
                        if node.get(f"{{{WORD_NS}}}instr", "").strip() not in {"PAGE", "NUMPAGES"}:
                            raise ValueError("DOCX 不支持复杂域，仅保留 PAGE/NUMPAGES 页码域")


def normalize_document(data):
    title = require_text(data.get("title"), "title")
    subtitle = data.get("subtitle")
    if subtitle is not None:
        require_text(subtitle, "subtitle")
    sections = data.get("sections")
    if not isinstance(sections, list) or not sections:
        raise ValueError("sections 必须是非空数组")
    for index, section in enumerate(sections):
        if not isinstance(section, dict):
            raise ValueError(f"sections[{index}] 必须是对象")
        require_text(section.get("heading"), f"sections[{index}].heading")
        level = section.get("level", 1)
        if isinstance(level, bool) or not isinstance(level, int) or not 1 <= level <= 5:
            raise ValueError("section.level 必须是 1 至 5 的整数")
        require_bool(section.get("page_break_before", False), "section.page_break_before")
        blocks = section.get("blocks")
        if not isinstance(blocks, list) or not blocks:
            raise ValueError("section.blocks 必须是非空数组")
        for block in blocks:
            validate_block(block)
    return title, subtitle, sections


def validate_block(block):
    if not isinstance(block, dict):
        raise ValueError("block 必须是对象")
    kind = block.get("type")
    if kind == "paragraph":
        if "runs" in block:
            if "text" in block or not isinstance(block["runs"], list) or not block["runs"]:
                raise ValueError("paragraph 使用 text 或非空 runs 数组，不能同时提供")
            for run in block["runs"]:
                if not isinstance(run, dict):
                    raise ValueError("paragraph.runs 项必须是对象")
                require_text(run.get("text"), "run.text", allow_empty=True)
                for flag in ("bold", "italic", "underline"):
                    require_bool(run.get(flag, False), f"run.{flag}")
            if not any(run["text"].strip() for run in block["runs"]):
                raise ValueError("paragraph.runs 不能全部为空")
        else:
            require_text(block.get("text"), "paragraph.text")
        if block.get("alignment", "left") not in {"left", "center", "right", "justify"}:
            raise ValueError("paragraph.alignment 不支持")
        for flag in ("keep_with_next", "keep_together", "page_break_before"):
            require_bool(block.get(flag, False), f"paragraph.{flag}")
    elif kind in {"bullets", "numbered"}:
        if not isinstance(block.get("items"), list) or not block["items"]:
            raise ValueError("列表 items 必须是非空数组")
        for item in block["items"]:
            require_text(item, "list.items")
    elif kind == "table":
        headers, rows = block.get("headers"), block.get("rows")
        if not isinstance(headers, list) or not headers or not isinstance(rows, list):
            raise ValueError("table 需要非空 headers 和 rows 数组")
        for value in headers:
            require_text(value, "table.headers")
        for row in rows:
            if not isinstance(row, list) or len(row) != len(headers):
                raise ValueError("table 每行列数必须与 headers 一致")
            for value in row:
                require_text(value, "table.rows", allow_empty=True)
        widths = block.get("column_widths_mm")
        if widths is not None:
            if not isinstance(widths, list) or len(widths) != len(headers):
                raise ValueError("column_widths_mm 数量必须等于列数")
            for width in widths:
                require_number(width, "column_widths_mm", 5, 200)
        require_bool(block.get("allow_row_split", False), "table.allow_row_split")
    elif kind == "image":
        path = resolve_input(block.get("path"), "image.path", allow_template=True)
        if path.suffix.lower() not in {".png", ".jpg", ".jpeg"}:
            raise ValueError("image 只支持 PNG/JPEG")
        with path.open("rb") as stream:
            signature = stream.read(8)
        if not ((path.suffix.lower() == ".png" and signature == b"\x89PNG\r\n\x1a\n") or
                (path.suffix.lower() in {".jpg", ".jpeg"} and signature.startswith(b"\xff\xd8\xff"))):
            raise ValueError("image 内容与 PNG/JPEG 格式不符")
        require_text(block.get("alt"), "image.alt")
        require_number(block.get("width_mm", 120), "image.width_mm", 5, 200)
        if "caption" in block:
            require_text(block["caption"], "image.caption")
    elif kind != "page_break":
        raise ValueError("block.type 只能为 paragraph、bullets、numbered、table、image 或 page_break")


def markdown_text(value):
    return re.sub(r"([\\`*_{}\[\]<>#])", r"\\\1", value)


def render_markdown(title, subtitle, sections):
    lines = [f"# {markdown_text(title)}"]
    assets = []
    if subtitle:
        lines.extend(["", markdown_text(subtitle)])
    for section in sections:
        lines.extend(["", f"{'#' * (section.get('level', 1) + 1)} {markdown_text(section['heading'])}"])
        for block in section["blocks"]:
            kind = block["type"]
            lines.append("")
            if kind == "paragraph":
                if "runs" in block:
                    parts = []
                    for run in block["runs"]:
                        content = markdown_text(run["text"])
                        if run.get("bold"):
                            content = f"**{content}**"
                        if run.get("italic"):
                            content = f"*{content}*"
                        parts.append(content)
                    lines.append("".join(parts))
                else:
                    lines.append(markdown_text(block["text"]))
            elif kind in {"bullets", "numbered"}:
                lines.extend(f"{str(index + 1) + '.' if kind == 'numbered' else '-'} {markdown_text(value)}" for index, value in enumerate(block["items"]))
            elif kind == "table":
                def row_line(row):
                    return "| " + " | ".join(markdown_text(cell).replace("|", "\\|").replace("\n", "<br>") for cell in row) + " |"
                lines.extend([row_line(block["headers"]), "| " + " | ".join("---" for _ in block["headers"]) + " |"])
                lines.extend(row_line(row) for row in block["rows"])
            elif kind == "image":
                source = resolve_input(block["path"], "image.path", allow_template=True)
                asset = OUTPUT / "assets" / f"image-{len(assets) + 1}{source.suffix.lower()}"
                asset.parent.mkdir(parents=True, exist_ok=True)
                asset.write_bytes(source.read_bytes())
                assets.append(asset)
                lines.append(f"![{markdown_text(block['alt'])}](assets/{asset.name})")
                if block.get("caption"):
                    lines.extend(["", markdown_text(block["caption"])])
            elif kind == "page_break":
                lines.append("---")
    return "\n".join(lines) + "\n", assets


def set_font(font, western, cjk):
    from docx.oxml.ns import qn
    if western is not None:
        font.name = western
    if cjk is not None:
        font._element.get_or_add_rPr().get_or_add_rFonts().set(qn("w:eastAsia"), cjk)


def setup_document(document, data, is_template):
    from docx.oxml import OxmlElement
    from docx.oxml.ns import qn
    from docx.shared import Mm, Pt
    styles = data.get("styles", {})
    page = data.get("page", {})
    if not isinstance(styles, dict) or not isinstance(page, dict):
        raise ValueError("styles 和 page 必须是对象")
    western = require_text(styles.get("western_font", "Liberation Sans"), "styles.western_font")
    cjk = require_text(styles.get("cjk_font", "Noto Sans CJK SC"), "styles.cjk_font")
    size = require_number(styles.get("body_size_pt", 11), "styles.body_size_pt", 10, 14)
    if not is_template or styles:
        for name in ("Normal", "Title", "Subtitle", "List Bullet", "List Number", "Header", "Footer", "Caption", *(f"Heading {level}" for level in range(1, 6))):
            style = document.styles[name]
            set_font(style.font, western if not is_template or "western_font" in styles else None,
                     cjk if not is_template or "cjk_font" in styles else None)
            if name in {"Normal", "List Bullet", "List Number"}:
                if not is_template or "body_size_pt" in styles:
                    style.font.size = Pt(size)
                if not is_template:
                    style.paragraph_format.space_after = Pt(6)
                    style.paragraph_format.line_spacing = 1.15
        if not is_template:
            for level in range(1, 6):
                style = document.styles[f"Heading {level}"]
                style.paragraph_format.keep_with_next = True
                style.paragraph_format.keep_together = True
    size_name = page.get("size", "A4")
    if size_name not in {"A4", "Letter"}:
        raise ValueError("page.size 只能为 A4 或 Letter")
    dimensions = (210, 297) if size_name == "A4" else (215.9, 279.4)
    margin = require_number(page.get("margin_mm", 22), "page.margin_mm", 10, 50)
    show_page_number = require_bool(page.get("page_numbers", True), "page.page_numbers")
    seen_parts = set()
    for section in document.sections:
        if not is_template or "size" in page:
            section.page_width, section.page_height = (Mm(value) for value in dimensions)
        if not is_template or "margin_mm" in page:
            section.top_margin = section.bottom_margin = section.left_margin = section.right_margin = Mm(margin)
        for key in ("header", "footer"):
            part = getattr(section, key)
            if key not in page and not (key == "footer" and show_page_number and (not is_template or "page_numbers" in page)):
                continue
            if part._element in seen_parts:
                continue
            seen_parts.add(part._element)
            if key in page:
                text = require_text(page[key], f"page.{key}", allow_empty=True)
                paragraph = part.paragraphs[0]
                if is_template and paragraph.text:
                    raise ValueError("模板已有页眉页脚时不能通过 page 覆盖；使用定向修改操作")
                paragraph.add_run(text)
            if key == "footer" and show_page_number and (not is_template or "page_numbers" in page):
                has_page = any(node.get(qn("w:instr"), "").strip() == "PAGE" for node in part._element.findall(".//w:fldSimple", NS)) or any((node.text or "").strip() == "PAGE" for node in part._element.findall(".//w:instrText", NS))
                if has_page:
                    continue
                paragraph = part.add_paragraph()
                paragraph.alignment = 1
                paragraph.add_run("第 ")
                field = OxmlElement("w:fldSimple")
                field.set(qn("w:instr"), "PAGE")
                paragraph._p.append(field)
                paragraph.add_run(" 页")


def generate_docx(target, data, title, subtitle, sections):
    from docx import Document
    from docx.enum.text import WD_ALIGN_PARAGRAPH
    from docx.oxml import OxmlElement
    from docx.shared import Mm
    template_path = data.get("template_path")
    template = resolve_input(template_path, "template_path", allow_template=True) if template_path else None
    if template:
        check_docx(template)
    document = Document(template) if template else Document()
    setup_document(document, data, template is not None)
    document.add_heading(title, level=0).alignment = WD_ALIGN_PARAGRAPH.CENTER
    if subtitle:
        document.add_paragraph(subtitle, style="Subtitle").alignment = WD_ALIGN_PARAGRAPH.CENTER
    width = document.sections[-1].page_width - document.sections[-1].left_margin - document.sections[-1].right_margin
    for section in sections:
        heading = document.add_heading(section["heading"], level=section.get("level", 1))
        heading.paragraph_format.page_break_before = section.get("page_break_before", False)
        for block in section["blocks"]:
            kind = block["type"]
            if kind == "paragraph":
                paragraph = document.add_paragraph()
                paragraph.alignment = {"left": 0, "center": 1, "right": 2, "justify": 3}[block.get("alignment", "left")]
                for flag in ("keep_with_next", "keep_together", "page_break_before"):
                    setattr(paragraph.paragraph_format, flag, block.get(flag, False))
                for content in block.get("runs", [{"text": block.get("text", "")} ]):
                    run = paragraph.add_run(content["text"])
                    for flag in ("bold", "italic", "underline"):
                        if flag in content:
                            setattr(run, flag, content[flag])
            elif kind in {"bullets", "numbered"}:
                for text in block["items"]:
                    document.add_paragraph(text, style="List Bullet" if kind == "bullets" else "List Number")
            elif kind == "table":
                table = document.add_table(rows=1, cols=len(block["headers"]))
                table.style = "Table Grid"
                widths = block.get("column_widths_mm")
                if widths and Mm(sum(widths)) > width:
                    raise ValueError("表格列宽总和超过页面可用宽度")
                if widths:
                    table.autofit = False
                    for column, value in zip(table.columns, widths):
                        column.width = Mm(value)
                table.rows[0]._tr.get_or_add_trPr().append(OxmlElement("w:tblHeader"))
                for row_index, values in enumerate([block["headers"], *block["rows"]]):
                    row = table.rows[0] if row_index == 0 else table.add_row()
                    if not block.get("allow_row_split", False):
                        row._tr.get_or_add_trPr().append(OxmlElement("w:cantSplit"))
                    for column_index, (cell, text) in enumerate(zip(row.cells, values)):
                        if widths:
                            cell.width = Mm(widths[column_index])
                        run = cell.paragraphs[0].add_run(text)
                        run.bold = row_index == 0
            elif kind == "image":
                if Mm(block.get("width_mm", 120)) > width:
                    raise ValueError("图片宽度超过页面可用宽度")
                paragraph = document.add_paragraph()
                paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
                picture = paragraph.add_run().add_picture(str(resolve_input(block["path"], "image.path", allow_template=True)), width=Mm(block.get("width_mm", 120)))
                picture._inline.docPr.set("descr", block["alt"])
                if block.get("caption"):
                    document.add_paragraph(block["caption"], style="Caption").alignment = WD_ALIGN_PARAGRAPH.CENTER
            elif kind == "page_break":
                document.add_page_break()
    document.save(target)


def editable_text_nodes(paragraph):
    """目标段落只支持直接文本 run，避免跨链接、域或图片意外删除结构。"""
    nodes = []
    for child in paragraph:
        if child.tag == f"{{{WORD_NS}}}pPr":
            continue
        if child.tag != f"{{{WORD_NS}}}r":
            raise ValueError("修改目标包含链接、书签或不支持的段落结构")
        for node in child:
            if node.tag == f"{{{WORD_NS}}}rPr":
                continue
            if node.tag != f"{{{WORD_NS}}}t":
                raise ValueError("修改目标包含域、换行、图片或非文本 run")
            nodes.append(node)
    return nodes


def replace_in_paragraph(paragraph, old, new):
    nodes = editable_text_nodes(paragraph)
    text = "".join(node.text or "" for node in nodes)
    matches = list(re.finditer(re.escape(old), text))
    starts, lengths, cursor = [], [], 0
    for node in nodes:
        starts.append(cursor)
        lengths.append(len(node.text or ""))
        cursor += lengths[-1]
    # 从尾向前更新保留每个 run 的样式，替换内容继承首字符所在 run。
    for match in reversed(matches):
        first = next(index for index in range(len(nodes)) if starts[index] <= match.start() < starts[index] + lengths[index])
        last = next(index for index in range(len(nodes)) if starts[index] < match.end() <= starts[index] + lengths[index])
        prefix = (nodes[first].text or "")[:match.start() - starts[first]]
        suffix = (nodes[last].text or "")[match.end() - starts[last]:]
        nodes[first].text = prefix + new + (suffix if first == last else "")
        for index in range(first + 1, last):
            nodes[index].text = ""
        if first != last:
            nodes[last].text = suffix
        for index in range(first, last + 1):
            nodes[index].set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
    return len(matches)


def modify_docx(target, data):
    from lxml import etree
    source = resolve_input(data.get("source_path"), "source_path")
    check_docx(source)
    operations = data.get("operations")
    if not isinstance(operations, list) or not operations:
        raise ValueError("modify.operations 必须是非空数组")
    with ZipFile(source) as archive:
        modified = {}
        counts = []
        body = parse_xml(archive.read("word/document.xml"))
        for operation in operations:
            if not isinstance(operation, dict):
                raise ValueError("operations 项必须是对象")
            kind = operation.get("type")
            if kind == "replace_text":
                old = require_text(operation.get("find"), "replace_text.find")
                new = require_text(operation.get("replace"), "replace_text.replace", allow_empty=True)
                expected = operation.get("expected_count")
                if isinstance(expected, bool) or not isinstance(expected, int) or expected < 1:
                    raise ValueError("replace_text.expected_count 必须是正整数")
                scope = operation.get("scope", "body")
                if scope not in {"body", "headers", "footers"}:
                    raise ValueError("replace_text.scope 只能为 body、headers 或 footers")
                names = ["word/document.xml"] if scope == "body" else [name for name in archive.namelist() if re.fullmatch(r"word/" + ("header" if scope == "headers" else "footer") + r"\d+\.xml", name)]
                count = 0
                for name in names:
                    root = body if name == "word/document.xml" else modified.get(name)
                    if root is None:
                        root = parse_xml(archive.read(name))
                    part_count = 0
                    for paragraph in root.findall(".//w:p", NS):
                        text = "".join(node.text or "" for node in paragraph.findall(".//w:t", NS))
                        if old in text:
                            part_count += replace_in_paragraph(paragraph, old, new)
                    count += part_count
                    if part_count:
                        modified[name] = root
                if count != expected:
                    raise ValueError(f"replace_text 实际匹配 {count} 次，与 expected_count={expected} 不符；不生成候选")
                counts.append(count)
            elif kind == "set_table_cell":
                indexes = []
                for key in ("table", "row", "cell"):
                    index = operation.get(key)
                    if isinstance(index, bool) or not isinstance(index, int) or index < 0:
                        raise ValueError(f"set_table_cell.{key} 必须是从零开始的非负整数")
                    indexes.append(index)
                old = require_text(operation.get("expected_text"), "set_table_cell.expected_text", allow_empty=True)
                new = require_text(operation.get("text"), "set_table_cell.text", allow_empty=True)
                try:
                    table = body.find("w:body", NS).findall("w:tbl", NS)[indexes[0]]
                    row = table.findall("w:tr", NS)[indexes[1]]
                    cell = row.findall("w:tc", NS)[indexes[2]]
                except (IndexError, AttributeError) as error:
                    raise ValueError("set_table_cell 指定表格位置不存在") from error
                if cell.find(".//w:gridSpan", NS) is not None or cell.find(".//w:vMerge", NS) is not None:
                    raise ValueError("set_table_cell 不支持合并单元格")
                paragraphs = cell.findall("w:p", NS)
                if len(paragraphs) != 1 or cell.find("w:tbl", NS) is not None:
                    raise ValueError("set_table_cell 仅支持单个普通段落的单元格")
                nodes = editable_text_nodes(paragraphs[0])
                if "".join(node.text or "" for node in nodes) != old:
                    raise ValueError("set_table_cell 原文与 expected_text 不符")
                if old:
                    replace_in_paragraph(paragraphs[0], old, new)
                elif nodes:
                    nodes[0].text = new
                    nodes[0].set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
                else:
                    run = etree.SubElement(paragraphs[0], f"{{{WORD_NS}}}r")
                    text_node = etree.SubElement(run, f"{{{WORD_NS}}}t")
                    text_node.text = new
                    text_node.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
                modified["word/document.xml"] = body
                counts.append(1)
            else:
                raise ValueError("operation.type 只能为 replace_text 或 set_table_cell")
        with ZipFile(target, "w") as result:
            for item in archive.infolist():
                payload = etree.tostring(modified[item.filename], xml_declaration=True, encoding="UTF-8", standalone=True) if item.filename in modified else archive.read(item.filename)
                result.writestr(item, payload)
    return counts


def inspect_docx(data):
    source = resolve_input(data.get("source_path"), "source_path")
    check_docx(source)
    with ZipFile(source) as archive:
        root = parse_xml(archive.read("word/document.xml"))
        body = root.find("w:body", NS)
        text = lambda node: "".join(item.text or "" for item in node.findall(".//w:t", NS))
        paragraphs = [{"index": index, "text": text(paragraph)} for index, paragraph in enumerate(body.findall("w:p", NS))]
        tables = [{"index": index, "rows": [[text(cell) for cell in row.findall("w:tc", NS)] for row in table.findall("w:tr", NS)]}
                  for index, table in enumerate(body.findall("w:tbl", NS))]
        other_parts = {name: [text(paragraph) for paragraph in parse_xml(archive.read(name)).findall(".//w:p", NS)]
                       for name in archive.namelist() if re.fullmatch(r"word/(header|footer)\d+\.xml", name)}
    return {"paragraphs": paragraphs, "tables": tables, "headers_footers": other_parts}


def output_filename(data, output_format):
    extension = "md" if output_format == "markdown" else "docx"
    name = data.get("output_name", "document." + extension)
    if not isinstance(name, str) or not name or name != name.strip() or unicodedata.normalize("NFKC", name) != name:
        raise ValueError("output_name 必须是规范化的安全文件名")
    if any(ord(char) < 32 or 127 <= ord(char) <= 159 or char in '\\/:*?"<>|' for char in name):
        raise ValueError("output_name 不能包含路径或控制字符")
    if len(name) > 200 or len(name.encode("utf-8")) > 255 or not name.lower().endswith("." + extension):
        raise ValueError("output_name 长度或实际候选扩展名不匹配")
    stem = name.rsplit(".", 1)[0].rstrip(" .").lower()
    if stem in {"con", "prn", "aux", "nul", *["com" + str(i) for i in range(1, 10)], *["lpt" + str(i) for i in range(1, 10)]}:
        raise ValueError("output_name 不能使用保留名")
    if name in {"result.json", "document.zip"}:
        raise ValueError("output_name 与执行结果或压缩包保留位置冲突")
    return name


def main():
    data = json.loads(locate_input("document.json").read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("document.json 必须是对象")
    requested_format = data.get("format", "markdown")
    if requested_format not in {"markdown", "docx", "pdf"}:
        raise ValueError("format 只能是 markdown、docx 或 pdf")
    action = data.get("action", "create")
    if action not in {"create", "modify", "inspect"}:
        raise ValueError("action 只能是 create、inspect 或 modify")
    bundle = require_bool(data.get("bundle_zip", False), "bundle_zip")
    if bundle and requested_format == "pdf":
        raise ValueError("PDF 的 ZIP 需在 render_artifact 返回 PDF 后单独按用户请求打包")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if action == "inspect":
        if bundle:
            raise ValueError("inspect 不生成正式成果或 ZIP")
        result = {"results": [{"kind": "text", "format": "plain", "text": json.dumps(inspect_docx(data), ensure_ascii=False)}]}
        (OUTPUT / "result.json").write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
        print(json.dumps({"action": action, "status": "inspected"}, ensure_ascii=False))
        return
    output_format = "docx" if requested_format == "pdf" else requested_format
    target = OUTPUT / output_filename(data, output_format)
    assets = []
    if action == "modify":
        if output_format != "docx":
            raise ValueError("既有 DOCX 修改只支持 docx 或经 render_artifact 导出的 pdf")
        counts = modify_docx(target, data)
        summary = {"operations": len(counts), "matches": counts}
    else:
        title, subtitle, sections = normalize_document(data)
        if output_format == "markdown":
            if data.get("template_path"):
                raise ValueError("DOCX 模板不能用于 Markdown 创建")
            markdown, assets = render_markdown(title, subtitle, sections)
            target.write_text(markdown, encoding="utf-8")
        else:
            generate_docx(target, data, title, subtitle, sections)
        summary = {"title": title, "sections": len(sections)}
    bundle_name = None
    if bundle:
        bundle_target = OUTPUT / "document.zip"
        with ZipFile(bundle_target, "w", compression=ZIP_DEFLATED) as archive:
            for path in [target, *assets]:
                archive.write(path, arcname=path.relative_to(OUTPUT).as_posix())
        bundle_name = bundle_target.name
    print(json.dumps({"action": action, "format": requested_format, "candidate_format": output_format,
                      "output": target.name, "assets": [path.relative_to(OUTPUT).as_posix() for path in assets],
                      "bundle": bundle_name, **summary}, ensure_ascii=False))


if __name__ == "__main__":
    main()
