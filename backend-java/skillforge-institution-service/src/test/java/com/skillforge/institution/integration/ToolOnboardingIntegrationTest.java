package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.core.capability.tool.ToolResolver;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.adapter.mcp.McpToolPortal;
import com.skillforge.runtime.session.SessionToolNames;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实管理HTTP、PostgreSQL事务与冻结绑定；仅外部MCP目录为owned loopback，不调用模型或工具。 */
@Timeout(90)
class ToolOnboardingIntegrationTest extends AbstractIntegrationTest {
    private static final String PASSWORD = "SyntheticToolOnboarding123";
    private static final String ACCESS = "/api/v1/tenant/foundation/access";
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CapabilityMountPersistenceService mounts;
    @Autowired CapabilityMountResolver mountResolver;
    @Autowired ToolResolver toolResolver;
    @Autowired javax.sql.DataSource dataSource;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger catalogCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private HttpServer mcp;
    private ExecutorService mcpExecutor;
    private volatile CountDownLatch discoveryEntered;
    private volatile CountDownLatch discoveryRelease;
    private volatile boolean failDiscovery;
    private boolean executeTool;
    private volatile String receivedToolName;
    private String tenant;
    private String slug;
    private String token;
    private String connector;

    @DynamicPropertySource
    static void noBackgroundExecution(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> false);
        registry.add("skillforge.memory-profile-summary-cron", () -> "-");
    }

    @BeforeEach void fixture() throws Exception {
        slug = "tool-onboard-" + SnowflakeIds.nextId();
        tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        try { createTestTenantAdministrator(tenant, "onboard-admin", PASSWORD); }
        finally { TenantContext.clear(); }
        token = login("onboard-admin");
        mcp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcp.createContext("/mcp", exchange -> {
            try {
                var request = json.readTree(exchange.getRequestBody());
                if (!"tools/list".equals(request.path("method").asText())) {
                    toolCalls.incrementAndGet();
                    if (!executeTool) { exchange.sendResponseHeaders(500, -1); return; }
                    receivedToolName = request.path("params").path("name").asText();
                    byte[] body = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"),
                            "result", Map.of("resultType", "complete", "content", List.of(Map.of("type", "text", "text", "ok")), "isError", false)));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    return;
                }
                catalogCalls.incrementAndGet();
                var entered = discoveryEntered;
                if (entered != null) {
                    entered.countDown();
                    try {
                        if (!discoveryRelease.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("discovery gate timeout");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                if (failDiscovery) { exchange.sendResponseHeaders(500, -1); return; }
                var result = Map.of("jsonrpc", "2.0", "id", request.get("id"), "result",
                        Map.of("tools", List.of(
                                Map.of("name", "alpha", "title", "目录查询", "inputSchema", Map.of("type", "object", "properties", Map.of())),
                                Map.of("name", "beta", "description", "另一工具", "inputSchema", Map.of("type", "object", "properties", Map.of()),
                                        "outputSchema", Map.of("type", "object")),
                                Map.of("name", "_search", "description", "查询", "inputSchema", Map.of("type", "object", "properties", Map.of())),
                                Map.of("name", "bad name", "inputSchema", Map.of("type", "object")),
                                Map.of("name", "broken", "description", "故意无效的发布图标", "inputSchema", Map.of("type", "object"),
                                        "icons", List.of(Map.of("src", "relative-icon"))))));
                byte[] body = json.writeValueAsBytes(result);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        mcpExecutor = Executors.newCachedThreadPool();
        mcp.setExecutor(mcpExecutor);
        mcp.start();
        connector = ok("POST", "/api/v1/connectors/instances", Map.of(
                "executor_type", "mcp", "name", "接入目录", "base_url", endpoint(), "auth_type", "none",
                "headers", Map.of("MCP-Protocol-Version", "2026-07-28")), token).path("instance_id").asText();
        assertFalse(connector.isBlank());
        // 创建原接口会发现全部名称；独立清空owned行，验证onboard真正补齐Runtime allowlist。
        scoped(() -> jdbc.update("UPDATE nw_connector_instances SET capabilities='[]'::jsonb WHERE tenant_id=? AND instance_id=?",
                Long.parseLong(tenant), connector));
    }

    @AfterEach void closeCatalog() {
        if (discoveryRelease != null) discoveryRelease.countDown();
        if (mcp != null) mcp.stop(0);
        if (mcpExecutor != null) mcpExecutor.shutdownNow();
        TenantContext.clear();
        assertEquals(executeTool ? 1 : 0, toolCalls.get());
    }

    @Test
    @org.junit.jupiter.api.Tag("tool-onboarding-browser")
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void browserAutomaticallyDiscoversAndExplicitlyOnboardsTool() throws Exception {
        int before = catalogCalls.get();
        try (var frontend = KnowledgeBrowserServer.startIsolated("tool-onboarding-browser-")) {
            frontend.bindBackend("http://127.0.0.1:" + port);
            var admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            var evidence = frontend.evidence();
            var browser = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                    "test", "--config=playwright.tool-onboarding-real.config.ts");
            browser.directory(admin.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = browser.environment(); env.put("E2E_BASE_URL", frontend.baseUrl()); env.put("E2E_ARTIFACT_DIR", evidence.toString());
            env.put("E2E_INSTITUTION_URL", "http://127.0.0.1:" + port); env.put("E2E_TENANT_SLUG", slug);
            env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_CONNECTOR_ID", connector); env.put("E2E_MCP_URL", endpoint());
            Process process = browser.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
                assertEquals(0, process.exitValue(), "浏览器验收失败：" + evidence);
            }
        }
        assertTrue(catalogCalls.get() > before);
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_tool_onboarding_receipts WHERE tenant_id=? AND status='SUCCEEDED'", Long.parseLong(tenant)));
    }

    @Test
    @org.junit.jupiter.api.Tag("connector-creation-browser")
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void browserRecoversConnectorCreationWithoutOnboardingTools() throws Exception {
        try (var frontend = KnowledgeBrowserServer.startIsolated("connector-creation-browser-")) {
            frontend.bindBackend("http://127.0.0.1:" + port);
            var admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            var evidence = frontend.evidence();
            var browser = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                    "test", "--config=playwright.connector-creation-real.config.ts");
            browser.directory(admin.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = browser.environment(); env.put("E2E_BASE_URL", frontend.baseUrl()); env.put("E2E_ARTIFACT_DIR", evidence.toString());
            env.put("E2E_INSTITUTION_URL", "http://127.0.0.1:" + port); env.put("E2E_TENANT_SLUG", slug);
            env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_MCP_URL", endpoint());
            Process process = browser.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture(); assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
                assertEquals(0, process.exitValue(), "浏览器验收失败：" + evidence);
            }
        }
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_connector_instances WHERE tenant_id=? AND name='浏览器恢复连接器'", Long.parseLong(tenant)));
        assertEquals(0, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_tool_deployments WHERE tenant_id=?", Long.parseLong(tenant)));
    }

    @Test void connectorUpdateReceiptIsAtomicScopedAndDoesNotPersistCredential() throws Exception {
        String id = UUID.randomUUID().toString();
        String query = "/api/v1/connectors/instances/update-with-receipt/result?request_id=" + id;
        assertEquals(204, send("GET", query, null, token).statusCode());
        var payload = Map.of("name", "更新后的连接器", "auth_type", "bearer", "auth_credential", "synthetic-update-secret");
        var command = Map.of("request_id", id, "instance_id", connector, "payload", payload);
        var first = ok("POST", "/api/v1/connectors/instances/update-with-receipt", command, token);
        assertEquals(id, first.path("request_id").asText());
        assertEquals(connector, first.path("instance_id").asText());
        var originalTime = scoped(() -> jdbc.queryForObject("SELECT updated_at FROM nw_connector_instances WHERE tenant_id=? AND instance_id=?", java.time.OffsetDateTime.class, Long.parseLong(tenant), connector));
        var replay = ok("POST", "/api/v1/connectors/instances/update-with-receipt", command, token);
        assertEquals(first, replay);
        assertEquals(originalTime, scoped(() -> jdbc.queryForObject("SELECT updated_at FROM nw_connector_instances WHERE tenant_id=? AND instance_id=?", java.time.OffsetDateTime.class, Long.parseLong(tenant), connector)));
        assertEquals(409, send("POST", "/api/v1/connectors/instances/update-with-receipt", Map.of("request_id", id, "instance_id", connector, "payload", Map.of("name", "其他内容")), token).statusCode());
        assertEquals(409, send("POST", "/api/v1/connectors/instances/update-with-receipt", Map.of("request_id", id, "instance_id", "missing", "payload", payload), token).statusCode());
        var differentSecret = Map.of("name", "更新后的连接器", "auth_type", "bearer", "auth_credential", "different-update-secret");
        assertEquals(409, send("POST", "/api/v1/connectors/instances/update-with-receipt", Map.of("request_id", id, "instance_id", connector, "payload", differentSecret), token).statusCode());
        assertEquals(connector, ok("GET", query, null, token).path("instance_id").asText());
        String stored = scoped(() -> jdbc.queryForObject("SELECT fingerprint FROM nw_connector_update_receipts WHERE tenant_id=? AND request_id=CAST(? AS uuid)", String.class, Long.parseLong(tenant), id));
        assertTrue(stored.startsWith("sfenc:1:"));
        assertFalse(stored.contains("synthetic-update-secret"));
        String user = "connector-updater-" + SnowflakeIds.nextId();
        var role = ok("POST", ACCESS + "/role", Map.of("code", "connector_updater_" + SnowflakeIds.nextId(),
                "name", "连接器维护", "data_scope", "all", "permissions", List.of("foundation.connectors.read", "foundation.connectors.update"), "department_ids", List.of()), token);
        ok("POST", ACCESS + "/member", Map.of("username", user, "display_name", user, "password", PASSWORD,
                "role_ids", List.of(role.path("id").asText())), token);
        String other = login(user);
        assertEquals(204, send("GET", query, null, other).statusCode());
        assertEquals(409, send("POST", "/api/v1/connectors/instances/update-with-receipt", command, other).statusCode());
        String rejected = UUID.randomUUID().toString();
        var invalid = Map.of("request_id", rejected, "instance_id", connector, "payload", Map.of("name", ""));
        var response = send("POST", "/api/v1/connectors/instances/update-with-receipt", invalid, token);
        assertEquals(400, response.statusCode());
        assertEquals("CONNECTOR_UPDATE_REJECTED", json.readTree(response.body()).path("code").asText());
        assertEquals(204, send("GET", "/api/v1/connectors/instances/update-with-receipt/result?request_id=" + rejected, null, token).statusCode());
        assertEquals(0, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_connector_update_receipts WHERE tenant_id=? AND request_id=CAST(? AS uuid)", Long.parseLong(tenant), rejected));
    }

    @Test void concurrentConnectorUpdateWithSameRequestCommitsOneReceipt() throws Exception {
        String id = UUID.randomUUID().toString();
        var command = Map.of("request_id", id, "instance_id", connector, "payload", Map.of("name", "并发更新"));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var requests = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> CompletableFuture.supplyAsync(() -> {
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("request gate timeout");
                    return send("POST", "/api/v1/connectors/instances/update-with-receipt", command, token);
                } catch (Exception failure) { throw new RuntimeException(failure); }
            }, pool)).toList();
            start.countDown();
            for (var request : requests) assertEquals(200, request.get(30, TimeUnit.SECONDS).statusCode());
            assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_connector_update_receipts WHERE tenant_id=? AND request_id=CAST(? AS uuid)", Long.parseLong(tenant), id));
            assertEquals(connector, ok("GET", "/api/v1/connectors/instances/update-with-receipt/result?request_id=" + id, null, token).path("instance_id").asText());
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void connectorCreationReceiptRecoversOnlyOriginalActorWithoutPersistingPlaintext() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String url = "/api/v1/connectors/instances/create-with-receipt/result?request_id=" + requestId;
        assertEquals(204, send("GET", url, null, token).statusCode());
        var payload = Map.of("executor_type", "mcp", "name", "连接器回执", "base_url", endpoint(),
                "auth_type", "bearer", "auth_credential", "synthetic-connector-secret",
                "headers", Map.of("MCP-Protocol-Version", "2026-07-28"));
        var result = ok("POST", "/api/v1/connectors/instances/create-with-receipt",
                Map.of("request_id", requestId, "payload", payload), token);
        assertEquals(requestId, result.path("request_id").asText());
        assertFalse(result.path("instance_id").asText().isBlank());
        assertEquals(result, ok("GET", url, null, token));
        assertFalse(result.toString().contains("synthetic-connector-secret"));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_connector_instances WHERE tenant_id=? AND create_request_id=? AND auth_credential_encrypted NOT LIKE '%synthetic-connector-secret%'", Long.parseLong(tenant), requestId));
        String user = "connector-reader-" + SnowflakeIds.nextId();
        var role = ok("POST", ACCESS + "/role", Map.of("code", "connector_reader_" + SnowflakeIds.nextId(),
                "name", "连接器读取", "data_scope", "all", "permissions", List.of("foundation.connectors.read"), "department_ids", List.of()), token);
        ok("POST", ACCESS + "/member", Map.of("username", user, "display_name", user, "password", PASSWORD,
                "role_ids", List.of(role.path("id").asText())), token);
        assertEquals(204, send("GET", url, null, login(user)).statusCode());
        failDiscovery = true;
        String failed = UUID.randomUUID().toString();
        var rejected = send("POST", "/api/v1/connectors/instances/create-with-receipt", Map.of("request_id", failed, "payload", payload), token);
        assertTrue(rejected.statusCode() >= 400);
        assertEquals("CONNECTOR_CREATE_REJECTED", json.readTree(rejected.body()).path("code").asText());
        assertEquals(204, send("GET", "/api/v1/connectors/instances/create-with-receipt/result?request_id=" + failed, null, token).statusCode());
    }

    @Test void oneAcceptancePublishesDeploysUnionsAndReusesExactReleasedIdentity() throws Exception {
        var before = counts();
        var request = request(List.of("alpha", "beta"));
        var accepted = ok("POST", "/api/v1/tools/onboard", request, token);
        assertEquals(connector, accepted.path("connector_id").asText());
        assertEquals(2, accepted.path("items").size());
        assertEquals(List.of(before.get(0) + 2, before.get(1) + 2, before.get(2) + 2), counts());
        for (var item : accepted.path("items")) {
            assertFalse(item.has("name"));
            assertFalse(item.has("description"));
            assertFalse(item.has("provider_label"));
            var rows = queryRows(jdbc, tenant, """
                    SELECT d.default_version_id,v.status,v.descriptor::text,d.status AS definition_status,
                           p.provider_ref,p.status AS deployment_status
                    FROM nw_capability_definitions d JOIN nw_capability_versions v ON v.definition_id=d.id
                    JOIN nw_tool_deployments p ON p.tool_version_id=v.id
                    WHERE d.tenant_id=? AND d.id=? AND v.id=? AND p.deployment_key=?
                    """, Long.parseLong(tenant), item.path("definition_id").asLong(), item.path("version_id").asLong(), item.path("deployment_key").asText());
            assertEquals(1, rows.size());
            var row = rows.getFirst();
            assertEquals(item.path("version_id").asLong(), ((Number) row.get("default_version_id")).longValue());
            assertEquals("PUBLISHED", row.get("status"));
            assertEquals("PUBLISHED", row.get("definition_status"));
            assertEquals("ACTIVE", row.get("deployment_status"));
            assertEquals(connector, row.get("provider_ref"));
            var descriptor = json.readTree(row.get("descriptor").toString());
            assertEquals(item.path("tool_name").asText(), descriptor.path("tool_name").asText());
            assertEquals("NON_IDEMPOTENT_WRITE", descriptor.path("effect").asText());
            assertFalse(descriptor.path("idempotent").asBoolean());
            assertEquals("NEVER", descriptor.path("confirmation_policy").asText());
            assertFalse(descriptor.path("parallel_safe").asBoolean());
            if ("alpha".equals(item.path("tool_name").asText())) {
                assertFalse(descriptor.has("output_schema"));
            }
        }
        var allowlist = queryRows(jdbc, tenant, "SELECT capabilities::text FROM nw_connector_instances WHERE tenant_id=? AND instance_id=?",
                Long.parseLong(tenant), connector).getFirst();
        var names = json.readTree(allowlist.get("capabilities").toString());
        assertTrue(names.toString().contains("alpha"));
        assertTrue(names.toString().contains("beta"));
        assertFalse(names.toString().contains(connector + "/"));
        var after = counts();
        var repeated = ok("POST", "/api/v1/tools/onboard", request, token);
        assertEquals(accepted, repeated);
        assertEquals(after, counts());
        var checked = ok("GET", "/api/v1/tools/onboarding?connectorId=" + connector + "&toolName=alpha&toolName=beta", null, token);
        for (int index = 0; index < repeated.path("items").size(); index++) {
            assertEquals("AVAILABLE", checked.path("items").get(index).path("status").asText());
            assertEquals(repeated.path("items").get(index).path("definition_id"),
                    checked.path("items").get(index).path("definition_id"));
        }
    }

    @Test void sameRequestReturnsImmutableReceiptAndConflictsOnChangedSelectionOrActor() throws Exception {
        var command = request(List.of("alpha", "beta"));
        String id = command.get("request_id").toString();
        assertEquals(204, send("GET", "/api/v1/tools/onboard/result?requestId=" + id, null, token).statusCode());
        var receipt = ok("POST", "/api/v1/tools/onboard", command, token);
        int calls = catalogCalls.get();
        scoped(() -> jdbc.update("UPDATE nw_connector_instances SET capabilities='[]'::jsonb WHERE tenant_id=? AND instance_id=?",
                Long.parseLong(tenant), connector));
        assertEquals(receipt, ok("GET", "/api/v1/tools/onboard/result?requestId=" + id, null, token));
        assertEquals(receipt, ok("POST", "/api/v1/tools/onboard", command, token));
        assertEquals(calls, catalogCalls.get());
        var reordered = Map.of("request_id", id, "connector_id", connector,
                "tool_names", List.of("beta", "alpha"), "allow_agent_calls", true);
        assertEquals(409, send("POST", "/api/v1/tools/onboard", reordered, token).statusCode());

        String user = "other-" + SnowflakeIds.nextId();
        var role = ok("POST", ACCESS + "/role", Map.of("code", "receipt_" + SnowflakeIds.nextId(), "name", "回执角色",
                "data_scope", "all", "permissions", List.of("foundation.access.read", "foundation.connectors.read",
                        "foundation.connectors.update", "foundation.capabilities.read", "foundation.capabilities.create",
                        "foundation.capabilities.publish"), "department_ids", List.of()), token);
        ok("POST", ACCESS + "/member", Map.of("username", user, "display_name", user, "password", PASSWORD,
                "role_ids", List.of(role.path("id").asText())), token);
        String other = login(user);
        assertEquals(409, send("POST", "/api/v1/tools/onboard", command, other).statusCode());
        assertEquals(204, send("GET", "/api/v1/tools/onboard/result?requestId=" + id, null, other).statusCode());
        String limitedUser = "read-denied-" + SnowflakeIds.nextId();
        var limitedRole = ok("POST", ACCESS + "/role", Map.of("code", "receipt_limited_" + SnowflakeIds.nextId(),
                "name", "受限回执角色", "data_scope", "all", "permissions",
                List.of("foundation.access.read", "foundation.connectors.read"), "department_ids", List.of()), token);
        ok("POST", ACCESS + "/member", Map.of("username", limitedUser, "display_name", limitedUser,
                "password", PASSWORD, "role_ids", List.of(limitedRole.path("id").asText())), token);
        assertEquals(403, send("GET", "/api/v1/tools/onboard/result?requestId=" + id, null, login(limitedUser)).statusCode());
    }

    @Test void idempotencyHeaderLeavesOriginalReceiptReplayAndConflictToDomain() throws Exception {
        var command = request(List.of("alpha"));
        String id = command.get("request_id").toString();
        var first = send("POST", "/api/v1/tools/onboard", command, token, "client-key");
        assertEquals(200, first.statusCode(), first.body());
        var receipt = json.readTree(first.body()).path("data");
        assertEquals("SUCCEEDED", receipt.path("status").asText());
        var before = counts();
        int calls = catalogCalls.get();
        var replay = send("POST", "/api/v1/tools/onboard", command, token, "client-key");
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(receipt, json.readTree(replay.body()).path("data"));
        var changed = Map.of("request_id", id, "connector_id", connector,
                "tool_names", List.of("beta"), "allow_agent_calls", true);
        var conflict = send("POST", "/api/v1/tools/onboard", changed, token, "client-key");
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertEquals("TOOL_ONBOARD_REQUEST_CONFLICT", json.readTree(conflict.body()).path("code").asText());
        assertEquals(before, counts());
        assertEquals(calls, catalogCalls.get());
    }

    @Test void inFlightIdenticalCommandsCommitOnceAndResultLookupStaysReadOnly() throws Exception {
        var command = request(List.of("alpha", "beta"));
        String id = command.get("request_id").toString();
        var before = counts();
        discoveryEntered = new CountDownLatch(1);
        discoveryRelease = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> uncheckedPost(command));
        assertTrue(discoveryEntered.await(20, TimeUnit.SECONDS));
        var processing = ok("GET", "/api/v1/tools/onboard/result?requestId=" + id, null, token);
        assertEquals("PROCESSING", processing.path("status").asText());
        assertEquals(connector, processing.path("connector_id").asText());
        assertEquals(List.of("alpha", "beta"), json.convertValue(processing.path("tool_names"), List.class));
        assertEquals(processing, ok("POST", "/api/v1/tools/onboard", command, token));
        discoveryRelease.countDown();
        var one = first.get(30, TimeUnit.SECONDS);
        assertEquals(200, one.statusCode(), one.body());
        assertEquals("SUCCEEDED", json.readTree(one.body()).path("data").path("status").asText());
        assertEquals(List.of(before.get(0) + 2, before.get(1) + 2, before.get(2) + 2), counts());
        assertEquals(json.readTree(one.body()).path("data"),
                ok("GET", "/api/v1/tools/onboard/result?requestId=" + id, null, token));
        assertEquals(1, queryCount(jdbc, tenant,
                "SELECT count(*) FROM nw_tool_onboarding_receipts WHERE tenant_id=? AND request_id=CAST(? AS uuid)",
                Long.parseLong(tenant), id));
    }

    @Test void discoveryTransportFailureRecordsRejectionAndNeverRediscoversOnSameKey() throws Exception {
        var before = counts();
        failDiscovery = true;
        var command = request(List.of("alpha"));
        var rejected = ok("POST", "/api/v1/tools/onboard", command, token);
        assertEquals("REJECTED", rejected.path("status").asText());
        assertEquals(connector, rejected.path("connector_id").asText());
        assertEquals("alpha", rejected.path("tool_names").get(0).asText());
        assertEquals("MCP 工具目录不可用", rejected.path("error_message").asText());
        int calls = catalogCalls.get();
        String id = command.get("request_id").toString();
        assertEquals(rejected, ok("GET", "/api/v1/tools/onboard/result?requestId=" + id, null, token));
        failDiscovery = false;
        assertEquals(rejected, ok("POST", "/api/v1/tools/onboard", command, token));
        assertEquals(calls, catalogCalls.get());
        assertEquals(before, counts());
    }

    @Test void technicalFailureDuringSuccessReceiptRollsBackWritesAndKeepsProcessing() throws Exception {
        var before = counts();
        var command = request(List.of("alpha"));
        String id = command.get("request_id").toString();
        jdbc.execute("""
                CREATE FUNCTION tool_onboard_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.status='SUCCEEDED' THEN RAISE EXCEPTION 'test receipt failure'; END IF;
                  RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER tool_onboard_test_failure BEFORE UPDATE ON nw_tool_onboarding_receipts "
                + "FOR EACH ROW EXECUTE FUNCTION tool_onboard_test_failure()");
        try {
            var failed = send("POST", "/api/v1/tools/onboard", command, token);
            assertEquals(500, failed.statusCode(), failed.body());
            assertEquals(before, counts());
            var processing = ok("GET", "/api/v1/tools/onboard/result?requestId=" + id, null, token);
            assertEquals("PROCESSING", processing.path("status").asText());
            int calls = catalogCalls.get();
            assertEquals(processing, ok("POST", "/api/v1/tools/onboard", command, token));
            assertEquals(calls, catalogCalls.get());
        } finally {
            jdbc.execute("DROP TRIGGER tool_onboard_test_failure ON nw_tool_onboarding_receipts");
            jdbc.execute("DROP FUNCTION tool_onboard_test_failure()");
        }
    }

    private HttpResponse<String> uncheckedPost(Map<String, Object> command) {
        try { return send("POST", "/api/v1/tools/onboard", command, token); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    @Test void catalogExplainsUnsupportedNamesAndOnboardsLeadingUnderscore() throws Exception {
        var items = ok("GET", "/api/v1/connectors/instances/mcp-tools?instanceId=" + connector, null, token).path("items");
        JsonNode supported = null, unsupported = null;
        for (var item : items) {
            if ("_search".equals(item.path("name").asText())) supported = item;
            if ("bad name".equals(item.path("name").asText())) unsupported = item;
        }
        assertNotNull(supported); assertTrue(supported.path("unsupported_reason").isNull());
        assertNotNull(unsupported); assertTrue(unsupported.path("unsupported_reason").asText().contains("128"));
        var accepted = onboard(List.of("_search"), token).path("items").get(0);
        assertEquals("_search", accepted.path("tool_name").asText());
        var rejected = send("POST", "/api/v1/tools/onboard", request(List.of("bad name")), token);
        assertEquals(422, rejected.statusCode(), rejected.body());
    }

    @Test void missingConnectorUpdateOrPublishPermissionRejectsBeforeAnyDatabaseWrite() throws Exception {
        var required = List.of("foundation.access.read", "foundation.connectors.read", "foundation.connectors.update",
                "foundation.capabilities.read", "foundation.capabilities.create", "foundation.capabilities.publish");
        for (var missing : List.of("foundation.connectors.update", "foundation.capabilities.publish")) {
            var permitted = new ArrayList<>(required); permitted.remove(missing);
            String user = "limited-" + SnowflakeIds.nextId();
            var role = ok("POST", ACCESS + "/role", Map.of("code", "limited_" + SnowflakeIds.nextId(), "name", "受限接入角色",
                    "data_scope", "all", "permissions", permitted, "department_ids", List.of()), token);
            ok("POST", ACCESS + "/member", Map.of("username", user, "display_name", user, "password", PASSWORD,
                    "role_ids", List.of(role.path("id").asText())), token);
            var before = counts(); int callsBefore = catalogCalls.get();
            var rejected = send("POST", "/api/v1/tools/onboard", request(List.of("alpha")), login(user));
            assertEquals(403, rejected.statusCode(), rejected.body());
            assertEquals(before, counts());
            assertEquals(callsBefore, catalogCalls.get());
            assertEquals("[]", queryRows(jdbc, tenant, "SELECT capabilities::text FROM nw_connector_instances WHERE tenant_id=? AND instance_id=?",
                    Long.parseLong(tenant), connector).getFirst().get("capabilities"));
        }
    }

    @Test void missingOrFalseExplicitAgentAuthorizationRejectsBeforeDiscoveryOrMutation() throws Exception {
        for (var body : List.of(
                Map.<String, Object>of("request_id", UUID.randomUUID().toString(), "connector_id", connector, "tool_names", List.of("alpha")),
                Map.<String, Object>of("request_id", UUID.randomUUID().toString(), "connector_id", connector, "tool_names", List.of("alpha"), "allow_agent_calls", false))) {
            var before = counts(); int callsBefore = catalogCalls.get();
            var rejected = send("POST", "/api/v1/tools/onboard", body, token);
            assertEquals(400, rejected.statusCode(), rejected.body());
            assertEquals(before, counts());
            assertEquals(callsBefore, catalogCalls.get());
            assertEquals("[]", queryRows(jdbc, tenant, "SELECT capabilities::text FROM nw_connector_instances WHERE tenant_id=? AND instance_id=?",
                    Long.parseLong(tenant), connector).getFirst().get("capabilities"));
        }
    }

    @Test void invalidSecondSelectedToolLeavesNoPartialPublicationDeploymentOrCapabilityUnion() throws Exception {
        var before = counts();
        var command = request(List.of("alpha", "broken"));
        var rejected = ok("POST", "/api/v1/tools/onboard", command, token);
        assertEquals("REJECTED", rejected.path("status").asText());
        assertEquals(rejected, ok("GET", "/api/v1/tools/onboard/result?requestId=" + command.get("request_id"), null, token));
        assertEquals(rejected, ok("POST", "/api/v1/tools/onboard", command, token));
        assertEquals(before, counts());
        assertEquals("[]", queryRows(jdbc, tenant, "SELECT capabilities::text FROM nw_connector_instances WHERE tenant_id=? AND instance_id=?",
                Long.parseLong(tenant), connector).getFirst().get("capabilities"));
        var checked = ok("GET", "/api/v1/tools/onboarding?connectorId=" + connector + "&toolName=alpha", null, token);
        assertEquals("MISSING", checked.path("items").get(0).path("status").asText());
    }

    @Test void availableIncludesPlatformToolsAndGroupsTwoDeploymentsWithoutDuplicatingTool() throws Exception {
        var item = onboard(List.of("alpha"), token).path("items").get(0);
        JsonNode platformDefinition = null;
        for (var candidate : ok("GET", "/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED", null, token))
            if ("tool:get_current_time".equals(candidate.path("capability_key").asText())) platformDefinition = candidate;
        assertNotNull(platformDefinition, "复用真实PlatformToolDefinitionInitializer发布的工具");
        ok("POST", "/api/v1/capabilities/deployments", Map.of("deployment_key", "onboard-platform-" + SnowflakeIds.nextId(),
                "tool_version_id", platformDefinition.path("default_version_id").asText(), "provider_type", "PLATFORM",
                "provider_ref", "get_current_time", "environment", "DEVELOPMENT", "status", "ACTIVE"), token);
        String second = "onboard-second-" + SnowflakeIds.nextId();
        ok("POST", "/api/v1/capabilities/deployments", Map.of("deployment_key", second,
                "tool_version_id", item.path("version_id").asText(), "provider_type", "MCP", "provider_ref", connector,
                "environment", "DEVELOPMENT", "status", "ACTIVE"), token);
        var available = ok("GET", "/api/v1/tools/available", null, token).path("items");
        var matching = new ArrayList<JsonNode>(); boolean platform = false;
        for (var entry : available) {
            if (item.path("definition_id").asText().equals(entry.path("definition_id").asText())) matching.add(entry);
            for (var deployment : entry.path("deployments"))
                if ("PLATFORM".equals(deployment.path("provider_type").asText())) platform = true;
        }
        assertTrue(platform, "既有平台工具仍通过同一可用目录选择");
        assertEquals(1, matching.size());
        assertEquals(2, matching.getFirst().path("deployments").size());
    }

    @Test void realAgentMountPreservesRemoteNameThroughMcpCall() throws Exception {
        var item = onboard(List.of("_search"), token).path("items").get(0);
        var agent = ok("POST", "/api/v1/agents", Map.of("agent_key", "onboard-agent-" + SnowflakeIds.nextId(),
                "name", "接入挂载验收", "system_prompt", "使用已挂载工具", "config", Map.of()), token).path("id").asText();
        ok("POST", "/api/v1/agents/capability-mounts", Map.of("agent_id", agent, "payload", Map.of(
                "capability_type", "TOOL", "capability_definition_id", item.path("definition_id").asText(),
                "tool_config", Map.of("tool_deployment_id", item.path("deployment_key").asText()), "priority", 0)), token);
        var resolved = scoped(() -> {
            var set = mounts.read(Long.parseLong(tenant), Long.parseLong(agent));
            String input = "tool-onboard-input-" + agent;
            var snapshot = mountResolver.freeze(input + ":capability-mount-snapshot", input,
                    Long.parseLong(tenant), Long.parseLong(agent), set.generation(), set.mounts());
            assertEquals(1, snapshot.mounts().size());
            return assertInstanceOf(ToolResolver.Resolved.class, toolResolver.resolve(snapshot.mounts().getFirst().handle(), snapshot));
        });
        var definition = resolved.version().definition(); var policy = resolved.version().policy();
        var target = new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:onboard:" + agent),
                new com.skillforge.runtime.api.CapabilityRef(com.skillforge.runtime.api.CapabilityType.TOOL,
                        item.path("definition_id").asText(), item.path("version_id").asText()),
                resolved.deployment().deploymentId(), resolved.deployment().revision(),
                new TypedPayload("json-schema", json.writeValueAsString(definition.inputSchema())),
                new TypedPayload("json-schema", json.writeValueAsString(definition.outputSchema().orElse(Map.of()))),
                com.skillforge.runtime.api.ToolEffect.valueOf(policy.effect().name()), policy.idempotent(),
                Duration.ofMillis(policy.timeoutMs()), resolved.version().metadata().invocationPolicy().modelInvocable(),
                ToolPortal.ToolConfirmationPolicy.valueOf(policy.confirmation().name()), policy.sensitiveFields(),
                policy.providerRequirements(), definition.outputSchema().isPresent(), policy.callIdentityPolicy());
        var bindings = new JdbcMcpBindings(dataSource);
        var frozen = bindings.publish(new TenantId(tenant), target, new JdbcMcpBindings.Profile("_search", "2026-07-28"));
        assertEquals("_search", frozen.toolName()); assertEquals(connector, frozen.providerRef());
        assertEquals(endpoint(), frozen.endpoint()); assertTrue(frozen.encryptedCredential().isEmpty());
        assertEquals(frozen, bindings.find(new TenantId(tenant), target).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> bindings.publish(new TenantId(tenant), target,
                new JdbcMcpBindings.Profile(connector + "/_search", "2026-07-28")));
        String alias = SessionToolNames.wireName(item.path("definition_id").asText(), definition.name());
        assertNotEquals("_search", alias);
        assertNotEquals(alias, SessionToolNames.wireName("another-definition", "_search"));
        var binding = new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.MCP, connector, frozen.toolName());
        var portal = new McpToolPortal((t, selected) -> bindings.find(t, selected).map(value ->
                new McpToolPortal.Binding(value.target(), binding, value.endpoint(), value.encryptedCredential(),
                        value.toolName(), value.protocolVersion())), cipher -> new byte[0],
                call -> true, call -> true, call -> true, call -> true, (call, output) -> true,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), java.time.Clock.systemUTC(), 100_000);
        var tenantId = new TenantId(tenant); var input = new InputId("onboard-input");
        var owner = SessionOwner.user(new SessionRef(tenantId, "onboard-session"), new AgentId(agent), new EndUserId(1));
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(tenantId, "onboard-run"),
                new TraceEnvelope("trace", "span", input, java.util.Optional.empty()), new ResultRef("identity"),
                new RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new RunOriginRef(tenantId, input.value()));
        var dispatch = new ProviderDispatch(run.runRef(), new ActionRef(tenantId, "action"), "invocation", "attempt",
                PortalCallKind.TOOL_INVOKE, new ResultRef("request"), new ResultRef("frozen"), "fingerprint", 1);
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenantId, owner.requireUser()), owner,
                java.util.Set.of("runtime:invoke"), 1, new ResultRef("verified"));
        var call = new ToolPortal.ToolCall(ProviderCallContext.dispatched(new PortCallContext(run, identity,
                new InvocationControl(java.time.Instant.now().plusSeconds(5), () -> false)), dispatch), binding, target,
                new TypedPayload("arguments.v1", "{}"), new IdempotencyKey("action"));
        executeTool = true;
        assertInstanceOf(ToolPortal.ToolOutcome.Succeeded.class, portal.invoke(call));
        assertEquals("_search", receivedToolName);
    }

    @Test void archivedDeploymentAnchorStillResolvesCurrentPublishedVersionInAvailableDirectory() throws Exception {
        var original = onboard(List.of("alpha"), token).path("items").get(0);
        var current = publishRevision(original, "NEVER", true);
        scoped(() -> jdbc.update("UPDATE nw_capability_versions SET status='ARCHIVED' WHERE tenant_id=? AND id=?",
                Long.parseLong(tenant), original.path("version_id").asLong()));
        var before = counts();
        var available = ok("GET", "/api/v1/tools/available", null, token).path("items");
        var matches = new ArrayList<JsonNode>();
        for (var item : available)
            if (original.path("definition_id").asText().equals(item.path("definition_id").asText())) matches.add(item);
        assertEquals(1, matches.size());
        assertEquals(current.path("id").asText(), matches.getFirst().path("default_version_id").asText());
        assertEquals(1, matches.getFirst().path("deployments").size());
        assertEquals(original.path("deployment_key").asText(), matches.getFirst().path("deployments").get(0).path("deployment_key").asText());
        assertEquals(before, counts(), "目录GET不重新发布或迁移旧deployment anchor");
    }

    @Test void existingAlwaysPolicyIsUnavailableAndExplicitOnboardingDoesNotSilentlyReauthorizeIt() throws Exception {
        var original = onboard(List.of("alpha"), token).path("items").get(0);
        var legacy = publishRevision(original, "ALWAYS", true);
        var before = counts(); int callsBefore = catalogCalls.get();
        var checked = ok("GET", "/api/v1/tools/onboarding?connectorId=" + connector + "&toolName=alpha", null, token);
        assertEquals("UNAVAILABLE", checked.path("items").get(0).path("status").asText());
        for (var item : ok("GET", "/api/v1/tools/available", null, token).path("items"))
            assertNotEquals(original.path("definition_id").asText(), item.path("definition_id").asText());
        var refused = ok("POST", "/api/v1/tools/onboard", request(List.of("alpha")), token);
        assertEquals("REJECTED", refused.path("status").asText());
        assertEquals(before, counts()); assertEquals(callsBefore, catalogCalls.get());
        var stored = ok("GET", "/api/v1/capabilities/versions/detail?versionId=" + legacy.path("id").asText(), null, token);
        assertEquals("ALWAYS", stored.path("descriptor").path("confirmation_policy").asText());
    }

    @Test void existingNonModelInvocablePublicationCannotBeSelectedOrSilentlyReauthorized() throws Exception {
        var original = onboard(List.of("alpha"), token).path("items").get(0);
        var disabled = publishRevision(original, "NEVER", false);
        var before = counts(); int callsBefore = catalogCalls.get();
        var checked = ok("GET", "/api/v1/tools/onboarding?connectorId=" + connector + "&toolName=alpha", null, token);
        assertEquals("UNAVAILABLE", checked.path("items").get(0).path("status").asText());
        for (var item : ok("GET", "/api/v1/tools/available", null, token).path("items"))
            assertNotEquals(original.path("definition_id").asText(), item.path("definition_id").asText());
        var refused = ok("POST", "/api/v1/tools/onboard", request(List.of("alpha")), token);
        assertEquals("REJECTED", refused.path("status").asText());
        assertEquals(before, counts()); assertEquals(callsBefore, catalogCalls.get());
        var stored = ok("GET", "/api/v1/capabilities/versions/detail?versionId=" + disabled.path("id").asText(), null, token);
        assertTrue(stored.path("descriptor").path("model_invocable").isBoolean());
        assertFalse(stored.path("descriptor").path("model_invocable").asBoolean());
    }

    @Test void publishedRowsRemainTenantIsolatedUnderAnActualNonBypassDatabaseRole() throws Exception {
        var receipt = onboard(List.of("alpha"), token);
        var item = receipt.path("items").get(0);
        String updateId = UUID.randomUUID().toString();
        ok("POST", "/api/v1/connectors/instances/update-with-receipt", Map.of("request_id", updateId,
                "instance_id", connector, "payload", Map.of("name", "隔离验证")), token);
        String other = createTenant(tenants, plans, "other-onboard-" + SnowflakeIds.nextId());
        String role = "onboard_rls_" + SnowflakeIds.nextId();
        boolean created = false;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                var current = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user");
                assertTrue(current.next());
                if (current.getBoolean(1)) {
                    // 默认Testcontainers账号是superuser；只为本只读断言建立临时受限角色。
                    statement.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
                    statement.execute("GRANT USAGE ON SCHEMA public TO " + role);
                    statement.execute("GRANT SELECT ON nw_capability_definitions,nw_capability_versions,nw_tool_deployments,nw_connector_instances,nw_tool_onboarding_receipts,nw_connector_update_receipts TO " + role);
                    connection.commit(); created = true;
                    statement.execute("SET LOCAL ROLE " + role);
                }
                var restricted = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user");
                assertTrue(restricted.next()); assertFalse(restricted.getBoolean(1));
            }
            try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)");
                 var count = connection.prepareStatement("SELECT count(*) FROM nw_capability_versions WHERE id=?")) {
                count.setLong(1, item.path("version_id").asLong());
                bind.setString(1, other); bind.execute();
                try (var rows = count.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); }
                bind.setString(1, tenant); bind.execute();
                try (var rows = count.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getLong(1)); }
                try (var updateCount = connection.prepareStatement("SELECT count(*) FROM nw_connector_update_receipts WHERE request_id=CAST(? AS uuid)")) {
                    updateCount.setString(1, updateId);
                    bind.setString(1, other); bind.execute();
                    try (var rows = updateCount.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); }
                    bind.setString(1, tenant); bind.execute();
                    try (var rows = updateCount.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getLong(1)); }
                }
                try (var receiptCount = connection.prepareStatement("SELECT count(*) FROM nw_tool_onboarding_receipts WHERE request_id=CAST(? AS uuid)")) {
                    receiptCount.setString(1, receipt.path("request_id").asText());
                    bind.setString(1, other); bind.execute();
                    try (var rows = receiptCount.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); }
                    bind.setString(1, tenant); bind.execute();
                    try (var rows = receiptCount.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getLong(1)); }
                }
            }
            connection.rollback();
        } finally {
            if (created) {
                try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("DROP OWNED BY " + role);
                    statement.execute("DROP ROLE " + role);
                }
            }
        }
    }

    private JsonNode onboard(List<String> names, String actor) throws Exception {
        return ok("POST", "/api/v1/tools/onboard", request(names), actor);
    }
    private Map<String, Object> request(List<String> names) {
        return Map.of("request_id", UUID.randomUUID().toString(), "connector_id", connector,
                "tool_names", names, "allow_agent_calls", true);
    }
    /** 复用正式Draft/校验/CAS发布，制造同稳定工具的新发布；不改写原版本正文。 */
    private JsonNode publishRevision(JsonNode original, String confirmation, boolean modelInvocable) throws Exception {
        var previous = ok("GET", "/api/v1/capabilities/versions/detail?versionId=" + original.path("version_id").asText(), null, token);
        var descriptor = previous.path("descriptor").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) descriptor).put("confirmation_policy", confirmation);
        ((com.fasterxml.jackson.databind.node.ObjectNode) descriptor).put("model_invocable", modelInvocable);
        String revision = "onboarding-revision-" + SnowflakeIds.nextId();
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((revision + json.writeValueAsString(descriptor)).getBytes(StandardCharsets.UTF_8)));
        var created = ok("POST", "/api/v1/capabilities/definitions/versions", Map.of(
                "definition_id", original.path("definition_id").asText(), "payload", Map.of(
                        "version_key", revision, "name", previous.path("name").asText(), "description", revision,
                        "content_hash", digest, "descriptor", descriptor)), token);
        var identity = Map.of("definition_id", original.path("definition_id").asText(), "version_id", created.path("id").asText());
        var validation = ok("POST", "/api/v1/capabilities/definitions/versions/validate", identity, token);
        assertTrue(validation.path("valid").asBoolean(), validation.toString());
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/capabilities/definitions/versions/publish"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").header("If-Match", validation.path("definition_revision").asText())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(identity), StandardCharsets.UTF_8)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return json.readTree(response.body()).path("data");
    }
    private String endpoint() { return "http://127.0.0.1:" + mcp.getAddress().getPort() + "/mcp"; }
    private String login(String username) throws Exception {
        return ok("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug, "username", username, "password", PASSWORD), null)
                .path("access_token").asText();
    }
    private HttpResponse<String> send(String method, String path, Object body, String actor) throws Exception {
        return send(method, path, body, actor, null);
    }
    private HttpResponse<String> send(String method, String path, Object body, String actor, String idempotencyKey) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30));
        if (actor != null) builder.header("Authorization", "Bearer " + actor);
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(String method, String path, Object body, String actor) throws Exception {
        var response = send(method, path, body, actor);
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.statusCode() + ": " + response.body());
        return json.readTree(response.body()).path("data");
    }
    private List<Long> counts() {
        return List.of(queryCount(jdbc, tenant, "SELECT count(*) FROM nw_capability_definitions WHERE tenant_id=?", Long.parseLong(tenant)),
                queryCount(jdbc, tenant, "SELECT count(*) FROM nw_capability_versions WHERE tenant_id=?", Long.parseLong(tenant)),
                queryCount(jdbc, tenant, "SELECT count(*) FROM nw_tool_deployments WHERE tenant_id=?", Long.parseLong(tenant)));
    }
    private <T> T scoped(Supplier<T> work) {
        return TenantContext.callWithTenant(tenant, () -> new TransactionTemplate(transactions).execute(ignored -> work.get()));
    }
}
