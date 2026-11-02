package com.skillforge.core.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 会话详情聚合响应（右侧会话详情面板数据源）。
 *
 * @param session    会话本体
 * @param endUserId  会话绑定的终端用户 ID（字符串保持完整精度）
 * @param targetAgentId 会话绑定的 Agent ID（Agent 已删除时仍保留）
 * @param closed     权威会话记录是否已关闭
 * @param agent      会话关联 Agent（可空：会话可能未绑定 Agent）
 * @param executions 该会话的工作流执行记录（倒序）
 * @param tasks      该会话的运行时任务（挂起/完成，倒序）
 * @param callbacks  该会话执行关联的入站回调注册
 */
public record SessionDetailResponse(
        @Schema(description = "会话本体") Session session,
        @Schema(description = "绑定的终端用户 ID", nullable = true) String endUserId,
        @Schema(description = "绑定的 Agent ID，字符串保持完整精度", nullable = true) String targetAgentId,
        @Schema(description = "会话已关闭，历史保留且不可续聊") boolean closed,
        @Schema(description = "会话关联 Agent（可空）") AgentInfo agent,
        @Schema(description = "工作流执行记录（倒序）") List<ExecutionItem> executions,
        @Schema(description = "运行时任务（倒序）") List<TaskItem> tasks,
        @Schema(description = "回调注册（按执行关联）") List<CallbackItem> callbacks) {

    /** 详情中的会话事实，不包含列表使用的消息标题投影。 */
    public record Session(String sessionId, String summary, Integer messageCount, Instant updatedAt,
            Long agentId, String teamId) {}

    /** Agent 摘要。 */
    public record AgentInfo(
            @Schema(description = "Agent ID") Long id,
            @Schema(description = "Agent key（业务标识）") String agentKey,
            @Schema(description = "Agent 名称") String name,
            @Schema(description = "状态（draft/published/disabled）") String status) {}

    /** 工作流执行摘要。 */
    public record ExecutionItem(
            @Schema(description = "执行 ID") String executionId,
            @Schema(description = "工作流业务 ID") String workflowId,
            @Schema(description = "工作流显示名（模板名，可空）") String workflowName,
            @Schema(description = "执行状态（running/waiting_hitl/completed/loop_terminated 等）") String state,
            @Schema(description = "创建时间") Instant createdAt,
            @Schema(description = "更新时间") Instant updatedAt) {}

    /** 运行时任务摘要。 */
    public record TaskItem(
            @Schema(description = "执行 ID") String executionId,
            @Schema(description = "挂起/执行节点 ID") String nodeId,
            @Schema(description = "任务类型（human_input/menu/async_task）") String taskType,
            @Schema(description = "状态（waiting/completed）") String status,
            @Schema(description = "挂起超时时间（可空）") Instant hitlExpiresAt,
            @Schema(description = "创建时间") Instant createdAt) {}

    /** 回调注册摘要。 */
    public record CallbackItem(
            @Schema(description = "回调 ID（外部可见）") String callbackId,
            @Schema(description = "绑定的执行 ID（可空）") String executionId,
            @Schema(description = "绑定的工作流 ID（可空）") String workflowId,
            @Schema(description = "恢复动作") String action,
            @Schema(description = "状态（active/disabled）") String status,
            @Schema(description = "创建时间") Instant createdAt) {}
}
