package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.SystemAgentExecution;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.institution.tasks.ConversationTaskCoordinator;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConversationTaskUpdateToolHandlerTest {
    private final TenantId tenant = new TenantId("tenant");
    private final SessionOwner owner = SessionOwner.user(new SessionRef(tenant, "session"),
            new AgentId("agent"), new EndUserId(1));

    @Test void createsOnceFromInvocationAndAppliesIncrementalCasPatch() {
        var fixture = new Fixture();
        fixture.external(owner, "user-source", "inbound.message");
        Map<String, Object> create = Map.of("title", "门店计划", "summary", "初始摘要",
                "facts", List.of(Map.of("key", "门店", "value", "一店", "source_event_id", "user-source",
                        "confirmed", true)), "pending", List.of("确认日期"));

        var first = fixture.handler.execute(create, fixture.context("create", "attempt-1"));
        var replay = fixture.handler.execute(create, fixture.context("create", "attempt-2"));

        var created = (Map<?, ?>) first.get("task");
        assertEquals(created, replay.get("task"));
        assertTrue(((String) created.get("task_id")).startsWith("task-"));
        assertEquals(1L, created.get("revision"));
        assertEquals("WAITING_USER", created.get("status"));
        var state = (Map<?, ?>) created.get("state");
        var fact = (Map<?, ?>) ((Map<?, ?>) state.get("facts")).get("门店");
        assertEquals(true, fact.get("confirmed"));
        assertEquals(1, fixture.commits.get());

        var changed = new LinkedHashMap<>(create);
        changed.put("summary", "漂移参数");
        assertEquals(Map.of("success", false, "error", "idempotency_conflict"),
                fixture.handler.execute(changed, fixture.context("create", "attempt-3")));
        assertEquals(1, fixture.commits.get());

        String taskId = (String) created.get("task_id");
        var updated = fixture.handler.execute(Map.of("task_id", taskId, "expected_revision", 1,
                "summary", "已确认日期", "pending", List.of()), fixture.context("update", "attempt"));
        var updateView = (Map<?, ?>) updated.get("task");
        assertEquals(2L, updateView.get("revision"));
        assertEquals("OPEN", updateView.get("status"));
        assertEquals("已确认日期", updateView.get("summary"));
        assertEquals(2, fixture.commits.get());

        assertEquals(Map.of("success", false, "error", "revision_conflict"), fixture.handler.execute(
                Map.of("task_id", taskId, "expected_revision", 1, "summary", "过期覆盖"),
                fixture.context("stale", "attempt")));
        assertEquals(2, fixture.commits.get());
    }

    @Test void onlySavedCrossSessionSourcesCanBackFactsAndToolObservationsStayUnconfirmed() {
        var fixture = new Fixture();
        fixture.external(owner, "seed", "inbound.message");
        var created = fixture.handler.execute(Map.of("title", "任务"), fixture.context("create", "attempt"));
        String taskId = (String) ((Map<?, ?>) created.get("task")).get("task_id");
        var other = SessionOwner.user(new SessionRef(tenant, "other"), owner.target().requireAgent(), owner.requireUser());
        var observation = fixture.external(other, "foreign-observation", "agent.tool-result");

        assertEquals(Map.of("success", false, "error", "fact_source_invalid"),
                fixture.handler.execute(Map.of("task_id", taskId,
                "expected_revision", 1, "facts", List.of(Map.of("key", "结果", "value", "完成",
                        "source_event_id", "foreign-observation", "confirmed", true))),
                fixture.context("unsaved-source", "attempt")));

        var before = fixture.stored.get(taskId);
        var allowedSources = new ArrayList<>(before.sources());
        allowedSources.add(new ConversationTask.Source(other, observation.eventId()));
        fixture.stored.put(taskId, new ConversationTask(before.taskId(), before.tenantId(), before.endUserId(),
                before.agentId(), before.revision(), before.status(), before.title(), before.summary(), before.state(),
                allowedSources, before.maintenance(), before.updatedAt()));

        var updated = fixture.handler.execute(Map.of("task_id", taskId, "expected_revision", 1,
                "facts", List.of(Map.of("key", "结果", "value", "完成",
                        "source_event_id", "foreign-observation", "confirmed", true))),
                fixture.context("saved-source", "attempt"));

        var state = (Map<?, ?>) ((Map<?, ?>) updated.get("task")).get("state");
        var fact = (Map<?, ?>) ((Map<?, ?>) state.get("facts")).get("结果");
        assertEquals(false, fact.get("confirmed"));
        assertEquals("foreign-observation", fact.get("source_event_id"));
        assertFalse(fact.containsKey("sourceSessionId"));
    }

    @Test void rejectsMissingCasCreateTitleAndIdentityArgumentsBeforeWriting() {
        var fixture = new Fixture();
        var invalid = List.of(Map.<String, Object>of("task_id", "task", "summary", "x"),
                Map.<String, Object>of("summary", "x"),
                Map.<String, Object>of("task_id", "task", "expected_revision", 1),
                Map.<String, Object>of("title", "x", "tenant_id", "other"),
                Map.<String, Object>of("title", "x", "facts", List.of(Map.of("key", "k", "value", 1,
                        "source_event_id", "source"))));
        invalid.forEach(arguments -> assertEquals(Map.of("success", false, "error", "invalid_arguments"),
                fixture.handler.execute(arguments, fixture.context("invalid-" + arguments.hashCode(), "attempt"))));
        assertEquals(0, fixture.commits.get());
        verify(fixture.log, never()).append(any());
    }

    private final class Fixture {
        private final ConversationTaskPort port = mock(ConversationTaskPort.class);
        private final SessionLogPort log = mock(SessionLogPort.class);
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final Map<EventId, CommittedSessionEvent> events = new LinkedHashMap<>();
        private final Map<String, ConversationTask> stored = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicInteger commits = new AtomicInteger();
        private final ConversationTaskUpdateToolHandler handler;

        private Fixture() {
            var coordinator = new ConversationTaskCoordinator(port, log, mock(SystemAgentExecution.class), json,
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            handler = new ConversationTaskUpdateToolHandler(coordinator, json);
            when(log.findByEvent(any(), any())).thenAnswer(call -> {
                var event = events.get((EventId) call.getArgument(1));
                return event != null && event.owner().equals(call.getArgument(0))
                        ? Optional.of(event) : Optional.empty();
            });
            when(log.append(any())).thenAnswer(call -> {
                SessionLogPort.AppendSessionEvent command = call.getArgument(0);
                var event = commit(command);
                events.put(event.eventId(), event);
                return new SessionLogPort.AppendResult.Appended(event);
            });
            when(port.find(any(), any(), anyString())).thenAnswer(call -> {
                var task = stored.get((String) call.getArgument(2));
                return task != null && task.belongsTo(call.getArgument(0), call.getArgument(1))
                        ? Optional.of(task) : Optional.empty();
            });
            when(port.acquire(any(), any(), anyString(), anyString(), any(), any())).thenAnswer(call ->
                    new ConversationTaskPort.Lease(call.getArgument(2), call.getArgument(3), 9, call.getArgument(5)));
            when(port.commit(any())).thenAnswer(call -> {
                ConversationTaskPort.Change change = call.getArgument(0);
                var before = stored.get(change.task().taskId());
                if ((before == null ? 0 : before.revision()) != change.expectedRevision())
                    return new ConversationTaskPort.Result.Conflict("revision");
                commits.incrementAndGet();
                stored.put(change.task().taskId(), change.task());
                var event = commit(change.event());
                events.put(event.eventId(), event);
                return new ConversationTaskPort.Result.Committed(change.task(), event, false);
            });
        }

        private CommittedSessionEvent external(SessionOwner sourceOwner, String id, String type) {
            var run = run(sourceOwner);
            var event = new CommittedSessionEvent(sourceOwner, run.causationInputId(), Optional.of(run.runRef()),
                    Optional.empty(), Optional.empty(), Optional.empty(), new EventId(id), sequence.incrementAndGet(),
                    type, "1", run.trace(), new TypedPayload("source.v1", "{}"), Instant.EPOCH);
            events.put(event.eventId(), event);
            return event;
        }

        private CommittedSessionEvent commit(SessionLogPort.AppendSessionEvent command) {
            var target = command.target();
            var draft = command.event();
            return new CommittedSessionEvent(target.owner(), target.inputId(), target.runRef(), target.executionRef(),
                    target.actionRef(), target.delegationRef(), draft.eventId(), sequence.incrementAndGet(),
                    draft.eventType(), draft.eventVersion(), draft.trace(), draft.payload(), draft.occurredAt());
        }

        private PlatformToolExecutionContext context(String invocation, String attempt) {
            var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                    Set.of(), 1, new ResultRef("verification"));
            return new PlatformToolExecutionContext(new PortCallContext(run(owner), identity,
                    new InvocationControl(Instant.now().plusSeconds(30), () -> false)), invocation, attempt);
        }

        private RunIdentity run(SessionOwner runOwner) {
            var input = new InputId("input");
            return new RunIdentity(runOwner, new AgentId((runOwner).target().id()), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("result"), new RunRequester.EndUser((runOwner).requireUser()), java.util.Optional.empty(), new RunOriginRef((runOwner).sessionRef().tenantId(), "input:" + (input).value()));
        }
    }
}
