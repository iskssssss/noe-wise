package com.skillforge.institution.artifact;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static com.skillforge.institution.artifact.DeliverableContract.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.institution.runtime.AgentRuntimeGoalDriver;
import com.skillforge.institution.runtime.InstitutionPlanHost;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.core.*;
import com.skillforge.runtime.session.*;
import com.skillforge.runtime.session.spi.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 新Store、Coordinator、Program、正式成果Mapper和事务的真实闭环；MODEL是确定测试Provider。
 * 文件仓储与当前宿主IAM使用受控边界，本组不替代文件扫描或生产身份集成验收。
 */
@Testcontainers(disabledWithoutDocker=false)
class TypedDeliverableLifecycleIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge").withUsername("typed_delivery_admin").withPassword("typed_delivery_password");
    static final CodecLimits LIMITS=new CodecLimits(1_000_000,100_000,64);
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static DataSource dataSource;

    /** 自有新库只执行正式迁移，不维护另一份Runtime或成果DDL。 */
    @BeforeAll static void migrate() throws Exception {
        var source=new PGSimpleDataSource();source.setURL(DATABASE.getJdbcUrl());source.setUser(DATABASE.getUsername());source.setPassword(DATABASE.getPassword());dataSource=source;
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.execute("CREATE ROLE skillforge NOLOGIN NOSUPERUSER NOBYPASSRLS");
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.execute("INSERT INTO nw_billing_plans(id,plan_code,display_name,slo_availability_target) VALUES(800000000000000311,'typed-delivery','Typed delivery',0.999)");
            statement.execute("INSERT INTO nw_tenants(id,name,slug,plan_id,status) VALUES(1,'Typed delivery','typed-delivery',800000000000000311,'active')");
        }
    }
    @AfterEach void clear() {TenantContext.clear();}

    /** ready候选只是可控文件，未调用finalize时不形成必须交付的宿主门禁。 */
    @Test void readyCandidatesDoNotRequireFinalizationBeforeAnOrdinaryReply() throws Exception {
        var f=new Fixture(false);
        var output=f.service.prepareOutput(f.identity);
        assertTrue(output.state().isEmpty());assertTrue(output.preparedResult().isEmpty());assertFalse(output.hasOutputs());
        assertFalse(f.service.hasOutputs(f.identity));
        assertNull(f.service.actionRejection(f.identity,"respond"));
        assertNull(f.service.actionRejection(f.identity,"execute"));
        assertEquals(0,f.models.get());assertEquals(0,f.finalizations.get());
    }

    /** 正式迁移只退役语义契约闭包，真实成果及外调/计量证据继续存在。 */
    @Test void currentSchemaRemovesOnlyTheRetiredSemanticRequirementTables() throws Exception {
        try(var connection=dataSource.getConnection();var query=connection.prepareStatement("SELECT to_regclass(?)::text")) {
            for(String table:List.of("nw_output_requirement_contracts","nw_output_requirement_sources",
                    "nw_output_requirement_items","nw_output_requirement_artifact_context","nw_output_requirement_nodes",
                    "nw_output_requirement_evidence","nw_output_requirement_run_bindings","nw_output_requirement_changes")) {
                query.setString(1,"public."+table);
                try(var rows=query.executeQuery()){assertTrue(rows.next());assertNull(rows.getString(1),table);}
            }
            for(String table:List.of("nw_deliverables","nw_deliverable_revisions","nw_task_results",
                    "nw_task_output_plans","nw_execution_artifact_files","nw_external_invocations","nw_usage_events",
                    "nw_model_call_records","nw_platform_system_agent_runs")) {
                query.setString(1,"public."+table);
                try(var rows=query.executeQuery()){assertTrue(rows.next());assertNotNull(rows.getString(1),table);}
            }
        }
    }

    /** 正式finalize由刚登记的工具调用进入，实际完成事务携带原成果引用后才发布。 */
    @Test void registeredFinalizationThenActualModelCompletionPublishesOneRevisionAndReplaysWithoutSending() throws Exception {
        var f=new Fixture(false);var outcome=f.drive();
        assertEquals(ExecutionCoordinator.Stop.FINISHED,outcome.stop());
        var run=f.store.load(f.identity.runRef()).orElseThrow();assertEquals(RunStatus.COMPLETED,run.status());
        var completion=f.store.event(f.identity.runRef(),AgentExecutionProgram.finishedEventId(f.identity.runRef())).orElseThrow();
        assertTrue(DeliverableService.completedReference(run,completion,f.service.preparedResult(f.identity).orElseThrow(),JSON));
        assertEquals("pending",f.mapper.publication("1",f.identity.runRef().runId()).get("status"));
        assertEquals(List.of(f.identity),f.service.pending("1",10),"已提交完成但通知丢失时须从Store恢复原精确身份");
        assertEquals(DeliverableService.PublicationExposure.PENDING,
                f.service.publicationExposure(f.identity.owner(),f.identity.runRef(),"task-result:"+f.identity.runRef().runId()));
        f.service.reconcile(f.identity);f.service.reconcile(f.identity);
        assertEquals("published",f.mapper.publication("1",f.identity.runRef().runId()).get("status"));
        assertEquals(DeliverableService.PublicationExposure.PUBLISHED,
                f.service.publicationExposure(f.identity.owner(),f.identity.runRef(),"task-result:"+f.identity.runRef().runId()));
        assertTrue(f.service.pending("1",10).isEmpty());
        assertEquals(1,f.mapper.revisionIds("1",f.identity.runRef().runId()).size());
        assertEquals(2,f.models.get());assertEquals(1,f.finalizations.get());
        assertEquals(ExecutionCoordinator.Stop.NOT_CLAIMED,f.drive().stop());
        assertEquals(2,f.models.get());assertEquals(1,f.finalizations.get());
    }

    /** 完成回调丢失后，正式恢复任务只发布原冻结清单；反复扫描不重做MODEL/工具或增加版本。 */
    @Test void committedOutputRecoversThroughPublicationJobUsingExactStoreIdentity() throws Exception {
        var f=new Fixture(false);f.drive();
        assertEquals(List.of(f.identity),f.service.pending("1",1));
        var outputs=new com.skillforge.institution.runtime.InstitutionOutputContract(f.service,mock(AgentRuntimeGoalDriver.class),
                mock(PublicOutputGuard.class),mock(SessionLogPort.class),JSON,mock(WorkflowInteractionView.class),
                new ArtifactClosingBudget(),mock(com.skillforge.institution.browser.BrowserClosingBudget.class));
        var recovery=new DeliverablePublicationJob(mock(com.skillforge.core.foundation.persistence.TenantMapper.class),f.service,outputs);
        recovery.recover(new TenantId("1"),1);
        assertEquals("published",f.mapper.publication("1",f.identity.runRef().runId()).get("status"));
        assertEquals(1,f.mapper.revisionIds("1",f.identity.runRef().runId()).size());
        assertTrue(f.service.pending("1",1).isEmpty());
        recovery.recover(new TenantId("1"),1);
        assertEquals(1,f.mapper.revisionIds("1",f.identity.runRef().runId()).size());
        assertEquals(2,f.models.get());assertEquals(1,f.finalizations.get());
    }

    /** 已发布记录的完整归属由Store确认，其他会话不能借合法结果引用取得发布状态。 */
    @Test void publicationExposureRejectsAnotherSessionOwnerWithSameTenant() throws Exception {
        var f=new Fixture(false);f.drive();f.service.reconcile(f.identity);
        var owner=f.identity.owner();
        var another=SessionOwner.user(new SessionRef(owner.sessionRef().tenantId(),"another-publication-session"),
                f.identity.agentId(),owner.requireUser());
        assertThrows(IllegalArgumentException.class,()->f.service.publicationExposure(another,f.identity.runRef(),"task-result:"+f.identity.runRef().runId()));
    }

    /** 已实际冻结后取消，迟到工具结果仍不能制造COMPLETED证明或公开成果。 */
    @Test void cancellationBeforeLateToolResultNeverPublishesFrozenCandidates() throws Exception {
        var f=new Fixture(true);f.drive();
        assertEquals(RunStatus.CANCELLED,f.store.load(f.identity.runRef()).orElseThrow().status());
        assertTrue(f.store.event(f.identity.runRef(),AgentExecutionProgram.finishedEventId(f.identity.runRef())).isEmpty());
        f.service.reconcile(f.identity);f.service.reconcile(f.identity);
        assertEquals("cancelled",f.mapper.publication("1",f.identity.runRef().runId()).get("status"));
        assertTrue(f.mapper.revisionIds("1",f.identity.runRef().runId()).isEmpty());
        assertEquals(1,f.models.get());assertEquals(1,f.finalizations.get());
    }

    /** 每例独立Run/Session和真实文件事实，不复用旧SessionRuntime生命周期夹具。 */
    private static final class Fixture implements AgentExecutionProgram.Sources {
        final AgentExecutionCodec codec=new AgentExecutionCodec(LIMITS);
        final SessionRuntimePayloads payloads=new SessionRuntimePayloads();
        final CoreRuntime core=new CoreRuntime(LIMITS,payloads);
        final JdbcExecutionBudgets budgets=new JdbcExecutionBudgets(dataSource);
        final JdbcExecutionStore store=new JdbcExecutionStore(dataSource,new JdbcIdentityPolicyPort(dataSource, java.util.Map.of()),budgets,(session,run,actor,prepared)->true);
        final AgentExecutionProgram program=new AgentExecutionProgram(this,core,LIMITS);
        final RunIdentity identity;
        final VerifiedIdentity actor;
        final AgentExecutionProgram.Configuration configuration;
        final SessionToolDirectory.Entry tool;
        final DeliverableMapper mapper;
        final DeliverableService service;
        final FinalizeTaskOutputsTool handler;
        final ExecutionCoordinator<AgentExecutionProgram.Materials> coordinator;
        final AtomicBoolean cancelled=new AtomicBoolean();
        final AtomicInteger models=new AtomicInteger(),finalizations=new AtomicInteger();
        final boolean cancelAfterFreeze;
        final Map<String,Object> finalization;

        Fixture(boolean cancelAfterFreeze) throws Exception {
            this.cancelAfterFreeze=cancelAfterFreeze;var suffix=UUID.randomUUID().toString();var tenant=new TenantId("1");var input=new InputId("input:"+suffix);
            var owner=SessionOwner.user(new SessionRef(tenant,"session:"+suffix),new AgentId("10"),new EndUserId(20));
            identity=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant,"run:"+suffix), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("original-identity:"+suffix), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
            actor=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant,owner.requireUser()),owner,Set.of("sessions.write"),1,identity.causationIdentityRef());
            var target=new FrozenModelTarget(new ModelBindingRef(tenant,"model-binding",1),"fixture-model",new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT),false,true,new ResultRef("model-capabilities")),Duration.ofSeconds(1),new ResultRef("model-resolution"));
            var profile=new SessionPreparations.Profile(new ResultRef("profile"),SessionPreparations.PROFILE_VERSION,SessionPreparations.AGENT_INPUT_RENDERER_VERSION,SessionAdmissionFacts.ContextKind.AGENT_INPUT,SessionPrompt.single("system",SessionPrompt.Type.HOST,"1","执行真实冻结并报告结果"),target.binding(),new RequestedModelStrategy("fixture","1"),new ResponseContract(new ResponseFormat.NativeConversation(),EnforcementLevel.STRICT,"native"),new GenerationOptions(OptionalInt.of(100),OptionalDouble.empty()),ReasoningPolicy.DEFAULT,PortalCallKind.MODEL_GENERATE,new ResultRef("tokenizer"),SessionContextPolicy.defaults(),100_000,100_000,100,false,Set.of(InboundKind.MESSAGE));
            var binding=new ToolBinding(ToolBinding.ProviderType.PLATFORM,"finalize_task_outputs","finalize_task_outputs");
            var frozenTool=new FrozenToolTarget(new ToolHandle("tool:finalize"),new CapabilityRef(CapabilityType.TOOL,"finalize","1"),"finalize",1,new TypedPayload("json-schema","{}"),new TypedPayload("json-schema","{}"),ToolEffect.IDEMPOTENT_WRITE,true,Duration.ofSeconds(30),true,ToolConfirmationPolicy.NEVER,Set.of(),Set.of("platform_handler:finalize_task_outputs"),true);
            tool=new SessionToolDirectory.Entry(frozenTool,binding,new SessionToolDirectory.Discovery(binding.serverIdentity(),binding.serverIdentity()+"/"+binding.toolName(),binding.toolName(),Optional.empty(),"冻结实际成果"),false);
            configuration=new AgentExecutionProgram.Configuration(profile,target,List.of(new ModelTool("finalize_outputs",frozenTool.handle().value(),"冻结实际成果",frozenTool.inputSchema(),false,ModelToolKind.TOOL_CALL,false)),List.of(tool),new LoopBudget.Enforced(10,10,0,100_000,100_000,200_000,Duration.ofMinutes(10)),new CapabilitySnapshotRef(owner.sessionRef(),input,"mounts",1),new ResultRef("mounts"),List.of(new ModelMessage("input",ModelRole.USER,"生成并交付 report.md")),actor,List.of(),List.of(),Set.of(),List.of(),PlanPort.State.absent(),false);
            var capacity=new ExecutionBudgetPort.Resources(10,0,10,0,1_000_000,1_000_000,2_000_000);
            var budget=new ExecutionBudgetPort.Registration(identity.runRef(),identity.runRef(),ExecutionBudgetPort.Role.PRIMARY,new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,capacity,capacity,capacity,600,600,"typed-delivery:v1")));
            var content=new ExecutionStore.Material(new ResultRef("opaque-original:"+suffix),codec.encodeInput(configuration.initialMessages(),new TypedPayload("input.v1","{\"text\":\"生成并交付report.md\",\"extraParameters\":{}}")));
            store.admit(new ExecutionStore.Admission(actor,identity,"admission:"+suffix,profile.reference(),content,program.initialContext(content.reference()),budget,List.of(AgentExecutionProgram.admissionEvent(identity)),List.of()));
            var transactions=new DataSourceTransactionManager(dataSource);
            var mybatis=new org.apache.ibatis.session.Configuration(new Environment("typed-deliverable",new SpringManagedTransactionFactory(),dataSource));mybatis.setMapUnderscoreToCamelCase(true);mybatis.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            try(var xml=getClass().getClassLoader().getResourceAsStream("mapper/DeliverableMapper.xml")) {
                if(xml==null)throw new IllegalStateException("deliverable_mapper_missing");
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(xml,mybatis,"mapper/DeliverableMapper.xml",mybatis.getSqlFragments()).parse();
            }
            mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(mybatis)).getMapper(DeliverableMapper.class);
            var bindings=mock(AgentRunBindings.class);var runBinding=mock(AgentRunBindings.Binding.class);when(bindings.find(identity.runRef())).thenReturn(Optional.of(runBinding));when(runBinding.owner()).thenReturn(owner);when(runBinding.actor()).thenReturn(identity.agentId());
            var storage=mock(ArtifactStorage.class);byte[] bytes="# real typed delivery".getBytes(StandardCharsets.UTF_8);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));var artifactId=UUID.randomUUID().toString();String fileId=artifactId+":report.md";
            try(var connection=dataSource.getConnection();var insert=connection.prepareStatement("INSERT INTO nw_execution_artifacts(id,tenant_id,session_id,run_id,invocation_id,type,name,size,sha256,manifest,inputs_sha256,created_at,expires_at,status) VALUES(?,1,?,?,?,'file','report',?,?,?::jsonb,?,now(),now()+interval '1 day','ready')")) {
                insert.setString(1,artifactId);insert.setString(2,owner.sessionRef().sessionId());insert.setString(3,identity.runRef().runId());insert.setString(4,"artifact:"+suffix);insert.setLong(5,bytes.length);insert.setString(6,hash);insert.setString(7,JSON.writeValueAsString(List.of(new ArtifactMetadata.File("report.md",bytes.length,hash))));insert.setString(8,hash);insert.executeUpdate();
            }
            try(var connection=dataSource.getConnection();var insert=connection.prepareStatement("INSERT INTO nw_execution_artifact_files(tenant_id,id,artifact_id,path,size,sha256,expires_at,status) VALUES(1,?,?,'report.md',?,?,now()+interval '1 day','ready')")){insert.setString(1,fileId);insert.setString(2,artifactId);insert.setLong(3,bytes.length);insert.setString(4,hash);insert.executeUpdate();}
            var artifact=new ArtifactRow(artifactId,"1",owner.sessionRef().sessionId(),identity.runRef().runId(),"artifact:"+suffix,"file","report",bytes.length,hash,"[]",hash,Instant.now(),Instant.now().plusSeconds(3600),"ready");
            when(storage.require("1",artifactId)).thenReturn(artifact);when(storage.publicScope(identity)).thenReturn(new PublicOutputGuard.Scope(List.of()));
            when(storage.verifiedFile(eq(artifact),eq("report.md"),anyInt())).thenAnswer(call->new ArtifactStorage.VerifiedFile(new ArtifactProvenance(artifactId,"report.md",bytes.length,hash),((Integer)call.getArgument(2))==0?null:bytes));
            var origins=com.skillforge.institution.runtime.ProgramResourceOriginFixture.ordinary(store);
            var requirements=new DeliverableRequirements();
            var real=new DeliverableService(JSON,storage,mock(ArtifactAccess.class),bindings,mock(StudioArtifactProjection.class),store,mock(AgentRuntimeGoalDriver.class),mapper,new ArtifactClosingBudget(),mock(InstitutionPlanHost.class),requirements,origins);
            var proxy=new ProxyFactory(real);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));service=(DeliverableService)proxy.getProxy();handler=new FinalizeTaskOutputsTool(service,JSON);
            var request=new Finalize(List.of(new Selection("report","正式报告","document",null,List.of(new Representation("source","md",true,null,List.of(new FileRef(artifactId,"report.md",fileId)),List.of())))),List.of(),null,null);
            finalization=JSON.convertValue(request,Map.class);
            coordinator=new ExecutionCoordinator<>(store,program,this::invoke,run->new ExecutionCoordinator.Control(){
                final ExecutionBudgetScope scope=ExecutionBudgetScope.open(new ExecutionBudgetScope.Binding(budgets,identity.runRef(),()->ExecutionBudgetPort.ClosingSpec.TEXT,()->Optional.of(Duration.ofSeconds(1)),()->Optional.of(new ExecutionBudgetScope.ClosingSizing(ExecutionBudgetPort.Category.BUSINESS_MODEL,100,100,Duration.ofSeconds(1))),()->Duration.ZERO));
                public boolean cancelled(){return cancelled.get();}public boolean ownershipLost(){return false;}public void close(){scope.close();}
            },mock(ExecutionCoordinator.LocalPlans.class));
        }

        ExecutionCoordinator.Outcome drive(){
            var outcome=coordinator.drive(new ExecutionStore.Claim(identity.runRef(),"typed-delivery-worker",UUID.randomUUID().toString(),Duration.ofSeconds(60)));
            assertNotEquals(RunStatus.FAILED,store.load(identity.runRef()).orElseThrow().status(),this::failureReason);
            return outcome;
        }
        private String failureReason() {
            try {
                var event=store.event(identity.runRef(),AgentExecutionProgram.finishedEventId(identity.runRef()));
                if(event.isEmpty())return "fixture_run_failed_without_terminal_event";
                String reason=JSON.readTree(event.orElseThrow().event().data().canonicalJson()).path("reason").asText();
                return reason.matches("[A-Za-z0-9_.:-]{1,160}")?"fixture_run_failed:"+reason:"fixture_run_failed_reason_unavailable";
            }catch(Exception unavailable){return "fixture_run_failed_reason_unavailable";}
        }
        /** 每次调用都必须先由真实Store签发SEND；确定Provider结果通过正式Core/Codec采用。 */
        ExecutionCoordinator.CapabilityResult invoke(ExecutionStore.DispatchResult.Registered registered,ExecutionStore.Prepared prepared) {
            assertTrue(store.authorizeSend(registered,prepared));
            try {
                if(prepared.kind()==ExecutionStore.Kind.MODEL) {
                    int call=models.incrementAndGet();
                    var turn=call==1?new AssistantTurn(List.of(new AssistantContent.ToolCall("provider-finalize:"+registered.attempt().id(),"finalize_outputs",JSON.writeValueAsString(finalization))))
                            :new AssistantTurn(List.of(new AssistantContent.Text("已正式冻结 report.md")));
                    var result=new ModelResult.Completed(new TypedPayload(ModelPort.TEXT_OUTPUT_SCHEMA,JSON.writeValueAsString(turn.content())),"fixture-model",
                            new ModelUsage(OptionalLong.of(3),OptionalLong.of(2)),call==1?"tool_calls":"stop",new ResultRef("model:"+registered.attempt().id()),Optional.empty(),Optional.of(turn));
                    return result(prepared,core.encodeOutcome(PortalCallKind.MODEL_GENERATE,result),Optional.empty());
                }
                finalizations.incrementAndGet();var request=codec.decodeTool(prepared.request().content());
                var context=new PlatformToolExecutionContext(new PortCallContext(identity,actor,new InvocationControl(Instant.now().plusSeconds(120),cancelled::get)),"finalize:"+identity.runRef().runId(),registered.attempt().id());
                var actual=handler.execute(finalization,context);assertEquals("finalizing",actual.get("state"),
                        ()->"正式finalize必须成功，当前拒绝码="+actual.get("error"));
                var outcome=new ToolOutcome.Succeeded(new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,JSON.writeValueAsString(Map.of("content",List.of(),"isError",false,"resultType","complete","structuredContent",actual))),new ResultRef("finalize-result"),List.of());
                var feedback=codec.safeToolFeedback(request,outcome);
                if(cancelAfterFreeze){store.cancel(new ExecutionStore.Cancel(actor,identity.runRef(),new InputId("cancel"),List.of()));cancelled.set(true);}
                return result(prepared,core.portalResults(payloads,payloads,payloads).encodeTool(PortalCallKind.TOOL_INVOKE,outcome),feedback);
            }catch(Exception failure){throw new IllegalStateException(failure);}
        }
        private ExecutionCoordinator.CapabilityResult result(ExecutionStore.Prepared prepared,PortalOutcomeSnapshot outcome,Optional<TypedPayload> feedback) {
            var material=new ExecutionStore.Material(new ResultRef("actual-result:"+prepared.action().actionId()),codec.encodeOutcome(outcome));
            return new ExecutionCoordinator.CapabilityResult(ExecutionStore.ResultKind.SUCCEEDED,material,feedback.map(value->new ExecutionStore.Material(new ResultRef("actual-feedback:"+prepared.action().actionId()),value)),Optional.empty(),ExecutionBudgetPort.Usage.reported(OptionalLong.of(3),OptionalLong.of(2)));
        }
        public AgentExecutionProgram.Configuration configuration(ExecutionStore.Run run,ResultRef input){assertEquals(identity,run.identity());return configuration;}
        public List<Optional<ExecutionStore.Material>> materials(ExecutionStore.Run run,List<ResultRef> references){return store.materials(run.identity().runRef().tenantId(),references);}
        public ExecutionStore.Material material(ExecutionStore.Run run,ResultRef reference){return store.material(run.identity().runRef().tenantId(),reference).orElseThrow();}
        public Duration elapsed(ExecutionStore.Run run){return Duration.ZERO;}
        public AgentExecutionTime.Sample sampleModelTime(ExecutionStore.Run run){return AgentExecutionTime.sample(java.time.Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),java.time.ZoneOffset.UTC));}
        public AgentExecutionModelMedia.Prepared prepareModelMessages(ExecutionStore.Run run,AgentExecutionProgram.Configuration configuration,List<ModelMessage> messages){return new AgentExecutionModelMedia.Prepared(messages,List.of());}
        public SessionTokenCounter.Measurement measure(AgentExecutionProgram.Configuration configuration,List<ModelMessage> messages, List<com.skillforge.runtime.api.port.ModelPort.ModelTool> tools){var profile=new SessionTokenCounter.Profile(configuration.profile().tokenizerProfileRef(),"1","fixture","fixture",configuration.target(),new SessionTokenCounter.RoleCosts(0,0,0,0),0,0,100,1_000_000);return new SessionTokenCounter.Measurement(profile,messages,Collections.nCopies(messages.size(),1L),messages.size(),0,0,tools,tools.isEmpty()?0:1);}
        public Optional<String> preparedResult(ExecutionStore.Run run){return service.preparedResult(identity);}
        public Optional<AgentExecutionOutputs.Policy> prepareOutput(ExecutionStore.Run run,AgentExecutionProgram.Configuration configuration){String response=service.actionRejection(identity,"respond"),execute=service.actionRejection(identity,"execute");return Optional.of(new AgentExecutionOutputs.Policy(Optional.of(new SessionOutputContract.PreparationHint("正式冻结后再答复","只报告真实成果","当前typed Run")),response==null?Optional.empty():Optional.of(new SessionOutputContract.ActionRejection(response,"先冻结成果")),execute==null?Optional.empty():Optional.of(new SessionOutputContract.ActionRejection(execute,"只能最终答复")),service.preparedResult(identity),new ExecutionBudgetPort.ClosingSpec(2,1,1,100,List.of("真实成果"),Set.of("tool:finalize")),false));}
    }
}
