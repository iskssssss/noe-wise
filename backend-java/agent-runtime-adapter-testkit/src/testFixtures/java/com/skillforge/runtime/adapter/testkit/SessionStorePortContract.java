package com.skillforge.runtime.adapter.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import com.skillforge.runtime.api.port.SessionStorePort;
import com.skillforge.runtime.api.port.SessionStorePort.ChangeSessionStatus;
import com.skillforge.runtime.api.port.SessionStorePort.CreateSession;
import com.skillforge.runtime.api.port.SessionStorePort.SessionCasResult;
import com.skillforge.runtime.api.port.SessionStorePort.SessionCreateResult;
import com.skillforge.runtime.api.port.SessionStorePort.SessionLookup;
import com.skillforge.runtime.api.port.SessionStorePort.SessionRecord;
import com.skillforge.runtime.api.port.SessionStorePort.SessionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 所有 {@link SessionStorePort} 实现共享的身份、生命周期与实时序列契约。 */
public abstract class SessionStorePortContract {

    protected static final Instant INITIAL_TIME =
            Instant.parse("2026-09-09T08:09:10.123456789Z");

    private static final TenantId TENANT = new TenantId("tenant-session-store");
    private static final SessionOwner OWNER = owner(
            TENANT.value(), "session-main", "agent-main", 101);
    private static final Instant REQUESTED_AT =
            Instant.parse("2024-01-02T03:04:05.987654321Z");

    private SessionStoreFixture fixture;
    private SessionStorePort store;
    private SessionLogPort log;

    /** 每项测试返回全新空存储，Store 与 Log 必须共享 owner 和 sequence 状态。 */
    protected abstract SessionStoreFixture createFixture(Instant initialTime);

    /** 同时暴露两个 Port 与权威存储时间推进能力。 */
    protected interface SessionStoreFixture {
        SessionStorePort store();
        SessionLogPort log();
        Instant storageTime();
        void advance(Duration duration);
    }

    @BeforeEach
    protected final void createEmptyStore() {
        fixture = Objects.requireNonNull(createFixture(INITIAL_TIME));
        store = Objects.requireNonNull(fixture.store());
        log = Objects.requireNonNull(fixture.log());
        assertTrue(store.find(new SessionLookup(OWNER)).isEmpty());
    }

    @Test
    protected final void createsAndFindsCompleteSessionUsingStorageClock() {
        CreateSession command = createCommand(OWNER);
        Instant before = fixture.storageTime();

        SessionRecord created = assertInstanceOf(
                SessionCreateResult.Created.class, store.create(command)).session();

        Instant after = fixture.storageTime();
        assertEquals(OWNER, created.owner());
        assertEquals(command.channel(), created.channel());
        assertEquals(command.scopeKey(), created.scopeKey());
        assertEquals(command.personaVersion(), created.personaVersion());
        assertEquals(SessionStatus.CREATED, created.status());
        assertEquals(1, created.sessionRevision());
        assertEquals(0, created.lastSequence());
        assertEquals(created.createdAt(), created.updatedAt());
        assertFalse(created.createdAt().isBefore(before));
        assertFalse(created.createdAt().isAfter(after));
        assertNotEquals(command.requestedAt(), created.createdAt());
        assertEquals(Optional.of(created), store.find(new SessionLookup(OWNER)));
    }

    @Test
    protected final void exactCreateReplaysCurrentProjectionAndFieldDriftConflictsStably() {
        CreateSession command = createCommand(OWNER);
        SessionRecord created = created(store.create(command));
        assertEquals(created, replayed(store.create(command)));

        for (CreateSession drift : createDrifts(command)) {
            SessionCreateResult.Conflict first = assertInstanceOf(
                    SessionCreateResult.Conflict.class, store.create(drift));
            SessionCreateResult.Conflict replay = assertInstanceOf(
                    SessionCreateResult.Conflict.class, store.create(drift));
            assertEquals(1, first.currentRevision());
            assertEquals(first, replay);
            assertNotNull(first.auditRef());
        }
        assertEquals(Optional.of(created), store.find(new SessionLookup(OWNER)));
    }

