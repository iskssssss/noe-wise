package com.skillforge.runtime.adapter.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.DelegationRef;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionLogPort.AppendResult;
import com.skillforge.runtime.api.port.SessionLogPort.AppendSessionEvent;
import com.skillforge.runtime.api.port.SessionLogPort.ReadSessionLog;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 所有 {@link SessionLogPort} 实现共享的可观察行为契约。
 *
 * <p>具体测试类只需返回一个全新空 Port；测试不依赖适配器框架、数据库或宿主模块。
 */
public abstract class SessionLogPortContract {

    private static final TenantId TENANT = new TenantId("tenant-main");
    private static final SessionOwner OWNER = SessionOwner.user(
            new SessionRef(TENANT, "session-main"),
            new AgentId("agent-main"),
            new EndUserId(101));
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-03T10:15:30.123456789Z");
    private static final EventId FULL_EVENT_ID = new EventId("event-full");

    private SessionLogPort port;

    /** 为每个继承测试创建全新且逻辑为空的存储。 */
    protected abstract SessionLogPort createPort();

    @BeforeEach
    protected final void createEmptyPort() {
        SessionLogPort created = createPort();
        assertNotNull(created, "createPort must return a SessionLogPort");
        port = created;
        assertTrue(port.findByEvent(OWNER, FULL_EVENT_ID).isEmpty(),
                "createPort must expose an empty store to every test");
        assertTrue(port.readAfter(new ReadSessionLog(OWNER, 0, 1)).isEmpty(),
                "createPort must expose an empty store to every test");
    }

    @Test
    protected final void roundTripsCompleteTargetTraceAndTypedPayload() {
        AppendSessionEvent command = fullCommand();

        CommittedSessionEvent appended = append(command, 1);

        assertEquals(committed(command, 1), appended);
        assertEquals(Optional.of(appended), port.findByEvent(OWNER, FULL_EVENT_ID));
        assertEquals(List.of(appended), port.readAfter(new ReadSessionLog(OWNER, 0, 10)));
        assertEquals("{\"emoji\":\"🧭\",\"message\":\"你好，世界\",\"nested\":{\"ok\":true}}",
                appended.payload().canonicalJson());
        assertEquals(OCCURRED_AT, appended.occurredAt());
    }

    /** 持久USER来源读取返回原target；任何其他用户、会话、租户或事件均不可见。 */
    @Test
    protected final void readsUserSourceByOriginalSessionUserAndEventWithoutInventingReviewerOwner() {
        CommittedSessionEvent original = append(fullCommand(), 1);
        assertEquals(Optional.of(original), port.findUserSource(OWNER.sessionRef(), OWNER.requireUser(), FULL_EVENT_ID));
        assertEquals(OWNER, port.findUserSource(OWNER.sessionRef(), OWNER.requireUser(), FULL_EVENT_ID).orElseThrow().owner());
        assertTrue(port.findUserSource(OWNER.sessionRef(), new EndUserId(999), FULL_EVENT_ID).isEmpty());
        assertTrue(port.findUserSource(new SessionRef(TENANT, "other-source-session"), OWNER.requireUser(), FULL_EVENT_ID).isEmpty());
        assertTrue(port.findUserSource(new SessionRef(new TenantId("other-tenant"), OWNER.sessionRef().sessionId()),
                OWNER.requireUser(), FULL_EVENT_ID).isEmpty());
        assertTrue(port.findUserSource(OWNER.sessionRef(), OWNER.requireUser(), new EventId("missing-source-event")).isEmpty());
        SessionOwner forgedReviewer = SessionOwner.user(OWNER.sessionRef(), new AgentId("reviewer-agent"), OWNER.requireUser());
        assertTrue(port.findByEvent(forgedReviewer, FULL_EVENT_ID).isEmpty(), "普通完整owner读取不得随USER核验接口放宽");
    }

