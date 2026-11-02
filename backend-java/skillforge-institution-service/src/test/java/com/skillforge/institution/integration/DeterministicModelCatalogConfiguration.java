package com.skillforge.institution.integration;

import com.skillforge.runtime.adapter.model.deepseek.DeepSeekModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.*;
import java.util.List;
import java.util.Set;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 为测试拥有的境内loopback HTTP模型明确声明地域、LLM类型及测试协议能力，保留生产目录校验。 */
@TestConfiguration(proxyBeanMethods = false)
class DeterministicModelCatalogConfiguration {
    @Bean
    ModelProviderCatalog deterministicModelCatalog() {
        var capabilities = new CapabilitySupport(SupportLevel.SUPPORTED, SupportLevel.UNSUPPORTED,
                SupportLevel.SUPPORTED, SupportLevel.SUPPORTED, SupportLevel.UNSUPPORTED);
        var protocol = new ProtocolProfile(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "openai-compatible-chat-v1",
                Set.of(AuthenticationScheme.BEARER_API_KEY), capabilities, VerificationStatus.VERIFIED);
        var service = new ServiceProfile("custom-openai-compatible", "本地确定性模型", "custom-v1",
                Availability.SUPPORTED, null, EndpointMode.CUSTOM, null, List.of(protocol),
                ServiceRegion.CHINA_MAINLAND, Set.of(ModelKind.LLM));
        ModelProvider fixture = () -> new ProviderPreset("custom", "本地确定性模型",
                ProviderCategory.CUSTOM_OR_SELF_HOSTED, List.of(service));
        return new ModelProviderCatalog(List.of(fixture, new DeepSeekModelProvider()));
    }
}
