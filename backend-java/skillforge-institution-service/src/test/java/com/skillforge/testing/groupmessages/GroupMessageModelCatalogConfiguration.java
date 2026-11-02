package com.skillforge.testing.groupmessages;

import com.skillforge.runtime.adapter.model.deepseek.DeepSeekModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.*;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * GI-03父测试与owned子JVM显式共用的本地HTTP能力目录；位于正式应用扫描范围之外。
 * 仅由本测试Import/附加单class JAR加载，不证明真实厂商JSON能力或Memory业务验收。
 */
@Configuration(proxyBeanMethods = false)
public class GroupMessageModelCatalogConfiguration {
    public static final String ACTIVATION_MARKER = "GI03_DETERMINISTIC_MODEL_CATALOG_ACTIVE";

    @Bean
    @Primary
    ModelProviderCatalog groupMessageFixtureModelCatalog() {
        var capabilities = new CapabilitySupport(SupportLevel.SUPPORTED, SupportLevel.UNSUPPORTED,
                SupportLevel.SUPPORTED, SupportLevel.SUPPORTED, SupportLevel.UNSUPPORTED);
        var protocol = new ProtocolProfile(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "openai-compatible-chat-v1",
                Set.of(AuthenticationScheme.BEARER_API_KEY), capabilities, VerificationStatus.VERIFIED);
        var service = new ServiceProfile("custom-openai-compatible", "本地确定性模型", "custom-v1",
                Availability.SUPPORTED, null, EndpointMode.CUSTOM, null, List.of(protocol),
                ServiceRegion.LOCAL, Set.of(ModelKind.LLM));
        ModelProvider fixture = () -> new ProviderPreset("custom", "本地确定性模型",
                ProviderCategory.CUSTOM_OR_SELF_HOSTED, List.of(service));
        System.out.println(ACTIVATION_MARKER);
        return new ModelProviderCatalog(List.of(fixture, new DeepSeekModelProvider()));
    }
}
