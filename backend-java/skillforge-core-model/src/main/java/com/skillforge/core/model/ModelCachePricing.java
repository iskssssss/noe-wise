package com.skillforge.core.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.billing.pricing.CacheCostPrice;
import com.skillforge.core.billing.pricing.CacheCostPrice.Mode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 管理员明确配置的缓存输入成本；写入单价是该桶完整价格，null 保留未知。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelCachePricing(Mode mode,
        @Schema(type = "string", pattern = ModelPriceJson.PATTERN, nullable = true, description = ModelPriceJson.DESCRIPTION)
        @ModelPriceJson BigDecimal readPricePerMillionCents,
        @Schema(type = "string", pattern = ModelPriceJson.PATTERN, nullable = true, description = ModelPriceJson.DESCRIPTION)
        @ModelPriceJson BigDecimal writePricePerMillionCents) {
    public ModelCachePricing {
        if (mode == null) throw new IllegalArgumentException("model_cache_pricing_mode_required");
        readPricePerMillionCents = ModelPrices.normalize(readPricePerMillionCents);
        writePricePerMillionCents = ModelPrices.normalize(writePricePerMillionCents);
        if (mode == Mode.CACHE_READ && writePricePerMillionCents != null)
            throw new IllegalArgumentException("model_cache_write_price_not_applicable");
    }

    /** 显式跨领域映射，不把模型连接或凭据交给计价器。 */
    public CacheCostPrice toPrice() {
        return new CacheCostPrice(mode, readPricePerMillionCents, writePricePerMillionCents);
    }
}
