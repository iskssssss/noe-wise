package com.skillforge.institution.integration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * 隔离记忆验收的透明 HTTP 观察器。只读取 duplicate，不额外订阅、重试或修改载荷。
 * 响应正文仅写入授权的私有合成样本证据；URI、请求头、凭据和异常正文不序列化。
 */
final class MemoryRecordingHttpClient extends HttpClient {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final int MAX_OBSERVED_BYTES = 32 * 1024 * 1024;
    private static final List<String> TOKEN_PATHS = List.of(
            "usage.prompt_tokens", "usage.input_tokens", "usage.completion_tokens", "usage.output_tokens",
            "usage.total_tokens", "usage.prompt_cache_hit_tokens", "usage.prompt_cache_miss_tokens",
            "usage.cache_read_input_tokens", "usage.cache_creation_input_tokens", "usage.cached_tokens",
            "usage.reasoning_tokens", "usage.prompt_tokens_details.cached_tokens",
            "usage.input_tokens_details.cached_tokens", "usage.completion_tokens_details.reasoning_tokens",
            "usage.output_tokens_details.reasoning_tokens", "usage.prompt_tokens_details.cache_hit_tokens",
            "usage.prompt_tokens_details.cache_miss_tokens", "usage.prompt_tokens_details.cache_read_tokens",
            "usage.prompt_tokens_details.cache_write_tokens");
    private static final List<String> REPORTED_PATHS = List.of(
            "usage.reported", "usage.is_reported", "usage.input_tokens_reported", "usage.output_tokens_reported",
            "usage.prompt_tokens_reported", "usage.completion_tokens_reported", "usage.cache_hit_tokens_reported",
            "usage.cache_miss_tokens_reported", "usage.cost_reported");
    private static final List<String> COST_PATHS = List.of(
            "usage.cost", "usage.total_cost", "usage.input_cost", "usage.output_cost", "usage.prompt_cost",
            "usage.completion_cost", "usage.cache_read_cost", "usage.cache_write_cost", "usage.reported_cost",
            "usage.prompt_cache_hit_cost", "usage.prompt_cache_miss_cost", "usage.cache_creation_cost");
    private final HttpClient delegate;
    private final Clock clock;
    private final LongSupplier nanos;
    private final List<Exchange> exchanges = new ArrayList<>();
    private final Map<String, Exchange> attempts = new LinkedHashMap<>();
    private final Map<String, RawRequest> previousByScope = new LinkedHashMap<>();
    private final Map<Thread, Pending> pendingOnThread = new LinkedHashMap<>();
    private Pending pending;
    private RawRequest previous;

    /** 生产创建的 HttpClient 原样作为 delegate；生命周期也由原有宿主负责。 */
    MemoryRecordingHttpClient(HttpClient delegate) {
        this(delegate, Clock.systemUTC(), System::nanoTime);
    }

    /** 离线测试注入单调时钟，避免依赖 sleep 或网络时延。 */
    MemoryRecordingHttpClient(HttpClient delegate, Clock clock, LongSupplier nanos) {
        this.delegate = Objects.requireNonNull(delegate);
        this.clock = Objects.requireNonNull(clock);
        this.nanos = Objects.requireNonNull(nanos);
    }

    /** Audit 先登记，再由跨线程的唯一聊天 send 消费；不使用 ThreadLocal 或正文猜测关联。 */
    synchronized void beginAttempt(AttemptContext context) {
        beginAttempt(context, context.category());
    }

    /** scope 是夹具给出的公开分组标签，供主调用与辅助调用分别比较前缀。 */
    synchronized void beginAttempt(AttemptContext context, String scope) {
        Objects.requireNonNull(context);
        scope = safeTag(scope);
        if (pending != null || registered(context.attemptId())
                || exchanges.stream().anyMatch(value -> !value.threadBound && !value.hasTerminal())) {
            throw new IllegalStateException("memory_eval_attempt_correlation_rejected");
        }
        pending = new Pending(context, scope, false);
    }

    /** 已知同步辅助路径在预算 markSent 同线程登记；不影响主请求跨线程的 pending。 */
    synchronized void beginThreadAttempt(AttemptContext context, String scope) {
        Objects.requireNonNull(context);
        scope = safeTag(scope);
        if (pendingOnThread.containsKey(Thread.currentThread()) || registered(context.attemptId())) {
            throw new IllegalStateException("memory_eval_thread_attempt_correlation_rejected");
        }
        pendingOnThread.put(Thread.currentThread(), new Pending(context, scope, true));
    }

