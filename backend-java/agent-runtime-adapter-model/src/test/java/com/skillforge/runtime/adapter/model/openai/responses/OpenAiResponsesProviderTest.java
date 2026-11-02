package com.skillforge.runtime.adapter.model.openai.responses;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.*;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolChoice;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.*;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ImageDetail;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;

/** 通用 Responses 协议的 loopback 契约验证，不连接或声称验收任何外部供应商。 */
class OpenAiResponsesProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEXT = "{\"type\":\"message\",\"id\":\"m1\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"你好\",\"annotations\":[]}]}";
    private static final String CALL = "{\"type\":\"function_call\",\"id\":\"f1\",\"call_id\":\"c1\",\"name\":\"lookup\",\"arguments\":\"{\\\"x\\\":1}\",\"status\":\"completed\"}";
    private static final String SECOND_CALL = "{\"type\":\"function_call\",\"id\":\"f2\",\"call_id\":\"c2\",\"name\":\"lookup\",\"arguments\":\"{\\\"x\\\":2}\",\"status\":\"completed\"}";
    private static final byte[] IMAGE = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6nO8AAAAASUVORK5CYII=");
    private HttpServer server;
    private ExecutorService workers;
    private OpenAiResponsesTransport transport;
    private String endpoint;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>(complete(TEXT));
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> pause = new AtomicReference<>();
    private final CountDownLatch started = new CountDownLatch(1);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        server.createContext("/v1/responses", exchange -> {
            requests.incrementAndGet(); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(JSON.readTree(exchange.getRequestBody())); started.countDown();
            try {
                var gate = pause.get(); if (gate != null && !gate.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture_gate_timeout");
                byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", received.get().path("stream").asBoolean() ? "text/event-stream" : "application/json");
                if (status.get() == 429) exchange.getResponseHeaders().set("Retry-After", "3");
                if (status.get() == 307) exchange.getResponseHeaders().set("Location", "/v1/responses");
                exchange.sendResponseHeaders(status.get(), body.length); exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start(); endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        transport = new OpenAiResponsesTransport(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC(), 100_000);
    }
    @AfterEach void stop() throws Exception {
        if (pause.get() != null) pause.get().countDown();
        if (transport != null) transport.close();
        if (server != null) server.stop(0);
        if (workers != null) { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    private static String complete(String output) {
        return "{\"id\":\"r1\",\"object\":\"response\",\"model\":\"fixture-model\",\"status\":\"completed\",\"output\":[" + output
                + "],\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"input_tokens_details\":{\"cached_tokens\":7},\"output_tokens_details\":{\"reasoning_tokens\":0}}}";
    }
    private InvocationRequest request(List<Message> messages) {
        return new InvocationRequest(endpoint, "fixture-key", "fixture-model",
                new InvocationProfile("custom-openai-compatible", "custom-v1", ModelProtocol.OPENAI_RESPONSES,
                        AuthenticationScheme.BEARER_API_KEY, OpenAiResponsesCodec.DIALECT), messages,
                new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "fixture"),
                new GenerationOptions(OptionalInt.of(256), OptionalDouble.of(0.2)), ReasoningPolicy.DISABLED,
                List.of(new ToolDefinition("lookup", "lookup", new TypedPayload("json-schema", "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"}},\"required\":[\"x\"],\"additionalProperties\":false}"), true, ModelToolKind.TOOL_CALL, false),
                        new ToolDefinition("optional", "optional", new TypedPayload("json-schema", "{\"type\":\"object\"}"), false, ModelToolKind.TOOL_CALL, false)),
                ToolChoice.AUTO, Duration.ofSeconds(3), () -> false, () -> { });
    }
    private InvocationRequest defaults() { return request(List.of(new Message(Role.SYSTEM, "core", null, List.of()), new Message(Role.USER, "hi", null, List.of()))); }
    private InvocationRequest copy(InvocationRequest base, List<Message> messages, ResponseContract contract, ReasoningPolicy reasoning,
            Duration timeout, BooleanSupplier cancelled, long limit, Optional<PromptCacheScope> cache) {
        return new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(), messages, contract, base.generation(), reasoning,
                base.tools(), base.toolChoice(), timeout, cancelled, base.mediaReader(), limit, cache, base.beforeSend());
    }
    private InvocationResult generate(InvocationRequest request) { return transport.generate(request, ChatCompletionsPolicy.standard()); }

    private static ModelContent.Image image(String id, ImageDetail detail) throws Exception {
        return image(id, "image/png", IMAGE.length, detail);
    }
    private static ModelContent.Image image(String id, String mimeType, long bytes, ImageDetail detail) throws Exception {
        var owner = new com.skillforge.runtime.api.SessionRef(new com.skillforge.runtime.api.TenantId("fixture-tenant"), "fixture-session");
        String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(IMAGE));
        return new ModelContent.Image(new MediaRef(owner, id, sha, mimeType, bytes, 1, 1), detail);
    }
    private InvocationRequest withMedia(InvocationRequest base, java.util.function.Function<MediaRef, byte[]> reader, long limit) {
        return new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(), base.messages(), base.response(),
                base.generation(), base.reasoning(), base.tools(), base.toolChoice(), base.timeout(), base.cancelled(), reader, limit,
                base.promptCacheScope(), base.prefixCapabilities(), base.beforeSend());
    }

    @Test void sendsStatelessTypedInputAndExactStrictFlagsWithInclusiveCacheUsage() {
        var base = defaults();
        var result = assertInstanceOf(InvocationResult.Completed.class, generate(copy(base, base.messages(), base.response(), base.reasoning(), base.timeout(), base.cancelled(), 100_000,
                Optional.of(new PromptCacheScope("a".repeat(64))))));
        assertEquals("你好", result.content()); assertEquals("stop", result.finishReasonCode());
        assertEquals(10, result.usage().inputTokens().orElseThrow()); assertEquals(7, result.usage().cachedInputTokens().orElseThrow());
        assertEquals(2, result.usage().outputTokens().orElseThrow()); assertTrue(result.usage().cacheWriteInputTokens().isEmpty());
        var wire = received.get();
        assertEquals("Bearer fixture-key", authorization.get()); assertFalse(wire.path("store").asBoolean());
        assertEquals("none", wire.at("/reasoning/effort").asText()); assertFalse(wire.path("parallel_tool_calls").asBoolean());
        assertFalse(wire.has("previous_response_id")); assertEquals("a".repeat(64), wire.path("prompt_cache_key").asText());
        assertTrue(wire.at("/tools/0/strict").asBoolean()); assertFalse(wire.at("/tools/1/strict").asBoolean());
        assertEquals("x", wire.at("/tools/0/parameters/required/0").asText());
        assertEquals("system", wire.at("/input/0/role").asText()); assertEquals("input_text", wire.at("/input/0/content/0/type").asText());
        assertEquals(256, wire.path("max_output_tokens").asInt()); assertEquals(1, requests.get());
    }

    @Test void sendsOrderedAndPureUserImagesWithEveryDetailForSyncAndStream() throws Exception {
        var low = image("low", ImageDetail.LOW); var high = image("high", ImageDetail.HIGH); var auto = image("auto", ImageDetail.AUTO);
        var reads = new AtomicInteger();
        var request = withMedia(request(List.of(new Message(Role.USER, List.of(new ModelContent.Text("before"), low,
                new ModelContent.Text("between"), high, auto, new ModelContent.Text("after")), null, List.of(), null))),
                media -> { assertTrue(Set.of(low.media(), high.media(), auto.media()).contains(media)); reads.incrementAndGet(); return IMAGE; }, 100_000);
        assertInstanceOf(InvocationResult.Completed.class, generate(request));
        var content = received.get().at("/input/0/content");
        assertEquals(6, content.size());
        assertEquals("before", content.get(0).path("text").asText());
        assertEquals("between", content.get(2).path("text").asText());
        assertEquals("after", content.get(5).path("text").asText());
        for (int index : List.of(1, 3, 4)) {
            var block = content.get(index);
            assertEquals("input_image", block.path("type").asText());
            assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(IMAGE), block.path("image_url").asText());
            assertFalse(block.has("text")); assertFalse(block.has("annotations"));
        }
        assertEquals("low", content.get(1).path("detail").asText());
        assertEquals("high", content.get(3).path("detail").asText());
        assertEquals("auto", content.get(4).path("detail").asText());
        assertEquals(3, reads.get());

        var pure = withMedia(request(List.of(new Message(Role.USER, List.of(auto), null, List.of(), null))),
                media -> { assertEquals(auto.media(), media); reads.incrementAndGet(); return IMAGE; }, 100_000);
        response.set(textStreamBody());
        assertEquals("你好", assertInstanceOf(StreamEvent.Completed.class, collect(pure).getLast()).result().content());
        assertEquals(1, received.get().path("input").size());
        assertEquals("user", received.get().at("/input/0/role").asText());
        assertEquals(1, received.get().at("/input/0/content").size());
        assertEquals("input_image", received.get().at("/input/0/content/0/type").asText());
        assertEquals(4, reads.get()); assertEquals(2, requests.get());
    }

    @Test void replaysNativePrivateItemsAndToolImageResultsForSyncAndStream() throws Exception {
        String reasoning = "{\"id\":\"rs1\",\"type\":\"reasoning\",\"summary\":[],\"encrypted_content\":\"private-image-cipher\"}";
        var low = image("tool-low", ImageDetail.LOW); var high = image("tool-high", ImageDetail.HIGH);
        for (boolean stream : List.of(false, true)) {
            var initial = withMedia(request(List.of(new Message(Role.USER, List.of(new ModelContent.Text("question"), high),
                    null, List.of(), null))), media -> IMAGE, 100_000);
            response.set(stream ? nativeToolsStreamBody(reasoning) : complete(reasoning + "," + CALL + "," + SECOND_CALL));
            var first = stream ? assertInstanceOf(StreamEvent.Completed.class, collect(initial).getLast()).result()
                    : assertInstanceOf(InvocationResult.Completed.class, generate(initial));
            assertEquals("tool_calls", first.finishReasonCode()); assertEquals(2, first.toolCalls().size());
            var state = first.protocolState().orElseThrow();
            assertEquals(3, state.items().size()); assertFalse(state.toString().contains("private-image-cipher"));
            var assistant = new Message(Role.ASSISTANT, List.of(), null, first.toolCalls(), first.reasoningContent(), Optional.of(state));
            var followup = withMedia(request(List.of(initial.messages().getFirst(), assistant,
                    new Message(Role.TOOL, List.of(new ModelContent.Text("before"), low, new ModelContent.Text("between"), high,
                            new ModelContent.Text("after")), "c1", List.of(), null), new Message(Role.TOOL, "second result", "c2", List.of()))),
                    media -> { assertTrue(media.equals(low.media()) || media.equals(high.media())); return IMAGE; }, 100_000);
            response.set(stream ? textStreamBody() : complete(TEXT));
            var completed = stream ? assertInstanceOf(StreamEvent.Completed.class, collect(followup).getLast()).result()
                    : assertInstanceOf(InvocationResult.Completed.class, generate(followup));
            assertEquals("你好", completed.content()); assertTrue(completed.toolCalls().isEmpty());
            var input = received.get().path("input");
            assertEquals(6, input.size());
            assertEquals(JSON.readTree(reasoning), input.get(1));
            assertEquals(JSON.readTree(CALL), input.get(2));
            assertEquals(JSON.readTree(SECOND_CALL), input.get(3));
            var result = input.get(4);
            assertEquals("function_call_output", result.path("type").asText()); assertEquals("c1", result.path("call_id").asText());
            assertTrue(result.path("output").isArray()); assertEquals(5, result.path("output").size());
            assertEquals("before", result.at("/output/0/text").asText());
            assertEquals("input_image", result.at("/output/1/type").asText()); assertEquals("low", result.at("/output/1/detail").asText());
            assertEquals("between", result.at("/output/2/text").asText());
            assertEquals("input_image", result.at("/output/3/type").asText()); assertEquals("high", result.at("/output/3/detail").asText());
            assertEquals("after", result.at("/output/4/text").asText());
            assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(IMAGE), result.at("/output/1/image_url").asText());
            assertEquals("c2", input.get(5).path("call_id").asText()); assertTrue(input.get(5).path("output").isTextual());
            assertEquals("second result", input.get(5).path("output").asText());
            assertFalse(received.get().toString().contains("Tool result images for tool_call_id"));
        }
        assertEquals(4, requests.get());
    }

    @Test void rejectsUnsupportedRolesMissingReadersAndInvalidImageTypesBeforeDispatch() throws Exception {
        var image = image("image", ImageDetail.AUTO); var reads = new AtomicInteger();
        for (var role : List.of(Role.SYSTEM, Role.ASSISTANT)) {
            var rejected = assertThrows(IllegalArgumentException.class, () -> {
                var base = request(List.of(new Message(role, List.of(image), null, List.of(), null)));
                generate(withMedia(base, media -> { reads.incrementAndGet(); return IMAGE; }, 100_000));
            });
            assertEquals("model_provider_image_role_invalid", rejected.getMessage());
        }
        var base = request(List.of(new Message(Role.USER, List.of(image), null, List.of(), null)));
        assertEquals("model_responses_image_unavailable", assertInstanceOf(InvocationResult.Rejected.class, generate(base)).reasonCode());
        assertEquals("model_responses_image_unavailable", assertInstanceOf(StreamEvent.Rejected.class, collect(base).getLast()).reasonCode());
        var unavailable = withMedia(base, media -> { throw new IllegalStateException("private store path and credential"); }, 100_000);
        var rejected = assertInstanceOf(InvocationResult.Rejected.class, generate(unavailable));
        assertEquals("model_responses_image_unavailable", rejected.reasonCode()); assertFalse(rejected.toString().contains("private store"));
        for (var invalid : List.<java.util.function.Function<MediaRef, byte[]>>of(media -> null, media -> new byte[0])) {
            var request = withMedia(base, invalid, 100_000);
            assertEquals("model_responses_image_unavailable", assertInstanceOf(InvocationResult.Rejected.class, generate(request)).reasonCode());
            assertEquals("model_responses_image_unavailable", assertInstanceOf(StreamEvent.Rejected.class, collect(request).getLast()).reasonCode());
        }
        var unsupported = image("unsupported", "image/svg+xml", IMAGE.length, ImageDetail.AUTO);
        var request = withMedia(request(List.of(new Message(Role.USER, List.of(unsupported), null, List.of(), null))),
                media -> { reads.incrementAndGet(); return IMAGE; }, 100_000);
        assertEquals("model_responses_image_invalid", assertInstanceOf(InvocationResult.Rejected.class, generate(request)).reasonCode());
        assertEquals("model_responses_image_invalid", assertInstanceOf(StreamEvent.Rejected.class, collect(request).getLast()).reasonCode());
        var orphan = withMedia(request(List.of(new Message(Role.USER, "question", null, List.of()),
                new Message(Role.TOOL, List.of(image), "unpaired-call", List.of(), null))),
                media -> { reads.incrementAndGet(); return IMAGE; }, 100_000);
        assertEquals("model_responses_tool_history_invalid", assertInstanceOf(InvocationResult.Rejected.class, generate(orphan)).reasonCode());
        assertEquals("model_responses_tool_history_invalid", assertInstanceOf(StreamEvent.Rejected.class, collect(orphan).getLast()).reasonCode());
        assertEquals(0, reads.get()); assertEquals(0, requests.get());
    }

    @Test void enforcesBase64LowerBoundAndExactEncodedImageRequestBytesBeforeDispatch() throws Exception {
        var image = image("image", ImageDetail.HIGH);
        var base = request(List.of(new Message(Role.USER, List.of(image), null, List.of(), null)));
        var noRead = withMedia(base, media -> { fail("Base64 alone exceeds the request budget"); return IMAGE; }, IMAGE.length);
        assertEquals("model_request_bytes_exceeded", assertInstanceOf(InvocationResult.Rejected.class, generate(noRead)).reasonCode());
        assertEquals("model_request_bytes_exceeded", assertInstanceOf(StreamEvent.Rejected.class, collect(noRead).getLast()).reasonCode());
        var oversized = image("oversized", "image/png", Long.MAX_VALUE, ImageDetail.AUTO);
        var declaredOverflow = withMedia(request(List.of(new Message(Role.USER, List.of(oversized), null, List.of(), null))),
                media -> { fail("Known wire overflow must precede media reads"); return IMAGE; }, 100_000);
        assertEquals("model_request_bytes_exceeded", assertInstanceOf(InvocationResult.Rejected.class, generate(declaredOverflow)).reasonCode());
        assertEquals(0, requests.get());
        var available = withMedia(base, media -> IMAGE, 100_000);
        long syncBytes = OpenAiResponsesCodec.encode(available, false).length;
        long streamBytes = OpenAiResponsesCodec.encode(available, true).length;
        assertEquals("model_request_bytes_exceeded", assertInstanceOf(InvocationResult.Rejected.class,
                generate(withMedia(base, media -> IMAGE, syncBytes - 1))).reasonCode());
        assertEquals("model_request_bytes_exceeded", assertInstanceOf(StreamEvent.Rejected.class,
                collect(withMedia(base, media -> IMAGE, streamBytes - 1)).getLast()).reasonCode());
        assertEquals(0, requests.get());
        assertInstanceOf(InvocationResult.Completed.class, generate(withMedia(base, media -> IMAGE, syncBytes)));
        response.set(textStreamBody());
        assertInstanceOf(StreamEvent.Completed.class, collect(withMedia(base, media -> IMAGE, streamBytes)).getLast());
        assertEquals(2, requests.get());
    }

    @Test void replaysFunctionsUsingCallIdAndKeepsOrderedSystemUpdates() throws Exception {
        var base = request(List.of(new Message(Role.USER, "question", null, List.of()),
                new Message(Role.ASSISTANT, "checking", null, List.of(new ToolCall("prior-call", "lookup", "{\"x\":1}"))),
                new Message(Role.SYSTEM, "new fact", null, List.of()), new Message(Role.TOOL, "answer", "prior-call", List.of())));
        response.set(complete(CALL));
        var result = assertInstanceOf(InvocationResult.Completed.class, generate(base));
        assertEquals(List.of(new ToolCall("c1", "lookup", "{\"x\":1}")), result.toolCalls());
        assertEquals("tool_calls", result.finishReasonCode());
        var input = received.get().path("input");
        assertEquals("output_text", input.get(1).at("/content/0/type").asText());
        assertEquals("function_call", input.get(2).path("type").asText()); assertEquals("prior-call", input.get(2).path("call_id").asText());
        assertEquals("system", input.get(3).path("role").asText());
        assertEquals("function_call_output", input.get(4).path("type").asText()); assertEquals("prior-call", input.get(4).path("call_id").asText());
        var incomplete = request(base.messages().subList(0, 3));
        assertInstanceOf(InvocationResult.Rejected.class, generate(incomplete)); assertEquals(1, requests.get());
    }

    @Test void encodesJsonObjectAndJsonSchemaWithoutWeakeningStrictContract() throws Exception {
        var base = defaults();
        for (ResponseFormat format : List.of(new ResponseFormat.Text(), new ResponseFormat.JsonObject(),
                new ResponseFormat.JsonSchema(new TypedPayload("json-schema", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")))) {
            var wire = JSON.readTree(OpenAiResponsesCodec.encode(copy(base, base.messages(), new ResponseContract(format, EnforcementLevel.STRICT, "fixture"),
                    base.reasoning(), base.timeout(), base.cancelled(), 100_000, Optional.empty()), false));
            String expected = format instanceof ResponseFormat.Text ? "text" : format instanceof ResponseFormat.JsonObject ? "json_object" : "json_schema";
            assertEquals(expected, wire.at("/text/format/type").asText());
            if (format instanceof ResponseFormat.JsonSchema) { assertTrue(wire.at("/text/format/strict").asBoolean()); assertEquals("runtime_result", wire.at("/text/format/name").asText()); }
        }
        assertEquals(0, requests.get());
    }

    @Test void parallelFunctionsFollowDeclaredBatchEligibilityOnTheActualResponsesWire() throws Exception {
        // 此例使用任意模型名，真实回包必须匹配本例请求，不能沿用 defaults 的 fixture-model。
        response.set(complete(TEXT).replace("fixture-model", "arbitrary-protocol-model"));
        var base = defaults();
        var schema = base.tools().getFirst().inputSchema();
        var serialRead = new ToolDefinition("serial_read", "READ_ONLY parallel_safe=false", schema, true, ModelToolKind.TOOL_CALL, true);
        var parallelRead = new ToolDefinition("parallel_read", "READ_ONLY parallel_safe=true", schema, true, ModelToolKind.TOOL_CALL, true);
        var write = new ToolDefinition("read_named_write", "NON_IDEMPOTENT_WRITE", schema, true, ModelToolKind.TOOL_CALL, false);
        var confirmation = new ToolDefinition("confirmed_read", "READ_ONLY confirmation=ALWAYS", schema, true, ModelToolKind.TOOL_CALL, false);
        var control = new ToolDefinition("ask_user", "control", schema, true, ModelToolKind.AGENT_ACTION, false);
        var resource = new ToolDefinition("read_skill_resource", "parallel_safe=false", schema, true, ModelToolKind.AGENT_ACTION, true);
        for (boolean nativeConversation : List.of(false, true)) {
            for (var tools : List.of(List.<ToolDefinition>of(), List.of(serialRead), List.of(parallelRead), List.of(write),
                    List.of(confirmation), List.of(control), List.of(resource), List.of(write, control),
                    List.of(serialRead, write, control), List.of(resource, confirmation))) {
                var request = new InvocationRequest(base.endpoint(), base.credential(), "arbitrary-protocol-model", base.profile(), base.messages(),
                        new ResponseContract(nativeConversation ? new ResponseFormat.NativeConversation() : new ResponseFormat.Text(), EnforcementLevel.STRICT, "fixture"),
                        base.generation(), base.reasoning(), tools, tools.isEmpty() ? ToolChoice.NONE : ToolChoice.AUTO,
                        base.timeout(), base.cancelled(), base.beforeSend());
                assertInstanceOf(InvocationResult.Completed.class, generate(request));
                var wire = received.get();
                assertEquals(nativeConversation && tools.stream().anyMatch(ToolDefinition::batchEligible),
                        wire.path("parallel_tool_calls").asBoolean());
                assertEquals("arbitrary-protocol-model", wire.path("model").asText());
            }
        }
        assertEquals(20, requests.get());
    }

    @Test void providerToolRecordAllowsOnlyTheExactNativeResourceControlException() {
        var schema = new TypedPayload("json-schema", "{\"type\":\"object\"}");
        assertTrue(new ToolDefinition("read_skill_resource", "resource", schema, false,
                ModelToolKind.AGENT_ACTION, true).batchEligible());
        for (String name : List.of("ask_user", "use_skill", "run_workflow", "Read_skill_resource",
                "read_skill_resource_extra", "read_skill_resource ")) {
            assertThrows(IllegalArgumentException.class, () ->
                    new ToolDefinition(name, "parallel_safe=true", schema, false, ModelToolKind.AGENT_ACTION, true));
            assertFalse(new ToolDefinition(name, "control", schema, false, ModelToolKind.AGENT_ACTION, false).batchEligible());
        }
        // 普通工具不按名字推断授权：叫 read_skill_resource 仍保持声明的 false，写名也可明确声明生成资格。
        assertFalse(new ToolDefinition("read_skill_resource", "write effect", schema, false, ModelToolKind.TOOL_CALL, false).batchEligible());
        assertTrue(new ToolDefinition("ordinary_tool", "tool", schema, false, ModelToolKind.TOOL_CALL, true).batchEligible());
    }

    @Test void cacheWriteUsageIsCountedAsPartOfInclusiveInputAndMissingRemainsUnknown() throws Exception {
        for (long written : List.of(0L, 3L)) {
            var raw = JSON.readTree("{\"input_tokens\":10,\"output_tokens\":2,\"input_tokens_details\":{\"cached_tokens\":7,\"cache_write_tokens\":" + written + "}}");
            var usage = OpenAiResponsesCodec.usage(raw);
            assertEquals(10, usage.inputTokens().orElseThrow()); assertEquals(7, usage.cachedInputTokens().orElseThrow());
            assertEquals(written, usage.cacheWriteInputTokens().orElseThrow());
        }
        assertTrue(OpenAiResponsesCodec.usage(JSON.readTree("{\"input_tokens\":10}")).cacheWriteInputTokens().isEmpty());
        assertTrue(OpenAiResponsesCodec.usage(JSON.readTree("{}")).inputTokens().isEmpty());
        for (String invalid : List.of("-1", "1.5", "4")) {
            var raw = JSON.readTree("{\"input_tokens\":10,\"input_tokens_details\":{\"cached_tokens\":7,\"cache_write_tokens\":" + invalid + "}}");
            assertThrows(java.io.IOException.class, () -> OpenAiResponsesCodec.usage(raw));
        }
        assertEquals(0, requests.get());
    }

    @Test void refusesUnsupportedReasoningMissingMediaAndOversizedRequestsBeforeSending() {
        var base = defaults();
        var media = new MediaRef(new com.skillforge.runtime.api.SessionRef(new com.skillforge.runtime.api.TenantId("fixture-tenant"), "fixture-session"),
                "fixture-image", "a".repeat(64), "image/png", 1, 1, 1);
        var image = new Message(Role.USER, List.of(new ModelContent.Image(media, ImageDetail.AUTO)), null, List.of(), null);
        for (var request : List.of(
                copy(base, base.messages(), base.response(), ReasoningPolicy.ENABLED, base.timeout(), base.cancelled(), 100_000, Optional.empty()),
                copy(base, List.of(new Message(Role.ASSISTANT, "answer", null, List.of(), "reasoning")), base.response(), base.reasoning(), base.timeout(), base.cancelled(), 100_000, Optional.empty()),
                copy(base, List.of(image), base.response(), base.reasoning(), base.timeout(), base.cancelled(), 100_000, Optional.empty()),
                copy(base, base.messages(), base.response(), base.reasoning(), base.timeout(), () -> true, 100_000, Optional.empty()),
                copy(base, base.messages(), base.response(), base.reasoning(), base.timeout(), base.cancelled(), 10, Optional.empty())))
            assertInstanceOf(InvocationResult.Rejected.class, generate(request));
        assertEquals(0, requests.get());
    }

    @Test void encodesEveryExplicitResponsesEffortWithoutInventingEnabledOrForcingASummary() throws Exception {
        var base = defaults();
        for (var effort : List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.DISABLED, ReasoningPolicy.MINIMAL,
                ReasoningPolicy.LOW, ReasoningPolicy.MEDIUM, ReasoningPolicy.HIGH, ReasoningPolicy.XHIGH, ReasoningPolicy.MAX)) {
            var raw = JSON.readTree(OpenAiResponsesCodec.encode(copy(base, base.messages(), base.response(), effort,
                    base.timeout(), base.cancelled(), 100_000, Optional.empty()), false));
            assertFalse(raw.path("reasoning").has("summary"));
            if (effort == ReasoningPolicy.DEFAULT) assertFalse(raw.path("reasoning").has("effort"));
            else assertEquals(effort == ReasoningPolicy.DISABLED ? "none" : effort.name().toLowerCase(Locale.ROOT), raw.at("/reasoning/effort").asText());
            assertEquals("reasoning.encrypted_content", raw.path("include").get(0).asText());
        }
        assertEquals(0, requests.get());
    }

    @Test void preservesPrivateReasoningAndExactOrderedOutputItemsForStatelessToolContinuation() throws Exception {
        String reasoning = "{\"id\":\"rs1\",\"type\":\"reasoning\",\"summary\":[],\"encrypted_content\":\"private-cipher\"}";
        response.set(complete(reasoning + "," + CALL)); var base = defaults();
        var result = assertInstanceOf(InvocationResult.Completed.class, generate(copy(base, base.messages(), base.response(),
                ReasoningPolicy.HIGH, base.timeout(), base.cancelled(), 100_000, Optional.empty())));
        assertNull(result.content()); assertNull(result.reasoningContent());
        var state = result.protocolState().orElseThrow(); assertEquals(2, state.items().size());
        assertFalse(result.toString().contains("private-cipher")); assertFalse(state.toString().contains("private-cipher"));
        var assistant = new Message(Role.ASSISTANT, List.of(), null, result.toolCalls(), null, Optional.of(state));
        var followup = request(List.of(new Message(Role.USER, "hi", null, List.of()), assistant, new Message(Role.TOOL, "done", "c1", List.of())));
        var wire = JSON.readTree(OpenAiResponsesCodec.encode(followup, false));
        assertEquals(4, wire.path("input").size());
        assertEquals(JSON.readTree(reasoning), wire.path("input").get(1));
        assertEquals(JSON.readTree(CALL), wire.path("input").get(2));
        assertEquals("function_call_output", wire.path("input").get(3).path("type").asText());
        var foreign = new NativeProtocolState(NativeProtocol.MESSAGES, state.dialectVersion(), state.modelId(), state.items());
        var bad = request(List.of(new Message(Role.ASSISTANT, List.of(), null, result.toolCalls(), null, Optional.of(foreign))));
        assertInstanceOf(InvocationResult.Rejected.class, generate(bad)); assertEquals(1, requests.get());
    }

    @Test void plaintextReasoningIsCompleteReplayStateWithoutRequiringCiphertext() throws Exception {
        String reasoning = "{\"id\":\"rs1\",\"type\":\"reasoning\",\"content\":[{\"type\":\"reasoning_text\",\"text\":\"raw-thought\"}]}";
        response.set(complete(reasoning + "," + CALL));
        var result = assertInstanceOf(InvocationResult.Completed.class, generate(defaults()));
        assertNull(result.content()); assertEquals("raw-thought", result.reasoningContent());
        var state = result.protocolState().orElseThrow();
        var assistant = new Message(Role.ASSISTANT, List.of(), null, result.toolCalls(), result.reasoningContent(), Optional.of(state));
        var wire = JSON.readTree(OpenAiResponsesCodec.encode(request(List.of(new Message(Role.USER, "hi", null, List.of()),
                assistant, new Message(Role.TOOL, "done", "c1", List.of()))), false));
        assertEquals(JSON.readTree(reasoning), wire.path("input").get(1));
        assertEquals(JSON.readTree(CALL), wire.path("input").get(2));
        assertFalse(state.toString().contains("raw-thought"));
        response.set(complete("{\"id\":\"rs1\",\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"not-replayable\"}]}"));
        assertInstanceOf(InvocationResult.OutcomeUnknown.class, generate(defaults()));
    }

    @Test void rawReasoningStreamMatchesCompletedThoughtAndRetainsTheFullNativeItem() throws Exception {
        String reasoning = "{\"id\":\"rs1\",\"type\":\"reasoning\",\"content\":[{\"type\":\"reasoning_text\",\"text\":\"first second\"}]}";
        response.set(event(0, "response.created", "\"response\":{\"id\":\"r1\",\"object\":\"response\"}")
                + event(1, "response.output_item.added", "\"output_index\":0,\"item\":{\"id\":\"rs1\",\"type\":\"reasoning\",\"content\":[]}")
                + event(2, "response.content_part.added", "\"output_index\":0,\"item_id\":\"rs1\",\"content_index\":0,\"part\":{\"type\":\"reasoning_text\",\"text\":\"\"}")
                + event(3, "response.reasoning_text.delta", "\"output_index\":0,\"item_id\":\"rs1\",\"content_index\":0,\"delta\":\"first\"")
                + event(4, "response.reasoning_text.delta", "\"output_index\":0,\"item_id\":\"rs1\",\"content_index\":0,\"delta\":\" second\"")
                + event(5, "response.reasoning_text.done", "\"output_index\":0,\"item_id\":\"rs1\",\"content_index\":0,\"text\":\"first second\"")
                + event(6, "response.content_part.done", "\"output_index\":0,\"item_id\":\"rs1\",\"content_index\":0,\"part\":{\"type\":\"reasoning_text\",\"text\":\"first second\"}")
                + event(7, "response.output_item.done", "\"output_index\":0,\"item\":" + reasoning)
                + event(8, "response.completed", "\"response\":" + complete(reasoning)));
        var events = collect(defaults()); var result = assertInstanceOf(StreamEvent.Completed.class, events.getLast()).result();
        String streamed = events.stream().filter(event -> event instanceof StreamEvent.ReasoningDelta)
                .map(event -> ((StreamEvent.ReasoningDelta) event).content()).collect(java.util.stream.Collectors.joining());
        assertEquals("first second", streamed); assertEquals(streamed, result.reasoningContent()); assertNull(result.content());
        assertEquals(JSON.readTree(reasoning), JSON.readTree(result.protocolState().orElseThrow().items().getFirst().canonicalJson()));
    }

    @Test void reasoningStreamUsesDoneCiphertextKeepsSummariesPrivateAndReplaysWithoutSummaryRequirement() throws Exception {
        String reasoning = "{\"id\":\"rs1\",\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"private-summary\"}],\"encrypted_content\":\"done-only-cipher\"}";
        String finalReasoning = "{\"id\":\"rs1\",\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"private-summary\"}]}";
        response.set(event(0, "response.created", "\"response\":{\"id\":\"r1\",\"object\":\"response\"}")
                + event(1, "response.output_item.added", "\"output_index\":0,\"item\":{\"id\":\"rs1\",\"type\":\"reasoning\",\"summary\":[]}")
                + event(2, "response.reasoning_summary_part.added", "\"output_index\":0,\"item_id\":\"rs1\",\"summary_index\":0,\"part\":{\"type\":\"summary_text\",\"text\":\"\"}")
                + event(3, "response.reasoning_summary_text.delta", "\"output_index\":0,\"item_id\":\"rs1\",\"summary_index\":0,\"delta\":\"private-summary\"")
                + event(4, "response.reasoning_summary_text.done", "\"output_index\":0,\"item_id\":\"rs1\",\"summary_index\":0,\"text\":\"private-summary\"")
                + event(5, "response.reasoning_summary_part.done", "\"output_index\":0,\"item_id\":\"rs1\",\"summary_index\":0,\"part\":{\"type\":\"summary_text\",\"text\":\"private-summary\"}")
                + event(6, "response.output_item.done", "\"output_index\":0,\"item\":" + reasoning)
                + event(7, "response.completed", "\"response\":" + complete(finalReasoning)));
        var events = collect(defaults()); var result = assertInstanceOf(StreamEvent.Completed.class, events.getLast()).result();
        assertNull(result.content()); assertNull(result.reasoningContent());
        assertTrue(events.stream().noneMatch(event -> event instanceof StreamEvent.ContentDelta || event instanceof StreamEvent.ReasoningDelta));
        assertEquals("done-only-cipher", JSON.readTree(result.protocolState().orElseThrow().items().getFirst().canonicalJson()).path("encrypted_content").asText());
        String noSummary = "{\"id\":\"rs1\",\"type\":\"reasoning\",\"encrypted_content\":\"cipher-without-summary\"}";
        response.set(event(0, "response.created", "\"response\":{\"id\":\"r1\",\"object\":\"response\"}")
                + event(1, "response.output_item.added", "\"output_index\":0,\"item\":{\"id\":\"rs1\",\"type\":\"reasoning\"}")
                + event(2, "response.output_item.done", "\"output_index\":0,\"item\":" + noSummary)
                + event(3, "response.completed", "\"response\":" + complete("{\"id\":\"rs1\",\"type\":\"reasoning\"}")));
        assertInstanceOf(StreamEvent.Completed.class, collect(defaults()).getLast());
    }

    private static String event(int sequence, String type, String fields) {
        return "event: " + type + "\ndata: {\"type\":\"" + type + "\",\"sequence_number\":" + sequence + "," + fields + "}\n\n";
    }
    private static String streamBody() {
        return event(0, "response.created", "\"response\":{\"id\":\"r1\",\"object\":\"response\"}")
                + event(1, "response.output_item.added", "\"output_index\":0,\"item\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}")
                + event(2, "response.content_part.added", "\"output_index\":0,\"item_id\":\"m1\",\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"\",\"annotations\":[]}")
                + event(3, "response.output_text.delta", "\"output_index\":0,\"item_id\":\"m1\",\"content_index\":0,\"delta\":\"你好\"")
                + event(4, "response.output_text.done", "\"output_index\":0,\"item_id\":\"m1\",\"content_index\":0,\"text\":\"你好\"")
                + event(5, "response.content_part.done", "\"output_index\":0,\"item_id\":\"m1\",\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"你好\",\"annotations\":[]}")
                + event(6, "response.output_item.done", "\"output_index\":0,\"item\":" + TEXT)
                + event(7, "response.output_item.added", "\"output_index\":1,\"item\":{\"id\":\"f1\",\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"lookup\",\"arguments\":\"\"}")
                + event(8, "response.function_call_arguments.delta", "\"output_index\":1,\"item_id\":\"f1\",\"delta\":\"{\\\"x\\\":1}\"")
                + event(9, "response.function_call_arguments.done", "\"output_index\":1,\"item_id\":\"f1\",\"arguments\":\"{\\\"x\\\":1}\"")
                + event(10, "response.output_item.done", "\"output_index\":1,\"item\":" + CALL)
                + event(11, "response.completed", "\"response\":" + complete(TEXT + "," + CALL));
    }
    private static String textStreamBody() {
        return event(0, "response.created", "\"response\":{\"id\":\"r1\",\"object\":\"response\"}")
                + event(1, "response.output_item.added", "\"output_index\":0,\"item\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}")
                + event(2, "response.content_part.added", "\"output_index\":0,\"item_id\":\"m1\",\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"\",\"annotations\":[]}")
                + event(3, "response.output_text.delta", "\"output_index\":0,\"item_id\":\"m1\",\"content_index\":0,\"delta\":\"你好\"")
                + event(4, "response.output_text.done", "\"output_index\":0,\"item_id\":\"m1\",\"content_index\":0,\"text\":\"你好\"")
                + event(5, "response.content_part.done", "\"output_index\":0,\"item_id\":\"m1\",\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"你好\",\"annotations\":[]}")
                + event(6, "response.output_item.done", "\"output_index\":0,\"item\":" + TEXT)
                + event(7, "response.completed", "\"response\":" + complete(TEXT));
    }
    private static String nativeToolsStreamBody(String reasoning) {
        return event(0, "response.created", "\"response\":{\"id\":\"r1\",\"object\":\"response\"}")
                + event(1, "response.output_item.added", "\"output_index\":0,\"item\":{\"id\":\"rs1\",\"type\":\"reasoning\",\"summary\":[]}")
                + event(2, "response.output_item.done", "\"output_index\":0,\"item\":" + reasoning)
                + event(3, "response.output_item.added", "\"output_index\":1,\"item\":{\"id\":\"f1\",\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"lookup\",\"arguments\":\"\"}")
                + event(4, "response.function_call_arguments.delta", "\"output_index\":1,\"item_id\":\"f1\",\"delta\":\"{\\\"x\\\":1}\"")
                + event(5, "response.function_call_arguments.done", "\"output_index\":1,\"item_id\":\"f1\",\"arguments\":\"{\\\"x\\\":1}\"")
                + event(6, "response.output_item.done", "\"output_index\":1,\"item\":" + CALL)
                + event(7, "response.output_item.added", "\"output_index\":2,\"item\":{\"id\":\"f2\",\"type\":\"function_call\",\"call_id\":\"c2\",\"name\":\"lookup\",\"arguments\":\"\"}")
                + event(8, "response.function_call_arguments.delta", "\"output_index\":2,\"item_id\":\"f2\",\"delta\":\"{\\\"x\\\":2}\"")
                + event(9, "response.function_call_arguments.done", "\"output_index\":2,\"item_id\":\"f2\",\"arguments\":\"{\\\"x\\\":2}\"")
                + event(10, "response.output_item.done", "\"output_index\":2,\"item\":" + SECOND_CALL)
                + event(11, "response.completed", "\"response\":" + complete(reasoning + "," + CALL + "," + SECOND_CALL));
    }
    private List<StreamEvent> collect(InvocationRequest request) throws Exception {
        var events = new CopyOnWriteArrayList<StreamEvent>(); var done = new CountDownLatch(1); var failure = new AtomicReference<Throwable>();
        transport.stream(request, ChatCompletionsPolicy.standard()).subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(StreamEvent event) { events.add(event); }
            public void onError(Throwable error) { failure.set(error); done.countDown(); }
            public void onComplete() { done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertNull(failure.get()); return List.copyOf(events);
    }

    @Test void streamsTextAndFunctionArgumentsAndReconcilesTheFinalCompletedResponse() throws Exception {
        response.set(streamBody()); var events = collect(defaults());
        var terminal = assertInstanceOf(StreamEvent.Completed.class, events.getLast());
        assertEquals("你好", terminal.result().content()); assertEquals("c1", terminal.result().toolCalls().getFirst().callId());
        assertEquals("{\"x\":1}", terminal.result().toolCalls().getFirst().argumentsJson());
        assertTrue(events.stream().anyMatch(event -> event instanceof StreamEvent.ContentDelta delta && delta.content().equals("你好")));
        assertTrue(events.stream().anyMatch(event -> event instanceof StreamEvent.ToolCallDelta delta && delta.index() == 0 && delta.argumentsDelta().equals("{\"x\":1}")));
        assertEquals(10, terminal.result().usage().inputTokens().orElseThrow()); assertEquals(1, requests.get());
    }

    @Test void neverCompletesTruncatedOrConflictingStreams() throws Exception {
        for (String body : List.of(streamBody().substring(0, streamBody().indexOf("event: response.completed")),
                streamBody().replace("\"arguments\":\"{\\\"x\\\":1}\"", "\"arguments\":\"{\\\"x\\\":2}\""),
                streamBody().replace("\"sequence_number\":8", "\"sequence_number\":7"))) {
            response.set(body); var events = collect(defaults()); assertInstanceOf(StreamEvent.OutcomeUnknown.class, events.getLast());
            assertTrue(events.stream().noneMatch(event -> event instanceof StreamEvent.Completed));
        }
        assertEquals(3, requests.get());
    }

    @Test void preservesReportedUsageWhenCompletedOutputConflictsButRejectsForeignIdentity() throws Exception {
        String prefix = textStreamBody().substring(0, textStreamBody().indexOf("event: response.completed"));
        String conflicting = complete(TEXT.replace("你好", "不同"));
        response.set(prefix + event(7, "response.completed", "\"response\":" + conflicting));
        var events = collect(defaults());
        var usage = events.stream().filter(StreamEvent.UsageDelta.class::isInstance)
                .map(StreamEvent.UsageDelta.class::cast).toList();
        assertEquals(1, usage.size());
        assertEquals(10, usage.getFirst().usage().inputTokens().orElseThrow());
        assertEquals(2, usage.getFirst().usage().outputTokens().orElseThrow());
        assertEquals(7, usage.getFirst().usage().cachedInputTokens().orElseThrow());
        assertInstanceOf(StreamEvent.OutcomeUnknown.class, events.getLast());

        response.set(prefix + event(7, "response.completed", "\"response\":" + conflicting.replace("fixture-model", "foreign-model")));
        var foreign = collect(defaults());
        assertTrue(foreign.stream().noneMatch(StreamEvent.UsageDelta.class::isInstance));
        assertInstanceOf(StreamEvent.OutcomeUnknown.class, foreign.getLast());
        assertEquals(2, requests.get());
    }

    @Test void synchronousUsageRequiresMatchingIdentityButSurvivesInvalidOutput() {
        String valid = complete(TEXT);
        for (String body : List.of(valid.replace("fixture-model", "foreign-model"),
                valid.replace("\"model\":\"fixture-model\",", ""))) {
            response.set(body);
            var unknown = assertInstanceOf(InvocationResult.OutcomeUnknown.class, generate(defaults()));
            assertTrue(unknown.usage().inputTokens().isEmpty());
            assertTrue(unknown.usage().outputTokens().isEmpty());
        }
        response.set(complete(TEXT.replace("\"role\":\"assistant\"", "\"role\":\"user\"")));
        var invalidOutput = assertInstanceOf(InvocationResult.OutcomeUnknown.class, generate(defaults()));
        assertEquals(10, invalidOutput.usage().inputTokens().orElseThrow());
        assertEquals(2, invalidOutput.usage().outputTokens().orElseThrow());
        assertEquals(7, invalidOutput.usage().cachedInputTokens().orElseThrow());
        assertEquals(3, requests.get());
    }

    @Test void failedAndIncompleteResponsesNeverBecomeCompleted() throws Exception {
        for (String state : List.of("failed", "incomplete")) {
            var raw = complete("").replace("\"status\":\"completed\"", "\"status\":\"" + state + "\"");
            response.set(raw); assertEquals("model_response_" + state, assertInstanceOf(InvocationResult.Rejected.class, generate(defaults())).reasonCode());
            response.set(event(0, "response.created", "\"response\":{\"id\":\"r1\",\"object\":\"response\"}") + event(1, "response." + state, "\"response\":" + raw));
            assertInstanceOf(StreamEvent.Rejected.class, collect(defaults()).getLast());
        }
        assertEquals(4, requests.get());
    }

    @Test void completedEventCannotTurnAnIncompleteResponsePayloadIntoSuccess() throws Exception {
        String incomplete = complete("").replace("\"status\":\"completed\"", "\"status\":\"incomplete\"");
        response.set(event(0, "response.created", "\"response\":{\"id\":\"r1\",\"object\":\"response\"}")
                + event(1, "response.completed", "\"response\":" + incomplete));
        var events = collect(defaults()); assertInstanceOf(StreamEvent.OutcomeUnknown.class, events.getLast());
        assertTrue(events.stream().noneMatch(event -> event instanceof StreamEvent.Completed)); assertEquals(1, requests.get());
    }

    @Test void sdkDoesNotFollowRedirectsOrAcceptDuplicateJsonFields() {
        status.set(307);
        assertInstanceOf(InvocationResult.Rejected.class, generate(defaults()));
        assertEquals(1, requests.get());
        status.set(200);
        response.set(complete(TEXT).replace("\"output_tokens\":2", "\"output_tokens\":2,\"output_tokens\":3"));
        assertInstanceOf(InvocationResult.OutcomeUnknown.class, generate(defaults()));
        assertEquals(2, requests.get());
    }

    @Test void sdkRetainsErrorEnvelopeAndKeepsErrorsAfterStreamOpenUnknown() throws Exception {
        status.set(400); response.set("{\"error\":{\"code\":\"context_length_exceeded\"}}");
        assertEquals("model_context_overflow", assertInstanceOf(InvocationResult.Rejected.class, generate(defaults())).reasonCode());
        response.set("{\"code\":\"context_length_exceeded\"}");
        assertEquals("model_http_rejected", assertInstanceOf(InvocationResult.Rejected.class, generate(defaults())).reasonCode());
        status.set(200);
        String text = textStreamBody();
        response.set(text.substring(0, text.indexOf("event: response.completed"))
                + "event: error\ndata: {\"error\":{\"code\":\"context_length_exceeded\"}}\n\n");
        var events = collect(defaults());
        assertTrue(events.stream().anyMatch(event -> event instanceof StreamEvent.ContentDelta));
        assertInstanceOf(StreamEvent.OutcomeUnknown.class, events.getLast());
    }

    @Test void httpRateLimitAndServerFailureAreNotRetried() {
        status.set(429); var rejected = assertInstanceOf(InvocationResult.Rejected.class, generate(defaults()));
        assertEquals("model_rate_limited", rejected.reasonCode()); assertTrue(rejected.retryAt().isPresent());
        status.set(503); assertInstanceOf(InvocationResult.OutcomeUnknown.class, generate(defaults())); assertEquals(2, requests.get());
    }

    @Test void deadlineAndCancellationCloseTheSingleOutstandingDispatch() throws Exception {
        pause.set(new CountDownLatch(1)); var cancelled = new AtomicBoolean(); var base = defaults();
        var request = copy(base, base.messages(), base.response(), base.reasoning(), base.timeout(), cancelled::get, 100_000, Optional.empty());
        var result = CompletableFuture.supplyAsync(() -> generate(request));
        assertTrue(started.await(2, TimeUnit.SECONDS)); cancelled.set(true);
        assertInstanceOf(InvocationResult.OutcomeUnknown.class, result.get(3, TimeUnit.SECONDS)); assertEquals(1, requests.get());
        assertInstanceOf(InvocationResult.OutcomeUnknown.class, generate(copy(base, base.messages(), base.response(), base.reasoning(),
                Duration.ofMillis(150), () -> false, 100_000, Optional.empty())));
        assertEquals(2, requests.get());
    }
}
