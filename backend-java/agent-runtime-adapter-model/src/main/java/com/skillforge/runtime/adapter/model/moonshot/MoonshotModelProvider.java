package com.skillforge.runtime.adapter.model.moonshot;

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

/** Kimi 中国站；思考参数和温度规则遵循供应商模型参数契约。 */
public final class MoonshotModelProvider extends DomesticChatModelProvider {
    public static final String SERVICE_ID = "moonshot-cn";
    private static final Set<String> EFFORT_MODELS = Set.of("kimi-k3", "kimi-k3.1-pro", "kimi-k3.1-pro-highspeed");
    private static final Set<String> ALWAYS_THINKING = Set.of("kimi-k2.7-code", "kimi-k2.7-code-highspeed");
    private static final Set<String> SWITCHABLE = Set.of("kimi-k2.6");
    private static final List<ReasoningPolicy> EFFORTS = List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED,
            ReasoningPolicy.LOW, ReasoningPolicy.HIGH, ReasoningPolicy.MAX);

    public MoonshotModelProvider() { this(ModelProviderTransport.unavailable()); }
    public MoonshotModelProvider(ModelProviderTransport transport) {
        super(preset("moonshot", "Kimi / 月之暗面", SERVICE_ID, "https://api.moonshot.cn/v1",
                new CapabilitySupport(SupportLevel.SUPPORTED, SupportLevel.UNVERIFIED, SupportLevel.SUPPORTED,
                        SupportLevel.SUPPORTED, SupportLevel.SUPPORTED), true),
                "https://platform.kimi.com/docs/guide/use-thinking-models", transport);
    }

    @Override protected List<ReasoningPolicy> reasoningPolicies(String modelId) {
        if (EFFORT_MODELS.contains(modelId)) return EFFORTS;
        if (ALWAYS_THINKING.contains(modelId)) return List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED);
        if (SWITCHABLE.contains(modelId)) return List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED, ReasoningPolicy.DISABLED);
        return List.of(ReasoningPolicy.DEFAULT);
    }

    @Override protected ChatCompletionsPolicy policy(String modelId, ReasoningPolicy reasoning) {
        var policy = ChatCompletionsPolicy.standard().withStrictToolGeneration(false)
                .withRequestOptions(new ChatRequestOptions(ThinkingHistory.DEFAULT, false, true));
        if (EFFORT_MODELS.contains(modelId)) {
            return reasoning == ReasoningPolicy.DEFAULT || reasoning == ReasoningPolicy.ENABLED ? policy
                    : policy.withReasoningEffort(ReasoningEffort.valueOf(reasoning.name()));
        }
        if (!SWITCHABLE.contains(modelId)) return policy;
        if (reasoning == ReasoningPolicy.DISABLED) return policy.withThinkingEncoding(ThinkingEncoding.THINKING_DISABLED_OBJECT);
        return policy.withThinkingEncoding(ThinkingEncoding.THINKING_ENABLED_OBJECT)
                .withRequestOptions(new ChatRequestOptions(ThinkingHistory.KEEP_ALL, false, true));
    }

    @Override protected String invocationRejection(InvocationRequest request) {
        return request.toolChoice() == ToolChoice.REQUIRED
                && (SWITCHABLE.contains(request.modelId()) || ALWAYS_THINKING.contains(request.modelId()))
                ? TOOL_CHOICE_UNSUPPORTED : null;
    }

    @Override public ModelImageSupport imageSupport(ServiceProfile service, ProtocolProfile protocol, String modelId) {
        return selectedChat(service, protocol, SERVICE_ID)
                && (EFFORT_MODELS.contains(modelId) || ALWAYS_THINKING.contains(modelId) || SWITCHABLE.contains(modelId))
                ? new ModelImageSupport(SupportLevel.SUPPORTED, SupportLevel.UNVERIFIED, null) : super.imageSupport(service, protocol, modelId);
    }
}
