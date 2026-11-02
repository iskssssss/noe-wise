package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.billing.pricing.CacheCostPrice.Mode;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.billing.pricing.TimePricingPolicy;
import com.skillforge.core.model.ModelCachePricing;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 独立 PostgreSQL 中验证模型小数分持久化、精确版本计价及用量列精度。 */
class PlatformModelPricePersistenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired PlatformModelCatalogService models;
    @Autowired PriceCalculator calculator;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.skillforge.core.model.PlatformModelMapper modelMapper;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @Test void timePolicyUsesRelationalConfigurationAndNewPublicationKeepsTheOldPriceRevision() throws Exception {
        var created = models.create(timeWrite(null, "synthetic-key", null), "1");
        models.publish(created.id(), created.revision(), "1");
        var original = models.readRevision(created.id(), 1);
        var usage = cacheUsage(1_000_000L, 900_000L, null, 100_000L);
        var peak = Instant.parse("2026-10-26T02:00:00Z");
        var offPeak = Instant.parse("2026-10-26T12:00:00Z");
        var originalRef = new ModelRevisionRef(created.id(), 1);
        var originalAmount = calculator.calculate(originalRef, 1L, CostType.LLM_CHAT,
                "deepseek", "deepseek-flash", usage, null, offPeak);
        assertThat(originalAmount.preciseTotal()).isEqualTo(new BigDecimal("103.600000000000"));

        var request = json.readValue(json.writeValueAsString(timeWrite(created.revision(), null,
                timePolicy())), ModelWrite.class);
        var preview = models.previewForProbe(created.id(), request);
        assertThat(preview.timePricing()).isEqualTo(timePolicy());
        assertThat(preview.apiKeyEncrypted()).isEqualTo(original.apiKeyEncrypted());
        assertThat(models.detail(created.id()).timePricing()).isNull();
        var updated = models.update(created.id(), request, "1");
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(draft, 'time_pricing') FROM nw_platform_models WHERE id=CAST(? AS bigint)",
                Boolean.class, created.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT time_pricing_rule_id IS NOT NULL FROM nw_platform_models WHERE id=CAST(? AS bigint)",
                Boolean.class, created.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(draft, 'timePricing') FROM nw_platform_models WHERE id=CAST(? AS bigint)",
                Boolean.class, created.id())).isFalse();
        models.publish(created.id(), updated.revision(), "1");
        var current = models.current(created.id());
        assertThat(current.timePricing()).isEqualTo(timePolicy());
        assertThat(current.inputPricePerMillionCents()).isEqualTo(original.inputPricePerMillionCents());
        assertThat(current.outputPricePerMillionCents()).isEqualTo(original.outputPricePerMillionCents());
        assertThat(current.cachePricing()).isEqualTo(original.cachePricing());
        assertThat(json.treeToValue(json.valueToTree(models.detail(created.id())).path("time_pricing"), TimePricingPolicy.class)).isEqualTo(timePolicy());
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(config, 'time_pricing') FROM nw_platform_model_versions WHERE model_id=CAST(? AS bigint) AND revision=2",
                Boolean.class, created.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT time_pricing_rule_id IS NOT NULL FROM nw_platform_model_versions WHERE model_id=CAST(? AS bigint) AND revision=2",
                Boolean.class, created.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT time_pricing_rule_id IS NULL FROM nw_platform_model_versions WHERE model_id=CAST(? AS bigint) AND revision=1",
                Boolean.class, created.id())).isTrue();
        var timedRef = new ModelRevisionRef(created.id(), 2);
        var busy = calculator.calculate(timedRef, 1L, CostType.LLM_CHAT,
                "deepseek", "deepseek-flash", usage, null, peak);
        var idle = calculator.calculate(timedRef, 1L, CostType.LLM_CHAT,
                "deepseek", "deepseek-flash", usage, null, offPeak);
        assertThat(busy.preciseTotal()).isEqualTo(new BigDecimal("103.600000000000"));
        assertThat(idle.preciseTotal()).isEqualTo(new BigDecimal("51.800000000000"));
        assertThat(idle.pricingSnapshotVersion()).isNotEqualTo(originalAmount.pricingSnapshotVersion());
        assertThat(calculator.calculate(originalRef, 1L, CostType.LLM_CHAT,
                "deepseek", "deepseek-flash", usage, null, offPeak)).isEqualTo(originalAmount);
        var restored = models.rollback(created.id(), 1, updated.revision(), "1");
        assertThat(restored.timePricing()).isNull();
        assertThat(models.current(created.id()).timePricing()).isEqualTo(timePolicy());
        models.publish(created.id(), restored.revision(), "1");
        assertThat(models.current(created.id()).timePricing()).isNull();
        assertThat(models.readRevision(created.id(), 2).timePricing()).isEqualTo(timePolicy());
    }

    @Test void productionMapperRejectsUnknownTimePoliciesInsteadOfDisablingThem() {
        for (String value : java.util.List.of("\"UNKNOWN_POLICY\"", "\"\"", "0", "true", "{}", "[]")) {
            assertThatThrownBy(() -> json.readValue("{\"time_pricing\":" + value + "}", ModelWrite.class))
                    .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        }
    }

    private static TimePricingPolicy timePolicy() {
        return new TimePricingPolicy("Asia/Shanghai", java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2026-12-31"),
                java.util.List.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.WEDNESDAY,
                        java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY),
                java.util.List.of(new TimePricingPolicy.Period(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(12, 0)),
                        new TimePricingPolicy.Period(java.time.LocalTime.of(14, 0), java.time.LocalTime.of(18, 0))),
                java.util.List.of(), java.util.List.of(), BigDecimal.ONE, new BigDecimal("0.5"),
                java.util.List.of(new TimePricingPolicy.Scope("deepseek", "deepseek-api", "2026-09-13", "deepseek-flash")),
                "https://pricing.example.cn/rates", "test-source-v1", null);
    }

    private ModelWrite timeWrite(Long revision, String key, TimePricingPolicy policy) {
        return new ModelWrite(revision, "DeepSeek 峰谷成本", "LLM", true, "deepseek", "deepseek-api",
                "2026-09-13", "openai-chat-completions", "bearer-api-key", "https://api.deepseek.com",
                "deepseek-flash", key, 4096, 8192, 1024, null, "DISABLED", 30, null, null,
                new BigDecimal("200"), new BigDecimal("800"), null, "CNY",
                com.skillforge.runtime.api.media.ModelImagePolicy.textOnly(),
                com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified(), null,
                new ModelCachePricing(Mode.CACHE_READ, new BigDecimal("4"), null), policy);
    }

    @Test void cacheCostContractUsesProductionJsonAndKeepsPublishedPricesFrozen() throws Exception {
        var read = new ModelCachePricing(Mode.CACHE_READ, new BigDecimal("0.123456"), null);
        // 从宿主真实 JSON 请求进入，草稿与不可变版本都使用同一正式字段名和十进制字符串。
        var request = json.readValue(json.writeValueAsString(cacheWrite(null, "synthetic-key", read)), ModelWrite.class);
        var created = models.create(request, "1");
        assertThat(models.draftForProbe(created.id()).cachePricing()).isEqualTo(read);
        assertThat(jdbc.queryForObject("SELECT draft #>> '{cache_pricing,read_price_per_million_cents}' FROM nw_platform_models WHERE id=CAST(? AS bigint)",
                String.class, created.id())).isEqualTo("0.123456");
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(draft #> '{cache_pricing,read_price_per_million_cents}') FROM nw_platform_models WHERE id=CAST(? AS bigint)",
                String.class, created.id())).isEqualTo("string");
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(draft, 'cachePricing') FROM nw_platform_models WHERE id=CAST(? AS bigint)",
                Boolean.class, created.id())).isFalse();
        models.publish(created.id(), created.revision(), "1");
        var original = models.readRevision(created.id(), 1);
        var originalRef = new ModelRevisionRef(created.id(), 1);
        var usage = cacheUsage(1_000_000L, 500_000L, 100_000L, 100_000L);
        var oldAmount = calculator.calculate(originalRef, 1L, CostType.LLM_CHAT, "provider-alias", "model-alias", usage, null, null);
        assertThat(oldAmount.preciseTotal()).isEqualTo(new BigDecimal("7.061728000000"));
        assertThat(jdbc.queryForObject("SELECT config #>> '{cache_pricing,mode}' FROM nw_platform_model_versions WHERE model_id=CAST(? AS bigint) AND revision=1",
                String.class, created.id())).isEqualTo("CACHE_READ");

        var mixed = new ModelCachePricing(Mode.CACHE_READ_WRITE, BigDecimal.ZERO, new BigDecimal("12.500000"));
        var edited = json.readValue(json.writeValueAsString(cacheWrite(created.revision(), null, mixed)), ModelWrite.class);
        var preview = models.previewForProbe(created.id(), edited);
        assertThat(preview.cachePricing()).isEqualTo(mixed);
        assertThat(preview.apiKeyEncrypted()).isEqualTo(original.apiKeyEncrypted());
        assertThat(models.detail(created.id()).cachePricing()).isEqualTo(read);
        assertThat(models.current(created.id()).cachePricing()).isEqualTo(read);
        var updated = models.update(created.id(), edited, "1");
        models.publish(created.id(), updated.revision(), "1");
        assertThat(models.current(created.id()).cachePricing()).isEqualTo(mixed);
        assertThat(models.detail(created.id()).cachePricing().readPricePerMillionCents()).isEqualTo(BigDecimal.ZERO);
        var view = json.valueToTree(models.detail(created.id()));
        assertThat(view.path("cache_pricing").path("read_price_per_million_cents").textValue()).isEqualTo("0");
        assertThat(view.path("cache_pricing").path("mode").textValue()).isEqualTo("CACHE_READ_WRITE");
        assertThat(jdbc.queryForObject("SELECT config #>> '{cache_pricing,write_price_per_million_cents}' FROM nw_platform_model_versions WHERE model_id=CAST(? AS bigint) AND revision=2",
                String.class, created.id())).isEqualTo("12.5");
        var newAmount = calculator.calculate(new ModelRevisionRef(created.id(), 2), 1L,
                CostType.LLM_CHAT, "provider-alias", "model-alias", usage, null, null);
        assertThat(newAmount.preciseTotal()).isEqualTo(new BigDecimal("7.250000000000"));
        assertThat(newAmount.pricingSnapshotVersion()).isNotEqualTo(oldAmount.pricingSnapshotVersion());
        assertThat(calculator.calculate(originalRef, 1L, CostType.LLM_CHAT,
                "provider-alias", "model-alias", usage, null, null)).isEqualTo(oldAmount);

        var unknown = new ModelCachePricing(Mode.CACHE_READ_WRITE, null, BigDecimal.ZERO);
        var unpriced = models.update(created.id(), cacheWrite(updated.revision(), null, unknown), "1");
        models.publish(created.id(), unpriced.revision(), "1");
        assertThat(models.readRevision(created.id(), 3).cachePricing()).isEqualTo(unknown);
        assertThat(jdbc.queryForObject("SELECT config #> '{cache_pricing,read_price_per_million_cents}' = 'null'::jsonb FROM nw_platform_model_versions WHERE model_id=CAST(? AS bigint) AND revision=3",
                Boolean.class, created.id())).isTrue();
        assertThat(calculator.calculate(new ModelRevisionRef(created.id(), 3), 1L,
                CostType.LLM_CHAT, "provider-alias", "model-alias", usage, null, null).preciseTotal()).isNull();
        var zero = calculator.calculate(new ModelRevisionRef(created.id(), 3), 1L,
                CostType.LLM_CHAT, "provider-alias", "model-alias", cacheUsage(1_000_000L, 0L, 1_000_000L, 0L), null, null);
        assertThat(zero.preciseTotal()).isEqualTo(new BigDecimal("0.000000000000"));

        var restored = models.rollback(created.id(), 1, unpriced.revision(), "1");
        assertThat(restored.cachePricing()).isEqualTo(read);
        assertThat(models.current(created.id()).cachePricing()).isEqualTo(unknown);
        models.publish(created.id(), restored.revision(), "1");
        assertThat(models.current(created.id()).cachePricing()).isEqualTo(read);
        assertThat(models.readRevision(created.id(), 2).cachePricing()).isEqualTo(mixed);
        assertThat(calculator.calculate(originalRef, 1L, CostType.LLM_CHAT,
                "provider-alias", "model-alias", usage, null, null)).isEqualTo(oldAmount);
    }

    @Test void nonLlmCacheCostsAreRejectedThroughTheProductionRequestContract() throws Exception {
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(embeddingWrite(null, "不适用缓存成本"));
        tree.set("cache_pricing", json.valueToTree(new ModelCachePricing(Mode.CACHE_READ, null, null)));
        var request = json.treeToValue(tree, ModelWrite.class);
        assertThatThrownBy(() -> models.previewForProbe(null, request))
                .isInstanceOf(com.skillforge.core.shared.exception.ApiException.class)
                .hasMessageContaining("model_cache_pricing_kind_invalid");
        assertThatThrownBy(() -> models.create(request, "1"))
                .isInstanceOf(com.skillforge.core.shared.exception.ApiException.class)
                .hasMessageContaining("model_cache_pricing_kind_invalid");
    }

    private ModelWrite cacheWrite(Long revision, String key, ModelCachePricing cache) {
        return new ModelWrite(revision, "缓存成本版本", "LLM", true, "custom", "custom-openai-compatible",
                "custom-v1", "openai-chat-completions", "bearer-api-key", "http://127.0.0.1:11434/v1",
                "explicit-cache-model", key, 4096, 8192, 1024, null, "DISABLED", 30,
                null, null, BigDecimal.TEN, new BigDecimal("20"), null, "CNY",
                com.skillforge.runtime.api.media.ModelImagePolicy.textOnly(),
                com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified(), null, cache, null);
    }

    private LlmUsage cacheUsage(Long input, Long read, Long write, Long output) {
        return new LlmUsage(input, read, write, output, null, null, UsageSource.PROVIDER_REPORTED, null);
    }

    @Test void responsesImagePolicySurvivesPublicationAndDraftChangesWithoutNewCredentials() {
        var policy = new com.skillforge.runtime.api.media.ModelImagePolicy(true, true, 4, 1024,
                8192, 2048, 512, 85, 170, "responses-explicit.v1");
        var created = models.create(responsesWrite(null, "synthetic-key", policy), "1");
        models.publish(created.id(), created.revision(), "1");
        var frozen = models.readRevision(created.id(), 1);
        assertThat(frozen.protocol()).isEqualTo("openai-responses");
        assertThat(frozen.imagePolicy()).isEqualTo(policy);
        var textOnly = com.skillforge.runtime.api.media.ModelImagePolicy.textOnly();
        var edited = responsesWrite(created.revision(), null, textOnly);
        var preview = models.previewForProbe(created.id(), edited);
        assertThat(preview.imagePolicy()).isEqualTo(textOnly);
        assertThat(preview.apiKeyEncrypted()).isEqualTo(frozen.apiKeyEncrypted());
        assertThat(models.detail(created.id()).imagePolicy()).isEqualTo(policy);
        var updated = models.update(created.id(), edited, "1");
        models.publish(created.id(), updated.revision(), "1");
        assertThat(models.current(created.id()).imagePolicy()).isEqualTo(textOnly);
        assertThat(models.readRevision(created.id(), 1).imagePolicy()).isEqualTo(policy);
        assertThat(models.readRevision(created.id(), 1).apiKeyEncrypted()).isEqualTo(frozen.apiKeyEncrypted());
    }

    private ModelWrite responsesWrite(Long revision, String key,
            com.skillforge.runtime.api.media.ModelImagePolicy policy) {
        return new ModelWrite(revision, "Responses 图片配置", "LLM", true, "custom", "custom-openai-compatible",
                "custom-v1", "openai-responses", "bearer-api-key", "http://127.0.0.1:11434/v1",
                "explicit-local-model", key, 4096, 8192, 1024, null, "DISABLED", 30,
                null, null, null, null, null, "CNY", policy);
    }

    @Test void compatibilityReportUsesProductionJsonAndRealDraftRevisionCas() throws Exception {
        var created = models.create(embeddingWrite(null, "报告测试"), "1");
        var passed = new com.skillforge.core.model.ModelCompatibilityReport.Check(
                com.skillforge.core.model.ModelCompatibilityReport.Status.PASSED, 7, null);
        var report = new com.skillforge.core.model.ModelCompatibilityReport(created.revision(),
                java.time.Instant.parse("2026-10-25T00:00:00Z"),
                com.skillforge.core.model.ModelCompatibilityReport.Status.PASSED,
                passed, passed, passed, 21, null);
        // 使用宿主真实 ObjectMapper，不用测试专用命名策略掩盖 JSONB 路径不一致。
        assertThat(json.valueToTree(models.draftForProbe(created.id())).has("compatibility_report")).isTrue();
        models.saveCompatibilityReport(created.id(), created.revision(), report);
        assertThat(models.detail(created.id()).compatibilityReport()).isEqualTo(report);
        assertThat(models.detail(created.id()).revision()).isEqualTo(created.revision());
        assertThat(jdbc.queryForObject("SELECT draft_revision FROM nw_platform_models WHERE id=CAST(? AS bigint)",
                Long.class, created.id())).isEqualTo(created.revision());
        assertThat(jdbc.queryForObject("SELECT draft -> 'compatibility_report' ->> 'status' FROM nw_platform_models WHERE id=CAST(? AS bigint)",
                String.class, created.id())).isEqualTo("PASSED");
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(draft, 'compatibilityReport') FROM nw_platform_models WHERE id=CAST(? AS bigint)",
                Boolean.class, created.id())).isFalse();
        var updated = models.update(created.id(), embeddingWrite(created.revision(), "编辑后"), "1");
        assertThat(updated.revision()).isEqualTo(created.revision() + 1);
        assertThat(updated.compatibilityReport()).isNull();
        assertThat(models.draftForProbe(created.id()).compatibilityReport()).isNull();
        assertThat(modelMapper.saveCompatibilityReport(created.id(), created.revision(), json.writeValueAsString(report))).isZero();
        assertThat(models.detail(created.id()).name()).isEqualTo("编辑后");
        assertThat(models.detail(created.id()).compatibilityReport()).isNull();
        assertThat(jdbc.queryForObject("SELECT draft -> 'compatibility_report' = 'null'::jsonb FROM nw_platform_models WHERE id=CAST(? AS bigint)",
                Boolean.class, created.id())).isTrue();
    }

    private ModelWrite embeddingWrite(Long revision, String name) {
        return new ModelWrite(revision, name, "EMBEDDING", true, "aliyun-bailian", "bailian-cn-beijing-embedding",
                "2026-10-25-cn-v1", "openai-embeddings", "bearer-api-key", "https://dashscope.aliyuncs.com/compatible-mode/v1", "text-embedding-v4", "synthetic-key",
                null, null, null, null, null, 30, 1536, null,
                new BigDecimal("12.5"), null, null, "CNY");
    }

    @Test void fractionalCentsSurviveModelPublicationAndTokenCostStoragePrecision() {
        var created = models.create(embeddingWrite(null, "小数分模型"), "1");
        models.publish(created.id(), created.revision(), "1");

        assertThat(models.current(created.id()).inputPricePerMillionCents()).isEqualTo(new BigDecimal("12.5"));
        assertThat(models.readRevision(created.id(), 1).inputPricePerMillionCents()).isEqualTo(new BigDecimal("12.5"));
        var amount = calculator.calculate(new ModelRevisionRef(created.id(), 1), 1L,
                CostType.LLM_EMBEDDING, "provider-alias", "model-alias", cacheUsage(1_000_001L, null, null, null), null, null);
        assertThat(amount.preciseTotal()).isEqualTo(new BigDecimal("12.500012500000"));
        assertThat(amount.total()).isEqualTo(13L);
        assertThat(jdbc.queryForObject("SELECT CAST(? AS numeric(24,12))", BigDecimal.class,
                amount.preciseTotal())).isEqualTo(new BigDecimal("12.500012500000"));
    }
}
