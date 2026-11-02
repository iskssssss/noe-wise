package com.skillforge.runtime.adapter.model.provider;

import com.skillforge.runtime.adapter.model.openai.CustomOpenAiCompatibleModelProvider;
import com.skillforge.runtime.adapter.model.aliyun_bailian.AliyunBailianModelProvider;
import com.skillforge.runtime.adapter.model.deepseek.DeepSeekModelProvider;
import com.skillforge.runtime.adapter.model.minimax.MiniMaxModelProvider;
import com.skillforge.runtime.adapter.model.moonshot.MoonshotModelProvider;
import com.skillforge.runtime.adapter.model.stepfun.StepFunModelProvider;
import com.skillforge.runtime.adapter.model.tencent.TencentTokenHubModelProvider;
import com.skillforge.runtime.adapter.model.xiaomi.XiaomiModelProvider;
import com.skillforge.runtime.adapter.model.zhipu.ZhipuModelProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.Availability;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.DiscoverySelection;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProviderProfileRejectedException;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelKind;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ServiceRegion;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.SupportLevel;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.Selection;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind;
import com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus;
import com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities;
import com.skillforge.runtime.api.port.ModelPort.ToolUpdateMode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelProviderCatalogTest {

    private final ModelProviderCatalog catalog = ModelProviderCatalog.standard();

    @Test void customResponsesExposesImageEncodingWithoutVerifyingSpecificModels() {
        var upper = catalog.protocolImageSupport("custom", "custom-openai-compatible", ModelProtocol.OPENAI_RESPONSES);
        var model = catalog.imageSupport("custom", "custom-openai-compatible", ModelProtocol.OPENAI_RESPONSES, "account-model");
        assertEquals(SupportLevel.SUPPORTED, upper.userImages());
        assertEquals(SupportLevel.SUPPORTED, upper.toolImages());
        assertEquals(SupportLevel.UNVERIFIED, model.userImages());
        assertEquals(SupportLevel.UNVERIFIED, model.toolImages());
        assertEquals(null, model.defaults());
        assertEquals(SupportLevel.SUPPORTED, catalog.protocolImageSupport("custom", "custom-openai-compatible",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS).userImages());
        assertEquals(SupportLevel.SUPPORTED, catalog.protocolImageSupport("deepseek", "deepseek-api",
                ModelProtocol.ANTHROPIC_MESSAGES).userImages());
    }

    /** 品牌和兼容协议不授予前缀更新能力；只有核实的服务、型号可保留声明并禁用调用。 */
    @Test void freezesExactChatPrefixCapabilitiesWithoutBorrowingMessagesSemantics() {
        var known = catalog.resolve(new Selection("deepseek", "deepseek-api", "2026-09-13", "deepseek-flash",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, null));
        assertEquals(CapabilitySupportStatus.SUPPORTED, known.prefixCapabilities().retainToolsWithToolChoiceNone());
        assertEquals(CapabilitySupportStatus.UNVERIFIED, known.prefixCapabilities().systemUpdatesInHistory());
        assertEquals(ToolUpdateMode.NONE, known.prefixCapabilities().toolUpdates());
        var unknown = catalog.resolve(new Selection("deepseek", "deepseek-api", "2026-09-13", "new-model",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, null));
        assertEquals(known.prefixCapabilities(), unknown.prefixCapabilities());
        var custom = catalog.resolve(new Selection("custom", "custom-openai-compatible", "custom-v1", "deepseek-flash",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, "http://127.0.0.1:11434/v1"));
        assertEquals(CapabilitySupportStatus.SUPPORTED, custom.prefixCapabilities().retainToolsWithToolChoiceNone());
        catalog.validatePrefixCapabilities(known, known.prefixCapabilities());
        catalog.validatePrefixCapabilities(known, PromptPrefixCapabilities.unverified());
        catalog.validatePrefixCapabilities(custom, known.prefixCapabilities());
        var adopted = new PromptPrefixCapabilities(CapabilitySupportStatus.SUPPORTED, ToolUpdateMode.NONE,
                CapabilitySupportStatus.UNSUPPORTED);
        assertEquals(adopted, catalog.effectivePrefixCapabilities(custom, adopted));
        assertThrows(ProviderProfileRejectedException.class, () -> catalog.validatePrefixCapabilities(known,
                new PromptPrefixCapabilities(CapabilitySupportStatus.SUPPORTED, ToolUpdateMode.ADD_REMOVE,
                        CapabilitySupportStatus.SUPPORTED)));
        var runtime = ModelProviderRuntime.standard();
        var wrongService = new ModelProvider.InvocationProfile("deepseek-messages", "2026-09-13",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, "openai-compatible-chat-v1");
        assertEquals(PromptPrefixCapabilities.unverified(), runtime.prefixCapabilities("deepseek", wrongService, "deepseek-flash"));
        var wrongVersion = new ModelProvider.InvocationProfile("deepseek-api", "unknown",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, "openai-compatible-chat-v1");
        assertEquals(PromptPrefixCapabilities.unverified(), runtime.prefixCapabilities("deepseek", wrongVersion, "deepseek-flash"));
    }

    @Test void workspaceAndCustomExposeImplementedProtocolsWithoutClaimingEndpointVerification() {
        var workspace = catalog.resolve(new Selection("aliyun-bailian", "bailian-cn-beijing-workspace",
                "2026-09-13-workspace-v1", "new-account-model", ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                AuthenticationScheme.BEARER_API_KEY, "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"));
        catalog.validateCapabilities(workspace, java.util.Set.of(ResponseFormatKind.TEXT), true, true, true);
        assertEquals(ModelProviderCatalog.VerificationStatus.UNVERIFIED, workspace.verificationStatus());
        var custom = catalog.resolve(new Selection("custom", "custom-openai-compatible", "custom-v1", "new-account-model",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, "http://127.0.0.1:11434/v1"));
        catalog.validateCapabilities(custom, java.util.Set.of(ResponseFormatKind.TEXT), true, true, true);
        assertEquals(ModelProviderCatalog.VerificationStatus.UNVERIFIED, custom.verificationStatus());
    }

    @Test
    void keepsEveryProviderInAnIndependentImplementationClass() {
        var providers = ModelProviders.standard();

        assertEquals(List.of(
                        DeepSeekModelProvider.class,
                        AliyunBailianModelProvider.class,
                        MoonshotModelProvider.class,
                        MiniMaxModelProvider.class,
                        ZhipuModelProvider.class,
                        StepFunModelProvider.class,
                        TencentTokenHubModelProvider.class,
                        XiaomiModelProvider.class,
                        CustomOpenAiCompatibleModelProvider.class),
                providers.stream().map(Object::getClass).toList());
        for (ModelProvider provider : providers) {
            var isolated = new ModelProviderCatalog(List.of(provider));
            assertEquals(List.of(provider.preset()), isolated.presets());
        }
    }

    @Test
    void keepsProviderRequestDifferencesInsideTheirOwnImplementations() {
        var deepSeek = new ModelProviderRuntime(List.of(new DeepSeekModelProvider()));
        var custom = new ModelProviderRuntime(List.of(new CustomOpenAiCompatibleModelProvider()));

        assertTrue(deepSeek.chatCompletionsPolicy(
                "deepseek", ReasoningPolicy.DISABLED).disableThinking());
        assertEquals(false, custom.chatCompletionsPolicy(
                "custom", ReasoningPolicy.DISABLED).disableThinking());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> custom.chatCompletionsPolicy(
                        "deepseek", ReasoningPolicy.DISABLED));
        assertEquals("model_provider_unknown", failure.getMessage());
    }

    @Test
    void keepsProviderServiceAndProtocolAsSeparateIdentities() {
        assertEquals("openai-chat-completions",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS.id());
        var bailian = catalog.presets().stream()
                .filter(provider -> provider.providerId().equals("aliyun-bailian"))
                .findFirst().orElseThrow();
        assertEquals(List.of("bailian-cn-beijing", "bailian-cn-beijing-workspace", "bailian-cn-beijing-explicit-cache"),
                bailian.services().stream()
                        .filter(service -> service.modelKinds().contains(ModelKind.LLM))
                        .map(ModelProviderCatalog.ServiceProfile::serviceId).toList());
        assertEquals(Availability.SUPPORTED,
                bailian.services().getFirst().availability());
        assertTrue(bailian.services().stream().filter(service -> service.modelKinds().contains(ModelKind.LLM)).skip(1)
                .allMatch(service -> service.availability()
                        == Availability.CONFIGURABLE_UNVERIFIED));
    }

    @Test
    void unsupportedBrandsAndOverseasBailianServicesCannotBeResolved() {
        for (String provider : List.of("openai", "opencode"))
            assertRejected("model_provider_unknown", new Selection(provider, "former-service", "former-version", "model",
                    ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, null));
        for (String service : List.of("bailian-intl-singapore", "bailian-us-virginia", "bailian-cn-hongkong", "bailian-eu-frankfurt", "bailian-jp-tokyo"))
            assertRejected("model_service_unknown", new Selection("aliyun-bailian", service, "2026-09-13-workspace-v1", "model",
                    ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, null));
    }

    @Test void addedDomesticProvidersResolveTheirOwnMainlandLlmServices() {
        for (String providerId : List.of("moonshot", "minimax", "zhipu", "stepfun", "tencent-tokenhub", "xiaomi")) {
            var preset = catalog.presets().stream().filter(value -> value.providerId().equals(providerId)).findFirst().orElseThrow();
            var languageServices = preset.services().stream().filter(service -> service.modelKinds().contains(ModelKind.LLM)).toList();
            assertFalse(languageServices.isEmpty());
            for (var service : languageServices) {
                assertEquals(ServiceRegion.CHINA_MAINLAND, service.region());
                assertTrue(service.modelKinds().contains(ModelKind.LLM));
                var protocol = service.protocols().stream().filter(value -> value.protocol() == ModelProtocol.OPENAI_CHAT_COMPLETIONS)
                        .findFirst().orElseThrow();
                var resolved = catalog.resolve(new Selection(providerId, service.serviceId(), service.profileVersion(), "account-visible-model",
                        protocol.protocol(), AuthenticationScheme.BEARER_API_KEY, null));
                assertEquals(service.endpoint(), resolved.endpoint());
                assertEquals(providerId, resolved.providerId());
                assertEquals("account-visible-model", resolved.modelId());
            }
        }
        var speech = catalog.serviceProfile("minimax", "minimax-cn-tts");
        assertEquals(Set.of(ModelKind.SPEECH_SYNTHESIS), speech.modelKinds());
        assertEquals(ServiceRegion.CHINA_MAINLAND, speech.region());
        assertEquals("https://api.minimax.cn/v1", speech.endpoint());
        assertEquals(List.of(ModelProtocol.MINIMAX_NATIVE), speech.protocols().stream()
                .map(ModelProviderCatalog.ProtocolProfile::protocol).toList());
    }

    @Test void bailianMediaServicesDeclareTheirOwnKindsAndDoNotBorrowLlmCapabilities() {
        var bailian = catalog.presets().stream().filter(value -> value.providerId().equals("aliyun-bailian")).findFirst().orElseThrow();
        var media = bailian.services().stream().filter(service -> !service.modelKinds().contains(ModelKind.LLM)).toList();
        assertEquals(Set.of(ModelKind.IMAGE_GENERATION, ModelKind.VIDEO_GENERATION, ModelKind.SPEECH_RECOGNITION,
                ModelKind.SPEECH_SYNTHESIS, ModelKind.EMBEDDING, ModelKind.RERANK), media.stream()
                .flatMap(service -> service.modelKinds().stream()).collect(java.util.stream.Collectors.toSet()));
        for (var service : media) {
            assertEquals(ServiceRegion.CHINA_MAINLAND, service.region());
            for (var protocol : service.protocols()) {
                assertFalse(Set.of(ModelProtocol.OPENAI_CHAT_COMPLETIONS, ModelProtocol.OPENAI_RESPONSES,
                        ModelProtocol.ANTHROPIC_MESSAGES).contains(protocol.protocol()));
                assertEquals(SupportLevel.UNSUPPORTED, protocol.capabilities().jsonObject());
                assertEquals(SupportLevel.UNSUPPORTED, protocol.capabilities().strictJsonSchema());
                assertEquals(SupportLevel.UNSUPPORTED, protocol.capabilities().toolCalling());
                assertEquals(SupportLevel.UNSUPPORTED, protocol.capabilities().reasoning());
            }
        }
    }

    @Test void nativeBailianMediaAcceptsGlobalDefaultAndBeijingWorkspaceWithoutChangingItsService() {
        for (String id : List.of("bailian-cn-beijing-image", "bailian-cn-beijing-video", "bailian-cn-beijing-asr",
                "bailian-cn-beijing-qwen-asr", "bailian-cn-beijing-tts")) {
            var service = catalog.serviceProfile("aliyun-bailian", id);
            assertEquals(ModelProviderCatalog.EndpointMode.SCOPED_SUBDOMAIN, service.endpointMode());
            assertTrue(service.customEndpoint());
            assertEquals("https://dashscope.aliyuncs.com", service.endpoint());
            var protocol = service.protocols().getFirst().protocol();
            for (String endpoint : new String[] { null, "https://dashscope.aliyuncs.com", "https://dashscope.aliyuncs.com/api/v1",
                    "https://workspace-123.cn-beijing.maas.aliyuncs.com", "https://workspace-123.cn-beijing.maas.aliyuncs.com/api/v1",
                    "https://workspace-123.cn-beijing.maas.aliyuncs.com/api/v1/" }) {
                var resolved = catalog.resolve(new Selection("aliyun-bailian", id, service.profileVersion(), "account-visible-model",
                        protocol, AuthenticationScheme.BEARER_API_KEY, endpoint));
                String expected = endpoint == null ? service.endpoint() : endpoint.replaceAll("/$", "");
                assertEquals(expected, resolved.endpoint());
                assertEquals(id, resolved.serviceId()); assertEquals(protocol, resolved.protocol());
                assertEquals("account-visible-model", resolved.modelId());
            }
        }
        for (String id : List.of("bailian-cn-beijing-embedding", "bailian-cn-beijing-rerank")) {
            var service = catalog.serviceProfile("aliyun-bailian", id);
            assertEquals(ModelProviderCatalog.EndpointMode.FIXED, service.endpointMode()); assertFalse(service.customEndpoint());
            var resolved = catalog.resolve(new Selection("aliyun-bailian", id, service.profileVersion(), "account-visible-model",
                    service.protocols().getFirst().protocol(), AuthenticationScheme.BEARER_API_KEY, null));
            assertEquals(id.endsWith("embedding") ? "https://dashscope.aliyuncs.com/compatible-mode/v1"
                    : "https://dashscope.aliyuncs.com", resolved.endpoint());
        }
    }

    @Test void nativeBailianMediaRejectsForeignNestedAndNonNativeEndpointRoots() {
        for (String id : List.of("bailian-cn-beijing-image", "bailian-cn-beijing-video", "bailian-cn-beijing-asr",
                "bailian-cn-beijing-qwen-asr", "bailian-cn-beijing-tts")) {
            var service = catalog.serviceProfile("aliyun-bailian", id);
            for (String endpoint : List.of("https://dashscope-intl.aliyuncs.com",
                    "https://workspace.ap-southeast-1.maas.aliyuncs.com/api/v1", "https://workspace.cn-hongkong.maas.aliyuncs.com/api/v1",
                    "https://nested.workspace.cn-beijing.maas.aliyuncs.com/api/v1", "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "https://workspace.cn-beijing.maas.aliyuncs.com/api/v1/tasks/task-id",
                    "https://workspace.cn-beijing.maas.aliyuncs.com/%61pi/v1", "http://dashscope.aliyuncs.com",
                    "https://workspace.cn-beijing.maas.aliyuncs.com:8443/api/v1", "https://user:secret@dashscope.aliyuncs.com",
                    "https://workspace.cn-beijing.maas.aliyuncs.com/api/v1?region=cn-beijing", "https://dashscope.aliyuncs.com#fragment")) {
                assertThrows(ProviderProfileRejectedException.class, () -> catalog.resolve(new Selection("aliyun-bailian", id,
                        service.profileVersion(), "account-visible-model", service.protocols().getFirst().protocol(),
                        AuthenticationScheme.BEARER_API_KEY, endpoint)), endpoint);
            }
        }
    }

    @Test
    void acceptsExplicitModelNamesAndResolvesEndpointsWithoutGuessing() {
        var deepSeek = catalog.resolve(new Selection(
                "deepseek", "deepseek-api", "2026-09-13", "account-visible-model",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                AuthenticationScheme.BEARER_API_KEY, null));
        var custom = catalog.resolve(new Selection(
                "custom", "custom-openai-compatible", "custom-v1", "local-model",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                AuthenticationScheme.BEARER_API_KEY, "http://127.0.0.1:11434/v1/"));

        assertEquals("https://api.deepseek.com", deepSeek.endpoint());
        assertEquals("account-visible-model", deepSeek.modelId());
        assertEquals("http://127.0.0.1:11434/v1", custom.endpoint());
        assertEquals("openai-compatible-chat-v1", custom.dialectVersion());
    }

    @Test
    void resolvesDeepSeekAndBailianModelDiscoveryEndpoints() {
        var deepSeek = catalog.resolveDiscovery(new DiscoverySelection(
                "deepseek", "deepseek-api", "2026-09-13",
                AuthenticationScheme.BEARER_API_KEY, null));
        var bailian = catalog.resolveDiscovery(new DiscoverySelection(
                "aliyun-bailian", "bailian-cn-beijing",
                "2026-09-15-global-v1",
                AuthenticationScheme.BEARER_API_KEY, null));

        assertEquals("https://api.deepseek.com/models", deepSeek.modelsEndpoint());
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/models",
                bailian.modelsEndpoint());
        assertEquals("openai-model-list-v1", bailian.dialectVersion());
    }

    @Test
    void selectsDiscoveryAuthenticationIndependentlyOfInferenceProtocols() {
        var target = catalog.resolveDiscovery(new DiscoverySelection(
                "deepseek", "deepseek-api", "2026-09-13", null, null));
        assertEquals(AuthenticationScheme.BEARER_API_KEY, target.authenticationScheme());
        assertEquals("https://api.deepseek.com/models", target.modelsEndpoint());
    }

    @Test
    void constrainsBailianCredentialsToTheBeijingWorkspace() {
        var profile = catalog.resolve(new Selection(
                "aliyun-bailian", "bailian-cn-beijing-workspace",
                "2026-09-13-workspace-v1", "account-visible-model",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                AuthenticationScheme.BEARER_API_KEY,
                "https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/"));

        assertEquals(
                "https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                profile.endpoint());
        assertRejected("model_service_endpoint_mismatch", new Selection(
                "aliyun-bailian", "bailian-cn-beijing-workspace",
                "2026-09-13-workspace-v1", "account-visible-model",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                AuthenticationScheme.BEARER_API_KEY,
                "https://workspace-123.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"));
        assertRejected("model_service_endpoint_scope_invalid", new Selection(
                "aliyun-bailian", "bailian-cn-beijing-workspace",
                "2026-09-13-workspace-v1", "account-visible-model",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                AuthenticationScheme.BEARER_API_KEY,
                "https://attacker.workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"));
    }

    @Test
    void validatesFirstBatchCapabilitiesAndRejectsUnsupportedStrictSchema() {
        var deepSeek = catalog.resolve(new Selection(
                "deepseek", "deepseek-api", "2026-09-13", "account-visible-model",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                AuthenticationScheme.BEARER_API_KEY, null));

        catalog.validateCapabilities(deepSeek,
                Set.of(ResponseFormatKind.TEXT, ResponseFormatKind.JSON_OBJECT),
                true, true, false);
        var strict = assertThrows(ProviderProfileRejectedException.class,
                () -> catalog.validateCapabilities(deepSeek,
                        Set.of(ResponseFormatKind.JSON_SCHEMA), false, false, false));

        assertEquals("model_strict_json_schema_unsupported", strict.getMessage());
    }

    @Test
    void rejectsUnlistedProtocolAuthenticationAndEndpoints() {
        assertRejected("model_protocol_not_allowed", new Selection(
                "deepseek", "deepseek-api", "2026-09-13", "deepseek-chat",
                ModelProtocol.OPENAI_RESPONSES,
                AuthenticationScheme.BEARER_API_KEY, null));
        assertRejected("model_authentication_not_allowed", new Selection(
                "deepseek", "deepseek-api", "2026-09-13", "deepseek-chat",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                AuthenticationScheme.ANTHROPIC_API_KEY, null));
        assertRejected("model_service_endpoint_mismatch", new Selection(
                "deepseek", "deepseek-api", "2026-09-13", "deepseek-chat",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                AuthenticationScheme.BEARER_API_KEY, "https://attacker.example/v1"));
    }

    @Test
    void customServiceUsesItsExplicitEndpointForOptionalModelDiscovery() {
        var discovery = catalog.resolveDiscovery(new DiscoverySelection(
                "custom", "custom-openai-compatible", "custom-v1",
                AuthenticationScheme.BEARER_API_KEY,
                "http://127.0.0.1:11434/v1"));

        assertEquals("http://127.0.0.1:11434/v1/models", discovery.modelsEndpoint());
    }

    @Test void customInferenceAndDiscoveryRejectOverseasEndpointsBeforeAnyRequest() {
        for (String endpoint : List.of("https://api.openai.com/v1", "https://api.moonshot.ai/v1",
                "https://tokenhub-intl.tencentmaas.com/v1", "https://203.0.113.7/v1")) {
            assertRejected("model_local_endpoint_required", new Selection("custom", "custom-openai-compatible", "custom-v1", "model",
                    ModelProtocol.OPENAI_CHAT_COMPLETIONS, AuthenticationScheme.BEARER_API_KEY, endpoint));
            var failure = assertThrows(ProviderProfileRejectedException.class, () -> catalog.resolveDiscovery(
                    new DiscoverySelection("custom", "custom-openai-compatible", "custom-v1", AuthenticationScheme.BEARER_API_KEY, endpoint)));
            assertEquals("model_local_endpoint_required", failure.getMessage());
        }
    }

    private void assertRejected(String reason, Selection selection) {
        var failure = assertThrows(ProviderProfileRejectedException.class,
                () -> catalog.resolve(selection));
        assertEquals(reason, failure.getMessage());
    }
}
