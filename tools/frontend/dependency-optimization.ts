import type { Plugin } from 'vite';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';

function elementPlusEntries(root: string): string[] {
  const require = createRequire(join(root, 'package.json'));
  const components = join(dirname(require.resolve('element-plus/package.json')), 'es/components');
  // Vite 的深层 glob 优先按 exports.types 展开；样式入口没有 .d.ts，必须读取实际运行文件。
  return readdirSync(components, { withFileTypes: true }).filter(entry => entry.isDirectory()).flatMap(entry =>
    ['index', 'style/css'].filter(path => existsSync(join(components, entry.name, `${path}.mjs`)))
      .map(path => `element-plus/es/components/${entry.name}/${path}`));
}

/** 自动导入的样式和异步组件在源码扫描阶段不可见，提前优化以免首次交互触发整页刷新。 */
export function frontendDependencyOptimization(): Plugin {
  return {
    name: 'skillforge:dependency-optimization',
    apply: 'serve',
    config: config => {
      const root = resolve(config.root ?? process.cwd());
      const manifest = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
      const highlight = manifest.dependencies?.['highlight.js'] ? ['highlight.js/lib/core', 'highlight.js/lib/languages/*'] : [];
      return {
      optimizeDeps: {
        include: [
          'element-plus/es',
          ...elementPlusEntries(root),
          ...highlight,
        ],
      },
      };
    },
  };
}
