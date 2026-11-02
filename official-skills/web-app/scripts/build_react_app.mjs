/** 以受管依赖编译单组件 TSX，并内联 React 运行时；不执行安装或网络命令。 */
import { readFile, readdir, stat, mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import Babel from '@babel/standalone';
import { resolveTheme, themeCss } from './theme.mjs';

const require = createRequire(import.meta.url);
const INPUT_DATA = '/input/data';
const INPUT_ARTIFACTS = '/input/artifacts';
const OUTPUT = '/workspace/output';

export async function readReactInput(inputData = INPUT_DATA, inputArtifacts = INPUT_ARTIFACTS) {
  const matches = [];
  const add = async path => { try { if ((await stat(path)).isFile()) matches.push(path); } catch (e) { if (e.code !== 'ENOENT') throw e; } };
  await add(`${inputData}/react-app.json`);
  try {
    for (const entry of await readdir(inputArtifacts, { withFileTypes: true }))
      if (entry.isDirectory()) await add(`${inputArtifacts}/${entry.name}/react-app.json`);
  } catch (e) { if (e.code !== 'ENOENT') throw e; }
  if (matches.length !== 1) throw new Error('显式输入中需要且只能提供一个 react-app.json');
  return JSON.parse(await readFile(matches[0], 'utf8'));
}

export async function buildReactApp(data, outputDir = OUTPUT) {
if (!data || typeof data !== 'object' || Array.isArray(data) || Object.keys(data).some(key => !['title','code','css','theme'].includes(key)))
  throw new Error('React 输入仅支持 title/code/css 与可选 theme');
for (const key of ['title','code','css']) if (typeof data[key] !== 'string' || !data[key].trim()) throw new Error(`${key} 必须是非空字符串`);
if (Buffer.byteLength(data.code) > 2 * 1024 * 1024) throw new Error('源码超过 2 MiB');
if (/@import\b|url\(\s*['"]?(?!data:|#)/i.test(data.css) || /<\/style/i.test(data.css)) throw new Error('CSS 必须完整内联');
// script 模式拒绝模块 import/export；使用 React 全局对象定义 App，不访问网络。
const compiled = Babel.transform(data.code, { filename:'app.tsx', sourceType:'script', presets:[['typescript',{isTSX:true,allExtensions:true}],['react',{runtime:'classic'}]], comments:false }).code;
const runtime = await readFile(join(dirname(require.resolve('react')), 'umd/react.production.min.js'), 'utf8');
const dom = await readFile(join(dirname(require.resolve('react-dom')), 'umd/react-dom.production.min.js'), 'utf8');
const escapeScript = value => value.replace(/<\/script/gi, '<\\/script');
const escapeHtml = value => value.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const theme = data.theme === undefined ? null : resolveTheme(data.theme);
const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(data.title)}</title><style>${data.css}</style>${theme ? `<style data-skillforge-theme="1">${themeCss(theme)}</style>` : ''}</head><body><div id="root"></div><script>${escapeScript(runtime)}</script><script>${escapeScript(dom)}</script><script>${escapeScript(compiled)}\nReactDOM.createRoot(document.getElementById('root')).render(React.createElement(App));</script></body></html>`;
await mkdir(outputDir, {recursive:true});
await writeFile(`${outputDir}/index.html`, html, 'utf8');
return {title:data.title,bytes:Buffer.byteLength(html),runtime:'react-18',browser_check:'required', ...(theme ? {theme:{preset:theme.preset,background:theme.background,text:theme.text,accent:theme.accent}} : {})};
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  console.log(JSON.stringify(await buildReactApp(await readReactInput())));
}
