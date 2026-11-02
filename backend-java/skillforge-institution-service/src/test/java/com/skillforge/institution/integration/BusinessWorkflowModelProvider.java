package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.time.Duration;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.core.agent.memory.MemoryJsonCodec;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 只替代外部模型决策；所有业务工具、等待续答与持久化均由真实 Runtime 执行。 */
final class BusinessWorkflowModelProvider implements AutoCloseable {
    private final ObjectMapper json;
    private final Path evidence;
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Map<String, Script> scripts = new ConcurrentHashMap<>();
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private final Map<String,ExtractionScript> extractionCalls=new ConcurrentHashMap<>();

    /** 端口由监听器原子分配，禁止复用开发模型端点。 */
    BusinessWorkflowModelProvider(ObjectMapper json, Path evidence) throws IOException {
        this.json = json;
        this.evidence = evidence;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/chat/completions", this::handle);
    }

    void start() { server.start(); }
    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    List<String> failures() { return List.copyOf(failures); }
    ExtractionScript registerExtraction(String group) {
        var script=new ExtractionScript();
        if(extractionCalls.putIfAbsent(group,script)!=null)throw new IllegalArgumentException("duplicate_extraction_script");
        return script;
    }

    /** 每个业务场景独立标记和固定工具序列，不按实际失败结果改变预期。 */
    Script register(String marker, List<Map<String, Object>> arguments, List<String> expectedResults, String finalText) {
        if (arguments.size() != expectedResults.size()) throw new IllegalArgumentException("script_result_count_mismatch");
        var script = new Script(marker, List.copyOf(arguments), List.copyOf(expectedResults), finalText, null);
        if (scripts.putIfAbsent(marker, script) != null) throw new IllegalArgumentException("duplicate_business_script");
        return script;
    }

    /** 同参schema的媒体工具按真实下发logical_name消歧，仍调用本次冻结的原生函数名称。 */
    Script registerMedia(String marker,String logicalName,Map<String,Object> arguments,String expectedResult,String finalText) {
        var script=new Script(marker,List.of(arguments),List.of(expectedResult),finalText,logicalName);
        if(scripts.putIfAbsent(marker,script)!=null)throw new IllegalArgumentException("duplicate_business_script");
        return script;
    }

