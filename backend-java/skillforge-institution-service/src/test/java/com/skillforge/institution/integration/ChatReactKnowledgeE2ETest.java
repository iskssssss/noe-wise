package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.shared.security.WebhookSignature;
import com.skillforge.core.chat.application.TraceViewService;
import com.skillforge.core.chat.tracing.TraceQuery;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.KnowledgePortal;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * 机构配置即用的真实 ReAct Knowledge API/SSE E2E。
 *
 * <p>租户和登录成员是认证前置 fixture；LLM、知识库、文档、Revision、Agent、CapabilityMount 和
 * Session 全部经公开 API 建立。Inbox、ReAct Execution、Policy/Snapshot、Knowledge
 * Observation、引用、Usage、Outbox 和 Webhook 只能由真实 Runtime 写入。</p>
 */
@Import(ChatReactKnowledgeE2ETest.SqlProfileConfiguration.class)
class ChatReactKnowledgeE2ETest extends AbstractIntegrationTest {

    private static final String PASSWORD = "ReactKnowledgeE2e123";
    private static final String WEBHOOK_SECRET = "react-knowledge-e2e-webhook-secret";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration TOTAL_LATENCY_BUDGET = Duration.ofSeconds(10);
    private static final boolean SQL_PROFILE_ENABLED = "1".equals(System.getenv("SKILLFORGE_PERF_SQL"));

