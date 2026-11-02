import assert from "node:assert/strict";
import { cp, mkdir, mkdtemp, readFile, readdir, rm, symlink, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { deflateSync } from "node:zlib";

// 在私有临时目录挂载精确依赖，检查真实 OOXML；不修改源码目录或仓库 node_modules。
const repository = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const packageRoot = join(repository, "official-skills/presentation");
const argumentIndex = process.argv.indexOf("--node-modules");
const dependencyRoot = argumentIndex >= 0 ? resolve(process.argv[argumentIndex + 1]) : resolve("node_modules");
const dependencies = createRequire(join(dependencyRoot, "../package.json"));
const dependencyMetadata = JSON.parse(await readFile(join(dependencyRoot, "pptxgenjs/package.json"), "utf8"));
assert.equal(dependencyMetadata.version, "3.12.0", "测试必须使用与 Skill 声明相同的 PptxGenJS 3.12.0");
const JSZip = dependencies("jszip");
const root = await mkdtemp(join(tmpdir(), "skillforge-presentation-test-"));
let passed = 0;

function crc32(bytes) {
  let value = 0xffffffff;
  for (const byte of bytes) {
    value ^= byte;
    for (let bit = 0; bit < 8; bit += 1) value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0);
  }
  return (value ^ 0xffffffff) >>> 0;
}

