package com.skillforge.institution.tasks;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.SystemAgentExecution;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ConversationTask.Maintenance;
import com.skillforge.runtime.api.port.ConversationTask.MaintenancePhase;
import com.skillforge.runtime.session.spi.SessionPreparations;
import com.skillforge.runtime.session.spi.SessionTaskContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConversationTaskCoordinatorTest {
    private final TenantId tenant = new TenantId("tenant");
    private final InputId input = new InputId("input");
    private final SessionOwner owner = SessionOwner.user(new SessionRef(tenant, "session"),
            new AgentId("agent"), new EndUserId(7));

    @Test void ordinaryRequestDoesNotSearchSelectOrCallTaskModel() {
        var fixture = new Fixture();
        var source = fixture.external(owner, "message", input, "inbound.message", "input.v1",
                "{\"extraParameters\":{},\"text\":\"你好\"}");

        assertTrue(fixture.coordinator.prepare(key(1), actor(owner), List.of(source)).isEmpty());

        verify(fixture.tasks, never()).current(any(), any());
        verify(fixture.tasks, never()).search(any(), any(), anyString(), anyBoolean(), anyInt(), anyInt());
        verify(fixture.tasks, never()).find(any(), any(), anyString());
        verify(fixture.log, never()).append(any());
        verifyNoInteractions(fixture.execution);
    }

    @Test void explicitOriginalInputFreezesSafeSnapshotAcrossIterations() throws Exception {
        var fixture = new Fixture();
        var initial = fixture.task("task", 1, "旧摘要");
        fixture.stored.put("task", initial);
        var source = fixture.external(owner, "message", input, "inbound.message", "input.v1",
                "{\"extraParameters\":{\"task_id\":\"task\"},\"text\":\"继续\"}");

        var first = fixture.coordinator.prepare(key(1), actor(owner), List.of(source)).orElseThrow();
        fixture.stored.put("task", fixture.task("task", 2, "新摘要"));
        var second = fixture.coordinator.prepare(key(2), actor(owner), List.of()).orElseThrow();

        assertEquals(first, second);
        var body = fixture.json.readTree(first.payload().canonicalJson());
        assertEquals("task", body.path("task").path("taskId").asText());
        assertEquals(1, body.path("task").path("revision").asLong());
        assertEquals("旧摘要", body.path("task").path("summary").asText());
        assertEquals("loop", body.path("loopId").asText());
        assertTrue(body.path("selectionId").asText().startsWith("task-selection:"));
        assertFalse(body.path("task").has("sources"));
        assertFalse(body.path("task").has("maintenance"));
        assertFalse(body.path("task").has("tenantId"));
        verify(fixture.tasks, times(1)).find(owner, identity().agentId(), "task");
        verify(fixture.tasks, never()).current(any(), any());
        verifyNoInteractions(fixture.execution);
    }

    @Test void ignoresTaskIdsOutsideOriginalInputAndRejectsMissingOwnedSelection() {
        var fixture = new Fixture();
        var original = fixture.external(owner, "message", input, "inbound.message", "input.v1",
                "{\"extraParameters\":{},\"text\":\"新请求\"}");
        var otherInput = new InputId("old-input");
        var old = fixture.external(owner, "old", otherInput, "inbound.message", "input.v1",
                "{\"extraParameters\":{\"task_id\":\"old-task\"},\"text\":\"旧请求\"}");

        assertTrue(fixture.coordinator.prepare(key(1), actor(owner), List.of(original, old)).isEmpty());

        var selected = new Fixture();
        var missing = selected.external(owner, "missing", input, "inbound.message", "input.v1",
                "{\"extraParameters\":{\"task_id\":\"foreign\"},\"text\":\"继续\"}");
        assertThrows(SessionTaskContext.SelectionRejected.class,
                () -> selected.coordinator.prepare(key(1), actor(owner), List.of(missing)));
        verify(selected.tasks).find(owner, identity().agentId(), "foreign");
        verifyNoInteractions(selected.execution);
    }

    @Test void explicitManagementCorrectionAloneCallsModelAndUsesCasSources() {
        var fixture = new Fixture();
        var before = fixture.task("task", 1, "旧摘要");
        fixture.stored.put("task", before);
        var correction = fixture.external(owner, "correction", input, "task.user-correction",
                "skillforge.task-management.v1", "{\"text\":\"门店改为二店\"}");
        var run = mock(com.skillforge.core.agent.systemagent.SystemAgentTypes.RunView.class);
        when(run.status()).thenReturn("COMPLETED");
        when(run.output()).thenReturn("{\"action\":\"update\",\"taskId\":\"task\","
                + "\"summary\":\"已改为二店\",\"facts\":[{\"key\":\"门店\",\"value\":\"二店\","
                + "\"sourceEventId\":\"correction\",\"confirmed\":true}]}");
        when(fixture.execution.executePurpose(anyString(), anyString(), anyString(), anyString())).thenReturn(run);

        var updated = fixture.coordinator.correct(identity(), "management", "task", correction);

        assertEquals(2, updated.revision());
        assertEquals("已改为二店", updated.summary());
        assertTrue(updated.state().canonicalJson().contains("\"confirmed\":true"));
        assertEquals(before.maintenance(), updated.maintenance());
        assertTrue(updated.sources().stream().anyMatch(source -> source.eventId().equals(correction.eventId())));
        verify(fixture.execution, times(1)).executePurpose(eq("task-context"), eq("tenant"),
                eq("management:model:0"), anyString());
        verify(fixture.tasks).acquire(eq(owner), eq(identity().agentId()), eq("task"), eq("run"), any(), any());
        verify(fixture.tasks).release(eq(owner), eq(identity().agentId()), eq("task"), eq("run"), eq(1L));
        verify(fixture.tasks).commit(argThat(change -> change.expectedRevision() == 1
                && change.task().revision() == 2 && change.select()));
    }

    @Test void managementReceiptReadsOnlyCommittedOriginalActorAndTask() {
        var fixture = new Fixture();
        fixture.stored.put("task", fixture.task("task", 1, "旧摘要"));
        fixture.stored.put("other", fixture.task("other", 1, "另一任务"));
        var management = new ConversationTaskManagement(mock(com.skillforge.core.chat.session.SessionService.class),
                fixture.tasks, fixture.coordinator, fixture.json, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        assertTrue(management.receipt(owner, "task", "operation-1", "actor-1").isEmpty());
        var committed = management.update(owner, "task", 1, "complete", null, "operation-1", "actor-1");
        int eventCount = fixture.events.size();
        assertEquals(committed, management.receipt(owner, "task", "operation-1", "actor-1").orElseThrow());
        assertTrue(management.receipt(owner, "task", "operation-1", "actor-2").isEmpty());
        assertTrue(management.receipt(owner, "other", "operation-1", "actor-1").isEmpty());
        assertEquals(eventCount, fixture.events.size());
        verify(fixture.tasks, times(1)).commit(any());
        verifyNoInteractions(fixture.execution);
    }

    @Test void staleManagementRevisionIsExplicitlyRejectedBeforeMutation() {
        var fixture = new Fixture();
        fixture.stored.put("task", fixture.task("task", 2, "现有摘要"));
        var management = new ConversationTaskManagement(mock(com.skillforge.core.chat.session.SessionService.class),
                fixture.tasks, fixture.coordinator, fixture.json, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var failure = assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                () -> management.update(owner, "task", 1, "complete", null, "stale-operation", "actor"));
        assertEquals("TASK_OPERATION_REJECTED_BEFORE_MUTATION", failure.getCode());
        assertTrue(management.receipt(owner, "task", "stale-operation", "actor").isEmpty());
        verify(fixture.tasks, never()).commit(any());
        verifyNoInteractions(fixture.execution);
    }

    private SessionPreparations.Key key(int iteration) {
        return new SessionPreparations.Key(identity(), "loop", iteration);
    }

    private RunIdentity identity() {
        return new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("result"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
    }

    private VerifiedIdentity actor(SessionOwner actorOwner) {
        var actor = mock(VerifiedIdentity.class);
        when(actor.owner()).thenReturn(actorOwner);
        return actor;
    }

    private final class Fixture {
        private final ConversationTaskPort tasks = mock(ConversationTaskPort.class);
        private final SessionLogPort log = mock(SessionLogPort.class);
        private final SystemAgentExecution execution = mock(SystemAgentExecution.class);
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final Map<EventId, CommittedSessionEvent> events = new LinkedHashMap<>();
        private final Map<String, ConversationTask> stored = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong();
        private final ConversationTaskCoordinator coordinator = new ConversationTaskCoordinator(
                tasks, log, execution, json, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        private Fixture() {
            when(log.findByEvent(any(), any())).thenAnswer(call -> {
                var event = events.get((EventId) call.getArgument(1));
                return event != null && event.owner().equals(call.getArgument(0))
                        ? Optional.of(event) : Optional.empty();
            });
            when(log.append(any())).thenAnswer(call -> {
                SessionLogPort.AppendSessionEvent command = call.getArgument(0);
                var draft = command.event();
                var target = command.target();
                var committed = new CommittedSessionEvent(target.owner(), target.inputId(), target.runRef(),
                        target.executionRef(), target.actionRef(), target.delegationRef(), draft.eventId(),
                        sequence.incrementAndGet(), draft.eventType(), draft.eventVersion(), draft.trace(),
                        draft.payload(), draft.occurredAt());
                events.put(committed.eventId(), committed);
                return new SessionLogPort.AppendResult.Appended(committed);
            });
            when(tasks.find(any(), any(), anyString())).thenAnswer(call -> {
                var task = stored.get((String) call.getArgument(2));
                return task != null && task.belongsTo(call.getArgument(0), call.getArgument(1))
                        ? Optional.of(task) : Optional.empty();
            });
            when(tasks.acquire(any(), any(), anyString(), anyString(), any(), any())).thenAnswer(call ->
                    new ConversationTaskPort.Lease(call.getArgument(2), call.getArgument(3), 1, call.getArgument(5)));
            when(tasks.commit(any())).thenAnswer(call -> {
                ConversationTaskPort.Change change = call.getArgument(0);
                var previous = stored.get(change.task().taskId());
                if ((previous == null ? 0 : previous.revision()) != change.expectedRevision())
                    return new ConversationTaskPort.Result.Conflict("revision");
                stored.put(change.task().taskId(), change.task());
                var command = change.event();
                var draft = command.event();
                var target = command.target();
                var event = new CommittedSessionEvent(target.owner(), target.inputId(), target.runRef(),
                        target.executionRef(), target.actionRef(), target.delegationRef(), draft.eventId(),
                        sequence.incrementAndGet(), draft.eventType(), draft.eventVersion(), draft.trace(),
                        draft.payload(), draft.occurredAt());
                events.put(event.eventId(), event);
                return new ConversationTaskPort.Result.Committed(change.task(), event, false);
            });
        }

        private ConversationTask task(String id, long revision, String summary) {
            var cursor = new Maintenance(owner, id, identity().runRef(), "old", MaintenancePhase.ENTRY,
                    0, 1, 0, List.of());
            return new ConversationTask(id, tenant, owner.requireUser(), owner.target().requireAgent(), revision,
                    ConversationTask.Status.OPEN, "目标", summary,
                    new TypedPayload(ConversationTask.STATE_SCHEMA, "{\"facts\":{},\"pending\":[]}"),
                    List.of(new ConversationTask.Source(owner, new EventId("seed"))), List.of(cursor), Instant.EPOCH);
        }

        private CommittedSessionEvent external(SessionOwner sourceOwner, String eventId, InputId sourceInput,
                                                String type, String schema, String payload) {
            var event = new CommittedSessionEvent(sourceOwner, sourceInput, Optional.of(identity().runRef()),
                    Optional.empty(), Optional.empty(), Optional.empty(), new EventId(eventId),
                    sequence.incrementAndGet(), type, "1", identity().trace(), new TypedPayload(schema, payload),
                    Instant.EPOCH);
            events.put(event.eventId(), event);
            return event;
        }
    }
}
