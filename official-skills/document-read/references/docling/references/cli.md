# 文档读取的流程选择

平台没有 CLI 调用路径。普通 PDF 使用 structured；只需原生文本时可选择 native。扫描件通过当前受管 OCR 参数处理，不切换到未支持 VLM。选择 page_start/page_end 限定范围，text_offset/text_limit 控制公开片段。检查转换状态、实际页数、正文完整性与 has_more_text。结果未知先核实原执行，不重复转换掩盖历史状态。

实际输入与能力范围见 [平台契约](../../input-contract.md)，执行与交付见 [根入口](../../../SKILL.md)。
