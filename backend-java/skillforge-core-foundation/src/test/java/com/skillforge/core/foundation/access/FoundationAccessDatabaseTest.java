package com.skillforge.core.foundation.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.foundation.application.AuditService;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.Configuration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 在隔离 PostgreSQL 的完整迁移链和 NOSUPERUSER/NOBYPASSRLS 连接验证实际 XML、RLS 与竞争。
 * 此测试不连接现有业务数据库，也不依赖存量管理员或真实凭据。
 */
@Testcontainers(disabledWithoutDocker=true)
class FoundationAccessDatabaseTest {
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16").withDatabaseName("foundation_access").withUsername("skillforge").withPassword("synthetic_test_password");
    private static JdbcTemplate owner;
    private static FoundationAccessMapper authorization;
    private static FoundationOrganizationMapper organization;
    private static FoundationAccess access;
    private static FoundationOrganizationService service;
    private static FoundationOptionService options;
    private static TransactionTemplate writer;
    private static AuthorizationDataSource connections;
    private static final String TENANT="2741001";
    private static final String OTHER="2741002";
    private static final FoundationAccess.Subject ADMIN_A=new FoundationAccess.Subject(false,TENANT,"2741101");
    private static final FoundationAccess.Subject ADMIN_B=new FoundationAccess.Subject(false,TENANT,"2741102");

