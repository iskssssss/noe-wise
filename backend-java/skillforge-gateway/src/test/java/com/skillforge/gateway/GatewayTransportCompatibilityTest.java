package com.skillforge.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** 使用真实 loopback HTTP、SSE 和 WebSocket 核对网关依赖调整的可见行为。 */
@SpringBootTest(classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewayTransportCompatibilityTest {
    private static final byte[] BINARY = new byte[] {0, 1, 13, 10, (byte) 0xff, (byte) 0xc3, (byte) 0xa9};
    private static final DisposableServer UPSTREAM = HttpServer.create().host("127.0.0.1").port(0)
            .route(routes -> routes
                    .get("/api/v1/echo", (request, response) -> response
                            .header("Content-Type", "application/json")
                            .header("X-Upstream", "fixture")
                            .sendString(Mono.just("{\"data\":\"fixture\"}")))
                    .get("/api/v1/events", (request, response) -> response
                            .header("Content-Type", "text/event-stream")
                            .sendString(Mono.just("event: result\ndata: fixture\n\n")))
                    .get("/health/ready", (request, response) -> response.sendString(Mono.just("ready")))
                    .post("/api/v1/multipart", (request, response) -> response
                            .header("Content-Type", request.requestHeaders().get("Content-Type"))
                            .header("X-Upstream-Trace", request.requestHeaders().get("X-Trace-Id"))
                            .send(request.receive().retain()))
                    .get("/api/v1/binary", (request, response) -> response
                            .header("Content-Type", "application/octet-stream")
                            .header("Content-Disposition", "attachment; filename=\"bytes.bin\"")
                            .header("X-Content-Type-Options", "nosniff")
                            .sendByteArray(Mono.just(BINARY)))
                    .get("/api/v1/rejected", (request, response) -> response.status(409)
                            .header("Content-Type", "application/json")
                            .header("X-Upstream-Trace", request.requestHeaders().get("X-Trace-Id"))
                            .sendString(Mono.just("{\"code\":\"fixture_conflict\",\"data\":null,\"message\":\"冲突\\n第二行\"}")))
                    .ws("/api/v1/agent/ws", (incoming, outgoing) ->
                            outgoing.sendString(incoming.receive().asString().map(text -> "echo:" + text))))
            .bindNow(Duration.ofSeconds(10));

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        String address = "127.0.0.1:" + UPSTREAM.port();
        registry.add("PLATFORM_URI", () -> "http://" + address);
        registry.add("INSTITUTION_WS_URI", () -> "ws://" + address);
        registry.add("INSTITUTION_URI", () -> "http://" + address);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.sentinel.enabled", () -> "false");
    }

    @AfterAll
    static void closeUpstream() {
        UPSTREAM.disposeNow(Duration.ofSeconds(10));
    }

    @LocalServerPort private int port;

    @Test
    void forwardsRestSseAndHealthWithoutRewritingBodies() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            var rest = get(client, "/api/v1/echo");
            assertEquals(200, rest.statusCode());
            assertEquals("{\"data\":\"fixture\"}", rest.body());
            assertEquals("fixture", rest.headers().firstValue("X-Upstream").orElseThrow());
            assertEquals("review-gateway", rest.headers().firstValue("X-Trace-Id").orElseThrow());
            var sse = get(client, "/api/v1/events");
            assertEquals(200, sse.statusCode());
            assertTrue(sse.headers().firstValue("Content-Type").orElseThrow().startsWith("text/event-stream"));
            assertEquals("event: result\ndata: fixture\n\n", sse.body());
            assertEquals("ready", get(client, "/health/ready").body());
        }
    }

    @Test
    void forwardsActualWebSocketHandshakeAndMessages() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            var reply = new CompletableFuture<String>();
            var listener = new WebSocket.Listener() {
                private final StringBuilder text = new StringBuilder();
                @Override public void onOpen(WebSocket socket) { socket.request(1); }
                @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                    text.append(data);
                    if (last) reply.complete(text.toString());
                    socket.request(1);
                    return null;
                }
                @Override public void onError(WebSocket socket, Throwable error) { reply.completeExceptionally(error); }
            };
            WebSocket socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/agent/ws"), listener)
                    .get(10, TimeUnit.SECONDS);
            try {
                socket.sendText("fixture", true).get(10, TimeUnit.SECONDS);
                assertEquals("echo:fixture", reply.get(10, TimeUnit.SECONDS));
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
            } finally {
                socket.abort();
            }
        }
    }

    /** multipart正文包含中文、CRLF及不合法UTF8，网关不解析/重写字节。 */
    @Test
    void forwardsMultipartBoundaryHeadersAndEveryBodyByte() throws Exception {
        String boundary = "gateway-complex-fixture";
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"材料.csv\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[prefix.length + BINARY.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(BINARY, 0, body, prefix.length, BINARY.length);
        System.arraycopy(suffix, 0, body, prefix.length + BINARY.length, suffix.length);
        String type = "multipart/form-data; boundary=" + boundary;
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/multipart"))
                    .header("Content-Type", type).header("X-Trace-Id", "multipart-fixture")
                    .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals(type, response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("multipart-fixture", response.headers().firstValue("X-Upstream-Trace").orElseThrow());
            assertArrayEquals(body, response.body());
        }
    }

    /** 附件下载二进制保持精确内容及安全响应头，不受统一JSON信封影响。 */
    @Test
    void preservesBinaryAttachmentHashAndDownloadHeaders() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/binary"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertArrayEquals(BINARY, response.body());
            assertEquals("6f9ed642556754148767a030d23583b98165f507fae7a362e526cd2dfa109211",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body())));
            assertEquals("attachment; filename=\"bytes.bin\"", response.headers().firstValue("Content-Disposition").orElseThrow());
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        }
    }

    /** 下游稳定领域错误保留原状态和正文，双方追踪ID仍对应同次请求。 */
    @Test
    void preservesDownstreamConflictEnvelopeAndTrace() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = get(client, "/api/v1/rejected");
            assertEquals(409, response.statusCode());
            assertEquals("{\"code\":\"fixture_conflict\",\"data\":null,\"message\":\"冲突\\n第二行\"}", response.body());
            assertEquals("review-gateway", response.headers().firstValue("X-Upstream-Trace").orElseThrow());
            assertEquals("review-gateway", response.headers().firstValue("X-Trace-Id").orElseThrow());
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Trace-Id", "review-gateway").timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
