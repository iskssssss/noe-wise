---
name: nw-engineering
description: 项目工程规范入口，涵盖架构、编码、依赖、接口、安全、并发和前端；设计、实现或检查代码时按改动加载。
---

# 工程

先读[工程核心要求](references/engineering-standards.md#工程核心要求)，再查实际变化对应章节及所属 Harness/docs 契约。下表是规范索引，不是每次任务必读或必检清单。

| 实际涉及 | 读取 |
|---|---|
| 模块、类型与依赖 | [模块边界](references/engineering-standards.md#模块与职责边界)、[接口与共享](references/engineering-standards.md#接口依赖与共享代码)、[数据归属](references/engineering-standards.md#数据模型归属)；Java 目录和类型组织需要时读[专题](references/java-organization-standards.md) |
| 固定结构、JSON 必要性或字段名维护 | [固定结构与 JSON 边界](references/engineering-standards.md#固定结构与-json-边界)；持久化另按[数据库技能](../nw-database/SKILL.md) |
| 外部协议、SDK/API 或框架关键映射行为 | [官方依据与版本核对](references/engineering-standards.md#依赖构建与生成代码) |
| 命名、类型、函数或可变数据 | [代码实现](references/engineering-standards.md#代码实现) |
| 常量、魔法值或配置 | [静态定义与配置](references/engineering-standards.md#静态定义与配置) |
| 中文注释与实现说明 | [代码注释](references/engineering-standards.md#代码注释) |
| 依赖、构建、格式或生成代码 | [依赖与构建](references/engineering-standards.md#依赖构建与生成代码) |
| HTTP、序列化、字段或分页 | [API 与错误](references/engineering-standards.md#api-与错误契约) |
| 身份、租户或凭据 | [权限](references/engineering-standards.md#身份权限与租户)、[凭据](references/engineering-standards.md#凭据边界) |
| 文件、上传、出站请求或内容渲染 | [输入与内容安全](references/engineering-standards.md#文件网络与内容安全) |
| 外部调用、超时或容量 | [外部调用](references/engineering-standards.md#外部调用超时与重试)；需要具体例子再读[防御模式](references/defensive-patterns.md) |
| 并发、异步、取消或资源释放 | [资源生命周期](references/engineering-standards.md#并发异步与资源生命周期) |
| 事务、日志、审计或指标 | [事务与观测](references/engineering-standards.md#事务日志与可观测性) |
| 数据字段、SQL、MyBatis 或迁移 | [数据库技能](../nw-database/SKILL.md) |
| 前端请求、表单或缓存 | [前端状态](references/engineering-standards.md#前端状态与接口消费) |
| 页面布局、反馈或弹层交互 | [UI 与交互](../nw-ui-ux/SKILL.md) |
| 模型可见的工具、提示词或上下文 | [模型使用体验](../nw-agent-experience/SKILL.md) |
| 错误传播、降级、重试或恢复 | [错误处理](../nw-error-handling/SKILL.md) |
| 性能、负载或已有预算 | [性能技能](../nw-performance/SKILL.md) |
| 测试设计、回归或验收 | [验证技能](../nw-verification/SKILL.md)；涉及异步夹具或共享资源再读[测试可靠性](../nw-test-reliability/SKILL.md) |
| 设计同步、文档或长期决策 | [文档技能](../nw-docs/SKILL.md) |
| Git 交付、发布或环境操作 | [交付授权](../nw-workflow/references/development-workflow.md#阶段交付与授权)与[部署指南](../../../docs/04-指南与运维/01-开发与部署指南.md) |

专项清理转到[简化技能](../nw-simplification/SKILL.md)。只读相关要求，普通实现不触发全仓审计。
