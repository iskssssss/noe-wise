package com.skillforge.runtime.adapter.model.openai;

import com.skillforge.runtime.adapter.model.provider.ReasoningOptions;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.Availability;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.EndpointMode;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderCategory;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderPreset;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceProfile;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;

/** 用户显式授权端点的自定义 OpenAI-compatible 供应商定义。 */
public final class CustomOpenAiCompatibleModelProvider implements ModelProvider {

    private final ModelProviderTransport transport;
    private final ModelProviderTransport responses;

    public CustomOpenAiCompatibleModelProvider() {
        this(ModelProviderTransport.unavailable(), ModelProviderTransport.unavailable());
    }

    public CustomOpenAiCompatibleModelProvider(ModelProviderTransport transport) {
        this(transport, ModelProviderTransport.unavailable());
    }

    public CustomOpenAiCompatibleModelProvider(ModelProviderTransport transport, ModelProviderTransport responses) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.responses = Objects.requireNonNull(responses, "responses");
    }

    @Override
    public List<OpenAiModelDiscoveryClient.DiscoveredModel> discover(
            DiscoveryRequest request) {
        return transport.discover(request);
    }

    @Override
    public InvocationResult generate(InvocationRequest request) {
        return selected(request).generate(request, chatCompletionsPolicy(request.reasoning()));
    }

    @Override
    public java.util.concurrent.Flow.Publisher<StreamEvent> stream(
            InvocationRequest request) {
        return selected(request).stream(request, chatCompletionsPolicy(request.reasoning()));
    }

    private ModelProviderTransport selected(InvocationRequest request) {
        return request.profile().protocol() == ModelProviderCatalog.ModelProtocol.OPENAI_RESPONSES
                ? responses : transport;
    }

    /** 自定义端点选择的是协议契约；来源标注协议文档，不代表该端点或模型已验收。 */
    @Override public ReasoningOptions reasoningOptions(ServiceProfile service,
            ModelProviderCatalog.ProtocolProfile protocol, String modelId) {
        if (protocol.protocol() != ModelProviderCatalog.ModelProtocol.OPENAI_RESPONSES
                && protocol.protocol() != ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS)
            return ReasoningOptions.unverified();
        return new ReasoningOptions(List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.DISABLED,
                ReasoningPolicy.MINIMAL, ReasoningPolicy.LOW, ReasoningPolicy.MEDIUM,
                ReasoningPolicy.HIGH, ReasoningPolicy.XHIGH, ReasoningPolicy.MAX),
                ReasoningOptions.Source.PROTOCOL_DEFINITION,
                protocol.protocol() == ModelProviderCatalog.ModelProtocol.OPENAI_RESPONSES
                        ? "https://developers.openai.com/api/reference/resources/responses/methods/create"
                        : "https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create");
    }

    /** NONE 是两种基础协议的编码能力；具体端点是否遵守由显式测试核验。 */
    @Override public com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities prefixCapabilities(
            ServiceProfile service, ModelProviderCatalog.ProtocolProfile protocol, String modelId) {
        return new com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities(
                com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus.UNVERIFIED,
                com.skillforge.runtime.api.port.ModelPort.ToolUpdateMode.NONE,
                com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus.SUPPORTED);
    }

    @Override public ChatCompletionsPolicy chatCompletionsPolicy(ReasoningPolicy reasoning) {
        if (reasoning == ReasoningPolicy.DEFAULT) return ChatCompletionsPolicy.standard();
        if (reasoning == ReasoningPolicy.ENABLED)
            throw new IllegalArgumentException("model_reasoning_option_unsupported");
        return ChatCompletionsPolicy.standard().withReasoningEffort(reasoning == ReasoningPolicy.DISABLED
                ? ReasoningEffort.NONE : ReasoningEffort.valueOf(reasoning.name()));
    }

    /** 协议已实现不代表任意自定义端点和模型已经验收。 */
    private static ModelProviderCatalog.ProtocolProfile responsesProfile() {
        return new ModelProviderCatalog.ProtocolProfile(ModelProviderCatalog.ModelProtocol.OPENAI_RESPONSES,
                "openai-responses-v1", Set.of(ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY),
                new ModelProviderCatalog.CapabilitySupport(ModelProviderCatalog.SupportLevel.SUPPORTED,
                        ModelProviderCatalog.SupportLevel.SUPPORTED, ModelProviderCatalog.SupportLevel.SUPPORTED,
                        ModelProviderCatalog.SupportLevel.SUPPORTED, ModelProviderCatalog.SupportLevel.SUPPORTED),
                ModelProviderCatalog.VerificationStatus.UNVERIFIED);
    }

    @Override
    public ProviderPreset preset() {
        return new ProviderPreset("custom", "自定义 OpenAI-compatible",
                ProviderCategory.CUSTOM_OR_SELF_HOSTED,
                List.of(new ServiceProfile(
                        "custom-openai-compatible", "自定义端点", "custom-v1",
                        Availability.CONFIGURABLE_UNVERIFIED,
                        null, EndpointMode.CUSTOM,
                        OpenAiCompatibleProfiles.unverifiedDiscovery(),
                        List.of(OpenAiCompatibleProfiles.customChat(), responsesProfile()),
                        ModelProviderCatalog.ServiceRegion.LOCAL, Set.of(ModelProviderCatalog.ModelKind.LLM)),
                        vectorService("custom-embedding", "本地向量服务", ModelProviderCatalog.ModelKind.EMBEDDING,
                                ModelProviderCatalog.ModelProtocol.OPENAI_EMBEDDINGS),
                        vectorService("custom-rerank", "本地重排服务", ModelProviderCatalog.ModelKind.RERANK,
                                ModelProviderCatalog.ModelProtocol.OPENAI_RERANK)));
    }

    private static ServiceProfile vectorService(String id, String name, ModelProviderCatalog.ModelKind kind,
            ModelProviderCatalog.ModelProtocol protocol) {
        return new ServiceProfile(id, name, "custom-v1", Availability.CONFIGURABLE_UNVERIFIED, null,
                EndpointMode.CUSTOM, null, List.of(new ModelProviderCatalog.ProtocolProfile(protocol,
                        "openai-compatible-vector-v1", Set.of(ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY),
                        new ModelProviderCatalog.CapabilitySupport(ModelProviderCatalog.SupportLevel.UNSUPPORTED,
                                ModelProviderCatalog.SupportLevel.UNSUPPORTED, ModelProviderCatalog.SupportLevel.UNSUPPORTED,
                                ModelProviderCatalog.SupportLevel.UNSUPPORTED, ModelProviderCatalog.SupportLevel.UNSUPPORTED),
                        ModelProviderCatalog.VerificationStatus.UNVERIFIED)),
                ModelProviderCatalog.ServiceRegion.LOCAL, Set.of(kind));
    }

    @Override public com.skillforge.runtime.adapter.model.provider.ModelImageSupport protocolImageSupport(ServiceProfile service,
            ModelProviderCatalog.ProtocolProfile protocol) {
        if (!service.modelKinds().contains(ModelProviderCatalog.ModelKind.LLM)
                || protocol.protocol() != ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS
                        && protocol.protocol() != ModelProviderCatalog.ModelProtocol.OPENAI_RESPONSES)
            return com.skillforge.runtime.adapter.model.provider.ModelImageSupport.unsupported();
        return new com.skillforge.runtime.adapter.model.provider.ModelImageSupport(
                ModelProviderCatalog.SupportLevel.SUPPORTED, ModelProviderCatalog.SupportLevel.SUPPORTED, null);
    }

    @Override public String resolveEndpoint(ServiceProfile service, String requested) {
        return com.skillforge.runtime.adapter.model.provider.ProviderEndpoints.local(requested);
    }
}
