package com.skillforge.runtime.adapter.model.domestic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.minimax.MiniMaxModelProvider;
import com.skillforge.runtime.adapter.model.moonshot.MoonshotModelProvider;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient.DiscoveredModel;
import com.skillforge.runtime.adapter.model.openai.chat.OpenAiCompatibleProviderTransport;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ChatCompletionsPolicy;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.DiscoveryRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Message;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Role;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.StreamEvent;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolChoice;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolDefinition;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelKind;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ResolvedDiscovery;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceRegion;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.SupportLevel;
import com.skillforge.runtime.adapter.model.provider.ReasoningOptions;
import com.skillforge.runtime.adapter.model.stepfun.StepFunModelProvider;
import com.skillforge.runtime.adapter.model.tencent.TencentTokenHubModelProvider;
import com.skillforge.runtime.adapter.model.xiaomi.XiaomiModelProvider;
import com.skillforge.runtime.adapter.model.zhipu.ZhipuModelProvider;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ModelToolKind;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 固定服务先经供应商校验，再通过测试传输改道本地 HTTP；无收费调用或区域回退。 */
class DomesticModelProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypedPayload SCHEMA = new TypedPayload("application/json",
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"],\"additionalProperties\":false}");
    private static final ToolDefinition TOOL = new ToolDefinition("weather", "天气", SCHEMA, true, ModelToolKind.TOOL_CALL, false);
    private static final List<Message> HISTORY = List.of(new Message(Role.USER, "问题", null, List.of()),
            new Message(Role.ASSISTANT, "历史回答", null, List.of(), "原始思考"),
            new Message(Role.USER, "继续", null, List.of()));
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicInteger exchanges = new AtomicInteger();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final com.skillforge.runtime.adapter.model.transport.SdkHttpResources sdkResources =
            new com.skillforge.runtime.adapter.model.transport.SdkHttpResources();
    private HttpServer server;
    private HttpClient http;
    private LocalTransport local;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::chat);
        server.createContext("/v1/models", exchange -> {
            exchanges.incrementAndGet();
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "{\"data\":[{\"id\":\"account-model\"}]}");
        });
        server.start();
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        local = new LocalTransport(new OpenAiCompatibleProviderTransport(http, Clock.systemUTC(), 100_000, sdkResources),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    @AfterEach void stop() throws InterruptedException {
        sdkResources.close();
        if (server != null) server.stop(0);
        if (http != null) {
            http.shutdownNow();
            assertTrue(http.awaitTermination(Duration.ofSeconds(5)), "HTTP 客户端应完成清理");
        }
    }

    @Test void providersUseOnlyVerifiedMainlandRootsAndForwardCompletionStreamAndUsage() throws Exception {
        for (var candidate : candidates()) {
            var provider = candidate.provider();
            var service = provider.preset().services().getFirst();
            assertEquals(candidate.endpoint(), service.endpoint());
            assertEquals(ServiceRegion.CHINA_MAINLAND, service.region());
            assertEquals(Set.of(ModelKind.LLM), service.modelKinds());
            var call = request(provider, candidate.modelId(), ReasoningPolicy.DEFAULT);
            var result = assertInstanceOf(InvocationResult.Completed.class, provider.generate(call));
            assertEquals("已完成", result.content());
            assertEquals("新的思考", result.reasoningContent());
            assertEquals(17, result.usage().inputTokens().orElseThrow());
            assertEquals(3, result.usage().outputTokens().orElseThrow());
            assertEquals(candidate.endpoint(), local.original.get().endpoint());
            assertEquals("Bearer test-credential", lastAuthorization.get());
            assertEquals("原始思考", lastBody.get().at("/messages/1/reasoning_content").asText());
            assertEquals(HISTORY, call.messages());
            var probe = stream(provider, call);
            assertTrue(probe.events.stream().anyMatch(StreamEvent.ReasoningDelta.class::isInstance));
            var terminal = assertInstanceOf(StreamEvent.Completed.class, probe.events.getLast());
            assertEquals(result.usage(), terminal.result().usage());
            assertEquals(result.content(), terminal.result().content());
            assertFalse(call.toString().contains("test-credential"));
        }
    }

    @Test void discoveryUsesOwnServiceAndCredentialWithoutInventingUnsupportedListApis() {
        for (var candidate : candidates()) {
            var provider = candidate.provider();
            var service = provider.preset().services().getFirst();
            if (service.modelDiscovery() == null) {
                assertThrows(ModelProvider.ProviderOperationException.class,
                        () -> provider.discover(new DiscoveryRequest(discovery(new MoonshotModelProvider(local)),
                                "test-credential", Duration.ofSeconds(3))));
                continue;
            }
            assertEquals(List.of("account-model"), provider.discover(new DiscoveryRequest(discovery(provider),
                    "test-credential", Duration.ofSeconds(3))).stream().map(DiscoveredModel::modelId).toList());
            assertEquals(service.endpoint() + "/models", local.originalDiscovery.get().target().modelsEndpoint());
            assertEquals("Bearer test-credential", lastAuthorization.get());
            int before = exchanges.get();
            var target = discovery(provider);
            var foreign = new ResolvedDiscovery(target.providerId(), target.serviceId(), target.profileVersion(),
                    target.authenticationScheme(), "https://overseas.example/v1", "https://overseas.example/v1/models",
                    target.dialectVersion(), target.verificationStatus());
            assertThrows(ModelProvider.ProviderOperationException.class,
                    () -> provider.discover(new DiscoveryRequest(foreign, "test-credential", Duration.ofSeconds(3))));
            assertEquals(before, exchanges.get());
        }
    }

    @Test void kimiK3SendsExactEffortWithoutThinkingToggleAndK26PreservesFullHistory() {
        var provider = new MoonshotModelProvider(local);
        completed(provider, "kimi-k3", ReasoningPolicy.MAX);
        assertEquals("max", lastBody.get().path("reasoning_effort").asText());
        assertFalse(lastBody.get().has("thinking"));
        assertFalse(lastBody.get().has("temperature"));
        rejected(provider, "kimi-k3", ReasoningPolicy.DISABLED);
        rejected(provider, "kimi-k3", ReasoningPolicy.MEDIUM);
        completed(provider, "kimi-k2.6", ReasoningPolicy.ENABLED);
        assertEquals("enabled", lastBody.get().at("/thinking/type").asText());
        assertEquals("all", lastBody.get().at("/thinking/keep").asText());
        completed(provider, "kimi-k2.6", ReasoningPolicy.DISABLED);
        assertEquals("disabled", lastBody.get().at("/thinking/type").asText());
        assertFalse(lastBody.get().path("thinking").has("keep"));
        completed(provider, "kimi-k2.7-code", ReasoningPolicy.ENABLED);
        assertFalse(lastBody.get().has("thinking"));
        rejected(provider, "kimi-k2.7-code", ReasoningPolicy.DISABLED);
        for (String model : List.of("kimi-k2.6", "kimi-k2.7-code")) {
            int before = exchanges.get();
            assertEquals("model_tool_choice_unsupported", assertInstanceOf(InvocationResult.Rejected.class,
                    provider.generate(withTools(request(provider, model, ReasoningPolicy.DEFAULT), ToolChoice.REQUIRED))).reasonCode());
            assertEquals(before, exchanges.get());
        }
        assertInstanceOf(InvocationResult.Completed.class, provider.generate(withTools(
                request(provider, "kimi-k3", ReasoningPolicy.DEFAULT), ToolChoice.REQUIRED)));
        assertEquals("required", lastBody.get().path("tool_choice").asText());
        assertFalse(lastBody.get().at("/tools/0/function").has("strict"));
        assertTrue(lastBody.get().at("/tools/0/function/parameters/required").isArray());
    }

    @Test void miniMaxUsesAdaptiveSplitAndCompletionBudgetWithoutPretendingM2CanDisableThinking() {
        var provider = new MiniMaxModelProvider(local);
        completed(provider, "MiniMax-M3.1-Flash", ReasoningPolicy.XHIGH);
        assertEquals("adaptive", lastBody.get().at("/thinking/type").asText());
        assertEquals("xhigh", lastBody.get().path("reasoning_effort").asText());
        assertTrue(lastBody.get().path("reasoning_split").asBoolean());
        assertEquals(64, lastBody.get().path("max_completion_tokens").asInt());
        assertFalse(lastBody.get().has("max_tokens"));
        assertFalse(lastBody.get().has("temperature"));
        rejected(provider, "MiniMax-M3.1-Flash", ReasoningPolicy.DISABLED);
        rejected(provider, "MiniMax-M2.7", ReasoningPolicy.DISABLED);
        completed(provider, "MiniMax-M3", ReasoningPolicy.DISABLED);
        assertEquals("disabled", lastBody.get().at("/thinking/type").asText());
    }

    @Test void zhipuPreservesThinkingAndDoesNotClampUnsupportedEffort() {
        var provider = new ZhipuModelProvider(local);
        completed(provider, "glm-5.3", ReasoningPolicy.MAX);
        assertEquals("enabled", lastBody.get().at("/thinking/type").asText());
        assertFalse(lastBody.get().at("/thinking/clear_thinking").asBoolean(true));
        assertEquals("max", lastBody.get().path("reasoning_effort").asText());
        rejected(provider, "glm-5.3", ReasoningPolicy.DISABLED);
        rejected(provider, "glm-5.3", ReasoningPolicy.MEDIUM);
        completed(provider, "glm-5.2", ReasoningPolicy.DISABLED);
        assertEquals("disabled", lastBody.get().at("/thinking/type").asText());
        assertFalse(lastBody.get().path("thinking").has("clear_thinking"));
        completed(provider, "glm-5.2", ReasoningPolicy.HIGH);
        assertEquals("high", lastBody.get().path("reasoning_effort").asText());
        rejected(provider, "glm-5.2", ReasoningPolicy.LOW);
    }

    @Test void stepfunEffortAndStrictSchemaFollowExactModelSupport() {
        var provider = new StepFunModelProvider(local);
        completed(provider, "step-3.7-flash", ReasoningPolicy.MEDIUM);
        assertEquals("medium", lastBody.get().path("reasoning_effort").asText());
        completed(provider, "step-3.5-flash-2603", ReasoningPolicy.LOW);
        assertEquals("low", lastBody.get().path("reasoning_effort").asText());
        rejected(provider, "step-3.5-flash-2603", ReasoningPolicy.MEDIUM);
        rejected(provider, "step-3.7-flash", ReasoningPolicy.DISABLED);
        var base = request(provider, "step-5-preview", ReasoningPolicy.DEFAULT);
        var schemaCall = withResponse(base, new ResponseFormat.JsonSchema(SCHEMA));
        assertInstanceOf(InvocationResult.Completed.class, provider.generate(schemaCall));
        assertTrue(lastBody.get().at("/response_format/json_schema/strict").asBoolean());
        assertEquals("json_schema", lastBody.get().at("/response_format/type").asText());
        var unknown = request(provider, "account-private-model", ReasoningPolicy.DEFAULT);
        assertEquals("model_response_format_unsupported",
                assertInstanceOf(InvocationResult.Rejected.class, provider.generate(withResponse(unknown,
                        new ResponseFormat.JsonSchema(SCHEMA)))).reasonCode());
    }

    @Test void tokenHubRejectsHy3LowWithToolsAndDoesNotBorrowOemModelCapabilities() {
        var provider = new TencentTokenHubModelProvider(local);
        completed(provider, "hy3", ReasoningPolicy.LOW);
        assertEquals("low", lastBody.get().path("reasoning_effort").asText());
        int before = exchanges.get();
        var unsupported = withTools(request(provider, "hy3", ReasoningPolicy.LOW), ToolChoice.AUTO);
        assertEquals("model_reasoning_policy_unsupported_with_tools",
                assertInstanceOf(InvocationResult.Rejected.class, provider.generate(unsupported)).reasonCode());
        assertEquals(before, exchanges.get());
        rejected(provider, "hy4-preview", ReasoningPolicy.LOW);
        rejected(provider, "kimi-k3", ReasoningPolicy.MAX);
        rejected(provider, "vendor-private-model", ReasoningPolicy.DISABLED);
        completed(provider, "hy4-preview", ReasoningPolicy.DISABLED);
        assertEquals("disabled", lastBody.get().at("/thinking/type").asText());
    }

    @Test void xiaomiChinaPlanKeepsThinkingTemperatureAndToolChoiceSemantics() {
        var provider = new XiaomiModelProvider(local);
        completed(provider, "mimo-v2.6-pro", ReasoningPolicy.ENABLED);
        assertFalse(lastBody.get().has("temperature"));
        assertEquals(64, lastBody.get().path("max_completion_tokens").asInt());
        completed(provider, "mimo-v2.6-pro", ReasoningPolicy.DISABLED);
        assertEquals(0.7, lastBody.get().path("temperature").asDouble());
        assertEquals("disabled", lastBody.get().at("/thinking/type").asText());
        var base = request(provider, "mimo-v2.6-pro", ReasoningPolicy.DEFAULT);
        assertInstanceOf(InvocationResult.Completed.class, provider.generate(withTools(base, ToolChoice.AUTO)));
        assertTrue(lastBody.get().at("/tools/0/function/strict").asBoolean());
        for (var choice : List.of(ToolChoice.REQUIRED, ToolChoice.NONE)) {
            int before = exchanges.get();
            assertEquals("model_tool_choice_unsupported", assertInstanceOf(InvocationResult.Rejected.class,
                    provider.generate(withTools(base, choice))).reasonCode());
            assertEquals(before, exchanges.get());
        }
    }

    @Test void unknownModelsHaveOnlyDefaultReasoningAndNoGuessedImageAccounting() {
        for (var candidate : candidates()) {
            var provider = candidate.provider();
            var service = provider.preset().services().getFirst();
            var protocol = service.protocols().getFirst();
            var unknown = provider.reasoningOptions(service, protocol, "account-private-model");
            assertEquals(List.of(ReasoningPolicy.DEFAULT), unknown.options());
            assertEquals(ReasoningOptions.Source.UNVERIFIED, unknown.source());
            assertEquals(SupportLevel.UNVERIFIED, provider.imageSupport(service, protocol, "account-private-model").userImages());
            assertEquals(SupportLevel.UNVERIFIED, provider.imageSupport(service, protocol, "account-private-model").toolImages());
            assertNull(provider.imageSupport(service, protocol, candidate.modelId()).defaults());
            completed(provider, "account-private-model", ReasoningPolicy.DEFAULT);
            rejected(provider, "account-private-model", ReasoningPolicy.DISABLED);
        }
    }

    @Test void documentedImageModelsAreDeclaredWithoutInventingToolImageOrTokenDefaults() {
        var visionCandidates = List.of(new Candidate(new MoonshotModelProvider(local), "kimi-k3", ""),
                new Candidate(new MiniMaxModelProvider(local), "MiniMax-M3.1-Flash", ""),
                new Candidate(new ZhipuModelProvider(local), "glm-5.3-flash", ""),
                new Candidate(new StepFunModelProvider(local), "step-3.7-flash", ""),
                new Candidate(new XiaomiModelProvider(local), "mimo-v2.6-pro", ""));
        for (var candidate : visionCandidates) {
            var service = candidate.provider().preset().services().getFirst();
            var images = candidate.provider().imageSupport(service, service.protocols().getFirst(), candidate.modelId());
            assertEquals(SupportLevel.SUPPORTED, images.userImages());
            assertEquals(SupportLevel.UNVERIFIED, images.toolImages());
            assertNull(images.defaults());
        }
        var tencent = new TencentTokenHubModelProvider(local);
        var service = tencent.preset().services().getFirst();
        assertEquals(SupportLevel.UNVERIFIED, tencent.imageSupport(service, service.protocols().getFirst(), "hy3").userImages());
    }

    @Test void invalidProfilesAreRejectedBeforeDispatchAndRejectedStreamsRespectDemandAndCancel() {
        var provider = new MoonshotModelProvider(local);
        var base = request(provider, "kimi-k3", ReasoningPolicy.DEFAULT);
        var foreign = withEndpoint(base, "https://api.moonshot.ai/v1");
        assertEquals("model_provider_profile_invalid", assertInstanceOf(InvocationResult.Rejected.class,
                provider.generate(foreign)).reasonCode());
        var probe = new Probe();
        provider.stream(foreign).subscribe(probe);
        assertTrue(probe.events.isEmpty());
        probe.subscription.request(1);
        assertEquals("model_provider_profile_invalid", assertInstanceOf(StreamEvent.Rejected.class,
                probe.events.getFirst()).reasonCode());
        assertEquals(0, exchanges.get());
        var cancelled = new Probe();
        provider.stream(foreign).subscribe(cancelled);
        cancelled.subscription.cancel();
        cancelled.subscription.request(1);
        assertTrue(cancelled.events.isEmpty());
        var invalidDemand = new Probe();
        provider.stream(foreign).subscribe(invalidDemand);
        invalidDemand.subscription.request(0);
        assertInstanceOf(IllegalArgumentException.class, invalidDemand.failure);
    }

    @Test void uncertainSupplierResponseIsNotRetriedOrConvertedIntoSuccess() throws Exception {
        responseStatus.set(503);
        var provider = new MiniMaxModelProvider(local);
        var call = request(provider, "MiniMax-M3.1-Flash", ReasoningPolicy.DEFAULT);
        assertInstanceOf(InvocationResult.OutcomeUnknown.class, provider.generate(call));
        assertEquals(1, exchanges.get());
        assertInstanceOf(StreamEvent.OutcomeUnknown.class, stream(provider, call).events.getLast());
        assertEquals(2, exchanges.get());
    }

    private List<Candidate> candidates() {
        return List.of(new Candidate(new MoonshotModelProvider(local), "kimi-k3", "https://api.moonshot.cn/v1"),
                new Candidate(new MiniMaxModelProvider(local), "MiniMax-M3.1-Flash", "https://api.minimax.cn/v1"),
                new Candidate(new ZhipuModelProvider(local), "glm-5.3", "https://open.bigmodel.cn/api/paas/v4"),
                new Candidate(new StepFunModelProvider(local), "step-3.7-flash", "https://api.stepfun.com/v1"),
                new Candidate(new TencentTokenHubModelProvider(local), "hy3", "https://tokenhub.tencentmaas.com/v1"),
                new Candidate(new XiaomiModelProvider(local), "mimo-v2.6-pro", "https://token-plan-cn.xiaomimimo.com/v1"));
    }

    private void completed(ModelProvider provider, String modelId, ReasoningPolicy reasoning) {
        assertInstanceOf(InvocationResult.Completed.class, provider.generate(request(provider, modelId, reasoning)));
    }

    private void rejected(ModelProvider provider, String modelId, ReasoningPolicy reasoning) {
        int before = exchanges.get();
        assertEquals("model_reasoning_policy_unsupported", assertInstanceOf(InvocationResult.Rejected.class,
                provider.generate(request(provider, modelId, reasoning))).reasonCode());
        assertEquals(before, exchanges.get());
    }

    private InvocationRequest request(ModelProvider provider, String modelId, ReasoningPolicy reasoning) {
        var service = provider.preset().services().getFirst();
        var protocol = service.protocols().getFirst();
        return new InvocationRequest(service.endpoint(), "test-credential", modelId,
                new InvocationProfile(service.serviceId(), service.profileVersion(), protocol.protocol(),
                        AuthenticationScheme.BEARER_API_KEY, protocol.dialectVersion()), HISTORY,
                new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "test"),
                new GenerationOptions(OptionalInt.of(64), OptionalDouble.of(0.7)), reasoning, List.of(), ToolChoice.NONE,
                Duration.ofSeconds(3), () -> false, () -> { });
    }

    private static InvocationRequest withResponse(InvocationRequest call, ResponseFormat format) {
        return new InvocationRequest(call.endpoint(), call.credential(), call.modelId(), call.profile(), call.messages(),
                new ResponseContract(format, EnforcementLevel.STRICT, "test"), call.generation(), call.reasoning(),
                call.tools(), call.toolChoice(), call.timeout(), call.cancelled(), call.beforeSend());
    }

    private static InvocationRequest withTools(InvocationRequest call, ToolChoice choice) {
        return new InvocationRequest(call.endpoint(), call.credential(), call.modelId(), call.profile(), call.messages(),
                call.response(), call.generation(), call.reasoning(), List.of(TOOL), choice, call.timeout(), call.cancelled(), call.beforeSend());
    }

    private static InvocationRequest withEndpoint(InvocationRequest call, String endpoint) {
        return new InvocationRequest(endpoint, call.credential(), call.modelId(), call.profile(), call.messages(),
                call.response(), call.generation(), call.reasoning(), call.tools(), call.toolChoice(), call.timeout(), call.cancelled(), () -> { });
    }

    private static ResolvedDiscovery discovery(ModelProvider provider) {
        var service = provider.preset().services().getFirst();
        var definition = service.modelDiscovery();
        return new ResolvedDiscovery(provider.preset().providerId(), service.serviceId(), service.profileVersion(),
                AuthenticationScheme.BEARER_API_KEY, service.endpoint(), service.endpoint() + definition.path(),
                definition.dialectVersion(), definition.verificationStatus());
    }

    private Probe stream(ModelProvider provider, InvocationRequest call) throws Exception {
        var probe = new Probe();
        provider.stream(call).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.done.await(5, TimeUnit.SECONDS), "流应在超时前结束");
        assertNull(probe.failure);
        if (probe.reader != null && probe.reader != Thread.currentThread()) {
            probe.reader.join(Duration.ofSeconds(2));
            assertFalse(probe.reader.isAlive(), "流读取线程应已结束");
        }
        return probe;
    }

    private void chat(HttpExchange exchange) throws IOException {
        exchanges.incrementAndGet();
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        var body = JSON.readTree(exchange.getRequestBody().readAllBytes());
        lastBody.set(body);
        String model = JSON.writeValueAsString(body.path("model").asText());
        if (body.path("stream").asBoolean()) {
            respond(exchange, "data: {\"model\":" + model + ",\"choices\":[{\"delta\":{\"content\":\"已完成\",\"reasoning_content\":\"新的思考\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":17,\"completion_tokens\":3}}\n\n"
                    + "data: [DONE]\n\n");
        } else respond(exchange, "{\"model\":" + model + ",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"已完成\",\"reasoning_content\":\"新的思考\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":17,\"completion_tokens\":3}}");
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
        finally { exchange.close(); }
    }

    private record Candidate(ModelProvider provider, String modelId, String endpoint) { }

    private static final class Probe implements Flow.Subscriber<StreamEvent> {
        private final List<StreamEvent> events = new ArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private Flow.Subscription subscription;
        private Throwable failure;
        private Thread reader;
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; }
        @Override public void onNext(StreamEvent event) { reader = Thread.currentThread(); events.add(event); }
        @Override public void onError(Throwable value) { failure = value; done.countDown(); }
        @Override public void onComplete() { done.countDown(); }
    }

    /** 仅测试运输层改道，生产 Provider 仍先验证其正式固定服务、配置版本和国内入口。 */
    private static final class LocalTransport implements ModelProviderTransport {
        private final ModelProviderTransport delegate;
        private final String endpoint;
        private final AtomicReference<InvocationRequest> original = new AtomicReference<>();
        private final AtomicReference<DiscoveryRequest> originalDiscovery = new AtomicReference<>();
        private LocalTransport(ModelProviderTransport delegate, String endpoint) { this.delegate = delegate; this.endpoint = endpoint; }
        @Override public List<DiscoveredModel> discover(DiscoveryRequest request) {
            originalDiscovery.set(request);
            var target = request.target();
            return delegate.discover(new DiscoveryRequest(new ResolvedDiscovery(target.providerId(), target.serviceId(),
                    target.profileVersion(), target.authenticationScheme(), endpoint, endpoint + "/models", target.dialectVersion(),
                    target.verificationStatus()), request.credential(), request.timeout()));
        }
        @Override public InvocationResult generate(InvocationRequest request, ChatCompletionsPolicy policy) {
            original.set(request);
            return delegate.generate(withEndpoint(request, endpoint), policy);
        }
        @Override public Flow.Publisher<StreamEvent> stream(InvocationRequest request, ChatCompletionsPolicy policy) {
            original.set(request);
            return delegate.stream(withEndpoint(request, endpoint), policy);
        }
    }
}