    @Test
    protected final void tenantAndAgentScopeResourcesWhileCompleteOwnerGuardsAccess() {
        SessionRecord original = created(store.create(createCommand(OWNER)));
        SessionOwner otherTenant = owner("tenant-other", "session-main", "agent-main", 101);
        SessionOwner otherAgent = owner(TENANT.value(), "session-main", "agent-other", 101);
        assertInstanceOf(SessionCreateResult.Created.class,
                store.create(createCommand(otherTenant)));
        assertInstanceOf(SessionCreateResult.Created.class,
                store.create(createCommand(otherAgent)));

        SessionOwner wrongUser = owner(TENANT.value(), "session-main", "agent-main", 202);
        SessionCreateResult.Conflict ownerConflict = assertInstanceOf(
                SessionCreateResult.Conflict.class,
                store.create(createCommand(wrongUser)));
        assertEquals(1, ownerConflict.currentRevision());

        List<SessionOwner> missing = List.of(
                wrongUser,
                owner("tenant-missing", "session-main", "agent-main", 101),
                owner(TENANT.value(), "session-missing", "agent-main", 101),
                owner(TENANT.value(), "session-main", "agent-missing", 101));
        for (SessionOwner owner : missing) {
            assertTrue(store.find(new SessionLookup(owner)).isEmpty());
            assertInstanceOf(SessionCasResult.NotFound.class,
                    store.compareAndSet(activate(owner, 1, "missing")));
        }
        assertEquals(Optional.of(original), store.find(new SessionLookup(OWNER)));
    }

    @Test
    protected final void ownerRootOnlyConflictUsesInitialResourceRevision() {
        append(OWNER, "root-only-first", 1);
        append(OWNER, "root-only-second", 2);
        SessionOwner wrongUser = owner(
                TENANT.value(), "session-main", "agent-main", 202);
        CreateSession wrongCreate = createCommand(wrongUser);

        SessionCreateResult.Conflict first = assertInstanceOf(
                SessionCreateResult.Conflict.class, store.create(wrongCreate));
        SessionCreateResult.Conflict replay = assertInstanceOf(
                SessionCreateResult.Conflict.class, store.create(wrongCreate));
        assertEquals(1, first.currentRevision());
        assertEquals(first, replay);
        assertTrue(store.find(new SessionLookup(OWNER)).isEmpty());
        assertTrue(store.find(new SessionLookup(wrongUser)).isEmpty());

        SessionRecord created = created(store.create(createCommand(OWNER)));
        assertEquals(2, created.lastSequence());
        applied(store.compareAndSet(activate(OWNER, 1, "root-only-activate")));
        applied(store.compareAndSet(close(OWNER, 2, "root-only-close")));
        SessionCreateResult.Conflict afterLifecycle = assertInstanceOf(
                SessionCreateResult.Conflict.class, store.create(wrongCreate));
        assertEquals(3, afterLifecycle.currentRevision());
    }

    @Test
    protected final void appliesCreatedToActiveAndActiveToClosedExactlyOnce() {
        SessionRecord created = created(store.create(createCommand(OWNER)));
        fixture.advance(Duration.ofSeconds(1));
        SessionRecord active = applied(store.compareAndSet(activate(OWNER, 1, "activate")));
        assertLifecycle(active, SessionStatus.ACTIVE, 2, created.createdAt());
        assertTrue(active.updatedAt().isAfter(created.updatedAt()));

        fixture.advance(Duration.ofSeconds(1));
        ChangeSessionStatus close = close(OWNER, 2, "close");
        SessionRecord closed = applied(store.compareAndSet(close));
        assertLifecycle(closed, SessionStatus.CLOSED, 3, created.createdAt());
        assertTrue(closed.updatedAt().isAfter(active.updatedAt()));

        ChangeSessionStatus staleClose = close(OWNER, 2, "close-after-terminal");
        SessionCasResult.Conflict conflict = assertInstanceOf(
                SessionCasResult.Conflict.class, store.compareAndSet(staleClose));
        assertEquals(closed, conflict.current());
        assertEquals(Optional.of(closed), store.find(new SessionLookup(OWNER)));
    }

    @Test
    protected final void casReplayDistinguishesFingerprintDriftAndAdvancedJournal() {
        created(store.create(createCommand(OWNER)));
        ChangeSessionStatus activation = activate(OWNER, 1, "activation-replay");
        SessionRecord active = applied(store.compareAndSet(activation));
        assertEquals(active, casReplayed(store.compareAndSet(activation)));

        ChangeSessionStatus traceDrift = new ChangeSessionStatus(
                activation.owner(), activation.expectedStatus(), activation.targetStatus(),
                activation.expectedRevision(), activation.causationInputId(),
                trace("trace-drift"), activation.requestedAt());
        ChangeSessionStatus timeDrift = new ChangeSessionStatus(
                activation.owner(), activation.expectedStatus(), activation.targetStatus(),
                activation.expectedRevision(), activation.causationInputId(),
                activation.trace(), activation.requestedAt().plusNanos(1));
        assertInstanceOf(SessionCasResult.Conflict.class,
                store.compareAndSet(traceDrift));
        assertInstanceOf(SessionCasResult.Conflict.class,
                store.compareAndSet(timeDrift));

        append(OWNER, "after-cas", 1);
        SessionRecord liveReplay = casReplayed(store.compareAndSet(activation));
        assertEquals(1, liveReplay.lastSequence());
        assertEquals(SessionStatus.ACTIVE, liveReplay.status());
        assertEquals(2, liveReplay.sessionRevision());

        SessionRecord closed = applied(store.compareAndSet(close(OWNER, 2, "close-after-replay")));
        SessionCasResult.Conflict oldJournal = assertInstanceOf(
                SessionCasResult.Conflict.class, store.compareAndSet(activation));
        assertEquals(closed, oldJournal.current());
    }

