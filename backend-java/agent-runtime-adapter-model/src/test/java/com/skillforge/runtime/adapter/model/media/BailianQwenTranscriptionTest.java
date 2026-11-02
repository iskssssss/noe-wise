package com.skillforge.runtime.adapter.model.media;

import static com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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

class BailianQwenTranscriptionTest {
    private static final URI BEIJING = URI.create("https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
    private static final String AUDIO_URL = "https://speech-assets.oss-cn-beijing.aliyuncs.com/reference.wav?signature=private";
    private static final String GENERATION = "/api/v1/services/aigc/multimodal-generation/generation";
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private RelayClient client;
    private BailianMediaProvider provider;
    private final AtomicReference<String> responseBody = new AtomicReference<>(success("你好"));
    private final AtomicInteger statusCode = new AtomicInteger(200);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> asyncHeader = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            asyncHeader.set(exchange.getRequestHeaders().getFirst("X-DashScope-Async"));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode.get(), bytes.length);
            try { exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        client = new RelayClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        provider = new BailianMediaProvider(client, Clock.systemUTC(), 32_000);
    }

    @AfterEach void close() throws Exception {
        if (server != null) server.stop(0);
        if (executor != null) { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        if (client != null) { client.shutdownNow(); assertTrue(client.awaitTermination(Duration.ofSeconds(5))); }
    }

    private static Connection connection() { return new Connection(BEIJING, "synthetic-key", Duration.ofSeconds(3), () -> false); }
    private static QwenTranscriptionRequest request(String audio) { return new QwenTranscriptionRequest("qwen3-asr-flash", audio, "zh", true); }
    private static String success(String text) {
        return "{\"request_id\":\"qwen-asr-1\",\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"" + text + "\"}]}}]}}";
    }

    private static JsonNode transcript(Result result) throws IOException {
        assertEquals(Status.SUCCEEDED, result.status()); assertEquals(1, result.assets().size());
        var asset = result.assets().getFirst();
        assertEquals(AssetKind.TRANSCRIPT, asset.kind()); assertEquals("application/json", asset.mediaType());
        var bytes = assertInstanceOf(BytesSource.class, asset.source()).bytes();
        assertTrue(bytes.length > 0);
        return JSON.readTree(bytes);
    }

    @Test void sendsNativeUrlRequestAndReturnsRealTextAnnotationsAndReportedUsage() throws Exception {
        responseBody.set("""
                {"request_id":"qwen-url-1","output":{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":[{"text":"你好，"},{"text":"请问有哪里不舒服？"}],"annotations":[{"type":"audio_info","language":"zh","emotion":"neutral"}]}}]},"usage":{"input_tokens_details":{"text_tokens":0},"output_tokens_details":{"text_tokens":6},"seconds":1.25}}
                """);
        var result = provider.execute(connection(), request(AUDIO_URL));
        assertEquals(JSON.readTree("""
                {"transcripts":[{"text":"你好，请问有哪里不舒服？","annotations":[{"type":"audio_info","language":"zh","emotion":"neutral"}]}]}
                """), transcript(result));
        assertEquals("qwen-url-1", result.requestId()); assertNull(result.taskId());
        assertNull(result.usage().inputTokens()); assertEquals(6L, result.usage().outputTokens());
        assertEquals(new BigDecimal("1.25"), result.usage().durationSeconds()); assertNull(result.usage().characters());
        assertEquals(GENERATION, path.get()); assertNull(asyncHeader.get());
        assertEquals("Bearer synthetic-key", auth.get());
        assertEquals("workspace-123.cn-beijing.maas.aliyuncs.com", client.sent.getFirst().uri().getHost());
        assertEquals("POST", client.sent.getFirst().method());
        var message = received.get().path("input").path("messages").get(0);
        assertEquals("user", message.path("role").textValue());
        assertEquals(AUDIO_URL, message.path("content").get(0).path("audio").textValue());
        assertEquals("zh", received.get().path("parameters").path("asr_options").path("language").textValue());
        assertTrue(received.get().path("parameters").path("asr_options").path("enable_itn").booleanValue());
        assertFalse(received.get().path("input").has("file_urls"));
        assertFalse(result.toString().contains("请问")); assertFalse(request(AUDIO_URL).toString().contains("private"));
        assertEquals(1, requests.get());
    }

    @Test void sendsCanonicalWavAndMpegDataWithoutReplacingThemWithAUrl() throws Exception {
        for (String mime : List.of("audio/wav", "audio/mpeg")) {
            String audio = "data:" + mime + ";base64,AQIDBA==";
            var result = provider.execute(connection(), new QwenTranscriptionRequest("qwen3-asr-flash", audio, null, false));
            assertEquals("你好", transcript(result).path("transcripts").get(0).path("text").textValue());
            assertNull(result.usage());
            assertEquals(audio, received.get().path("input").path("messages").get(0).path("content").get(0).path("audio").textValue());
            var options = received.get().path("parameters").path("asr_options");
            assertFalse(options.has("language")); assertFalse(options.path("enable_itn").booleanValue());
            assertNull(asyncHeader.get()); assertEquals(GENERATION, path.get());
        }
        assertEquals(2, requests.get());
    }

    @Test void preservesSilenceAsAnEmptyRealTranscriptWithANonemptyJsonAssetAndUnknownUsage() throws Exception {
        responseBody.set(success(""));
        var result = provider.execute(connection(), request(AUDIO_URL));
        var document = transcript(result);
        assertEquals(1, document.path("transcripts").size());
        assertEquals("", document.path("transcripts").get(0).path("text").textValue());
        assertNull(result.usage()); assertEquals(1, requests.get());
    }

    @Test void rejectsInvalidMimeBase64EncodingAndLengthBeforeDispatchWithoutUrlFallback() {
        var invalid = List.of("data:audio/mp3;base64,AQIDBA==", "data:audio/x-wav;base64,AQIDBA==",
                "data:audio/ogg;base64,AQIDBA==", "data:image/png;base64,AQIDBA==", "data:audio/wav,AQIDBA==",
                "data:audio/wav;base64,AQIDBA", "data:audio/wav;base64,AQI*", "data:audio/wav;base64,-_8=",
                "data:audio/wav;base64,AQID\nBA==", "data:audio/wav;base64,AQIDBA%3D%3D",
                "data:audio/wav;base64,AQIDBA===", "data:audio/wav;base64,AB==", "data:audio/wav;base64,");
        for (String audio : invalid) {
            var result = provider.execute(connection(), request(audio));
            assertEquals(Status.NOT_SUBMITTED, result.status(), audio); assertTrue(result.assets().isEmpty());
        }
        String oversized = "data:audio/wav;base64," + "AAAA".repeat(QwenTranscriptionRequest.MAXIMUM_ENCODED_AUDIO_BYTES / 4);
        assertEquals(Status.NOT_SUBMITTED, provider.execute(connection(), request(oversized)).status());
        assertEquals(0, requests.get()); assertTrue(client.sent.isEmpty());
    }

    @Test void rejectsNonPublicAudioUrlsAndInternationalProviderEndpointsWithoutNetworkRequests() {
        for (String audio : List.of("http://speech-assets.oss-cn-beijing.aliyuncs.com/audio.wav", "https://localhost/audio.wav",
                "https://127.0.0.1/audio.wav", "https://10.1.2.3/audio.wav", "https://[::1]/audio.wav",
                "https://user:password@speech-assets.oss-cn-beijing.aliyuncs.com/audio.wav", AUDIO_URL + "#audio", "file:///E:/audio.wav"))
            assertEquals(Status.NOT_SUBMITTED, provider.execute(connection(), request(audio)).status(), audio);
        var overseas = new Connection(URI.create("https://dashscope-intl.aliyuncs.com/compatible-mode/v1"), "synthetic-key", Duration.ofSeconds(3), () -> false);
        assertEquals(Status.NOT_SUBMITTED, provider.execute(overseas, request(AUDIO_URL)).status());
        assertEquals(0, requests.get()); assertTrue(client.sent.isEmpty());
    }

    @Test void incompleteUnexpectedAndMalformedSuccessResponsesRemainUnknownWithoutRetry() {
        var fixtures = List.of("{", "{}", "{\"output\":{\"choices\":[]}}",
                "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"a\"}]}},{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"b\"}]}}]}}",
                success("partial").replace("\"stop\"", "\"length\""),
                success("wrong-role").replace("\"assistant\"", "\"user\""),
                "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":[{\"text\":\"missing role\"}]}}]}}",
                "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"partial\"},{\"audio\":\"ignored\"}]}}]}}",
                "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":null}]}}]}}",
                "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":[]}}]}}");
        for (String fixture : fixtures) {
            responseBody.set(fixture);
            var result = provider.execute(connection(), request(AUDIO_URL));
            assertEquals(Status.UNKNOWN, result.status(), fixture); assertTrue(result.assets().isEmpty());
        }
        assertEquals(fixtures.size(), requests.get());
    }

    @Test void distinguishesProviderHttpRejectionFromUnknownServerAndTimeoutOutcomesWithoutRetry() {
        responseBody.set("{\"code\":\"InvalidParameter\",\"message\":\"private transcription and key\"}");
        var rejected = provider.execute(connection(), request(AUDIO_URL));
        assertEquals(Status.FAILED, rejected.status()); assertFalse(rejected.toString().contains("private"));
        statusCode.set(401);
        assertEquals(Status.FAILED, provider.execute(connection(), request(AUDIO_URL)).status());
        statusCode.set(500);
        assertEquals(Status.UNKNOWN, provider.execute(connection(), request(AUDIO_URL)).status());
        statusCode.set(408);
        assertEquals(Status.UNKNOWN, provider.execute(connection(), request(AUDIO_URL)).status());
        assertEquals(4, requests.get());
    }

    @Test void explicitRequestTypeChoosesQwenOrFileTranscriptionWireEvenWithTheSameModel() throws Exception {
        var qwen = provider.execute(connection(), new QwenTranscriptionRequest("same-model", AUDIO_URL, "zh", true));
        assertEquals("你好", transcript(qwen).path("transcripts").get(0).path("text").textValue());
        assertEquals(GENERATION, path.get()); assertNull(asyncHeader.get());
        assertEquals("same-model", received.get().path("model").textValue());
        assertTrue(received.get().path("input").has("messages")); assertFalse(received.get().path("input").has("file_urls"));
        responseBody.set("{\"request_id\":\"file-submit\",\"output\":{\"task_id\":\"asr-file-1\",\"task_status\":\"PENDING\"}}");
        var file = provider.execute(connection(), new TranscriptionRequest("same-model", List.of(AUDIO_URL), List.of(0), List.of("zh"), false, null));
        assertEquals(Status.PENDING, file.status()); assertEquals("asr-file-1", file.taskId());
        assertEquals("/api/v1/services/audio/asr/transcription", path.get()); assertEquals("enable", asyncHeader.get());
        assertEquals("same-model", received.get().path("model").textValue());
        assertEquals(AUDIO_URL, received.get().path("input").path("file_urls").get(0).textValue());
        assertFalse(received.get().path("input").has("messages"));
        assertEquals(0, received.get().path("parameters").path("channel_id").get(0).intValue());
        assertEquals("zh", received.get().path("parameters").path("language_hints").get(0).textValue());
        assertEquals(2, requests.get());
    }

    @Test void responseLimitEndsTheOriginalQwenCallWithoutReturningAPartialJsonAsset() {
        responseBody.set(success("字".repeat(500)));
        var small = new BailianMediaProvider(client, Clock.systemUTC(), 64);
        var result = small.execute(connection(), request(AUDIO_URL));
        assertEquals(Status.UNKNOWN, result.status()); assertTrue(result.assets().isEmpty()); assertEquals(1, requests.get());
    }

    /** 保留真实国内 URI、请求体与认证头，只将传输指向受控本地服务。 */
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
