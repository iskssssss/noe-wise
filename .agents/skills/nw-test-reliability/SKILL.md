---
name: nw-test-reliability
description: 测试隔离、子进程配置、Worker 归属、异步清理和偶发 CI 失败；测试涉及这些风险时使用。
---

# 测试可靠性

只读取[测试可靠性正文](references/test-reliability.md)中当前风险对应的章节，不因存在 CI 就扩大验证。

| 风险 | 章节 |
|---|---|
| 端口、文件、数据库、容器或全局状态 | [隔离资源](references/test-reliability.md#隔离资源与全局状态) |
| 新增或改变子进程/长链路测试 | [装配预检](references/test-reliability.md#子进程与长链路预检)；事实已证实时复用，不每批预检 |
| readiness、竞争、时钟或清理 | [等待与清理](references/test-reliability.md#等待与清理信号) |
| 共享设施、执行窗口或未知结果 | [设施与窗口](references/test-reliability.md#设施复用与执行窗口) |
| 跨平台差异 | [平台差异](references/test-reliability.md#平台差异) |
| 概率失败或 CI flake | [诊断与证明](references/test-reliability.md#诊断与证明) |

本技能拥有测试资源和同步方法；检查范围、失败分类和证据复用由[验证规范](../nw-verification/references/candidate-driven-delivery.md)维护。
