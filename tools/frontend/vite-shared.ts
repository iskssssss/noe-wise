import vue from '@vitejs/plugin-vue';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import ElementPlus from 'unplugin-element-plus/vite';
import { componentDeclarations } from './component-declarations';
import { frontendDependencyOptimization } from './dependency-optimization';

/** 后台构建只处理 Vue 与 Element Plus；界面样式由后台公共资源维护。 */
export function frontendPlugins() {
  const declarations = componentDeclarations(ElementPlusResolver());
  return [frontendDependencyOptimization(), vue(),
    // 声明由相同 resolver 驱动的受管生成器完整发布，替代上游不等待完成的节流写入。
    Components({ resolvers: declarations.resolvers, dts: false, dirs: [] }),
    declarations.plugin,
    ElementPlus({ useSource: false, format: 'esm' }),
  ];
}
export const runtimeDedupe = ['vue', 'vue-router', 'pinia', 'vue-i18n', 'element-plus', 'lucide-vue-next'];
