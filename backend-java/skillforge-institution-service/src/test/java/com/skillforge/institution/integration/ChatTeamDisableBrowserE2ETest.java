package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.common.context.TenantContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.core.channel.mapper.ChannelAgentBindingMapper;
import com.skillforge.core.channel.model.ChannelAgentBindingDO;
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
import org.springframework.beans.factory.annotation.Autowired;

/** 个人团队停用与重新发布后，原会话提交状态及真实运行的窄闭环。 */
class ChatTeamDisableBrowserE2ETest extends ChatRuntimeChannelE2ETest {
    @Autowired private ChannelAgentBindingMapper teamChannelBindings;

    @Test void disabledTeamBlocksExistingConversationUntilRepublished() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_CHAT_TEAM_DISABLE", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        Path evidence = Path.of("build/runtime-e2e/chat-team-disable-" + runId).toAbsolutePath();
        Files.createDirectories(evidence);
        System.out.println("CHAT_TEAM_DISABLE_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "Real isolated channel/profile/team APIs, Spring/PostgreSQL/Redis/Vite/browser and Runtime. NativeChatOpenAiProvider is a local controlled HTTP model. Model request/output originals are the persisted Runtime materials, provider HTTP request bodies are not recorded. Trace disabled; profile identity token and channel credentials excluded from evidence. Disabled history/status HTTP results are recorded independently without claiming readability.");
        String channelToken = "team-disable-" + UUID.randomUUID(), clientRef = UUID.randomUUID().toString();
        long channel;
        TenantContext.setTenantId(tenantId);
        try { channel = createNativeBrowserChannel(channelToken, null, null); }
        finally { TenantContext.clear(); }
        var profileRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/client-profile"))
                .timeout(Duration.ofSeconds(20)).header("X-Channel-Token", channelToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("client_ref", clientRef, "display_name", "团队停用验收")))).build();
        var profileResponse = httpClient.send(profileRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, profileResponse.statusCode(), "隔离访客登记失败");
        var profile = objectMapper.readTree(profileResponse.body()).path("data");
        String identity = profile.path("identity_token").asText(), user = profile.path("end_user_id").asText();
        assertFalse(identity.isBlank()); assertFalse(user.isBlank());
        Files.writeString(evidence.resolve("profile-identifiers.json"), objectMapper.writeValueAsString(Map.of("channel", Long.toString(channel), "clientRef", clientRef, "endUserId", user)));
        String teamName = "原会话停用验收团队";
        var teamBody = Map.of("client_ref", clientRef, "revision", 0, "name", teamName, "description", "", "primary_agent_id", agentId, "members", List.of(Map.of("agent_id", agentId)));
        var create = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/teams"))
                .timeout(Duration.ofSeconds(20)).header("X-Channel-Token", channelToken).header("X-Chat-User-Token", identity)
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(teamBody))).build();
        var created = httpClient.send(create, HttpResponse.BodyHandlers.ofString());
        Files.writeString(evidence.resolve("team-created.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("request", teamBody, "status", created.statusCode(), "body", objectMapper.readTree(created.body()))));
        assertEquals(200, created.statusCode(), created.body());
        var team = objectMapper.readTree(created.body()).path("data");
        assertEquals("published", team.path("status").asText());
        assertEquals(agentId, team.path("primary_agent_id").asText()); assertEquals(1, team.path("members").size());
        String teamId = team.path("id").asText(); assertFalse(teamId.isBlank());
        Path root = KnowledgeBrowserServer.repoRoot();
        var fixture = Map.of("backend", baseUrl(), "token", channelToken, "identity", identity, "clientRef", clientRef,
                "channel", Long.toString(channel), "team", teamId, "teamName", teamName, "reply", "fixture-answer-" + runId,
                "evidence", evidence.toString(), "mode", mode);
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), root.resolve("frontend-chat/e2e/team-disable-browser.mjs").toString());
        command.directory(root.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("browser.log").toFile());
        command.environment().put("TEAM_DISABLE_FIXTURE", objectMapper.writeValueAsString(fixture));
        command.environment().remove("SDK4_LIVE_MODEL_KEY"); command.environment().remove("E2E_LIVE_MODEL");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "团队停用浏览器超时：" + evidence);
            assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("browser.log")));
        } finally {
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,input_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=? ORDER BY created_second LIMIT 10", tenantId, agentId),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND r.agent_id=? ORDER BY a.run_id,a.action_ordinal LIMIT 20", tenantId, agentId),
                    "budgets", queryRows(jdbc, tenantId, "SELECT b.run_id,b.state,b.category FROM agent_runtime_budget_attempts b JOIN agent_runtime_execution_runs r ON r.tenant_id=b.tenant_id AND r.run_id=b.run_id WHERE r.tenant_id=? AND r.agent_id=? LIMIT 20", tenantId, agentId),
                    "providerCalls", provider.requestCount(), "providerKinds", provider.responseKinds())));
        }
        var result = objectMapper.readTree(Files.readString(evidence.resolve("receipt.json")));
        String session = result.path("session").asText(); assertFalse(session.isBlank());
        assertEquals(teamId, result.path("team").asText());
        assertEquals("baseline".equals(mode) ? 3 : 2, result.path("submissions").asInt());
        assertEquals(2, provider.requestCount());
        assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=?", tenantId, agentId));
        assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, session));
        assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id WHERE a.tenant_id=? AND r.session_id=? AND a.kind='MODEL' AND a.status='SUCCEEDED'", tenantId, session));
        for (String name : List.of("first", "second")) {
            String actualRun = result.path(name).path("body").path("data").path("execution_id").asText();
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND session_id=? AND status='COMPLETED'", tenantId, actualRun, session));
        }
        if ("candidate".equals(mode)) {
            // 浏览器闭环结束后，在隔离团队上核对历史读取的当前授权边界。
            var disableBody = Map.of("client_ref", clientRef, "id", teamId,
                    "revision", result.path("restored").path("body").path("data").path("revision").asLong());
            var disableRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/teams/disable"))
                    .timeout(Duration.ofSeconds(20)).header("X-Channel-Token", channelToken)
                    .header("X-Chat-User-Token", identity).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(disableBody))).build();
            var disabledAgain = httpClient.send(disableRequest, HttpResponse.BodyHandlers.ofString());
            Files.writeString(evidence.resolve("auth-probe-team-disabled.json"), disabledAgain.body());
            assertEquals(200, disabledAgain.statusCode(), disabledAgain.body());
            var publication = queryRows(jdbc, tenantId,
                    "SELECT revision,published_revision,status FROM nw_agent_teams WHERE tenant_id=? AND id=?",
                    Long.parseLong(tenantId), teamId);
            Files.writeString(evidence.resolve("auth-probe-publication.json"), objectMapper.writeValueAsString(publication));
            assertEquals("disabled", publication.getFirst().get("status"));
            assertTrue(((Number) publication.getFirst().get("revision")).longValue()
                    > ((Number) publication.getFirst().get("published_revision")).longValue());
            String path = "/api/v1/chat/history?session_id=" + session;
            var ownHistory = historyProbe(path, channelToken, identity, evidence.resolve("auth-probe-owner.json"));
            assertEquals(200, ownHistory.statusCode(), ownHistory.body());
            assertEquals(2, java.util.stream.StreamSupport.stream(
                    objectMapper.readTree(ownHistory.body()).path("data").path("messages").spliterator(), false)
                    .filter(message -> "assistant".equals(message.path("role").asText())).count());
            var otherUser = registerProbeUser(channelToken);
            assertEquals(403, historyProbe(path, channelToken, otherUser.path("identity_token").asText(),
                    evidence.resolve("auth-probe-other-user.json")).statusCode());
            String otherToken = "team-disable-other-" + UUID.randomUUID();
            TenantContext.setTenantId(tenantId);
            try { createNativeBrowserChannel(otherToken, null, null); }
            finally { TenantContext.clear(); }
            var otherChannelUser = registerProbeUser(otherToken);
            assertEquals(403, historyProbe(path, otherToken, otherChannelUser.path("identity_token").asText(),
                    evidence.resolve("auth-probe-other-channel.json")).statusCode());
            TenantContext.setTenantId(tenantId);
            try {
                assertEquals(1, teamChannelBindings.delete(new LambdaQueryWrapper<ChannelAgentBindingDO>()
                        .eq(ChannelAgentBindingDO::getTenantId, Long.parseLong(tenantId))
                        .eq(ChannelAgentBindingDO::getChannelId, channel)
                        .eq(ChannelAgentBindingDO::getAgentId, Long.parseLong(agentId))));
            } finally { TenantContext.clear(); }
            assertEquals(403, historyProbe(path, channelToken, identity,
                    evidence.resolve("auth-probe-member-revoked.json")).statusCode());
            assertEquals(2, provider.requestCount());
            assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND session_id=?", tenantId, session));
        }
    }

    private HttpResponse<String> historyProbe(String path, String token, String identity, Path evidence) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(20))
                .header("X-Channel-Token", token).header("X-Chat-User-Token", identity).GET().build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Files.writeString(evidence, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("path", path, "status", response.statusCode(), "body", objectMapper.readTree(response.body()))));
        return response;
    }

    private JsonNode registerProbeUser(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/client-profile"))
                .timeout(Duration.ofSeconds(20)).header("X-Channel-Token", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("client_ref", UUID.randomUUID().toString(), "display_name", "历史访问边界验收")))).build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return objectMapper.readTree(response.body()).path("data");
    }
}
