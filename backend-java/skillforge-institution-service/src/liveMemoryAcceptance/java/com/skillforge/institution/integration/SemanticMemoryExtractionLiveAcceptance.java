package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.agent.systemagent.SystemAgentExecution;
import com.skillforge.core.agent.systemagent.SystemAgentService;
import com.skillforge.core.agent.systemagent.SystemAgentTypes;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.institution.integration.SemanticMemoryExtractionReports.*;
import com.skillforge.prompts.PromptDefaults;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式C采集：复用基类独占PG/Redis与正式账本接线，不读取来源业务库或复制模型执行器。 */
class SemanticMemoryExtractionLiveAcceptance extends AbstractIntegrationTest {
    private static final String PURPOSE = "memory-extraction";
    private static final String ENDPOINT = "https://api.deepseek.com";
    private static final int OUTPUT_LIMIT = 2048;
    private static final int MAX_CALLS = 32;
    private static final long RESERVATION_PER_CALL = 10_000;
    private static final long TOTAL_RESERVATION = 320_000;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration BATCH_TIMEOUT = Duration.ofMinutes(20);
    private static final SuiteHashes SUITE_HASHES = new SuiteHashes(
            "7c5e4e0794f34beb3b289bb11aec2e423625808d20604f1115cd040047ac330a",
            "6eaea619e750fcd2a5eea4edc5b4eb0e323972e983e5a2064d073e397d363879");
    @Autowired SystemAgentService systemAgents;
    @Autowired SystemAgentExecution execution;
    @Autowired JdbcTemplate jdbc;
    @Autowired CryptoService crypto;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    private ObjectMapper report;

    @DynamicPropertySource static void isolateC(DynamicPropertyRegistry registry) {
        if (System.getenv().keySet().stream().anyMatch(key -> key.startsWith("SKILLFORGE_S03_EXTERNAL_")))
            throw new IllegalStateException("SEMANTIC_C_OWNED_TESTCONTAINERS_REQUIRED");
        registry.add("sa-token.jwt-secret-key", () -> "semantic-c-isolated-auth-key-only");
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("agent-runtime.workers.auto-startup", () -> "false");
        registry.add("skillforge.code-execution.preview-port", () -> "0");
        registry.add("logging.level.root", () -> "OFF");
    }

