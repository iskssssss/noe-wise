"""读取明确挂载的文档并返回可分页正文；SDK和模型来自冻结运行镜像。"""
import hashlib
import importlib.metadata
import json
import os
import re
from pathlib import Path, PurePosixPath
from zipfile import ZipFile

PACKAGE = Path(__file__).resolve().parent.parent
INPUT_DATA = Path("/input/data")
INPUT_ARTIFACTS = Path("/input/artifacts")
OUTPUT = Path("/workspace/output")
WORK = Path("/workspace/work")
MODELS = Path("/opt/skillforge/docling-models")
MAX_BYTES = 50 * 1024 * 1024
MAX_PAGES = 100
MAX_PIXELS = 40_000_000
MAX_EXPANDED_BYTES = 128 * 1024 * 1024
MAX_RESULT_BYTES = 8 * 1024 * 1024
SUPPORTED = {".pdf", ".docx", ".pptx", ".xlsx", ".png", ".jpg", ".jpeg"}


def integer(value, label, minimum, maximum):
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ValueError(f"{label} 必须是 {minimum} 至 {maximum} 的整数")
    return value


def source_file(value):
    if not isinstance(value, str) or not value or "\\" in value or "\x00" in value:
        raise ValueError("source_path 必须是明确挂载的绝对路径")
    lexical = PurePosixPath(value)
    if not lexical.is_absolute() or ".." in lexical.parts:
        raise ValueError("source_path 路径不合法")
    path = Path(value).resolve()
    if not any(path.is_relative_to(root.resolve()) and path != root.resolve()
               for root in (INPUT_DATA, INPUT_ARTIFACTS)) or not path.is_file():
        raise ValueError("source_path 不在明确的输入挂载内")
    if not 0 < path.stat().st_size <= MAX_BYTES:
        raise ValueError("文档必须非空且不超过50MiB")
    if path.suffix.lower() not in SUPPORTED:
        raise ValueError("仅支持PDF、DOCX、PPTX、XLSX和PNG/JPEG")
    return path


def check_office(path):
    """产物输入与上传输入采用相同安全边界；不按 ZIP 包名信任内容。"""
    from defusedxml import ElementTree as ET
    from defusedxml.common import DefusedXmlException
    from openpyxl.utils.cell import range_boundaries
    main = {".docx": "word/document.xml", ".pptx": "ppt/presentation.xml", ".xlsx": "xl/workbook.xml"}[path.suffix.lower()]
    with ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(entries) > 4096 or len(names) != len(set(names)) or sum(e.file_size for e in entries) > MAX_EXPANDED_BYTES:
            raise ValueError("Office 包超出容量或有重名项")
        if "[Content_Types].xml" not in names or main not in names:
            raise ValueError("Office 内容与扩展名不符")
        slides = sum(bool(re.fullmatch(r"ppt/slides/slide[0-9]+\.xml", name)) for name in names)
        if slides > MAX_PAGES:
            raise ValueError("PPTX不能超过100页")
        cells = 0
        for entry in entries:
            name = entry.filename; lower = name.lower()
            if name.startswith("/") or ".." in PurePosixPath(name).parts or "\\" in name or ":" in name \
                    or entry.flag_bits & 1 or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Office 包含非法路径、加密项或符号链接")
            if any(part in lower for part in ("vbaproject", "/embeddings/", "/activex/")):
                raise ValueError("Office 不支持宏、嵌入对象或ActiveX")
            xml = lower.endswith((".xml", ".rels"))
            if entry.file_size > (8 if xml else 32) * 1024 * 1024 \
                    or (entry.file_size > 1024 * 1024 and entry.file_size > max(entry.compress_size, 1) * 200):
                raise ValueError("Office 单项或压缩率超出容量")
            if not xml:
                continue
            data = archive.read(entry)
            try:
                root = ET.fromstring(data, forbid_dtd=True, forbid_entities=True, forbid_external=True)
            except DefusedXmlException as unsafe:
                raise ValueError("Office XML不支持DTD或实体") from unsafe
            if name == "[Content_Types].xml" and any("macroenabled" in node.get("ContentType", "").lower() for node in root):
                raise ValueError("Office不支持宏内容类型")
            if lower.endswith(".rels") and any(node.get("TargetMode") == "External" and not node.get("Type", "").endswith("/hyperlink") for node in root):
                raise ValueError("Office不支持外链媒体或连接")
            if lower.startswith("xl/worksheets/"):
                for node in root.iter():
                    if node.tag.rsplit("}", 1)[-1] == "c":
                        cells += 1
                        bounds = range_boundaries(node.get("r", "A1"))
                        if bounds[0] > 256 or bounds[1] > 10000:
                            raise ValueError("XLSX读取范围不能超过10000行、256列")
                    if node.tag.rsplit("}", 1)[-1] in {"mergeCell", "dimension"} and node.get("ref"):
                        bounds = range_boundaries(node.get("ref"))
                        if bounds[2] > 256 or bounds[3] > 10000:
                            raise ValueError("XLSX声明范围不能超过10000行、256列")
                if cells > 100000:
                    raise ValueError("XLSX单元格总数不能超过100000")
        return {"pages_total": slides if path.suffix.lower() == ".pptx" else None}


