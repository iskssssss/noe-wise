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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实部署列表、受控详情断连与同一部署的 Runtime 调用验收。 */
class ToolDeploymentDirectoryBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("tool-directory-");

    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void deploymentNamesRemainSearchableAndOriginalToolRuns() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_DEPLOYMENT_DIRECTORY", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        login();
        Path evidence = Path.of("build/runtime-e2e/tool-directory-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("playwright"); Files.createDirectories(browser);
        System.out.println("TOOL_DIRECTORY_EVIDENCE=" + evidence);
        var versions = new LinkedHashMap<String, JsonNode>();
        var deployments = new LinkedHashMap<String, JsonNode>();
        var definitions = successData(get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED"));
        for (String tool : List.of("get_current_time", "json_query")) {
            String definition = "", version = "";
            for (var item : definitions) {
                if (("tool:" + tool).equals(item.path("capability_key").asText())) {
                    definition = item.path("id").asText(); version = item.path("default_version_id").asText();
                }
            }
            assertFalse(version.isBlank());
            versions.put(tool, successData(get("/api/v1/capabilities/versions/detail?versionId=" + version)));
            String key = "directory-" + tool + "-" + SnowflakeIds.nextId();
            var created = send("/api/v1/capabilities/deployments", "POST", Map.of(
                    "deployment_key", key, "tool_version_id", version, "provider_type", "PLATFORM",
                    "provider_ref", tool, "status", "ACTIVE", "environment", "DEVELOPMENT"), accessToken);
            assertEquals(201, created.statusCode(), created.body());
            deployments.put(tool, successData(created));
            if ("get_current_time".equals(tool)) {
                assertEquals(200, send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
                var mounted = send("/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", Map.of(
                        "capability_type", "TOOL", "capability_definition_id", definition, "capability_version_id", version,
                        "priority", 0, "tool_config", Map.of("tool_deployment_id", key,
                                "tool_deployment_revision", successData(created).path("revision").asLong()))), accessToken);
                assertEquals(201, mounted.statusCode(), mounted.body());
                Files.writeString(evidence.resolve("mount.json"), mounted.body());
            }
        }
        assertEquals(200, send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken).statusCode());
        Files.writeString(evidence.resolve("deployments.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(deployments));
        String reply = "DIRECTORY_TOOL_RESULT_" + SnowflakeIds.nextId();
        try (var model = new ClockModel(reply)) {
            model.server.start();
            TenantContext.setTenantId(tenantId);
            try { configureTenantModel("http://127.0.0.1:" + model.server.getAddress().getPort()); }
            finally { TenantContext.clear(); }
            FRONTEND.bindBackend(baseUrl());
            Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
            Path cli = admin.resolve("node_modules/.bin").resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
            var command = new ProcessBuilder(cli.toString(), "test", "e2e/tool-deployment-directory-real.spec.ts");
            command.directory(admin.toFile()); command.redirectErrorStream(true);
            command.redirectOutput(evidence.resolve("playwright.log").toFile());
            var env = command.environment();
            env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
            env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD);
            env.put("E2E_AGENT_ID", agentId); env.put("E2E_EXPECTED_REPLY", reply);
            env.put("E2E_DEPLOYMENT_DIRECTORY", mode);
            env.put("E2E_CLOCK_DEPLOYMENT", deployments.get("get_current_time").path("deployment_key").asText());
            env.put("E2E_QUERY_DEPLOYMENT", deployments.get("json_query").path("deployment_key").asText());
            env.put("E2E_CLOCK_NAME", versions.get("get_current_time").path("name").asText());
            env.put("E2E_CLOCK_VERSION_KEY", versions.get("get_current_time").path("version_key").asText());
            env.put("E2E_QUERY_VERSION", versions.get("json_query").path("id").asText());
            env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
            Process process = command.start();
            try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
                while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                owned.capture(); assertFalse(process.isAlive(), "部署目录浏览器超时：" + evidence);
                assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
            } finally {
                Files.writeString(evidence.resolve("model-requests.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model.requests));
                Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=? ORDER BY created_second LIMIT 20", tenantId, agentId),
                        "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.kind,a.status,t.result_kind,t.adopted,req.canonical_json AS request,res.canonical_json AS result FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND r.agent_id=? ORDER BY a.action_ordinal LIMIT 50", tenantId, agentId),
                        "budgets", queryRows(jdbc, tenantId, "SELECT b.run_id,b.state,b.category FROM agent_runtime_budget_attempts b JOIN agent_runtime_execution_runs r ON r.tenant_id=b.tenant_id AND r.run_id=b.run_id WHERE r.tenant_id=? AND r.agent_id=? LIMIT 50", tenantId, agentId))));
            }
            var receipt = objectMapper.readTree(Files.readString(browser.resolve("receipt.json")));
            String actualRun = receipt.path("execution_id").asText();
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, actualRun));
            assertEquals(2, model.requests.size());
            assertTrue(model.clockObserved, "工具实际结果必须回传下一轮模型");
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='TOOL' AND status='SUCCEEDED'", tenantId, actualRun));
        }
    }

    private final class ClockModel implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        volatile boolean clockObserved;

        ClockModel(String reply) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                try (exchange) {
                    var request = objectMapper.readTree(exchange.getRequestBody()); requests.add(request.deepCopy());
                    int ordinal = requests.size();
                    if (ordinal > 2) throw new java.io.IOException("unexpected_directory_model_call");
                    String name = "";
                    for (var tool : request.path("tools")) {
                        if (tool.path("function").path("parameters").path("properties").has("timezone"))
                            name = tool.path("function").path("name").asText();
                    }
                    if (name.isBlank()) throw new java.io.IOException("clock_not_mounted");
                    Object delta;
                    String finish;
                    if (ordinal == 1) {
                        delta = Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "directory-clock",
                                "type", "function", "function", Map.of("name", name, "arguments", "{\"timezone\":\"Asia/Shanghai\"}"))));
                        finish = "tool_calls";
                    } else {
                        for (var message : request.path("messages")) {
                            if ("tool".equals(message.path("role").asText()) && "directory-clock".equals(message.path("tool_call_id").asText())) {
                                var result = objectMapper.readTree(message.path("content").asText());
                                clockObserved = result.findPath("epoch_millis").isIntegralNumber()
                                        && "Asia/Shanghai".equals(result.findPath("timezone").asText());
                            }
                        }
                        if (!clockObserved) throw new java.io.IOException("clock_result_not_returned");
                        delta = Map.of("role", "assistant", "content", reply); finish = "stop";
                    }
                    var chunk = Map.of("id", "directory-model", "object", "chat.completion.chunk", "model", request.path("model").asText(),
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
