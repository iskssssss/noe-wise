package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.execution.browser.BrowserDevices;
import com.skillforge.execution.browser.BrowserParameters;
import com.skillforge.execution.browser.BrowserReceipt;
import com.skillforge.execution.browser.BrowserRequest;
import com.skillforge.institution.runtime.AgentRuntimeExecutionWorker;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 隔离 PG/Redis 与真实 Runtime、BrowserClient；模型和浏览器服务是受控 HTTP 夹具。 */
class BrowserCurrentReadIntegrationTest extends BrowserConnectionIntegrationSupport {
    private static PageBrowser browser;
    private static AgentRuntimeExecutionWorker executionWorker;
    @Autowired private ExecutionBudgetPort budgets;

    @DynamicPropertySource
    static void browserAddress(DynamicPropertyRegistry registry) {
        registry.add("BROWSER_SERVICE_URL", () -> browser().endpoint());
        registry.add("BROWSER_SERVICE_TOKEN", () -> PageBrowser.TOKEN);
    }

    private static synchronized PageBrowser browser() {
        if (browser == null) {
            try { browser = new PageBrowser(); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        return browser;
    }

    @Autowired
    void registerExecutionWorker(AgentRuntimeExecutionWorker worker) {
        executionWorker = worker;
    }

    @AfterAll
    static void closeBrowser() {
        try {
            if (executionWorker != null) executionWorker.close();
        } finally {
            if (browser != null) {
                browser.close();
                assertTrue(browser.failures.isEmpty(), browser.failures.toString());
            }
        }
    }

    @Test
    void repeatedObservationAfterClickCreatesNewAttemptAndFeedsUpdatedPageToModel() throws Exception {
        try (var model = new PageModel(json)) {
            var initial = fixture(model.endpoint());
            String agent = api(initial.token(), "/api/v1/agents/revise", Map.of("agent_id", initial.agent()), 200).path("id").asText();
            String tool = "browser_call";
            var definitions = get(initial.token(), "/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED");
            long definitionId = element(definitions, "capability_key", "tool:" + tool).path("id").asLong();
            var versions = get(initial.token(), "/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId=" + definitionId);
            String versionKey = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor())
                    .entries().stream().filter(entry -> entry.name().equals(tool)).findFirst().orElseThrow().publishedVersionKey();
            long version = element(versions, "version_key", versionKey).path("id").asLong();
            String deployment = "browser-current-read-" + com.skillforge.core.shared.config.SnowflakeIds.nextId();
            var deployed = api(initial.token(), "/api/v1/capabilities/deployments", Map.of("deployment_key", deployment, "tool_version_id", version,
                    "provider_type", "PLATFORM", "provider_ref", tool, "environment", "TEST", "status", "ACTIVE"), 201);
            api(initial.token(), "/api/v1/agents/capability-mounts", Map.of("agent_id", agent, "payload", Map.of("capability_type", "TOOL",
                    "capability_definition_id", Long.toString(definitionId), "capability_version_id", Long.toString(version), "tool_config", Map.of("tool_deployment_id", deployment,
                    "tool_deployment_revision", deployed.path("revision").asLong()), "priority", 0)), 201);
            api(initial.token(), "/api/v1/agents/publish", Map.of("agent_id", agent), 200);
            var fixture = new Fixture(initial.tenant(), initial.token(), agent, initial.user());
            String user = Long.toString(fixture.user());
            browser().owner = new Owner(fixture.tenant(), user);
            var scenario = begin(fixture, "current-read");
            await("页面更新后的运行完成", () -> {
                String status = runStatus(fixture, scenario.run());
                assertFalse(Set.of("FAILED", "CANCELLED", "TERMINATED").contains(status),
                        "运行提前结束: " + status + " model=" + model.failures + " browser=" + browser().failures);
                return "COMPLETED".equals(status);
            });
            assertTrue(model.failures.isEmpty(), model.failures.toString());
            var calls = List.copyOf(browser().calls);
            assertEquals(List.of("start", "observe", "click", "observe"), calls.stream().map(BrowserRequest::operation).toList());
            var first = calls.get(1); var second = calls.get(3);
            assertEquals(first.task(), second.task());
            assertEquals(first.parameters(), second.parameters());
            assertEquals("e1", ((BrowserParameters.ClickParameters) calls.get(2).parameters()).refValue());
            assertEquals(first.run(), second.run());
            assertNotEquals(first.invocation(), second.invocation());

            var rows = queryRows(jdbc, fixture.tenant(),
                    "SELECT a.action_id,a.status,a.current_attempt,a.selected_attempt,t.attempt_id,t.result_kind,t.adopted "
                    + "FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t "
                    + "ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id AND t.action_id=a.action_id "
                    + "WHERE a.tenant_id=? AND a.run_id=? AND a.kind='TOOL' ORDER BY a.action_ordinal,t.observation_sequence",
                    fixture.tenant(), scenario.run());
            var firstRow = rows.stream().filter(row -> first.invocation().equals(row.get("action_id"))).findFirst().orElseThrow();
            var secondRow = rows.stream().filter(row -> second.invocation().equals(row.get("action_id"))).findFirst().orElseThrow();
            assertEquals(1, rows.stream().filter(row -> first.invocation().equals(row.get("action_id"))).count());
            assertEquals(1, rows.stream().filter(row -> second.invocation().equals(row.get("action_id"))).count());
            assertNotEquals(firstRow.get("action_id"), secondRow.get("action_id"));
            assertNotEquals(firstRow.get("attempt_id"), secondRow.get("attempt_id"));
            for (var row : List.of(firstRow, secondRow)) {
                assertEquals("SUCCEEDED", row.get("status"));
                assertEquals("SUCCEEDED", row.get("result_kind"));
                assertEquals(row.get("attempt_id"), row.get("selected_attempt"));
                assertEquals(row.get("attempt_id"), row.get("current_attempt"));
                assertEquals(Boolean.TRUE, row.get("adopted"));
            }
            var requests = model.requests;
            assertEquals(5, requests.size());
            var finalMessages = requests.getLast().path("messages");
            assertTrue(toolText(finalMessages, "observe-before").contains("PAGE_BEFORE"));
            assertTrue(toolText(finalMessages, "observe-after").contains("PAGE_AFTER"));
            assertFalse(toolText(finalMessages, "observe-after").contains("PAGE_BEFORE"));
            var run = executions.load(runRef(fixture, scenario.run())).orElseThrow();
            assertEquals(4, run.progress().toolCalls());
            assertEquals(4, budgets.snapshot(run.identity().runRef()).runConsumed().tools());
            assertTrue(browser().failures.isEmpty(), browser().failures.toString());
        }
    }

    private static String toolText(JsonNode messages, String callId) {
        for (var message : messages)
            if ("tool".equals(message.path("role").asText()) && callId.equals(message.path("tool_call_id").asText()))
                return message.path("content").asText();
        throw new AssertionError("missing model tool message " + callId);
    }

    /** 顺序由实际工具反馈数驱动，task 取自首个成功启动反馈。 */
    private static final class PageModel implements AutoCloseable {
        private final HttpServer server;
        private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        private final List<String> failures = new CopyOnWriteArrayList<>();
        PageModel(ObjectMapper json) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                try {
                    var request = json.readTree(exchange.getRequestBody()); requests.add(request);
                    int results = 0; String task = null;
                    for (var message : request.path("messages")) if ("tool".equals(message.path("role").asText())) {
                        results++;
                        if (task == null && message.path("content").isTextual()) try (var parser = json.createParser(message.path("content").textValue())) {
                            JsonNode structured = json.readTree(parser);
                            if (structured != null && structured.path("task").isTextual()) task = structured.path("task").textValue();
                        }
                    }
                    String logical = results == 0 ? "browser_start" : results < 4 ? "browser_call" : null;
                    String id = switch (results) { case 0 -> "start-browser"; case 1 -> "observe-before";
                        case 2 -> "click-page"; case 3 -> "observe-after"; case 4 -> "answer";
                        default -> throw new IllegalStateException("unexpected model turn " + results); };
                    Map<String, Object> message;
                    if (logical == null) message = Map.of("role", "assistant", "content", "页面已更新");
                    else {
                        String arguments = "{}";
                        if (results > 0) {
                            if (task == null || !UUID.fromString(task).toString().equals(task)) throw new IllegalStateException("missing task");
                            String operation = results == 2 ? "click" : "observe";
                            Map<String, Object> parameters = results == 2 ? Map.of("ref", "e1") : Map.of();
                            arguments = json.writeValueAsString(Map.of("task", task, "command", Map.of("operation", operation, "parameters", parameters)));
                        }
                        String name = selectTool(json, request, logical);
                        message = Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", id, "type", "function",
                                "function", Map.of("name", name, "arguments", arguments))));
                    }
                    String finish = logical == null ? "stop" : "tool_calls";
                    var usage = Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18);
                    String body;
                    if (request.path("stream").asBoolean()) {
                        String first = json.writeValueAsString(Map.of("id", "current-read", "object", "chat.completion.chunk", "model", request.path("model").asText(),
                                "choices", List.of(Map.of("index", 0, "delta", message))));
                        String last = json.writeValueAsString(Map.of("id", "current-read", "object", "chat.completion.chunk", "model", request.path("model").asText(),
                                "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)), "usage", usage));
                        body = "data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n";
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    } else {
                        body = json.writeValueAsString(Map.of("id", "current-read", "object", "chat.completion", "model", request.path("model").asText(),
                                "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish)), "usage", usage));
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Exception failure) {
                    failures.add(failure.toString());
                    byte[] body = "{\"error\":\"fixture_failure\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(500, body.length); exchange.getResponseBody().write(body);
                } finally { exchange.close(); }
            });
            server.start();
        }
        private static String selectTool(ObjectMapper json, JsonNode request, String logical) {
            var expected = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor())
                    .entries().stream().filter(value -> logical.equals(value.name())).findFirst().orElseThrow();
            JsonNode schema = json.valueToTree(expected.parametersSchema()); String selected = null;
            for (var item : request.path("tools")) {
                var function = item.path("function"); String description = function.path("description").asText();
                if (!description.contains("logical_name=\"" + logical + "\"\n")
                        || !description.contains("qualified_name=\"platform:" + logical + "/" + logical + "\"\n")
                        || !schema.equals(function.path("parameters"))) continue;
                if (selected != null) throw new IllegalStateException("ambiguous tool " + logical);
                selected = function.path("name").asText();
            }
            if (selected == null || selected.isBlank()) throw new IllegalStateException("missing tool " + logical);
            return selected;
        }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }

    /** 唯一测试用户的本地协议端；click 只改变该 task 的模拟页面内容。 */
    private static final class PageBrowser implements AutoCloseable {
        static final String TOKEN = "synthetic-current-read-browser-token";
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final List<BrowserRequest> calls = new CopyOnWriteArrayList<>();
        private final List<String> failures = new CopyOnWriteArrayList<>();
        private volatile Owner owner;
        private BrowserReceipt started;
        private boolean clicked;
        PageBrowser() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers); server.createContext("/internal/browser", this::handle); server.start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        private void handle(HttpExchange exchange) throws IOException {
            try {
                if (!TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Browser-Service-Token"))) throw new IllegalStateException("invalid token");
                String path = exchange.getRequestURI().getPath().substring("/internal/browser".length());
                if ("GET".equals(exchange.getRequestMethod()) && "/devices".equals(path)) {
                    var query = query(exchange.getRequestURI().getRawQuery());
                    if (!Objects.equals(owner, new Owner(query.get("tenant"), query.get("user")))) throw new IllegalStateException("wrong owner");
                    var device = new BrowserDevices.Device("page-device", "page-browser", "模拟浏览器", "2099-01-01T00:00:00Z", true);
                    send(exchange, new BrowserDevices(List.of(device), "https://example.invalid/install", "page-browser"));
                } else if ("POST".equals(exchange.getRequestMethod()) && "/execute".equals(path)) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    var request = json.readValue(body, BrowserRequest.class); calls.add(request);
                    send(exchange, execute(request, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))));
                } else throw new IllegalStateException("unexpected browser route " + path);
            } catch (Exception failure) {
                failures.add(failure.toString()); byte[] body = "{\"error\":\"fixture_failure\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length); exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        }
        private synchronized BrowserReceipt execute(BrowserRequest request, String fingerprint) {
            if (!Objects.equals(owner, new Owner(request.tenant(), request.endUser())) || !request.deadlineAt().isAfter(Instant.now()))
                throw new IllegalStateException("invalid browser request");
            String task;
            if ("start".equals(request.operation())) {
                if (!(request.parameters() instanceof BrowserParameters.StartParameters start) || !"page-browser".equals(start.browser()))
                    throw new IllegalStateException("wrong device");
                task = UUID.nameUUIDFromBytes(request.invocation().getBytes(StandardCharsets.UTF_8)).toString();
            } else {
                if (started == null || !Objects.equals(started.task(), request.task()) || !started.run().equals(request.run())
                        || !started.session().equals(request.session())) throw new IllegalStateException("wrong task");
                task = request.task();
            }
            var result = json.createObjectNode().put("ok", true);
            if ("click".equals(request.operation())) clicked = true;
            if ("observe".equals(request.operation())) result.put("text", clicked ? "PAGE_AFTER" : "PAGE_BEFORE");
            var receipt = new BrowserReceipt(fingerprint, request.invocation(), request.operation(), task, request.session(), request.run(),
                    Instant.now().toString(), "succeeded", null, "page-browser",
                    new BrowserReceipt.BrowserBinding("upstream-session:" + task, "instance:" + task, 1L), result);
            if ("start".equals(request.operation())) started = receipt;
            return receipt;
        }
        private void send(HttpExchange exchange, Object value) throws IOException {
            byte[] body = json.writeValueAsBytes(value); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
        }
        private static Map<String, String> query(String raw) {
            var values = new LinkedHashMap<String, String>();
            for (String part : raw.split("&")) {
                String[] pair = part.split("=", 2);
                values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            return values;
        }
        @Override public void close() { server.stop(0); workers.close(); }
    }
    private record Owner(String tenant, String user) { }
}
