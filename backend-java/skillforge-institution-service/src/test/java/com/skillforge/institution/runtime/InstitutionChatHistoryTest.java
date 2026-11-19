package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.institution.team.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class InstitutionChatHistoryTest {
    @Test void productHistoryRestoresSelectedModeAndExactMessagePagePlans() {
        var session = SessionView.builder().tenantId(7L).sessionId("session").agentId(101L).endUserId(8L).build();
        var owner = TeamWorkService.owner(session);
        var executions = mock(AgentRuntimeExecutionProjection.class);
        var plans = mock(TeamPlanHistoryReader.class);
        var log = mock(SessionLogPort.class);
        var input = new InputId("selection");
        when(log.readAfter(any(), anySet())).thenReturn(List.of(new CommittedSessionEvent(owner, input, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                new EventId("mode-on"), 1, "plan/selection", "1", new TraceEnvelope("trace", "span", input, Optional.empty()),
                new TypedPayload("skillforge.runtime.plan-selection.v1", "{\"active\":true}"), Instant.EPOCH)));
        when(executions.read(session)).thenReturn(new ChatHistoryResponse("session", List.of(), List.of(), List.of(), List.of(), ChatHistoryResponse.Collaboration.empty(), false, null, "original-cursor"));
        var expected = List.<Map<String,Object>>of(Map.of("input_id", "old-input", "approval_required", true));
        when(plans.read(session, Set.of("old-input"))).thenReturn(expected);
        var history = new InstitutionChatHistory(executions, plans, log);
        assertTrue(history.read(session).collaboration().selectedActive());
        assertEquals("original-cursor", history.read(session).eventCursor());
        assertSame(expected, history.workPlans(session, Set.of("old-input")));
        verify(plans).read(session, Set.of("old-input"));
        verifyNoMoreInteractions(plans);
    }
}
