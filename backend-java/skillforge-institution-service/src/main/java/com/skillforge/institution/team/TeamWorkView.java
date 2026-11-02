package com.skillforge.institution.team;

import static com.skillforge.institution.team.TeamPublicViews.State.*;
import static com.skillforge.institution.team.TeamPublicViews.Stage.*;
import static com.skillforge.institution.team.TeamPublicViews.PendingKind.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.institution.runtime.PublicPresentationTexts;
import com.skillforge.institution.runtime.TeamRuntimeBridge;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.dag.DagPlan;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.session.dag.DagSettlement;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionDelegations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 团队视图只读取权威状态与显式业务成果，不公开账本或协议正文。 */
@Component
public class TeamWorkView {
    private final AgentRunBindings bindings;
    private final TeamResultService results;
    private final TeamRuntimeBridge runtime;
    private final PublicOutputGuard outputs;
    private final ObjectMapper json;

    public TeamWorkView(AgentRunBindings bindings, TeamResultService results, TeamRuntimeBridge runtime,
            PublicOutputGuard outputs, ObjectMapper json) {
        this.bindings=bindings; this.results=results; this.runtime=runtime; this.outputs=outputs; this.json=json;
    }

    public TeamPublicViews.Work view(DagWork work) {
        var scope=outputs.capture(work.key().owner());
        var reports=new ArrayList<>(results.view(work)); var names=memberNames(work,scope);
        List<TeamPublicViews.Task> tasks=new ArrayList<>();
        if (work.plan()!=null) for (var task:work.plan().tasks()) {
            var execution=work.executions().get(task.id());
            boolean primary=task.executorType()==DagPlan.ExecutorType.PRIMARY;
            boolean visible=primary || readable(work,task.agentId().value());
            String status=execution==null ? task.cancelled()?CANCELLED:DagSettlement.blockedBy(work,task).isPresent()?BLOCKED:PENDING : taskStatus(work,execution);
            String wait=execution==null ? task.cancelled()?"已取消，尚未开始执行":unadmittedReason(work,task)
                    : CANCELLING.equals(status)?"正在取消任务":null;
            var pending=visible && execution!=null && (!primary || !execution.terminal()) ? visiblePending(work,execution.binding().runRef()).orElse(null):null;
            if (execution!=null && execution.status()==DagWork.TaskStatus.WAITING && pending==null) wait="等待运行时继续";
            tasks.add(new TeamPublicViews.Task(task.id(),TeamPublicViews.TaskKind.PLAN,safe(scope,task.title()),task.agentId().value(),
                    primary?"主助手":names.getOrDefault(task.agentId().value(),"协作成员"),status,
                    execution==null?null:execution.binding().runRef().runId(),
                    task.inputs().stream().map(DagPlan.Input::sourceTask).distinct().toList(),
                    reports.stream().filter(result->result.taskId().equals(task.id())).map(TeamPublicViews.Result::resultId).toList(),
                    execution==null?null:phase(status),pending,wait,task.replacesTaskId(),
                    !visible?TeamPublicViews.DetailsAccess.REVOKED:execution==null?TeamPublicViews.DetailsAccess.NOT_STARTED:TeamPublicViews.DetailsAccess.AVAILABLE,task.executorType().name(),safe(scope,task.objective()),safe(scope,task.expectedOutput()),task.constraints().stream().map(value->safe(scope,value)).toList()));
        }
        return base(work,scope,names,reports,tasks);
    }

