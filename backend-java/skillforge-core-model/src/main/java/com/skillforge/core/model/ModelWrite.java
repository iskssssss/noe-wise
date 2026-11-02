package com.skillforge.core.model;

import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.core.billing.pricing.TimePricingPolicy;
import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;

/** 平台管理员提交的模型配置；凭据留空保留原值，连接认证范围变化必须重新填写。 */
public record ModelWrite(Long expectedRevision, String name, String kind, boolean enabled,
        String providerId, String serviceId, String profileVersion, String protocol,
        String authenticationScheme, String endpoint, String modelId, String apiKey,
        Integer supportedMaxOutputTokens, Integer contextWindowTokens, Integer maxOutputTokens,
        Double temperature, String reasoning, Integer timeoutSeconds, Integer dimensions, String path,
        @Schema(type = "string", pattern = ModelPriceJson.PATTERN, nullable = true, description = ModelPriceJson.DESCRIPTION) @ModelPriceJson BigDecimal inputPricePerMillionCents, @Schema(type = "string", pattern = ModelPriceJson.PATTERN, nullable = true, description = ModelPriceJson.DESCRIPTION) @ModelPriceJson BigDecimal outputPricePerMillionCents,
        @Schema(type = "string", pattern = ModelPriceJson.PATTERN, nullable = true, description = ModelPriceJson.DESCRIPTION) @ModelPriceJson BigDecimal pricePerQuantityCents, String currency,
        @Schema(description = "具体模型的显式图片能力、字节和尺寸限额及计量规则；缺省禁用图片，不根据模型名称推断") ModelImagePolicy imagePolicy,
        com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities prefixCapabilities,
        @com.fasterxml.jackson.annotation.JsonProperty("media_pricing") MediaCostPricing mediaPricing,
        @Schema(description = "LLM 缓存输入成本；null 表示总输入统一价，缓存单价 null 表示未知，0 表示明确零价")
        @com.fasterxml.jackson.annotation.JsonProperty("cache_pricing") ModelCachePricing cachePricing,
        @Schema(description = "峰谷成本配置；null 不启用，模型基准单价按所配置时段倍率计算")
        @com.fasterxml.jackson.annotation.JsonProperty("time_pricing") TimePricingPolicy timePricing) {
    public ModelWrite(Long expectedRevision, String name, String kind, boolean enabled,
        String providerId, String serviceId, String profileVersion, String protocol,
        String authenticationScheme, String endpoint, String modelId, String apiKey,
        Integer supportedMaxOutputTokens, Integer contextWindowTokens, Integer maxOutputTokens,
        Double temperature, String reasoning, Integer timeoutSeconds, Integer dimensions, String path,
        BigDecimal inputPricePerMillionCents, BigDecimal outputPricePerMillionCents,
        BigDecimal pricePerQuantityCents, String currency, ModelImagePolicy imagePolicy) {
        this(expectedRevision, name, kind, enabled, providerId, serviceId, profileVersion, protocol,
                authenticationScheme, endpoint, modelId, apiKey, supportedMaxOutputTokens, contextWindowTokens,
                maxOutputTokens, temperature, reasoning, timeoutSeconds, dimensions, path, inputPricePerMillionCents,
                outputPricePerMillionCents, pricePerQuantityCents, currency, imagePolicy,
                com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified(), null, null, null);
    }
    public ModelWrite {
        if (prefixCapabilities == null) prefixCapabilities = com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified();
    }

    public ModelWrite(Long expectedRevision, String name, String kind, boolean enabled,
        String providerId, String serviceId, String profileVersion, String protocol,
        String authenticationScheme, String endpoint, String modelId, String apiKey,
        Integer supportedMaxOutputTokens, Integer contextWindowTokens, Integer maxOutputTokens,
        Double temperature, String reasoning, Integer timeoutSeconds, Integer dimensions, String path,
        BigDecimal inputPricePerMillionCents, BigDecimal outputPricePerMillionCents,
        BigDecimal pricePerQuantityCents, String currency) {
        this(expectedRevision, name, kind, enabled, providerId, serviceId, profileVersion, protocol, authenticationScheme, endpoint, modelId, apiKey, supportedMaxOutputTokens, contextWindowTokens, maxOutputTokens, temperature, reasoning, timeoutSeconds, dimensions, path, inputPricePerMillionCents, outputPricePerMillionCents, pricePerQuantityCents, currency, ModelImagePolicy.textOnly());
    }

    @Override public String toString() { return "ModelWrite[kind=" + kind + "]"; }
}
