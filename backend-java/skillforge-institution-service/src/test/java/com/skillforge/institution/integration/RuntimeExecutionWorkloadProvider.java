package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 独立脚本的确定性 OpenAI HTTP 端点，供真实 Runtime 主链及并发负载复用。
 *
 * <p>每个独立 Session 使用一个脚本标记；工具由产品实际执行，本端点只验证下一次模型请求。
 * 所有成功与失败请求均保存完整响应边界计时，仅明确模拟执行段可从平台耗时中扣除。
 * 监听器及有界执行器归本实例所有，调用方必须在测试结束时关闭。
 */
final class RuntimeExecutionWorkloadProvider implements AutoCloseable {
    private static final Pattern SCRIPT_MARKER = Pattern.compile("\\[runtime-execution-workload:([a-zA-Z0-9_-]{1,128})\\]");
    private final ObjectMapper json;
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final HandoffProbe handoff;
    private final Map<String, Script> scripts = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RequestTiming> timings = new CopyOnWriteArrayList<>();

    /** 固定验收工作量；长历史场景复用 ORDINARY，历史由产品会话夹具准备。 */
    enum Workload {
        /** 两次模型及一次实际平台模板工具。 */
        ORDINARY(2, 1),
        /** 六次模型及五次实际平台模板工具。 */
        MULTI_STEP(6, 5),
        /** 一次真实MCP等待及续答完成后再进行第二次模型调用。 */
        MCP_WAIT(2, 1),
        /** 非幂等写入的MCP参数拒绝，后继模型必须消费安全反馈。 */
        MCP_WRITE_REJECTED(2, 1),
        /** 原生AskUser本地等待，准确人类回复后第二次模型完成，不执行外部工具。 */
        HUMAN_WAIT(2, 0),
        /** 两次实际模型与一次真实知识检索；检索不计作PLATFORM工具。 */
        KNOWLEDGE(2, 0);

        final int modelCalls;
        final int toolCalls;

        /** 保存固定模型与工具次数，不接受动态放宽工作量。 */
        Workload(int modelCalls, int toolCalls) {
            this.modelCalls = modelCalls;
            this.toolCalls = toolCalls;
        }
    }

    /** 在 loopback 原子分配端口；最多 64 个 HTTP Handler 并行，排队仍计入端到端平台开销。 */
    RuntimeExecutionWorkloadProvider(ObjectMapper json) throws IOException {
        this(json, new HandoffProbe() { });
    }

    /** 仅测试可注入精确交接屏障，模拟响应已到客户端但前一 Handler 仍在收尾的窗口。 */
    RuntimeExecutionWorkloadProvider(ObjectMapper json, HandoffProbe handoff) throws IOException {
        this.json = Objects.requireNonNull(json);
        this.handoff = Objects.requireNonNull(handoff);
        var threadIds = new AtomicInteger();
        executor = new ThreadPoolExecutor(64, 64, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1024), runnable -> {
            var thread = new Thread(runnable, "runtime-workload-provider-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/chat/completions", this::handle);
    }

    /** 启动已绑定监听器；后续通过 endpoint 获取实际地址。 */
    void start() { server.start(); }

    /** 返回生产 OpenAI 适配器使用的服务基址。 */
    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    /** 注册唯一脚本，默认不模拟外部推理，因此不扣除任何解析、编码、发送或排队耗时。 */
    Script register(String scriptKey, Workload workload) {
        return register(scriptKey, workload, Duration.ZERO);
    }

    /** 注册一个独立 Session 的固定脚本；相同标记不能重置或复用，避免掩盖重复模型调用。 */
    Script register(String scriptKey, Workload workload, Duration simulatedExecution) {
        if (scriptKey == null || !scriptKey.matches("[a-zA-Z0-9_-]{1,128}")) throw new IllegalArgumentException("invalid_script_key");
        Objects.requireNonNull(workload);
        Objects.requireNonNull(simulatedExecution);
        if (simulatedExecution.isNegative() || simulatedExecution.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("invalid_simulated_execution");
        var script = new Script(scriptKey, workload, simulatedExecution);
        if (scripts.putIfAbsent(scriptKey, script) != null) throw new IllegalArgumentException("duplicate_script_key");
        return script;
    }

    /** 包含无法路由及响应发送失败的请求，调用方不得静默丢弃失败样本。 */
    List<RequestTiming> requests() { return List.copyOf(timings); }

    /** 停止接收并中止本实例任务，等待执行器静止后才释放夹具。 */
    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("workload_provider_not_quiescent");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("workload_provider_shutdown_interrupted", interrupted);
        }
    }

