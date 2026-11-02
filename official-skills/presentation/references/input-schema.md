# 演示文稿输入与版式

运行入口为 [生成脚本](../scripts/generate_presentation.mjs)，它是字段和检查行为的事实源。根对象使用 `schema_version: 2`、非空 `title`、非空 `slides`，可选 `theme`、`output_name` 与布尔值 `bundle_zip`。未知字段和旧版输入明确拒绝。

`output_name` 默认为 `presentation.pptx`，例如 `project-update.pptx`。只接受已为 NFKC 的单个文件名，保留原名、不自动修剪或规范化；拒绝首尾空白、C0/C1 控制字符、路径分隔符与 `\ / : * ? " < > |`。最多 200 个 Unicode 字符且 UTF-8 不超过 255 字节，扩展名须为 `.pptx`（不区分大小写），文件主体去除末尾点和空格后不能为 Windows 保留名称 `CON/PRN/AUX/NUL/COM1–9/LPT1–9`。实际 PPTX 与 ZIP 内部条目保持同名；显式打包的 ZIP 仍为 `presentation.zip`。

`theme` 可包含 `preset`、`font_face`、`accent`、`background`、`text`；preset 的十套主题和覆盖顺序见 [统一主题](theme-presets.md)。颜色使用六位十六进制字符串，文字与背景对比度至少为 4.5。默认字体为 `Noto Sans CJK SC`，实际运行环境必须有该字体或明确选择已安装中文字体。更改字体后仍须重新渲染检查。

每个 slide 有 `type`、非空 `title` 和可选非空 `subtitle`。每种版式只接受下表的额外字段，不能把用户内容放入未支持字段后假定会被显示。

| type | 额外字段 | 内容处理 |
| --- | --- | --- |
| `title` | 无 | 标题页；大标题与副标题，超出预算拒绝 |
| `section` | 无 | 章节页；章节标题与说明，超出预算拒绝 |
| `bullets` | `bullets: string[]` | 保留完整文字，长段和条目按页拆分 |
| `two-column` | `left`、`right`，各含 `heading` 与 `bullets` | 每侧独立分页，页面按较长一侧对齐 |
| `image` | `image`、`bullets`、可选 `caption` | 左图右文；正文分页时图片按页重复 |
| `metrics` | `metrics`，每项含 `label`、`value`，可选 `detail` | 每页最多六项，超长值或标签拒绝 |
| `table` | `columns: string[]`、`rows: (string/number/boolean/null)[][]` | 原生表格；逐行分页，重复表头，不截断单元格 |
| `chart` | `chart`，含 `type` 与 `series` | 原生图表与嵌入数据工作簿 |
| `summary` | `bullets: string[]` | 总结页，按完整内容分页 |

输入最多 100 个 slide，分页后的 PPTX 最多 100 页；超过时按主题拆分成果。单页要点输入最多 500 项，表格最多六列与 500 行，指标最多 100 项。表格每行与列数一致，单行超过可容纳预算时明确拒绝；使用更少列、拆分描述或重新组织内容，不自动删除单元格。

正文使用 18–20 pt，指标标签为 17 pt；标题为 26/36 pt。页码、字幕、指标说明和图表轴标签使用较小字号。自动分页不会持续缩小正文。字号与保守宽字符预算不能证明实际排版，通过逐页渲染确认。

图片对象示例：

```json
{"type":"image","title":"任务提供的图片","image":{"input_path":"artifacts/真实产物UUID/images/photo.png","fit":"contain"},"caption":"来自当前任务授权输入","bullets":["说明图片所支持的事实"]}
```

`input_path` 是 `/input` 下的相对路径，只接受 `data/<path>` 或 `artifacts/<artifact_id>/<path>`，文件需在当前执行显式挂载。`fit` 为 `contain`（完整显示，默认）或 `cover`（裁切充满）。只接受 20 MiB 以内的 PNG/JPEG；验证内容签名和实际解析路径，拒绝 URL、Base64、SVG、绝对路径与越界符号链接。不会遍历输入目录寻找图片。

图表对象示例：

```json
{"type":"chart","title":"虚构演示数据","chart":{"type":"line","series":[{"name":"样例","labels":["一月","二月","三月"],"values":[2,4,3]}]}}
```

图表类型支持 `bar`、`line`、`pie`。柱形和折线最多四个系列，饼图仅一个系列；每个系列含非空 `name`、最多十二个非空文本 `labels` 和等长有限数字 `values`。各系列分类标签完全一致；饼图数据非负且总和大于零。图例或分类标签超长时拒绝并要求缩短或换成表格，不静默隐藏标签。数值事实由任务输入提供，不联网补充未知数据。

既有 PPTX 无损模板编辑、动画、复杂母版和嵌入对象不在该生成器支持范围。所需编辑能力超出本入口时，应依据实际支持范围选择处理方式。
