package com.skillforge.core.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 历史会话列表项 DTO。
 *
 * @param sessionId    会话 ID（续聊时回传）
 * @param summary      会话画像摘要，可为空
 * @param messageCount 消息数
 * @param updatedAt    最近更新时间
 * @param agentId      单 Agent 目标，可空
 * @param teamId       Team 目标，可空
 * @param title        首条用户消息文本标题，可为空
 */
public record SessionItemResponse(
        @Schema(description = "会话 ID（续聊时回传）") String sessionId,
        @Schema(description = "会话画像摘要，可为空", nullable = true) String summary,
        @Schema(description = "消息数") Integer messageCount,
        @Schema(description = "最近更新时间") Instant updatedAt,
        @Schema(description = "单 Agent 目标，可空", nullable = true) Long agentId,
        @Schema(description = "Team 目标，可空", nullable = true) String teamId,
        @Schema(description = "首条用户消息文本标题，可为空", nullable = true) String title) {

    /** 保持既有五参数 Java 调用的构造方式。 */
    public SessionItemResponse(
            String sessionId, String summary, Integer messageCount, Instant updatedAt, Long agentId) {
        this(sessionId, summary, messageCount, updatedAt, agentId, null, null);
    }
}
