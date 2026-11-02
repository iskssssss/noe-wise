package com.skillforge.core.model;

import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.billing.pricing.MediaCostPrice.Unit;
import com.skillforge.core.billing.pricing.CacheCostPrice.Mode;
import com.skillforge.core.billing.pricing.TimePricingPolicy;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.UsageSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 平台模型价格匹配必须确定且具有稳定快照版本。 */
class PlatformModelPricingServiceTest {
    @Test void unrelatedConflictingDeepseekPricesDoNotBlockUnpricedMcpToolCalls() {
        var models = mock(PlatformModelCatalogService.class);
        var first = mock(ModelView.class);
        var second = mock(ModelView.class);
        for (var model : List.of(first, second)) {
            when(model.kind()).thenReturn("LLM");
            when(model.providerId()).thenReturn("deepseek");
            when(model.modelId()).thenReturn("deepseek-flash");
        }
        when(first.id()).thenReturn("1");
        when(second.id()).thenReturn("2");
        when(first.inputPricePerMillionCents()).thenReturn(BigDecimal.ONE);
        when(second.inputPricePerMillionCents()).thenReturn(BigDecimal.TEN);
        when(models.list()).thenReturn(List.of(first, second));
        when(models.pricingModels("mcp", "inst:remote-tool")).thenReturn(List.of());
        when(models.pricingModels("deepseek", "deepseek-flash")).thenReturn(List.of(first, second));
        var service = new PlatformModelPricingService(models);
        assertThrows(IllegalStateException.class, service::snapshot);
        var snapshot = service.snapshot("mcp", "inst:remote-tool");
        assertEquals(0, snapshot.prices().size());
        var amount = new com.skillforge.core.billing.pricing.PriceCalculator(service).calculate(
                1L, com.skillforge.core.billing.metering.CostType.TOOL_CALL,
                "mcp", "inst:remote-tool", LlmUsage.empty(), 1, null);
        assertNull(amount.input());
        assertNull(amount.output());
        assertNull(amount.total());
        assertNull(amount.preciseTotal());
        assertEquals(snapshot.version(), amount.pricingSnapshotVersion());
        assertThrows(IllegalStateException.class,
                () -> service.snapshot("deepseek", "deepseek-flash"));
        verify(models).list();
        verify(models, times(2)).pricingModels("mcp", "inst:remote-tool");
        verify(models).pricingModels("deepseek", "deepseek-flash");
    }

    @Test void timePolicyParticipatesInSnapshotIdentityWithoutChangingTheBusyRates() {
        var models = mock(PlatformModelCatalogService.class);
        var service = new PlatformModelPricingService(models);
        var reference = new ModelRevisionRef("1", 2);
        when(models.readRevision("1", 2)).thenReturn(timeModel(null));
        var flat = service.snapshot(reference);
        when(models.readRevision("1", 2)).thenReturn(timeModel(TimePricingTestPolicies.configured(List.of())));
        var timed = service.snapshot(reference);
        assertNotEquals(flat.version(), timed.version());
        assertNull(flat.exactPrice().timePricing());
        assertEquals(TimePricingTestPolicies.configured(List.of()), timed.exactPrice().timePricing());
        assertEquals(flat.exactPrice().inputPricePerMillion(), timed.exactPrice().inputPricePerMillion());
        assertEquals(flat.exactPrice().outputPricePerMillion(), timed.exactPrice().outputPricePerMillion());
        assertEquals(flat.exactPrice().cachePricing(), timed.exactPrice().cachePricing());
        verify(models, never()).list();
    }

    private PlatformModelConfig timeModel(TimePricingPolicy policy) {
        return new PlatformModelConfig("1", 2, "DeepSeek 峰谷", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key", "openai-compatible-chat-v1",
                "https://api.deepseek.com", "deepseek-flash", "encrypted", 4096, 8192, 1024, null, "DISABLED", 30, null, null,
                new BigDecimal("200"), new BigDecimal("800"), null, "CNY", com.skillforge.runtime.api.media.ModelImagePolicy.textOnly(), null,
                com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified(), null,
                new ModelCachePricing(Mode.CACHE_READ, new BigDecimal("4"), null), policy);
    }

