package com.skillforge.runtime.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.runtime.adapter.jdbc.identity.JdbcIdentityTransactions;
import com.skillforge.runtime.adapter.jdbc.memory.MemoryTransitionGuard;
import com.skillforge.runtime.adapter.jdbc.session.JdbcWorkflowPolicy;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.api.port.MediaPort;
import com.skillforge.runtime.codec.spi.KnowledgeDocumentCodec;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import com.skillforge.runtime.memory.MemoryRuntime;
import com.skillforge.runtime.memory.MemorySourcePolicy;
import com.skillforge.runtime.memory.MemoryTypeRegistry;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

/** Starter 使用纯 SDK 夹具，宿主权限与业务绑定必须显式接入。 */
class AgentRuntimeAutoConfigurationTest {
    static ApplicationContextRunner sharedInfrastructure() throws Exception {
        return sharedInfrastructureWithoutGuard()
                .withBean(MemoryTransitionGuard.class, () -> (session, command, current) -> {
                    throw new AssertionError("静态装配不得迁移Memory事实");
                });
    }

    static ApplicationContextRunner sharedInfrastructureWithoutGuard() throws Exception {
        return infrastructure()
                .withBean(JdbcIdentityTransactions.class, () -> mock(JdbcIdentityTransactions.class));
    }

    static ApplicationContextRunner infrastructureWithoutMemory() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new AssertionError("静态基础装配不能查询业务数据库"));
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class))
                .withBean(DataSource.class, () -> source)
                .withBean(MediaPort.class, () -> mock(MediaPort.class))
                .withBean(AgentRuntimeDecrypt.class, () -> ciphertext -> { throw new AssertionError("静态装配不得解密"); });
    }

    private static ApplicationContextRunner infrastructure() throws Exception {
        return infrastructureWithoutMemory()
                .withBean(MemoryAccessPolicy.class, () -> request -> { throw new AssertionError("静态装配不得访问Memory"); })
                .withBean(MemoryTypeRegistry.class, () -> type -> Optional.empty())
                .withBean(AgentRuntimeMemorySearchBindings.class,()->new AgentRuntimeMemorySearchBindings(
                        fact->{throw new AssertionError("静态装配不得投影事实");},tenant->com.skillforge.runtime.api.MemoryRetrievalPolicy.lexicalV1(),
                        request->new com.skillforge.runtime.adapter.jdbc.memory.MemorySearchEmbedding.Outcome.Rejected("memory_embedding_not_selected")))
                .withBean(MemorySourcePolicy.class, () -> request -> { throw new AssertionError("静态装配不得读取Memory来源"); })
                .withBean(MemoryRuntime.CurrentFactSource.class, () -> (context, reference) -> Optional.empty());
    }

    @Test
    void assemblesRegisteredProviderInfrastructureWithoutOldExecutionGraphOrDatabaseCalls() throws Exception {
        sharedInfrastructure().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentRuntimeProperties.class).hasSingleBean(AgentRuntimeModelConfiguration.class);
            assertThat(context).hasSingleBean(JdbcIdentityTransactions.class).hasSingleBean(JdbcWorkflowPolicy.class);
            assertThat(context).hasSingleBean(ModelProviderCatalog.class).hasSingleBean(OpenAiModelPort.class);
            assertThat(context).hasSingleBean(MemoryRuntime.class);
            assertThat(context).hasSingleBean(KnowledgeDocumentCodec.class);
            assertThat(context.getBean(KnowledgeDocumentCodec.class).encodeText("原文\n😀"))
                    .isEqualTo(new com.skillforge.runtime.api.TypedPayload(
                            KnowledgeDocumentCodec.TEXT_SCHEMA, "{\"text\":\"原文\\n😀\"}"));
            for (String hostBean : java.util.List.of("agentRuntimeIdentityPolicy", "agentRuntimeTrustedAccess",
                    "agentRuntimeSystemGrants", "agentRuntimeSessionCapabilities", "agentRuntimeMcpBindings",
                    "agentRuntimeMcpToolProvider", "agentRuntimeKnowledgeBindings", "agentRuntimeKnowledgePortal",
                    "agentRuntimeWorkflowBindings", "agentRuntimeCallbackSigningKeys", "agentRuntimeCallbackIngress",
                    "agentRuntimeCallbacks", "agentRuntimeWorkflowInteractionView"))
                assertThat(context).doesNotHaveBean(hostBean);
            for (String removed : java.util.List.of("agentRuntime", "agentRuntimeWorkers", "agentRuntimeSessionRuntime",
                    "agentRuntimeSessionFlight", "agentRuntimeSessionCommitter", "agentRuntimeProgramTools", "agentRuntimeTrustedNewRun",
                    "agentRuntimeActionLedger", "agentRuntimeToolPortal", "agentRuntimeWorkQueueDispatcher", "agentRuntimeWorkflowNodeCalls"))
                assertThat(context).doesNotHaveBean(removed);
        });
    }

    @Test
    void missingHostIdentityPolicyFailsStartupWithoutCreatingAPlatformIdentityImplementation() throws Exception {
        infrastructure()
                .withBean(MemoryTransitionGuard.class, () -> (session, command, current) -> {
                    throw new AssertionError("静态装配不得迁移Memory事实");
                }).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
                            .rootCause().hasMessageContaining(JdbcIdentityTransactions.class.getName());
                });
    }

    @Test
    void missingHostMemoryTransitionGuardFailsStartupWithoutAnAllowingFallback() throws Exception {
        sharedInfrastructureWithoutGuard().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
                    .rootCause().hasMessageContaining(MemoryTransitionGuard.class.getName());
        });
    }

    @Test
    void invalidTransportLimitsFailDuringConfigurationValidation() throws Exception {
        sharedInfrastructure().withPropertyValues("agent-runtime.model-maximum-response-bytes=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("agent-runtime numeric limits must be positive");
        });
    }

    @Test
    void positiveLeaseDurationBindsWithoutAnUnrelatedTransportWindow() throws Exception {
        sharedInfrastructure().withPropertyValues("agent-runtime.worker-lease-duration=1s").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AgentRuntimeProperties.class).getWorkerLeaseDuration())
                    .isEqualTo(java.time.Duration.ofSeconds(1));
        });
    }

    @Test
    void sdkEnvironmentVariableBindsWithoutPlatformPrefix() throws Exception {
        sharedInfrastructure().withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.SystemEnvironmentPropertySource("sdk-test-systemEnvironment",
                        java.util.Map.of("AGENTRUNTIME_MODELMAXIMUMRESPONSEBYTES", "2345678"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AgentRuntimeProperties.class).getModelMaximumResponseBytes())
                            .isEqualTo(2_345_678);
                });
    }

    @Test
    void nonpositiveLeaseDurationStillFailsDuringConfigurationValidation() throws Exception {
        for (String value : java.util.List.of("0s", "-1s")) {
            sharedInfrastructure().withPropertyValues("agent-runtime.worker-lease-duration=" + value).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("agent-runtime.worker-lease-duration must be positive");
            });
        }
    }
}
