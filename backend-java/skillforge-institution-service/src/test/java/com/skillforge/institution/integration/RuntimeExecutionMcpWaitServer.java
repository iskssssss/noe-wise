package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 确定性真实MCP HTTP端点，能力仍经正式发现、挂载、MCP Provider和typed等待协议执行。
 * 每个ticket保存原RPC请求身份，精确续答后只完成一次业务效果；无能力mock或放行授权。
 */
final class RuntimeExecutionMcpWaitServer implements AutoCloseable {
    static final String TOOL = "runtime_wait";
    static final String VERSION = "2026-07-28";
    private final ObjectMapper json;
    private final boolean write;
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final Map<String, Script> scripts = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Observed> requests = new CopyOnWriteArrayList<>();

    /** 隔离实例拥有loopback监听器和有界工作线程，close必须等待静止。 */
    RuntimeExecutionMcpWaitServer(ObjectMapper json) throws IOException {
        this(json, false);
    }

    RuntimeExecutionMcpWaitServer(ObjectMapper json, boolean write) throws IOException {
        this.json = Objects.requireNonNull(json);
        this.write = write;
        workers = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
                Thread.ofPlatform().daemon().name("runtime-mcp-fixture-", 0).factory());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/mcp", this::handle);
    }

    /** 明确启动已绑定端口，不读取网络账号或外部服务。 */
    void start() { server.start(); }

    /** 发布给真实Connector的完整MCP地址。 */
    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"; }

    /** 一个ticket对应一个原工具调用，不能重置以掩盖重放。 */
    Script register(String key, Mode mode) {
        if (key == null || !key.matches("[a-zA-Z0-9_-]{1,128}")) throw new IllegalArgumentException("invalid_mcp_script_key");
        var script = new Script(key, Objects.requireNonNull(mode));
        if (scripts.putIfAbsent(key, script) != null) throw new IllegalArgumentException("duplicate_mcp_script_key");
        return script;
    }

    /** 所有请求含失败都保留；只记录协议身份，不保存认证头或完整请求正文。 */
    List<Observed> requests() { return List.copyOf(requests); }

    /** 默认等待续答；取消竞争场景可在准确响应校验后阻停真实HTTP结果。 */
    enum Mode { WAIT, BLOCK_CONTINUATION_RESULT, DROP_INITIAL_RESULT, WRITE_ARGUMENTS_REJECTED, REPEATED_READ_FAILURE, OVERSIZED_READ_FAILURE, READ_QUERY }

    /** 先校验协议和原身份，错误返回固定JSON-RPC拒绝并保存失败观测。 */
    private void handle(HttpExchange exchange) throws IOException {
        String method = "";
        String rpc = "";
        String ticket = "";
        String failure = "";
        try {
            if (!"POST".equals(exchange.getRequestMethod())) throw new IllegalArgumentException("mcp_post_required");
            JsonNode request;
            try (var input = exchange.getRequestBody()) { request = json.readTree(input); }
            method = request.path("method").asText();
            rpc = request.path("id").asText();
            if (!"2.0".equals(request.path("jsonrpc").asText())) throw new IllegalArgumentException("mcp_rpc_required");
            switch (method) {
                case "initialize" -> respond(exchange, request, Map.of("protocolVersion", VERSION, "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "runtime-execution-wait-fixture", "version", "1")));
                case "notifications/initialized" -> exchange.sendResponseHeaders(202, -1);
                case "tools/list" -> respond(exchange, request, Map.of("tools", List.of(Map.of("name", TOOL,
                        "description", "保存原ticket，等待quantity表单后返回唯一实际结果", "inputSchema", inputSchema(),
                        "outputSchema", outputSchema(), "annotations", Map.of("readOnlyHint", !write, "idempotentHint", false)))));
                case "tools/call" -> {
                    var params = request.path("params");
                    if (!TOOL.equals(params.path("name").asText())) throw new IllegalArgumentException("mcp_tool_mismatch");
                    ticket = params.path("arguments").path("ticket").asText();
                    var script = scripts.get(ticket);
                    if (script == null) throw new IllegalArgumentException("mcp_ticket_missing");
                    int call = script.calls.incrementAndGet();
                    if (!script.rpcIds.add(rpc)) throw new IllegalArgumentException("mcp_rpc_replayed");
                    if (script.mode == Mode.OVERSIZED_READ_FAILURE) {
                        respond(exchange, request, Map.of("resultType", "complete", "content", List.of(), "isError", true,
                                "structuredContent", Map.of("status", 422, "error", "\\".repeat(90_000))));
                        break;
                    }
                    if (script.mode == Mode.REPEATED_READ_FAILURE) {
                        respond(exchange, request, Map.of("resultType", "complete", "content", List.of(), "isError", true,
                                "structuredContent", Map.of("status", 422, "error", "same-private-error")));
                        break;
                    }
                    if (script.mode == Mode.READ_QUERY) {
                        respond(exchange, request, Map.of("resultType", "complete", "content", List.of(), "isError", false,
                                "structuredContent", Map.of("success", true, "rendered", "QUERY-7")));
                        break;
                    }
                    if (call == 1) {
                        if (params.has("inputResponses") || params.has("requestState")) throw new IllegalArgumentException("mcp_initial_is_continuation");
                        script.initialReceived.countDown();
                        if (script.mode == Mode.DROP_INITIAL_RESULT) return;
                        if (script.mode == Mode.WRITE_ARGUMENTS_REJECTED) {
                            respond(exchange, request, Map.of("resultType", "complete", "content", List.of(), "isError", true,
                                    "structuredContent", Map.of("status", 422, "error", "private-fixture-error")));
                            break;
                        }
                        respond(exchange, request, Map.of("resultType", "input_required", "requestState", script.state(),
                                "inputRequests", Map.of("quantity", Map.of("method", "elicitation/create", "params", Map.of("mode", "form",
                                        "message", "提供本次ticket的quantity", "requestedSchema", Map.of("type", "object", "properties",
                                                Map.of("quantity", Map.of("type", "integer", "minimum", 27, "maximum", 27)), "required", List.of("quantity")))))));
                    } else {
                        if (call != 2 || !script.state().equals(params.path("requestState").asText())
                                || params.path("inputResponses").size() != 1 || !params.path("inputResponses").has("quantity"))
                            throw new IllegalArgumentException("mcp_continuation_mismatch");
                        var response = params.path("inputResponses").path("quantity");
                        if (!"accept".equals(response.path("action").asText())
                                || !response.path("content").path("quantity").isIntegralNumber()
                                || response.path("content").path("quantity").asInt() != 27)
                            throw new IllegalArgumentException("mcp_response_mismatch");
                        script.continuationReceived.countDown();
                        if (script.mode == Mode.BLOCK_CONTINUATION_RESULT && !script.release.await(30, TimeUnit.SECONDS))
                            throw new IllegalStateException("mcp_result_release_timeout");
                        if (script.effects.incrementAndGet() != 1) throw new IllegalStateException("mcp_effect_duplicated");
                        respond(exchange, request, Map.of("resultType", "complete", "content", List.of(), "isError", false,
                                "structuredContent", Map.of("success", true, "rendered", script.expectedResult())));
                    }
                }
                default -> throw new IllegalArgumentException("mcp_method_unsupported");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); failure = "mcp_fixture_interrupted";
        } catch (RuntimeException | IOException rejected) {
            failure = "mcp_fixture_rejected";
            if (exchange.getResponseCode() < 0) {
                byte[] bytes = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"fixture rejected request\"},\"id\":null}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, bytes.length);
                try (var body = exchange.getResponseBody()) { body.write(bytes); }
            }
        } finally {
            exchange.close();
            requests.add(new Observed(method, rpc, ticket, exchange.getResponseCode(), failure));
        }
    }

    /** 共享真实发布descriptor与MCP发现的固定参数schema。 */
    static Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("ticket", Map.of("type", "string")),
                "required", List.of("ticket"), "additionalProperties", false);
    }

    /** 实际工具完成结果必须同时包含success和rendered。 */
    static Map<String, Object> outputSchema() {
        return Map.of("type", "object", "properties", Map.of("success", Map.of("type", "boolean"), "rendered", Map.of("type", "string")),
                "required", List.of("success", "rendered"), "additionalProperties", false);
    }

    /** 原请求id原样回传，经过正式MCP wire解析，不直接构造ToolOutcome。 */
    private void respond(HttpExchange exchange, JsonNode request, Object result) throws IOException {
        var envelope = json.createObjectNode().put("jsonrpc", "2.0");
        envelope.set("id", request.path("id")); envelope.set("result", json.valueToTree(result));
        byte[] bytes = json.writeValueAsBytes(envelope);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var body = exchange.getResponseBody()) { body.write(bytes); }
    }

    /** 解除本实例屏障，停止监听并等待Handler退出，不关闭共享Docker或业务服务。 */
    @Override public void close() {
        scripts.values().forEach(script -> script.release.countDown());
        server.stop(0); workers.shutdownNow();
        try { if (!workers.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("mcp_fixture_not_quiescent"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("mcp_fixture_close_interrupted", interrupted); }
    }

    /** 一个真实MCP调用的只读计数与精确网络屏障，由测试控制取消竞争。 */
    static final class Script {
        final String key;
        final Mode mode;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger effects = new AtomicInteger();
        final java.util.Set<String> rpcIds = ConcurrentHashMap.newKeySet();
        final CountDownLatch initialReceived = new CountDownLatch(1);
        final CountDownLatch continuationReceived = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        /** 脚本只由register建立，不允许重置调用次数。 */
        Script(String key, Mode mode) { this.key = key; this.mode = mode; }
        /** 原服务端opaque等待状态，续答必须逐字相等。 */
        String state() { return "runtime-wait:" + key; }
        /** 与确定性模型夹具同一script的真实工具结果。 */
        String expectedResult() { return "runtime-workload-" + key + "-1"; }
        /** 只等待原请求到达，不发送或重试业务调用。 */
        boolean awaitInitial(Duration timeout) throws InterruptedException { return initialReceived.await(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        /** 等待真实续答已校验，用于在结果返回前取消原Run。 */
        boolean awaitContinuation(Duration timeout) throws InterruptedException { return continuationReceived.await(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        /** 释放唯一原响应，不发起新请求。 */
        void releaseResult() { release.countDown(); }
    }

    /** @param method RPC方法 @param rpcId 原请求id @param ticket 业务脚本键 @param httpStatus 实际响应状态 @param failure 固定失败原因 */
    record Observed(String method, String rpcId, String ticket, int httpStatus, String failure) { }
}
