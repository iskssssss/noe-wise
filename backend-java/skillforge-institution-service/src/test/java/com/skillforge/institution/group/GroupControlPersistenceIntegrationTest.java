package com.skillforge.institution.group;

import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.access.SessionAccessPolicy;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.institution.group.GroupControlContracts.*;
import com.skillforge.institution.group.GroupSenderContracts.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
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

/** 正式Flyway与受限PG下的群控制权威事实；远端ACK只是可信参与方协议夹具，不代表进程隔离验收。 */
@Testcontainers(disabledWithoutDocker=false)
class GroupControlPersistenceIntegrationTest {
    private static final Set<String> SCOPES=Set.of("groups.write","groups.read","groups.controls.write","groups.controls.read","groups.controls.reconcile","groups.controls.release","groups.controls.ack","groups.senders.manage");
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16").withDatabaseName("group_controls").withUsername("skillforge").withPassword("isolated-control-owner");
    private static JdbcTemplate owner,restricted;
    private static TransactionTemplate transactions;
    private static SqlSessionTemplate sessions;
    private GroupBindingService bindings;
    private GroupControlService controls;
    private GroupSenderService senders;
    private Domain domain,manager,otherIntegration,otherTenant;
    private GroupContracts.GroupView group;

    /** 每类独占映射端口和新PG；完整迁移后只给NOSUPERUSER/NOBYPASSRLS角色业务权限。 */
    @BeforeAll static void database() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()).locations("classpath:db/migration").load().migrate();
        owner=new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()));
        owner.execute("CREATE ROLE group_control_test LOGIN PASSWORD 'isolated-control-role' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO group_control_test");owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO group_control_test");
        owner.execute("""
            CREATE FUNCTION group_control_test_reject_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
            IF current_setting('test.fail_control_receipt',true)='true' THEN RAISE EXCEPTION 'controlled_receipt_failure' USING ERRCODE='P0001'; END IF;
            RETURN NEW; END $$
            """);
        owner.execute("CREATE TRIGGER group_control_test_receipt BEFORE INSERT ON nw_group_control_commands FOR EACH ROW EXECUTE FUNCTION group_control_test_reject_receipt()");
        var source=new DriverManagerDataSource(DATABASE.getJdbcUrl(),"group_control_test","isolated-control-role");restricted=new JdbcTemplate(source);transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
        var configuration=new MybatisConfiguration(new Environment("group-controls",new SpringManagedTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true);configuration.setArgNameBasedConstructorAutoMapping(true);configuration.addInterceptor(new TenantRlsInterceptor());
        for(var mapper:List.of(TenantMapper.class,AuditLogMapper.class,ServicePrincipalMapper.class,SessionMapper.class))configuration.addMapper(mapper);
        for(var path:List.of("mapper/GroupBindingMapper.xml","mapper/InstitutionIntegrationMapper.xml","mapper/SessionMapper.xml","mapper/ServicePrincipalMapper.xml")) {
            try(var stream=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(stream,configuration,path,configuration.getSqlFragments()).parse();}
        }
        GroupControlMapper.configure(configuration);sessions=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
    }
    /** 真实固定SP、独立执行域及产品Session，通过正式领域ensure建立合法群基线。 */
    @BeforeEach void fixture() throws Exception {
        domain=domain(tenant(),null,null);manager=domain(domain.tenant(),domain.integration(),domain.scope());
        otherIntegration=domain(domain.tenant(),null,null);otherTenant=domain(tenant(),null,null);
        var principals=new ServicePrincipalService(sessions.getMapper(ServicePrincipalMapper.class),mock(CryptoService.class));
        var integrations=new InstitutionIntegrationService(sessions.getMapper(InstitutionIntegrationMapper.class),sessions.getMapper(TenantMapper.class),principals,mock(PlatformManagementService.class));
        var audit=new AuditService(sessions.getMapper(AuditLogMapper.class));var json=new ObjectMapper();
        var productSessions=new SessionService(sessions.getMapper(SessionMapper.class),mock(OperationalSettingsService.class),mock(SessionAccessPolicy.class));
        bindings=new GroupBindingService(sessions.getMapper(GroupBindingMapper.class),integrations,mock(EndUserService.class),productSessions,mock(GroupAgentEligibility.class),audit,json);
        controls=new GroupControlService(mapper(),integrations,audit,json);senders=new GroupSenderService(controls,mapper());
        group=in(domain,()->bindings.ensure(new GroupContracts.Ensure("control-test","group-"+SnowflakeIds.nextId(),"life",domain.scope()),"ensure"));
    }
    @AfterEach void clear(){ServicePrincipalContext.clear();TenantContext.clear();}

    @Test void senderRequiredSetAndAckAreDurableWhileOriginalPostReceiptRemainsStable() {
        var command=command("pause",0,"paused",List.of("send"));var original=in(domain,()->controls.command(command,"pause"));
        assertEquals("PARTIAL",original.status());assertTrue(original.requiredParticipants().stream().anyMatch(value->value.endpointId().equals("unregistered_sender")));
        var registration=in(domain,()->senders.register(new Register(group.groupBindingId(),"sender",1L,0L,"shared-ledger"),"register"));assertEquals("REGISTERING",registration.status());
        in(domain,()->senders.ack(new Ack(group.groupBindingId(),"sender",1L,"pause",1L,"shared-ledger","durable-pause",1L,List.of()),"ack"));
        assertEquals("APPLIED",in(domain,()->controls.detail(group.groupBindingId(),"pause")).status());
        assertEquals(original,in(domain,()->controls.command(command,"pause")));
        controls=rebuild();assertEquals("APPLIED",in(domain,()->controls.detail(group.groupBindingId(),"pause")).status());
        assertEquals(1,count("nw_group_control_commands"));assertEquals(2,count("nw_group_control_acks"));
    }
    @Test void staleCasIsPersistentlyRejectedAndCannotBeChangedToAnotherCommandBody() {
        in(domain,()->controls.command(command("first",0,"paused",List.of("execution")),"first"));
        var stale=command("stale",0,"running",List.of("execution"));var rejected=in(domain,()->controls.command(stale,"stale"));
        assertEquals("rejected",rejected.resultKind());assertEquals(1,in(domain,()->controls.current(group.groupBindingId())).controlRevision());
        assertEquals(rejected,in(domain,()->controls.command(stale,"stale")));
        assertEquals(409,assertThrows(ApiException.class,()->in(domain,()->controls.command(command("stale",1,"running",List.of("execution")),"stale"))).getStatus().value());
        assertEquals(2,count("nw_group_control_commands"));
        sqlState(assertThrows(RuntimeException.class,()->in(domain,()->restricted.update("UPDATE nw_group_control_commands SET result_kind='accepted',accepted_revision=2 WHERE tenant_id=? AND group_binding_id=? AND command_id='stale'",domain.tenant(),id()))),"23514");
    }
    @Test void terminalizationAndLateAcceptanceCompeteForOneIrreversibleCommandFact() throws Exception {
        var command=command("race",0,"paused",List.of("send"));var fingerprint=controls.commandFingerprint(command);var start=new CountDownLatch(1);var ready=new CountDownLatch(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var accepted=pool.submit(()->{ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));return in(domain,()->controls.command(command,"race"));});
            var ended=pool.submit(()->{ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));return in(manager,()->controls.terminalize(new Terminalize(group.groupBindingId(),"race",fingerprint,"local-intent",null,0L),"terminalize"));});
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();var left=accepted.get(30,TimeUnit.SECONDS);var right=ended.get(30,TimeUnit.SECONDS);
            assertEquals(left.resultKind(),right.resultKind());assertEquals(left.resultKind(),in(domain,()->controls.command(command,"race")).resultKind());
            assertEquals(1,count("nw_group_control_commands"));assertEquals("accepted".equals(left.resultKind())?1:0,in(domain,()->controls.current(group.groupBindingId())).controlRevision());
        }
    }
    @Test void anotherManagerReadsTheOriginalTerminalBoundaryAfterNewControlRevision() {
        var command=command("unknown",0,"paused",List.of("send"));var fingerprint=controls.commandFingerprint(command);
        var original=in(domain,()->controls.terminalize(new Terminalize(group.groupBindingId(),"unknown",fingerprint,"intent",null,0L),"terminalize"));
        in(domain,()->controls.command(command("later",0,"paused",List.of("execution")),"later"));
        var recovered=in(manager,()->controls.terminalize(new Terminalize(group.groupBindingId(),"unknown",fingerprint,"intent",null,1L),"manager-terminalize"));
        assertEquals(original,recovered);assertEquals(0,recovered.controlRevision());assertEquals(0L,recovered.throughTriggerSequence());
        assertEquals("terminal_not_accepted",in(domain,()->controls.command(command,"unknown")).resultKind());
        assertEquals(1,in(domain,()->controls.current(group.groupBindingId())).controlRevision());
    }
    @Test void receiptFailureRollsBackStateRevisionParticipantAndAckTogether() {
        sqlState(assertThrows(RuntimeException.class,()->in(domain,()->{restricted.queryForObject("SELECT set_config('test.fail_control_receipt','true',true)",String.class);return controls.command(command("atomic",0,"paused",List.of("execution","send")),"atomic");})),"P0001");
        for(var table:List.of("nw_group_control_commands","nw_group_control_states","nw_group_control_participants","nw_group_control_acks","nw_group_control_boundaries"))assertEquals(0,count(table));
        assertEquals(0,in(domain,()->controls.current(group.groupBindingId())).controlRevision());
        assertEquals(1,in(domain,()->controls.command(command("atomic",0,"paused",List.of("execution","send")),"atomic")).controlRevision());
    }
    @Test void endpointPrincipalIsolationAndRetirementCannotBeBypassedByAckOrAnotherLedger() {
        in(domain,()->senders.register(new Register(group.groupBindingId(),"sender",1L,0L,"shared-ledger"),"register-one"));
        var initial=new Ack(group.groupBindingId(),"sender",1L,"initial",0L,"shared-ledger","initial-one",1L,List.of());
        assertEquals(403,assertThrows(ApiException.class,()->in(manager,()->senders.ack(initial,"forged-ack"))).getStatus().value());
        in(domain,()->senders.ack(initial,"initial-one"));
        assertEquals(409,assertThrows(ApiException.class,()->in(domain,()->senders.register(new Register(group.groupBindingId(),"renamed",1L,0L,"empty-ledger"),"bypass-id"))).getStatus().value());
        assertEquals(409,assertThrows(ApiException.class,()->in(domain,()->senders.register(new Register(group.groupBindingId(),"sender",2L,1L,"empty-ledger"),"bypass-ledger"))).getStatus().value());
        in(manager,()->senders.register(new Register(group.groupBindingId(),"sender",2L,1L,"shared-ledger"),"register-two"));
        in(domain,()->controls.command(command("handoff",0,"paused",List.of("execution")),"handoff"));
        var next=new Ack(group.groupBindingId(),"sender",2L,"handoff",1L,"shared-ledger","initial-two",2L,List.of());
        assertEquals(409,assertThrows(ApiException.class,()->in(manager,()->senders.ack(next,"too-early"))).getStatus().value());
        var retire=new Retire(group.groupBindingId(),"sender",1L,2L,"shared-ledger",2L,"retirement-proof","fence-two",List.of(new Flight("old-delivery","UNKNOWN",null)));
        var retired=in(manager,()->senders.retire(retire,"retire"));
        assertEquals(List.of(new Flight("old-delivery","UNKNOWN",null)),in(domain,()->controls.detail(group.groupBindingId(),"handoff")).inFlight());
        in(manager,()->senders.ack(next,"initial-two"));
        assertTrue(in(domain,()->controls.detail(group.groupBindingId(),"handoff")).inFlight().isEmpty());
        assertEquals(retired,in(manager,()->senders.retire(retire,"retire")));
        assertEquals(1L,in(domain,()->restricted.queryForObject("SELECT count(*) FROM nw_group_sender_endpoint_flights WHERE tenant_id=? AND group_binding_id=? AND generation=1 AND delivery_id='old-delivery' AND status='UNKNOWN'",Long.class,domain.tenant(),id())));
        assertEquals(409,assertThrows(ApiException.class,()->in(domain,()->senders.ack(initial,"old-generation"))).getStatus().value());
        var endpoints=in(domain,()->controls.current(group.groupBindingId())).endpoints();assertEquals(List.of("RETIRED","ACTIVE"),endpoints.stream().map(Endpoint::status).toList());
        assertEquals("retirement-proof",endpoints.getFirst().retirementReceiptRef());
    }
    @Test void restrictedRlsAndExactIntegrationRejectCrossDomainWithoutNewFacts() {
        in(domain,()->controls.command(command("private",0,"paused",List.of("execution")),"private"));
        assertEquals(403,assertThrows(ApiException.class,()->in(otherIntegration,()->controls.current(group.groupBindingId()))).getStatus().value());
        assertEquals(403,assertThrows(ApiException.class,()->in(otherTenant,()->controls.current(group.groupBindingId()))).getStatus().value());
        assertTrue(in(otherTenant,()->mapper().states(domain.tenant(),domain.integration(),id())).isEmpty());
        sqlState(assertThrows(RuntimeException.class,()->in(otherIntegration,()->mapper().ensure(domain.tenant(),otherIntegration.integration(),id()))),"23503");
        assertEquals(1,count("nw_group_control_commands"));
    }

    private GroupControlService rebuild(){return new GroupControlService(mapper(),new InstitutionIntegrationService(sessions.getMapper(InstitutionIntegrationMapper.class),sessions.getMapper(TenantMapper.class),new ServicePrincipalService(sessions.getMapper(ServicePrincipalMapper.class),mock(CryptoService.class)),mock(PlatformManagementService.class)),new AuditService(sessions.getMapper(AuditLogMapper.class)),new ObjectMapper());}
    private Command command(String id,long expected,String desired,List<String> scopes){return new Command(id,group.groupBindingId(),null,expected,desired,scopes);}
    private GroupControlMapper mapper(){return sessions.getMapper(GroupControlMapper.class);}
    private long id(){return Long.parseLong(group.groupBindingId());}
    private long count(String table){assertTrue(Set.of("nw_group_control_commands","nw_group_control_states","nw_group_control_participants","nw_group_control_acks","nw_group_control_boundaries").contains(table));return owner.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=? AND group_binding_id=?",Long.class,domain.tenant(),id());}
    private long tenant(){long id=SnowflakeIds.nextId();Long plan=owner.queryForObject("SELECT id FROM nw_billing_plans WHERE is_active ORDER BY id LIMIT 1",Long.class);owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id,status,deployment_mode,settings) VALUES(?,?,?,?,'active','cloud','{}')",id,"control tenant","control-"+id,plan);return id;}
    private Domain domain(long tenant,String integration,String scope) throws Exception {
        if(integration==null){integration="control-"+SnowflakeIds.nextId();scope=Long.toString(SnowflakeIds.nextId());owner.update("INSERT INTO nw_institution_integrations(tenant_id,integration_id) VALUES(?,?)",tenant,integration);owner.update("INSERT INTO nw_integration_business_scopes(scope_id,tenant_id,integration_id,scope_type,external_ref,display_name,status,revision,actor_ref) VALUES(?,?,?,'business',?,'control','active',1,'fixture')",scope,tenant,integration,scope);}
        String principal="sp-control-"+SnowflakeIds.nextId();owner.update("INSERT INTO nw_service_principals(id,tenant_id,principal_id,integration_id,api_key_hash,api_key_prefix,signing_secret_encrypted,scopes,status,credential_version) VALUES(?,?,?,?,?,?,?,?::jsonb,'active',1)",SnowflakeIds.nextId(),tenant,principal,integration,String.format("%064d",SnowflakeIds.nextId()),"control_test","unused-secret",new ObjectMapper().writeValueAsString(SCOPES));return new Domain(tenant,integration,scope,principal);
    }
    /** 每个线程显式设定身份与租户，真实事务结束后清理，不借用主线程上下文。 */
    private <T>T in(Domain actor,Supplier<T> action){try{TenantContext.setTenantId(Long.toString(actor.tenant()));ServicePrincipalContext.set(new ServicePrincipalContext.Identity(actor.principal(),Long.toString(actor.tenant()),actor.integration(),1,SCOPES));return transactions.execute(status->{restricted.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,Long.toString(actor.tenant()));return action.get();});}finally{clear();}}
    private static void sqlState(Throwable failure,String expected){for(Throwable value=failure;value!=null;value=value.getCause())if(value instanceof SQLException sql&&expected.equals(sql.getSQLState()))return;fail("Expected PostgreSQL SQLSTATE "+expected,failure);}
    /** 每个测试独立的真实租户/执行域/业务范围/固定SP。 */
    private record Domain(long tenant,String integration,String scope,String principal) { }
}
