# HTML 生成器输入

入口为 `scripts/generate_web_app.py`，读取唯一的 `site.json`。可直接提供完整 JSON 文本，不需要先运行一次代码准备输入文件；已有输入文件也可用于执行，二者选其一，不提供同名空字符串占位。

`site.json` 仅支持非空 `html`、可选布尔值 `bundle_zip` 与可选 `theme`。`html` 是完整网页，示例见 [site.json](../examples/site.json)；`theme` 的字段与覆盖方式见 [主题契约](theme-presets.md)。包内示例不会被自动当作输入。

长页面可用共享 CSS、可复用组件、数据数组和循环生成重复内容，保留用户要求的正文与交互，减少重复 HTML。制作过程可分文件维护，再按任务要求和本生成器的输入契约组装完整 HTML；不要将制作阶段的文件组织方式当作最终交付格式要求。

脚本只从 `/input/data/site.json` 或明确输入中的 `/input/artifacts/<artifact_id>/site.json` 定位文件。缺少输入与存在多个同名输入分别报错；不会从临时工作目录或示例目录推断来源。输入对象在文件传递通道中的值应是 UTF-8 JSON 文本，不是嵌套对象。

输出为 `/workspace/output/index.html`，`bundle_zip: true` 时另生成 `site.zip`。HTML 内的 CSS、JavaScript 和必要资源应完整内联；生成器检查常见外部加载引用，但不证明浏览器布局、全部交互或离线行为正确。
