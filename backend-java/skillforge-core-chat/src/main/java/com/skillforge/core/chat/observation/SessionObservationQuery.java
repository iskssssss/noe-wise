package com.skillforge.core.chat.observation;

import java.time.Instant;

/** 查询服务的类型化输入，不直接承担 HTTP 参数绑定。 */
public final class SessionObservationQuery {
    private SessionObservationQuery() {
    }

    public record Sessions(
            Long tenantId,
            String sessionId,
            Long userId,
            Long agentId,
            String teamId,
            String channel,
            String status,
            Instant startedFrom,
            Instant startedTo,
            String summary,
            int page,
            int pageSize) {
    }

    public record Resource(long tenantId, String sessionId) {
    }

    public record Rounds(
            long tenantId, String sessionId, String rootInputId, String cursor, int pageSize) {
    }

    public record Messages(
            long tenantId, String sessionId, String rootInputId, String cursor, int pageSize) {
    }

    public record Trace(
            long tenantId,
            String sessionId,
            String rootInputId,
            String cursor,
            int pageSize,
            String nodeId) {
    }

    public record Content(long tenantId, String sessionId, String rootInputId, String contentRef) {
    }

    public record Usage(
            long tenantId, String sessionId, String rootInputId, String cursor, int pageSize) {
    }
}
