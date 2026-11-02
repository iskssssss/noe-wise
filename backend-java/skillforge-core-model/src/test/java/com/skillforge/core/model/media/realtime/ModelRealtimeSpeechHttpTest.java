package com.skillforge.core.model.media.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.model.media.FileModelMediaAssetStore;
import com.skillforge.core.model.media.ModelMediaJob.Owner;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.adapter.model.media.BailianRealtimeTranscription;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProviders;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 MiniMax HTTP/SSE 解析经过宿主收集到受控文件；只有目录和数据库访问使用替身。 */
class ModelRealtimeSpeechHttpTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.systemUTC();
    private final Owner owner = new Owner(null, "7");
    private final PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final ModelRealtimeMapper mapper = mock(ModelRealtimeMapper.class);
    private final AtomicReference<ModelRealtimeMapper.Row> stored = new AtomicReference<>();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<ModelRealtimeService> services = new ArrayList<>();
    private HttpServer server;
    private ExecutorService executor;
    private RelayClient client;
    private FileModelMediaAssetStore assets;
    private Path root;

    @BeforeEach void start() throws Exception {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        if (System.getProperty("os.name").startsWith("Windows"))
            assertTrue(temporary.startsWith(Path.of("E:\\")), "实时媒体夹具只能写入 E 盘临时目录");
        root = Files.createTempDirectory(temporary, "realtime-http-");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/t2a_v2", exchange -> {
            requests.incrementAndGet();
            received.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            finally { exchange.close(); }
        });
        server.start();
        client = new RelayClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        assets = new FileModelMediaAssetStore(client, root.resolve("assets"), 32768);
        when(crypto.decrypt("encrypted-fixture")).thenReturn("synthetic-key");
        var configuration = config();
        when(models.draftForProbe("71")).thenReturn(configuration);
        when(mapper.insert(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return 1; });
        when(mapper.find(eq(owner.key()), anyString())).thenAnswer(call -> stored.get());
        when(mapper.operation(eq(owner.key()), anyString())).thenAnswer(call -> stored.get());
        when(mapper.started(anyString(), any())).thenAnswer(call -> { stored.get().status = "RUNNING"; return 1; });
        when(mapper.complete(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return 1; });
        when(mapper.asset(any())).thenAnswer(call -> {
            var row = (ModelRealtimeMapper.Row) call.getArgument(0);
            row.assetAvailable = true; stored.set(row); return 1;
        });
    }

    @AfterEach void close() throws Exception {
        try {
            services.forEach(ModelRealtimeService::close);
            if (server != null) server.stop(0);
            if (executor != null) { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
            if (client != null) { client.shutdownNow(); assertTrue(client.awaitTermination(Duration.ofSeconds(5))); }
        } finally {
            TenantContext.clear();
            if (root != null) try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    @Test void completeMp3SurvivesServiceReconstructionAndOperationReadDoesNotResynthesize() throws Exception {
        response.set("""
                data: {"data":{"audio":"0102","status":1},"trace_id":"mini-1","base_resp":{"status_code":0}}

                data: {"data":{"audio":"03ff","status":1},"trace_id":"mini-1","base_resp":{"status_code":0}}

                data: {"data":{"audio":"010203ff","status":2},"trace_id":"mini-final","base_resp":{"status_code":0},"extra_info":{"audio_length":1200,"usage_characters":2}}

                """);
        var listener = new Listener();
        var service = service();
        var command = command();
        var opened = service.streamSpeech(owner, command, listener);
        assertTrue(listener.done.await(5, TimeUnit.SECONDS));
        assertEquals(DomesticMediaProvider.Status.SUCCEEDED, listener.snapshot.get().status());
        assertEquals(2, listener.audio.size());
        assertEquals("mp3", listener.audio.getFirst().encoding());
        assertArrayEquals(new byte[] {1, 2}, Base64.getDecoder().decode(listener.audio.getFirst().dataBase64()));
        assertEquals("audio/mpeg", listener.snapshot.get().asset().mediaType());
        assertTrue(listener.snapshot.get().asset().name().endsWith(".mp3"));
        assertEquals("mini-final", listener.snapshot.get().providerTaskId());

        var rebuilt = service();
        var original = rebuilt.detail(owner, opened.id());
        assertFalse(original.connected());
        assertNotNull(original.asset());
        var download = rebuilt.download(owner, opened.id());
        try (var input = download.content()) { assertArrayEquals(new byte[] {1, 2, 3, (byte) 255}, input.readAllBytes()); }
        var repeated = new Listener();
        assertEquals(opened.id(), rebuilt.streamSpeech(owner, command, repeated).id());
        assertTrue(repeated.done.await(1, TimeUnit.SECONDS));
        assertTrue(repeated.audio.isEmpty());
        assertEquals(1, requests.get());
        assertEquals("https://api.minimax.cn/v1/t2a_v2", client.sent.getFirst().uri().toString());
        assertTrue(received.get().path("stream_options").path("exclude_aggregated_audio").booleanValue());
        assertFalse(json.writeValueAsString(original).contains("synthetic-key"));
        try (var files = Files.list(root.resolve("assets"))) { assertEquals(1, files.count()); }
    }

    @Test void eofAfterRealAudioKeepsUnknownAndDoesNotPublishIncompleteFile() throws Exception {
        response.set("""
                data: {"data":{"audio":"0102","status":1},"trace_id":"mini-1","base_resp":{"status_code":0}}

                """);
        var listener = new Listener();
        var service = service();
        var opened = service.streamSpeech(owner, command(), listener);
        assertTrue(listener.done.await(5, TimeUnit.SECONDS));
        assertEquals(1, listener.audio.size());
        var original = service.detail(owner, opened.id());
        assertEquals(DomesticMediaProvider.Status.UNKNOWN, original.status());
        assertNull(original.asset());
        assertEquals("model_realtime_asset_not_ready",
                assertThrows(ApiException.class, () -> service.download(owner, opened.id())).getMessage());
        assertFalse(Files.exists(root.resolve("assets")));
        assertEquals(1, requests.get());
    }

    private ModelRealtimeService service() {
        var transactions = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        var service = new ModelRealtimeService(models, crypto, mapper, assets,
                new BailianRealtimeTranscription(client, clock, 32768),
                new DomesticMediaProviders(client, clock, 32768), transactions, json, clock);
        services.add(service); return service;
    }
    private static ModelRealtimeSession.OpenSpeech command() {
        return new ModelRealtimeSession.OpenSpeech(UUID.randomUUID().toString(), "71", null, "测试", "voice-fixture", "Chinese");
    }
    private static PlatformModelConfig config() {
        var config = mock(PlatformModelConfig.class);
        when(config.enabled()).thenReturn(true); when(config.kind()).thenReturn("SPEECH_SYNTHESIS");
        when(config.revision()).thenReturn(1L); when(config.providerId()).thenReturn("minimax");
        when(config.serviceId()).thenReturn("minimax-cn-tts"); when(config.modelId()).thenReturn("speech-02-hd");
        when(config.endpoint()).thenReturn("https://api.minimax.cn/v1");
        when(config.apiKeyEncrypted()).thenReturn("encrypted-fixture"); when(config.timeoutSeconds()).thenReturn(5);
        return config;
    }
    private static final class Listener implements ModelRealtimeService.Listener {
        final AtomicReference<ModelRealtimeSession> snapshot = new AtomicReference<>();
        final List<ModelRealtimeSession.Audio> audio = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        @Override public void snapshot(ModelRealtimeSession value) { snapshot.set(value); }
        @Override public void audio(ModelRealtimeSession.Audio value) { audio.add(value); }
        @Override public void complete() { done.countDown(); }
    }

    /** 校验与保存真实境内请求后，仅将物理传输改道到本地夹具。 */
    private static final class RelayClient extends HttpClient {
        final HttpClient delegate = HttpClient.newBuilder().followRedirects(Redirect.NEVER).build();
        final URI target;
        final List<HttpRequest> sent = new CopyOnWriteArrayList<>();
        RelayClient(URI target) { this.target = target; }
        private HttpRequest relay(HttpRequest original) {
            sent.add(original);
            var builder = HttpRequest.newBuilder(target.resolve(original.uri().getRawPath()));
            original.timeout().ifPresent(builder::timeout);
            original.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            return builder.method(original.method(), original.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())).build();
        }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException { return delegate.send(relay(request), handler); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { return delegate.sendAsync(relay(request), handler); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { return delegate.sendAsync(relay(request), handler, pushPromiseHandler); }
        @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return delegate.followRedirects(); }
        @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
        @Override public SSLContext sslContext() { return delegate.sslContext(); }
        @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public Optional<Executor> executor() { return delegate.executor(); }
        @Override public void shutdownNow() { delegate.shutdownNow(); }
        @Override public boolean awaitTermination(Duration duration) throws InterruptedException { return delegate.awaitTermination(duration); }
    }
}
