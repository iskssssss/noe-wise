package com.skillforge.institution.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.GrantChain;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.GrantHop;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.Grant;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts.Revoke;
import com.skillforge.core.agent.deployment.AgentTemplateGrantService;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentDistributionPolicy;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper.GrantRow;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 完整Flyway＋正式Mapper＋NOSUPERUSER/NOBYPASSRLS验证固定单链、当前关系及不可变修订。 */
@Testcontainers(disabledWithoutDocker=false)
class AgentGrantChainDatabaseTest {
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("agent_grant_chains").withUsername("skillforge").withPassword("isolated-owner-password");
    private static JdbcTemplate owner;
    private static DriverManagerDataSource restricted;
    private static TransactionTemplate writer;
    private static AgentDeploymentMapper mapper;
    private static AuditLogMapper auditMapper;
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    private long hq,child,other,source,relation,rootGrant,childGrant;
    private GrantChain original;
    @BeforeAll static void database() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()).locations("classpath:db/migration").load().migrate();
        owner=new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()));
        owner.execute("CREATE ROLE grant_chain_reader LOGIN PASSWORD 'isolated-reader-password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO grant_chain_reader");
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO grant_chain_reader");
        assertTrue(owner.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname='grant_chain_reader'",Boolean.class));
        restricted=new DriverManagerDataSource(DATABASE.getJdbcUrl(),"grant_chain_reader","isolated-reader-password");
        writer=new TransactionTemplate(new DataSourceTransactionManager(restricted));
        var config=new MybatisConfiguration(new Environment("restricted-grants",new SpringManagedTransactionFactory(),restricted));
        config.setMapUnderscoreToCamelCase(true);config.addInterceptor(new TenantRlsInterceptor());config.addMapper(AuditLogMapper.class);
        String path="mapper/AgentDeploymentMapper.xml";
        try(var xml=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(xml,config,path,config.getSqlFragments()).parse();}
        var sessions=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));mapper=sessions.getMapper(AgentDeploymentMapper.class);auditMapper=sessions.getMapper(AuditLogMapper.class);
        owner.execute("CREATE FUNCTION grant_test_fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF current_setting('test.fail_grant_audit',true)='true' AND NEW.action='permission.agent_grant.create' THEN RAISE EXCEPTION 'simulated_grant_audit_failure'; END IF; RETURN NEW; END $$");
        owner.execute("CREATE TRIGGER grant_test_fail_audit BEFORE INSERT ON nw_audit_logs FOR EACH ROW EXECUTE FUNCTION grant_test_fail_audit()");
    }
    @BeforeEach void fixture() throws Exception {
        hq=tenant(null);child=tenant(hq);other=tenant(null);source=SnowflakeIds.nextId();relation=relation(child,hq,1);
        owner.update("INSERT INTO nw_agent_templates(id,template_key,name,version,definition,status,distribution_policy,owner_kind) VALUES(?,?,?,'1.0.0','{}','published','AUTHORIZED_DEPLOY','platform')",source,"grant-source-"+source,"source");
        rootGrant=SnowflakeIds.nextId();childGrant=SnowflakeIds.nextId();
        tx(()->{var root=row(rootGrant,hq,"deploy,execute",true,2,null,null,null);assertEquals(1,mapper.insertGrant(root));mapper.appendGrantRevision(rootGrant,"platform:test","create","root-"+rootGrant);
            var leaf=row(childGrant,child,"deploy",false,0,rootGrant,hq,relation);assertEquals(1,mapper.insertGrant(leaf));mapper.appendGrantRevision(childGrant,"tenant:"+hq,"create","child-"+childGrant);return null;});
        original=new GrantChain(source,"platform",null,List.of(new GrantHop(rootGrant,1,"platform",null,hq,null,null,null,null),new GrantHop(childGrant,1,"tenant",hq,child,rootGrant,1L,relation,1L)));
    }
    @AfterEach void clear(){TenantContext.clear();}
    @Test void eachPurposeIsCheckedAcrossTheSingleFrozenChain() throws Exception {
        assertTrue(available(original,"deploy"));assertFalse(available(original,"execute"));
        assertFalse(tx(()->mapper.chainAvailable(encode(original),other,source,"deploy")));
        assertFalse(tx(()->mapper.chainAvailable(encode(original),child,source+1,"deploy")));
        assertFalse(tx(()->mapper.chainAvailable(encode(original),child,source,"source.edit")));
    }
    @Test void narrowingParentKeepsCompatibleDescendantWithOriginalRevision() throws Exception {
        tx(()->{assertEquals(1,mapper.narrowGrant(rootGrant,1,"deploy",null,true,1));mapper.appendGrantRevision(rootGrant,"platform:test","narrow","narrow-"+rootGrant);return null;});
        assertTrue(available(original,"deploy"));assertEquals(1,original.hops().getFirst().revision());assertEquals(2,tx(()->mapper.findGrant(rootGrant)).revision());
        assertEquals("deploy,execute",tx(()->mapper.grantRevision(rootGrant,1)).actions());
    }
    @Test void parentScopeNoLongerCoveringChildInvalidatesOriginalChain() throws Exception {
        tx(()->{mapper.narrowGrant(rootGrant,1,"execute",null,true,1);mapper.appendGrantRevision(rootGrant,"platform:test","narrow","narrow-"+rootGrant);return null;});
        assertFalse(available(original,"deploy"));
    }
    @Test void expiryInvalidatesImmediately() throws Exception {
        tx(()->{mapper.narrowGrant(rootGrant,1,"deploy,execute",Instant.EPOCH,true,2);mapper.appendGrantRevision(rootGrant,"platform:test","narrow","expire-"+rootGrant);return null;});
        assertFalse(available(original,"deploy"));
    }
    @Test void ancestorDisableAndSourceDeprecationAreCurrentIndependentGates() {
        assertTrue(available(original,"deploy"));owner.update("UPDATE nw_tenants SET status='suspended' WHERE id=?",hq);assertFalse(available(original,"deploy"));
        owner.update("UPDATE nw_tenants SET status='active' WHERE id=?",hq);assertTrue(available(original,"deploy"));
        owner.update("UPDATE nw_agent_templates SET status='deprecated' WHERE id=?",source);assertFalse(available(original,"deploy"));
    }
    @Test void movingAwayAndBackNeverRevivesOldRelationOrOldGrantKey() throws Exception {
        assertTrue(available(original,"deploy"));move(child,other,2);assertFalse(available(original,"deploy"));move(child,hq,3);assertFalse(available(original,"deploy"));
        assertEquals(relation,tx(()->mapper.findGrant(childGrant)).relationRef());assertEquals(1,tx(()->mapper.findGrant(childGrant)).relationRevision());
    }
    @Test void revokedOriginalCannotBeReactivatedAndAnotherGrantDoesNotRepairSnapshot() throws Exception {
        tx(()->{assertEquals(1,mapper.revokeGrant(rootGrant,1,"platform:test","revoke","f".repeat(64)));mapper.appendGrantRevision(rootGrant,"platform:test","revoke","revoke");return null;});
        assertFalse(available(original,"deploy"));
        long replacement=SnowflakeIds.nextId();tx(()->{mapper.insertGrant(row(replacement,hq,"deploy,execute",true,2,null,null,null));mapper.appendGrantRevision(replacement,"platform:test","create","replacement");return null;});
        assertFalse(available(original,"deploy"));
        assertThrows(RuntimeException.class,()->new JdbcTemplate(restricted).update("UPDATE nw_agent_definition_grants SET status='active',revision=revision+1,revoke_key=NULL,revoke_fingerprint=NULL,revoked_by=NULL,revoked_at=NULL WHERE grant_id=?",rootGrant));
    }
    @Test void databaseRejectsExpandedScopeWrongParentAndHistoryMutation() {
        assertThrows(RuntimeException.class,()->tx(()->mapper.narrowGrant(childGrant,1,"deploy,execute",null,false,0)));
        assertThrows(RuntimeException.class,()->tx(()->mapper.insertGrant(row(SnowflakeIds.nextId(),other,"deploy",false,0,rootGrant,hq,relation))));
        long unrelatedChild=tenant(other),unrelatedRelation=relation(unrelatedChild,other,1);
        assertThrows(RuntimeException.class,()->tx(()->mapper.insertGrant(row(SnowflakeIds.nextId(),unrelatedChild,"deploy",false,0,rootGrant,other,unrelatedRelation))));
        assertThrows(RuntimeException.class,()->new JdbcTemplate(restricted).update("UPDATE nw_agent_definition_grant_revisions SET actions='execute' WHERE grant_id=?",rootGrant));
        assertThrows(RuntimeException.class,()->new JdbcTemplate(restricted).update("DELETE FROM nw_agent_definition_grant_revisions WHERE grant_id=?",rootGrant));
    }
    @Test void tenantServiceCreatesAuditedParentChainAndOldCreateKeyReadsRevokedFact() {
        var service=service();String key="tenant-service-"+source;
        var created=tx(()->service.tenantGrant(new Grant(source,child,List.of("deploy"),null,false,0,rootGrant),key));
        assertEquals(rootGrant,created.parentGrantId());assertEquals(hq,created.grantorTenantId());
        var revoked=tx(()->service.tenantRevoke(new Revoke(created.grantId(),1),"revoke-"+source));assertEquals("revoked",revoked.status());
        var replay=tx(()->service.tenantGrant(new Grant(source,child,List.of("deploy"),null,false,0,rootGrant),key));assertEquals(created.grantId(),replay.grantId());assertEquals("revoked",replay.status());
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM nw_agent_definition_grants WHERE request_key=?",Integer.class,key));
        assertEquals(2,owner.queryForObject("SELECT count(*) FROM nw_agent_definition_grant_revisions WHERE grant_id=?",Integer.class,created.grantId()));
        assertEquals(2,owner.queryForObject("SELECT count(*) FROM nw_audit_logs WHERE resource_type='agent_definition_grant' AND resource_id=?",Integer.class,created.grantId().toString()));
    }
    @Test void failedSuccessAuditRollsBackGrantHistoryAndReceiptInSameTransaction() {
        String key="rollback-"+source;var service=service();
        assertThrows(RuntimeException.class,()->tx(()->{
            new JdbcTemplate(restricted).execute("SELECT set_config('test.fail_grant_audit','true',true)");
            return service.tenantGrant(new Grant(source,child,List.of("deploy"),null,false,0,rootGrant),key);
        }));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM nw_agent_definition_grants WHERE request_key=?",Integer.class,key));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM nw_agent_definition_grant_revisions WHERE request_key=?",Integer.class,key));
        assertEquals(2,owner.queryForObject("SELECT count(*) FROM nw_agent_definition_grants WHERE definition_id=?",Integer.class,source));
    }
    private AgentTemplateGrantService service(){return service(mapper);}
    private AgentTemplateGrantService service(AgentDeploymentMapper repository) {
        var templates=mock(AgentTemplateService.class);var definition=new AgentTemplateDO();definition.setId(source);definition.setTemplateKey("grant-source-"+source);definition.setOwnerKind("platform");definition.setStatus("published");definition.setDistributionPolicy(AgentDistributionPolicy.AUTHORIZED_DEPLOY);definition.setName("source");definition.setVersion("1.0.0");
        when(templates.getById(source)).thenReturn(definition);when(templates.lockTemplate(source)).thenReturn(definition);
        var foundation=mock(FoundationAccess.class);when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(false,Long.toString(hq),"9"));
        return new AgentTemplateGrantService(repository,templates,mock(PlatformManagementService.class),foundation,mock(InstitutionIntegrationService.class),new AuditService(auditMapper),json);
    }
    @Test void revokedGrantReadCanReturnNotReadyThroughRealTransactionalProxy() {
        tx(()->{mapper.revokeGrant(rootGrant,1,"platform:test","revoke-projection","f".repeat(64));mapper.appendGrantRevision(rootGrant,"platform:test","revoke","revoke-projection");return null;});
        var proxied=transactional(service());
        boolean ready=assertDoesNotThrow(()->tx(()->{
            try {proxied.requireFrozenChain(original,child,source,"deploy");return true;}
            catch(ApiException denied){assertEquals(403,denied.getStatus().value());return false;}
        }));
        assertFalse(ready);
        boolean chainReady=assertDoesNotThrow(()->tx(()->{
            try {proxied.requireChain(childGrant,child,source,"deploy");return true;}
            catch(ApiException denied){return false;}
        }));
        boolean grantedReady=assertDoesNotThrow(()->tx(()->{
            try {proxied.requireGranted(childGrant,child,source,"deploy");return true;}
            catch(ApiException denied){return false;}
        }));
        assertFalse(chainReady);assertFalse(grantedReady);
    }
    @Test void admissionDenialStillMarksOuterTransactionForRollback() {
        var proxied=transactional(service());
        assertThrows(UnexpectedRollbackException.class,()->tx(()->{
            try {proxied.requireFrozenChainForAdmission(original,child,source,"execute");}
            catch(ApiException denied){assertEquals(403,denied.getStatus().value());}
            return false;
        }));
    }
    @Test void technicalReadFailureStillRollsBackThroughRealTransactionalProxy() {
        var failing=mock(AgentDeploymentMapper.class);
        when(failing.chainAvailable(anyString(),anyLong(),anyLong(),anyString())).thenThrow(new DataAccessResourceFailureException("simulated_grant_database_unavailable"));
        var proxied=transactional(service(failing));
        assertThrows(UnexpectedRollbackException.class,()->tx(()->{
            try {proxied.requireFrozenChain(original,child,source,"deploy");}
            catch(DataAccessResourceFailureException unavailable){assertEquals("simulated_grant_database_unavailable",unavailable.getMessage());}
            return false;
        }));
    }
    private AgentTemplateGrantService transactional(AgentTemplateGrantService target) {
        var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(restricted),new AnnotationTransactionAttributeSource()));
        return (AgentTemplateGrantService)factory.getProxy();
    }
    @Test void sameActorCreateKeyCannotSelectAnotherTargetEvenUnderRace() throws Exception {
        String key="same-intent-"+source;var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var left=pool.submit(()->{await(start);return createIntent(key,hq);});
            var right=pool.submit(()->{await(start);return createIntent(key,other);});
            start.countDown();assertEquals(1,(left.get(10,TimeUnit.SECONDS)?1:0)+(right.get(10,TimeUnit.SECONDS)?1:0));
        }
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM nw_agent_definition_grants WHERE actor_ref='platform:test' AND request_key=?",Integer.class,key));
    }
    private boolean createIntent(String key,long target) {
        try{return tx(()->{mapper.lockExternal("grant:platform:test",0,"create:"+key);if(mapper.findGrantRequest("platform:test",key)!=null)return false;
            var candidate=row(SnowflakeIds.nextId(),target,"deploy",false,0,null,null,null);
            var intent=new GrantRow(candidate.grantId(),candidate.definitionId(),candidate.targetTenantId(),candidate.actions(),null,"active",1,candidate.actorRef(),key,candidate.fingerprint(),null,null,candidate.createdAt(),"platform",null,"platform",null,null,null,null,null,false,0);
            return mapper.insertGrant(intent)==1;});}catch(RuntimeException conflict){return false;}
    }
    @Test void admissionShareLockSerializesOnlyExactGrantRevoke() throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var admission=pool.submit(()->tx(()->{mapper.lockGrant(rootGrant);locked.countDown();await(release);return true;}));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            var revoke=pool.submit(()->tx(()->{started.countDown();return mapper.revokeGrant(rootGrant,1,"platform:test","race","f".repeat(64));}));
            assertTrue(started.await(10,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->revoke.get(150,TimeUnit.MILLISECONDS));release.countDown();assertTrue(admission.get(10,TimeUnit.SECONDS));assertEquals(1,revoke.get(10,TimeUnit.SECONDS));
        } finally {release.countDown();}
        assertFalse(available(original,"deploy"));
    }
    private GrantRow row(long id,long target,String actions,boolean regrant,int depth,Long parent,Long issuer,Long relationId) {
        return new GrantRow(id,source,target,actions,null,"active",1,issuer==null?"platform:test":"tenant:"+issuer,"key-"+id,"f".repeat(64),null,null,Instant.now(),"platform",null,
                issuer==null?"platform":"tenant",issuer,parent,parent==null?null:1L,relationId,relationId==null?null:1L,regrant,depth);
    }
    private boolean available(GrantChain chain,String action){return tx(()->mapper.chainAvailable(encode(chain),child,source,action));}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException(failure);}}
    private long tenant(Long parent){long id=SnowflakeIds.nextId();Long plan=owner.queryForObject("SELECT id FROM nw_billing_plans WHERE is_active ORDER BY id LIMIT 1",Long.class);owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id,status,deployment_mode,settings,parent_tenant_id) VALUES(?,?,?,?,'active','cloud','{}',?)",id,"grant-"+id,"grant-"+id,plan,parent);return id;}
    private long relation(long childId,Long parent,long revision){long id=SnowflakeIds.nextId();owner.update("INSERT INTO nw_institution_relation_revisions(id,child_tenant_id,parent_tenant_id,revision,tree_revision,actor_ref,command_ref) SELECT ?,?,?,?,revision,'platform:test',? FROM nw_institution_tree_revision WHERE singleton",id,childId,parent,revision,"relation-"+id);return id;}
    private void move(long childId,long parent,long revision){owner.update("UPDATE nw_tenants SET parent_tenant_id=?,relation_revision=? WHERE id=?",parent,revision,childId);relation(childId,parent,revision);}
    private <T>T tx(Supplier<T> action){TenantContext.setTenantId(Long.toString(hq==0?1:hq));try{return writer.execute(status->action.get());}finally{TenantContext.clear();}}
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("grant_lock_timeout");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}}
}
