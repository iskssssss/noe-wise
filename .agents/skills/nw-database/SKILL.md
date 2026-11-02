---
name: nw-database
description: 数据建模、字段精度、MyBatis、SQL 和迁移规则；修改持久化模型或数据访问时使用。
---

# 数据库

定位所属领域契约，再按改动读[字段规范](references/database-modeling-standards.md)和[数据库与迁移](../nw-engineering/references/engineering-standards.md#数据库与迁移)。

| 变化 | 字段规范章节 |
|---|---|
| 新对象、身份或字段 | §2 模型归属、§3 命名与完整定义 |
| 类型、长度、空值、时间或 JSON | §4 类型与精度 |
| 公共字段或 PATCH | §5 可信写入方、§6 部分更新 |
| 租户、约束、并发、索引或保留 | §7 隔离与生命周期 |
| 实施与验收范围 | §8 实施与验收 |

项目不兼容历史实现的原则不自动授权销毁数据或重建环境。真实数据库、Flyway、事务与受限角色/RLS 的验证按[测试路由](../nw-verification/references/candidate-driven-delivery.md#测试路由)选择，不把建模讨论当成实际迁移。