    private boolean registered(String attemptId) {
        return attempts.containsKey(attemptId) || pending != null && pending.context().attemptId().equals(attemptId)
                || pendingOnThread.values().stream().anyMatch(value -> value.context().attemptId().equals(attemptId));
    }

    /** 与已消费的 attempt 精确绑定；发送前失败只清除同一待发送登记，不伪造 HTTP 次数。 */
    synchronized void terminalAttempt(String attemptId, String status) {
        status = safeTerminal(status);
        var exchange = attempts.get(attemptId);
        if (exchange != null) exchange.terminal(status);
        else if (pending != null && pending.context().attemptId().equals(attemptId)) pending = null;
        else if (!pendingOnThread.entrySet().removeIf(value -> value.getValue().context().attemptId().equals(attemptId))) {
            throw new IllegalStateException("memory_eval_terminal_correlation_rejected");
        }
    }

    /** 快照深度不可变；所有可序列化字段均是公开关联标识、枚举、哈希、时间或白名单数字。 */
    List<RequestRecord> snapshot() {
        List<Exchange> copy;
        synchronized (this) { copy = List.copyOf(exchanges); }
        return copy.stream().map(Exchange::snapshot).toList();
    }

    /** 只统计实际调用 delegate 的聊天请求，不把 audit 登记当成网络发送。 */
    synchronized int actualRequestCount() { return exchanges.size(); }

    /** 失败只用静态分类；异常消息和供应商错误正文不会进入快照。 */
    int failureCount() {
        return (int) snapshot().stream().filter(value -> value.transportFailure() != null
                || value.observationFailure() != null || "FAILED".equals(value.terminalStatus())
                || "UNRESOLVED".equals(value.terminalStatus())).count();
    }

    /** 非聊天请求完全透传；本验收的聊天端点使用固定 /chat/completions 路径。 */
    private synchronized Exchange claim(HttpRequest request) {
        if (!"POST".equals(request.method()) || request.uri().getPath() == null
                || !request.uri().getPath().replaceFirst("/$", "").endsWith("/chat/completions")) return null;
        var selected = pendingOnThread.remove(Thread.currentThread());
        if (selected == null) { selected = pending; pending = null; }
        if (selected == null) throw new IllegalStateException("memory_eval_attempt_not_registered");
        var exchange = new Exchange(exchanges.size() + 1L, selected, clock.instant(), nanos.getAsLong());
        exchanges.add(exchange);
        attempts.put(exchange.context.attemptId(), exchange);
        return exchange;
    }

    /** 原始 JSON 字节片段比较，避免重序列化掩盖 wire 字段顺序或空白变化。 */
    private void requestCompleted(Exchange exchange, byte[] body) {
        try {
            var raw = rawRequest(exchange.sequence, body);
            synchronized (this) {
                if (exchange.requestObserved(raw.metadata())) return;
                exchange.request(raw.metadata(), compare(previous, raw), compare(previousByScope.get(exchange.scope), raw));
                previous = raw;
                previousByScope.put(exchange.scope, raw);
            }
        } catch (IOException | RuntimeException invalid) { exchange.observationFailure("REQUEST_OBSERVATION_REJECTED"); }
    }

