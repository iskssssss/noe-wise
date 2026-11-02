package com.skillforge.runtime.api;

/** 定义 Loop 非失败终止的稳定预算、策略与持久化原因。 */
public enum LoopTerminationReason {
    /** 检测到没有有效进展的重复循环。 */
    REPEAT_DETECTED,
    /** 业务模型调用次数耗尽；辅助模型额度由独立账本维度管理。 */
    BUSINESS_MODEL_CALL_BUDGET_EXCEEDED,
    /** Workflow 达到最大执行步骤。 */
    WORKFLOW_MAX_STEPS_REACHED,
    /** Workflow 重复访问受保护节点。 */
    WORKFLOW_NODE_REVISIT,
    /** 超出输入 token 预算。 */
    INPUT_TOKEN_BUDGET_EXCEEDED,
    /** 超出输出 token 预算。 */
    OUTPUT_TOKEN_BUDGET_EXCEEDED,
    /** 超出总 token 预算。 */
    TOTAL_TOKEN_BUDGET_EXCEEDED,
    /** 超出工具调用预算。 */
    TOOL_CALL_BUDGET_EXCEEDED,
    /** 超出知识调用预算。 */
    KNOWLEDGE_CALL_BUDGET_EXCEEDED,
    /** 超出 wall-time 预算。 */
    WALL_TIME_BUDGET_EXCEEDED,
    /** Context Policy 明确拒绝继续执行。 */
    CONTEXT_POLICY_DENIED,
    /** 缺少必须的 Context Guard 配置。 */
    CONTEXT_GUARD_NOT_CONFIGURED,
    /** 无法持久化 Context 快照。 */
    CONTEXT_SNAPSHOT_PERSIST_FAILED,
    /** 无法持久化 Knowledge 引用。 */
    KNOWLEDGE_REFERENCE_PERSIST_FAILED
}
