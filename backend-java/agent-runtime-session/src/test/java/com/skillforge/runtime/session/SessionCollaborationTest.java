package com.skillforge.runtime.session;

import static com.skillforge.runtime.session.SessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SessionCollaborationTest {
    @Test void realExecutionLifecycleFreezesSelectionAndKeepsNewerChoicePending() {
        var log = new Log(); var state = new SessionCollaboration(log);
        log.accepted(IDENTITY, SessionCollaboration.Selection.inactive());
        state.select(ADMISSION, IDENTITY.agentId(), "on", true, IDENTITY.trace(), NOW);
        assertFalse(state.read(OWNER).active()); assertTrue(state.read(OWNER).pending());
        var frozen = state.selected(OWNER);
        log.completed(IDENTITY);
        assertFalse(state.read(OWNER).active(), "终态不会追改Run的冻结配置");
        var next = nextRun("next");
        log.accepted(next, frozen);
        assertTrue(new SessionCollaboration(log).read(OWNER).active());
        assertFalse(state.read(OWNER).pending());
        state.select(ADMISSION, IDENTITY.agentId(), "off", false, IDENTITY.trace(), NOW);
        assertTrue(state.read(OWNER).active()); assertTrue(state.read(OWNER).pending());
        log.completed(IDENTITY); // 旧Run终态不能关闭仍在执行的next。
        state.select(ADMISSION, IDENTITY.agentId(), "still-off", false, IDENTITY.trace(), NOW);
        assertTrue(state.read(OWNER).active());
        log.completed(next);
        log.accepted(nextRun("third"), state.selected(OWNER));
        assertFalse(state.read(OWNER).active()); assertFalse(state.read(OWNER).pending());
    }

    @Test void selectionAfterFreezeRemainsPendingAndReplayCannotOverrideIt() {
        var log = new Log(); var state = new SessionCollaboration(log);
        var frozen = state.selected(OWNER);
        state.select(ADMISSION, IDENTITY.agentId(), "new", true, IDENTITY.trace(), NOW);
        log.accepted(IDENTITY, frozen);
        assertFalse(state.read(OWNER).active()); assertTrue(state.read(OWNER).pending());
        state.select(ADMISSION, IDENTITY.agentId(), "new", true, IDENTITY.trace(), NOW.plusSeconds(1));
        assertFalse(state.read(OWNER).active()); assertTrue(state.read(OWNER).selectedActive());
        assertThrows(RuntimeException.class, () -> state.select(ADMISSION, IDENTITY.agentId(), "new", false, IDENTITY.trace(), NOW));
        assertFalse(state.read(SessionOwner.user(OWNER.sessionRef(), new AgentId("different"), OWNER.requireUser())).active());
    }

    @Test void approvedExitWaitsForNextAcceptedRunAndWaitingDoesNotMakeSessionIdle() {
        var log = new Log(); var state = new SessionCollaboration(log);
        state.select(ADMISSION, IDENTITY.agentId(), "on", true, IDENTITY.trace(), NOW);
        log.accepted(IDENTITY, state.selected(OWNER));
        log.event("execution.waiting", "waiting");
        state.requestPlanExit(IDENTITY, "review", NOW);
        assertTrue(state.read(OWNER).active()); assertTrue(state.read(OWNER).pending());
        log.completed(IDENTITY);
        assertTrue(state.read(OWNER).active());
        log.accepted(nextRun("approved"), state.selected(OWNER));
        assertFalse(state.read(OWNER).active()); assertFalse(state.read(OWNER).pending());
    }

    @Test void admissionSelectionReadsOnlySelectionEventsAcrossPages() {
        var log = new Log(); var state = new SessionCollaboration(log);
        for (int i = 0; i < 101; i++) {
            var run = nextRun("mixed-" + i);
            log.accepted(run, SessionCollaboration.Selection.inactive());
            log.completed(run);
        }
        assertEquals(SessionCollaboration.Selection.inactive(), state.selected(OWNER));
        assertEquals(1, log.filteredReads); assertEquals(0, log.filteredEvents);

        for (int i = 0; i < 101; i++) {
            state.select(ADMISSION, IDENTITY.agentId(), "selection-" + i, i % 2 == 0, IDENTITY.trace(), NOW);
            var run = nextRun("selected-" + i);
            log.accepted(run, state.selected(OWNER));
            log.completed(run);
        }
        var frozen = state.selected(OWNER);
        assertTrue(frozen.active());
        var exit = state.requestPlanExit(IDENTITY, "filtered-exit", NOW);
        log.filteredReads = 0; log.filteredEvents = 0;
        var selected = state.selected(OWNER);
        assertFalse(selected.active());
        assertEquals(exit.eventId().value(), selected.sourceEventId());
        assertEquals(exit.sessionSequence(), selected.sourceSequence());
        assertTrue(frozen.active(), "后续选择不能改变已冻结值");
        assertEquals(2, log.filteredReads); assertEquals(102, log.filteredEvents);
        assertEquals(Set.of(SessionCollaboration.SELECT), log.lastTypes);
        assertTrue(state.read(OWNER).active()); assertTrue(state.read(OWNER).pending());
        assertEquals(Set.of(SessionCollaboration.SELECT, SessionCollaboration.MODE,
                AgentExecutionProgram.ACCEPTED_EVENT, AgentExecutionProgram.COMPLETED_EVENT), log.lastTypes);
    }

    @Test void admissionSelectionRejectsWrongOwnerAndNonIncreasingSequence() {
        var source = new Log();
        new SessionCollaboration(source).select(ADMISSION, IDENTITY.agentId(), "selected", true, IDENTITY.trace(), NOW);
        var event = source.events.values().iterator().next();
        var wrongOwner = SessionOwner.user(OWNER.sessionRef(), new AgentId("different"), OWNER.requireUser());
        var malformed = new Log() {
            @Override public List<CommittedSessionEvent> readAfter(ReadSessionLog query, Set<String> types) {
                return query.owner().equals(wrongOwner) ? List.of(event) : List.of(event, event);
            }
        };
        var state = new SessionCollaboration(malformed);
        assertThrows(IllegalStateException.class, () -> state.selected(wrongOwner));
        assertThrows(IllegalStateException.class, () -> state.selected(OWNER));
    }

    private static RunIdentity nextRun(String id) {
        return new RunIdentity(OWNER, IDENTITY.agentId(), IDENTITY.causationInputId(), new RunRef(TENANT, id),
                IDENTITY.trace(), IDENTITY.causationIdentityRef(), IDENTITY.requester(), IDENTITY.sourceMessageRef(), IDENTITY.originRef());
    }

    static class Log implements SessionLogPort {
        final Map<String, CommittedSessionEvent> events = new LinkedHashMap<>();
        int filteredReads;
        int filteredEvents;
        Set<String> lastTypes = Set.of();
        @Override public AppendResult append(AppendSessionEvent command) {
            var target = command.target(); var draft = command.event();
            String key = target.owner() + ":" + draft.eventId();
            var prior = events.get(key);
            if (prior != null) {
                SessionEventValues.requireAppend(command, prior, 0);
                return new AppendResult.Replayed(prior);
            }
            var event = new CommittedSessionEvent(target.owner(), target.inputId(), target.runRef(), target.executionRef(),
                    target.actionRef(), target.delegationRef(), draft.eventId(), events.size() + 1, draft.eventType(),
                    draft.eventVersion(), draft.trace(), draft.payload(), draft.occurredAt());
            events.put(key, event); return new AppendResult.Appended(event);
        }
        @Override public Optional<CommittedSessionEvent> findByEvent(SessionOwner owner, EventId id) {
            return events.values().stream().filter(event -> event.owner().equals(owner) && event.eventId().equals(id)).findFirst();
        }
        @Override public List<CommittedSessionEvent> readAfter(ReadSessionLog query) {
            return events.values().stream().filter(event -> event.owner().equals(query.owner()) && event.sessionSequence() > query.afterSequence())
                    .limit(query.limit()).toList();
        }
        @Override public List<CommittedSessionEvent> readAfter(ReadSessionLog query, Set<String> eventTypes) {
            lastTypes = Set.copyOf(eventTypes); filteredReads++;
            var page = events.values().stream().filter(event -> event.owner().equals(query.owner())
                    && event.sessionSequence() > query.afterSequence() && lastTypes.contains(event.eventType()))
                    .limit(query.limit()).toList();
            filteredEvents += page.size();
            return page;
        }
        void accepted(RunIdentity run, SessionCollaboration.Selection selection) {
            appendEvent(run, AgentExecutionProgram.admissionEvent(run));
            appendEvent(run, SessionCollaboration.admissionMode(run, selection));
        }
        void completed(RunIdentity run) {
            appendEvent(run, new com.skillforge.runtime.session.spi.ExecutionStore.Event(
                    new EventId("completed:" + run.runRef().runId()), AgentExecutionProgram.COMPLETED_EVENT,
                    new TypedPayload(AgentExecutionProgram.COMPLETED_SCHEMA, "{\"status\":\"completed\"}")));
        }
        void appendEvent(RunIdentity run, com.skillforge.runtime.session.spi.ExecutionStore.Event event) {
            append(new AppendSessionEvent(SessionCollaboration.target(run), new SessionEventDraft(event.id(), event.type(),
                    "1", "fixture", run.trace(), event.data(), NOW)));
        }
        void event(String type, String id) {
            append(new AppendSessionEvent(SessionCollaboration.target(IDENTITY), new SessionEventDraft(new EventId(id), type,
                    "1", "fixture", IDENTITY.trace(), new TypedPayload("fixture.v1", "{}"), NOW)));
        }
    }
}
