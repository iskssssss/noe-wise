package com.skillforge.core.workflow.execution.node;

import com.skillforge.core.workflow.execution.WorkflowHistoryMessage;
import com.skillforge.core.workflow.execution.WorkflowModels.Workflow;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowEdge;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import java.util.List;
import java.util.Map;

/**
 * 节点执行上下文：单节点执行所需的全部运行期数据。
 *
 * <p>由状态机引擎在每次节点执行前组装，经 {@link WorkflowNodeExecutor#execute} 传入；
 * state / extracted / messages 为共享容器，执行器直接读写即生效。
 */
public record NodeExecutionContext(
        Workflow workflow,
        WorkflowNode node,
        Map<String, Object> config,
        Map<String, Object> state,
        Map<String, Object> extracted,
        String userInput,
        List<Map<String, Object>> messages,
        String workflowInstanceId,
        String tenantId,
        List<WorkflowHistoryMessage> history,
        String executionId,
        Long runId,
        String sessionId,
        Map<String, Object> extraVars,
        WorkflowEdge incomingEdge,
        NodeCallIdentity callIdentity) {
}
