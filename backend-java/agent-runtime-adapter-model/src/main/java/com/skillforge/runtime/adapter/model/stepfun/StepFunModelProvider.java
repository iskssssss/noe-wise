package com.skillforge.runtime.adapter.model.stepfun;

import com.skillforge.runtime.adapter.model.domestic.DomesticChatModelProvider;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.adapter.model.provider.ModelImageSupport;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.CapabilitySupport;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProtocolProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.SupportLevel;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import java.util.List;
import java.util.Set;

/** 阶跃星辰中国按量 API，使用独立供应商配置。 */
public final class StepFunModelProvider extends DomesticChatModelProvider {
    public static final String SERVICE_ID = "stepfun-cn";
    private static final Set<String> THREE_EFFORTS = Set.of("step-3.7-flash", "step-5-preview");
    private static final Set<String> STRUCTURED_MODELS = Set.of("step-5-preview", "step-3.7-flash",
            "step-3.5-flash", "step-3.5-flash-2603");

    public StepFunModelProvider() { this(ModelProviderTransport.unavailable()); }
    public StepFunModelProvider(ModelProviderTransport transport) {
        super(preset("stepfun", "阶跃星辰", SERVICE_ID, "https://api.stepfun.com/v1",
                new CapabilitySupport(SupportLevel.SUPPORTED, SupportLevel.SUPPORTED, SupportLevel.SUPPORTED,
                        SupportLevel.SUPPORTED, SupportLevel.SUPPORTED), true),
                "https://platform.stepfun.com/docs/zh/guides/developer/reasoning", transport);
    }

    @Override protected List<ReasoningPolicy> reasoningPolicies(String modelId) {
        if (THREE_EFFORTS.contains(modelId)) return List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED,
                ReasoningPolicy.LOW, ReasoningPolicy.MEDIUM, ReasoningPolicy.HIGH);
        if ("step-3.5-flash-2603".equals(modelId)) return List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED,
                ReasoningPolicy.LOW, ReasoningPolicy.HIGH);
        if ("step-3.5-flash".equals(modelId)) return List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.ENABLED);
        return List.of(ReasoningPolicy.DEFAULT);
    }

    @Override protected ChatCompletionsPolicy policy(String modelId, ReasoningPolicy reasoning) {
        var policy = ChatCompletionsPolicy.standard().withStrictToolGeneration(false);
        return reasoning == ReasoningPolicy.DEFAULT || reasoning == ReasoningPolicy.ENABLED ? policy
                : policy.withReasoningEffort(ReasoningEffort.valueOf(reasoning.name()));
    }

    @Override protected String invocationRejection(InvocationRequest request) {
        if (request.response().format() instanceof ResponseFormat.JsonSchema
                && !STRUCTURED_MODELS.contains(request.modelId())) return RESPONSE_FORMAT_UNSUPPORTED;
        return null;
    }

    @Override public ModelImageSupport imageSupport(ServiceProfile service, ProtocolProfile protocol, String modelId) {
        return selectedChat(service, protocol, SERVICE_ID) && THREE_EFFORTS.contains(modelId)
                ? new ModelImageSupport(SupportLevel.SUPPORTED, SupportLevel.UNVERIFIED, null) : super.imageSupport(service, protocol, modelId);
    }
}
