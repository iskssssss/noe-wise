# React 单文件构建

入口为 `scripts/build_react_app.mjs`，读取唯一的 `react-app.json`，输出 `/workspace/output/index.html`。React、ReactDOM 和 Babel 的精确依赖由包清单声明，不需要在每次执行时手抄。构建器使用预备好的依赖，不执行 npm/bash 或启动长期开发服务器。

输入 title 是页面标题，css 为完整内联样式，code 是 TypeScript/JSX 脚本，定义函数 App。React、ReactDOM 是全局对象，使用 React.useState 等 API；不使用 import/export、CDN、外部模块、fetch、WebSocket 或外部字体。需要图表可直接用 SVG 或 canvas，图像使用明确授权的 data URI。支持单文件组件与状态管理，不承诺整个 shadcn/ui 项目或任意 NPM 项目无改动打包。

可选 `theme` 与 HTML 入口使用同一 [主题契约](theme-presets.md)，支持 preset 和安全配色覆盖。CSS 使用 `--sf-*` 变量或主题类，使组件颜色随选定主题一致变化；无 theme 时保持作者 CSS。未知字段和不可读文字配色会拒绝生成。

构建器使用 Babel 转换 TSX，并内联 React UMD 与应用脚本。脚本能编译不证明交互正确：生成后实际打开页面、检查布局和断网交互。复杂跨文件项目超出该入口能力时，依据实际工具选择合适实现，不把任意项目当作本构建器已经支持。
