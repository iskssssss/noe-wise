package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.knowledge.EmbeddingProvider;
import com.skillforge.core.knowledge.search.SearchIndexer;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.service.KnowledgeManagementService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 独立浏览器、机构管理员与真实 HTTP/PG；经济索引和受控禁用的外部索引不调用模型。 */
@Tag("knowledge-publish-browser")
class KnowledgeRevisionPublishBrowserE2ETest extends AbstractIntegrationTest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("knowledge-publish-browser-");
    @DynamicPropertySource static void origins(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
        registry.add("agent-runtime.workers.auto-startup", () -> false);
    }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired KnowledgeManagementService management;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean EmbeddingProvider embedding;
    @MockitoBean SearchIndexer indexer;
    @LocalServerPort int port;

    @Test void browserPublishesSelectedReadySource() throws Exception {
        String slug = "knowledge-browser-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug), password = "KnowledgeBrowser123";
        TenantContext.setTenantId(tenant);
        long kb;
        try {
            long member = createTestTenantAdministrator(tenant, "knowledge-browser-admin", password);
            kb = withAuthenticatedMember(member, () -> {
                var base = management.create(tenant, "浏览器经济知识库", null, "economical", "full_text", false);
                return base.id();
            });
        } finally { TenantContext.clear(); }
        FRONTEND.bindBackend("http://127.0.0.1:" + port);
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin"), evidence = FRONTEND.evidence();
        var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "--config=playwright.knowledge-publish.config.ts");
        command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", evidence.toString());
        env.put("E2E_TENANT_SLUG", slug); env.put("E2E_USERNAME", "knowledge-browser-admin"); env.put("E2E_PASSWORD", password);
        env.put("E2E_KB_ID", Long.toString(kb));
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
            assertEquals(0, process.exitValue(), () -> "浏览器验收失败：" + evidence);
        }
        var receipt = json.readTree(Files.readString(evidence.resolve("knowledge-publish-receipt.json"))).path("data");
        var upload = json.readTree(Files.readString(evidence.resolve("knowledge-upload-receipt.json"))).path("data");
        var mount = json.readTree(Files.readString(evidence.resolve("knowledge-agent-mount.json")));
        long agentId = mount.path("agentId").asLong();
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_agent_capability_mounts WHERE tenant_id=? AND agent_id=? AND capability_type='KNOWLEDGE_SOURCE'", Long.parseLong(tenant), agentId));
        assertEquals("SUCCEEDED", upload.path("status").asText());
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_upload_receipts WHERE tenant_id=? AND kb_id=? AND request_id=?::uuid AND status='SUCCEEDED' AND document_id=?",
                Long.parseLong(tenant), kb, upload.path("request_id").asText(), upload.path("document").path("document_id").asLong()));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_documents WHERE tenant_id=? AND kb_id=?",
                Long.parseLong(tenant), kb));
        assertEquals("SUCCEEDED", receipt.path("status").asText());
        assertEquals("published", receipt.path("revision").path("status").asText());
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_revision_publish_receipts WHERE tenant_id=? AND kb_id=? AND request_id=?::uuid AND status='SUCCEEDED'",
                Long.parseLong(tenant), kb, receipt.path("request_id").asText()));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_base_revisions WHERE tenant_id=? AND kb_id=? AND status='published'",
                Long.parseLong(tenant), kb));
        verifyNoInteractions(embedding);
    }
}
