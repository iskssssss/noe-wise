package com.skillforge.runtime.adapter.inmemory.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.adapter.testkit.SessionStorePortContract;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import com.skillforge.runtime.api.port.SessionStorePort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class InMemorySessionStorePortContractTest extends SessionStorePortContract {

    @Override
    protected SessionStoreFixture createFixture(Instant initialTime) {
        MutableClock clock = new MutableClock(initialTime);
        InMemorySessionOwnerState state = new InMemorySessionOwnerState();
        SessionStorePort store = new InMemorySessionStorePort(clock, state);
        SessionLogPort log = new InMemorySessionLogPort(state);
        return new SessionStoreFixture() {
            @Override
            public SessionStorePort store() {
                return store;
            }

            @Override
            public SessionLogPort log() {
                return log;
            }

            @Override
            public Instant storageTime() {
                return clock.instant();
            }

            @Override
            public void advance(Duration duration) {
                clock.advance(duration);
            }
        };
    }

    @Test
    void committedLogFreezesTheSameOwnerRootForLifecycleStore() {
        MutableClock clock = new MutableClock(INITIAL_TIME);
        InMemorySessionOwnerState state = new InMemorySessionOwnerState();
        SessionLogPort log = new InMemorySessionLogPort(state);
        InMemorySessionStorePort store = new InMemorySessionStorePort(clock, state);
        SessionOwner first = owner(201);
        SessionOwner drift = owner(202);

        var target = new SessionProjectionTarget(first, new InputId("owner-root-input"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var event = new SessionEventDraft(new EventId("owner-root-event"), "session.test", "1", "owner-root-fingerprint",
                trace("owner-root"), new TypedPayload("application/json", "{}"), INITIAL_TIME);
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class, log.append(new SessionLogPort.AppendSessionEvent(target, event)));
        assertEquals(Optional.empty(),
                store.find(new SessionStorePort.SessionLookup(first)));
        SessionStorePort.SessionCreateResult.Conflict conflict = assertInstanceOf(
                SessionStorePort.SessionCreateResult.Conflict.class,
                store.create(create(drift)));
        SessionStorePort.SessionCreateResult.Conflict replay = assertInstanceOf(
                SessionStorePort.SessionCreateResult.Conflict.class,
                store.create(create(drift)));
        assertEquals(1, conflict.currentRevision());
        assertEquals(conflict, replay);
        assertInstanceOf(SessionStorePort.SessionCreateResult.Created.class,
                store.create(create(first)));
    }

    @Test
    void explicitStateSharesSessionAndMutationReplayAcrossStoreInstances() {
        MutableClock clock = new MutableClock(INITIAL_TIME);
        InMemorySessionOwnerState state = new InMemorySessionOwnerState();
        SessionStorePort first = new InMemorySessionStorePort(clock, state);
        SessionStorePort second = new InMemorySessionStorePort(clock, state);
        SessionOwner owner = owner(301);
        SessionStorePort.CreateSession create = create(owner);

        assertInstanceOf(SessionStorePort.SessionCreateResult.Created.class,
                first.create(create));
        assertInstanceOf(SessionStorePort.SessionCreateResult.Replayed.class,
                second.create(create));

        SessionStorePort.ChangeSessionStatus activation = change(
                owner, SessionStorePort.SessionStatus.CREATED,
                SessionStorePort.SessionStatus.ACTIVE, 1, "shared-activation");
        assertInstanceOf(SessionStorePort.SessionCasResult.Applied.class,
                second.compareAndSet(activation));
        assertInstanceOf(SessionStorePort.SessionCasResult.Replayed.class,
                first.compareAndSet(activation));
    }

    @Test
    void clockRegressionDoesNotPublishLifecycleOrMutationJournal() {
        MutableClock clock = new MutableClock(INITIAL_TIME);
        InMemorySessionOwnerState state = new InMemorySessionOwnerState();
        SessionStorePort store = new InMemorySessionStorePort(clock, state);
        SessionOwner owner = owner(302);
        assertInstanceOf(SessionStorePort.SessionCreateResult.Created.class,
                store.create(create(owner)));

        clock.advance(Duration.ofSeconds(2));
        SessionStorePort.ChangeSessionStatus activation = change(
                owner, SessionStorePort.SessionStatus.CREATED,
                SessionStorePort.SessionStatus.ACTIVE, 1, "clock-activation");
        assertInstanceOf(SessionStorePort.SessionCasResult.Applied.class,
                store.compareAndSet(activation));

        SessionStorePort.ChangeSessionStatus close = change(
                owner, SessionStorePort.SessionStatus.ACTIVE,
                SessionStorePort.SessionStatus.CLOSED, 2, "clock-close");
        clock.set(INITIAL_TIME.minusNanos(1));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> store.compareAndSet(close));
        assertEquals("session_store_storage_failure", failure.getMessage());
        assertEquals(SessionStorePort.SessionStatus.ACTIVE,
                store.find(new SessionStorePort.SessionLookup(owner)).orElseThrow().status());

        clock.set(INITIAL_TIME.plusSeconds(3));
        assertInstanceOf(SessionStorePort.SessionCasResult.Applied.class,
                store.compareAndSet(close));
    }

    private static SessionStorePort.CreateSession create(SessionOwner owner) {
        return new SessionStorePort.CreateSession(
                owner, "channel", "scope", "persona",
                new InputId("input-create"), trace("create"), INITIAL_TIME.minusSeconds(2));
    }

    private static SessionStorePort.ChangeSessionStatus change(
            SessionOwner owner,
            SessionStorePort.SessionStatus expected,
            SessionStorePort.SessionStatus target,
            long revision,
            String suffix) {
        return new SessionStorePort.ChangeSessionStatus(
                owner, expected, target, revision,
                new InputId("input-" + suffix), trace(suffix),
                INITIAL_TIME.minusSeconds(1));
    }

    private static TraceEnvelope trace(String suffix) {
        return new TraceEnvelope(
                "trace-" + suffix, "span-" + suffix,
                new InputId("root-" + suffix), Optional.empty());
    }

    private static SessionOwner owner(long endUserId) {
        return SessionOwner.user(
                new SessionRef(new TenantId("tenant-owner-root"), "session-owner-root"),
                new AgentId("agent-owner-root"), new EndUserId(endUserId));
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        private void set(Instant value) {
            current = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
