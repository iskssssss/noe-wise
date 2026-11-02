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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 以隔离真实浏览器验证智能体试用的公开回复与历史恢复。 */
@Tag("live")
@Tag("live-browser")
@EnabledIfEnvironmentVariable(named = "SDK4_LIVE_MODEL_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_AGENT_TRIAL", matches = "1")
class AgentTrialBrowserE2ETest extends ChatRuntimeChannelE2ETest {

    @org.springframework.beans.factory.annotation.Autowired com.skillforge.core.agent.mapper.AgentMapper agents;
    @org.springframework.beans.factory.annotation.Autowired com.skillforge.core.agent.AgentPublicationService publications;
    @org.springframework.beans.factory.annotation.Autowired com.skillforge.core.foundation.access.FoundationAccess foundation;
    private static KnowledgeBrowserServer FRONTEND;

    @DynamicPropertySource
    static void allowIsolatedBrowserOrigin(DynamicPropertyRegistry registry) {
        FRONTEND = KnowledgeBrowserServer.startIsolated("agent-trial-browser-");
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll
    static void closeIsolatedFrontend() {
        if (FRONTEND != null) FRONTEND.close();
    }

    @Test
    @Tag("live")
    @Tag("live-browser")
    @EnabledIfEnvironmentVariable(named = "SDK4_LIVE_MODEL_KEY", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_AGENT_TRIAL", matches = "1")
    void restrictedAgentTrialSubmitsOnceAndRestoresReply() throws Exception {
        com.skillforge.common.context.TenantContext.setTenantId(fixtureTenantId());
        try {
            String member = jdbc.queryForObject("SELECT id::text FROM nw_tenant_members WHERE tenant_id=? AND username='runtime-e2e-admin'", String.class, Long.parseLong(fixtureTenantId()));
            var subject = new com.skillforge.core.foundation.access.FoundationAccess.Subject(false, fixtureTenantId(), member);
            foundation.withSubject(subject, () -> {
                var agent = agents.selectById(Long.parseLong(fixtureAgentId()));
                agent.setMaxBusinessModelCalls(12); agent.setMaxToolCalls(8); agent.setMaxKnowledgeCalls(8);
                agents.updateById(agent); publications.publish(agent); return null;
            });
            var permissions = foundation.snapshot(subject).permissions();
            assertTrue(permissions.contains("foundation.teams.read"));
            long role = com.skillforge.core.shared.config.SnowflakeIds.nextId();
            jdbc.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(?,?,?,'试用隔离角色','all')", role, fixtureTenantId(), "trial_" + role);
            for (String permission : permissions) if (!permission.equals("foundation.teams.read"))
                jdbc.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,?,?)", fixtureTenantId(), role, permission);
            jdbc.update("DELETE FROM nw_foundation_member_roles WHERE scope_key=? AND member_id=?", fixtureTenantId(), Long.parseLong(member));
            jdbc.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,?,?)", fixtureTenantId(), Long.parseLong(member), role);
            jdbc.update("UPDATE nw_foundation_access_scopes SET authorization_version=authorization_version+1 WHERE scope_key=?", fixtureTenantId());
            var restricted = foundation.snapshot(subject).permissions();
            assertTrue(!restricted.contains("foundation.teams.read") && restricted.containsAll(java.util.List.of("foundation.agents.read", "foundation.sessions.read", "foundation.sessions.create", "foundation.sessions.run")), "isolated trial permissions");
        } finally { com.skillforge.common.context.TenantContext.clear(); }
        runBrowser(true);
    }

