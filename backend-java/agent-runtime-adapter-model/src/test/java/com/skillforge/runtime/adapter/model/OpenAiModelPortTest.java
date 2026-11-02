package com.skillforge.runtime.adapter.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.deepseek.DeepSeekModelProvider;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient.DiscoveredModel;
import com.skillforge.runtime.adapter.model.openai.chat.OpenAiCompatibleProviderTransport;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真 PostgreSQL 发布绑定与真 HTTP transport；许可和输出 guard 是显式允许夹具，生产策略另验。 */
@Testcontainers(disabledWithoutDocker = false)
class OpenAiModelPortTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            // 临时集群的初始化不等待全盘同步；正式 PostgreSQL 提交仍使用默认 fsync。
            .withEnv("POSTGRES_INITDB_ARGS", "--no-sync")
            .withDatabaseName("skillforge").withUsername("model_admin").withPassword("model_admin_password");
    private static final TenantId TENANT = new TenantId("1");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEEPSEEK_ENDPOINT = "https://api.deepseek.com";
    private static final String SUCCESS = "{\"model\":\"frozen-model\",\"choices\":[{\"message\":{\"reasoning_content\":\"provider reasoning\",\"content\":\"hello😀\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}";
    private final java.util.List<ModelProviderRuntime> ownedProviders = new java.util.ArrayList<>();
    private final com.skillforge.runtime.adapter.model.transport.SdkHttpResources sdkResources =
            new com.skillforge.runtime.adapter.model.transport.SdkHttpResources();
    private HttpServer server;
    private ExecutorService workers;
    private JdbcModelBindings bindings;
    private JdbcModelBindings.Binding binding;
    private final AtomicReference<Responder> responder = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeAll static void schema() throws Exception {
        var source = new PGSimpleDataSource(); source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        com.skillforge.runtime.adapter.jdbc.schema.RuntimeJdbcSchema.install(source);
    }

    @BeforeEach void setup() throws Exception {
        sql("TRUNCATE agent_runtime_model_bindings");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(workers);
        responder.set(exchange -> respond(exchange, 200, SUCCESS));
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet(); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(JSON.readTree(exchange.getRequestBody()));
            try { responder.get().respond(exchange); } finally { exchange.close(); }
        });
        server.createContext("/v1/responses", exchange -> {
            requests.incrementAndGet(); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(JSON.readTree(exchange.getRequestBody()));
            try { responder.get().respond(exchange); } finally { exchange.close(); }
        });
        server.start();
        var dataSource = new PGSimpleDataSource(); dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername()); dataSource.setPassword(POSTGRES.getPassword());
        var providers = ModelProviderCatalog.standard();
        bindings = new JdbcModelBindings(dataSource, (configuration, requestedProfile) -> {
            var profile = configuration.providerProfile();
            var resolved = providers.resolve(new ModelProviderCatalog.Selection(
                    profile.providerId(), profile.serviceId(), profile.profileVersion(),
                    configuration.modelId(), ModelProtocol.fromId(profile.protocol()),
                    AuthenticationScheme.fromId(profile.authenticationScheme()),
                    configuration.endpoint()));
            if (!resolved.dialectVersion().equals(profile.dialectVersion())) {
                throw new IllegalArgumentException("model_dialect_version_mismatch");
            }
        });
        binding = bindings.publish(TENANT, "chat", Optional.empty(), new JdbcModelBindings.Profile(
                new RequestedModelStrategy("chat", "v1"), Set.of(ResponseFormatKind.TEXT, ResponseFormatKind.JSON_OBJECT, ResponseFormatKind.JSON_SCHEMA),
                true, false, Duration.ofSeconds(5)), new JdbcModelBindings.Source("platform-model:frozen",
                new JdbcModelBindings.ProviderConfiguration("frozen-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        new JdbcModelBindings.ProviderProfile("custom", "custom-openai-compatible", "custom-v1",
                                "openai-chat-completions", "bearer-api-key", "openai-compatible-chat-v1")), "encrypted-secret"));
    }

    @AfterEach void cleanup() { sdkResources.close(); for (var runtime : ownedProviders) runtime.close(); release.countDown(); server.stop(0); workers.shutdownNow(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ModelProtocol.class,
            names = {"OPENAI_CHAT_COMPLETIONS", "OPENAI_RESPONSES"})
    void readsOnlyFrozenSessionMediaAndRejectsChangedBytesBeforeHttp(ModelProtocol protocol) throws Exception {
        boolean responses = protocol == ModelProtocol.OPENAI_RESPONSES;
        if (responses) responder.set(exchange -> respond(exchange, 200,
                "{\"object\":\"response\",\"id\":\"resp-image\",\"status\":\"completed\",\"model\":\"frozen-model\",\"output\":[{\"type\":\"message\",\"id\":\"msg-image\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}],\"usage\":{\"input_tokens\":4,\"output_tokens\":2}}"));
        var policy = new com.skillforge.runtime.api.media.ModelImagePolicy(true, true, 4, 1024,
                8192, 2048, 512, 85, 170, "vision.v1");
        binding = bindings.publish(TENANT, "chat", Optional.empty(), new JdbcModelBindings.Profile(
                binding.strategy(), binding.target().capabilities().responseFormats(), true, false,
                Duration.ofSeconds(5), policy), new JdbcModelBindings.Source(binding.sourceReference(),
                new JdbcModelBindings.ProviderConfiguration(binding.target().effectiveModelId(), binding.endpoint(),
                        new JdbcModelBindings.ProviderProfile("custom", "custom-openai-compatible", "custom-v1",
                                protocol.id(), "bearer-api-key", responses ? "openai-responses-v1" : "openai-compatible-chat-v1")),
                binding.encryptedCredential()));
        assertEquals(policy, bindings.find(binding.target().binding()).orElseThrow().target().capabilities().imagePolicy());
        byte[] original = new byte[]{1, 2, 3};
        var content = new AtomicReference<>(original);
        var reads = new AtomicInteger();
        var failRead = new AtomicBoolean();
        MediaPort mediaPort = new MediaPort() {
            @Override public byte[] read(SessionRef owner, com.skillforge.runtime.api.media.MediaRef ref) {
                assertEquals(owner, ref.owner()); reads.incrementAndGet();
                if (failRead.get()) throw new IllegalStateException("private-media-read-failed");
                return content.get();
            }
            @Override public com.skillforge.runtime.api.media.MediaRef prepare(SessionRef owner,
                    com.skillforge.runtime.api.media.MediaRef ref, com.skillforge.runtime.api.media.ModelImagePolicy p) {
                throw new AssertionError("sending must never choose a new image variant");
            }
        };
        var base = ordinary(false);
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
        var ref = new com.skillforge.runtime.api.media.MediaRef(base.context().callContext().runIdentity().owner().sessionRef(),
                "frozen-image", hash, "image/png", 3, 1, 1);
        var call = new ModelCall(base.context(), base.requestedStrategy(), base.target(), base.invocationSnapshotRef(),
                List.of(new ModelMessage("image", ModelRole.USER, List.of(new com.skillforge.runtime.api.media.ModelContent.Image(
                        ref, com.skillforge.runtime.api.media.ImageDetail.HIGH)))),
                base.response(), base.generation(), base.reasoning());
        var selected = transportBinding();
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var knownFailures = new AtomicInteger();
        var unknownFailures = new AtomicInteger();
        var audit = new ModelInvocationAudit() {
            @Override public boolean start(ModelCall value, Provider provider) { return true; }
            @Override public void succeeded(ModelCall value, Provider provider, ModelUsage usage) { }
            @Override public void failed(ModelCall value, Provider provider, String reason) { knownFailures.incrementAndGet(); }
            @Override public void unresolved(ModelCall value, Provider provider, String reason) { unknownFailures.incrementAndGet(); }
        };
        var port = new OpenAiModelPort(id -> Optional.of(selected), cipher -> "key".getBytes(StandardCharsets.UTF_8),
                value -> true, (value, output) -> true, http, Clock.systemUTC(), 100_000,
                com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime.runtime(http, Clock.systemUTC(), 100_000),
                audit, mediaPort);
        var completed = assertInstanceOf(ModelResult.Completed.class, port.generate(call));
        assertTrue(completed.assistantTurn().isEmpty());
        var image = requestBody.get().path(responses ? "input" : "messages").get(0).path("content").get(0);
        assertEquals("data:image/png;base64,AQID", responses ? image.path("image_url").asText()
                : image.path("image_url").path("url").asText());
        if (responses) assertEquals("high", image.path("detail").asText());
        content.set(new byte[]{4, 5, 6});
        assertInstanceOf(ModelResult.Failed.class, port.generate(call));
        assertEquals(1, requests.get());
        assertEquals(2, reads.get());
        failRead.set(true);
        assertInstanceOf(ModelResult.Failed.class, port.generate(call));
        assertEquals(1, requests.get(), "媒体读取抛错不得启动新的 HTTP 请求");
        assertEquals(2, knownFailures.get());
        assertEquals(0, unknownFailures.get());
    }

    @Test void usesFrozenRevisionCanonicalRequestAndUsageEvenAfterSourceChanges() throws Exception {
        byte[] key = "test-key".getBytes(StandardCharsets.UTF_8);
        var result = assertInstanceOf(ModelResult.Completed.class, port(cipher -> {
            assertEquals("encrypted-secret", cipher); return key;
        }).generate(call(binding.target(), new ResponseFormat.Text(), Instant.now().plusSeconds(5), () -> false, false)));
        assertEquals("frozen-model", result.effectiveModelId());
        assertEquals("skillforge.runtime.model.text.v1", result.output().schemaId());
        assertEquals("hello😀", JSON.readTree(result.output().canonicalJson()).textValue());
        assertTrue(result.reasoningContent().isEmpty());
        assertEquals(4, result.usage().inputTokens().orElseThrow()); assertEquals(2, result.usage().outputTokens().orElseThrow());
        assertEquals("Bearer test-key", authorization.get()); assertArrayEquals(new byte[key.length], key);
        var body = requestBody.get(); assertEquals("frozen-model", body.path("model").textValue());
        assertEquals("system", body.path("messages").get(0).path("role").textValue());
        assertEquals("hello", body.path("messages").get(1).path("content").textValue());
        assertEquals(100, body.path("max_tokens").intValue()); assertEquals(0.25, body.path("temperature").doubleValue());
        assertFalse(body.has("response_format")); assertFalse(body.path("stream").booleanValue()); assertEquals(1, requests.get());
    }

    /**
     * 直接派发上下文经过实际模型 JSON/SSE HTTP 适配，第二次请求保留真实线协议的调用与结果配对。
     * 仅验证适配器合同；授权及工具结果使用明确夹具，不作为新 Runtime 主链、IAM 或真实工具执行验收。
     */
    @Test void directDispatchContextCarriesNativeToolHistoryAcrossGenerateAndStream() throws Exception {
        String providerCallId = "direct-provider-tool-call";
        String arguments = "{\"template\":\"数量{{quantity}}\",\"data\":{\"quantity\":7}}";
        var observed = new CopyOnWriteArrayList<JsonNode>();
        responder.set(exchange -> {
            var request = requestBody.get().deepCopy();
            observed.add(request);
            int ordinal = observed.size();
            var message = ordinal == 1 ? nativeMessage("sf_tool_0", arguments)
                    : JSON.createObjectNode().put("content", "已处理数量7");
            if (ordinal == 1) ((com.fasterxml.jackson.databind.node.ObjectNode) message.path("tool_calls").get(0))
                    .put("id", providerCallId);
            var root = JSON.createObjectNode().put("model", "frozen-model");
            var choice = root.putArray("choices").addObject().put("index", 0).put("finish_reason", ordinal == 1 ? "tool_calls" : "stop");
            choice.set(request.path("stream").asBoolean() ? "delta" : "message", message);
            root.putObject("usage").put("prompt_tokens", ordinal == 1 ? 4 : 9).put("completion_tokens", ordinal == 1 ? 2 : 3);
            String body = JSON.writeValueAsString(root);
            respond(exchange, 200, request.path("stream").asBoolean()
                    ? "data: " + body + "\n\ndata: [DONE]\n\n" : body);
        });
        var messages = new ArrayList<ModelMessage>();
        messages.add(new ModelMessage("direct-system", ModelRole.SYSTEM, "调用模板工具后确认结果"));
        messages.add(new ModelMessage("direct-user", ModelRole.USER, "处理数量7"));
        var firstCall = dispatchedCall("first", false, messages, List.of(nativeTool(ModelToolKind.TOOL_CALL)));
        assertEquals(PortalCallKind.MODEL_GENERATE, firstCall.context().dispatch().callKind());
        assertEquals(firstCall.invocationSnapshotRef(), firstCall.context().dispatch().requestRef());
        var adapter = port();
        var first = assertInstanceOf(ModelResult.Completed.class, adapter.generate(firstCall));
        assertEquals(ModelPort.TEXT_OUTPUT_SCHEMA, first.output().schemaId());
        assertEquals(new AssistantContent.ToolCall(providerCallId, "sf_tool_0", arguments),
                first.assistantTurn().orElseThrow().toolCalls().getFirst());
        assertEquals(4, first.usage().inputTokens().orElseThrow());
        assertEquals(2, first.usage().outputTokens().orElseThrow());
        assertEquals("tool_calls", first.finishReasonCode());
        messages.add(new ModelMessage("direct-assistant", ModelRole.ASSISTANT, "",
                List.of(new ModelToolCall(providerCallId, "sf_tool_0", new TypedPayload("args.v1", arguments))),
                Optional.empty(), Optional.empty()));
        String toolResult = "{\"success\":true,\"rendered\":\"数量7\"}";
        messages.add(new ModelMessage("direct-tool-result", ModelRole.TOOL, toolResult, List.of(),
                Optional.of(providerCallId), Optional.empty()));
        var secondCall = dispatchedCall("second", true, messages,
                List.of(nativeTool(ModelToolKind.TOOL_CALL)));
        assertEquals(PortalCallKind.MODEL_STREAM, secondCall.context().dispatch().callKind());
        assertEquals(secondCall.invocationSnapshotRef(), secondCall.context().dispatch().requestRef());
        var probe = new Probe();
        adapter.stream(secondCall).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.done.await(3, TimeUnit.SECONDS));
        assertNull(probe.error.get());
        var streamEvents = new ArrayList<ModelStreamEvent>();
        probe.events.drainTo(streamEvents);
        var finalEvent = assertInstanceOf(ModelStreamEvent.Completed.class, streamEvents.getLast());
        assertEquals("已处理数量7", JSON.readTree(finalEvent.result().output().canonicalJson()).textValue());
        assertTrue(finalEvent.result().assistantTurn().orElseThrow().toolCalls().isEmpty());
        assertEquals(9, finalEvent.result().usage().inputTokens().orElseThrow());
        assertEquals(3, finalEvent.result().usage().outputTokens().orElseThrow());
        assertEquals(2, requests.get());
        assertEquals(2, observed.size());
        assertFalse(observed.getFirst().path("stream").asBoolean());
        assertTrue(observed.getLast().path("stream").asBoolean());
        var wireMessages = observed.getLast().path("messages");
        assertEquals(4, wireMessages.size());
        assertEquals("assistant", wireMessages.get(2).path("role").asText());
        var wireCall = wireMessages.get(2).path("tool_calls").get(0);
        assertEquals(providerCallId, wireCall.path("id").asText());
        assertEquals("sf_tool_0", wireCall.path("function").path("name").asText());
        assertEquals(JSON.readTree(arguments), JSON.readTree(wireCall.path("function").path("arguments").asText()));
        assertEquals("tool", wireMessages.get(3).path("role").asText());
        assertEquals(providerCallId, wireMessages.get(3).path("tool_call_id").asText());
        assertEquals(toolResult, wireMessages.get(3).path("content").asText());
        assertNotEquals(firstCall.context().dispatch().attemptId(), secondCall.context().dispatch().attemptId());
    }

    /** 受限视图复用原 transport；审计未准入不发送，替换请求也不能沿视图绕过原授权。 */
    @Test void dispatchedViewUsesCapturedBindingAndScopedAuditBeforeSendingWithoutLegacyPolicyLookup() throws Exception {
        var response = JSON.createObjectNode().put("model", "frozen-model");
        response.putArray("choices").addObject().put("finish_reason", "tool_calls")
                .set("message", nativeMessage("sf_tool_0", "{\"city\":\"杭州\"}"));
        response.putObject("usage").put("prompt_tokens", 4).put("completion_tokens", 2);
        String responseBody = JSON.writeValueAsString(response);
        responder.set(exchange -> respond(exchange, 200, responseBody));
        var call = dispatchedCall("restricted-view", false,
                List.of(new ModelMessage("user", ModelRole.USER, "查询杭州")), List.of(nativeTool(ModelToolKind.TOOL_CALL)));
        var frozen = transportBinding();
        var allowStart = new AtomicBoolean();
        var starts = new AtomicInteger();
        var succeeded = new AtomicInteger();
        var usageReports = new AtomicInteger();
        var reportedUsage = new AtomicReference<ModelUsage>();
        var terminalUsage = new AtomicReference<ModelUsage>();
        var audit = new ModelInvocationAudit() {
            @Override public boolean start(ModelCall candidate, Provider provider) {
                assertSame(call, candidate);
                starts.incrementAndGet();
                return allowStart.get();
            }
            @Override public void usageReported(ModelCall candidate, Provider provider, long revision, ModelUsage usage) {
                assertSame(call, candidate);
                assertEquals(1, revision);
                usageReports.incrementAndGet();
                reportedUsage.set(usage);
            }
            @Override public void succeeded(ModelCall candidate, Provider provider, ModelUsage usage) {
                assertSame(call, candidate);
                succeeded.incrementAndGet();
                terminalUsage.set(usage);
            }
            @Override public void failed(ModelCall candidate, Provider provider, String reasonCode) {
                throw new AssertionError("unexpected audit failure: " + reasonCode);
            }
            @Override public void unresolved(ModelCall candidate, Provider provider, String reasonCode) {
                throw new AssertionError("unexpected unresolved audit: " + reasonCode);
            }
        };
        var decryptions = new AtomicInteger();
        try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var original = new OpenAiModelPort(reference -> { throw new AssertionError("legacy binding lookup must not run"); },
                    cipher -> { decryptions.incrementAndGet(); return "test-key".getBytes(StandardCharsets.UTF_8); },
                    candidate -> { throw new AssertionError("outer authorization callback must not run"); }, (candidate, output) -> true,
                    http, Clock.systemUTC(), 100_000);
            assertThrows(IllegalArgumentException.class, () -> original.forDispatch(call, frozen, ModelInvocationAudit.noop()));
            var view = original.forDispatch(call, frozen, audit);
            assertInstanceOf(ModelResult.OutcomeUnknown.class, view.generate(call));
            assertEquals(0, requests.get(), "受限审计未取得实际 attempt 时不得 HTTP 发送");
            allowStart.set(true);
            assertInstanceOf(ModelResult.Completed.class, view.generate(call));
            assertEquals(1, requests.get());
            var replacement = new ModelCall(call.context(), call.requestedStrategy(), call.target(), call.invocationSnapshotRef(),
                    call.messages(), call.tools(), call.toolChoice(), call.response(), call.generation(), call.reasoning());
            assertInstanceOf(ModelResult.Failed.class, view.generate(replacement));
            assertEquals(1, requests.get(), "同字段的新请求对象也不能替换唯一登记调用");
            assertEquals(2, decryptions.get(), "仅两个获绑定的准备访问解密，替换请求在此之前拒绝");
            assertEquals(2, starts.get());
            assertEquals(1, succeeded.get());
            assertEquals(1, usageReports.get());
            assertEquals(4, reportedUsage.get().inputTokens().orElseThrow());
            assertEquals(2, reportedUsage.get().outputTokens().orElseThrow());
            assertEquals(reportedUsage.get(), terminalUsage.get());
        }
    }

    @Test void currentAuthorizationRevokedAtSendBoundaryFailsAuditWithoutHttp() throws Exception {
        for (boolean streaming : List.of(false, true)) {
            var audit = new RecordingAudit();
            var checks = new AtomicInteger();
            var selected = transportBinding();
            try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
                var adapter = new OpenAiModelPort(reference -> Optional.of(selected),
                        cipher -> "test-key".getBytes(StandardCharsets.UTF_8),
                        candidate -> { checks.incrementAndGet(); return audit.starts.get() == 0; }, (candidate, output) -> true,
                        http, Clock.systemUTC(), 100_000,
                        ModelProviderRuntime.runtime(http, Clock.systemUTC(), 100_000), audit);
                var call = ordinary(streaming);
                if (streaming) {
                    var probe = new Probe(); adapter.stream(call).subscribe(probe);
                    probe.subscription.request(Long.MAX_VALUE);
                    assertTrue(probe.done.await(5, TimeUnit.SECONDS));
                    assertInstanceOf(ModelStreamEvent.Failed.class, new ArrayList<>(probe.events).getLast());
                } else assertInstanceOf(ModelResult.Failed.class, adapter.generate(call));
            }
            assertEquals(0, requests.get());
            assertEquals(1, audit.starts.get());
            assertEquals(1, audit.failed.get());
            assertEquals(0, audit.unresolved.get());
            assertEquals(1, checks.get());
        }
    }

    @Test void persistedAttemptAuditPreventsRestartFromSendingTheSameAttemptAgain() {
        var audit = new RecordingAudit();
        var adapter = port(audit);

        assertInstanceOf(ModelResult.Completed.class, adapter.generate(ordinary(false)));
        assertInstanceOf(ModelResult.OutcomeUnknown.class, adapter.generate(ordinary(false)));

        assertEquals(2, audit.starts.get());
        assertEquals(1, audit.succeeded.get());
        assertEquals(1, requests.get());
    }

    @Test void disablesDeepSeekThinkingWithoutSendingProviderOptionsToOtherProviders() throws Exception {
        responder.set(exchange -> respond(exchange, 200, SUCCESS.replace("frozen-model", "deepseek-flash")));
        for (String provider : List.of("deepseek", "custom")) {
            var profile = new OpenAiModelPort.ProviderProfile(provider,
                    provider.equals("deepseek") ? "deepseek-api" : "custom-openai-compatible",
                    provider.equals("deepseek") ? "2026-09-13" : "custom-v1",
                    ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY,
                    "openai-compatible-chat-v1");
            var target = new FrozenModelTarget(binding.target().binding(), "deepseek-flash",
                    binding.target().capabilities(), binding.target().timeout(), binding.target().resolutionRef());
            var transport = new OpenAiModelPort.Binding(target, binding.strategy(),
                    provider.equals("deepseek") ? DEEPSEEK_ENDPOINT : binding.endpoint(), binding.encryptedCredential(), profile);
            var original = ordinary(false);
            var call = new ModelCall(original.context(), original.requestedStrategy(), target,
                    original.invocationSnapshotRef(), original.messages(), original.tools(), original.toolChoice(),
                    original.response(), original.generation(), original.reasoning());
            assertInstanceOf(ModelResult.Completed.class, port(transport,
                    cipher -> "test-key".getBytes(StandardCharsets.UTF_8)).generate(call));
            if (provider.equals("deepseek")) {
                assertEquals("disabled", requestBody.get().path("thinking").path("type").asText());
            } else {
                assertFalse(requestBody.get().has("thinking"));
            }
        }
    }

    @Test void rejectsUnregisteredProviderProfileBeforeDispatch() {
        var profile = new OpenAiModelPort.ProviderProfile("deepseek", "fixture", "v1",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY,
                "openai-compatible-chat-v1");
        var transport = new OpenAiModelPort.Binding(binding.target(), binding.strategy(),
                binding.endpoint(), binding.encryptedCredential(), profile);
        assertInstanceOf(ModelResult.Failed.class, port(transport,
                cipher -> "test-key".getBytes(StandardCharsets.UTF_8)).generate(ordinary(false)));
        assertEquals(0, requests.get());
    }

    @Test void rejectsMissingRevisionTargetDriftAndDecryptFailureBeforeHttpWithoutPlaintextFallback() {
        var target = binding.target();
        for (var altered : List.of(new FrozenModelTarget(new ModelBindingRef(TENANT, "chat", 99), target.effectiveModelId(),
                target.capabilities(), target.timeout(), target.resolutionRef()),
                new FrozenModelTarget(target.binding(), "replaced-model", target.capabilities(), target.timeout(), target.resolutionRef()))) {
            assertInstanceOf(ModelResult.Failed.class, port(cipher -> "key".getBytes(StandardCharsets.UTF_8))
                    .generate(call(altered, new ResponseFormat.Text(), Instant.now().plusSeconds(5), () -> false, false)));
        }
        var failure = assertInstanceOf(ModelResult.Failed.class, port(cipher -> { throw new IllegalStateException("provider-private-secret"); })
                .generate(call(target, new ResponseFormat.Text(), Instant.now().plusSeconds(5), () -> false, false)));
        assertEquals("model_pre_dispatch_rejected", failure.failure().reasonCode());
        assertFalse(failure.toString().contains("provider-private-secret")); assertEquals(0, requests.get());
    }

    @Test void rejectsFrozenProtocolAuthenticationAndDialectDriftBeforeDecryptOrHttp() {
        var original = binding.providerProfile();
        for (var altered : List.of(
                new OpenAiModelPort.ProviderProfile(original.providerId(), original.serviceId(),
                        original.profileVersion(), ModelProtocol.OPENAI_RESPONSES,
                        AuthenticationScheme.fromId(original.authenticationScheme()),
                        original.dialectVersion()),
                new OpenAiModelPort.ProviderProfile(original.providerId(), original.serviceId(),
                        original.profileVersion(), ModelProtocol.fromId(original.protocol()),
                        AuthenticationScheme.ANTHROPIC_API_KEY, original.dialectVersion()),
                new OpenAiModelPort.ProviderProfile(original.providerId(), original.serviceId(),
                        original.profileVersion(), ModelProtocol.fromId(original.protocol()),
                        AuthenticationScheme.fromId(original.authenticationScheme()),
                        "drifted-dialect"))) {
            var decryptCalls = new AtomicInteger();
            var transportBinding = new OpenAiModelPort.Binding(binding.target(),
                    binding.strategy(), binding.endpoint(), binding.encryptedCredential(), altered);

            var failed = assertInstanceOf(ModelResult.Failed.class,
                    port(transportBinding, cipher -> {
                        decryptCalls.incrementAndGet();
                        return "test-key".getBytes(StandardCharsets.UTF_8);
                    }).generate(ordinary(false)));

            assertEquals("model_pre_dispatch_rejected", failed.failure().reasonCode());
            assertEquals(0, decryptCalls.get());
            assertEquals(0, requests.get());
        }
    }

    @Test void mapsJsonFormatsAndDistinguishesReportedZeroFromMissingUsage() throws Exception {
        responder.set(exchange -> respond(exchange, 200, "{\"model\":\"frozen-model\",\"choices\":[{\"message\":{\"content\":\"{\\\"z\\\":2,\\\"a\\\":1}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":0}}"));
        for (var format : List.of(new ResponseFormat.JsonObject(), new ResponseFormat.JsonSchema(new TypedPayload("json-schema.v1", "{\"type\":\"object\"}")))) {
            var result = assertInstanceOf(ModelResult.Completed.class, port().generate(call(binding.target(), format, Instant.now().plusSeconds(5), () -> false, false)));
            assertEquals("{\"a\":1,\"z\":2}", result.output().canonicalJson());
            assertEquals(0, result.usage().inputTokens().orElseThrow()); assertTrue(result.usage().outputTokens().isEmpty());
            assertEquals(format instanceof ResponseFormat.JsonObject ? "json_object" : "json_schema", requestBody.get().path("response_format").path("type").textValue());
        }
        assertTrue(requestBody.get().path("response_format").path("json_schema").path("strict").booleanValue());
    }

    @Test void nativeToolCallRetainsWireIdentityAndRawArguments() throws Exception {
        responder.set(exchange -> respond(exchange, 200,
                "{\"model\":\"frozen-model\",\"choices\":[{\"message\":{\"content\":null,"
                        + "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"sf_tool_0\",\"arguments\":\"{\\\"city\\\":\\\"杭州\\\"}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}"));
        ModelCall base = ordinary(false);
        var tool = new ModelTool("sf_tool_0", "opaque-tool-handle", "查询天气",
                new TypedPayload("json-schema.v1",
                        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}"),
                true, com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL, false);
        ModelCall call = new ModelCall(base.context(), base.requestedStrategy(), base.target(),
                base.invocationSnapshotRef(), base.messages(), List.of(tool), ToolChoice.AUTO,
                new ResponseContract(new ResponseFormat.NativeConversation(),
                        EnforcementLevel.STRICT, "native-conversation"),
                base.generation(), base.reasoning());

        var result = assertInstanceOf(ModelResult.Completed.class, port().generate(call));

        assertEquals("tool_calls", result.finishReasonCode());
        assertEquals(new AssistantContent.ToolCall("call-1", "sf_tool_0", "{\"city\":\"杭州\"}"),
                result.assistantTurn().orElseThrow().toolCalls().getFirst());
        assertEquals("sf_tool_0", requestBody.get().path("tools").get(0)
                .path("function").path("name").textValue());
        assertEquals("auto", requestBody.get().path("tool_choice").textValue());
        assertFalse(requestBody.get().path("parallel_tool_calls").booleanValue());
    }

    @Test void nativeToolStreamExposesOpaqueHandleDeltaAndRawTerminal() throws Exception {
        responder.set(exchange -> respond(exchange, 200,
                "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"tool_calls\":[{"
                        + "\"index\":0,\"id\":\"call-1\"}]}}]}\n\n"
                        + "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"tool_calls\":[{"
                        + "\"index\":0,\"function\":{\"name\":\"sf_tool_0\",\"arguments\":\"{\\\"city\\\":\"}}]}}]}\n\n"
                        + "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"tool_calls\":[{"
                        + "\"index\":0,\"function\":{\"arguments\":\"\\\"杭州\\\"}\"}}]}}]}\n\n"
                        + "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{},"
                        + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: {\"model\":\"frozen-model\",\"choices\":[],\"usage\":{"
                        + "\"prompt_tokens\":4,\"completion_tokens\":2}}\n\n"
                        + "data: [DONE]\n\n"));
        ModelCall base = ordinary(true);
        var tool = new ModelTool("sf_tool_0", "opaque-tool-handle", "查询天气",
                new TypedPayload("json-schema.v1", "{\"type\":\"object\"}"), true, com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL, false);
        ModelCall call = new ModelCall(base.context(), base.requestedStrategy(), base.target(),
                base.invocationSnapshotRef(), base.messages(), List.of(tool), ToolChoice.AUTO,
                new ResponseContract(new ResponseFormat.NativeConversation(),
                        EnforcementLevel.STRICT, "native-conversation"),
                base.generation(), base.reasoning());
        var probe = new Probe();
        port().stream(call).subscribe(probe);
        probe.subscription.request(10);

        var delta = assertInstanceOf(ModelStreamEvent.ToolCallDelta.class, probe.next());
        assertEquals("opaque-tool-handle", delta.toolHandle());
        assertEquals("{\"city\":", delta.argumentsDelta());
        var remainder = assertInstanceOf(ModelStreamEvent.ToolCallDelta.class, probe.next());
        assertEquals("\"杭州\"}", remainder.argumentsDelta());
        assertInstanceOf(ModelStreamEvent.UsageDelta.class, probe.next());
        var terminal = assertInstanceOf(ModelStreamEvent.Completed.class, probe.next());
        assertEquals("tool_calls", terminal.result().finishReasonCode());
        assertEquals("sf_tool_0", terminal.result().assistantTurn().orElseThrow().toolCalls().getFirst().wireName());
        assertTrue(probe.done.await(2, TimeUnit.SECONDS));
        assertNull(probe.error.get());
    }

    @Test void blankToolIdentityFragmentsDoNotOverwriteKnownStreamIdentity() throws Exception {
        String prefix = "{\"city\":";
        String suffix = "\"杭州\"}";
        responder.set(exchange -> respond(exchange, 200,
                toolFragment("call-1", "sf_tool_0", prefix)
                        + toolFragment("", "", suffix)
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n"));
        var probe = new Probe();
        port().stream(nativeCall(true, List.of(nativeTool(ModelToolKind.TOOL_CALL)))).subscribe(probe);
        probe.subscription.request(10);

        var first = assertInstanceOf(ModelStreamEvent.ToolCallDelta.class, probe.next());
        assertEquals("call-1", first.callId());
        assertEquals("opaque-tool-handle", first.toolHandle());
        assertEquals(prefix, first.argumentsDelta());
        var second = assertInstanceOf(ModelStreamEvent.ToolCallDelta.class, probe.next());
        assertEquals("call-1", second.callId());
        assertEquals("opaque-tool-handle", second.toolHandle());
        assertEquals(suffix, second.argumentsDelta());
        var terminal = assertInstanceOf(ModelStreamEvent.Completed.class, probe.next());
        assertEquals("sf_tool_0", terminal.result().assistantTurn().orElseThrow().toolCalls().getFirst().wireName());
        assertTrue(probe.done.await(2, TimeUnit.SECONDS));
        assertNull(probe.error.get());
        assertTrue(probe.events.isEmpty());
    }

    @Test void toolArgumentsWaitForStreamIdentityAndFlushWhenIdentityArrives() throws Exception {
        String prefix = "{\"city\":";
        String suffix = "\"杭州\"}";
        responder.set(exchange -> respond(exchange, 200,
                toolFragment("", "", prefix)
                        + toolFragment("call-1", "sf_tool_0", suffix)
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n"));
        var probe = new Probe();
        port().stream(nativeCall(true, List.of(nativeTool(ModelToolKind.TOOL_CALL)))).subscribe(probe);
        probe.subscription.request(10);

        var delta = assertInstanceOf(ModelStreamEvent.ToolCallDelta.class, probe.next());
        assertEquals("call-1", delta.callId());
        assertEquals("opaque-tool-handle", delta.toolHandle());
        assertEquals(prefix + suffix, delta.argumentsDelta());
        var terminal = assertInstanceOf(ModelStreamEvent.Completed.class, probe.next());
        assertEquals("sf_tool_0", terminal.result().assistantTurn().orElseThrow().toolCalls().getFirst().wireName());
        assertTrue(probe.done.await(2, TimeUnit.SECONDS));
        assertNull(probe.error.get());
        assertTrue(probe.events.isEmpty());
    }

    @Test void blankToolIdentityNeverInventsCallOrCompletesStream() throws Exception {
        responder.set(exchange -> respond(exchange, 200,
                "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"tool_calls\":[{"
                        + "\"index\":0,\"id\":\"\",\"function\":{\"name\":\"\","
                        + "\"arguments\":\"{\\\"city\\\":\"}}]}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"\","
                        + "\"function\":{\"name\":\"\",\"arguments\":\"\\\"杭州\\\"}\"}}]}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n"));
        var probe = new Probe();
        port().stream(nativeCall(true, List.of(nativeTool(ModelToolKind.TOOL_CALL)))).subscribe(probe);
        probe.subscription.request(10);

        assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, probe.next());
        assertTrue(probe.done.await(2, TimeUnit.SECONDS));
        assertNull(probe.error.get());
        assertTrue(probe.events.isEmpty());
    }

    @Test void parallelNativeToolCallsPreserveOrderedCalls() throws Exception {
        responder.set(exchange -> respond(exchange, 200,
                "{\"model\":\"frozen-model\",\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":["
                        + "{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"sf_tool_0\",\"arguments\":\"{\\\"a\\\":1}\"}},"
                        + "{\"id\":\"call-2\",\"type\":\"function\",\"function\":{\"name\":\"sf_tool_0\",\"arguments\":\"{\\\"a\\\":2}\"}}"
                        + "]},\"finish_reason\":\"tool_calls\"}]}"));
        var generated = assertInstanceOf(ModelResult.Completed.class, port().generate(nativeToolsCall(false)));
        assertEquals(2, generated.assistantTurn().orElseThrow().toolCalls().size());

        responder.set(exchange -> respond(exchange, 200,
                "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"tool_calls\":[{"
                        + "\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"sf_tool_0\",\"arguments\":\"{\\\"a\\\":1}\"}}]}}]}\n\n"
                        + "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"tool_calls\":[{"
                        + "\"index\":1,\"id\":\"c2\",\"function\":{\"name\":\"sf_tool_0\",\"arguments\":\"{\\\"a\\\":2}\"}}]}}]}\n\n"
                        + "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n"));
        var probe = new Probe();
        port().stream(nativeToolsCall(true)).subscribe(probe);
        probe.subscription.request(10);
        assertInstanceOf(ModelStreamEvent.ToolCallDelta.class, probe.next());
        assertInstanceOf(ModelStreamEvent.ToolCallDelta.class, probe.next());
        var terminal = assertInstanceOf(ModelStreamEvent.Completed.class, probe.next());
        assertEquals(2, terminal.result().assistantTurn().orElseThrow().toolCalls().size());
        assertTrue(probe.done.await(2, TimeUnit.SECONDS));
        assertNull(probe.error.get());
    }

    @Test void filteredToolDeltasPreserveSingleItemDemandUntilTerminal() throws Exception {
        responder.set(exchange -> respond(exchange, 200, """
                data: {"model":"frozen-model","choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"undeclared_tool","arguments":"{"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """));
        var probe = new Probe();
        port().stream(jsonObjectStream()).subscribe(probe);
        // 与 Runtime 一致，每次只请求一个片段；被过滤的增量不能使最终结果永远等待。
        probe.subscription.request(1);
        var terminal = assertInstanceOf(ModelStreamEvent.Failed.class, probe.next());
        assertEquals("model_output_schema_rejected", terminal.failure().reasonCode());
        assertTrue(probe.done.await(2, TimeUnit.SECONDS));
        assertNull(probe.error.get());
        assertTrue(probe.events.isEmpty());
        assertEquals(1, requests.get());
    }

    @Test void classifiesHttpRejectionAndTruncatedOrInvalidSuccessWithoutLeakingProviderText() {
        responder.set(exchange -> respond(exchange, 400, "provider-private-secret"));
        var failed = assertInstanceOf(ModelResult.Failed.class, port().generate(ordinary(false)));
        assertEquals("model_http_rejected", failed.failure().reasonCode()); assertEquals(RetryMode.NONE, failed.failure().retryMode());
        assertTrue(failed.retryAt().isEmpty());
        assertFalse(failed.toString().contains("provider-private-secret"));
        for (String malformed : List.of("provider-private-secret")) {
            responder.set(exchange -> respond(exchange, 200, malformed));
            var unknown = assertInstanceOf(ModelResult.OutcomeUnknown.class, port().generate(ordinary(false)));
            assertEquals("model_outcome_unknown", unknown.failure().reasonCode()); assertEquals(RetryMode.NONE, unknown.failure().retryMode());
            assertFalse(unknown.toString().contains("provider-private-secret"));
        }
        // 单个 usage 字段非法不改变已验证的回答结果；其他字段仍保持供应商报告值。
        responder.set(exchange -> respond(exchange, 200, SUCCESS.replace("\"prompt_tokens\":4", "\"prompt_tokens\":1.5")));
        var partial = assertInstanceOf(ModelResult.Completed.class, port().generate(ordinary(false)));
        assertTrue(partial.usage().inputTokens().isEmpty());
        assertEquals(2L, partial.usage().outputTokens().orElseThrow());
        responder.set(exchange -> { exchange.sendResponseHeaders(200, 5000); exchange.getResponseBody().write('{'); });
        assertInstanceOf(ModelResult.OutcomeUnknown.class, port().generate(ordinary(false)));
        assertEquals(4, requests.get()); // 每次操作仅有一次 HTTP，未知结果不自动重发。
    }

    @Test void mapsRateLimitCooldownToSynchronousAndStreamingFailures() throws Exception {
        Instant retryAt = Instant.ofEpochSecond(Instant.now().getEpochSecond() + 3600);
        responder.set(exchange -> {
            exchange.getResponseHeaders().set(
                    "X-RateLimit-Reset", Long.toString(retryAt.getEpochSecond()));
            respond(exchange, 429, "provider-private-secret");
        });

        var generated = assertInstanceOf(ModelResult.Failed.class,
                port().generate(ordinary(false)));
        assertEquals("model_rate_limited", generated.failure().reasonCode());
        assertEquals(RetryMode.NONE, generated.failure().retryMode());
        assertEquals(Optional.of(retryAt), generated.retryAt());
        assertFalse(generated.toString().contains("provider-private-secret"));
        assertEquals(1, requests.get());

        var streamed = assertInstanceOf(ModelStreamEvent.Failed.class,
                terminal(ordinary(true)));
        assertEquals("model_rate_limited", streamed.failure().reasonCode());
        assertEquals(RetryMode.NONE, streamed.failure().retryMode());
        assertEquals(Optional.of(retryAt), streamed.retryAt());
        assertFalse(streamed.toString().contains("provider-private-secret"));
        assertEquals(2, requests.get());
    }

    @Test void streamsWithSharedSequenceDemandAndExactlyOneTerminalAfterUsageAndDone() throws Exception {
        responder.set(exchange -> respond(exchange, 200, sse()));
        var subscriber = new Probe(); var publisher = port().stream(ordinary(true));
        assertEquals(0, requests.get()); publisher.subscribe(subscriber);
        assertNull(subscriber.events.poll(100, TimeUnit.MILLISECONDS));
        subscriber.subscription.request(1);
        var delta = assertInstanceOf(ModelStreamEvent.ContentDelta.class, subscriber.next()); assertEquals(1, delta.sequence());
        assertEquals("hello", delta.content()); assertNull(subscriber.events.poll(100, TimeUnit.MILLISECONDS));
        subscriber.subscription.request(1);
        var usage = assertInstanceOf(ModelStreamEvent.UsageDelta.class, subscriber.next()); assertEquals(2, usage.sequence());
        assertEquals(2, usage.usage().outputTokens().orElseThrow());
        subscriber.subscription.request(1);
        var terminal = assertInstanceOf(ModelStreamEvent.Completed.class, subscriber.next());
        assertEquals("\"hello\"", terminal.result().output().canonicalJson());
        assertTrue(subscriber.done.await(2, TimeUnit.SECONDS)); assertNull(subscriber.error.get());
        subscriber.subscription.request(10); assertNull(subscriber.events.poll(100, TimeUnit.MILLISECONDS));
        assertTrue(requestBody.get().path("stream_options").path("include_usage").booleanValue());
        assertEquals(1, requests.get());
    }

    @Test void streamFinishChunkMayOmitModelAndUseMessageOrBareFinishReason() throws Exception {
        responder.set(exchange -> respond(exchange, 200,
                "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"content\":\"hel\"}}]}\n\n"
                        + "data: {\"choices\":[{\"message\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n"));
        var messageFinish = new Probe();
        port().stream(ordinary(true)).subscribe(messageFinish);
        messageFinish.subscription.request(10);
        assertEquals("hel", assertInstanceOf(ModelStreamEvent.ContentDelta.class, messageFinish.next()).content());
        assertEquals("lo", assertInstanceOf(ModelStreamEvent.ContentDelta.class, messageFinish.next()).content());
        var messageTerminal = assertInstanceOf(ModelStreamEvent.Completed.class, messageFinish.next());
        assertEquals("\"hello\"", messageTerminal.result().output().canonicalJson());
        assertTrue(messageFinish.done.await(2, TimeUnit.SECONDS));
        assertNull(messageFinish.error.get());

        responder.set(exchange -> respond(exchange, 200,
                "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
                        + "data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n"));
        var bareFinish = new Probe();
        port().stream(ordinary(true)).subscribe(bareFinish);
        bareFinish.subscription.request(10);
        assertEquals("hello", assertInstanceOf(ModelStreamEvent.ContentDelta.class, bareFinish.next()).content());
        var bareTerminal = assertInstanceOf(ModelStreamEvent.Completed.class, bareFinish.next());
        assertEquals("\"hello\"", bareTerminal.result().output().canonicalJson());
        assertTrue(bareFinish.done.await(2, TimeUnit.SECONDS));
        assertNull(bareFinish.error.get());
    }

    @Test void streamWithExplicitFinishReasonMayEndAtEofWithoutDoneSentinel() throws Exception {
        responder.set(exchange -> respond(exchange, 200,
                "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
                        + "data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\n"));
        var probe = new Probe();
        port().stream(ordinary(true)).subscribe(probe);
        probe.subscription.request(10);

        assertEquals("hello", assertInstanceOf(ModelStreamEvent.ContentDelta.class, probe.next()).content());
        var terminal = assertInstanceOf(ModelStreamEvent.Completed.class, probe.next());
        assertEquals("\"hello\"", terminal.result().output().canonicalJson());
        assertEquals("stop", terminal.result().finishReasonCode());
        assertTrue(probe.done.await(2, TimeUnit.SECONDS));
        assertNull(probe.error.get());
    }

    @Test void incompleteSseHasOneUnknownTerminalAndCancellationStopsFurtherDelivery() throws Exception {
        responder.set(exchange -> respond(exchange, 200, "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"));
        var incomplete = new Probe(); port().stream(ordinary(true)).subscribe(incomplete); incomplete.subscription.request(10);
        assertInstanceOf(ModelStreamEvent.ContentDelta.class, incomplete.next());
        assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, incomplete.next()); assertTrue(incomplete.done.await(2, TimeUnit.SECONDS));
        assertNull(incomplete.error.get()); assertTrue(incomplete.events.isEmpty());
        responder.set(exchange -> respond(exchange, 200, sse()));
        var cancelled = new Probe(); port().stream(ordinary(true)).subscribe(cancelled); cancelled.subscription.request(1);
        assertInstanceOf(ModelStreamEvent.ContentDelta.class, cancelled.next()); cancelled.subscription.cancel(); cancelled.subscription.request(10);
        assertNull(cancelled.events.poll(200, TimeUnit.MILLISECONDS)); assertEquals(1, cancelled.done.getCount()); assertNull(cancelled.error.get());
    }

    @Test void structuredEmptyOrReasoningOnlyStreamCompletesAsEmptyObjectNotUnknown() throws Exception {
        responder.set(exchange -> respond(exchange, 200,
                "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"}}]}\n\n"));
        var reasoningOnly = new Probe();
        port().stream(jsonObjectStream()).subscribe(reasoningOnly);
        reasoningOnly.subscription.request(10);
        var reasoningTerminal = assertInstanceOf(ModelStreamEvent.Completed.class, reasoningOnly.next());
        assertEquals("{}", reasoningTerminal.result().output().canonicalJson());
        assertEquals("skillforge.runtime.model.json.v1", reasoningTerminal.result().output().schemaId());
        assertTrue(reasoningOnly.done.await(2, TimeUnit.SECONDS));
        assertNull(reasoningOnly.error.get());

        responder.set(exchange -> respond(exchange, 200,
                "{\"model\":\"frozen-model\",\"choices\":[{\"message\":{\"content\":\"not-json\"},\"finish_reason\":\"stop\"}]}"));
        var prose = assertInstanceOf(ModelResult.Completed.class,
                port().generate(call(binding.target(), new ResponseFormat.JsonObject(),
                        Instant.now().plusSeconds(5), () -> false, false)));
        assertEquals("{}", prose.output().canonicalJson());
    }

    @Test void completedStructuredStreamRejectsTrailingTextAsInvalidDecisionRatherThanUnknown() throws Exception {
        for (String content : List.of("{} trailing", "{} {}", "{}```", "{\"a\":1,\"a\":2}")) {
            String encoded = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(content);
            responder.set(exchange -> respond(exchange, 200,
                    "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"content\":"
                            + encoded + "},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"));
            var probe = new Probe();
            port().stream(jsonObjectStream()).subscribe(probe);
            probe.subscription.request(10);
            assertInstanceOf(ModelStreamEvent.ContentDelta.class, probe.next());
            var terminal = assertInstanceOf(ModelStreamEvent.Completed.class, probe.next());
            assertEquals("{}", terminal.result().output().canonicalJson());
            assertTrue(terminal.result().assistantTurn().isEmpty());
            assertTrue(probe.done.await(2, TimeUnit.SECONDS));
            assertNull(probe.error.get());
        }
    }

    @Test void cancellingStartedStreamClosesInvocationAuditAsUnresolved() throws Exception {
        responder.set(exchange -> respond(exchange, 200, sse()));
        var audit = new RecordingAudit();
        var probe = new Probe();
        port(audit).stream(ordinary(true)).subscribe(probe);
        probe.subscription.request(1);
        assertInstanceOf(ModelStreamEvent.ContentDelta.class, probe.next());

        probe.subscription.cancel();

        assertEquals(1, audit.starts.get());
        assertEquals(0, audit.succeeded.get());
        assertEquals(1, audit.unresolved.get());
    }

    @Test void streamUsageReportsSurviveInterruptionWithoutMarkingTheAttemptSuccessful() throws Exception {
        responder.set(exchange -> respond(exchange, 200, """
                data: {"model":"frozen-model","choices":[{"delta":{"content":"partial"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":4}}

                data: {"choices":[],"usage":{"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":1,"cache_creation_tokens":1},"completion_tokens_details":{"reasoning_tokens":1}}}

                """));
        var audit = new RecordingAudit();
        var probe = new Probe();
        port(audit).stream(ordinary(true)).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);

        assertInstanceOf(ModelStreamEvent.ContentDelta.class, probe.next());
        assertInstanceOf(ModelStreamEvent.UsageDelta.class, probe.next());
        assertInstanceOf(ModelStreamEvent.UsageDelta.class, probe.next());
        assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, probe.next());
        assertTrue(probe.done.await(3, TimeUnit.SECONDS));
        assertEquals(List.of(1L, 2L), List.copyOf(audit.usageRevisions));
        var reported = List.copyOf(audit.reportedUsage).getLast();
        assertEquals(4, reported.inputTokens().orElseThrow());
        assertEquals(2, reported.outputTokens().orElseThrow());
        assertEquals(1, reported.cachedInputTokens().orElseThrow());
        assertEquals(1, reported.cacheWriteInputTokens().orElseThrow());
        assertEquals(1, reported.reasoningOutputTokens().orElseThrow());
        assertEquals(0, audit.succeeded.get());
        assertEquals(1, audit.unresolved.get());
        assertEquals(1, requests.get());
    }

    @Test void invalidCompletedPayloadReportsUsageWithoutRepeatingHttp() {
        responder.set(exchange -> respond(exchange, 200, """
                {"model":"frozen-model","choices":[{"message":{"content":"hello","tool_calls":"invalid"},
                "finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2}}
                """));
        var audit = new RecordingAudit();

        var result = port(audit).generate(ordinary(false));

        assertInstanceOf(ModelResult.OutcomeUnknown.class, result);
        assertEquals(List.of(1L), List.copyOf(audit.usageRevisions));
        var usage = List.copyOf(audit.reportedUsage).getFirst();
        assertEquals(4, usage.inputTokens().orElseThrow());
        assertEquals(2, usage.outputTokens().orElseThrow());
        assertEquals(1, audit.unresolved.get());
        assertEquals(1, requests.get());
    }

    @Test void completedResponseDoesNotReportUsageBeforeModelIdentityIsConfirmed() {
        for (String model : List.of("\"model\":\"other-model\",", "")) {
            responder.set(exchange -> respond(exchange, 200, "{" + model
                    + "\"choices\":[{\"message\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3}}"));
            int before = requests.get();
            var audit = new RecordingAudit();
            var result = assertInstanceOf(ModelResult.OutcomeUnknown.class, port(audit).generate(ordinary(false)));
            assertEquals("model_outcome_unknown", result.failure().reasonCode());
            assertEquals(RetryMode.NONE, result.failure().retryMode());
            assertTrue(audit.reportedUsage.isEmpty());
            assertEquals(0, audit.succeeded.get());
            assertEquals(1, audit.unresolved.get());
            assertEquals(before + 1, requests.get());
        }
    }

    @Test void streamRejectsForeignModelUsageAndPreservesEarlierConfirmedUsage() throws Exception {
        responder.set(exchange -> respond(exchange, 200, """
                data: {"model":"frozen-model","choices":[{"delta":{"content":"partial"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":4}}

                data: {"model":"other-model","choices":[],"usage":{"prompt_tokens":99,"completion_tokens":88}}

                """));
        var audit = new RecordingAudit();
        var probe = new Probe();
        port(audit).stream(ordinary(true)).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);

        assertInstanceOf(ModelStreamEvent.ContentDelta.class, probe.next());
        var usage = assertInstanceOf(ModelStreamEvent.UsageDelta.class, probe.next());
        assertEquals(4, usage.usage().inputTokens().orElseThrow());
        var terminal = assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, probe.next());
        assertEquals("model_outcome_unknown", terminal.failure().reasonCode());
        assertEquals(RetryMode.NONE, terminal.failure().retryMode());
        assertTrue(probe.done.await(3, TimeUnit.SECONDS));
        assertNull(probe.error.get());
        assertTrue(probe.events.isEmpty());
        assertEquals(List.of(1L), List.copyOf(audit.usageRevisions));
        assertEquals(4, audit.reportedUsage.peek().inputTokens().orElseThrow());
        assertTrue(audit.reportedUsage.peek().outputTokens().isEmpty());
        assertEquals(0, audit.succeeded.get());
        assertEquals(1, audit.unresolved.get());
        assertEquals(1, requests.get());
    }

    @Test void streamDoesNotReportUsageBeforeFirstModelIdentity() throws Exception {
        for (String model : List.of("\"model\":\"other-model\",", "")) {
            responder.set(exchange -> respond(exchange, 200, "data: {" + model
                    + "\"choices\":[],\"usage\":{\"prompt_tokens\":9}}\n\n"));
            int before = requests.get();
            var audit = new RecordingAudit();
            var probe = new Probe();
            port(audit).stream(ordinary(true)).subscribe(probe);
            probe.subscription.request(Long.MAX_VALUE);
            var terminal = assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, probe.next());
            assertEquals("model_outcome_unknown", terminal.failure().reasonCode());
            assertTrue(probe.done.await(3, TimeUnit.SECONDS));
            assertNull(probe.error.get());
            assertTrue(probe.events.isEmpty());
            assertTrue(audit.reportedUsage.isEmpty());
            assertEquals(0, audit.succeeded.get());
            assertEquals(1, audit.unresolved.get());
            assertEquals(before + 1, requests.get());
        }
    }

    @Test void receivedToolIdentityFailuresPreserveUsageAndRemainUnknownInBothModes() throws Exception {
        for (boolean stream : List.of(false, true)) {
            for (String field : List.of("id", "name")) {
                for (boolean missing : List.of(false, true)) {
                    // 无参数增量，确保非法身份在 transport 完成组装时被校验。
                    var message = nativeMessage("sf_tool_0", "");
                    var tool = (com.fasterxml.jackson.databind.node.ObjectNode) message.path("tool_calls").get(0);
                    var identity = "id".equals(field) ? tool
                            : (com.fasterxml.jackson.databind.node.ObjectNode) tool.path("function");
                    if (missing) identity.remove(field);
                    else identity.put(field, "private-identity\ncontrol");
                    var root = JSON.createObjectNode().put("model", "frozen-model");
                    root.putArray("choices").addObject().put("finish_reason", "tool_calls")
                            .set(stream ? "delta" : "message", message);
                    root.putObject("usage").put("prompt_tokens", 4).put("completion_tokens", 2);
                    String json = JSON.writeValueAsString(root);
                    String response = stream ? "data: " + json + "\n\ndata: [DONE]\n\n" : json;
                    responder.set(exchange -> respond(exchange, 200, response));
                    int before = requests.get();
                    var audit = new RecordingAudit();
                    var call = nativeCall(stream, List.of(nativeTool(ModelToolKind.TOOL_CALL)));
                    if (stream) {
                        var probe = new Probe();
                        port(audit).stream(call).subscribe(probe);
                        probe.subscription.request(Long.MAX_VALUE);
                        assertTrue(probe.done.await(3, TimeUnit.SECONDS));
                        assertNull(probe.error.get());
                        var events = new ArrayList<ModelStreamEvent>();
                        probe.events.drainTo(events);
                        assertEquals(1, events.stream().filter(event -> event instanceof ModelStreamEvent.UsageDelta).count());
                        var terminal = assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, events.getLast());
                        assertEquals("model_outcome_unknown", terminal.failure().reasonCode());
                        assertEquals(RetryMode.NONE, terminal.failure().retryMode());
                        assertEquals(1, events.stream().filter(event -> event instanceof ModelStreamEvent.OutcomeUnknown
                                || event instanceof ModelStreamEvent.Failed || event instanceof ModelStreamEvent.Completed).count());
                    } else {
                        var result = assertInstanceOf(ModelResult.OutcomeUnknown.class, port(audit).generate(call));
                        assertEquals("model_outcome_unknown", result.failure().reasonCode());
                        assertEquals(RetryMode.NONE, result.failure().retryMode());
                    }
                    assertEquals(List.of(1L), List.copyOf(audit.usageRevisions));
                    var usage = List.copyOf(audit.reportedUsage).getFirst();
                    assertEquals(4, usage.inputTokens().orElseThrow());
                    assertEquals(2, usage.outputTokens().orElseThrow());
                    assertEquals(0, audit.succeeded.get());
                    assertEquals(0, audit.failed.get());
                    assertEquals(1, audit.unresolved.get());
                    assertEquals(before + 1, requests.get(), "非法响应身份不能触发重试");
                }
            }
        }
    }

    @Test void transportEncodingRejectionRemainsDeterministicBeforeHttpInBothModes() throws Exception {
        for (boolean stream : List.of(false, true)) {
            var tool = new ModelTool("sf_tool_0", "opaque-tool-handle", "冻结函数",
                    new TypedPayload("schema.v1", "{"), true, ModelToolKind.TOOL_CALL, true);
            var call = nativeCall(stream, List.of(tool));
            var audit = new RecordingAudit();
            if (stream) {
                var probe = new Probe();
                port(audit).stream(call).subscribe(probe);
                probe.subscription.request(Long.MAX_VALUE);
                var terminal = assertInstanceOf(ModelStreamEvent.Failed.class, probe.next());
                assertEquals("model_pre_dispatch_rejected", terminal.failure().reasonCode());
                assertTrue(probe.done.await(3, TimeUnit.SECONDS));
                assertNull(probe.error.get());
                assertTrue(probe.events.isEmpty());
            } else {
                var result = assertInstanceOf(ModelResult.Failed.class, port(audit).generate(call));
                assertEquals("model_pre_dispatch_rejected", result.failure().reasonCode());
            }
            assertEquals(1, audit.starts.get());
            assertEquals(1, audit.failed.get());
            assertEquals(0, audit.unresolved.get());
            assertTrue(audit.reportedUsage.isEmpty());
            assertEquals(0, requests.get());
        }
    }

    @Test void invalidStreamDeltaDeliversUsageBeforeTheUnknownTerminal() throws Exception {
        responder.set(exchange -> respond(exchange, 200, """
                data: {"model":"frozen-model","choices":[{"delta":{"tool_calls":"invalid"}}],"usage":{"prompt_tokens":4,"completion_tokens":2}}

                data: [DONE]

                """));
        var audit = new RecordingAudit();
        var probe = new Probe();
        port(audit).stream(ordinary(true)).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);

        var usage = assertInstanceOf(ModelStreamEvent.UsageDelta.class, probe.next()).usage();
        assertEquals(4, usage.inputTokens().orElseThrow());
        assertEquals(2, usage.outputTokens().orElseThrow());
        assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, probe.next());
        assertTrue(probe.done.await(3, TimeUnit.SECONDS));
        assertEquals(List.of(1L), List.copyOf(audit.usageRevisions));
        assertEquals(1, audit.unresolved.get());
        assertEquals(1, requests.get());
    }

    @Test void responsesConflictingOutputReportsUsageToAuditBeforeUnknown() throws Exception {
        binding = bindings.publish(TENANT, "chat", Optional.empty(), new JdbcModelBindings.Profile(
                binding.strategy(), binding.target().capabilities().responseFormats(), true, false,
                Duration.ofSeconds(5)), new JdbcModelBindings.Source(binding.sourceReference(),
                new JdbcModelBindings.ProviderConfiguration(binding.target().effectiveModelId(), binding.endpoint(),
                        new JdbcModelBindings.ProviderProfile("custom", "custom-openai-compatible", "custom-v1",
                                "openai-responses", "bearer-api-key", "openai-responses-v1")), binding.encryptedCredential()));
        responder.set(exchange -> respond(exchange, 200, """
                event: response.created
                data: {"type":"response.created","sequence_number":0,"response":{"id":"r1","object":"response"}}

                event: response.completed
                data: {"type":"response.completed","sequence_number":1,"response":{"id":"r1","object":"response","model":"frozen-model","status":"completed","output":[{"id":"m1","type":"message","role":"assistant","content":[{"type":"output_text","text":"unmatched"}]}],"usage":{"input_tokens":10,"output_tokens":2}}}

                """));
        var audit = new RecordingAudit();
        var probe = new Probe();
        port(audit).stream(nativeCall(true, List.of())).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);

        var usage = assertInstanceOf(ModelStreamEvent.UsageDelta.class, probe.next()).usage();
        assertEquals(10, usage.inputTokens().orElseThrow());
        assertEquals(2, usage.outputTokens().orElseThrow());
        assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, probe.next());
        assertTrue(probe.done.await(3, TimeUnit.SECONDS));
        assertEquals(List.of(1L), List.copyOf(audit.usageRevisions));
        assertEquals(1, audit.unresolved.get());
        assertEquals(0, audit.succeeded.get());
        assertEquals(1, requests.get());
    }

    @Test void responsesSynchronousAuditOnlyAcceptsUsageForConfirmedModel() {
        binding = bindings.publish(TENANT, "chat", Optional.empty(), new JdbcModelBindings.Profile(
                binding.strategy(), binding.target().capabilities().responseFormats(), true, false,
                Duration.ofSeconds(5)), new JdbcModelBindings.Source(binding.sourceReference(),
                new JdbcModelBindings.ProviderConfiguration(binding.target().effectiveModelId(), binding.endpoint(),
                        new JdbcModelBindings.ProviderProfile("custom", "custom-openai-compatible", "custom-v1",
                                "openai-responses", "bearer-api-key", "openai-responses-v1")), binding.encryptedCredential()));
        String body = """
                {"id":"r1","object":"response","model":"frozen-model","status":"completed",
                 "output":[{"id":"m1","type":"message","role":"user","content":[{"type":"output_text","text":"invalid"}]}],
                 "usage":{"input_tokens":10,"output_tokens":2}}
                """;
        for (String response : List.of(body.replace("frozen-model", "foreign-model"),
                body.replace("\"model\":\"frozen-model\",", ""), body)) {
            responder.set(exchange -> respond(exchange, 200, response));
            int before = requests.get();
            var audit = new RecordingAudit();
            var result = assertInstanceOf(ModelResult.OutcomeUnknown.class,
                    port(audit).generate(nativeCall(false, List.of())));
            assertEquals("model_outcome_unknown", result.failure().reasonCode());
            assertEquals(RetryMode.NONE, result.failure().retryMode());
            if (response.equals(body)) {
                assertEquals(List.of(1L), List.copyOf(audit.usageRevisions));
                assertEquals(10, audit.reportedUsage.peek().inputTokens().orElseThrow());
                assertEquals(2, audit.reportedUsage.peek().outputTokens().orElseThrow());
            } else {
                assertTrue(audit.reportedUsage.isEmpty());
            }
            assertEquals(0, audit.succeeded.get());
            assertEquals(0, audit.failed.get());
            assertEquals(1, audit.unresolved.get());
            assertEquals(before + 1, requests.get());
        }
    }

    @Test void terminalAuditFailureDowngradesAndStillDeliversExactlyOneTerminal() throws Exception {
        responder.set(exchange -> respond(exchange, 200, sse()));
        var audit = new RecordingAudit();
        audit.throwOnSucceeded.set(true);
        var probe = new Probe();
        port(audit).stream(ordinary(true)).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);

        assertInstanceOf(ModelStreamEvent.ContentDelta.class, probe.next());
        assertInstanceOf(ModelStreamEvent.UsageDelta.class, probe.next());
        assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, probe.next());
        assertTrue(probe.done.await(3, TimeUnit.SECONDS));
        assertNull(probe.error.get());
        var delivered = new ArrayList<ModelStreamEvent>();
        probe.events.drainTo(delivered);
        assertTrue(delivered.stream().noneMatch(event -> event instanceof ModelStreamEvent.Completed
                || event instanceof ModelStreamEvent.Failed
                || event instanceof ModelStreamEvent.OutcomeUnknown));
        assertEquals(1, audit.succeeded.get());
        assertEquals(0, audit.unresolved.get());
    }

    @Test void usageAndUnresolvedAuditFailuresDoNotSuppressTheRuntimeTerminal() throws Exception {
        responder.set(exchange -> respond(exchange, 200, sse()));
        var audit = new RecordingAudit();
        audit.throwOnUsageReported.set(true);
        audit.throwOnUnresolved.set(true);
        var probe = new Probe();
        port(audit).stream(ordinary(true)).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);

        assertInstanceOf(ModelStreamEvent.ContentDelta.class, probe.next());
        assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, probe.next());
        assertTrue(probe.done.await(3, TimeUnit.SECONDS));
        assertNull(probe.error.get());
        assertEquals(1, audit.usageRevisions.size());
        assertEquals(1, audit.unresolved.get());
        assertTrue(probe.events.isEmpty());
    }

    @Test void nativeCompletedFrameUsageAuditFailureRemainsUnknownAfterDispatch() throws Exception {
        responder.set(exchange -> respond(exchange, 200, """
                data: {"model":"frozen-model","choices":[{"delta":{"content":"hello"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":4}}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2}}

                data: [DONE]

                """));
        for (boolean earlierUsage : List.of(false, true)) {
            int before = requests.get();
            var audit = new RecordingAudit();
            audit.throwOnUsageRevision.set(earlierUsage ? 2 : 1);
            var probe = new Probe();
            portWithFilteredUsage(audit, earlierUsage, false).stream(nativeCall(true, List.of())).subscribe(probe);
            probe.subscription.request(Long.MAX_VALUE);
            if (earlierUsage) {
                var usage = assertInstanceOf(ModelStreamEvent.UsageDelta.class, probe.next());
                assertEquals(4, usage.usage().inputTokens().orElseThrow());
                assertTrue(usage.usage().outputTokens().isEmpty());
            }
            var terminal = assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, probe.next());
            assertEquals("model_outcome_unknown", terminal.failure().reasonCode());
            assertEquals(RetryMode.NONE, terminal.failure().retryMode());
            assertTrue(probe.done.await(3, TimeUnit.SECONDS));
            assertNull(probe.error.get());
            assertTrue(probe.events.isEmpty());
            assertEquals(earlierUsage ? List.of(1L, 2L) : List.of(1L), List.copyOf(audit.usageRevisions));
            assertEquals(2, List.copyOf(audit.reportedUsage).getLast().outputTokens().orElseThrow());
            assertEquals(1, audit.starts.get());
            assertEquals(0, audit.failed.get());
            assertEquals(0, audit.succeeded.get());
            assertEquals(1, audit.unresolved.get());
            assertEquals(before + 1, requests.get());
        }
    }

    @Test void nativeCompletedFrameOutputGovernanceFailureDependsOnTerminalAudit() throws Exception {
        responder.set(exchange -> respond(exchange, 200, sse()));
        for (boolean failAudit : List.of(false, true)) {
            int before = requests.get();
            var audit = new RecordingAudit();
            audit.throwOnFailed.set(failAudit);
            var probe = new Probe();
            portWithFilteredUsage(audit, false, true).stream(nativeCall(true, List.of())).subscribe(probe);
            probe.subscription.request(Long.MAX_VALUE);
            var terminal = probe.next();
            if (failAudit) {
                var unknown = assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, terminal);
                assertEquals("model_outcome_unknown", unknown.failure().reasonCode());
                assertEquals(RetryMode.NONE, unknown.failure().retryMode());
            } else {
                var failed = assertInstanceOf(ModelStreamEvent.Failed.class, terminal);
                assertEquals("model_output_schema_rejected", failed.failure().reasonCode());
            }
            assertTrue(probe.done.await(3, TimeUnit.SECONDS));
            assertNull(probe.error.get());
            assertTrue(probe.events.isEmpty());
            assertEquals(List.of(1L), List.copyOf(audit.usageRevisions));
            assertEquals(1, audit.failed.get());
            assertEquals(0, audit.succeeded.get());
            assertEquals(0, audit.unresolved.get());
            assertEquals(before + 1, requests.get());
        }
    }

    @Test void nativeGenerateAuditFailureNeverAddsAnotherTerminalAudit() {
        responder.set(exchange -> respond(exchange, 200, SUCCESS));
        for (String scenario : List.of("succeeded", "failed", "governance", "usage")) {
            int before = requests.get();
            var audit = new RecordingAudit();
            audit.throwOnSucceeded.set(scenario.equals("succeeded"));
            audit.throwOnFailed.set(scenario.equals("failed"));
            audit.throwOnUsageReported.set(scenario.equals("usage"));
            boolean invalidOutput = scenario.equals("failed") || scenario.equals("governance");
            var result = portWithFilteredUsage(audit, false, invalidOutput).generate(nativeCall(false, List.of()));
            if (scenario.equals("governance")) {
                var failed = assertInstanceOf(ModelResult.Failed.class, result);
                assertEquals("model_output_schema_rejected", failed.failure().reasonCode());
            } else {
                var unknown = assertInstanceOf(ModelResult.OutcomeUnknown.class, result);
                assertEquals("model_outcome_unknown", unknown.failure().reasonCode());
                assertEquals(RetryMode.NONE, unknown.failure().retryMode());
            }
            assertEquals(1, audit.starts.get());
            assertEquals(1, audit.usageRevisions.size());
            assertEquals(scenario.equals("succeeded") ? 1 : 0, audit.succeeded.get());
            assertEquals(invalidOutput ? 1 : 0, audit.failed.get());
            assertEquals(scenario.equals("usage") ? 1 : 0, audit.unresolved.get());
            assertEquals(before + 1, requests.get());
        }
    }

    @Test void completedStreamDoesNotReportItsLastUsageSnapshotTwice() throws Exception {
        responder.set(exchange -> respond(exchange, 200, sse()));
        var audit = new RecordingAudit();
        var probe = new Probe();
        port(audit).stream(ordinary(true)).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);

        assertTrue(probe.done.await(3, TimeUnit.SECONDS));
        assertEquals(List.of(1L), List.copyOf(audit.usageRevisions));
        assertEquals(1, audit.succeeded.get());
        assertEquals(0, audit.unresolved.get());
    }

    @Test void preDispatchStreamFailureSuppressesCompletionOnlyWhenCancelledInTerminal() throws Exception {
        for (boolean cancelOnNext : List.of(false, true)) {
            for (boolean expired : List.of(false, true)) {
                var events = new java.util.concurrent.CopyOnWriteArrayList<ModelStreamEvent>();
                var completes = new AtomicInteger(); var errors = new AtomicInteger();
                var terminal = new CountDownLatch(1); var worker = new AtomicReference<Thread>();
                var subscription = new AtomicReference<Flow.Subscription>();
                var call = expired
                        ? call(binding.target(), new ResponseFormat.Text(), Instant.now().minusSeconds(1), () -> false, true)
                        : ordinary(false);
                port().stream(call).subscribe(new Flow.Subscriber<ModelStreamEvent>() {
                    public void onSubscribe(Flow.Subscription value) { subscription.set(value); value.request(1); }
                    public void onNext(ModelStreamEvent event) {
                        worker.set(Thread.currentThread()); events.add(event);
                        if (cancelOnNext) subscription.get().cancel();
                        terminal.countDown();
                    }
                    public void onError(Throwable failure) { errors.incrementAndGet(); }
                    public void onComplete() { completes.incrementAndGet(); }
                });
                assertTrue(terminal.await(3, TimeUnit.SECONDS)); worker.get().join(3000);
                assertFalse(worker.get().isAlive(), "pre-dispatch stream worker must finish");
                assertEquals(1, events.size());
                assertEquals("model_pre_dispatch_rejected", assertInstanceOf(ModelStreamEvent.Failed.class, events.getFirst()).failure().reasonCode());
                assertEquals(cancelOnNext ? 0 : 1, completes.get()); assertEquals(0, errors.get());
            }
        }
        assertEquals(0, requests.get());
    }

    @Test void preCancelledAndExpiredCallsHaveZeroHttpAndInFlightDeadlineClosesBlockedBody() throws Exception {
        assertInstanceOf(ModelResult.Failed.class, port().generate(call(binding.target(), new ResponseFormat.Text(), Instant.now().plusSeconds(5), () -> true, false)));
        assertInstanceOf(ModelResult.Failed.class, port().generate(call(binding.target(), new ResponseFormat.Text(), Instant.now().minusSeconds(1), () -> false, false)));
        assertEquals(0, requests.get());
        var received = new CountDownLatch(1);
        responder.set(exchange -> { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            received.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } });
        var result = workers.submit(() -> port().generate(call(binding.target(), new ResponseFormat.Text(), Instant.now().plusMillis(700), () -> false, false)));
        assertTrue(received.await(2, TimeUnit.SECONDS));
        assertInstanceOf(ModelResult.OutcomeUnknown.class, result.get(3, TimeUnit.SECONDS));
        assertEquals(1, release.getCount()); // 服务端仍阻塞，客户端主动关闭 body 才能结束读取。
        assertEquals(1, requests.get());
    }

    @Test void inFlightCancellationClosesBlockedBodyAndReturnsSafeUnknown() throws Exception {
        var received = new CountDownLatch(1); var cancelled = new AtomicBoolean();
        responder.set(exchange -> { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            received.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } });
        var result = workers.submit(() -> port().generate(call(binding.target(), new ResponseFormat.Text(), Instant.now().plusSeconds(5), cancelled::get, false)));
        assertTrue(received.await(2, TimeUnit.SECONDS)); cancelled.set(true);
        assertInstanceOf(ModelResult.OutcomeUnknown.class, result.get(2, TimeUnit.SECONDS)); assertEquals(1, release.getCount());
    }

    @Test void rejectsMethodSubstitutionWithoutHttpForEitherDispatchKind() throws Exception {
        var failed = assertInstanceOf(ModelResult.Failed.class, port().generate(ordinary(true)));
        assertEquals("model_pre_dispatch_rejected", failed.failure().reasonCode());
        var probe = new Probe(); port().stream(ordinary(false)).subscribe(probe);
        assertNull(probe.events.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(1, probe.done.getCount());
        probe.subscription.request(1);
        var terminal = assertInstanceOf(ModelStreamEvent.Failed.class, probe.next());
        assertEquals("model_pre_dispatch_rejected", terminal.failure().reasonCode());
        assertTrue(probe.done.await(2, TimeUnit.SECONDS)); assertNull(probe.error.get());
        assertTrue(probe.events.isEmpty()); assertEquals(0, requests.get());
    }

    @Test void rejectsProviderModelDriftOrAbsenceWithoutRelabelingOutputAsFrozenModel() {
        for (String response : List.of(SUCCESS.replace("frozen-model", "different-model"),
                SUCCESS.replace("\"model\":\"frozen-model\",", ""))) {
            responder.set(exchange -> respond(exchange, 200, response));
            var unknown = assertInstanceOf(ModelResult.OutcomeUnknown.class, port().generate(ordinary(false)));
            assertEquals("model_outcome_unknown", unknown.failure().reasonCode());
            assertEquals(RetryMode.NONE, unknown.failure().retryMode());
        }
        assertEquals(2, requests.get());
    }

    @Test void rejectsFirstAndLaterSseModelDriftWithExactlyOneUnknownTerminal() throws Exception {
        for (String response : List.of(sse().replace("frozen-model", "different-model"),
                sse().replace("\"model\":\"frozen-model\",", ""))) {
            responder.set(exchange -> respond(exchange, 200, response));
            var probe = new Probe(); port().stream(ordinary(true)).subscribe(probe); probe.subscription.request(10);
            assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, probe.next());
            assertTrue(probe.done.await(2, TimeUnit.SECONDS)); assertTrue(probe.events.isEmpty()); assertNull(probe.error.get());
        }
        String driftAfterContent = sse().replace("\"model\":\"frozen-model\",\"choices\":[{\"delta\":{}",
                "\"model\":\"different-model\",\"choices\":[{\"delta\":{}");
        responder.set(exchange -> respond(exchange, 200, driftAfterContent));
        var probe = new Probe(); port().stream(ordinary(true)).subscribe(probe); probe.subscription.request(10);
        assertInstanceOf(ModelStreamEvent.ContentDelta.class, probe.next());
        assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, probe.next());
        assertTrue(probe.done.await(2, TimeUnit.SECONDS)); assertTrue(probe.events.isEmpty()); assertNull(probe.error.get());
        assertEquals(3, requests.get());
    }

    @Test void cancellationWhileDecryptIsBlockedPreventsHttpAfterDecryptReturns() throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var transportThread = new AtomicReference<Thread>();
        var adapter = port(cipher -> {
            transportThread.set(Thread.currentThread()); entered.countDown();
            try {
                if (!resume.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("fixture_decrypt_timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("fixture_decrypt_interrupted");
            }
            return "test-key".getBytes(StandardCharsets.UTF_8);
        });
        var probe = new Probe(); adapter.stream(ordinary(true)).subscribe(probe); probe.subscription.request(10);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); probe.subscription.cancel();
        } finally { resume.countDown(); }
        transportThread.get().join(2000);
        assertFalse(transportThread.get().isAlive());
        assertEquals(0, requests.get()); assertTrue(probe.events.isEmpty());
        assertEquals(1, probe.done.getCount()); assertNull(probe.error.get());
    }

    @Test void silentStreamBodyCannotOutliveDeadline() throws Exception {
        var headersSent = new CountDownLatch(1);
        responder.set(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(": ready\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            headersSent.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
        });
        var probe = new Probe();
        port().stream(call(binding.target(), new ResponseFormat.Text(), Instant.now().plusSeconds(2), () -> false, true)).subscribe(probe);
        probe.subscription.request(10);
        try {
            assertTrue(headersSent.await(2, TimeUnit.SECONDS));
            assertTrue(probe.done.await(5, TimeUnit.SECONDS), "正文停止发送后也必须受 deadline 约束");
            assertNull(probe.error.get());
            assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, probe.next());
            assertTrue(probe.events.isEmpty());
        } finally { release.countDown(); }
    }

    /** 原生正文（含JSON、代码和Markdown）均是答案候选，adapter不解释成动作。 */
    @Test void nativeConversationPreservesDirectTextWithOrWithoutTools() throws Exception {
        for (boolean stream : List.of(false, true)) {
            for (String text : List.of("你好😀", "## 结果\n正文", "{\"type\":\"tool_call\",\"output\":\"literal\"}", "const x = 1;")) {
                for (var tools : List.of(List.<ModelTool>of(), List.of(nativeTool(ModelToolKind.TOOL_CALL)))) {
                    var result = nativeCompleted(stream, JSON.createObjectNode().put("content", text), "stop", tools);
                    assertEquals(ModelPort.TEXT_OUTPUT_SCHEMA, result.output().schemaId());
                    assertEquals(text, JSON.readTree(result.output().canonicalJson()).textValue());
                    assertEquals(new AssistantTurn(List.of(new AssistantContent.Text(text))), result.assistantTurn().orElseThrow());
                    assertTrue(result.assistantTurn().orElseThrow().toolCalls().isEmpty());
                    assertEquals("stop", result.finishReasonCode());
                    assertEquals(tools.isEmpty() ? "" : "auto", requestBody.get().path("tool_choice").asText());
                }
            }
        }
    }

    /** 原始参数不在适配器中改写；完整回合保留旁白、顺序、调用ID和原始JSON。 */
    @Test void nativeConversationPreservesTextAndOrderedRawCalls() throws Exception {
        for (boolean stream : List.of(false, true)) {
            var message = nativeMessage("sf_tool_0", "{ \"n\":1.0, \"large\":9007199254740993 }");
            message.put("content", "我先读取文件");
            message.put("reasoning_content", "私有推理不进入公开正文");
            var calls = (com.fasterxml.jackson.databind.node.ArrayNode) message.path("tool_calls");
            var second = ((com.fasterxml.jackson.databind.node.ObjectNode) calls.get(0)).deepCopy().put("id", "call-2").put("index", 1);
            ((com.fasterxml.jackson.databind.node.ObjectNode) second.path("function")).put("arguments", "{\"n\":2}");
            calls.add(second);
            var result = nativeCompleted(stream, message, "tool_calls", List.of(nativeTool(ModelToolKind.TOOL_CALL)));
            var turn = result.assistantTurn().orElseThrow();
            assertEquals(List.of(new AssistantContent.Text("我先读取文件"),
                    new AssistantContent.ToolCall("call-1", "sf_tool_0", "{ \"n\":1.0, \"large\":9007199254740993 }"),
                    new AssistantContent.ToolCall("call-2", "sf_tool_0", "{\"n\":2}")), turn.contents());
            assertEquals("我先读取文件", JSON.readTree(result.output().canonicalJson()).textValue());
            assertEquals(AssistantTurnKind.TOOL_CALLS, turn.kind());
            assertTrue(requestBody.get().path("parallel_tool_calls").booleanValue());
        }
    }

    /** 参数错误、未知工具和控制动作混批都保留原始事实，由Session作封闭分类。 */
    @Test void nativeConversationLeavesToolValidationToSession() throws Exception {
        for (boolean stream : List.of(false, true)) {
            for (String argument : List.of("", " ", "not-json", "[]", "null", "{} {}", "{\"a\":1,\"a\":2}", "{\"type\":\"dispatch\"}", "{\"n\":1e1001}")) {
                var result = nativeCompleted(stream, nativeMessage("provider_unknown_tool", argument), "tool_calls",
                        List.of(nativeTool(ModelToolKind.TOOL_CALL)));
                assertEquals(new AssistantContent.ToolCall("call-1", "provider_unknown_tool", argument),
                        result.assistantTurn().orElseThrow().toolCalls().getFirst());
                assertEquals("\"\"", result.output().canonicalJson());
            }
            var message = nativeMessage("ask_user", "{\"question\":\"继续吗\"}");
            var calls = (com.fasterxml.jackson.databind.node.ArrayNode) message.path("tool_calls");
            var second = ((com.fasterxml.jackson.databind.node.ObjectNode) calls.get(0)).deepCopy().put("index", 1);
            calls.add(second);
            var result = nativeCompleted(stream, message, "tool_calls", List.of(nativeControl("ask_user")));
            assertEquals(2, result.assistantTurn().orElseThrow().toolCalls().size());
            assertEquals("call-1", result.assistantTurn().orElseThrow().toolCalls().getFirst().callId());
            assertEquals("call-1", result.assistantTurn().orElseThrow().toolCalls().get(1).callId());
        }
    }

    /** 同轮多调用资格由声明决定；parallel_safe=false 仍可先生成多调用再由宿主串行执行。 */
    @Test void nativeParallelFlagUsesDeclaredBatchEligibilityAcrossTheWholeCatalog() throws Exception {
        var source = nativeTool(ModelToolKind.TOOL_CALL);
        var serialRead = new ModelTool("serial_read", "serial-read", "READ_ONLY parallel_safe=false", source.inputSchema(),
                true, ModelToolKind.TOOL_CALL, true);
        var parallelRead = new ModelTool("parallel_read", "parallel-read", "READ_ONLY parallel_safe=true", source.inputSchema(),
                true, ModelToolKind.TOOL_CALL, true);
        var write = new ModelTool("read_named_write", "write", "NON_IDEMPOTENT_WRITE", source.inputSchema(),
                true, ModelToolKind.TOOL_CALL, false);
        var confirmation = new ModelTool("confirmed_read", "confirmation", "READ_ONLY confirmation=ALWAYS", source.inputSchema(),
                true, ModelToolKind.TOOL_CALL, false);
        var resource = new ModelTool("read_skill_resource", "runtime:agent-action:read_skill_resource", "parallel_safe=false",
                source.inputSchema(), true, ModelToolKind.AGENT_ACTION, true);
        var control = nativeControl("ask_user");
        for (boolean stream : List.of(false, true)) {
            for (var tools : List.of(List.<ModelTool>of(), List.of(serialRead), List.of(parallelRead), List.of(write),
                    List.of(confirmation), List.of(control), List.of(resource), List.of(write, control),
                    List.of(serialRead, write, control), List.of(resource, confirmation))) {
                nativeCompleted(stream, JSON.createObjectNode().put("content", "回答"), "stop", tools);
                boolean eligible = tools.stream().anyMatch(ModelTool::batchEligible);
                assertEquals(eligible, requestBody.get().path("parallel_tool_calls").booleanValue());
                // 普通文本合同即使声明可多调用工具，也不能启用 NativeConversation 的多调用生成。
                if (!stream) {
                    nativeResponse(false, JSON.createObjectNode().put("content", "回答"), "stop");
                    var base = nativeCall(false, tools);
                    var text = new ModelCall(base.context(), base.requestedStrategy(), base.target(), base.invocationSnapshotRef(),
                            base.messages(), base.tools(), base.toolChoice(),
                            new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "text"),
                            base.generation(), base.reasoning());
                    assertInstanceOf(ModelResult.Completed.class, port().generate(text));
                    assertFalse(requestBody.get().path("parallel_tool_calls").booleanValue());
                }
            }
        }
    }

    @Test void dispatchedNativePreviewObservesOnlyRealTextWithoutChangingFormalStreamOrCallingTwice() throws Exception {
        nativeResponse(true, JSON.createObjectNode().put("content", "实时正文"), "stop");
        var call = dispatchedCall("preview", true, List.of(new ModelMessage("user", ModelRole.USER, "回答")), List.of());
        var audit = new RecordingAudit();
        var text = new StringBuilder();
        var probe = new Probe();
        port().forDispatch(call, transportBinding(), audit, text::append).stream(call).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.done.await(3, TimeUnit.SECONDS)); assertNull(probe.error.get());
        assertEquals("实时正文", text.toString());
        assertFalse(probe.events.stream().anyMatch(ModelStreamEvent.ContentDelta.class::isInstance));
        var terminal = probe.events.stream().filter(ModelStreamEvent.Completed.class::isInstance)
                .map(ModelStreamEvent.Completed.class::cast).findFirst().orElseThrow();
        assertEquals("实时正文", terminal.result().assistantTurn().orElseThrow().content());
        assertEquals(1, requests.get()); assertEquals(1, audit.succeeded.get());
    }

    @Test void previewDoesNotReceiveReasoningOrNativeToolArguments() throws Exception {
        var message = nativeMessage("sf_tool_0", "{\"secret_argument\":\"private-tool-value\"}");
        message.put("content", "准备查询"); message.put("reasoning_content", "private-reasoning");
        nativeResponse(true, message, "tool_calls");
        var call = dispatchedCall("preview-tool", true, List.of(new ModelMessage("user", ModelRole.USER, "查询")),
                List.of(nativeTool(ModelToolKind.TOOL_CALL)));
        var preview = new StringBuilder(); var probe = new Probe();
        port().forDispatch(call, transportBinding(), new RecordingAudit(), preview::append).stream(call).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.done.await(3, TimeUnit.SECONDS)); assertNull(probe.error.get());
        assertEquals("准备查询", preview.toString()); assertEquals(1, requests.get());
        assertTrue(probe.events.stream().anyMatch(ModelStreamEvent.Completed.class::isInstance));
    }

    @Test void failedPreviewProjectionDoesNotLoseTheAlreadyDispatchedModelResultOrRetry() throws Exception {
        nativeResponse(true, JSON.createObjectNode().put("content", "真实终态"), "stop");
        var call = dispatchedCall("preview-unavailable", true, List.of(new ModelMessage("user", ModelRole.USER, "回答")), List.of());
        var audit = new RecordingAudit(); var callbacks = new AtomicInteger(); var probe = new Probe();
        port().forDispatch(call, transportBinding(), audit, content -> {
            callbacks.incrementAndGet(); throw new IllegalStateException("projection unavailable");
        }).stream(call).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.done.await(3, TimeUnit.SECONDS)); assertNull(probe.error.get());
        assertTrue(probe.events.stream().anyMatch(ModelStreamEvent.Completed.class::isInstance));
        assertEquals(1, callbacks.get()); assertEquals(1, requests.get());
        assertEquals(1, audit.succeeded.get()); assertEquals(0, audit.unresolved.get());
    }

    /** 未经验证的正文增量暂不公开，但完整结果正文能交给Session。 */
    @Test void nativeStreamDefersTextUntilCompleteTurn() throws Exception {
        nativeResponse(true, JSON.createObjectNode().put("content", "实时正文"), "stop");
        var probe = new Probe();
        port().stream(nativeCall(true, List.of(nativeTool(ModelToolKind.TOOL_CALL)))).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.done.await(3, TimeUnit.SECONDS));
        assertNull(probe.error.get());
        assertFalse(probe.events.stream().anyMatch(ModelStreamEvent.ContentDelta.class::isInstance));
        var completed = assertInstanceOf(ModelStreamEvent.Completed.class, probe.next());
        assertEquals("实时正文", completed.result().assistantTurn().orElseThrow().content());
    }

    @Test void emptyLimitedOutputPreservesTerminalAndUsageForTextAndStructuredCalls() throws Exception {
        for (boolean stream : List.of(false, true)) {
            for (String finish : List.of("length", "content_filter")) {
                for (ResponseFormat format : List.of(new ResponseFormat.Text(), new ResponseFormat.JsonObject(),
                        new ResponseFormat.JsonSchema(new TypedPayload("json-schema.v1", "{\"type\":\"object\"}")))) {
                    String choice = stream ? "\"delta\":{}" : "\"message\":{\"content\":null}";
                    String body = "{\"model\":\"frozen-model\",\"choices\":[{" + choice
                            + ",\"finish_reason\":\"" + finish + "\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}";
                    responder.set(exchange -> respond(exchange, 200, stream ? "data: " + body + "\n\ndata: [DONE]\n\n" : body));
                    var call = call(binding.target(), format, Instant.now().plusSeconds(5), () -> false, stream);
                    var audit = new RecordingAudit();
                    var transportBinding = transportBinding();
                    var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
                    var providers = ModelProviderRuntime.runtime(http, Clock.systemUTC(), 100_000);
                    ownedProviders.add(providers);
                    var adapter = new OpenAiModelPort(ref -> Optional.of(transportBinding),
                            cipher -> "test-key".getBytes(StandardCharsets.UTF_8), callToAuthorize -> true,
                            (candidate, output) -> false, http, Clock.systemUTC(), 100_000, providers, audit);
                    int before = requests.get();
                    ModelResult.Completed result;
                    if (stream) {
                        var probe = new Probe();
                        adapter.stream(call).subscribe(probe);
                        probe.subscription.request(Long.MAX_VALUE);
                        assertTrue(probe.done.await(3, TimeUnit.SECONDS));
                        assertNull(probe.error.get());
                        var events = new ArrayList<ModelStreamEvent>();
                        probe.events.drainTo(events);
                        assertTrue(events.stream().anyMatch(ModelStreamEvent.UsageDelta.class::isInstance));
                        result = assertInstanceOf(ModelStreamEvent.Completed.class, events.getLast()).result();
                    } else result = assertInstanceOf(ModelResult.Completed.class, adapter.generate(call));
                    assertEquals(finish, result.finishReasonCode());
                    assertEquals(format instanceof ResponseFormat.Text ? "\"\"" : "{}", result.output().canonicalJson());
                    assertEquals(4, result.usage().inputTokens().orElseThrow());
                    assertEquals(2, result.usage().outputTokens().orElseThrow());
                    assertEquals(1, audit.succeeded.get());
                    assertEquals(List.of(result.usage()), List.copyOf(audit.reportedUsage));
                    assertEquals(before + 1, requests.get());
                }
            }
        }
    }

    @Test void incompleteToolIdentityPreservesLimitedFinishAndUsage() throws Exception {
        for (boolean stream : List.of(false, true)) {
            for (String finish : List.of("length", "content_filter", "stop")) {
                for (boolean missingId : List.of(true, false)) {
                    var message = nativeMessage("sf_tool_0", "{\"partial\":");
                    var tool = (com.fasterxml.jackson.databind.node.ObjectNode) message.path("tool_calls").get(0);
                    if (missingId) tool.remove("id");
                    else ((com.fasterxml.jackson.databind.node.ObjectNode) tool.path("function")).remove("name");
                    var root = JSON.createObjectNode().put("model", "frozen-model");
                    var choice = root.putArray("choices").addObject().put("finish_reason", finish);
                    choice.set(stream ? "delta" : "message", message);
                    if (!stream) root.putObject("usage").put("prompt_tokens", 4).put("completion_tokens", 2);
                    String body = JSON.writeValueAsString(root);
                    responder.set(exchange -> respond(exchange, 200, stream ? "data: " + body
                            + "\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n" : body));
                    var call = nativeCall(stream, List.of(nativeTool(ModelToolKind.TOOL_CALL)));
                    int before = requests.get();
                    if ("stop".equals(finish)) {
                        if (stream) assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, terminal(call));
                        else assertInstanceOf(ModelResult.OutcomeUnknown.class, port().generate(call));
                    } else {
                        var result = stream ? assertInstanceOf(ModelStreamEvent.Completed.class, terminal(call)).result()
                                : assertInstanceOf(ModelResult.Completed.class, port().generate(call));
                        assertEquals(finish, result.finishReasonCode());
                        assertTrue(result.assistantTurn().orElseThrow().toolCalls().isEmpty());
                        assertEquals(4, result.usage().inputTokens().orElseThrow());
                        assertEquals(2, result.usage().outputTokens().orElseThrow());
                    }
                    assertEquals(before + 1, requests.get());
                }
            }
        }
    }

    @Test void structuredLimitedToolAndPartialJsonCannotOverrideFinish() throws Exception {
        for (boolean stream : List.of(false, true)) {
            for (String finish : List.of("length", "content_filter")) {
                var message = nativeMessage("sf_tool_0", "{\"partial\":");
                message.put("content", "{\"partial\":");
                var root = JSON.createObjectNode().put("model", "frozen-model");
                root.putArray("choices").addObject().put("finish_reason", finish).set(stream ? "delta" : "message", message);
                root.putObject("usage").put("prompt_tokens", 4).put("completion_tokens", 2);
                String body = JSON.writeValueAsString(root);
                responder.set(exchange -> respond(exchange, 200, stream ? "data: " + body + "\n\ndata: [DONE]\n\n" : body));
                var call = call(binding.target(), new ResponseFormat.JsonObject(), Instant.now().plusSeconds(5), () -> false, stream);
                var result = stream ? assertInstanceOf(ModelStreamEvent.Completed.class, terminal(call)).result()
                        : assertInstanceOf(ModelResult.Completed.class, port().generate(call));
                assertEquals(finish, result.finishReasonCode());
                assertEquals("{}", result.output().canonicalJson());
                assertEquals(2, result.usage().outputTokens().orElseThrow());
            }
        }
    }

    @Test void limitedMultiToolResponseRetainsOnlyCallsWithCompleteIdentity() throws Exception {
        for (boolean stream : List.of(false, true)) {
            for (String finish : List.of("length", "content_filter", "stop")) {
                var message = nativeMessage("sf_tool_0", "{\"partial\":");
                var calls = (com.fasterxml.jackson.databind.node.ArrayNode) message.path("tool_calls");
                calls.addObject().put("index", 1).put("type", "function")
                        .putObject("function").put("name", "sf_tool_0").put("arguments", "{}");
                var root = JSON.createObjectNode().put("model", "frozen-model");
                root.putArray("choices").addObject().put("finish_reason", finish).set(stream ? "delta" : "message", message);
                root.putObject("usage").put("prompt_tokens", 4).put("completion_tokens", 2);
                String body = JSON.writeValueAsString(root);
                responder.set(exchange -> respond(exchange, 200, stream ? "data: " + body + "\n\ndata: [DONE]\n\n" : body));
                var call = nativeCall(stream, List.of(nativeTool(ModelToolKind.TOOL_CALL)));
                if ("stop".equals(finish)) {
                    if (stream) assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, terminal(call));
                    else assertInstanceOf(ModelResult.OutcomeUnknown.class, port().generate(call));
                } else {
                    var result = stream ? assertInstanceOf(ModelStreamEvent.Completed.class, terminal(call)).result()
                            : assertInstanceOf(ModelResult.Completed.class, port().generate(call));
                    var retained = result.assistantTurn().orElseThrow().toolCalls();
                    assertEquals(1, retained.size());
                    assertEquals("call-1", retained.getFirst().callId());
                    assertEquals("{\"partial\":", retained.getFirst().argumentsJson());
                    assertEquals(2, result.usage().outputTokens().orElseThrow());
                }
            }
        }
    }

    @Test void invalidFirstStreamToolFragmentDoesNotHideLaterValidCall() throws Exception {
        for (String finish : List.of("length", "content_filter", "stop")) {
            responder.set(exchange -> respond(exchange, 200,
                    "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"tool_calls\":["
                            + "{\"function\":{\"arguments\":\"{\"}},"
                            + "{\"index\":1,\"id\":\"call-1\",\"function\":{\"name\":\"sf_tool_0\",\"arguments\":\"{}\"}}]},"
                            + "\"finish_reason\":\"" + finish + "\"}]}\n\n"
                            + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}\n\n"
                            + "data: [DONE]\n\n"));
            var call = nativeCall(true, List.of(nativeTool(ModelToolKind.TOOL_CALL)));
            int before = requests.get();
            if ("stop".equals(finish)) {
                assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, terminal(call));
            } else {
                var result = assertInstanceOf(ModelStreamEvent.Completed.class, terminal(call)).result();
                assertEquals(finish, result.finishReasonCode());
                var calls = result.assistantTurn().orElseThrow().toolCalls();
                assertEquals(1, calls.size());
                assertEquals("call-1", calls.getFirst().callId());
                assertEquals("{}", calls.getFirst().argumentsJson());
                assertEquals(2, result.usage().outputTokens().orElseThrow());
            }
            assertEquals(before + 1, requests.get());
        }
    }

    @Test void limitedStructuredProtocolFactDoesNotRequireCompleteJson() throws Exception {
        var call = call(binding.target(), new ResponseFormat.JsonObject(), Instant.now().plusSeconds(5), () -> false, false);
        var usage = new ModelUsage(OptionalLong.of(4), OptionalLong.of(2));
        for (String finish : List.of("length", "content_filter")) {
            for (String content : List.of("{\"partial\":", "{\"v\":1e1000000}")) {
                var result = OpenAiModelProtocol.completed(call, content, finish, usage,
                        new ResultRef("result"), Optional.empty(), Optional.empty(), Optional.empty());
                assertEquals("{}", result.output().canonicalJson());
                assertEquals(finish, result.finishReasonCode());
                assertEquals(usage, result.usage());
            }
        }
        assertThrows(IOException.class, () -> OpenAiModelProtocol.completed(call, "{\"partial\":", "stop", usage,
                new ResultRef("result"), Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> OpenAiModelProtocol.completed(call, "{\"v\":1e1000000}", "stop", usage,
                new ResultRef("result"), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    /** 已完整结束的空、截断和拒绝响应不是传输未知；Session保留各自原因并决定能否采纳。 */
    @Test void nativeEmptyReasoningOnlyAndLimitedOutputPreserveCompleteFacts() throws Exception {
        for (boolean stream : List.of(false, true)) {
            for (String text : List.of("", "  ")) {
                var result = nativeCompleted(stream, JSON.createObjectNode().put("content", text), "stop", List.of());
                assertEquals(text, result.assistantTurn().orElseThrow().content());
            }
            var onlyReasoning = nativeCompleted(stream,
                    JSON.createObjectNode().putNull("content").put("reasoning_content", "仅思考"), "stop", List.of());
            assertTrue(onlyReasoning.assistantTurn().orElseThrow().contents().isEmpty());
            for (String finish : List.of("length", "content_filter")) {
                var message = nativeMessage("provider_unknown_tool", "{\"partial\":");
                message.put("content", "部分正文");
                var result = nativeCompleted(stream, message, finish, List.of());
                assertEquals(finish, result.finishReasonCode());
                assertEquals("部分正文", result.assistantTurn().orElseThrow().content());
                assertEquals("{\"partial\":", result.assistantTurn().orElseThrow().toolCalls().getFirst().argumentsJson());
            }
            var refusal = nativeCompleted(stream, JSON.createObjectNode().putNull("content").put("refusal", "无法协助"), "stop", List.of());
            assertEquals("content_filter", refusal.finishReasonCode());
            for (var message : List.of(JSON.createObjectNode().put("content", "部分正文"),
                    JSON.createObjectNode().put("reasoning_content", "部分思考"))) {
                nativeResponse(stream, message, null);
                var call = nativeCall(stream, List.of());
                if (stream) assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, terminal(call));
                else assertInstanceOf(ModelResult.OutcomeUnknown.class, port().generate(call));
            }
        }
    }

    /** 过滤的原生正文不消耗下游需求；真实工具增量序号与终态背压保持连续。 */
    @Test void filteredNativeContentKeepsToolAndUsageBackpressureContinuous() throws Exception {
        responder.set(exchange -> respond(exchange, 200,
                "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"content\":\"我先查询天气\"}}]}\n\n"
                        + toolFragment("call-1", "sf_tool_0", "{\"city\":\"杭州\"}")
                        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n"));
        var probe = new Probe();
        port().stream(nativeCall(true, List.of(nativeTool(ModelToolKind.TOOL_CALL)))).subscribe(probe);
        probe.subscription.request(1);
        var tool = assertInstanceOf(ModelStreamEvent.ToolCallDelta.class, probe.next());
        assertEquals(1, tool.sequence());
        assertNull(probe.events.poll(100, TimeUnit.MILLISECONDS));
        probe.subscription.request(1);
        var usage = assertInstanceOf(ModelStreamEvent.UsageDelta.class, probe.next());
        assertEquals(2, usage.sequence());
        assertNull(probe.events.poll(100, TimeUnit.MILLISECONDS));
        probe.subscription.request(1);
        var completed = assertInstanceOf(ModelStreamEvent.Completed.class, probe.next());
        assertEquals("我先查询天气", completed.result().assistantTurn().orElseThrow().content());
        assertEquals("call-1", completed.result().assistantTurn().orElseThrow().toolCalls().getFirst().callId());
        assertTrue(probe.done.await(3, TimeUnit.SECONDS));
        assertTrue(probe.events.isEmpty());
    }

    @Test void nativeStreamingMessageWithoutIndicesKeepsIndependentToolArguments() throws Exception {
        var message = nativeMessage("sf_tool_0", "{\"sample\":1}");
        var calls = (com.fasterxml.jackson.databind.node.ArrayNode) message.path("tool_calls");
        ((com.fasterxml.jackson.databind.node.ObjectNode) calls.get(0)).remove("index");
        var second = ((com.fasterxml.jackson.databind.node.ObjectNode) calls.get(0)).deepCopy().put("id", "call-2");
        ((com.fasterxml.jackson.databind.node.ObjectNode) second.path("function")).put("arguments", "{\"sample\":2}");
        calls.add(second);
        var root = JSON.createObjectNode().put("model", "frozen-model");
        root.putArray("choices").addObject().put("finish_reason", "tool_calls").set("message", message);
        responder.set(exchange -> respond(exchange, 200, "data: " + JSON.writeValueAsString(root) + "\n\ndata: [DONE]\n\n"));
        var result = assertInstanceOf(ModelStreamEvent.Completed.class,
                terminal(nativeCall(true, List.of(nativeTool(ModelToolKind.TOOL_CALL))))).result();
        assertEquals(List.of(new AssistantContent.ToolCall("call-1", "sf_tool_0", "{\"sample\":1}"),
                new AssistantContent.ToolCall("call-2", "sf_tool_0", "{\"sample\":2}")), result.assistantTurn().orElseThrow().contents());
    }

    @Test void nativeHistoryPreservesWireAssociationAndReasoningInBothRequestModes() throws Exception {
        for (boolean stream : List.of(false, true)) {
            nativeResponse(stream, JSON.createObjectNode().put("content", "完成"), "stop");
            var base = nativeCall(stream, List.of(nativeTool(ModelToolKind.TOOL_CALL)));
            var history = List.of(new ModelMessage("assistant", ModelRole.ASSISTANT, "调用前说明",
                            List.of(new ModelToolCall("frozen-call", "historical_wire",
                                            new TypedPayload("args.v1", "{\"q\":\"原问题\"}")),
                                    new ModelToolCall("frozen-call-2", "historical_wire_2",
                                            new TypedPayload("args.v1", "{\"q\":\"第二问\"}"))),
                            Optional.empty(), Optional.of("原始推理\n保留")),
                    new ModelMessage("result", ModelRole.TOOL, "历史结果", List.of(),
                            Optional.of("frozen-call"), Optional.empty()),
                    new ModelMessage("result-2", ModelRole.TOOL, "历史结果二", List.of(),
                            Optional.of("frozen-call-2"), Optional.empty()));
            var call = new ModelCall(base.context(), base.requestedStrategy(), base.target(),
                    base.invocationSnapshotRef(), history, base.tools(), base.toolChoice(),
                    base.response(), base.generation(), base.reasoning());
            if (stream) assertInstanceOf(ModelStreamEvent.Completed.class, terminal(call));
            else assertInstanceOf(ModelResult.Completed.class, port().generate(call));
            var messages = requestBody.get().path("messages");
            assertEquals(3, messages.size());
            assertEquals("调用前说明", messages.get(0).path("content").asText());
            assertEquals("原始推理\n保留", messages.get(0).path("reasoning_content").asText());
            var toolCall = messages.get(0).path("tool_calls").get(0);
            assertEquals("frozen-call", toolCall.path("id").asText());
            assertEquals("historical_wire", toolCall.path("function").path("name").asText());
            assertEquals("{\"q\":\"原问题\"}", toolCall.path("function").path("arguments").asText());
            var secondToolCall = messages.get(0).path("tool_calls").get(1);
            assertEquals("frozen-call-2", secondToolCall.path("id").asText());
            assertEquals("historical_wire_2", secondToolCall.path("function").path("name").asText());
            assertEquals("{\"q\":\"第二问\"}", secondToolCall.path("function").path("arguments").asText());
            assertEquals("tool", messages.get(1).path("role").asText());
            assertEquals("frozen-call", messages.get(1).path("tool_call_id").asText());
            assertEquals("历史结果", messages.get(1).path("content").asText());
            assertEquals("tool", messages.get(2).path("role").asText());
            assertEquals("frozen-call-2", messages.get(2).path("tool_call_id").asText());
            assertEquals("历史结果二", messages.get(2).path("content").asText());
        }
    }

    @Test void nativePartialBodyTimeoutRemainsUnknownInsteadOfRespond() throws Exception {
        for (boolean stream : List.of(false, true)) {
            var received = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            responder.set(exchange -> {
                exchange.sendResponseHeaders(200, 0);
                String body = stream ? "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"content\":\"部分正文\"}}]}\n\n" : "{";
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                received.countDown();
                try { resume.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            try {
                var call = call(binding.target(), new ResponseFormat.NativeConversation(),
                        Instant.now().plusMillis(700), () -> false, stream);
                if (stream) {
                    assertInstanceOf(ModelStreamEvent.OutcomeUnknown.class, terminal(call));
                } else {
                    var result = workers.submit(() -> port().generate(call));
                    assertTrue(received.await(2, TimeUnit.SECONDS));
                    assertInstanceOf(ModelResult.OutcomeUnknown.class, result.get(3, TimeUnit.SECONDS));
                }
                assertEquals(0, received.getCount());
                assertEquals(1, resume.getCount());
            } finally { resume.countDown(); }
        }
    }

    private ModelTool nativeControl(String name) {
        String properties = switch (name) {
            case "run_workflow" -> "\"handle\":{\"type\":\"string\"},\"commentary\":{\"type\":\"string\"}";
            case "use_skill" -> "\"skill_handle\":{\"type\":\"string\"},\"commentary\":{\"type\":\"string\"}";
            default -> "\"target_handle\":{},\"instruction\":{},\"background\":{},\"id\":{},\"ids\":{},\"message\":{},\"commentary\":{}";
        };
        return new ModelTool(name, "runtime:agent-action:" + name, "冻结控制工具",
                new TypedPayload("schema.v1", "{\"type\":\"object\",\"properties\":{" + properties + "},\"additionalProperties\":false}"), true, ModelToolKind.AGENT_ACTION, false);
    }

    private ModelTool nativeTool(ModelToolKind kind) {
        return new ModelTool("sf_tool_0", "opaque-tool-handle", "冻结函数",
                new TypedPayload("schema.v1", "{\"type\":\"object\"}"), true, kind, kind == ModelToolKind.TOOL_CALL);
    }

    private ModelCall nativeCall(boolean stream, List<ModelTool> tools) {
        var base = ordinary(stream);
        return new ModelCall(base.context(), base.requestedStrategy(), base.target(),
                base.invocationSnapshotRef(), base.messages(), tools, tools.isEmpty() ? ToolChoice.NONE : ToolChoice.AUTO,
                new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "native-decision"),
                base.generation(), base.reasoning());
    }

    private static String toolFragment(String id, String name, String arguments) throws IOException {
        var root = JSON.createObjectNode().put("model", "frozen-model");
        var delta = root.putArray("choices").addObject().putObject("delta");
        delta.putArray("tool_calls").addObject().put("index", 0).put("id", id)
                .putObject("function").put("name", name).put("arguments", arguments);
        return "data: " + JSON.writeValueAsString(root) + "\n\n";
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode nativeMessage(String name, String arguments) {
        var message = JSON.createObjectNode().putNull("content");
        message.putArray("tool_calls").addObject().put("index", 0).put("id", "call-1").put("type", "function")
                .putObject("function").put("name", name).put("arguments", arguments);
        return message;
    }

    private void nativeResponse(boolean stream, JsonNode message, String finish) throws Exception {
        var root = JSON.createObjectNode().put("model", "frozen-model");
        var choice = root.putArray("choices").addObject();
        choice.set(stream ? "delta" : "message", message);
        if (finish != null) choice.put("finish_reason", finish);
        String body = JSON.writeValueAsString(root);
        String response = stream ? "data: " + body + "\n\ndata: [DONE]\n\n" : body;
        responder.set(exchange -> respond(exchange, 200, response));
    }

    private ModelResult.Completed nativeCompleted(boolean stream, JsonNode message, String finish,
            List<ModelTool> tools) throws Exception {
        nativeResponse(stream, message, finish);
        var call = nativeCall(stream, tools);
        return stream ? assertInstanceOf(ModelStreamEvent.Completed.class, terminal(call)).result()
                : assertInstanceOf(ModelResult.Completed.class, port().generate(call));
    }

    private ModelStreamEvent terminal(ModelCall call) throws Exception {
        var probe = new Probe();
        port().stream(call).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.done.await(3, TimeUnit.SECONDS));
        assertNull(probe.error.get());
        var events = new ArrayList<ModelStreamEvent>();
        probe.events.drainTo(events);
        var terminals = events.stream().filter(event -> event instanceof ModelStreamEvent.Completed
                || event instanceof ModelStreamEvent.Failed || event instanceof ModelStreamEvent.OutcomeUnknown).toList();
        assertEquals(1, terminals.size());
        assertSame(events.getLast(), terminals.getFirst());
        return terminals.getFirst();
    }

    private OpenAiModelPort port() { return port(cipher -> "test-key".getBytes(StandardCharsets.UTF_8)); }
    private OpenAiModelPort port(ModelInvocationAudit audit) {
        var transportBinding = transportBinding();
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return new OpenAiModelPort(ref -> ref.equals(transportBinding.target().binding())
                ? Optional.of(transportBinding) : Optional.empty(),
                cipher -> "test-key".getBytes(StandardCharsets.UTF_8),
                call -> true, (call, output) -> true, http, Clock.systemUTC(), 100_000,
                com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime.runtime(
                        http, Clock.systemUTC(), 100_000), audit);
    }
    /** 真实 HTTP 流保留完整终态，过滤中途用量以覆盖仅终态报告的适配器路径。 */
    private OpenAiModelPort portWithFilteredUsage(ModelInvocationAudit audit, boolean passFirst, boolean invalidOutput) {
        var transportBinding = transportBinding();
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var clock = Clock.systemUTC();
        var delegate = new OpenAiCompatibleProviderTransport(http, clock, 100_000, sdkResources);
        var transport = new ModelProviderTransport() {
            @Override public List<DiscoveredModel> discover(ModelProvider.DiscoveryRequest request) {
                return delegate.discover(request);
            }
            @Override public ModelProvider.InvocationResult generate(ModelProvider.InvocationRequest request,
                    ModelProvider.ChatCompletionsPolicy policy) { return delegate.generate(request, policy); }
            @Override public Flow.Publisher<ModelProvider.StreamEvent> stream(ModelProvider.InvocationRequest request,
                    ModelProvider.ChatCompletionsPolicy policy) {
                return subscriber -> delegate.stream(request, policy).subscribe(new Flow.Subscriber<>() {
                    private int usages;
                    @Override public void onSubscribe(Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
                    @Override public void onNext(ModelProvider.StreamEvent event) {
                        if (event instanceof ModelProvider.StreamEvent.UsageDelta && !(passFirst && ++usages == 1)) return;
                        subscriber.onNext(event);
                    }
                    @Override public void onError(Throwable error) { subscriber.onError(error); }
                    @Override public void onComplete() { subscriber.onComplete(); }
                });
            }
        };
        var providers = new ModelProviderRuntime(List.of(
                new com.skillforge.runtime.adapter.model.openai.CustomOpenAiCompatibleModelProvider(transport)));
        return new OpenAiModelPort(ref -> ref.equals(transportBinding.target().binding())
                ? Optional.of(transportBinding) : Optional.empty(),
                cipher -> "test-key".getBytes(StandardCharsets.UTF_8), call -> true,
                (call, output) -> { if (invalidOutput) throw new IllegalArgumentException("invalid output"); return true; },
                http, clock, 100_000, providers, audit);
    }
    private OpenAiModelPort port(Function<String, byte[]> decrypt) {
        return port(transportBinding(), decrypt);
    }
    private OpenAiModelPort.Binding transportBinding() {
        return bindings.find(binding.target().binding()).map(value -> new OpenAiModelPort.Binding(
                value.target(), value.strategy(), value.endpoint(), value.encryptedCredential(),
                new OpenAiModelPort.ProviderProfile(value.providerProfile().providerId(),
                        value.providerProfile().serviceId(), value.providerProfile().profileVersion(),
                        ModelProtocol.fromId(value.providerProfile().protocol()),
                        AuthenticationScheme.fromId(
                                value.providerProfile().authenticationScheme()),
                        value.providerProfile().dialectVersion())))
                .orElseThrow();
    }
    private OpenAiModelPort port(OpenAiModelPort.Binding transportBinding,
            Function<String, byte[]> decrypt) {
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var clock = Clock.systemUTC();
        var providers = ModelProviderRuntime.runtime(http, clock, 100_000);
        ownedProviders.add(providers);
        if ("deepseek".equals(transportBinding.providerProfile().providerId())) {
            var chat = new LocalTransport(new OpenAiCompatibleProviderTransport(http, clock, 100_000, sdkResources), binding.endpoint());
            providers = new ModelProviderRuntime(List.of(new DeepSeekModelProvider(chat)));
        }
        return new OpenAiModelPort(ref -> ref.equals(transportBinding.target().binding())
                ? Optional.of(transportBinding) : Optional.empty(), decrypt,
                call -> true, (call, output) -> true, http, clock, 100_000, providers, ModelInvocationAudit.noop());
    }

    /** 正式 DeepSeek 端点通过准入后，仅测试传输改道到当前夹具的本地服务。 */
    private record LocalTransport(ModelProviderTransport delegate, String endpoint) implements ModelProviderTransport {
        @Override public List<DiscoveredModel> discover(ModelProvider.DiscoveryRequest request) {
            return delegate.discover(request);
        }
        @Override public ModelProvider.InvocationResult generate(ModelProvider.InvocationRequest request,
                ModelProvider.ChatCompletionsPolicy policy) {
            return delegate.generate(local(request), policy);
        }
        @Override public Flow.Publisher<ModelProvider.StreamEvent> stream(ModelProvider.InvocationRequest request,
                ModelProvider.ChatCompletionsPolicy policy) {
            return delegate.stream(local(request), policy);
        }
        private ModelProvider.InvocationRequest local(ModelProvider.InvocationRequest request) {
            assertEquals(DEEPSEEK_ENDPOINT, request.endpoint());
            return new ModelProvider.InvocationRequest(endpoint, request.credential(), request.modelId(), request.profile(), request.messages(),
                    request.response(), request.generation(), request.reasoning(), request.tools(), request.toolChoice(),
                    request.timeout(), request.cancelled(), request.mediaReader(), request.maximumRequestBytes(),
                    request.promptCacheScope(), request.prefixCapabilities(), request.beforeSend());
        }
    }
    private ModelCall ordinary(boolean stream) { return call(binding.target(), new ResponseFormat.Text(), Instant.now().plusSeconds(5), () -> false, stream); }
    private ModelCall jsonObjectStream() {
        return call(binding.target(), new ResponseFormat.JsonObject(), Instant.now().plusSeconds(5), () -> false, true);
    }
    private ModelCall nativeToolsCall(boolean stream) {
        ModelCall base = ordinary(stream);
        var tool = new ModelTool("sf_tool_0", "opaque-tool-handle", "查询",
                new TypedPayload("json-schema.v1", "{\"type\":\"object\"}"), true, com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL, false);
        return new ModelCall(base.context(), base.requestedStrategy(), base.target(),
                base.invocationSnapshotRef(), base.messages(), List.of(tool), ToolChoice.AUTO,
                new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "native-conversation"),
                base.generation(), base.reasoning());
    }

    /** 直接派发纯值夹具；验证适配器协议，不代替Store登记或当前IAM授权。 */
    private ModelCall dispatchedCall(String step, boolean stream, List<ModelMessage> messages, List<ModelTool> tools) {
        var input = new InputId("direct-input");
        var owner = SessionOwner.user(new SessionRef(TENANT, "direct-session"), new AgentId("11"), new EndUserId(1));
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(TENANT, "direct-run"), new TraceEnvelope("direct-trace", "direct-span", input, Optional.empty()), new ResultRef("direct-causation"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, owner.requireUser()), owner,
                Set.of("runtime:invoke"), 1, new ResultRef("direct-verified-fixture"));
        var request = new ResultRef("direct-request-" + step);
        var dispatch = new ProviderDispatch(run.runRef(), new ActionRef(TENANT, "direct-action-" + step),
                "direct-action-" + step, "direct-attempt-" + step,
                stream ? PortalCallKind.MODEL_STREAM : PortalCallKind.MODEL_GENERATE, request,
                new ResultRef("direct-capability"), "direct-fingerprint-" + step, 1);
        var context = ProviderCallContext.dispatched(new PortCallContext(run, identity,
                new InvocationControl(Instant.now().plusSeconds(5), () -> false)), dispatch);
        return new ModelCall(context, binding.strategy(), binding.target(), request, messages, tools, tools.isEmpty() ? ToolChoice.NONE : ToolChoice.AUTO,
                new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "direct-native-adapter-fixture"),
                new GenerationOptions(OptionalInt.of(100), OptionalDouble.of(0.25)), ReasoningPolicy.DISABLED);
    }

    /** transport纯值夹具绑定准确派发和调用种类，不声称已经通过生产Store或IAM。 */
    private ModelCall call(FrozenModelTarget target, ResponseFormat format, Instant deadline, CancellationSignal cancellation, boolean stream) {
        var input = new InputId("input"); var owner = SessionOwner.user(new SessionRef(TENANT, "session"), new AgentId("11"), new EndUserId(1));
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(TENANT, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        var action = new ActionRef(TENANT, "action"); var ref = new ResultRef("invocation");
        var dispatch = new ProviderDispatch(run.runRef(), action, action.actionId(), "attempt",
                stream ? PortalCallKind.MODEL_STREAM : PortalCallKind.MODEL_GENERATE,
                ref, new ResultRef("capability"), "fingerprint", 1);
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, owner.requireUser()), owner, Set.of("runtime:invoke"), 1, new ResultRef("verified"));
        return new ModelCall(ProviderCallContext.dispatched(new PortCallContext(run, identity, new InvocationControl(deadline, cancellation)), dispatch),
                binding.strategy(), target, ref, List.of(new ModelMessage("system", ModelRole.SYSTEM, "system"), new ModelMessage("user", ModelRole.USER, "hello")),
                new ResponseContract(format, EnforcementLevel.STRICT, "supported"), new GenerationOptions(OptionalInt.of(100), OptionalDouble.of(0.25)), ReasoningPolicy.DISABLED);
    }
    private static String sse() {
        return "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
                + "data: {\"model\":\"frozen-model\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}\n\n"
                + "data: [DONE]\n\n";
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    private static void sql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    @FunctionalInterface private interface Responder { void respond(HttpExchange exchange) throws IOException; }
    @Test void streamingAuditPreparationDoesNotPinVirtualThreadCarriers() throws Exception {
        responder.set(exchange -> respond(exchange, 200, sse()));
        var audit = new RecordingAudit();
        var probe = new Probe();
        port(audit).stream(ordinary(true)).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.done.await(5, TimeUnit.SECONDS));
        assertNull(probe.error.get());
        assertNotNull(audit.startThread.get());
        assertFalse(audit.startThread.get().isVirtual());
        audit.startThread.get().join(Duration.ofSeconds(2));
        assertFalse(audit.startThread.get().isAlive());
    }

    private static final class RecordingAudit implements ModelInvocationAudit {
        final AtomicReference<Thread> startThread = new AtomicReference<>();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger succeeded = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger unresolved = new AtomicInteger();
        final AtomicBoolean throwOnSucceeded = new AtomicBoolean();
        final AtomicBoolean throwOnFailed = new AtomicBoolean();
        final AtomicBoolean throwOnUsageReported = new AtomicBoolean();
        final AtomicLong throwOnUsageRevision = new AtomicLong();
        final AtomicBoolean throwOnUnresolved = new AtomicBoolean();
        final ConcurrentLinkedQueue<Long> usageRevisions = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<ModelUsage> reportedUsage = new ConcurrentLinkedQueue<>();

        @Override public boolean start(ModelCall call, Provider provider) {
            startThread.set(Thread.currentThread());
            return starts.incrementAndGet() == 1;
        }
        @Override public void succeeded(ModelCall call, Provider provider, ModelUsage usage) {
            succeeded.incrementAndGet();
            if (throwOnSucceeded.get()) throw new IllegalStateException("audit unavailable");
        }
        @Override public void usageReported(ModelCall call, Provider provider, long revision,
                ModelUsage usage) {
            usageRevisions.add(revision);
            reportedUsage.add(usage);
            if (throwOnUsageReported.get() || throwOnUsageRevision.get() == revision)
                throw new IllegalStateException("usage audit unavailable");
        }
        @Override public void failed(ModelCall call, Provider provider, String reasonCode) {
            failed.incrementAndGet();
            if (throwOnFailed.get()) throw new IllegalStateException("failed audit unavailable");
        }
        @Override public void unresolved(ModelCall call, Provider provider, String reasonCode) {
            unresolved.incrementAndGet();
            if (throwOnUnresolved.get()) throw new IllegalStateException("unresolved audit unavailable");
        }
    }
    private static final class Probe implements Flow.Subscriber<ModelStreamEvent> {
        final BlockingQueue<ModelStreamEvent> events = new LinkedBlockingQueue<>(); final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>(); volatile Flow.Subscription subscription;
        public void onSubscribe(Flow.Subscription value) { subscription = value; }
        public void onNext(ModelStreamEvent value) { events.add(value); }
        public void onError(Throwable value) { error.set(value); done.countDown(); }
        public void onComplete() { done.countDown(); }
        ModelStreamEvent next() throws InterruptedException { var value = events.poll(3, TimeUnit.SECONDS); assertNotNull(value); return value; }
    }
}
