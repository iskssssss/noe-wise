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
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit.Provider;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 只读当前开发库模型连接；独立新库走真实 HTTP、Provider、工具和生产预算端口，不复制旧配置。 */
@Import(OperationalConfigLiveAcceptance.AuditConfiguration.class)
class OperationalConfigLiveAcceptance extends AbstractIntegrationTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    static final LiveGradleAttemptMeter.Outcome liveMeterOutcome = new LiveGradleAttemptMeter.Outcome();
    @Autowired private javax.sql.DataSource liveMeterDataSource;

    private static final int MAX_ATTEMPTS = 6;
    // 预留使用 UTF-8 保守上界，须覆盖下一动作与主动收尾；实际发送另受六次模型门禁。
    private static final long RUN_TOKENS = 3_000_000;
    private static final String PASSWORD = "LiveConfigIsolated123";
    private static final Path ARTIFACT_ROOT = isolatedArtifactRoot();
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired TenantMemberMapper members;
    @Autowired EndUserMapper users;
    @Autowired PasswordEncoder passwords;
    @Autowired ApplicationContext application;
    @Autowired AgentRuntimeProperties runtime;
    @Autowired SkillForgeProperties resources;
    @Autowired OperationalSettingsService settings;
    @Autowired RunSettingsService runSettings;
    @Autowired AgentRuntimeHostProvisioner provisioner;
    @Autowired AgentRuntimeModelConfiguration modelConfiguration;
    @Autowired ExecutionBudgetPort budgets;
    @Autowired BoundedAudit audit;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String tenant, token, session, agent, run;
    private long user;
    private String stage = "SOURCE_READ";

    /** 当前策略与模型连接分别装配；测试环境没有旧冻结或旧运营快照的兼容路径。 */
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        registry.add("skillforge.secret-key", () -> required("SECRET_KEY"));
        registry.add("sa-token.jwt-secret-key", () -> "live-config-isolated-auth-key");
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
        registry.add("skillforge.code-execution.preview-port", () -> "0");
        registry.add("skillforge.code-execution.preview-origin", () -> "");
        registry.add("skillforge.code-execution.artifact-root", ARTIFACT_ROOT::toString);
        registry.add("logging.level.root", () -> "OFF");
        registry.add("logging.level.com.skillforge.runtime.starter.AgentRuntimeSessionRuntime", () -> "WARN");
    }

    /** 计算器验收独占空产物目录，不启动现有环境的预览监听器。 */
    private static Path isolatedArtifactRoot() {
        try { return Files.createTempDirectory("skillforge-live-config-"); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("ISOLATED_ARTIFACT_ROOT_UNAVAILABLE"); }
    }
    /** 本样本不生成文件；只删除自己创建的空目录。 */
    @AfterAll static void removeEmptyArtifactRoot() throws java.io.IOException { Files.deleteIfExists(ARTIFACT_ROOT); }

    /** 多步骤输入超过 32k；运行中修改当前策略，原 Run 的准备引用和账本仍保持不变。 */
    @Test void realSupplierCompletesLargeInputWithToolsAndKeepsTheFrozenConfiguration() throws Exception {
        var started = Instant.now();
        String outcome = "FAILED", failure = null, terminal = "NOT_SUBMITTED";
        long effectiveInput = 0, measuredInput = 0, toolResults = 0;
        boolean frozenUnchanged = false, usageSettled = false;
        PlatformModelConfig sourceModel = null;
        try {
            Source source = readSource(); sourceModel = source.model(); audit.model(sourceModel);
            stage = "FIXTURE"; fixture(); installModel(source);
            requireRunningRuntimeWorkers(application, runtime);
            stage = "AGENT"; createAgent(); mountCalculator();
            request("/api/v1/agents/publish", "POST", Map.of("agent_id", agent), 200);
            session = request("/api/v1/chat/sessions", "POST", Map.of("agent_id", agent, "end_user_id", user), 200).path("session_id").asText();
            stage = "SUBMIT";
            run = request("/api/v1/chat", "POST", Map.of("content", List.of(Map.of("type", "text", "text", longInput())), "session_id", session,
                    "agent_id", agent, "client_message_id", UUID.randomUUID().toString(), "context", Map.of("end_user_id", user)), 202)
                    .path("execution_id").asText();
            check(!run.isBlank(), "RUN_RECEIPT_MISSING");
            terminal = "ACCEPTED";
            var ref = new RunRef(new TenantId(tenant), run);
            stage = "FREEZE";
            var frozen = scoped(() -> runSettings.requireFrozen(tenant, run));
            var profileRef = new ResultRef(frozen.preparationProfile());
            var profile = modelConfiguration.preparationProfile(new TenantId(tenant), profileRef).orElseThrow();
            var registration = budgets.find(ref).orElseThrow();
            effectiveInput = sourceModel.contextWindowTokens() - sourceModel.maxOutputTokens()
                    - Math.floorDiv(sourceModel.contextWindowTokens() * 5L + 99, 100);
            check(profile.maxTokens() == effectiveInput && profile.maxCharacters() == 32 * 1024 * 1024,
                    "EFFECTIVE_CONTEXT_INCORRECT");
            check(registration.policy().requireEnforced().run().totalTokens() == RUN_TOKENS
                    && registration.policy().requireEnforced().run().inputTokens() == RUN_TOKENS
                    && registration.policy().requireEnforced().run().outputTokens() == RUN_TOKENS, "RUN_TOKEN_CONTRACT_INCORRECT");
            var current = settings.read("task_budget");
            var values = new LinkedHashMap<>(current.values());
            values.put("standard_root_total_tokens", 200_000_000L);
            settings.update("task_budget", current.revision(), values, 1);
            resources.setContextContentCharacters(2_000_000);
            stage = "OBSERVE";
            long deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos();
            ChatHistoryResponse history = null;
            while (System.nanoTime() < deadline) {
                history = history();
                terminal = scoped(() -> jdbc.queryForObject("SELECT status FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?", String.class, tenant, run));
                boolean finalVisible = history.messages().stream().anyMatch(message -> "assistant".equals(message.role())
                        && message.metadata() != null && "final".equals(message.metadata().get("phase")));
                if ("COMPLETED".equals(terminal) && finalVisible || Set.of("FAILED", "TERMINATED", "CANCELLED").contains(terminal)) break;
                check(!audit.stopped(), "SUPPLIER_STOPPED");
                Thread.sleep(1500); // 有上限的真实状态观察，不重发任何输入。
            }
            check("COMPLETED".equals(terminal), "RUN_NOT_COMPLETED");
            String answer = history.messages().stream().filter(message -> "assistant".equals(message.role())
                    && message.metadata() != null && "final".equals(message.metadata().get("phase")))
                    .map(message -> message.content() == null ? "" : message.content()).collect(java.util.stream.Collectors.joining("\n"));
            check(answer.contains("391") && answer.contains("540") && answer.contains("72"), "CALCULATION_RESULT_MISSING");
            stage = "PERSISTED_EVIDENCE";
            toolResults = scoped(() -> jdbc.queryForObject("SELECT count(*) FROM agent_runtime_session_events WHERE tenant_id=? AND run_id=? AND event_type='agent.tool-result'", Long.class, tenant, run));
            check(toolResults >= 3, "REAL_TOOL_RESULTS_MISSING");
            measuredInput = audit.maximumInput();
            check(measuredInput > 32_000 && measuredInput <= effectiveInput && !audit.stopped(), "LARGE_PROVIDER_INPUT_NOT_VERIFIED");
            var restored = scoped(() -> provisioner.prepareOperation(tenant, agent, run, "live:restore-check"));
            frozenUnchanged = restored.profile().equals(profileRef) && budgets.find(ref).orElseThrow().equals(registration)
                    && scoped(() -> runSettings.requireFrozen(tenant, run)).equals(frozen)
                    && modelConfiguration.preparationProfile(new TenantId(tenant), profileRef).orElseThrow().equals(profile);
            check(frozenUnchanged, "FROZEN_CONFIGURATION_DRIFT");
            var rebuilt = new JdbcExecutionBudgets(dataSource).snapshot(ref);
            usageSettled = rebuilt.consumed().inputTokens() == audit.inputTokens() && rebuilt.consumed().outputTokens() == audit.outputTokens()
                    && rebuilt.reserved().totalTokens() == 0;
            check(usageSettled, "PROVIDER_USAGE_NOT_SETTLED");
            int attempts = audit.attempts(); history(); history();
            check(audit.attempts() == attempts, "GET_REPEATED_PROVIDER_CALL");
            outcome = "PASSED";
        } catch (Exception | AssertionError invalid) {
            // 原始异常、请求与回答可能带敏感配置；报告只保存本地原因码和计量元数据。
            failure = failureCode(invalid);
            throw new AssertionError("LIVE_CONFIG_ACCEPTANCE_SEE_SAFE_EVIDENCE");
        } finally {
            audit.close();
            if (session != null && token != null) try { request("/api/v1/chat/sessions/close", "POST",
                    Map.of("session_id", session, "payload", Map.of("reason", "live-config-finished")), 200); } catch (Exception ignored) { }
            var evidence = new Evidence(outcome, stage, failure, terminal,
                    sourceModel == null ? null : sourceModel.modelId(), effectiveInput, audit.maximumInput(), toolResults,
                    audit.attempts(), audit.inputTokens(), audit.outputTokens(), frozenUnchanged, usageSettled,
                    Duration.between(started, Instant.now()).toSeconds(), runFailureCodes());
            Path path = Path.of(required("LIVE_CONFIG_EVIDENCE_FILE")).toAbsolutePath();
            Files.createDirectories(path.getParent()); json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), evidence);
        }
    }

    /** 在来源数据库的只读重复读事务中核对精确发布模型，密文仅在内存使用。 */
    private Source readSource() throws Exception {
        try (var connection = DriverManager.getConnection(required("SPRING_DATASOURCE_URL"),
                required("PLATFORM_DATASOURCE_USERNAME"), required("PLATFORM_DATASOURCE_PASSWORD"))) {
            connection.setReadOnly(true); connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ); connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("SELECT v.config::text FROM nw_platform_models m JOIN nw_platform_model_versions v ON v.model_id=m.id AND v.revision=m.current_revision WHERE m.id=CAST(? AS bigint) AND m.current_revision=?")) {
                query.setString(1, required("LIVE_CONFIG_SOURCE_MODEL_ID")); query.setLong(2, Long.parseLong(required("LIVE_CONFIG_SOURCE_MODEL_REVISION")));
                try (var rows = query.executeQuery()) {
                    check(rows.next(), "SOURCE_MODEL_NOT_PUBLISHED");
                    String config = rows.getString(1);
                    var model = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).readValue(config, PlatformModelConfig.class);
                    check(model.enabled() && "LLM".equals(model.kind()) && model.contextWindowTokens() > 64_000
                            && model.maxOutputTokens() <= 8192, "SOURCE_MODEL_CAPACITY_INVALID");
                    connection.rollback(); return new Source(model, config);
                }
            }
        }
    }

    /** 身份夹具独占新库租户；认证及业务资源仍经真实公开入口。 */
    private void fixture() throws Exception {
        stage = "FIXTURE_TENANT";
        String slug = "live-config-" + UUID.randomUUID(); tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        try {
            stage = "FIXTURE_MEMBER";
            var member = new TenantMemberDO(); member.setId(SnowflakeIds.nextId()); member.setTenantId(Long.valueOf(tenant));
            member.setUsername("live-config-admin"); member.setRole("admin"); member.setPermissions(List.of()); member.setStatus("active");
            member.setPasswordHash(passwords.encode(PASSWORD)); member.setCreatedAt(Instant.now()); members.insert(member);
            stage = "FIXTURE_END_USER";
            var endUser = new EndUserDO(); endUser.setId(SnowflakeIds.nextId()); endUser.setTenantId(Long.valueOf(tenant)); endUser.setStatus("active");
            endUser.setDisplayName("隔离真实验收用户"); endUser.setFirstSeenAt(Instant.now()); endUser.setLastActiveAt(Instant.now()); endUser.setCreatedAt(Instant.now()); users.insert(endUser); user = endUser.getId();
        } finally { TenantContext.clear(); }
        stage = "FIXTURE_LOGIN";
        token = request("/api/v1/auth/login", "POST", Map.of("tenant_slug", slug, "username", "live-config-admin", "password", PASSWORD), 200).path("access_token").asText();
        check(!token.isBlank(), "LOGIN_TOKEN_MISSING");
        stage = "FIXTURE_ACTOR";
        jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash) VALUES(1,'live-config-fixture','unused') ON CONFLICT(id) DO NOTHING");
    }

    /** 只安装已核对的模型连接；所有运营预算和技术策略使用新契约默认值。 */
    private void installModel(Source source) {
        stage = "INSTALL_MODEL";
        scoped(() -> {
            jdbc.update("INSERT INTO nw_platform_models(id,current_revision,draft,draft_changed) VALUES(CAST(? AS bigint),?,CAST(? AS jsonb),false)",
                    source.model().id(), source.model().revision(), source.config());
            jdbc.update("INSERT INTO nw_platform_model_versions(model_id,revision,config,actor_id) VALUES(CAST(? AS bigint),?,CAST(? AS jsonb),'live-config-fixture')", source.model().id(), source.model().revision(), source.config());
            jdbc.update("INSERT INTO nw_tenant_model_selections(tenant_id,llm_model_config_id) VALUES(CAST(? AS bigint),CAST(? AS bigint))", tenant, source.model().id());
            return null;
        });
    }

    /** 五项覆盖显式走真实 Agent 保存契约；次数和时间同时限制付费测试。 */
    private void createAgent() throws Exception {
        agent = request("/api/v1/agents", "POST", Map.of("agent_key", "live-config-" + UUID.randomUUID(),
                "name", "大输入多步骤验收助手", "system_prompt", "按用户明确步骤使用真实工具核算，最终保留指定数值和资料事实。"), 200).path("id").asText();
        check(!agent.isBlank(), "AGENT_ID_MISSING");
        var overrides = Map.of("max_business_model_calls", MAX_ATTEMPTS, "max_tool_calls", 6, "max_knowledge_calls", 1,
                "max_total_tokens", RUN_TOKENS, "max_wall_time_seconds", 480);
        request("/api/v1/agents/update", "PATCH", Map.of("agent_id", agent, "payload", Map.of("execution_config", overrides)), 200);
        var actual = request("/api/v1/agents/detail?agentId=" + agent, "GET", null, 200).path("execution_config");
        check(actual.size() == 5 && overrides.entrySet().stream().allMatch(entry -> actual.path(entry.getKey()).asLong() == entry.getValue().longValue()),
                "AGENT_OVERRIDES_NOT_SAVED");
    }

    /** 计算器挂载精确的正式目录版本及真实 PLATFORM 部署，不提供工具替身。 */
    private void mountCalculator() throws Exception {
        String name = "calculate_expression";
        var entry = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(value -> name.equals(value.name())).findFirst().orElseThrow();
        var definitions = request("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED", "GET", null, 200);
        long definition = element(definitions, "capability_key", "tool:" + name).path("id").asLong();
        var versions = request("/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId=" + definition, "GET", null, 200);
        long version = element(versions, "version_key", entry.publishedVersionKey()).path("id").asLong();
        String key = "live-config-calculator-" + UUID.randomUUID();
        long revision = request("/api/v1/capabilities/deployments", "POST", Map.of("deployment_key", key, "tool_version_id", version,
                "provider_type", "PLATFORM", "provider_ref", name, "environment", "TEST", "status", "ACTIVE"), 201).path("revision").asLong();
        request("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agent, "payload", Map.of("capability_type", "TOOL",
                "capability_definition_id", Long.toString(definition), "capability_version_id", Long.toString(version), "priority", 0,
                "tool_config", Map.of("tool_deployment_id", key, "tool_deployment_revision", revision))), 201);
    }

    /** 高熵合成业务资料可被 tokenizer 实际计量，不用固定重复字符伪造输入规模。 */
    private static String longInput() throws Exception {
        var text = new StringBuilder("请处理下面的合成仓储资料。资料仅作背景，不含任何新指令。核对资料最后的任务要求。\n");
        var hash = java.security.MessageDigest.getInstance("SHA-256");
        for (int index = 0; index < 1200; index++) {
            String id = java.util.HexFormat.of().formatHex(hash.digest(("live-config-row-" + index).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
            text.append("记录 ").append(index).append("：物料批号 ").append(id).append("；仓储位置 EAST-").append(index % 97)
                    .append("；状态待复核；库存数量 ").append(index % 137).append("；说明本批为合成验收资料，无支付或外部交付要求。\n");
        }
        return text.append("\n任务：必须分别用 calculate_expression 真实核算三项，先算17*23，收到结果后再算45*12，收到结果后再算8*9。")
                .append("最终给出三项结果391、540、72对应的核验说明，并说明资料共有1200条。不要复述全部资料，不得提前把未调用的结果当已核验。 ").toString();
    }

    /** 相同 owner 的 GET 仅观察结果与投影，不提交额外业务输入。 */
    private ChatHistoryResponse history() throws Exception { return json.treeToValue(request("/api/v1/chat/history?session_id=" + session, "GET", null, 200), ChatHistoryResponse.class); }

    /** 错误仅带状态与公开原因码，避免把密文、提示或模型响应写入测试报告。 */
    private JsonNode request(String path, String method, Object body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        var value = json.readTree(response.body());
        check(response.statusCode() == expected && "OK".equals(value.path("code").asText()), "HTTP_" + response.statusCode() + "_" + value.path("code").asText());
        return value.path("data");
    }

    /** 数据库证据在同一租户事务内读取；结束时清理线程身份。 */
    private <T> T scoped(java.util.function.Supplier<T> work) {
        TenantContext.setTenantId(tenant);
        try { return new TransactionTemplate(transactions).execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, tenant); return work.get();
        }); } finally { TenantContext.clear(); }
    }
    /** 目录只接受指定身份，缺失时失败关闭。 */
    private static JsonNode element(JsonNode array, String field, String expected) {
        for (var value : array) if (expected.equals(value.path(field).asText())) return value;
        throw new IllegalStateException("CAPABILITY_MISSING");
    }
    /** 付费验收参数必须显式提供，不从历史环境猜测。 */
    private static String required(String key) { return Objects.requireNonNull(System.getenv(key), "MISSING_ENV_" + key); }
    /** 稳定本地原因码用于安全证据。 */
    private static void check(boolean passed, String code) { if (!passed) throw new AssertionError(code); }
    /** 只记录数据库状态码与 schema 标识，不记录 SQL 参数、详情或错误行。 */
    private static String failureCode(Throwable invalid) {
        for (Throwable cause = invalid; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.postgresql.util.PSQLException postgres) {
                var error = postgres.getServerErrorMessage();
                return "DATABASE_" + postgres.getSQLState() + (error == null ? "" : "_" + error.getTable() + "_" + error.getColumn() + "_" + error.getConstraint());
            }
        }
        return invalid.getMessage() != null && invalid.getMessage().matches("[A-Z0-9_]{1,100}")
                ? invalid.getMessage() : invalid.getClass().getSimpleName();
    }
    /** 运行失败只读持久原因码；不解码或保存完整 checkpoint、用户输入和供应商输出。 */
    private List<String> runFailureCodes() {
        if (run == null || tenant == null) return List.of();
        try {
            return scoped(() -> jdbc.queryForList("SELECT reason_code FROM nw_runtime_session_terminal_failures WHERE tenant_id=? AND run_id=? "
                    + "UNION SELECT reason_code FROM agent_runtime_executions WHERE tenant_id=? AND run_id=? AND reason_code IS NOT NULL",
                    String.class, tenant, run, tenant, run)).stream()
                    .map(code -> code.matches("[A-Za-z0-9_.:-]{1,160}") ? code : "UNRECOGNIZED_RUNTIME_REASON").toList();
        } catch (RuntimeException unavailable) { return List.of("RUNTIME_DIAGNOSTIC_UNAVAILABLE"); }
    }
    private record Source(PlatformModelConfig model, String config) { @Override public String toString() { return "Source[frozen]"; } }
    private record Evidence(String outcome, String stage, String failure, String terminal, String model,
            long effectiveInputTokens, long maximumProviderInputTokens, long toolResults, int modelAttempts,
            long providerInputTokens, long providerOutputTokens, boolean frozenUnchanged, boolean usageSettled, long elapsedSeconds,
            List<String> runFailureCodes) { }

    /** 六次发送上限及失败即停仅限制付费验收；正式审计、账本与事务装配完整保留。 */
    static final class BoundedAudit implements LiveDispatchedModelAudit.Callbacks {
        private PlatformModelConfig model;
        private int attempts;
        private boolean stopped;
        private long input, output, maximum;
        synchronized void model(PlatformModelConfig model) { this.model = model; }
        @Override public synchronized boolean start(ModelInvocationAudit delegate, ModelCall call, Provider provider) {
            if (stopped || model == null || attempts >= MAX_ATTEMPTS || !model.modelId().equals(provider.modelId())
                    || !model.providerId().equals(provider.providerId()) || call.generation().maxOutputTokens().orElse(Integer.MAX_VALUE) > model.maxOutputTokens()) {
                stopped = true; return false;
            }
            boolean started = delegate.start(call, provider); if (started) attempts++; return started;
        }
        @Override public void usageReported(ModelInvocationAudit delegate, ModelCall call, Provider provider, long revision, ModelUsage usage) { delegate.usageReported(call, provider, revision, usage); }
        @Override public synchronized void succeeded(ModelInvocationAudit delegate, ModelCall call, Provider provider, ModelUsage usage) {
            delegate.succeeded(call, provider, usage);
            if (usage.inputTokens().isEmpty() || usage.outputTokens().isEmpty()) { stopped = true; return; }
            input += usage.inputTokens().getAsLong(); output += usage.outputTokens().getAsLong(); maximum = Math.max(maximum, usage.inputTokens().getAsLong());
        }
        @Override public synchronized void failed(ModelInvocationAudit delegate, ModelCall call, Provider provider, String reason) { stopped = true; delegate.failed(call, provider, reason); }
        @Override public synchronized void unresolved(ModelInvocationAudit delegate, ModelCall call, Provider provider, String reason) { stopped = true; delegate.unresolved(call, provider, reason); }
        synchronized int attempts() { return attempts; }
        synchronized long inputTokens() { return input; }
        synchronized long outputTokens() { return output; }
        synchronized long maximumInput() { return maximum; }
        synchronized boolean stopped() { return stopped; }
        synchronized void close() { stopped = true; }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class AuditConfiguration {
        @Bean BoundedAudit boundedLiveConfigAudit() { return new BoundedAudit(); }
        @Bean @Primary AgentRuntimeModelInvocationAudit boundedLiveConfigDispatchedAudit(
                @org.springframework.beans.factory.annotation.Qualifier("agentRuntimeModelInvocationAudit") AgentRuntimeModelInvocationAudit original,
                com.skillforge.core.billing.invocation.ExternalInvocationLedger ledger,
                com.skillforge.core.billing.metering.CostRecorder recorder,
                org.springframework.transaction.PlatformTransactionManager transactions,
                BoundedAudit callbacks, DataSource source,
                org.springframework.beans.factory.ObjectProvider<com.skillforge.institution.runtime.AgentRuntimeWorkflowNodeExecutions> workflows) {
            return new LiveDispatchedModelAudit(original, ledger, recorder, transactions, workflows,
                    actual -> LiveGradleAttemptMeter.wrap(actual, source), callbacks);
        }
    }

    @org.junit.jupiter.api.AfterEach void collectLiveAttemptEvidence() { LiveGradleAttemptMeter.snapshot(liveMeterDataSource, tenant); }
}
