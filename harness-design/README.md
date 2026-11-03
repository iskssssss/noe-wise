# Agent Runtime 设计

本目录定义 灵知 Agent Runtime 的目标边界、对象模型与分阶段落地方案。
产品以通用智能 Agent 为中心，由 Agent 理解意图、结合知识与记忆判断、选择受授权的行动并整合结果；Workflow 与专业 Agent 委派分别支撑确定执行和专业分工。产品定位、用户旅程及产品级路线先从[产品概述](../docs/01-产品与需求/02-产品概述.md)、[PRD](../docs/01-产品与需求/01-PRD.md)和[实施路线图](../docs/01-产品与需求/04-实施路线图.md)阅读，本目录负责支撑这些结果的技术契约。
设计定义技术契约；当前模块、精确接口与实际行为以 `backend-java` 源码和对应验证为证。文档分层按 [Note 治理规范 §2](../.agents/skills/nw-docs/references/notes-governance.md#2-文档分层与唯一事实源)，完整或部分交付状态按 [§3](../.agents/skills/nw-docs/references/notes-governance.md#3-notes-目录与生命周期) 判读。

## 阅读顺序

| 文档 | 职责 |
| :-- | :-- |
| [01-MVP-0 需求分析](01-Agent-Workflow协作引擎-MVP需求分析.md) | MVP-0 的需求、边界和验收标准；FR 编号的权威来源。 |
| [02-微内核插件架构](02-微内核插件架构实现思路.md) | 内核、插件、能力装配与设计替换路径。 |
| [03-核心对象模型与状态机](03-核心对象模型与状态机.md) | 契约 record、运行时对象、状态机和事实源边界。 |
| [03-上下文视图与执行优化专题](03-上下文视图与执行优化.md) | 任务边界、历史摘要、输出回读、冻结请求、并行只读及流聚合的目标契约。 |
| [03-多模态内容与媒体引用专题](03-多模态内容与媒体引用.md) | 有序图文、可信媒体、请求变体、视觉计量、图片卸载与冻结恢复的目标契约。 |
| [04-P1/P2 需求扩展](04-后续阶段需求扩展-P1-P3.md) | P1/P2 需求与验收来源；P3 动态插件与生态市场另行立项。 |
| [06-MVP-0 技术底座实施方案](06-MVP-0技术底座实施方案.md) | 技术底座与 SDK 的里程碑、数据方案、架构门禁与 Definition of Done。 |
| [07-文档治理入口](../.agents/skills/nw-docs/references/notes-governance.md) | 导航到技能中的 Runtime 文档分层与 Note 治理规则。 |
| [08-Agent 委派与多智能体运行时](08-Agent委派与多智能体运行时.md) | 预定义专业 Agent 的动态选择、目标快照、父子运行操作、生命周期、持久化、恢复及 Session/Workflow 组合语义。 |

## 关键架构图

| 图 | 用途 |
| :-- | :-- |
| [MVP-0 总体架构](02-微内核插件架构实现思路.md#12-mvp-0-全局架构) | 查看接入、Session、Agent、能力运行时、外部调用守卫、权威事实域与查询投影的整体关系。 |
| [问题到结果时序](02-微内核插件架构实现思路.md#51-正常执行) | 查看 Inbound、上下文快照、Action/Observation 循环、Workflow 与 Outbox 的完整路径。 |
| [记忆与上下文融合](02-微内核插件架构实现思路.md#57-会话状态记忆与生命周期) | 查看工作上下文、短期对话、会话/中长期事实、任务投影与冻结知识证据如何进入 ContextSnapshot。 |
| [多挂起意图唤醒](02-微内核插件架构实现思路.md#510-多执行与挂起路由单-session-多-workflow) | 查看自由文本、确定性回调、候选冻结与单目标恢复的路由。 |
| [第三方回环时序](02-微内核插件架构实现思路.md#512-第三方回环在会话中的形式) | 查看回调入 Inbox、Recovery、Workflow 恢复与 Output Mailbox 排序。 |
| [轨迹与费用归因](02-微内核插件架构实现思路.md#45-端到端轨迹与计量边界) | 查看 InvocationContext、各权威账本、TraceProjection、UsageSummary 与 OpenTelemetry 的边界。 |
| [执行与持久化主链](02-微内核插件架构实现思路.md#15-执行与持久化重构) | 查看 Core、Session、Store 的单一职责及当前源码入口；事务与恢复契约归 [最小权威模型](03-核心对象模型与状态机.md#101-执行与持久化的最小权威模型)。 |
| [状态机与事实源关系](03-核心对象模型与状态机.md#6-状态机) | 查看 Session、Execution、PendingEntry、MemoryFact 状态机及事实源投影关系。 |
| [Agent 委派总体架构](08-Agent委派与多智能体运行时.md#3-总体架构) | 查看 Parent Loop、Delegation Runtime、目标/能力快照、子 Agent Loop、Workflow 与 Session Mailbox 的关系。 |
| [Agent 委派生命周期](08-Agent委派与多智能体运行时.md#11-生命周期与状态机) | 查看 Delegation Execution 与 Parent Loop 的独立状态机。 |

## 事实源约定

按 [Note 治理规范 §2](../.agents/skills/nw-docs/references/notes-governance.md#2-文档分层与唯一事实源) 查询本目录的主题归属；全项目边界见 [文档规范](../.agents/skills/nw-docs/references/documentation-standards.md#1-分级体系每类事实只有一个家)。本索引不重复定义契约、状态或术语。

## 分期

MVP-0 与后续阶段概览见 [Harness 01 §6](01-Agent-Workflow协作引擎-MVP需求分析.md#6-mvp-0-范围边界)，技术底座与 SDK 的交付顺序见 [Harness 06](06-MVP-0技术底座实施方案.md)，P1/P2 需求与验收见 [Harness 04](04-后续阶段需求扩展-P1-P3.md)。
