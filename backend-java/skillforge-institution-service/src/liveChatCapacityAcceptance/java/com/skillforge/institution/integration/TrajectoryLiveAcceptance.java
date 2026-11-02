package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.billing.budget.AuxiliaryModelBudgets;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.chat.contract.ChatToolSchemaContributor;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.observation.SessionObservationView;
import com.skillforge.core.chat.trajectory.TrajectoryView;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit;
import com.skillforge.institution.team.TeamPublicViews;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.ExecutionObservation;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
import org.springframework.core.env.Environment;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 两个真实管理HTTP入口消费隔离新库中的真实供应商轨迹；仅显式live任务可运行。 */
@Import(TrajectoryLiveAcceptance.LiveConfiguration.class)
class TrajectoryLiveAcceptance extends AbstractIntegrationTest {
    static final int MAX_SENDS = 24;
    static final String INSTITUTION_SNOWFLAKE_WORKER = "1", PLATFORM_SNOWFLAKE_WORKER = "2";
    private static final int OUTPUT_LIMIT = 1024;
    private static final Duration MODEL_WINDOW = Duration.ofMinutes(10);
    private static final Duration BROWSER_WINDOW = Duration.ofMinutes(10);
    private static final String TENANT_USERNAME = "trajectory-live-admin";
    private static final String PLATFORM_USERNAME = "trajectory-live-platform";
    private static final String BROWSER_ORIGINS = "http://127.0.0.1:14340,http://127.0.0.1:14341";
    private static final Pattern LISTENING = Pattern.compile("Tomcat started on port (\\d+)");
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired EndUserMapper users;
    @Autowired ApplicationContext application;
    @Autowired AgentRuntimeProperties runtime;
    @Autowired Environment environment;
    @Autowired SkillForgeProperties properties;
    @Autowired LiveAudit audit;
    @Autowired PromptCacheRecordingHttpClient recording;
    @Autowired ExecutionStore executionStore;
    @Autowired LivePromptCacheBudgets budgets;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<RunEvidence> runs = new ArrayList<>();
    private final List<ObservationEvidence> observations = new ArrayList<>();
    private final List<ManagementHttpEvidence> managementHttp = new ArrayList<>();
    private final List<CleanupEvidence> cleanup = new ArrayList<>();
    private String tenant, tenantSlug, token, platformToken, calculator, coordinator;
    private long user;
    private int platformPort;
    private Process platformProcess;
    private Path platformLog;
    private Path platformRuntimeDirectory;
    private SnowflakeEvidence snowflake;
    private String stage = "SOURCE_READ";

    @DynamicPropertySource static void isolate(DynamicPropertyRegistry registry) {
        registry.add("skillforge.secret-key", () -> required("SECRET_KEY"));
        registry.add("sa-token.jwt-secret-key", () -> "trajectory-isolated-institution-auth-key");
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
        registry.add("skillforge.code-execution.preview-port", () -> "0");
        registry.add("skillforge.code-execution.preview-origin", () -> "");
        registry.add("skillforge.code-execution.artifact-root", () -> artifactRoot().toString());
        registry.add("skillforge.web.allowed-origins", () -> BROWSER_ORIGINS);
        registry.add("logging.level.root", () -> "OFF");
    }

    private static Path artifactRoot() {
        return OwnedArtifacts.ROOT;
    }
    private static final class OwnedArtifacts {
        private static final Path ROOT = createArtifactRoot();
    }
    private static Path createArtifactRoot() {
        try { return Files.createTempDirectory("skillforge-live-trajectory-"); }
        catch (java.io.IOException unavailable) { throw new IllegalStateException("TRAJECTORY_ARTIFACT_ROOT_UNAVAILABLE"); }
    }
    @AfterAll static void removeEmptyArtifactRoot() throws java.io.IOException { Files.deleteIfExists(artifactRoot()); }

    @Test void realSupplierTrajectoryIsPersistentScopedAndReadableFromBothManagementRealms() throws Exception {
        Instant started = Instant.now();
        String outcome = "FAILED", failure = null;
        Source source = null;
        boolean browserVerified = false;
        try {
            check("1".equals(System.getenv("SKILLFORGE_LIVE_TRAJECTORY_ACCEPTANCE")), "TRAJECTORY_EXPLICIT_GATE_REQUIRED");
            check(INSTITUTION_SNOWFLAKE_WORKER.equals(System.getenv("SNOWFLAKE_WORKER_ID")), "TRAJECTORY_INSTITUTION_SNOWFLAKE_WORKER_REQUIRED");
            source = readSource();
            stage = "FIXTURE";
            fixture(); installModel(source);
            requireRunningRuntimeWorkers(application, runtime);
            stage = "PLATFORM_HOST";
            startPlatform();
            platformToken = exchange(true, null, "/api/v1/platform/auth/login", "POST",
                    Map.of("username", PLATFORM_USERNAME, "password", fixturePassword()), 200).path("access_token").asText();
            check(!platformToken.isBlank(), "TRAJECTORY_PLATFORM_LOGIN_MISSING");
            verifyBrowserOrigins();
            stage = "AGENTS";
            calculator = createAgent("真实轨迹计算成员", "根据明确任务使用一次真实 calculate_expression 计算，不心算替代工具；最终简短给出数值。");
            mountCalculator(calculator); publish(calculator);
            coordinator = createAgent("真实轨迹协作助手", "需要计算时仅委派一次给计算成员，持久等待真实成员结果后简短汇总。不要自己计算，不要后台执行。");
            request("/api/v1/agents/update", "PATCH", Map.of("agent_id", coordinator, "payload", Map.of(
                    "delegation_config", new AgentDelegationConfig(true, List.of(calculator)))), 200);
            publish(coordinator);
            audit.freeze(source.fixture(), tenant, Set.of(calculator, coordinator));
            budgets.begin(tenant);
            stage = "DIRECT_REQUEST";
            var direct = run(calculator, "请仅调用一次 calculate_expression 计算17*23，然后用一句短句给出计算结果。", "391");
            stage = "DELEGATED_REQUEST";
            var delegated = run(coordinator, "请只委派一次给已授权的计算成员：调用一次 calculate_expression 计算45*12；等待成员完成后汇总其结果。不要后台执行，不要自己心算。", "540");
            stage = "PERSISTED_OBSERVATION";
            verifyDelegatedChild(delegated);
            for (var run : runs) { verifyObservation(false, run); verifyObservation(true, run); }
            verifyWireAndUsage();
            stage = "READ_ONLY_REPLAY";
            int before = recording.actualRequestCount();
            for (var run : runs) { verifyObservation(false, run); verifyObservation(true, run); }
            check(recording.actualRequestCount() == before, "TRAJECTORY_READ_DISPATCHED_MODEL");
            audit.close();
            budgets.close();
            stage = "BROWSER_READINESS";
            browserVerified = holdForBrowsers(direct, delegated);
            check(recording.actualRequestCount() == before, "TRAJECTORY_BROWSER_DISPATCHED_MODEL");
            outcome = "PASSED";
        } catch (Exception | AssertionError rejected) {
            failure = safeFailure(rejected);
            throw new AssertionError("LIVE_TRAJECTORY_SEE_SAFE_EVIDENCE");
        } finally {
            audit.close();
            budgets.close();
            refreshRunEvidence();
            boolean bodyFailed = failure != null;
            String cleanupFailure = null;
            for (var run : runs) try { request("/api/v1/chat/sessions/close", "POST", Map.of("session_id", run.sessionId(),
                    "payload", Map.of("reason", "live-trajectory-finished")), 200); }
                catch (Exception | AssertionError rejected) { cleanupFailure = "TRAJECTORY_SESSION_CLEANUP_FAILED"; }
            try { stopPlatform(); }
            catch (Exception | AssertionError rejected) { cleanupFailure = "TRAJECTORY_PLATFORM_CLEANUP_FAILED"; }
            finally {
                if (cleanupFailure != null) { outcome = "FAILED"; if (failure == null) failure = cleanupFailure; }
                writeEvidence(new Evidence("TRAJECTORY_REAL_HOST", outcome, stage, failure, source == null ? null : source.evidence(),
                        MAX_SENDS, recording.actualRequestCount(), allCalls(), recording.snapshot(), List.copyOf(runs),
                        List.copyOf(observations), List.copyOf(cleanup), safeBudget(), persistentCounts(),
                        audit.auxiliarySources(), List.copyOf(managementHttp), snowflake, browserVerified, Duration.between(started, Instant.now()).toSeconds()));
            }
            if (cleanupFailure != null && !bodyFailed) throw new AssertionError(cleanupFailure);
        }
    }

