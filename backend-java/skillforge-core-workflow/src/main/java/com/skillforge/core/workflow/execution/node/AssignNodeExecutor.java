package com.skillforge.core.workflow.execution.node;

import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;

/**
 * 赋值节点执行器：args 为 target → source 映射，逐个解析写入 state；
 * 含 ${} 的 source 按模板插值（支持复合表达式），否则按字面量/路径解析。
 */
public class AssignNodeExecutor extends WorkflowNodeExecutor {

    @Override
    protected NodeOutcome doExecute(NodeExecutionContext ctx) {
        WorkflowNode node = ctx.node();
        if (node.args() != null) {
            node.args().forEach((target, source) -> {
                if (target != null && source != null) {
                    String sourceText = String.valueOf(source);
                    Object value = sourceText.contains("${")
                            ? WorkflowExpressions.interpolate(sourceText, ctx.config(),
                                    ctx.state(), ctx.extracted(), ctx.extraVars())
                            : WorkflowExpressions.resolve(sourceText, ctx.config(),
                                    ctx.state(), ctx.extracted(), ctx.extraVars());
                    ctx.state().put(target, value);
                }
            });
        }
        return NodeOutcome.continueFlow();
    }
}
