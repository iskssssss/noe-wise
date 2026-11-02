# 官方技能包

本目录保存可独立发布的官方 Skill 源码。系统不预置 Skill，平台和 Runtime 的生产 JAR 不携带这些内容；平台人员与机构人员使用同一套草稿、校验、发布及精确版本挂载机制维护技能。

| 能力 | 包 |
|---|---|
| 资料读取与办公交付 | [document-read](document-read/SKILL.md)、[document](document/SKILL.md)、[spreadsheet](spreadsheet/SKILL.md)、[presentation](presentation/SKILL.md) |
| 网页生成与验收 | [web-app](web-app/SKILL.md)、[webapp-testing](webapp-testing/SKILL.md) |
| 专业任务 | [research](research/SKILL.md)、[mcp-builder](mcp-builder/SKILL.md)、[configuration](configuration/SKILL.md)、[code-delivery](code-delivery/SKILL.md) |

[index.json](index.json)管理发布文件集合，各包 `SKILL.md` 的 `metadata.version` 声明精确版本。来源、许可、依赖锁及构包命令见[维护工具](../tools/skills/README.md)。ZIP 中只包含所属包的文件，构包不执行包内脚本，也不自动调用平台 API。

发布流程为：创建 Skill 定义 → 编辑草稿或导入 ZIP → 校验 → 发布新版本 → 机构或 Agent 采用。已发布内容不可原地改写，切换默认版本不更新固定版本挂载，已受理执行继续使用原冻结版本。具体后台操作与采用边界见[运行指南](../docs/04-指南与运维/09-代码执行与产物运行指南.md#官方-skill-独立发布与采用)。

技能正文、参考、示例和脚本可以独立更新；新增原生库、浏览器、OCR 模型等环境能力仍需对应沙箱支持。平台市场的分发能力与包的发布机制分别演进，保存于本目录不代表已上线市场或已安装到任何机构。