    /** 直接委派的成员终态不决定主助手终态。 */
    public TeamPublicViews.Work direct(DagWork shell,List<SessionDelegations.Entry> entries) {
        var scope=outputs.capture(shell.key().owner());
        var names=memberNames(shell,scope); List<TeamPublicViews.Task> tasks=new ArrayList<>();
        List<TeamPublicViews.Result> reports=new ArrayList<>();
        for(var entry:entries) {
            boolean visible=readable(shell,entry.target().agentId().value());
            String status=entry.state()==SessionDelegations.State.QUEUED?PENDING:entry.state().name().toLowerCase(Locale.ROOT);
            boolean unknown=runtime.fact(shell,entry.childRun()).map(runtime::outcomeUnknown).orElse(false);
            if(unknown&&SessionDelegations.terminal(entry.state()))status=OUTCOME_UNKNOWN;
            String title=safe(scope,entry.task().objective());
            tasks.add(new TeamPublicViews.Task(entry.id(),TeamPublicViews.TaskKind.DIRECT,title,entry.target().agentId().value(),
                    names.getOrDefault(entry.target().agentId().value(),"协作成员"),status,entry.childRun().runId(),List.of(),
                    visible && entry.result().isPresent()?List.of(entry.id()):List.of(),
                    visible?phase(status):"成员访问权限已撤销",visible?visiblePending(shell,entry.childRun()).orElse(null):null,
                    entry.cancelKey().isPresent()&&!SessionDelegations.terminal(entry.state())?"正在取消任务":null,"",visible?TeamPublicViews.DetailsAccess.AVAILABLE:TeamPublicViews.DetailsAccess.REVOKED));
            if(visible) entry.result().ifPresent(result->reports.add(new TeamPublicViews.Result(entry.id(),entry.id(),title,"text",
                    unknown?OUTCOME_UNKNOWN:switch(result.state()) {case COMPLETED->READY;case CANCELLED->CANCELLED;default->FAILED;},
                    safe(scope,result.summary()),safe(scope,result.summary()),List.of(),List.of("成员执行报告，需主助手核验"),null)));
        }
        return base(shell,scope,names,reports,tasks);
    }

    private TeamPublicViews.Work base(DagWork work,PublicOutputGuard.Scope scope,Map<String,String> names,List<TeamPublicViews.Result> reports,List<TeamPublicViews.Task> tasks) {
        var parent=runtime.fact(work,work.request().controlRun());
        String stage=parent.map(record->switch(record.status()) {
            case COMPLETED->FINISHED;case FAILED->FAILED;case CANCELLED->CANCELLED;case TERMINATED->TERMINATED;
            default->work.status()==DagWork.Status.COMPLETED || work.plan()!=null
                    && DagSettlement.settled(work)?SUMMARIZING:WORKING;
        }).orElse(WORKING);
        return new TeamPublicViews.Work(work.key().rootInputId().value(),work.key().owner().sessionRef().sessionId(),work.revision(),
                work.key().rootInputId().value(),work.request().controlRun().runId(),parent.map(record->record.revision()).orElse(null),
                parent.map(record->record.status().name().toLowerCase(Locale.ROOT)).orElse(null),stage,
                switch(work.status()){case QUEUED->QUEUED;case ACTIVE,CANCELLING->ACTIVE;case COMPLETED->COMPLETED;case CANCELLED->CANCELLED;},
                work.plan()==null?0:work.plan().revision(),work.approvedRevision()==0?null:work.approvedRevision(),
                work.plan()!=null&&work.plan().approvalRequired(),safe(scope,work.request().handoffBrief()),
                new TeamPublicViews.Member(work.coordinator().value(),names.getOrDefault(work.coordinator().value(),"助手")),
                names.entrySet().stream().map(entry->new TeamPublicViews.Member(entry.getKey(),entry.getValue())).toList(),
                List.copyOf(reports),List.copyOf(tasks),visiblePending(work,work.request().controlRun()).orElse(null),0,List.of(),safe(scope,work.plan()==null?work.request().text():work.plan().title()),
                safe(scope,work.plan()==null?"":work.plan().changeReason()));
    }

    public Optional<TeamPublicViews.Pending> pendingView(Optional<TeamRuntimeBridge.Pending> pending) {
        if(pending.isEmpty()) return Optional.empty();
        var value=pending.orElseThrow();
        if(value.kind()!=ExecutionStore.WaitKind.HUMAN&&value.kind()!=ExecutionStore.WaitKind.TOOL)return Optional.empty();
        try {
            var card=json.readTree(value.card().canonicalJson());
            String prompt=value.kind()==ExecutionStore.WaitKind.HUMAN?card.path("body").asText():"请补充任务所需信息";
            return Optional.of(new TeamPublicViews.Pending(value.reference(),Long.toString(value.revision()),prompt,
                    value.kind()==ExecutionStore.WaitKind.HUMAN?INPUT:WORKFLOW,value.run().runId(),
                    new TeamPublicViews.Wait(value.waiting().action().actionId(),Long.toString(value.waiting().generation())),Long.toString(value.revision()),card));
        }catch(java.io.IOException invalid){throw new IllegalStateException("team_wait_card_invalid",invalid);}
    }