    private void runBrowser(boolean live) throws Exception {
        Path repoRoot = findRepoRoot();
        Path adminRoot = repoRoot.resolve("frontend-admin");
        Path playwright = adminRoot.resolve("node_modules/.bin")
                .resolve(isWindows() ? "playwright.cmd" : "playwright");
        assertTrue(Files.isRegularFile(playwright),
                "Playwright is not installed; run npm ci and npx playwright install chromium");

        Path evidence = Path.of("build", "runtime-e2e", "agent-trial-browser-" + fixtureRunId())
                .toAbsolutePath();
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("fixture-budget.json"), "{\"model\":\"deepseek-flash\",\"maxBusinessModelCalls\":12,\"maxToolCalls\":8,\"maxKnowledgeCalls\":8,\"modelContextLimit\":32768,\"modelOutputLimit\":1024,\"browserSubmissions\":1}", StandardCharsets.UTF_8);
        Path log = evidence.resolve("playwright-vite.log");
        ProcessBuilder command = new ProcessBuilder(
                playwright.toString(), "test", "e2e/agent-trial-live.spec.ts");
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
        environment.put("E2E_ARTIFACT_DIR", evidence.toString());

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
            Path accepted = evidence.resolve("accepted-receipt.json");
            if (process.exitValue() != 0 && Files.exists(accepted)) {
                var receipt = objectMapper.readTree(Files.readString(accepted, StandardCharsets.UTF_8));
                saveOriginalRunDiagnostic(evidence, receipt.path("sessionId").asText(), receipt.path("runId").asText(), receipt.path("inputId").asText());
            }
            Files.writeString(evidence.resolve("fixture-budget.json"), "{\"model\":\"deepseek-flash\",\"maxBusinessModelCalls\":12,\"maxToolCalls\":8,\"maxKnowledgeCalls\":8,\"modelContextLimit\":32768,\"modelOutputLimit\":1024,\"browserSubmissions\":1}", StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), "Chat process browser E2E failed; evidence=" + evidence
                    + "\n" + tail);
            if (live) {
                var receipt = objectMapper.readTree(Files.readString(evidence.resolve("browser-receipt.json"), StandardCharsets.UTF_8));
                String browserSession = receipt.path("sessionId").asText(), browserRun = receipt.path("runId").asText(), browserInput = receipt.path("inputId").asText();
                assertCurrentExecution(evidence, browserSession, browserRun, browserInput, expectedReply);
                Files.writeString(evidence.resolve("fixture-budget.json"), "{\"model\":\"deepseek-flash\",\"maxBusinessModelCalls\":12,\"maxToolCalls\":8,\"maxKnowledgeCalls\":8,\"modelContextLimit\":32768,\"modelOutputLimit\":1024,\"browserSubmissions\":1}", StandardCharsets.UTF_8);
            }
        } finally {
            terminateOwnedProcesses(owned);
        }
    }

    /** 在短事务绑定租户，按受理身份读取当前 ExecutionStore 与宿主持久事实。 */
    private Map<String, Object> currentExecution(String session, String run, String input) {
        return Map.of(
            "sessionId", session, "runId", run, "inputId", input,
            "runs", queryRows(jdbc, tenantId, "select run_id,session_id,input_id,status,phase from agent_runtime_execution_runs where tenant_id=? and run_id=? limit 1", tenantId, run),
            "actions", queryRows(jdbc, tenantId, "select action_id,kind,target_handle,status from agent_runtime_execution_actions where tenant_id=? and run_id=? order by action_ordinal limit 20", tenantId, run),
            "attempts", queryRows(jdbc, tenantId, "select attempt_id,action_id,result_kind,adopted from agent_runtime_execution_attempts where tenant_id=? and run_id=? limit 20", tenantId, run),
            "usage", queryRows(jdbc, tenantId, "select provider,model,usage_source,usage_event_kind,prompt_tokens,completion_tokens from nw_usage_events where tenant_id=? and run_id=? and event_type='llm.chat' limit 20", Long.parseLong(tenantId), run),
            "outbox", queryRows(jdbc, tenantId, "select event_type,runtime_run_id from nw_chat_session_outbox where tenant_id=? and session_id=? and runtime_run_id=? order by id limit 50", Long.parseLong(tenantId), session, run),
            "messages", queryRows(jdbc, tenantId, "select message_id,role from nw_chat_messages where tenant_id=? and session_id=? and message_id=? limit 1", Long.parseLong(tenantId), session, input),
            "deterministicProviderRequests", provider.requestCount());
    }

    private void saveOriginalRunDiagnostic(Path evidence, String session, String run, String input) throws Exception {
        Files.writeString(evidence.resolve("original-run-diagnostic.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentExecution(session, run, input)), StandardCharsets.UTF_8);
    }

    private void assertCurrentExecution(Path evidence, String session, String run, String input, String reply) throws Exception {
        var facts = currentExecution(session, run, input);
        Files.writeString(evidence.resolve("live-backend-evidence.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts), StandardCharsets.UTF_8);
        var runs = (java.util.List<java.util.Map<String, Object>>) facts.get("runs");
        var actions = (java.util.List<java.util.Map<String, Object>>) facts.get("actions");
        var attempts = (java.util.List<java.util.Map<String, Object>>) facts.get("attempts");
        var usage = (java.util.List<java.util.Map<String, Object>>) facts.get("usage");
        var outbox = (java.util.List<java.util.Map<String, Object>>) facts.get("outbox");
        var messages = (java.util.List<java.util.Map<String, Object>>) facts.get("messages");
        assertEquals(1, runs.size()); assertEquals(session, runs.getFirst().get("session_id")); assertEquals(input, runs.getFirst().get("input_id"));
        assertEquals("COMPLETED", runs.getFirst().get("status")); assertEquals("FINISHED", runs.getFirst().get("phase"));
        assertEquals(1, actions.size()); assertEquals("MODEL", actions.getFirst().get("kind"));
        assertEquals("deepseek-flash", actions.getFirst().get("target_handle")); assertEquals("SUCCEEDED", actions.getFirst().get("status"));
        assertEquals(1, attempts.size()); assertEquals("SUCCEEDED", attempts.getFirst().get("result_kind")); assertEquals(true, attempts.getFirst().get("adopted"));
        assertTrue(usage.stream().anyMatch(row -> "deepseek".equals(row.get("provider")) && "deepseek-flash".equals(row.get("model")) && "measured".equals(row.get("usage_event_kind")) && "PROVIDER_REPORTED".equals(row.get("usage_source"))));
        assertTrue(outbox.stream().anyMatch(row -> "execution.completed".equals(row.get("event_type"))));
        assertEquals(1, messages.size()); assertEquals("user", messages.getFirst().get("role"));
        assertEquals(0, provider.requestCount());
        assertTrue(countRuntime("select count(*) from nw_chat_session_outbox where tenant_id=? and session_id=? and runtime_run_id=? and event_type='message.delta' and payload->'data'->>'content' like ?", Long.parseLong(tenantId), session, run, "%" + reply + "%") >= 1);
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
