package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 原取消未到达时只读核验并显式继续同一运行的取消。 */
class ChatCancelRetryBrowserE2ETest extends ChatExecutionChainE2ETest {
    @Autowired private ExecutionBudgetPort budgets;

    @Test void cancelNotArrivedCanBeExplicitlyContinued() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_CHAT_CANCEL_RETRY", "candidate");
        assertTrue(java.util.Set.of("baseline", "candidate").contains(mode));
        provider.holdStreamCompletion();
        String channelToken = "cancel-not-arrived-" + UUID.randomUUID(), clientRef = UUID.randomUUID().toString();
        TenantContext.setTenantId(tenantId);
        long channel;
        try { channel = createNativeBrowserChannel(channelToken, null, null); }
        finally { TenantContext.clear(); }
        var profileRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/client-profile"))
                .header("X-Channel-Token", channelToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("client_ref", clientRef, "display_name", "取消验收")))).build();
        var profileResponse = httpClient.send(profileRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, profileResponse.statusCode());
        var profile = objectMapper.readTree(profileResponse.body()).path("data");
        String user = profile.path("end_user_id").asText(), identity = profile.path("identity_token").asText();
        assertFalse(user.isBlank()); assertFalse(identity.isBlank());
        var sessionRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/sessions"))
                .header("X-Channel-Token", channelToken).header("X-Chat-User-Token", identity).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("agent_id", agentId, "end_user_id", user)))).build();
        var created = httpClient.send(sessionRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode());
        String session = objectMapper.readTree(created.body()).path("data").path("session_id").asText();
        assertFalse(session.isBlank());
        Path root = KnowledgeBrowserServer.repoRoot();
        Path evidence = Path.of("build", "runtime-e2e", "cancel-not-arrived-" + runId).toAbsolutePath();
        Files.createDirectories(evidence);
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), root.resolve("frontend-chat/e2e/cancel-retry-browser.mjs").toString());
        command.directory(root.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("browser.log").toFile());
        command.environment().put("CANCEL_RETRY_FIXTURE", objectMapper.writeValueAsString(Map.of(
                "backend", baseUrl(), "token", channelToken, "identity", identity, "clientRef", clientRef,
                "channel", Long.toString(channel), "session", session, "agent", agentId, "evidence", evidence.toString(), "mode", mode)));
        Files.writeString(evidence.resolve("boundary.txt"), "隔离服务、PostgreSQL、浏览器和真实 Runtime；本地受控模型首段保持。首次取消由浏览器在派发前切断；候选由用户显式继续取消同一 Run。基线采证后释放模型仅作夹具清理。凭据和 trace 不落盘。与外部供应商无关。", java.nio.charset.StandardCharsets.UTF_8);
        Process process = command.start(); boolean capturedBefore = false;
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) {
                if (!capturedBefore && Files.exists(evidence.resolve("inspect-before"))) {
                    assertEquals(1, provider.heldStreamStage(), "原模型首段已发出且仍在门控");
                    String run = Files.readString(evidence.resolve("run-id.txt")).trim();
                    var rows = queryRows(jdbc, tenantId, "SELECT run_id,session_id,status,phase,cancelled FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, run);
                    assertEquals(1, rows.size()); assertEquals(session, rows.getFirst().get("session_id"));
                    assertEquals(false, rows.getFirst().get("cancelled"));
                    var budget = TenantContext.callWithTenant(tenantId, () -> budgets.snapshot(new RunRef(new TenantId(tenantId), run)));
                    assertTrue(budget.runReserved().totalTokens() > 0);
                    Files.writeString(evidence.resolve("before-runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "runs", rows, "providerCalls", provider.requestCount(), "reservedTokens", budget.runReserved().totalTokens())));
                    assertEquals(1, provider.requestCount());
                    Files.writeString(evidence.resolve("before-ready"), "ready"); capturedBefore = true;
                }
                owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            owned.capture(); assertFalse(process.isAlive(), "cancel_retry_browser_timeout: " + evidence);
            assertEquals(0, process.exitValue(), "cancel_retry_browser_failed: " + evidence + "\n" + Files.readString(evidence.resolve("browser.log")));
        }
        assertTrue(capturedBefore);
        var receipt = objectMapper.readTree(Files.readString(evidence.resolve("receipt.json")));
        String actualRun = receipt.path("runId").asText(); assertFalse(actualRun.isBlank());
        if ("candidate".equals(mode)) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='CANCELLED' AND phase='FINISHED'", tenantId, actualRun) == 0 && System.nanoTime() < deadline) Thread.sleep(100);
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='CANCELLED' AND phase='FINISHED' AND cancelled", tenantId, actualRun));
        }
        var runs = queryRows(jdbc, tenantId, "SELECT run_id,session_id,status,phase,cancelled FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=?", tenantId, session);
        var attempts = queryRows(jdbc, tenantId, "SELECT a.kind,t.result_kind,t.adopted FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id WHERE a.tenant_id=? AND a.run_id=? ORDER BY a.action_ordinal,t.observation_sequence", tenantId, actualRun);
        var budget = TenantContext.callWithTenant(tenantId, () -> budgets.snapshot(new RunRef(new TenantId(tenantId), actualRun)));
        Files.writeString(evidence.resolve("runtime-facts.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "session", session, "run", actualRun, "runs", runs, "attempts", attempts,
                "providerCalls", provider.requestCount(), "reservedTokens", budget.runReserved().totalTokens())));
        assertEquals(1, runs.size()); assertEquals(1, provider.requestCount());
        if ("baseline".equals(mode)) provider.abortHeldStream();
    }
}
