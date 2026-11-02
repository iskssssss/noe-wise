---
name: nw-verification
description: 风险分级、测试选择、证据复用与交付核验；验收代码、规则或远程交付时使用。
---

# 验证

从[风险路由](references/candidate-driven-delivery.md#风险路由)选择最低充分证据，再读取对应章节；达到约定完成标准即停止。

| 当前需要 | 读取 |
|---|---|
| 选择测试与夹具 | [测试路由](references/candidate-driven-delivery.md#测试路由)、涉及装配时读[测试夹具](references/candidate-driven-delivery.md#测试夹具) |
| 代码评审 | [评审技能](../nw-code-review/SKILL.md) |
| 失败分类 | [失败处理](references/candidate-driven-delivery.md#失败处理)；需要深入定位再读[排障](../nw-debugging/SKILL.md) |
| 测试资源、子进程、时钟或 teardown | [测试可靠性](../nw-test-reliability/SKILL.md) |
| 规则维护 | [规则落地与场景核对](references/candidate-driven-delivery.md#自动检查与规则落地) |
| 证据复用 | [复用条件](references/candidate-driven-delivery.md#证据复用与-verification-manifest)；符合[启用条件](references/candidate-driven-delivery.md#启用条件)才读 manifest |
| 并行、远程交付或集成 | 对应的[隔离](references/candidate-driven-delivery.md#writer-隔离)、[交付差量](references/candidate-driven-delivery.md#交付前差量)或[组合验收](references/candidate-driven-delivery.md#候选集成与业务闭环) |

性能任务使用[性能技能](../nw-performance/SKILL.md)。普通测试不附加性能基准、独立评审或受控记录。
