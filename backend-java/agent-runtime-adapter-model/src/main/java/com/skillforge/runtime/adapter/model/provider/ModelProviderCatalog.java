package com.skillforge.runtime.adapter.model.provider;

import com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind;
import com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities;
import com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus;
import com.skillforge.runtime.api.port.ModelPort.ToolUpdateMode;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SDK 模型供应商预设目录。
 *
 * <p>目录只描述品牌、接入服务、协议—认证组合与可选模型发现方案，不预置供应商模型，
 * 也不保存租户凭据。宿主消费该目录完成租户选择，协议适配器消费解析结果执行调用；
 * {@code /models} 返回项和未核实能力不能扩大这里声明的协议组合。
 */
public final class ModelProviderCatalog {
    private final List<ProviderPreset> presets;
    private final Map<String, ModelProvider> providers;

    public ModelProviderCatalog(List<? extends ModelProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        Map<String, ModelProvider> indexed = new LinkedHashMap<>();
        for (ModelProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String providerId = provider.preset().providerId();
            if (indexed.putIfAbsent(providerId, provider) != null) {
                throw new IllegalArgumentException("model_provider_duplicate");
            }
        }
        this.providers = Map.copyOf(indexed);
        this.presets = indexed.values().stream().map(ModelProvider::preset).toList();
        requireUnique(this.presets.stream().map(ProviderPreset::providerId).toList(),
                "model_provider_duplicate");
    }

    /** 返回当前保留的供应商及基础协议接入预设。 */
    public static ModelProviderCatalog standard() {
        return new ModelProviderCatalog(ModelProviders.standard());
    }

    public List<ProviderPreset> presets() {
        return presets;
    }

    /** 服务范围由供应商目录声明，宿主不能从域名后缀或品牌猜测地域。 */
    public ServiceProfile serviceProfile(String providerId, String serviceId) {
        return service(provider(providerId), serviceId);
    }

    /** 查询协议编码上限，不借助空型号代替具体型号的能力核实。 */
    public ModelImageSupport protocolImageSupport(String providerId, String serviceId, ModelProtocol protocol) {
        var provider = provider(providerId);
        var service = service(provider, serviceId);
        var selected = service.protocols().stream().filter(value -> value.protocol() == protocol).findFirst()
                .orElseThrow(() -> rejected("model_protocol_not_allowed"));
        return provider.protocolImageSupport(service, selected);
    }

    /** 查询当前具体型号的图片能力；未核实不等于编码明确不支持。 */
    public ModelImageSupport imageSupport(String providerId, String serviceId, ModelProtocol protocol, String modelId) {
        var provider = provider(providerId);
        var service = service(provider, serviceId);
        var selected = service.protocols().stream().filter(value -> value.protocol() == protocol).findFirst()
                .orElseThrow(() -> rejected("model_protocol_not_allowed"));
        return provider.imageSupport(service, selected, modelId);
    }

    /**
     * 解析一个明确的服务—协议—认证与用户模型名选择；不联网，也不猜测缺失字段。
     *
     * @throws ProviderProfileRejectedException 组合未声明、尚未决定或端点不合法
     */
    public ResolvedProfile resolve(Selection selection) {
        Objects.requireNonNull(selection, "selection");
        ModelProvider provider = provider(selection.providerId());
        ServiceProfile service = service(provider, selection.serviceId());
        if (!service.configurable()) {
            throw rejected("model_service_not_configurable");
        }
        if (!service.profileVersion().equals(selection.profileVersion())) {
            throw rejected("model_service_profile_version_unknown");
        }
        requireText(selection.modelId(), "model_id");
        ProtocolProfile protocol = service.protocols().stream()
                .filter(candidate -> candidate.protocol() == selection.protocol())
                .findFirst().orElseThrow(() -> rejected("model_protocol_not_allowed"));
        if (!protocol.authenticationSchemes().contains(selection.authenticationScheme())) {
            throw rejected("model_authentication_not_allowed");
        }
        String endpoint = provider.resolveEndpoint(service, selection.endpoint());
        return new ResolvedProfile(selection.providerId(), service.serviceId(),
                service.profileVersion(), selection.modelId(), protocol.protocol(),
                protocol.dialectVersion(), selection.authenticationScheme(), endpoint,
                protocol.capabilities(), protocol.verificationStatus(),
                provider.prefixCapabilities(service, protocol, selection.modelId()));
    }

