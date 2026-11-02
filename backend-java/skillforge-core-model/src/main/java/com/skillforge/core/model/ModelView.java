package com.skillforge.core.model;

import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.core.billing.pricing.TimePricingPolicy;
import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;

/** 平台展示投影，永远不包含凭据明文或密文。 */
public record ModelView(String id, long revision, String name, String kind, boolean enabled,
        String providerId, String serviceId, String profileVersion, String protocol,
        String authenticationScheme, String endpoint, String modelId, boolean credentialConfigured,
        Integer supportedMaxOutputTokens, Integer contextWindowTokens, Integer maxOutputTokens,
        Double temperature, String reasoning, Integer timeoutSeconds, Integer dimensions, String path,
        @Schema(type = "string", pattern = ModelPriceJson.PATTERN, nullable = true, description = ModelPriceJson.DESCRIPTION) @ModelPriceJson BigDecimal inputPricePerMillionCents, @Schema(type = "string", pattern = ModelPriceJson.PATTERN, nullable = true, description = ModelPriceJson.DESCRIPTION) @ModelPriceJson BigDecimal outputPricePerMillionCents,
        @Schema(type = "string", pattern = ModelPriceJson.PATTERN, nullable = true, description = ModelPriceJson.DESCRIPTION) @ModelPriceJson BigDecimal pricePerQuantityCents, String currency, ModelImagePolicy imagePolicy,
        Long publishedRevision, boolean hasUnpublishedChanges, ModelCompatibilityReport compatibilityReport,
        com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities prefixCapabilities,
        @com.fasterxml.jackson.annotation.JsonProperty("media_pricing") MediaCostPricing mediaPricing,
        @Schema(description = "LLM 缓存输入成本；null 表示总输入统一价，缓存单价 null 表示未知，0 表示明确零价")
        @com.fasterxml.jackson.annotation.JsonProperty("cache_pricing") ModelCachePricing cachePricing,
        @Schema(description = "峰谷成本配置；null 不启用，模型基准单价按所配置时段倍率计算")
        @com.fasterxml.jackson.annotation.JsonProperty("time_pricing") TimePricingPolicy timePricing) {
    public ModelView(String id, long revision, String name, String kind, boolean enabled,
        String providerId, String serviceId, String profileVersion, String protocol,
        String authenticationScheme, String endpoint, String modelId, boolean credentialConfigured,
        Integer supportedMaxOutputTokens, Integer contextWindowTokens, Integer maxOutputTokens,
        Double temperature, String reasoning, Integer timeoutSeconds, Integer dimensions, String path,
        BigDecimal inputPricePerMillionCents, BigDecimal outputPricePerMillionCents,
        BigDecimal pricePerQuantityCents, String currency) {
        this(id, revision, name, kind, enabled, providerId, serviceId, profileVersion, protocol, authenticationScheme, endpoint, modelId, credentialConfigured, supportedMaxOutputTokens, contextWindowTokens, maxOutputTokens, temperature, reasoning, timeoutSeconds, dimensions, path, inputPricePerMillionCents, outputPricePerMillionCents, pricePerQuantityCents, currency, ModelImagePolicy.textOnly(), revision, false, null, com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified(), null, null, null);
    }

    public static ModelView from(PlatformModelConfig c) {
        return from(c, c.revision(), false);
    }

    public static ModelView from(PlatformModelConfig c, Long publishedRevision, boolean changed) {
        return new ModelView(c.id(), c.revision(), c.name(), c.kind(), c.enabled(), c.providerId(),
                c.serviceId(), c.profileVersion(), c.protocol(), c.authenticationScheme(), c.endpoint(),
                c.modelId(), c.apiKeyEncrypted() != null && !c.apiKeyEncrypted().isBlank(),
                c.supportedMaxOutputTokens(), c.contextWindowTokens(), c.maxOutputTokens(),
                c.temperature(), c.reasoning(), c.timeoutSeconds(), c.dimensions(), c.path(),
                c.inputPricePerMillionCents(), c.outputPricePerMillionCents(), c.pricePerQuantityCents(), c.currency(), c.imagePolicy(), publishedRevision, changed, c.compatibilityReport(), c.prefixCapabilities(), c.mediaPricing(), c.cachePricing(), c.timePricing());
    }
}
