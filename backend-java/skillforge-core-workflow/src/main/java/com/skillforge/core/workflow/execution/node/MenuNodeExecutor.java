package com.skillforge.core.workflow.execution.node;

import java.util.Map;

/**
 * 行动点节点执行器（type=select 卡片，挂起）：行动点由节点 actions 生成，
 * 点选后由引擎恢复逻辑按 action id 路由；卡片仅承载行动点，
 * 展示类卡片由 card 节点负责。
 */
public class MenuNodeExecutor extends WorkflowNodeExecutor {

    @Override
    protected NodeOutcome doExecute(NodeExecutionContext ctx) {
        Map<String, Object> card = WorkflowExpressions.buildMenuCard(
                ctx.node(), ctx.config(), ctx.state(), ctx.extracted(), ctx.extraVars());
        return emitPendingCard(ctx, card);
    }
}
