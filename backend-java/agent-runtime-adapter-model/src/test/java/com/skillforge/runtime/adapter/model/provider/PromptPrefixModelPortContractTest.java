package com.skillforge.runtime.adapter.model.provider;

import com.skillforge.runtime.adapter.model.openai.CustomOpenAiCompatibleModelProvider;
import com.skillforge.runtime.adapter.model.openai.chat.OpenAiCompatibleProviderTransport;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

/** 真 HTTP/SSE 验证保留声明不扩大工具权限；派发和绑定纯值夹具不替代Store/IAM验收。 */
final class PromptPrefixModelPortContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TenantId TENANT = new TenantId("tenant");
    private static final PromptPrefixCapabilities PREFIX = new PromptPrefixCapabilities(
            CapabilitySupportStatus.UNVERIFIED, ToolUpdateMode.NONE, CapabilitySupportStatus.SUPPORTED);
    private final com.skillforge.runtime.adapter.model.transport.SdkHttpResources sdkResources =
            new com.skillforge.runtime.adapter.model.transport.SdkHttpResources();
    private HttpServer server;
    private ExecutorService workers;
    private HttpClient http;
    private OpenAiModelPort.Binding binding;
    private ModelProviderRuntime providers;
    private final AtomicReference<String> reply = new AtomicReference<>();
    private final AtomicReference<JsonNode> sent = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    /** 原子分配 loopback 端口，服务启动完成后才构造精确绑定。 */
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            sent.set(JSON.readTree(exchange.getRequestBody()));
            byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        providers = new ModelProviderRuntime(List.of(new CustomOpenAiCompatibleModelProvider(
                new OpenAiCompatibleProviderTransport(http, Clock.systemUTC(), 100_000, sdkResources))));
        var target = new FrozenModelTarget(new ModelBindingRef(TENANT, "binding", 1), "deepseek-flash",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), true, false, new ResultRef("capability"), PREFIX),
                Duration.ofSeconds(5), new ResultRef("resolution"));
        binding = new OpenAiModelPort.Binding(target, new RequestedModelStrategy("chat", "v1"),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "cipher",
                new OpenAiModelPort.ProviderProfile("custom", "custom-openai-compatible", "custom-v1",
                        ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                        ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY, "openai-compatible-chat-v1"));
    }

    /** 失败路径也停服务并等待拥有的工作线程静止，避免迟到回调影响下个用例。 */
    @AfterEach void stop() throws Exception {
        sdkResources.close();
        if (server != null) server.stop(0);
        if (workers != null) { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
        if (http != null) { http.close(); assertTrue(http.awaitTermination(Duration.ofSeconds(5))); }
    }

    /** 普通文本调用保留原声明并发送 none，正文仍按文本响应契约完成。 */
    @Test void retainsToolsWithNoneForCompleteHttpAndSseText() throws Exception {
        for (boolean stream : List.of(false, true)) {
            reply.set(response(stream, false, 0, "stop"));
            var call = call(stream, new ResponseFormat.Text());
            if (stream) {
                var events = stream(call);
                assertEquals("最终正文", assertInstanceOf(ModelStreamEvent.ContentDelta.class, events.getFirst()).content());
                assertInstanceOf(ModelStreamEvent.Completed.class, events.getLast());
                assertFalse(events.stream().anyMatch(ModelStreamEvent.ToolCallDelta.class::isInstance));
            } else assertInstanceOf(ModelResult.Completed.class, port().generate(call));
            assertEquals("none", sent.get().path("tool_choice").asText());
            assertEquals("frozen_wire", sent.get().path("tools").get(0).path("function").path("name").asText());
        }
        assertEquals(2, requests.get());
    }

    /** NONE 不发布可执行增量；原生完整事实由Session判定，普通文本仍在适配边界拒绝工具。 */
    @Test void disabledCallsNeverExposeExecutableDeltasAndPreserveNativeFacts() throws Exception {
        for (boolean stream : List.of(false, true)) for (int count : List.of(1, 2))
            for (String finish : List.of("stop", "tool_calls"))
                for (ResponseFormat format : List.of(new ResponseFormat.NativeConversation(), new ResponseFormat.Text())) {
                    reply.set(response(stream, true, count, finish));
                    var call = call(stream, format);
                    if (stream) {
                        var events = stream(call);
                        assertFalse(events.stream().anyMatch(ModelStreamEvent.ToolCallDelta.class::isInstance));
                        if (format instanceof ResponseFormat.NativeConversation) {
                            var result = assertInstanceOf(ModelStreamEvent.Completed.class, events.getLast()).result();
                            assertEquals(count, result.assistantTurn().orElseThrow().toolCalls().size());
                            assertEquals(finish, result.finishReasonCode());
                        } else assertInstanceOf(ModelStreamEvent.Failed.class, events.getLast());
                    } else if (format instanceof ResponseFormat.NativeConversation) {
                        var result = assertInstanceOf(ModelResult.Completed.class, port().generate(call));
                        assertEquals(count, result.assistantTurn().orElseThrow().toolCalls().size());
                        assertEquals(finish, result.finishReasonCode());
                    } else assertInstanceOf(ModelResult.Failed.class, port().generate(call));
                }
    }

    /** 截断、拒绝及空工具终止均保存真实原因，完整接收不等于Runtime采纳。 */
    @Test void retainedFinalHttpAndSsePreserveIncompleteFinishes() throws Exception {
        for (boolean streaming : List.of(false, true))
            for (String finish : List.of("length", "tool_calls", "content_filter")) {
                reply.set(response(streaming, false, 0, finish));
                assertFinalFacts(streaming, finish, "最终正文");
            }
    }

    /** 空串和纯空白仍是确定响应，Session负责拒绝空答案。 */
    @Test void retainedFinalHttpAndSsePreserveEmptyContent() throws Exception {
        for (boolean streaming : List.of(false, true)) for (String content : List.of("", " \t")) {
            reply.set(response(streaming, false, 0, "stop", content));
            assertFinalFacts(streaming, "stop", content);
        }
    }

    private void assertFinalFacts(boolean streaming, String finish, String content) throws Exception {
        var call = call(streaming, new ResponseFormat.NativeConversation());
        ModelResult.Completed result;
        if (streaming) {
            var events = stream(call);
            result = assertInstanceOf(ModelStreamEvent.Completed.class, events.getLast()).result();
            assertFalse(events.stream().anyMatch(ModelStreamEvent.ToolCallDelta.class::isInstance));
            assertFalse(events.stream().anyMatch(ModelStreamEvent.ContentDelta.class::isInstance));
        } else result = assertInstanceOf(ModelResult.Completed.class, port().generate(call));
        assertEquals(finish, result.finishReasonCode());
        assertEquals(content, result.assistantTurn().orElseThrow().content());
        assertEquals(com.skillforge.runtime.api.port.ModelPort.TEXT_OUTPUT_SCHEMA, result.output().schemaId());
    }

    /** 冻结未知能力和错误服务在发送前失败；显式冻结的能力适用于用户选择的任意模型。 */
    @Test void requiresFrozenCapabilitiesAndValidServiceWithoutModelWhitelist() throws Exception {
        reply.set(response(false, false, 0, "stop"));
        var original = binding;
        var target = original.target();
        var unknown = new FrozenModelTarget(target.binding(), target.effectiveModelId(),
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), true, false, new ResultRef("unknown")),
                target.timeout(), target.resolutionRef());
        binding = new OpenAiModelPort.Binding(unknown, original.strategy(), original.endpoint(), "cipher", original.providerProfile());
        assertInstanceOf(ModelResult.Failed.class, port().generate(call(false, new ResponseFormat.NativeConversation())));
        var unknownModel = new FrozenModelTarget(target.binding(), "unverified-model", target.capabilities(), target.timeout(), target.resolutionRef());
        binding = new OpenAiModelPort.Binding(unknownModel, original.strategy(), original.endpoint(), "cipher", original.providerProfile());
        reply.set(response(false, false, 0, "stop").replace("deepseek-flash", "unverified-model"));
        assertInstanceOf(ModelResult.Completed.class, port().generate(call(false, new ResponseFormat.NativeConversation())));
        binding = new OpenAiModelPort.Binding(target, original.strategy(), original.endpoint(), "cipher",
                new OpenAiModelPort.ProviderProfile("custom", "unlisted-service", "custom-v1",
                        ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                        ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY, "openai-compatible-chat-v1"));
        assertInstanceOf(ModelResult.Failed.class, port().generate(call(false, new ResponseFormat.NativeConversation())));
        assertEquals(1, requests.get());
    }

    /** 通过正式绑定读取注入入口装配，输出校验器允许合法响应以暴露适配器自身的拒绝责任。 */
    private OpenAiModelPort port() {
        return new OpenAiModelPort(ref -> Optional.of(binding), ignored -> "key".getBytes(StandardCharsets.UTF_8),
                ignored -> true, (call, output) -> true, http, Clock.systemUTC(), 100_000, providers);
    }

    /** 明确本次派发身份，测试只改变工具选择和能力，仍经过实际ModelPort前置校验。 */
    private ModelCall call(boolean stream, ResponseFormat format) {
        var input = new InputId("input");
        var owner = SessionOwner.user(new SessionRef(TENANT, "session"), new AgentId("agent"), new EndUserId(1));
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(TENANT, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        var action = new ActionRef(TENANT, "action"); var now = Instant.now(); var ref = new ResultRef("invocation");
        var dispatch = new ProviderDispatch(run.runRef(), action, action.actionId(), "attempt",
                stream ? PortalCallKind.MODEL_STREAM : PortalCallKind.MODEL_GENERATE,
                ref, new ResultRef("capability"), "fingerprint", 1);
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, owner.requireUser()), owner,
                Set.of("runtime:invoke"), 1, new ResultRef("verified"));
        return new ModelCall(ProviderCallContext.dispatched(new PortCallContext(run, identity,
                new InvocationControl(now.plusSeconds(10), () -> false)), dispatch), binding.strategy(), binding.target(), ref,
                List.of(new ModelMessage("system", ModelRole.SYSTEM, "规则"), new ModelMessage("user", ModelRole.USER, "问题")),
                List.of(new ModelTool("frozen_wire", "opaque-handle", "冻结声明", new TypedPayload("schema.v1", "{\"type\":\"object\"}"), false, com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL, false)),
                ToolChoice.NONE, new ResponseContract(format, EnforcementLevel.STRICT, "supported"),
                new GenerationOptions(OptionalInt.of(100), OptionalDouble.empty()), ReasoningPolicy.DISABLED);
    }

    /** 使用规范完整消息/SSE，而不是 Mock终态，覆盖供应商解析与 ModelPort 治理两个边界。 */
    private static String response(boolean stream, boolean withCalls, int count, String finish) throws Exception {
        return response(stream, withCalls, count, finish, "最终正文");
    }

    /** 正文独立于终止原因，以真实 wire 覆盖无正文成功响应。 */
    private static String response(boolean stream, boolean withCalls, int count, String finish, String content) throws Exception {
        var message = JSON.createObjectNode().put("content", content);
        if (withCalls) for (int i = 0; i < count; i++) {
            var calls = message.withArray("tool_calls");
            calls.addObject().put("index", i).put("id", "call-" + i).put("type", "function")
                    .putObject("function").put("name", "frozen_wire").put("arguments", "{}");
        }
        var root = JSON.createObjectNode().put("model", "deepseek-flash");
        var choice = root.putArray("choices").addObject();
        choice.set(stream ? "delta" : "message", message);
        if (!stream) { choice.put("finish_reason", finish); return JSON.writeValueAsString(root); }
        return "data: " + JSON.writeValueAsString(root) + "\n\n"
                + "data: {\"model\":\"deepseek-flash\",\"choices\":[{\"delta\":{},\"finish_reason\":\"" + finish + "\"}]}\n\n"
                + "data: [DONE]\n\n";
    }

    /** completion latch 建立跨线程可见性，任何 onError 都使断言失败。 */
    private List<ModelStreamEvent> stream(ModelCall call) throws Exception {
        var events = new ArrayList<ModelStreamEvent>();
        var done = new CountDownLatch(1); var error = new AtomicReference<Throwable>();
        port().stream(call).subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ModelStreamEvent event) { events.add(event); }
            public void onError(Throwable failure) { error.set(failure); done.countDown(); }
            public void onComplete() { done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertNull(error.get());
        return List.copyOf(events);
    }
}
