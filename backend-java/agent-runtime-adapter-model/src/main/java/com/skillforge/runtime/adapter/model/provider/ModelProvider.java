package com.skillforge.runtime.adapter.model.provider;

import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderPreset;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ResolvedDiscovery;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProtocolProfile;
import com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient.DiscoveredModel;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.NativeProtocolState;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;

/**
 * 单个模型供应商的目录定义与供应商级请求差异。
 *
 * <p>每个供应商由独立实现类拥有，不允许在共享协议或目录中按供应商品牌分支。
 * 协议编码仍可复用；这里只返回当前供应商明确拥有的窄请求策略。
 */
public interface ModelProvider {

    ProviderPreset preset();

    /** 当前服务、协议与 Codec 的图片编码上限，不代表任何具体型号已通过视觉核实。 */
    default ModelImageSupport protocolImageSupport(ServiceProfile service, ProtocolProfile protocol) {
        return ModelImageSupport.unsupported();
    }

    /** 具体型号的图片能力；编码明确不支持时拒绝，其余未知型号保留管理员显式配置。 */
    default ModelImageSupport imageSupport(ServiceProfile service, ProtocolProfile protocol, String modelId) {
        var upper = protocolImageSupport(service, protocol);
        return new ModelImageSupport(
                upper.userImages() == ModelProviderCatalog.SupportLevel.UNSUPPORTED
                        ? ModelProviderCatalog.SupportLevel.UNSUPPORTED : ModelProviderCatalog.SupportLevel.UNVERIFIED,
                upper.toolImages() == ModelProviderCatalog.SupportLevel.UNSUPPORTED
                        ? ModelProviderCatalog.SupportLevel.UNSUPPORTED : ModelProviderCatalog.SupportLevel.UNVERIFIED, null);
    }

