package com.skillforge.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.billing.pricing.MediaCostPrice.Unit;
import com.skillforge.core.billing.pricing.TimePricingPolicy;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelKind;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.SupportLevel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 平台模型目录的不可变版本、脱敏投影与租户隔离测试。 */
class PlatformModelCatalogServiceTest {
    @Test void missingInstitutionDefaultHasActionableStableError() {
        var service = service(new InMemoryMapper());
        assertThatThrownBy(() -> service.resolveDefault("7", "LLM"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getCode()).isEqualTo("INSTITUTION_MODEL_SELECTION_REQUIRED");
                    assertThat(error.getMessage()).contains("机构设置", "默认模型");
                });
    }
    @Test void summaryReadsOnlyDirectoryProjectionWithoutPricingOrFullDraft() {
        var mapper = org.mockito.Mockito.mock(PlatformModelMapper.class);
        var rules = org.mockito.Mockito.mock(TimePricingRules.class);
        var crypto = org.mockito.Mockito.mock(CryptoService.class);
        var rows = List.of(new PlatformModelDirectorySummary("9007199254740993", "未发布", "LLM", "deepseek",
                "draft-model", false, null, true));
        org.mockito.Mockito.when(mapper.listSummaries()).thenReturn(rows);
        var service = new PlatformModelCatalogService(mapper, crypto, new ObjectMapper(), ModelProviderCatalog.standard(), rules);
        assertThat(service.summaries()).isSameAs(rows);
        org.mockito.Mockito.verify(mapper).listSummaries();
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
        org.mockito.Mockito.verifyNoInteractions(rules, crypto);
    }

    @Test void noPricingTargetSkipsEveryRuleAndUnrelatedDraftRead() {
        var mapper=org.mockito.Mockito.mock(PlatformModelMapper.class);
        var rules=org.mockito.Mockito.mock(TimePricingRules.class);
        var crypto=org.mockito.Mockito.mock(CryptoService.class);
        org.mockito.Mockito.when(mapper.pricingDrafts("mcp","inst:remote-tool")).thenReturn(List.of());
        var service=new PlatformModelCatalogService(mapper,crypto,new ObjectMapper(),ModelProviderCatalog.standard(),rules);
        assertThat(service.pricingModels("mcp","inst:remote-tool")).isEmpty();
        org.mockito.Mockito.verify(mapper).pricingDrafts("mcp","inst:remote-tool");
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);org.mockito.Mockito.verifyNoInteractions(rules,crypto);
    }

    @Test void pricingTargetsKeepFullViewsDisabledUnpublishedAndOnlyTheirSharedTimeRule() throws Exception {
        var stored=new InMemoryMapper();var full=service(stored);
        var cache=new ModelCachePricing(com.skillforge.core.billing.pricing.CacheCostPrice.Mode.CACHE_READ,BigDecimal.ZERO,null);
        var first=full.create(cacheWrite(null,"synthetic-first",cache,timePolicy()),"7");
        full.publish(first.id(),first.revision(),"7");
        var json=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        var disabled=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(cacheWrite(null,"synthetic-second",cache,timePolicy()));
        disabled.put("enabled",false);
        var second=full.create(json.treeToValue(disabled,ModelWrite.class),"7");
        full.create(write(null,"unrelated",true,"http://127.0.0.1:11434/v1","synthetic-unrelated"),"7");
        Long rule=stored.draftRules.get(first.id());stored.draftRules.put(second.id(),rule);
        var expected=full.list().stream().filter(row->"deepseek".equals(row.providerId())&&"deepseek-flash".equals(row.modelId())).toList();
        var mapper=org.mockito.Mockito.mock(PlatformModelMapper.class);var rules=org.mockito.Mockito.mock(TimePricingRules.class);
        org.mockito.Mockito.when(mapper.pricingDrafts("deepseek","deepseek-flash")).thenReturn(List.of(
                new PlatformModelMapper.PricingDraft(first.id(),stored.draft(first.id()),rule),
                new PlatformModelMapper.PricingDraft(second.id(),stored.draft(second.id()),rule)));
        org.mockito.Mockito.when(rules.readAll(List.of(rule))).thenReturn(Map.of(rule,timePolicy()));
        var target=new PlatformModelCatalogService(mapper,org.mockito.Mockito.mock(CryptoService.class),json,ModelProviderCatalog.standard(),rules);
        assertThat(target.pricingModels("deepseek","deepseek-flash")).containsExactlyElementsOf(expected);
        assertThat(expected.get(1).enabled()).isFalse();assertThat(expected.get(1).publishedRevision()).isNull();
        org.mockito.Mockito.verify(rules).readAll(List.of(rule));org.mockito.Mockito.verifyNoMoreInteractions(rules);
        org.mockito.Mockito.verify(mapper).pricingDrafts("deepseek","deepseek-flash");org.mockito.Mockito.verifyNoMoreInteractions(mapper);
    }

    @Test void pricingTargetWithoutRulesDoesNotReadPricingTablesOrPerModelRuleId() {
        var stored=new InMemoryMapper();var full=service(stored);
        var created=full.create(cacheWrite(null,"synthetic-flat",null),"7");
        var mapper=org.mockito.Mockito.mock(PlatformModelMapper.class);var rules=org.mockito.Mockito.mock(TimePricingRules.class);
        org.mockito.Mockito.when(mapper.pricingDrafts("deepseek","deepseek-flash")).thenReturn(List.of(
                new PlatformModelMapper.PricingDraft(created.id(),stored.draft(created.id()),null)));
        var target=new PlatformModelCatalogService(mapper,org.mockito.Mockito.mock(CryptoService.class),
                new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE),ModelProviderCatalog.standard(),rules);
        assertThat(target.pricingModels("deepseek","deepseek-flash")).containsExactly(full.detail(created.id()));
        org.mockito.Mockito.verifyNoInteractions(rules);
        org.mockito.Mockito.verify(mapper).pricingDrafts("deepseek","deepseek-flash");org.mockito.Mockito.verifyNoMoreInteractions(mapper);
    }

    @Test void timePolicySurvivesPreviewPublicationHistoricalReadsAndRollbackWithoutNewCredentials() {
        var service = service(new InMemoryMapper());
        var read = new ModelCachePricing(com.skillforge.core.billing.pricing.CacheCostPrice.Mode.CACHE_READ,
                new BigDecimal("0.1"), null);
        var request = cacheWrite(null, "synthetic-key", read, timePolicy());
        assertThat(service.previewForProbe(null, request).timePricing()).isEqualTo(timePolicy());
        assertThat(service.list()).isEmpty();
        var created = service.create(request, "7");
        assertThat(created.timePricing()).isEqualTo(timePolicy());
        service.publish(created.id(), created.revision(), "7");
        var original = service.readRevision(created.id(), 1);
        var edited = cacheWrite(created.revision(), null, read, null);
        var preview = service.previewForProbe(created.id(), edited);
        assertThat(preview.timePricing()).isNull();
        assertThat(preview.apiKeyEncrypted()).isEqualTo(original.apiKeyEncrypted());
        assertThat(service.detail(created.id()).timePricing()).isEqualTo(timePolicy());
        var updated = service.update(created.id(), edited, "7");
        service.publish(created.id(), updated.revision(), "7");
        assertThat(service.current(created.id()).timePricing()).isNull();
        assertThat(service.readRevision(created.id(), 1).timePricing()).isEqualTo(timePolicy());
        var restored = service.rollback(created.id(), 1, updated.revision(), "7");
        assertThat(restored.timePricing()).isEqualTo(timePolicy());
        assertThat(service.current(created.id()).timePricing()).isNull();
        service.publish(created.id(), restored.revision(), "7");
        assertThat(service.current(created.id()).timePricing()).isEqualTo(timePolicy());
        assertThat(service.history(created.id())).extracting(ModelView::timePricing)
                .containsExactly(timePolicy(), null, timePolicy());
    }

    @Test void timePolicySupportsConfiguredModelsWithUnifiedReadAndReadWritePricing() throws Exception {
        var service = service(new InMemoryMapper());
        var json = new ObjectMapper().findAndRegisterModules();
        for (var cache : java.util.Arrays.asList(null,
                new ModelCachePricing(com.skillforge.core.billing.pricing.CacheCostPrice.Mode.CACHE_READ, null, null),
                new ModelCachePricing(com.skillforge.core.billing.pricing.CacheCostPrice.Mode.CACHE_READ_WRITE, null, null))) {
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(
                    cacheWrite(null, "synthetic-key", cache, unrestrictedTimePolicy()));
            tree.put("modelId", "administrator-configured-future-model");
            var created = service.create(json.treeToValue(tree, ModelWrite.class), "7");
            service.publish(created.id(), created.revision(), "7");
            assertThat(service.readRevision(created.id(), 1).timePricing()).isEqualTo(unrestrictedTimePolicy());
            assertThat(service.readRevision(created.id(), 1).cachePricing()).isEqualTo(cache);
        }
    }

    @Test void timePolicyRejectsScopeMismatchesAndNonLlmAtSaveAndPublish() throws Exception {
        var mapper = new InMemoryMapper();
        var service = service(mapper);
        var json = new ObjectMapper().findAndRegisterModules();
        var read = new ModelCachePricing(com.skillforge.core.billing.pricing.CacheCostPrice.Mode.CACHE_READ, BigDecimal.ONE, null);
        var base = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(
                cacheWrite(null, "synthetic-key", read, timePolicy()));
        for (var change : Map.of("providerId", "custom", "serviceId", "custom-openai-compatible",
                "profileVersion", "other-profile", "modelId", "different-model", "kind", "EMBEDDING").entrySet()) {
            var tree = base.deepCopy(); tree.put(change.getKey(), change.getValue());
            var invalid = json.treeToValue(tree, ModelWrite.class);
            assertThatThrownBy(() -> service.previewForProbe(null, invalid)).isInstanceOf(ApiException.class)
                    .hasMessageContaining("model_time_pricing_scope_invalid");
            assertThatThrownBy(() -> service.create(invalid, "7")).isInstanceOf(ApiException.class)
                    .hasMessageContaining("model_time_pricing_scope_invalid");
        }
        var created = service.create(cacheWrite(null, "synthetic-key", read, timePolicy()), "7");
        var stored = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(mapper.drafts.get(created.id()));
        stored.put("model_id", "outside-configured-scope");
        mapper.drafts.put(created.id(), json.writeValueAsString(stored));
        assertThatThrownBy(() -> service.publish(created.id(), created.revision(), "7")).isInstanceOf(ApiException.class)
                .hasMessageContaining("model_time_pricing_scope_invalid");
        assertThat(mapper.history(created.id())).isEmpty();
    }

    private static TimePricingPolicy timePolicy() {
        return TimePricingTestPolicies.configured(List.of(new TimePricingPolicy.Scope(
                "deepseek", "deepseek-api", "2026-09-13", "deepseek-flash")));
    }

    private static TimePricingPolicy unrestrictedTimePolicy() {
        return TimePricingTestPolicies.configured(List.of());
    }

    @Test void cacheCostsSurviveUnsavedPreviewCredentialReusePublicationAndRollback() {
        var service = service(new InMemoryMapper());
        var read = new ModelCachePricing(com.skillforge.core.billing.pricing.CacheCostPrice.Mode.CACHE_READ,
                new BigDecimal("0.123456"), null);
        var request = cacheWrite(null, "synthetic-key", read);
        assertThat(service.previewForProbe(null, request).cachePricing()).isEqualTo(read);
        assertThat(service.list()).isEmpty();
        var created = service.create(request, "7");
        assertThat(created.cachePricing()).isEqualTo(read);
        service.publish(created.id(), created.revision(), "7");
        var original = service.readRevision(created.id(), 1);
        var changed = new ModelCachePricing(com.skillforge.core.billing.pricing.CacheCostPrice.Mode.CACHE_READ_WRITE,
                null, BigDecimal.ZERO);
        var edited = cacheWrite(created.revision(), null, changed);
        var preview = service.previewForProbe(created.id(), edited);
        assertThat(preview.cachePricing()).isEqualTo(changed);
        assertThat(preview.apiKeyEncrypted()).isEqualTo(original.apiKeyEncrypted());
        assertThat(service.detail(created.id()).cachePricing()).isEqualTo(read);
        var updated = service.update(created.id(), edited, "7");
        service.publish(created.id(), updated.revision(), "7");
        assertThat(service.current(created.id()).cachePricing()).isEqualTo(changed);
        assertThat(service.readRevision(created.id(), 1).cachePricing()).isEqualTo(read);
        assertThat(service.history(created.id())).extracting(ModelView::cachePricing).containsExactly(changed, read);
        var restored = service.rollback(created.id(), 1, updated.revision(), "7");
        assertThat(restored.cachePricing()).isEqualTo(read);
        assertThat(service.current(created.id()).cachePricing()).isEqualTo(changed);
        service.publish(created.id(), restored.revision(), "7");
        assertThat(service.current(created.id()).cachePricing()).isEqualTo(read);
        var flat = service.update(created.id(), cacheWrite(restored.revision(), null, null), "7");
        assertThat(flat.cachePricing()).isNull();
        service.publish(created.id(), flat.revision(), "7");
        assertThat(service.current(created.id()).cachePricing()).isNull();
        assertThat(service.readRevision(created.id(), 1).cachePricing()).isEqualTo(read);
    }

    @Test void cachePricesAreRejectedOnNonLlmKinds() throws Exception {
        var service = service(new InMemoryMapper());
        var json = new ObjectMapper();
        var cache = new ModelCachePricing(com.skillforge.core.billing.pricing.CacheCostPrice.Mode.CACHE_READ, null, null);
        for (var request : List.of(write(null, "Embedding", true, "http://127.0.0.1:11434/v1", "synthetic-key"),
                rerankWrite(null, null), mediaWrite())) {
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(request);
            tree.set("cache_pricing", json.valueToTree(cache));
            var invalid = json.treeToValue(tree, ModelWrite.class);
            assertThatThrownBy(() -> service.previewForProbe(null, invalid))
                    .isInstanceOf(ApiException.class).hasMessageContaining("model_cache_pricing_kind_invalid");
            assertThatThrownBy(() -> service.create(invalid, "7"))
                    .isInstanceOf(ApiException.class).hasMessageContaining("model_cache_pricing_kind_invalid");
        }
        assertThat(service.list()).isEmpty();
    }

    private ModelWrite cacheWrite(Long revision, String key, ModelCachePricing cache) {
        return cacheWrite(revision, key, cache, null);
    }

    private ModelWrite cacheWrite(Long revision, String key, ModelCachePricing cache, TimePricingPolicy policy) {
        return new ModelWrite(revision, "缓存成本", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key",
                "https://api.deepseek.com", "deepseek-flash", key, 8192, 64000, 4096, null, "DISABLED", 90,
                null, null, BigDecimal.TEN, new BigDecimal("20"), null, "CNY",
                com.skillforge.runtime.api.media.ModelImagePolicy.textOnly(),
                com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified(), null, cache, policy);
    }

    @Test void explicitMediaCostSurvivesDraftPublishProbeAndHistoricalRevision() throws Exception {
        var service = service(new InMemoryMapper());
        var json = new ObjectMapper();
        var request = mediaWrite();
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(request);
        var pricing = new MediaCostPricing(Unit.OUTPUT_IMAGE, new BigDecimal("0.123456"), null, null, null);
        tree.set("media_pricing", json.valueToTree(pricing));
        var configured = json.treeToValue(tree, ModelWrite.class);
        var preview = service.previewForProbe(null, configured);
        assertThat(preview.mediaPricing()).isEqualTo(pricing);
        assertThat(service.list()).isEmpty();
        var created = service.create(configured, "7");
        assertThat(created.mediaPricing()).isEqualTo(pricing);
        service.publish(created.id(), created.revision(), "7");
        assertThat(service.current(created.id()).mediaPricing()).isEqualTo(pricing);
        assertThat(service.history(created.id()).getFirst().mediaPricing()).isEqualTo(pricing);
        tree.put("expectedRevision", created.revision());
        tree.putNull("apiKey");
        tree.set("media_pricing", json.valueToTree(new MediaCostPricing(Unit.OUTPUT_IMAGE, BigDecimal.ONE, null, null, null)));
        var edited = json.treeToValue(tree, ModelWrite.class);
        assertThat(service.previewForProbe(created.id(), edited).mediaPricing().pricePerUnitCents()).isEqualTo(BigDecimal.ONE);
        service.update(created.id(), edited, "7");
        assertThat(service.readRevision(created.id(), 1).mediaPricing()).isEqualTo(pricing);
        assertThat(service.detail(created.id()).mediaPricing().pricePerUnitCents()).isEqualTo(BigDecimal.ONE);
        assertThat(json.readTree(json.writeValueAsString(created)).path("media_pricing").path("price_per_unit_cents").textValue()).isEqualTo("0.123456");
    }

    @Test void mediaGenericPricesAndNonMediaNestedPricesAreRejectedInsteadOfIgnored() throws Exception {
        var service = service(new InMemoryMapper());
        var json = new ObjectMapper();
        var media = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(mediaWrite());
        for (String field : List.of("inputPricePerMillionCents", "outputPricePerMillionCents", "pricePerQuantityCents")) {
            var invalid = media.deepCopy(); invalid.put(field, "1");
            assertThatThrownBy(() -> service.create(json.treeToValue(invalid, ModelWrite.class), "7"))
                    .isInstanceOf(ApiException.class).hasMessageContaining("model_media_generic_price_ambiguous");
        }
        var nested = new MediaCostPricing(Unit.OUTPUT_IMAGE, BigDecimal.ONE, null, null, null);
        var llm = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(chatWrite(null, "HIGH"));
        llm.set("media_pricing", json.valueToTree(nested));
        assertThatThrownBy(() -> service.create(json.treeToValue(llm, ModelWrite.class), "7"))
                .isInstanceOf(ApiException.class).hasMessageContaining("model_media_pricing_kind_invalid");
        media.set("media_pricing", json.valueToTree(new MediaCostPricing(Unit.INPUT_AUDIO_SECOND, BigDecimal.ONE, null, null, null)));
        assertThatThrownBy(() -> service.create(json.treeToValue(media, ModelWrite.class), "7"))
                .isInstanceOf(ApiException.class).hasMessageContaining("model_media_pricing_kind_invalid");
        assertThat(service.create(mediaWrite(), "7").mediaPricing()).isNull();
    }

    private static ModelWrite mediaWrite() {
        return new ModelWrite(null, "国内图像", "IMAGE_GENERATION", true,
                "aliyun-bailian", "bailian-cn-beijing-image", "2026-10-25-cn-v1", "bailian-native",
                "bearer-api-key", "https://dashscope.aliyuncs.com", "explicit-image-model", "synthetic-key",
                null, null, null, null, null, 600, null, null, null, null, null, "CNY");
    }

    @Test void providerProjectionKeepsImageEncodingUpperBoundsSeparateFromSpecificModels() {
        var view = ModelProviderCatalogView.from(ModelProviderCatalog.standard());
        for (var provider : view.providers()) for (var service : provider.services()) {
            if (!service.modelKinds().contains(ModelKind.LLM.name())) continue;
            for (var protocol : service.protocols()) {
                assertThat(protocol.capabilities().userImages()).isEqualTo(SupportLevel.SUPPORTED.name());
                assertThat(protocol.capabilities().toolImages()).isEqualTo(SupportLevel.SUPPORTED.name());
                assertThat(protocol.imagePolicyDefaults()).isNull();
            }
        }
    }

    @Test void responsesImagesRequireExplicitPolicyAndFreezeItAtPublication() throws Exception {
        var mapper = new InMemoryMapper();
        var service = service(mapper);
        var json = new ObjectMapper();
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(chatWrite(null, "DISABLED"));
        input.put("providerId", "custom");
        input.put("serviceId", "custom-openai-compatible");
        input.put("profileVersion", "custom-v1");
        input.put("protocol", "openai-responses");
        input.put("endpoint", "http://127.0.0.1:11434/v1");
        input.put("modelId", "account-model");
        var textOnly = service.create(json.treeToValue(input, ModelWrite.class), "7");
        assertThat(textOnly.imagePolicy().enabled()).isFalse();
        var policy = new com.skillforge.runtime.api.media.ModelImagePolicy(true, true,
                4, 1024, 8192, 2048, 512, 85, 170, "explicit-image-policy.v1");
        input.set("imagePolicy", json.valueToTree(policy));
        var request = json.treeToValue(input, ModelWrite.class);
        var preview = service.previewForProbe(null, request);
        assertThat(preview.imagePolicy()).isEqualTo(policy);
        assertThat(service.list()).hasSize(1);
        var created = service.create(request, "7");
        service.publish(created.id(), created.revision(), "7");
        assertThat(service.readRevision(created.id(), 1).imagePolicy()).isEqualTo(policy);
    }

    @Test void messagesVisionUsesExplicitFixedImageBudgetAndKnownTextOnlyModelsCannotEnableImages() throws Exception {
        var service = service(new InMemoryMapper());
        var json = new ObjectMapper();
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(messagesWrite(null, "DISABLED"));
        var policy = new com.skillforge.runtime.api.media.ModelImagePolicy(true, true, 4,
                10485760, 20971520, 2048, 512, 1024, 0, "deepseek-vision-upper-bound.v1");
        input.set("imagePolicy", json.valueToTree(policy));
        var created = service.create(json.treeToValue(input, ModelWrite.class), "7");
        assertThat(created.imagePolicy()).isEqualTo(policy);
        input.put("modelId", "deepseek-v4-pro");
        assertThatThrownBy(() -> service.create(json.treeToValue(input, ModelWrite.class), "7"))
                .isInstanceOf(ApiException.class).hasMessageContaining("model_image_policy_invalid");
    }

    @Test void mediaServicesKeepTheirOwnKindAndCannotBeSelectedAsConversationModels() {
        var service = service(new InMemoryMapper());
        var request = new ModelWrite(null, "国内图像", "IMAGE_GENERATION", true,
                "aliyun-bailian", "bailian-cn-beijing-image", "2026-10-25-cn-v1", "bailian-native",
                "bearer-api-key", "https://dashscope.aliyuncs.com", "explicit-image-model", "synthetic-key",
                null, null, null, null, null, 600, null, null, null, null, null, "CNY");
        var created = service.create(request, "7");
        service.publish(created.id(), created.revision(), "7");
        assertThat(service.readRevision(created.id(), 1).kind()).isEqualTo("IMAGE_GENERATION");
        assertThatThrownBy(() -> service.select("101", new ModelSelections(created.id(), null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("platform_model_kind_mismatch");
    }

    @Test void previewsUnsavedInputWithoutCreatingOrUpdatingDraftsAndReusesOnlyMatchingCredentials() {
        var mapper = new InMemoryMapper();
        var service = service(mapper);
        var fresh = service.previewForProbe(null, chatWrite(null, "HIGH"));
        assertThat(fresh.revision()).isZero();
        assertThat(fresh.reasoning()).isEqualTo("HIGH");
        assertThat(service.list()).isEmpty();

        var created = service.create(write(null, "已保存", true, "http://127.0.0.1:11434/v1", "synthetic-key"), "7");
        var saved = service.draftForProbe(created.id());
        var preview = service.previewForProbe(created.id(),
                write(created.revision(), "未保存名称", true, saved.endpoint(), null));
        assertThat(preview.name()).isEqualTo("未保存名称");
        assertThat(preview.apiKeyEncrypted()).isEqualTo(saved.apiKeyEncrypted());
        assertThat(preview.revision()).isZero();
        assertThat(service.draftForProbe(created.id())).isEqualTo(saved);
        assertThat(mapper.history(created.id())).isEmpty();
        assertThat(mapper.current(created.id())).isNull();
        assertThatThrownBy(() -> service.previewForProbe(created.id(),
                write(created.revision(), "换地址", true, "http://127.0.0.1:11435/v1", null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("model_credential_required_for_service_change");
        assertThatThrownBy(() -> service.previewForProbe(created.id(),
                write(2L, "旧修订", true, saved.endpoint(), null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("model_revision_conflict");
        assertThat(service.draftForProbe(created.id())).isEqualTo(saved);
    }

    @Test void messagesModelCanPublishTextOnlyButCannotSilentlyEnableUnsupportedReasoning() {
        var service = service(new InMemoryMapper());
        var created = service.create(messagesWrite(null, "DISABLED"), "7");
        service.publish(created.id(), created.revision(), "7");
        var published = service.readRevision(created.id(), 1);
        assertThat(published.protocol()).isEqualTo("anthropic-messages");
        assertThat(published.dialectVersion()).isEqualTo("deepseek-messages-v1");
        assertThat(published.reasoning()).isEqualTo("DISABLED");
        assertThatThrownBy(() -> service.update(created.id(), messagesWrite(created.revision(), "MEDIUM"), "7"))
                .isInstanceOf(ApiException.class).hasMessageContaining("model_provider_profile_invalid");
        assertThat(service.readRevision(created.id(), 1)).isEqualTo(published);
    }

    @Test void reasoningEffortIsValidatedBySelectedModelAndFrozenWithoutNormalization() {
        var mapper = new InMemoryMapper();
        var service = service(mapper);
        var created = service.create(chatWrite(null, "HIGH"), "7");
        service.publish(created.id(), created.revision(), "7");
        assertThat(service.current(created.id()).reasoning()).isEqualTo("HIGH");
        assertThatThrownBy(() -> service.update(created.id(), chatWrite(created.revision(), "MEDIUM"), "7"))
                .isInstanceOf(ApiException.class).hasMessageContaining("model_provider_profile_invalid");
        assertThat(service.current(created.id()).reasoning()).isEqualTo("HIGH");
        assertThatThrownBy(() -> service.create(chatWrite(null, "high"), "7"))
                .isInstanceOf(ApiException.class).hasMessageContaining("model_reasoning_invalid");
    }

    @Test void publishRechecksFrozenDraftReasoningBeforeAdvancingThePublishedPointer() {
        var mapper = new InMemoryMapper();
        var service = service(mapper);
        var created = service.create(chatWrite(null, "HIGH"), "7");
        mapper.drafts.compute(created.id(), (id, stored) -> stored.replace("HIGH", "MEDIUM"));
        assertThatThrownBy(() -> service.publish(created.id(), created.revision(), "7"))
                .isInstanceOf(ApiException.class).hasMessageContaining("model_provider_profile_invalid");
        assertThat(mapper.current(created.id())).isNull();
        assertThat(mapper.history(created.id())).isEmpty();
    }

    @Test void compatibilityReportIsServerWrittenAndInvalidatedByEditingWithRevisionCas() {
        var mapper = new InMemoryMapper();
        var service = service(mapper);
        var created = service.create(chatWrite(null, "HIGH"), "7");
        var passed = new ModelCompatibilityReport.Check(ModelCompatibilityReport.Status.PASSED, 1, null);
        var report = new ModelCompatibilityReport(created.revision(), java.time.Instant.parse("2026-10-25T00:00:00Z"),
                ModelCompatibilityReport.Status.PASSED, passed, passed, passed, 3, null);
        service.saveCompatibilityReport(created.id(), created.revision(), report);
        assertThat(service.detail(created.id()).compatibilityReport()).isEqualTo(report);
        assertThat(service.detail(created.id()).revision()).isEqualTo(created.revision());
        var updated = service.update(created.id(), chatWrite(created.revision(), "LOW"), "7");
        assertThat(updated.compatibilityReport()).isNull();
        assertThatThrownBy(() -> service.saveCompatibilityReport(created.id(), created.revision(), report))
                .isInstanceOf(ApiException.class).hasMessageContaining("model_revision_conflict");
        assertThat(service.detail(created.id()).reasoning()).isEqualTo("LOW");
        assertThat(service.detail(created.id()).compatibilityReport()).isNull();
        assertThat(java.util.Arrays.stream(ModelWrite.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("compatibilityReport");
    }

    @Test void explicitlySelectedPrefixCapabilitiesSurvivePublishedSnapshotAndSafeProjection() throws Exception {
        var service = service(new InMemoryMapper());
        var json = new ObjectMapper();
        var prefix = new com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities(
                com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus.SUPPORTED,
                com.skillforge.runtime.api.port.ModelPort.ToolUpdateMode.NONE,
                com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus.SUPPORTED);
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(chatWrite(null, "LOW"));
        tree.set("prefixCapabilities", json.valueToTree(prefix));
        var request = json.treeToValue(tree, ModelWrite.class);
        var created = service.create(request, "7");
        service.publish(created.id(), created.revision(), "7");
        assertThat(service.current(created.id()).prefixCapabilities()).isEqualTo(prefix);
        assertThat(service.detail(created.id()).prefixCapabilities()).isEqualTo(prefix);
        assertThat(chatWrite(null, "LOW").prefixCapabilities())
                .isEqualTo(com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified());
    }

    private ModelWrite chatWrite(Long revision, String reasoning) {
        return new ModelWrite(revision, "DeepSeek 推理程度", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key",
                "https://api.deepseek.com", "deepseek-flash", "synthetic-key",
                8192, 64000, 4096, null, reasoning, 90, null, null, null, null, null, "CNY");
    }

    private ModelWrite messagesWrite(Long revision, String reasoning) {
        return new ModelWrite(revision, "Messages 文本模型", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "anthropic-messages", "anthropic-api-key",
                "https://api.deepseek.com", "deepseek-flash", "synthetic-messages-key",
                8192, 64000, 4096, 0.5, reasoning, 90, null, null, null, null, null, "CNY");
    }

    @Test void rerankCostUnitMustBeSelectedExplicitlyAndCannotMixTokenAndDocumentPrices() {
        var mapper = new InMemoryMapper();
        var service = service(mapper);
        assertThatThrownBy(() -> service.create(rerankWrite(new BigDecimal("50"), BigDecimal.ZERO), "7"))
                .isInstanceOf(ApiException.class).hasMessageContaining("rerank_price_unit_ambiguous");
        assertThat(mapper.listDrafts()).isEmpty();
        var token = service.create(rerankWrite(new BigDecimal("50"), null), "7");
        service.publish(token.id(), token.revision(), "7");
        assertThat(token.inputPricePerMillionCents()).isEqualTo(new BigDecimal("50"));
        assertThat(token.pricePerQuantityCents()).isNull();
        assertThat(service.readRevision(token.id(), 1).inputPricePerMillionCents()).isEqualTo(new BigDecimal("50"));
        assertThat(service.readRevision(token.id(), 1).pricePerQuantityCents()).isNull();
        var quantity = service.create(rerankWrite(null, new BigDecimal("2.5")), "7");
        service.publish(quantity.id(), quantity.revision(), "7");
        assertThat(quantity.inputPricePerMillionCents()).isNull();
        assertThat(quantity.pricePerQuantityCents()).isEqualTo(new BigDecimal("2.5"));
        assertThat(service.readRevision(quantity.id(), 1).inputPricePerMillionCents()).isNull();
        assertThat(service.readRevision(quantity.id(), 1).pricePerQuantityCents()).isEqualTo(new BigDecimal("2.5"));
    }

    @Test void fractionalPricesPersistInDraftAndFrozenPublishedSnapshot() {
        var mapper = new InMemoryMapper();
        var service = service(mapper);
        for (String price : List.of("12.500000", "9007199254740993.123456", "0.000001")) {
            var created = service.create(pricedWrite(new BigDecimal(price)), "7");
            service.publish(created.id(), created.revision(), "7");
            var rebuilt = service(mapper);
            assertThat(rebuilt.detail(created.id()).inputPricePerMillionCents()).isEqualByComparingTo(price);
            assertThat(rebuilt.readRevision(created.id(), 1).inputPricePerMillionCents()).isEqualByComparingTo(price);
        }
    }

    @Test void directJavaCallersCannotBypassPriceRangeAndPrecisionValidation() {
        var mapper = new InMemoryMapper();
        var service = service(mapper);
        for (var invalid : List.of("-0.1", "0.0000001", "9223372036854775808", "9223372036854775807.1")) {
            assertThatThrownBy(() -> service.create(pricedWrite(new BigDecimal(invalid)), "7"))
                    .isInstanceOf(ApiException.class).hasMessageContaining("model_price_invalid");
        }
        assertThat(mapper.listDrafts()).isEmpty();
    }

    @Test void modelKindDefaultsApplyOnlyToNewOmittedTimeoutAndPublishedVersionsStayFrozen() {
        var service = service(new InMemoryMapper());
        for (var expected : Map.of("LLM", 600, "EMBEDDING", 120, "RERANK", 60).entrySet()) {
            var created = service.create(timeoutWrite(expected.getKey(), null, null, "key"), "7");
            assertThat(created.timeoutSeconds()).isEqualTo(expected.getValue());
            service.publish(created.id(), created.revision(), "7");
            var explicit = service.update(created.id(), timeoutWrite(expected.getKey(), 73, created.revision(), ""), "7");
            assertThat(explicit.timeoutSeconds()).isEqualTo(73);
            service.publish(explicit.id(), explicit.revision(), "7");
            var omitted = service.update(explicit.id(), timeoutWrite(expected.getKey(), null, explicit.revision(), ""), "7");
            assertThat(omitted.timeoutSeconds()).isEqualTo(73);
            assertThat(service.readRevision(created.id(), created.revision()).timeoutSeconds()).isEqualTo(expected.getValue());
            assertThat(service.current(created.id()).timeoutSeconds()).isEqualTo(73);
        }
    }

    @Test void repeatedDraftSavesKeepOneIdentityAndOnlyPublishAddsHistory() {
        var service = service(new InMemoryMapper());
        var first = service.create(write(null, "Original", true, "http://127.0.0.1:11434/v1", "key"), "7");
        service.publish(first.id(), first.revision(), "7");
        var draft = service.update(first.id(), write(1L, "Second", true, "http://127.0.0.1:11434/v1", ""), "7");
        draft = service.update(first.id(), write(draft.revision(), "Third", true, "http://127.0.0.1:11434/v1", ""), "7");
        assertThat(service.list()).hasSize(1);
        assertThat(service.history(first.id())).hasSize(1);
        assertThat(service.current(first.id()).name()).isEqualTo("Original");
        var published = service.publish(first.id(), draft.revision(), "7");
        assertThat(published.id()).isEqualTo(first.id());
        assertThat(published.publishedRevision()).isEqualTo(2L);
        assertThat(service.current(first.id()).name()).isEqualTo("Third");
        assertThat(service.readRevision(first.id(), 1).name()).isEqualTo("Original");
    }
    @Test void explicitImagePolicySurvivesSnapshotAndPublicProjection() throws Exception {
        var service = service(new InMemoryMapper());
        var policy = new com.skillforge.runtime.api.media.ModelImagePolicy(true, true, 4, 1024,
                8192, 2048, 512, 85, 170, "vision.v1");
        var request = new ModelWrite(null, "Vision", "LLM", true,
                "aliyun-bailian", "bailian-cn-beijing", "2026-09-15-global-v1", "openai-chat-completions",
                "bearer-api-key", "https://dashscope.aliyuncs.com/compatible-mode/v1", "explicit-model", "secret",
                4096, 8192, 1024, null, "DEFAULT", 30, null, null,
                java.math.BigDecimal.valueOf(10L), java.math.BigDecimal.valueOf(20L), null, "CNY", policy);
        var support = ModelProviderCatalog.standard().imageSupport(request.providerId(), request.serviceId(),
                ModelProtocol.fromId(request.protocol()), request.modelId());
        assertThat(support.userImages()).isEqualTo(SupportLevel.UNVERIFIED);
        assertThat(support.toolImages()).isEqualTo(SupportLevel.UNVERIFIED);
        assertThat(support.defaults()).isNull();
        var view = service.create(request, "7");
        assertThat(view.imagePolicy()).isEqualTo(policy);
        service.publish(view.id(), view.revision(), "7");
        assertThat(service.current(view.id()).imagePolicy()).isEqualTo(policy);
        assertThat(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(view))
                .path("imagePolicy").path("version").asText()).isEqualTo("vision.v1");
        var httpJson = new ObjectMapper().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        var published = httpJson.readTree(httpJson.writeValueAsString(view));
        assertThat(published.path("image_policy").path("tool_images").asBoolean()).isTrue();
        assertThat(httpJson.readValue(httpJson.writeValueAsString(request), ModelWrite.class).imagePolicy()).isEqualTo(policy);
    }

    @Test void omittedVisionConfigurationPublishesTextOnlyPolicyEvenForVisionNamedModels() {
        var service = service(new InMemoryMapper());
        var request = new ModelWrite(null, "Vision named", "LLM", true,
                "aliyun-bailian", "bailian-cn-beijing", "2026-09-15-global-v1", "openai-chat-completions",
                "bearer-api-key", "https://dashscope.aliyuncs.com/compatible-mode/v1", "gpt-vision", "secret",
                4096, 8192, 1024, null, "DEFAULT", 30, null, null, null, null, null, "CNY");
        assertThat(service.create(request, "7").imagePolicy().enabled()).isFalse();
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void compareAndSetConflictDoesNotAppendARevision() {
        InMemoryMapper mapper = new InMemoryMapper();
        PlatformModelCatalogService service = service(mapper);
        ModelView created = service.create(write(null, "Embedding v1", true,
                "http://127.0.0.1:11434/v1", "top-secret"), "7");
        service.publish(created.id(), created.revision(), "7");
        mapper.rejectNextAdvance = true;

        assertThatThrownBy(() -> service.update(created.id(), write(1L, "Embedding v2", true,
                "http://127.0.0.1:11434/v1", ""), "7"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("model_revision_conflict");

        assertThat(service.current(created.id()).revision()).isEqualTo(1);
        assertThat(mapper.history(created.id())).hasSize(1);
    }

    @Test
    void rollbackAppendsARevisionWithoutExposingTheCredential() {
        InMemoryMapper mapper = new InMemoryMapper();
        PlatformModelCatalogService service = service(mapper);
        String secret = "top-secret";
        ModelView created = service.create(write(null, "Embedding v1", true,
                "http://127.0.0.1:11434/v1", secret), "7");
        service.publish(created.id(), created.revision(), "7");
        ModelView updated = service.update(created.id(), write(1L, "Embedding v2", true,
                "http://127.0.0.1:11434/v1", ""), "7");

        service.publish(updated.id(), updated.revision(), "7");
        ModelView restored = service.rollback(created.id(), 1, updated.revision(), "7");

        assertThat(restored.revision()).isEqualTo(3);
        assertThat(restored.name()).isEqualTo("Embedding v1");
        assertThat(restored.credentialConfigured()).isTrue();
        assertThat(service.current(created.id()).name()).isEqualTo("Embedding v2");
        assertThat(restored.hasUnpublishedChanges()).isTrue();
        service.publish(restored.id(), restored.revision(), "7");
        assertThat(service.history(created.id()))
                .extracting(ModelView::revision)
                .containsExactly(3L, 2L, 1L);
        assertThat(service.history(created.id()))
                .allSatisfy(view -> assertThat(view.toString()).doesNotContain(secret));
        assertThat(mapper.history(created.id()))
                .allSatisfy(stored -> assertThat(stored).doesNotContain(secret));
    }

    @Test
    void institutionSummaryOmitsConnectionDetailsAndCrossTenantReadsAreRejected() {
        InMemoryMapper mapper = new InMemoryMapper();
        PlatformModelCatalogService service = service(mapper);
        String endpoint = "http://127.0.0.1:11434/v1";
        String secret = "top-secret";
        var model = service.create(write(null, "Institution Embedding", true, endpoint, secret), "7");
        assertThat(service.available()).isEmpty();
        service.publish(model.id(), model.revision(), "7");

        List<PlatformModelCatalogService.ModelSummary> available = service.available();

        assertThat(available).hasSize(1);
        assertThat(available.getFirst().toString())
                .doesNotContain(endpoint, secret, "bearer-api-key", "openai");
        assertThat(PlatformModelCatalogService.ModelSummary.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("endpoint", "providerId", "serviceId", "profileVersion",
                        "protocol", "authenticationScheme", "apiKeyEncrypted");

        TenantContext.setTenantId("7");
        assertThatThrownBy(() -> service.selections("8"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("tenant_scope_mismatch");
        assertThat(mapper.boundTenants).isEmpty();
    }

    private PlatformModelCatalogService service(InMemoryMapper mapper) {
        SkillForgeProperties properties = new SkillForgeProperties();
        properties.setSecretKey("synthetic-platform-model-catalog-test-key-32");
        return new PlatformModelCatalogService(
                mapper, new CryptoService(properties), new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE),
                com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.standard(), rules(mapper));
    }

    private TimePricingRules rules(InMemoryMapper mapper) {
        var rules = org.mockito.Mockito.mock(TimePricingRules.class);
        org.mockito.Mockito.when(rules.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            TimePricingPolicy policy = call.getArgument(0);
            if (policy == null) return null;
            long id = mapper.timeRules.size() + 1L;
            mapper.timeRules.put(id, policy); return id;
        });
        org.mockito.Mockito.when(rules.read(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> mapper.timeRules.get(call.getArgument(0)));
        org.mockito.Mockito.when(rules.readAll(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> Map.copyOf(mapper.timeRules));
        return rules;
    }

    private ModelWrite write(
            Long expectedRevision, String name, boolean enabled, String endpoint, String apiKey) {
        return new ModelWrite(expectedRevision, name, "EMBEDDING", enabled,
                "custom", "custom-embedding", "custom-v1", "openai-embeddings", "bearer-api-key", endpoint,
                "text-embedding-3-small", apiKey,
                null, null, null, null, null, 30, 1_536, null,
                java.math.BigDecimal.valueOf(10L), null, null, "CNY");
    }

    private ModelWrite pricedWrite(BigDecimal price) {
        return new ModelWrite(null, "小数价格", "EMBEDDING", true,
                "custom", "custom-embedding", "custom-v1", "openai-embeddings", "bearer-api-key", "http://127.0.0.1:11434/v1",
                "text-embedding-3-small", "synthetic-key", null, null, null, null, null, 30, 1536, null,
                price, null, null, "CNY");
    }

    private ModelWrite rerankWrite(BigDecimal inputPrice, BigDecimal quantityPrice) {
        return new ModelWrite(null, "重排计价", "RERANK", true,
                "aliyun-bailian", "bailian-cn-beijing-rerank", "2026-10-25-cn-v1", "openai-rerank", "bearer-api-key",
                "https://dashscope.aliyuncs.com", "qwen3.7-text-rerank", "synthetic-key",
                null, null, null, null, null, 60, null, "/api/v1/services/rerank/text-rerank/text-rerank",
                inputPrice, null, quantityPrice, "CNY");
    }

    private ModelWrite timeoutWrite(String kind, Integer timeout, Long revision, String apiKey) {
        boolean llm = kind.equals("LLM");
        return new ModelWrite(revision, kind + " timeout", kind, true,
                llm ? "aliyun-bailian" : "custom", llm ? "bailian-cn-beijing" : kind.equals("EMBEDDING") ? "custom-embedding" : "custom-rerank",
                llm ? "2026-09-15-global-v1" : "custom-v1", llm ? "openai-chat-completions" : kind.equals("EMBEDDING") ? "openai-embeddings" : "openai-rerank",
                "bearer-api-key", llm ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : "http://127.0.0.1:11434/v1", "explicit-model", apiKey,
                llm ? 4096 : null, llm ? 8192 : null, llm ? 1024 : null, null, llm ? "DEFAULT" : null,
                timeout, kind.equals("EMBEDDING") ? 1536 : null, kind.equals("RERANK") ? "/rerank" : null,
                java.math.BigDecimal.valueOf(10L), null, null, "CNY");
    }

    private static final class InMemoryMapper implements PlatformModelMapper {
        private final Map<Long, TimePricingPolicy> timeRules = new LinkedHashMap<>();
        private final Map<String, Long> draftRules = new LinkedHashMap<>();
        private final Map<String, Map<Long, Long>> versionRules = new LinkedHashMap<>();
        @Override public Long draftRuleId(String id) { return draftRules.get(id); }
        @Override public Long revisionRuleId(String id, long revision) { return versionRules.getOrDefault(id, Map.of()).get(revision); }
        @Override public List<ModelRuleRef> draftRuleRefs() {
            return drafts.keySet().stream().filter(id -> draftRules.get(id) != null).map(id -> new ModelRuleRef(id, draftRevisions.get(id), draftRules.get(id))).toList();
        }
        @Override public List<ModelRuleRef> revisionRuleRefs(String id) {
            return versions.getOrDefault(id, Map.of()).keySet().stream().filter(rev -> revisionRuleId(id, rev) != null).map(rev -> new ModelRuleRef(id, rev, revisionRuleId(id, rev))).toList();
        }
        private final Map<String, Long> heads = new LinkedHashMap<>();
        private final Map<String, Map<Long, String>> versions = new LinkedHashMap<>();
        private final List<String> boundTenants = new ArrayList<>();
        private final Map<String, ModelSelections> selections = new LinkedHashMap<>();
        private boolean rejectNextAdvance;
        private final Map<String,String> drafts = new LinkedHashMap<>();
        private final Map<String,Long> draftRevisions = new LinkedHashMap<>();
        private final Map<String,Boolean> draftChanged = new LinkedHashMap<>();
        @Override public List<PricingDraft> pricingDrafts(String provider,String model) {
            throw new UnsupportedOperationException("target filtering tested with production mapper");
        }
        @Override public List<PlatformModelDirectorySummary> listSummaries() {
            throw new UnsupportedOperationException("summary projection tested with production mapper");
        }
        @Override public List<String> listDrafts() { return drafts.keySet().stream().map(this::draft).toList(); }
        @Override public String draft(String id) {
            if (!drafts.containsKey(id)) return null;
            return "{\"config\":" + drafts.get(id) + ",\"published_revision\":" + heads.get(id)
                + ",\"has_unpublished_changes\":" + draftChanged.get(id) + "}";
        }
        @Override public int createDraft(String id,String config,Long ruleId) {
            if (drafts.containsKey(id)) return 0;
            drafts.put(id,config); draftRevisions.put(id,1L); draftChanged.put(id,true);
            heads.put(id,null); versions.put(id,new LinkedHashMap<>()); draftRules.put(id,ruleId); return 1;
        }
        @Override public int saveDraft(String id,long expected,String config,Long ruleId) {
            if (rejectNextAdvance) { rejectNextAdvance=false; return 0; }
            if (!draftRevisions.replace(id,expected,expected+1)) return 0;
            drafts.put(id,config); draftChanged.put(id,true); draftRules.put(id,ruleId); return 1;
        }
        @Override public int saveCompatibilityReport(String id, long expected, String report) {
            if (!java.util.Objects.equals(draftRevisions.get(id), expected)) return 0;
            try {
                var json = new ObjectMapper();
                var draft = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(drafts.get(id));
                draft.set("compatibility_report", json.readTree(report));
                drafts.put(id, json.writeValueAsString(draft));
                return 1;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Override public int publishDraft(String id,long expected,long revision) {
            if (!java.util.Objects.equals(draftRevisions.get(id),expected) || !Boolean.TRUE.equals(draftChanged.get(id))) return 0;
            Long previous = heads.get(id);
            if (revision != (previous == null ? 1 : previous+1)) return 0;
            heads.put(id,revision); draftChanged.put(id,false); return 1;
        }

        @Override
        public List<String> listCurrent() {
            return heads.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .filter(entry -> entry.getValue() != null)
                    .map(entry -> versions.get(entry.getKey()).get(entry.getValue()))
                    .toList();
        }

        @Override
        public String current(String id) {
            Long revision = heads.get(id);
            return revision == null ? null : versions.getOrDefault(id, Map.of()).get(revision);
        }

        @Override
        public String revision(String id, long revision) {
            return versions.getOrDefault(id, Map.of()).get(revision);
        }

        @Override
        public List<String> history(String id) {
            return versions.getOrDefault(id, Map.of()).entrySet().stream()
                    .sorted(Map.Entry.<Long, String>comparingByKey(Comparator.reverseOrder()))
                    .map(Map.Entry::getValue)
                    .toList();
        }

        @Override
        public int append(String id, long revision, String config, String actor, Long ruleId) {
            versionRules.computeIfAbsent(id, ignored -> new LinkedHashMap<>()).put(revision, ruleId);
            return versions.computeIfAbsent(id, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(revision, config) == null ? 1 : 0;
        }

        @Override
        public String bindTenant(String tenant) {
            boundTenants.add(tenant);
            return tenant;
        }

        @Override
        public ModelSelections selections(String tenant) {
            return selections.get(tenant);
        }

        @Override
        public int saveSelections(String tenant, ModelSelections value) {
            selections.put(tenant, value);
            return 1;
        }

        @Override
        public String agentSelection(String tenant, String agent) {
            return null;
        }

        @Override
        public boolean agentExists(String tenant, String agent) {
            return false;
        }
    }
}