    /**
     * 校验冻结绑定请求的能力均已由当前服务协议明确支持。
     * 未核实能力与明确不支持能力都在绑定写入和网络发送前拒绝。
     */
    public void validateCapabilities(ResolvedProfile profile,
            Set<ResponseFormatKind> responseFormats, boolean streaming,
            boolean reasoning) {
        validateCapabilities(profile, responseFormats, streaming, false, reasoning);
    }

    /** 宿主选择器与实际调用共享供应商拥有的精确档位及官方依据。 */
    public ReasoningOptions reasoningOptions(Selection selection) {
        return reasoningOptions(resolve(selection));
    }

    private ReasoningOptions reasoningOptions(ResolvedProfile profile) {
        ModelProvider provider = provider(profile.providerId());
        ServiceProfile service = service(provider, profile.serviceId());
        var protocol = service.protocols().stream().filter(value -> value.protocol() == profile.protocol()
                && value.dialectVersion().equals(profile.dialectVersion())
                && value.authenticationSchemes().contains(profile.authenticationScheme())).findFirst()
                .orElseThrow(() -> rejected("model_protocol_not_allowed"));
        if (!service.profileVersion().equals(profile.profileVersion())) throw rejected("model_service_profile_version_unknown");
        return provider.reasoningOptions(service, protocol, profile.modelId());
    }

    public void validateReasoning(ResolvedProfile profile, com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy policy) {
        Objects.requireNonNull(policy);
        if (!reasoningOptions(profile).options().contains(policy)) throw rejected("model_reasoning_policy_unsupported");
    }

