---
name: web-app
description: 根据用户内容生成可离线使用的网页、报告、仪表盘和交互工具，支持 HTML 或 React 单文件产物及已有网页修订。
license: Apache-2.0
compatibility: Python 3.12 or Node.js 24
metadata:
  version: 2.1.8
  upstream-commit: 683bc88e56f3e09ba94f7055977f3d3aa499f202
  homepage: https://github.com/anthropics/skills
---

# 网页设计与制作

先读取 [设计指导](references/frontend-design.md)，按任务内容选择版式、字体、配色和交互，使用用户真实数据。信息足够时自主完成设计，只有缺失信息影响结果时澄清。不要把所有需求都压缩成卡片列表。

网页和演示需要统一风格时读取 [主题契约](references/theme-presets.md)。本包生成器支持 `theme.preset` 与 `--sf-*` 配色变量；修订既有页面时可沿用原有样式系统。主题选择服从用户要求，并检查实际页面的对比度与可读性。

## 创建与修改

独立网页通常采用可离线打开的 HTML，包含 `html lang`、标题和 viewport，内联必要的 CSS、JavaScript、图片和数据。只有需要组件和状态管理时才选 React。格式与打包方式按用户目标决定，不附加无需求的源码或 ZIP。

修改已有网页时读取原件，理解其结构和交互。样式优化优先定向修改原 CSS，保留未要求修改的正文、数据和交互；可按当前文件与工具能力选择直接编辑，不必为了换样式把整页重写或转成生成器输入。

使用本包生成器时按下表选择入口；设计指导也可独立用于已有网页。

| 用途 | 入口 | 输入参考 | 输出 |
|---|---|---|---|
| HTML 单页 | `scripts/generate_web_app.py` | [HTML 输入](references/html-input.md)、[示例](examples/site.json) | `index.html` |
| React 单页 | `scripts/build_react_app.mjs` | [React 输入](references/react-build.md)、[示例](examples/react-app.json) | `index.html` |

需要较复杂的交互结构时读取 [复杂网页指导](references/web-artifacts-builder.md)。React 构建器支持的依赖以本包 manifest 为准，不支持任意 NPM 项目。包内示例只说明结构，不是用户数据。

## 检查

在实际预览或浏览器中检查桌面和窄屏、中文长文本、键盘操作，以及页面包含的筛选、排序、表单和展开交互。离线网页还应关闭网络确认核心内容与交互可用。脚本的静态资源检查不能代替浏览器检查。

未知数据标为待确认。外部链接可作为明确点击的导航，不作为离线页面必需的加载资源。未实际执行的视觉、交互或离线检查应如实说明。
