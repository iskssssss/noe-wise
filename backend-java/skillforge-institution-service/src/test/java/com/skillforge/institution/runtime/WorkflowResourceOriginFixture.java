package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.port.RunIdentity;
import org.springframework.beans.factory.ObjectProvider;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 审计单测只替代Workflow来源端口，真实冻结材料验证由WorkflowToolSourceTest覆盖。 */
public final class WorkflowResourceOriginFixture {
    private WorkflowResourceOriginFixture() {}
    public static ObjectProvider<AgentRuntimeWorkflowNodeExecutions> ordinary() {
        var nodes=mock(AgentRuntimeWorkflowNodeExecutions.class);
        when(nodes.resourceOrigin(any(RunIdentity.class))).thenAnswer(call->call.getArgument(0));
        when(nodes.resourceOrigin(any(com.skillforge.runtime.session.spi.ExecutionStore.Run.class))).thenAnswer(call->((com.skillforge.runtime.session.spi.ExecutionStore.Run)call.getArgument(0)).identity());
        return provider(nodes);
    }
    @SuppressWarnings("unchecked")
    public static ObjectProvider<AgentRuntimeWorkflowNodeExecutions> provider(AgentRuntimeWorkflowNodeExecutions nodes) {
        var provider=(ObjectProvider<AgentRuntimeWorkflowNodeExecutions>)mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(nodes);return provider;
    }
}
