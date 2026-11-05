package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.knowledge.EmbeddingProvider;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.core.knowledge.search.SearchIndexer;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.service.KnowledgeManagementService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 真实 HTTP、管理员、数据库与能力发布事务；经济索引不调用模型。 */
class KnowledgeRevisionPublishHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String PATH = "/api/v1/knowledge/bases/revisions/prepare-and-publish";
    private static final String SAVED_PATH = "/api/v1/knowledge/bases/revisions/publish-with-receipt";
    private static final String PASSWORD = "KnowledgePublishTest123";
    @DynamicPropertySource static void workers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> false);
    }
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired KnowledgeManagementService management;
    @Autowired KnowledgeService knowledge;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired javax.sql.DataSource receiptDataSource;
    @MockitoBean EmbeddingProvider embedding;
    @MockitoBean SearchIndexer indexer;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void savedRevisionReceiptNeedsOnlyPublishAndSurvivesLaterPublication() throws Exception {
        var f = savedFixture();
        var permissions = List.of("foundation.knowledge.read", "foundation.knowledge.publish",
                "foundation.resource.knowledge.read", "foundation.resource.knowledge.manage");
        String publisher = member(f.admin(), f.slug(), "saved_publisher", permissions);
        String other = member(f.admin(), f.slug(), "saved_other", permissions);
        String denied = member(f.admin(), f.slug(), "saved_reader", List.of("foundation.knowledge.read",
                "foundation.resource.knowledge.read", "foundation.resource.knowledge.manage"));
        long revision = prepareSaved(f.admin(), f.kb(), List.of(f.source())).path("revision").asLong();
        String requestId = UUID.randomUUID().toString();
        var command = savedCommand(requestId, f.kb(), revision);
        var unauthorized = call(null, "POST", SAVED_PATH, command, 401);
        assertEquals("KNOWLEDGE_PUBLISH_PRE_DISPATCH_REJECTED", json.readTree(unauthorized.body()).path("code").asText());
        var forbidden = call(denied, "POST", SAVED_PATH, command, 403);
        assertEquals("KNOWLEDGE_PUBLISH_PRE_DISPATCH_REJECTED", json.readTree(forbidden.body()).path("code").asText());
        call(f.admin(), "GET", savedResult(requestId), null, 204);
        var success = data(call(publisher, "POST", SAVED_PATH, command, 200));
        assertEquals("SUCCEEDED", success.path("status").asText());
        assertEquals(requestId, success.path("request_id").asText());
        assertEquals(revision, success.path("revision").path("revision").asLong());
        assertEquals("published", success.path("revision").path("status").asText());
        assertEquals(success, data(call(publisher, "GET", savedResult(requestId), null, 200)));
        assertEquals(success, data(call(publisher, "POST", SAVED_PATH, command, 200)));
        call(other, "GET", savedResult(requestId), null, 204);
        call(other, "POST", SAVED_PATH, command, 409);
        call(publisher, "POST", SAVED_PATH, savedCommand(requestId, f.kb(), revision + 1), 409);
        call(f.admin(), "POST", PATH, command(requestId, f.kb(), List.of(f.source())), 409);

        long nextRevision = prepareSaved(f.admin(), f.kb(), List.of(f.nextSource())).path("revision").asLong();
        var newer = data(call(publisher, "POST", SAVED_PATH,
                savedCommand(UUID.randomUUID().toString(), f.kb(), nextRevision), 200));
        assertEquals("SUCCEEDED", newer.path("status").asText());
        assertEquals(success, data(call(publisher, "GET", savedResult(requestId), null, 200)));
        assertEquals(success, data(call(publisher, "POST", SAVED_PATH, command, 200)));
        var base = data(call(f.admin(), "GET", "/api/v1/knowledge/bases/detail?kbId=" + f.kb(), null, 200));
        assertEquals(newer.path("revision").path("id"), base.path("active_revision_id"));
        var listed = data(call(f.admin(), "GET", "/api/v1/knowledge/bases/revisions?kbId=" + f.kb(), null, 200));
        assertTrue(java.util.stream.StreamSupport.stream(listed.path("items").spliterator(), false)
                .anyMatch(row -> row.path("revision").asLong() == revision && "archived".equals(row.path("status").asText())));

        String rejectedId = UUID.randomUUID().toString();
        var rejected = data(call(publisher, "POST", SAVED_PATH, savedCommand(rejectedId, f.kb(), revision), 200));
        assertEquals("REJECTED", rejected.path("status").asText());
        assertFalse(rejected.path("error_message").asText().isBlank());
        assertEquals(rejected, data(call(publisher, "GET", savedResult(rejectedId), null, 200)));
        assertEquals(rejected, data(call(publisher, "POST", SAVED_PATH, savedCommand(rejectedId, f.kb(), revision), 200)));
        String crossKind = UUID.randomUUID().toString();
        call(f.admin(), "POST", SAVED_PATH, savedCommand(crossKind, f.kb(), revision), 200);
        call(f.admin(), "POST", PATH, command(crossKind, f.kb(), List.of(f.source())), 409);
        assertEquals(2, revisions(f.tenant(), f.kb()));
        assertReceiptRls(f.tenant(), requestId);
        verifyNoInteractions(embedding);
    }

    @Test void savedPublishDoesNotGrantOperationOrObjectReadPermission() throws Exception {
        var f = savedFixture();
        long revision = prepareSaved(f.admin(), f.kb(), List.of(f.source())).path("revision").asLong();
        String noRead = member(f.admin(), f.slug(), "saved_publish_only", List.of("foundation.knowledge.publish",
                "foundation.resource.knowledge.manage"));
        String requestId = UUID.randomUUID().toString();
        assertEquals("SUCCEEDED", data(call(noRead, "POST", SAVED_PATH, savedCommand(requestId, f.kb(), revision), 200)).path("status").asText());
        call(noRead, "GET", savedResult(requestId), null, 403);
        String noObjectRead = member(f.admin(), f.slug(), "saved_no_object_read", List.of("foundation.knowledge.read",
                "foundation.knowledge.publish", "foundation.resource.knowledge.manage"));
        String rejectedId = UUID.randomUUID().toString();
        assertEquals("REJECTED", data(call(noObjectRead, "POST", SAVED_PATH, savedCommand(rejectedId, f.kb(), revision), 200)).path("status").asText());
        call(noObjectRead, "GET", savedResult(rejectedId), null, 403);
    }

    @Test void savedRevisionReceiptFailureRollsBackPublicationAndNeverRedispatchesProcessing() throws Exception {
        var f = savedFixture();
        long revision = prepareSaved(f.admin(), f.kb(), List.of(f.source())).path("revision").asLong();
        long versions = queryCount(jdbc, f.tenant(), "SELECT count(*) FROM nw_capability_versions WHERE tenant_id=?", Long.parseLong(f.tenant()));
        String requestId = UUID.randomUUID().toString();
        var command = savedCommand(requestId, f.kb(), revision);
        String constraint = "test_saved_publish_" + SnowflakeIds.nextId();
        jdbc.execute("ALTER TABLE nw_knowledge_revision_publish_receipts ADD CONSTRAINT " + constraint
                + " CHECK (request_id <> '" + requestId + "'::uuid OR status <> 'SUCCEEDED')");
        try { call(f.admin(), "POST", SAVED_PATH, command, 500); }
        finally { jdbc.execute("ALTER TABLE nw_knowledge_revision_publish_receipts DROP CONSTRAINT " + constraint); }
        var processing = data(call(f.admin(), "GET", savedResult(requestId), null, 200));
        assertEquals("PROCESSING", processing.path("status").asText());
        assertEquals(requestId, processing.path("request_id").asText());
        assertEquals(processing, data(call(f.admin(), "POST", SAVED_PATH, command, 200)));
        assertEquals(1, queryCount(jdbc, f.tenant(), "SELECT count(*) FROM nw_knowledge_base_revisions WHERE tenant_id=? AND kb_id=? AND revision=? AND status='ready' AND capability_version_id IS NULL", Long.parseLong(f.tenant()), f.kb(), revision));
        assertEquals(1, queryCount(jdbc, f.tenant(), "SELECT count(*) FROM nw_knowledge_bases WHERE tenant_id=? AND id=? AND active_revision_id IS NULL", Long.parseLong(f.tenant()), f.kb()));
        assertEquals(versions, queryCount(jdbc, f.tenant(), "SELECT count(*) FROM nw_capability_versions WHERE tenant_id=?", Long.parseLong(f.tenant())));
    }

    private record SavedFixture(String tenant, String slug, String admin, long kb, String source, String nextSource) { }

    private SavedFixture savedFixture() throws Exception {
        String slug = "saved-knowledge-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        long member = createTestTenantAdministrator(tenant, "saved-admin", PASSWORD);
        var fixture = withAuthenticatedMember(member, () -> {
            var base = management.create(tenant, "已准备修订知识库", null, "economical", "full_text", false);
            var first = knowledge.uploadFile(tenant, base.id(), "原文档", new MockMultipartFile("file", "first.txt",
                    "text/plain", "第一份发布内容".getBytes(StandardCharsets.UTF_8)));
            var next = knowledge.uploadFile(tenant, base.id(), "新文档", new MockMultipartFile("file", "next.txt",
                    "text/plain", "后续修订发布内容".getBytes(StandardCharsets.UTF_8)));
            return Map.of("kb", base.id(), "source", first.sourceVersion(), "next", next.sourceVersion());
        });
        TenantContext.clear();
        String token = data(call(null, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "saved-admin", "password", PASSWORD), 200)).path("access_token").asText();
        return new SavedFixture(tenant, slug, token, (Long) fixture.get("kb"), (String) fixture.get("source"), (String) fixture.get("next"));
    }

    private JsonNode prepareSaved(String token, long kb, List<String> sources) throws Exception {
        var draft = data(call(token, "POST", "/api/v1/knowledge/bases/revisions", Map.of("kb_id", kb), 200));
        return data(call(token, "POST", "/api/v1/knowledge/bases/revisions/ready",
                Map.of("kb_id", kb, "revision", draft.path("revision").asLong(),
                        "payload", command(UUID.randomUUID().toString(), kb, sources).get("payload")), 200));
    }

    private Map<String, Object> savedCommand(String requestId, long kb, long revision) {
        return Map.of("request_id", requestId, "kb_id", Long.toString(kb), "revision", revision);
    }

    private String savedResult(String requestId) { return SAVED_PATH + "/result?request_id=" + requestId; }

    private void assertReceiptRls(String tenant, String requestId) throws Exception {
        String role = "knowledge_receipt_reader_" + SnowflakeIds.nextId();
        try (var connection = receiptDataSource.getConnection()) {
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
                sql.execute("GRANT USAGE ON SCHEMA public TO " + role);
                sql.execute("GRANT SELECT,INSERT ON public.nw_knowledge_revision_publish_receipts TO " + role);
            }
            connection.setAutoCommit(false);
            try {
                try (var sql = connection.createStatement()) {
                    sql.execute("SET LOCAL ROLE " + role);
                    try (var flags = sql.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                        assertTrue(flags.next()); assertFalse(flags.getBoolean(1));
                    }
                }
                try (var scope = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    scope.setString(1, tenant); scope.execute();
                }
                try (var query = connection.prepareStatement("SELECT count(*) FROM nw_knowledge_revision_publish_receipts WHERE request_id=?::uuid")) {
                    query.setString(1, requestId);
                    try (var rows = query.executeQuery()) { rows.next(); assertEquals(1, rows.getInt(1)); }
                    try (var scope = connection.createStatement()) { scope.execute("SELECT set_config('app.tenant_id','0',true)"); }
                    try (var rows = query.executeQuery()) { rows.next(); assertEquals(0, rows.getInt(1)); }
                }
                try (var insert = connection.prepareStatement("INSERT INTO nw_knowledge_revision_publish_receipts(tenant_id,request_id,kb_id,actor_ref,request_fingerprint,status) VALUES (?::bigint,?::uuid,1,'rls',repeat('a',64),'PROCESSING')")) {
                    insert.setString(1, tenant); insert.setString(2, UUID.randomUUID().toString());
                    assertThrows(java.sql.SQLException.class, insert::executeUpdate);
                }
            } finally {
                connection.rollback(); connection.setAutoCommit(true);
                try (var sql = connection.createStatement()) {
                    sql.execute("DROP OWNED BY " + role); sql.execute("DROP ROLE " + role);
                }
            }
        }
    }

    @Test void creationReceiptIsAtomicIdempotentAndOwnedByOriginalActor() throws Exception {
        String slug = "knowledge-receipt-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        createTestTenantAdministrator(tenant, "knowledge-admin", PASSWORD);
        TenantContext.clear();
        String token = data(call(null, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "knowledge-admin", "password", PASSWORD), 200)).path("access_token").asText();
        String other = member(token, slug, "other_reader", List.of("foundation.knowledge.read", "foundation.knowledge.create"));
        String id = UUID.randomUUID().toString(), path = "/api/v1/knowledge/bases/create-with-receipt";
        var payload = Map.of("name", "回执知识库", "indexing_mode", "economical", "chunk_size", 640);
        var command = Map.of("request_id", id, "payload", payload);
        call(token, "GET", path + "/result?request_id=" + id, null, 204);
        var result = data(call(token, "POST", path, command, 200));
        assertEquals(id, result.path("request_id").asText());
        assertTrue(result.path("kb_id").isTextual());
        assertEquals(result, data(call(token, "GET", path + "/result?request_id=" + id, null, 200)));
        assertEquals(result, data(call(token, "POST", path, command, 200)));
        call(other, "GET", path + "/result?request_id=" + id, null, 204);
        call(token, "POST", path, Map.of("request_id", id, "payload", Map.of("name", "不同内容", "indexing_mode", "economical")), 409);
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_bases WHERE tenant_id=? AND name=?", Long.parseLong(tenant), "回执知识库"));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_create_receipts WHERE tenant_id=? AND request_id=?::uuid AND kb_id=?", Long.parseLong(tenant), id, result.path("kb_id").asLong()));
        String rejectedId = UUID.randomUUID().toString();
        call(token, "POST", path, Map.of("request_id", rejectedId, "payload", Map.of("name", " ", "indexing_mode", "economical")), 422);
        call(token, "GET", path + "/result?request_id=" + rejectedId, null, 204);
        assertEquals(0, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_create_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.parseLong(tenant), rejectedId));
        String concurrentId = UUID.randomUUID().toString();
        var concurrent = Map.of("request_id", concurrentId, "payload", Map.of("name", "并发创建", "indexing_mode", "economical"));
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return data(call(token, "POST", path, concurrent, 200)); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return data(call(token, "POST", path, concurrent, 200)); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        assertEquals(first.get(), second.get());
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_bases WHERE tenant_id=? AND name=?", Long.parseLong(tenant), "并发创建"));
        verifyNoInteractions(embedding);
    }

    @Test void creationSavesAdvancedSettingsAtomicallyAndChecksUpdatePermission() throws Exception {
        String slug = "knowledge-create-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        createTestTenantAdministrator(tenant, "knowledge-admin", PASSWORD);
        TenantContext.clear();
        String token = data(call(null, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "knowledge-admin", "password", PASSWORD), 200)).path("access_token").asText();
        String createOnly = member(token, slug, "knowledge_create_only", List.of("foundation.knowledge.create"));
        var settings = Map.of("name", "一次配置", "indexing_mode", "economical", "retrieval_mode", "full_text",
                "rerank_enabled", false, "chunk_size", 640, "chunk_overlap", 40, "top_k", 7);
        call(createOnly, "POST", "/api/v1/knowledge/bases", settings, 403);
        assertEquals(0, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_bases WHERE tenant_id=? AND name=?", Long.parseLong(tenant), "一次配置"));
        var created = data(call(token, "POST", "/api/v1/knowledge/bases", settings, 200));
        var detail = data(call(token, "GET", "/api/v1/knowledge/bases/detail?kbId=" + created.path("id").asText(), null, 200));
        assertEquals(640, detail.path("chunk_size").asInt());
        assertEquals(40, detail.path("chunk_overlap").asInt());
        assertEquals(7, detail.path("top_k").asInt());
        jdbc.execute("ALTER TABLE nw_knowledge_bases ADD CONSTRAINT test_atomic_creation CHECK (chunk_size IS NULL OR chunk_size <> 333)");
        try {
            call(token, "POST", "/api/v1/knowledge/bases", Map.of("name", "失败时回滚", "indexing_mode", "economical",
                    "retrieval_mode", "full_text", "chunk_size", 333), 500);
            assertEquals(0, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_bases WHERE tenant_id=? AND name=?", Long.parseLong(tenant), "失败时回滚"));
        } finally { jdbc.execute("ALTER TABLE nw_knowledge_bases DROP CONSTRAINT test_atomic_creation"); }
        verifyNoInteractions(embedding);
    }

    @Test void originalReceiptAndAtomicDuplicateRejection() throws Exception {
        String slug = "knowledge-publish-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        long member = createTestTenantAdministrator(tenant, "knowledge-admin", PASSWORD);
        var fixture = withAuthenticatedMember(member, () -> {
            var base = management.create(tenant, "经济知识库", null, "economical", "full_text", false);
            // 显式构造超出 JS 安全整数的对象身份；默认 Snowflake 生成值仍在安全范围。
            long largeKb = 9_007_199_254_740_997L;
            jdbc.update("INSERT INTO nw_knowledge_bases(id,tenant_id,name,status,indexing_mode,retrieval_mode,rerank_enabled) VALUES(?,?,?,'active','economical','full_text',false)",
                    largeKb, Long.parseLong(tenant), "大身份知识库");
            var doc = knowledge.uploadFile(tenant, largeKb, "文档", new MockMultipartFile("file", "a.txt",
                    "text/plain", "用于知识库发布的正文".getBytes(StandardCharsets.UTF_8)));
            return Map.of("kb", largeKb, "source", doc.sourceVersion());
        });
        TenantContext.clear();
        String token = data(call(null, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "knowledge-admin", "password", PASSWORD), 200)).path("access_token").asText();
        String partial = member(token, slug, "partial", List.of("foundation.knowledge.read", "foundation.knowledge.create",
                "foundation.knowledge.update", "foundation.resource.knowledge.read", "foundation.resource.knowledge.manage"));
        String other = member(token, slug, "other", List.of("foundation.knowledge.read", "foundation.knowledge.create",
                "foundation.knowledge.update", "foundation.knowledge.publish", "foundation.resource.knowledge.read", "foundation.resource.knowledge.manage"));
        long kb = (Long) fixture.get("kb"); String source = (String) fixture.get("source");
        String id = UUID.randomUUID().toString();
        var command = command(id, kb, List.of(source));
        String deniedId = UUID.randomUUID().toString();
        var denied = call(partial, "POST", PATH, command(deniedId, kb, List.of(source)), 403);
        assertEquals("KNOWLEDGE_PUBLISH_PRE_DISPATCH_REJECTED", json.readTree(denied.body()).path("code").asText());
        call(token, "GET", PATH + "/result?requestId=" + deniedId, null, 204);
        var unauthorized = call(null, "POST", PATH, command, 401);
        assertEquals("KNOWLEDGE_PUBLISH_PRE_DISPATCH_REJECTED", json.readTree(unauthorized.body()).path("code").asText());
        call(token, "GET", PATH + "/result?requestId=" + id, null, 204);
        String limitedId = UUID.randomUUID().toString();
        long window = System.currentTimeMillis() / 60_000;
        var keys = List.of("rate:" + tenant + ":" + member + ":" + window,
                "rate:" + tenant + ":" + member + ":" + (window + 1));
        try {
            for (String key : keys) redis.opsForValue().set(key, "1000000000", java.time.Duration.ofMinutes(2));
            var limited = call(token, "POST", PATH, command(limitedId, kb, List.of(source)), 429);
            assertEquals("KNOWLEDGE_PUBLISH_PRE_DISPATCH_REJECTED", json.readTree(limited.body()).path("code").asText());
        } finally { redis.delete(keys); }
        call(token, "GET", PATH + "/result?requestId=" + limitedId, null, 204);
        var missingPolicy = Map.of("request_id", UUID.randomUUID().toString(), "kb_id", kb,
                "payload", Map.of("source_versions", List.of(source), "policy", Map.of("allowed_operators", List.of("KEYWORD"))));
        var invalid = call(token, "POST", PATH, missingPolicy, 422);
        assertEquals("KNOWLEDGE_PUBLISH_PRE_DISPATCH_REJECTED", json.readTree(invalid.body()).path("code").asText());
        var success = data(call(token, "POST", PATH, command, 200));
        assertEquals("SUCCEEDED", success.path("status").asText());
        assertEquals("published", success.path("revision").path("status").asText());
        var published = success.path("revision");
        assertTrue(published.path("id").isTextual());
        assertTrue(published.path("kb_id").isTextual());
        assertTrue(kb > 9_007_199_254_740_991L);
        assertEquals(Long.toString(kb), published.path("kb_id").asText());
        var detail = data(call(token, "GET", "/api/v1/knowledge/bases/detail?kbId=" + kb, null, 200));
        assertTrue(detail.path("id").isTextual());
        assertEquals(Long.toString(kb), detail.path("id").asText());
        assertTrue(detail.path("active_revision_id").isTextual());
        assertEquals(published.path("id"), detail.path("active_revision_id"));
        var bases = data(call(token, "GET", "/api/v1/knowledge/bases", null, 200));
        assertTrue(java.util.stream.StreamSupport.stream(bases.path("items").spliterator(), false)
                .anyMatch(item -> item.path("id").isTextual() && Long.toString(kb).equals(item.path("id").asText())));
        var listed = data(call(token, "GET", "/api/v1/knowledge/bases/revisions?kbId=" + kb, null, 200));
        assertEquals(published.path("id"), listed.path("items").get(0).path("id"));
        assertEquals(success, data(call(token, "GET", PATH + "/result?requestId=" + id, null, 200)));
        call(other, "GET", PATH + "/result?requestId=" + id, null, 204);
        call(other, "POST", PATH, command, 409);
        assertEquals(success, data(call(token, "POST", PATH, command, 200)));
        call(token, "POST", PATH, command(id, kb, List.of("different")), 409);
        assertEquals(1, revisions(tenant, kb));
        String duplicateId = UUID.randomUUID().toString();
        var duplicate = data(call(token, "POST", PATH, command(duplicateId, kb, List.of(source)), 200));
        assertEquals("REJECTED", duplicate.path("status").asText());
        assertEquals(duplicate, data(call(token, "GET", PATH + "/result?requestId=" + duplicateId, null, 200)));
        assertEquals(1, revisions(tenant, kb), "重复内容冲突必须回滚新修订");
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_base_revisions WHERE tenant_id=? AND kb_id=? AND status='published'", Long.parseLong(tenant), kb));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_revision_publish_receipts WHERE tenant_id=? AND kb_id=? AND status='REJECTED'", Long.parseLong(tenant), kb));
        verifyNoInteractions(embedding);
    }
    private long revisions(String tenant, long kb) {
        return queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_base_revisions WHERE tenant_id=? AND kb_id=?", Long.parseLong(tenant), kb);
    }
    private Map<String, Object> command(String id, long kb, List<String> sources) {
        return Map.of("request_id", id, "kb_id", kb, "payload", Map.of("source_versions", sources,
                "policy", Map.of("model_invocable", true, "user_invocable", false, "allowed_operators", List.of("KEYWORD"),
                        "freshness_policy", "PINNED", "citation_required", true)));
    }
    private String member(String admin, String slug, String name, List<String> permissions) throws Exception {
        String access = "/api/v1/tenant/foundation/access";
        String role = data(call(admin, "POST", access + "/role", Map.of("code", name + "_" + SnowflakeIds.nextId(),
                "name", name, "data_scope", "all", "permissions", permissions, "department_ids", List.of()), 200)).path("id").asText();
        call(admin, "POST", access + "/member", Map.of("username", name, "display_name", name, "password", PASSWORD,
                "role_ids", List.of(role)), 200);
        return data(call(null, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", name, "password", PASSWORD), 200)).path("access_token").asText();
    }
    private HttpResponse<String> call(String token, String method, String path, Object body, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body()); return response;
    }
    private JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).path("data"); }
}
