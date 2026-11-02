package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 真实 Tomcat 核对下载失败转 JSON 的响应头；票据与存储受控隔离，不启动数据库或完整宿主。 */
class ArtifactDownloadErrorServletTest {
    /** 共享下载夹具的原文件长度，与错误 JSON 的正文长度无关。 */
    private static final long FILE_LENGTH = 4096;
    /** 仅用于读取实际 HTTP 响应，禁止接受 JSON 前后混入文件正文。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 三次真实 GET 覆盖 AT、DT 与共享下载错误边界，失败也释放端口和 Servlet 上下文。 */
    @Test void integrityFailureRemovesAttachmentHeadersAndPreservesOtherHeaderValues(@TempDir Path directory)
            throws Exception {
        var context = new AnnotationConfigWebApplicationContext();
        context.register(MvcConfiguration.class);
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getByName("127.0.0.1"));
        factory.setBaseDirectory(directory.toFile());
        WebServer server = null;
        try {
            server = factory.getWebServer(servlets -> {
                context.setServletContext(servlets);
                context.refresh();
                var dispatcher = servlets.addServlet("downloads", new DispatcherServlet(context));
                dispatcher.setLoadOnStartup(1);
                dispatcher.addMapping("/");
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getPort();
            try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10)).build()) {
                assertJsonFailure(get(client, base + "/api/v1/chat/artifact-content/detail?ticket=AT"));
                assertJsonFailure(get(client, base + "/api/v1/chat/deliverable-content/detail?ticket=DT"));
                var shared = get(client, base + "/shared-download");
                assertJsonFailure(shared);
                assertEquals(List.of("https://download.test"), shared.headers().allValues(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
                assertEquals(List.of("true"), shared.headers().allValues(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
                assertEquals(List.of("Origin", "Accept"), shared.headers().allValues(HttpHeaders.VARY));
                assertEquals(List.of("first=one; Path=/; HttpOnly", "second=two; Path=/; Secure"),
                        shared.headers().allValues(HttpHeaders.SET_COOKIE));
            }
        } finally {
            try { if (server != null) server.stop(); }
            finally { context.close(); }
        }
    }

    /** 在真实网络请求上设置有界响应等待，GET 不携带模拟 Servlet 状态。 */
    private static HttpResponse<byte[]> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /** 精确领域错误只允许完整 JSON；附件头必须缺失，旧文件长度不能残留。 */
    private static void assertJsonFailure(HttpResponse<byte[]> response) throws IOException {
        assertEquals(500, response.statusCode());
        var type = MediaType.parseMediaType(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow());
        assertEquals("application", type.getType());
        assertEquals("json", type.getSubtype());
        JsonNode body = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
        assertEquals("ARTIFACT_INTEGRITY_MISMATCH", body.path("code").textValue());
        assertEquals("文件完整性校验失败，无法下载", body.path("message").textValue());
        assertTrue(body.path("retryable").isBoolean());
        assertFalse(body.path("retryable").booleanValue());
        assertTrue(body.has("data"));
        assertTrue(body.path("data").isNull());
        assertTrue(response.headers().allValues(HttpHeaders.CONTENT_DISPOSITION).isEmpty());
        assertFalse(response.headers().allValues(HttpHeaders.CONTENT_LENGTH).contains(Long.toString(FILE_LENGTH)));
        assertTrue(response.body().length < 1024);
        assertEquals(List.of("no-store"), response.headers().allValues(HttpHeaders.CACHE_CONTROL));
        assertEquals(List.of("no-referrer"), response.headers().allValues("Referrer-Policy"));
        assertEquals(List.of("nosniff"), response.headers().allValues("X-Content-Type-Options"));
    }

    /** 与生产 storage 完整性拒绝保持同一稳定领域错误，夹具不模拟真实磁盘或数据库验证。 */
    private static ApiException integrityFailure() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ARTIFACT_INTEGRITY_MISMATCH", "文件完整性校验失败，无法下载", false);
    }

    /** 只装配生产下载 controller 与共享异常映射；授权和存储入口由本上下文独占 mock。 */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({ArtifactController.class, DeliverableController.class, GlobalExceptionHandler.class, SharedDownloadController.class})
    static class MvcConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }

        /** 文件读取在 controller 设置附件头后拒绝，不写字节。 */
        @Bean ArtifactStorage storage() throws IOException {
            var storage = mock(ArtifactStorage.class);
            doThrow(integrityFailure()).when(storage).file(any(ArtifactRow.class), eq("report.md"), any(OutputStream.class));
            return storage;
        }

        /** AT 票据已授权的固定文件，测试只核对后续真实 Servlet 错误响应。 */
        @Bean ArtifactAccess access() {
            var access = mock(ArtifactAccess.class);
            when(access.verify("AT", null)).thenReturn(new ArtifactAccess.Access(
                    new ArtifactAccess.Claim("7", "9", null, "artifact", "file", "report.md", Long.MAX_VALUE, "nonce"),
                    row()));
            return access;
        }

        /** DT 票据已授权的发布文件，复用生产成果下载 controller。 */
        @Bean DeliverableAccess tickets() {
            var access = mock(DeliverableAccess.class);
            var file = new DeliverableReader.FileGrant("revision", "file", "session", row(), "report.md",
                    "报告.md", "md", "representation", null, "report.md");
            when(access.verify("DT", null)).thenReturn(new DeliverableAccess.Granted(
                    new DeliverableAccess.Claim("deliverable.v1", "7", "9", null, "revision", "file",
                            "representation", "file", Long.MAX_VALUE, "nonce"), file));
            return access;
        }

        /** 非下载路由依赖仅满足生产构造器，不参与本回归。 */
        @Bean ArtifactVersionService versions() { return mock(ArtifactVersionService.class); }

        /** 发布列表依赖仅满足生产构造器，下载授权由独立 mock 票据固定。 */
        @Bean DeliverableReader reader() { return mock(DeliverableReader.class); }

        /** 固定内部行用于传入 mock 存储，不建立持久事实或真实文件。 */
        private static ArtifactRow row() {
            return new ArtifactRow("artifact", "7", "session", "run", "invocation", "file", "报告", FILE_LENGTH,
                    "0".repeat(64), "[]", "0".repeat(64), Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), "ready");
        }
    }

    /** 共享下载失败样本：未提交文件正文、旧长度与多值安全响应头共同进入生产异常映射。 */
    @RestController
    static class SharedDownloadController {
        /** 完整性拒绝前预置真实附件、缓存、安全与 CORS 头；缓冲正文也必须由 reset 清除。 */
        @GetMapping("/shared-download")
        public void download(HttpServletResponse response) throws IOException {
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report.bin");
            response.setContentLengthLong(FILE_LENGTH);
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://download.test");
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            response.addHeader(HttpHeaders.VARY, "Origin");
            response.addHeader(HttpHeaders.VARY, "Accept");
            response.addHeader(HttpHeaders.SET_COOKIE, "first=one; Path=/; HttpOnly");
            response.addHeader(HttpHeaders.SET_COOKIE, "second=two; Path=/; Secure");
            response.getOutputStream().write("buffered-download-prefix".getBytes(StandardCharsets.UTF_8));
            throw integrityFailure();
        }
    }
}
