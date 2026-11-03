---
name: presentation
description: 生成可编辑中文 PPTX，支持标题、章节、双栏、图文、指标、表格、原生图表与总结；长内容分页，按要求导出 PDF 或打包 ZIP。
license: Apache-2.0
compatibility: Node.js 24
metadata:
  version: 2.1.9
  runtime: pptxgenjs==3.12.0
---

# 可编辑演示文稿

先明确受众、讲述目标、用户事实和所需格式。信息足够时自主选择版式与风格，不为使用模板反复询问。每页表达一个主张；图表数值与指标来自用户资料或已确认事实，示例数字不能作为业务数据。普通文字、表格和图表保持可编辑，不能用整页截图代替 PPTX。

企业汇报、周报、FAQ 或事件复盘可读取 [企业内容组织](references/enterprise-writing.md)。风格选择可读取 [统一主题](references/theme-presets.md)，本包通过 `theme.preset` 应用与网页一致的主题，用户机构色可显式覆盖。

未指定格式时建议 PPTX；用户需要固定版式或打包时再生成 PDF 或 ZIP。

## 本包生成器

入口为 `scripts/generate_presentation.mjs`，读取唯一的 `presentation.json`。输入使用 `schema_version: 2`，按 [输入和版式参考](references/input-schema.md) 编写；可参考 [多版式](examples/presentation.json)、[长内容](examples/presentation-long.json) 与 [指定文件名](examples/presentation-named.json) 示例。依赖由包清单声明。

输入支持标题、章节、要点、双栏、图文、指标、表格、原生柱形/折线/饼图与总结。要点、双栏、正文、指标和表格自动分页；过长标题、图例或无法容纳的单行表格明确报错。应调整结构和内容，不无限缩小字体或删除事实凑版面。

默认输出 `presentation.pptx`；`output_name` 可指定实际 PPTX 文件名，`bundle_zip: true` 另生成 `presentation.zip`。图文页使用实际提供的 PNG/JPEG，明确来源、许可和裁切意图；`image.input_path` 的输入路径与尺寸限制见输入参考。

生成器返回源页对应、页数、分页结果和布局预算检查。`layout_budget: passed` 只说明保守版式预算通过，`visual_review: required` 表示仍需查看渲染页面；这些字段不能替代真实视觉检查。

## 检查与格式转换

按 [质量检查参考](references/quality-checks.md) 渲染并查看所有页，核对中文字体、完整内容、文字越界与重叠、表格分页、图表、图片裁切和页码。只有实际读到页图才能称为视觉检查；只取得下载链接时说明该项尚未验证。检查失败时修正输入及相关页面，不只以文件存在宣告完成。

用户需要 PDF 时，转换实际 PPTX 后核对 PDF 页数与内容。只需要 PDF 时，PPTX 是转换中间文件；只需要 PPTX 时不默认附加 PDF。渲染正确不能证明所有 Office 客户端排版完全相同。

## 支持范围

本生成器重建可编辑 PPTX，不支持加载和无损修改任意已有 PPTX 模板、动画、复杂母版、嵌入对象或演讲者备注。修改现有 PPTX 前核对所需编辑能力；若采用已确认内容重建，应说明实际保留范围，不宣称保留未知模板结构。