def check_source(path):
    if path.suffix.lower() in {".docx", ".pptx", ".xlsx"}:
        return check_office(path)
    if path.suffix.lower() == ".pdf":
        import pypdfium2
        with path.open("rb") as stream:
            signature = stream.read(5)
        if signature != b"%PDF-":
            raise ValueError("PDF内容与扩展名不符")
        with pypdfium2.PdfDocument(path) as pdf:
            pages = len(pdf)
        if not 1 <= pages <= MAX_PAGES:
            raise ValueError("PDF必须为1至100页且无需密码")
        return {"pages_total": pages}
    from PIL import Image
    Image.MAX_IMAGE_PIXELS = MAX_PIXELS
    with Image.open(path) as image:
        expected = "PNG" if path.suffix.lower() == ".png" else "JPEG"
        if image.format != expected or image.width * image.height > MAX_PIXELS or image.width <= 0 or image.height <= 0:
            raise ValueError("图片类型不符或超过4000万像素")
        image.verify()
    return {"pages_total": 1}


def require_runtime(models):
    manifest = json.loads((PACKAGE / "manifest.json").read_text(encoding="utf-8"))
    expected = manifest["runtime_requirements"]
    for item in expected["python"]:
        if importlib.metadata.version(item["name"]) != item["version"]:
            raise RuntimeError("冻结运行镜像版本不符：" + item["name"])
    if not models:
        return
    lock_file = MODELS / "models.lock.json"
    if not lock_file.is_file() or hashlib.sha256(lock_file.read_bytes()).hexdigest() != expected["model_lock_sha256"]:
        raise RuntimeError("冻结模型清单缺失或摘要不符")
    for model in json.loads(lock_file.read_text(encoding="utf-8"))["models"]:
        for item in model["files"]:
            path = MODELS / model["directory"] / item["path"]
            if path.is_symlink() or not path.is_file() or path.stat().st_size != item["bytes"]:
                raise RuntimeError("冻结模型缺失或大小不符")
            digest = hashlib.sha256()
            with path.open("rb") as source:
                while part := source.read(1024 * 1024):
                    digest.update(part)
            if digest.hexdigest() != item["sha256"]:
                raise RuntimeError("冻结模型摘要不符")


