package com.skillforge.institution.config;

import com.skillforge.core.shared.web.HttpBodyCachingProperties;

import com.skillforge.core.shared.web.ExternalAuthBodyCachingFilter;

import com.skillforge.core.shared.web.CachedBodyHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.channel.mapper.ChannelMapper;
import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.institution.auth.OpenApiAuthenticator;
import com.skillforge.institution.auth.ServicePrincipalAuthenticator;
import com.skillforge.institution.auth.ChannelTokenAuthenticator;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.contract.ChatAttachmentLimits;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import com.skillforge.institution.security.HmacSignatureVerifier;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.unit.DataSize;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

/** 真实 loopback Servlet HTTP 验证缓存边界；不启动数据库、Runtime 或外部供应商。 */
@Execution(ExecutionMode.SAME_THREAD)
class ExternalAuthBodyCachingHttpTest {
    private static final int LIMIT = 1024;
    private static final String PATH = "/api/v1/body-probe";
    private static final String ATTACHMENT = "/api/v1/open-platform/sessions/attachments";
    private static final String MEDIA = "/api/v1/open-platform/sessions/media";
    private static final AtomicInteger AUTHENTICATIONS = new AtomicInteger();
    private static final AtomicInteger ACCEPTED = new AtomicInteger();
    private static final AtomicLong OBSERVED_LENGTH = new AtomicLong();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static StaticWebApplicationContext context;
    private static WebServer server;
    private static HttpClient client;
    private static URI endpoint;

