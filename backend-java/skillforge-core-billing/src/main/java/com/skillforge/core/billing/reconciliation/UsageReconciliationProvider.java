package com.skillforge.core.billing.reconciliation;

import com.skillforge.core.billing.metering.AmountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 第三方账单拉取 SPI；实现负责外部认证与游标，核心只接受已校验事实。 */
public interface UsageReconciliationProvider {

    /** 稳定的租户配置选择键；不同供应商不得复用同一键。 */
    default String providerId() {
        return getClass().getName();
    }

    /** 拉取有限时间窗口的一页第三方账单事实。 */
    Batch pull(String tenantId, Instant from, Instant to, String cursor);

    record Batch(List<Fact> facts, String nextCursor) {
        public Batch {
            facts = facts == null ? List.of() : List.copyOf(facts);
            nextCursor = nextCursor == null ? "" : nextCursor;
        }
    }

    /** 与平台 measured event 绑定的外部账单事实。 */
    record Fact(
            String eventId,
            Long originalEventId,
            AmountStatus amountStatus,
            BigDecimal inputAmount,
            BigDecimal outputAmount,
            BigDecimal totalAmount,
            BigDecimal exchangeRateToCny,
            String pricingSnapshotVersion,
            String currency,
            Map<String, Object> metadata) {
        public Fact {
            if (eventId == null || eventId.isBlank()) {
                throw new IllegalArgumentException("reconciliation eventId is required");
            }
            if (originalEventId == null || originalEventId <= 0) {
                throw new IllegalArgumentException("reconciliation originalEventId is required");
            }
            if (amountStatus == null) {
                throw new IllegalArgumentException("reconciliation amountStatus is required");
            }
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
