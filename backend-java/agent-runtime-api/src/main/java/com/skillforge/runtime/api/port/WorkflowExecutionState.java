package com.skillforge.runtime.api.port;

/** 表示 Workflow Execution Ledger 的封闭状态集合。 */
public enum WorkflowExecutionState {
    QUEUED,
    RUNNING,
    WAITING_HITL,
    WAITING_EXTERNAL,
    RESUMING,
    COMPLETED,
    FAILED,
    LOOP_TERMINATED,
    CANCELLED;

    /** 人工和后台等待共用持久恢复协议，交互投影仍区分原因。 */
    public boolean isWaiting() {
        return this == WAITING_HITL || this == WAITING_EXTERNAL;
    }
}