    /** 原生工具名称取自实际下发 schema，不能把业务名硬编码为宿主内部函数名。 */
    private void handle(HttpExchange exchange) throws IOException {
        try {
            JsonNode request;
            try (var input = exchange.getRequestBody()) { request = json.readTree(input); }
            if(extraction(request,exchange))return;
            var script = currentScript(request);
            int ordinal = script.calls.incrementAndGet();
            saveToolDiagnostics(request, script, ordinal);
            if (ordinal > script.arguments.size() + 1) throw new IllegalStateException("unexpected_extra_model_call");
            script.awaitResponseRelease(ordinal);
            // 前一工具的真实返回必须出现在下一轮模型上下文中，等待卡不能冒充成功回执。
            if (ordinal > 1) requireToolResult(request, script, ordinal - 2);
            String name = null;
            Map<String, Object> arguments = Map.of();
            if (ordinal <= script.arguments.size()) {
                arguments = script.arguments.get(ordinal - 1);
                name = toolName(request, arguments.keySet(), script.logicalName);
            }
            var call = new LinkedHashMap<String, Object>();
            call.put("id", "business-" + script.marker + "-" + ordinal);
            call.put("type", "function");
            if (name != null) call.put("function", Map.of("name", name, "arguments", json.writeValueAsString(arguments)));
            String model = request.path("model").asText();
            var message = name == null ? Map.of("role", "assistant", "content", script.finalText)
                    : Map.of("role", "assistant", "tool_calls", List.of(call));
            String finish = name == null ? "stop" : "tool_calls";
            var usage = Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18);
            if (!request.path("stream").asBoolean()) {
                send(exchange, "application/json", 200, json.writeValueAsString(Map.of("id", "business-" + script.marker,
                        "object", "chat.completion", "model", model, "choices", List.of(Map.of("index", 0,
                                "message", message, "finish_reason", finish)), "usage", usage)));
                return;
            }
            if (name != null) call.put("index", 0);
            String first = json.writeValueAsString(Map.of("id", "business-" + script.marker,
                    "object", "chat.completion.chunk", "model", model,
                    "choices", List.of(Map.of("index", 0, "delta", message))));
            String last = json.writeValueAsString(Map.of("id", "business-" + script.marker,
                    "object", "chat.completion.chunk", "model", model,
                    "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)), "usage", usage));
            send(exchange, "text/event-stream", 200, "data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n");
        } catch (Exception failure) {
            String reason=failure instanceof IllegalStateException?failure.getMessage():failure.getClass().getSimpleName();
            failures.add(failure.getClass().getSimpleName() + ":" + reason);
            // 只保留稳定原因与合成脚本身份，避免等待超时掩盖首个Provider偏离；不保存原输入。
            try { Files.writeString(evidence.resolve("provider-failure-"+failures.size()+".json"),json.writeValueAsString(
                    new ProviderFailure(failure.getClass().getSimpleName(),reason)),StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW); }
            catch(IOException diagnosticFailure) { failures.add("provider_diagnostic_write_failed"); }
            send(exchange, "application/json", 500, "{\"error\":{\"message\":\"business fixture rejected model request\"}}");
        }
    }

    /** 正式群输入按来源顺序保留多个USER消息；脚本只取最新USER来源，不把旧会话标记当新任务。 */
    private Script currentScript(JsonNode request) {
        JsonNode messages=request.path("messages");
        for(int index=messages.size()-1;index>=0;index--) {
            JsonNode message=messages.get(index);
            if(!"user".equals(message.path("role").asText()))continue;
            String content=message.path("content").toString();
            var matches=scripts.values().stream().filter(script->content.contains(script.prompt())).toList();
            if(matches.isEmpty())continue;
            if(matches.size()!=1)throw new IllegalStateException("business_current_source_script_not_unique");
            return matches.getFirst();
        }
        throw new IllegalStateException("business_current_source_script_missing");
    }

    private record ProviderFailure(String failureType,String reason) { }

    /** 空候选仍经过真实 LlmService、供应商回执与用量结算；不 Mock 系统任务或计量服务。 */
    private boolean extraction(JsonNode request,HttpExchange exchange) throws IOException {
        var contract=new MemoryExtractionCandidates();
        for(JsonNode message:request.path("messages")) {
            if(!"user".equals(message.path("role").asText())||!message.path("content").isTextual())continue;
            String value=message.path("content").asText();
            if(!value.startsWith("{")||!value.contains("schemaVersion"))continue;
            final MemoryExtractionCandidates.ExtractionInput input;
            try { input=contract.decodeInput(value); }catch(IllegalArgumentException unrelated) { continue; }
            if(input.group()==null||!extractionCalls.containsKey(input.group().groupBindingId()))continue;
            if(input.schemaVersion()!=3)throw new IllegalStateException("group_extraction_version_invalid");
            var script=extractionCalls.get(input.group().groupBindingId());
            script.calls.incrementAndGet();script.entered.countDown();
            try {
                if(!script.released.await(30,TimeUnit.SECONDS))throw new IllegalStateException("extraction_usage_receipt_barrier_timeout");
            }catch(InterruptedException stopped) { Thread.currentThread().interrupt();throw new IOException("extraction_usage_interrupted",stopped); }
            String result=MemoryJsonCodec.encode(new MemoryExtractionCandidates.ExtractionOutput(3,List.of()));
            var content=Map.of("role","assistant","content",result);
            send(exchange,"application/json",200,json.writeValueAsString(Map.of("id","group-memory-usage",
                    "object","chat.completion","model",request.path("model").asText(),"choices",List.of(Map.of("index",0,
                            "message",content,"finish_reason","stop")),"usage",Map.of("prompt_tokens",13,"completion_tokens",5,"total_tokens",18))));
            return true;
        }
        return false;
    }

    /** 只留本测试合成工具调用和结果；不保存HTTP头、凭据或系统/用户消息，失败前先落盘。 */
    private void saveToolDiagnostics(JsonNode request, Script script, int ordinal) throws IOException {
        var messages = new ArrayList<JsonNode>();
        for (JsonNode message : request.path("messages")) {
            String role = message.path("role").asText();
            if ("tool".equals(role) || "assistant".equals(role) && message.has("tool_calls")) messages.add(message);
        }
        Files.writeString(evidence.resolve("model-tools-" + script.marker + "-" + ordinal + ".json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("script", script.marker,
                        "model_ordinal", ordinal, "tool_messages", messages)), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    /** 按冻结实参定位前一调用，再核对同一tool_call_id的结果，消息正文不能冒充回执。 */
    private void requireToolResult(JsonNode request, Script script, int step) throws IOException {
        String callId = null;
        JsonNode expected = json.valueToTree(script.arguments.get(step));
        for (JsonNode message : request.path("messages")) {
            if (!"assistant".equals(message.path("role").asText())) continue;
            for (JsonNode call : message.path("tool_calls")) {
                JsonNode arguments = json.readTree(call.path("function").path("arguments").asText());
                if (!expected.equals(arguments)) continue;
                if (callId != null) throw new IllegalStateException("duplicate_business_tool_call");
                callId = call.path("id").asText();
            }
        }
        if (callId == null || callId.isBlank()) throw new IllegalStateException("previous_business_tool_call_missing");
        int matches = 0;
        for (JsonNode message : request.path("messages")) {
            if (!"tool".equals(message.path("role").asText()) || !callId.equals(message.path("tool_call_id").asText())) continue;
            if (!message.path("content").asText().contains(script.expectedResults.get(step)))
                throw new IllegalStateException("business_tool_result_missing_step_" + (step + 1));
            script.toolResults.add(message.path("content").asText());
            matches++;
        }
        if (matches != 1) throw new IllegalStateException("previous_business_tool_result_missing_or_duplicate");
    }

    /** 固定业务参数集合精确匹配一个已挂载能力，避免调用其他资源。 */
    private String toolName(JsonNode request, Set<String> arguments, String logicalName) {
        var matches = new ArrayList<String>();
        for (JsonNode tool : request.path("tools")) {
            var function = tool.path("function");
            var names = new java.util.HashSet<String>();
            function.path("parameters").path("properties").fieldNames().forEachRemaining(names::add);
            if (names.equals(arguments) && (logicalName == null
                    || function.path("description").asText().contains("logical_name=\""+logicalName+"\"\n")))
                matches.add(function.path("name").asText());
        }
        if (matches.size() != 1) throw new IllegalStateException("mounted_business_tool_not_unique:" + arguments);
        return matches.getFirst();
    }

    private static void send(HttpExchange exchange, String type, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
        finally { exchange.close(); }
    }

    /** 先停止监听，再等待本实例所有 Handler 退出。 */
    @Override public void close() {
        extractionCalls.values().forEach(ExtractionScript::release);
        server.stop(0);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("business_provider_not_quiescent");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("business_provider_shutdown_interrupted", interrupted);
        }
    }

    static final class ExtractionScript {
        private final AtomicInteger calls=new AtomicInteger();
        private final CountDownLatch entered=new CountDownLatch(1),released=new CountDownLatch(1);
        boolean awaitEntered() throws InterruptedException { return entered.await(20,TimeUnit.SECONDS); }
        void release() { released.countDown(); }
        int calls() { return calls.get(); }
    }

    static final class Script {
        private final String marker;
        private final List<Map<String, Object>> arguments;
        private final List<String> expectedResults;
        private final String finalText;
        private final String logicalName;
        private final List<String> toolResults=new CopyOnWriteArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile int blockedOrdinal;
        private final CountDownLatch responseEntered=new CountDownLatch(1);
        private final CountDownLatch responseReleased=new CountDownLatch(1);

        private Script(String marker, List<Map<String, Object>> arguments, List<String> expectedResults, String finalText, String logicalName) {
            this.marker = marker; this.arguments = arguments; this.expectedResults = expectedResults; this.finalText = finalText;
            this.logicalName=logicalName;
        }
        String prompt() { return "[business-workflow:" + marker + "]"; }
        int calls() { return calls.get(); }
        List<String> toolResults() { return List.copyOf(toolResults); }
        /** 只控制测试拥有的模型响应时点，供真实 Runtime 派发门禁竞争使用。 */
        void blockResponse(int ordinal) { blockedOrdinal=ordinal; }
        boolean awaitBlocked(Duration timeout) throws InterruptedException { return responseEntered.await(timeout.toMillis(),TimeUnit.MILLISECONDS); }
        void releaseResponse() { responseReleased.countDown(); }
        private void awaitResponseRelease(int ordinal) throws InterruptedException {
            if(blockedOrdinal!=ordinal)return;
            responseEntered.countDown();
            if(!responseReleased.await(45,TimeUnit.SECONDS))throw new IllegalStateException("business_model_barrier_timeout");
        }
    }
}
