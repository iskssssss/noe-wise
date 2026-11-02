"""受信产物渲染脚本：仅由 RenderArtifactToolHandler 以固定格式调用。"""
import csv
import html
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from openpyxl import load_workbook

INPUT_ROOT = Path("/input/artifacts")
OUTPUT = Path("/workspace/output")
MAX_SOURCE_BYTES = 200 * 1024 * 1024
MAX_PAGES = 20
MAX_TABLE_ROWS = 500
MAX_TABLE_COLUMNS = 50
MAX_CELL_CHARS = 2_000
MACRO_EXTENSIONS = {".docm", ".xlsm", ".pptm"}
OFFICE_EXTENSIONS = {".docx", ".odt", ".xlsx", ".ods", ".pptx", ".odp"}


def source_file():
    files = [path for path in INPUT_ROOT.rglob("*") if path.is_file()]
    if len(files) != 1:
        raise ValueError("需要且只能提供一个产物文件")
    source = files[0].resolve()
    root = INPUT_ROOT.resolve()
    if root not in source.parents or source.stat().st_size > MAX_SOURCE_BYTES:
        raise ValueError("产物文件不可渲染")
    if source.suffix.lower() in MACRO_EXTENSIONS:
        raise ValueError("不支持包含宏的文件")
    return source


def command(arguments):
    subprocess.run(arguments, check=True, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                   stderr=subprocess.PIPE, timeout=90)


def pdf_from(source, output_name="rendered.pdf"):
    if source.suffix.lower() == ".pdf":
        target = OUTPUT / output_name
        shutil.copyfile(source, target)
        return target
    if source.suffix.lower() not in OFFICE_EXTENSIONS:
        raise ValueError("该文件类型不能转换为 PDF")
    target = OUTPUT / output_name
    # LibreOffice 的缓存与配置不属于交付文件，不进入 output 清单。
    with tempfile.TemporaryDirectory(prefix="render-", dir="/workspace/work") as scratch:
        converted = Path(scratch) / "converted"
        profile = Path(scratch) / "lo-profile"
        converted.mkdir()
        profile.mkdir()
        command(["libreoffice", "--headless", "--safe-mode", "--nologo", "--nodefault", "--norestore",
                 f"-env:UserInstallation={profile.resolve().as_uri()}", "--convert-to", "pdf",
                 "--outdir", str(converted), str(source)])
        generated = list(converted.glob("*.pdf"))
        if len(generated) != 1:
            raise ValueError("PDF 转换未生成唯一结果")
        shutil.move(generated[0], target)
    return target


def pages_from(pdf):
    info = subprocess.run(["pdfinfo", str(pdf)], check=True, stdin=subprocess.DEVNULL,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=30)
    match = re.search(r"^Pages:\s+(\d+)\s*$", info.stdout, re.MULTILINE)
    if match is None or not 1 <= int(match.group(1)) <= MAX_PAGES:
        raise ValueError("PDF 页数超过预览上限")
    pages = int(match.group(1))
    target = OUTPUT / "pages"
    target.mkdir(parents=True, exist_ok=True)
    command(["pdftoppm", "-png", "-r", "144", "-f", "1", "-l", str(pages), str(pdf),
             str(target / "page")])
    if len(list(target.glob("page-*.png"))) != pages:
        raise ValueError("页面预览不完整")


def bounded_rows(source):
    suffix = source.suffix.lower()
    if suffix in {".csv", ".tsv"}:
        with source.open("r", encoding="utf-8-sig", newline="") as input_file:
            rows = list(csv.reader(input_file, delimiter="\t" if suffix == ".tsv" else ","))
    elif suffix == ".xlsx":
        workbook = load_workbook(source, read_only=True, data_only=True, keep_links=False)
        try:
            rows = [["" if cell is None else str(cell) for cell in row]
                    for row in workbook.active.iter_rows(values_only=True)]
        finally:
            workbook.close()
    else:
        raise ValueError("静态表格预览仅支持 CSV、TSV 或 XLSX")
    if not rows or len(rows) > MAX_TABLE_ROWS or max(len(row) for row in rows) > MAX_TABLE_COLUMNS:
        raise ValueError("表格数据超过预览上限")
    if any(len(cell) > MAX_CELL_CHARS for row in rows for cell in row):
        raise ValueError("表格单元格超过预览上限")
    return rows


def table_from(source, output_name="index.html"):
    rows = bounded_rows(source)
    header, body = rows[0], rows[1:]
    head = "".join(f"<th>{html.escape(cell)}</th>" for cell in header)
    lines = ["<tr>" + "".join(f"<td>{html.escape(cell)}</td>" for cell in row) + "</tr>" for row in body]
    document = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>表格预览</title>" \
        "<style>body{font:14px system-ui;margin:24px;color:#172033}table{border-collapse:collapse;max-width:100%}" \
        "th,td{border:1px solid #d7deea;padding:8px;text-align:left;vertical-align:top;max-width:360px;word-break:break-word}" \
        "th{background:#edf3ff}</style><h1>" + html.escape(source.name) + "</h1><table><thead><tr>" + head \
        + "</tr></thead><tbody>" + "".join(lines) + "</tbody></table></html>"
    (OUTPUT / output_name).write_text(document, encoding="utf-8")


def main(render_format, output_name=None):
    OUTPUT.mkdir(parents=True, exist_ok=True)
    source = source_file()
    if render_format == "pdf":
        pdf_from(source, output_name or "rendered.pdf")
    elif render_format == "pages":
        pages_from(pdf_from(source))
    elif render_format == "table":
        table_from(source, output_name or "index.html")
    else:
        raise ValueError("不支持的渲染格式")
