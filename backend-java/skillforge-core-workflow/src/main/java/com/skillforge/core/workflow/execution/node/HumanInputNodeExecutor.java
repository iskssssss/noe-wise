package com.skillforge.core.workflow.execution.node;

import java.util.Map;

/**
 * 人工确认节点执行器（HITL 挂起）：按节点 card 配置构建确认卡，
 * 带卡消息下发后返回 pending（等待人工确认）。
 */
public class HumanInputNodeExecutor extends WorkflowNodeExecutor {

    @Override
    protected NodeOutcome doExecute(NodeExecutionContext ctx) {
        Map<String, Object> card = WorkflowExpressions.buildCard(
                ctx.node(), ctx.config(), ctx.state(), ctx.extracted(), ctx.extraVars());
        return emitPendingCard(ctx, card);
    }
}
