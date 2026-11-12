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
class ChatAcceptedHistoryBrowserE2ETest extends ChatExecutionChainE2ETest {

    @Test void acceptedInputRemainsObservableWhenHistoryReadFails() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_CHAT_ACCEPTED_HISTORY", "candidate");
        assertTrue(java.util.Set.of("baseline", "candidate").contains(mode));
        provider.holdStreamCompletion();
        String channelToken = "accepted-history-" + UUID.randomUUID(), clientRef = UUID.randomUUID().toString();
        TenantContext.setTenantId(tenantId);
        long channel;
        try { channel = createNativeBrowserChannel(channelToken, null, null); }
        finally { TenantContext.clear(); }
        var profileRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/client-profile"))
                .header("X-Channel-Token", channelToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("client_ref", clientRef, "display_name", "受理历史验收")))).build();
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
        Path evidence = Path.of("build", "runtime-e2e", "accepted-history-" + runId).toAbsolutePath();
        Files.createDirectories(evidence);
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), root.resolve("frontend-chat/e2e/accepted-history-browser.mjs").toString());
        command.directory(root.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("browser.log").toFile());
        command.environment().put("ACCEPTED_HISTORY_FIXTURE", objectMapper.writeValueAsString(Map.of(
                "backend", baseUrl(), "token", channelToken, "identity", identity, "clientRef", clientRef,
                "channel", Long.toString(channel), "session", session, "agent", agentId, "evidence", evidence.toString(), "mode", mode)));
        Files.writeString(evidence.resolve("boundary.txt"), "隔离服务、PostgreSQL、真实浏览器和 Runtime；受控模型在首段后暂停，POST 202 正常交付，后置 history 上游 200 后浏览器中断，随后恢复 SSE 并释放模型。凭据和 trace 不落盘。", java.nio.charset.StandardCharsets.UTF_8);
        Process process = command.start(); boolean released = false;
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) {
                if (!released && Files.exists(evidence.resolve("release-model")) && provider.heldStreamStage() == 1) {
                    provider.advanceHeldStream(); provider.finishHeldStream(); released = true;
                }
                owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            owned.capture(); assertFalse(process.isAlive(), "浏览器超时：" + evidence);
            assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("browser.log")));
        } finally {
            if (!released) provider.abortHeldStream();
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,input_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=?", tenantId, session),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.kind,a.status,t.result_kind,t.adopted FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id WHERE a.tenant_id=? AND a.run_id IN (SELECT run_id FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=?) ORDER BY a.action_ordinal", tenantId, tenantId, session),
                    "providerCalls", provider.requestCount())));
        }
        assertTrue(released);
        var receipt = objectMapper.readTree(Files.readString(evidence.resolve("receipt.json")));
        String run = receipt.path("runId").asText(); assertFalse(run.isBlank());
        assertEquals(1, receipt.path("submissions").asInt());
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, run, session));
        assertEquals(1, provider.requestCount());
    }
}
