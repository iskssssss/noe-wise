package com.skillforge.core.foundation.institution;

import static com.skillforge.core.foundation.institution.InstitutionDeploymentManagementContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.foundation.application.AuditService;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 完整迁移、正式 Mapper/审计及受限 PostgreSQL 角色验证目标许可的生命周期和原子性。 */
@Testcontainers(disabledWithoutDocker = false)
class InstitutionDeploymentManagementDatabaseTest {
    private static final String READ = "tenant.agent_deployments.read";
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("institution_deployment_grants").withUsername("skillforge").withPassword("isolated-test-password");
    private static JdbcTemplate owner;
    private static DriverManagerDataSource restricted;
    private static DriverManagerDataSource platform;
    private static SqlSessionTemplate tenantSessions;
    private static SqlSessionTemplate platformSessions;
    private static TransactionTemplate tenantWriter;
    private static TransactionTemplate platformWriter;
    private final AtomicReference<Set<Long>> targets = new AtomicReference<>();
    private InstitutionDeploymentManagementService platformService;
    private InstitutionDeploymentManagementService tenantService;
    private long hq, child, other, grandchild, relation;

    /** 独立容器执行完整迁移；租户角色无 superuser/BYPASSRLS，平台角色仅对应正式平台数据源能力。 */
    @BeforeAll static void database() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("CREATE ROLE deployment_grant_tenant LOGIN PASSWORD 'isolated-role-password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("CREATE ROLE deployment_grant_platform LOGIN PASSWORD 'isolated-platform-password' NOSUPERUSER BYPASSRLS");
        for (String role : List.of("deployment_grant_tenant", "deployment_grant_platform")) {
            owner.execute("GRANT USAGE ON SCHEMA public TO " + role);
            owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO " + role);
        }
        assertTrue(owner.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname='deployment_grant_tenant'", Boolean.class));
        assertTrue(owner.queryForObject("SELECT NOT rolsuper AND rolbypassrls FROM pg_roles WHERE rolname='deployment_grant_platform'", Boolean.class));
        owner.execute("""
                CREATE FUNCTION deployment_grant_test_fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF current_setting('test.fail_deployment_grant_audit',true)='true'
                    AND NEW.action LIKE 'platform.institution_management_grants.%' THEN
                    RAISE EXCEPTION 'simulated_deployment_grant_audit_failure' USING ERRCODE='P0001';
                  END IF;
                  RETURN NEW;
                END $$
                """);
        owner.execute("CREATE TRIGGER deployment_grant_test_fail_audit BEFORE INSERT ON nw_audit_logs FOR EACH ROW EXECUTE FUNCTION deployment_grant_test_fail_audit()");
        restricted = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "deployment_grant_tenant", "isolated-role-password");
        platform = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "deployment_grant_platform", "isolated-platform-password");
        tenantSessions = sessions(restricted, "tenant-deployment-grants");
        platformSessions = sessions(platform, "platform-deployment-grants");
        tenantWriter = new TransactionTemplate(new DataSourceTransactionManager(restricted));
        platformWriter = new TransactionTemplate(new DataSourceTransactionManager(platform));
    }

    /** 正式 SQL 和 RLS 拦截器参与每次数据访问，不用内存仓储替代持久化。 */
    private static SqlSessionTemplate sessions(DriverManagerDataSource connections, String environment) throws Exception {
        var configuration = new MybatisConfiguration(new Environment(environment, new SpringManagedTransactionFactory(), connections));
        configuration.setMapUnderscoreToCamelCase(true); configuration.addInterceptor(new TenantRlsInterceptor());
        configuration.addMapper(AuditLogMapper.class);
        for (String path : List.of("mapper/InstitutionDeploymentManagementMapper.xml", "mapper/InstitutionRelationMapper.xml")) {
            try (var xml = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(xml, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
    }

    /** 每个场景使用互不重叠的真实机构与关系，IAM 只提供当前已认证主体投影。 */
    @BeforeEach void fixture() {
        hq = tenant(null); child = tenant(hq); other = tenant(null); grandchild = tenant(child);
        relation = appendRelation(child, hq); appendRelation(grandchild, child);
        targets.set(Set.of(hq, child, other, grandchild));
        var management = mock(PlatformManagementService.class);
        when(management.authorizedTenantScope(anyString())).thenAnswer(call ->
                new PlatformManagementService.TenantScope("platform_member:deployment-grant-test", 1, targets.get()));
        var foundation = mock(FoundationAccess.class);
        when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(false, Long.toString(hq), "70"));
        when(foundation.allowed(any(FoundationAccess.Subject.class), anyString())).thenReturn(true);
        platformService = service(platformSessions, management, foundation);
        tenantService = service(tenantSessions, management, foundation);
    }

    /** 所有授权和审计均使用当前会话所属的真实数据库。 */
    private InstitutionDeploymentManagementService service(SqlSessionTemplate host, PlatformManagementService management, FoundationAccess foundation) {
        return new InstitutionDeploymentManagementService(host.getMapper(InstitutionDeploymentManagementMapper.class),
                host.getMapper(InstitutionRelationMapper.class), management, foundation, mock(InstitutionIntegrationService.class),
                new AuditService(host.getMapper(AuditLogMapper.class)), new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    /** 异常场景结束时也释放全部线程身份。 */
    @AfterEach void clear() { TenantContext.clear(); ServicePrincipalContext.clear(); ManagementContext.clear(); }

    /** active 唯一性不妨碍显式新 ID 重授；旧创建回执从不复活被撤销记录。 */
    @Test void revokeIsTerminalAndExplicitRegrantCreatesANewIdForEachOfTheFourActions() {
        for (String action : List.of(READ, "tenant.agent_deployments.create", "tenant.agent_deployments.bind", "tenant.agent_deployments.switch_authorization")) {
            var command = new Create(hq, child, action, 1L);
            var first = platformTx(() -> platformService.create(command, "create-" + child + action));
            assertEquals(first, platformTx(() -> platformService.create(command, "create-" + child + action)));
            assertEquals(409, assertThrows(ApiException.class, () -> platformTx(() -> platformService.create(command, "duplicate-" + child + action))).getStatus().value());
            var revoked = platformTx(() -> platformService.revoke(new Revoke(Long.parseLong(first.grantId()), 1L), "revoke-" + first.grantId()));
            assertEquals("revoked", revoked.status()); assertEquals(2, revoked.revision());
            assertEquals(first, platformTx(() -> platformService.create(command, "create-" + child + action)));
            var second = platformTx(() -> platformService.create(command, "regrant-" + child + action));
            assertNotEquals(first.grantId(), second.grantId());
            assertEquals("revoked", owner.queryForObject("SELECT status FROM nw_institution_management_grants WHERE id=?", String.class, Long.parseLong(first.grantId())));
            assertEquals(1, owner.queryForObject("SELECT count(*) FROM nw_institution_management_grants WHERE actor_ref=? AND target_tenant_id=? AND action=? AND status='active'", Integer.class, "tenant:" + hq, child, action));
            assertEquals(1, owner.queryForObject("SELECT count(*) FROM nw_audit_logs WHERE resource_id=? AND action='platform.institution_management_grants.create'", Integer.class, first.grantId()));
            assertEquals(1, owner.queryForObject("SELECT count(*) FROM nw_audit_logs WHERE resource_id=? AND action='platform.institution_management_grants.revoke'", Integer.class, first.grantId()));
        }
    }

    /** 共享来源或祖先关系不构成本许可，执行只认直接父子、精确历史依据及当前运营状态。 */
    @Test void restrictedTenantOnlySeesExactCurrentDirectGrantAndOldRelationNeverRevives() {
        var grant = platformTx(() -> platformService.create(new Create(hq, child, READ, 1L), "read-" + child));
        var proof = asHeadquarters(() -> tenantTx(() -> tenantService.requireTarget(Long.toString(child), READ)));
        assertEquals(grant.grantId(), proof.grantId()); assertEquals("tenant:" + hq + ":member:70", proof.actorRef());
        assertEquals(Set.of(child), asHeadquarters(() -> tenantTx(() -> tenantService.authorizedTargets(READ))));
        assertEquals(403, assertThrows(ApiException.class, () -> asHeadquarters(() -> tenantTx(() -> tenantService.requireTarget(Long.toString(grandchild), READ)))).getStatus().value());
        owner.update("UPDATE nw_tenants SET status='suspended' WHERE id=?", child);
        assertEquals(Set.of(), asHeadquarters(() -> tenantTx(() -> tenantService.authorizedTargets(READ))));
        owner.update("UPDATE nw_tenants SET status='active',parent_tenant_id=?,relation_revision=2 WHERE id=?", other, child);
        appendRelation(child, other);
        owner.update("UPDATE nw_tenants SET parent_tenant_id=?,relation_revision=3 WHERE id=?", hq, child);
        appendRelation(child, hq);
        assertEquals(Set.of(), asHeadquarters(() -> tenantTx(() -> tenantService.authorizedTargets(READ))));
        assertEquals(403, assertThrows(ApiException.class, () -> asHeadquarters(() -> tenantTx(() -> tenantService.requireTarget(Long.toString(child), READ)))).getStatus().value());
        assertEquals(grant, platformTx(() -> platformService.create(new Create(hq, child, READ, 1L), "read-" + child)));
        var replacement = platformTx(() -> platformService.create(new Create(hq, child, READ, 3L), "new-relation-" + child));
        assertNotEquals(grant.grantId(), replacement.grantId()); assertNotEquals(grant.relationRef(), replacement.relationRef());
    }

    /** SQL 双目标过滤先于计数和页切片，单边平台目标不获得许可历史。 */
    @Test void platformListFiltersBothInstitutionsBeforePaginationAndReceiptReplayChecksCurrentAuthority() {
        var command = new Create(hq, child, READ, 1L);
        var grant = platformTx(() -> platformService.create(command, "visible-" + child));
        for (Set<Long> scope : List.of(Set.of(hq), Set.of(child))) {
            targets.set(scope);
            assertEquals(0, platformTx(() -> platformService.platformList(null, null, 1, 1)).total());
            assertEquals(403, assertThrows(ApiException.class, () -> platformTx(() -> platformService.create(command, "visible-" + child))).getStatus().value());
            assertEquals(404, assertThrows(ApiException.class, () -> platformTx(() -> platformService.revoke(new Revoke(Long.parseLong(grant.grantId()), 1L), "hidden-" + child))).getStatus().value());
        }
        targets.set(Set.of(hq, child));
        var page = platformTx(() -> platformService.platformList(null, null, 1, 1));
        assertEquals(1, page.total()); assertEquals(List.of(grant), page.items());
        assertEquals(List.of(), platformTx(() -> platformService.platformList(null, null, 2, 1)).items());
    }

    /** 正式审计写入失败时，创建与撤销的许可和回执都由同一数据库事务回滚。 */
    @Test void realAuditFailureRollsBackBothCreateAndRevokeWithTheirReceipts() {
        String key = "audit-create-" + child;
        var failure = assertThrows(RuntimeException.class, () -> platformTx(() -> {
            new JdbcTemplate(platform).execute("SELECT set_config('test.fail_deployment_grant_audit','true',true)");
            return platformService.create(new Create(hq, child, READ, 1L), key);
        }));
        assertTrue(exceptionContains(failure, "simulated_deployment_grant_audit_failure"));
        assertEquals(0, owner.queryForObject("SELECT count(*) FROM nw_institution_management_grants WHERE target_tenant_id=?", Integer.class, child));
        assertEquals(0, owner.queryForObject("SELECT count(*) FROM nw_institution_relation_requests WHERE command_ref=?", Integer.class, key));
        var grant = platformTx(() -> platformService.create(new Create(hq, child, READ, 1L), "create-ok-" + child));
        String revokeKey = "audit-revoke-" + child;
        assertThrows(RuntimeException.class, () -> platformTx(() -> {
            new JdbcTemplate(platform).execute("SELECT set_config('test.fail_deployment_grant_audit','true',true)");
            return platformService.revoke(new Revoke(Long.parseLong(grant.grantId()), 1L), revokeKey);
        }));
        assertEquals("active", owner.queryForObject("SELECT status FROM nw_institution_management_grants WHERE id=?", String.class, Long.parseLong(grant.grantId())));
        assertEquals(1, owner.queryForObject("SELECT revision FROM nw_institution_management_grants WHERE id=?", Integer.class, Long.parseLong(grant.grantId())));
        assertEquals(0, owner.queryForObject("SELECT count(*) FROM nw_institution_relation_requests WHERE command_ref=?", Integer.class, revokeKey));
        assertEquals(0, owner.queryForObject("SELECT count(*) FROM nw_audit_logs WHERE resource_id=? AND action='platform.institution_management_grants.revoke'", Integer.class, grant.grantId()));
    }

    /** GI-15 目录许可继续被数据库接受，未批准的跨域动作仍受 CHECK 拒绝。 */
    @Test void migrationPreservesDirectoryGrantAndRejectsUnapprovedActions() {
        assertEquals(1, owner.update("INSERT INTO nw_institution_management_grants(id,actor_domain,actor_ref,target_tenant_id,action,status,revision,relation_ref) VALUES(?,'tenant',?,?,'tenant.sub_institutions.read','active',1,?)",
                SnowflakeIds.nextId(), "tenant:" + hq, child, relation));
        assertThrows(RuntimeException.class, () -> owner.update("INSERT INTO nw_institution_management_grants(id,actor_domain,actor_ref,target_tenant_id,action,status,revision,relation_ref) VALUES(?,'tenant',?,?,'tenant.sessions.read','active',1,?)",
                SnowflakeIds.nextId(), "tenant:" + hq, child, relation));
        assertEquals(Set.of(), asHeadquarters(() -> tenantTx(() -> tenantService.authorizedTargets(READ))));
    }

    /** 创建真实机构，保持计划和机构树数据库约束。 */
    private long tenant(Long parent) {
        long id = SnowflakeIds.nextId(); Long plan = owner.queryForObject("SELECT id FROM nw_billing_plans WHERE is_active ORDER BY id LIMIT 1", Long.class);
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id,status,deployment_mode,settings,parent_tenant_id) VALUES(?,?,?,?,'active','cloud','{}',?)",
                id, "deployment-grant-" + id, "deployment-grant-" + id, plan, parent);
        return id;
    }
    /** 为当前实际父子关系追加不可变依据，不修改旧历史。 */
    private long appendRelation(long target, long parent) {
        long id = SnowflakeIds.nextId();
        owner.update("INSERT INTO nw_institution_relation_revisions(id,child_tenant_id,parent_tenant_id,revision,tree_revision,actor_ref,command_ref) SELECT ?,id,?,relation_revision,(SELECT revision FROM nw_institution_tree_revision WHERE singleton),'platform_member:test',? FROM nw_tenants WHERE id=?",
                id, parent, "relation-" + id, target);
        return id;
    }
    /** 与正式平台数据源的事务所有者一致。 */
    private <T> T platformTx(Supplier<T> work) { return platformWriter.execute(status -> work.get()); }
    /** 在受限租户连接中持有授权共享锁。 */
    private <T> T tenantTx(Supplier<T> work) { return tenantWriter.execute(status -> work.get()); }
    /** 真实总部上下文作用域不从目标 ID 派生。 */
    private <T> T asHeadquarters(Supplier<T> work) {
        try { TenantContext.setTenantId(Long.toString(hq)); return work.get(); } finally { TenantContext.clear(); }
    }
    /** 断言真正的数据库触发器失败而不是无关异常。 */
    private boolean exceptionContains(Throwable failure, String expected) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current.getMessage() != null && current.getMessage().contains(expected)) return true;
        return false;
    }
}