    @Test void cacheModeAndUnknownOrZeroRatesParticipateInSnapshotIdentity() {
        var models = mock(PlatformModelCatalogService.class);
        var pricing = new PlatformModelPricingService(models);
        var versions = new java.util.HashSet<String>();
        var configured = List.of(
                new ModelCachePricing(Mode.CACHE_READ, null, null),
                new ModelCachePricing(Mode.CACHE_READ, BigDecimal.ZERO, null),
                new ModelCachePricing(Mode.CACHE_READ, new BigDecimal("0.1"), null),
                new ModelCachePricing(Mode.CACHE_READ_WRITE, new BigDecimal("0.1"), null),
                new ModelCachePricing(Mode.CACHE_READ_WRITE, new BigDecimal("0.1"), BigDecimal.ZERO),
                new ModelCachePricing(Mode.CACHE_READ_WRITE, new BigDecimal("0.1"), new BigDecimal("12.5")));
        when(models.readRevision("1", 2)).thenReturn(modelConfig("1", 2, BigDecimal.TEN, BigDecimal.ONE));
        versions.add(pricing.snapshot(new ModelRevisionRef("1", 2)).version());
        for (var cache : configured) {
            when(models.readRevision("1", 2)).thenReturn(modelConfig("1", 2, BigDecimal.TEN, BigDecimal.ONE, cache));
            var frozen = pricing.snapshot(new ModelRevisionRef("1", 2));
            assertEquals(cache.toPrice(), frozen.exactPrice().cachePricing());
            versions.add(frozen.version());
        }
        assertEquals(configured.size() + 1, versions.size());
        verify(models, never()).list();
    }

    @Test void conflictingCachePricesForSameProviderAndModelAreRejected() {
        var models = mock(PlatformModelCatalogService.class);
        var read = new ModelCachePricing(Mode.CACHE_READ, new BigDecimal("0.1"), null);
        var zero = new ModelCachePricing(Mode.CACHE_READ, BigDecimal.ZERO, null);
        when(models.list()).thenReturn(List.of(
                ModelView.from(modelConfig("1", 2, BigDecimal.TEN, BigDecimal.ONE, read)),
                ModelView.from(modelConfig("2", 3, BigDecimal.TEN, BigDecimal.ONE, zero))));
        assertThrows(IllegalStateException.class, () -> new PlatformModelPricingService(models).snapshot());
        when(models.list()).thenReturn(List.of(
                ModelView.from(modelConfig("1", 2, BigDecimal.TEN, BigDecimal.ONE, read)),
                ModelView.from(modelConfig("2", 3, BigDecimal.TEN, BigDecimal.ONE,
                        new ModelCachePricing(Mode.CACHE_READ, new BigDecimal("0.100000"), null)))));
        assertEquals(1, new PlatformModelPricingService(models).snapshot().prices().size());
    }

    @Test void mediaSnapshotMapsOnlyTheExactPublishedRevisionAndIncludesItsUnitInIdentity() {
        var models = mock(PlatformModelCatalogService.class);
        var configured = mediaModel("1", 3, new MediaCostPricing(Unit.VIDEO_SECOND, new BigDecimal("12.500000"), null, null, "720P"));
        when(models.readRevision("1", 3)).thenReturn(configured);
        var frozen = new PlatformModelPricingService(models).snapshot(new ModelRevisionRef("1", 3));
        assertEquals(Unit.VIDEO_SECOND, frozen.exactPrice().mediaPricing().unit());
        assertEquals(new BigDecimal("12.5"), frozen.exactPrice().mediaPricing().pricePerUnitCents());
        assertEquals("720P", frozen.exactPrice().mediaPricing().videoResolution()); assertEquals("USD", frozen.exactPrice().currency());
        verify(models, never()).list();
        var other = mediaModel("2", 4, new MediaCostPricing(Unit.OUTPUT_VIDEO, new BigDecimal("12.75"), null, null, null));
        when(models.readRevision("2", 4)).thenReturn(other);
        var second = new PlatformModelPricingService(models).snapshot(new ModelRevisionRef("2", 4));
        assertEquals(Unit.OUTPUT_VIDEO, second.exactPrice().mediaPricing().unit());
        assertEquals(new BigDecimal("12.75"), second.exactPrice().mediaPricing().pricePerUnitCents());
        assertNotEquals(frozen.version(), second.version());
        verify(models, never()).list();
    }

