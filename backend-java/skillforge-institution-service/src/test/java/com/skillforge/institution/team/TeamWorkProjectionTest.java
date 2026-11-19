package com.skillforge.institution.team;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.port.SessionOwner;
import java.util.*;
import org.junit.jupiter.api.Test;

class TeamWorkProjectionTest {
    @Test void currentCommittedRevisionKeepsStableNotificationIdentityWithoutUsingExecutionCursor() {
        var works = mock(JdbcDagWorkPort.class);
        var mailbox = mock(SessionMailboxService.class);
        var owner = SessionOwner.user(new SessionRef(new TenantId("7"), "session"), new AgentId("101"), new EndUserId(8));
        var key = new DagWork.Key(owner, new InputId("original-input"));
        var work = mock(DagWork.class);
        when(work.revision()).thenReturn(3L);
        when(works.read(key)).thenReturn(Optional.of(work));
        var ids = new ArrayList<String>();
        when(mailbox.appendOutput(eq("7"), eq("session"), anyString(), eq("work.updated"), anyMap(), any())).thenAnswer(call -> {
            ids.add(call.getArgument(2));
            assertEquals(Map.of("event", "work.updated", "data", Map.of("work_id", "original-input", "session_id", "session")), call.getArgument(4));
            return null;
        });
        new TeamWorkProjection(works, mailbox).changed(key);
        new TeamWorkProjection(works, mailbox).changed(key);
        assertEquals(ids.getFirst(), ids.getLast());
        when(work.revision()).thenReturn(4L);
        new TeamWorkProjection(works, mailbox).changed(key);
        assertNotEquals(ids.getFirst(), ids.getLast());
        verify(mailbox, times(3)).appendOutput(eq("7"), eq("session"), anyString(), eq("work.updated"), anyMap(), any());
        verifyNoMoreInteractions(mailbox);
    }
}
