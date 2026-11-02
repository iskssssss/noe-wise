package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.contract.ControlOperationItem;
import com.skillforge.core.foundation.contract.ControlOperationStatus;
import com.skillforge.core.foundation.persistence.ControlOperationMapper;
import com.skillforge.core.foundation.application.ControlOperationExecutor;
import com.skillforge.core.foundation.application.ControlOperationService;
import com.skillforge.core.foundation.application.ControlOperationWorker;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 正式控制账本约束、真实 MyBatis/JSONB 与不可绕过 RLS 的应用角色验证类型化 item 恢复。 */
@Testcontainers(disabledWithoutDocker = false)
class ControlOperationItemsIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static JdbcTemplate admin;
    private static ControlOperationService operations;

    @BeforeAll
    static void schema() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        for (String migration : List.of("V73__open_platform_control_operations.sql", "V75__control_operation_transition_rls.sql")) {
            try (var source = ControlOperationItemsIntegrationTest.class.getClassLoader()
                    .getResourceAsStream("db/migration/" + migration)) {
                assertNotNull(source);
                admin.execute(new String(source.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        admin.execute("ALTER TABLE nw_control_operations ADD COLUMN origin_kind varchar(32), ADD COLUMN actor_ref varchar(256), ADD COLUMN responsibility_ref varchar(256)");
        admin.execute("CREATE ROLE control_items_test LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT SELECT, INSERT, UPDATE ON nw_control_operations TO control_items_test");
        var application = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "control_items_test", "test-only-password");
        assertEquals(false, new JdbcTemplate(application).queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("control-operation-items-test",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(), application));
        configuration.addInterceptor(new TenantRlsInterceptor());
        configuration.addMapper(ControlOperationMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        operations = new ControlOperationService(sessions.getMapper(ControlOperationMapper.class), mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
    }

    @BeforeEach
    void clearRows() {
        admin.execute("TRUNCATE nw_control_operations");
        TenantContext.setTenantId("7");
        com.skillforge.common.context.ServicePrincipalContext.set(new com.skillforge.common.context.ServicePrincipalContext.Identity(
                "fixture-sp","7","crm",1,java.util.Set.of("resources.write")));
    }

    @AfterEach
    void clearContext() { TenantContext.clear();com.skillforge.common.context.ServicePrincipalContext.clear(); }

    @Test
    void roundTripsTypedItemsNullReferencesAndDeclaredStatusValuesAndReplaysTheStoredFact() throws Exception {
        var accepted = new ControlOperationItem("item", "workflow", ControlOperationStatus.ACCEPTED, null, null, false);
        operations.accept("7", "crm", "operation", null, "publish", "idem", "sha256:request", List.of(accepted));
        assertEquals(List.of(accepted), operations.find("7", "crm", "operation").items());
        var initialJson = new ObjectMapper().readTree(admin.queryForObject(
                "SELECT items::text FROM nw_control_operations WHERE operation_id='operation'", String.class)).get(0);
        assertEquals("accepted", initialJson.path("status").asText());
        assertTrue(initialJson.path("result_ref").isNull());
        assertTrue(initialJson.path("error_code").isNull());

        operations.transition("7", "crm", "operation", ControlOperationStatus.ACCEPTED, ControlOperationStatus.PROCESSING, null);
        var results = List.of(
                new ControlOperationItem("success", "workflow-a", ControlOperationStatus.SUCCEEDED, "revision:2", null, false),
                new ControlOperationItem("failed", "workflow-b", ControlOperationStatus.FAILED, null, "RETRY_RESOURCE", true));
        operations.transition("7", "crm", "operation", ControlOperationStatus.PROCESSING,
                ControlOperationStatus.PARTIALLY_SUCCEEDED, results);
        var restored = operations.find("7", "crm", "operation");
        assertEquals(results, restored.items());
        assertNotNull(restored.completedAt());
        var replay = operations.accept("7", "crm", "ignored", null, "publish", "idem", "sha256:request", List.of());
        assertFalse(replay.created());
        assertEquals(restored, replay.operation());
        assertThrows(IllegalArgumentException.class, () -> operations.accept(
                "7", "crm", "conflict", null, "publish", "idem", "sha256:different", List.of()));
        assertEquals(1L, admin.queryForObject("SELECT count(*) FROM nw_control_operations", Long.class));
    }

    @Test
    void rejectsUndeclaredItemStatusDuringRealJsonbRecovery() {
        var item = new ControlOperationItem("item", "workflow", ControlOperationStatus.ACCEPTED, null, null, false);
        operations.accept("7", "crm", "invalid-item", null, "publish", "idem", "sha256:request", List.of(item));
        admin.execute("UPDATE nw_control_operations SET items=jsonb_set(items,'{0,status}','\"unknown\"') WHERE operation_id='invalid-item'");
        assertThrows(RuntimeException.class, () -> operations.find("7", "crm", "invalid-item"));
        admin.execute("UPDATE nw_control_operations SET items=jsonb_set(items,'{0,status}','\"accepted\"') WHERE operation_id='invalid-item'");
        assertEquals(List.of(item), operations.find("7", "crm", "invalid-item").items());
    }

    @Test
    void enforcesTenantContextAndIntegrationBoundariesForReadsAndTransitions() {
        operations.accept("7", "crm", "operation", null, "publish", "idem", "sha256:request", List.of());
        assertNull(operations.find("7", "other-integration", "operation"));
        TenantContext.setTenantId("8");
        assertNull(operations.find("7", "crm", "operation"));
        assertThrows(IllegalArgumentException.class, () -> operations.transition("7", "crm", "operation",
                ControlOperationStatus.ACCEPTED, ControlOperationStatus.PROCESSING, null));
        TenantContext.clear();com.skillforge.common.context.ServicePrincipalContext.clear();
        assertNull(operations.find("7", "crm", "operation"));
        TenantContext.setTenantId("7");
        com.skillforge.common.context.ServicePrincipalContext.set(new com.skillforge.common.context.ServicePrincipalContext.Identity(
                "fixture-sp","7","crm",1,java.util.Set.of("resources.write")));
        assertEquals(ControlOperationStatus.ACCEPTED, operations.find("7", "crm", "operation").status());
    }

    @Test
    void repeatedWorkerWakeupReturnsThePersistedResultWithoutExecutingAgain() {
        var item = new ControlOperationItem("item", "workflow", ControlOperationStatus.ACCEPTED, null, null, false);
        operations.accept("7", "crm", "worker", null, "publish", "idem", "sha256:request", List.of(item));
        var executor = mock(ControlOperationExecutor.class);
        when(executor.supports("crm", "publish")).thenReturn(true);
        var completedItems = List.of(new ControlOperationItem("item", "workflow", ControlOperationStatus.SUCCEEDED,
                "revision:2", null, false));
        when(executor.execute(any())).thenReturn(new ControlOperationExecutor.Execution(ControlOperationStatus.SUCCEEDED, completedItems));
        var worker = new ControlOperationWorker(operations, List.of(executor),
                new com.skillforge.core.billing.metering.ControlOperationMeteringScope());
        worker.processAccepted("7", "crm", "worker");
        var replay = worker.processAccepted("7", "crm", "worker");
        assertEquals(ControlOperationStatus.SUCCEEDED, replay.status());
        assertEquals(completedItems, replay.items());
        verify(executor, times(1)).execute(any());
    }
}
