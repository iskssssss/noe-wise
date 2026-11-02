package com.skillforge.core.billing.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import org.apache.ibatis.annotations.Param;

/** 仅查询原模型用量和有效成本，不写价格、发票或归账状态。 */
public interface ModelBillingMapper {
    @Data
    class Summary {
        private long modelCalls;
        private BigDecimal knownAmountCents;
        private long missingCostCount;
        private BigDecimal promptTokens;
        private BigDecimal completionTokens;
    }

    @Data
    class Row {
        private Long id;
        private String eventType, provider, model;
        private Instant occurredAt;
        private String invocationId, attemptId, sessionId, runId;
        private Integer quantity;
        private Long promptTokens, completionTokens, imageCount, videoCount, characters;
        private BigDecimal durationSeconds;
        private String pricingSnapshotVersion;
        private Long pricingModelId, pricingModelRevision;
        private BigDecimal originalAmountCents;
        private String originalCurrency;
        private BigDecimal amountCents;
        private String costStatus;
    }

    Summary summarize(@Param("tenantId") long tenantId, @Param("from") Instant from, @Param("to") Instant to);

    List<Row> list(@Param("tenantId") long tenantId, @Param("from") Instant from, @Param("to") Instant to,
            @Param("offset") long offset, @Param("limit") int limit);
}
