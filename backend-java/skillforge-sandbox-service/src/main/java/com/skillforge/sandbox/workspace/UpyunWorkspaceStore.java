package com.skillforge.sandbox.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** 又拍云版本快照适配；共享限速、无重定向、有限重试，不参与用户代码执行。 */
@Component
public class UpyunWorkspaceStore {
    private static final Logger log = LoggerFactory.getLogger(UpyunWorkspaceStore.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private static final int MAX_ATTEMPTS = 5;
    private final WorkspaceCloudProperties properties;
    private final HttpClient client;
    private long nextRequestNanos;

    /** 注入专用客户端，使协议测试和实际部署共用相同的请求边界。 */
    public UpyunWorkspaceStore(WorkspaceCloudProperties properties,
            @Qualifier("workspaceCloudHttpClient") HttpClient client) {
        this.properties = properties;
        this.client = client;
    }

    /** 云端配置为空时允许保留本地工作区，由启用入口决定是否需要备份能力。 */
    public boolean configured() {
        boolean configured = !properties.bucket().isBlank() && !properties.operator().isBlank()
                && !properties.password().isBlank();
        log.debug("读取工作区云端配置状态 configured={}", configured);
        return configured;
    }

    /** 流式上传已冻结 ZIP；提交内容摘要后再核验远端大小与摘要，成功才允许推进备份事实。 */
    public void upload(String key, Path zip) throws IOException {
        log.debug("开始上传工作区快照 keyTag={}", keyTag(key));
        if (!Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("工作区快照不是普通文件");
        }
        URI uri = objectUri(key);
        long size = Files.size(zip);
        String digest = md5(zip);
        HttpResponse<Void> uploaded = send(() -> request("PUT", uri, digest)
                .header("Content-Type", "application/zip")
                .header("Mkdir", "true")
                .PUT(fileBody(zip)).build(), HttpResponse.BodyHandlers.discarding());
        requireSuccess(uploaded.statusCode(), "上传");
        HttpResponse<Void> metadata = send(() -> request("HEAD", uri, null)
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        requireSuccess(metadata.statusCode(), "校验");
        String remoteSize = metadata.headers().firstValue("x-upyun-file-size")
                .or(() -> metadata.headers().firstValue("Content-Length")).orElse("");
        String remoteDigest = metadata.headers().firstValue("Content-MD5")
                .or(() -> metadata.headers().firstValue("ETag"))
                .or(() -> metadata.headers().firstValue("x-upyun-file-md5")).orElse("");
        if (!Long.toString(size).equals(remoteSize) || !digest.equalsIgnoreCase(unquote(remoteDigest))) {
            throw new IOException("工作区快照远端大小或摘要不匹配");
        }
        log.info("工作区快照上传并校验完成 keyTag={} bytes={}", keyTag(key), size);
    }

    /** 下载到同目录暂存文件；网络失败或摘要不符不会用部分内容覆盖目标。 */
    public void download(String key, Path target) throws IOException {
        log.debug("开始下载工作区快照 keyTag={}", keyTag(key));
        URI uri = objectUri(key);
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".workspace-download-", ".part");
        try {
            HttpResponse.BodyHandler<Path> handler = info -> info.statusCode() >= 200 && info.statusCode() < 300
                    ? HttpResponse.BodyHandlers.ofFile(temporary).apply(info)
                    : HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.discarding(), ignored -> (Path) null);
            HttpResponse<Path> downloaded = send(() -> request("GET", uri, null).GET().build(), handler);
            requireSuccess(downloaded.statusCode(), "下载");
            String digest = downloaded.headers().firstValue("Content-MD5")
                    .or(() -> downloaded.headers().firstValue("ETag")).orElse("");
            if (!digest.isBlank() && !md5(temporary).equalsIgnoreCase(unquote(digest))) {
                throw new IOException("工作区快照下载摘要不匹配");
            }
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info("工作区快照下载完成 keyTag={} bytes={}", keyTag(key), Files.size(absolute));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 删除持久登记的精确快照对象；不存在视为已清理，不枚举或删除其他工作区。 */
    public void delete(String key) throws IOException {
        log.debug("开始删除工作区云端快照 keyTag={}", keyTag(key));
        URI uri = objectUri(key);
        HttpResponse<Void> deleted = send(() -> request("DELETE", uri, null).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        if (deleted.statusCode() != 404) {
            requireSuccess(deleted.statusCode(), "删除");
        }
        log.info("工作区云端快照清理完成 keyTag={} status={}", keyTag(key), deleted.statusCode());
    }

    /** 每次重试重新签名和打开文件流；只对服务端明确返回的 429 在本轮退避。 */
    private <T> HttpResponse<T> send(Supplier<HttpRequest> factory, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        log.debug("开始工作区云端请求");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            awaitPermit();
            CompletableFuture<HttpResponse<T>> future = client.sendAsync(factory.get(), handler);
            HttpResponse<T> response;
            try {
                response = future.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("工作区云端请求已中断", interrupted);
            } catch (TimeoutException timeout) {
                future.cancel(true);
                throw new IOException("工作区云端请求超时");
            } catch (ExecutionException failed) {
                // 不传播可能包含请求 URL/头部的外部异常文本；持久队列负责下一轮重试。
                throw new IOException("工作区云端连接失败");
            }
            if (response.statusCode() != 429 || attempt == MAX_ATTEMPTS - 1) {
                log.debug("工作区云端请求结束 status={} attempts={}", response.statusCode(), attempt + 1);
                return response;
            }
            Duration delay = retryDelay(response.headers().firstValue("Retry-After").orElse(""), attempt, Instant.now());
            log.warn("工作区云端请求限流 attempt={} retryMillis={}", attempt + 1, delay.toMillis());
            pause(delay);
        }
        throw new IOException("工作区云端请求未能完成");
    }

    /** 全部云端操作共用单实例速率窗口，避免上传、恢复、清理各自限速后累加超额。 */
    private synchronized void awaitPermit() throws IOException {
        log.debug("等待工作区云端请求速率窗口");
        long now = System.nanoTime();
        long remaining = nextRequestNanos - now;
        if (remaining > 0) {
            pause(Duration.ofNanos(remaining));
        }
        nextRequestNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1) / properties.requestsPerSecond();
        log.debug("取得工作区云端请求速率窗口");
    }

    /** 服务端等待超过本轮预算时停止本轮；不会截短其要求而提前再次请求。 */
    static Duration retryDelay(String value, int attempt, Instant now) throws IOException {
        log.debug("计算工作区云端退避 attempt={}", attempt + 1);
        Duration directed = null;
        try {
            if (!value.isBlank()) {
                directed = Duration.ofSeconds(Long.parseLong(value));
            }
        } catch (NumberFormatException invalidSeconds) {
            try {
                directed = Duration.between(now, DateTimeFormatter.RFC_1123_DATE_TIME.parse(value, Instant::from));
            } catch (DateTimeParseException invalidDate) {
                directed = null;
            }
        }
        if (directed != null && !directed.isNegative()) {
            if (directed.compareTo(Duration.ofSeconds(60)) > 0) {
                throw new DeferredRetryException(now.plus(directed));
            }
            log.debug("采用工作区云端指定退避 millis={}", directed.toMillis());
            return directed;
        }
        long base = Math.min(60_000L, 1_000L << Math.min(attempt, 6));
        Duration delay = Duration.ofMillis(Math.min(60_000L, base + ThreadLocalRandom.current().nextLong(base / 4 + 1)));
        log.debug("采用工作区云端指数退避 millis={}", delay.toMillis());
        return delay;
    }

    /** 构造 SDK 一致的 HMAC 签名；Content-MD5 存在时必须纳入签名。 */
    private HttpRequest.Builder request(String method, URI uri, String contentMd5) {
        log.debug("构造工作区云端请求 method={}", method);
        String date = HTTP_DATE.format(Instant.now());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                .header("Date", date)
                .header("Authorization", authorization(method, uri.getRawPath(), date, contentMd5,
                        properties.operator(), properties.password()));
        if (contentMd5 != null) {
            builder.header("Content-MD5", contentMd5);
        }
        log.debug("工作区云端请求构造完成 method={}", method);
        return builder;
    }

    /** 认证计算不写参数日志，避免操作员、密码与签名进入诊断输出。 */
    static String authorization(String method, String rawPath, String date, String contentMd5,
            String operator, String password) {
        log.debug("计算工作区云端请求认证 method={}", method);
        try {
            String passwordMd5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(password.getBytes(StandardCharsets.UTF_8)));
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(passwordMd5.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String message = method + "&" + rawPath + "&" + date
                    + (contentMd5 == null || contentMd5.isBlank() ? "" : "&" + contentMd5);
            String result = "UPYUN " + operator + ":" + Base64.getEncoder()
                    .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
            log.debug("工作区云端请求认证计算完成 method={}", method);
            return result;
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("工作区云端认证算法不可用", unavailable);
        }
    }

    /** 对象地址只接受安全相对键，统一编码路径并保持服务名边界。 */
    private URI objectUri(String key) throws IOException {
        log.debug("解析工作区云端对象地址 keyTag={}", keyTag(key));
        if (!configured()) {
            throw new IOException("工作区云端未配置");
        }
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("\\")
                || key.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("工作区云端对象键不合法");
        }
        for (String part : key.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IOException("工作区云端对象键越界");
            }
        }
        try {
            URI endpoint = properties.endpoint();
            URI uri = new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(),
                    "/" + properties.bucket() + "/" + key, null, null);
            log.debug("工作区云端对象地址解析完成 keyTag={}", keyTag(key));
            return uri;
        } catch (URISyntaxException invalid) {
            throw new IOException("工作区云端对象地址不合法");
        }
    }

    /** 整体流式计算摘要，不把快照读入堆内存。 */
    private static String md5(Path file) throws IOException {
        log.debug("开始计算工作区快照传输摘要");
        try (InputStream stream = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
            }
            String result = HexFormat.of().formatHex(digest.digest());
            log.debug("工作区快照传输摘要计算完成");
            return result;
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("工作区快照摘要算法不可用", unavailable);
        }
    }

    /** 为每次 PUT 创建独立文件 publisher；异常只保留受控说明。 */
    private static HttpRequest.BodyPublisher fileBody(Path file) {
        log.debug("创建工作区快照文件传输流");
        try {
            return HttpRequest.BodyPublishers.ofFile(file);
        } catch (IOException unavailable) {
            throw new IllegalStateException("工作区快照文件无法读取");
        }
    }

    /** 不读取错误响应正文，避免远端回显的认证上下文泄露。 */
    private static void requireSuccess(int status, String operation) throws IOException {
        log.debug("检查工作区云端响应 operation={} status={}", operation, status);
        if (status < 200 || status >= 300) {
            throw new IOException("工作区云端" + operation + "失败，HTTP " + status);
        }
        log.debug("工作区云端响应检查完成 operation={}", operation);
    }

    /** 去掉 ETag 的标准双引号，不接受多段或弱摘要冒充完整文件 MD5。 */
    private static String unquote(String value) {
        log.debug("规范化工作区云端摘要");
        String normalized = value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2
                ? value.substring(1, value.length() - 1) : value;
        log.debug("工作区云端摘要规范化完成");
        return normalized;
    }

    /** 等待可中断；关闭服务后不继续占用后台上传线程。 */
    private static void pause(Duration delay) throws IOException {
        log.debug("等待工作区云端调用窗口 millis={}", delay.toMillis());
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("工作区云端等待已中断", interrupted);
        }
        log.debug("工作区云端调用窗口等待完成");
    }

    /** 对象键日志只保留不可逆摘要标签，避免 Session 等关联原值泄露。 */
    private static String keyTag(String key) {
        return key == null ? "none" : Integer.toHexString(key.hashCode());
    }

    /** 本轮结束后仍须遵守服务端指定时刻，持久备份队列不得用更短的默认间隔覆盖它。 */
    public static final class DeferredRetryException extends IOException {
        private static final long serialVersionUID = 1L;
        private final Instant retryAt;

        /** 仅携带下一次允许请求的时间，不携带响应正文或认证上下文。 */
        public DeferredRetryException(Instant retryAt) {
            super("工作区云端要求较长退避，等待后续备份批次");
            this.retryAt = Objects.requireNonNull(retryAt, "retryAt");
            log.debug("工作区云端请求延期到后续批次 retryAt={}", retryAt);
        }

        /** 向持久队列提供准确的下次请求时间，确保重启后仍不提前重试。 */
        public Instant retryAt() {
            log.debug("读取工作区云端延期时间");
            log.debug("工作区云端延期时间读取完成 retryAt={}", retryAt);
            return retryAt;
        }
    }

    /** 专用客户端通过正式 Bean 注入入口装配，不复用可能跟随重定向的通用客户端。 */
    @Configuration(proxyBeanMethods = false)
    static class HttpConfiguration {
        @Bean("workspaceCloudHttpClient")
        HttpClient workspaceCloudHttpClient() {
            log.debug("创建工作区云端专用 HTTP 客户端");
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
            log.debug("工作区云端专用 HTTP 客户端创建完成");
            return client;
        }
    }
}
