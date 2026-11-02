# 文档输入与能力范围

本包使用独立实现的 Python 脚本与 `python-docx==1.1.2`；未复制或改编外部受限 Office Skill。精确依赖由 [manifest](../manifest.json) 声明。使用方式见 [Skill 入口](../SKILL.md)。

## 输出命名

可选 `output_name` 指定实际生成文件的位置，`title` 只表示正文与展示标题。名称必须为 NFKC 规范的安全单段 basename，不接受路径、控制字符、Windows 保留名；最多 200 字符、255 UTF-8 字节。Markdown 必须以 `.md` 结尾，Word 及 PDF 模式的中间 DOCX 必须以 `.docx` 结尾。省略时使用默认文件名；`inspect` 不生成文件，不使用此参数。`bundle_zip` 仍使用独立的 `document.zip`，不影响所选正文文件名或 ZIP 内实际名称。

PDF 转换时使用转换能力支持的实际文件名，生成器的 `output_name` 只控制中间 DOCX 名称。

## 创建

`action` 默认为 `create`，`format` 默认为 `markdown`，另支持 `docx` 与 `pdf`。`title` 为必填文本，`subtitle` 可选；`sections` 为非空数组，每项有 `heading`、可选 `level`（1 至 5，默认 1）、可选 `page_break_before` 和非空有序 `blocks`。输入文本保持原内容与空格，不自动补全未知事实。

| block.type | 参数 | 行为 |
| --- | --- | --- |
| `paragraph` | `text` 或非空 `runs`；`alignment` 为 left/center/right/justify | runs 含 text 和可选 bold/italic/underline；text 与 runs 互斥 |
| `paragraph` | 可选 `keep_with_next`、`keep_together`、`page_break_before` 布尔值 | Word 段落分页约束，默认 false；Markdown 不表达这些分页属性 |
| `bullets` / `numbered` | 非空 `items` 文本数组 | 无序/有序列表 |
| `table` | 非空文本 `headers`、`rows` 数组 | 每行列数一致，允许空单元格；Word 首行重复表头 |
| `table` | 可选 `column_widths_mm`，`allow_row_split` 默认 false | 列宽数量匹配列数，总宽不超过页面；较高的内容行按需求允许跨页 |
| `image` | 明确 `path`、必填 `alt`、`width_mm` 默认 120、可选 `caption` | 只接受有正确签名的 PNG/JPEG；Word 内嵌图片，Markdown 同时交付 assets 文件 |
| `page_break` | 无其他必填参数 | Word 插入分页；Markdown 输出分隔线 |

`styles` 支持 `western_font`、`cjk_font` 与 `body_size_pt`（10 至 14）。新 Word 默认 Liberation Sans、Noto Sans CJK SC 和 11 pt；字体名称是文件字体声明，镜像必须真实安装对应字体才证明渲染使用该字体。

`page` 支持 `size`（A4/Letter）、`margin_mm`（10 至 50）、`header`、`footer`、`page_numbers`。新 Word 默认 A4、22 mm 四边页距与页码；标题跟随下段，表格首行重复并默认保持整行。页码使用 PAGE 域，由 Word/LibreOffice 渲染计算。长文本由实际渲染检查分页与跨页表现，不承诺脚本自动估算所有内容的页高。

`template_path` 只用于 Word/PDF 创建，读取普通 DOCX 的现有结构并在末尾追加内容。默认保留模板已有页设置和样式；明确给出 styles/page 时应用所指定配置；模板首段已有页眉页脚时不允许用 page 覆盖。此模式不清除模板原正文，不进行占位符猜测替换。定向更新占位符应使用 modify。

## 原件修改

本包可用 [读取示例](../examples/document-inspect.json) 中的 `action: "inspect"`、`source_path` 检查原件。脚本的检查报告写入 `/workspace/output/result.json`，`results[0].text` 是 JSON 文本，含正文段落、从零编号的顶层表格行/列与页眉页脚，不生成文档。修改时使用 [修改示例](../examples/document-modify.json) 的 `action: "modify"`、`format: "docx"` 或 `"pdf"`，并提供实际原件的 `source_path`；示例中的输入路径是占位值。

| operation.type | 必填参数 | 支持边界 |
| --- | --- | --- |
| `replace_text` | `find`、`replace`、正整数 `expected_count`；`scope` 默认 body，也支持 headers/footers | 在普通段落和表格段落中逐段精确匹配；不跨段落匹配；可跨普通 run 并保留原 run 格式 |
| `set_table_cell` | 从零编号的 `table`、`row`、`cell`，`expected_text`、`text` | 顶层表格的单个普通文本段落单元格；拒绝合并单元格、嵌套表格和多段落单元格 |

修改直接更新 OOXML，不把原件降为文本后重建。仅命中的正文或页眉页脚 XML 重新序列化，其他包项解压后的内容保持相同，原文件不覆盖；文字替换继承首字符所在 run 的样式，未匹配的内容和结构保留。匹配次数或原文不符时不写任何候选。

目标段落含链接、书签、域、图片、制表符或换行元素时拒绝修改该段落；这些元素在未修改位置继续保留。PAGE/NUMPAGES 页码域可以存在于原件，但含域的目标段落不编辑。不支持并明确拒绝宏、嵌入对象、ActiveX、外链媒体/模板、修订追踪、内容控件、altChunk 和其他复杂域；不自动接受修订或删除不支持对象。普通外部超链接保留，不访问链接目标。此范围不承诺任意 Office 文档无损修改。

原件检查限制为 32 MiB 压缩文件、128 MiB 总解压、2048 包项、32 MiB 单项；大于 1 MiB 的包项解压比不得超过 200。拒绝重名项、加密项、非法包路径、包内符号链接、DTD 与实体声明。被拒绝的原件需用户提供符合范围的版本，不能关闭检查继续处理。

## 输入路径与生成文件

入口仅定位直接 `/input/data/document.json` 或每个显式 `/input/artifacts/<artifact_id>/document.json`；缺失或多个同名输入均失败，不递归扫描包内示例。图片、原件和模板只接受 `/input/data`、`/input/artifacts` 中的明确绝对路径；模板和包内图片也可用 `templates/<path>`。不接受远程 URL、任意宿主路径、`..`、反斜线或解析后逃出允许根的符号链接。

Markdown 图片复制为 `assets/image-N.png` 或 JPEG，正文文件与图片须保持相对路径关系。普通 Markdown 文本不需要 python-docx；Word、DOCX 检查、模板与修改的依赖由包清单声明。

PDF 模式只生成中间 `document.docx`，随后由可用的转换能力导出实际 PDF。`bundle_zip: true` 为 Markdown/Word 打包正文及其必要资源；PDF ZIP 在转换后另行打包实际 PDF。检查 JSON、页图、日志与中间 DOCX 不是用户未请求的正文文件。

操作失败时保留失败结果，修正对应问题，不擅自改成另一格式后报告原要求已满足。
