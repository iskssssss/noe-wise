package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实平台工具、模型 HTTP 与浏览器回显的字符长度约束闭环。 */
class JsonSchemaUnicodeBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("json-schema-unicode-");
    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }
    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void unicodeLengthResultsReachNextModelAndBrowser() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_JSON_SCHEMA_UNICODE", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        Path evidence = Path.of("build/runtime-e2e/json-schema-unicode-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("playwright"); Files.createDirectories(browser);
        System.out.println("JSON_SCHEMA_UNICODE_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "Real isolated Spring/PostgreSQL/Redis, platform tool registration/mount/publication, Runtime and browser. Model responses use a local controlled HTTP server. Final report copies actual tool messages. No external supplier acceptance. Trace disabled to exclude credentials.");
        var api = new ArrayList<Map<String, Object>>();
        try (var model = new SchemaModel()) {
            try {
                String definition = "", version = "";
                var definitions = get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED");
                Files.writeString(evidence.resolve("definitions.json"), definitions.body());
                for (var item : successData(definitions)) {
                    if ("tool:json_schema_validate".equals(item.path("capability_key").asText())) {
                        definition = item.path("id").asText(); version = item.path("default_version_id").asText();
                    }
                }
                assertFalse(version.isBlank());
                String key = "json-schema-unicode-" + SnowflakeIds.nextId();
                Map<String, Object> deploymentBody = Map.of("deployment_key", key, "tool_version_id", version, "provider_type", "PLATFORM",
                        "provider_ref", "json_schema_validate", "status", "ACTIVE", "environment", "DEVELOPMENT");
                var deployment = send("/api/v1/capabilities/deployments", "POST", deploymentBody, accessToken);
                api.add(Map.of("path", "/api/v1/capabilities/deployments", "request", deploymentBody, "status", deployment.statusCode(), "response", deployment.body()));
                assertEquals(201, deployment.statusCode(), deployment.body());
                var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
                api.add(Map.of("path", "/api/v1/agents/revise", "request", Map.of("agent_id", agentId), "status", revised.statusCode(), "response", revised.body()));
                assertEquals(200, revised.statusCode(), revised.body());
                Map<String, Object> mountBody = Map.of("agent_id", agentId, "payload", Map.of("capability_type", "TOOL",
                        "capability_definition_id", definition, "capability_version_id", version, "priority", 0,
                        "tool_config", Map.of("tool_deployment_id", key, "tool_deployment_revision", successData(deployment).path("revision").asLong())));
                var mounted = send("/api/v1/agents/capability-mounts", "POST", mountBody, accessToken);
                api.add(Map.of("path", "/api/v1/agents/capability-mounts", "request", mountBody, "status", mounted.statusCode(), "response", mounted.body()));
                assertEquals(201, mounted.statusCode(), mounted.body());
                var publication = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
                api.add(Map.of("path", "/api/v1/agents/publish", "request", Map.of("agent_id", agentId), "status", publication.statusCode(), "response", publication.body()));
                assertEquals(200, publication.statusCode(), publication.body());
                model.server.start();
                TenantContext.setTenantId(tenantId);
                try { configureTenantModel("http://127.0.0.1:" + model.server.getAddress().getPort()); }
                finally { TenantContext.clear(); }
                FRONTEND.bindBackend(baseUrl());
                Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
                Path cli = admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
                var command = new ProcessBuilder(cli.toString(), "test", "e2e/json-schema-unicode-real.spec.ts");
                command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
                var env = command.environment();
                env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
                env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_AGENT_ID", agentId);
                env.put("E2E_JSON_SCHEMA_UNICODE", mode); env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
                Process process = command.start();
                try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                    while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                    owned.capture(); assertFalse(process.isAlive(), "格式校验浏览器超时：" + evidence);
                    assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
                }
            } finally {
                Files.writeString(evidence.resolve("api.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(api));
                Files.writeString(evidence.resolve("model-requests.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model.requests));
                Files.writeString(evidence.resolve("model-errors.json"), objectMapper.writeValueAsString(model.errors));
                Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "runs", queryRows(jdbc, tenantId, "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=? LIMIT 5", tenantId, agentId),
                        "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted,req.canonical_json AS request,res.canonical_json AS result FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND r.agent_id=? ORDER BY a.action_ordinal LIMIT 20", tenantId, agentId),
                        "budgets", queryRows(jdbc, tenantId, "SELECT b.run_id,b.state,b.category FROM agent_runtime_budget_attempts b JOIN agent_runtime_execution_runs r ON r.tenant_id=b.tenant_id AND r.run_id=b.run_id WHERE r.tenant_id=? AND r.agent_id=? LIMIT 20", tenantId, agentId))));
            }
            var receipt = objectMapper.readTree(Files.readString(browser.resolve("receipt.json")));
            String actualRun = receipt.path("execution_id").asText();
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=?", tenantId, agentId));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun));
            assertEquals(3, model.requests.size()); assertTrue(model.errors.isEmpty(), model.errors.toString());
            assertEquals(3, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun));
            assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='TOOL' AND status='SUCCEEDED'", tenantId, actualRun));
            var state = objectMapper.readTree(Files.readString(evidence.resolve("runtime.json")));
            int index = 0;
            for (var action : state.path("actions")) {
                if (!"TOOL".equals(action.path("kind").asText())) continue;
                boolean valid = "candidate".equals(mode) && index == 0;
                assertEquals("SUCCEEDED", action.path("result_kind").asText()); assertTrue(action.path("adopted").asBoolean());
                var material = objectMapper.readTree(action.path("result").asText());
                var outcome = objectMapper.readTree(material.path("outcome").path("canonicalJson").asText());
                var envelope = objectMapper.readTree(outcome.path("observation").path("canonicalJson").asText());
                assertTrue(envelope.path("isError").isBoolean());
                assertFalse(envelope.path("isError").asBoolean(), "不匹配 schema 是业务校验结果");
                var result = envelope.path("structuredContent");
                assertTrue(result.path("success").isBoolean()); assertTrue(result.path("valid").isBoolean());
                assertTrue(result.path("success").asBoolean()); assertEquals(valid, result.path("valid").asBoolean());
                assertTrue(result.path("errors").isArray()); assertEquals(valid, result.path("errors").isEmpty());
                JsonNode received = null;
                for (var message : model.requests.get(index + 1).path("messages")) {
                    if ("tool".equals(message.path("role").asText()) && ("schema-" + (index + 1)).equals(message.path("tool_call_id").asText()))
                        received = objectMapper.readTree(message.path("content").asText());
                }
                assertNotNull(received); assertEquals(result, received, "下一模型必须收到逐项完整工具结果");
                index++;
            }
            assertEquals(2, index);
        }
    }

    private final class SchemaModel implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> cases = List.of(
                Map.of("value", "😀", "schema", Map.of("type", "string", "maxLength", 1)),
                Map.of("value", "AB", "schema", Map.of("type", "string", "maxLength", 1)));

        SchemaModel() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                try (exchange) {
                    var request = objectMapper.readTree(exchange.getRequestBody()); requests.add(request.deepCopy());
                    int ordinal = requests.size();
                    if (ordinal > 3) throw new java.io.IOException("unexpected_schema_model_call");
                    String name = "";
                    for (var tool : request.path("tools")) {
                        var properties = tool.path("function").path("parameters").path("properties");
                        if (properties.has("schema") && properties.has("value")) name = tool.path("function").path("name").asText();
                    }
                    if (name.isBlank()) throw new java.io.IOException("json_schema_validate_not_mounted");
                    Object delta; String finish;
                    if (ordinal <= cases.size()) {
                        delta = Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "schema-" + ordinal,
                                "type", "function", "function", Map.of("name", name, "arguments", objectMapper.writeValueAsString(cases.get(ordinal - 1))))));
                        finish = "tool_calls";
                    } else {
                        var actual = objectMapper.createArrayNode();
                        for (int index = 1; index <= cases.size(); index++) {
                            JsonNode observed = null;
                            for (var message : request.path("messages")) {
                                if ("tool".equals(message.path("role").asText()) && ("schema-" + index).equals(message.path("tool_call_id").asText()))
                                    observed = objectMapper.readTree(message.path("content").asText());
                            }
                            if (observed == null) throw new java.io.IOException("schema_result_missing_" + index);
                            actual.add(observed);
                        }
                        delta = Map.of("role", "assistant", "content", "JSON_SCHEMA_UNICODE_OBSERVED\n```json\n" + objectMapper.writeValueAsString(actual) + "\n```");
                        finish = "stop";
                    }
                    var chunk = Map.of("id", "schema-model", "object", "chat.completion.chunk", "model", request.path("model").asText(),
                            "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)),
                            "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15));
                    byte[] body = ("data: " + objectMapper.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } catch (Exception failure) { errors.add(failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
            });
        }
        @Override public void close() { server.stop(0); }
    }
}
