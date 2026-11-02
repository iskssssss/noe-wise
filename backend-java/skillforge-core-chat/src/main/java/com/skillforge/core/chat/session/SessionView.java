package com.skillforge.core.chat.session;

import java.time.Instant;
import java.util.Map;
import lombok.Builder;

/** 会话领域只读事实；不携带持久化主键、执行指针或可写实体能力。 */
@Builder(toBuilder = true)
public record SessionView(
        String sessionId,
        Long tenantId,
        Long endUserId,
        Long groupBindingId,
        Long channelId,
        String scopeId,
        String scopeType,
        String originKind,
        String actorRef,
        String responsibilityRef,
        String integrationId,
        String businessScopeId,
        Long agentId,
        String teamId,
        Integer messageCount,
        String summaryText,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt) {
    public SessionView {
        metadata = metadata == null ? null : immutableMap(metadata);
    }

    public String getSessionId() { return sessionId; }
    public Long getTenantId() { return tenantId; }
    public Long getEndUserId() { return endUserId; }
    public Long getGroupBindingId() { return groupBindingId; }
    public Long getChannelId() { return channelId; }
    public String getScopeId() { return scopeId; }
    public String getScopeType() { return scopeType; }
    public String getOriginKind() { return originKind; }
    public String getActorRef() { return actorRef; }
    public String getResponsibilityRef() { return responsibilityRef; }
    public String getIntegrationId() { return integrationId; }
    public String getBusinessScopeId() { return businessScopeId; }
    public Long getAgentId() { return agentId; }
    public String getTeamId() { return teamId; }
    public Integer getMessageCount() { return messageCount; }
    public String getSummaryText() { return summaryText; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        var copy = new java.util.LinkedHashMap<String, Object>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new java.util.LinkedHashMap<Object, Object>();
            map.forEach((key, nested) -> copy.put(key, immutableValue(nested)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.List<?> list) {
            return java.util.Collections.unmodifiableList(list.stream().map(SessionView::immutableValue).toList());
        }
        return value;
    }
}
