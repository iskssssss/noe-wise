package com.skillforge.institution.team;
import com.skillforge.institution.runtime.AgentRunBindings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.runtime.TeamRuntimeBridge;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.dag.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.SessionDelegationEngine;
import com.skillforge.runtime.session.spi.SessionDelegations;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TeamWorkControllerTest {
    @AfterEach void clear(){TenantContext.clear();}
    @Test void exactHistorySelectionAndControlsNeverDriftToLatestWork() {
        TenantContext.setTenantId("7");
        var sessions=mock(SessionService.class);var store=mock(JdbcDagWorkPort.class);
        var works=mock(TeamWorkService.class);var views=mock(TeamWorkView.class);var access=mock(ArtifactAccess.class);
        var inputs=mock(TeamWorkInputs.class);var delegations=mock(SessionDelegations.Store.class);
        var session=SessionView.builder().tenantId(7L).sessionId("session").teamId("studio").endUserId(8L).build();
        when(sessions.require("7","session")).thenReturn(session);
        when(sessions.requireWritable("7","session")).thenReturn(session);
        var owner=TeamWorkService.owner(session);var older=work(owner,"old",1);var latest=work(owner,"new",2);
        when(store.history(owner,100)).thenReturn(List.of(latest,older));when(delegations.forSession(owner,1000)).thenReturn(List.of());
        when(views.view(older)).thenReturn(snapshot("old"));
        when(views.view(latest)).thenReturn(snapshot("new"));
        when(store.read(older.key())).thenReturn(Optional.of(older));when(inputs.recent(any())).thenReturn(List.of());
        var controller=new TeamWorkController(sessions,store,works,views,access,inputs,delegations,
                mock(SessionDelegationEngine.class),mock(AgentRunBindings.class),mock(TeamRuntimeBridge.class),Clock.systemUTC(),mock(MemberTaskDetails.class));
        assertEquals("new",controller.read("session",null).data().workId());
        assertEquals("old",controller.read("session","old").data().workId());
        assertNull(controller.read("session","other").data());
        controller.control(new TeamWorkController.ControlRequest("session","input","old","", "approve_plan","",1L,"","",""));
        verify(works).control(eq(older),argThat(control->control.inputId().equals("input")&&control.planRevision()==1L));
        verify(works,never()).control(eq(latest),any());
    }
    private static TeamPublicViews.Work snapshot(String id) {
        return new TeamPublicViews.Work(id,"session",1,id,"run",1L,"running","working","active",1,null,false,"",
                new TeamPublicViews.Member("101","助手"),List.of(),List.of(),List.of(),null,0,List.of());
    }
    private static DagWork work(SessionOwner owner,String id,int seconds){
        var task=new DagPlan.Task("task","工作",new AgentId("102"),"目标",List.of(),Set.of("result"),Set.of());
        return new DagWork(new DagWork.Key(owner,new InputId(id)),new AgentId("101"),1,DagWork.Status.ACTIVE,
                new DagPlan(1,true,List.of(task)),0,0,Map.of(),Map.of(),
                new DagWork.Request("目标",Optional.of(new AgentId("101")),new RunRef(owner.sessionRef().tenantId(),"run:"+id),"",DagWork.Request.Stage.WORKING,
                        Instant.EPOCH.plusSeconds(seconds),true,"9",""));
    }
}
