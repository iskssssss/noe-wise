package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.dto.request.CapabilityMountRequest;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.service.CapabilityMountManagementService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 原创建 HTTP 身份、真实挂载与回执同事务；仅测试管理行为，不调用模型。 */
@Import(DeterministicModelCatalogConfiguration.class)
class AgentMountCreationHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String PATH = "/api/v1/agents/capability-mounts";
    private static final String PASSWORD = "mount-receipt-fixture-2026";
    private final HttpClient http = HttpClient.newHttpClient();
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private CapabilityMountManagementService mounts;

    @DynamicPropertySource static void managementOnly(DynamicPropertyRegistry properties) {
        properties.add("agent-runtime.workers.auto-startup", () -> false);
    }

    @Test void priorityOutsideIntegerRangeIsRejectedBeforeMountCreation() throws Exception {
        var account = account();
        String agent = agent(account.token()), identity = UUID.randomUUID().toString();
        var payload = new LinkedHashMap<>(payload(account.token()));
        payload.put("priority", 2147483648L);
        var before = facts(account.tenant(), agent);
        var rejected = call(account.token(), "POST", PATH,
                Map.of("request_id", identity, "agent_id", agent, "payload", payload), 400);
        assertEquals("VALIDATION_ERROR", rejected.path("code").asText());
        org.mockito.Mockito.verify(mounts, org.mockito.Mockito.never()).create(
                eq(Long.parseLong(account.tenant())), eq(Long.parseLong(agent)), any(CapabilityMountRequest.class));
        call(account.token(), "GET", result(agent, identity), null, 204);
        assertEquals(before, facts(account.tenant(), agent), "输入解码失败不得创建挂载、回执或修改草稿");
    }

    @Test void concurrentOriginalRequestCreatesOnceAndHistoricalReceiptSurvivesRemoval() throws Exception {
        var account = account();
        String agent = agent(account.token());
        var payload = payload(account.token());
        String identity = UUID.randomUUID().toString();
        var command = Map.of("request_id", identity, "agent_id", agent, "payload", payload);
        var before = facts(account.tenant(), agent);
        call(account.token(), "GET", result(agent, identity), null, 204);
        assertEquals(before, facts(account.tenant(), agent), "只读核对不得建立空挂载集或修改草稿");
        var first = http.sendAsync(request(account.token(), "POST", PATH, command, null), HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(request(account.token(), "POST", PATH, command, null), HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);
        JsonNode receipt = response(first.join(), 201);
        assertEquals(receipt, response(second.join(), 201));
        assertEquals(identity, receipt.path("request_id").asText());
        assertEquals(agent, receipt.path("agent_id").asText());
        assertFalse(receipt.path("mount_id").asText().isBlank());
        var created = facts(account.tenant(), agent);
        assertEquals(1L, ((Number) created.get("mounts")).longValue());
        assertEquals(1L, ((Number) created.get("receipts")).longValue());
        assertEquals(1L, ((Number) created.get("generation")).longValue());
        assertEquals(receipt, call(account.token(), "GET", result(agent, identity), null, 200));
        assertEquals(receipt, call(account.token(), "POST", PATH, command, 201));
        assertEquals(created, facts(account.tenant(), agent), "原请求重放不得增加挂载、generation或草稿revision");

        var changed = new LinkedHashMap<>(payload); changed.put("priority", 7);
        assertEquals("AGENT_MOUNT_CREATE_IDENTITY_CONFLICT", call(account.token(), "POST", PATH,
                Map.of("request_id", identity, "agent_id", agent, "payload", changed), 409).path("code").asText());
        String another = agent(account.token());
        assertEquals("AGENT_MOUNT_CREATE_IDENTITY_CONFLICT", call(account.token(), "POST", PATH,
                Map.of("request_id", identity, "agent_id", another, "payload", payload), 409).path("code").asText());
        String duplicate = UUID.randomUUID().toString();
        assertEquals("AGENT_MOUNT_CREATE_REJECTED", call(account.token(), "POST", PATH,
                Map.of("request_id", duplicate, "agent_id", agent, "payload", payload), 409).path("code").asText());
        call(account.token(), "GET", result(agent, duplicate), null, 204);
        call(account.token(), "POST", PATH, Map.of("agent_id", agent, "payload", payload), 400);
        assertEquals(created, facts(account.tenant(), agent));
        assertReceiptRls(account.tenant(), identity);

        var current = call(account.token(), "GET", PATH + "?agentId=" + agent, null, 200);
        assertEquals(receipt.path("mount_id"), current.path("mounts").get(0).path("mount_id"));
        response(http.send(request(account.token(), "DELETE", PATH + "/delete",
                Map.of("agent_id", agent, "mount_id", receipt.path("mount_id").asText()),
                current.path("mounts").get(0).path("revision").asText()), HttpResponse.BodyHandlers.ofString()), 200);
        var removed = facts(account.tenant(), agent);
        assertEquals(0L, ((Number) removed.get("mounts")).longValue());
        assertEquals(1L, ((Number) removed.get("receipts")).longValue());
        assertEquals(receipt, call(account.token(), "GET", result(agent, identity), null, 200));
        assertEquals(receipt, call(account.token(), "POST", PATH, command, 201));
        assertEquals(removed, facts(account.tenant(), agent), "历史成功回执不得重新创建已删除挂载");
    }

    @Test void receiptKeepsActorTenantAndCurrentResourcePermissions() throws Exception {
        var owner = account();
        String agent = agent(owner.token()), identity = UUID.randomUUID().toString();
        var payload = payload(owner.token());
        var command = Map.of("request_id", identity, "agent_id", agent, "payload", payload);
        call(owner.token(), "POST", PATH, command, 201);
        var created = facts(owner.tenant(), agent);
        var other = member(owner, List.of("foundation.agents.read", "foundation.agents.create",
                "foundation.resource.agent.read", "foundation.resource.agent.manage"));
        call(other.token(), "GET", result(agent, identity), null, 204);
        assertEquals("AGENT_MOUNT_CREATE_IDENTITY_CONFLICT", call(other.token(), "POST", PATH, command, 409).path("code").asText());
        var withoutResource = member(owner, List.of("foundation.agents.read", "foundation.agents.create"));
        call(withoutResource.token(), "GET", result(agent, identity), null, 403);
        call(withoutResource.token(), "POST", PATH, command, 403);
        var reader = member(owner, List.of("foundation.agents.read", "foundation.resource.agent.read"));
        call(reader.token(), "POST", PATH, command, 403);
        var creator = member(owner, List.of("foundation.agents.create", "foundation.resource.agent.manage"));
        String creatorAgent = agent(owner.token()), creatorIdentity = UUID.randomUUID().toString();
        call(creator.token(), "POST", PATH, Map.of("request_id", creatorIdentity, "agent_id", creatorAgent, "payload", payload), 201);
        call(creator.token(), "GET", result(creatorAgent, creatorIdentity), null, 403);

        String ownAgent = agent(owner.token()), ownIdentity = UUID.randomUUID().toString();
        var ownCommand = Map.of("request_id", ownIdentity, "agent_id", ownAgent, "payload", payload);
        var ownReceipt = call(other.token(), "POST", PATH, ownCommand, 201);
        assertEquals(ownReceipt, call(other.token(), "GET", result(ownAgent, ownIdentity), null, 200));
        var ownFacts = facts(owner.tenant(), ownAgent);
        call(owner.token(), "PUT", "/api/v1/tenant/foundation/access/role", Map.of("id", other.role().path("id").asText(),
                "name", other.role().path("name").asText(), "data_scope", "all", "enabled", true,
                "lock_version", other.role().path("lock_version").asLong(), "department_ids", List.of(),
                "permissions", List.of("foundation.agents.read", "foundation.agents.create")), 200);
        call(other.token(), "GET", result(ownAgent, ownIdentity), null, 401);
        String revoked = login(owner.slug(), other.role().path("code").asText());
        call(revoked, "GET", result(ownAgent, ownIdentity), null, 403);
        call(revoked, "POST", PATH, ownCommand, 403);
        assertEquals(ownFacts, facts(owner.tenant(), ownAgent), "权限撤销后原身份仍不能绕过当前资源授权");

        var foreign = account();
        call(foreign.token(), "GET", result(agent, identity), null, 403);
        String foreignAgent = agent(foreign.token());
        call(foreign.token(), "GET", result(foreignAgent, identity), null, 204);
        assertEquals("AGENT_MOUNT_CREATE_IDENTITY_CONFLICT", call(foreign.token(), "POST", PATH,
                Map.of("request_id", identity, "agent_id", foreignAgent, "payload", payload), 409).path("code").asText());
        assertEquals(created, facts(owner.tenant(), agent));
    }

    @Test void failureAfterMountWriteRollsBackMountReceiptAndDraftChange() throws Exception {
        var account = account();
        String agent = agent(account.token()), identity = UUID.randomUUID().toString();
        var command = Map.of("request_id", identity, "agent_id", agent, "payload", payload(account.token()));
        var before = facts(account.tenant(), agent);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("mount_receipt_rollback_fixture"); })
                .when(mounts).create(eq(Long.parseLong(account.tenant())), eq(Long.parseLong(agent)), any(CapabilityMountRequest.class));
        try {
            call(account.token(), "POST", PATH, command, 500);
            call(account.token(), "GET", result(agent, identity), null, 204);
            assertEquals(before, facts(account.tenant(), agent));
        } finally {
            doCallRealMethod().when(mounts).create(eq(Long.parseLong(account.tenant())), eq(Long.parseLong(agent)), any(CapabilityMountRequest.class));
        }
        var receipt = call(account.token(), "POST", PATH, command, 201);
        assertEquals(receipt, call(account.token(), "GET", result(agent, identity), null, 200));
        assertEquals(1L, ((Number) facts(account.tenant(), agent).get("mounts")).longValue());
    }

    private Account account() throws Exception {
        String slug = "mount-receipt-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        try { createTestTenantAdministrator(tenant, "receipt-admin", PASSWORD); }
        finally { TenantContext.clear(); }
        return new Account(tenant, slug, login(slug, "receipt-admin"));
    }

    private String login(String slug, String username) throws Exception {
        return call(null, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug, "username", username,
                "password", PASSWORD), 200).path("access_token").asText();
    }

    private Member member(Account owner, List<String> permissions) throws Exception {
        String name = "mount_reader_" + SnowflakeIds.nextId(), path = "/api/v1/tenant/foundation/access";
        var role = call(owner.token(), "POST", path + "/role", Map.of("code", name, "name", name,
                "data_scope", "all", "permissions", permissions, "department_ids", List.of()), 200);
        call(owner.token(), "POST", path + "/member", Map.of("username", name, "display_name", name,
                "password", PASSWORD, "role_ids", List.of(role.path("id").asText())), 200);
        return new Member(login(owner.slug(), name), role);
    }

    private String agent(String token) throws Exception {
        return call(token, "POST", "/api/v1/agents", Map.of("name", "挂载原请求验收", "system_prompt", "挂载创建事务"), 200)
                .path("id").asText();
    }

    private Map<String, Object> payload(String token) throws Exception {
        JsonNode definition = null;
        for (var entry : call(token, "GET", "/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED", null, 200))
            if ("tool:get_current_time".equals(entry.path("capability_key").asText())) definition = entry;
        assertNotNull(definition, "复用正式平台工具目录，不创建模拟能力");
        String deployment = "mount-receipt-" + SnowflakeIds.nextId();
        call(token, "POST", "/api/v1/capabilities/deployments", Map.of("deployment_key", deployment,
                "tool_version_id", definition.path("default_version_id").asText(), "provider_type", "PLATFORM",
                "provider_ref", "get_current_time", "environment", "DEVELOPMENT", "status", "ACTIVE"), 201);
        return Map.of("capability_type", "TOOL", "capability_definition_id", definition.path("id").asText(),
                "tool_config", Map.of("tool_deployment_id", deployment), "priority", 0);
    }

    private Map<String, Object> facts(String tenant, String agent) {
        return new TransactionTemplate(transactions).execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, tenant);
            return jdbc.queryForMap("""
                    SELECT a.revision,a.has_draft_changes,
                    (SELECT count(*) FROM nw_agent_mount_create_receipts r WHERE r.tenant_id=a.tenant_id AND r.agent_id=a.id) AS receipts,
                    (SELECT count(*) FROM nw_agent_capability_mounts m WHERE m.tenant_id=a.tenant_id AND m.agent_id=a.id) AS mounts,
                    (SELECT generation FROM nw_agent_capability_mount_sets s WHERE s.tenant_id=a.tenant_id AND s.agent_id=a.id) AS generation
                    FROM nw_agents a WHERE a.tenant_id=? AND a.id=?
                    """, Long.parseLong(tenant), Long.parseLong(agent));
        });
    }

    private void assertReceiptRls(String tenant, String identity) throws Exception {
        String role = "mount_receipt_reader_" + SnowflakeIds.nextId();
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
                statement.execute("GRANT USAGE ON SCHEMA public TO " + role);
                statement.execute("GRANT SELECT,INSERT ON public.nw_agent_mount_create_receipts TO " + role);
            }
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL ROLE " + role);
                    try (var flags = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                        assertTrue(flags.next()); assertFalse(flags.getBoolean(1));
                    }
                }
                try (var scope = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                    scope.setString(1, tenant); scope.execute();
                }
                try (var query = connection.prepareStatement("SELECT count(*) FROM nw_agent_mount_create_receipts WHERE request_id=?::uuid")) {
                    query.setString(1, identity);
                    try (var rows = query.executeQuery()) { rows.next(); assertEquals(1, rows.getInt(1)); }
                    try (var scope = connection.prepareStatement("SELECT set_config('app.tenant_id','0',true)")) { scope.execute(); }
                    try (var rows = query.executeQuery()) { rows.next(); assertEquals(0, rows.getInt(1)); }
                }
                try (var insert = connection.prepareStatement("INSERT INTO nw_agent_mount_create_receipts(request_id,tenant_id,actor_ref,agent_id,request_fingerprint) VALUES(?::uuid,?::bigint,'rls',1,repeat('a',64))")) {
                    insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, tenant);
                    assertThrows(java.sql.SQLException.class, insert::executeUpdate);
                }
            } finally {
                connection.rollback(); connection.setAutoCommit(true);
                try (var statement = connection.createStatement()) {
                    statement.execute("DROP OWNED BY " + role); statement.execute("DROP ROLE " + role);
                }
            }
        }
    }

    private String result(String agent, String identity) { return PATH + "/result?agent_id=" + agent + "&request_id=" + identity; }

    private HttpRequest request(String token, String method, String path, Object body, String revision) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (revision != null) request.header("If-Match", revision);
        return request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }

    private JsonNode call(String token, String method, String path, Object body, int expected) throws Exception {
        return response(http.send(request(token, method, path, body, null), HttpResponse.BodyHandlers.ofString()), expected);
    }

    private JsonNode response(HttpResponse<String> response, int expected) throws Exception {
        assertEquals(expected, response.statusCode(), response.body());
        if (expected == 204) return json.nullNode();
        var body = json.readTree(response.body());
        return expected >= 400 ? body : body.path("data");
    }

    private record Member(String token, JsonNode role) { }
    private record Account(String tenant, String slug, String token) { }
}
