package com.skillforge.core.capability.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.core.shared.sandbox.SandboxGuard;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;

/** 平台 HTTP 工具的沙箱、方法、截断与发送后未知结果覆盖。 */
class HttpRequestToolHandlerTest {

    private HttpServer server;
    private String baseUrl;
    private SandboxGuard sandboxGuard;
    private CountDownLatch hangRelease;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        SkillForgeProperties properties = new SkillForgeProperties();
        properties.setDebug(true);
        sandboxGuard = new SandboxGuard(properties);
    }

    @AfterEach
    void tearDown() {
        if (hangRelease != null) {
            hangRelease.countDown();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void getAppendsQueryAndReturnsBoundedBody() throws Exception {
        server.createContext("/records", exchange -> {
            byte[] body = "0123456789".getBytes(StandardCharsets.UTF_8);
            assertTrue(exchange.getRequestURI().getRawQuery().contains("kind=all"));
            assertTrue(exchange.getRequestURI().getRawQuery().contains("page=1"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Map<String, Object> result = handler(64).execute(Map.of(
                "method", "GET",
                "url", baseUrl + "/records?kind=all",
                "query", Map.of("page", 1)), PlatformToolTestContext.create());

        assertEquals(true, result.get("success"));
        assertEquals(200, result.get("status"));
        assertEquals("0123456789", result.get("body"));
        assertEquals(false, result.get("truncated"));
        assertTrue(result.get("content_type").toString().contains("application/json"));
    }

    @Test
    void postSendsJsonObjectBody() throws Exception {
        server.createContext("/echo", exchange -> {
            byte[] incoming = exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, incoming.length);
            exchange.getResponseBody().write(incoming);
            exchange.close();
        });

        Map<String, Object> result = handler(64).execute(Map.of(
                "method", "POST",
                "url", baseUrl + "/echo",
                "body", Map.of("kind", "all")), PlatformToolTestContext.create());

        assertEquals(true, result.get("success"));
        assertEquals(201, result.get("status"));
        assertTrue(result.get("body").toString().contains("\"kind\""));
    }

    @Test
    void truncatesOversizedResponse() throws Exception {
        server.createContext("/big", exchange -> {
            byte[] body = "0123456789".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Map<String, Object> result = handler(4).execute(Map.of(
                "method", "GET", "url", baseUrl + "/big"), PlatformToolTestContext.create());

        assertEquals(true, result.get("success"));
        assertEquals("0123", result.get("body"));
        assertEquals(true, result.get("truncated"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {404, 500})
    void nonSuccessfulHttpResponseRetainsKnownStatusAndBodyWithoutResending(int status) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String responseBody = "upstream-error-" + status;
        server.createContext("/failed", exchange -> {
            calls.incrementAndGet();
            assertEquals("POST", exchange.getRequestMethod());
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Map<String, Object> result = handler(64).execute(Map.of(
                "method", "POST", "url", baseUrl + "/failed", "body", Map.of("request", "one")),
                PlatformToolTestContext.create());

        assertEquals(false, result.get("success"));
        assertEquals(status, result.get("status"));
        assertEquals(responseBody, result.get("body"));
        assertEquals("text/plain; charset=utf-8", result.get("content_type"));
        assertEquals(false, result.get("truncated"));
        assertTrue(result.get("error").toString().contains(Integer.toString(status)));
        assertEquals(1, calls.get());
    }

    @Test
    void truncatedErrorBodyDoesNotTurnKnownFailureIntoSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/large-error", exchange -> {
            calls.incrementAndGet();
            byte[] body = "0123456789".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Map<String, Object> result = handler(4).execute(Map.of(
                "method", "GET", "url", baseUrl + "/large-error"), PlatformToolTestContext.create());

        assertEquals(false, result.get("success"));
        assertEquals(404, result.get("status"));
        assertEquals("0123", result.get("body"));
        assertEquals("text/plain", result.get("content_type"));
        assertEquals(true, result.get("truncated"));
        assertTrue(result.get("error").toString().contains("404"));
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsRedirectsWithoutFollowing() throws Exception {
        AtomicInteger targetCalls = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        Map<String, Object> result = handler(64).execute(Map.of(
                "method", "GET", "url", baseUrl + "/redirect"), PlatformToolTestContext.create());

        assertEquals(false, result.get("success"));
        assertEquals(302, result.get("status"));
        assertEquals(0, targetCalls.get());
        assertTrue(result.get("error").toString().contains("重定向"));
    }

    @Test
    void rejectsGetWithBodyHostOverrideAndUserInfo() throws Exception {
        HttpRequestToolHandler handler = handler(64);
        assertFalse((Boolean) handler.execute(Map.of(
                "method", "GET", "url", baseUrl + "/x", "body", Map.of("a", 1)),
                PlatformToolTestContext.create()).get("success"));
        assertFalse((Boolean) handler.execute(Map.of(
                "method", "POST", "url", baseUrl + "/x",
                "headers", Map.of("Host", "evil.example")), PlatformToolTestContext.create()).get("success"));
        assertFalse((Boolean) handler.execute(Map.of(
                "method", "GET", "url", "http://user:pass@127.0.0.1/x"),
                PlatformToolTestContext.create()).get("success"));
        assertFalse((Boolean) handler.execute(Map.of(
                "method", "PUT", "url", baseUrl + "/x"), PlatformToolTestContext.create()).get("success"));
    }

    @Test
    void sandboxRejectionDoesNotCallNetwork() throws Exception {
        SandboxGuard blocked = mock(SandboxGuard.class);
        String url = baseUrl + "/blocked";
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, "目标地址为内网/保留地址，已拦截"))
                .when(blocked).assertSafe(org.mockito.ArgumentMatchers.anyString());
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/blocked", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        Map<String, Object> result = new HttpRequestToolHandler(blocked, new ObjectMapper(),
                noRedirectClient(), fixedSettings(64, 2))
                .execute(Map.of("method", "GET", "url", url), PlatformToolTestContext.create());

        assertEquals(false, result.get("success"));
        assertEquals(0, calls.get());
        assertTrue(result.get("error").toString().contains("内网"));
    }

    @Test
    void privateAddressIsRejectedBySandbox() throws Exception {
        SkillForgeProperties properties = new SkillForgeProperties();
        properties.setDebug(false);
        HttpRequestToolHandler handler = new HttpRequestToolHandler(
                new SandboxGuard(properties), new ObjectMapper(), noRedirectClient(), fixedSettings(64, 2));

        Map<String, Object> result = handler.execute(Map.of(
                "method", "GET", "url", "https://192.168.1.1/secret"),
                PlatformToolTestContext.create());

        assertEquals(false, result.get("success"));
        assertTrue(result.get("error").toString().contains("内网"));
    }

    @Test
    @Timeout(5)
    void timeoutAfterDispatchIsUnknown() {
        hangRelease = new CountDownLatch(1);
        server.createContext("/hang", exchange -> {
            try {
                hangRelease.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    exchange.sendResponseHeaders(200, -1);
                } catch (IOException ignored) {
                    // 客户端超时后连接可能已关闭。
                }
                exchange.close();
            }
        });

        HttpRequestToolHandler handler = new HttpRequestToolHandler(
                sandboxGuard, new ObjectMapper(), noRedirectClient(), fixedSettings(64, 1));

        assertThrows(ExternalInvocationGuard.OutcomeUnknownException.class,
                () -> handler.execute(Map.of("method", "GET", "url", baseUrl + "/hang"),
                        PlatformToolTestContext.create()));
    }

    @Test
    @Timeout(8)
    void frozenHttpTimeoutCoversStalledBodyAfterHeaders() throws Exception {
        var bodyStarted = stalledBody(200);
        assertControlledTransferStops(bodyStarted, PlatformToolTestContext.create(), 1, null, false);
    }

    @Test
    @Timeout(8)
    void redirectBodyIsClosedWithoutWaitingOrFollowing() throws Exception {
        var targetCalls = new AtomicInteger();
        server.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        var bodyStarted = stalledBody(302);
        assertControlledTransferStops(bodyStarted, PlatformToolTestContext.create(), 1, null, true);
        assertEquals(0, targetCalls.get());
    }

    @Test
    @Timeout(8)
    void trustedDeadlineCoversStalledBodyBeforeLongerHttpTimeout() throws Exception {
        var bodyStarted = stalledBody(200);
        assertControlledTransferStops(bodyStarted, controlledContext(Instant.now().plusSeconds(1), () -> false),
                10, null, false);
    }

    @Test
    @Timeout(8)
    void trustedCancellationStopsStalledBody() throws Exception {
        var cancelled = new AtomicBoolean();
        var bodyStarted = stalledBody(200);
        assertControlledTransferStops(bodyStarted, controlledContext(Instant.now().plusSeconds(20), cancelled::get),
                10, () -> cancelled.set(true), false);
    }

    @Test
    void alreadyCancelledOrExpiredContextDoesNotSendHttpRequest() throws Exception {
        var http = mock(HttpClient.class);
        when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        var handler = new HttpRequestToolHandler(sandboxGuard, new ObjectMapper(), http, fixedSettings(64, 10));
        for (var context : List.of(controlledContext(Instant.now().plusSeconds(20), () -> true),
                controlledContext(Instant.now().minusSeconds(1), () -> false))) {
            assertThrows(ExternalInvocationGuard.OutcomeUnknownException.class, () -> handler.execute(
                    Map.of("method", "GET", "url", baseUrl + "/never-sent"), context));
        }
        verify(http, never()).send(org.mockito.ArgumentMatchers.any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
    }

    /** 首字节已发送后停发；只有测试清理放行，避免将头部超时误当正文覆盖。 */
    private CountDownLatch stalledBody(int status) {
        hangRelease = new CountDownLatch(1);
        var bodyStarted = new CountDownLatch(1);
        server.createContext("/stalled-body", exchange -> {
            try {
                if (status == 302) exchange.getResponseHeaders().add("Location", "/target");
                exchange.sendResponseHeaders(status, 0);
                exchange.getResponseBody().write('a');
                exchange.getResponseBody().flush();
                bodyStarted.countDown();
                hangRelease.await(6, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        return bodyStarted;
    }

    private void assertControlledTransferStops(CountDownLatch bodyStarted, PlatformToolExecutionContext context,
            long timeoutSeconds, Runnable afterHeaders, boolean redirect) throws Exception {
        try (var client = noRedirectClient()) {
            var observed = spy(client);
            var transferThread = new AtomicReference<Thread>();
            doAnswer(invocation -> {
                transferThread.set(Thread.currentThread());
                return invocation.callRealMethod();
            }).when(observed).send(org.mockito.ArgumentMatchers.any(HttpRequest.class),
                    org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
            var handler = new HttpRequestToolHandler(sandboxGuard, new ObjectMapper(), observed,
                    fixedSettings(64, timeoutSeconds));
            var task = new FutureTask<Map<String, Object>>(() -> handler.execute(
                    Map.of("method", "GET", "url", baseUrl + "/stalled-body"), context));
            Thread worker = Thread.startVirtualThread(task);
            try {
                assertTrue(bodyStarted.await(2, TimeUnit.SECONDS), "响应头与首字节必须已送达");
                if (afterHeaders != null) afterHeaders.run();
                if (redirect) {
                    var result = task.get(3, TimeUnit.SECONDS);
                    assertEquals(false, result.get("success"));
                    assertEquals(302, result.get("status"));
                    assertEquals("", result.get("body"));
                    assertEquals(false, result.get("truncated"));
                    assertTrue(result.get("error").toString().contains("重定向"));
                } else {
                    var failure = assertThrows(ExecutionException.class, () -> task.get(3, TimeUnit.SECONDS));
                    assertTrue(failure.getCause() instanceof ExternalInvocationGuard.OutcomeUnknownException,
                            "发送后截止/取消必须保留结果未知语义");
                }
                assertEquals(1, hangRelease.getCount(), "证明静止时服务端仍未放行正文");
                assertFalse(transferThread.get().isAlive(), "Handler 返回前拥有的传输 worker 必须退出");
            } finally {
                hangRelease.countDown();
                task.cancel(true);
                worker.join(2_000);
                assertFalse(worker.isAlive(), "HTTP 读取 worker 必须结束");
            }
        }
    }

    private static PlatformToolExecutionContext controlledContext(Instant deadline,
            com.skillforge.runtime.api.port.CancellationSignal cancellation) {
        var base = PlatformToolTestContext.create();
        return new PlatformToolExecutionContext(new com.skillforge.runtime.api.port.PortCallContext(
                base.run(), base.callContext().identity(),
                new com.skillforge.runtime.api.port.InvocationControl(deadline, cancellation)),
                base.invocationId(), base.attemptId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachInvocationUsesItsFrozenCallPolicy() throws Exception {
        RunSettingsService settings = mock(RunSettingsService.class);
        var first = new RunSettingsService.FrozenConfig(null, 0, Map.of("calls", 1L), null, com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited(), "profile");
        var second = new RunSettingsService.FrozenConfig(null, 0, Map.of("calls", 2L), null, com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited(), "profile");
        when(settings.captureTool("1", "run", "invocation-a")).thenReturn(first);
        when(settings.captureTool("1", "run", "invocation-b")).thenReturn(second);
        when(settings.read(first, "calls")).thenReturn(calls(1, 4, 2));
        when(settings.read(second, "calls")).thenReturn(calls(2, 8, 5));

        HttpClient http = mock(HttpClient.class);
        when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(response.body()).thenAnswer(ignored -> new ByteArrayInputStream(
                "0123456789".getBytes(StandardCharsets.UTF_8)));
        List<Duration> timeouts = new ArrayList<>();
        when(http.send(org.mockito.ArgumentMatchers.any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    timeouts.add(request.timeout().orElseThrow());
                    return response;
                });
        HttpRequestToolHandler handler = new HttpRequestToolHandler(
                sandboxGuard, new ObjectMapper(), http, settings);

        Map<String, Object> firstResult = handler.execute(
                Map.of("method", "GET", "url", baseUrl + "/policy"), context("invocation-a"));
        Map<String, Object> secondResult = handler.execute(
                Map.of("method", "GET", "url", baseUrl + "/policy"), context("invocation-b"));
        Map<String, Object> retryResult = handler.execute(
                Map.of("method", "GET", "url", baseUrl + "/policy"), context("invocation-a"));

        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(2)), timeouts);
        assertEquals("0123", firstResult.get("body"));
        assertEquals("01234567", secondResult.get("body"));
        assertEquals("0123", retryResult.get("body"));
        verify(settings, times(2)).captureTool("1", "run", "invocation-a");
        verify(settings).captureTool("1", "run", "invocation-b");
        verify(settings, times(2)).read(first, "calls");
        verify(settings).read(second, "calls");
    }

    private HttpRequestToolHandler handler(int maxBytes) {
        return new HttpRequestToolHandler(sandboxGuard, new ObjectMapper(), noRedirectClient(),
                fixedSettings(maxBytes, 2));
    }

    private static RunSettingsService fixedSettings(int maxBytes, long timeoutSeconds) {
        RunSettingsService settings = mock(RunSettingsService.class);
        var frozen = new RunSettingsService.FrozenConfig(null, 0, Map.of("calls", 1L), null, com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig.inherited(), "profile");
        when(settings.captureTool(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(frozen);
        when(settings.read(frozen, "calls")).thenReturn(calls(1, maxBytes, timeoutSeconds));
        return settings;
    }

    private static OperationalSettingsService.Snapshot calls(
            long revision, int maxBytes, long timeoutSeconds) {
        return new OperationalSettingsService.Snapshot(revision, Map.of(
                "http_tool_max_response_bytes", maxBytes,
                "http_tool_timeout_seconds", timeoutSeconds));
    }

    private static PlatformToolExecutionContext context(String invocationId) {
        PlatformToolExecutionContext base = PlatformToolTestContext.create();
        return new PlatformToolExecutionContext(base.callContext(), invocationId, base.attemptId());
    }

    private static HttpClient noRedirectClient() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2)).build();
    }
}
