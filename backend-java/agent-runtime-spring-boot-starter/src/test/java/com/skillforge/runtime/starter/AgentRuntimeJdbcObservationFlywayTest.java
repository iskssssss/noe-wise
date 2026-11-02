package com.skillforge.runtime.starter;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** 真实Boot派生迁移数据源的属性链；不连接数据库，不执行迁移。 */
class AgentRuntimeJdbcObservationFlywayTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void flywayUserOverrideDerivesOriginalPoolPropertiesWithObservationEnabledOrDisabled(boolean observe) {
        var migrations = new AtomicInteger();
        var originalPool = new HikariDataSource[1];
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class, AgentRuntimeJdbcObservationConfiguration.class))
                .withBean(FlywayMigrationStrategy.class, () -> flyway -> migrations.incrementAndGet())
                .withPropertyValues(
                        "agent-runtime.jdbc-observation-enabled=" + observe,
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/observation_test_no_connection",
                        "spring.datasource.username=application_user",
                        "spring.datasource.password=application_password",
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.flyway.user=migration_user",
                        "spring.flyway.password=migration_password")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var source = context.getBean(DataSource.class);
                    var pool = source.unwrap(HikariDataSource.class);
                    originalPool[0] = pool;
                    assertSame(pool, source.unwrap(DataSource.class));
                    if (observe) assertNotSame(pool, source);
                    else assertSame(pool, source);
                    var flyway = context.getBean(Flyway.class);
                    var migrationSource = assertInstanceOf(SimpleDriverDataSource.class,
                            flyway.getConfiguration().getDataSource());
                    assertEquals(pool.getJdbcUrl(), migrationSource.getUrl());
                    assertEquals("migration_user", migrationSource.getUsername());
                    assertEquals("migration_password", migrationSource.getPassword());
                    assertEquals("application_user", pool.getUsername());
                    assertEquals("application_password", pool.getPassword());
                    assertFalse(pool.isRunning(), "属性提取不启动连接池或连接数据库");
                    assertFalse(pool.isClosed());
                    assertEquals(1, migrations.get(), "实际Flyway初始化使用无副作用策略");
                });
        assertNotNull(originalPool[0]);
        assertTrue(originalPool[0].isClosed(), "观察器不能取得额外池生命周期");
    }
}
