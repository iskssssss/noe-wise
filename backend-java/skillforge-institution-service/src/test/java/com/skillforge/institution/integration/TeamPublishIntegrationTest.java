package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.service.AgentManagementService;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实 HTTP、RLS 与事务验证；模型目录为本地夹具，不调用模型。 */
@Import(DeterministicModelCatalogConfiguration.class)
class TeamPublishIntegrationTest extends AbstractIntegrationTest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("team-publish-browser-");
    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @DynamicPropertySource static void workers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> false);
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired AgentMapper agents;
    @Autowired AgentManagementService agentManagement;
    @Autowired PlatformModelCatalogService modelCatalog;
    @Autowired ObjectMapper json;
    @Autowired com.skillforge.core.foundation.persistence.TenantMemberMapper members;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @LocalServerPort int port;

    @Test void draftReceiptsKeepOriginalIdentityAcrossLaterSaves() throws Exception {
        String slug = "team-draft-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug), password = "TeamDraftIntegration123";
        String agentId;
        TenantContext.setTenantId(tenant);
        try {
            long member = createTestTenantAdministrator(tenant, "team-draft-admin", password);
            restrictedMember(tenant, "team-draft-reader", password, List.of("foundation.teams.read"));
            PlatformModelFixtures.selectLlm(modelCatalog, tenant, "http://127.0.0.1:9", "deepseek-flash", "fixture-key",
                    "custom", "custom-openai-compatible", "custom-v1");
            AgentDO agent = agent(tenant, "published"); agents.insert(agent);
            withAuthenticatedMember(member, () -> { agentManagement.publish(agent.getId().toString()); return null; });
            agentId = agent.getId().toString();
        } finally { TenantContext.clear(); }
        HttpClient http = HttpClient.newHttpClient();
        String token = login(http, slug, "team-draft-admin", password);
        String reader = login(http, slug, "team-draft-reader", password);
        String requestId = UUID.randomUUID().toString();
        String lookup = "/api/v1/teams/save-with-receipt/result?request_id=" + requestId;
        assertEquals(204, request(http, "GET", lookup, null, token).statusCode());
        var created = body(request(http, "POST", "/api/v1/teams/save-with-receipt",
                command(requestId, null, "draft", 0, agentId, "团队草稿"), token));
        assertEquals(requestId, created.path("request_id").asText());
        assertEquals(1, created.path("revision").asLong());
        assertEquals(created, body(request(http, "GET", lookup, null, token)));
        assertEquals(204, request(http, "GET", lookup, null, reader).statusCode());
        String teamId = created.path("team_id").asText();
        String updateId = UUID.randomUUID().toString();
        var updated = body(request(http, "POST", "/api/v1/teams/save-with-receipt",
                command(updateId, teamId, "draft", 1, agentId, "团队草稿更新"), token));
        assertEquals(teamId, updated.path("team_id").asText());
        assertEquals(2, updated.path("revision").asLong());
        assertEquals(created, body(request(http, "GET", lookup, null, token)));
        assertEquals(409, request(http, "POST", "/api/v1/teams/save-with-receipt",
                command(UUID.randomUUID().toString(), teamId, "draft", 1, agentId, "旧修订"), token).statusCode());
        assertEquals(2, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_team_draft_receipts WHERE tenant_id=? AND team_id=?", Long.valueOf(tenant), teamId));
        assertEquals(2, ((Number) queryRows(jdbc, tenant, "SELECT revision FROM nw_agent_teams WHERE tenant_id=? AND id=?", Long.valueOf(tenant), teamId).getFirst().get("revision")).longValue());
    }

    @Test void originalReceiptAndAtomicPublication() throws Exception {
        String slug = "team-publish-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug), password = "TeamPublishIntegration123";
        TenantContext.setTenantId(tenant);
        String agentId, draftId;
        long member;
        try {
            member = createTestTenantAdministrator(tenant, "team-admin", password);
            restrictedMember(tenant, "team-reader", password, List.of("foundation.teams.read"));
            restrictedMember(tenant, "team-create", password, List.of("foundation.teams.read", "foundation.teams.create"));
            restrictedMember(tenant, "team-publish", password, List.of("foundation.teams.read", "foundation.teams.publish"));
            restrictedMember(tenant, "team-operator", password, List.of("foundation.teams.read", "foundation.teams.create", "foundation.teams.publish"));
            PlatformModelFixtures.selectLlm(modelCatalog, tenant, "http://127.0.0.1:9", "deepseek-flash", "fixture-key",
                    "custom", "custom-openai-compatible", "custom-v1");
            AgentDO agent = agent(tenant, "published"); agents.insert(agent);
            withAuthenticatedMember(member, () -> { agentManagement.publish(agent.getId().toString()); return null; });
            agentId = agent.getId().toString();
            AgentDO draft = agent(tenant, "unpublished"); agents.insert(draft); draftId = draft.getId().toString();
        } finally { TenantContext.clear(); }
        String otherSlug = "team-other-" + SnowflakeIds.nextId();
        String otherTenant = createTenant(tenants, plans, otherSlug);
        TenantContext.setTenantId(otherTenant);
        try { createTestTenantAdministrator(otherTenant, "other-admin", password); }
        finally { TenantContext.clear(); }
        HttpClient http = HttpClient.newHttpClient();
        String token = json.readTree(request(http, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "team-admin", "password", password), null).body()).path("data").path("access_token").asText();
        assertFalse(token.isBlank());
        String other = login(http, otherSlug, "other-admin", password);
        String reader = login(http, slug, "team-reader", password);
        String createOnly = login(http, slug, "team-create", password);
        String publishOnly = login(http, slug, "team-publish", password);
        String operator = login(http, slug, "team-operator", password);
        String requestId = UUID.randomUUID().toString();
        var command = command(requestId, null, "draft", 0, agentId, "团队一");
        assertPreDispatch(request(http, "POST", "/api/v1/teams/save-and-publish", command, null), 401);
        assertEquals(204, request(http, "GET", "/api/v1/teams/save-and-publish/result?requestId=" + requestId, null, token).statusCode());
        var deniedRead = request(http, "GET", "/api/v1/teams/save-and-publish/result?requestId=" + requestId, null, null);
        assertEquals(401, deniedRead.statusCode());
        assertNotEquals("TEAM_PUBLISH_PRE_DISPATCH_REJECTED", json.readTree(deniedRead.body()).path("code").asText());
        String limitedId = UUID.randomUUID().toString();
        long window = System.currentTimeMillis() / 60_000;
        var rateKeys = List.of("rate:" + tenant + ":" + member + ":" + window,
                "rate:" + tenant + ":" + member + ":" + (window + 1));
        try {
            for (String key : rateKeys) redis.opsForValue().set(key, "1000000000", java.time.Duration.ofMinutes(2));
            var limited = request(http, "POST", "/api/v1/teams/save-and-publish", command(limitedId, null, "draft", 0, agentId, "限流"), token);
            assertPreDispatch(limited, 429);
            assertEquals("60", limited.headers().firstValue("Retry-After").orElse(null));
        } finally { redis.delete(rateKeys); }
        assertEquals(204, request(http, "GET", "/api/v1/teams/save-and-publish/result?requestId=" + limitedId, null, token).statusCode());
        JsonNode success = body(request(http, "POST", "/api/v1/teams/save-and-publish", command, token));
        assertEquals("SUCCEEDED", success.path("status").asText());
        String team = success.path("team").path("id").asText();
        assertEquals(2, success.path("team").path("revision").asLong());
        assertEquals("published", success.path("team").path("status").asText());
        assertEquals(success, body(request(http, "GET", "/api/v1/teams/save-and-publish/result?requestId=" + requestId, null, token)));
        assertEquals(success, body(request(http, "POST", "/api/v1/teams/save-and-publish", command, token)));
        var withKey = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/teams/save-and-publish"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Idempotency-Key", "team-receipt-test-" + requestId)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
        assertEquals(success, body(http.send(withKey, HttpResponse.BodyHandlers.ofString())));
        assertEquals(success, body(http.send(withKey, HttpResponse.BodyHandlers.ofString())));
        assertEquals(1, versions(tenant, team));
        assertEquals(204, request(http, "GET", "/api/v1/teams/save-and-publish/result?requestId=" + requestId, null, reader).statusCode());
        assertEquals(204, request(http, "GET", "/api/v1/teams/save-and-publish/result?requestId=" + requestId, null, other).statusCode());
        assertEquals(204, request(http, "GET", "/api/v1/teams/save-and-publish/result?requestId=" + requestId, null, operator).statusCode());
        assertEquals(409, request(http, "POST", "/api/v1/teams/save-and-publish", command, operator).statusCode());
        assertPreDispatch(request(http, "POST", "/api/v1/teams/save-and-publish", command(UUID.randomUUID().toString(), null, "draft", 0, agentId, "无发布权限"), createOnly), 403);
        assertPreDispatch(request(http, "POST", "/api/v1/teams/save-and-publish", command(UUID.randomUUID().toString(), null, "draft", 0, agentId, "无创建权限"), publishOnly), 403);
        assertPreDispatch(request(http, "POST", "/api/v1/teams/save-and-publish", command(UUID.randomUUID().toString(), team, "draft", 2, agentId, "无更新权限"), publishOnly), 403);
        assertPreDispatch(request(http, "POST", "/api/v1/teams/save-and-publish", command, createOnly), 403);
        assertEquals(success, body(request(http, "GET", "/api/v1/teams/save-and-publish/result?requestId=" + requestId, null, token)));
        String concurrentId = UUID.randomUUID().toString();
        var concurrent = command(concurrentId, null, "draft", 0, agentId, "并发团队");
        var uri = URI.create("http://127.0.0.1:" + port + "/api/v1/teams/save-and-publish");
        var concurrentRequest = HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(concurrent))).build();
        var first = http.sendAsync(concurrentRequest, HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(concurrentRequest, HttpResponse.BodyHandlers.ofString());
        assertTrue(Set.of("PROCESSING", "SUCCEEDED").contains(body(first.get()).path("status").asText()));
        assertTrue(Set.of("PROCESSING", "SUCCEEDED").contains(body(second.get()).path("status").asText()));
        var concurrentReceipt = body(request(http, "GET", "/api/v1/teams/save-and-publish/result?requestId=" + concurrentId, null, token));
        assertEquals("SUCCEEDED", concurrentReceipt.path("status").asText());
        assertEquals(1, versions(tenant, concurrentReceipt.path("team").path("id").asText()));
        assertEquals(409, request(http, "POST", "/api/v1/teams/save-and-publish", command(requestId, null, "draft", 0, agentId, "不同参数"), token).statusCode());
        assertRejected(http, token, command(UUID.randomUUID().toString(), team, "draft", 2, draftId, "不能发布"));
        assertRejected(http, token, command(UUID.randomUUID().toString(), team, "disabled", 2, agentId, "不能停用后发布"));
        assertRejected(http, token, command(UUID.randomUUID().toString(), team, "draft", 2, "abc", "非法成员"));
        assertRejected(http, token, command(UUID.randomUUID().toString(), team, "draft", 1, agentId, "旧版本"));
        assertEquals(2, ((Number) queryRows(jdbc, tenant, "SELECT revision FROM nw_agent_teams WHERE tenant_id=? AND id=?", Long.valueOf(tenant), team).getFirst().get("revision")).longValue());
        assertEquals(1, versions(tenant, team));
        assertEquals(204, request(http, "GET", "/api/v1/teams/save-and-publish/result?requestId=" + UUID.randomUUID(), null, token).statusCode());

        FRONTEND.bindBackend("http://127.0.0.1:" + port);
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin"), evidence = FRONTEND.evidence();
        var builder = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "--config=playwright.team-publish.config.ts");
        builder.directory(admin.toFile()); builder.redirectErrorStream(true); builder.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = builder.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", evidence.toString());
        env.put("E2E_TENANT_SLUG", slug); env.put("E2E_USERNAME", "team-admin"); env.put("E2E_PASSWORD", password);
        env.put("E2E_AGENT_NAME", "助手 published");
        Process process = builder.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
            assertEquals(0, process.exitValue(), () -> "浏览器验收失败：" + evidence);
        }
        var browserReceipt = json.readTree(java.nio.file.Files.readString(evidence.resolve("team-publish-receipt.json"))).path("data");
        assertEquals("SUCCEEDED", browserReceipt.path("status").asText());
        String browserTeam = browserReceipt.path("team").path("id").asText();
        assertEquals(1, versions(tenant, browserTeam));
        var persisted = queryRows(jdbc, tenant, "SELECT revision, published_revision, status FROM nw_agent_teams WHERE tenant_id=? AND id=?",
                Long.valueOf(tenant), browserTeam).getFirst();
        assertEquals("published", persisted.get("status"));
        assertEquals(browserReceipt.path("team").path("revision").asLong(), ((Number) persisted.get("published_revision")).longValue());
    }
    private void restrictedMember(String tenant, String username, String password, List<String> permissions) {
        var member = new com.skillforge.core.foundation.persistence.TenantMemberDO();
        member.setId(SnowflakeIds.nextId()); member.setTenantId(Long.parseLong(tenant)); member.setUsername(username);
        member.setRole("member"); member.setPermissions(List.of()); member.setStatus("active");
        member.setPasswordHash(passwords.encode(password)); member.setCreatedAt(Instant.now()); members.insert(member);
        jdbc.update("INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name) VALUES(?,?,?)", tenant, member.getId(), username);
        long role = SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(?,?,?,'测试角色','all')", role, tenant, "team_test_" + role);
        for (String permission : permissions)
            jdbc.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,?,?)", tenant, role, permission);
        jdbc.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,?,?)", tenant, member.getId(), role);
    }
    private String login(HttpClient http, String slug, String username, String password) throws Exception {
        var response = request(http, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug, "username", username, "password", password), null);
        assertEquals(200, response.statusCode(), response.body());
        String token = json.readTree(response.body()).path("data").path("access_token").asText(); assertFalse(token.isBlank()); return token;
    }
    private AgentDO agent(String tenant, String suffix) {
        AgentDO value = new AgentDO(); value.setId(SnowflakeIds.nextId()); value.setTenantId(Long.parseLong(tenant));
        value.setAgentKey("team-" + suffix + "-" + SnowflakeIds.nextId()); value.setName("助手 " + suffix);
        value.setSystemPrompt("测试助手"); value.setRoleDescription("测试"); value.setConfig(Map.of());
        value.setStatus("draft"); value.setCreatedAt(Instant.now()); value.setUpdatedAt(Instant.now()); return value;
    }
    private Map<String, Object> command(String requestId, String id, String status, long revision, String agent, String name) {
        var value = new HashMap<String, Object>(); value.put("request_id", requestId); value.put("id", id);
        value.put("payload", Map.of("name", name, "description", "测试", "status", status, "revision", revision,
                "members", List.of(Map.of("agent_id", agent)), "primary_agent_id", agent)); return value;
    }
    private HttpResponse<String> request(HttpClient http, String method, String path, Object payload, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (payload != null) builder.header("Content-Type", "application/json");
        return http.send(builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void assertPreDispatch(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals("TEAM_PUBLISH_PRE_DISPATCH_REJECTED", json.readTree(response.body()).path("code").asText());
    }
    private JsonNode body(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body()); return json.readTree(response.body()).path("data");
    }
    private void assertRejected(HttpClient http, String token, Object command) throws Exception {
        assertEquals("REJECTED", body(request(http, "POST", "/api/v1/teams/save-and-publish", command, token)).path("status").asText());
    }
    private long versions(String tenant, String team) { return queryCount(jdbc, tenant, "SELECT count(*) FROM nw_agent_team_versions WHERE tenant_id=? AND team_id=?", Long.valueOf(tenant), team); }
}
