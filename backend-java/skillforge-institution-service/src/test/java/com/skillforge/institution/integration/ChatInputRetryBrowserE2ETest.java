package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.common.context.TenantContext;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** 原请求真实受理但回执丢失后，重试拒绝不能改写原请求的未知结果。 */
class ChatInputRetryBrowserE2ETest extends ChatRuntimeChannelE2ETest {
    @Test void rejectedRetryKeepsOriginalInputUntilHistoryConfirmsIt() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_CHAT_INPUT_RETRY", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        String channelToken = "input-retry-" + UUID.randomUUID();
        String clientRef = UUID.randomUUID().toString();
        long channel;
        TenantContext.setTenantId(tenantId);
        try { channel = createNativeBrowserChannel(channelToken, null, null); }
        finally { TenantContext.clear(); }
        var profileRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/client-profile"))
                .header("X-Channel-Token", channelToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("client_ref", clientRef, "display_name", "输入重试验收")))).build();
        var profileResponse = httpClient.send(profileRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, profileResponse.statusCode(), "隔离访客登记失败");
        var profile = objectMapper.readTree(profileResponse.body()).path("data");
        String identity = profile.path("identity_token").asText();
        String user = profile.path("end_user_id").asText();
        assertFalse(identity.isBlank()); assertFalse(user.isBlank());
        var createRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/sessions"))
                .header("X-Channel-Token", channelToken).header("X-Chat-User-Token", identity)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("agent_id", agentId, "end_user_id", user)))).build();
        var created = httpClient.send(createRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode(), created.body());
        String targetSession = objectMapper.readTree(created.body()).path("data").path("session_id").asText();
        assertFalse(targetSession.isBlank());
        Path root = KnowledgeBrowserServer.repoRoot();
        Path evidence = Path.of("build/runtime-e2e/input-retry-" + runId).toAbsolutePath();
        Files.createDirectories(evidence);
        Path rawTrace = Files.createTempFile("input-retry-trace-", ".zip");
        Files.writeString(evidence.resolve("session-created.json"), created.body());
        System.out.println("INPUT_RETRY_EVIDENCE=" + evidence);
        var fixture = new LinkedHashMap<String, Object>();
        fixture.put("backend", baseUrl()); fixture.put("token", channelToken); fixture.put("identity", identity);
        fixture.put("clientRef", clientRef); fixture.put("channel", Long.toString(channel));
        fixture.put("session", targetSession); fixture.put("agent", agentId); fixture.put("adminToken", accessToken);
        fixture.put("reply", "fixture-answer-" + runId); fixture.put("evidence", evidence.toString());
        fixture.put("tracePath", rawTrace.toString()); fixture.put("mode", mode);
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(),
                root.resolve("frontend-chat/e2e/input-retry-browser.mjs").toString());
        command.directory(root.toFile()); command.redirectErrorStream(true);
        Path log = evidence.resolve("browser.log"); command.redirectOutput(log.toFile());
        command.environment().put("INPUT_RETRY_FIXTURE", objectMapper.writeValueAsString(fixture));
        command.environment().remove("SDK4_LIVE_MODEL_KEY");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) {
                owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            owned.capture();
            assertFalse(process.isAlive(), "输入重试浏览器超时：" + evidence);
            assertEquals(0, process.exitValue(), "输入重试浏览器失败：" + evidence + "\n" + Files.readString(log));
        } finally {
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,input_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=? ORDER BY created_second LIMIT 20", tenantId, targetSession),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id WHERE a.tenant_id=? AND r.session_id=? ORDER BY a.action_ordinal LIMIT 50", tenantId, targetSession),
                    "budgets", queryRows(jdbc, tenantId, "SELECT b.run_id,b.state,b.category FROM agent_runtime_budget_attempts b JOIN agent_runtime_execution_runs r ON r.tenant_id=b.tenant_id AND r.run_id=b.run_id WHERE r.tenant_id=? AND r.session_id=? LIMIT 50", tenantId, targetSession),
                    "providerCalls", provider.requestCount(), "providerKinds", provider.responseKinds())));
            // 浏览器 trace 会包含请求头；留存前移除本次隔离凭据，临时原件不进入证据目录。
            try (var input = new ZipInputStream(Files.newInputStream(rawTrace));
                    var output = new ZipOutputStream(Files.newOutputStream(evidence.resolve("browser-trace.zip")))) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    byte[] bytes = input.readAllBytes();
                    if (entry.getName().endsWith(".trace") || entry.getName().endsWith(".network")) {
                        String text = new String(bytes, StandardCharsets.UTF_8);
                        for (String secret : List.of(channelToken, identity, accessToken)) text = text.replace(secret, "[REDACTED]");
                        bytes = text.getBytes(StandardCharsets.UTF_8);
                    }
                    output.putNextEntry(new ZipEntry(entry.getName())); output.write(bytes); output.closeEntry();
                }
            } finally { Files.deleteIfExists(rawTrace); }
            Files.writeString(evidence.resolve("trace-redaction.txt"),
                    "browser-trace.zip 中的 trace/network 凭据已替换为 [REDACTED]；HTTP 业务正文、页面快照与截图保持原记录。临时含凭据 trace 已删除。\n");
        }
        var result = objectMapper.readTree(Files.readString(evidence.resolve("receipt.json")));
        String actualRun = result.path("runId").asText();
        var runs = queryRows(jdbc, tenantId, "SELECT run_id,input_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=?", tenantId, targetSession);
        assertEquals(1, runs.size()); assertEquals(actualRun, runs.getFirst().get("run_id"));
        assertEquals(result.path("inputId").asText(), runs.getFirst().get("input_id"));
        assertEquals("COMPLETED", runs.getFirst().get("status")); assertEquals("FINISHED", runs.getFirst().get("phase"));
        assertEquals(1, provider.requestCount());
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun));
        assertEquals(2, result.path("submissions").asInt());
    }
}
