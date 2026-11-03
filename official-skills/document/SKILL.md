---
name: document
description: 创建带标题层级、表格、图片和分页的 Markdown/Word 正式报告；定向修改既有普通 DOCX 并保留其样式与关系，按明确要求导出 PDF。
license: Apache-2.0
compatibility: Python 3.12
metadata:
  version: 2.1.7
  runtime: python-docx==1.1.2,lxml==6.1.3,typing-extensions==4.16.0
---

# 文档制作与修改

按用户事实确定受众、标题层级和内容顺序。周报、项目更新、管理层汇报、FAQ 或事件报告可读取 [企业内容组织](references/enterprise-writing.md)，区分事实、判断、计划与待确认项；不使用示例数据补齐未知事实。

未指定格式时建议 Markdown；需要可编辑排版时使用 Word，需要固定版式时导出 PDF。仅在用户要求打包时生成 ZIP。

## 内容与版式

按内容安排段落、列表、表格和图片。正文按 10 至 14 pt 排版，长篇按章节分页，超高表格行明确允许跨页；不要无限缩小字体凑一页。新 Word 默认 A4、中文字体 Noto Sans CJK SC、11 pt 正文与页码，可按需求设页眉页脚。

图片使用实际提供的 PNG/JPEG，填写真实 `alt`，外链不作为正文必要依赖。Markdown 的分页控制只表达为分隔线，Word 页眉页脚和实际分页不适用于 Markdown。

## 本包生成器

入口为 `scripts/generate_document.py`，读取唯一的 `document.json`。输入可参考 [Markdown](examples/document.json)、[Word](examples/document-word.json) 和 [明确命名](examples/document-named.json)；完整字段与脚本输入路径见 [输入与能力范围](references/document-schema.md)。依赖由包清单声明。

`sections` 每项含非空 `heading` 与有序 `blocks`；`level` 可选，范围为 1 至 5，默认 1。`format` 省略时生成 Markdown；`docx` 生成 Word；`pdf` 先生成 DOCX，随后使用可用的 PDF 转换能力。`output_name` 指定实际文件名，不能用正文 `title` 替代。

默认文件名为 `document.md` 或 `document.docx`，Markdown 图片另存到 `assets/`。需要 PDF 时检查转换后的实际 PDF；只需要 PDF 时，DOCX 是中间文件。PDF 与 ZIP 同时要求时，转换完成后打包真实 PDF，不把中间 DOCX 当作 PDF。

## 既有文档修改

修改前读取原件并核对目标结构。本包的 [inspect](examples/document-inspect.json) 可返回正文、从零编号的表格与页眉页脚；[modify](examples/document-modify.json) 用明确的 `source_path` 与 `operations` 更新原件。`replace_text` 指定 `expected_count`，`set_table_cell` 指定位置与 `expected_text`。匹配不符时停止，不把提取文本后重建的文件冒充原件修改。

修改脚本仅重写命中的正文或页眉页脚 XML，保留未修改的包项、样式、媒体和关系；跨 run 文本替换保留 run 格式，替换内容继承首字符样式。`template_path` 创建模式在原结构末尾追加内容，不表示任意模板的无损编辑；替换模板正文应使用定向修改。

生成器仅支持普通 DOCX，不支持宏、修订追踪、内容控件、复杂域和嵌入对象。支持范围和拒绝条件见 [原件修改](references/document-schema.md#原件修改)，不静默删除不支持结构。

## 检查

检查内容完整性、中文字体、分页、表格跨页和页眉页脚。Word/PDF 的实际布局需要渲染后查看页图；渲染成功或取得下载链接不等于已经看过页面。检查失败时修正受影响内容并复查，不只以文件存在宣称完成。

未实际完成的视觉检查应说明。PDF 渲染不能证明 Word 在全部 Office 客户端布局一致。
