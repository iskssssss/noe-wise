package com.skillforge.runtime.adapter.model.deepseek.messages;

import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.deepseek.DeepSeekModelProvider;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient.DiscoveredModel;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.*;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolChoice;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.*;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
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
import org.junit.jupiter.api.*;

class DeepSeekMessagesProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROVIDER_ENDPOINT = "https://api.deepseek.com";
    private static final PromptPrefixCapabilities IN_HISTORY = new PromptPrefixCapabilities(
            CapabilitySupportStatus.SUPPORTED,ToolUpdateMode.NONE,CapabilitySupportStatus.SUPPORTED);
    private static final String COMPLETE = """
            {"id":"msg-1","type":"message","role":"assistant","model":"deepseek-flash",
             "content":[{"type":"text","text":"done"}],"stop_reason":"end_turn",
             "usage":{"input_tokens":7,"cache_read_input_tokens":100,"cache_creation_input_tokens":3,"output_tokens":2}}
            """;
    private HttpServer server;
    private ExecutorService executor;
    private String localEndpoint;
    private ModelProviderRuntime provider;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> response = new AtomicReference<>(COMPLETE);
    private final AtomicReference<byte[]> responseBytes = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> pause = new AtomicReference<>();
    private final CountDownLatch started = new CountDownLatch(1);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/anthropic/v1/messages", exchange -> {
            requests.incrementAndGet(); auth.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            received.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            started.countDown();
            try {
                var latch = pause.get();
                if (latch != null && !latch.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("test_gate_timeout");
                byte[] bytes = responseBytes.get() == null ? response.get().getBytes(StandardCharsets.UTF_8) : responseBytes.get();
                exchange.getResponseHeaders().set("Content-Type", received.get().path("stream").asBoolean() ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(status.get(), bytes.length); exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start(); localEndpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        provider = runtime(100_000);
    }

    @AfterEach void stop() throws Exception {
        var latch = pause.get(); if (latch != null) latch.countDown();
        if (server != null) server.stop(0);
        if (executor != null) { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private ModelProviderRuntime runtime(int limit) {
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var messages = new LocalTransport(new DeepSeekMessagesTransport(http, Clock.systemUTC(), limit), localEndpoint);
        return new ModelProviderRuntime(List.of(new DeepSeekModelProvider(ModelProviderTransport.unavailable(), messages)));
    }
    private InvocationRequest request(List<Message> history) {
        return new InvocationRequest(PROVIDER_ENDPOINT, "test-key", "deepseek-flash",
                new InvocationProfile("deepseek-api", "2026-09-13", ModelProtocol.ANTHROPIC_MESSAGES,
                        AuthenticationScheme.ANTHROPIC_API_KEY, DeepSeekMessagesCodec.DIALECT), history,
                new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.BEST_EFFORT, "test"),
                new GenerationOptions(OptionalInt.of(256), OptionalDouble.empty()), ReasoningPolicy.DISABLED,
                List.of(new ToolDefinition("lookup", "lookup", new TypedPayload("application/json", "{\"type\":\"object\"}"),
                        false, ModelToolKind.TOOL_CALL, true)), ToolChoice.AUTO, Duration.ofSeconds(3), () -> false, () -> { }).withPrefixCapabilities(IN_HISTORY);
    }
    private InvocationRequest defaults() { return request(List.of(new Message(Role.SYSTEM, "core", null, List.of()), new Message(Role.USER, "hi", null, List.of()))); }
    private InvocationRequest copy(InvocationRequest base, ResponseContract response, ReasoningPolicy reasoning,
            Duration timeout, java.util.function.BooleanSupplier cancelled, long maximumBytes) {
        return new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(), base.messages(), response,
                base.generation(), reasoning, base.tools(), base.toolChoice(), timeout, cancelled, base.mediaReader(), maximumBytes, base.beforeSend()).withPrefixCapabilities(base.prefixCapabilities());
    }

    @Test void messagesSendAuthorizationRunsOnceAndDenialNeverPosts() throws Exception {
        var checks = new AtomicInteger();
        var authorized = defaults().withBeforeSend(checks::incrementAndGet);
        assertInstanceOf(InvocationResult.Completed.class, provider.generate("deepseek", authorized));
        assertEquals(1, checks.get()); assertEquals(1, requests.get());
        response.set(streamBody());
        assertInstanceOf(StreamEvent.Completed.class, collect(authorized).getLast());
        assertEquals(2, checks.get()); assertEquals(2, requests.get());
        var denied = defaults().withBeforeSend(() -> { throw new IllegalStateException("revoked"); });
        assertEquals("model_pre_dispatch_rejected", assertInstanceOf(InvocationResult.Rejected.class,
                provider.generate("deepseek", denied)).reasonCode());
        assertEquals("model_pre_dispatch_rejected", assertInstanceOf(StreamEvent.Rejected.class,
                collect(denied).getLast()).reasonCode());
        assertEquals(2, requests.get());
    }

    @Test void sendsRealMessagesProtocolAndCountsCachedInputOnce() {
        var result = assertInstanceOf(InvocationResult.Completed.class, provider.generate("deepseek", defaults()));
        assertEquals("test-key", auth.get()); assertEquals("done", result.content());
        assertEquals(110, result.usage().inputTokens().orElseThrow());
        assertEquals(100, result.usage().cachedInputTokens().orElseThrow());
        assertEquals(3, result.usage().cacheWriteInputTokens().orElseThrow());
        assertEquals(2, result.usage().outputTokens().orElseThrow());
        assertEquals("disabled", received.get().at("/thinking/type").asText());
        assertEquals("object", received.get().at("/tools/0/input_schema/type").asText());
        assertEquals("auto", received.get().at("/tool_choice/type").asText());
        assertEquals("core", received.get().path("system").asText());
    }

    private static final byte[] IMAGE = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6nO8AAAAASUVORK5CYII=");

    private static ModelContent.Image image(String mimeType, long bytes, int dimension) throws Exception {
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(IMAGE));
        return new ModelContent.Image(new MediaRef(new SessionRef(new TenantId("tenant"), "session"),
                "image-1", digest, mimeType, bytes, dimension, dimension), ImageDetail.HIGH);
    }

    private InvocationRequest withMedia(InvocationRequest base,
            java.util.function.Function<MediaRef, byte[]> reader, long maximumBytes) {
        return new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(), base.messages(),
                base.response(), base.generation(), base.reasoning(), base.tools(), base.toolChoice(),
                base.timeout(), base.cancelled(), reader, maximumBytes, base.beforeSend()).withPrefixCapabilities(base.prefixCapabilities());
    }

    @Test void sendsPureAndOrderedUserImagesUsingTheTrustedMediaReader() throws Exception {
        var image = image("image/png", IMAGE.length, 1);
        var base = request(List.of(new Message(Role.USER, List.of(new ModelContent.Text("before"), image,
                new ModelContent.Text("after")), null, List.of(), null)));
        var reads = new AtomicInteger();
        var request = withMedia(base, media -> { assertEquals(image.media(), media); reads.incrementAndGet(); return IMAGE; }, 100_000);
        assertInstanceOf(InvocationResult.Completed.class, provider.generate("deepseek", request));
        assertEquals(1, reads.get());
        var contents = received.get().at("/messages/0/content");
        assertEquals(3, contents.size());
        assertEquals("before", contents.get(0).path("text").asText());
        assertEquals("image", contents.get(1).path("type").asText());
        assertEquals("base64", contents.get(1).at("/source/type").asText());
        assertEquals("image/png", contents.get(1).at("/source/media_type").asText());
        assertArrayEquals(IMAGE, Base64.getDecoder().decode(contents.get(1).at("/source/data").asText()));
        assertEquals("after", contents.get(2).path("text").asText());

        var pure = withMedia(request(List.of(new Message(Role.USER, List.of(image), null, List.of(), null))),
                media -> IMAGE, 100_000);
        response.set(streamBody());
        assertInstanceOf(StreamEvent.Completed.class, collect(pure).getLast());
        assertEquals(1, received.get().at("/messages/0/content").size());
        assertEquals("image", received.get().at("/messages/0/content/0/type").asText());
    }

    @Test void embedsToolImagesInsideTheOriginalToolResultAndRetainsAllCallResults() throws Exception {
        var image = image("image/png", IMAGE.length, 1);
        var base = request(List.of(new Message(Role.USER, "first", null, List.of()),
                new Message(Role.ASSISTANT, (String) null, null,
                        List.of(new ToolCall("a", "lookup", "{}"), new ToolCall("b", "lookup", "{}"))),
                new Message(Role.TOOL, List.of(new ModelContent.Text("before"), image, new ModelContent.Text("after")),
                        "a", List.of(), null), new Message(Role.TOOL, "second result", "b", List.of())));
        assertInstanceOf(InvocationResult.Completed.class, provider.generate("deepseek", withMedia(base, media -> IMAGE, 100_000)));
        var wire = received.get();
        assertEquals(3, wire.path("messages").size());
        var results = wire.at("/messages/2/content");
        assertEquals(2, results.size());
        assertEquals("tool_result", results.get(0).path("type").asText());
        assertEquals("a", results.get(0).path("tool_use_id").asText());
        assertEquals("before", results.get(0).at("/content/0/text").asText());
        assertEquals("image", results.get(0).at("/content/1/type").asText());
        assertEquals("after", results.get(0).at("/content/2/text").asText());
        assertEquals("b", results.get(1).path("tool_use_id").asText());
        assertEquals("second result", results.get(1).path("content").asText());
        assertFalse(wire.toString().contains("Tool result images for tool_call_id"));
    }

    @Test void rejectsUnavailableOrUnsupportedImagesAndEncodedWireOverflowBeforeHttp() throws Exception {
        var image = image("image/png", IMAGE.length, 1);
        var base = request(List.of(new Message(Role.USER, List.of(image), null, List.of(), null)));
        var available = withMedia(base, media -> IMAGE, 100_000);
        long actualWireBytes = DeepSeekMessagesCodec.encode(available, false).length;
        var unavailable = withMedia(base, media -> { throw new IllegalStateException("private media store path"); }, 100_000);
        var denied = assertInstanceOf(InvocationResult.Rejected.class, provider.generate("deepseek", unavailable));
        assertEquals("model_messages_image_unavailable", denied.reasonCode());
        assertFalse(denied.toString().contains("private media store path"));
        assertInstanceOf(InvocationResult.Rejected.class, provider.generate("deepseek", withMedia(base, media -> new byte[0], 100_000)));
        assertEquals("model_request_bytes_exceeded", assertInstanceOf(InvocationResult.Rejected.class,
                provider.generate("deepseek", withMedia(base, media -> IMAGE, actualWireBytes - 1))).reasonCode());
        for (var invalid : List.of(image("image/svg+xml", IMAGE.length, 1),
                image("image/png", 32L * 1024 * 1024 + 1, 1), image("image/png", IMAGE.length, 8193))) {
            var rejected = request(List.of(new Message(Role.USER, List.of(invalid), null, List.of(), null)));
            assertEquals("model_messages_image_invalid", assertInstanceOf(InvocationResult.Rejected.class,
                    provider.generate("deepseek", withMedia(rejected, media -> { fail("invalid image must not be read"); return IMAGE; }, 100_000))).reasonCode());
        }
        var tooMany = request(List.of(new Message(Role.USER, Collections.nCopies(601, image), null, List.of(), null)));
        assertEquals("model_messages_images_exceeded", assertInstanceOf(InvocationResult.Rejected.class,
                provider.generate("deepseek", withMedia(tooMany, media -> { fail("excess images must not be read"); return IMAGE; }, 100_000))).reasonCode());
        var largeBatchImage = image("image/png", IMAGE.length, 4097);
        var largeBatch = request(List.of(new Message(Role.USER, Collections.nCopies(15, largeBatchImage), null, List.of(), null)));
        assertEquals("model_messages_image_invalid", assertInstanceOf(InvocationResult.Rejected.class,
                provider.generate("deepseek", withMedia(largeBatch, media -> { fail("oversized batch image must not be read"); return IMAGE; }, 100_000))).reasonCode());
        assertEquals("model_request_bytes_exceeded", assertInstanceOf(InvocationResult.Rejected.class,
                provider.generate("deepseek", withMedia(base, media -> { fail("base64 lower bound already exceeds wire budget"); return IMAGE; }, IMAGE.length))).reasonCode());
        assertEquals(0, requests.get());
        assertInstanceOf(InvocationResult.Completed.class, provider.generate("deepseek", withMedia(base, media -> IMAGE, actualWireBytes)));
    }

    @Test void encodesDocumentedThinkingEffortAndCompatibleAliases() throws Exception {
        var base=defaults();
        for (var policy:ReasoningPolicy.values()) {
            var wire=JSON.readTree(DeepSeekMessagesCodec.encode(copy(base,base.response(),policy,base.timeout(),base.cancelled(),100_000),false));
            assertEquals(policy==ReasoningPolicy.DISABLED?"disabled":"enabled",wire.at("/thinking/type").asText());
            String expected=switch(policy) {
                case DEFAULT,ENABLED,DISABLED -> "";
                case MINIMAL,LOW -> "low";
                case MEDIUM,HIGH,XHIGH -> "high";
                case MAX -> "max";
            };
            assertEquals(expected,wire.at("/output_config/effort").asText());
        }
    }

    @Test void providerExposesOnlyDocumentedCanonicalReasoningLevels() {
        var base=defaults();
        for(var policy:List.of(ReasoningPolicy.MINIMAL,ReasoningPolicy.MEDIUM,ReasoningPolicy.XHIGH))
            assertEquals("model_reasoning_policy_unsupported",assertInstanceOf(InvocationResult.Rejected.class,
                    provider.generate("deepseek",copy(base,base.response(),policy,base.timeout(),base.cancelled(),100_000))).reasonCode());
        assertEquals(0,requests.get());
        for(var policy:List.of(ReasoningPolicy.DEFAULT,ReasoningPolicy.ENABLED,ReasoningPolicy.LOW,ReasoningPolicy.HIGH,ReasoningPolicy.MAX))
            assertInstanceOf(InvocationResult.Completed.class,provider.generate("deepseek",
                    copy(base,base.response(),policy,base.timeout(),base.cancelled(),100_000)));
        assertEquals(5,requests.get());
    }

    private InvocationRequest thinking() {
        var base=defaults();return copy(base,base.response(),ReasoningPolicy.HIGH,base.timeout(),base.cancelled(),100_000);
    }
    private Message replay(InvocationResult.Completed result) {
        return new Message(Role.ASSISTANT,result.content()==null?List.of():List.of(new com.skillforge.runtime.api.media.ModelContent.Text(result.content())),
                null,result.toolCalls(),result.reasoningContent(),result.protocolState());
    }
    private void assertNativeReplay(InvocationResult.Completed result) throws Exception {
        var state=result.protocolState().orElseThrow();
        assertEquals(NativeProtocol.MESSAGES,state.protocol());assertEquals("deepseek-messages-v1",state.dialectVersion());
        var base=request(List.of(new Message(Role.USER,"first",null,List.of()),replay(result),new Message(Role.TOOL,"actual", "a",List.of())));
        var wire=JSON.readTree(DeepSeekMessagesCodec.encode(base,false));
        var blocks=wire.at("/messages/1/content");assertEquals(state.items().size(),blocks.size());
        for(int i=0;i<blocks.size();i++) assertEquals(JSON.readTree(state.items().get(i).canonicalJson()),blocks.get(i));
        assertEquals("opaque-signature",blocks.get(0).path("signature").asText());
        assertFalse(result.toString().contains("opaque-signature"));
    }

    @Test void syncThinkingSignatureAndToolBlocksRoundTripExactly() throws Exception {
        response.set(COMPLETE.replace("{\"type\":\"text\",\"text\":\"done\"}",
                "{\"type\":\"thinking\",\"thinking\":\"private thoughts\",\"signature\":\"opaque-signature\"},{\"type\":\"text\",\"text\":\"done\"},{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"lookup\",\"input\":{\"x\":1}}")
                .replace("end_turn","tool_use"));
        var result=assertInstanceOf(InvocationResult.Completed.class,provider.generate("deepseek",thinking()));
        assertEquals("private thoughts",result.reasoningContent());assertEquals("done",result.content());
        assertEquals(3,result.protocolState().orElseThrow().items().size());assertNativeReplay(result);
    }

    @Test void rejectsUnbackedOrMismatchedThinkingHistoryBeforeHttp() throws Exception {
        var unbacked=request(List.of(new Message(Role.USER,"hi",null,List.of()),new Message(Role.ASSISTANT,"answer",null,List.of(),"private")));
        assertInstanceOf(InvocationResult.Rejected.class,provider.generate("deepseek",unbacked));
        var state=new NativeProtocolState(NativeProtocol.MESSAGES,"other-dialect","deepseek-flash",List.of(new TypedPayload(
                DeepSeekMessagesCodec.BLOCK_SCHEMA,"{\"type\":\"text\",\"text\":\"answer\"}")));
        var mismatched=request(List.of(new Message(Role.USER,"hi",null,List.of()),new Message(Role.ASSISTANT,
                List.of(new com.skillforge.runtime.api.media.ModelContent.Text("answer")),null,List.of(),null,Optional.of(state))));
        assertInstanceOf(InvocationResult.Rejected.class,provider.generate("deepseek",mismatched));
        assertEquals(0,requests.get());
    }

    @Test void cumulativeSystemProjectionMergesUpdatesAndPreservesCompleteToolGroups() throws Exception {
        var request = request(List.of(new Message(Role.SYSTEM, "core", null, List.of()),
                new Message(Role.SYSTEM, "role", null, List.of()), new Message(Role.USER, "first", null, List.of()),
                new Message(Role.ASSISTANT, (String) null, null, List.of(new ToolCall("a", "lookup", "{}"), new ToolCall("b", "lookup", "{}"))),
                new Message(Role.SYSTEM, "output", null, List.of()), new Message(Role.SYSTEM, "time", null, List.of()),
                new Message(Role.TOOL, "A", "a", List.of()), new Message(Role.TOOL, "B", "b", List.of())));
        var wire = JSON.readTree(DeepSeekMessagesCodec.encode(request, true));
        assertEquals("core\n\nrole", wire.path("system").asText());
        assertEquals(4, wire.path("messages").size());
        assertEquals(2, wire.at("/messages/1/content").size());
        assertEquals(2, wire.at("/messages/2/content").size());
        assertEquals("system", wire.at("/messages/3/role").asText());
        assertEquals("core\n\nrole\n\noutput\n\ntime", wire.at("/messages/3/content/0/text").asText());
        assertEquals("tool_result", wire.at("/messages/2/content/0/type").asText());
        var missing = request(request.messages().subList(0, request.messages().size() - 1));
        assertInstanceOf(InvocationResult.Rejected.class, provider.generate("deepseek", missing));
        assertEquals(0, requests.get());
    }

    @Test void rejectsUnsupportedContractsBeforeDispatch() {
        var base = defaults();
        for (var request : List.of(copy(base, new ResponseContract(new ResponseFormat.JsonObject(), EnforcementLevel.BEST_EFFORT, "test"), base.reasoning(), base.timeout(), base.cancelled(), 100_000),
                copy(base, base.response(), base.reasoning(), base.timeout(), base.cancelled(), 10))) {
            assertInstanceOf(InvocationResult.Rejected.class, provider.generate("deepseek", request));
        }
        assertEquals(0, requests.get());
    }

    @Test void parsesSyncToolCallsAndNoneChoice() throws Exception {
        response.set(COMPLETE.replace("{\"type\":\"text\",\"text\":\"done\"}", "{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"lookup\",\"input\":{\"x\":1}}")
                .replace("end_turn", "tool_use"));
        var result = assertInstanceOf(InvocationResult.Completed.class, provider.generate("deepseek", defaults()));
        assertEquals(List.of(new ToolCall("a", "lookup", "{\"x\":1}")), result.toolCalls());
        assertEquals("tool_calls", result.finishReasonCode());
        var base = defaults();
        var none = new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(), base.messages(), base.response(),
                base.generation(), base.reasoning(), base.tools(), ToolChoice.NONE, base.timeout(), base.cancelled(), base.beforeSend());
        assertEquals("none", JSON.readTree(DeepSeekMessagesCodec.encode(none, false)).at("/tool_choice/type").asText());
    }

    private static String event(String json) { return "data: " + json + "\n\n"; }
    private String streamBody() {
        return event("{\"type\":\"message_start\",\"message\":{\"type\":\"message\",\"role\":\"assistant\",\"model\":\"deepseek-flash\",\"content\":[],\"usage\":{\"input_tokens\":7,\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":0,\"output_tokens\":0}}}")
                + event("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + event("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}")
                + event("{\"type\":\"content_block_stop\",\"index\":0}")
                + event("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"lookup\",\"input\":{}}}")
                + event("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":1}\"}}")
                + event("{\"type\":\"content_block_stop\",\"index\":1}")
                + event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":9}}")
                + event("{\"type\":\"message_stop\"}");
    }
    private List<StreamEvent> collect(InvocationRequest request) throws Exception {
        var result = new CopyOnWriteArrayList<StreamEvent>(); var done = new CountDownLatch(1); var failure = new AtomicReference<Throwable>();
        provider.stream("deepseek", request).subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(StreamEvent event) { result.add(event); }
            public void onError(Throwable error) { failure.set(error); done.countDown(); }
            public void onComplete() { done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertNull(failure.get()); return List.copyOf(result);
    }

    private String thinkingStreamBody() {
        var body=streamBody().replace("\"index\":1","\"index\":2").replace("\"index\":0","\"index\":1");
        int position=body.indexOf("data: {\"type\":\"content_block_start\"");
        var thinking=event("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}")
                +event("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"private \"}}")
                +event("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"thoughts\"}}")
                +event("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"opaque-\"}}")
                +event("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"signature\"}}")
                +event("{\"type\":\"content_block_stop\",\"index\":0}");
        return body.substring(0,position)+thinking+body.substring(position);
    }

    @Test void streamsThinkingAndSignatureWithoutLeakingSignatureAndReplaysToolRound() throws Exception {
        response.set(thinkingStreamBody());var events=collect(thinking());
        assertEquals("private thoughts",events.stream().filter(StreamEvent.ReasoningDelta.class::isInstance)
                .map(StreamEvent.ReasoningDelta.class::cast).map(StreamEvent.ReasoningDelta::content).collect(java.util.stream.Collectors.joining()));
        assertTrue(events.stream().filter(StreamEvent.ContentDelta.class::isInstance).map(StreamEvent.ContentDelta.class::cast)
                .noneMatch(event->event.content().contains("opaque-")||event.content().contains("private")));
        var result=assertInstanceOf(StreamEvent.Completed.class,events.getLast()).result();
        assertEquals("hello",result.content());assertEquals("private thoughts",result.reasoningContent());
        assertEquals(107,result.usage().inputTokens().orElseThrow());assertEquals(9,result.usage().outputTokens().orElseThrow());
        assertEquals("tool_calls",result.finishReasonCode());assertNativeReplay(result);
    }

    @Test void streamedThinkingToolReplayAcceptsSpacedJsonAndRejectsChangedArgumentValue() throws Exception {
        response.set(thinkingStreamBody().replace("{\\\"x\\\":1}","{ \\\"x\\\": 1 }"));
        var completed=assertInstanceOf(StreamEvent.Completed.class,collect(thinking()).getLast()).result();
        assertEquals("{ \"x\": 1 }",completed.toolCalls().getFirst().argumentsJson());
        assertNativeReplay(completed);
        var changed=new Message(Role.ASSISTANT,List.of(new com.skillforge.runtime.api.media.ModelContent.Text(completed.content())),
                null,List.of(new ToolCall("a","lookup","{\"x\":2}")),completed.reasoningContent(),completed.protocolState());
        var request=request(List.of(new Message(Role.USER,"first",null,List.of()),changed,new Message(Role.TOOL,"actual","a",List.of())));
        assertEquals("model_messages_protocol_state_mismatch",assertInstanceOf(InvocationResult.Rejected.class,
                provider.generate("deepseek",request)).reasonCode());
        assertEquals(1,requests.get(),"派生参数真的变化仍在下一请求HTTP派发前拒绝");
    }

    @Test void missingSignatureAndUnsupportedRedactedThinkingStayUnknown() throws Exception {
        response.set(thinkingStreamBody().lines().filter(line->!line.contains("signature_delta")).collect(java.util.stream.Collectors.joining("\n"))+"\n");
        assertInstanceOf(StreamEvent.OutcomeUnknown.class,collect(thinking()).getLast());
        response.set(COMPLETE.replace("{\"type\":\"text\",\"text\":\"done\"}","{\"type\":\"redacted_thinking\",\"data\":\"opaque\"}"));
        assertInstanceOf(InvocationResult.OutcomeUnknown.class,provider.generate("deepseek",thinking()));
    }

    @Test void streamsTextToolsAndTerminalMergedUsage() throws Exception {
        response.set(streamBody()); var events = collect(defaults());
        assertTrue(events.stream().anyMatch(event -> event instanceof StreamEvent.ContentDelta text && text.content().equals("hello")));
        assertTrue(events.stream().anyMatch(event -> event instanceof StreamEvent.ToolCallDelta call && call.index() == 0 && call.argumentsDelta().equals("{\"x\":1}")));
        var finalEvent = assertInstanceOf(StreamEvent.Completed.class, events.getLast());
        assertEquals("hello", finalEvent.result().content());
        assertEquals(107, finalEvent.result().usage().inputTokens().orElseThrow());
        assertEquals(9, finalEvent.result().usage().outputTokens().orElseThrow());
        assertEquals("{\"x\":1}", finalEvent.result().toolCalls().getFirst().argumentsJson());
    }

    @Test void incompleteStreamAndOversizedResponseStayUnknownWithoutReplay() throws Exception {
        response.set(streamBody().replace(event("{\"type\":\"message_stop\"}"), ""));
        assertInstanceOf(StreamEvent.OutcomeUnknown.class, collect(defaults()).getLast());
        response.set(COMPLETE);
        assertInstanceOf(InvocationResult.OutcomeUnknown.class, runtime(30).generate("deepseek", defaults()));
        assertEquals(2, requests.get());
    }

    @Test void invalidToolJsonIsKnownNativeDecisionAndTruncatedStreamKeepsUsage() throws Exception {
        response.set(streamBody().replace("{\\\"x\\\":1}", "{\\\"x\\\":"));
        var completed = assertInstanceOf(StreamEvent.Completed.class, collect(defaults()).getLast());
        assertEquals("{\"x\":", completed.result().toolCalls().getFirst().argumentsJson());
        response.set(streamBody().replace(event("{\"type\":\"message_stop\"}"), ""));
        var events = collect(defaults());
        assertInstanceOf(StreamEvent.OutcomeUnknown.class, events.getLast());
        assertEquals(107, events.stream().filter(StreamEvent.UsageDelta.class::isInstance)
                .map(StreamEvent.UsageDelta.class::cast).toList().getLast().usage().inputTokens().orElseThrow());
    }

    @Test void missingUsageComponentsRemainUnknownAndInvalidUtf8IsRejected() throws Exception {
        var usage = DeepSeekMessagesCodec.usage(JSON.readTree("{\"input_tokens\":7,\"cache_read_input_tokens\":100,\"output_tokens\":2}"));
        assertTrue(usage.inputTokens().isEmpty()); assertEquals(100, usage.cachedInputTokens().orElseThrow());
        assertThrows(java.io.IOException.class, () -> DeepSeekMessagesCodec.parse(new byte[]{(byte) 0xc3, 0x28}));
        responseBytes.set(new byte[]{(byte) 0xc3, 0x28});
        assertInstanceOf(InvocationResult.OutcomeUnknown.class, provider.generate("deepseek", defaults()));
        assertInstanceOf(StreamEvent.OutcomeUnknown.class, collect(defaults()).getLast());
    }

    @Test void systemImagesAreRejectedBeforeMediaReadOrDispatch() {
        var session = new com.skillforge.runtime.api.SessionRef(new com.skillforge.runtime.api.TenantId("tenant"), "session");
        var media = new com.skillforge.runtime.api.media.MediaRef(session, "image", "0".repeat(64), "image/jpeg", 3, 1, 1);
        var reads = new AtomicInteger();
        var rejected = assertThrows(IllegalArgumentException.class, () -> {
            var request = request(List.of(new Message(Role.SYSTEM, List.of(new com.skillforge.runtime.api.media.ModelContent.Image(
                    media, com.skillforge.runtime.api.media.ImageDetail.AUTO)), null, List.of(), null),
                    new Message(Role.USER, "describe", null, List.of())));
            var guarded = withMedia(request, value -> { reads.incrementAndGet(); return new byte[3]; }, 100_000);
            provider.generate("deepseek", guarded);
        });
        assertEquals("model_provider_image_role_invalid", rejected.getMessage());
        assertEquals(0, reads.get());
        assertEquals(0, requests.get());
    }

    @Test void explicitFrozenCapabilityControlsSystemProjectionForAnyModel() throws Exception {
        var history = List.of(new Message(Role.SYSTEM, "core", null, List.of()), new Message(Role.USER, "hi", null, List.of()),
                new Message(Role.SYSTEM, "time", null, List.of()));
        for (String model : List.of("deepseek-flash","deepseek-v4-pro", "future-account-model")) {
            var base=request(history);
            for (var status:CapabilitySupportStatus.values()) {
                var capability=new PromptPrefixCapabilities(status,ToolUpdateMode.NONE,CapabilitySupportStatus.SUPPORTED);
                var projection=DeepSeekMessagesProjection.project(history,capability);
                var dynamic=new InvocationRequest(base.endpoint(),base.credential(),model,base.profile(),base.messages(),base.response(),
                        base.generation(),base.reasoning(),base.tools(),base.toolChoice(),base.timeout(),base.cancelled(), base.beforeSend()).withPrefixCapabilities(capability);
                var wire=JSON.readTree(DeepSeekMessagesCodec.encode(dynamic,false));
                assertEquals(model,wire.path("model").asText());
                if(status==CapabilitySupportStatus.SUPPORTED) {
                    assertEquals("core",projection.leadingSystem());assertEquals(2,projection.messages().size());
                    assertEquals("system",wire.at("/messages/1/role").asText());
                    assertEquals("core\n\ntime",wire.at("/messages/1/content/0/text").asText());
                } else {
                    assertEquals("core\n\ntime",projection.leadingSystem());assertEquals(1,projection.messages().size());
                    assertEquals("core\n\ntime",wire.path("system").asText());assertEquals(1,wire.path("messages").size());
                    assertEquals(Role.USER,projection.messages().getFirst().role());
                }
            }
        }
    }

    @Test void inHistoryCapabilityStillRebasesEarlyStopCorrectionWithoutInventingUser() throws Exception {
        var history = List.of(new Message(Role.SYSTEM, "core", null, List.of()), new Message(Role.USER, "finish task", null, List.of()),
                new Message(Role.ASSISTANT, (String) null, null, List.of(new ToolCall("prior-call", "lookup", "{}"))),
                new Message(Role.TOOL, "actual result", "prior-call", List.of()),
                new Message(Role.ASSISTANT, "premature final", null, List.of()),
                new Message(Role.SYSTEM, "output rejected: remaining work", null, List.of()), new Message(Role.SYSTEM, "time", null, List.of()));
        var projection = DeepSeekMessagesProjection.project(history, IN_HISTORY);
        assertEquals("core\n\noutput rejected: remaining work\n\ntime", projection.leadingSystem());
        assertEquals(history.subList(1, 5), projection.messages());
        var wire = JSON.readTree(DeepSeekMessagesCodec.encode(request(history), true));
        assertEquals(projection.leadingSystem(), wire.path("system").asText());
        assertEquals(4, wire.path("messages").size());
        assertEquals("user", wire.at("/messages/0/role").asText());
        assertEquals("assistant", wire.at("/messages/1/role").asText());
        assertEquals("prior-call", wire.at("/messages/1/content/0/id").asText());
        assertEquals("prior-call", wire.at("/messages/2/content/0/tool_use_id").asText());
        assertEquals("premature final", wire.at("/messages/3/content/0/text").asText());
    }

    @Test void strictGenerationPreferenceNeverWeakensToolSchemaOrResponseEnforcement() throws Exception {
        var base = defaults();
        var schema = new TypedPayload("application/json", "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"}},\"required\":[\"x\"],\"additionalProperties\":false}");
        for (boolean strict : List.of(true, false)) {
            var request = new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(), base.messages(), base.response(),
                    base.generation(), base.reasoning(), List.of(new ToolDefinition("lookup", "lookup", schema, strict, ModelToolKind.TOOL_CALL, true)),
                    ToolChoice.AUTO, base.timeout(), base.cancelled(), base.beforeSend());
            var tool = JSON.readTree(DeepSeekMessagesCodec.encode(request, false)).at("/tools/0");
            assertFalse(tool.has("strict")); assertEquals(JSON.readTree(schema.canonicalJson()), tool.get("input_schema"));
        }
        var response = new ResponseContract(new ResponseFormat.JsonSchema(schema), EnforcementLevel.STRICT, "test");
        assertInstanceOf(InvocationResult.Rejected.class, provider.generate("deepseek", copy(base, response, base.reasoning(), base.timeout(), base.cancelled(), 100_000)));
        var noLimit = new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(), base.messages(), base.response(),
                new GenerationOptions(OptionalInt.empty(), OptionalDouble.empty()), base.reasoning(), base.tools(), base.toolChoice(), base.timeout(), base.cancelled(), base.beforeSend());
        assertEquals("model_messages_output_limit_required", assertInstanceOf(InvocationResult.Rejected.class, provider.generate("deepseek", noLimit)).reasonCode());
        assertEquals(0, requests.get());
    }

    @Test void knownHttpRefusalAndServerFailureNeverRetry() {
        status.set(429); assertEquals("model_rate_limited", assertInstanceOf(InvocationResult.Rejected.class, provider.generate("deepseek", defaults())).reasonCode());
        status.set(503); assertInstanceOf(InvocationResult.OutcomeUnknown.class, provider.generate("deepseek", defaults()));
        assertEquals(2, requests.get());
    }

    @Test void cancellationAndDeadlineCloseAnInFlightExchange() throws Exception {
        pause.set(new CountDownLatch(1)); var cancel = new AtomicBoolean(); var base = defaults();
        var future = CompletableFuture.supplyAsync(() -> provider.generate("deepseek", copy(base, base.response(), base.reasoning(), Duration.ofSeconds(2), cancel::get, 100_000)));
        assertTrue(started.await(2, TimeUnit.SECONDS)); cancel.set(true);
        assertInstanceOf(InvocationResult.OutcomeUnknown.class, future.get(3, TimeUnit.SECONDS));
        assertInstanceOf(InvocationResult.OutcomeUnknown.class, provider.generate("deepseek", copy(base, base.response(), base.reasoning(), Duration.ofMillis(100), () -> false, 100_000)));
        assertEquals(2, requests.get());
    }

    @Test void catalogPublishesSupportedMessagesReasoningWithoutUpgradingJsonOrChatPrefix() {
        var catalog = ModelProviderCatalog.standard();
        var messages = catalog.resolve(new Selection("deepseek", "deepseek-api", "2026-09-13", "deepseek-flash",
                ModelProtocol.ANTHROPIC_MESSAGES, AuthenticationScheme.ANTHROPIC_API_KEY, null));
        assertEquals(SupportLevel.UNSUPPORTED, messages.capabilities().jsonObject());
        assertEquals(SupportLevel.SUPPORTED, messages.capabilities().reasoning());
        assertDoesNotThrow(() -> catalog.validateCapabilities(messages, Set.of(ResponseFormatKind.TEXT), false, true));
        assertEquals(CapabilitySupportStatus.UNVERIFIED, messages.prefixCapabilities().systemUpdatesInHistory());
        assertEquals(ToolUpdateMode.NONE, messages.prefixCapabilities().toolUpdates());
        var chat = catalog.resolve(new Selection("deepseek", "deepseek-api", "2026-09-13", "deepseek-flash",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, null));
        assertEquals(CapabilitySupportStatus.UNVERIFIED, chat.prefixCapabilities().systemUpdatesInHistory());
        assertThrows(ProviderProfileRejectedException.class, () -> catalog.validateCapabilities(messages, Set.of(ResponseFormatKind.JSON_OBJECT), false, false));
    }

    /** 正式端点经过 SDK 校验后，仅在测试传输中改道到本用例拥有的 loopback 服务。 */
    private record LocalTransport(ModelProviderTransport delegate, String endpoint) implements ModelProviderTransport {
        @Override public List<DiscoveredModel> discover(DiscoveryRequest request) {
            return delegate.discover(request);
        }
        @Override public InvocationResult generate(InvocationRequest request, ChatCompletionsPolicy policy) {
            return delegate.generate(local(request), policy);
        }
        @Override public Flow.Publisher<StreamEvent> stream(InvocationRequest request, ChatCompletionsPolicy policy) {
            return delegate.stream(local(request), policy);
        }
        private InvocationRequest local(InvocationRequest request) {
            assertEquals(PROVIDER_ENDPOINT, request.endpoint());
            return new InvocationRequest(endpoint, request.credential(), request.modelId(), request.profile(), request.messages(),
                    request.response(), request.generation(), request.reasoning(), request.tools(), request.toolChoice(),
                    request.timeout(), request.cancelled(), request.mediaReader(), request.maximumRequestBytes(),
                    request.promptCacheScope(), request.prefixCapabilities(), request.beforeSend());
        }
    }
}
