package com.skillforge.core.capability.connector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.core.shared.sandbox.SandboxGuard;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 平台通用 HTTP 调用工具。
 *
 * <p>只接受 GET/POST 与完整 URL；发送前经 {@link SandboxGuard} 校验，禁止重定向、
 * URL 用户信息和改写 Host。已发出请求后的传输失败视为结果未知，不伪装成校验错误。
 */
@Component
public class HttpRequestToolHandler implements PlatformToolHandler {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "host", "content-length", "transfer-encoding", "connection", "cookie");

    private final SandboxGuard sandboxGuard;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final RunSettingsService settings;

    @Autowired
    public HttpRequestToolHandler(
            SandboxGuard sandboxGuard, ObjectMapper objectMapper, RunSettingsService settings) {
        this(sandboxGuard, objectMapper, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build(), settings);
    }

    HttpRequestToolHandler(SandboxGuard sandboxGuard, ObjectMapper objectMapper, HttpClient http,
            RunSettingsService settings) {
        this.sandboxGuard = Objects.requireNonNull(sandboxGuard, "sandboxGuard");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.http = Objects.requireNonNull(http, "http");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("http_request 禁止跟随重定向");
        }
    }

    @Override
    public String toolName() {
        return "http_request";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, PlatformToolExecutionContext context)
            throws Exception {
        String tenant = context.run().runRef().tenantId().value();
        String run = context.run().runRef().runId();
        var operation = settings.captureTool(tenant, run, context.invocationId());
        var calls = settings.read(operation, "calls");
        TransferPolicy policy = new TransferPolicy(
                calls.integer("http_tool_max_response_bytes"),
                Duration.ofSeconds(calls.longValue("http_tool_timeout_seconds")));
        PreparedRequest prepared;
        try {
            prepared = prepare(args, policy.timeout());
        } catch (RejectedRequest rejected) {
            return fail(rejected.getMessage());
        }
        try {
            return transfer(prepared.request(), policy, context);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExternalInvocationGuard.OutcomeUnknownException("http_request 发送后中断，结果未知", interrupted);
        } catch (IOException unknown) {
            throw new ExternalInvocationGuard.OutcomeUnknownException("http_request 发送后结果未知", unknown);
        }
    }

    /** 流式 body 的读取不继承 JDK 请求 timeout；整个传输共享冻结预算，并在取消时关闭流。 */
    private Map<String, Object> transfer(HttpRequest request, TransferPolicy policy,
            PlatformToolExecutionContext context) throws IOException, InterruptedException {
        if (!Instant.now().isBefore(context.deadlineAt()) || context.cancellationSignal().isCancellationRequested())
            throw new IOException("HTTP 传输在发送前已截止或取消");
        long expiresAt = System.nanoTime() + policy.timeout().toNanos();
        var activeBody = new AtomicReference<InputStream>();
        var task = new FutureTask<Map<String, Object>>(() -> {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream input = response.body();
            try {
                activeBody.set(input);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    // 响应头已确定拒绝；不可见正文不需要排空，关闭即可保留原 3xx 结果。
                    return fail("不允许跟随重定向", status, contentType(response), "", false);
                }
                Body body = readBody(input, policy.maxResponseBytes());
                if (status < 200 || status >= 300)
                    return fail("HTTP 请求返回非成功状态码：" + status, status,
                            contentType(response), body.text(), body.truncated());
                return success(status, contentType(response), body.text(), body.truncated());
            } finally {
                closeBody(activeBody.getAndSet(null));
            }
        });
        Thread worker = Thread.startVirtualThread(task);
        Throwable primaryFailure = null;
        try {
            while (true) {
                long remaining = Math.min(expiresAt - System.nanoTime(),
                        Duration.between(Instant.now(), context.deadlineAt()).toNanos());
                if (remaining <= 0 || context.cancellationSignal().isCancellationRequested())
                    throw new IOException("HTTP 传输已截止或取消");
                try {
                    return task.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException waiting) {
                    // 复查整个传输的预算与可信取消信号，而不是重新开始正文计时。
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof IOException io) throw io;
                    if (cause instanceof InterruptedException interrupted) throw interrupted;
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    throw new IOException("HTTP 传输失败", cause);
                }
            }
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!task.isDone()) task.cancel(true);
            if (worker.isAlive()) worker.interrupt();
            closeBody(activeBody.getAndSet(null));
            // 只有本次创建的传输 worker 退出才完成清理；二次中断不能提前跳过静止等待。
            boolean restoreInterrupt = Thread.interrupted();
            long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            try {
                while (worker.isAlive()) {
                    long remaining = cleanupDeadline - System.nanoTime();
                    if (remaining <= 0) break;
                    try { TimeUnit.NANOSECONDS.timedJoin(worker, remaining); }
                    catch (InterruptedException interrupted) { restoreInterrupt = true; }
                }
                if (worker.isAlive()) {
                    var cleanupFailure = new IOException("HTTP 传输 worker 未及时结束");
                    if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                    else throw cleanupFailure;
                }
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeBody(InputStream input) {
        if (input == null) return;
        try { input.close(); }
        catch (IOException ignored) {
            // 清理失败不得覆盖已确定的重定向拒绝、截止、取消或传输异常。
        }
    }

    private PreparedRequest prepare(Map<String, Object> args, Duration timeout) throws RejectedRequest {
        if (args == null) {
            throw new RejectedRequest("method 与 url 为必填参数");
        }
        String method = text(args.get("method"));
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new RejectedRequest("method 仅支持 GET 或 POST");
        }
        String url = text(args.get("url"));
        if (url == null) {
            throw new RejectedRequest("url 为必填参数");
        }
        if (args.get("body") != null && "GET".equals(method)) {
            throw new RejectedRequest("GET 不得携带 body");
        }
        URI target = targetUri(url, args.get("query"));
        Map<String, String> headers = headers(args.get("headers"));
        byte[] body = bodyBytes(method, args.get("body"));
        String safeUrl;
        try {
            safeUrl = sandboxGuard.assertSafe(target.toString());
        } catch (ApiException rejected) {
            throw new RejectedRequest(rejected.getMessage());
        }
        URI safeTarget = URI.create(safeUrl);
        if (!Objects.equals(target.getScheme(), safeTarget.getScheme())
                || !Objects.equals(target.getHost(), safeTarget.getHost())
                || target.getPort() != safeTarget.getPort()) {
            throw new RejectedRequest("沙箱规范化不得改变目标主机");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(safeTarget).timeout(timeout);
        headers.forEach(builder::header);
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            if (headers.keySet().stream().noneMatch(name -> "content-type".equals(name.toLowerCase(Locale.ROOT)))) {
                builder.header("Content-Type", "application/json; charset=UTF-8");
            }
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        }
        return new PreparedRequest(builder.build());
    }

    private URI targetUri(String url, Object query) throws RejectedRequest {
        URI parsed;
        try {
            parsed = URI.create(url.trim());
        } catch (IllegalArgumentException invalid) {
            throw new RejectedRequest("url 不是合法 URI");
        }
        if (parsed.getScheme() == null || parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new RejectedRequest("url 必须包含主机");
        }
        if (parsed.getRawUserInfo() != null || parsed.getUserInfo() != null) {
            throw new RejectedRequest("url 不得包含用户信息");
        }
        if (parsed.getRawFragment() != null) {
            throw new RejectedRequest("url 不得包含 fragment");
        }
        String mergedQuery = mergeQuery(parsed.getRawQuery(), query);
        String path = parsed.getRawPath() == null || parsed.getRawPath().isBlank() ? "/" : parsed.getRawPath();
        StringBuilder built = new StringBuilder();
        built.append(parsed.getScheme()).append("://");
        appendHost(built, parsed.getHost());
        if (parsed.getPort() != -1) {
            built.append(':').append(parsed.getPort());
        }
        built.append(path);
        if (mergedQuery != null) {
            built.append('?').append(mergedQuery);
        }
        try {
            URI target = URI.create(built.toString());
            if (target.getRawUserInfo() != null
                    || !parsed.getHost().equals(target.getHost())
                    || !parsed.getScheme().equals(target.getScheme())
                    || parsed.getPort() != target.getPort()) {
                throw new RejectedRequest("无法组合请求 URL");
            }
            return target;
        } catch (IllegalArgumentException invalid) {
            throw new RejectedRequest("无法组合请求 URL");
        }
    }

    private String mergeQuery(String existing, Object query) throws RejectedRequest {
        List<String> parts = new ArrayList<>();
        if (existing != null && !existing.isBlank()) {
            parts.add(existing);
        }
        if (query == null) {
            return parts.isEmpty() ? null : String.join("&", parts);
        }
        if (!(query instanceof Map<?, ?> values)) {
            throw new RejectedRequest("query 必须是对象");
        }
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String name) || name.isBlank() || !name.equals(name.trim())) {
                throw new RejectedRequest("query 字段名必须是非空字符串");
            }
            if (entry.getValue() == null) {
                continue;
            }
            if (!(entry.getValue() instanceof String || entry.getValue() instanceof Number
                    || entry.getValue() instanceof Boolean)) {
                throw new RejectedRequest("query 值仅支持字符串、数字或布尔");
            }
            parts.add(encode(name) + "=" + encode(String.valueOf(entry.getValue())));
        }
        return parts.isEmpty() ? null : String.join("&", parts);
    }

    private Map<String, String> headers(Object raw) throws RejectedRequest {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> values)) {
            throw new RejectedRequest("headers 必须是对象");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String name) || name.isBlank() || !name.equals(name.trim())) {
                throw new RejectedRequest("header 名必须是非空字符串");
            }
            if (FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new RejectedRequest("不允许覆盖受保护的请求头");
            }
            if (!(entry.getValue() instanceof String value) || value.chars().anyMatch(code -> code < 32)) {
                throw new RejectedRequest("header 值必须是不含控制字符的字符串");
            }
            headers.put(name, value);
        }
        return Map.copyOf(headers);
    }

    private byte[] bodyBytes(String method, Object body) throws RejectedRequest {
        if (!"POST".equals(method)) {
            return new byte[0];
        }
        if (body == null) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
        if (!(body instanceof Map<?, ?>)) {
            throw new RejectedRequest("body 必须是 JSON 对象");
        }
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException invalid) {
            throw new RejectedRequest("body 无法序列化为 JSON");
        }
    }

    private Body readBody(InputStream input, int maxResponseBytes) throws IOException {
        if (input == null) {
            return new Body("", false);
        }
        try (InputStream stream = input) {
            byte[] bytes = stream.readNBytes(maxResponseBytes + 1);
            boolean truncated = bytes.length > maxResponseBytes;
            int length = truncated ? maxResponseBytes : bytes.length;
            return new Body(new String(bytes, 0, length, StandardCharsets.UTF_8), truncated);
        }
    }

    private static void appendHost(StringBuilder built, String host) {
        if (host.contains(":")) {
            built.append('[').append(host).append(']');
            return;
        }
        built.append(host);
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> fail(String error) {
        return fail(error, null, "", "", false);
    }

    private static Map<String, Object> fail(String error, Integer status, String contentType,
            String body, boolean truncated) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", error);
        if (status != null) {
            result.put("status", status);
        }
        result.put("content_type", contentType);
        result.put("body", body);
        result.put("truncated", truncated);
        return Map.copyOf(result);
    }

    private static Map<String, Object> success(int status, String contentType, String body, boolean truncated) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("status", status);
        result.put("content_type", contentType);
        result.put("body", body);
        result.put("truncated", truncated);
        return Map.copyOf(result);
    }

    private record PreparedRequest(HttpRequest request) {
    }

    private record Body(String text, boolean truncated) {
    }

    private record TransferPolicy(int maxResponseBytes, Duration timeout) {
    }

    /** 预发送参数或沙箱拒绝，不视为已发出的未知结果。 */
    private static final class RejectedRequest extends Exception {
        private RejectedRequest(String message) {
            super(message);
        }
    }
}