    /** 来源库只读、精确当前发布版本；密文和完整配置只进入临时库内存装配。 */
    private Source readSource() {
        var mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        String id = required("LIVE_TRAJECTORY_SOURCE_MODEL_ID");
        long revision = Long.parseLong(required("LIVE_TRAJECTORY_SOURCE_MODEL_REVISION"));
        try (Connection connection = DriverManager.getConnection(required("SPRING_DATASOURCE_URL"),
                required("PLATFORM_DATASOURCE_USERNAME"), required("PLATFORM_DATASOURCE_PASSWORD"))) {
            check(!databaseEndpoint(connection.getMetaData().getURL()).equals(databaseEndpoint(environment.getRequiredProperty("spring.datasource.url"))),
                    "TRAJECTORY_SOURCE_EQUALS_FIXTURE_DATABASE");
            connection.setReadOnly(true); connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) { statement.execute("SET TRANSACTION READ ONLY"); }
            try (var query = connection.prepareStatement("SELECT v.config::text FROM nw_platform_models m JOIN nw_platform_model_versions v "
                    + "ON v.model_id=m.id AND v.revision=m.current_revision WHERE m.id=CAST(? AS bigint) AND m.current_revision=? LIMIT 2")) {
                query.setString(1, id); query.setLong(2, revision);
                try (var rows = query.executeQuery()) {
                    check(rows.next(), "TRAJECTORY_SOURCE_NOT_CURRENT_PUBLISHED");
                    var config = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(rows.getString(1));
                    var original = mapper.treeToValue(config, PlatformModelConfig.class);
                    check(!rows.next() && id.equals(original.id()) && revision == original.revision() && original.enabled()
                            && "LLM".equals(original.kind()) && "DISABLED".equals(original.reasoning())
                            && "openai-compatible-chat-v1".equals(original.dialectVersion()) && original.maxOutputTokens() != null
                            && original.maxOutputTokens() >= OUTPUT_LIMIT && original.contextWindowTokens() != null
                            && original.contextWindowTokens() > original.maxOutputTokens() && original.apiKeyEncrypted() != null
                            && !original.apiKeyEncrypted().isBlank(), "TRAJECTORY_SOURCE_MODEL_INVALID");
                    config.put("max_output_tokens", OUTPUT_LIMIT);
                    var fixture = mapper.treeToValue(config, PlatformModelConfig.class);
                    connection.rollback();
                    return new Source(original, fixture, mapper.writeValueAsString(config));
                }
            }
        } catch (AssertionError identified) { throw identified; }
        catch (Exception unavailable) { throw new IllegalStateException("TRAJECTORY_SOURCE_READ_FAILED_" + unavailable.getClass().getSimpleName()); }
    }

    private void fixture() throws Exception {
        tenantSlug = "live-trajectory-" + UUID.randomUUID();
        tenant = createTenant(tenants, plans, tenantSlug);
        TenantContext.setTenantId(tenant);
        try {
            createTestTenantAdministrator(tenant, TENANT_USERNAME, fixturePassword());
            var endUser = new EndUserDO(); endUser.setId(SnowflakeIds.nextId()); endUser.setTenantId(Long.valueOf(tenant));
            endUser.setStatus("active"); endUser.setDisplayName("隔离真实轨迹验收用户"); endUser.setFirstSeenAt(Instant.now());
            endUser.setLastActiveAt(Instant.now()); endUser.setCreatedAt(Instant.now()); users.insert(endUser); user = endUser.getId();
        } finally { TenantContext.clear(); }
        ensureTestPlatformAdministrator(PLATFORM_USERNAME, fixturePassword());
        token = exchange(false, null, "/api/v1/auth/login", "POST",
                Map.of("tenant_slug", tenantSlug, "username", TENANT_USERNAME, "password", fixturePassword()), 200).path("access_token").asText();
        check(!token.isBlank(), "TRAJECTORY_INSTITUTION_LOGIN_MISSING");
    }

    private void installModel(Source source) {
        scoped(() -> {
            jdbc.update("INSERT INTO nw_platform_models(id,current_revision,draft,draft_changed) VALUES(CAST(? AS bigint),?,CAST(? AS jsonb),false)",
                    source.fixture().id(), source.fixture().revision(), source.config());
            jdbc.update("INSERT INTO nw_platform_model_versions(model_id,revision,config,actor_id) VALUES(CAST(? AS bigint),?,CAST(? AS jsonb),'trajectory-live-fixture')",
                    source.fixture().id(), source.fixture().revision(), source.config());
            jdbc.update("INSERT INTO nw_tenant_model_selections(tenant_id,llm_model_config_id) VALUES(CAST(? AS bigint),CAST(? AS bigint))", tenant, source.fixture().id());
            return null;
        });
    }

    private String createAgent(String name, String prompt) throws Exception {
        String agent = request("/api/v1/agents", "POST", Map.of("agent_key", "live-trajectory-" + UUID.randomUUID(), "name", name, "system_prompt", prompt), 200).path("id").asText();
        check(!agent.isBlank(), "TRAJECTORY_AGENT_ID_MISSING");
        request("/api/v1/agents/update", "PATCH", Map.of("agent_id", agent, "payload", Map.of("execution_config", Map.of(
                "max_business_model_calls", 8, "max_tool_calls", 6, "max_knowledge_calls", 1,
                "max_total_tokens", 200_000, "max_wall_time_seconds", 480))), 200);
        return agent;
    }
    private void publish(String agent) throws Exception { request("/api/v1/agents/publish", "POST", Map.of("agent_id", agent), 200); }

    private void mountCalculator(String agent) throws Exception {
        String name = "calculate_expression";
        var entry = new PlatformToolCatalog(new ChatToolSchemaContributor()).entries().stream().filter(value -> name.equals(value.name())).findFirst().orElseThrow();
        long definition = element(request("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED", "GET", null, 200), "capability_key", "tool:" + name).path("id").asLong();
        long version = element(request("/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId=" + definition, "GET", null, 200), "version_key", entry.publishedVersionKey()).path("id").asLong();
        String key = "live-trajectory-calculator-" + UUID.randomUUID();
        long revision = request("/api/v1/capabilities/deployments", "POST", Map.of("deployment_key", key, "tool_version_id", version,
                "provider_type", "PLATFORM", "provider_ref", name, "environment", "TEST", "status", "ACTIVE"), 201).path("revision").asLong();
        request("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agent, "payload", Map.of("capability_type", "TOOL",
                "capability_definition_id", Long.toString(definition), "capability_version_id", Long.toString(version), "priority", 0,
                "tool_config", Map.of("tool_deployment_id", key, "tool_deployment_revision", revision))), 201);
    }

    private RunEvidence run(String agent, String content, String expected) throws Exception {
        ensureHealthy();
        String session = request("/api/v1/chat/sessions", "POST", Map.of("agent_id", agent, "end_user_id", user), 200).path("session_id").asText();
        String input = UUID.randomUUID().toString();
        String run = request("/api/v1/chat", "POST", Map.of("content", List.of(Map.of("type", "text", "text", content)),
                "session_id", session, "agent_id", agent, "client_message_id", input, "context", Map.of("end_user_id", user)), 202).path("execution_id").asText();
        check(!run.isBlank(), "TRAJECTORY_RUN_RECEIPT_MISSING");
        var pending = new RunEvidence(session, input, run, null, "ACCEPTED", expected);
        runs.add(pending);
        while (audit.remainingMillis() > 0) {
            ensureHealthy();
            var current = scoped(() -> executionStore.load(new RunRef(new TenantId(tenant), run)));
            String state = current.map(value -> value.status().name()).orElse("ACCEPTED");
            if (current.isPresent()) runs.set(runs.size() - 1, runEvidence(pending, current.orElseThrow()));
            if (current.map(value -> value.phase() == ExecutionStore.Phase.UNCERTAIN).orElse(false)) {
                audit.close(); throw new AssertionError("TRAJECTORY_EXECUTION_OUTCOME_UNKNOWN");
            }
            check(!Set.of("FAILED", "TERMINATED", "CANCELLED", "OUTCOME_UNKNOWN").contains(state), "TRAJECTORY_RUN_NOT_COMPLETED");
            var history = json.treeToValue(request("/api/v1/chat/history?session_id=" + encode(session), "GET", null, 200), ChatHistoryResponse.class);
            if ("COMPLETED".equals(state)) {
                var finals = history.messages().stream().filter(message -> "assistant".equals(message.role()) && message.metadata() != null
                        && run.equals(message.metadata().get("run_id")) && "final".equals(message.metadata().get("phase"))).toList();
                if (!finals.isEmpty()) {
                    check(finals.size() == 1 && finals.getFirst().content() != null && finals.getFirst().content().contains(expected), "TRAJECTORY_FUNCTIONAL_ANSWER_INVALID");
                    return runs.getLast();
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("TRAJECTORY_OBSERVATION_TIMEOUT");
    }

    /** 使用Store精确当前Run与正式完成/观测事件；绝不把正文或请求材料写入失败报告。 */
    private RunEvidence runEvidence(RunEvidence original, ExecutionStore.Run current) {
        if (current.phase() != ExecutionStore.Phase.FINISHED && current.phase() != ExecutionStore.Phase.UNCERTAIN)
            return new RunEvidence(original.sessionId(), original.rootInputId(), original.runId(), current.identity().trace().traceId(),
                    current.status().name(), original.expected(), current.phase().name(), null, List.of(), null);
        String reason = null, diagnosticFailure = null;
        var failures = new ArrayList<FailureObservation>();
        try {
            var ref = current.identity().runRef();
            var terminal = scoped(() -> executionStore.event(ref, AgentExecutionProgram.finishedEventId(ref)));
            if (terminal.isPresent()) {
                var saved = terminal.orElseThrow();
                check(saved.run().equals(ref) && saved.event().id().equals(AgentExecutionProgram.finishedEventId(ref))
                        && AgentExecutionProgram.COMPLETED_EVENT.equals(saved.event().type())
                        && AgentExecutionProgram.COMPLETED_SCHEMA.equals(saved.event().data().schemaId()), "TRAJECTORY_TERMINAL_SOURCE_MISMATCH");
                var metadata = new ObjectMapper().readValue(saved.event().data().canonicalJson(), TerminalMetadata.class);
                check(metadata.status() == current.status() && original.rootInputId().equals(metadata.rootInputId())
                        && current.identity().agentId().value().equals(metadata.agentId()), "TRAJECTORY_TERMINAL_IDENTITY_MISMATCH");
                reason = safeReason(metadata.reason());
            }
            long after = 0;
            for (int page = 0; page < 10; page++) {
                long cursor = after;
                var events = scoped(() -> executionStore.events(new SessionRef(ref.tenantId(), original.sessionId()), cursor, 100));
                for (var saved : events) {
                    if (!ref.equals(saved.run()) || !ExecutionObservation.EVENT_TYPE.equals(saved.event().type())) continue;
                    var fact = ExecutionObservation.decode(saved.event().data(), 1_000_000, 10_000, 128);
                    check(fact.runId().equals(ref.runId()), "TRAJECTORY_FAILURE_OBSERVATION_OWNER_MISMATCH");
                    if (fact.reasonCode() != null || Set.of(ExecutionObservation.Status.FAILED, ExecutionObservation.Status.REJECTED,
                            ExecutionObservation.Status.UNKNOWN, ExecutionObservation.Status.TERMINATED).contains(fact.status()))
                        failures.add(new FailureObservation(saved.event().id().value(), fact.operationId(), fact.kind().name(), fact.status().name(),
                                fact.stage() == null ? null : fact.stage().name(), safeReason(fact.reasonCode())));
                }
                if (events.size() < 100) break;
                after = events.getLast().sequence();
            }
        } catch (Exception | AssertionError rejected) { diagnosticFailure = safeFailure(rejected); }
        return new RunEvidence(original.sessionId(), original.rootInputId(), original.runId(), current.identity().trace().traceId(),
                current.status().name(), original.expected(), current.phase().name(), reason, List.copyOf(failures), diagnosticFailure);
    }

    private void refreshRunEvidence() {
        for (int index = 0; index < runs.size(); index++) {
            var previous = runs.get(index);
            try {
                var current = scoped(() -> executionStore.load(new RunRef(new TenantId(tenant), previous.runId())));
                if (current.isPresent()) runs.set(index, runEvidence(previous, current.orElseThrow()));
            } catch (RuntimeException unavailable) {
                runs.set(index, new RunEvidence(previous.sessionId(), previous.rootInputId(), previous.runId(), previous.traceId(), previous.status(),
                        previous.expected(), previous.phase(), previous.terminalReason(), previous.failures(), safeFailure(unavailable)));
            }
        }
    }

    private void verifyDelegatedChild(RunEvidence run) throws Exception {
        var work = json.treeToValue(request("/api/v1/chat/work?session_id=" + encode(run.sessionId()), "GET", null, 200), TeamPublicViews.Work.class);
        check(work.tasks().size() == 1 && TeamPublicViews.State.COMPLETED.equals(work.tasks().getFirst().status()), "TRAJECTORY_REAL_DELEGATION_MISSING");
        String child = work.tasks().getFirst().runId();
        var edges = queryRows(jdbc, tenant, "SELECT parent_run_id,parent_action_id,parent_attempt_id,child_run_id FROM agent_runtime_execution_child_runs "
                + "WHERE tenant_id=? AND parent_run_id=? AND child_run_id=? LIMIT 2", tenant, run.runId(), child);
        check(edges.size() == 1 && edges.getFirst().get("parent_action_id") != null && edges.getFirst().get("parent_attempt_id") != null,
                "TRAJECTORY_EXACT_CHILD_EDGE_MISSING");
    }

    /** 每端都走真实认证HTTP、分页内容引用、当前对象授权与持久usage，不从夹具反推结果。 */
    private void verifyObservation(boolean platform, RunEvidence run) throws Exception {
        check(budgets.stopCode() == null, "TRAJECTORY_GLOBAL_BUDGET_STOPPED");
        audit.ensureNoUnknown();
        String base = platform ? "/api/v1/platform/trajectories" : "/api/v1/chat/trajectories";
        String scope = "tenant_id=" + tenant + "&session_id=" + encode(run.sessionId()) + "&root_input_id=" + encode(run.rootInputId());
        var detail = json.treeToValue(observe(platform, base + "/detail?" + scope, 200), TrajectoryView.Detail.class);
        check("COMPLETED".equals(detail.summary().status()) && run.rootInputId().equals(detail.summary().rootInputId())
                && detail.summary().output() != null && detail.summary().output().contains(run.expected()), "TRAJECTORY_DETAIL_RESULT_INVALID");
        var list = json.treeToValue(observe(platform, base + "?tenant_id=" + tenant + "&session_id=" + encode(run.sessionId()), 200), TrajectoryView.ListPage.class);
        check(list.items().stream().anyMatch(value -> run.rootInputId().equals(value.rootInputId())), "TRAJECTORY_LIST_ROOT_MISSING");
        var nodes = nodes(platform, base, scope, 2);
        Set<TrajectoryView.Kind> kinds = nodes.stream().map(TrajectoryView.ObservationNode::kind).collect(Collectors.toSet());
        check(kinds.containsAll(Set.of(TrajectoryView.Kind.ENTRY, TrajectoryView.Kind.AGENT, TrajectoryView.Kind.STEP,
                TrajectoryView.Kind.LLM, TrajectoryView.Kind.TOOL)), "TRAJECTORY_EXECUTION_TREE_INCOMPLETE");
        var byId = nodes.stream().collect(Collectors.toMap(TrajectoryView.ObservationNode::nodeId, value -> value));
        check(nodes.stream().allMatch(node -> node.parentNodeId() == null || byId.containsKey(node.parentNodeId())), "TRAJECTORY_PARENT_CLOSURE_INCOMPLETE");
        var tools = nodes.stream().filter(value -> value.kind() == TrajectoryView.Kind.TOOL && value.name().contains("calculate_expression")).toList();
        check(tools.size() == 1, "TRAJECTORY_CALCULATOR_NODE_COUNT_INVALID");
        var tool = tools.getFirst();
        var output = json.treeToValue(observe(platform, base + "/content?" + scope + "&content_ref=" + encode(tool.output().ref()), 200), TrajectoryView.Content.class);
        check("FULL".equals(output.availability()), "TRAJECTORY_CALCULATOR_BODY_UNAVAILABLE");
        var body = json.valueToTree(output.value());
        check(body.path("structuredContent").path("success").asBoolean() && run.expected().equals(body.path("structuredContent").path("value").asText()), "TRAJECTORY_CALCULATOR_BODY_MISMATCH");
        check(!body.has("target") && !body.has("binding") && !body.has("requestState"), "TRAJECTORY_PRIVATE_TOOL_ENVELOPE_DISCLOSED");
        var input = json.treeToValue(observe(platform, base + "/content?" + scope + "&content_ref=" + encode(tool.input().ref()), 200), TrajectoryView.Content.class);
        check("FULL".equals(input.availability()) && json.valueToTree(input.value()).path("expression").isTextual(), "TRAJECTORY_CALCULATOR_INPUT_UNAVAILABLE");
        for (var node : nodes.stream().filter(value -> value.kind() == TrajectoryView.Kind.LLM).toList()) {
            check(!node.attempts().isEmpty(), "TRAJECTORY_MODEL_ATTEMPT_NOT_RECORDED");
            boolean diagnostic = false;
            for (var attempt : node.attempts()) {
                var available = json.valueToTree(attempt).path("diagnostic_available");
                check(available.isBoolean(), "TRAJECTORY_DIAGNOSTIC_AVAILABILITY_UNKNOWN");
                diagnostic |= available.booleanValue();
            }
            String expectedAvailability = diagnostic ? "REDACTED" : "NOT_RECORDED";
            check(expectedAvailability.equals(node.input().availability()) && expectedAvailability.equals(node.output().availability()), "TRAJECTORY_MODEL_CONTEXT_DISCLOSED");
        }
        // 密封content身份不能因更换root或管理主体而复用。
        observe(platform, base + "/content?tenant_id=" + tenant + "&session_id=" + encode(run.sessionId())
                + "&root_input_id=other-root&content_ref=" + encode(tool.output().ref()), 404);
        observe(!platform, (platform ? "/api/v1/chat/trajectories" : "/api/v1/platform/trajectories")
                + "/content?" + scope + "&content_ref=" + encode(tool.output().ref()), 404);
        if (!platform) observe(false, base + "/detail?tenant_id=1&session_id=" + encode(run.sessionId()) + "&root_input_id=" + encode(run.rootInputId()), 403);
        Set<String> visibleRuns = nodes.stream().map(TrajectoryView.ObservationNode::runId).filter(Objects::nonNull).collect(Collectors.toSet());
        check(visibleRuns.contains(run.runId()), "TRAJECTORY_ROOT_RUN_MISSING");
        if (coordinatorRun(run)) {
            check(visibleRuns.size() >= 2, "TRAJECTORY_CHILD_SCOPE_MISSING");
            var childAgent = nodes.stream().filter(node -> node.kind() == TrajectoryView.Kind.AGENT && node.runId() != null
                    && !run.runId().equals(node.runId())).findFirst().orElseThrow();
            check(byId.get(childAgent.parentNodeId()).kind() == TrajectoryView.Kind.TOOL, "TRAJECTORY_CHILD_NOT_UNDER_DISPATCH_TOOL");
        } else check(visibleRuns.size() == 1, "TRAJECTORY_UNRELATED_ROOT_LEAKED");
        verifyUsage(detail.usage(), visibleRuns, nodes);
        check(detail.summary().modelCalls() == allCalls().stream().filter(value -> visibleRuns.contains(value.runId())).count(),
                "TRAJECTORY_SUMMARY_OMITS_ACTUAL_MODEL_CALLS");
        observations.add(new ObservationEvidence(platform ? "PLATFORM" : "INSTITUTION", run.runId(), nodes.size(),
                kinds.stream().map(Enum::name).sorted().toList(), visibleRuns.stream().sorted().toList(), detail.usage().coverage(),
                detail.usage().totals(), output.availability(), true, true));
    }

    private boolean coordinatorRun(RunEvidence run) { return runs.size() > 1 && runs.get(1).runId().equals(run.runId()); }

    private List<TrajectoryView.ObservationNode> nodes(boolean platform, String base, String scope, int pageSize) throws Exception {
        Map<String, TrajectoryView.ObservationNode> nodes = new LinkedHashMap<>();
        String cursor = null, snapshot = null;
        Set<String> seenCursors = new LinkedHashSet<>();
        do {
            String path = base + "/nodes?" + scope + "&page_size=" + pageSize;
            if (cursor != null) path += "&cursor=" + encode(cursor) + "&snapshot=" + encode(snapshot);
            var page = json.treeToValue(observe(platform, path, 200), TrajectoryView.ObservationPage.class);
            check(page.items().size() <= pageSize && page.snapshot() != null && !page.snapshot().isBlank(), "TRAJECTORY_PAGE_INVALID");
            for (var node : page.items()) check(nodes.putIfAbsent(node.nodeId(), node) == null, "TRAJECTORY_DUPLICATE_NODE_PAGE");
            if (snapshot == null) snapshot = page.snapshot(); else check(snapshot.equals(page.snapshot()), "TRAJECTORY_PAGE_SNAPSHOT_CHANGED");
            cursor = page.nextCursor();
            check(cursor == null || seenCursors.add(cursor), "TRAJECTORY_CURSOR_CYCLE");
            check(nodes.size() <= 200 && seenCursors.size() <= 100, "TRAJECTORY_UNBOUNDED_PAGINATION");
        } while (cursor != null);
        return List.copyOf(nodes.values());
    }

    private void verifyUsage(SessionObservationView.Usage usage, Set<String> visibleRuns, List<TrajectoryView.ObservationNode> nodes) {
        var actual = allCalls().stream().filter(call -> visibleRuns.contains(call.runId())).toList();
        check(!actual.isEmpty() && usage.coverage().totalAttempts() == actual.size() && usage.coverage().unknownAttempts() == 0
                && usage.coverage().partialAttempts() == 0 && "COMPLETE".equals(usage.coverage().status()), "TRAJECTORY_USAGE_COVERAGE_INVALID");
        var expected = actual.stream().collect(Collectors.toMap(CallEvidence::attemptId, value -> value));
        check(usage.attempts().size() == expected.size(), "TRAJECTORY_USAGE_ATTEMPT_COUNT_MISMATCH");
        for (var value : usage.attempts()) {
            var call = expected.get(value.attemptId());
            check(call != null && call.usage() != null && value.traceNodeId() != null
                    && nodes.stream().anyMatch(node -> node.nodeId().equals(value.traceNodeId()) && node.attempts().stream().anyMatch(attempt -> attempt.attemptId().equals(value.attemptId()))), "TRAJECTORY_USAGE_NODE_ASSOCIATION_MISSING");
            check(Objects.equals(Long.toString(call.usage().input()), value.totals().promptTokens())
                    && Objects.equals(Long.toString(call.usage().output()), value.totals().completionTokens())
                    && Objects.equals(text(call.usage().cached()), value.totals().cachedInputTokens())
                    && Objects.equals(text(call.usage().cacheWrite()), value.totals().cacheWriteInputTokens())
                    && Objects.equals(text(call.usage().reasoning()), value.totals().reasoningOutputTokens()), "TRAJECTORY_USAGE_SUPPLIER_MISMATCH");
        }
        long input = actual.stream().mapToLong(value -> value.usage().input()).sum();
        long output = actual.stream().mapToLong(value -> value.usage().output()).sum();
        check(BigInteger.valueOf(input).toString().equals(usage.totals().promptTokens())
                && BigInteger.valueOf(output).toString().equals(usage.totals().completionTokens()), "TRAJECTORY_USAGE_TOTAL_MISMATCH");
    }

    private void verifyWireAndUsage() {
        ensureHealthy();
        var calls = allCalls().stream().collect(Collectors.toMap(CallEvidence::attemptId, value -> value));
        check(recording.actualRequestCount() == calls.size() && calls.size() >= 6 && calls.size() <= MAX_SENDS, "TRAJECTORY_ACTUAL_SEND_COUNT_INVALID");
        for (var wire : recording.snapshot()) {
            check(wire.observationFailure() == null && wire.transportFailure() == null && "SUCCEEDED".equals(wire.terminalStatus())
                    && wire.httpStatus() != null && wire.httpStatus() == 200 && wire.requestSubscriptions() == 1, "TRAJECTORY_SUPPLIER_REQUEST_NOT_CONFIRMED");
            var call = calls.get(wire.attempt().attemptId());
            Long input = wire.rawUsage().get("usage.prompt_tokens"), output = wire.rawUsage().get("usage.completion_tokens");
            if (input == null) input = wire.rawUsage().get("usage.input_tokens");
            if (output == null) output = wire.rawUsage().get("usage.output_tokens");
            check(call != null && call.usage() != null && Objects.equals(input, call.usage().input()) && Objects.equals(output, call.usage().output()),
                    "TRAJECTORY_RAW_HTTP_USAGE_MISMATCH");
        }
        var persisted = persistentCounts();
        check(persisted.readFailure() == null && persisted.modelInvocations() == recording.actualRequestCount()
                && persisted.unknownInvocations() == 0 && persisted.effectiveUsageAttempts() == recording.actualRequestCount(),
                "TRAJECTORY_PERSISTED_SEND_ACCOUNTING_MISMATCH");
        check(calls.values().stream().anyMatch(value -> "AUXILIARY_MODEL".equals(value.category())), "TRAJECTORY_AUXILIARY_SEND_NOT_OBSERVED");
    }

    private void ensureHealthy() {
        check(budgets.stopCode() == null && budgets.remainingMillis() > 0, "TRAJECTORY_GLOBAL_BUDGET_STOPPED");
        audit.ensureHealthy();
    }

    /** 固定模型任务没有独立ExecutionStore模型Action；按真实父Run/attempt/usage记录，不猜节点父边。 */
    private List<CallEvidence> allCalls() {
        var calls = new LinkedHashMap<String, CallEvidence>();
        audit.snapshot().forEach(value -> calls.put(value.attemptId(), value));
        try {
            var report = budgets.report();
            for (var auxiliary : report.attempts()) {
                if (!auxiliary.sent() || !"AUXILIARY_MODEL".equals(auxiliary.category())) continue;
                var source = audit.auxiliarySources().stream().filter(value -> auxiliary.attemptId().equals(value.attemptId())).findFirst().orElse(null);
                var usage = queryRows(jdbc, tenant, "SELECT prompt_tokens,completion_tokens,cached_input_tokens,cache_write_input_tokens,reasoning_output_tokens "
                        + "FROM observation_effective_model_usage WHERE tenant_id=CAST(? AS bigint) AND attempt_id=? AND run_id=? LIMIT 2",
                        tenant, auxiliary.attemptId(), auxiliary.runId());
                UsageEvidence amounts = null;
                if (usage.size() == 1) {
                    var row = usage.getFirst();
                    amounts = new UsageEvidence(number(row.get("prompt_tokens")), number(row.get("completion_tokens")), number(row.get("cached_input_tokens")),
                            number(row.get("cache_write_input_tokens")), number(row.get("reasoning_output_tokens")));
                }
                calls.put(auxiliary.attemptId(), new CallEvidence(auxiliary.attemptId(), auxiliary.runId(), source == null ? null : source.sessionId(),
                        auxiliary.category(), auxiliary.terminal(), amounts));
            }
        } catch (RuntimeException unavailable) { /* 原审计事实保留；不把未读取到的辅助终态补成成功。 */ }
        return List.copyOf(calls.values());
    }

    private Object safeBudget() {
        try { return budgets.report(); }
        catch (RuntimeException unavailable) { return Map.of("state", "UNAVAILABLE", "failureClass", unavailable.getClass().getSimpleName()); }
    }

    /** 临时库的完整模型外调/usage计数交叉核对，既包含根与成员，也包含正式固定模型用途。 */
    private PersistenceEvidence persistentCounts() {
        if (tenant == null) return new PersistenceEvidence(0L, 0L, 0L, 0L, 0L, null);
        try {
            long invocations = jdbc.queryForObject("SELECT count(*) FROM nw_external_invocations WHERE operation_type='model.chat'", Long.class);
            long unknown = jdbc.queryForObject("SELECT count(*) FROM nw_external_invocations WHERE operation_type='model.chat' AND status NOT IN ('SUCCEEDED','FAILED')", Long.class);
            long usage = jdbc.queryForObject("SELECT count(*) FROM observation_effective_model_usage", Long.class);
            long measured = jdbc.queryForObject("SELECT count(*) FROM nw_usage_events WHERE event_type='llm.chat' AND usage_event_kind='measured'", Long.class);
            long systems = jdbc.queryForObject("SELECT count(*) FROM nw_platform_system_agent_runs", Long.class);
            return new PersistenceEvidence(invocations, unknown, usage, measured, systems, null);
        } catch (RuntimeException unavailable) { return new PersistenceEvidence(null, null, null, null, null, unavailable.getClass().getSimpleName()); }
    }

    private static Long number(Object value) { return value == null ? null : ((Number) value).longValue(); }

    private boolean holdForBrowsers(RunEvidence direct, RunEvidence delegated) throws Exception {
        String ready = System.getenv("LIVE_TRAJECTORY_READY_FILE"), finish = System.getenv("LIVE_TRAJECTORY_BROWSER_FINISH_FILE");
        if (ready == null && finish == null) return false;
        check(ready != null && finish != null, "TRAJECTORY_BROWSER_HANDSHAKE_INCOMPLETE");
        Path finished = Path.of(finish).toAbsolutePath();
        check(!Files.exists(finished), "TRAJECTORY_STALE_BROWSER_FINISH");
        var identity = new Ready("http://127.0.0.1:" + port, "http://127.0.0.1:" + platformPort, tenant, tenantSlug,
                direct.sessionId(), direct.rootInputId(), direct.runId(), direct.traceId(), TENANT_USERNAME, PLATFORM_USERNAME,
                "calculate_expression", direct.expected(), delegated.sessionId(), delegated.rootInputId(), delegated.runId());
        writeNew(Path.of(ready).toAbsolutePath(), identity);
        long deadline = System.nanoTime() + BROWSER_WINDOW.toNanos();
        while (System.nanoTime() < deadline) {
            check(platformProcess != null && platformProcess.isAlive(), "TRAJECTORY_PLATFORM_EXITED_DURING_BROWSER");
            if (Files.exists(finished)) {
                check(Files.size(finished) <= 16_384, "TRAJECTORY_BROWSER_FINISH_INVALID");
                var receipt = json.readTree(Files.readString(finished));
                check("PASSED".equals(receipt.path("state").asText()), "TRAJECTORY_BROWSER_REJECTED");
                return true;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("TRAJECTORY_BROWSER_FINISH_TIMEOUT");
    }

    /** 仅平台进程读取同一Testcontainers地址；不继承来源库连接作为业务连接。 */
    private void startPlatform() throws Exception {
        Path root = repositoryRoot();
        Path jar = root.resolve("backend-java/skillforge-platform-service/build/libs/skillforge-platform-service.jar");
        check(Files.isRegularFile(jar), "TRAJECTORY_PLATFORM_BOOTJAR_MISSING");
        platformRuntimeDirectory = Files.createTempDirectory("skillforge-trajectory-platform-");
        Path runtimeJar = platformRuntimeDirectory.resolve("platform.jar");
        Files.copy(jar, runtimeJar);
        platformLog = Path.of(required("LIVE_TRAJECTORY_EVIDENCE_FILE")).toAbsolutePath().resolveSibling("platform-startup-" + UUID.randomUUID() + ".log");
        Files.createDirectories(platformLog.getParent());
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var builder = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-jar", runtimeJar.toString(),
                "--server.port=0", "--server.address=127.0.0.1", "--spring.flyway.enabled=false", "--agent-runtime.workers.auto-startup=false",
                "--spring.cloud.nacos.discovery.enabled=false", "--spring.cloud.nacos.config.enabled=false", "--logging.level.root=WARN",
                "--logging.charset.console=UTF-8",
                "--logging.level.org.springframework.boot.web.embedded.tomcat.TomcatWebServer=INFO", "--skillforge.code-execution.preview-port=0",
                "--skillforge.code-execution.preview-origin=", "--skillforge.code-execution.artifact-root=" + artifactRoot(),
                "--skillforge.web.allowed-origins=" + BROWSER_ORIGINS);
        builder.directory(root.resolve("backend-java/skillforge-platform-service").toFile()).redirectErrorStream(true).redirectOutput(platformLog.toFile());
        var env = builder.environment();
        snowflake = configurePlatformSnowflake(env, System.getenv("SNOWFLAKE_WORKER_ID"));
        env.put("SPRING_DATASOURCE_URL", environment.getRequiredProperty("spring.datasource.url"));
        env.put("PLATFORM_DATASOURCE_USERNAME", environment.getRequiredProperty("spring.datasource.username"));
        env.put("PLATFORM_DATASOURCE_PASSWORD", environment.getRequiredProperty("spring.datasource.password"));
        env.put("REDIS_HOST", environment.getRequiredProperty("spring.data.redis.host"));
        env.put("REDIS_PORT", environment.getRequiredProperty("spring.data.redis.port"));
        env.put("REDIS_DATABASE", environment.getProperty("spring.data.redis.database", "0"));
        env.put("SECRET_KEY", properties.getSecretKey()); env.put("PLATFORM_SECRET_KEY", TEST_PLATFORM_JWT_SECRET);
        env.put("PLATFORM_ADMIN_USERNAME", PLATFORM_USERNAME); env.put("PLATFORM_ADMIN_PASSWORD", fixturePassword());
        platformProcess = builder.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            check(platformProcess.isAlive(), "TRAJECTORY_PLATFORM_STARTUP_FAILED");
            var match = LISTENING.matcher(Files.readString(platformLog, StandardCharsets.UTF_8));
            if (match.find()) {
                platformPort = Integer.parseInt(match.group(1));
                try {
                    var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + platformPort + "/v3/api-docs"))
                            .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() == 200) return;
                } catch (java.io.IOException notReady) { }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("TRAJECTORY_PLATFORM_READINESS_TIMEOUT");
    }

    /** 使用生产生成器已支持的正式环境变量；子JVM不能继承机构节点，也不在运行后改全局生成器。 */
    static SnowflakeEvidence configurePlatformSnowflake(Map<String, String> processEnvironment, String institutionWorker) {
        check(INSTITUTION_SNOWFLAKE_WORKER.equals(institutionWorker), "TRAJECTORY_INSTITUTION_SNOWFLAKE_WORKER_REQUIRED");
        processEnvironment.put("SNOWFLAKE_WORKER_ID", PLATFORM_SNOWFLAKE_WORKER);
        check(!institutionWorker.equals(processEnvironment.get("SNOWFLAKE_WORKER_ID")), "TRAJECTORY_SNOWFLAKE_WORKER_COLLISION");
        return new SnowflakeEvidence(institutionWorker, processEnvironment.get("SNOWFLAKE_WORKER_ID"));
    }

    private void stopPlatform() throws Exception {
        String phase = "REQUEST_EXIT";
        try {
            if (platformProcess != null) {
                platformProcess.destroy();
                if (!platformProcess.waitFor(10, TimeUnit.SECONDS)) {
                    phase = "FORCED_EXIT";
                    platformProcess.destroyForcibly();
                    check(platformProcess.waitFor(10, TimeUnit.SECONDS), "TRAJECTORY_PLATFORM_CLEANUP_FAILED");
                }
            }
            cleanup.add(cleanupEvidence("EXIT_CONFIRMED", null));
            if (platformRuntimeDirectory != null) {
                phase = "DELETE_OWNED_JAR";
                Files.deleteIfExists(platformRuntimeDirectory.resolve("platform.jar"));
                phase = "DELETE_OWNED_DIRECTORY";
                Files.deleteIfExists(platformRuntimeDirectory);
            }
            cleanup.add(cleanupEvidence("CLEANED", null));
        } catch (Exception | AssertionError rejected) {
            cleanup.add(cleanupEvidence(phase, rejected));
            throw rejected;
        }
    }

    /** 异常消息/完整系统路径不进入证据；仅保存资源状态和标准化OS原因。 */
    private CleanupEvidence cleanupEvidence(String phase, Throwable failure) {
        boolean alive = platformProcess != null && platformProcess.isAlive();
        Integer exit = platformProcess != null && !alive ? platformProcess.exitValue() : null;
        Path jar = platformRuntimeDirectory == null ? null : platformRuntimeDirectory.resolve("platform.jar");
        int entries = 0;
        String inspectionFailure = null;
        if (platformRuntimeDirectory != null && Files.isDirectory(platformRuntimeDirectory)) {
            try (var children = Files.list(platformRuntimeDirectory)) { entries = (int) children.limit(100).count(); }
            catch (java.io.IOException unavailable) { inspectionFailure = unavailable.getClass().getSimpleName(); }
        }
        return new CleanupEvidence(phase, alive, exit, platformRuntimeDirectory == null ? null : platformRuntimeDirectory.getFileName().toString(),
                platformRuntimeDirectory != null && Files.exists(platformRuntimeDirectory), jar != null && Files.exists(jar),
                jar != null && Files.isWritable(jar), entries, failure == null ? null : failure.getClass().getSimpleName(), cleanupReason(failure), inspectionFailure);
    }

    private static String cleanupReason(Throwable failure) {
        if (failure == null) return null;
        if (failure instanceof java.nio.file.DirectoryNotEmptyException) return "DIRECTORY_NOT_EMPTY";
        if (failure instanceof java.nio.file.NoSuchFileException) return "RESOURCE_ALREADY_MISSING";
        if (failure instanceof java.nio.file.FileSystemException file) {
            String reason = Objects.toString(file.getReason(), "").toLowerCase(java.util.Locale.ROOT);
            if (reason.contains("another process") || reason.contains("being used") || reason.contains("另一个程序") || reason.contains("另一进程")) return "SHARING_VIOLATION";
            if (reason.contains("access is denied") || reason.contains("access denied") || reason.contains("拒绝访问")) return "ACCESS_DENIED";
        }
        if (failure instanceof java.nio.file.AccessDeniedException) return "ACCESS_DENIED";
        if (failure instanceof InterruptedException) return "EXIT_WAIT_INTERRUPTED";
        return failure instanceof AssertionError ? "EXIT_NOT_CONFIRMED" : "UNCLASSIFIED_RESOURCE_FAILURE";
    }

    /** 临时浏览器端口的真实预检必须被当前宿主接受；不修改仓库环境或生产来源配置。 */
    private void verifyBrowserOrigins() throws Exception {
        for (boolean platform : List.of(false, true)) for (String origin : BROWSER_ORIGINS.split(",")) {
            String path = platform ? "/api/v1/platform/auth/login" : "/api/v1/auth/login";
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + (platform ? platformPort : port) + path))
                    .timeout(Duration.ofSeconds(10)).header("Origin", origin).header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type").method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            check(response.statusCode() == 200 && response.headers().firstValue("Access-Control-Allow-Origin").filter(origin::equals).isPresent()
                    && response.headers().firstValue("Access-Control-Allow-Credentials").filter("true"::equals).isPresent(), "TRAJECTORY_BROWSER_CORS_NOT_READY");
        }
    }

    private JsonNode observe(boolean platform, String path, int expected) throws Exception {
        return exchange(platform, platform ? platformToken : token, path, "GET", null, expected);
    }
    private JsonNode request(String path, String method, Object body, int expected) throws Exception {
        return exchange(false, token, path, method, body, expected);
    }
    private JsonNode exchange(boolean platform, String credential, String path, String method, Object body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + (platform ? platformPort : port) + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (credential != null) builder.header(platform ? "X-Platform-Token" : "Authorization", "Bearer " + credential);
        var request = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
        var response = sendManagementRead(http, request, budgets::remainingMillis, Thread::sleep, attempt -> {
            if (attempt.status() == 429 || attempt.status() != expected) managementHttp.add(new ManagementHttpEvidence(
                    stage, platform ? "PLATFORM" : "INSTITUTION", method, safeManagementPath(request.uri()), expected,
                    attempt.number(), attempt.status(), attempt.retryAfterMillis(), attempt.retryScheduled(), attempt.reason()));
        });
        check(response.statusCode() == expected, "TRAJECTORY_HTTP_STATUS_" + response.statusCode() + "_EXPECTED_" + expected);
        return json.readTree(response.body()).path("data");
    }

    /** 仅安全GET按服务端Retry-After重试一次；等待及下一次HTTP超时必须容纳于原live窗口。 */
    static HttpResponse<String> sendManagementRead(HttpClient client, HttpRequest request,
            java.util.function.LongSupplier remainingMillis, ReadWait wait,
            java.util.function.Consumer<ManagementHttpAttempt> evidence) throws Exception {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        var delay = retryAfterMillis(response, Instant.now());
        long timeout = Math.max(1, request.timeout().orElse(Duration.ofSeconds(30)).toMillis());
        boolean safeGet = "GET".equals(request.method())
                && request.bodyPublisher().map(publisher -> publisher.contentLength() == 0).orElse(true);
        long remaining = remainingMillis.getAsLong();
        boolean retry = safeGet && response.statusCode() == 429 && delay.isPresent()
                && remaining >= timeout && delay.getAsLong() <= remaining - timeout;
        evidence.accept(new ManagementHttpAttempt(1, response.statusCode(), delay.isPresent() ? delay.getAsLong() : null,
                retry, retry ? "RETRY_AFTER" : response.statusCode() != 429 ? "SINGLE_REQUEST"
                        : !safeGet ? "METHOD_NOT_RETRIED" : delay.isEmpty() ? "RETRY_AFTER_INVALID" : "WINDOW_INSUFFICIENT"));
        if (!retry) return response;
        long pending = delay.getAsLong();
        while (pending > 0) {
            remaining = remainingMillis.getAsLong();
            if (remaining < timeout || pending > remaining - timeout) return response;
            long chunk = Math.min(60_000, pending);
            wait.pause(chunk); pending -= chunk;
        }
        if (remainingMillis.getAsLong() < timeout) return response;
        response = client.send(request, HttpResponse.BodyHandlers.ofString());
        evidence.accept(new ManagementHttpAttempt(2, response.statusCode(), null, false, "RETRY_LIMIT_REACHED"));
        return response;
    }

    private static OptionalLong retryAfterMillis(HttpResponse<?> response, Instant now) {
        var header = response.headers().firstValue("Retry-After");
        if (header.isEmpty()) return OptionalLong.empty();
        try {
            String value = header.orElseThrow().trim();
            if (value.matches("[0-9]{1,12}")) return OptionalLong.of(Math.multiplyExact(Long.parseLong(value), 1_000));
            var date = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return OptionalLong.of(Math.max(0, Duration.between(now, date).toMillis()));
        } catch (RuntimeException invalid) { return OptionalLong.empty(); }
    }

    static String safeManagementPath(URI uri) {
        String path = uri.getRawPath();
        return path != null && path.startsWith("/api/v1/") && path.length() <= 1_024
                && path.matches("[A-Za-z0-9_/%:.-]+") ? path : "UNRECOGNIZED_PATH";
    }

    @FunctionalInterface interface ReadWait { void pause(long millis) throws InterruptedException; }
    record ManagementHttpAttempt(int number, int status, Long retryAfterMillis, boolean retryScheduled, String reason) { }
    record ManagementHttpEvidence(String stage, String realm, String method, String path, int expectedStatus,
                                  int attempt, int status, Long retryAfterMillis, boolean retryScheduled, String reason) { }
    record SnowflakeEvidence(String institutionWorkerId, String platformWorkerId) { }
    private <T> T scoped(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> {
            TenantContext.setTenantId(tenant);
            try { jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, tenant); return work.get(); }
            finally { TenantContext.clear(); }
        });
    }
    private void writeEvidence(Evidence evidence) throws Exception { writeNew(Path.of(required("LIVE_TRAJECTORY_EVIDENCE_FILE")).toAbsolutePath(), evidence); }
    private void writeNew(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, json.copy().setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    private static Path repositoryRoot() {
        for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent())
            if (Files.isRegularFile(root.resolve("backend-java/settings.gradle.kts"))) return root;
        throw new IllegalStateException("TRAJECTORY_REPOSITORY_ROOT_MISSING");
    }
    private static JsonNode element(JsonNode values, String field, String expected) {
        for (var value : values) if (expected.equals(value.path(field).asText())) return value;
        throw new AssertionError("TRAJECTORY_PUBLISHED_CAPABILITY_MISSING");
    }
    private static String encode(String value) { check(value != null, "TRAJECTORY_REFERENCE_MISSING"); return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String databaseEndpoint(String jdbcUrl) throws java.net.UnknownHostException {
        check(jdbcUrl.startsWith("jdbc:postgresql://"), "TRAJECTORY_DATABASE_URL_INVALID");
        var uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        var address = java.net.InetAddress.getByName(uri.getHost());
        String host = address.isLoopbackAddress() ? "loopback" : address.getHostAddress();
        return host + ":" + (uri.getPort() < 0 ? 5432 : uri.getPort()) + uri.getPath();
    }
    private static String fixturePassword() { return required("LIVE_TRAJECTORY_FIXTURE_PASSWORD"); }
    private static String required(String name) {
        String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException("MISSING_ENV_" + name); return value;
    }
    private static void check(boolean passed, String code) { if (!passed) throw new AssertionError(code); }
    private static String safeFailure(Throwable failure) { return failure.getMessage() != null && failure.getMessage().matches("[A-Z0-9_]{1,160}") ? failure.getMessage() : failure.getClass().getSimpleName(); }
    private static String safeReason(String value) { return value == null ? null : value.matches("[A-Za-z][A-Za-z0-9_.:-]{0,159}") ? value : "UNRECOGNIZED_REASON"; }
    private static Long token(OptionalLong value) { return value.isPresent() ? value.getAsLong() : null; }
    private static String text(Long value) { return value == null ? null : value.toString(); }

    private record Source(PlatformModelConfig original, PlatformModelConfig fixture, String config) {
        @Override public String toString() { return "TrajectorySource[REDACTED]"; }
        ModelEvidence evidence() { return new ModelEvidence(original.id(), original.revision(), original.providerId(), original.modelId(),
                original.contextWindowTokens(), original.maxOutputTokens(), fixture.maxOutputTokens(), true); }
    }
    record ModelEvidence(String id, long revision, String provider, String model, Integer contextWindowTokens,
                         Integer sourceMaxOutputTokens, Integer fixtureMaxOutputTokens, boolean sourceReadOnly) { }
    record RunEvidence(String sessionId, String rootInputId, String runId, String traceId, String status, String expected,
                       String phase, String terminalReason, List<FailureObservation> failures, String diagnosticReadFailure) {
        RunEvidence(String sessionId, String rootInputId, String runId, String traceId, String status, String expected) {
            this(sessionId, rootInputId, runId, traceId, status, expected, null, null, List.of(), null);
        }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record TerminalMetadata(RunStatus status, String reason, String inputId, String rootInputId, String traceId, String agentId) { }
    record FailureObservation(String eventId, String operationId, String kind, String status, String stage, String reasonCode) { }
    record CleanupEvidence(String phase, boolean processAlive, Integer exitCode, String ownedDirectoryName,
                           boolean directoryExists, boolean jarExists, boolean jarWritable, int remainingEntryCount,
                           String failureClass, String reason, String inspectionFailureClass) { }
    record AuxiliarySource(String attemptId, String parentRunId, String sessionId, String rootInputId, String systemRunId,
                           String systemAgentId, long systemVersion, String modelConfigId, long modelRevision, String callPoint) { }
    record PersistenceEvidence(Long modelInvocations, Long unknownInvocations, Long effectiveUsageAttempts, Long measuredUsageEvents,
                               Long systemPurposeRuns, String readFailure) { }
    record ObservationEvidence(String realm, String runId, int nodeCount, List<String> kinds, List<String> visibleRunIds,
                               SessionObservationView.Coverage coverage, SessionObservationView.TokenTotals totals,
                               String toolOutputAvailability, boolean wrongRootRejected, boolean differentSubjectRejected) { }
    record Ready(String institutionBackendURL, String platformBackendURL, String tenantId, String tenantSlug,
                 String sessionId, String rootInputId, String runId, String traceId, String tenantUsername, String platformUsername,
                 String toolName, String expectedToolResultFragment, String delegatedSessionId, String delegatedRootInputId, String delegatedRunId) { }
    record Evidence(String suite, String outcome, String stage, String failureCode, ModelEvidence model, int maximumSends, int actualHttpSends,
                    List<CallEvidence> runtimeCalls, List<PromptCacheRecordingHttpClient.RequestRecord> httpCalls, List<RunEvidence> runs,
                    List<ObservationEvidence> observations, List<CleanupEvidence> cleanup, Object budget,
                    PersistenceEvidence persistence, List<AuxiliarySource> auxiliarySources, List<ManagementHttpEvidence> managementHttp,
                    SnowflakeEvidence snowflake, boolean browserVerified, long elapsedSeconds) { }
    record UsageEvidence(Long input, Long output, Long cached, Long cacheWrite, Long reasoning) {
        static UsageEvidence from(ModelUsage value) { return new UsageEvidence(token(value.inputTokens()), token(value.outputTokens()), token(value.cachedInputTokens()), token(value.cacheWriteInputTokens()), token(value.reasoningOutputTokens())); }
    }
    record CallEvidence(String attemptId, String runId, String sessionId, String category, String terminal, UsageEvidence usage) { }

    /** 最大24次发送；失败、UNKNOWN、缺usage或源漂移后关闭全suite，包括真实子运行。 */
    static final class LiveAudit implements LiveDispatchedModelAudit.Callbacks {
        private final JdbcModelBindings bindings;
        private final PromptCacheRecordingHttpClient recording;
        private final JdbcTemplate jdbc;
        private final java.util.function.BiConsumer<RunRef, String> businessGate;
        private final java.util.function.BiConsumer<ModelCall, ModelUsage> businessUsage;
        private final Map<String, CallEvidence> calls = new LinkedHashMap<>();
        private PlatformModelConfig model;
        private String tenant, stop;
        private Set<String> agents = Set.of();
        private long deadline;
        private boolean closed;
        private final List<AuxiliarySource> auxiliary = new ArrayList<>();
        LiveAudit(JdbcModelBindings bindings, PromptCacheRecordingHttpClient recording, DataSource source,
                java.util.function.BiConsumer<RunRef, String> businessGate,
                java.util.function.BiConsumer<ModelCall, ModelUsage> businessUsage) {
            this.bindings = bindings; this.recording = recording; this.jdbc = new JdbcTemplate(source);
            this.businessGate = Objects.requireNonNull(businessGate);
            this.businessUsage = Objects.requireNonNull(businessUsage);
        }
        synchronized void freeze(PlatformModelConfig model, String tenant, Set<String> agents) {
            check(this.model == null, "TRAJECTORY_AUDIT_ALREADY_FROZEN");
            this.model = model; this.tenant = tenant; this.agents = Set.copyOf(agents); deadline = System.nanoTime() + MODEL_WINDOW.toNanos();
        }
        synchronized void beforeAuxiliarySend(RunRef run, String attempt) {
            ensureHealthy();
            var cost = MeterScope.current();
            check(tenant.equals(run.tenantId().value()) && calls.size() < MAX_SENDS && cost != null
                    && run.runId().equals(cost.runId()) && cost.agentId() != null && agents.contains(cost.agentId().toString())
                    && "system_agent_run".equals(cost.scopeType()) && cost.scopeId() != null
                    && "system-agent.task".equals(cost.callPoint()), "TRAJECTORY_AUXILIARY_OWNER_MISMATCH");
            var frozen = queryRows(jdbc, tenant, "SELECT agent_id::text AS agent_id,version,model_revision,debug,status,config->>'model_config_id' AS model_id "
                    + "FROM nw_platform_system_agent_runs WHERE tenant_id=CAST(? AS bigint) AND id=CAST(? AS bigint) LIMIT 2", tenant, cost.scopeId());
            check(frozen.size() == 1, "TRAJECTORY_AUXILIARY_FROZEN_SOURCE_MISSING");
            var source = frozen.getFirst();
            Long version = number(source.get("version")), modelRevision = number(source.get("model_revision"));
            check(source.get("agent_id") != null && version != null && version > 0
                    && modelRevision != null && model.revision() == modelRevision && model.id().equals(source.get("model_id"))
                    && Boolean.FALSE.equals(source.get("debug")) && "RUNNING".equals(source.get("status")), "TRAJECTORY_AUXILIARY_SOURCE_DRIFT");
            check(calls.putIfAbsent(attempt, new CallEvidence(attempt, run.runId(), cost.sessionId(), "AUXILIARY_MODEL", "STARTED", null)) == null,
                    "TRAJECTORY_DUPLICATE_AUXILIARY_ATTEMPT");
            auxiliary.add(new AuxiliarySource(attempt, run.runId(), cost.sessionId(), cost.rootInputId(), cost.scopeId(), source.get("agent_id").toString(),
                    version, model.id(), modelRevision, cost.callPoint()));
        }
        synchronized List<AuxiliarySource> auxiliarySources() { return List.copyOf(auxiliary); }
        synchronized long remainingMillis() { return Math.max(0, (deadline - System.nanoTime()) / 1_000_000); }
        synchronized void ensureNoUnknown() { check(stop == null && recording.failureCount() == 0, "TRAJECTORY_SUPPLIER_UNKNOWN_OR_FAILED"); }
        synchronized void ensureHealthy() { ensureNoUnknown(); check(!closed && remainingMillis() > 0, "TRAJECTORY_SEND_WINDOW_CLOSED"); }
        synchronized void close() { closed = true; }
        @Override public synchronized boolean start(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider) {
            var identity = call.context().callContext().runIdentity();
            if (closed || stop != null || model == null || remainingMillis() == 0 || calls.size() >= MAX_SENDS || recording.failureCount() != 0
                    || !tenant.equals(identity.runRef().tenantId().value()) || !agents.contains(identity.agentId().value())
                    || !model.modelId().equals(provider.modelId()) || !model.providerId().equals(provider.providerId()) || !model.serviceId().equals(provider.serviceId())
                    || call.generation().maxOutputTokens().orElse(Integer.MAX_VALUE) > OUTPUT_LIMIT || call.reasoning() != ReasoningPolicy.DISABLED
                    || !bindings.find(call.target().binding()).map(JdbcModelBindings.Binding::sourceReference)
                        .filter(("platform-model:" + model.id() + ":" + model.revision())::equals).isPresent()) {
                stop = "TRAJECTORY_SEND_GATE_CLOSED"; return false;
            }
            try { businessGate.accept(identity.runRef(), call.context().dispatch().attemptId()); }
            catch (RuntimeException | AssertionError rejected) { stop = "TRAJECTORY_GLOBAL_BUDGET_REJECTED"; throw rejected; }
            try {
                if (!delegate.start(call, provider)) { stop = "TRAJECTORY_DISPATCH_NOT_AUTHORIZED"; return false; }
            } catch (RuntimeException rejected) { stop = "TRAJECTORY_DISPATCH_AUDIT_FAILED"; throw rejected; }
            String id = call.context().dispatch().attemptId();
            if (calls.putIfAbsent(id, new CallEvidence(id, identity.runRef().runId(), identity.owner().sessionRef().sessionId(), call.budgetCategory().name(), "STARTED", null)) != null) {
                stop = "TRAJECTORY_DUPLICATE_MODEL_ATTEMPT"; return false;
            }
            try {
                recording.beginAttempt(new PromptCacheRecordingHttpClient.AttemptContext(id, identity.runRef().runId(), call.budgetCategory().name(),
                        call.context().dispatch().callKind().name(), call.response().format().getClass().getSimpleName(), call.toolChoice().name()), identity.runRef().runId());
            } catch (RuntimeException concurrent) { stop = "TRAJECTORY_HTTP_CORRELATION_FAILED"; throw concurrent; }
            return true;
        }
        @Override public synchronized void usageReported(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider, long revision, ModelUsage usage) {
            try { delegate.usageReported(call, provider, revision, usage); }
            catch (RuntimeException rejected) { stop = "TRAJECTORY_USAGE_AUDIT_FAILED"; throw rejected; }
        }
        @Override public synchronized void succeeded(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider, ModelUsage usage) {
            try { businessUsage.accept(call, usage); delegate.succeeded(call, provider, usage); }
            catch (RuntimeException rejected) { stop = "TRAJECTORY_RESULT_AUDIT_UNKNOWN"; finish(call, "UNRESOLVED", null); throw rejected; }
            if (usage.inputTokens().isEmpty() || usage.outputTokens().isEmpty()) stop = "TRAJECTORY_SUPPLIER_USAGE_UNKNOWN";
            finish(call, "SUCCEEDED", UsageEvidence.from(usage));
        }
        @Override public synchronized void failed(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider, String reason) {
            stop = "TRAJECTORY_SUPPLIER_FAILED"; delegate.failed(call, provider, reason); finish(call, "FAILED", null);
        }
        @Override public synchronized void unresolved(ModelInvocationAudit delegate, ModelCall call, ModelInvocationAudit.Provider provider, String reason) {
            stop = "TRAJECTORY_SUPPLIER_UNKNOWN"; delegate.unresolved(call, provider, reason); finish(call, "UNRESOLVED", null);
        }
        private void finish(ModelCall call, String terminal, UsageEvidence usage) {
            String id = call.context().dispatch().attemptId();
            var original = calls.get(id);
            if (original != null) calls.put(id, new CallEvidence(id, original.runId(), original.sessionId(), original.category(), terminal, usage));
            recording.terminalAttempt(id, terminal);
        }
        synchronized List<CallEvidence> snapshot() { return List.copyOf(calls.values()); }
    }

    /** 原Guard先完成预算/外调开始事实，真正externalCall前附加live门禁；不重放原事务动作。 */
    static final class LiveAuxiliaryGuard extends ExternalInvocationGuard {
        private final ExternalInvocationGuard original;
        private final LivePromptCacheBudgets budgets;
        LiveAuxiliaryGuard(ExternalInvocationGuard original, ExternalInvocationLedger ledger, AuxiliaryModelBudgets modelBudgets,
                LivePromptCacheBudgets budgets) {
            super(ledger, modelBudgets); this.original = original; this.budgets = budgets;
        }
        @Override public <T> T invokeModel(String tenant, ExternalInvocationRequest request, AuxiliaryModelBudgets.Request demand,
                ExternalCall<T> call) throws Exception {
            return original.invokeModel(tenant, request, demand, () -> {
                var run = ExecutionBudgetScope.current().orElseThrow(() -> new IllegalStateException("TRAJECTORY_AUXILIARY_BUDGET_SCOPE_MISSING")).run();
                try { budgets.beforeProviderSend(run, request.attemptId(), ExecutionBudgetPort.Category.AUXILIARY_MODEL); }
                catch (RuntimeException | AssertionError rejected) { budgets.stop("LIVE_AUXILIARY_SEND_REJECTED"); throw rejected; }
                return call.call();
            });
        }
        @Override public <T> T invoke(String tenant, ExternalInvocationRequest request, ExternalCall<T> call) throws Exception {
            return original.invoke(tenant, request, call);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LiveConfiguration {
        /** 超类默认合成key不能覆盖本lane显式来源key；在任何Crypto单例构建前绑定。 */
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor liveTrajectorySecret(ConfigurableEnvironment environment) {
            return factory -> environment.getPropertySources().addFirst(new MapPropertySource("trajectory-live-source-key",
                    Map.of("skillforge.secret-key", required("SECRET_KEY"))));
        }
        /** Host/Store/Sources始终取得同一个正式Jdbc预算；live观察器不能替代事务能力。 */
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor liveTrajectoryHostBudget() {
            return factory -> factory.getBeanDefinition("hostExecutionBudgets").setPrimary(true);
        }
        @Bean @Primary PromptCacheRecordingHttpClient liveTrajectoryHttpClient() {
            return new PromptCacheRecordingHttpClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
        }
        @Bean LiveAudit liveTrajectoryAudit(JdbcModelBindings bindings, PromptCacheRecordingHttpClient recording, DataSource source,
                org.springframework.beans.factory.ObjectProvider<LivePromptCacheBudgets> budgets) {
            return new LiveAudit(bindings, recording, source,
                    (run, attempt) -> budgets.getObject().beforeProviderSend(run, attempt, ExecutionBudgetPort.Category.BUSINESS_MODEL),
                    (call, usage) -> budgets.getObject().providerUsage(call.context().callContext().runIdentity().runRef(),
                            call.context().dispatch().attemptId(), ExecutionBudgetPort.Usage.reported(usage.inputTokens(), usage.outputTokens())));
        }
        @Bean LivePromptCacheBudgets liveTrajectoryBudgets(DataSource source, PromptCacheRecordingHttpClient recording, LiveAudit audit,
                @org.springframework.beans.factory.annotation.Qualifier("hostExecutionBudgets") ExecutionBudgetPort original) {
            return new LivePromptCacheBudgets(source, recording, MAX_SENDS, audit::beforeAuxiliarySend, original);
        }
        @Bean @Primary ExternalInvocationGuard liveTrajectoryAuxiliaryGuard(
                @org.springframework.beans.factory.annotation.Qualifier("externalInvocationGuard") ExternalInvocationGuard original,
                ExternalInvocationLedger ledger, AuxiliaryModelBudgets modelBudgets, LivePromptCacheBudgets budgets) {
            return new LiveAuxiliaryGuard(original, ledger, modelBudgets, budgets);
        }
        @Bean @Primary AgentRuntimeModelInvocationAudit liveTrajectoryDispatchedAudit(
                @org.springframework.beans.factory.annotation.Qualifier("agentRuntimeModelInvocationAudit") AgentRuntimeModelInvocationAudit original,
                com.skillforge.core.billing.invocation.ExternalInvocationLedger ledger,
                com.skillforge.core.billing.metering.CostRecorder recorder,
                PlatformTransactionManager transactions, LiveAudit callbacks,
                org.springframework.beans.factory.ObjectProvider<com.skillforge.institution.runtime.AgentRuntimeWorkflowNodeExecutions> workflows) {
            return new LiveDispatchedModelAudit(original, ledger, recorder, transactions, workflows, actual -> actual, callbacks);
        }
    }
}
