package com.skillforge.runtime.adapter.model.xiaomi;

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

/** 小米 MiMo 中国集群 Token Plan，需使用专属 tp/ttp 凭据；不复用按量 sk 凭据或海外集群。 */
public final class XiaomiModelProvider extends DomesticChatModelProvider {
    public static final String SERVICE_ID = "xiaomi-token-plan-cn";
    private static final Set<String> MODELS = Set.of("mimo-v2.6-flash", "mimo-v2.6-pro", "mimo-v2.6-pro-ultraspeed");

    public XiaomiModelProvider() { this(ModelProviderTransport.unavailable()); }
    public XiaomiModelProvider(ModelProviderTransport transport) {
        super(preset("xiaomi", "小米 MiMo Token Plan", SERVICE_ID, "https://token-plan-cn.xiaomimimo.com/v1",
                new CapabilitySupport(SupportLevel.SUPPORTED, SupportLevel.UNSUPPORTED, SupportLevel.SUPPORTED,
                        SupportLevel.SUPPORTED, SupportLevel.SUPPORTED), false),
                "https://mimo.mi.com/docs/zh-CN/api/chat/openai-api", transport);
    }

    @Override protected List<ReasoningPolicy> reasoningPolicies(String modelId) {
        return MODELS.contains(modelId) ? List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED, ReasoningPolicy.DISABLED)
                : List.of(ReasoningPolicy.DEFAULT);
    }

    @Override protected ChatCompletionsPolicy policy(String modelId, ReasoningPolicy reasoning) {
        var policy = ChatCompletionsPolicy.standard().withOutputTokenField(OutputTokenField.MAX_COMPLETION_TOKENS);
        if (!MODELS.contains(modelId)) return policy;
        if (reasoning == ReasoningPolicy.DISABLED) return policy.withThinkingEncoding(ThinkingEncoding.THINKING_DISABLED_OBJECT);
        return policy.withThinkingEncoding(ThinkingEncoding.THINKING_ENABLED_OBJECT)
                .withRequestOptions(new ChatRequestOptions(ThinkingHistory.DEFAULT, false, true));
    }

    @Override protected String invocationRejection(InvocationRequest request) {
        // MiMo 对非 auto 的 tool_choice 会直接移除；先拒绝，不能让强制工具调用静默变成可选调用。
        return !request.tools().isEmpty() && request.toolChoice() != ToolChoice.AUTO ? TOOL_CHOICE_UNSUPPORTED : null;
    }

    @Override public ModelImageSupport imageSupport(ServiceProfile service, ProtocolProfile protocol, String modelId) {
        return selectedChat(service, protocol, SERVICE_ID) && MODELS.contains(modelId)
                ? new ModelImageSupport(SupportLevel.SUPPORTED, SupportLevel.UNVERIFIED, null) : super.imageSupport(service, protocol, modelId);
    }
}
