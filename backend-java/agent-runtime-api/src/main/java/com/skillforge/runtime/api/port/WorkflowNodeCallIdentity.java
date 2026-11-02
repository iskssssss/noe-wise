package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.ExecutionRef;
import java.util.Objects;

/** Workflow 节点逻辑调用身份；只有持久准备来源可证明它已获准执行。 */
public record WorkflowNodeCallIdentity(RunIdentity owner, ExecutionRef executionRef,
        String nodeId, int visit, int call) {
    public WorkflowNodeCallIdentity {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(executionRef, "executionRef");
        if (nodeId == null || nodeId.isBlank() || visit <= 0 || call <= 0
                || !executionRef.tenantId().equals(owner.runRef().tenantId()))
            throw new IllegalArgumentException("invalid workflow node call identity");
    }
}
