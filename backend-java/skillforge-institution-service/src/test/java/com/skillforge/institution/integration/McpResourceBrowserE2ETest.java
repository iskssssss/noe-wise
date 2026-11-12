package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class McpResourceBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static Map<String,Object> inputSchema() { return Map.of("type", "object", "properties", Map.of("ticket", Map.of("type", "string")), "required", List.of("ticket")); }
    @Test void embeddedTextResourceReachesModelAndBrowser() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_MCP_RESOURCE", "candidate");
        assertTrue(java.util.Set.of("baseline", "candidate").contains(mode));
        String marker = "RESOURCE_" + SnowflakeIds.nextId();
        Path evidence = Path.of("build/runtime-e2e/mcp-resource-" + SnowflakeIds.nextId()).toAbsolutePath();
        Files.createDirectories(evidence); System.out.println("MCP_RESOURCE_EVIDENCE=" + evidence);
        var mcpRequests = new CopyOnWriteArrayList<String>(); var modelRequests = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                var req = objectMapper.readTree(exchange.getRequestBody()); String method = req.path("method").asText();
                mcpRequests.add(objectMapper.writeValueAsString(req));
                if ("notifications/initialized".equals(method)) { exchange.sendResponseHeaders(202, -1); return; }
                Object result = switch (method) {
                    case "initialize" -> Map.of("protocolVersion", "2026-07-28", "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "fixture", "version", "1"));
                    case "tools/list" -> Map.of("tools", List.of(Map.of("name", "runtime_wait", "description", "读取资源", "inputSchema", inputSchema())));
                    case "tools/call" -> Map.of("resultType", "complete", "content", List.of(Map.of("type", "resource", "resource", Map.of("uri", "test://resource", "mimeType", "text/plain", "text", marker))), "isError", false);
                    default -> throw new IllegalArgumentException(method);
                };
                byte[] body = objectMapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", req.path("id"), "result", result));
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.createContext("/chat/completions", exchange -> {
            try {
                var req = objectMapper.readTree(exchange.getRequestBody()); modelRequests.add(objectMapper.writeValueAsString(req));
                String model = req.path("model").asText("runtime-fixture"); boolean first = modelRequests.size() == 1;
                Map<String,Object> message;
                if (first) {
                    String tool = ""; boolean wrapped = false;
                    for (var candidate : req.path("tools")) {
                        var properties = candidate.path("function").path("parameters").path("properties");
                        boolean nested = properties.path("arguments").path("properties").has("ticket");
                        if (properties.has("ticket") || nested) { tool = candidate.path("function").path("name").asText(); wrapped = nested; break; }
                    }
                    if (tool.isBlank()) throw new IllegalStateException("fixture_tool_missing");
                    String arguments = objectMapper.writeValueAsString(wrapped ? Map.of("arguments", Map.of("ticket", "resource")) : Map.of("ticket", "resource"));
                    message = Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "resource-call", "type", "function", "function", Map.of("name", tool, "arguments", arguments))));
                } else {
                    if (!req.path("messages").toString().contains(marker)) throw new IllegalStateException("fixture_resource_missing");
                    message = Map.of("role", "assistant", "content", marker);
                }
                String finish = first ? "tool_calls" : "stop";
                var usage = Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18);
                String body;
                if (req.path("stream").asBoolean()) {
                    String one = objectMapper.writeValueAsString(Map.of("id", "resource-fixture", "object", "chat.completion.chunk", "model", model, "choices", List.of(Map.of("index", 0, "delta", message))));
                    String last = objectMapper.writeValueAsString(Map.of("id", "resource-fixture", "object", "chat.completion.chunk", "model", model, "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)), "usage", usage));
                    body = "data: " + one + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n"; exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                } else {
                    body = objectMapper.writeValueAsString(Map.of("id", "resource-fixture", "object", "chat.completion", "model", model, "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish)), "usage", usage)); exchange.getResponseHeaders().set("Content-Type", "application/json");
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            TenantContext.setTenantId(tenantId); try { configureTenantModel(base); } finally { TenantContext.clear(); }
            login(); mountResourceTool(base + "/mcp");
            String token = "resource-" + UUID.randomUUID(), clientRef = UUID.randomUUID().toString(); long channel;
            TenantContext.setTenantId(tenantId); try { channel = createNativeBrowserChannel(token, null, null); } finally { TenantContext.clear(); }
            var profileRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/client-profile")).header("X-Channel-Token", token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("client_ref", clientRef, "display_name", "资源验收")))).build();
            var profileResponse = httpClient.send(profileRequest, HttpResponse.BodyHandlers.ofString()); assertEquals(200, profileResponse.statusCode());
            var profile = objectMapper.readTree(profileResponse.body()).path("data"); String identity = profile.path("identity_token").asText();
            var sessionRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/chat/sessions")).header("X-Channel-Token", token).header("X-Chat-User-Token", identity).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("agent_id", agentId, "end_user_id", profile.path("end_user_id").asText())))).build();
            var sessionResponse = httpClient.send(sessionRequest, HttpResponse.BodyHandlers.ofString()); assertEquals(200, sessionResponse.statusCode());
            String session = objectMapper.readTree(sessionResponse.body()).path("data").path("session_id").asText();
            var process = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), KnowledgeBrowserServer.repoRoot().resolve("frontend-chat/e2e/mcp-resource-browser.mjs").toString()).directory(KnowledgeBrowserServer.repoRoot().toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("browser.log").toFile());
            process.environment().put("MCP_RESOURCE_FIXTURE", objectMapper.writeValueAsString(Map.of("backend", baseUrl(), "token", token, "identity", identity, "clientRef", clientRef, "channel", Long.toString(channel), "session", session, "agent", agentId, "evidence", evidence.toString(), "mode", mode, "marker", marker)));
            Process child = process.start();
            try (var owned = new KnowledgeBrowserProcesses(child.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                while (child.isAlive() && System.nanoTime() < deadline) { owned.capture(); child.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture(); assertFalse(child.isAlive()); assertEquals(0, child.exitValue(), Files.readString(evidence.resolve("browser.log")));
            } finally {
                Files.writeString(evidence.resolve("mcp-requests.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(mcpRequests));
                Files.writeString(evidence.resolve("model-requests.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(modelRequests));
            }
            var browser = objectMapper.readTree(Files.readString(evidence.resolve("browser.json"))); String run = browser.path("runId").asText();
            var runs = queryRows(jdbc, tenantId, "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, run);
            var actions = queryRows(jdbc, tenantId, "SELECT a.kind,a.status,t.result_kind,t.adopted,res.schema_id,res.canonical_json FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? ORDER BY a.action_ordinal", tenantId, run);
            Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runs", runs, "actions", actions, "marker", marker)));
            assertEquals(1, mcpRequests.stream().filter(s -> s.contains("tools/call")).count());
            if ("candidate".equals(mode)) { assertEquals(2, modelRequests.size()); assertTrue(modelRequests.get(1).contains(marker)); assertEquals("COMPLETED", runs.getFirst().get("status")); }
        } finally { server.stop(0); }
    }
    private void mountResourceTool(String endpoint) throws Exception {
        var revise = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        agentId = successData(revise).path("id").asText();
        var connector = send("/api/v1/connectors/instances", "POST", Map.of("executor_type", "mcp", "name", "Runtime wait fixture",
                "base_url", endpoint, "auth_type", "none", "headers", Map.of("MCP-Protocol-Version", "2026-07-28")), accessToken);
        assertEquals(200, connector.statusCode(), connector.body());
        String providerRef = successData(connector).path("instance_id").asText();
        assertFalse(providerRef.isBlank(), connector.body());
        String key = "runtime-wait-" + SnowflakeIds.nextId();
        var descriptor = Map.of("input_schema", inputSchema(),
                "effect", "READ_ONLY", "idempotent", false, "timeout_ms", 30000, "tool_name", "runtime_wait",
                "model_invocable", true, "user_invocable", false, "confirmation_policy", "NEVER");
        var definitionResponse = send("/api/v1/capabilities/definitions", "POST", Map.of("capability_type", "TOOL", "capability_key", key,
                "name", "Runtime MCP wait", "description", "真实MCP表单等待续答"), accessToken);
        assertEquals(201, definitionResponse.statusCode(), definitionResponse.body());
        long definition = successData(definitionResponse).path("id").asLong();
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(descriptor)));
        var draft = send("/api/v1/capabilities/definitions/versions", "POST", Map.of("definition_id", definition,
                "payload", Map.of("version_key", "v1", "name", "Runtime MCP wait", "description", "用准确ticket等待quantity并返回实际结果",
                        "content_hash", hash, "descriptor", descriptor)), accessToken);
        assertEquals(201, draft.statusCode(), draft.body());
        long version = successData(draft).path("id").asLong();
        var validated = send("/api/v1/capabilities/definitions/versions/validate", "POST", Map.of("definition_id", definition, "version_id", version), accessToken);
        assertTrue(successData(validated).path("valid").asBoolean(), validated.body());
        var publish = httpClient.send(HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/api/v1/capabilities/definitions/versions/publish"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
                .header("If-Match", successData(validated).path("definition_revision").asText())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("definition_id", definition, "version_id", version))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, publish.statusCode(), publish.body());
        var deployment = send("/api/v1/capabilities/deployments", "POST", Map.of("deployment_key", key, "tool_version_id", version,
                "provider_type", "MCP", "provider_ref", providerRef, "environment", "TEST", "status", "ACTIVE"), accessToken);
        assertEquals(201, deployment.statusCode(), deployment.body());
        var mount = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of("capability_type", "TOOL",
                "capability_definition_id", Long.toString(definition), "capability_version_id", Long.toString(version), "tool_config", Map.of("tool_deployment_id", key,
                        "tool_deployment_revision", successData(deployment).path("revision").asLong()), "priority", 0)), accessToken);
        assertEquals(201, mount.statusCode(), mount.body());
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
        sessionId = successData(session).path("session_id").asText();
        assertFalse(sessionId.isBlank());
    }

}