    /** 既有未专项核实供应商保留原 DEFAULT/DISABLED 入口，但来源明确未知，不授予其他推理档位。 */
    default ReasoningOptions reasoningOptions(ServiceProfile service, ProtocolProfile protocol, String modelId) {
        return new ReasoningOptions(List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.DISABLED), ReasoningOptions.Source.UNVERIFIED, "");
    }

    /** 按已选服务、协议与模型冻结前缀能力；模型专项限制由所属供应商覆盖。 */
    default PromptPrefixCapabilities prefixCapabilities(ServiceProfile service,
            ProtocolProfile protocol, String modelId) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(modelId, "modelId");
        return protocol.prefixCapabilities();
    }

    /** 发送前精确匹配当前实现拥有的服务和方言，未知组合不得借用品牌能力。 */
    default PromptPrefixCapabilities prefixCapabilities(InvocationProfile profile, String modelId) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(modelId, "modelId");
        for (var service : preset().services()) {
            if (!service.serviceId().equals(profile.serviceId())
                    || !service.profileVersion().equals(profile.profileVersion())) continue;
            for (var protocol : service.protocols()) {
                if (protocol.protocol() == profile.protocol()
                        && protocol.dialectVersion().equals(profile.dialectVersion())
                        && protocol.authenticationSchemes().contains(profile.authenticationScheme())) {
                    return prefixCapabilities(service, protocol, modelId);
                }
            }
        }
        return PromptPrefixCapabilities.unverified();
    }

    /** 显式发现当前凭据可见的模型；实现内部完成请求和响应校验。 */
    default List<DiscoveredModel> discover(DiscoveryRequest request) {
        throw new ProviderOperationException("model_discovery_not_implemented");
    }

    /** 执行一次完整模型调用；不得把待发送 HTTP 参数返回给上层。 */
    default InvocationResult generate(InvocationRequest request) {
        return new InvocationResult.Rejected("model_provider_call_not_implemented");
    }

    /** 执行流式模型调用，并在 Provider 内部完成协议事件解析与聚合。 */
    default Flow.Publisher<StreamEvent> stream(InvocationRequest request) {
        return subscriber -> subscriber.onError(
                new ProviderOperationException("model_provider_stream_not_implemented"));
    }

    /** 端点解析属于供应商边界；默认实现只处理固定地址与宿主授权的自定义地址。 */
    default String resolveEndpoint(ServiceProfile service, String requestedEndpoint) {
        Objects.requireNonNull(service, "service");
        return ProviderEndpoints.resolveDefault(service, requestedEndpoint);
    }

    /** 发现可使用独立原生路径；默认兼容协议沿已授权接入根目录解析。 */
    default String resolveDiscoveryEndpoint(ServiceProfile service,
            ModelProviderCatalog.ModelDiscoveryProfile discovery, String endpoint) {
        return endpoint + discovery.path();
    }

    default ChatCompletionsPolicy chatCompletionsPolicy(
            ReasoningPolicy reasoningPolicy) {
        Objects.requireNonNull(reasoningPolicy, "reasoningPolicy");
        return ChatCompletionsPolicy.standard();
    }

    /** OpenAI Chat Completions 共享编码器可消费的供应商级差异。 */
    record ChatCompletionsPolicy(ThinkingEncoding thinkingEncoding, boolean requireAssistantReasoning,
            PromptCacheEncoding promptCacheEncoding, OutputTokenField outputTokenField,
            ContentRejectionPolicy contentRejectionPolicy,
            Optional<ReasoningEffort> reasoningEffort, boolean strictToolGeneration,
            ChatRequestOptions requestOptions) {
        public ChatCompletionsPolicy(ThinkingEncoding thinkingEncoding, boolean requireAssistantReasoning,
                PromptCacheEncoding promptCacheEncoding, OutputTokenField outputTokenField,
                ContentRejectionPolicy contentRejectionPolicy, Optional<ReasoningEffort> reasoningEffort,
                boolean strictToolGeneration) {
            this(thinkingEncoding, requireAssistantReasoning, promptCacheEncoding, outputTokenField,
                    contentRejectionPolicy, reasoningEffort, strictToolGeneration, ChatRequestOptions.standard());
        }
        /** 现有基础Chat协议默认保留strict生成偏好，供应商可按实际wire支持显式关闭。 */
        public ChatCompletionsPolicy(ThinkingEncoding thinkingEncoding, boolean requireAssistantReasoning,
                PromptCacheEncoding promptCacheEncoding, OutputTokenField outputTokenField,
                ContentRejectionPolicy contentRejectionPolicy, Optional<ReasoningEffort> reasoningEffort) {
            this(thinkingEncoding, requireAssistantReasoning, promptCacheEncoding, outputTokenField,
                    contentRejectionPolicy, reasoningEffort, true);
        }

        public ChatCompletionsPolicy(boolean disableThinking, boolean requireAssistantReasoning) {
            this(disableThinking ? ThinkingEncoding.THINKING_DISABLED_OBJECT : ThinkingEncoding.NONE,
                    requireAssistantReasoning, PromptCacheEncoding.NONE,
                    OutputTokenField.MAX_TOKENS, ContentRejectionPolicy.standard(), Optional.empty());
        }

        public ChatCompletionsPolicy {
            Objects.requireNonNull(thinkingEncoding, "thinkingEncoding");
            Objects.requireNonNull(promptCacheEncoding, "promptCacheEncoding");
            Objects.requireNonNull(outputTokenField, "outputTokenField");
            Objects.requireNonNull(contentRejectionPolicy, "contentRejectionPolicy");
            Objects.requireNonNull(reasoningEffort, "reasoningEffort");
            Objects.requireNonNull(requestOptions, "requestOptions");
        }

        public ChatCompletionsPolicy withPromptCache(PromptCacheEncoding encoding) {
            return new ChatCompletionsPolicy(thinkingEncoding, requireAssistantReasoning, encoding,
                    outputTokenField, contentRejectionPolicy, reasoningEffort, strictToolGeneration, requestOptions);
        }

        public ChatCompletionsPolicy withOutputTokenField(OutputTokenField field) {
            return new ChatCompletionsPolicy(thinkingEncoding, requireAssistantReasoning, promptCacheEncoding,
                    field, contentRejectionPolicy, reasoningEffort, strictToolGeneration, requestOptions);
        }

        public ChatCompletionsPolicy withThinkingEncoding(ThinkingEncoding encoding) {
            return new ChatCompletionsPolicy(encoding, requireAssistantReasoning, promptCacheEncoding,
                    outputTokenField, contentRejectionPolicy, reasoningEffort, strictToolGeneration, requestOptions);
        }

        public ChatCompletionsPolicy withContentRejectionPolicy(ContentRejectionPolicy policy) {
            return new ChatCompletionsPolicy(thinkingEncoding, requireAssistantReasoning, promptCacheEncoding,
                    outputTokenField, policy, reasoningEffort, strictToolGeneration, requestOptions);
        }

        public ChatCompletionsPolicy withReasoningEffort(ReasoningEffort effort) {
            return new ChatCompletionsPolicy(thinkingEncoding, requireAssistantReasoning, promptCacheEncoding,
                    outputTokenField, contentRejectionPolicy, Optional.of(effort), strictToolGeneration, requestOptions);
        }

        /** 只控制供应商生成偏好字段，完整参数schema与Runtime本地校验不变。 */
        public ChatCompletionsPolicy withStrictToolGeneration(boolean enabled) {
            return new ChatCompletionsPolicy(thinkingEncoding, requireAssistantReasoning, promptCacheEncoding,
                    outputTokenField, contentRejectionPolicy, reasoningEffort, enabled, requestOptions);
        }

        /** 只发送供应商声明的固定参数，不能作为任意 JSON 参数透传入口。 */
        public ChatCompletionsPolicy withRequestOptions(ChatRequestOptions options) {
            return new ChatCompletionsPolicy(thinkingEncoding, requireAssistantReasoning, promptCacheEncoding,
                    outputTokenField, contentRejectionPolicy, reasoningEffort, strictToolGeneration, options);
        }

        public boolean disableThinking() { return thinkingEncoding == ThinkingEncoding.THINKING_DISABLED_OBJECT
                || thinkingEncoding == ThinkingEncoding.ENABLE_THINKING_FALSE; }

        public static ChatCompletionsPolicy standard() {
            return new ChatCompletionsPolicy(false, false);
        }

        public static ChatCompletionsPolicy withThinkingDisabled() {
            return new ChatCompletionsPolicy(true, false);
        }

        public static ChatCompletionsPolicy withAssistantReasoning() {
            return new ChatCompletionsPolicy(false, true);
        }
    }

    /** 供应商拥有的内容拒绝识别；响应字段仅用于判断，不能作为运行原因或日志输出。 */
    @FunctionalInterface
    interface ContentRejectionPolicy {
        boolean rejects(String code, String type, String message);

        /** 通用协议只承认明确的标准错误码，不按提示文本猜测内容拒绝。 */
        static ContentRejectionPolicy standard() {
            return (code, type, message) -> "content_filter".equals(code)
                    || "content_policy_violation".equals(code);
        }
    }

    /** 供应商拥有的原生思考开关；DEFAULT 不编码任何参数。 */
    enum ThinkingEncoding { NONE, THINKING_DISABLED_OBJECT, THINKING_ENABLED_OBJECT, THINKING_ADAPTIVE_OBJECT,
        ENABLE_THINKING_FALSE, ENABLE_THINKING_TRUE }

    /** Kimi 与智谱各自声明的历史思考保留字段；不能由共享编码器按品牌选择。 */
    enum ThinkingHistory { DEFAULT, KEEP_ALL, CLEAR_FALSE }

    /** 固定的 Chat 供应商参数差异；省略不支持的温度与拆分思考正文均由供应商明确选择。 */
    record ChatRequestOptions(ThinkingHistory thinkingHistory, boolean reasoningSplit, boolean omitTemperature) {
        public ChatRequestOptions { Objects.requireNonNull(thinkingHistory, "thinkingHistory"); }
        public static ChatRequestOptions standard() { return new ChatRequestOptions(ThinkingHistory.DEFAULT, false, false); }
    }

    /** 原生强度值由供应商策略选定；共享 Chat 编码器不按品牌推断。 */
    enum ReasoningEffort {
        NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX;
        public String wireValue() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    /** 供应商已经选择的协议编码；共享传输只执行编码，不识别品牌或推断模型能力。 */
    enum PromptCacheEncoding {
        NONE, OPENAI_KEY, EPHEMERAL_MESSAGES, UNSUPPORTED_MODEL, INVALID_PROFILE
    }

    enum OutputTokenField {
        MAX_TOKENS("max_tokens"), MAX_COMPLETION_TOKENS("max_completion_tokens");

        private final String fieldName;
        OutputTokenField(String fieldName) { this.fieldName = fieldName; }
        public String fieldName() { return fieldName; }
    }

    /** 宿主适配边界派生的隔离哈希；不得发送租户、主体或会话的原始标识。 */
    record PromptCacheScope(String key) {
        public PromptCacheScope {
            if (key == null || !key.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("model_prompt_cache_scope_invalid");
            }
        }

        @Override public String toString() { return "PromptCacheScope[key=***]"; }
    }

    record DiscoveryRequest(ResolvedDiscovery target, String credential, Duration timeout) {
        public DiscoveryRequest {
            Objects.requireNonNull(target, "target");
            requireText(credential, "model_provider_credential_missing");
            requireDuration(timeout);
        }

        /** 日志投影只保留安全路由字段，不输出凭据与端点。 */
        @Override public String toString() {
            return "DiscoveryRequest[targetConfigured=true, credential=***, timeout=" + timeout + "]";
        }
    }

    record InvocationRequest(String endpoint, String credential, String modelId,
            InvocationProfile profile, List<Message> messages,
            ResponseContract response, GenerationOptions generation,
            ReasoningPolicy reasoning, List<ToolDefinition> tools,
            ToolChoice toolChoice, Duration timeout, BooleanSupplier cancelled,
            java.util.function.Function<MediaRef, byte[]> mediaReader, long maximumRequestBytes,
            Optional<PromptCacheScope> promptCacheScope, PromptPrefixCapabilities prefixCapabilities,
            Runnable beforeSend) {
        public InvocationRequest withBeforeSend(Runnable check) {
            return new InvocationRequest(endpoint, credential, modelId, profile, messages, response, generation,
                    reasoning, tools, toolChoice, timeout, cancelled, mediaReader, maximumRequestBytes,
                    promptCacheScope, prefixCapabilities, check);
        }
        public InvocationRequest(String endpoint, String credential, String modelId,
                InvocationProfile profile, List<Message> messages, ResponseContract response,
                GenerationOptions generation, ReasoningPolicy reasoning, List<ToolDefinition> tools,
                ToolChoice toolChoice, Duration timeout, BooleanSupplier cancelled,
                java.util.function.Function<MediaRef, byte[]> mediaReader, long maximumRequestBytes,
                Optional<PromptCacheScope> promptCacheScope, Runnable beforeSend) {
            this(endpoint, credential, modelId, profile, messages, response, generation, reasoning,
                    tools, toolChoice, timeout, cancelled, mediaReader, maximumRequestBytes, promptCacheScope,
                    PromptPrefixCapabilities.unverified(), beforeSend);
        }
        public InvocationRequest withPrefixCapabilities(PromptPrefixCapabilities capabilities) {
            return new InvocationRequest(endpoint, credential, modelId, profile, messages, response, generation,
                    reasoning, tools, toolChoice, timeout, cancelled, mediaReader, maximumRequestBytes,
                    promptCacheScope, capabilities, beforeSend);
        }
        public InvocationRequest(String endpoint, String credential, String modelId,
                InvocationProfile profile, List<Message> messages, ResponseContract response,
                GenerationOptions generation, ReasoningPolicy reasoning, List<ToolDefinition> tools,
                ToolChoice toolChoice, Duration timeout, BooleanSupplier cancelled,
                java.util.function.Function<MediaRef, byte[]> mediaReader, long maximumRequestBytes, Runnable beforeSend) {
            this(endpoint, credential, modelId, profile, messages, response, generation, reasoning,
                    tools, toolChoice, timeout, cancelled, mediaReader, maximumRequestBytes, Optional.empty(), beforeSend);
        }
        public InvocationRequest(String endpoint, String credential, String modelId,
                InvocationProfile profile, List<Message> messages, ResponseContract response,
                GenerationOptions generation, ReasoningPolicy reasoning, List<ToolDefinition> tools,
                ToolChoice toolChoice, Duration timeout, BooleanSupplier cancelled, Runnable beforeSend) {
            this(endpoint, credential, modelId, profile, messages, response, generation, reasoning,
                    tools, toolChoice, timeout, cancelled,
                    ref -> { throw new IllegalStateException("model_media_reader_missing"); }, 16L * 1024 * 1024, beforeSend);
        }
        public InvocationRequest {
            requireText(endpoint, "model_provider_endpoint_missing");
            requireText(credential, "model_provider_credential_missing");
            requireText(modelId, "model_provider_model_missing");
            Objects.requireNonNull(profile, "profile");
            messages = List.copyOf(messages);
            if (messages.isEmpty() || messages.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("model_provider_messages_invalid");
            }
            Objects.requireNonNull(response, "response");
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(reasoning, "reasoning");
            tools = List.copyOf(tools);
            if (tools.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("model_provider_tools_invalid");
            }
            Objects.requireNonNull(toolChoice, "toolChoice");
            if (tools.isEmpty() && toolChoice != ToolChoice.NONE) {
                throw new IllegalArgumentException("model_provider_tool_choice_invalid");
            }
            requireDuration(timeout);
            Objects.requireNonNull(cancelled, "cancelled");
            Objects.requireNonNull(mediaReader, "mediaReader");
            Objects.requireNonNull(promptCacheScope, "promptCacheScope");
            Objects.requireNonNull(prefixCapabilities, "prefixCapabilities");
            Objects.requireNonNull(beforeSend, "beforeSend");
            if (maximumRequestBytes <= 0) throw new IllegalArgumentException("model_request_limit_invalid");
        }

        /** 日志投影不输出凭据、端点、消息正文、响应约束或工具定义。 */
        @Override public String toString() {
            return "InvocationRequest[credential=***, messageCount=" + messages.size()
                    + ", toolCount=" + tools.size() + ", reasoning=" + reasoning
                    + ", toolChoice=" + toolChoice + ", timeout=" + timeout + "]";
        }
    }

    record InvocationProfile(String serviceId, String profileVersion,
            ModelProviderCatalog.ModelProtocol protocol,
            ModelProviderCatalog.AuthenticationScheme authenticationScheme,
            String dialectVersion) {
        public InvocationProfile {
            requireText(serviceId, "model_provider_service_missing");
            requireText(profileVersion, "model_provider_profile_missing");
            Objects.requireNonNull(protocol, "protocol");
            Objects.requireNonNull(authenticationScheme, "authenticationScheme");
            requireText(dialectVersion, "model_provider_dialect_missing");
        }
    }

    record Message(Role role, List<ModelContent> contents, String toolCallId,
            List<ToolCall> toolCalls, String reasoningContent, Optional<NativeProtocolState> protocolState) {
        public Message(Role role, List<ModelContent> contents, String toolCallId, List<ToolCall> toolCalls, String reasoningContent) {
            this(role, contents, toolCallId, toolCalls, reasoningContent, Optional.empty());
        }
        public Message(Role role, String content, String toolCallId, List<ToolCall> toolCalls) {
            this(role, content, toolCallId, toolCalls, null);
        }
        public Message(Role role, String content, String toolCallId, List<ToolCall> toolCalls, String reasoningContent) {
            this(role, content == null ? List.of() : List.of(new ModelContent.Text(content)), toolCallId, toolCalls, reasoningContent);
        }
        public Message {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(protocolState, "protocolState");
            contents = List.copyOf(contents);
            if ((reasoningContent != null || protocolState.isPresent()) && role != Role.ASSISTANT) {
                throw new IllegalArgumentException("model_provider_message_invalid");
            }
            toolCalls = List.copyOf(toolCalls);
            if (toolCalls.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("model_provider_message_invalid");
            }
            if (role == Role.TOOL) {
                requireText(toolCallId, "model_provider_tool_call_id_missing");
                if (contents.isEmpty()) throw new IllegalArgumentException("model_provider_message_invalid");
            } else if (role == Role.ASSISTANT && !toolCalls.isEmpty()) {
                // 无正文工具回合保持消息边界，不伪造空图片或文本。
                if (contents.stream().allMatch(part -> part instanceof ModelContent.Text text && text.text().isEmpty()))
                    contents = List.of();
            } else {
                if (contents.isEmpty()) throw new IllegalArgumentException("model_provider_message_invalid");
                if (toolCallId != null || !toolCalls.isEmpty()) {
                    throw new IllegalArgumentException("model_provider_message_invalid");
                }
            }
            if ((role == Role.SYSTEM || role == Role.ASSISTANT)
                    && contents.stream().anyMatch(ModelContent.Image.class::isInstance))
                throw new IllegalArgumentException("model_provider_image_role_invalid");
        }
        /** 显式纯文本投影供文本消费者使用；媒体引用不伪装成正文。 */
        public String textContent() {
            return contents.stream().filter(ModelContent.Text.class::isInstance)
                    .map(ModelContent.Text.class::cast).map(ModelContent.Text::text)
                    .collect(java.util.stream.Collectors.joining());
        }
        @Override public String toString() { return "ProviderMessage[role=" + role + ",REDACTED]"; }
    }

    enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    /**
     * strict 是参数生成偏好，不是 ResponseContract 的强执行保证；宿主始终校验完整 inputSchema。
     * batchEligible 允许模型同轮生成多调用，不授予宿主并发执行权限。
     */
    record ToolDefinition(String name, String description,
            TypedPayload inputSchema, boolean strict,
            com.skillforge.runtime.api.port.ModelPort.ModelToolKind kind, boolean batchEligible) {
        public ToolDefinition {
            requireText(name, "model_provider_tool_name_missing");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(inputSchema, "inputSchema");
            Objects.requireNonNull(kind, "kind");
            if (batchEligible && kind != com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL
                    && !(kind == com.skillforge.runtime.api.port.ModelPort.ModelToolKind.AGENT_ACTION
                            && name.equals("read_skill_resource")))
                throw new IllegalArgumentException("control action cannot enter tool batch");
        }
    }

    record ToolCall(String callId, String name, String argumentsJson) {
        public ToolCall {
            requireText(callId, "model_provider_tool_call_id_missing");
            requireText(name, "model_provider_tool_name_missing");
            // 参数合法性由消费该调用的协议边界判定，空参数也应进入受控解码失败。
            Objects.requireNonNull(argumentsJson, "argumentsJson");
        }
    }

    enum ToolChoice { NONE, AUTO, REQUIRED }

    sealed interface InvocationResult permits InvocationResult.Completed,
            InvocationResult.Rejected, InvocationResult.OutcomeUnknown {
        record Completed(String content, List<ToolCall> toolCalls,
                String modelId, ModelUsage usage, String finishReasonCode,
                String reasoningContent, Optional<NativeProtocolState> protocolState)
                implements InvocationResult {
            public Completed(String content, List<ToolCall> toolCalls, String modelId, ModelUsage usage,
                    String finishReasonCode, String reasoningContent) {
                this(content, toolCalls, modelId, usage, finishReasonCode, reasoningContent, Optional.empty());
            }
            public Completed(String content, List<ToolCall> toolCalls,
                    String modelId, ModelUsage usage, String finishReasonCode) {
                this(content, toolCalls, modelId, usage, finishReasonCode, null);
            }
            public Completed {
                toolCalls = List.copyOf(toolCalls);
                // 已完整结束但无正文的原生响应也是确定结果，由适配器交给决策 decoder 拒绝。
                requireText(modelId, "model_provider_result_model_missing");
                Objects.requireNonNull(usage, "usage");
                requireText(finishReasonCode, "model_provider_finish_reason_missing");
                Objects.requireNonNull(protocolState, "protocolState");
                if (protocolState.isPresent() && !modelId.equals(protocolState.orElseThrow().modelId()))
                    throw new IllegalArgumentException("native_protocol_model_mismatch");
            }
            @Override public String toString() { return "ProviderCompleted[REDACTED]"; }
        }
        record Rejected(String reasonCode, java.util.Optional<java.time.Instant> retryAt,
                ModelUsage usage) implements InvocationResult {
            public Rejected(String reasonCode) { this(reasonCode, java.util.Optional.empty(), emptyUsage()); }
            public Rejected(String reasonCode, java.util.Optional<java.time.Instant> retryAt) {
                this(reasonCode, retryAt, emptyUsage());
            }
            public Rejected {
                requireText(reasonCode, "model_provider_reason_missing");
                Objects.requireNonNull(retryAt); Objects.requireNonNull(usage, "usage");
            }
        }
        record OutcomeUnknown(String reasonCode, ModelUsage usage) implements InvocationResult {
            public OutcomeUnknown(String reasonCode) { this(reasonCode, emptyUsage()); }
            public OutcomeUnknown {
                requireText(reasonCode, "model_provider_reason_missing");
                Objects.requireNonNull(usage, "usage");
            }
        }
    }

    sealed interface StreamEvent permits StreamEvent.ContentDelta,
            StreamEvent.ReasoningDelta, StreamEvent.ToolCallDelta, StreamEvent.UsageDelta,
            StreamEvent.Completed, StreamEvent.Rejected, StreamEvent.OutcomeUnknown {
        record ContentDelta(long sequence, String content) implements StreamEvent { }
        record ReasoningDelta(long sequence, String content) implements StreamEvent { }
        record ToolCallDelta(long sequence, int index, String callId,
                String name, String argumentsDelta) implements StreamEvent { }
        record UsageDelta(long sequence, ModelUsage usage) implements StreamEvent { }
        record Completed(InvocationResult.Completed result) implements StreamEvent { }
        record Rejected(String reasonCode, java.util.Optional<java.time.Instant> retryAt) implements StreamEvent {
            public Rejected(String reasonCode) { this(reasonCode, java.util.Optional.empty()); }
            public Rejected { requireText(reasonCode, "model_provider_reason_missing"); Objects.requireNonNull(retryAt); }
        }
        record OutcomeUnknown(String reasonCode) implements StreamEvent { }
    }

    final class ProviderOperationException extends RuntimeException {
        public ProviderOperationException(String reason) { super(reason); }
    }

    private static String requireText(String value, String reason) {
        if (value == null || value.isBlank()
                || value.chars().anyMatch(character -> Character.isISOControl((char) character))) {
            throw new IllegalArgumentException(reason);
        }
        return value;
    }

    private static void requireDuration(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("model_provider_timeout_invalid");
        }
    }

    private static ModelUsage emptyUsage() {
        return new ModelUsage(java.util.OptionalLong.empty(), java.util.OptionalLong.empty(),
                java.util.OptionalLong.empty(), java.util.OptionalLong.empty(),
                java.util.OptionalLong.empty());
    }
}
