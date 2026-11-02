# 读取输入与资源边界

只接受 `/input/data/document-read.json` 的JSON对象配置，最多64KiB。原件来自明确的 `/input/data` 或 `/input/artifacts` 挂载；拒绝相对路径、`..`、URL和符号链接逃逸。文件仅PDF、DOCX、PPTX、XLSX、PNG、JPEG，非空且不超过50MiB。

| 字段 | 默认值与行为 |
|---|---|
| `source_path` | 必填，原件的明确绝对挂载路径 |
| `action` | `read`：只返回公开正文；`export`：同时生成所需文件 |
| `format` | `markdown`；导出可显式选 `json` |
| `pdf_pipeline` | `structured`：布局、OCR、表格；`native`：PDF原生文本，不做OCR或表格重建 |
| `page_start` / `page_end` | 一基页号，仅PDF/图片；默认从第一页读取至最多20页 |
| `max_pages` | 默认20，最大100；选定范围不能超过该值 |
| `text_offset` | Markdown字符偏移，默认0，读取下一段采用上次返回的真实偏移 |
| `text_limit` | 默认12000，允许256至24000字符；回读是否完整以 `has_more_text` 为准 |
| `ocr` / `force_ocr` | 默认启用OCR、非强制整页；扫描PDF可明确启用强制OCR |
| `tables` | 默认重建PDF/图片表格；关闭时不运行TableFormer，不保证扫描表格结构 |
| `timeout_seconds` | Docling文档超时默认120秒，允许10至300秒；宿主执行预算、取消和deadline继续生效 |

PDF拒绝密码材料和超过100页原件。Office按原生结构读取，不能按打印页范围截取；PPTX最多100张幻灯片。Office ZIP最多4096项、解压合计128MiB；XML项最多8MiB，其他项32MiB，超过200倍且解压后超过1MiB的压缩项拒绝。包须有相应主文档和内容类型，拒绝DTD/实体、宏、ActiveX、嵌入对象以及非超链接的外部关系。XLSX范围最多10000行、256列、合计100000个存储单元格，读取已有公式缓存，未缓存的计算值可能为空。

图片必须实际为PNG/JPEG且能完整解码，最多4000万像素。OCR采用本地Tesseract简体中文及英语，准确率依赖原件质量，未对任意字体、语言、手写或扫描表格作准确率承诺。Office中的图片和图表不自动转成OCR结果；当前不提供无损转换、修改原件、知识库入库或检索授权。

转换只有 `ConversionStatus.SUCCESS` 才写候选。部分失败、模型缺失、版本/摘要不符、内存限额或超时均失败，不将不完整转换标记为成功。成功后仍需区分选定页面范围与公开正文片段；正文和结构化JSON各最多8MiB，超过时缩小页范围。

SDK与依赖固定在Python运行镜像。布局模型Heron固定Hugging Face commit `8f39ad3c0b4c58e9c2d2c84a38465abf757272d8`（Apache-2.0），TableFormer固定 `fc0f2d45e2218ea24bce5045f58a389aed16dc23`（CDLA-Permissive-2.0）。每次结构化读取先核对镜像中的模型清单、大小和SHA-256。模型未随Skill包分发，运行期不下载，不启用VLM、远程服务或外部插件。任务执行及取消沿用 `code_execute` 所属管线；结果未知时查原执行，不能重新提交。
