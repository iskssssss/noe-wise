package com.skillforge.core.workflow.execution.node;

import java.util.Map;
import java.util.Optional;

/**
 * 节点执行结果。
 *
 * @param card     节点卡数据（挂起卡 / 展示卡）；null 表示无卡
 * @param gotoNode on_error=goto 的跳转目标；null 表示按条件边路由
 * @param waiting  类型化等待原因；展示卡不创建等待
 */
public record NodeOutcome(Map<String, Object> card, String gotoNode, Optional<WaitReason> waiting,
        Optional<WorkflowNodeWait> toolInputWait) {

    public enum WaitReason { HUMAN_INPUT, CALLBACK, CHILD_EXECUTION, AGENT, TOOL_INPUT }

    public NodeOutcome {
        java.util.Objects.requireNonNull(waiting, "waiting");
        java.util.Objects.requireNonNull(toolInputWait, "toolInputWait");
        if (waiting.filter(reason -> reason == WaitReason.TOOL_INPUT).isPresent() != toolInputWait.isPresent())
            throw new IllegalArgumentException("Tool 输入等待必须携带真实节点Run等待");
        if (waiting.isPresent() && gotoNode != null) throw new IllegalArgumentException("等待与跳转互斥");
    }

    public boolean pending() { return waiting.isPresent(); }

    /** 外部等待没有用户卡片，恢复必须命中持久等待引用。 */
    public static NodeOutcome waiting(WaitReason reason) { return new NodeOutcome(null, null, Optional.of(reason), Optional.empty()); }

    public static NodeOutcome toolInput(WorkflowNodeWait wait) {
        return new NodeOutcome(null, null, Optional.of(WaitReason.TOOL_INPUT), Optional.of(wait));
    }

    /** 正常继续：无卡片、无条件跳转。 */
    public static NodeOutcome continueFlow() {
        return new NodeOutcome(null, null, Optional.empty(), Optional.empty());
    }

    /** 需人工确认：携带挂起卡片。 */
    public static NodeOutcome pending(Map<String, Object> card) {
        return new NodeOutcome(card, null, Optional.of(WaitReason.HUMAN_INPUT), Optional.empty());
    }

    /** 展示卡（card 节点）：卡数据随活动记录供历史渲染，但不挂起流程。 */
    public static NodeOutcome displayCard(Map<String, Object> card) {
        return new NodeOutcome(card, null, Optional.empty(), Optional.empty());
    }

    /** on_error=goto 跳转。 */
    public static NodeOutcome jump(String nodeId) {
        return new NodeOutcome(null, nodeId, Optional.empty(), Optional.empty());
    }
}
