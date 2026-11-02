package com.skillforge.core.foundation.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 正式迁移、真实平台IAM与受限连接共同验证观测窗口；不依赖平台数据库绕过RLS。 */
@Testcontainers(disabledWithoutDocker = false)
class PlatformObservationAccessDatabaseTest {
    private static final String ROLE = "platform_observation_reader";
    private static final String PASSWORD = "isolated-observation-password";
    private static final String REFERENCE = "observation-fixture";
    private static final String FIRST_VALUE = "{\"tenant\":\"7\"}";
    private static final String SECOND_VALUE = "{\"tenant\":\"8\"}";
    private static final long FIRST = 7;
    private static final long SECOND = 8;
    private static final long PLATFORM_ROLE = 8173;
    private static final FoundationAccess.Subject PLATFORM = new FoundationAccess.Subject(true, null, "8171");
    private static final FoundationAccess.Subject TENANT = new FoundationAccess.Subject(false, "7", "8172");
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static JdbcTemplate owner;

    @BeforeAll
    static void database() throws Exception {
        CurrentHostDatabase.migrate(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var admin = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        owner = new JdbcTemplate(admin);
        try (var connection = admin.getConnection()) {
            CurrentHostFixtures.tenant(connection, FIRST);
            CurrentHostFixtures.tenant(connection, SECOND);
        }
        owner.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(8171,'observation-reader','unused-fixture','active')");
        owner.update("INSERT INTO nw_tenant_members(id,tenant_id,username,password_hash,status) VALUES(8172,7,'observation-tenant','unused-fixture','active')");
        for (long tenant : List.of(FIRST, SECOND))
            owner.update("INSERT INTO nw_foundation_access_scopes(scope_key,tenant_id) VALUES(?,?) ON CONFLICT DO NOTHING", Long.toString(tenant), tenant);
        owner.update("INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name) VALUES('platform',8171,'观测读取员'),('7',8172,'机构成员')");
        owner.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(?,'platform','observation_reader','观测读取员','all')", PLATFORM_ROLE);
        owner.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES('platform',8171,?)", PLATFORM_ROLE);
        owner.update("INSERT INTO agent_runtime_execution_materials(tenant_id,reference,schema_id,canonical_json) VALUES('7',?,'fixture.observation',?),('8',?,'fixture.observation',?)",
                REFERENCE, FIRST_VALUE, REFERENCE, SECOND_VALUE);
        owner.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO " + ROLE);
    }

    @BeforeEach
    void currentPermission() {
        owner.update("UPDATE nw_platform_members SET status='active' WHERE id=8171");
        owner.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES('platform',?,'foundation.sessions.read') ON CONFLICT DO NOTHING", PLATFORM_ROLE);
    }

    @AfterEach
    void clearThread() {
        TenantContext.clear();
        assertNull(PlatformObservationAccess.currentAuthorization());
    }

    @Test
    void platformReadsExactTargetsAndClearsSharedExecutorCachesAndConnectionIdentity() throws Exception {
        try (var fixture = new Fixture()) {
            var secondExecutor = fixture.newSession().getMapper(ProbeMapper.class);
            fixture.foundation.withSubject(PLATFORM, () -> fixture.required.execute(status -> {
                assertTrue(fixture.mapper.materials("7").isEmpty(), "平台空GUC不能读取Runtime机构正文");
                assertTrue(secondExecutor.materials("7").isEmpty());
                assertEquals(List.of(FIRST), fixture.reads.targets(FIRST, 0, 100));
                assertEquals(List.of(SECOND), fixture.reads.targets(SECOND, 0, 100));
                assertEquals(List.of(FIRST_VALUE), fixture.reads.read(FIRST, () -> {
                    assertNull(TenantContext.getTenantId());
                    assertEquals(PLATFORM, fixture.foundation.currentSubject());
                    assertEquals("7|false", fixture.mapper.state("7"));
                    return fixture.mapper.materials("7");
                }));
                assertEquals("|", fixture.mapper.state(""));
                assertTrue(secondExecutor.materials("7").isEmpty(), "另一Executor不能返回刚读过的机构正文");
                assertEquals(List.of(SECOND_VALUE), fixture.reads.read(SECOND, () -> secondExecutor.materials("8")));
                assertEquals(List.of(FIRST_VALUE), fixture.reads.read(FIRST, () -> fixture.mapper.materials("7")));
                assertTrue(fixture.mapper.materials("8").isEmpty());
                assertNull(TenantContext.getTenantId());
                assertNull(PlatformObservationAccess.currentAuthorization());
                return null;
            }));
            fixture.assertPoolIdentityEmpty();
        }
    }

    @Test
    void tenantCannotForgePlatformAuthorizationOrReplaceItsOwnTenant() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.foundation.withSubject(TENANT, () -> fixture.required.execute(status -> {
                var invoked = new AtomicBoolean();
                assertThrows(ApiException.class, () -> fixture.reads.read(SECOND, () -> {
                    invoked.set(true); return fixture.mapper.materials("8");
                }));
                assertFalse(invoked.get());
                assertThrows(IllegalStateException.class,
                        () -> TenantRlsInterceptor.withPlatformObservationTarget(FIRST, () -> List.of()));
                assertEquals(List.of(FIRST_VALUE), fixture.reads.read(FIRST, () -> fixture.mapper.materials("7")));
                assertEquals("7", TenantContext.getTenantId());
                assertNull(PlatformObservationAccess.currentAuthorization());
                return null;
            }));
            fixture.assertPoolIdentityEmpty();
        }
    }

    @Test
    void currentPlatformPermissionIsRequiredAndACreatedAuthorizationValueGrantsNothing() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.foundation.withSubject(PLATFORM, () -> fixture.required.execute(status -> {
                var invented = new PlatformObservationAccess.Authorization(PLATFORM.memberId(), Set.of(FIRST));
                assertEquals(Set.of(FIRST), invented.tenantIds());
                assertThrows(IllegalStateException.class,
                        () -> TenantRlsInterceptor.withPlatformObservationTarget(FIRST, () -> List.of()));
                return null;
            }));
            owner.update("DELETE FROM nw_foundation_role_permissions WHERE scope_key='platform' AND role_id=? AND permission_code='foundation.sessions.read'", PLATFORM_ROLE);
            fixture.foundation.withSubject(PLATFORM, () -> fixture.required.execute(status -> {
                var invoked = new AtomicBoolean();
                var failure = assertThrows(ApiException.class, () -> fixture.reads.read(FIRST, () -> {
                    invoked.set(true); return List.of();
                }));
                assertEquals("foundation.permission.denied", failure.getCode());
                assertFalse(invoked.get());
                return null;
            }));
            fixture.assertPoolIdentityEmpty();
        }
    }

    @Test
    void windowRejectsForeignParametersWritesSettingsAndWritableSelectBeforeDatabaseEffects() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.foundation.withSubject(PLATFORM, () -> fixture.required.execute(status -> {
                assertMessage("platform_observation_statement_not_allowed", assertThrows(RuntimeException.class,
                        () -> fixture.reads.read(FIRST, () -> fixture.mapper.materials("8"))));
                assertMessage("platform_observation_statement_not_allowed", assertThrows(RuntimeException.class,
                        () -> fixture.reads.read(FIRST, () -> fixture.mapper.write("7"))));
                assertMessage("platform_observation_statement_not_allowed", assertThrows(RuntimeException.class,
                        () -> fixture.reads.read(FIRST, () -> fixture.mapper.setting("7", "8"))));
                assertMessage("platform_observation_statement_not_allowed", assertThrows(RuntimeException.class,
                        () -> fixture.reads.read(FIRST, () -> fixture.mapper.writableSelect("7"))));
                assertEquals("|", fixture.mapper.state(""));
                assertEquals(List.of(SECOND_VALUE), fixture.reads.read(SECOND, () -> fixture.mapper.materials("8")));
                return null;
            }));
            assertEquals(0L, owner.queryForObject("SELECT count(*) FROM agent_runtime_execution_materials WHERE reference IN ('forged-write','forged-select')", Long.class));
            fixture.assertPoolIdentityEmpty();
        }
    }

    @Test
    void callbackFailureRestoresTargetAndCacheBeforeTheOuterTransactionContinues() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.foundation.withSubject(PLATFORM, () -> fixture.required.execute(status -> {
                var original = new IllegalStateException("synthetic_callback_failure");
                assertSame(original, assertThrows(IllegalStateException.class, () -> fixture.reads.read(FIRST, () -> {
                    assertEquals(List.of(FIRST_VALUE), fixture.mapper.materials("7"));
                    throw original;
                })));
                assertNull(PlatformObservationAccess.currentAuthorization());
                assertNull(TenantContext.getTenantId());
                assertEquals("|", fixture.mapper.state(""));
                assertTrue(fixture.mapper.materials("7").isEmpty());
                assertEquals(List.of(SECOND_VALUE), fixture.reads.read(SECOND, () -> fixture.mapper.materials("8")));
                return null;
            }));
            fixture.assertPoolIdentityEmpty();
        }
    }

    @Test
    void databaseFailureKeepsItsCauseAndRollbackLeavesNoTargetForTheNextBorrower() throws Exception {
        try (var fixture = new Fixture()) {
            var failure = assertThrows(RuntimeException.class, () -> fixture.foundation.withSubject(PLATFORM,
                    () -> fixture.required.execute(status -> fixture.reads.read(FIRST, () -> fixture.mapper.divideByZero("7")))));
            Throwable cause = failure;
            while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
            assertEquals("22012", assertInstanceOf(SQLException.class, cause).getSQLState());
            assertNull(PlatformObservationAccess.currentAuthorization());
            assertNull(TenantContext.getTenantId());
            fixture.assertPoolIdentityEmpty();
            fixture.foundation.withSubject(PLATFORM, () -> fixture.required.execute(status -> {
                assertTrue(fixture.mapper.materials("7").isEmpty());
                assertEquals(List.of(SECOND_VALUE), fixture.reads.read(SECOND, () -> fixture.mapper.materials("8")));
                return null;
            }));
            fixture.assertPoolIdentityEmpty();
        }
    }

    @Test
    void realRequiresNewAuditCommitsAfterPlatformAuthorizationAndDoesNotPinTheOuterTarget() throws Exception {
        try (var fixture = new Fixture()) {
            assertTrue(AopUtils.isAopProxy(fixture.audit));
            fixture.foundation.withSubject(PLATFORM, () -> fixture.required.execute(status -> {
                int outer = fixture.mapper.backendPid("");
                assertEquals(List.of(FIRST_VALUE), fixture.reads.read(FIRST, () -> fixture.mapper.materials("7")));
                fixture.audit.record("7", PLATFORM.memberId(), "trajectory.content.read", "session", "audit-roundtrip",
                        "读取测试轨迹内容", Map.of("side", "input"));
                assertNull(TenantContext.getTenantId());
                assertNull(PlatformObservationAccess.currentAuthorization());
                assertEquals(outer, fixture.mapper.backendPid(""));
                assertEquals("|", fixture.mapper.state(""));
                assertEquals(List.of(SECOND_VALUE), fixture.reads.read(SECOND, () -> fixture.mapper.materials("8")));
                assertEquals(1L, auditCount("audit-roundtrip"), "独立事务已向另一连接提交审计");
                status.setRollbackOnly();
                return null;
            }));
            assertEquals(1L, auditCount("audit-roundtrip"), "外层回滚不能回滚已提交的读取审计");
            fixture.assertPoolIdentityEmpty();
        }
    }

    @Test
    void independentAuditRejectsOtherActionsAndActorForgeryWithoutAppendingRows() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.foundation.withSubject(PLATFORM, () -> {
                assertThrows(ApiException.class, () -> fixture.audit.record("7", PLATFORM.memberId(), "settings.update", "session", "audit-denied", "拒绝", Map.of()));
                assertThrows(ApiException.class, () -> fixture.audit.record("7", "8172", "trajectory.content.read", "session", "audit-denied", "拒绝", Map.of()));
                return null;
            });
            assertEquals(0L, auditCount("audit-denied"));
            fixture.assertPoolIdentityEmpty();
        }
    }

    private static long auditCount(String resource) {
        return owner.queryForObject("SELECT count(*) FROM nw_audit_logs WHERE tenant_id=7 AND actor_id='8171' AND resource_id=?", Long.class, resource);
    }

    private static void assertMessage(String expected, Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (expected.equals(cause.getMessage())) return;
        fail("必须由明确窗口守卫拒绝: " + expected, failure);
    }

    /** 仅为本模块隔离窗口的查询夹具；不替代各业务域固定Mapper的授权规则测试。 */
    public interface ProbeMapper {
        // tenant参数故意不参与SQL缓存键；跨目标必须依靠窗口清理而不是碰巧不同的参数缓存键。
        @Select("SELECT canonical_json FROM public.agent_runtime_execution_materials WHERE reference='observation-fixture' ORDER BY tenant_id")
        List<String> materials(@Param("tenant") String tenant);
        @Select("SELECT COALESCE(current_setting('app.tenant_id',true),'')||'|'||COALESCE(current_setting('app.platform_admin',true),'')")
        String state(@Param("tenant") String tenant);
        @Select("SELECT pg_backend_pid()") int backendPid(@Param("tenant") String tenant);
        @Select("SELECT 1/0") int divideByZero(@Param("tenant") String tenant);
        @Insert("INSERT INTO public.agent_runtime_execution_materials(tenant_id,reference,schema_id,canonical_json) VALUES(#{tenant},'forged-write','fixture.observation','{}')")
        int write(@Param("tenant") String tenant);
        @Select("SELECT set_config('app.tenant_id',#{target},true)")
        String setting(@Param("tenant") String tenant, @Param("target") String target);
        @Select("WITH forged AS (INSERT INTO public.agent_runtime_execution_materials(tenant_id,reference,schema_id,canonical_json) VALUES(#{tenant},'forged-select','fixture.observation','{}') RETURNING canonical_json) SELECT canonical_json FROM forged")
        String writableSelect(@Param("tenant") String tenant);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class AuditTransactions { }

    private static final class Fixture implements AutoCloseable {
        private static final Set<String> METHODS = Set.of("materials", "state", "backendPid", "divideByZero", "write", "setting", "writableSelect");
        final HikariDataSource pool;
        final DataSourceTransactionManager transactions;
        final TransactionTemplate required;
        final FoundationAccess foundation;
        final PlatformObservationAccess reads;
        final ProbeMapper mapper;
        final ObservationReadAudit audit;
        final AnnotationConfigApplicationContext context;

        Fixture() throws Exception {
            var config = new HikariConfig();
            config.setJdbcUrl(DATABASE.getJdbcUrl()); config.setUsername(ROLE); config.setPassword(PASSWORD);
            config.setMaximumPoolSize(2); config.setMinimumIdle(0);
            pool = new HikariDataSource(config);
            context = new AnnotationConfigApplicationContext();
            try {
                transactions = new DataSourceTransactionManager(pool);
                required = new TransactionTemplate(transactions);
                var session = newSession();
                mapper = session.getMapper(ProbeMapper.class);
                FoundationPermissionContributor permissions = () -> List.of(new FoundationPermission("foundation.sessions.read", "会话读取", FoundationPermission.Audience.BOTH));
                foundation = new FoundationAccess(session.getMapper(FoundationAccessMapper.class), new FoundationPermissionCatalogue(List.of(permissions)), transactions);
                reads = new PlatformObservationAccess(foundation, session.getMapper(PlatformObservationTargetMapper.class));
                context.registerBean("transactionManager", PlatformTransactionManager.class, () -> transactions);
                context.registerBean(FoundationAccess.class, () -> foundation);
                context.registerBean(AuditService.class, () -> new AuditService(session.getMapper(AuditLogMapper.class)));
                context.registerBean(ObservationReadAudit.class);
                context.register(AuditTransactions.class);
                context.refresh();
                audit = context.getBean(ObservationReadAudit.class);
                var jdbc = new JdbcTemplate(pool);
                assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
                assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='agent_runtime_execution_materials'::regclass", Boolean.class));
            } catch (Exception | Error failure) {
                context.close(); pool.close(); throw failure;
            }
        }

        SqlSessionTemplate newSession() throws Exception {
            AuthorizedSqlRule rule = access -> access.window() == AuthorizedSqlRule.Window.PLATFORM_OBSERVATION
                    && METHODS.contains(access.statementId().substring(access.statementId().lastIndexOf('.') + 1))
                    && ProbeMapper.class.getName().equals(access.namespace())
                    && access.parameterEquals("tenant", access.tenant());
            var configuration = new MybatisConfiguration(new Environment("platform-observation", new SpringManagedTransactionFactory(), pool));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addInterceptor(new TenantRlsInterceptor(List.of(rule)));
            configuration.addMapper(ProbeMapper.class);
            configuration.addMapper(AuditLogMapper.class);
            for (String path : List.of("mapper/foundation/FoundationAccessMapper.xml", "mapper/foundation/PlatformObservationTargetMapper.xml")) {
                try (var xml = new ClassPathResource(path).getInputStream()) {
                    new XMLMapperBuilder(xml, configuration, path, configuration.getSqlFragments()).parse();
                }
            }
            return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        }

        void assertPoolIdentityEmpty() throws Exception {
            try (var first = pool.getConnection(); var second = pool.getConnection()) {
                for (var connection : List.of(first, second)) {
                    try (var query = connection.prepareStatement("SELECT COALESCE(current_setting('app.tenant_id',true),''),COALESCE(current_setting('app.platform_admin',true),'')"); var rows = query.executeQuery()) {
                        assertTrue(rows.next()); assertEquals("", rows.getString(1));
                        assertFalse("true".equals(rows.getString(2)));
                    }
                }
            }
        }

        @Override public void close() { context.close(); pool.close(); }
    }
}
