package com.skillforge.core.billing.dto.billing;

import lombok.Data;

/**
 * 按事件类型分组的用量统计行。
 */
@Data
public class UsageByTypeRow {

    /** 事件类型（request / llm.chat / llm.embedding / llm.rerank / knowledge.source / workflow.external）。 */
    private String eventType;

    /** 事件数。 */
    private Long quantity;

    /** token 汇总（request 不计）。 */
    private Long tokens;

    /** 金额汇总（分）。 */
    private Long totalAmountCents;
    /** 已确认结算成本（元 CNY）；缺口另列，不能当作完整总额。 */
    private String confirmedCost;
    private Long missingCostCount;
    private String usageStatus;
}
