# 网页统一主题

[主题资源](../resources/themes.json) 包含经调整的 Anthropic theme-factory 材料，来源 commit 为 `683bc88e56f3e09ba94f7055977f3d3aa499f202`，适用 [Apache-2.0 许可证](upstream/theme-factory/LICENSE.txt)。资源提供十套主题，适配可读配色角色和中文字体。HTML/React 与 presentation 使用相同 preset 值，包内资源自包含，不联网加载字体或另一 Skill。

`site.json` 或 `react-app.json` 增加 `"theme":{"preset":"ocean-depths"}`。可选 `accent`、`background`、`text` 用六位十六进制字符串覆盖；`font_face` 默认 `Noto Sans CJK SC`，浏览器按本机实际字体选择中文 fallback。网页不嵌入字体，不承诺每个用户设备均装有 Noto。省略 theme 时保留原 HTML/CSS。

支持的十个 preset 为 `ocean-depths`（海洋深处）、`sunset-boulevard`（日落大道）、`forest-canopy`（森林树冠）、`modern-minimalist`（现代极简）、`golden-hour`（金色时刻）、`arctic-frost`（极地霜冻）、`desert-rose`（沙漠玫瑰）、`tech-innovation`（科技创新）、`botanical-garden`（植物花园）、`midnight-galaxy`（午夜星河）。用户已指定主题时直接使用；信息足够时按受众自主选择，无需先展示 PDF 或逐次确认。

生成器在内联 CSS 中添加 `--sf-background`、`--sf-text`、`--sf-accent`、`--sf-surface`、`--sf-muted`、`--sf-border`、`--sf-accent-text`、`--sf-on-accent` 和 `--sf-font`；应用网页基础背景、文字和字体。设计组件使用这些变量或 `sf-surface`、`sf-muted`、`sf-accent` 类，主题才能覆盖整页组件。生成器不尝试重写任意作者 CSS 中的固定颜色；修订原页面时需将相关组件显式改用主题变量，并保持原交互。

```css
.panel { background: var(--sf-surface); color: var(--sf-text); border: 1px solid var(--sf-border); }
.page-title { color: var(--sf-accent-text); }
.primary-action { background: var(--sf-accent); color: var(--sf-on-accent); }
```

主文字与背景对比度低于 4.5、未知 preset、无效颜色或含 CSS 注入字符的字体名会失败，不写成果。主题不允许外链依赖。基础文字对比通过不代表所有作者控件都可读，仍须在真实浏览器检查桌面、窄屏、键盘及离线交互。
