package com.skillforge.runtime.adapter.model.openai.responses;

import static com.skillforge.runtime.adapter.model.openai.responses.OpenAiResponsesCodec.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.*;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.adapter.model.transport.SdkModelCall;
import com.skillforge.runtime.adapter.model.transport.SdkHttpResources;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIServiceException;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Responses SDK 传输；平台保留原生状态、发送治理和单次 Attempt。 */
public final class OpenAiResponsesTransport implements ModelProviderTransport, AutoCloseable {
    private final SdkHttpResources resources;
    private final boolean ownsResources;
    private final HttpClient http;
    private final Clock clock;
    private final int maximumBytes;

    public OpenAiResponsesTransport(HttpClient http, Clock clock, int maximumBytes) {
        this(http, clock, maximumBytes, new SdkHttpResources(), true);
    }

    public OpenAiResponsesTransport(HttpClient http, Clock clock, int maximumBytes, SdkHttpResources resources) {
        this(http, clock, maximumBytes, resources, false);
    }

    private OpenAiResponsesTransport(HttpClient http, Clock clock, int maximumBytes, SdkHttpResources resources, boolean ownsResources) {
        this.ownsResources = ownsResources;
        this.resources = Objects.requireNonNull(resources);
        this.http = Objects.requireNonNull(http); this.clock = Objects.requireNonNull(clock);
        if (maximumBytes <= 0 || http.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("model_transport_configuration_invalid");
        this.maximumBytes = maximumBytes;
    }

    @Override public void close() { if (ownsResources) resources.close(); }

    @Override public List<OpenAiModelDiscoveryClient.DiscoveredModel> discover(DiscoveryRequest request) {
        throw new ProviderOperationException("model_responses_discovery_not_supported");
    }

    private ResponseCreateParams prepare(InvocationRequest request, boolean stream) throws IOException {
        var profile = request.profile();
        if (profile.protocol() != ModelProviderCatalog.ModelProtocol.OPENAI_RESPONSES
                || profile.authenticationScheme() != ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY
                || !DIALECT.equals(profile.dialectVersion())) throw rejected("model_provider_profile_invalid");
        if (request.cancelled().getAsBoolean()) throw rejected("model_pre_dispatch_cancelled");
        URI endpoint = URI.create(request.endpoint().replaceAll("/+$", "") + "/responses");
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())))
            throw rejected("model_endpoint_invalid");
        return parameters(request, stream);
    }

    @Override public InvocationResult generate(InvocationRequest request, ChatCompletionsPolicy policy) {
        ResponseCreateParams prepared;
        try { prepared = prepare(request, false); }
        catch (IllegalArgumentException invalid) { return new InvocationResult.Rejected(localReason(invalid)); }
        catch (IOException invalid) { return new InvocationResult.Rejected("model_pre_dispatch_rejected"); }
        ModelUsage usage = emptyUsage();
        var call = new SdkModelCall(request, clock, maximumBytes, http, resources);
        try (call) {
            var raw = JSON.valueToTree(call.client().responses().create(prepared));
            requireIdentity(request, raw);
            if (raw.hasNonNull("usage")) usage = usage(raw.get("usage"));
            return decode(raw, request.modelId(), usage);
        } catch (OpenAIServiceException failure) {
            return call.preDispatchRejection().<InvocationResult>map(InvocationResult.Rejected::new)
                    .orElseGet(() -> failure(failure, call.errorBody(), policy));
        } catch (Exception invalid) {
            return call.preDispatchRejection().<InvocationResult>map(InvocationResult.Rejected::new)
                    .orElse(new InvocationResult.OutcomeUnknown("model_outcome_unknown", usage));
        }
    }

    @Override public Flow.Publisher<StreamEvent> stream(InvocationRequest request, ChatCompletionsPolicy policy) {
        var subscribed = new AtomicBoolean();
        return subscriber -> {
            Objects.requireNonNull(subscriber);
            if (!subscribed.compareAndSet(false, true)) { subscriber.onError(new IllegalStateException("model_stream_already_subscribed")); return; }
            var subscription = new Subscription(request, policy, subscriber);
            subscriber.onSubscribe(subscription);
            Thread.ofPlatform().daemon(true).name("responses-stream").start(subscription::run);
        };
    }

    private final class Subscription implements Flow.Subscription {
        private final InvocationRequest request;
        private final ChatCompletionsPolicy policy;
        private final Flow.Subscriber<? super StreamEvent> subscriber;
        private final Instant deadline;
        private long demand;
        private boolean cancelled;
        private boolean terminated;
        private boolean responseOpened;
        private SdkModelCall exchange;
        Subscription(InvocationRequest request, ChatCompletionsPolicy policy, Flow.Subscriber<? super StreamEvent> subscriber) {
            this.request = request; this.policy = policy; this.subscriber = subscriber; deadline = clock.instant().plus(request.timeout());
        }
        @Override public synchronized void request(long n) {
            if (cancelled || terminated) return;
            if (n <= 0) { cancel(); subscriber.onError(new IllegalArgumentException("model_stream_invalid_demand")); return; }
            demand = demand > Long.MAX_VALUE - n ? Long.MAX_VALUE : demand + n; notifyAll();
        }
        @Override public synchronized void cancel() { cancelled = true; if (exchange != null) exchange.close(); notifyAll(); }
        private synchronized void emit(StreamEvent event) throws InterruptedException {
            while (demand == 0 && !cancelled) {
                if (request.cancelled().getAsBoolean() || !clock.instant().isBefore(deadline)) { cancel(); break; }
                wait(20);
            }
            if (cancelled) throw new InterruptedException("model_stream_cancelled");
            if (demand != Long.MAX_VALUE) demand--;
            subscriber.onNext(event);
        }
        private void finish(StreamEvent event) {
            try {
                emit(event);
                synchronized (this) { if (cancelled) return; terminated = true; }
                subscriber.onComplete();
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (RuntimeException failed) { cancel(); }
        }
        private void run() {
            ResponseCreateParams prepared;
            try { prepared = prepare(request, true); }
            catch (IllegalArgumentException invalid) { finish(new StreamEvent.Rejected(localReason(invalid))); return; }
            catch (IOException invalid) { finish(new StreamEvent.Rejected("model_pre_dispatch_rejected")); return; }
            StreamEvent terminal;
            try {
                synchronized (this) { if (cancelled) return; exchange = new SdkModelCall(request, clock, maximumBytes, http, resources); }
                try (var running = exchange;
                     var events = running.client().responses().createStreaming(prepared)) {
                    responseOpened = true;
                    terminal = events(events);
                }
            } catch (OpenAIServiceException failure) {
                terminal = exchange.preDispatchRejection().<StreamEvent>map(StreamEvent.Rejected::new)
                        .orElseGet(() -> responseOpened ? new StreamEvent.OutcomeUnknown("model_outcome_unknown")
                                : terminal(failure(failure, exchange.errorBody(), policy)));
            } catch (Exception invalid) {
                terminal = exchange == null ? new StreamEvent.OutcomeUnknown("model_outcome_unknown")
                        : exchange.preDispatchRejection().<StreamEvent>map(StreamEvent.Rejected::new)
                                .orElseGet(() -> new StreamEvent.OutcomeUnknown("model_outcome_unknown"));
            }
            finish(terminal);
        }

        private StreamEvent events(StreamResponse<ResponseStreamEvent> responseEvents) throws IOException, InterruptedException {
            var iterator = responseEvents.stream().iterator();
            var items = new TreeMap<Integer, Item>(); var itemIds = new HashSet<String>();
            String responseId = null; long previous = -1; long sequence = 0;
            while (iterator.hasNext()) {
                JsonNode event = JSON.valueToTree(iterator.next());
                long current = integer(event, "sequence_number");
                if (current <= previous) throw invalid(); previous = current;
                String type = required(event, "type");
                if ("response.created".equals(type)) {
                    if (responseId != null || !"response".equals(required(event.path("response"), "object"))) throw invalid();
                    responseId = required(event.path("response"), "id");
                    if (responseId.isBlank()) throw invalid();
                    continue;
                }
                if (responseId == null) throw invalid();
                switch (type) {
                    case "response.in_progress", "response.queued" -> {
                        if (!responseId.equals(required(event.path("response"), "id"))) throw invalid();
                    }
                    case "response.output_item.added" -> {
                        int index = index(event, "output_index"); var value = event.path("item");
                        if (index != items.size() || !itemIds.add(required(value, "id"))) throw invalid();
                        var item = new Item(value, (int) items.values().stream().filter(existing -> existing.function).count()); items.put(index, item);
                        if (item.function) emit(new StreamEvent.ToolCallDelta(++sequence, item.toolIndex, item.callId, item.name, item.arguments.toString()));
                    }
                    case "response.function_call_arguments.delta" -> {
                        var item = item(items, event);
                        if (!item.function || item.argumentsDone) throw invalid();
                        String delta = required(event, "delta"); item.arguments.append(delta);
                        emit(new StreamEvent.ToolCallDelta(++sequence, item.toolIndex, item.callId, item.name, delta));
                    }
                    case "response.function_call_arguments.done" -> {
                        var item = item(items, event);
                        if (!item.function || item.argumentsDone || !required(event, "arguments").equals(item.arguments.toString())) throw invalid();
                        item.argumentsDone = true;
                    }
                    case "response.content_part.added" -> {
                        var item = item(items, event); int index = index(event, "content_index");
                        String partType = required(event.path("part"), "type");
                        if (item.function || index != item.parts.size() || !(item.reasoning ? "reasoning_text" : "output_text").equals(partType)) throw invalid();
                        var part = new StringBuilder(required(event.path("part"), "text")); item.parts.put(index, part);
                        if (!part.isEmpty()) {
                            if (item.reasoning) { item.emittedReasoning.append(part); emit(new StreamEvent.ReasoningDelta(++sequence, part.toString())); }
                            else emit(new StreamEvent.ContentDelta(++sequence, part.toString()));
                        }
                    }
                    case "response.output_text.delta", "response.reasoning_text.delta" -> {
                        var item = item(items, event); int index = index(event, "content_index");
                        if (item.reasoning != "response.reasoning_text.delta".equals(type)) throw invalid();
                        // 一些端点只提供 reasoning_text delta，没有独立 part.added。
                        if (item.reasoning) item.parts.computeIfAbsent(index, ignored -> new StringBuilder());
                        var part = item.parts.get(index);
                        if (item.function || part == null || item.doneParts.contains(index)) throw invalid();
                        String delta = required(event, "delta"); part.append(delta);
                        if (item.reasoning) { item.emittedReasoning.append(delta); emit(new StreamEvent.ReasoningDelta(++sequence, delta)); }
                        else emit(new StreamEvent.ContentDelta(++sequence, delta));
                    }
                    case "response.output_text.done", "response.reasoning_text.done" -> {
                        var item = item(items, event); int index = index(event, "content_index");
                        var part = item.parts.get(index);
                        if (item.reasoning != "response.reasoning_text.done".equals(type) || part == null || !item.doneParts.add(index)
                                || !part.toString().equals(required(event, "text"))) throw invalid();
                    }
                    case "response.content_part.done" -> {
                        var item = item(items, event); int index = index(event, "content_index");
                        if (!item.doneParts.contains(index) || !(item.reasoning ? "reasoning_text" : "output_text").equals(required(event.path("part"), "type"))
                                || !item.parts.get(index).toString().equals(required(event.path("part"), "text"))) throw invalid();
                    }
                    case "response.reasoning_summary_part.added" -> {
                        var item = item(items, event); int index = index(event, "summary_index");
                        if (!item.reasoning || index != item.summaries.size() || !"summary_text".equals(required(event.path("part"), "type"))) throw invalid();
                        item.summaries.put(index, new StringBuilder(required(event.path("part"), "text")));
                    }
                    case "response.reasoning_summary_text.delta" -> {
                        var item = item(items, event); var summary = item.summaries.get(index(event, "summary_index"));
                        if (!item.reasoning || summary == null) throw invalid();
                        summary.append(required(event, "delta"));
                    }
                    case "response.reasoning_summary_text.done" -> {
                        var item = item(items, event); var summary = item.summaries.get(index(event, "summary_index"));
                        if (!item.reasoning || summary == null || !summary.toString().equals(required(event, "text"))) throw invalid();
                    }
                    case "response.reasoning_summary_part.done" -> {
                        var item = item(items, event); var summary = item.summaries.get(index(event, "summary_index"));
                        if (!item.reasoning || summary == null || !"summary_text".equals(required(event.path("part"), "type"))
                                || !summary.toString().equals(required(event.path("part"), "text"))) throw invalid();
                    }
                    case "response.output_item.done" -> {
                        int index = index(event, "output_index"); var item = items.get(index);
                        if (item == null || item.done != null) throw invalid();
                        item.close(event.path("item"));
                        if (item.reasoning) {
                            String raw = reasoningText(item.done);
                            if (!raw.startsWith(item.emittedReasoning.toString())) throw invalid();
                            String remainder = raw.substring(item.emittedReasoning.length());
                            if (!remainder.isEmpty()) { item.emittedReasoning.append(remainder); emit(new StreamEvent.ReasoningDelta(++sequence, remainder)); }
                        }
                    }
                    case "response.completed", "response.incomplete", "response.failed" -> {
                        var response = event.path("response");
                        if (!responseId.equals(requireIdentity(request, response))
                                || !type.substring("response.".length()).equals(required(response, "status"))) throw invalid();
                        // 用量与正文一致性独立；终态身份确认后先交付供应商快照，正文有冲突仍保持结果未知。
                        var reportedUsage = response.hasNonNull("usage") ? usage(response.get("usage")) : emptyUsage();
                        if (response.hasNonNull("usage")) emit(new StreamEvent.UsageDelta(++sequence, reportedUsage));
                        if ("response.completed".equals(type)) {
                            if (!response.path("output").isArray() || response.path("output").size() != items.size()) throw invalid();
                            var authoritative = JSON.createArrayNode();
                            for (var entry : items.entrySet()) {
                                var item = entry.getValue(); var finalItem = response.path("output").get(entry.getKey());
                                if (item.done == null) throw invalid();
                                if (item.reasoning) {
                                    // 官方协议要求密文取 output_item.done，completed 快照允许不再携带密文。
                                    if (!item.id.equals(required(finalItem, "id")) || !"reasoning".equals(required(finalItem, "type"))
                                            || finalItem.hasNonNull("encrypted_content") && !finalItem.get("encrypted_content").equals(item.done.get("encrypted_content"))
                                            || finalItem.hasNonNull("content") && !finalItem.get("content").equals(item.done.get("content"))) throw invalid();
                                } else if (!item.done.equals(finalItem)) throw invalid();
                                authoritative.add(item.done);
                            }
                            var complete = response.deepCopy();
                            ((com.fasterxml.jackson.databind.node.ObjectNode) complete).set("output", authoritative);
                            response = complete;
                        }
                        var result = decode(response, request.modelId(), reportedUsage);
                        return terminal(result);
                    }
                    // 未支持的事件不静默丢弃，也不将截断响应包装成成功。
                    default -> throw invalid();
                }
            }
            throw invalid();
        }
    }

    private static Item item(Map<Integer, Item> items, JsonNode event) throws IOException {
        var item = items.get(index(event, "output_index"));
        if (item == null || item.done != null || !item.id.equals(required(event, "item_id"))) throw invalid();
        return item;
    }
    private static final class Item {
        final String id; final boolean function; final boolean reasoning; final String callId; final String name; final int toolIndex;
        final StringBuilder arguments = new StringBuilder();
        final Map<Integer, StringBuilder> parts = new TreeMap<>(); final Set<Integer> doneParts = new HashSet<>();
        final Map<Integer, StringBuilder> summaries = new TreeMap<>();
        final StringBuilder emittedReasoning = new StringBuilder();
        boolean argumentsDone; JsonNode done;
        Item(JsonNode value, int toolIndex) throws IOException {
            id = required(value, "id"); if (id.isBlank()) throw invalid();
            String type = required(value, "type"); function = "function_call".equals(type); reasoning = "reasoning".equals(type); this.toolIndex = toolIndex;
            if (function) { callId = required(value, "call_id"); name = required(value, "name"); arguments.append(required(value, "arguments")); }
            else {
                if (!reasoning && (!"message".equals(type) || !"assistant".equals(required(value, "role")) || !value.path("content").isArray() || !value.path("content").isEmpty())) throw invalid();
                callId = null; name = null;
            }
        }
        void close(JsonNode value) throws IOException {
            if (!id.equals(required(value, "id")) || value.hasNonNull("status") && !"completed".equals(required(value, "status"))) throw invalid();
            if (function) {
                if (!"function_call".equals(required(value, "type")) || !callId.equals(required(value, "call_id"))
                        || !name.equals(required(value, "name")) || !argumentsDone || !arguments.toString().equals(required(value, "arguments"))) throw invalid();
            } else if (reasoning) {
                if (!"reasoning".equals(required(value, "type")) || value.hasNonNull("encrypted_content") && !value.path("encrypted_content").isTextual()) throw invalid();
                if (reasoningText(value).isEmpty() && value.path("encrypted_content").asText().isBlank()) throw invalid();
                for (var summary : summaries.entrySet()) {
                    var actual = value.path("summary").path(summary.getKey());
                    if (!"summary_text".equals(required(actual, "type")) || !summary.getValue().toString().equals(required(actual, "text"))) throw invalid();
                }
                for (var part : parts.entrySet()) {
                    var actual = value.path("content").path(part.getKey());
                    if (!"reasoning_text".equals(required(actual, "type")) || !part.getValue().toString().equals(required(actual, "text"))) throw invalid();
                }
            } else {
                if (!"message".equals(required(value, "type")) || !"assistant".equals(required(value, "role"))
                        || !value.path("content").isArray() || value.path("content").size() != parts.size() || doneParts.size() != parts.size()) throw invalid();
                for (var part : parts.entrySet()) {
                    var actual = value.path("content").get(part.getKey());
                    if (!"output_text".equals(required(actual, "type")) || !part.getValue().toString().equals(required(actual, "text"))) throw invalid();
                }
            }
            done = value;
        }
    }
    private static StreamEvent terminal(InvocationResult result) {
        return switch (result) {
            case InvocationResult.Completed completed -> new StreamEvent.Completed(completed);
            case InvocationResult.Rejected rejected -> new StreamEvent.Rejected(rejected.reasonCode(), rejected.retryAt());
            case InvocationResult.OutcomeUnknown unknown -> new StreamEvent.OutcomeUnknown(unknown.reasonCode());
        };
    }
    private static long integer(JsonNode value, String field) throws IOException {
        var node = value.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) throw invalid(); return node.longValue();
    }
    private static int index(JsonNode value, String field) throws IOException {
        long result = integer(value, field); if (result > Integer.MAX_VALUE) throw invalid(); return (int) result;
    }
    private InvocationResult failure(OpenAIServiceException response, JsonNode errorBody, ChatCompletionsPolicy policy) {
        int status = response.statusCode();
        if (status >= 500 || status == 408) return new InvocationResult.OutcomeUnknown("model_outcome_unknown");
        String reason = status == 429 ? "model_rate_limited" : status == 401 || status == 403 ? "model_authentication_rejected" : "model_http_rejected";
        if (status == 400 && errorBody != null) {
            var error = errorBody.path("error");
            if ("context_length_exceeded".equals(error.path("code").asText())) reason = "model_context_overflow";
            else if (policy.contentRejectionPolicy().rejects(error.path("code").asText(), error.path("type").asText(), error.path("message").asText())) reason = "model_content_rejected";
        }
        Optional<Instant> retry = Optional.empty();
        if (status == 429) {
            var raw = response.headers().values("Retry-After").stream().findFirst().orElse("");
            try { retry = Optional.of(raw.matches("[0-9]{1,10}") ? clock.instant().plusSeconds(Math.max(1, Long.parseLong(raw)))
                    : java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()); }
            catch (RuntimeException invalid) { retry = Optional.of(clock.instant().plusSeconds(1)); }
        }
        return new InvocationResult.Rejected(reason, retry);
    }
    private static String localReason(IllegalArgumentException invalid) {
        var code = invalid.getMessage(); return code != null && code.matches("model_[a-z_]+") ? code : "model_pre_dispatch_rejected";
    }
}