    @DynamicPropertySource
    static void enableRuntimeWorkers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
        System.setProperty("skillforge.runtime.session.drive-failure-dir",
                Path.of("build", "runtime-e2e", "drive-failures").toAbsolutePath().toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private PlanMapper planMapper;

    @Autowired
    private TenantMemberMapper tenantMemberMapper;

    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TraceViewService traceViewService;

    @Autowired
    private EndUserMapper endUserMapper;

    @Autowired
    private AgentRuntimeHostProvisioner provisioner;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AgentRuntimeProperties runtimeProperties;

    @Autowired
    private PlatformModelCatalogService modelCatalog;

    @Autowired
    private ExecutionStore executionStore;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private DeterministicOpenAiProvider provider;
    private WebhookReceiver webhook;
    private String runId;
    private String sentinel;
    private String tenantId;
    private String tenantSlug;
    private String username;
    private String requesterMemberId;
    private String accessToken;
    private String kbId;
    private String knowledgeSourceVersion;
    private long knowledgeRevisionId;
    private long knowledgeCapabilityDefinitionId;
    private long knowledgeCapabilityVersionId;
    private String knowledgeHandle;
    private String agentId;
    private String sessionId;
    private Long endUserId;

    @BeforeEach
    void setUpFixture() throws Exception {
        runId = "react-knowledge-e2e-" + SnowflakeIds.nextId();
        sentinel = "E2E_SENTINEL_" + SnowflakeIds.nextId();
        tenantSlug = "e2e-react-knowledge-" + SnowflakeIds.nextId();
        username = "e2e-react-admin-" + SnowflakeIds.nextId();
        provider = new DeterministicOpenAiProvider(objectMapper, runId, sentinel);
        provider.start();
        webhook = new WebhookReceiver();
        webhook.start();

        tenantId = createTenant(tenantMapper, planMapper, tenantSlug);
        TenantContext.setTenantId(tenantId);
        try {
            createMember();
            endUserId = createDefaultEndUser();
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void tearDownFixture() {
        TenantContext.clear();
        if (webhook != null) {
            webhook.close();
        }
        if (provider != null) {
            provider.close();
        }
    }

    @Test
    void institutionConfigurationTraversesKnowledgeReActAndRuntimeLedger() throws Exception {
        login();
        configureProviderThroughPublicApi();
        TenantContext.setTenantId(tenantId);
        try {
            provisioner.provision(tenantId);
            requireRunningRuntimeWorkers(applicationContext, runtimeProperties);
        } finally {
            TenantContext.clear();
        }
        createKnowledgeThroughPublicApi();
        createAgentAndMountThroughPublicApi();
        createSessionThroughPublicApi();
        createWebhookSubscription();

        String question = knowledgeQuestion(sentinel);
        assertTrue(question.length() > sentinel.length());
        Map<String, Object> payload = chatPayload(question);
        HttpResponse<String> first = postSync(payload);
        assertEquals(200, first.statusCode(), first.body());
        JsonNode body = successData(first);
        assertEquals("queued", body.path("status").asText(), first.body());
        assertEquals("run:" + runId, body.path("execution_id").asText(), first.body());
        RunRef actualRun = new RunRef(new TenantId(tenantId), body.path("execution_id").asText());
        String receiptTrace = objectMapper.readTree(first.body()).path("trace_id").asText();

        TenantContext.setTenantId(tenantId);
        try {
            awaitExecution(actualRun);
            assertExecutionFactsAndCausality(actualRun, question, receiptTrace);
        } finally {
            TenantContext.clear();
        }

        assertPublicKnowledgeHistory();
        await("actual signed Webhook from the completed run", this::webhookHasFinalAnswer);
        assertWebhookMatchesOutbox(actualRun);
        ExecutionSnapshot acceptedFacts = executionSnapshot(actualRun);
        int providerRequests = provider.requestCount();
        HttpResponse<String> replay = postSync(payload);
        assertEquals(200, replay.statusCode(), replay.body());
        JsonNode replayData = successData(replay);
        assertFalse(body.path("replayed").asBoolean(true));
        assertTrue(replayData.path("replayed").asBoolean(false));
        var originalReceipt = ((com.fasterxml.jackson.databind.node.ObjectNode) body).deepCopy();
        var replayedReceipt = ((com.fasterxml.jackson.databind.node.ObjectNode) replayData).deepCopy();
        originalReceipt.remove("replayed");
        replayedReceipt.remove("replayed");
        assertEquals(originalReceipt, replayedReceipt);
        HttpResponse<String> conflict = postSync(chatPayload("冲突 payload " + sentinel));
        assertTrue(conflict.statusCode() >= 400 && conflict.statusCode() < 500, conflict.body());

        TenantContext.setTenantId(tenantId);
        try {
            assertEquals(providerRequests, provider.requestCount());
            assertEquals(acceptedFacts, executionSnapshot(actualRun),
                    "原输入重放及冲突必须保留原Run/Action/Attempt/材料/事件，不新增知识或外调");
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 固定工作量的真实 HTTP/SSE Runtime 时延基线。
     *
     * <p>计时从聊天请求发出开始，到客户端收到 {@code execution.completed} 为止；租户、知识库、
     * Agent 和会话的 fixture 均在计时前完成。Provider 以本地零延迟响应固定两轮独立工具协议；日志中的
     * {@code model_server_ms} 仅为该 fixture 的服务端处理时间，{@code non_model_estimate_ms} 是据此
     * 得出的估算值，不能视为完整模型外调时延。</p>
     */
    @Test
    @org.junit.jupiter.api.Tag("runtime-latency")
    void runtimeLatencyStaysWithinTenSecondsForThreeModelOneKnowledgeSsePath() throws Exception {
        login();
        configureProviderThroughPublicApi();
        TenantContext.setTenantId(tenantId);
        try {
            provisioner.provision(tenantId);
            requireRunningRuntimeWorkers(applicationContext, runtimeProperties);
        } finally {
            TenantContext.clear();
        }
        createKnowledgeThroughPublicApi();
        createAgentAndMountThroughPublicApi();
        createSessionThroughPublicApi();

        SqlProfileDataSource.reset();
        long startedAt = System.nanoTime();
        String question = knowledgeQuestion(sentinel);
        SseCapture sse = postSse(chatPayload(question));
        long totalElapsedNanos = System.nanoTime() - startedAt;
        SqlProfileDataSource.printSummary();

        assertEquals(200, sse.statusCode(), sse.raw());
        assertTrue(sse.events().contains("execution.completed"), sse.raw());
        assertTrue(sse.raw().contains("基于随机知识文档 " + sentinel), sse.raw());
        TenantContext.setTenantId(tenantId);
        try {
            await("runtime knowledge call, completed run and model server timing", () ->
                    runtimeFactsReady() && provider.requestServerElapsedNanos().size() == 2);
            assertRuntimeFactsAndCausality(question);
            assertEquals(1, countRuntime("select count(*) from nw_runtime_session_knowledge_observations "
                    + "where tenant_id = ? and run_id = ?", tenantId, "run:" + runId));
            printRuntimeEventGaps();
        } finally {
            TenantContext.clear();
        }
        assertEquals(List.of("knowledge_query", "respond"), provider.responseKinds());
        assertEquals(2, provider.requestCount());
        List<Long> modelServerElapsedNanos = provider.requestServerElapsedNanos();
        assertEquals(2, modelServerElapsedNanos.size());
        long modelServerElapsedNanosTotal = modelServerElapsedNanos.stream().mapToLong(Long::longValue).sum();
        long nonModelEstimateNanos = Math.max(0L, totalElapsedNanos - modelServerElapsedNanosTotal);
        System.out.printf("RUNTIME_LATENCY scenario=two-model-one-knowledge-sse total_ms=%.3f "
                        + "model_server_ms=%.3f non_model_estimate_ms=%.3f model_calls=%d knowledge_calls=%d%n",
                nanosToMillis(totalElapsedNanos), nanosToMillis(modelServerElapsedNanosTotal),
                nanosToMillis(nonModelEstimateNanos), provider.requestCount(), 1);
        assertTrue(totalElapsedNanos <= TOTAL_LATENCY_BUDGET.toNanos(),
                () -> "Runtime total latency exceeded " + TOTAL_LATENCY_BUDGET
                        + "; total_ms=" + nanosToMillis(totalElapsedNanos)
                        + ", model_server_ms=" + nanosToMillis(modelServerElapsedNanosTotal)
                        + ", non_model_estimate_ms=" + nanosToMillis(nonModelEstimateNanos));
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private void printRuntimeEventGaps() {
        if (!SQL_PROFILE_ENABLED) {
            return;
        }
        List<RuntimeEventTiming> events = jdbc.query("select event_type, session_sequence, occurred_at_epoch_second, occurred_at_nano "
                        + "from agent_runtime_session_events where tenant_id = ? and run_id = ? order by session_sequence",
                (result, row) -> new RuntimeEventTiming(result.getString("event_type"), result.getLong("session_sequence"),
                        result.getLong("occurred_at_epoch_second"), result.getInt("occurred_at_nano")), tenantId, "run:" + runId);
        for (int index = 1; index < events.size(); index++) {
            RuntimeEventTiming previous = events.get(index - 1);
            RuntimeEventTiming current = events.get(index);
            double gapMillis = (current.epochSecond() - previous.epochSecond()) * 1_000.0
                    + (current.nano() - previous.nano()) / 1_000_000.0;
            System.out.printf("RUNTIME_EVENT_GAP from_sequence=%d from_type=%s to_sequence=%d to_type=%s gap_ms=%.3f%n",
                    previous.sequence(), previous.type(), current.sequence(), current.type(), gapMillis);
        }
    }

    private record RuntimeEventTiming(String type, long sequence, long epochSecond, int nano) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlProfileConfiguration {
        @Bean
        ModelProviderCatalog modelCatalog() {
            var providers = new ArrayList<ModelProvider>();
            new DeterministicModelCatalogConfiguration().deterministicModelCatalog().presets()
                    .forEach(preset -> providers.add(() -> preset));
            new KnowledgeEmbeddingFixture.Configuration().knowledgeEmbeddingHttpCatalog().presets().stream()
                    .filter(preset -> preset.providerId().equals("knowledge-fixture"))
                    .forEach(preset -> providers.add(() -> preset));
            return new ModelProviderCatalog(providers);
        }

        @Bean
        static BeanPostProcessor performanceSqlDataSourceBeanPostProcessor() {
            return SqlProfileDataSource.beanPostProcessor();
        }
    }

    private void configureProviderThroughPublicApi() throws Exception {
        var model = modelCatalog.create(new ModelWrite(
                null, "ReAct Knowledge E2E " + runId, "LLM", true,
                "custom", "custom-openai-compatible", "custom-v1",
                "openai-chat-completions", "bearer-api-key",
                provider.endpoint(), "react-knowledge-fixture", "react-knowledge-fixture-key",
                4096, 32768, 1024, 0.2, "DISABLED", 30,
                null, null, null, null, null, "CNY"), "integration-fixture");
        modelCatalog.publish(model.id(), model.revision(), "integration-fixture");
        // 即使选择关键词索引，知识库也要求冻结合法的向量模型配置。
        var embedding = modelCatalog.create(new ModelWrite(
                null, "Knowledge embedding fixture " + runId, "EMBEDDING", true,
                "knowledge-fixture", "knowledge-embedding-fixture", "knowledge-fixture-v1",
                "openai-embeddings", "bearer-api-key",
                provider.endpoint(), "embedding-fixture", "embedding-fixture-key",
                null, null, null, null, null, 30,
                1024, null, null, null, null, "CNY"), "integration-fixture");
        modelCatalog.publish(embedding.id(), embedding.revision(), "integration-fixture");
        Map<String, Object> selections = new LinkedHashMap<>();
        selections.put("llm_model_config_id", model.id());
        selections.put("embedding_model_config_id", embedding.id());
        selections.put("rerank_model_config_id", null);
        HttpResponse<String> saved = send("/api/v1/settings/model-selections", "PUT", selections);
        assertEquals(200, saved.statusCode(), saved.body());
        JsonNode response = successData(saved);
        assertEquals(model.id(), response.path("llm_model_config_id").asText(), saved.body());
        assertEquals(embedding.id(), response.path("embedding_model_config_id").asText(), saved.body());
        assertTrue(response.path("rerank_model_config_id").isNull(), saved.body());
    }

    private void createKnowledgeThroughPublicApi() throws Exception {
        var published = new RuntimeExecutionKnowledgeFixture(objectMapper, httpClient, baseUrl(), accessToken)
                .publish(runId, sentinel);
        kbId = published.kbId();
        knowledgeSourceVersion = published.sourceVersion();
        knowledgeRevisionId = published.revisionId();
        knowledgeCapabilityDefinitionId = published.definitionId();
        knowledgeCapabilityVersionId = published.capabilityVersionId();
    }
    private void createAgentAndMountThroughPublicApi() throws Exception {
        Map<String, Object> budget = Map.of(
                "max_business_model_calls", 4,
                "max_tool_calls", 1,
                "max_knowledge_calls", 1,
                // 覆盖真实发送的保守输入预留、下一次动作与收尾；不是本地 Provider 的报告用量。
                "max_total_tokens", 250_000,
                "max_wall_time_seconds", 180);
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("agent_key", "e2e-react-knowledge-agent-" + runId);
        create.put("name", "ReAct Knowledge E2E Agent");
        create.put("system_prompt", "你只能依据已挂载知识能力回答，并且必须引用实际证据。");
        HttpResponse<String> created = send("/api/v1/agents", "POST", create);
        assertEquals(200, created.statusCode(), created.body());
        agentId = successData(created).path("id").asText();
        assertFalse(agentId.isBlank());
        HttpResponse<String> configured = send("/api/v1/agents/update", "PATCH",
                Map.of("agent_id", agentId, "payload", Map.of("execution_config", budget)));
        assertEquals(200, configured.statusCode(), configured.body());
        successData(configured);

        Map<String, Object> mountConfig = Map.of(
                "mode", "PINNED_REVISION",
                "pinned_revision_id", Long.toString(knowledgeRevisionId));
        Map<String, Object> mount = new LinkedHashMap<>();
        mount.put("capability_type", "KNOWLEDGE_SOURCE");
        mount.put("capability_definition_id", Long.toString(knowledgeCapabilityDefinitionId));
        mount.put("capability_version_id", Long.toString(knowledgeCapabilityVersionId));
        mount.put("knowledge_config", mountConfig);
        mount.put("priority", 0);
        HttpResponse<String> mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", mount));
        assertEquals(201, mounted.statusCode(), mounted.body());
        JsonNode mountResponse = successData(mounted);
        assertTrue(mountResponse.path("mounts").isArray(), mounted.body());
        assertFalse(mountResponse.path("mounts").path(0).path("mount_id").asText().isBlank(),
                mounted.body());
        knowledgeHandle = "knowledge:" + mountResponse.path("mounts").path(0).path("mount_id").asText();
        provider.requireKnowledge(knowledgeHandle, kbId, knowledgeSourceVersion);
        HttpResponse<String> published = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId));
        assertEquals(200, published.statusCode(), published.body());
        successData(published);
    }

    private void createSessionThroughPublicApi() throws Exception {
        HttpResponse<String> created = send("/api/v1/chat/sessions", "POST",
                Map.of("agent_id", agentId, "end_user_id", endUserId));
        assertEquals(200, created.statusCode(), created.body());
        sessionId = successData(created).path("session_id").asText();
        assertFalse(sessionId.isBlank());
    }

    private void createWebhookSubscription() throws Exception {
        HttpResponse<String> created = send("/api/v1/webhook-subscriptions", "POST", Map.of(
                "session_id", sessionId,
                "endpoint_url", webhook.endpoint(),
                "signing_secret", WEBHOOK_SECRET));
        assertEquals(201, created.statusCode(), created.body());
        assertFalse(successData(created).path("id").asText().isBlank(), created.body());
    }

    private void createMember() {
        var member = new com.skillforge.core.foundation.persistence.TenantMemberDO();
        member.setId(SnowflakeIds.nextId());
        member.setTenantId(Long.parseLong(tenantId));
        member.setUsername(username);
        member.setRole("admin");
        member.setPermissions(List.of());
        member.setStatus("active");
        member.setPasswordHash(passwordEncoder.encode(PASSWORD));
        member.setCreatedAt(Instant.now());
        tenantMemberMapper.insert(member);
        initializeTenantAdministrator(tenantId, member.getId());
        requesterMemberId = member.getId().toString();
    }

    private Long createDefaultEndUser() {
        EndUserDO user = new EndUserDO();
        user.setId(SnowflakeIds.nextId());
        user.setTenantId(Long.parseLong(tenantId));
        user.setDisplayName("React Knowledge E2E User");
        user.setStatus("active");
        user.setFirstSeenAt(Instant.now());
        user.setLastActiveAt(Instant.now());
        user.setCreatedAt(Instant.now());
        endUserMapper.insert(user);
        return user.getId();
    }

    private void login() throws Exception {
        Map<String, Object> body = Map.of(
                "tenant_slug", tenantSlug, "username", username, "password", PASSWORD);
        HttpResponse<String> response = send("/api/v1/auth/login", "POST", body, null);
        assertEquals(200, response.statusCode(), response.body());
        accessToken = successData(response).path("access_token").asText();
        assertFalse(accessToken.isBlank());
    }

    private Map<String, Object> chatPayload(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", List.of(Map.of("type", "text", "text", message)));
        body.put("session_id", sessionId);
        body.put("agent_id", agentId);
        body.put("client_message_id", runId);
        body.put("context", Map.of("end_user_id", endUserId));
        return body;
    }

    private static String knowledgeQuestion(String sentinel) {
        return "这是一次机构知识问答验收。请先理解这段包含业务背景、回答格式、审计要求和来源约束的长问题，"
                + "再检索已挂载的知识；不要把整段问题原样当作检索词。请给出随机哨兵值 " + sentinel
                + "，引用实际证据，并排除未挂载来源、未批准版本及其他租户的内容。";
    }

    private SseCapture postSse(Map<String, Object> body) throws Exception {
        HttpRequest submission = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/v1/chat"))
                .timeout(POLL_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> accepted = httpClient.send(submission, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode(), accepted.body());
        JsonNode receipt = successData(accepted);
        assertEquals("queued", receipt.path("status").asText(), accepted.body());
        assertEquals(sessionId, receipt.path("session_id").asText(), accepted.body());
        assertEquals("run:" + runId, receipt.path("execution_id").asText(), accepted.body());
        String acceptedSession = java.net.URLEncoder.encode(receipt.path("session_id").asText(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/v1/chat/events?session_id=" + acceptedSession))
                .timeout(POLL_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "text/event-stream")
                .GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        boolean eventStream = response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream");
        if (response.statusCode() != 200 || !eventStream) response.body().close();
        assertEquals(200, response.statusCode());
        assertTrue(eventStream, response.headers().toString());
        List<String> events = new CopyOnWriteArrayList<>();
        StringBuilder raw = new StringBuilder();
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "react-knowledge-e2e-sse-reader");
            thread.setDaemon(true);
            return thread;
        });
        Future<SseCapture> capture = readerExecutor.submit(() -> {
            BufferedReader lines = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            String currentEvent = null;
            StringBuilder currentData = new StringBuilder();
            String line;
            while ((line = lines.readLine()) != null) {
                synchronized (raw) {
                    raw.append(line).append('\n');
                }
                if (line.startsWith("event:")) {
                    currentEvent = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (!currentData.isEmpty()) currentData.append('\n');
                    currentData.append(line.substring("data:".length()).trim());
                } else if (line.isBlank() && currentEvent != null) {
                    events.add(currentEvent);
                    if ("error".equals(currentEvent) || "execution.completed".equals(currentEvent)
                            && ("run:" + runId).equals(objectMapper.readTree(currentData.toString()).path("run_id").asText())) {
                        break;
                    }
                    currentEvent = null;
                    currentData.setLength(0);
                }
            }
            return new SseCapture(response.statusCode(), events, raw.toString());
        });
        try {
            SseCapture result = capture.get(POLL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            response.body().close();
            return result;
        } catch (TimeoutException timeout) {
            response.body().close();
            capture.cancel(true);
            throw new AssertionError("SSE did not complete; events=" + events + "; raw=" + raw, timeout);
        } finally {
            response.body().close();
            capture.cancel(true);
            readerExecutor.shutdownNow();
        }
    }

    private HttpResponse<String> postSync(Map<String, Object> body) throws Exception {
        return send("/api/v1/chat/sync", "POST", body);
    }

    private JsonNode successData(HttpResponse<String> response) throws IOException {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        JsonNode envelope = objectMapper.readTree(response.body());
        assertNotNull(envelope, response.body());
        assertTrue(envelope.isObject(), response.body());
        Set<String> fields = new HashSet<>();
        envelope.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("code", "message", "data", "trace_id"), fields, response.body());
        assertEquals("OK", envelope.path("code").asText(), response.body());
        assertEquals("成功", envelope.path("message").asText(), response.body());
        assertTrue(envelope.has("data"), response.body());
        assertTrue(envelope.path("trace_id").isTextual(), response.body());
        assertFalse(envelope.path("trace_id").asText().isBlank(), response.body());
        return envelope.get("data");
    }

    private HttpResponse<String> send(String path, String method, Map<String, Object> body) throws Exception {
        return send(path, method, body, accessToken);
    }

    private HttpResponse<String> send(String path, String method, Map<String, Object> body, String token)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path)).timeout(POLL_TIMEOUT)
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest request = body == null
                ? builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
                : builder.method(method, HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body))).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private AgentExecutionCodec executionCodec() {
        return new AgentExecutionCodec(new CodecLimits(runtimeProperties.getMaxPayloadCodeUnits(),
                runtimeProperties.getMaxPayloadNodes(), runtimeProperties.getMaxPayloadDepth()));
    }

