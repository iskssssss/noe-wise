package com.skillforge.sandbox.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 聚焦认证、明确限流拒绝及校验失败；不接触真实凭据或远端用户文件。 */
class UpyunWorkspaceStoreTest {
    @TempDir
    Path root;

    /** 固定向量保护 SDK 的 Content-MD5 签名顺序，避免只验证内部自洽。 */
    @Test
    void signatureMatchesSdkFixedVector() {
        assertThat(UpyunWorkspaceStore.authorization("PUT", "/bucket/workspaces/w1/v1.zip",
                "Wed, 29 Oct 2014 02:26:58 GMT", "900150983cd24fb0d6963f7d28e17f72", "operator", "password"))
                .isEqualTo("UPYUN operator:hQx+wspKL5WTvCXNjG/ExSZAOuE=");
    }

    /** 上传遇到 429 后同摘要重试，再核验服务器文件信息；失败不会被当作备份成功。 */
    @Test
    void retriesExplicitRateLimitThenVerifiesUploadedObject() throws Exception {
        HttpClient client = mock(HttpClient.class);
        var limited = CompletableFuture.completedFuture(response(429, Map.of("Retry-After", List.of("0"))));
        var uploaded = CompletableFuture.completedFuture(response(200, Map.of()));
        var metadata = CompletableFuture.completedFuture(response(200,
                Map.of("x-upyun-file-size", List.of("3"), "Content-MD5", List.of("900150983cd24fb0d6963f7d28e17f72"))));
        when(client.sendAsync(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(limited).thenReturn(uploaded).thenReturn(metadata);
        Path zip = root.resolve("snapshot.zip");
        Files.writeString(zip, "abc");

        new UpyunWorkspaceStore(properties(), client).upload("workspaces/w1/v1.zip", zip);

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(3)).sendAsync(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues()).extracting(HttpRequest::method).containsExactly("PUT", "PUT", "HEAD");
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.uri()).isEqualTo(URI.create("https://v0.api.upyun.com/bucket/workspaces/w1/v1.zip"));
            assertThat(request.timeout()).contains(Duration.ofMinutes(2));
        });
        assertThat(requests.getAllValues().getFirst().headers().firstValue("Content-MD5"))
                .contains("900150983cd24fb0d6963f7d28e17f72");
    }

    /** 服务端摘要不符时拒绝宣布上传成功。 */
    @Test
    void rejectsRemoteDigestMismatch() throws Exception {
        HttpClient client = mock(HttpClient.class);
        var uploaded = CompletableFuture.completedFuture(response(200, Map.of()));
        var metadata = CompletableFuture.completedFuture(response(200,
                Map.of("x-upyun-file-size", List.of("3"), "ETag", List.of("\"00000000000000000000000000000000\""))));
        when(client.sendAsync(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(uploaded).thenReturn(metadata);
        Path zip = root.resolve("snapshot.zip");
        Files.writeString(zip, "abc");

        assertThatThrownBy(() -> new UpyunWorkspaceStore(properties(), client).upload("workspaces/w1/v1.zip", zip))
                .isInstanceOf(IOException.class).hasMessageContaining("摘要不匹配");
    }

    /** 读取失败只清理本次暂存文件，不覆盖已有目标。 */
    @Test
    void failedDownloadPreservesExistingTargetAndRemovesTemporaryFile() throws Exception {
        HttpClient client = mock(HttpClient.class);
        var missing = CompletableFuture.completedFuture(response(404, Map.of()));
        when(client.sendAsync(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(missing);
        Path target = root.resolve("download.zip");
        Files.writeString(target, "previous");

        assertThatThrownBy(() -> new UpyunWorkspaceStore(properties(), client).download("workspaces/w1/missing.zip", target))
                .isInstanceOf(IOException.class).hasMessageContaining("HTTP 404");
        assertThat(Files.readString(target)).isEqualTo("previous");
        try (var files = Files.list(root)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly("download.zip");
        }
    }

    /** 清理不存在的精确版本可以幂等完成。 */
    @Test
    void deletingMissingObjectIsIdempotent() throws Exception {
        HttpClient client = mock(HttpClient.class);
        var missing = CompletableFuture.completedFuture(response(404, Map.of()));
        when(client.sendAsync(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(missing);
        new UpyunWorkspaceStore(properties(), client).delete("workspaces/w1/v1.zip");
        verify(client).sendAsync(any(), any(HttpResponse.BodyHandler.class));
    }

    /** 长等待不能被截短成更快的重试；路径越界在网络副作用前拒绝。 */
    @Test
    void honorsServerDelayBudgetAndRejectsUnsafeKeys() {
        assertThatThrownBy(() -> UpyunWorkspaceStore.retryDelay("120", 0, Instant.EPOCH))
                .isInstanceOf(UpyunWorkspaceStore.DeferredRetryException.class).hasMessageContaining("较长退避")
                .satisfies(failure -> assertThat(((UpyunWorkspaceStore.DeferredRetryException) failure).retryAt())
                        .isEqualTo(Instant.EPOCH.plusSeconds(120)));
        assertThatThrownBy(() -> new UpyunWorkspaceStore(properties(), mock(HttpClient.class)).delete("../other/file.zip"))
                .isInstanceOf(IOException.class).hasMessageContaining("越界");
    }

    /** 测试凭据仅用于协议夹具；部署配置渲染不得泄露这些值。 */
    @Test
    void rejectsUnsafeDeploymentConfigAndRedactsCredentials() {
        assertThat(properties().toString()).doesNotContain("password=", "operator=");
        assertThatThrownBy(() -> new WorkspaceCloudProperties(root, "project-volume", URI.create("http://example.com"),
                "bucket", "operator", "password", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkspaceCloudProperties(root, "project-volume", URI.create("https://v0.api.upyun.com"),
                "bucket", "", "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new UpyunWorkspaceStore.HttpConfiguration().workspaceCloudHttpClient().followRedirects())
                .isEqualTo(HttpClient.Redirect.NEVER);
    }

    /** 构造合法的独立部署输入，高速率只用于无网络的请求序列夹具。 */
    private WorkspaceCloudProperties properties() {
        return new WorkspaceCloudProperties(root, "project-volume", URI.create("https://v0.api.upyun.com"),
                "bucket", "operator", "password", 1000);
    }

    /** 回应夹具只表达状态和白名单元数据，不提供任何认证或错误响应正文。 */
    private static HttpResponse<Void> response(int status, Map<String, List<String>> headers) {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return response;
    }
}
