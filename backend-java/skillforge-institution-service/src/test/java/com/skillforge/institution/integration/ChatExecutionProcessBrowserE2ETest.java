package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 以真实浏览器验证 Chat 公开 SSE 回复与历史恢复，不展示供应商内部推理。 */
class ChatExecutionProcessBrowserE2ETest extends ChatRuntimeChannelE2ETest {

    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("chat-process-browser-");
    @org.springframework.beans.factory.annotation.Autowired
    private com.skillforge.core.foundation.persistence.TenantMemberMapper webhookMembers;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.security.crypto.password.PasswordEncoder webhookPasswords;
    @org.springframework.beans.factory.annotation.Autowired
    private com.skillforge.core.foundation.settings.OperationalSettingsService operationalSettings;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.skillforge.core.workflow.execution.StateMachineEngine workflowEngine;
    private com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot originalRate;
    private long fixtureRateRevision;
    private long fixtureRateActor;
    private HttpErrorModel httpErrorModel;
    @org.springframework.beans.factory.annotation.Autowired
    private com.skillforge.core.chat.session.SessionMailboxService historyMailbox;

    @BeforeEach
    void configureBrowserRatePolicy() {
        // 多个浏览器场景共用真实限流器；为隔离测试负载设置容量。
        originalRate = operationalSettings.read("rate_limits");
        fixtureRateActor = com.skillforge.core.shared.config.SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(?,?,?,'disabled')",
                fixtureRateActor, "chat-browser-policy-" + fixtureRateActor, "unused");
        fixtureRateRevision = operationalSettings.update("rate_limits", originalRate.revision(),
                Map.of("requests_per_minute_default", 600), fixtureRateActor).revision();
        assertEquals(600, operationalSettings.read("rate_limits").integer("requests_per_minute_default"));
    }

    @AfterEach
    void restoreBrowserRatePolicy() {
        if (httpErrorModel != null) httpErrorModel.close();
        if (fixtureRateRevision != 0) {
            operationalSettings.update("rate_limits", fixtureRateRevision, originalRate.values(), fixtureRateActor);
            assertEquals(originalRate.values(), operationalSettings.read("rate_limits").values());
        }
    }

    @DynamicPropertySource
    static void allowIsolatedBrowserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
        if (System.getenv("E2E_HTTP_STATUS") != null) registry.add("skillforge.debug", () -> "true");
    }

    @AfterAll
    static void closeIsolatedFrontend() {
        FRONTEND.close();
    }

    @Test
    void realBrowserRestoresPublicReplyWithoutExposingInternalReasoning() throws Exception {
        runBrowser(false);
    }

    @Test
    @Tag("live")
    @Tag("live-browser")
    @EnabledIfEnvironmentVariable(named = "SDK4_LIVE_MODEL_KEY", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_CHAT_BROWSER", matches = "1")
    void liveDeepSeekBrowserSubmitsOnceAndRestoresPersistedPublicReply() throws Exception {
        runBrowser(true);
    }

    private void runBrowser(boolean live) throws Exception {
        createWebhookOnlyMember();
        if (System.getenv("E2E_HTTP_STATUS") != null) prepareHttpError();
        Path repoRoot = findRepoRoot();
        Path adminRoot = repoRoot.resolve("frontend-admin");
        Path playwright = adminRoot.resolve("node_modules/.bin")
                .resolve(isWindows() ? "playwright.cmd" : "playwright");
        assertTrue(Files.isRegularFile(playwright),
                "Playwright is not installed; run npm ci and npx playwright install chromium");

        Path evidence = Path.of("build", "runtime-e2e", "chat-process-browser-" + fixtureRunId())
                .toAbsolutePath();
        Files.createDirectories(evidence);
        // Playwright 启动时会清空自己的 outputDir，宿主日志不能放进该清理范围。
        Path browserEvidence = evidence.resolve("playwright");
        Path log = evidence.resolve("playwright-vite.log");
        ProcessBuilder command = new ProcessBuilder(
                playwright.toString(), "test", "e2e/chat-execution-process.spec.ts");
        command.directory(adminRoot.toFile());
        command.redirectErrorStream(true);
        command.redirectOutput(log.toFile());
        FRONTEND.bindBackend(baseUrl());
        System.out.println("CHAT_BROWSER_FRONTEND base_url=" + FRONTEND.baseUrl() + "; evidence=" + FRONTEND.evidence());
        Map<String, String> environment = command.environment();
        environment.put("E2E_BASE_URL", FRONTEND.baseUrl());
        environment.remove("SDK4_LIVE_MODEL_KEY");
        environment.remove("E2E_FRONTEND_PORT");
        environment.put("E2E_BACKEND_URL", baseUrl());
        environment.put("E2E_TENANT_SLUG", fixtureTenantSlug());
        environment.put("E2E_USERNAME", "runtime-e2e-admin");
        environment.put("E2E_WEBHOOK_USERNAME", "webhook-only");
        environment.put("E2E_PASSWORD", PASSWORD);
        environment.put("E2E_AGENT_ID", fixtureAgentId());
        String expectedReply = live ? "LIVE_BROWSER_" + fixtureRunId() : "fixture-answer-" + fixtureRunId();
        environment.put("E2E_EXPECTED_REPLY", expectedReply);
        if (live) {
            environment.put("E2E_LIVE_MODEL", "1");
            environment.put("E2E_MESSAGE", "请只回复以下唯一短标记，不添加其他文字，不调用任何工具：" + expectedReply);
        } else {
            environment.remove("E2E_LIVE_MODEL");
            environment.remove("E2E_MESSAGE");
        }
        environment.put("E2E_INTERNAL_REASONING", "fixture-reasoning-" + fixtureRunId());
        environment.put("E2E_ARTIFACT_DIR", browserEvidence.toString());
        if ("1".equals(System.getenv("NOEWISE_TEST_CANCEL_RACE"))) {
            Path gate = evidence.resolve("workflow-entered-gate");
            environment.put("E2E_WORKFLOW_CANCEL_GATE", gate.toString());
            org.mockito.Mockito.doAnswer(invocation -> {
                var request = (com.skillforge.core.workflow.execution.ControlledWorkflowExecution.Request) invocation.getArgument(0);
                if (!tenantId.equals(request.tenantId()))
                    return invocation.callRealMethod();
                Files.writeString(gate, "entered", StandardCharsets.UTF_8);
                long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < limit && countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=? AND cancelled", tenantId, request.sessionId()) == 0) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
                }
                if (countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=? AND cancelled", tenantId, request.sessionId()) == 0)
                    throw new AssertionError("隔离运行未收到取消请求");
                return invocation.callRealMethod();
            }).when(workflowEngine).runControlled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        if (System.getenv("E2E_CHAT_HISTORY_PAGES") != null) {
            String sentinel = "earliest-history-" + fixtureRunId();
            for (int i = 0; i < 65; i++) {
                String id = "history-page-" + String.format("%03d", i) + "-" + fixtureRunId();
                var data = Map.<String, Object>of("message_id", id, "message_version", 1,
                        "role", "assistant", "content", i == 0 ? sentinel : "历史分页消息 " + i,
                        "state", "completed", "citations", List.of());
                com.skillforge.common.context.TenantContext.runWithTenant(tenantId,
                        () -> historyMailbox.appendOutput(tenantId, sessionId, "fixture:" + id,
                                "message.delta", Map.of("event", "message.delta", "data", data)));
            }
            environment.put("E2E_HISTORY_SESSION", sessionId);
            environment.put("E2E_HISTORY_SENTINEL", sentinel);
            command.command().addAll(List.of("--grep", "已加载历史分页"));
        }
        if (System.getenv("E2E_HTTP_STATUS") != null) {
            environment.put("E2E_CHAT_INPUT_RECOVERY", "0");
            command.command().addAll(List.of("--grep", "\u771f\u5b9e\u673a\u6784\u767b\u5f55\u53d1\u9001\u4e00\u6b21"));
        }
        Process process = command.start();
        Set<ProcessHandle> owned = new LinkedHashSet<>();
        owned.add(process.toHandle());
        try {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            boolean finished = false;
            while (System.nanoTime() < deadline) {
                captureProcessTree(owned);
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) { finished = true; break; }
            }
            captureProcessTree(owned);
            assertTrue(finished, "Chat process browser E2E exceeded 5 minutes; evidence=" + evidence);
            String output = Files.exists(log)
                    ? Files.readString(log, StandardCharsets.UTF_8) : "<no Playwright log>";
            String tail = output.length() > 6000 ? output.substring(output.length() - 6000) : output;
            var closeEvidence = Map.of(
                    "tests", queryRows(jdbc, tenantId, "SELECT id,session_id,status FROM nw_workflow_test_sessions WHERE tenant_id=?", Long.parseLong(tenantId)),
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,status,phase,cancelled,lease_claim_id IS NOT NULL AS leased FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id IN (SELECT session_id FROM nw_workflow_test_sessions WHERE tenant_id=CAST(? AS bigint))", tenantId, tenantId),
                    "attempts", queryRows(jdbc, tenantId, "SELECT p.run_id,p.result_kind,p.adopted FROM agent_runtime_execution_attempts p JOIN agent_runtime_execution_runs r ON r.tenant_id=p.tenant_id AND r.run_id=p.run_id WHERE r.tenant_id=? AND r.session_id IN (SELECT session_id FROM nw_workflow_test_sessions WHERE tenant_id=CAST(? AS bigint))", tenantId, tenantId),
                    "budgets", queryRows(jdbc, tenantId, "SELECT b.run_id,b.state,b.category FROM agent_runtime_budget_attempts b JOIN agent_runtime_execution_runs r ON r.tenant_id=b.tenant_id AND r.run_id=b.run_id WHERE r.tenant_id=? AND r.session_id IN (SELECT session_id FROM nw_workflow_test_sessions WHERE tenant_id=CAST(? AS bigint))", tenantId, tenantId),
                    "workflows", queryRows(jdbc, tenantId, "SELECT execution_id,run_id,session_id,state,cancel_request_id IS NOT NULL AS cancellation_requested,lease_claim_id IS NOT NULL AS leased FROM agent_runtime_executions WHERE tenant_id=? AND session_id IN (SELECT session_id FROM nw_workflow_test_sessions WHERE tenant_id=CAST(? AS bigint))", tenantId, tenantId));
            Files.writeString(evidence.resolve("workflow-close-state.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(closeEvidence), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), "Chat process browser E2E failed; evidence=" + evidence
                    + "\n" + tail);
            if ("1".equals(System.getenv("NOEWISE_TEST_CANCEL_RACE"))) {
                assertEquals(1, closeEvidence.get("tests").size());
                assertEquals("closed", closeEvidence.get("tests").getFirst().get("status"));
                assertEquals("CANCELLED", closeEvidence.get("runs").getFirst().get("status"));
                assertEquals("FINISHED", closeEvidence.get("runs").getFirst().get("phase"));
                assertEquals(false, closeEvidence.get("runs").getFirst().get("leased"));
                assertEquals("SUCCEEDED", closeEvidence.get("attempts").getFirst().get("result_kind"));
                assertEquals(false, closeEvidence.get("attempts").getFirst().get("adopted"));
                assertEquals("CANCELLED", closeEvidence.get("workflows").getFirst().get("state"));
                assertEquals(false, closeEvidence.get("workflows").getFirst().get("leased"));
            }
            var receipt = objectMapper.readTree(Files.readString(browserEvidence.resolve("browser-receipt.json"), StandardCharsets.UTF_8));
            assertExecutionPersistence(receipt.path("sessionId").asText(), receipt.path("runId").asText(),
                    receipt.path("inputId").asText(), expectedReply, evidence, live);
        } finally {
            terminateOwnedProcesses(owned);
        }
    }

    private void createWebhookOnlyMember() {
        String tenant = fixtureTenantId();
        com.skillforge.common.context.TenantContext.setTenantId(tenant);
        try {
            var member = new com.skillforge.core.foundation.persistence.TenantMemberDO();
            member.setId(com.skillforge.core.shared.config.SnowflakeIds.nextId());
            member.setTenantId(Long.parseLong(tenant)); member.setUsername("webhook-only");
            member.setRole("member"); member.setPermissions(List.of()); member.setStatus("active");
            member.setPasswordHash(webhookPasswords.encode(PASSWORD)); member.setCreatedAt(java.time.Instant.now());
            webhookMembers.insert(member);
            jdbc.update("INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name) VALUES(?,?,?)", tenant, member.getId(), "Webhook 测试成员");
            long role = com.skillforge.core.shared.config.SnowflakeIds.nextId();
            jdbc.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(?,?,?,'Webhook 测试角色','all')", role, tenant, "webhook_" + role);
            for (String permission : List.of("foundation.webhooks.read", "foundation.webhooks.create"))
                jdbc.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,?,?)", tenant, role, permission);
            jdbc.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,?,?)", tenant, member.getId(), role);
        } finally { com.skillforge.common.context.TenantContext.clear(); }
    }

    /** 核对浏览器受理身份对应的新执行链；独立保存真实模型用量证据。 */
    private void assertExecutionPersistence(String session, String run, String input, String expected,
            Path evidence, boolean live) throws Exception {
        assertTrue(!session.isBlank() && !run.isBlank() && !input.isBlank());
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? "
                + "AND session_id=? AND agent_id=? AND end_user_id=? AND input_id=? AND status='COMPLETED' AND phase='FINISHED'",
                tenantId, run, session, agentId, endUserId, input));
        if (httpErrorModel != null) {
            var tools = queryRows(jdbc, tenantId, "SELECT a.action_id,a.status,t.result_kind,t.adopted,res.canonical_json AS result FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='TOOL'", tenantId, run);
            Files.writeString(evidence.resolve("http-tool-results.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tools));
            assertEquals(1, tools.size()); assertEquals("SUCCEEDED", tools.getFirst().get("result_kind"));
            assertEquals(1, httpErrorModel.httpCalls.get()); assertEquals(2, httpErrorModel.calls.get());
            assertTrue(httpErrorModel.verifiedHttpResult.get());
            var material = objectMapper.readTree(tools.getFirst().get("result").toString());
            assertEquals("KNOWN_RESULT", material.path("resolution").asText());
            var outcome = objectMapper.readTree(material.path("outcome").path("canonicalJson").asText());
            var observation = objectMapper.readTree(outcome.path("observation").path("canonicalJson").asText());
            var response = observation.path("structuredContent");
            assertEquals(500, response.path("status").asInt());
            assertEquals("fixture-http-500", response.path("body").asText());
            assertEquals("baseline".equals(System.getenv("E2E_HTTP_STATUS")), response.path("success").asBoolean());
            assertEquals(true, tools.getFirst().get("adopted"));
            Files.writeString(evidence.resolve("http-runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runId",run,"httpCalls",httpErrorModel.httpCalls.get(),"modelCalls",httpErrorModel.calls.get(),"resultReturnedToModel",httpErrorModel.verifiedHttpResult.get(),"httpResult",response,"modelToolDescription",httpErrorModel.toolDescription)));
            return;
        }
        long models = countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? "
                + "AND kind='MODEL' AND status='SUCCEEDED'", tenantId, run);
        assertEquals(1, models, "必须只有一次成功模型动作");
        assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? "
                + "AND kind<>'MODEL'", tenantId, run), "纯回复不得执行其他能力");
        var attempts = queryRows(jdbc, tenantId, "SELECT t.attempt_id,t.result_kind,t.adopted,b.state,b.category "
                + "FROM agent_runtime_execution_attempts t JOIN agent_runtime_execution_actions a "
                + "ON a.tenant_id=t.tenant_id AND a.action_id=t.action_id AND a.run_id=t.run_id "
                + "JOIN agent_runtime_budget_attempts b ON b.tenant_id=t.tenant_id AND b.attempt_id=t.attempt_id "
                + "WHERE t.tenant_id=? AND t.run_id=? AND a.kind='MODEL'", tenantId, run);
        assertEquals(1, attempts.size(), "刷新和恢复不得制造第二次模型尝试");
        assertEquals("SUCCEEDED", attempts.getFirst().get("result_kind"));
        assertEquals(true, attempts.getFirst().get("adopted"));
        assertEquals("SETTLED", attempts.getFirst().get("state"));
        assertEquals("BUSINESS_MODEL", attempts.getFirst().get("category"));
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_events "
                + "WHERE tenant_id=? AND run_id=? AND type='execution.completed' AND canonical_json LIKE ?",
                tenantId, run, "%" + expected + "%"));
        Files.writeString(evidence.resolve("runtime-evidence.json"), objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("sessionId", session, "runId", run, "inputId", input,
                        "runs", queryRows(jdbc, tenantId, "SELECT run_id,input_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, run),
                        "modelActions", models, "attempts", attempts, "liveProvider", live)), StandardCharsets.UTF_8);
        if (!live) return;
        assertEquals(0, provider.requestCount(), "真实模型验收不得调用本地模型夹具");
        var usage = queryRows(jdbc, tenantId, "SELECT provider,model,usage_source,prompt_tokens,completion_tokens "
                + "FROM nw_usage_events WHERE tenant_id=CAST(? AS bigint) AND run_id=? "
                + "AND event_type='llm.chat' AND usage_event_kind='measured'", tenantId, run);
        assertEquals(1, usage.size(), "真实供应商用量必须持久化且不重复计量");
        var measurement = usage.getFirst();
        assertEquals("deepseek", measurement.get("provider"));
        assertEquals("deepseek-flash", measurement.get("model"));
        assertEquals("PROVIDER_REPORTED", measurement.get("usage_source"));
        assertTrue(((Number) measurement.get("prompt_tokens")).longValue() > 0);
        assertTrue(((Number) measurement.get("completion_tokens")).longValue() > 0);
        Files.writeString(evidence.resolve("live-backend-evidence.json"), objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("tenantId", tenantId, "sessionId", session, "runId", run,
                        "inputId", input, "runStatus", "COMPLETED", "modelActions", models,
                        "attempts", attempts, "deterministicProviderRequests", provider.requestCount(),
                        "usage", usage, "publicReplyPersisted", true)), StandardCharsets.UTF_8);
    }

    /** 持续保留本次创建的子树句柄，父进程退出后仍能清理其 Vite/浏览器后代。 */
    private static void captureProcessTree(Set<ProcessHandle> owned) {
        for (var handle : List.copyOf(owned)) handle.descendants().forEach(owned::add);
    }

    private static void terminateOwnedProcesses(Set<ProcessHandle> owned) {
        captureProcessTree(owned);
        for (var handle : List.copyOf(owned).reversed()) if (handle.isAlive()) handle.destroy();
        awaitOwnedExit(owned, 5);
        for (var handle : List.copyOf(owned).reversed()) if (handle.isAlive()) handle.destroyForcibly();
        awaitOwnedExit(owned, 10);
        assertTrue(owned.stream().noneMatch(ProcessHandle::isAlive), "本次 Playwright/Vite 子树必须完全结束");
    }

    private static void awaitOwnedExit(Set<ProcessHandle> owned, int seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (owned.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            captureProcessTree(owned);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
    }

    private Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("frontend-admin/package.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private void prepareHttpError() throws Exception {
        login();
        var revised=send("/api/v1/agents/revise","POST",Map.of("agent_id",agentId),accessToken);
        assertEquals(200,revised.statusCode(),revised.body());
        agentId=successData(revised).path("id").asText();
        var definitions=successData(send("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED","GET",null,accessToken));
        String definition="",version="";
        for(var item:definitions) if("tool:http_request".equals(item.path("capability_key").asText())) {definition=item.path("id").asText();version=item.path("default_version_id").asText();}
        assertTrue(!definition.isBlank()&&!version.isBlank());
        String key="http-status-"+fixtureRunId();
        var deployment=send("/api/v1/capabilities/deployments","POST",Map.of("deployment_key",key,"tool_version_id",version,"provider_type","PLATFORM","provider_ref","http_request","environment","DEVELOPMENT","status","ACTIVE"),accessToken);
        assertEquals(201,deployment.statusCode(),deployment.body());
        long revision=successData(deployment).path("revision").asLong();
        var mount=send("/api/v1/agents/capability-mounts","POST",Map.of("agent_id",agentId,"payload",Map.of("capability_type","TOOL","capability_definition_id",definition,"capability_version_id",version,"priority",0,"tool_config",Map.of("tool_deployment_id",key,"tool_deployment_revision",revision))),accessToken);
        assertEquals(201,mount.statusCode(),mount.body());
        assertEquals(200,send("/api/v1/agents/publish","POST",Map.of("agent_id",agentId),accessToken).statusCode());
        httpErrorModel=new HttpErrorModel(objectMapper); httpErrorModel.start();
        com.skillforge.common.context.TenantContext.setTenantId(tenantId);
        try {configureTenantModel(httpErrorModel.endpoint());} finally {com.skillforge.common.context.TenantContext.clear();}
    }

    /** 单次工具决策，收到真实 HTTP 结果后再给出一次公开回答。 */
    private final class HttpErrorModel implements AutoCloseable {
        final com.fasterxml.jackson.databind.ObjectMapper json;
        final com.sun.net.httpserver.HttpServer server;
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger httpCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean verifiedHttpResult = new java.util.concurrent.atomic.AtomicBoolean();
        volatile String toolDescription;

        HttpErrorModel(com.fasterxml.jackson.databind.ObjectMapper json) throws Exception {
            this.json = json;
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", this::respond);
            server.createContext("/http-error", exchange -> {
                httpCalls.incrementAndGet();
                byte[] body = "fixture-http-500".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(500, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
        }

        void start() { server.start(); }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        void respond(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
            var request = json.readTree(exchange.getRequestBody());
            int ordinal = calls.incrementAndGet();
            if (ordinal > 2) throw new java.io.IOException("unexpected_model_call");
            if (ordinal == 2) {
                for (var input : request.path("messages")) {
                    if ("tool".equals(input.path("role").asText())
                            && input.path("content").toString().contains("500")
                            && input.path("content").toString().contains("fixture-http-500"))
                        verifiedHttpResult.set(true);
                }
                if (!verifiedHttpResult.get()) throw new java.io.IOException("http_result_not_returned_to_model");
            }
            String function = null;
            for (var tool : request.path("tools")) {
                if (tool.path("function").path("parameters").path("properties").has("url")) {
                    function = tool.path("function").path("name").asText();
                    if (ordinal == 1) {
                        toolDescription = tool.path("function").path("description").asText();
                        if ("candidate".equals(System.getenv("E2E_HTTP_STATUS"))
                                && (!toolDescription.contains("2xx") || !toolDescription.contains("重试")))
                            throw new java.io.IOException("http_result_contract_not_visible_to_model");
                    }
                }
            }
            if (function == null) throw new java.io.IOException("http_tool_missing");
            String arguments = json.writeValueAsString(Map.of("method", "GET", "url", endpoint() + "/http-error"));
            var invocation = Map.of("name", function, "arguments", arguments);
            Object message = ordinal == 1
                    ? Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                            "id", "http-call", "type", "function", "function", invocation)))
                    : Map.of("role", "assistant", "content",
                            "fixture-answer-" + fixtureRunId() + " HTTP status 500; fixture-http-500");
            String finish = ordinal == 1 ? "tool_calls" : "stop";
            String model = request.path("model").asText();
            var usage = Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18);
            String body, type;
            if (!request.path("stream").asBoolean()) {
                type = "application/json";
                body = json.writeValueAsString(Map.of("id", "http-model", "object", "chat.completion",
                        "model", model, "choices", List.of(Map.of(
                                "index", 0, "message", message, "finish_reason", finish)), "usage", usage));
            } else {
                type = "text/event-stream";
                Object delta = ordinal == 1
                        ? Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                                "index", 0, "id", "http-call", "type", "function", "function", invocation)))
                        : message;
                String first = json.writeValueAsString(Map.of("id", "http-model", "object", "chat.completion.chunk",
                        "model", model, "choices", List.of(Map.of("index", 0, "delta", delta))));
                String last = json.writeValueAsString(Map.of("id", "http-model", "object", "chat.completion.chunk",
                        "model", model, "choices", List.of(Map.of(
                                "index", 0, "delta", Map.of(), "finish_reason", finish)), "usage", usage));
                body = "data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        }

        @Override public void close() { server.stop(0); }
    }
}
