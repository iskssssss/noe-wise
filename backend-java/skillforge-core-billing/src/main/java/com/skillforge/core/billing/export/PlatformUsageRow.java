package com.skillforge.core.billing.export;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;
import java.util.List;

/** 导出引用与不可变源账本连接的查询行；源业务字段不再存入导出表。 */
@Data
public class PlatformUsageRow {
    private String originKind;
    private String actorRef;
    private String responsibilityRef;
    private Long deploymentId;
    private Long deploymentRevision;
    private Long grantId;
    private Long grantRevision;
    private String sourceOwnerKind;
    private Long sourceOwnerTenantId;
    private Long agentDefinitionId;
    private Long agentRevisionId;
    private Integer agentRevision;
    private List<PlatformUsageGrantHop> grantChain=List.of();
    /** 群身份来自实际 Run target 或已冻结后台任务，普通个人调用为空。 */
    private Long groupBindingId;
    /** 原请求者与系统实际执行主体分开保存，格式由持久来源契约定义。 */
    private String requesterKind;
    private String requesterRef;
    /** 后台任务原始输入来源，不能被技术模型会话覆盖。 */
    private String originSessionId;
    private String originEventId;
    /** 后台任务冻结的业务责任实例，实际模型执行者仍由 agentId 表示。 */
    private Long responsibilityAgentId;
    /** 系统用途调用的不可变逻辑 Run，配置与逻辑调用引用由其精确解析。 */
    private Long systemAgentRunId;
    private String systemAgentConfigurationRef;
    private String systemAgentInvocationRef;
    private Long tenantId;
    private Long usageId;
    private String eventId;
    private String sourceEventId;
    private String integrationId;
    private String businessScopeId;
    private String agentDefinitionVersion;
    private Long publicationCursor;
    private Instant publishedAt;
    private String eventType;
    private String usageEventKind;
    private Long reportRevision;
    private Long usageSnapshotRevision;
    private String adjustmentOfEventId;
    private String invocationId;
    private String attemptId;
    private String requestId;
    private String rootInputId;
    private String sessionId;
    private String runId;
    private Long endUserId;
    private String channelId;
    private Long agentId;
    private String provider;
    private String model;
    private Integer quantity;
    private Long pricingModelId;
    private Long pricingModelRevision;
    private Long imageCount;
    private Long videoCount;
    private Long characters;
    private BigDecimal durationSeconds;
    private String measurementUnit;
    private Long tokens;
    private Long promptTokens;
    private Long completionTokens;
    private String usageSource;
    private String amountStatus;
    private BigDecimal amountCents;
    private String currency;
    private BigDecimal exchangeRateToCny;
    private Instant occurredAt;
    private Instant ingestedAt;
}
