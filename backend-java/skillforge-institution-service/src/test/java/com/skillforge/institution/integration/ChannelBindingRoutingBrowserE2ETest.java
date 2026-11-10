package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementContracts.SaveTenantGrant;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实修改默认绑定后，以渠道目录选择和模型执行核对原路由参数。 */
class ChannelBindingRoutingBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("channel-binding-routing-");
    @Autowired EntitlementMapper entitlements;
    @DynamicPropertySource static void origin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", () -> FRONTEND.baseUrl() + ",http://localhost:3200");
    }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void bindingEditPreservesRoutingForRuntime() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_CHANNEL_BINDING_ROUTING", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        Path evidence = Path.of("build/runtime-e2e/channel-binding-routing-" + SnowflakeIds.nextId()).toAbsolutePath();
        Files.createDirectories(evidence);
        System.out.println("CHANNEL_BINDING_ROUTING_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "Real isolated Spring/PostgreSQL/Redis, admin browser binding edit, independent chat browser and local controlled HTTP model/Runtime. Priority is verified through directory fallback and actual selected agent. Traffic percentage is persistence-only evidence; no traffic allocation claim. Token/password excluded; trace/video/automatic screenshots disabled.");
        TenantContext.setTenantId(tenantId);
        try {
            long member = jdbc.queryForObject("SELECT id FROM nw_tenant_members WHERE tenant_id=? AND username=?", Long.class, Long.valueOf(tenantId), "runtime-e2e-admin");
            withAuthenticatedMember(member, () -> {
                entitlements.insertTenantGrant(new SaveTenantGrant(tenantId, "channels", true, 0L), "channel-binding-routing-fixture");
                return null;
            });
        } finally { TenantContext.clear(); }
        var createAgentBody = Map.<String, Object>of("name", "渠道路由助手B", "system_prompt", "你是渠道路由验收助手，完成用户请求。");
        var second = send("/api/v1/agents", "POST", createAgentBody, accessToken);
        Files.writeString(evidence.resolve("agent-created.json"), second.body());
        assertEquals(200, second.statusCode(), second.body());
        String agentB = successData(second).path("id").asText(); assertFalse(agentB.isBlank());
        var publication = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentB), accessToken);
        Files.writeString(evidence.resolve("agent-published.json"), publication.body());
        assertEquals(200, publication.statusCode(), publication.body());
        String channelName = "仅清除默认值验收渠道";
        var bindings = List.of(Map.of("agent_id", agentId, "priority", 20, "traffic_percentage", 25, "is_default", true),
                Map.of("agent_id", agentB, "priority", 10, "traffic_percentage", 75, "is_default", false));
        var body = Map.<String, Object>of("name", channelName, "channel_type", "openapi", "config", Map.of(), "agent_bindings", bindings);
        var created = send("/api/v1/channels", "POST", body, accessToken);
        assertEquals(200, created.statusCode(), "真实渠道创建失败，响应未输出以保护首次令牌");
        var channel = successData(created);
        String channelId = channel.path("id").asText(), token = channel.path("channel_token").asText();
        assertFalse(channelId.isBlank()); assertFalse(token.isBlank());
        Files.writeString(evidence.resolve("channel-create-request.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body));
        var initialChannel = successData(get("/api/v1/channels/detail?channelId=" + channelId));
        Files.writeString(evidence.resolve("initial-channel.json"), objectMapper.writeValueAsString(Map.of(
                "id", initialChannel.path("id"), "name", initialChannel.path("name"),
                "agent_bindings", initialChannel.path("agent_bindings"))));
        FRONTEND.bindBackend(baseUrl());
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "e2e/channel-binding-routing-real.spec.ts", "--output=" + evidence.resolve("playwright-output"));
        command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment();
        env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_BACKEND_URL", baseUrl()); env.put("E2E_ARTIFACT_DIR", evidence.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD);
        env.put("E2E_CHANNEL_BINDING_ROUTING", mode); env.put("E2E_CHANNEL_ID", channelId); env.put("E2E_CHANNEL_NAME", channelName);
        env.put("E2E_CHANNEL_TOKEN", token); env.put("E2E_AGENT_A", agentId); env.put("E2E_AGENT_B", agentB);
        env.put("E2E_EXPECTED_REPLY", "fixture-answer-" + runId); env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "渠道绑定浏览器超时：" + evidence);
            assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
        } finally {
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "bindings", queryRows(jdbc, tenantId, "SELECT agent_id,priority,traffic_percentage,is_default FROM nw_channel_agent_bindings WHERE tenant_id=CAST(? AS bigint) AND channel_id=CAST(? AS bigint) ORDER BY agent_id LIMIT 5", tenantId, channelId),
                    "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,agent_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? ORDER BY created_second LIMIT 10", tenantId),
                    "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? ORDER BY a.action_ordinal LIMIT 10", tenantId),
                    "budgets", queryRows(jdbc, tenantId, "SELECT run_id,state,category FROM agent_runtime_budget_attempts WHERE tenant_id=? LIMIT 10", tenantId),
                    "providerCalls", provider.requestCount(), "providerKinds", provider.responseKinds())));
        }
        var result = objectMapper.readTree(Files.readString(evidence.resolve("result.json")));
        String expected = "baseline".equals(mode) ? agentB : agentId;
        assertEquals(expected, result.path("expectedAgent").asText());
        assertEquals(1, result.path("submissions").asInt()); assertEquals(1, result.path("bindingWrites").asInt()); assertEquals(1, provider.requestCount());
        String actualRun = result.path("runId").asText();
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND agent_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun, expected));
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun));
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_budget_attempts WHERE tenant_id=? AND run_id=? AND state='SETTLED'", tenantId, actualRun));
    }
}
