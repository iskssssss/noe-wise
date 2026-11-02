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
    private com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot originalRate;
    private long fixtureRateRevision;
    private long fixtureRateActor;

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
        if (fixtureRateRevision != 0) {
            operationalSettings.update("rate_limits", fixtureRateRevision, originalRate.values(), fixtureRateActor);
            assertEquals(originalRate.values(), operationalSettings.read("rate_limits").values());
        }
    }

    @DynamicPropertySource
    static void allowIsolatedBrowserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
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
            assertEquals(0, process.exitValue(), "Chat process browser E2E failed; evidence=" + evidence
                    + "\n" + tail);
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

}
