package com.skillforge.runtime.adapter.model.deepseek;

import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient;
import com.skillforge.runtime.adapter.model.openai.OpenAiCompatibleProfiles;
import com.skillforge.runtime.adapter.model.provider.ReasoningOptions;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.Availability;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.EndpointMode;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderCategory;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderPreset;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProtocolProfile;
import com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities;
import com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus;
import com.skillforge.runtime.api.port.ModelPort.ToolUpdateMode;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.adapter.model.deepseek.messages.DeepSeekMessagesProjection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** DeepSeek 供应商定义、思考模式请求差异与明确内容拒绝识别。 */
public final class DeepSeekModelProvider implements ModelProvider {

    private static final Pattern CONTENT_RISK = Pattern.compile("Content Exists Risk(?: \\(request_id: "
            + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\))?");

    private static final ModelProviderCatalog.CapabilitySupport CHAT_CAPABILITIES =
            new ModelProviderCatalog.CapabilitySupport(
                    ModelProviderCatalog.SupportLevel.SUPPORTED,
                    ModelProviderCatalog.SupportLevel.UNSUPPORTED,
                    ModelProviderCatalog.SupportLevel.SUPPORTED,
                    ModelProviderCatalog.SupportLevel.SUPPORTED,
                    ModelProviderCatalog.SupportLevel.SUPPORTED);

    private final ModelProviderTransport transport;
    private final ModelProviderTransport messages;

    /** 官方 Chat 文档核实于 2026-10-11；未声明 Messages 的历史工具更新协议。 */
    private static final PromptPrefixCapabilities CHAT_PREFIX = new PromptPrefixCapabilities(
            CapabilitySupportStatus.UNVERIFIED, ToolUpdateMode.NONE, CapabilitySupportStatus.SUPPORTED);

    public DeepSeekModelProvider() {
        this(ModelProviderTransport.unavailable(), ModelProviderTransport.unavailable());
    }

    public DeepSeekModelProvider(ModelProviderTransport transport) {
        this(transport, ModelProviderTransport.unavailable());
    }

    public DeepSeekModelProvider(ModelProviderTransport transport, ModelProviderTransport messages) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public List<OpenAiModelDiscoveryClient.DiscoveredModel> discover(
            DiscoveryRequest request) {
        return transport.discover(request);
    }

    @Override
    public InvocationResult generate(InvocationRequest request) {
        if (!reasoningAllowed(request)) return new InvocationResult.Rejected("model_reasoning_policy_unsupported");
        return selected(request).generate(request, chatCompletionsPolicy(request.reasoning()));
    }

    @Override
    public java.util.concurrent.Flow.Publisher<StreamEvent> stream(
            InvocationRequest request) {
        if (!reasoningAllowed(request)) return rejectedStream("model_reasoning_policy_unsupported");
        return selected(request).stream(request, chatCompletionsPolicy(request.reasoning()));
    }

    private boolean reasoningAllowed(InvocationRequest request) {
        var service = preset().services().getFirst();
        // 原生协议实例会进一步核 profile；此处只对本供应商声明的完整冻结组合应用模型清单。
        if (!service.serviceId().equals(request.profile().serviceId()) || !service.profileVersion().equals(request.profile().profileVersion()))
            return request.reasoning() == ReasoningPolicy.DEFAULT || request.reasoning() == ReasoningPolicy.DISABLED;
        var protocol = service.protocols().stream().filter(value -> value.protocol() == request.profile().protocol()
                && value.dialectVersion().equals(request.profile().dialectVersion())
                && value.authenticationSchemes().contains(request.profile().authenticationScheme())).findFirst();
        return protocol.isPresent() && reasoningOptions(service, protocol.orElseThrow(), request.modelId()).options().contains(request.reasoning());
    }

    private static java.util.concurrent.Flow.Publisher<StreamEvent> rejectedStream(String reason) {
        return subscriber -> subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            private final java.util.concurrent.atomic.AtomicBoolean terminal = new java.util.concurrent.atomic.AtomicBoolean();
            public void request(long n) {
                if (!terminal.compareAndSet(false, true)) return;
                if (n <= 0) { subscriber.onError(new IllegalArgumentException("model_stream_invalid_demand")); return; }
                subscriber.onNext(new StreamEvent.Rejected(reason)); subscriber.onComplete();
            }
            public void cancel() { terminal.set(true); }
        });
    }

    @Override public ReasoningOptions reasoningOptions(ServiceProfile service, ProtocolProfile protocol, String modelId) {
        if (!"deepseek-api".equals(service.serviceId()) || !"2026-09-13".equals(service.profileVersion())) return ReasoningOptions.unverified();
        if (protocol.protocol() == ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS
                && "openai-compatible-chat-v1".equals(protocol.dialectVersion()))
            return new ReasoningOptions(List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.DISABLED, ReasoningPolicy.ENABLED, ReasoningPolicy.LOW,
                    ReasoningPolicy.HIGH, ReasoningPolicy.MAX), ReasoningOptions.Source.PROTOCOL_DEFINITION,
                    "https://api-docs.deepseek.com/guides/thinking_mode");
        if (protocol.protocol() == ModelProviderCatalog.ModelProtocol.ANTHROPIC_MESSAGES
                && DeepSeekMessagesProjection.DIALECT.equals(protocol.dialectVersion()))
            return new ReasoningOptions(List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.DISABLED, ReasoningPolicy.ENABLED,
                    ReasoningPolicy.LOW, ReasoningPolicy.HIGH, ReasoningPolicy.MAX), ReasoningOptions.Source.PROTOCOL_DEFINITION,
                    "https://api-docs.deepseek.com/guides/thinking_mode");
        return ReasoningOptions.unverified();
    }

    private ModelProviderTransport selected(InvocationRequest request) {
        return request.profile().protocol() == ModelProviderCatalog.ModelProtocol.ANTHROPIC_MESSAGES ? messages : transport;
    }

    /** 协议提供思考参数候选；具体模型兼容性由正式测试结果确定。 */
    private static ProtocolProfile messagesProfile() {
        return new ProtocolProfile(ModelProviderCatalog.ModelProtocol.ANTHROPIC_MESSAGES,
                DeepSeekMessagesProjection.DIALECT, java.util.Set.of(ModelProviderCatalog.AuthenticationScheme.ANTHROPIC_API_KEY),
                new ModelProviderCatalog.CapabilitySupport(
                    ModelProviderCatalog.SupportLevel.UNSUPPORTED,
                        ModelProviderCatalog.SupportLevel.UNSUPPORTED,
                    ModelProviderCatalog.SupportLevel.SUPPORTED,
                        ModelProviderCatalog.SupportLevel.SUPPORTED,
                    ModelProviderCatalog.SupportLevel.SUPPORTED),
                ModelProviderCatalog.VerificationStatus.VERIFIED);
    }

    @Override
    public ProviderPreset preset() {
        return new ProviderPreset("deepseek", "DeepSeek",
                ProviderCategory.DOMESTIC_DIRECT,
                List.of(new ServiceProfile(
                        "deepseek-api", "DeepSeek API", "2026-09-13",
                        Availability.SUPPORTED,
                        "https://api.deepseek.com", EndpointMode.FIXED,
                        OpenAiCompatibleProfiles.verifiedDiscovery(),
                        List.of(OpenAiCompatibleProfiles.chat(CHAT_CAPABILITIES, ModelProviderCatalog.VerificationStatus.VERIFIED), messagesProfile()),
                        ModelProviderCatalog.ServiceRegion.CHINA_MAINLAND, java.util.Set.of(ModelProviderCatalog.ModelKind.LLM))));
    }

    @Override public com.skillforge.runtime.adapter.model.provider.ModelImageSupport protocolImageSupport(ServiceProfile service,
            ProtocolProfile protocol) {
        if (!preset().services().contains(service) || !service.protocols().contains(protocol))
            return com.skillforge.runtime.adapter.model.provider.ModelImageSupport.unsupported();
        return new com.skillforge.runtime.adapter.model.provider.ModelImageSupport(
                ModelProviderCatalog.SupportLevel.SUPPORTED, ModelProviderCatalog.SupportLevel.SUPPORTED, null);
    }

    @Override public com.skillforge.runtime.adapter.model.provider.ModelImageSupport imageSupport(ServiceProfile service,
            ProtocolProfile protocol, String modelId) {
        if (protocolImageSupport(service, protocol).userImages() == ModelProviderCatalog.SupportLevel.UNSUPPORTED)
            return com.skillforge.runtime.adapter.model.provider.ModelImageSupport.unsupported();
        if ("deepseek-v4-pro".equals(modelId))
            return com.skillforge.runtime.adapter.model.provider.ModelImageSupport.unsupported();
        if (!"deepseek-flash".equals(modelId))
            return ModelProvider.super.imageSupport(service, protocol, modelId);
        // Vision 官方每图至多 1024 tokens，以显式保守上界冻结；不据型号猜测视觉能力。
        var defaults = new com.skillforge.runtime.api.media.ModelImagePolicy(true, true, 4,
                10485760, 20971520, 2048, 512, 1024, 0, "deepseek-vision-upper-bound.v1");
        return new com.skillforge.runtime.adapter.model.provider.ModelImageSupport(
                ModelProviderCatalog.SupportLevel.SUPPORTED, ModelProviderCatalog.SupportLevel.SUPPORTED, defaults);
    }

    /** 只声明协议级可确定的前缀能力，不由模型名称推断历史更新支持。 */
    @Override public PromptPrefixCapabilities prefixCapabilities(ServiceProfile service,
            ProtocolProfile protocol, String modelId) {
        if (!"deepseek-api".equals(service.serviceId()) || !"2026-09-13".equals(service.profileVersion()))
            return PromptPrefixCapabilities.unverified();
        if (protocol.protocol() == ModelProviderCatalog.ModelProtocol.ANTHROPIC_MESSAGES
                && DeepSeekMessagesProjection.DIALECT.equals(protocol.dialectVersion()))
            return new PromptPrefixCapabilities(CapabilitySupportStatus.UNVERIFIED,
                    ToolUpdateMode.NONE, CapabilitySupportStatus.SUPPORTED);
        return protocol.protocol() == ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS
                && "openai-compatible-chat-v1".equals(protocol.dialectVersion())
                ? CHAT_PREFIX : PromptPrefixCapabilities.unverified();
    }

    @Override
    public ChatCompletionsPolicy chatCompletionsPolicy(
            ReasoningPolicy reasoningPolicy) {
        Objects.requireNonNull(reasoningPolicy, "reasoningPolicy");
        var policy = reasoningPolicy == ReasoningPolicy.DISABLED
                ? ChatCompletionsPolicy.withThinkingDisabled()
                : ChatCompletionsPolicy.withAssistantReasoning();
        if (reasoningPolicy != ReasoningPolicy.DEFAULT && reasoningPolicy != ReasoningPolicy.DISABLED) {
            policy = policy.withThinkingEncoding(ThinkingEncoding.THINKING_ENABLED_OBJECT);
            policy = switch (reasoningPolicy) {
                case LOW -> policy.withReasoningEffort(ReasoningEffort.LOW);
                case HIGH -> policy.withReasoningEffort(ReasoningEffort.HIGH);
                case MAX -> policy.withReasoningEffort(ReasoningEffort.MAX);
                case ENABLED -> policy;
                default -> throw new IllegalArgumentException("model_reasoning_policy_unsupported");
            };
        }
        // https://api-docs.deepseek.com/guides/tool_calls: strict需要/beta；普通Chat服务不发送该Beta字段。
        return policy.withContentRejectionPolicy(DeepSeekModelProvider::contentRejected).withStrictToolGeneration(false);
    }

    /** 仅认可已确认的 DeepSeek 拒绝协议；相似文本或回显请求不能扩大分类。 */
    private static boolean contentRejected(String code, String type, String message) {
        return ContentRejectionPolicy.standard().rejects(code, type, message)
                || "invalid_request_error".equals(code) && "invalid_request_error".equals(type)
                && CONTENT_RISK.matcher(message).matches();
    }
}
