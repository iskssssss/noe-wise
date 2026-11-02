package com.skillforge.runtime.adapter.model.openai;

import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.CapabilitySupport;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelDiscoveryProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ProtocolProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.SupportLevel;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.VerificationStatus;
import java.util.Set;

/** OpenAI-compatible 协议族的不可变公共描述，不拥有任何供应商身份。 */
public final class OpenAiCompatibleProfiles {

    private static final CapabilitySupport CUSTOM_CHAT = new CapabilitySupport(
            SupportLevel.SUPPORTED,
            SupportLevel.UNSUPPORTED,
            SupportLevel.SUPPORTED,
            SupportLevel.SUPPORTED,
            SupportLevel.SUPPORTED);

    private OpenAiCompatibleProfiles() {
    }

    public static ProtocolProfile customChat() {
        return chat(CUSTOM_CHAT, VerificationStatus.UNVERIFIED);
    }

    public static ModelDiscoveryProfile unverifiedDiscovery() {
        return discovery(VerificationStatus.UNVERIFIED);
    }

    public static ModelDiscoveryProfile verifiedDiscovery() {
        return discovery(VerificationStatus.VERIFIED);
    }

    public static ProtocolProfile chat(
            CapabilitySupport capabilities, VerificationStatus status) {
        return new ProtocolProfile(
                ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                "openai-compatible-chat-v1",
                Set.of(AuthenticationScheme.BEARER_API_KEY),
                capabilities,
                status,
                new com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities(
                        com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus.UNVERIFIED,
                        com.skillforge.runtime.api.port.ModelPort.ToolUpdateMode.NONE,
                        com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus.SUPPORTED));
    }

    private static ModelDiscoveryProfile discovery(VerificationStatus status) {
        return new ModelDiscoveryProfile(
                "/models", "openai-model-list-v1",
                Set.of(AuthenticationScheme.BEARER_API_KEY), status);
    }
}