def converter(path, data):
    from docling.datamodel.base_models import InputFormat
    from docling.datamodel.accelerator_options import AcceleratorDevice, AcceleratorOptions
    from docling.datamodel.pipeline_options import (LayoutObjectDetectionOptions, NativePdfPipelineOptions,
                                                  PdfPipelineOptions, TableFormerMode, TesseractCliOcrOptions)
    from docling.document_converter import DocumentConverter, ImageFormatOption, NativePdfFormatOption, PdfFormatOption
    formats = {".docx": InputFormat.DOCX, ".pptx": InputFormat.PPTX, ".xlsx": InputFormat.XLSX,
               ".pdf": InputFormat.PDF, ".png": InputFormat.IMAGE, ".jpg": InputFormat.IMAGE, ".jpeg": InputFormat.IMAGE}
    selected = formats[path.suffix.lower()]
    options = {}
    if selected in {InputFormat.PDF, InputFormat.IMAGE}:
        timeout = integer(data.get("timeout_seconds", 120), "timeout_seconds", 10, 300)
        if selected == InputFormat.PDF and data.get("pdf_pipeline", "structured") == "native":
            require_runtime(False)
            options[selected] = NativePdfFormatOption(pipeline_options=NativePdfPipelineOptions(
                document_timeout=timeout, parser_threads=2, generate_page_images=False, generate_picture_images=False,
                enable_remote_services=False, allow_external_plugins=False))
        else:
            require_runtime(True)
            import torch
            torch.set_num_threads(2)
            pipeline = PdfPipelineOptions(artifacts_path=MODELS, do_ocr=data.get("ocr", True),
                do_table_structure=data.get("tables", True), document_timeout=timeout,
                accelerator_options=AcceleratorOptions(device=AcceleratorDevice.CPU, num_threads=2),
                ocr_options=TesseractCliOcrOptions(lang=["chi_sim", "eng"], tesseract_cmd="/usr/bin/tesseract",
                                                  force_full_page_ocr=data.get("force_ocr", False)),
                enable_remote_services=False, allow_external_plugins=False, generate_page_images=False,
                generate_picture_images=False, do_picture_classification=False, do_picture_description=False,
                layout_batch_size=1, table_batch_size=1, ocr_batch_size=1, queue_max_size=2)
            layout = LayoutObjectDetectionOptions()
            layout.model_spec.revision = "8f39ad3c0b4c58e9c2d2c84a38465abf757272d8"
            pipeline.layout_options = layout
            pipeline.table_structure_options.mode = TableFormerMode.ACCURATE
            options[selected] = (PdfFormatOption if selected == InputFormat.PDF else ImageFormatOption)(pipeline_options=pipeline)
    else:
        require_runtime(False)
    return DocumentConverter(allowed_formats=[selected], format_options=options)


def validate_request(data):
    if not isinstance(data, dict):
        raise ValueError("document-read.json必须为对象")
    allowed = {"source_path", "action", "format", "page_start", "page_end", "max_pages", "text_offset",
               "text_limit", "pdf_pipeline", "ocr", "force_ocr", "tables", "timeout_seconds"}
    if set(data) - allowed:
        raise ValueError("存在不支持的读取字段")
    if data.get("action", "read") not in {"read", "export"}:
        raise ValueError("action只能为read或export")
    if data.get("format", "markdown") not in {"markdown", "json"}:
        raise ValueError("format只能为markdown或json")
    if data.get("pdf_pipeline", "structured") not in {"native", "structured"}:
        raise ValueError("pdf_pipeline只能为native或structured")
    for key in ("ocr", "force_ocr", "tables"):
        if key in data and not isinstance(data[key], bool):
            raise ValueError(key + "必须为布尔值")
    if data.get("force_ocr") and data.get("ocr") is False:
        raise ValueError("force_ocr需要启用ocr")


