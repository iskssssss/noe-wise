package com.skillforge.runtime.adapter.model.tencent;

import com.skillforge.runtime.adapter.model.domestic.DomesticChatModelProvider;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.CapabilitySupport;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.SupportLevel;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import java.util.List;
import java.util.Set;

/** 腾讯 TokenHub 广州服务；不从网关品牌推断第三方模型的思考或图片能力。 */
public final class TencentTokenHubModelProvider extends DomesticChatModelProvider {
    public static final String SERVICE_ID = "tencent-tokenhub-cn";
    private static final String REASONING_WITH_TOOLS_UNSUPPORTED = "model_reasoning_policy_unsupported_with_tools";
    private static final Set<String> HUNYUAN_MODELS = Set.of("hy3", "hy4-preview");

    public TencentTokenHubModelProvider() { this(ModelProviderTransport.unavailable()); }
    public TencentTokenHubModelProvider(ModelProviderTransport transport) {
        super(preset("tencent-tokenhub", "腾讯 TokenHub", SERVICE_ID, "https://tokenhub.tencentmaas.com/v1",
                new CapabilitySupport(SupportLevel.UNVERIFIED, SupportLevel.UNVERIFIED, SupportLevel.SUPPORTED,
                        SupportLevel.SUPPORTED, SupportLevel.SUPPORTED), true),
                "https://cloud.tencent.com/document/product/1823/131208", transport);
    }

    @Override protected List<ReasoningPolicy> reasoningPolicies(String modelId) {
        if ("hy3".equals(modelId)) return List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED,
                ReasoningPolicy.DISABLED, ReasoningPolicy.LOW, ReasoningPolicy.HIGH);
        if ("hy4-preview".equals(modelId)) return List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED,
                ReasoningPolicy.DISABLED, ReasoningPolicy.HIGH);
        return List.of(ReasoningPolicy.DEFAULT);
    }

    @Override protected ChatCompletionsPolicy policy(String modelId, ReasoningPolicy reasoning) {
        var policy = ChatCompletionsPolicy.standard().withStrictToolGeneration(false);
        if (!HUNYUAN_MODELS.contains(modelId) || reasoning == ReasoningPolicy.DEFAULT) return policy;
        if (reasoning == ReasoningPolicy.DISABLED) return policy.withThinkingEncoding(ThinkingEncoding.THINKING_DISABLED_OBJECT);
        policy = policy.withThinkingEncoding(ThinkingEncoding.THINKING_ENABLED_OBJECT);
        return reasoning == ReasoningPolicy.ENABLED ? policy
                : policy.withReasoningEffort(ReasoningEffort.valueOf(reasoning.name()));
    }

    @Override protected String invocationRejection(InvocationRequest request) {
        // 官方 Hy3 文档明确：携带 tools 时 low 会被服务端改为 high，不能当成已遵守 low。
        return "hy3".equals(request.modelId()) && request.reasoning() == ReasoningPolicy.LOW && !request.tools().isEmpty()
                ? REASONING_WITH_TOOLS_UNSUPPORTED : null;
    }
}