function fixturePng() {
  const chunk = (type, body) => {
    const name = Buffer.from(type);
    const result = Buffer.alloc(body.length + 12);
    result.writeUInt32BE(body.length); name.copy(result, 4); body.copy(result, 8);
    result.writeUInt32BE(crc32(Buffer.concat([name, body])), body.length + 8);
    return result;
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(160); header.writeUInt32BE(90, 4); header[8] = 8; header[9] = 2;
  const pixels = Buffer.alloc(90 * (1 + 160 * 3));
  for (let y = 0; y < 90; y += 1) for (let x = 0; x < 160; x += 1) {
    const offset = y * 481 + 1 + x * 3;
    pixels[offset] = x < 80 ? 23 : 210; pixels[offset + 1] = 107; pixels[offset + 2] = y < 45 ? 135 : 64;
  }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", header), chunk("IDAT", deflateSync(pixels)), chunk("IEND", Buffer.alloc(0))]);
}

function decodeXml(value) {
  return value.replace(/&lt;/gu, "<").replace(/&gt;/gu, ">").replace(/&quot;/gu, '"').replace(/&apos;/gu, "'").replace(/&amp;/gu, "&");
}

function textRuns(xml) { return [...xml.matchAll(/<a:t>([\s\S]*?)<\/a:t>/gu)].map(match => decodeXml(match[1])); }

async function inspect(outputDir, report) {
  const zip = await JSZip.loadAsync(await readFile(join(outputDir, report.output_files[0])), { checkCRC32: true });
  const slidePaths = Object.keys(zip.files).filter(path => /^ppt\/slides\/slide\d+\.xml$/u.test(path)).sort((a, b) => Number(a.match(/\d+/u)[0]) - Number(b.match(/\d+/u)[0]));
  assert.equal(slidePaths.length, report.slide_count);
  assert.ok(zip.file("[Content_Types].xml"));
  const xmls = await Promise.all(slidePaths.map(path => zip.file(path).async("string")));
  for (const xml of xmls) {
    assert.ok(xml.includes("<p:sp>"), "每页含独立可编辑文字形状");
    assert.ok(xml.includes('typeface="Noto Sans CJK SC"'));
    assert.ok(!xml.includes("<a:normAutofit"), "不可通过自动缩字掩盖溢出");
    for (const match of xml.matchAll(/<(?:a|p):xfrm[^>]*>([\s\S]*?)<\/(?:a|p):xfrm>/gu)) {
      const offset = match[1].match(/<a:off x="(-?\d+)" y="(-?\d+)"/u);
      const extent = match[1].match(/<a:ext cx="(\d+)" cy="(\d+)"/u);
      if (!offset || !extent) continue;
      const x = Number(offset[1]); const y = Number(offset[2]); const w = Number(extent[1]); const h = Number(extent[2]);
      assert.ok(x >= 0 && y >= 0 && x + w <= 12192001 && y + h <= 6858001, "真实 OOXML 对象应位于页面边界内");
    }
  }
  assert.equal(report.layout_budget, "passed");
  assert.equal(report.visual_review, "required", "结构测试不能宣布视觉通过");
  const publicResults = JSON.parse(await readFile(join(outputDir, "result.json"), "utf8"));
  assert.deepEqual(Object.keys(publicResults), ["results"]);
  assert.deepEqual(Object.keys(publicResults.results[0]), ["kind", "format", "text"]);
  assert.equal(publicResults.results[0].kind, "text");
  assert.equal(publicResults.results[0].format, "plain");
  assert.deepEqual(JSON.parse(publicResults.results[0].text), report, "回读遵循现有 PublicResults 契约");
  return { zip, xmls };
}

async function check(name, fn) { await fn(); passed += 1; console.log(`PASS ${name}`); }

try {
  await symlink(dependencyRoot, join(root, "node_modules"), process.platform === "win32" ? "junction" : "dir");
  const mountedPackage = join(root, "package");
  await cp(packageRoot, mountedPackage, { recursive: true });
  const entry = join(mountedPackage, "scripts/generate_presentation.mjs");
  const { generatePresentation, preparePresentation, readPresentationInput } = await import(pathToFileURL(entry).href);
  const inputRoot = join(root, "input");
  await mkdir(join(inputRoot, "data"), { recursive: true });
  await mkdir(join(inputRoot, "artifacts", "fixture"), { recursive: true });
  await writeFile(join(inputRoot, "artifacts", "fixture", "photo.png"), fixturePng());
  const example = JSON.parse(await readFile(join(packageRoot, "examples/presentation.json"), "utf8"));

  await check("P01 多版式、可编辑表格/图表/文字和明确图片", async () => {
    const data = structuredClone(example);
    data.slides.splice(4, 0, { type: "image", title: "自建测试图片", image: { input_path: "artifacts/fixture/photo.png", fit: "cover" }, caption: "自建无敏感测试图", bullets: ["图片是当前显式挂载输入", "文字保留为独立可编辑形状"] });
    data.slides.splice(5, 0, { type: "image", title: "完整显示测试图片", image: { input_path: "artifacts/fixture/photo.png", fit: "contain" }, bullets: ["保留图片真实比例"] });
    data.slides.push({ type: "chart", title: "折线样例", chart: { type: "line", series: [{ name: "甲", labels: ["一", "二"], values: [-1.25, 2.5] }] } });
    data.slides.push({ type: "chart", title: "饼图样例", chart: { type: "pie", series: [{ name: "占比", labels: ["甲", "乙"], values: [4, 6] }] } });
    const outputDir = join(root, "all-layouts");
    const report = await generatePresentation(data, { inputRoot, outputDir });
    const { zip, xmls } = await inspect(outputDir, report);
    assert.deepEqual(new Set(report.slides.map(slide => slide.type)), new Set(["title", "section", "bullets", "two-column", "image", "metrics", "table", "chart", "summary"]));
    assert.ok(xmls.some(xml => xml.includes("<a:tbl>")), "原生可编辑表格");
    assert.equal(xmls.filter(xml => xml.includes("<c:chart ")).length, 3, "柱形、折线、饼图为原生图表");
    assert.equal(Object.keys(zip.files).filter(path => /^ppt\/embeddings\/.*\.xlsx$/u.test(path)).length, 3, "每个原生图表有可编辑数据工作簿");
    const chartXmls = await Promise.all(Object.keys(zip.files).filter(path => /^ppt\/charts\/chart\d+\.xml$/u.test(path)).map(path => zip.file(path).async("string")));
    assert.ok(chartXmls.some(xml => xml.includes("<c:v>-1.25</c:v>")), "图表保留真实输入的负数与精度");
    assert.ok(chartXmls.some(xml => xml.includes("<c:barChart>")));
    assert.ok(chartXmls.some(xml => xml.includes("<c:lineChart>")));
    assert.ok(chartXmls.some(xml => xml.includes("<c:pieChart>")));
    const [coverXml, containXml] = xmls.filter(xml => xml.includes("<p:pic>"));
    assert.ok(coverXml.includes("<p:sp>"));
    const crop = coverXml.match(/<a:srcRect l="(\d+)" r="(\d+)" t="(\d+)" b="(\d+)"/u);
    assert.ok(crop && Number(crop[1]) > 0 && Number(crop[2]) > 0, "cover 原生左右裁切，不按目标框拉伸");
    assert.equal(Number(crop[3]), 0); assert.equal(Number(crop[4]), 0);
    const containedPicture = containXml.match(/<p:pic>([\s\S]*?)<\/p:pic>/u)[1];
    const containedExtent = containedPicture.match(/<a:ext cx="(\d+)" cy="(\d+)"/u);
    assert.ok(Math.abs(Number(containedExtent[1]) / Number(containedExtent[2]) - 160 / 90) < 0.00001, "contain 保留真实图片像素比例");
    assert.ok(!containedPicture.includes("<a:srcRect"), "contain 完整显示，不裁切");
    assert.ok(Object.keys(zip.files).some(path => /^ppt\/media\/.+\.png$/u.test(path)));
    assert.deepEqual((await readdir(outputDir)).sort(), ["presentation.pptx", "result.json"]);
  });

  await check("非负柱形图零基线，混合负数与其他图表不强制零下界", async () => {
    const chart = (type, title, series) => ({ type: "chart", title, chart: { type, series } });
    const positive = { name: "部门费用", labels: ["A部门", "B部门"], values: [241, 249] };
    const negative = { name: "调整金额", labels: ["A部门", "B部门"], values: [-1, 2] };
    const data = { schema_version: 2, title: "图表基线验收", slides: [
      chart("bar", "正数柱形图", [positive]),
      chart("bar", "含零柱形图", [{ ...positive, values: [0, 249] }]),
      chart("bar", "第二系列含负数", [positive, negative]),
      chart("line", "正数折线图", [positive]),
      chart("pie", "饼图", [positive]),
    ] };
    const outputDir = join(root, "chart-baselines");
    const report = await generatePresentation(data, { inputRoot, outputDir });
    const { zip } = await inspect(outputDir, report);
    const chartPaths = Object.keys(zip.files).filter(path => /^ppt\/charts\/chart\d+\.xml$/u.test(path)).sort((left, right) => Number(left.match(/chart(\d+)/u)[1]) - Number(right.match(/chart(\d+)/u)[1]));
    assert.equal(chartPaths.length, 5);
    const charts = await Promise.all(chartPaths.map(path => zip.file(path).async("string")));
    for (const xml of charts.slice(0, 2)) assert.match(xml, /<c:valAx>[\s\S]*?<c:scaling>[\s\S]*?<c:min val="0"\/>/u, "实际 OOXML 明确纵轴零下界，不依赖客户端自动范围");
    for (const xml of charts.slice(2)) assert.ok(!xml.includes('<c:min val="0"/>'), "含负数的柱形图与非柱形图保持自身范围");
    assert.ok(charts[2].includes("<c:v>-1</c:v>"), "第二系列负数仍保留");
    for (const value of [241, 249]) assert.ok(charts[0].includes(`<c:v>${value}</c:v>`), "修正基线不修改源数值");
  });

  await check("P02 长中文正文分页且无遗漏、超长标题明确拒绝", async () => {
    const content = `${"中文正文需要保留完整内容与数据精度，不截断事实。".repeat(40)}\n${"另一段包含 & < > 和 emoji 🙂，分页后仍保留完整文字。".repeat(15)}`;
    const data = { schema_version: 2, title: "分页验证", slides: [{ type: "bullets", title: "长段落", bullets: [content] }] };
    const prepared = preparePresentation(data);
    assert.ok(prepared.pages.length > 1);
    assert.equal(prepared.pages.flatMap(page => page.items).map(item => item.text).join(""), content);
    const outputDir = join(root, "long-bullets");
    const report = await generatePresentation(data, { inputRoot, outputDir });
    const { xmls } = await inspect(outputDir, report);
    const actualContent = xmls.flatMap(textRuns).filter(value => value !== "长段落" && !/^\d+ \/ \d+/u.test(value)).join("").replace(/\s/gu, "");
    assert.equal(actualContent, content.replace(/\s/gu, ""), "真实 XML 中完整保留源正文");
    assert.throws(() => preparePresentation({ ...data, slides: [{ ...data.slides[0], title: "超长标题".repeat(100) }] }), /超出版式预算/u);
  });

  await check("P02 双栏、表格和指标按页覆盖全部源项目", async () => {
    const data = { schema_version: 2, title: "分页内容验证", slides: [
      { type: "two-column", title: "两侧不同长度", left: { heading: "左侧", bullets: Array.from({ length: 22 }, (_, index) => `左侧项目${index}`) }, right: { heading: "右侧", bullets: ["右侧唯一项"] } },
      { type: "table", title: "表格", columns: ["编号", "内容"], rows: Array.from({ length: 22 }, (_, index) => [index, `内容${index}`]) },
      { type: "metrics", title: "指标", metrics: Array.from({ length: 13 }, (_, index) => ({ label: `指标${index}`, value: String(index) })) },
    ] };
    const prepared = preparePresentation(data);
    const columns = prepared.pages.filter(page => page.type === "two-column");
    assert.ok(columns.length > 1);
    assert.equal(columns.flatMap(page => page.columns[0].items).length, 22);
    assert.equal(columns.flatMap(page => page.columns[1].items).length, 1);
    assert.deepEqual(prepared.pages.filter(page => page.type === "table").flatMap(page => page.rows).map(row => row.source_row), Array.from({ length: 22 }, (_, index) => index));
    assert.equal(prepared.pages.filter(page => page.type === "metrics").flatMap(page => page.metrics).length, 13);
    const outputDir = join(root, "other-pagination");
    const report = await generatePresentation(data, { inputRoot, outputDir });
    const { xmls } = await inspect(outputDir, report);
    const tableXmls = report.slides.flatMap((slide, index) => slide.type === "table" ? [xmls[index]] : []);
    assert.ok(tableXmls.length > 1);
    for (const xml of tableXmls) assert.deepEqual(textRuns(xml).slice(1, 3), ["编号", "内容"], "续页重复表头");
    for (let index = 0; index < 22; index += 1) assert.equal(tableXmls.flatMap(textRuns).filter(value => value === `内容${index}`).length, 1);
    assert.equal(xmls.flatMap(textRuns).filter(value => value === "右侧唯一项").length, 1);
  });

  await check("固定长内容示例实际生成", async () => {
    const data = JSON.parse(await readFile(join(packageRoot, "examples/presentation-long.json"), "utf8"));
    const outputDir = join(root, "long-example");
    const report = await generatePresentation(data, { inputRoot, outputDir });
    assert.ok(report.slide_count > data.slides.length);
    await inspect(outputDir, report);
  });

  await check("ZIP 仅明确要求生成且内容对应 PPTX", async () => {
    const outputDir = join(root, "explicit-zip");
    const report = await generatePresentation({ ...example, bundle_zip: true }, { inputRoot, outputDir });
    assert.equal(report.bundle, "presentation.zip");
    const zip = await JSZip.loadAsync(await readFile(join(outputDir, "presentation.zip")), { checkCRC32: true });
    assert.deepEqual(Object.keys(zip.files), ["presentation.pptx"]);
    assert.deepEqual(await zip.file("presentation.pptx").async("nodebuffer"), await readFile(join(outputDir, "presentation.pptx")));
    assert.throws(() => preparePresentation({ ...example, bundle_zip: "true" }), /布尔值/u);
  });

  await check("指定文件名贯穿实际 PPTX、结果与 ZIP，保留三页", async () => {
    const data = JSON.parse(await readFile(join(packageRoot, "examples/presentation-named.json"), "utf8"));
    for (const name of [data.output_name, "项目进展.pptx", "报告.PPTX"]) {
      const outputDir = join(root, `named-${name}`);
      const report = await generatePresentation({ ...data, output_name: name, bundle_zip: true }, { inputRoot, outputDir });
      assert.equal(report.slide_count, 3);
      assert.deepEqual(report.output_files, [name, "presentation.zip"]);
      await inspect(outputDir, report);
      assert.deepEqual((await readdir(outputDir)).sort(), [name, "presentation.zip", "result.json"].sort());
      const zip = await JSZip.loadAsync(await readFile(join(outputDir, "presentation.zip")), { checkCRC32: true });
      assert.deepEqual(Object.keys(zip.files), [name]);
      assert.deepEqual(await zip.file(name).async("nodebuffer"), await readFile(join(outputDir, name)));
    }
  });

  await check("非法输出文件名在写入前拒绝，长度按字符与 UTF-8 字节检查", async () => {
    const invalidNames = [null, 3, {}, "", " ", " report.pptx", "report.pptx ", "../report.pptx", "dir/report.pptx", "dir\\report.pptx", "C:report.pptx", "a?.pptx", "a*.pptx", 'a".pptx', "a<.pptx", "a>.pptx", "a|.pptx", "a\u0000.pptx", "a\t.pptx", "a\n.pptx", "a\u007f.pptx", "a\u0085.pptx", "a\u009f.pptx", "ｒeport.pptx", "e\u0301.pptx", "CON.pptx", "prn.pptx", "AUX .pptx", "nul..pptx", "COM1.pptx", "lpt9.pptx", "a".repeat(196) + ".pptx", "中".repeat(84) + ".pptx", "report.pdf", "report.pptx."];
    for (const output_name of invalidNames) {
      const outputDir = join(root, "invalid-output-name");
      await assert.rejects(generatePresentation({ ...example, output_name }, { inputRoot, outputDir }), /output_name/u);
      await assert.rejects(readdir(outputDir), /ENOENT/u);
    }
    for (const output_name of ["a".repeat(195) + ".pptx", "中".repeat(83) + ".pptx", "🙂".repeat(62) + ".pptx", "COM10.pptx"]) {
      assert.equal(preparePresentation({ ...example, output_name }).output_name, output_name);
    }
  });

  await check("精确 JSON 输入：缺失、多个输入拒绝且不回退示例", async () => {
    await assert.rejects(readPresentationInput(inputRoot), /需要且只能提供一个/u);
    await writeFile(join(inputRoot, "data", "presentation.json"), JSON.stringify(example));
    assert.deepEqual(await readPresentationInput(inputRoot), example);
    await writeFile(join(inputRoot, "artifacts", "fixture", "presentation.json"), JSON.stringify(example));
    await assert.rejects(readPresentationInput(inputRoot), /需要且只能提供一个/u);
    await rm(join(inputRoot, "data", "presentation.json"));
    assert.deepEqual(await readPresentationInput(inputRoot), example);
    await rm(join(inputRoot, "artifacts", "fixture", "presentation.json"));
  });

  await check("显式图片边界：遍历、网络、Base64、缺失、假图片拒绝", async () => {
    const imageData = inputPath => ({ schema_version: 2, title: "图片输入", slides: [{ type: "image", title: "图片页", image: { input_path: inputPath }, bullets: ["说明"] }] });
    for (const inputPath of ["../private.png", "data/../../private.png", "https://example.com/x.png", "data:image/png;base64,abc", "/input/data/photo.png", "data\\photo.png", "examples/photo.png", "data/missing.png"]) {
      const outputDir = join(root, "rejected-images");
      await assert.rejects(generatePresentation(imageData(inputPath), { inputRoot, outputDir }));
      await assert.rejects(readdir(outputDir), /ENOENT/u, "失败不生成部分 PPTX");
    }
    await writeFile(join(inputRoot, "data", "fake.png"), "this is not a png");
    await assert.rejects(generatePresentation(imageData("data/fake.png"), { inputRoot, outputDir: join(root, "fake-image") }), /PNG\/JPEG/u);
    const outsideDir = join(root, "outside");
    await mkdir(outsideDir); await writeFile(join(outsideDir, "private.png"), fixturePng());
    await symlink(outsideDir, join(inputRoot, "data", "escape"), process.platform === "win32" ? "junction" : "dir");
    await assert.rejects(generatePresentation(imageData("data/escape/private.png"), { inputRoot, outputDir: join(root, "escaped-image") }), /超出输入目录/u);
    await writeFile(join(outsideDir, "presentation.json"), JSON.stringify(example));
    const linkedInputRoot = join(root, "linked-input");
    await mkdir(linkedInputRoot);
    await symlink(outsideDir, join(linkedInputRoot, "data"), process.platform === "win32" ? "junction" : "dir");
    await assert.rejects(readPresentationInput(linkedInputRoot), /超出输入目录/u);
  });

  await check("旧 schema、未知内容字段、无效数据与无法容纳表格明确拒绝", async () => {
    const invalid = [
      [{ title: "旧输入", slides: [{ title: "页面", bullets: ["正文"] }] }, /schema_version/u],
      [{ ...example, irrelevant_user_data: "不应静默丢弃" }, /不支持的字段/u],
      [{ ...example, slides: [{ type: "table", title: "表格", columns: ["列"], rows: [[1, 2]] }] }, /等长/u],
      [{ ...example, slides: [{ type: "table", title: "表格", columns: ["列甲", "列乙", "列丙", "列丁", "列戊", "列己"], rows: [["超长单元格".repeat(100), "", "", "", "", ""]] }] }, /单行内容超出版式/u],
      [{ ...example, slides: [{ type: "chart", title: "图表", chart: { type: "line", series: [{ name: "甲", labels: ["一"], values: ["3"] }] } }] }, /有限数字/u],
      [{ ...example, slides: [{ type: "chart", title: "图表", chart: { type: "pie", series: [{ name: "甲", labels: ["一"], values: [-2] }] } }] }, /非负/u],
      [{ ...example, slides: [{ type: "chart", title: "图表", chart: { type: "bar", series: [{ name: "甲", labels: ["一"], values: [1] }, { name: "乙", labels: ["二"], values: [2] }] } }] }, /相同 labels/u],
    ];
    for (const [data, pattern] of invalid) assert.throws(() => preparePresentation(data), pattern);
  });
  console.log(JSON.stringify({ passed, pptxgenjs: dependencyMetadata.version, visual_review: "not_run" }));
} finally {
  // 删除仅由本测试原子创建的私有临时根；junction 自身移除，不触碰外部依赖目录。
  assert.equal(dirname(resolve(root)), resolve(tmpdir()));
  assert.ok(basename(root).startsWith("skillforge-presentation-test-"));
  await rm(root, { recursive: true, force: true });
}
