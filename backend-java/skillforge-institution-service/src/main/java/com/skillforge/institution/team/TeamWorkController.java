package com.skillforge.institution.team;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.runtime.AgentRunBindings;

import com.skillforge.institution.common.TenantIds;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.runtime.TeamRuntimeBridge;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.SessionDelegationEngine;
import com.skillforge.runtime.session.spi.SessionDelegations;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 统一读取可选计划与直接委派；所有历史操作始终绑定原始 work_id。 */
@RestController
@RequestMapping("/api/v1/chat/work")
public class TeamWorkController {
    private final SessionService sessions;
    private final JdbcDagWorkPort store;
    private final TeamWorkService works;
    private final TeamWorkView views;
    private final ArtifactAccess access;
    private final TeamWorkInputs inputs;
    private final SessionDelegations.Store delegations;
    private final SessionDelegationEngine engine;
    private final AgentRunBindings bindings;
    private final TeamRuntimeBridge runtime;
    private final Clock clock;
    private final MemberTaskDetails details;

    public TeamWorkController(SessionService sessions, JdbcDagWorkPort store, TeamWorkService works,
            TeamWorkView views, ArtifactAccess access, TeamWorkInputs inputs, SessionDelegations.Store delegations,
            SessionDelegationEngine engine, AgentRunBindings bindings, TeamRuntimeBridge runtime, Clock clock, MemberTaskDetails details) {
        this.sessions=sessions;this.store=store;this.works=works;this.views=views;this.access=access;this.inputs=inputs;
        this.delegations=delegations;this.engine=engine;this.bindings=bindings;this.runtime=runtime;this.clock=clock;
        this.details=details;
    }
    @GetMapping("/tasks/detail")
@RequirePermission("foundation.sessions.read")
    public ApiResponse<TeamPublicViews.MemberDetail> taskDetails(@RequestParam("session_id") String sessionId,
            @RequestParam("work_id") String workId,@RequestParam("task_kind") String kind,
            @RequestParam("task_id") String taskId,@RequestParam(value="cursor",required=false) String cursor,
            @RequestParam(value="limit",defaultValue=TeamPublicViews.DETAIL_DEFAULT_LIMIT) int limit) {
        var session=sessions.require(TenantIds.requireTenantId(),sessionId);
        return ApiResponses.wrap(details.read(session,workId,kind,taskId,cursor,limit));
    }
    @GetMapping
@RequirePermission("foundation.sessions.read")
    public ApiResponse<TeamPublicViews.Work> read(@RequestParam("session_id") String sessionId,
            @RequestParam(value="work_id",required=false) String workId) {
        if (workId!=null) {
            String tenant=TenantIds.requireTenantId();var session=sessions.require(tenant,sessionId);access.authorizeCurrent(tenant,sessionId);
            var owner=TeamWorkService.owner(session);
            var entries=delegations.forSession(owner,TeamPublicViews.DELEGATION_HISTORY_LIMIT).stream()
                    .filter(entry->entry.parent().causationInputId().value().equals(workId)).toList();
            var work=store.read(new DagWork.Key(owner,new InputId(workId)));
            if (work.isPresent()) return ApiResponses.wrap(snapshot(work.orElseThrow(),entries));
            return ApiResponses.wrap(entries.isEmpty()?null:snapshot(shell(entries),entries));
        }
        var items=history(sessionId).data().items();
        return ApiResponses.wrap(items.isEmpty()?null:items.getLast());
    }
    @GetMapping("/history")
@RequirePermission("foundation.sessions.read")
    public ApiResponse<TeamPublicViews.History> history(@RequestParam("session_id") String sessionId) {
        String tenant=TenantIds.requireTenantId();var session=sessions.require(tenant,sessionId);access.authorizeCurrent(tenant,sessionId);
        var owner=TeamWorkService.owner(session);
        var entries=delegations.forSession(owner,TeamPublicViews.DELEGATION_HISTORY_LIMIT);
        var groups=groups(entries);
        var all=new LinkedHashMap<String,DagWork>();
        for(var plan:store.history(owner,TeamPublicViews.WORK_HISTORY_LIMIT))all.put(plan.key().rootInputId().value(),plan);
        groups.forEach((id,group)->all.putIfAbsent(id,shell(group)));
        var items=all.values().stream().sorted(Comparator.comparing((DagWork work)->work.request().receivedAt()))
                .map(work->snapshot(work,groups.getOrDefault(work.key().rootInputId().value(),List.of()))).toList();
        return ApiResponses.wrap(new TeamPublicViews.History(items));
    }
    public record ControlRequest(String sessionId,String inputId,String workId,String taskId,String operation,String content,
                                 Long planRevision,String pendingId,String parametersVersion,String agentId) { }
    @PostMapping("/controls")
@RequirePermission("foundation.sessions.write")
    public ApiResponse<TeamPublicViews.Work> control(@RequestBody ControlRequest body) {
        String tenant=TenantIds.requireTenantId();var session=sessions.requireWritable(tenant,body.sessionId());access.authorizeWritableCurrent(tenant,body.sessionId());
        var owner=TeamWorkService.owner(session);
        var control=new TeamWorkInputs.Control(body.inputId(),body.taskId(),body.operation(),body.content(),body.planRevision(),body.pendingId(),body.parametersVersion(),body.agentId());
        var entries=delegations.forSession(owner,TeamPublicViews.DELEGATION_HISTORY_LIMIT).stream().filter(entry->entry.parent().causationInputId().value().equals(body.workId())).toList();
        var plan=store.read(new DagWork.Key(owner,new InputId(body.workId())));
        var direct=entries.stream().filter(entry->!entry.operationKey().startsWith("dag:")&&entry.id().equals(control.taskId())).findFirst();
        if(plan.isPresent() && direct.isEmpty()) {
            works.control(plan.orElseThrow(),control);
            return ApiResponses.wrap(snapshot(store.read(plan.orElseThrow().key()).orElseThrow(),entries));
        }
        if(entries.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"协作记录不存在");
        var shell=shell(entries);
        if(control.operation().equals("cancel")) {
            if(control.taskId().isEmpty())engine.cancelOwned(entries.getFirst().parent(),"ui-cancel:"+control.inputId());
            else engine.cancel(direct.orElseThrow(()->invalid("任务不属于该工作")),"ui-cancel:"+control.inputId());
        } else if(Set.of("supplement","adjust").contains(control.operation())) {
            if(control.content().isBlank())throw invalid("补充内容不能为空");
            if(control.taskId().isEmpty())runtime.supplement(shell,shell.request().controlRun(),control.inputId(),control.content());
            else {
                var entry=direct.orElseThrow(()->invalid("任务不属于该工作"));
                engine.execute(entry.parent(),"ui-send:"+control.inputId(),new DelegationCommand(DelegationCommand.Operation.SEND,"",Optional.empty(),List.of(entry.id()),control.content(), true));
            }
        } else if(Set.of("approve_operation","reject_operation","reply_input","workflow_response").contains(control.operation())) {
            var entry=direct.orElseThrow(()->invalid("任务不属于该工作"));
            String operation=switch(control.operation()){case "approve_operation"->"approve";case "reject_operation"->"reject";case "workflow_response"->"workflow";default->"reply";};
            runtime.respond(shell,entry.childRun(),control.inputId(),operation,control.pendingId(),control.parametersVersion(),control.content(),clock.instant());
        } else throw invalid("当前协作不支持该操作");
        return read(body.sessionId(),body.workId());
    }
    private TeamPublicViews.Work snapshot(DagWork work,List<SessionDelegations.Entry> entries) {
        if(work.plan()==null)return views.direct(work,entries);
        var value=views.view(work);
        var direct=entries.stream().filter(entry->!entry.operationKey().startsWith("dag:")).toList();
        var tasks=new ArrayList<>(value.tasks());var results=new ArrayList<>(value.results());
        if(!direct.isEmpty()) {
            var extra=views.direct(shell(direct),direct);tasks.addAll(extra.tasks());results.addAll(extra.results());
        }
        return value.withCollections(results,tasks,inputs.recent(work));
    }
    private Map<String,List<SessionDelegations.Entry>> groups(List<SessionDelegations.Entry> entries) {
        var grouped=new LinkedHashMap<String,List<SessionDelegations.Entry>>();
        for(var entry:entries)grouped.computeIfAbsent(entry.parent().causationInputId().value(),ignored->new ArrayList<>()).add(entry);
        return grouped;
    }
    private DagWork shell(List<SessionDelegations.Entry> entries) {
        return details.directWork(entries);
    }
    private static ApiException invalid(String message){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,message);}
}
