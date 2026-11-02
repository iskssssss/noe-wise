package com.skillforge.core.workflow.execution.node;

import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工作流节点执行器（模板方法模式）。
 *
 * <p>每种节点类型一个子类，由引擎注册表按 {@link WorkflowNode#type()} 分派。
 * 模板方法 {@link #execute} 固定执行骨架（前后日志三明治），业务差异点由子类
 * {@link #doExecute} 实现；工具/HTTP 共用的 on_error 三分支与挂起节点共用的
 * 带卡消息逻辑下沉为 protected 助手，子类组合复用。
 *
 * <p>执行器由引擎内部实例化（非 Spring bean），null 依赖语义与迁移前一致：
 * 各子类按注入依赖是否为空决定报错或降级（如 http 未注入抛 ApiException、
 * agentic 未注入返回 unavailable）。
 */
public abstract class WorkflowNodeExecutor {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(WorkflowNodeExecutor.class);

    /**
     * 模板方法：执行节点。
     *
     * <p>骨架固定（执行前/后日志三明治），不引入空钩子——各节点类型的真实公共
     * 步骤已在 {@link #handleOnError} / {@link #emitPendingCard} 等助手中收敛，
     * 其余差异点全部由子类承担。
     *
     * @param ctx 节点执行上下文
     * @return 节点执行结果
     */
    public final NodeOutcome execute(NodeExecutionContext ctx) {
        log.info("执行工作流节点 workflow={} node={} type={}",
                ctx.workflow().key(), ctx.node().id(), ctx.node().type());
        NodeOutcome outcome = doExecute(ctx);
        log.info("工作流节点执行完成 workflow={} node={} pending={} gotoNode={}",
                ctx.workflow().key(), ctx.node().id(), outcome.pending(), outcome.gotoNode());
        return outcome;
    }

    /**
     * 子类实现节点业务主体。
     *
     * @param ctx 节点执行上下文
     * @return 节点执行结果
     */
    protected abstract NodeOutcome doExecute(NodeExecutionContext ctx);

    /**
     * tool / http 节点共享的失败处理（on_error 三分支）。
     *
     * <p>fail（默认）原样上抛原始异常（类型与对象不变，由引擎主循环记录 failed
     * 活动后重新抛出）；goto:&lt;id&gt; 写 {@code state.last_error} 后跳转；
     * message 追加错误消息结束。
     *
     * @param ctx            节点执行上下文
     * @param kind           失败方名称（日志前缀，如「工具」「HTTP 节点」）
     * @param failureDetail  失败消息文案前缀（追加异常信息，如「工具「X」调用失败: 」）
     * @param e              捕获的异常
     * @return 处理结果：message 分支返回 continueFlow，goto 分支返回 jump；
     *         fail 分支不返回（原样上抛）
     */
    protected NodeOutcome handleOnError(NodeExecutionContext ctx, String kind, String failureDetail, Exception e) {
        WorkflowNode node = ctx.node();
        String onError = node.onError() == null ? "fail" : node.onError();
        if ("message".equals(onError)) {
            log.warn("{}调用失败（on_error=message） workflow={} node={}: {}",
                    kind, ctx.workflow().key(), node.id(), e.getMessage());
            ctx.messages().add(Map.of("role", "assistant",
                    "content", failureDetail + e.getMessage()));
            return NodeOutcome.continueFlow();
        }
        if (onError != null && onError.startsWith("goto:")) {
            log.warn("{}调用失败（on_error=goto） workflow={} node={}: {}",
                    kind, ctx.workflow().key(), node.id(), e.getMessage());
            ctx.state().put("last_error", String.valueOf(e.getMessage()));
            return NodeOutcome.jump(onError.substring("goto:".length()).trim());
        }
        // fail（默认策略）：原样上抛（sneaky throw 保持异常类型与对象不变）
        throw rethrow(e);
    }

    /**
     * human_input / menu 节点共享的挂起处理：prompt 插值生成带卡消息并返回 pending。
     *
     * @param ctx  节点执行上下文
     * @param card 挂起卡片（由子类按节点类型构建）
     * @return pending 结果（随卡挂起，等待 HITL 响应）
     */
    protected NodeOutcome emitPendingCard(NodeExecutionContext ctx, Map<String, Object> card) {
        WorkflowNode node = ctx.node();
        // 无默认值：prompt 未配置则不显示消息文案
        String prompt = node.prompt() == null ? "" : node.prompt();
        // 挂起卡随消息（上层持久化时据此识别卡消息并关联活动 message_id）
        Map<String, Object> message = WorkflowExpressions.cardMessage(
                prompt, ctx.config(), ctx.state(), ctx.extracted(), ctx.extraVars(), card);
        // 渲染数据契约：卡消息携带节点身份与自定义渲染数据（平台原样透传不参与渲染）
        message.put("node_id", node.id());
        message.put("node_type", node.type());
        Map<String, Object> render = WorkflowExpressions.buildRender(
                node, ctx.config(), ctx.state(), ctx.extracted(), ctx.extraVars());
        if (render != null) {
            message.put("render", render);
        }
        String mergeMessageId = WorkflowExpressions.mergeMessageId(
                node, ctx.config(), ctx.state(), ctx.extracted(), ctx.extraVars());
        if (mergeMessageId != null) {
            message.put("merge_message_id", mergeMessageId);
        }
        ctx.messages().add(message);
        return NodeOutcome.pending(card);
    }

    /**
     * 原样上抛任意异常（不改变异常类型与对象）。
     *
     * <p>catch(Exception) 内需要精确重抛原始异常（引擎主循环与上层按类型处理）时使用；
     * 方法声明返回 {@link RuntimeException} 使调用点无需声明受检异常，运行时抛出的
     * 仍是传入的原始异常实例。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
