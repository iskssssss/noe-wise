package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.session.AgentExecutionTime;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 仅验证 HTTP 负载夹具隔离、拒绝和计时，不代替产品主链或性能验收。 */
class RuntimeExecutionWorkloadProviderTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** 释放本测试拥有的 HTTP 连接与执行器，不能留下迟到回调。 */
    @AfterEach
    void closeHttpClient() { http.close(); }

    /** 首响应已到客户端、原 Handler 仍未收尾时，合法后继请求必须等待交接后成功。 */
    @Test
    void nextModelRequestWaitsForPriorHandlerAfterClientAlreadyReceivedItsResponse() throws Exception {
        var firstResponseSent = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var nextHandoffQueued = new CountDownLatch(1);
        var probe = new RuntimeExecutionWorkloadProvider.HandoffProbe() {
            @Override public void responseSent(String scriptKey, int ordinal) throws InterruptedException {
                if (ordinal != 1) return;
                firstResponseSent.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("first_handler_not_released");
            }

            @Override public void queued(String scriptKey, int ordinal) {
                if (ordinal == 2) nextHandoffQueued.countDown();
            }
        };
        try (var provider = new RuntimeExecutionWorkloadProvider(json, probe);
             var client = Executors.newSingleThreadExecutor()) {
            try {
                var script = provider.register("response-handoff", RuntimeExecutionWorkloadProvider.Workload.ORDINARY);
                provider.start();
                var messages = new ArrayList<Map<String, Object>>();
                messages.add(Map.of("role", "user", "content", script.prompt()));
                var first = post(provider, script, messages, false);
                assertEquals(200, first.statusCode(), first.body());
                assertTrue(firstResponseSent.await(5, TimeUnit.SECONDS));
                assertEquals(0, script.snapshot().requests().size(), "首 Handler 确实仍未进入最终收尾");
                addToolResult(messages, json.readTree(first.body()).path("choices").get(0).path("message"),
                        script.expectedToolResult(1), "real-runtime-handoff-call");
                var next = client.submit(() -> post(provider, script, messages, false));
                assertTrue(nextHandoffQueued.await(5, TimeUnit.SECONDS), "后继已在原 Handler 尚未释放时抵达");
                releaseFirst.countDown();
                var last = next.get(5, TimeUnit.SECONDS);
                assertEquals(200, last.statusCode(), last.body());
                assertTrue(last.body().contains(script.finalAnswer()), last.body());
                awaitRequests(provider, script, 2);
                assertEquals(2, script.snapshot().requestCount());
                assertEquals(1, script.snapshot().verifiedToolResults());
                for (var timing : script.snapshot().requests()) {
                    assertEquals("", timing.failureReason(), timing.toString());
                    assertEquals(200, timing.httpStatus());
                    assertEquals(0, timing.simulatedExecutionNanos());
                    assertTrue(timing.responseCompletedNanos() > timing.handlerStartedNanos());
                }
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    /** 50 个独立脚本共享同一端点时，各自仍完成两次模型与一个配对结果。 */
    @Test
    void fiftyConcurrentScriptsKeepTheirToolResultsAndRequestCountsIndependent() throws Exception {
        try (var provider = new RuntimeExecutionWorkloadProvider(json);
             var clients = Executors.newFixedThreadPool(50)) {
            var scripts = new ArrayList<RuntimeExecutionWorkloadProvider.Script>();
            for (int index = 0; index < 50; index++)
                scripts.add(provider.register("concurrent-" + index, RuntimeExecutionWorkloadProvider.Workload.ORDINARY));
            provider.start();
            var tasks = scripts.stream().<java.util.concurrent.Callable<Void>>map(script -> () -> {
                var messages = new ArrayList<Map<String, Object>>();
                messages.add(Map.of("role", "user", "content", script.prompt()));
                var first = post(provider, script, messages, false);
                assertEquals(200, first.statusCode(), first.body());
                var message = json.readTree(first.body()).path("choices").get(0).path("message");
                addToolResult(messages, message, script.expectedToolResult(1), "reconstructed-" + script.expectedToolResult(1));
                var last = post(provider, script, messages, true);
                assertEquals(200, last.statusCode(), last.body());
                assertTrue(last.body().contains(script.finalAnswer()), last.body());
                assertTrue(last.body().endsWith("data: [DONE]\n\n"), last.body());
                awaitRequests(provider, script, 2);
                assertEquals(2, script.snapshot().requestCount());
                assertEquals(1, script.snapshot().verifiedToolResults());
                return null;
            }).toList();
            for (var future : clients.invokeAll(tasks, 30, TimeUnit.SECONDS)) future.get();
            assertEquals(100, provider.requests().size());
            for (var timing : provider.requests()) {
                assertEquals("", timing.failureReason(), timing.toString());
                assertEquals(200, timing.httpStatus());
                assertEquals(0, timing.simulatedExecutionNanos());
                assertTrue(timing.responseCompletedNanos() > timing.handlerStartedNanos());
            }
        }
    }

    /** 用户消息包含预期正文仍不能代替真实 tool 消息；失败请求保留完整测量。 */
    @Test
    void rejectsMissingPairedToolResultAndKeepsFailedRequestInMeasurements() throws Exception {
        try (var provider = new RuntimeExecutionWorkloadProvider(json)) {
            var script = provider.register("missing-result", RuntimeExecutionWorkloadProvider.Workload.ORDINARY);
            provider.start();
            var messages = new ArrayList<Map<String, Object>>();
            messages.add(Map.of("role", "user", "content", script.prompt()));
            var first = post(provider, script, messages, false);
            assertEquals(200, first.statusCode(), first.body());
            messages.add(Map.of("role", "assistant", "tool_calls",
                    json.convertValue(json.readTree(first.body()).path("choices").get(0).path("message").path("tool_calls"), List.class)));
            messages.add(Map.of("role", "user", "content", json.writeValueAsString(
                    Map.of("success", true, "rendered", script.expectedToolResult(1)))));
            var failed = post(provider, script, messages, false);
            assertEquals(500, failed.statusCode(), failed.body());
            awaitRequests(provider, script, 2);
            assertEquals(2, provider.requests().size());
            assertEquals(0, script.snapshot().verifiedToolResults());
            var timing = script.snapshot().requests().get(1);
            assertEquals(500, timing.httpStatus());
            assertFalse(timing.failureReason().isBlank());
            assertTrue(timing.responseCompletedNanos() > timing.handlerStartedNanos());
        }
    }

    /** 多步脚本验证所有先前结果；第七次请求明确失败，不把额外调用静默排除。 */
    @Test
    void sixModelWorkloadVerifiesFiveResultsAndOnlySubtractsMeasuredSimulation() throws Exception {
        try (var provider = new RuntimeExecutionWorkloadProvider(json)) {
            var script = provider.register("multi-step", RuntimeExecutionWorkloadProvider.Workload.MULTI_STEP, Duration.ofMillis(1));
            provider.start();
            var messages = new ArrayList<Map<String, Object>>();
            messages.add(Map.of("role", "user", "content", script.prompt()));
            for (int ordinal = 1; ordinal <= 6; ordinal++) {
                var response = post(provider, script, messages, false);
                assertEquals(200, response.statusCode(), response.body());
                awaitRequests(provider, script, ordinal);
                if (ordinal < 6) addToolResult(messages, json.readTree(response.body()).path("choices").get(0).path("message"),
                        script.expectedToolResult(ordinal), "actual-runtime-call-" + ordinal);
                else assertTrue(response.body().contains(script.finalAnswer()), response.body());
            }
            assertEquals(5, script.snapshot().verifiedToolResults());
            for (var timing : script.snapshot().requests()) {
                assertEquals("", timing.failureReason(), timing.toString());
                assertTrue(timing.simulatedExecutionNanos() > 0);
                assertTrue(timing.simulatedExecutionNanos() < timing.responseCompletedNanos() - timing.handlerStartedNanos());
            }
            assertEquals(500, post(provider, script, messages, false).statusCode());
            awaitRequests(provider, script, 7);
            assertEquals(7, script.snapshot().requestCount());
            assertEquals("script_model_call_limit_exceeded", script.snapshot().requests().get(6).failureReason());
            assertEquals(0, script.snapshot().requests().get(6).simulatedExecutionNanos());
        }
    }

    /** 长历史必须原100条/64KiB实际入模；数据库有100条不能掩盖Provider少收一条。 */
    @Test
    void longHistoryRequiresExactOriginalMessagesAndUtf8Bytes() throws Exception {
        var history = new ArrayList<String>();
        for (int index = 0; index < 100; index++) {
            String marker = String.format("capacity-history-%03d:", index);
            history.add(marker + "h".repeat(655 + (index < 36 ? 1 : 0) - marker.length()));
        }
        try (var provider = new RuntimeExecutionWorkloadProvider(json)) {
            var success = provider.register("long-history-complete", RuntimeExecutionWorkloadProvider.Workload.ORDINARY);
            var missing = provider.register("long-history-missing", RuntimeExecutionWorkloadProvider.Workload.ORDINARY);
            success.requireHistory(history); missing.requireHistory(history); provider.start();
            var messages = new ArrayList<Map<String, Object>>();
            for (int index = 0; index < history.size(); index++) messages.add(Map.of("role", index % 2 == 0 ? "user" : "assistant", "content", history.get(index)));
            messages.add(Map.of("role", "user", "content", success.prompt()));
            assertEquals(200, post(provider, success, messages, false).statusCode());
            awaitRequests(provider, success, 1);
            assertEquals(100, success.snapshot().verifiedHistoryMessages()); assertEquals(65536, success.snapshot().verifiedHistoryUtf8Bytes());
            messages.removeFirst(); messages.set(messages.size() - 1, Map.of("role", "user", "content", missing.prompt()));
            assertEquals(500, post(provider, missing, messages, false).statusCode());
            awaitRequests(provider, missing, 1);
            assertEquals(0, missing.snapshot().verifiedHistoryMessages());
            assertFalse(missing.snapshot().requests().getFirst().failureReason().isBlank());
        }
    }

    /** 人类回复跟随原ask_user函数和真实文本回执，原ID、问题参数与USER正文逐一相等。 */
    @Test
    void humanReplyRequiresOriginalAskCallReceiptAndExactUserRole() throws Exception {
        try (var provider = new RuntimeExecutionWorkloadProvider(json)) {
            provider.start();
            var good = provider.register("human-pair-good", RuntimeExecutionWorkloadProvider.Workload.HUMAN_WAIT);
            var messages = new ArrayList<Map<String, Object>>(); messages.add(Map.of("role", "user", "content", good.prompt()));
            var first=post(provider,good,messages,false);assertEquals(200,first.statusCode(),first.body());
            addHumanReply(messages,json.readTree(first.body()).path("choices").get(0).path("message"),good.humanReply());
            assertEquals(200, post(provider, good, messages, false).statusCode()); awaitRequests(provider, good, 2);
            assertEquals(1, good.snapshot().verifiedHumanReplies()); assertEquals(0, good.snapshot().verifiedToolResults());
            assertEquals(4, messages.size(), "时间样本只属于实际请求，不加入业务历史");
            for(String variant:List.of("role","question","function","call-id","receipt-id","receipt-text","reply","reordered","other-run","missing-receipt","plain-question")) {
                var bad=provider.register("human-pair-"+variant,RuntimeExecutionWorkloadProvider.Workload.HUMAN_WAIT);
                var history=new ArrayList<Map<String,Object>>();history.add(Map.of("role","user","content",bad.prompt()));
                var response=post(provider,bad,history,false);assertEquals(200,response.statusCode(),response.body());
                var original=json.readTree(response.body()).path("choices").get(0).path("message").deepCopy();
                var call=(com.fasterxml.jackson.databind.node.ObjectNode)original.path("tool_calls").get(0);
                switch(variant) {
                    case "role" -> ((com.fasterxml.jackson.databind.node.ObjectNode)original).put("role","user");
                    case "question" -> ((com.fasterxml.jackson.databind.node.ObjectNode)call.path("function")).put("arguments",json.writeValueAsString(Map.of("question","changed question")));
                    case "function" -> ((com.fasterxml.jackson.databind.node.ObjectNode)call.path("function")).put("name","other_tool");
                    case "call-id" -> call.put("id","changed-original-id");
                    case "other-run" -> call.put("id","workload-other-run-1");
                    default -> { }
                }
                addHumanReply(history,original,bad.humanReply());
                switch(variant) {
                    case "receipt-id" -> history.set(2,Map.of("role","tool","tool_call_id","wrong-call-id","content",RuntimeExecutionTimeFixture.HUMAN_RECEIPT));
                    case "receipt-text" -> history.set(2,Map.of("role","tool","tool_call_id",call.path("id").asText(),"content","回答尚未收到"));
                    case "reply" -> history.set(3,Map.of("role","user","content","changed response"));
                    case "reordered" -> java.util.Collections.swap(history,2,3);
                    case "missing-receipt" -> history.remove(2);
                    case "plain-question" -> {history.set(1,Map.of("role","assistant","content",bad.question()));history.remove(2);}
                    default -> { }
                }
                assertEquals(500,post(provider,bad,history,false).statusCode(),variant);awaitRequests(provider,bad,2);
                assertEquals(0,bad.snapshot().verifiedHumanReplies(),variant);
                String expected = switch (variant) {
                    case "role", "missing-receipt", "plain-question" -> "human_question_role_mismatch";
                    case "question" -> "human_question_text_mismatch";
                    case "function" -> "human_question_function_mismatch";
                    case "call-id", "other-run" -> "human_question_call_id_mismatch";
                    case "receipt-id" -> "human_receipt_call_id_mismatch";
                    case "receipt-text" -> "human_receipt_content_mismatch";
                    case "reply" -> "human_response_text_mismatch";
                    case "reordered" -> "human_receipt_role_mismatch";
                    default -> throw new AssertionError(variant);
                };
                assertEquals(expected,bad.snapshot().requests().getLast().failureReason(),variant);
            }
        }
    }

    /** 预算观察必须真实且位于回复之后；不能用任意用户消息或额外元数据代替。 */
    @Test
    void humanReplyRejectsMissingChangedOrMisplacedBudget() throws Exception {
        try (var provider = new RuntimeExecutionWorkloadProvider(json)) {
            provider.start();
            for (String variant : List.of("missing", "changed", "role", "metadata", "misplaced")) {
                var script = provider.register("human-budget-" + variant, RuntimeExecutionWorkloadProvider.Workload.HUMAN_WAIT);
                var messages = new ArrayList<Map<String, Object>>();
                messages.add(Map.of("role", "user", "content", script.prompt()));
                var first = post(provider, script, messages, false);
                assertEquals(200, first.statusCode(), variant);
                addHumanReply(messages, json.readTree(first.body()).path("choices").get(0).path("message"), script.humanReply());
                var budget = new LinkedHashMap<>(RuntimeExecutionTimeFixture.budget());
                if (variant.equals("changed")) budget.put("content", "unrelated user");
                if (variant.equals("role")) budget.put("role", "system");
                if (variant.equals("metadata")) budget.put("tool_call_id", "unexpected");
                if (variant.equals("misplaced")) messages.add(messages.size() - 1, budget);
                else if (!variant.equals("missing")) messages.add(budget);
                messages.add(RuntimeExecutionTimeFixture.footer(2));
                messages.addFirst(RuntimeExecutionTimeFixture.outputPrefix(2));
                assertEquals(500, postRaw(provider, messages, false).statusCode(), variant);
                awaitRequests(provider, script, 2);
                assertEquals(0, script.snapshot().verifiedHumanReplies(), variant);
                assertEquals("human_budget_context_missing_or_invalid", script.snapshot().requests().getLast().failureReason(), variant);
            }
        }
    }

    /** 仅末条正式样本可移除；缺失、错契约、移位或任意额外 SYSTEM/USER 都不能掩盖原问答边界。 */
    @Test
    void humanReplyRejectsMissingChangedOrMisplacedTimeSampleWithoutSkippingSystemMessages() throws Exception {
        try (var provider = new RuntimeExecutionWorkloadProvider(json)) {
            provider.start();
            for (String variant : List.of("missing", "schema", "ordinal", "sample", "offset", "zone", "zone-source",
                    "clock-source", "notice", "time-role", "metadata", "misplaced", "after-footer", "before-footer", "user-gap", "duplicate")) {
                var script = provider.register("human-time-" + variant, RuntimeExecutionWorkloadProvider.Workload.HUMAN_WAIT);
                var messages = new ArrayList<Map<String, Object>>();
                messages.add(Map.of("role", "user", "content", script.prompt()));
                var first = post(provider, script, messages, false);
                assertEquals(200, first.statusCode(), variant + ":" + first.body());
                addHumanReply(messages,json.readTree(first.body()).path("choices").get(0).path("message"),script.humanReply());
                var footer = new LinkedHashMap<>(RuntimeExecutionTimeFixture.footer(2));
                String original = footer.get("content").toString();
                String content = switch (variant) {
                    case "schema" -> original + "\n冻结格式：wrong.time-context.v2";
                    case "ordinal" -> original + "；模型准备序号：3";
                    case "sample" -> original.replace("2026-10-22T01:02:03.123456789Z", "not-an-instant");
                    case "offset" -> original.replace("2026-10-22T01:02:03.123456789Z", "2026-10-22T09:02:03.123456789+08:00");
                    case "zone" -> original.replace("（UTC）", "（Asia/Shanghai）");
                    case "zone-source" -> original.replace("不表示用户时区", "表示用户时区");
                    case "clock-source" -> original.replace("来源为宿主时钟", "来源为供应商时钟");
                    case "notice" -> original.replace("这是本次模型请求准备时的当前时间锚点", "当前用户时间");
                    default -> original;
                };
                if (List.of("schema", "ordinal", "sample", "offset", "zone", "zone-source", "clock-source", "notice").contains(variant))
                    assertFalse(original.equals(content), variant + ":实际时间文本必须发生篡改");
                if (variant.equals("time-role")) footer.put("role","system");
                if (variant.equals("metadata")) footer.put("tool_call_id", "unexpected-tool-result");
                footer.put("content", content);
                if (variant.equals("misplaced")) messages.add(messages.size() - 1, footer);
                else if (!variant.equals("missing")) {
                    if (variant.equals("before-footer")) messages.add(Map.of("role", "system", "content", "unrelated system"));
                    if (variant.equals("user-gap")) messages.add(Map.of("role", "user", "content", "unrelated user"));
                    messages.add(RuntimeExecutionTimeFixture.budget());
                    messages.add(footer);
                    if (variant.equals("after-footer")) messages.add(Map.of("role", "system", "content", "unrelated system"));
                    if (variant.equals("duplicate")) messages.add(footer);
                }
                var actual = new ArrayList<Map<String, Object>>();
                actual.add(RuntimeExecutionTimeFixture.outputPrefix(2)); actual.addAll(messages);
                var rejected = postRaw(provider, actual, false);
                assertEquals(500, rejected.statusCode(), variant + ":" + rejected.body());
                awaitRequests(provider, script, 2);
                assertEquals(0, script.snapshot().verifiedHumanReplies(), variant);
                String expected = List.of("before-footer", "user-gap", "duplicate").contains(variant)
                        ? (variant.equals("duplicate") ? "human_budget_context_missing_or_invalid" : "human_question_role_mismatch") : "execution_time_context_missing_or_invalid";
                assertEquals(expected, script.snapshot().requests().getLast().failureReason(), variant);
            }
        }
    }

    /** 知识结果只采用原query调用配对的准确来源版本，最终引用真实注入ID；错误版本拒绝并留失败。 */
    @Test
    void knowledgeEvidenceRequiresOriginalCallAndExactPublishedSourceVersion() throws Exception {
        try (var provider = new RuntimeExecutionWorkloadProvider(json)) {
            provider.start();
            for (boolean wrongVersion : List.of(false, true)) {
                var script = provider.register("knowledge-pair-" + wrongVersion, RuntimeExecutionWorkloadProvider.Workload.KNOWLEDGE);
                script.requireKnowledge("knowledge:test", "kb-test", "v1", "actualsentinel");
                var messages = new ArrayList<Map<String, Object>>(); messages.add(Map.of("role", "user", "content", script.prompt()));
                var first = post(provider, script, messages, false); assertEquals(200, first.statusCode(), first.body());
                var call = json.readTree(first.body()).path("choices").get(0).path("message").path("tool_calls").get(0).deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) call).put("id", "actual-knowledge-call");
                messages.add(Map.of("role", "assistant", "tool_calls", List.of(json.convertValue(call, Map.class))));
                messages.add(Map.of("role", "tool", "tool_call_id", "actual-knowledge-call", "content", json.writeValueAsString(Map.of(
                        "bundleId", "bundle-test", "version", "1", "sufficiency", "PARTIAL", "items", List.of(Map.of("itemId", "evidence-test", "citationId", "k0123456789abcdef",
                                "sourceId", "kb-test", "sourceVersion", wrongVersion ? "v2" : "v1", "text", "Original text actualsentinel"))))));
                var second = post(provider, script, messages, false); assertEquals(wrongVersion ? 500 : 200, second.statusCode(), second.body());
                awaitRequests(provider, script, 2);
                if (wrongVersion) { assertTrue(script.snapshot().verifiedKnowledgeItemIds().isEmpty()); assertFalse(script.snapshot().requests().getLast().failureReason().isBlank()); }
                else {
                    assertEquals(List.of("evidence-test"), script.snapshot().verifiedKnowledgeItemIds());
                    var response = json.readTree(second.body()).path("choices").get(0);
                    assertEquals("stop", response.path("finish_reason").asText());
                    assertFalse(response.path("message").has("tool_calls"));
                    assertEquals(script.rawFinalAnswer(), response.path("message").path("content").asText());
                    assertTrue(script.rawFinalAnswer().endsWith("[[cite:k0123456789abcdef]]"));
                }
            }
        }
    }

    /** 直接保留原Provider函数及身份，使用 Runtime 的真实采纳回执。 */
    private void addHumanReply(List<Map<String,Object>> messages,JsonNode providerMessage,String reply) throws Exception {
        messages.add(json.convertValue(providerMessage,new TypeReference<Map<String,Object>>() { }));
        messages.add(Map.of("role","tool","tool_call_id",providerMessage.path("tool_calls").get(0).path("id").asText(),"content",RuntimeExecutionTimeFixture.HUMAN_RECEIPT));
        messages.add(Map.of("role","user","content",reply));
    }

    /** 按冻结工作量生成真实时间末条：本地 AskUser 不新增外调动作，其他步骤各含一次实际能力动作。 */
    private HttpResponse<String> post(RuntimeExecutionWorkloadProvider provider, RuntimeExecutionWorkloadProvider.Script script,
                                      List<Map<String, Object>> messages, boolean stream) throws Exception {
        var snapshot = script.snapshot(); long requestOrdinal = snapshot.requestCount() + 1L;
        long actionOrdinal = snapshot.workload() == RuntimeExecutionWorkloadProvider.Workload.HUMAN_WAIT
                ? requestOrdinal : requestOrdinal * 2 - 1;
        var actual = new ArrayList<Map<String, Object>>();
        actual.add(RuntimeExecutionTimeFixture.outputPrefix(actionOrdinal)); actual.addAll(messages);
        if (snapshot.workload() == RuntimeExecutionWorkloadProvider.Workload.HUMAN_WAIT && requestOrdinal == 2) actual.add(RuntimeExecutionTimeFixture.budget());
        actual.add(RuntimeExecutionTimeFixture.footer(actionOrdinal));
        return postRaw(provider, actual, stream);
    }

    /** 通过真实 HTTP 原样提交消息；负控可缺失或篡改末条，工具声明只用于夹具选择逻辑。 */
    private HttpResponse<String> postRaw(RuntimeExecutionWorkloadProvider provider, List<Map<String, Object>> messages,
                                         boolean stream) throws Exception {
        var body = Map.of("model", "runtime-fixture", "stream", stream, "messages", messages,
                "tools", List.of(Map.of("type", "function", "function", Map.of("name", "sf_tool_template",
                        "parameters", Map.of("type", "object", "properties", Map.of("template", Map.of("type", "string"),
                                "data", Map.of("type", "object"))))),
                        Map.of("type", "function", "function", Map.of("name", "ask_user", "parameters", Map.of("type", "object",
                                "properties", Map.of("question", Map.of("type", "string"))))),
                        Map.of("type", "function", "function", Map.of("name", "knowledge_query", "parameters", Map.of("type", "object", "properties",
                                Map.of("knowledge_handle", Map.of("type", "string", "enum", List.of("knowledge:test")), "query", Map.of("type", "string")))))));
        var request = HttpRequest.newBuilder(URI.create(provider.endpoint() + "/chat/completions"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 模拟适配器重建原生调用身份；配对关系必须依据实际发送 id，不能依赖夹具原 id。 */
    private void addToolResult(List<Map<String, Object>> messages, JsonNode providerMessage, String rendered, String actualCallId) throws Exception {
        var call = providerMessage.path("tool_calls").get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) call).put("id", actualCallId);
        messages.add(Map.of("role", "assistant", "tool_calls", List.of(json.convertValue(call, Map.class))));
        messages.add(Map.of("role", "tool", "tool_call_id", actualCallId,
                "content", "工具结果：\n" + json.writeValueAsString(Map.of("status", "SUCCEEDED", "output",
                        Map.of("structuredContent", Map.of("success", true, "rendered", rendered))))));
    }

    /** 响应已接收后仍等待 Handler 的最终计时完成，避免用线程调度顺序冒充完成。 */
    private void awaitRequests(RuntimeExecutionWorkloadProvider provider, RuntimeExecutionWorkloadProvider.Script script,
                               int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (script.snapshot().requests().size() < expected && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(expected, script.snapshot().requests().size(), provider.requests().toString());
    }
}
