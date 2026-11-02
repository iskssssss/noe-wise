package com.skillforge.institution.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.session.SessionOutboxRecoveryScan;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.institution.runtime.AgentRuntimeExecutionProjection;
import com.skillforge.institution.group.GroupHistorySourcePublisher;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.SessionLogPort;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 唯一 Worker 提供租户和节拍；投递恢复只消费 chat 签发的候选和游标。 */
class SessionOutboxDispatcherTest {
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void recoversTypedAndHumanEventsWithinTenantAndRestoresCallingContext() {
        var sessions = mock(SessionOutboxRecoveryScan.class); var projection = mock(AgentRuntimeExecutionProjection.class);
        var human = mock(SessionLogPort.class); var session = session("s-1");
        var cursor = mock(SessionOutboxRecoveryScan.Cursor.class);
        when(sessions.scan("7", null, 10)).thenAnswer(call -> {
            assertEquals("7", TenantContext.getTenantId());
            return new SessionOutboxRecoveryScan.Batch(List.of(session), cursor);
        });
        var recovery = new SessionOutboxDispatcher(sessions, projection, human, mock(GroupHistorySourcePublisher.class));
        TenantContext.setTenantId("8"); recovery.recover(new TenantId("7"), 64);
        verify(projection).reconcile(session); verify(projection).reconcileHuman(session, human);
        assertEquals("8", TenantContext.getTenantId());
    }

    @Test void eachTenantContinuesOnlyItsOwnOpaqueCursor() {
        var sessions = mock(SessionOutboxRecoveryScan.class); var projection = mock(AgentRuntimeExecutionProjection.class);
        var human = mock(SessionLogPort.class);
        var firstCursor = mock(SessionOutboxRecoveryScan.Cursor.class);
        var otherCursor = mock(SessionOutboxRecoveryScan.Cursor.class);
        when(sessions.scan("7", null, 10)).thenReturn(new SessionOutboxRecoveryScan.Batch(List.of(), firstCursor));
        when(sessions.scan("8", null, 10)).thenReturn(new SessionOutboxRecoveryScan.Batch(List.of(), otherCursor));
        when(sessions.scan("7", firstCursor, 10)).thenReturn(new SessionOutboxRecoveryScan.Batch(List.of(), firstCursor));
        var recovery = new SessionOutboxDispatcher(sessions, projection, human, mock(GroupHistorySourcePublisher.class));
        recovery.recover(new TenantId("7"), 10); recovery.recover(new TenantId("8"), 10); recovery.recover(new TenantId("7"), 10);
        verify(sessions).scan("7", firstCursor, 10);
        verifyNoInteractions(projection, human); assertNull(TenantContext.getTenantId());
    }

    @Test void projectionFailureLeavesOtherSessionsDeliverableAndRetriesOnNextRotation() {
        var sessions = mock(SessionOutboxRecoveryScan.class); var projection = mock(AgentRuntimeExecutionProjection.class);
        var human = mock(SessionLogPort.class); var first = session("s-1"); var next = session("s-2");
        var cursor = mock(SessionOutboxRecoveryScan.Cursor.class);
        when(sessions.scan(eq("7"), any(), eq(10))).thenReturn(new SessionOutboxRecoveryScan.Batch(List.of(first, next), cursor));
        doThrow(new IllegalStateException("database unavailable")).doNothing().when(projection).reconcile(first);
        var recovery = new SessionOutboxDispatcher(sessions, projection, human, mock(GroupHistorySourcePublisher.class));
        recovery.recover(new TenantId("7"), 10); recovery.recover(new TenantId("7"), 10);
        verify(projection, times(2)).reconcile(first); verify(projection, times(2)).reconcile(next);
        verify(projection).reconcileHuman(first, human); verify(projection, times(2)).reconcileHuman(next, human);
        verify(sessions).scan("7", cursor, 10); assertNull(TenantContext.getTenantId());
    }

    @Test void groupSessionRecoversSourceAndRuntimeWithoutInventedPersonalOwner() {
        var sessions = mock(SessionOutboxRecoveryScan.class); var projection = mock(AgentRuntimeExecutionProjection.class);
        var sources = mock(GroupHistorySourcePublisher.class); var human = mock(SessionLogPort.class);
        var group = SessionView.builder().tenantId(7L).sessionId("group-session").groupBindingId(41L)
                .integrationId("integration").businessScopeId("scope").scopeType("group:integration").scopeId("41").build();
        when(sessions.scan("7", null, 10)).thenReturn(new SessionOutboxRecoveryScan.Batch(List.of(group), mock(SessionOutboxRecoveryScan.Cursor.class)));
        new SessionOutboxDispatcher(sessions, projection, human, sources).recover(new TenantId("7"), 10);
        verify(sources).reconcile(group); verify(projection).reconcile(group);
        verify(projection, never()).reconcileHuman(any(), any()); verifyNoInteractions(human);
    }

    private static SessionView session(String sessionId) {
        return SessionView.builder().tenantId(7L).sessionId(sessionId).agentId(3L).endUserId(9L).build();
    }
}
