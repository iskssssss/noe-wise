package com.skillforge.runtime.adapter.model.provider;

import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ChatCompletionsPolicy;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities;
import com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.net.http.HttpClient;
import java.time.Clock;

/** 按冻结供应商标识选择独立实现，不读取目录默认值重新解释绑定。 */
public final class ModelProviderRuntime implements AutoCloseable {

    private final com.skillforge.runtime.adapter.model.transport.SdkHttpResources resources;

    private final Map<String, ModelProvider> providers;

    public ModelProviderRuntime(List<? extends ModelProvider> providers) {
        this(providers, null);
    }

    private ModelProviderRuntime(List<? extends ModelProvider> providers,
            com.skillforge.runtime.adapter.model.transport.SdkHttpResources resources) {
        this.resources = resources;
        Objects.requireNonNull(providers, "providers");
        Map<String, ModelProvider> indexed = new HashMap<>();
        for (ModelProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String providerId = provider.preset().providerId();
            if (indexed.putIfAbsent(providerId, provider) != null) {
                throw new IllegalArgumentException("model_provider_duplicate");
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public static ModelProviderRuntime standard() {
        return new ModelProviderRuntime(ModelProviders.standard());
    }

    /** 创建具备真实发现与调用能力的首批 Provider 集合。 */
    public static ModelProviderRuntime runtime(
            HttpClient http, Clock clock, int maximumResponseBytes) {
        var resources = new com.skillforge.runtime.adapter.model.transport.SdkHttpResources();
        return new ModelProviderRuntime(
                ModelProviders.runtime(http, clock, maximumResponseBytes, resources), resources);
    }

    @Override public void close() { if (resources != null) resources.close(); }

    /** 精确核对冻结路由声明，不按品牌或模型名猜测协议兼容。 */
    public boolean supportsInvocationProfile(String providerId, ModelProvider.InvocationProfile profile) {
        var provider = providers.get(providerId);
        return provider != null && provider.preset().services().stream().anyMatch(service ->
                service.serviceId().equals(profile.serviceId()) && service.profileVersion().equals(profile.profileVersion())
                && service.protocols().stream().anyMatch(protocol -> protocol.protocol() == profile.protocol()
                        && protocol.dialectVersion().equals(profile.dialectVersion())
                        && protocol.authenticationSchemes().contains(profile.authenticationScheme())));
    }

    public ChatCompletionsPolicy chatCompletionsPolicy(
            String providerId, ReasoningPolicy reasoningPolicy) {
        ModelProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("model_provider_unknown");
        }
        return provider.chatCompletionsPolicy(reasoningPolicy);
    }

    public List<OpenAiModelDiscoveryClient.DiscoveredModel> discover(
            String providerId, ModelProvider.DiscoveryRequest request) {
        var selected = provider(providerId);
        var target = request.target();
        var validated = new ModelProviderCatalog(List.of(selected)).resolveDiscovery(
                new ModelProviderCatalog.DiscoverySelection(providerId, target.serviceId(), target.profileVersion(),
                        target.authenticationScheme(), target.endpoint()));
        if (!validated.equals(target)) throw new IllegalArgumentException("model_discovery_profile_invalid");
        return selected.discover(request);
    }

    public ModelProvider.InvocationResult generate(
            String providerId, ModelProvider.InvocationRequest request) {
        String rejection = invocationRejection(providerId, request);
        if (rejection != null) return new ModelProvider.InvocationResult.Rejected(rejection);
        return provider(providerId).generate(request);
    }

    public Flow.Publisher<ModelProvider.StreamEvent> stream(
            String providerId, ModelProvider.InvocationRequest request) {
        String rejection = invocationRejection(providerId, request);
        if (rejection != null) return subscriber -> subscriber.onSubscribe(
                new Flow.Subscription() {
                    private final java.util.concurrent.atomic.AtomicBoolean terminal = new java.util.concurrent.atomic.AtomicBoolean();
                    private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
                    /** 拒绝发布者同样遵守一次终态、正需求与取消静默。 */
                    public void request(long amount) {
                        if (!terminal.compareAndSet(false, true)) return;
                        if (amount <= 0) {
                            subscriber.onError(new IllegalArgumentException("model_stream_demand_invalid"));
                            return;
                        }
                        subscriber.onNext(new ModelProvider.StreamEvent.Rejected(rejection));
                        if (!cancelled.get()) subscriber.onComplete();
                    }
                    /** 在任何需求到来前取消都不释放拒绝事件。 */
                    public void cancel() { cancelled.set(true); terminal.set(true); }
                });
        return provider(providerId).stream(request);
    }

    private String invocationRejection(String providerId, ModelProvider.InvocationRequest request) {
        if (!supportsInvocationProfile(providerId, request.profile())) return "model_provider_profile_invalid";
        var profile = request.profile();
        var provider = provider(providerId);
        var service = provider.preset().services().stream().filter(value -> value.serviceId().equals(profile.serviceId())
                && value.profileVersion().equals(profile.profileVersion())).findFirst().orElseThrow();
        if (!service.modelKinds().contains(ModelProviderCatalog.ModelKind.LLM)) return "model_provider_kind_invalid";
        var protocol = service.protocols().stream().filter(value -> value.protocol() == profile.protocol()
                && value.dialectVersion().equals(profile.dialectVersion())
                && value.authenticationSchemes().contains(profile.authenticationScheme())).findFirst().orElseThrow();
        if (!provider.reasoningOptions(service, protocol, request.modelId()).options().contains(request.reasoning()))
            return "model_reasoning_policy_unsupported";
        try { provider.resolveEndpoint(service, request.endpoint()); }
        catch (IllegalArgumentException rejected) { return "model_provider_endpoint_invalid"; }
        return retainedToolsAllowed(providerId, request) ? null : "model_retain_tools_none_unverified";
    }

    /** Runtime 和宿主消费相同的协议能力，模型声明由冻结请求提供。 */
    public PromptPrefixCapabilities prefixCapabilities(String providerId,
            ModelProvider.InvocationProfile profile, String modelId) {
        return provider(providerId).prefixCapabilities(profile, modelId);
    }

    /** 工具声明可以保留，但只有已证实的具体服务才能将其与 NONE 一并发送。 */
    private boolean retainedToolsAllowed(String providerId, ModelProvider.InvocationRequest request) {
        return request.tools().isEmpty() || request.toolChoice() != ModelProvider.ToolChoice.NONE
                || request.prefixCapabilities().retainToolsWithToolChoiceNone() == CapabilitySupportStatus.SUPPORTED
                || prefixCapabilities(providerId, request.profile(), request.modelId()).retainToolsWithToolChoiceNone()
                    == CapabilitySupportStatus.SUPPORTED;
    }

    private ModelProvider provider(String providerId) {
        ModelProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("model_provider_unknown");
        }
        return provider;
    }
}
