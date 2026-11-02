package com.skillforge.institution.integration;

import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.channel.mapper.ChannelAgentBindingMapper;
import com.skillforge.core.channel.mapper.ChannelMapper;
import com.skillforge.core.channel.model.ChannelAgentBindingDO;
import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.security.TokenHash;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** 显式启用的人工验收驻留服务；真实 Browser 授权和服务集成，不执行模型或启动浏览器。 */
@EnabledIfEnvironmentVariable(named = "SKILLFORGE_BROWSER_HUMAN_ACCEPTANCE", matches = "1")
@TestPropertySource(properties = "agent-runtime.workers.auto-startup=false")
class BrowserHumanAcceptanceHarnessTest extends AbstractIntegrationTest {
    private static final String USERNAME = "browser-human-admin", PASSWORD = "BrowserHumanAcceptance123";
    @DynamicPropertySource static void browserService(DynamicPropertyRegistry registry) {
        // BrowserClient 当前读取的是这两个大写属性，并非 skillforge.browser.*。
        registry.add("BROWSER_SERVICE_URL", () -> required("BROWSER_HUMAN_SERVICE_URL"));
        registry.add("BROWSER_SERVICE_TOKEN", () -> required("BROWSER_HUMAN_SERVICE_TOKEN"));
    }
    @Autowired TenantMapper tenants; @Autowired PlanMapper plans; @Autowired TenantMemberMapper members;
    @Autowired AgentMapper agents; @Autowired ChannelMapper channels; @Autowired ChannelAgentBindingMapper bindings;
    @Autowired EndUserMapper users; @Autowired SessionMapper sessions;
    @Autowired PasswordEncoder passwords; @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @Test void serveIsolatedApplicationUntilExplicitHumanFinishReceipt() throws Exception {
        Path evidence = Path.of(required("BROWSER_HUMAN_EVIDENCE_DIR")).toAbsolutePath().normalize();
        Files.createDirectories(evidence);
        if (Files.exists(evidence.resolve("ready.private.json")) || Files.exists(evidence.resolve("finish.json")))
            throw new IllegalStateException("BROWSER_HUMAN_EVIDENCE_DIRECTORY_ALREADY_USED");
        String run = Long.toString(SnowflakeIds.nextId()), slug = "browser-human-" + run;
        String tenant = createTenant(tenants, plans, slug);
        String channelToken = "browser-human-synthetic-" + UUID.randomUUID();
        var ready = new LinkedHashMap<String, Object>();
        TenantContext.setTenantId(tenant);
        try {
            long owner = Long.parseLong(tenant);
            var member = new TenantMemberDO(); member.setId(SnowflakeIds.nextId()); member.setTenantId(owner);
            member.setUsername(USERNAME); member.setRole("admin"); member.setPermissions(List.of()); member.setStatus("active");
            member.setPasswordHash(passwords.encode(PASSWORD)); member.setCreatedAt(Instant.now()); members.insert(member);
            var agent = new AgentDO(); agent.setId(SnowflakeIds.nextId()); agent.setTenantId(owner); agent.setAgentKey("browser-human-" + run);
            agent.setName("Browser Human Acceptance"); agent.setStatus("published"); agent.setSystemPrompt("合成浏览器人工验收，不执行模型");
            agent.setTemplateVersion("1.0"); agent.setRoleDescription("真实浏览器授权验收助手"); agents.insert(agent);
            var channel = new ChannelDO(); channel.setId(SnowflakeIds.nextId()); channel.setTenantId(owner);
            channel.setName("Browser human isolated channel"); channel.setChannelType("openapi"); channel.setStatus("active");
            channel.setChannelTokenHash(TokenHash.sha256Hex(channelToken)); channel.setConfig(Map.of()); channels.insert(channel);
            var binding = new ChannelAgentBindingDO(); binding.setId(SnowflakeIds.nextId()); binding.setTenantId(owner);
            binding.setChannelId(channel.getId()); binding.setAgentId(agent.getId()); binding.setIsDefault(true);
            binding.setPriority(0); binding.setTrafficPercentage(100); bindings.insert(binding);
            var user = new EndUserDO(); user.setId(SnowflakeIds.nextId()); user.setTenantId(owner); user.setStatus("active");
            user.setDisplayName("合成人工验收用户"); user.setCreatedAt(Instant.now()); user.setFirstSeenAt(Instant.now()); user.setLastActiveAt(Instant.now()); users.insert(user);
            var session = new SessionDO(); session.setId(SnowflakeIds.nextId()); session.setSessionId("browser-human-session-" + run);
            session.setTenantId(owner); session.setEndUserId(user.getId()); session.setAgentId(agent.getId()); session.setChannelId(channel.getId());
            session.setMessageCount(0); session.setLastEventId(0L); session.setMetadata(Map.of());
            session.setCreatedAt(Instant.now()); session.setUpdatedAt(Instant.now()); sessions.insert(session);
            ready.put("status", "AWAITING_HUMAN_ACCEPTANCE"); ready.put("backend_url", "http://127.0.0.1:" + port);
            ready.put("tenant_id", tenant); ready.put("tenant_slug", slug); ready.put("username", USERNAME); ready.put("password", PASSWORD);
            ready.put("agent_id", agent.getId().toString()); ready.put("channel_id", channel.getId().toString()); ready.put("channel_token", channelToken);
            ready.put("end_user_id", user.getId().toString()); ready.put("session_id", session.getSessionId());
            ready.put("model_calls", 0); ready.put("runtime_workers_enabled", false); ready.put("ready_at", Instant.now().toString());
        } finally { TenantContext.clear(); }
        Files.writeString(evidence.resolve("ready.private.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(ready),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        String outcome = "HUMAN_NOT_COMPLETED";
        try {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(60);
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(evidence.resolve("finish.json"))) {
                    // 仅确认人工回执存在；测试服务退出不等同产品验收通过。
                    json.readTree(Files.readString(evidence.resolve("finish.json"), StandardCharsets.UTF_8));
                    outcome = "HUMAN_FINISH_RECEIPT_RECEIVED"; return;
                }
                Thread.sleep(1000); // 可中断，驻留等待不创建模型请求或后台自动复验。
            }
        } catch (InterruptedException interrupted) {
            outcome = "HUMAN_WAIT_INTERRUPTED"; Thread.currentThread().interrupt(); throw interrupted;
        } finally {
            Files.writeString(evidence.resolve("harness-result.json"), json.writeValueAsString(Map.of("status", outcome,
                    "finished_at", Instant.now().toString(), "product_acceptance_verdict", "NOT_ASSERTED_BY_HARNESS")), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            // PG/Redis 与 Spring Context 由父类 Testcontainers/@DirtiesContext 按所有权清理。
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("MISSING_" + name);
        return value;
    }
}
