package com.skillforge.core.chat.session;

import java.time.Instant;

/** 历史会话目录事实，只包含列表展示与目标恢复所需字段。 */
public record SessionDirectoryItem(
        String sessionId,
        String summaryText,
        Integer messageCount,
        Instant updatedAt,
        Long agentId,
        String teamId,
        String title) {}
