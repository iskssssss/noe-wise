package com.skillforge.runtime.adapter.model.openai.chat;

import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient;
import com.skillforge.runtime.adapter.model.protocol.CanonicalConversation;
import com.skillforge.runtime.adapter.model.protocol.ConversationCodec;
import com.skillforge.runtime.adapter.model.protocol.ConversationCodecs;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.adapter.model.transport.SdkModelCall;
import com.skillforge.runtime.adapter.model.transport.SdkHttpResources;
import com.openai.core.ObjectMappers;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIServiceException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ChatCompletionsPolicy;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.DiscoveryRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.StreamEvent;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolCall;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient.DiscoveredModel;
import com.skillforge.runtime.adapter.model.OpenAiModelProtocol;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** OpenAI Chat Completions 兼容服务共用的受限协议与无重试 HTTP 传输。 */
public final class OpenAiCompatibleProviderTransport implements ModelProviderTransport, AutoCloseable {

    private static final System.Logger log = System.getLogger(OpenAiCompatibleProviderTransport.class.getName());
    private static final String HTTP_REJECTED = "model_http_rejected";
    private static final String CONTEXT_OVERFLOW = "model_context_overflow";
    private static final String CONTENT_REJECTED = "model_content_rejected";
    private static final String CONTEXT_OVERFLOW_PROVIDER_CODE = "context_length_exceeded";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final ObjectMapper SDK_JSON = ObjectMappers.jsonMapper().copy();
    private static final ConversationCodecs CONVERSATIONS = ConversationCodecs.standard();

    private final SdkHttpResources resources;
    private final boolean ownsResources;
    private final HttpClient http;
    private final Clock clock;
    private final int maximumResponseBytes;
    private final OpenAiModelDiscoveryClient discovery;

    public OpenAiCompatibleProviderTransport(HttpClient http, Clock clock, int maximumResponseBytes) {
        this(http, clock, maximumResponseBytes, new SdkHttpResources(), true);
    }

    public OpenAiCompatibleProviderTransport(HttpClient http, Clock clock, int maximumResponseBytes, SdkHttpResources resources) {
        this(http, clock, maximumResponseBytes, resources, false);
    }