    @Test
    protected final void casReplayUsesStructuredCommandIdentityInsteadOfRecordText() {
        created(store.create(createCommand(OWNER)));
        TraceEnvelope firstTrace = new TraceEnvelope(
                "x, spanId=y", "z", new InputId("root-boundary"), Optional.empty());
        TraceEnvelope secondTrace = new TraceEnvelope(
                "x", "y, spanId=z", new InputId("root-boundary"), Optional.empty());
        ChangeSessionStatus first = new ChangeSessionStatus(
                OWNER, SessionStatus.CREATED, SessionStatus.ACTIVE, 1,
                new InputId("input-boundary"), firstTrace, REQUESTED_AT.plusSeconds(1));
        ChangeSessionStatus drift = new ChangeSessionStatus(
                OWNER, SessionStatus.CREATED, SessionStatus.ACTIVE, 1,
                new InputId("input-boundary"), secondTrace, REQUESTED_AT.plusSeconds(1));
        assertNotEquals(first, drift);

        SessionRecord active = applied(store.compareAndSet(first));
        SessionCasResult.Conflict conflict = assertInstanceOf(
                SessionCasResult.Conflict.class, store.compareAndSet(drift));

        assertEquals(active, conflict.current());
    }

    @Test
    protected final void staleStatusAndRevisionConflictWithoutMutation() {
        created(store.create(createCommand(OWNER)));
        SessionRecord active = applied(store.compareAndSet(activate(OWNER, 1, "activate-stale")));

        SessionCasResult.Conflict staleRevision = assertInstanceOf(
                SessionCasResult.Conflict.class,
                store.compareAndSet(close(OWNER, 1, "stale-revision")));
        SessionCasResult.Conflict staleStatus = assertInstanceOf(
                SessionCasResult.Conflict.class,
                store.compareAndSet(activate(OWNER, 2, "stale-status")));
        assertEquals(active, staleRevision.current());
        assertEquals(active, staleStatus.current());
        assertEquals(Optional.of(active), store.find(new SessionLookup(OWNER)));
    }

    @Test
    protected final void lastSequenceIsLiveSessionLogProjectionAndNotReplayState() {
        CreateSession create = createCommand(OWNER);
        created(store.create(create));
        ChangeSessionStatus activation = activate(OWNER, 1, "sequence-activation");
        applied(store.compareAndSet(activation));

        SessionLogPort.AppendSessionEvent first = logCommand(OWNER, "first");
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class, log.append(first));
        assertInstanceOf(SessionLogPort.AppendResult.Replayed.class, log.append(first));
        SessionEventDraft firstEvent = first.event();
        SessionLogPort.AppendSessionEvent conflict = new SessionLogPort.AppendSessionEvent(
                first.target(), new SessionEventDraft(
                        firstEvent.eventId(), firstEvent.eventType(), firstEvent.eventVersion(),
                        "fingerprint-conflict", firstEvent.trace(), firstEvent.payload(),
                        firstEvent.occurredAt()));
        assertInstanceOf(SessionLogPort.AppendResult.Conflict.class, log.append(conflict));
        append(OWNER, "second", 2);

