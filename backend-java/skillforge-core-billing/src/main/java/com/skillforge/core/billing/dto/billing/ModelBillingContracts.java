package com.skillforge.core.billing.dto.billing;

import java.time.Instant;
import java.util.List;

/** 月度模型费用直接读取原用量成本；金额为精确元字符串，未知保持空值。 */
public final class ModelBillingContracts {
    private ModelBillingContracts() { }

    public record Monthly(String month, String currency, long modelCalls, String knownAmount, String totalAmount,
            long missingCostCount, String promptTokens, String completionTokens, long total,
            int page, int pageSize, List<Line> items) { }

    public record Line(String id, String eventType, String provider, String model, Instant occurredAt,
            String invocationId, String attemptId, String sessionId, String runId, String quantity,
            String promptTokens, String completionTokens, String imageCount, String videoCount, String characters,
            String durationSeconds, String pricingSnapshotVersion, String pricingModelId, Long pricingModelRevision,
            String originalAmount, String originalCurrency, String amount, String costStatus) { }
}
