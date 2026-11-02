import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import { componentDeclarations } from './component-declarations.ts';

const resolvers = () => [
  { type: 'component', async resolve(name) { return { from: 'element-plus/es', name }; } },
  { type: 'directive', async resolve(name) { return { from: 'element-plus/es', name: `El${name}Directive` }; } },
];

async function fixture(action) {
  const root = await mkdtemp(join(tmpdir(), 'skillforge-components-'));
  try { await action(root, join(root, 'src/components.d.ts')); }
  finally { await rm(root, { recursive: true, force: true }); }
}

test('生产构建完整发布组件与指令类型，旧声明不会混入新构建', async () => fixture(async (root, path) => {
  await mkdir(join(root, 'src'));
  await writeFile(path, "declare module 'vue' { export interface GlobalComponents {\n    OldComponent: typeof import('old')['Old']\n  } }\n");
  const { plugin, resolvers: tracked } = componentDeclarations(resolvers());
  await plugin.configResolved({ root, command: 'build' });
  await Promise.all(Array.from({ length: 128 }, (_, index) => tracked[0].resolve(`ElExample${index}`)));
  await tracked[1].resolve('Loading');
  await plugin.buildEnd();
  const code = await readFile(path, 'utf8');
  for (let index = 0; index < 128; index++) assert.ok(code.includes(`ElExample${index}: typeof import("element-plus/es")["ElExample${index}"]`));
  assert.ok(code.includes('vLoading: typeof import("element-plus/es")["ElLoadingDirective"]'));
  assert.ok(code.includes("RouterLink: typeof import('vue-router')['RouterLink']"));
  assert.ok(code.includes("RouterView: typeof import('vue-router')['RouterView']"));
  assert.ok(!code.includes('OldComponent'));
  assert.deepEqual(await readdir(join(root, 'src')), ['components.d.ts']);
}));

test('开发态并行 resolver 读取串行发布，保留未访问页面的声明及全部新增类型', async () => fixture(async (root, path) => {
  await mkdir(join(root, 'src'));
  await writeFile(path, "declare module 'vue' { export interface GlobalComponents {\n    Existing: typeof import('existing')['Existing']\n  } }\n");
  const { plugin, resolvers: tracked } = componentDeclarations(resolvers());
  await plugin.configResolved({ root, command: 'serve' });
  await Promise.all(Array.from({ length: 64 }, (_, index) => tracked[0].resolve(`ElConcurrent${index}`)));
  const code = await readFile(path, 'utf8');
  assert.ok(code.includes("Existing: typeof import('existing')['Existing']"));
  for (let index = 0; index < 64; index++) assert.ok(code.includes(`ElConcurrent${index}:`));
  assert.deepEqual(await readdir(join(root, 'src')), ['components.d.ts']);
}));

test('发布失败返回构建生命周期，失败构建不发布部分类型', async () => fixture(async (root, path) => {
  await mkdir(path, { recursive: true });
  const { plugin } = componentDeclarations(resolvers());
  await plugin.configResolved({ root, command: 'build' });
  await assert.rejects(plugin.buildEnd());
  assert.deepEqual(await readdir(join(root, 'src')), ['components.d.ts']);
  const failed = componentDeclarations(resolvers());
  await failed.plugin.configResolved({ root, command: 'build' });
  await failed.plugin.buildEnd(new Error('transform failed'));
  assert.deepEqual(await readdir(join(root, 'src')), ['components.d.ts']);
}));

test('保持真实 ElementPlus 的组件与指令导入身份以及 Vue 模块增强', async () => fixture(async (root, path) => {
  const { plugin, resolvers: tracked } = componentDeclarations(ElementPlusResolver({ version: '2.13.7' }));
  await plugin.configResolved({ root, command: 'build' });
  const button = await tracked[0].resolve('ElButton');
  const loading = await tracked[1].resolve('Loading');
  assert.equal(button.name, 'ElButton');
  assert.equal(button.from, 'element-plus/es');
  assert.equal(loading.name, 'ElLoadingDirective');
  assert.equal(loading.from, 'element-plus/es');
  await plugin.buildEnd();
  const code = await readFile(path, 'utf8');
  assert.ok(code.includes('export {}\n'));
  assert.ok(code.includes("declare module 'vue' {"));
  assert.ok(code.includes('export interface GlobalComponents {'));
  assert.ok(code.includes('ElButton: typeof import("element-plus/es")["ElButton"]'));
  assert.ok(code.includes('export interface GlobalDirectives {'));
  assert.ok(code.includes('vLoading: typeof import("element-plus/es")["ElLoadingDirective"]'));
  assert.deepEqual(await readdir(join(root, 'src')), ['components.d.ts']);
}));

test('保留 resolver alias、named/default export、相对导入及返回对象', async () => fixture(async (root, path) => {
  const named = { from: 'library', name: 'Exported', as: 'PublicAlias', sideEffects: 'library/style.css' };
  const directive = { from: 'library', name: 'DirectiveExport', as: 'vPublicAlias' };
  const { plugin, resolvers: tracked } = componentDeclarations([
    (name) => name === 'Default' ? 'default-library' : named,
    { type: 'component', resolve: () => ({ from: join(root, 'src/Local.vue') }) },
    { type: 'directive', resolve: () => directive },
  ]);
  await plugin.configResolved({ root, command: 'build' });
  assert.equal(await tracked[0].resolve('Named'), named);
  assert.deepEqual(await tracked[1].resolve('Local'), { from: join(root, 'src/Local.vue') });
  await tracked[0].resolve('Default');
  assert.equal(await tracked[2].resolve('Loading'), directive);
  await plugin.buildEnd();
  const code = await readFile(path, 'utf8');
  assert.ok(code.includes('PublicAlias: typeof import("library")["Exported"]'));
  assert.ok(code.includes('Default: typeof import("default-library")["default"]'));
  assert.ok(code.includes('Local: typeof import("./Local.vue")["default"]'));
  assert.ok(code.includes('vPublicAlias: typeof import("library")["DirectiveExport"]'));
}));
