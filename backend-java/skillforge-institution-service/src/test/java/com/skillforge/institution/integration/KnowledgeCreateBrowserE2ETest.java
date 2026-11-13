package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 隔离浏览器、Spring 和 PG；经济索引不调用模型。 */
@Tag("knowledge-create-browser")
class KnowledgeCreateBrowserE2ETest extends AbstractIntegrationTest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("knowledge-create-browser-");
    @DynamicPropertySource static void origins(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
        registry.add("agent-runtime.workers.auto-startup", () -> false);
    }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @Test void creationNavigatesToItsPersistedKnowledgeBase() throws Exception {
        String slug = "knowledge-create-browser-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        String password = "KnowledgeCreateBrowser123";
        TenantContext.setTenantId(tenant);
        try { createTestTenantAdministrator(tenant, "knowledge-create-admin", password); }
        finally { TenantContext.clear(); }
        FRONTEND.bindBackend("http://127.0.0.1:" + port);
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("frontend-admin/package.json"))) root = root.getParent();
        assertNotNull(root);
        Path admin = root.resolve("frontend-admin");
        Path evidence = FRONTEND.evidence();
        var command = new ProcessBuilder("node", admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "--config=playwright.knowledge-create.config.ts");
        command.directory(admin.toFile());
        command.redirectErrorStream(true);
        command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment();
        env.put("E2E_BASE_URL", FRONTEND.baseUrl());
        env.put("E2E_ARTIFACT_DIR", evidence.toString());
        env.put("E2E_TENANT_SLUG", slug);
        env.put("E2E_USERNAME", "knowledge-create-admin");
        env.put("E2E_PASSWORD", password);
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) {
                owned.capture();
                process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            owned.capture();
            assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
            assertEquals(0, process.exitValue(), () -> "浏览器验收失败：" + evidence);
        }
        var receipt = json.readTree(Files.readString(evidence.resolve("creation-receipt.json")));
        var rows = queryRows(jdbc, tenant, "SELECT id, name, status, indexing_mode, chunk_size, chunk_overlap, top_k FROM nw_knowledge_bases WHERE tenant_id=? AND id=?",
                Long.valueOf(tenant), receipt.path("data").path("kb_id").asLong());
        assertEquals(1, rows.size());
        assertEquals("浏览器创建知识库", rows.getFirst().get("name"));
        assertEquals("archived", rows.getFirst().get("status"));
        assertEquals("economical", rows.getFirst().get("indexing_mode"));
        assertEquals(640, rows.getFirst().get("chunk_size"));
        assertEquals(40, rows.getFirst().get("chunk_overlap"));
        assertEquals(7, rows.getFirst().get("top_k"));
        var lifecycle = json.readTree(Files.readString(evidence.resolve("knowledge-lifecycle.json")));
        assertFalse(lifecycle.path("initialCanDelete").asBoolean());
        assertEquals(1, lifecycle.path("documentDeletes").asInt());
        assertEquals(0, lifecycle.path("documentCount").asLong());
        assertEquals(0, lifecycle.path("chunkCount").asLong());
        assertFalse(lifecycle.path("canDelete").asBoolean());
        assertEquals(1, lifecycle.path("archiveWrites").asInt());
        assertEquals(0, lifecycle.path("deleteWrites").asInt());
        assertEquals("archived", lifecycle.path("status").asText());
        assertEquals(1, queryCount(jdbc, tenant,
                "SELECT count(*) FROM nw_knowledge_documents WHERE tenant_id=? AND kb_id=?",
                Long.valueOf(tenant), receipt.path("data").path("kb_id").asLong()));
        assertEquals(1, queryCount(jdbc, tenant,
                "SELECT count(*) FROM nw_knowledge_documents WHERE tenant_id=? AND kb_id=? AND deleted_at IS NOT NULL",
                Long.valueOf(tenant), receipt.path("data").path("kb_id").asLong()));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_knowledge_bases WHERE tenant_id=? AND name=? AND id=?",
                Long.valueOf(tenant), "恢复知识库", receipt.path("recovered").path("kb_id").asLong()));
    }
}
