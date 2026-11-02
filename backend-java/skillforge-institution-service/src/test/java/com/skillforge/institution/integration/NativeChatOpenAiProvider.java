package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 当前 Native Chat 协议的本地 HTTP 夹具；不访问供应商，不保存请求正文。 */
final class NativeChatOpenAiProvider implements AutoCloseable {
    private final ObjectMapper json;
    private final String runId;
    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final CopyOnWriteArrayList<String> kinds = new CopyOnWriteArrayList<>();
    private volatile boolean hitl, truncated, httpFailure, repeatedTemplate;
    private volatile boolean unavailableTool, unavailableToolCorrectionObserved;
    private volatile String forcedPublicRejection;
    private volatile boolean genericToolInput;
    private volatile String fixedWorkload;
    private volatile String repeatedMcpTicket;
    private volatile boolean mixedResources, changedFifthResource;
    private volatile boolean singleMcp;
    private volatile String fixedWorkloadFailure = "";
    private final AtomicInteger fixedRequests = new AtomicInteger();
    private final CopyOnWriteArrayList<Long> fixedServerNanos = new CopyOnWriteArrayList<>();
    private volatile boolean heldStream, streamCompleted, abortStream;
    private volatile int heldStreamStage;
    private final CountDownLatch advanceStream = new CountDownLatch(1);
    private final CountDownLatch finishStream = new CountDownLatch(1);

    /** 原生assistant正文的两个独立段；仅测试控制器能释放下段与完成信号。 */
    static final String STREAM_FIRST = "FIRST_STREAM_VISIBLE\n\n" + "第一段正在从模型生成并持久传送。".repeat(96);
    static final String STREAM_SECOND = "\n\nSECOND_STREAM_VISIBLE\n\n" + "第二段继续增量生成，执行仍未完成。".repeat(96);
    static final String STREAM_TAIL = "\n\nSTREAM_FINISHED";

