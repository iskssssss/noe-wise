---
name: spreadsheet
description: 生成含多工作表、明确数据类型、跨表公式、汇总图表与打印布局的 Excel；读取并定向修改普通 XLSX，实际重算公式并验证缓存，保留重复流水与原件。
license: Apache-2.0
compatibility: Python 3.12；计算与图表缓存需要预装 LibreOffice Calc
metadata:
  version: 2.0.5
  runtime: openpyxl==3.1.5 et_xmlfile==2.0.0
---

# 表格制作与检查

先确认工作簿用途、数据来源、列类型、统计口径与用户要求的格式。未指定格式时建议 XLSX，仅在明确需要时打包 ZIP。实际行、金额和公式含义来自用户资料；示例只说明输入结构，未知事实不能用示例值补齐。

默认保留所有行、原始空白和文本。只有明确要求清洗时才启用去空白或去重，财务流水中相同金额不能自动视为重复交易。编号和超出 Excel 精度的数字标识使用文本，日期、数字和公式使用明确类型。

## 本包生成器

入口为 `scripts/generate_spreadsheet.py`，支持 `create`、`inspect`、`edit`，读取唯一的 `spreadsheet.json`。按需读取 [基本样例](examples/spreadsheet.json)、[跨表汇总样例](examples/spreadsheet-formulas.json) 与 [输入规范](references/input-contract.md)。依赖由包清单声明。

生成或编辑默认输出 `report.xlsx`；`output_name` 可指定安全的 XLSX 文件名，参见 [命名样例](examples/spreadsheet-named.json)。`bundle_zip: true` 另生成 `report.zip`。脚本返回的实际文件名与检查结果是判断生成结果的依据。

列可设置文字、数字、整数、布尔、日期、时间或公式。普通 `=...` 字符串始终写为文本，只有 `type: "formula"` 的列或单元格才写入公式。非有限数字、隐式字符串转数字及有时区的 Excel 日期会被拒绝。

## 公式与数值

openpyxl 写入公式不会计算结果。本包含公式或图表时默认使用已安装 LibreOffice 重算；核对 `recalculated`、`formula_cache_verified`、`cached_formula_count` 与 `chart_cache_verified`。需要数值正确时保留重算，并用独立可确认的金额或跨表结果设置 `checks`，不把猜测值作为校验依据。

用户只需要公式结构时可明确 `recalculate: false`，并说明缓存未验证。重算失败不能自动关闭重算后声称结果已计算；预览或文件存在也不能证明计算完成。支持函数、缓存与误差检查见 [计算与检查](references/calculation-and-quality.md)。

## 既有工作簿修改

修改前读取原件、确认工作表结构与目标位置。本包 `inspect` 返回有限预览及截断标记；必要时继续读取具体数据，不能把局部预览当成全量数据。按 [编辑样例](examples/spreadsheet-edit.json) 定向更新单元格、样式、冻结和打印设置，保留原件和其他内容，不为局部修改重建全表。

生成器仅支持普通 XLSX 的基础工作表、样式、单元格公式与标准图表。不支持宏、外部链接与连接、透视表、图片、嵌入对象、特殊形状和扩展；发现时明确拒绝并保留原件，不自动执行宏、访问网络或静默删除对象。具体文件读取与 `source` 结构见输入规范。

## 检查

核对重复流水、数据类型、普通等号文本、公式缓存、跨表结果和图表数据。检查打印范围、列宽、数字格式、表头重复、分页及图表遮挡；需要确认布局时查看实际 PDF 或页图。图表应反映真实数据，非负金额柱形图通常从零起，不能通过裁掉数据夸大差异。

未看到实际页面时说明视觉检查缺口。LibreOffice 与 Excel 可能有排版差异，仅说明实际验证过的环境；修改公式或布局后复查相关部分。