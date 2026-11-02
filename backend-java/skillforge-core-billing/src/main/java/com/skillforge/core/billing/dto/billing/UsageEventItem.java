package com.skillforge.core.billing.dto.billing;

import java.time.Instant;
import java.math.BigDecimal;

/**
 * 用量事件明细项（usage_events 最小单元，N28）。
 *
 * @param id 事件 ID
 * @param eventType 事件类型（request / llm.chat / llm.embedding / llm.rerank / knowledge.source / workflow.external）
 * @param provider AI 服务商标识
 * @param model 模型名
 * @param quantity 计量单位数
 * @param tokens 总 token（llm 类）
 * @param promptTokens 输入 token
 * @param completionTokens 输出 token
 * @param cachedInputTokens 输入 token 中命中缓存的子集
 * @param cacheWriteInputTokens 输入 token 中写入缓存的子集
 * @param reasoningOutputTokens 输出 token 中模型推理的子集
 * @param usageSource token 来源（PROVIDER_REPORTED / ESTIMATED / UNKNOWN）
 * @param estimatorVersion 平台估算规则版本；非估算时为空
 * @param inputAmountCents 输入金额（分）
 * @param outputAmountCents 输出金额（分）
 * @param totalAmountCents 合计金额（分）
 * @param currency 币种
 * @param inputAmount 输入精确金额（分）
 * @param outputAmount 输出精确金额（分）
 * @param totalAmount 合计精确金额（分）
 * @param exchangeRateToCny 写时汇率快照
 * @param pricingSnapshotVersion 写时计价快照版本
 * @param usageEventKind 事实类型（measured / reconciliation / adjustment）
 * @param amountStatus 金额状态（priced / unpriced / unresolved）
 * @param adjustmentOfEventId 对账/补偿引用的原始事件 ID
 * @param eventId 业务幂等 identifier
 * @param requestId 请求根 ID（全链路归组键）
 * @param rootInputId 权威根 Input ID
 * @param parentEventId 父子引用（内层调用指向外层事件）
 * @param sessionId 会话 ID
 * @param endUserId C 端用户 ID
 * @param channelId 渠道 ID
 * @param agentId Agent ID
 * @param runId Agent Runtime Run ID
 * @param workflowInstanceId 工作流实例 ID
 * @param executionId Runtime Execution ID
 * @param scopeId 三方透传实体标识
 * @param scopeType 三方透传类型
 * @param callPoint 调用点枚举
 * @param occurredAt 事件时间
 * @param createdAt 记录创建时间
 */
public record UsageEventItem(
        Long id,
        String eventType,
        String provider,
        String model,
        Integer quantity,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long tokens,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long promptTokens,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long completionTokens,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long cachedInputTokens,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long cacheWriteInputTokens,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long reasoningOutputTokens,
        String usageSource,
        String estimatorVersion,
        Long inputAmountCents,
        Long outputAmountCents,
        Long totalAmountCents,
        String currency,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal inputAmount,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal outputAmount,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal totalAmount,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal exchangeRateToCny,
        String pricingSnapshotVersion,
        String usageEventKind,
        String amountStatus,
        Long adjustmentOfEventId,
        String eventId,
        String requestId,
        String rootInputId,
        Long parentEventId,
        String sessionId,
        Long endUserId,
        String channelId,
        Long agentId,
        String runId,
        Long workflowInstanceId,
        String executionId,
        String scopeId,
        String scopeType,
        String callPoint,
        Instant occurredAt,
        Instant createdAt,
        String imageCount, String videoCount, String characters, String durationSeconds,
        String pricingModelId, Long pricingModelRevision) {
}
