package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.capability.persistence.ConnectorInstanceMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.capability.persistence.ConnectorInstanceDO;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordDO;
import com.skillforge.core.capability.application.ConnectorInstanceService;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 Spring/MyBatis 事务、普通数据库角色与竞争写入共同验证连接器删除边界。 */
@Timeout(60)
class ConnectorInstanceServiceIntegrationTest extends AbstractIntegrationTest {
    @Autowired private ConnectorInstanceService service;
    @Autowired private ConnectorInstanceMapper instances;
    @Autowired private ToolDeploymentRecordMapper deployments;
    @Autowired private CapabilityCatalogService catalog;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private final String role = "sf_connector_test_" + SnowflakeIds.nextId();
    private boolean roleCreated;
    private long tenantId;
    private long otherTenantId;
    private long versionId;
    private ConnectorInstanceDO connector;

    @DynamicPropertySource
    static void disableUnrelatedWorkers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> false);
    }

    private void createRestrictedDatabaseRole() throws NoSuchMethodException {
        assertTrue(AopUtils.isAopProxy(service), "删除必须经过实际 Spring 事务代理");
        // 外层事务用于 SET LOCAL ROLE；另外核验真实代理属性，避免外层事务掩盖生产注解被移除。
        var method = ConnectorInstanceService.class.getMethod("deleteUnused", Long.class, String.class);
        var targetClass = AopUtils.getTargetClass(service);
        var transactionInterceptor = Arrays.stream(((Advised) service).getAdvisors())
                .filter(advisor -> advisor instanceof PointcutAdvisor pointcut
                        && pointcut.getPointcut().getClassFilter().matches(targetClass)
                        && pointcut.getPointcut().getMethodMatcher().matches(method, targetClass))
                .map(advisor -> advisor.getAdvice())
                .filter(TransactionInterceptor.class::isInstance)
                .map(TransactionInterceptor.class::cast).findFirst().orElseThrow();
        var attributeSource = transactionInterceptor.getTransactionAttributeSource();
        assertNotNull(attributeSource);
        var attribute = attributeSource.getTransactionAttribute(method, targetClass);
        assertNotNull(attribute, "deleteUnused 自身必须拥有事务属性");
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, attribute.getPropagationBehavior());
        assertTrue(attribute.rollbackOn(new Exception("checked failure")), "受检异常必须触发回滚");
        assertTrue(attribute.rollbackOn(new IllegalStateException("runtime failure")));
        jdbc.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
        roleCreated = true;
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + role);
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON nw_connector_instances, nw_tool_deployments TO " + role);
        jdbc.execute("GRANT SELECT ON nw_capability_versions, nw_capability_definitions TO " + role);
    }

    @AfterEach
    void removeRestrictedDatabaseRole() {
        TenantContext.clear();
        if (!roleCreated) return;
        jdbc.execute("DROP OWNED BY " + role);
        jdbc.execute("DROP ROLE " + role);
    }

    @BeforeEach
    void persistLegalFixture() throws NoSuchMethodException {
        TenantContext.clear();
        // 默认 PER_METHOD 在容器 BeforeAll 完成后才创建 Spring 夹具；角色与数据按明确顺序建立。
        createRestrictedDatabaseRole();
        tenantId = Long.parseLong(createTenant(tenants, plans, "connector-a-" + SnowflakeIds.nextId()));
        otherTenantId = Long.parseLong(createTenant(tenants, plans, "connector-b-" + SnowflakeIds.nextId()));
        TenantContext.setTenantId(Long.toString(tenantId));
        try {
            // 复用正式只读工具契约，经真实 Catalog 校验、持久化和发布，不构造空 Descriptor。
            var source = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                    .filter(entry -> "get_current_time".equals(entry.name())).findFirst().orElseThrow();
            var descriptor = new LinkedHashMap<String, Object>(source.descriptor());
            descriptor.put("provider_requirements", List.of());
            var scope = CapabilityCatalogScope.tenant(tenantId);
            var definition = catalog.createDefinition(scope, CapabilityType.TOOL,
                    "tool:connector-test-" + SnowflakeIds.nextId(), source.name(), source.description());
            var draft = catalog.createDraftVersion(scope, definition.id(), "v1", source.name(),
                    source.description(), "connector-test-hash-" + SnowflakeIds.nextId(), descriptor);
            versionId = catalog.publishVersion(scope, definition.id(), draft.id(),
                    catalog.getDefinition(scope, definition.id()).revision()).id();
            connector = new ConnectorInstanceDO();
            connector.setId(SnowflakeIds.nextId());
            connector.setInstanceId("inst_test_" + connector.getId());
            connector.setTenantId(tenantId);
            connector.setName("连接器删除测试");
            connector.setBaseUrl("https://connector.example");
            connector.setExecutorType("mcp");
            connector.setCapabilities(List.of(source.name()));
            connector.setAuthType("bearer");
            connector.setStatus("active");
            connector.setVisibility("tenant");
            connector.setHeaders(Map.of());
            connector.setCapabilityMapping(Map.of());
            connector.setCreatedAt(Instant.now());
            connector.setUpdatedAt(connector.getCreatedAt());
            assertEquals(1, asTenant(tenantId, () -> instances.insert(connector)));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void activeAndDisabledDeploymentsRejectDeletionWithoutSideEffects() {
        var deployment = newDeployment();
        assertEquals(1, asTenant(tenantId, () -> deployments.insert(deployment)));
        assertReferencedWithoutDeletion();

        assertEquals(1, asTenant(tenantId, () -> deployments.update(null,
                new LambdaUpdateWrapper<ToolDeploymentRecordDO>()
                        .eq(ToolDeploymentRecordDO::getId, deployment.getId())
                        .set(ToolDeploymentRecordDO::getStatus, "DISABLED"))));
        assertReferencedWithoutDeletion();
        assertEquals("DISABLED", asTenant(tenantId,
                () -> deployments.selectById(deployment.getId()).getStatus()));
    }

    @Test
    void crossTenantDeletionReturnsNotFoundAndPreservesOwnerInstance() {
        var failure = assertThrows(ApiException.class, () -> asTenant(otherTenantId, () -> {
            service.deleteUnused(otherTenantId, connector.getInstanceId());
            return null;
        }));
        assertEquals(404, failure.getStatus().value());
        assertEquals(1L, countInstance(tenantId));
        assertEquals(0L, countInstance(otherTenantId));
    }

    @Test
    void unreferencedInstanceDeletesUnderRestrictedRoleAndMyBatisTenantBinding() {
        asTenant(tenantId, () -> {
            service.deleteUnused(tenantId, connector.getInstanceId());
            assertMyBatisBoundTenant(tenantId);
            return null;
        });
        assertEquals(0L, countInstance(tenantId));
    }

    @Test
    void concurrentDeploymentWaitsForUncommittedDeleteThenForeignKeyRejectsInsert() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var deletionCompleted = new CountDownLatch(1);
        var allowDeleteCommit = new CountDownLatch(1);
        var insertionEntered = new CountDownLatch(1);
        var deletingPid = new AtomicInteger();
        var insertingPid = new AtomicInteger();
        var deployment = newDeployment();
        Future<?> deleting = null;
        Future<?> inserting = null;
        try {
            deleting = executor.submit(() -> asTenant(tenantId, () -> {
                deletingPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                service.deleteUnused(tenantId, connector.getInstanceId());
                assertMyBatisBoundTenant(tenantId);
                deletionCompleted.countDown();
                awaitLatch(allowDeleteCommit);
                return null;
            }));
            assertTrue(deletionCompleted.await(10, TimeUnit.SECONDS), "真实删除尚未返回外层事务");
            inserting = executor.submit(() -> asTenant(tenantId, () -> {
                insertingPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                insertionEntered.countDown();
                return deployments.insert(deployment);
            }));
            assertTrue(insertionEntered.await(10, TimeUnit.SECONDS), "新增事务尚未到达 Mapper");
            awaitDatabaseBlock(deletingPid.get(), insertingPid.get());
            assertFalse(inserting.isDone(), "新增引用必须等待外层删除事务释放锁");

            allowDeleteCommit.countDown();
            deleting.get(10, TimeUnit.SECONDS);
            Future<?> blockedInsert = inserting;
            var failure = assertThrows(ExecutionException.class,
                    () -> blockedInsert.get(10, TimeUnit.SECONDS));
            assertTrue(hasSqlState(failure, "23503"), "提交删除后必须由真实 FK 拒绝引用写入");
            assertEquals(0L, countInstance(tenantId));
            assertEquals(0L, asTenant(tenantId, () -> deployments.selectCount(
                    new LambdaQueryWrapper<ToolDeploymentRecordDO>()
                            .eq(ToolDeploymentRecordDO::getId, deployment.getId()))));
            assertEquals(0L, jdbc.queryForObject("""
                    SELECT count(*) FROM nw_tool_deployments d
                    LEFT JOIN nw_connector_instances c
                      ON c.tenant_id = d.tenant_id AND c.instance_id = d.connector_instance_ref
                    WHERE d.tenant_id = ? AND d.connector_instance_ref IS NOT NULL AND c.id IS NULL
                    """, Long.class, tenantId));
        } finally {
            allowDeleteCommit.countDown();
            if (deleting != null && !deleting.isDone()) deleting.cancel(true);
            if (inserting != null && !inserting.isDone()) inserting.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS), "竞争事务必须结束后才释放夹具");
        }
    }

    private ToolDeploymentRecordDO newDeployment() {
        var deployment = new ToolDeploymentRecordDO();
        deployment.setId(SnowflakeIds.nextId());
        deployment.setTenantId(tenantId);
        deployment.setDeploymentKey("deployment-test-" + deployment.getId());
        deployment.setToolVersionId(versionId);
        deployment.setProviderType("MCP");
        deployment.setProviderRef(connector.getInstanceId());
        deployment.setEnvironment("TEST");
        deployment.setStatus("ACTIVE");
        deployment.setRevision(1L);
        deployment.setCreatedAt(Instant.now());
        deployment.setUpdatedAt(deployment.getCreatedAt());
        return deployment;
    }

    private void assertReferencedWithoutDeletion() {
        var failure = assertThrows(ApiException.class, () -> asTenant(tenantId, () -> {
            service.deleteUnused(tenantId, connector.getInstanceId());
            return null;
        }));
        assertEquals(409, failure.getStatus().value());
        assertEquals("CONNECTOR_REFERENCED", failure.getCode());
        assertFalse(failure.isRetryable());
        assertEquals(1L, countInstance(tenantId));
        assertEquals(1L, asTenant(tenantId, () -> deployments.selectCount(
                new LambdaQueryWrapper<ToolDeploymentRecordDO>()
                        .eq(ToolDeploymentRecordDO::getProviderRef, connector.getInstanceId()))));
    }

    private long countInstance(long tenant) {
        return asTenant(tenant, () -> {
            long count = instances.selectCount(new LambdaQueryWrapper<ConnectorInstanceDO>()
                    .eq(ConnectorInstanceDO::getInstanceId, connector.getInstanceId()));
            assertMyBatisBoundTenant(tenant);
            return count;
        });
    }

    /** SET LOCAL ROLE 与 Mapper 必须复用 Spring 管理的同一事务连接。 */
    private <T> T asTenant(long tenant, Supplier<T> operation) {
        String previous = TenantContext.getTenantId();
        TenantContext.setTenantId(Long.toString(tenant));
        try {
            return new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL ROLE " + role);
                jdbc.execute("SET LOCAL statement_timeout = '15s'");
                jdbc.queryForObject("SELECT set_config('app.tenant_id', '', true)", String.class);
                assertEquals(role, jdbc.queryForObject("SELECT current_user", String.class));
                assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user
                        """, Boolean.class)));
                return operation.get();
            });
        } finally {
            if (previous == null) TenantContext.clear();
            else TenantContext.setTenantId(previous);
        }
    }

    private void assertMyBatisBoundTenant(long tenant) {
        assertEquals(Long.toString(tenant), jdbc.queryForObject(
                "SELECT current_setting('app.tenant_id', true)", String.class));
        assertEquals(role, jdbc.queryForObject("SELECT current_user", String.class));
    }

    /** latch 只负责到达顺序；数据库阻塞关系才证明两个真实事务发生竞争。 */
    private void awaitDatabaseBlock(int blocker, int blocked) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT CAST(? AS integer) = ANY(pg_blocking_pids(?))", Boolean.class, blocker, blocked))) return;
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("数据库阻塞观察被中断");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
        throw new AssertionError("未观察到新增部署等待删除事务的数据库锁");
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new AssertionError("未收到删除事务提交信号");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("竞争事务被中断", interrupted);
        }
    }

    private boolean hasSqlState(Throwable failure, String expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && expected.equals(sql.getSQLState())) return true;
        }
        return false;
    }
}