    /** 正确性入口只观察原HTTP受理的typed Run；UNKNOWN和夹具异常必须立即失败，不等待成功重试。 */
    private void awaitExecution(RunRef reference) throws Exception {
        await("typed execution knowledge result and completed run", () -> {
            assertTrue(provider.failures().isEmpty(), provider.failures().toString());
            var run = executionStore.load(reference).orElseThrow();
            assertFalse(run.phase() == ExecutionStore.Phase.UNCERTAIN,
                    () -> "原Run结果未知；" + runtimeDiagnostic());
            if (run.phase() != ExecutionStore.Phase.FINISHED) return false;
            assertEquals(RunStatus.COMPLETED, run.status(), runtimeDiagnostic());
            return true;
        });
    }

    private ExecutionSnapshot executionSnapshot(RunRef reference) {
        var run = executionStore.load(reference).orElseThrow();
        var actions = executionStore.actions(reference, 0, 100);
        var attempts = actions.stream().map(action -> executionStore.attempt(reference, action.currentAttempt()).orElseThrow()).toList();
        var events = executionStore.events(run.identity().owner().sessionRef(), 0, 100);
        assertTrue(events.size() < 100, "隔离会话的完整事实必须在本页内");
        assertTrue(events.stream().allMatch(event -> reference.equals(event.run())));
        var input = executionStore.originalInput(reference).orElseThrow();
        var materials = new LinkedHashMap<ResultRef, ExecutionStore.Material>();
        materials.put(input.reference(), input);
        materials.put(run.progress().context(), executionStore.material(reference.tenantId(), run.progress().context()).orElseThrow());
        for (var action : actions) materials.put(action.request(), executionStore.material(reference.tenantId(), action.request()).orElseThrow());
        for (var attempt : attempts) attempt.result().ifPresent(result ->
                materials.put(result, executionStore.material(reference.tenantId(), result).orElseThrow()));
        return new ExecutionSnapshot(run, actions, attempts, Map.copyOf(materials), events, executionStore.usage(reference));
    }

