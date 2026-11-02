package com.skillforge.core.chat.trajectory;

import java.time.Instant;

/** 轨迹检索条件；授权机构和会话范围另由当前身份生成。 */
public record TrajectoryListQuery(
        Long tenantId, String traceId, String sessionId, Long agentId,
        String model, String tool, String status, Instant startedFrom, Instant startedTo,
        Long durationMinMs, Long durationMaxMs, String inputKeyword, String outputKeyword,
        int page, int pageSize) { }
