package com.skillforge.institution.team;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.institution.runtime.AgentRunBindings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.core.agent.model.AgentDelegationConfig;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.InboundEnvelope;
import com.skillforge.runtime.api.InputReceipt;
import com.skillforge.runtime.api.RunTarget;
import com.skillforge.institution.runtime.TeamRuntimeBridge;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.SessionDelegationEngine;
import com.skillforge.runtime.session.spi.SessionDelegations;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.Map;
import java.util.function.Consumer;
import com.skillforge.runtime.api.dag.DagPlan;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.dag.DagWorkPort;
import com.skillforge.runtime.api.RunStatus;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;


class TeamWorkServiceTest {

    @Test
    void blockedUnadmittedStepsSettleButClosureStillRequiresParentTerminalFact() {
        var tenant=new TenantId("1");
        var agent=new AgentId("7");
        var owner=SessionOwner.user(new SessionRef(tenant,"blocked-session"),agent,new EndUserId(9));
        var run=new RunRef(tenant,"blocked-parent");
        var first=new DagPlan.Task("T1","核对",agent,"核对",List.of(),Set.of("answer"),Set.of());
        var second=new DagPlan.Task("T2","汇总",agent,"汇总",
                List.of(new DagPlan.Input("source","T1","answer")),Set.of("answer"),Set.of());
        var plan=new DagPlan(1,false,List.of(first,second));
        var bridge=mock(TeamRuntimeBridge.class);
        var service=new TeamWorkService(mock(JdbcDagWorkPort.class),mock(TeamService.class),bridge,
                mock(TeamResultService.class),mock(TeamWorkInputs.class),mock(ArtifactAccess.class),mock(TeamRecoveryMapper.class),
                hostJson(),Clock.systemUTC(),mock(AgentRunBindings.class),mock(SessionDelegationEngine.class),
                mock(SessionDelegations.Store.class),mock(SessionLogPort.class),mock(com.skillforge.runtime.session.spi.ExecutionStore.class),
                executionCodec(),planDependencies(),delegationHost());
        var parent=mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);
        when(bridge.fact(owner,run)).thenReturn(Optional.of(parent));
        for(var outcome:List.of(DagWork.TaskStatus.FAILED,DagWork.TaskStatus.CANCELLED,DagWork.TaskStatus.OUTCOME_UNKNOWN)) {
            var execution=new DagWork.Execution(new DagWork.Binding("t1-admission",1,first,new RunRef(tenant,"member-run"),Map.of()),
                    outcome,Optional.empty(),false,0,Map.of(),"",Optional.empty());
            var work=new DagWork(new DagWork.Key(owner,new InputId("input-1")),agent,2,DagWork.Status.ACTIVE,
                    plan,0,0,Map.of("T1",execution),Map.of(),new DagWork.Request("核查",Optional.empty(),run,""));
            assertTrue(service.stepsSettled(work));
            when(parent.status()).thenReturn(RunStatus.RUNNING);
            assertTrue(service.finish(work).isEmpty());
            when(parent.status()).thenReturn(RunStatus.COMPLETED);
            assertTrue(service.finish(work).isPresent());
            assertEquals(Set.of("T1"),work.executions().keySet());
            assertEquals(outcome,work.executions().get("T1").status());
        }
    }

    @Test
    void memberReservationAdmitsActualChildOutsidePlanTransactionScope() {
        var tenant=new TenantId("1");var agent=new AgentId("7");var input=new InputId("plan-input");
        var owner=SessionOwner.user(new SessionRef(tenant,"plan-session"),agent,new EndUserId(9));
        var parent=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant,"parent-run"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var task=new DagPlan.Task("member","核对",new AgentId("8"),"核对订单",List.of(),Set.of("answer"),Set.of());
        var child=new RunRef(tenant,"exact-member-run");var admission=new DagWork.Binding("admission",1,task,child,Map.of());
        var permit=new DagWork.Permit(2,Instant.now().plusSeconds(600));
        var execution=new DagWork.Execution(admission,DagWork.TaskStatus.RESERVED,Optional.of(permit),false,0,Map.of(),"reserved",Optional.empty());
        var work=new DagWork(new DagWork.Key(owner,input),agent,2,DagWork.Status.ACTIVE,new DagPlan(1,false,List.of(task)),0,0,
                Map.of(task.id(),execution),Map.of(),new DagWork.Request("核对",Optional.empty(),parent.runRef(),""));
        var binding=mock(AgentRunBindings.Binding.class);when(binding.owner()).thenReturn(owner);when(binding.actor()).thenReturn(agent);when(binding.role()).thenReturn(AgentRunBindings.Role.PRIMARY);
        var bindings=mock(AgentRunBindings.class);when(bindings.find(parent.runRef())).thenReturn(Optional.of(binding));
        var bridge=mock(TeamRuntimeBridge.class);var record=mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);when(record.identity()).thenReturn(parent);
        when(bridge.fact(owner,parent.runRef())).thenReturn(Optional.of(record));
        var target=new SessionDelegations.Target("member:8",task.agentId(),"核对成员","核对",List.of(),new TypedPayload("member","{}"));
        var frozenTask=new SessionDelegations.Task(task.objective(),"准确绑定上下文",task.constraints(),"准确成果契约");
        var entry=new SessionDelegations.Entry("delegation",parent,"dag:admission",target,frozenTask,child,new InputId("child-input"),Optional.empty(),
                SessionDelegations.State.QUEUED,Optional.empty(),Optional.empty(),1,Instant.EPOCH,Instant.EPOCH);
        var delegations=mock(SessionDelegationEngine.class);when(delegations.targets(parent)).thenReturn(List.of(target));
        when(delegations.start(parent,"dag:admission",target,frozenTask,Optional.empty())).thenReturn(entry);
        var hosts=delegationHost();var host=hosts.getObject();when(host.planTask(work,admission)).thenReturn(frozenTask);
        var service=new TeamWorkService(mock(JdbcDagWorkPort.class),mock(TeamService.class),bridge,mock(TeamResultService.class),mock(TeamWorkInputs.class),
                mock(ArtifactAccess.class),mock(TeamRecoveryMapper.class),hostJson(),Clock.systemUTC(),bindings,delegations,mock(SessionDelegations.Store.class),
                mock(SessionLogPort.class),mock(com.skillforge.runtime.session.spi.ExecutionStore.class),executionCodec(),planDependencies(),hosts);
        service.ensureAccepted(work,execution);
        verify(host).admitPlan(entry,work.key(),admission,permit);
        org.mockito.Mockito.clearInvocations(host,delegations);
        service.participatePlan(parent,binding,()->{service.ensureAccepted(work,execution);return null;});
        verifyNoInteractions(host,delegations);
    }

    @Test
    void wholePlanSupplementAndAdjustmentReachOriginalRunBeforeAnyStepStarts() {
        for (String operation : List.of("supplement", "adjust")) {
            var owner = SessionOwner.user(new SessionRef(new TenantId("1"), "plan-session"),
                    SessionTarget.agent(new AgentId("7")), new EndUserId(9));
            var run = new RunRef(new TenantId("1"), "original-primary-run");
            var task = new DagPlan.Task("P1", "分析", new AgentId("7"), DagPlan.ExecutorType.PRIMARY,
                    "分析预算", List.of(), Set.of("answer"), Set.of());
            var work = new DagWork(new DagWork.Key(owner, new InputId("original-input")), new AgentId("7"),
                    1, DagWork.Status.ACTIVE, new DagPlan(1, true, List.of(task)), 0, 0, Map.of(), Map.of(),
                    new DagWork.Request("预算", Optional.empty(), run, ""));
            var works = mock(JdbcDagWorkPort.class);
            when(works.read(work.key())).thenReturn(Optional.of(work));
            var inputs = mock(TeamWorkInputs.class);
            var bridge = mock(TeamRuntimeBridge.class);
            var service = new TeamWorkService(works, mock(TeamService.class), bridge,
                    mock(TeamResultService.class), inputs, mock(ArtifactAccess.class), mock(TeamRecoveryMapper.class),
                    hostJson(), Clock.systemUTC(), mock(AgentRunBindings.class), mock(SessionDelegationEngine.class),
                    mock(SessionDelegations.Store.class), mock(SessionLogPort.class), mock(com.skillforge.runtime.session.spi.ExecutionStore.class), executionCodec(), planDependencies(), delegationHost());
            var control = new TeamWorkInputs.Control("control-" + operation, "", operation,
                    "预算改为48000，先修订并等待批准", null, "", "", "");
            when(inputs.pending(work)).thenReturn(List.of(new TeamWorkInputs.Input(control, Instant.now(), "ACCEPTED", "")));

            service.control(work, control);

            verify(bridge).supplement(work, run, control.inputId(), control.content());
            verify(inputs).applied(work, control.inputId(), "已纳入对应工作或执行");
            org.mockito.Mockito.verify(inputs, org.mockito.Mockito.never()).rejected(any(), any(), any());
            org.mockito.Mockito.verifyNoMoreInteractions(bridge);
            assertTrue(work.plan().approvalRequired());
            assertEquals(0, work.approvedRevision());
            assertTrue(work.executions().isEmpty());
        }
    }

    @Test
    void ordinaryAgentCreatesPrimaryPlanWithoutDelegationAndKeepsApprovalSticky() {
        withParentInput("{\"content\":[{\"type\":\"text\",\"text\":\"调查并输出\"}],"
                + "\"extraParameters\":{\"delegation_requester_id\":\"member-9\",\"delegation_channel_id\":\"\"}}",
                new AgentRunBindings.Requester("member-9", ""), SessionTarget.agent(new AgentId("7")), fixture -> {
            when(fixture.binding().scope().delegation()).thenReturn(new AgentDelegationConfig(false, List.of()));
            var plan = new DagPlan(1, true, List.of(new DagPlan.Task("P1", "主步骤", new AgentId("7"),
                    DagPlan.ExecutorType.PRIMARY, "调查并输出", List.of(), Set.of("answer"), Set.of())));
            var created = new AtomicReference<DagWork>();
            when(fixture.works().create(any(), any(), any())).thenAnswer(call -> {
                created.set(call.getArgument(0)); return true;
            });
            assertEquals(DagWorkPort.Commit.APPLIED, fixture.service().submitPlan(fixture.parent(), "create", plan, 0, "request"));
            assertTrue(created.get().plan().approvalRequired());
            assertEquals(fixture.parent().runRef(), created.get().request().controlRun());
            org.mockito.Mockito.verify(fixture.bindings(), org.mockito.Mockito.never()).authorizeTarget(any(), any());
            when(fixture.works().read(created.get().key())).thenReturn(Optional.of(created.get()));
            var revision = new DagPlan(2, false, plan.tasks());
            var failure = assertThrows(ApiException.class,
                    () -> fixture.service().submitPlan(fixture.parent(), "downgrade", revision, 1, "request"));
            assertEquals("任务计划的审批要求不能取消", failure.getMessage());
        });
    }

    @Test
    void primaryResultsRequireExactDurableStepEventAndIndependentOutputDigests() throws Exception {
        var owner = SessionOwner.user(new SessionRef(new TenantId("1"), "primary-session"),
                SessionTarget.agent(new AgentId("7")), new EndUserId(9));
        var run = new RunRef(new TenantId("1"), "primary-run");
        var task = new DagPlan.Task("P1", "主步骤", new AgentId("7"), DagPlan.ExecutorType.PRIMARY,
                "分析", List.of(), Set.of("answer", "核查/结论"), Set.of());
        var key = new DagWork.Key(owner, new InputId("input-1"));
        var binding = new DagWork.Binding("admission-1", 1, task, run, Map.of());
        var execution = new DagWork.Execution(binding, DagWork.TaskStatus.RUNNING,
                Optional.of(new DagWork.Permit(2, Instant.now().plusSeconds(60))), false, 0, Map.of(), "", Optional.empty());
        var work = new DagWork(key, new AgentId("7"), 2, DagWork.Status.ACTIVE,
                new DagPlan(1, false, List.of(task)), 0, 0, Map.of("P1", execution), Map.of(),
                new DagWork.Request("分析", Optional.empty(), run, ""));
        var log = mock(SessionLogPort.class);
        var bridge = mock(TeamRuntimeBridge.class);
        var service = new TeamWorkService(mock(JdbcDagWorkPort.class), mock(TeamService.class), bridge,
                mock(TeamResultService.class), mock(TeamWorkInputs.class), mock(ArtifactAccess.class), mock(TeamRecoveryMapper.class),
                hostJson(), Clock.systemUTC(), mock(AgentRunBindings.class), mock(SessionDelegationEngine.class),
                mock(SessionDelegations.Store.class), log, mock(com.skillforge.runtime.session.spi.ExecutionStore.class), executionCodec(), planDependencies(), delegationHost());
        String summary = "分析结论";
        String outputText = "独立分析产出";
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(outputText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var ref = new ResultRef("plan-finish:finish-1");
        var outputRef = new ResultRef(ref.value() + "#" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("answer".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String reviewText = "核查结果与分析分别保存";
        String reviewDigest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(reviewText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var reviewRef = new ResultRef(ref.value() + "#" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("核查/结论".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var completion = new com.skillforge.runtime.session.dag.DagCoordinator.Completion(ref.value(), "P1", run, 2,
                DagWork.TaskStatus.COMPLETED, 0, Map.of("answer", new DagWork.Output(outputRef, digest, true),
                        "核查/结论", new DagWork.Output(reviewRef, reviewDigest, true)), summary, ref);
        assertThrows(ApiException.class, () -> service.requireResult(work, execution, completion));
        var payload = new java.util.LinkedHashMap<String, Object>(Map.of("work_id", "input-1", "task_id", "P1",
                "admission_id", "admission-1", "summary", summary, "outcome", "COMPLETED", "result_refs", List.of(),
                "outputs", Map.of("answer", outputText, "核查/结论", reviewText)));
        var event = new CommittedSessionEvent(owner, new AgentId("7"), key.rootInputId(), Optional.of(run),
                Optional.empty(), Optional.empty(), Optional.empty(), new EventId(ref.value()), 1, "plan.step.finished", "1",
                new TraceEnvelope("trace", "span", key.rootInputId(), Optional.empty()),
                new TypedPayload("plan.step.finished.v1", hostJson().writeValueAsString(payload)), Instant.now());
        when(log.findByEvent(owner, new EventId(ref.value()))).thenReturn(Optional.of(event));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.requireResult(work, execution, completion));
        var completedExecution = new DagWork.Execution(binding, DagWork.TaskStatus.COMPLETED, Optional.empty(), false,
                0, completion.outputs(), summary, Optional.of(ref));
        var settledWork = new DagWork(key, work.coordinator(), 3, DagWork.Status.ACTIVE, work.plan(), 0, 0,
                Map.of("P1", completedExecution), Map.of(), work.request());
        var parent = mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);
        when(bridge.fact(owner, run)).thenReturn(Optional.of(parent));
        when(parent.status()).thenReturn(RunStatus.RUNNING);
        assertTrue(service.stepsSettled(settledWork));
        assertTrue(service.finish(settledWork).isEmpty());
        when(parent.status()).thenReturn(RunStatus.COMPLETED);
        assertTrue(service.finish(settledWork).isPresent());
        var forged = new com.skillforge.runtime.session.dag.DagCoordinator.Completion(ref.value(), "P1", run, 2,
                DagWork.TaskStatus.COMPLETED, 0, Map.of("answer", new DagWork.Output(outputRef, "forged", true),
                        "核查/结论", new DagWork.Output(reviewRef, reviewDigest, true)), summary, ref);
        assertThrows(ApiException.class, () -> service.requireResult(work, execution, forged));
        var swapped = new com.skillforge.runtime.session.dag.DagCoordinator.Completion(ref.value(), "P1", run, 2,
                DagWork.TaskStatus.COMPLETED, 0, Map.of("answer", new DagWork.Output(reviewRef, reviewDigest, true),
                        "核查/结论", new DagWork.Output(outputRef, digest, true)), summary, ref);
        assertThrows(ApiException.class, () -> service.requireResult(work, execution, swapped));
        payload.put("admission_id", "other-admission");
        var wrong = new CommittedSessionEvent(owner, new AgentId("7"), key.rootInputId(), Optional.of(run),
                Optional.empty(), Optional.empty(), Optional.empty(), new EventId(ref.value()), 1, "plan.step.finished", "1",
                event.trace(), new TypedPayload("plan.step.finished.v1", hostJson().writeValueAsString(payload)), Instant.now());
        when(log.findByEvent(owner, new EventId(ref.value()))).thenReturn(Optional.of(wrong));
        assertThrows(ApiException.class, () -> service.requireResult(work, execution, completion));
    }

    @Test
    void workerRecoveryTicksOnlyBoundedTenantPlansAndRestoresContext() {
        var mapper = mock(TeamRecoveryMapper.class);
        var store = mock(JdbcDagWorkPort.class);
        var delegates = mock(SessionDelegationEngine.class);
        var owner = SessionOwner.user(new SessionRef(new TenantId("1"), "ordinary-session"),
                SessionTarget.agent(new AgentId("7")), new EndUserId(9));
        var key = new DagWork.Key(owner, new InputId("ordinary-input"));
        when(store.recoverable(new TenantId("1"), 100)).thenReturn(List.of(key));
        when(store.read(key)).thenReturn(Optional.of(new DagWork(key, new AgentId("7"), 1, DagWork.Status.COMPLETED,
                new DagPlan(1, false, List.of(new DagPlan.Task("P1", "主步骤", new AgentId("7"), DagPlan.ExecutorType.PRIMARY,
                        "分析", List.of(), Set.of("answer"), Set.of()))), 0, 0, Map.of(), Map.of(),
                new DagWork.Request("分析", Optional.empty(), new RunRef(new TenantId("1"), "run"), ""))));
        var service = new TeamWorkService(store, mock(TeamService.class), mock(TeamRuntimeBridge.class),
                mock(TeamResultService.class), mock(TeamWorkInputs.class), mock(ArtifactAccess.class), mapper,
                hostJson(), Clock.systemUTC(), mock(AgentRunBindings.class), delegates,
                mock(SessionDelegations.Store.class), mock(SessionLogPort.class), mock(com.skillforge.runtime.session.spi.ExecutionStore.class), executionCodec(), planDependencies(), delegationHost());
        String previous = com.skillforge.common.context.TenantContext.getTenantId();
        service.recover(new TenantId("1"),100);
        service.recover(new TenantId("2"),100);
        assertEquals(previous, com.skillforge.common.context.TenantContext.getTenantId());
        verifyNoInteractions(delegates);
        verify(store).read(key);
        verifyNoInteractions(mapper);
        assertThrows(IllegalArgumentException.class,()->service.recover(new TenantId("1"),101));
    }

    @Test
    void cancelledWorkReconcilesOriginalSuspendedParentWithoutPrimaryExecutionOrAnotherUserCancel() {
        var tenant = new TenantId("1");
        var owner = SessionOwner.user(new SessionRef(tenant, "cancel-session"), SessionTarget.agent(new AgentId("7")), new EndUserId(9));
        var runRef = new RunRef(tenant, "original-control-run");
        var key = new DagWork.Key(owner, new InputId("original-input"));
        var plan = new DagPlan(2, true, List.of(new DagPlan.Task("P1", "计算", new AgentId("7"), DagPlan.ExecutorType.PRIMARY,
                "计算", List.of(), Set.of("answer"), Set.of())));
        for (var workStatus : List.of(DagWork.Status.CANCELLED, DagWork.Status.COMPLETED)) {
            for (var runStatus : List.of(RunStatus.SUSPENDED, RunStatus.RUNNING, RunStatus.COMPLETED, RunStatus.CANCELLED)) {
                var work = new DagWork(key, new AgentId("7"), 4, workStatus, plan, 2, 0, Map.of(), Map.of(),
                        new DagWork.Request("任务", Optional.empty(), runRef, ""));
                var store = mock(JdbcDagWorkPort.class);
                when(store.read(key)).thenReturn(Optional.of(work));
                var bridge = mock(TeamRuntimeBridge.class);
                var run = mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);
                when(run.status()).thenReturn(runStatus);
                when(run.cancelled()).thenReturn(false);
                when(bridge.fact(owner, runRef)).thenReturn(Optional.of(run));
                var log = mock(SessionLogPort.class);
                var event = new com.skillforge.runtime.api.port.CommittedSessionEvent(owner, new AgentId("7"), key.rootInputId(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), new EventId("cancel-event"), 169,
                        "work.cancel_requested", "1", new com.skillforge.runtime.api.TraceEnvelope("t", "s", key.rootInputId(), Optional.of(new EventId("control:original-cancel-input"))),
                        new com.skillforge.runtime.api.TypedPayload("dag.work.v1", "{}"), Instant.now());
                when(log.readAfter(any(SessionLogPort.ReadSessionLog.class), org.mockito.ArgumentMatchers.eq(Set.of("work.cancel_requested"))))
                        .thenReturn(List.of(event));
                var inputs = mock(TeamWorkInputs.class);
                var originalInput = new TeamWorkInputs.Input(new TeamWorkInputs.Control("original-cancel-input", "", "cancel", "", null, "", "", ""), Instant.now(), "ACCEPTED", "");
                var unrelatedInput = new TeamWorkInputs.Input(new TeamWorkInputs.Control("other-input", "", "supplement", "other", null, "", "", ""), Instant.now(), "ACCEPTED", "");
                when(inputs.pending(work)).thenReturn(List.of(originalInput, unrelatedInput), List.of(unrelatedInput));
                var service = new TeamWorkService(store, mock(TeamService.class), bridge, mock(TeamResultService.class),
                        inputs, mock(ArtifactAccess.class), mock(TeamRecoveryMapper.class), hostJson(), Clock.systemUTC(),
                        mock(AgentRunBindings.class), mock(SessionDelegationEngine.class), mock(SessionDelegations.Store.class), log, mock(com.skillforge.runtime.session.spi.ExecutionStore.class), executionCodec(), planDependencies(), delegationHost());
                service.tick(key);
                service.tick(key);
                if (workStatus == DagWork.Status.CANCELLED && (runStatus == RunStatus.SUSPENDED || runStatus == RunStatus.RUNNING))
                    verify(bridge, org.mockito.Mockito.times(2)).cancel(work, runRef, "plan-parent-cancel:control:original-cancel-input");
                else verify(bridge, org.mockito.Mockito.never()).cancel(any(), any(), any());
                if (workStatus == DagWork.Status.CANCELLED)
                    verify(inputs).applied(work, "original-cancel-input", "已纳入对应工作或执行");
                else verify(inputs, org.mockito.Mockito.never()).applied(any(), any(), any());
                verify(inputs, org.mockito.Mockito.never()).applied(org.mockito.ArgumentMatchers.eq(work), org.mockito.ArgumentMatchers.eq("other-input"), any());
                assertTrue(work.executions().isEmpty());
            }
        }
    }

    @Test
    void terminalParentClosesUnapprovedWorkWhileSuspendedParentRemainsAwaitingApproval() {
        var tenant = new TenantId("1");
        var owner = SessionOwner.user(new SessionRef(tenant, "session-1"),
                SessionTarget.team("team-1"), new EndUserId(9));
        var runRef = new RunRef(tenant, "run-1");
        var key = new DagWork.Key(owner, new InputId("input-1"));
        var plan = new DagPlan(1, true, List.of(
                new DagPlan.Task("T1", "计算", new AgentId("8"), "计算 17×19",
                        List.of(), Set.of("answer"), Set.of())));
        for (var status : List.of(RunStatus.COMPLETED,
                RunStatus.FAILED, RunStatus.CANCELLED,
                RunStatus.TERMINATED, RunStatus.SUSPENDED)) {
            var work = new DagWork(key, new AgentId("7"), 1,
                    DagWork.Status.ACTIVE, plan, 0, 0, Map.of(),
                    Map.of(), new DagWork.Request(
                            "待审批任务", Optional.empty(), runRef, ""));
            var current = new AtomicReference<>(work);
            var store = mock(JdbcDagWorkPort.class);
            when(store.read(key)).thenAnswer(ignored -> Optional.of(current.get()));
            when(store.commit(any(), anyLong(), any(), any(), any(), any()))
                    .thenAnswer(call -> {
                        assertEquals(current.get().revision(), (long) call.getArgument(1));
                        current.set(call.getArgument(4));
                        return DagWorkPort.Commit.APPLIED;
                    });
            var bridge = mock(TeamRuntimeBridge.class);
            var run = mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);
            when(run.status()).thenReturn(status);
            when(run.cancelled()).thenReturn(false);
            when(bridge.fact(owner, runRef)).thenReturn(Optional.of(run));
            var delegations = mock(SessionDelegationEngine.class);
            var bindings = mock(AgentRunBindings.class);
            var binding = mock(AgentRunBindings.Binding.class);
            when(binding.owner()).thenReturn(owner);
            when(binding.actor()).thenReturn(new AgentId("7"));
            when(binding.role()).thenReturn(AgentRunBindings.Role.PRIMARY);
            when(bindings.find(runRef)).thenReturn(Optional.of(binding));
            var service = new TeamWorkService(store, mock(TeamService.class), bridge,
                    mock(TeamResultService.class), mock(TeamWorkInputs.class), mock(ArtifactAccess.class),
                    mock(TeamRecoveryMapper.class), hostJson(), Clock.systemUTC(), bindings,
                    delegations, mock(SessionDelegations.Store.class), mock(SessionLogPort.class), mock(com.skillforge.runtime.session.spi.ExecutionStore.class), executionCodec(), planDependencies(), delegationHost());

            service.tick(key);

            assertEquals(status == RunStatus.SUSPENDED
                    ? DagWork.Status.ACTIVE
                    : DagWork.Status.CANCELLED, current.get().status());
            verifyNoInteractions(delegations);
        }
    }

    @Test
    void unadmittedRevocationClosesWorkButTransientFailureRemainsRetryable() {
        var tenant = new TenantId("1");
        var owner = SessionOwner.user(new SessionRef(tenant, "single-chat"),
                SessionTarget.agent(new AgentId("7")), new EndUserId(9));
        var runRef = new RunRef(tenant, "run-1");
        var key = new DagWork.Key(owner, new InputId("input-1"));
        var plan = new DagPlan(1, true, List.of(new DagPlan.Task("T1", "计算", new AgentId("8"), "计算结果",
                List.of(), Set.of("answer"), Set.of())));
        for (RuntimeException failure : List.of(new SecurityException("revoked"), new IllegalStateException("database unavailable"))) {
            var work = new DagWork(key, new AgentId("7"), 1, DagWork.Status.ACTIVE, plan, 1, 0,
                    Map.of(), Map.of(), new DagWork.Request("计划", Optional.empty(), runRef, ""));
            var current = new AtomicReference<>(work);
            var store = mock(JdbcDagWorkPort.class);
            when(store.read(key)).thenAnswer(ignored -> Optional.of(current.get()));
            when(store.commit(any(), anyLong(), any(), any(), any(), any())).thenAnswer(call -> {
                current.set(call.getArgument(4)); return DagWorkPort.Commit.APPLIED;
            });
            var bindings = mock(AgentRunBindings.class);
            var binding = mock(AgentRunBindings.Binding.class);
            when(binding.owner()).thenReturn(owner);
            when(binding.actor()).thenReturn(new AgentId("7"));
            when(binding.role()).thenReturn(AgentRunBindings.Role.PRIMARY);
            when(bindings.find(runRef)).thenReturn(Optional.of(binding));
            org.mockito.Mockito.doThrow(failure).when(bindings).authorizeTarget(binding, "8");
            var bridge = mock(TeamRuntimeBridge.class);
            var run = mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);
            when(run.status()).thenReturn(RunStatus.SUSPENDED);
            when(run.cancelled()).thenReturn(false);
            when(bridge.fact(owner, runRef)).thenReturn(Optional.of(run));
            var delegates = mock(SessionDelegationEngine.class);
            var service = new TeamWorkService(store, mock(TeamService.class), bridge,
                    mock(TeamResultService.class), mock(TeamWorkInputs.class), mock(ArtifactAccess.class),
                    mock(TeamRecoveryMapper.class), hostJson(), Clock.systemUTC(), bindings,
                    delegates, mock(SessionDelegations.Store.class), mock(SessionLogPort.class), mock(com.skillforge.runtime.session.spi.ExecutionStore.class), executionCodec(), planDependencies(), delegationHost());
            if (failure instanceof SecurityException) {
                service.tick(key);
                assertEquals(DagWork.Status.CANCELLED, current.get().status());
                assertTrue(current.get().executions().isEmpty());
            } else {
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.tick(key));
                assertEquals(DagWork.Status.ACTIVE, current.get().status());
            }
            verifyNoInteractions(delegates);
        }
    }

    @Test
    void planModeReadsCurrentSessionCollaborationInsteadOfOriginalInputFlag() {
        var tenant = new TenantId("1");
        var owner = SessionOwner.user(new SessionRef(tenant, "session-1"),
                SessionTarget.team("team-1"), new EndUserId(9));
        var runRef = new RunRef(tenant, "run-1");
        var input = new InputId("input-1");
        var identity = new RunIdentity(owner, new AgentId("7"), input, runRef, new TraceEnvelope("trace-1", "span-1", input, Optional.empty()), new ResultRef("identity:causation"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var bindings = mock(AgentRunBindings.class);
        var binding = mock(AgentRunBindings.Binding.class);
        when(binding.role()).thenReturn(AgentRunBindings.Role.PRIMARY);
        when(binding.owner()).thenReturn(owner);
        when(bindings.find(runRef)).thenReturn(Optional.of(binding));
        var bridge = mock(TeamRuntimeBridge.class);
        var run = mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);
        when(run.identity()).thenReturn(identity);
        when(bridge.fact(owner, runRef)).thenReturn(Optional.of(run));
        var log = mock(SessionLogPort.class);
        var service = new TeamWorkService(
                mock(JdbcDagWorkPort.class), mock(TeamService.class), bridge,
                mock(TeamResultService.class), mock(TeamWorkInputs.class),
                mock(ArtifactAccess.class), mock(TeamRecoveryMapper.class), hostJson(),
                Clock.systemUTC(), bindings, mock(SessionDelegationEngine.class),
                mock(SessionDelegations.Store.class), log, mock(com.skillforge.runtime.session.spi.ExecutionStore.class), executionCodec(), planDependencies(), delegationHost());
        var event = new CommittedSessionEvent(owner, identity.agentId(), new InputId("plan-on"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), new EventId("plan-on"), 1,
                "plan/selection", "1", identity.trace(),
                new TypedPayload("skillforge.runtime.plan-selection.v1", "{\"active\":true}"),
                Instant.parse("2026-09-26T00:00:00Z"));
        when(log.readAfter(any(SessionLogPort.ReadSessionLog.class), org.mockito.ArgumentMatchers.<Set<String>>any()))
                .thenReturn(List.of(event));

        assertTrue(service.planMode(runRef));

        var pending = new CommittedSessionEvent(owner, identity.agentId(), new InputId("plan-pending"),
                Optional.of(runRef), Optional.empty(), Optional.empty(), Optional.empty(),
                new EventId("plan-pending"), 1, "plan/selection", "1", identity.trace(),
                new TypedPayload("skillforge.runtime.plan-selection.v1", "{\"active\":true}"),
                Instant.parse("2026-09-26T00:00:01Z"));
        when(log.readAfter(any(SessionLogPort.ReadSessionLog.class), org.mockito.ArgumentMatchers.<Set<String>>any()))
                .thenReturn(List.of(pending));
        assertTrue(service.planMode(runRef));

        when(log.readAfter(any(SessionLogPort.ReadSessionLog.class), org.mockito.ArgumentMatchers.<Set<String>>any()))
                .thenReturn(List.of());
        assertFalse(service.planMode(runRef));
    }

    @Test
    void parentGoalConcatenatesOrderedTextPartsAndIgnoresImagesWithoutChangingRequester() {
        withParentInput("""
                {"chatRequestFingerprint":"frozen-current-input",
                 "content":[{"type":"text","text":"  相邻"},
                            {"type":"image","detail":"HIGH","media":null},
                            {"type":"text","text":"分片\\n保留换行  "}],
                 "extraParameters":{"delegation_requester_id":"member-9","delegation_channel_id":""}}
                """, fixture -> {
            var created = new AtomicReference<DagWork>();
            when(fixture.works().create(any(), any(), any())).thenAnswer(call -> {
                created.set(call.getArgument(0)); return true;
            });

            assertEquals(DagWorkPort.Commit.APPLIED,
                    fixture.service().submitPlan(fixture.parent(), "submit", fixture.plan(), 0, "request"));

            assertEquals("request", created.get().consumedEvents().get("submit"));
            when(fixture.works().read(created.get().key())).thenReturn(Optional.of(created.get()));
            assertTrue(fixture.service().planSubmitAccepted(fixture.parent(), "submit", "request"));
            assertFalse(fixture.service().planSubmitAccepted(fixture.parent(), "other", "request"));
            assertEquals("plan_command_conflict", assertThrows(IllegalArgumentException.class,
                    () -> fixture.service().planSubmitAccepted(fixture.parent(), "submit", "changed")).getMessage());
            assertEquals("相邻分片\n保留换行", created.get().request().text());
            assertEquals("member-9", created.get().request().requesterId());
            assertEquals("", created.get().request().channelId());
            verify(fixture.artifacts()).authorizeBound("1", "parent-session", "member-9", "");
        });
    }

    @Test
    void parentGoalKeepsAuthenticatedChannelBinding() {
        withParentInput("""
                {"content":[{"type":"text","text":"渠道目标"}],
                 "extraParameters":{"delegation_requester_id":"","delegation_channel_id":"channel-9"}}
                """, new AgentRunBindings.Requester("", "channel-9"), fixture -> {
            var created = new AtomicReference<DagWork>();
            when(fixture.works().create(any(), any(), any())).thenAnswer(call -> {
                created.set(call.getArgument(0)); return true;
            });
            assertEquals(DagWorkPort.Commit.APPLIED,
                    fixture.service().submitPlan(fixture.parent(), "submit", fixture.plan(), 0, "request"));
            assertEquals("", created.get().request().requesterId());
            assertEquals("channel-9", created.get().request().channelId());
            verify(fixture.artifacts()).authorizeBound("1", "parent-session", "", "channel-9");
        });
    }

    @Test
    void emptyParentGoalsAreRejectedAndOldTextIsNotUsedAsFallback() {
        for (String content : List.of("[]", "[{\"type\":\"text\",\"text\":\"  \\n  \"}]",
                "[{\"type\":\"image\",\"detail\":\"HIGH\",\"media\":null}]")) {
            withParentInput("{\"content\":" + content
                    + ",\"text\":\"旧字段不能恢复父目标\",\"extraParameters\":{}}", fixture -> {
                var error = assertThrows(ApiException.class,
                        () -> fixture.service().submitPlan(fixture.parent(), "submit", fixture.plan(), 0, "request"));
                assertEquals("父工作目标不能为空", error.getMessage());
                verifyNoInteractions(fixture.works(), fixture.artifacts());
            });
        }
    }

    @Test
    void frozenContentCannotReplaceBoundRequester() {
        withParentInput("""
                {"content":[{"type":"text","text":"有效目标"}],
                 "extraParameters":{"delegation_requester_id":"another-member","delegation_channel_id":""}}
                """, fixture -> {
            var error = assertThrows(ApiException.class,
                    () -> fixture.service().submitPlan(fixture.parent(), "submit", fixture.plan(), 0, "request"));
            assertEquals("父输入的认证身份不匹配", error.getMessage());
            verifyNoInteractions(fixture.artifacts());
        });
    }

    private static void withParentInput(String payload, Consumer<ParentPlanFixture> assertions) {
        withParentInput(payload, new AgentRunBindings.Requester("member-9", ""), assertions);
    }

    private static void withParentInput(String payload, AgentRunBindings.Requester requester,
            Consumer<ParentPlanFixture> assertions) {
        withParentInput(payload, requester, SessionTarget.team("team-1"), assertions);
    }

    private static void withParentInput(String payload, AgentRunBindings.Requester requester,
            SessionTarget target, Consumer<ParentPlanFixture> assertions) {
        var owner = SessionOwner.user(new SessionRef(new TenantId("1"), "parent-session"),
                target, new EndUserId(9));
        var input = new InputId("parent-input");
        var runRef = new RunRef(new TenantId("1"), "parent-run");
        var parent = new RunIdentity(owner, new AgentId("7"), input, runRef, new TraceEnvelope("trace-1", "span-1", input, Optional.empty()), new ResultRef("identity:causation"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var executions=mock(com.skillforge.runtime.session.spi.ExecutionStore.class);
        var codec=executionCodec();
        var accepted=mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);
        when(accepted.identity()).thenReturn(parent);when(accepted.createdAt()).thenReturn(Instant.parse("2026-09-26T00:00:00Z"));
        when(executions.load(runRef)).thenReturn(Optional.of(accepted));
        var material=new com.skillforge.runtime.session.spi.ExecutionStore.Material(new ResultRef("execution-input:"+runRef.runId()),
                codec.encodeInput(List.of(new com.skillforge.runtime.api.port.ModelPort.ModelMessage("input",com.skillforge.runtime.api.port.ModelPort.ModelRole.USER,"fixture")),new TypedPayload("input.v1",payload)));
        when(executions.material(runRef.tenantId(),material.reference())).thenReturn(Optional.of(material));
        {
            var bindings = mock(AgentRunBindings.class);
            var binding = mock(AgentRunBindings.Binding.class);
            when(binding.role()).thenReturn(AgentRunBindings.Role.PRIMARY);
            when(binding.owner()).thenReturn(owner);
            when(binding.actor()).thenReturn(parent.agentId());
            var scope = mock(AgentRunBindings.Scope.class);
            when(scope.delegation()).thenReturn(new AgentDelegationConfig(true, List.of("8")));
            when(scope.requester()).thenReturn(requester);
            when(binding.scope()).thenReturn(scope);
            when(bindings.find(runRef)).thenReturn(Optional.of(binding));
            var bridge = mock(TeamRuntimeBridge.class);
            var run = mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);
            when(run.identity()).thenReturn(parent);
            when(run.status()).thenReturn(RunStatus.RUNNING);
            when(bridge.fact(owner, runRef)).thenReturn(Optional.of(run));
            var works = mock(JdbcDagWorkPort.class);
            when(works.read(new DagWork.Key(owner, input))).thenReturn(Optional.empty());
            var artifacts = mock(ArtifactAccess.class);
            var service = new TeamWorkService(works, mock(TeamService.class), bridge,
                    mock(TeamResultService.class), mock(TeamWorkInputs.class), artifacts,
                    mock(TeamRecoveryMapper.class), hostJson(), Clock.systemUTC(), bindings,
                    mock(SessionDelegationEngine.class), mock(SessionDelegations.Store.class),
                    mock(SessionLogPort.class), executions, codec, planDependencies(), delegationHost());
            var plan = new DagPlan(1, false, List.of(new DagPlan.Task("T1", "计算", new AgentId("8"),
                    "计算结果", List.of(), Set.of("answer"), Set.of())));
            assertions.accept(new ParentPlanFixture(service, parent, plan, works, artifacts, bindings, binding));
        }
    }

    private static org.springframework.beans.factory.ObjectProvider<com.skillforge.institution.runtime.AgentDelegationHost> delegationHost() {
        var provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getObject()).thenReturn(org.mockito.Mockito.mock(com.skillforge.institution.runtime.AgentDelegationHost.class));
        return provider;
    }

    private static org.springframework.beans.factory.ObjectProvider<com.skillforge.institution.runtime.AgentRuntimePlanDependencies> planDependencies() {
        var provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getObject()).thenReturn(org.mockito.Mockito.mock(com.skillforge.institution.runtime.AgentRuntimePlanDependencies.class));
        return provider;
    }

    private static com.skillforge.runtime.session.AgentExecutionCodec executionCodec() {
        return new com.skillforge.runtime.session.AgentExecutionCodec(new com.skillforge.runtime.json.CodecLimits(1_000_000,100_000,128));
    }

    private static ObjectMapper hostJson() {
        return new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    private record ParentPlanFixture(TeamWorkService service, RunIdentity parent, DagPlan plan,
            JdbcDagWorkPort works, ArtifactAccess artifacts, AgentRunBindings bindings, AgentRunBindings.Binding binding) { }
}
