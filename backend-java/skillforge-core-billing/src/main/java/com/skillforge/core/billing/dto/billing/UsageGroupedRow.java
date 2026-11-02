package com.skillforge.core.billing.dto.billing;

import lombok.Data;

/**
 * 维度分组统计行（group_by：agent / end_user / channel / model / event_type）。
 *
 * <p>{@code dimension} 为分组键的字符串形态（如 agent_id、模型名；无值时 "-"）。
 */
@Data
public class UsageGroupedRow {

    /** 分组键值。 */
    private String dimension;

    /** 请求数（request 根事件数）。 */
    private Long calls;

    /** token 汇总。 */
    private Long tokens;

    /** 金额汇总（分）。 */
    private Long totalAmountCents;
    /** 已确认结算成本（元 CNY）；缺口另列，不能当作完整总额。 */
    private String confirmedCost;
    private Long missingCostCount;
    private String usageStatus;
}
