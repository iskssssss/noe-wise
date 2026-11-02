package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.execution.ProgramCodeToolHandler;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.Availability;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.CapabilitySupport;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.EndpointMode;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProtocolProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderCategory;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderPreset;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceRegion;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelKind;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.SupportLevel;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.VerificationStatus;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.core.capability.connector.ProgramToolInvoker;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 真实聊天入口到外部 Code Runner 的最小组合 smoke。
 *
 * <p>租户成员和终端用户只作为认证前置夹具；模型配置、平台 Tool Deployment、Agent、
 * CapabilityMount 与 Session 均经公开 API 建立。测试不挂载 Skill，模型依次使用
 * inline {@code code_execute} 与 {@code finalize_task_outputs}，
 * 从而覆盖普通聊天生成并正式发布文档和静态网站的产品路径。</p>
 */
@Import(CodeExecutionChatIntegrationTest.VerifiedModelProviderConfiguration.class)
class CodeExecutionChatIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "CodeExecutionSmoke123";
    private static final String DOCUMENT_MARKER = "[CODE_EXECUTION_DOCUMENT]";
    private static final String WEBSITE_MARKER = "[CODE_EXECUTION_WEBSITE]";
    private static final String PROGRAM_MARKER = "[PROGRAM_TOOL_CALL]";
    private static final String DOCUMENT_STDOUT_MARKER = "CODE_EXECUTION_INTERNAL_STDOUT_DOCUMENT";
    private static final String WEBSITE_STDOUT_MARKER = "CODE_EXECUTION_INTERNAL_STDOUT_WEBSITE";
    private static final String RUNNER_ENDPOINT = normalizeEndpoint(
            System.getenv("CODE_EXECUTION_SMOKE_ENDPOINT"));
    private static final String RUNNER_TOKEN = value(System.getenv("CODE_EXECUTION_SMOKE_TOKEN"));
    private static final boolean RUNNER_CONFIGURED = validRunnerConfiguration();
    private static final Ports PORTS = RUNNER_CONFIGURED ? availablePorts() : new Ports(0, 0);
    private static final int APPLICATION_PORT = PORTS.application();
    private static final int PREVIEW_PORT = PORTS.preview();
    private static final Path ARTIFACT_ROOT = Path.of(
            "build", "runtime-e2e", "code-execution-smoke", Long.toUnsignedString(System.nanoTime()))
            .toAbsolutePath().normalize();
    private static final Duration SMOKE_TIMEOUT = Duration.ofSeconds(260);

    @DynamicPropertySource
    static void configureSmoke(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> Integer.toString(APPLICATION_PORT));
        registry.add("skillforge.code-execution.endpoint",
                () -> RUNNER_CONFIGURED ? RUNNER_ENDPOINT : "http://127.0.0.1:9");
        registry.add("skillforge.code-execution.service-token",
                () -> RUNNER_CONFIGURED ? RUNNER_TOKEN : "disabled-code-execution-token-00000000");
        registry.add("skillforge.code-execution.artifact-root", ARTIFACT_ROOT::toString);
        registry.add("skillforge.code-execution.ticket-key",
                () -> "code-execution-smoke-ticket-key-0123456789abcdef");
        registry.add("skillforge.code-execution.platform-origin",
                () -> "http://localhost:" + (APPLICATION_PORT == 0 ? 1 : APPLICATION_PORT));
        registry.add("skillforge.code-execution.download-origin",
                () -> "http://127.0.0.1:" + (APPLICATION_PORT == 0 ? 1 : APPLICATION_PORT));
        registry.add("skillforge.code-execution.preview-origin",
                () -> "http://127.0.0.1:" + (PREVIEW_PORT == 0 ? 1 : PREVIEW_PORT));
        registry.add("skillforge.code-execution.preview-port", () -> Integer.toString(PREVIEW_PORT));
        registry.add("agent-runtime.worker-lease-duration", () -> "210s");
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
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
    private OperationalSettingsService settings;

    @Autowired
    private com.skillforge.core.foundation.runtimeconfig.RunSettingsService runSettings;

    @Autowired
    private ProgramToolInvoker programTools;

    @Autowired
    private ExecutionBudgetPort executionBudgets;

    @Autowired
    private com.skillforge.institution.execution.CodeExecutionToolHandler realCodeExecution;

    @MockitoBean
    private ProgramCodeToolHandler programCode;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 不跳过真实Node RPC：测试Tool别名只转接真实Handler，子调用沿实际Runtime授权与账本。 */
    @Test
    void realNodeSandboxCallsThreeRuntimeToolsAndChecksSeventyTwoRows() throws Exception {
        assumeTrue(RUNNER_CONFIGURED, "真实Node RPC需要隔离CODE_EXECUTION_SMOKE_ENDPOINT/TOKEN");
        String fixtureId = Long.toUnsignedString(SnowflakeIds.nextId());
        String tenantSlug = "node-rpc-" + fixtureId, username = "node-rpc-admin-" + fixtureId;
        String tenantId = createTenant(tenantMapper, planMapper, tenantSlug);
        Long endUser;
        TenantContext.setTenantId(tenantId);
        try { createMember(tenantId, username); endUser = createEndUser(tenantId); }
        finally { TenantContext.clear(); }
        long actor = Long.parseLong(fixtureId);
        jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(?,?,?,'disabled')", actor, "node-rpc-settings-" + fixtureId, "unused");
        var oldBudget = settings.read(ChatBudgetSettings.GROUP);
        var oldSandbox = settings.read("sandbox");
        try (DeterministicCodeModel model = new DeterministicCodeModel(objectMapper)) {
            setToolBudget(4, actor);
            var sandboxValues = new LinkedHashMap<>(oldSandbox.values()); sandboxValues.put("enabled", true);
            settings.update("sandbox", oldSandbox.revision(), sandboxValues, actor);
            model.start(); String token = login(tenantSlug, username);
            TenantContext.setTenantId(tenantId);
            try { configureModel(tenantId, model.endpoint()); provisioner.provision(tenantId); requireRunningRuntimeWorkers(applicationContext, runtimeProperties); }
            finally { TenantContext.clear(); }
            PlatformTool program = resolvePlatformTool(token, "run_code"), clock = resolvePlatformTool(token, "get_current_time");
            String agent = createAgent(token, fixtureId + "-node-rpc");
            // 任务根工具额度为 4；本单 Agent 场景需显式 Run 覆盖，不能继承普通主助手的半额策略。
            json(send(token, "/api/v1/agents/update", "PATCH", Map.of("agent_id", agent,
                    "payload", Map.of("execution_config", Map.of("max_tool_calls", 4)))), 200);
            mountTool(token, agent, program, createDeployment(token, program, fixtureId, "run_code"));
            String handle = mountTool(token, agent, clock, createDeployment(token, clock, fixtureId, "get_current_time"));
            publishAgent(token, agent);
            var invoked = new AtomicInteger();
            doAnswer(invocation -> {
                invoked.incrementAndGet();
                var context = invocation.<PlatformToolExecutionContext>getArgument(1);
                var args = new LinkedHashMap<String,Object>(invocation.<Map<String,Object>>getArgument(0));
                // 与正式 ProgramCodeToolHandler 一致：适配内部 code_execute 的固定 inline/Node 契约。
                args.put("mode", "inline");
                args.put("language", "node");
                String handleJson = objectMapper.writeValueAsString(handle);
                args.put("code", "import {readFile,writeFile} from 'node:fs/promises';\n"
                        + "const rows=JSON.parse(await readFile('/input/data/records.json','utf8'));\n"
                        + "if(rows.length!==72||rows.reduce((s,r)=>s+r.cents,0)!==328500)throw Error('72-row oracle mismatch');\n"
                        + "const observations=[];for(let i=0;i<3;i++){const value=await tools.call(" + handleJson + ",{});"
                        + "if(value===null||typeof value!=='object')throw Error('tool observation missing');observations.push(value);}\n"
                        + "await writeFile('/workspace/output/result.json',JSON.stringify({results:[{kind:'text',format:'plain',text:'NODE_RPC_72_ROWS_AND_3_TOOL_CALLS_VERIFIED'}]}));");
                var rows = new ArrayList<Map<String,Object>>();
                for (int index=1; index<=72; index++) rows.add(Map.of("id", "sale-"+index, "department", "部门"+index%6, "cents", index*125));
                args.put("input_files", Map.of("records.json", objectMapper.writeValueAsString(rows)));
                return realCodeExecution.executeProgram(args, context, programTools);
            }).when(programCode).execute(anyMap(), any(PlatformToolExecutionContext.class));
            when(programCode.toolName()).thenReturn("run_code");
            String input="node-rpc-real-"+fixtureId;
            String run=runProgramScenario(tenantId,token,agent,endUser,input,new AtomicReference<>(),model,Duration.ofSeconds(120));
            var frozen = runSettings.requireFrozen(tenantId, run);
            assertEquals(Integer.valueOf(4), frozen.executionConfig().maxToolCalls(), "原 Run 必须冻结正式发布的显式工具额度");
            assertEquals(4L, runSettings.read(frozen, ChatBudgetSettings.GROUP)
                    .longValue(ChatBudgetSettings.Profile.STANDARD_ROOT.field(ExecutionBudgetPort.Dimension.TOOLS)));
            assertEquals(1,invoked.get()); assertToolBudget(tenantId,run,4);
            assertEquals(3,queryCount(jdbc,tenantId,"select count(*) from agent_runtime_session_events where tenant_id=? and run_id=? and event_type='agent.program-tool-request'",tenantId,run));
            assertEquals(4,queryCount(jdbc,tenantId,"select count(*) from agent_runtime_actions where tenant_id=? and run_id=? and action_kind='tool.invoke' and state='SUCCEEDED'",tenantId,run));
            JsonNode history=json(send(token,"/api/v1/chat/history?session_id="+createSessionQueryForRun(tenantId,run),"GET",null),200);
            assertTrue(history.toString().contains("NODE_RPC_72_ROWS_AND_3_TOOL_CALLS_VERIFIED"));
        } finally {
            TenantContext.clear();
            settings.update("sandbox",settings.read("sandbox").revision(),oldSandbox.values(),actor);
            settings.update(ChatBudgetSettings.GROUP,settings.read(ChatBudgetSettings.GROUP).revision(),oldBudget.values(),actor);
        }
    }

    /** 查询真实Run归属的产品Session，不以模型返回或新建Session替代原执行。 */
    private String createSessionQueryForRun(String tenantId, String run) {
        var rows=queryRows(jdbc,tenantId,"select session_id from agent_runtime_runs where tenant_id=? and run_id=?",tenantId,run);
        assertEquals(1,rows.size());
        return java.net.URLEncoder.encode(String.valueOf(rows.getFirst().get("session_id")),StandardCharsets.UTF_8);
    }

    @Test
    void chatCreatesDocumentAndWebsiteThroughRealCodeRunner() throws Exception {
        assumeTrue(RUNNER_CONFIGURED,
                "Set valid CODE_EXECUTION_SMOKE_ENDPOINT and CODE_EXECUTION_SMOKE_TOKEN "
                        + "(at least 32 characters) to run the real Code Runner smoke");

        String fixtureId = Long.toUnsignedString(SnowflakeIds.nextId());
        String tenantSlug = "code-execution-smoke-" + fixtureId;
        String username = "code-execution-admin-" + fixtureId;
        String tenantId = createTenant(tenantMapper, planMapper, tenantSlug);
        Long endUserId;
        TenantContext.setTenantId(tenantId);
        try {
            createMember(tenantId, username);
            endUserId = createEndUser(tenantId);
        } finally {
            TenantContext.clear();
        }

        long settingsActor = Long.parseLong(fixtureId);
        jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(?,?,?,'disabled')",
                settingsActor, "sandbox-settings-" + fixtureId, "unused");
        var originalSandbox = settings.read("sandbox");
        var sandboxValues = new java.util.LinkedHashMap<>(originalSandbox.values());
        sandboxValues.put("enabled", true);
        var evidence = new LinkedHashMap<String, Object>();
        var scenarioEvidence = new ArrayList<Map<String, Object>>();
        evidence.put("fixtureId", fixtureId);
        evidence.put("tenantId", tenantId);
        evidence.put("scenarios", scenarioEvidence);
        Throwable smokeFailure = null;
        var observedModel = new AtomicReference<DeterministicCodeModel>();
        try (DeterministicCodeModel model = new DeterministicCodeModel(objectMapper)) {
            observedModel.set(model);
            settings.update("sandbox", originalSandbox.revision(), sandboxValues, settingsActor);
            model.start();
            String accessToken = login(tenantSlug, username);

            TenantContext.setTenantId(tenantId);
            try {
                configureModel(tenantId, model.endpoint());
                provisioner.provision(tenantId);
                requireRunningRuntimeWorkers(applicationContext, runtimeProperties);
            } finally {
                TenantContext.clear();
            }

            PlatformTool tool = resolveCodeExecutionTool(accessToken);
            PlatformTool finalize = resolvePlatformTool(accessToken, "finalize_task_outputs");
            Deployment deployment = createDeployment(accessToken, tool, fixtureId);
            Deployment finalizeDeployment = createDeployment(accessToken, finalize, fixtureId,
                    "finalize_task_outputs");
            String agentId = createAgent(accessToken, fixtureId);
            mountOutputChain(accessToken, agentId, tool, deployment,
                    finalize, finalizeDeployment);
            publishAgent(accessToken, agentId);

            ScenarioResult document = runScenario(accessToken, tenantId, agentId, endUserId,
                    new Scenario(
                            "document-" + fixtureId,
                            DOCUMENT_MARKER + " document-" + fixtureId + " 生成一个 Markdown 文档。",
                            "file",
                            List.of("report.md"),
                            "SkillForge code execution smoke document " + fixtureId), scenarioEvidence);
            assertDownload(accessToken, document.artifact(), "report.md",
                    "SkillForge code execution smoke document " + fixtureId);
            scenarioEvidence.getLast().put("candidateDownloadVerified", true);

            ScenarioResult website = runScenario(accessToken, tenantId, agentId, endUserId,
                    new Scenario(
                            "website-" + fixtureId,
                            WEBSITE_MARKER + " website-" + fixtureId + " 生成一个包含 CSS 的静态网站。",
                            "website",
                            List.of("index.html"),
                            "SkillForge static website " + fixtureId), scenarioEvidence);
            assertDownload(accessToken, website.artifact(), "index.html",
                    "SkillForge static website " + fixtureId);
            scenarioEvidence.getLast().put("candidateDownloadVerified", true);
            assertPreview(accessToken, website.artifact(),
                    "SkillForge static website " + fixtureId);
            scenarioEvidence.getLast().put("candidatePreviewVerified", true);
            if ("1".equals(System.getenv("CODE_EXECUTION_SMOKE_BROWSER"))) {
                evidence.put("browser", assertBrowserPreviews(accessToken, website, fixtureId));
            }

            assertEquals(4, model.toolCallCount(), model.responseKinds().toString());
            assertEquals(2, model.respondCount(), model.responseKinds().toString());
            evidence.put("modelToolCalls", model.toolCallCount());
            evidence.put("modelFinalResponses", model.respondCount());
            evidence.put("status", "passed");
        } catch (Exception | AssertionError failure) {
            smokeFailure = failure;
            evidence.put("status", "failed");
            evidence.put("failureType", failure.getClass().getName());
            throw failure;
        } finally {
            TenantContext.clear();
            if (observedModel.get() != null) {
                evidence.put("modelToolCalls", observedModel.get().toolCallCount());
                evidence.put("modelFinalResponses", observedModel.get().respondCount());
            }
            try {
                settings.update("sandbox", settings.read("sandbox").revision(), originalSandbox.values(), settingsActor);
            } catch (Exception restoreFailure) {
                evidence.put("status", "failed");
                evidence.put("settingsRestoreFailureType", restoreFailure.getClass().getName());
                if (smokeFailure != null) smokeFailure.addSuppressed(restoreFailure);
                else {
                    smokeFailure = restoreFailure;
                    throw restoreFailure;
                }
            } finally {
                writeSmokeEvidence(fixtureId, tenantId, evidence, scenarioEvidence, smokeFailure);
            }
        }
    }

    /** 客户端尚未订阅最终答复时停启 workers；不模拟 finalize 回执丢失或进程 kill。 */
    @Test
    void completedDeliverableSurvivesWorkerRestartBeforeClientSubscriptionWithoutReexecution() throws Exception {
        assumeTrue(RUNNER_CONFIGURED, "Requires isolated CODE_EXECUTION_SMOKE_ENDPOINT and CODE_EXECUTION_SMOKE_TOKEN");
        String fixtureId = Long.toUnsignedString(SnowflakeIds.nextId());
        String slug = "late-delivery-" + fixtureId;
        String username = "late-delivery-admin-" + fixtureId;
        String tenant = createTenant(tenantMapper, planMapper, slug);
        Long endUser;
        TenantContext.setTenantId(tenant);
        try { createMember(tenant, username); endUser = createEndUser(tenant); }
        finally { TenantContext.clear(); }
        long actor = Long.parseLong(fixtureId);
        jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(?,?,?,'disabled')",
                actor, "late-delivery-settings-" + fixtureId, "unused");
        var original = settings.read("sandbox");
        var enabled = new LinkedHashMap<>(original.values()); enabled.put("enabled", true);
        var evidence = new LinkedHashMap<String, Object>();
        var scenarios = new ArrayList<Map<String, Object>>();
        evidence.put("fixtureId", fixtureId); evidence.put("tenantId", tenant); evidence.put("scenarios", scenarios);
        evidence.put("boundary", "Run completed before first client SSE subscription; worker stop/start only");
        evidence.put("provider", "controlled loopback, not paid model");
        Throwable failure = null;
        org.springframework.context.SmartLifecycle workers = null;
        try (var model = new DeterministicCodeModel(objectMapper)) {
            settings.update("sandbox", original.revision(), enabled, actor);
            model.start();
            String token = login(slug, username);
            TenantContext.setTenantId(tenant);
            try { configureModel(tenant, model.endpoint()); provisioner.provision(tenant); }
            finally { TenantContext.clear(); }
            requireRunningRuntimeWorkers(applicationContext, runtimeProperties);
            var owned = applicationContext.getBeansOfType(org.springframework.context.SmartLifecycle.class).values().stream()
                    .filter(value -> value.getClass().getName().contains("AgentRuntimeWorkers")).toList();
            assertEquals(1, owned.size()); workers = owned.getFirst();
            var code = resolveCodeExecutionTool(token);
            var finalizeTool = resolvePlatformTool(token, "finalize_task_outputs");
            String agent = createAgent(token, fixtureId);
            mountOutputChain(token, agent, code, createDeployment(token, code, fixtureId), finalizeTool,
                    createDeployment(token, finalizeTool, fixtureId, "finalize_task_outputs"));
            publishAgent(token, agent);
            String session = createSession(token, agent, endUser);
            var scenario = new Scenario("late-document-" + fixtureId,
                    DOCUMENT_MARKER + " document-" + fixtureId + " 生成一个 Markdown 文档。",
                    "file", List.of("report.md"), "SkillForge code execution smoke document " + fixtureId);
            String run = "run:" + scenario.inputId();
            var facts = new LinkedHashMap<String, Object>();
            facts.put("sessionId", session); facts.put("runId", run); scenarios.add(facts);
            Map<String, Object> body = Map.of("content", List.of(Map.of("type", "text", "text", scenario.prompt())),
                    "session_id", session, "agent_id", agent, "client_message_id", scenario.inputId(),
                    "context", Map.of("end_user_id", endUser));
            JsonNode receipt = json(send(token, "/api/v1/chat", "POST", body), 202);
            assertFalse(receipt.path("replayed").asBoolean());
            long deadline = System.nanoTime() + SMOKE_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline && queryCount(jdbc, tenant,
                    "select count(*) from agent_runtime_runs where tenant_id=? and run_id=? and status='COMPLETED'", tenant, run) != 1) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
            }
            assertEquals(1, queryCount(jdbc, tenant,
                    "select count(*) from agent_runtime_runs where tenant_id=? and run_id=? and status='COMPLETED'", tenant, run));
            JsonNode listedBefore;
            do {
                listedBefore = json(send(token, "/api/v1/chat/sessions/deliverables?sessionId=" + session + "&includeHistory=true", "GET", null), 200);
                if (listedBefore.size() == 1) break;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
            } while (System.nanoTime() < deadline);
            assertEquals(1, listedBefore.size(), "正式发布必须完成后才能进入客户端晚订阅窗口");
            // 完成前没有调用 events/history；先确认正式成果已可下载，再记录冻结账本。
            assertPublishedDeliverable(token, session, run, scenario);
            var hashesBefore = downloadPublishedHashes(token, listedBefore);
            var ledgerBefore = deliveryRecoveryLedger(tenant, run);
            int modelTools = model.toolCallCount(), modelFinals = model.respondCount();
            assertEquals(3, modelTools); assertEquals(1, modelFinals);
            assertEquals(3, queryCount(jdbc, tenant, "select count(*) from agent_runtime_actions "
                    + "where tenant_id=? and run_id=? and execute_call_kind='TOOL_INVOKE' and state='SUCCEEDED'", tenant, run));
            assertEquals(4, queryCount(jdbc, tenant, "select count(*) from agent_runtime_actions "
                    + "where tenant_id=? and run_id=? and execute_call_kind in ('MODEL_GENERATE','MODEL_STREAM') and state='SUCCEEDED'", tenant, run));
            var previousLoops = new ArrayList<Thread>();
            for (String fieldName : List.of("loop", "eventLoop")) {
                var field = workers.getClass().getDeclaredField(fieldName); field.setAccessible(true);
                Thread thread = (Thread) field.get(workers); if (thread != null) previousLoops.add(thread);
            }
            workers.stop();
            for (Thread thread : previousLoops) {
                thread.join(Duration.ofSeconds(45));
                assertFalse(thread.isAlive(), "旧 worker 循环须退出后再 start");
            }
            assertFalse(workers.isRunning());
            workers.start();
            assertTrue(workers.isRunning());
            facts.put("workerStopStart", true);
            JsonNode replay = json(send(token, "/api/v1/chat", "POST", body), 202);
            assertTrue(replay.path("replayed").asBoolean());
            assertEquals(receipt.path("execution_id"), replay.path("execution_id"));
            // 此处才首次订阅；helper 再次提交的是同一个已完成输入。
            var events = submitAndObserve(token, body);
            assertTrue(events.events().contains("execution.completed"), events.raw());
            assertFalse(events.raw().contains(DOCUMENT_STDOUT_MARKER));
            JsonNode history = json(send(token, "/api/v1/chat/history?session_id=" + session, "GET", null), 200);
            assertTrue(history.path("messages").toString().contains(scenario.expectedContent()));
            assertPublishedDeliverable(token, session, run, scenario);
            var listedAfter = json(send(token,
                    "/api/v1/chat/sessions/deliverables?sessionId=" + session + "&includeHistory=true", "GET", null), 200);
            assertEquals(listedBefore, listedAfter, "revision、file_id 与 metadata.sha256 不变");
            assertEquals(hashesBefore, downloadPublishedHashes(token, listedAfter), "停启前后真实下载字节 SHA 不变");
            assertEquals(ledgerBefore, deliveryRecoveryLedger(tenant, run), "读取、重放及 worker 停启不新增 Action/attempt 或修改终态");
            assertEquals(modelTools, model.toolCallCount()); assertEquals(modelFinals, model.respondCount());
            facts.put("ledgerUnchanged", true); facts.put("successfulToolActions", 3); facts.put("modelRequests", 4);
            facts.put("publishedDeliverableAndDownloadVerified", true); facts.put("downloadSha256", hashesBefore);
            evidence.put("status", "passed");
        } catch (Exception | AssertionError caught) {
            failure = caught; evidence.put("status", "failed"); evidence.put("failureType", caught.getClass().getName()); throw caught;
        } finally {
            TenantContext.clear();
            try {
                if (workers != null && !workers.isRunning()) workers.start();
                settings.update("sandbox", settings.read("sandbox").revision(), original.values(), actor);
            } catch (Exception restore) {
                if (failure != null) failure.addSuppressed(restore); else throw restore;
            } finally { writeSmokeEvidence(fixtureId, tenant, evidence, scenarios, failure); }
        }
    }

    private Map<String, String> downloadPublishedHashes(String token, JsonNode listed) throws Exception {
        var hashes = new LinkedHashMap<String, String>();
        for (JsonNode deliverable : listed) for (JsonNode file : deliverable.path("files")) {
            JsonNode ticket = json(send(token, "/api/v1/chat/deliverables/download-ticket", "POST",
                    Map.of("id", deliverable.path("id").asText(), "file_id", file.path("file_id").asText())), 200);
            URI issued = URI.create(ticket.path("url").asText());
            assertEquals("127.0.0.1", issued.getHost()); assertEquals(port, issued.getPort());
            var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl()).resolve(issued))
                    .timeout(SMOKE_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(response.body()));
            assertEquals(file.path("sha256").asText(), hash, "实际文件必须匹配正式 metadata.sha256");
            assertEquals(file.path("size").asLong(), (long) response.body().length);
            hashes.put(deliverable.path("id").asText() + "/" + file.path("file_id").asText(), hash);
        }
        assertFalse(hashes.isEmpty()); return Map.copyOf(hashes);
    }

    /** 只比较本 Run 的执行事实，不把可重试 Outbox 的投递计数当成业务重执行。 */
    private Map<String, Object> deliveryRecoveryLedger(String tenant, String run) {
        return Map.of("run", queryRows(jdbc, tenant,
                        "select status,run_revision from agent_runtime_runs where tenant_id=? and run_id=?", tenant, run),
                "actions", queryRows(jdbc, tenant, "select action_id,invocation_id,state,execute_call_kind from agent_runtime_actions "
                        + "where tenant_id=? and run_id=? order by action_id", tenant, run),
                "attempts", queryRows(jdbc, tenant, "select f.action_id,f.invocation_id,f.attempt_id,f.evidence_revision,f.status "
                        + "from agent_runtime_action_attempt_facts f join agent_runtime_actions a using(tenant_id,action_id,invocation_id) "
                        + "where a.tenant_id=? and a.run_id=? order by f.action_id,f.attempt_id,f.evidence_revision", tenant, run));
    }

    private ScenarioResult runScenario(
            String accessToken,
            String tenantId,
            String agentId,
            Long endUserId,
            Scenario scenario, List<Map<String, Object>> evidence) throws Exception {
        String sessionId = createSession(accessToken, agentId, endUserId);
        String runId = "run:" + scenario.inputId();
        var facts = new LinkedHashMap<String, Object>();
        facts.put("sessionId", sessionId);
        facts.put("runId", runId);
        facts.put("artifactType", scenario.artifactType());
        evidence.add(facts);
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("content", List.of(Map.of("type", "text", "text", scenario.prompt())));
        chat.put("session_id", sessionId);
        chat.put("agent_id", agentId);
        chat.put("client_message_id", scenario.inputId());
        chat.put("context", Map.of("end_user_id", endUserId));

        SseCapture sse = submitAndObserve(accessToken, chat);
        assertEquals(200, sse.statusCode(), sse.raw());
        assertTrue(sse.events().contains("process.finished"), sse.raw());
        assertTrue(sse.events().contains("message.delta"), sse.raw());
        assertTrue(sse.raw().contains("\"type\":\"tool\""), sse.raw());
        assertTrue(sse.raw().contains("\"status\":\"succeeded\""), sse.raw());
        assertTrue(sse.events().contains("execution.completed"), sse.raw());
        assertTrue(sse.raw().contains("\"status\":\"completed\""), sse.raw());
        assertFalse(sse.raw().contains(DOCUMENT_STDOUT_MARKER));
        assertFalse(sse.raw().contains(WEBSITE_STDOUT_MARKER));
        facts.put("publicSseStatus", sse.statusCode());
        facts.put("publicSseEvents", sse.events());

        JsonNode history = json(send(accessToken,
                "/api/v1/chat/history?session_id=" + sessionId, "GET", null), 200);
        assertTrue(history.path("messages").isArray(), history.toString());
        assertTrue(history.path("messages").toString().contains(scenario.expectedContent()),
                history.toString());
        assertFalse(history.toString().contains(DOCUMENT_STDOUT_MARKER));
        assertFalse(history.toString().contains(WEBSITE_STDOUT_MARKER));
        facts.put("stdoutExcludedFromPublicProjection", true);

        JsonNode toolProcess = succeededCodeExecutionProcess(history, runId);
        assertFalse(toolProcess.has("detail"), "公开过程不能携带原始工具参数和结果");
        assertFalse(toolProcess.path("presentation").path("title").asText().isBlank());
        assertTrue(containsText(toolProcess.path("presentation").path("results"),
                scenario.artifactType().equals("website") ? "WEBSITE_READY" : "DOCUMENT_READY"));
        facts.put("typedPublicResultVerified", true);

        JsonNode artifacts = json(send(accessToken, "/api/v1/chat/sessions/artifacts?" + "sessionId=" + java.net.URLEncoder.encode(String.valueOf(sessionId), java.nio.charset.StandardCharsets.UTF_8), "GET", null), 200);
        assertTrue(artifacts.isArray(), artifacts.toString());
        assertEquals(1, artifacts.size(), artifacts.toString());
        JsonNode artifact = artifacts.get(0);
        assertEquals(runId, artifact.path("run_id").asText(), artifact.toString());
        assertEquals(scenario.artifactType(), artifact.path("type").asText(), artifact.toString());
        assertEquals("ready", artifact.path("status").asText(), artifact.toString());
        assertFalse(artifact.path("id").asText().isBlank(), artifact.toString());
        assertEquals(scenario.filePaths(), filePaths(artifact), artifact.toString());
        facts.put("artifactId", artifact.path("id").asText());
        facts.put("files", scenario.filePaths());
        facts.put("artifactStatus", artifact.path("status").asText());
        var presentation = objectMapper.treeToValue(toolProcess.path("presentation"),
                com.skillforge.core.chat.contract.ProcessPresentation.class);
        assertTrue(presentation.references().stream().anyMatch(reference ->
                reference.source() instanceof com.skillforge.core.chat.contract.ChatInputReference.ArtifactFiles files
                && files.files().stream().anyMatch(file -> file.artifactId().equals(artifact.path("id").asText()))),
                "实际文件通过结构化引用关联");
        var visible = com.skillforge.institution.runtime.PublicPresentationTexts.read(toolProcess.path("presentation"));
        assertTrue(visible.stream().noneMatch(text -> text.contains(artifact.path("id").asText()) || text.contains(runId)),
                "内部标识不能进入公开正文和引用标签");

        assertPublishedDeliverable(accessToken, sessionId, runId, scenario);
        facts.put("publishedDeliverableAndDownloadVerified", true);

        assertEquals(3, queryCount(jdbc, tenantId,
                "select count(*) from agent_runtime_actions "
                        + "where tenant_id = ? and run_id = ? and action_kind = 'tool.invoke' "
                        + "and execute_call_kind = 'TOOL_INVOKE' "
                        + "and state = 'SUCCEEDED'",
                tenantId, runId));
        assertEquals(3, queryCount(jdbc, tenantId,
                "select count(*) from agent_runtime_action_attempt_facts f "
                        + "join agent_runtime_actions a "
                        + "on a.tenant_id = f.tenant_id and a.action_id = f.action_id "
                        + "where f.tenant_id = ? and a.run_id = ? "
                        + "and f.call_kind = 'TOOL_INVOKE' and f.status = 'SUCCEEDED'",
                tenantId, runId));
        facts.put("successfulToolActions", 3);
        facts.put("successfulToolAttempts", 3);
        return new ScenarioResult(sessionId, artifact);
    }

    /** 只保存测试身份与有界账本字段；不保存请求、日志正文、签名 URL 或访问凭据。 */
    private void writeSmokeEvidence(String fixtureId, String tenantId, Map<String, Object> evidence,
            List<Map<String, Object>> scenarios, Throwable failure) throws Exception {
        try {
            for (var scenario : scenarios) {
                String run = (String) scenario.get("runId");
                scenario.put("runs", queryRows(jdbc, tenantId,
                        "select status,run_revision from agent_runtime_runs where tenant_id=? and run_id=?", tenantId, run));
                scenario.put("actions", queryRows(jdbc, tenantId,
                        "select action_id,invocation_id,action_kind,execute_call_kind,state,reason_code "
                                + "from agent_runtime_actions where tenant_id=? and run_id=? order by action_id", tenantId, run));
                scenario.put("attempts", queryRows(jdbc, tenantId,
                        "select f.action_id,f.invocation_id,f.attempt_id,f.evidence_revision,f.status "
                                + "from agent_runtime_action_attempt_facts f join agent_runtime_actions a "
                                + "using(tenant_id,action_id,invocation_id) where a.tenant_id=? and a.run_id=? "
                                + "order by f.action_id,f.attempt_id,f.evidence_revision", tenantId, run));
            }
            Path directory = smokeEvidenceDirectory(fixtureId);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("code-execution-smoke-evidence.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence), StandardCharsets.UTF_8);
        } catch (Exception evidenceFailure) {
            if (failure != null) failure.addSuppressed(evidenceFailure);
            else throw evidenceFailure;
        }
    }

    private Path smokeEvidenceDirectory(String fixtureId) {
        String configured = value(System.getProperty("skillforge.code-execution.smoke-evidence-dir"));
        if (configured.isBlank()) configured = value(System.getenv("CODE_EXECUTION_SMOKE_EVIDENCE_DIR"));
        return (configured.isBlank() ? Path.of("build", "runtime-e2e", "code-execution-smoke-evidence")
                : Path.of(configured)).toAbsolutePath().normalize().resolve(fixtureId);
    }

    /** 浏览器只读本次真实预览；短期票据通过 stdin 传递，不写入日志或配置文件。 */
    private JsonNode assertBrowserPreviews(String accessToken, ScenarioResult website, String fixtureId) throws Exception {
        Path repository = Path.of("").toAbsolutePath().normalize();
        while (repository != null && !Files.isRegularFile(repository.resolve("frontend-admin/package.json"))) {
            repository = repository.getParent();
        }
        assertNotNull(repository, "浏览器验收必须定位当前仓库");
        assertTrue(Files.isRegularFile(repository.resolve("frontend-admin/node_modules/@playwright/test/package.json")),
                "显式浏览器验收需要已有 Playwright 依赖和 Chromium");
        Path script = repository.resolve("tools/frontend/check-code-execution-preview-browser.mjs");
        assertTrue(Files.isRegularFile(script));
        Path directory = smokeEvidenceDirectory(fixtureId).resolve("browser");
        Files.createDirectories(directory);
        JsonNode listed = json(send(accessToken, "/api/v1/chat/sessions/deliverables?sessionId="
                + java.net.URLEncoder.encode(website.sessionId(), StandardCharsets.UTF_8), "GET", null), 200);
        assertEquals(1, listed.size(), "网站应有一个当前正式成果");
        JsonNode revision = listed.get(0);
        JsonNode file = element(revision.path("files"), "path", "index.html");
        String artifactId = website.artifact().path("id").asText();
        String revisionId = revision.path("id").asText();
        String fileId = file.path("file_id").asText();
        assertFalse(artifactId.isBlank(), "候选产物 id 必须存在");
        assertFalse(revisionId.isBlank(), "正式成果 Revision id 必须存在");
        assertFalse(fileId.isBlank(), "正式成果 file_id 必须存在");
        assertEquals(website.sessionId(), revision.path("session_id").asText(), "正式成果必须属于本次 Session");
        assertEquals(artifactId, file.path("artifact_id").asText(), "正式成果必须引用本次候选产物");
        assertEquals(artifactId + ":index.html", fileId, "正式文件身份必须绑定实际产物和精确路径");
        // 生产 file_id 是 artifact UUID + ':' + path，含扩展名；证据单独记录摘要，不改变 API 身份。
        String fileIdSha256 = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(fileId.getBytes(StandardCharsets.UTF_8)));
        JsonNode candidateTicket = json(send(accessToken, "/api/v1/chat/artifacts/preview-ticket", "POST",
                Map.of("id", artifactId)), 200);
        JsonNode formalTicket = json(send(accessToken, "/api/v1/chat/deliverables/preview-ticket", "POST",
                Map.of("id", revisionId, "file_id", fileId)), 200);
        assertEquals("html", formalTicket.path("kind").asText());
        Map<String, Object> configuration = Map.of(
                "candidateUrl", candidateTicket.path("url").asText(),
                "formalUrl", formalTicket.path("url").asText(),
                "hostOrigin", "http://localhost:" + port,
                "expectedTitle", "SkillForge static website " + fixtureId,
                "evidenceDirectory", directory.toString(),
                "identities", Map.of("sessionId", website.sessionId(), "artifactId", artifactId,
                        "revisionId", revisionId, "fileIdSha256", fileIdSha256));
        ProcessBuilder command = new ProcessBuilder("node", script.toString()).directory(repository.toFile());
        // 仅保留 Node/Chromium 启动需要的本机环境，不把 Runner、模型或平台凭据传给子进程。
        var inherited = new LinkedHashMap<>(command.environment());
        command.environment().clear();
        for (var entry : inherited.entrySet()) {
            if (Set.of("PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "HOME", "USERPROFILE", "LOCALAPPDATA",
                    "APPDATA", "TEMP", "TMP", "PLAYWRIGHT_BROWSERS_PATH").contains(entry.getKey().toUpperCase(java.util.Locale.ROOT))) {
                command.environment().put(entry.getKey(), entry.getValue());
            }
        }
        command.redirectErrorStream(true).redirectOutput(directory.resolve("browser.log").toFile());
        Process process = command.start();
        Set<ProcessHandle> owned = new LinkedHashSet<>();
        owned.add(process.toHandle());
        try {
            try (var input = process.getOutputStream()) { input.write(objectMapper.writeValueAsBytes(configuration)); }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100);
            boolean finished = false;
            while (System.nanoTime() < deadline) {
                captureBrowserProcesses(owned);
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) { finished = true; break; }
            }
            captureBrowserProcesses(owned);
            assertTrue(finished, "产物浏览器验收超时；诊断位于 " + directory);
            assertEquals(0, process.exitValue(), "产物浏览器验收失败；安全诊断位于 " + directory);
            var receipt = objectMapper.readTree(Files.readString(directory.resolve("browser-receipt.json"), StandardCharsets.UTF_8));
            assertEquals("passed", receipt.path("status").asText());
            assertTrue(receipt.path("browserClosed").asBoolean());
            return receipt;
        } finally {
            captureBrowserProcesses(owned);
            for (var handle : List.copyOf(owned).reversed()) if (handle.isAlive()) handle.destroy();
            awaitBrowserProcesses(owned, 5);
            for (var handle : List.copyOf(owned).reversed()) if (handle.isAlive()) handle.destroyForcibly();
            awaitBrowserProcesses(owned, 10);
            assertTrue(owned.stream().noneMatch(ProcessHandle::isAlive), "本次 Node/Chromium 子树必须完全结束");
        }
    }

    private static void captureBrowserProcesses(Set<ProcessHandle> owned) {
        for (var handle : List.copyOf(owned)) handle.descendants().forEach(owned::add);
    }

    private static void awaitBrowserProcesses(Set<ProcessHandle> owned, int seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (owned.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            captureBrowserProcesses(owned);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
    }

    private void assertPublishedDeliverable(
            String accessToken, String sessionId, String runId, Scenario scenario) throws Exception {
        String query = java.net.URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
        JsonNode listed = json(send(accessToken,
                "/api/v1/chat/sessions/deliverables?sessionId=" + query + "&includeHistory=true",
                "GET", null), 200);
        assertTrue(listed.isArray(), listed.toString());
        assertEquals(1, listed.size(), listed.toString());
        JsonNode deliverable = listed.get(0);
        assertEquals(runId, deliverable.path("run_id").asText(), deliverable.toString());
        assertTrue(containsText(deliverable.path("result_run_ids"), runId), deliverable.toString());
        assertEquals(scenario.artifactType(), deliverable.path("type").asText(), deliverable.toString());
        assertEquals("ready", deliverable.path("status").asText(), deliverable.toString());
        assertTrue(deliverable.path("current").asBoolean(), deliverable.toString());
        assertEquals(scenario.filePaths(), filePaths(deliverable), deliverable.toString());

        StringBuilder downloaded = new StringBuilder();
        for (JsonNode file : deliverable.path("files")) {
            JsonNode ticket = json(send(accessToken, "/api/v1/chat/deliverables/download-ticket", "POST",
                    Map.of("id", deliverable.path("id").asText(),
                            "file_id", file.path("file_id").asText())), 200);
            URI issued = URI.create(ticket.path("url").asText());
            assertEquals("127.0.0.1", issued.getHost(), "成果下载必须使用本次应用源");
            assertEquals(port, issued.getPort(), "成果下载必须使用本次应用端口");
            assertEquals("/api/v1/chat/deliverable-content/detail", issued.getPath());
            assertTrue(issued.getRawQuery().startsWith("ticket="));
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl()).resolve(issued))
                    .timeout(SMOKE_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            String body = new String(response.body(), StandardCharsets.UTF_8);
            assertEquals(200, response.statusCode(), body);
            assertFalse(body.isBlank(), file.toString());
            assertEquals(file.path("download_name").asText(),
                    org.springframework.http.ContentDisposition.parse(response.headers()
                            .firstValue("Content-Disposition").orElseThrow()).getFilename(), file.toString());
            downloaded.append(body).append('\n');
        }
        assertTrue(downloaded.toString().contains(scenario.expectedContent()), downloaded.toString());
    }

    private void assertDownload(
            String accessToken, JsonNode artifact, String path, String expected) throws Exception {
        String artifactId = artifact.path("id").asText();
        JsonNode ticket = json(send(accessToken, "/api/v1/chat/artifacts/download-ticket", "POST", Map.of("id", artifactId, "payload", Map.of("path", path, "zip", false))), 200);
        URI issued = URI.create(ticket.path("url").asText());
        assertEquals("127.0.0.1", issued.getHost(), "候选下载必须使用本次应用源");
        assertEquals(port, issued.getPort(), "候选下载必须使用本次应用端口");
        assertEquals("/api/v1/chat/artifact-content/detail", issued.getPath());
        assertTrue(issued.getRawQuery().startsWith("ticket="));
        HttpRequest request = HttpRequest.newBuilder(issued)
                .timeout(SMOKE_TIMEOUT)
                .GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains(expected));
        assertTrue(response.headers().firstValue("Content-Disposition").orElse("")
                .contains(path));
    }

    private void assertPreview(String accessToken, JsonNode artifact, String expected) throws Exception {
        JsonNode ticket = json(send(accessToken, "/api/v1/chat/artifacts/preview-ticket", "POST", Map.of("id", artifact.path("id").asText())), 200);
        URI preview = URI.create(ticket.path("url").asText());
        assertEquals("127.0.0.1", preview.getHost());
        assertEquals(PREVIEW_PORT, preview.getPort());
        assertTrue(preview.getPath().startsWith("/p/"));

        HttpRequest request = HttpRequest.newBuilder(preview)
                .timeout(SMOKE_TIMEOUT)
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains(expected), response.body());
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("")
                .contains("sandbox allow-scripts"));
    }

    private void setToolBudget(int calls, long actor) {
        var current = settings.read(ChatBudgetSettings.GROUP);
        var values = new LinkedHashMap<>(current.values());
        // 可委派目标随第二个 Agent 发布而变化；两个主助手场景都使用同一父子共享额度。
        for (var profile : List.of(ChatBudgetSettings.Profile.STANDARD_ROOT,
                ChatBudgetSettings.Profile.DELEGATION_ROOT, ChatBudgetSettings.Profile.TEAM_ROOT)) {
            values.put(profile.field(ExecutionBudgetPort.Dimension.TOOLS), calls);
        }
        settings.update(ChatBudgetSettings.GROUP, current.revision(), values, actor);
    }

    private void assertToolBudget(String tenantId, String runId, long calls) {
        var run = new RunRef(new TenantId(tenantId), runId);
        var budget = executionBudgets.snapshot(run);
        assertEquals(run, budget.registration().root(), "新 Session 的程序和子调用共享本任务根账本");
        Long budgetRevision = runSettings.requireFrozen(tenantId, runId).groups().get(ChatBudgetSettings.GROUP);
        assertNotNull(budgetRevision, "预算修订必须来自本 Run 原冻结配置");
        assertEquals("budget-policy.v4:" + ChatBudgetSettings.GROUP + ":" + budgetRevision,
                budget.registration().policy().sourceVersion());
        assertEquals(calls, budget.registration().policy().requireEnforced().root().tools());
        assertEquals(calls, budget.registration().policy().requireEnforced().run().tools());
        assertEquals(calls, budget.consumed().tools(), "父工具与成功子调用分别占用根工具额度");
        assertEquals(calls, budget.runConsumed().tools());
        assertEquals(0, budget.reserved().tools());
        assertEquals(0, budget.runReserved().tools());
    }

    private String runProgramScenario(String tenantId, String accessToken, String agentId, Long endUserId, String input,
            AtomicReference<RuntimeException> failure, DeterministicCodeModel model, Duration observationLimit) throws Exception {
        String sessionId = createSession(accessToken, agentId, endUserId);
        String runId = "run:" + input;
        int finalCount = model.respondCount();
        try {
            // 小型本地工具场景须先于外层 JUnit 超时给出真实事实诊断，不以延长等待掩盖未收敛。
            SseCapture sse = submitAndObserve(accessToken, Map.of("content", List.of(Map.of("type", "text", "text", PROGRAM_MARKER)),
                    "session_id", sessionId, "agent_id", agentId, "client_message_id", input,
                    "context", Map.of("end_user_id", endUserId)), () -> {
                        if (failure.get() != null) throw new AssertionError("program child call failed", failure.get());
                        if (model.fixtureFailure.get() != null) throw new AssertionError("program model fixture failed", model.fixtureFailure.get());
                    }, observationLimit);
            assertEquals(200, sse.statusCode(), sse.raw());
            assertTrue(sse.events().contains("execution.completed"), sse.raw());
            assertEquals(finalCount + 1, model.respondCount(), model.responseKinds().toString());
            return runId;
        } catch (Exception | AssertionError problem) {
            throw new AssertionError("program scenario did not complete; models=" + model.responseKinds()
                    + "; runs=" + queryRows(jdbc, tenantId,
                            "select status,run_revision from agent_runtime_runs where tenant_id=? and run_id=?", tenantId, runId)
                    + "; actions=" + queryRows(jdbc, tenantId,
                            "select action_id,action_kind,state,reason_code from agent_runtime_actions where tenant_id=? and run_id=?", tenantId, runId)
                    + "; attempts=" + queryRows(jdbc, tenantId,
                            "select f.action_id,f.attempt_id,f.evidence_revision,f.status from agent_runtime_action_attempt_facts f "
                                    + "join agent_runtime_actions a on a.tenant_id=f.tenant_id and a.action_id=f.action_id "
                                    + "where a.tenant_id=? and a.run_id=?", tenantId, runId)
                    + "; inbox=" + queryRows(jdbc, tenantId,
                            "select status,reason_code,attempts from agent_runtime_session_inbox where tenant_id=? and input_id=?", tenantId, input), problem);
        }
    }

    private PlatformTool resolveCodeExecutionTool(String accessToken) throws Exception {
        return resolvePlatformTool(accessToken, "code_execute");
    }

    private PlatformTool resolvePlatformTool(String accessToken, String toolName) throws Exception {
        JsonNode definitions = json(send(accessToken,
                "/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED",
                "GET", null), 200);
        JsonNode definition = element(definitions, "capability_key", "tool:" + toolName);
        long definitionId = definition.path("id").asLong();
        assertTrue(definitionId > 0, definitions.toString());

        JsonNode versions = json(send(accessToken, "/api/v1/capabilities/definitions/versions?status=PUBLISHED&" + "definitionId=" + java.net.URLEncoder.encode(String.valueOf(definitionId), java.nio.charset.StandardCharsets.UTF_8), "GET", null), 200);
        String publishedVersion = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                .filter(entry -> entry.name().equals(toolName)).findFirst().orElseThrow().publishedVersionKey();
        JsonNode version = element(versions, "version_key", publishedVersion);
        long versionId = version.path("id").asLong();
        assertTrue(versionId > 0, versions.toString());
        assertEquals(toolName, version.path("descriptor").path("tool_name").asText(),
                version.toString());
        return new PlatformTool(definitionId, versionId);
    }

    private Deployment createDeployment(
            String accessToken, PlatformTool tool, String fixtureId) throws Exception {
        return createDeployment(accessToken, tool, fixtureId, "code_execute");
    }

    private Deployment createDeployment(
            String accessToken, PlatformTool tool, String fixtureId, String toolName) throws Exception {
        String key = toolName.replace('_', '-') + "-smoke-" + fixtureId;
        JsonNode response = json(send(accessToken, "/api/v1/capabilities/deployments", "POST",
                Map.of(
                        "deployment_key", key,
                        "tool_version_id", tool.versionId(),
                        "provider_type", "PLATFORM",
                        "provider_ref", toolName,
                        "environment", "TEST",
                        "status", "ACTIVE")), 201);
        assertEquals(key, response.path("deployment_key").asText(), response.toString());
        assertEquals("PLATFORM", response.path("provider_type").asText(), response.toString());
        assertEquals(toolName, response.path("provider_ref").asText(), response.toString());
        long revision = response.path("revision").asLong();
        assertTrue(revision > 0, response.toString());
        return new Deployment(key, revision);
    }

    private String createAgent(String accessToken, String fixtureId) throws Exception {
        JsonNode created = json(send(accessToken, "/api/v1/agents", "POST", Map.of(
                "agent_key", "code-execution-smoke-agent-" + fixtureId,
                "name", "Code Execution Smoke Agent",
                "system_prompt", "需要生成成果时依次登记计划、执行代码并冻结最终文件；普通工具任务完成后简洁回答。")), 200);
        String agentId = created.path("id").asText();
        assertFalse(agentId.isBlank(), created.toString());
        return agentId;
    }

    private void publishAgent(String accessToken, String agentId) throws Exception {
        json(send(accessToken, "/api/v1/agents/publish", "POST", Map.of("agent_id", agentId)), 200);
    }

    private void mountOutputChain(
            String accessToken, String agentId,
            PlatformTool code, Deployment codeDeployment,
            PlatformTool finalize, Deployment finalizeDeployment)
            throws Exception {
        mountTool(accessToken, agentId, code, codeDeployment);
        mountTool(accessToken, agentId, finalize, finalizeDeployment);
    }

    private String mountTool(
            String accessToken, String agentId, PlatformTool tool, Deployment deployment)
            throws Exception {
        JsonNode mounted = json(send(accessToken, "/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                        "capability_type", "TOOL",
                        "capability_definition_id", Long.toString(tool.definitionId()),
                        "capability_version_id", Long.toString(tool.versionId()),
                        "tool_config", Map.of(
                                "tool_deployment_id", deployment.key(),
                                "tool_deployment_revision", deployment.revision()),
                        "priority", 0))), 201);
        assertTrue(mounted.path("mounts").isArray(), mounted.toString());
        JsonNode selected = element(mounted.path("mounts"), "capability_definition_id", Long.toString(tool.definitionId()));
        assertEquals("TOOL", selected
                .path("capability_type").asText(), mounted.toString());
        String mountId = selected.path("mount_id").asText();
        assertFalse(mountId.isBlank(),
                mounted.toString());
        return "tool:" + mountId;
    }

    private String createSession(String accessToken, String agentId, Long endUserId) throws Exception {
        JsonNode response = json(send(accessToken, "/api/v1/chat/sessions", "POST",
                Map.of("agent_id", agentId, "end_user_id", endUserId)), 200);
        String sessionId = response.path("session_id").asText();
        assertFalse(sessionId.isBlank(), response.toString());
        return sessionId;
    }

    private void configureModel(String tenantId, String endpoint) {
        PlatformModelFixtures.selectLlm(modelCatalog, tenantId, endpoint,
                "code-execution-smoke-model", "deterministic-code-model-key",
                "custom", "custom-openai-compatible", "custom-v1");
    }

    private String login(String tenantSlug, String username) throws Exception {
        JsonNode response = json(send(null, "/api/v1/auth/login", "POST", Map.of(
                "tenant_slug", tenantSlug,
                "username", username,
                "password", PASSWORD)), 200);
        String token = response.path("access_token").asText();
        assertFalse(token.isBlank(), "登录成功响应应包含访问凭据");
        return token;
    }

    private void createMember(String tenantId, String username) {
        TenantMemberDO member = new TenantMemberDO();
        member.setId(SnowflakeIds.nextId());
        member.setTenantId(Long.valueOf(tenantId));
        member.setUsername(username);
        member.setRole("admin");
        member.setPermissions(List.of());
        member.setStatus("active");
        member.setPasswordHash(passwordEncoder.encode(PASSWORD));
        member.setCreatedAt(Instant.now());
        tenantMemberMapper.insert(member);
        initializeTenantAdministrator(tenantId, member.getId());
    }

    private Long createEndUser(String tenantId) {
        EndUserDO user = new EndUserDO();
        user.setId(SnowflakeIds.nextId());
        user.setTenantId(Long.valueOf(tenantId));
        user.setDisplayName("Code Execution Smoke User");
        user.setStatus("active");
        user.setFirstSeenAt(Instant.now());
        user.setLastActiveAt(Instant.now());
        user.setCreatedAt(Instant.now());
        endUserMapper.insert(user);
        return user.getId();
    }

    private SseCapture submitAndObserve(String accessToken, Map<String, Object> body) throws Exception {
        return submitAndObserve(accessToken, body, () -> { });
    }

    private SseCapture submitAndObserve(String accessToken, Map<String, Object> body, Runnable checkFailure) throws Exception {
        return submitAndObserve(accessToken, body, checkFailure, SMOKE_TIMEOUT);
    }

    private SseCapture submitAndObserve(String accessToken, Map<String, Object> body, Runnable checkFailure,
            Duration observationTimeout) throws Exception {
        JsonNode receipt = json(send(accessToken, "/api/v1/chat", "POST", body), 202);
        assertFalse(receipt.path("execution_id").asText().isBlank(), "单次提交必须取得持久Run回执");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/v1/chat/events?session_id="
                        + java.net.URLEncoder.encode(String.valueOf(body.get("session_id")), StandardCharsets.UTF_8)))
                .timeout(SMOKE_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        List<String> events = new CopyOnWriteArrayList<>();
        StringBuilder raw = new StringBuilder();
        ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "code-execution-smoke-sse-reader");
            thread.setDaemon(true);
            return thread;
        });
        Future<SseCapture> capture = reader.submit(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                    response.body(), StandardCharsets.UTF_8))) {
                String event = null;
                String line;
                while ((line = lines.readLine()) != null) {
                    synchronized (raw) {
                        raw.append(line).append('\n');
                    }
                    if (line.startsWith("event:")) {
                        event = line.substring("event:".length()).trim();
                    } else if (line.isBlank() && event != null) {
                        events.add(event);
                        if ("execution.completed".equals(event) || "error".equals(event)) {
                            break;
                        }
                        event = null;
                    }
                }
            }
            return new SseCapture(response.statusCode(), List.copyOf(events), raw.toString());
        });
        try {
            long deadline = System.nanoTime() + observationTimeout.toNanos();
            while (true) {
                checkFailure.run();
                try {
                    var result = capture.get(200, TimeUnit.MILLISECONDS);
                    checkFailure.run();
                    return result;
                } catch (TimeoutException waiting) {
                    if (System.nanoTime() >= deadline) throw waiting;
                }
            }
        } catch (TimeoutException timeout) {
            response.body().close();
            capture.cancel(true);
            throw new AssertionError("SSE did not complete; events=" + events + "; raw=" + raw,
                    timeout);
        } finally {
            response.body().close();
            reader.shutdownNow();
            assertTrue(reader.awaitTermination(10, TimeUnit.SECONDS), "观察读取线程必须完成清理");
        }
    }

    private HttpResponse<String> send(
            String accessToken, String path, String method, Map<String, Object> body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(SMOKE_TIMEOUT)
                .header("Content-Type", "application/json");
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        HttpRequest request = body == null
                ? builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
                : builder.method(method, HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response, int expectedStatus) throws Exception {
        assertEquals(expectedStatus, response.statusCode(), response.body());
        var envelope=objectMapper.readTree(response.body());
        if (expectedStatus >= 200 && expectedStatus < 300) {
            assertEquals("OK",envelope.path("code").asText(),"成功响应须符合公开API envelope");
            assertTrue(envelope.has("data"),"成功响应必须具有data字段");
            return envelope.path("data");
        }
        return envelope;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static JsonNode element(JsonNode array, String field, String expected) {
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                if (expected.equals(item.path(field).asText())) {
                    return item;
                }
            }
        }
        throw new AssertionError("Missing " + field + '=' + expected + " in " + array);
    }

    private static JsonNode succeededCodeExecutionProcess(JsonNode history, String runId) {
        for (JsonNode process : history.path("processes")) {
            if (runId.equals(process.path("run_id").asText())
                    && "tool".equals(process.path("type").asText())
                    && "succeeded".equals(process.path("status").asText())
                    && process.path("presentation").path("references").isArray()
                    && !process.path("presentation").path("references").isEmpty()) {
                return process;
            }
        }
        throw new AssertionError("Missing succeeded code execution process for " + runId + " in " + history);
    }

    private static List<String> filePaths(JsonNode artifact) {
        List<String> paths = new ArrayList<>();
        artifact.path("files").forEach(file -> paths.add(file.path("path").asText()));
        paths.sort(String::compareTo);
        return List.copyOf(paths);
    }

    private static boolean containsText(JsonNode value, String expected) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return value.asText().contains(expected);
        }
        if (value.isContainerNode()) {
            for (JsonNode child : value) {
                if (containsText(child, expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalizeEndpoint(String endpoint) {
        String normalized = value(endpoint);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String value(String input) {
        return input == null ? "" : input.trim();
    }

    private static boolean validRunnerConfiguration() {
        if (RUNNER_ENDPOINT.isBlank() || RUNNER_TOKEN.length() < 32) {
            return false;
        }
        try {
            URI endpoint = URI.create(RUNNER_ENDPOINT);
            return Set.of("http", "https").contains(endpoint.getScheme())
                    && endpoint.getHost() != null
                    && endpoint.getUserInfo() == null
                    && endpoint.getQuery() == null
                    && endpoint.getFragment() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static Ports availablePorts() {
        // 两个 socket 同时占位，保证平台与预览监听器不会拿到同一端口；返回后由 Spring 接管。
        try (ServerSocket application = new ServerSocket(); ServerSocket preview = new ServerSocket()) {
            application.setReuseAddress(false);
            preview.setReuseAddress(false);
            application.bind(new InetSocketAddress("127.0.0.1", 0));
            preview.bind(new InetSocketAddress("127.0.0.1", 0));
            return new Ports(application.getLocalPort(), preview.getLocalPort());
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @AfterAll
    static void deleteArtifacts() throws IOException {
        if (!Files.exists(ARTIFACT_ROOT)) {
            return;
        }
        Files.walkFileTree(ARTIFACT_ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record PlatformTool(long definitionId, long versionId) {
    }

    private record Deployment(String key, long revision) {
    }

    private record Ports(int application, int preview) {
    }

    private record Scenario(
            String inputId,
            String prompt,
            String artifactType,
            List<String> filePaths,
            String expectedContent) {
    }

    private record ScenarioResult(String sessionId, JsonNode artifact) {
        private ScenarioResult {
            assertNotNull(sessionId);
            assertNotNull(artifact);
        }
    }

    private record SseCapture(int statusCode, List<String> events, String raw) {
    }

    /**
     * 测试模型端点实现并验收 JSON Object、流式和原生 Tool Calling，因此为该本地 fixture
     * 注册独立已验证 profile。Runtime 的实际 HTTP 调用仍由正式 OpenAI-compatible Provider
     * 发送，只有目录中的能力事实替换为测试端点已经实现的精确能力。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class VerifiedModelProviderConfiguration {
        @Bean
        ModelProviderCatalog codeExecutionSmokeModelProviderCatalog() {
            CapabilitySupport capabilities = new CapabilitySupport(
                    SupportLevel.SUPPORTED,
                    SupportLevel.SUPPORTED,
                    SupportLevel.SUPPORTED,
                    SupportLevel.SUPPORTED,
                    SupportLevel.SUPPORTED);
            ProtocolProfile protocol = new ProtocolProfile(
                    ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                    "openai-compatible-chat-v1",
                    Set.of(AuthenticationScheme.BEARER_API_KEY),
                    capabilities,
                    VerificationStatus.VERIFIED);
            ServiceProfile service = new ServiceProfile(
                    "custom-openai-compatible",
                    "Code execution deterministic fixture",
                    "custom-v1",
                    Availability.SUPPORTED,
                    null,
                    EndpointMode.CUSTOM,
                    null,
                    List.of(protocol), ServiceRegion.LOCAL, Set.of(ModelKind.LLM));
            ModelProvider provider = () -> new ProviderPreset(
                    "custom",
                    "Code execution deterministic fixture",
                    ProviderCategory.CUSTOM_OR_SELF_HOSTED,
                    List.of(service));
            return new ModelProviderCatalog(List.of(provider));
        }
    }

    /** 本地确定性 OpenAI-compatible 模型；仅保存响应种类，不保存完整 prompt。 */
    private static final class DeterministicCodeModel implements AutoCloseable {
        private final ObjectMapper mapper;
        private final HttpServer server;
        private final CopyOnWriteArrayList<String> responseKinds = new CopyOnWriteArrayList<>();
        private final AtomicInteger requestSequence = new AtomicInteger();
        private final AtomicReference<Exception> fixtureFailure = new AtomicReference<>();

        private DeterministicCodeModel(ObjectMapper mapper) throws IOException {
            this.mapper = mapper;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/chat/completions", this::handle);
        }

        private void start() {
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private int toolCallCount() {
            return (int) responseKinds.stream().filter("tool_call"::equals).count();
        }

        private int respondCount() {
            return (int) responseKinds.stream().filter("respond"::equals).count();
        }

        private List<String> responseKinds() {
            return List.copyOf(responseKinds);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                JsonNode request = mapper.readTree(exchange.getRequestBody());
                String content;
                ToolResponse tool = null;
                if (isMemoryExtraction(request)) {
                    responseKinds.add("memory");
                    content = mapper.writeValueAsString(Map.of("candidates", List.of()));
                } else if (shouldRespond(request, toolObservationCount(request))) {
                    responseKinds.add("respond");
                    content = responseText(request);
                    tool = null;
                } else {
                    responseKinds.add("tool_call");
                    content = null;
                    tool = toolResponse(request);
                }
                if (request.path("stream").asBoolean(false)) {
                    writeStream(exchange, request, content, tool);
                } else {
                    writeCompletion(exchange, request, content, tool);
                }
            } catch (Exception failure) {
                fixtureFailure.compareAndSet(null, failure);
                byte[] body = "{\"error\":{\"message\":\"deterministic fixture failure\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            } finally {
                exchange.close();
            }
        }

        private ToolResponse toolResponse(JsonNode request) throws IOException {
            JsonNode tools = request.path("tools");
            if (serializedMessages(request).contains(PROGRAM_MARKER)) {
                JsonNode program = java.util.stream.StreamSupport.stream(tools.spliterator(), false)
                        .map(value -> value.path("function"))
                        .filter(value -> value.path("description").asText().contains("编程调用已显示的授权工具"))
                        .findFirst().orElseThrow(() -> new IOException("fixture did not receive run_code schema"));
                if (!businessProperties(program).has("code")) throw new IOException("fixture did not receive native run_code arguments schema");
                return new ToolResponse("call-run-code-" + requestSequence.incrementAndGet(),
                        program.path("name").asText(), nativeToolArguments("正在组合已授权的工具调用并核对共享额度。", Map.of(
                                "code", "await tools.call('tool:clock', {});", "dependencies", List.of())));
            }
            int observations = toolObservationCount(request);
            if (observations == 1) {
                return finalizeResponse(request, tools);
            }
            if (observations != 0) {
                throw new IOException("fixture received unexpected tool observation count: " + observations);
            }
            var codeTools = java.util.stream.StreamSupport.stream(tools.spliterator(), false)
                    .map(value -> value.path("function"))
                    .filter(value -> businessProperties(value).has("mode")
                            && businessProperties(value).has("output_files"))
                    .toList();
            if (codeTools.size() != 1) throw new IOException("fixture expected one code_execute tool");
            JsonNode function = codeTools.getFirst();
            String wireName = function.path("name").asText();
            JsonNode properties = businessProperties(function);
            if (wireName.isBlank() || !properties.has("mode") || !properties.has("language")
                    || !properties.has("code")) {
                throw new IOException("fixture did not receive code_execute schema");
            }
            boolean website = serializedMessages(request).contains(WEBSITE_MARKER);
            String code = website ? websiteCode(request) : documentCode(request);
            Map<String, Object> arguments = Map.of(
                    "mode", "inline",
                    "language", website ? "node" : "python",
                    "code", code,
                    "output_files", website ? List.of("index.html") : List.of("report.md"),
                    "dependencies", List.of());
            return new ToolResponse(
                    "call-code-execute-" + requestSequence.incrementAndGet(),
                    wireName,
                    nativeToolArguments("正在执行已确认的方案并核验产物。", arguments));
        }

        private ToolResponse finalizeResponse(JsonNode request, JsonNode tools) throws IOException {
            JsonNode function = outputControlFunction(tools, "representations");
            String artifactId = artifactId(request);
            List<Map<String, String>> files = deliverablePaths(request).stream()
                    .map(path -> Map.of("artifact_id", artifactId, "path", path))
                    .toList();
            Map<String, Object> representation = new LinkedHashMap<>();
            representation.put("role", "source");
            representation.put("format", deliverableFormat(request));
            representation.put("primary_preview", true);
            if (isWebsite(request)) {
                representation.put("entry_path", "index.html");
            }
            representation.put("files", files);
            Map<String, Object> deliverable = Map.of(
                    "key", deliverableKey(request),
                    "title", deliverableTitle(request),
                    "kind", deliverableKind(request),
                    "representations", List.of(representation));
            Map<String, Object> arguments = Map.of(
                    "deliverables", List.of(deliverable),
                    "evidence", List.of());
            return new ToolResponse(
                    "call-finalize-task-outputs-" + requestSequence.incrementAndGet(),
                    function.path("name").asText(),
                    nativeToolArguments("正在执行已确认的方案并核验产物。", arguments));
        }

        /** 当前原生业务函数的参数包包含公开摘要和完整业务 arguments。 */
        private String nativeToolArguments(String commentary, Map<String, Object> arguments) throws IOException {
            return mapper.writeValueAsString(Map.of("commentary", commentary, "arguments", arguments));
        }

        /** 根据实际显示的函数定义读取业务 schema，不绕开原生包装。 */
        private JsonNode businessProperties(JsonNode function) {
            JsonNode properties = function.path("parameters").path("properties");
            return properties.path("arguments").path("properties");
        }

        private JsonNode outputControlFunction(JsonNode tools, String itemField) throws IOException {
            var matches = java.util.stream.StreamSupport.stream(tools.spliterator(), false)
                    .map(value -> value.path("function"))
                    .filter(value -> businessProperties(value)
                            .path("deliverables").path("items").path("properties").has(itemField))
                    .toList();
            if (matches.size() != 1 || matches.getFirst().path("name").asText().isBlank()) {
                throw new IOException("fixture expected one output control tool with " + itemField);
            }
            return matches.getFirst();
        }

        private String artifactId(JsonNode request) throws IOException {
            JsonNode messages = request.path("messages");
            for (int index = messages.size() - 1; index >= 0; index--) {
                JsonNode message = messages.get(index);
                if (!"tool".equals(message.path("role").asText())) {
                    continue;
                }
                String content = message.path("content").asText("");
                int jsonStart = content.indexOf('{');
                if (jsonStart < 0) {
                    continue;
                }
                JsonNode result = mapper.readTree(content.substring(jsonStart));
                JsonNode output = result.path("output");
                JsonNode structured = output.path("structuredContent");
                if (!"complete".equals(output.path("resultType").asText())
                        || !structured.path("success").asBoolean(false)
                        || !"ready".equals(structured.path("delivery_status").asText())) {
                    continue;
                }
                JsonNode artifacts = structured.path("artifacts");
                if (artifacts.isArray() && !artifacts.isEmpty()) {
                    String id = artifacts.get(0).path("id").asText();
                    if (!id.isBlank()) {
                        return id;
                    }
                }
            }
            throw new IOException("fixture did not receive code execution artifact id");
        }

        private boolean isWebsite(JsonNode request) {
            return serializedMessages(request).contains(WEBSITE_MARKER);
        }

        private String deliverableKey(JsonNode request) {
            return isWebsite(request) ? "website" : "report";
        }

        private String deliverableTitle(JsonNode request) {
            return (isWebsite(request) ? "Smoke website " : "Smoke document ")
                    + markerSuffix(request, isWebsite(request) ? "website-" : "document-");
        }

        private String deliverableKind(JsonNode request) {
            return isWebsite(request) ? "website" : "document";
        }

        private String deliverableFormat(JsonNode request) {
            return isWebsite(request) ? "html" : "md";
        }

        private List<String> deliverablePaths(JsonNode request) {
            return isWebsite(request) ? List.of("index.html") : List.of("report.md");
        }

        private String documentCode(JsonNode request) {
            String fixtureId = markerSuffix(request, "document-");
            String body = "# SkillForge code execution smoke document " + fixtureId;
            return "from pathlib import Path\n"
                    + "output = Path('/workspace/output')\n"
                    + "output.mkdir(parents=True, exist_ok=True)\n"
                    + "(output / 'document-summary.json').write_text('{}')\n"
                    + "(output / 'input.json').write_text('{}')\n"
                    + "(output / 'report.md').write_text(" + pythonString(body)
                    + ", encoding='utf-8')\n"
                    + "(output / 'result.json').write_text(" + pythonString(
                            "{\"results\":[{\"kind\":\"text\",\"format\":\"plain\",\"text\":\"DOCUMENT_READY\"}]}")
                    + ", encoding='utf-8')\n"
                    + "print('" + DOCUMENT_STDOUT_MARKER + "')\n";
        }

        private String websiteCode(JsonNode request) {
            String fixtureId = markerSuffix(request, "website-");
            String title = "SkillForge static website " + fixtureId;
            String css = "body{font-family:sans-serif;color:#123456}";
            String html = "<!doctype html><html><head><meta charset=\"utf-8\"><style>" + css
                    + "</style></head><body><h1>"
                    + title + "</h1><button id=\"increment\" type=\"button\">增加计数</button>"
                    + "<p>计数：<span id=\"counter\">0</span></p>"
                    + "<p id=\"isolation-result\">pending</p><p id=\"origin-result\">pending</p>"
                    + "<script>document.getElementById('origin-result').textContent=window.origin;"
                    + "document.getElementById('increment').addEventListener('click',()=>{"
                    + "const counter=document.getElementById('counter');counter.textContent=String(Number(counter.textContent)+1);"
                    + "try{parent.document.getElementById('host-sentinel').textContent='escaped';"
                    + "document.getElementById('isolation-result').textContent='escaped';}"
                    + "catch(error){document.getElementById('isolation-result').textContent=error.name;}"
                    + "});</script></body></html>";
            return "import fs from 'node:fs';\n"
                    + "fs.mkdirSync('/workspace/output', {recursive: true});\n"
                    + "fs.writeFileSync('/workspace/output/debug.txt', 'internal');\n"
                    + "fs.writeFileSync('/workspace/output/index.html', " + jsString(html)
                    + ", 'utf8');\n"
                    + "fs.writeFileSync('/workspace/output/style.css', " + jsString(css)
                    + ", 'utf8');\n"
                    + "fs.writeFileSync('/workspace/output/result.json', " + jsString(
                            "{\"results\":[{\"kind\":\"text\",\"format\":\"plain\",\"text\":\"WEBSITE_READY\"}]}")
                    + ", 'utf8');\n"
                    + "console.log('" + WEBSITE_STDOUT_MARKER + "');\n";
        }

        private String responseText(JsonNode request) {
            String serialized = serializedMessages(request);
            if (serialized.contains(PROGRAM_MARKER)) {
                return "程序工具调用已完成";
            }
            if (serialized.contains(WEBSITE_MARKER) || serialized.contains("WEBSITE_READY")) {
                return "静态网站已生成：SkillForge static website "
                        + markerSuffix(request, "website-");
            }
            return "文档已生成：SkillForge code execution smoke document "
                    + markerSuffix(request, "document-");
        }

        /** 只从本场景真实 user 正文中的唯一精确 marker 读取数字，不扫描文件名或响应。 */
        private String markerSuffix(JsonNode request, String prefix) {
            String marker = switch (prefix) {
                case "document-" -> DOCUMENT_MARKER;
                case "website-" -> WEBSITE_MARKER;
                default -> throw new IllegalStateException("code fixture marker prefix was not supported");
            };
            var pattern = java.util.regex.Pattern.compile("^" + java.util.regex.Pattern.quote(marker)
                    + "\\s+" + java.util.regex.Pattern.quote(prefix) + "([0-9]+)(?:\\s|$)");
            var matches = new ArrayList<String>();
            for (JsonNode message : request.path("messages")) {
                if (!"user".equals(message.path("role").asText())) continue;
                JsonNode content = message.path("content");
                if (!content.isTextual()) continue;
                var match = pattern.matcher(content.asText());
                if (match.find()) matches.add(match.group(1));
            }
            if (matches.size() != 1) {
                throw new IllegalStateException("code fixture required exactly one original user marker");
            }
            return matches.getFirst();
        }

        private int toolObservationCount(JsonNode request) {
            int count = 0;
            for (JsonNode message : request.path("messages")) {
                if ("tool".equals(message.path("role").asText())) {
                    count++;
                }
            }
            return count;
        }

        private boolean shouldRespond(JsonNode request, int observations) {
            if (serializedMessages(request).contains(PROGRAM_MARKER)) {
                return observations >= 1;
            }
            return observations >= 2;
        }

        private boolean isMemoryExtraction(JsonNode request) {
            for (JsonNode message : request.path("messages")) {
                if (message.path("content").asText("").contains("记忆提取器")) {
                    return true;
                }
            }
            return false;
        }

        private String serializedMessages(JsonNode request) {
            return request.path("messages").toString();
        }

        private void writeCompletion(
                HttpExchange exchange, JsonNode request, String content, ToolResponse tool)
                throws IOException {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", content);
            if (tool != null) {
                message.put("tool_calls", List.of(tool.wire()));
            }
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", tool == null ? "stop" : "tool_calls");
            byte[] body = mapper.writeValueAsBytes(Map.of(
                    "id", "code-smoke-" + requestSequence.get(),
                    "object", "chat.completion",
                    "model", model(request),
                    "choices", List.of(choice),
                    "usage", usage()));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        private void writeStream(
                HttpExchange exchange, JsonNode request, String content, ToolResponse tool)
                throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                Map<String, Object> delta = new LinkedHashMap<>();
                delta.put("role", "assistant");
                if (tool == null) {
                    delta.put("content", content);
                } else {
                    delta.put("tool_calls", List.of(tool.delta()));
                }
                writeSse(output, request, List.of(choice(delta, null)), null);
                writeSse(output, request, List.of(choice(Map.of(),
                        tool == null ? "stop" : "tool_calls")), null);
                writeSse(output, request, List.of(), usage());
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        }

        private void writeSse(
                OutputStream output,
                JsonNode request,
                List<Map<String, Object>> choices,
                Map<String, Object> usage) throws IOException {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", "code-smoke-" + requestSequence.get());
            chunk.put("object", "chat.completion.chunk");
            chunk.put("model", model(request));
            chunk.put("choices", choices);
            if (usage != null) {
                chunk.put("usage", usage);
            }
            output.write(("data: " + mapper.writeValueAsString(chunk) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private static Map<String, Object> choice(
                Map<String, Object> delta, String finishReason) {
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", finishReason);
            return choice;
        }

        private static Map<String, Object> usage() {
            return Map.of("prompt_tokens", 40, "completion_tokens", 20, "total_tokens", 60);
        }

        private static String model(JsonNode request) {
            return request.path("model").asText("code-execution-smoke-model");
        }

        private static String pythonString(String value) {
            return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
        }

        private static String jsString(String value) {
            return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private record ToolResponse(String callId, String wireName, String arguments) {
            private Map<String, Object> wire() {
                return Map.of(
                        "id", callId,
                        "type", "function",
                        "function", Map.of("name", wireName, "arguments", arguments));
            }

            private Map<String, Object> delta() {
                return Map.of(
                        "index", 0,
                        "id", callId,
                        "type", "function",
                        "function", Map.of("name", wireName, "arguments", arguments));
            }
        }
    }
}
