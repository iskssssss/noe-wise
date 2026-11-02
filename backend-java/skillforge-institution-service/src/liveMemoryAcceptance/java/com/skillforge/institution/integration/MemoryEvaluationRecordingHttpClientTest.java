package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

/** 无网络、无供应商的订阅链验证；伪 HttpClient 只驱动调用方原 handler，不发起任何 HTTP。 */
class MemoryEvaluationRecordingHttpClientTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String SYSTEM = "{\"role\":\"system\",\"content\":\"中文 stable system\"}";
    private static final String USER = "{\"role\":\"user\",\"content\":\"PRIVATE_BODY_SAMPLE\"}";
    private static final String TOOLS = "[{\"type\":\"function\",\"function\":{\"name\":\"sf_tool_0\",\"parameters\":{\"type\":\"object\"}}}]";

    /** 实际模型输出保存到私有候选证据，认证头和URL始终保持隐藏。 */
    @Test void keepsRawCandidatesFromActualBodyWithoutRecordingCredentials() throws Exception {
        var nanos = new AtomicLong();
        try (var original = new FakeHttpClient(nanos)) {
            String candidate = "{\"candidates\":[{\"type\":\"preference\",\"object\":{\"text\":\"先给结论\"}}]}";
            original.chunks = List.of(JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", candidate))),
                    "usage", Map.of("prompt_tokens", 51, "completion_tokens", 19))));
            var recording = new MemoryRecordingHttpClient(original, CLOCK, nanos::get);
            recording.beginThreadAttempt(context("raw-candidates", "AUXILIARY_MODEL"), "system-agent.task");
            var response = recording.send(request(body(List.of(SYSTEM, USER), "none", false)), HttpResponse.BodyHandlers.ofByteArray());
            assertArrayEquals(original.responseBytes(), response.body());
            var observed = recording.snapshot().getFirst();
            assertEquals(candidate, observed.responseContent());
            assertEquals(51, observed.rawUsage().get("usage.prompt_tokens"));
            assertEquals(19, observed.rawUsage().get("usage.completion_tokens"));
            assertSafe(JSON.writeValueAsString(observed));
            assertEquals(1, original.sends);
        }
    }

    @Test void requestAndResponseRemainExactWhileMainPrefixSkipsAuxiliaryCalls() throws Exception {
        var nanos = new AtomicLong();
        try (var original = new FakeHttpClient(nanos)) {
            var recording = new MemoryRecordingHttpClient(original, CLOCK, nanos::get);
            var first = request(body(List.of(SYSTEM, USER), "auto", false));
            recording.beginAttempt(context("main-1", "BUSINESS_MODEL"), "main");
            var future = recording.sendAsync(first, HttpResponse.BodyHandlers.ofByteArray());
            assertSame(original.lastFuture, future, "返回原 Future，取消不丢失");
            assertArrayEquals(original.responseBytes(), future.get().body());
            assertArrayEquals(bodyBytes(first), original.lastBody);
            assertEquals(first.uri(), original.lastRequest.uri());
            assertSame(first.headers(), original.lastRequest.headers());
            assertEquals(first.method(), original.lastRequest.method());
            assertEquals(first.timeout(), original.lastRequest.timeout());
            assertEquals(first.version(), original.lastRequest.version());
            assertEquals(first.expectContinue(), original.lastRequest.expectContinue());
            assertEquals(bodyBytes(first).length, original.lastRequest.bodyPublisher().orElseThrow().contentLength());
            recording.terminalAttempt("main-1", "SUCCEEDED");

            recording.beginThreadAttempt(context("aux-1", "AUXILIARY_MODEL"), "profile.summarize");
            recording.sendAsync(request(body(List.of("{\"role\":\"system\",\"content\":\"aux\"}", USER), "none", false)),
                    HttpResponse.BodyHandlers.ofByteArray()).get();
            recording.terminalAttempt("aux-1", "SUCCEEDED");

            recording.beginAttempt(context("main-2", "BUSINESS_MODEL"), "main");
            recording.sendAsync(request(body(List.of(SYSTEM, USER, "{\"role\":\"assistant\",\"content\":\"next\"}"), "none", false)),
                    HttpResponse.BodyHandlers.ofByteArray()).get();
            var records = recording.snapshot();
            var record = records.getLast();
            assertEquals(3, recording.actualRequestCount());
            assertEquals(1, record.requestSubscriptions());
            assertEquals(2L, record.prefixComparison().previousRequestSequence());
            assertEquals(1L, record.scopePrefixComparison().previousRequestSequence());
            assertEquals(2, record.scopePrefixComparison().commonMessages());
            assertTrue(record.scopePrefixComparison().previousMessagesArePrefix());
            assertEquals(1, record.scopePrefixComparison().appendedMessages());
            assertEquals("APPEND_ONLY", record.scopePrefixComparison().boundary());
            assertTrue(record.scopePrefixComparison().toolsUnchanged());
            assertFalse(record.scopePrefixComparison().generationUnchanged(), "tool_choice 变化计入 wire generation 哈希");
            assertEquals(hash(SYSTEM), record.wire().systemSha256());
            assertEquals(hash(TOOLS), record.wire().toolsSha256());
            assertEquals(hash(USER), record.wire().messages().get(1).sha256());
            assertEquals(List.of("sf_tool_0"), record.wire().toolNames());
            assertEquals("none", record.wire().toolChoice());
            assertNull(record.ttftMillis(), "非流式响应无法观测真实首 token");
            assertThrows(UnsupportedOperationException.class, () -> records.add(record));
            assertThrows(UnsupportedOperationException.class, () -> record.rawUsage().put("usage.prompt_tokens", 0L));
            assertThrows(UnsupportedOperationException.class, () -> record.wire().messages().clear());
            assertSafe(JSON.writeValueAsString(records));
        }
    }

    @Test void arbitraryUtf8FragmentsAndCrLfObserveFirstRealDeltaAndRawUsageWithoutChangingBytes() throws Exception {
        for (var kind : List.of("CONTENT", "REASONING", "TOOL")) {
            var nanos = new AtomicLong();
            try (var original = new FakeHttpClient(nanos)) {
                String delta = switch (kind) {
                    case "CONTENT" -> "{\"content\":\"首\"}";
                    case "REASONING" -> "{\"reasoning_content\":\"思\"}";
                    default -> "{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"sf_tool_0\",\"arguments\":\"{\"}}]}";
                };
                String sse = ": keepalive\r\n\r\ndata: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\r\n\r\n"
                        + "data: {\"choices\":[{\"delta\":" + delta + "}]}\r\n\r\n"
                        + "data: {\"choices\":[],\r\ndata: \"usage\":{\"prompt_tokens\":42,\"completion_tokens\":7,"
                        + "\"prompt_cache_hit_tokens\":32,\"prompt_cache_miss_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":32},"
                        + "\"cost\":0.0042,\"cost_reported\":true,\"private_text\":\"RESPONSE_SECRET_SAMPLE\"}}\r\n\r\n"
                        + "data: [DONE]\r\n\r\n";
                original.sse(sse, true);
                var recording = new MemoryRecordingHttpClient(original, CLOCK, nanos::get);
                recording.beginAttempt(context("fragment-" + kind, "BUSINESS_MODEL"));
                var result = recording.sendAsync(request(body(List.of(SYSTEM, USER), "auto", true)),
                        HttpResponse.BodyHandlers.ofByteArray()).get();
                assertArrayEquals(sse.getBytes(StandardCharsets.UTF_8), result.body());
                var record = recording.snapshot().getFirst();
                assertEquals(kind, record.firstDeltaKind());
                assertNotNull(record.ttftMillis());
                assertTrue(record.ttftMillis() > record.bodyMillis());
                assertTrue(record.latencyMillis() > record.ttftMillis());
                assertTrue(record.bodyCompleteMillis() >= record.latencyMillis());
                assertEquals(42L, record.rawUsage().get("usage.prompt_tokens"));
                assertEquals(32L, record.rawUsage().get("usage.prompt_cache_hit_tokens"));
                assertEquals(10L, record.rawUsage().get("usage.prompt_cache_miss_tokens"));
                assertEquals(32L, record.rawUsage().get("usage.prompt_tokens_details.cached_tokens"));
                assertNull(record.rawUsage().get("usage.input_tokens"), "没有报告的字段保留 null");
                assertNull(record.rawUsage().get("usage.cache_creation_input_tokens"));
                assertEquals("0.0042", record.providerReported().costs().get("usage.cost").toPlainString());
                assertNull(record.providerReported().costs().get("usage.total_cost"));
                assertTrue(record.providerReported().flags().get("usage.cost_reported"));
                assertNull(record.providerReported().flags().get("usage.input_tokens_reported"));
                assertNull(record.observationFailure());
                assertSafe(JSON.writeValueAsString(record));
            }
        }
    }

    @Test void doneRecordsLatencyBeforeSubscriberEofAndEofCanCompleteWithoutDone() throws Exception {
        var nanos = new AtomicLong();
        try (var original = new FakeHttpClient(nanos)) {
            original.sse("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\ndata: [DONE]\n\n", false);
            var recording = new MemoryRecordingHttpClient(original, CLOCK, nanos::get);
            recording.beginAttempt(context("done", "BUSINESS_MODEL"));
            recording.sendAsync(request(body(List.of(SYSTEM, USER), "none", true)), info -> immediateBody()).get();
            var record = recording.snapshot().getFirst();
            assertNotNull(record.latencyMillis(), "生产读取方见 DONE 后可能立即关闭，不能等待 subscriber EOF");
            assertNull(record.bodyCompleteMillis());
            assertNull(record.terminalStatus());
            recording.terminalAttempt("done", "SUCCEEDED");
            assertNull(record.terminalStatus(), "旧快照不可随 callback 修改");
            assertEquals("SUCCEEDED", recording.snapshot().getFirst().terminalStatus());
        }
        var eofNanos = new AtomicLong();
        try (var original = new FakeHttpClient(eofNanos)) {
            original.sse("data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}", true);
            var recording = new MemoryRecordingHttpClient(original, CLOCK, eofNanos::get);
            recording.beginAttempt(context("eof", "BUSINESS_MODEL"));
            recording.sendAsync(request(body(List.of(SYSTEM, USER), "none", true)), HttpResponse.BodyHandlers.ofByteArray()).get();
            var record = recording.snapshot().getFirst();
            assertNotNull(record.ttftMillis());
            assertNotNull(record.latencyMillis());
            assertNotNull(record.bodyCompleteMillis());
            assertNull(record.rawUsage().get("usage.prompt_tokens"));
        }
    }

    @Test void explicitThreadAuxAndCrossThreadMainKeepExactAttemptAndUnknownSendFailsBeforeDelegate() throws Exception {
        var nanos = new AtomicLong();
        try (var original = new FakeHttpClient(nanos)) {
            var recording = new MemoryRecordingHttpClient(original, CLOCK, nanos::get);
            var request = request(body(List.of(SYSTEM, USER), "none", false));
            assertThrows(IllegalStateException.class, () -> recording.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()));
            assertEquals(0, original.sends);
            recording.beginAttempt(context("main", "BUSINESS_MODEL"), "main");
            recording.beginThreadAttempt(context("aux", "AUXILIARY_MODEL"), "profile.summarize");
            recording.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).get();
            assertEquals("aux", recording.snapshot().getFirst().attempt().attemptId());
            recording.terminalAttempt("aux", "SUCCEEDED");
            CompletableFuture.supplyAsync(() -> recording.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()))
                    .get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
            assertEquals("main", recording.snapshot().getLast().attempt().attemptId());
            recording.terminalAttempt("main", "SUCCEEDED");
            var other = HttpRequest.newBuilder(URI.create("https://example.invalid/v1/models?private=QUERY_SECRET_SAMPLE")).GET().build();
            recording.sendAsync(other, HttpResponse.BodyHandlers.ofByteArray()).get();
            assertEquals(3, original.sends);
            assertEquals(2, recording.actualRequestCount(), "非聊天请求正常委托，不占用 attempt 或写报告");
        }
    }

    @Test void malformedResponseAndTransportFailureKeepSafeClassificationsAndNeverInventUsageOrCost() throws Exception {
        var nanos = new AtomicLong();
        try (var original = new FakeHttpClient(nanos)) {
            original.sse("data: RESPONSE_SECRET_SAMPLE\r\n\r\ndata: [DONE]\r\n\r\n", true);
            var recording = new MemoryRecordingHttpClient(original, CLOCK, nanos::get);
            recording.beginAttempt(context("invalid", "BUSINESS_MODEL"));
            var response = recording.sendAsync(request(body(List.of(SYSTEM, USER), "none", true)), HttpResponse.BodyHandlers.ofByteArray()).get();
            assertArrayEquals(original.responseBytes(), response.body(), "观察失败不改原响应");
            recording.terminalAttempt("invalid", "UNRESOLVED");
            original.failure = new IOException("RESPONSE_SECRET_SAMPLE Authorization=HEADER_SECRET_SAMPLE");
            recording.beginAttempt(context("failed", "BUSINESS_MODEL"));
            assertThrows(Exception.class, () -> recording.sendAsync(request(body(List.of(SYSTEM, USER), "none", false)),
                    HttpResponse.BodyHandlers.ofByteArray()).get());
            recording.terminalAttempt("failed", "FAILED");
            assertEquals(2, recording.failureCount());
            var records = recording.snapshot();
            assertEquals("RESPONSE_OBSERVATION_REJECTED", records.getFirst().observationFailure());
            assertEquals("TRANSPORT_FAILED", records.getLast().transportFailure());
            assertTrue(records.getLast().rawUsage().values().stream().allMatch(value -> value == null));
            assertTrue(records.getLast().providerReported().costs().values().stream().allMatch(value -> value == null));
            assertNull(records.getLast().ttftMillis());
            assertNull(records.getLast().latencyMillis());
            assertSafe(JSON.writeValueAsString(records));
        }
    }

    /** 构造固定安全 audit 元数据；secret 字符串只存在于伪载荷，检测报告泄漏。 */
    private static MemoryRecordingHttpClient.AttemptContext context(String id, String category) {
        return new MemoryRecordingHttpClient.AttemptContext(id, "run-1", category, "MODEL_GENERATE", "NativeConversation", "AUTO");
    }
    private static String body(List<String> messages, String choice, boolean stream) {
        return "{\"model\":\"deepseek-flash\",\"messages\":[" + String.join(",", messages) + "],\"tools\":" + TOOLS
                + ",\"tool_choice\":\"" + choice + "\",\"stream\":" + stream + ",\"max_tokens\":1024}";
    }
    private static HttpRequest request(String body) {
        return HttpRequest.newBuilder(URI.create("https://example.invalid/v1/chat/completions?private=QUERY_SECRET_SAMPLE"))
                .header("Authorization", "Bearer HEADER_SECRET_SAMPLE").header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(13)).version(HttpClient.Version.HTTP_1_1).expectContinue(true)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }
    private static byte[] bodyBytes(HttpRequest request) {
        var out = new ByteArrayOutputStream();
        request.bodyPublisher().ifPresent(publisher -> publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer bytes) { var copy = new byte[bytes.remaining()]; bytes.get(copy); out.writeBytes(copy); }
            @Override public void onError(Throwable error) { fail(error); }
            @Override public void onComplete() { }
        }));
        return out.toByteArray();
    }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static void assertSafe(String report) {
        for (var secret : List.of("PRIVATE_BODY_SAMPLE", "RESPONSE_SECRET_SAMPLE", "HEADER_SECRET_SAMPLE", "QUERY_SECRET_SAMPLE", "example.invalid")) {
            assertFalse(report.contains(secret), "正文、头、URL、异常消息不入报告");
        }
    }
    private static HttpResponse.BodySubscriber<String> immediateBody() {
        return new HttpResponse.BodySubscriber<>() {
            @Override public CompletionStage<String> getBody() { return CompletableFuture.completedFuture("original-body"); }
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(List<ByteBuffer> bytes) { }
            @Override public void onError(Throwable error) { }
            @Override public void onComplete() { }
        };
    }

    /** 可控订阅假体；每个 response byte 都单独分片，覆盖 UTF-8 字符与 CRLF 跨块。 */
    private static final class FakeHttpClient extends HttpClient {
        private final HttpClient settings = HttpClient.newBuilder().followRedirects(Redirect.NEVER).build();
        private final AtomicLong nanos;
        private List<byte[]> chunks = List.of("{\"choices\":[],\"usage\":null}".getBytes(StandardCharsets.UTF_8));
        private String contentType = "application/json";
        private boolean complete = true;
        private IOException failure;
        private HttpRequest lastRequest;
        private byte[] lastBody;
        private CompletableFuture<?> lastFuture;
        private int sends;
        FakeHttpClient(AtomicLong nanos) { this.nanos = nanos; }
        void sse(String body, boolean complete) {
            chunks = new ArrayList<>();
            for (byte value : body.getBytes(StandardCharsets.UTF_8)) chunks.add(new byte[] { value });
            contentType = "text/event-stream"; this.complete = complete;
        }
        byte[] responseBytes() { var out = new ByteArrayOutputStream(); chunks.forEach(out::writeBytes); return out.toByteArray(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            sends++; lastRequest = request; lastBody = bodyBytes(request);
            if (failure != null) { var future = CompletableFuture.<HttpResponse<T>>failedFuture(failure); lastFuture = future; return future; }
            nanos.addAndGet(10_000_000);
            var headers = HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (name, value) -> true);
            var subscriber = handler.apply(new HttpResponse.ResponseInfo() {
                @Override public int statusCode() { return 200; }
                @Override public HttpHeaders headers() { return headers; }
                @Override public Version version() { return Version.HTTP_1_1; }
            });
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) { assertTrue(count > 0); }
                @Override public void cancel() { }
            });
            for (var chunk : chunks) {
                nanos.addAndGet(1_000_000);
                // 非零 offset/read-only buffer 防止观察器误动 position 或依赖 backing array。
                var padded = new byte[chunk.length + 2]; System.arraycopy(chunk, 0, padded, 1, chunk.length);
                subscriber.onNext(List.of(ByteBuffer.wrap(padded, 1, chunk.length).asReadOnlyBuffer()));
            }
            if (complete) { nanos.addAndGet(1_000_000); subscriber.onComplete(); }
            T body = subscriber.getBody().toCompletableFuture().join();
            HttpResponse<T> response = new HttpResponse<T>() {
                @Override public int statusCode() { return 200; }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return headers; }
                @Override public T body() { return body; }
                @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
            var future = CompletableFuture.completedFuture(response); lastFuture = future; return future;
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> push) { return sendAsync(request, handler); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            try { return sendAsync(request, handler).join(); }
            catch (RuntimeException error) { if (failure != null) throw failure; throw error; }
        }
        @Override public Optional<CookieHandler> cookieHandler() { return settings.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return settings.connectTimeout(); }
        @Override public Redirect followRedirects() { return settings.followRedirects(); }
        @Override public Optional<ProxySelector> proxy() { return settings.proxy(); }
        @Override public SSLContext sslContext() { return settings.sslContext(); }
        @Override public SSLParameters sslParameters() { return settings.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return settings.authenticator(); }
        @Override public Version version() { return settings.version(); }
        @Override public Optional<Executor> executor() { return settings.executor(); }
        @Override public void close() { settings.close(); }
    }
}
