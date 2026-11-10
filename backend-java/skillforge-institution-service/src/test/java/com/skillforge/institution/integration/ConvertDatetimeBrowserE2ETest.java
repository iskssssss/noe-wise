package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

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

/** 真实平台时间工具的参数拒绝回到模型，并在同一 Run 内纠正。 */
class ConvertDatetimeBrowserE2ETest extends ChatExecutionChainE2ETest {
    private static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.startIsolated("convert-datetime-");

    @DynamicPropertySource static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll static void closeFrontend() { FRONTEND.close(); }

    @Test void invalidTimezoneCanBeCorrectedInTheSameRuntime() throws Exception {
        String mode = System.getenv().getOrDefault("E2E_CONVERT_DATETIME", "candidate");
        assertTrue(List.of("baseline", "candidate").contains(mode));
        boolean baseline = "baseline".equals(mode);
        login();
        Path evidence = Path.of("build/runtime-e2e/convert-datetime-" + SnowflakeIds.nextId()).toAbsolutePath();
        Path browser = evidence.resolve("browser");
        Files.createDirectories(browser);
        System.out.println("CONVERT_DATETIME_EVIDENCE=" + evidence);
        Files.writeString(evidence.resolve("boundary.txt"), "Real isolated Spring/PostgreSQL/Redis, platform tool deployment/mount/publication, browser input, Runtime and persisted tool results. Local controlled HTTP model is identical in both modes and corrects only after receiving an actual tool rejection. One input and one Run. Baseline remains SUSPENDED/UNCERTAIN after the unknown tool result, without claiming a terminal result. No external supplier. Credentials, trace and video excluded.");
        var api = new ArrayList<Map<String, Object>>();
        try (var model = new DatetimeModel()) {
            try {
                String definition = "", version = "";
                var definitions = get("/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED");
                Files.writeString(evidence.resolve("definitions.json"), definitions.body());
                for (var item : successData(definitions)) {
                    if ("tool:convert_datetime".equals(item.path("capability_key").asText())) {
                        definition = item.path("id").asText(); version = item.path("default_version_id").asText();
                    }
                }
                assertFalse(version.isBlank());
                String key = "convert-datetime-" + SnowflakeIds.nextId();
                Map<String, Object> deploymentBody = Map.of("deployment_key", key, "tool_version_id", version, "provider_type", "PLATFORM",
                        "provider_ref", "convert_datetime", "status", "ACTIVE", "environment", "DEVELOPMENT");
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
                model.server.start();
                TenantContext.runWithTenant(tenantId, () -> configureTenantModel("http://127.0.0.1:" + model.server.getAddress().getPort()));
                var publication = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
                api.add(Map.of("path", "/api/v1/agents/publish", "request", Map.of("agent_id", agentId), "status", publication.statusCode(), "response", publication.body()));
                assertEquals(200, publication.statusCode(), publication.body());
                FRONTEND.bindBackend(baseUrl());
                Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
                var command = new ProcessBuilder(KnowledgeBrowserServer.nodeExecutable(), admin.resolve("node_modules/@playwright/test/cli.js").toString(),
                        "test", "e2e/convert-datetime-real.spec.ts", "--output", evidence.resolve("playwright-output").toString());
                command.directory(admin.toFile()); command.redirectErrorStream(true); command.redirectOutput(evidence.resolve("playwright.log").toFile());
                var env = command.environment();
                env.put("E2E_BASE_URL", FRONTEND.baseUrl()); env.put("E2E_ARTIFACT_DIR", browser.toString());
                env.put("E2E_TENANT_SLUG", fixtureTenantSlug()); env.put("E2E_PASSWORD", PASSWORD); env.put("E2E_AGENT_ID", agentId);
                env.put("E2E_CONVERT_DATETIME", mode); env.remove("SDK4_LIVE_MODEL_KEY"); env.remove("E2E_LIVE_MODEL");
                Process process = command.start();
                try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                    while (process.isAlive() && System.nanoTime() < deadline) { owned.capture(); process.waitFor(200, TimeUnit.MILLISECONDS); }
                    owned.capture();
                    assertFalse(process.isAlive(), "时间转换浏览器超时：" + evidence);
                    assertEquals(0, process.exitValue(), evidence + "\n" + Files.readString(evidence.resolve("playwright.log")));
                }
            } finally {
                Files.writeString(evidence.resolve("api.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(api));
                Files.writeString(evidence.resolve("model-requests.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model.requests));
                Files.writeString(evidence.resolve("model-responses.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model.responses));
                Files.writeString(evidence.resolve("model-errors.json"), objectMapper.writeValueAsString(model.errors));
                Files.writeString(evidence.resolve("runtime.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "runs", queryRows(jdbc, tenantId, "SELECT run_id,session_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=? LIMIT 5", tenantId, agentId),
                        "actions", queryRows(jdbc, tenantId, "SELECT a.run_id,a.action_id,a.action_ordinal,a.kind,a.status,t.attempt_id,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request,res.schema_id AS result_schema,res.canonical_json AS result FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_runs r ON r.tenant_id=a.tenant_id AND r.run_id=a.run_id LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND r.agent_id=? ORDER BY a.action_ordinal LIMIT 10", tenantId, agentId),
                        "events", queryRows(jdbc, tenantId, "SELECT e.run_id,e.event_sequence,e.type,e.canonical_json FROM agent_runtime_execution_events e JOIN agent_runtime_execution_runs r ON r.tenant_id=e.tenant_id AND r.run_id=e.run_id WHERE e.tenant_id=? AND r.agent_id=? ORDER BY e.event_sequence LIMIT 100", tenantId, agentId),
                        "budgets", queryRows(jdbc, tenantId, "SELECT b.run_id,b.state,b.category FROM agent_runtime_budget_attempts b JOIN agent_runtime_execution_runs r ON r.tenant_id=b.tenant_id AND r.run_id=b.run_id WHERE r.tenant_id=? AND r.agent_id=? LIMIT 10", tenantId, agentId))));
            }
            var receipt = objectMapper.readTree(Files.readString(browser.resolve("receipt.json")));
            String actualRun = receipt.path("execution_id").asText();
            assertFalse(actualRun.isBlank());
            var state = objectMapper.readTree(Files.readString(evidence.resolve("runtime.json")));
            assertEquals(1, state.path("runs").size());
            assertEquals(actualRun, state.path("runs").get(0).path("run_id").asText());
            assertEquals(baseline ? "SUSPENDED" : "COMPLETED", state.path("runs").get(0).path("status").asText());
            assertEquals(baseline ? "UNCERTAIN" : "FINISHED", state.path("runs").get(0).path("phase").asText());
            assertTrue(model.errors.isEmpty(), model.errors.toString());
            assertEquals(baseline ? 1 : 3, model.requests.size());
            assertEquals(baseline ? 1 : 3, model.responses.size());
            assertEquals(baseline ? 1 : 3, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun));
            assertEquals(baseline ? 1 : 3, countRuntime("SELECT count(*) FROM agent_runtime_budget_attempts WHERE tenant_id=? AND run_id=? AND category='BUSINESS_MODEL' AND state='SETTLED'", tenantId, actualRun));
            var tools = new ArrayList<JsonNode>();
            for (var action : state.path("actions")) if ("TOOL".equals(action.path("kind").asText())) tools.add(action);
            assertEquals(baseline ? 1 : 2, tools.size());
            if (baseline) {
                assertEquals("UNKNOWN", tools.getFirst().path("status").asText());
                assertEquals("UNKNOWN", tools.getFirst().path("result_kind").asText());
                assertTrue(tools.getFirst().path("adopted").asBoolean());
                var material = objectMapper.readTree(tools.getFirst().path("result").asText());
                assertEquals("OUTCOME_UNKNOWN", material.path("resolution").asText());
                assertEquals("OutcomeUnknown", material.path("outcomeType").asText());
                var outcome = objectMapper.readTree(material.path("outcome").path("canonicalJson").asText());
                assertEquals("platform_tool_result_boundary_unknown", outcome.path("failure").path("reasonCode").asText());
                assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'", tenantId, actualRun));
            } else {
                for (int index = 0; index < tools.size(); index++) {
                    var action = tools.get(index);
                    assertEquals("SUCCEEDED", action.path("status").asText());
                    assertEquals("SUCCEEDED", action.path("result_kind").asText());
                    assertTrue(action.path("adopted").asBoolean());
                    var material = objectMapper.readTree(action.path("result").asText());
                    var outcome = objectMapper.readTree(material.path("outcome").path("canonicalJson").asText());
                    var envelope = objectMapper.readTree(outcome.path("observation").path("canonicalJson").asText());
                    assertTrue(envelope.path("isError").isBoolean());
                    assertEquals(index == 0, envelope.path("isError").asBoolean());
                    var result = envelope.path("structuredContent");
                    assertTrue(result.path("success").isBoolean());
                    assertEquals(index == 1, result.path("success").asBoolean());
                    if (index == 0) assertFalse(result.path("error").asText().isBlank());
                    else {
                        assertEquals("Asia/Shanghai", result.path("timezone").asText());
                        assertEquals("2026-01-01T08:00:00+08:00", result.path("value").asText());
                    }
                    JsonNode received = null;
                    for (var message : model.requests.get(index + 1).path("messages")) {
                        if ("tool".equals(message.path("role").asText()) && ("datetime-" + (index + 1)).equals(message.path("tool_call_id").asText()))
                            received = objectMapper.readTree(message.path("content").asText());
                    }
                    assertNotNull(received);
                    assertEquals(result, received, "下一模型必须收到本次实际工具结果");
                }
            }
        }
    }

    private final class DatetimeModel implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final List<Object> responses = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();

        DatetimeModel() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                try (exchange) {
                    var request = objectMapper.readTree(exchange.getRequestBody()); requests.add(request.deepCopy());
                    int ordinal = requests.size();
                    if (ordinal > 3) throw new java.io.IOException("unexpected_datetime_model_call");
                    String name = "";
                    for (var tool : request.path("tools")) {
                        var properties = tool.path("function").path("parameters").path("properties");
                        if (properties.has("datetime") && properties.has("target_timezone")) name = tool.path("function").path("name").asText();
                    }
                    if (name.isBlank()) throw new java.io.IOException("convert_datetime_not_mounted");
                    var actual = objectMapper.createArrayNode();
                    for (int index = 1; index < ordinal; index++) {
                        JsonNode observed = null;
                        for (var message : request.path("messages")) {
                            if ("tool".equals(message.path("role").asText()) && ("datetime-" + index).equals(message.path("tool_call_id").asText()))
                                observed = objectMapper.readTree(message.path("content").asText());
                        }
                        if (observed == null || !observed.path("success").isBoolean()) throw new java.io.IOException("datetime_result_missing_" + index);
                        if (index == 1 && (observed.path("success").asBoolean() || observed.path("error").asText().isBlank()))
                            throw new java.io.IOException("datetime_rejection_required_before_correction");
                        if (index == 2 && (!observed.path("success").asBoolean() || !"2026-01-01T08:00:00+08:00".equals(observed.path("value").asText())))
                            throw new java.io.IOException("datetime_conversion_result_invalid");
                        actual.add(observed);
                    }
                    Object delta; String finish;
                    if (ordinal <= 2) {
                        var arguments = Map.of("datetime", "2026-01-01T00:00:00Z", "target_timezone", ordinal == 1 ? "Asia/Beijing" : "Asia/Shanghai");
                        delta = Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "datetime-" + ordinal,
                                "type", "function", "function", Map.of("name", name, "arguments", objectMapper.writeValueAsString(arguments)))));
                        finish = "tool_calls";
                    } else {
                        delta = Map.of("role", "assistant", "content", "CONVERT_DATETIME_OBSERVED\n```json\n" + objectMapper.writeValueAsString(actual) + "\n```");
                        finish = "stop";
                    }
                    var chunk = Map.of("id", "datetime-model-" + ordinal, "object", "chat.completion.chunk", "model", request.path("model").asText(),
                            "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)),
                            "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15));
                    responses.add(chunk);
                    byte[] body = ("data: " + objectMapper.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } catch (Exception failure) { errors.add(failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
            });
        }

        @Override public void close() { server.stop(0); }
    }
}
