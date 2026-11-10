package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实浏览器、平台工具与 Runtime 验证空值、缺失值和查询失败的结果回传。 */
class JsonQueryBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("json-query-");

    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void queryResultsReachNextModelAndBrowser() throws Exception {
        login();
        String definition = "", version = "";
        for (var item : successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"))) {
            if ("tool:json_query".equals(item.path("capability_key").asText())) {
                definition = item.path("id").asText(); version = item.path("default_version_id").asText();
            }
        }
        assertFalse(version.isBlank());
        String key = "json-query-" + SnowflakeIds.nextId();
        var deployment = send("/api/v1/capabilities/deployments", "POST", Map.of(
                "deployment_key", key, "tool_version_id", version, "provider_type", "PLATFORM",
                "provider_ref", "json_query", "status", "ACTIVE", "environment", "DEVELOPMENT"), accessToken);
        assertEquals(201, deployment.statusCode(), deployment.body());
        assertEquals(200, send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                "capability_type", "TOOL", "capability_definition_id", definition, "capability_version_id", version,
                "priority", 0, "tool_config", Map.of("tool_deployment_id", key,
                        "tool_deployment_revision", successData(deployment).path("revision").asLong()))), accessToken);
        assertEquals(201, mounted.statusCode(), mounted.body());
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        Path evidence = Path.of("build/runtime-e2e/json-query-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("playwright"); Files.createDirectories(browser);
        Files.writeString(evidence.resolve("deployment.json"), deployment.body());
        Files.writeString(evidence.resolve("mount.json"), mounted.body());
        try (var model = new QueryModel()) {
            model.server.start();
            TenantContext.setTenantId(tenantId);
            try { configureTenantModel("http://127.0.0.1:" + model.server.getAddress().getPort()); }
            finally { TenantContext.clear(); }
            FRONTEND.bindBackend(baseUrl());
            Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            Path cli = admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
            var command = new ProcessBuilder(cli.toString(), "test", "e2e/json-query-real.spec.ts");
            command.directory(admin.toFile()); command.redirectErrorStream(true);
            command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = command.environment();
            env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
            env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_AGENT_ID", agentId);
            env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
            Process process = command.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
                while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture(); assertFalse(process.isAlive(), "查询浏览器超时：" + evidence);
                assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
            } finally {
                Files.writeString(evidence.resolve("model-requests.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model.requests));
                Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "runs", queryRows(jdbc, tenantId, "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=?", tenantId, agentId),
                        "tools", queryRows(jdbc, tenantId, "SELECT a.action_id,a.status,t.result_kind,t.adopted,req.canonical_json AS request,res.canonical_json AS result FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND r.agent_id=? AND a.kind='TOOL' ORDER BY a.action_ordinal", tenantId, agentId))));
            }
            var receipt = objectMapper.readTree(Files.readString(browser.resolve("receipt.json")));
            String actualRun = receipt.path("execution_id").asText();
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun));
            assertEquals(5, model.requests.size());
            assertEquals(4, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='TOOL'", tenantId, actualRun));
            var state = objectMapper.readTree(Files.readString(evidence.resolve("runtime.json")));
            var expected = List.of(
                    objectMapper.readTree("{\"success\":true,\"found\":true,\"value\":null}"),
                    objectMapper.readTree("{\"success\":true,\"found\":false,\"value\":null}"),
                    objectMapper.readTree("{\"success\":true,\"found\":true,\"value\":\"Ada\"}"),
                    objectMapper.readTree("{\"success\":false,\"error\":\"pointer 必须是有效的 RFC 6901 JSON Pointer，value 必须是 JSON 值\"}"));
            assertEquals(expected.size(), state.path("tools").size());
            for (int index = 0; index < expected.size(); index++) {
                var tool = state.path("tools").get(index);
                assertEquals("SUCCEEDED", tool.path("result_kind").asText());
                assertTrue(tool.path("adopted").asBoolean());
                var material = objectMapper.readTree(tool.path("result").asText());
                var outcome = objectMapper.readTree(material.path("outcome").path("canonicalJson").asText());
                var envelope = objectMapper.readTree(outcome.path("observation").path("canonicalJson").asText());
                assertEquals(index == 3, envelope.path("isError").asBoolean());
                assertEquals(expected.get(index), envelope.path("structuredContent"));
                JsonNode received = null;
                for (var message : model.requests.get(index + 1).path("messages")) {
                    if ("tool".equals(message.path("role").asText())
                            && ("query-" + (index + 1)).equals(message.path("tool_call_id").asText()))
                        received = objectMapper.readTree(message.path("content").asText());
                }
                assertEquals(expected.get(index), received, "下一轮模型必须收到原工具的完整业务结果");
            }
        }
    }

    private final class QueryModel implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final List<String> pointers = List.of("/contact/phone", "/contact/missing", "/contact/name", "contact/name");

        QueryModel() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                try (exchange) {
                    var request = objectMapper.readTree(exchange.getRequestBody()); requests.add(request.deepCopy());
                    int ordinal = requests.size();
                    if (ordinal > 5) throw new java.io.IOException("unexpected_query_model_call");
                    String name = "";
                    for (var tool : request.path("tools")) {
                        if (tool.path("function").path("parameters").path("properties").has("pointer"))
                            name = tool.path("function").path("name").asText();
                    }
                    if (name.isBlank()) throw new java.io.IOException("json_query_not_mounted");
                    Object delta;
                    String finish;
                    if (ordinal <= pointers.size()) {
                        var args = objectMapper.createObjectNode();
                        args.putObject("value").putObject("contact").putNull("phone").put("name", "Ada");
                        args.put("pointer", pointers.get(ordinal - 1));
                        delta = Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "query-" + ordinal,
                                "type", "function", "function", Map.of("name", name, "arguments", objectMapper.writeValueAsString(args)))));
                        finish = "tool_calls";
                    } else {
                        var report = new StringBuilder("JSON_QUERY_OBSERVED\n");
                        for (var message : request.path("messages")) {
                            if ("tool".equals(message.path("role").asText()))
                                report.append(message.path("content").asText()).append('\n');
                        }
                        delta = Map.of("role", "assistant", "content", report.toString()); finish = "stop";
                    }
                    var chunk = Map.of("id", "query-model", "object", "chat.completion.chunk", "model", request.path("model").asText(),
                            "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)),
                            "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15));
                    byte[] body = ("data: " + objectMapper.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                }
            });
        }
        @Override public void close() { server.stop(0); }
    }
}