    /** 模型能力由宿主显式采用；这里仅拒绝适配器尚未实现的工具目录更新协议。 */
    public void validatePrefixCapabilities(ResolvedProfile profile, PromptPrefixCapabilities requested) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(requested, "requested");
        if (requested.toolUpdates() != ToolUpdateMode.UNVERIFIED && requested.toolUpdates() != ToolUpdateMode.NONE)
            throw rejected("model_tool_updates_not_implemented");
    }

    /** 采用的模型声明覆盖协议默认值；未报告项保持协议已有的确定语义。 */
    public PromptPrefixCapabilities effectivePrefixCapabilities(ResolvedProfile profile, PromptPrefixCapabilities configured) {
        validatePrefixCapabilities(profile, configured);
        var defaults = profile.prefixCapabilities();
        return new PromptPrefixCapabilities(
                configured.systemUpdatesInHistory() == CapabilitySupportStatus.UNVERIFIED
                        ? defaults.systemUpdatesInHistory() : configured.systemUpdatesInHistory(),
                configured.toolUpdates() == ToolUpdateMode.UNVERIFIED ? defaults.toolUpdates() : configured.toolUpdates(),
                configured.retainToolsWithToolChoiceNone() == CapabilitySupportStatus.UNVERIFIED
                        ? defaults.retainToolsWithToolChoiceNone() : configured.retainToolsWithToolChoiceNone());
    }

    /** 校验响应、流式、原生工具调用与 reasoning 的完整调用能力。 */
    public void validateCapabilities(ResolvedProfile profile,
            Set<ResponseFormatKind> responseFormats, boolean streaming,
            boolean toolCalling, boolean reasoning) {
        Objects.requireNonNull(profile, "profile");
        Set<ResponseFormatKind> formats = Set.copyOf(
                Objects.requireNonNull(responseFormats, "responseFormats"));
        if (formats.contains(ResponseFormatKind.JSON_OBJECT)) {
            requireSupported(profile.capabilities().jsonObject(), "json_object");
        }
        if (formats.contains(ResponseFormatKind.JSON_SCHEMA)) {
            requireSupported(profile.capabilities().strictJsonSchema(),
                    "strict_json_schema");
        }
        if (streaming) {
            requireSupported(profile.capabilities().streaming(), "streaming");
        }
        if (toolCalling) {
            requireSupported(profile.capabilities().toolCalling(), "tool_calling");
        }
        if (reasoning) {
            requireSupported(profile.capabilities().reasoning(), "reasoning");
        }
    }

    /** 解析显式模型发现请求；只有声明发现方案的可配置服务可以访问远端。 */
    public ResolvedDiscovery resolveDiscovery(DiscoverySelection selection) {
        Objects.requireNonNull(selection, "selection");
        ModelProvider provider = provider(selection.providerId());
        ServiceProfile service = service(provider, selection.serviceId());
        if (!service.configurable()) {
            throw rejected("model_service_not_configurable");
        }
        if (!service.profileVersion().equals(selection.profileVersion())) {
            throw rejected("model_service_profile_version_unknown");
        }
        ModelDiscoveryProfile discovery = service.modelDiscovery();
        if (discovery == null) {
            throw rejected("model_discovery_not_supported");
        }
        var authentication = selection.authenticationScheme();
        if (authentication == null) {
            if (discovery.authenticationSchemes().size() != 1) throw rejected("model_discovery_authentication_required");
            authentication = discovery.authenticationSchemes().iterator().next();
        }
        if (!discovery.authenticationSchemes().contains(authentication)) {
            throw rejected("model_authentication_not_allowed");
        }
        String endpoint = provider.resolveEndpoint(service, selection.endpoint());
        return new ResolvedDiscovery(selection.providerId(), service.serviceId(),
                service.profileVersion(), authentication, endpoint,
                provider.resolveDiscoveryEndpoint(service, discovery, endpoint), discovery.dialectVersion(),
                discovery.verificationStatus());
    }

    private ModelProvider provider(String providerId) {
        ModelProvider provider = providers.get(providerId);
        if (provider == null) {
            throw rejected("model_provider_unknown");
        }
        return provider;
    }

    private ServiceProfile service(ModelProvider provider, String serviceId) {
        return provider.preset().services().stream()
                .filter(candidate -> candidate.serviceId().equals(serviceId))
                .findFirst().orElseThrow(() -> rejected("model_service_unknown"));
    }

    private static String normalizedDiscoveryPath(String value) {
        requireText(value, "discovery_path");
        if (!value.startsWith("/") || value.startsWith("//")
                || !value.matches("/[A-Za-z0-9._~/-]+") || value.contains("..")) {
            throw rejected("model_discovery_path_invalid");
        }
        return value;
    }

    private static void requireUnique(List<String> values, String reason) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(reason);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw rejected("model_" + name + "_missing");
        }
        if (value.chars().anyMatch(character ->
                Character.isISOControl((char) character))) {
            throw rejected("model_" + name + "_invalid");
        }
        return value;
    }

    private static void requireSupported(SupportLevel support, String capability) {
        Objects.requireNonNull(support, "support");
        if (support == SupportLevel.SUPPORTED) {
            return;
        }
        throw rejected("model_" + capability + "_"
                + (support == SupportLevel.UNVERIFIED ? "unverified" : "unsupported"));
    }

    private static ProviderProfileRejectedException rejected(String reason) {
        return new ProviderProfileRejectedException(reason);
    }

    public enum ProviderCategory {
        DOMESTIC_DIRECT,
        CLOUD_PLATFORM,
        CUSTOM_OR_SELF_HOSTED
    }

    public enum Availability {
        SUPPORTED,
        CONFIGURABLE_UNVERIFIED
    }

    public enum EndpointMode {
        FIXED,
        SCOPED_SUBDOMAIN,
        CUSTOM
    }

    public enum ModelProtocol {
        OPENAI_CHAT_COMPLETIONS("openai-chat-completions"),
        OPENAI_RESPONSES("openai-responses"),
        ANTHROPIC_MESSAGES("anthropic-messages"),
        BAILIAN_NATIVE("bailian-native"),
        QWEN_ASR_NATIVE("qwen-asr-native"),
        MINIMAX_NATIVE("minimax-native"),
        OPENAI_EMBEDDINGS("openai-embeddings"),
        OPENAI_RERANK("openai-rerank");

        private final String id;

        ModelProtocol(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static ModelProtocol fromId(String id) {
            return EnumSet.allOf(ModelProtocol.class).stream()
                    .filter(protocol -> protocol.id.equals(id))
                    .findFirst().orElseThrow(() -> rejected("model_protocol_unknown"));
        }
    }

    public enum AuthenticationScheme {
        BEARER_API_KEY("bearer-api-key"),
        ANTHROPIC_API_KEY("anthropic-api-key");

        private final String id;

        AuthenticationScheme(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static AuthenticationScheme fromId(String id) {
            return EnumSet.allOf(AuthenticationScheme.class).stream()
                    .filter(scheme -> scheme.id.equals(id))
                    .findFirst().orElseThrow(() -> rejected("model_authentication_unknown"));
        }
    }

    public enum SupportLevel {
        SUPPORTED,
        UNSUPPORTED,
        UNVERIFIED
    }

    public enum VerificationStatus {
        VERIFIED,
        UNVERIFIED
    }

    /** 能力状态按服务协议保存；发现或手工模型名不能把未核实能力变成支持。 */
    public record CapabilitySupport(
            SupportLevel jsonObject,
            SupportLevel strictJsonSchema,
            SupportLevel toolCalling,
            SupportLevel streaming,
            SupportLevel reasoning) {
        public CapabilitySupport {
            Objects.requireNonNull(jsonObject, "jsonObject");
            Objects.requireNonNull(strictJsonSchema, "strictJsonSchema");
            Objects.requireNonNull(toolCalling, "toolCalling");
            Objects.requireNonNull(streaming, "streaming");
            Objects.requireNonNull(reasoning, "reasoning");
        }
    }

    /** 一个服务协议允许的认证及保守能力组合。 */
    public record ProtocolProfile(
            ModelProtocol protocol,
            String dialectVersion,
            Set<AuthenticationScheme> authenticationSchemes,
            CapabilitySupport capabilities,
            VerificationStatus verificationStatus,
            PromptPrefixCapabilities prefixCapabilities) {
        /** 没有核实前缀协议的服务以未知能力关闭优化，供普通协议目录构造使用。 */
        public ProtocolProfile(ModelProtocol protocol, String dialectVersion,
                Set<AuthenticationScheme> authenticationSchemes, CapabilitySupport capabilities,
                VerificationStatus verificationStatus) {
            this(protocol, dialectVersion, authenticationSchemes, capabilities, verificationStatus,
                    PromptPrefixCapabilities.unverified());
        }

        public ProtocolProfile {
            Objects.requireNonNull(protocol, "protocol");
            requireText(dialectVersion, "dialect_version");
            authenticationSchemes = Set.copyOf(authenticationSchemes);
            if (authenticationSchemes.isEmpty()) {
                throw new IllegalArgumentException("model_authentication_missing");
            }
            Objects.requireNonNull(capabilities, "capabilities");
            Objects.requireNonNull(verificationStatus, "verificationStatus");
            Objects.requireNonNull(prefixCapabilities, "prefixCapabilities");
        }
    }

    /** 服务的可选模型发现方案；返回结果仅用于配置候选。 */
    public record ModelDiscoveryProfile(String path, String dialectVersion,
            Set<AuthenticationScheme> authenticationSchemes,
            VerificationStatus verificationStatus) {
        public ModelDiscoveryProfile {
            path = normalizedDiscoveryPath(path);
            requireText(dialectVersion, "discovery_dialect_version");
            authenticationSchemes = Set.copyOf(authenticationSchemes);
            if (authenticationSchemes.isEmpty()) {
                throw new IllegalArgumentException("model_discovery_authentication_missing");
            }
            Objects.requireNonNull(verificationStatus, "verificationStatus");
        }
    }

    public enum ServiceRegion { CHINA_MAINLAND, LOCAL, UNVERIFIED }

    public enum ModelKind {
        LLM, EMBEDDING, RERANK, IMAGE_GENERATION, VIDEO_GENERATION, SPEECH_RECOGNITION, SPEECH_SYNTHESIS
    }

    public record ServiceProfile(String serviceId, String displayName,
            String profileVersion, Availability availability, String endpoint,
            EndpointMode endpointMode, ModelDiscoveryProfile modelDiscovery,
            List<ProtocolProfile> protocols, ServiceRegion region, Set<ModelKind> modelKinds) {
        /** 未核实服务仅可供协议装配，不能由宿主作为境内服务启用。 */
        public ServiceProfile(String serviceId, String displayName, String profileVersion,
                Availability availability, String endpoint, EndpointMode endpointMode,
                ModelDiscoveryProfile modelDiscovery, List<ProtocolProfile> protocols) {
            this(serviceId, displayName, profileVersion, availability, endpoint, endpointMode,
                    modelDiscovery, protocols, ServiceRegion.UNVERIFIED, Set.of(ModelKind.LLM));
        }
        public ServiceProfile {
            requireText(serviceId, "service_id");
            requireText(displayName, "service_display_name");
            requireText(profileVersion, "service_profile_version");
            Objects.requireNonNull(availability, "availability");
            Objects.requireNonNull(endpointMode, "endpointMode");
            Objects.requireNonNull(region, "region");
            modelKinds = Set.copyOf(modelKinds);
            if (modelKinds.isEmpty()) throw new IllegalArgumentException("model_kind_missing");
            if (endpointMode == EndpointMode.CUSTOM) {
                if (endpoint != null && !endpoint.isBlank()) {
                    throw new IllegalArgumentException("model_service_endpoint_mode_invalid");
                }
            } else {
                requireText(endpoint, "service_endpoint");
                if (endpointMode == EndpointMode.FIXED) {
                    ProviderEndpoints.normalized(endpoint);
                }
            }
            protocols = List.copyOf(protocols);
            if ((availability == Availability.SUPPORTED
                    || availability == Availability.CONFIGURABLE_UNVERIFIED)
                    && protocols.isEmpty()) {
                throw new IllegalArgumentException("model_protocol_missing");
            }
            requireUnique(protocols.stream()
                    .map(value -> value.protocol().id()).toList(),
                    "model_protocol_duplicate");
            if (modelDiscovery != null) {
                Set<AuthenticationScheme> protocolAuthentication = protocols.stream()
                        .flatMap(value -> value.authenticationSchemes().stream())
                        .collect(java.util.stream.Collectors.toSet());
                if (!protocolAuthentication.containsAll(
                        modelDiscovery.authenticationSchemes())) {
                    throw new IllegalArgumentException(
                            "model_discovery_authentication_not_declared");
                }
            }
        }

        public boolean configurable() {
            return availability == Availability.SUPPORTED
                    || availability == Availability.CONFIGURABLE_UNVERIFIED;
        }

        /** 固定地址外的两种模式都需要宿主提供配置值。 */
        public boolean customEndpoint() {
            return endpointMode != EndpointMode.FIXED;
        }
    }

    public record ProviderPreset(String providerId, String displayName,
            ProviderCategory category, List<ServiceProfile> services) {
        public ProviderPreset {
            requireText(providerId, "provider_id");
            requireText(displayName, "provider_display_name");
            Objects.requireNonNull(category, "category");
            services = List.copyOf(services);
            if (services.isEmpty()) {
                throw new IllegalArgumentException("model_service_missing");
            }
            requireUnique(services.stream().map(ServiceProfile::serviceId).toList(),
                    "model_service_duplicate");
        }
    }

    public record Selection(String providerId, String serviceId,
            String profileVersion, String modelId, ModelProtocol protocol,
            AuthenticationScheme authenticationScheme, String endpoint) {
        public Selection {
            requireText(providerId, "provider_id");
            requireText(serviceId, "service_id");
            requireText(profileVersion, "service_profile_version");
            requireText(modelId, "model_id");
            Objects.requireNonNull(protocol, "protocol");
            Objects.requireNonNull(authenticationScheme, "authenticationScheme");
        }
    }

    /** authenticationScheme 留空时使用服务声明的唯一发现认证，与推理协议无关。 */
    public record DiscoverySelection(String providerId, String serviceId,
            String profileVersion, AuthenticationScheme authenticationScheme,
            String endpoint) {
        public DiscoverySelection {
            requireText(providerId, "provider_id");
            requireText(serviceId, "service_id");
            requireText(profileVersion, "service_profile_version");
        }
    }

    /** 可进入租户配置与冻结绑定的完整解析结果。 */
    public record ResolvedProfile(String providerId, String serviceId,
            String profileVersion, String modelId, ModelProtocol protocol,
            String dialectVersion, AuthenticationScheme authenticationScheme,
            String endpoint, CapabilitySupport capabilities,
            VerificationStatus verificationStatus,
            PromptPrefixCapabilities prefixCapabilities) {
        public ResolvedProfile {
            requireText(providerId, "provider_id");
            requireText(serviceId, "service_id");
            requireText(profileVersion, "service_profile_version");
            requireText(modelId, "model_id");
            Objects.requireNonNull(protocol, "protocol");
            requireText(dialectVersion, "dialect_version");
            Objects.requireNonNull(authenticationScheme, "authenticationScheme");
            ProviderEndpoints.normalized(endpoint);
            Objects.requireNonNull(capabilities, "capabilities");
            Objects.requireNonNull(verificationStatus, "verificationStatus");
            Objects.requireNonNull(prefixCapabilities, "prefixCapabilities");
        }
    }

    /** 可交给模型发现协议适配器的完整解析结果。 */
    public record ResolvedDiscovery(String providerId, String serviceId,
            String profileVersion, AuthenticationScheme authenticationScheme,
            String endpoint, String modelsEndpoint, String dialectVersion,
            VerificationStatus verificationStatus) {
        public ResolvedDiscovery {
            requireText(providerId, "provider_id");
            requireText(serviceId, "service_id");
            requireText(profileVersion, "service_profile_version");
            Objects.requireNonNull(authenticationScheme, "authenticationScheme");
            ProviderEndpoints.normalized(endpoint);
            ProviderEndpoints.normalized(modelsEndpoint);
            requireText(dialectVersion, "discovery_dialect_version");
            Objects.requireNonNull(verificationStatus, "verificationStatus");
        }
    }

    /** 稳定的本地拒绝，不携带供应商响应正文。 */
    public static final class ProviderProfileRejectedException extends IllegalArgumentException {
        public ProviderProfileRejectedException(String reason) {
            super(reason);
        }
    }
}
