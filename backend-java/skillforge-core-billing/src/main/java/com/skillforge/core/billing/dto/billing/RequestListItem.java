package com.skillforge.core.billing.dto.billing;

import java.time.Instant;

/**
 * 请求列表项（request 根事件 + 全链路聚合，N28）。
 *
 * @param requestId 请求根 ID
 * @param occurredAt 请求开始时间
 * @param sessionId 会话 ID
 * @param endUserId C 端用户 ID
 * @param channelId 渠道 ID
 * @param agentId Agent ID
 * @param runId Agent Runtime Run ID
 * @param scopeId 三方透传实体标识
 * @param scopeType 三方透传类型
 * @param llmCalls 该请求全链路 LLM 调用次数（最小单元数）
 * @param tokens 该请求全链路 token 汇总
 * @param totalAmountCents 该请求全链路金额汇总（分）
 * @param currency 币种
 */
public record RequestListItem(
        String requestId,
        Instant occurredAt,
        String sessionId,
        Long endUserId,
        String channelId,
        Long agentId,
        String runId,
        String scopeId,
        String scopeType,
        long llmCalls,
        long tokens,
        Long totalAmountCents,
        String currency, String confirmedCost, long missingCostCount, String usageStatus) {
}
