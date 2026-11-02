package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementContracts.SaveTenantGrant;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 隔离浏览器通过真实 HTTP 创建渠道，验证最终绑定与回执。 */
class ChannelOnboardingBrowserIntegrationTest extends AbstractIntegrationTest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("channel-onboarding-browser-");
    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> false);
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired AgentMapper agents;
    @Autowired AgentPublicationService publications;
    @Autowired EntitlementMapper entitlements;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @Test void createsAndBindsInOneBrowserAction() throws Exception {
        String slug = "channel-browser-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        String password = "ChannelManagement123";
        long agentId, secondAgentId;
        TenantContext.setTenantId(tenant);
        try {
            long member = createTestTenantAdministrator(tenant, "channel-admin", password);
            withAuthenticatedMember(member, () -> {
                entitlements.insertTenantGrant(new SaveTenantGrant(tenant, "channels", true, 0L), "channel-browser-fixture");
                return null;
            });
            var agent = new AgentDO(); agent.setId(SnowflakeIds.nextId()); agent.setTenantId(Long.parseLong(tenant));
            agent.setAgentKey("channel-browser-" + agent.getId()); agent.setName("渠道测试助手");
            agent.setStatus("draft"); agent.setSystemPrompt("测试助手"); agents.insert(agent);
            publications.publish(agent); agentId = agent.getId();
            var second = new AgentDO(); second.setId(SnowflakeIds.nextId()); second.setTenantId(Long.parseLong(tenant));
            second.setAgentKey("channel-browser-" + second.getId()); second.setName("渠道导诊助手");
            second.setStatus("draft"); second.setSystemPrompt("测试助手"); agents.insert(second);
            publications.publish(second); secondAgentId = second.getId();
        } finally { TenantContext.clear(); }
        FRONTEND.bindBackend("http://127.0.0.1:" + port);
        var admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var evidence = FRONTEND.evidence();
        var builder = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "--config=playwright.channel-onboarding.config.ts");
        builder.directory(admin.toFile()); builder.redirectErrorStream(true); builder.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = builder.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", evidence.toString());
        env.put("E2E_TENANT_SLUG", slug); env.put("E2E_USERNAME", "channel-admin"); env.put("E2E_PASSWORD", password);
        env.put("E2E_AGENT_NAME", "渠道测试助手");
        env.put("E2E_SECOND_AGENT_NAME", "渠道导诊助手");
        Process process = builder.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
            assertEquals(0, process.exitValue(), () -> "浏览器验收失败：" + evidence);
        }
        var result = json.readTree(Files.readString(evidence.resolve("channel-onboarding-result.json")));
        long channelId = result.path("channel_id").asLong();
        assertTrue(channelId > 0);
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_channel_agent_bindings WHERE tenant_id=? AND channel_id=? AND agent_id=? AND NOT is_default", Long.valueOf(tenant), channelId, agentId));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_channel_agent_bindings WHERE tenant_id=? AND channel_id=? AND agent_id=? AND is_default", Long.valueOf(tenant), channelId, secondAgentId));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_channel_create_receipts WHERE tenant_id=? AND request_id=?::uuid AND channel_id=?", Long.valueOf(tenant), result.path("request_id").asText(), channelId));
        var recovered = result.path("recovered");
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_channel_create_receipts WHERE tenant_id=? AND request_id=?::uuid AND channel_id=?", Long.valueOf(tenant), recovered.path("request_id").asText(), recovered.path("channel_id").asLong()));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_channels WHERE tenant_id=? AND name=?", Long.valueOf(tenant), "恢复渠道"));
    }
}