    @Test
    protected final void filteredPagesSkipUnrelatedEventsWithoutLosingCursorOrOwner() {
        var expected = new ArrayList<CommittedSessionEvent>();
        for (int index = 0; index < 7; index++) {
            var original = command("filtered-" + index, OCCURRED_AT.plusSeconds(index));
            var draft = original.event();
            var changed = withEvent(original, new SessionEventDraft(draft.eventId(),
                    index % 3 == 0 ? "selected" : "unrelated", draft.eventVersion(), draft.eventFingerprint(),
                    draft.trace(), draft.payload(), draft.occurredAt()));
            var event = append(changed, index + 1);
            if (index % 3 == 0) expected.add(event);
        }
        assertEquals(expected.subList(0, 2), port.readAfter(new ReadSessionLog(OWNER, 0, 2), Set.of("selected")));
        assertEquals(expected.subList(2, 3), port.readAfter(new ReadSessionLog(OWNER, 4, 2), Set.of("selected")));
        var other = SessionOwner.user(OWNER.sessionRef(), OWNER.target().requireAgent(), new EndUserId(999));
        assertTrue(port.readAfter(new ReadSessionLog(other, 0, 2), Set.of("selected")).isEmpty());
        var ids = new HashSet<EventId>(expected.stream().map(CommittedSessionEvent::eventId).toList());
        ids.add(new EventId("missing"));
        assertEquals(Set.copyOf(expected), Set.copyOf(port.findByEvents(OWNER, ids).values()));
        assertTrue(port.findByEvents(other, ids).isEmpty());
    }

    @Test
    protected final void runOnlyPagesAndFirstInputSourceKeepOriginalSequenceAndFullOwner() {
        var run = fullCommand().target().runRef().orElseThrow();
        var input = fullCommand().target().inputId();
        var otherRun = new RunRef(TENANT, "run-other");
        var otherInput = new InputId("input-other");
        var first = append(command("source-first", OCCURRED_AT), 1);
        var unrelated = append(withInputRun(command("other-run", OCCURRED_AT), input, otherRun), 2);
        var later = append(command("source-later", OCCURRED_AT.minusSeconds(10)), 3);
        var nextInput = append(withInputRun(command("other-input", OCCURRED_AT), otherInput, run), 4);
        assertEquals(List.of(first, later), port.readAfter(new ReadSessionLog(OWNER, 0, 2), run));
        assertEquals(List.of(later, nextInput), port.readAfter(new ReadSessionLog(OWNER, 1, 2), run));
        assertEquals(Optional.of(first), port.findFirst(OWNER, input, run));
        assertEquals(Optional.of(unrelated), port.findFirst(OWNER, input, otherRun));
        assertEquals(Optional.of(nextInput), port.findFirst(OWNER, otherInput, run));
        assertTrue(port.findFirst(OWNER, new InputId("missing"), run).isEmpty());
        var wrongUser = SessionOwner.user(OWNER.sessionRef(), OWNER.target(), new EndUserId(999));
        assertTrue(port.readAfter(new ReadSessionLog(wrongUser, 0, 2), run).isEmpty());
        assertTrue(port.findFirst(wrongUser, input, run).isEmpty());
        var wrongTenantRun = new RunRef(new TenantId("other-tenant"), run.runId());
        assertThrows(IllegalArgumentException.class, () -> port.readAfter(new ReadSessionLog(OWNER, 0, 2), wrongTenantRun));
        assertThrows(IllegalArgumentException.class, () -> port.findFirst(OWNER, input, wrongTenantRun));
    }

    @Test
    protected final void exactReplayReturnsOriginalEntryWithoutConsumingSequence() {
        AppendSessionEvent command = fullCommand();
        CommittedSessionEvent original = append(command, 1);

        AppendResult.Replayed replayed = assertInstanceOf(
                AppendResult.Replayed.class, port.append(command));
        assertEquals(original, replayed.event());

        CommittedSessionEvent next = append(command("after-replay", OCCURRED_AT.plusSeconds(1)), 2);
        assertEquals(List.of(original, next), port.readAfter(new ReadSessionLog(OWNER, 0, 10)));
    }

    @Test
    protected final void boundedBatchPreservesEveryEnvelopeAndExactReplayWithoutAllocatingMoreSequence() {
        var commands = IntStream.range(0, 3).mapToObj(index -> command("batch-" + index,
                OCCURRED_AT.plusNanos(index))).toList();
        var results = port.appendBatch(commands);
        assertEquals(commands.size(), results.size());
        var committed = IntStream.range(0, commands.size()).mapToObj(index -> {
            var result = assertInstanceOf(AppendResult.Appended.class, results.get(index));
            assertEquals(committed(commands.get(index), index + 1L), result.event());
            return result.event();
        }).toList();
        var replay = port.appendBatch(commands);
        assertEquals(committed, replay.stream().map(result -> assertInstanceOf(AppendResult.Replayed.class, result).event()).toList());
        assertEquals(committed, port.readAfter(new ReadSessionLog(OWNER, 0, 10)));
        assertEquals(4, append(command("after-batch", OCCURRED_AT.plusSeconds(1)), 4).sessionSequence());
    }

