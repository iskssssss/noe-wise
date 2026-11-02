package com.skillforge.institution.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.dag.DagWork;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TeamPlanHistoryReaderTest {
    @Test void currentPageReadsExactOldInputWithoutHistoryLimit() {
        var session=SessionView.builder().tenantId(7L).sessionId("session").agentId(101L).endUserId(8L).build();
        var plans=mock(JdbcDagWorkPort.class);var views=mock(TeamWorkView.class);
        var key=new DagWork.Key(TeamWorkService.owner(session),new InputId("old-input"));
        var work=mock(DagWork.class);when(work.plan()).thenReturn(mock(com.skillforge.runtime.api.dag.DagPlan.class));
        when(work.request()).thenReturn(new DagWork.Request("旧任务",Optional.empty(),new com.skillforge.runtime.api.RunRef(key.owner().sessionRef().tenantId(),"run"),""));
        when(plans.read(key)).thenReturn(Optional.of(work));
        var snapshot=new TeamPublicViews.Work("old-input","session",2,"old-input","run",3L,"running","working","active",1,null,false,"",
                new TeamPublicViews.Member("101","助手"),List.of(),List.of(),List.of(),null,0,List.of());
        when(views.view(work)).thenReturn(snapshot);
        var result=new TeamPlanHistoryReader(plans,views,new ObjectMapper()).read(session,Set.of("old-input"));
        assertEquals(1,result.size());assertEquals("old-input",result.getFirst().get("input_id"));
        verify(plans).read(key);verifyNoMoreInteractions(plans);
    }
}
