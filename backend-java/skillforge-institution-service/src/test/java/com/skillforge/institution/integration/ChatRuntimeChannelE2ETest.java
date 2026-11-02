package com.skillforge.institution.integration;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.channel.mapper.ChannelMapper;
import com.skillforge.core.channel.mapper.ChannelAgentBindingMapper;
import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.core.channel.model.ChannelAgentBindingDO;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.chat.persistence.SessionEventSubscriptionDO;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.core.shared.security.WebhookSignature;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.chat.tracing.TraceView;
import com.skillforge.core.chat.tracing.TraceQuery;
import com.skillforge.core.chat.application.TraceViewService;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.runtime.api.TenantId;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chat 公开入口的 Runtime/渠道真实端到端门禁。
 *
 * <p>fixture 只建立租户前置资源；用户输入使用真实登录后的 HTTP/SSE，
 * Inbox、Execution、Usage、Outbox 和 Webhook delivery 均由运行时写入。</p>
 */
@Import({ChatRuntimeChannelE2ETest.LoopbackEvents.class, DeterministicModelCatalogConfiguration.class})
class ChatRuntimeChannelE2ETest extends AbstractIntegrationTest {

    @org.junit.jupiter.api.extension.RegisterExtension
    static final LiveGradleAttemptMeter.Outcome liveMeterOutcome = new LiveGradleAttemptMeter.Outcome();
    @Autowired private DataSource liveMeterDataSource;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    protected com.skillforge.runtime.adapter.model.OpenAiModelPort liveModelPort;

    protected static final String PASSWORD = "RuntimeE2e123";
    private static final String WEBHOOK_SECRET = "runtime-e2e-webhook-secret";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(120);

    @DynamicPropertySource
    static void enableRuntimeWorkers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CryptoService cryptoService;
    @Autowired
    private PlatformModelCatalogService modelCatalog;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private PlanMapper planMapper;

    @Autowired
    private TenantMemberMapper tenantMemberMapper;

    @Autowired private FoundationAccess foundation;
    @Autowired private FoundationOrganizationService organization;

    @Autowired
    private EndUserMapper endUserMapper;

    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private ChannelMapper browserChannels;

    @Autowired
    private ChannelAgentBindingMapper browserChannelBindings;

    @Autowired
    private AgentTemplateService templateService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AgentPublicationService agentPublications;

    @Autowired
    private SessionEventSubscriptionMapper subscriptionMapper;

    @Autowired
    private TraceViewService traceViewService;

    @Autowired
    private AgentRuntimeHostProvisioner provisioner;

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired
    private AgentRuntimeProperties runtimeProperties;

    protected final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    protected NativeChatOpenAiProvider provider;
    @org.springframework.test.context.bean.override.convention.TestBean(name = "templateRenderToolHandler", methodName = "interactiveFixture")
    com.skillforge.core.capability.connector.TemplateRenderToolHandler interactiveFixture;
    static com.skillforge.core.capability.connector.TemplateRenderToolHandler interactiveFixture() { return new LivePublicInteractiveFixture(); }
    private WebhookReceiver webhook;
    protected String tenantId;
    protected String tenantSlug;
    protected String sessionId;
    protected String agentId;
    protected String runId;
    protected Long endUserId;
    protected String accessToken;
    private String memberId;
    private boolean live;
    private boolean liveConversation;
    private boolean liveBrowser;
    private String liveToolHandle;

    @TestConfiguration(proxyBeanMethods = false)
    static class LoopbackEvents {
        @Bean
        static org.springframework.beans.factory.config.BeanPostProcessor performanceSqlDataSourceBeanPostProcessor() {
            return SqlProfileDataSource.beanPostProcessor();
        }

        @Bean @Primary
        com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit liveMeterAudit(
                @org.springframework.beans.factory.annotation.Qualifier("agentRuntimeModelInvocationAudit") com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit original,
                DataSource source,com.skillforge.core.billing.invocation.ExternalInvocationLedger ledger,
                com.skillforge.core.billing.metering.CostRecorder recorder,org.springframework.transaction.PlatformTransactionManager transactions,
                org.springframework.beans.factory.ObjectProvider<com.skillforge.institution.runtime.AgentRuntimeWorkflowNodeExecutions> workflows) {
            return new com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit(ledger,recorder,transactions,workflows) {
                @Override public com.skillforge.runtime.adapter.model.ModelInvocationAudit dispatched(
                        com.skillforge.runtime.session.spi.ExecutionStore.DispatchResult.Registered registered,
                        com.skillforge.runtime.session.spi.ExecutionStore.Prepared prepared,com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings.Binding binding) {
                    return LiveGradleAttemptMeter.wrap(original.dispatched(registered,prepared,binding),source);
                }
            };
        }

    }

