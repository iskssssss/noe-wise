package com.skillforge.core.shared.sessionobservation;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.ObservationReadAudit;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.foundation.access.PlatformObservationTargetMapper;
import com.skillforge.core.foundation.access.FoundationAuthorizedSqlRule;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.integration.publicread.RuntimeCompletedAnswerReader;
import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.observation.ChatObservationAuthorizedSqlRule;
import com.skillforge.core.chat.observation.SessionObservationMapper;
import com.skillforge.core.chat.observation.SessionObservationQuery;
import com.skillforge.core.chat.observation.SessionObservationRepository;
import com.skillforge.core.chat.observation.SessionObservationService;
import com.skillforge.core.chat.session.SessionTraceService;
import com.skillforge.core.chat.trajectory.TrajectoryMapper;
import com.skillforge.core.chat.trajectory.TrajectoryRepository;
import com.skillforge.core.chat.trajectory.TrajectoryProjectionService;
import com.skillforge.core.chat.trajectory.TrajectorySnapshotCache;
import com.skillforge.core.chat.trajectory.TrajectorySourceAuthorizer;
import com.skillforge.core.billing.application.BillingTraceQueryService;
import com.skillforge.core.knowledge.KnowledgeObservationAccess;
import com.skillforge.platform.sessionmodelattempt.*;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.core.*;
import com.skillforge.runtime.session.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 当前全迁移与真实typed Store/受限MyBatis诊断闭包；不写旧Run、旧fact或旧preparation fixture。 */
@Testcontainers(disabledWithoutDocker=false)
class ModelAttemptDiagnosticIntegrationTest {
    private static final TenantId TENANT=new TenantId("101");
    private static final CodecLimits LIMITS=new CodecLimits(1_000_000,100_000,128);
    private static final AgentExecutionCodec CODEC=new AgentExecutionCodec(LIMITS);
    private static final long TOKENS=9_007_199_254_740_993L;
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16").withDatabaseName("typed_diagnostic").withUsername("admin").withPassword("test-only");
    private static DriverManagerDataSource source;private static ModelAttemptDiagnosticMapper mapper;private static TransactionTemplate transactions;
    private static SessionObservationMapper observationMapper;private static TrajectoryMapper trajectoryMapper;
    @BeforeAll static void schema() throws Exception {
        var adminSource=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());var admin=new JdbcTemplate(adminSource);
        admin.execute("CREATE ROLE skillforge NOSUPERUSER NOBYPASSRLS");Flyway.configure().dataSource(adminSource).locations("classpath:db/migration").load().migrate();
        admin.update("INSERT INTO nw_billing_plans(id,plan_code,display_name,slo_availability_target) VALUES(910000,'typed-diagnostic','typed诊断',0.9900)");
        admin.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(101,'诊断机构','typed-diagnostic',910000)");
        admin.update("INSERT INTO nw_end_users(id,tenant_id,display_name) VALUES(920101,101,'诊断用户')");
        admin.update("INSERT INTO nw_agents(id,tenant_id,agent_key,name) VALUES(910101,101,'typed-diagnostic','诊断Agent')");
        admin.update("INSERT INTO nw_chat_sessions(id,session_id,tenant_id,end_user_id,agent_id,message_count,origin_kind,actor_ref,responsibility_ref) VALUES(930101,'typed-diagnostic',101,920101,910101,0,'system','system:fixture','tenant:101')");
        admin.execute("CREATE ROLE typed_diagnostic LOGIN PASSWORD 'test-only-business' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA public TO typed_diagnostic");admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO typed_diagnostic");admin.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO typed_diagnostic");
        admin.execute("GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO typed_diagnostic");
        source=new DriverManagerDataSource(DATABASE.getJdbcUrl(),"typed_diagnostic","test-only-business");
        assertFalse(new JdbcTemplate(source).queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);config.addInterceptor(new TenantRlsInterceptor(List.of(new FoundationAuthorizedSqlRule(),new ChatObservationAuthorizedSqlRule(),new ModelAttemptObservationAuthorizedSqlRule())));factory.setConfiguration(config);factory.setMapperLocations(new ClassPathResource("mapper/ModelAttemptDiagnosticMapper.xml"),new ClassPathResource("mapper/SessionObservationMapper.xml"),new ClassPathResource("mapper/TrajectoryMapper.xml"));
        var template=new SqlSessionTemplate(factory.getObject());mapper=template.getMapper(ModelAttemptDiagnosticMapper.class);
        observationMapper=template.getMapper(SessionObservationMapper.class);trajectoryMapper=template.getMapper(TrajectoryMapper.class);
        transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    @AfterEach void clear(){TenantContext.clear();}
    @Test void completedAttemptReadsOnlyTypedActualMaterialAndReportedBudgetTokens() {
        var fixture=fixture(true);var audit=mock(ObservationReadAudit.class);var detail=read(fixture,audit);
        assertEquals("FULL",detail.input().availability());assertEquals("完整真实推理",detail.reasoning().value());assertEquals(Long.toString(TOKENS),detail.usage().totals().promptTokens());
        assertEquals("7",detail.usage().totals().completionTokens());assertNull(detail.invocationId(),"没有Provider审计时保留缺失，不能造外调记录");assertNull(detail.startedAt());
        assertEquals("FULL",detail.capabilityCallsAvailability());assertTrue(detail.capabilityCalls().isEmpty());
        var json=detail.input().value().toString();assertTrue(json.contains("真实用户问题"));assertFalse(json.contains("private-binding"));assertFalse(json.contains("private-resolution"));
        TenantContext.setTenantId("101");transactions.execute(status->{var row=mapper.attempt(101,"typed-diagnostic",fixture.attempt(),fixture.root()).getFirst();assertEquals(fixture.action(),row.get("action_id"));assertEquals(fixture.request().canonicalJson(),row.get("request_json"));return null;});
        verify(audit).record(eq("101"),eq("99"),eq("platform.session.model-attempt.detail.read"),eq("model_attempt"),eq(fixture.attempt()),anyString(),anyMap());
    }
    @Test void registeredAttemptWithoutProviderAuditStillCountsAndRetainsUnknownUsage() {
        var fixture=fixture(false);var detail=read(fixture,mock(ObservationReadAudit.class));
        assertEquals("UNRESOLVED",detail.status());assertEquals("FULL",detail.input().availability());assertEquals("NOT_RECORDED",detail.output().availability());
        assertEquals("UNKNOWN",detail.usage().reportStatus());assertNull(detail.usage().totals().promptTokens());assertNull(detail.usage().totals().completionTokens());assertNull(detail.startedAt());
        TenantContext.setTenantId("102");transactions.execute(status->{assertTrue(mapper.attempt(101,"typed-diagnostic",fixture.attempt(),fixture.root()).isEmpty());return null;});
    }
    private ModelAttemptDetail read(Fixture fixture,ObservationReadAudit audit) {
        var access=mock(FoundationAccess.class);when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true,null,"99"));
        var service=new ModelAttemptDiagnosticService(mapper,new ExecutionModelAttemptInspector(LIMITS),audit,
                mock(SessionObservationAuthorizer.class),mock(TrajectoryProjectionService.class),reads(access));TenantContext.clear();
        var scope=mock(ModelAttemptDiagnosticScope.class);when(scope.actorId()).thenReturn("99");
        return transactions.execute(status->service.detail(scope,101,"typed-diagnostic",fixture.root(),fixture.attempt(),null,null));
    }
    @Test void childWithoutProductSessionIsReadableOnlyThroughItsExactAuthorizedParentTrajectory() {
        var parent=fixture(true);var child=childFixture(parent);var audit=mock(ObservationReadAudit.class);
        var access=mock(FoundationAccess.class);when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true,null,"99"));
        var authorization=new SessionObservationAuthorizer(access);
        var reads=reads(access);
        var guard=new PublicOutputGuard("./build/child-attempt-diagnostic-artifacts");
        var sessions=new SessionObservationService(new SessionObservationRepository(observationMapper,reads),mock(SessionTraceService.class),
                audit,new ObjectMapper(),new RuntimeCompletedAnswerReader(new ObjectMapper()),guard,reads);
        var trajectories=new TrajectoryProjectionService(new TrajectoryRepository(trajectoryMapper,reads),authorization,sessions,
                mock(CryptoService.class),new ObjectMapper(),audit,reads,mock(BillingTraceQueryService.class),
                mock(TrajectorySnapshotCache.class),new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(TrajectorySourceAuthorizer.class),
                mock(KnowledgeObservationAccess.class),mock(com.skillforge.core.model.invocation.ModelCallRecordReader.class),
                LIMITS.maxCodeUnits(),LIMITS.maxNodes(),LIMITS.maxDepth());
        var service=new ModelAttemptDiagnosticService(mapper,new ExecutionModelAttemptInspector(LIMITS),audit,authorization,trajectories,reads);
        var scope=new ModelAttemptDiagnosticAuthorizer(access).platformAdmin();
        String childSession=child.actor().owner().sessionRef().sessionId();
        var fixtureReader=new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()));
        assertEquals(0,fixtureReader.queryForObject("SELECT count(*) FROM nw_chat_sessions WHERE tenant_id=101 AND session_id=?",Integer.class,childSession));
        TenantContext.clear();
        transactions.execute(status->{
            var detail=service.detail(scope,101,childSession,child.root(),child.attempt(),"typed-diagnostic",parent.root());
            assertEquals(childSession,detail.sessionId());assertEquals(child.run().runId(),detail.runId());
            assertEquals(childSession,detail.usage().sourceSessionId());assertEquals(child.root(),detail.usage().sourceRootInputId());
            assertEquals("子Agent供应商推理",detail.reasoning().value());assertEquals("3",detail.usage().totals().promptTokens());
            assertTrue(detail.input().value().toString().contains("子Agent真实问题"));
            assertEquals(org.springframework.http.HttpStatus.NOT_FOUND,assertThrows(ApiException.class,
                    ()->service.detail(scope,101,childSession,child.root(),child.attempt(),null,null)).getStatus());
            assertEquals(org.springframework.http.HttpStatus.NOT_FOUND,assertThrows(ApiException.class,
                    ()->service.detail(scope,101,childSession,child.root(),child.attempt(),"typed-diagnostic","forged-root")).getStatus());
            assertEquals(org.springframework.http.HttpStatus.NOT_FOUND,assertThrows(ApiException.class,
                    ()->service.detail(scope,101,childSession,child.root(),child.attempt(),childSession,child.root())).getStatus());
            assertEquals(org.springframework.http.HttpStatus.NOT_FOUND,assertThrows(ApiException.class,
                    ()->service.detail(scope,102,childSession,child.root(),child.attempt(),"typed-diagnostic",parent.root())).getStatus());
            return null;
        });
    }
    private static PlatformObservationAccess reads(FoundationAccess access) {
        return new PlatformObservationAccess(access,mock(PlatformObservationTargetMapper.class));
    }
    @Test void oldUsageOffersDiagnosticsOnlyForExactCoreRequestsAndNeverAuxiliaryOrUnsupportedMaterials() throws Exception {
        var core=fixture(true);var unsupported=fixture(true,false);
        String auxiliary=auxiliaryAttempt(core);
        var access=mock(FoundationAccess.class);when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true,null,"99"));
        var reads=reads(access);var audit=mock(ObservationReadAudit.class);
        var authorization=new SessionObservationAuthorizer(access);
        var publicSessions=new SessionObservationService(new SessionObservationRepository(observationMapper,reads),mock(SessionTraceService.class),
                audit,new ObjectMapper(),new RuntimeCompletedAnswerReader(new ObjectMapper()),new PublicOutputGuard("./build/usage-diagnostic-artifacts"),reads);
        var scope=authorization.platformAdmin();TenantContext.clear();
        var coreUsage=transactions.execute(status->publicSessions.usage(scope,
                new SessionObservationQuery.Usage(101,"typed-diagnostic",core.root(),null,50)));
        assertEquals(1,coreUsage.attempts().size());assertEquals(core.attempt(),coreUsage.attempts().getFirst().attemptId());
        assertTrue(coreUsage.attempts().getFirst().diagnosticAvailable());
        assertEquals("typed-diagnostic",coreUsage.attempts().getFirst().sourceSessionId());
        assertEquals(core.root(),coreUsage.attempts().getFirst().sourceRootInputId());
        assertEquals(Long.toString(TOKENS),coreUsage.totals().promptTokens());
        assertTrue(coreUsage.attempts().stream().noneMatch(value->value.attemptId().equals(auxiliary)),
                "旧usage保持正式view的Core分母，不能把辅助预算当成Core诊断材料");
        var unavailable=transactions.execute(status->publicSessions.usage(scope,
                new SessionObservationQuery.Usage(101,"typed-diagnostic",unsupported.root(),null,50)));
        assertEquals(1,unavailable.attempts().size());assertFalse(unavailable.attempts().getFirst().diagnosticAvailable());
        assertEquals(Long.toString(TOKENS),unavailable.totals().promptTokens(),"诊断可用性不改变原Provider Token口径");
        var json=new ObjectMapper().findAndRegisterModules().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
                .valueToTree(unavailable.attempts().getFirst());
        assertTrue(json.has("diagnostic_available"));assertFalse(json.get("diagnostic_available").booleanValue());
        assertEquals("typed-diagnostic",json.get("source_session_id").textValue());
        assertEquals(unsupported.root(),json.get("source_root_input_id").textValue());
        var diagnostic=new ModelAttemptDiagnosticService(mapper,new ExecutionModelAttemptInspector(LIMITS),audit,authorization,
                mock(TrajectoryProjectionService.class),reads);
        var diagnosticScope=new ModelAttemptDiagnosticAuthorizer(access).platformAdmin();
        transactions.execute(status->{assertEquals(org.springframework.http.HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class,()->diagnostic.detail(diagnosticScope,101,"typed-diagnostic",core.root(),auxiliary,null,null)).getStatus());return null;});
    }
    /** 正式辅助预算及已提交外部审计事实；不调用模型、不伪造Core Action或材料。 */
    private String auxiliaryAttempt(Fixture parent) {
        String invocation="diagnostic-aux:"+UUID.randomUUID();String attemptId=invocation+":attempt";
        var budgets=new com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets(source);
        var helper=new com.skillforge.core.billing.budget.AuxiliaryModelBudgets(budgets,
                mock(com.skillforge.core.foundation.settings.OperationalSettingsService.class));
        try(var binding=ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(budgets,parent.run()));
            var auxiliary=helper.reserve("101",invocation,attemptId,
                    com.skillforge.core.billing.budget.AuxiliaryModelBudgets.Request.freeze(10,10,"model.chat","fixture:1",List.of("diagnostic")))) {
            assertTrue(auxiliary.sent());
            com.skillforge.core.billing.budget.AuxiliaryModelBudgets.captureUsage(new com.skillforge.core.billing.metering.LlmUsage(7L,3L,10L));
            auxiliary.resultSaved();
        }
        transactions.executeWithoutResult(status->{
            var jdbc=new JdbcTemplate(source);jdbc.queryForObject("SELECT set_config('app.tenant_id','101',true)",String.class);
            jdbc.update("""
                    INSERT INTO nw_external_invocations(id,tenant_id,invocation_id,attempt_id,operation_type,provider,service,status,
                        input_id,root_input_id,trace_id,span_id,source_event_id,started_at,metadata)
                    VALUES(?,101,?,?,'model.chat','aux-provider','aux-model','SUCCEEDED',?,?,?,?,?,now(),'{}'::jsonb)
                    """,com.skillforge.core.shared.config.SnowflakeIds.nextId(),invocation,attemptId,parent.root(),parent.root(),
                    "trace:"+parent.root(),"span:"+parent.root(),"event:"+invocation);
        });
        return attemptId;
    }
    /** 正式Store提供准入/派发/结果，fixture只提供已授权typed输入和实际Provider返回值。 */
    private Fixture fixture(boolean complete) {
        return fixture(complete,true);
    }
    private Fixture fixture(boolean complete,boolean currentTypedRequest) {
        var id=UUID.randomUUID().toString();var run=new RunRef(TENANT,"diagnostic:"+id);var input=new InputId("input:"+id);
        var owner=SessionOwner.user(new SessionRef(TENANT,"typed-diagnostic"),new AgentId("910101"),new EndUserId(920101));
        var actor=new VerifiedIdentity(new PrincipalRef.SystemPrincipal(TENANT,"fixture"),owner,Set.of("sessions.write"),1,new ResultRef("verified:"+id));
        var identity=new RunIdentity(owner,owner.target().requireAgent(),input,run,new TraceEnvelope("trace:"+id,"span:"+id,input,Optional.empty()),actor.verificationRef(),
                new RunRequester.EndUser(owner.requireUser()),Optional.empty(),new RunOriginRef(TENANT,input.value()));
        var capacity=new ExecutionBudgetPort.Resources(2,2,2,2,TOKENS+1000,100,TOKENS+1100);
        var budget=new ExecutionBudgetPort.Registration(run,run,ExecutionBudgetPort.Role.PRIMARY,new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,capacity,capacity,capacity,120,120,"diagnostic:v1")));
        var store=new JdbcExecutionStore(source, new JdbcIdentityPolicyPort(source, java.util.Map.of()),(session,state,current,prepared)->true);
        store.admit(new ExecutionStore.Admission(actor,identity,"fingerprint:"+id,new ResultRef("configuration:"+id),material(id+"-input",new TypedPayload("fixture.input","{}")),material(id+"-context",new TypedPayload("fixture.context","{}")),budget,List.of(),List.of()));
        var claimed=store.claim(new ExecutionStore.Claim(run,"worker","claim:"+id,Duration.ofMinutes(2))).orElseThrow();
        var target=new FrozenModelTarget(new ModelBindingRef(TENANT,"private-binding",1),"actual-model",new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT),false,true,new ResultRef("capabilities")),Duration.ofSeconds(30),new ResultRef("private-resolution"));
        var actualRequest=new FrozenModelRequest(new RequestedModelStrategy("strategy","1"),target,List.of(new ModelMessage("system",ModelRole.SYSTEM,"真实系统指令"),new ModelMessage("user",ModelRole.USER,"真实用户问题")),new ResponseContract(new ResponseFormat.Text(),EnforcementLevel.STRICT,"supported"),new GenerationOptions(OptionalInt.of(100),OptionalDouble.empty()),ReasoningPolicy.DEFAULT);
        var request=CODEC.encodeModelRequest(new AgentExecutionCodec.ModelCallRequest(actualRequest,List.of(),0,Optional.empty()));var action=new ActionRef(TENANT,"model:"+id);var attempt="attempt:"+id;
        var prepared=new ExecutionStore.Prepared(action,1,ExecutionStore.Kind.MODEL,"model",new ResultRef("model-capability"),
                material(id+"-request",currentTypedRequest?request:new TypedPayload("fixture.request","{}")),"request:"+id);
        var demand=new ExecutionBudgetPort.Demand(run,attempt,prepared.fingerprint(),ExecutionBudgetPort.Category.BUSINESS_MODEL,ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.BUSINESS_MODEL,TOKENS+1000,100),false);
        var registered=assertInstanceOf(ExecutionStore.DispatchResult.Registered.class,store.dispatch(new ExecutionStore.Dispatch(run,claimed.revision(),claimed.lease().orElseThrow(),prepared,attempt,demand,actor,Optional.empty(), List.of())));
        if(complete) {
            var payloads=new SessionRuntimePayloads();var core=new CoreRuntime(LIMITS,payloads);
            var result=new ModelResult.Completed(new TypedPayload(ModelPort.JSON_OUTPUT_SCHEMA,"{\"answer\":\"真实回答\"}"),"actual-model",new ModelUsage(OptionalLong.of(TOKENS),OptionalLong.of(7)),"stop",new ResultRef("provider-result:"+id),Optional.of("完整真实推理"));
            var saved=CODEC.encodeOutcome(core.portalResults(payloads,payloads,payloads).encodeModel(PortalCallKind.MODEL_GENERATE,result));var context=material(id+"-completed-context",new TypedPayload("fixture.context","{}"));
            store.complete(new ExecutionStore.Completion(run,attempt,ExecutionStore.ResultKind.SUCCEEDED,material(id+"-result",saved),Optional.empty(),Optional.empty(),ExecutionBudgetPort.Usage.reported(OptionalLong.of(TOKENS),OptionalLong.of(7)),registered.run().revision(),registered.run().lease().orElseThrow(),new ExecutionStore.Progress(1,0,0,context.reference()),context,List.of(),Optional.empty()));
        }
        return new Fixture(attempt,input.value(),action.actionId(),request,run,actor,store);
    }
    private Fixture childFixture(Fixture parent) {
        var id=UUID.randomUUID().toString();var store=parent.store();var current=store.load(parent.run()).orElseThrow();
        var parentAction=new ExecutionStore.Prepared(new ActionRef(TENANT,"delegate:"+id),2,ExecutionStore.Kind.DELEGATION,
                "delegate",new ResultRef("delegate-capability"),material(id+"-delegate",new TypedPayload("fixture.delegation","{}")),"delegate:"+id);
        var parentAttempt="delegate-attempt:"+id;
        var registered=assertInstanceOf(ExecutionStore.DispatchResult.Registered.class,store.dispatch(new ExecutionStore.Dispatch(
                parent.run(),current.revision(),current.lease().orElseThrow(),parentAction,parentAttempt,
                new ExecutionBudgetPort.Demand(parent.run(),parentAttempt,parentAction.fingerprint(),ExecutionBudgetPort.Category.TOOL,
                        ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL,0,0),false),parent.actor(),Optional.empty(),List.of())));
        var childRun=new RunRef(TENANT,"diagnostic-child:"+id);var input=new InputId("child-input:"+id);
        var owner=SessionOwner.user(new SessionRef(TENANT,"delegation:"+id),new AgentId("910101"),new EndUserId(920101));
        var actor=new VerifiedIdentity(new PrincipalRef.SystemPrincipal(TENANT,"child-fixture"),owner,Set.of("sessions.write"),1,new ResultRef("child-verified:"+id));
        var identity=new RunIdentity(owner,owner.target().requireAgent(),input,childRun,
                new TraceEnvelope("child-trace:"+id,"child-span:"+id,new InputId(parent.root()),Optional.empty()),actor.verificationRef(),
                new RunRequester.EndUser(owner.requireUser()),Optional.empty(),new RunOriginRef(TENANT,input.value()));
        var capacity=new ExecutionBudgetPort.Resources(2,2,2,2,TOKENS+1000,100,TOKENS+1100);
        var budget=new ExecutionBudgetPort.Registration(childRun,parent.run(),ExecutionBudgetPort.Role.MEMBER,
                new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,capacity,capacity,capacity,120,120,"diagnostic:v1")));
        var admission=new ExecutionStore.Admission(actor,identity,"child-fingerprint:"+id,new ResultRef("child-configuration:"+id),
                material(id+"-input",new TypedPayload("fixture.input","{}")),material(id+"-context",new TypedPayload("fixture.context","{}")),budget,List.of(),List.of());
        store.admitChild(new ExecutionStore.ChildAdmission(registered,parentAction,new ExecutionStore.DelegatedChild("entry:"+id),admission),
                (session,parentRun,origin,candidate)->{});
        var claimed=store.claim(new ExecutionStore.Claim(childRun,"child-worker","claim:"+id,Duration.ofMinutes(2))).orElseThrow();
        var target=new FrozenModelTarget(new ModelBindingRef(TENANT,"private-binding",1),"actual-child-model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT),false,true,new ResultRef("child-capabilities")),Duration.ofSeconds(30),new ResultRef("private-resolution"));
        var actualRequest=new FrozenModelRequest(new RequestedModelStrategy("strategy","1"),target,
                List.of(new ModelMessage("child-user",ModelRole.USER,"子Agent真实问题")),
                new ResponseContract(new ResponseFormat.Text(),EnforcementLevel.STRICT,"supported"),
                new GenerationOptions(OptionalInt.of(10),OptionalDouble.empty()),ReasoningPolicy.DEFAULT);
        var request=CODEC.encodeModelRequest(new AgentExecutionCodec.ModelCallRequest(actualRequest,List.of(),0,Optional.empty()));
        var action=new ActionRef(TENANT,"child-model:"+id);var attempt="child-attempt:"+id;
        var prepared=new ExecutionStore.Prepared(action,1,ExecutionStore.Kind.MODEL,"model",new ResultRef("child-model-capability"),
                material(id+"-request",request),"child-request:"+id);
        var sent=assertInstanceOf(ExecutionStore.DispatchResult.Registered.class,store.dispatch(new ExecutionStore.Dispatch(childRun,
                claimed.revision(),claimed.lease().orElseThrow(),prepared,attempt,new ExecutionBudgetPort.Demand(childRun,attempt,
                prepared.fingerprint(),ExecutionBudgetPort.Category.BUSINESS_MODEL,ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.BUSINESS_MODEL,100,10),false),actor,Optional.empty(),List.of())));
        var payloads=new SessionRuntimePayloads();var core=new CoreRuntime(LIMITS,payloads);
        var outcome=new ModelResult.Completed(new TypedPayload(ModelPort.JSON_OUTPUT_SCHEMA,"{\"answer\":\"子Agent真实回答\"}"),
                "actual-child-model",new ModelUsage(OptionalLong.of(3),OptionalLong.of(1)),"stop",new ResultRef("child-provider-result:"+id),Optional.of("子Agent供应商推理"));
        var saved=CODEC.encodeOutcome(core.portalResults(payloads,payloads,payloads).encodeModel(PortalCallKind.MODEL_GENERATE,outcome));
        var context=material(id+"-completed-context",new TypedPayload("fixture.context","{}"));
        store.complete(new ExecutionStore.Completion(childRun,attempt,ExecutionStore.ResultKind.SUCCEEDED,material(id+"-result",saved),
                Optional.empty(),Optional.empty(),ExecutionBudgetPort.Usage.reported(OptionalLong.of(3),OptionalLong.of(1)),sent.run().revision(),
                sent.run().lease().orElseThrow(),new ExecutionStore.Progress(1,0,0,context.reference()),context,List.of(),Optional.empty()));
        return new Fixture(attempt,parent.root(),action.actionId(),request,childRun,actor,store);
    }
    private static ExecutionStore.Material material(String id,TypedPayload payload){return new ExecutionStore.Material(new ResultRef("diagnostic-material:"+id),payload);}
    private record Fixture(String attempt,String root,String action,TypedPayload request,RunRef run,VerifiedIdentity actor,JdbcExecutionStore store){}
}
