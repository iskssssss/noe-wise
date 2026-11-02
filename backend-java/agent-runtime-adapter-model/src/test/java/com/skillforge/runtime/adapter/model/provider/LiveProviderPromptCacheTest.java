package com.skillforge.runtime.adapter.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Message;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.PromptCacheScope;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Role;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.StreamEvent;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolChoice;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 显式启用的付费缓存对比；默认测试和 CI 不访问外网。
 *
 * <p>每个服务最多八次流式请求。合成消息通过生产 Provider 编码，HTTP 观察器只复制字节，
 * 不自行添加供应商参数。缓存 token 缺失保持 UNKNOWN，命中率及延迟是观察结果而非硬性通过条件。
 */
@EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_PROMPT_CACHE", matches = "true")
class LiveProviderPromptCacheTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    static final LiveProviderAttemptMeter.Outcome liveMeterOutcome = new LiveProviderAttemptMeter.Outcome();
    @org.junit.jupiter.api.AfterEach void collectLiveAttemptEvidence() { LiveProviderAttemptMeter.snapshot(); }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BYTE_LIMIT = 1024 * 1024;
    private static final long CACHE_PROPAGATION_MILLIS = 5000;
    private static final String TIME_V1 = "Current time: 2026-10-08T08:00:00Z.";
    private static final String TIME_V2 = "Current time: 2026-10-08T08:01:00Z.";

    @Test
    void deepSeekAutomaticCache() throws Exception {
        verify("deepseek", "deepseek-api", "deepseek", "deepseek-flash");
    }

    @Test
    void bailianAutomaticCache() throws Exception {
        verify("aliyun-bailian", "bailian-cn-beijing", "bailian", "qwen3.7-plus");
    }

    @Test
    void bailianExplicitCache() throws Exception {
        verify("aliyun-bailian", "bailian-cn-beijing-explicit-cache", "bailian", "qwen3.7-plus");
    }

    private void verify(String providerId, String serviceId, String credentialName, String defaultModel)
            throws Exception {
        JsonNode configuration = credentials().path(credentialName);
        String credential = configuration.path("api_key").asText();
        assertTrue(!credential.isBlank(), "真实测试凭据缺失");
        String model = configuration.path("model").asText();
        if (model.isBlank()) model = defaultModel;
        String configuredEndpoint = configuration.path("endpoint").asText(null);
        Path evidenceDirectory = Path.of(required("SKILLFORGE_PROMPT_CACHE_EVIDENCE_DIR"))
                .toAbsolutePath().normalize();
        Files.createDirectories(evidenceDirectory);
        String runId = UUID.randomUUID().toString();
        String filePrefix = serviceId + "-" + runId;
        Path reportPath = evidenceDirectory.resolve(filePrefix + "-report.json");
        ObjectNode report = JSON.createObjectNode();
        report.put("schemaVersion", 1).put("startedAt", Instant.now().toString())
                .put("provider", providerId).put("service", serviceId).put("requestedModel", model)
                .put("syntheticInput", true).put("maximumPaidCalls", 8)
                .put("cachePropagationDelayMillis", CACHE_PROPAGATION_MILLIS)
                .put("prefixMetric", "UTF-8 bytes of outbound messages JSON; not supplier token prefix")
                .put("layoutComparison", "independent nonce per layout; memory changes hold time fixed")
                .put("validationScope", "synthetic production Provider requests; not business-session end-to-end acceptance")
                .put("cacheHitRequiredForPass", false).put("completed", false);
        if ("aliyun-bailian".equals(providerId)) {
            report.put("providerSystemMergeCaveat",
                    "Qwen 3.5+ may merge multiple system messages. A changed final system time can therefore "
                            + "invalidate the effective upstream prefix despite an unchanged outbound message prefix.");
        }
        ArrayNode observations = report.putArray("calls");
        HttpClient delegate = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        try (var sdkResources = new com.skillforge.runtime.adapter.model.transport.SdkHttpResources()) {
            ObservedHttpClient http = new ObservedHttpClient(delegate);
            ModelProvider provider = ModelProviders.runtime(http, Clock.systemUTC(), BYTE_LIMIT, sdkResources).stream()
                    .filter(candidate -> providerId.equals(candidate.preset().providerId()))
                    .findFirst().orElseThrow();
            var service = provider.preset().services().stream()
                    .filter(candidate -> serviceId.equals(candidate.serviceId())).findFirst().orElseThrow();
            var protocol = service.protocols().getFirst();
            var profile = new InvocationProfile(service.serviceId(), service.profileVersion(), protocol.protocol(),
                    AuthenticationScheme.BEARER_API_KEY, protocol.dialectVersion());
            report.put("profileVersion", profile.profileVersion()).put("protocol", profile.protocol().name());
            String endpoint = provider.resolveEndpoint(service, configuredEndpoint);
            String oldNonce = UUID.randomUUID().toString();
            String newNonce = UUID.randomUUID().toString();
            List<Message> oldMessages = messages(oldNonce, false, "V1", TIME_V1);
            List<Message> newMessages = messages(newNonce, true, "V1", TIME_V1);
            List<Message> changedTime = messages(newNonce, true, "V2", TIME_V2);
            List<Message> appendOnly = new ArrayList<>(changedTime);
            appendOnly.add(message(Role.ASSISTANT, "OK"));
            appendOnly.add(message(Role.USER, "Reply with exactly OK again, without explanation."));
            List<Scenario> scenarios = List.of(
                    new Scenario("old_cold", oldNonce, oldMessages, false),
                    new Scenario("old_exact_repeat", oldNonce, oldMessages, true),
                    new Scenario("old_memory_change_fixed_time", oldNonce,
                            messages(oldNonce, false, "V2", TIME_V1), false),
                    new Scenario("new_cold", newNonce, newMessages, false),
                    new Scenario("new_exact_repeat", newNonce, newMessages, true),
                    new Scenario("new_memory_change_fixed_time", newNonce,
                            messages(newNonce, true, "V2", TIME_V1), false),
                    new Scenario("new_time_change_only", newNonce, changedTime, false),
                    new Scenario("new_append_only", newNonce, List.copyOf(appendOnly), false));
            byte[] previousBody = null;
            String previousScenario = null;
            for (Scenario scenario : scenarios) {
                if (scenario.waitForCachePropagation()) Thread.sleep(CACHE_PROPAGATION_MILLIS);
                PromptCacheScope scope = new PromptCacheScope(sha256(
                        (providerId + "\n" + serviceId + "\n" + model + "\n" + scenario.nonce())
                                .getBytes(StandardCharsets.UTF_8)));
                var request = new InvocationRequest(endpoint, credential, model, profile, scenario.messages(),
                        new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "live-cache-ab"),
                        new GenerationOptions(OptionalInt.of(128), OptionalDouble.empty()),
                        ReasoningPolicy.DISABLED, List.of(), ToolChoice.NONE, Duration.ofSeconds(45), () -> false,
                        ref -> { throw new IllegalStateException("live_cache_media_not_expected"); },
                        BYTE_LIMIT, Optional.of(scope), () -> { });
                Capture capture = new Capture();
                http.active.set(capture);
                Probe probe = new Probe();
                try {
                    // Admission ceilings are fixture policy; actual tokens come only from Provider usage.
                    var ticket = LiveProviderAttemptMeter.prepare(providerId, model, serviceId + ":" + scenario.name(), 128000, 128, 96000);
                    var meteredProvider = ModelProviders.runtime(LiveProviderAttemptMeter.client(http, ticket), Clock.systemUTC(), BYTE_LIMIT, sdkResources).stream()
                            .filter(candidate -> providerId.equals(candidate.preset().providerId())).findFirst().orElseThrow();
                    meteredProvider.stream(request).subscribe(LiveProviderAttemptMeter.observe(ticket, probe));
                    if (!probe.done.await(55, TimeUnit.SECONDS)) probe.fail("stream_deadline_exceeded");
                } finally {
                    if (probe.subscription != null) probe.subscription.cancel();
                }
                ObjectNode observation = probe.observation();
                observation.put("scenario", scenario.name()).put("nonce", scenario.nonce())
                        .put("cacheScopeHash", scope.key()).put("httpStatus", capture.status)
                        .put("requestSubscriptions", capture.requestSubscriptions)
                        .put("requestCaptureTruncated", capture.request.truncated)
                        .put("responseCaptureTruncated", capture.response.truncated);
                observation.set("rawUsage", rawUsage(capture.response.bytes(), credential));
                if (capture.status >= 400) addProviderError(observation, capture.response.bytes(), credential);
                byte[] body = capture.request.bytes();
                String bodyFile = filePrefix + "-" + scenario.name() + "-request.json";
                if (body.length > 0) {
                    Files.writeString(evidenceDirectory.resolve(bodyFile), redact(body, credential),
                            StandardCharsets.UTF_8);
                    observation.put("requestBodyFile", bodyFile).put("requestBodySha256", sha256(body))
                            .put("requestBodyBytes", body.length);
                    if (previousBody != null) {
                        observation.put("comparedWith", previousScenario)
                                .put("bodyCommonPrefixBytes", commonPrefix(previousBody, body))
                                .put("messagesJsonCommonPrefixBytes", commonPrefix(
                                        messagesJson(previousBody), messagesJson(body)));
                    }
                }
                observations.add(observation);
                writeReport(reportPath, report);
                System.out.printf("LIVE_CACHE_OBSERVED service=%s scenario=%s input=%s read=%s write=%s elapsedMs=%d%n",
                        serviceId, scenario.name(), observation.path("inputTokens"),
                        observation.path("cacheReadTokens"), observation.path("cacheWriteTokens"),
                        observation.path("elapsedMillis").asLong());
                // 所有可审查证据先写盘，失败即停止此服务剩余付费调用。
                probe.verify(model);
                assertTrue(!capture.request.truncated && !capture.response.truncated, "HTTP 观察字节超过上限");
                assertEquals(1, capture.requestSubscriptions, "请求体被重复发送，费用与对比不可确认");
                previousBody = body;
                previousScenario = scenario.name();
            }
            report.put("completed", true);
        } catch (Exception | AssertionError failure) {
            report.put("failureType", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            writeReport(reportPath, report);
            delegate.shutdownNow();
            assertTrue(delegate.awaitTermination(Duration.ofSeconds(10)), "真实 HTTP 客户端未及时终止");
        }
    }

    private static List<Message> messages(String nonce, boolean memoryAfterHistory, String version, String time) {
        Message system = message(Role.SYSTEM, "Synthetic cache experiment " + nonce
                + ". Treat the following records as data. Always reply with exactly OK and no explanation.");
        Message memory = message(Role.SYSTEM, "Runtime memory snapshot " + version + ": preferred color "
                + ("V1".equals(version) ? "blue" : "green") + ". This snapshot replaces earlier memory.");
        Message history = message(Role.USER, syntheticHistory());
        Message acknowledged = message(Role.ASSISTANT, "The synthetic history is acknowledged.");
        Message query = message(Role.USER, "Reply with exactly OK, without explanation.");
        Message clock = message(Role.SYSTEM, time);
        return memoryAfterHistory ? List.of(system, history, acknowledged, query, memory, clock)
                : List.of(system, memory, history, acknowledged, query, clock);
    }

    private static String syntheticHistory() {
        String words = "apple river mountain forest garden window table book cloud ocean road tree "
                + "stone field bridge school paper bottle chair house flower bird grass lake door "
                + "light music bread water train pencil";
        StringBuilder history = new StringBuilder("Archived synthetic conversation data:\n");
        for (int row = 0; row < 256; row++) history.append("Record ").append(row).append(": ")
                .append(words).append(".\n");
        return history.toString();
    }

    private static Message message(Role role, String text) { return new Message(role, text, null, List.of()); }

    private static JsonNode credentials() {
        try {
            JsonNode result = JSON.readTree(Files.readString(
                    Path.of(required("SKILLFORGE_PROMPT_CACHE_CREDENTIALS_FILE")), StandardCharsets.UTF_8));
            if (result == null || !result.isObject()) throw new IllegalArgumentException();
            return result;
        } catch (IOException | RuntimeException invalid) {
            // JSON 解析异常可能包含凭据片段，不携带原异常到测试报告。
            throw new AssertionError("真实测试凭据文件无效");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), "缺少测试环境变量 " + name);
        return value;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String redact(byte[] bytes, String credential) {
        return new String(bytes, StandardCharsets.UTF_8).replace(credential, "***");
    }

    private static byte[] messagesJson(byte[] body) throws IOException {
        return JSON.writeValueAsBytes(JSON.readTree(body).path("messages"));
    }

    private static int commonPrefix(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        int index = 0;
        while (index < length && left[index] == right[index]) index++;
        return index;
    }

    private static ArrayNode rawUsage(byte[] response, String credential) {
        ArrayNode usages = JSON.createArrayNode();
        String body = redact(response, credential);
        if (body.stripLeading().startsWith("{")) addUsage(usages, body);
        else for (String line : body.split("\\r?\\n")) {
            if (line.startsWith("data:")) addUsage(usages, line.substring(5).trim());
        }
        return usages;
    }

    private static void addUsage(ArrayNode usages, String json) {
        if (json.isEmpty() || "[DONE]".equals(json)) return;
        try {
            JsonNode node = JSON.readTree(json);
            if (node != null && node.path("usage").isObject()) usages.add(node.path("usage"));
        } catch (IOException ignored) {
            // 仅提取供应商原始 usage，不把正文、推理或错误响应写盘。
        }
    }

    private static void addProviderError(ObjectNode observation, byte[] response, String credential) {
        try {
            JsonNode root = JSON.readTree(redact(response, credential));
            if (root == null) return;
            JsonNode code = root.path("error").path("code");
            if (code.isMissingNode() || code.isNull()) code = root.path("code");
            safeErrorField(observation, "providerErrorCode", code);
            safeErrorField(observation, "providerErrorType", root.path("error").path("type"));
        } catch (IOException ignored) {
            // 无法解析的错误响应不写盘；保留 HTTP 状态和 Runtime 分类。
        }
    }

    private static void safeErrorField(ObjectNode observation, String field, JsonNode value) {
        if (!value.isTextual() && !value.isIntegralNumber()) return;
        String text = value.asText();
        if (text.matches("[A-Za-z0-9_.:-]{1,100}")) observation.put(field, text);
    }

    private static void writeReport(Path path, ObjectNode report) throws IOException {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
    }

    private record Scenario(String name, String nonce, List<Message> messages, boolean waitForCachePropagation) { }

    private static final class Probe implements Flow.Subscriber<StreamEvent> {
        private final long started = System.nanoTime();
        private final CountDownLatch done = new CountDownLatch(1);
        private final StringBuilder content = new StringBuilder();
        private volatile Flow.Subscription subscription;
        private ModelUsage usage = new ModelUsage(OptionalLong.empty(), OptionalLong.empty());
        private InvocationResult.Completed completed;
        private String outcome = "no_terminal";
        private String reason;
        private int terminalCount;
        private long firstContent;
        private long firstProviderDelta;

        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(Long.MAX_VALUE);
        }

        @Override public synchronized void onNext(StreamEvent event) {
            if (event instanceof StreamEvent.ContentDelta delta) {
                if (firstContent == 0) firstContent = System.nanoTime();
                if (firstProviderDelta == 0) firstProviderDelta = System.nanoTime();
                content.append(delta.content());
            } else if (event instanceof StreamEvent.ReasoningDelta) {
                if (firstProviderDelta == 0) firstProviderDelta = System.nanoTime();
            } else if (event instanceof StreamEvent.UsageDelta delta) {
                usage = delta.usage();
            } else if (event instanceof StreamEvent.Completed terminal) {
                completed = terminal.result();
                usage = completed.usage();
                outcome = "completed";
                terminalCount++;
            } else if (event instanceof StreamEvent.Rejected rejected) {
                outcome = "rejected";
                reason = rejected.reasonCode();
                terminalCount++;
            } else if (event instanceof StreamEvent.OutcomeUnknown unknown) {
                outcome = "outcome_unknown";
                reason = unknown.reasonCode();
                terminalCount++;
            }
        }

        @Override public synchronized void onError(Throwable failure) {
            fail("unclassified_" + failure.getClass().getSimpleName());
            done.countDown();
        }

        @Override public void onComplete() { done.countDown(); }

        private synchronized void fail(String code) { reason = code; }

        private synchronized ObjectNode observation() {
            ObjectNode result = JSON.createObjectNode();
            result.put("outcome", outcome).put("terminalCount", terminalCount)
                    .put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (reason != null) result.put("reasonCode", reason);
            if (completed != null) result.put("finishReason", completed.finishReasonCode())
                    .put("contentMatchesOK", "OK".equals(completed.content() == null ? "" : completed.content().trim()));
            if (firstContent > 0) result.put("timeToFirstContentMillis",
                    TimeUnit.NANOSECONDS.toMillis(firstContent - started));
            else result.putNull("timeToFirstContentMillis");
            if (firstProviderDelta > 0) result.put("timeToFirstProviderDeltaMillis",
                    TimeUnit.NANOSECONDS.toMillis(firstProviderDelta - started));
            else result.putNull("timeToFirstProviderDeltaMillis");
            putTokens(result, "inputTokens", usage.inputTokens());
            putTokens(result, "outputTokens", usage.outputTokens());
            putTokens(result, "cacheReadTokens", usage.cachedInputTokens());
            putTokens(result, "cacheWriteTokens", usage.cacheWriteInputTokens());
            putTokens(result, "reasoningOutputTokens", usage.reasoningOutputTokens());
            return result;
        }

        private synchronized void verify(String model) {
            assertEquals(null, reason, "供应商流失败分类");
            assertEquals(1, terminalCount, "供应商流必须恰好一个终态");
            assertTrue(completed != null, outcome);
            assertEquals(model, completed.modelId());
            assertEquals("stop", completed.finishReasonCode());
            assertEquals("OK", completed.content() == null ? "" : completed.content().trim());
            assertEquals(completed.content(), content.toString(), "流式正文与终态不一致");
            assertTrue(completed.toolCalls().isEmpty());
            assertTrue(usage.inputTokens().isPresent(), "供应商输入计量缺失");
            assertTrue(usage.outputTokens().isPresent(), "供应商输出计量缺失");
            assertTrue(usage.inputTokens().orElseThrow() >= 4096, "合成输入不足以观察长前缀缓存");
        }

        private static void putTokens(ObjectNode result, String name, OptionalLong tokens) {
            if (tokens.isPresent()) result.put(name, tokens.getAsLong());
            else result.putNull(name);
            result.put(name + "Status", tokens.isPresent() ? "REPORTED" : "UNKNOWN");
        }
    }

    private static final class Capture {
        private final BoundedBytes request = new BoundedBytes();
        private final BoundedBytes response = new BoundedBytes();
        private volatile int status;
        private volatile int requestSubscriptions;
    }

    private static final class BoundedBytes {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile boolean truncated;

        private synchronized void append(ByteBuffer buffer) {
            ByteBuffer copy = buffer.asReadOnlyBuffer();
            int available = BYTE_LIMIT - bytes.size();
            if (copy.remaining() > available) truncated = true;
            byte[] chunk = new byte[Math.min(copy.remaining(), available)];
            copy.get(chunk);
            bytes.writeBytes(chunk);
        }

        private synchronized byte[] bytes() { return bytes.toByteArray(); }
    }

    /** 保持生产发送、取消和响应消费语义，只复制实际 body 字节。 */
    private static final class ObservedHttpClient extends HttpClient {
        private final HttpClient delegate;
        private final AtomicReference<Capture> active = new AtomicReference<>();

        private ObservedHttpClient(HttpClient delegate) { this.delegate = delegate; }

        private HttpRequest observe(HttpRequest request, Capture capture) {
            HttpRequest.BodyPublisher original = request.bodyPublisher().orElseThrow();
            HttpRequest.BodyPublisher observed = new HttpRequest.BodyPublisher() {
                @Override public long contentLength() { return original.contentLength(); }
                @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                    capture.requestSubscriptions++;
                    original.subscribe(new Flow.Subscriber<>() {
                        @Override public void onSubscribe(Flow.Subscription subscription) {
                            subscriber.onSubscribe(subscription);
                        }
                        @Override public void onNext(ByteBuffer bytes) {
                            capture.request.append(bytes);
                            subscriber.onNext(bytes);
                        }
                        @Override public void onError(Throwable failure) { subscriber.onError(failure); }
                        @Override public void onComplete() { subscriber.onComplete(); }
                    });
                }
            };
            return HttpRequest.newBuilder(request, (name, value) -> true)
                    .method(request.method(), observed).build();
        }

        private <T> HttpResponse.BodyHandler<T> observe(HttpResponse.BodyHandler<T> handler, Capture capture) {
            return information -> {
                capture.status = information.statusCode();
                HttpResponse.BodySubscriber<T> original = handler.apply(information);
                return new HttpResponse.BodySubscriber<>() {
                    @Override public CompletionStage<T> getBody() { return original.getBody(); }
                    @Override public void onSubscribe(Flow.Subscription subscription) { original.onSubscribe(subscription); }
                    @Override public void onNext(List<ByteBuffer> buffers) {
                        buffers.forEach(capture.response::append);
                        original.onNext(buffers);
                    }
                    @Override public void onError(Throwable failure) { original.onError(failure); }
                    @Override public void onComplete() { original.onComplete(); }
                };
            };
        }

        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            Capture capture = active.get();
            return delegate.send(observe(request, capture), observe(handler, capture));
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            Capture capture = active.get();
            return delegate.sendAsync(observe(request, capture), observe(handler, capture));
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler) {
            Capture capture = active.get();
            return delegate.sendAsync(observe(request, capture), observe(handler, capture), pushHandler);
        }
        @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return delegate.followRedirects(); }
        @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
        @Override public SSLContext sslContext() { return delegate.sslContext(); }
        @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public Optional<Executor> executor() { return delegate.executor(); }
    }
}
