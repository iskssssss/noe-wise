package com.skillforge.core.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.billing.pricing.MediaCostPrice;
import com.skillforge.core.billing.pricing.MediaCostPrice.Unit;
import java.math.BigDecimal;
import java.util.Set;

/** 管理员明确配置的供应商媒体成本；未知价格保持null，不根据型号或请求数量推断。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MediaCostPricing(Unit unit, @ModelPriceJson BigDecimal pricePerUnitCents,
        @ModelPriceJson BigDecimal inputPricePerMillionCents, @ModelPriceJson BigDecimal outputPricePerMillionCents,
        String videoResolution) {
    public MediaCostPricing {
        if (unit == null) throw new IllegalArgumentException("model_media_pricing_unit_required");
        pricePerUnitCents = ModelPrices.normalize(pricePerUnitCents);
        inputPricePerMillionCents = ModelPrices.normalize(inputPricePerMillionCents);
        outputPricePerMillionCents = ModelPrices.normalize(outputPricePerMillionCents);
        if (unit == Unit.TOKENS) {
            if (pricePerUnitCents != null || inputPricePerMillionCents == null || outputPricePerMillionCents == null)
                throw new IllegalArgumentException("model_media_token_prices_required");
        } else if (pricePerUnitCents == null || inputPricePerMillionCents != null || outputPricePerMillionCents != null)
            throw new IllegalArgumentException("model_media_unit_price_required");
        if (unit == Unit.VIDEO_SECOND) {
            if (videoResolution == null || !Set.of("480P", "720P", "1080P").contains(videoResolution))
                throw new IllegalArgumentException("model_media_video_resolution_required");
        } else if (videoResolution != null) throw new IllegalArgumentException("model_media_video_resolution_not_applicable");
    }

    void requireKind(String kind) {
        boolean allowed = switch (kind) {
            case "IMAGE_GENERATION" -> unit == Unit.OUTPUT_IMAGE || unit == Unit.TOKENS;
            case "VIDEO_GENERATION" -> unit == Unit.OUTPUT_VIDEO || unit == Unit.VIDEO_SECOND || unit == Unit.TOKENS;
            case "SPEECH_RECOGNITION" -> unit == Unit.INPUT_AUDIO_SECOND || unit == Unit.TOKENS;
            case "SPEECH_SYNTHESIS" -> unit == Unit.BILLING_CHARACTER || unit == Unit.OUTPUT_AUDIO_SECOND || unit == Unit.TOKENS;
            default -> false;
        };
        if (!allowed) throw new IllegalArgumentException("model_media_pricing_kind_invalid");
    }

    /** 显式跨领域映射，不把管理配置或模型凭据交给计价器。 */
    public MediaCostPrice toPrice() {
        return new MediaCostPrice(unit, pricePerUnitCents,
                inputPricePerMillionCents, outputPricePerMillionCents, videoResolution);
    }
}
