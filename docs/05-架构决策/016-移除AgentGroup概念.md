# ADR-016：移除 Agent Group 概念

- **状态**：Accepted
- **日期**：2026-08-14
- **适用范围**：删除 Agent Group 产品与运行时概念
- **后续基线**：[Harness Design](../../harness-design/README.md)

## 背景

Agent Group 没有独立产品入口，却把多成员、计划、执行、计费和恢复耦合到单 Agent 会话。该抽象增加数据与运行时复杂度，并与 Agent 作为会话唯一归属的方向冲突。

## 决策

- 删除 Agent Group 模板、实例、成员和渠道绑定概念。
- 不再新增 group 级路由、权限、配额、记忆或执行语义。
- 单个 Session 绑定一个 Agent。
- 复杂任务由一个 Agent 挂载的能力、多 Execution 和 P2 主从委派承担。
- 历史 Group 表、字段和值不构成当前目标 schema；按 MVP-0 里程碑删除或一次性转换。

## 澄清

本 ADR 只确认“移除 Agent Group”。原决策中关于 ReAct/Planner 双轨、旧运行时表保留和旧恢复链路的描述已由
Agent 与 Workflow 的当前运行边界由 Harness Design 取代。

## 验证

- 当前设计文档不再把 Agent Group 作为能力、入口或数据模型。
- 架构规则禁止新增 group 运行时依赖。
- P2 委派使用 DelegationOrder，不恢复群组模型。