def main():
    config = source_file_config()
    validate_request(config)
    path = source_file(config.get("source_path"))
    metadata = check_source(path)
    pages = metadata["pages_total"]
    paginated = path.suffix.lower() in {".pdf", ".png", ".jpg", ".jpeg"}
    if not paginated and any(key in config for key in ("page_start", "page_end", "max_pages")):
        raise ValueError("Office采用原生结构读取，不支持按打印页截取")
    max_pages = integer(config.get("max_pages", 20), "max_pages", 1, MAX_PAGES)
    start = integer(config.get("page_start", 1), "page_start", 1, pages or MAX_PAGES)
    end = integer(config.get("page_end", min(pages or MAX_PAGES, start + max_pages - 1)), "page_end", start, pages or MAX_PAGES)
    if end - start + 1 > max_pages:
        raise ValueError("请求页数超过max_pages")
    WORK.mkdir(parents=True, exist_ok=True)
    for key in ("HF_HUB_OFFLINE", "TRANSFORMERS_OFFLINE", "HF_DATASETS_OFFLINE"):
        os.environ[key] = "1"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    os.environ["OMP_NUM_THREADS"] = "2"
    for key, folder in (("HOME", "home"), ("HF_HOME", "hf"), ("TMPDIR", "tmp"), ("DOCLING_CACHE_DIR", "docling")):
        target = WORK / folder; target.mkdir(exist_ok=True); os.environ[key] = str(target)
    engine = converter(path, config)
    arguments = {"max_file_size": MAX_BYTES, "max_num_pages": MAX_PAGES, "raises_on_error": False}
    if paginated:
        arguments["page_range"] = (start, end)
    result = engine.convert(path, **arguments)
    from docling.datamodel.base_models import ConversionStatus
    if result.status != ConversionStatus.SUCCESS:
        raise RuntimeError("文档解析未完整成功；不交付部分转换：" + result.status.value)
    markdown = result.document.export_to_markdown(page_break_placeholder="<!-- page break -->")
    if len(markdown.encode("utf-8")) > MAX_RESULT_BYTES:
        raise ValueError("解析正文超过8MiB，需要缩小读取页范围")
    if not markdown.strip():
        raise ValueError("未提取到可读正文；检查扫描质量、OCR选项或原件")
    offset = integer(config.get("text_offset", 0), "text_offset", 0, len(markdown))
    limit = integer(config.get("text_limit", 12000), "text_limit", 256, 24000)
    excerpt = markdown[offset:offset + limit]
    next_offset = offset + len(excerpt)
    ocr_enabled = paginated and (path.suffix.lower() != ".pdf" or config.get("pdf_pipeline", "structured") != "native") and config.get("ocr", True)
    summary = {"conversion_status": "success", "source_format": path.suffix.lower()[1:], "pages_total": pages,
               "pages_selected": [start, end] if paginated else None,
               "page_content_complete": not paginated or (start == 1 and end == pages),
               "text_characters": len(markdown), "text_offset": offset, "next_text_offset": next_offset,
               "has_more_text": next_offset < len(markdown), "tables_found": len(result.document.tables),
               "pipeline": config.get("pdf_pipeline", "structured") if path.suffix.lower() == ".pdf" else
                           "ocr-layout" if paginated else "native-office",
               "ocr_enabled": ocr_enabled,
               "warnings": ["OCR文字与表格须对照原件复核"] if ocr_enabled else []}
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if config.get("action", "read") == "export":
        if config.get("format", "markdown") == "markdown":
            (OUTPUT / "document.md").write_text(markdown, encoding="utf-8")
        else:
            document = result.document.export_to_dict()
            # JSON保留页坐标和表格结构，不导出图片像素或工作目录URI。
            for picture in document.get("pictures", []):
                picture.pop("image", None)
            for page in document.get("pages", {}).values():
                page.pop("image", None)
            encoded = json.dumps({"reading": summary, "document": document}, ensure_ascii=False)
            if len(encoded.encode("utf-8")) > MAX_RESULT_BYTES:
                raise ValueError("结构化JSON超过8MiB，需要缩小读取页范围")
            (OUTPUT / "document.json").write_text(encoded, encoding="utf-8")
    public = {"results": [{"kind": "text", "format": "plain", "text": json.dumps(summary, ensure_ascii=False)},
                          {"kind": "text", "format": "markdown", "text": excerpt}]}
    (OUTPUT / "result.json").write_text(json.dumps(public, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"conversion_status": "success", "action": config.get("action", "read")}, ensure_ascii=False))


def source_file_config():
    path = INPUT_DATA / "document-read.json"
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 64 * 1024:
        raise ValueError("需要唯一的/input/data/document-read.json受管输入，配置不能超过64KiB")
    return json.loads(path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