    /** 从选定Attempt的生产codec恢复原请求与原结果，再闭合知识来源、native配对及最终公开引用。 */
    private void assertExecutionFactsAndCausality(RunRef reference, String question, String receiptTrace) throws IOException {
        var snapshot = executionSnapshot(reference); var run = snapshot.run(); var identity = run.identity();
        var codec = executionCodec();
        assertEquals(reference, identity.runRef());
        assertEquals(tenantId, identity.owner().sessionRef().tenantId().value());
        assertEquals(sessionId, identity.owner().sessionRef().sessionId());
        assertEquals(endUserId.longValue(), identity.owner().requireUser().value());
        assertEquals(agentId, identity.agentId().value());
        assertEquals(runId, identity.causationInputId().value());
        assertEquals(runId, identity.trace().rootInputId().value());
        assertEquals(receiptTrace, identity.trace().traceId());
        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(ExecutionStore.Phase.FINISHED, run.phase());
        assertTrue(run.lease().isEmpty()); assertTrue(run.currentAction().isEmpty());
        assertEquals(2, run.progress().decisions()); assertEquals(1, run.progress().knowledgeCalls());
        assertEquals(0, run.progress().toolCalls());
        var originalInput = executionStore.originalInput(reference).orElseThrow().content();
        var frozenInput = objectMapper.readTree(codec.frozenInput(originalInput).canonicalJson());
        var expectedContext = objectMapper.createObjectNode()
                .put("delegation_requester_id", requesterMemberId)
                .put("end_user_id", endUserId.longValue())
                .put("event_id", runId).put("input_id", runId).put("root_input_id", runId);
        assertEquals(expectedContext, frozenInput.path("extraParameters"),
                "服务端业务上下文必须准确绑定本次认证成员、终端用户与输入因果，不能增加未知字段");
        var expectedContent = objectMapper.createArrayNode();
        expectedContent.addObject().put("type", "text").put("text", question);
        assertEquals(expectedContent, frozenInput.path("content"));
        var input = codec.decodeInput(originalInput);
        assertEquals(ModelPort.ModelRole.USER, input.getLast().role());
        assertEquals("input:" + runId, input.getLast().messageId());
        assertEquals(List.of(new ModelContent.Text(question),
                        new ModelContent.Text("\n输入业务上下文：\n" + expectedContext)),
                input.getLast().contents(), "模型输入必须完整保留原问题及准确业务上下文两个独立文本项");
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND input_id=?",
                tenantId, reference.runId(), runId));
        assertEquals(List.of(ExecutionStore.Kind.MODEL, ExecutionStore.Kind.KNOWLEDGE, ExecutionStore.Kind.MODEL),
                snapshot.actions().stream().map(ExecutionStore.Action::kind).toList());
        assertEquals(3, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?",
                tenantId, reference.runId()));
        for (int index = 0; index < snapshot.actions().size(); index++) {
            var action = snapshot.actions().get(index); var attempt = snapshot.attempts().get(index);
            assertEquals(reference, action.run()); assertEquals(index + 1, action.ordinal());
            assertEquals(ExecutionStore.ActionStatus.SUCCEEDED, action.status());
            assertEquals(action.currentAttempt(), action.selectedAttempt().orElseThrow());
            assertEquals(action.reference(), attempt.action()); assertEquals(reference, attempt.run());
            assertEquals(action.request(), attempt.request()); assertTrue(attempt.adopted());
            assertEquals(ExecutionStore.ResultKind.SUCCEEDED, attempt.resultKind().orElseThrow());
            assertTrue(attempt.uncertainty().isEmpty()); assertTrue(attempt.result().isPresent());
        }
        var firstModel = codec.decodeModelOutcome(snapshot.materials().get(snapshot.attempts().get(0).result().orElseThrow()).content());
        assertTrue(firstModel instanceof ModelPort.ModelResult.Completed);
        var firstCompleted = (ModelPort.ModelResult.Completed) firstModel;
        assertEquals(ModelPort.TEXT_OUTPUT_SCHEMA, firstCompleted.output().schemaId());
        var firstCalls = firstCompleted.assistantTurn().orElseThrow().toolCalls();
        assertEquals(1, firstCalls.size());
        assertEquals("knowledge_query", firstCalls.getFirst().wireName());
        var decision = objectMapper.readTree(firstCalls.getFirst().argumentsJson());
        assertEquals(sentinel, decision.path("query").asText());
        assertEquals(knowledgeHandle, decision.path("knowledge_handle").asText());
        var knowledgeAction = snapshot.actions().get(1); var knowledgeAttempt = snapshot.attempts().get(1);
        var request = codec.decodeKnowledgeRequest(snapshot.materials().get(knowledgeAction.request()).content());
        assertEquals(sentinel, ((com.skillforge.runtime.api.KnowledgeOperation.Search) request.request().operation()).query()); assertFalse(question.equals(((com.skillforge.runtime.api.KnowledgeOperation.Search) request.request().operation()).query()));
        assertEquals(identity.owner().sessionRef(), request.request().sessionRef());
        assertEquals(identity.agentId(), request.request().agentId());
        assertEquals(identity.requester(), request.request().requester());
        assertEquals(identity.trace(), request.request().trace());
        assertEquals(Set.of(kbId), request.request().allowedSourceIds());
        assertEquals(knowledgeHandle, request.target().handle().value());
        assertEquals("knowledge_query", request.wireName());
        assertEquals(provider.pairedKnowledgeCallId(), request.callId());
        assertEquals(AgentExecutionProgram.admissionEvent(identity).id(), request.sourceEventId());
        assertEquals(AgentExecutionProgram.ACCEPTED_EVENT, executionStore.event(reference, request.sourceEventId()).orElseThrow().event().type());
        var outcome = codec.decodeKnowledgeOutcome(snapshot.materials().get(knowledgeAttempt.result().orElseThrow()).content());
        assertTrue(outcome instanceof KnowledgePortal.KnowledgeOutcome.Retrieved);
        var bundle = ((KnowledgePortal.KnowledgeOutcome.Retrieved) outcome).bundle();
        assertEquals(request.request().knowledgeRequestId(), bundle.knowledgeRequestId());
        assertEquals(List.of(sentinel), bundle.plan().steps().stream().map(step -> step.query()).toList());
        assertFalse(bundle.items().isEmpty());
        for (var item : bundle.items()) {
            assertEquals(kbId, item.sourceId()); assertEquals(knowledgeSourceVersion, item.sourceVersion());
            assertTrue(objectMapper.readTree(item.content().canonicalJson()).path("text").asText().contains(sentinel));
        }
        var finalRequest = codec.decodeModelRequest(snapshot.materials().get(snapshot.actions().get(2).request()).content(),
                refs -> executionStore.materials(reference.tenantId(), refs)).request();
        var calls = finalRequest.messages().stream().flatMap(message -> message.toolCalls().stream())
                .filter(call -> request.callId().equals(call.callId())).toList();
        assertEquals(1, calls.size()); assertEquals(request.wireName(), calls.getFirst().wireName());
        // 原模型参数和知识请求分别冻结，JSON对象字段顺序不改变调用语义。
        assertEquals(request.arguments().schemaId(), calls.getFirst().arguments().schemaId());
        assertEquals(objectMapper.readTree(request.arguments().canonicalJson()),
                objectMapper.readTree(calls.getFirst().arguments().canonicalJson()));
        assertEquals(firstCalls.getFirst().argumentsJson(), calls.getFirst().arguments().canonicalJson());
        var toolMessages = finalRequest.messages().stream().filter(message ->
                message.role() == ModelPort.ModelRole.TOOL && message.toolCallId().filter(request.callId()::equals).isPresent()).toList();
        assertEquals(1, toolMessages.size());
        var injected = objectMapper.readTree(toolMessages.getFirst().textContent());
        assertEquals(bundle.bundleId(), injected.path("bundleId").asText());
        var injectedIds = new ArrayList<String>();
        for (var item : injected.path("items")) injectedIds.add(item.path("itemId").asText());
        assertFalse(injectedIds.isEmpty()); assertEquals(provider.verifiedEvidenceItemIds(), injectedIds);
        assertTrue(bundle.items().stream().map(item -> item.itemId()).toList().containsAll(injectedIds));
        var finalModel = codec.decodeModelOutcome(snapshot.materials().get(snapshot.attempts().get(2).result().orElseThrow()).content());
        assertTrue(finalModel instanceof ModelPort.ModelResult.Completed);
        var finalDecision = objectMapper.readTree(((ModelPort.ModelResult.Completed) finalModel).output().canonicalJson());
        assertEquals(ModelPort.TEXT_OUTPUT_SCHEMA, ((ModelPort.ModelResult.Completed) finalModel).output().schemaId());
        assertTrue(finalDecision.isTextual());
        assertEquals(provider.finalAnswer(), finalDecision.textValue());
        var completed = executionStore.event(reference, AgentExecutionProgram.finishedEventId(reference)).orElseThrow();
        assertEquals(AgentExecutionProgram.COMPLETED_EVENT, completed.event().type());
        var terminal = objectMapper.readTree(completed.event().data().canonicalJson());
        assertEquals("COMPLETED", terminal.path("status").asText());
        assertEquals(provider.publicFinalAnswer(), terminal.path("text").asText());
        assertEquals(runId, terminal.path("inputId").asText()); assertEquals(runId, terminal.path("rootInputId").asText());
        assertEquals(receiptTrace, terminal.path("traceId").asText());
        assertEquals(snapshot.attempts().get(2).result().orElseThrow().value(), terminal.path("resultRef").asText());
        assertEquals(injectedIds.size(), terminal.path("evidence").size());
        var citedIds = new ArrayList<String>();
        for (var citation : terminal.path("evidence")) {
            citedIds.add(citation.path("itemId").asText());
            assertEquals(bundle.bundleId(), citation.path("bundleId").asText());
            assertEquals(knowledgeAttempt.result().orElseThrow().value(), citation.path("resultRef").asText());
            assertEquals(kbId, citation.path("sourceId").asText());
            assertEquals(knowledgeSourceVersion, citation.path("sourceVersion").asText());
        }
        assertEquals(injectedIds, citedIds);
        assertEquals(AgentExecutionProgram.ACCEPTED_EVENT, snapshot.events().getFirst().event().type());
        assertEquals(run.revision(), completed.revision());
        assertEquals(List.of(completed), snapshot.events().stream()
                .filter(event -> AgentExecutionProgram.COMPLETED_EVENT.equals(event.event().type())).toList());
        var trailing = snapshot.events().stream().filter(event -> event.sequence() > completed.sequence()).toList();
        assertEquals(1, trailing.size(), "完成后只能保留同次提交的最终输出检查事实");
        var outputCheck = trailing.getFirst();
        assertEquals(completed.revision(), outputCheck.revision());
        assertEquals(com.skillforge.runtime.session.ExecutionObservation.EVENT_TYPE, outputCheck.event().type());
        var fact = com.skillforge.runtime.session.ExecutionObservation.decode(outputCheck.event().data(),
                runtimeProperties.getMaxPayloadCodeUnits(), runtimeProperties.getMaxPayloadNodes(),
                runtimeProperties.getMaxPayloadDepth());
        assertEquals(com.skillforge.runtime.session.ExecutionObservation.Kind.GUARDRAIL, fact.kind());
        assertEquals(com.skillforge.runtime.session.ExecutionObservation.Stage.AGENT_OUTPUT, fact.stage());
        assertEquals(com.skillforge.runtime.session.ExecutionObservation.Status.SUCCEEDED, fact.status());
        assertEquals(reference.runId(), fact.runId());
        assertEquals(snapshot.actions().getLast().reference().actionId(), fact.actionId());
        assertEquals(snapshot.attempts().getLast().id(), fact.attemptId());
        assertEquals(com.skillforge.runtime.session.ExecutionObservation.stepId(fact.actionId()), fact.parentOperationId());
        assertEquals(fact.parentOperationId(), fact.stepId());
        assertEquals(runId, fact.inputId()); assertEquals(runId, fact.rootInputId());
        assertEquals(receiptTrace, fact.traceId()); assertEquals(identity.trace().spanId(), fact.spanId());
        long sequence = 0;
        for (var event : snapshot.events()) { assertTrue(event.sequence() > sequence); sequence = event.sequence(); }
        for (var action : List.of(snapshot.actions().getFirst(), snapshot.actions().getLast())) {
            var results = snapshot.events().stream().filter(event -> AgentExecutionProgram.MODEL_RESULT_EVENT.equals(event.event().type()))
                    .filter(event -> {
                        try { return action.reference().actionId().equals(objectMapper.readTree(event.event().data().canonicalJson()).path("actionId").asText()); }
                        catch (IOException invalid) { throw new java.io.UncheckedIOException(invalid); }
                    }).toList();
            assertEquals(1, results.size());
            var event = objectMapper.readTree(results.getFirst().event().data().canonicalJson());
            assertEquals("SUCCEEDED", event.path("status").asText());
            assertEquals(runId, event.path("rootInputId").asText());
        }
        assertEquals(2, provider.requestCount()); assertEquals(List.of("knowledge_query", "respond"), provider.responseKinds());
        assertTrue(provider.failures().isEmpty());
        assertEquals(36, snapshot.usage().reportedTokens()); assertTrue(snapshot.usage().complete());
    }

    private void assertPublicKnowledgeHistory() throws Exception {
        var history = successData(send("/api/v1/chat/history?session_id=" + sessionId, "GET", null));
        var answers = new ArrayList<JsonNode>();
        for (var message : history.path("messages"))
            if ("assistant".equals(message.path("role").asText())
                    && provider.publicFinalAnswer().equals(message.path("content").asText())) answers.add(message);
        assertEquals(1, answers.size());
        var citations = answers.getFirst().path("metadata").path("citations");
        assertEquals(provider.verifiedEvidenceItemIds().size(), citations.size());
        for (var citation : citations) {
            assertTrue(provider.verifiedEvidenceItemIds().contains(citation.path("item_id").asText()));
            assertEquals(kbId, citation.path("source_id").asText());
            assertEquals(knowledgeSourceVersion, citation.path("source_version").asText());
            assertFalse(citation.has("result_ref")); assertFalse(citation.has("bundle_id"));
        }
    }

    private boolean webhookHasFinalAnswer() {
        for (var received : webhook.requests()) {
            try { if (isFinalKnowledgeAnswer(objectMapper.readTree(received.body()))) return true; }
            catch (IOException invalid) { throw new java.io.UncheckedIOException(invalid); }
        }
        return false;
    }

    private boolean isFinalKnowledgeAnswer(JsonNode body) {
        return "message.delta".equals(body.path("event").asText())
                && "assistant".equals(body.path("data").path("role").asText())
                && "completed".equals(body.path("data").path("state").asText())
                && "final".equals(body.path("data").path("phase").asText())
                && provider.publicFinalAnswer().equals(body.path("data").path("content").asText());
    }

    private void assertWebhookMatchesOutbox(RunRef reference) throws Exception {
        for (var received : webhook.requests()) {
            var body = objectMapper.readTree(received.body());
            if (!isFinalKnowledgeAnswer(body)) continue;
            var outputs = queryRows(jdbc, tenantId, "SELECT event_id,session_sequence,root_input_id,trace_id FROM nw_chat_session_outbox "
                    + "WHERE tenant_id=? AND session_id=? AND runtime_run_id=? AND event_id=?",
                    Long.valueOf(tenantId), sessionId, reference.runId(), body.path("event_id").asText());
            assertEquals(1, outputs.size()); var output = outputs.getFirst();
            assertEquals(((Number) output.get("session_sequence")).longValue(), body.path("session_sequence").asLong());
            assertEquals(runId, output.get("root_input_id")); assertEquals(runId, body.path("data").path("root_input_id").asText());
            assertEquals(output.get("trace_id"), body.path("data").path("trace_id").asText());
            assertEquals(sessionId, body.path("session_id").asText());
            String timestamp = received.header("X-SkillForge-Timestamp"); assertNotNull(timestamp);
            assertEquals("sha256=" + WebhookSignature.sign(WEBHOOK_SECRET, timestamp, received.body()), received.header("X-SkillForge-Signature"));
            assertEquals(body.path("event_id").asText(), received.header("Idempotency-Key"));
            return;
        }
        throw new AssertionError("正式完成消息缺少签名Webhook");
    }

    private record ExecutionSnapshot(ExecutionStore.Run run, List<ExecutionStore.Action> actions,
            List<ExecutionStore.Attempt> attempts, Map<ResultRef, ExecutionStore.Material> materials,
            List<ExecutionStore.SavedEvent> events, ExecutionStore.TokenUsage usage) { }

    private boolean runtimeFactsReady() {
        return countRuntime("select count(*) from agent_runtime_input_ledger "
                + "where tenant_id = ? and input_id = ? and receipt_status = 'ACCEPTED'",
                tenantId, runId) == 1
                && countRuntime("select count(*) from agent_runtime_runs "
                + "where tenant_id = ? and run_id = ? and status = 'COMPLETED'",
                tenantId, "run:" + runId) == 1
                && countRuntime("select count(*) from nw_runtime_session_knowledge_observations "
                + "where tenant_id = ? and run_id = ?",
                tenantId, "run:" + runId) >= 1
                && provider.requestCount() >= 1;
    }

    private void assertRuntimeFactsAndCausality(String question) throws IOException {
        assertEquals(1, count("select count(*) from nw_chat_sessions where tenant_id = ? and session_id = ?",
                tenantId, sessionId));
        assertTrue(count("select count(*) from nw_chat_messages where tenant_id = ? and session_id = ?",
                tenantId, sessionId) >= 1);
        assertTrue(countRuntime("select count(*) from nw_runtime_session_knowledge_observations "
                + "where tenant_id = ? and run_id = ?", tenantId, "run:" + runId) >= 1);
        assertTrue(provider.requestCount() >= 1);

        List<String> decisionQueries = new ArrayList<>();
        List<String> persistedQueries = new ArrayList<>();
        boolean targetEvidence = false;
        List<Map<String, Object>> events = jdbc.queryForList(
                "select event_type, payload_canonical_json from agent_runtime_session_events "
                        + "where tenant_id = ? and run_id = ? "
                        + "and event_type in ('agent.model-result', 'agent.knowledge-observation') "
                        + "order by session_sequence",
                tenantId, "run:" + runId);
        for (Map<String, Object> row : events) {
            String eventType = String.valueOf(row.get("event_type"));
            JsonNode event = objectMapper.readTree(String.valueOf(row.get("payload_canonical_json")));
            if ("agent.model-result".equals(eventType)) {
                String canonicalJson = event.path("output").path("canonicalJson").asText();
                if (!canonicalJson.isBlank()) {
                    JsonNode decision = objectMapper.readTree(canonicalJson);
                    if ("knowledge_query".equals(decision.path("type").asText())) {
                        decisionQueries.add(decision.path("query").asText());
                    }
                }
                continue;
            }
            JsonNode bundle = event.path("bundle");
            for (JsonNode step : bundle.path("plan").path("steps")) {
                persistedQueries.add(step.path("query").asText());
            }
            for (JsonNode item : bundle.path("items")) {
                String contentJson = item.path("content").path("canonicalJson").asText();
                if (kbId.equals(item.path("sourceId").asText())
                        && knowledgeSourceVersion.equals(item.path("sourceVersion").asText())
                        && !contentJson.isBlank()
                        && objectMapper.readTree(contentJson).path("text").asText().contains(sentinel)) {
                    targetEvidence = true;
                }
            }
        }
        assertEquals(List.of(sentinel), decisionQueries, events.toString());
        assertEquals(List.of(sentinel), persistedQueries, events.toString());
        assertFalse(persistedQueries.contains(question), events.toString());
        assertTrue(targetEvidence, events.toString());
    }

    private long countRuntime(String sql, Object... args) {
        return queryCount(jdbc, tenantId, sql, args);
    }

    private int indexOf(List<ChatMessageRecord> messages, String kind) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).metadata().containsKey(kind)) {
                return index;
            }
        }
        return -1;
    }

    private int indexOfSnapshotWithEvidence(List<ChatMessageRecord> messages) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).metadata().containsKey("agent_context_snapshot")
                    && !maps(nested(messages.get(index), "agent_context_snapshot").get("evidence_refs")).isEmpty()) {
                return index;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(ChatMessageRecord message, String key) {
        Object value = message.metadata().get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Map<?, ?> nested ? (Map<String, Object>) nested : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item).toList();
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String messageKinds(List<ChatMessageRecord> messages) {
        return messages.stream().map(message -> message.metadata().keySet().toString()).toList().toString();
    }

    private long count(String sql, Object... args) {
        return queryCount(jdbc, tenantId, sql, normalizeTenantArgs(args));
    }

    private Object[] normalizeTenantArgs(Object... args) {
        Object[] normalized = args.clone();
        for (int index = 0; index < normalized.length; index++) {
            if (tenantId.equals(normalized[index])) {
                normalized[index] = Long.valueOf(tenantId);
            }
        }
        return normalized;
    }

    private void await(String description, Supplier<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(100);
        }
        var threadDump = Path.of("build", "runtime-e2e", "knowledge-threads-" + runId + ".txt").toAbsolutePath();
        Files.createDirectories(threadDump.getParent());
        java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
                .dumpThreads(threadDump.toString(), com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
        throw new AssertionError("Timed out waiting for " + description + "; threads=" + threadDump
                + "; " + runtimeDiagnostic());
    }

    private String runtimeDiagnostic() {
        var reference = new RunRef(new TenantId(tenantId), "run:" + runId);
        var saved = executionStore.load(reference);
        var actions = executionStore.actions(reference, 0, 100);
        var attempts = actions.stream().map(action -> executionStore.attempt(reference, action.currentAttempt())
                .map(attempt -> Map.<String, Object>of("attempt", attempt.id(), "resultKind", attempt.resultKind().map(Enum::name).orElse("PENDING"),
                        "adopted", attempt.adopted(), "result", attempt.result().map(ResultRef::value).orElse("<none>")))
                .orElse(Map.<String, Object>of("attempt", action.currentAttempt(), "missing", true))).toList();
        return "providerRequests=" + provider.requestCount() + " providerFailures=" + provider.failures()
                + " typedRun=" + saved.map(run -> run.status() + "/" + run.phase() + "/revision=" + run.revision()).orElse("<missing>")
                + " actions=" + actions + " attempts=" + attempts
                + " events=" + saved.map(run -> executionStore.events(run.identity().owner().sessionRef(), 0, 100).stream()
                        .filter(event -> event.run().equals(reference)).map(event -> event.sequence() + ":" + event.event().type()).toList()).orElse(List.of())
                + " waits=" + waitingQueries();
    }

    private List<Map<String, Object>> waitingQueries() {
        try {
            return jdbc.queryForList(
                    "select wait_event_type, wait_event, state, left(query, 180) as query "
                            + "from pg_stat_activity where datname = current_database() "
                            + "and pid <> pg_backend_pid() and state <> 'idle' "
                            + "and query not like '%pg_stat_activity%'");
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private String driveFailureDump() {
        Path file = Path.of("build", "runtime-e2e", "drive-failures", runId + ".txt");
        if (!Files.isRegularFile(file)) {
            return "<none>";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", " | ").replace('\n', ' ');
        } catch (IOException ignored) {
            return "<unreadable>";
        }
    }

    private record SseCapture(int statusCode, List<String> events, String raw) {
    }

    private final class WebhookReceiver implements AutoCloseable {
        private final HttpServer server;
        private final CopyOnWriteArrayList<CapturedRequest> requests = new CopyOnWriteArrayList<>();

        private WebhookReceiver() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/webhooks/react-knowledge-e2e", new Handler());
        }

        private void start() {
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/webhooks/react-knowledge-e2e";
        }

        private List<CapturedRequest> requests() {
            return List.copyOf(requests);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private final class Handler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                objectMapper.readTree(body);
                Map<String, String> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((key, values) -> {
                    if (values != null && !values.isEmpty()) {
                        headers.put(key, values.get(0));
                    }
                });
                requests.add(new CapturedRequest(headers, body));
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            }
        }
    }

    private record CapturedRequest(Map<String, String> headers, String body) {
        private String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
    }
}