    /** 真实迁移后创建受限连接，生产授权不能借助表所有者或超级用户绕过 RLS。 */
    @BeforeAll static void database() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()).locations("classpath:db/migration").load().migrate();
        owner=new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()));
        owner.execute("CREATE ROLE foundation_access_test LOGIN PASSWORD 'synthetic_restricted_password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO foundation_access_test"); owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO foundation_access_test");
        connections=new AuthorizationDataSource(new DriverManagerDataSource(DATABASE.getJdbcUrl(),"foundation_access_test","synthetic_restricted_password"));
        Configuration configuration=new Configuration();configuration.setMapUnderscoreToCamelCase(true);configuration.addInterceptor(new TenantRlsInterceptor());
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(connections);factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/foundation/FoundationAccessMapper.xml"),new ClassPathResource("mapper/foundation/FoundationOrganizationMapper.xml"),new ClassPathResource("mapper/foundation/FoundationOptionMapper.xml"));
        SqlSessionTemplate session=new SqlSessionTemplate(factory.getObject());authorization=session.getMapper(FoundationAccessMapper.class);organization=session.getMapper(FoundationOrganizationMapper.class);
        FoundationPermissionContributor sessions=()->List.of(new FoundationPermission("foundation.sessions.read","会话读取",FoundationPermission.Audience.TENANT));
        var transactions=new DataSourceTransactionManager(connections);
        FoundationPermissionCatalogue catalogue=new FoundationPermissionCatalogue(List.of(new AccessPermissions(),sessions,new com.skillforge.core.foundation.security.SecurityPermissions(),new com.skillforge.core.foundation.messaging.config.MessagingPermissions(),new com.skillforge.core.foundation.catalog.CatalogPermissionContributor()));access=new FoundationAccess(authorization,catalogue,transactions);
        options=new FoundationOptionService(access,session.getMapper(FoundationOptionMapper.class));
        service=new FoundationOrganizationService(access,organization,authorization,catalogue,new BCryptPasswordEncoder(),mock(AuditService.class),List.of(),mock(FeatureGate.class),mock(ResourceQuota.class));
        writer=new TransactionTemplate(transactions);
    }

    /** 重建本测试租户合法基线，不更改其他测试或外部数据。 */
    @BeforeEach void fixture(){
        owner.update("DELETE FROM nw_foundation_resource_grants WHERE scope_key IN (?,?)",TENANT,OTHER);
        owner.update("DELETE FROM nw_foundation_resource_ownership WHERE scope_key IN (?,?)",TENANT,OTHER);
        owner.update("DELETE FROM nw_foundation_member_roles WHERE scope_key IN (?,?)",TENANT,OTHER);
        owner.update("DELETE FROM nw_foundation_member_profiles WHERE scope_key IN (?,?)",TENANT,OTHER);
        owner.update("DELETE FROM nw_foundation_roles WHERE scope_key IN (?,?)",TENANT,OTHER);
        owner.update("DELETE FROM nw_foundation_departments WHERE scope_key IN (?,?)",TENANT,OTHER);
        owner.update("DELETE FROM nw_foundation_positions WHERE scope_key IN (?,?)",TENANT,OTHER);
        owner.update("DELETE FROM nw_foundation_access_scopes WHERE scope_key IN (?,?)",TENANT,OTHER);
        owner.update("DELETE FROM nw_tenant_members WHERE tenant_id IN (2741001,2741002)"); owner.update("DELETE FROM nw_agents WHERE tenant_id IN (2741001,2741002)");
        owner.update("DELETE FROM nw_tenants WHERE id IN (2741001,2741002)");
        owner.update("DELETE FROM nw_foundation_member_roles WHERE scope_key='platform' AND member_id IN(2741701,2741702)");
        owner.update("DELETE FROM nw_foundation_member_profiles WHERE scope_key='platform' AND member_id IN(2741701,2741702)");
        owner.update("DELETE FROM nw_platform_members WHERE id IN(2741701,2741702)");
        Long plan=owner.queryForObject("SELECT id FROM nw_billing_plans ORDER BY id LIMIT 1",Long.class);
        for(String tenant:List.of(TENANT,OTHER)) owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id,status,deployment_mode,settings) VALUES(?::bigint,?,?,?,'active','cloud','{}'::jsonb)",tenant,"隔离授权测试", "foundation-"+tenant,plan);
        for(long id:List.of(2741101L,2741102L)) owner.update("INSERT INTO nw_tenant_members(id,tenant_id,username,status,password_hash) VALUES(?,2741001,?,'active','synthetic-unused')",id,"member-"+id);
        owner.update("INSERT INTO nw_tenant_members(id,tenant_id,username,status,password_hash) VALUES(2741201,2741002,'foreign-member','active','synthetic-unused')");
        writer.execute(status->{service.initializeTenant(TENANT,ADMIN_A.memberId());return null;});
        access.withSubject(ADMIN_A,()->writer.execute(status->{service.bootstrapMember(ADMIN_B,"tenant_admin");return null;}));
        writer.execute(status->{service.initializeTenant(OTHER,"2741201");return null;});
    }
    /** 保证线程租户不污染下一例。 */ @AfterEach void clear(){TenantContext.clear();}

    /** 名称候选与已选解析都必须在真实受限连接中保留租户、动作和对象范围。 */
    @Test void optionDirectoriesFilterBeforeSearchPaginationAndSelectedResolution(){
        owner.update("INSERT INTO nw_agents(id,tenant_id,agent_key,name) VALUES(2741401,2741001,'candidate-own','同名资源')");
        owner.update("INSERT INTO nw_agents(id,tenant_id,agent_key,name) VALUES(2741402,2741001,'candidate-other','同名资源')");
        owner.update("INSERT INTO nw_agents(id,tenant_id,agent_key,name) VALUES(2741403,2741002,'candidate-foreign','同名资源')");
        owner.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(2741501,?,'option_operator','目录操作员','self')",TENANT);
        for(String permission:List.of(AccessPermissions.RESOURCE_MANAGE,"foundation.resource.agent.manage","foundation.messaging.publish",com.skillforge.core.foundation.security.SecurityPermissions.REVOKE))
            owner.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,2741501,?)",TENANT,permission);
        owner.update("DELETE FROM nw_foundation_member_roles WHERE scope_key=? AND member_id=2741102",TENANT);
        owner.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,2741102,2741501)",TENANT);
        owner.update("INSERT INTO nw_foundation_resource_ownership(scope_key,kind,resource_id,owner_member_id) VALUES(?,'agent',2741401,2741102)",TENANT);
        owner.update("INSERT INTO nw_foundation_resource_ownership(scope_key,kind,resource_id,owner_member_id) VALUES(?,'agent',2741402,2741101)",TENANT);
        access.withSubject(ADMIN_B,()->{
            assertThat(access.currentSnapshot().permissions()).doesNotContain(AccessPermissions.READ);
            assertThat(options.resources("agent","同名",List.of(),1).items()).extracting(FoundationOptionService.Option::id).containsExactly("2741401");
            assertThat(options.resources("agent","",List.of("2741402","2741403"),1).items()).isEmpty();
            assertThat(options.members("security","",List.of("2741101","2741102","2741201"),1).items()).extracting(FoundationOptionService.Option::id).containsExactly("2741101","2741102");
            assertThat(options.members("messaging","",List.of(),1).items()).extracting(FoundationOptionService.Option::id).containsExactly("2741102");
            assertThat(options.resourceMembers("resource-owner","agent","2741401","",List.of("2741101","2741102","2741201"),1).items()).extracting(FoundationOptionService.Option::id).containsExactly("2741102");
            owner.update("UPDATE nw_tenant_members SET status='disabled' WHERE id=2741101");
            assertThat(options.resourceMembers("resource-grantee","agent","2741401","",List.of("2741101","2741102","2741201"),1).items()).extracting(FoundationOptionService.Option::id).containsExactly("2741101","2741102");
            assertThatThrownBy(()->options.resourceMembers("resource-owner","agent","2741402","",List.of(),1)).isInstanceOf(ApiException.class);
            assertThat(options.recipientGroups("role","",List.of(),1).items()).extracting(FoundationOptionService.Option::code).containsExactly("option_operator");
            assertThat(options.recipientGroups("department","",List.of(),1).items()).isEmpty();
            assertThatThrownBy(()->options.tenants("",List.of(),1)).isInstanceOf(ApiException.class);
            return null;
        });
    }

    /** 普通候选分页真实探测第51项；精确解析不受分页和同名对象影响。 */
    @Test void optionDirectoryPaginatesRealRowsAndKeepsExactSelectedIds(){
        owner.update("INSERT INTO nw_agents(id,tenant_id,agent_key,name) SELECT 2741800+i,2741001,'option-page-'||i,'目录分页' FROM generate_series(1,75) i");
        access.withSubject(ADMIN_A,()->{
            var first=options.resources("agent","目录分页",List.of(),1);
            var second=options.resources("agent","目录分页",List.of(),2);
            assertThat(first.items()).hasSize(50);assertThat(first.hasMore()).isTrue();
            assertThat(second.items()).hasSize(25);assertThat(second.hasMore()).isFalse();
            assertThat(first.items()).extracting(FoundationOptionService.Option::id).doesNotContainAnyElementsOf(second.items().stream().map(FoundationOptionService.Option::id).toList());
            assertThat(options.resources("agent","",List.of("2741875"),1).items()).extracting(FoundationOptionService.Option::id).containsExactly("2741875");
            return null;
        });
    }

    /** 单次授权所有真实 Mapper 共用一个受限连接，回调不持有事务；下一次仍读取已提交撤权。 */
    @Test void everyAuthorizationReadUsesOneRestrictedConnectionAndEndsBeforeBusinessCallback(){
        owner.update("INSERT INTO nw_agents(id,tenant_id,agent_key,name) VALUES(2741401,2741001,'foundation-short-read','短读事务资源')");
        owner.update("INSERT INTO nw_agents(id,tenant_id,agent_key,name) VALUES(2741402,2741002,'foundation-short-read-foreign','其他租户资源')");
        owner.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(2741501,?,'reader','只读','self')",TENANT);
        for(String permission:List.of("foundation.resource.agent.read","foundation.sessions.read"))
            owner.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,2741501,?)",TENANT,permission);
        owner.update("DELETE FROM nw_foundation_member_roles WHERE scope_key=? AND member_id=2741102",TENANT);
        owner.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,2741102,2741501)",TENANT);
        owner.update("INSERT INTO nw_foundation_resource_ownership(scope_key,kind,resource_id,owner_member_id) VALUES(?,'agent',2741401,2741101)",TENANT);
        owner.update("INSERT INTO nw_foundation_resource_grants(id,scope_key,kind,resource_id,grantee_type,grantee_id,action,created_by_id) VALUES(2741601,?,'agent',2741401,'member',2741102,'read',2741101)",TENANT);
        TenantContext.setTenantId(OTHER);
        oneConnection(()->assertThat(access.snapshot(ADMIN_B).permissions()).contains("foundation.resource.agent.read"));
        oneConnection(()->access.require(ADMIN_B,"foundation.resource.agent.read"));
        oneConnection(()->assertThat(access.canAccessResource(ADMIN_B,"agent","2741401","read")).isTrue());
        oneConnection(()->assertThat(access.canAccessResource(ADMIN_B,"agent","2741402","read")).isFalse());
        oneConnection(()->assertThat(access.resourceFilter(ADMIN_B,"agent","read").resourceIds()).containsExactly("2741401"));
        oneConnection(()->assertThat(access.dataScope(ADMIN_B).all()).isFalse());
        oneConnection(()->assertThat(access.visibleSessionIds(ADMIN_B,"read")).isEmpty());
        oneConnection(()->assertThatThrownBy(()->access.requireSession(ADMIN_B,"missing-session","read")).isInstanceOf(ApiException.class));
        oneConnection(()->access.withSubject(ADMIN_B,()->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TenantContext.getTenantId()).isEqualTo(TENANT);
            return null;
        }));
        assertThat(TenantContext.getTenantId()).isEqualTo(OTHER);
        owner.update("DELETE FROM nw_foundation_resource_grants WHERE id=2741601");
        oneConnection(()->assertThat(access.canAccessResource(ADMIN_B,"agent","2741401","read")).isFalse());
        owner.update("UPDATE nw_foundation_roles SET enabled=false,lock_version=lock_version+1 WHERE id=2741501");
        oneConnection(()->assertThat(access.allowed(ADMIN_B,"foundation.resource.agent.read")).isFalse());
        owner.update("UPDATE nw_tenant_members SET status='disabled' WHERE id=2741102");
        oneConnection(()->assertThatThrownBy(()->access.snapshot(ADMIN_B)).isInstanceOf(ApiException.class));
        assertThat(TenantContext.getTenantId()).isEqualTo(OTHER);
    }

    /** 连接计数负控：删除短事务后，多条真实 XML 查询会借用多个连接，精确等于一的断言失败。 */
    private void oneConnection(Runnable operation){
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        int before=connections.borrowed.get();
        operation.run();
        assertThat(connections.borrowed.get()-before).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    /** 仅计数测试受限数据源的真实连接借用，不替换授权、SQL、租户或事务行为。 */
    private static final class AuthorizationDataSource extends DelegatingDataSource {
        final AtomicInteger borrowed=new AtomicInteger();
        AuthorizationDataSource(javax.sql.DataSource target){super(target);}
        @Override public Connection getConnection() throws SQLException {borrowed.incrementAndGet();return super.getConnection();}
        @Override public Connection getConnection(String username,String password) throws SQLException {borrowed.incrementAndGet();return super.getConnection(username,password);}
    }

    /** 受限连接即使传入其他租户 scope 参数也不能读取或写入。 */
    @Test void rlsRejectsCrossTenantAndCompositeReferences(){
        owner.update("INSERT INTO nw_foundation_departments(id,scope_key,code,name,created_by_id,updated_by_id) VALUES(2741301,?,'foreign','其他机构部门',2741201,2741201)",OTHER);
        TenantContext.setTenantId(TENANT);assertThat(organization.departments(OTHER)).isEmpty();
        AccessRows.Department forged=new AccessRows.Department();forged.setId(2741302L);forged.setCode("forged");forged.setName("伪造");
        assertThatThrownBy(()->organization.insertDepartment(OTHER,forged,2741101L)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->owner.update("UPDATE nw_foundation_member_profiles SET department_id=2741301 WHERE scope_key=? AND member_id=2741101",TENANT)).isInstanceOf(RuntimeException.class);
    }
    /** 真实同租户对象及授权关系读取；共享撤回后下一请求立即拒绝。 */
    @Test void resourceGrantRevocationAndDisabledRolesAreImmediate(){
        owner.update("INSERT INTO nw_agents(id,tenant_id,agent_key,name) VALUES(2741401,2741001,'foundation-resource','授权资源')");
        owner.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(2741501,?,'reader','只读','self')",TENANT);
        owner.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,2741501,'foundation.resource.agent.read')",TENANT);
        owner.update("DELETE FROM nw_foundation_member_roles WHERE scope_key=? AND member_id=2741102",TENANT);
        owner.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,2741102,2741501)",TENANT);
        owner.update("INSERT INTO nw_foundation_resource_ownership(scope_key,kind,resource_id,owner_member_id) VALUES(?,'agent',2741401,2741101)",TENANT);
        assertThat(access.canAccessResource(ADMIN_B,"agent","2741401","read")).isFalse();
        owner.update("INSERT INTO nw_foundation_resource_grants(id,scope_key,kind,resource_id,grantee_type,grantee_id,action,created_by_id) VALUES(2741601,?,'agent',2741401,'member',2741102,'read',2741101)",TENANT);
        assertThat(access.canAccessResource(ADMIN_B,"agent","2741401","read")).isTrue();
        owner.update("DELETE FROM nw_foundation_resource_grants WHERE id=2741601"); assertThat(access.canAccessResource(ADMIN_B,"agent","2741401","read")).isFalse();
        owner.update("UPDATE nw_foundation_roles SET enabled=false,lock_version=lock_version+1 WHERE id=2741501"); assertThat(access.snapshot(ADMIN_B).permissions()).doesNotContain("foundation.resource.agent.read");
    }
    /** 受信外部删除清理只影响绑定租户，并由数据库 FK 原子级联共享关系。 */
    @Test void trustedResourceCleanupIsTenantBoundAndCascadesGrants(){
        owner.update("INSERT INTO nw_agents(id,tenant_id,agent_key,name) VALUES(2741401,2741001,'foundation-cleanup','清理资源')");
        owner.update("INSERT INTO nw_foundation_resource_ownership(scope_key,kind,resource_id,owner_member_id) VALUES(?,'agent',2741401,2741101)",TENANT);
        owner.update("INSERT INTO nw_foundation_resource_grants(id,scope_key,kind,resource_id,grantee_type,grantee_id,action,created_by_id) VALUES(2741601,?,'agent',2741401,'member',2741102,'read',2741101)",TENANT);
        TenantContext.setTenantId(TENANT);
        assertThatThrownBy(()->writer.execute(status->{service.forgetResourceForTenant(OTHER,"agent","2741401");return null;})).isInstanceOf(ApiException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_foundation_resource_grants WHERE id=2741601",Long.class)).isEqualTo(1L);
        writer.execute(status->{service.forgetResourceForTenant(TENANT,"agent","2741401");return null;});
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_foundation_resource_ownership WHERE scope_key=? AND kind='agent' AND resource_id=2741401",Long.class,TENANT)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_foundation_resource_grants WHERE id=2741601",Long.class)).isZero();
    }
    /** 两个管理员互相停用时真实 scope 行锁允许一次写入并阻止最后管理员停用。 */
    @Test void concurrentDisableRetainsExactlyOneActiveAdministrator() throws Exception {
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->disable(ADMIN_A,ADMIN_B,ready,start));var second=executor.submit(()->disable(ADMIN_B,ADMIN_A,ready,start));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            List<Boolean> results=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));assertThat(results).containsExactlyInAnyOrder(true,false);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_tenant_members WHERE tenant_id=2741001 AND status='active'",Long.class)).isEqualTo(1L);
    }
    /** 真数据库首启动完成账号和正式角色；重复入口不再调用账号创建回调。 */
    @Test void firstPlatformAdministratorIsAtomicAndRepeatDoesNotElevateAnotherAccount(){
        assertThat(writer.<Boolean>execute(status->service.initializePlatformAdministrator("2741701",()->insertPlatformMember(2741701L,"active")))).isTrue();
        var first=new FoundationAccess.Subject(true,null,"2741701");
        assertThat(access.snapshot(first).roles()).containsExactly("platform_admin");
        assertThat(access.snapshot(first).permissions()).contains(AccessPermissions.ORGANIZATION_MANAGE);
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        assertThat(writer.<Boolean>execute(status->service.initializePlatformAdministrator("2741702",()->called.set(true)))).isFalse();
        assertThat(called).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_platform_members WHERE id IN(2741701,2741702)",Long.class)).isEqualTo(1L);
        assertThat(access.snapshot(first).roles()).containsExactly("platform_admin");
    }
    /** 创建了非法停用账号但未完成建制时，真实外层事务回滚账号与所有授权行。 */
    @Test void invalidPlatformBootstrapRollsBackTheRealAccount(){
        assertThatThrownBy(()->writer.execute(status->service.initializePlatformAdministrator("2741701",()->insertPlatformMember(2741701L,"disabled")))).isInstanceOf(ApiException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_platform_members WHERE id=2741701",Long.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_foundation_member_roles WHERE scope_key='platform' AND member_id=2741701",Long.class)).isZero();
    }
    /** 两个启动实例争用同一 scope 锁，只执行一个真实创建回调并建立一个管理员。 */
    @Test void concurrentFirstPlatformBootstrapCreatesOnlyOneAdministrator() throws Exception {
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->bootstrapPlatform(2741701L,ready,start));var second=executor.submit(()->bootstrapPlatform(2741702L,ready,start));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_platform_members WHERE id IN(2741701,2741702)",Long.class)).isEqualTo(1L);
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_foundation_member_roles WHERE scope_key='platform' AND member_id IN(2741701,2741702)",Long.class)).isEqualTo(1L);
    }
    /** 相同真实事务连接写入平台账号，不用超级用户夹具伪造被测提交。 */
    private void insertPlatformMember(long id,String status){
        AccessRows.Member member=new AccessRows.Member();member.setId(id);member.setUsername("platform-bootstrap-"+id);member.setDisplayName("首启管理员");member.setStatus(status);
        organization.insertMember("platform",member,"synthetic-unused");
    }
    /** 栅栏仅同步启动竞争，实际串行性来自数据库授权体系行锁。 */
    private boolean bootstrapPlatform(long memberId,CountDownLatch ready,CountDownLatch start) throws Exception {
        ready.countDown();if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("平台启动栅栏超时");
        try{return writer.execute(status->service.initializePlatformAdministrator(Long.toString(memberId),()->insertPlatformMember(memberId,"active")));}
        finally{TenantContext.clear();}
    }
    /** 栅栏使两个真实事务同时竞争；失败必须为权限或最后管理员保护，而非测试线程静默异常。 */
    private boolean disable(FoundationAccess.Subject actor,FoundationAccess.Subject target,CountDownLatch ready,CountDownLatch start) throws Exception {
        ready.countDown();if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("并发栅栏超时");
        try{return access.withSubject(actor,()->writer.execute(status->service.changeAccount(target,true,()->{organization.updateMemberAccount(target.scope(),disabled(target.memberId()));return true;})));}
        catch(ApiException rejected){assertThat(rejected.getStatus().value()).isIn(401,409);return false;}
        finally{TenantContext.clear();}
    }
    /** 状态命令只写真实账号状态，资料和密码不来自测试推导。 */
    private AccessRows.Member disabled(String id){AccessRows.Member member=new AccessRows.Member();member.setId(Long.valueOf(id));member.setStatus("disabled");return member;}
}
