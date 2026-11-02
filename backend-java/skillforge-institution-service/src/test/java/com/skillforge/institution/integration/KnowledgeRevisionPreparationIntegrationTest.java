package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实 HTTP 与 PostgreSQL 元数据事务；经济索引夹具不配置或调用模型。 */
@Timeout(90)
class KnowledgeRevisionPreparationIntegrationTest extends AbstractIntegrationTest {
    private static final String PASSWORD = "SyntheticKnowledgePrepare123";
    private static final String ENDPOINT = "/api/v1/knowledge/bases/revisions/prepare";
    private static final String ACCESS = "/api/v1/tenant/foundation/access";
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired KnowledgeService knowledge;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private String tenant;
    private String slug;
    private String admin;
    private String kb;
    private String selected;
    private String omitted;

    @DynamicPropertySource static void noBackgroundExecution(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> false);
        registry.add("skillforge.memory-profile-summary-cron", () -> "-");
    }

    @BeforeEach void fixture() throws Exception {
        slug = "knowledge-prepare-" + SnowflakeIds.nextId();
        tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        try { createTestTenantAdministrator(tenant, "prepare-admin", PASSWORD); }
        finally { TenantContext.clear(); }
        admin = login("prepare-admin");
        createBaseAndDocuments(admin);
    }

    private void createBaseAndDocuments(String owner) throws Exception {
        var created = ok("POST", "/api/v1/knowledge/bases", Map.of("name", "显式来源准备",
                "indexing_mode", "economical", "retrieval_mode", "full_text", "rerank_enabled", false), owner);
        assertTrue(created.path("id").isTextual(), "知识库 ID 必须按字符串传输，避免大整数精度丢失");
        kb = created.path("id").asText();
        TenantContext.setTenantId(tenant);
        try {
            selected = knowledge.uploadFile(tenant, Long.valueOf(kb), "选择的资料",
                    new MockMultipartFile("file", "selected.txt", "text/plain", "选择的资料原文".getBytes(StandardCharsets.UTF_8))).sourceVersion();
            omitted = knowledge.uploadFile(tenant, Long.valueOf(kb), "未选择的资料",
                    new MockMultipartFile("file", "omitted.txt", "text/plain", "未选择的资料原文".getBytes(StandardCharsets.UTF_8))).sourceVersion();
        } finally { TenantContext.clear(); }
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void knowledgeWriteRejectionLeavesDatabaseUnchangedAndAllowsCorrection() throws Exception {
        var validation = send("POST", "/api/v1/knowledge/bases", Map.of("name", " "), admin);
        assertEquals(400, validation.statusCode());
        assertEquals("KNOWLEDGE_WRITE_REJECTED", json.readTree(validation.body()).path("code").asText());
        assertTrue(json.readTree(validation.body()).path("field_errors").has("name"));
        var anonymous = send("POST", "/api/v1/knowledge/bases", Map.of("name", " "), null);
        assertEquals(401, anonymous.statusCode());
        assertNotEquals("KNOWLEDGE_WRITE_REJECTED", json.readTree(anonymous.body()).path("code").asText());
        var invalid = send("POST", "/api/v1/knowledge/bases", Map.of("name", "拒绝创建",
                "indexing_mode", "invalid"), admin);
        assertEquals(422, invalid.statusCode());
        assertEquals("KNOWLEDGE_WRITE_REJECTED", json.readTree(invalid.body()).path("code").asText());
        var bases = ok("GET", "/api/v1/knowledge/bases", null, admin).path("items");
        assertFalse(bases.toString().contains("拒绝创建"));

        var rejected = send("PATCH", "/api/v1/knowledge/bases/update", Map.of("kb_id", kb,
                "payload", Map.of("name", "不应保存", "status", "invalid")), admin);
        assertEquals(400, rejected.statusCode());
        assertEquals("KNOWLEDGE_WRITE_REJECTED", json.readTree(rejected.body()).path("code").asText());
        assertEquals("显式来源准备", ok("GET", "/api/v1/knowledge/bases/detail?kbId=" + kb, null, admin).path("name").asText());
        var corrected = ok("PATCH", "/api/v1/knowledge/bases/update", Map.of("kb_id", kb,
                "payload", Map.of("name", "已修正")), admin);
        assertEquals("已修正", corrected.path("name").asText());
    }

    @Test void explicitPreparationIsReadyAndRequiresSeparatePublishPermissionAndConfirmation() throws Exception {
        String preparer = member(List.of("foundation.knowledge.read", "foundation.knowledge.create", "foundation.knowledge.update",
                "foundation.resource.knowledge.read", "foundation.resource.knowledge.manage"));
        assertEquals(403, send("POST", ENDPOINT, command(List.of(selected)), preparer).statusCode(),
                "本人范围的 create/update 与资源 manage 权限不能越过已有知识库的所有权");
        assertEquals(0L, revisionCount());
        // 正例由准备者通过正式创建入口成为新知识库 owner，不扩大生产资源权限。
        createBaseAndDocuments(preparer);
        var prepared = ok("POST", ENDPOINT, command(List.of(selected)), preparer);
        assertEquals("ready", prepared.path("status").asText());
        assertEquals(List.of(selected), json.convertValue(prepared.path("source_versions"), List.class));
        assertNotEquals(selected, omitted);
        assertTrue(prepared.path("capability_version_id").isMissingNode() || prepared.path("capability_version_id").isNull());
        assertEquals(1L, revisionCount());
        assertNull(basePointer());
        assertEquals(0L, capabilityCount());

        var publish = Map.of("kb_id", kb, "revision", prepared.path("revision").asLong());
        assertEquals(403, send("POST", "/api/v1/knowledge/bases/revisions/publish", publish, preparer).statusCode());
        assertNull(basePointer());
        var published = ok("POST", "/api/v1/knowledge/bases/revisions/publish", publish, admin);
        assertEquals("published", published.path("status").asText());
        assertEquals(prepared.path("id").asText(), basePointer().toString());
        assertEquals(1L, capabilityCount());
    }

    @Test void invalidSourcesRollbackNewDraftAndPreserveReusedDraft() throws Exception {
        assertEquals(422, send("POST", ENDPOINT, command(List.of("missing-source")), admin).statusCode());
        assertEquals(0L, revisionCount(), "markReady失败必须回滚prepare内新建草稿");
        var draft = ok("POST", "/api/v1/knowledge/bases/revisions", Map.of("kb_id", kb), admin);
        assertEquals(422, send("POST", ENDPOINT, command(List.of("missing-source")), admin).statusCode());
        assertEquals(1L, revisionCount());
        var revisions = ok("GET", "/api/v1/knowledge/bases/revisions?kbId=" + kb, null, admin).path("items");
        assertEquals(draft.path("id"), revisions.get(0).path("id"));
        assertEquals("draft", revisions.get(0).path("status").asText());
        assertTrue(revisions.get(0).path("source_versions").isEmpty());
        assertNull(basePointer());
        var ready = ok("POST", ENDPOINT, command(List.of(selected)), admin);
        assertEquals(draft.path("id"), ready.path("id"));
        assertEquals("ready", ready.path("status").asText());
        assertEquals(1L, revisionCount());
        assertEquals(0L, capabilityCount());
    }

    @Test void emptyOversizedOrIncompletePolicyRequestsDoNotCreateRevisions() throws Exception {
        assertEquals(400, send("POST", ENDPOINT, command(List.of()), admin).statusCode());
        assertEquals(400, send("POST", ENDPOINT, command(Collections.nCopies(2001, selected)), admin).statusCode());
        var incomplete = Map.of("kb_id", kb, "payload", Map.of("source_versions", List.of(selected),
                "policy", Map.of("model_invocable", true)));
        assertEquals(400, send("POST", ENDPOINT, incomplete, admin).statusCode());
        assertEquals(0L, revisionCount());
        assertNull(basePointer());
    }

    @Test void eitherMissingWritePermissionAndCrossTenantTargetAreRejectedBeforePreparation() throws Exception {
        for (var missing : List.of("foundation.knowledge.create", "foundation.knowledge.update")) {
            var permissions = new ArrayList<>(List.of("foundation.knowledge.read", "foundation.knowledge.create", "foundation.knowledge.update"));
            permissions.remove(missing);
            assertEquals(403, send("POST", ENDPOINT, command(List.of(selected)), member(permissions)).statusCode());
            assertEquals(0L, revisionCount());
        }
        String otherSlug = "knowledge-other-" + SnowflakeIds.nextId();
        String otherTenant = createTenant(tenants, plans, otherSlug);
        TenantContext.setTenantId(otherTenant);
        try { createTestTenantAdministrator(otherTenant, "other-admin", PASSWORD); }
        finally { TenantContext.clear(); }
        String otherToken = ok("POST", "/api/v1/auth/login", Map.of("tenant_slug", otherSlug,
                "username", "other-admin", "password", PASSWORD), null).path("access_token").asText();
        int status = send("POST", ENDPOINT, command(List.of(selected)), otherToken).statusCode();
        assertTrue(status == 403 || status == 404);
        assertEquals(0L, revisionCount());
    }

    private Map<String, Object> command(List<String> sources) {
        return Map.of("kb_id", kb, "payload", Map.of("source_versions", sources, "policy", Map.of(
                "model_invocable", true, "user_invocable", false, "allowed_operators", List.of("KEYWORD"),
                "freshness_policy", "PINNED", "citation_required", true)));
    }
    private long revisionCount() {
        return ((Number) queryRows(jdbc, tenant, "SELECT count(*) AS n FROM nw_knowledge_base_revisions WHERE tenant_id=? AND kb_id=?",
                Long.valueOf(tenant), Long.valueOf(kb)).getFirst().get("n")).longValue();
    }
    private Object basePointer() {
        return queryRows(jdbc, tenant, "SELECT active_revision_id FROM nw_knowledge_bases WHERE tenant_id=? AND id=?",
                Long.valueOf(tenant), Long.valueOf(kb)).getFirst().get("active_revision_id");
    }
    private long capabilityCount() {
        return ((Number) queryRows(jdbc, tenant, "SELECT count(*) AS n FROM nw_capability_definitions WHERE tenant_id=? AND capability_type='KNOWLEDGE_SOURCE' AND capability_key=?",
                Long.valueOf(tenant), "knowledge-base:" + kb).getFirst().get("n")).longValue();
    }
    private String member(List<String> permissions) throws Exception {
        String name = "preparer-" + SnowflakeIds.nextId();
        var role = ok("POST", ACCESS + "/role", Map.of("code", "prepare_" + SnowflakeIds.nextId(), "name", "准备知识角色",
                "data_scope", "self", "permissions", permissions, "department_ids", List.of()), admin);
        ok("POST", ACCESS + "/member", Map.of("username", name, "display_name", name, "password", PASSWORD,
                "role_ids", List.of(role.path("id").asText())), admin);
        return login(name);
    }
    private String login(String username) throws Exception {
        return ok("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug, "username", username, "password", PASSWORD), null)
                .path("access_token").asText();
    }
    private HttpResponse<String> send(String method, String path, Object body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(String method, String path, Object body, String token) throws Exception {
        var response = send(method, path, body, token);
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.statusCode() + ": " + response.body());
        return json.readTree(response.body()).path("data");
    }
}
