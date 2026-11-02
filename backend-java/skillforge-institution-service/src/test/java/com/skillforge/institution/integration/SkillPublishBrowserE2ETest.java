package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.SkillDraftService;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 隔离浏览器、机构管理员、真实 HTTP 和 PostgreSQL；不调用模型。 */
@Tag("skill-publish-browser")
class SkillPublishBrowserE2ETest extends AbstractIntegrationTest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("skill-publish-browser-");
    @DynamicPropertySource static void origins(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
        registry.add("agent-runtime.workers.auto-startup", () -> false);
    }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired SkillDraftService drafts;
    @Autowired CapabilityCatalogService catalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @Autowired com.skillforge.core.foundation.persistence.TenantMemberMapper members;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @LocalServerPort int port;

    @Test void browserPublishesAndReadsOriginalReceipt() throws Exception {
        String slug = "skill-browser-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug), password = "SkillPublishBrowser123";
        long tenantId = Long.parseLong(tenant);
        var scope = CapabilityCatalogScope.tenant(tenantId);
        long definitionId, draftId, member;
        TenantContext.setTenantId(tenant);
        try {
            member = createTestTenantAdministrator(tenant, "skill-browser-admin", password);
            restrictedMember(tenant, "skill-update-only", password, java.util.List.of("foundation.skills.read", "foundation.skills.update"));
            restrictedMember(tenant, "skill-publish-only", password, java.util.List.of("foundation.skills.read", "foundation.skills.publish"));
            var draft = drafts.createSkill(scope, "browser-skill-" + SnowflakeIds.nextId(), "浏览器技能", "浏览器验收");
            definitionId = draft.definitionId(); draftId = draft.id();
        } finally { TenantContext.clear(); }
        var http = HttpClient.newHttpClient();
        String endpoint = "http://127.0.0.1:" + port + "/api/v1/capabilities/skill-drafts/save-and-publish";
        var unauthenticated = HttpRequest.newBuilder(URI.create(endpoint)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        var denied = http.send(unauthenticated, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, denied.statusCode(), denied.body());
        assertEquals("SKILL_PUBLISH_PRE_DISPATCH_REJECTED", json.readTree(denied.body()).path("code").asText());
        var login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("tenant_slug", slug,
                        "username", "skill-browser-admin", "password", password)))).build();
        var loginResponse = http.send(login, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginResponse.statusCode(), loginResponse.body());
        String token = json.readTree(loginResponse.body()).path("data").path("access_token").asText();
        assertFalse(token.isBlank());
        long window = System.currentTimeMillis() / 60_000;
        var keys = java.util.List.of("rate:" + tenant + ":" + member + ":" + window,
                "rate:" + tenant + ":" + member + ":" + (window + 1));
        try {
            for (String key : keys) redis.opsForValue().set(key, "1000000000", java.time.Duration.ofMinutes(2));
            var limited = HttpRequest.newBuilder(URI.create(endpoint)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            var response = http.send(limited, HttpResponse.BodyHandlers.ofString());
            assertEquals(429, response.statusCode(), response.body());
            assertEquals("SKILL_PUBLISH_PRE_DISPATCH_REJECTED", json.readTree(response.body()).path("code").asText());
        } finally { redis.delete(keys); }
        var partialCommand = Map.of("request_id", java.util.UUID.randomUUID().toString(), "draft_id", Long.toString(draftId),
                "revision", 1, "definition_revision", 1);
        for (String username : java.util.List.of("skill-update-only", "skill-publish-only")) {
            var partialLogin = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(Map.of("tenant_slug", slug, "username", username, "password", password)))).build();
            var partialResponse = http.send(partialLogin, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, partialResponse.statusCode(), partialResponse.body());
            String partialToken = json.readTree(partialResponse.body()).path("data").path("access_token").asText();
            var partial = HttpRequest.newBuilder(URI.create(endpoint)).header("Authorization", "Bearer " + partialToken)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(partialCommand))).build();
            var response = http.send(partial, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode(), response.body());
            assertEquals("SKILL_PUBLISH_PRE_DISPATCH_REJECTED", json.readTree(response.body()).path("code").asText());
        }
        String invalidId = java.util.UUID.randomUUID().toString();
        Map<String, String> tooManyFiles = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 257; index++) tooManyFiles.put("file-" + index, "");
        var invalid = Map.of("request_id", invalidId, "draft_id", Long.toString(draftId), "revision", 1,
                "definition_revision", 1, "files", tooManyFiles);
        var invalidRequest = HttpRequest.newBuilder(URI.create(endpoint)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(invalid))).build();
        var invalidResponse = http.send(invalidRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalidResponse.statusCode(), invalidResponse.body());
        assertEquals("SKILL_PUBLISH_PRE_DISPATCH_REJECTED", json.readTree(invalidResponse.body()).path("code").asText());
        var missingReceipt = HttpRequest.newBuilder(URI.create(endpoint + "/result?request_id=" + invalidId))
                .header("Authorization", "Bearer " + token).GET().build();
        assertEquals(204, http.send(missingReceipt, HttpResponse.BodyHandlers.ofString()).statusCode());
        FRONTEND.bindBackend("http://127.0.0.1:" + port);
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin"), evidence = FRONTEND.evidence();
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "--config=playwright.skill-publish.config.ts");
        command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", evidence.toString());
        env.put("E2E_TENANT_SLUG", slug); env.put("E2E_USERNAME", "skill-browser-admin"); env.put("E2E_PASSWORD", password);
        env.put("E2E_SKILL_ID", Long.toString(definitionId));
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
            assertEquals(0, process.exitValue(), () -> "浏览器验收失败：" + evidence);
        }
        var createdSkill = json.readTree(Files.readString(evidence.resolve("skill-create-receipt.json")));
        var creationCommand = createdSkill.path("command");
        var creationReceipt = createdSkill.path("receipt");
        String createPath = "http://127.0.0.1:" + port + "/api/v1/capabilities/skill-drafts/create-with-receipt";
        var replay = HttpRequest.newBuilder(URI.create(createPath)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(creationCommand.toString())).build();
        var replayed = http.send(replay, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, replayed.statusCode(), replayed.body());
        assertEquals(creationReceipt, json.readTree(replayed.body()).path("data"));
        var changed = creationCommand.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("payload")).put("name", "不同内容");
        var conflict = HttpRequest.newBuilder(URI.create(createPath)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(changed.toString())).build();
        assertEquals(409, http.send(conflict, HttpResponse.BodyHandlers.ofString()).statusCode());
        var creationRows = queryRows(jdbc, tenant, "SELECT draft_id FROM nw_skill_create_receipts WHERE tenant_id=? AND request_id=?::uuid",
                tenantId, creationReceipt.path("request_id").asText());
        assertEquals(1, creationRows.size());
        assertEquals(creationReceipt.path("draft_id").asText(), creationRows.getFirst().get("draft_id").toString());
        var otherLogin = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("tenant_slug", slug, "username", "skill-update-only", "password", password)))).build();
        var otherToken = json.readTree(http.send(otherLogin, HttpResponse.BodyHandlers.ofString()).body()).path("data").path("access_token").asText();
        assertFalse(otherToken.isBlank());
        var otherLookup = HttpRequest.newBuilder(URI.create(createPath + "/result?request_id=" + creationReceipt.path("request_id").asText()))
                .header("Authorization", "Bearer " + otherToken).GET().build();
        assertEquals(204, http.send(otherLookup, HttpResponse.BodyHandlers.ofString()).statusCode());
        var receipt = json.readTree(Files.readString(evidence.resolve("skill-publish-receipt.json"))).path("data");
        assertEquals("SUCCEEDED", receipt.path("status").asText());
        assertEquals(Long.toString(definitionId), receipt.path("definition_id").asText());
        assertEquals(Long.toString(draftId), receipt.path("draft_id").asText());
        String version = receipt.path("publication").path("version_id").asText();
        assertFalse(version.isBlank());
        var rows = queryRows(jdbc, tenant, "SELECT status, response::text AS response FROM nw_skill_publish_receipts WHERE tenant_id=? AND request_id=?::uuid",
                tenantId, receipt.path("request_id").asText());
        assertEquals(1, rows.size());
        assertEquals("SUCCEEDED", rows.getFirst().get("status"));
        assertEquals(receipt, json.readTree(rows.getFirst().get("response").toString()));
        var lookup = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/capabilities/skill-drafts/save-and-publish/result?request_id=" + receipt.path("request_id").asText()))
                .header("Authorization", "Bearer " + token).GET().build();
        var lookedUp = http.send(lookup, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, lookedUp.statusCode(), lookedUp.body());
        assertEquals(receipt, json.readTree(lookedUp.body()).path("data"));
        TenantContext.setTenantId(tenant);
        try {
            var draft = drafts.get(scope, draftId);
            assertEquals(receipt.path("revision").asLong(), draft.revision());
            assertEquals(version, draft.publishedVersionId().toString());
            assertEquals("PUBLISHED", catalog.getVersion(scope, Long.parseLong(version)).status());
            assertEquals(1, catalog.listVersions(scope, definitionId, "PUBLISHED").size());
        } finally { TenantContext.clear(); }
    }
    private void restrictedMember(String tenant, String username, String password, java.util.List<String> permissions) {
        var member = new com.skillforge.core.foundation.persistence.TenantMemberDO();
        member.setId(SnowflakeIds.nextId()); member.setTenantId(Long.parseLong(tenant)); member.setUsername(username);
        member.setRole("member"); member.setPermissions(java.util.List.of()); member.setStatus("active");
        member.setPasswordHash(passwords.encode(password)); member.setCreatedAt(java.time.Instant.now()); members.insert(member);
        jdbc.update("INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name) VALUES(?,?,?)", tenant, member.getId(), username);
        long role = SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(?,?,?,'测试角色','all')", role, tenant, "skill_test_" + role);
        for (String permission : permissions)
            jdbc.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,?,?)", tenant, role, permission);
        jdbc.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,?,?)", tenant, member.getId(), role);
    }
}
