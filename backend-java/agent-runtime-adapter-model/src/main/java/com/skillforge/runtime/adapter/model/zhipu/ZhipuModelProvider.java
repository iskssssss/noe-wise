package com.skillforge.runtime.adapter.model.zhipu;

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

/** 智谱中国标准 API；保留式思考原样回放，不沿用国际 Z.AI 的端点或账户。 */
public final class ZhipuModelProvider extends DomesticChatModelProvider {
    public static final String SERVICE_ID = "zhipu-cn";
    private static final Set<String> GLM53 = Set.of("glm-5.3", "glm-5.3-flash", "glm-5.3-flashx");
    private static final Set<String> SWITCHABLE = Set.of("glm-5.2", "glm-5.1", "glm-5", "glm-4.7", "glm-4.6", "glm-4.5", "glm-4.5-air");

    public ZhipuModelProvider() { this(ModelProviderTransport.unavailable()); }
    public ZhipuModelProvider(ModelProviderTransport transport) {
        super(preset("zhipu", "智谱 GLM", SERVICE_ID, "https://open.bigmodel.cn/api/paas/v4",
                new CapabilitySupport(SupportLevel.SUPPORTED, SupportLevel.UNVERIFIED, SupportLevel.SUPPORTED,
                        SupportLevel.SUPPORTED, SupportLevel.SUPPORTED), false),
                "https://docs.bigmodel.cn/cn/guide/capabilities/thinking-mode", transport);
    }

    @Override protected List<ReasoningPolicy> reasoningPolicies(String modelId) {
        if (GLM53.contains(modelId)) return List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED,
                ReasoningPolicy.LOW, ReasoningPolicy.HIGH, ReasoningPolicy.MAX);
        if ("glm-5.2".equals(modelId)) return List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED,
                ReasoningPolicy.DISABLED, ReasoningPolicy.HIGH, ReasoningPolicy.MAX);
        if (SWITCHABLE.contains(modelId)) return List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED, ReasoningPolicy.DISABLED);
        return List.of(ReasoningPolicy.DEFAULT);
    }

    @Override protected ChatCompletionsPolicy policy(String modelId, ReasoningPolicy reasoning) {
        var policy = ChatCompletionsPolicy.standard().withStrictToolGeneration(false);
        if (!GLM53.contains(modelId) && !SWITCHABLE.contains(modelId)) return policy;
        if (reasoning == ReasoningPolicy.DISABLED) return policy.withThinkingEncoding(ThinkingEncoding.THINKING_DISABLED_OBJECT);
        policy = policy.withThinkingEncoding(ThinkingEncoding.THINKING_ENABLED_OBJECT)
                .withRequestOptions(new ChatRequestOptions(ThinkingHistory.CLEAR_FALSE, false, false));
        return reasoning == ReasoningPolicy.DEFAULT || reasoning == ReasoningPolicy.ENABLED ? policy
                : policy.withReasoningEffort(ReasoningEffort.valueOf(reasoning.name()));
    }

    @Override public ModelImageSupport imageSupport(ServiceProfile service, ProtocolProfile protocol, String modelId) {
        return selectedChat(service, protocol, SERVICE_ID)
                && Set.of("glm-5.3-flash", "glm-5.3-flashx").contains(modelId)
                ? new ModelImageSupport(SupportLevel.SUPPORTED, SupportLevel.UNVERIFIED, null) : super.imageSupport(service, protocol, modelId);
    }
}
