package com.skillforge.core.workflow.execution.node;

/** 一次逻辑节点调用的稳定身份；恢复和重试不能提高访问或调用序号。 */
public record NodeCallIdentity(String executionId, String nodeId, int visit, int call) {
    public NodeCallIdentity {
        if (executionId == null || executionId.isBlank() || nodeId == null || nodeId.isBlank() || visit <= 0 || call <= 0)
            throw new IllegalArgumentException("invalid workflow node call identity");
    }
}
