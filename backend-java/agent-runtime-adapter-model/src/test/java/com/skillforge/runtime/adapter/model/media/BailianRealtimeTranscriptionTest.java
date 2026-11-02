package com.skillforge.runtime.adapter.model.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.media.BailianRealtimeTranscription.RealtimeRequest;
import com.skillforge.runtime.adapter.model.media.BailianRealtimeTranscription.TranscriptEvent;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Connection;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Status;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BailianRealtimeTranscriptionTest {
    private ServerSocket server;
    private volatile Socket socket;
    private ExecutorService executor;
    private RelayClient client;
    private BailianRealtimeTranscription transcription;
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger finishCommands = new AtomicInteger();
    private final AtomicReference<JsonNode> runCommand = new AtomicReference<>();
    private final AtomicReference<byte[]> audio = new AtomicReference<>();
    private final AtomicReference<String> handshake = new AtomicReference<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final AtomicBoolean wrongIdentity = new AtomicBoolean();
    private final AtomicBoolean omitFinished = new AtomicBoolean();
    private final CountDownLatch audioReceived = new CountDownLatch(1);
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach void start() throws Exception {
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        executor = Executors.newSingleThreadExecutor(); executor.submit(this::serve);
        client = new RelayClient(URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/api-ws/v1/inference"));
        transcription = new BailianRealtimeTranscription(client, Clock.systemUTC(), 16_384);
    }

    @AfterEach void stop() throws Exception {
        if (socket != null) socket.close();
        if (server != null) server.close();
        if (executor != null) { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        if (client != null) { client.shutdownNow(); assertTrue(client.awaitTermination(Duration.ofSeconds(5))); }
        assertNull(serverFailure.get(), () -> "WebSocket fixture failure: " + serverFailure.get());
    }

    private static Connection connection() {
        return new Connection(URI.create("https://workspace.cn-beijing.maas.aliyuncs.com"), "synthetic-key", Duration.ofSeconds(5), () -> false);
    }
    private static RealtimeRequest request() { return new RealtimeRequest(UUID.randomUUID().toString(), "paraformer-realtime-v2", 16000, List.of("zh")); }

    @Test void sendsPcmAfterProviderStartedAndOnlyFinishesFromOriginalTaskReceipt() throws Exception {
        var events = new CopyOnWriteArrayList<TranscriptEvent>();
        var request = request();
        try (var session = transcription.open(connection(), request, events::add)) {
            session.ready().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals("wss", client.originalEndpoint.get().getScheme());
            assertEquals("workspace.cn-beijing.maas.aliyuncs.com", client.originalEndpoint.get().getHost());
            assertTrue(handshake.get().contains("Authorization: Bearer synthetic-key"));
            assertEquals("pcm", runCommand.get().path("payload").path("parameters").path("format").textValue());
            assertEquals(16000, runCommand.get().path("payload").path("parameters").path("sample_rate").intValue());
            assertTrue(runCommand.get().path("payload").path("input").isObject());
            session.sendAudio(new byte[]{1, 2, 3, 4}).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertTrue(audioReceived.await(3, TimeUnit.SECONDS));
            var outcome = session.finish().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(Status.SUCCEEDED, outcome.result().status());
            assertEquals(request.taskId(), outcome.result().taskId()); assertEquals("你好。", outcome.text());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, audio.get());
            assertEquals(1, outcome.finalSentences().size()); assertEquals(1, finishCommands.get());
            assertEquals(outcome, session.finish().toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertEquals(1, finishCommands.get()); assertEquals(1, connections.get());
            assertTrue(events.stream().anyMatch(event -> event instanceof TranscriptEvent.Segment segment && !segment.sentence().finalSegment()));
            assertFalse(outcome.toString().contains("你好"));
        }
    }

    @Test void taskIdentityMismatchEndsUnknownWithoutOpeningAnotherConnection() throws Exception {
        wrongIdentity.set(true);
        try (var session = transcription.open(connection(), request(), ignored -> {})) {
            var outcome = session.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(Status.UNKNOWN, outcome.result().status());
            assertEquals("media_task_identity_invalid", outcome.result().reasonCode());
            assertEquals(1, connections.get()); assertEquals(0, finishCommands.get());
        }
    }

    @Test void cleanSocketCloseWithoutTaskFinishedPreservesKnownTextAndUnknownOutcome() throws Exception {
        omitFinished.set(true);
        try (var session = transcription.open(connection(), request(), ignored -> {})) {
            session.ready().toCompletableFuture().get(3, TimeUnit.SECONDS);
            session.sendAudio(new byte[]{0, 0}).toCompletableFuture().get(3, TimeUnit.SECONDS);
            var outcome = session.finish().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(Status.UNKNOWN, outcome.result().status()); assertEquals("你好。", outcome.text());
            assertEquals(1, connections.get()); assertEquals(1, finishCommands.get());
        }
    }

    @Test void cancellationKeepsOriginalIdentityAndDoesNotClaimCloudTaskCancellation() throws Exception {
        var request = request();
        var session = transcription.open(connection(), request, ignored -> {});
        session.ready().toCompletableFuture().get(3, TimeUnit.SECONDS);
        session.cancel();
        var outcome = session.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(Status.UNKNOWN, outcome.result().status()); assertEquals(request.taskId(), outcome.result().taskId());
        assertEquals(0, finishCommands.get()); assertEquals(1, connections.get());
    }

    private void serve() {
        try {
            socket = server.accept(); connections.incrementAndGet(); socket.setSoTimeout(5000);
            var input = socket.getInputStream(); var output = socket.getOutputStream();
            String headers = readHeaders(input); handshake.set(headers);
            String key = headers.lines().filter(line -> line.toLowerCase().startsWith("sec-websocket-key:"))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim()).findFirst().orElseThrow();
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
            output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)); output.flush();
            Frame run = readFrame(input); runCommand.set(JSON.readTree(run.body()));
            String id = runCommand.get().path("header").path("task_id").textValue();
            sendText(output, event(wrongIdentity.get() ? UUID.randomUUID().toString() : id, "task-started", "{}"));
            while (true) {
                var next = readFrame(input);
                if (next.opcode() == 2) {
                    audio.set(next.body()); audioReceived.countDown();
                    sendText(output, event(id, "result-generated", "{\"output\":{\"sentence\":{\"text\":\"你好\",\"begin_time\":0,\"sentence_end\":false,\"heartbeat\":false}}}"));
                } else if (next.opcode() == 1) {
                    var finish = JSON.readTree(next.body());
                    if (!"finish-task".equals(finish.path("header").path("action").textValue()) || !id.equals(finish.path("header").path("task_id").textValue()))
                        throw new IOException("Unexpected finish identity");
                    finishCommands.incrementAndGet();
                    sendText(output, event(id, "result-generated", "{\"output\":{\"sentence\":{\"text\":\"你好。\",\"begin_time\":0,\"end_time\":900,\"sentence_end\":true,\"heartbeat\":false}},\"usage\":{\"duration\":1}}"));
                    if (!omitFinished.get()) sendText(output, event(id, "task-finished", "{}"));
                    else sendFrame(output, 8, new byte[]{3, (byte) 232});
                    return;
                } else if (next.opcode() == 8) return;
            }
        } catch (java.net.SocketException | EOFException expectedShutdown) {
            // 客户端取消会立即关闭 socket，夹具保留已收到的任务与音频事实。
        } catch (Throwable failure) { serverFailure.set(failure); }
    }

    private static String event(String id, String name, String payload) {
        return "{\"header\":{\"task_id\":\"" + id + "\",\"event\":\"" + name + "\"},\"payload\":" + payload + "}";
    }
    private static String readHeaders(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream(); int next;
        while ((next = input.read()) >= 0) {
            bytes.write(next);
            if (bytes.size() > 8192) throw new IOException("Fixture handshake too large");
            if (bytes.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) return bytes.toString(StandardCharsets.ISO_8859_1);
        }
        throw new EOFException();
    }
    private record Frame(int opcode, byte[] body) { }
    private static Frame readFrame(InputStream input) throws IOException {
        int first = input.read(), second = input.read(); if (first < 0 || second < 0) throw new EOFException();
        long length = second & 127;
        if (length == 126) length = (input.read() << 8) | input.read();
        else if (length == 127) { length = 0; for (int index = 0; index < 8; index++) length = (length << 8) | input.read(); }
        if (length < 0 || length > 32768) throw new IOException("Fixture frame too large");
        byte[] mask = (second & 128) != 0 ? input.readNBytes(4) : null;
        byte[] body = input.readNBytes((int) length); if (body.length != length) throw new EOFException();
        if (mask != null) for (int index = 0; index < body.length; index++) body[index] ^= mask[index % 4];
        return new Frame(first & 15, body);
    }
    private static void sendText(OutputStream output, String text) throws IOException { sendFrame(output, 1, text.getBytes(StandardCharsets.UTF_8)); }
    private static void sendFrame(OutputStream output, int opcode, byte[] body) throws IOException {
        output.write(128 | opcode);
        if (body.length < 126) output.write(body.length);
        else { output.write(126); output.write(body.length >>> 8); output.write(body.length & 255); }
        output.write(body); output.flush();
    }

    private static final class RelayClient extends HttpClient {
        final HttpClient delegate = HttpClient.newBuilder().followRedirects(Redirect.NEVER).build();
        final URI target;
        final AtomicReference<URI> originalEndpoint = new AtomicReference<>();
        RelayClient(URI target) { this.target = target; }
        @Override public WebSocket.Builder newWebSocketBuilder() {
            var builder = delegate.newWebSocketBuilder();
            return new WebSocket.Builder() {
                @Override public WebSocket.Builder header(String name, String value) { builder.header(name, value); return this; }
                @Override public WebSocket.Builder connectTimeout(Duration timeout) { builder.connectTimeout(timeout); return this; }
                @Override public WebSocket.Builder subprotocols(String mostPreferred, String... lessPreferred) { builder.subprotocols(mostPreferred, lessPreferred); return this; }
                @Override public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) { originalEndpoint.set(uri); return builder.buildAsync(target, listener); }
            };
        }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException { return delegate.send(request, handler); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { return delegate.sendAsync(request, handler); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { return delegate.sendAsync(request, handler, push); }
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
        @Override public boolean awaitTermination(Duration timeout) throws InterruptedException { return delegate.awaitTermination(timeout); }
    }
}
