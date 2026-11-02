package com.skillforge.systemagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.SystemAgentRunner;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsMapper;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.adapter.jdbc.memory.MemorySearchEmbedding;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.MediaPort;
import com.skillforge.runtime.memory.MemoryRuntime;
import com.skillforge.runtime.starter.AgentRuntimeAutoConfiguration;
import com.skillforge.runtime.starter.AgentRuntimeMemorySearchBindings;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

/** 使用真实System+Starter配置验证Memory必填装配，不访问数据库或模型。 */
class SystemAgentRuntimeAutoConfigurationTest {
    private ApplicationContextRunner standalone(DataSource source) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SystemAgentRuntimeAutoConfiguration.class,
                        AgentRuntimeAutoConfiguration.class))
                .withPropertyValues("agent-runtime.workers.auto-startup=false")
                .withBean(DataSource.class, () -> source)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(com.skillforge.core.capability.connector.PlatformToolCatalog.class,
                        () -> mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class))
                .withBean(SkillForgeProperties.class, SkillForgeProperties::new)
                .withBean(ServicePrincipalMapper.class, () -> mock(ServicePrincipalMapper.class))
                .withBean("runSettingsMapper", RunSettingsMapper.class, () -> mock(RunSettingsMapper.class))
                .withBean(CryptoService.class, () -> mock(CryptoService.class))
                .withBean(OperationalSettingsService.class, () -> mock(OperationalSettingsService.class))
                .withBean(PlatformModelCatalogService.class, () -> mock(PlatformModelCatalogService.class))
                .withBean(ExternalInvocationGuard.class, () -> mock(ExternalInvocationGuard.class))
                .withBean(ExternalInvocationLedger.class, () -> mock(ExternalInvocationLedger.class))
                .withBean(CostRecorder.class, () -> mock(CostRecorder.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(MediaPort.class, () -> mock(MediaPort.class));
    }

    @Test
    void standaloneSystemHostAssemblesReadOnlyMemoryWithoutInstitutionConfiguration() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new AssertionError("静态装配不得查询业务数据库"));
        standalone(source).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentRuntimeMemorySearchBindings.class)
                    .hasSingleBean(JdbcMemoryPort.class).hasSingleBean(MemoryRuntime.class)
                    .hasSingleBean(SystemAgentRunner.class);
            var bindings = context.getBean(AgentRuntimeMemorySearchBindings.class);
            assertEquals(MemoryRetrievalPolicy.lexicalV1(),
                    bindings.indexingPolicy().apply(new TenantId(SystemAgentRuntimeContracts.TENANT_ID)));
            var rejected = assertThrows(IllegalStateException.class,
                    () -> bindings.projector().apply(mock(MemoryFact.class)));
            assertEquals("system_agent_memory_write_denied", rejected.getMessage());
            var embedding = bindings.embedding().embed(mock(MemorySearchEmbedding.Request.class));
            assertEquals(new MemorySearchEmbedding.Outcome.Rejected("system_agent_memory_embedding_unconfigured"), embedding);
            verifyNoInteractions(source);
        });
    }

    @Test
    void explicitProductHostBindingsTakePrecedenceOverStandaloneReadOnlyBindings() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new AssertionError("静态装配不得查询业务数据库"));
        var explicit = new AgentRuntimeMemorySearchBindings(
                fact -> { throw new AssertionError("装配不能投影业务事实"); },
                tenant -> MemoryRetrievalPolicy.lexicalV1(),
                request -> new MemorySearchEmbedding.Outcome.Rejected("explicit-product-model-not-selected"));
        standalone(source).withBean(AgentRuntimeMemorySearchBindings.class, () -> explicit).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentRuntimeMemorySearchBindings.class)
                    .doesNotHaveBean("systemAgentMemorySearchBindings");
            assertThat(context.getBean(AgentRuntimeMemorySearchBindings.class)).isSameAs(explicit);
            verifyNoInteractions(source);
        });
    }
}