    @BeforeAll
    static void start() throws Exception {
        context = new StaticWebApplicationContext();
        context.getBeanFactory().registerSingleton("errors", new GlobalExceptionHandler(JSON));
        context.refresh();
        var errors = new ExceptionHandlerExceptionResolver();
        errors.setApplicationContext(context);
        errors.setMessageConverters(List.of(new MappingJackson2HttpMessageConverter(JSON)));
        errors.afterPropertiesSet();
        var channel = new ChannelDO();
        channel.setId(1L); channel.setTenantId(7L); channel.setChannelType("openapi");
        channel.setConfig(Map.of()); channel.setApiSecretEncrypted("fixture-encrypted");
        var channels = mock(ChannelMapper.class);
        when(channels.selectForAuthByApiKeyHash(anyString())).thenReturn(channel);
        var crypto = mock(CryptoService.class);
        when(crypto.decrypt("fixture-encrypted")).thenReturn("fixture-secret");
        var auth = new OpenApiAuthenticator(channels, crypto);
        var principal = new ServicePrincipalDO();
        principal.setTenantId(7L); principal.setPrincipalId("sp-fixture"); principal.setIntegrationId("crm-fixture");
        principal.setSigningSecretEncrypted("fixture-encrypted"); principal.setCredentialVersion(3L); principal.setScopes(List.of("artifacts.write"));
        var principals = mock(ServicePrincipalMapper.class);
        when(principals.selectForAuthByApiKeyHash(anyString())).thenReturn(principal);
        var external = new ExternalAuthInterceptor(mock(ChannelTokenAuthenticator.class), auth, new ServicePrincipalAuthenticator(principals, crypto));
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getByName("127.0.0.1"));
        try {
            server = factory.getWebServer(servlets -> {
                servlets.addFilter("trace", new TraceIdFilter(errors)).addMappingForUrlPatterns(
                        EnumSet.of(DispatcherType.REQUEST), false, "/*");
                servlets.addFilter("body", new ExternalAuthBodyCachingFilter(
                        new HttpBodyCachingProperties(DataSize.ofBytes(LIMIT), DataSize.ofMegabytes(50)))).addMappingForUrlPatterns(
                        EnumSet.of(DispatcherType.REQUEST), true, "/*");
                servlets.addServlet("probe", new HttpServlet() {
                    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException, jakarta.servlet.ServletException {
                        OBSERVED_LENGTH.set(request.getContentLengthLong());
                        boolean service = request.getRequestURI().startsWith("/api/v1/open-platform/");
                        if (service) {
                            AUTHENTICATIONS.incrementAndGet();
                            try { assertTrue(external.preHandle(request, response, null)); }
                            catch (RuntimeException failure) { throw failure; }
                            catch (Exception failure) { throw new jakarta.servlet.ServletException(failure); }
                            assertEquals("7", TenantContext.getTenantId());
                            assertEquals("crm-fixture", ServicePrincipalContext.get().integrationId());
                            assertEquals(3L, ServicePrincipalContext.get().credentialVersion());
                        }
                        boolean multipart = request.getContentType().startsWith("multipart/");
                        if (multipart) {
                            assertFalse(request instanceof CachedBodyHttpServletRequest);
                            response.setStatus(200);
                            response.getOutputStream().write(request.getInputStream().readAllBytes());
                            return;
                        }
                        var cached = (CachedBodyHttpServletRequest) request;
                        if (!service) {
                            String body = new String(cached.getCachedBody(), StandardCharsets.UTF_8);
                            AUTHENTICATIONS.incrementAndGet();
                            auth.authenticate(request.getHeader("X-Api-Key"), request.getHeader("X-Timestamp"),
                                    request.getHeader("X-Signature"), request, body);
                        }
                        byte[] first = cached.getInputStream().readAllBytes();
                        byte[] borrowed = cached.getCachedBody();
                        if (borrowed.length > 0) borrowed[0] ^= 1;
                        assertArrayEquals(first, cached.getInputStream().readAllBytes());
                        assertArrayEquals(first, cached.getCachedBody());
                        ACCEPTED.incrementAndGet();
                        response.setStatus(200);
                        response.getOutputStream().write(first);
                    }
                }).addMapping(PATH, "/api/v1/open-platform/*");
            });
            server.start();
            endpoint = URI.create("http://127.0.0.1:" + server.getPort() + PATH);
            client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10)).build();
        } catch (Exception | Error failure) {
            stop();
            throw failure;
        }
    }

    @AfterAll static void stop() {
        if (client != null) client.close();
        if (server != null) server.stop();
        if (context != null) context.close();
    }

    @BeforeEach void counters() {
        AUTHENTICATIONS.set(0); ACCEPTED.set(0); OBSERVED_LENGTH.set(Long.MIN_VALUE);
    }

    @Test void defaultBudgetIsIndependentAndInvalidSizesFailAtBinding() {
        assertEquals(8 * 1024 * 1024, new HttpBodyCachingProperties(null, DataSize.ofMegabytes(50)).maxCachedBodyBytes());
        assertThrows(IllegalArgumentException.class, () -> new HttpBodyCachingProperties(DataSize.ofBytes(0), DataSize.ofMegabytes(50)));
        assertThrows(IllegalArgumentException.class, () -> new HttpBodyCachingProperties(DataSize.ofBytes(Integer.MAX_VALUE), DataSize.ofMegabytes(50)));
        assertThrows(IllegalArgumentException.class, () -> new HttpBodyCachingProperties(null, null));
    }

    @Test void declaredLengthAcceptsLimitAndRejectsLimitPlusOneBeforeAuthentication() throws Exception {
        byte[] accepted = "a".repeat(LIMIT).getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(accepted, send(accepted, false, true, "application/json").body());
        assertEquals(1, ACCEPTED.get());
        counters();
        assertTooLarge(send("a".repeat(LIMIT + 1).getBytes(StandardCharsets.UTF_8), false, true, "application/json"));
    }

    @Test void unknownLengthChunkedHasTheSameActualByteLimit() throws Exception {
        byte[] accepted = "b".repeat(LIMIT).getBytes(StandardCharsets.UTF_8);
        var allowed = send(accepted, true, true, "application/json");
        assertEquals(200, allowed.statusCode());
        assertArrayEquals(accepted, allowed.body());
        assertEquals(-1, OBSERVED_LENGTH.get(), "真实未知 Content-Length 请求");
        counters();
        assertTooLarge(send("b".repeat(LIMIT + 1).getBytes(StandardCharsets.UTF_8), true, false, "application/json"));
    }

    @Test void oversizedDeclaredLengthRejectsHeadersWithoutWaitingForBody() throws Exception {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", endpoint.getPort()), 10_000);
            socket.setSoTimeout(10_000);
            // 只发送请求头。Tomcat 可提前发出 100；最终 413 必须在未发送正文时返回。
            String headers = "POST " + PATH + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + (LIMIT + 1)
                    + "\r\nExpect: 100-continue\r\nX-Trace-Id: cache-http-fixture\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var response = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            String finalStatus = null;
            for (int responses = 0; responses < 4; responses++) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "未发送正文时必须有界返回最终响应");
                socket.setSoTimeout((int) Math.max(1, Duration.ofNanos(remaining).toMillis()));
                String status = response.readLine();
                assertNotNull(status, "最终响应前连接关闭");
                assertTrue(status.matches("HTTP/1\\.1 [1-5][0-9]{2}( .*)?"), status);
                boolean headersComplete = false;
                for (int headersRead = 0; headersRead < 64; headersRead++) {
                    remaining = deadline - System.nanoTime();
                    assertTrue(remaining > 0, "响应头读取超过总时限");
                    socket.setSoTimeout((int) Math.max(1, Duration.ofNanos(remaining).toMillis()));
                    String header = response.readLine();
                    assertNotNull(header, "响应头未完成时连接关闭");
                    if (header.isEmpty()) {
                        headersComplete = true;
                        break;
                    }
                }
                assertTrue(headersComplete, "响应头数量超过有界预算");
                int code = Integer.parseInt(status.substring(9, 12));
                if (code < 200) {
                    assertNotEquals(101, code, "请求不得升级协议");
                    continue;
                }
                finalStatus = status;
                break;
            }
            assertNotNull(finalStatus, "临时响应后必须获得最终响应");
            assertTrue(finalStatus.startsWith("HTTP/1.1 413"), finalStatus);
            assertEquals(0, AUTHENTICATIONS.get());
            assertEquals(0, ACCEPTED.get());
        }
    }

    @Test void utf8ChineseIsCountedByBytesAndSignedBodyRemainsExactOnEveryRead() throws Exception {
        byte[] exact = ("中".repeat(341) + " ").getBytes(StandardCharsets.UTF_8);
        assertEquals(LIMIT, exact.length);
        var allowed = send(exact, true, true, "application/json");
        assertEquals(200, allowed.statusCode());
        assertArrayEquals(exact, allowed.body());
        counters();
        assertTooLarge(send(("中".repeat(341) + "  ").getBytes(StandardCharsets.UTF_8), true, true, "application/json"));
        byte[] formatted = "{\n  \"内容\": \"中文原文\", \"空白\": \" 保留 \"\n}\n".getBytes(StandardCharsets.UTF_8);
        var reread = send(formatted, false, true, "application/json");
        assertEquals(200, reread.statusCode());
        assertArrayEquals(formatted, reread.body());
    }

    @Test void oversizeWithoutCredentialsIs413AndAllowedUnauthenticatedBodyStill401() throws Exception {
        assertTooLarge(send("x".repeat(LIMIT + 1).getBytes(StandardCharsets.UTF_8), false, false, "application/json"));
        var small = send("{}".getBytes(StandardCharsets.UTF_8), false, false, "application/json");
        assertEquals(401, small.statusCode());
        assertEquals("UNAUTHORIZED", JSON.readTree(small.body()).path("code").asText());
        assertEquals(1, AUTHENTICATIONS.get());
        assertEquals(0, ACCEPTED.get());
    }

    @Test void multipartBypassesTheJsonCachingBudget() throws Exception {
        byte[] body = ("--fixture\r\nContent-Disposition: form-data; name=\"file\"; filename=\"demo.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n" + "m".repeat(LIMIT * 2) + "\r\n--fixture--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        var response = send(body, false, false, "multipart/form-data; boundary=fixture");
        assertEquals(200, response.statusCode());
        assertArrayEquals(body, response.body());
        assertEquals(0, AUTHENTICATIONS.get());
    }

    /** 真实二进制跨过过滤器与SP拦截器，非法UTF-8和NUL不会被重编码，业务读流仍保持全值。 */
    @Test void rawBinaryServicePrincipalUploadsPreserveBytesAndDatabaseIdentity() throws Exception {
        byte[] body = new byte[LIMIT + 1];
        for (int index = 0; index < body.length; index++) body[index] = (byte) index;
        for (String path : List.of(ATTACHMENT + "?sessionId=s1&uploadId=raw&filename=fixture.bin", MEDIA + "?sessionId=s1&uploadId=raw")) {
            var allowed = sendRaw(path, body, body, true, "application/octet-stream");
            assertEquals(200, allowed.statusCode(), new String(allowed.body(), StandardCharsets.UTF_8));
            assertArrayEquals(body, allowed.body());
        }
        assertEquals(2, ACCEPTED.get());
    }

    /** 改一个原始字节、签空正文或缺签名均拒绝；失败不会进入业务写入探针。 */
    @Test void rawBinaryTamperingEmptyBodyAndMissingSignatureAreRejected() throws Exception {
        byte[] body = {0, (byte) 0xff, (byte) 0xfe, 10};
        byte[] tampered = body.clone(); tampered[1] ^= 1;
        for (HttpResponse<byte[]> rejected : List.of(
                sendRaw(MEDIA, tampered, body, false, "application/octet-stream"),
                sendRaw(MEDIA, body, new byte[0], false, "application/octet-stream"),
                sendRaw(MEDIA, body, null, false, "application/octet-stream"))) {
            assertEquals(401, rejected.statusCode());
            assertEquals("UNAUTHORIZED", JSON.readTree(rejected.body()).path("code").asText());
        }
        assertEquals(0, ACCEPTED.get());
    }

    /** 提升的预算只给精确POST二进制入口，JSON/邻接路径仍使用普通缓存限制。 */
    @Test void binaryUploadBudgetDoesNotIncreaseJsonOrNeighbouringRoutes() throws Exception {
        byte[] body = new byte[LIMIT + 1];
        assertTooLarge(sendRaw(MEDIA, body, body, false, "application/json"));
        assertTooLarge(sendRaw(MEDIA + "/extra", body, body, false, "application/octet-stream"));
        assertTooLarge(sendRaw(ATTACHMENT + "/extra", body, body, true, "application/octet-stream"));
    }

    /** 文件头声明超过正式50MiB时，不读取正文也不进入认证。 */
    @Test void oversizedBinaryUploadHeadersRejectBeforeReadingBodyOrAuthenticating() throws Exception {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", endpoint.getPort()), 10_000); socket.setSoTimeout(10_000);
            String headers = "POST " + ATTACHMENT + " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/octet-stream\r\nContent-Length: "
                    + (ChatAttachmentLimits.MAX_FILE_BYTES + 1) + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertTrue(input.readLine().startsWith("HTTP/1.1 413"));
        }
        assertEquals(0, AUTHENTICATIONS.get()); assertEquals(0, ACCEPTED.get());
    }

    /** 原SP multipart不参与空正文摘要认证；普通native multipart测试继续保留。 */
    @Test void servicePrincipalMultipartNeverFallsBackToEmptyBodySignature() throws Exception {
        byte[] body = "--fixture\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nraw\r\n--fixture--\r\n".getBytes(StandardCharsets.UTF_8);
        var rejected = sendRaw(ATTACHMENT, body, new byte[0], false, "multipart/form-data; boundary=fixture");
        assertEquals(401, rejected.statusCode()); assertEquals(0, ACCEPTED.get());
    }

    private HttpResponse<byte[]> sendRaw(String path, byte[] body, byte[] signedBody, boolean chunked, String type) throws Exception {
        var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)) : HttpRequest.BodyPublishers.ofByteArray(body);
        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        var request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(10)).header("Content-Type", type)
                .header("X-Trace-Id", "cache-http-fixture").header("X-Api-Key", "fixture-sp-key").header("X-Timestamp", timestamp).POST(publisher);
        if (signedBody != null) request.header("X-Signature", HmacSignatureVerifier.sign("fixture-secret", HmacSignatureVerifier.canonicalBytes(timestamp, "POST", path, signedBody)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> send(byte[] body, boolean chunked, boolean signed, String contentType) throws Exception {
        var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                : HttpRequest.BodyPublishers.ofByteArray(body);
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Content-Type", contentType).header("X-Trace-Id", "cache-http-fixture").POST(publisher);
        if (signed) {
            String timestamp = Long.toString(System.currentTimeMillis() / 1000);
            request.header("X-Api-Key", "fixture-key").header("X-Timestamp", timestamp)
                    .header("X-Signature", HmacSignatureVerifier.sign("fixture-secret",
                            HmacSignatureVerifier.canonical(timestamp, "POST", PATH, new String(body, StandardCharsets.UTF_8))));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void assertTooLarge(HttpResponse<byte[]> response) throws Exception {
        assertEquals(413, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        var error = JSON.readTree(response.body());
        assertEquals("PAYLOAD_TOO_LARGE", error.path("code").asText());
        assertFalse(error.path("retryable").asBoolean());
        assertEquals("cache-http-fixture", error.path("trace_id").asText());
        assertEquals("cache-http-fixture", response.headers().firstValue("X-Trace-Id").orElseThrow());
        assertEquals(0, AUTHENTICATIONS.get());
        assertEquals(0, ACCEPTED.get());
    }
}
