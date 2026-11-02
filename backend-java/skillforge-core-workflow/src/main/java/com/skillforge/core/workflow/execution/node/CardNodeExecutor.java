package com.skillforge.core.workflow.execution.node;

import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 卡片节点执行器（展示卡 type=info，不挂起）：title / body / fields / actions
 * （含 link 跳转 url）递归插值后随消息下发，style 保持原样，流程继续；
 * terminal 时由无出边结束。
 */
public class CardNodeExecutor extends WorkflowNodeExecutor {

    @Override
    protected NodeOutcome doExecute(NodeExecutionContext ctx) {
        WorkflowNode node = ctx.node();
        Map<String, Object> card = WorkflowExpressions.buildInfoCard(
                node, ctx.config(), ctx.state(), ctx.extracted(), ctx.extraVars());
        String cardContent = WorkflowExpressions.interpolate(node.message() == null ? "" : node.message(),
                ctx.config(), ctx.state(), ctx.extracted(), ctx.extraVars());
        Map<String, Object> cardMessage = new LinkedHashMap<>();
        cardMessage.put("role", "assistant");
        cardMessage.put("content", cardContent);
        cardMessage.put("card", card);
        // 渲染数据契约：卡消息携带节点身份与自定义渲染数据（平台原样透传不参与渲染）
        cardMessage.put("node_id", node.id());
        cardMessage.put("node_type", node.type());
        Map<String, Object> render = WorkflowExpressions.buildRender(
                node, ctx.config(), ctx.state(), ctx.extracted(), ctx.extraVars());
        if (render != null) {
            cardMessage.put("render", render);
        }
        String mergeMessageId = WorkflowExpressions.mergeMessageId(
                node, ctx.config(), ctx.state(), ctx.extracted(), ctx.extraVars());
        if (mergeMessageId != null) {
            cardMessage.put("merge_message_id", mergeMessageId);
        }
        ctx.messages().add(cardMessage);
        // 展示卡：卡数据随活动记录（历史渲染数据源），但不挂起流程
        return NodeOutcome.displayCard(card);
    }
}
