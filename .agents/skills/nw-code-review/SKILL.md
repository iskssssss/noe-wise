---
name: nw-code-review
description: 审查代码差量或处理评审意见；聚焦契约、实际消费者、权限、生命周期与验证证据。
---

# 代码评审

先确认任务范围、当前差量和实际基线；远程 PR 使用当前 base/head，本地评审包含相关未提交输入。读取变更所涉及的工程章节，不遍历无关专题。

涉及界面、模型输入输出或失败恢复时，分别参考 [UI 交互](../nw-ui-ux/SKILL.md)、[模型使用体验](../nw-agent-experience/SKILL.md)或[错误处理](../nw-error-handling/SKILL.md)的相关要求。

## 评审规则

同轮审查差量及直接影响：先核对需求是否遗漏、多做或误解，再按[工程规范](../nw-engineering/references/engineering-standards.md)相关章节检查架构、契约与实现。按变化覆盖接口两端、权限拒绝前的副作用、异步/取消/清理所有者、缓存与持久事实、输出完整性及脱敏；动态装配和可见行为查真实入口，prompt、Tool schema 和结果按模型可见变化判断。[常量与配置](../nw-engineering/references/engineering-standards.md#静态定义与配置)、注释与日志并入本轮，不另设轮次。

发现须有位置、触发、影响、依据及最小修正。实现后集中输出并按模块批次修复，设计阻塞提前核对；修复后只复核差量与直接影响，不因换 Reviewer 重审。个人风格、无证据推测和未来需求不构成阻断，达到[完成标准](../nw-workflow/references/development-workflow.md#完成标准)即停止。

收到意见先查实际入口、调用方与契约：成立则修复，不成立说明依据；新增需求或架构冲突按[关键取舍](../nw-workflow/references/development-workflow.md#自主执行边界)处理，不为意见恢复历史兼容。信息不足补最小检查，仅暂停依赖修改；执行者或 Reviewer 自述均不代替证据。

区分未审查、复用与本次审查；需要受控证据时才建 review.json，不伪造零 finding。明确要求独立验收的候选须由不同实现者和验收者完成，验收者自行核实代码及原始证据；暂缺验收者则保留待验收，解释与自测不能替代独立结论。
