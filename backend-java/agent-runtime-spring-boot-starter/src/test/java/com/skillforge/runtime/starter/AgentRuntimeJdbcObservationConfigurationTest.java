package com.skillforge.runtime.starter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentRuntimeJdbcObservationConfigurationTest {
    private interface PoolDataSource extends DataSource { }

    @Test
    void explicitObservationKeepsSpringPoolDestructionAndUnwrap() throws Exception {
        var source = mock(PoolDataSource.class, withSettings().extraInterfaces(AutoCloseable.class));
        when(source.unwrap(PoolDataSource.class)).thenReturn(source);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentRuntimeJdbcObservationConfiguration.class))
                .withPropertyValues("agent-runtime.jdbc-observation-enabled=true")
                .withBean(DataSource.class, () -> source)
                .run(context -> {
                    var observed = context.getBean(DataSource.class);
                    assertNotSame(source, observed);
                    // Boot提取迁移数据源属性须沿unwrap到原池，与真实Flyway装配契约一致。
                    assertSame(source, observed.unwrap(DataSource.class));
                    assertSame(source, observed.unwrap(PoolDataSource.class));
                });
        verify((AutoCloseable) source).close();
    }

    @Test
    void ordinaryStartupDoesNotInstallConnectionProxies() {
        var source = mock(DataSource.class);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentRuntimeJdbcObservationConfiguration.class))
                .withBean(DataSource.class, () -> source)
                .run(context -> assertSame(source, context.getBean(DataSource.class)));
    }
}
