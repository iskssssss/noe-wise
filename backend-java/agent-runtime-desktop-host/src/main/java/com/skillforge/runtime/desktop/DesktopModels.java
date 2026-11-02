package com.skillforge.runtime.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.ModelBindingRef;
import com.skillforge.runtime.api.port.ModelPort.ModelCapabilitySnapshot;
import com.skillforge.runtime.api.port.ModelPort.RequestedModelStrategy;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 精确模型配置可持久化，API Key 仅保留在该进程的凭据槽中。 */
final class DesktopModels implements AutoCloseable {
    static final RequestedModelStrategy STRATEGY = new RequestedModelStrategy("desktop-native", "1");
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final ConcurrentHashMap<String, byte[]> credentials = new ConcurrentHashMap<>();
    private final ModelProviderCatalog catalog = ModelProviderCatalog.standard();
    private final ModelProviderRuntime providers;
    private final HttpClient http;
    private final Clock clock;
    private final CodecLimits limits;
    private volatile Settings current;

    DesktopModels(Clock clock, CodecLimits limits) {
        this.clock = clock;
        this.limits = limits;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        providers = ModelProviderRuntime.runtime(http, clock, MAX_RESPONSE_BYTES);
    }

    synchronized Settings configure(String providerId, String baseUrl, String model, String apiKey) {
        if (!Set.of("deepseek", "custom").contains(providerId)) throw new IllegalArgumentException("desktop_provider_unsupported");
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 8192 || apiKey.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("desktop_api_key_invalid");
        var service = catalog.presets().stream().filter(item -> item.providerId().equals(providerId))
                .findFirst().orElseThrow().services().getFirst();
        var resolved = catalog.resolve(new ModelProviderCatalog.Selection(providerId, service.serviceId(),
                service.profileVersion(), model, ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                AuthenticationScheme.BEARER_API_KEY, baseUrl));
        var settings = new Settings(providerId, resolved.endpoint(), resolved.modelId(), service.serviceId(), service.profileVersion(),
                resolved.verificationStatus() == ModelProviderCatalog.VerificationStatus.VERIFIED
                        && resolved.capabilities().streaming() == ModelProviderCatalog.SupportLevel.SUPPORTED);
        var previous = credentials.put(settings.id(), apiKey.getBytes(StandardCharsets.UTF_8));
        if (previous != null) Arrays.fill(previous, (byte) 0);
        current = settings;
        return settings;
    }

    Settings current() {
        var selected = current;
        if (selected == null) throw new DesktopRequestException(DesktopRequestException.Code.MODEL_NOT_CONFIGURED);
        return selected;
    }

    boolean available(Settings settings) { return credentials.containsKey(settings.id()); }

    ObjectNode test() {
        var callingThread = Thread.currentThread();
        var settings = current(); var key = credentials.get(settings.id());
        if (key == null) throw new DesktopRequestException(DesktopRequestException.Code.MODEL_NOT_CONFIGURED);
        var profile = new com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationProfile(settings.serviceId(), settings.profileVersion(),
                ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, "openai-compatible-chat-v1");
        var request = new com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest(settings.baseUrl(), new String(key, StandardCharsets.UTF_8),
                settings.model(), profile, java.util.List.of(new com.skillforge.runtime.adapter.model.provider.ModelProvider.Message(
                        com.skillforge.runtime.adapter.model.provider.ModelProvider.Role.USER, "Reply with OK.", null, java.util.List.of())),
                new ModelPort.ResponseContract(new ModelPort.ResponseFormat.Text(), ModelPort.EnforcementLevel.BEST_EFFORT, "desktop-connection-test"),
                new ModelPort.GenerationOptions(java.util.OptionalInt.of(16), java.util.OptionalDouble.empty()), ModelPort.ReasoningPolicy.DISABLED,
                java.util.List.of(), com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolChoice.NONE, Duration.ofSeconds(30), callingThread::isInterrupted, () -> { });
        var result = providers.generate(settings.providerId(), request);
        if (result instanceof com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult.Completed completed) {
            if ("length".equals(completed.finishReasonCode()) || "content_filter".equals(completed.finishReasonCode()))
                return DesktopJson.object().put("connected", false)
                        .put("code", "length".equals(completed.finishReasonCode()) ? "model_output_length" : "model_content_filtered")
                        .put("message", "模型未返回完整回复；本次测试产生了一次真实模型调用。");
            return DesktopJson.object().put("connected", true).put("message", "模型连接测试成功；本次测试产生了一次真实模型调用。");
        }
        if (result instanceof com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult.Rejected rejected)
            return DesktopJson.object().put("connected", false).put("code", rejected.reasonCode()).put("message", "模型供应商拒绝请求，请检查地址、型号和凭据。");
        return DesktopJson.object().put("connected", false).put("code", "MODEL_RESULT_UNKNOWN").put("message", "模型请求结果未知，请检查连接；不会自动重试。");
    }

    OpenAiModelPort port(Settings settings, FrozenModelTarget target, java.util.function.Predicate<ModelPort.ModelCall> authorize, java.util.function.Consumer<String> bodyPreview) {
        var profile = new OpenAiModelPort.ProviderProfile(settings.providerId(), settings.serviceId(), settings.profileVersion(),
                ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, "openai-compatible-chat-v1");
        var binding = new OpenAiModelPort.Binding(target, STRATEGY, settings.baseUrl(), settings.id(), profile);
        return new OpenAiModelPort(reference -> reference.equals(target.binding()) ? Optional.of(binding) : Optional.empty(),
                reference -> { var value = credentials.get(reference); return value == null ? null : value.clone(); }, authorize,
                (call, output) -> {
                    new SessionRuntimePayloads().validate(output, limits);
                    return true;
                }, http, clock, MAX_RESPONSE_BYTES, providers, bodyPreview);
    }

    FrozenModelTarget target(Settings settings, TenantId tenant, DesktopPolicy policy) {
        return new FrozenModelTarget(new ModelBindingRef(tenant, settings.id(), 1), settings.model(),
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT),
                        settings.streaming(), false,
                        new ResultRef("model-capability:" + settings.id())), policy.modelTimeout(), new ResultRef("model-resolution:" + settings.id()));
    }

    @Override public synchronized void close() {
        current = null;
        credentials.values().forEach(value -> Arrays.fill(value, (byte) 0));
        credentials.clear();
        providers.close();
        http.close();
    }

    record Settings(String providerId, String baseUrl, String model, String serviceId, String profileVersion, boolean streaming) {
        String id() { return "model:" + AgentExecutionCodec.digest(providerId + "\0" + baseUrl + "\0" + model + "\0" + serviceId + "\0" + profileVersion); }
        ObjectNode json() {
            return DesktopJson.object().put("providerId", providerId).put("baseUrl", baseUrl).put("model", model)
                    .put("serviceId", serviceId).put("profileVersion", profileVersion).put("streaming", streaming);
        }
        static Settings read(JsonNode value) {
            return new Settings(value.required("providerId").textValue(), value.required("baseUrl").textValue(),
                    value.required("model").textValue(), value.required("serviceId").textValue(), value.required("profileVersion").textValue(),
                    value.required("streaming").booleanValue());
        }
        @Override public String toString() { return "DesktopModelSettings[REDACTED]"; }
    }
}
