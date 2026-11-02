package com.skillforge.core.billing.dto.billing;

import java.time.Instant;
import java.util.List;

/**
 * 一次请求全链路总流水响应（可细拆为最小单元，N28）。
 *
 * @param requestId 请求根 ID
 * @param occurredAt 请求开始时间
 * @param summary 全链路聚合（LLM 调用次数 / tokens / 金额，由最小单元算出）
 * @param items 最小单元明细列表（展开即细拆）
 */
public record RequestDetailResponse(
        String requestId,
        Instant occurredAt,
        RequestSummary summary,
        List<UsageEventItem> items) {

    /**
     * 全链路聚合摘要。
     *
     * @param llmCalls LLM 调用次数
     * @param tokens   token 汇总
     * @param promptTokens 输入 token 汇总
     * @param completionTokens 输出 token 汇总
     * @param totalAmountCents 金额汇总（分）
     * @param currency 币种
     */
    public record RequestSummary(
            long llmCalls,
            long tokens,
            long promptTokens,
            long completionTokens,
            Long totalAmountCents,
            String currency, String confirmedCost, long missingCostCount, String usageStatus) {
    }
}
