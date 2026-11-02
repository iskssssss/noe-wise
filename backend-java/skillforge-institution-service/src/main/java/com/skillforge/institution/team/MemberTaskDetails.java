package com.skillforge.institution.team;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.institution.runtime.AgentRuntimeExecutionProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.institution.runtime.TeamRuntimeBridge;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.institution.runtime.AgentDelegationHost;
import java.util.Locale;
import java.util.Comparator;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.spi.SessionDelegations;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 以父产品任务绑定读取独立成员 Session，不开放任意 child owner/run 查询。 */
@Service
public final class MemberTaskDetails {
    private static final String DAG_OPERATION_PREFIX = "dag:";
    private static final int CURSOR_PART_COUNT = 5;
    private final JdbcDagWorkPort plans;
    private final SessionDelegations.Store delegations;
    private final AgentRunBindings bindings;
    private final ArtifactAccess access;
    private final ExecutionStore store;
    private final AgentRuntimeExecutionProjection projection;
    private final ObjectMapper json;
    private final TeamRuntimeBridge runtime;
    private final TeamWorkView views;

    public MemberTaskDetails(JdbcDagWorkPort plans, SessionDelegations.Store delegations,
            AgentRunBindings bindings, ArtifactAccess access, ExecutionStore store,
            AgentRuntimeExecutionProjection projection, TeamRuntimeBridge runtime, TeamWorkView views,ObjectMapper json) {
        this.plans=plans; this.delegations=delegations; this.bindings=bindings; this.access=access;
        this.store=store; this.projection=projection; this.runtime=runtime; this.views=views;this.json=json;
    }

    public record Source(SessionOwner owner, RunRef run, DagWork work, String taskKind, String taskId, String title, String actorName) { }

