package com.skillforge.runtime.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.MemoryQueryProfile;
import com.skillforge.runtime.api.MemoryRetrievalTrace;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.MemoryPort;
import com.skillforge.runtime.api.port.MemoryPort.MemoryCommand;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.MemoryPort.MemoryMutationResult;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryCall;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryOutcome;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryResult;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.PortConflict;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionLogPort.AppendResult;
import com.skillforge.runtime.api.port.SessionLogPort.AppendSessionEvent;
import com.skillforge.runtime.api.port.SessionLogPort.ReadSessionLog;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Exercises the Memory coordination boundary with hostile in-memory ports. */
class MemoryRuntimeTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final TenantId OTHER_TENANT = new TenantId("tenant-2");
    private static final EndUserId USER = new EndUserId(7);
    private static final EndUserId OTHER_USER = new EndUserId(8);
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final AgentId OTHER_AGENT = new AgentId("agent-2");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final SessionRef OTHER_SESSION = new SessionRef(TENANT, "session-2");
    private static final SessionRef OTHER_TENANT_SESSION =
            new SessionRef(OTHER_TENANT, "session-1");
    private static final SessionOwner OWNER = SessionOwner.user(SESSION, AGENT, USER);
    private static final MemoryTypeRef TYPE = new MemoryTypeRef("preference");
    private static final MemoryTypeRef OTHER_TYPE = new MemoryTypeRef("other-registered-type");
    private static final MemoryTypeRef UNKNOWN_TYPE = new MemoryTypeRef("unknown-type");
    private static final MemoryTypeRef EPISODIC = new MemoryTypeRef("episode");
    private static final MemoryTypeRef SENSITIVE = new MemoryTypeRef("sensitive");
    private static final RetentionClassRef RETENTION = new RetentionClassRef("standard");
    private static final RetentionClassRef OTHER_RETENTION =
            new RetentionClassRef("different-retention");
    private static final TypedPayload PAYLOAD = new TypedPayload("memory-fact", "{}");
    private static final EventId SOURCE_EVENT = new EventId("source-event-1");
    private static final ExecutionRef EXECUTION = new ExecutionRef(TENANT, "execution-1");
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant CREATED = NOW.minusSeconds(60);
    private static final Instant AS_OF = NOW.minusSeconds(10);
    private static final Instant EVALUATED = NOW.minusSeconds(5);
    private static final InputId INPUT = new InputId("input-1");
    private static final TraceEnvelope TRACE = new TraceEnvelope(
            "trace-1", "span-1", INPUT, Optional.of(new EventId("cause-1")));
    private static final PortCallContext CONTEXT = context();
    private static final ResultRef QUERY_RESULT = new ResultRef("memory-query-result-1");
    private static final ResultRef ACCESS_AUDIT = new ResultRef("memory-access-audit-1");
    private static final ResultRef SOURCE_AUDIT = new ResultRef("memory-source-audit-1");
    private static final ResultRef CONFLICT_AUDIT = new ResultRef("memory-conflict-audit-1");

    @Test
    void retrievesCandidatesAndEmptyResultsWithoutUsingTheWritePort() {
        Fixture populated = new Fixture();

        MemoryRuntime.RetrievalReceipt receipt = recorded(populated.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, populated.query)));

        assertEquals(MemoryRuntime.Phase.CANDIDATE, receipt.phase());
        assertEquals(List.of("fact-1"), receipt.trace().candidateIds());
        assertEquals(List.of(new MemoryFactRef(TENANT, "fact-1")),
                receipt.candidateFacts().stream().map(MemoryFact::ref).toList());
        assertTrue(receipt.selectedFacts().isEmpty());
        assertTrue(receipt.injectedFacts().isEmpty());
        assertEquals(1, populated.memory.queryCalls);
        assertEquals(0, populated.memory.submitCalls);
        assertEquals(1, populated.log.appendCalls);

        Fixture empty = new Fixture();
        empty.respond(List.of());

        MemoryRuntime.RetrievalReceipt emptyReceipt = recorded(empty.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, empty.query)));

        assertTrue(emptyReceipt.candidateFacts().isEmpty());
        assertTrue(emptyReceipt.trace().candidateIds().isEmpty());
        assertEquals(1, empty.memory.queryCalls);
        assertEquals(0, empty.memory.submitCalls);
        assertEquals(1, empty.log.appendCalls);
    }

    @Test
    void retrievesUserPreferenceForAnotherAgentWithoutMatchingOwnerAgent() {
        Fixture fixture = new Fixture();
        fixture.types.add(registration(TYPE,
                Set.of(MemoryScope.USER, MemoryScope.USER_AGENT, MemoryScope.SESSION), false, false));
        PortCallContext reader = contextFor(OTHER_AGENT, OTHER_SESSION);
        fixture.query = query(TENANT, USER, OTHER_AGENT, Optional.of(OTHER_SESSION),
                Set.of(MemoryScope.USER, MemoryScope.USER_AGENT), Set.of(TYPE),
                MemoryQueryProfile.DEFAULT);
        MemoryFact shared = new FactBuilder("user-pref")
                .scope(MemoryScope.USER).agent(Optional.empty()).predicate("theme").build();
        fixture.respond(List.of(shared));

        MemoryRuntime.RetrievalReceipt receipt = recorded(fixture.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(reader, fixture.query)));

        assertEquals(List.of("user-pref"), receipt.trace().candidateIds());
        assertEquals(List.of(shared), receipt.candidateFacts());
        assertEquals(SESSION, receipt.candidateFacts().getFirst().sourceSessionRef());
        assertEquals(0, fixture.memory.submitCalls);
    }

    @Test
    void overlaysMatchingUserAgentPreferenceWithoutMutatingUserFact() {
        MemoryFact shared = new FactBuilder("user-pref")
                .scope(MemoryScope.USER).agent(Optional.empty()).predicate("theme").build();
        MemoryFact owned = new FactBuilder("agent-pref").predicate("theme").build();
        assertEquals(List.of(owned), MemoryRuntime.overlayUserAgentOverUser(List.of(shared, owned)));
        assertEquals(MemoryScope.USER, shared.scope());
        assertEquals(new MemoryOwner.User(TENANT, USER), shared.owner());
    }

    @Test
    void exposesFactsOnlyAcrossTheExactFourPhaseTrace() {
        Fixture fixture = new Fixture();
        MemoryFact first = new FactBuilder("fact-1").build();
        MemoryFact second = new FactBuilder("fact-2").build();
        fixture.respond(List.of(first, second));

        MemoryRuntime.RetrievalReceipt candidate = recorded(fixture.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query)));
        assertTrue(candidate.injectedFacts().isEmpty());
        assertTrue(candidate.referencedFacts().isEmpty());

        MemoryRetrievalTrace selectedTrace = new MemoryRetrievalTrace(
                fixture.query.queryId(), List.of("fact-1", "fact-2"),
                List.of("fact-2"), List.of(), List.of());
        MemoryRuntime.RetrievalReceipt selected = recorded(fixture.runtime.advance(
                candidate, MemoryRuntime.Phase.SELECTED, selectedTrace,
                EVALUATED.plusSeconds(1)));
        assertEquals(List.of(second), selected.selectedFacts());
        assertTrue(selected.injectedFacts().isEmpty());

        MemoryRetrievalTrace injectedTrace = new MemoryRetrievalTrace(
                fixture.query.queryId(), List.of("fact-1", "fact-2"),
                List.of("fact-2"), List.of("fact-2"), List.of());
        MemoryRuntime.RetrievalReceipt injected = recorded(fixture.runtime.advance(
                selected, MemoryRuntime.Phase.INJECTED, injectedTrace,
                EVALUATED.plusSeconds(2)));
        assertEquals(List.of(second), injected.injectedFacts());
        assertTrue(injected.referencedFacts().isEmpty());

        MemoryRetrievalTrace referencedTrace = new MemoryRetrievalTrace(
                fixture.query.queryId(), List.of("fact-1", "fact-2"),
                List.of("fact-2"), List.of("fact-2"), List.of("fact-2"));
        MemoryRuntime.RetrievalReceipt referenced = recorded(fixture.runtime.advance(
                injected, MemoryRuntime.Phase.REFERENCED, referencedTrace,
                EVALUATED.plusSeconds(3)));
        assertEquals(List.of(second), referenced.referencedFacts());
        assertEquals(4, fixture.log.appendCalls);
        assertEquals(1, fixture.memory.queryCalls);
        assertEquals(0, fixture.memory.submitCalls);

        MemoryRuntime.RetrievalResult skipped = fixture.runtime.advance(
                candidate, MemoryRuntime.Phase.INJECTED, injectedTrace,
                EVALUATED.plusSeconds(2));
        assertEquals("memory_retrieval_phase_rejected", stoppedReason(skipped));
        assertEquals(4, fixture.log.appendCalls);
    }

    @Test
    void retriesAnUncertainRetrievalWithoutRequeryingOrRevalidatingSources() {
        Fixture fixture = new Fixture();
        fixture.log.mode = LogMode.PERSIST_THEN_THROW;

        MemoryRuntime.RetrievalResult initial = fixture.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query));
        MemoryRuntime.RetrievalResult.Pending pending = assertInstanceOf(
                MemoryRuntime.RetrievalResult.Pending.class, initial);
        assertEquals("memory_session_log_outcome_unknown", pending.failure().reasonCode());
        int registryCalls = fixture.types.calls;
        int sourceReads = fixture.log.findCalls;
        int accessCalls = fixture.access.calls;

        MemoryRuntime.RetrievalReceipt receipt = recorded(fixture.runtime.retry(pending.pending()));

        assertEquals(pending.pending().eventId(), receipt.committedEvent().eventId());
        assertEquals(1, fixture.memory.queryCalls);
        assertEquals(0, fixture.memory.submitCalls);
        assertEquals(registryCalls, fixture.types.calls);
        assertEquals(sourceReads, fixture.log.findCalls);
        assertEquals(accessCalls, fixture.access.calls);
        assertEquals(2, fixture.log.appendCalls);
    }

    @Test
    void replaysFrozenFactsWithCurrentPermissionAndGovernanceWithoutSearchingOrReadingSources() {
        Fixture original = new Fixture();
        MemoryRuntime.RetrievalReceipt committed = recorded(original.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, original.query)));
        Fixture replay = new Fixture();

        MemoryRuntime.RetrievalReceipt receipt = replay.runtime.replayRetrieval(
                CONTEXT, committed.committedEvent());

        assertEquals(committed.query(), receipt.query());
        assertEquals(committed.trace(), receipt.trace());
        assertEquals(committed.candidateFacts(), receipt.candidateFacts());
        assertEquals(0, replay.memory.queryCalls);
        assertEquals(0, replay.memory.submitCalls);
        assertEquals(2, replay.access.calls);
        assertEquals(0, replay.types.calls);
        assertEquals(0, replay.sources.calls);
        assertEquals(0, replay.log.findCalls);
        assertEquals(0, replay.log.appendCalls);
        assertEquals(0, replay.current.calls);
        assertEquals(2, replay.memory.currentBatchCalls);
    }

    @Test
    void keepsInjectedFactsUnavailableWhenTheirAppendAcknowledgementDrifts() {
        Fixture fixture = new Fixture();
        MemoryRuntime.RetrievalReceipt candidate = recorded(fixture.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query)));
        MemoryRetrievalTrace selectedTrace = new MemoryRetrievalTrace(
                fixture.query.queryId(), List.of("fact-1"), List.of("fact-1"),
                List.of(), List.of());
        MemoryRuntime.RetrievalReceipt selected = recorded(fixture.runtime.advance(
                candidate, MemoryRuntime.Phase.SELECTED, selectedTrace,
                EVALUATED.plusSeconds(1)));
        fixture.log.mode = LogMode.BAD_ACK;
        MemoryRetrievalTrace injectedTrace = new MemoryRetrievalTrace(
                fixture.query.queryId(), List.of("fact-1"), List.of("fact-1"),
                List.of("fact-1"), List.of());

        MemoryRuntime.RetrievalResult result = fixture.runtime.advance(
                selected, MemoryRuntime.Phase.INJECTED, injectedTrace,
                EVALUATED.plusSeconds(2));

        MemoryRuntime.RetrievalResult.Pending pending = assertInstanceOf(
                MemoryRuntime.RetrievalResult.Pending.class, result);
        assertEquals(MemoryRuntime.Phase.INJECTED, pending.pending().phase());
        assertEquals("memory_session_log_ack_mismatch", pending.failure().reasonCode());
        assertFalse(result instanceof MemoryRuntime.RetrievalResult.Recorded);
        assertTrue(selected.injectedFacts().isEmpty());
        assertEquals(3, fixture.log.appendCalls);
    }

    @Test
    void replaysFrozenInjectedReceiptUsingOnlyCurrentAuthorizationAndGovernanceReads() {
        Fixture original = new Fixture();
        MemoryRuntime.RetrievalReceipt candidate = recorded(original.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, original.query)));
        MemoryRetrievalTrace selectedTrace = new MemoryRetrievalTrace(
                original.query.queryId(), List.of("fact-1"), List.of("fact-1"),
                List.of(), List.of());
        MemoryRuntime.RetrievalReceipt selected = recorded(original.runtime.advance(
                candidate, MemoryRuntime.Phase.SELECTED, selectedTrace,
                EVALUATED.plusSeconds(1)));
        MemoryRetrievalTrace injectedTrace = new MemoryRetrievalTrace(
                original.query.queryId(), List.of("fact-1"), List.of("fact-1"),
                List.of("fact-1"), List.of());
        MemoryRuntime.RetrievalReceipt injected = recorded(original.runtime.advance(
                selected, MemoryRuntime.Phase.INJECTED, injectedTrace,
                EVALUATED.plusSeconds(2)));
        Fixture replay = new Fixture();

        MemoryRuntime.RetrievalReceipt restored = replay.runtime.replayRetrieval(
                CONTEXT, injected.committedEvent());

        assertEquals(MemoryRuntime.Phase.INJECTED, restored.phase());
        assertEquals(List.of(new MemoryFactRef(TENANT, "fact-1")),
                restored.injectedFacts().stream().map(MemoryFact::ref).toList());
        assertEquals(0, replay.memory.queryCalls);
        assertEquals(0, replay.memory.submitCalls);
        assertEquals(0, replay.types.calls);
        assertEquals(0, replay.sources.calls);
        assertEquals(0, replay.log.findCalls);
        assertEquals(0, replay.log.appendCalls);
        assertEquals(2, replay.access.calls);
        assertEquals(0, replay.current.calls);
        assertEquals(2, replay.memory.currentBatchCalls);
    }

    @Test
    void findsTheLatestFrozenRetrievalAndReauthorizesWithoutQueryingMemory() {
        Fixture fixture = new Fixture();
        MemoryRuntime.RetrievalReceipt candidate = recorded(fixture.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query)));
        MemoryRetrievalTrace selectedTrace = new MemoryRetrievalTrace(
                fixture.query.queryId(), candidate.trace().candidateIds(),
                candidate.trace().candidateIds(), List.of(), List.of());
        MemoryRuntime.RetrievalReceipt selected = recorded(fixture.runtime.advance(
                candidate, MemoryRuntime.Phase.SELECTED, selectedTrace,
                EVALUATED.plusSeconds(1)));
        MemoryRetrievalTrace injectedTrace = new MemoryRetrievalTrace(
                fixture.query.queryId(), selected.trace().candidateIds(),
                selected.trace().selectedIds(), selected.trace().selectedIds(), List.of());
        MemoryRuntime.RetrievalReceipt injected = recorded(fixture.runtime.advance(
                selected, MemoryRuntime.Phase.INJECTED, injectedTrace,
                EVALUATED.plusSeconds(2)));
        int queryCalls = fixture.memory.queryCalls;
        int accessCalls = fixture.access.calls;

        MemoryRuntime.RetrievalReceipt restored = fixture.runtime.findLatestRetrieval(
                CONTEXT, fixture.query.queryId()).orElseThrow();

        assertEquals(MemoryRuntime.Phase.INJECTED, restored.phase());
        assertEquals(injected.committedEvent(), restored.committedEvent());
        assertEquals(injected.injectedFacts(), restored.injectedFacts());
        assertEquals(queryCalls, fixture.memory.queryCalls);
        assertEquals(accessCalls + 3, fixture.access.calls);
        assertEquals(MemoryAccessPolicy.Operation.QUERY, fixture.access.lastRequest.operation());
        assertEquals(fixture.query.scopes(), fixture.access.lastRequest.scopes());
        assertEquals(fixture.query.types(), fixture.access.lastRequest.types());
        assertTrue(fixture.log.readCalls > 0);
    }

    @Test
    void returnsEmptyForAnotherQueryIdWithoutReauthorizing() {
        Fixture fixture = new Fixture();
        recorded(fixture.runtime.retrieve(new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query)));
        int accessCalls = fixture.access.calls;

        assertTrue(fixture.runtime.findLatestRetrieval(CONTEXT, "other-query").isEmpty());

        assertEquals(accessCalls, fixture.access.calls);
        assertEquals(1, fixture.memory.queryCalls);
    }

    @Test
    void refusesFrozenRetrievalWhenCurrentControlOrAccessRejectsIt() {
        Fixture denied = new Fixture();
        recorded(denied.runtime.retrieve(new MemoryRuntime.RetrievalCall(CONTEXT, denied.query)));
        denied.access.decision = new MemoryAccessPolicy.Decision.Denied(
                "memory_access_denied", ACCESS_AUDIT);

        IllegalStateException accessFailure = assertThrows(IllegalStateException.class,
                () -> denied.runtime.findLatestRetrieval(CONTEXT, denied.query.queryId()));
        assertEquals("memory_access_denied", accessFailure.getMessage());
        assertEquals(1, denied.memory.queryCalls);

        Fixture cancelled = new Fixture();
        recorded(cancelled.runtime.retrieve(new MemoryRuntime.RetrievalCall(CONTEXT, cancelled.query)));
        int reads = cancelled.log.readCalls;
        PortCallContext stopped = withControl(new InvocationControl(
                NOW.plusSeconds(60), () -> true));

        IllegalStateException cancellation = assertThrows(IllegalStateException.class,
                () -> cancelled.runtime.findLatestRetrieval(stopped, cancelled.query.queryId()));
        assertEquals("memory_call_cancelled", cancellation.getMessage());
        assertEquals(reads, cancelled.log.readCalls);
    }

    @Test
    void failsClosedWhenAFrozenRetrievalPhaseIsDuplicated() {
        Fixture fixture = new Fixture();
        CommittedSessionEvent candidate = recorded(fixture.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query))).committedEvent();
        fixture.log.readEvents.add(new CommittedSessionEvent(
                candidate.owner(), candidate.inputId(), candidate.runRef(),
                candidate.executionRef(), candidate.actionRef(), candidate.delegationRef(),
                candidate.eventId(), candidate.sessionSequence() + 1,
                candidate.eventType(), candidate.eventVersion(), candidate.trace(),
                candidate.payload(), candidate.occurredAt()));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.runtime.findLatestRetrieval(CONTEXT, fixture.query.queryId()));

        assertEquals("memory_retrieval_chain_invalid", failure.getMessage());
    }

    @Test
    void rejectsTamperedReplayTargetEventIdentityAndPayloadWithoutExternalCalls() {
        Fixture original = new Fixture();
        CommittedSessionEvent event = recorded(original.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, original.query))).committedEvent();
        Fixture replay = new Fixture();

        CommittedSessionEvent wrongTarget = copyEvent(
                event, new InputId("other-input"), event.eventId(), event.payload());
        IllegalStateException targetFailure = assertThrows(IllegalStateException.class,
                () -> replay.runtime.replayRetrieval(CONTEXT, wrongTarget));
        assertEquals("memory_replay_target_mismatch", targetFailure.getMessage());

        CommittedSessionEvent wrongEventId = copyEvent(
                event, event.inputId(), new EventId("tampered-retrieval-event"),
                event.payload());
        IllegalStateException identityFailure = assertThrows(IllegalStateException.class,
                () -> replay.runtime.replayRetrieval(CONTEXT, wrongEventId));
        assertEquals("memory_replay_event_mismatch", identityFailure.getMessage());

        String tamperedJson = event.payload().canonicalJson()
                .replace("query-1", "query-tampered");
        assertFalse(tamperedJson.equals(event.payload().canonicalJson()));
        CommittedSessionEvent wrongPayload = copyEvent(
                event, event.inputId(), event.eventId(),
                new TypedPayload(event.payload().schemaId(), tamperedJson));
        assertThrows(IllegalStateException.class,
                () -> replay.runtime.replayRetrieval(CONTEXT, wrongPayload));

        assertNoExternalCalls(replay);
    }

    @Test
    void rejectsUntrustedQueryResultsBeforeRecordingThem() {
        List<NamedScenario> scenarios = List.of(
                scenario("query id", fixture -> fixture.memory.queryOutcome = retrieved(
                        new MemoryQueryResult("other-query",
                List.of(),
                QUERY_RESULT,
                EVALUATED,
                testDiagnostics(List.of())))),
                scenario("max items", fixture -> fixture.respond(List.of(
                        new FactBuilder("fact-1").build(),
                        new FactBuilder("fact-2").build(),
                        new FactBuilder("fact-3").build()))),
                scenario("tenant", fixture -> {
                    MemoryFact fact = new FactBuilder("wrong-tenant")
                            .ref(new MemoryFactRef(OTHER_TENANT, "wrong-tenant"))
                            .sourceSession(OTHER_TENANT_SESSION).build();
                    fixture.respond(List.of(configureSource(fixture,
                            SessionOwner.user(OTHER_TENANT_SESSION, AGENT, USER), fact)));
                }),
                scenario("end user", fixture -> {
                    MemoryFact fact = new FactBuilder("wrong-user").user(OTHER_USER).build();
                    fixture.respond(List.of(configureSource(fixture,
                            SessionOwner.user(SESSION, AGENT, OTHER_USER), fact)));
                }),
                scenario("agent", fixture -> {
                    MemoryFact fact = new FactBuilder("wrong-agent").agent(OTHER_AGENT).build();
                    fixture.respond(List.of(configureSource(fixture,
                            SessionOwner.user(SESSION, OTHER_AGENT, USER), fact)));
                }),
                scenario("session", fixture -> {
                    fixture.query = query(Set.of(MemoryScope.SESSION), Set.of(TYPE),
                            MemoryQueryProfile.DEFAULT);
                    MemoryFact fact = new FactBuilder("wrong-session")
                            .scope(MemoryScope.SESSION)
                            .session(Optional.of(OTHER_SESSION))
                            .sourceSession(OTHER_SESSION).build();
                    fixture.respond(List.of(configureSource(fixture,
                            SessionOwner.user(OTHER_SESSION, AGENT, USER), fact)));
                }),
                scenario("scope", fixture -> {
                    fixture.query = query(Set.of(MemoryScope.SESSION), Set.of(TYPE),
                            MemoryQueryProfile.DEFAULT);
                    fixture.respond(List.of(new FactBuilder("wrong-scope").build()));
                }),
                scenario("type", fixture -> fixture.respond(List.of(
                        new FactBuilder("wrong-type")
                                .type(OTHER_TYPE).build()))),
                scenario("status", fixture -> fixture.respond(List.of(
                        new FactBuilder("inactive").status(FactStatus.DECAYED).build()))),
                scenario("validTo", fixture -> fixture.respond(List.of(
                        new FactBuilder("ended").validTo(Optional.of(AS_OF)).build()))),
                scenario("expiresAt", fixture -> fixture.respond(List.of(
                        new FactBuilder("expired").expiresAt(Optional.of(AS_OF)).build()))),
                scenario("future creation", fixture -> fixture.respond(List.of(
                        new FactBuilder("future")
                                .createdAt(EVALUATED.plusSeconds(1)).build()))),
                scenario("future validity", fixture -> fixture.respond(List.of(
                        new FactBuilder("not-yet-valid")
                                .validFrom(AS_OF.plusSeconds(1)).build()))),
                scenario("superseded", fixture -> fixture.respond(List.of(
                        new FactBuilder("superseded").supersededBy(Optional.of(
                                new MemoryFactRef(TENANT, "successor"))).build()))),
                scenario("missing source", fixture -> {
                    fixture.log.sources.clear();
                    fixture.respond(List.of(new FactBuilder("missing-source").build()));
                }),
                scenario("mismatched source", fixture -> {
                    fixture.log.sources.put(SOURCE_EVENT, sourceEvent(
                            new EventId("different-source"), OWNER, Optional.empty()));
                    fixture.respond(List.of(new FactBuilder("mismatched-source").build()));
                }));

        for (NamedScenario scenario : scenarios) {
            Fixture fixture = new Fixture();
            scenario.arrange().accept(fixture);

            MemoryRuntime.RetrievalResult result = fixture.runtime.retrieve(
                    new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query));

            assertEquals("memory_query_result_rejected", stoppedReason(result), scenario.name());
            assertEquals(0, fixture.log.appendCalls, scenario.name());
            assertEquals(0, fixture.memory.submitCalls, scenario.name());
        }
    }

    @Test
    void rejectsQueryOwnerDriftBeforeCallingTheMemoryPort() {
        List<NamedScenario> scenarios = List.of(
                scenario("tenant", fixture -> fixture.query = query(
                        OTHER_TENANT, USER, AGENT, Optional.empty(),
                        Set.of(MemoryScope.USER_AGENT), Set.of(TYPE),
                        MemoryQueryProfile.DEFAULT)),
                scenario("end user", fixture -> fixture.query = query(
                        TENANT, OTHER_USER, AGENT, Optional.empty(),
                        Set.of(MemoryScope.USER_AGENT), Set.of(TYPE),
                        MemoryQueryProfile.DEFAULT)),
                scenario("agent", fixture -> fixture.query = query(
                        TENANT, USER, OTHER_AGENT, Optional.empty(),
                        Set.of(MemoryScope.USER_AGENT), Set.of(TYPE),
                        MemoryQueryProfile.DEFAULT)),
                scenario("session", fixture -> fixture.query = query(
                        TENANT, USER, AGENT, Optional.of(OTHER_SESSION),
                        Set.of(MemoryScope.USER_AGENT), Set.of(TYPE),
                        MemoryQueryProfile.DEFAULT)));

        for (NamedScenario scenario : scenarios) {
            Fixture fixture = new Fixture();
            scenario.arrange().accept(fixture);

            MemoryRuntime.RetrievalResult result = fixture.runtime.retrieve(
                    new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query));

            assertEquals("memory_query_contract_rejected", stoppedReason(result), scenario.name());
            assertEquals(1, fixture.access.calls, scenario.name());
            assertEquals(0, fixture.types.calls, scenario.name());
            assertEquals(0, fixture.memory.queryCalls, scenario.name());
            assertNoLogOrMutationSideEffects(fixture, scenario.name());
        }
    }

    @Test
    void acceptsHistoricalUserAgentSourcesAndTheInclusiveValidFromBoundary() {
        EventId historicalEvent = new EventId("historical-source-event");
        Fixture historical = new Fixture();
        historical.log.sources.put(historicalEvent, sourceEvent(
                historicalEvent, SessionOwner.user(OTHER_SESSION, AGENT, USER),
                Optional.empty()));
        MemoryFact historicalFact = new FactBuilder("historical-fact")
                .sourceEvents(List.of(historicalEvent))
                .sourceSession(OTHER_SESSION)
                .build();
        historical.respond(List.of(historicalFact));

        MemoryRuntime.RetrievalReceipt receipt = recorded(historical.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, historical.query)));
        assertEquals(List.of(historicalFact), receipt.candidateFacts());
        assertEquals(SessionOwner.user(OTHER_SESSION, AGENT, USER),
                historical.log.lastFindOwner);

        Fixture wrongSourceOwner = new Fixture();
        wrongSourceOwner.log.sources.put(historicalEvent, sourceEvent(
                historicalEvent, OWNER, Optional.empty()));
        wrongSourceOwner.respond(List.of(historicalFact));
        MemoryRuntime.RetrievalResult rejected = wrongSourceOwner.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, wrongSourceOwner.query));
        assertEquals("memory_query_result_rejected", stoppedReason(rejected));
        assertEquals(0, wrongSourceOwner.log.appendCalls);

        Fixture boundary = new Fixture();
        MemoryFact startsAtAsOf = new FactBuilder("valid-at-boundary")
                .validFrom(AS_OF).build();
        boundary.respond(List.of(startsAtAsOf));
        assertEquals(List.of(startsAtAsOf), recorded(boundary.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, boundary.query))).candidateFacts());

        Fixture future = new Fixture();
        future.respond(List.of(new FactBuilder("valid-in-future")
                .validFrom(AS_OF.plusNanos(1)).build()));
        assertEquals("memory_query_result_rejected", stoppedReason(future.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, future.query))));
        assertEquals(0, future.log.appendCalls);
    }

    @Test
    void rejectsBadOrConflictingRetrievalSessionLogAcknowledgements() {
        Fixture badAck = new Fixture();
        badAck.log.mode = LogMode.BAD_ACK;

        MemoryRuntime.RetrievalResult.Pending pending = assertInstanceOf(
                MemoryRuntime.RetrievalResult.Pending.class,
                badAck.runtime.retrieve(new MemoryRuntime.RetrievalCall(CONTEXT, badAck.query)));
        assertEquals("memory_session_log_ack_mismatch", pending.failure().reasonCode());

        Fixture conflict = new Fixture();
        conflict.log.mode = LogMode.CONFLICT;

        MemoryRuntime.RetrievalResult result = conflict.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, conflict.query));
        assertEquals("memory_session_event_conflict", stoppedReason(result));
        assertEquals(1, conflict.memory.queryCalls);
        assertEquals(0, conflict.memory.submitCalls);
    }

    @Test
    void enforcesTheRecentEpisodicQueryContract() {
        Fixture valid = new Fixture();
        valid.query = query(Set.of(MemoryScope.USER_AGENT), Set.of(EPISODIC),
                MemoryQueryProfile.RECENT_EPISODIC);
        valid.respond(List.of(new FactBuilder("episode-1").type(EPISODIC).build()));
        assertEquals(MemoryRuntime.Phase.CANDIDATE, recorded(valid.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, valid.query))).phase());

        Fixture wrongScope = new Fixture();
        wrongScope.query = query(Set.of(MemoryScope.SESSION), Set.of(EPISODIC),
                MemoryQueryProfile.RECENT_EPISODIC);
        assertEquals("memory_query_contract_rejected", stoppedReason(wrongScope.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, wrongScope.query))));
        assertEquals(0, wrongScope.memory.queryCalls);

        Fixture nonEpisodicType = new Fixture();
        nonEpisodicType.query = query(Set.of(MemoryScope.USER_AGENT), Set.of(TYPE),
                MemoryQueryProfile.RECENT_EPISODIC);
        assertEquals("memory_query_contract_rejected", stoppedReason(
                nonEpisodicType.runtime.retrieve(new MemoryRuntime.RetrievalCall(
                        CONTEXT, nonEpisodicType.query))));
        assertEquals(0, nonEpisodicType.memory.queryCalls);

        Fixture noTypes = new Fixture();
        noTypes.query = query(Set.of(MemoryScope.USER_AGENT), Set.of(),
                MemoryQueryProfile.RECENT_EPISODIC);
        assertEquals("memory_query_contract_rejected", stoppedReason(noTypes.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, noTypes.query))));
        assertEquals(0, noTypes.memory.queryCalls);
    }

    @Test
    void rejectsEveryReturnedFactTypeContractDriftBeforeRecording() {
        List<NamedScenario> scenarios = List.of(
                scenario("unknown type", fixture -> {
                    fixture.query = query(Set.of(MemoryScope.USER_AGENT), Set.of(),
                            MemoryQueryProfile.DEFAULT);
                    fixture.respond(List.of(new FactBuilder("unknown-type")
                            .type(UNKNOWN_TYPE).build()));
                }),
                scenario("unknown schema", fixture -> fixture.respond(List.of(
                        new FactBuilder("unknown-schema").schemaVersion("v2").build()))),
                scenario("scope", fixture -> {
                    fixture.query = query(Set.of(MemoryScope.USER_AGENT), Set.of(),
                            MemoryQueryProfile.DEFAULT);
                    fixture.types.add(registration(OTHER_TYPE,
                            Set.of(MemoryScope.SESSION), false, false));
                    fixture.respond(List.of(new FactBuilder("scope-drift")
                            .type(OTHER_TYPE).build()));
                }),
                scenario("retention", fixture -> fixture.respond(List.of(
                        new FactBuilder("retention-drift")
                                .retention(OTHER_RETENTION).build()))),
                scenario("payload schema", fixture -> fixture.respond(List.of(
                        new FactBuilder("payload-schema-drift")
                                .payload(new TypedPayload("wrong-payload-schema", "{}"))
                                .build()))),
                scenario("validator rejected", fixture -> {
                    fixture.types.add(registration(TYPE,
                            (schema, payload) -> new MemoryTypeRegistry.Validation.Rejected(
                                    "memory_payload_rejected")));
                    fixture.respond(List.of(new FactBuilder("validator-rejected").build()));
                }),
                scenario("validator exception", fixture -> {
                    fixture.types.add(registration(TYPE, (schema, payload) -> {
                        throw new IllegalStateException("validator unavailable");
                    }));
                    fixture.respond(List.of(new FactBuilder("validator-exception").build()));
                }),
                scenario("validator null", fixture -> {
                    fixture.types.add(registration(TYPE, (schema, payload) -> null));
                    fixture.respond(List.of(new FactBuilder("validator-null").build()));
                }));

        for (NamedScenario scenario : scenarios) {
            Fixture fixture = new Fixture();
            scenario.arrange().accept(fixture);

            MemoryRuntime.RetrievalResult result = fixture.runtime.retrieve(
                    new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query));

            assertEquals("memory_query_result_rejected", stoppedReason(result), scenario.name());
            assertEquals(1, fixture.memory.queryCalls, scenario.name());
            assertEquals(0, fixture.log.findCalls, scenario.name());
            assertEquals(0, fixture.log.appendCalls, scenario.name());
            assertEquals(0, fixture.memory.submitCalls, scenario.name());
        }
    }

    @Test
    void rejectsEveryCreateDraftTypeContractDriftBeforeSubmit() {
        List<DraftScenario> scenarios = List.of(
                new DraftScenario("unknown type", "memory_type_not_registered",
                        fixture -> new DraftBuilder().type(UNKNOWN_TYPE).build()),
                new DraftScenario("unknown schema", "memory_schema_not_registered",
                        fixture -> new DraftBuilder().schemaVersion("v2").build()),
                new DraftScenario("scope", "memory_type_contract_mismatch", fixture -> {
                    fixture.types.add(registration(TYPE,
                            Set.of(MemoryScope.SESSION), false, false));
                    return new DraftBuilder().build();
                }),
                new DraftScenario("retention", "memory_type_contract_mismatch",
                        fixture -> new DraftBuilder().retention(OTHER_RETENTION).build()),
                new DraftScenario("payload schema", "memory_type_contract_mismatch",
                        fixture -> new DraftBuilder()
                                .payload(new TypedPayload("wrong-payload-schema", "{}"))
                                .build()),
                new DraftScenario("validator rejected", "memory_payload_rejected", fixture -> {
                    fixture.types.add(registration(TYPE,
                            (schema, payload) -> new MemoryTypeRegistry.Validation.Rejected(
                                    "memory_payload_rejected")));
                    return new DraftBuilder().build();
                }),
                new DraftScenario("validator exception", "memory_mutation_contract_rejected",
                        fixture -> {
                            fixture.types.add(registration(TYPE, (schema, payload) -> {
                                throw new IllegalStateException("validator unavailable");
                            }));
                            return new DraftBuilder().build();
                        }),
                new DraftScenario("validator null", "memory_mutation_contract_rejected",
                        fixture -> {
                            fixture.types.add(registration(TYPE, (schema, payload) -> null));
                            return new DraftBuilder().build();
                        }));

        for (DraftScenario scenario : scenarios) {
            Fixture fixture = new Fixture();
            MemoryFactDraft draft = scenario.draft().apply(fixture);

            MemoryRuntime.MutationResult result = fixture.runtime.mutate(
                    mutationCall("draft-" + scenario.name(), draft));

            assertEquals(scenario.reason(), stoppedReason(result), scenario.name());
            assertEquals(1, fixture.access.calls, scenario.name());
            assertEquals(0, fixture.log.findCalls, scenario.name());
            assertEquals(0, fixture.sources.calls, scenario.name());
            assertEquals(0, fixture.memory.submitCalls, scenario.name());
            assertEquals(0, fixture.log.appendCalls, scenario.name());
        }
    }

    @Test
    void forcesSensitiveDraftsThroughPendingReview() {
        Fixture rejected = new Fixture();
        MemoryFactDraft unsafe = new DraftBuilder().type(SENSITIVE)
                .status(FactStatus.ACTIVE).reviewRequired(false).build();

        MemoryRuntime.MutationResult result = rejected.runtime.mutate(new MemoryRuntime.MutationCall(
                CONTEXT, new MemoryRuntime.Mutation.Create(new IdempotencyKey("create-sensitive"), unsafe), NOW));

        assertEquals("memory_review_required", stoppedReason(result));
        assertEquals(0, rejected.memory.submitCalls);
        assertEquals(0, rejected.log.findCalls);

        Fixture accepted = new Fixture();
        MemoryFactDraft reviewed = new DraftBuilder().type(SENSITIVE)
                .status(FactStatus.PENDING_CONFIRM).reviewRequired(true).build();
        MemoryFact applied = factFromDraft(reviewed, "sensitive-fact");
        accepted.memory.mutationResult = new MemoryMutationResult.Applied(applied, Optional.empty(), false);

        MemoryRuntime.MutationResult.Recorded recorded = assertInstanceOf(
                MemoryRuntime.MutationResult.Recorded.class,
                accepted.runtime.mutate(new MemoryRuntime.MutationCall(
                        CONTEXT, new MemoryRuntime.Mutation.Create(
                                new IdempotencyKey("create-reviewed"), reviewed), NOW)));
        assertEquals(FactStatus.PENDING_CONFIRM, recorded.fact().status());
        assertEquals(1, accepted.memory.submitCalls);
    }

    @Test
    void recordsCreateAndAcceptsExactMemoryAndSessionLogReplay() {
        Fixture fixture = new Fixture();
        MemoryFactDraft draft = new DraftBuilder().build();
        MemoryFact created = factFromDraft(draft, "created-fact");
        IdempotencyKey key = new IdempotencyKey("create-1");
        MemoryRuntime.MutationCall call = new MemoryRuntime.MutationCall(
                CONTEXT, new MemoryRuntime.Mutation.Create(key, draft), NOW);
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(created, Optional.empty(), false);

        MemoryRuntime.MutationResult.Recorded first = assertInstanceOf(
                MemoryRuntime.MutationResult.Recorded.class, fixture.runtime.mutate(call));
        assertFalse(first.memoryReplayed());
        assertEquals(created, first.fact());
        assertInstanceOf(MemoryCommand.Create.class, fixture.memory.lastCommand);

        fixture.memory.mutationResult = new MemoryMutationResult.Applied(created, Optional.empty(), true);
        MemoryRuntime.MutationResult.Recorded replayed = assertInstanceOf(
                MemoryRuntime.MutationResult.Recorded.class, fixture.runtime.mutate(call));

        assertTrue(replayed.memoryReplayed());
        assertEquals(first.event(), replayed.event());
        assertEquals(2, fixture.memory.submitCalls);
        assertEquals(1, fixture.log.appendCalls);
        assertEquals(2, fixture.access.calls);
        assertEquals(1, fixture.log.appended.size());
    }

    @Test
    void rejectsMutationSourcesAndPolicyDenialsBeforeSubmitting() {
        Fixture denied = new Fixture();
        denied.sources.decision = new MemorySourcePolicy.Decision.Denied(
                "memory_source_denied", SOURCE_AUDIT);
        MemoryFactDraft validDraft = new DraftBuilder().build();
        MemoryRuntime.MutationResult deniedResult = denied.runtime.mutate(mutationCall(
                "source-denied", validDraft));
        assertEquals("memory_source_denied", stoppedReason(deniedResult));
        assertEquals(1, denied.sources.calls);
        assertEquals(0, denied.memory.submitCalls);

        Fixture missing = new Fixture();
        missing.log.sources.clear();
        MemoryRuntime.MutationResult missingResult = missing.runtime.mutate(mutationCall(
                "source-missing", new DraftBuilder().build()));
        assertEquals("memory_source_event_missing", stoppedReason(missingResult));
        assertEquals(0, missing.sources.calls);
        assertEquals(0, missing.memory.submitCalls);

        Fixture exportWithoutExecution = new Fixture();
        MemoryFactDraft invalidExport = new DraftBuilder()
                .exportPath(Optional.of("$.result.preference")).build();
        MemoryRuntime.MutationResult exportResult = exportWithoutExecution.runtime.mutate(
                mutationCall("export-without-execution", invalidExport));
        assertEquals("memory_export_missing_execution", stoppedReason(exportResult));
        assertEquals(0, exportWithoutExecution.log.findCalls);
        assertEquals(0, exportWithoutExecution.sources.calls);
        assertEquals(0, exportWithoutExecution.memory.submitCalls);
    }

    @Test
    void propagatesRequestAwareSourcePolicyDenialsWithTheirAuditReference() {
        List<SourceScenario> scenarios = List.of(
                new SourceScenario(
                        "callback raw", "callback.received.raw",
                        new TypedPayload("callback-raw", "{}"), Optional.empty(),
                        "memory_callback_raw_source_denied"),
                new SourceScenario(
                        "checkpoint", "workflow.checkpoint.saved",
                        new TypedPayload("workflow-checkpoint", "{}"), Optional.empty(),
                        "memory_checkpoint_source_denied"),
                new SourceScenario(
                        "unexported data", "workflow.completed",
                        new TypedPayload("workflow-result-data", "{}"),
                        Optional.of(EXECUTION), "memory_unexported_data_source_denied"));

        for (SourceScenario scenario : scenarios) {
            Fixture fixture = new Fixture();
            CommittedSessionEvent source = sourceEvent(
                    SOURCE_EVENT, OWNER, scenario.executionRef(),
                    scenario.eventType(), scenario.payload());
            fixture.log.sources.put(SOURCE_EVENT, source);
            fixture.sources.responder = MemoryRuntimeTest::sourceDecision;
            DraftBuilder draftBuilder = new DraftBuilder();
            scenario.executionRef().ifPresent(execution ->
                    draftBuilder.sourceExecution(Optional.of(execution)));
            MemoryFactDraft draft = draftBuilder.build();

            MemoryRuntime.MutationResult.Stopped stopped = assertInstanceOf(
                    MemoryRuntime.MutationResult.Stopped.class,
                    fixture.runtime.mutate(mutationCall(
                            "source-policy-" + scenario.name(), draft)),
                    scenario.name());

            assertEquals(scenario.reason(), stopped.failure().reasonCode(), scenario.name());
            assertEquals(Optional.of(SOURCE_AUDIT), stopped.failure().auditRef(), scenario.name());
            assertEquals(new MemorySourcePolicy.Request(CONTEXT, draft, List.of(source)),
                    fixture.sources.lastRequest, scenario.name());
            assertEquals(1, fixture.sources.calls, scenario.name());
            assertEquals(0, fixture.memory.submitCalls, scenario.name());
            assertEquals(0, fixture.log.appendCalls, scenario.name());
        }
    }

    @Test
    void acceptsAnOrdinaryMatchingExecutionSourceWithoutAnExportPath() {
        Fixture fixture = new Fixture();
        CommittedSessionEvent source = sourceEvent(
                SOURCE_EVENT, OWNER, Optional.of(EXECUTION),
                "tool.result.observed", new TypedPayload("tool-observation", "{}"));
        fixture.log.sources.put(SOURCE_EVENT, source);
        fixture.sources.responder = MemoryRuntimeTest::sourceDecision;
        MemoryFactDraft draft = new DraftBuilder()
                .sourceExecution(Optional.of(EXECUTION))
                .exportPath(Optional.empty())
                .build();
        MemoryFact applied = factFromDraft(draft, "execution-source-fact");
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(applied, Optional.empty(), false);

        MemoryRuntime.MutationResult.Recorded recorded = assertInstanceOf(
                MemoryRuntime.MutationResult.Recorded.class,
                fixture.runtime.mutate(mutationCall("ordinary-execution-source", draft)));

        assertEquals(applied, recorded.fact());
        assertEquals(new MemorySourcePolicy.Request(CONTEXT, draft, List.of(source)),
                fixture.sources.lastRequest);
        assertTrue(draft.exportPath().isEmpty());
        assertEquals(1, fixture.memory.submitCalls);
        assertEquals(1, fixture.log.appendCalls);
    }

    @Test
    void failsClosedWhenSourcePolicyThrowsOrReturnsNull() {
        List<NamedScenario> scenarios = List.of(
                scenario("exception", fixture -> fixture.sources.responder = request -> {
                    throw new IllegalStateException("source policy unavailable");
                }),
                scenario("null", fixture -> fixture.sources.responder = request -> null));

        for (NamedScenario scenario : scenarios) {
            Fixture fixture = new Fixture();
            scenario.arrange().accept(fixture);

            MemoryRuntime.MutationResult result = fixture.runtime.mutate(
                    mutationCall("source-policy-" + scenario.name(),
                            new DraftBuilder().build()));

            assertEquals("memory_mutation_contract_rejected",
                    stoppedReason(result), scenario.name());
            assertEquals(1, fixture.sources.calls, scenario.name());
            assertEquals(0, fixture.memory.submitCalls, scenario.name());
            assertEquals(0, fixture.log.appendCalls, scenario.name());
        }
    }

    @Test
    void retriesOnlyTheAuditAppendAfterMemoryWasApplied() {
        Fixture fixture = new Fixture();
        MemoryFactDraft draft = new DraftBuilder().build();
        MemoryFact created = factFromDraft(draft, "possibly-created");
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(created, Optional.empty(), false);
        fixture.log.mode = LogMode.PERSIST_THEN_THROW;

        MemoryRuntime.MutationResult.Pending pending = assertInstanceOf(
                MemoryRuntime.MutationResult.Pending.class,
                fixture.runtime.mutate(mutationCall("pending-audit", draft)));
        assertEquals("memory_session_log_outcome_unknown", pending.failure().reasonCode());
        int accessCalls = fixture.access.calls;
        int registryCalls = fixture.types.calls;
        int sourceReads = fixture.log.findCalls;
        int sourcePolicyCalls = fixture.sources.calls;

        MemoryRuntime.MutationResult.Recorded recorded = assertInstanceOf(
                MemoryRuntime.MutationResult.Recorded.class,
                fixture.runtime.retry(pending.pending()));

        assertEquals(created, recorded.fact());
        assertEquals(1, fixture.memory.submitCalls);
        assertEquals(2, fixture.log.appendCalls);
        assertEquals(accessCalls, fixture.access.calls);
        assertEquals(registryCalls, fixture.types.calls);
        assertEquals(sourceReads, fixture.log.findCalls);
        assertEquals(sourcePolicyCalls, fixture.sources.calls);
    }

    @Test
    void freezesUnknownSubmissionsAcrossRetry() {
        Fixture fixture = new Fixture();
        MemoryFactDraft draft = new DraftBuilder().build();
        MemoryFact created = factFromDraft(draft, "created-after-retry");
        fixture.memory.submitFailure = new IllegalStateException("connection lost");
        MemoryRuntime.MutationCall call = mutationCall("unknown-submit", draft);

        MemoryRuntime.MutationResult.Uncertain uncertain = assertInstanceOf(
                MemoryRuntime.MutationResult.Uncertain.class, fixture.runtime.mutate(call));
        assertEquals("memory_submit_outcome_unknown", uncertain.failure().reasonCode());
        MemoryCommand frozen = fixture.memory.lastCommand;
        int accessCalls = fixture.access.calls;
        int sourcePolicyCalls = fixture.sources.calls;
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(created, Optional.empty(), false);

        MemoryRuntime.MutationResult.Recorded recorded = assertInstanceOf(
                MemoryRuntime.MutationResult.Recorded.class,
                fixture.runtime.retry(uncertain.pending()));

        assertEquals(created, recorded.fact());
        assertSame(frozen, fixture.memory.commands.get(1));
        assertEquals(2, fixture.memory.submitCalls);
        assertEquals(accessCalls + 1, fixture.access.calls);
        assertEquals(sourcePolicyCalls, fixture.sources.calls);
    }

    @Test
    void preparesSupersedeFromTheAuthoritativeCurrentFactAndExactCas() {
        Fixture fixture = new Fixture();
        MemoryFact current = new FactBuilder("current-fact").revision(3).build();
        fixture.current.fact = Optional.of(current);
        MemoryFactDraft replacement = new DraftBuilder()
                .derivedFrom(Optional.of(current.ref())).build();
        MemoryFact applied = factFromDraft(replacement, "replacement-fact");
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(applied, Optional.of(current), false);

        MemoryRuntime.MutationResult result = fixture.runtime.mutate(new MemoryRuntime.MutationCall(
                CONTEXT,
                new MemoryRuntime.Mutation.Supersede(
                        new IdempotencyKey("supersede-1"), current.ref(), 3, replacement),
                NOW));

        assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, result);
        MemoryCommand.Supersede command = assertInstanceOf(
                MemoryCommand.Supersede.class, fixture.memory.lastCommand);
        assertEquals(current.ref(), command.current());
        assertEquals(3, command.expectedRevision());
        assertEquals(replacement, command.replacement());
        assertEquals(1, fixture.current.calls);
        assertEquals(1, fixture.memory.submitCalls);

        Fixture missingDerivation = new Fixture();
        missingDerivation.current.fact = Optional.of(current);
        MemoryFactDraft unrelated = new DraftBuilder().build();
        MemoryRuntime.MutationResult stopped = missingDerivation.runtime.mutate(
                new MemoryRuntime.MutationCall(CONTEXT,
                        new MemoryRuntime.Mutation.Supersede(
                                new IdempotencyKey("supersede-unrelated"),
                                current.ref(), 3, unrelated), NOW));
        assertEquals("memory_supersede_source_mismatch", stoppedReason(stopped));
        assertEquals(0, missingDerivation.memory.submitCalls);

        Fixture staleRevision = new Fixture();
        staleRevision.current.fact = Optional.of(current);
        staleRevision.memory.mutationResult = new MemoryMutationResult.Conflict(
                current.ref(), current.revision(), CONFLICT_AUDIT);
        MemoryRuntime.MutationResult stale = staleRevision.runtime.mutate(
                new MemoryRuntime.MutationCall(CONTEXT,
                        new MemoryRuntime.Mutation.Supersede(
                                new IdempotencyKey("supersede-stale"),
                                current.ref(), 2, replacement), NOW));
        MemoryRuntime.MutationResult.Conflict conflict = assertInstanceOf(
                MemoryRuntime.MutationResult.Conflict.class, stale);
        assertEquals(current.ref(), conflict.fact());
        assertEquals(current.revision(), conflict.currentRevision());
        assertEquals(CONFLICT_AUDIT, conflict.auditRef());
        MemoryCommand.Supersede staleCommand = assertInstanceOf(
                MemoryCommand.Supersede.class, staleRevision.memory.lastCommand);
        assertEquals(2, staleCommand.expectedRevision());
        assertEquals(replacement, staleCommand.replacement());
        assertEquals(Optional.of(current), staleRevision.current.fact);
        assertEquals(1, staleRevision.memory.submitCalls);
        assertEquals(0, staleRevision.log.appendCalls);
    }

    /** 状态已经推进的 stale 请求仍由 Port 的原 revision CAS 判定，不能提前变成领域拒绝。 */
    @Test
    void staleSupersedeAndTransitionKeepExactCasAcrossCurrentStatusChanges() {
        for (FactStatus status : List.of(FactStatus.ACTIVE, FactStatus.FORGOTTEN, FactStatus.ARCHIVED)) {
            MemoryFact current = new FactBuilder("stale-status-" + status)
                    .revision(4).status(status).build();
            MemoryFactDraft replacement = new DraftBuilder().predicate("corrected")
                    .payload(new TypedPayload(PAYLOAD.schemaId(), "{\"value\":\"draft\"}"))
                    .derivedFrom(Optional.of(current.ref())).build();
            List<MemoryRuntime.Mutation> mutations = List.of(
                    new MemoryRuntime.Mutation.Supersede(new IdempotencyKey("stale-supersede-" + status),
                            current.ref(), 3, replacement),
                    new MemoryRuntime.Mutation.Transition(new IdempotencyKey("stale-transition-" + status),
                            current.ref(), 3, current.scope(), current.type(), FactStatus.FORGOTTEN, "forget"));
            for (MemoryRuntime.Mutation mutation : mutations) {
                Fixture fixture = new Fixture();
                fixture.current.fact = Optional.of(current);
                fixture.memory.mutationResult = new MemoryMutationResult.Conflict(
                        current.ref(), current.revision(), CONFLICT_AUDIT);

                MemoryRuntime.MutationResult.Conflict conflict = assertInstanceOf(
                        MemoryRuntime.MutationResult.Conflict.class,
                        fixture.runtime.mutate(new MemoryRuntime.MutationCall(CONTEXT, mutation, NOW)));

                assertEquals(current.ref(), conflict.fact());
                assertEquals(4, conflict.currentRevision());
                assertEquals(CONFLICT_AUDIT, conflict.auditRef());
                switch (fixture.memory.lastCommand) {
                    case MemoryCommand.Supersede command -> {
                        assertEquals(3, command.expectedRevision());
                        assertEquals(replacement, command.replacement());
                    }
                    case MemoryCommand.Transition command -> assertEquals(3, command.expectedRevision());
                    case MemoryCommand.Create ignored -> throw new AssertionError("unexpected Create");
                    case MemoryCommand.AcceptRevision ignored -> throw new AssertionError("unexpected AcceptRevision");
                }
                assertEquals(Optional.of(current), fixture.current.fact);
                assertEquals(1, fixture.memory.submitCalls);
                assertEquals(0, fixture.log.appendCalls);
            }
        }
    }

    /** stale revision 不跳过替代事实的来源授权或当前操作权限。 */
    @Test
    void staleSupersedeStillRejectsSourceAndAccessPoliciesBeforeSubmit() {
        MemoryFact current = new FactBuilder("stale-denied").revision(4).build();
        MemoryFactDraft replacement = new DraftBuilder().derivedFrom(Optional.of(current.ref())).build();
        MemoryRuntime.MutationCall call = new MemoryRuntime.MutationCall(CONTEXT,
                new MemoryRuntime.Mutation.Supersede(new IdempotencyKey("stale-denied"),
                        current.ref(), 3, replacement), NOW);
        Fixture sourceDenied = new Fixture();
        sourceDenied.current.fact = Optional.of(current);
        sourceDenied.sources.decision = new MemorySourcePolicy.Decision.Denied(
                "memory_source_denied", SOURCE_AUDIT);
        MemoryRuntime.MutationResult.Stopped sourceStopped = assertInstanceOf(
                MemoryRuntime.MutationResult.Stopped.class, sourceDenied.runtime.mutate(call));
        assertEquals("memory_source_denied", sourceStopped.failure().reasonCode());
        assertEquals(Optional.of(SOURCE_AUDIT), sourceStopped.failure().auditRef());
        assertEquals(0, sourceDenied.memory.submitCalls);
        assertEquals(0, sourceDenied.log.appendCalls);

        Fixture accessDenied = new Fixture();
        accessDenied.current.fact = Optional.of(current);
        accessDenied.access.decision = new MemoryAccessPolicy.Decision.Denied(
                "memory_access_denied", ACCESS_AUDIT);
        MemoryRuntime.MutationResult.Stopped accessStopped = assertInstanceOf(
                MemoryRuntime.MutationResult.Stopped.class, accessDenied.runtime.mutate(call));
        assertEquals("memory_access_denied", accessStopped.failure().reasonCode());
        assertEquals(Optional.of(ACCESS_AUDIT), accessStopped.failure().auditRef());
        assertEquals(1, accessDenied.current.calls);
        assertEquals(current.ref(), accessDenied.current.lastRef);
        assertEquals(current.owner(), accessDenied.access.lastRequest.owner());
        assertEquals(0, accessDenied.memory.submitCalls);
        assertEquals(0, accessDenied.log.appendCalls);
        assertEquals(0, accessDenied.log.findCalls);
        assertEquals(0, accessDenied.types.calls);
        assertEquals(0, accessDenied.sources.calls);
        assertEquals(0, accessDenied.memory.submitCalls);
        assertEquals(0, accessDenied.log.appendCalls);
    }

    @Test
    void rejectsMissingOrDriftingAuthoritativeCurrentFactsBeforeSubmit() {
        MemoryFact expected = new FactBuilder("expected-current").revision(3).build();
        List<CurrentScenario> scenarios = List.of(
                new CurrentScenario("missing", "memory_current_fact_missing", fixture -> { }),
                new CurrentScenario("ref", "memory_current_fact_mismatch", fixture ->
                        fixture.current.fact = Optional.of(
                                new FactBuilder("different-current").revision(3).build())),
                new CurrentScenario("owner", "memory_fact_owner_mismatch", fixture -> {
                    fixture.current.fact = Optional.of(new FactBuilder("expected-current")
                            .revision(3).user(OTHER_USER).build());
                    fixture.log.sources.put(SOURCE_EVENT, sourceEvent(
                            SOURCE_EVENT, SessionOwner.user(SESSION, AGENT, OTHER_USER),
                            Optional.empty()));
                }),
                new CurrentScenario("scope", "memory_current_fact_mismatch", fixture ->
                        fixture.current.fact = Optional.of(new FactBuilder("expected-current")
                                .revision(3).scope(MemoryScope.SESSION)
                                .session(Optional.of(SESSION)).build())),
                new CurrentScenario("type", "memory_current_fact_mismatch", fixture ->
                        fixture.current.fact = Optional.of(new FactBuilder("expected-current")
                                .revision(3).type(OTHER_TYPE).build())),
                new CurrentScenario("source", "memory_source_event_mismatch", fixture -> {
                    fixture.current.fact = Optional.of(expected);
                    fixture.log.sources.put(SOURCE_EVENT, sourceEvent(
                            SOURCE_EVENT, SessionOwner.user(OTHER_SESSION, AGENT, USER),
                            Optional.empty()));
                }));

        for (CurrentScenario scenario : scenarios) {
            for (long expectedRevision : List.of(3L, 2L)) {
                Fixture fixture = new Fixture();
                scenario.arrange().accept(fixture);

                MemoryRuntime.MutationResult result = fixture.runtime.mutate(new MemoryRuntime.MutationCall(
                        CONTEXT, new MemoryRuntime.Mutation.Transition(
                                new IdempotencyKey("current-" + scenario.name()), expected.ref(), expectedRevision,
                                expected.scope(), expected.type(), FactStatus.DECAYED, "decay"), NOW));

                assertEquals(scenario.reason(), stoppedReason(result), scenario.name());
                assertEquals(1, fixture.current.calls, scenario.name());
                assertEquals(CONTEXT, fixture.current.lastContext, scenario.name());
                assertEquals(expected.ref(), fixture.current.lastRef, scenario.name());
                assertEquals(0, fixture.memory.submitCalls, scenario.name());
                assertEquals(0, fixture.log.appendCalls, scenario.name());
            }
        }
    }

    @Test
    void enforcesEveryLegalAndIllegalFactStatusTransition() {
        Set<TransitionEdge> legal = Set.of(
                edge(FactStatus.ACTIVE, FactStatus.DECAYED),
                edge(FactStatus.ACTIVE, FactStatus.PENDING_CONFIRM),
                edge(FactStatus.ACTIVE, FactStatus.ARCHIVED),
                edge(FactStatus.ACTIVE, FactStatus.FORGOTTEN),
                edge(FactStatus.DECAYED, FactStatus.ACTIVE),
                edge(FactStatus.PENDING_CONFIRM, FactStatus.ACTIVE),
                edge(FactStatus.PENDING_CONFIRM, FactStatus.REJECTED),
                edge(FactStatus.PENDING_CONFIRM, FactStatus.FORGOTTEN),
                edge(FactStatus.FORGOTTEN, FactStatus.ACTIVE));

        for (TransitionEdge transition : legal) {
            Fixture fixture = new Fixture();
            MemoryFact current = new FactBuilder("transition-fact")
                    .revision(3).status(transition.source()).build();
            fixture.current.fact = Optional.of(current);
            fixture.memory.mutationResult = new MemoryMutationResult.Applied(
                    copyForTransition(current, 4, transition.target()), Optional.of(current), false);

            MemoryRuntime.MutationResult result = fixture.runtime.mutate(transitionCall(
                    current, transition.target(), "legal-" + transition));

            assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, result,
                    transition.toString());
            MemoryCommand.Transition command = assertInstanceOf(
                    MemoryCommand.Transition.class, fixture.memory.lastCommand);
            assertEquals(3, command.expectedRevision(), transition.toString());
            assertEquals(transition.target(), command.target(), transition.toString());
            assertEquals(1, fixture.memory.submitCalls, transition.toString());
            assertEquals(1, fixture.log.appendCalls, transition.toString());
        }

        for (FactStatus source : FactStatus.values()) {
            for (FactStatus target : FactStatus.values()) {
                TransitionEdge transition = edge(source, target);
                if (legal.contains(transition)) {
                    continue;
                }
                Fixture fixture = new Fixture();
                MemoryFact current = new FactBuilder("illegal-transition")
                        .revision(3).status(source).build();
                fixture.current.fact = Optional.of(current);

                MemoryRuntime.MutationResult result = fixture.runtime.mutate(transitionCall(
                        current, target, "illegal-" + source + "-" + target));

                assertEquals("memory_transition_not_allowed", stoppedReason(result),
                        transition.toString());
                assertEquals(0, fixture.memory.submitCalls, transition.toString());
                assertEquals(0, fixture.log.appendCalls, transition.toString());
            }
        }
    }

    @Test
    void returnsAnExactCasConflictOnceWithoutAppendingAnAuditEvent() {
        Fixture fixture = new Fixture();
        MemoryFact current = new FactBuilder("conflicted-fact").revision(3).build();
        fixture.current.fact = Optional.of(current);
        fixture.memory.mutationResult = new MemoryMutationResult.Conflict(
                current.ref(), 4, CONFLICT_AUDIT);

        MemoryRuntime.MutationResult.Conflict conflict = assertInstanceOf(
                MemoryRuntime.MutationResult.Conflict.class,
                fixture.runtime.mutate(transitionCall(
                        current, FactStatus.DECAYED, "conflict")));

        assertEquals(current.ref(), conflict.fact());
        assertEquals(4, conflict.currentRevision());
        assertEquals(CONFLICT_AUDIT, conflict.auditRef());
        assertEquals(1, fixture.memory.submitCalls);
        assertEquals(0, fixture.log.appendCalls);
    }

    @Test
    void rejectsMemoryAppliedAndConflictAcknowledgementDrift() {
        Fixture appliedDrift = new Fixture();
        MemoryFactDraft draft = new DraftBuilder().build();
        MemoryFact wrong = new FactBuilder("wrong-created-fact")
                .predicate("different-predicate").build();
        appliedDrift.memory.mutationResult = new MemoryMutationResult.Applied(wrong, Optional.empty(), false);

        MemoryRuntime.MutationResult.Uncertain uncertain = assertInstanceOf(
                MemoryRuntime.MutationResult.Uncertain.class,
                appliedDrift.runtime.mutate(mutationCall("bad-applied", draft)));
        assertEquals("memory_mutation_ack_mismatch", uncertain.failure().reasonCode());
        assertEquals(0, appliedDrift.log.appendCalls);

        Fixture conflictDrift = new Fixture();
        MemoryFact current = new FactBuilder("expected-fact").revision(2).build();
        conflictDrift.current.fact = Optional.of(current);
        conflictDrift.memory.mutationResult = new MemoryMutationResult.Conflict(
                new MemoryFactRef(TENANT, "different-fact"), 3, CONFLICT_AUDIT);

        MemoryRuntime.MutationResult.Uncertain badConflict = assertInstanceOf(
                MemoryRuntime.MutationResult.Uncertain.class,
                conflictDrift.runtime.mutate(transitionCall(
                        current, FactStatus.DECAYED, "bad-conflict")));
        assertEquals("memory_conflict_ack_mismatch", badConflict.failure().reasonCode());
        assertEquals(1, conflictDrift.memory.submitCalls);
        assertEquals(0, conflictDrift.log.appendCalls);
    }

    @Test
    void rejectsTransitionAppliedAcknowledgementIdentityRevisionAndStatusDrift() {
        List<AppliedScenario> scenarios = List.of(
                new AppliedScenario("ref", current -> copyForTransition(
                        current, new MemoryFactRef(TENANT, "different-applied-fact"),
                        current.revision() + 1, FactStatus.DECAYED)),
                new AppliedScenario("revision", current -> copyForTransition(
                        current, current.ref(), current.revision() + 2, FactStatus.DECAYED)),
                new AppliedScenario("status", current -> copyForTransition(
                        current, current.ref(), current.revision() + 1, FactStatus.ARCHIVED)));

        for (AppliedScenario scenario : scenarios) {
            Fixture fixture = new Fixture();
            MemoryFact current = new FactBuilder("transition-ack-fact")
                    .revision(3).build();
            fixture.current.fact = Optional.of(current);
            fixture.memory.mutationResult = new MemoryMutationResult.Applied(
                    scenario.applied().apply(current), Optional.of(current), false);

            MemoryRuntime.MutationResult.Uncertain result = assertInstanceOf(
                    MemoryRuntime.MutationResult.Uncertain.class,
                    fixture.runtime.mutate(transitionCall(
                            current, FactStatus.DECAYED, "applied-" + scenario.name())),
                    scenario.name());

            assertEquals("memory_mutation_ack_mismatch",
                    result.failure().reasonCode(), scenario.name());
            assertEquals(1, fixture.memory.submitCalls, scenario.name());
            assertEquals(0, fixture.log.appendCalls, scenario.name());
        }
    }

    @Test
    void neverRecordsAnAppliedMutationFromBadOrConflictingWrittenEventAck() {
        Map<LogMode, String> scenarios = Map.of(
                LogMode.BAD_ACK, "memory_session_log_ack_mismatch",
                LogMode.CONFLICT, "memory_session_event_conflict");

        for (Map.Entry<LogMode, String> scenario : scenarios.entrySet()) {
            Fixture fixture = new Fixture();
            MemoryFactDraft draft = new DraftBuilder().build();
            fixture.memory.mutationResult = new MemoryMutationResult.Applied(
                    factFromDraft(draft, "written-ack-fact"), Optional.empty(), false);
            fixture.log.mode = scenario.getKey();

            MemoryRuntime.MutationResult result = fixture.runtime.mutate(
                    mutationCall("written-ack-" + scenario.getKey(), draft));

            MemoryRuntime.MutationResult.Pending pending = assertInstanceOf(
                    MemoryRuntime.MutationResult.Pending.class, result, scenario.getKey().name());
            assertEquals(scenario.getValue(), pending.failure().reasonCode());
            assertFalse(result instanceof MemoryRuntime.MutationResult.Recorded);
            assertEquals(1, fixture.memory.submitCalls);
            assertEquals(1, fixture.log.appendCalls);
            if (scenario.getKey() == LogMode.CONFLICT) {
                assertEquals(Optional.of(CONFLICT_AUDIT), pending.failure().auditRef());
            }
        }
    }

    @Test
    void deniesEveryOperationWithTheExactAccessRequestAndNoDownstreamCall() {
        Fixture query = deniedFixture();
        MemoryRuntime.RetrievalResult.Stopped queryDenied = assertInstanceOf(
                MemoryRuntime.RetrievalResult.Stopped.class,
                query.runtime.retrieve(new MemoryRuntime.RetrievalCall(CONTEXT, query.query)));
        assertAccessDenial(query, queryDenied.failure(), new MemoryAccessPolicy.Request(
                CONTEXT, query.query.owner(), MemoryAccessPolicy.Operation.QUERY,
                query.query.scopes(), query.query.types(), Optional.empty()));

        Fixture create = deniedFixture();
        MemoryFactDraft draft = new DraftBuilder().build();
        MemoryRuntime.MutationResult.Stopped createDenied = assertInstanceOf(
                MemoryRuntime.MutationResult.Stopped.class,
                create.runtime.mutate(mutationCall("denied-create", draft)));
        assertAccessDenial(create, createDenied.failure(), new MemoryAccessPolicy.Request(
                CONTEXT, draft.owner(), MemoryAccessPolicy.Operation.CREATE,
                Set.of(draft.scope()), Set.of(draft.type()), Optional.empty()));

        MemoryFact current = new FactBuilder("denied-current").revision(3).build();
        MemoryFactDraft replacement = new DraftBuilder()
                .derivedFrom(Optional.of(current.ref())).build();
        Fixture supersede = deniedFixture();
        supersede.current.fact = Optional.of(current);
        MemoryRuntime.MutationResult.Stopped supersedeDenied = assertInstanceOf(
                MemoryRuntime.MutationResult.Stopped.class,
                supersede.runtime.mutate(new MemoryRuntime.MutationCall(
                        CONTEXT, new MemoryRuntime.Mutation.Supersede(
                                new IdempotencyKey("denied-supersede"),
                                current.ref(), current.revision(), replacement), NOW)));
        assertAccessDenial(supersede, supersedeDenied.failure(),
                new MemoryAccessPolicy.Request(
                        CONTEXT, current.owner(), MemoryAccessPolicy.Operation.SUPERSEDE,
                        Set.of(replacement.scope()), Set.of(replacement.type()),
                        Optional.of(current.ref())));

        Fixture transition = deniedFixture();
        transition.current.fact = Optional.of(current);
        MemoryRuntime.MutationResult.Stopped transitionDenied = assertInstanceOf(
                MemoryRuntime.MutationResult.Stopped.class,
                transition.runtime.mutate(transitionCall(
                        current, FactStatus.DECAYED, "denied-transition")));
        assertAccessDenial(transition, transitionDenied.failure(),
                new MemoryAccessPolicy.Request(
                        CONTEXT, current.owner(), MemoryAccessPolicy.Operation.TRANSITION,
                        Set.of(current.scope()), Set.of(current.type()),
                        Optional.of(current.ref())));
    }

    @Test
    void failsClosedOnAccessPolicyAndLiveControlFailures() {
        Fixture denied = new Fixture();
        denied.access.decision = new MemoryAccessPolicy.Decision.Denied(
                "memory_access_denied", ACCESS_AUDIT);
        MemoryRuntime.RetrievalResult deniedResult = denied.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, denied.query));
        MemoryRuntime.RetrievalResult.Stopped stopped = assertInstanceOf(
                MemoryRuntime.RetrievalResult.Stopped.class, deniedResult);
        assertEquals("memory_access_denied", stopped.failure().reasonCode());
        assertEquals(Optional.of(ACCESS_AUDIT), stopped.failure().auditRef());
        assertEquals(0, denied.memory.queryCalls);

        Fixture policyFailure = new Fixture();
        policyFailure.access.failure = new IllegalStateException("policy unavailable");
        assertEquals("memory_access_policy_failed", stoppedReason(
                policyFailure.runtime.retrieve(new MemoryRuntime.RetrievalCall(
                        CONTEXT, policyFailure.query))));
        assertEquals(0, policyFailure.memory.queryCalls);

        Fixture cancelled = new Fixture();
        PortCallContext cancelledContext = withControl(
                new InvocationControl(NOW.plusSeconds(30), () -> true));
        assertEquals("memory_call_cancelled", stoppedReason(cancelled.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(cancelledContext, cancelled.query))));
        assertEquals(0, cancelled.access.calls);
        assertEquals(0, cancelled.memory.queryCalls);

        Fixture expired = new Fixture();
        PortCallContext expiredContext = withControl(new InvocationControl(NOW, () -> false));
        assertEquals("memory_call_deadline_reached", stoppedReason(expired.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(expiredContext, expired.query))));
        assertEquals(0, expired.access.calls);
        assertEquals(0, expired.memory.queryCalls);
    }

    @Test
    void rejectsOversizedRetrievalAndMutationInputsBeforeCallingMemoryPort() {
        Fixture retrieval = new Fixture();
        MemoryQuery original = retrieval.query;
        MemoryQuery oversizedQuery = new MemoryQuery(original.queryId(),
                original.owner(),
                original.subjectEndUserId(),
                "x".repeat(MemoryEventCodec.LIMITS.maxCodeUnits()),
                Optional.empty(),
                original.scopes(),
                original.types(),
                original.profile(),
                original.asOf(),
                original.retrievalPolicy(),
                original.maxItems(),
                original.tokenBudget(),
                original.characterBudget(),
                original.requireProvenance());

        assertEquals("memory_event_encoding_rejected", stoppedReason(retrieval.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, oversizedQuery))));
        assertEquals(0, retrieval.memory.queryCalls);
        assertEquals(0, retrieval.log.appendCalls);

        String oversizedJson = "\"" + "x".repeat(MemoryEventCodec.LIMITS.maxCodeUnits()) + "\"";
        Fixture returned = new Fixture();
        returned.respond(List.of(new FactBuilder("oversized-candidate")
                .payload(new TypedPayload(PAYLOAD.schemaId(), oversizedJson))
                .build()));

        assertEquals("memory_event_encoding_rejected", stoppedReason(returned.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, returned.query))));
        assertEquals(1, returned.memory.queryCalls);
        assertEquals(0, returned.log.appendCalls);

        Fixture mutation = new Fixture();
        MemoryFactDraft oversizedDraft = new DraftBuilder()
                .payload(new TypedPayload(PAYLOAD.schemaId(), oversizedJson))
                .build();

        assertEquals("memory_event_encoding_rejected", stoppedReason(mutation.runtime.mutate(
                mutationCall("oversized-mutation", oversizedDraft))));
        assertEquals(0, mutation.memory.submitCalls);
        assertEquals(0, mutation.sources.calls);
        assertEquals(0, mutation.log.findCalls);
        assertEquals(0, mutation.log.appendCalls);
    }

    @Test
    void turnsOversizedAppliedFactIntoAStableUncertainResult() {
        Fixture fixture = new Fixture();
        MemoryFact current = new FactBuilder("bounded-current").revision(3).build();
        fixture.current.fact = Optional.of(current);
        MemoryFact oversizedApplied = new FactBuilder(current.ref().factId())
                .revision(current.revision() + 1)
                .supersededBy(Optional.of(new MemoryFactRef(
                        TENANT, "x".repeat(MemoryEventCodec.LIMITS.maxCodeUnits()))))
                .status(FactStatus.ARCHIVED)
                .build();
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(oversizedApplied, Optional.empty(), false);

        MemoryRuntime.MutationResult.Uncertain result = assertInstanceOf(
                MemoryRuntime.MutationResult.Uncertain.class,
                fixture.runtime.mutate(transitionCall(
                        current, FactStatus.ARCHIVED, "oversized-applied")));

        assertEquals("memory_event_encoding_rejected", result.failure().reasonCode());
        assertEquals(1, fixture.memory.submitCalls);
        assertEquals(0, fixture.log.appendCalls);
    }

    private static MemoryRuntime.MutationCall mutationCall(String key, MemoryFactDraft draft) {
        return new MemoryRuntime.MutationCall(CONTEXT,
                new MemoryRuntime.Mutation.Create(new IdempotencyKey(key), draft), NOW);
    }

    private static MemoryRuntime.MutationCall transitionCall(
            MemoryFact current, FactStatus target, String key) {
        return new MemoryRuntime.MutationCall(CONTEXT,
                new MemoryRuntime.Mutation.Transition(
                        new IdempotencyKey(key), current.ref(), current.revision(),
                        current.scope(), current.type(), target, "test-transition"), NOW);
    }

    @Test
    void replayUsesOriginalBeforeAndCommittedEventDespiteAdvancedHeadAndNewAudit() {
        Fixture fixture = new Fixture();
        MemoryFact before = new FactBuilder("replayed-transition").revision(3).build();
        MemoryFact after = copyForTransition(before, 4, FactStatus.FORGOTTEN);
        fixture.current.fact = Optional.of(before);
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(after, Optional.of(before), false);
        MemoryRuntime.MutationCall call = transitionCall(before, FactStatus.FORGOTTEN, "stable-transition");
        var first = assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, fixture.runtime.mutate(call));
        fixture.current.fact = Optional.of(after);
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(after, Optional.of(before), true);
        fixture.access.decision = new MemoryAccessPolicy.Decision.Allowed(new ResultRef("new-current-audit"));
        var replay = assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, fixture.runtime.mutate(
                new MemoryRuntime.MutationCall(call.context(), call.mutation(), call.occurredAt().plusSeconds(1))));
        assertEquals(first.event(), replay.event());
        assertEquals(after, replay.fact());
        assertEquals(1, fixture.log.appendCalls);
        assertEquals(2, fixture.memory.submitCalls);
        fixture.access.decision = new MemoryAccessPolicy.Decision.Denied("revoked-now", ACCESS_AUDIT);
        assertInstanceOf(MemoryRuntime.MutationResult.Stopped.class, fixture.runtime.mutate(call));
        assertEquals(2, fixture.memory.submitCalls);
    }

    @Test
    void reconstructionAfterMemoryCommitWithoutLogUsesAuthoritativePriorRevision() {
        Fixture recovered = new Fixture();
        MemoryFact before = new FactBuilder("reconstructed-transition").revision(3).build();
        MemoryFact after = copyForTransition(before, 4, FactStatus.FORGOTTEN);
        recovered.current.fact = Optional.of(after);
        recovered.memory.mutationResult = new MemoryMutationResult.Applied(after, Optional.of(before), true);
        var result = assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class,
                recovered.runtime.mutate(transitionCall(before, FactStatus.FORGOTTEN, "commit-before-crash")));
        assertEquals(after, result.fact());
        assertEquals(1, recovered.memory.submitCalls);
        assertEquals(1, recovered.log.appendCalls);
        assertEquals(MemoryEventCodec.writtenPayload(
                new MemoryRuntime.Mutation.Transition(new IdempotencyKey("commit-before-crash"), before.ref(),
                        before.revision(), before.scope(), before.type(), FactStatus.FORGOTTEN, "test-transition"),
                Optional.of(before), after, ACCESS_AUDIT, Optional.empty()), result.event().payload());
    }

    @Test
    void frozenContentRefusesCurrentForgetRevocationAndMissingGovernanceWithoutResearching() {
        Fixture fixture = new Fixture();
        MemoryRuntime.RetrievalReceipt receipt = recorded(fixture.runtime.retrieve(new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query)));
        MemoryFact frozen = fixture.current.available.get(new MemoryFactRef(TENANT, "fact-1"));
        fixture.current.fact = Optional.of(copyForTransition(frozen, 2, FactStatus.FORGOTTEN));
        assertEquals("memory_frozen_fact_no_longer_usable", assertThrows(IllegalStateException.class, receipt::candidateFacts).getMessage());
        fixture.current.fact = Optional.of(frozen);
        fixture.access.decision = new MemoryAccessPolicy.Decision.Denied("revoked-now", ACCESS_AUDIT);
        assertEquals("revoked-now", assertThrows(IllegalStateException.class, receipt::candidateFacts).getMessage());
        fixture.access.decision = new MemoryAccessPolicy.Decision.Allowed(ACCESS_AUDIT);
        fixture.current.unavailable = true;
        assertEquals("memory_frozen_fact_unavailable", assertThrows(IllegalStateException.class, receipt::candidateFacts).getMessage());
        assertEquals(1, fixture.memory.queryCalls);
        assertEquals(0, fixture.memory.submitCalls);
        assertEquals(1, fixture.log.appendCalls);
    }

    @Test
    void currentActiveRevisionDoesNotReplaceFrozenFactOrDiagnostics() {
        Fixture fixture = new Fixture();
        MemoryRuntime.RetrievalReceipt receipt = recorded(fixture.runtime.retrieve(new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query)));
        MemoryFact original = fixture.current.available.get(new MemoryFactRef(TENANT, "fact-1"));
        var diagnostics = receipt.diagnostics();
        fixture.current.fact = Optional.of(copyForTransition(original, 2, FactStatus.ACTIVE));
        MemoryRuntime.RetrievalReceipt restored = fixture.runtime.findLatestRetrieval(CONTEXT, fixture.query.queryId()).orElseThrow();
        assertEquals(List.of(original), restored.candidateFacts());
        assertEquals(1, restored.candidateFacts().getFirst().revision());
        assertEquals(diagnostics, restored.diagnostics());
        assertEquals(1, fixture.memory.queryCalls);
    }

    @Test
    void supersessionKeepsOriginalFrozenBodyUntilTheCurrentSuccessorIsForgotten() {
        Fixture fixture = new Fixture();
        MemoryRuntime.RetrievalReceipt receipt = recorded(fixture.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query)));
        MemoryFact original = receipt.candidateFacts().getFirst();
        var diagnostics = receipt.diagnostics();
        MemoryFact successor = new FactBuilder("corrected-preference")
                .payload(new TypedPayload(PAYLOAD.schemaId(), "{\"value\":\"corrected preference\"}"))
                .derivedFrom(Optional.of(original.ref())).build();
        MemoryFact latest = new FactBuilder("latest-preference")
                .payload(new TypedPayload(PAYLOAD.schemaId(), "{\"value\":\"latest preference\"}"))
                .derivedFrom(Optional.of(successor.ref())).build();
        fixture.current.available.put(original.ref(), new FactBuilder(original.ref().factId()).revision(2)
                .status(FactStatus.ARCHIVED).validTo(Optional.of(NOW.minusSeconds(2)))
                .supersededBy(Optional.of(successor.ref())).build());
        fixture.current.available.put(successor.ref(), new FactBuilder(successor.ref().factId()).revision(2)
                .status(FactStatus.ARCHIVED).validTo(Optional.of(NOW.minusSeconds(1)))
                .derivedFrom(Optional.of(original.ref())).supersededBy(Optional.of(latest.ref())).build());
        fixture.current.available.put(latest.ref(), latest);

        var restored = fixture.runtime.replayRetrieval(CONTEXT, receipt.committedEvent());
        assertEquals(List.of(original), restored.candidateFacts());
        assertEquals(diagnostics, restored.diagnostics());
        assertEquals(original.object(), restored.candidateFacts().getFirst().object());
        fixture.current.available.put(latest.ref(), copyForTransition(latest, 2, FactStatus.FORGOTTEN));
        assertEquals("memory_frozen_fact_no_longer_usable", assertThrows(IllegalStateException.class,
                restored::candidateFacts).getMessage());
        assertEquals("memory_frozen_fact_no_longer_usable", assertThrows(IllegalStateException.class,
                () -> fixture.runtime.replayRetrieval(CONTEXT, receipt.committedEvent())).getMessage());
        assertEquals(1, fixture.memory.queryCalls);
        assertEquals(0, fixture.memory.submitCalls);
        assertEquals(1, fixture.log.appendCalls);
    }

    @Test
    void validatesAlreadyInjectedRetrievalsThroughBorrowedCurrentReaderWithoutSearching() {
        Fixture fixture = new Fixture();
        MemoryRuntime.RetrievalReceipt candidate = recorded(fixture.runtime.retrieve(
                new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query)));
        MemoryFact original = candidate.candidateFacts().getFirst();
        var selected = recorded(fixture.runtime.advance(candidate, MemoryRuntime.Phase.SELECTED,
                new MemoryRetrievalTrace(fixture.query.queryId(), List.of("fact-1"), List.of("fact-1"),
                        List.of(), List.of()), EVALUATED.plusSeconds(1)));
        var injected = recorded(fixture.runtime.advance(selected, MemoryRuntime.Phase.INJECTED,
                new MemoryRetrievalTrace(fixture.query.queryId(), List.of("fact-1"), List.of("fact-1"),
                        List.of("fact-1"), List.of()), EVALUATED.plusSeconds(2)));
        var injectedFacts = injected.injectedFacts();
        MemoryFact successor = new FactBuilder("injected-successor").derivedFrom(Optional.of(original.ref())).build();
        var current = new HashMap<MemoryFactRef, MemoryFact>();
        current.put(original.ref(), new FactBuilder(original.ref().factId()).revision(2).status(FactStatus.ARCHIVED)
                .validTo(Optional.of(NOW.minusSeconds(1))).supersededBy(Optional.of(successor.ref())).build());
        current.put(successor.ref(), successor);
        var reads = new ArrayList<MemoryFactRef>();
        MemoryRuntime.CurrentFactBatchReader reader = batch -> batch.reads().stream().map(read -> {
            assertEquals(CONTEXT, read.context());
            reads.add(read.fact());
            return Optional.ofNullable(current.get(read.fact()));
        }).toList();
        int batchCalls = fixture.memory.currentBatchCalls;
        int typeCalls = fixture.types.calls;
        int sourceCalls = fixture.sources.calls;
        int sourceReads = fixture.log.findCalls;

        fixture.runtime.requireCurrentInjectedRetrievals(CONTEXT, reader);
        assertEquals(List.of(original.ref(), successor.ref()), reads);
        assertEquals(List.of(original), injectedFacts);
        current.put(successor.ref(), copyForTransition(successor, 2, FactStatus.FORGOTTEN));
        assertEquals("memory_frozen_fact_no_longer_usable", assertThrows(IllegalStateException.class,
                () -> fixture.runtime.requireCurrentInjectedRetrievals(CONTEXT, reader)).getMessage());
        fixture.access.decision = new MemoryAccessPolicy.Decision.Denied("revoked-at-dispatch", ACCESS_AUDIT);
        int readsBeforeRevocation = reads.size();
        assertEquals("revoked-at-dispatch", assertThrows(IllegalStateException.class,
                () -> fixture.runtime.requireCurrentInjectedRetrievals(CONTEXT, reader)).getMessage());
        assertEquals(readsBeforeRevocation, reads.size());
        assertEquals(batchCalls, fixture.memory.currentBatchCalls);
        assertEquals(typeCalls, fixture.types.calls);
        assertEquals(sourceCalls, fixture.sources.calls);
        assertEquals(sourceReads, fixture.log.findCalls);
        assertEquals(1, fixture.memory.queryCalls);
        assertEquals(0, fixture.memory.submitCalls);
        assertEquals(3, fixture.log.appendCalls);
    }

    @Test
    void frozenFactExpiresBeforeActualUseAndCannotBeSilentlyReselected() {
        Fixture fixture = new Fixture();
        fixture.respond(List.of(new FactBuilder("expires-now").expiresAt(Optional.of(NOW)).build()));
        MemoryRuntime.RetrievalReceipt receipt = recorded(fixture.runtime.retrieve(new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query)));
        assertEquals("memory_frozen_fact_no_longer_usable", assertThrows(IllegalStateException.class, receipt::candidateFacts).getMessage());
        assertEquals(1, fixture.memory.queryCalls);
        assertEquals(1, fixture.log.appendCalls);
    }

    @Test
    void currentGovernanceBatchRejectsMissingReorderedAndFailedResponsesWithoutResearching() {
        Fixture fixture = new Fixture();
        MemoryFact first = new FactBuilder("batch-first").build();
        MemoryFact second = new FactBuilder("batch-second").build();
        fixture.respond(List.of(first, second));
        MemoryRuntime.RetrievalReceipt receipt = recorded(fixture.runtime.retrieve(new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query)));
        fixture.memory.batchResponder = batch -> List.of();
        assertEquals("memory_current_governance_batch_mismatch", assertThrows(IllegalStateException.class, receipt::candidateFacts).getMessage());
        fixture.memory.batchResponder = batch -> List.of(Optional.of(second), Optional.of(first));
        assertEquals("memory_frozen_fact_no_longer_usable", assertThrows(IllegalStateException.class, receipt::candidateFacts).getMessage());
        fixture.memory.batchResponder = batch -> { throw new IllegalStateException("database unavailable"); };
        assertEquals("memory_current_governance_unavailable", assertThrows(IllegalStateException.class, receipt::candidateFacts).getMessage());
        assertEquals(3, fixture.memory.currentBatchCalls);
        assertEquals(List.of(first.ref(), second.ref()), fixture.memory.lastCurrentBatch.reads().stream().map(MemoryPort.CurrentFactRead::fact).toList());
        assertEquals(0, fixture.current.calls);
        assertEquals(1, fixture.memory.queryCalls);
        assertEquals(0, fixture.memory.submitCalls);
        assertEquals(1, fixture.log.appendCalls);
    }

    @Test
    void acceptsStoredRevisionWithOriginalProposalSourcesAndSupersedeAuthorization() {
        Fixture fixture = new Fixture();
        MemoryFact current = new FactBuilder("accept-current").revision(3).build();
        EventId source = new EventId("accept-proposal-source");
        MemoryFact proposal = new FactBuilder("accept-proposal").revision(2).status(FactStatus.PENDING_CONFIRM)
                .derivedFrom(Optional.of(current.ref())).sourceEvents(List.of(source)).predicate("corrected").build();
        configureAcceptance(fixture, current, proposal);
        fixture.log.sources.put(source, sourceEvent(source, OWNER, Optional.empty()));
        MemoryFact activated = copyForTransition(proposal, 3, FactStatus.ACTIVE);
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(activated, Optional.of(current), false);
        var result = assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, fixture.runtime.mutate(acceptanceCall(current, proposal, "accept-operation")));
        var command = assertInstanceOf(MemoryCommand.AcceptRevision.class, fixture.memory.lastCommand);
        assertEquals(current.ref(), command.current());
        assertEquals(proposal.ref(), command.proposal());
        assertEquals(3, command.expectedRevision());
        assertEquals(2, command.expectedProposalRevision());
        assertEquals(MemoryAccessPolicy.Operation.SUPERSEDE, fixture.access.lastRequest.operation());
        assertEquals(Optional.of(current.ref()), fixture.access.lastRequest.factRef());
        assertEquals(proposal.sourceSessionRef(), fixture.sources.lastRequest.draft().sourceSessionRef());
        assertEquals(proposal.sourceEventIds(), fixture.sources.lastRequest.draft().sourceEventIds());
        assertEquals(FactStatus.ACTIVE, fixture.sources.lastRequest.draft().initialStatus());
        assertEquals(List.of(source), fixture.sources.lastRequest.sources().stream().map(CommittedSessionEvent::eventId).toList());
        assertEquals(activated, result.fact());
        assertEquals(1, fixture.memory.currentBatchCalls);
        assertEquals(0, fixture.current.calls);
        assertEquals(1, fixture.memory.submitCalls);
        assertTrue(result.event().payload().canonicalJson().contains("\"operation\":\"ACCEPT_REVISION\""));
    }

    /** USER治理按原会话真实用户读取来源，保留原Agent且仅以提案自己的来源授权激活。 */
    @Test
    void acceptsUserRevisionAcrossAgentsUsingOriginalOwnersAndOnlyProposalEvidence() {
        Fixture fixture = new Fixture();
        fixture.types.add(registration(TYPE, Set.of(MemoryScope.USER), false, false));
        SessionOwner originalOwner = SessionOwner.user(OTHER_SESSION, OTHER_AGENT, USER);
        MemoryFact current = new FactBuilder("cross-agent-user-current").scope(MemoryScope.USER)
                .agent(Optional.empty()).sourceSession(OTHER_SESSION).revision(3).build();
        EventId proposalSource = new EventId("cross-agent-proposal-source");
        MemoryFact proposal = new FactBuilder("cross-agent-user-proposal").scope(MemoryScope.USER)
                .agent(Optional.empty()).sourceSession(OTHER_SESSION).revision(2).status(FactStatus.PENDING_CONFIRM)
                .derivedFrom(Optional.of(current.ref())).sourceEvents(List.of(proposalSource)).build();
        configureAcceptance(fixture, current, proposal);
        fixture.log.sources.put(SOURCE_EVENT, sourceEvent(SOURCE_EVENT, originalOwner, Optional.empty()));
        CommittedSessionEvent originalProposalSource = sourceEvent(proposalSource, originalOwner, Optional.empty());
        fixture.log.sources.put(proposalSource, originalProposalSource);
        MemoryFact activated = copyForTransition(proposal, 3, FactStatus.ACTIVE);
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(activated, Optional.of(current), false);

        var result = assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class,
                fixture.runtime.mutate(acceptanceCall(current, proposal, "cross-agent-user-accept")));

        assertEquals(activated, result.fact());
        assertEquals(List.of(originalProposalSource), fixture.sources.lastRequest.sources());
        assertEquals(OTHER_AGENT, fixture.sources.lastRequest.sources().getFirst().agentId());
        assertEquals(originalOwner, fixture.sources.lastRequest.sources().getFirst().owner());
        assertEquals(List.of(proposalSource), fixture.sources.lastRequest.draft().sourceEventIds());
        assertEquals(OTHER_SESSION, fixture.sources.lastRequest.draft().sourceSessionRef());
        assertFalse(fixture.sources.lastRequest.sources().stream().anyMatch(event -> event.eventId().equals(SOURCE_EVENT)));
        assertTrue(fixture.log.userSourceReads.stream().allMatch(read -> read.session().equals(OTHER_SESSION)
                && read.user().equals(USER)));
        assertTrue(fixture.log.findOwners.stream().noneMatch(readOwner -> readOwner.sessionRef().equals(OTHER_SESSION)),
                "不得借当前Agent构造历史USER来源owner");
        assertEquals(OWNER, result.event().owner());
        assertEquals(1, fixture.memory.submitCalls);
    }

    /** 宿主适配器即使返回敌对来源，Runtime仍重验tenant、原会话、真实用户及精确事件。 */
    @Test
    void crossAgentUserRevisionRejectsMismatchedSourceIdentityBeforeSubmit() {
        SessionOwner originalOwner = SessionOwner.user(OTHER_SESSION, OTHER_AGENT, USER);
        List<CommittedSessionEvent> hostile = List.of(
                sourceEvent(SOURCE_EVENT, SessionOwner.user(OTHER_SESSION, OTHER_AGENT, OTHER_USER), Optional.empty()),
                sourceEvent(SOURCE_EVENT, OWNER, Optional.empty()),
                sourceEvent(new EventId("wrong-original-event"), originalOwner, Optional.empty()),
                sourceEvent(SOURCE_EVENT, SessionOwner.user(OTHER_TENANT_SESSION, OTHER_AGENT, USER), Optional.empty()));
        for (CommittedSessionEvent returned : hostile) {
            Fixture fixture = new Fixture();
            fixture.types.add(registration(TYPE, Set.of(MemoryScope.USER), false, false));
            MemoryFact current = new FactBuilder("cross-agent-hostile-current").scope(MemoryScope.USER)
                    .agent(Optional.empty()).sourceSession(OTHER_SESSION).revision(3).build();
            MemoryFact proposal = new FactBuilder("cross-agent-hostile-proposal").scope(MemoryScope.USER)
                    .agent(Optional.empty()).status(FactStatus.PENDING_CONFIRM).derivedFrom(Optional.of(current.ref())).build();
            configureAcceptance(fixture, current, proposal);
            fixture.log.userSourceResponder = ignored -> Optional.of(returned);

            assertEquals("memory_source_event_mismatch", stoppedReason(fixture.runtime.mutate(
                    acceptanceCall(current, proposal, "reject-cross-agent-source"))));
            assertEquals(0, fixture.memory.submitCalls);
            assertEquals(0, fixture.log.appendCalls);
        }
    }

    /** 缺少原来源则拒绝；治理权限拒绝时也不得调用USER原来源读取接口。 */
    @Test
    void crossAgentUserRevisionRequiresOriginalSourceAndPriorGovernanceAuthorization() {
        MemoryFact current = new FactBuilder("cross-agent-missing-current").scope(MemoryScope.USER)
                .agent(Optional.empty()).sourceSession(OTHER_SESSION).revision(3).build();
        MemoryFact proposal = new FactBuilder("cross-agent-missing-proposal").scope(MemoryScope.USER)
                .agent(Optional.empty()).status(FactStatus.PENDING_CONFIRM).derivedFrom(Optional.of(current.ref())).build();
        Fixture missing = new Fixture();
        missing.types.add(registration(TYPE, Set.of(MemoryScope.USER), false, false));
        configureAcceptance(missing, current, proposal);
        assertEquals("memory_source_event_missing", stoppedReason(missing.runtime.mutate(
                acceptanceCall(current, proposal, "missing-original-user-source"))));
        assertEquals(0, missing.memory.submitCalls);
        assertEquals(0, missing.log.appendCalls);

        Fixture denied = deniedFixture();
        configureAcceptance(denied, current, proposal);
        assertEquals("memory_access_denied", stoppedReason(denied.runtime.mutate(
                acceptanceCall(current, proposal, "denied-original-user-source"))));
        assertTrue(denied.log.userSourceReads.isEmpty());
        assertEquals(0, denied.memory.submitCalls);
    }

    /** 已持久USER来源读取能力不能将别的Agent原事件升格为当前会话新草稿的证据。 */
    @Test
    void originalUserSourceCannotAuthorizeNewDraftEvidenceFromAnotherAgent() {
        Fixture fixture = new Fixture();
        fixture.types.add(registration(TYPE, Set.of(MemoryScope.USER), false, false));
        fixture.log.sources.put(SOURCE_EVENT, sourceEvent(SOURCE_EVENT,
                SessionOwner.user(OTHER_SESSION, OTHER_AGENT, USER), Optional.empty()));
        MemoryFactDraft draft = new DraftBuilder().scope(MemoryScope.USER).agent(Optional.empty()).build();

        assertEquals("memory_source_event_mismatch", stoppedReason(fixture.runtime.mutate(new MemoryRuntime.MutationCall(
                CONTEXT, new MemoryRuntime.Mutation.Create(new IdempotencyKey("reject-historical-new-source"), draft), NOW))));
        assertTrue(fixture.log.userSourceReads.isEmpty());
        assertEquals(0, fixture.memory.submitCalls);
        assertEquals(0, fixture.log.appendCalls);
    }

    @Test
    void acceptedRevisionRestoresOriginalReceiptAfterBothHeadsAdvanceAndAfterMissingLog() {
        Fixture fixture = new Fixture();
        MemoryFact current = new FactBuilder("accept-replay-current").revision(3).build();
        MemoryFact proposal = new FactBuilder("accept-replay-proposal").revision(2).status(FactStatus.PENDING_CONFIRM)
                .derivedFrom(Optional.of(current.ref())).predicate("corrected").build();
        configureAcceptance(fixture, current, proposal);
        MemoryFact activated = copyForTransition(proposal, 3, FactStatus.ACTIVE);
        var call = acceptanceCall(current, proposal, "accept-replay-operation");
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(activated, Optional.of(current), false);
        var first = assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, fixture.runtime.mutate(call));
        MemoryFact archived = copyForTransition(current, 4, FactStatus.ARCHIVED);
        configureAcceptance(fixture, archived, activated);
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(activated, Optional.of(current), true);
        fixture.access.decision = new MemoryAccessPolicy.Decision.Allowed(new ResultRef("new-accept-audit"));
        var replay = assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, fixture.runtime.mutate(call));
        assertEquals(first.event(), replay.event());
        assertEquals(1, fixture.log.appendCalls);
        Fixture recovered = new Fixture();
        configureAcceptance(recovered, archived, activated);
        recovered.memory.mutationResult = new MemoryMutationResult.Applied(activated, Optional.of(current), true);
        var repaired = assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, recovered.runtime.mutate(call));
        assertEquals(first.event().payload(), repaired.event().payload());
        assertEquals(1, recovered.log.appendCalls);
        assertEquals(activated, repaired.fact());
    }

    @Test
    void acceptRevisionRejectsOriginalSourceDenialAndInvalidPendingStateBeforeSubmit() {
        MemoryFact current = new FactBuilder("accept-denied-current").revision(3).build();
        MemoryFact proposal = new FactBuilder("accept-denied-proposal").revision(2).status(FactStatus.PENDING_CONFIRM)
                .derivedFrom(Optional.of(current.ref())).build();
        Fixture denied = new Fixture();
        configureAcceptance(denied, current, proposal);
        denied.sources.decision = new MemorySourcePolicy.Decision.Denied("memory_original_proposal_source_denied", SOURCE_AUDIT);
        assertEquals("memory_original_proposal_source_denied", stoppedReason(denied.runtime.mutate(acceptanceCall(current, proposal, "accept-denied"))));
        assertEquals(0, denied.memory.submitCalls);
        assertEquals(0, denied.log.appendCalls);
        Fixture invalid = new Fixture();
        MemoryFact rejected = copyForTransition(proposal, proposal.revision(), FactStatus.REJECTED);
        configureAcceptance(invalid, current, rejected);
        assertEquals("memory_accept_revision_source_mismatch", stoppedReason(invalid.runtime.mutate(acceptanceCall(current, rejected, "accept-invalid"))));
        assertEquals(0, invalid.memory.submitCalls);
        assertEquals(0, invalid.log.appendCalls);
        Fixture bypass = new Fixture();
        bypass.current.fact = Optional.of(proposal);
        assertEquals("memory_revision_acceptance_required", stoppedReason(bypass.runtime.mutate(
                transitionCall(proposal, FactStatus.ACTIVE, "single-sided-acceptance"))));
        assertEquals(0, bypass.memory.submitCalls);
        assertEquals(0, bypass.log.appendCalls);
    }

    @Test
    void acceptsProposalCasConflictReferenceAndRejectsWrongAppliedProposalRevision() {
        Fixture fixture = new Fixture();
        MemoryFact current = new FactBuilder("accept-conflict-current").revision(3).build();
        MemoryFact proposal = new FactBuilder("accept-conflict-proposal").revision(2).status(FactStatus.PENDING_CONFIRM)
                .derivedFrom(Optional.of(current.ref())).build();
        configureAcceptance(fixture, current, proposal);
        fixture.memory.mutationResult = new MemoryMutationResult.Conflict(proposal.ref(), 3, CONFLICT_AUDIT);
        var conflict = assertInstanceOf(MemoryRuntime.MutationResult.Conflict.class, fixture.runtime.mutate(acceptanceCall(current, proposal, "accept-conflict")));
        assertEquals(proposal.ref(), conflict.fact());
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(copyForTransition(proposal, 4, FactStatus.ACTIVE), Optional.of(current), false);
        var invalid = assertInstanceOf(MemoryRuntime.MutationResult.Uncertain.class, fixture.runtime.mutate(acceptanceCall(current, proposal, "accept-bad-ack")));
        assertEquals("memory_mutation_ack_mismatch", invalid.failure().reasonCode());
        assertEquals(0, fixture.log.appendCalls);
    }

    @Test
    void acceptRevisionUnknownSubmissionRetriesSameFrozenCommandAndBothVersions() {
        Fixture fixture = new Fixture();
        MemoryFact current = new FactBuilder("accept-unknown-current").revision(3).build();
        MemoryFact proposal = new FactBuilder("accept-unknown-proposal").revision(2).status(FactStatus.PENDING_CONFIRM)
                .derivedFrom(Optional.of(current.ref())).build();
        configureAcceptance(fixture, current, proposal);
        fixture.memory.submitFailure = new IllegalStateException("lost response");
        var pending = assertInstanceOf(MemoryRuntime.MutationResult.Uncertain.class, fixture.runtime.mutate(acceptanceCall(current, proposal, "accept-unknown")));
        MemoryCommand original = fixture.memory.lastCommand;
        fixture.memory.mutationResult = new MemoryMutationResult.Applied(copyForTransition(proposal, 3, FactStatus.ACTIVE), Optional.of(current), true);
        assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, fixture.runtime.retry(pending.pending()));
        assertSame(original, fixture.memory.commands.get(1));
        assertEquals(1, fixture.memory.currentBatchCalls);
        assertEquals(2, fixture.memory.submitCalls);
    }

    private static void configureAcceptance(Fixture fixture, MemoryFact current, MemoryFact proposal) {
        fixture.current.fact = Optional.empty();
        fixture.current.available.clear();
        fixture.current.available.put(current.ref(), current);
        fixture.current.available.put(proposal.ref(), proposal);
    }

    private static MemoryRuntime.MutationCall acceptanceCall(MemoryFact current, MemoryFact proposal, String key) {
        return new MemoryRuntime.MutationCall(CONTEXT, new MemoryRuntime.Mutation.AcceptRevision(new IdempotencyKey(key),
                current.ref(), current.revision(), proposal.ref(), proposal.revision(), current.scope(), current.type()), NOW);
    }

    private static Fixture deniedFixture() {
        Fixture fixture = new Fixture();
        fixture.access.decision = new MemoryAccessPolicy.Decision.Denied(
                "memory_access_denied", ACCESS_AUDIT);
        return fixture;
    }

    private static void assertAccessDenial(
            Fixture fixture,
            MemoryRuntime.Failure failure,
            MemoryAccessPolicy.Request expectedRequest) {
        assertEquals("memory_access_denied", failure.reasonCode());
        assertEquals(Optional.of(ACCESS_AUDIT), failure.auditRef());
        assertEquals(expectedRequest, fixture.access.lastRequest);
        assertEquals(1, fixture.access.calls);
        assertEquals(0, fixture.memory.queryCalls);
        assertEquals(0, fixture.memory.submitCalls);
        assertEquals(0, fixture.types.calls);
        assertEquals(0, fixture.sources.calls);
        assertEquals(0, fixture.log.findCalls);
        assertEquals(0, fixture.log.appendCalls);
        assertEquals(expectedRequest.factRef().isPresent() ? 1 : 0, fixture.current.calls);
    }

    private static void assertNoExternalCallsAfterAccess(Fixture fixture) {
        assertEquals(0, fixture.memory.currentBatchCalls);
        assertEquals(0, fixture.memory.queryCalls);
        assertEquals(0, fixture.memory.submitCalls);
        assertEquals(0, fixture.types.calls);
        assertEquals(0, fixture.sources.calls);
        assertEquals(0, fixture.log.findCalls);
        assertEquals(0, fixture.log.appendCalls);
        assertEquals(0, fixture.current.calls);
    }

    private static void assertNoLogOrMutationSideEffects(Fixture fixture, String message) {
        assertEquals(0, fixture.memory.submitCalls, message);
        assertEquals(0, fixture.sources.calls, message);
        assertEquals(0, fixture.log.findCalls, message);
        assertEquals(0, fixture.log.appendCalls, message);
        assertEquals(0, fixture.current.calls, message);
    }

    private static void assertNoExternalCalls(Fixture fixture) {
        assertNoExternalCallsAfterAccess(fixture);
        assertEquals(0, fixture.access.calls);
    }

    private static MemoryRuntime.RetrievalReceipt recorded(MemoryRuntime.RetrievalResult result) {
        return assertInstanceOf(MemoryRuntime.RetrievalResult.Recorded.class, result).receipt();
    }

    private static String stoppedReason(MemoryRuntime.RetrievalResult result) {
        return assertInstanceOf(MemoryRuntime.RetrievalResult.Stopped.class, result)
                .failure().reasonCode();
    }

    private static String stoppedReason(MemoryRuntime.MutationResult result) {
        return assertInstanceOf(MemoryRuntime.MutationResult.Stopped.class, result)
                .failure().reasonCode();
    }

    private static MemoryQueryOutcome retrieved(MemoryQueryResult result) {
        return new MemoryQueryOutcome.Retrieved(result);
    }

    private static MemoryQuery query(
            Set<MemoryScope> scopes,
            Set<MemoryTypeRef> types,
            MemoryQueryProfile profile) {
        Optional<SessionRef> session = scopes.contains(MemoryScope.SESSION)
                ? Optional.of(SESSION) : Optional.empty();
        return query(TENANT, USER, AGENT, session, scopes, types, profile);
    }

    private static MemoryQuery query(
            TenantId tenant,
            EndUserId user,
            AgentId agent,
            Optional<SessionRef> session,
            Set<MemoryScope> scopes,
            Set<MemoryTypeRef> types,
            MemoryQueryProfile profile) {
        return new MemoryQuery("query-1",
                testQueryOwner(tenant, user, agent, session),
                Optional.empty(),
                "remember user facts",
                Optional.empty(),
                scopes,
                types,
                profile,
                AS_OF,
                MemoryRetrievalPolicy.lexicalV1(),
                2,
                1_000,
                4_000,
                true);
    }

    private static PortCallContext context() {
        return contextFor(AGENT, SESSION);
    }

    private static PortCallContext contextFor(AgentId agent, SessionRef session) {
        SessionOwner owner = SessionOwner.user(session, agent, USER);
        RunIdentity run = new RunIdentity(owner, owner.target().requireAgent(), INPUT, new RunRef(TENANT, "run-1"), TRACE, new ResultRef("causation-identity-1"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), INPUT.value()));
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(TENANT, USER), owner,
                Set.of("runtime:memory"), 1, new ResultRef("verification-1"));
        return new PortCallContext(
                run, identity, new InvocationControl(NOW.plusSeconds(60), () -> false));
    }

    private static PortCallContext withControl(InvocationControl control) {
        return new PortCallContext(CONTEXT.runIdentity(), CONTEXT.identity(), control);
    }

    private static CommittedSessionEvent sourceEvent(
            EventId id, SessionOwner owner, Optional<ExecutionRef> executionRef) {
        return sourceEvent(id, owner, executionRef, "source.recorded",
                new TypedPayload("source-event", "{}"));
    }

    private static CommittedSessionEvent sourceEvent(
            EventId id,
            SessionOwner owner,
            Optional<ExecutionRef> executionRef,
            String eventType,
            TypedPayload payload) {
        TenantId tenant = owner.sessionRef().tenantId();
        return new CommittedSessionEvent(
                owner, INPUT, Optional.of(new RunRef(tenant, "source-run")),
                executionRef, Optional.empty(), Optional.empty(), id, 1,
                eventType, "1", TRACE, payload, CREATED);
    }

    private static CommittedSessionEvent copyEvent(
            CommittedSessionEvent event,
            InputId inputId,
            EventId eventId,
            TypedPayload payload) {
        return new CommittedSessionEvent(
                event.owner(), inputId, event.runRef(), event.executionRef(),
                event.actionRef(), event.delegationRef(), eventId,
                event.sessionSequence(), event.eventType(), event.eventVersion(),
                event.trace(), payload, event.occurredAt());
    }

    private static MemoryFact configureSource(
            Fixture fixture, SessionOwner sourceOwner, MemoryFact fact) {
        fixture.log.sources.put(SOURCE_EVENT, sourceEvent(
                SOURCE_EVENT, sourceOwner, fact.sourceExecutionRef()));
        return fact;
    }

    private static MemoryFact factFromDraft(MemoryFactDraft draft, String factId) {
        return new MemoryFact(new MemoryFactRef(draft.sourceSessionRef().tenantId(), factId),
                draft.owner(),
                draft.type(),
                draft.retentionClass(),
                draft.schemaVersion(),
                draft.subject(),
                draft.predicate(),
                draft.object(),
                draft.sourceEventIds(),
                draft.sourceSessionRef(),
                draft.sourceExecutionRef(),
                draft.exportPath(),
                draft.extractorVersion(),
                1,
                CREATED,
                draft.validFrom(),
                draft.validTo(),
                draft.expiresAt(),
                draft.confidence(),
                draft.importance(),
                draft.reviewRequired(),
                draft.derivedFrom(),
                Optional.empty(),
                0d,
                draft.initialStatus());
    }

    private static MemoryFact copyForTransition(
            MemoryFact current, long revision, FactStatus status) {
        return copyForTransition(current, current.ref(), revision, status);
    }

    private static MemoryFact copyForTransition(
            MemoryFact current,
            MemoryFactRef ref,
            long revision,
            FactStatus status) {
        return new MemoryFact(ref,
                current.owner(),
                current.type(),
                current.retentionClass(),
                current.schemaVersion(),
                current.subject(),
                current.predicate(),
                current.object(),
                current.sourceEventIds(),
                current.sourceSessionRef(),
                current.sourceExecutionRef(),
                current.exportPath(),
                current.extractorVersion(),
                revision,
                current.createdAt(),
                current.validFrom(),
                current.validTo(),
                current.expiresAt(),
                current.confidence(),
                current.importance(),
                current.reviewRequired(),
                current.derivedFrom(),
                current.supersededBy(),
                current.decayScore(),
                status);
    }

    private static MemorySourcePolicy.Decision sourceDecision(
            MemorySourcePolicy.Request request) {
        CommittedSessionEvent source = request.sources().getFirst();
        if (source.eventType().startsWith("callback.")) {
            return new MemorySourcePolicy.Decision.Denied(
                    "memory_callback_raw_source_denied", SOURCE_AUDIT);
        }
        if (source.eventType().contains("checkpoint")) {
            return new MemorySourcePolicy.Decision.Denied(
                    "memory_checkpoint_source_denied", SOURCE_AUDIT);
        }
        if (source.payload().schemaId().equals("workflow-result-data")
                && request.draft().exportPath().isEmpty()) {
            return new MemorySourcePolicy.Decision.Denied(
                    "memory_unexported_data_source_denied", SOURCE_AUDIT);
        }
        return new MemorySourcePolicy.Decision.Allowed(SOURCE_AUDIT);
    }

    private static NamedScenario scenario(String name, Consumer<Fixture> arrange) {
        return new NamedScenario(name, arrange);
    }

    private static TransitionEdge edge(FactStatus source, FactStatus target) {
        return new TransitionEdge(source, target);
    }

    private record NamedScenario(String name, Consumer<Fixture> arrange) { }

    private record DraftScenario(
            String name,
            String reason,
            Function<Fixture, MemoryFactDraft> draft) { }

    private record SourceScenario(
            String name,
            String eventType,
            TypedPayload payload,
            Optional<ExecutionRef> executionRef,
            String reason) { }

    private record CurrentScenario(
            String name,
            String reason,
            Consumer<Fixture> arrange) { }

    private record AppliedScenario(
            String name,
            Function<MemoryFact, MemoryFact> applied) { }

    private record TransitionEdge(FactStatus source, FactStatus target) { }

    /** 六十条跨作用域输入与独立身份集合，断言不从 Runtime 输出反推。 */
    private static List<MemoryFact> complexSixtyFacts() {
        List<MemoryFact> facts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            facts.add(new FactBuilder("shared-" + i).scope(MemoryScope.USER)
                    .agent(Optional.empty()).predicate("complex-preference-" + i).build());
            facts.add(new FactBuilder("agent-" + i).predicate("complex-preference-" + i).revision(i + 2).build());
            facts.add(new FactBuilder("session-" + i).scope(MemoryScope.SESSION)
                    .session(Optional.of(SESSION)).predicate("complex-episode-" + i).build());
        }
        return List.copyOf(facts);
    }

    /** 本夹具预算足以保留全部输入，不能靠截断掩盖授权或回放漂移。 */
    private static Fixture complexMemoryFixture() {
        Fixture fixture = new Fixture();
        fixture.types.add(registration(TYPE, Set.of(MemoryScope.USER, MemoryScope.USER_AGENT, MemoryScope.SESSION), false, false));
        fixture.query = new MemoryQuery("complex-sixty",
                testQueryOwner(TENANT, USER, AGENT, Optional.of(SESSION)),
                Optional.empty(),
                "recall scoped business preferences",
                Optional.empty(),
                Set.of(MemoryScope.USER, MemoryScope.USER_AGENT, MemoryScope.SESSION),
                Set.of(TYPE),
                MemoryQueryProfile.DEFAULT,
                AS_OF,
                MemoryRetrievalPolicy.lexicalV1(),
                100,
                20_000,
                80_000,
                true);
        fixture.respond(complexSixtyFacts());
        return fixture;
    }

    @Test
    void complexSixtyScopeOverlaySurvivesReconstructionWithCurrentGovernanceOnly() {
        Fixture original = complexMemoryFixture();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 20; i++) { expected.add("agent-" + i); expected.add("session-" + i); }
        var committed = recorded(original.runtime.retrieve(new MemoryRuntime.RetrievalCall(CONTEXT, original.query)));
        assertEquals(expected, committed.trace().candidateIds());
        assertEquals(60, complexSixtyFacts().size());
        assertEquals(20, complexSixtyFacts().stream().filter(f -> f.scope() == MemoryScope.USER).count());
        Fixture rebuilt = complexMemoryFixture();
        var replay = rebuilt.runtime.replayRetrieval(CONTEXT, committed.committedEvent());
        assertEquals(committed.candidateFacts(), replay.candidateFacts());
        assertEquals(expected, replay.trace().candidateIds());
        assertEquals(0, rebuilt.memory.queryCalls);
        assertEquals(0, rebuilt.memory.submitCalls);
        assertEquals(0, rebuilt.types.calls);
        assertEquals(0, rebuilt.sources.calls);
        assertEquals(0, rebuilt.log.findCalls);
        assertEquals(0, rebuilt.log.appendCalls);
        assertEquals(2, rebuilt.access.calls);
        assertEquals(0, rebuilt.current.calls);
        assertEquals(2, rebuilt.memory.currentBatchCalls);
        assertEquals(expected, rebuilt.memory.lastCurrentBatch.reads().stream().map(read -> read.fact().factId()).toList());
    }

    @Test
    void complexSixtyPoolRejectsOneTemporalStatusOrOwnerDriftBeforeWritingAnyTrace() {
        List<MemoryFact> hostile = List.of(
                new FactBuilder("foreign-user").user(OTHER_USER).build(),
                new FactBuilder("future").validFrom(AS_OF.plusNanos(1)).build(),
                new FactBuilder("expired").expiresAt(Optional.of(AS_OF)).build(),
                new FactBuilder("ended").validTo(Optional.of(AS_OF)).build(),
                new FactBuilder("forgotten").status(FactStatus.FORGOTTEN).build(),
                new FactBuilder("wrong-session").scope(MemoryScope.SESSION).session(Optional.of(OTHER_SESSION))
                        .sourceSession(OTHER_SESSION).build());
        for (MemoryFact invalid : hostile) {
            Fixture fixture = complexMemoryFixture();
            List<MemoryFact> candidates = new ArrayList<>(complexSixtyFacts());
            candidates.set(31, invalid);
            fixture.respond(candidates);
            assertEquals("memory_query_result_rejected", stoppedReason(fixture.runtime.retrieve(
                    new MemoryRuntime.RetrievalCall(CONTEXT, fixture.query))), invalid.ref().factId());
            assertEquals(1, fixture.memory.queryCalls);
            // 前十组合法候选允许核验来源；第31输入仍在其owner/时间/状态门禁拒绝。
            assertEquals(20, fixture.log.findCalls, invalid.ref().factId());
            assertEquals(java.util.Collections.nCopies(20, OWNER), fixture.log.findOwners,
                    "只能读取此前合法候选的当前owner来源: " + invalid.ref().factId());
            assertEquals(0, fixture.memory.submitCalls, invalid.ref().factId());
            assertEquals(0, fixture.sources.calls, invalid.ref().factId());
            assertEquals(0, fixture.log.appendCalls, invalid.ref().factId());
            assertEquals(0, fixture.current.calls, invalid.ref().factId());
        }
    }

    @Test
    void complexSixtyPoolKeepsTwentyIndependentCasTargetsAndFrozenUnknownSubmissions() {
        for (int i = 0; i < 20; i++) {
            MemoryFact current = complexSixtyFacts().get(i * 3 + 1);
            Fixture stale = complexMemoryFixture();
            stale.current.fact = Optional.of(current);
            stale.memory.mutationResult = new MemoryMutationResult.Conflict(
                    current.ref(), current.revision(), CONFLICT_AUDIT);
            MemoryFactDraft replacement = new DraftBuilder().predicate(current.predicate())
                    .derivedFrom(Optional.of(current.ref())).build();
            var rejected = stale.runtime.mutate(new MemoryRuntime.MutationCall(CONTEXT,
                    new MemoryRuntime.Mutation.Supersede(new IdempotencyKey("complex-stale-" + i),
                            current.ref(), current.revision() - 1, replacement), NOW));
            var conflict = assertInstanceOf(MemoryRuntime.MutationResult.Conflict.class, rejected);
            assertEquals(current.ref(), conflict.fact());
            assertEquals(current.revision(), conflict.currentRevision());
            assertEquals(CONFLICT_AUDIT, conflict.auditRef());
            assertEquals(current.revision() - 1,
                    assertInstanceOf(MemoryCommand.Supersede.class, stale.memory.lastCommand).expectedRevision());
            assertEquals(Optional.of(current), stale.current.fact);
            assertEquals(1, stale.memory.submitCalls);
            assertEquals(0, stale.log.appendCalls);
            Fixture lost = complexMemoryFixture();
            lost.current.fact = Optional.of(current);
            lost.memory.submitFailure = new IllegalStateException("lost mutation receipt");
            var uncertain = assertInstanceOf(MemoryRuntime.MutationResult.Uncertain.class,
                    lost.runtime.mutate(new MemoryRuntime.MutationCall(CONTEXT,
                            new MemoryRuntime.Mutation.Supersede(new IdempotencyKey("complex-exact-" + i),
                                    current.ref(), current.revision(), replacement), NOW)));
            MemoryCommand frozen = lost.memory.lastCommand;
            lost.memory.mutationResult = new MemoryMutationResult.Applied(factFromDraft(replacement, "complex-replacement-" + i), Optional.of(current), false);
            var recorded = assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class, lost.runtime.retry(uncertain.pending()));
            assertEquals(current.ref(), recorded.fact().derivedFrom().orElseThrow());
            assertSame(frozen, lost.memory.commands.get(1));
            assertEquals(2, lost.memory.submitCalls);
            assertEquals(1, lost.current.calls);
        }
    }

    private static final class Fixture {
        private final FakeMemoryPort memory = new FakeMemoryPort();
        private final FakeSessionLog log = new FakeSessionLog();
        private final FakeAccessPolicy access = new FakeAccessPolicy();
        private final FakeRegistry types = new FakeRegistry();
        private final FakeSourcePolicy sources = new FakeSourcePolicy();
        private final FakeCurrentFactSource current = new FakeCurrentFactSource();
        private final MemoryRuntime runtime;
        private MemoryQuery query = query(
                Set.of(MemoryScope.USER_AGENT), Set.of(TYPE), MemoryQueryProfile.DEFAULT);

        private Fixture() {
            memory.current = current;
            log.sources.put(SOURCE_EVENT, sourceEvent(SOURCE_EVENT, OWNER, Optional.empty()));
            types.add(registration(TYPE,
                    Set.of(MemoryScope.USER_AGENT, MemoryScope.SESSION), false, false));
            types.add(registration(OTHER_TYPE,
                    Set.of(MemoryScope.USER_AGENT, MemoryScope.SESSION), false, false));
            types.add(registration(EPISODIC,
                    Set.of(MemoryScope.USER_AGENT), true, false));
            types.add(registration(SENSITIVE,
                    Set.of(MemoryScope.USER_AGENT), false, true));
            respond(List.of(new FactBuilder("fact-1").build()));
            runtime = new MemoryRuntime(
                    memory, log, access, types, sources, current,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private void respond(List<MemoryFact> facts) {
            current.available.clear();
            for (MemoryFact fact : facts) current.available.put(fact.ref(), fact);
            memory.queryOutcome = retrieved(new MemoryQueryResult(query.queryId(),
                facts,
                QUERY_RESULT,
                EVALUATED,
                testDiagnostics(facts)));
        }
    }

    private static MemoryTypeRegistry.Registration registration(
            MemoryTypeRef type,
            Set<MemoryScope> scopes,
            boolean recentEpisodic,
            boolean forceReview) {
        return registration(type, scopes, recentEpisodic, forceReview,
                (schema, payload) -> new MemoryTypeRegistry.Validation.Allowed());
    }

    private static MemoryTypeRegistry.Registration registration(
            MemoryTypeRef type,
            MemoryTypeRegistry.PayloadValidator validator) {
        return registration(
                type, Set.of(MemoryScope.USER_AGENT, MemoryScope.SESSION),
                false, false, validator);
    }

    private static MemoryTypeRegistry.Registration registration(
            MemoryTypeRef type,
            Set<MemoryScope> scopes,
            boolean recentEpisodic,
            boolean forceReview,
            MemoryTypeRegistry.PayloadValidator validator) {
        return new MemoryTypeRegistry.Registration(
                type, scopes,
                List.of(new MemoryTypeRegistry.Schema("v1", PAYLOAD.schemaId())),
                RETENTION, recentEpisodic, forceReview, validator);
    }

    private static final class FactBuilder {
        private MemoryFactRef ref;
        private EndUserId user = USER;
        private Optional<AgentId> agent = Optional.of(AGENT);
        private Optional<SessionRef> session = Optional.empty();
        private MemoryScope scope = MemoryScope.USER_AGENT;
        private MemoryTypeRef type = TYPE;
        private RetentionClassRef retention = RETENTION;
        private String schemaVersion = "v1";
        private String subject = "user";
        private String predicate = "likes";
        private TypedPayload payload = PAYLOAD;
        private List<EventId> sourceEvents = List.of(SOURCE_EVENT);
        private SessionRef sourceSession = SESSION;
        private Optional<ExecutionRef> sourceExecution = Optional.empty();
        private Optional<String> exportPath = Optional.empty();
        private String extractorVersion = "extractor-v1";
        private long revision = 1;
        private Instant createdAt = CREATED;
        private Instant validFrom = CREATED;
        private Optional<Instant> validTo = Optional.empty();
        private Optional<Instant> expiresAt = Optional.empty();
        private double confidence = 0.8;
        private double importance = 0.7;
        private boolean reviewRequired;
        private Optional<MemoryFactRef> derivedFrom = Optional.empty();
        private Optional<MemoryFactRef> supersededBy = Optional.empty();
        private double decayScore;
        private FactStatus status = FactStatus.ACTIVE;

        private FactBuilder(String factId) {
            ref = new MemoryFactRef(TENANT, factId);
        }

        private FactBuilder ref(MemoryFactRef value) { ref = value; return this; }
        private FactBuilder user(EndUserId value) { user = value; return this; }
        private FactBuilder agent(AgentId value) { agent = Optional.of(value); return this; }
        private FactBuilder agent(Optional<AgentId> value) { agent = value; return this; }
        private FactBuilder session(Optional<SessionRef> value) { session = value; return this; }
        private FactBuilder scope(MemoryScope value) { scope = value; return this; }
        private FactBuilder type(MemoryTypeRef value) { type = value; return this; }
        private FactBuilder retention(RetentionClassRef value) { retention = value; return this; }
        private FactBuilder schemaVersion(String value) { schemaVersion = value; return this; }
        private FactBuilder payload(TypedPayload value) { payload = value; return this; }
        private FactBuilder sourceEvents(List<EventId> value) { sourceEvents = value; return this; }
        private FactBuilder sourceSession(SessionRef value) { sourceSession = value; return this; }
        private FactBuilder predicate(String value) { predicate = value; return this; }
        private FactBuilder revision(long value) { revision = value; return this; }
        private FactBuilder createdAt(Instant value) { createdAt = value; return this; }
        private FactBuilder validFrom(Instant value) { validFrom = value; return this; }
        private FactBuilder validTo(Optional<Instant> value) { validTo = value; return this; }
        private FactBuilder expiresAt(Optional<Instant> value) { expiresAt = value; return this; }
        private FactBuilder supersededBy(Optional<MemoryFactRef> value) {
            supersededBy = value;
            return this;
        }
        private FactBuilder status(FactStatus value) { status = value; return this; }
        private FactBuilder derivedFrom(Optional<MemoryFactRef> value) { derivedFrom = value; return this; }

        private MemoryFact build() {
            return new MemoryFact(ref,
                testOwner((ref).tenantId(), user, agent, session, scope),
                type,
                retention,
                schemaVersion,
                subject,
                predicate,
                payload,
                sourceEvents,
                sourceSession,
                sourceExecution,
                exportPath,
                extractorVersion,
                revision,
                createdAt,
                validFrom,
                validTo,
                expiresAt,
                confidence,
                importance,
                reviewRequired,
                derivedFrom,
                supersededBy,
                decayScore,
                status);
        }
    }

    private static final class DraftBuilder {
        private EndUserId user = USER;
        private Optional<AgentId> agent = Optional.of(AGENT);
        private Optional<SessionRef> session = Optional.empty();
        private MemoryScope scope = MemoryScope.USER_AGENT;
        private MemoryTypeRef type = TYPE;
        private RetentionClassRef retention = RETENTION;
        private String schemaVersion = "v1";
        private String subject = "user";
        private String predicate = "likes";
        private TypedPayload payload = PAYLOAD;
        private List<EventId> sourceEvents = List.of(SOURCE_EVENT);
        private SessionRef sourceSession = SESSION;
        private Optional<ExecutionRef> sourceExecution = Optional.empty();
        private Optional<String> exportPath = Optional.empty();
        private String extractorVersion = "extractor-v1";
        private Instant validFrom = CREATED;
        private Optional<Instant> validTo = Optional.empty();
        private Optional<Instant> expiresAt = Optional.empty();
        private double confidence = 0.8;
        private double importance = 0.7;
        private boolean reviewRequired;
        private Optional<MemoryFactRef> derivedFrom = Optional.empty();
        private FactStatus status = FactStatus.ACTIVE;

        private DraftBuilder type(MemoryTypeRef value) { type = value; return this; }
        private DraftBuilder scope(MemoryScope value) { scope = value; return this; }
        private DraftBuilder agent(Optional<AgentId> value) { agent = value; return this; }
        private DraftBuilder retention(RetentionClassRef value) { retention = value; return this; }
        private DraftBuilder schemaVersion(String value) { schemaVersion = value; return this; }
        private DraftBuilder payload(TypedPayload value) { payload = value; return this; }
        private DraftBuilder predicate(String value) { predicate = value; return this; }
        private DraftBuilder sourceExecution(Optional<ExecutionRef> value) {
            sourceExecution = value;
            return this;
        }
        private DraftBuilder reviewRequired(boolean value) { reviewRequired = value; return this; }
        private DraftBuilder derivedFrom(Optional<MemoryFactRef> value) {
            derivedFrom = value;
            return this;
        }
        private DraftBuilder exportPath(Optional<String> value) { exportPath = value; return this; }
        private DraftBuilder status(FactStatus value) { status = value; return this; }

        private MemoryFactDraft build() {
            return new MemoryFactDraft(testOwner((sourceSession).tenantId(), user, agent, session, scope),
                type,
                retention,
                schemaVersion,
                subject,
                predicate,
                payload,
                sourceEvents,
                sourceSession,
                sourceExecution,
                exportPath,
                extractorVersion,
                validFrom,
                validTo,
                expiresAt,
                confidence,
                importance,
                reviewRequired,
                derivedFrom,
                status);
        }
    }

    private static final class FakeMemoryPort implements MemoryPort {
        private int queryCalls;
        private int currentBatchCalls;
        private CurrentFactReadBatch lastCurrentBatch;
        private FakeCurrentFactSource current;
        private Function<CurrentFactReadBatch, List<Optional<MemoryFact>>> batchResponder;
        private int submitCalls;
        private MemoryQueryCall lastQuery;
        private MemoryCommand lastCommand;
        private final List<MemoryCommand> commands = new ArrayList<>();
        private MemoryQueryOutcome queryOutcome;
        private MemoryMutationResult mutationResult;
        private RuntimeException queryFailure;
        private RuntimeException submitFailure;

        @Override
        public List<Optional<MemoryFact>> findCurrent(CurrentFactReadBatch batch) {
            currentBatchCalls++;
            lastCurrentBatch = batch;
            if (batchResponder != null) return batchResponder.apply(batch);
            return batch.reads().stream().map(read -> current.lookup(read.fact())).toList();
        }

        @Override
        public MemoryQueryOutcome query(MemoryQueryCall call) {
            queryCalls++;
            lastQuery = call;
            if (queryFailure != null) {
                RuntimeException failure = queryFailure;
                queryFailure = null;
                throw failure;
            }
            return queryOutcome;
        }

        @Override
        public MemoryMutationResult submit(MemoryCommand command) {
            submitCalls++;
            lastCommand = command;
            commands.add(command);
            if (submitFailure != null) {
                RuntimeException failure = submitFailure;
                submitFailure = null;
                throw failure;
            }
            return mutationResult;
        }
    }

    private enum LogMode {
        NORMAL,
        PERSIST_THEN_THROW,
        BAD_ACK,
        CONFLICT
    }

    private static final class FakeSessionLog implements SessionLogPort {
        private final Map<EventId, CommittedSessionEvent> sources = new HashMap<>();
        private final Map<EventId, CommittedSessionEvent> appended = new LinkedHashMap<>();
        private final List<CommittedSessionEvent> readEvents = new ArrayList<>();
        private int appendCalls;
        private int findCalls;
        private int readCalls;
        private long nextSequence = 1;
        private LogMode mode = LogMode.NORMAL;
        private AppendSessionEvent lastAppend;
        private SessionOwner lastFindOwner;
        private final List<SessionOwner> findOwners = new ArrayList<>();
        private EventId lastFindEventId;
        private final List<UserSourceRead> userSourceReads = new ArrayList<>();
        private Function<UserSourceRead, Optional<CommittedSessionEvent>> userSourceResponder;

        @Override
        public AppendResult append(AppendSessionEvent command) {
            appendCalls++;
            lastAppend = command;
            CommittedSessionEvent existing = appended.get(command.event().eventId());
            if (existing != null) {
                return new AppendResult.Replayed(existing);
            }
            if (mode == LogMode.CONFLICT) {
                mode = LogMode.NORMAL;
                return new AppendResult.Conflict(new PortConflict(
                        "session-event", command.event().eventId().value(), CONFLICT_AUDIT));
            }
            CommittedSessionEvent committed = commit(command, nextSequence++);
            appended.put(committed.eventId(), committed);
            readEvents.add(committed);
            if (mode == LogMode.PERSIST_THEN_THROW) {
                mode = LogMode.NORMAL;
                throw new IllegalStateException("response lost after commit");
            }
            if (mode == LogMode.BAD_ACK) {
                mode = LogMode.NORMAL;
                return new AppendResult.Appended(new CommittedSessionEvent(
                        committed.owner(), committed.inputId(), committed.runRef(),
                        committed.executionRef(), committed.actionRef(), committed.delegationRef(),
                        committed.eventId(), committed.sessionSequence(),
                        committed.eventType() + ".wrong", committed.eventVersion(),
                        committed.trace(), committed.payload(), committed.occurredAt()));
            }
            return new AppendResult.Appended(committed);
        }

        @Override
        public Optional<CommittedSessionEvent> findByEvent(
                SessionOwner owner, EventId eventId) {
            findCalls++;
            findOwners.add(owner);
            lastFindOwner = owner;
            lastFindEventId = eventId;
            return Optional.ofNullable(sources.containsKey(eventId) ? sources.get(eventId) : appended.get(eventId));
        }

        @Override
        public Optional<CommittedSessionEvent> findUserSource(SessionRef session, EndUserId user, EventId eventId) {
            var read = new UserSourceRead(session, user, eventId);
            userSourceReads.add(read);
            if (userSourceResponder != null) return userSourceResponder.apply(read);
            return Optional.ofNullable(sources.get(eventId)).filter(event -> event.owner().sessionRef().equals(session)
                    && event.owner().subject() instanceof com.skillforge.runtime.api.SessionSubject.User subject
                    && subject.endUserId().equals(user) && event.eventId().equals(eventId));
        }

        @Override
        public List<CommittedSessionEvent> readAfter(ReadSessionLog query) {
            readCalls++;
            return readEvents.stream()
                    .filter(event -> event.owner().equals(query.owner()))
                    .filter(event -> event.sessionSequence() > query.afterSequence())
                    .sorted(java.util.Comparator.comparingLong(CommittedSessionEvent::sessionSequence))
                    .limit(query.limit())
                    .toList();
        }

        private static CommittedSessionEvent commit(AppendSessionEvent command, long sequence) {
            var target = command.target();
            SessionEventDraft event = command.event();
            return new CommittedSessionEvent(
                    target.owner(), target.agentId(), target.inputId(), target.runRef(), target.executionRef(),
                    target.actionRef(), target.delegationRef(), event.eventId(), sequence,
                    event.eventType(), event.eventVersion(), event.trace(), event.payload(),
                    event.occurredAt());
        }
    }

    private record UserSourceRead(SessionRef session, EndUserId user, EventId event) { }

    private static final class FakeAccessPolicy implements MemoryAccessPolicy {
        private int calls;
        private Request lastRequest;
        private Decision decision = new Decision.Allowed(ACCESS_AUDIT);
        private RuntimeException failure;

        @Override
        public Decision authorize(Request request) {
            calls++;
            lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return decision;
        }
    }

    private static final class FakeRegistry implements MemoryTypeRegistry {
        private final Map<MemoryTypeRef, Registration> registrations = new HashMap<>();
        private int calls;

        private void add(Registration registration) {
            registrations.put(registration.type(), registration);
        }

        @Override
        public Optional<Registration> find(MemoryTypeRef type) {
            calls++;
            return Optional.ofNullable(registrations.get(type));
        }
    }

    private static final class FakeSourcePolicy implements MemorySourcePolicy {
        private int calls;
        private Request lastRequest;
        private Decision decision = new Decision.Allowed(SOURCE_AUDIT);
        private RuntimeException failure;
        private Function<Request, Decision> responder;

        @Override
        public Decision authorize(Request request) {
            calls++;
            lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return responder == null ? decision : responder.apply(request);
        }
    }

    private static final class FakeCurrentFactSource implements MemoryRuntime.CurrentFactSource {
        private int calls;
        private PortCallContext lastContext;
        private MemoryFactRef lastRef;
        private Optional<MemoryFact> fact = Optional.empty();
        private final Map<MemoryFactRef, MemoryFact> available = new HashMap<>();
        private boolean unavailable;

        @Override
        public Optional<MemoryFact> find(PortCallContext context, MemoryFactRef factRef) {
            calls++;
            lastContext = context;
            lastRef = factRef;
            return lookup(factRef);
        }

        private Optional<MemoryFact> lookup(MemoryFactRef factRef) {
            return unavailable ? Optional.empty() : fact.isPresent() ? fact : Optional.ofNullable(available.get(factRef));
        }
    }

    private static MemoryOwner testOwner(com.skillforge.runtime.api.TenantId tenant,
            com.skillforge.runtime.api.EndUserId user, Optional<com.skillforge.runtime.api.AgentId> agent,
            Optional<com.skillforge.runtime.api.SessionRef> session, MemoryScope scope) {
        java.util.Objects.requireNonNull(session);
        return switch (scope) {
            case SESSION -> new MemoryOwner.Session(session.orElseThrow(() -> new IllegalArgumentException("session required")));
            case USER_AGENT -> {
                if (session.isPresent()) throw new IllegalArgumentException("agent fact has no session owner");
                yield new MemoryOwner.UserAgent(tenant, user, agent.orElseThrow(() -> new IllegalArgumentException("agent required")));
            }
            case USER -> {
                if (session.isPresent() || agent.isPresent()) throw new IllegalArgumentException("user owner has no agent/session");
                yield new MemoryOwner.User(tenant, user);
            }
            default -> throw new IllegalArgumentException("use typed group owner in group fixtures");
        };
    }

    private static MemoryOwner testQueryOwner(com.skillforge.runtime.api.TenantId tenant,
            com.skillforge.runtime.api.EndUserId user, com.skillforge.runtime.api.AgentId agent,
            Optional<com.skillforge.runtime.api.SessionRef> session) {
        java.util.Objects.requireNonNull(session);
        if (session.isPresent()) {
            if (!session.orElseThrow().tenantId().equals(tenant)) throw new IllegalArgumentException("foreign session");
            return new MemoryOwner.Session(session.orElseThrow());
        }
        return new MemoryOwner.UserAgent(tenant, user, agent);
    }

    /** 受控 fixture 的词法候选诊断，不表示真实检索得分。 */
    private static com.skillforge.runtime.api.MemoryRetrievalDiagnostics testDiagnostics(List<MemoryFact> facts) {
        var candidates = new java.util.ArrayList<com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Candidate>();
        for (int index = 0; index < facts.size(); index++) {
            MemoryFact fact = facts.get(index);
            candidates.add(new com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Candidate(fact.ref(), fact.revision(),
                    Optional.of(1d), Optional.of(index + 1), Optional.empty(), Optional.empty(), 1d / (61 + index)));
        }
        return new com.skillforge.runtime.api.MemoryRetrievalDiagnostics(com.skillforge.runtime.api.MemoryRetrievalDiagnostics.Mode.LEXICAL,
                Optional.empty(), facts.size(), 0, candidates, facts.stream().map(fact -> fact.ref().factId()).toList());
    }
}
