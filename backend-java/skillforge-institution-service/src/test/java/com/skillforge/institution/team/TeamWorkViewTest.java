package com.skillforge.institution.team;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.runtime.TeamRuntimeBridge;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.dag.DagPlan;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.port.SessionOwner;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TeamWorkViewTest {
    private static final TenantId TENANT = new TenantId("7");
    private static final AgentId COORDINATOR = new AgentId("101");
    private static final AgentId MEMBER = new AgentId("102");
    private static final DagWork.Key KEY = new DagWork.Key(
            SessionOwner.user(new SessionRef(TENANT, "session-1"), SessionTarget.team("team-1"), new EndUserId(8L)),
            new InputId("input-1"));

    private final AgentRunBindings bindings = mock(AgentRunBindings.class);
    private final TeamResultService results = mock(TeamResultService.class);
    private final TeamRuntimeBridge runtime = mock(TeamRuntimeBridge.class);
    private final com.skillforge.core.chat.integration.publicread.PublicOutputGuard guard = mock(com.skillforge.core.chat.integration.publicread.PublicOutputGuard.class);
    private final TeamWorkView view = new TeamWorkView(bindings, results, runtime,guard,new com.fasterxml.jackson.databind.ObjectMapper());

    @org.junit.jupiter.api.BeforeEach void publicScope() { when(guard.capture(org.mockito.ArgumentMatchers.any(SessionOwner.class))).thenReturn(new com.skillforge.core.chat.integration.publicread.PublicOutputGuard.Scope(List.of())); }

    @Test
    void completedMembersKeepParentIdentityAndSummarizingUntilParentTerminates() {
        var active = work(plan(), 1, Map.of(), DagWork.Request.Stage.WORKING, "");
        var work = new DagWork(active.key(), active.coordinator(), 3, DagWork.Status.COMPLETED,
                active.plan(), active.approvedRevision(), 0, Map.of(), Map.of(), active.request());
        names();
        when(results.view(work)).thenReturn(List.of());
        var record = mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);
        when(record.status()).thenReturn(com.skillforge.runtime.api.RunStatus.RUNNING);
        when(record.revision()).thenReturn(8L);
        when(runtime.fact(work, work.request().controlRun())).thenReturn(Optional.of(record));

        var snapshot = view.view(work);
        assertEquals("input-1", snapshot.inputId());
        assertEquals("control-1", snapshot.runId());
        assertEquals("completed", snapshot.status());
        assertEquals("running", snapshot.runStatus());
        assertEquals(8L, snapshot.runRevision());
        assertEquals("summarizing", snapshot.stage());

        when(record.status()).thenReturn(com.skillforge.runtime.api.RunStatus.COMPLETED);
        when(record.revision()).thenReturn(9L);
        snapshot = view.view(work);
        assertEquals(3L, snapshot.revision());
        assertEquals(9L, snapshot.runRevision());
        assertEquals("finished", snapshot.stage());
        when(record.status()).thenReturn(com.skillforge.runtime.api.RunStatus.FAILED);
        assertEquals("failed", view.view(work).stage());
        when(record.status()).thenReturn(com.skillforge.runtime.api.RunStatus.CANCELLED);
        assertEquals("cancelled", view.view(work).stage());
    }

    @Test
    void ordinaryUnplannedWorkDoesNotInventTasksOrRouting() {
        DagWork work = work(null, 0, Map.of(), DagWork.Request.Stage.WORKING, "交给人工处理");
        when(results.view(work)).thenReturn(List.of());
        names();

        var snapshot = view.view(work);

        assertEquals(List.of(), snapshot.tasks());
        assertEquals("working", snapshot.stage());
        org.junit.jupiter.api.Assertions.assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(snapshot).has("notice"));
        assertEquals("交给人工处理", snapshot.handoff());
        assertEquals("", snapshot.changeReason());
        assertEquals("queued", snapshot.status());
    }

    @Test
    void planChangeReasonIsPublicAndPreservedWhenCollectionsAreRefreshed() {
        names();
        var revised=new DagPlan(2,false,plan().tasks(),"核查计划","补充来源核查后再汇总");
        var work=work(revised,2,Map.of(),DagWork.Request.Stage.WORKING,"");
        when(results.view(work)).thenReturn(List.of());
        var snapshot=view.view(work);
        assertEquals(revised.changeReason(),snapshot.changeReason());
        var refreshed=snapshot.withCollections(List.of(),snapshot.tasks(),List.of());
        assertEquals(revised.changeReason(),refreshed.changeReason());
        var json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(refreshed);
        assertEquals(revised.changeReason(),json.path("change_reason").asText());
    }

    @Test
    void planChangeReasonUsesTheSamePublicOutputGuardAsTitle() {
        names();
        String privatePath="private-plan-root";
        when(guard.capture(org.mockito.ArgumentMatchers.any(SessionOwner.class))).thenReturn(
                new com.skillforge.core.chat.integration.publicread.PublicOutputGuard.Scope(List.of(privatePath)));
        var revised=new DagPlan(2,false,plan().tasks(),"核查计划", "从"+privatePath+"读取资料");
        var work=work(revised,2,Map.of(),DagWork.Request.Stage.WORKING,"");
        when(results.view(work)).thenReturn(List.of());
        var snapshot=view.view(work);
        assertEquals("核查计划",snapshot.title());
        assertEquals(com.skillforge.core.chat.integration.publicread.PublicOutputGuard.SAFE_MESSAGE,snapshot.changeReason());
    }

    @Test
    void coordinatorPendingInteractionIsProjectedAtTheWorkLevel() {
        DagWork work = work(null, 0, Map.of(), DagWork.Request.Stage.ROUTING, "");
        when(results.view(work)).thenReturn(List.of());
        names();
        when(runtime.pending(work, work.request().controlRun())).thenReturn(Optional.of(
                pending(work.request().controlRun(),4,"pending-control","请选择负责人")));

        var snapshot = view.view(work);
        var pending = snapshot.pending();

        assertEquals("input", pending.kind());
        assertEquals("pending-control:1", pending.pendingId());
        assertEquals("4", pending.parametersVersion());
    }

    @Test
    void unapprovedPlanKeepsTasksPendingWithDependenciesAndVisibleResultIds() {
        DagPlan plan = plan();
        DagWork work = work(plan, 0, Map.of(), DagWork.Request.Stage.WORKING, "");
        when(results.view(work)).thenReturn(List.of(new TeamPublicViews.Result("result-1","task-1","报告","text","ready","","",List.of(),List.of(),null)));
        names();

        var snapshot = view.view(work);
        var tasks = snapshot.tasks();
        var members = snapshot.members();

        assertEquals("pending", tasks.get(0).status());
        assertEquals("等待计划审批", tasks.get(0).waitReason());
        assertEquals(List.of("result-1"), tasks.get(0).resultIds());
        assertEquals("协调员（冻结）", tasks.get(0).agentName());
        assertEquals(List.of("task-1"), tasks.get(1).dependsOn());
        assertEquals("pending", tasks.get(1).status());
        assertEquals("成员（冻结）", tasks.get(1).agentName());
        assertEquals(java.util.Set.of("101", "102"), members.stream()
                .map(TeamPublicViews.Member::agentId).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void unadmittedCancelledTaskDoesNotClaimAnExecutionAndBlocksDependents() {
        DagPlan.Task cancelled = new DagPlan.Task("task-1", "task-1", COORDINATOR, "完成task-1", "", List.of(), "完成声明的产出",
                List.of(), java.util.Set.of("answer"), java.util.Set.of(), true, "");
        DagPlan.Task dependent = task("task-2", MEMBER, true, "task-1");
        DagWork work = work(new DagPlan(1, false, List.of(cancelled, dependent)), 1, Map.of(),
                DagWork.Request.Stage.WORKING, "");
        when(results.view(work)).thenReturn(List.of());
        names();

        var snapshot = view.view(work);
        var tasks = snapshot.tasks();

        assertEquals("cancelled", tasks.get(0).status());
        assertEquals(null, tasks.get(0).runId());
        assertEquals("blocked", tasks.get(1).status());
        assertEquals("前置任务已取消，需要调整后续计划", tasks.get(1).waitReason());
    }

    @Test
    void blockedDescendantsHaveNoInventedRunAndPreserveUnknownVersusFailedReason() {
        names();
        for (var outcome : List.of(DagWork.TaskStatus.FAILED, DagWork.TaskStatus.CANCELLED,
                DagWork.TaskStatus.OUTCOME_UNKNOWN)) {
            var first=task("task-1", COORDINATOR, false);
            var plan=new DagPlan(1,false,List.of(first,task("task-2",MEMBER,false,"task-1"),
                    task("task-3",MEMBER,false,"task-2")));
            var initial=execution(first,"run-1");
            var completed=new DagWork.Execution(initial.binding(),outcome,Optional.empty(),false,0,Map.of(),"",Optional.empty());
            var work=work(plan,0,Map.of(first.id(),completed),DagWork.Request.Stage.WORKING,"");
            when(results.view(work)).thenReturn(List.of());
            var parent=mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);
            when(parent.status()).thenReturn(com.skillforge.runtime.api.RunStatus.RUNNING);
            when(runtime.fact(work,work.request().controlRun())).thenReturn(Optional.of(parent));
            var snapshot=view.view(work);
            assertEquals(outcome.name().toLowerCase(java.util.Locale.ROOT),snapshot.tasks().getFirst().status());
            assertEquals("summarizing",snapshot.stage());
            String reason=switch(outcome) {
                case FAILED -> "前置任务失败，需要调整后续计划";
                case CANCELLED -> "前置任务已取消，需要调整后续计划";
                case OUTCOME_UNKNOWN -> "前置任务结果待核实，不能重复执行";
                default -> throw new AssertionError();
            };
            for(var blocked:snapshot.tasks().subList(1,3)) {
                assertEquals("blocked",blocked.status());
                assertEquals(reason,blocked.waitReason());
                assertEquals(null,blocked.runId());
                assertEquals(TeamPublicViews.DetailsAccess.NOT_STARTED,blocked.detailsAccess());
            }
        }
    }

    @Test
    void waitingTaskProjectsAgentAndPlanReviewInteractions() {
        DagPlan plan = plan();
        DagWork.Execution execution = execution(plan.tasks().getFirst(), "run-1");
        DagWork work = work(plan, 1, Map.of("task-1", execution), DagWork.Request.Stage.WORKING, "");
        when(results.view(work)).thenReturn(List.of());
        names();
        when(runtime.pending(work, execution.binding().runRef())).thenReturn(Optional.of(
                pending(execution.binding().runRef(),4,"pending-1","请补充资料")));

        var snapshot = view.view(work);
        var tasks = snapshot.tasks();
        var pending = tasks.getFirst().pending();
        assertEquals("input", pending.kind());
        assertEquals("4", pending.parametersVersion());
        assertEquals("请补充资料", pending.prompt());

        when(runtime.pending(work, execution.binding().runRef())).thenReturn(Optional.of(
                new TeamRuntimeBridge.Pending(execution.binding().runRef(),new com.skillforge.runtime.session.spi.ExecutionStore.WaitRef(
                        new com.skillforge.runtime.api.port.ActionRef(TENANT,"plan-action"),1),5,
                        com.skillforge.runtime.session.spi.ExecutionStore.WaitKind.PLAN,new com.skillforge.runtime.api.TypedPayload("plan","{}"))));
        snapshot = view.view(work);
        assertEquals(null,snapshot.tasks().getFirst().pending(),"内部计划依赖等待不能冒充普通HUMAN交互");
    }

    @Test
    void resultAuthorizationFailureIsPropagated() {
        DagWork work = work(plan(), 1, Map.of(), DagWork.Request.Stage.WORKING, "");
        ApiException denied = new ApiException(HttpStatus.FORBIDDEN, "无权读取成果");
        when(results.view(work)).thenThrow(denied);

        assertThrows(ApiException.class, () -> view.view(work));
    }

    @Test void unstartedPrimaryWaitsForPrimaryStartAfterApprovalWithoutClaimingAdmission() {
        var task = new DagPlan.Task("self-step", "核对资料", COORDINATOR, DagPlan.ExecutorType.PRIMARY,
                "核对来源", List.of(), java.util.Set.of("answer"), java.util.Set.of());
        var plan = new DagPlan(1, true, List.of(task));
        names();
        var unapproved = work(plan, 0, Map.of(), DagWork.Request.Stage.WORKING, "");
        when(results.view(unapproved)).thenReturn(List.of());
        assertEquals("等待计划审批", view.view(unapproved).tasks().getFirst().waitReason());
        var approved = work(plan, 1, Map.of(), DagWork.Request.Stage.WORKING, "");
        when(results.view(approved)).thenReturn(List.of());
        var projected = view.view(approved).tasks().getFirst();
        assertEquals("等待主助手开始步骤", projected.waitReason());
        assertEquals("pending", projected.status());
        assertEquals(null, projected.runId());
        assertEquals(TeamPublicViews.DetailsAccess.NOT_STARTED, projected.detailsAccess());
    }

    @Test void primaryTaskUsesItsOwnCompletionSummaryAndExplicitExecutorContract() {
        var task=new DagPlan.Task("self-step","核对资料",COORDINATOR,DagPlan.ExecutorType.PRIMARY,"核对来源", "",List.of("保留未知"),"核对结论",List.of(),java.util.Set.of("answer"),java.util.Set.of(),false,"");
        var execution=new DagWork.Execution(new DagWork.Binding("primary-admission",1,task,new RunRef(TENANT,"control-1"),Map.of()),
                DagWork.TaskStatus.COMPLETED,Optional.empty(),false,0,Map.of(),"已完成来源核查",Optional.empty());
        var work=work(new DagPlan(1,false,List.of(task)),1,Map.of(task.id(),execution),DagWork.Request.Stage.WORKING,"");
        names();when(results.view(work)).thenReturn(List.of(new TeamPublicViews.Result("event-self",task.id(),task.title(),"text","succeeded","已完成来源核查","已完成来源核查",List.of(),List.of(),null)));
        var parent=mock(com.skillforge.runtime.session.spi.ExecutionStore.Run.class);
        when(parent.status()).thenReturn(com.skillforge.runtime.api.RunStatus.RUNNING);
        when(parent.revision()).thenReturn(8L);
        when(runtime.fact(work,work.request().controlRun())).thenReturn(Optional.of(parent));
        var snapshot=view.view(work);
        assertEquals("active",snapshot.status());assertEquals("summarizing",snapshot.stage());
        assertEquals("任务计划",snapshot.title());
        assertEquals("PRIMARY",snapshot.tasks().getFirst().executorType());
        assertEquals("核对来源",snapshot.tasks().getFirst().goal());
        assertEquals("核对结论",snapshot.tasks().getFirst().expectedOutput());
        assertEquals(List.of("保留未知"),snapshot.tasks().getFirst().constraints());
        assertEquals("completed",snapshot.tasks().getFirst().status());
        assertEquals("已完成来源核查",snapshot.results().getFirst().summary());
        assertEquals(null,snapshot.tasks().getFirst().pending());
    }

    @Test
    void reservedMemberBeforeChildBindingExistsHasNoPendingCardAndDoesNotBreakWorkView() {
        names();
        var task = task("member-starting", MEMBER, false);
        var child = new RunRef(TENANT, "child-not-provisioned-yet");
        var execution = new DagWork.Execution(new DagWork.Binding("reserved-admission", 1, task, child, Map.of()),
                DagWork.TaskStatus.RESERVED, Optional.of(new DagWork.Permit(1, java.time.Instant.now().plusSeconds(60))),
                false, 0, Map.of(), "", Optional.empty());
        var work = work(new DagPlan(1, false, List.of(task)), 0, Map.of(task.id(), execution),
                DagWork.Request.Stage.WORKING, "");
        when(results.view(work)).thenReturn(List.of());
        when(bindings.find(child)).thenReturn(Optional.empty());

        var snapshot = view.view(work);

        assertEquals("pending", snapshot.tasks().getFirst().status());
        assertEquals(child.runId(), snapshot.tasks().getFirst().runId());
        assertEquals(null, snapshot.tasks().getFirst().pending());
        org.mockito.Mockito.verify(runtime, org.mockito.Mockito.never()).pending(work, child);
    }

    private void names() {
        var binding = mock(AgentRunBindings.Binding.class);
        var scope = mock(AgentRunBindings.Scope.class);
        when(bindings.find(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(binding));
        when(binding.owner()).thenReturn(KEY.owner());
        when(binding.scope()).thenReturn(scope);
        when(scope.directory()).thenReturn(Map.of(
                "101", member("101", "协调员（冻结）"),
                "102", member("102", "成员（冻结）"),
                "104", member("104", "已移除成员")));
        org.mockito.Mockito.doThrow(new SecurityException("revoked"))
                .when(bindings).authorizeRead(binding, "104");
    }

    private static TeamDefinition.Member member(String id, String name) {
        return new TeamDefinition.Member(id, "1", name, "角色", List.of());
    }

    private static DagPlan plan() {
        return new DagPlan(1, true, List.of(
                task("task-1", COORDINATOR, false),
                task("task-2", MEMBER, true, "task-1")));
    }

    private static DagPlan.Task task(String id, AgentId agent, boolean finalAnswer, String... dependencies) {
        return new DagPlan.Task(id, id, agent, "完成" + id,
                java.util.Arrays.stream(dependencies)
                        .map(source -> new DagPlan.Input(source, source, "answer")).toList(),
                java.util.Set.of("answer"), java.util.Set.of());
    }

    private static DagWork.Execution execution(DagPlan.Task task, String runId) {
        RunRef run = new RunRef(TENANT, runId);
        return new DagWork.Execution(new DagWork.Binding("admission-" + task.id(), 1, task, run, Map.of()),
                DagWork.TaskStatus.WAITING, Optional.empty(), false, 0, Map.of(), "review", Optional.empty());
    }

    private static DagWork work(DagPlan plan, long approvedRevision, Map<String, DagWork.Execution> executions,
            DagWork.Request.Stage stage, String handoff) {
        return new DagWork(KEY, COORDINATOR, 1, plan == null ? DagWork.Status.QUEUED : DagWork.Status.ACTIVE,
                plan, approvedRevision, 0, executions, Map.of(),
                new DagWork.Request("分析请求", Optional.empty(), new RunRef(TENANT, "control-1"), handoff, stage));
    }
    private static TeamRuntimeBridge.Pending pending(RunRef run,long revision,String action,String question) {
        var wait=new com.skillforge.runtime.session.spi.ExecutionStore.WaitRef(new com.skillforge.runtime.api.port.ActionRef(TENANT,action),1);
        var card=new com.skillforge.runtime.api.TypedPayload("public-card","{\"type\":\"user_input\",\"body\":\""+question+"\"}");
        return new TeamRuntimeBridge.Pending(run,wait,revision,com.skillforge.runtime.session.spi.ExecutionStore.WaitKind.HUMAN,card);
    }

}