    @Test void capturesFrozenDevAndHeldoutThroughRealSystemAgentOnce() throws Exception {
        report = SemanticMemoryExtractionEvaluation.reportJson(json);
        assertEquals("1", required("SKILLFORGE_SEMANTIC_C_LIVE_ACCEPTANCE"), "真实模型采集必须显式开启");
        Path suites = absolute("SEMANTIC_C_SUITE_ROOT"), output = absolute("SEMANTIC_C_EVIDENCE_DIR");
        String modelId = required("SEMANTIC_C_CHAT_MODEL_ID");
        assertTrue(modelId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"), "模型ID必须是已发现的明确型号");
        for (String split : List.of("dev", "heldout")) assertEquals(SUITE_HASHES.forSplit(split),
                digest(Files.readAllBytes(suites.resolve(split + ".json"))), "收费前必须绑定已冻结的人工样本");
        Path receipt = output.resolve("capture.json");
        if (Files.exists(receipt)) {
            // 原目录只读确定回执；未知批次不能换幂等键或新配置再次派发。
            Capture prior = report.readValue(Files.readAllBytes(receipt), Capture.class);
            assertEquals(modelId, prior.modelId); assertEquals(SUITE_HASHES, prior.suiteHashes);
            assertEquals("COMPLETED", prior.state, "原批次未确定完成，保留原目录，不自动重发");
            for (String split : List.of("dev", "heldout")) assertEquals("COMPLETED",
                    report.readValue(Files.readAllBytes(output.resolve(split).resolve("batch.json")), Batch.class).outcome);
            assertEquals("PASS", prior.protocolOutcome, "原矩阵已采集，原协议失败仍保留");
            return;
        }
        Files.createDirectories(output);
        try (var files = Files.list(output)) { assertTrue(files.findAny().isEmpty(), "新的C采集根目录必须为空"); }
        String key = required("SEMANTIC_C_CHAT_API_KEY");
        var capture = new Capture(); capture.startedAt = Instant.now().toString(); capture.modelId = modelId;
        capture.suiteHashes = SUITE_HASHES; capture.plannedSamples = MAX_CALLS; capture.inputRoot = suites.toString();
        capture.approvedLimits = new ApprovedLimits(MAX_CALLS, OUTPUT_LIMIT, RESERVATION_PER_CALL, TOTAL_RESERVATION,
                CALL_TIMEOUT.toSeconds(), BATCH_TIMEOUT.toSeconds());
        Files.writeString(receipt, report.writerWithDefaultPrettyPrinter().writeValueAsString(capture) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        String tenant = null; long started = System.nanoTime();
        try {
            tenant = createTenant(tenants, plans, "semantic-c-" + UUID.randomUUID());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='325' AND success", Integer.class));
            capture.databasePreflight = databaseReceipts(tenant); verifyReceipts(capture.databasePreflight, 0, List.of()); persist(receipt, capture);
            PlatformModelConfig model = installModel(modelId, key);
            var published = publishExtractor(model.id());
            String configurationRef = "system-agent-config:" + published.id() + ":" + published.publishedVersion();
            Configuration evidence = configurationEvidence(model, published, configurationRef);
            capture.tenantId = tenant; capture.configuration = evidence; persist(receipt, capture);
            for (String split : List.of("dev", "heldout")) {
                Duration remaining = BATCH_TIMEOUT.minusNanos(System.nanoTime() - started);
                int calls = capture.modelCallsAttempted; long reserved = capture.chargedOrReservedTokens;
                var limits = new SemanticMemoryExtractionEvaluation.Limits(MAX_CALLS - calls, OUTPUT_LIMIT,
                        RESERVATION_PER_CALL, TOTAL_RESERVATION - reserved, CALL_TIMEOUT, remaining, configurationRef);
                assertEquals(published.publishedVersion(), systemAgents.detail(published.id()).publishedVersion(), "两套件之间不得漂移系统任务配置");
                try {
                    SemanticMemoryExtractionEvaluation.evaluate(execution, report, suites.resolve(split + ".json"), output.resolve(split), tenant, limits, evidence);
                } finally {
                    Path batchFile = output.resolve(split).resolve("batch.json");
                    if (Files.exists(batchFile)) {
                        Batch batch = report.readValue(Files.readAllBytes(batchFile), Batch.class);
                        capture.modelCallsAttempted = calls + (batch.callsAttempted == null ? 0 : batch.callsAttempted);
                        capture.chargedOrReservedTokens = reserved + (batch.chargedOrReservedTokens == null ? 0 : batch.chargedOrReservedTokens);
                        capture.protocolFailures += batch.protocolFailures;
                        capture.protocolOutcome = capture.protocolFailures > 0 ? "FAIL" : batch.protocolOutcome;
                        capture.systemAgentCalls.addAll(batch.systemAgentCalls);
                        capture.lastSplit = split; capture.lastBatchOutcome = batch.outcome; persist(receipt, capture);
                    }
                }
            }
            assertEquals(MAX_CALLS, capture.modelCallsAttempted);
            capture.databaseReceipts = databaseReceipts(tenant); verifyReceipts(capture.databaseReceipts, MAX_CALLS, capture.systemAgentCalls);
            capture.protocolOutcome = capture.protocolFailures > 0 ? "FAIL" : "PASS";
            capture.state = "COMPLETED";
        } catch (Exception | AssertionError failure) {
            capture.state = "STOPPED"; capture.failureClass = failure.getClass().getName(); throw failure;
        } finally {
            capture.finishedAt = Instant.now().toString();
            if (tenant != null && capture.databaseReceipts == null) {
                try { capture.databaseReceipts = databaseReceipts(tenant); }
                catch (Exception unavailable) { capture.state = "STOPPED"; capture.databaseReceipts = DatabaseReceipts.unknown(unavailable); }
            }
            persist(receipt, capture);
        }
        // 原始矩阵及账本已完整保存后再执行协议gate，不能把已确定的失败改成通过或丢弃。
        assertEquals("PASS", capture.protocolOutcome, "C矩阵已完整采集，协议失败见原样本与capture回执");
    }

    private PlatformModelConfig installModel(String modelId, String key) throws Exception {
        String id = Long.toString(SnowflakeIds.nextId());
        var model = new PlatformModelConfig(id, 1, "C人工语料临时模型", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key",
                "openai-compatible-chat-v1", ENDPOINT, modelId, crypto.encrypt(key), OUTPUT_LIMIT, 131_072,
                OUTPUT_LIMIT, 0.0, "DISABLED", Math.toIntExact(CALL_TIMEOUT.toSeconds()), null, null, null, null, null, "CNY");
        String stored = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).writeValueAsString(model);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash) VALUES(1,'semantic-c-isolated-fixture','unused') ON CONFLICT(id) DO NOTHING");
            jdbc.update("INSERT INTO nw_platform_models(id,current_revision,draft,draft_changed) VALUES(CAST(? AS bigint),NULL,CAST(? AS jsonb),false)", id, stored);
            jdbc.update("INSERT INTO nw_platform_model_versions(model_id,revision,config,actor_id) VALUES(CAST(? AS bigint),1,CAST(? AS jsonb),'1')", id, stored);
            assertEquals(1, jdbc.update("UPDATE nw_platform_models SET current_revision=1 WHERE id=CAST(? AS bigint)", id));
        });
        return model;
    }

    private SystemAgentTypes.View publishExtractor(String modelId) {
        var agent = systemAgents.list().stream().filter(item -> PURPOSE.equals(item.purpose())).findFirst().orElseThrow();
        assertEquals("MODEL_TASK", agent.executionMode());
        var updated = systemAgents.update(agent.id(), agent.revision(), new SystemAgentTypes.Config(agent.draft().name(),
                agent.draft().description(), PromptDefaults.systemPurpose(PURPOSE), modelId,
                Math.toIntExact(CALL_TIMEOUT.toSeconds()), 1, List.of()), "1");
        return systemAgents.transition(updated.id(), updated.revision(), "publish", "1");
    }

    private Configuration configurationEvidence(PlatformModelConfig model, SystemAgentTypes.View agent, String reference) throws Exception {
        return new Configuration(model.id(), model.revision(), model.providerId(), model.modelId(), ENDPOINT,
                digest(report.writeValueAsBytes(ModelConfigurationFingerprint.from(model))), agent.id(), agent.publishedVersion(), reference,
                "CURRENT_REPOSITORY_PROMPT_DEFAULTS_EXPLICITLY_PUBLISHED", digest(agent.published().systemPrompt().getBytes(StandardCharsets.UTF_8)),
                "REAL_DEEPSEEK_PROVIDER_THROUGH_PRODUCTION_LLM_SERVICE",
                "PRODUCTION_EXTERNAL_INVOCATION_GUARD_AUXILIARY_MODEL_BUDGETS_COST_RECORDER_IN_ISOLATED_PG",
                "CONTROLLED_RUNNER_COUNTERS_AND_REAL_PER_CALL_MAINTENANCE_BUDGET_ROOT", "NOT_RUN", "UNKNOWN_NO_SUPPLIER_PRICE_APPROVED");
    }

    /** 固定投影在JDBC边界一次映射为本域record；同事务绑定租户，不改变连接权限。 */
    private DatabaseReceipts databaseReceipts(String tenant) {
        return new TransactionTemplate(transactions).execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant);
            return new DatabaseReceipts(
                    jdbc.query("SELECT id,status,input_tokens,output_tokens,error_code FROM nw_platform_system_agent_runs WHERE tenant_id=CAST(? AS bigint) ORDER BY id",
                            (r, i) -> new SystemAgentRun(r.getLong(1), r.getString(2), r.getObject(3, Long.class), r.getObject(4, Long.class), r.getString(5)), tenant),
                    jdbc.query("SELECT invocation_id,attempt_id,status,operation_type,error_code FROM nw_external_invocations WHERE tenant_id=CAST(? AS bigint) ORDER BY id",
                            (r, i) -> new ExternalInvocation(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)), tenant),
                    jdbc.query("SELECT event_id,attempt_id,usage_event_kind,usage_source,prompt_tokens,completion_tokens FROM nw_usage_events WHERE tenant_id=CAST(? AS bigint) ORDER BY id",
                            (r, i) -> new UsageEvent(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getObject(5, Long.class), r.getObject(6, Long.class)), tenant),
                    jdbc.query("SELECT attempt_id,category,state FROM agent_runtime_budget_attempts WHERE tenant_id=? ORDER BY attempt_id",
                            (r, i) -> new BudgetAttempt(r.getString(1), r.getString(2), r.getString(3)), tenant), null, null);
        });
    }

    static void verifyReceipts(DatabaseReceipts receipts, int expectedCalls, List<SystemAgentCall> calls) {
        assertEquals(expectedCalls, receipts.systemAgentRuns().size(), "systemAgentRuns");
        assertEquals(expectedCalls, receipts.externalInvocations().size(), "externalInvocations");
        assertEquals(expectedCalls, receipts.budgetAttempts().size(), "budgetAttempts");
        assertEquals(expectedCalls, calls.size(), "captured systemAgentCalls");
        if (expectedCalls == 0) assertTrue(receipts.usageEvents().isEmpty(), "preflight usageEvents");
        Map<String, SystemAgentCall> byRun = calls.stream().collect(Collectors.toMap(SystemAgentCall::runId, Function.identity()));
        for (var run : receipts.systemAgentRuns()) {
            var captured = byRun.get(Long.toString(run.id())); assertNotNull(captured, "数据库Run必须属于本轮实际回执");
            assertEquals(captured.status(), run.status()); assertTrue(List.of("COMPLETED", "FAILED").contains(run.status()));
            assertEquals(captured.errorCode(), run.errorCode());
            assertNotNull(run.inputTokens()); assertNotNull(run.outputTokens());
            assertEquals(captured.usage().inputTokens(), run.inputTokens()); assertEquals(captured.usage().outputTokens(), run.outputTokens());
        }
        var dispatched = new HashSet<String>();
        var byInvocation = receipts.externalInvocations().stream().collect(Collectors.toMap(ExternalInvocation::invocationId, Function.identity()));
        for (var invocation : receipts.externalInvocations()) {
            assertEquals("SUCCEEDED", invocation.status()); assertTrue(dispatched.add(invocation.attemptId()));
        }
        var settled = new HashSet<String>();
        for (var attempt : receipts.budgetAttempts()) {
            assertEquals("SETTLED", attempt.state()); assertTrue(settled.add(attempt.attemptId()));
        }
        var measured = new HashSet<String>();
        var measuredUsage = new java.util.HashMap<String, UsageEvent>();
        for (var usage : receipts.usageEvents()) {
            if (!"measured".equals(usage.usageEventKind())) continue;
            assertEquals("PROVIDER_REPORTED", usage.usageSource()); assertNotNull(usage.promptTokens()); assertNotNull(usage.completionTokens());
            assertTrue(measured.add(usage.attemptId()));
            measuredUsage.put(usage.attemptId(), usage);
        }
        assertEquals(expectedCalls, measured.size(), "每次真实尝试均有原始用量事实");
        assertEquals(dispatched, settled, "外调与预算使用同一attempt身份");
        assertEquals(dispatched, measured, "外调与计量使用同一attempt身份");
        for (var call : calls) {
            var invocation = byInvocation.get(call.externalInvocationId()); assertNotNull(invocation, "每个Run必须关联自己的原始外调");
            var usage = measuredUsage.get(invocation.attemptId()); assertNotNull(usage);
            assertEquals(call.usage().inputTokens(), usage.promptTokens()); assertEquals(call.usage().outputTokens(), usage.completionTokens());
        }
    }
    private void persist(Path path, Capture value) throws Exception {
        Files.writeString(path, report.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardCharsets.UTF_8);
    }
    private static String required(String name) {
        String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少显式参数 " + name); return value;
    }
    private static Path absolute(String name) {
        Path path = Path.of(required(name)); if (!path.isAbsolute()) throw new IllegalArgumentException("必须使用绝对路径 " + name); return path.normalize();
    }
    private static String digest(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
}
