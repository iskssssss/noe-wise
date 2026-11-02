package com.skillforge.runtime.adapter.model.aliyun_bailian;

import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient;
import com.skillforge.runtime.adapter.model.openai.OpenAiCompatibleProfiles;
import com.skillforge.runtime.adapter.model.provider.ProviderEndpoints;
import com.skillforge.runtime.adapter.model.provider.ReasoningOptions;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.Availability;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.EndpointMode;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderCategory;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderPreset;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProtocolProfile;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import java.util.List;
import java.util.Objects;

/** 阿里云百炼供应商定义；各地域作为独立接入服务维护。 */
public final class AliyunBailianModelProvider implements ModelProvider {

    private static final ModelProviderCatalog.CapabilitySupport CHAT_CAPABILITIES =
            new ModelProviderCatalog.CapabilitySupport(
                    ModelProviderCatalog.SupportLevel.SUPPORTED,
                    ModelProviderCatalog.SupportLevel.SUPPORTED,
                    ModelProviderCatalog.SupportLevel.SUPPORTED,
                    ModelProviderCatalog.SupportLevel.SUPPORTED,
                    ModelProviderCatalog.SupportLevel.SUPPORTED);

    private final ModelProviderTransport transport;
    private final java.util.Optional<AliyunBailianModelDiscoveryClient> discovery;

    public AliyunBailianModelProvider() {
        this(ModelProviderTransport.unavailable());
    }

    public AliyunBailianModelProvider(ModelProviderTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.discovery = java.util.Optional.empty();
    }

