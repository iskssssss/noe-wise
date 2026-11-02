import { mkdir, readFile, readdir, realpath, stat, writeFile } from "node:fs/promises";
import { isAbsolute, join, relative, resolve, sep } from "node:path";
import { pathToFileURL } from "node:url";
import pptxgen from "pptxgenjs";
import sizeOf from "image-size";
import { resolveTheme } from "./theme.mjs";

const PAGE = { width: 13.333333, height: 7.5, x: 0.7, y: 1.85, widthBody: 11.93, heightBody: 4.8 };
const TYPES = new Set(["title", "section", "bullets", "two-column", "image", "metrics", "table", "chart", "summary"]);

function object(value, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${label} 必须是对象`);
  return value;
}

function fields(value, allowed, label) {
  object(value, label);
  for (const key of Object.keys(value)) if (!allowed.includes(key)) throw new Error(`${label}.${key} 是不支持的字段`);
}

function text(value, label, required = true) {
  if (value === undefined && !required) return undefined;
  if (typeof value !== "string" || !value.trim()) throw new Error(`${label} 必须是非空字符串`);
  if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/u.test(value)) throw new Error(`${label} 含非法控制字符`);
  return value.trim();
}

function list(value, label, maximum) {
  if (!Array.isArray(value) || !value.length || value.length > maximum) throw new Error(`${label} 必须是 1–${maximum} 项的数组`);
  return value;
}

// 按宽字符与保守行高预算分页，不依赖 PowerPoint 自动缩字隐藏溢出。渲染仍须检查实际字体度量。
function charWidth(character) {
  if (character === "\t") return 2.4;
  if (/[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}\p{Extended_Pictographic}]/u.test(character) || character.codePointAt(0) >= 0xff00) return 1;
  return /[MW@#%&]/u.test(character) ? 0.9 : 0.62;
}

function lineUnits(width, fontSize) { return width * 72 / fontSize * 0.87; }
function lineHeight(fontSize) { return fontSize / 72 * 1.4; }

function countLines(value, width, fontSize) {
  let lines = 1;
  let units = 0;
  const limit = lineUnits(width, fontSize);
  for (const character of value) {
    if (character === "\n") { lines += 1; units = 0; continue; }
    const next = charWidth(character);
    if (units && units + next > limit) { lines += 1; units = 0; }
    units += next;
  }
  return lines;
}

function fits(value, width, height, fontSize, label) {
  if (countLines(value, width, fontSize) * lineHeight(fontSize) > height + 0.001) {
    throw new Error(`${label} 超出版式预算；缩短内容、拆页或使用更合适的版式，不能继续缩小字体`);
  }
}

function splitText(value, width, fontSize, maxLines) {
  const chunks = [];
  let chunk = "";
  let units = 0;
  let lines = 1;
  const limit = lineUnits(width, fontSize);
  for (const character of value) {
    const next = charWidth(character);
    const addsLine = character === "\n" || (units > 0 && units + next > limit);
    if (addsLine && lines === maxLines) {
      chunks.push(chunk);
      chunk = ""; units = 0; lines = character === "\n" ? 2 : 1;
    } else if (addsLine) {
      lines += 1; units = 0;
    }
    chunk += character;
    if (character !== "\n") units += next;
  }
  if (chunk) chunks.push(chunk);
  return chunks;
}

function bulletPages(values, width, fontSize, height, label) {
  const bullets = list(values, label, 500).map((value, index) => text(value, `${label}[${index}]`));
  const maxLines = Math.floor((height - 0.15) / lineHeight(fontSize));
  const pages = [];
  let items = [];
  let used = 0;
  for (const [sourceItem, value] of bullets.entries()) {
    for (const [piece, content] of splitText(value, width - 0.28, fontSize, maxLines).entries()) {
      const itemHeight = countLines(content, width - 0.28, fontSize) * lineHeight(fontSize) + 0.15;
      if (items.length && used + itemHeight > height + 0.001) { pages.push(items); items = []; used = 0; }
      if (itemHeight > height + 0.001) throw new Error(`${label}[${sourceItem}] 无法安全分页`);
      items.push({ text: content, height: itemHeight, source_item: sourceItem, continuation: piece > 0 });
      used += itemHeight;
    }
  }
  if (items.length) pages.push(items);
  return pages;
}

function tableCell(value, label) {
  if (value === null) return "";
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  if (typeof value === "boolean") return String(value);
  if (typeof value === "string" && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/u.test(value)) return value;
  throw new Error(`${label} 只支持文本、有限数字、布尔值或 null`);
}

function chartData(value, label) {
  fields(value, ["type", "series"], label);
  if (!["bar", "line", "pie"].includes(value.type)) throw new Error(`${label}.type 只支持 bar、line、pie`);
  const series = list(value.series, `${label}.series`, value.type === "pie" ? 1 : 4).map((item, index) => {
    const itemLabel = `${label}.series[${index}]`;
    fields(item, ["name", "labels", "values"], itemLabel);
    const name = text(item.name, `${itemLabel}.name`);
    fits(name, 3, 0.5, 14, `${itemLabel}.name`);
    const labels = list(item.labels, `${itemLabel}.labels`, 12).map((entry, labelIndex) => {
      const result = text(entry, `${itemLabel}.labels[${labelIndex}]`);
      fits(result, 1.1, 0.6, 12, `${itemLabel}.labels[${labelIndex}]`);
      return result;
    });
    if (!Array.isArray(item.values) || item.values.length !== labels.length || item.values.some(entry => typeof entry !== "number" || !Number.isFinite(entry))) throw new Error(`${itemLabel}.values 必须与 labels 等长且只含有限数字`);
    if (value.type === "pie" && (item.values.some(entry => entry < 0) || !item.values.some(entry => entry > 0))) throw new Error(`${itemLabel}.values 的饼图数值必须非负且总和大于零`);
    return { name, labels, values: [...item.values] };
  });
  if (series.some(item => JSON.stringify(item.labels) !== JSON.stringify(series[0].labels))) throw new Error(`${label} 的所有系列必须使用相同 labels`);
  return { type: value.type, series };
}

function outputFilename(data) {
  const name = data.output_name === undefined ? "presentation.pptx" : data.output_name;
  if (typeof name !== "string" || !name || name !== name.trim() || name.normalize("NFKC") !== name) {
    throw new Error("output_name 必须是非空、无首尾空白且已为 NFKC 的文件名");
  }
  if (/[\u0000-\u001f\u007f-\u009f\\/:*?"<>|]/u.test(name)) throw new Error("output_name 含非法控制字符或路径字符");
  if ([...name].length > 200 || Buffer.byteLength(name, "utf8") > 255 || !name.toLowerCase().endsWith(".pptx")) {
    throw new Error("output_name 必须以 .pptx 结尾，最多 200 字符和 255 UTF-8 字节");
  }
  const stem = name.slice(0, -5).replace(/[ .]+$/u, "").toLowerCase();
  if (/^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])$/u.test(stem)) throw new Error("output_name 不能使用 Windows 保留名称");
  return name;
}

export function preparePresentation(data) {
  fields(data, ["schema_version", "title", "theme", "slides", "bundle_zip", "output_name"], "presentation");
  if (data.schema_version !== 2) throw new Error("schema_version 必须为 2；不推断旧版输入");
  const output_name = outputFilename(data);
  const title = text(data.title, "title");
  if (data.bundle_zip !== undefined && typeof data.bundle_zip !== "boolean") throw new Error("bundle_zip 必须是布尔值");
  const theme = resolveTheme(data.theme);
  const pages = [];
  for (const [sourceIndex, raw] of list(data.slides, "slides", 100).entries()) {
    const label = `slides[${sourceIndex}]`;
    object(raw, label);
    if (!TYPES.has(raw.type)) throw new Error(`${label}.type 必须是支持的版式`);
    const extraFields = { title: [], section: [], bullets: ["bullets"], summary: ["bullets"], "two-column": ["left", "right"], image: ["image", "caption", "bullets"], metrics: ["metrics"], table: ["columns", "rows"], chart: ["chart"] }[raw.type];
    fields(raw, ["type", "title", "subtitle", ...extraFields], label);
    const spec = { type: raw.type, title: text(raw.title, `${label}.title`), subtitle: text(raw.subtitle, `${label}.subtitle`, false), source_index: sourceIndex };
    const expanded = [];
    if (raw.type === "title" || raw.type === "section") {
      fits(spec.title, 11.6, 1.55, 36, `${label}.title`);
      if (spec.subtitle) fits(spec.subtitle, 11.6, 1.3, 22, `${label}.subtitle`);
      expanded.push(spec);
    } else {
      fits(spec.title, PAGE.widthBody, 1.05, 26, `${label}.title`);
      if (spec.subtitle) fits(spec.subtitle, PAGE.widthBody, 0.32, 13, `${label}.subtitle`);
      if (raw.type === "bullets" || raw.type === "summary") {
        for (const items of bulletPages(raw.bullets, PAGE.widthBody, 20, PAGE.heightBody, `${label}.bullets`)) expanded.push({ ...spec, items });
      } else if (raw.type === "two-column") {
        const columns = ["left", "right"].map(side => {
          fields(raw[side], ["heading", "bullets"], `${label}.${side}`);
          const heading = text(raw[side].heading, `${label}.${side}.heading`);
          fits(heading, 5.65, 0.75, 20, `${label}.${side}.heading`);
          return { heading, pages: bulletPages(raw[side].bullets, 5.65, 18, 3.9, `${label}.${side}.bullets`) };
        });
        for (let part = 0; part < Math.max(...columns.map(column => column.pages.length)); part += 1) expanded.push({ ...spec, columns: columns.map(column => ({ heading: column.heading, items: column.pages[part] ?? [] })) });
      } else if (raw.type === "image") {
        fields(raw.image, ["input_path", "fit"], `${label}.image`);
        const image = { input_path: text(raw.image.input_path, `${label}.image.input_path`), fit: raw.image.fit ?? "contain" };
        if (!["contain", "cover"].includes(image.fit)) throw new Error(`${label}.image.fit 只支持 contain 或 cover`);
        const caption = text(raw.caption, `${label}.caption`, false);
        if (caption) fits(caption, 6, 0.36, 13, `${label}.caption`);
        for (const items of bulletPages(raw.bullets, 5.5, 19, 4.45, `${label}.bullets`)) expanded.push({ ...spec, image, caption, items });
      } else if (raw.type === "metrics") {
        const metrics = list(raw.metrics, `${label}.metrics`, 100).map((metric, index) => {
          const metricLabel = `${label}.metrics[${index}]`;
          fields(metric, ["label", "value", "detail"], metricLabel);
          const result = { label: text(metric.label, `${metricLabel}.label`), value: text(metric.value, `${metricLabel}.value`), detail: text(metric.detail, `${metricLabel}.detail`, false) };
          fits(result.value, 3.3, 0.65, 30, `${metricLabel}.value`);
          fits(result.label, 3.3, 0.7, 17, `${metricLabel}.label`);
          if (result.detail) fits(result.detail, 3.3, 0.33, 13, `${metricLabel}.detail`);
          return result;
        });
        for (let start = 0; start < metrics.length; start += 6) expanded.push({ ...spec, metrics: metrics.slice(start, start + 6) });
      } else if (raw.type === "table") {
        const columns = list(raw.columns, `${label}.columns`, 6).map((value, index) => text(value, `${label}.columns[${index}]`));
        const cellWidth = PAGE.widthBody / columns.length - 0.2;
        const headerHeight = Math.max(...columns.map(value => countLines(value, cellWidth, 18))) * lineHeight(18) + 0.18;
        if (headerHeight > 1.25) throw new Error(`${label}.columns 标题太长；减少列数或缩短标题`);
        const rows = list(raw.rows, `${label}.rows`, 500).map((row, rowIndex) => {
          if (!Array.isArray(row) || row.length !== columns.length) throw new Error(`${label}.rows[${rowIndex}] 必须与 columns 等长`);
          const cells = row.map((value, columnIndex) => tableCell(value, `${label}.rows[${rowIndex}][${columnIndex}]`));
          const height = Math.max(...cells.map(value => countLines(value, cellWidth, 18))) * lineHeight(18) + 0.18;
          if (height > PAGE.heightBody - headerHeight) throw new Error(`${label}.rows[${rowIndex}] 单行内容超出版式；拆分内容或减少列数`);
          return { cells, height, source_row: rowIndex };
        });
        let current = [];
        let used = headerHeight;
        for (const row of rows) {
          if (current.length && used + row.height > PAGE.heightBody) { expanded.push({ ...spec, columns, rows: current, header_height: headerHeight }); current = []; used = headerHeight; }
          current.push(row); used += row.height;
        }
        if (current.length) expanded.push({ ...spec, columns, rows: current, header_height: headerHeight });
      } else if (raw.type === "chart") expanded.push({ ...spec, chart: chartData(raw.chart, `${label}.chart`) });
    }
    expanded.forEach((page, part) => pages.push({ ...page, part: part + 1, parts: expanded.length }));
    if (pages.length > 100) throw new Error("分页后超过 100 页；请按主题拆分演示文稿");
  }
  return { title, theme, pages, output_name, source_slides: data.slides.length, bundle_zip: data.bundle_zip ?? false };
}

export async function readPresentationInput(inputRoot = "/input") {
  const candidates = [];
  const addIfFile = async candidate => {
    try { if ((await stat(candidate)).isFile()) candidates.push(candidate); }
    catch (error) { if (error.code !== "ENOENT") throw error; }
  };
  await addIfFile(join(inputRoot, "data", "presentation.json"));
  let artifacts = [];
  try { artifacts = await readdir(join(inputRoot, "artifacts"), { withFileTypes: true }); }
  catch (error) { if (error.code !== "ENOENT") throw error; }
  for (const artifact of artifacts) if (artifact.isDirectory()) await addIfFile(join(inputRoot, "artifacts", artifact.name, "presentation.json"));
  if (candidates.length !== 1) throw new Error("/input/data 或 /input/artifacts 中需要且只能提供一个 presentation.json 输入文件");
  await checkInputBoundary(inputRoot, candidates[0]);
  return JSON.parse(await readFile(candidates[0], "utf8"));
}

async function checkInputBoundary(inputRoot, candidate) {
  const rootPath = await realpath(inputRoot);
  const filePath = await realpath(candidate);
  const within = relative(rootPath, filePath);
  if (within === ".." || within.startsWith(`..${sep}`) || isAbsolute(within)) throw new Error("输入解析路径超出输入目录");
  return filePath;
}

async function resolveImage(inputRoot, inputPath) {
  if (isAbsolute(inputPath) || inputPath.includes("\\") || inputPath.includes(":") || inputPath.split("/").some(part => !part || part === "." || part === "..") || /[\u0000-\u001f]/u.test(inputPath)) throw new Error(`图片 input_path 必须是安全的相对输入路径：${inputPath}`);
  const parts = inputPath.split("/");
  if (!(parts[0] === "data" && parts.length >= 2) && !(parts[0] === "artifacts" && parts.length >= 3)) throw new Error("图片只能来自显式挂载的 data/<path> 或 artifacts/<artifact_id>/<path>");
  const filePath = await checkInputBoundary(inputRoot, resolve(inputRoot, inputPath));
  const metadata = await stat(filePath);
  if (!metadata.isFile() || metadata.size > 20 * 1024 * 1024) throw new Error("图片必须是 20 MiB 以内的 PNG/JPEG 文件");
  const bytes = await readFile(filePath);
  const png = bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  const jpeg = bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
  if (!png && !jpeg) throw new Error("图片只支持内容有效的 PNG/JPEG，不接受 URL、Base64 或 SVG");
  const dimensions = sizeOf(bytes);
  if (!Number.isFinite(dimensions.width) || !Number.isFinite(dimensions.height) || dimensions.width <= 0 || dimensions.height <= 0) throw new Error("图片尺寸不可解析");
  return { path: filePath, width: dimensions.width, height: dimensions.height };
}

function addText(slide, value, box, theme, options = {}) {
  slide.addText(value, { ...box, fontFace: theme.font_face, color: theme.text, margin: 0, breakLine: false, valign: "top", lineSpacingMultiple: 1.15, ...options });
}

function addHeader(slide, page, theme) {
  addText(slide, page.title, { x: PAGE.x, y: 0.42, w: PAGE.widthBody, h: 1.05 }, theme, { fontSize: 26, bold: true, color: theme.accent_text });
  if (page.subtitle) addText(slide, page.subtitle, { x: PAGE.x, y: 1.46, w: PAGE.widthBody, h: 0.32 }, theme, { fontSize: 13, color: theme.muted });
}

function addBullets(slide, items, box, theme, fontSize) {
  let y = box.y;
  for (const item of items) {
    addText(slide, item.text, { x: box.x, y, w: box.w, h: item.height - 0.1 }, theme, { fontSize, bullet: item.continuation ? undefined : { indent: fontSize * 0.8 }, hanging: fontSize * 0.2 });
    y += item.height;
  }
}

function crc32(bytes) {
  let value = 0xffffffff;
  for (const byte of bytes) {
    value ^= byte;
    for (let index = 0; index < 8; index += 1) value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0);
  }
  return (value ^ 0xffffffff) >>> 0;
}

function singleFileZip(filename, content) {
  const name = Buffer.from(filename, "utf8");
  const data = Buffer.from(content);
  const checksum = crc32(data);
  const local = Buffer.alloc(30 + name.length + data.length);
  local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x0800, 6); local.writeUInt32LE(checksum, 14);
  local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(name.length, 26);
  name.copy(local, 30); data.copy(local, 30 + name.length);
  const central = Buffer.alloc(46 + name.length);
  central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0x0800, 8);
  central.writeUInt32LE(checksum, 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24);
  central.writeUInt16LE(name.length, 28); name.copy(central, 46);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(1, 8); end.writeUInt16LE(1, 10);
  end.writeUInt32LE(central.length, 12); end.writeUInt32LE(local.length, 16);
  return Buffer.concat([local, central, end]);
}

export async function generatePresentation(data, { inputRoot = "/input", outputDir = "/workspace/output" } = {}) {
  const deck = preparePresentation(data);
  // 先验证所有图片，防止生成部分成果后才发现未挂载或非法路径。
  const images = new Map();
  for (const page of deck.pages) if (page.image && !images.has(page.image.input_path)) images.set(page.image.input_path, await resolveImage(inputRoot, page.image.input_path));
  const pptx = new pptxgen();
  pptx.layout = "LAYOUT_WIDE";
  pptx.author = "灵知";
  pptx.subject = deck.title;
  pptx.title = deck.title;
  pptx.lang = "zh-CN";
  pptx.theme = { headFontFace: deck.theme.font_face, bodyFontFace: deck.theme.font_face, lang: "zh-CN" };
  for (const [index, page] of deck.pages.entries()) {
    const slide = pptx.addSlide();
    slide.background = { color: deck.theme.background };
    if (page.type === "title" || page.type === "section") {
      slide.addShape(pptx.ShapeType.rect, { x: PAGE.x, y: 1.85, w: 0.15, h: 2.8, line: { color: deck.theme.accent }, fill: { color: deck.theme.accent } });
      addText(slide, page.title, { x: 1.15, y: 2, w: 11.6, h: 1.55 }, deck.theme, { fontSize: 36, bold: true, color: deck.theme.accent_text });
      if (page.subtitle) addText(slide, page.subtitle, { x: 1.15, y: 3.8, w: 11.6, h: 1.3 }, deck.theme, { fontSize: 22 });
    } else {
      addHeader(slide, page, deck.theme);
      if (page.type === "bullets" || page.type === "summary") addBullets(slide, page.items, { x: PAGE.x, y: PAGE.y, w: PAGE.widthBody }, deck.theme, 20);
      else if (page.type === "two-column") {
        slide.addShape(pptx.ShapeType.line, { x: 6.65, y: PAGE.y, w: 0, h: 4.8, line: { color: deck.theme.border, width: 1 } });
        page.columns.forEach((column, columnIndex) => {
          const x = columnIndex ? 6.98 : PAGE.x;
          addText(slide, column.heading, { x, y: PAGE.y, w: 5.65, h: 0.75 }, deck.theme, { fontSize: 20, bold: true, color: deck.theme.accent_text });
          addBullets(slide, column.items, { x, y: 2.7, w: 5.65 }, deck.theme, 18);
        });
      } else if (page.type === "image") {
        const image = images.get(page.image.input_path);
        const ratio = image.width / image.height;
        const width = Math.min(6, 4.45 * ratio);
        const height = width / ratio;
        // 3.12 的 sizing 使用调用方 w/h 作为原图比例；先读取真实像素，避免按目标框拉伸图片。
        const placement = page.image.fit === "cover"
          ? { x: PAGE.x, y: PAGE.y, w: width, h: height, sizing: { type: "cover", w: 6, h: 4.45 } }
          : { x: PAGE.x + (6 - width) / 2, y: PAGE.y + (4.45 - height) / 2, w: width, h: height };
        slide.addImage({ path: image.path, ...placement, altText: page.caption ?? page.title });
        if (page.caption) addText(slide, page.caption, { x: PAGE.x, y: 6.36, w: 6, h: 0.36 }, deck.theme, { fontSize: 13, color: deck.theme.muted });
        addBullets(slide, page.items, { x: 7.13, y: PAGE.y, w: 5.5 }, deck.theme, 19);
      } else if (page.type === "metrics") {
        page.metrics.forEach((metric, metricIndex) => {
          const x = PAGE.x + (metricIndex % 3) * 4.07;
          const y = PAGE.y + Math.floor(metricIndex / 3) * 2.38;
          slide.addShape(pptx.ShapeType.rect, { x, y, w: 3.78, h: 2.08, line: { color: deck.theme.border, width: 1 }, fill: { color: deck.theme.surface } });
          addText(slide, metric.value, { x: x + 0.2, y: y + 0.18, w: 3.3, h: 0.65 }, deck.theme, { fontSize: 30, bold: true, color: deck.theme.accent_text });
          addText(slide, metric.label, { x: x + 0.2, y: y + 0.92, w: 3.3, h: 0.7 }, deck.theme, { fontSize: 17 });
          if (metric.detail) addText(slide, metric.detail, { x: x + 0.2, y: y + 1.68, w: 3.3, h: 0.33 }, deck.theme, { fontSize: 13, color: deck.theme.muted });
        });
      } else if (page.type === "table") {
        const header = page.columns.map(value => ({ text: value, options: { bold: true, color: deck.theme.on_accent, fill: deck.theme.accent } }));
        slide.addTable([header, ...page.rows.map(row => row.cells)], {
          x: PAGE.x, y: PAGE.y, w: PAGE.widthBody, colW: page.columns.map(() => PAGE.widthBody / page.columns.length), rowH: [page.header_height, ...page.rows.map(row => row.height)],
          autoPage: false, fontFace: deck.theme.font_face, fontSize: 18, color: deck.theme.text, margin: 0.08,
          border: { type: "solid", color: deck.theme.border, pt: 0.5 }, fill: deck.theme.surface, valign: "top",
        });
      } else if (page.type === "chart") {
        // 非负柱形图从零起量，避免渲染器自动截断纵轴而夸大柱高差异。
        const zeroBaseline = page.chart.type === "bar" && page.chart.series.every(series => series.values.every(value => value >= 0));
        slide.addChart(pptx.ChartType[page.chart.type], page.chart.series, {
          x: PAGE.x, y: PAGE.y, w: PAGE.widthBody, h: PAGE.heightBody,
          catAxisLabelFontFace: deck.theme.font_face, catAxisLabelFontSize: 12, valAxisLabelFontFace: deck.theme.font_face, valAxisLabelFontSize: 12,
          legendFontFace: deck.theme.font_face, legendFontSize: 14, legendPos: "b", showLegend: true,
          catAxisLabelColor: deck.theme.text, valAxisLabelColor: deck.theme.text, legendColor: deck.theme.text,
          catAxisLineColor: deck.theme.border, valAxisLineColor: deck.theme.border, chartArea: { fill: { color: deck.theme.background } }, plotArea: { fill: { color: deck.theme.background } },
          showTitle: false, showValue: false, showCatName: false,
          ...(zeroBaseline ? { valAxisMinVal: 0 } : {}),
          chartColors: deck.theme.chart_colors, showBorder: false,
        });
      }
    }
    const continuation = page.parts > 1 ? ` · ${page.part}/${page.parts}` : "";
    addText(slide, `${index + 1} / ${deck.pages.length}${continuation}`, { x: 11.2, y: 7.03, w: 1.4, h: 0.24 }, deck.theme, { fontSize: 11, color: deck.theme.muted, align: "right" });
  }
  await mkdir(outputDir, { recursive: true });
  await pptx.writeFile({ fileName: join(outputDir, deck.output_name) });
  let bundle = null;
  if (deck.bundle_zip) {
    bundle = "presentation.zip";
    await writeFile(join(outputDir, bundle), singleFileZip(deck.output_name, await readFile(join(outputDir, deck.output_name))));
  }
  const report = {
    schema_version: 2, title: deck.title, source_slide_count: deck.source_slides, slide_count: deck.pages.length,
    layout_budget: "passed", visual_review: "required", font_face: deck.theme.font_face,
    theme: { preset: deck.theme.preset, background: deck.theme.background, text: deck.theme.text, accent: deck.theme.accent },
    slides: deck.pages.map((page, index) => ({ number: index + 1, source_index: page.source_index, type: page.type, part: page.part, parts: page.parts, title: page.title,
      ...(page.rows ? { row_count: page.rows.length } : {}), ...(page.metrics ? { metric_count: page.metrics.length } : {}),
      ...(page.chart ? { chart_type: page.chart.type, series_count: page.chart.series.length } : {}),
      ...(page.items ? { text_segment_count: page.items.length } : {}) })),
    output_files: [deck.output_name, ...(bundle ? [bundle] : [])], bundle,
  };
  const publicResults = { results: [{ kind: "text", format: "plain", text: JSON.stringify(report) }] };
  await writeFile(join(outputDir, "result.json"), `${JSON.stringify(publicResults, null, 2)}\n`);
  return report;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const report = await generatePresentation(await readPresentationInput());
  console.log(JSON.stringify(report));
}