    @BeforeEach
    void setUpFixture(TestInfo test) throws Exception {
        live = test.getTags().contains("live");
        liveConversation = test.getTags().contains("live-conversation");
        liveBrowser = test.getTags().contains("live-browser");
        runId = "runtime-e2e-" + SnowflakeIds.nextId();
        provider = new NativeChatOpenAiProvider(objectMapper, runId);
        provider.start();
        webhook = new WebhookReceiver(objectMapper);
        webhook.start();

        tenantSlug = "runtime-e2e-" + SnowflakeIds.nextId();
        tenantId = createTenant(tenantMapper, planMapper, tenantSlug);
        TenantContext.setTenantId(tenantId);
        try {
            configureTenantModel(provider.endpoint());
            provisioner.provision(tenantId);
            requireRunningRuntimeWorkers(applicationContext, runtimeProperties);
            createMember();
            endUserId = createDefaultEndUser();
            Long createdAgentId = createPublishedAgent();
            agentId = String.valueOf(createdAgentId);
            createSession(endUserId, createdAgentId);
            createWebhookSubscription();
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void tearDownFixture() throws Exception {
        try {
        writeWebhookEvidence();
        if (webhook != null) {
            webhook.close();
        }
        if (provider != null) {
            provider.close();
        }
        TenantContext.clear();

        } finally {
            LiveGradleAttemptMeter.snapshot(liveMeterDataSource, tenantId);
        }
    }

    /** 固定本地 Provider，记录冷态及独立会话热态的 HTTP/SSE 完整耗时，不含外部模型推理。 */
    @org.junit.jupiter.api.Tag("runtime-latency")






    /**
     * 新旧候选共用同一普通工具工作量、真实登录和挂载以及客户端 HTTP/SSE 计时。
     * 每个样本单独创建产品 Session；预热及测量不重置 Provider 脚本，也不扣除协议或工具耗时。
     * 本方法只生成首链少量热请求证据，不作为三组五十并发验收结论。
     *
     * @param candidate 本次独立候选名称，只影响证据目录
     * @param warmups 不计入热统计的独立会话数
     * @param measured 计入热统计的独立会话数
     * @param persistence 验证所属候选实际权威表；重复受理后再次调用
     */
    protected void ordinaryTemplateSamples(String candidate, int warmups, int measured,
            OrdinaryChainPersistence persistence) throws Exception {
        assertTrue(candidate.matches("[a-z0-9-]+"));
        assertTrue(warmups >= 0 && measured > 0);
        assertFalse("1".equals(System.getenv("SKILLFORGE_PERF_SQL")), "热时延样本必须关闭详细 SQL profiler");
        assertFalse("1".equals(System.getenv("SKILLFORGE_PERF_SQL_CALLERS")), "热时延样本必须关闭 SQL 调用栈采样");
        Path evidence = Path.of("build", "reports", "runtime-execution-first-chain", candidate + "-" + SnowflakeIds.nextId());
        Files.createDirectories(evidence);
        var samples = new ArrayList<Map<String, Object>>();
        var report = new LinkedHashMap<String, Object>();
        report.put("candidate", candidate);
        report.put("warmupRequests", warmups);
        report.put("measurementRequests", measured);
        report.put("workload", "2 actual HTTP model calls + 1 real PLATFORM template_render; independent short-history Session");
        report.put("timingScope", "client HTTP submission through persisted terminal SSE; zero simulated Provider execution, no time subtracted");
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("javaVm", System.getProperty("java.vm.name"));
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("loadedClassSha256", ordinaryExecutionClassHashes());
        report.put("databaseVersion", jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductVersion()));
        report.put("configuredConnectionPoolMaximum", applicationContext.getEnvironment().getProperty("spring.datasource.hikari.maximum-pool-size"));
        report.put("sqlProfiler", false);
        report.put("sqlCallers", false);
        report.put("stageRelativeReductionTarget", 0.5);
        report.put("fiftyConcurrentAcceptance", "NOT_RUN");
        report.put("samples", samples);
        report.put("outcome", "FAILED");
        try (var workload = new RuntimeExecutionWorkloadProvider(objectMapper)) {
            workload.start();
            TenantContext.setTenantId(tenantId);
            try { configureTenantModel(workload.endpoint()); }
            finally { TenantContext.clear(); }
            login();
            mountGenericTemplateTool();
            for (int index = 0; index < warmups + measured; index++) {
                var sample = new LinkedHashMap<String, Object>();
                sample.put("index", index);
                sample.put("warmup", index < warmups);
                sample.put("outcome", "FAILED");
                samples.add(sample);
                try {
                    runId = "execution-compare-" + SnowflakeIds.nextId();
                    var created = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
                    assertEquals(200, created.statusCode(), created.body());
                    sessionId = successData(created).path("session_id").asText();
                    assertFalse(sessionId.isBlank(), created.body());
                    // 固定脚本键使两个独立候选的模型正文、模板参数和工具结果逐样本相同。
                    var script = workload.register("ordinary-hot-" + index, RuntimeExecutionWorkloadProvider.Workload.ORDINARY);
                    var payload = chatPayload(script.prompt());
                    sample.put("session", sessionId);
                    sample.put("run", "run:" + runId);
                    sample.put("script", "ordinary-hot-" + index);
                    long started = System.nanoTime();
                    var response = postSse(payload);
                    long elapsed = System.nanoTime() - started;
                    sample.put("elapsedNanos", elapsed);
                    sample.put("platformNanos", elapsed);
                    Path sse = evidence.resolve(String.format("%02d-%s.sse.txt", index, runId));
                    Files.writeString(sse, response.raw(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
                    sample.put("sseEvidence", sse.toAbsolutePath().toString());
                    assertEquals(200, response.statusCode(), response.raw());
                    assertTrue(response.events().contains("execution.completed"), response.raw());
                    assertFalse(response.events().contains("error"), response.raw());
                    assertTrue(response.raw().contains(script.finalAnswer()), response.raw());
                    awaitRuntimeCondition("ordinary hot Provider response timing", () -> script.snapshot().requests().size() >= 2);
                    var snapshot = script.snapshot();
                    sample.put("provider", snapshot);
                    assertEquals(2, snapshot.requestCount(), snapshot.toString());
                    assertEquals(2, snapshot.requests().size(), snapshot.toString());
                    assertEquals(1, snapshot.verifiedToolResults(), snapshot.toString());
                    for (var timing : snapshot.requests()) {
                        assertEquals(200, timing.httpStatus(), timing.toString());
                        assertEquals("", timing.failureReason(), timing.toString());
                        assertEquals(0, timing.simulatedExecutionNanos(), "零模拟不能扣除 Provider 解析、编码、连接或发送时间");
                    }
                    assertEquals((index + 1) * 2, workload.requests().size(), "不得遗漏无法路由、额外或失败的 Provider 请求");
                    assertEquals(0, provider.requestCount(), "所有模型请求必须使用同一个工作量脚本端点");
                    String actualRun = "run:" + runId;
                    persistence.verify(actualRun, script.expectedToolResult(1));
                    var replay = send("/api/v1/chat", "POST", payload, accessToken);
                    assertEquals(202, replay.statusCode(), replay.body());
                    assertTrue(successData(replay).path("replayed").asBoolean(), replay.body());
                    assertEquals(2, script.snapshot().requestCount(), "重复输入不能增加模型调用");
                    persistence.verify(actualRun, script.expectedToolResult(1));
                    sample.put("duplicateSubmissionReplayed", true);
                    sample.put("outcome", "PASS");
                } catch (Exception | AssertionError failure) {
                    sample.put("failureType", failure.getClass().getName());
                    throw failure;
                }
            }
            var hot = samples.stream().filter(sample -> Boolean.FALSE.equals(sample.get("warmup")))
                    .map(sample -> ((Number) sample.get("platformNanos")).longValue()).sorted().toList();
            report.put("measuredPlatformMedianNanos", hot.get(hot.size() / 2));
            report.put("measuredPlatformP95Nanos", hot.get((int) Math.ceil(hot.size() * 0.95) - 1));
            report.put("measuredPlatformP99Nanos", hot.get((int) Math.ceil(hot.size() * 0.99) - 1));
            report.put("outcome", "PASS");
        } finally {
            Files.writeString(evidence.resolve("report.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
            System.out.println("RUNTIME_EXECUTION_FIRST_CHAIN_REPORT " + evidence.resolve("report.json").toAbsolutePath());
        }
    }

    /**
     * 在计时外一次读取本进程实际类加载器对应的class字节，新旧候选记录完全相同的集合。
     * 不初始化这些类、不读取环境配置文件或凭据，也不为指纹启动SQL或Profiler。
     */
    protected Map<String, String> ordinaryExecutionClassHashes() throws Exception {
        var hashes = new java.util.TreeMap<String, String>();
        for (String name : List.of(
                "com.skillforge.institution.runtime.AgentRuntimeExecutionGateway",
                "com.skillforge.institution.runtime.AgentRuntimeExecutionWorker",
                "com.skillforge.runtime.starter.ExecutionHostWorker",
                "com.skillforge.runtime.starter.ExecutionHostRegistry",
                "com.skillforge.runtime.starter.ExecutionHostScheduler",
                "com.skillforge.institution.runtime.AgentRuntimeExecutionSources",
                "com.skillforge.institution.runtime.AgentRuntimeExecutionCapabilities",
                "com.skillforge.institution.runtime.AgentRuntimeExecutionProjection",
                "com.skillforge.runtime.session.ExecutionCoordinator",
                "com.skillforge.runtime.session.AgentExecutionProgram",
                "com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore",
                "com.skillforge.runtime.adapter.model.OpenAiModelPort",
                "com.skillforge.institution.runtime.AgentRuntimePlatformToolProvider",
                "com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit",
                "com.skillforge.institution.integration.RuntimeExecutionWorkloadProvider")) {
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                if (bytes == null) throw new IllegalStateException("execution_class_bytes_missing:" + name);
                hashes.put(name, java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));
            }
        }
        return Map.copyOf(hashes);
    }

    /** 只读检查本次真实链路落在所属权威表，不由统计或 Provider 计数代替持久事实。 */
    @FunctionalInterface
    protected interface OrdinaryChainPersistence {
        /** 绑定当前独立 Run 与真实模板结果；允许读取JSON材料时传递解析错误。 */
        void verify(String actualRun, String expectedRendered) throws Exception;
    }



    private record FixedToolLatencySample(boolean cold, String run, double totalMs, double modelServerMs,
            double nonModelEstimateMs, int modelCalls, int toolCalls, List<String> renderedResults, List<String> stages,
            long startedEpochMillis, List<LatencyMilestone> timeline) { }

    /** 计时结束后读取原持久时间，只用于区分执行、重排队与公开输出间隙。 */
    private List<LatencyMilestone> fixedWorkloadTimeline(String actualRun) {
        return queryRows(jdbc, tenantId, """
                select 'runtime' as source, event_type as event,
                       occurred_at_epoch_second * 1000 + occurred_at_nano / 1000000.0 as epoch_ms
                  from agent_runtime_session_events where tenant_id=? and run_id=?
                union all
                select 'inbox', 'attempts=' || attempts,
                       updated_epoch_second * 1000 + updated_nano / 1000000.0
                  from agent_runtime_session_inbox where tenant_id=? and session_id=?
                union all
                select 'inbox-mutation', mutation_kind, extract(epoch from created_at) * 1000
                  from agent_runtime_inbox_mutations where tenant_id=? and session_id=?
                order by epoch_ms
                """, tenantId, actualRun, tenantId, sessionId, tenantId, sessionId).stream()
                .map(row -> new LatencyMilestone(row.get("source").toString(), row.get("event").toString(),
                        ((Number) row.get("epoch_ms")).doubleValue())).toList();
    }

    private record LatencyMilestone(String source, String event, double epochMillis) { }
    private record FixedToolLatencyReport(String scenario, boolean sqlProfile, boolean sqlCallers,
            String timingScope, double hotMinMs, double hotMedianMs, double hotMaxMs,
            double hotNonModelEstimateMinMs, double hotNonModelEstimateMedianMs, double hotNonModelEstimateMaxMs,
            List<FixedToolLatencySample> samples) { }



    @Test
    @Tag("live")
    @EnabledIfEnvironmentVariable(named = "SDK4_LIVE_MODEL_KEY", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_TOOL_CONVERSATION", matches = "1")
    void liveDeepSeekMcpToolAndConversationReplay() throws Exception {
        var modelCalls = new AtomicInteger();
        org.mockito.stubbing.Answer<Object> bounded = invocation -> {
            ModelCall call = invocation.getArgument(0);
            if (tenantId.equals(call.target().binding().tenantId().value()) && modelCalls.incrementAndGet() > 3)
                throw new IllegalStateException("live_tool_conversation_model_call_limit_exceeded");
            return invocation.callRealMethod();
        };
        doAnswer(bounded).when(liveModelPort).forDispatch(any(ModelCall.class),
                any(com.skillforge.runtime.adapter.model.OpenAiModelPort.Binding.class),
                any(com.skillforge.runtime.adapter.model.ModelInvocationAudit.class), any());
        TenantContext.setTenantId(tenantId);
        try { mountLiveReadOnlyTool(); } finally { TenantContext.clear(); }
        var published = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, published.statusCode(), published.body());
        String marker = "TOOL_HISTORY_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String firstRun = "run:" + runId;
        var payload = chatPayload("请调用 fetch_Hello_World_documentation 一次，读取公开仓库文档。"
                + "根据工具返回内容用中文简要概括，最后附上标记 " + marker + "。不调用其他工具。");
        var accepted = send("/api/v1/chat", "POST", payload, accessToken);
        assertEquals(202, accepted.statusCode(), accepted.body());
        assertEquals(firstRun, successData(accepted).path("execution_id").asText());
        var first = observeSse(sessionId, null, firstRun);
        assertTrue(first.events().contains("execution.completed"), first.raw());
        assertTrue(liveFinalText(first, firstRun).contains(marker), first.raw());
        awaitRuntimeCondition("live tool run and usage", () -> executionCompleted(firstRun) && liveUsage(firstRun).size() == 2);
        assertEquals(2, modelCalls.get());
        var facts = conversationInvocationSnapshot(firstRun);
        assertEquals(3, facts.size());
        assertEquals(2, facts.stream().filter(row -> "MODEL".equals(row.get("kind"))).count());
        assertEquals(1, facts.stream().filter(row -> "TOOL".equals(row.get("kind"))).count());
        assertTrue(facts.stream().allMatch(row -> "SUCCEEDED".equals(row.get("status"))
                && "SUCCEEDED".equals(row.get("result_kind")) && Boolean.TRUE.equals(row.get("adopted"))));
        assertEquals(1, countRuntime("select count(*) from agent_runtime_execution_actions a join agent_runtime_execution_attempts t "
                + "on t.tenant_id=a.tenant_id and t.run_id=a.run_id and t.attempt_id=a.selected_attempt "
                + "join agent_runtime_execution_materials m on m.tenant_id=t.tenant_id and m.reference=t.result_ref "
                + "where a.tenant_id=? and a.run_id=? and a.kind='TOOL' and m.canonical_json ilike '%hello%'", tenantId, firstRun));
        var usage = liveUsage(firstRun);
        assertTrue(usage.stream().allMatch(row -> "deepseek-flash".equals(row.get("model"))
                && "PROVIDER_REPORTED".equals(row.get("usage_source"))));
        var history = successData(get("/api/v1/chat/history?session_id=" + sessionId));
        String cursor = history.path("event_cursor").asText();
        assertFalse(cursor.isBlank());
        var replay = send("/api/v1/chat", "POST", payload, accessToken);
        assertEquals(202, replay.statusCode(), replay.body());
        assertTrue(successData(replay).path("replayed").asBoolean());
        assertEquals(receiptWithoutReplay(accepted), receiptWithoutReplay(replay));
        runId = "live-tool-followup-" + SnowflakeIds.nextId();
        String secondRun = "run:" + runId;
        var followup = chatPayload("请只复述上一轮回复末尾的验证标记，不调用工具。");
        assertFalse(objectMapper.writeValueAsString(followup).contains(marker));
        var next = send("/api/v1/chat", "POST", followup, accessToken);
        assertEquals(202, next.statusCode(), next.body());
        assertEquals(secondRun, successData(next).path("execution_id").asText());
        var second = observeSse(sessionId, cursor, secondRun);
        assertTrue(second.events().contains("execution.completed"), second.raw());
        assertTrue(liveFinalText(second, secondRun).contains(marker), second.raw());
        awaitRuntimeCondition("live tool followup and usage", () -> executionCompleted(secondRun) && liveUsage(secondRun).size() == 1);
        assertConversationInvocation(secondRun);
        var nextFacts = conversationInvocationSnapshot(secondRun);
        var nextUsage = liveUsage(secondRun);
        var nextReplay = send("/api/v1/chat", "POST", followup, accessToken);
        assertEquals(202, nextReplay.statusCode(), nextReplay.body());
        assertTrue(successData(nextReplay).path("replayed").asBoolean());
        assertEquals(receiptWithoutReplay(next), receiptWithoutReplay(nextReplay));
        assertEquals(facts, conversationInvocationSnapshot(firstRun));
        assertEquals(usage, liveUsage(firstRun));
        assertEquals(nextFacts, conversationInvocationSnapshot(secondRun));
        assertEquals(nextUsage, liveUsage(secondRun));
        assertEquals(3, modelCalls.get());
        assertEquals(0, provider.requestCount());
    }

    @Test
    @Tag("live")
    @Tag("live-conversation")
    @EnabledIfEnvironmentVariable(named = "SDK4_LIVE_MODEL_KEY", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_CONVERSATION", matches = "1")
    void liveDeepSeekConversationRestoresHistoryAndReplaysWithoutNewCalls() throws Exception {
        var published = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, published.statusCode(), published.body());
        String marker = "HISTORY_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String firstRun = "run:" + runId;
        var firstPayload = chatPayload("请只输出这个标记：" + marker);
        var first = send("/api/v1/chat", "POST", firstPayload, accessToken);
        assertEquals(202, first.statusCode(), first.body());
        assertEquals(firstRun, successData(first).path("execution_id").asText());
        var firstSse = observeSse(sessionId, null, firstRun);
        assertTrue(firstSse.events().contains("execution.completed"), firstSse.raw());
        assertTrue(liveFinalText(firstSse, firstRun).contains(marker), firstSse.raw());
        awaitRuntimeCondition("first live run and usage", () -> executionCompleted(firstRun) && liveUsage(firstRun).size() == 1);
        assertConversationInvocation(firstRun);
        var firstFacts = conversationInvocationSnapshot(firstRun);
        var firstUsage = liveUsage(firstRun);
        var history = successData(get("/api/v1/chat/history?session_id=" + sessionId));
        assertTrue(history.path("messages").toString().contains(marker), history.toString());
        String cursor = history.path("event_cursor").asText();
        assertFalse(cursor.isBlank(), history.toString());
        var replay = send("/api/v1/chat", "POST", firstPayload, accessToken);
        assertEquals(202, replay.statusCode(), replay.body());
        assertTrue(successData(replay).path("replayed").asBoolean());
        assertEquals(receiptWithoutReplay(first), receiptWithoutReplay(replay));

        runId = "live-conversation-followup-" + SnowflakeIds.nextId();
        String secondRun = "run:" + runId;
        var followup = chatPayload("请复述上一轮助手回复中的完整标记。");
        assertFalse(objectMapper.writeValueAsString(followup).contains(marker));
        var second = send("/api/v1/chat", "POST", followup, accessToken);
        assertEquals(202, second.statusCode(), second.body());
        assertEquals(secondRun, successData(second).path("execution_id").asText());
        var secondSse = observeSse(sessionId, cursor, secondRun);
        assertTrue(secondSse.events().contains("execution.completed"), secondSse.raw());
        assertTrue(liveFinalText(secondSse, secondRun).contains(marker), secondSse.raw());
        awaitRuntimeCondition("second live run and usage", () -> executionCompleted(secondRun) && liveUsage(secondRun).size() == 1);
        assertConversationInvocation(secondRun);
        var secondFacts = conversationInvocationSnapshot(secondRun);
        var secondUsage = liveUsage(secondRun);
        var secondReplay = send("/api/v1/chat", "POST", followup, accessToken);
        assertEquals(202, secondReplay.statusCode(), secondReplay.body());
        assertTrue(successData(secondReplay).path("replayed").asBoolean());
        assertEquals(receiptWithoutReplay(second), receiptWithoutReplay(secondReplay));
        assertEquals(firstFacts, conversationInvocationSnapshot(firstRun));
        assertEquals(firstUsage, liveUsage(firstRun));
        assertEquals(secondFacts, conversationInvocationSnapshot(secondRun));
        assertEquals(secondUsage, liveUsage(secondRun));
        assertEquals(0, provider.requestCount(), "真实会话不得使用本地模型夹具");
    }


    protected boolean executionCompleted(String run) {
        return countRuntime("select count(*) from agent_runtime_execution_runs where tenant_id=? and run_id=? and session_id=? and status='COMPLETED' and phase='FINISHED'", tenantId, run, sessionId) == 1;
    }

    protected List<Map<String, Object>> conversationInvocationSnapshot(String run) {
        return queryRows(jdbc, tenantId, "select a.action_id,a.kind,a.status,a.selected_attempt,t.attempt_id,t.result_kind,t.adopted "
                + "from agent_runtime_execution_actions a join agent_runtime_execution_attempts t "
                + "on t.tenant_id=a.tenant_id and t.run_id=a.run_id and t.action_id=a.action_id "
                + "where a.tenant_id=? and a.run_id=? order by a.action_ordinal,t.attempt_id", tenantId, run);
    }

    protected void assertConversationInvocation(String run) {
        assertTrue(executionCompleted(run));
        var facts = conversationInvocationSnapshot(run);
        assertEquals(1, facts.size(), "每轮应只有一次模型执行尝试");
        var fact = facts.getFirst();
        assertEquals("MODEL", fact.get("kind"));
        assertEquals("SUCCEEDED", fact.get("status"));
        assertEquals("SUCCEEDED", fact.get("result_kind"));
        assertEquals(true, fact.get("adopted"));
        assertEquals(fact.get("attempt_id"), fact.get("selected_attempt"));
        var usage = liveUsage(run);
        assertEquals(1, usage.size());
        assertEquals("deepseek", usage.getFirst().get("provider"));
        assertEquals("deepseek-flash", usage.getFirst().get("model"));
        assertEquals("PROVIDER_REPORTED", usage.getFirst().get("usage_source"));
        assertTrue(((Number) usage.getFirst().get("prompt_tokens")).longValue() > 0);
        assertTrue(((Number) usage.getFirst().get("completion_tokens")).longValue() > 0);
    }

    @Test
    @Tag("session-directory-browser")
    void standaloneBrowserFindsFirstUserMessageWithoutCallingModel() throws Exception {
        String channelToken = "directory-test-" + java.util.UUID.randomUUID();
        String clientRef = java.util.UUID.randomUUID().toString();
        TenantContext.setTenantId(tenantId);
        long channel;
        try { channel = createNativeBrowserChannel(channelToken, null, null); }
        finally { TenantContext.clear(); }
        var profileRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/client-profile"))
                .header("X-Channel-Token", channelToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("client_ref", clientRef, "display_name", "目录验收")))).build();
        var profileResponse = httpClient.send(profileRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, profileResponse.statusCode(), profileResponse.body());
        var profile = objectMapper.readTree(profileResponse.body()).path("data");
        String user = profile.path("end_user_id").asText();
        String identity = profile.path("identity_token").asText();
        assertFalse(user.isBlank()); assertFalse(identity.isBlank());
        var sessionRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/sessions"))
                .header("X-Channel-Token", channelToken).header("X-Chat-User-Token", identity)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("agent_id", agentId, "end_user_id", user)))).build();
        var created = httpClient.send(sessionRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode(), created.body());
        String directorySession = objectMapper.readTree(created.body()).path("data").path("session_id").asText();
        assertFalse(directorySession.isBlank());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
            jdbc.update("INSERT INTO nw_chat_messages(id,tenant_id,session_id,end_user_id,message_id,role,content,metadata,created_at) VALUES(?,?,?,?,?,'user',?,?::jsonb,now())",
                    SnowflakeIds.nextId(), Long.parseLong(tenantId), directorySession, Long.parseLong(user),
                    java.util.UUID.randomUUID().toString(), "预约复查 目录验收问题", "{}");
        });
        Path root = KnowledgeBrowserServer.repoRoot();
        Path evidence = Path.of("build", "runtime-e2e", "directory-browser-" + runId).toAbsolutePath();
        Files.createDirectories(evidence);
        ProcessBuilder command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(),
                root.resolve("frontend-chat/e2e/session-directory-browser.mjs").toString());
        command.directory(root.toFile()); command.redirectErrorStream(true);
        Path log = evidence.resolve("browser.log"); command.redirectOutput(log.toFile());
        command.environment().put("DIRECTORY_FIXTURE", objectMapper.writeValueAsString(Map.of(
                "backend", baseUrl(), "token", channelToken, "identity", identity,
                "clientRef", clientRef, "channel", Long.toString(channel), "session", directorySession)));
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) {
                owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            assertFalse(process.isAlive(), "browser_timeout: " + evidence);
            assertEquals(0, process.exitValue(), "browser_failed: " + evidence + "\n" + Files.readString(log));
        }
        var createdSecond = httpClient.send(sessionRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, createdSecond.statusCode(), createdSecond.body());
        String secondSession = objectMapper.readTree(createdSecond.body()).path("data").path("session_id").asText();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
            for (int i = 0; i < 35; i++) {
                for (String session : List.of(directorySession, secondSession)) {
                    String content = session.equals(directorySession) ? "旧会话消息" + i + "长文本".repeat(80) : "新会话消息" + i + "正文".repeat(8);
                    jdbc.update("INSERT INTO nw_chat_messages(id,tenant_id,session_id,end_user_id,message_id,role,content,metadata,created_at) VALUES(?,?,?,?,?,'user',?,?::jsonb,now() + (? * interval '1 second'))",
                            SnowflakeIds.nextId(), Long.parseLong(tenantId), session, Long.parseLong(user), java.util.UUID.randomUUID().toString(), content, "{}", i + 1);
                }
            }
        });
        ProcessBuilder historyCommand = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(),
                root.resolve("frontend-chat/e2e/history-scroll-browser.mjs").toString());
        historyCommand.directory(root.toFile()); historyCommand.redirectErrorStream(true);
        Path historyLog = evidence.resolve("history-scroll-browser.log"); historyCommand.redirectOutput(historyLog.toFile());
        historyCommand.environment().put("DIRECTORY_FIXTURE", objectMapper.writeValueAsString(Map.of(
                "backend", baseUrl(), "token", channelToken, "identity", identity,
                "clientRef", clientRef, "channel", Long.toString(channel), "session", directorySession,
                "secondSession", secondSession)));
        Process historyProcess = historyCommand.start();
        try (var owned = new KnowledgeBrowserProcesses(historyProcess.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (historyProcess.isAlive() && System.nanoTime() < deadline) {
                owned.capture(); historyProcess.waitFor(200, TimeUnit.MILLISECONDS);
            }
            assertFalse(historyProcess.isAlive(), "history_browser_timeout: " + evidence);
            assertEquals(0, historyProcess.exitValue(), "history_browser_failed: " + evidence + "\n" + Files.readString(historyLog));
        }
        ProcessBuilder uploadCommand = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(),
                root.resolve("frontend-chat/e2e/mixed-upload-browser.mjs").toString());
        uploadCommand.directory(root.toFile()); uploadCommand.redirectErrorStream(true);
        Path uploadLog = evidence.resolve("upload-browser.log"); uploadCommand.redirectOutput(uploadLog.toFile());
        uploadCommand.environment().put("UPLOAD_FIXTURE", objectMapper.writeValueAsString(Map.of(
                "backend", baseUrl(), "token", channelToken, "identity", identity,
                "clientRef", clientRef, "channel", Long.toString(channel), "session", directorySession,
                "agent", agentId)));
        Process uploadProcess = uploadCommand.start();
        try (var owned = new KnowledgeBrowserProcesses(uploadProcess.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (uploadProcess.isAlive() && System.nanoTime() < deadline) {
                owned.capture(); uploadProcess.waitFor(200, TimeUnit.MILLISECONDS);
            }
            assertFalse(uploadProcess.isAlive(), "upload_browser_timeout: " + evidence);
            assertEquals(0, uploadProcess.exitValue(), "upload_browser_failed: " + evidence + "\n" + Files.readString(uploadLog));
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM nw_chat_session_attachments WHERE tenant_id=? AND session_id=? AND mime_type IS NULL", Integer.class, Long.parseLong(tenantId), directorySession));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM nw_chat_session_attachments variant JOIN nw_chat_session_attachments source ON variant.tenant_id=source.tenant_id AND variant.source_media_id=source.id WHERE variant.tenant_id=? AND variant.session_id=? AND source.session_id=variant.session_id AND source.media_transform='source-v1' AND variant.media_transform='normalize-v1'", Integer.class, Long.parseLong(tenantId), directorySession));
        });
        assertEquals(0, provider.requestCount(), "目录验收不得调用模型");
    }

    /** 只创建隔离渠道前置资源；浏览器必须真实认证、登记访问者和创建渠道Session。 */
    private long createNativeBrowserChannel(String token, String apiKey, String apiSecret) {
        var channel = new ChannelDO();
        channel.setId(SnowflakeIds.nextId());
        channel.setTenantId(Long.parseLong(tenantId));
        channel.setName("隔离真实会话渠道");
        channel.setChannelType("openapi");
        channel.setStatus("active");
        channel.setConfig(Map.of());
        channel.setUiConfig(Map.of());
        channel.setChannelTokenHash(TokenHash.sha256Hex(token));
        if (apiKey != null) {
            channel.setApiKeyHash(TokenHash.sha256Hex(apiKey));
            channel.setApiSecretEncrypted(cryptoService.encrypt(apiSecret));
        }
        browserChannels.insert(channel);
        var binding = new ChannelAgentBindingDO();
        binding.setId(SnowflakeIds.nextId());
        binding.setTenantId(Long.parseLong(tenantId));
        binding.setChannelId(channel.getId());
        binding.setAgentId(Long.parseLong(agentId));
        binding.setIsDefault(true);
        binding.setPriority(0);
        binding.setTrafficPercentage(100);
        browserChannelBindings.insert(binding);
        return channel.getId();
    }

















    /** Generic 用例 HTTP 观察按一秒采样，避免短轮询耗尽公开端点限流。 */
    private void awaitGeneric(String description, Supplier<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) return;
            Thread.sleep(1000);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private Map<String, Object> genericContinuationDiagnostic(String actualRun) {
        var diagnostic = new LinkedHashMap<String, Object>(); diagnostic.put("run_id", actualRun);
        diagnostic.put("runs", queryRows(jdbc, tenantId, "SELECT status,run_revision FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
        diagnostic.put("terminal_failures", queryRows(jdbc, tenantId, "SELECT reason_code FROM nw_runtime_session_terminal_failures WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
        diagnostic.put("actions", queryRows(jdbc, tenantId, "SELECT action_id,state,reason_code,attempt FROM agent_runtime_actions WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
        diagnostic.put("response_journals", countRuntime("SELECT count(*) FROM nw_runtime_session_tool_input_responses WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
        diagnostic.put("events", queryRows(jdbc, tenantId, "SELECT event_type,session_sequence FROM agent_runtime_session_events WHERE tenant_id=? AND run_id=? ORDER BY session_sequence", tenantId, actualRun));
        diagnostic.put("provider_calls", provider.requestCount()); return diagnostic;
    }

    protected void mountGenericTemplateTool() throws Exception {
        var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, revised.statusCode(), revised.body());
        agentId = successData(revised).path("id").asText();
        assertFalse(agentId.isBlank());
        JsonNode definitions = successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"));
        long definition = 0;
        for (var item : definitions) if ("tool:template_render".equals(item.path("capability_key").asText())) definition = item.path("id").asLong();
        assertTrue(definition > 0);
        var catalog = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(item -> "template_render".equals(item.name())).findFirst().orElseThrow();
        var versions = successData(get("/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId=" + definition));
        long version = 0;
        for (var item : versions) if (catalog.publishedVersionKey().equals(item.path("version_key").asText())) version = item.path("id").asLong();
        assertTrue(version > 0);
        String key = "generic-template-" + runId;
        var deployment = send("/api/v1/capabilities/deployments", "POST", Map.of("deployment_key", key, "tool_version_id", version,
                "provider_type", "PLATFORM", "provider_ref", "template_render", "status", "ACTIVE", "environment", "TEST"), accessToken);
        assertEquals(201, deployment.statusCode(), deployment.body());
        var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                "capability_type", "TOOL", "capability_definition_id", Long.toString(definition), "capability_version_id", Long.toString(version),
                "tool_config", Map.of("tool_deployment_id", key, "tool_deployment_revision", successData(deployment).path("revision").asLong()), "priority", 0)), accessToken);
        assertEquals(201, mounted.statusCode(), mounted.body());
        var published = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, published.statusCode(), published.body());
        var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
        assertEquals(200, session.statusCode(), session.body());
        sessionId = successData(session).path("session_id").asText();
        assertFalse(sessionId.isBlank());
    }



    private Map<String, Object> hitlReply(String inputId, String pendingRef, String revision) {
        var body = chatPayload("同意继续");
        body.put("client_message_id", inputId);
        body.put("context", Map.of("end_user_id", endUserId, "input_id", inputId));
        body.put("hitl_response", Map.of("execution_id", "run:" + runId,
                "pending_ref", pendingRef, "checkpoint_revision", revision,
                "response", "agent-hitl-approved-" + runId));
        return body;
    }





    protected String liveFinalText(SseCapture capture, String expectedRun) throws IOException {
        var text = new StringBuilder();
        String event = null;
        for (String line : capture.raw().split("\\R")) {
            if (line.startsWith("event:")) event = line.substring(6).trim();
            else if (line.startsWith("data:") && "message.delta".equals(event)) {
                var data = objectMapper.readTree(line.substring(5).trim());
                if (expectedRun.equals(data.path("run_id").asText()) && "final".equals(data.path("phase").asText()))
                    text.append(data.path("content").asText());
            }
        }
        return text.toString();
    }



    private boolean completedRun(String run) {
        return countRuntime("select count(*) from agent_runtime_runs where tenant_id=? and run_id=? and session_id=? "
                + "and agent_id=? and end_user_id=? and status='COMPLETED'", tenantId, run, sessionId, agentId, endUserId) == 1;
    }

    private void assertLiveInvocationEvidence(String run, int models, int tools) {
        assertTrue(completedRun(run));
        assertEquals(models, countRuntime("select count(*) from agent_runtime_actions where tenant_id=? and run_id=? "
                + "and execute_call_kind in ('MODEL_GENERATE','MODEL_STREAM')", tenantId, run));
        assertEquals(tools, countRuntime("select count(*) from agent_runtime_actions where tenant_id=? and run_id=? "
                + "and execute_call_kind='TOOL_INVOKE'", tenantId, run));
        assertEquals(tools, countRuntime("select count(*) from agent_runtime_actions where tenant_id=? and run_id=? "
                + "and execute_call_kind='TOOL_INVOKE' and execute_target_handle like 'skillforge.runtime.tool-target.v2:%'", tenantId, run));
        // Ledger targetHandle 是冻结 binding+target 的摘要引用；实际 mount handle 由工具结果来源事实证明。
        assertEquals(tools, countRuntime("select count(distinct a.action_id) from agent_runtime_actions a "
                + "join agent_runtime_session_events e on e.tenant_id=a.tenant_id and e.run_id=a.run_id and e.action_id=a.action_id "
                + "where a.tenant_id=? and a.run_id=? and a.execute_call_kind='TOOL_INVOKE' "
                + "and e.event_type='agent.tool-result' and e.payload_canonical_json::jsonb->>'toolHandle'=?",
                tenantId, run, liveToolHandle));
        assertEquals(models + tools, countRuntime("select count(*) from agent_runtime_actions where tenant_id=? and run_id=?", tenantId, run));
        assertEquals(models + tools, countRuntime("select count(*) from agent_runtime_actions where tenant_id=? and run_id=? "
                + "and state='SUCCEEDED'", tenantId, run));
        String scope = " from agent_runtime_action_attempt_facts f join agent_runtime_actions a "
                + "using(tenant_id,action_id,invocation_id) where a.tenant_id=? and a.run_id=?";
        assertEquals(models + tools, countRuntime("select count(distinct(f.tenant_id,f.action_id,f.invocation_id,f.attempt_id))" + scope, tenantId, run));
        assertEquals(2 * (models + tools), countRuntime("select count(*)" + scope, tenantId, run));
        assertEquals(models + tools, countRuntime("select count(*)" + scope
                + " and f.status='STARTED' and f.evidence_revision=1 and f.authorization='EXECUTE'", tenantId, run));
        assertEquals(models + tools, countRuntime("select count(*)" + scope
                + " and f.status='SUCCEEDED' and f.evidence_revision=2 and f.authorization='EXECUTE'", tenantId, run));
        String permits = " from nw_runtime_action_permit_consumptions p join agent_runtime_actions a "
                + "using(tenant_id,action_id,invocation_id) where a.tenant_id=? and a.run_id=?";
        assertEquals(models + tools, countRuntime("select count(*)" + permits, tenantId, run));
        assertEquals(models + tools, countRuntime("select count(*)" + permits + " and p.status='CONSUMED'", tenantId, run));
        // 每个唯一调用许可同时闭合原 STARTED 与同 attempt 的成功终态，不能用其他 attempt 的事实凑数。
        assertEquals(models + tools, countRuntime("select count(*) from nw_runtime_action_permit_consumptions p "
                + "join agent_runtime_actions a using(tenant_id,action_id,invocation_id) "
                + "join agent_runtime_action_attempt_facts s on s.tenant_id=p.tenant_id and s.action_id=p.action_id "
                + "and s.invocation_id=p.invocation_id and s.attempt_id=p.attempt_id and s.fact_ref=p.started_fact_ref "
                + "join agent_runtime_action_attempt_facts t on t.tenant_id=s.tenant_id and t.action_id=s.action_id "
                + "and t.invocation_id=s.invocation_id and t.attempt_id=s.attempt_id and t.start_operation_id=s.start_operation_id "
                + "where a.tenant_id=? and a.run_id=? and p.status='CONSUMED' and s.status='STARTED' "
                + "and s.evidence_revision=1 and t.status='SUCCEEDED' and t.evidence_revision=2 "
                + "and t.fact_ref=a.outcome_fact_ref", tenantId, run));
        String external = " from nw_external_invocations x join agent_runtime_action_attempt_facts f "
                + "on x.tenant_id=f.tenant_id::bigint and x.attempt_id=f.attempt_id and x.invocation_id=f.invocation_id "
                + "join agent_runtime_actions a on a.tenant_id=f.tenant_id and a.action_id=f.action_id and a.invocation_id=f.invocation_id "
                + "where a.tenant_id=? and a.run_id=? and f.status='STARTED' and f.evidence_revision=1 "
                + "and a.execute_call_kind in ('MODEL_GENERATE','MODEL_STREAM')";
        assertEquals(models, countRuntime("select count(*)" + external, tenantId, run));
        assertEquals(models, countRuntime("select count(*)" + external
                + " and x.status='SUCCEEDED' and x.provider='deepseek' and x.operation_type=?", tenantId, run,
                com.skillforge.core.billing.invocation.ExternalOperationType.MODEL_CHAT.value()));
        var usage = liveUsage(run);
        assertEquals(models, usage.size(), "每次真实模型调用恰好一条 measured usage");
        for (var row : usage) {
            assertEquals("deepseek", row.get("provider")); assertEquals("deepseek-flash", row.get("model"));
            assertEquals("PROVIDER_REPORTED", row.get("usage_source"));
            assertTrue(((Number) row.get("prompt_tokens")).longValue() > 0);
            assertTrue(((Number) row.get("completion_tokens")).longValue() > 0);
        }
        assertEquals(1, countRuntime("select count(*) from agent_runtime_input_ledger where tenant_id=? and input_id=? "
                + "and receipt_status='ACCEPTED'", tenantId, run.substring("run:".length())));
    }

    private List<Map<String, Object>> liveInvocationSnapshot(String run) {
        return queryRows(jdbc, tenantId, "select a.action_id,a.invocation_id,a.state,a.execute_call_kind,a.execute_target_handle,a.reason_code,f.attempt_id,"
                + "f.start_operation_id,f.evidence_revision,f.status,f.fact_ref,f.outcome_type,"
                + "case when a.execute_call_kind='TOOL_INVOKE' then f.outcome_canonical_json::jsonb#>>'{failure,reasonCode}' end as tool_failure_reason,"
                + "p.status as permit_status,x.status as external_status,x.provider as external_provider,x.operation_type as external_operation_type "
                + "from agent_runtime_actions a join agent_runtime_action_attempt_facts f using(tenant_id,action_id,invocation_id) "
                + "join nw_runtime_action_permit_consumptions p on p.tenant_id=f.tenant_id and p.action_id=f.action_id "
                + "and p.invocation_id=f.invocation_id and p.attempt_id=f.attempt_id "
                + "left join nw_external_invocations x on x.tenant_id=f.tenant_id::bigint and x.attempt_id=f.attempt_id "
                + "and x.invocation_id=f.invocation_id where a.tenant_id=? and a.run_id=? "
                + "order by a.action_id,f.attempt_id,f.evidence_revision", tenantId, run);
    }

    /** 只提取工具结果的身份、状态与安全原因码，不输出业务参数或原始私有结果。 */
    private List<Map<String, Object>> liveToolResultReasons(String run) {
        return queryRows(jdbc, tenantId, "select event_id,action_id,"
                + "payload_canonical_json::jsonb->>'toolHandle' as tool_handle,"
                + "payload_canonical_json::jsonb->>'status' as status,"
                + "payload_canonical_json::jsonb->>'reasonCode' as reason_code "
                + "from agent_runtime_session_events where tenant_id=? and run_id=? and event_type='agent.tool-result' order by session_sequence",
                tenantId, run);
    }

    protected List<Map<String, Object>> liveUsage(String run) {
        return queryRows(jdbc, tenantId, "select event_id,provider,model,usage_source,prompt_tokens,completion_tokens "
                + "from nw_usage_events where tenant_id=? and run_id=? and event_type='llm.chat' and usage_event_kind='measured' "
                + "order by event_id", Long.valueOf(tenantId), run);
    }

    private Map<String, Object> mountLiveReadOnlyTool() throws Exception {
        long tenant = Long.parseLong(tenantId);
        String connector = "sdk4-gitmcp-" + SnowflakeIds.nextId();
        String tool = "fetch_Hello_World_documentation";
        var descriptor = Map.of(
                "input_schema", Map.of("type", "object", "additionalProperties", false),
                "output_schema", Map.of(), "effect", "READ_ONLY",
                "idempotent", false, "timeout_ms", 30_000, "tool_name", tool,
                "model_invocable", true, "user_invocable", false, "confirmation_policy", "NEVER");
        var definitionCreated = send("/api/v1/capabilities/definitions", "POST", Map.of(
                "capability_type", "TOOL", "capability_key", connector,
                "name", "Public Hello World documentation", "description", "Read public Hello-World documentation"), accessToken);
        assertEquals(201, definitionCreated.statusCode(), definitionCreated.body());
        long definition = successData(definitionCreated).path("id").asLong();
        assertTrue(definition > 0, definitionCreated.body());
        String contentHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(objectMapper.writeValueAsBytes(descriptor)));
        var draftCreated = send("/api/v1/capabilities/definitions/versions", "POST", Map.of(
                "definition_id", definition, "payload", Map.of("version_key", "v1",
                        "name", "Public Hello World documentation",
                        "description", tool + ": Read the public octocat/Hello-World repository documentation. "
                                + "This read-only tool takes an empty JSON object and returns the repository documentation; call it once to read actual content.",
                        "content_hash", contentHash,
                        "descriptor", descriptor)), accessToken);
        assertEquals(201, draftCreated.statusCode(), draftCreated.body());
        var draft = successData(draftCreated);
        long version = draft.path("id").asLong();
        assertTrue(version > 0, draft.toString());
        assertEquals("DRAFT", draft.path("status").asText(), draft.toString());
        var validated = send("/api/v1/capabilities/definitions/versions/validate", "POST",
                Map.of("definition_id", definition, "version_id", version), accessToken);
        assertEquals(200, validated.statusCode(), validated.body());
        var validation = successData(validated);
        assertTrue(validation.path("valid").asBoolean(), validation.toString());
        long definitionRevision = validation.path("definition_revision").asLong();
        assertTrue(definitionRevision > 0, validation.toString());
        // 发布 CAS 使用校验返回的 Definition revision；正式生命周期重新执行严格 descriptor 校验。
        var versionPublished = httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/v1/capabilities/definitions/versions/publish"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json").header("If-Match", Long.toString(definitionRevision))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("definition_id", definition, "version_id", version)))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, versionPublished.statusCode(), versionPublished.body());
        var publishedVersion = successData(versionPublished);
        assertEquals("PUBLISHED", publishedVersion.path("status").asText(), publishedVersion.toString());
        // Connector 创建接口会远程 tools/list；本夹具仅预置连接来源，能力及 Deployment 走公开生命周期。
        jdbc.update("insert into nw_connector_instances(id,instance_id,tenant_id,name,base_url,auth_type,executor_type,capabilities,status) "
                + "values(?,?,?,'GitMCP public read-only','https://gitmcp.io/octocat/Hello-World','none','mcp',?::jsonb,'active')",
                SnowflakeIds.nextId(), connector, tenant, objectMapper.writeValueAsString(List.of(tool)));
        var deploymentCreated = send("/api/v1/capabilities/deployments", "POST", Map.of(
                "deployment_key", connector, "tool_version_id", version, "provider_type", "MCP",
                "provider_ref", connector, "environment", "PRODUCTION", "status", "ACTIVE"), accessToken);
        assertEquals(201, deploymentCreated.statusCode(), deploymentCreated.body());
        long deployment = successData(deploymentCreated).path("id").asLong();
        assertTrue(deployment > 0, deploymentCreated.body());
        // 公开挂载使用 Deployment key；数据库数值 id 不是 tool_deployment_id 的业务标识。
        var deploymentRead = get("/api/v1/capabilities/deployments/detail?deploymentKey=" + connector);
        assertEquals(200, deploymentRead.statusCode(), "live deployment preflight: " + deploymentRead.body());
        var deploymentData = successData(deploymentRead);
        assertEquals(Long.toString(deployment), deploymentData.path("id").asText(), deploymentData.toString());
        assertEquals(connector, deploymentData.path("deployment_key").asText(), deploymentData.toString());
        assertEquals(Long.toString(version), deploymentData.path("tool_version_id").asText(), deploymentData.toString());
        assertEquals("MCP", deploymentData.path("provider_type").asText(), deploymentData.toString());
        assertEquals(connector, deploymentData.path("provider_ref").asText(), deploymentData.toString());
        assertEquals("ACTIVE", deploymentData.path("status").asText(), deploymentData.toString());
        long deploymentRevision = deploymentData.path("revision").asLong();
        assertTrue(deploymentRevision > 0, deploymentData.toString());
        var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId,
                "payload", Map.of("capability_type", "TOOL", "capability_definition_id", Long.toString(definition),
                        "capability_version_id", Long.toString(version), "tool_config", Map.of(
                                "tool_deployment_id", connector, "tool_deployment_revision", deploymentRevision),
                        "priority", 0)), accessToken);
        assertEquals(201, mounted.statusCode(), "live mount preflight: deployment=" + deploymentData + "; response=" + mounted.body());
        var mountSet = successData(mounted);
        assertEquals(1, mountSet.path("mounts").size(), mountSet.toString());
        assertTrue(mountSet.path("generation").asLong() > 0, mountSet.toString());
        var mount = mountSet.path("mounts").get(0);
        assertEquals("TOOL", mount.path("capability_type").asText(), mount.toString());
        assertEquals(Long.toString(definition), mount.path("capability_definition_id").asText(), mount.toString());
        assertEquals(Long.toString(version), mount.path("capability_version_id").asText(), mount.toString());
        assertEquals(connector, mount.path("tool_config").path("tool_deployment_id").asText(), mount.toString());
        assertEquals(deploymentRevision, mount.path("tool_config").path("tool_deployment_revision").asLong(), mount.toString());
        assertTrue(mount.path("revision").asLong() > 0, mount.toString());
        assertEquals("ACTIVE", mount.path("status").asText(), mount.toString());
        String mountedId = mount.path("mount_id").asText();
        assertFalse(mountedId.isBlank());
        var mountRead = get("/api/v1/agents/capability-mounts?agentId=" + agentId);
        assertEquals(200, mountRead.statusCode(), "live mount readback: " + mountRead.body());
        assertEquals(mountSet, successData(mountRead), "公开读取必须返回相同装配代和精确挂载版本");
        liveToolHandle = "tool:" + mountedId;
        var registry = applicationContext.getBean(com.skillforge.core.capability.PersistentCapabilityRegistry.class);
        var capability = new com.skillforge.core.capability.CapabilityRef(
                com.skillforge.core.capability.CapabilityType.TOOL, Long.toString(definition), Long.toString(version));
        assertTrue(registry.find(tenant, capability).isPresent(), "真实 Registry 必须能严格解码已发布 Tool 版本");
        var persisted = applicationContext.getBean(com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService.class)
                .read(tenant, Long.parseLong(agentId));
        var snapshot = applicationContext.getBean(com.skillforge.core.capability.CapabilityMountResolver.class)
                .freeze(com.skillforge.core.capability.CapabilityMountSnapshot.snapshotIdForInput("live-preflight:" + runId),
                        "live-preflight:" + runId, tenant, Long.parseLong(agentId), persisted.generation(), persisted.mounts());
        var catalog = applicationContext.getBean(com.skillforge.core.capability.tool.ToolCatalog.class).list(snapshot);
        assertEquals(1, catalog.size(), "真实 Registry、MountResolver 与 ToolCatalog 必须在模型外调前完整解析挂载");
        assertEquals(liveToolHandle, catalog.getFirst().handle());
        assertEquals(tool, catalog.getFirst().definition().name());
        assertEquals(connector, catalog.getFirst().target().deployment().deploymentId());
        assertEquals(deploymentRevision, catalog.getFirst().target().deployment().revision());
        return Map.of("publishedVersion", publishedVersion, "validation", validation,
                "deployment", deploymentData, "mountSet", mountSet, "toolHandle", liveToolHandle, "localCatalogResolved", true);
    }

    protected void configureTenantModel(String endpoint) {
        PlatformModelFixtures.selectLlm(modelCatalog, tenantId,
                live ? "https://api.deepseek.com" : endpoint,
                live ? "deepseek-flash" : "runtime-fixture",
                live ? System.getenv("SDK4_LIVE_MODEL_KEY") : "runtime-fixture-key",
                live ? "deepseek" : "custom",
                live ? "deepseek-api" : "custom-openai-compatible",
                live ? "2026-09-13" : "custom-v1");
    }

    private void createMember() {
        TenantMemberDO member = new TenantMemberDO();
        member.setId(SnowflakeIds.nextId());
        member.setTenantId(Long.parseLong(tenantId));
        member.setUsername("runtime-e2e-admin");
        member.setRole("admin");
        member.setPermissions(List.of());
        member.setStatus("active");
        member.setPasswordHash(passwordEncoder.encode(PASSWORD));
        member.setCreatedAt(Instant.now());
        tenantMemberMapper.insert(member);
        memberId = member.getId().toString();
        organization.initializeTenant(tenantId, memberId);
    }

    private Long createDefaultEndUser() {
        EndUserDO user = new EndUserDO();
        user.setId(SnowflakeIds.nextId());
        user.setTenantId(Long.parseLong(tenantId));
        user.setDisplayName("Runtime E2E User");
        user.setStatus("active");
        user.setFirstSeenAt(Instant.now());
        user.setLastActiveAt(Instant.now());
        user.setCreatedAt(Instant.now());
        endUserMapper.insert(user);
        return user.getId();
    }

    private Long createPublishedAgent() {
        AgentDO agent = new AgentDO();
        agent.setId(SnowflakeIds.nextId());
        agent.setTenantId(Long.parseLong(tenantId));
        agent.setAgentKey("runtime-e2e-agent-" + runId);
        agent.setName("Runtime E2E Agent");
        agent.setSystemPrompt("你是一个确定性的 Runtime E2E 助手。fixture agent_id=" + agent.getId());
        agent.setRoleDescription("Runtime E2E");

        agent.setConfig(Map.of());
        if (live && !liveBrowser) {
            // 首轮工具决策和结果回答各一次；后续输入仍使用同一已发布配置。
            agent.setMaxBusinessModelCalls(liveConversation ? 1 : 2); agent.setMaxToolCalls(1); agent.setMaxKnowledgeCalls(1);
        }
        if (liveBrowser) {
            // 真实浏览器只允许一次模型迭代，不以再次付费调用修复协议或供应商失败。
            // 运营预算的最小合法值为 1；本场景不挂载工具和知识，后验要求实际调用为零。
            agent.setMaxBusinessModelCalls(1); agent.setMaxToolCalls(1); agent.setMaxKnowledgeCalls(1);
        }
        boolean draftForLiveTool = live && !liveBrowser;
        agent.setStatus(draftForLiveTool ? "draft" : "published");
        if (!draftForLiveTool) agent.setPublishedAt(Instant.now());
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        return foundation.withSubject(fixtureSubject(), () -> new TransactionTemplate(transactionManager).execute(status -> {
            agentMapper.insert(agent);
            organization.registerResource("agent", agent.getId().toString());
            if (!draftForLiveTool) agentPublications.publish(agent);
            return agent.getId();
        }));
    }

    /** 真实成员登录创建Session；来源冻结与同事务ownership登记均由正式入口完成。 */
    private void createSession(Long endUserId, Long createdAgentId) throws Exception {
        login();
        var created = send("/api/v1/chat/sessions", "POST", Map.of(
                "agent_id", createdAgentId.toString(), "end_user_id", endUserId), accessToken);
        assertEquals(200, created.statusCode(), created.body());
        sessionId = successData(created).path("session_id").asText();
        assertFalse(sessionId.isBlank(), created.body());
        var sources = queryRows(jdbc, tenantId, "SELECT s.tenant_id,s.agent_id,s.origin_kind,s.actor_ref,s.responsibility_ref,"
                + "s.integration_id,s.business_scope_id,s.channel_id,u.id AS end_user_id,u.status AS end_user_status,"
                + "m.id AS member_id,m.status AS member_status FROM nw_chat_sessions s "
                + "JOIN nw_end_users u ON u.id=s.end_user_id AND u.tenant_id=s.tenant_id "
                + "JOIN nw_foundation_resource_ownership o ON o.scope_key=s.tenant_id::text AND o.kind='session' AND o.resource_id=s.id "
                + "JOIN nw_tenant_members m ON m.id=o.owner_member_id AND m.tenant_id=s.tenant_id "
                + "WHERE s.tenant_id=? AND s.session_id=?", Long.valueOf(tenantId), sessionId);
        assertEquals(1, sources.size(), "正式Session必须同时关联本机构真实EndUser与创建成员所有权");
        var source = sources.getFirst();
        assertEquals(Long.valueOf(tenantId), source.get("tenant_id"));
        assertEquals(createdAgentId, source.get("agent_id"));
        assertEquals(endUserId, source.get("end_user_id"));
        assertEquals("active", source.get("end_user_status"));
        assertEquals(Long.valueOf(memberId), source.get("member_id"));
        assertEquals("active", source.get("member_status"));
        assertEquals("native", source.get("origin_kind"));
        assertEquals("member:" + memberId, source.get("actor_ref"));
        assertEquals("tenant:" + tenantId, source.get("responsibility_ref"));
        assertNull(source.get("integration_id"));
        assertNull(source.get("business_scope_id"));
        assertNull(source.get("channel_id"));
    }

    /** 夹具直接建资源时使用真实已建制账号；业务输入仍通过真实登录HTTP/SSE。 */
    private FoundationAccess.Subject fixtureSubject() { return new FoundationAccess.Subject(false, tenantId, memberId); }

    private void createWebhookSubscription() {
        SessionEventSubscriptionDO subscription = new SessionEventSubscriptionDO();
        subscription.setId(SnowflakeIds.nextId());
        subscription.setTenantId(Long.parseLong(tenantId));
        subscription.setSessionId(sessionId);
        subscription.setEndpointUrl(webhook.endpoint());
        subscription.setSigningSecretEncrypted(cryptoService.encrypt(WEBHOOK_SECRET));
        subscription.setStatus("active");
        subscription.setCreatedAt(Instant.now());
        subscription.setUpdatedAt(Instant.now());
        subscriptionMapper.insert(subscription);
    }

    protected void login() throws Exception {
        Map<String, Object> body = Map.of(
                "tenant_slug", tenantSlug,
                "username", "runtime-e2e-admin",
                "password", PASSWORD);
        HttpResponse<String> response = send("/api/v1/auth/login", "POST", body, null);
        assertEquals(200, response.statusCode(), response.body());
        accessToken = successData(response).path("access_token").asText();
        assertFalse(accessToken.isBlank());
    }

    protected Map<String, Object> chatPayload(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", List.of(Map.of("type", "text", "text", message)));
        body.put("session_id", sessionId);
        body.put("agent_id", agentId);
        body.put("client_message_id", runId);
        body.put("hitl_response", null);
        body.put("context", Map.of("end_user_id", endUserId));
        return body;
    }

    protected SseCapture postSse(Map<String, Object> body) throws Exception {
        HttpResponse<String> accepted = send("/api/v1/chat", "POST", body, accessToken);
        assertEquals(202, accepted.statusCode(), accepted.body());
        String acceptedSession = successData(accepted).path("session_id").asText();
        assertEquals(body.get("session_id"), acceptedSession);
        String acceptedRun = successData(accepted).path("execution_id").asText();
        assertFalse(acceptedRun.isBlank(), accepted.body());
        return observeSse(acceptedSession, null, acceptedRun);
    }

    private SseCapture observeSse(String acceptedSession) throws Exception {
        return observeSse(acceptedSession, null);
    }

    private SseCapture observeSse(String acceptedSession, String lastEventId) throws Exception {
        return observeSse(acceptedSession, lastEventId, null);
    }

    protected SseCapture observeSse(String acceptedSession, String lastEventId, String expectedRun) throws Exception {
        return observeSse(URI.create(baseUrl() + "/api/v1/chat/events?session_id=" + acceptedSession), lastEventId, expectedRun);
    }

    /** 并发候选只使用不可变会话身份；等待验收可在真实hitl.required处停止订阅。 */
    protected SseCapture observeSseUntil(String acceptedSession, String lastEventId, String expectedRun,
            java.util.Set<String> terminalEvents) throws Exception {
        return observeSse(URI.create(baseUrl() + "/api/v1/chat/events?session_id=" + acceptedSession), lastEventId,
                expectedRun, terminalEvents);
    }

    /** 本地确定模型已失败时尽早收回订阅；关闭订阅仍不取消服务端 Run。 */
    protected SseCapture observeSseUntil(String acceptedSession, String lastEventId, String expectedRun,
            java.util.Set<String> terminalEvents, Runnable fixtureHealth) throws Exception {
        return observeSse(URI.create(baseUrl() + "/api/v1/chat/events?session_id=" + acceptedSession), lastEventId,
                expectedRun, terminalEvents, fixtureHealth);
    }

    private SseCapture observeSse(URI eventsUri, String lastEventId, String expectedRun) throws Exception {
        return observeSse(eventsUri, lastEventId, expectedRun, java.util.Set.of("execution.completed", "error"));
    }

    /** SSE读者拥有自己的流与executor，关闭页面订阅不取消服务端Run。 */
    private SseCapture observeSse(URI eventsUri, String lastEventId, String expectedRun,
            java.util.Set<String> terminalEvents) throws Exception {
        return observeSse(eventsUri,lastEventId,expectedRun,terminalEvents,()->{});
    }

    private SseCapture observeSse(URI eventsUri, String lastEventId, String expectedRun,
            java.util.Set<String> terminalEvents, Runnable fixtureHealth) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(eventsUri)
                .timeout(POLL_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "text/event-stream");
        if (lastEventId != null) request.header("Last-Event-ID", lastEventId);
        HttpResponse<InputStream> response = httpClient.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        boolean eventStream = response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream");
        if (response.statusCode() != 200 || !eventStream) response.body().close();
        assertEquals(200, response.statusCode());
        assertTrue(eventStream);
        List<String> events = new CopyOnWriteArrayList<>();
        StringBuilder raw = new StringBuilder();
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "runtime-e2e-sse-reader");
            thread.setDaemon(true);
            return thread;
        });
        Future<SseCapture> capture = readerExecutor.submit(() -> {
            BufferedReader lines = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            String currentEvent = null;
            StringBuilder currentData = new StringBuilder();
            long terminalReceivedNanos = 0;
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
                    boolean terminal = terminalEvents.contains(currentEvent);
                    if (terminal && (expectedRun == null
                            || expectedRun.equals(objectMapper.readTree(currentData.toString()).path("run_id").asText()))) {
                        terminalReceivedNanos = System.nanoTime();
                        break;
                    }
                    currentEvent = null;
                    currentData.setLength(0);
                }
            }
            return new SseCapture(response.statusCode(), events, raw.toString(), terminalReceivedNanos, 0, 0);
        });
        SseCapture result;
        long bodyClosedNanos;
        try {
            long deadline=System.nanoTime()+POLL_TIMEOUT.toNanos();
            for(;;) {
                fixtureHealth.run();
                try { result=capture.get(100,TimeUnit.MILLISECONDS);break; }
                catch(TimeoutException pending) { if(System.nanoTime()>=deadline)throw pending; }
            }
            fixtureHealth.run();
            response.body().close();
            bodyClosedNanos = System.nanoTime();
        } catch (TimeoutException timeout) {
            response.body().close();
            capture.cancel(true);
            String observed;
            synchronized (raw) {
                observed = raw.toString();
            }
            throw new AssertionError("SSE did not emit a terminal event within " + POLL_TIMEOUT
                    + "; events=" + events + "; raw=" + observed, timeout);
        } finally {
            response.body().close();
            capture.cancel(true);
            readerExecutor.shutdownNow();
            assertTrue(readerExecutor.awaitTermination(5, TimeUnit.SECONDS), "SSE reader must stop before fixture teardown");
        }
        return new SseCapture(result.statusCode(), result.events(), result.raw(), result.terminalReceivedNanos(),
                bodyClosedNanos, System.nanoTime());
    }

    protected JsonNode successData(HttpResponse<String> response) throws IOException {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        JsonNode envelope = objectMapper.readTree(response.body());
        assertEquals("OK", envelope.path("code").asText(), response.body());
        assertTrue(envelope.has("data"), response.body());
        return envelope.path("data");
    }

    protected JsonNode receiptWithoutReplay(HttpResponse<String> response) throws IOException {
        var receipt = ((com.fasterxml.jackson.databind.node.ObjectNode) successData(response)).deepCopy();
        receipt.remove("replayed");
        return receipt;
    }

    protected HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(HttpRequest.newBuilder().uri(URI.create(baseUrl() + path))
                .timeout(POLL_TIMEOUT).header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postSync(Map<String, Object> body) throws Exception {
        return send("/api/v1/chat/sync", "POST", body, accessToken);
    }

    protected HttpResponse<String> send(String path, String method, Map<String, Object> body, String token)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest request = builder.method(method,
                HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    protected String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    protected String fixtureTenantId() {
        return tenantId;
    }

    protected String fixtureTenantSlug() {
        return tenantSlug;
    }

    protected String fixtureAgentId() {
        return agentId;
    }

    protected String fixtureSessionId() {
        return sessionId;
    }

    protected String fixtureRunId() {
        return runId;
    }

    protected long countRuntime(String sql, Object... args) {
        return queryCount(jdbc, tenantId, sql, args);
    }

    private long count(String sql, Object... args) {
        return queryCount(jdbc, tenantId, sql, normalizeTenantArgs(args));
    }

    private Object[] normalizeTenantArgs(Object... args) {
        Object[] normalized = args.clone();
        for (int i = 0; i < normalized.length; i++) {
            if (tenantId.equals(normalized[i])) {
                normalized[i] = Long.valueOf(tenantId);
            }
        }
        return normalized;
    }

    protected void awaitRuntimeCondition(String description, Supplier<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.get()) {
                    return;
                }
            } catch (Throwable failure) {
                lastFailure = failure;
            }
            Thread.sleep(100);
        }
        AssertionError error = new AssertionError("Timed out waiting for " + description
                + "; " + runtimeDiagnostic());
        if (lastFailure != null) {
            error.initCause(lastFailure);
        }
        throw error;
    }

    private String runtimeDiagnostic() {
        return "providerRequests=" + provider.requestCount()
                + " ledger=" + queryRows(jdbc, tenantId,
                "select input_id, receipt_status from agent_runtime_input_ledger "
                        + "where tenant_id = ? and input_id = ?", tenantId, runId)
                + " runs=" + queryRows(jdbc, tenantId,
                "select run_id, status, run_revision, lease_worker_id, lease_until_epoch_second "
                        + "from agent_runtime_runs where tenant_id = ? and run_id = ?",
                tenantId, "run:" + runId)
                + " inbox=" + queryRows(jdbc, tenantId,
                "select input_id, status, reason_code, attempts from agent_runtime_session_inbox "
                        + "where tenant_id = ? and session_id = ?", tenantId, sessionId)
                + " actions=" + queryRows(jdbc, tenantId,
                "select action_id, session_id, agent_id, end_user_id, state, reason_code, invocation_id "
                        + "from agent_runtime_actions where tenant_id = ?", tenantId)
                + " resolutions=" + queryRows(jdbc, tenantId,
                "select action_id, invocation_id, winner_fact_ref is not null as has_winner "
                        + "from nw_runtime_action_resolutions where tenant_id = ?", tenantId)
                + " events=" + queryRows(jdbc, tenantId,
                "select event_type, payload_schema_id from agent_runtime_session_events "
                        + "where tenant_id = ? and run_id = ? order by session_sequence",
                tenantId, "run:" + runId)
                + " modelOps=" + queryRows(jdbc, tenantId,
                "select command_kind from nw_runtime_session_model_operations "
                        + "where tenant_id = ? and run_id = ? order by recorded_at",
                tenantId, "run:" + runId)
                + " projections=" + queryRows(jdbc, tenantId,
                "select count(*) as n from nw_runtime_session_model_projections "
                        + "where tenant_id = ? and run_id = ?",
                tenantId, "run:" + runId)
                + " consumes=" + queryRows(jdbc, tenantId,
                "select count(*) as n from nw_runtime_action_permit_consumptions p "
                        + "join agent_runtime_actions a using (tenant_id, action_id) "
                        + "where a.tenant_id = ? and a.run_id = ? and p.status = 'CONSUMED'",
                tenantId, "run:" + runId)
                + " checkpoint=" + queryRows(jdbc, tenantId,
                "select (record_data like '%model-result%') as has_model_result, "
                        + "(record_data like '%consumedModels%') as has_consumed, "
                        + "(record_data like '%preparations%') as has_preparations, "
                        + "(record_data like '%session-checkpoint%') as has_checkpoint_schema, "
                        + "length(record_data) as nbytes, left(record_data, 180) as head "
                        + "from agent_runtime_runs where tenant_id = ? and run_id = ?",
                tenantId, "run:" + runId)
                + " driveFailure=" + driveFailureDump();
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

    private void assertWebhookMatchesOutbox() throws Exception {
        Map<String, Object> outbox = jdbc.queryForMap(
                "select event_id, session_sequence, root_input_id, payload::text as payload "
                        + "from nw_chat_session_outbox where tenant_id = ? and root_input_id = ? "
                        + "order by session_sequence limit 1", normalizeTenantArgs(tenantId, runId));
        WebhookReceiver.CapturedRequest received = webhook.requests().get(0);
        JsonNode body = objectMapper.readTree(received.body());
        assertEquals(String.valueOf(outbox.get("event_id")), body.path("event_id").asText());
        assertEquals(((Number) outbox.get("session_sequence")).longValue(),
                body.path("session_sequence").asLong());
        assertEquals(runId, body.path("data").path("root_input_id").asText());
        String timestamp = received.header("X-SkillForge-Timestamp");
        assertNotNull(timestamp);
        assertEquals("sha256=" + WebhookSignature.sign(WEBHOOK_SECRET, timestamp, received.body()),
                received.header("X-SkillForge-Signature"));
        assertEquals(body.path("event_id").asText(), received.header("Idempotency-Key"));
    }

    private void writeWebhookEvidence() {
        if (webhook == null || webhook.requests().isEmpty()) {
            return;
        }
        try {
            Path directory = Path.of("build", "runtime-e2e", runId);
            Files.createDirectories(directory);
            List<Map<String, Object>> evidence = webhook.requests().stream().map(request -> Map.of(
                    "headers", Map.of(
                            "X-SkillForge-Event-Id", request.header("X-SkillForge-Event-Id"),
                            "X-SkillForge-Session-Id", request.header("X-SkillForge-Session-Id"),
                            "X-SkillForge-Timestamp", request.header("X-SkillForge-Timestamp"),
                            "Idempotency-Key", request.header("Idempotency-Key")),
                    "body", request.body())).toList();
            Files.writeString(directory.resolve("webhook-capture.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 证据写入失败不能掩盖主断言；JUnit/Spring 日志仍由 Gradle 保留。
        }
    }

    /** 本JVM单调时间区分终态完整行收到、响应流关闭及reader静止；不把客户端收尾推断为服务端延迟。 */
    protected record SseCapture(int statusCode, List<String> events, String raw, long terminalReceivedNanos,
            long bodyClosedNanos, long readerStoppedNanos) {
    }

    /** 本地 Webhook 接收器；只保留脱敏请求证据和原始 body。 */
    private static final class WebhookReceiver implements AutoCloseable {
        private final ObjectMapper mapper;
        private final HttpServer server;
        private final CopyOnWriteArrayList<CapturedRequest> requests = new CopyOnWriteArrayList<>();

        private WebhookReceiver(ObjectMapper mapper) throws IOException {
            this.mapper = mapper;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/webhooks/runtime-e2e", new Handler());
        }

        private void start() {
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/webhooks/runtime-e2e";
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
                // 解析一次，确保 fixture 只接受有效 JSON；不把异常载荷写进证据。
                mapper.readTree(body);
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

        private record CapturedRequest(Map<String, String> headers, String body) {
            private String header(String name) {
                return headers.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                        .map(Map.Entry::getValue)
                        .findFirst().orElse(null);
            }
        }
    }
}
