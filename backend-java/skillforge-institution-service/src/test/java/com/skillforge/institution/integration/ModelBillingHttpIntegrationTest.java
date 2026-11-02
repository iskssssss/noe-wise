package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.invocation.ExternalInvocationScope;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.billing.pricing.ModelPriceSource;
import com.skillforge.core.billing.pricing.CacheCostPrice.Mode;
import com.skillforge.core.billing.pricing.TimePricingPolicy;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.ModelCachePricing;
import com.skillforge.core.model.ModelSelections;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 真实宿主HTTP、登录和Foundation授权边界；底层受限PG投影由ModelBillingDatabaseTest集中验证。 */
class ModelBillingHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String PASSWORD = "SyntheticModelBilling123";
    private static final Instant START = Instant.parse("2024-01-31T16:00:00Z");
    private static final Instant END = Instant.parse("2024-02-29T16:00:00Z");
    private static final AtomicLong IDS = new AtomicLong(9_007_199_254_741_200L);
    private static final String TIMED_USAGE_COLUMNS = "id,event_id,invocation_id,attempt_id,root_input_id,run_id,prompt_tokens,cached_input_tokens,cache_write_input_tokens,completion_tokens,reasoning_output_tokens,tokens,usage_source,usage_snapshot_revision,input_amount,output_amount,total_amount,amount_status,exchange_rate_to_cny,pricing_model_id,pricing_model_revision,pricing_snapshot_version";
    @LocalServerPort private int port;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private TenantMemberMapper members;
    @Autowired private PasswordEncoder passwords;
    @Autowired private CostEventMapper events;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private CostRecorder costs;
    @Autowired private ExternalInvocationLedger invocationLedger;
    @Autowired private EndUserMapper endUsers;
    @Autowired private AgentRuntimeHostProvisioner provisioner;
    @Autowired private RunSettingsService runSettings;
    @Autowired private ModelPriceSource prices;
    @Autowired private JdbcModelBindings modelBindings;
    @Autowired private AgentRuntimeProperties runtime;
    @Autowired private ApplicationContext application;
    @MockitoSpyBean private ModelProviderRuntime provider;

    @DynamicPropertySource static void workers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void monthlyHttpUsesCurrentIdentityAndPreservesUnknownCostPrecisionAndPaginationWithoutWrites() throws Exception {
        var own = account(); var other = account();
        TenantContext.setTenantId(own.tenant());
        var chat = event(own.tenant(), "llm.chat", START, "1.25");
        chat.setPromptTokens(9_007_199_254_740_993L); chat.setCompletionTokens(2L); chat.setTokens(9_007_199_254_740_995L); events.insert(chat);
        var speech = event(own.tenant(), "media.speech", START.plusSeconds(1), "0.08");
        speech.setPromptTokens(null); speech.setCompletionTokens(null); speech.setTokens(null); speech.setCharacters(10L);
        speech.setPricingModelId(9_007_199_254_740_994L); speech.setPricingModelRevision(3L); events.insert(speech);
        var unknown = event(own.tenant(), "media.transcription", START.plusSeconds(2), null);
        unknown.setUsageEventKind("usage_report"); unknown.setReportRevision(0L); unknown.setAmountStatus("unresolved");
        unknown.setUsageSource("UNKNOWN"); unknown.setPromptTokens(null); unknown.setCompletionTokens(null); unknown.setTokens(null); events.insert(unknown);
        events.insert(event(own.tenant(), "llm.chat", START.minusSeconds(1), "999"));
        events.insert(event(own.tenant(), "llm.chat", END, "999"));
        var audit = event(own.tenant(), "tool.call", START, null); audit.setAmountStatus("unpriced"); events.insert(audit);
        TenantContext.setTenantId(other.tenant()); events.insert(event(other.tenant(), "llm.chat", START, "999")); TenantContext.clear();
        String before = fingerprint(own.tenant());
        assertNotNull(before);

        var first = get(own.token(), "/api/v1/billing/monthly?month=2024-02&page=1&page_size=2&tenant_id=" + other.tenant(), 200);
        var second = get(own.token(), "/api/v1/billing/monthly?month=2024-02&page=2&page_size=2", 200);
        assertEquals("2024-02", first.path("month").asText()); assertEquals("CNY", first.path("currency").asText());
        assertEquals(3, first.path("model_calls").asLong()); assertEquals(3, first.path("total").asLong());
        assertEquals(1, first.path("missing_cost_count").asLong()); assertTrue(first.path("total_amount").isNull());
        assertEquals(0, new BigDecimal("0.0133").compareTo(new BigDecimal(first.path("known_amount").asText())));
        assertEquals(first.path("known_amount"), second.path("known_amount")); assertEquals(first.path("total"), second.path("total"));
        assertEquals("9007199254740993", first.path("prompt_tokens").asText());
        assertEquals(2, first.path("items").size()); assertEquals(1, second.path("items").size());
        var identities = new HashSet<String>();
        first.path("items").forEach(item -> identities.add(item.path("id").asText()));
        second.path("items").forEach(item -> identities.add(item.path("id").asText()));
        assertEquals(java.util.Set.of(chat.getId().toString(), speech.getId().toString(), unknown.getId().toString()), identities);
        JsonNode chatLine = second.path("items").get(0);
        assertTrue(chatLine.path("id").isTextual()); assertEquals("9007199254740993", chatLine.path("prompt_tokens").asText());
        assertEquals(0, new BigDecimal("0.0125").compareTo(new BigDecimal(chatLine.path("amount").asText())));
        JsonNode speechLine = first.path("items").get(1);
        assertEquals("10", speechLine.path("characters").asText()); assertEquals("9007199254740994", speechLine.path("pricing_model_id").asText());
        assertEquals(3, speechLine.path("pricing_model_revision").asLong());
        assertTrue(speechLine.path("prompt_tokens").isNull());
        assertTrue(first.path("items").get(0).path("amount").isNull());
        assertEquals("unresolved", first.path("items").get(0).path("cost_status").asText());
        assertEquals(1, get(other.token(), "/api/v1/billing/monthly?month=2024-02", 200).path("model_calls").asLong());
        get(own.token(), "/api/v1/billing/monthly?month=2024-02&page=1&page_size=200", 200);
        assertEquals(before, fingerprint(own.tenant()), "月费用及翻页必须只读取原用量事实");
    }

    @Test void realAuthenticationAndPermissionDenyAnonymousUnassignedMemberAndRemovedWriteRoutes() throws Exception {
        var own = account();
        TenantContext.setTenantId(own.tenant());
        var member = new TenantMemberDO(); member.setId(SnowflakeIds.nextId()); member.setTenantId(Long.parseLong(own.tenant()));
        member.setUsername("unassigned-model-reader"); member.setRole("developer"); member.setPermissions(List.of()); member.setStatus("active");
        member.setPasswordHash(passwords.encode(PASSWORD)); member.setCreatedAt(Instant.now()); members.insert(member); TenantContext.clear();
        String unassigned = login(own.slug(), member.getUsername());
        get(null, "/api/v1/billing/monthly?month=2024-02", 401);
        get(unassigned, "/api/v1/billing/monthly?month=2024-02", 403);
        get(own.token(), "/api/v1/billing/monthly?month=bad", 422);
        get(own.token(), "/api/v1/billing/monthly?month=2024-02&page_size=201", 422);
        get(own.token(), "/api/v1/billing/invoices", 404);
        assertEquals(404, exchange(own.token(), "POST", "/api/v1/billing/invoices/generate", Map.of("billing_period", "2024-02")).statusCode());
    }

    @Test void unpricedExternalOperationReachesAuthenticatedRecordsWithoutReadingUnrelatedPrices() throws Exception {
        var own = account();
        var other = account();
        // 配置与外部操作身份均为受控样本，不派发模型或工具请求。
        var cache = new ModelCachePricing(Mode.CACHE_READ, BigDecimal.ONE, null);
        models.create(cacheModel(null, "deepseek-flash", "synthetic-no-dispatch", cache, false), "external-pricing-fixture");
        models.create(cacheModel(null, "deepseek-flash", "synthetic-no-dispatch", cache, true), "external-pricing-fixture");
        assertThrows(IllegalStateException.class, prices::snapshot);
        String service = "inst:external-pricing-" + SnowflakeIds.nextId();
        var emptyPrice = prices.snapshot("mcp", service);
        assertTrue(emptyPrice.prices().isEmpty());
        String event = "external-pricing:" + SnowflakeIds.nextId();
        var context = CostContext.builder().eventId(event).requestId(event).rootInputId(event)
                .callPoint("external-pricing-fixture").build();
        TenantContext.setTenantId(own.tenant());
        costs.recordExternal(own.tenant(), CostType.TOOL_CALL, "mcp", service, context,
                Map.of("evidence_kind", "controlled_external_operation_no_dispatch"));
        TenantContext.clear();

        var stored = queryRows(jdbc, own.tenant(),
                "SELECT event_id,quantity,tokens,total_amount,amount_status,pricing_snapshot_version FROM nw_usage_events WHERE tenant_id=? AND event_id=?",
                Long.parseLong(own.tenant()), event);
        assertEquals(1, stored.size());
        assertEquals("unpriced", stored.getFirst().get("amount_status"));
        assertNull(stored.getFirst().get("tokens"));
        assertNull(stored.getFirst().get("total_amount"));
        String version = stored.getFirst().get("pricing_snapshot_version").toString();
        assertTrue(version.startsWith(emptyPrice.version()));
        String before = fingerprint(own.tenant());
        var records = get(own.token(), "/api/v1/billing/records", 200);
        assertEquals(1L, records.path("total").asLong());
        var item = records.path("items").get(0);
        assertEquals(event, item.path("event_id").asText());
        assertEquals("tool.call", item.path("event_type").asText());
        assertEquals("mcp", item.path("provider").asText());
        assertEquals(service, item.path("model").asText());
        assertEquals(1, item.path("quantity").asInt());
        assertEquals("unpriced", item.path("amount_status").asText());
        assertTrue(item.path("tokens").isNull());
        assertTrue(item.path("total_amount").isNull());
        assertEquals(version, item.path("pricing_snapshot_version").asText());
        assertEquals(0L, get(other.token(), "/api/v1/billing/records", 200).path("total").asLong());
        assertEquals(records, get(own.token(), "/api/v1/billing/records", 200));
        assertEquals(before, fingerprint(own.tenant()), "账单读取不能重算外部操作原用量");
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.never()).stream(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test void publishedEmbeddingAndRerankPricesReachAuthenticatedMonthlyThroughRealMetering() throws Exception {
        var own = account();
        // 单价及供应商计量均为受控样本；不发送供应商HTTP，也不直接插入已计算金额。
        var embedding = models.create(pricedModel(null, "EMBEDDING", "12.5", "synthetic-no-dispatch"), "controlled-model-cost-fixture");
        var rerank = models.create(pricedModel(null, "RERANK", "50", "synthetic-no-dispatch"), "controlled-model-cost-fixture");
        var publishedEmbedding = models.publish(embedding.id(), embedding.revision(), "controlled-model-cost-fixture");
        var publishedRerank = models.publish(rerank.id(), rerank.revision(), "controlled-model-cost-fixture");
        var embeddingRef = new ModelRevisionRef(embedding.id(), publishedEmbedding.publishedRevision());
        var rerankRef = new ModelRevisionRef(rerank.id(), publishedRerank.publishedRevision());
        assertEquals(1L, embeddingRef.revision()); assertEquals(1L, rerankRef.revision());

        var changedEmbedding = models.update(embedding.id(), pricedModel(publishedEmbedding.revision(), "EMBEDDING", "99", ""), "controlled-model-cost-fixture");
        var changedRerank = models.update(rerank.id(), pricedModel(publishedRerank.revision(), "RERANK", "999", ""), "controlled-model-cost-fixture");
        models.publish(embedding.id(), changedEmbedding.revision(), "controlled-model-cost-fixture");
        models.publish(rerank.id(), changedRerank.revision(), "controlled-model-cost-fixture");
        TenantContext.setTenantId(own.tenant());
        recordControlledModelUsage(own.tenant(), CostType.LLM_EMBEDDING, embeddingRef, 1_000_001L, 1);
        recordControlledModelUsage(own.tenant(), CostType.LLM_RERANK, rerankRef, 79L, 2);
        TenantContext.clear();

        var before = get(own.token(), "/api/v1/billing/monthly?page_size=200", 200);
        assertEquals(2L, before.path("model_calls").asLong()); assertEquals(0L, before.path("missing_cost_count").asLong());
        assertEquals("1000080", before.path("prompt_tokens").asText());
        assertTrue(before.path("completion_tokens").isNull(), "未报告输出Token不补成零");
        assertEquals(0, new BigDecimal("0.125039625").compareTo(new BigDecimal(before.path("total_amount").asText())));
        JsonNode embeddingLine = null, rerankLine = null;
        for (JsonNode item : before.path("items")) {
            if (item.path("event_type").asText().equals("llm.embedding")) embeddingLine = item;
            if (item.path("event_type").asText().equals("llm.rerank")) rerankLine = item;
            assertEquals("priced", item.path("cost_status").asText());
            assertEquals("CNY", item.path("original_currency").asText());
            assertFalse(item.path("pricing_snapshot_version").asText().isBlank());
        }
        assertNotNull(embeddingLine); assertNotNull(rerankLine);
        assertEquals(0, new BigDecimal("0.125000125").compareTo(new BigDecimal(embeddingLine.path("amount").asText())));
        assertEquals(0, new BigDecimal("0.0000395").compareTo(new BigDecimal(rerankLine.path("amount").asText())));
        assertEquals("79", rerankLine.path("prompt_tokens").asText()); assertEquals("2", rerankLine.path("quantity").asText());
        String original = fingerprint(own.tenant());
        var current = models.detail(embedding.id());
        var later = models.update(embedding.id(), pricedModel(current.revision(), "EMBEDDING", "9999", ""), "controlled-model-cost-fixture");
        models.publish(embedding.id(), later.revision(), "controlled-model-cost-fixture");
        assertEquals(before, get(own.token(), "/api/v1/billing/monthly?page_size=200", 200));
        assertEquals(original, fingerprint(own.tenant()), "改当前模型价格及月费读取不修改原用量");
    }

    /** 模型仅stream受控；原目录、正式HTTP、Runtime、报告水位及成本写入保持真实。 */
    @Test @Timeout(150)
    void cacheUsageFlowsThroughRuntimeToExactMonthlyWithoutRepricingHistoricalFacts() throws Exception {
        var own = account();
        var evidence = Files.createTempDirectory("model-cache-billing-http-");
        System.out.println("MODEL_CACHE_BILLING_HTTP_EVIDENCE " + evidence);
        var report = new LinkedHashMap<String, Object>(); report.put("status", "FAILED");
        report.put("external_boundary", "controlled_provider_stream_no_paid_supplier");
        var facts = new ArrayList<Map<String, Object>>(); report.put("cases", facts);
        var current = new AtomicReference<CacheSample>();
        var providerFailure = new AtomicReference<Throwable>();
        var providerCalls = new AtomicInteger();
        controlledCacheStream(current, providerFailure, providerCalls);
        requireRunningRuntimeWorkers(application, runtime);
        TenantContext.setTenantId(own.tenant());
        var user = new EndUserDO(); user.setId(SnowflakeIds.nextId()); user.setTenantId(Long.valueOf(own.tenant()));
        user.setDisplayName("缓存计费受控用户"); user.setStatus("active"); user.setFirstSeenAt(Instant.now());
        user.setLastActiveAt(user.getFirstSeenAt()); user.setCreatedAt(user.getFirstSeenAt()); endUsers.insert(user); TenantContext.clear();
        var cases = List.of(
                new CacheCase("read-hit", Mode.CACHE_READ, 40L, null, new BigDecimal("5"), null, "0.0017", "0.0000295"),
                new CacheCase("read-write", Mode.CACHE_READ_WRITE, 30L, 20L, new BigDecimal("5"), new BigDecimal("40"), "0.0022", "0.0000345"),
                new CacheCase("missing-read", Mode.CACHE_READ, null, null, new BigDecimal("5"), null, null, null),
                new CacheCase("missing-write", Mode.CACHE_READ_WRITE, 30L, null, new BigDecimal("5"), new BigDecimal("40"), null, null),
                new CacheCase("zero-buckets", Mode.CACHE_READ_WRITE, 0L, 0L, null, null, "0.0025", "0.0000375"));
        var publishedModels = new ArrayList<String>();
        try {
            for (var scenario : cases) {
                String modelName = "cache-http-" + scenario.key() + "-" + SnowflakeIds.nextId();
                var created = models.create(cacheModel(null, modelName, "synthetic-cache-stream-only", scenario.pricing(), false), "cache-http-fixture");
                var published = models.publish(created.id(), created.revision(), "cache-http-fixture");
                publishedModels.add(created.id());
                var reference = new ModelRevisionRef(created.id(), published.publishedRevision());
                String snapshotVersion = prices.snapshot(reference).version() + "@fx-cny-identity-v1";
                TenantContext.setTenantId(own.tenant());
                try { models.select(own.tenant(), new ModelSelections(created.id(), null, null)); provisioner.provision(own.tenant()); }
                finally { TenantContext.clear(); }
                String agent = write(own.token(), "POST", "/api/v1/agents", Map.of("agent_key", modelName, "name", "缓存成本回归",
                        "system_prompt", "简洁确认已收到受控回归输入。"), 200).path("id").asText();
                write(own.token(), "PATCH", "/api/v1/agents/update", Map.of("agent_id", agent, "payload", Map.of(
                        "model_config_id", created.id(), "execution_config", Map.of("max_business_model_calls", 3),
                        "config", Map.of("memory", Map.of("automatic_extraction", false)))), 200);
                write(own.token(), "POST", "/api/v1/agents/publish", Map.of("agent_id", agent), 200);
                String session = write(own.token(), "POST", "/api/v1/chat/sessions", Map.of("agent_id", agent, "end_user_id", user.getId()), 200)
                        .path("session_id").asText();
                String input = "cache-http-input-" + SnowflakeIds.nextId();
                current.set(new CacheSample(modelName, scenario, "缓存成本回归完成：" + scenario.key()));
                var accepted = write(own.token(), "POST", "/api/v1/chat", Map.of("session_id", session, "agent_id", agent,
                        "client_message_id", input, "content", List.of(Map.of("type", "text", "text", "确认本次缓存成本受控样本。")),
                        "context", Map.of("end_user_id", user.getId())), 202);
                String run = accepted.path("execution_id").asText(); assertFalse(run.isBlank());
                var item = new LinkedHashMap<String, Object>(); item.put("scenario", scenario); item.put("run_id", run); facts.add(item);
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() -> {
                    if (providerFailure.get() != null) throw new IllegalStateException("cache_provider_fixture_failed", providerFailure.get());
                    var runs = queryRows(jdbc, own.tenant(), "SELECT status,phase,lease_claim_id FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", own.tenant(), run);
                    if (runs.size() != 1) return false;
                    var actual = runs.getFirst(); item.put("run", actual);
                    if (List.of("SUSPENDED", "UNKNOWN", "UNCERTAIN", "FAILED", "TERMINATED", "CANCELLED").contains(actual.get("status")))
                        throw new IllegalStateException("cache_runtime_cannot_complete:" + actual.get("status") + ":" + actual.get("phase"));
                    return "COMPLETED".equals(actual.get("status")) && "FINISHED".equals(actual.get("phase")) && actual.get("lease_claim_id") == null;
                });
                var measured = queryRows(jdbc, own.tenant(), "SELECT invocation_id,attempt_id,root_input_id,run_id,prompt_tokens,cached_input_tokens,cache_write_input_tokens,completion_tokens,reasoning_output_tokens,tokens,usage_source,usage_snapshot_revision,report_revision,input_amount,output_amount,total_amount,amount_status,pricing_model_id,pricing_model_revision,pricing_snapshot_version FROM nw_usage_events WHERE tenant_id=? AND run_id=? AND event_type='llm.chat' AND usage_event_kind='measured'", Long.valueOf(own.tenant()), run);
                assertEquals(1, measured.size()); var usage = measured.getFirst(); item.put("measured", usage);
                assertEquals(input, usage.get("root_input_id")); assertEquals("PROVIDER_REPORTED", usage.get("usage_source"));
                assertEquals(100L, usage.get("prompt_tokens")); assertEquals(10L, usage.get("completion_tokens"));
                assertEquals(110L, usage.get("tokens")); assertEquals(3L, usage.get("reasoning_output_tokens"));
                assertEquals(scenario.hit(), usage.get("cached_input_tokens")); assertEquals(scenario.written(), usage.get("cache_write_input_tokens"));
                assertEquals(1L, usage.get("usage_snapshot_revision")); assertNull(usage.get("report_revision"));
                assertNull(usage.get("pricing_model_id")); assertNull(usage.get("pricing_model_revision"));
                assertEquals(snapshotVersion, usage.get("pricing_snapshot_version"));
                assertAmount(scenario.inputCents(), usage.get("input_amount")); assertAmount("0.00125", usage.get("output_amount"));
                assertAmount(scenario.yuan() == null ? null : new BigDecimal(scenario.yuan()).movePointRight(2).toPlainString(), usage.get("total_amount"));
                assertEquals(scenario.yuan() == null ? "unpriced" : "priced", usage.get("amount_status"));
                assertEquals(1, queryCount(jdbc, own.tenant(), "SELECT count(*) FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id AND t.attempt_id=a.selected_attempt WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' AND a.action_id=? AND t.attempt_id=? AND t.adopted AND t.result_kind='SUCCEEDED'", own.tenant(), run, usage.get("invocation_id"), usage.get("attempt_id")));
                assertEquals(1, queryCount(jdbc, own.tenant(), "SELECT count(*) FROM nw_usage_events WHERE tenant_id=? AND attempt_id=? AND usage_event_kind='usage_report' AND report_revision=1 AND cached_input_tokens IS NOT DISTINCT FROM ?::bigint AND cache_write_input_tokens IS NOT DISTINCT FROM ?::bigint", Long.valueOf(own.tenant()), usage.get("attempt_id"), scenario.hit(), scenario.written()));
                var requests = queryRows(jdbc, own.tenant(), "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL'", own.tenant(), run);
                assertEquals(1, requests.size()); var request = requests.getFirst(); item.put("frozen_request", request);
                var codec = new AgentExecutionCodec(new CodecLimits(runtime.getMaxPayloadCodeUnits(), runtime.getMaxPayloadNodes(), runtime.getMaxPayloadDepth()));
                var decoded = ModelRequestTestMaterials.decode(codec, new TypedPayload(request.get("schema_id").toString(), request.get("canonical_json").toString()), jdbc, own.tenant());
                var binding = modelBindings.find(decoded.request().target().binding()).orElseThrow();
                assertEquals("platform-model:" + reference.modelId() + ":" + reference.revision(), binding.sourceReference());
                TenantContext.setTenantId(own.tenant());
                try { var frozen = runSettings.requireFrozen(own.tenant(), run); assertEquals(reference.modelId(), frozen.modelId()); assertEquals(reference.revision(), frozen.modelRevision()); item.put("run_settings", frozen); }
                finally { TenantContext.clear(); }
                var monthly = get(own.token(), "/api/v1/billing/monthly?page_size=200", 200);
                var line = java.util.stream.StreamSupport.stream(monthly.path("items").spliterator(), false)
                        .filter(value -> run.equals(value.path("run_id").asText())).findFirst().orElseThrow();
                assertEquals(usage.get("invocation_id"), line.path("invocation_id").asText()); assertEquals(usage.get("attempt_id"), line.path("attempt_id").asText());
                assertTrue(line.path("pricing_model_id").isNull()); assertTrue(line.path("pricing_model_revision").isNull());
                assertEquals(snapshotVersion, line.path("pricing_snapshot_version").asText());
                if (scenario.yuan() == null) assertTrue(line.path("amount").isNull()); else assertAmount(scenario.yuan(), line.path("amount").asText());
                item.put("monthly_line", line);
            }
            assertEquals(cases.size(), providerCalls.get());
            var before = get(own.token(), "/api/v1/billing/monthly?page_size=200", 200);
            assertEquals(5, before.path("model_calls").asLong()); assertEquals(2, before.path("missing_cost_count").asLong());
            assertTrue(before.path("total_amount").isNull()); assertAmount("0.0001015", before.path("known_amount").asText());
            assertEquals("500", before.path("prompt_tokens").asText()); assertEquals("50", before.path("completion_tokens").asText());
            String original = fingerprint(own.tenant());
            for (int index = 0; index < publishedModels.size(); index++) {
                String id = publishedModels.get(index); var originalModel = models.detail(id);
                var changed = models.update(id, cacheModel(originalModel.revision(), originalModel.modelId(), "", cases.get(index).pricing(), true), "cache-http-fixture");
                models.publish(id, changed.revision(), "cache-http-fixture");
                assertNotEquals(prices.snapshot(new ModelRevisionRef(id, 1)).version(), prices.snapshot(new ModelRevisionRef(id, 2)).version());
            }
            assertEquals(before, get(own.token(), "/api/v1/billing/monthly?page_size=200", 200));
            assertEquals(original, fingerprint(own.tenant()), "新价发布及月费查询均不得重写原用量、缓存数量、报告水位和成本");
            assertEquals(cases.size(), providerCalls.get()); report.put("monthly", before); report.put("status", "PASSED");
        } finally {
            report.put("provider_calls", providerCalls.get());
            Files.writeString(evidence.resolve("report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }

    /** 在原调用已开始、usage尚未返回时改发规则；受控Ledger样本不冒充供应商网络到达时间。 */
    @Test @Timeout(120)
    void timePricingUsesOriginalRuntimeAndControlledLedgerStartsWithoutRepricingHistory() throws Exception {
        var own = account();
        var evidence = Files.createTempDirectory("model-time-billing-http-");
        System.out.println("MODEL_TIME_BILLING_HTTP_EVIDENCE " + evidence);
        var report = new LinkedHashMap<String, Object>(); report.put("status", "FAILED");
        report.put("evidence_boundary", "two_real_runtime_runs_with_controlled_stream_and_six_controlled_ledger_start_inputs_no_paid_supplier");
        var facts = new ArrayList<Map<String, Object>>(); report.put("cases", facts);
        var created = models.create(timedModel(null, false), "time-http-fixture");
        var publication = models.publish(created.id(), created.revision(), "time-http-fixture");
        var reference = new ModelRevisionRef(created.id(), publication.publishedRevision());
        var current = new AtomicReference<CacheSample>(new CacheSample("deepseek-flash",
                new CacheCase("time-runtime", Mode.CACHE_READ, 40L, null, new BigDecimal("5"), null, "0.0017", "0.0000295"), "峰谷成本回归完成"));
        var providerFailure = new AtomicReference<Throwable>(); var providerCalls = new AtomicInteger();
        var nextReference = new AtomicReference<ModelRevisionRef>();
        controlledCacheStream(current, providerFailure, providerCalls, () -> {
            if (nextReference.get() != null) return;
            assertEquals(1, queryCount(jdbc, own.tenant(), "SELECT count(*) FROM nw_external_invocations WHERE tenant_id=? AND provider='deepseek' AND service='deepseek-api' AND status='STARTED'", Long.valueOf(own.tenant())),
                    "模型发布发生在原外调账本开始之后、返回usage之前");
            var changed = models.update(created.id(), timedModel(publication.revision(), true), "time-http-fixture");
            var published = models.publish(created.id(), changed.revision(), "time-http-fixture");
            nextReference.set(new ModelRevisionRef(created.id(), published.publishedRevision()));
        });
        try {
            TenantContext.setTenantId(own.tenant());
            var user = new EndUserDO(); user.setId(SnowflakeIds.nextId()); user.setTenantId(Long.valueOf(own.tenant()));
            user.setDisplayName("峰谷计费受控用户"); user.setStatus("active"); user.setFirstSeenAt(Instant.now());
            user.setLastActiveAt(user.getFirstSeenAt()); user.setCreatedAt(user.getFirstSeenAt());
            try { endUsers.insert(user); models.select(own.tenant(), new ModelSelections(created.id(), null, null)); provisioner.provision(own.tenant()); }
            finally { TenantContext.clear(); }
            requireRunningRuntimeWorkers(application, runtime);
            String agent = write(own.token(), "POST", "/api/v1/agents", Map.of("agent_key", "time-http-" + SnowflakeIds.nextId(),
                    "name", "峰谷成本回归", "system_prompt", "简洁确认收到本次受控输入。"), 200).path("id").asText();
            write(own.token(), "PATCH", "/api/v1/agents/update", Map.of("agent_id", agent, "payload", Map.of("model_config_id", created.id(),
                    "config", Map.of("memory", Map.of("automatic_extraction", false)))), 200);
            write(own.token(), "POST", "/api/v1/agents/publish", Map.of("agent_id", agent), 200);
            String session = write(own.token(), "POST", "/api/v1/chat/sessions", Map.of("agent_id", agent, "end_user_id", user.getId()), 200).path("session_id").asText();
            var runtimeFact = timedRuntimeUsage(own, agent, session, user.getId(), reference, providerFailure, facts);
            Instant actualStart = (Instant) runtimeFact.get("started_at");
            String actualBand = expectedTimeBand(actualStart);
            @SuppressWarnings("unchecked") var actualUsage = (Map<String, Object>) runtimeFact.get("measured");
            runtimeFact.put("expected_band", actualBand); runtimeFact.put("monthly_line", assertTimedCost(own, reference, actualUsage, actualStart, actualBand));
            assertNotNull(nextReference.get()); assertEquals(2L, nextReference.get().revision());
            assertEquals(timedPolicy(false), models.readRevision(created.id(), 1).timePricing());
            assertEquals(timedPolicy(true), models.readRevision(created.id(), 2).timePricing());
            assertNotEquals(prices.snapshot(reference).version(), prices.snapshot(nextReference.get()).version());
            var nextFact = timedRuntimeUsage(own, agent, session, user.getId(), nextReference.get(), providerFailure, facts);
            @SuppressWarnings("unchecked") var nextUsage = (Map<String, Object>) nextFact.get("measured");
            nextFact.put("expected_band", "OFF_PEAK"); nextFact.put("monthly_line", assertTimedCost(own, nextReference.get(), nextUsage,
                    (Instant) nextFact.get("started_at"), "OFF_PEAK"));
            var cases = List.of(
                    new TimedCase("peak-start-boundary-crosses-noon", Instant.parse("2026-10-26T01:00:00Z"), Instant.parse("2026-10-26T04:00:00Z"), "PEAK"),
                    new TimedCase("off-peak-noon-boundary-crosses-afternoon", Instant.parse("2026-10-26T04:00:00Z"), Instant.parse("2026-10-26T06:00:00Z"), "OFF_PEAK"),
                    new TimedCase("weekday-public-holiday", Instant.parse("2026-10-01T02:00:00Z"), Instant.parse("2026-10-01T03:00:00Z"), "OFF_PEAK"),
                    new TimedCase("configured-extra-peak-saturday", Instant.parse("2026-10-10T02:00:00Z"), Instant.parse("2026-10-10T03:00:00Z"), "PEAK"),
                    new TimedCase("unsupported-2027", Instant.parse("2027-01-04T02:00:00Z"), Instant.parse("2027-01-04T03:00:00Z"), "UNKNOWN"),
                    new TimedCase("missing-start", null, null, "UNKNOWN"));
            for (var scenario : cases) {
                var item = controlledTimedUsage(own.tenant(), reference, scenario); facts.add(item);
                @SuppressWarnings("unchecked") var usage = (Map<String, Object>) item.get("measured");
                item.put("monthly_line", assertTimedCost(own, reference, usage, scenario.start(), scenario.band()));
            }
            var before = get(own.token(), "/api/v1/billing/monthly?page_size=200", 200);
            assertEquals(8, before.path("model_calls").asLong()); assertEquals("800", before.path("prompt_tokens").asText());
            assertEquals("80", before.path("completion_tokens").asText());
            assertEquals("UNKNOWN".equals(actualBand) ? 3 : 2, before.path("missing_cost_count").asLong()); assertTrue(before.path("total_amount").isNull());
            BigDecimal known = new BigDecimal("0.000095875");
            if (!"UNKNOWN".equals(actualBand)) known = known.add(new BigDecimal("PEAK".equals(actualBand) ? "0.0000295" : "0.00001475"));
            assertAmount(known.toPlainString(), before.path("known_amount").asText());
            String originalUsage = fingerprint(own.tenant()), originalLedger = invocationFingerprint(own.tenant());
            var changed = models.update(created.id(), timedModel(models.detail(created.id()).revision(), false), "time-http-fixture");
            models.publish(created.id(), changed.revision(), "time-http-fixture");
            assertEquals(before, get(own.token(), "/api/v1/billing/monthly?page_size=200", 200));
            assertEquals(originalUsage, fingerprint(own.tenant())); assertEquals(originalLedger, invocationFingerprint(own.tenant()));
            assertEquals(2, providerCalls.get()); report.put("monthly", before); report.put("status", "PASSED");
        } finally {
            report.put("provider_calls", providerCalls.get());
            Files.writeString(evidence.resolve("report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }

    private record TimedCase(String key, Instant start, Instant end, String band) { }

    private Map<String, Object> timedRuntimeUsage(Account own, String agent, String session, Long user,
            ModelRevisionRef reference, AtomicReference<Throwable> providerFailure, List<Map<String, Object>> facts) throws Exception {
        String input = "time-http-input-" + SnowflakeIds.nextId();
        String run = write(own.token(), "POST", "/api/v1/chat", Map.of("session_id", session, "agent_id", agent, "client_message_id", input,
                "content", List.of(Map.of("type", "text", "text", "确认原始起始时间控制本次峰谷费用。")), "context", Map.of("end_user_id", user)), 202)
                .path("execution_id").asText();
        assertFalse(run.isBlank());
        var fact = new LinkedHashMap<String, Object>(); fact.put("kind", "actual_runtime_start"); fact.put("run_id", run); facts.add(fact);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() -> {
            if (providerFailure.get() != null) throw new IllegalStateException("time_provider_fixture_failed", providerFailure.get());
            var rows = queryRows(jdbc, own.tenant(), "SELECT status,phase,lease_claim_id FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", own.tenant(), run);
            if (rows.size() != 1) return false;
            fact.put("run", rows.getFirst());
            if (List.of("SUSPENDED", "UNKNOWN", "UNCERTAIN", "FAILED", "TERMINATED", "CANCELLED").contains(rows.getFirst().get("status")))
                throw new IllegalStateException("time_runtime_cannot_complete:" + rows.getFirst().get("status"));
            return "COMPLETED".equals(rows.getFirst().get("status")) && "FINISHED".equals(rows.getFirst().get("phase")) && rows.getFirst().get("lease_claim_id") == null;
        });
        var usages = queryRows(jdbc, own.tenant(), "SELECT " + TIMED_USAGE_COLUMNS + " FROM nw_usage_events WHERE tenant_id=? AND run_id=? AND event_type='llm.chat' AND usage_event_kind='measured'", Long.valueOf(own.tenant()), run);
        assertEquals(1, usages.size()); var usage = usages.getFirst(); fact.put("measured", usage);
        var ledger = timedLedger(own.tenant(), usage.get("attempt_id").toString()); fact.put("ledger", ledger);
        fact.put("started_at", ((Timestamp) ledger.get("started_at")).toInstant());
        assertEquals(1L, usage.get("usage_snapshot_revision"));
        assertEquals(1, queryCount(jdbc, own.tenant(), "SELECT count(*) FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id AND t.attempt_id=a.selected_attempt WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' AND a.action_id=? AND t.attempt_id=? AND t.adopted AND t.result_kind='SUCCEEDED'", own.tenant(), run, usage.get("invocation_id"), usage.get("attempt_id")));
        var frozenRequests = queryRows(jdbc, own.tenant(), "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL'", own.tenant(), run);
        assertEquals(1, frozenRequests.size()); var frozenRequest = frozenRequests.getFirst(); fact.put("frozen_request", frozenRequest);
        var codec = new AgentExecutionCodec(new CodecLimits(runtime.getMaxPayloadCodeUnits(), runtime.getMaxPayloadNodes(), runtime.getMaxPayloadDepth()));
        var decoded = ModelRequestTestMaterials.decode(codec, new TypedPayload(frozenRequest.get("schema_id").toString(), frozenRequest.get("canonical_json").toString()), jdbc, own.tenant());
        String source = modelBindings.find(decoded.request().target().binding()).orElseThrow().sourceReference();
        assertEquals("platform-model:" + reference.modelId() + ":" + reference.revision(), source); fact.put("model_binding_source", source);
        TenantContext.setTenantId(own.tenant());
        try { var frozen = runSettings.requireFrozen(own.tenant(), run); assertEquals(reference.modelId(), frozen.modelId()); assertEquals(reference.revision(), frozen.modelRevision()); fact.put("run_settings", frozen); }
        finally { TenantContext.clear(); }
        return fact;
    }

    private ModelWrite timedModel(Long expected, boolean later) {
        return new ModelWrite(expected, "配置化峰谷成本受控模型", "LLM", true, "deepseek", "deepseek-api", "2026-09-13",
                "openai-chat-completions", "bearer-api-key", "https://api.deepseek.com", "deepseek-flash", expected == null ? "synthetic-time-stream-only" : "",
                4096, 131072, 1024, 0.2, "DISABLED", 30, null, null,
                new BigDecimal("25"), new BigDecimal("125"), null, "CNY",
                ModelImagePolicy.textOnly(), ModelPort.PromptPrefixCapabilities.unverified(), null,
                new ModelCachePricing(Mode.CACHE_READ, new BigDecimal("5"), null), timedPolicy(later));
    }

    /** 人工配置样本，不绑定供应商现行规则；新版以UTC和空峰时集合表示全天闲时。 */
    private static TimePricingPolicy timedPolicy(boolean later) {
        return new TimePricingPolicy(later ? "UTC" : "Asia/Shanghai",
                LocalDate.parse(later ? "2000-01-01" : "2026-01-01"), LocalDate.parse(later ? "2099-12-31" : "2026-12-31"),
                later ? List.of() : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                later ? List.of() : List.of(new TimePricingPolicy.Period(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                        new TimePricingPolicy.Period(LocalTime.of(14, 0), LocalTime.of(18, 0))),
                later ? List.of() : List.of(new TimePricingPolicy.DateRange(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10"))),
                later ? List.of() : List.of(new TimePricingPolicy.DateRange(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"))),
                new BigDecimal(later ? "2" : "1"), new BigDecimal(later ? "0.25" : "0.5"),
                List.of(new TimePricingPolicy.Scope("deepseek", "deepseek-api", "2026-09-13", "deepseek-flash")),
                null, later ? "controlled-rule-revision-2" : "controlled-rule-revision-1", null);
    }

    private Map<String, Object> controlledTimedUsage(String tenant, ModelRevisionRef model, TimedCase scenario) {
        String invocation = "controlled-time:" + scenario.key() + ":" + SnowflakeIds.nextId(), attempt = invocation + ":attempt:1";
        var trace = new TraceContext(invocation, invocation, "controlled-time-trace", "controlled-time-span", invocation, null);
        var request = new ExternalInvocationRequest(invocation, attempt, ExternalOperationType.MODEL_CHAT, "deepseek", "deepseek-api", attempt, trace,
                Map.of("evidence_kind", "controlled_ledger_start_no_network"));
        var context = CostContext.builder().eventId(invocation + ":usage").requestId(invocation).rootInputId(invocation)
                .callPoint("controlled-time-ledger-fixture").traceContext(trace).build();
        var usage = new LlmUsage(100L, 40L, null, 10L, 3L, 110L, UsageSource.PROVIDER_REPORTED, null);
        var facts = new LinkedHashMap<String, Object>(); facts.put("kind", "controlled_ledger_input"); facts.put("scenario", scenario);
        TenantContext.setTenantId(tenant);
        try {
            if (scenario.start() != null) {
                assertTrue(invocationLedger.start(tenant, request, scenario.start()));
                assertTrue(invocationLedger.finish(tenant, attempt, ExternalInvocationStatus.SUCCEEDED, null, null, scenario.end()));
                try (var meter = MeterScope.openIsolated(context); var external = ExternalInvocationScope.open(request)) {
                    costs.recordUsageReport(tenant, context, request, "deepseek-flash", 1, usage, Map.of("evidence_kind", "controlled_ledger_input"));
                    costs.recordLlm(tenant, CostType.LLM_CHAT, "deepseek", "deepseek-flash", usage, 1, model, Map.of("evidence_kind", "controlled_ledger_input"));
                }
                var ledger = timedLedger(tenant, attempt); assertEquals(scenario.start(), ((Timestamp) ledger.get("started_at")).toInstant());
                assertEquals(scenario.end(), ((Timestamp) ledger.get("ended_at")).toInstant()); facts.put("ledger", ledger);
            } else {
                try (var meter = MeterScope.openIsolated(context)) {
                    costs.recordLlm(tenant, CostType.LLM_CHAT, "deepseek", "deepseek-flash", usage, 1, model, Map.of("evidence_kind", "missing_attempt_start_no_network"));
                }
                assertEquals(0, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_external_invocations WHERE tenant_id=? AND attempt_id=?", Long.valueOf(tenant), attempt));
            }
        } finally { TenantContext.clear(); }
        String event = scenario.start() == null ? invocation + ":usage" : attempt + ":usage";
        var rows = queryRows(jdbc, tenant, "SELECT " + TIMED_USAGE_COLUMNS + " FROM nw_usage_events WHERE tenant_id=? AND event_id=? AND usage_event_kind='measured'", Long.valueOf(tenant), event);
        assertEquals(1, rows.size()); facts.put("measured", rows.getFirst());
        assertEquals(scenario.start() == null ? null : 1L, rows.getFirst().get("usage_snapshot_revision"));
        return facts;
    }

    private Map<String, Object> timedLedger(String tenant, String attempt) {
        var rows = queryRows(jdbc, tenant, "SELECT invocation_id,attempt_id,status,started_at,ended_at FROM nw_external_invocations WHERE tenant_id=? AND attempt_id=?", Long.valueOf(tenant), attempt);
        assertEquals(1, rows.size()); assertEquals("SUCCEEDED", rows.getFirst().get("status")); return rows.getFirst();
    }

    private JsonNode assertTimedCost(Account own, ModelRevisionRef model, Map<String, Object> usage, Instant originalStart, String band) throws Exception {
        assertEquals(100L, usage.get("prompt_tokens")); assertEquals(40L, usage.get("cached_input_tokens"));
        assertEquals(10L, usage.get("completion_tokens")); assertEquals(3L, usage.get("reasoning_output_tokens")); assertEquals(110L, usage.get("tokens"));
        assertNull(usage.get("cache_write_input_tokens")); assertNull(usage.get("pricing_model_id")); assertNull(usage.get("pricing_model_revision"));
        assertEquals("PROVIDER_REPORTED", usage.get("usage_source"));
        TimePricingPolicy policy = timedPolicy(model.revision() == 2);
        String version = prices.snapshot(model).version() + "@time-" + policy.snapshotKey() + "-" + band + "-" + originalStart
                + (originalStart == null ? "" : "@fx-cny-identity-v1");
        assertEquals(version, usage.get("pricing_snapshot_version"));
        boolean unknown = "UNKNOWN".equals(band);
        BigDecimal factor = unknown ? null : model.revision() == 2 ? new BigDecimal("0.25")
                : "PEAK".equals(band) ? BigDecimal.ONE : new BigDecimal("0.5");
        assertAmount(unknown ? null : new BigDecimal("0.0017").multiply(factor).toPlainString(), usage.get("input_amount"));
        assertAmount(unknown ? null : new BigDecimal("0.00125").multiply(factor).toPlainString(), usage.get("output_amount"));
        assertAmount(unknown ? null : new BigDecimal("0.00295").multiply(factor).toPlainString(), usage.get("total_amount"));
        assertAmount(originalStart == null ? null : "1", usage.get("exchange_rate_to_cny"));
        assertEquals(unknown ? "unpriced" : "priced", usage.get("amount_status"));
        var monthly = get(own.token(), "/api/v1/billing/monthly?page_size=200", 200);
        var line = java.util.stream.StreamSupport.stream(monthly.path("items").spliterator(), false)
                .filter(item -> usage.get("id").toString().equals(item.path("id").asText())).findFirst().orElseThrow();
        assertEquals(version, line.path("pricing_snapshot_version").asText());
        assertTrue(line.path("pricing_model_id").isNull()); assertTrue(line.path("pricing_model_revision").isNull());
        if (unknown) assertTrue(line.path("amount").isNull()); else assertAmount(new BigDecimal("0.0000295").multiply(factor).toPlainString(), line.path("amount").asText());
        return line;
    }

    /** 独立验收判据来自本测试的人工配置，不调用生产分类或倍率方法。 */
    private static String expectedTimeBand(Instant startedAt) {
        if (startedAt == null) return "UNKNOWN";
        var local = startedAt.atZone(ZoneId.of("Asia/Shanghai"));
        if (local.getYear() != 2026) return "UNKNOWN";
        String date = local.toLocalDate().toString();
        if (date.equals("2026-10-01")) return "OFF_PEAK";
        if (!date.equals("2026-10-10") && local.getDayOfWeek().getValue() > 5) return "OFF_PEAK";
        int minute = local.getHour() * 60 + local.getMinute();
        return minute >= 540 && minute < 720 || minute >= 840 && minute < 1080 ? "PEAK" : "OFF_PEAK";
    }

    private String invocationFingerprint(String tenant) {
        return queryRows(jdbc, tenant, "SELECT md5(COALESCE(string_agg(to_jsonb(e)::text,',' ORDER BY id),'')) AS fingerprint FROM nw_external_invocations e WHERE tenant_id=?",
                Long.valueOf(tenant)).getFirst().get("fingerprint").toString();
    }

    private record CacheCase(String key, Mode mode, Long hit, Long written, BigDecimal readPrice, BigDecimal writePrice,
                             String inputCents, String yuan) {
        ModelCachePricing pricing() { return new ModelCachePricing(mode, readPrice, writePrice); }
    }
    private record CacheSample(String model, CacheCase scenario, String answer) { }

    private ModelWrite cacheModel(Long expected, String model, String credential, ModelCachePricing cache, boolean later) {
        return new ModelWrite(expected, "缓存成本真实链受控模型", "LLM", true, "deepseek", "deepseek-api", "2026-09-13",
                "openai-chat-completions", "bearer-api-key", "https://api.deepseek.com", model, credential,
                4096, 131072, 1024, 0.2, "DISABLED", 30, null, null,
                new BigDecimal(later ? "2500" : "25"), new BigDecimal(later ? "12500" : "125"), null, "CNY",
                ModelImagePolicy.textOnly(), ModelPort.PromptPrefixCapabilities.unverified(), null,
                later ? new ModelCachePricing(cache.mode(), new BigDecimal("500"), cache.mode() == Mode.CACHE_READ_WRITE ? new BigDecimal("4000") : null) : cache, null);
    }

    private void controlledCacheStream(AtomicReference<CacheSample> current, AtomicReference<Throwable> failure, AtomicInteger calls) {
        controlledCacheStream(current, failure, calls, () -> { });
    }

    private void controlledCacheStream(AtomicReference<CacheSample> current, AtomicReference<Throwable> failure,
            AtomicInteger calls, Runnable beforeFirstUsage) {
        org.mockito.Mockito.doAnswer(invocation -> {
            ModelProvider.InvocationRequest request = invocation.getArgument(1);
            CacheSample sample = current.get();
            return (Flow.Publisher<ModelProvider.StreamEvent>) subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                int index;
                @Override public void request(long count) {
                    if (count <= 0 || index > 1) return;
                    try {
                        if (sample == null || !sample.model().equals(request.modelId()) || !request.profile().serviceId().equals("deepseek-api"))
                            throw new IllegalStateException("cache_stream_exact_model_required");
                        var usage = new ModelPort.ModelUsage(OptionalLong.of(100), OptionalLong.of(10),
                                sample.scenario().hit() == null ? OptionalLong.empty() : OptionalLong.of(sample.scenario().hit()),
                                sample.scenario().written() == null ? OptionalLong.empty() : OptionalLong.of(sample.scenario().written()), OptionalLong.of(3));
                        if (index == 0) { index = 1; beforeFirstUsage.run(); calls.incrementAndGet(); subscriber.onNext(new ModelProvider.StreamEvent.UsageDelta(1, usage)); count--; }
                        if (count > 0 && index == 1) { index = 2;
                            subscriber.onNext(new ModelProvider.StreamEvent.Completed(new ModelProvider.InvocationResult.Completed(sample.answer(), List.of(), request.modelId(), usage, "stop")));
                            subscriber.onComplete();
                        }
                    } catch (Throwable problem) { index = 2; failure.compareAndSet(null, problem); subscriber.onError(problem); }
                }
                @Override public void cancel() { index = 2; }
            });
        }).when(provider).stream(org.mockito.ArgumentMatchers.eq("deepseek"), org.mockito.ArgumentMatchers.any());
    }

    private static void assertAmount(String expected, Object actual) {
        if (expected == null) assertNull(actual); else { assertNotNull(actual); assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString()))); }
    }

    private JsonNode write(String token, String method, String path, Object body, int status) throws Exception {
        var response = exchange(token, method, path, body); assertEquals(status, response.statusCode(), response.body());
        var envelope = json.readTree(response.body()); assertEquals("OK", envelope.path("code").asText(), response.body()); return envelope.path("data");
    }

    private ModelWrite pricedModel(Long expectedRevision, String kind, String inputPrice, String apiKey) {
        boolean embedding = kind.equals("EMBEDDING");
        return new ModelWrite(expectedRevision, "独立模型费用受控样本 " + kind, kind, true,
                "aliyun-bailian", embedding ? "bailian-cn-beijing-embedding" : "bailian-cn-beijing-rerank",
                "2026-10-25-cn-v1", embedding ? "openai-embeddings" : "openai-rerank", "bearer-api-key",
                embedding ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : "https://dashscope.aliyuncs.com",
                embedding ? "text-embedding-v4" : "qwen3.7-text-rerank", apiKey,
                null, null, null, null, null, 30, embedding ? 1536 : null,
                embedding ? null : "/api/v1/services/rerank/text-rerank/text-rerank", new BigDecimal(inputPrice), null, null, "CNY");
    }

    private void recordControlledModelUsage(String tenant, CostType type, ModelRevisionRef model, long inputTokens, int quantity) {
        String invocation = "controlled-model-cost:" + SnowflakeIds.nextId();
        var trace = new TraceContext(invocation, invocation, "controlled-cost-trace", "controlled-cost-span", invocation, null);
        var request = new ExternalInvocationRequest(invocation, invocation + ":attempt:1",
                type == CostType.LLM_EMBEDDING ? ExternalOperationType.MODEL_EMBEDDING : ExternalOperationType.MODEL_RERANK,
                "aliyun-bailian", type == CostType.LLM_EMBEDDING ? "text-embedding-v4" : "qwen3.7-text-rerank",
                invocation, trace, Map.of("evidence_kind", "controlled_provider_usage"));
        try (var meter = MeterScope.openIsolated(CostContext.builder().eventId(invocation).requestId(invocation)
                    .rootInputId(invocation).callPoint("model-cost-controlled-fixture").traceContext(trace).build());
                var attempt = ExternalInvocationScope.open(request)) {
            costs.recordLlm(tenant, type, request.provider(), request.service(),
                    new LlmUsage(inputTokens, null, null, null, null, inputTokens, UsageSource.PROVIDER_REPORTED, null),
                    quantity, model, Map.of("evidence_kind", "controlled_provider_usage"));
        }
    }

    private Account account() throws Exception {
        String slug = "model-monthly-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug); TenantContext.setTenantId(tenant);
        createTestTenantAdministrator(tenant, "model-admin", PASSWORD); TenantContext.clear();
        return new Account(tenant, slug, login(slug, "model-admin"));
    }
    private String login(String slug, String username) throws Exception {
        var response = exchange(null, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug, "username", username, "password", PASSWORD));
        assertEquals(200, response.statusCode()); String token = json.readTree(response.body()).path("data").path("access_token").asText();
        assertFalse(token.isBlank()); return token;
    }
    private JsonNode get(String token, String path, int status) throws Exception {
        var response = exchange(token, "GET", path, null); assertEquals(status, response.statusCode(), "GET " + path);
        return json.readTree(response.body()).path("data");
    }
    private HttpResponse<String> exchange(String token, String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) { return http.send(builder.build(), HttpResponse.BodyHandlers.ofString()); }
    }
    private String fingerprint(String tenant) {
        return (String) queryRows(jdbc, tenant,
                "SELECT md5(COALESCE(string_agg(to_jsonb(e)::text,',' ORDER BY id),'')) AS fingerprint FROM nw_usage_events e WHERE tenant_id=?",
                Long.parseLong(tenant)).getFirst().get("fingerprint");
    }
    private static CostEventDO event(String tenant, String type, Instant at, String amount) {
        var event = new CostEventDO(); long id = IDS.incrementAndGet(); event.setId(id); event.setTenantId(Long.parseLong(tenant));
        event.setEventId("model-http:" + id); event.setInvocationId("inv:model-http:" + id); event.setAttemptId("attempt:model-http:" + id);
        event.setEventType(type); event.setProvider("synthetic-provider"); event.setModel("synthetic-model"); event.setQuantity(1);
        event.setOccurredAt(at); event.setIngestedAt(at); event.setCreatedAt(at); event.setPromptTokens(1L); event.setCompletionTokens(1L); event.setTokens(2L);
        event.setCurrency("CNY"); event.setExchangeRateToCny(BigDecimal.ONE); event.setUsageSource("PROVIDER_REPORTED"); event.setUsageEventKind("measured");
        event.setAmountStatus(amount == null ? "unpriced" : "priced"); event.setTotalAmount(amount == null ? null : new BigDecimal(amount));
        event.setOriginKind("system"); event.setActorRef("call_point:model-billing-http-fixture"); event.setResponsibilityRef("tenant:" + tenant);
        event.setCallPoint("model-billing-http-fixture"); event.setMeta(Map.of()); event.setPricingSnapshotVersion("frozen-http-price-v1"); return event;
    }
    private record Account(String tenant, String slug, String token) { }
}