    public Optional<TeamPublicViews.Pending> visiblePending(DagWork work,RunRef run) {
        try {
            // DAG先持久化成员准入，委派随后才创建子Run绑定；此窗口没有可投影的确认卡。
            var current=bindings.find(run);
            if(current.isEmpty())return Optional.empty();
            var binding=current.orElseThrow(); var scope=outputs.capture(binding.owner());
            var pending=pendingView(runtime.pending(work,run));
            return pending.map(value->{
                if(scope.rejects(value.prompt()) || value.card()!=null && PublicPresentationTexts.read(value.card()).stream().anyMatch(scope::rejects))
                    return new TeamPublicViews.Pending(value.pendingId(),value.parametersVersion(),PublicOutputGuard.SAFE_MESSAGE,REJECTED,
                            value.executionId(),value.toolWait(),value.runRevision(),null);
                return value;
            });
        } catch(SecurityException denied){return Optional.empty();}
        catch(ApiException denied){if(denied.getStatus().is4xxClientError())return Optional.empty();throw denied;}
    }

    private boolean readable(DagWork work,String actor) {
        try {bindings.authorizeRead(bindings.find(work.request().controlRun()).orElseThrow(),actor);return true;}
        catch(SecurityException denied){return false;}
        catch(ApiException denied){if(denied.getStatus().is4xxClientError())return false;throw denied;}
    }
    private Map<String,String> memberNames(DagWork work,PublicOutputGuard.Scope scope) {
        var binding=bindings.find(work.request().controlRun()).orElseThrow();
        if(!binding.owner().equals(work.key().owner()))throw new SecurityException("work_scope_mismatch");
        var names=new LinkedHashMap<String,String>();
        binding.scope().directory().forEach((id,member)->{if(readable(work,id)) names.put(id,member.name()==null||member.name().isBlank()?"协作成员":safe(scope,member.name()));});
        return Map.copyOf(names);
    }
    private static String safe(PublicOutputGuard.Scope scope,String text) { return scope.rejects(text)?PublicOutputGuard.SAFE_MESSAGE:text; }
    private String unadmittedReason(DagWork work,DagPlan.Task task) {
        var blocked=DagSettlement.blockedBy(work,task);
        if(blocked.isPresent()) return switch(blocked.orElseThrow()) {
            case OUTCOME_UNKNOWN -> "前置任务结果待核实，不能重复执行";
            case FAILED -> "前置任务失败，需要调整后续计划";
            case CANCELLED -> "前置任务已取消，需要调整后续计划";
            default -> throw new IllegalStateException("invalid dependency blocking status");
        };
        if(work.plan().approvalRequired()&&work.approvedRevision()<work.plan().revision())return "等待计划审批";
        if(!task.inputs().stream().map(DagPlan.Input::sourceTask).distinct().map(work.executions()::get)
                .allMatch(value->value!=null&&value.status()==DagWork.TaskStatus.COMPLETED))return "等待前置任务完成";
        if(task.executorType()==DagPlan.ExecutorType.PRIMARY)return "等待主助手开始步骤";
        return "等待依赖、资源互斥或成员授权";
    }
    private static String taskStatus(DagWork work,DagWork.Execution execution) {
        if(work.status()==DagWork.Status.CANCELLING&&!execution.terminal())return CANCELLING;
        return switch(execution.status()){case RESERVED->PENDING;case RUNNING->RUNNING;case WAITING->WAITING;case COMPLETED->COMPLETED;case FAILED->FAILED;case CANCELLED->CANCELLED;case OUTCOME_UNKNOWN->OUTCOME_UNKNOWN;};
    }
    private static String phase(String status) {return switch(status){case COMPLETED->"成员执行已结束";case FAILED->"成员执行失败";case OUTCOME_UNKNOWN->"执行结果尚未核实";case CANCELLED->"成员任务已取消";case WAITING->"等待补充或确认";case RUNNING->"正在执行";default->"任务已受理";};}
}
