package com.skillforge.institution.team;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.institution.runtime.AgentRuntimeExecutionProjection;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.institution.runtime.TeamRuntimeBridge;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.dag.DagPlan;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.spi.SessionDelegations;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 成员过程详情必须从父任务精确绑定解析，并在扫描前后重新检查当前读取授权。 */
class MemberTaskDetailsTest {
    private static final TenantId TENANT=new TenantId("7");
    private static final AgentId PRIMARY=new AgentId("101");
    private static final AgentId MEMBER=new AgentId("102");
    private static final String WORK_ID="root-input";
    private static final String TASK_ID="member-task";
    private static final SessionOwner ORIGIN=SessionOwner.user(new SessionRef(TENANT,"product-session"),PRIMARY,new EndUserId(8));
    private static final SessionOwner CHILD=SessionOwner.user(new SessionRef(TENANT,"delegation:"+TASK_ID),MEMBER,ORIGIN.requireUser());
    private static final RunRef CONTROL=new RunRef(TENANT,"parent-run");
    private static final RunRef CHILD_RUN=new RunRef(TENANT,"child-run");
    private static final InputId INPUT=new InputId(WORK_ID);
    private static final RunIdentity PARENT=new RunIdentity(ORIGIN, new AgentId((ORIGIN).target().id()), INPUT, CONTROL, new TraceEnvelope("trace","span",INPUT,Optional.empty()), new ResultRef("parent-identity"), new RunRequester.EndUser((ORIGIN).requireUser()), java.util.Optional.empty(), new RunOriginRef((ORIGIN).sessionRef().tenantId(), "input:" + (INPUT).value()));
    private static final RunIdentity CHILD_IDENTITY=new RunIdentity(CHILD, new AgentId((CHILD).target().id()), new InputId("child-input"), CHILD_RUN, new TraceEnvelope("child-trace","child-span",INPUT,Optional.empty()), new ResultRef("child-identity"), new RunRequester.EndUser((CHILD).requireUser()), java.util.Optional.empty(), new RunOriginRef((CHILD).sessionRef().tenantId(), "input:" + (new InputId("child-input")).value()));
    private final SessionView session=SessionView.builder().tenantId(7L).sessionId(ORIGIN.sessionRef().sessionId())
            .agentId(101L).endUserId(8L).build();
    private final JdbcDagWorkPort plans=mock(JdbcDagWorkPort.class);
    private final SessionDelegations.Store delegations=mock(SessionDelegations.Store.class);
    private final AgentRunBindings bindings=mock(AgentRunBindings.class);
    private final ArtifactAccess access=mock(ArtifactAccess.class);
    private final ExecutionStore store=mock(ExecutionStore.class);
    private final TeamRuntimeBridge runtime=mock(TeamRuntimeBridge.class);
    private final AgentRuntimeExecutionProjection projection=mock(AgentRuntimeExecutionProjection.class);
    private final TeamWorkView views=mock(TeamWorkView.class);
    private final AgentRunBindings.Binding childBinding=mock(AgentRunBindings.Binding.class);
    private final AgentRunBindings.Scope scope=mock(AgentRunBindings.Scope.class);
    private final MemberTaskDetails details=new MemberTaskDetails(plans,delegations,bindings,access,store,projection,
            runtime,views,new com.fasterxml.jackson.databind.ObjectMapper());
    private DagWork work;

