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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** 本地知识检索 HTTP 夹具：知识结果之后直接输出带可信引用的正文，不访问外部供应商。 */
final class DeterministicOpenAiProvider implements AutoCloseable {
    private final ObjectMapper mapper;
    private final String runId;
    private final String sentinel;
    private final HttpServer server;
    private final CopyOnWriteArrayList<String> responseKinds = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Long> requestServerElapsedNanos = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> failures = new CopyOnWriteArrayList<>();
    private volatile KnowledgeExpected knowledge;
    private volatile List<String> verifiedEvidenceItemIds = List.of();
    private volatile List<String> verifiedCitationIds = List.of();
    private volatile String pairedKnowledgeCallId;
    private int requestCount;

    /** sentinel 绑定当前隔离知识文档，最终答复必须引用实际注入的条目。 */
    DeterministicOpenAiProvider(ObjectMapper mapper, String runId, String sentinel) throws IOException {
        this.mapper = mapper;
        this.runId = runId;
        this.sentinel = sentinel;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/chat/completions", this::handle);
    }

    void start() { server.start(); }
    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    synchronized int requestCount() { return requestCount; }
    List<String> responseKinds() { return List.copyOf(responseKinds); }
    List<String> failures() { return List.copyOf(failures); }
    List<String> verifiedEvidenceItemIds() { return verifiedEvidenceItemIds; }
    String finalAnswer() { return "基于随机知识文档 " + sentinel + " 的回答"
            + verifiedCitationIds.stream().map(id -> "[[cite:" + id + "]]").collect(java.util.stream.Collectors.joining()); }
    String publicFinalAnswer() {
        var answer = new StringBuilder("基于随机知识文档 " + sentinel + " 的回答");
        for (int index = 0; index < verifiedCitationIds.size(); index++) answer.append("[知识依据 ").append(index + 1)
                .append("](#citation-").append(verifiedCitationIds.get(index)).append(")");
        return answer.toString();
    }
    String pairedKnowledgeCallId() { return pairedKnowledgeCallId; }

    /** 正式挂载回执在首个模型请求前绑定；来源和版本不能在收到结果后补造。 */
    synchronized void requireKnowledge(String handle, String sourceId, String sourceVersion) {
        if (knowledge != null || requestCount != 0
                || List.of(handle, sourceId, sourceVersion).stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("fixture_knowledge_registration_invalid");
        knowledge = new KnowledgeExpected(handle, sourceId, sourceVersion);
    }

    /** 仅统计固定 fixture 处理时间，不代表 Runtime 客户端或真实供应商耗时。 */
    List<Long> requestServerElapsedNanos() { return List.copyOf(requestServerElapsedNanos); }

    /** 同一供应商边界处理流式与非流式独立工具调用。 */
    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        try {
            JsonNode request;
            try (var input = exchange.getRequestBody()) { request = mapper.readTree(input); }
            int ordinal;
            synchronized (this) { ordinal = ++requestCount; }
            if (knowledge == null || ordinal > 2) throw new IOException("fixture_unexpected_model_request");
            String wireName;
            Map<String, Object> arguments;
            if (ordinal == 1) {
                wireName = "knowledge_query";
                responseKinds.add("knowledge_query");
                arguments = Map.of("knowledge_handle", exposedKnowledgeHandle(request), "query", sentinel,
                        "commentary", "我先检索与问题相关的知识文档");
            } else {
                verifiedEvidenceItemIds = injectedEvidenceItems(request);
                wireName = null;
                responseKinds.add("respond");
                arguments = Map.of();
            }
            if (wireName != null) requireExposedTool(request, wireName);
            var call = new LinkedHashMap<String, Object>();
            call.put("id", "knowledge-fixture-" + ordinal);
            call.put("type", "function");
            if (wireName != null) call.put("function", Map.of("name", wireName, "arguments", mapper.writeValueAsString(arguments)));
            var message = wireName == null ? Map.of("role", "assistant", "content", finalAnswer())
                    : Map.of("role", "assistant", "tool_calls", List.of(call));
            String finish = wireName == null ? "stop" : "tool_calls";
            var usage = Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18);
            String model = request.path("model").asText("runtime-fixture");
            boolean stream = request.path("stream").asBoolean();
            String body;
            if (stream) {
                if (wireName != null) call.put("index", 0);
                String first = mapper.writeValueAsString(Map.of("id", "fixture-" + runId,
                        "object", "chat.completion.chunk", "model", model,
                        "choices", List.of(Map.of("index", 0, "delta", message))));
                String last = mapper.writeValueAsString(Map.of("id", "fixture-" + runId,
                        "object", "chat.completion.chunk", "model", model,
                        "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)),
                        "usage", usage));
                body = "data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n";
            } else {
                body = mapper.writeValueAsString(Map.of("id", "fixture-" + runId,
                        "object", "chat.completion", "model", model,
                        "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish)),
                        "usage", usage));
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        } catch (IOException | RuntimeException failure) {
            String reason = failure.getMessage();
            failures.add(reason != null && reason.startsWith("fixture_")
                    ? reason : failure.getClass().getSimpleName());
            throw failure;
        } finally {
            exchange.close();
            requestServerElapsedNanos.add(System.nanoTime() - started);
        }
    }

