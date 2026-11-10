package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.shared.config.SnowflakeIds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 浏览器停用与重新发布同一智能体，核对真实执行拒绝及历史保留。 */
class AgentLifecycleBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("agent-lifecycle-");

    @DynamicPropertySource
    static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void disabledAgentCanBeRepublishedAndRunFromBrowser() throws Exception {
        login();
        boolean baseline = "baseline".equals(System.getenv("E2E_AGENT_LIFECYCLE"));
        Path evidence = Path.of("build/runtime-e2e/agent-lifecycle-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("playwright");
        Files.createDirectories(browser);
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        Path playwright = admin.resolve("node_modules/.bin").resolve(
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
        assertTrue(Files.isRegularFile(playwright));
        FRONTEND.bindBackend(baseUrl());
        var command = new ProcessBuilder(playwright.toString(), "test", "e2e/agent-lifecycle-real.spec.ts");
        command.directory(admin.toFile());
        command.redirectErrorStream(true);
        command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment();
        env.put("E2E_BASE_URL", FRONTEND.baseUrl());
        env.put("E2E_ARTIFACT_DIR", browser.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug());
        env.put("E2E_USERNAME", "runtime-e2e-admin");
        env.put("E2E_PASSWORD", PASSWORD);
        env.put("E2E_AGENT_ID", agentId);
        env.put("E2E_EXPECTED_REPLY", "fixture-answer-" + runId);
        env.put("E2E_AGENT_LIFECYCLE", baseline ? "baseline" : "candidate");
        env.remove("SDK4_LIVE_MODEL_KEY");
        env.remove("E2E_LIVE_MODEL");
        Process process = command.start();
        boolean disabledVerified = false;
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            while (process.isAlive() && System.nanoTime() < deadline) {
                owned.capture();
                if (!disabledVerified && Files.exists(browser.resolve("disabled-rejection.json"))) {
                    assertEquals(1, provider.requestCount(), "停用后拒绝输入不得调用模型");
                    assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=?", tenantId, agentId));
                    assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, agentId));
                    Files.writeString(browser.resolve("disabled-verified"), "runs=1;modelCalls=1");
                    disabledVerified = true;
                }
                process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            owned.capture();
            assertFalse(process.isAlive(), "生命周期浏览器验收超时：" + evidence);
            assertEquals(0, process.exitValue(), "生命周期浏览器验收失败：" + evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
        } finally {
            var state = Map.of(
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=? ORDER BY created_second", tenantId, agentId),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.kind,a.status FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id WHERE r.tenant_id=? AND r.agent_id=? ORDER BY a.run_id,a.action_ordinal", tenantId, agentId),
                    "agent", queryRows(jdbc, tenantId, "SELECT id,status,published_revision_id,revision,has_draft_changes FROM nw_agents WHERE tenant_id=CAST(? AS bigint) AND id=CAST(? AS bigint)", tenantId, agentId),
                    "publications", queryRows(jdbc, tenantId, "SELECT id,revision,request_id FROM nw_agent_publications WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint) ORDER BY revision", tenantId, agentId),
                    "modelCalls", provider.requestCount());
            Files.writeString(evidence.resolve("lifecycle-runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
        }
        assertTrue(disabledVerified);
        var facts = objectMapper.readTree(Files.readString(browser.resolve("agent-lifecycle.json")));
        int expectedRuns = baseline ? 1 : 2;
        assertEquals(expectedRuns, provider.requestCount());
        assertEquals(expectedRuns, facts.path("runs").size());
        assertEquals(expectedRuns, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=?", tenantId, agentId));
        for (var run : facts.path("runs")) {
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, agentId, run.asText()));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, run.asText()));
        }
        assertEquals(expectedRuns, countRuntime("SELECT count(*) FROM nw_agent_publications WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint)", tenantId, agentId));
        assertEquals(1, countRuntime("SELECT count(*) FROM nw_agents WHERE tenant_id=CAST(? AS bigint) AND id=CAST(? AS bigint) AND status=? AND NOT has_draft_changes", tenantId, agentId, baseline ? "disabled" : "published"));
    }
}
