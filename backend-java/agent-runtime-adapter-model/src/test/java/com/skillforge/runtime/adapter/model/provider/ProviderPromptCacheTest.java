package com.skillforge.runtime.adapter.model.provider;

import com.skillforge.runtime.adapter.model.openai.CustomOpenAiCompatibleModelProvider;
import com.skillforge.runtime.adapter.model.aliyun_bailian.AliyunBailianModelProvider;
import com.skillforge.runtime.adapter.model.deepseek.DeepSeekModelProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Message;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.PromptCacheScope;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Role;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.StreamEvent;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolChoice;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.adapter.model.openai.chat.OpenAiCompatibleProviderTransport;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus;
import com.skillforge.runtime.api.port.ModelPort.ToolUpdateMode;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
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
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 经真实本地 HTTP 路径检查 Provider 策略；不调用收费供应商，不宣称真实缓存命中。 */
class ProviderPromptCacheTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PromptCacheScope SCOPE = new PromptCacheScope("a".repeat(64));
    private static final List<Message> MESSAGES = List.of(
            new Message(Role.SYSTEM, "稳定指令", null, List.of()),
            new Message(Role.USER, "当前问题", null, List.of()),
            new Message(Role.SYSTEM, "本次时间样本", null, List.of()));
    private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> reportedModel = new AtomicReference<>();
    private final AtomicReference<String> followingStreamModel = new AtomicReference<>();
    private final AtomicReference<JsonNode> reportedUsage = new AtomicReference<>();
    private final AtomicInteger exchanges = new AtomicInteger();
    private final com.skillforge.runtime.adapter.model.transport.SdkHttpResources sdkResources =
            new com.skillforge.runtime.adapter.model.transport.SdkHttpResources();
    private HttpServer server;
    private HttpClient http;
    private ModelProviderTransport transport;
    private String endpoint;

    @BeforeEach void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::chat);
        server.createContext("/v1/models", exchange -> respond(exchange, "{\"data\":[{\"id\":\"account-model\"}]}"));
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        transport = new OpenAiCompatibleProviderTransport(http, Clock.systemUTC(), 100_000, sdkResources);
    }

    @AfterEach void stopServer() throws InterruptedException {
        sdkResources.close();
        if (server != null) server.stop(0);
        if (http != null) {
            http.shutdownNow();
            assertTrue(http.awaitTermination(Duration.ofSeconds(5)), "HTTP 客户端尚未静止");
        }
    }

    @Test void chatProtocolEncodesScopedCacheKeyAndSelectedOutputTokenFieldWithoutChangingMessages() {
        var request = request("custom-openai-compatible", "custom-v1", "account-model");
        var policy = ModelProvider.ChatCompletionsPolicy.standard().withPromptCache(ModelProvider.PromptCacheEncoding.OPENAI_KEY)
                .withOutputTokenField(ModelProvider.OutputTokenField.MAX_COMPLETION_TOKENS);
        var completed = assertInstanceOf(InvocationResult.Completed.class, transport.generate(request, policy));
        assertEquals("hello", completed.content());
        assertEquals(SCOPE.key(), lastRequest.get().path("prompt_cache_key").asText());
        assertEquals(32, lastRequest.get().path("max_completion_tokens").asInt());
        assertFalse(lastRequest.get().has("max_tokens"));
        assertEquals("稳定指令", lastRequest.get().at("/messages/0/content").asText());
        assertFalse(request.toString().contains(SCOPE.key()));
        assertFalse(SCOPE.toString().contains(SCOPE.key()));
        assertEquals(MESSAGES, request.messages());
    }

    @Test void deepSeekStandardChatOmitsBetaStrictWhileCustomPreservesPreferenceAndSchema() throws Exception {
        var schema=new TypedPayload("application/json","{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"}},\"required\":[\"x\"],\"additionalProperties\":false}");
        var tool=new ModelProvider.ToolDefinition("lookup","lookup",schema,true,
                com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL,false);
        for(boolean deepSeek:List.of(true,false)) {
            var base=request(deepSeek?"deepseek-api":"custom-openai-compatible",deepSeek?"2026-09-13":"custom-v1","private-account-model");
            var call=new InvocationRequest(base.endpoint(),base.credential(),base.modelId(),base.profile(),base.messages(),base.response(),
                    base.generation(),base.reasoning(),List.of(tool),base.toolChoice(),base.timeout(),base.cancelled(), base.beforeSend());
            ModelProvider provider=deepSeek?new DeepSeekModelProvider(transport):new CustomOpenAiCompatibleModelProvider(transport);
            assertInstanceOf(InvocationResult.Completed.class,provider.generate(call));
            assertEquals(!deepSeek,lastRequest.get().at("/tools/0/function").has("strict"));
            assertEquals(JSON.readTree(schema.canonicalJson()),lastRequest.get().at("/tools/0/function/parameters"));
            assertInstanceOf(StreamEvent.Completed.class,stream(provider,call));
            assertEquals(!deepSeek,lastRequest.get().at("/tools/0/function").has("strict"));
            assertEquals(JSON.readTree(schema.canonicalJson()),lastRequest.get().at("/tools/0/function/parameters"));
            assertTrue(call.tools().getFirst().strict(),"不修改冻结工具自身的生成偏好");
        }
        var policy=new DeepSeekModelProvider().chatCompletionsPolicy(ReasoningPolicy.LOW)
                .withPromptCache(ModelProvider.PromptCacheEncoding.NONE)
                .withOutputTokenField(ModelProvider.OutputTokenField.MAX_COMPLETION_TOKENS)
                .withThinkingEncoding(ModelProvider.ThinkingEncoding.THINKING_ENABLED_OBJECT)
                .withContentRejectionPolicy((code,type,message)->false)
                .withReasoningEffort(ModelProvider.ReasoningEffort.HIGH);
        assertFalse(policy.strictToolGeneration(),"各with方法保留供应商strict wire策略");
        assertTrue(new ModelProvider.ChatCompletionsPolicy(false,false).strictToolGeneration());
    }

    @Test void runtimeAssemblyActuallyWiresBaseProtocolDiscoveryAndInvocation() {
        var runtime = ModelProviderRuntime.runtime(http, Clock.systemUTC(), 100_000);
        var discovery = new ModelProviderCatalog.ResolvedDiscovery("custom", "custom-openai-compatible", "custom-v1",
                AuthenticationScheme.BEARER_API_KEY, endpoint, endpoint + "/models", "openai-model-list-v1",
                ModelProviderCatalog.VerificationStatus.UNVERIFIED);
        assertEquals("account-model", runtime.discover("custom",
                new ModelProvider.DiscoveryRequest(discovery, "key", Duration.ofSeconds(3))).getFirst().modelId());
        assertInstanceOf(InvocationResult.Completed.class,
                runtime.generate("custom", request("custom-openai-compatible", "custom-v1", "account-model")));
    }


    @Test void baseProtocolRejectsAnyChangedModelIdentityInBothModes() throws Exception {
        var provider = new CustomOpenAiCompatibleModelProvider(transport);
        var request = request("custom-openai-compatible", "custom-v1", "account-model");
        for (String model : List.of("another-model", "account-model-2026-01-01", "account-model-suffix")) {
            reportedModel.set(model);
            assertInstanceOf(InvocationResult.OutcomeUnknown.class, provider.generate(request));
            assertInstanceOf(StreamEvent.OutcomeUnknown.class, stream(provider, request));
        }
    }

    @Test void baseProtocolModelCannotChangeAfterStreamIdentityConfirmation() throws Exception {
        reportedModel.set("account-model");
        followingStreamModel.set("another-model");
        assertInstanceOf(StreamEvent.OutcomeUnknown.class, stream(new CustomOpenAiCompatibleModelProvider(transport),
                request("custom-openai-compatible", "custom-v1", "account-model")));
    }


    @Test void ordinaryBailianAndCustomDoNotReceiveAnotherProvidersCacheFields() {
        for (ModelProvider provider : List.of(new AliyunBailianModelProvider(transport),
                new CustomOpenAiCompatibleModelProvider(transport))) {
            provider.generate(request("bailian-cn-beijing", "2026-09-15-global-v1", "qwen-plus"));
            var body = lastRequest.get();
            assertFalse(body.has("prompt_cache_key"));
            assertFalse(body.has("prompt_cache_retention"));
            assertFalse(body.toString().contains("cache_control"));
            assertEquals(32, body.path("max_tokens").asInt());
            assertTrue(body.path("messages").get(0).path("content").isTextual());
        }
    }

    @Test void disabledBailianMixedModelsUseNativeThinkingFlagInBothModes() throws Exception {
        var provider = new AliyunBailianModelProvider(transport);
        for (boolean explicit : List.of(false, true)) {
            var models = List.of("qwen3.8-max", "future-model", "private-account-model");
            for (String model : models) {
                var request = request(explicit ? AliyunBailianModelProvider.EXPLICIT_CACHE_SERVICE : "bailian-cn-beijing",
                        explicit ? AliyunBailianModelProvider.EXPLICIT_CACHE_PROFILE : "2026-09-15-global-v1", model);
                assertInstanceOf(InvocationResult.Completed.class, provider.generate(request));
                assertBailianThinkingDisabled();
                assertInstanceOf(StreamEvent.Completed.class, stream(provider, request));
                assertBailianThinkingDisabled();
            }
        }
    }

    @Test void bailianThinkingSwitchUsesProtocolEncodingForAnyModelInBothModes() throws Exception {
        var provider = new AliyunBailianModelProvider(transport);
        for (String model : List.of("qwen3.8-2.4t-a95b", "future-model", "tenant-private-model")) {
            var request = request("bailian-cn-beijing", "2026-09-15-global-v1", model);
            assertInstanceOf(InvocationResult.Completed.class, provider.generate(request));assertBailianThinkingDisabled();
            assertInstanceOf(StreamEvent.Completed.class, stream(provider, request));assertBailianThinkingDisabled();
        }
        assertEquals(6, exchanges.get());
    }

    @Test void defaultBailianThinkingPolicyPreservesSupplierDefaultInBothModes() throws Exception {
        var provider = new AliyunBailianModelProvider(transport);
        for (String model : List.of("qwen3.7-plus", "qwen3.8-max", "qwen3.7-max-preview", "future-model")) {
            var request = withReasoning(request("bailian-cn-beijing", "2026-09-15-global-v1", model), ReasoningPolicy.DEFAULT);
            assertInstanceOf(InvocationResult.Completed.class, provider.generate(request));
            assertNoThinkingFlag();
            assertInstanceOf(StreamEvent.Completed.class, stream(provider, request));
            assertNoThinkingFlag();
        }
    }

    @Test void deepSeekCanonicalEffortsUseNativeFieldsInBothModes() throws Exception {
        var provider = new DeepSeekModelProvider(transport);
        for (String model : List.of("deepseek-flash", "deepseek-v4-pro", "future-model")) {
            for (var effort : List.of(ReasoningPolicy.LOW, ReasoningPolicy.HIGH, ReasoningPolicy.MAX)) {
                var call = withReasoning(request("deepseek-api", "2026-09-13", model), effort);
                assertInstanceOf(InvocationResult.Completed.class, provider.generate(call));
                assertDeepSeekEffort(effort);
                assertInstanceOf(StreamEvent.Completed.class, stream(provider, call));
                assertDeepSeekEffort(effort);
            }
        }
    }

    @Test void deepSeekEnabledIsAvailableForDynamicModelNamesWithoutInventedEffort() throws Exception {
        var provider=new DeepSeekModelProvider(transport);
        var call=withReasoning(request("deepseek-api","2026-09-13","private-account-model"),ReasoningPolicy.ENABLED);
        assertInstanceOf(InvocationResult.Completed.class,provider.generate(call));
        assertEquals("enabled",lastRequest.get().at("/thinking/type").asText());
        assertFalse(lastRequest.get().has("reasoning_effort"));
        assertInstanceOf(StreamEvent.Completed.class,stream(provider,call));
        assertEquals("enabled",lastRequest.get().at("/thinking/type").asText());
        assertFalse(lastRequest.get().has("reasoning_effort"));
    }

    private void assertDeepSeekEffort(ReasoningPolicy effort) {
        assertEquals(effort.name().toLowerCase(java.util.Locale.ROOT), lastRequest.get().path("reasoning_effort").asText());
        assertEquals("enabled", lastRequest.get().at("/thinking/type").asText());
        assertFalse(lastRequest.get().has("enable_thinking"));
    }

    @Test void unsupportedDeepSeekEffortsNeverDispatchRegardlessOfModelName() throws Exception {
        var provider = new DeepSeekModelProvider(transport);
        var runtime = new ModelProviderRuntime(List.of(provider));
        for (String model : List.of("deepseek-flash", "unknown-model")) {
            for (var effort : List.of(ReasoningPolicy.MINIMAL, ReasoningPolicy.MEDIUM, ReasoningPolicy.XHIGH)) {
                var call = withReasoning(request("deepseek-api", "2026-09-13", model), effort);
                assertEquals("model_reasoning_policy_unsupported", assertInstanceOf(InvocationResult.Rejected.class, provider.generate(call)).reasonCode());
                assertInstanceOf(StreamEvent.Rejected.class, stream(provider, call));
                assertEquals("model_reasoning_policy_unsupported", assertInstanceOf(InvocationResult.Rejected.class, runtime.generate("deepseek", call)).reasonCode());
            }
        }
        assertEquals(0, exchanges.get());
    }

    @Test void bailianEnabledIsABooleanSwitchNotAnInventedEffortOrBudget() throws Exception {
        var provider = new AliyunBailianModelProvider(transport);
        for (String model : List.of("qwen3.8-max", "qwen3.7-plus", "qwen3.8-2.4t-a95b")) {
            for (boolean explicit : List.of(false, true)) {
                var call = withReasoning(request(explicit ? AliyunBailianModelProvider.EXPLICIT_CACHE_SERVICE : "bailian-cn-beijing",
                        explicit ? AliyunBailianModelProvider.EXPLICIT_CACHE_PROFILE : "2026-09-15-global-v1", model), ReasoningPolicy.ENABLED);
                assertInstanceOf(InvocationResult.Completed.class, provider.generate(call));
                assertTrue(lastRequest.get().path("enable_thinking").asBoolean());
                assertFalse(lastRequest.get().has("reasoning_effort")); assertFalse(lastRequest.get().has("thinking_budget"));
                assertInstanceOf(StreamEvent.Completed.class, stream(provider, call));
                assertTrue(lastRequest.get().path("enable_thinking").asBoolean());
                assertFalse(lastRequest.get().has("reasoning_effort")); assertFalse(lastRequest.get().has("thinking_budget"));
            }
        }
    }

    @Test void reasoningMetadataDescribesProtocolCandidatesWithoutModelCompatibilityClaims() {
        var catalog = ModelProviderCatalog.standard();
        for (String model : List.of("deepseek-flash", "future-model")) {
            var selection = new ModelProviderCatalog.Selection("deepseek", "deepseek-api", "2026-09-13", model,
                    ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, null);
            var options = catalog.reasoningOptions(selection);
            assertEquals(List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.DISABLED, ReasoningPolicy.ENABLED,
                    ReasoningPolicy.LOW, ReasoningPolicy.HIGH, ReasoningPolicy.MAX), options.options());
            assertEquals(ReasoningOptions.Source.PROTOCOL_DEFINITION, options.source());
            assertThrows(ModelProviderCatalog.ProviderProfileRejectedException.class,
                    () -> catalog.validateReasoning(catalog.resolve(selection), ReasoningPolicy.MEDIUM));
        }
        for (String model : List.of("qwen3.8-max", "qwen3.7-max-preview", "future-model")) {
            var selection = new ModelProviderCatalog.Selection("aliyun-bailian", "bailian-cn-beijing", "2026-09-15-global-v1", model,
                    ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, null);
            var options = catalog.reasoningOptions(selection);
            assertEquals(List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.DISABLED, ReasoningPolicy.ENABLED), options.options());
            assertEquals(ReasoningOptions.Source.PROTOCOL_DEFINITION, options.source());
        }
    }

    @Test void deepSeekPrefixCandidatesDoNotInferCapabilitiesFromModelNames() {
        var catalog=ModelProviderCatalog.standard();
        for (String model:List.of("deepseek-flash","deepseek-v4-pro","future-account-model")) {
            for (var protocol:List.of(ModelProtocol.OPENAI_CHAT_COMPLETIONS,ModelProtocol.ANTHROPIC_MESSAGES)) {
                var selected=catalog.resolve(new ModelProviderCatalog.Selection("deepseek","deepseek-api","2026-09-13",model,
                        protocol,protocol==ModelProtocol.ANTHROPIC_MESSAGES?AuthenticationScheme.ANTHROPIC_API_KEY:AuthenticationScheme.BEARER_API_KEY,null));
                assertEquals(CapabilitySupportStatus.UNVERIFIED,selected.prefixCapabilities().systemUpdatesInHistory());
                assertEquals(ToolUpdateMode.NONE,selected.prefixCapabilities().toolUpdates());
                assertEquals(CapabilitySupportStatus.SUPPORTED,selected.prefixCapabilities().retainToolsWithToolChoiceNone());
            }
        }
    }

    @Test void bailianProtocolParametersDoNotLeakIntoOtherSuppliers() throws Exception {
        new CustomOpenAiCompatibleModelProvider(transport)
                .generate(request("bailian-cn-beijing", "2026-09-15-global-v1", "future-model"));
        assertNoThinkingFlag();
    }

    @Test void deepSeekKeepsItsOwnThinkingObjectAndDefaultOmitsItInBothModes() throws Exception {
        var provider = new DeepSeekModelProvider(transport);
        var request = request("deepseek-api", "2026-09-13", "deepseek-flash");
        assertInstanceOf(InvocationResult.Completed.class, provider.generate(request));
        assertEquals("disabled", lastRequest.get().path("thinking").path("type").asText());
        assertFalse(lastRequest.get().has("enable_thinking"));
        assertInstanceOf(StreamEvent.Completed.class, stream(provider, request));
        assertEquals("disabled", lastRequest.get().path("thinking").path("type").asText());
        assertFalse(lastRequest.get().has("enable_thinking"));
        var defaultRequest = withReasoning(request, ReasoningPolicy.DEFAULT);
        assertInstanceOf(InvocationResult.Completed.class, provider.generate(defaultRequest));
        assertNoThinkingFlag();
        assertInstanceOf(StreamEvent.Completed.class, stream(provider, defaultRequest));
        assertNoThinkingFlag();
        assertTrue(provider.chatCompletionsPolicy(ReasoningPolicy.DEFAULT).requireAssistantReasoning());
    }

    @Test void explicitBailianMarksStableInstructionAndNewestUserWhileFinalTimeStaysUnmarked() throws Exception {
        var provider = new AliyunBailianModelProvider(transport);
        var request = request(AliyunBailianModelProvider.EXPLICIT_CACHE_SERVICE,
                AliyunBailianModelProvider.EXPLICIT_CACHE_PROFILE, "qwen-plus");
        assertInstanceOf(InvocationResult.Completed.class, provider.generate(request));
        assertBailianBoundaries(lastRequest.get());
        assertInstanceOf(StreamEvent.Completed.class, stream(provider, request));
        assertBailianBoundaries(lastRequest.get());
        assertEquals(MESSAGES, request.messages());
    }

    @Test void explicitBailianPreservesToolIdentityArgumentsAndSchemaAndMarksNewestToolResult() {
        var base = request(AliyunBailianModelProvider.EXPLICIT_CACHE_SERVICE,
                AliyunBailianModelProvider.EXPLICIT_CACHE_PROFILE, "qwen3-coder-plus");
        var call = new ModelProvider.ToolCall("call-1", "sf_tool_x", "{\"n\":9007199254740993.123456789}");
        var messages = List.of(MESSAGES.getFirst(), MESSAGES.get(1),
                new Message(Role.ASSISTANT, "", null, List.of(call), "冻结推理"),
                new Message(Role.TOOL, "冻结结果", call.callId(), List.of()), MESSAGES.getLast());
        var tools = List.of(new ModelProvider.ToolDefinition(call.name(), "工具",
                new TypedPayload("json-schema.v1", "{\"type\":\"object\"}"), false,
                com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL, false));
        var request = new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(),
                messages, base.response(), base.generation(), ReasoningPolicy.DEFAULT, tools, ToolChoice.AUTO,
                base.timeout(), base.cancelled(), base.mediaReader(), base.maximumRequestBytes(), base.promptCacheScope(), base.beforeSend());
        new AliyunBailianModelProvider(transport).generate(request);
        var encoded = lastRequest.get().path("messages");
        assertEquals(call.argumentsJson(), encoded.get(2).path("tool_calls").get(0).path("function").path("arguments").asText());
        assertEquals("冻结推理", encoded.get(2).path("reasoning_content").asText());
        assertTrue(encoded.get(2).path("content").isNull());
        assertEquals(call.callId(), encoded.get(3).path("tool_call_id").asText());
        assertEquals("ephemeral", encoded.get(3).path("content").get(0).path("cache_control").path("type").asText());
        assertEquals("冻结结果", encoded.get(3).path("content").get(0).path("text").asText());
        assertFalse(lastRequest.get().path("tools").toString().contains("cache_control"));
        assertTrue(encoded.get(4).path("content").isTextual());
        assertEquals(messages, request.messages());
    }

    @Test void bailianOfficialWriteAndReadCountsReachBothSyncAndStreamResults() throws Exception {
        reportedUsage.set(JSON.readTree("{\"prompt_tokens\":1618,\"completion_tokens\":2,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0,\"cache_creation_input_tokens\":1605}}"));
        assertBailianUsage(OptionalLong.of(1618), OptionalLong.of(0), OptionalLong.of(1605));
        reportedUsage.set(JSON.readTree("{\"prompt_tokens\":1620,\"completion_tokens\":2,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":1605,\"cache_creation_input_tokens\":0}}"));
        assertBailianUsage(OptionalLong.of(1620), OptionalLong.of(1605), OptionalLong.of(0));
    }

    @Test void bailianNestedFiveMinuteWriteAliasIsNormalizedWithoutDoubleCounting() throws Exception {
        reportedUsage.set(JSON.readTree("{\"prompt_tokens\":1618,\"completion_tokens\":2,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":1605}}}"));
        assertBailianUsage(OptionalLong.of(1618), OptionalLong.of(0), OptionalLong.of(1605));
        reportedUsage.set(JSON.readTree("{\"prompt_tokens\":1618,\"completion_tokens\":2,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0,\"cache_creation_input_tokens\":1605,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":1605}}}"));
        assertBailianUsage(OptionalLong.of(1618), OptionalLong.of(0), OptionalLong.of(1605));
    }

    @Test void missingBailianCacheFieldsRemainUnknownInBothModes() throws Exception {
        reportedUsage.set(JSON.readTree("{\"prompt_tokens\":1618,\"completion_tokens\":2,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0}}"));
        assertBailianUsage(OptionalLong.of(1618), OptionalLong.of(0), OptionalLong.empty());
        reportedUsage.set(JSON.readTree("{\"prompt_tokens\":1618,\"completion_tokens\":2}"));
        assertBailianUsage(OptionalLong.of(1618), OptionalLong.empty(), OptionalLong.empty());
        reportedUsage.set(null);
        assertBailianUsage(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
    }

    @Test void invalidBailianWriteAliasKeepsOtherReportedUsageAndRetainsInputSubsetValidation() throws Exception {
        for (String write : List.of("-1", "1.5", "\"1605\"", "null", "501")) {
            reportedUsage.set(JSON.readTree("{\"prompt_tokens\":500,\"completion_tokens\":2,"
                    + "\"prompt_tokens_details\":{\"cached_tokens\":200,\"cache_creation_input_tokens\":"
                    + write + "}}"));
            assertBailianUsage(OptionalLong.of(500), OptionalLong.of(200), OptionalLong.empty());
        }
    }

    @Test void explicitCacheAcceptsAnyModelButMismatchedProfileStillRejects() throws Exception {
        var bailian = new AliyunBailianModelProvider(transport);
        var dynamic = request(AliyunBailianModelProvider.EXPLICIT_CACHE_SERVICE,
                AliyunBailianModelProvider.EXPLICIT_CACHE_PROFILE, "future-account-model");
        assertInstanceOf(InvocationResult.Completed.class,bailian.generate(dynamic));assertBailianBoundaries(lastRequest.get());
        assertInstanceOf(StreamEvent.Completed.class,stream(bailian,dynamic));assertBailianBoundaries(lastRequest.get());
        int before=exchanges.get();
        assertRejected(bailian, request(AliyunBailianModelProvider.EXPLICIT_CACHE_SERVICE,
                "wrong-version", "future-account-model"), "model_provider_profile_invalid");
        assertEquals(before, exchanges.get());
    }

    @Test void cacheEncodingRemainsInsideRequestByteLimit() {
        var base = request("custom-openai-compatible", "custom-v1", "account-model");
        var bounded = new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(),
                base.messages(), base.response(), base.generation(), base.reasoning(), base.tools(), base.toolChoice(),
                base.timeout(), base.cancelled(), base.mediaReader(), 100, base.promptCacheScope(), base.beforeSend());
        var result = assertInstanceOf(InvocationResult.Rejected.class,
                transport.generate(bounded, ModelProvider.ChatCompletionsPolicy.standard().withPromptCache(ModelProvider.PromptCacheEncoding.OPENAI_KEY)));
        assertEquals("model_request_bytes_exceeded", result.reasonCode());
        assertEquals(0, exchanges.get());
    }

    @Test void rawCacheIdentifiersAreRejectedAndMissingScopeIsNotFabricated() {
        assertThrows(IllegalArgumentException.class, () -> new PromptCacheScope("tenant-id:session-id"));
        var base = request("custom-openai-compatible", "custom-v1", "account-model");
        var withoutScope = new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(),
                base.messages(), base.response(), base.generation(), base.reasoning(), base.tools(), base.toolChoice(),
                base.timeout(), base.cancelled(), base.beforeSend());
        transport.generate(withoutScope, ModelProvider.ChatCompletionsPolicy.standard().withPromptCache(ModelProvider.PromptCacheEncoding.OPENAI_KEY));
        assertFalse(lastRequest.get().has("prompt_cache_key"));
    }

    private static void assertBailianBoundaries(JsonNode root) {
        var messages = root.path("messages");
        for (int index : List.of(0, 1)) {
            assertEquals("ephemeral", messages.get(index).path("content").get(0).path("cache_control").path("type").asText());
        }
        assertEquals("稳定指令", messages.get(0).path("content").get(0).path("text").asText());
        assertEquals("当前问题", messages.get(1).path("content").get(0).path("text").asText());
        assertEquals("本次时间样本", messages.get(2).path("content").asText());
        assertFalse(root.has("prompt_cache_key"));
        assertFalse(root.has("enable_cache_control"));
    }

    private void assertBailianThinkingDisabled() {
        assertTrue(lastRequest.get().path("enable_thinking").isBoolean());
        assertFalse(lastRequest.get().path("enable_thinking").asBoolean());
        assertFalse(lastRequest.get().has("thinking"));
    }

    private void assertNoThinkingFlag() {
        assertFalse(lastRequest.get().has("enable_thinking"));
        assertFalse(lastRequest.get().has("thinking"));
    }

    private static InvocationRequest withReasoning(InvocationRequest base, ReasoningPolicy reasoning) {
        return new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(),
                base.messages(), base.response(), base.generation(), reasoning, base.tools(), base.toolChoice(),
                base.timeout(), base.cancelled(), base.mediaReader(), base.maximumRequestBytes(), base.promptCacheScope(), base.beforeSend());
    }

    private void assertRejected(ModelProvider provider, InvocationRequest request, String reason) throws Exception {
        assertEquals(reason, assertInstanceOf(InvocationResult.Rejected.class, provider.generate(request)).reasonCode());
        assertEquals(reason, assertInstanceOf(StreamEvent.Rejected.class, stream(provider, request)).reasonCode());
    }

    private void assertBailianUsage(OptionalLong input, OptionalLong cached, OptionalLong cacheWrite) throws Exception {
        var provider = new AliyunBailianModelProvider(transport);
        var request = request(AliyunBailianModelProvider.EXPLICIT_CACHE_SERVICE,
                AliyunBailianModelProvider.EXPLICIT_CACHE_PROFILE, "qwen-plus");
        var synchronous = assertInstanceOf(InvocationResult.Completed.class, provider.generate(request)).usage();
        var streamed = assertInstanceOf(StreamEvent.Completed.class, stream(provider, request)).result().usage();
        for (var usage : List.of(synchronous, streamed)) {
            assertEquals(input, usage.inputTokens());
            assertEquals(cached, usage.cachedInputTokens());
            assertEquals(cacheWrite, usage.cacheWriteInputTokens());
            assertEquals(reportedUsage.get() == null ? OptionalLong.empty() : OptionalLong.of(2), usage.outputTokens());
        }
    }

    private InvocationRequest request(String service, String version, String model) {
        return new InvocationRequest(endpoint, "key", model,
                new InvocationProfile(service, version, ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                        AuthenticationScheme.BEARER_API_KEY, "openai-compatible-chat-v1"), MESSAGES,
                new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "test"),
                new GenerationOptions(OptionalInt.of(32), OptionalDouble.empty()), ReasoningPolicy.DISABLED,
                List.of(), ToolChoice.NONE, Duration.ofSeconds(3), () -> false,
                ref -> { throw new AssertionError("No media expected"); }, 100_000, Optional.of(SCOPE), () -> { });
    }

    private StreamEvent stream(ModelProvider provider, InvocationRequest request) throws InterruptedException {
        var probe = new Probe();
        provider.stream(request).subscribe(probe);
        assertTrue(probe.done.await(5, TimeUnit.SECONDS), "流式调用未到达终态");
        if (probe.failure != null) throw new AssertionError(probe.failure);
        return probe.events.getLast();
    }

    private void chat(HttpExchange exchange) throws IOException {
        exchanges.incrementAndGet();
        var body = JSON.readTree(exchange.getRequestBody());
        lastRequest.set(body);
        var response = JSON.createObjectNode().put("model",
                reportedModel.get() == null ? body.path("model").asText() : reportedModel.get());
        var choice = response.putArray("choices").addObject().put("finish_reason", "stop");
        if (body.path("stream").asBoolean()) {
            choice.putObject("delta").put("content", "hello");
            String second = "";
            if (followingStreamModel.get() != null) {
                var following = JSON.createObjectNode().put("model", followingStreamModel.get());
                following.putArray("choices").addObject().put("finish_reason", "stop").putObject("delta");
                second = "data: " + following + "\n\n";
            }
            if (reportedUsage.get() != null) {
                var usageFrame = JSON.createObjectNode();
                usageFrame.putArray("choices");
                usageFrame.set("usage", reportedUsage.get());
                second += "data: " + usageFrame + "\n\n";
            }
            respond(exchange, "data: " + response + "\n\n" + second + "data: [DONE]\n\n");
        } else {
            choice.putObject("message").put("content", "hello");
            if (reportedUsage.get() != null) response.set("usage", reportedUsage.get());
            respond(exchange, response.toString());
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class Probe implements Flow.Subscriber<StreamEvent> {
        private final CountDownLatch done = new CountDownLatch(1);
        private final List<StreamEvent> events = new ArrayList<>();
        private Throwable failure;
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
        @Override public void onNext(StreamEvent event) { events.add(event); }
        @Override public void onError(Throwable error) { failure = error; done.countDown(); }
        @Override public void onComplete() { done.countDown(); }
    }
}
