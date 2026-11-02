package com.skillforge.core.billing.application;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Objects;
import lombok.Builder;

/** 计费账本用于轨迹关联的只读事实；不携带持久化主键、原始 metadata 或账本写权。 */
public final class BillingTraceFacts {
    private BillingTraceFacts() { }
    public static final int COST_SCOPE_BATCH_SIZE = 200;

    /** 调用方已逐一授权的实际来源；只同会话或同根输入均不足以纳入某个 Run。 */
    public record ReadScope(String sessionId, String runId, String rootInputId) {
        public ReadScope {
            for (String identity : new String[]{sessionId, runId, rootInputId}) {
                Objects.requireNonNull(identity, "billing read scope identity");
                if (identity.isBlank()) throw new IllegalArgumentException("billing_cost_scope_invalid");
            }
        }
    }

    /**
     * 原 measured 事件的有效成本；对账替换和增减调整已由账本视图投影。
     * confirmedCostCents 为已换算 CNY 的精确分值，未知保持 null，不能按原始币种或整数解释。
     */
    public record EffectiveCost(String eventId, String attemptId, long tenantId,
            String sessionId, String runId, String rootInputId, String costStatus, BigDecimal confirmedCostCents) {
        public String currency() { return "CNY"; }
        public ReadScope scope() { return new ReadScope(sessionId, runId, rootInputId); }
    }

    /** 用量保留原始可空数值与来源；未知值不能被补成零，展示政策由轨迹消费者决定。 */
    @Builder(toBuilder = true)
    public record Usage(
            String eventId,
            String eventType,
            String provider,
            String model,
            String usageSource,
            String estimatorVersion,
            String pricingSnapshotVersion,
            Long tokens,
            Long promptTokens,
            Long completionTokens,
            Long cachedInputTokens,
            Long cacheWriteInputTokens,
            Long reasoningOutputTokens,
            String rootInputId,
            String runId,
            String invocationId,
            String attemptId,
            String callPoint,
            String amountStatus,
            Long totalAmountCents,
            String currency,
            Instant occurredAt,
            Long parentEventId,
            String traceId) {
        public String getEventId() { return eventId; }
        public String getEventType() { return eventType; }
        public String getProvider() { return provider; }
        public String getModel() { return model; }
        public String getUsageSource() { return usageSource; }
        public String getEstimatorVersion() { return estimatorVersion; }
        public String getPricingSnapshotVersion() { return pricingSnapshotVersion; }
        public Long getTokens() { return tokens; }
        public Long getPromptTokens() { return promptTokens; }
        public Long getCompletionTokens() { return completionTokens; }
        public Long getCachedInputTokens() { return cachedInputTokens; }
        public Long getCacheWriteInputTokens() { return cacheWriteInputTokens; }
        public Long getReasoningOutputTokens() { return reasoningOutputTokens; }
        public String getRootInputId() { return rootInputId; }
        public String getRunId() { return runId; }
        public String getInvocationId() { return invocationId; }
        public String getAttemptId() { return attemptId; }
        public String getCallPoint() { return callPoint; }
        public String getAmountStatus() { return amountStatus; }
        public Long getTotalAmountCents() { return totalAmountCents; }
        public String getCurrency() { return currency; }
        public Instant getOccurredAt() { return occurredAt; }
        public Long getParentEventId() { return parentEventId; }
        public String getTraceId() { return traceId; }
    }

    /** 已保存的外部调用尝试；状态与起止时间均保持原账本事实，不推定未知结果。 */
    @Builder(toBuilder = true)
    public record Invocation(
            String invocationId,
            String attemptId,
            String operationType,
            String provider,
            String service,
            String status,
            String traceId,
            String errorCode,
            Instant startedAt,
            Instant endedAt) {
        public String getInvocationId() { return invocationId; }
        public String getAttemptId() { return attemptId; }
        public String getOperationType() { return operationType; }
        public String getProvider() { return provider; }
        public String getService() { return service; }
        public String getStatus() { return status; }
        public String getTraceId() { return traceId; }
        public String getErrorCode() { return errorCode; }
        public Instant getStartedAt() { return startedAt; }
        public Instant getEndedAt() { return endedAt; }
    }

}
