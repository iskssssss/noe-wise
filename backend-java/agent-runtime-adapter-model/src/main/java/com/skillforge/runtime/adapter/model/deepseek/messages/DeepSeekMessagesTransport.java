package com.skillforge.runtime.adapter.model.deepseek.messages;

import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.adapter.model.transport.ProviderHttpExchange;

import static com.skillforge.runtime.adapter.model.deepseek.messages.DeepSeekMessagesCodec.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.*;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一次派发的 Messages HTTP/SSE 传输；任何已发送后的不完整响应保持 UNKNOWN，不自动重试。 */
public final class DeepSeekMessagesTransport implements ModelProviderTransport {
    private final HttpClient http;
    private final Clock clock;
    private final int maximumBytes;

    public DeepSeekMessagesTransport(HttpClient http, Clock clock, int maximumBytes) {
        this.http = Objects.requireNonNull(http);
        this.clock = Objects.requireNonNull(clock);
        if (maximumBytes <= 0 || http.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("model_transport_configuration_invalid");
        this.maximumBytes = maximumBytes;
    }

    @Override public List<OpenAiModelDiscoveryClient.DiscoveredModel> discover(DiscoveryRequest request) {
        // 目录发现仍由供应商的独立 /models 协议拥有，不用 Messages 重解释发现请求。
        throw new ModelProvider.ProviderOperationException("model_messages_discovery_not_supported");
    }

    private HttpRequest prepare(InvocationRequest request, boolean stream) throws IOException {
        var profile = request.profile();
        if (!DeepSeekMessagesProjection.supports("deepseek", profile)) throw rejected("model_provider_profile_invalid");
        if (request.cancelled().getAsBoolean()) throw rejected("model_pre_dispatch_cancelled");
        String base = request.endpoint().replaceAll("/+$", "");
        URI endpoint = URI.create(base + "/anthropic/v1/messages");
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())))
            throw rejected("model_endpoint_invalid");
        return HttpRequest.newBuilder(endpoint).timeout(request.timeout()).header("Content-Type", "application/json")
                .header("x-api-key", request.credential()).header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofByteArray(encode(request, stream))).build();
    }

    @Override public InvocationResult generate(InvocationRequest request, ChatCompletionsPolicy policy) {
        HttpRequest prepared;
        try { prepared = prepare(request, false); }
        catch (IllegalArgumentException invalid) { return new InvocationResult.Rejected(localReason(invalid)); }
        catch (IOException invalid) { return new InvocationResult.Rejected("model_pre_dispatch_rejected"); }
        ModelUsage usage = emptyUsage();
        try (var exchange = new ProviderHttpExchange(http, clock, request, prepared)) {
            var response = exchange.response();
            try (var body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) return failure(response, body, policy);
                JsonNode value = parse(readBounded(body));
                if (value.has("usage")) usage = usage(value.get("usage"));
                return decode(request, value);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new InvocationResult.OutcomeUnknown("model_outcome_unknown", usage);
        } catch (ProviderHttpExchange.PreDispatchRejected denied) { return new InvocationResult.Rejected("model_pre_dispatch_rejected"); }
        catch (Exception invalid) { return new InvocationResult.OutcomeUnknown("model_outcome_unknown", usage); }
    }

    @Override public Flow.Publisher<StreamEvent> stream(InvocationRequest request, ChatCompletionsPolicy policy) {
        var subscribed = new AtomicBoolean();
        return subscriber -> {
            Objects.requireNonNull(subscriber);
            if (!subscribed.compareAndSet(false, true)) {
                subscriber.onError(new IllegalStateException("model_stream_already_subscribed")); return;
            }
            var subscription = new Subscription(request, policy, subscriber);
            subscriber.onSubscribe(subscription);
            Thread.ofPlatform().daemon(true).name("deepseek-messages-stream").start(subscription::run);
        };
    }

    private final class Subscription implements Flow.Subscription {
        private final InvocationRequest request;
        private final Flow.Subscriber<? super StreamEvent> subscriber;
        private final ChatCompletionsPolicy policy;
        private final Instant deadline;
        private long demand;
        private boolean cancelled;
        private boolean terminated;
        private ProviderHttpExchange exchange;

        Subscription(InvocationRequest request, ChatCompletionsPolicy policy, Flow.Subscriber<? super StreamEvent> subscriber) {
            this.request = request; this.policy = policy; this.subscriber = subscriber; this.deadline = clock.instant().plus(request.timeout());
        }
        @Override public synchronized void request(long n) {
            if (cancelled || terminated) return;
            if (n <= 0) {
                cancel(); subscriber.onError(new IllegalArgumentException("model_stream_invalid_demand")); return;
            }
            demand = demand > Long.MAX_VALUE - n ? Long.MAX_VALUE : demand + n;
            notifyAll();
        }
        @Override public synchronized void cancel() {
            cancelled = true;
            if (exchange != null) exchange.close();
            notifyAll();
        }
        private synchronized void emit(StreamEvent event) throws InterruptedException {
            while (demand == 0 && !cancelled) {
                if (request.cancelled().getAsBoolean() || !clock.instant().isBefore(deadline)) {
                    cancel(); break;
                }
                wait(20);
            }
            if (cancelled) throw new InterruptedException("model_stream_cancelled");
            if (demand != Long.MAX_VALUE) demand--;
            subscriber.onNext(event);
        }
        private void run() {
            StreamEvent terminal;
            HttpRequest prepared;
            try { prepared = prepare(request, true); }
            catch (IllegalArgumentException invalid) { finish(new StreamEvent.Rejected(localReason(invalid))); return; }
            catch (IOException invalid) { finish(new StreamEvent.Rejected("model_pre_dispatch_rejected")); return; }
            try {
                synchronized (this) {
                    if (cancelled) return;
                    exchange = new ProviderHttpExchange(http, clock, request, prepared);
                }
                try (var running = exchange) {
                    var response = running.response();
                    try (var body = response.body()) {
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            var failure = failure(response, body, policy);
                            terminal = failure instanceof InvocationResult.Rejected reject
                                    ? new StreamEvent.Rejected(reject.reasonCode(), reject.retryAt())
                                    : new StreamEvent.OutcomeUnknown("model_outcome_unknown");
                        } else terminal = events(body);
                    }
                }
            } catch (ProviderHttpExchange.PreDispatchRejected denied) { terminal = new StreamEvent.Rejected("model_pre_dispatch_rejected"); }
            catch (Exception invalid) { terminal = new StreamEvent.OutcomeUnknown("model_outcome_unknown"); }
            finish(terminal);
        }
        private void finish(StreamEvent terminal) {
            try {
                emit(terminal);
                synchronized (this) { if (cancelled) return; terminated = true; }
                subscriber.onComplete();
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (RuntimeException failure) { cancel(); }
        }

        private StreamEvent events(InputStream input) throws IOException, InterruptedException {
            var reader = new SseLines(input);
            var data = new StringBuilder();
            var blocks = new TreeMap<Integer, Block>();
            ObjectNode rawUsage = JSON.createObjectNode();
            boolean started = false;
            boolean settled = false;
            String finish = null;
            long sequence = 0;
            String line;
            while ((line = reader.line()) != null) {
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5).stripLeading()); continue;
                }
                if (!line.isEmpty() || data.isEmpty()) continue;
                JsonNode event = parse(data.toString()); data.setLength(0);
                String type = required(event, "type");
                if ("ping".equals(type)) continue;
                if ("error".equals(type)) throw invalid();
                if ("message_start".equals(type)) {
                    var message = event.path("message");
                    if (started || !"message".equals(required(message, "type"))
                            || !"assistant".equals(required(message, "role"))
                            || !request.modelId().equals(required(message, "model"))
                            || !message.path("content").isArray() || !message.path("content").isEmpty()) throw invalid();
                    mergeUsage(rawUsage, message.get("usage")); started = true;
                    emit(new StreamEvent.UsageDelta(++sequence, usage(rawUsage))); continue;
                }
                if (!started) throw invalid();
                switch (type) {
                    case "content_block_start" -> {
                        int index = index(event);
                        if (settled || index != blocks.size()) throw invalid();
                        var block = new Block(event.path("content_block"), (int) blocks.values().stream().filter(value -> value.tool).count()); blocks.put(index, block);
                        if (block.tool) emit(new StreamEvent.ToolCallDelta(++sequence, block.toolIndex, block.id, block.name, ""));
                        else if (!block.text.isEmpty()) {
                            if (block.thinking) emit(new StreamEvent.ReasoningDelta(++sequence, block.text.toString()));
                            else emit(new StreamEvent.ContentDelta(++sequence, block.text.toString()));
                        }
                    }
                    case "content_block_delta" -> {
                        var block = blocks.get(index(event));
                        if (settled || block == null || block.closed) throw invalid();
                        var delta = event.path("delta");
                        if (block.tool && "input_json_delta".equals(required(delta, "type"))) {
                            String part = required(delta, "partial_json"); block.json.append(part);
                            emit(new StreamEvent.ToolCallDelta(++sequence, block.toolIndex, block.id, block.name, part));
                        } else if (block.thinking && "thinking_delta".equals(required(delta, "type"))) {
                            String part = required(delta, "thinking"); block.text.append(part);
                            emit(new StreamEvent.ReasoningDelta(++sequence, part));
                        } else if (block.thinking && "signature_delta".equals(required(delta, "type"))) {
                            block.signature.append(required(delta, "signature")); block.hasSignature = true;
                        } else if (!block.tool && !block.thinking && "text_delta".equals(required(delta, "type"))) {
                            String part = required(delta, "text"); block.text.append(part);
                            emit(new StreamEvent.ContentDelta(++sequence, part));
                        } else throw invalid();
                    }
                    case "content_block_stop" -> {
                        var block = blocks.get(index(event));
                        if (block == null || block.closed || settled) throw invalid(); block.closed = true;
                    }
                    case "message_delta" -> {
                        if (settled || blocks.values().stream().anyMatch(block -> !block.closed)) throw invalid();
                        finish = DeepSeekMessagesCodec.finish(required(event.path("delta"), "stop_reason")); settled = true;
                        mergeUsage(rawUsage, event.get("usage"));
                        emit(new StreamEvent.UsageDelta(++sequence, usage(rawUsage)));
                    }
                    case "message_stop" -> {
                        if (!settled || finish == null) throw invalid();
                        var text = new StringBuilder(); var thoughts = new StringBuilder();
                        var calls = new ArrayList<ToolCall>(); var ids = new HashSet<String>();
                        var content = JSON.createArrayNode(); boolean hasThinking = false;
                        for (var block : blocks.values()) {
                            var value = block.original.deepCopy();
                            if (block.tool) {
                                if (!ids.add(block.id)) throw invalid();
                                String args = block.json.isEmpty() ? block.initial : block.json.toString();
                                // 参数字符串的 JSON/schema 合法性归 Native decoder；已完整结束的无效调用不是网络 UNKNOWN。
                                calls.add(new ToolCall(block.id, block.name, args));
                                try { value.set("input", parse(args)); }
                                catch (IOException malformed) {
                                    // 原调用参数保持原样交给Native决策拒绝；私有回放状态不能伪造为合法对象。
                                    value.put("input", args);
                                }
                            } else if (block.thinking) {
                                if (!block.hasSignature) throw invalid();
                                hasThinking = true; thoughts.append(block.text);
                                value.put("thinking", block.text.toString());value.put("signature", block.signature.toString());
                            } else { text.append(block.text);value.put("text", block.text.toString()); }
                            content.add(value);
                        }
                        return new StreamEvent.Completed(new InvocationResult.Completed(text.isEmpty() ? null : text.toString(),
                                calls, request.modelId(), usage(rawUsage), finish, hasThinking ? thoughts.toString() : null,
                                content.isEmpty() ? Optional.empty() : Optional.of(protocolState(request, content))));
                    }
                    default -> throw invalid();
                }
            }
            throw invalid();
        }
    }

    private static final class Block {
        private final boolean tool;
        private final boolean thinking;
        private final ObjectNode original;
        private final StringBuilder signature = new StringBuilder();
        private boolean hasSignature;
        private final String id;
        private final String name;
        private final String initial;
        private final int toolIndex;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder json = new StringBuilder();
        private boolean closed;
        Block(JsonNode value, int toolIndex) throws IOException {
            this.toolIndex = toolIndex;
            if (!value.isObject()) throw invalid();original = ((ObjectNode) value).deepCopy();
            String type = required(value, "type"); tool = "tool_use".equals(type);thinking = "thinking".equals(type);
            if (tool) {
                id = required(value, "id"); name = required(value, "name");
                if (id.isBlank() || name.isBlank() || !value.path("input").isObject()) throw invalid();
                initial = JSON.writeValueAsString(value.get("input"));
            } else {
                if (!"text".equals(type) && !thinking) throw invalid();
                text.append(required(value, thinking ? "thinking" : "text"));
                if (thinking && value.has("signature")) { signature.append(required(value, "signature"));hasSignature = true; }
                id = null; name = null; initial = null;
            }
        }
    }

    private static void mergeUsage(ObjectNode target, JsonNode value) throws IOException {
        if (value == null || !value.isObject()) throw invalid();
        for (String field : List.of("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens"))
            if (value.has(field)) { tokens(value, field); target.set(field, value.get(field)); }
    }
    private static int index(JsonNode event) throws IOException {
        var value = event.path("index");
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw invalid();
        return value.intValue();
    }
    private final class SseLines {
        private final InputStream input;
        private long bytes;
        private boolean skipLf;
        SseLines(InputStream input) { this.input = new BufferedInputStream(input); }
        String line() throws IOException {
            var result = new ByteArrayOutputStream(); int c;
            while ((c = input.read()) != -1) {
                if (++bytes > maximumBytes) throw invalid();
                if (skipLf) { skipLf = false; if (c == '\n') continue; }
                if (c == '\r' || c == '\n') { skipLf = c == '\r'; return utf8(result.toByteArray()); }
                result.write(c);
            }
            return result.size() == 0 ? null : utf8(result.toByteArray());
        }
    }
    private byte[] readBounded(InputStream body) throws IOException {
        var output = new ByteArrayOutputStream(); var buffer = new byte[8192]; int count;
        while ((count = body.read(buffer)) != -1) {
            if ((long) output.size() + count > maximumBytes) throw invalid(); output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private InvocationResult failure(HttpResponse<?> response, InputStream body, ChatCompletionsPolicy policy) {
        int status = response.statusCode();
        if (status >= 500 || status == 408) return new InvocationResult.OutcomeUnknown("model_outcome_unknown");
        String reason = status == 429 ? "model_rate_limited" : status == 401 || status == 403
                ? "model_authentication_rejected" : "model_http_rejected";
        if (status == 400) {
            try {
                var error = parse(readBounded(body)).path("error");
                if ("context_length_exceeded".equals(error.path("code").asText())) reason = "model_context_overflow";
                else if (policy.contentRejectionPolicy().rejects(error.path("code").asText(), error.path("type").asText(),
                        error.path("message").asText())) reason = "model_content_rejected";
            } catch (IOException ignored) { }
        }
        Optional<Instant> retryAt = Optional.empty();
        if (status == 429) {
            String value = response.headers().firstValue("Retry-After").orElse("");
            try {
                retryAt = Optional.of(value.matches("[0-9]{1,10}") ? clock.instant().plusSeconds(Math.max(1, Long.parseLong(value)))
                        : java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            } catch (RuntimeException invalid) { retryAt = Optional.of(clock.instant().plusSeconds(1)); }
        }
        return new InvocationResult.Rejected(reason, retryAt);
    }
    private static String localReason(IllegalArgumentException invalid) {
        String code = invalid.getMessage();
        return code != null && code.matches("model_[a-z_]+") ? code : "model_pre_dispatch_rejected";
    }
    private static ModelUsage emptyUsage() { return new ModelUsage(OptionalLong.empty(), OptionalLong.empty()); }
    private static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
}
