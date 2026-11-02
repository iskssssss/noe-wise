package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.institution.team.*;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import org.apache.ibatis.session.SqlSession;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.dag.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.dag.DagCoordinator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class InstitutionPlanHostTest {
    /** 直接委派没有MEMBER admission，拥有自己的执行身份；不能拿childRun调用PRIMARY会话planMode。 */
    @Test void invalidStartStepReturnsCorrectableFeedbackWithoutPrimaryAdmissionOrPrivilegeChange() {
        var f = new Fixture();
        var primary = primary("P1",Set.of("answer"));
        var member = new DagPlan.Task("M1","member",new AgentId("8"),"member task",List.of(),Set.of("answer"),Set.of());
        f.current.set(f.work(new DagPlan(1,true,List.of(primary,member)),Map.of(),1));
        for (var entry : Map.of("missing","plan_step_not_found","M1","plan_primary_step_required").entrySet()) {
            var reply = f.execute(f.run,"invalid-"+entry.getKey(),new PlanCommand(PlanCommand.Operation.START_STEP,
                    f.run.causationInputId().value(),1,Optional.empty(),entry.getKey(),"","",List.of(),List.of()));
            assertEquals("rejected",reply.value().get("status")); assertEquals(entry.getValue(),reply.value().get("reason"));
            assertTrue(reply.waitHandle().isEmpty()); assertNotNull(reply.value().get("tasks"));
        }
        var stale = f.execute(f.run,"stale",new PlanCommand(PlanCommand.Operation.START_STEP,
                f.run.causationInputId().value(),2,Optional.empty(),"P1","","",List.of(),List.of()));
        assertEquals("plan_revision_conflict",stale.value().get("reason"));
        verify(f.service,never()).startPrimary(any(),anyString(),anyString(),any());
        assertTrue(f.current.get().executions().isEmpty());
        when(f.service.startPrimary(any(),eq("P1"),anyString(),any())).thenReturn(DagWorkPort.Commit.CONFLICT);
        var blocked = f.execute(f.run,"blocked",new PlanCommand(PlanCommand.Operation.START_STEP,
                f.run.causationInputId().value(),1,Optional.empty(),"P1","","",List.of(),List.of()));
        assertEquals("plan_step_not_ready",blocked.value().get("reason"));
        when(f.service.startPrimary(any(),eq("P1"),anyString(),any())).thenThrow(new SecurityException("scope rejected"));
        assertThrows(SecurityException.class,()->f.execute(f.run,"denied",new PlanCommand(PlanCommand.Operation.START_STEP,
                f.run.causationInputId().value(),1,Optional.empty(),"P1","","",List.of(),List.of())));
    }

    @Test void directDelegateDispatchUsesOwnIdentityWithoutPrimaryPlanModeOrPlanPrivileges() {
        var f = new Fixture(); var child = child(f.run); var binding = memberBinding(child,f.run.runRef());
        when(f.works.workForRun(child.runRef())).thenReturn(Optional.empty());
        var action = new ActionRef(child.runRef().tenantId(),"direct-child-tool");
        assertDoesNotThrow(() -> f.host.participate(mock(SqlSession.class),child,binding,0,() -> {
            f.host.requireDispatch(child,action,ExecutionStore.Kind.TOOL,false,false,false); return null;
        }));
        verify(f.service,never()).planMode(child.runRef());
        verify(f.works,never()).bindPrimaryAction(any(),anyString(),anyString(),any(),anyString());
        var get = new PlanCommand(PlanCommand.Operation.GET,child.causationInputId().value(),0,Optional.empty(),"","","",List.of(),List.of());
        assertEquals("plan_member_uses_inherited_step",assertThrows(IllegalArgumentException.class,() ->
                f.host.participate(mock(SqlSession.class),child,binding,0,() -> f.host.execute(child,"child-plan",get))).getMessage());
    }

    /** 计划成员只能继承准确Run、Agent和父Run的非终态MEMBER步骤；不绑定为PRIMARY Action。 */
    @Test void plannedDelegateRequiresExactActiveMemberAdmissionAndRejectsInvalidAssociation() {
        for (String variant : List.of("actual","parent","agent","run","terminal","missing-work")) {
            var f = new Fixture(); var child = child(f.run);
            var binding = memberBinding(child,variant.equals("parent") ? new RunRef(child.runRef().tenantId(),"other-parent") : f.run.runRef());
            var task = new DagPlan.Task("M1","核对成员",new AgentId(variant.equals("agent") ? "9" : "8"),"核对真实任务",List.of(),Set.of("answer"),Set.of());
            var source = new DagWork.Binding("admission-M1",1,task,variant.equals("run") ? new RunRef(child.runRef().tenantId(),"other-child") : child.runRef(),Map.of());
            var status = variant.equals("terminal") ? DagWork.TaskStatus.COMPLETED : DagWork.TaskStatus.RUNNING;
            var execution = new DagWork.Execution(source,status,variant.equals("terminal") ? Optional.empty() : Optional.of(new DagWork.Permit(9,Instant.parse("2026-10-19T01:00:00Z"))),
                    false,0,Map.of(),"核对",Optional.empty());
            var work = f.work(new DagPlan(1,true,List.of(task)),Map.of("M1",execution),1);
            if (!variant.equals("missing-work")) f.current.set(work);
            when(f.works.workForRun(child.runRef())).thenReturn(Optional.of(work.key()));
            var action = new ActionRef(child.runRef().tenantId(),"planned-child-tool");
            org.junit.jupiter.api.function.Executable dispatch = () -> f.host.participate(mock(SqlSession.class),child,binding,0,() -> {
                f.host.requireDispatch(child,action,ExecutionStore.Kind.TOOL,false,false,false); return null;
            });
            if (variant.equals("actual")) assertDoesNotThrow(dispatch);
            else if (variant.equals("parent")) assertEquals("plan_parent_binding_invalid",assertThrows(SecurityException.class,dispatch).getMessage());
            else assertEquals("plan_member_step_not_active",assertThrows(IllegalArgumentException.class,dispatch).getMessage(),variant);
            verify(f.service,never()).planMode(child.runRef());
            verify(f.works,never()).bindPrimaryAction(any(),anyString(),anyString(),any(),anyString());
        }
    }

    /** 子身份使用私有会话、实际目标成员和独立input/run，原父身份只通过可信binding关联。 */
    private static RunIdentity child(RunIdentity parent) {
        var owner = SessionOwner.user(new SessionRef(parent.runRef().tenantId(),"delegation:exact-child"),new AgentId("8"),parent.owner().requireUser());
        return new RunIdentity(owner, new AgentId("8"), new InputId("child-input"), new RunRef(parent.runRef().tenantId(),"child-run"), parent.trace(), new ResultRef("child-identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (new InputId("child-input")).value()));
    }

    /** 只建立当前计划核验真正消费的原冻结身份，不赋予成员任何PRIMARY编排权限。 */
    private static AgentRunBindings.Binding memberBinding(RunIdentity child,RunRef parent) {
        var binding = mock(AgentRunBindings.Binding.class);when(binding.owner()).thenReturn(child.owner());when(binding.actor()).thenReturn(child.agentId());
        when(binding.role()).thenReturn(AgentRunBindings.Role.DELEGATE);when(binding.parentRunRef()).thenReturn(Optional.of(parent));return binding;
    }

    @Test
    void actualDispatchRequiresCurrentApprovalAndBindsOnlyAnActiveStep() {
        var f = new Fixture();
        var task = primary("P1", Set.of("answer"));
        f.current.set(f.work(new DagPlan(1, true, List.of(task)), Map.of(), 0));
        var action = new ActionRef(f.run.runRef().tenantId(), "actual-tool");
        assertEquals("plan_step_not_started_or_approved", assertThrows(IllegalArgumentException.class,
                () -> f.dispatch(action, false, false, false)).getMessage());
        assertDoesNotThrow(() -> f.dispatch(action, true, false, false));
        verify(f.works, never()).bindPrimaryAction(any(), anyString(), anyString(), any(), anyString());
        f.current.set(f.work(new DagPlan(1, true, List.of(task)), Map.of("P1", f.running(task, 1, 9)), 1));
        when(f.works.bindPrimaryAction(f.current.get().key(), "P1", "admission-P1", f.run.runRef(), action.actionId())).thenReturn(true);
        assertDoesNotThrow(() -> f.dispatch(action, false, false, false));
        verify(f.works).bindPrimaryAction(f.current.get().key(), "P1", "admission-P1", f.run.runRef(), action.actionId());
        assertEquals("delegate_must_use_plan_step", assertThrows(IllegalArgumentException.class,
                () -> f.dispatch(action, false, false, true)).getMessage());
    }

    @Test
    void requiredPlanAllowsInvestigationButRejectsBusinessExecutionBeforeSubmission() {
        var f = new Fixture();
        when(f.service.planMode(f.run.runRef())).thenReturn(true);
        var action = new ActionRef(f.run.runRef().tenantId(), "actual-tool");
        assertEquals("plan_required_before_execution", assertThrows(IllegalArgumentException.class,
                () -> f.dispatch(action, false, false, false)).getMessage());
        assertDoesNotThrow(() -> f.dispatch(action, true, false, false));
        verify(f.works, never()).bindPrimaryAction(any(), anyString(), anyString(), any(), anyString());
    }
    @Test
    void resumedSubmitAndCurrentGetKeepActualApprovalInputIdentityAndVersion() throws Exception {
        var f = new Fixture();
        f.modelThrough = 0;
        var task = new PlanCommand.Task("P1", "分析", new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY, ""),
                new DelegationCommand.Task("分析", "", List.of(), "结果"), List.of(), Set.of("answer"), Set.of());
        var submit = new PlanCommand(PlanCommand.Operation.SUBMIT, "", 0,
                Optional.of(new PlanCommand.Plan("计划", "", List.of(task), true)), "", "", "", List.of(), List.of());
        when(f.service.submitPlan(eq(f.run), anyString(), any(), eq(0L), anyString())).thenAnswer(call -> {
            f.current.set(f.work(call.getArgument(2), Map.of(), 0));
            return DagWorkPort.Commit.APPLIED;
        });
        var waiting = f.execute(f.run, "approval-source", submit);
        assertTrue(waiting.waitHandle().isPresent());
        assertEquals(true, waiting.value().get("approval_pending"));
        assertEquals(List.of(), waiting.value().get("control_inputs"));
        var work = f.current.get();
        f.current.set(f.work(work.plan(), Map.of(), 1));
        when(f.inputs.recent(f.current.get())).thenReturn(List.of(
                new TeamWorkInputs.Receipt("human-approval", "", "approve_plan", 1L, "APPLIED", "操作已受理")));
        var resumed = f.host.pollWait(f.run, waiting.waitHandle().orElseThrow()).orElseThrow();
        assertEquals(true, resumed.value().get("wait_satisfied"));
        assertEquals(false, resumed.value().get("approval_pending"));
        var controls = (List<?>) resumed.value().get("control_inputs");
        var expectedControls = List.of(Map.of("input_id", "human-approval", "task_id", "", "operation", "approve_plan",
                "plan_revision", 1L, "state", "APPLIED", "detail", "操作已受理"));
        assertEquals(f.json.readTree(f.json.writeValueAsString(expectedControls)),
                f.json.readTree(f.json.writeValueAsString(controls)));
        assertEquals(1L, f.json.valueToTree(controls.getFirst()).path("plan_revision").longValue());
        var get = new PlanCommand(PlanCommand.Operation.GET, "input", 0, Optional.empty(), "", "", "", List.of(), List.of());
        assertEquals(controls, f.execute(f.run, "current-get", get).value().get("control_inputs"));
        for (String state : List.of("ACCEPTED", "REJECTED")) {
            f.current.set(f.work(work.plan(), Map.of(), 0));
            when(f.inputs.recent(f.current.get())).thenReturn(List.of(
                    new TeamWorkInputs.Receipt("not-approved", "", "approve_plan", 1L, state, "正在处理操作")));
            var pending = f.execute(f.run, "pending-get-" + state, get).value();
            assertEquals(true, pending.get("approval_pending"));
            assertEquals(state, f.json.valueToTree(((List<?>) pending.get("control_inputs")).getFirst()).path("state").asText());
            assertTrue(f.host.pollWait(f.run, waiting.waitHandle().orElseThrow()).isEmpty());
        }
    }

    @Test void futureOnlyRevisionPreservesAdmittedDefinitionAndReplayAfterFurtherAdmission() {
        var f = new Fixture();
        var frozen = primary("P1", Set.of("answer"));
        var execution = f.running(frozen, 1, 9);
        f.current.set(f.work(new DagPlan(1, false, List.of(frozen)), Map.of("P1", execution), 0));
        var future = new PlanCommand.Task("P2", "乘六", new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY, ""),
                new DelegationCommand.Task("乘六", "未来定义", List.of(), "结果"), List.of(new PlanCommand.Input("source", "P1", "answer")), Set.of("answer"), Set.of());
        var command = new PlanCommand(PlanCommand.Operation.SUBMIT, "input", 1,
                Optional.of(new PlanCommand.Plan("修订", "用户纠正", List.of(future))), "", "", "", List.of(), List.of());
        when(f.service.submitPlan(eq(f.run), anyString(), any(), eq(1L), anyString())).thenAnswer(call -> {
            DagPlan plan = call.getArgument(2);
            assertEquals(frozen, plan.task("P1"));
            assertEquals("未来定义", plan.task("P2").context());
            f.current.set(f.work(plan, Map.of("P1", execution), 0));
            return DagWorkPort.Commit.APPLIED;
        });
        f.execute(f.run, "revise", command);
        var plan = f.current.get().plan();
        var done = new DagWork.Execution(execution.binding(), DagWork.TaskStatus.COMPLETED, Optional.empty(), false,
                0, Map.of(), "完成", Optional.empty());
        f.current.set(f.work(plan, Map.of("P1", done, "P2", f.running(plan.task("P2"), 2, 10)), 0));
        assertDoesNotThrow(() -> f.execute(f.run, "revise", command));
        verify(f.service, times(1)).submitPlan(eq(f.run), anyString(), any(), eq(1L), anyString());
        assertEquals("plan_admitted_task_immutable", f.execute(f.run, "new-revise", new PlanCommand(PlanCommand.Operation.SUBMIT, "input", 2,
                        Optional.of(new PlanCommand.Plan("修订", "再次纠正", List.of(future))), "", "", "", List.of(), List.of())).value().get("reason"));
    }

    @Test void commandFingerprintSortsSetsAndRejectsChangedSemanticField() {
        var f = new Fixture();
        var first = new PlanCommand.Task("P1", "计划", new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY, ""),
                new DelegationCommand.Task("分析", "原上下文", List.of(), "结果"), List.of(),
                new LinkedHashSet<>(List.of("a", "b")), new LinkedHashSet<>(List.of("x", "y")));
        var reversed = new PlanCommand.Task("P1", "计划", first.executor(), first.task(), first.inputs(),
                new LinkedHashSet<>(List.of("b", "a")), new LinkedHashSet<>(List.of("y", "x")));
        var a = new PlanCommand(PlanCommand.Operation.SUBMIT, "", 0, Optional.of(new PlanCommand.Plan("计划", "", List.of(first))), "", "", "", List.of(), List.of());
        var b = new PlanCommand(PlanCommand.Operation.SUBMIT, "", 0, Optional.of(new PlanCommand.Plan("计划", "", List.of(reversed))), "", "", "", List.of(), List.of());
        String fingerprint = org.springframework.test.util.ReflectionTestUtils.invokeMethod(f.host, "requestFingerprint", a);
        assertEquals(fingerprint, org.springframework.test.util.ReflectionTestUtils.invokeMethod(f.host, "requestFingerprint", b));
        var changed = new PlanCommand.Task("P1", "计划", first.executor(), new DelegationCommand.Task("分析", "不同上下文", List.of(), "结果"),
                first.inputs(), first.outputs(), first.exclusiveResources());
        var c = new PlanCommand(PlanCommand.Operation.SUBMIT, "", 0, Optional.of(new PlanCommand.Plan("计划", "", List.of(changed))), "", "", "", List.of(), List.of());
        assertNotEquals(fingerprint, org.springframework.test.util.ReflectionTestUtils.invokeMethod(f.host, "requestFingerprint", c));
    }

    @Test void committedRequestFingerprintRecoversWithoutAcceptedEventAfterTaskAdmission() {
        var f = new Fixture();
        var task = primary("P1", Set.of("answer"));
        f.current.set(f.work(new DagPlan(2, false, List.of(task)), Map.of("P1", f.running(task, 2, 9)), 0));
        var submittedTask = new PlanCommand.Task("P1", "主步骤", new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY, ""),
                new DelegationCommand.Task("分析", "", List.of(), ""), List.of(), Set.of("answer"), Set.of());
        var command = new PlanCommand(PlanCommand.Operation.SUBMIT, "input", 1,
                Optional.of(new PlanCommand.Plan("修订", "纠正", List.of(submittedTask))), "", "", "", List.of(), List.of());
        when(f.service.planSubmitAccepted(eq(f.run), eq("plan-submit:crash"), anyString())).thenReturn(true);
        assertDoesNotThrow(() -> f.execute(f.run, "crash", command));
        verify(f.service, never()).submitPlan(any(), anyString(), any(), anyLong(), anyString());
        assertTrue(f.events.isEmpty());
    }

    @Test void emptyFutureSetRetainsOnlyAdmittedTasks() {
        var f = new Fixture(); var task = primary("P1", Set.of("answer"));
        var future = primary("P2", Set.of("answer"));
        f.current.set(f.work(new DagPlan(1, false, List.of(task, future)), Map.of("P1", f.running(task, 1, 9)), 0));
        when(f.service.submitPlan(eq(f.run), anyString(), any(), eq(1L), anyString())).thenAnswer(call -> {
            DagPlan merged = call.getArgument(2); assertEquals(List.of(task), merged.tasks());
            f.current.set(f.work(merged, f.current.get().executions(), 0)); return DagWorkPort.Commit.APPLIED;
        });
        f.execute(f.run, "remove-future", new PlanCommand(PlanCommand.Operation.SUBMIT, "input", 1,
                Optional.of(new PlanCommand.Plan("修订", "移除未来步骤", List.of())), "", "", "", List.of(), List.of()));
        assertEquals(List.of(task), f.current.get().plan().tasks());
    }

    @Test void getProvidesFutureDefinitionsAndAuthoritativeLogicalOutputsWithoutHistoricalContext() {
        var f = new Fixture();
        var frozen = primary("P1", Set.of("answer"));
        var future = new DagPlan.Task("M1", "成员", new AgentId("8"), "调查", List.of(), Set.of("report"), Set.of());
        f.current.set(f.work(new DagPlan(1, false, List.of(frozen, future)), Map.of("P1", f.running(frozen, 1, 9)), 0));
        var reply = f.execute(f.run, "get", new PlanCommand(PlanCommand.Operation.GET, "input", 0,
                Optional.empty(), "", "", "", List.of(), List.of())).value();
        assertEquals(List.of("P1"), reply.get("immutable_task_ids"));
        var pending = (List<?>) reply.get("pending_tasks");
        assertEquals(1, pending.size());
        assertEquals(Map.of("type", "member", "target_handle", "member:8"), ((Map<?, ?>)pending.getFirst()).get("executor"));
        assertTrue(reply.get("dependency_outputs").toString().contains("answer"));
    }

    @Test void finalizationRequiresSuccessfulApprovedStepsAndNeverTreatsSettledFailureAsSuccess() {
        var f = new Fixture();
        assertDoesNotThrow(() -> f.host.requireFinalizationReady(f.run));
        var task = primary("P1", Set.of("answer"));
        for (var status : List.of(DagWork.TaskStatus.RUNNING, DagWork.TaskStatus.FAILED,
                DagWork.TaskStatus.CANCELLED, DagWork.TaskStatus.OUTCOME_UNKNOWN)) {
            var execution = status == DagWork.TaskStatus.RUNNING ? f.running(task, 1, 9)
                    : new DagWork.Execution(f.running(task, 1, 9).binding(), status, Optional.empty(),
                            false, 0, Map.of(), "未成功", Optional.empty());
            f.current.set(f.work(new DagPlan(1, false, List.of(task)), Map.of("P1", execution), 0));
            assertEquals("plan_steps_not_complete_before_finalization", assertThrows(IllegalArgumentException.class,
                    () -> f.host.requireFinalizationReady(f.run)).getMessage());
        }
        var completed = new DagWork.Execution(f.running(task, 1, 9).binding(), DagWork.TaskStatus.COMPLETED,
                Optional.empty(), false, 0, Map.of("answer", new DagWork.Output(new ResultRef("result"), "a".repeat(64), true)),
                "成功", Optional.empty());
        f.current.set(f.work(new DagPlan(1, true, List.of(task)), Map.of("P1", completed), 0));
        assertEquals("plan_not_approved_for_finalization", assertThrows(IllegalArgumentException.class,
                () -> f.host.requireFinalizationReady(f.run)).getMessage());
        f.current.set(f.work(new DagPlan(1, true, List.of(task)), Map.of("P1", completed), 1));
        assertDoesNotThrow(() -> f.host.requireFinalizationReady(f.run));
        var before = f.current.get();
        f.current.set(new DagWork(before.key(), before.coordinator(), before.revision(), DagWork.Status.CANCELLED,
                before.plan(), before.approvedRevision(), before.spentTokens(), before.executions(), before.consumedEvents(), before.request()));
        assertEquals("plan_not_approved_for_finalization", assertThrows(IllegalArgumentException.class,
                () -> f.host.requireFinalizationReady(f.run)).getMessage());
    }
    @Test
    void currentPrimaryEvidenceUsesOnlyAdoptedTypedResultsForThatStep() {
        var f = new Fixture(); f.runningPlan("P1");
        f.action("good", "P1", "verified-result", ExecutionStore.ActionStatus.SUCCEEDED, "{}");
        f.action("business-error", "P1", "error-result", ExecutionStore.ActionStatus.SUCCEEDED, "{\"isError\":true}");
        f.action("foreign", "P0", "foreign-result", ExecutionStore.ActionStatus.SUCCEEDED, "{}");
        var get = new PlanCommand(PlanCommand.Operation.GET, "input", 0, Optional.empty(), "", "", "", List.of(), List.of());
        var evidence = (Map<?, ?>) f.execute(f.run, "evidence", get).value().get("current_primary_step_evidence");
        assertEquals(List.of("verified-result"), evidence.get("successful_result_refs"));
        assertEquals(true, evidence.get("requires_result_refs"));
        var rejected = f.execute(f.run, "foreign-result", finish("P1", Map.of("answer", "结论"), List.of(new ResultRef("foreign-result")))).value();
        assertEquals("plan_result_not_owned_or_succeeded", rejected.get("reason"));
        f.action("unknown", "P1", "unknown-result", ExecutionStore.ActionStatus.UNKNOWN, "{}");
        var unknown = f.execute(f.run, "unknown", finish("P1", Map.of("answer", "结论"), List.of(new ResultRef("verified-result")))).value();
        assertEquals("plan_step_outcome_unknown", unknown.get("reason"));
        verify(f.service, never()).finishPrimary(any(), any());
    }

    @Test
    void omittedReferencesFreezeOriginalTypedResultAndIgnoreLaterResultsOnReplay() throws Exception {
        var f = new Fixture(); f.runningPlan("P2");
        f.action("own", "P2", "own-result", ExecutionStore.ActionStatus.SUCCEEDED, "{}");
        f.action("foreign", "P1", "foreign-result", ExecutionStore.ActionStatus.SUCCEEDED, "{}");
        var command = finish("P2", Map.of("answer", "结论"), List.of());
        f.execute(f.run, "finish", command);
        var original = f.events.get(new EventId("plan-finish:finish"));
        assertEquals(List.of("own-result"), f.json.convertValue(f.json.readTree(original.payload().canonicalJson()).path("result_refs"), List.class));
        f.action("later", "P2", "later-result", ExecutionStore.ActionStatus.SUCCEEDED, "{}");
        f.execute(f.run, "finish", command);
        assertEquals(original, f.events.get(original.eventId()));
        verify(f.service, times(2)).finishPrimary(any(), any());
    }

    @Test
    void inFlightAndUnknownResultsCannotBeHiddenByCompletingPlanStep() {
        for (var status : List.of(ExecutionStore.ActionStatus.DISPATCHED, ExecutionStore.ActionStatus.UNKNOWN)) {
            var f = new Fixture(); f.runningPlan("P1");
            f.action("original", "P1", "result", status, "{}");
            assertEquals("plan_step_outcome_unknown", f.execute(f.run, "finish", finish("P1", Map.of("answer", "结论"), List.of())).value().get("reason"));
            verify(f.service, never()).finishPrimary(any(), any());
        }
    }

    @Test
    void businessFailureCannotAutoCompleteButAbsentSuccessFlagDoesNotInventFailure() {
        for (String envelope : List.of("{\"isError\":true}", "{\"structuredContent\":{\"success\":false}}", "{}", "{\"structuredContent\":{\"success\":null}}", "{\"structuredContent\":{\"success\":true}}")) {
            var f = new Fixture(); f.runningPlan("P1");
            f.action("actual", "P1", "actual-result", ExecutionStore.ActionStatus.SUCCEEDED, envelope);
            var reply = f.execute(f.run, "finish", finish("P1", Map.of("answer", "结论"), List.of()));
            if (envelope.contains("true}") && envelope.contains("isError") || envelope.contains("false")) {
                assertEquals("plan_result_evidence_required", reply.value().get("reason"));
                verify(f.service, never()).finishPrimary(any(), any());
            } else verify(f.service).finishPrimary(any(), any());
        }
    }

    @Test
    void modelPlanSnapshotOmitsUiMergeRevisionsWithoutChangingPublicWorkProjection() {
        var f = new Fixture();
        f.current.set(f.work(new DagPlan(2, true, List.of(primary("P1", Set.of("answer")))), Map.of(), 2));
        var task = new TeamPublicViews.Task("P1", TeamPublicViews.TaskKind.PLAN, "分析", "7", "主助手",
                "running", "run", List.of(), List.of(), "正在执行", null, null, "",
                TeamPublicViews.DetailsAccess.AVAILABLE, "PRIMARY", "核算", "结论", List.of());
        var publicWork = new TeamPublicViews.Work("input", "session", 4, "input", "run", 529L,
                "RUNNING", "working", "active", 2, 2L, true, "计划", new TeamPublicViews.Member("7", "主 Agent"),
                List.of(), List.of(), List.of(task), null, 0, List.of(), "计划", "");
        when(f.views.view(any())).thenReturn(publicWork);
        var get = new PlanCommand(PlanCommand.Operation.GET, "input", 0, Optional.empty(),
                "", "", "", List.of(), List.of());

        var model = f.execute(f.run, "get-plan", get).value();

        assertFalse(model.containsKey("revision"));
        assertFalse(model.containsKey("run_revision"));
        assertEquals(2, ((Number) model.get("plan_revision")).intValue());
        assertEquals(2, ((Number) model.get("approved_revision")).intValue());
        assertFalse(model.containsKey("run_status"));
        assertFalse(model.containsKey("approval_required"));
        assertEquals(false,model.get("approval_pending"));
        var ui = f.json.valueToTree(f.views.view(f.current.get()));
        assertEquals(4, ui.path("revision").asInt());
        assertEquals(529, ui.path("run_revision").asInt());
        assertEquals("P1", f.json.valueToTree(model.get("tasks")).get(0).path("id").asText());
        verify(f.views, times(1)).view(f.current.get());
        assertEquals(2, ui.path("plan_revision").asInt());
    }

    @Test
    void approvalRecoveryReplyReportsOnlyPreciseCurrentPlanApprovalWithoutMutations() {
        var f=new Fixture();
        var task=primary("P1",Set.of("answer"));
        var cases=List.of(
                f.work(new DagPlan(1,true,List.of(task)),Map.of(),0),
                f.work(new DagPlan(1,true,List.of(task)),Map.of(),1),
                f.work(new DagPlan(1,false,List.of(task)),Map.of(),0),
                f.work(new DagPlan(2,true,List.of(task)),Map.of("P1",f.running(task,1,9)),1));
        var get=new PlanCommand(PlanCommand.Operation.GET,"input",0,Optional.empty(),"","","",List.of(),List.of());
        for(int index=0;index<cases.size();index++) {
            var work=cases.get(index);
            f.current.set(work);
            var publicWork=new TeamPublicViews.Work("input","session",1,"input","run",4L,
                    "suspended","working","active",work.plan().revision(),work.approvedRevision()==0?null:work.approvedRevision(),
                    work.plan().approvalRequired(),"",new TeamPublicViews.Member("7","主助手"),
                    List.of(),List.of(),List.of(),null,0,List.of(),"计划","");
            when(f.views.view(any())).thenReturn(publicWork);
            var reply=f.execute(f.run,"get-approval-"+index,get).value();
            assertFalse(reply.containsKey("approval_required"));
            assertEquals(index==0 || index==3,reply.get("approval_pending"));
            assertEquals(work.plan().revision(),((Number)reply.get("plan_revision")).longValue());
            assertFalse(reply.containsKey("run_status"),"pre-resume UI status is not an execution instruction");
            assertFalse(reply.containsKey("execution_approved"),"old admitted steps do not approve a revised plan");
            assertEquals("suspended",publicWork.runStatus(),"UI projection remains factual");
        }
        assertTrue(f.events.isEmpty(),"reading approval cannot approve a plan or start work");
        verifyNoInteractions(f.service);
    }

    @Test
    void alreadyApprovedWaitReturnsExplicitSatisfiedResultWithoutCreatingAnotherWait() {
        var f=new Fixture();
        var plan=new DagPlan(2,true,List.of(primary("P1",Set.of("answer"))));
        f.current.set(f.work(plan,Map.of(),2));
        var command=new PlanCommand(PlanCommand.Operation.WAIT,"input",0,Optional.empty(),"","","",List.of(),List.of());
        var reply=f.execute(f.run,"wait-after-approval",command);
        assertTrue(reply.waitHandle().isEmpty());
        assertEquals(true,reply.value().get("wait_satisfied"));
        assertEquals(false,reply.value().get("approval_pending"));
        assertFalse(reply.value().containsKey("approval_required"));
        assertTrue(f.events.isEmpty());
        verifyNoInteractions(f.service);
    }

    @Test
    void recoveredApprovalWaitReturnsTheSameSatisfiedOperationFact() {
        var f=new Fixture();
        var plan=new DagPlan(2,true,List.of(primary("P1",Set.of("answer"))));
        f.current.set(f.work(plan,Map.of(),0));
        f.modelThrough = 0;
        var command=new PlanCommand(PlanCommand.Operation.WAIT,"input",0,Optional.empty(),"","","",List.of(),List.of());
        var waiting=f.execute(f.run,"approval-wait",command);
        assertTrue(waiting.waitHandle().isPresent());
        assertFalse(waiting.value().containsKey("wait_satisfied"));
        f.current.set(f.work(plan,Map.of(),2));
        int before=f.events.size();
        var recovered=f.host.pollWait(f.run,waiting.waitHandle().orElseThrow()).orElseThrow();
        assertTrue(recovered.waitHandle().isEmpty());
        assertEquals(true,recovered.value().get("wait_satisfied"));
        assertEquals(false,recovered.value().get("approval_pending"));
        assertEquals(before,f.events.size());
        verifyNoInteractions(f.service);
    }

    @Test
    void inputReceivedDuringModelCallReleasesWaitWithoutApprovingPlan() {
        var f = new Fixture();
        f.current.set(f.work(new DagPlan(1, true, List.of(primary("P1", Set.of("answer")))), Map.of(), 0));
        var wait = new PlanCommand(PlanCommand.Operation.WAIT, "input", 0, Optional.empty(), "", "", "", List.of(), List.of());
        f.modelThrough = 0;
        f.supplement("during-model");
        f.execute(f.run, "original-wait", wait);
        assertTrue(f.host.pollWait(f.run, "delegation-wait:original-wait").isPresent());
        assertFalse(f.host.state(f.run).executionApproved());
        assertEquals(0, f.current.get().approvedRevision());
        assertTrue(f.current.get().executions().isEmpty());
        f.modelThrough = 1;
        f.execute(f.run, "next-model-wait", wait);
        assertTrue(f.host.pollWait(f.run, "delegation-wait:next-model-wait").isEmpty());
    }

    @Test
    void inputAfterCommittedWaitReleasesOnlyOriginalWait() {
        var f = new Fixture();
        f.current.set(f.work(new DagPlan(1, true, List.of(primary("P1", Set.of("answer")))), Map.of(), 0));
        var wait = new PlanCommand(PlanCommand.Operation.WAIT, "input", 0, Optional.empty(), "", "", "", List.of(), List.of());
        f.execute(f.run, "wait", wait);
        assertTrue(f.host.pollWait(f.run, "delegation-wait:wait").isEmpty());
        f.supplement("after-wait");
        assertTrue(f.host.pollWait(f.run, "delegation-wait:wait").isPresent());
        assertFalse(f.host.state(f.run).executionApproved());
        verify(f.service, never()).submitPlan(any(), anyString(), any(), anyLong(), anyString());
    }

    @Test
    void waitWithoutValidatedModelSourceScopeIsRejected() {
        var f = new Fixture();
        f.current.set(f.work(new DagPlan(1, true, List.of(primary("P1", Set.of("answer")))), Map.of(), 0));
        var wait = new PlanCommand(PlanCommand.Operation.WAIT, "input", 0, Optional.empty(), "", "", "", List.of(), List.of());
        assertEquals("plan_model_source_scope_missing", assertThrows(IllegalArgumentException.class,
                () -> f.host.execute(f.run, "missing-source", wait)).getMessage());
        assertTrue(f.events.isEmpty());
    }

    @Test
    void initialSubmitReplayUsesOriginalEmptyChangeReason() {
        var f = new Fixture();
        var task = new PlanCommand.Task("P1", "分析", new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY, ""),
                new DelegationCommand.Task("分析", "", List.of(), "结果"), List.of(), Set.of("answer"), Set.of());
        var command = new PlanCommand(PlanCommand.Operation.SUBMIT, "", 0,
                Optional.of(new PlanCommand.Plan("计划", "", List.of(task))), "", "", "", List.of(), List.of());
        var calls = new ArrayList<DagPlan>();
        when(f.service.submitPlan(eq(f.run), eq("plan-submit:submit-1"), any(), eq(0L), anyString())).thenAnswer(call -> {
            DagPlan plan = call.getArgument(2);
            calls.add(plan);
            f.current.set(f.work(plan, Map.of(), 0));
            return calls.size() == 1 ? DagWorkPort.Commit.APPLIED : DagWorkPort.Commit.REPLAYED;
        });
        assertDoesNotThrow(() -> f.execute(f.run, "submit-1", command));
        when(f.service.planMode(f.run.runRef())).thenReturn(true);
        assertDoesNotThrow(() -> f.execute(f.run, "submit-1", command));
        assertEquals(1, calls.size());
        assertEquals(calls.getFirst(), calls.getLast());
        assertEquals("", calls.getLast().changeReason());
        assertFalse(calls.getLast().approvalRequired());
        verify(f.log, times(1)).append(argThat(append -> append.event().eventId().value()
                .equals("plan-submit-accepted:submit-1")));
        var changed = new PlanCommand(PlanCommand.Operation.SUBMIT, "", 0,
                Optional.of(new PlanCommand.Plan("计划", "", List.of(task), true)), "", "", "", List.of(), List.of());
        assertEquals("plan_command_conflict", assertThrows(IllegalArgumentException.class,
                () -> f.execute(f.run, "submit-1", changed)).getMessage());
        assertEquals(1, calls.size());
    }

    @Test
    void firstNewSubmitUsesCurrentApprovalModeWhenNoFrozenPlanExists() {
        var f = new Fixture();
        when(f.service.planMode(f.run.runRef())).thenReturn(true);
        var task = new PlanCommand.Task("P1", "分析", new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY, ""),
                new DelegationCommand.Task("分析", "", List.of(), "结果"), List.of(), Set.of("answer"), Set.of());
        var command = new PlanCommand(PlanCommand.Operation.SUBMIT, "", 0,
                Optional.of(new PlanCommand.Plan("计划", "", List.of(task))), "", "", "", List.of(), List.of());
        when(f.service.submitPlan(eq(f.run), anyString(), any(), eq(0L), anyString())).thenAnswer(call -> {
            DagPlan plan = call.getArgument(2);
            assertTrue(plan.approvalRequired());
            f.current.set(f.work(plan, Map.of(), 0));
            return DagWorkPort.Commit.APPLIED;
        });
        f.modelThrough = 0;
        assertTrue(f.execute(f.run, "new-submit", command).waitHandle().isPresent());
        assertTrue(f.current.get().plan().approvalRequired());
    }

    @Test
    void approvalRequiredSubmitUsesExactExistingWaitAndReplaysWithoutAnotherModelAction() {
        var f = new Fixture();
        f.modelThrough = 0;
        var task = new PlanCommand.Task("P1", "分析", new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY, ""),
                new DelegationCommand.Task("分析", "", List.of(), "结果"), List.of(), Set.of("answer"), Set.of());
        var command = new PlanCommand(PlanCommand.Operation.SUBMIT, "", 0,
                Optional.of(new PlanCommand.Plan("计划", "", List.of(task), true)), "", "", "", List.of(), List.of());
        when(f.service.submitPlan(eq(f.run), eq("plan-submit:approval-submit"), any(), eq(0L), anyString())).thenAnswer(call -> {
            if (f.current.get() == null) {
                f.current.set(f.work(call.getArgument(2), Map.of(), 0));
                return DagWorkPort.Commit.APPLIED;
            }
            return DagWorkPort.Commit.REPLAYED;
        });
        var submitted = f.execute(f.run, "approval-submit", command);
        assertEquals(Optional.of("delegation-wait:approval-submit"), submitted.waitHandle());
        assertFalse(f.host.state(f.run).executionApproved());
        assertTrue(f.current.get().executions().isEmpty());
        assertTrue(f.host.pollWait(f.run, submitted.waitHandle().orElseThrow()).isEmpty());
        var replayed = f.execute(f.run, "approval-submit", command);
        assertEquals(submitted, replayed);
        verify(f.log, times(1)).append(argThat(a -> a.event().eventId().value().equals("plan-wait:approval-submit")));
        var data = f.json.valueToTree(f.events.get(new EventId("plan-wait:approval-submit")).payload());
        assertTrue(data.toString().contains("plan_revision"));
        f.current.set(f.work(f.current.get().plan(), Map.of(), 1));
        assertTrue(f.host.pollWait(f.run, submitted.waitHandle().orElseThrow()).isPresent());
        assertTrue(f.execute(f.run, "approval-submit", command).waitHandle().isEmpty());
        verify(f.service, never()).startPrimary(any(), anyString(), anyString(), any());
    }

    @Test
    void steeringReleasesSubmitWaitWithoutApprovingPlan() {
        var f = new Fixture();
        f.modelThrough = 0;
        var task = new PlanCommand.Task("P1", "分析", new PlanCommand.Executor(PlanCommand.ExecutorType.PRIMARY, ""),
                new DelegationCommand.Task("分析", "", List.of(), "结果"), List.of(), Set.of("answer"), Set.of());
        var command = new PlanCommand(PlanCommand.Operation.SUBMIT, "", 0,
                Optional.of(new PlanCommand.Plan("计划", "", List.of(task), true)), "", "", "", List.of(), List.of());
        when(f.service.submitPlan(eq(f.run), anyString(), any(), eq(0L), anyString())).thenAnswer(call -> {
            f.current.set(f.work(call.getArgument(2), Map.of(), 0));
            return DagWorkPort.Commit.APPLIED;
        });
        var waiting = f.execute(f.run, "submit-steering", command).waitHandle().orElseThrow();
        f.supplement("new-steering");
        assertTrue(f.host.pollWait(f.run, waiting).isPresent());
        assertEquals(0, f.current.get().approvedRevision());
        assertTrue(f.current.get().executions().isEmpty());
        // The old wait also releases when a newer unapproved revision replaces its exact version.
        f.supplements.clear();
        f.current.set(f.work(new DagPlan(2, true, List.of(primary("P1", Set.of("answer")))), Map.of(), 0));
        assertTrue(f.host.pollWait(f.run, waiting).isPresent());
        assertFalse(f.host.state(f.run).executionApproved());
        var current = f.current.get();
        f.current.set(new DagWork(current.key(), current.coordinator(), current.revision(), DagWork.Status.CANCELLED,
                current.plan(), current.approvedRevision(), current.spentTokens(), current.executions(), current.consumedEvents(), current.request()));
        assertTrue(f.host.pollWait(f.run, waiting).isPresent());
    }

    @Test
    void revisionTwoAwaitingApprovalDoesNotRevokeAdmittedRevisionOneScope() {
        var f = new Fixture();
        var task = primary("P1", Set.of("answer"));
        var execution = f.running(task, 1, 9);
        f.current.set(f.work(new DagPlan(2, true, List.of(task)), Map.of("P1", execution), 1));
        var state = f.host.state(f.run);
        assertTrue(state.executionApproved());
        assertEquals(Optional.of("P1"), state.activeTaskId());
        f.current.set(f.work(new DagPlan(2, true, List.of(task)), Map.of(), 1));
        assertFalse(f.host.state(f.run).executionApproved());
    }

    @Test
    void waitWithoutRevisionPersistsAndReplaysButRejectsPrimarySelfWait() {
        var f = new Fixture();
        var member = new DagPlan.Task("M1", "调查", new AgentId("8"), "调查", List.of(), Set.of("answer"), Set.of());
        f.current.set(f.work(new DagPlan(3, false, List.of(member)), Map.of(), 0));
        f.modelThrough = 0;
        var command = new PlanCommand(PlanCommand.Operation.WAIT, "input", 0, Optional.empty(), "", "", "", List.of(), List.of("M1"));
        assertEquals(Optional.of("delegation-wait:wait-1"), f.execute(f.run, "wait-1", command).waitHandle());
        assertEquals(Optional.of("delegation-wait:wait-1"), f.execute(f.run, "wait-1", command).waitHandle());
        assertEquals(1, f.events.size());
        f.current.set(f.work(new DagPlan(3, false, List.of(primary("P1", Set.of("answer")))), Map.of(), 0));
        var self = new PlanCommand(PlanCommand.Operation.WAIT, "input", 0, Optional.empty(), "", "", "", List.of(), List.of("P1"));
        var rejected=f.execute(f.run, "wait-self", self);
        assertEquals("rejected",rejected.value().get("status"));
        assertEquals("plan_wait_self_deadlock",rejected.value().get("reason"));
        assertTrue(rejected.waitHandle().isEmpty());
        assertNotNull(rejected.value().get("tasks"));
        assertEquals(1, f.events.size());
    }

    @Test
    void finishRejectsReferenceFromAnotherStepOfSameRunBeforePersistence() {
        var f = new Fixture();
        var task = primary("P2", Set.of("answer"));
        f.current.set(f.work(new DagPlan(1, false, List.of(task)), Map.of("P2", f.running(task, 1, 9)), 0));
        f.action("action-1", "P1", "result-one", ExecutionStore.ActionStatus.SUCCEEDED, "{}");
        var command = finish("P2", Map.of("answer", "错误引用"), List.of(new ResultRef("result-one")));
        var rejected = f.execute(f.run, "finish-cross", command).value();
        assertEquals("rejected", rejected.get("status"));
        assertEquals("plan_result_not_owned_or_succeeded", rejected.get("reason"));
        assertEquals(List.of(), ((Map<?, ?>) rejected.get("current_primary_step_evidence")).get("successful_result_refs"));
        verify(f.service, never()).finishPrimary(any(), any());
        assertTrue(f.events.isEmpty());
    }

    @Test void finishUnstartedMemberOrStaleRevisionReturnsFeedbackWithoutAnyCompletionWrite() {
        for(var variant:List.of("unstarted","member","stale")) {
            var f=new Fixture(); var primary=primary("P1",Set.of("answer"));
            var member=new DagPlan.Task("M1","member",new AgentId("8"),"member",List.of(),Set.of("answer"),Set.of());
            var memberExecution=new DagWork.Execution(new DagWork.Binding("admission-M1",1,member,
                    new RunRef(f.run.runRef().tenantId(),"member-run"),Map.of()),DagWork.TaskStatus.RUNNING,
                    Optional.of(new DagWork.Permit(9,Instant.parse("2026-10-19T01:00:00Z"))),false,0,Map.of(),"running",Optional.empty());
            Map<String,DagWork.Execution> running=switch(variant) {
                case "unstarted" -> Map.of();
                case "member" -> Map.of("M1",memberExecution);
                default -> Map.of("P1",f.running(primary,1,9));
            };
            f.current.set(f.work(new DagPlan(1,false,List.of(primary,member)),running,0));
            var command=new PlanCommand(PlanCommand.Operation.FINISH_STEP,"input",variant.equals("stale")?2:1,
                    Optional.empty(),variant.equals("member")?"M1":"P1","completed","finish",List.of(),List.of(),Map.of("answer","answer"));
            var reply=f.execute(f.run,"invalid-finish-"+variant,command);
            assertEquals("rejected",reply.value().get("status"));
            assertEquals(switch(variant) {
                case "unstarted" -> "plan_step_not_started";
                case "member" -> "plan_member_finish_forbidden";
                default -> "plan_revision_conflict";
            },reply.value().get("reason"));
            assertTrue(reply.waitHandle().isEmpty()); assertNotNull(reply.value().get("tasks"));
            assertTrue(f.events.isEmpty()); assertEquals(running,f.current.get().executions());
            verify(f.service,never()).finishPrimary(any(),any());
        }
    }

    @Test void unknownWaitTaskReturnsCurrentSnapshotWithoutRegisteringOrRewritingAWait() {
        var f=new Fixture(); var member=new DagPlan.Task("M1","member",new AgentId("8"),"member",List.of(),Set.of("answer"),Set.of());
        f.current.set(f.work(new DagPlan(1,false,List.of(member)),Map.of(),0));
        var reply=f.execute(f.run,"unknown-wait",new PlanCommand(PlanCommand.Operation.WAIT,"input",0,
                Optional.empty(),"","","",List.of(),List.of("missing")));
        assertEquals("rejected",reply.value().get("status"));
        assertEquals("plan_step_not_found",reply.value().get("reason"));
        assertTrue(reply.waitHandle().isEmpty()); assertNotNull(reply.value().get("tasks"));
        assertTrue(f.events.isEmpty());
        var legitimate=new PlanCommand(PlanCommand.Operation.WAIT,"input",0,Optional.empty(),"","","",List.of(),List.of("M1"));
        assertTrue(f.execute(f.run,"original-wait",legitimate).waitHandle().isPresent());
        assertEquals("plan_command_conflict",assertThrows(IllegalArgumentException.class,()->f.execute(f.run,"original-wait",
                new PlanCommand(PlanCommand.Operation.WAIT,"input",0,Optional.empty(),"","","",List.of(),List.of("missing")))).getMessage());
        assertEquals(1,f.events.size());
    }

    @Test void finishOutputMismatchReturnsSnapshotWithoutFinishingOrChangingOriginalPermit() {
        var f=new Fixture(); var task=primary("P1",Set.of("draft","sources"));
        var execution=f.running(task,1,9);
        f.current.set(f.work(new DagPlan(1,false,List.of(task)),Map.of("P1",execution),0));
        for(var outputs:List.of(Map.of("draft","draft"),Map.of("draft","draft","sources","sources","extra","extra"))) {
            var reply=f.execute(f.run,"bad-output-"+outputs.size(),finish("P1",outputs,List.of()));
            assertEquals("rejected",reply.value().get("status"));
            assertEquals(outputs.size()==1?"plan_step_outputs_incomplete":"plan_output_undeclared",reply.value().get("reason"));
            assertTrue(reply.waitHandle().isEmpty()); assertNotNull(reply.value().get("dependency_outputs"));
            assertEquals(execution,f.current.get().executions().get("P1"));
        }
        assertTrue(f.events.isEmpty()); verify(f.service,never()).finishPrimary(any(),any());
        when(f.service.finishPrimary(any(),any())).thenReturn(DagWorkPort.Commit.APPLIED);
        f.execute(f.run,"correct-output",finish("P1",Map.of("draft","draft","sources","sources"),List.of()));
        verify(f.service).finishPrimary(eq(f.current.get().key()),argThat(value->value.fence()==9 && value.taskId().equals("P1")));
        assertEquals(1,f.events.size());
    }

    @Test void waitingForMemberDependingOnPendingPrimaryReturnsFeedbackWithoutRegisteringWait() {
        var f=new Fixture(); var primary=primary("P1",Set.of("draft"));
        var member=new DagPlan.Task("M1","member",new AgentId("8"),"member",
                List.of(new DagPlan.Input("draft","P1","draft")),Set.of("answer"),Set.of());
        f.current.set(f.work(new DagPlan(1,false,List.of(primary,member)),Map.of(),0));
        var reply=f.execute(f.run,"indirect-self-wait",new PlanCommand(PlanCommand.Operation.WAIT,"input",0,
                Optional.empty(),"","","",List.of(),List.of("M1")));
        assertEquals("rejected",reply.value().get("status"));
        assertEquals("plan_wait_self_deadlock",reply.value().get("reason"));
        assertTrue(reply.waitHandle().isEmpty()); assertTrue(f.events.isEmpty());
        assertTrue(f.current.get().executions().isEmpty());
    }

    @Test
    void finishPersistsIndependentNamedOutputsAndReplaysOriginalFenceAfterPermitGone() throws Exception {
        var f = new Fixture();
        var task = primary("P1", Set.of("分析", "核查/结果"));
        var execution = f.running(task, 1, 17);
        f.current.set(f.work(new DagPlan(1, false, List.of(task)), Map.of("P1", execution), 0));
        var completions = new ArrayList<DagCoordinator.Completion>();
        when(f.service.finishPrimary(any(), any())).thenAnswer(call -> {
            DagCoordinator.Completion completion = call.getArgument(1);
            completions.add(completion);
            var done = new DagWork.Execution(execution.binding(), DagWork.TaskStatus.COMPLETED, Optional.empty(), false,
                    0, completion.outputs(), completion.phase(), Optional.of(completion.sourceEvent()));
            f.current.set(f.work(f.current.get().plan(), Map.of("P1", done), 0));
            return completions.size() == 1 ? DagWorkPort.Commit.APPLIED : DagWorkPort.Commit.REPLAYED;
        });
        var values = Map.of("分析", "各项统计", "核查/结果", "来源核对");
        var command = finish("P1", values, List.of());
        f.execute(f.run, "finish-1", command);
        f.execute(f.run, "finish-1", command);
        assertEquals(2, completions.size());
        assertEquals(completions.getFirst(), completions.getLast());
        assertEquals(17, completions.getLast().fence());
        assertEquals(1, f.events.size());
        for (var item : values.entrySet()) {
            var output = completions.getFirst().outputs().get(item.getKey());
            assertEquals("plan-finish:finish-1#" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(item.getKey().getBytes(StandardCharsets.UTF_8)), output.resultRef().value());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(item.getValue().getBytes(StandardCharsets.UTF_8))), output.digest());
        }
        assertNotEquals(completions.getFirst().outputs().get("分析"), completions.getFirst().outputs().get("核查/结果"));
    }

    private static PlanCommand finish(String id, Map<String, String> outputs, List<ResultRef> refs) {
        return new PlanCommand(PlanCommand.Operation.FINISH_STEP, "input", 1, Optional.empty(), id,
                "completed", "完成分析与核查", refs, List.of(), outputs);
    }

    private static DagPlan.Task primary(String id, Set<String> outputs) {
        return new DagPlan.Task(id, "主步骤", new AgentId("7"), DagPlan.ExecutorType.PRIMARY,
                "分析", List.of(), outputs, Set.of());
    }

    private static final class Fixture {
        final JdbcDagWorkPort works = mock(JdbcDagWorkPort.class);
        final TeamWorkService service = mock(TeamWorkService.class);
        final TeamWorkView views = mock(TeamWorkView.class);
        final TeamWorkInputs inputs = mock(TeamWorkInputs.class);
        final AgentDelegationHost delegates = mock(AgentDelegationHost.class);
        final JdbcSessionLogPort log = mock(JdbcSessionLogPort.class);
        final ExecutionStore executions = mock(ExecutionStore.class);
        final AgentRunBindings.Binding binding = mock(AgentRunBindings.Binding.class);
        final AgentExecutionCodec codec = new AgentExecutionCodec(new CodecLimits(1000000, 100000, 64));
        final List<ExecutionStore.Action> actions = new ArrayList<>();
        final List<ExecutionStore.SupplementalInput> supplements = new ArrayList<>();
        long modelThrough;
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final SessionOwner owner = SessionOwner.user(new SessionRef(new TenantId("1"), "session"),
                SessionTarget.agent(new AgentId("7")), new EndUserId(9));
        final RunIdentity run = new RunIdentity(owner, new AgentId((owner).target().id()), new InputId("input"), new RunRef(new TenantId("1"), "run"), new TraceEnvelope("trace", "span", new InputId("input"), Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (new InputId("input")).value()));
        final AtomicReference<DagWork> current = new AtomicReference<>();
        final Map<EventId, CommittedSessionEvent> events = new LinkedHashMap<>();
        final InstitutionPlanHost host;

        Fixture() {
            var bindings = mock(AgentRunBindings.class);
            when(bindings.withContinuationRequester(eq(owner), any())).thenAnswer(call ->
                    ((java.util.function.Supplier<?>) call.getArgument(1)).get());

            when(bindings.find(run.runRef())).thenReturn(Optional.of(binding));
            when(binding.owner()).thenReturn(owner);
            when(binding.actor()).thenReturn(run.agentId());
            when(binding.role()).thenReturn(AgentRunBindings.Role.PRIMARY);
            when(works.read(new DagWork.Key(owner, run.causationInputId()))).thenAnswer(call -> Optional.ofNullable(current.get()));
            when(views.view(any())).thenReturn(new TeamPublicViews.Work("input", "session", 1, "input", "run", 1L,
                    "RUNNING", "working", "active", 1, 0L, false, "计划", new TeamPublicViews.Member("7", "主 Agent"),
                    List.of(), List.of(), List.of(), null, 0, List.of(), "计划", ""));
            when(log.findByEvent(eq(owner), any())).thenAnswer(call -> Optional.ofNullable(events.get(call.getArgument(1))));
            when(log.readAfter(any(SessionLogPort.ReadSessionLog.class), eq(run.runRef()), anySet())).thenAnswer(call -> {
                SessionLogPort.ReadSessionLog query = call.getArgument(0);
                Set<String> types = call.getArgument(2);
                return events.values().stream().filter(e -> types.contains(e.eventType())
                        && e.sessionSequence() > query.afterSequence()).limit(query.limit()).toList();
            });
            when(log.append(any())).thenAnswer(call -> {
                SessionLogPort.AppendSessionEvent append = call.getArgument(0);
                var draft = append.event();
                var existing = events.get(draft.eventId());
                if (existing != null) return new SessionLogPort.AppendResult.Replayed(existing);
                var event = event(draft.eventId().value(), draft.eventType(), Optional.empty(), draft.payload().canonicalJson());
                events.put(event.eventId(), event);
                return new SessionLogPort.AppendResult.Appended(event);
            });
            when(delegates.targets(run)).thenReturn(List.of(new com.skillforge.runtime.session.spi.SessionDelegations.Target(
                    "member:8", new AgentId("8"), "成员", "", List.of(), new TypedPayload("test", "{}"))));
            when(delegates.targets(run, binding)).thenAnswer(call -> delegates.targets(run));
            var stored = mock(ExecutionStore.Run.class);
            when(stored.identity()).thenReturn(run);
            when(executions.load(run.runRef())).thenReturn(Optional.of(stored));
            when(executions.actions(eq(run.runRef()), anyLong(), anyInt())).thenAnswer(call -> actions.stream()
                    .filter(action -> action.ordinal() > (long) call.getArgument(1)).limit((int) call.getArgument(2)).toList());
            when(executions.inputs(eq(run.runRef()), anyLong(), eq(false), anyInt())).thenAnswer(call -> supplements.stream()
                    .filter(input -> input.ordinal() > (long) call.getArgument(1)).limit((int) call.getArgument(3)).toList());
            when(works.participate(any(), eq(run.runRef().tenantId()), any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(2)).get());
            when(log.participate(any(), eq(run.runRef().tenantId()), any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(2)).get());
            when(inputs.recentBorrowed(any(), any())).thenAnswer(call -> inputs.recent(call.getArgument(1)));
            host = new InstitutionPlanHost(works, provider(service), provider(delegates), bindings,
                    inputs, log, json, Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC), executions, codec);
        }

        DagWork work(DagPlan plan, Map<String, DagWork.Execution> executions, long approved) {
            return new DagWork(new DagWork.Key(owner, run.causationInputId()), run.agentId(), 2, DagWork.Status.ACTIVE,
                    plan, approved, 0, executions, Map.of(), new DagWork.Request("分析", Optional.empty(), run.runRef(), ""));
        }

        DagWork.Execution running(DagPlan.Task task, long revision, long fence) {
            return new DagWork.Execution(new DagWork.Binding("admission-" + task.id(), revision, task, run.runRef(), Map.of()),
                    DagWork.TaskStatus.RUNNING, Optional.of(new DagWork.Permit(fence, Instant.parse("2026-10-19T01:00:00Z"))),
                    false, 0, Map.of(), "执行", Optional.empty());
        }

        PlanPort.Reply execute(RunIdentity identity, String operation, PlanCommand command) {
            return host.participate(mock(SqlSession.class), identity, binding, modelThrough, () -> host.execute(identity, operation, command));
        }

        void dispatch(ActionRef action, boolean readOnly, boolean finalizeOutputs, boolean delegateStart) {
            host.participate(mock(SqlSession.class), run, binding, modelThrough, () -> {
                host.requireDispatch(run, action, ExecutionStore.Kind.TOOL, readOnly, finalizeOutputs, delegateStart);
                return null;
            });
        }

        void runningPlan(String taskId) {
            var task = primary(taskId, Set.of("answer"));
            current.set(work(new DagPlan(1, false, List.of(task)), Map.of(taskId, running(task, 1, 9)), 0));
        }

        void supplement(String id) {
            supplements.add(new ExecutionStore.SupplementalInput(run.runRef(), new InputId(id), new ResultRef("input:" + id),
                    "fingerprint:" + id, new ResultRef("actor:" + id), supplements.size() + 1, OptionalLong.empty()));
        }

        void action(String id, String taskId, String resultId, ExecutionStore.ActionStatus status, String envelope) {
            var reference = new ActionRef(run.runRef().tenantId(), id);
            String attemptId = "attempt:" + id;
            boolean success = status == ExecutionStore.ActionStatus.SUCCEEDED;
            var result = new ResultRef(resultId);
            var request = new ResultRef("request:" + id);
            actions.add(new ExecutionStore.Action(reference, run.runRef(), actions.size() + 1, ExecutionStore.Kind.TOOL, "tool", new ResultRef("capability:" + id),
                    request, "fingerprint:" + id, status, attemptId, success ? Optional.of(attemptId) : Optional.empty(), Optional.empty()));
            when(works.taskForAction(run.runRef(), id)).thenReturn(Optional.of(taskId));
            if (!success) return;
            var attempt = new ExecutionStore.Attempt(attemptId, reference, run.runRef(), 1, request, Optional.empty(),
                    Optional.of(ExecutionStore.ResultKind.SUCCEEDED), Optional.of(result), Optional.empty(), Optional.empty(), Optional.empty(), true);
            when(executions.attempt(run.runRef(), attemptId)).thenReturn(Optional.of(attempt));
            var payloads = new SessionRuntimePayloads();
            var core = new CoreRuntime(new CodecLimits(1000000, 100000, 64), payloads);
            var observation = json.createObjectNode(); observation.put("resultType", "complete"); observation.put("isError", false);
            observation.putArray("content"); observation.putObject("structuredContent");
            try { observation.setAll((com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(envelope)); }
            catch (java.io.IOException invalidFixture) { throw new AssertionError(invalidFixture); }
            var actual = new ToolPortal.ToolOutcome.Succeeded(new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, observation.toString()), result, List.of());
            var material = new ExecutionStore.Material(result, codec.encodeOutcome(core.portalResults(payloads, payloads, payloads).encodeTool(PortalCallKind.TOOL_INVOKE, actual)));
            when(executions.material(run.runRef().tenantId(), result)).thenReturn(Optional.of(material));
        }

        CommittedSessionEvent event(String id, String type, Optional<ActionRef> action, String payload) {
            return new CommittedSessionEvent(owner, run.agentId(), run.causationInputId(), Optional.of(run.runRef()),
                    Optional.empty(), action, Optional.empty(), new EventId(id), events.size() + 1, type, "1", run.trace(),
                    new TypedPayload("test.v1", payload), Instant.parse("2026-10-19T00:00:00Z"));
        }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked") var provider = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }
}
