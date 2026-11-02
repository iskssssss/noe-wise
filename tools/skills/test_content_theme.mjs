/** 检查完整受管包生成的 PPTX 与 React，冻结依赖由调用者显式提供。 */
import assert from 'node:assert/strict';
import { cp, mkdir, mkdtemp, readFile, rm, symlink } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const args = process.argv.slice(2);
const value = (name, fallback) => args.includes(name) ? args[args.indexOf(name) + 1] : fallback;
const dependencyRoot = resolve(value('--node-modules', 'node_modules'));
const evidence = value('--output', null);
const root = await mkdtemp(join(tmpdir(), 'skillforge-content-theme-'));
const require = createRequire(join(dependencyRoot, '../package.json'));
const JSZip = require('jszip');
let passed = 0;
const check = async (name, fn) => { await fn(); passed += 1; console.log(`PASS ${name}`); };

try {
  await symlink(dependencyRoot, join(root, 'node_modules'), process.platform === 'win32' ? 'junction' : 'dir');
  const official = join(repository, 'official-skills');
  for (const name of ['presentation', 'web-app']) await cp(join(official, name), join(root, name), { recursive: true });
  const entry = await import(pathToFileURL(join(root, 'presentation/scripts/generate_presentation.mjs')).href);
  const themeModule = await import(pathToFileURL(join(root, 'presentation/scripts/theme.mjs')).href);
  const webTheme = await import(pathToFileURL(join(root, 'web-app/scripts/theme.mjs')).href);
  const react = await import(pathToFileURL(join(root, 'web-app/scripts/build_react_app.mjs')).href);
  const presets = JSON.parse(await readFile(join(root, 'presentation/resources/themes.json'), 'utf8')).presets;
  const input = { schema_version: 2, title: '统一主题验收（虚构数据）', slides: [
    { type: 'title', title: '统一主题验收', subtitle: '中文可编辑文字；虚构样例' },
    { type: 'metrics', title: '指标样例', metrics: [{ label: '示例计数', value: '12', detail: '虚构数据，非业务事实' }] },
    { type: 'table', title: '状态样例', columns: ['事项', '状态'], rows: [['负责人', '待确认'], ['期限', '待确认']] },
    { type: 'chart', title: '图表样例', chart: { type: 'bar', series: [
      { name: '虚构样例一', labels: ['甲', '乙'], values: [2, 4] },
      { name: '虚构样例二', labels: ['甲', '乙'], values: [3, 1] },
      { name: '虚构样例三', labels: ['甲', '乙'], values: [1, 3] },
      { name: '虚构样例四', labels: ['甲', '乙'], values: [4, 2] },
    ] } },
  ] };

  await check('10 套主题进入实际 PPTX，深色表格与图表保留可读文字', async () => {
    for (const preset of Object.keys(presets)) {
      const theme = themeModule.resolveTheme({ preset });
      assert.deepEqual(theme, webTheme.resolveTheme({ preset }), '两个受管包采用相同角色');
      assert.ok(themeModule.contrast(theme.text, theme.background) >= 4.5);
      assert.equal(new Set(theme.chart_colors).size, 4, '图表多系列颜色不能退化成同一文字色');
      for (const color of theme.chart_colors) assert.ok(themeModule.contrast(color, theme.background) >= 3, '图表色应与背景可区分');
      const outputDir = join(root, 'output', preset);
      const report = await entry.generatePresentation({ ...input, theme: { preset } }, { outputDir });
      assert.equal(report.theme.preset, preset);
      assert.equal(report.slide_count, 4);
      const zip = await JSZip.loadAsync(await readFile(join(outputDir, 'presentation.pptx')), { checkCRC32: true });
      const xmls = await Promise.all([1, 2, 3, 4].map(index => zip.file(`ppt/slides/slide${index}.xml`).async('string')));
      for (const xml of xmls) {
        assert.ok(xml.includes(`val="${theme.background}"`));
        assert.ok(xml.includes('typeface="Noto Sans CJK SC"'));
      }
      assert.ok(xmls[1].includes(`val="${theme.surface}"`), '指标卡表面使用主题色');
      assert.ok(xmls[2].includes(`val="${theme.surface}"`), '表格正文使用主题色');
      assert.ok(xmls[2].includes(`val="${theme.on_accent}"`), '表头使用实际可读文字色');
      const chartPath = Object.keys(zip.files).find(path => /^ppt\/charts\/chart\d+\.xml$/u.test(path));
      const chart = await zip.file(chartPath).async('string');
      assert.ok(chart.includes(`val="${theme.text}"`), '图表轴和图例文字使用主题色');
      for (const color of theme.chart_colors) assert.ok(chart.includes(`val="${color}"`), '4 个实际图表系列应使用不同配色');
      assert.ok(chart.includes('<c:v>2</c:v>') && chart.includes('<c:v>4</c:v>'), '原生图表保持源数据');
      assert.equal(report.visual_review, 'required');
      if (evidence) await cp(outputDir, join(resolve(evidence), 'pptx', preset), { recursive: true });
    }
  });

  await check('主题覆盖与非法/低对比色失败，不产生候选', async () => {
    const theme = themeModule.resolveTheme({ preset: 'ocean-depths', accent: 'f9a620' });
    assert.equal(theme.accent, 'F9A620');
    for (const bad of [null, [], { preset: 'unknown' }, { text: 'FFFFFF', background: 'FFFFFF' }, { font_face: '</style>' }, { accent: '#abc' }]) {
      assert.throws(() => entry.preparePresentation({ ...input, theme: bad }));
    }
  });

  await check('React 构建真实离线单文件并采用同主题角色', async () => {
    const data = { title: 'React 主题验收', theme: { preset: 'midnight-galaxy' }, css: 'main{padding:2rem}button{padding:1rem}', code: 'function App(){const [count,setCount]=React.useState(0);return <main className="sf-surface"><h1>React 主题验收</h1><button className="sf-accent" onClick={()=>setCount(count+1)}>计数 {count}</button></main>}' };
    const output = join(root, 'react');
    const report = await react.buildReactApp(data, output);
    const html = await readFile(join(output, 'index.html'), 'utf8');
    assert.equal(report.theme.preset, 'midnight-galaxy');
    assert.ok(html.includes('--sf-background:#2B1E3E'));
    assert.ok(html.includes('ReactDOM.createRoot'));
    assert.ok(!html.includes('<script src='));
    await assert.rejects(() => react.buildReactApp({ ...data, theme: { preset: 'unknown' } }, join(root, 'invalid-react')));
    if (evidence) await cp(output, join(resolve(evidence), 'react'), { recursive: true });
  });
  await check('企业周报内容示例由现有页型真实生成，保留事实空缺', async () => {
    const data = JSON.parse(await readFile(join(root, 'presentation/examples/presentation-weekly-update.json'), 'utf8'));
    const outputDir = join(root, 'weekly-update');
    const report = await entry.generatePresentation(data, { outputDir });
    assert.equal(report.slide_count, 4);
    const zip = await JSZip.loadAsync(await readFile(join(outputDir, 'presentation.pptx')));
    const text = (await Promise.all([1, 2, 3, 4].map(index => zip.file(`ppt/slides/slide${index}.xml`).async('string')))).join('');
    assert.ok(text.includes('待确认'));
    assert.ok(text.includes('下一步计划与当前阻塞'));
    assert.ok(text.includes('已完成事项与证据'));
    if (evidence) await cp(outputDir, join(resolve(evidence), 'weekly-update'), { recursive: true });
  });
  console.log(JSON.stringify({ passed, themes: Object.keys(presets).length, local_generation: 'passed', visual_review: 'required' }));
} finally {
  await rm(root, { recursive: true, force: true });
}