    @Test
    protected final void batchCommitsOnlyTheAcceptedPrefixAtTheFirstConflict() {
        var original = fullCommand();
        append(original, 1);
        var draft = original.event();
        var conflict = withEvent(original, new SessionEventDraft(draft.eventId(), draft.eventType(), draft.eventVersion(),
                "different-fingerprint", draft.trace(), draft.payload(), draft.occurredAt()));
        var first = command("batch-prefix", OCCURRED_AT.plusSeconds(1));
        var tail = command("batch-tail", OCCURRED_AT.plusSeconds(2));
        var result = port.appendBatch(List.of(first, conflict, tail));
        assertEquals(2, result.size());
        assertEquals(committed(first, 2), assertInstanceOf(AppendResult.Appended.class, result.getFirst()).event());
        assertEquals(assertInstanceOf(AppendResult.Conflict.class, port.append(conflict)), result.getLast());
        assertTrue(port.findByEvent(OWNER, tail.event().eventId()).isEmpty());
        assertEquals(3, append(command("after-prefix-conflict", OCCURRED_AT.plusSeconds(3)), 3).sessionSequence());
    }

    @Test
    protected final void batchRejectsUnboundedOrMixedOwnerRequestsBeforeWriting() {
        assertThrows(IllegalArgumentException.class, () -> port.appendBatch(List.of()));
        assertThrows(IllegalArgumentException.class, () -> port.appendBatch(IntStream.range(0, SessionLogPort.MAX_APPEND_BATCH + 1)
                .mapToObj(index -> command("oversized-" + index, OCCURRED_AT)).toList()));
        var otherOwner = SessionOwner.user(new SessionRef(TENANT, "another-session"), OWNER.target(), OWNER.requireUser());
        assertThrows(IllegalArgumentException.class, () -> port.appendBatch(List.of(fullCommand(), withOwner(fullCommand(), otherOwner))));
        assertTrue(port.readAfter(new ReadSessionLog(OWNER, 0, 10)).isEmpty());
    }

    @Test
    protected final void anySameEventIdentityDriftConflictsWithoutConsumingSequence() {
        AppendSessionEvent original = fullCommand();
        CommittedSessionEvent first = append(original, 1);

        for (Drift drift : driftedCommands(original)) {
            AppendResult.Conflict conflict = assertInstanceOf(
                    AppendResult.Conflict.class,
                    port.append(drift.command()),
                    "same eventId drift must conflict: " + drift.field());
            assertNotNull(conflict.conflict(), "conflict fact must be present: " + drift.field());
        }

        CommittedSessionEvent next = append(command("after-conflicts", OCCURRED_AT.plusSeconds(2)), 2);
        assertEquals(List.of(first, next), port.readAfter(new ReadSessionLog(OWNER, 0, 10)));
    }

    @Test
    protected final void establishedAgentSessionRejectsUserDriftWithoutConsumingSequence() {
        CommittedSessionEvent first = append(fullCommand(), 1);
        SessionOwner userDrift = SessionOwner.user(
                OWNER.sessionRef(), OWNER.target().requireAgent(), new EndUserId(202));

        assertInstanceOf(AppendResult.Conflict.class,
                port.append(withOwner(command("user-drift", OCCURRED_AT.plusSeconds(4)), userDrift)));

        CommittedSessionEvent next = append(command("after-owner-drift", OCCURRED_AT.plusSeconds(5)), 2);
        assertEquals(List.of(first, next), port.readAfter(new ReadSessionLog(OWNER, 0, 10)));
    }

    @Test
    protected final void differentAgentsOwnIndependentSameNamedSessions() {
        CommittedSessionEvent first = append(fullCommand(), 1);
        SessionOwner otherAgent = SessionOwner.user(
                OWNER.sessionRef(), new AgentId("agent-other"), OWNER.requireUser());
        CommittedSessionEvent other = append(withOwner(
                command("other-agent", OCCURRED_AT.plusSeconds(3)), otherAgent), 1);

        assertEquals(List.of(first), port.readAfter(new ReadSessionLog(OWNER, 0, 10)));
        assertEquals(List.of(other), port.readAfter(new ReadSessionLog(otherAgent, 0, 10)));
        assertEquals(Optional.of(other), port.findByEvent(otherAgent, other.eventId()));
    }

