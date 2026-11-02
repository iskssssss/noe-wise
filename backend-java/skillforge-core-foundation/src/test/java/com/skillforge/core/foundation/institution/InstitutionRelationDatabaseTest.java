package com.skillforge.core.foundation.institution;

import static com.skillforge.core.foundation.institution.InstitutionRelationContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.IntegrationContracts.ProvisionInstitution;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 完整迁移、正式Mapper/Interceptor和受限PG角色验证机构树、回执、授权及开通原子性。 */
@Testcontainers(disabledWithoutDocker=false)
class InstitutionRelationDatabaseTest {
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("institution_relations").withUsername("skillforge").withPassword("isolated-test-password");
    private static JdbcTemplate owner;
    private static DriverManagerDataSource restricted;
    private static SqlSessionTemplate sessions;
    private static TransactionTemplate writer;
    private static SqlSessionTemplate platformSessions;
    private static TransactionTemplate platformWriter;
    private InstitutionRelationService service;
    private InstitutionRelationService tenantService;
    private FoundationAccess foundation;
    private final AtomicReference<Set<Long>> targets=new AtomicReference<>();
    private long h1,h2,c1,c2;

    @BeforeAll static void database() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner=new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()));
        owner.execute("CREATE ROLE relation_reader LOGIN PASSWORD 'isolated-role-password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO relation_reader");
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO relation_reader");
        owner.execute("CREATE ROLE relation_platform LOGIN PASSWORD 'isolated-platform-password' NOSUPERUSER BYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO relation_platform");
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO relation_platform");
        assertTrue(owner.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname='relation_reader'",Boolean.class));
        assertTrue(owner.queryForObject("SELECT NOT rolsuper AND rolbypassrls FROM pg_roles WHERE rolname='relation_platform'",Boolean.class));
        owner.execute("""
                CREATE FUNCTION relation_test_fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF current_setting('test.fail_relation_audit',true)='true' AND NEW.action='tenant.sub_institutions.provision' THEN
                        RAISE EXCEPTION 'simulated_relation_audit_failure' USING ERRCODE='P0001';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        owner.execute("CREATE TRIGGER relation_test_fail_audit BEFORE INSERT ON nw_audit_logs FOR EACH ROW EXECUTE FUNCTION relation_test_fail_audit()");
        restricted=new DriverManagerDataSource(DATABASE.getJdbcUrl(),"relation_reader","isolated-role-password");
        sessions=sessions(restricted,"tenant-relations");
        writer=new TransactionTemplate(new DataSourceTransactionManager(restricted));
        var platform=new DriverManagerDataSource(DATABASE.getJdbcUrl(),"relation_platform","isolated-platform-password");
        platformSessions=sessions(platform,"platform-relations");
        platformWriter=new TransactionTemplate(new DataSourceTransactionManager(platform));
    }

    private static SqlSessionTemplate sessions(DriverManagerDataSource connections,String environment) throws Exception {
        var configuration=new MybatisConfiguration(new Environment(environment,new SpringManagedTransactionFactory(),connections));configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new TenantRlsInterceptor());configuration.addMapper(TenantMapper.class);configuration.addMapper(AuditLogMapper.class);
        for(String path:List.of("mapper/InstitutionRelationMapper.xml","mapper/InstitutionIntegrationMapper.xml")) {
            try(var xml=new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(xml,configuration,path,configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
    }

    @BeforeEach void fixture() {
        h1=tenant();h2=tenant();c1=tenant();c2=tenant();targets.set(Set.of(h1,h2,c1,c2));
        foundation=mock(FoundationAccess.class);
        var management=mock(PlatformManagementService.class);
        when(management.authorizedTenantScope(anyString())).thenAnswer(call->new PlatformManagementService.TenantScope("platform_member:test",1,targets.get()));
        when(foundation.currentSubject()).thenAnswer(call->new FoundationAccess.Subject(false,TenantContext.getTenantId(),"1"));
        service=service(platformSessions,management);
        tenantService=service(sessions,management);
    }
    private InstitutionRelationService service(SqlSessionTemplate host,PlatformManagementService management) {
        var institutions=new InstitutionIntegrationService(host.getMapper(InstitutionIntegrationMapper.class),host.getMapper(TenantMapper.class),mock(ServicePrincipalService.class),management);
        return new InstitutionRelationService(host.getMapper(InstitutionRelationMapper.class),management,foundation,institutions,mock(ResourceQuota.class),
                new AuditService(host.getMapper(AuditLogMapper.class)),new ObjectMapper().registerModule(new JavaTimeModule()));
    }
    @AfterEach void clear(){TenantContext.clear();}

    @Test void concurrentReverseAttachmentsCannotCreateACycle() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var left=pool.submit(()->{start.await();return attempt(()->service.attach(new Change(h1,h2,1),"left-"+h1));});
            var right=pool.submit(()->{start.await();return attempt(()->service.attach(new Change(h2,h1,1),"right-"+h1));});
            start.countDown();assertEquals(1,List.of(left.get(30,TimeUnit.SECONDS),right.get(30,TimeUnit.SECONDS)).stream().filter(Boolean::booleanValue).count());
        }
        assertFalse(Boolean.TRUE.equals(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM nw_tenants a JOIN nw_tenants b ON a.parent_tenant_id=b.id WHERE b.parent_tenant_id=a.id AND a.id IN(?,?))",Boolean.class,h1,h2)));
    }

    @Test void committedReceiptIsImmutableAndOldReplayNeverOverwritesALaterMove() {
        String key="attach-"+c1;var original=tx(()->service.attach(new Change(c1,h1,1),key));
        var moved=tx(()->service.move(new Change(c1,h2,2),"move-"+c1));
        assertEquals(original,tx(()->service.attach(new Change(c1,h1,1),key)));
        assertEquals(h2,owner.queryForObject("SELECT parent_tenant_id FROM nw_tenants WHERE id=?",Long.class,c1));
        assertEquals(3,moved.revision());
        assertThrows(RuntimeException.class,()->owner.update("UPDATE nw_institution_relation_requests SET fingerprint='changed' WHERE child_tenant_id=?",c1));
        assertEquals(2,owner.queryForObject("SELECT count(*) FROM nw_institution_relation_revisions WHERE child_tenant_id=?",Integer.class,c1));
    }

    @Test void platformDirectoryFiltersInvisibleParentsBeforePaginationAndCount() {
        tx(()->service.attach(new Change(c1,h1,1),"attach-"+c1));
        targets.set(Set.of(c1,c2));var page=tx(()->service.platformList(null,1,1));
        assertEquals(1,page.total());assertEquals(Long.toString(c2),page.items().getFirst().tenantId());
        targets.set(Set.of(c1,h1));assertEquals(2,tx(()->service.platformList(null,1,20)).total());
    }

    @Test void headquartersProvisionUsesParentContextAndOldGrantDoesNotReviveAfterMovingBack() {
        var request=provision("hq-"+h1,"child");
        var child=asHeadquarters(h1,()->tenantTx(()->tenantService.provisionSubInstitution(request,"create-"+h1)));
        long id=Long.parseLong(child.tenantId());targets.set(Set.of(h1,h2,id));
        assertEquals(child,asHeadquarters(h1,()->tenantTx(()->tenantService.provisionSubInstitution(request,"create-"+h1))));
        assertEquals(1,asHeadquarters(h1,()->tenantTx(()->tenantService.subInstitutions(1,20))).total());
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM nw_service_principals WHERE tenant_id=?",Integer.class,id));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM nw_tenant_members WHERE tenant_id=?",Integer.class,id));
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM nw_institution_integrations WHERE tenant_id=?",Integer.class,id));
        tx(()->service.move(new Change(id,h2,1),"move-away-"+id));tx(()->service.move(new Change(id,h1,2),"move-back-"+id));
        assertEquals(0,asHeadquarters(h1,()->tenantTx(()->tenantService.subInstitutions(1,20))).total());
        assertEquals(child,asHeadquarters(h1,()->tenantTx(()->tenantService.provisionSubInstitution(request,"create-"+h1))));
        assertEquals(3,owner.queryForObject("SELECT relation_revision FROM nw_tenants WHERE id=?",Integer.class,id));
    }

    @Test void failedSuccessAuditRollsBackTenantMappingRelationGrantReceiptAndTreeRevision() {
        long tree=owner.queryForObject("SELECT revision FROM nw_institution_tree_revision WHERE singleton",Long.class);
        String namespace="rollback-"+h1;
        var failure=assertThrows(RuntimeException.class,()->asHeadquarters(h1,()->tenantTx(()->{
            new JdbcTemplate(restricted).execute("SELECT set_config('test.fail_relation_audit','true',true)");
            return tenantService.provisionSubInstitution(provision(namespace,"rollback"),"rollback-"+h1);
        })));
        assertTrue(exceptionContains(failure,"simulated_relation_audit_failure"));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM nw_external_institution_mappings WHERE namespace=?",Integer.class,namespace));
        assertEquals(tree,owner.queryForObject("SELECT revision FROM nw_institution_tree_revision WHERE singleton",Long.class));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM nw_institution_relation_requests WHERE actor_ref=? AND command_ref=?",Integer.class,"tenant:"+h1+":member:1","rollback-"+h1));
    }

    @Test void restrictedDirectInsertCannotInitializeAnExistingChildOrAnUnrelatedInstitution() {
        tx(()->service.attach(new Change(c1,h1,1),"attach-"+c1));
        for(long target:List.of(c1,c2)) {
            assertThrows(RuntimeException.class,()->asHeadquarters(h1,()->writer.execute(status->{
                new JdbcTemplate(restricted).execute("SELECT set_config('app.tenant_id','"+h1+"',true)");
                return new JdbcTemplate(restricted).update("INSERT INTO nw_external_institution_mappings(id,namespace,external_institution_ref,tenant_id,integration_id,provision_fingerprint,created_by) VALUES(?,?,?,?,?,?,?)",
                        SnowflakeIds.nextId(),"forged-"+h1,Long.toString(target),target,"forged-"+target,"a".repeat(64),"tenant:"+h1+":member:1");
            })));
            assertEquals(0,owner.queryForObject("SELECT count(*) FROM nw_institution_integrations WHERE tenant_id=? AND integration_id=?",Integer.class,target,"forged-"+target));
        }
    }

    @Test void parentWithChildrenCannotBeDeletedAndDirectSqlCannotCreateCycle() {
        tx(()->service.attach(new Change(c1,h1,1),"attach-"+c1));
        assertThrows(RuntimeException.class,()->owner.update("DELETE FROM nw_tenants WHERE id=?",h1));
        assertThrows(RuntimeException.class,()->owner.update("UPDATE nw_tenants SET parent_tenant_id=? WHERE id=?",c1,h1));
        assertNull(owner.queryForObject("SELECT parent_tenant_id FROM nw_tenants WHERE id=?",Long.class,h1));
    }

    private long tenant() {
        long id=SnowflakeIds.nextId();Long plan=owner.queryForObject("SELECT id FROM nw_billing_plans WHERE is_active ORDER BY id LIMIT 1",Long.class);
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id,status,deployment_mode,settings) VALUES(?,?,?,?,'active','cloud','{}')",id,"institution-"+id,"relation-"+id,plan);return id;
    }
    private ProvisionInstitution provision(String namespace,String ref) {return new ProvisionInstitution(namespace,ref,"child","free",null,null,null,null,null);}
    private <T>T tx(Supplier<T> action){return platformWriter.execute(status->action.get());}
    private <T>T tenantTx(Supplier<T> action){return writer.execute(status->action.get());}
    private boolean exceptionContains(Throwable failure,String text){for(Throwable current=failure;current!=null;current=current.getCause())if(current.getMessage()!=null&&current.getMessage().contains(text))return true;return false;}
    private boolean attempt(Supplier<Relation> action){try{tx(action);return true;}catch(ApiException conflict){assertEquals(409,conflict.getStatus().value());return false;}finally{TenantContext.clear();}}
    private <T>T asHeadquarters(long id,Supplier<T> action){try{TenantContext.setTenantId(Long.toString(id));return action.get();}finally{TenantContext.clear();}}
}
