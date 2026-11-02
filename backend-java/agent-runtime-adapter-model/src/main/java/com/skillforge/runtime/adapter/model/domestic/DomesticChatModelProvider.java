package com.skillforge.runtime.adapter.model.domestic;

import com.skillforge.runtime.adapter.model.openai.OpenAiCompatibleProfiles;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient.DiscoveredModel;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelImageSupport;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.Availability;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.CapabilitySupport;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.EndpointMode;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProtocolProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelKind;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceRegion;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderCategory;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderPreset;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.SupportLevel;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.VerificationStatus;
import com.skillforge.runtime.adapter.model.provider.ReasoningOptions;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** 国内固定服务共用的完整 Chat 操作；供应商仅声明自己的精确参数和型号限制。 */
public abstract class DomesticChatModelProvider implements ModelProvider {
    public static final String PROFILE_VERSION = "2026-10-25-cn-v1";
    protected static final String PROFILE_INVALID = "model_provider_profile_invalid";
    protected static final String REASONING_UNSUPPORTED = "model_reasoning_policy_unsupported";
    protected static final String RESPONSE_FORMAT_UNSUPPORTED = "model_response_format_unsupported";
    protected static final String TOOL_CHOICE_UNSUPPORTED = "model_tool_choice_unsupported";
    private final ProviderPreset preset;
    private final String reasoningSource;
    private final ModelProviderTransport transport;

    protected DomesticChatModelProvider(ProviderPreset preset, String reasoningSource, ModelProviderTransport transport) {
        this.preset = Objects.requireNonNull(preset);
        this.reasoningSource = Objects.requireNonNull(reasoningSource);
        this.transport = Objects.requireNonNull(transport);
    }

    protected static ProviderPreset preset(String providerId, String displayName, String serviceId,
            String endpoint, CapabilitySupport capabilities, boolean modelListing) {
        return new ProviderPreset(providerId, displayName, ProviderCategory.DOMESTIC_DIRECT,
                List.of(new ServiceProfile(serviceId, displayName + "（中国内地）", PROFILE_VERSION,
                        Availability.CONFIGURABLE_UNVERIFIED, endpoint, EndpointMode.FIXED,
                        modelListing ? OpenAiCompatibleProfiles.unverifiedDiscovery() : null,
                        List.of(OpenAiCompatibleProfiles.chat(capabilities, VerificationStatus.UNVERIFIED)),
                        ServiceRegion.CHINA_MAINLAND, Set.of(ModelKind.LLM))));
    }

    protected abstract List<ReasoningPolicy> reasoningPolicies(String modelId);
    protected abstract ChatCompletionsPolicy policy(String modelId, ReasoningPolicy reasoning);

    /** 服务端会改变请求语义的已知参数组合由所属供应商在发送前拒绝。 */
    protected String invocationRejection(InvocationRequest request) { return null; }

    @Override public final ProviderPreset preset() { return preset; }

    /** 共享 Chat Codec 能编码用户图和关联工具图；这不推断当前具体型号的视觉能力。 */
    @Override public final ModelImageSupport protocolImageSupport(ServiceProfile service, ProtocolProfile protocol) {
        if (!preset.services().contains(service) || !service.protocols().contains(protocol)
                || protocol.protocol() != ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS
                || !"openai-compatible-chat-v1".equals(protocol.dialectVersion())) return ModelImageSupport.unsupported();
        return new ModelImageSupport(SupportLevel.SUPPORTED, SupportLevel.SUPPORTED, null);
    }

    @Override public final ReasoningOptions reasoningOptions(ServiceProfile service, ProtocolProfile protocol, String modelId) {
        if (!preset.services().contains(service) || !service.protocols().contains(protocol)) return ReasoningOptions.unverified();
        var values = reasoningPolicies(modelId);
        return new ReasoningOptions(values, values.equals(List.of(ReasoningPolicy.DEFAULT))
                ? ReasoningOptions.Source.UNVERIFIED : ReasoningOptions.Source.OFFICIAL_DOCUMENTATION, reasoningSource);
    }

    @Override public final List<DiscoveredModel> discover(DiscoveryRequest request) {
        var service = preset.services().getFirst();
        var discovery = service.modelDiscovery();
        if (discovery == null) throw new ProviderOperationException("model_discovery_not_supported");
        var target = request.target();
        if (!preset.providerId().equals(target.providerId()) || !service.serviceId().equals(target.serviceId())
                || !service.profileVersion().equals(target.profileVersion()) || !service.endpoint().equals(target.endpoint())
                || !discovery.authenticationSchemes().contains(target.authenticationScheme())
                || !discovery.dialectVersion().equals(target.dialectVersion())
                || !resolveDiscoveryEndpoint(service, discovery, target.endpoint()).equals(target.modelsEndpoint()))
            throw new ProviderOperationException(PROFILE_INVALID);
        return transport.discover(request);
    }

    @Override public final InvocationResult generate(InvocationRequest request) {
        String rejection = rejection(request);
        return rejection == null ? transport.generate(request, policy(request.modelId(), request.reasoning()))
                : new InvocationResult.Rejected(rejection);
    }

    @Override public final Flow.Publisher<StreamEvent> stream(InvocationRequest request) {
        String rejection = rejection(request);
        if (rejection == null) return transport.stream(request, policy(request.modelId(), request.reasoning()));
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean terminal = new AtomicBoolean();
            @Override public void request(long count) {
                if (!terminal.compareAndSet(false, true)) return;
                if (count <= 0) { subscriber.onError(new IllegalArgumentException("model_stream_invalid_demand")); return; }
                subscriber.onNext(new StreamEvent.Rejected(rejection));
                subscriber.onComplete();
            }
            @Override public void cancel() { terminal.set(true); }
        });
    }

    private String rejection(InvocationRequest request) {
        var service = preset.services().getFirst();
        var protocol = service.protocols().getFirst();
        var selection = request.profile();
        if (!service.serviceId().equals(selection.serviceId()) || !service.profileVersion().equals(selection.profileVersion())
                || protocol.protocol() != selection.protocol() || !protocol.dialectVersion().equals(selection.dialectVersion())
                || !protocol.authenticationSchemes().contains(selection.authenticationScheme())
                || !service.endpoint().equals(request.endpoint())) return PROFILE_INVALID;
        if (!reasoningPolicies(request.modelId()).contains(request.reasoning())) return REASONING_UNSUPPORTED;
        if ((request.response().format() instanceof ResponseFormat.JsonObject
                && protocol.capabilities().jsonObject() != SupportLevel.SUPPORTED)
                || (request.response().format() instanceof ResponseFormat.JsonSchema
                && protocol.capabilities().strictJsonSchema() != SupportLevel.SUPPORTED))
            return RESPONSE_FORMAT_UNSUPPORTED;
        return invocationRejection(request);
    }

    protected static boolean selectedChat(ServiceProfile service, ProtocolProfile protocol, String serviceId) {
        return serviceId.equals(service.serviceId()) && PROFILE_VERSION.equals(service.profileVersion())
                && protocol.protocol() == ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS
                && "openai-compatible-chat-v1".equals(protocol.dialectVersion());
    }
}