    NativeChatOpenAiProvider(ObjectMapper json, String runId) throws IOException {
        this.json = json; this.runId = runId;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handle);
    }
    void start() { server.start(); }
    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    int requestCount() { return requests.get(); }
    List<String> responseKinds() { return List.copyOf(kinds); }
    void enableAgentHitl() { hitl = true; }
    void enableTruncatedDecision() { truncated = true; }
    void enableUnavailableTool() { unavailableTool = true; }
    boolean unavailableToolCorrectionObserved() { return unavailableToolCorrectionObserved; }
    void enableHttpFailure() { httpFailure = true; }
    void enableGenericToolInput() { genericToolInput = true; }
    void enableRepeatedTemplate() { repeatedTemplate = true; }
    void enableRepeatedMcp(String ticket) { repeatedMcpTicket = ticket; }
    void enableMixedMcp(String ticket, boolean changed) { repeatedMcpTicket = ticket; mixedResources = true; changedFifthResource = changed; }
    void enableSingleMcp(String ticket) { repeatedMcpTicket = ticket; singleMcp = true; }
    void beginFixedToolWorkload(String sample) {
        fixedRequests.set(0); fixedServerNanos.clear(); fixedWorkloadFailure = ""; fixedWorkload = sample;
    }
    String fixedWorkloadFailure() { return fixedWorkloadFailure; }
    List<Long> fixedWorkloadServerNanos() { return List.copyOf(fixedServerNanos); }
    static String fixedToolResult(String sample, int step) { return "runtime-latency-" + sample + "-" + step; }
    void holdStreamCompletion() { heldStream = true; }
    void advanceHeldStream() { advanceStream.countDown(); }
    void finishHeldStream() { finishStream.countDown(); }
    /** 明确结束已flush的本次响应，不发送finish/usage/DONE；不与server关闭争抢。 */
    void abortHeldStream() { abortStream = true; advanceStream.countDown(); finishStream.countDown(); }
    boolean heldStreamCompleted() { return streamCompleted; }
    /** 真实 HTTP flush 之后发布阶段，浏览器不能以请求尚未发出时的空白作为通过依据。 */
    int heldStreamStage() { return heldStreamStage; }
    /** 只生成无效合成凭据，固定危险 Respond 候选以验证宿主门禁，不依赖模型随机行为。 */
    String enablePublicOutputRejection() {
        forcedPublicRejection = "password=deterministic_guard_synthetic_" + runId;
        return forcedPublicRejection;
    }
    @Override public void close() {
        advanceStream.countDown(); finishStream.countDown(); server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (fixedWorkload == null) { handleRequest(exchange); return; }
        long started = System.nanoTime();
        try { handleRequest(exchange); }
        catch (IOException | RuntimeException failure) {
            fixedWorkloadFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            System.err.println("FIXED_TOOL_PROVIDER_FAILURE " + fixedWorkloadFailure);
            respond(exchange, "application/json", 500, "{\"error\":{\"message\":\"fixed workload fixture rejected request\"}}");
        }
        finally { fixedServerNanos.add(System.nanoTime() - started); }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        int ordinal = requests.incrementAndGet();
        JsonNode request;
        try (var input = exchange.getRequestBody()) { request = json.readTree(input); }
        if (heldStream) {
            if (!request.path("stream").asBoolean()) throw new IOException("held_fixture_requires_true_stream");
            kinds.add("held_answer");
            writeHeldStream(exchange, request.path("model").asText());
            return;
        }
        if (httpFailure) {
            kinds.add("http_500");
            respond(exchange, "application/json", 500, "{\"error\":{\"message\":\"fixture failure\"}}");
            return;
        }
        String finish = "stop";
        boolean jsonObject="json_object".equals(request.path("response_format").path("type").asText());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("reasoning_content", "fixture-reasoning-" + runId);
        if (jsonObject) {
            // 明确响应JSON_OBJECT线协议，不能把原生tool_call或普通文本冒充对象正文。
            if(hitl&&!approved(request)) {
                kinds.add("json_object_ask_user");message.put("content",json.writeValueAsString(Map.of("type","ask_user","question","是否确认继续？")));
            } else {
                kinds.add("json_object_respond");message.put("content",json.writeValueAsString(Map.of("type","respond","output","fixture-answer-"+runId,"evidence_item_ids",List.of())));
            }
        } else if (mixedResources && ordinal > 5) {
            if (!request.path("messages").toString().contains("FROZEN_DETAIL_REFERENCE") || !request.path("messages").toString().contains("QUERY-7"))
                throw new IOException("mixed_fixture_final_results_missing");
            kinds.add("mixed_complete"); message.put("content", "已根据查询与参考资料完成。");
        } else if (repeatedMcpTicket != null && singleMcp && ordinal > 1) {
            kinds.add("single_mcp_complete"); message.put("content", "已记录确定失败。");
        } else if (repeatedMcpTicket != null) {
            String name = null; boolean wrapped = false;
            for (JsonNode tool : request.path("tools")) {
                var function = tool.path("function"); var properties = function.path("parameters").path("properties");
                boolean candidateWrapped = properties.path("arguments").path("properties").has("ticket");
                if (!properties.has("ticket") && !candidateWrapped) continue;
                if (name != null) throw new IOException("repeat_mcp_fixture_ambiguous_tool");
                name = function.path("name").asText(); wrapped = candidateWrapped;
            }
            if (name == null) throw new IOException("repeat_mcp_fixture_tool_missing");
            var arguments = Map.of("ticket", repeatedMcpTicket);
            kinds.add("repeat_mcp_failure");
            var business = Map.of("id", "repeat-mcp-" + ordinal, "type", "function",
                    "function", Map.of("name", name, "arguments", json.writeValueAsString(wrapped ? Map.of("arguments", arguments) : arguments)));
            if (mixedResources) {
                String handle = "";
                for (var tool : request.path("tools")) if ("read_skill_resource".equals(tool.path("function").path("name").asText()))
                    handle = tool.path("function").path("parameters").path("properties").path("skill_handle").path("enum").path(0).asText();
                if (handle.isBlank()) throw new IOException("mixed_fixture_skill_handle_missing");
                String path = changedFifthResource && ordinal == 5 ? "references/detail.md" : "references/guide.md";
                var resource = Map.of("id", "repeat-resource-" + ordinal, "type", "function", "function", Map.of("name", "read_skill_resource",
                        "arguments", json.writeValueAsString(Map.of("skill_handle", handle, "path", path))));
                message.put("tool_calls", List.of(business, resource));
            } else message.put("tool_calls", List.of(business));
            finish = "tool_calls";
        } else if (repeatedTemplate) {
            String name = null; boolean wrapped = false;
            for (JsonNode tool : request.path("tools")) {
                var function = tool.path("function"); var properties = function.path("parameters").path("properties");
                boolean candidateWrapped = properties.path("arguments").path("properties").has("template");
                if (!properties.has("template") && !candidateWrapped) continue;
                if (name != null) throw new IOException("repeat_fixture_ambiguous_tool");
                name = function.path("name").asText(); wrapped = candidateWrapped;
            }
            if (name == null) throw new IOException("repeat_fixture_tool_missing");
            var arguments = Map.of("template", "REPEAT-{{number}}", "data", Map.of("number", 7));
            kinds.add("repeat_template");
            message.put("tool_calls", List.of(Map.of("id", "repeat-" + ordinal, "type", "function",
                    "function", Map.of("name", name, "arguments", json.writeValueAsString(wrapped ? Map.of("arguments", arguments) : arguments)))));
            finish = "tool_calls";
        } else if (fixedWorkload != null) {
            int step = fixedRequests.incrementAndGet();
            if (step > 6) throw new IOException("fixed workload exceeded six model calls");
            if (step > 1 && !request.path("messages").toString().contains(fixedToolResult(fixedWorkload, step - 1)))
                throw new IOException("fixed workload previous rendered result missing");
            if (step <= 5) {
                String name = null;
                for (JsonNode tool : request.path("tools")) {
                    String candidate = tool.path("function").path("name").asText();
                    JsonNode business = tool.path("function").path("parameters").path("properties");
                    if (!candidate.isBlank() && business.has("template") && business.has("data")) {
                        if (name != null) throw new IOException("fixed workload has ambiguous template tools");
                        name = candidate;
                    }
                }
                if (name == null) {
                    var declarations = new ArrayList<FixedToolDeclaration>();
                    for (JsonNode tool : request.path("tools")) {
                        var function = tool.path("function");
                        declarations.add(new FixedToolDeclaration(function.path("name").asText(), function.path("parameters")));
                    }
                    System.err.println("FIXED_TOOL_DECLARATIONS " + json.writeValueAsString(declarations));
                    throw new IOException("fixed workload has no mounted template tool");
                }
                kinds.add("fixed_template_render");
                message.put("tool_calls", List.of(Map.of("id", "fixture-fixed-" + fixedWorkload + "-" + step,
                        "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(Map.of(
                                "template", "runtime-latency-" + fixedWorkload + "-{{quantity}}", "data", Map.of("quantity", step)))))));
                finish = "tool_calls";
            } else {
                kinds.add("fixed_answer");
                message.put("content", "fixed-workload-completed-" + fixedWorkload);
            }
        } else if (unavailableTool && ordinal == 1) {
            String unavailable = "unavailable_fixture_tool";
            for (JsonNode tool : request.path("tools")) {
                if (unavailable.equals(tool.path("function").path("name").asText()))
                    throw new IOException("unavailable fixture tool unexpectedly declared");
            }
            kinds.add("unavailable_tool");
            message.put("tool_calls", List.of(Map.of("id", "fixture-unavailable-1", "type", "function",
                    "function", Map.of("name", unavailable, "arguments", "{}"))));
            finish = "tool_calls";
        } else if (unavailableTool) {
            if (ordinal != 2) throw new IOException("unavailable tool fixture exceeded two requests");
            for (JsonNode previous : request.path("messages")) {
                String text = previous.path("content").toString();
                if (text.contains("TOOL_NOT_AVAILABLE") && text.contains("NOT_DISPATCHED")
                        && text.contains("只能使用当前请求提供的工具")
                        && text.contains("本决定未派发任何工具")) unavailableToolCorrectionObserved = true;
            }
            if (!unavailableToolCorrectionObserved) throw new IOException("unavailable tool correction missing from next request");
            kinds.add("corrected_answer");
            message.put("content", "当前未提供所需工具，本次没有执行该操作。");
        } else if (genericToolInput && ordinal == 1) {
            String name = null;
            for (JsonNode tool : request.path("tools")) {
                String candidate = tool.path("function").path("name").asText();
                JsonNode business = tool.path("function").path("parameters").path("properties").path("arguments").path("properties");
                if (candidate.startsWith("sf_tool_") && business.has("template") && business.has("data")) {
                    if (name != null) throw new IOException("generic fixture has ambiguous template tools");
                    name = candidate;
                }
            }
            if (name == null) throw new IOException("generic fixture has no mounted native tool");
            kinds.add("generic_tool_input");
            message.put("content", "我会先生成模板，请补充需要的数量。");
            message.put("tool_calls", List.of(Map.of("id", "fixture-generic-1", "type", "function",
                    "function", Map.of("name", name, "arguments", json.writeValueAsString(Map.of(
                            "commentary", "请补充本次验收数量", "arguments", Map.of(
                                    "template", LivePublicInteractiveFixture.TEMPLATE, "data", Map.of())))))));
            finish = "tool_calls";
        } else if (genericToolInput) {
            kinds.add("generic_respond"); message.put("content", "验收补参数量27");
        } else if (forcedPublicRejection != null) {
            kinds.add("respond_public_rejection");
            message.put("content", forcedPublicRejection);
        } else if (truncated && ordinal == 1) {
            kinds.add("truncated_decision");
            message.put("content", "unfinished"); finish = "length";
        } else if (hitl && !approved(request)) {
            kinds.add("ask_user");
            message.put("tool_calls", List.of(Map.of("id", "fixture-hitl-" + ordinal,
                    "type", "function", "function", Map.of("name", "ask_user",
                            "arguments", json.writeValueAsString(Map.of("question", "是否确认继续？"))))));
            finish = "tool_calls";
        } else {
            if (hitl) kinds.add("respond");
            message.put("content", hitl ? "HITL 已确认" : "fixture-answer-" + runId);
        }
        String model = request.path("model").asText("runtime-fixture");
        Map<String, Object> usage = Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18);
        if (!request.path("stream").asBoolean()) {
            respond(exchange, "application/json", 200, json.writeValueAsString(Map.of(
                    "id", "fixture-" + runId, "object", "chat.completion", "model", model,
                    "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish)), "usage", usage)));
            return;
        }
        // 每个原生调用使用独立位置；正文和内部推理仍走真实流式解析边界。
        if (message.containsKey("tool_calls")) {
            var indexed = new ArrayList<Map<String, Object>>();
            for (Object raw : (List<?>) message.get("tool_calls")) {
                var call = new LinkedHashMap<String, Object>();
                ((Map<?, ?>) raw).forEach((key, value) -> call.put(key.toString(), value));
                call.put("index", indexed.size()); indexed.add(call);
            }
            message.put("tool_calls", indexed);
        }
        String first = json.writeValueAsString(Map.of("id", "fixture-" + runId,
                "object", "chat.completion.chunk", "model", model,
                "choices", List.of(Map.of("index", 0, "delta", message))));
        String last = json.writeValueAsString(Map.of("id", "fixture-" + runId,
                "object", "chat.completion.chunk", "model", model,
                "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)), "usage", usage));
        respond(exchange, "text/event-stream", 200, "data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n");
    }

    private boolean approved(JsonNode request) {
        for (JsonNode message : request.path("messages")) {
            if (message.path("content").toString().contains("agent-hitl-approved-" + runId)) return true;
        }
        return false;
    }

    /** 夹具失败只记录声明，不记录请求消息、HTTP头或凭据。 */
    private record FixedToolDeclaration(String name, JsonNode schema) { }

    /** 真 HTTP chunked SSE，完成屏障未释放时绝不发送 finish_reason、usage 或 DONE。 */
    private void writeHeldStream(HttpExchange exchange, String model) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (var output = exchange.getResponseBody()) {
            writeChunk(output, model, Map.of("content", STREAM_FIRST,
                    "reasoning_content", "fixture-reasoning-" + runId), false);
            heldStreamStage = 1;
            awaitRelease(advanceStream);
            if (abortStream) { heldStreamStage = -1; return; }
            writeChunk(output, model, Map.of("content", STREAM_SECOND), false);
            heldStreamStage = 2;
            awaitRelease(finishStream);
            if (abortStream) { heldStreamStage = -1; return; }
            writeChunk(output, model, Map.of("content", STREAM_TAIL), false);
            writeChunk(output, model, Map.of(), true);
            output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
            streamCompleted = true;
            heldStreamStage = 3;
        } finally { exchange.close(); }
    }

    /** 每段 flush 是真实传输边界，计量只在最后的供应商事件出现。 */
    private void writeChunk(java.io.OutputStream output, String model, Map<String, ?> delta, boolean completed) throws IOException {
        var choice = new LinkedHashMap<String, Object>();
        choice.put("index", 0); choice.put("delta", delta);
        if (completed) choice.put("finish_reason", "stop");
        var chunk = new LinkedHashMap<String, Object>();
        chunk.put("id", "fixture-" + runId); chunk.put("object", "chat.completion.chunk");
        chunk.put("model", model); chunk.put("choices", List.of(choice));
        if (completed) chunk.put("usage", Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18));
        output.write(("data: " + json.writeValueAsString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8)); output.flush();
    }

    /** 屏障失败显式结束夹具，不用 sleep 假设浏览器已收到正文。 */
    private void awaitRelease(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(120, TimeUnit.SECONDS)) throw new IOException("held_stream_barrier_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("held_stream_interrupted", interrupted);
        }
    }
    private void respond(HttpExchange exchange, String type, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
        finally { exchange.close(); }
    }
}
