package com.skillforge.runtime.adapter.model.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.AssetKind;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Connection;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.ImageRequest;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Result;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.SpeechEvent;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.SpeechRequest;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Status;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.TaskKind;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.TaskQuery;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.TranscriptionRequest;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.VideoInput;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.VideoInputKind;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.VideoRequest;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
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
import static org.junit.jupiter.api.Assertions.*;

class BailianMediaProviderTest {
    private HttpServer server;
    private ExecutorService executor;
    private RelayClient client;
    private BailianMediaProvider provider;
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>("application/json");
    private final AtomicInteger statusCode = new AtomicInteger(200);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> asyncHeader = new AtomicReference<>();
    private final AtomicReference<String> streamHeader = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicBoolean holdBody = new AtomicBoolean();
    private final CountDownLatch bodyStarted = new CountDownLatch(1);
    private final CountDownLatch releaseBody = new CountDownLatch(1);
    private static final URI BEIJING = URI.create("https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
    private static final String IMAGE_URL = "https://result.oss-cn-beijing.aliyuncs.com/image.png?signature=private";
    private static final String AUDIO_URL = "http://result.oss-cn-beijing.aliyuncs.com/audio.wav?signature=private";
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            path.set(exchange.getRequestURI().getPath()); method.set(exchange.getRequestMethod());
            asyncHeader.set(exchange.getRequestHeaders().getFirst("X-DashScope-Async"));
            streamHeader.set(exchange.getRequestHeaders().getFirst("X-DashScope-SSE"));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] input = exchange.getRequestBody().readAllBytes();
            if (input.length > 0) received.set(JSON.readTree(input));
            byte[] response = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType.get());
            exchange.sendResponseHeaders(statusCode.get(), holdBody.get() ? 0 : response.length);
            try {
                if (holdBody.get()) {
                    exchange.getResponseBody().write(' '); exchange.getResponseBody().flush();
                    bodyStarted.countDown(); releaseBody.await(5, TimeUnit.SECONDS);
                }
                exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        client = new RelayClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        provider = new BailianMediaProvider(client, Clock.systemUTC(), 32_000);
    }

    @AfterEach void close() throws Exception {
        releaseBody.countDown();
        if (server != null) server.stop(0);
        if (executor != null) { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        if (client != null) { client.shutdownNow(); assertTrue(client.awaitTermination(Duration.ofSeconds(5))); }
    }

    private static Connection connection() { return new Connection(BEIJING, "synthetic-key", Duration.ofSeconds(3), () -> false); }
    private static ImageRequest image() { return new ImageRequest("qwen-image", "create image", List.of(), "1024*1024", 1, null, null, false, false); }
    private static SpeechRequest speech() { return new SpeechRequest("qwen3-tts-flash", "你好", "Cherry", "Chinese"); }
    private static VideoRequest video(List<VideoInput> media, Integer duration, Boolean promptExtend) {
        return new VideoRequest("wan3.0-video", "animate", media, "720P", "16:9", duration, false, 7L, promptExtend, false);
    }

    @Test void observesEachActualHttpDispatchOnceIncludingOriginalTaskQuery() {
        var observed = new AtomicInteger();
        var audited = new Connection(BEIJING, "synthetic-key", Duration.ofSeconds(3), () -> false,
                () -> assertEquals(client.sent.size(), observed.getAndIncrement()));
        responseBody.set("""
                {"request_id":"create-1","output":{"task_id":"original","task_status":"PENDING"}}
                """);
        assertEquals(Status.PENDING, provider.execute(audited, video(List.of(), 5, true)).status());
        responseBody.set("""
                {"request_id":"query-1","output":{"task_id":"original","task_status":"RUNNING"}}
                """);
        assertEquals(Status.RUNNING, provider.execute(audited, new TaskQuery(TaskKind.VIDEO, "original")).status());
        assertEquals(2, observed.get()); assertEquals(2, client.sent.size()); assertEquals(2, requests.get());
    }

    @Test void dispatchAuditFailurePropagatesBeforeAnyHttpRequest() {
        var rejected = new IllegalArgumentException("local_audit_rejected");
        var observed = new AtomicInteger();
        var audited = new Connection(BEIJING, "synthetic-key", Duration.ofSeconds(3), () -> false,
                () -> { observed.incrementAndGet(); throw rejected; });
        assertSame(rejected, assertThrows(IllegalArgumentException.class, () -> provider.execute(audited, image())));
        assertEquals(1, observed.get()); assertTrue(client.sent.isEmpty()); assertEquals(0, requests.get());
    }

    @Test void cancellationDuringDispatchAuditRejectsSyncAndStreamingBeforeAnyHttpRequest() throws Exception {
        var cancelled = new AtomicBoolean();
        var observed = new AtomicInteger();
        var audited = new Connection(BEIJING, "synthetic-key", Duration.ofSeconds(3), cancelled::get,
                () -> { observed.incrementAndGet(); cancelled.set(true); });
        var refused = provider.execute(audited, image());
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
        var bounded = new BailianMediaProvider(client, time, 32_000);
        var observed = new AtomicInteger();
        var audited = new Connection(BEIJING, "synthetic-key", Duration.ofSeconds(3), () -> false,
                () -> { observed.incrementAndGet(); time.advance(Duration.ofSeconds(3)); });
        var refused = bounded.execute(audited, image());
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
        var rejectedConnection = new Connection(BEIJING, "synthetic-key", Duration.ofSeconds(3), () -> false,
                () -> { observed.incrementAndGet(); throw rejected; });
        var refused = new SpeechSubscriber();
        provider.streamSpeech(rejectedConnection, speech()).subscribe(refused);
        assertTrue(refused.done.await(3, TimeUnit.SECONDS)); assertSame(rejected, refused.error.get());
        assertTrue(refused.events.isEmpty()); assertEquals(1, observed.get());
        assertTrue(client.sent.isEmpty()); assertEquals(0, requests.get());

        var audited = new Connection(BEIJING, "synthetic-key", Duration.ofSeconds(3), () -> false,
                () -> { assertTrue(client.sent.isEmpty()); observed.incrementAndGet(); });
        contentType.set("text/event-stream");
        responseBody.set("data: {\"request_id\":\"speech-1\",\"output\":{\"finish_reason\":\"stop\",\"audio\":{\"data\":\"AQI=\",\"url\":\"" + AUDIO_URL + "\"}}}\n\n");
        var accepted = new SpeechSubscriber();
        provider.streamSpeech(audited, speech()).subscribe(accepted);
        assertTrue(accepted.done.await(3, TimeUnit.SECONDS)); assertNull(accepted.error.get());
        assertEquals(Status.SUCCEEDED, ((SpeechEvent.Completed) accepted.events.getLast()).result().status());
        assertEquals(2, observed.get()); assertEquals(1, client.sent.size()); assertEquals(1, requests.get());
    }

    /** 官方完整成功及拒绝样例：图像宽高是计量字段，不能当成文本Token或异常对象。 */
    @Test void decodesOfficialImageExamplesAndPreservesTheirOriginalReceipts() {
        responseBody.set("""
                {"output":{"choices":[{"finish_reason":"stop","message":{"content":[{"image":"https://dashscope-result-sh.oss-cn-shanghai.aliyuncs.com/xxx.png?Expires=xxx"}],"role":"assistant"}}]},"usage":{"height":2048,"image_count":1,"width":2048},"request_id":"d0250a3d-b07f-49e1-bdc8-6793f4929xxx"}
                """);
        var request = new ImageRequest("qwen-image-2.0", "create image", List.of(), "512*512", 1, null, null, false, false);
        var result = provider.execute(connection(), request);
        assertEquals(Status.SUCCEEDED, result.status());
        assertEquals("d0250a3d-b07f-49e1-bdc8-6793f4929xxx", result.requestId());
        assertEquals(1, result.assets().size()); assertEquals(AssetKind.IMAGE, result.assets().getFirst().kind());
        assertEquals("image/png", result.assets().getFirst().mediaType());
        assertEquals(1L, result.usage().imageCount()); assertNull(result.usage().inputTokens()); assertNull(result.usage().outputTokens());
        assertEquals("512*512", received.get().path("parameters").path("size").textValue());
        assertEquals(1, received.get().path("parameters").path("n").intValue());
        assertFalse(received.get().path("parameters").path("prompt_extend").booleanValue());
        assertFalse(received.get().path("parameters").path("watermark").booleanValue());
        responseBody.set("""
                {"request_id":"a4d78a5f-655f-9639-8437-xxxxxx","code":"InvalidParameter","message":"n must be 1"}
                """);
        var rejected = provider.execute(connection(), request);
        assertEquals(Status.FAILED, rejected.status()); assertEquals("media_provider_rejected", rejected.reasonCode());
        assertEquals("a4d78a5f-655f-9639-8437-xxxxxx", rejected.requestId()); assertEquals(2, requests.get());
    }

    /** 实际百炼回执的脱敏样例：加速地址只作为资源来源返回，不能触发 SDK 下载。 */
    @Test void acceptsBailianAcceleratedImageReceiptWithoutDownloadingIt() {
        var sources = List.of(
                "https://dashscope-0484.oss-accelerate.aliyuncs.com/redacted/image.png?OSSAccessKeyId=synthetic&Expires=1999999999&Signature=synthetic%2Fsignature%3D",
                "https://dashscope-0484.oss-accelerate.aliyuncs.com:443/redacted/image.png?OSSAccessKeyId=synthetic&Expires=1999999999&Signature=synthetic%2Fsignature%3D");
        for (var source : sources) {
            responseBody.set("""
                    {"request_id":"recovered-image-receipt","output":{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":[{"image":"%s"}]}}]},"usage":{"image_count":1,"width":512,"height":512}}
                    """.formatted(source));
            var result = provider.execute(connection(), image());
            assertEquals(Status.SUCCEEDED, result.status()); assertEquals("recovered-image-receipt", result.requestId());
            assertEquals(1L, result.usage().imageCount()); assertEquals(1, result.assets().size());
            var asset = result.assets().getFirst();
            assertEquals(AssetKind.IMAGE, asset.kind()); assertEquals("image/png", asset.mediaType());
            assertEquals(URI.create(source), ((DomesticMediaProvider.UrlSource) asset.source()).url());
            assertFalse(result.toString().contains("synthetic%2Fsignature"));
        }
        assertEquals(2, requests.get()); assertEquals(2, client.sent.size());
        assertTrue(client.sent.stream().allMatch(request -> BEIJING.getHost().equals(request.uri().getHost())));
    }

    @Test void rejectsUntrustedAcceleratedOriginsAndNonDefaultResultPorts() {
        var sources = List.of(
                "http://dashscope-0484.oss-accelerate.aliyuncs.com/image.png",
                "ftp://dashscope-0484.oss-accelerate.aliyuncs.com/image.png",
                "https://foreign.oss-accelerate.aliyuncs.com/image.png",
                "https://dashscope-0484.oss-accelerate-overseas.aliyuncs.com/image.png",
                "https://nested.dashscope-0484.oss-accelerate.aliyuncs.com/image.png",
                "https://dashscope-0484.oss-accelerate.aliyuncs.com.foreign.example/image.png",
                "https://user@dashscope-0484.oss-accelerate.aliyuncs.com/image.png",
                "https://dashscope-0484.oss-accelerate.aliyuncs.com/image.png#fragment",
                "https://dashscope-0484.oss-accelerate.aliyuncs.com:444/image.png",
                "https://dashscope-0484.oss-accelerate.aliyuncs.com:80/image.png",
                "https://dashscope-.oss-accelerate.aliyuncs.com/image.png",
                "https://result.oss-cn-beijing.aliyuncs.com:8443/image.png",
                "http://result.oss-cn-beijing.aliyuncs.com:443/image.png");
        for (var source : sources) {
            responseBody.set("""
                    {"request_id":"invalid-origin-receipt","output":{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":[{"image":"%s"}]}}]}}
                    """.formatted(source));
            var result = provider.execute(connection(), image());
            assertEquals(Status.UNKNOWN, result.status()); assertEquals("media_response_content_invalid", result.reasonCode());
            assertEquals("invalid-origin-receipt", result.requestId()); assertTrue(result.assets().isEmpty());
        }
        assertEquals(sources.size(), requests.get()); assertEquals(sources.size(), client.sent.size());
    }

    @Test void transportFailureIsUnknownWithoutLeakingItsExceptionOrRetrying() {
        client.transportFailure = new IOException("synthetic private response, signed URL and credential");
        var result = provider.execute(connection(), image());
        assertEquals(Status.UNKNOWN, result.status()); assertEquals("media_transport_outcome_unknown", result.reasonCode());
        assertNull(result.requestId()); assertNull(result.taskId()); assertTrue(result.assets().isEmpty());
        assertFalse(result.toString().contains("private")); assertEquals(1, client.sent.size()); assertEquals(0, requests.get());
    }

    @Test void sendsImageEditingBlocksInOrderAndReturnsOnlyReportedUsage() {
        responseBody.set("""
                {"request_id":"request-image","output":{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":[{"image":"%s"}]}}]},"usage":{"image_count":1}}
                """.formatted(IMAGE_URL));
        var request = new ImageRequest("qwen-image-edit", "edit both references", List.of("data:image/png;base64,YQ==", "https://input.example/reference.png"), "1024*1024", 1, "bad", 7L, false, true);
        var result = provider.execute(connection(), request);
        assertEquals(Status.SUCCEEDED, result.status());
        assertEquals(IMAGE_URL, ((DomesticMediaProvider.UrlSource) result.assets().getFirst().source()).url().toString());
        assertEquals(AssetKind.IMAGE, result.assets().getFirst().kind());
        assertEquals(1L, result.usage().imageCount()); assertNull(result.usage().inputTokens());
        assertEquals("/api/v1/services/aigc/multimodal-generation/generation", path.get());
        assertEquals("Bearer synthetic-key", auth.get()); assertNull(asyncHeader.get());
        var content = received.get().path("input").path("messages").get(0).path("content");
        assertEquals("data:image/png;base64,YQ==", content.get(0).path("image").textValue());
        assertEquals("https://input.example/reference.png", content.get(1).path("image").textValue());
        assertEquals("edit both references", content.get(2).path("text").textValue());
        assertEquals(7L, received.get().path("parameters").path("seed").longValue());
        assertFalse(result.toString().contains("private")); assertFalse(connection().toString().contains("synthetic-key"));
        assertEquals("workspace-123.cn-beijing.maas.aliyuncs.com", client.sent.getFirst().uri().getHost());
    }

    @Test void createsWan3TaskOnceAndQueriesItsExactIdentityWithoutCreatingAgain() {
        responseBody.set("""
                {"request_id":"submit","output":{"task_id":"video-1","task_status":"PENDING"}}
                """);
        var submitted = provider.execute(connection(), new VideoRequest("wan3.0-video", "animate", List.of(new VideoInput(VideoInputKind.FIRST_FRAME, "https://input.example/first.png")), "720P", "16:9", 5, false, 7L, false, false));
        assertEquals(Status.PENDING, submitted.status()); assertEquals("video-1", submitted.taskId());
        assertNull(submitted.usage()); assertEquals("enable", asyncHeader.get());
        assertEquals("first_frame", received.get().path("input").path("media").get(0).path("type").textValue());
        assertEquals(5, received.get().path("parameters").path("duration").intValue());
        responseBody.set("""
                {"output":{"task_id":"video-1","task_status":"SUCCEEDED","video_url":"https://result.oss-cn-beijing.aliyuncs.com/video.mp4"},"usage":{"video_count":1,"duration":5.25}}
                """);
        var completed = provider.execute(connection(), new TaskQuery(TaskKind.VIDEO, submitted.taskId()));
        assertEquals(Status.SUCCEEDED, completed.status()); assertEquals("GET", method.get());
        assertEquals("/api/v1/tasks/video-1", path.get()); assertNull(asyncHeader.get());
        assertEquals(new BigDecimal("5.25"), completed.usage().durationSeconds());
        assertEquals(1L, completed.usage().videoCount()); assertEquals(2, requests.get());
        assertEquals(1, client.sent.stream().filter(request -> "POST".equals(request.method())).count());
    }

    @Test void rejectsWan3InvalidDurationsBeforeDispatch() {
        for (int duration : new int[] {1, 31}) {
            var rejected = provider.execute(connection(), video(List.of(), duration, null));
            assertEquals(Status.NOT_SUBMITTED, rejected.status());
            assertEquals("media_configuration_invalid", rejected.reasonCode());
        }
        var reference = new VideoInput(VideoInputKind.REFERENCE_VIDEO, "https://input.example/reference.mp4");
        assertEquals(Status.NOT_SUBMITTED, provider.execute(connection(), video(List.of(reference), 31, null)).status());
        assertEquals(0, requests.get()); assertTrue(client.sent.isEmpty());
    }

    @Test void rejectsWan3ConflictingMediaBeforeDispatch() {
        for (var frame : List.of(VideoInputKind.FIRST_FRAME, VideoInputKind.LAST_FRAME)) {
            for (var reference : List.of(VideoInputKind.REFERENCE_IMAGE, VideoInputKind.REFERENCE_VIDEO,
                    VideoInputKind.REFERENCE_AUDIO, VideoInputKind.FILE, VideoInputKind.LINK)) {
                var rejected = provider.execute(connection(), video(List.of(
                        new VideoInput(frame, "https://input.example/frame.png"),
                        new VideoInput(reference, "https://input.example/reference")), 5, true));
                assertEquals(Status.NOT_SUBMITTED, rejected.status());
                assertEquals("media_configuration_invalid", rejected.reasonCode());
            }
        }
        var incompatibleSources = List.of(new VideoInput(VideoInputKind.FILE, "https://input.example/document.pdf"),
                new VideoInput(VideoInputKind.LINK, "https://input.example/page"));
        assertEquals(Status.NOT_SUBMITTED, provider.execute(connection(), video(incompatibleSources, 5, true)).status());
        assertEquals(0, requests.get()); assertTrue(client.sent.isEmpty());
    }

    @Test void rejectsWan3MediaCountsAboveDocumentedLimitsBeforeDispatch() {
        var oversized = List.of(
                Collections.nCopies(2, new VideoInput(VideoInputKind.FIRST_FRAME, "https://input.example/frame.png")),
                Collections.nCopies(2, new VideoInput(VideoInputKind.LAST_FRAME, "https://input.example/frame.png")),
                Collections.nCopies(11, new VideoInput(VideoInputKind.REFERENCE_IMAGE, "https://input.example/reference.png")),
                Collections.nCopies(6, new VideoInput(VideoInputKind.REFERENCE_VIDEO, "https://input.example/reference.mp4")),
                Collections.nCopies(6, new VideoInput(VideoInputKind.REFERENCE_AUDIO, "https://input.example/reference.mp3")),
                Collections.nCopies(2, new VideoInput(VideoInputKind.FILE, "https://input.example/document.pdf")),
                Collections.nCopies(2, new VideoInput(VideoInputKind.LINK, "https://input.example/page")));
        for (var media : oversized) {
            var rejected = provider.execute(connection(), video(media, 5, true));
            assertEquals(Status.NOT_SUBMITTED, rejected.status());
            assertEquals("media_configuration_invalid", rejected.reasonCode());
        }
        assertEquals(0, requests.get()); assertTrue(client.sent.isEmpty());
    }

    @Test void rejectsWan3FileOrLinkWhenPromptExpansionIsDisabled() {
        for (var kind : List.of(VideoInputKind.FILE, VideoInputKind.LINK)) {
            var rejected = provider.execute(connection(), video(List.of(new VideoInput(kind, "https://input.example/material")), 5, false));
            assertEquals(Status.NOT_SUBMITTED, rejected.status());
            assertEquals("media_configuration_invalid", rejected.reasonCode());
        }
        assertEquals(0, requests.get()); assertTrue(client.sent.isEmpty());
    }

    @Test void preservesWan3TextVideoDurationBoundariesAndDefaults() {
        responseBody.set("{\"request_id\":\"submit\",\"output\":{\"task_id\":\"video-1\",\"task_status\":\"PENDING\"}}");
        for (Integer duration : new Integer[] {2, 30, -1, null}) {
            var submitted = provider.execute(connection(), video(List.of(), duration, null));
            assertEquals(Status.PENDING, submitted.status());
            assertFalse(received.get().path("input").has("media"));
            var parameters = received.get().path("parameters");
            if (duration == null) assertFalse(parameters.has("duration"));
            else assertEquals(duration.intValue(), parameters.path("duration").intValue());
            assertFalse(parameters.has("prompt_extend"));
            assertEquals("720P", parameters.path("resolution").textValue());
            assertEquals("16:9", parameters.path("ratio").textValue());
            assertFalse(parameters.path("audio").booleanValue()); assertFalse(parameters.path("watermark").booleanValue());
            assertEquals(7L, parameters.path("seed").longValue());
        }
        assertEquals(4, requests.get());
    }

    @Test void preservesWan3ReferenceVideoDurationException() {
        responseBody.set("{\"request_id\":\"submit\",\"output\":{\"task_id\":\"video-1\",\"task_status\":\"PENDING\"}}");
        var reference = new VideoInput(VideoInputKind.REFERENCE_VIDEO, "https://input.example/reference.mp4");
        for (Integer duration : new Integer[] {1, 29, -1, null}) {
            var submitted = provider.execute(connection(), video(List.of(reference), duration, false));
            assertEquals(Status.PENDING, submitted.status());
            var material = received.get().path("input").path("media").get(0);
            assertEquals("reference_video", material.path("type").textValue());
            assertEquals(reference.source(), material.path("url").textValue());
            var parameters = received.get().path("parameters");
            if (duration == null) assertFalse(parameters.has("duration"));
            else assertEquals(duration.intValue(), parameters.path("duration").intValue());
            assertFalse(parameters.path("prompt_extend").booleanValue());
        }
        assertEquals(4, requests.get());
    }

    @Test void preservesWan3FileAndLinkDefaultOrEnabledPromptExpansion() {
        responseBody.set("{\"request_id\":\"submit\",\"output\":{\"task_id\":\"video-1\",\"task_status\":\"PENDING\"}}");
        for (var kind : List.of(VideoInputKind.FILE, VideoInputKind.LINK)) {
            for (Boolean extension : new Boolean[] {null, true}) {
                var submitted = provider.execute(connection(), video(List.of(new VideoInput(kind, "https://input.example/material")), 5, extension));
                assertEquals(Status.PENDING, submitted.status());
                var parameters = received.get().path("parameters");
                if (extension == null) assertFalse(parameters.has("prompt_extend"));
                else assertTrue(parameters.path("prompt_extend").booleanValue());
                assertEquals(5, parameters.path("duration").intValue());
            }
        }
        assertEquals(4, requests.get());
    }

    @Test void mapsPartialTranscriptionFailureWithoutClaimingTheWholeTaskPassed() {
        responseBody.set("""
                {"output":{"task_id":"asr-1","task_status":"PENDING"}}
                """);
        var submitted = provider.execute(connection(), new TranscriptionRequest("paraformer-v2", List.of("https://input.example/audio.wav"), List.of(0), List.of("zh"), false, null));
        assertEquals(Status.PENDING, submitted.status());
        assertEquals("/api/v1/services/audio/asr/transcription", path.get());
        assertEquals("zh", received.get().path("parameters").path("language_hints").get(0).textValue());
        responseBody.set("""
                {"output":{"task_id":"asr-1","task_status":"SUCCEEDED","results":[{"subtask_status":"SUCCEEDED","transcription_url":"https://result.oss-cn-beijing.aliyuncs.com/transcript.json"},{"subtask_status":"FAILED","message":"private text"}],"task_metrics":{"TOTAL":2,"SUCCEEDED":1,"FAILED":1}},"usage":{"duration":9.1}}
                """);
        var result = provider.execute(connection(), new TaskQuery(TaskKind.TRANSCRIPTION, "asr-1"));
        assertEquals(Status.FAILED, result.status());
        assertEquals("media_transcription_partial_failure", result.reasonCode());
        assertEquals(1, result.assets().size()); assertEquals(AssetKind.TRANSCRIPT, result.assets().getFirst().kind());
        assertFalse(result.toString().contains("private text"));
    }

    @Test void returnsCompletedSpeechUrlAndReportedCharacters() {
        responseBody.set("""
                {"request_id":"speech-1","output":{"finish_reason":"stop","audio":{"url":"%s"}},"usage":{"characters":2}}
                """.formatted(AUDIO_URL));
        var result = provider.execute(connection(), speech());
        assertEquals(Status.SUCCEEDED, result.status()); assertEquals(2L, result.usage().characters());
        assertEquals("Chinese", received.get().path("input").path("language_type").textValue());
        assertEquals(AssetKind.AUDIO, result.assets().getFirst().kind()); assertNull(result.usage().inputTokens());
    }

    @Test void rejectsInternationalEndpointsAndPreDispatchCancellationWithoutSendingCredentials() {
        responseBody.set("{}");
        var observed = new AtomicInteger();
        var overseas = new Connection(URI.create("https://dashscope-intl.aliyuncs.com"), "synthetic-key", Duration.ofSeconds(3), () -> false, observed::incrementAndGet);
        assertEquals(Status.NOT_SUBMITTED, provider.execute(overseas, image()).status());
        var cancelled = new Connection(BEIJING, "synthetic-key", Duration.ofSeconds(3), () -> true, observed::incrementAndGet);
        assertEquals(Status.NOT_SUBMITTED, provider.execute(cancelled, image()).status());
        var cancellationChecks = new AtomicInteger();
        var cancelledAfterPreparation = new Connection(BEIJING, "synthetic-key", Duration.ofSeconds(3),
                () -> cancellationChecks.incrementAndGet() > 1, observed::incrementAndGet);
        assertEquals(Status.NOT_SUBMITTED, provider.execute(cancelledAfterPreparation, image()).status());
        assertEquals(0, observed.get()); assertTrue(client.sent.isEmpty()); assertEquals(0, requests.get());
    }

    @Test void preservesUnknownForMalformedSuccessAndTaskIdentityMismatchWithoutRetry() {
        responseBody.set("{\"request_id\":\"untrusted-id\",\"output\":");
        var malformed = provider.execute(connection(), image());
        assertEquals(Status.UNKNOWN, malformed.status()); assertEquals("media_response_json_invalid", malformed.reasonCode());
        assertNull(malformed.requestId());
        assertEquals(1, requests.get());
        responseBody.set("{\"request_id\":\"unparsed-usage\",\"usage\":{\"input_tokens\":{\"unexpected\":1}}}");
        var wrongType = provider.execute(connection(), image());
        assertEquals(Status.UNKNOWN, wrongType.status()); assertEquals("media_response_json_invalid", wrongType.reasonCode());
        assertNull(wrongType.requestId()); assertEquals(2, requests.get());
        responseBody.set("{\"output\":{\"task_id\":\"other\",\"task_status\":\"SUCCEEDED\"}}");
        var result = provider.execute(connection(), new TaskQuery(TaskKind.VIDEO, "original"));
        assertEquals(Status.UNKNOWN, result.status()); assertEquals("original", result.taskId());
        assertEquals("media_task_identity_invalid", result.reasonCode()); assertEquals(3, requests.get());
    }

    @Test void rejectsForeignResultOriginsAndUnknownTaskStatusWithoutDefaultingToSuccess() {
        responseBody.set("""
                {"request_id":"image-receipt","output":{"choices":[{"finish_reason":"stop","message":{"content":[{"image":"https://outside.example/image.png"}]}}]}}
                """);
        var foreign = provider.execute(connection(), image());
        assertEquals(Status.UNKNOWN, foreign.status()); assertEquals("media_response_content_invalid", foreign.reasonCode());
        assertEquals("image-receipt", foreign.requestId()); assertTrue(foreign.assets().isEmpty());
        responseBody.set("""
                {"output":{"choices":[{"finish_reason":"stop","message":{"content":[{"image":"https://result.oss-cn-hongkong.aliyuncs.com/image.png"}]}}]}}
                """);
        assertEquals(Status.UNKNOWN, provider.execute(connection(), image()).status());
        responseBody.set("""
                {"request_id":"query-receipt","output":{"task_id":"video-1","task_status":"SUCCEEDED","video_url":"https://result.oss-cn-beijing.aliyuncs.com/bad image.mp4"}}
                """);
        var invalidUri = provider.execute(connection(), new TaskQuery(TaskKind.VIDEO, "video-1"));
        assertEquals(Status.UNKNOWN, invalidUri.status()); assertEquals("media_response_content_invalid", invalidUri.reasonCode());
        assertEquals("query-receipt", invalidUri.requestId()); assertEquals("video-1", invalidUri.taskId());
        assertTrue(invalidUri.assets().isEmpty());
        responseBody.set("{\"output\":{\"task_id\":\"video-1\",\"task_status\":\"NEW_VENDOR_STATE\"}}");
        assertEquals(Status.UNKNOWN, provider.execute(connection(), new TaskQuery(TaskKind.VIDEO, "video-1")).status());
        assertEquals(4, requests.get());
    }

    @Test void distinguishesProviderRejectionFromServerOutcomeUnknownAndNeverRetries() {
        responseBody.set("{\"request_id\":\"original-http-error\",\"code\":\"InvalidParameter\",\"message\":\"private endpoint and key\"}");
        statusCode.set(400);
        var rejected = provider.execute(connection(), image());
        assertEquals(Status.FAILED, rejected.status()); assertFalse(rejected.toString().contains("private"));
        assertEquals("original-http-error", rejected.requestId());
        statusCode.set(500);
        var unknown = provider.execute(connection(), new TaskQuery(TaskKind.VIDEO, "original-task"));
        assertEquals(Status.UNKNOWN, unknown.status()); assertEquals("original-http-error", unknown.requestId());
        assertEquals("original-task", unknown.taskId());
        assertEquals(2, requests.get());
    }

    @Test void retainsHttpClassificationWhenErrorBodyIsMalformedOrOversized() {
        responseBody.set("{\"request_id\":\"unparsed-receipt\","); statusCode.set(401);
        var unauthorized = provider.execute(connection(), image());
        assertEquals(Status.FAILED, unauthorized.status()); assertEquals("media_authentication_rejected", unauthorized.reasonCode());
        assertNull(unauthorized.requestId()); assertTrue(unauthorized.assets().isEmpty());
        responseBody.set("{\"message\":\"" + "x".repeat(32_000) + "\"}"); statusCode.set(503);
        var unavailable = provider.execute(connection(), image());
        assertEquals(Status.UNKNOWN, unavailable.status()); assertEquals("media_provider_http_failure", unavailable.reasonCode());
        assertNull(unavailable.requestId()); assertTrue(unavailable.assets().isEmpty()); assertEquals(2, requests.get());
    }

    @Test void errorBodyReadHonorsDeadlineWithoutLosingConfirmedAuthenticationRejection() throws Exception {
        statusCode.set(401); responseBody.set("{}"); holdBody.set(true);
        var result = CompletableFuture.supplyAsync(() -> provider.execute(new Connection(BEIJING, "synthetic-key", Duration.ofMillis(500), () -> false), image()));
        assertTrue(bodyStarted.await(2, TimeUnit.SECONDS));
        var rejected = result.get(2, TimeUnit.SECONDS);
        assertEquals(Status.FAILED, rejected.status()); assertEquals("media_authentication_rejected", rejected.reasonCode());
        assertNull(rejected.requestId()); assertTrue(rejected.assets().isEmpty()); assertEquals(1, requests.get());
    }

    @Test void streamingAuthenticationRejectionPreservesItsReceiptWithoutPublishingAudio() throws Exception {
        statusCode.set(401); responseBody.set("{\"request_id\":\"speech-http-receipt\",\"code\":\"InvalidApiKey\"}");
        var subscriber = new SpeechSubscriber();
        provider.streamSpeech(connection(), speech()).subscribe(subscriber);
        assertTrue(subscriber.done.await(3, TimeUnit.SECONDS)); assertNull(subscriber.error.get());
        assertEquals(1, subscriber.events.size());
        var rejected = ((SpeechEvent.Completed) subscriber.events.getFirst()).result();
        assertEquals(Status.FAILED, rejected.status()); assertEquals("media_authentication_rejected", rejected.reasonCode());
        assertEquals("speech-http-receipt", rejected.requestId()); assertTrue(rejected.assets().isEmpty()); assertEquals(1, requests.get());
    }

    @Test void cancelsBodyReadWithinTheSameCallDeadlineWithoutResubmission() throws Exception {
        responseBody.set("{}"); holdBody.set(true);
        var result = CompletableFuture.supplyAsync(() -> provider.execute(new Connection(BEIJING, "synthetic-key", Duration.ofMillis(500), () -> false), image()));
        assertTrue(bodyStarted.await(2, TimeUnit.SECONDS));
        var unknown = result.get(2, TimeUnit.SECONDS);
        assertEquals(Status.UNKNOWN, unknown.status()); assertEquals("media_response_read_unknown", unknown.reasonCode());
        assertNull(unknown.requestId()); assertEquals(1, requests.get());
    }

    @Test void streamsOneWavAcrossChunksAndRequiresAnExplicitFinalStop() throws Exception {
        // 原百炼回执的流式WAV头；仅替换音频正文，连续片段不能各自补头或剥头。
        String headerHex = "52494646bfffff7f57415645666d74201000000001000100c05d000080bb000002001000646174619bffff7f";
        byte[] firstBytes = HexFormat.of().parseHex(headerHex + "01020304");
        byte[] secondBytes = {5, 6, 7, 8};
        contentType.set("text/event-stream");
        responseBody.set("""
                data: {"request_id":"stream-1","output":{"finish_reason":"null","audio":{"data":"%s"}}}

                data: {"request_id":"stream-1","output":{"finish_reason":"null","audio":{"data":"%s"}}}

                data: {"request_id":"stream-1","output":{"finish_reason":"stop","audio":{"url":"%s","data":""}},"usage":{"characters":2}}

                """.formatted(Base64.getEncoder().encodeToString(firstBytes), Base64.getEncoder().encodeToString(secondBytes), AUDIO_URL));
        var subscriber = new SpeechSubscriber();
        provider.streamSpeech(connection(), speech()).subscribe(subscriber);
        assertTrue(subscriber.done.await(3, TimeUnit.SECONDS));
        assertNull(subscriber.error.get()); assertEquals(3, subscriber.events.size());
        var first = (SpeechEvent.AudioChunk) subscriber.events.getFirst();
        var second = (SpeechEvent.AudioChunk) subscriber.events.get(1);
        assertArrayEquals(firstBytes, first.bytes()); assertArrayEquals(secondBytes, second.bytes());
        for (var delta : List.of(first, second)) {
            assertEquals("wav", delta.encoding()); assertEquals(24000, delta.sampleRateHz()); assertEquals(1, delta.channels());
        }
        var combined = new ByteArrayOutputStream();
        combined.writeBytes(first.bytes()); combined.writeBytes(second.bytes());
        assertArrayEquals(HexFormat.of().parseHex(headerHex + "0102030405060708"), combined.toByteArray());
        assertEquals(Status.SUCCEEDED, ((SpeechEvent.Completed) subscriber.events.getLast()).result().status());
        assertEquals("enable", streamHeader.get()); assertEquals(1, requests.get());
    }

    @Test void streamEofAfterAudioRemainsUnknownAndPreservesDeltas() throws Exception {
        contentType.set("text/event-stream");
        responseBody.set("data: {\"output\":{\"audio\":{\"data\":\"AQIDBA==\"}}}\n\n");
        var subscriber = new SpeechSubscriber();
        provider.streamSpeech(connection(), speech()).subscribe(subscriber);
        assertTrue(subscriber.done.await(3, TimeUnit.SECONDS));
        assertEquals(2, subscriber.events.size()); assertInstanceOf(SpeechEvent.AudioChunk.class, subscriber.events.getFirst());
        assertEquals(Status.UNKNOWN, ((SpeechEvent.Completed) subscriber.events.getLast()).result().status());
    }

    @Test void responseSizeLimitStopsTheOriginalCallWithoutRetry() {
        responseBody.set("{\"padding\":\"" + "x".repeat(1000) + "\"}");
        var small = new BailianMediaProvider(client, Clock.systemUTC(), 64);
        assertEquals(Status.UNKNOWN, small.execute(connection(), image()).status());
        assertEquals(1, requests.get());
    }

    @Test void subscriberCancellationAfterAudioDoesNotEmitAFalseCompletion() throws Exception {
        contentType.set("text/event-stream");
        responseBody.set("data: {\"output\":{\"audio\":{\"data\":\"AQIDBA==\"}}}\n\n"
                + "data: {\"output\":{\"finish_reason\":\"stop\",\"audio\":{\"url\":\"" + AUDIO_URL + "\"}}}\n\n");
        var receivedAudio = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<SpeechEvent>();
        provider.streamSpeech(connection(), speech()).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(Long.MAX_VALUE); }
            @Override public void onNext(SpeechEvent event) {
                events.add(event);
                if (event instanceof SpeechEvent.AudioChunk) { subscription.cancel(); receivedAudio.countDown(); }
            }
            @Override public void onError(Throwable failure) { fail(failure); }
            @Override public void onComplete() { fail("Cancelled subscription must not report completion"); }
        });
        assertTrue(receivedAudio.await(3, TimeUnit.SECONDS));
        assertEquals(1, events.size()); assertInstanceOf(SpeechEvent.AudioChunk.class, events.getFirst());
        assertEquals(1, requests.get());
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

    /** 只将网络传输转到本地夹具，保留生产请求的原始 URI 与认证头供核验。 */
    private static final class RelayClient extends HttpClient {
        final HttpClient delegate = HttpClient.newBuilder().followRedirects(Redirect.NEVER).build();
        final URI target;
        final List<HttpRequest> sent = new CopyOnWriteArrayList<>();
        volatile IOException transportFailure;
        RelayClient(URI target) { this.target = target; }
        private HttpRequest relay(HttpRequest original) {
            sent.add(original);
            var builder = HttpRequest.newBuilder(target.resolve(original.uri().getRawPath()));
            original.timeout().ifPresent(builder::timeout);
            original.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            return builder.method(original.method(), original.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())).build();
        }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException { return delegate.send(relay(request), handler); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            if (transportFailure != null) { sent.add(request); return CompletableFuture.failedFuture(transportFailure); }
            return delegate.sendAsync(relay(request), handler);
        }
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
