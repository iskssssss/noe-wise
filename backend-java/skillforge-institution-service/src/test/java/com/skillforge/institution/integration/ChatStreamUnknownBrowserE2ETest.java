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

/** 真实公开预览后截断供应商流，保留原调用未知事实与浏览器观察。 */
class ChatStreamUnknownBrowserE2ETest extends ChatExecutionChainE2ETest {
    @Autowired private ExecutionBudgetPort budgets;

    @Test void incompleteStreamPreservesUnknownAndClearsPreview() throws Exception {
        provider.holdStreamCompletion();
        String channelToken = "stream-unknown-" + UUID.randomUUID(), clientRef = UUID.randomUUID().toString();
        TenantContext.setTenantId(tenantId);
        long channel;
        try { channel = createNativeBrowserChannel(channelToken, null, null); }
        finally { TenantContext.clear(); }
        var profileRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/client-profile"))
                .header("X-Channel-Token", channelToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("client_ref", clientRef, "display_name", "断流验收")))).build();
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
        Path evidence = Path.of("build", "runtime-e2e", "stream-unknown-" + runId).toAbsolutePath();
        Files.createDirectories(evidence);
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), root.resolve("frontend-chat/e2e/stream-unknown-browser.mjs").toString());
        command.directory(root.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("browser.log").toFile());
        command.environment().put("STREAM_UNKNOWN_FIXTURE", objectMapper.writeValueAsString(Map.of(
                "backend", baseUrl(), "token", channelToken, "identity", identity, "clientRef", clientRef,
                "channel", Long.toString(channel), "session", session, "agent", agentId, "evidence", evidence.toString())));
        Process process = command.start(); boolean aborted = false;
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) {
                if (!aborted && Files.exists(evidence.resolve("abort-model"))) {
                    assertEquals(1, provider.heldStreamStage(), "浏览器预览必须来自已 flush 的真实首段");
                    provider.abortHeldStream(); aborted = true;
                }
                owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            owned.capture(); assertFalse(process.isAlive(), "stream_unknown_browser_timeout: " + evidence);
            assertEquals(0, process.exitValue(), "stream_unknown_browser_failed: " + evidence + "\n" + Files.readString(evidence.resolve("browser.log")));
        }
        assertTrue(aborted);
        var receipt = objectMapper.readTree(Files.readString(evidence.resolve("receipt.json")));
        String actualRun = receipt.path("runId").asText(); assertFalse(actualRun.isBlank());
        long settleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND phase='UNCERTAIN'", tenantId, actualRun) == 0
                && System.nanoTime() < settleDeadline) Thread.sleep(100);
        var runs = queryRows(jdbc, tenantId, "SELECT run_id,session_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=? ORDER BY created_second,created_nano LIMIT 10", tenantId, session);
        var attempts = queryRows(jdbc, tenantId, "SELECT a.kind,t.result_kind,t.adopted FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id WHERE a.tenant_id=? AND a.run_id=? ORDER BY a.action_ordinal,t.observation_sequence", tenantId, actualRun);
        var budget = TenantContext.callWithTenant(tenantId, () -> budgets.snapshot(new RunRef(new TenantId(tenantId), actualRun)));
        long completed = countRuntime("SELECT count(*) FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'", tenantId, actualRun);
        Files.writeString(evidence.resolve("runtime-facts.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "session", session, "run", actualRun, "runs", runs, "attempts", attempts,
                "providerCalls", provider.requestCount(), "reservedTokens", budget.runReserved().totalTokens(), "completedEvents", completed)));
        assertEquals(1, runs.size()); assertEquals(actualRun, runs.getFirst().get("run_id")); assertEquals("UNCERTAIN", runs.getFirst().get("phase"));
        assertEquals(1, attempts.size()); assertEquals("MODEL", attempts.getFirst().get("kind")); assertEquals("UNKNOWN", attempts.getFirst().get("result_kind"));
        assertTrue(budget.runReserved().totalTokens() > 0); assertEquals(0, completed); assertEquals(1, provider.requestCount());
    }
}