    @Test void differentMediaTiersNeverBlockOrChangeTheNonMediaFallbackCatalog() {
        var models = mock(PlatformModelCatalogService.class);
        var chat = model("100", 2, new BigDecimal("10"), new BigDecimal("20"));
        when(models.list()).thenReturn(List.of(chat));
        var service = new PlatformModelPricingService(models);
        var before = service.snapshot();
        var list = new java.util.ArrayList<ModelView>(); list.add(chat);
        int index = 0;
        for (String kind : List.of("IMAGE_GENERATION", "VIDEO_GENERATION", "SPEECH_RECOGNITION", "SPEECH_SYNTHESIS")) {
            for (int tier = 1; tier <= 2; tier++) {
                var media = mock(ModelView.class);
                when(media.id()).thenReturn(Integer.toString(++index)); when(media.revision()).thenReturn(1L);
                when(media.kind()).thenReturn(kind); when(media.providerId()).thenReturn("same-media-provider");
                when(media.modelId()).thenReturn("same-media-model"); when(media.currency()).thenReturn("CNY");
                when(media.mediaPricing()).thenReturn(new MediaCostPricing(Unit.TOKENS, null, BigDecimal.valueOf(tier), BigDecimal.valueOf(tier), null));
                list.add(media);
            }
        }
        when(models.list()).thenReturn(list);
        var after = service.snapshot();
        assertEquals(before, after); assertEquals(1, after.prices().size());
        assertNull(after.resolve("same-media-provider", "same-media-model"));
        when(models.pricingModels("provider", "model")).thenReturn(List.of(chat));
        var calculator = new com.skillforge.core.billing.pricing.PriceCalculator(service);
        assertEquals(new BigDecimal("10.000000000000"), calculator.calculate(1L, com.skillforge.core.billing.metering.CostType.LLM_CHAT,
                "provider", "model", new LlmUsage(1_000_000L, 0L, 1_000_000L), 1, null).preciseTotal());
    }

    private static PlatformModelConfig mediaModel(String id, long revision, MediaCostPricing pricing) {
        return new PlatformModelConfig(id, revision, "媒体成本", "VIDEO_GENERATION", true,
                "aliyun-bailian", "bailian-cn-beijing-video", "fixture", "bailian-native", "bearer-api-key", "fixture",
                "https://dashscope.aliyuncs.com", "video-model", "encrypted", null, null, null, null, null, 120, null, null,
                null, null, null, "USD", com.skillforge.runtime.api.media.ModelImagePolicy.textOnly(), null,
                com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified(), pricing, null, null);
    }

    @Test
    void identicalDuplicateMatchesAreAllowed() {
        PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
        when(models.list()).thenReturn(List.of(
                model("1", 2, new BigDecimal("10"), new BigDecimal("20")), model("2", 5, new BigDecimal("10"), new BigDecimal("20"))));

        var snapshot = new PlatformModelPricingService(models).snapshot();
        var price = snapshot.resolve("provider", "model");

        assertEquals(new BigDecimal("10"), price.inputPricePerMillion());
        assertEquals(new BigDecimal("20"), price.outputPricePerMillion());
        assertEquals(1, snapshot.prices().size());
        assertEquals(46, snapshot.version().length());
    }

    @Test
    void conflictingDuplicateMatchesAreRejected() {
        PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
        when(models.list()).thenReturn(List.of(
                model("1", 2, new BigDecimal("10"), new BigDecimal("20")), model("2", 5, new BigDecimal("11"), new BigDecimal("20"))));

        assertThrows(IllegalStateException.class,
                () -> new PlatformModelPricingService(models).snapshot());
    }

    @Test
    void exactRevisionSnapshotDoesNotReadTheCurrentCatalog() {
        PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
        when(models.readRevision("1", 2)).thenReturn(
                modelConfig("1", 2, new BigDecimal("10"), new BigDecimal("20")));
        var reference = new ModelRevisionRef("1", 2);

        var snapshot = new PlatformModelPricingService(models).snapshot(reference);

        assertEquals(new BigDecimal("10"), snapshot.exactPrice().inputPricePerMillion());
        assertEquals(new BigDecimal("20"), snapshot.exactPrice().outputPricePerMillion());
        verify(models).readRevision("1", 2);
        verify(models, never()).list();
    }

