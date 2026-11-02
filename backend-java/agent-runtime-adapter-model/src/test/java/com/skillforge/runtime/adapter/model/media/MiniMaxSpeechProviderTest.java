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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MiniMaxSpeechProviderTest {
    private static final URI DOMESTIC = URI.create("https://api.minimax.cn/v1");
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private RelayClient client;
    private MiniMaxSpeechProvider provider;
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<String> contentType = new AtomicReference<>("application/json");
    private final AtomicInteger statusCode = new AtomicInteger(200);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> accept = new AtomicReference<>();
    private final AtomicBoolean holdBody = new AtomicBoolean();
    private final CountDownLatch bodyStarted = new CountDownLatch(1);
    private final CountDownLatch releaseBody = new CountDownLatch(1);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            received.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType.get());
            exchange.sendResponseHeaders(statusCode.get(), holdBody.get() ? 0 : bytes.length);
            try {
                if (holdBody.get()) {
                    exchange.getResponseBody().write(' '); exchange.getResponseBody().flush();
                    bodyStarted.countDown(); releaseBody.await(5, TimeUnit.SECONDS);
                }
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        client = new RelayClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        provider = new MiniMaxSpeechProvider(client, Clock.systemUTC(), 32_000);
    }

    @AfterEach void close() throws Exception {
        releaseBody.countDown();
        if (server != null) server.stop(0);
        if (executor != null) { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        if (client != null) { client.shutdownNow(); assertTrue(client.awaitTermination(Duration.ofSeconds(5))); }
    }

    private static Connection connection() { return new Connection(DOMESTIC, "synthetic-key", Duration.ofSeconds(3), () -> false); }
    private static SpeechRequest speech() { return new SpeechRequest("speech-02-hd", "你好", "male-qn-qingse", "Chinese"); }

    @Test void observesTheActualSpeechHttpDispatchOnce() {
        var observed = new AtomicInteger();
        var audited = new Connection(DOMESTIC, "synthetic-key", Duration.ofSeconds(3), () -> false,
                () -> { assertTrue(client.sent.isEmpty()); observed.incrementAndGet(); });
        responseBody.set("{\"data\":{\"audio\":\"0102\",\"status\":2},\"base_resp\":{\"status_code\":0}}");
        assertEquals(Status.SUCCEEDED, provider.execute(audited, speech()).status());
        assertEquals(1, observed.get()); assertEquals(1, client.sent.size()); assertEquals(1, requests.get());
    }

    @Test void dispatchAuditFailurePropagatesBeforeAnyHttpRequest() {
        var rejected = new IllegalArgumentException("local_audit_rejected");
        var observed = new AtomicInteger();
        var audited = new Connection(DOMESTIC, "synthetic-key", Duration.ofSeconds(3), () -> false,
                () -> { observed.incrementAndGet(); throw rejected; });
        assertSame(rejected, assertThrows(IllegalArgumentException.class, () -> provider.execute(audited, speech())));
        assertEquals(1, observed.get()); assertTrue(client.sent.isEmpty()); assertEquals(0, requests.get());
    }

    @Test void cancellationDuringDispatchAuditRejectsSyncAndStreamingBeforeAnyHttpRequest() throws Exception {
        var cancelled = new AtomicBoolean();
        var observed = new AtomicInteger();
        var audited = new Connection(DOMESTIC, "synthetic-key", Duration.ofSeconds(3), cancelled::get,
                () -> { observed.incrementAndGet(); cancelled.set(true); });
        var refused = provider.execute(audited, speech());
        assertEquals(Status.NOT_SUBMITTED, refused.status());
        assertEquals("media_cancelled_before_dispatch", refused.reasonCode());
        assertEquals(1, observed.get()); assertTrue(client.sent.isEmpty()); assertEquals(0, requests.get());

        cancelled.set(false);
        var subscriber = new SpeechSubscriber();
        provider.streamSpeech(audited, speech()).subscribe(subscriber);
        assertTrue(subscriber.done.await(3, TimeUnit.SECONDS)); assertNull(subscriber.error.get());
        assertEquals(1, subscriber.events.size());
        var result = ((SpeechEvent.Completed) subscriber.events.getFirst()).result();
        assertEquals(Status.NOT_SUBMITTED, result.status());
        assertEquals("media_cancelled_before_dispatch", result.reasonCode());
        assertEquals(2, observed.get()); assertTrue(client.sent.isEmpty()); assertEquals(0, requests.get());
    }

    @Test void deadlineReachedDuringDispatchAuditRejectsSyncAndStreamingBeforeAnyHttpRequest() throws Exception {
        var time = new MediaDispatchTestClock();
        var bounded = new MiniMaxSpeechProvider(client, time, 32_000);
        var observed = new AtomicInteger();
        var audited = new Connection(DOMESTIC, "synthetic-key", Duration.ofSeconds(3), () -> false,
                () -> { observed.incrementAndGet(); time.advance(Duration.ofSeconds(3)); });
        var refused = bounded.execute(audited, speech());
        assertEquals(Status.NOT_SUBMITTED, refused.status());
        assertEquals("media_deadline_before_dispatch", refused.reasonCode());
        assertEquals(1, observed.get()); assertTrue(client.sent.isEmpty()); assertEquals(0, requests.get());

        var subscriber = new SpeechSubscriber();
        bounded.streamSpeech(audited, speech()).subscribe(subscriber);
        assertTrue(subscriber.done.await(3, TimeUnit.SECONDS)); assertNull(subscriber.error.get());
        assertEquals(1, subscriber.events.size());
        var result = ((SpeechEvent.Completed) subscriber.events.getFirst()).result();
        assertEquals(Status.NOT_SUBMITTED, result.status());
        assertEquals("media_deadline_before_dispatch", result.reasonCode());
        assertEquals(2, observed.get()); assertTrue(client.sent.isEmpty()); assertEquals(0, requests.get());
    }

    @Test void streamingAuditRunsOnceBeforeHttpAndFailureReachesSubscriberWithoutSending() throws Exception {
        var rejected = new IllegalStateException("local_audit_unavailable");
        var observed = new AtomicInteger();
        var rejectedConnection = new Connection(DOMESTIC, "synthetic-key", Duration.ofSeconds(3), () -> false,
                () -> { observed.incrementAndGet(); throw rejected; });
        var refused = new SpeechSubscriber();
        provider.streamSpeech(rejectedConnection, speech()).subscribe(refused);
        assertTrue(refused.done.await(3, TimeUnit.SECONDS)); assertSame(rejected, refused.error.get());
        assertTrue(refused.events.isEmpty()); assertEquals(1, observed.get());
        assertTrue(client.sent.isEmpty()); assertEquals(0, requests.get());

        var audited = new Connection(DOMESTIC, "synthetic-key", Duration.ofSeconds(3), () -> false,
                () -> { assertTrue(client.sent.isEmpty()); observed.incrementAndGet(); });
        contentType.set("text/event-stream");
        responseBody.set("data: {\"data\":{\"audio\":\"0102\",\"status\":2},\"base_resp\":{\"status_code\":0}}\n\n");
        var accepted = new SpeechSubscriber();
        provider.streamSpeech(audited, speech()).subscribe(accepted);
        assertTrue(accepted.done.await(3, TimeUnit.SECONDS)); assertNull(accepted.error.get());
        assertEquals(Status.SUCCEEDED, ((SpeechEvent.Completed) accepted.events.getLast()).result().status());
        assertEquals(2, observed.get()); assertEquals(1, client.sent.size()); assertEquals(1, requests.get());
    }

    @Test void returnsActualMp3BytesAndConvertsReportedMillisecondsWithoutInventingUsage() {
        responseBody.set("""
                {"data":{"audio":"010203ff","status":2},"trace_id":"speech-1","base_resp":{"status_code":0,"status_msg":"success"},"extra_info":{"audio_length":9901,"audio_sample_rate":32000,"audio_format":"mp3","audio_channel":1,"usage_characters":26,"word_count":52}}
                """);
        var result = provider.execute(connection(), speech());
        assertEquals(Status.SUCCEEDED, result.status()); assertEquals("speech-1", result.requestId());
        var audio = result.assets().getFirst();
        assertEquals(AssetKind.AUDIO, audio.kind()); assertEquals("audio/mpeg", audio.mediaType());
        assertArrayEquals(new byte[]{1, 2, 3, (byte) 255}, ((BytesSource) audio.source()).bytes());
        assertEquals(new BigDecimal("9.901"), result.usage().durationSeconds());
        assertEquals(26L, result.usage().characters()); assertNull(result.usage().inputTokens());
        assertEquals("Bearer synthetic-key", auth.get());
        assertEquals("application/json", accept.get());
        assertEquals("https://api.minimax.cn/v1/t2a_v2", client.sent.getFirst().uri().toString());
        assertEquals("POST", client.sent.getFirst().method());
        assertEquals("hex", received.get().path("output_format").textValue());
        assertFalse(received.get().path("stream").booleanValue());
        assertEquals("male-qn-qingse", received.get().path("voice_setting").path("voice_id").textValue());
        assertEquals("Chinese", received.get().path("language_boost").textValue());
        assertEquals("mp3", received.get().path("audio_setting").path("format").textValue());
        assertFalse(result.toString().contains("010203ff")); assertFalse(connection().toString().contains("synthetic-key"));
        assertEquals(1, requests.get());
    }

    @Test void leavesUnreportedUsageNullEvenWhenWordCountIsPresent() {
        responseBody.set("{\"data\":{\"audio\":\"01\",\"status\":2},\"base_resp\":{\"status_code\":0},\"extra_info\":{\"word_count\":52}}");
        var result = provider.execute(connection(), speech());
        assertEquals(Status.SUCCEEDED, result.status()); assertNull(result.usage());
    }

    @Test void rejectsInternationalLegacyAndUncontrolledEndpointsBeforeSendingCredentials() {
        var observed = new AtomicInteger();
        for (String endpoint : List.of("https://api.minimax.io/v1", "https://api.minimaxi.com/v1", "http://api.minimax.cn/v1",
                "https://api.minimax.cn:8443/v1", "https://api.minimax.cn/v1?GroupId=1", "https://api.minimax.cn/v1/t2a_v2")) {
            var result = provider.execute(new Connection(URI.create(endpoint), "synthetic-key", Duration.ofSeconds(3), () -> false, observed::incrementAndGet), speech());
            assertEquals(Status.NOT_SUBMITTED, result.status(), endpoint);
        }
        assertEquals(Status.NOT_SUBMITTED, provider.execute(new Connection(DOMESTIC, "synthetic-key", Duration.ofSeconds(3), () -> true, observed::incrementAndGet), speech()).status());
        var audited = new Connection(DOMESTIC, "synthetic-key", Duration.ofSeconds(3), () -> false, observed::incrementAndGet);
        assertEquals(Status.NOT_SUBMITTED, provider.execute(audited, new TaskQuery(TaskKind.VIDEO, "task-1")).status());
        assertEquals(Status.NOT_SUBMITTED, provider.execute(audited, new SpeechRequest("speech-02-hd", "字".repeat(10000), "voice", null)).status());
        var cancellationChecks = new AtomicInteger();
        var cancelledAfterPreparation = new Connection(DOMESTIC, "synthetic-key", Duration.ofSeconds(3),
                () -> cancellationChecks.incrementAndGet() > 1, observed::incrementAndGet);
        assertEquals(Status.NOT_SUBMITTED, provider.execute(cancelledAfterPreparation, speech()).status());
        assertEquals(0, observed.get()); assertEquals(0, requests.get()); assertTrue(client.sent.isEmpty());
        for (String path : List.of("", "/", "/v1", "/v1/"))
            assertEquals(URI.create("https://api.minimax.cn/v1/t2a_v2"), MiniMaxSpeechProvider.nativeEndpoint(URI.create("https://api.minimax.cn" + path)));
    }

    @Test void preservesUnknownForMalformedHexMissingSuccessCodeAndIncompleteResponsesWithoutRetry() {
        for (String fixture : List.of("{", "{\"data\":{\"audio\":\"zz\",\"status\":2},\"base_resp\":{\"status_code\":0}}",
                "{\"data\":{\"audio\":\"01\",\"status\":2}}", "{\"data\":{\"audio\":\"01\",\"status\":1},\"base_resp\":{\"status_code\":0}}",
                "{\"data\":{\"audio\":\"01\",\"status\":2},\"base_resp\":{\"status_code\":0},\"extra_info\":{\"usage_characters\":-1}}")) {
            responseBody.set(fixture);
            assertEquals(Status.UNKNOWN, provider.execute(connection(), speech()).status());
        }
        assertEquals(5, requests.get());
    }

    @Test void distinguishesVendorRejectionFromServerOutcomeUnknownAndNeverRetries() {
        responseBody.set("{\"trace_id\":\"reject-1\",\"base_resp\":{\"status_code\":1004,\"status_msg\":\"private key rejected\"}}");
        var rejected = provider.execute(connection(), speech());
        assertEquals(Status.FAILED, rejected.status()); assertEquals("reject-1", rejected.requestId());
        assertFalse(rejected.toString().contains("private"));
        statusCode.set(401);
        assertEquals("media_authentication_rejected", provider.execute(connection(), speech()).reasonCode());
        statusCode.set(500);
        assertEquals(Status.UNKNOWN, provider.execute(connection(), speech()).status());
        assertEquals(3, requests.get());
    }

    @Test void streamsHexDeltasWithExplicitCompletionAndDoesNotRepeatFinalAggregatedAudio() throws Exception {
        contentType.set("text/event-stream; charset=utf-8");
        responseBody.set("""
                : heartbeat
                data: {"data":{"audio":"0102","status":1},"trace_id":"stream-1","base_resp":{"status_code":0}}

                data: {"data":{"audio":"03ff","status":1},"trace_id":"stream-1","base_resp":{"status_code":0}}

                data: {"data":{"audio":"010203ff","status":2},"trace_id":"stream-final","base_resp":{"status_code":0},"extra_info":{"audio_length":1200,"usage_characters":2}}

                """);
        var subscriber = new SpeechSubscriber();
        provider.streamSpeech(connection(), speech()).subscribe(subscriber);
        assertTrue(subscriber.done.await(3, TimeUnit.SECONDS)); assertNull(subscriber.error.get());
        assertEquals(3, subscriber.events.size());
        var first = (SpeechEvent.AudioChunk) subscriber.events.getFirst();
        assertArrayEquals(new byte[]{1, 2}, first.bytes()); assertEquals("mp3", first.encoding());
        assertEquals(32000, first.sampleRateHz()); assertEquals(1, first.channels());
        assertArrayEquals(new byte[]{3, (byte) 255}, ((SpeechEvent.AudioChunk) subscriber.events.get(1)).bytes());
        var terminal = ((SpeechEvent.Completed) subscriber.events.getLast()).result();
        assertEquals(Status.SUCCEEDED, terminal.status()); assertEquals("stream-final", terminal.requestId());
        assertEquals(new BigDecimal("1.200"), terminal.usage().durationSeconds());
        assertEquals(1, terminal.assets().size());
        var completedAudio = terminal.assets().getFirst();
        assertEquals(AssetKind.AUDIO, completedAudio.kind()); assertEquals("audio/mpeg", completedAudio.mediaType());
        assertArrayEquals(new byte[]{1, 2, 3, (byte) 255}, ((BytesSource) completedAudio.source()).bytes());
        assertEquals("text/event-stream", accept.get());
        assertTrue(received.get().path("stream_options").path("exclude_aggregated_audio").booleanValue());
        assertTrue(received.get().path("stream").booleanValue()); assertEquals(1, requests.get());
    }

    @Test void eofAfterAudioPreservesTheRealChunkAndReportsUnknown() throws Exception {
        contentType.set("text/event-stream");
        responseBody.set("data: {\"data\":{\"audio\":\"0102\",\"status\":1},\"base_resp\":{\"status_code\":0}}\n\n");
        var subscriber = new SpeechSubscriber();
        provider.streamSpeech(connection(), speech()).subscribe(subscriber);
        assertTrue(subscriber.done.await(3, TimeUnit.SECONDS));
        assertEquals(2, subscriber.events.size()); assertInstanceOf(SpeechEvent.AudioChunk.class, subscriber.events.getFirst());
        var incomplete = ((SpeechEvent.Completed) subscriber.events.getLast()).result();
        assertEquals(Status.UNKNOWN, incomplete.status()); assertTrue(incomplete.assets().isEmpty());
        assertEquals(1, requests.get());
    }

    @Test void completedAssetConcatenatesActualDeltasWhenFinalAggregationIsExcluded() throws Exception {
        contentType.set("text/event-stream");
        responseBody.set("data: {\"data\":{\"audio\":\"0102\",\"status\":1},\"base_resp\":{\"status_code\":0}}\n\n"
                + "data: {\"data\":{\"audio\":\"03ff\",\"status\":1},\"base_resp\":{\"status_code\":0}}\n\n"
                + "data: {\"data\":{\"status\":2},\"base_resp\":{\"status_code\":0}}\n\n");
        var subscriber = new SpeechSubscriber();
        provider.streamSpeech(connection(), speech()).subscribe(subscriber);
        assertTrue(subscriber.done.await(3, TimeUnit.SECONDS)); assertNull(subscriber.error.get());
        assertEquals(3, subscriber.events.size());
        var result = ((SpeechEvent.Completed) subscriber.events.getLast()).result();
        assertEquals(Status.SUCCEEDED, result.status()); assertEquals(1, result.assets().size());
        assertArrayEquals(new byte[]{1, 2, 3, (byte) 255}, ((BytesSource) result.assets().getFirst().source()).bytes());
        assertEquals(1, requests.get());
    }

    @Test void singleTerminalAudioIsReturnedOnceAndAnEmptyTerminalCannotCreateACompletedAsset() throws Exception {
        contentType.set("text/event-stream");
        responseBody.set("data: {\"data\":{\"audio\":\"010203ff\",\"status\":2},\"base_resp\":{\"status_code\":0}}\n\n");
        var subscriber = new SpeechSubscriber();
        provider.streamSpeech(connection(), speech()).subscribe(subscriber);
        assertTrue(subscriber.done.await(3, TimeUnit.SECONDS)); assertNull(subscriber.error.get());
        assertEquals(2, subscriber.events.size());
        assertArrayEquals(new byte[]{1, 2, 3, (byte) 255}, ((SpeechEvent.AudioChunk) subscriber.events.getFirst()).bytes());
        var result = ((SpeechEvent.Completed) subscriber.events.getLast()).result();
        assertEquals(Status.SUCCEEDED, result.status());
        assertArrayEquals(new byte[]{1, 2, 3, (byte) 255}, ((BytesSource) result.assets().getFirst().source()).bytes());
        responseBody.set("data: {\"data\":{\"status\":2},\"base_resp\":{\"status_code\":0}}\n\n");
        var empty = new SpeechSubscriber();
        provider.streamSpeech(connection(), speech()).subscribe(empty);
        assertTrue(empty.done.await(3, TimeUnit.SECONDS)); assertEquals(1, empty.events.size());
        var incomplete = ((SpeechEvent.Completed) empty.events.getFirst()).result();
        assertEquals(Status.UNKNOWN, incomplete.status()); assertTrue(incomplete.assets().isEmpty());
        assertEquals(2, requests.get());
    }

    @Test void vendorFailureAfterRealAudioDoesNotExposeAnIncompleteAssetAsComplete() throws Exception {
        contentType.set("text/event-stream");
        responseBody.set("data: {\"data\":{\"audio\":\"0102\",\"status\":1},\"base_resp\":{\"status_code\":0}}\n\n"
                + "data: {\"base_resp\":{\"status_code\":1004}}\n\n");
        var subscriber = new SpeechSubscriber();
        provider.streamSpeech(connection(), speech()).subscribe(subscriber);
        assertTrue(subscriber.done.await(3, TimeUnit.SECONDS)); assertNull(subscriber.error.get());
        assertEquals(2, subscriber.events.size()); assertInstanceOf(SpeechEvent.AudioChunk.class, subscriber.events.getFirst());
        var failure = ((SpeechEvent.Completed) subscriber.events.getLast()).result();
        assertEquals(Status.FAILED, failure.status()); assertTrue(failure.assets().isEmpty());
        assertEquals(1, requests.get());
    }

    @Test void streamingJsonVendorErrorRemainsAReportedFailure() throws Exception {
        responseBody.set("{\"trace_id\":\"reject-stream\",\"base_resp\":{\"status_code\":1004}}");
        var subscriber = new SpeechSubscriber();
        provider.streamSpeech(connection(), speech()).subscribe(subscriber);
        assertTrue(subscriber.done.await(3, TimeUnit.SECONDS));
        assertEquals(1, subscriber.events.size());
        var result = ((SpeechEvent.Completed) subscriber.events.getFirst()).result();
        assertEquals(Status.FAILED, result.status()); assertEquals("reject-stream", result.requestId());
    }

    @Test void responseLimitAndBodyDeadlineStopTheOriginalCallWithoutResubmission() throws Exception {
        responseBody.set("{\"padding\":\"" + "x".repeat(1000) + "\"}");
        var small = new MiniMaxSpeechProvider(client, Clock.systemUTC(), 64);
        assertEquals(Status.UNKNOWN, small.execute(connection(), speech()).status());
        holdBody.set(true); responseBody.set("{}");
        var result = CompletableFuture.supplyAsync(() -> provider.execute(new Connection(DOMESTIC, "synthetic-key", Duration.ofMillis(500), () -> false), speech()));
        assertTrue(bodyStarted.await(2, TimeUnit.SECONDS));
        assertEquals(Status.UNKNOWN, result.get(2, TimeUnit.SECONDS).status()); assertEquals(2, requests.get());
    }

    @Test void streamSizeLimitDoesNotClaimCompletion() throws Exception {
        contentType.set("text/event-stream");
        responseBody.set("data: {\"data\":{\"audio\":\"" + "01".repeat(100) + "\",\"status\":2},\"base_resp\":{\"status_code\":0}}\n\n");
        var subscriber = new SpeechSubscriber();
        new MiniMaxSpeechProvider(client, Clock.systemUTC(), 64).streamSpeech(connection(), speech()).subscribe(subscriber);
        assertTrue(subscriber.done.await(3, TimeUnit.SECONDS));
        var incomplete = ((SpeechEvent.Completed) subscriber.events.getLast()).result();
        assertEquals(Status.UNKNOWN, incomplete.status()); assertTrue(incomplete.assets().isEmpty());
        assertEquals(1, requests.get());
    }

    @Test void downstreamCancellationAfterTheFirstAudioDoesNotReportFalseSuccess() throws Exception {
        contentType.set("text/event-stream");
        responseBody.set("data: {\"data\":{\"audio\":\"0102\",\"status\":1},\"base_resp\":{\"status_code\":0}}\n\n"
                + "data: {\"data\":{\"status\":2},\"base_resp\":{\"status_code\":0}}\n\n");
        var audioReceived = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<SpeechEvent>();
        provider.streamSpeech(connection(), speech()).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(Long.MAX_VALUE); }
            @Override public void onNext(SpeechEvent item) { events.add(item); subscription.cancel(); audioReceived.countDown(); }
            @Override public void onError(Throwable failure) { fail(failure); }
            @Override public void onComplete() { fail("Cancelled subscription must not complete"); }
        });
        assertTrue(audioReceived.await(3, TimeUnit.SECONDS));
        assertEquals(1, events.size()); assertInstanceOf(SpeechEvent.AudioChunk.class, events.getFirst()); assertEquals(1, requests.get());
    }

    private static final class SpeechSubscriber implements Flow.Subscriber<SpeechEvent> {
        final List<SpeechEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
        @Override public void onNext(SpeechEvent item) { events.add(item); }
        @Override public void onError(Throwable failure) { error.set(failure); done.countDown(); }
        @Override public void onComplete() { done.countDown(); }
    }

    /** 保留官方 URI 与认证请求，仅把实际传输转到受控本地 HTTP 夹具。 */
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
