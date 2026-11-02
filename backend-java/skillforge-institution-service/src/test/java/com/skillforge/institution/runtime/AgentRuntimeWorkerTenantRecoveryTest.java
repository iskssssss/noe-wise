package com.skillforge.institution.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimeSystemGrants;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实受限 PostgreSQL、生产 Mapper/RLS 拦截器及 Spring 事务验证启动恢复；仅授权登记为 mock。 */
@Testcontainers
class AgentRuntimeWorkerTenantRecoveryTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate admin;
    private static RuntimeWorkerRecoveryMapper mapper;
    private static DataSourceTransactionManager transactionManager;
    private AgentRuntimeSystemGrants grants;
    private AgentRuntimeWorkerTenantRecovery recovery;

    @BeforeAll
    static void schema() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        // 本用例只需要恢复查询读取的列；策略与生产 Inbox/Execution 的租户策略相同。
        admin.execute("""
                CREATE TABLE nw_tenants(id bigint PRIMARY KEY, status text NOT NULL);
                CREATE TABLE agent_runtime_session_inbox(tenant_id text NOT NULL, status text NOT NULL);
                CREATE TABLE agent_runtime_executions(tenant_id text NOT NULL, state text NOT NULL, cancel_request_id text);
                CREATE TABLE agent_runtime_execution_runs(tenant_id text NOT NULL, status text NOT NULL);
                CREATE ROLE recovery_reader LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOBYPASSRLS;
                GRANT USAGE ON SCHEMA public TO recovery_reader;
                GRANT SELECT ON ALL TABLES IN SCHEMA public TO recovery_reader;
                """);
        for (String table : java.util.List.of("agent_runtime_session_inbox", "agent_runtime_executions", "agent_runtime_execution_runs")) {
            admin.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
            admin.execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
            admin.execute("CREATE POLICY tenant_isolation ON " + table
                    + " USING (tenant_id=COALESCE(current_setting('app.tenant_id',true),''))");
        }
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "recovery_reader", "test-only-password");
        assertFalse(Boolean.TRUE.equals(new JdbcTemplate(source).queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)));
        var configuration = new Configuration();
        configuration.addMapper(RuntimeWorkerRecoveryMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setPlugins(new TenantRlsInterceptor());
        factory.setMapperLocations(new ClassPathResource("mapper/RuntimeWorkerRecoveryMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(RuntimeWorkerRecoveryMapper.class);
        transactionManager = new DataSourceTransactionManager(source);
    }

    @BeforeEach
    void fixture() {
        TenantContext.clear();
        admin.execute("TRUNCATE nw_tenants,agent_runtime_session_inbox,agent_runtime_executions,agent_runtime_execution_runs");
        admin.execute("""
                INSERT INTO nw_tenants VALUES (1,'active'),(2,'active'),(3,'disabled'),(4,'active');
                INSERT INTO agent_runtime_session_inbox VALUES
                    ('1','QUEUED'),('1','PROCESSING'),('1','COMPLETED'),
                    ('2','COMPLETED'),('3','PROCESSING'),('4','FAILED');
                INSERT INTO agent_runtime_executions VALUES
                    ('1','COMPLETED',NULL),('2','QUEUED',NULL),('2','RUNNING',NULL),
                    ('2','WAITING_EXTERNAL',NULL),('2','RESUMING',NULL),
                    ('2','WAITING_HITL',NULL),('2','WAITING_HITL','cancel'),('2','FAILED',NULL),
                    ('3','RUNNING',NULL),('4','WAITING_HITL',NULL);
                """);
        grants = mock(AgentRuntimeSystemGrants.class);
        var properties = new AgentRuntimeProperties();
        properties.setWorkerId("recovery-worker");
        recovery = new AgentRuntimeWorkerTenantRecovery(mapper, transactionManager, grants, properties);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void activeTenantPagesUseStableBoundedTextCursorAndExcludeDisabledTenants() {
        admin.update("INSERT INTO nw_tenants VALUES (10,'active'),(11,'disabled'),(20,'active')");
        assertThat(mapper.activeTenantPage("", 2)).containsExactly("1", "10");
        assertThat(mapper.activeTenantPage("10", 2)).containsExactly("2", "20");
        assertThat(mapper.activeTenantPage("20", 2)).containsExactly("4");
        assertThat(mapper.activeTenantPage("4", 2)).isEmpty();
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void manualTransactionBindingIsOverwrittenUnlessThreadTenantCoversEveryQuery() {
        var transactions = new TransactionTemplate(transactionManager);
        Long unscopedCount = transactions.execute(status -> {
            mapper.bindTenant("1");
            return mapper.unfinished();
        });
        Long firstTenantCount = TenantContext.callWithTenant("1", () -> transactions.execute(status -> {
            mapper.bindTenant("1");
            return mapper.unfinished();
        }));
        Long secondTenantCount = TenantContext.callWithTenant("2", () -> transactions.execute(status -> {
            mapper.bindTenant("2");
            return mapper.unfinished();
        }));
        assertThat(unscopedCount).isEqualTo(0L);
        assertThat(firstTenantCount).isEqualTo(2L);
        assertThat(secondTenantCount).isEqualTo(5L);
        assertNull(TenantContext.getTenantId());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "3")
    void startupRegistersOnlyActiveUnfinishedTenantsAndRestoresCallerContext(String callerTenant) {
        if (callerTenant != null) TenantContext.setTenantId(callerTenant);

        recovery.run(new DefaultApplicationArguments());

        verify(grants).register(new TenantId("1"), "recovery-worker", AgentRuntimeHostProvisioner.RUNTIME_SCOPES);
        verify(grants).register(new TenantId("2"), "recovery-worker", AgentRuntimeHostProvisioner.RUNTIME_SCOPES);
        verifyNoMoreInteractions(grants);
        assertEquals(callerTenant, TenantContext.getTenantId());
    }

    @Test
    void startupRestoresTypedRunsWithoutInboxOrWorkflowAndExcludesTerminalOrDisabledTenants() {
        admin.execute("DELETE FROM agent_runtime_session_inbox");
        admin.execute("DELETE FROM agent_runtime_executions");
        admin.execute("""
                INSERT INTO agent_runtime_execution_runs VALUES
                  ('1','RUNNING'),('1','SUSPENDED'),('2','QUEUED'),('2','RESUMING'),
                  ('3','RUNNING'),('4','COMPLETED'),('4','FAILED'),('4','CANCELLED'),('4','TERMINATED');
                """);
        recovery.run(new DefaultApplicationArguments());
        verify(grants).register(new TenantId("1"), "recovery-worker", AgentRuntimeHostProvisioner.RUNTIME_SCOPES);
        verify(grants).register(new TenantId("2"), "recovery-worker", AgentRuntimeHostProvisioner.RUNTIME_SCOPES);
        verifyNoMoreInteractions(grants);
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void queryFailureRestoresCallerContextAndDoesNotRegisterUnconfirmedTenant() {
        TenantContext.setTenantId("caller");
        // 仅对本用例独立容器撤销读取，触发真实 MyBatis/事务异常后恢复测试角色权限。
        admin.execute("REVOKE SELECT ON agent_runtime_executions FROM recovery_reader");
        try {
            assertThrows(RuntimeException.class, () -> recovery.run(new DefaultApplicationArguments()));
            assertEquals("caller", TenantContext.getTenantId());
            verifyNoInteractions(grants);
        } finally {
            admin.execute("GRANT SELECT ON agent_runtime_executions TO recovery_reader");
        }
    }

    @Test
    void terminalInputsAndUncancelledHumanWaitDoNotRegisterWorker() {
        admin.execute("DELETE FROM agent_runtime_session_inbox WHERE status IN ('QUEUED','PROCESSING')");
        admin.execute("DELETE FROM agent_runtime_executions WHERE state<>'WAITING_HITL' OR cancel_request_id IS NOT NULL");

        recovery.run(new DefaultApplicationArguments());

        verifyNoInteractions(grants);
        assertNull(TenantContext.getTenantId());
    }
}
