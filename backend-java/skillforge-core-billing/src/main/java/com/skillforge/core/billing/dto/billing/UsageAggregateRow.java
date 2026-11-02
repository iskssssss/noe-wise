package com.skillforge.core.billing.dto.billing;

import lombok.Data;

/**
 * 用量事件聚合行（Mapper XML 查询结果，强类型非 Map）。
 *
 * <p>基于 {@code usage_events} 最小单元聚合：calls 为 request 根事件数（配额口径），
 * tokens/金额为 llm 类事件汇总（request 事件不计入，避免双计）。
 */
@Data
public class UsageAggregateRow {

    /** 请求数（request 根事件数）。 */
    private Long calls;
    private String requestId;

    /** 总 token（llm 类事件）。 */
    private Long tokens;

    /** 输入 token（llm.chat）。 */
    private Long promptTokens;

    /** 输出 token（llm.chat）。 */
    private Long completionTokens;

    /** 合计金额（分，llm 类事件）。 */
    private Long totalAmountCents;
    /** 已确认结算成本（元 CNY）；缺口另列，不能当作完整总额。 */
    private String confirmedCost;
    private Long missingCostCount;
    private String usageStatus;
}