    /** 假 Provider 也必须从真实冻结工具集合选择名称。 */
    private static void requireExposedTool(JsonNode request, String expected) {
        for (JsonNode tool : request.path("tools")) {
            if (expected.equals(tool.path("function").path("name").asText())) return;
        }
        throw new IllegalStateException("fixture_native_tool_not_exposed:" + expected);
    }

    /** 准确句柄只来自本次原生工具的封闭schema，不从提示词或用户正文推断。 */
    private String exposedKnowledgeHandle(JsonNode request) throws IOException {
        var declarations = new ArrayList<JsonNode>();
        for (var tool : request.path("tools"))
            if ("knowledge_query".equals(tool.path("function").path("name").asText())) declarations.add(tool);
        if (declarations.size() != 1) throw new IOException("fixture_knowledge_tool_not_unique");
        JsonNode handles = declarations.getFirst().path("function").path("parameters")
                .path("properties").path("knowledge_handle").path("enum");
        if (!handles.isArray() || handles.size() != 1 || !handles.get(0).isTextual()
                || !knowledge.handle().equals(handles.get(0).textValue()))
            throw new IOException("fixture_knowledge_handle_not_exposed");
        return handles.get(0).textValue();
    }

    /** 原assistant调用与TOOL回执按真实callId配对；只引用实际注入的准确来源条目。 */
    private List<String> injectedEvidenceItems(JsonNode request) throws IOException {
        String callId = null;
        for (JsonNode message : request.path("messages")) {
            if (!"assistant".equals(message.path("role").asText())) continue;
            for (var call : message.path("tool_calls")) {
                if (!"knowledge_query".equals(call.path("function").path("name").asText())) continue;
                var arguments = mapper.readTree(call.path("function").path("arguments").asText());
                if (callId != null || !knowledge.handle().equals(arguments.path("knowledge_handle").asText())
                        || !sentinel.equals(arguments.path("query").asText()))
                    throw new IOException("fixture_knowledge_call_mismatch_or_duplicate");
                callId = call.path("id").asText();
            }
        }
        if (callId == null || callId.isBlank()) throw new IOException("fixture_knowledge_call_missing");
        var ids = new LinkedHashSet<String>(); var citations = new LinkedHashSet<String>(); int paired = 0;
        for (var message : request.path("messages")) {
            if (!"tool".equals(message.path("role").asText())
                    || !callId.equals(message.path("tool_call_id").asText())) continue;
            paired++;
            var bundle = mapper.readTree(textContent(message.path("content")));
            if (!bundle.path("bundleId").isTextual() || bundle.path("bundleId").asText().isBlank()
                    || !bundle.path("items").isArray() || bundle.path("items").isEmpty())
                throw new IOException("fixture_injected_knowledge_bundle_missing");
            for (var item : bundle.path("items")) {
                String citation = item.path("citationId").asText();
                if (!citation.matches("k[0-9a-f]{16}") || !citations.add(citation)) throw new IOException("fixture_knowledge_citation_invalid");
                if (!item.path("itemId").isTextual() || item.path("itemId").asText().isBlank()
                        || !ids.add(item.path("itemId").textValue())
                        || !knowledge.sourceId().equals(item.path("sourceId").asText())
                        || !knowledge.sourceVersion().equals(item.path("sourceVersion").asText())
                        || !item.path("text").asText().contains(sentinel))
                    throw new IOException("fixture_injected_knowledge_item_mismatch");
            }
        }
        if (paired != 1 || ids.isEmpty()) throw new IOException("fixture_knowledge_tool_pair_missing_or_duplicate");
        pairedKnowledgeCallId = callId;
        verifiedCitationIds = List.copyOf(citations);
        return List.copyOf(ids);
    }

    private static String textContent(JsonNode content) throws IOException {
        if (content.isTextual()) return content.textValue();
        if (!content.isArray() || content.isEmpty()) throw new IOException("fixture_tool_text_missing");
        var text = new StringBuilder();
        for (var part : content) {
            if (!"text".equals(part.path("type").asText()) || !part.path("text").isTextual())
                throw new IOException("fixture_tool_text_invalid");
            text.append(part.path("text").textValue());
        }
        return text.toString();
    }

    private record KnowledgeExpected(String handle, String sourceId, String sourceVersion) { }

    @Override public void close() { server.stop(0); }
}
