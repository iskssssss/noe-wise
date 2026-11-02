# Note 治理与 Runtime 事实源

适用于 Runtime 与项目治理的长期决策/实施记录；非 Runtime 技术决策使用 ADR，全项目归属见[文档规范](documentation-standards.md)。

## 1. 目标

保存需追溯的决策、状态和证据，不为普通实现细节建材料。任务执行见[开发流程](../../nw-workflow/references/development-workflow.md)，验证见[评审与验证规范](../../nw-verification/references/candidate-driven-delivery.md)。

## 2. 文档分层与唯一事实源

| 事实 | Harness 归属 |
|---|---|
| MVP-0 需求、NFR 与验收编号 | [01-需求](../../../../harness-design/01-Agent-Workflow协作引擎-MVP需求分析.md) |
| 全局架构、模块、装配与宿主替换 | [02-架构](../../../../harness-design/02-微内核插件架构实现思路.md) |
| 契约、对象、状态迁移、不变量与权威边界 | [03-对象与状态机](../../../../harness-design/03-核心对象模型与状态机.md) |
| P1–P3 需求与验收 | [04-扩展](../../../../harness-design/04-后续阶段需求扩展-P1-P3.md) |
| MVP-0 里程碑、数据、Gate 与 DoD | [06-实施](../../../../harness-design/06-MVP-0技术底座实施方案.md) |
| 委派快照、操作、生命周期、持久恢复与 Session/Workflow 组合 | [08-委派](../../../../harness-design/08-Agent委派与多智能体运行时.md) |

主文档表达已批准目标，实现与验证来源按[文档归属](documentation-standards.md#1-分级体系每类事实只有一个家)，Note 不复制技术契约。

## 3. Notes 目录与生命周期

路径为 `.agents/notes/<status>/<class>/YYYY-MM-DD-topic.md`；class 限 architecture、feature、process、testing、bug-fix、simplification，topic 用小写 kebab-case，头部 Status 与目录一致。首次产生需要保留的决策时再创建目录和索引，README 只列链接和一句摘要。仓库不依赖未公开的历史记录；当前契约应在设计正文自足表达，验证结论必须有当前可访问的源码、测试或报告支持。

| status | 含义 | 必要信息 |
|---|---|---|
| proposed | 尚未整体验收，不自证已评审/实现 | Problem、Proposal、Acceptance criteria |
| implemented | 行为、必要文档与有效验证一致 | Problem、Decision、Verification |
| rejected | 明确放弃，不作待实施计划 | Problem、Rejected proposal、Reason |

章节可合并，有实际取舍/风险/影响才补对应内容。已授权实现包含证据回写和满足条件后的本地转态，纯讨论不转态，状态不扩大[授权](../../nw-workflow/references/development-workflow.md#阶段交付与授权)。转态移动原文件，不复制；implemented 使用现在时 Decision 和已验证 Verification。跨阶段未全部验收仍为 proposed，分别记录已交付子项与剩余范围。

## 4. Note 触发与复用

Runtime/项目治理任务产生值得未来维护者保留的决策、取舍或跨阶段交接事实时，更新已有 Note；没有合适记录才新建。以下是定位已有事实源的线索，不是自动建文档的触发清单：

- 业务语义变化；
- 状态或持久化模型变化；
- 跨模块契约变化；
- 架构边界变化；
- 治理授权、验收或恢复策略变化；
- 已有跨阶段任务续接未完成范围。

直接在已有上下文判断；定向查同决策、机制或明确备选的 Note，能承接则更新。代码、契约、测试或现有任务摘要已足以表达且无独特决策价值时，不新增 Note，不另建勾选记录或盘点全库。

已有设计的实现、恢复目标行为的修复、普通补测、淘汰旧实现、公开类型改动或规则措辞澄清本身不触发 Note，仍按上述条件判断；不因文件名含 Agent/Session/retry/recovery 建记录。错误证据直接纠正所属记录。

## 5. 文档驱动实施流程

定位所属事实与同主题记录，有决策才写问题、取舍、范围与可观察验收；设计语义变化才改主文档，未触发 Note 直接实施。

按[状态维护节奏](../../nw-workflow/references/development-workflow.md#工作状态快照)更新所属阶段的差量、验收与下一步；已有 Note 承载状态时不再建快照。总方案只更新全局依赖与验收索引，详细执行证据保留原报告；全部验收后按 §3 转态。

## 6. 工程规则引用

架构替换/数据授权见[工程规范](../../nw-engineering/references/engineering-standards.md#项目阶段与优先级)，目标 schema 与发布验收见 [Harness 06 §5.2](../../../../harness-design/06-MVP-0技术底座实施方案.md#52-目标-schema-落地顺序)和[§7](../../../../harness-design/06-MVP-0技术底座实施方案.md#7-架构一致性与发布门禁)。

## 7. 追溯与验收索引

implemented Note 链接适用需求/设计、决策、实现及验证，不补造层级。Verification 保存范围/版本、检查或证据位置、结果和限制；同检查可覆盖多个目标，按[证据复用](../../nw-verification/references/candidate-driven-delivery.md#证据复用与-verification-manifest)区分未运行、复用和本次通过。原报告不复制，历史时间/版本与本次差量分开。

部分取代在原 Note 标明范围并互链当前来源，保留历史验证，不将已交付 Note 移入 rejected；历史 Decision 不覆盖当前规则。

## 8. 合并检查清单

只核对本次归属、链接、目录/Status、实际范围与证据，不追加流程或历史清理。