    public AliyunBailianModelProvider(ModelProviderTransport transport, AliyunBailianModelDiscoveryClient discovery) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.discovery = java.util.Optional.of(Objects.requireNonNull(discovery, "discovery"));
    }

    @Override
    public List<OpenAiModelDiscoveryClient.DiscoveredModel> discover(
            DiscoveryRequest request) {
        if (AliyunBailianModelDiscoveryClient.DIALECT.equals(request.target().dialectVersion()))
            return discovery.orElseThrow(() -> new ModelProvider.ProviderOperationException("model_discovery_transport_unavailable"))
                    .discover(request);
        return transport.discover(request);
    }

    @Override
    public InvocationResult generate(InvocationRequest request) {
        var policy = requestPolicy(request);
        String rejection = thinkingRejection(request);
        return rejection == null ? transport.generate(request, policy) : new InvocationResult.Rejected(rejection);
    }

    @Override
    public java.util.concurrent.Flow.Publisher<StreamEvent> stream(
            InvocationRequest request) {
        var policy = requestPolicy(request);
        String rejection = thinkingRejection(request);
        return rejection == null ? transport.stream(request, policy) : rejectedStream(rejection);
    }

    private static String thinkingRejection(InvocationRequest request) {
        return request.reasoning() == ReasoningPolicy.DEFAULT || request.reasoning() == ReasoningPolicy.DISABLED
                || request.reasoning() == ReasoningPolicy.ENABLED ? null : "model_reasoning_policy_unsupported";
    }

    private static java.util.concurrent.Flow.Publisher<StreamEvent> rejectedStream(String reason) {
        return subscriber -> {
            Objects.requireNonNull(subscriber);
            subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
                private final java.util.concurrent.atomic.AtomicBoolean terminal = new java.util.concurrent.atomic.AtomicBoolean();
                public void request(long amount) {
                    if (!terminal.compareAndSet(false, true)) return;
                    if (amount <= 0) { subscriber.onError(new IllegalArgumentException("model_stream_invalid_demand")); return; }
                    subscriber.onNext(new StreamEvent.Rejected(reason)); subscriber.onComplete();
                }
                public void cancel() { terminal.set(true); }
            });
        };
    }

    private ChatCompletionsPolicy requestPolicy(InvocationRequest request) {
        var policy = chatCompletionsPolicy(request.reasoning());
        // 只按当前协议编码用户选择；不从模型名判断开关是否会被供应商接受。
        if (request.reasoning() == ReasoningPolicy.DISABLED)
            policy = policy.withThinkingEncoding(ThinkingEncoding.ENABLE_THINKING_FALSE);
        if (request.reasoning() == ReasoningPolicy.ENABLED)
            policy = policy.withThinkingEncoding(ThinkingEncoding.ENABLE_THINKING_TRUE);
        if (!EXPLICIT_CACHE_SERVICE.equals(request.profile().serviceId())) return policy;
        // 缓存写入费用仍由独立服务选择授权，模型兼容交给供应商及测试按钮确认。
        return policy.withPromptCache(EXPLICIT_CACHE_PROFILE.equals(request.profile().profileVersion())
                ? PromptCacheEncoding.EPHEMERAL_MESSAGES : PromptCacheEncoding.INVALID_PROFILE);
    }

    public static final String EXPLICIT_CACHE_SERVICE = "bailian-cn-beijing-explicit-cache";
    public static final String EXPLICIT_CACHE_PROFILE = "2026-10-08-explicit-cache-v1";

    @Override public ReasoningOptions reasoningOptions(ServiceProfile service, ProtocolProfile protocol, String modelId) {
        if (protocol.protocol() != ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS
                || !"openai-compatible-chat-v1".equals(protocol.dialectVersion())) return ReasoningOptions.unverified();
        return new ReasoningOptions(List.of(ReasoningPolicy.DEFAULT, ReasoningPolicy.DISABLED, ReasoningPolicy.ENABLED),
                ReasoningOptions.Source.PROTOCOL_DEFINITION, "https://help.aliyun.com/zh/model-studio/deep-thinking");
    }

    private static final String GLOBAL_PROFILE_VERSION = "2026-09-15-global-v1";
    private static final String WORKSPACE_PROFILE_VERSION = "2026-09-13-workspace-v1";
    private static final String COMPATIBLE_PATH = "/compatible-mode/v1";
    private static final String BEIJING_WORKSPACE_DOMAIN = "cn-beijing.maas.aliyuncs.com";

    @Override
    public ProviderPreset preset() {
        return new ProviderPreset("aliyun-bailian", "阿里云百炼 / 通义千问",
                ProviderCategory.CLOUD_PLATFORM,
                java.util.stream.Stream.concat(List.of(
                        new ServiceProfile(
                                "bailian-cn-beijing", "百炼中国内地（北京）",
                                GLOBAL_PROFILE_VERSION, Availability.SUPPORTED,
                                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                EndpointMode.FIXED,
                                OpenAiCompatibleProfiles.verifiedDiscovery(),
                                List.of(OpenAiCompatibleProfiles.chat(CHAT_CAPABILITIES, ModelProviderCatalog.VerificationStatus.VERIFIED)),
                                ModelProviderCatalog.ServiceRegion.CHINA_MAINLAND, java.util.Set.of(ModelProviderCatalog.ModelKind.LLM)),
                        new ServiceProfile(
                                "bailian-cn-beijing-workspace", "百炼北京 Workspace",
                                WORKSPACE_PROFILE_VERSION, Availability.CONFIGURABLE_UNVERIFIED,
                                "https://{workspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                                EndpointMode.SCOPED_SUBDOMAIN,
                                new ModelProviderCatalog.ModelDiscoveryProfile("/api/v1/models", AliyunBailianModelDiscoveryClient.DIALECT,
                                        java.util.Set.of(ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY),
                                        ModelProviderCatalog.VerificationStatus.UNVERIFIED),
                                List.of(OpenAiCompatibleProfiles.chat(CHAT_CAPABILITIES, ModelProviderCatalog.VerificationStatus.UNVERIFIED)),
                                ModelProviderCatalog.ServiceRegion.CHINA_MAINLAND, java.util.Set.of(ModelProviderCatalog.ModelKind.LLM)),
                        new ServiceProfile(
                                EXPLICIT_CACHE_SERVICE, "百炼北京（显式缓存，含写入计费）",
                                EXPLICIT_CACHE_PROFILE, Availability.CONFIGURABLE_UNVERIFIED,
                                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                EndpointMode.FIXED,
                                OpenAiCompatibleProfiles.unverifiedDiscovery(),
                                List.of(OpenAiCompatibleProfiles.chat(CHAT_CAPABILITIES, ModelProviderCatalog.VerificationStatus.UNVERIFIED)),
                                ModelProviderCatalog.ServiceRegion.CHINA_MAINLAND, java.util.Set.of(ModelProviderCatalog.ModelKind.LLM))).stream(),
                        BailianServiceProfiles.media().stream()).toList());
    }

    @Override public com.skillforge.runtime.adapter.model.provider.ModelImageSupport protocolImageSupport(ServiceProfile service,
            ProtocolProfile protocol) {
        if (!service.modelKinds().contains(ModelProviderCatalog.ModelKind.LLM)
                || protocol.protocol() != ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS)
            return com.skillforge.runtime.adapter.model.provider.ModelImageSupport.unsupported();
        return new com.skillforge.runtime.adapter.model.provider.ModelImageSupport(
                ModelProviderCatalog.SupportLevel.SUPPORTED, ModelProviderCatalog.SupportLevel.SUPPORTED, null);
    }

    @Override public String resolveDiscoveryEndpoint(ServiceProfile service,
            ModelProviderCatalog.ModelDiscoveryProfile profile, String endpoint) {
        if (AliyunBailianModelDiscoveryClient.DIALECT.equals(profile.dialectVersion()))
            return java.net.URI.create(endpoint).resolve("/api/v1/models").toString();
        return ModelProvider.super.resolveDiscoveryEndpoint(service, profile, endpoint);
    }

    @Override
    public String resolveEndpoint(ServiceProfile service, String requestedEndpoint) {
        if (service.endpointMode() == EndpointMode.SCOPED_SUBDOMAIN && List.of(
                "bailian-cn-beijing-image", "bailian-cn-beijing-video", "bailian-cn-beijing-asr",
                "bailian-cn-beijing-qwen-asr", "bailian-cn-beijing-tts").contains(service.serviceId())) {
            String endpoint = requestedEndpoint == null || requestedEndpoint.isBlank() ? service.endpoint() : requestedEndpoint;
            java.net.URI uri;
            try { uri = java.net.URI.create(endpoint); }
            catch (IllegalArgumentException invalid) {
                throw new ModelProviderCatalog.ProviderProfileRejectedException("model_endpoint_invalid");
            }
            String path = uri.getRawPath();
            if ("/".equals(path)) path = "";
            if ("/api/v1/".equals(path)) path = "/api/v1";
            if (!"".equals(path) && !"/api/v1".equals(path))
                throw new ModelProviderCatalog.ProviderProfileRejectedException("model_service_endpoint_mismatch");
            // 原生服务保留用户选择的根路径，只允许已核实默认域名或北京单级 Workspace。
            String domain = "dashscope.aliyuncs.com".equalsIgnoreCase(uri.getHost()) ? "aliyuncs.com" : BEIJING_WORKSPACE_DOMAIN;
            return ProviderEndpoints.scopedSubdomain(endpoint, domain, path);
        }
        if (service.endpointMode() == EndpointMode.FIXED) {
            return ProviderEndpoints.resolveDefault(service, requestedEndpoint);
        }
        if (!"bailian-cn-beijing-workspace".equals(service.serviceId())
                || service.endpointMode() != EndpointMode.SCOPED_SUBDOMAIN) {
            throw new ModelProviderCatalog.ProviderProfileRejectedException(
                    "model_service_endpoint_policy_not_implemented");
        }
        return ProviderEndpoints.scopedSubdomain(
                requestedEndpoint, BEIJING_WORKSPACE_DOMAIN, COMPATIBLE_PATH);
    }

}
