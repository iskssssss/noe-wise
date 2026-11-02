# Docling 结构与流水线

DocumentConverter 的结果包含转换状态和 DoclingDocument。正文、段落、表格与页码应结合来源解释。结构化 PDF 保留布局与表格，native 模式只读原生文本；Office 的图片和图表不自动变成 OCR 正文。运行参数和模型均由本包受管入口冻结，不能用 SDK 示例绕过来源校验或扩大支持范围。

仅 ConversionStatus.SUCCESS 可形成成功候选。PARTIAL_SUCCESS、模型故障或超时均保持失败；正文片段与原页截取是不同限制。Markdown 用于阅读，结构化 JSON 用于显式要求的导出；不默认额外交付文件。

实际输入与能力范围见 [平台契约](../../input-contract.md)，执行与交付见 [根入口](../../../SKILL.md)。


## 受管输入与结果读取示例

读取已有明确挂载的 PDF，只提供受支持参数，不另建 DocumentConverter：

```json
{"source_path":"/input/artifacts/<真实artifact_id>/report.pdf","action":"read",
 "pdf_pipeline":"structured","page_start":1,"page_end":5,"text_limit":12000}
```

该 JSON 通过 input_files.document-read.json 传入，原件须同次 artifact_inputs 显式挂载。结果 results[0].text 是读取摘要 JSON，results[1] 是 Markdown 片段。has_more_text=true 时以真实 next_text_offset 请求下一片段，原件每次重新挂载；pages_selected=[1,5] 不能推定已读取原件其余页。

需要原生文本时显式 pdf_pipeline=native；不会因此得到 OCR 或表格重建。明确导出时才用 action=export，format=markdown 或用户要求的 json，检查实际文件后以完整选择调用 finalize_task_outputs。

## SDK 结构概念

受管实现内部使用 DocumentConverter.convert；format_options 使用 InputFormat 枚举与对应 FormatOption，而不是字符串键映射 PipelineOptions。PdfPipelineOptions 控制固定结构化 PDF，原生 PDF 使用 NativePdfFormatOption。模型位置和 OCR 引擎已由镜像契约冻结，不由任务参数自由切换。

DoclingDocument 的 document.tables 是表格集合，table.export_to_markdown(doc=document) 可表达表格；iterate_items() 的 item/level 可解释层级；export_to_dict() 保留结构。这些是理解结果与维护入口的方法，不要求模型复制 SDK 程序替代受管读取。平台导出 JSON 去掉图片像素，仅保留允许的页坐标和表格结构；不尝试联网补图。
