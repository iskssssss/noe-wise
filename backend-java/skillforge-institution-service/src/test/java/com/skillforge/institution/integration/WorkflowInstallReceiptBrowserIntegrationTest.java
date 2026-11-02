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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class WorkflowInstallReceiptBrowserIntegrationTest extends AbstractIntegrationTest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("workflow-install-receipt-browser-");
    @AfterAll static void closeFrontend() { FRONTEND.close(); }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> false);
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @Test void recoversOriginalInstallationAfterResponseLoss() throws Exception {
        String slug = "workflow-receipt-browser-" + SnowflakeIds.nextId(), password = "WorkflowBrowser123";
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        try { createTestTenantAdministrator(tenant, "workflow-admin", password); }
        finally { TenantContext.clear(); }
        HttpClient http = HttpClient.newHttpClient();
        var login = request(http, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug, "username", "workflow-admin", "password", password), null, null);
        String token = login.path("data").path("access_token").asText();
        var graph = Map.of("entry", "start", "nodes", List.of(Map.of("id", "start", "type", "start"), Map.of("id", "end", "type", "end")),
                "edges", List.of(Map.of("from", "start", "to", "end")), "meta", Map.of("name", "安装回执验收",
                        "capability", Map.of("model_invocable", true, "user_invocable", false, "input_schema", Map.of(),
                                "output_schema", Map.of(), "supports_hitl", false, "export_fields", List.of(), "timeout_ms", 30000, "concurrency_safe", true)));
        var created = request(http, "POST", "/api/v1/builder/workflows", Map.of("workflow_id", "receipt-flow", "definition", graph), token, null);
        request(http, "POST", "/api/v1/builder/workflows/publish", Map.of("workflow_id", "receipt-flow"), token, created.path("data").path("revision").asText());
        var extractGraph = Map.of("entry", "start", "nodes", List.of(Map.of("id", "start", "type", "start"),
                        Map.of("id", "model", "type", "llm", "name", "模型处理", "prompt", "提取订单信息"),
                        Map.of("id", "end", "type", "end")),
                "edges", List.of(Map.of("from", "start", "to", "model"), Map.of("from", "model", "to", "end")),
                "meta", graph.get("meta"));
        request(http, "POST", "/api/v1/builder/workflows", Map.of("workflow_id", "extract-flow", "definition", extractGraph), token, null);
        FRONTEND.bindBackend("http://127.0.0.1:" + port);
        var admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        var evidence = FRONTEND.evidence();
        var builder = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                "test", "--config=playwright.workflow-install-recovery.config.ts");
        builder.directory(admin.toFile()); builder.redirectErrorStream(true); builder.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = builder.environment(); env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", evidence.toString());
        env.put("E2E_TENANT_SLUG", slug); env.put("E2E_USERNAME", "workflow-admin"); env.put("E2E_PASSWORD", password);
        Process process = builder.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
            owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
            assertEquals(0, process.exitValue(), () -> "浏览器验收失败：" + evidence);
        }
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_workflow_install_receipts WHERE tenant_id=? AND workflow_id=?", Long.valueOf(tenant), "receipt-flow"));
    }

    private com.fasterxml.jackson.databind.JsonNode request(HttpClient http, String method, String path, Object body, String token, String revision) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (revision != null) request.header("If-Match", revision);
        var response = http.send(request.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
}
