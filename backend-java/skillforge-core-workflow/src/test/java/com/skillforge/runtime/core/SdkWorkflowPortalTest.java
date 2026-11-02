package com.skillforge.runtime.core;

import com.skillforge.core.workflow.execution.ControlledWorkflowExecution;
import com.skillforge.core.workflow.execution.StateMachineEngine;
import com.skillforge.core.workflow.execution.WorkflowParser;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.execution.ExecutionCommands;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.identity.WorkflowPolicyResource;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.adapter.jdbc.session.JdbcWorkflowPolicy;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.WorkflowPortal.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实typed Registered、当前IAM、不可变Workflow安装和Engine持久执行的代表性集成合同。 */
@Testcontainers(disabledWithoutDocker = false)
class SdkWorkflowPortalTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final TypedPayload INPUT = new TypedPayload("application/json", "{\"user_input\":\"核验已登记执行\"}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutionBudgetPort.Resources CAPACITY = new ExecutionBudgetPort.Resources(0, 0, 4, 0, 0, 0, 0);
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("typed_registered_workflow").withUsername("admin").withPassword("admin_password");
    private static PGSimpleDataSource source;
    private JdbcIdentityPolicyPort identities;
    private JdbcWorkflowBindings bindings;
    private JdbcWorkflowPolicy policy;
    private JdbcExecutionStore store;
    private JdbcExecutionLedgerPort ledger;
    private final Map<ActionRef, Expected> originals = new HashMap<>();
    private final Map<ResultRef, Material> continuationRequests = new HashMap<>();
    private final AtomicInteger nodes = new AtomicInteger();

    @BeforeAll static void schema() throws Exception {
        execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='skillforge') THEN CREATE ROLE skillforge NOLOGIN NOSUPERUSER NOBYPASSRLS; END IF; END $$");
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()).locations("classpath:db/migration").load().migrate();
        execute("INSERT INTO nw_billing_plans(id,plan_code,display_name,slo_availability_target) VALUES(800000000000000309,'typed-workflow-portal','Typed Workflow Portal',0.999)");
        execute("INSERT INTO nw_tenants(id,name,slug,plan_id,status) VALUES(1,'Workflow tenant','typed-workflow-portal',800000000000000309,'active')");
        execute("INSERT INTO nw_agents(id,agent_key,tenant_id,name,status) VALUES(10,'typed-workflow-agent',1,'Workflow Agent','published')");
        execute("INSERT INTO nw_end_users(id,tenant_id,status) VALUES(1,1,'active')");
        install(0, false); install(1, true);
        execute("CREATE ROLE workflow_registered LOGIN PASSWORD 'workflow_registered_test_password' NOSUPERUSER NOBYPASSRLS");
        execute("GRANT USAGE ON SCHEMA public TO workflow_registered");
        execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA public TO workflow_registered");
        // 取消父Run会在同一受限事务触发容量释放；只补齐该单表DELETE，继续受当前租户RLS约束。
        execute("GRANT DELETE ON public.agent_runtime_dag_permits TO workflow_registered");
        execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO workflow_registered");
        execute("GRANT EXECUTE ON FUNCTION resolve_runtime_authentication_tenant(text) TO workflow_registered");
        source = new PGSimpleDataSource(); source.setURL(DATABASE.getJdbcUrl()); source.setUser("workflow_registered"); source.setPassword("workflow_registered_test_password");
    }
    @BeforeEach void prepare() {
        identities = new JdbcIdentityPolicyPort(source, Map.of(new PrincipalRef.SystemPrincipal(TENANT, "workflow-worker"), Set.of("sessions.write", "sessions.read")));
        bindings = new JdbcWorkflowBindings(source); policy = new JdbcWorkflowPolicy(source, identities); ledger = new JdbcExecutionLedgerPort(source);
        store = new JdbcExecutionStore(source, identities, (session, run, actor, prepared) -> {
            var expected = originals.get(prepared.action());
            if (expected == null || !expected.run().equals(run.identity()) || !(expected.request().equals(prepared.request())
                    || prepared.request().equals(continuationRequests.get(prepared.request().reference())))) return false;
            var resource = WorkflowPolicyResource.dispatchResource(run.identity(), expected.snapshot(), expected.target(), expected.execution(),
                    INPUT, List.of(), prepared.action(), prepared.request().reference(), prepared.fingerprint());
            return identities.authorize(session, new IdentityPolicyPort.AuthorizationRequest(actor, "workflow.dispatch", resource,
                    run.identity().trace().traceId(), run.identity().trace().spanId())) instanceof IdentityPolicyPort.AuthorizationResult.Allowed;
        });
    }

    @Test void realRegisteredDispatchCompletesOnceAndRebuiltPortalReadsOriginalTerminal() throws Exception {
        var f = fixture("completed", false); var portal = portal();
        var done = assertInstanceOf(WorkflowResult.Completed.class, portal.dispatchRegistered(f.registered(), f.prepared(), f.request()));
        assertEquals("skillforge.runtime.workflow.engine-result.v1", done.output().schemaId());
        assertEquals(JSON.valueToTree(Map.of("messages", List.of(Map.of("role", "assistant", "content", "registered-ready")))),
                JSON.readTree(done.output().canonicalJson()), "结果只能包含冻结白名单显式导出的实际消息");
        assertEquals(3, nodes.get());
        var saved = record(f); assertEquals(WorkflowExecutionState.COMPLETED, saved.state());
        var originalAttempt = store.attempt(f.registered().run().identity().runRef(), f.registered().attempt().id()).orElseThrow();
        assertEquals(done, portal().dispatchRegistered(f.registered(), f.prepared(), f.request()));
        assertEquals(saved, record(f)); assertEquals(3, nodes.get());
        assertEquals(originalAttempt, store.attempt(f.registered().run().identity().runRef(), f.registered().attempt().id()).orElseThrow());
        assertEquals(WorkflowExecutionState.COMPLETED, portal().query(new WorkflowQuery(f.identity(), f.request().executionRef())).orElseThrow().state());
        assertEquals(3, nodes.get());
        assertInstanceOf(DispatchResult.Existing.class, store.dispatch(f.dispatch()));
        assertEquals(1, store.actions(f.registered().run().identity().runRef(), 0, 10).size());
        // 原终态可读不等于获得新的发送权；当前撤权后原Registered也不能再走重复派发入口。
        identities.revoke(f.authentication());
        assertThrows(IllegalArgumentException.class, () -> portal().dispatchRegistered(f.registered(), f.prepared(), f.request()));
        assertEquals(saved, record(f)); assertEquals(3, nodes.get());
        assertEquals(originalAttempt, store.attempt(f.registered().run().identity().runRef(), f.registered().attempt().id()).orElseThrow());
    }

    @Test void substitutedAttemptRequestOrFingerprintCannotReachEngineOrCreateExecution() {
        var f = fixture("substitution", false);
        for (var changed : List.of(new ProviderDispatch(f.dispatch().run(), f.prepared().action(), "invocation", "wrong-attempt", PortalCallKind.WORKFLOW_DISPATCH,
                        f.prepared().request().reference(), f.prepared().capability(), f.prepared().fingerprint(), f.registered().run().fence()),
                new ProviderDispatch(f.dispatch().run(), f.prepared().action(), "invocation", f.registered().attempt().id(), PortalCallKind.WORKFLOW_DISPATCH,
                        new ResultRef("wrong-request"), f.prepared().capability(), f.prepared().fingerprint(), f.registered().run().fence()),
                new ProviderDispatch(f.dispatch().run(), f.prepared().action(), "invocation", f.registered().attempt().id(), PortalCallKind.WORKFLOW_DISPATCH,
                        f.prepared().request().reference(), f.prepared().capability(), "b".repeat(64), f.registered().run().fence()))) {
            var context = ProviderCallContext.dispatched(f.request().context().callContext(), changed);
            var substituted = new WorkflowDispatch(context, f.request().snapshot(), f.request().target(), f.request().executionRef(), INPUT, List.of(), f.request().idempotencyKey());
            assertThrows(IllegalArgumentException.class, () -> portal().dispatchRegistered(f.registered(), f.prepared(), substituted));
        }
        assertTrue(ledger.find(new ExecutionLedgerPort.ExecutionLookup(f.identity(), f.request().executionRef())).isEmpty());
        assertEquals(0, nodes.get());
    }

    @Test void currentAuthenticationRevocationAfterRegistrationPreventsExecutionCreation() {
        var f = fixture("revoked", false); identities.revoke(f.authentication());
        assertThrows(IllegalArgumentException.class, () -> portal().dispatchRegistered(f.registered(), f.prepared(), f.request()));
        assertTrue(ledger.find(new ExecutionLedgerPort.ExecutionLookup(f.identity(), f.request().executionRef())).isEmpty());
        assertEquals(0, nodes.get());
    }

    @Test void committedParentCancellationClosesOriginalHumanWaitWithoutExecutingNextNodeOrNewAttempt() {
        var f = fixture("parent-cancel", true);
        assertInstanceOf(WorkflowResult.Suspended.class, portal().dispatchRegistered(f.registered(), f.prepared(), f.request()));
        assertEquals(WorkflowExecutionState.WAITING_HITL, record(f).state());
        int executed = nodes.get();
        var parent = store.cancel(new Cancel(f.identity(), f.registered().run().identity().runRef(), new InputId("cancel-parent"), List.of()));
        assertEquals(RunStatus.CANCELLED, parent.status());
        assertTrue(portal().cancelCommittedParent(f.identity(), f.request().executionRef(), new InputId("cancel-workflow"), f.request().context().callContext().control()));
        assertEquals(WorkflowExecutionState.CANCELLED, record(f).state()); assertEquals(executed, nodes.get());
        assertTrue(portal().cancelCommittedParent(f.identity(), f.request().executionRef(), new InputId("cancel-workflow-repeat"), f.request().context().callContext().control()));
        assertEquals(executed, nodes.get()); assertEquals(1, store.actions(parent.identity().runRef(), 0, 10).size());
        assertTrue(store.attempt(parent.identity().runRef(), f.registered().attempt().id()).orElseThrow().result().isEmpty());
        assertFalse(store.authorizeSend(f.registered(), f.prepared()));
    }

    @Test void competingAuthorizedActorsWithSameCancellationKeyLeaveOnePersistedIntent() throws Exception {
        var f=fixture("competing-cancel-actors",true);
        assertInstanceOf(WorkflowResult.Suspended.class,portal().dispatchRegistered(f.registered(),f.prepared(),f.request()));
        int executed=nodes.get();
        var parent=store.cancel(new Cancel(f.identity(),f.registered().run().identity().runRef(),new InputId("cancel-competing-parent"),List.of()));
        var authentication=identities.registerTrusted(new PrincipalRef.SystemPrincipal(TENANT,"workflow-worker"),
                parent.identity().owner(),"worker","queue",Duration.ofMinutes(5));
        var second=assertInstanceOf(IdentityPolicyPort.IdentityResult.Verified.class,identities.verify(
                new IdentityPolicyPort.IdentityRequest(authentication,parent.identity().owner(),"worker","queue",
                        new RequestId("verify-competing-cancel"),Optional.empty(),"trace:competing-cancel","span:competing-cancel"))).identity();
        assertNotEquals(f.identity().verificationRef(),second.verificationRef());
        var original=record(f);var wait=original.waiting().orElseThrow();
        var resource=WorkflowPolicyResource.cancelResource(parent.identity(),f.request().snapshot(),f.request().target(),
                f.request().executionRef(),wait.actionKey(),original.executionRevision(),"parent_cancelled",f.prepared().action(),
                f.prepared().request().reference(),f.prepared().fingerprint());
        assertTrue(policy.authorizeCommittedCancel(f.identity(),parent,resource));
        assertTrue(policy.authorizeCommittedCancel(second,parent,resource));
        var input=new InputId("workflow-parent-cancel:"+parent.identity().runRef().runId()+":"+f.prepared().action().actionId());
        var event=new EventId(input.value());var at=Instant.now();
        var firstCommand=ExecutionCommands.cancel(original,f.identity(),input,event,at);
        var secondCommand=ExecutionCommands.cancel(original,second,input,event,at);
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return ledger.requestCancel(firstCommand);});
            var competing=executor.submit(()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));return ledger.requestCancel(secondCommand);});
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            var a=first.get(10,TimeUnit.SECONDS);var b=competing.get(10,TimeUnit.SECONDS);
            assertTrue(a instanceof ExecutionLedgerPort.ExecutionCancelResult.Applied ^ b instanceof ExecutionLedgerPort.ExecutionCancelResult.Applied);
            var rejected=assertInstanceOf(ExecutionLedgerPort.ExecutionCancelResult.Rejected.class,
                    a instanceof ExecutionLedgerPort.ExecutionCancelResult.Rejected?a:b);
            assertEquals("execution_mutation_conflict",rejected.failure().reasonCode());
        }
        assertEquals(Optional.of(input),record(f).cancelRequestId());
        assertTrue(portal().cancelCommittedParent(f.identity(),f.request().executionRef(),input,
                new InvocationControl(Instant.now().plusSeconds(5),()->false)));
        assertEquals(WorkflowExecutionState.CANCELLED,record(f).state());assertEquals(executed,nodes.get());
    }

    @Test void inFlightDispatchReturnsCommittedCancellationWithoutExecutingANode() {
        var f=fixture("dispatch-cancel",false);var cancelled=new AtomicBoolean();
        var request=withControl(f,new InvocationControl(Instant.now().plusSeconds(60),cancelled::get));
        var portal=portal(()->{
            store.cancel(new Cancel(f.identity(),f.registered().run().identity().runRef(),new InputId("cancel-parent-dispatch"),List.of()));
            cancelled.set(true);
        },true);
        var result=assertInstanceOf(WorkflowResult.Cancelled.class,portal.dispatchRegistered(f.registered(),f.prepared(),request));
        assertEquals(request.executionRef(),result.executionRef());
        assertEquals(WorkflowExecutionState.CANCELLED,record(f).state());
        assertTrue(record(f).lease().isEmpty());assertEquals(0,nodes.get());
        assertFalse(store.authorizeSend(f.registered(),f.prepared()));
        assertEquals(1,store.actions(f.registered().run().identity().runRef(),0,10).size());
    }

    @Test void inFlightDispatchKeepsUnknownWhenCleanupCannotConverge() {
        var f=fixture("dispatch-cancel-unknown",false);var cancelled=new AtomicBoolean();
        var request=withControl(f,new InvocationControl(Instant.now().plusSeconds(60),cancelled::get));
        var portal=portal(()->{
            store.cancel(new Cancel(f.identity(),f.registered().run().identity().runRef(),new InputId("cancel-parent-unknown"),List.of()));
            cancelled.set(true);
        },false);
        assertInstanceOf(WorkflowResult.OutcomeUnknown.class,portal.dispatchRegistered(f.registered(),f.prepared(),request));
        assertNotEquals(WorkflowExecutionState.CANCELLED,record(f).state());
        assertTrue(record(f).cancelRequestId().isPresent());assertEquals(0,nodes.get());
    }

    @Test void inFlightDispatchObservesConcurrentCleanupWithoutRepeatingCancellation() throws Exception {
        var f=fixture("dispatch-cancel-observed",false);var cancelled=new AtomicBoolean();
        var firstCleanup=new CountDownLatch(1);
        var request=withControl(f,new InvocationControl(Instant.now().plusSeconds(60),cancelled::get));
        var portal=portal(()->{
            store.cancel(new Cancel(f.identity(),f.registered().run().identity().runRef(),new InputId("cancel-parent-observed"),List.of()));
            cancelled.set(true);
        },false,firstCleanup::countDown);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var other=executor.submit(()->{
                assertTrue(firstCleanup.await(5,TimeUnit.SECONDS));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(record(f).lease().isPresent()&&System.nanoTime()-until<0)Thread.sleep(10);
                assertTrue(portal().cancelCommittedParent(f.identity(),request.executionRef(),new InputId("other-cleanup"),
                        new InvocationControl(Instant.now().plusSeconds(5),()->false)));
                return null;
            });
            assertInstanceOf(WorkflowResult.Cancelled.class,portal.dispatchRegistered(f.registered(),f.prepared(),request));
            other.get(5,TimeUnit.SECONDS);
        }
        assertEquals(WorkflowExecutionState.CANCELLED,record(f).state());assertEquals(0,nodes.get());
        assertEquals(1,store.actions(f.registered().run().identity().runRef(),0,10).size());
    }

    @Test void revokedDuringObservationKeepsResultUnknown() {
        var f=fixture("dispatch-observation-revoked",false);var cancelled=new AtomicBoolean();
        var request=withControl(f,new InvocationControl(Instant.now().plusSeconds(60),cancelled::get));
        var portal=portal(()->{
            store.cancel(new Cancel(f.identity(),f.registered().run().identity().runRef(),new InputId("cancel-parent-observation-revoked"),List.of()));
            cancelled.set(true);
        },false,()->identities.revoke(f.authentication()));
        assertInstanceOf(WorkflowResult.OutcomeUnknown.class,portal.dispatchRegistered(f.registered(),f.prepared(),request));
        assertEquals(0,nodes.get());
    }

    @Test void interruptedObservationKeepsResultUnknownAndInterruptStatus() {
        var f=fixture("dispatch-observation-interrupted",false);var cancelled=new AtomicBoolean();
        var request=withControl(f,new InvocationControl(Instant.now().plusSeconds(60),cancelled::get));
        var portal=portal(()->{
            store.cancel(new Cancel(f.identity(),f.registered().run().identity().runRef(),new InputId("cancel-parent-observation-interrupted"),List.of()));
            cancelled.set(true);
        },false,()->Thread.currentThread().interrupt());
        try {
            assertInstanceOf(WorkflowResult.OutcomeUnknown.class,portal.dispatchRegistered(f.registered(),f.prepared(),request));
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,nodes.get());
        } finally { Thread.interrupted(); }
    }

    @Test void cancellationSignalWithoutCommittedParentDoesNotImpersonateCancellation() {
        var f=fixture("dispatch-signal-only",false);var cancelled=new AtomicBoolean();
        var request=withControl(f,new InvocationControl(Instant.now().plusSeconds(60),cancelled::get));
        assertThrows(RuntimeException.class,()->portal(()->cancelled.set(true),true).dispatchRegistered(f.registered(),f.prepared(),request));
        assertFalse(store.load(f.registered().run().identity().runRef()).orElseThrow().cancelled());
        assertTrue(record(f).cancelRequestId().isEmpty());assertEquals(0,nodes.get());
    }

    @Test void revokedActorCannotUseCancellationCleanupToBypassAuthorization() {
        var f=fixture("dispatch-cancel-revoked",false);var cancelled=new AtomicBoolean();
        var request=withControl(f,new InvocationControl(Instant.now().plusSeconds(60),cancelled::get));
        var portal=portal(()->{
            store.cancel(new Cancel(f.identity(),f.registered().run().identity().runRef(),new InputId("cancel-parent-revoked"),List.of()));
            identities.revoke(f.authentication());cancelled.set(true);
        },true);
        assertInstanceOf(WorkflowResult.OutcomeUnknown.class,portal.dispatchRegistered(f.registered(),f.prepared(),request));
        assertEquals(0,nodes.get());
    }

    @Test void expiredDeadlineDoesNotBecomeCommittedCancellation() {
        var f=fixture("dispatch-expired",false);
        var request=withControl(f,new InvocationControl(Instant.now().minusSeconds(1),()->false));
        assertThrows(IllegalStateException.class,()->portal().dispatchRegistered(f.registered(),f.prepared(),request));
        assertFalse(store.load(f.registered().run().identity().runRef()).orElseThrow().cancelled());
        assertTrue(record(f).cancelRequestId().isEmpty());assertEquals(0,nodes.get());
    }

    @Test void acceptedContinuationReturnsCommittedCancellationWithoutRunningNextNode() {
        var f=fixture("resume-cancel",true);
        var continuation=continuation(f);
        int executed=nodes.get();var cancelled=new AtomicBoolean();
        var request=withControl(continuation.request(),new InvocationControl(Instant.now().plusSeconds(60),cancelled::get));
        var portal=portal(()->{
            store.cancel(new Cancel(f.identity(),f.registered().run().identity().runRef(),new InputId("cancel-parent-resume"),List.of()));
            cancelled.set(true);
        },true);
        assertInstanceOf(WorkflowResult.Cancelled.class,portal.resumeRegistered(continuation.registered(),continuation.prepared(),request));
        assertEquals(WorkflowExecutionState.CANCELLED,record(f).state());
        assertTrue(record(f).lease().isEmpty());assertEquals(executed,nodes.get());
        assertEquals(Optional.of(continuation.registered().attempt().id()),store.waiting(f.dispatch().run(),continuation.waiting()).orElseThrow().continuationAttempt());
    }

    @Test void acceptedContinuationKeepsUnknownWhenOriginalEffectsCannotBeCancelled() {
        var f=fixture("resume-cancel-unknown",true);var continuation=continuation(f);
        int executed=nodes.get();var cancelled=new AtomicBoolean();
        var request=withControl(continuation.request(),new InvocationControl(Instant.now().plusSeconds(60),cancelled::get));
        var portal=portal(()->{
            store.cancel(new Cancel(f.identity(),f.registered().run().identity().runRef(),new InputId("cancel-parent-resume-unknown"),List.of()));
            cancelled.set(true);
        },false);
        assertInstanceOf(WorkflowResult.OutcomeUnknown.class,portal.resumeRegistered(continuation.registered(),continuation.prepared(),request));
        assertNotEquals(WorkflowExecutionState.CANCELLED,record(f).state());assertEquals(executed,nodes.get());
    }

    @Test void continuationResponseMismatchCannotBeReportedAsConfirmedCancellation() {
        var f=fixture("resume-response-mismatch",true);var continuation=continuation(f);
        int executed=nodes.get();var cancelled=new AtomicBoolean();
        var original=withControl(continuation.request(),new InvocationControl(Instant.now().plusSeconds(60),cancelled::get));
        var substituted=new WorkflowResume(original.context(),original.snapshot(),original.target(),original.executionRef(),original.suspendRef(),
                original.actionKey(),new TypedPayload("application/json","{\"confirmed\":false}"),original.expectedRevision(),original.idempotencyKey());
        var portal=portal(()->{
            store.cancel(new Cancel(f.identity(),f.registered().run().identity().runRef(),new InputId("cancel-parent-resume-mismatch"),List.of()));
            cancelled.set(true);
        },true);
        assertInstanceOf(WorkflowResult.OutcomeUnknown.class,portal.resumeRegistered(continuation.registered(),continuation.prepared(),substituted));
        assertEquals(executed,nodes.get());
    }

    @Test void continuationCancellationSignalWithoutCommittedParentCannotImpersonateCancellation() {
        var f=fixture("resume-signal-only",true);var continuation=continuation(f);
        int executed=nodes.get();var cancelled=new AtomicBoolean();
        var request=withControl(continuation.request(),new InvocationControl(Instant.now().plusSeconds(60),cancelled::get));
        assertThrows(IllegalStateException.class,()->portal(()->cancelled.set(true),true).resumeRegistered(continuation.registered(),continuation.prepared(),request));
        assertFalse(store.load(f.dispatch().run()).orElseThrow().cancelled());
        assertTrue(record(f).lease().isEmpty());assertEquals(executed,nodes.get());
    }

    private record Continuation(DispatchResult.Registered registered,Prepared prepared,WaitRef waiting,WorkflowResume request) { }
    private Continuation continuation(Fixture f) {
        var suspended=assertInstanceOf(WorkflowResult.Suspended.class,portal().dispatchRegistered(f.registered(),f.prepared(),f.request()));
        var original=record(f);var wait=original.waiting().orElseThrow();
        var waitRef=new WaitRef(f.prepared().action(),1);var context=material("resume-context:"+f.dispatch().run().runId());
        var waiting=store.complete(new Completion(f.dispatch().run(),f.registered().attempt().id(),ResultKind.SUCCEEDED,
                material("resume-suspended:"+f.dispatch().run().runId()),Optional.empty(),Optional.empty(),ExecutionBudgetPort.Usage.unreported(),
                f.registered().run().revision(),f.registered().run().lease().orElseThrow(),new Progress(0,0,0,context.reference()),context,List.of(),
                Optional.of(new WaitDeclaration(waitRef,WaitKind.WORKFLOW,Map.of(),Optional.of(new WorkflowWait(f.request().executionRef(),
                        suspended.suspendRef(),wait.actionKey(),suspended.revision())))))).run();
        var response=new Material(new ResultRef("resume-response:"+f.dispatch().run().runId()),new TypedPayload("application/json","{\"confirmed\":true}"));
        store.acceptDependency(new Resume(f.identity(),f.dispatch().run(),waitRef,waiting.revision(),new InputId("resume-input:"+f.dispatch().run().runId()),
                response,"accepted-response",Map.of(),List.of()),(session,parent,exact,children,value)->{});
        var claimed=store.claim(new Claim(f.dispatch().run(),"resume-worker","resume-claim:"+f.dispatch().run().runId(),Duration.ofMinutes(5))).orElseThrow();
        var material=material("resume-request:"+f.dispatch().run().runId());
        var fingerprint=digest(material.content().schemaId()+"\0"+material.content().canonicalJson());
        var prepared=new Prepared(f.prepared().action(),f.prepared().ordinal(),Kind.WORKFLOW,f.prepared().target(),f.prepared().capability(),material,fingerprint);
        continuationRequests.put(material.reference(),material);
        var attempt="resume-attempt:"+f.dispatch().run().runId();
        var demand=new ExecutionBudgetPort.Demand(f.dispatch().run(),attempt,fingerprint,ExecutionBudgetPort.Category.TOOL,
                ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL,0,0),false);
        var registered=assertInstanceOf(DispatchResult.Registered.class,store.dispatch(new Dispatch(f.dispatch().run(),claimed.revision(),claimed.lease().orElseThrow(),
                prepared,attempt,demand,f.identity(),Optional.of(waitRef),List.of())));
        var dispatch=new ProviderDispatch(f.dispatch().run(),prepared.action(),"resume-invocation",attempt,PortalCallKind.WORKFLOW_RESUME,
                material.reference(),prepared.capability(),fingerprint,registered.run().fence());
        var call=ProviderCallContext.dispatched(new PortCallContext(registered.run().identity(),f.identity(),new InvocationControl(Instant.now().plusSeconds(60),()->false)),dispatch);
        return new Continuation(registered,prepared,waitRef,new WorkflowResume(call,f.request().snapshot(),f.request().target(),f.request().executionRef(),
                suspended.suspendRef(),wait.actionKey(),response.content(),suspended.revision(),new IdempotencyKey("resume:"+f.dispatch().run().runId())));
    }

    private WorkflowResume withControl(WorkflowResume original,InvocationControl control) {
        var call=original.context().callContext();
        var context=ProviderCallContext.dispatched(new PortCallContext(call.runIdentity(),call.identity(),control),original.context().dispatch());
        return new WorkflowResume(context,original.snapshot(),original.target(),original.executionRef(),original.suspendRef(),original.actionKey(),
                original.response(),original.expectedRevision(),original.idempotencyKey());
    }

    private WorkflowDispatch withControl(Fixture fixture,InvocationControl control) {
        var original=fixture.request();var call=original.context().callContext();
        var context=ProviderCallContext.dispatched(new PortCallContext(call.runIdentity(),call.identity(),control),original.context().dispatch());
        return new WorkflowDispatch(context,original.snapshot(),original.target(),original.executionRef(),original.parameters(),original.evidenceRefs(),original.idempotencyKey());
    }

    private Fixture fixture(String label, boolean human) {
        var owner = SessionOwner.user(new SessionRef(TENANT, "session:" + label), new AgentId("10"), new EndUserId(1));
        var authentication = identities.registerTrusted(new PrincipalRef.SystemPrincipal(TENANT, "workflow-worker"), owner, "worker", "queue", Duration.ofMinutes(5));
        var identity = assertInstanceOf(IdentityPolicyPort.IdentityResult.Verified.class, identities.verify(new IdentityPolicyPort.IdentityRequest(authentication,
                owner, "worker", "queue", new RequestId("verify:" + label), Optional.empty(), "trace:" + label, "span:" + label))).identity();
        var input = new InputId("input:" + label); var runRef = new RunRef(TENANT, "run:" + label);
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, runRef, new TraceEnvelope("trace:" + label, "span:" + label, input, Optional.empty()), identity.verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        // 与生产入口相同，先由正式SessionLog建立owner，不补造旧Run或绕过Execution外键。
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class, new JdbcSessionLogPort(source).append(
                new SessionLogPort.AppendSessionEvent(new SessionProjectionTarget(owner, input,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                        new SessionEventDraft(new EventId("session-opened:" + label), "session.opened", "1", "session-opened:" + label,
                                run.trace(), new TypedPayload("test.session.v1", "{}"), Instant.now()))));
        var limits = new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD, CAPACITY, CAPACITY, CAPACITY, 3600, 3600, "sdk-registered:v2");
        store.admit(new Admission(identity, run, "input:" + label, new ResultRef("profile:" + label), material("input:" + label), material("context:" + label),
                new ExecutionBudgetPort.Registration(runRef, runRef, ExecutionBudgetPort.Role.PRIMARY,
                        new ExecutionBudgetPort.Policy.Enforced(limits)), List.of(), List.of()));
        var claimed = store.claim(new Claim(runRef, "sdk-test-worker", "claim:" + label, Duration.ofMinutes(5))).orElseThrow();
        int offset = human ? 1 : 0;
        var target = new FrozenWorkflowTarget(new WorkflowHandle("workflow:" + offset), Integer.toString(200 + offset), 1L + offset,
                new CapabilityRef(CapabilityType.WORKFLOW, Integer.toString(50 + offset), Integer.toString(100 + offset)), Duration.ofSeconds(30), 1L);
        bindings.publish(TENANT, target);
        var snapshot = new CapabilitySnapshotRef(owner.sessionRef(), input, "snapshot:" + label, 1);
        var execution = new ExecutionRef(TENANT, "execution:" + label);
        var requestMaterial = new Material(new ResultRef("workflow-request:" + label), new TypedPayload("test.workflow.request.v1", json(Map.of(
                "workflow", target.instanceId(), "version", target.instanceRevision(), "input", INPUT.canonicalJson(), "snapshot", snapshot.snapshotId(), "execution", execution.executionId()))));
        var prepared = new Prepared(new ActionRef(TENANT, "workflow-action:" + label), 1, Kind.WORKFLOW, target.handle().value(),
                new ResultRef("workflow-capability:" + label), requestMaterial, digest(requestMaterial.content().schemaId() + "\0" + requestMaterial.content().canonicalJson()));
        originals.put(prepared.action(), new Expected(run, target, snapshot, execution, requestMaterial));
        var demand = new ExecutionBudgetPort.Demand(runRef, "attempt:" + label, prepared.fingerprint(), ExecutionBudgetPort.Category.TOOL,
                ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false);
        var dispatch = new Dispatch(runRef, claimed.revision(), claimed.lease().orElseThrow(), prepared, demand.attemptId(), demand, identity, Optional.empty(), List.of());
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(dispatch));
        var direct = new ProviderDispatch(runRef, prepared.action(), "invocation:" + label, registered.attempt().id(), PortalCallKind.WORKFLOW_DISPATCH,
                prepared.request().reference(), prepared.capability(), prepared.fingerprint(), registered.run().fence());
        var context = ProviderCallContext.dispatched(new PortCallContext(run, identity, new InvocationControl(Instant.now().plusSeconds(60), () -> false)), direct);
        return new Fixture(authentication, identity, registered, prepared, dispatch, new WorkflowDispatch(context, snapshot, target, execution, INPUT, List.of(), new IdempotencyKey("workflow:" + label)));
    }

    private SdkWorkflowPortal portal() { return portal(()->{},true); }
    private SdkWorkflowPortal portal(Runnable beforeEngine,boolean cleanupConverges) { return portal(beforeEngine,cleanupConverges,()->{}); }
    private SdkWorkflowPortal portal(Runnable beforeEngine,boolean cleanupConverges,Runnable onCancel) {
        var engine = new StateMachineEngine() {
            @Override public Map<String,Object> runControlled(ControlledWorkflowExecution.Request request, ControlledWorkflowExecution.Hooks hooks) {
                beforeEngine.run();
                return super.runControlled(request, new ControlledWorkflowExecution.Hooks() {
                    public void beforeNode(String id) { hooks.beforeNode(id); nodes.incrementAndGet(); }
                    public com.skillforge.core.workflow.execution.node.NodeOutcome executeExternalNode(com.skillforge.core.workflow.execution.node.NodeExecutionContext node) { return hooks.executeExternalNode(node); }
                    public void checkpoint(ControlledWorkflowExecution.Checkpoint value) { hooks.checkpoint(value); }
                });
            }
        };
        return new SdkWorkflowPortal(source, bindings, policy, engine, new WorkflowParser(),
                new SdkWorkflowPortal.ExternalNodes() {
                    @Override public com.skillforge.core.workflow.execution.node.NodeOutcome execute(VerifiedIdentity identity,ExecutionLedgerPort.ExecutionLease lease,
                            ExecutionLedgerPort.ExecutionRecord execution,InvocationControl control,SdkWorkflowPortal.NodeInputs inputs,
                            com.skillforge.core.workflow.execution.node.NodeExecutionContext node) { throw new IllegalStateException("external_node_not_expected"); }
                    @Override public boolean cancel(VerifiedIdentity identity,ExecutionLedgerPort.ExecutionLease lease,
                            ExecutionLedgerPort.ExecutionRecord execution,InvocationControl control) { onCancel.run();return cleanupConverges; }
                },
                call -> INPUT.equals(call.parameters()), call -> "application/json".equals(call.response().schemaId()),
                (binding, output) -> "skillforge.runtime.workflow.engine-result.v1".equals(output.schemaId()), Clock.systemUTC(), store,
                (session, execution) -> {
                    var action = store.action(execution.causativeActionRef().orElseThrow()).orElseThrow();
                    var expected = Objects.requireNonNull(originals.get(action.reference()), "original workflow source");
                    var parent = store.load(action.run()).orElseThrow(); var original = store.material(TENANT, action.request()).orElseThrow();
                    if (!parent.identity().equals(execution.owner()) || !expected.run().equals(execution.owner()) || !expected.execution().equals(execution.executionRef())
                            || action.kind() != Kind.WORKFLOW || !original.equals(expected.request()) || !action.fingerprint().equals(digest(original.content().schemaId() + "\0" + original.content().canonicalJson())))
                        throw new IllegalArgumentException("workflow_original_registered_source_rejected");
                    SdkWorkflowPortal.requireInitialSource(execution, expected.target(), expected.snapshot(), INPUT, List.of());
                });
    }
    private ExecutionLedgerPort.ExecutionRecord record(Fixture f) { return ledger.find(new ExecutionLedgerPort.ExecutionLookup(f.identity(), f.request().executionRef())).orElseThrow(); }
    private static Material material(String id) { return new Material(new ResultRef(id), new TypedPayload("application/json", "{}")); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static String json(Map<String,?> value) {
        try { return JSON.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value); }
        catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }
    private static void install(int offset, boolean human) throws Exception {
        String definition = human
                ? "{\"entry\":\"start\",\"nodes\":[{\"id\":\"start\",\"type\":\"start\"},{\"id\":\"human\",\"type\":\"human_input\",\"prompt\":\"请确认\",\"result_to\":\"state.confirmation\"},{\"id\":\"done\",\"type\":\"output\",\"message\":\"must-not-run-after-cancel\"}],\"edges\":[{\"from\":\"start\",\"to\":\"human\"},{\"from\":\"human\",\"to\":\"done\"}]}"
                : "{\"entry\":\"start\",\"nodes\":[{\"id\":\"start\",\"type\":\"start\"},{\"id\":\"done\",\"type\":\"output\",\"message\":\"registered-ready\"},{\"id\":\"end\",\"type\":\"end\"}],\"edges\":[{\"from\":\"start\",\"to\":\"done\"},{\"from\":\"done\",\"to\":\"end\"}]}";
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            try (var statement = connection.prepareStatement("INSERT INTO nw_capability_definitions(id,tenant_id,capability_type,capability_key,name,trust_level,status,created_at,updated_at) VALUES(?,1,'WORKFLOW',?,'Workflow','TENANT','PUBLISHED',now(),now())")) {
                statement.setLong(1,50 + offset); statement.setString(2,"typed-workflow-" + offset); statement.executeUpdate();
            }
            // 本夹具验证实际消息结果与终态重放，须显式声明messages；空白名单按契约只能导出{}。
            try (var statement = connection.prepareStatement("INSERT INTO nw_capability_versions(id,tenant_id,definition_id,capability_type,status,descriptor,content_hash,version_key,name,trust_level,created_at,published_at) VALUES(?,1,?,'WORKFLOW','PUBLISHED','{\"output_schema\":{},\"export_fields\":[\"messages\"]}',?,'v1','Workflow','TENANT',now(),now())")) {
                statement.setLong(1,100 + offset); statement.setLong(2,50 + offset); statement.setString(3,"typed-workflow-hash-" + offset); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("UPDATE nw_capability_definitions SET default_version_id=? WHERE id=?")) {
                statement.setLong(1,100 + offset); statement.setLong(2,50 + offset); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO nw_workflow_containers(id,tenant_id,workflow_id) VALUES(?,1,?)")) {
                statement.setLong(1,300 + offset); statement.setString(2,"typed-workflow-" + offset); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO nw_workflow_draft_revisions(id,container_id,tenant_id,revision,definition) VALUES(?,?,1,1,?::jsonb)")) {
                statement.setLong(1,400 + offset); statement.setLong(2,300 + offset); statement.setString(3,definition); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO nw_workflow_definition_versions(id,tenant_id,container_id,definition_version,source_draft_id,capability_version_id,definition) VALUES(?,1,?,1,?,?,?::jsonb)")) {
                statement.setLong(1,1 + offset); statement.setLong(2,300 + offset); statement.setLong(3,400 + offset); statement.setLong(4,100 + offset); statement.setString(5,definition); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO nw_workflow_instances(id,tenant_id,instance_id,definition_version_id,status) VALUES(?,1,?,?,'draft')")) {
                statement.setLong(1,200 + offset); statement.setString(2,"typed-instance-" + offset); statement.setLong(3,1 + offset); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO nw_workflow_installation_revisions(tenant_id,instance_id,installation_revision,definition_version_id,config,custom_prompt,bindings,created_at) VALUES(1,?,1,?,'{}','','{}',clock_timestamp())")) {
                statement.setLong(1,200 + offset); statement.setLong(2,1 + offset); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("UPDATE nw_workflow_instances SET installation_revision=1,status='published' WHERE id=?")) { statement.setLong(1,200 + offset); statement.executeUpdate(); }
        }
    }
    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private record Expected(RunIdentity run, FrozenWorkflowTarget target, CapabilitySnapshotRef snapshot, ExecutionRef execution, Material request) { }
    private record Fixture(AuthenticationRef authentication, VerifiedIdentity identity, DispatchResult.Registered registered, Prepared prepared,
                           Dispatch dispatch, WorkflowDispatch request) { }
}
