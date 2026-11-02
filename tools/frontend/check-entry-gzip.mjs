#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { gzipSync } from "node:zlib";
import { dirname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const workspace = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const requestedProjects = process.argv.slice(2);
const projects = requestedProjects.length ? requestedProjects : ["frontend-admin", "frontend-platform"];
const limitBytes = 75 * 1024;
let failed = false;

function manifestFilePath(distRoot, file) {
  const resolved = resolve(distRoot, file);
  const pathWithinDist = relative(distRoot, resolved);
  if (pathWithinDist.startsWith("..") || pathWithinDist.includes("../")) {
    throw new Error(`manifest 包含 dist 目录外的资源：${file}`);
  }
  return resolved;
}

function collectInitialResources(manifest, entryKey) {
  const resources = new Set();
  const visitedEntries = new Set();

  function visit(key) {
    if (visitedEntries.has(key)) return;
    visitedEntries.add(key);
    const item = manifest[key];
    if (!item) throw new Error(`构建 manifest 引用了不存在的模块：${key}`);
    if (item.file) resources.add(item.file);
    for (const css of item.css ?? []) resources.add(css);
    for (const importedKey of item.imports ?? []) visit(importedKey);
  }

  visit(entryKey);
  return [...resources].sort();
}

async function gzipBytes(path) {
  return gzipSync(await readFile(path), { level: 9 }).byteLength;
}

for (const project of projects) {
  const projectRoot = resolve(requestedProjects.length ? process.cwd() : workspace, project);
  const projectName = relative(workspace, projectRoot).replaceAll("\\", "/") || ".";
  const distRoot = resolve(projectRoot, "dist");
  const manifestPath = resolve(distRoot, ".vite/manifest.json");

  try {
    let manifest;
    try {
      manifest = JSON.parse(await readFile(manifestPath, "utf8"));
    } catch (error) {
      if (error?.code === "ENOENT") {
        throw new Error(`未找到 ${relative(projectRoot, manifestPath).replaceAll("\\", "/")}，请先执行生产构建`);
      }
      if (error instanceof SyntaxError) {
        throw new Error(`构建 manifest 不是有效 JSON：${error.message}`);
      }
      throw error;
    }

    const entries = Object.entries(manifest);
    const [entryKey, entry] = entries.find(([key]) => key === "index.html")
      ?? entries.find(([, item]) => item?.isEntry && item?.src === "index.html")
      ?? entries.find(([, item]) => item?.isEntry && item?.src === "src/main.ts")
      ?? entries.find(([, item]) => item?.isEntry)
      ?? [];
    if (!entryKey || !entry?.file) throw new Error("构建 manifest 中未找到入口文件");

    const entryPath = manifestFilePath(distRoot, entry.file);
    const entryBytes = await gzipBytes(entryPath);
    console.log(`${projectName}: ${relative(projectRoot, entryPath).replaceAll("\\", "/")} = ${(entryBytes / 1024).toFixed(2)} KiB gzip`);

    const resources = collectInitialResources(manifest, entryKey);
    const resourceSizes = await Promise.all(resources.map(async (file) => ({
      file,
      bytes: await gzipBytes(manifestFilePath(distRoot, file)),
    })));
    const totalBytes = resourceSizes.reduce((total, resource) => total + resource.bytes, 0);
    console.log(`${projectName}: 首屏静态资源 ${resourceSizes.length} 个，共 ${(totalBytes / 1024).toFixed(2)} KiB gzip`);
    for (const resource of resourceSizes) {
      console.log(`  - ${resource.file}: ${(resource.bytes / 1024).toFixed(2)} KiB gzip`);
    }

    // main 动态加载 bootstrap，路由又动态加载页面；只计算入口静态依赖会漏掉真实首屏主体。
    const findSource = (suffix) => entries.find(([key, item]) => (item.src ?? key).replaceAll("\\", "/").endsWith(suffix))?.[0];
    const bootstrapKey = findSource("src/bootstrap.ts");
    const consoleName = projectName.endsWith("frontend-platform") ? "platform" : "admin";
    for (const [label, sources] of [
      ["登录页", ["src/views/LoginPage.vue"]],
      ["首个业务页", [`src/views/${consoleName}/Layout.vue`, `src/views/${consoleName}/HomePage.vue`]],
    ]) {
      const keys = sources.map(findSource);
      if (!bootstrapKey || keys.some(key => !key)) continue;
      const pageResources = new Set([entryKey, bootstrapKey, ...keys].flatMap(key => collectInitialResources(manifest, key)));
      const pageSizes = await Promise.all([...pageResources].map(file => gzipBytes(manifestFilePath(distRoot, file))));
      console.log(`${projectName}: ${label}构建依赖合计 ${pageResources.size} 个，${(pageSizes.reduce((sum, size) => sum + size, 0) / 1024).toFixed(2)} KiB gzip（冷加载估算，不含运行时请求及按需字体）`);
    }

    if (entryBytes <= limitBytes) continue;
    failed = true;
    console.error(`${projectName}: 入口超过 75 KiB gzip 上限`);
  } catch (error) {
    failed = true;
    console.error(`[FAIL] ${projectName}: ${error instanceof Error ? error.message : String(error)}`);
  }
}

if (failed) process.exitCode = 1;
