package com.skillforge.runtime.api;

/** 定义 Loop 可持久恢复的挂起原因。 */
public enum LoopSuspendReason {
    /** 等待 Agent 级人工确认。 */
    AGENT_HITL,
    /** 等待 mounted Workflow 的人工确认。 */
    WORKFLOW_HITL,
    /** 等待 Workflow 后台节点；不投影人工确认卡。 */
    WORKFLOW_EXTERNAL,
    /** 等待同一 Tool invocation 的类型化输入响应。 */
    TOOL_INPUT,
    /** 等待子 Agent 委派的持久结果。 */
    DELEGATED
}
