package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 目录暂时读取失败不应抹去已保存会话；恢复后继续同一上下文。 */
class ChatSessionRestoreBrowserE2ETest extends ChatExecutionChainE2ETest {
    @Test void savedSessionRestoresWhenDirectoryRefreshFails() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_CHAT_SESSION_RESTORE", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        String token = "session-restore-" + UUID.randomUUID();
        String clientRef = UUID.randomUUID().toString();
        long channel;
        TenantContext.setTenantId(tenantId);
        try { channel = createNativeBrowserChannel(token, null, null); }
        finally { TenantContext.clear(); }
        var profileResponse = httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/client-profile"))
                .timeout(Duration.ofSeconds(15)).header("X-Channel-Token", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("client_ref", clientRef, "display_name", "会话恢复验收")))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, profileResponse.statusCode(), "隔离访客登记失败");
        var profile = objectMapper.readTree(profileResponse.body()).path("data");
        String identity = profile.path("identity_token").asText();
        assertFalse(identity.isBlank()); assertFalse(profile.path("end_user_id").asText().isBlank());
        Path evidence = Path.of("build/runtime-e2e/session-restore-" + runId).toAbsolutePath();
        Files.createDirectories(evidence);
        System.out.println("SESSION_RESTORE_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "Real isolated Spring/PostgreSQL/Redis, channel/profile APIs, browser session creation and two inputs, local controlled HTTP model/Runtime. Reload second session-directory response is fetched from the real backend (200) then aborted once. Other history/API responses are real. No external provider, trace, video or credential capture.");
        String firstMessage = "会话恢复首轮 " + runId;
        String secondMessage = "请继续上一轮，并保留原会话中的上下文。";
        var fixture = new java.util.LinkedHashMap<String, Object>();
        fixture.put("backend", baseUrl()); fixture.put("token", token); fixture.put("identity", identity);
        fixture.put("clientRef", clientRef); fixture.put("channel", Long.toString(channel));
        fixture.put("reply", "fixture-answer-" + runId); fixture.put("evidence", evidence.toString());
        fixture.put("firstMessage", firstMessage); fixture.put("secondMessage", secondMessage); fixture.put("mode", mode);
        Path root = KnowledgeBrowserServer.repoRoot();
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), root.resolve("frontend-chat/e2e/session-restore-browser.mjs").toString());
        command.directory(root.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("browser.log").toFile());
        command.environment().put("SESSION_RESTORE_FIXTURE", objectMapper.writeValueAsString(fixture));
        command.environment().remove("SDK4_LIVE_MODEL_KEY"); command.environment().remove("E2E_LIVE_MODEL");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "会话恢复浏览器超时：" + evidence);
            assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("browser.log")));
        } finally {
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,input_id,agent_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? ORDER BY created_second LIMIT 10", tenantId),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? ORDER BY a.run_id,a.action_ordinal LIMIT 10", tenantId),
                    "budgets", queryRows(jdbc, tenantId, "SELECT run_id,state,category FROM agent_runtime_budget_attempts WHERE tenant_id=? LIMIT 10", tenantId),
                    "providerCalls", provider.requestCount(), "providerKinds", provider.responseKinds())));
        }
        var result = objectMapper.readTree(Files.readString(evidence.resolve("result.json")));
        String restoredSession = result.path("session").asText();
        String secondRun = result.path("secondRun").asText();
        assertFalse(restoredSession.isBlank()); assertFalse(secondRun.isBlank());
        assertEquals(2, result.path("submissions").asInt()); assertEquals(1, result.path("sessionCreations").asInt());
        assertEquals(1, result.path("injections").asInt()); assertEquals(2, provider.requestCount());
        assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=?", tenantId));
        assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, restoredSession));
        assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId));
        assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND adopted AND result_kind='SUCCEEDED'", tenantId));
        assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_budget_attempts WHERE tenant_id=? AND state<>'SETTLED'", tenantId));
        var material = queryRows(jdbc, tenantId, "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL'", tenantId, secondRun);
        assertEquals(1, material.size());
        var properties = applicationContext.getBean(AgentRuntimeProperties.class);
        var codec = new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
        var row = material.getFirst();
        var request = ModelRequestTestMaterials.decode(codec, new TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString()), jdbc, tenantId);
        Files.writeString(evidence.resolve("second-model-messages.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request.request().messages()));
        assertTrue(request.request().messages().stream().anyMatch(message -> message.role() == ModelRole.USER && message.textContent().contains(firstMessage)), "第二轮实际模型输入必须包含首轮用户消息");
        assertTrue(request.request().messages().stream().anyMatch(message -> message.role() == ModelRole.ASSISTANT && message.textContent().contains("fixture-answer-" + runId)), "第二轮实际模型输入必须包含首轮助手回复");
        assertTrue(request.request().messages().stream().anyMatch(message -> message.role() == ModelRole.USER && message.textContent().contains(secondMessage)));
    }
}