    @Test
    protected final void mismatchedOwnerQueriesNeverRevealEvents() {
        CommittedSessionEvent appended = append(fullCommand(), 1);
        SessionOwner agentDrift = SessionOwner.user(
                OWNER.sessionRef(), new AgentId("agent-other"), OWNER.requireUser());
        SessionOwner userDrift = SessionOwner.user(
                OWNER.sessionRef(), OWNER.target().requireAgent(), new EndUserId(202));
        SessionOwner otherSession = SessionOwner.user(
                new SessionRef(TENANT, "session-other"), OWNER.target().requireAgent(), OWNER.requireUser());
        SessionOwner otherTenant = SessionOwner.user(
                new SessionRef(new TenantId("tenant-other"), OWNER.sessionRef().sessionId()),
                OWNER.target().requireAgent(), OWNER.requireUser());

        for (SessionOwner mismatched : List.of(agentDrift, userDrift, otherSession, otherTenant)) {
            assertTrue(port.findByEvent(mismatched, FULL_EVENT_ID).isEmpty());
            assertTrue(port.readAfter(new ReadSessionLog(mismatched, 0, 10)).isEmpty());
        }
        assertEquals(Optional.of(appended), port.findByEvent(OWNER, FULL_EVENT_ID));
    }

    @Test
    protected final void readAfterUsesStrictAscendingSequenceCursorAndLimit() {
        CommittedSessionEvent first = append(command("read-first", OCCURRED_AT.plusSeconds(30)), 1);
        CommittedSessionEvent second = append(command("read-second", OCCURRED_AT.plusSeconds(10)), 2);
        CommittedSessionEvent third = append(command("read-third", OCCURRED_AT.plusSeconds(20)), 3);

        assertEquals(List.of(first, second), port.readAfter(new ReadSessionLog(OWNER, 0, 2)));
        assertEquals(List.of(second, third), port.readAfter(new ReadSessionLog(OWNER, 1, 10)));
        assertEquals(List.of(third), port.readAfter(new ReadSessionLog(OWNER, 2, 1)));
        assertTrue(port.readAfter(new ReadSessionLog(OWNER, 3, 10)).isEmpty());
        assertEquals(Optional.of(second), port.findByEvent(OWNER, second.eventId()));
        assertTrue(port.findByEvent(OWNER, new EventId("event-missing")).isEmpty());
    }

    @Test
    protected final void concurrentDistinctEventsReceiveUniqueContinuousSequence() throws Exception {
        int count = 12;
        List<AppendSessionEvent> commands = IntStream.range(0, count)
                .mapToObj(index -> command("parallel-" + index, OCCURRED_AT.plusNanos(index * 1_000L)))
                .toList();

        List<AppendResult> results = appendConcurrently(commands);

        List<Long> sequences = results.stream()
                .map(result -> assertInstanceOf(AppendResult.Appended.class, result).event().sessionSequence())
                .sorted()
                .toList();
        assertEquals(LongStream.rangeClosed(1, count).boxed().toList(), sequences);
        List<CommittedSessionEvent> stored = port.readAfter(new ReadSessionLog(OWNER, 0, count + 1));
        assertEquals(LongStream.rangeClosed(1, count).boxed().toList(),
                stored.stream().map(CommittedSessionEvent::sessionSequence).toList());
        assertEquals(count, new HashSet<>(stored.stream().map(CommittedSessionEvent::eventId).toList()).size());
    }

    @Test
    protected final void concurrentExactReplayAppendsOnceAndReplaysTheOriginal() throws Exception {
        int count = 12;
        AppendSessionEvent command = fullCommand();
        List<AppendResult> results = appendConcurrently(IntStream.range(0, count)
                .mapToObj(ignored -> command)
                .toList());

        assertEquals(1, results.stream().filter(AppendResult.Appended.class::isInstance).count());
        assertEquals(count - 1L, results.stream().filter(AppendResult.Replayed.class::isInstance).count());
        Set<CommittedSessionEvent> returnedEvents = new HashSet<>();
        for (AppendResult result : results) {
            if (result instanceof AppendResult.Appended appended) {
                returnedEvents.add(appended.event());
            } else if (result instanceof AppendResult.Replayed replayed) {
                returnedEvents.add(replayed.event());
            }
        }
        assertEquals(Set.of(committed(command, 1)), returnedEvents);
        assertEquals(List.of(committed(command, 1)), port.readAfter(new ReadSessionLog(OWNER, 0, 10)));
        append(command("after-parallel-replay", OCCURRED_AT.plusSeconds(6)), 2);
    }

