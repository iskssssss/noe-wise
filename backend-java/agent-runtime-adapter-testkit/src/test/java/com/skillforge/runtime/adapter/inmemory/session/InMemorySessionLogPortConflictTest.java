package com.skillforge.runtime.adapter.inmemory.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.PortConflict;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort.AppendResult;
import com.skillforge.runtime.api.port.SessionLogPort.AppendSessionEvent;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class InMemorySessionLogPortConflictTest {

    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final SessionOwner OWNER = SessionOwner.user(
            new SessionRef(TENANT, "session-a"), new AgentId("agent-a"), new EndUserId(101));

    @Test
    void eventConflictUsesStableHashedAuditReferenceAndCanonicalResource() {
        InMemorySessionLogPort port = new InMemorySessionLogPort();
        port.append(command(OWNER, "event-a", "fingerprint-a", "{\"secret\":\"stored-secret\"}"));
        AppendSessionEvent drift = command(
                OWNER, "event-a", "fingerprint-a", "{\"secret\":\"requested-secret\"}");

        PortConflict first = conflict(port.append(drift));
        PortConflict replay = conflict(port.append(drift));

        assertEquals("session_event", first.resourceKind());
        assertEquals("tenant-a/agent-a/session-a/event-a", first.resourceId());
        assertEquals(first, replay);
        assertTrue(first.auditRef().value().matches("[0-9a-f]{64}"));
        assertFalse(first.auditRef().value().contains("stored-secret"));
        assertFalse(first.auditRef().value().contains("requested-secret"));
    }

    @Test
    void ownerConflictWinsBeforeEventLookupAndUsesStableHashedAuditReference() {
        InMemorySessionLogPort port = new InMemorySessionLogPort();
        AppendSessionEvent initial = command(
                OWNER, "event-a", "fingerprint-a", "{\"message\":\"same\"}");
        port.append(initial);
        SessionOwner driftedOwner = SessionOwner.user(
                OWNER.sessionRef(), OWNER.target().requireAgent(), new EndUserId(202));
        AppendSessionEvent ownerDrift = new AppendSessionEvent(
                new SessionProjectionTarget(
                        driftedOwner,
                        initial.target().inputId(),
                        initial.target().runRef(),
                        initial.target().executionRef(),
                        initial.target().actionRef(),
                        initial.target().delegationRef()),
                initial.event());

        PortConflict first = conflict(port.append(ownerDrift));
        PortConflict replay = conflict(port.append(ownerDrift));

        assertEquals("session_owner", first.resourceKind());
        assertEquals("tenant-a/agent-a/session-a", first.resourceId());
        assertEquals(first, replay);
        assertTrue(first.auditRef().value().matches("[0-9a-f]{64}"));
    }

    private static PortConflict conflict(AppendResult result) {
        return assertInstanceOf(AppendResult.Conflict.class, result).conflict();
    }

    private static AppendSessionEvent command(
            SessionOwner owner, String eventId, String fingerprint, String payload) {
        SessionProjectionTarget target = new SessionProjectionTarget(
                owner,
                new InputId("input-a"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        SessionEventDraft draft = new SessionEventDraft(
                new EventId(eventId),
                "session.test",
                "1",
                fingerprint,
                new TraceEnvelope(
                        "trace-a", "span-a", new InputId("input-root"), Optional.empty()),
                new TypedPayload("skillforge.runtime.test.v1", payload),
                Instant.parse("2026-09-03T10:15:30Z"));
        return new AppendSessionEvent(target, draft);
    }
}
