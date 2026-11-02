package com.skillforge.core.agent.management;

import com.skillforge.core.agent.AgentService;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.shared.exception.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 发布只排除已知不可能的覆盖，不根据工具挂载猜测每轮文件、人工等待或协作需求。 */
class AgentConfigurationPolicyTest {
    @Test void publicationRejectsExplicitTokensAndActiveTimeThatCannotFitEvenTextClosing() {
        var policy=policy(120,120);
        assertThrows(ApiException.class,()->policy.requireReady(config(new AgentExecutionConfig(1,1,1,2048L,600L))));
        assertThrows(ApiException.class,()->policy.requireReady(config(new AgentExecutionConfig(1,1,1,50000L,120L))));
        assertDoesNotThrow(()->policy.requireReady(config(new AgentExecutionConfig(1,1,1,50000L,121L))));
    }
    @Test void effectiveCallTimeoutAndInheritedValuesAllowLegalDifferentUses() {
        var policy=policy(600,60);
        assertDoesNotThrow(()->policy.requireReady(config(AgentExecutionConfig.inherited())));
        assertDoesNotThrow(()->policy.requireReady(config(new AgentExecutionConfig(1,1,1,30000L,61L))));
        assertDoesNotThrow(()->policy.requireReady(config(new AgentExecutionConfig(1,1,1,2049L,61L))));
    }
    private AgentConfigurationPolicy policy(int modelTimeout,int callTimeout) {
        var models=mock(PlatformModelCatalogService.class);var model=mock(PlatformModelConfig.class);
        when(model.maxOutputTokens()).thenReturn(2048);when(model.timeoutSeconds()).thenReturn(modelTimeout);
        when(models.requireEnabled("7","LLM")).thenReturn(model);
        var settings=mock(OperationalSettingsService.class);
        when(settings.read("calls")).thenReturn(new OperationalSettingsService.Snapshot(1,Map.of("chat_timeout_seconds",callTimeout)));
        return new AgentConfigurationPolicy(mock(AgentService.class),models,settings);
    }
    private AgentConfigurationPolicy.ReadyConfiguration config(AgentExecutionConfig execution) {
        return new AgentConfigurationPolicy.ReadyConfiguration(1,"业务指令",7L,execution,Map.of());
    }
}
