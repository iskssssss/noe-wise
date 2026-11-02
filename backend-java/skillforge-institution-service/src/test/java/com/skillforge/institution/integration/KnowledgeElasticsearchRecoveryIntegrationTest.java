package com.skillforge.institution.integration;

import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.knowledge.*;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.knowledge.dto.request.KnowledgeSearchRequest;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.knowledge.search.SearchIndexer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;

/** 真实 ES 写失败后从 PG 已完成分块重放投影；不把投影可用性当发布事实。 */
@org.springframework.context.annotation.Import(KnowledgeEmbeddingFixture.Configuration.class)
class KnowledgeElasticsearchRecoveryIntegrationTest extends AbstractIntegrationTest {
    @Container static final GenericContainer<?> ES = new GenericContainer<>("docker.elastic.co/elasticsearch/elasticsearch:8.15.3")
            .withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms384m -Xmx384m").withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200)).withStartupTimeout(Duration.ofMinutes(3));
    @DynamicPropertySource static void elasticsearch(DynamicPropertyRegistry registry) {
        registry.add("skillforge.elasticsearch-enabled", () -> true);
        registry.add("skillforge.elasticsearch-uri", KnowledgeElasticsearchRecoveryIntegrationTest::esUri);
        registry.add("skillforge.elasticsearch-index", () -> "knowledge-recovery");
    }
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeBaseRevisionService revisions;
    @Autowired PlatformModelCatalogService models;
    @Autowired SkillForgeProperties properties;
    @Autowired SearchIndexer search;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired TenantMemberMapper members;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @Test void failedProjectionLeavesCompletePgVersionPublishableAndExplicitReplayRestoresEs() throws Exception {
        ensureFreePlan(plans);
        String slug = "es-recovery-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        String live = properties.getElasticsearchUri();
        try (var embeddings = new KnowledgeEmbeddingFixture(models, tenant)) {
            var member = new TenantMemberDO(); member.setId(SnowflakeIds.nextId()); member.setTenantId(Long.parseLong(tenant));
            member.setUsername("projection-admin"); member.setRole("admin"); member.setPermissions(List.of()); member.setStatus("active");
            member.setPasswordHash(passwords.encode("ProjectionTest123")); member.setCreatedAt(Instant.now()); members.insert(member);
            initializeTenantAdministrator(tenant, member.getId());
            var login = post("/api/v1/auth/login", null, Map.of("tenant_slug", slug, "username", "projection-admin", "password", "ProjectionTest123"));
            assertEquals(200, login.statusCode()); String token = json.readTree(login.body()).path("data").path("access_token").asText();
            var base = knowledge.createBase(tenant, "Projection recovery", null);
            // 真实 ES 索引只读块使 bulk 返回逐项403；服务仍完成 PG 提交。
            RestClient.create().put().uri(live + "/knowledge-recovery/_settings")
                    .body(Map.of("index.blocks.write", true)).retrieve().toBodilessEntity();
            var upload = knowledge.uploadFile(tenant, base.id(), "Committed source", new MockMultipartFile(
                    "file", "recovery.txt", "text/plain", "projectionneedle committed PG authority".getBytes(StandardCharsets.UTF_8)));
            assertTrue(upload.chunks() > 0);
            int modelCalls = embeddings.calls();
            assertTrue(search.search(tenant, base.id().toString(), "projectionneedle", 10, upload.sourceVersion()).isEmpty());
            var request = Map.of("kb_id", base.id(), "source_version", upload.sourceVersion());
            assertEquals(503, post("/api/v1/knowledge/bases/projections/repair", token, request).statusCode());
            assertEquals(404, post("/api/v1/knowledge/bases/projections/repair", token, Map.of("kb_id", base.id(), "source_version", "foreign-source")).statusCode());
            assertEquals(401, post("/api/v1/knowledge/bases/projections/repair", null, request).statusCode());
            var developer = new TenantMemberDO(); developer.setId(SnowflakeIds.nextId()); developer.setTenantId(Long.parseLong(tenant));
            developer.setUsername("projection-developer"); developer.setRole("developer"); developer.setPermissions(List.of()); developer.setStatus("active");
            developer.setPasswordHash(passwords.encode("ProjectionTest123")); developer.setCreatedAt(Instant.now()); members.insert(developer);
            var developerLogin = post("/api/v1/auth/login", null, Map.of("tenant_slug", slug, "username", "projection-developer", "password", "ProjectionTest123"));
            assertEquals(200, developerLogin.statusCode());
            String developerToken = json.readTree(developerLogin.body()).path("data").path("access_token").asText();
            assertEquals(403, post("/api/v1/knowledge/bases/projections/repair", developerToken, request).statusCode());
            String other = createTenant(tenants, plans, "es-foreign-" + SnowflakeIds.nextId());
            TenantContext.setTenantId(other);
            try (var foreignEmbedding = new KnowledgeEmbeddingFixture(models, other)) {
                var foreignBase = knowledge.createBase(other, "Foreign projection", null);
                var foreignUpload = knowledge.uploadFile(other, foreignBase.id(), "Foreign source", new MockMultipartFile(
                        "file", "foreign.txt", "text/plain", "foreign complete PG source".getBytes(StandardCharsets.UTF_8)));
                assertEquals(1, queryCount(jdbc, other, "SELECT count(*) FROM nw_knowledge_document_versions WHERE tenant_id=? AND kb_id=? AND source_version=? AND status='ready'", Long.parseLong(other), foreignBase.id(), foreignUpload.sourceVersion()));
                // 当前Foundation先拒绝跨租户资源权限，不进入核心来源版本查找。
                var crossTenant = post("/api/v1/knowledge/bases/projections/repair", token,
                        Map.of("kb_id", foreignBase.id(), "source_version", foreignUpload.sourceVersion()));
                assertEquals(403, crossTenant.statusCode(), crossTenant.body());
                var crossTenantError = json.readTree(crossTenant.body());
                assertEquals("foundation.resource.denied", crossTenantError.path("code").asText());
                assertTrue(crossTenantError.path("data").isNull());
                assertEquals(404, post("/api/v1/knowledge/bases/projections/repair", token,
                        Map.of("kb_id", base.id(), "source_version", foreignUpload.sourceVersion())).statusCode());
            } finally { TenantContext.setTenantId(tenant); }
            assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_document_versions WHERE tenant_id=? AND document_id=? AND source_version=? AND status='ready' AND chunk_count>0", Long.parseLong(tenant), upload.documentId(), upload.sourceVersion()));
            var draft = revisions.create(tenant, base.id());
            var ready = revisions.markReady(tenant, base.id(), draft.revision(), List.of(upload.sourceVersion()),
                    new KnowledgeCapabilityPolicyRequest(true, false, Set.of("VECTOR", "KEYWORD"), "PINNED", true));
            var published = revisions.publish(tenant, base.id(), ready.revision());
            assertEquals("published", published.status());
            var pgHits = knowledge.search(tenant, base.id().toString(), "projectionneedle", 10,
                    new KnowledgeSearchRequest(base.id().toString(), "projectionneedle", 10, "full_text", false, null, null, false, upload.sourceVersion()));
            assertFalse(pgHits.isEmpty(), "ES写失败期间PG关键词回退仍读到已完成版本");
            var before = queryRows(jdbc, tenant, "SELECT active_revision_id,embedding_model_config_id FROM nw_knowledge_bases WHERE tenant_id=? AND id=?", Long.parseLong(tenant), base.id());
            RestClient.create().put().uri(live + "/knowledge-recovery/_settings")
                    .body(Map.of("index.blocks.write", false)).retrieve().toBodilessEntity();
            // 明确的修复动作：精确重放PG中已提交版本，不重新生成Embedding或发布版本。
            var repaired = post("/api/v1/knowledge/bases/projections/repair", token, request);
            // 前三次真实写失败使现有熔断器OPEN；只通过同一幂等API观察10秒恢复窗口后的探测。
            long recoveryDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            int pendingProbes = 0;
            while (repaired.statusCode() == 503 && System.nanoTime() < recoveryDeadline) {
                pendingProbes++;
                Thread.sleep(500);
                repaired = post("/api/v1/knowledge/bases/projections/repair", token, request);
            }
            assertEquals(200, repaired.statusCode(), repaired.body());
            System.out.println("knowledge_projection_recovery pending503=" + pendingProbes + " finalStatus=" + repaired.statusCode());
            assertEquals(upload.chunks(), json.readTree(repaired.body()).path("data").path("chunk_count").asInt());
            RestClient.create().post().uri(live + "/knowledge-recovery/_refresh").retrieve().toBodilessEntity();
            var recovered = search.search(tenant, base.id().toString(), "projectionneedle", 10, upload.sourceVersion());
            assertEquals(upload.chunks(), recovered.size());
            assertTrue(search.search("999999", base.id().toString(), "projectionneedle", 10, upload.sourceVersion()).isEmpty());
            assertTrue(search.search(tenant, base.id().toString(), "projectionneedle", 10, "unpublished-version").isEmpty());
            assertEquals(before, queryRows(jdbc, tenant, "SELECT active_revision_id,embedding_model_config_id FROM nw_knowledge_bases WHERE tenant_id=? AND id=?", Long.parseLong(tenant), base.id()));
            assertEquals(modelCalls, embeddings.calls(), "修复ES投影不得调用Embedding");
        } finally { properties.setElasticsearchUri(live); TenantContext.clear(); }
    }
    private static String esUri() { return "http://" + ES.getHost() + ":" + ES.getMappedPort(9200); }
    private HttpResponse<String> post(String path, String token, Map<String, ?> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
