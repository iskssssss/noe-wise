package com.skillforge.runtime.adapter.model.minimax;

import com.skillforge.runtime.adapter.model.domestic.DomesticChatModelProvider;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.adapter.model.provider.ModelImageSupport;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.CapabilitySupport;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProtocolProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.SupportLevel;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import java.util.List;
import java.util.Set;

/** MiniMax 中国站 OpenAI 协议；使用当前国内域名，不把历史 minimaxi.com 或国际站作为回退。 */
public final class MiniMaxModelProvider extends DomesticChatModelProvider {
    public static final String SERVICE_ID = "minimax-cn-chat";
    private static final String FLASH = "MiniMax-M3.1-Flash";
    private static final Set<String> M2_MODELS = Set.of("MiniMax-M2.7", "MiniMax-M2.7-highspeed", "MiniMax-M2.5",
            "MiniMax-M2.5-highspeed", "MiniMax-M2.1", "MiniMax-M2.1-highspeed", "MiniMax-M2");

    public MiniMaxModelProvider() { this(ModelProviderTransport.unavailable()); }
    public MiniMaxModelProvider(ModelProviderTransport transport) {
        super(withSpeech(preset("minimax", "MiniMax", SERVICE_ID, "https://api.minimax.cn/v1",
                new CapabilitySupport(SupportLevel.UNSUPPORTED, SupportLevel.UNSUPPORTED, SupportLevel.SUPPORTED,
                        SupportLevel.SUPPORTED, SupportLevel.SUPPORTED), true)),
                "https://platform.minimax.cn/docs/api-reference/text-openai-api", transport);
    }

    private static com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderPreset withSpeech(
            com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderPreset chat) {
        var services = new java.util.ArrayList<>(chat.services());
        services.add(new ServiceProfile("minimax-cn-tts", "MiniMax 中国站 · 语音合成", PROFILE_VERSION,
                com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.Availability.SUPPORTED,
                "https://api.minimax.cn/v1", com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.EndpointMode.FIXED,
                null, List.of(new ProtocolProfile(
                        com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol.MINIMAX_NATIVE,
                        "minimax-speech-v1", Set.of(com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY),
                        new CapabilitySupport(SupportLevel.UNSUPPORTED, SupportLevel.UNSUPPORTED, SupportLevel.UNSUPPORTED,
                                SupportLevel.SUPPORTED, SupportLevel.UNSUPPORTED),
                        com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.VerificationStatus.UNVERIFIED)),
                com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceRegion.CHINA_MAINLAND,
                Set.of(com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelKind.SPEECH_SYNTHESIS)));
        return new com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderPreset(
                chat.providerId(), chat.displayName(), chat.category(), services);
    }

    @Override protected List<ReasoningPolicy> reasoningPolicies(String modelId) {
        if (FLASH.equals(modelId)) return List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED,
                ReasoningPolicy.LOW, ReasoningPolicy.MEDIUM, ReasoningPolicy.HIGH, ReasoningPolicy.XHIGH, ReasoningPolicy.MAX);
        if ("MiniMax-M3".equals(modelId)) return List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED, ReasoningPolicy.DISABLED);
        if (M2_MODELS.contains(modelId)) return List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED);
        return List.of(ReasoningPolicy.DEFAULT);
    }

    @Override protected ChatCompletionsPolicy policy(String modelId, ReasoningPolicy reasoning) {
        var policy = ChatCompletionsPolicy.standard().withStrictToolGeneration(false)
                .withOutputTokenField(OutputTokenField.MAX_COMPLETION_TOKENS)
                .withRequestOptions(new ChatRequestOptions(ThinkingHistory.DEFAULT, true, true));
        if (FLASH.equals(modelId)) {
            policy = policy.withThinkingEncoding(ThinkingEncoding.THINKING_ADAPTIVE_OBJECT);
            return reasoning == ReasoningPolicy.DEFAULT || reasoning == ReasoningPolicy.ENABLED ? policy
                    : policy.withReasoningEffort(ReasoningEffort.valueOf(reasoning.name()));
        }
        if ("MiniMax-M3".equals(modelId)) return policy.withThinkingEncoding(reasoning == ReasoningPolicy.DISABLED
                ? ThinkingEncoding.THINKING_DISABLED_OBJECT : ThinkingEncoding.THINKING_ADAPTIVE_OBJECT);
        return policy;
    }

    @Override public ModelImageSupport imageSupport(ServiceProfile service, ProtocolProfile protocol, String modelId) {
        return selectedChat(service, protocol, SERVICE_ID) && (FLASH.equals(modelId) || "MiniMax-M3".equals(modelId))
                ? new ModelImageSupport(SupportLevel.SUPPORTED, SupportLevel.UNVERIFIED, null) : super.imageSupport(service, protocol, modelId);
    }
}
