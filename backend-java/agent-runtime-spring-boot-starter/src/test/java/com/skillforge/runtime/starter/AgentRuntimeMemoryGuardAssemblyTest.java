package com.skillforge.runtime.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.adapter.jdbc.identity.JdbcIdentityTransactions;
import com.skillforge.runtime.api.port.SessionStorePort;
import com.skillforge.runtime.memory.MemoryRuntime;
import com.skillforge.runtime.adapter.jdbc.memory.MemoryTransitionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.test.util.ReflectionTestUtils;

/** 验证真正Starter装配把宿主迁移钩子交给持久适配器；SQL/同连接拒绝由PG契约测试承担。 */
class AgentRuntimeMemoryGuardAssemblyTest {
    /** 只消费会话存储时无需声明任何记忆治理端口。 */
    @Test void disabledMemoryDoesNotAssembleStoresOrRequireHostMemoryBeans() throws Exception {
        AgentRuntimeAutoConfigurationTest.infrastructureWithoutMemory()
                .withBean(JdbcIdentityTransactions.class, () -> mock(JdbcIdentityTransactions.class))
                .withPropertyValues("agent-runtime.memory.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SessionStorePort.class)
                            .doesNotHaveBean(JdbcMemoryPort.class).doesNotHaveBean(MemoryRuntime.class);
                    assertThat(context.getBean(AgentRuntimeProperties.class).getMemory().isEnabled()).isFalse();
                });
    }

    /** 显式启用仍要求完整宿主治理，不能自动回退到默认允许。 */
    @Test void enabledMemoryWithoutHostGovernanceFailsStartup() throws Exception {
        AgentRuntimeAutoConfigurationTest.infrastructureWithoutMemory()
                .withBean(JdbcIdentityTransactions.class, () -> mock(JdbcIdentityTransactions.class))
                .withPropertyValues("agent-runtime.memory.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
                            .rootCause().hasMessageContaining(MemoryTransitionGuard.class.getName());
                });
    }

    /** 宿主注册的投影钩子不能在通用默认构造路径中被忽略。 */
    @Test void hostGuardIsUsedByTheProductionMemoryBean() throws Exception {
        MemoryTransitionGuard guard = (connection, command, current) -> false;
        AgentRuntimeAutoConfigurationTest.sharedInfrastructureWithoutGuard()
                .withBean(MemoryTransitionGuard.class, () -> guard)
                .withPropertyValues("agent-runtime.workers.auto-startup=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JdbcMemoryPort.class).hasSingleBean(MemoryRuntime.class);
                    assertThat(context.getBean(AgentRuntimeProperties.class).getMemory().isEnabled()).isTrue();
                    assertThat(ReflectionTestUtils.getField(context.getBean(JdbcMemoryPort.class), "transitionGuard"))
                            .isSameAs(guard);
                });
    }
}
