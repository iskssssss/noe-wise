package com.skillforge.core.foundation.institution;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.util.List;
import java.util.Set;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 正式 XML、完整迁移与无 BYPASSRLS 的 PG 连接验证授权/部署两个 SQL 分支。 */
@Testcontainers(disabledWithoutDocker=false)
class InstitutionTargetOptionDatabaseTest {
    private static final String READ = "tenant.agent_deployments.read";
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("institution_target_options").withUsername("skillforge").withPassword("isolated-test-password");
    private static JdbcTemplate owner;
    private static SqlSessionTemplate sessions;
    private static TransactionTemplate reader;
    private InstitutionTargetOptionMapper options;
    private InstitutionDeploymentManagementService management;
    private long hq, first, second, foreign, grandchild, suspended, stale;

    @BeforeAll static void database() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("CREATE ROLE target_option_reader LOGIN PASSWORD 'isolated-role-password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO target_option_reader");
        owner.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO target_option_reader");
        assertTrue(owner.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname='target_option_reader'", Boolean.class));
        var restricted = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "target_option_reader", "isolated-role-password");
        var configuration = new MybatisConfiguration(new Environment("institution-target-options", new SpringManagedTransactionFactory(), restricted));
        configuration.setMapUnderscoreToCamelCase(true); configuration.addInterceptor(new TenantRlsInterceptor());
        for (String path : List.of("mapper/InstitutionTargetOptionMapper.xml", "mapper/InstitutionDeploymentManagementMapper.xml"))
            try (var xml = new ClassPathResource(path).getInputStream()) { new XMLMapperBuilder(xml, configuration, path, configuration.getSqlFragments()).parse(); }
        sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        reader = new TransactionTemplate(new DataSourceTransactionManager(restricted)); reader.setReadOnly(true);
    }
    @BeforeEach void fixture() {
        hq = tenant(null, "总部"); first = tenant(hq, "A 分店"); second = tenant(hq, "B 分店");
        foreign = tenant(null, "其他机构"); grandchild = tenant(first, "孙机构"); suspended = tenant(hq, "停用分店"); stale = tenant(hq, "缺少当前关系依据");
        long firstRelation = relation(first, hq), secondRelation = relation(second, hq);
        relation(grandchild, first); relation(suspended, hq);
        owner.update("UPDATE nw_tenants SET status='suspended' WHERE id=?", suspended);
        grant(first, firstRelation, READ); grant(second, secondRelation, "tenant.agent_deployments.create");
        options = sessions.getMapper(InstitutionTargetOptionMapper.class);
        var foundation = mock(FoundationAccess.class);
        when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(false, Long.toString(hq), "70"));
        management = new InstitutionDeploymentManagementService(sessions.getMapper(InstitutionDeploymentManagementMapper.class),
                mock(InstitutionRelationMapper.class), mock(PlatformManagementService.class), foundation,
                mock(InstitutionIntegrationService.class), mock(AuditService.class), new ObjectMapper());
        TenantContext.setTenantId(Long.toString(hq));
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void grantBranchFiltersCurrentDirectRelationshipsBeforeNameIdAndPaginationWithoutDirectoryGrant() {
        assertEquals(2, tx(() -> options.count(hq, true, Set.of(), "分店", null)));
        assertEquals(List.of(Long.toString(second)), tx(() -> options.rows(hq, true, Set.of(), "分店", null, 1, 1)).stream().map(InstitutionTargetOption::id).toList());
        var selected = tx(() -> options.rows(hq, true, Set.of(), "", first, 200, 0));
        assertEquals("A 分店", selected.getFirst().label()); assertEquals(Long.toString(first), selected.getFirst().description());
        for (long excluded : List.of(hq, foreign, grandchild, suspended, stale))
            assertTrue(tx(() -> options.rows(hq, true, Set.of(), "", excluded, 200, 0)).isEmpty());
    }
    @Test void deploymentBranchUsesExactCurrentActionPermissionPlusOwnTenantAndDropsMovedRevokedOrInactiveTargets() {
        var scope = tx(() -> management.requireTargetOptions(READ));
        assertEquals(Set.of(hq, first), scope.tenantIds());
        assertEquals(2, tx(() -> options.count(hq, false, scope.tenantIds(), "", null)));
        assertEquals(List.of(Long.toString(hq), Long.toString(first)), tx(() -> options.rows(hq, false, scope.tenantIds(), "", null, 200, 0)).stream().map(InstitutionTargetOption::id).toList());
        assertTrue(tx(() -> options.rows(hq, false, Set.of(hq, first), "", second, 200, 0)).isEmpty());
        owner.update("UPDATE nw_institution_management_grants SET status='revoked',revision=revision+1 WHERE target_tenant_id=? AND action=?", first, READ);
        assertEquals(Set.of(hq), tx(() -> management.requireTargetOptions(READ)).tenantIds());
        owner.update("UPDATE nw_tenants SET parent_tenant_id=?,relation_revision=relation_revision+1 WHERE id=?", foreign, first);
        relation(first, foreign);
        assertTrue(tx(() -> options.rows(hq, false, Set.of(hq, first), "", first, 200, 0)).isEmpty());
        owner.update("UPDATE nw_tenants SET status='suspended' WHERE id=?", hq);
        assertEquals(0, tx(() -> options.count(hq, false, Set.of(hq), "", null)));
    }
    private long tenant(Long parent, String name) {
        long id = SnowflakeIds.nextId(); Long plan = owner.queryForObject("SELECT id FROM nw_billing_plans WHERE is_active ORDER BY id LIMIT 1", Long.class);
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id,status,deployment_mode,settings,parent_tenant_id) VALUES(?,?,?,?,'active','cloud','{}',?)", id, name, "target-option-" + id, plan, parent);
        return id;
    }
    private long relation(long child, long parent) {
        long id = SnowflakeIds.nextId();
        owner.update("INSERT INTO nw_institution_relation_revisions(id,child_tenant_id,parent_tenant_id,revision,tree_revision,actor_ref,command_ref) SELECT ?,id,?,relation_revision,(SELECT revision FROM nw_institution_tree_revision WHERE singleton),'platform_member:test',? FROM nw_tenants WHERE id=?", id, parent, "target-option-" + id, child);
        return id;
    }
    private void grant(long child, long relation, String action) {
        owner.update("INSERT INTO nw_institution_management_grants(id,actor_domain,actor_ref,target_tenant_id,action,status,revision,relation_ref) VALUES(?,'tenant',?,?,?,'active',1,?)", SnowflakeIds.nextId(), "tenant:" + hq, child, action, relation);
    }
    private <T> T tx(Supplier<T> work) { return reader.execute(status -> work.get()); }
}
