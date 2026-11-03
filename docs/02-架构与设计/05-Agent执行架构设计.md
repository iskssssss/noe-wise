# Agent 执行架构：宿主集成索引

> Agent Runtime 的目标架构已经迁移到 [Harness Design](../../harness-design/README.md)。
> 本文只说明宿主平台的集成入口，不再维护第二套执行架构、数据模型或阶段计划。

## 1. 权威文档

| 主题 | 文档 |
| :--- | :--- |
| MVP-0 需求与验收 | [Harness 01](../../harness-design/01-Agent-Workflow协作引擎-MVP需求分析.md) |
| 微内核、插件与宿主边界 | [Harness 02](../../harness-design/02-微内核插件架构实现思路.md) |
| 契约、对象与状态机 | [Harness 03](../../harness-design/03-核心对象模型与状态机.md) |
| P1/P2 扩展 | [Harness 04](../../harness-design/04-后续阶段需求扩展-P1-P3.md) |
| M1 Java 落地 | [Harness 02](../../harness-design/02-微内核插件架构实现思路.md) |
| MVP-0 实施与门禁 | [Harness 06](../../harness-design/06-MVP-0技术底座实施方案.md) |
| Agent 委派与多智能体运行时 | [Harness 08](../../harness-design/08-Agent委派与多智能体运行时.md) |
| 实现与验证 | 当前源码、契约测试及对应候选的原始运行结果；设计文档不作为验收证据 |

## 2. 宿主集成职责

宿主模块接线见[技术架构](01-技术架构.md#3-宿主边界)；SDK 目标发布单元与依赖限制直接引用 [Harness 02](../../harness-design/02-微内核插件架构实现思路.md#14-agent-runtime-sdk-交付边界)。旧 core-agent/core-chat/core-shared 等物理模块不定义 SDK 公共边界。

宿主负责身份、产品配置、渠道协议、基础设施适配与启动装配，运行控制通过 SDK façade；公开命令、结果与宿主 SPI 见 [Harness 03](../../harness-design/03-核心对象模型与状态机.md#75-agent-runtime-sdk-façade)。

## 3. 固定架构约束

架构约束见 [Harness 02](../../harness-design/02-微内核插件架构实现思路.md)；契约语义与事实源边界见 [Harness 03](../../harness-design/03-核心对象模型与状态机.md)；委派目标、生命周期与恢复见 [Harness 08](../../harness-design/08-Agent委派与多智能体运行时.md)。本文不重复维护约束清单。

## 4. 已废弃的目标描述

以下设计不再代表目标架构：

- Agent Group 或多成员群组作为会话运行时；
- ReAct 快车道与 Workflow 慢车道的门卫分流；
- 无 LLM 时保留旧 Planner 的兼容分支；
- 用户显式选择 Workflow 并绕过 Agent；
- Agent 读取 Workflow 节点、checkpoint 或 Pending Map；
- 多个 Controller、Scheduler 和回调入口分别恢复执行；
- Plan/Step 表天然等同于 Session 多 Execution。

这些内容只可作为历史背景阅读，相关 ADR 状态见
[架构决策索引](../05-架构决策/索引.md)。

## 5. 状态判读

本文只索引目标设计，不声明实现完成度。目标范围见 [Harness](../../harness-design/README.md)，实际行为需核对当前源码和测试；Note 是否需要及何时转态按 [Note 治理规范](../../.agents/skills/nw-docs/references/notes-governance.md)。
