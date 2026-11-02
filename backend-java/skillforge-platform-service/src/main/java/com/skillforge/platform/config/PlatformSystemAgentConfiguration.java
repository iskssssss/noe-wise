package com.skillforge.platform.config;

import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.port.MediaPort;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;

/** 平台系统智能体管理和固定模型任务的显式装配。 */
@Configuration
@Import({
    com.skillforge.core.chat.contract.ChatToolSchemaContributor.class,
    com.skillforge.core.capability.connector.PlatformToolCatalog.class,
    com.skillforge.core.capability.CapabilityProducerPublicationBridge.class,
    com.skillforge.core.capability.connector.PlatformToolDefinitionInitializer.class,
    com.skillforge.core.agent.systemagent.SystemAgentConfiguration.class,
    com.skillforge.core.agent.systemagent.SystemAgentService.class,
    com.skillforge.core.agent.systemagent.SystemAgentExecution.class,
    com.skillforge.core.agent.systemagent.PlatformAgentResources.class,
    com.skillforge.core.model.invocation.LlmService.class,
    com.skillforge.core.model.invocation.LlmHttpSupport.class,
    com.skillforge.core.model.invocation.DefaultModelInvocationPolicyChain.class,
    com.skillforge.core.shared.resilience.Resilience.class,
    com.skillforge.core.foundation.settings.OperationalRetryPolicyProvider.class,
    com.skillforge.core.billing.invocation.ExternalInvocationGuard.class,
    com.skillforge.core.foundation.runtimeconfig.ExecutionBudgetConfiguration.class,
    com.skillforge.core.billing.budget.AuxiliaryModelBudgets.class,
    com.skillforge.core.billing.invocation.ExternalInvocationLedger.class,
    com.skillforge.core.billing.metering.CostRecorder.class,
    com.skillforge.core.billing.pricing.PriceCalculator.class,
    com.skillforge.core.model.PlatformModelPricingService.class
})
public class PlatformSystemAgentConfiguration {
    /** 平台系统任务尚无产品会话媒体存储；图片请求明确拒绝，文本请求正常执行。 */
    @Bean
    MediaPort systemAgentMediaPort() {
        return new MediaPort() {
            @Override public byte[] read(SessionRef owner,
                    MediaRef ref) {
                throw new IllegalArgumentException("media_unavailable");
            }
            @Override public MediaRef prepare(
                    SessionRef owner, MediaRef ref,
                    ModelImagePolicy policy) {
                throw new IllegalArgumentException("media_unavailable");
            }
        };
    }
    @Bean
    ModelProviderCatalog systemAgentModelProviderCatalog() { return ModelProviderCatalog.standard(); }
    @Bean
    ModelProviderRuntime systemAgentModelProviderRuntime() {
        return ModelProviderRuntime.runtime(java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15)).build(),java.time.Clock.systemUTC(),16_000_000);
    }
}
