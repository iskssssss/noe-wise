package com.skillforge.core.workflow.execution;

/** 工作流执行结果；等待与循环终止不依赖聊天接口状态。 */
public enum WorkflowExecutionStatus {
    COMPLETED("completed"), WAITING_HITL("waiting_hitl"),
    WAITING_EXTERNAL("waiting_external"), LOOP_TERMINATED("loop_terminated");

    private final String code;
    WorkflowExecutionStatus(String code) { this.code = code; }
    public String code() { return code; }
}
