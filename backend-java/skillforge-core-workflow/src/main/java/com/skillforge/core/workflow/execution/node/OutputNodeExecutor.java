package com.skillforge.core.workflow.execution.node;

import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import java.util.Map;

/**
 * 输出消息节点执行器：message 插值（缺省「处理完成」）后追加助手消息。
 */
public class OutputNodeExecutor extends WorkflowNodeExecutor {

    @Override
    protected NodeOutcome doExecute(NodeExecutionContext ctx) {
        WorkflowNode node = ctx.node();
        String content = WorkflowExpressions.interpolate(
                node.message() == null ? "处理完成" : node.message(),
                ctx.config(), ctx.state(), ctx.extracted(), ctx.extraVars());
        ctx.messages().add(Map.of("role", "assistant", "content", content));
        return NodeOutcome.continueFlow();
    }
}
