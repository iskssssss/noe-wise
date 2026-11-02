package com.skillforge.core.chat.session;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * 已冻结的公开会话输出；事件身份和会话序号用于消费与重放，不暴露持久化主键。
 * 最近消息查询的 firstMessage 字段保留消息首次出现的位置，不能替换当前输出的真实序号和时间。
 */
@Builder(toBuilder = true)
public record SessionOutboxEntry(
        Long tenantId,
        String sessionId,
        String runtimeRunId,
        String eventId,
        String inputId,
        String rootInputId,
        String traceId,
        String sourceEventId,
        String causedByEventId,
        Long sessionSequence,
        String eventType,
        Map<String, Object> payload,
        Instant createdAt,
        Long firstMessageSequence,
        Instant firstMessageCreatedAt) {

    public SessionOutboxEntry {
        payload = payload == null ? null : immutableMap(payload);
    }

    public Long getTenantId() { return tenantId; }
    public String getSessionId() { return sessionId; }
    public String getRuntimeRunId() { return runtimeRunId; }
    public String getEventId() { return eventId; }
    public String getInputId() { return inputId; }
    public String getRootInputId() { return rootInputId; }
    public String getTraceId() { return traceId; }
    public String getSourceEventId() { return sourceEventId; }
    public String getCausedByEventId() { return causedByEventId; }
    public Long getSessionSequence() { return sessionSequence; }
    public String getEventType() { return eventType; }
    public Map<String, Object> getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getFirstMessageSequence() { return firstMessageSequence; }
    public Instant getFirstMessageCreatedAt() { return firstMessageCreatedAt; }

    static Map<String, Object> immutableMap(Map<String, Object> source) {
        var copy = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<Object, Object>();
            map.forEach((key, nested) -> copy.put(key, immutableValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(list.stream().map(SessionOutboxEntry::immutableValue).toList());
        }
        return value;
    }
}