    @Test void fractionalPriceSurvivesFrozenRevisionAndOneTokenCost() {
        PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
        when(models.readRevision("1", 2)).thenReturn(modelConfig("1", 2, new BigDecimal("12.5"), null));
        var reference = new ModelRevisionRef("1", 2);
        var calculator = new com.skillforge.core.billing.pricing.PriceCalculator(new PlatformModelPricingService(models));
        var amount = calculator.calculate(reference, 1L, com.skillforge.core.billing.metering.CostType.LLM_EMBEDDING,
                "alias", "alias", new LlmUsage(1L, null, null, null, null, null, UsageSource.PROVIDER_REPORTED, null), null, null);
        assertEquals(new BigDecimal("0.000012500000"), amount.preciseTotal());
        assertEquals(0L, amount.total());
        var million = calculator.calculate(reference, 1L, com.skillforge.core.billing.metering.CostType.LLM_EMBEDDING,
                "alias", "alias", new LlmUsage(1_000_000L, null, null, null, null, null, UsageSource.PROVIDER_REPORTED, null), null, null);
        assertEquals(new BigDecimal("12.500000000000"), million.preciseTotal());
        assertEquals(13L, million.total());
    }

    @Test void equivalentDecimalScalesDoNotCreateConflictingPrices() {
        PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
        when(models.list()).thenReturn(List.of(
                model("1", 2, new BigDecimal("12.5"), BigDecimal.ZERO),
                model("2", 3, new BigDecimal("12.500000"), BigDecimal.ZERO)));
        assertEquals(1, new PlatformModelPricingService(models).snapshot().prices().size());
    }

    @Test void targetedSnapshotPreservesDuplicateDecimalAndUnknownPriceSemantics() {
        var models = mock(PlatformModelCatalogService.class);
        var pricing = new PlatformModelPricingService(models);
        var equivalent = List.of(
                model("1", 2, new BigDecimal("12.5"), null),
                model("2", 3, new BigDecimal("12.500000"), null));
        when(models.pricingModels("provider", "model")).thenReturn(equivalent);
        var targeted = pricing.snapshot("provider", "model");
        when(models.list()).thenReturn(equivalent);
        assertEquals(pricing.snapshot(), targeted);
        assertNull(targeted.resolve("provider", "model").outputPricePerMillion());
        when(models.pricingModels("provider", "model")).thenReturn(List.of(
                equivalent.getFirst(), model("3", 4, new BigDecimal("12.5"), BigDecimal.ZERO)));
        assertThrows(IllegalStateException.class, () -> pricing.snapshot("provider", "model"));
        verify(models).list();
        verify(models, times(2)).pricingModels("provider", "model");
    }

    @Test void targetedSnapshotPreservesCacheAndTimePolicyIdentity() {
        var models = mock(PlatformModelCatalogService.class);
        var pricing = new PlatformModelPricingService(models);
        var flat = List.of(ModelView.from(timeModel(null)));
        when(models.pricingModels("deepseek", "deepseek-flash")).thenReturn(flat);
        var flatSnapshot = pricing.snapshot("deepseek", "deepseek-flash");
        var timed = List.of(ModelView.from(timeModel(TimePricingTestPolicies.configured(List.of()))));
        when(models.pricingModels("deepseek", "deepseek-flash")).thenReturn(timed);
        var timedSnapshot = pricing.snapshot("deepseek", "deepseek-flash");
        assertNotEquals(flatSnapshot.version(), timedSnapshot.version());
        assertEquals(flatSnapshot.resolve("deepseek", "deepseek-flash").cachePricing(),
                timedSnapshot.resolve("deepseek", "deepseek-flash").cachePricing());
        assertEquals(TimePricingTestPolicies.configured(List.of()),
                timedSnapshot.resolve("deepseek", "deepseek-flash").timePricing());
        verify(models, never()).list();
    }

    private ModelView model(String id, long revision, BigDecimal input, BigDecimal output) {
        return new ModelView(id, revision, "模型 " + id, "LLM", true,
                "provider", "service", "2025-03-11", "RESPONSES", "BEARER",
                "https://example.com/v1", "model", true,
                32_000, 128_000, 32_000, null, "DEFAULT", 60, null, null,
                input, output, null, "CNY");
    }

    private PlatformModelConfig modelConfig(
            String id, long revision, BigDecimal input, BigDecimal output) {
        return modelConfig(id, revision, input, output, null);
    }

    private PlatformModelConfig modelConfig(
            String id, long revision, BigDecimal input, BigDecimal output, ModelCachePricing cache) {
        return new PlatformModelConfig(id, revision, "模型 " + id, "LLM", true,
                "provider", "service", "2025-03-11", "RESPONSES", "BEARER", "v1",
                "https://example.com/v1", "configured-model", "encrypted",
                32_000, 128_000, 32_000, null, "DEFAULT", 60, null, null,
                input, output, null, "CNY", com.skillforge.runtime.api.media.ModelImagePolicy.textOnly(), null,
                com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified(), null, cache, null);
    }
}
