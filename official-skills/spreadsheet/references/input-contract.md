# 工作簿输入规范

入口始终读取唯一显式挂载的 `spreadsheet.json`，不会从 examples 或工作区搜索输入。JSON 最大 2 MiB；普通 XLSX 最大 20 MiB、ZIP 包最多 2048 条目、总解压大小最大 64 MiB。最多 32 个工作表，工作簿总有效矩形范围最多 100000 个单元格；稀疏远端单元格也占用该范围预算。单元格文本最大 32767 字符。未知字段、损坏包、越界路径及歧义输入均失败。

可选 `output_name` 决定 create/edit 的实际输出 XLSX 文件名；省略采用 `report.xlsx`。必须是 NFKC 规范的安全单段 basename，拒绝路径分隔符、C0/C1 控制字符和 Windows 保留名；最多 200 字符、255 UTF-8 字节，扩展名 `.xlsx`（大小写无关）。`inspect` 不接受命名参数。命名不改变公式、重算、检查或编辑原件；ZIP 仍为 `report.zip`，包内 XLSX 条目及类型化 result.output 使用实际名称。

该可选属性的 JSON Schema 为（与现有脚本字段校验共同使用，安全规则由脚本严格执行）：

```json
{"output_name": {"type": "string", "minLength": 1, "maxLength": 200, "pattern": "^[^\\\\/:*?\"<>|\\u0000-\\u001F\\u007F-\\u009F]+\\.[xX][lL][sS][xX]$"}}
```

顶层 `operation` 默认 create；支持字段为 `operation`、`sheets`、`source`、`updates`、`recalculate`、`checks`、`bundle_zip`、`output_name`。create 必须提供非空 `sheets`，不接受 source/updates；edit 必须提供 source 和非空 updates，不接受 sheets；inspect 只接受 operation/source。

工作表使用 `name` 和可选 `columns`、`rows`、`cells`、`charts`、`trim_strings`、`deduplicate_by`、`freeze_panes`、`autofilter`、`print`。名称符合 Excel 31 字符限制且不重复。columns 为 `{key,title,type,number_format,width}` 数组，key/title 唯一，title 默认 key，type 默认 text。rows 是按列键组织的对象数组，缺省值为空；未知列键拒绝。默认不去空白、不去重；用户明确清洗时 `trim_strings: true`，去重时用非空唯一列键数组 `deduplicate_by`。去重键保留数据类型，文本 "1" 与数字 1 不是同值。

单元格值可以是 JSON 基础值，也可以是 `{type,value}`。类型支持 text、number、integer、boolean、date、datetime、formula、blank。number/integer 仅接受有限 JSON 数字；布尔值不转换为数字；日期为 ISO 日期，datetime 不接受时区。blank 使用 null。显式类型优先于列类型，普通字符串按文本保存，即使以 `=` 开头；列类型 formula 或 `{type:"formula",value:"=..."}` 才写入公式。text 保留前导零；涉及证件编号、流水号和超过 Excel 精度的数字标识时使用 text。

cells 为 `{cell,value,style}` 数组，cell 为单一 A1 地址，不重复；value/style 至少一项。基础值自动识别数字、布尔和空值，其余为 text。样式支持 bold、六位 RGB font_color/fill、number_format、wrap_text、horizontal（left/center/right/general）。cells 在 columns/rows 后执行，因此可明确添加或更新统计单元格。修改只应用明确列出的 cell，不重新生成原工作簿其他内容。

charts 最多 16 个，支持 `{type,title,sheet,data,categories,anchor,title_from_data,width,height,axis_min}`。type 为 bar、line、pie；sheet 默认为所在工作表；data/categories 是有限 A1 范围，categories 必须单列，行数与数据匹配；data 默认首行作为系列名称，取消时 `title_from_data: false`。pie 只支持一列数据系列。anchor 默认 G2，宽高默认 18/10 厘米，允许 3–40 厘米。柱形/折线图可用有限数字 axis_min 设置纵轴下界；比较非负金额的柱形图应设置 0，涉及负数时不得使用会裁掉数据的下界，时间趋势图如使用非零下界须解释口径。图表必须基于实际用户数据和明确定义的汇总，不捏造趋势。

freeze_panes 默认为有 columns 时 A2，无 columns 时不冻结；可传 null。autofilter 默认为有 columns 时开启。默认自动列宽、中文换行、A4 横向、宽度一页、高度不限；column.width 允许 6–80。print 支持 orientation（landscape/portrait）、area（本地范围）、repeat_rows（如 1:1）、fit_width/fit_height（0–10）。图表超出当前单元格范围时明确扩展 print.area，默认单元格范围不包含远端图表面积。

source 为输入文件的 `{artifact_id,path}`，对应本次可读的 `/input/artifacts/<artifact_id>/<path>`；artifact_id 与 path 应来自实际输入引用，示例中的 UUID 是占位值。输出为 `/workspace/output/<output_name>`（省略名称时为 `report.xlsx`），不覆盖原件。编辑 updates 是 `{sheet,cells,print,freeze_panes}` 数组，sheet 必须是存在且不重复的工作表。不支持增删工作表、重命名、全表重写、宏或特殊对象；未知需求先确认可支持范围。

参考 [明确命名](../examples/spreadsheet-named.json)、[简单流水](../examples/spreadsheet.json)、[跨表汇总与图表](../examples/spreadsheet-formulas.json)、[既有成果修改](../examples/spreadsheet-edit.json)。编辑样例中的 UUID 是占位值，执行时必须替换为真实引用。
