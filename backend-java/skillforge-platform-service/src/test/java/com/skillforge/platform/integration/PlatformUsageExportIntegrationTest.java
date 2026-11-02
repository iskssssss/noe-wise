package com.skillforge.platform.integration;

import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;

import com.skillforge.core.billing.export.PlatformUsageGrantHop;
import com.skillforge.core.billing.export.PlatformUsageGrantHopRow;
import com.skillforge.core.billing.export.PlatformUsageMapper;
import com.skillforge.core.billing.export.PlatformUsageQuery;
import com.skillforge.core.billing.export.PlatformUsageRecorder;
import com.skillforge.core.billing.export.PlatformUsageRow;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.UsageAdjustment;
import com.skillforge.core.billing.metering.AmountStatus;
import com.skillforge.core.billing.metering.UsageEventKind;
import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.foundation.integration.IntegrationProvenanceMapper;
import com.skillforge.core.foundation.integration.IntegrationProvenanceService;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** 完整Flyway及真实MyBatis事务验证平台单流；仅使用本类拥有的新PostgreSQL。 */
@Testcontainers
class PlatformUsageExportIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("platform_usage").withUsername("skillforge").withPassword("test-only");
    private static final AtomicLong IDS=new AtomicLong(810000000);
    private static JdbcTemplate jdbc;
    private static DataSource source;
    private static TransactionTemplate tx;
    private static PlatformUsageMapper publications;
    private static CostEventMapper events;
    private static IntegrationProvenanceMapper provenanceMapper;
    @BeforeAll static void initialize() throws Exception {
        source=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        config.addInterceptor(new com.skillforge.core.foundation.config.TenantRlsInterceptor());
        config.setEnvironment(new org.apache.ibatis.mapping.Environment("platform-usage-test",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),source));
        for(String xml:List.of("CostEventMapper","PlatformUsageMapper","IntegrationProvenanceMapper")) {
            try(var input=new ClassPathResource("mapper/"+xml+".xml").getInputStream()) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(input,config,"mapper/"+xml+".xml",config.getSqlFragments()).parse();
            }
        }
        var sql=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        publications=sql.getMapper(PlatformUsageMapper.class);events=sql.getMapper(CostEventMapper.class);
        provenanceMapper=sql.getMapper(IntegrationProvenanceMapper.class);
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void lateCommitReceivesPositionAfterAlreadyPublishedHigherSourceId() throws Exception {
        long tenant=tenant();var low=event(tenant,"measured");var high=event(tenant,"measured");
        var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var pending=pool.submit(()->tx.executeWithoutResult(status->{store(low);inserted.countDown();await(release);}));
            await(inserted);tx.executeWithoutResult(status->store(high));publish();
            long earlier=position(high);assertNull(positionOrNull(low));release.countDown();pending.get(20,TimeUnit.SECONDS);
            publish();assertTrue(position(low)>earlier);
        } finally { release.countDown(); }
    }
    @Test void sourceAndPendingRollbackTogetherAndRepeatedSourceDoesNotDuplicate() {
        var event=event(tenant(),"measured");
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{store(event);throw new IllegalStateException("rollback");}));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM nw_usage_publication_pending WHERE usage_id=?",Long.class,event.getId()));
        tx.executeWithoutResult(status->{store(event);store(event);});publish();publish();
        assertEquals("{}",jdbc.queryForObject("SELECT meta::text FROM nw_usage_events WHERE tenant_id=? AND id=?",String.class,event.getTenantId(),event.getId()));
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM nw_usage_publication_events WHERE usage_id=?",Long.class,event.getId()));
    }
    @Test void finalSnapshotRevisionSurvivesActualSqlProjectionForBillingConsumer() {
        long tenant=tenant();var event=event(tenant,"measured");event.setUsageSnapshotRevision(7L);
        event.setEventType("llm.chat");event.setAttemptId("final-snapshot-"+event.getId());
        event.setUsageSource("PROVIDER_REPORTED");event.setAmountStatus("unpriced");
        event.setPromptTokens(100L);event.setCompletionTokens(50L);
        tx.executeWithoutResult(status->store(event));publish();
        var actual=publications.list(Set.of(tenant),null,PlatformUsageQuery.empty(),0,publications.streamTail(),100).getFirst();
        assertEquals(7L,actual.getUsageSnapshotRevision());assertNull(actual.getReportRevision());
        assertEquals(50L,actual.getCompletionTokens());
    }
    @Test void concurrentPublishersAndRestartPreserveOneMonotonicStream() throws Exception {
        long tenant=tenant();for(int i=0;i<12;i++){var event=event(tenant,"measured");tx.executeWithoutResult(status->store(event));}
        try(var pool=Executors.newFixedThreadPool(3)) {
            var a=pool.submit(PlatformUsageExportIntegrationTest::publish);var b=pool.submit(PlatformUsageExportIntegrationTest::publish);
            a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
        }
        assertEquals(12L,jdbc.queryForObject("SELECT count(DISTINCT publication_cursor) FROM nw_usage_publication_events WHERE tenant_id=?",Long.class,tenant));
        long tail=publications.streamTail();publish();assertEquals(tail,publications.streamTail());
    }
    @Test void systemFactsShareStableIdentityAndEffectiveReportSemantics() {
        long tenant=tenant();var original=event(tenant,"measured");original.setEventType("llm.chat");original.setAttemptId("attempt-"+original.getId());
        original.setUsageSource("UNKNOWN");original.setAmountStatus("unresolved");original.setCurrency("CNY");
        var report=event(tenant,"usage_report");report.setEventType("llm.chat");report.setAttemptId(original.getAttemptId());
        report.setUsageSource("PROVIDER_REPORTED");report.setAmountStatus("unresolved");report.setReportRevision(1L);report.setPromptTokens(10L);report.setCompletionTokens(5L);
        var confirmation=event(tenant,"reconciliation");confirmation.setEventType("llm.chat");confirmation.setAdjustmentOfEventId(original.getId());
        confirmation.setAmountStatus("priced");confirmation.setCurrency("CNY");confirmation.setTotalAmount(new BigDecimal("1.5"));
        tx.executeWithoutResult(status->{store(original);store(report);store(confirmation);});publish();
        var totals=publications.summarize(Set.of(tenant),null,PlatformUsageQuery.empty()).getFirst();
        assertEquals(1L,totals.getInvocationCount());assertEquals(10L,totals.getPromptTokens());assertEquals(5L,totals.getCompletionTokens());
        assertEquals(0L,totals.getUnresolvedCostCount());assertEquals(0,new BigDecimal("1.5").compareTo(totals.getAmountCents()));
        assertEquals(3,publications.list(Set.of(tenant),null,PlatformUsageQuery.empty(),0,publications.streamTail(),100).size());
        assertTrue(jdbc.queryForObject("SELECT event_id FROM nw_usage_publication_events WHERE usage_id=?",String.class,original.getId()).startsWith(tenant+":"));
    }
    @Test void restrictedRoleCannotReadOtherTenantOrPublishCallerSuppliedFacts() throws Exception {
        long own=tenant(),foreign=tenant();tx.executeWithoutResult(status->{store(event(own,"measured"));store(event(foreign,"measured"));});publish();
        String role="usage_reader_"+IDS.incrementAndGet();jdbc.execute("CREATE ROLE "+role+" LOGIN PASSWORD 'test-only' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT SELECT ON nw_usage_publication_events TO "+role);
        jdbc.execute("GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO "+role);
        var restricted=new DriverManagerDataSource(DATABASE.getJdbcUrl(),role,"test-only");
        try(var connection=restricted.getConnection();var statement=connection.createStatement()) {
            connection.setAutoCommit(false);statement.execute("SELECT set_config('app.tenant_id','"+own+"',true)");
            try(var rows=statement.executeQuery("SELECT count(*) FROM nw_usage_publication_events WHERE tenant_id="+own)) {
                assertTrue(rows.next());assertEquals(1,rows.getLong(1));
            }
            try(var rows=statement.executeQuery("SELECT count(*) FROM nw_usage_publication_events WHERE tenant_id="+foreign)) {
                assertTrue(rows.next());assertEquals(0,rows.getLong(1));
            }
            assertThrows(java.sql.SQLException.class,()->statement.execute("INSERT INTO nw_usage_publication_events(event_id,tenant_id,usage_id,stream_revision,publication_cursor) VALUES('forged',"+own+",1,'fake',1)"));
            connection.rollback();
        }
    }

    @Test void frozenGrantFactsRollbackAtomicallyAndConfirmationInheritsOriginalWithoutCurrentLookup() {
        long tenant=tenant();TenantContext.setTenantId(Long.toString(tenant));
        var original=event(tenant,"measured");original.setEventType("tool.call");original.setAmountStatus("unpriced");
        original.setQuantity(null);original.setCurrency("CNY");original.setSourceOwnerKind("platform");
        original.setAgentDefinitionId(123L);original.setAgentRevisionId(456L);original.setAgentRevision(2);
        original.setAgentDefinitionVersion("1.0.0");
        original.setGrantChain(List.of(
                new PlatformUsageGrantHop(1,10L,3L,"platform",null,999L,null,null,null,null),
                new PlatformUsageGrantHop(2,11L,4L,"tenant",999L,tenant,10L,3L,12L,2L)));
        var recorder=new PlatformUsageRecorder(publications,null);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{
            assertEquals(1,events.insert(original));recorder.enqueue(original);throw new IllegalStateException("rollback");
        }));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM nw_usage_event_grant_hops WHERE usage_id=?",Long.class,original.getId()));
        tx.executeWithoutResult(status->{assertEquals(1,events.insert(original));recorder.enqueue(original);});
        assertEquals("call",jdbc.queryForObject("SELECT measurement_unit FROM nw_usage_events WHERE id=?",String.class,original.getId()));
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("usage",recorder);
        var costs=new CostRecorder(events,org.mockito.Mockito.mock(PriceCalculator.class),null,beans.getBeanProvider(PlatformUsageRecorder.class));
        var adjustment=tx.execute(status->costs.recordAdjustment(Long.toString(tenant),new UsageAdjustment(
                "tool-confirm-"+original.getId(),original.getId(),UsageEventKind.RECONCILIATION,
                AmountStatus.UNRESOLVED,null,null,null,null,null,"CNY",java.util.Map.of())));
        assertNotNull(adjustment);assertNull(adjustment.getQuantity());
        assertEquals(original.getAgentDefinitionId(),adjustment.getAgentDefinitionId());
        assertEquals(original.getAgentRevisionId(),adjustment.getAgentRevisionId());
        assertEquals(original.getGrantChain(),adjustment.getGrantChain());
        var batch=publications.grantHops(Set.of(tenant),List.of(original.getId(),adjustment.getId()));
        assertEquals(4,batch.size());assertEquals(List.of(1,2,1,2),batch.stream().map(PlatformUsageGrantHopRow::getOrdinal).toList());
        publish();
        var rows=publications.list(Set.of(tenant),null,PlatformUsageQuery.empty(),0,publications.streamTail(),100);
        assertEquals(2,rows.size());assertTrue(rows.stream().allMatch(row->"call".equals(row.getMeasurementUnit())));
        var totals=publications.summarize(Set.of(tenant),null,PlatformUsageQuery.empty()).getFirst();
        assertEquals(1L,totals.getUnknownUsageCount());assertEquals(1L,totals.getUnresolvedCostCount());
        assertNull(totals.getAmountCents());
    }

    @Test void generatedUnitsMatchActualQuantityAndUnknownDoesNotTurnIntoZero() {
        long tenant=tenant();var request=event(tenant,"measured");
        var rerank=event(tenant,"measured");rerank.setEventType("llm.rerank");rerank.setQuantity(3);rerank.setAmountStatus("unpriced");
        var unknown=event(tenant,"usage_report");unknown.setEventType("tool.call");unknown.setQuantity(null);
        unknown.setAmountStatus("unresolved");unknown.setAttemptId("unknown-"+unknown.getId());unknown.setReportRevision(0L);
        tx.executeWithoutResult(status->{store(request);store(rerank);store(unknown);});publish();
        var rows=publications.list(Set.of(tenant),null,PlatformUsageQuery.empty(),0,publications.streamTail(),100);
        assertEquals(List.of("request","document","call"),rows.stream().map(PlatformUsageRow::getMeasurementUnit).toList());
        assertNull(rows.getLast().getQuantity());assertNull(rows.getLast().getAmountCents());
        assertEquals(1L,publications.summarize(Set.of(tenant),null,PlatformUsageQuery.empty()).getFirst().getUnknownUsageCount());
    }

    @Test void sourceOwnerConstraintRejectsPartialNullPairsInActualInsertSql() {
        long tenant=tenant();TenantContext.setTenantId(Long.toString(tenant));
        var invalid=event(tenant,"measured");invalid.setSourceOwnerTenantId(tenant);
        RuntimeException rejected=assertThrows(RuntimeException.class,
                ()->tx.executeWithoutResult(status->events.insert(invalid)));
        Throwable cause=rejected;while(cause.getCause()!=null)cause=cause.getCause();
        assertTrue(cause.getMessage().contains("usage_source_owner"),cause.getMessage());
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM nw_usage_events WHERE id=?",Long.class,invalid.getId()));
    }

    /** 真实表列/JSON路径回归：原生自有Agent合法空授权链；本fixture不表示执行过Runtime调用。 */
    @Test void frozenSourceAndGrantHopsReadActualScopeSnapshotColumn() {
        long tenant=tenant(),agent=IDS.incrementAndGet(),revision=IDS.incrementAndGet();
        String run="usage-binding-"+agent;TenantContext.setTenantId(Long.toString(tenant));
        String member="""
                {"agent_id":"%s","name":"usage binding fixture","system_prompt":"fixture","role_description":"fixture",
                 "execution_config":{},"model_id":"fixture-model","model_revision":1,
                 "mounts":{"tenant_id":"%s","agent_id":"%s","generation":1,"mounts":[]},
                 "agent_revision_id":%s,"agent_revision":3,"delegation_config":{"enabled":false,"target_agent_ids":[]},
                 "agent_definition_version":"agent:3","template_id":null}
                """.formatted(agent,tenant,agent,revision);
        jdbc.update("""
                INSERT INTO public.nw_runtime_agent_run_bindings
                 (tenant_id,run_id,input_id,session_id,target_kind,target_id,end_user_id,actor_agent_id,role,scope_snapshot,frozen_member)
                VALUES(?,?,?,?,'AGENT',?,7,?,'PRIMARY','{"authorizations":{}}'::jsonb,?::jsonb)
                """,Long.toString(tenant),run,"input-"+run,"session-"+run,Long.toString(agent),Long.toString(agent),member);
        var frozen=publications.frozenSource(Long.toString(tenant),run,agent);
        assertNotNull(frozen);assertEquals("tenant",frozen.getSourceOwnerKind());
        assertEquals(tenant,frozen.getSourceOwnerTenantId());assertEquals(revision,frozen.getAgentRevisionId());
        assertEquals(3,frozen.getAgentRevision());assertEquals("agent:3",frozen.getAgentDefinitionVersion());
        assertNull(frozen.getAgentDefinitionId());assertNull(frozen.getDeploymentId());
        assertEquals(List.of(),publications.frozenGrantHops(Long.toString(tenant),run,agent));
        assertNull(publications.frozenSource(Long.toString(tenant),run,agent+1));
        assertEquals(List.of(),publications.frozenGrantHops(Long.toString(tenant),run,agent+1));
    }

    /** 真实 typed Run 与系统宿主不可变输入快照的 SQL 投影；本用例不声称发生过 Provider 调用。 */
    @Test void systemAgentRunUsesExactHostSnapshotWithoutInstitutionBindingOrCurrentDefinition() {
        var f=systemRun(true,true);TenantContext.setTenantId(Long.toString(f.tenant()));
        var provenance=new IntegrationProvenanceService(provenanceMapper,null);
        var actual=provenance.resolveRun(Long.toString(f.tenant()),f.run());
        assertNotNull(actual);assertEquals("system",actual.originKind());assertEquals(f.agent(),actual.agentId());
        assertEquals("system:platform-system-agent",actual.actorRef());
        assertEquals("system-agent:"+f.agent(),actual.responsibilityRef());assertNull(actual.integrationId());
        var event=event(f.tenant(),"measured");event.setRunId(f.run());event.setAgentId(f.agent());
        event.setEventType("tool.call");event.setCallPoint("any-trusted-caller");event.setAmountStatus("unpriced");
        var recorder=new PlatformUsageRecorder(publications,provenance);
        tx.executeWithoutResult(status->{recorder.attribute(event);assertEquals(1,events.insert(event));recorder.enqueue(event);});
        assertEquals("platform",event.getSourceOwnerKind());assertNull(event.getSourceOwnerTenantId());
        assertEquals("system-agent:"+f.agent()+":published:2",event.getAgentDefinitionVersion());
        assertNull(event.getAgentDefinitionId());assertNull(event.getAgentRevisionId());assertNull(event.getAgentRevision());
        assertNull(event.getDeploymentId());assertNull(event.getGrantId());assertTrue(event.getGrantChain().isEmpty());
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM nw_runtime_agent_run_bindings WHERE tenant_id=? AND run_id=?",
                Long.class,Long.toString(f.tenant()),f.run()));
        publish();
        var exported=publications.list(Set.of(f.tenant()),null,PlatformUsageQuery.empty(),0,publications.streamTail(),100).getFirst();
        assertEquals(event.getAgentDefinitionVersion(),exported.getAgentDefinitionVersion());
        assertEquals("platform",exported.getSourceOwnerKind());assertNull(exported.getAgentDefinitionId());
    }

    @Test void missingOrDifferentSystemBusinessOriginCannotBorrowSnapshotOrCallPoint() {
        for(var f:List.of(systemRun(false,true),systemRun(true,false))) {
            TenantContext.setTenantId(Long.toString(f.tenant()));
            var provenance=new IntegrationProvenanceService(provenanceMapper,null);
            assertNull(provenance.resolveRun(Long.toString(f.tenant()),f.run()));
            assertNull(publications.frozenSource(Long.toString(f.tenant()),f.run(),f.agent()));
            var event=event(f.tenant(),"measured");event.setRunId(f.run());event.setAgentId(f.agent());
            event.setCallPoint("system-agent.model");
            assertEquals("usage_persistent_origin_missing",assertThrows(IllegalStateException.class,
                    ()->new PlatformUsageRecorder(publications,provenance).attribute(event)).getMessage());
        }
    }

    @Test void duplicateInstitutionAndSystemSourcesAreRejectedInsteadOfSelectingEither() {
        var f=systemRun(true,true);TenantContext.setTenantId(Long.toString(f.tenant()));
        jdbc.update("INSERT INTO nw_agents(id,tenant_id,agent_key,name) VALUES(?,?,?,'dual source fixture')",
                f.agent(),f.tenant(),"dual-"+f.agent());
        jdbc.update("INSERT INTO nw_end_users(id,tenant_id,display_name) VALUES(?,?,'dual source user')",f.user(),f.tenant());
        jdbc.update("""
                INSERT INTO nw_chat_sessions(id,session_id,tenant_id,end_user_id,agent_id,origin_kind,actor_ref,responsibility_ref)
                VALUES(?,?,?,?,?,'native','member:5',?)
                """,IDS.incrementAndGet(),f.session(),f.tenant(),f.user(),f.agent(),"tenant:"+f.tenant());
        jdbc.update("""
                INSERT INTO nw_integration_run_sources(tenant_id,run_id,origin_kind,actor_ref,responsibility_ref,agent_id,session_id,root_input_id)
                VALUES(?,?,'native','member:5',?,?,?,?)
                """,f.tenant(),f.run(),"tenant:"+f.tenant(),f.agent(),f.session(),f.input());
        jdbc.update("""
                INSERT INTO nw_runtime_agent_run_bindings(tenant_id,run_id,input_id,session_id,target_kind,target_id,end_user_id,
                 actor_agent_id,role,scope_snapshot,frozen_member)
                VALUES(?,?,?,?,'AGENT',?,? ,?,'PRIMARY','{"authorizations":{}}'::jsonb,'{}'::jsonb)
                """,Long.toString(f.tenant()),f.run(),f.input(),f.session(),Long.toString(f.agent()),f.user(),Long.toString(f.agent()));
        assertThrows(RuntimeException.class,()->provenanceMapper.run(Long.toString(f.tenant()),f.run()));
        assertThrows(RuntimeException.class,()->publications.frozenSource(Long.toString(f.tenant()),f.run(),f.agent()));
    }

    /** 通过正式 typed Store 准入建立完整外键根；来源表按系统 Host 原注册字段持久化。 */
    private static SystemRun systemRun(boolean withSnapshot,boolean matchingOrigin) {
        long tenant=tenant(),agent=IDS.incrementAndGet(),user=IDS.incrementAndGet();String key=Long.toString(agent);
        String session="system-session-"+key,input="system-input-"+key,run="system-run-"+key,business="system-business-"+key;
        TenantContext.setTenantId(Long.toString(tenant));
        jdbc.update("""
                INSERT INTO nw_runtime_host_owners(tenant_id,target_kind,target_id,agent_id,session_id,end_user_id,integration_id)
                VALUES(?,'AGENT',?,?,?,?,'system-agents')
                """,Long.toString(tenant),key,key,session,user);
        if(withSnapshot) {
            String data="""
                    {"run_id":"%s","prompt_source_ref":"system-agent:%s:published:2","generation":2,
                     "config":{"name":"source fixture","description":"","system_prompt":"fixture","model_config_id":"model",
                      "timeout_seconds":30,"max_business_model_calls":1,"mounts":[]},
                     "model_revision":3,"mounts":[],"input_fingerprint":"%s"}
                    """.formatted(matchingOrigin?business:"different-business",key,"a".repeat(64));
            jdbc.update("""
                    INSERT INTO nw_system_agent_runtime_capability_sources(tenant_id,session_id,agent_id,end_user_id,input_id,generation,source_data)
                    VALUES(?,?,?,?,?,2,?::jsonb)
                    """,Long.toString(tenant),session,key,user,input,data);
        }
        var tenantRef=new TenantId(Long.toString(tenant));var agentRef=new AgentId(key);
        var owner=SessionOwner.user(new SessionRef(tenantRef,session),agentRef,new EndUserId(user));
        var principal=new PrincipalRef.SystemPrincipal(tenantRef,"platform-system-agent");
        var verified=new VerifiedIdentity(principal,owner,Set.of("sessions.write"),1,new ResultRef("verified-"+key));
        var ref=new RunRef(tenantRef,run);var identity=new RunIdentity(owner,agentRef,new InputId(input),ref,
                new TraceEnvelope("trace-"+key,"span-"+key,new InputId(input),Optional.empty()),verified.verificationRef(),
                new RunRequester.System(principal),Optional.empty(),new RunOriginRef(tenantRef,business));
        var capacity=new ExecutionBudgetPort.Resources(1,0,0,0,100,100,200);
        var limits=new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,capacity,capacity,capacity,30,30,"system-source-fixture");
        var store=new JdbcExecutionStore(source, new JdbcIdentityPolicyPort(source, java.util.Map.of()),(sql,current,actor,prepared)->false);
        assertTrue(store.admit(new ExecutionStore.Admission(verified,identity,"source-"+key,new ResultRef("configuration-"+key),
                new ExecutionStore.Material(new ResultRef("input-"+key),new TypedPayload("fixture.input.v1","{}")),
                new ExecutionStore.Material(new ResultRef("context-"+key),new TypedPayload("fixture.context.v1","{}")),
                new ExecutionBudgetPort.Registration(ref,ref,ExecutionBudgetPort.Role.PRIMARY,new ExecutionBudgetPort.Policy.Enforced(limits)),
                List.of(),List.of())).created());
        return new SystemRun(tenant,agent,user,session,input,run);
    }

    private record SystemRun(long tenant,long agent,long user,String session,String input,String run) { }
    /** 使用生产SQL与同一事务写源事实和pending，失败不留下孤立投影。 */
    private static void store(CostEventDO event) { if(events.insert(event)==1)publications.insertPending(event); }
    private static long publish() { return tx.execute(status->publications.publishCommitted(1000)); }
    private static Long positionOrNull(CostEventDO event) {
        var values=jdbc.queryForList("SELECT publication_cursor FROM nw_usage_publication_events WHERE usage_id=?",Long.class,event.getId());
        return values.isEmpty()?null:values.getFirst();
    }
    private static long position(CostEventDO event) { return positionOrNull(event); }
    private static long tenant() {
        long id=IDS.incrementAndGet();jdbc.update("INSERT INTO nw_tenants(id,name,slug,plan_id) SELECT ?,'usage fixture',?,id FROM nw_billing_plans WHERE plan_code='free'",id,"usage-"+id);return id;
    }
    /** 系统来源不伪造外部integration；事实仍必有真实费用机构和调用责任。 */
    private static CostEventDO event(long tenant,String kind) {
        var event=new CostEventDO();event.setId(IDS.incrementAndGet());event.setTenantId(tenant);event.setOriginKind("system");
        event.setActorRef("call_point:usage-test");event.setResponsibilityRef("tenant:"+tenant);event.setEventId("usage-"+event.getId());
        event.setEventType("request");event.setUsageEventKind(kind);event.setUsageSource("UNKNOWN");event.setAmountStatus("not_applicable");
        event.setOccurredAt(Instant.now());event.setIngestedAt(Instant.now());event.setCreatedAt(Instant.now());event.setQuantity(1);return event;
    }
    private static void await(CountDownLatch latch) { try { if(!latch.await(20,TimeUnit.SECONDS))throw new AssertionError("transaction latch timeout"); }
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);} }
}
