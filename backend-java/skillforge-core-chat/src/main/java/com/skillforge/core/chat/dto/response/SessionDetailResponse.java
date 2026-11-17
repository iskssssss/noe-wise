package com.skillforge.core.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 会话详情聚合响应（右侧会话详情面板数据源）。
 *
 * @param session    会话本体
 * @param endUserId  会话绑定的终端用户 ID（字符串保持完整精度）
 * @param targetAgentId 会话绑定的 Agent ID（Agent 已删除时仍保留）
 * @param closed     权威会话记录是否已关闭
 * @param agent      会话关联 Agent（可空：会话可能未绑定 Agent）
 */
public record SessionDetailResponse(
        @Schema(description = "会话本体") Session session,
        @Schema(description = "绑定的终端用户 ID", nullable = true) String endUserId,
        @Schema(description = "绑定的 Agent ID，字符串保持完整精度", nullable = true) String targetAgentId,
        @Schema(description = "会话已关闭，历史保留且不可续聊") boolean closed,
        @Schema(description = "会话关联 Agent（可空）") AgentInfo agent) {

    /** 详情中的会话事实，不包含列表使用的消息标题投影。 */
    public record Session(String sessionId, String summary, @Schema(description = "已受理的会话请求次数，不包含助手回复") Integer messageCount, Instant updatedAt,
            Long agentId, String teamId) {}

    /** Agent 摘要。 */
    public record AgentInfo(
            @Schema(description = "Agent ID") Long id,
            @Schema(description = "Agent key（业务标识）") String agentKey,
            @Schema(description = "Agent 名称") String name,
            @Schema(description = "状态（draft/published/disabled）") String status) {}

}
