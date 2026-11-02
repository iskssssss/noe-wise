package com.skillforge.institution.integration;

import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit.Provider;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 当前代码、独立 PG/Redis、正式 Chat 与真实供应商；报告只输出安全计量和摘要。 */
@Import(SessionPromptCacheLiveAcceptance.LiveConfiguration.class)
class SessionPromptCacheLiveAcceptance extends AbstractIntegrationTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    static final LiveGradleAttemptMeter.Outcome liveMeterOutcome = new LiveGradleAttemptMeter.Outcome();
    @Autowired private javax.sql.DataSource liveMeterDataSource;

    private static final String PASSWORD = "LivePrefixIsolated123";
    private static final int FIXTURE_OUTPUT_LIMIT = 1024;
    private static final Path ARTIFACT_ROOT = artifactRoot();
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired TenantMemberMapper members;
    @Autowired EndUserMapper users;
    @Autowired PasswordEncoder passwords;
    @Autowired ApplicationContext application;
    @Autowired AgentRuntimeProperties runtime;
    @Autowired LivePromptCacheBudgets budgets;
    @Autowired LiveAudit audit;
    @Autowired PromptCacheRecordingHttpClient recording;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<RunEvidence> runs = new ArrayList<>();
    private String tenant, token, session, agent;
    private long user;
    private String stage = "SOURCE_READ";

    @DynamicPropertySource static void isolate(DynamicPropertyRegistry registry) {
        registry.add("skillforge.secret-key", () -> required("SECRET_KEY"));
        registry.add("sa-token.jwt-secret-key", () -> "live-prefix-isolated-auth-key");
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
        registry.add("skillforge.code-execution.preview-port", () -> "0");
        registry.add("skillforge.code-execution.preview-origin", () -> "");
        registry.add("skillforge.code-execution.artifact-root", ARTIFACT_ROOT::toString);
        registry.add("logging.level.root", () -> "OFF");
        registry.add("logging.level.com.skillforge.runtime.starter.AgentRuntimeSessionRuntime", () -> "WARN");
    }
    private static Path artifactRoot() {
        try { return Files.createTempDirectory("skillforge-live-prefix-"); }
        catch (java.io.IOException unavailable) { throw new IllegalStateException("LIVE_CACHE_ARTIFACT_ROOT_UNAVAILABLE"); }
    }
    @AfterAll static void deleteEmptyArtifactRoot() throws java.io.IOException { Files.deleteIfExists(ARTIFACT_ROOT); }

    /** 两轮真实 calculator 与六轮短答；模型自行选择迭代，不给供应商编排预制响应。 */
    @Test void eightRunsPreserveSessionPrefixAndReportEveryRealSupplierCall() throws Exception {
        Instant started = Instant.now();
        String outcome = "FAILED", failure = null;
        Source source = null;
        List<Map<String, Object>> diagnostics = List.of();
        boolean replayNoExternalCall = false, usageSettled = false;
        try {
            check("1".equals(System.getenv("SKILLFORGE_LIVE_SESSION_PROMPT_CACHE_ACCEPTANCE")), "LIVE_CACHE_EXPLICIT_GATE_REQUIRED");
            source = readSource();
            stage = "FIXTURE"; fixture(); installModel(source);
            requireRunningRuntimeWorkers(application, runtime);
            stage = "AGENT"; createAgent(); mountCalculator();
            request("/api/v1/agents/publish", "POST", Map.of("agent_id", agent), 200);
            session = request("/api/v1/chat/sessions", "POST", Map.of("agent_id", agent, "end_user_id", user), 200).path("session_id").asText();
            check(!session.isBlank(), "LIVE_CACHE_SESSION_RECEIPT_MISSING");
            audit.freeze(source.fixtureModel(), tenant, session);
            budgets.begin(tenant);
            Map<String, Object> firstBody = null;
            String firstRun = null;
            for (int index = 0; index < 8; index++) {
                ensureSendingAllowed();
                stage = "RUN_" + (index + 1) + "_SUBMIT";
                String input = java.util.UUID.randomUUID().toString();
                String expected = index == 0 ? "391" : index == 1 ? "540" : "确认完成第" + (index + 1) + "轮";
                String content = index == 0 ? "请仅使用一次 calculate_expression 真实计算17*23，然后用一句短句给出结果。"
                        : index == 1 ? "请仅使用一次 calculate_expression 真实计算45*12，然后用一句短句给出结果。"
                        : "本轮无需工具，请只回复：" + expected;
                var body = chat(input, content);
                String run = request("/api/v1/chat", "POST", body, 202).path("execution_id").asText();
                check(run.equals("run:" + input), "LIVE_CACHE_RUN_RECEIPT_MISMATCH");
                runs.add(new RunEvidence(index + 1, run, "ACCEPTED", false, 0));
                if (index == 0) { firstBody = body; firstRun = run; }
                stage = "RUN_" + (index + 1) + "_OBSERVE";
                observeRun(index + 1, run, expected, index < 2);
            }
            stage = "PERSISTED_EVIDENCE";
            waitForAllTerminals();
            int attempts = recording.actualRequestCount();
            check(attempts >= 12 && attempts <= LivePromptCacheBudgets.MAX_ATTEMPTS, "LIVE_CACHE_ACTUAL_REQUEST_COUNT_INVALID");
            diagnostics = promptDiagnostics(audit.businessCalls());
            verifyWireEvidence();
            usageSettled = verifySettledUsage();
            check(usageSettled, "LIVE_CACHE_USAGE_NOT_SETTLED");
            stage = "IDEMPOTENT_REPLAY";
            String replayRun = request("/api/v1/chat", "POST", firstBody, 202).path("execution_id").asText();
            check(firstRun.equals(replayRun), "LIVE_CACHE_REPLAY_RECEIPT_CHANGED");
            history(); history();
            // 等待宿主轮询两个周期，检查同 client_message_id 重放和只读历史没有再发送。
            Thread.sleep(500);
            replayNoExternalCall = recording.actualRequestCount() == attempts;
            check(replayNoExternalCall, "LIVE_CACHE_REPLAY_SENT_NEW_REQUEST");
            check(budgets.stopCode() == null && recording.failureCount() == 0, "LIVE_CACHE_SUPPLIER_STOPPED");
            outcome = "PASSED";
        } catch (Exception | AssertionError invalid) {
            failure = safeFailure(invalid);
            throw new AssertionError("LIVE_SESSION_PROMPT_CACHE_SEE_SAFE_EVIDENCE");
        } finally {
            // 拒绝新发送后再取消 Session；结果未知的既有请求不会被重放或释放预算。
            audit.close(); budgets.close();
            if (session != null && token != null) try { request("/api/v1/chat/sessions/close", "POST",
                    Map.of("session_id", session, "payload", Map.of("reason", "live-prefix-finished")), 200); } catch (Exception ignored) { }
            refreshRunStates();
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("suite", "SESSION_PROMPT_CACHE_REAL_HOST"); evidence.put("outcome", outcome);
            evidence.put("stage", stage); evidence.put("failureCode", failure);
            evidence.put("model", source == null ? null : source.evidence());
            evidence.put("scenario", Map.of("requestedRuns", 8, "calculatorRuns", 2, "directRuns", 6,
                    "operationalConfiguration", "NEW_DATABASE_CURRENT_DEFAULTS", "actualModelIterations", "NATURAL_SUPPLIER_DECISIONS",
                    "comparison", "SINGLE_CANDIDATE_NOT_SAME_WORKLOAD_AB"));
            evidence.put("runs", List.copyOf(runs)); evidence.put("runtimeCalls", audit.snapshot());
            evidence.put("httpCalls", recording.snapshot()); evidence.put("budget", safeBudgetReport());
            evidence.put("persistentPrefixDiagnostics", diagnostics.isEmpty() ? safeDiagnostics() : diagnostics);
            evidence.put("replayNoExternalCall", replayNoExternalCall); evidence.put("usageSettled", usageSettled);
            evidence.put("supplierCost", Map.of("state", "UNKNOWN", "reason", "NO_VERIFIED_CACHE_PRICE_OR_PROVIDER_BILLING_AMOUNT"));
            evidence.put("elapsedSeconds", Duration.between(started, Instant.now()).toSeconds());
            evidence.put("runFailureCodes", failureCodes());
            Path path = Path.of(required("LIVE_PROMPT_CACHE_EVIDENCE_FILE")).toAbsolutePath();
            Files.createDirectories(path.getParent()); json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), evidence);
        }
    }

    /** 来源只读；只复制明确指定的当前发布模型，不读取旧 Session、Agent 或运营快照。 */
    private Source readSource() {
        var mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        String id = required("LIVE_PROMPT_CACHE_SOURCE_MODEL_ID");
        long revision;
        try { revision = Long.parseLong(required("LIVE_PROMPT_CACHE_SOURCE_MODEL_REVISION")); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("LIVE_CACHE_SOURCE_REVISION_INVALID"); }
        try (var connection = DriverManager.getConnection(required("SPRING_DATASOURCE_URL"),
                required("PLATFORM_DATASOURCE_USERNAME"), required("PLATFORM_DATASOURCE_PASSWORD"))) {
            connection.setReadOnly(true); connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) { statement.execute("SET TRANSACTION READ ONLY"); }
            try (var query = connection.prepareStatement("SELECT v.config::text FROM nw_platform_models m JOIN nw_platform_model_versions v "
                    + "ON v.model_id=m.id AND v.revision=m.current_revision WHERE m.id=CAST(? AS bigint) AND m.current_revision=?")) {
                query.setString(1, id); query.setLong(2, revision);
                try (var rows = query.executeQuery()) {
                    check(rows.next(), "LIVE_CACHE_SOURCE_NOT_CURRENT_PUBLISHED");
                    ObjectNode config = (ObjectNode) mapper.readTree(rows.getString(1));
                    var original = mapper.treeToValue(config, PlatformModelConfig.class);
                    check(!rows.next() && id.equals(original.id()) && revision == original.revision() && original.enabled()
                            && "LLM".equals(original.kind()) && "deepseek".equals(original.providerId())
                            && "deepseek-api".equals(original.serviceId()) && "deepseek-flash".equals(original.modelId())
                            && "2026-09-13".equals(original.profileVersion()) && "openai-compatible-chat-v1".equals(original.dialectVersion())
                            && "DISABLED".equals(original.reasoning()) && original.maxOutputTokens() != null
                            && original.maxOutputTokens() >= FIXTURE_OUTPUT_LIMIT && original.contextWindowTokens() != null
                            && original.contextWindowTokens() > original.maxOutputTokens() && original.timeoutSeconds() != null
                            && original.timeoutSeconds() > 0 && original.timeoutSeconds() <= 120
                            && original.apiKeyEncrypted() != null && !original.apiKeyEncrypted().isBlank(), "LIVE_CACHE_SOURCE_MODEL_INVALID");
                    config.put("max_output_tokens", FIXTURE_OUTPUT_LIMIT);
                    var fixture = mapper.treeToValue(config, PlatformModelConfig.class);
                    connection.rollback(); return new Source(original, fixture, mapper.writeValueAsString(config));
                }
            }
        } catch (AssertionError identified) { throw identified; }
        catch (Exception unavailable) { throw new IllegalStateException("LIVE_CACHE_SOURCE_READ_FAILED_" + unavailable.getClass().getSimpleName()); }
    }
    private void fixture() throws Exception {
        String slug = "live-prefix-" + java.util.UUID.randomUUID(); tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        try {
            var member = new TenantMemberDO(); member.setId(SnowflakeIds.nextId()); member.setTenantId(Long.valueOf(tenant));
            member.setUsername("live-prefix-admin"); member.setRole("admin"); member.setPermissions(List.of()); member.setStatus("active");
            member.setPasswordHash(passwords.encode(PASSWORD)); member.setCreatedAt(Instant.now()); members.insert(member);
            var endUser = new EndUserDO(); endUser.setId(SnowflakeIds.nextId()); endUser.setTenantId(Long.valueOf(tenant)); endUser.setStatus("active");
            endUser.setDisplayName("隔离真实缓存验收用户"); endUser.setFirstSeenAt(Instant.now()); endUser.setLastActiveAt(Instant.now());
            endUser.setCreatedAt(Instant.now()); users.insert(endUser); user = endUser.getId();
        } finally { TenantContext.clear(); }
        token = request("/api/v1/auth/login", "POST", Map.of("tenant_slug", slug, "username", "live-prefix-admin", "password", PASSWORD), 200).path("access_token").asText();
        check(!token.isBlank(), "LIVE_CACHE_LOGIN_TOKEN_MISSING");
        jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash) VALUES(1,'live-prefix-fixture','unused') ON CONFLICT(id) DO NOTHING");
    }
    private void installModel(Source source) {
        scoped(() -> {
            jdbc.update("INSERT INTO nw_platform_models(id,current_revision,draft,draft_changed) VALUES(CAST(? AS bigint),?,CAST(? AS jsonb),false)",
                    source.fixtureModel().id(), source.fixtureModel().revision(), source.config());
            jdbc.update("INSERT INTO nw_platform_model_versions(model_id,revision,config,actor_id) VALUES(CAST(? AS bigint),?,CAST(? AS jsonb),'live-prefix-fixture')",
                    source.fixtureModel().id(), source.fixtureModel().revision(), source.config());
            jdbc.update("INSERT INTO nw_tenant_model_selections(tenant_id,llm_model_config_id) VALUES(CAST(? AS bigint),CAST(? AS bigint))", tenant, source.fixtureModel().id());
            return null;
        });
    }
    private void createAgent() throws Exception {
        agent = request("/api/v1/agents", "POST", Map.of("agent_key", "live-prefix-" + java.util.UUID.randomUUID(), "name", "真实会话连续性验收助手",
                "system_prompt", "根据用户要求使用已授权的真实计算工具；无需工具时简短直接回答。"), 200).path("id").asText();
        check(!agent.isBlank(), "LIVE_CACHE_AGENT_ID_MISSING");
        var overrides = Map.of("max_business_model_calls", 6, "max_tool_calls", 2, "max_knowledge_calls", 1,
                "max_total_tokens", 400_000, "max_wall_time_seconds", 600);
        request("/api/v1/agents/update", "PATCH", Map.of("agent_id", agent, "payload", Map.of("execution_config", overrides)), 200);
    }
    private void mountCalculator() throws Exception {
        String name = "calculate_expression";
        var entry = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(value -> name.equals(value.name())).findFirst().orElseThrow();
        long definition = element(request("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED", "GET", null, 200),
                "capability_key", "tool:" + name).path("id").asLong();
        long version = element(request("/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId=" + definition, "GET", null, 200),
                "version_key", entry.publishedVersionKey()).path("id").asLong();
        String key = "live-prefix-calculator-" + java.util.UUID.randomUUID();
        long revision = request("/api/v1/capabilities/deployments", "POST", Map.of("deployment_key", key, "tool_version_id", version,
                "provider_type", "PLATFORM", "provider_ref", name, "environment", "TEST", "status", "ACTIVE"), 201).path("revision").asLong();
        request("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agent, "payload", Map.of("capability_type", "TOOL",
                "capability_definition_id", Long.toString(definition), "capability_version_id", Long.toString(version), "priority", 0,
                "tool_config", Map.of("tool_deployment_id", key, "tool_deployment_revision", revision))), 201);
    }
    private Map<String, Object> chat(String input, String content) { return Map.of("content", List.of(Map.of("type", "text", "text", content)),
            "session_id", session, "agent_id", agent, "client_message_id", input, "context", Map.of("end_user_id", user)); }
    private void observeRun(int index, String run, String expected, boolean calculator) throws Exception {
        String terminal = "ACCEPTED";
        while (budgets.remainingMillis() > 0) {
            ensureSendingAllowed();
            var rows = queryRows(jdbc, tenant, "SELECT status FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?", tenant, run);
            if (!rows.isEmpty()) terminal = rows.getFirst().get("status").toString();
            if (Set.of("FAILED", "TERMINATED", "CANCELLED", "OUTCOME_UNKNOWN").contains(terminal)) break;
            if ("COMPLETED".equals(terminal) && queryCount(jdbc, tenant, "SELECT count(*) FROM agent_runtime_session_events WHERE tenant_id=? AND run_id=? AND event_type='run.completed'", tenant, run) == 1) {
                var finals = history().messages().stream().filter(message -> "assistant".equals(message.role()) && message.metadata() != null
                        && run.equals(message.metadata().get("run_id")) && "final".equals(message.metadata().get("phase"))).toList();
                if (finals.isEmpty()) { Thread.sleep(100); continue; }
                check(finals.size() == 1 && finals.getFirst().content() != null && finals.getFirst().content().contains(expected), "LIVE_CACHE_FUNCTIONAL_ANSWER_INVALID");
                long tools = queryCount(jdbc, tenant, "SELECT count(*) FROM agent_runtime_session_events WHERE tenant_id=? AND run_id=? AND event_type='agent.tool-result' "
                        + "AND payload_canonical_json::jsonb->>'status'='SUCCEEDED'", tenant, run);
                check(tools == (calculator ? 1 : 0), "LIVE_CACHE_CALCULATOR_CALL_COUNT_INVALID");
                runs.set(index - 1, new RunEvidence(index, run, terminal, true, tools)); return;
            }
            Thread.sleep(100);
        }
        runs.set(index - 1, new RunEvidence(index, run, terminal, false, 0));
        budgets.stop("LIVE_CACHE_RUN_NOT_COMPLETED"); throw new AssertionError("LIVE_CACHE_RUN_NOT_COMPLETED");
    }
    private void ensureSendingAllowed() {
        check(budgets.stopCode() == null && budgets.remainingMillis() > 0 && recording.failureCount() == 0, "LIVE_CACHE_SUPPLIER_OR_BUDGET_STOPPED");
    }
    private void waitForAllTerminals() throws InterruptedException {
        while (budgets.remainingMillis() > 0) {
            ensureSendingAllowed();
            var report = budgets.report();
            if (report.sentAttempts() == recording.actualRequestCount() && report.unknownUsageAttempts() == 0
                    && report.attempts().stream().filter(LivePromptCacheBudgets.AttemptEvidence::sent).allMatch(value -> "SUCCEEDED".equals(value.terminal()))) return;
            Thread.sleep(100);
        }
        throw new AssertionError("LIVE_CACHE_ALL_CALLS_NOT_TERMINAL");
    }
    private boolean verifySettledUsage() {
        var report = budgets.report();
        if (report.stopCode() != null || report.unknownUsageAttempts() != 0 || report.sentAttempts() != recording.actualRequestCount()) return false;
        for (var run : runs) {
            var snapshot = budgets.snapshot(new RunRef(new TenantId(tenant), run.runId()));
            var calls = audit.snapshot().stream().filter(value -> run.runId().equals(value.runId())).toList();
            long input = calls.stream().filter(value -> value.usage() != null && value.usage().inputTokens() != null).mapToLong(value -> value.usage().inputTokens()).sum();
            long output = calls.stream().filter(value -> value.usage() != null && value.usage().outputTokens() != null).mapToLong(value -> value.usage().outputTokens()).sum();
            // 维护根的画像摘要另在全局预算报告核对；同 Run 的真实辅助调用也必须落正式账本。
            boolean auxiliary = report.attempts().stream().anyMatch(value -> value.sent() && run.runId().equals(value.runId()) && "AUXILIARY_MODEL".equals(value.category()));
            if (snapshot.reserved().totalTokens() != 0 || !auxiliary && (snapshot.consumed().inputTokens() != input || snapshot.consumed().outputTokens() != output)) return false;
        }
        return report.reportedTokens() <= LivePromptCacheBudgets.MAX_TOKENS;
    }
    private List<Map<String, Object>> promptDiagnostics(int expected) throws Exception {
        List<Map<String, Object>> rows;
        do {
            rows = safeDiagnostics();
            if (rows.size() >= expected) break;
            ensureSendingAllowed(); Thread.sleep(100);
        } while (budgets.remainingMillis() > 0);
        check(expected >= 12 && rows.size() == expected, "LIVE_CACHE_PREFIX_DIAGNOSTIC_COUNT_INVALID");
        var operationKeys = new HashSet<String>();
        var counts = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            check(Boolean.TRUE.equals(row.get("persistedSchemaVerified")) && Boolean.TRUE.equals(row.get("metricFieldsVerified")), "LIVE_CACHE_PREFIX_UNSAFE_FIELDS");
            check(operationKeys.add(row.get("operationKey").toString()), "LIVE_CACHE_PREFIX_DUPLICATE_OPERATION");
            var metrics = (JsonNode) row.get("metrics");
            for (String hash : List.of("systemHash", "toolsHash", "generation")) check(metrics.path(hash).asText().matches("[0-9a-f]{64}"), "LIVE_CACHE_PREFIX_HASH_INVALID");
            check("CANONICAL_MESSAGES_NOT_PROVIDER_TOKENS".equals(metrics.path("measurementKind").asText()), "LIVE_CACHE_PREFIX_MEASUREMENT_INVALID");
            if (index == 0) check("BASELINE".equals(metrics.path("resetReason").asText()) && metrics.path("commonMessages").asLong(-1) == 0
                    && metrics.path("commonMessageBytes").asLong(-1) == 0, "LIVE_CACHE_PREFIX_BASELINE_INVALID");
            else check(!"BASELINE".equals(metrics.path("resetReason").asText()) && metrics.path("commonMessages").asLong() > 0
                    && metrics.path("commonMessageBytes").asLong() > 0, "LIVE_CACHE_PREFIX_CONTINUITY_DIAGNOSTIC_INVALID");
            counts.merge(row.get("runId").toString(), 1, Integer::sum);
        }
        check(counts.size() == 8, "LIVE_CACHE_PREFIX_RUN_COUNT_INVALID");
        for (var run : runs) check(counts.getOrDefault(run.runId(), 0) == audit.snapshot().stream()
                .filter(value -> run.runId().equals(value.runId()) && "BUSINESS_MODEL".equals(value.category())).count(), "LIVE_CACHE_PREFIX_RUN_CALL_COUNT_MISMATCH");
        return rows;
    }
    private List<Map<String, Object>> safeDiagnostics() {
        if (tenant == null || session == null) return List.of();
        try {
            var values = queryRows(jdbc, tenant, "SELECT run_id,payload_schema_id,payload_canonical_json FROM agent_runtime_session_events WHERE tenant_id=? AND session_id=? AND agent_id=? "
                    + "AND event_type='agent.runtime-diagnostic' AND payload_canonical_json::jsonb->>'kind'='PROMPT_PREFIX' ORDER BY session_sequence", tenant, session, agent);
            var result = new ArrayList<Map<String, Object>>();
            for (var row : values) {
                var payload = json.readTree(row.get("payload_canonical_json").toString());
                var metrics = payload.path("metrics");
                var fields = new HashSet<String>(); metrics.fieldNames().forEachRemaining(fields::add);
                var safe = json.createObjectNode();
                for (String hash : List.of("systemHash", "toolsHash", "generation")) {
                    String value = metrics.path(hash).asText(); safe.put(hash, value.matches("[0-9a-f]{64}") ? value : "");
                }
                safe.put("resetReason", safeCode(metrics.path("resetReason").asText()));
                safe.put("commonMessages", metrics.path("commonMessages").asLong(-1));
                safe.put("commonMessageBytes", metrics.path("commonMessageBytes").asLong(-1));
                safe.put("measurementKind", "CANONICAL_MESSAGES_NOT_PROVIDER_TOKENS".equals(metrics.path("measurementKind").asText())
                        ? "CANONICAL_MESSAGES_NOT_PROVIDER_TOKENS" : "UNKNOWN");
                String operation = payload.path("operationKey").asText();
                result.add(Map.of("runId", row.get("run_id").toString(), "operationKey", operation.matches("[A-Za-z0-9_.:/-]{1,1024}") ? operation : "INVALID_OPERATION_KEY",
                        "persistedSchemaVerified", "skillforge.runtime.agent.runtime-diagnostic.v1".equals(row.get("payload_schema_id")),
                        "metricFieldsVerified", fields.equals(Set.of("systemHash", "toolsHash", "generation", "resetReason", "commonMessages", "commonMessageBytes", "measurementKind")),
                        "metrics", safe));
            }
            return result;
        } catch (Exception invalid) { return List.of(); }
    }
    private void verifyWireEvidence() throws Exception {
        // Collector 只公开安全 record；以其分类后的主调用 scope 避免辅助摘要污染相邻比较。
        int mainCalls = 0;
        int retainedToolsFinalizations = 0;
        long rawTotal = 0;
        for (var value : recording.snapshot()) {
            check("SUCCEEDED".equals(value.terminalStatus()) && value.wire() != null
                    && value.observationFailure() == null && value.transportFailure() == null, "LIVE_CACHE_HTTP_CALL_NOT_VERIFIED");
            Long input = value.rawUsage().get("usage.prompt_tokens"), output = value.rawUsage().get("usage.completion_tokens");
            if (input == null) input = value.rawUsage().get("usage.input_tokens");
            if (output == null) output = value.rawUsage().get("usage.output_tokens");
            check(input != null && output != null && input >= 0 && output >= 0, "LIVE_CACHE_RAW_USAGE_UNKNOWN");
            rawTotal = Math.addExact(rawTotal, Math.addExact(input, output));
            if (!"BUSINESS_MODEL".equals(value.attempt().category())) continue;
            mainCalls++;
            check(value.attempt().toolChoice().toLowerCase(java.util.Locale.ROOT).equals(value.wire().toolChoice()), "LIVE_CACHE_WIRE_TOOL_CHOICE_MISMATCH");
            if ("NONE".equals(value.attempt().toolChoice())) {
                check(!value.wire().toolNames().isEmpty(), "LIVE_CACHE_FINALIZATION_TOOLS_NOT_RETAINED"); retainedToolsFinalizations++;
            }
            if (mainCalls == 1) continue;
            var prefix = value.scopePrefixComparison();
            check(prefix != null && Boolean.TRUE.equals(prefix.previousMessagesArePrefix()), "LIVE_CACHE_WIRE_PREFIX_CHANGED");
            // AUTO -> NONE 属于真实收尾协议转换，collector 的 generation 摘要包含此字段。
            check(Boolean.TRUE.equals(prefix.systemUnchanged()) && Boolean.TRUE.equals(prefix.toolsUnchanged()), "LIVE_CACHE_WIRE_FIXED_INSTRUCTIONS_CHANGED");
        }
        check(mainCalls == audit.businessCalls(), "LIVE_CACHE_WIRE_MAIN_CALL_COUNT_MISMATCH");
        check(retainedToolsFinalizations >= 2, "LIVE_CACHE_FINALIZATION_CAPABILITY_NOT_EXERCISED");
        check(rawTotal == budgets.report().reportedTokens(), "LIVE_CACHE_RAW_USAGE_BUDGET_MISMATCH");
    }
    private Object safeBudgetReport() { try { return budgets.report(); } catch (RuntimeException unavailable) { return Map.of("state", "UNAVAILABLE"); } }
    private void refreshRunStates() {
        if (tenant == null) return;
        for (int index = 0; index < runs.size(); index++) try {
            var current = runs.get(index);
            var rows = queryRows(jdbc, tenant, "SELECT status FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?", tenant, current.runId());
            if (!rows.isEmpty()) runs.set(index, new RunEvidence(current.ordinal(), current.runId(), safeCode(rows.getFirst().get("status").toString()),
                    current.functionalAnswerVerified(), current.calculatorResults()));
        } catch (RuntimeException ignored) { }
    }
    private List<String> failureCodes() {
        if (tenant == null) return List.of();
        try { return queryRows(jdbc, tenant, "SELECT reason_code FROM nw_runtime_session_terminal_failures WHERE tenant_id=? UNION "
                + "SELECT reason_code FROM agent_runtime_executions WHERE tenant_id=? AND reason_code IS NOT NULL", tenant, tenant).stream()
                .map(row -> safeCode(row.get("reason_code").toString())).toList(); }
        catch (RuntimeException unavailable) { return List.of("LIVE_CACHE_RUNTIME_DIAGNOSTIC_UNAVAILABLE"); }
    }
    private ChatHistoryResponse history() throws Exception { return json.treeToValue(request("/api/v1/chat/history?session_id=" + session, "GET", null, 200), ChatHistoryResponse.class); }
    private JsonNode request(String path, String method, Object body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        var value = json.readTree(response.body());
        check(response.statusCode() == expected && "OK".equals(value.path("code").asText()), "LIVE_CACHE_HTTP_" + response.statusCode() + "_" + safeCode(value.path("code").asText()));
        return value.path("data");
    }
    private <T> T scoped(java.util.function.Supplier<T> work) {
        TenantContext.setTenantId(tenant);
        try { return new TransactionTemplate(transactions).execute(status -> { jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, tenant); return work.get(); }); }
        finally { TenantContext.clear(); }
    }
    private static JsonNode element(JsonNode array, String field, String expected) {
        for (var value : array) if (expected.equals(value.path(field).asText())) return value;
        throw new IllegalStateException("LIVE_CACHE_CAPABILITY_MISSING");
    }
    private static String required(String name) {
        String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException("MISSING_ENV_" + name); return value;
    }
    private static void check(boolean passed, String code) { if (!passed) throw new AssertionError(code); }
    private static String safeCode(String value) { return value != null && value.matches("[A-Za-z0-9_.:-]{1,160}") ? value : "UNRECOGNIZED_REASON"; }
    private static String safeFailure(Throwable invalid) { return invalid.getMessage() != null && invalid.getMessage().matches("[A-Z0-9_]{1,160}") ? invalid.getMessage() : invalid.getClass().getSimpleName(); }
    private static Long token(java.util.OptionalLong value) { return value.isPresent() ? value.getAsLong() : null; }
    record RunEvidence(int ordinal, String runId, String terminal, boolean functionalAnswerVerified, long calculatorResults) { }
    record UsageEvidence(Long inputTokens, Long outputTokens, Long cachedInputTokens, Long cacheWriteInputTokens, Long reasoningOutputTokens) {
        static UsageEvidence from(ModelUsage value) { return new UsageEvidence(token(value.inputTokens()), token(value.outputTokens()), token(value.cachedInputTokens()), token(value.cacheWriteInputTokens()), token(value.reasoningOutputTokens())); }
    }
    record CallEvidence(String attemptId, String runId, String category, String callKind, String responseFormat, String toolChoice,
                        Instant startedAt, String terminal, String reasonCode, UsageEvidence usage, List<UsageRevision> usageRevisions) { }
    record UsageRevision(long revision, UsageEvidence usage) { }
    private record Source(PlatformModelConfig original, PlatformModelConfig fixtureModel, String config) {
        @Override public String toString() { return "Source[REDACTED]"; }
        Map<String, Object> evidence() {
            var value = new LinkedHashMap<String, Object>();
            value.put("sourceId", original.id()); value.put("sourceRevision", original.revision()); value.put("provider", original.providerId());
            value.put("service", original.serviceId()); value.put("model", original.modelId()); value.put("profileVersion", original.profileVersion());
            value.put("dialectVersion", original.dialectVersion()); value.put("contextWindowTokens", original.contextWindowTokens());
            value.put("sourceMaxOutputTokens", original.maxOutputTokens()); value.put("fixtureMaxOutputTokens", fixtureModel.maxOutputTokens());
            value.put("sourceReasoning", original.reasoning()); value.put("fixtureReasoning", fixtureModel.reasoning());
            value.put("timeoutSeconds", original.timeoutSeconds()); value.put("temperature", original.temperature()); value.put("currency", original.currency());
            value.put("sourceReadOnly", true); value.put("fixtureOverrides", List.of("max_output_tokens=1024")); return value;
        }
    }

    /** 生产外调审计照常执行；额外保存安全分类，禁止来源漂移或失败后的后续发送。 */
    static final class LiveAudit implements LiveDispatchedModelAudit.Callbacks {
        private final JdbcModelBindings bindings;
        private final LivePromptCacheBudgets budgets;
        private final PromptCacheRecordingHttpClient recording;
        private final Map<String, CallState> calls = new LinkedHashMap<>();
        private PlatformModelConfig model;
        private String tenant, session;
        private boolean closed;
        LiveAudit(JdbcModelBindings bindings, LivePromptCacheBudgets budgets, PromptCacheRecordingHttpClient recording) {
            this.bindings = bindings; this.budgets = budgets; this.recording = recording;
        }
        synchronized void freeze(PlatformModelConfig model, String tenant, String session) { this.model = model; this.tenant = tenant; this.session = session; }
        @Override public synchronized boolean start(ModelInvocationAudit delegate, ModelCall call, Provider provider) {
            var identity = call.context().callContext().runIdentity();
            if (closed || model == null || budgets.stopCode() != null || !tenant.equals(identity.runRef().tenantId().value())
                    || !session.equals(identity.owner().sessionRef().sessionId()) || !model.modelId().equals(provider.modelId())
                    || !model.providerId().equals(provider.providerId()) || !model.serviceId().equals(provider.serviceId())
                    || call.generation().maxOutputTokens().isEmpty() || call.generation().maxOutputTokens().getAsInt() > model.maxOutputTokens()
                    || call.reasoning() != ReasoningPolicy.DISABLED
                    || !bindings.find(call.target().binding()).map(JdbcModelBindings.Binding::sourceReference)
                        .filter(("platform-model:" + model.id() + ":" + model.revision())::equals).isPresent()) {
                budgets.stop("LIVE_CACHE_MODEL_CONFIGURATION_DRIFT"); return false;
            }
            boolean started = delegate.start(call, provider);
            if (!started) { budgets.stop("LIVE_CACHE_ATTEMPT_NOT_AUTHORIZED"); return false; }
            String id = attempt(call);
            var state = new CallState(call);
            check(calls.putIfAbsent(id, state) == null, "LIVE_CACHE_DUPLICATE_ATTEMPT");
            recording.beginAttempt(new PromptCacheRecordingHttpClient.AttemptContext(id, state.runId, state.category, state.callKind, state.responseFormat, state.toolChoice), "main");
            return true;
        }
        @Override public synchronized void usageReported(ModelInvocationAudit delegate, ModelCall call, Provider provider, long revision, ModelUsage usage) {
            delegate.usageReported(call, provider, revision, usage);
            var state = calls.get(attempt(call)); if (state != null) state.revisions.add(new UsageRevision(revision, UsageEvidence.from(usage)));
        }
        @Override public synchronized void succeeded(ModelInvocationAudit delegate, ModelCall call, Provider provider, ModelUsage usage) {
            delegate.succeeded(call, provider, usage);
            budgets.providerUsage(call.context().callContext().runIdentity().runRef(), attempt(call), ExecutionBudgetPort.Usage.reported(usage.inputTokens(), usage.outputTokens()));
            finish(call, "SUCCEEDED", null, UsageEvidence.from(usage));
        }
        @Override public synchronized void failed(ModelInvocationAudit delegate, ModelCall call, Provider provider, String reason) {
            budgets.stop("LIVE_CACHE_MODEL_FAILED"); delegate.failed(call, provider, reason); finish(call, "FAILED", safeCode(reason), null);
        }
        @Override public synchronized void unresolved(ModelInvocationAudit delegate, ModelCall call, Provider provider, String reason) {
            budgets.stop("LIVE_CACHE_MODEL_OUTCOME_UNKNOWN"); delegate.unresolved(call, provider, reason); finish(call, "UNRESOLVED", safeCode(reason), null);
        }
        private void finish(ModelCall call, String terminal, String reason, UsageEvidence usage) {
            var state = calls.get(attempt(call));
            if (state != null) { state.terminal = terminal; state.reason = reason; state.usage = usage; }
            recording.terminalAttempt(attempt(call), terminal);
        }
        synchronized int businessCalls() { return (int) calls.values().stream().filter(value -> "BUSINESS_MODEL".equals(value.category)).count(); }
        synchronized List<CallEvidence> snapshot() { return calls.entrySet().stream().map(entry -> entry.getValue().evidence(entry.getKey())).toList(); }
        synchronized void close() { closed = true; }
        private static String attempt(ModelCall call) { return call.context().dispatch().attemptId(); }
        private static final class CallState {
            final String runId, category, callKind, responseFormat, toolChoice;
            final Instant started = Instant.now(); final List<UsageRevision> revisions = new ArrayList<>();
            String terminal = "STARTED", reason; UsageEvidence usage;
            CallState(ModelCall call) {
                runId = call.context().callContext().runIdentity().runRef().runId(); category = call.budgetCategory().name();
                callKind = call.context().dispatch().callKind().name();
                responseFormat = call.response().format().getClass().getSimpleName(); toolChoice = call.toolChoice().name();
            }
            CallEvidence evidence(String id) { return new CallEvidence(id, runId, category, callKind, responseFormat, toolChoice, started, terminal, reason, usage, List.copyOf(revisions)); }
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class LiveConfiguration {
        @Bean @Primary PromptCacheRecordingHttpClient livePromptCacheHttpClient() {
            return new PromptCacheRecordingHttpClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
        }
        @Bean @Primary LivePromptCacheBudgets livePromptCacheBudgets(DataSource source, PromptCacheRecordingHttpClient recording) { return new LivePromptCacheBudgets(source, recording); }
        @Bean LiveAudit livePromptCacheAudit(JdbcModelBindings bindings, LivePromptCacheBudgets budgets, PromptCacheRecordingHttpClient recording) {
            return new LiveAudit(bindings, budgets, recording);
        }
        @Bean @Primary AgentRuntimeModelInvocationAudit livePromptCacheDispatchedAudit(
                @org.springframework.beans.factory.annotation.Qualifier("agentRuntimeModelInvocationAudit") AgentRuntimeModelInvocationAudit original,
                com.skillforge.core.billing.invocation.ExternalInvocationLedger ledger,
                com.skillforge.core.billing.metering.CostRecorder recorder,
                org.springframework.transaction.PlatformTransactionManager transactions,
                LiveAudit callbacks, DataSource source,
                org.springframework.beans.factory.ObjectProvider<com.skillforge.institution.runtime.AgentRuntimeWorkflowNodeExecutions> workflows) {
            return new LiveDispatchedModelAudit(original, ledger, recorder, transactions, workflows,
                    actual -> LiveGradleAttemptMeter.wrap(actual, source), callbacks);
        }
    }

    @org.junit.jupiter.api.AfterEach void collectLiveAttemptEvidence() { LiveGradleAttemptMeter.snapshot(liveMeterDataSource, tenant); }
}
