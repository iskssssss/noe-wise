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
    @Test void directoryIncludesGoalsWithoutChangingMessageTitlesOrOwnerScope() {
        var log = mock(SessionLogPort.class);
        var events = new ArrayList<CommittedSessionEvent>();
        when(log.readAfter(any(), anySet())).thenAnswer(call -> {
            SessionLogPort.ReadSessionLog query = call.getArgument(0);
            Set<String> types = call.getArgument(1);
            return events.stream().filter(event -> event.owner().equals(query.owner())
                    && event.sessionSequence() > query.afterSequence() && types.contains(event.eventType()))
                    .limit(query.limit()).toList();
        });
        when(log.append(any())).thenAnswer(call -> {
            SessionLogPort.AppendSessionEvent command = call.getArgument(0);
            var target = command.target(); var draft = command.event();
            var event = new CommittedSessionEvent(target.owner(), target.agentId(), target.inputId(), target.runRef(),
                    target.executionRef(), target.actionRef(), target.delegationRef(), draft.eventId(), events.size() + 1L,
                    draft.eventType(), draft.eventVersion(), draft.trace(), draft.payload(), draft.occurredAt());
            events.add(event); return new SessionLogPort.AppendResult.Appended(event);
        });
        var at = Instant.parse("2026-11-20T02:00:00Z");
        var tenant = new TenantId("7"); var agent = new AgentId("101");
        var owner = SessionOwner.user(new SessionRef(tenant, "goal-session"), agent, new EndUserId(8));
        var input = new InputId("goal-start:one");
        var run = new RunIdentity(owner, agent, input, new RunRef(tenant, "run:one"),
                new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(tenant, input.value()));
        var goals = new com.skillforge.runtime.session.SessionGoal(log);
        goals.create(run, "one", "  目标标题\n" + "🧭".repeat(90), 1, at);
        goals.update(run, "done", 1, com.skillforge.runtime.session.SessionGoal.State.COMPLETE, null, at.plusSeconds(1));
        goals.create(run, "two", "新目标不覆盖首个标题", 1, at.plusSeconds(2));
        var history = new InstitutionChatHistory(mock(AgentRuntimeExecutionProjection.class), mock(TeamPlanHistoryReader.class), log);
        var ordinary = new com.skillforge.core.chat.session.SessionDirectoryItem("ordinary", null, 3, at.minusSeconds(1), 101L, null, "用户消息标题", 8L, at.minusSeconds(1));
        var goal = new com.skillforge.core.chat.session.SessionDirectoryItem("goal-session", null, 1, at, 101L, null, null, 8L, null);
        var empty = new com.skillforge.core.chat.session.SessionDirectoryItem("empty", null, 0, at.plusSeconds(10), 101L, null, null, 8L, null);
        var result = history.directory("7", List.of(ordinary, empty, goal));
        assertEquals(List.of("goal-session", "ordinary", "empty"), result.stream().map(com.skillforge.core.chat.dto.response.SessionItemResponse::sessionId).toList());
        assertEquals("目标标题 " + "🧭".repeat(75), result.getFirst().title());
        assertEquals(3, result.getFirst().messageCount());
        assertEquals(at.plusSeconds(2), result.getFirst().updatedAt());
        assertEquals("用户消息标题", result.get(1).title());
        assertNull(result.getLast().title());
        var mixed = new com.skillforge.core.chat.session.SessionDirectoryItem("goal-session", null, 1, at, 101L, null, "原用户标题", 8L, at);
        assertEquals("原用户标题", history.directory("7", List.of(mixed)).getFirst().title());
        var foreignUser = new com.skillforge.core.chat.session.SessionDirectoryItem("goal-session", null, 0, at, 101L, null, null, 9L, null);
        assertNull(history.directory("7", List.of(foreignUser)).getFirst().title());
        assertNull(history.directory("8", List.of(goal)).getFirst().title());
        var foreignTarget = new com.skillforge.core.chat.session.SessionDirectoryItem("goal-session", null, 0, at, null, "team", null, 8L, null);
        assertNull(history.directory("7", List.of(foreignTarget)).getFirst().title());
    }
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
