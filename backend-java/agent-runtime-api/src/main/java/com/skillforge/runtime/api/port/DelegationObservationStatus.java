package com.skillforge.runtime.api.port;

/** 表示父 Agent Loop 可以消费的四种且仅四种委派决策事实。 */
public enum DelegationObservationStatus {
    /** DelegateAction 在 Delegation Ledger claim 前被确定拒绝。 */
    REJECTED,
    /** 子 Agent 已提交成功终态及公开运行报告。 */
    COMPLETED,
    /** 子 Agent 已提交带稳定原因的失败终态。 */
    FAILED,
    /** 子 Agent 与其活动工作流均已确认取消并收敛。 */
    CANCELLED
}
