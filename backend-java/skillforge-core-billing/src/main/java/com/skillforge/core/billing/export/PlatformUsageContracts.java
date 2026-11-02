package com.skillforge.core.billing.export;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 平台用量公开契约。金额为供应商精确分字符串，身份和游标为字符串，避免 JS 精度损失。 */
public final class PlatformUsageContracts {
    private PlatformUsageContracts() { }

    /** 原子事实导出；报告是累计修订，确认替代成本，调整只表达成本增减。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name="PlatformUsageEvent")
    public record Event(String cursor, String eventId, String sourceEventId,
            String originKind, String actorRef, String responsibilityRef, String deploymentId,
            String tenantId, String integrationId, String kind,
            @Schema(description="累计报告使用report修订，最终计量使用原usage快照修订；同修订以最终计量为准")
            long revision, String adjustmentOfEventId, String eventType, Integer quantity,
            Long tokens, Long promptTokens, Long completionTokens, String usageSource,
            String amountStatus, String amountCents, String currency,
            String invocationId, String attemptId, String requestId, String rootInputId,
            String agentId, String agentDefinitionVersion, String sessionId, String runId,
            String endUserId, String channelId, String businessScopeId,
            String provider, String model, Instant occurredAt, Instant publishedAt,
            String sourceOwnerKind, String sourceOwnerTenantId, String agentDefinitionId,
            String agentRevisionId, Integer agentRevision, Long deploymentRevision,
            String grantId, Long grantRevision, List<GrantHop> grantChain, String measurementUnit,
            String groupBindingId, String requesterKind, String requesterRef,
            String originSessionId, String originEventId, String responsibilityAgentId, String systemAgentRunId,
            String systemAgentConfigurationRef, String systemAgentInvocationRef,
            String imageCount, String videoCount, String characters, String durationSeconds,
            String pricingModelId, Long pricingModelRevision) {
        public Event { grantChain = List.copyOf(grantChain); }
    }

    /** 原执行冻结的逐跳授权依据，ID保持十进制字符串，不按当前树重建历史链。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name="PlatformUsageGrantHop")
    public record GrantHop(int ordinal, String grantId, long revision, String grantorKind,
            String grantorTenantId, String targetTenantId, String parentGrantId, Long parentGrantRevision,
            String relationRef, Long relationRevision) { }

    /** 游标绑定当前授权和过滤的扫描位置；无匹配事实时也推进，不能按数值解析。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name="PlatformUsagePage")
    public record Page(List<Event> items, String nextCursor, boolean hasMore) { }

    /** 同币种精确金额汇总，空值不会按零填补。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name="PlatformUsageCost")
    public record Cost(String currency, String amountCents) { }

    /** 报告按尝试归并，成本按最新确认加调整计算；请求数与调用数分别展示。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name="PlatformUsageSummary")
    public record Summary(long requestCount, long invocationCount,
            Long promptTokens, Long completionTokens, long unknownUsageCount,
            long unresolvedCostCount, List<Cost> costs) { }
}
