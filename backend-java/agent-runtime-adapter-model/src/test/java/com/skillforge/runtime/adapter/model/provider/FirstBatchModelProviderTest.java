package com.skillforge.runtime.adapter.model.provider;

import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient;
import com.skillforge.runtime.adapter.model.openai.CustomOpenAiCompatibleModelProvider;
import com.skillforge.runtime.adapter.model.aliyun_bailian.AliyunBailianModelProvider;
import com.skillforge.runtime.adapter.model.deepseek.DeepSeekModelProvider;
import com.skillforge.runtime.adapter.model.openai.chat.OpenAiCompatibleProviderTransport;

import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Message;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Role;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.StreamEvent;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolChoice;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolDefinition;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelKind;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ResolvedDiscovery;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.VerificationStatus;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FirstBatchModelProviderTest {
    @Test void encodesMediaAtSendBoundaryAndRejectsActualRequestBytesBeforeHttp() {
        var provider = new CustomOpenAiCompatibleModelProvider(transport);
        var base = request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE);
        var owner = new com.skillforge.runtime.api.SessionRef(new com.skillforge.runtime.api.TenantId("tenant"), "session");
        var media = new com.skillforge.runtime.api.media.MediaRef(owner, "image", "0".repeat(64), "image/jpeg", 3, 1, 1);
        var reads = new AtomicInteger();
        var images = List.of(new Message(Role.USER, List.of(new com.skillforge.runtime.api.media.ModelContent.Image(
                media, com.skillforge.runtime.api.media.ImageDetail.AUTO)), null, List.of(), null));
        var call = new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(), images,
                base.response(), base.generation(), base.reasoning(), base.tools(), base.toolChoice(), base.timeout(), base.cancelled(),
                ref -> { reads.incrementAndGet(); return new byte[]{1, 2, 3}; }, 8192, base.beforeSend());
        assertInstanceOf(InvocationResult.Completed.class, provider.generate(call));
        assertEquals(1, reads.get());
        assertEquals("data:image/jpeg;base64,AQID", lastRequest.get().path("messages").get(0).path("content").get(0)
                .path("image_url").path("url").asText());
        var tooSmall = new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(), images,
                base.response(), base.generation(), base.reasoning(), base.tools(), base.toolChoice(), base.timeout(), base.cancelled(),
                ref -> new byte[]{1, 2, 3}, 10, base.beforeSend());
        assertEquals("model_request_bytes_exceeded", assertInstanceOf(InvocationResult.Rejected.class, provider.generate(tooSmall)).reasonCode());
        assertEquals(1, chatRequests.get());
    }

    @Test void sdkConsumesSendAuthorizationExactlyOnceForEachActualRequest() throws Exception {
        var checks = new AtomicInteger();
        var provider = new CustomOpenAiCompatibleModelProvider(transport);
        var request = request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE).withBeforeSend(checks::incrementAndGet);
        assertInstanceOf(InvocationResult.Completed.class, provider.generate(request));
        assertEquals(1, checks.get()); assertEquals(1, chatRequests.get());
        assertInstanceOf(StreamEvent.Completed.class, stream(provider, request).events.getLast());
        assertEquals(2, checks.get()); assertEquals(2, chatRequests.get());
    }

    @Test void sendBoundaryAuthorizationFailureNeverSendsAndRemainsKnown() throws Exception {
        var provider = new CustomOpenAiCompatibleModelProvider(transport);
        var denied = request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE)
                .withBeforeSend(() -> { throw new IllegalArgumentException("revoked"); });
        assertEquals("model_pre_dispatch_rejected", assertInstanceOf(InvocationResult.Rejected.class,
                provider.generate(denied)).reasonCode());
        assertEquals("model_pre_dispatch_rejected", assertInstanceOf(StreamEvent.Rejected.class,
                stream(provider, denied).events.getLast()).reasonCode());
        assertEquals(0, chatRequests.get());
    }

    @Test void mediaPreparationFailureIsKnownBeforeAnyHttp() throws Exception {
        var provider = new CustomOpenAiCompatibleModelProvider(transport);
        var base = request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE);
        var owner = new com.skillforge.runtime.api.SessionRef(new com.skillforge.runtime.api.TenantId("tenant"), "session");
        var media = new com.skillforge.runtime.api.media.MediaRef(owner, "image", "0".repeat(64), "image/jpeg", 3, 1, 1);
        var images = List.of(new Message(Role.USER, List.of(new com.skillforge.runtime.api.media.ModelContent.Image(
                media, com.skillforge.runtime.api.media.ImageDetail.AUTO)), null, List.of(), null));
        var broken = new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(), images,
                base.response(), base.generation(), base.reasoning(), base.tools(), base.toolChoice(), base.timeout(), base.cancelled(),
                ref -> { throw new IllegalStateException("private-media-error"); }, 8192, base.beforeSend());
        assertEquals("model_pre_dispatch_rejected", assertInstanceOf(InvocationResult.Rejected.class,
                provider.generate(broken)).reasonCode());
        assertEquals("model_pre_dispatch_rejected", assertInstanceOf(StreamEvent.Rejected.class,
                stream(provider, broken).events.getLast()).reasonCode());
        assertEquals(0, chatRequests.get());
    }

    @Test void sdkDoesNotFollowRedirectsOrRetryServerFailure() throws Exception {
        var provider = new CustomOpenAiCompatibleModelProvider(transport);
        chatResponse.set(new HttpResponse(307, "", Map.of("Location", endpoint + "/chat/completions")));
        assertInstanceOf(InvocationResult.Rejected.class,
                provider.generate(request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE)));
        assertEquals(1, chatRequests.get());
        chatResponse.set(new HttpResponse(500, "{}"));
        assertInstanceOf(InvocationResult.OutcomeUnknown.class,
                provider.generate(request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE)));
        assertEquals(2, chatRequests.get());
    }

    @Test void cancellingAfterResponseHeadersInterruptsBlockedSdkBodyRead() throws Exception {
        var received = new CountDownLatch(1);
        var releaseBody = new CountDownLatch(1);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/v1/blocked/chat/completions", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            received.countDown();
            try { releaseBody.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        var base = request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE);
        var blocked = new InvocationRequest(endpoint + "/blocked", base.credential(), base.modelId(), base.profile(),
                base.messages(), base.response(), base.generation(), base.reasoning(), base.tools(), base.toolChoice(),
                Duration.ofSeconds(5), cancelled::get, base.beforeSend());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> new CustomOpenAiCompatibleModelProvider(transport).generate(blocked));
            assertTrue(received.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            assertInstanceOf(InvocationResult.OutcomeUnknown.class, future.get(2, TimeUnit.SECONDS));
            assertEquals(1, releaseBody.getCount(), "供应商仍阻塞时 SDK 正文读取必须已终止");
        } finally { releaseBody.countDown(); executor.shutdownNow(); }
    }

    @Test void sdkUsesDeclaredHostConnectTimeoutOrTheCurrentCallTimeout() throws Exception {
        var request = request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE);
        for (var configured : List.of(Optional.of(Duration.ofSeconds(15)), Optional.<Duration>empty())) {
            var builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER);
            configured.ifPresent(builder::connectTimeout);
            try (var resources = new com.skillforge.runtime.adapter.model.transport.SdkHttpResources();
                 var http = builder.build();
                 var call = new com.skillforge.runtime.adapter.model.transport.SdkModelCall(
                         request, Clock.systemUTC(), 100_000, http, resources)) {
                var effective = new AtomicReference<com.openai.core.Timeout>();
                call.client().withOptions(options -> effective.set(options.timeout()));
                assertEquals(configured.orElse(request.timeout()), effective.get().connect());
                assertEquals(request.timeout(), effective.get().request());
                var completed = call.client().chat().completions().create(
                        com.openai.models.chat.completions.ChatCompletionCreateParams.builder()
                                .model(request.modelId()).addUserMessage("question").build());
                assertEquals("hello", completed.choices().getFirst().message().content().orElseThrow());
            }
        }
        assertEquals(2, chatRequests.get());
    }

    @Test void sharedSdkPoolReusesConnectionWithoutSharingCredentialsOrSendHooks() throws Exception {
        var ports = new java.util.ArrayList<Integer>();
        var credentials = new java.util.ArrayList<String>();
        var sent = new AtomicInteger();
        server.createContext("/v1/shared/chat/completions", exchange -> {
            ports.add(exchange.getRemoteAddress().getPort());
            credentials.add(exchange.getRequestHeaders().getFirst("Authorization"));
            chat(exchange);
        });
        var base = request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE);
        try (var resources = new com.skillforge.runtime.adapter.model.transport.SdkHttpResources();
             var http = HttpClient.newHttpClient()) {
            for (var credential : List.of("tenant-a", "tenant-b")) {
                var request = new InvocationRequest(endpoint + "/shared", credential, base.modelId(), base.profile(),
                        base.messages(), base.response(), base.generation(), base.reasoning(), base.tools(), base.toolChoice(),
                        base.timeout(), () -> false, sent::incrementAndGet);
                try (var call = new com.skillforge.runtime.adapter.model.transport.SdkModelCall(
                        request, Clock.systemUTC(), 100_000, http, resources)) {
                    call.client().chat().completions().create(com.openai.models.chat.completions.ChatCompletionCreateParams.builder()
                            .model(request.modelId()).addUserMessage("question").build());
                }
            }
        }
        assertEquals(List.of("Bearer tenant-a", "Bearer tenant-b"), credentials);
        assertEquals(2, sent.get());
        assertEquals(2, ports.size()); assertEquals(ports.getFirst(), ports.getLast());
    }

    @Test void cancellingOneSdkCallLeavesTheOtherSharedPoolCallRunning() throws Exception {
        var parallel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var bothReceived = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var credentials = new java.util.concurrent.ConcurrentHashMap<String, String>();
        parallel.setExecutor(workers);
        parallel.createContext("/v1/chat/completions", exchange -> {
            var credential = exchange.getRequestHeaders().getFirst("Authorization");
            credentials.put(credential, credential);
            exchange.getRequestBody().readAllBytes();
            bothReceived.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                respond(exchange, 200, "{\"model\":\"model-a\",\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        parallel.start();
        var base = request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var url = "http://127.0.0.1:" + parallel.getAddress().getPort() + "/v1";
        var first = new InvocationRequest(url, "tenant-a", base.modelId(), base.profile(), base.messages(), base.response(),
                base.generation(), base.reasoning(), base.tools(), base.toolChoice(), Duration.ofSeconds(5), cancelled::get, () -> { });
        var second = new InvocationRequest(url, "tenant-b", base.modelId(), base.profile(), base.messages(), base.response(),
                base.generation(), base.reasoning(), base.tools(), base.toolChoice(), Duration.ofSeconds(5), () -> false, () -> { });
        try (var local = new OpenAiCompatibleProviderTransport(httpClient(), Clock.systemUTC(), 100_000)) {
            var provider = new CustomOpenAiCompatibleModelProvider(local);
            var a = workers.submit(() -> provider.generate(first));
            var b = workers.submit(() -> provider.generate(second));
            assertTrue(bothReceived.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            assertInstanceOf(InvocationResult.OutcomeUnknown.class, a.get(2, TimeUnit.SECONDS));
            assertFalse(b.isDone(), "one cancellation must leave the other HTTP call open");
            release.countDown();
            assertInstanceOf(InvocationResult.Completed.class, b.get(2, TimeUnit.SECONDS));
            assertEquals(java.util.Set.of("Bearer tenant-a", "Bearer tenant-b"), credentials.keySet());
        } finally {
            release.countDown(); parallel.stop(0); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void runtimeRejectionRespectsTerminalCancellationNormalCompletionAndInvalidDemand() {
        for (var mode : List.of("cancel-on-next", "complete", "cancel-before-demand", "invalid-demand")) {
            var events = new java.util.ArrayList<StreamEvent>();
            var completes = new AtomicInteger(); var errors = new AtomicInteger();
            var subscription = new AtomicReference<Flow.Subscription>();
            ModelProviderRuntime.standard().stream("unknown-provider", request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE))
                    .subscribe(new Flow.Subscriber<StreamEvent>() {
                        public void onSubscribe(Flow.Subscription value) { subscription.set(value); }
                        public void onNext(StreamEvent event) {
                            events.add(event);
                            if (mode.equals("cancel-on-next")) subscription.get().cancel();
                        }
                        public void onError(Throwable failure) { assertInstanceOf(IllegalArgumentException.class, failure); errors.incrementAndGet(); }
                        public void onComplete() { completes.incrementAndGet(); }
                    });
            if (mode.equals("cancel-before-demand")) subscription.get().cancel();
            subscription.get().request(mode.equals("invalid-demand") ? 0 : 1);
            subscription.get().request(1);
            assertEquals(mode.equals("cancel-on-next") || mode.equals("complete") ? 1 : 0, events.size());
            if (!events.isEmpty()) assertEquals("model_provider_profile_invalid", assertInstanceOf(StreamEvent.Rejected.class, events.getFirst()).reasonCode());
            assertEquals(mode.equals("complete") ? 1 : 0, completes.get());
            assertEquals(mode.equals("invalid-demand") ? 1 : 0, errors.get());
        }
        assertEquals(0, chatRequests.get());
    }

    @Test void finiteDemandWaitExitsAtTheSubscriptionDeadline() throws Exception {
        var now = new AtomicReference<>(NOW);
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        var bounded = new OpenAiCompatibleProviderTransport(httpClient(), clock, 100_000, sdkResources);
        var first = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var subscription = new AtomicReference<Flow.Subscription>();
        var received = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var completed = new AtomicInteger();
        var provider = new CustomOpenAiCompatibleModelProvider(bounded);
        try {
            provider.stream(request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE)).subscribe(new Flow.Subscriber<StreamEvent>() {
                public void onSubscribe(Flow.Subscription value) { subscription.set(value); value.request(1); }
                public void onNext(StreamEvent event) { worker.set(Thread.currentThread()); received.incrementAndGet(); first.countDown(); }
                public void onError(Throwable error) { failure.set(error); }
                public void onComplete() { completed.incrementAndGet(); }
            });
            assertTrue(first.await(2, TimeUnit.SECONDS));
            now.set(NOW.plusSeconds(3));
            worker.get().join(2000);
            assertFalse(worker.get().isAlive(), "finite demand provider worker must exit after deadline");
            assertEquals(1, received.get());
            assertNull(failure.get());
            assertEquals(0, completed.get());
        } finally {
            if (subscription.get() != null) subscription.get().cancel();
            var activeWorker = worker.get();
            if (activeWorker != null) {
                activeWorker.join(2000);
                assertFalse(activeWorker.isAlive(), "fixture cancellation must release the provider worker");
            }
        }
    }

    @Test void finiteDemandCanResumeAndCompleteNormally() throws Exception {
        var first = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var subscription = new AtomicReference<Flow.Subscription>();
        var events = new java.util.concurrent.CopyOnWriteArrayList<StreamEvent>();
        var failure = new AtomicReference<Throwable>();
        try {
            new CustomOpenAiCompatibleModelProvider(transport).stream(request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE))
                    .subscribe(new Flow.Subscriber<StreamEvent>() {
                        public void onSubscribe(Flow.Subscription value) { subscription.set(value); value.request(1); }
                        public void onNext(StreamEvent event) { worker.set(Thread.currentThread()); events.add(event); first.countDown(); }
                        public void onError(Throwable error) { failure.set(error); done.countDown(); }
                        public void onComplete() { done.countDown(); }
                    });
            assertTrue(first.await(2, TimeUnit.SECONDS)); assertEquals(1, events.size());
            subscription.get().request(Long.MAX_VALUE);
            assertTrue(done.await(2, TimeUnit.SECONDS)); assertNull(failure.get());
            assertInstanceOf(StreamEvent.Completed.class, events.getLast());
        } finally {
            if (subscription.get() != null) subscription.get().cancel();
            var activeWorker = worker.get();
            if (activeWorker != null) {
                activeWorker.join(2000);
                assertFalse(activeWorker.isAlive(), "fixture cancellation must release the provider worker");
            }
        }
    }

    @Test void invalidDemandAfterFirstDeltaClosesAndWakesTheProviderWorker() throws Exception {
        var first = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var subscription = new AtomicReference<Flow.Subscription>();
        var errors = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var completed = new AtomicInteger();
        try {
            new CustomOpenAiCompatibleModelProvider(transport).stream(request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE))
                    .subscribe(new Flow.Subscriber<StreamEvent>() {
                        public void onSubscribe(Flow.Subscription value) { subscription.set(value); value.request(1); }
                        public void onNext(StreamEvent event) { worker.set(Thread.currentThread()); first.countDown(); }
                        public void onError(Throwable error) { failure.set(error); errors.incrementAndGet(); }
                        public void onComplete() { completed.incrementAndGet(); }
                    });
            assertTrue(first.await(2, TimeUnit.SECONDS));
            subscription.get().request(0); subscription.get().request(0);
            worker.get().join(2000);
            assertFalse(worker.get().isAlive()); assertEquals(1, errors.get());
            assertInstanceOf(IllegalArgumentException.class, failure.get());
            assertEquals(0, completed.get());
        } finally {
            if (subscription.get() != null) subscription.get().cancel();
            var activeWorker = worker.get();
            if (activeWorker != null) {
                activeWorker.join(2000);
                assertFalse(activeWorker.isAlive(), "fixture cancellation must release the provider worker");
            }
        }
    }

    @Test void cancellingInsideTerminalOnNextSuppressesOnComplete() throws Exception {
        var terminal = new CountDownLatch(1);
        var completed = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var worker = new AtomicReference<Thread>();
        var subscription = new AtomicReference<Flow.Subscription>();
        try {
            new CustomOpenAiCompatibleModelProvider(transport).stream(request(new ResponseFormat.Text(), List.of(), ToolChoice.NONE))
                    .subscribe(new Flow.Subscriber<StreamEvent>() {
                        public void onSubscribe(Flow.Subscription value) { subscription.set(value); value.request(Long.MAX_VALUE); }
                        public void onNext(StreamEvent event) {
                            worker.set(Thread.currentThread());
                            if (event instanceof StreamEvent.Completed) {
                                subscription.get().cancel(); terminal.countDown();
                            }
                        }
                        public void onError(Throwable error) { failure.set(error); }
                        public void onComplete() { completed.incrementAndGet(); }
                    });
            assertTrue(terminal.await(2, TimeUnit.SECONDS)); worker.get().join(2000);
            assertFalse(worker.get().isAlive()); assertEquals(0, completed.get());
            assertNull(failure.get());
        } finally {
            if (subscription.get() != null) subscription.get().cancel();
            var activeWorker = worker.get();
            if (activeWorker != null) {
                activeWorker.join(2000);
                assertFalse(activeWorker.isAlive(), "fixture cancellation must release the provider worker");
            }
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
    private final AtomicReference<String> usageResponse = new AtomicReference<>();
    private final AtomicReference<HttpResponse> chatResponse = new AtomicReference<>();
    private final AtomicInteger chatRequests = new AtomicInteger();
    private final com.skillforge.runtime.adapter.model.transport.SdkHttpResources sdkResources =
            new com.skillforge.runtime.adapter.model.transport.SdkHttpResources();
    private HttpServer server;
    private String endpoint;
    private OpenAiCompatibleProviderTransport transport;
    private final java.util.ArrayList<HttpClient> clients = new java.util.ArrayList<>();
    private final AtomicReference<com.fasterxml.jackson.databind.JsonNode> lastRequest =
            new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> respond(exchange, 200,
                "{\"data\":[{\"id\":\"model-b\"},{\"id\":\"model-a\"}]}"));
        server.createContext("/v1/chat/completions", this::chat);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        HttpClient http = httpClient();
        transport = new OpenAiCompatibleProviderTransport(
                http, Clock.fixed(NOW, ZoneOffset.UTC), 100_000);
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        if (transport != null) transport.close();
        sdkResources.close();
        if (server != null) server.stop(0);
        for (var http : clients) {
            http.shutdownNow();
            assertTrue(http.awaitTermination(Duration.ofSeconds(5)), "HTTP 客户端应完成清理");
        }
    }

    private HttpClient httpClient() {
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        clients.add(http);
        return http;
    }

    @Test
    void eachFirstBatchProviderOwnsDiscoveryAndInvocation() {
        for (ModelProvider provider : List.of(
                new DeepSeekModelProvider(transport),
                new AliyunBailianModelProvider(transport),
                new CustomOpenAiCompatibleModelProvider(transport))) {
            var discovered = provider.discover(new ModelProvider.DiscoveryRequest(
                    discovery(provider), "key", Duration.ofSeconds(2)));
            assertEquals(List.of("model-a", "model-b"),
                    discovered.stream().map(
                            OpenAiModelDiscoveryClient.DiscoveredModel::modelId).toList());
            InvocationResult.Completed completed = assertInstanceOf(
                    InvocationResult.Completed.class, provider.generate(request(provider,
                            new ResponseFormat.Text(), List.of(), ToolChoice.NONE)));
            assertEquals("hello", completed.content());
            assertNull(completed.reasoningContent());
        }
    }

    @Test
    void providerProtocolHandlesStructuredToolAndStreamResults() throws Exception {
        ModelProvider provider = new CustomOpenAiCompatibleModelProvider(transport);
        InvocationResult.Completed structured = assertInstanceOf(
                InvocationResult.Completed.class, provider.generate(request(provider,
                        new ResponseFormat.JsonObject(), List.of(), ToolChoice.NONE)));
        assertEquals("{\"ok\":true}", structured.content());

        ToolDefinition tool = new ToolDefinition("get_weather", "Get weather",
                new TypedPayload("json-schema.v1",
                        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"),
                false, com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL, false);
        InvocationResult.Completed toolResult = assertInstanceOf(
                InvocationResult.Completed.class, provider.generate(request(provider,
                        new ResponseFormat.Text(), List.of(tool), ToolChoice.AUTO)));
        assertEquals("get_weather", toolResult.toolCalls().getFirst().name());
        assertEquals("{\"city\":\"Beijing\"}",
                toolResult.toolCalls().getFirst().argumentsJson());

        Probe probe = new Probe();
        provider.stream(request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE,
                        ReasoningPolicy.DEFAULT))
                .subscribe(probe);
        probe.subscription.request(10);
        assertTrue(probe.done.await(2, TimeUnit.SECONDS));
        assertInstanceOf(StreamEvent.ContentDelta.class, probe.events.get(0));
        assertInstanceOf(StreamEvent.ReasoningDelta.class, probe.events.get(1));
        assertInstanceOf(StreamEvent.UsageDelta.class, probe.events.get(2));
        var terminal = assertInstanceOf(StreamEvent.Completed.class, probe.events.get(3));
        assertEquals("reasoning", terminal.result().reasoningContent());
    }

    @Test
    void deepSeekReasoningPolicyKeepsReasoningSeparateAndCanDisableThinking() {
        ModelProvider provider = new DeepSeekModelProvider(transport);
        var enabled = assertInstanceOf(InvocationResult.Completed.class,
                provider.generate(request(provider, new ResponseFormat.Text(), List.of(),
                        ToolChoice.NONE, ReasoningPolicy.DEFAULT)));
        assertEquals("reasoning", enabled.reasoningContent());
        assertTrue(!lastRequest.get().has("thinking"));

        provider.generate(request(provider, new ResponseFormat.Text(), List.of(),
                ToolChoice.NONE, ReasoningPolicy.DISABLED));
        assertEquals("disabled", lastRequest.get().path("thinking").path("type").textValue());
    }

    @Test void deepSeekProjectsMissingAssistantReasoningWithoutChangingEvidenceOrOtherProviders() {
        var history = List.of(new Message(Role.USER, "question", null, List.of()),
                new Message(Role.ASSISTANT, "answer", null, List.of()),
                new Message(Role.ASSISTANT, "", null,
                        List.of(new ModelProvider.ToolCall("call-1", "lookup", "{}")), "original reasoning"),
                new Message(Role.TOOL, "result", "call-1", List.of()));
        for (ModelProvider provider : List.of(new DeepSeekModelProvider(transport),
                new CustomOpenAiCompatibleModelProvider(transport))) {
            for (ReasoningPolicy reasoning : List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.DISABLED)) {
                var base = request(provider, new ResponseFormat.NativeConversation(), List.of(), ToolChoice.NONE, reasoning);
                provider.generate(new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(),
                        history, base.response(), base.generation(), reasoning, base.tools(), base.toolChoice(),
                        base.timeout(), base.cancelled(), base.beforeSend()));
                var messages = lastRequest.get().path("messages");
                boolean required = provider instanceof DeepSeekModelProvider && reasoning == ReasoningPolicy.DEFAULT;
                assertEquals(required, messages.get(1).has("reasoning_content"));
                if (required) assertEquals("", messages.get(1).path("reasoning_content").asText());
                assertTrue(!messages.get(0).has("reasoning_content"));
                assertTrue(!messages.get(3).has("reasoning_content"));
                assertEquals("original reasoning", messages.get(2).path("reasoning_content").asText());
                assertNull(history.get(1).reasoningContent());
            }
        }
    }

    @Test
    void frozenAssistantReasoningAndParallelToolHistoryReachRealProviderInBothModes() throws Exception {
        String firstArguments = "{\"q\":\"杭州\",\"decimal\":0.12345678901234567890123456789}";
        String secondArguments = "{ \"q\": \"宁波\" }";
        var history = List.of(new Message(Role.ASSISTANT, "调用前说明", null,
                        List.of(new ModelProvider.ToolCall("frozen-call-1", "historical_lookup", firstArguments),
                                new ModelProvider.ToolCall("frozen-call-2", "historical_weather", secondArguments)),
                        "先分析\n再调用"),
                new Message(Role.TOOL, "历史结果一", "frozen-call-1", List.of()),
                new Message(Role.TOOL, "历史结果二", "frozen-call-2", List.of()));
        var response = JSON.createObjectNode().put("model", "model-a");
        var choice = response.putArray("choices").addObject().put("finish_reason", "tool_calls");
        var assistant = choice.putObject("message").put("role", "assistant")
                .put("content", "继续查询说明").put("reasoning_content", "继续分析");
        var calls = assistant.putArray("tool_calls");
        calls.addObject().put("id", "new-call-1").put("type", "function").putObject("function")
                .put("name", "lookup").put("arguments", firstArguments);
        calls.addObject().put("id", "new-call-2").put("type", "function").putObject("function")
                .put("name", "weather").put("arguments", secondArguments);
        response.putObject("usage").put("prompt_tokens", 4).put("completion_tokens", 2);

        for (ModelProvider provider : List.of(new DeepSeekModelProvider(transport),
                new CustomOpenAiCompatibleModelProvider(transport))) {
            for (boolean streaming : List.of(false, true)) {
                chatResponse.set(new HttpResponse(200, streaming
                        ? "data: " + response + "\n\ndata: [DONE]\n\n" : response.toString()));
                var base = request(provider, new ResponseFormat.NativeConversation(), List.of(), ToolChoice.NONE,
                        ReasoningPolicy.DEFAULT);
                var call = new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(),
                        history, base.response(), base.generation(), base.reasoning(), base.tools(), base.toolChoice(),
                        base.timeout(), base.cancelled(), base.beforeSend());
                int before = chatRequests.get();
                InvocationResult.Completed completed;
                if (streaming) {
                    Probe probe = stream(provider, call);
                    completed = assertInstanceOf(StreamEvent.Completed.class, probe.events.getLast()).result();
                    assertEquals(2L, probe.events.stream().filter(StreamEvent.ToolCallDelta.class::isInstance).count());
                } else {
                    completed = assertInstanceOf(InvocationResult.Completed.class, provider.generate(call));
                }
                assertEquals(before + 1, chatRequests.get());
                assertEquals("继续查询说明", completed.content());
                assertEquals("继续分析", completed.reasoningContent());
                assertEquals("tool_calls", completed.finishReasonCode());
                assertEquals(List.of(new ModelProvider.ToolCall("new-call-1", "lookup", firstArguments),
                        new ModelProvider.ToolCall("new-call-2", "weather", secondArguments)), completed.toolCalls());
                assertEquals(4, completed.usage().inputTokens().orElseThrow());
                assertEquals(2, completed.usage().outputTokens().orElseThrow());

                var messages = lastRequest.get().path("messages");
                assertEquals(3, messages.size());
                assertEquals("assistant", messages.get(0).path("role").asText());
                assertEquals("调用前说明", messages.get(0).path("content").asText());
                assertEquals("先分析\n再调用", messages.get(0).path("reasoning_content").asText());
                var frozenCalls = messages.get(0).path("tool_calls");
                assertEquals(2, frozenCalls.size());
                assertEquals("frozen-call-1", frozenCalls.get(0).path("id").asText());
                assertEquals("historical_lookup", frozenCalls.get(0).path("function").path("name").asText());
                assertEquals(firstArguments, frozenCalls.get(0).path("function").path("arguments").asText());
                assertEquals("frozen-call-2", frozenCalls.get(1).path("id").asText());
                assertEquals("historical_weather", frozenCalls.get(1).path("function").path("name").asText());
                assertEquals(secondArguments, frozenCalls.get(1).path("function").path("arguments").asText());
                for (int index = 1; index <= 2; index++) {
                    assertEquals("tool", messages.get(index).path("role").asText());
                    assertEquals("frozen-call-" + index, messages.get(index).path("tool_call_id").asText());
                    assertTrue(!messages.get(index).has("reasoning_content"));
                }
                assertEquals("历史结果一", messages.get(1).path("content").asText());
                assertEquals("历史结果二", messages.get(2).path("content").asText());
            }
        }
    }

    @Test void fiftyBlockingSubscribersLeaveVirtualThreadCarriersAvailableAndReleaseReaders() throws Exception {
        var entered = new CountDownLatch(50);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(50);
        var readers = new java.util.concurrent.ConcurrentLinkedQueue<Thread>();
        var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        var provider = new DeepSeekModelProvider(transport);
        var base = request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE);
        var call = new InvocationRequest(base.endpoint(), base.credential(), base.modelId(), base.profile(),
                base.messages(), base.response(), base.generation(), base.reasoning(), base.tools(), base.toolChoice(),
                Duration.ofSeconds(30), base.cancelled(), base.beforeSend());
        try {
            for (int index = 0; index < 50; index++) {
                provider.stream(call).subscribe(new Flow.Subscriber<>() {
                    private boolean blocked;
                    @Override public void onSubscribe(Flow.Subscription value) { value.request(Long.MAX_VALUE); }
                    @Override public void onNext(StreamEvent event) {
                        if (blocked) return;
                        blocked = true;
                        readers.add(Thread.currentThread());
                        // 模拟 Runtime 持有写权监视器后进入 JDBC 的阻塞边界。
                        synchronized (this) {
                            entered.countDown();
                            try { release.await(); }
                            catch (InterruptedException failure) { Thread.currentThread().interrupt(); errors.add(failure); }
                        }
                    }
                    @Override public void onError(Throwable failure) { errors.add(failure); finished.countDown(); }
                    @Override public void onComplete() { finished.countDown(); }
                });
            }
            assertTrue(entered.await(15, TimeUnit.SECONDS), "50 个回调都须在释放前进入");
            assertTrue(readers.stream().noneMatch(Thread::isVirtual));
            var carrierAvailable = new CountDownLatch(1);
            Thread.startVirtualThread(carrierAvailable::countDown);
            assertTrue(carrierAvailable.await(2, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        for (Thread reader : readers) reader.join(Duration.ofSeconds(2));
        assertTrue(readers.stream().noneMatch(Thread::isAlive));
        assertTrue(errors.isEmpty());
    }

    @Test void cancellingWithoutFurtherDemandReleasesStreamReader() throws Exception {
        var delivered = new CountDownLatch(1);
        var reader = new AtomicReference<Thread>();
        var subscription = new AtomicReference<Flow.Subscription>();
        var provider = new DeepSeekModelProvider(transport);
        provider.stream(request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE))
                .subscribe(new Flow.Subscriber<>() {
                    @Override public void onSubscribe(Flow.Subscription value) {
                        subscription.set(value); value.request(1);
                    }
                    @Override public void onNext(StreamEvent event) {
                        reader.set(Thread.currentThread()); delivered.countDown();
                    }
                    @Override public void onError(Throwable failure) { delivered.countDown(); }
                    @Override public void onComplete() { }
                });
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertTrue(reader.get() != null);
        subscription.get().cancel();
        reader.get().join(Duration.ofSeconds(2));
        assertTrue(!reader.get().isAlive());
    }

    @Test
    void providerReportedUsageSurvivesBothResponseModesIncludingZeroAndMissing() throws Exception {
        for (ModelProvider provider : List.of(new DeepSeekModelProvider(transport),
                new AliyunBailianModelProvider(transport))) {
            for (String usage : List.of("{\"prompt_tokens\":137,\"completion_tokens\":29,\"total_tokens\":166}",
                    "{\"prompt_tokens\":137,\"completion_tokens\":0}", "null")) {
                usageResponse.set(usage);
                var completed = assertInstanceOf(InvocationResult.Completed.class,
                        provider.generate(request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE)));
                Probe probe = new Probe();
                provider.stream(request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE)).subscribe(probe);
                probe.subscription.request(Long.MAX_VALUE);
                assertTrue(probe.done.await(5, TimeUnit.SECONDS));
                var streamed = assertInstanceOf(StreamEvent.Completed.class, probe.events.getLast()).result();
                assertTrue(lastRequest.get().path("stream_options").path("include_usage").asBoolean());
                assertEquals(completed.usage(), streamed.usage());
                if (usage.equals("null")) {
                    assertTrue(completed.usage().inputTokens().isEmpty());
                    assertTrue(completed.usage().outputTokens().isEmpty());
                    assertTrue(completed.usage().cachedInputTokens().isEmpty());
                    assertTrue(completed.usage().cacheWriteInputTokens().isEmpty());
                    assertTrue(completed.usage().reasoningOutputTokens().isEmpty());
                } else {
                    assertEquals(137, completed.usage().inputTokens().orElseThrow());
                    assertEquals(usage.contains("29") ? 29 : 0, completed.usage().outputTokens().orElseThrow());
                }
            }
        }
    }

    @Test
    void providerUsageNormalizesAliasesAndKeepsIndependentInvalidFieldsUnknown() throws Exception {
        ModelProvider provider = new DeepSeekModelProvider(transport);
        usageResponse.set("""
                {"input_tokens":137,"output_tokens":29,
                 "prompt_tokens_details":{"cached_tokens":80,"cache_creation_tokens":10},
                 "completion_tokens_details":{"reasoning_tokens":7}}
                """);
        var aliased = assertInstanceOf(InvocationResult.Completed.class,
                provider.generate(request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE))).usage();
        assertEquals(137, aliased.inputTokens().orElseThrow());
        assertEquals(29, aliased.outputTokens().orElseThrow());
        assertEquals(80, aliased.cachedInputTokens().orElseThrow());
        assertEquals(10, aliased.cacheWriteInputTokens().orElseThrow());
        assertEquals(7, aliased.reasoningOutputTokens().orElseThrow());

        usageResponse.set("""
                {"prompt_tokens":"broken","completion_tokens":29,"prompt_cache_hit_tokens":80,
                 "prompt_tokens_details":{"cache_creation_tokens":-1},
                 "completion_tokens_details":{"reasoning_tokens":7}}
                """);
        var partial = assertInstanceOf(InvocationResult.Completed.class,
                provider.generate(request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE))).usage();
        assertTrue(partial.inputTokens().isEmpty());
        assertEquals(29, partial.outputTokens().orElseThrow());
        assertEquals(80, partial.cachedInputTokens().orElseThrow());
        assertTrue(partial.cacheWriteInputTokens().isEmpty());
        assertEquals(7, partial.reasoningOutputTokens().orElseThrow());

        usageResponse.set("""
                {"prompt_tokens":4,"completion_tokens":2,
                 "prompt_tokens_details":{"cached_tokens":5,"cache_creation_tokens":5},
                 "completion_tokens_details":{"reasoning_tokens":3}}
                """);
        var inconsistent = assertInstanceOf(InvocationResult.Completed.class,
                provider.generate(request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE))).usage();
        assertEquals(4, inconsistent.inputTokens().orElseThrow());
        assertEquals(2, inconsistent.outputTokens().orElseThrow());
        assertTrue(inconsistent.cachedInputTokens().isEmpty());
        assertTrue(inconsistent.cacheWriteInputTokens().isEmpty());
        assertTrue(inconsistent.reasoningOutputTokens().isEmpty());

        usageResponse.set("""
                {"cache_read_input_tokens":80,"cache_creation_input_tokens":20,"output_tokens":3}
                """);
        var unsupportedSplit = assertInstanceOf(InvocationResult.Completed.class,
                provider.generate(request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE))).usage();
        assertTrue(unsupportedSplit.inputTokens().isEmpty());
        assertEquals(3, unsupportedSplit.outputTokens().orElseThrow());
        assertTrue(unsupportedSplit.cachedInputTokens().isEmpty());
        assertTrue(unsupportedSplit.cacheWriteInputTokens().isEmpty());
    }

    @Test
    void streamedUsageFramesRemainCumulativeSnapshotsInsteadOfSummedDeltas() throws Exception {
        ModelProvider provider = new CustomOpenAiCompatibleModelProvider(transport);
        chatResponse.set(new HttpResponse(200, """
                data: {"model":"model-a","choices":[{"delta":{"content":"hello"},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"prompt_tokens":4}}

                data: {"choices":[],"usage":{"completion_tokens":2}}

                data: [DONE]

                """));
        Probe probe = stream(provider);
        var first = assertInstanceOf(StreamEvent.UsageDelta.class, probe.events.get(1)).usage();
        var second = assertInstanceOf(StreamEvent.UsageDelta.class, probe.events.get(2)).usage();
        var completed = assertInstanceOf(StreamEvent.Completed.class, probe.events.getLast()).result().usage();
        assertEquals(4, first.inputTokens().orElseThrow());
        assertTrue(first.outputTokens().isEmpty());
        assertEquals(4, second.inputTokens().orElseThrow());
        assertEquals(2, second.outputTokens().orElseThrow());
        assertEquals(second, completed);
    }

    @Test
    void onlyExactBounded400Or413ErrorCodeIsClassifiedAsContextOverflow() throws Exception {
        ModelProvider provider = new CustomOpenAiCompatibleModelProvider(transport);
        String overflow = "{\"error\":{\"message\":\"private\",\"code\":\"context_length_exceeded\"}}";
        for (int status : List.of(400, 413)) {
            chatResponse.set(new HttpResponse(status, overflow));
            assertEquals("model_context_overflow", assertInstanceOf(
                    InvocationResult.Rejected.class, provider.generate(request(provider,
                            new ResponseFormat.Text(), List.of(), ToolChoice.NONE))).reasonCode());
            assertEquals("model_context_overflow", streamTerminal(provider,
                    StreamEvent.Rejected.class).reasonCode());
        }

        for (HttpResponse response : List.of(
                new HttpResponse(400, "{\"error\":{\"message\":\"context_length_exceeded\"}}"),
                new HttpResponse(400, "{\"error\":{\"code\":\"other\",\"message\":\"context_length_exceeded\"}}"),
                new HttpResponse(400, "{broken"),
                new HttpResponse(400, "x".repeat(100_001)))) {
            chatResponse.set(response);
            var generated = assertInstanceOf(
                    InvocationResult.Rejected.class, provider.generate(request(provider,
                            new ResponseFormat.Text(), List.of(), ToolChoice.NONE)));
            assertEquals("model_http_rejected", generated.reasonCode());
            assertTrue(generated.retryAt().isEmpty());
            var streamed = streamTerminal(provider, StreamEvent.Rejected.class);
            assertEquals("model_http_rejected", streamed.reasonCode());
            assertTrue(streamed.retryAt().isEmpty());
        }

        for (int status : List.of(408, 500)) {
            var response = new HttpResponse(status, "provider-private-secret");
            chatResponse.set(response);
            int before = chatRequests.get();
            var generated = assertInstanceOf(
                    InvocationResult.OutcomeUnknown.class, provider.generate(request(provider,
                            new ResponseFormat.Text(), List.of(), ToolChoice.NONE)));
            assertEquals("model_outcome_unknown", generated.reasonCode());
            assertFalse(generated.toString().contains(response.body()));
            assertEquals(before + 1, chatRequests.get());
            var streamed = streamTerminal(provider, StreamEvent.OutcomeUnknown.class);
            assertEquals("model_outcome_unknown", streamed.reasonCode());
            assertFalse(streamed.toString().contains(response.body()));
            assertEquals(before + 2, chatRequests.get());
        }
    }

    @Test
    void exactStandardContentCodesAreKnownRejectionsWithoutRetryInBothModes() throws Exception {
        for (ModelProvider provider : List.of(new DeepSeekModelProvider(transport),
                new AliyunBailianModelProvider(transport), new CustomOpenAiCompatibleModelProvider(transport))) {
            for (String code : List.of("content_filter", "content_policy_violation")) {
                String response = JSON.writeValueAsString(Map.of("error", Map.of(
                        "code", code, "message", "provider-private-secret")));
                assertRejectedBoth(provider, new HttpResponse(400, response, Map.of("Retry-After", "7")),
                        "model_content_rejected", Optional.empty());
            }
        }
    }

    @Test
    void deepSeekRecognizesOnlyItsExactConfirmedContentRiskResponse() throws Exception {
        var provider = new DeepSeekModelProvider(transport);
        for (String message : List.of("Content Exists Risk",
                "Content Exists Risk (request_id: 90254a95-578d-41a1-8cf9-38be5ea810d1)")) {
            String response = JSON.writeValueAsString(Map.of("error", Map.of(
                    "code", "invalid_request_error", "type", "invalid_request_error", "message", message)));
            assertRejectedBoth(provider, new HttpResponse(400, response), "model_content_rejected", Optional.empty());
            for (ModelProvider other : List.of(new AliyunBailianModelProvider(transport),
                    new CustomOpenAiCompatibleModelProvider(transport)))
                assertRejectedBoth(other, new HttpResponse(400, response), "model_http_rejected", Optional.empty());
        }
        var policy = provider.chatCompletionsPolicy(ReasoningPolicy.DEFAULT)
                .withPromptCache(ModelProvider.PromptCacheEncoding.NONE)
                .withOutputTokenField(ModelProvider.OutputTokenField.MAX_TOKENS)
                .withThinkingEncoding(ModelProvider.ThinkingEncoding.NONE);
        assertTrue(policy.contentRejectionPolicy().rejects("invalid_request_error", "invalid_request_error",
                "Content Exists Risk (request_id: 90254a95-578d-41a1-8cf9-38be5ea810d1)"));
    }

    @Test
    void similarContentRiskMessagesAndMalformedResponsesStayGeneric() throws Exception {
        var provider = new DeepSeekModelProvider(transport);
        for (Map<String, Object> error : List.<Map<String, Object>>of(
                Map.of("message", "Content Exists Risk"),
                Map.of("code", "invalid_request_error", "message", "Content Exists Risk"),
                Map.of("code", "other", "type", "invalid_request_error", "message", "Content Exists Risk"),
                Map.of("code", "invalid_request_error", "type", "other", "message", "Content Exists Risk"),
                Map.of("code", "invalid_request_error", "type", "invalid_request_error", "message", "Content Exists Risk: private"),
                Map.of("code", "invalid_request_error", "type", "invalid_request_error", "message", "Content Exists Risk (request_id: private-secret)"),
                Map.of("code", "invalid_request_error", "type", "invalid_request_error", "message", "content exists risk"),
                Map.of("code", "content_filter_more", "message", "content_filter"),
                Map.of("code", "invalid_request_error", "type", "invalid_request_error", "message", Map.of("text", "Content Exists Risk")))) {
            assertRejectedBoth(provider, new HttpResponse(400, JSON.writeValueAsString(Map.of("error", error))),
                    "model_http_rejected", Optional.empty());
        }
        for (String response : List.of("{broken", "x".repeat(100_001),
                "{\"error\":{\"code\":\"content_filter\",\"code\":\"other\"}}"))
            assertRejectedBoth(provider, new HttpResponse(400, response), "model_http_rejected", Optional.empty());
    }

    @Test
    void contentCodeInUncertainHttpStatusCannotBecomeKnownRejection() throws Exception {
        var provider = new DeepSeekModelProvider(transport);
        for (int status : List.of(408, 500)) {
            chatResponse.set(new HttpResponse(status, "{\"error\":{\"code\":\"content_filter\"}}"));
            int before = chatRequests.get();
            assertEquals("model_outcome_unknown", assertInstanceOf(InvocationResult.OutcomeUnknown.class,
                    provider.generate(request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE))).reasonCode());
            assertEquals("model_outcome_unknown", streamTerminal(provider, StreamEvent.OutcomeUnknown.class).reasonCode());
            assertEquals(before + 2, chatRequests.get());
        }
    }

    @Test
    void authenticationRejectionIsStableAndNeverRetried() throws Exception {
        ModelProvider provider = new CustomOpenAiCompatibleModelProvider(transport);
        for (int status : List.of(401, 403)) {
            assertRejectedBoth(provider,
                    new HttpResponse(status, "provider-private-secret"),
                    "model_authentication_rejected", Optional.empty());
        }
    }

    @Test
    void rateLimitHeadersProduceCooldownWithoutTransportRetries() throws Exception {
        ModelProvider provider = new CustomOpenAiCompatibleModelProvider(transport);
        Instant rfcRetryAt = NOW.plusSeconds(17);
        Instant resetRetryAt = NOW.plusSeconds(29);

        assertRejectedBoth(provider,
                new HttpResponse(429, "provider-private-secret", Map.of(
                        "Retry-After", "7",
                        "X-RateLimit-Reset", Long.toString(resetRetryAt.getEpochSecond()))),
                "model_rate_limited", Optional.of(NOW.plusSeconds(7)));
        assertRejectedBoth(provider,
                new HttpResponse(429, "provider-private-secret", Map.of("Retry-After",
                        DateTimeFormatter.RFC_1123_DATE_TIME.format(rfcRetryAt.atZone(ZoneOffset.UTC)))),
                "model_rate_limited", Optional.of(rfcRetryAt));
        assertRejectedBoth(provider,
                new HttpResponse(429, "provider-private-secret", Map.of(
                        "X-RateLimit-Reset", Long.toString(resetRetryAt.getEpochSecond()))),
                "model_rate_limited", Optional.of(resetRetryAt));
        assertRejectedBoth(provider,
                new HttpResponse(429, "provider-private-secret"),
                "model_rate_limited", Optional.of(NOW.plusSeconds(1)));
        assertRejectedBoth(provider,
                new HttpResponse(429, "provider-private-secret", Map.of(
                        "Retry-After", "provider-private-invalid",
                        "X-RateLimit-Reset", Long.toString(resetRetryAt.getEpochSecond()))),
                "model_rate_limited", Optional.empty());
    }

    @Test
    void emptyTextAtTokenLimitIsKnownTruncationRatherThanUnknownNetworkOutcome() throws Exception {
        ModelProvider provider = new CustomOpenAiCompatibleModelProvider(transport);
        chatResponse.set(new HttpResponse(200,
                "{\"model\":\"model-a\",\"choices\":[{\"message\":{\"content\":null},\"finish_reason\":\"length\"}]}"));
        var result = assertInstanceOf(InvocationResult.Completed.class,
                provider.generate(request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE)));
        assertEquals("length", result.finishReasonCode());
        assertNull(result.content());
    }

    @Test
    void emptyStreamedTextAtTokenLimitRetainsKnownFinishAndUsage() throws Exception {
        ModelProvider provider = new CustomOpenAiCompatibleModelProvider(transport);
        chatResponse.set(new HttpResponse(200,
                "data: {\"model\":\"model-a\",\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n"
                        + "data: [DONE]\n\n"));
        Probe probe = stream(provider);
        var completed = assertInstanceOf(StreamEvent.Completed.class, probe.events.getLast()).result();
        assertEquals("length", completed.finishReasonCode());
        assertNull(completed.content());
        assertEquals(2, completed.usage().outputTokens().orElseThrow());
        assertEquals(1, probe.events.stream().filter(StreamEvent.Completed.class::isInstance).count());
    }

    @Test
    void emptyContentFilterIsACompletedFactAndEmptyStopRemainsUnknown() throws Exception {
        ModelProvider provider = new CustomOpenAiCompatibleModelProvider(transport);
        for (String finish : List.of("content_filter", "stop")) {
            int before = chatRequests.get();
            chatResponse.set(new HttpResponse(200,
                    "{\"model\":\"model-a\",\"choices\":[{\"message\":{\"content\":null},\"finish_reason\":\"" + finish
                            + "\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}"));
            var result = provider.generate(request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE));
            if ("content_filter".equals(finish)) {
                var completed = assertInstanceOf(InvocationResult.Completed.class, result);
                assertEquals(finish, completed.finishReasonCode());
                assertEquals(2, completed.usage().outputTokens().orElseThrow());
            } else assertInstanceOf(InvocationResult.OutcomeUnknown.class, result);
            assertEquals(before + 1, chatRequests.get());

            chatResponse.set(new HttpResponse(200,
                    "data: {\"model\":\"model-a\",\"choices\":[{\"delta\":{},\"finish_reason\":\"" + finish + "\"}]}\n\n"
                            + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n"
                            + "data: [DONE]\n\n"));
            var probe = stream(provider);
            assertTrue(probe.events.stream().anyMatch(StreamEvent.UsageDelta.class::isInstance));
            if ("content_filter".equals(finish)) {
                var completed = assertInstanceOf(StreamEvent.Completed.class, probe.events.getLast()).result();
                assertEquals(finish, completed.finishReasonCode());
                assertEquals(2, completed.usage().outputTokens().orElseThrow());
            } else assertInstanceOf(StreamEvent.OutcomeUnknown.class, probe.events.getLast());
            assertEquals(before + 2, chatRequests.get());
        }
    }

    @Test
    void responseBudgetStopsAnUnterminatedSseLineBeforeServerClosesIt() throws Exception {
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        server.createContext("/bounded/chat/completions", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(("data: " + "x".repeat(2048)).getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                written.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
                closed.countDown();
            }
        });
        endpoint = endpoint.replace("/v1", "/bounded");
        var limited = new OpenAiCompatibleProviderTransport(httpClient(), Clock.fixed(NOW, ZoneOffset.UTC), 1024, sdkResources);
        var provider = new CustomOpenAiCompatibleModelProvider(limited);
        var probe = new Probe();
        try {
            provider.stream(request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE)).subscribe(probe);
            probe.subscription.request(Long.MAX_VALUE);
            assertTrue(written.await(2, TimeUnit.SECONDS));
            assertTrue(probe.done.await(1, TimeUnit.SECONDS), "超限时必须结束读取，不能等待换行或 EOF");
            assertInstanceOf(StreamEvent.OutcomeUnknown.class, probe.events.getLast());
        } finally {
            release.countDown();
            probe.subscription.cancel();
            assertTrue(closed.await(2, TimeUnit.SECONDS), "测试拥有的 HTTP handler 必须退出");
        }
    }

    @Test
    void countsRawCrLfAndUtf8BytesAtTheExactResponseBoundary() throws Exception {
        String response = "data: {\"model\":\"model-a\",\"choices\":[{\"delta\":{\"content\":\"中文\"},"
                + "\"finish_reason\":\"stop\"}]}\r\n\r\ndata: [DONE]\n";
        chatResponse.set(new HttpResponse(200, response));
        int bytes = response.getBytes(StandardCharsets.UTF_8).length;
        var exact = new CustomOpenAiCompatibleModelProvider(new OpenAiCompatibleProviderTransport(
                httpClient(), Clock.fixed(NOW, ZoneOffset.UTC), bytes, sdkResources));
        assertEquals("中文", streamTerminal(exact, StreamEvent.Completed.class).result().content());
        var tooSmall = new CustomOpenAiCompatibleModelProvider(new OpenAiCompatibleProviderTransport(
                httpClient(), Clock.fixed(NOW, ZoneOffset.UTC), bytes - 1, sdkResources));
        streamTerminal(tooSmall, StreamEvent.OutcomeUnknown.class);
    }

    @Test
    void responseLimitRetainsUsageDeliveredBeforeAnOversizedLine() throws Exception {
        chatResponse.set(new HttpResponse(200,
                "data: {\"model\":\"model-a\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}\n\n"
                        + "data: " + "x".repeat(2048)));
        var limited = new CustomOpenAiCompatibleModelProvider(new OpenAiCompatibleProviderTransport(
                httpClient(), Clock.fixed(NOW, ZoneOffset.UTC), 1024, sdkResources));
        var probe = stream(limited);
        var usage = assertInstanceOf(StreamEvent.UsageDelta.class, probe.events.getFirst()).usage();
        assertEquals(3, usage.inputTokens().orElseThrow());
        assertEquals(2, usage.outputTokens().orElseThrow());
        assertInstanceOf(StreamEvent.OutcomeUnknown.class, probe.events.getLast());
    }

    @Test
    void sparseToolIndicesRetainOrderAndArgumentFragmentPrecision() throws Exception {
        String first = "{\"n\":9007199254740993.123456789}";
        chatResponse.set(new HttpResponse(200,
                toolFrame(1, "call-1", "{\"ok\":true}", false)
                        + toolFrame(0, "call-0", "{\"n\":", false)
                        + toolFrame(0, null, "9007199254740993.123456789}", true)
                        + "data: [DONE]\n\n"));
        var probe = stream(new CustomOpenAiCompatibleModelProvider(transport),
                request(new ResponseFormat.NativeConversation(), List.of(), ToolChoice.NONE));
        var result = assertInstanceOf(StreamEvent.Completed.class, probe.events.getLast()).result();
        assertEquals(List.of(new ModelProvider.ToolCall("call-0", "lookup", first),
                new ModelProvider.ToolCall("call-1", "lookup", "{\"ok\":true}")), result.toolCalls());
    }

    @Test
    void hugeSparseIndexAndMissingIndexRemainUnknownWithoutDenseAllocation() throws Exception {
        for (int index : List.of(Integer.MAX_VALUE, 1)) {
            chatResponse.set(new HttpResponse(200,
                    toolFrame(index, "call-large", "{}", true) + "data: [DONE]\n\n"));
            var probe = stream(new CustomOpenAiCompatibleModelProvider(transport),
                    request(new ResponseFormat.NativeConversation(), List.of(), ToolChoice.NONE));
            assertInstanceOf(StreamEvent.OutcomeUnknown.class, probe.events.getLast());
            assertEquals(1, probe.events.stream().filter(StreamEvent.OutcomeUnknown.class::isInstance).count());
        }
    }

    private static String toolFrame(int index, String id, String arguments, boolean finish) throws IOException {
        var root = JSON.createObjectNode().put("model", "model-a");
        var choice = root.putArray("choices").addObject();
        var call = choice.putObject("delta").putArray("tool_calls").addObject().put("index", index);
        if (id != null) call.put("id", id);
        call.putObject("function").put("name", "lookup").put("arguments", arguments);
        if (finish) choice.put("finish_reason", "tool_calls");
        return "data: " + JSON.writeValueAsString(root) + "\n\n";
    }

    @Test
    void successfulLengthFinishRemainsACompletedResult() throws Exception {
        ModelProvider provider = new CustomOpenAiCompatibleModelProvider(transport);
        chatResponse.set(new HttpResponse(200,
                "{\"model\":\"model-a\",\"choices\":[{\"message\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}"));
        var completed = assertInstanceOf(InvocationResult.Completed.class,
                provider.generate(request(provider,
                        new ResponseFormat.Text(), List.of(), ToolChoice.NONE)));
        assertEquals("length", completed.finishReasonCode());
        assertEquals("partial", completed.content());

        chatResponse.set(new HttpResponse(200,
                "data: {\"model\":\"model-a\",\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}\n\n"
                        + "data: [DONE]\n\n"));
        var streamed = streamTerminal(provider, StreamEvent.Completed.class).result();
        assertEquals("length", streamed.finishReasonCode());
        assertEquals("partial", streamed.content());
    }

    @Test
    void streamedContentFollowedByProviderErrorStaysOutcomeUnknown() throws Exception {
        ModelProvider provider = new CustomOpenAiCompatibleModelProvider(transport);
        chatResponse.set(new HttpResponse(200,
                "data: {\"model\":\"model-a\",\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                        + "data: {\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"private\"}}\n\n"
                        + "data: [DONE]\n\n"));

        Probe probe = stream(provider);

        var content = assertInstanceOf(StreamEvent.ContentDelta.class, probe.events.getFirst());
        assertEquals("partial", content.content());
        assertEquals("model_outcome_unknown", assertInstanceOf(
                StreamEvent.OutcomeUnknown.class, probe.events.getLast()).reasonCode());
    }

    private InvocationRequest request(ResponseFormat format,
            List<ToolDefinition> tools, ToolChoice choice) {
        return request(format, tools, choice, ReasoningPolicy.DISABLED);
    }

    private InvocationRequest request(ResponseFormat format,
            List<ToolDefinition> tools, ToolChoice choice, ReasoningPolicy reasoning) {
        return request(new CustomOpenAiCompatibleModelProvider(), format, tools, choice, reasoning);
    }

    private InvocationRequest request(ModelProvider provider, ResponseFormat format,
            List<ToolDefinition> tools, ToolChoice choice) {
        return request(provider, format, tools, choice, ReasoningPolicy.DISABLED);
    }

    private InvocationRequest request(ModelProvider provider, ResponseFormat format,
            List<ToolDefinition> tools, ToolChoice choice, ReasoningPolicy reasoning) {
        var service = chatService(provider);
        var protocol = service.protocols().stream().filter(value -> value.protocol() == ModelProtocol.OPENAI_CHAT_COMPLETIONS)
                .findFirst().orElseThrow();
        return new InvocationRequest(endpoint, "key", "model-a",
                new InvocationProfile(service.serviceId(), service.profileVersion(),
                        ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                        AuthenticationScheme.BEARER_API_KEY,
                        protocol.dialectVersion()),
                List.of(new Message(Role.USER, "question", null, List.of())),
                new ResponseContract(format, EnforcementLevel.STRICT, "test"),
                new GenerationOptions(OptionalInt.of(32), OptionalDouble.empty()),
                reasoning, tools, choice, Duration.ofSeconds(2), () -> false, () -> { });
    }

    private ServiceProfile chatService(ModelProvider provider) {
        return provider.preset().services().stream().filter(value -> value.modelKinds().contains(ModelKind.LLM)
                && value.protocols().stream().anyMatch(protocol -> protocol.protocol() == ModelProtocol.OPENAI_CHAT_COMPLETIONS))
                .findFirst().orElseThrow();
    }

    private ResolvedDiscovery discovery(ModelProvider provider) {
        var service = chatService(provider);
        var discovery = service.modelDiscovery();
        return new ResolvedDiscovery(provider.preset().providerId(), service.serviceId(), service.profileVersion(),
                AuthenticationScheme.BEARER_API_KEY, endpoint, endpoint + "/models",
                discovery.dialectVersion(), VerificationStatus.UNVERIFIED);
    }

    private void chat(HttpExchange exchange) throws IOException {
        chatRequests.incrementAndGet();
        var request = JSON.readTree(exchange.getRequestBody());
        lastRequest.set(request);
        var response = chatResponse.get();
        if (response != null) {
            response.headers().forEach(exchange.getResponseHeaders()::set);
            respond(exchange, response.status(), response.body());
            return;
        }
        if (usageResponse.get() != null) {
            if (request.path("stream").asBoolean()) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                respond(exchange, 200,
                        "data: {\"model\":\"model-a\",\"choices\":[{\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":null}\n\n"
                        + "data: {\"choices\":[],\"usage\":" + usageResponse.get() + "}\n\n"
                        + "data: [DONE]\n\n");
            } else {
                respond(exchange, 200, "{\"model\":\"model-a\",\"choices\":[{\"message\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}],\"usage\":"
                        + usageResponse.get() + "}");
            }
            return;
        }
        if (request.path("stream").asBoolean()) {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            respond(exchange, 200, "data: {\"model\":\"model-a\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\",\"reasoning_content\":\"reasoning\"}}]}\n\n"
                    + "data: {\"model\":\"model-a\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\n\n"
                    + "data: [DONE]\n\n");
        } else if (request.has("tools")) {
            respond(exchange, 200, "{\"model\":\"model-a\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Beijing\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
        } else if (request.has("response_format")) {
            respond(exchange, 200, "{\"model\":\"model-a\",\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}");
        } else {
            respond(exchange, 200, "{\"model\":\"model-a\",\"choices\":[{\"message\":{\"reasoning_content\":\"reasoning\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private <T extends StreamEvent> T streamTerminal(ModelProvider provider, Class<T> type)
            throws Exception {
        Probe probe = stream(provider);
        return assertInstanceOf(type, probe.events.getLast());
    }

    private Probe stream(ModelProvider provider) throws Exception {
        return stream(provider, request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE));
    }

    private Probe stream(ModelProvider provider, InvocationRequest call) throws Exception {
        Probe probe = new Probe();
        provider.stream(call).subscribe(probe);
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(probe.done.await(5, TimeUnit.SECONDS));
        return probe;
    }

    private void assertRejectedBoth(ModelProvider provider, HttpResponse response,
            String reasonCode, Optional<Instant> retryAt) throws Exception {
        chatResponse.set(response);
        int before = chatRequests.get();

        var generated = assertInstanceOf(InvocationResult.Rejected.class,
                provider.generate(request(provider, new ResponseFormat.Text(), List.of(), ToolChoice.NONE)));
        assertEquals(reasonCode, generated.reasonCode());
        assertEquals(retryAt, generated.retryAt());
        assertFalse(generated.toString().contains(response.body()));
        assertEquals(before + 1, chatRequests.get());

        var streamed = streamTerminal(provider, StreamEvent.Rejected.class);
        assertEquals(reasonCode, streamed.reasonCode());
        assertEquals(retryAt, streamed.retryAt());
        assertFalse(streamed.toString().contains(response.body()));
        assertEquals(before + 2, chatRequests.get());
    }

    private record HttpResponse(int status, String body, Map<String, String> headers) {
        private HttpResponse(int status, String body) {
            this(status, body, Map.of());
        }

        private HttpResponse {
            headers = Map.copyOf(headers);
        }
    }

    private static final class Probe implements Flow.Subscriber<StreamEvent> {
        private final java.util.ArrayList<StreamEvent> events = new java.util.ArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private Flow.Subscription subscription;
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; }
        @Override public void onNext(StreamEvent event) { events.add(event); }
        @Override public void onError(Throwable failure) { done.countDown(); }
        @Override public void onComplete() { done.countDown(); }
    }
}
