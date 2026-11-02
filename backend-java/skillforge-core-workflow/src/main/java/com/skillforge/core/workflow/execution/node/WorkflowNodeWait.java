package com.skillforge.core.workflow.execution.node;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.port.ActionRef;
import java.util.Objects;

/** Workflow只引用真实节点Run的原工具等待；响应受理仍由同一个ExecutionStore拥有。 */
public record WorkflowNodeWait(RunRef nodeRun,ActionRef action,long generation,ResultRef sourceResult) {
    public WorkflowNodeWait {
        Objects.requireNonNull(nodeRun);Objects.requireNonNull(action);Objects.requireNonNull(sourceResult);
        if(generation<=0||!nodeRun.tenantId().equals(action.tenantId()))throw new IllegalArgumentException("workflow_node_wait_invalid");
    }
}