    private CommittedSessionEvent append(AppendSessionEvent command, long expectedSequence) {
        AppendResult.Appended appended = assertInstanceOf(
                AppendResult.Appended.class, port.append(command));
        assertEquals(expectedSequence, appended.event().sessionSequence());
        return appended.event();
    }

    private List<AppendResult> appendConcurrently(List<AppendSessionEvent> commands) throws Exception {
        int count = commands.size();
        ExecutorService executor = ContractTestExecutors.fixedPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<AppendResult>> futures = new ArrayList<>(count);
            for (AppendSessionEvent command : commands) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "concurrent append start timed out");
                    return port.append(command);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "concurrent append workers did not become ready");
            start.countDown();
            List<AppendResult> results = new ArrayList<>(count);
            for (Future<AppendResult> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "concurrent append executor did not terminate");
        }
    }

    private static AppendSessionEvent fullCommand() {
        SessionProjectionTarget target = new SessionProjectionTarget(
                OWNER,
                new InputId("input-current"),
                Optional.of(new RunRef(TENANT, "run-current")),
                Optional.of(new ExecutionRef(TENANT, "execution-current")),
                Optional.of(new ActionRef(TENANT, "action-current")),
                Optional.of(new DelegationRef(TENANT, "delegation-current")));
        TraceEnvelope trace = new TraceEnvelope(
                "trace-current",
                "span-current",
                new InputId("input-root"),
                Optional.of(new EventId("event-cause")));
        SessionEventDraft event = new SessionEventDraft(
                FULL_EVENT_ID,
                "session.contract-test",
                "7",
                "fingerprint-full",
                trace,
                new TypedPayload(
                        "skillforge.runtime.contract-test.v1",
                        "{\"emoji\":\"🧭\",\"message\":\"你好，世界\",\"nested\":{\"ok\":true}}"),
                OCCURRED_AT);
        return new AppendSessionEvent(target, event);
    }

    private static AppendSessionEvent command(String suffix, Instant occurredAt) {
        AppendSessionEvent full = fullCommand();
        SessionEventDraft event = full.event();
        return new AppendSessionEvent(
                full.target(),
                new SessionEventDraft(
                        new EventId("event-" + suffix),
                        event.eventType(),
                        event.eventVersion(),
                        "fingerprint-" + suffix,
                        event.trace(),
                        new TypedPayload(event.payload().schemaId(), "{\"event\":\"" + suffix + "\"}"),
                        occurredAt));
    }

    private static AppendSessionEvent withOwner(AppendSessionEvent command, SessionOwner owner) {
        SessionProjectionTarget target = command.target();
        return new AppendSessionEvent(
                new SessionProjectionTarget(
                        owner,
                        target.inputId(),
                        target.runRef(),
                        target.executionRef(),
                        target.actionRef(),
                        target.delegationRef()),
                command.event());
    }

    private static AppendSessionEvent withInputRun(AppendSessionEvent command, InputId input, RunRef run) {
        var target = command.target();
        return new AppendSessionEvent(new SessionProjectionTarget(target.owner(), input, Optional.of(run),
                target.executionRef(), target.actionRef(), target.delegationRef()), command.event());
    }

    private static CommittedSessionEvent committed(AppendSessionEvent command, long sequence) {
        SessionProjectionTarget target = command.target();
        SessionEventDraft event = command.event();
        return new CommittedSessionEvent(
                target.owner(),
                target.inputId(),
                target.runRef(),
                target.executionRef(),
                target.actionRef(),
                target.delegationRef(),
                event.eventId(),
                sequence,
                event.eventType(),
                event.eventVersion(),
                event.trace(),
                event.payload(),
                event.occurredAt());
    }

    private static List<Drift> driftedCommands(AppendSessionEvent original) {
        SessionProjectionTarget target = original.target();
        SessionEventDraft event = original.event();
        List<Drift> drifts = new ArrayList<>();
        drifts.add(new Drift("target.owner.endUserId", withTarget(original, new SessionProjectionTarget(
                SessionOwner.user(target.owner().sessionRef(), target.owner().target().requireAgent(), new EndUserId(202)),
                target.inputId(), target.runRef(), target.executionRef(), target.actionRef(),
                target.delegationRef()))));
        drifts.add(new Drift("target.inputId", withTarget(original, new SessionProjectionTarget(
                target.owner(), new InputId("input-other"), target.runRef(), target.executionRef(),
                target.actionRef(), target.delegationRef()))));
        drifts.add(new Drift("target.runRef", withTarget(original, new SessionProjectionTarget(
                target.owner(), target.inputId(), Optional.of(new RunRef(TENANT, "run-other")),
                target.executionRef(), target.actionRef(), target.delegationRef()))));
        drifts.add(new Drift("target.executionRef", withTarget(original, new SessionProjectionTarget(
                target.owner(), target.inputId(), target.runRef(),
                Optional.of(new ExecutionRef(TENANT, "execution-other")),
                target.actionRef(), target.delegationRef()))));
        drifts.add(new Drift("target.actionRef", withTarget(original, new SessionProjectionTarget(
                target.owner(), target.inputId(), target.runRef(), target.executionRef(),
                Optional.of(new ActionRef(TENANT, "action-other")), target.delegationRef()))));
        drifts.add(new Drift("target.delegationRef", withTarget(original, new SessionProjectionTarget(
                target.owner(), target.inputId(), target.runRef(), target.executionRef(), target.actionRef(),
                Optional.of(new DelegationRef(TENANT, "delegation-other"))))));
        drifts.add(new Drift("eventFingerprint", withEvent(original, new SessionEventDraft(
                event.eventId(), event.eventType(), event.eventVersion(), "fingerprint-other",
                event.trace(), event.payload(), event.occurredAt()))));
        drifts.add(new Drift("eventType", withEvent(original, new SessionEventDraft(
                event.eventId(), "session.contract-test.other", event.eventVersion(), event.eventFingerprint(),
                event.trace(), event.payload(), event.occurredAt()))));
        drifts.add(new Drift("eventVersion", withEvent(original, new SessionEventDraft(
                event.eventId(), event.eventType(), "8", event.eventFingerprint(),
                event.trace(), event.payload(), event.occurredAt()))));
        drifts.add(new Drift("trace.traceId", withEvent(original, withTrace(event, new TraceEnvelope(
                "trace-other", event.trace().spanId(), event.trace().rootInputId(),
                event.trace().causedByEventId())))));
        drifts.add(new Drift("trace.spanId", withEvent(original, withTrace(event, new TraceEnvelope(
                event.trace().traceId(), "span-other", event.trace().rootInputId(),
                event.trace().causedByEventId())))));
        drifts.add(new Drift("trace.rootInputId", withEvent(original, withTrace(event, new TraceEnvelope(
                event.trace().traceId(), event.trace().spanId(), new InputId("input-root-other"),
                event.trace().causedByEventId())))));
        drifts.add(new Drift("trace.causedByEventId", withEvent(original, withTrace(event, new TraceEnvelope(
                event.trace().traceId(), event.trace().spanId(), event.trace().rootInputId(),
                Optional.of(new EventId("event-cause-other")))))));
        drifts.add(new Drift("payload.schemaId", withEvent(original, withPayload(event, new TypedPayload(
                "skillforge.runtime.contract-test.v2", event.payload().canonicalJson())))));
        drifts.add(new Drift("payload.canonicalJson", withEvent(original, withPayload(event, new TypedPayload(
                event.payload().schemaId(), "{\"emoji\":\"🛰️\",\"message\":\"再见\"}")))));
        drifts.add(new Drift("occurredAt", withEvent(original, new SessionEventDraft(
                event.eventId(), event.eventType(), event.eventVersion(), event.eventFingerprint(),
                event.trace(), event.payload(), event.occurredAt().plusNanos(1_000L)))));
        return List.copyOf(drifts);
    }

    private static AppendSessionEvent withTarget(
            AppendSessionEvent command, SessionProjectionTarget target) {
        return new AppendSessionEvent(target, command.event());
    }

    private static AppendSessionEvent withEvent(
            AppendSessionEvent command, SessionEventDraft event) {
        return new AppendSessionEvent(command.target(), event);
    }

    private static SessionEventDraft withTrace(SessionEventDraft event, TraceEnvelope trace) {
        return new SessionEventDraft(
                event.eventId(), event.eventType(), event.eventVersion(), event.eventFingerprint(),
                trace, event.payload(), event.occurredAt());
    }

    private static SessionEventDraft withPayload(SessionEventDraft event, TypedPayload payload) {
        return new SessionEventDraft(
                event.eventId(), event.eventType(), event.eventVersion(), event.eventFingerprint(),
                event.trace(), payload, event.occurredAt());
    }

    private record Drift(String field, AppendSessionEvent command) {}
}
