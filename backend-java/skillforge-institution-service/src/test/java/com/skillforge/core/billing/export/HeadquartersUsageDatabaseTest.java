package com.skillforge.core.billing.export;

import com.skillforge.core.foundation.institution.usage.HeadquartersUsageAccess;
import com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions;
import com.skillforge.core.foundation.institution.usage.HeadquartersUsageMapper;

import static com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.access.FoundationAuthorizedSqlRule;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementContracts.Create;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementContracts.Grant;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementContracts.Revoke;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementMapper;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementService;
import com.skillforge.core.foundation.institution.InstitutionRelationMapper;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.application.AuditService;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 完整Flyway、正式Mapper与受限PG角色验证HQ用量授权；计量fixture不代表真实Runtime调用。 */
@Testcontainers(disabledWithoutDocker=false)
class HeadquartersUsageDatabaseTest {
    private static final String READER="hq_usage_reader",PLATFORM="hq_usage_platform",PASSWORD="isolated-hq-usage-password";
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("headquarters_usage").withUsername("skillforge").withPassword("isolated-owner-password");
    private static JdbcTemplate owner;
    private static DriverManagerDataSource ownerSource,platformSource;
    private static SqlSessionTemplate fixtureSessions,platformSessions;
    private static TransactionTemplate fixtureWriter,platformWriter;
    private SingleConnectionDataSource restricted;
    private JdbcTemplate readerJdbc;
    private SqlSessionTemplate sessions;
    private TransactionTemplate reads;
    private HeadquartersUsageAccess access;
    private InstitutionDeploymentManagementService grants;
    private final AtomicReference<Set<String>> permissions=new AtomicReference<>(Set.of(SUMMARY,DETAILS,EVENTS));
    private long hq,child,secondChild,foreign,grandchild,childRelation;

