package com.skillforge.core.model;

import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.core.billing.pricing.TimePricingPolicy;
import java.math.BigDecimal;
/** 平台模型不可变版本；连接与凭据仅供受信宿主使用，不可直接序列化到管理接口。 */
public record PlatformModelConfig(
        String id, long revision, String name, String kind, boolean enabled,
        String providerId, String serviceId, String profileVersion, String protocol,
        String authenticationScheme, String dialectVersion, String endpoint, String modelId,
        String apiKeyEncrypted, Integer supportedMaxOutputTokens, Integer contextWindowTokens,
        Integer maxOutputTokens, Double temperature, String reasoning, Integer timeoutSeconds,
        Integer dimensions, String path, BigDecimal inputPricePerMillionCents,
        BigDecimal outputPricePerMillionCents, BigDecimal pricePerQuantityCents, String currency, ModelImagePolicy imagePolicy,
        ModelCompatibilityReport compatibilityReport,
        com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities prefixCapabilities,
        @com.fasterxml.jackson.annotation.JsonProperty("media_pricing") MediaCostPricing mediaPricing,
        @com.fasterxml.jackson.annotation.JsonProperty("cache_pricing") ModelCachePricing cachePricing,
        @com.fasterxml.jackson.annotation.JsonProperty("time_pricing") TimePricingPolicy timePricing) {
    public PlatformModelConfig(
        String id, long revision, String name, String kind, boolean enabled,
        String providerId, String serviceId, String profileVersion, String protocol,
        String authenticationScheme, String dialectVersion, String endpoint, String modelId,
        String apiKeyEncrypted, Integer supportedMaxOutputTokens, Integer contextWindowTokens,
        Integer maxOutputTokens, Double temperature, String reasoning, Integer timeoutSeconds,
        Integer dimensions, String path, BigDecimal inputPricePerMillionCents,
        BigDecimal outputPricePerMillionCents, BigDecimal pricePerQuantityCents, String currency, ModelImagePolicy imagePolicy) {
        this(id, revision, name, kind, enabled, providerId, serviceId, profileVersion, protocol, authenticationScheme,
                dialectVersion, endpoint, modelId, apiKeyEncrypted, supportedMaxOutputTokens, contextWindowTokens,
                maxOutputTokens, temperature, reasoning, timeoutSeconds, dimensions, path,
                inputPricePerMillionCents, outputPricePerMillionCents, pricePerQuantityCents, currency, imagePolicy, null, com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified(), null, null, null);
    }

    public PlatformModelConfig(
        String id, long revision, String name, String kind, boolean enabled,
        String providerId, String serviceId, String profileVersion, String protocol,
        String authenticationScheme, String dialectVersion, String endpoint, String modelId,
        String apiKeyEncrypted, Integer supportedMaxOutputTokens, Integer contextWindowTokens,
        Integer maxOutputTokens, Double temperature, String reasoning, Integer timeoutSeconds,
        Integer dimensions, String path, BigDecimal inputPricePerMillionCents,
        BigDecimal outputPricePerMillionCents, BigDecimal pricePerQuantityCents, String currency) {
        this(id, revision, name, kind, enabled, providerId, serviceId, profileVersion, protocol, authenticationScheme, dialectVersion, endpoint, modelId, apiKeyEncrypted, supportedMaxOutputTokens, contextWindowTokens, maxOutputTokens, temperature, reasoning, timeoutSeconds, dimensions, path, inputPricePerMillionCents, outputPricePerMillionCents, pricePerQuantityCents, currency, ModelImagePolicy.textOnly());
    }

    public PlatformModelConfig {
        if (prefixCapabilities == null) prefixCapabilities = com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified();
        java.util.Objects.requireNonNull(imagePolicy, "imagePolicy");
        inputPricePerMillionCents = ModelPrices.normalize(inputPricePerMillionCents);
        outputPricePerMillionCents = ModelPrices.normalize(outputPricePerMillionCents);
        pricePerQuantityCents = ModelPrices.normalize(pricePerQuantityCents);
    }
    @Override public String toString() {
        return "PlatformModelConfig[id=" + id + ", revision=" + revision + ", kind=" + kind + "]";
    }
}
