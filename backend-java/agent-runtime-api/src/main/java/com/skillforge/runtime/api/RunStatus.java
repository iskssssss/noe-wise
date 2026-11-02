package com.skillforge.runtime.api;

/** 定义 façade 可查询的持久运行状态。 */
public enum RunStatus {
    /** 已存在可领取的持久排队事实。 */
    QUEUED,
    /** AgentRun 正在执行。 */
    RUNNING,
    /** AgentRun 已持久挂起。 */
    SUSPENDED,
    /** 恢复 CAS 已占有且正在恢复。 */
    RESUMING,
    /** AgentRun 已正常完成。 */
    COMPLETED,
    /** Loop 已按预算或策略原因终止。 */
    TERMINATED,
    /** AgentRun 因编排或技术故障失败。 */
    FAILED,
    /** AgentRun 已按取消请求收敛。 */
    CANCELLED
}