        SessionRecord found = store.find(new SessionLookup(OWNER)).orElseThrow();
        assertEquals(2, found.lastSequence());
        assertEquals(2, replayed(store.create(create)).lastSequence());
        assertEquals(2, casReplayed(store.compareAndSet(activation)).lastSequence());
    }

    private void append(SessionOwner owner, String suffix, long expectedSequence) {
        CommittedSessionEvent event = assertInstanceOf(
                SessionLogPort.AppendResult.Appended.class,
                log.append(logCommand(owner, suffix))).event();
        assertEquals(expectedSequence, event.sessionSequence());
    }

    private static SessionRecord created(SessionCreateResult result) {
        return assertInstanceOf(SessionCreateResult.Created.class, result).session();
    }

    private static SessionRecord replayed(SessionCreateResult result) {
        return assertInstanceOf(SessionCreateResult.Replayed.class, result).session();
    }

    private static SessionRecord applied(SessionCasResult result) {
        return assertInstanceOf(SessionCasResult.Applied.class, result).session();
    }

    private static SessionRecord casReplayed(SessionCasResult result) {
        return assertInstanceOf(SessionCasResult.Replayed.class, result).session();
    }

    private static void assertLifecycle(
            SessionRecord record, SessionStatus status, long revision, Instant createdAt) {
        assertEquals(OWNER, record.owner());
        assertEquals("channel-main", record.channel());
        assertEquals("scope-main", record.scopeKey());
        assertEquals("persona-v1", record.personaVersion());
        assertEquals(status, record.status());
        assertEquals(revision, record.sessionRevision());
        assertEquals(createdAt, record.createdAt());
    }

    private static CreateSession createCommand(SessionOwner owner) {
        return new CreateSession(
                owner, "channel-main", "scope-main", "persona-v1",
                new InputId("input-create"), trace("create"), REQUESTED_AT);
    }

    private static ChangeSessionStatus activate(
            SessionOwner owner, long revision, String suffix) {
        return new ChangeSessionStatus(
                owner, SessionStatus.CREATED, SessionStatus.ACTIVE, revision,
                new InputId("input-" + suffix), trace(suffix),
                REQUESTED_AT.plusSeconds(1));
    }

    private static ChangeSessionStatus close(
            SessionOwner owner, long revision, String suffix) {
        return new ChangeSessionStatus(
                owner, SessionStatus.ACTIVE, SessionStatus.CLOSED, revision,
                new InputId("input-" + suffix), trace(suffix),
                REQUESTED_AT.plusSeconds(2));
    }

    private static TraceEnvelope trace(String suffix) {
        return new TraceEnvelope(
                "trace-" + suffix, "span-" + suffix,
                new InputId("input-root-" + suffix),
                Optional.of(new EventId("event-cause-" + suffix)));
    }

    private static SessionLogPort.AppendSessionEvent logCommand(
            SessionOwner owner, String suffix) {
        TraceEnvelope trace = trace("log-" + suffix);
        return new SessionLogPort.AppendSessionEvent(
                new SessionProjectionTarget(
                        owner, new InputId("input-log-" + suffix),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                new SessionEventDraft(
                        new EventId("event-log-" + suffix),
                        "session.store-contract", "1", "fingerprint-log-" + suffix,
                        trace, new TypedPayload(
                                "skillforge.runtime.session-store-contract.v1",
                                "{\"suffix\":\"" + suffix + "\"}"),
                        REQUESTED_AT.plusSeconds(10)));
    }

    private static List<CreateSession> createDrifts(CreateSession original) {
        List<CreateSession> drifts = new ArrayList<>();
        drifts.add(new CreateSession(
                SessionOwner.user(original.owner().sessionRef(), original.owner().target().requireAgent(),
                        new EndUserId(202)),
                original.channel(), original.scopeKey(), original.personaVersion(),
                original.causationInputId(), original.trace(), original.requestedAt()));
        drifts.add(copy(original, "channel-other", original.scopeKey(),
                original.personaVersion(), original.causationInputId(),
                original.trace(), original.requestedAt()));
        drifts.add(copy(original, original.channel(), "scope-other",
                original.personaVersion(), original.causationInputId(),
                original.trace(), original.requestedAt()));
        drifts.add(copy(original, original.channel(), original.scopeKey(),
                "persona-v2", original.causationInputId(),
                original.trace(), original.requestedAt()));
        drifts.add(copy(original, original.channel(), original.scopeKey(),
                original.personaVersion(), new InputId("input-create-other"),
                original.trace(), original.requestedAt()));
        drifts.add(copy(original, original.channel(), original.scopeKey(),
                original.personaVersion(), original.causationInputId(),
                trace("create-other"), original.requestedAt()));
        drifts.add(copy(original, original.channel(), original.scopeKey(),
                original.personaVersion(), original.causationInputId(),
                original.trace(), original.requestedAt().plusNanos(1)));
        return List.copyOf(drifts);
    }

    private static CreateSession copy(
            CreateSession original,
            String channel,
            String scopeKey,
            String personaVersion,
            InputId causationInputId,
            TraceEnvelope trace,
            Instant requestedAt) {
        return new CreateSession(
                original.owner(), channel, scopeKey, personaVersion,
                causationInputId, trace, requestedAt);
    }

    private static SessionOwner owner(
            String tenantId, String sessionId, String agentId, long endUserId) {
        return SessionOwner.user(
                new SessionRef(new TenantId(tenantId), sessionId),
                new AgentId(agentId), new EndUserId(endUserId));
    }
}
