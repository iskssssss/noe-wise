package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 仅管理 HTTP/真实 PG 事务；不装配模型夹具、不发布或执行技能。 */
class SkillOnboardingHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String PATH = "/api/v1/capabilities/skill-drafts";
    private static final String PASSWORD = "SyntheticSkillOnboarding123";
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> false);
        registry.add("skillforge.memory-profile-summary-cron", () -> "-");
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void onePostCreatesOnlyOneDefinitionAndActiveEmptyDraftWithoutPublication() throws Exception {
        var owner = account();
        String actor = member(owner, List.of("foundation.capabilities.create", "foundation.skills.create",
                "foundation.skills.read"));
        var before = counts(owner);
        var draft = data(send(actor, "POST", PATH + "/new", request("new-" + owner.tenant())), 201);
        assertFalse(draft.path("id").asText().isBlank());
        assertTrue(draft.path("definition_id").asLong() > 0);
        assertEquals("", draft.path("version_key").asText());
        assertEquals(0, draft.path("files").size());
        assertTrue(draft.path("base_version_id").isNull());
        assertTrue(draft.path("published_version_id").isNull());
        assertEquals(List.of(before.get(0) + 1, before.get(1) + 1, before.get(2)), counts(owner));
        String definition = draft.path("definition_id").asText();
        assertEquals(draft, data(send(actor, "GET", PATH + "?definitionId=" + definition, null), 200));
        assertEquals(1, count(owner, "SELECT count(*) FROM nw_capability_definitions WHERE tenant_id=? AND id="
                + Long.parseLong(definition) + " AND capability_type='SKILL' AND status='DRAFT' AND default_version_id IS NULL"));
    }

    @Test
    void eitherMissingPermissionRejectsBeforeDefinitionOrDraftWrites() throws Exception {
        var owner = account();
        var before = counts(owner);
        for (String permission : List.of("foundation.capabilities.create", "foundation.skills.create")) {
            String actor = member(owner, List.of(permission));
            var response = send(actor, "POST", PATH + "/new", request("denied-" + SnowflakeIds.nextId()));
            assertEquals(403, response.statusCode(), response.body());
            assertEquals(before, counts(owner));
        }
    }

    @Test
    void duplicateKeyDoesNotCreateAnotherDraftAndOtherTenantCannotReadOriginalDraft() throws Exception {
        var owner = account();
        String key = "tenant-key-" + owner.tenant();
        var draft = data(send(owner.token(), "POST", PATH + "/new", request(key)), 201);
        var original = counts(owner);
        var duplicate = send(owner.token(), "POST", PATH + "/new", request(key));
        assertEquals(409, duplicate.statusCode(), duplicate.body());
        assertEquals(original, counts(owner));
        var other = account();
        assertEquals(404, send(other.token(), "GET", PATH + "/detail?draftId=" + draft.path("id").asText(), null).statusCode());
        var separate = data(send(other.token(), "POST", PATH + "/new", request(key)), 201);
        assertNotEquals(draft.path("definition_id"), separate.path("definition_id"));
        assertEquals(List.of(1L, 1L, 0L), counts(other));
        assertEquals(original, counts(owner));
    }

    @Test
    void actualDraftInsertConstraintFailureRollsBackTheAlreadyInsertedDefinition() throws Exception {
        var owner = account();
        String key = "rollback-" + owner.tenant();
        String constraint = "owned_skill_insert_" + owner.tenant();
        var before = counts(owner);
        String witness = "owned_skill_fault_" + owner.tenant();
        // 序列变更不随事务回滚，用于证明实际到达草稿 INSERT 的故障点；只阻断本用例租户。
        jdbc.execute("CREATE SEQUENCE " + witness);
        try {
            jdbc.execute("ALTER TABLE nw_skill_editing_drafts ADD CONSTRAINT " + constraint
                    + " CHECK (tenant_id IS DISTINCT FROM " + Long.parseLong(owner.tenant())
                    + " OR nextval('" + witness + "') < 0)");
        } catch (RuntimeException failure) {
            jdbc.execute("DROP SEQUENCE " + witness);
            throw failure;
        }
        try {
            var response = send(owner.token(), "POST", PATH + "/new", request(key));
            assertEquals(500, response.statusCode(), response.body());
            assertEquals("INTERNAL_ERROR", json.readTree(response.body()).path("code").asText());
            assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT is_called FROM " + witness, Boolean.class),
                    "必须确实到达 PostgreSQL 草稿 INSERT 检查约束，而非此前入口拒绝");
            assertEquals(before, counts(owner));
            assertEquals(0, count(owner, "SELECT count(*) FROM nw_capability_definitions WHERE tenant_id=? AND capability_key='" + key + "'"));
        } finally {
            jdbc.execute("ALTER TABLE nw_skill_editing_drafts DROP CONSTRAINT " + constraint);
            jdbc.execute("DROP SEQUENCE " + witness);
        }
        // 失败请求不重发；HTTP 明确成功的事务由独立正例证明。
    }

    private Map<String, Object> request(String key) {
        return Map.of("capability_key", key, "name", "自有技能", "description", "事务验收元数据");
    }
    private Account account() throws Exception {
        String suffix = Long.toString(SnowflakeIds.nextId());
        String slug = "skill-onboarding-" + suffix;
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        String user = "admin-" + suffix;
        createTestTenantAdministrator(tenant, user, PASSWORD);
        return new Account(tenant, slug, login(slug, user));
    }
    private String member(Account owner, List<String> permissions) throws Exception {
        String suffix = Long.toString(SnowflakeIds.nextId());
        String access = "/api/v1/tenant/foundation/access";
        var role = data(send(owner.token(), "POST", access + "/role", Map.of("code", "skill_" + suffix,
                "name", "技能操作", "data_scope", "all", "permissions", permissions, "department_ids", List.of())), 200);
        String user = "member-" + suffix;
        data(send(owner.token(), "POST", access + "/member", Map.of("username", user,
                "display_name", "技能成员", "password", PASSWORD, "role_ids", List.of(role.path("id").asText()))), 200);
        return login(owner.slug(), user);
    }
    private String login(String slug, String user) throws Exception {
        return data(send(null, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", user, "password", PASSWORD)), 200).path("access_token").asText();
    }
    private List<Long> counts(Account owner) {
        return List.of(count(owner, "SELECT count(*) FROM nw_capability_definitions WHERE tenant_id=?"),
                count(owner, "SELECT count(*) FROM nw_skill_editing_drafts WHERE tenant_id=?"),
                count(owner, "SELECT count(*) FROM nw_capability_versions WHERE tenant_id=?"));
    }
    private long count(Account owner, String sql) {
        return queryCount(jdbc, owner.tenant(), sql, Long.parseLong(owner.tenant()));
    }
    private HttpResponse<String> send(String token, String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode data(HttpResponse<String> response, int expected) throws Exception {
        assertEquals(expected, response.statusCode(), response.body());
        var envelope = json.readTree(response.body());
        assertEquals("OK", envelope.path("code").asText(), response.body());
        return envelope.path("data");
    }
    private record Account(String tenant, String slug, String token) {}
}