    private OpenAiCompatibleProviderTransport(HttpClient http, Clock clock, int maximumResponseBytes, SdkHttpResources resources, boolean ownsResources) {
        this.ownsResources = ownsResources;
        this.resources = Objects.requireNonNull(resources);
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumResponseBytes <= 0 || http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("model_transport_configuration_invalid");
        }
        this.maximumResponseBytes = maximumResponseBytes;
        this.discovery = new OpenAiModelDiscoveryClient(http, maximumResponseBytes);
    }

    @Override public void close() { if (ownsResources) resources.close(); }

    @Override
    public List<DiscoveredModel> discover(DiscoveryRequest request) {
        return discovery.discover(request.target(), request.credential(), request.timeout());
    }

    @Override
    public InvocationResult generate(InvocationRequest request, ChatCompletionsPolicy policy) {
        ChatCompletionCreateParams prepared;
        try {
            prepared = parameters(request, policy, false);
        } catch (IllegalArgumentException rejected) {
            return new InvocationResult.Rejected(preDispatchReason(rejected));
        } catch (IOException rejected) {
            return new InvocationResult.Rejected("model_pre_dispatch_rejected");
        } catch (RuntimeException unknown) {
            return new InvocationResult.Rejected("model_pre_dispatch_rejected");
        }
        var call = new SdkModelCall(request, clock, maximumResponseBytes, http, resources);
        try (call) {
            return decodeCompleted(request, policy,
                    SDK_JSON.valueToTree(call.client().chat().completions().create(prepared)));
        } catch (OpenAIServiceException rejected) {
            return uncertain(rejected.statusCode())
                    ? new InvocationResult.OutcomeUnknown("model_outcome_unknown")
                    : new InvocationResult.Rejected(rejectionReason(rejected.statusCode(), call.errorBody(), policy), retryAt(rejected));
        } catch (Exception unknown) {
            return call.preDispatchRejection().<InvocationResult>map(InvocationResult.Rejected::new)
                    .orElseGet(() -> new InvocationResult.OutcomeUnknown("model_outcome_unknown"));
        }
    }

    @Override
    public Flow.Publisher<StreamEvent> stream(
            InvocationRequest request, ChatCompletionsPolicy policy) {
        AtomicBoolean subscribed = new AtomicBoolean();
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            if (!subscribed.compareAndSet(false, true)) {
                subscriber.onError(new IllegalStateException("model_stream_already_subscribed"));
                return;
            }
            StreamSubscription subscription = new StreamSubscription(
                    request, policy, subscriber);
            subscriber.onSubscribe(subscription);
            // 同步订阅回调会持有 Runtime 写权监视器并访问 JDBC；Java 21 下不能钉住虚拟线程载体。
            Thread.ofPlatform().daemon(true).name("model-provider-stream").start(subscription::run);
        };
    }

    private ChatCompletionCreateParams parameters(
            InvocationRequest request, ChatCompletionsPolicy policy, boolean stream) throws IOException {
        validateProfile(request);
        endpoint(request.endpoint(), "/chat/completions");
        return ChatCompletionCreateParams.builder()
                .body(SDK_JSON.treeToValue(body(request, policy, stream), ChatCompletionCreateParams.Body.class))
                .build();
    }

    private JsonNode body(InvocationRequest request, ChatCompletionsPolicy policy, boolean stream)
            throws IOException {
        var root = JSON.createObjectNode().put("model", request.modelId());
        switch (policy.thinkingEncoding()) {
            case NONE -> { }
            case THINKING_DISABLED_OBJECT -> root.putObject("thinking").put("type", "disabled");
            case THINKING_ENABLED_OBJECT -> root.putObject("thinking").put("type", "enabled");
            case THINKING_ADAPTIVE_OBJECT -> root.putObject("thinking").put("type", "adaptive");
            case ENABLE_THINKING_FALSE -> root.put("enable_thinking", false);
            case ENABLE_THINKING_TRUE -> root.put("enable_thinking", true);
        }
        var options = policy.requestOptions();
        switch (options.thinkingHistory()) {
            case DEFAULT -> { }
            case KEEP_ALL -> root.withObject("thinking").put("keep", "all");
            case CLEAR_FALSE -> root.withObject("thinking").put("clear_thinking", false);
        }
        if (options.reasoningSplit()) root.put("reasoning_split", true);
        policy.reasoningEffort().ifPresent(effort -> root.put("reasoning_effort", effort.wireValue()));
        root.set("messages", CONVERSATIONS.require(request.profile().protocol())
                .encode(CanonicalConversation.from(request.messages(), request.mediaReader()), policy));
        request.generation().maxOutputTokens().ifPresent(v -> root.put(policy.outputTokenField().fieldName(), v));
        if (!options.omitTemperature()) request.generation().temperature().ifPresent(v -> root.put("temperature", v));
        if (request.response().format()
                instanceof com.skillforge.runtime.api.port.ModelPort.ResponseFormat.JsonObject) {
            root.putObject("response_format").put("type", "json_object");
        } else if (request.response().format()
                instanceof com.skillforge.runtime.api.port.ModelPort.ResponseFormat.JsonSchema schema) {
            var jsonSchema = root.putObject("response_format").put("type", "json_schema")
                    .putObject("json_schema").put("name", "runtime_response")
                    .put("strict", request.response().enforcement()
                            == com.skillforge.runtime.api.port.ModelPort.EnforcementLevel.STRICT);
            JsonNode value = JSON.readTree(schema.schema().canonicalJson());
            if (value == null || !value.isObject()) throw new IOException("model_protocol_invalid");
            jsonSchema.set("schema", value);
        }
        if (!request.tools().isEmpty()) {
            var tools = root.putArray("tools");
            for (var tool : request.tools()) {
                var function = tools.addObject().put("type", "function").putObject("function");
                function.put("name", tool.name()).put("description", tool.description());
                JsonNode schema = JSON.readTree(tool.inputSchema().canonicalJson());
                if (schema == null || !schema.isObject()) throw new IOException("model_protocol_invalid");
                function.set("parameters", schema);
                if (tool.strict() && policy.strictToolGeneration()) function.put("strict", true);
            }
            root.put("tool_choice", request.toolChoice().name().toLowerCase(Locale.ROOT));
            // NativeConversation 的整批资格由 Runtime 冻结目录与逐项权限共同校验。
            root.put("parallel_tool_calls", nativeConversation(request) && request.tools().stream().anyMatch(
                    ModelProvider.ToolDefinition::batchEligible));
        }
        root.put("stream", stream);
        if (stream) root.putObject("stream_options").put("include_usage", true);
        ChatCompletionsPromptCacheEncoder.encode(root, request, policy.promptCacheEncoding());
        byte[] encoded = JSON.writeValueAsBytes(root);
        if (encoded.length > request.maximumRequestBytes()) throw new RequestBytesExceeded();
        return root;
    }

    private static String preDispatchReason(IllegalArgumentException rejected) {
        if (rejected instanceof ChatCompletionsPromptCacheEncoder.InvalidProfile) {
            return "model_provider_profile_invalid";
        }
        if (rejected instanceof ChatCompletionsPromptCacheEncoder.UnsupportedModel) {
            return "model_prompt_cache_model_unsupported";
        }
        return rejected instanceof RequestBytesExceeded ? "model_request_bytes_exceeded" : "model_pre_dispatch_rejected";
    }

    private static final class RequestBytesExceeded extends IllegalArgumentException {
        private RequestBytesExceeded() { super("model_request_bytes_exceeded"); }
    }

    private InvocationResult decodeCompleted(InvocationRequest request, ChatCompletionsPolicy policy, JsonNode root)
            throws IOException {
        ModelUsage reportedUsage = emptyUsage();
        try {
            requireModel(root, request.modelId());
            reportedUsage = usage(root);
            JsonNode choice = singleChoice(root, false);
            String finish = finish(choice.get("finish_reason"));
            JsonNode message = choice.get("message");
            var codec = CONVERSATIONS.require(request.profile().protocol());
            var includeReasoning = request.reasoning() != com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy.DISABLED;
            com.skillforge.runtime.adapter.model.protocol.CanonicalConversation conversation;
            try {
                conversation = codec.decodeAssistant(message, nativeConversation(request), includeReasoning);
            } catch (IOException | IllegalArgumentException invalidTools) {
                if (!limitedFinish(finish) || message == null || !message.isObject() || !message.has("tool_calls")) throw invalidTools;
                var withoutTools = ((com.fasterxml.jackson.databind.node.ObjectNode) message).deepCopy();
                withoutTools.remove("tool_calls");
                conversation = codec.decodeAssistant(withoutTools, nativeConversation(request), includeReasoning);
                JsonNode rawCalls = message.get("tool_calls");
                if (nativeConversation(request) && rawCalls.isArray()) {
                    var validCalls = withoutTools.putArray("tool_calls");
                    for (JsonNode rawCall : rawCalls) {
                        var candidate = withoutTools.deepCopy();
                        candidate.putArray("tool_calls").add(rawCall);
                        try {
                            codec.decodeAssistant(candidate, true, includeReasoning);
                            validCalls.add(rawCall);
                        } catch (IOException | IllegalArgumentException ignored) {
                            // 明确终止时只保留能表达身份的调用事实。
                        }
                    }
                    conversation = codec.decodeAssistant(withoutTools, true, includeReasoning);
                }
            }
            String content = conversation.assistantText();
            String reasoning = conversation.reasoningText().orElse(null);
            List<ToolCall> toolCalls = conversation.toolCalls().stream()
                    .map(call -> new ToolCall(call.callId(), call.name(), call.argumentsJson())).toList();
            if (nativeConversation(request) && explicitRefusal(choice.get("message")) && "stop".equals(finish))
                finish = "content_filter";
            if (limitedFinish(finish) && !nativeConversation(request)) toolCalls = List.of();
            String decision = structuredDecisionContent(request, content, toolCalls);
            // 截断和内容过滤是确定终态，不要求其包含可用正文。
            if (!limitedFinish(finish)) validateOutput(request, decision, toolCalls);
            return new InvocationResult.Completed(decision, toolCalls, request.modelId(),
                    reportedUsage, finish, reasoning);
        } catch (IOException | RuntimeException invalidResponse) {
            // HTTP 已返回且 usage 已由供应商报告；正文或 tool_calls 不能解析时仍须保留计量事实。
            return new InvocationResult.OutcomeUnknown("model_outcome_unknown", reportedUsage);
        }
    }

    private static void validateOutput(
            InvocationRequest request, String content, List<ToolCall> calls) throws IOException {
        if (nativeConversation(request)) return;
        if (!calls.isEmpty()) return;
        if (content == null || content.isEmpty()) throw invalid();
        if (!(request.response().format()
                instanceof com.skillforge.runtime.api.port.ModelPort.ResponseFormat.Text)) {
            JsonNode structured = JSON.readTree(content);
            if (structured == null || !structured.isObject()) throw invalid();
        }
    }

    /**
     * json_object/json_schema 且无 tool_call 时，空正文或非对象是本地可判定的不可用决策。
     * 收成空对象交给 decoder 拒绝并按 LoopBudget 重试，不能记成结果未知。
     */
    private static String structuredDecisionContent(
            InvocationRequest request, String content, List<ToolCall> calls) {
        if (nativeConversation(request) || !calls.isEmpty() || request.response().format()
                instanceof com.skillforge.runtime.api.port.ModelPort.ResponseFormat.Text) {
            return content;
        }
        if (content == null || content.isBlank()) return "{}";
        try (var parser = JSON.createParser(content)) {
            JsonNode structured = JSON.readTree(parser);
            // 与 Runtime 使用相同的完整文档边界，不能接受对象后夹带正文或第二个 JSON 值。
            if (structured != null && structured.isObject() && parser.nextToken() == null) return content;
        } catch (IOException ignored) {
            // 非 JSON 与非对象同样不能成为七动作决策。
        }
        return "{}";
    }

    private static boolean structuredEmptyDecision(
            InvocationRequest request, StringBuilder content, boolean noToolCalls) {
        return !nativeConversation(request) && noToolCalls && content.isEmpty()
                && !(request.response().format()
                instanceof com.skillforge.runtime.api.port.ModelPort.ResponseFormat.Text);
    }

    /** 拒绝字段属于供应商协议事实，只有显式非空拒绝才规范为 content_filter。 */
    private static boolean explicitRefusal(JsonNode message) throws IOException {
        if (message == null || !message.hasNonNull("refusal")) return false;
        var refusal = message.get("refusal");
        if (!refusal.isTextual()) throw invalid();
        return !refusal.textValue().isBlank();
    }

    private static boolean nativeConversation(InvocationRequest request) {
        return request.response().format()
                instanceof com.skillforge.runtime.api.port.ModelPort.ResponseFormat.NativeConversation;
    }

    private final class StreamSubscription implements Flow.Subscription {
        private final InvocationRequest request;
        private final java.time.Instant deadline;
        private final ChatCompletionsPolicy policy;
        private final Flow.Subscriber<? super StreamEvent> subscriber;
        private long demand;
        private boolean cancelled;
        private boolean terminated;
        private SdkModelCall exchange;

        private StreamSubscription(InvocationRequest request, ChatCompletionsPolicy policy,
                Flow.Subscriber<? super StreamEvent> subscriber) {
            this.request = request;
            this.deadline = clock.instant().plus(request.timeout());
            this.policy = policy;
            this.subscriber = subscriber;
        }

        @Override public synchronized void request(long count) {
            if (cancelled || terminated) return;
            if (count <= 0) {
                cancel();
                subscriber.onError(new IllegalArgumentException("model_stream_invalid_demand"));
                return;
            }
            demand = demand > Long.MAX_VALUE - count ? Long.MAX_VALUE : demand + count;
            notifyAll();
        }

        @Override public synchronized void cancel() {
            cancelled = true;
            if (exchange != null) exchange.close();
            notifyAll();
        }

        private void run() {
            StreamEvent terminal;
            boolean responseOpened = false;
            ChatCompletionCreateParams prepared;
            try {
                prepared = parameters(request, policy, true);
            } catch (IllegalArgumentException rejected) {
                emitTerminal(new StreamEvent.Rejected(preDispatchReason(rejected)));
                return;
            } catch (IOException rejected) {
                emitTerminal(new StreamEvent.Rejected("model_pre_dispatch_rejected"));
                return;
            } catch (RuntimeException unknown) {
                emitTerminal(new StreamEvent.Rejected("model_pre_dispatch_rejected"));
                return;
            }
            try {
                synchronized (this) {
                    if (cancelled) return;
                    exchange = new SdkModelCall(request, clock, maximumResponseBytes, http, resources);
                }
                try (var active = exchange;
                     var events = active.client().chat().completions().createStreaming(prepared)) {
                    responseOpened = true;
                    terminal = readEvents(events);
                }
            } catch (OpenAIServiceException rejected) {
                terminal = responseOpened || uncertain(rejected.statusCode())
                        ? new StreamEvent.OutcomeUnknown("model_outcome_unknown")
                        : new StreamEvent.Rejected(rejectionReason(rejected.statusCode(), exchange.errorBody(), policy), retryAt(rejected));
            } catch (Exception unknown) {
                terminal = exchange != null && exchange.preDispatchRejection().isPresent()
                        ? new StreamEvent.Rejected(exchange.preDispatchRejection().orElseThrow())
                        : new StreamEvent.OutcomeUnknown("model_outcome_unknown");
            }
            emitTerminal(terminal);
        }

        private StreamEvent readEvents(StreamResponse<ChatCompletionChunk> events) throws Exception {
            ConversationCodec conversation = CONVERSATIONS.require(request.profile().protocol());
            var content = new StringBuilder();
            var reasoning = new StringBuilder();
            // 供应商索引是关联身份，不能据其数值分配稠密数组；缺项在完成时仍严格拒绝。
            var toolBuilders = new TreeMap<Integer, ToolBuilder>();
            ModelUsage usage = emptyUsage();
            String finish = null;
            long sequence = 0;
            boolean modelConfirmed = false;
            boolean refused = false;
            boolean invalidToolFragments = false;
            String confirmedModel = null;
            var iterator = events.stream().iterator();
            String stage = "read";
            try {
                while (iterator.hasNext()) {
                    stage = "stream_budget";
                    if (request.cancelled().getAsBoolean()
                            || !clock.instant().isBefore(deadline)) {
                        throw invalid();
                    }
                    stage = "frame_json";
                    JsonNode root = SDK_JSON.valueToTree(iterator.next());
                    stage = "model_identity";
                    if (root.hasNonNull("model") || !modelConfirmed) {
                        String reportedModel = requireModel(root, request.modelId());
                        if (confirmedModel != null && !confirmedModel.equals(reportedModel)) throw invalid();
                        confirmedModel = reportedModel;
                        modelConfirmed = true;
                    }
                    if (root.hasNonNull("usage")) {
                        stage = "usage";
                        // usage 与同帧 delta 相互独立；先交付已报告快照，后续 delta 非法也不能抹掉计量。
                        usage = OpenAiModelProtocol.mergeUsage(usage, usage(root));
                        emit(new StreamEvent.UsageDelta(++sequence, usage));
                    }
                    stage = "choice";
                    JsonNode choice = singleChoice(root, true);
                    if (choice != null) {
                        stage = "delta";
                        JsonNode part = choicePart(choice);
                        if (part != null) {
                            if (nativeConversation(request)) refused |= explicitRefusal(part);
                            String next = conversation.decodeTextDelta(part).orElse(null);
                            if (next != null && !next.isEmpty()) {
                                content.append(next);
                                emit(new StreamEvent.ContentDelta(++sequence, next));
                            }
                            String nextReasoning = request.reasoning()
                                    == com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy.DISABLED
                                    ? null : conversation.decodeReasoningDelta(part).orElse(null);
                            if (nextReasoning != null && !nextReasoning.isEmpty()) {
                                reasoning.append(nextReasoning);
                                emit(new StreamEvent.ReasoningDelta(++sequence, nextReasoning));
                            }
                            try {
                                for (JsonNode call : conversation.toolCallDeltas(part)) {
                                    try {
                                        var fragment = conversation.decodeToolCallDelta(call, nativeConversation(request));
                                        int index = fragment.index();
                                        if (index < 0 && fragment.callId() != null) {
                                            // 部分兼容端点在 SSE 中发送完整 message，调用只有 id 没有 delta index。
                                            // 按首次出现顺序关联独立调用，不能把第二个调用的参数拼进第一个调用。
                                            String callId = fragment.callId();
                                            index = toolBuilders.entrySet().stream()
                                                    .filter(entry -> callId.equals(entry.getValue().id))
                                                    .mapToInt(Map.Entry::getKey).findFirst().orElse(-1);
                                            if (index < 0) {
                                                if (!toolBuilders.isEmpty() && toolBuilders.lastKey() == Integer.MAX_VALUE) throw invalid();
                                                index = toolBuilders.isEmpty() ? 0 : toolBuilders.lastKey() + 1;
                                            }
                                        }
                                        if (index < 0) throw invalid();
                                        ToolBuilder builder = toolBuilders.computeIfAbsent(index, ignored -> new ToolBuilder());
                                        builder.append(fragment);
                                        String argumentsDelta = builder.takeArgumentsDelta();
                                        if (argumentsDelta != null) {
                                            emit(new StreamEvent.ToolCallDelta(++sequence, index,
                                                    builder.id, builder.name, argumentsDelta));
                                        }
                                    } catch (IOException | IllegalArgumentException invalidTool) {
                                        invalidToolFragments = true;
                                    }
                                }
                            } catch (IOException | IllegalArgumentException invalidTools) {
                                // 等待完成原因与后续 usage；只有明确截断或过滤才能丢弃不完整调用。
                                invalidToolFragments = true;
                            }
                        }
                        stage = "finish_reason";
                        if (choice.hasNonNull("finish_reason")) finish = finish(choice.get("finish_reason"));
                    }
                }
                stage = "stream_end";
                if (!modelConfirmed) throw invalid();
                // finish_reason 已明确终结语义时，允许兼容服务以 EOF 结束而不再发送可选的 [DONE] 哨兵。
                // 有部分正文或 tool_call 却没有 finish_reason 仍是截断，保持协议失败→结果未知。
                if (finish == null) {
                    if (!structuredEmptyDecision(request, content, toolBuilders.isEmpty())) throw invalid();
                    if (finish == null) finish = "stop";
                }
                if (refused && "stop".equals(finish)) finish = "content_filter";
                List<ToolCall> calls = new ArrayList<>();
                if (!limitedFinish(finish) && (invalidToolFragments
                        || !toolBuilders.isEmpty() && toolBuilders.lastKey() != toolBuilders.size() - 1)) throw invalid();
                stage = "tool_assembly";
                if (!limitedFinish(finish) || nativeConversation(request)) {
                    for (ToolBuilder builder : toolBuilders.values()) {
                        if (limitedFinish(finish) && (builder.id == null || builder.name == null)) continue;
                        calls.add(builder.build(nativeConversation(request)));
                    }
                }
                stage = "output_validation";
                String finalContent = structuredDecisionContent(request,
                        content.isEmpty() ? null : content.toString(), calls);
                if (!limitedFinish(finish)) validateOutput(request, finalContent, calls);
                return new StreamEvent.Completed(new InvocationResult.Completed(
                        finalContent, calls, request.modelId(), usage, finish,
                        reasoning.isEmpty() ? null : reasoning.toString()));
            } catch (Exception failure) {
                // 只输出阶段和计数，禁止输出供应商正文、工具参数、凭据或异常消息。
                log.log(System.Logger.Level.WARNING,
                        "模型流诊断 stage={0} limit={1} contentChars={2} reasoningChars={3} tools={4} finished={5} exception={6}",
                        stage, maximumResponseBytes, content.length(), reasoning.length(),
                        toolBuilders.size(), finish != null, failure.getClass().getSimpleName());
                throw failure;
            }
        }

        private synchronized void emit(StreamEvent event) throws InterruptedException {
            while (demand == 0 && !cancelled) {
                if (request.cancelled().getAsBoolean() || !clock.instant().isBefore(deadline)) { cancel(); break; }
                wait(20);
            }
            if (cancelled) throw new InterruptedException("model_stream_cancelled");
            if (demand != Long.MAX_VALUE) demand--;
            subscriber.onNext(event);
        }

        private void emitTerminal(StreamEvent event) {
            try {
                emit(event);
                synchronized (this) { if (cancelled) return; terminated = true; }
                subscriber.onComplete();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                cancel();
            }
        }

    }

    private static boolean limitedFinish(String finish) {
        return "length".equals(finish) || "content_filter".equals(finish);
    }

    private static final class ToolBuilder {
        private String id;
        private String name;
        private boolean invalidArguments;
        private final StringBuilder arguments = new StringBuilder();
        private final StringBuilder pendingArguments = new StringBuilder();

        private void append(ConversationCodec.ToolCallFragment fragment) {
            // 流式续片可用空字符串表示未重复发送身份；不得覆盖已收齐的调用身份。
            if (fragment.callId() != null && !fragment.callId().isBlank()) id = fragment.callId();
            if (fragment.name() != null && !fragment.name().isBlank()) name = fragment.name();
            if (fragment.invalidArguments()) {
                invalidArguments = true;
                return;
            }
            if (fragment.argumentsDelta() != null) {
                arguments.append(fragment.argumentsDelta());
                pendingArguments.append(fragment.argumentsDelta());
            }
        }

        private String takeArgumentsDelta() {
            if (id == null || name == null || pendingArguments.isEmpty()) return null;
            String value = pendingArguments.toString();
            pendingArguments.setLength(0);
            return value;
        }

        private ToolCall build(boolean nativeConversation) throws IOException {
            // 已接收响应却始终缺少身份属于协议结果未知，不能误报为派发前拒绝。
            if (id == null || name == null) throw invalid();
            if (!nativeConversation && arguments.toString().isBlank()) {
                throw new IllegalArgumentException("model_provider_tool_arguments_missing");
            }
            return new ToolCall(id, name, invalidArguments ? "" : arguments.toString());
        }
    }

    /** 确定拒绝状态消费供应商策略；不重试，不把错误响应或请求回显带入原因码。 */
    private String rejectionReason(int status, JsonNode root, ChatCompletionsPolicy policy) {
        if (status == 429) return "model_rate_limited";
        if (status == 401 || status == 403) return "model_authentication_rejected";
        if (status != 400 && status != 413) return HTTP_REJECTED;
        try {
            JsonNode error = root == null || !root.isObject() ? null : root.get("error");
            String code = errorText(error, "code");
            String type = errorText(error, "type");
            String message = errorText(error, "message");
            String reason = CONTEXT_OVERFLOW_PROVIDER_CODE.equals(code) ? CONTEXT_OVERFLOW
                    : policy.contentRejectionPolicy().rejects(code, type, message) ? CONTENT_REJECTED : HTTP_REJECTED;
            // 只输出固定字段名命中标记，供应商文本可能包含回显的请求或凭据。
            List<String> fields = List.of("reasoning_content", "tool_call_id", "tool_calls", "messages",
                    "max_tokens", "function", "tools", "temperature", "thinking", "content", "length", "invalid");
            String classifiedMessage = message.toLowerCase(Locale.ROOT);
            log.log(System.Logger.Level.WARNING, "模型拒绝分类 category={0} fields={1}", reason,
                    fields.stream().filter(classifiedMessage::contains).toList());
            return reason;
        } catch (RuntimeException rejected) {
            return HTTP_REJECTED;
        }
    }

    private static String errorText(JsonNode error, String field) {
        JsonNode value = error == null || !error.isObject() ? null : error.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    /** 只将响应头转换为冷却事实；HTTP 层不重发请求，也不截短供应商要求的等待时间。 */
    private java.util.Optional<java.time.Instant> retryAt(OpenAIServiceException response) {
        if (response.statusCode() != 429) return java.util.Optional.empty();
        var now = clock.instant();
        String value = response.headers().values("Retry-After").stream().findFirst().orElse("").trim();
        try {
            if (value.matches("[0-9]{1,10}")) return java.util.Optional.of(now.plusSeconds(Math.max(1, Long.parseLong(value))));
            if (!value.isEmpty()) {
                var at = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return java.util.Optional.of(at.isAfter(now) ? at : now.plusSeconds(1));
            }
        } catch (RuntimeException invalid) { return java.util.Optional.empty(); }
        value = response.headers().values("X-RateLimit-Reset").stream().findFirst().orElse("").trim();
        try {
            if (value.matches("[0-9]{1,12}")) {
                var at = java.time.Instant.ofEpochSecond(Long.parseLong(value));
                return java.util.Optional.of(at.isAfter(now) ? at : now.plusSeconds(1));
            }
        } catch (RuntimeException invalid) { return java.util.Optional.empty(); }
        return java.util.Optional.of(now.plusSeconds(1));
    }

    private static URI endpoint(String base, String path) {
        URI uri = URI.create(base.replaceAll("/+$", "") + path);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !("https".equals(uri.getScheme())
                || "http".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("model_endpoint_invalid");
        }
        return uri;
    }

    private static void validateProfile(InvocationRequest request) {
        if (request.profile().protocol()
                != ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS
                || request.profile().authenticationScheme()
                != ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY
                || !"openai-compatible-chat-v1".equals(request.profile().dialectVersion())) {
            throw new IllegalArgumentException("model_provider_profile_invalid");
        }
    }

    private static JsonNode singleChoice(JsonNode root, boolean emptyAllowed) throws IOException {
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.size() > 1
                || (!emptyAllowed && choices.isEmpty())) throw invalid();
        return choices.isEmpty() ? null : choices.get(0);
    }

    /**
     * 流式增量优先读 delta；部分兼容网关在收尾块改放完整 message，或只给 finish_reason。
     * 已确认过模型身份后，缺少 delta/message 的收尾块不能当成协议失败。
     */
    private static JsonNode choicePart(JsonNode choice) throws IOException {
        JsonNode delta = choice.get("delta");
        if (delta != null && !delta.isNull()) {
            if (!delta.isObject()) throw invalid();
            return delta;
        }
        JsonNode message = choice.get("message");
        if (message != null && !message.isNull()) {
            if (!message.isObject()) throw invalid();
            return message;
        }
        if (choice.hasNonNull("finish_reason")) {
            return null;
        }
        throw invalid();
    }

    private static String requireModel(JsonNode root, String expected) throws IOException {
        String reported = text(root.get("model"));
        if (!expected.equals(reported)) throw invalid();
        return reported;
    }

    private static String finish(JsonNode value) throws IOException {
        String reason = text(value);
        return switch (reason) {
            case "stop", "length", "content_filter", "tool_calls" -> reason;
            default -> throw invalid();
        };
    }

    private static ModelUsage usage(JsonNode root) throws IOException {
        JsonNode value = root.get("usage");
        if (value == null || value.isNull()) return emptyUsage();
        if (!value.isObject()) throw invalid();
        return OpenAiModelProtocol.usageObject(value);
    }

    private static ModelUsage emptyUsage() {
        return OpenAiModelProtocol.emptyUsage();
    }

    private static String text(JsonNode value) throws IOException {
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static boolean uncertain(int status) { return status == 408 || status >= 500; }

    private static IOException invalid() { return new IOException("model_protocol_invalid"); }
}