    /** HTTP 和输入引用共用解析及当前读权，不使用执行授权排除终态历史。 */
    public Source resolve(SessionView session, String workId, String taskKind, String taskId) {
        access.authorizeCurrent(Long.toString(session.getTenantId()), session.getSessionId());
        var origin = TeamWorkService.owner(session);
        DagWork work;
        RunRef child;
        String title;
        AgentId expectedActor;
        SessionOwner expectedOwner=null;
        if (TeamPublicViews.TaskKind.PLAN.wire().equals(taskKind)) {
            work=plans.read(new DagWork.Key(origin,new InputId(workId))).orElseThrow(MemberTaskDetails::missing);
            if (work.plan()==null) throw missing();
            var task=work.plan().tasks().stream().filter(value -> value.id().equals(taskId)).findFirst().orElseThrow(MemberTaskDetails::missing);
            var execution=work.executions().get(taskId);
            if (execution==null) throw new ApiException(HttpStatus.CONFLICT,"成员任务尚未开始");
            if(!execution.binding().task().equals(task)) throw missing();
            child=execution.binding().runRef(); title=task.title(); expectedActor=task.agentId();
            if (task.executorType()==com.skillforge.runtime.api.dag.DagPlan.ExecutorType.PRIMARY) {
                var primary=bindings.find(child).orElseThrow(MemberTaskDetails::missing);
                if (!child.equals(work.request().controlRun()) || !primary.owner().equals(origin)
                        || !primary.actor().equals(work.coordinator()) || !task.agentId().equals(work.coordinator())) throw missing();
                return new Source(origin,child,work,taskKind,taskId,title,"主助手");
            }
        } else if (TeamPublicViews.TaskKind.DIRECT.wire().equals(taskKind)) {
            var entries=delegations.forSession(origin,TeamPublicViews.DELEGATION_HISTORY_LIMIT).stream()
                    .filter(entry -> entry.parent().causationInputId().value().equals(workId))
                    .filter(entry -> !entry.operationKey().startsWith(DAG_OPERATION_PREFIX)).toList();
            var entry=entries.stream().filter(value -> value.id().equals(taskId)).findFirst().orElseThrow(MemberTaskDetails::missing);
            work=directWork(entries); child=entry.childRun(); title=entry.task().objective(); expectedActor=entry.target().agentId();
            expectedOwner=AgentDelegationHost.childOwner(entry);
        } else throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"成员任务类型无效");
        var binding=bindings.find(child).orElseThrow(MemberTaskDetails::missing);
        if (binding.role()!=AgentRunBindings.Role.DELEGATE || !binding.actor().equals(expectedActor)
                || expectedOwner!=null&&!binding.owner().equals(expectedOwner) || !binding.scope().origin().equals(origin)
                || !binding.parentRunRef().equals(Optional.of(work.request().controlRun()))
                || !binding.owner().subject().equals(origin.subject())
                || !binding.owner().sessionRef().tenantId().equals(origin.sessionRef().tenantId())
                || !binding.owner().target().equals(origin.target().kind()==SessionTarget.Kind.TEAM
                    ?origin.target():SessionTarget.agent(expectedActor))) throw missing();
        try { bindings.authorizeRead(binding,binding.actor().value()); }
        catch (SecurityException denied) { throw new ApiException(HttpStatus.FORBIDDEN,"成员详情当前不可访问"); }
        var names=new LinkedHashMap<String,String>();
        binding.scope().directory().forEach((id, member) -> names.put(id,member.name()));
        String name=names.getOrDefault(binding.actor().value(),"协作成员");
        if(name==null||name.isBlank()) name="协作成员";
        return new Source(binding.owner(),child,work,taskKind,taskId,title,name);
    }

    public boolean primary(Source source) {
        return source.work().plan()!=null && TeamPublicViews.TaskKind.PLAN.wire().equals(source.taskKind())
                && source.work().plan().task(source.taskId()).executorType()==com.skillforge.runtime.api.dag.DagPlan.ExecutorType.PRIMARY;
    }

    /** 主 Run 共享多步；引用入口也必须只返回当前步骤的公开 Action，不能读取整 Run 回答。 */
    public com.skillforge.core.chat.dto.response.ChatHistoryResponse history(Source source) {
        if (!primary(source)) return projection.readSource(source.owner(),source.run());
        var events=new java.util.ArrayList<ExecutionStore.SavedEvent>();
        long after=0;
        while (true) {
            var page=store.events(source.owner().sessionRef(),after,100);
            if (page.isEmpty()) break;
            for (var event:page) {
                if (!event.run().tenantId().equals(source.owner().sessionRef().tenantId()) || event.sequence()<=after) throw new IllegalStateException("task_process_source_invalid");
                after=event.sequence();
                if (visibleEvent(source,event,true)) events.add(event);
            }
            if (page.size()<100) break;
        }
        return projection.project(source.owner(),events);
    }

    public TeamPublicViews.MemberDetail read(SessionView session,String workId,String kind,String taskId,String cursor,int limit) {
        if (limit<1 || limit>TeamPublicViews.MAX_DETAIL_PAGE_SIZE) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"每页记录数量无效");
        var source=resolve(session,workId,kind,taskId);
        long after=decode(cursor,source);
        var events=readEvents(source.owner().sessionRef(),after,limit);
        long scanned=after;
        for (var event : events) {
            if (!event.run().tenantId().equals(source.owner().sessionRef().tenantId()) || event.sequence()<=scanned)
                throw new IllegalStateException("member_process_source_invalid");
            scanned=event.sequence();
        }
        boolean primary=source.work().plan()!=null && TeamPublicViews.TaskKind.PLAN.wire().equals(kind)
                && source.work().plan().task(taskId).executorType()==com.skillforge.runtime.api.dag.DagPlan.ExecutorType.PRIMARY;
        var processes=projection.project(source.owner(),events.stream().filter(event->visibleEvent(source,event,primary)).toList()).processes();
        var run=runtime.fact(source.work(),source.run()).orElseThrow(MemberTaskDetails::missing);
        var snapshot=TeamPublicViews.TaskKind.DIRECT.wire().equals(kind)
                ? views.direct(source.work(),delegations.forSession(TeamWorkService.owner(session),TeamPublicViews.DELEGATION_HISTORY_LIMIT).stream()
                    .filter(entry -> entry.parent().causationInputId().value().equals(workId) && !entry.operationKey().startsWith(DAG_OPERATION_PREFIX)).toList())
                : views.view(source.work());
        var results=snapshot.results().stream().filter(value->taskId.equals(value.taskId())).toList();
        var visibleTask=snapshot.tasks().stream().filter(value->taskId.equals(value.taskId())).findFirst().orElseThrow(MemberTaskDetails::missing);
        var task=visibleTask;
        var pending=primary && source.work().executions().get(taskId).terminal()?null:views.visiblePending(source.work(),source.run()).orElse(null);
        // 查询期间撤权也不能返回已构造的受限材料。
        resolve(session,workId,kind,taskId);
        return new TeamPublicViews.MemberDetail(task,new TeamPublicViews.Run(primary?visibleTask.status():run.status().name().toLowerCase(Locale.ROOT),
                run.revision(),run.cancelled()),processes,encode(scanned,source),events.size()==limit,events.size()<limit,pending,results);
    }

    /** 产品允许200条，但每次存储查询最多100；继续页仍使用真实持久Session序号。 */
    private List<ExecutionStore.SavedEvent> readEvents(SessionRef session,long after,int limit) {
        var result=new ArrayList<ExecutionStore.SavedEvent>();long cursor=after;
        while(result.size()<limit) {
            int count=Math.min(100,limit-result.size());var page=store.events(session,cursor,count);
            if(page.size()>count)throw new IllegalStateException("member_process_page_unbounded");
            for(var event:page){if(event.sequence()<=cursor)throw new IllegalStateException("member_process_source_invalid");cursor=event.sequence();result.add(event);}
            if(page.size()<count)break;
        }
        return List.copyOf(result);
    }

    /** 主Run共享计划步骤；仅展示原真实Action绑定到本任务的公开事件，不读取整个Run最终回答。 */
    private boolean visibleEvent(Source source,ExecutionStore.SavedEvent event,boolean primary) {
        if(!event.run().equals(source.run()))return false;if(!primary)return true;
        try {
            var body=json.readTree(event.event().data().canonicalJson());var action=body.get("actionId");
            return action!=null&&action.isTextual()&&plans.taskForAction(source.run(),action.textValue()).filter(source.taskId()::equals).isPresent();
        }catch(java.io.IOException invalid){throw new IllegalStateException("member_process_event_invalid",invalid);}
    }

    public DagWork directWork(List<SessionDelegations.Entry> entries) {
        if (entries.isEmpty()) throw missing();
        var first=entries.getFirst(); var parent=first.parent();
        var binding=bindings.find(parent.runRef()).orElseThrow(MemberTaskDetails::missing);
        boolean done=entries.stream().allMatch(entry -> SessionDelegations.terminal(entry.state()));
        var request=new DagWork.Request(first.task().objective(),Optional.of(parent.agentId()),parent.runRef(),"",DagWork.Request.Stage.WORKING,
                entries.stream().map(SessionDelegations.Entry::createdAt).min(Comparator.naturalOrder()).orElseThrow(),false,
                binding.scope().requester().requesterId(),binding.scope().requester().channelId());
        return new DagWork(new DagWork.Key(parent.owner(),parent.causationInputId()),parent.agentId(),
                entries.stream().mapToLong(SessionDelegations.Entry::revision).sum(),done?DagWork.Status.COMPLETED:DagWork.Status.ACTIVE,
                null,0,entries.stream().mapToLong(entry -> entry.result().map(SessionDelegations.Result::usedTokens).orElse(0L)).sum(),Map.of(),Map.of(),request);
    }

    private static String encode(long sequence,Source source) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((source.owner().sessionRef().sessionId()+"\n"+source.taskKind()+"\n"+source.taskId()+"\n"+source.run().runId()+"\n"+sequence).getBytes(StandardCharsets.UTF_8));
    }
    private static long decode(String cursor,Source source) {
        if (cursor==null || cursor.isBlank()) return 0;
        try {
            String decoded=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
            String[] parts=decoded.split("\n",-1);
            if (parts.length!=CURSOR_PART_COUNT || !parts[0].equals(source.owner().sessionRef().sessionId())
                    || !parts[1].equals(source.taskKind()) || !parts[2].equals(source.taskId()) || !parts[3].equals(source.run().runId())) throw new IllegalArgumentException();
            long sequence=Long.parseLong(parts[4]); if (sequence<0) throw new IllegalArgumentException(); return sequence;
        } catch (IllegalArgumentException invalid) { throw new ApiException(HttpStatus.CONFLICT,"成员详情游标已失效"); }
    }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"成员任务不存在"); }
}