    @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        var exchange = claim(request);
        if (exchange == null) return delegate.send(request, handler);
        try { return delegate.send(observedRequest(request, exchange), observedHandler(handler, exchange)); }
        catch (IOException | RuntimeException failed) { exchange.failure("TRANSPORT_FAILED"); throw failed; }
        catch (InterruptedException interrupted) { exchange.failure("TRANSPORT_INTERRUPTED"); throw interrupted; }
    }

    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return sendObserved(request, handler, null);
    }

    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
            HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler) {
        return sendObserved(request, handler, pushHandler);
    }

    /** 返回 delegate 的同一个 Future，保留调用方的取消与异常传播语义。 */
    private <T> CompletableFuture<HttpResponse<T>> sendObserved(HttpRequest request,
            HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler) {
        var exchange = claim(request);
        if (exchange == null) return pushHandler == null ? delegate.sendAsync(request, handler)
                : delegate.sendAsync(request, handler, pushHandler);
        try {
            var observed = observedRequest(request, exchange);
            var body = observedHandler(handler, exchange);
            var result = pushHandler == null ? delegate.sendAsync(observed, body)
                    : delegate.sendAsync(observed, body, pushHandler);
            result.whenComplete((response, error) -> { if (error != null) exchange.failure("TRANSPORT_FAILED"); });
            return result;
        } catch (RuntimeException failed) { exchange.failure("TRANSPORT_FAILED"); throw failed; }
    }

    /** 只替换 publisher 为薄观察包装；URI、所有 headers、timeout 与版本直接引用原请求。 */
    private HttpRequest observedRequest(HttpRequest original, Exchange exchange) {
        return new HttpRequest() {
            @Override public Optional<BodyPublisher> bodyPublisher() {
                return original.bodyPublisher().map(publisher -> new BodyPublisher() {
                    @Override public long contentLength() { return publisher.contentLength(); }
                    @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> downstream) {
                        exchange.requestSubscribed();
                        publisher.subscribe(new Flow.Subscriber<>() {
                            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            private boolean exceeded;
                            @Override public void onSubscribe(Flow.Subscription subscription) { downstream.onSubscribe(subscription); }
                            @Override public void onNext(ByteBuffer item) {
                                if (!exceeded) {
                                    if (bytes.size() + (long) item.remaining() > MAX_OBSERVED_BYTES) {
                                        exceeded = true; bytes.reset(); exchange.observationFailure("REQUEST_OBSERVATION_LIMIT");
                                    } else copy(item, bytes);
                                }
                                downstream.onNext(item);
                            }
                            @Override public void onError(Throwable error) {
                                bytes.reset(); exchange.failure("REQUEST_BODY_FAILED"); downstream.onError(error);
                            }
                            @Override public void onComplete() {
                                if (!exceeded) requestCompleted(exchange, bytes.toByteArray());
                                bytes.reset(); downstream.onComplete();
                            }
                        });
                    }
                });
            }
            @Override public String method() { return original.method(); }
            @Override public Optional<Duration> timeout() { return original.timeout(); }
            @Override public boolean expectContinue() { return original.expectContinue(); }
            @Override public URI uri() { return original.uri(); }
            @Override public Optional<Version> version() { return original.version(); }
            @Override public HttpHeaders headers() { return original.headers(); }
        };
    }

    /** 下游仍收到原 ByteBuffer 列表与相同 demand；观察错误单独记录，不改变供应商响应。 */
    private <T> HttpResponse.BodyHandler<T> observedHandler(HttpResponse.BodyHandler<T> original, Exchange exchange) {
        return info -> {
            exchange.headers(info.statusCode());
            var subscriber = original.apply(info);
            var parser = new ResponseObserver(exchange, info.statusCode() < 400
                    && (info.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).contains("text/event-stream")
                    || exchange.streaming()));
            return new HttpResponse.BodySubscriber<>() {
                @Override public CompletionStage<T> getBody() { return subscriber.getBody(); }
                @Override public void onSubscribe(Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
                @Override public void onNext(List<ByteBuffer> items) {
                    try { for (var item : items) parser.accept(item); }
                    catch (RuntimeException invalid) { exchange.observationFailure("RESPONSE_OBSERVATION_REJECTED"); }
                    subscriber.onNext(items);
                }
                @Override public void onError(Throwable error) {
                    parser.discard(); exchange.failure("RESPONSE_BODY_FAILED"); subscriber.onError(error);
                }
                @Override public void onComplete() {
                    parser.complete(); exchange.bodyComplete(); subscriber.onComplete();
                }
            };
        };
    }

    /** SSE 按原始字节处理任意分片及 CR/LF/CRLF，多 data 行合并后才解析 JSON。 */
    private final class ResponseObserver {
        private final Exchange exchange;
        private final boolean sse;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final ByteArrayOutputStream event = new ByteArrayOutputStream();
        private long received;
        private boolean carriageReturn;
        private boolean exceeded;
        private boolean hasData;

        ResponseObserver(Exchange exchange, boolean sse) { this.exchange = exchange; this.sse = sse; }
        void accept(ByteBuffer original) {
            var bytes = original.duplicate();
            if (bytes.hasRemaining()) exchange.bodyStarted();
            received += bytes.remaining();
            if (received > MAX_OBSERVED_BYTES) {
                exceeded = true; discard(); exchange.observationFailure("RESPONSE_OBSERVATION_LIMIT"); return;
            }
            if (exceeded) return;
            if (!sse) { copy(bytes, event); return; }
            while (bytes.hasRemaining()) {
                int value = Byte.toUnsignedInt(bytes.get());
                if (carriageReturn && value == '\n') { carriageReturn = false; continue; }
                carriageReturn = false;
                if (value == '\r' || value == '\n') { finishLine(); carriageReturn = value == '\r'; }
                else line.write(value);
            }
        }
        private void finishLine() {
            var bytes = line.toByteArray(); line.reset();
            if (bytes.length == 0) { dispatch(); return; }
            var text = new String(bytes, StandardCharsets.UTF_8);
            if (!text.startsWith("data:")) return;
            String data = text.substring(5);
            if (data.startsWith(" ")) data = data.substring(1);
            if (hasData) event.write('\n');
            event.writeBytes(data.getBytes(StandardCharsets.UTF_8)); hasData = true;
        }
        private void dispatch() {
            if (!hasData) return;
            var bytes = event.toByteArray(); event.reset(); hasData = false;
            if ("[DONE]".equals(new String(bytes, StandardCharsets.UTF_8).trim())) { exchange.completed(); return; }
            observeJson(bytes, true);
        }
        private void observeJson(byte[] bytes, boolean delta) {
            try {
                var root = JSON.readTree(bytes);
                if (root == null || !root.isObject()) throw new IOException("invalid observation");
                exchange.usage(root);
                exchange.content(root, delta);
                if (delta) {
                    var choices = root.path("choices");
                    if (choices.isArray()) for (var choice : choices) {
                        var value = choice.path("delta");
                        if (nonempty(value.get("content"))) exchange.delta("CONTENT");
                        else if (nonempty(value.get("reasoning_content")) || nonempty(value.get("reasoning"))) exchange.delta("REASONING");
                        else if (toolDelta(value)) exchange.delta("TOOL");
                    }
                }
            } catch (IOException | RuntimeException invalid) { exchange.observationFailure("RESPONSE_OBSERVATION_REJECTED"); }
        }
        void complete() {
            if (!exceeded) {
                if (sse) { if (line.size() > 0) finishLine(); dispatch(); }
                else if (event.size() > 0) observeJson(event.toByteArray(), false);
                exchange.completed();
            }
            discard();
        }
        void discard() { line.reset(); event.reset(); hasData = false; }
    }

    /** 唯一 exchange 的状态在各回调线程之间同步；正文不属于可序列化状态。 */
    private final class Exchange {
        final long sequence;
        final AttemptContext context;
        final String scope;
        final boolean threadBound;
        final Instant startedAt;
        final long start;
        final Map<String, Long> usage = unknownMap(TOKEN_PATHS);
        final Map<String, Boolean> reported = unknownMap(REPORTED_PATHS);
        final Map<String, BigDecimal> costs = unknownMap(COST_PATHS);
        final StringBuilder content = new StringBuilder();
        WireMetadata wire;
        PrefixComparison prefix;
        PrefixComparison scopePrefix;
        Double headersMillis, bodyMillis, ttftMillis, latencyMillis, bodyCompleteMillis, terminalMillis;
        Integer httpStatus;
        int requestSubscriptions;
        String firstDeltaKind, terminalStatus, transportFailure, observationFailure;
        Exchange(long sequence, Pending pending, Instant at, long start) {
            this.sequence = sequence; this.context = pending.context(); this.scope = pending.scope(); this.threadBound = pending.threadBound();
            this.startedAt = at; this.start = start;
        }
        synchronized boolean hasTerminal() { return terminalStatus != null; }
        synchronized void requestSubscribed() { requestSubscriptions++; }
        synchronized boolean requestObserved(WireMetadata value) {
            if (wire == null) return false;
            if (!wire.requestSha256().equals(value.requestSha256())) observationFailure("REQUEST_REPLAY_CHANGED");
            return true;
        }
        synchronized void request(WireMetadata value, PrefixComparison prefix, PrefixComparison scopePrefix) {
            this.wire = value; this.prefix = prefix; this.scopePrefix = scopePrefix;
        }
        synchronized boolean streaming() { return wire != null && Boolean.TRUE.equals(wire.stream()); }
        synchronized void headers(int status) { httpStatus = status; if (headersMillis == null) headersMillis = elapsed(); }
        synchronized void bodyStarted() { if (bodyMillis == null) bodyMillis = elapsed(); }
        synchronized void delta(String kind) { if (ttftMillis == null) { ttftMillis = elapsed(); firstDeltaKind = kind; } }
        synchronized void completed() { if (latencyMillis == null) latencyMillis = elapsed(); }
        synchronized void bodyComplete() { if (bodyCompleteMillis == null) bodyCompleteMillis = elapsed(); }
        synchronized void terminal(String status) {
            if (terminalStatus != null && !terminalStatus.equals(status)) throw new IllegalStateException("memory_eval_terminal_drift");
            terminalStatus = status; if (terminalMillis == null) terminalMillis = elapsed();
        }
        synchronized void failure(String code) { if (transportFailure == null) transportFailure = code; }
        synchronized void observationFailure(String code) { if (observationFailure == null) observationFailure = code; }
        private double elapsed() { return Math.max(0L, nanos.getAsLong() - start) / 1_000_000.0; }
        synchronized void usage(JsonNode root) {
            for (var path : TOKEN_PATHS) {
                var value = at(root, path);
                if (value.isIntegralNumber() && value.canConvertToLong()) usage.put(path, value.longValue());
            }
            for (var path : REPORTED_PATHS) { var value = at(root, path); if (value.isBoolean()) reported.put(path, value.booleanValue()); }
            for (var path : COST_PATHS) { var value = at(root, path); if (value.isNumber()) costs.put(path, value.decimalValue()); }
        }
        /** 原始候选来自实际供应商响应；保留解析前正文以识别模型格式和过滤问题。 */
        synchronized void content(JsonNode root, boolean delta) {
            var choices = root.path("choices");
            if (choices.isArray()) for (var choice : choices) {
                var value = choice.path(delta ? "delta" : "message").path("content");
                if (value.isTextual() && content.length() + value.textValue().length() <= MAX_OBSERVED_BYTES)
                    content.append(value.textValue());
            }
        }
        synchronized RequestRecord snapshot() {
            return new RequestRecord(sequence, context, scope, startedAt, headersMillis, bodyMillis, ttftMillis,
                    latencyMillis, bodyCompleteMillis, terminalMillis, httpStatus, requestSubscriptions, wire == null ? null : wire.stream(),
                    firstDeltaKind, terminalStatus, transportFailure, observationFailure, wire, prefix, scopePrefix,
                    usage, new ProviderReported(reported, costs), content.toString());
        }
    }

    /** 字节片段只留在私有内存；输出的逐条摘要不含 content、reasoning 或 tool arguments。 */
    private static RawRequest rawRequest(long sequence, byte[] body) throws IOException {
        var fields = objectFields(body);
        byte[] messageBytes = fields.get("messages");
        if (messageBytes == null) throw new IOException("messages missing");
        var messages = arrayItems(messageBytes);
        var digests = new ArrayList<MessageDigestRecord>();
        String system = null;
        for (int index = 0; index < messages.size(); index++) {
            var message = JSON.readTree(messages.get(index));
            String role = knownText(message.path("role"), Set.of("system", "developer", "user", "assistant", "tool", "function"));
            var digest = sha256(messages.get(index));
            if (system == null && "system".equals(role)) system = digest;
            digests.add(new MessageDigestRecord(index, role, digest, messages.get(index).length));
        }
        var tools = fields.get("tools");
        var toolNames = new ArrayList<String>();
        if (tools != null) {
            var list = JSON.readTree(tools);
            if (list.isArray()) for (var tool : list) {
                var name = tool.path("function").path("name");
                if (name.isTextual() && name.textValue().matches("[A-Za-z_][A-Za-z0-9_-]{0,127}")) toolNames.add(name.textValue());
            }
        }
        var generation = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) if (!Set.of("messages", "tools").contains(field.getKey())) {
            generation.writeBytes(field.getKey().getBytes(StandardCharsets.UTF_8)); generation.write(0);
            generation.writeBytes(field.getValue()); generation.write(0);
        }
        var choice = fields.containsKey("tool_choice") ? JSON.readTree(fields.get("tool_choice")) : null;
        String toolChoice = knownText(choice, Set.of("none", "auto", "required"));
        if (choice != null && choice.isObject()) toolChoice = "named_function";
        var format = fields.containsKey("response_format") ? JSON.readTree(fields.get("response_format")) : null;
        String responseFormat = format == null ? null : knownText(format.path("type"), Set.of("text", "json_object", "json_schema"));
        var streamNode = fields.containsKey("stream") ? JSON.readTree(fields.get("stream")) : null;
        Boolean stream = streamNode != null && streamNode.isBoolean() ? streamNode.booleanValue() : null;
        var metadata = new WireMetadata(sha256(body), system, tools == null ? null : sha256(tools), sha256(generation.toByteArray()),
                sha256(messageBytes), body.length, messages.size(), digests, toolNames, toolChoice, responseFormat, stream);
        return new RawRequest(sequence, metadata, messageBytes, messages);
    }

    /** Jackson token offset 截取实际 UTF-8 字节，容器结束位置含其右括号。 */
    private static Map<String, byte[]> objectFields(byte[] bytes) throws IOException {
        var fields = new LinkedHashMap<String, byte[]>();
        try (var parser = JSON.getFactory().createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("object expected");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IOException("field expected");
                String name = parser.currentName();
                if (parser.nextToken() == null) throw new IOException("value expected");
                if (fields.put(name, tokenBytes(parser, bytes)) != null) throw new IOException("duplicate field");
            }
            if (parser.nextToken() != null) throw new IOException("trailing payload");
        }
        return fields;
    }

    private static List<byte[]> arrayItems(byte[] bytes) throws IOException {
        var values = new ArrayList<byte[]>();
        try (var parser = JSON.getFactory().createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) throw new IOException("array expected");
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() != JsonToken.START_OBJECT) throw new IOException("message expected");
                values.add(tokenBytes(parser, bytes));
            }
        }
        return List.copyOf(values);
    }

    private static byte[] tokenBytes(JsonParser parser, byte[] bytes) throws IOException {
        int start = Math.toIntExact(parser.currentTokenLocation().getByteOffset());
        // String token 可能仍是 lazy，先读 text 才能保证当前位置已越过引号。
        if (parser.currentToken() == JsonToken.VALUE_STRING) parser.getText();
        parser.skipChildren();
        int end = Math.toIntExact(parser.currentLocation().getByteOffset());
        return Arrays.copyOfRange(bytes, start, end);
    }

    /** 完整 messages prefix 与字节 prefix 分别记录；绝不把某个 hash 相同当成全提示词已复用。 */
    private static PrefixComparison compare(RawRequest old, RawRequest current) {
        if (old == null) return new PrefixComparison(null, 0, 0, null, null, "FIRST_REQUEST", null, null, null);
        int common = 0;
        while (common < Math.min(old.messages().size(), current.messages().size())
                && Arrays.equals(old.messages().get(common), current.messages().get(common))) common++;
        int commonBytes = 0;
        while (commonBytes < Math.min(old.messageBytes().length, current.messageBytes().length)
                && old.messageBytes()[commonBytes] == current.messageBytes()[commonBytes]) commonBytes++;
        boolean prefix = common == old.messages().size() && current.messages().size() >= old.messages().size();
        String boundary = prefix ? current.messages().size() == old.messages().size() ? "UNCHANGED" : "APPEND_ONLY"
                : current.messages().size() < old.messages().size() && common == current.messages().size() ? "TRUNCATED" : "PREFIX_CHANGED";
        return new PrefixComparison(old.sequence(), common, commonBytes, prefix,
                prefix ? current.messages().size() - old.messages().size() : null, boundary,
                equalKnown(old.metadata().systemSha256(), current.metadata().systemSha256()),
                equalKnown(old.metadata().toolsSha256(), current.metadata().toolsSha256()),
                equalKnown(old.metadata().generationSha256(), current.metadata().generationSha256()));
    }

    private static Boolean equalKnown(String old, String current) { return old == null || current == null ? null : old.equals(current); }
    private static JsonNode at(JsonNode root, String path) { for (var part : path.split("\\.")) root = root.path(part); return root; }
    private static boolean nonempty(JsonNode value) { return value != null && value.isTextual() && !value.textValue().isEmpty(); }
    private static boolean toolDelta(JsonNode value) {
        if (nonempty(value.path("function_call").get("name")) || nonempty(value.path("function_call").get("arguments"))) return true;
        var tools = value.path("tool_calls");
        if (tools.isArray()) for (var tool : tools) if (nonempty(tool.get("id"))
                || nonempty(tool.path("function").get("name")) || nonempty(tool.path("function").get("arguments"))) return true;
        return false;
    }
    private static String knownText(JsonNode value, Set<String> known) {
        return value != null && value.isTextual() && known.contains(value.textValue()) ? value.textValue() : null;
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("sha256_unavailable"); }
    }
    private static void copy(ByteBuffer original, ByteArrayOutputStream out) {
        var bytes = original.duplicate(); var copy = new byte[bytes.remaining()]; bytes.get(copy); out.writeBytes(copy);
    }
    private static String safeTag(String text) {
        if (text == null || !text.matches("[A-Za-z0-9_.:/-]{1,256}")) throw new IllegalArgumentException("unsafe_recording_tag");
        return text;
    }
    private static String safeTerminal(String value) {
        if (value == null || !Set.of("SUCCEEDED", "FAILED", "UNRESOLVED").contains(value)) throw new IllegalArgumentException("unknown_recording_terminal");
        return value;
    }
    private static <T> Map<String, T> unknownMap(List<String> paths) {
        var result = new LinkedHashMap<String, T>(); paths.forEach(path -> result.put(path, null)); return result;
    }
    private static <T> Map<String, T> immutableMap(Map<String, T> values) { return Collections.unmodifiableMap(new LinkedHashMap<>(values)); }

    record AttemptContext(String attemptId, String runId, String category, String callKind, String responseFormat, String toolChoice) {
        AttemptContext {
            attemptId = safeTag(attemptId); runId = safeTag(runId); category = safeTag(category);
            callKind = safeTag(callKind); responseFormat = safeTag(responseFormat); toolChoice = safeTag(toolChoice);
        }
    }
    record RequestRecord(long requestSequence, AttemptContext attempt, String scope, Instant startedAt,
            Double headersMillis, Double bodyMillis, Double ttftMillis, Double latencyMillis, Double bodyCompleteMillis,
            Double terminalMillis, Integer httpStatus, int requestSubscriptions, Boolean stream, String firstDeltaKind, String terminalStatus,
            String transportFailure, String observationFailure, WireMetadata wire, PrefixComparison prefixComparison,
            PrefixComparison scopePrefixComparison, Map<String, Long> rawUsage, ProviderReported providerReported,
            String responseContent) {
        RequestRecord { rawUsage = immutableMap(rawUsage); }
    }
    record WireMetadata(String requestSha256, String systemSha256, String toolsSha256, String generationSha256,
            String messagesSha256, long requestBytes, int messageCount, List<MessageDigestRecord> messages,
            List<String> toolNames, String toolChoice, String responseFormat, Boolean stream) {
        WireMetadata { messages = List.copyOf(messages); toolNames = List.copyOf(toolNames); }
    }
    record MessageDigestRecord(int index, String role, String sha256, long bytes) { }
    record PrefixComparison(Long previousRequestSequence, int commonMessages, long commonBytes,
            Boolean previousMessagesArePrefix, Integer appendedMessages, String boundary,
            Boolean systemUnchanged, Boolean toolsUnchanged, Boolean generationUnchanged) { }
    record ProviderReported(Map<String, Boolean> flags, Map<String, BigDecimal> costs) {
        ProviderReported { flags = immutableMap(flags); costs = immutableMap(costs); }
    }
    private record Pending(AttemptContext context, String scope, boolean threadBound) { }
    private record RawRequest(long sequence, WireMetadata metadata, byte[] messageBytes, List<byte[]> messages) { }

    // 配置与生命周期全部委托；记录器不引入自己的 timeout、连接池、重试或关闭策略。
    @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
    @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
    @Override public Redirect followRedirects() { return delegate.followRedirects(); }
    @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
    @Override public SSLContext sslContext() { return delegate.sslContext(); }
    @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
    @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
    @Override public Version version() { return delegate.version(); }
    @Override public Optional<Executor> executor() { return delegate.executor(); }
    @Override public WebSocket.Builder newWebSocketBuilder() { return delegate.newWebSocketBuilder(); }
    @Override public void shutdown() { delegate.shutdown(); }
    @Override public void shutdownNow() { delegate.shutdownNow(); }
    @Override public boolean isTerminated() { return delegate.isTerminated(); }
    @Override public boolean awaitTermination(Duration duration) throws InterruptedException { return delegate.awaitTermination(duration); }
    @Override public void close() { delegate.close(); }
}
