package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.schema.RuntimeJdbcSchema;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实受限连接核验空同步与宿主事务：租户绑定不会在单条自动提交后丢失。 */
@Testcontainers(disabledWithoutDocker = false)
class HostBudgetTransactionsIntegrationTest {
    private static final String ROLE = "host_budget_runtime";
    private static final String PASSWORD = "synthetic_host_budget_password";
    private static HikariDataSource source;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("host_budget_transactions").withUsername("owner").withPassword("synthetic_owner_password");

    @BeforeAll
    static void initialize() throws Exception {
        var owner = new PGSimpleDataSource();
        owner.setURL(POSTGRES.getJdbcUrl()); owner.setUser(POSTGRES.getUsername()); owner.setPassword(POSTGRES.getPassword());
        RuntimeJdbcSchema.install(owner);
        try (var connection = owner.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + ROLE);
        }
        var config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl()); config.setUsername(ROLE); config.setPassword(PASSWORD);
        config.setMaximumPoolSize(3); source = new HikariDataSource(config);
    }

    @AfterAll
    static void closePool() {
        if (source != null) source.close();
    }

    @Test
    void neverSynchronizationWithBoundAutocommitConnectionStillRegistersTenantBudget() throws Exception {
        // 负控证明受限角色不能在缺租户绑定时写入；后续成功不得依赖绕过RLS。
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), ROLE, PASSWORD);
                var statement = connection.createStatement()) {
            var denied = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO agent_runtime_budget_roots(tenant_id,root_id,scenario,source_version,active_limit_seconds) "
                    + "VALUES ('7','unbound-negative','MAINTENANCE','test',60)"));
            assertEquals("42501", denied.getSQLState());
        }
        var budgets = new JdbcExecutionBudgets(source, new HostBudgetTransactions());
        var registration = registration();
        var never = new TransactionTemplate(new DataSourceTransactionManager(source));
        never.setPropagationBehavior(TransactionDefinition.PROPAGATION_NEVER);
        never.executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var connection = DataSourceUtils.getConnection(source);
            try {
                assertTrue(connection.getAutoCommit());
                assertTrue(TransactionSynchronizationManager.hasResource(source));
                assertEquals(registration, budgets.register(registration));
                assertEquals(registration, budgets.find(registration.run()).orElseThrow());
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            } finally {
                DataSourceUtils.releaseConnection(connection, source);
            }
        });
        assertFalse(TransactionSynchronizationManager.hasResource(source));
        assertEquals(registration, budgets.find(registration.run()).orElseThrow());
        try (var connection = source.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM agent_runtime_budget_roots")) {
            assertTrue(rows.next()); assertEquals(0, rows.getInt(1), "池连接不能遗留其他请求的租户绑定");
        }
    }

    @Test
    void genuineHostTransactionRollsBackBudgetRegistrationTogether() {
        var budgets = new JdbcExecutionBudgets(source, new HostBudgetTransactions());
        var registration = registration();
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.hasResource(source));
            assertEquals(registration, budgets.register(registration));
            assertEquals(registration, budgets.find(registration.run()).orElseThrow());
            status.setRollbackOnly();
        });
        assertTrue(budgets.find(registration.run()).isEmpty());
    }

    private static ExecutionBudgetPort.Registration registration() {
        var run = new RunRef(new TenantId("7"), "maintenance:" + UUID.randomUUID());
        var resources = new ExecutionBudgetPort.Resources(0, 8, 0, 0, 10_000, 10_000, 10_000);
        var limits = new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.MAINTENANCE,
                resources, resources, resources, 600, 600, "host-budget-test:v1");
        return new ExecutionBudgetPort.Registration(run, run, ExecutionBudgetPort.Role.PRIMARY,
                new ExecutionBudgetPort.Policy.Enforced(limits));
    }
}
