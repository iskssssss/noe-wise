import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { optimizeDeps, resolveConfig } from 'vite';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

for (const app of ['frontend-admin', 'frontend-platform']) {
  test(`${app} 冷缓存已包含首次菜单和抽屉所需的自动导入依赖`, async () => {
    const cacheDir = await mkdtemp(resolve(tmpdir(), 'skillforge-deps-'));
    try {
      const root = resolve(repo, app);
      const config = await resolveConfig({
        root, configFile: resolve(root, 'vite.config.ts'), cacheDir, logLevel: 'silent',
        // 不借助页面访问或扫描结果填充缓存，验证显式预编译覆盖动态入口。
        optimizeDeps: { entries: [] },
      }, 'serve');
      const metadata = await optimizeDeps(config, true);
      const styles = config.optimizeDeps.include.filter(id => id.startsWith('element-plus/es/components/') && id.endsWith('/style/css'));
      assert.ok(styles.length > 50, '样式 JS 没有类型声明，不能只依赖 exports.types glob');
      for (const id of [...styles, 'element-plus/es/components/input/index', 'element-plus/es/components/select/index']) {
        assert.ok(metadata.optimized[id], `未提前优化 ${id}`);
        // 部分无样式组件的入口会被优化为空模块；文件可读即是有效输出。
        await readFile(metadata.optimized[id].file, 'utf8');
      }
      assert.notEqual(config.server.hmr, false, '保留正常开发热更新');
      assert.notEqual(config.optimizeDeps.noDiscovery, true, '保留新依赖发现');
    } finally {
      // cacheDir 是 mkdtemp 在系统临时目录返回的唯一测试目录。
      assert.ok(dirname(cacheDir) === resolve(tmpdir()) && cacheDir.includes('skillforge-deps-'));
      await rm(cacheDir, { recursive: true, force: true });
    }
  });
}
