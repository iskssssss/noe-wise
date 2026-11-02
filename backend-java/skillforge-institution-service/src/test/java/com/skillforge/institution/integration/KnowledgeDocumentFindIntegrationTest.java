package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.core.knowledge.dto.response.UploadDocumentResponse;
import com.skillforge.core.knowledge.mapper.KnowledgeDocumentMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 HTTP 绑定、MyBatis 分页 COUNT 与 PG 当前来源过滤；资料为经济模式，不调用模型。 */
@Timeout(90)
class KnowledgeDocumentFindIntegrationTest extends AbstractIntegrationTest {
    private static final String PASSWORD = "SyntheticDocumentFind123";
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeDocumentMapper documents;
    @Autowired KnowledgeDocumentVersionMapper versions;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private String tenant;
    private String slug;
    private String token;
    private String kb;

    @DynamicPropertySource static void noBackgroundExecution(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> false);
        registry.add("skillforge.memory-profile-summary-cron", () -> "-");
    }

    @BeforeEach void fixture() throws Exception {
        slug = "document-find-" + SnowflakeIds.nextId();
        tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        try { createTestTenantAdministrator(tenant, "find-admin", PASSWORD); }
        finally { TenantContext.clear(); }
        token = login(slug, "find-admin");
        kb = createBase();
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void filtersTitleAndStatusBeforeCountAndPaginationAndTreatsMetacharactersLiterally() throws Exception {
        upload("ALPHA One"); upload("alpha Two"); upload("Alpha Three");
        statusFixture(upload("Alpha failed"), "failed");
        statusFixture(upload("Alpha processing"), "processing");
        upload("Budget%_\\Report");
        var page = find("  aLpHa  ", "ready", 2, 2, token);
        assertEquals(3, page.path("total").asInt());
        assertEquals(1, page.path("items").size());
        assertEquals("ALPHA One", page.path("items").get(0).path("title").asText());
        assertEquals(5, find("alpha", null, 1, 20, token).path("total").asInt());
        assertEquals(1, find("ALPHA", "failed", 1, 20, token).path("total").asInt());
        assertEquals(1, find("ALPHA", "processing", 1, 20, token).path("total").asInt());
        var literal = find("%_\\", null, 1, 20, token);
        assertEquals(1, literal.path("total").asInt());
        assertEquals("Budget%_\\Report", literal.path("items").get(0).path("title").asText());
        assertEquals(0, find("%' OR TRUE --", null, 1, 20, token).path("total").asInt());
        var first = find("alpha", "ready", 1, 2, token).path("items");
        assertEquals(2, first.size());
        assertTrue(java.util.stream.StreamSupport.stream(first.spliterator(), false)
                .noneMatch(item -> item.path("id").equals(page.path("items").get(0).path("id"))));
        assertFalse(page.path("items").get(0).has("content"));
        assertFalse(page.path("items").get(0).has("embedding"));
    }

    @Test void onlyCurrentNondeletedDocumentsMatchAndReadScopesStillApply() throws Exception {
        var old = upload("prior-title");
        TenantContext.setTenantId(tenant);
        try { knowledge.updateDocument(tenant, old.documentId(), "current-title", null, old.sourceVersion()); }
        finally { TenantContext.clear(); }
        var deleted = upload("prior-title deleted");
        TenantContext.setTenantId(tenant);
        try { knowledge.deleteDocument(tenant, deleted.documentId(), deleted.sourceVersion()); }
        finally { TenantContext.clear(); }
        assertEquals(0, find("prior-title", null, 1, 20, token).path("total").asInt());
        assertEquals(1, find("CURRENT-title", null, 1, 20, token).path("total").asInt());
        String previous = kb; kb = createBase(); upload("current-title other KB"); kb = previous;
        assertEquals(1, find("current-title", null, 1, 20, token).path("total").asInt());

        String access = "/api/v1/tenant/foundation/access";
        var role = ok("POST", access + "/role", Map.of("code", "find_" + SnowflakeIds.nextId(), "name", "本人文档读取",
                "data_scope", "self", "permissions", List.of("foundation.knowledge.read", "foundation.resource.knowledge.read"),
                "department_ids", List.of()), token);
        String user = "reader-" + SnowflakeIds.nextId();
        ok("POST", access + "/member", Map.of("username", user, "display_name", user, "password", PASSWORD,
                "role_ids", List.of(role.path("id").asText())), token);
        assertEquals(403, request("GET", path("current-title", null, 1, 20), null, login(slug, user)).statusCode());
        String otherSlug = "find-other-" + SnowflakeIds.nextId();
        String otherTenant = createTenant(tenants, plans, otherSlug);
        TenantContext.setTenantId(otherTenant);
        try { createTestTenantAdministrator(otherTenant, "other-admin", PASSWORD); }
        finally { TenantContext.clear(); }
        int denied = request("GET", path("current-title", null, 1, 20), null, login(otherSlug, "other-admin")).statusCode();
        assertTrue(denied == 403 || denied == 404);
    }

    @Test void titleQueryBindsCamelCaseWithUnicodeCodePointLimitAndBlankMeansNoFilter() throws Exception {
        upload("😀".repeat(128));
        assertEquals(1, find("😀".repeat(128), null, 1, 20, token).path("total").asInt());
        assertEquals(400, request("GET", path("😀".repeat(129), null, 1, 20), null, token).statusCode());
        assertEquals(1, find(" \t\u2003 ", null, 1, 20, token).path("total").asInt());
        assertEquals(1, find(null, null, 1, 20, token).path("total").asInt());
    }

    @Test void latestReadySourcesUseCurrentVersionsAndEnforceReadScope() throws Exception {
        var first = upload("最早文档");
        var changed = upload("更新文档");
        TenantContext.setTenantId(tenant);
        String current;
        try { current = knowledge.updateDocument(tenant, changed.documentId(), "新标题", null, changed.sourceVersion()).sourceVersion(); }
        finally { TenantContext.clear(); }
        statusFixture(upload("失败文档"), "failed");
        var deleted = upload("删除文档");
        TenantContext.setTenantId(tenant);
        try { knowledge.deleteDocument(tenant, deleted.documentId(), deleted.sourceVersion()); }
        finally { TenantContext.clear(); }
        String url = "/api/v1/knowledge/bases/documents/latest-ready-sources?kbId=" + kb;
        var sources = ok("GET", url, null, token);
        assertEquals(2, sources.size());
        assertEquals(current, sources.get(0).asText());
        assertEquals(first.sourceVersion(), sources.get(1).asText());
        assertEquals(401, request("GET", url, null, null).statusCode());
        String otherSlug = "ready-other-" + SnowflakeIds.nextId();
        String otherTenant = createTenant(tenants, plans, otherSlug);
        TenantContext.setTenantId(otherTenant);
        try { createTestTenantAdministrator(otherTenant, "other-admin", PASSWORD); }
        finally { TenantContext.clear(); }
        int denied = request("GET", url, null, login(otherSlug, "other-admin")).statusCode();
        assertTrue(denied == 403 || denied == 404);
    }

    @Test void latestReadySourcesAreBoundedToTwoThousandInDatabaseOrder() throws Exception {
        // 用经济索引的合成正文和分块构造合法就绪版本，不调用外部模型。
        long start = SnowflakeIds.nextId() + 10_000_000L, t = Long.parseLong(tenant), base = Long.parseLong(kb);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant);
            jdbc.update("INSERT INTO nw_knowledge_documents(id,tenant_id,kb_id,created_at) SELECT ?+n,?,?,now()+n*interval '1 second' FROM generate_series(1,2001) n", start, t, base);
            jdbc.update("INSERT INTO nw_knowledge_document_contents(id,tenant_id,document_id,format,digest,content) SELECT ?+n,?,?+n,'text',repeat('a',64),'fixture' FROM generate_series(1,2001) n", start, t, start);
            jdbc.update("INSERT INTO nw_knowledge_chunk_sets(id,tenant_id,kb_id,document_id) SELECT ?+n,?,?,?+n FROM generate_series(1,2001) n", start, t, base, start);
            jdbc.update("INSERT INTO nw_knowledge_chunk_contents(id,tenant_id,document_id,format,digest,content) SELECT ?+n,?,?+n,'text',repeat('a',64),'fixture' FROM generate_series(1,2001) n", start, t, start);
            jdbc.update("INSERT INTO nw_knowledge_chunks(id,tenant_id,kb_id,document_id,chunk_set_id,content_id,chunk_index) SELECT ?+n,?,?,?+n,?+n,?+n,0 FROM generate_series(1,2001) n", start, t, base, start, start, start);
            jdbc.update("INSERT INTO nw_knowledge_document_versions(id,tenant_id,kb_id,document_id,source_version,title,source_type,status,chunk_count,content_id,chunk_set_id) SELECT ?+n,?,?,?+n,'fixture-'||n,'fixture','file','ready',1,?+n,?+n FROM generate_series(1,2001) n", start, t, base, start, start, start);
            jdbc.update("UPDATE nw_knowledge_documents SET current_source_version='fixture-'||(id-?) WHERE tenant_id=? AND kb_id=?", start, t, base);
        });
        var sources = ok("GET", "/api/v1/knowledge/bases/documents/latest-ready-sources?kbId=" + kb, null, token);
        assertEquals(2000, sources.size());
        assertEquals("fixture-2001", sources.get(0).asText());
        assertEquals("fixture-2", sources.get(1999).asText());
    }

    /** 仅建立合法 failed/processing 读取夹具；不将合成状态冒称真实索引失败。 */
    private void statusFixture(UploadDocumentResponse source, String status) {
        TenantContext.setTenantId(tenant);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                var version = versions.selectOne(new LambdaQueryWrapper<KnowledgeDocumentVersionDO>()
                        .eq(KnowledgeDocumentVersionDO::getTenantId, Long.valueOf(tenant))
                        .eq(KnowledgeDocumentVersionDO::getDocumentId, source.documentId())
                        .eq(KnowledgeDocumentVersionDO::getSourceVersion, source.sourceVersion()));
                version.setId(SnowflakeIds.nextId()); version.setSourceVersion("find-state-" + version.getId());
                version.setStatus(status); version.setCreatedAt(Instant.now());
                assertEquals(1, versions.insert(version));
                assertEquals(1, documents.compareAndSetCurrentVersion(Long.parseLong(tenant), source.documentId(),
                        source.sourceVersion(), version.getSourceVersion()));
            });
        } finally { TenantContext.clear(); }
    }
    private UploadDocumentResponse upload(String title) {
        TenantContext.setTenantId(tenant);
        try { return knowledge.uploadFile(tenant, Long.valueOf(kb), title,
                new MockMultipartFile("file", "find.txt", "text/plain", "受控读取定位材料".getBytes(StandardCharsets.UTF_8))); }
        finally { TenantContext.clear(); }
    }
    private String createBase() throws Exception {
        return ok("POST", "/api/v1/knowledge/bases", Map.of("name", "文档定位", "indexing_mode", "economical",
                "retrieval_mode", "full_text", "rerank_enabled", false), token).path("id").asText();
    }
    private String path(String title, String status, int page, int size) {
        String result = "/api/v1/knowledge/bases/documents?kbId=" + kb + "&page=" + page + "&page_size=" + size;
        if (title != null) result += "&titleQuery=" + URLEncoder.encode(title, StandardCharsets.UTF_8);
        if (status != null) result += "&status=" + status;
        return result;
    }
    private JsonNode find(String title, String status, int page, int size, String actor) throws Exception {
        return ok("GET", path(title, status, page, size), null, actor);
    }
    private String login(String tenantSlug, String username) throws Exception {
        return ok("POST", "/api/v1/auth/login", Map.of("tenant_slug", tenantSlug, "username", username, "password", PASSWORD), null)
                .path("access_token").asText();
    }
    private HttpResponse<String> request(String method, String path, Object body, String actor) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30));
        if (actor != null) builder.header("Authorization", "Bearer " + actor);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(String method, String path, Object body, String actor) throws Exception {
        var response = request(method, path, body, actor);
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.statusCode() + ": " + response.body());
        return json.readTree(response.body()).path("data");
    }
}
