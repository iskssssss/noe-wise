package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

/** 隔离浏览器、机构服务和数据库验证原发布回执恢复。 */
@Tag("workflow-publish-browser")
class WorkflowPublishBrowserE2ETest extends AbstractIntegrationTest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("workflow-publish-browser-");
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

    @Test void browserRecoversOriginalPublicationAfterResponseLoss() throws Exception {
        String slug = "workflow-publish-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        String password = "WorkflowPublishBrowser123";
        TenantContext.setTenantId(tenant);
        try { createTestTenantAdministrator(tenant, "workflow-publish-admin", password); }
        finally { TenantContext.clear(); }
        var http = HttpClient.newHttpClient();
        var login = request(http, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "workflow-publish-admin", "password", password), null);
        String token = login.path("data").path("access_token").asText();
        assertFalse(token.isBlank());
        var graph = Map.of("version", "1.0", "entry", "start",
                "nodes", List.of(Map.of("id", "start", "type", "start"),
                        Map.of("id", "reply", "type", "output", "message", "测试输出"),
                        Map.of("id", "end", "type", "end")),
                "edges", List.of(Map.of("from", "start", "to", "reply"), Map.of("from", "reply", "to", "end")),
                "meta", Map.of("name", "browser-publish", "display_name", "原名称", "version", "1.0.0",
                        "capability", Map.of("model_invocable", true, "user_invocable", true,
                                "input_schema", Map.of("type", "object", "properties", Map.of()),
                                "output_schema", Map.of("type", "object", "properties", Map.of()),
                                "supports_hitl", false, "export_fields", List.of(), "timeout_ms", 30000,
                                "concurrency_safe", false)));
        request(http, "POST", "/api/v1/builder/workflows", Map.of("workflow_id", "browser-publish", "definition", graph), token);
        FRONTEND.bindBackend("http://127.0.0.1:" + port);
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("frontend-admin/package.json"))) root = root.getParent();
        assertNotNull(root);
        Path admin = root.resolve("frontend-admin"), evidence = FRONTEND.evidence();
        var command = new ProcessBuilder("node", admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "--config=playwright.workflow-publish.config.ts");
        command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", evidence.toString());
        env.put("E2E_TENANT_SLUG", slug); env.put("E2E_PASSWORD", password);
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
            assertEquals(0, process.exitValue(), () -> "浏览器验收失败：" + evidence);
        }
        var result = json.readTree(Files.readString(evidence.resolve("workflow-publish.json")));
        String requestId = result.path("command").path("request_id").asText();
        assertEquals("SUCCEEDED", result.path("receipt").path("status").asText());
        assertEquals(1L, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_workflow_publish_receipts WHERE request_id=?::uuid", requestId));
        assertEquals(1L, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_workflow_definition_versions WHERE tenant_id=?", Long.valueOf(tenant)));
        var savedDraft = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/builder/workflows/draft?workflowId=browser-publish"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, savedDraft.statusCode(), savedDraft.body());
        assertEquals(result.path("command").path("definition"), json.readTree(savedDraft.body()).path("data").path("definition"));
        var savedLayout = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/builder/workflows/draft/snapshot?workflowId=browser-publish"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, savedLayout.statusCode(), savedLayout.body());
        assertEquals(result.path("command").path("layout"), json.readTree(savedLayout.body()).path("data").path("layout"));
    }

    private com.fasterxml.jackson.databind.JsonNode request(HttpClient http, String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        var response = http.send(builder.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
}