    @BeforeAll static void migrate()throws Exception {
        ownerSource=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        Flyway.configure().dataSource(ownerSource).locations("classpath:db/migration").load().migrate();
        owner=new JdbcTemplate(ownerSource);
        owner.execute("CREATE ROLE "+READER+" LOGIN PASSWORD '"+PASSWORD+"' NOSUPERUSER NOBYPASSRLS");
        owner.execute("CREATE ROLE "+PLATFORM+" LOGIN PASSWORD '"+PASSWORD+"' NOSUPERUSER BYPASSRLS");
        for(String role:List.of(READER,PLATFORM)) {
            owner.execute("GRANT USAGE ON SCHEMA public TO "+role);
            owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO "+role);
            owner.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO "+role);
        }
        owner.execute("GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO "+READER);
        assertTrue(owner.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=?",Boolean.class,READER));
        for(String table:List.of("nw_usage_events","nw_usage_publication_events","nw_usage_publication_pending","nw_usage_event_grant_hops"))
            assertTrue(owner.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid=CAST(? AS regclass)",Boolean.class,"public."+table),table);
        fixtureSessions=sessions(ownerSource,"hq-usage-fixture");fixtureWriter=transaction(ownerSource,false);
        platformSource=new DriverManagerDataSource(DATABASE.getJdbcUrl(),PLATFORM,PASSWORD);
        platformSessions=sessions(platformSource,"hq-usage-platform");platformWriter=transaction(platformSource,false);
    }

    /** 每例一条可复用物理受限连接；窗口退出及下一事务都必须留下原身份。 */
    @BeforeEach void fixture()throws Exception {
        hq=tenant(null);child=tenant(hq);secondChild=tenant(hq);foreign=tenant(null);grandchild=tenant(child);
        childRelation=relation(child,hq);relation(secondChild,hq);relation(grandchild,child);
        for(long tenant:List.of(hq,child,secondChild,foreign,grandchild))writeUsage(tenant);
        fixtureWriter.execute(status->fixtureSessions.getMapper(PlatformUsageMapper.class).publishCommitted(1000));
        writeUsage(secondChild);
        restricted=new SingleConnectionDataSource(DATABASE.getJdbcUrl(),READER,PASSWORD,true);
        readerJdbc=new JdbcTemplate(restricted);sessions=sessions(restricted,"hq-usage-restricted");reads=transaction(restricted,true);
        var foundation=mock(FoundationAccess.class);
        when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(false,Long.toString(hq),"71"));
        when(foundation.snapshot(any(FoundationAccess.Subject.class))).thenAnswer(call->new FoundationAccess.Snapshot(List.of("usage-fixture"),permissions.get().stream().sorted().toList(),1));
        access=new HeadquartersUsageAccess(foundation,mock(InstitutionIntegrationService.class),sessions.getMapper(HeadquartersUsageMapper.class));
        var management=mock(PlatformManagementService.class);
        when(management.authorizedTenantScope(anyString())).thenReturn(new PlatformManagementService.TenantScope("platform_member:hq-usage-fixture",1,Set.of(hq,child,secondChild,foreign,grandchild)));
        grants=new InstitutionDeploymentManagementService(platformSessions.getMapper(InstitutionDeploymentManagementMapper.class),
                platformSessions.getMapper(InstitutionRelationMapper.class),management,foundation,mock(InstitutionIntegrationService.class),
                new AuditService(platformSessions.getMapper(AuditLogMapper.class)),new ObjectMapper().registerModule(new JavaTimeModule()));
        TenantContext.setTenantId(Long.toString(hq));
    }

    @AfterEach void release() {
        try {assertNull(HeadquartersUsageAccess.currentAuthorization());}
        finally {TenantContext.clear();ServicePrincipalContext.clear();ManagementContext.clear();if(restricted!=null)restricted.destroy();}
    }

    @Test void directoryAndParentRelationshipDoNotGrantChildUsage() {
        owner.update("INSERT INTO nw_institution_management_grants(id,actor_domain,actor_ref,target_tenant_id,action,status,revision,relation_ref) VALUES(?,'tenant',?,?,'tenant.sub_institutions.read','active',1,?)",
                SnowflakeIds.nextId(),"tenant:"+hq,child,childRelation);
        reads.executeWithoutResult(status->{
            var authorization=access.authorize(SUMMARY);assertEquals(Set.of(),authorization.tenantIds());
            assertEquals(0,access.read(authorization,()->summary(authorization)).getRequestCount());
            assertTrue(usage().list(Set.of(child,secondChild,foreign,grandchild),null,PlatformUsageQuery.empty(),0,Long.MAX_VALUE,100).isEmpty());
            assertEquals(READER,state().get("current_user"));assertEquals(Long.toString(hq),state().get("tenant"));
        });
    }

    @Test void summaryGrantCannotReadDetailsEventsOrExpandTargets() {
        grant(child,SUMMARY,1);permissions.set(Set.of(SUMMARY));
        reads.executeWithoutResult(status->{
            var authorization=access.authorize(SUMMARY);assertEquals(Set.of(child),authorization.tenantIds());
            assertEquals(1,access.read(authorization,()->summary(authorization)).getRequestCount());
            assertEquals(403,assertThrows(ApiException.class,()->access.authorize(DETAILS)).getStatus().value());
            assertEquals(403,assertThrows(ApiException.class,()->access.authorize(EVENTS)).getStatus().value());
            rejected("headquarters_usage_statement_not_allowed",()->access.read(authorization,()->usage().list(authorization.tenantIds(),null,PlatformUsageQuery.empty(),0,Long.MAX_VALUE,100)));
            rejected("headquarters_usage_statement_not_allowed",()->access.read(authorization,()->usage().hasPending(authorization.tenantIds(),null,PlatformUsageQuery.empty())));
            rejected("headquarters_usage_statement_not_allowed",()->access.read(authorization,()->usage().grantHops(authorization.tenantIds(),List.of(1L))));
            rejected("headquarters_usage_targets_mismatch",()->access.read(authorization,()->usage().summarize(Set.of(child,secondChild),null,PlatformUsageQuery.empty())));
            rejected("headquarters_usage_targets_mismatch",()->access.read(authorization,()->usage().summarize(Set.of(secondChild),null,PlatformUsageQuery.empty())));
            assertTrue(usage().list(Set.of(child),null,PlatformUsageQuery.empty(),0,Long.MAX_VALUE,100).isEmpty());
        });
    }

    @Test void detailsAndEventsRequireTheirOwnExactCurrentGrant() {
        grant(child,SUMMARY,1);grant(child,DETAILS,1);grant(secondChild,EVENTS,1);
        reads.executeWithoutResult(status->{
            var details=access.authorize(DETAILS);var events=access.authorize(EVENTS);
            assertEquals(Set.of(child),details.tenantIds());assertEquals(Set.of(secondChild),events.tenantIds());
            var detailed=access.read(details,()->usage().list(details.tenantIds(),null,PlatformUsageQuery.empty(),0,Long.MAX_VALUE,100));
            assertEquals(List.of(child),detailed.stream().map(PlatformUsageRow::getTenantId).toList());
            assertTrue(access.read(details,()->usage().grantHops(details.tenantIds(),detailed.stream().map(PlatformUsageRow::getUsageId).toList())).isEmpty());
            var eventPage=access.read(events,()->usage().list(events.tenantIds(),null,PlatformUsageQuery.empty(),0,Long.MAX_VALUE,100));
            assertEquals(List.of(secondChild),eventPage.stream().map(PlatformUsageRow::getTenantId).toList());
            assertTrue(access.read(events,()->usage().hasPending(events.tenantIds(),null,PlatformUsageQuery.empty())));
            rejected("headquarters_usage_statement_not_allowed",()->access.read(details,()->usage().summarize(details.tenantIds(),null,PlatformUsageQuery.empty())));
            rejected("headquarters_usage_targets_mismatch",()->access.read(events,()->usage().list(Set.of(child),null,PlatformUsageQuery.empty(),0,Long.MAX_VALUE,100)));
        });
    }

    @Test void groupAndSystemReferencesSurviveRestrictedReadsWithoutOpeningSystemRunAccess() {
        long group=SnowflakeIds.nextId(),otherGroup=SnowflakeIds.nextId();
        var expected=writeGroupUsage(child,group);
        writeGroupUsage(secondChild,otherGroup);
        writeGroupUsage(foreign,group);
        fixtureWriter.execute(status->fixtureSessions.getMapper(PlatformUsageMapper.class).publishCommitted(1000));
        grant(child,EVENTS,1);
        var query=new PlatformUsageQuery(null,null,null,null,null,null,null,null,null,null,null,null,null,100,group);
        try {
            TenantContext.setTenantId(Long.toString(child));
            reads.executeWithoutResult(status->{
                var rows=usage().list(Set.of(child,secondChild,foreign),null,query,0,Long.MAX_VALUE,100);
                assertEquals(1,rows.size());assertGroupSource(expected,rows.getFirst());
                var ownOtherGroup=new PlatformUsageQuery(null,null,null,null,null,null,null,null,null,null,null,null,null,100,otherGroup);
                assertTrue(usage().list(Set.of(child,secondChild,foreign),null,ownOtherGroup,0,Long.MAX_VALUE,100).isEmpty());
            });
        } finally {TenantContext.setTenantId(Long.toString(hq));}
        reads.executeWithoutResult(status->{
            var authorized=access.authorize(EVENTS);assertEquals(Set.of(child),authorized.tenantIds());
            access.read(authorized,()->{
                var rows=usage().list(authorized.tenantIds(),null,query,0,Long.MAX_VALUE,100);
                assertEquals(1,rows.size());assertGroupSource(expected,rows.getFirst());
                // 用量窗口只打开原账本；后台输入/配置所在的系统执行表仍按机构RLS隔离。
                assertEquals(0L,readerJdbc.queryForObject("SELECT count(*) FROM nw_platform_system_agent_runs WHERE tenant_id=?",Long.class,child));
                return null;
            });
            rejected("headquarters_usage_targets_mismatch",()->access.read(authorized,()->
                    usage().list(Set.of(child,foreign),null,query,0,Long.MAX_VALUE,100)));
            assertTrue(usage().list(Set.of(child),null,query,0,Long.MAX_VALUE,100).isEmpty());
        });
    }

    private static void assertGroupSource(GroupUsageFixture expected,PlatformUsageRow row) {
        assertEquals(expected.tenant(),row.getTenantId());assertEquals(expected.group(),row.getGroupBindingId());
        assertEquals("SYSTEM",row.getRequesterKind());assertEquals("system:system-agent:900000000000000003",row.getRequesterRef());
        assertEquals(expected.session(),row.getOriginSessionId());assertEquals(expected.event(),row.getOriginEventId());
        assertEquals(expected.responsibleAgent(),row.getResponsibilityAgentId());assertEquals(expected.systemRun(),row.getSystemAgentRunId());
        assertEquals("system-agent-config:900000000000000003:draft",row.getSystemAgentConfigurationRef());
        assertEquals("system-agent-invocation:"+expected.systemRun(),row.getSystemAgentInvocationRef());
    }

    /** 仅建立RLS读取事实，不声称发生真实模型调用；系统Run外键与发布流均走正式schema。 */
    private static GroupUsageFixture writeGroupUsage(long tenant,long group) {
        long run=SnowflakeIds.nextId(),responsibleAgent=SnowflakeIds.nextId();
        owner.update("""
                INSERT INTO nw_platform_system_agent_runs(id,tenant_id,agent_id,idempotency_key,input_fingerprint,version,debug,
                    config,model_revision,input)
                SELECT ?,?,id,?,repeat('a',64),NULL,true,draft,1,'headquarters group source read fixture'
                FROM nw_platform_system_agents WHERE id=900000000000000003
                """,run,tenant,"hq-group-source-"+run);
        var event=event(tenant);String session="group-source-"+group,source="source-event-"+run;
        event.setGroupBindingId(group);event.setRequesterKind("SYSTEM");event.setRequesterRef("system:system-agent:900000000000000003");
        event.setActorRef(event.getRequesterRef());event.setResponsibilityRef("agent:"+responsibleAgent);
        event.setAgentId(900000000000000003L);event.setSourceOwnerKind("platform");
        event.setAgentDefinitionVersion("system-agent-config:900000000000000003:draft");
        event.setOriginSessionId(session);event.setOriginEventId(source);event.setResponsibilityAgentId(responsibleAgent);
        event.setSystemAgentRunId(run);event.setSystemAgentConfigurationRef("system-agent-config:900000000000000003:draft");
        event.setSystemAgentInvocationRef("system-agent-invocation:"+run);
        fixtureWriter.executeWithoutResult(status->{assertEquals(1,fixtureSessions.getMapper(CostEventMapper.class).insert(event));
            assertEquals(1,fixtureSessions.getMapper(PlatformUsageMapper.class).insertPending(event));});
        return new GroupUsageFixture(tenant,group,session,source,responsibleAgent,run);
    }
    private record GroupUsageFixture(long tenant,long group,String session,String event,long responsibleAgent,long systemRun) { }

    @Test void revokedGrantDoesNotLeakThroughPreviouslyCapturedAuthorization() {
        Grant grant=grant(child,SUMMARY,1);
        var before=reads.execute(status->access.authorize(SUMMARY));assertNotNull(before);
        assertEquals(1,reads.execute(status->access.read(before,()->summary(before))).getRequestCount());
        platformWriter.executeWithoutResult(status->grants.revoke(new Revoke(Long.parseLong(grant.grantId()),1L),"revoke-"+grant.grantId()));
        reads.executeWithoutResult(status->{
            var current=access.authorize(SUMMARY);assertEquals(Set.of(),current.tenantIds());
            assertNotEquals(before.authorizationVersion(),current.authorizationVersion());
            assertReadDeniedBeforeOperation(before);
            assertEquals(0,access.read(current,()->summary(current)).getRequestCount());
        });
    }

    @Test void movingChildAwayAndBackCannotReviveHistoricalRelationGrant() {
        Grant old=grant(child,SUMMARY,1);var frozen=reads.execute(status->access.authorize(SUMMARY));assertNotNull(frozen);
        owner.update("UPDATE nw_tenants SET parent_tenant_id=?,relation_revision=2 WHERE id=?",foreign,child);relation(child,foreign);
        assertEquals(Set.of(),reads.execute(status->access.authorize(SUMMARY)).tenantIds());
        reads.executeWithoutResult(status->assertReadDeniedBeforeOperation(frozen));
        owner.update("UPDATE nw_tenants SET parent_tenant_id=?,relation_revision=3 WHERE id=?",hq,child);relation(child,hq);
        assertEquals(Set.of(),reads.execute(status->access.authorize(SUMMARY)).tenantIds());
        Grant replacement=grant(child,SUMMARY,3);assertNotEquals(old.relationRef(),replacement.relationRef());
        reads.executeWithoutResult(status->{
            assertReadDeniedBeforeOperation(frozen);
            var current=access.authorize(SUMMARY);assertEquals(Set.of(child),current.tenantIds());
            assertEquals(1,access.read(current,()->summary(current)).getRequestCount());
        });
    }

    @Test void callerConstructedSnapshotCannotExpandTheCurrentReadWindow() {
        grant(child,SUMMARY,1);
        reads.executeWithoutResult(status->{
            var current=access.authorize(SUMMARY);
            var original=state();
            var forged=new HeadquartersUsageAccess.Authorization(current.actorTenantId(),current.actorRef(),
                    current.authorizationVersion(),current.action(),Set.of(child,secondChild,foreign));
            assertReadDeniedBeforeOperation(forged);
            assertEquals(original,state());
            assertEquals(1,access.read(current,()->{
                assertSame(current,HeadquartersUsageAccess.currentAuthorization());
                return summary(current);
            }).getRequestCount());
            assertEquals(original,state());assertNull(HeadquartersUsageAccess.currentAuthorization());
        });
    }

    @Test void successfulAndFailedReadWindowsRestoreGucsAndConnectionIdentity() {
        grant(child,SUMMARY,1);
        long backend=((Number)state().get("backend")).longValue();
        reads.executeWithoutResult(status->{
            var authorization=access.authorize(SUMMARY);
            readerJdbc.execute("SELECT set_config('app.platform_admin','false',true),set_config('app.headquarters_usage_action','original-action',true),set_config('app.headquarters_usage_targets','original-targets',true)");
            var original=state();
            access.read(authorization,()->{
                assertSame(authorization,HeadquartersUsageAccess.currentAuthorization());assertEquals(1,summary(authorization).getRequestCount());
                var during=state();assertEquals(READER,during.get("current_user"));assertEquals(READER,during.get("session_user"));
                assertEquals(Long.toString(hq),during.get("tenant"));assertEquals("false",during.get("platform"));
                assertEquals(SUMMARY,during.get("action"));assertEquals(Long.toString(child),during.get("targets"));return null;
            });
            assertEquals(original,state());assertNull(HeadquartersUsageAccess.currentAuthorization());
            var failure=assertThrows(IllegalStateException.class,()->access.read(authorization,()->{summary(authorization);throw new IllegalStateException("fixture_read_failed");}));
            assertEquals("fixture_read_failed",failure.getMessage());assertEquals(original,state());assertNull(HeadquartersUsageAccess.currentAuthorization());
            assertTrue(usage().list(Set.of(child),null,PlatformUsageQuery.empty(),0,Long.MAX_VALUE,100).isEmpty());
        });
        var after=state();assertEquals(backend,((Number)after.get("backend")).longValue());
        for(String key:List.of("tenant","platform","action","targets"))assertEquals("",after.get(key),key);
        assertEquals(READER,after.get("current_user"));assertEquals(READER,after.get("session_user"));
    }

    @Test void windowRejectsWritesUnrelatedMappersNestedScopesAndConnectionSwitching() {
        grant(child,SUMMARY,1);long before=owner.queryForObject("SELECT count(*) FROM nw_usage_events",Long.class);
        reads.executeWithoutResult(status->{
            var authorization=access.authorize(SUMMARY);
            rejected("headquarters_usage_statement_not_allowed",()->access.read(authorization,()->sessions.getMapper(CostEventMapper.class).insert(event(hq))));
            rejected("headquarters_usage_statement_not_allowed",()->access.read(authorization,()->sessions.getMapper(TenantMapper.class).selectById(child)));
            rejected("headquarters_usage_nested_scope_forbidden",()->access.read(authorization,()->access.read(authorization,()->summary(authorization))));
            rejected("authorized_scope_requires_original_managed_transaction",()->access.read(authorization,()->fixtureSessions.getMapper(PlatformUsageMapper.class).summarize(Set.of(child),null,PlatformUsageQuery.empty())));
            access.read(authorization,()->{
                assertEquals(1,summary(authorization).getRequestCount());
                readerJdbc.execute((ConnectionCallback<Void>) connection->{
                    var savepoint=connection.setSavepoint();
                    try(var statement=connection.prepareStatement("UPDATE nw_tenants SET name=name WHERE id=?")) {
                        statement.setLong(1,hq);var failure=assertThrows(SQLException.class,statement::executeUpdate);assertEquals("25006",failure.getSQLState());
                    } finally {connection.rollback(savepoint);connection.releaseSavepoint(savepoint);}
                    return null;
                });return null;
            });
            assertNull(HeadquartersUsageAccess.currentAuthorization());assertEquals(Long.toString(hq),TenantContext.getTenantId());
        });
        assertEquals(before,owner.queryForObject("SELECT count(*) FROM nw_usage_events",Long.class));
    }

    private PlatformUsageMapper usage(){return sessions.getMapper(PlatformUsageMapper.class);}
    private void assertReadDeniedBeforeOperation(HeadquartersUsageAccess.Authorization authorization) {
        var invoked=new AtomicBoolean();
        var rejected=assertThrows(ApiException.class,()->access.read(authorization,()->{
            invoked.set(true);return summary(authorization);
        }));
        assertEquals(403,rejected.getStatus().value());assertFalse(invoked.get());
        assertNull(HeadquartersUsageAccess.currentAuthorization());
        assertEquals(Long.toString(hq),TenantContext.getTenantId());
    }
    private PlatformUsageSummaryRow summary(HeadquartersUsageAccess.Authorization authorization) {
        var rows=usage().summarize(authorization.tenantIds(),null,PlatformUsageQuery.empty());assertEquals(1,rows.size());return rows.getFirst();
    }
    private Grant grant(long target,String action,long revision) {
        return platformWriter.execute(status->grants.create(new Create(hq,target,action,revision),"grant-"+target+":"+action+":"+revision));
    }
    private Map<String,Object> state() {
        return readerJdbc.queryForMap("SELECT pg_backend_pid() AS backend,current_user,session_user,COALESCE(current_setting('app.tenant_id',true),'') AS tenant,COALESCE(current_setting('app.platform_admin',true),'') AS platform,COALESCE(current_setting('app.headquarters_usage_action',true),'') AS action,COALESCE(current_setting('app.headquarters_usage_targets',true),'') AS targets");
    }
    private static void rejected(String code,Supplier<?> work) {
        var failure=assertThrows(RuntimeException.class,work::get);assertTrue(causeContains(failure,code),()->"expected "+code+" but got "+failure.getClass().getSimpleName());
    }
    private static boolean causeContains(Throwable failure,String code) {
        for(Throwable current=failure;current!=null;current=current.getCause())if(current.getMessage()!=null&&current.getMessage().contains(code))return true;return false;
    }
    private static TransactionTemplate transaction(DataSource source,boolean readOnly) {
        var manager=new DataSourceTransactionManager(source);manager.setEnforceReadOnly(readOnly);
        var template=new TransactionTemplate(manager);template.setReadOnly(readOnly);return template;
    }
    private static SqlSessionTemplate sessions(DataSource source,String name)throws Exception {
        var configuration=new MybatisConfiguration(new Environment(name,new SpringManagedTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true);configuration.addInterceptor(new TenantRlsInterceptor(
                List.of(new FoundationAuthorizedSqlRule(),new BillingAuthorizedSqlRule())));
        configuration.addMapper(TenantMapper.class);configuration.addMapper(AuditLogMapper.class);
        for(String mapper:List.of("CostEventMapper","PlatformUsageMapper","HeadquartersUsageMapper","InstitutionDeploymentManagementMapper","InstitutionRelationMapper")) {
            String path="mapper/"+mapper+".xml";
            try(var xml=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(xml,configuration,path,configuration.getSqlFragments()).parse();}
        }
        return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
    }
    private static long tenant(Long parent) {
        long id=SnowflakeIds.nextId();owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id,status,parent_tenant_id) SELECT ?,?,?,id,'active',? FROM nw_billing_plans WHERE plan_code='free'",id,"hq-usage-"+id,"hq-usage-"+id,parent);return id;
    }
    private static long relation(long child,long parent) {
        long id=SnowflakeIds.nextId();owner.update("INSERT INTO nw_institution_relation_revisions(id,child_tenant_id,parent_tenant_id,revision,tree_revision,actor_ref,command_ref) SELECT ?,id,?,relation_revision,(SELECT revision FROM nw_institution_tree_revision WHERE singleton),'platform_member:hq-usage-fixture',? FROM nw_tenants WHERE id=?",id,parent,"hq-usage-relation-"+id,child);return id;
    }
    /** 合法系统计量仅测试授权；源事实和pending复用正式Mapper在同一事务提交。 */
    private static void writeUsage(long tenant) {
        fixtureWriter.executeWithoutResult(status->{var event=event(tenant);assertEquals(1,fixtureSessions.getMapper(CostEventMapper.class).insert(event));assertEquals(1,fixtureSessions.getMapper(PlatformUsageMapper.class).insertPending(event));});
    }
    private static CostEventDO event(long tenant) {
        var event=new CostEventDO();event.setId(SnowflakeIds.nextId());event.setTenantId(tenant);event.setOriginKind("system");
        event.setActorRef("call_point:headquarters-rls-fixture");event.setResponsibilityRef("tenant:"+tenant);
        event.setEventId("hq-usage-"+event.getId());event.setEventType("request");event.setUsageEventKind("measured");
        event.setUsageSource("UNKNOWN");event.setAmountStatus("not_applicable");event.setQuantity(1);
        event.setOccurredAt(Instant.now());event.setIngestedAt(Instant.now());event.setCreatedAt(Instant.now());return event;
    }
}
