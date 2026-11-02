package com.skillforge.core.billing.export;

import java.math.BigDecimal;
import lombok.Data;

/** 按供应商成本币种汇总的内部结果；数量字段各行相同，由应用服务合并为公开契约。 */
@Data
public class PlatformUsageSummaryRow {
    private long requestCount;
    private long invocationCount;
    private Long promptTokens;
    private Long completionTokens;
    private long unknownUsageCount;
    private long unresolvedCostCount;
    private String currency;
    private BigDecimal amountCents;
}