    /** 每个请求独立记录从进入 Handler 至完整响应发送/关闭后的时间，失败仍保留原测量。 */
    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        Script script = null;
        int ordinal = 0;
        int verified = 0;
        long simulatedNanos = 0;
        long responseCompleted = 0;
        boolean owned = false;
        String failure = "";
        try {
            if (!"POST".equals(exchange.getRequestMethod())) throw new IOException("workload_requires_post");
            JsonNode request;
            try (var input = exchange.getRequestBody()) { request = json.readTree(input); }
            script = script(request);
            ordinal = script.requests.incrementAndGet();
            owned = script.execution.tryLock();
            if (!owned) {
                // 客户端可能先于前一 Handler 的 finally 收到完整响应；合法下一轮等待交接，不能误判为并发违约。
                handoff.queued(script.key, ordinal);
                owned = script.execution.tryLock(5, TimeUnit.SECONDS);
                if (!owned) throw new IOException("script_handoff_timeout");
            }
            if (ordinal > script.workload.modelCalls) throw new IOException("script_model_call_limit_exceeded");
            requireHistory(request, script);
            for (int step = 1; step < ordinal && step <= script.workload.toolCalls; step++) {
                requireToolResult(request, script, step);
                script.verifiedSteps.add(step);
                verified++;
            }
            if (script.workload == Workload.HUMAN_WAIT && ordinal == 2) requireHumanReply(request, script);
            if (script.workload == Workload.KNOWLEDGE && ordinal == 2) requireKnowledgeEvidence(request, script);
            if (!script.simulatedExecution.isZero()) {
                long simulationStarted = System.nanoTime();
                try { TimeUnit.NANOSECONDS.sleep(script.simulatedExecution.toNanos()); }
                finally { simulatedNanos = System.nanoTime() - simulationStarted; }
            }
            respond(exchange, request, script, ordinal);
            responseCompleted = System.nanoTime();
            handoff.responseSent(script.key, ordinal);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure = "workload_request_interrupted";
            if (exchange.getResponseCode() < 0) reject(exchange);
        } catch (IOException | RuntimeException rejected) {
            // 错误只保存固定原因，不回显用户正文、模型消息、HTTP 头或凭据。
            failure = rejected instanceof IOException && rejected.getMessage() != null
                    && rejected.getMessage().matches("[a-z_]+") ? rejected.getMessage() : "workload_fixture_rejected";
            if (exchange.getResponseCode() < 0) reject(exchange);
        } finally {
            exchange.close();
            var timing = new RequestTiming(script == null ? "" : script.key, ordinal, exchange.getResponseCode(),
                    started, responseCompleted == 0 ? System.nanoTime() : responseCompleted, simulatedNanos, verified, failure);
            timings.add(timing);
            if (script != null) synchronized (script) {
                script.timings.add(timing);
                if (owned) script.execution.unlock();
            }
        }
    }

    /** 仅从真实用户消息解析唯一脚本，历史中的同一标记可以重复出现。 */
    private Script script(JsonNode request) throws IOException {
        var keys = ConcurrentHashMap.<String>newKeySet();
        for (JsonNode message : request.path("messages")) {
            if (!"user".equals(message.path("role").asText())) continue;
            var matcher = SCRIPT_MARKER.matcher(message.path("content").toString());
            while (matcher.find()) keys.add(matcher.group(1));
        }
        if (keys.size() != 1) throw new IOException("script_marker_missing_or_ambiguous");
        Script script = scripts.get(keys.iterator().next());
        if (script == null) throw new IOException("script_not_registered");
        return script;
    }

    /**
     * 验证原生 assistant 调用与 tool 结果实际配对，不能只靠任意消息包含预期文本放行。
     * 产品可重建其可信 tool_call_id，因此按冻结参数识别调用，再匹配其实际 id。
     */
    private void requireToolResult(JsonNode request, Script script, int step) throws IOException {
        String callId = null;
        for (JsonNode message : request.path("messages")) {
            if (!"assistant".equals(message.path("role").asText())) continue;
            for (JsonNode call : message.path("tool_calls")) {
                JsonNode arguments;
                try { arguments = json.readTree(call.path("function").path("arguments").asText()); }
                catch (IOException invalid) { continue; }
                if (arguments == null) continue;
                boolean expected = script.workload == Workload.MCP_WAIT || script.workload == Workload.MCP_WRITE_REJECTED
                        ? script.key.equals(arguments.path("ticket").asText())
                        : script.template().equals(arguments.path("template").asText())
                            && arguments.path("data").path("quantity").asInt(-1) == step;
                if (!expected) continue;
                if (callId != null) throw new IOException("duplicate_script_tool_call");
                callId = call.path("id").asText();
            }
        }
        if (callId == null || callId.isBlank()) throw new IOException("previous_template_call_missing");
        int matches = 0;
        for (JsonNode message : request.path("messages")) {
            if (!"tool".equals(message.path("role").asText()) || !callId.equals(message.path("tool_call_id").asText())) continue;
            String content = message.path("content").asText();
            if (script.workload == Workload.MCP_WRITE_REJECTED) {
                if (!content.contains("原因码：mcp_tool_arguments_invalid_after_dispatch")
                        || !content.contains("结果引用：mcp-result:")
                        || !content.contains("失败不代表未执行或没有副作用")
                        || !content.contains("先依据本地结果引用核查已有副作用与幂等约束")
                        || !content.contains("确认安全后依据本次已授权工具的真实 Schema 考虑修正参数")
                        || !content.contains("不要盲目重发") || content.contains("private-fixture-error"))
                    throw new IOException("write_failure_feedback_invalid");
                script.writeFailureFeedback = content;
            } else if (!containsRenderedResult(content, script.expectedToolResult(step)))
                throw new IOException("previous_rendered_tool_result_missing");
            matches++;
        }
        if (matches != 1) throw new IOException("previous_tool_result_pair_missing_or_duplicate");
    }

    /** 长历史验收核验100条原消息按原顺序实际到达Provider，不以数据库预置条数代替实际入模。 */
    private void requireHistory(JsonNode request, Script script) throws IOException {
        var expected = script.history;
        if (expected.isEmpty()) return;
        int next = 0; int bytes = 0;
        for (JsonNode message : request.path("messages")) {
            if (!Set.of("user", "assistant").contains(message.path("role").asText())) continue;
            String content = message.path("content").isTextual() ? message.path("content").asText()
                    : textContent(message.path("content"));
            if (!content.startsWith("capacity-history-")) continue;
            if (next >= expected.size() || !expected.get(next).equals(content)) throw new IOException("long_history_missing_reordered_or_changed");
            next++; bytes += content.getBytes(StandardCharsets.UTF_8).length;
        }
        if (next != 100 || bytes != 65536) throw new IOException("long_history_count_or_bytes_mismatch");
        script.verifiedHistoryMessages = next; script.verifiedHistoryUtf8Bytes = bytes;
    }

    /** OpenAI字符串或原生text内容块都必须还原成同一原文；忽略图片块而不伪造历史文本。 */
    private static String textContent(JsonNode content) {
        var text = new StringBuilder();
        if (content.isArray()) for (var part : content) if ("text".equals(part.path("type").asText())) text.append(part.path("text").asText());
        return text.toString();
    }

    /** AskUser恢复保留原函数和真实文本回执，再追加精确USER回复；不能用正文提及问题替代调用配对。 */
    private void requireHumanReply(JsonNode request, Script script) throws IOException {
        var messages = RuntimeExecutionTimeFixture.businessMessages(request, 2); int size = messages.size();
        if (size < 4 || !RuntimeExecutionTimeFixture.isBudget(messages.get(size - 1))) throw new IOException("human_budget_context_missing_or_invalid");
        var question = messages.get(size - 4); var receipt = messages.get(size - 3); var response = messages.get(size - 2);
        String responseText = response.path("content").isTextual() ? response.path("content").asText() : textContent(response.path("content"));
        if (!"assistant".equals(question.path("role").asText())) {
            var tail = new java.util.ArrayList<HumanMessageShape>();
            for (int index = Math.max(0, size - 5); index < size; index++) {
                var message = messages.get(index);
                String role = switch (message.path("role").asText()) {
                    case "system", "user", "assistant", "tool" -> message.path("role").asText();
                    default -> "other";
                };
                tail.add(new HumanMessageShape(role, message.path("id").getNodeType().name(),
                        message.path("tool_call_id").getNodeType().name(), message.path("tool_calls").size()));
            }
            script.humanMessageTail = List.copyOf(tail);
            throw new IOException("human_question_role_mismatch");
        }
        if (!"tool".equals(receipt.path("role").asText())) throw new IOException("human_receipt_role_mismatch");
        if (!"user".equals(response.path("role").asText())) throw new IOException("human_response_role_mismatch");
        if (question.path("tool_calls").size() != 1) throw new IOException("human_question_call_count_mismatch");
        if (question.has("tool_call_id")) throw new IOException("human_question_unexpected_call_id");
        if (receipt.path("tool_calls").size() != 0) throw new IOException("human_receipt_unexpected_calls");
        if (response.path("tool_calls").size() != 0) throw new IOException("human_response_unexpected_calls");
        if (!script.humanReply().equals(responseText)) throw new IOException("human_response_text_mismatch");
        if (response.has("tool_call_id")) throw new IOException("human_response_unexpected_call_id");
        var call = question.path("tool_calls").get(0); String originalId = "workload-" + script.key + "-1";
        var function = call.path("function");
        if (!originalId.equals(call.path("id").asText())) throw new IOException("human_question_call_id_mismatch");
        if (!"function".equals(call.path("type").asText())) throw new IOException("human_question_call_type_mismatch");
        if (!"ask_user".equals(function.path("name").asText())) throw new IOException("human_question_function_mismatch");
        if (!function.path("arguments").isTextual()) throw new IOException("human_question_arguments_not_text");
        if (!originalId.equals(receipt.path("tool_call_id").asText())) throw new IOException("human_receipt_call_id_mismatch");
        if (!receipt.path("content").isTextual()) throw new IOException("human_receipt_content_not_text");
        final JsonNode arguments;
        try { arguments = json.readTree(function.path("arguments").asText()); }
        catch (IOException invalid) { throw new IOException("human_question_arguments_invalid_json"); }
        if (arguments == null || !arguments.isObject()) throw new IOException("human_question_arguments_not_object");
        if (arguments.size() != 1) throw new IOException("human_question_arguments_size_mismatch");
        if (!script.question().equals(arguments.path("question").asText())) throw new IOException("human_question_text_mismatch");
        if (!RuntimeExecutionTimeFixture.HUMAN_RECEIPT.equals(receipt.path("content").textValue())) throw new IOException("human_receipt_content_mismatch");
        script.verifiedHumanReplies.incrementAndGet();
    }

    /** 仅接受原knowledge_query真实callId对应的TOOL证据；用户文本或其他工具结果不能替代。 */
    private void requireKnowledgeEvidence(JsonNode request, Script script) throws IOException {
        var expected = script.knowledge;
        if (expected == null) throw new IOException("knowledge_fixture_not_registered");
        String callId = null;
        for (var message : request.path("messages")) {
            if (!"assistant".equals(message.path("role").asText())) continue;
            for (var call : message.path("tool_calls")) {
                if (!"knowledge_query".equals(call.path("function").path("name").asText())) continue;
                var arguments = json.readTree(call.path("function").path("arguments").asText());
                if (!expected.handle().equals(arguments.path("knowledge_handle").asText()) || !expected.query().equals(arguments.path("query").asText()))
                    throw new IOException("knowledge_query_identity_mismatch");
                if (callId != null) throw new IOException("knowledge_query_duplicate_call");
                callId = call.path("id").asText();
            }
        }
        if (callId == null || callId.isBlank()) throw new IOException("knowledge_query_call_missing");
        int paired = 0; var ids = new java.util.LinkedHashSet<String>(); var citations = new java.util.LinkedHashSet<String>();
        for (var message : request.path("messages")) {
            if (!"tool".equals(message.path("role").asText()) || !callId.equals(message.path("tool_call_id").asText())) continue;
            paired++;
            String content = message.path("content").isTextual() ? message.path("content").asText() : textContent(message.path("content"));
            var bundle = json.readTree(content);
            if (!bundle.path("bundleId").isTextual() || bundle.path("bundleId").asText().isBlank()
                    || !bundle.path("version").isTextual() || !bundle.path("version").asText().matches("[1-9][0-9]*")
                    || !bundle.path("items").isArray() || bundle.path("items").isEmpty()) throw new IOException("knowledge_injected_bundle_missing");
            for (var item : bundle.path("items")) {
                String id = item.path("itemId").asText();
                String citation = item.path("citationId").asText();
                if (!citation.matches("k[0-9a-f]{16}") || !citations.add(citation)) throw new IOException("knowledge_citation_id_invalid");
                if (id.isBlank() || !ids.add(id) || !expected.sourceId().equals(item.path("sourceId").asText())
                        || !expected.sourceVersion().equals(item.path("sourceVersion").asText())
                        || !item.path("text").asText().contains(expected.expectedText())) throw new IOException("knowledge_injected_item_mismatch");
            }
        }
        if (paired != 1 || ids.isEmpty()) throw new IOException("knowledge_tool_pair_missing_or_duplicate");
        script.verifiedKnowledgeItemIds = List.copyOf(ids);
        script.verifiedCitationIds = List.copyOf(citations);
    }

    /** 允许产品正文说明，但必须存在实际结构化 success/rendered 同一对象，不能由输入文本冒充。 */
    private boolean containsRenderedResult(String content, String expected) {
        for (int index = content.indexOf('{'); index >= 0; index = content.indexOf('{', index + 1)) {
            try { if (rendered(json.readTree(content.substring(index)), expected)) return true; }
            catch (IOException invalid) { /* 正文中的非 JSON 花括号不构成工具结果。 */ }
        }
        return false;
    }

    /** 只沿结果 JSON 寻找同一业务对象，不保存或改写模型正文。 */
    private boolean rendered(JsonNode node, String expected) {
        if (node == null) return false;
        if (node.isObject() && node.path("success").isBoolean() && node.path("success").asBoolean()
                && expected.equals(node.path("rendered").asText())) return true;
        for (JsonNode child : node) if (rendered(child, expected)) return true;
        return false;
    }

    /** 从真实下发 schema 精确选择模板工具，响应继续经过生产 OpenAI JSON/SSE 解析器。 */
    private void respond(HttpExchange exchange, JsonNode request, Script script, int ordinal) throws IOException {
        String function = null;
        Map<String, Object> arguments = Map.of();
        if (script.workload == Workload.KNOWLEDGE && ordinal == 1) {
            if (script.knowledge == null) throw new IOException("knowledge_fixture_not_registered");
            var exposed = java.util.stream.StreamSupport.stream(request.path("tools").spliterator(), false)
                    .filter(tool -> "knowledge_query".equals(tool.path("function").path("name").asText())).toList();
            if (exposed.size() != 1 || java.util.stream.StreamSupport.stream(exposed.getFirst().path("function").path("parameters")
                    .path("properties").path("knowledge_handle").path("enum").spliterator(), false)
                    .noneMatch(handle -> script.knowledge.handle().equals(handle.asText()))) throw new IOException("knowledge_handle_not_exposed");
            function = "knowledge_query"; arguments = Map.of("knowledge_handle", script.knowledge.handle(), "query", script.knowledge.query());
        } else if (script.workload == Workload.HUMAN_WAIT && ordinal == 1) {
            if (java.util.stream.StreamSupport.stream(request.path("tools").spliterator(), false)
                    .noneMatch(tool -> "ask_user".equals(tool.path("function").path("name").asText())))
                throw new IOException("ask_user_native_tool_missing");
            function = "ask_user"; arguments = Map.of("question", script.question());
        } else if (ordinal <= script.workload.toolCalls) {
            function = null;
            for (JsonNode tool : request.path("tools")) {
                JsonNode candidate = tool.path("function");
                JsonNode properties = candidate.path("parameters").path("properties");
                if (script.workload == Workload.MCP_WAIT || script.workload == Workload.MCP_WRITE_REJECTED ? !properties.has("ticket")
                        : !properties.has("template") || !properties.has("data")) continue;
                if (function != null) throw new IOException("ambiguous_template_tools");
                function = candidate.path("name").asText();
            }
            if (function == null || function.isBlank()) throw new IOException("mounted_template_tool_missing");
            arguments = script.workload == Workload.MCP_WAIT || script.workload == Workload.MCP_WRITE_REJECTED ? Map.of("ticket", script.key)
                    : Map.of("template", script.template(), "data", Map.of("quantity", ordinal));
        }
        var call = new LinkedHashMap<String, Object>();
        call.put("id", "workload-" + script.key + "-" + ordinal);
        call.put("type", "function");
        if (function != null) call.put("function", Map.of("name", function, "arguments", json.writeValueAsString(arguments)));
        String model = request.path("model").asText("runtime-fixture");
        var message = function == null ? Map.of("role", "assistant", "content", script.rawFinalAnswer(), "reasoning_content", "deterministic-workload")
                : Map.of("role", "assistant", "reasoning_content", "deterministic-workload", "tool_calls", List.of(call));
        String finish = function == null ? "stop" : "tool_calls";
        var usage = Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18);
        if (!request.path("stream").asBoolean()) {
            send(exchange, "application/json", 200, json.writeValueAsString(Map.of("id", "workload-" + script.key,
                    "object", "chat.completion", "model", model, "choices", List.of(Map.of("index", 0,
                            "message", message, "finish_reason", finish)), "usage", usage)));
            return;
        }
        if (function != null) call.put("index", 0);
        String first = json.writeValueAsString(Map.of("id", "workload-" + script.key, "object", "chat.completion.chunk",
                "model", model, "choices", List.of(Map.of("index", 0, "delta", message))));
        String last = json.writeValueAsString(Map.of("id", "workload-" + script.key, "object", "chat.completion.chunk",
                "model", model, "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)), "usage", usage));
        send(exchange, "text/event-stream", 200, "data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n");
    }

    /** 夹具错误返回固定公开错误，具体原因保留于对应失败计时。 */
    private void reject(HttpExchange exchange) throws IOException {
        send(exchange, "application/json", 500, "{\"error\":{\"message\":\"runtime workload fixture rejected request\"}}");
    }

    /** 返回后响应正文已全部写出并关闭；完整协议处理耗时仍单列保留。 */
    private void send(HttpExchange exchange, String contentType, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    /** 测试源集内的时序探针；默认空操作，不进入产品或正式负载的同步流程。 */
    interface HandoffProbe {
        /** 完整响应已关闭但脚本执行权尚未释放，用于确定性阻停原竞争窗口。 */
        default void responseSent(String scriptKey, int ordinal) throws InterruptedException { }

        /** 后继请求确已发现前一 Handler 持有执行权，即将进入有界等待。 */
        default void queued(String scriptKey, int ordinal) { }
    }

    /** 一个独立 Session 的脚本句柄；不能在测量间重置，快照包含其全部失败。 */
    final class Script {
        private final String key;
        private final Workload workload;
        private final Duration simulatedExecution;
        private final AtomicInteger requests = new AtomicInteger();
        /** 同脚本的响应与收尾串行交接；不同脚本各自执行，不共享锁。 */
        private final ReentrantLock execution = new ReentrantLock(true);
        private final Set<Integer> verifiedSteps = ConcurrentHashMap.newKeySet();
        private final CopyOnWriteArrayList<RequestTiming> timings = new CopyOnWriteArrayList<>();
        private volatile List<String> history = List.of();
        private volatile int verifiedHistoryMessages;
        private volatile int verifiedHistoryUtf8Bytes;
        private final AtomicInteger verifiedHumanReplies = new AtomicInteger();
        private volatile List<HumanMessageShape> humanMessageTail = List.of();
        private volatile KnowledgeExpected knowledge;
        private volatile List<String> verifiedKnowledgeItemIds = List.of();
        private volatile List<String> verifiedCitationIds = List.of();
        private volatile String writeFailureFeedback = "";

        /** 仅由注册入口创建，状态只属于此脚本。 */
        private Script(String key, Workload workload, Duration simulatedExecution) {
            this.key = key;
            this.workload = workload;
            this.simulatedExecution = simulatedExecution;
        }

        /** 传给真实产品输入的唯一脚本标记，不承载业务身份或授权。 */
        String prompt() { return "[runtime-execution-workload:" + key + "] " + (workload == Workload.MCP_WAIT
                ? "请调用等待工具，按需要补充 quantity 后确认实际结果。" : workload == Workload.MCP_WRITE_REJECTED
                    ? "请调用已挂载的写工具并根据实际结果作答。" : workload == Workload.HUMAN_WAIT
                    ? "请先向用户询问当前必要选择，收到实际回答后再完成。" : workload == Workload.KNOWLEDGE
                        ? "请检索已挂载知识中的随机值 " + Objects.requireNonNull(knowledge).query() + " 并引用实际证据。"
                        : "请完成模板工具任务并确认结果。"); }

        String writeFailureFeedback() { return writeFailureFeedback; }

        /** 原生AskUser的实际问题，不提前写进用户输入。 */
        String question() { return "runtime-question-" + key; }

        /** 通过真实hitl_response提交的用户原文，不由Provider填入模型历史。 */
        String humanReply() { return "runtime-answer-" + key; }

        /** 第 step 次真实模板渲染的预期正文。 */
        String expectedToolResult(int step) { return "runtime-workload-" + key + "-" + step; }

        /** 最后一次模型响应的稳定断言文本。 */
        String rawFinalAnswer() { return "runtime-workload-completed-" + key
                + verifiedCitationIds.stream().map(id -> "[[cite:" + id + "]]").collect(java.util.stream.Collectors.joining()); }
        String finalAnswer() {
            var answer = new StringBuilder("runtime-workload-completed-" + key);
            for (int index = 0; index < verifiedCitationIds.size(); index++) answer.append("[知识依据 ").append(index + 1)
                    .append("](#citation-").append(verifiedCitationIds.get(index)).append(")");
            return answer.toString();
        }

        /** 实际工具参数的模板，quantity 只能由真实模板 Handler 渲染。 */
        private String template() { return "runtime-workload-" + key + "-{{quantity}}"; }

        /** 正式长历史工作量固定100条及64KiB，必须在原HTTP请求开始前登记且不能重置。 */
        synchronized void requireHistory(List<String> messages) {
            if (!history.isEmpty() || requests.get() != 0 || messages.size() != 100
                    || messages.stream().mapToInt(text -> text.getBytes(StandardCharsets.UTF_8).length).sum() != 65536
                    || messages.stream().distinct().count() != 100) throw new IllegalArgumentException("invalid_long_history_fixture");
            history = List.copyOf(messages);
        }

        /** 期望值来自正式挂载与原文档回执，不能在模型请求开始后重置以掩盖错来源。 */
        synchronized void requireKnowledge(String handle, String sourceId, String sourceVersion, String query) {
            requireKnowledge(handle, sourceId, sourceVersion, query, query);
        }

        /** 查询文字与原文断言独立冻结，允许验证大小写差异而不改写来源正文。 */
        synchronized void requireKnowledge(String handle, String sourceId, String sourceVersion, String query, String expectedText) {
            if (workload != Workload.KNOWLEDGE || knowledge != null || requests.get() != 0
                    || List.of(handle, sourceId, sourceVersion, query, expectedText).stream().anyMatch(String::isBlank))
                throw new IllegalArgumentException("invalid_knowledge_fixture");
            knowledge = new KnowledgeExpected(handle, sourceId, sourceVersion, query, expectedText);
        }

        /** 快照可在请求运行中读取；结束后应同时确认请求数与完成计时数一致。 */
        synchronized ScriptSnapshot snapshot() {
            return new ScriptSnapshot(key, workload, requests.get(), verifiedSteps.size(), verifiedHistoryMessages,
                    verifiedHistoryUtf8Bytes, verifiedHumanReplies.get(), humanMessageTail, verifiedKnowledgeItemIds, List.copyOf(timings));
        }
    }

    /**
     * 脚本观测，无正文或凭据；请求数包含已进入且尚未完成响应的请求。
     * @param scriptKey 唯一测试脚本标记
     * @param workload 冻结的固定工作量
     * @param requestCount 全部已进入模型 HTTP 的次数
     * @param verifiedToolResults 已在后继请求验证的不同工具结果数
     * @param verifiedHistoryMessages 实际进入Provider的原长历史消息数，普通场景为零
     * @param verifiedHistoryUtf8Bytes 实际核验的原历史UTF8字节数，普通场景为零
     * @param verifiedHumanReplies 在原生assistant/user角色边界核验的准确人类回复次数
     * @param verifiedKnowledgeItemIds 实际注入且匹配原callId/来源版本的知识证据ID
     * @param requests 已完成或已失败的全部响应计时
     */
    record HumanMessageShape(String role, String messageIdType, String toolCallIdType, int toolCallCount) { }

    record ScriptSnapshot(String scriptKey, Workload workload, int requestCount, int verifiedToolResults,
                          int verifiedHistoryMessages, int verifiedHistoryUtf8Bytes,
                          int verifiedHumanReplies, List<HumanMessageShape> humanMessageTail,
                          List<String> verifiedKnowledgeItemIds,
                          List<RequestTiming> requests) { }

    private record KnowledgeExpected(String handle, String sourceId, String sourceVersion, String query, String expectedText) { }

    /**
     * 每个 HTTP 请求的原始测量；失败不从样本移除，handler 时间不可全部作为外部耗时扣除。
     * @param scriptKey 已解析的脚本标记；无法路由时为空
     * @param ordinal 本脚本第几次模型请求；无法路由时为零
     * @param httpStatus 实际响应状态；尚未发响应头即断开时为负数
     * @param handlerStartedNanos System.nanoTime 的 Handler 开始值，不含其前排队
     * @param responseCompletedNanos 完整响应写出/关闭或失败后的 System.nanoTime 值
     * @param simulatedExecutionNanos 明确模拟服务端执行段的实测时间；零模拟严格记零
     * @param verifiedToolResults 本次请求已验证的先前工具结果数
     * @param failureReason 空字符串表示完成，非空表示失败；不能作为成功延迟样本
     */
    record RequestTiming(String scriptKey, int ordinal, int httpStatus, long handlerStartedNanos,
                         long responseCompletedNanos, long simulatedExecutionNanos, int verifiedToolResults,
                         String failureReason) { }
}
