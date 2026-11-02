package com.skillforge.runtime.adapter.model.aliyun_bailian;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient.ModelDiscoveryException;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class AliyunBailianModelDiscoveryClientTest {
    private HttpServer server;
    private HttpClient http;
    private ExecutorService executor;
    private String endpoint;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> secondPage = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> block = new AtomicReference<>();
    private final CountDownLatch secondStarted = new CountDownLatch(1);

    private static String page(int number, int total, String models) {
        return "{\"success\":true,\"output\":{\"total\":" + total + ",\"page_no\":" + number
                + ",\"page_size\":2,\"models\":[" + models + "]}}";
    }

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/api/v1/models", exchange -> {
            int call = calls.incrementAndGet();
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("page_no=" + call + "&page_size=20", exchange.getRequestURI().getRawQuery());
            String response;
            if (call == 1) response = page(1, 3, """
                    {"model":"future-z","provider":"author-z","name":"Future Z","capabilities":["TG","Reasoning"],
                    "features":["function-calling","cache"],"inference_metadata":{"request_modality":["Text","Image"],"response_modality":["Text"]},
                    "model_info":{"context_window":262144,"max_input_tokens":250000,"max_output_tokens":8192,"max_reasoning_tokens":4096,
                    "reasoning_max_input_tokens":200000,"reasoning_max_output_tokens":4096}},
                    {"model":"never-seen-before","model_info":{"context_window":null,"max_output_tokens":null}}
                    """);
            else {
                secondStarted.countDown();
                try {
                    var latch = block.get(); if (latch != null) latch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                response = secondPage.get() == null ? page(2, 3, "{\"model\":\"future-a\",\"features\":[\"new-future-feature\"]}") : secondPage.get();
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        });
        server.start(); endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterEach void stop() throws Exception {
        var latch = block.get(); if (latch != null) latch.countDown();
        if (server != null) server.stop(0);
        if (executor != null) { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        if (http != null) { http.shutdownNow(); assertTrue(http.awaitTermination(Duration.ofSeconds(5))); }
    }

    private ModelProvider.DiscoveryRequest request() {
        var target = new ResolvedDiscovery("aliyun-bailian", "bailian-cn-beijing-workspace", "2026-09-13-workspace-v1",
                AuthenticationScheme.BEARER_API_KEY, endpoint + "/compatible-mode/v1", endpoint + "/api/v1/models",
                AliyunBailianModelDiscoveryClient.DIALECT, VerificationStatus.UNVERIFIED);
        return new ModelProvider.DiscoveryRequest(target, "test-key", Duration.ofSeconds(3));
    }

    @Test void paginatesAndPreservesTypedFactsForUnknownModelIdsThroughProvider() {
        var client = new AliyunBailianModelDiscoveryClient(http, 64_000);
        var provider = new AliyunBailianModelProvider(ModelProviderTransport.unavailable(), client);
        var models = provider.discover(request());
        assertEquals(2, calls.get()); assertEquals("Bearer test-key", auth.get());
        assertEquals(List.of("future-a", "future-z", "never-seen-before"), models.stream().map(value -> value.modelId()).toList());
        var metadata = models.get(1).metadata().orElseThrow();
        assertEquals("Future Z", metadata.name()); assertEquals("author-z", models.get(1).ownedBy());
        assertEquals(262144L, metadata.context().contextWindow()); assertEquals(8192L, metadata.context().maxOutputTokens());
        assertEquals(200000L, metadata.context().reasoningMaxInputTokens());
        assertEquals(List.of("Text", "Image"), metadata.inputModalities());
        assertEquals(List.of("TG", "Reasoning"), metadata.capabilities());
        assertEquals(List.of("function-calling", "cache"), metadata.features());
        assertNull(metadata.reasoning()); assertNull(metadata.messages());
        assertNull(models.getLast().metadata().orElseThrow().context());
        assertEquals(List.of("new-future-feature"), models.getFirst().metadata().orElseThrow().features());
    }

    @Test void nativeDiscoveryUsesWorkspaceOriginAndKeepsLegacyDiscoverySeparate() {
        var catalog = ModelProviderCatalog.standard();
        var nativeTarget = catalog.resolveDiscovery(new DiscoverySelection("aliyun-bailian", "bailian-cn-beijing-workspace",
                "2026-09-13-workspace-v1", AuthenticationScheme.BEARER_API_KEY,
                "https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"));
        assertEquals("https://workspace-123.cn-beijing.maas.aliyuncs.com/api/v1/models", nativeTarget.modelsEndpoint());
        assertEquals(AliyunBailianModelDiscoveryClient.DIALECT, nativeTarget.dialectVersion());
        var legacy = catalog.resolveDiscovery(new DiscoverySelection("aliyun-bailian", "bailian-cn-beijing",
                "2026-09-15-global-v1", AuthenticationScheme.BEARER_API_KEY, null));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/models", legacy.modelsEndpoint());
        assertEquals("openai-model-list-v1", legacy.dialectVersion());
    }

    @Test void changedDirectoryTotalDoesNotPretendPaginationIsASnapshot() {
        secondPage.set(page(2, 4, "{\"model\":\"future-a\"},{\"model\":\"newly-listed-model\"}"));
        var models = new AliyunBailianModelDiscoveryClient(http, 64_000).discover(request());
        assertEquals(List.of("future-a", "future-z", "never-seen-before", "newly-listed-model"),
                models.stream().map(value -> value.modelId()).toList());
        assertEquals(2, calls.get());
    }

    @Test void duplicateAcrossPagesUsesTheLatestObservedDeclaration() {
        secondPage.set(page(2, 3, "{\"model\":\"future-z\",\"model_info\":{\"context_window\":524288}}"));
        var models = new AliyunBailianModelDiscoveryClient(http, 64_000).discover(request());
        assertEquals(2, models.size());
        assertEquals("future-z", models.getFirst().modelId());
        assertEquals(524288L, models.getFirst().metadata().orElseThrow().context().contextWindow());
        assertEquals(2, calls.get());
    }

    @Test void cumulativeResponseBudgetRejectsBeforeReturningAnyPartialList() {
        secondPage.set(page(2, 3, "{\"model\":\"" + "x".repeat(1000) + "\"}"));
        var failure = assertThrows(ModelDiscoveryException.class,
                () -> new AliyunBailianModelDiscoveryClient(http, 1300).discover(request()));
        assertEquals("model_discovery_response_too_large", failure.getMessage());
    }

    @Test void interruptDuringSecondPageCancelsTheCurrentExchange() throws Exception {
        block.set(new CountDownLatch(1)); var failure = new AtomicReference<Throwable>();
        var worker = Thread.ofPlatform().start(() -> {
            try { new AliyunBailianModelDiscoveryClient(http, 64_000).discover(request()); }
            catch (Throwable value) { failure.set(value); }
        });
        try {
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS)); worker.interrupt(); worker.join(2000);
            assertFalse(worker.isAlive());
            assertEquals("model_discovery_interrupted", assertInstanceOf(ModelDiscoveryException.class, failure.get()).getMessage());
        } finally { block.get().countDown(); worker.interrupt(); worker.join(2000); }
    }
}