    @BeforeEach void fixture() {
        var task=new DagPlan.Task(TASK_ID,"订单分析",MEMBER,"分析订单",List.of(),Set.of("answer"),Set.of());
        var plan=new DagPlan(1,true,List.of(task));
        var execution=new DagWork.Execution(new DagWork.Binding("admission",1,task,CHILD_RUN,Map.of()),
                DagWork.TaskStatus.COMPLETED,Optional.empty(),false,0,Map.of(),"completed",Optional.empty());
        work=new DagWork(new DagWork.Key(ORIGIN,INPUT),PRIMARY,2,DagWork.Status.COMPLETED,plan,1,0,
                Map.of(TASK_ID,execution),Map.of(),new DagWork.Request("分析订单",Optional.empty(),CONTROL,""));
        when(plans.read(work.key())).thenReturn(Optional.of(work));
        when(bindings.find(CHILD_RUN)).thenReturn(Optional.of(childBinding));
        when(childBinding.role()).thenReturn(AgentRunBindings.Role.DELEGATE);
        when(childBinding.scope()).thenReturn(scope);when(childBinding.owner()).thenReturn(CHILD);when(childBinding.actor()).thenReturn(MEMBER);
        when(childBinding.parentRunRef()).thenReturn(Optional.of(CONTROL));
        when(scope.origin()).thenReturn(ORIGIN);when(scope.primaryRun()).thenReturn(CONTROL);
        when(scope.directory()).thenReturn(Map.of(MEMBER.value(),new TeamDefinition.Member(MEMBER.value(),"1","分析成员","订单分析",List.of())));
        var record=mock(ExecutionStore.Run.class);when(record.identity()).thenReturn(CHILD_IDENTITY);
        when(record.status()).thenReturn(RunStatus.COMPLETED);when(record.revision()).thenReturn(9L);when(record.cancelled()).thenReturn(false);
        when(runtime.fact(work,CHILD_RUN)).thenReturn(Optional.of(record));
        when(store.events(any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());when(projection.project(any(),anyList())).thenReturn(new com.skillforge.core.chat.dto.response.ChatHistoryResponse("session",List.of(),List.of(),List.of(),List.of(),com.skillforge.core.chat.dto.response.ChatHistoryResponse.Collaboration.empty()));
        var snapshot=mock(TeamPublicViews.Work.class);
        when(snapshot.tasks()).thenReturn(List.of(new TeamPublicViews.Task(TASK_ID,TeamPublicViews.TaskKind.PLAN,"订单分析",MEMBER.value(),"分析成员",
                TeamPublicViews.State.COMPLETED,CHILD_RUN.runId(),List.of(),List.of(),null,null,null,"",TeamPublicViews.DetailsAccess.AVAILABLE)));
        when(snapshot.results()).thenReturn(List.of());
        when(views.view(work)).thenReturn(snapshot);when(views.direct(any(),anyList())).thenReturn(snapshot);
        when(views.visiblePending(any(),any())).thenReturn(Optional.empty());
        var parentBinding=mock(AgentRunBindings.Binding.class);when(parentBinding.scope()).thenReturn(scope);
        when(scope.requester()).thenReturn(new AgentRunBindings.Requester("9",""));
        when(bindings.find(CONTROL)).thenReturn(Optional.of(parentBinding));
        when(delegations.forSession(ORIGIN,1000)).thenReturn(List.of(entry("direct-operation",PARENT,CHILD_RUN)));
    }

    @Test void primaryHistoryNeverReturnsTheSharedRunMessages() {
        var task=new DagPlan.Task("self-step","核查",PRIMARY,DagPlan.ExecutorType.PRIMARY,"核查来源",List.of(),Set.of("answer"),Set.of());
        var execution=new DagWork.Execution(new DagWork.Binding("primary-admission",1,task,CONTROL,Map.of()),
                DagWork.TaskStatus.COMPLETED,Optional.empty(),false,0,Map.of(),"核查完成",Optional.empty());
        var own=new DagWork(work.key(),PRIMARY,3,DagWork.Status.ACTIVE,new DagPlan(1,false,List.of(task)),1,0,
                Map.of(task.id(),execution),Map.of(),work.request());
        when(plans.read(work.key())).thenReturn(Optional.of(own));
        var binding=mock(AgentRunBindings.Binding.class);
        when(bindings.find(CONTROL)).thenReturn(Optional.of(binding));
        when(binding.owner()).thenReturn(ORIGIN);when(binding.actor()).thenReturn(PRIMARY);
        var source=details.resolve(session,WORK_ID,"plan",task.id());
        assertTrue(details.primary(source));
        assertEquals(List.of(),details.history(source).messages());
        verify(projection,never()).readSource(any(),any());
        verify(store).events(ORIGIN.sessionRef(),0,100);
    }

    @Test void terminalPlanUsesCurrentReadPermissionAndExactTaskExecution() {
        var result=details.read(session,WORK_ID,"plan",TASK_ID,null,20);
        assertEquals("completed",result.run().status());assertEquals(9,result.run().revision());
        assertEquals(CHILD_RUN.runId(),result.task().runId());assertEquals("分析成员",result.task().agentName());
        verify(access,times(2)).authorizeCurrent(TENANT.value(),ORIGIN.sessionRef().sessionId());
        verify(bindings,times(2)).authorizeRead(childBinding,MEMBER.value());
        verify(bindings,never()).authorize(childBinding);verify(bindings,never()).authorizeTarget(any(),any());
        verify(store).events(CHILD.sessionRef(),0,20);
    }

    @Test void deniedProductSessionReadCannotResolveOrScanMemberMaterial() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"会话不属于当前终端用户")).when(access)
                .authorizeCurrent(TENANT.value(),ORIGIN.sessionRef().sessionId());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,
                ()->details.read(session,WORK_ID,"plan",TASK_ID,null,20)).getStatus());
        verifyNoInteractions(plans,store,projection);
        verify(bindings,never()).authorizeRead(any(),any());
    }

    @Test void differentChildEndUserIsRejectedBeforeLogRead() {
        when(childBinding.owner()).thenReturn(SessionOwner.user(CHILD.sessionRef(),MEMBER,new EndUserId(99)));
        assertMissingPlan();
        verifyNoInteractions(store,projection);
    }

    @Test void directTaskUsesParentInputAndExcludesDagDelegations() {
        assertEquals(CHILD_RUN,details.resolve(session,WORK_ID,"direct",TASK_ID).run());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,
                ()->details.resolve(session,"other-input","direct",TASK_ID)).getStatus());
        when(delegations.forSession(ORIGIN,1000)).thenReturn(List.of(entry("dag:operation",PARENT,CHILD_RUN)));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,
                ()->details.resolve(session,WORK_ID,"direct",TASK_ID)).getStatus());
    }

    @Test void foreignParentOriginOrTaskBindingIsRejectedBeforeLogRead() {
        when(childBinding.parentRunRef()).thenReturn(Optional.of(new RunRef(TENANT,"foreign-parent")));
        assertMissingPlan();
        when(childBinding.parentRunRef()).thenReturn(Optional.of(CONTROL));
        when(scope.origin()).thenReturn(SessionOwner.user(new SessionRef(TENANT,"foreign-session"),PRIMARY,ORIGIN.requireUser()));
        assertMissingPlan();when(scope.origin()).thenReturn(ORIGIN);
        when(childBinding.actor()).thenReturn(new AgentId("103"));
        assertMissingPlan();
        verifyNoInteractions(store);
    }

    @Test void directTargetMustMatchActorAndChildSessionBinding() {
        when(childBinding.actor()).thenReturn(new AgentId("103"));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,
                ()->details.resolve(session,WORK_ID,"direct",TASK_ID)).getStatus());
        when(childBinding.actor()).thenReturn(MEMBER);
        when(childBinding.owner()).thenReturn(SessionOwner.user(new SessionRef(TENANT,"delegation:another-task"),MEMBER,ORIGIN.requireUser()));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,
                ()->details.resolve(session,WORK_ID,"direct",TASK_ID)).getStatus());
    }

    @Test void revokedReadAndRevocationDuringQueryFailWithoutReturningConstructedMaterial() {
        doThrow(new SecurityException("revoked")).when(bindings).authorizeRead(childBinding,MEMBER.value());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,
                ()->details.read(session,WORK_ID,"plan",TASK_ID,null,20)).getStatus());
        verifyNoInteractions(store);
        doNothing().doThrow(new SecurityException("revoked during read")).when(bindings).authorizeRead(childBinding,MEMBER.value());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,
                ()->details.read(session,WORK_ID,"plan",TASK_ID,null,20)).getStatus());
        verify(store).events(CHILD.sessionRef(),0,20);
    }

    @Test void cursorRejectsDifferentMemberRunAndKindBeforeScanning() {
        for(String[] parts:List.of(
                new String[]{"delegation:foreign", "plan",TASK_ID,CHILD_RUN.runId(),"7"},
                new String[]{CHILD.sessionRef().sessionId(),"direct",TASK_ID,CHILD_RUN.runId(),"7"},
                new String[]{CHILD.sessionRef().sessionId(),"plan","different-task",CHILD_RUN.runId(),"7"},
                new String[]{CHILD.sessionRef().sessionId(),"plan",TASK_ID,"different-run","7"})) {
            String cursor=Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("\n",parts).getBytes(StandardCharsets.UTF_8));
            assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,
                    ()->details.read(session,WORK_ID,"plan",TASK_ID,cursor,20)).getStatus());
        }
        verifyNoInteractions(store);
    }

    @Test void privateAndNonTargetEmptyPagesStillAdvanceScannedCursor() {
        var privateEvent=event(7,CHILD_RUN,"agent.private-observation");
        var otherRun=event(9,new RunRef(TENANT,"other-child-run"),"agent.responded");
        when(store.events(CHILD.sessionRef(),0,2)).thenReturn(List.of(privateEvent,otherRun));
        var first=details.read(session,WORK_ID,"plan",TASK_ID,null,2);
        assertTrue(first.processes().isEmpty());assertTrue(first.hasMore());assertFalse(first.caughtUp());
        String decoded=new String(Base64.getUrlDecoder().decode(first.nextCursor()),StandardCharsets.UTF_8);
        assertTrue(decoded.endsWith("\n9"));
        verify(projection).project(any(),org.mockito.ArgumentMatchers.eq(List.of(privateEvent)));
        var next=details.read(session,WORK_ID,"plan",TASK_ID,first.nextCursor(),2);
        assertTrue(next.caughtUp());assertFalse(next.hasMore());assertEquals(first.nextCursor(),next.nextCursor());
        verify(store).events(CHILD.sessionRef(),9,2);
    }

    private void assertMissingPlan() {
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,
                ()->details.resolve(session,WORK_ID,"plan",TASK_ID)).getStatus());
    }
    private static SessionDelegations.Entry entry(String operation,RunIdentity parent,RunRef child) {
        return new SessionDelegations.Entry(TASK_ID,parent,operation,
                new SessionDelegations.Target("member-handle",MEMBER,"分析成员","订单分析",List.of(),new TypedPayload("target.v1","{}")),
                new SessionDelegations.Task("分析订单","",List.of(),"返回结论"),child,new InputId("child-input"),Optional.empty(),
                SessionDelegations.State.COMPLETED,Optional.of(new SessionDelegations.Result(SessionDelegations.State.COMPLETED,"已完成","",0,List.of())),
                Optional.empty(),2,Instant.EPOCH,Instant.EPOCH);
    }
    private static ExecutionStore.SavedEvent event(long sequence,RunRef run,String type) {
        return new ExecutionStore.SavedEvent(run,sequence,1,Instant.EPOCH,new ExecutionStore.Event(new EventId("member-event-"+sequence),type,
                new TypedPayload("application/json","{}")));
    }
}
