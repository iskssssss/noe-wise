package com.skillforge.core.workflow.execution.node;

/** 已确定的节点失败；与外部结果未知严格区分，允许账本提交 FAILED 终态。 */
public final class WorkflowNodeFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String reason;

    public WorkflowNodeFailure(String reason) {
        super(reason);
        if (reason == null || !reason.matches("[a-z][a-z0-9_]{0,127}"))
            throw new IllegalArgumentException("invalid workflow node failure reason");
        this.reason = reason;
    }

    public String reason() { return reason; }
}
