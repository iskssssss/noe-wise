package com.skillforge.runtime.adapter.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryQueryProfile;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.MemoryPort;
import com.skillforge.runtime.api.port.MemoryPort.CurrentFactRead;
import com.skillforge.runtime.api.port.MemoryPort.CurrentFactReadBatch;
import com.skillforge.runtime.api.port.MemoryPort.MemoryCommand;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.MemoryPort.MemoryMutationResult;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryCall;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryOutcome;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Memory Store development/production 实现共享的查询、CAS、幂等与恢复合同。 */
public abstract class MemoryPortContract {

    protected static final Instant NOW = Instant.parse("2020-09-11T08:30:00.123456789Z");
    protected static final TenantId TENANT = new TenantId("7001");
    protected static final AgentId AGENT = new AgentId("8001");
    protected static final EndUserId USER = new EndUserId(9001);
    protected static final SessionRef SESSION = new SessionRef(TENANT, "memory-session-current");
    protected static final SessionRef HISTORICAL = new SessionRef(TENANT, "memory-session-history");
    protected static final GroupBindingRef GROUP = new GroupBindingRef(TENANT, "memory-group-binding");
    protected static final GroupBindingRef OTHER_GROUP = new GroupBindingRef(TENANT, "memory-other-group-binding");
    protected static final SessionRef GROUP_SESSION = new SessionRef(TENANT, "memory-group-session-current");
    protected static final SessionRef GROUP_HISTORICAL = new SessionRef(TENANT, "memory-group-session-history");
    protected static final SessionRef OTHER_GROUP_SESSION = new SessionRef(TENANT, "memory-other-group-session");
    protected static final EndUserId OTHER_MEMBER = new EndUserId(9002);
    protected static final MemoryTypeRef TYPE = new MemoryTypeRef("episodic.preference");

    private MemoryPort port;

    protected abstract MemoryPort createPort();

    protected abstract MemoryPort recreatePort();

    protected abstract Optional<MemoryFact> findCurrent(
            MemoryPort subject, PortCallContext context, MemoryFactRef ref);

    /** 实现可按注入时钟或数据库时钟验证一次真实 Store 采样。 */
    protected void assertStoreTime(Instant sampledAt) {
        assertNotNull(sampledAt);
    }

    @BeforeEach
    protected final void resetPort() {
        port = createPort();
        assertNotNull(port);
    }

    @Test
    protected final void userFactsAreVisibleToSameEndUserDifferentAgentWithoutLeakingAgentMemory() {
        MemoryFact shared = createFact("user-share", context(SESSION),
                draft(SESSION, MemoryScope.USER, FactStatus.ACTIVE,
                        NOW.minusSeconds(60), Optional.empty(), "likes tea", 0.8));
        createFact("agent-private", context(SESSION),
                draft(SESSION, MemoryScope.USER_AGENT, FactStatus.ACTIVE,
                        NOW.minusSeconds(60), Optional.empty(), "likes tea", 0.8));
        assertEquals(MemoryScope.USER, shared.scope());
        assertEquals(new MemoryOwner.User(TENANT, USER), shared.owner());
        assertEquals(SESSION, shared.sourceSessionRef());

        AgentId reader = new AgentId("8002");
        SessionRef readerSession = new SessionRef(TENANT, "memory-session-reader");
        PortCallContext readerContext = context(readerSession, reader);
        MemoryQueryOutcome.Retrieved visible = retrieved(port.query(new MemoryQueryCall(
                readerContext,
                query(readerSession, Set.of(MemoryScope.USER), MemoryQueryProfile.DEFAULT,
                        "likes tea", 10, 10_000, 10_000))));
        assertEquals(List.of(shared), visible.result().candidates());

        MemoryQueryOutcome.Retrieved hidden = retrieved(port.query(new MemoryQueryCall(
                readerContext,
                query(readerSession, Set.of(MemoryScope.USER_AGENT), MemoryQueryProfile.DEFAULT,
                        "likes tea", 10, 10_000, 10_000))));
        assertTrue(hidden.result().candidates().isEmpty());
    }

    @Test
    protected final void groupFactsAreSharedAcrossAgentsAndMemberQueriesKeepTheirSubject() {
        PortCallContext writer = groupContext(GROUP_SESSION, GROUP, USER, AGENT);
        MemoryFact shared = createFact("group-shared", writer,
                groupDraft(new MemoryOwner.Group(GROUP), GROUP_SESSION,
                        FactStatus.ACTIVE, "prefers tea", Optional.empty()));
        MemoryFact member = createFact("group-member-self", writer,
                groupDraft(new MemoryOwner.GroupMember(GROUP, USER), GROUP_SESSION,
                        FactStatus.ACTIVE, "prefers tea", Optional.empty()));
        MemoryFact other = createFact("group-member-other", writer,
                groupDraft(new MemoryOwner.GroupMember(GROUP, OTHER_MEMBER), GROUP_SESSION,
                        FactStatus.ACTIVE, "prefers tea", Optional.empty()));
        assertEquals(new MemoryOwner.Group(GROUP), shared.owner());
        assertEquals(new MemoryOwner.GroupMember(GROUP, OTHER_MEMBER), other.owner());

        PortCallContext reader = groupContext(GROUP_HISTORICAL, GROUP, USER, new AgentId("8002"));
        Set<MemoryScope> scopes = Set.of(MemoryScope.GROUP, MemoryScope.GROUP_MEMBER);
        var own = retrieved(port.query(new MemoryQueryCall(reader,
                groupQuery(GROUP, Optional.empty(), scopes))));
        assertEquals(Set.of(shared.ref(), member.ref()), own.result().candidates().stream()
                .map(MemoryFact::ref).collect(java.util.stream.Collectors.toSet()));
        var explicit = retrieved(port.query(new MemoryQueryCall(reader,
                groupQuery(GROUP, Optional.of(OTHER_MEMBER), scopes))));
        assertEquals(Set.of(shared.ref(), other.ref()), explicit.result().candidates().stream()
                .map(MemoryFact::ref).collect(java.util.stream.Collectors.toSet()));

        PortCallContext otherMember = groupContext(GROUP_HISTORICAL, GROUP, OTHER_MEMBER, AGENT);
        assertEquals(List.of(other.ref()), retrieved(port.query(new MemoryQueryCall(otherMember,
                groupQuery(GROUP, Optional.empty(), Set.of(MemoryScope.GROUP_MEMBER)))))
                .result().candidates().stream().map(MemoryFact::ref).toList());
        assertEquals(shared, findCurrent(recreatePort(), reader, shared.ref()).orElseThrow());
        assertEquals(other, findCurrent(recreatePort(), otherMember, other.ref()).orElseThrow());
    }

    @Test
    protected final void groupCurrentReadsAndQueriesDoNotCrossGroupOrPersonalBoundaries() {
        PortCallContext group = groupContext(GROUP_SESSION, GROUP, USER, AGENT);
        MemoryFact shared = createFact("boundary-group", group,
                groupDraft(new MemoryOwner.Group(GROUP), GROUP_SESSION,
                        FactStatus.ACTIVE, "prefers tea", Optional.empty()));
        MemoryFact personal = createFact("boundary-personal", context(SESSION),
                draft(SESSION, MemoryScope.USER, FactStatus.ACTIVE,
                        NOW.minusSeconds(60), Optional.empty(), "prefers tea", 0.8));
        PortCallContext another = groupContext(OTHER_GROUP_SESSION, OTHER_GROUP, USER, AGENT);
        assertTrue(findCurrent(port, another, shared.ref()).isEmpty());
        assertTrue(findCurrent(port, context(SESSION), shared.ref()).isEmpty());
        assertTrue(findCurrent(port, group, personal.ref()).isEmpty());
        assertTrue(retrieved(port.query(new MemoryQueryCall(another,
                groupQuery(OTHER_GROUP, Optional.empty(), Set.of(MemoryScope.GROUP)))))
                .result().candidates().isEmpty());
        assertEquals(List.of(shared.ref()), retrieved(port.query(new MemoryQueryCall(group,
                groupQuery(GROUP, Optional.empty(), Set.of(MemoryScope.GROUP)))))
                .result().candidates().stream().map(MemoryFact::ref).toList());
    }

    @Test
    protected final void groupGovernanceUsesCasAndPreservesOwnerAfterReconstruction() {
        PortCallContext group = groupContext(GROUP_SESSION, GROUP, USER, AGENT);
        MemoryFact pending = createFact("group-pending", group,
                groupDraft(new MemoryOwner.GroupMember(GROUP, USER), GROUP_SESSION,
                        FactStatus.PENDING_CONFIRM, "prefers tea", Optional.empty()));
        MemoryFact confirmed = applied(port.submit(new MemoryCommand.Transition(group,
                new IdempotencyKey("group-confirm"), pending.ref(), pending.revision(),
                FactStatus.ACTIVE, "member-confirmed")), false);
        assertEquals(pending.owner(), confirmed.owner());
        assertInstanceOf(MemoryMutationResult.Conflict.class,
                port.submit(new MemoryCommand.Transition(group, new IdempotencyKey("group-stale"),
                        pending.ref(), pending.revision(), FactStatus.REJECTED, "stale-rejection")));
        MemoryFact forgotten = applied(port.submit(new MemoryCommand.Transition(group,
                new IdempotencyKey("group-forget"), confirmed.ref(), confirmed.revision(),
                FactStatus.FORGOTTEN, "member-forgotten")), false);
        MemoryPort restored = recreatePort();
        assertEquals(forgotten, findCurrent(restored, group, forgotten.ref()).orElseThrow());
        assertTrue(retrieved(restored.query(new MemoryQueryCall(group,
                groupQuery(GROUP, Optional.empty(), Set.of(MemoryScope.GROUP_MEMBER)))))
                .result().candidates().isEmpty());
    }

    @Test
    protected final void pendingGroupMemberFactCanBeForgottenWithoutBecomingActive() {
        MemoryFact pending = createFact("third-party-pending",
                groupContext(GROUP_SESSION, GROUP, OTHER_MEMBER, AGENT),
                groupDraft(new MemoryOwner.GroupMember(GROUP, USER), GROUP_SESSION,
                        FactStatus.PENDING_CONFIRM, "prefers tea", Optional.empty()));
        PortCallContext subject = groupContext(GROUP_SESSION, GROUP, USER, AGENT);
        MemoryCommand.Transition forget = new MemoryCommand.Transition(subject,
                new IdempotencyKey("subject-forget-pending"), pending.ref(), pending.revision(),
                FactStatus.FORGOTTEN, "subject-forgotten");
        MemoryMutationResult.Applied first = assertInstanceOf(MemoryMutationResult.Applied.class,
                port.submit(forget));
        assertEquals(Optional.of(pending), first.before());
        MemoryFact forgotten = applied(first, false);
        assertEquals(FactStatus.FORGOTTEN, forgotten.status());
        assertEquals(pending.revision() + 1, forgotten.revision());
        assertEquals(pending.owner(), forgotten.owner());
        MemoryPort restored = recreatePort();
        assertEquals(forgotten, findCurrent(restored, subject, pending.ref()).orElseThrow());
        MemoryMutationResult.Applied replay = assertInstanceOf(MemoryMutationResult.Applied.class,
                restored.submit(forget));
        assertEquals(Optional.of(pending), replay.before());
        assertEquals(forgotten, applied(replay, true));
        assertTrue(retrieved(restored.query(new MemoryQueryCall(subject,
                groupQuery(GROUP, Optional.empty(), Set.of(MemoryScope.GROUP_MEMBER)))))
                .result().candidates().isEmpty());
    }

    @Test
    protected final void groupCorrectionsCannotMoveFactsToAnotherMember() {
        PortCallContext group = groupContext(GROUP_SESSION, GROUP, USER, AGENT);
        MemoryFact original = createFact("group-correction-source", group,
                groupDraft(new MemoryOwner.GroupMember(GROUP, USER), GROUP_SESSION,
                        FactStatus.ACTIVE, "prefers tea", Optional.empty()));
        assertInstanceOf(MemoryMutationResult.Rejected.class,
                port.submit(new MemoryCommand.Supersede(group, new IdempotencyKey("group-retarget"),
                        original.ref(), original.revision(),
                        groupDraft(new MemoryOwner.GroupMember(GROUP, OTHER_MEMBER), GROUP_SESSION,
                                FactStatus.ACTIVE, "prefers tea", Optional.of(original.ref())))));
        MemoryFact correction = applied(port.submit(new MemoryCommand.Supersede(group,
                new IdempotencyKey("group-correct"), original.ref(), original.revision(),
                groupDraft(original.owner(), GROUP_SESSION, FactStatus.ACTIVE,
                        "prefers green tea", Optional.of(original.ref())))), false);
        MemoryPort restored = recreatePort();
        MemoryFact archived = findCurrent(restored, group, original.ref()).orElseThrow();
        assertEquals(FactStatus.ARCHIVED, archived.status());
        assertEquals(Optional.of(correction.ref()), archived.supersededBy());
        assertEquals(original.owner(), correction.owner());
        assertEquals(correction, findCurrent(restored, group, correction.ref()).orElseThrow());
    }

    @Test
    protected final void systemGroupQueryWithoutSubjectCannotBroadenToAllMembers() {
        PortCallContext writer = groupContext(GROUP_SESSION, GROUP, USER, AGENT);
        MemoryFact shared = createFact("system-group-public", writer,
                groupDraft(new MemoryOwner.Group(GROUP), GROUP_SESSION,
                        FactStatus.ACTIVE, "prefers tea", Optional.empty()));
        createFact("system-group-member", writer,
                groupDraft(new MemoryOwner.GroupMember(GROUP, USER), GROUP_SESSION,
                        FactStatus.ACTIVE, "prefers tea", Optional.empty()));
        RunIdentity original = writer.runIdentity();
        PrincipalRef.SystemPrincipal principal = new PrincipalRef.SystemPrincipal(TENANT, "memory-worker");
        RunIdentity systemRun = new RunIdentity(original.owner(), original.agentId(),
                original.causationInputId(), original.runRef(), original.trace(), original.causationIdentityRef(),
                new RunRequester.System(principal), original.sourceMessageRef(), original.originRef());
        PortCallContext system = new PortCallContext(systemRun,
                new VerifiedIdentity(principal, original.owner(), Set.of("runtime:memory"), 1,
                        new ResultRef("memory-system-verification")), writer.control());
        assertEquals(List.of(shared.ref()), retrieved(port.query(new MemoryQueryCall(system,
                groupQuery(GROUP, Optional.empty(), Set.of(MemoryScope.GROUP)))))
                .result().candidates().stream().map(MemoryFact::ref).toList());
        assertThrows(IllegalArgumentException.class, () -> new MemoryQueryCall(system,
                groupQuery(GROUP, Optional.empty(), Set.of(MemoryScope.GROUP, MemoryScope.GROUP_MEMBER))));
    }

    @Test
    protected final void currentBatchKeepsEachOwnerOrderMissingDuplicatesAndGovernanceState() {
        MemoryFact session = createFact("batch-historical", context(HISTORICAL),
                draft(HISTORICAL, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.minusSeconds(1), Optional.empty(), "historical", 0.7));
        MemoryFact agent = createFact("batch-agent", context(HISTORICAL),
                draft(HISTORICAL, MemoryScope.USER_AGENT, FactStatus.ACTIVE,
                        NOW.minusSeconds(1), Optional.empty(), "agent", 0.7));
        MemoryFact decayed = applied(port.submit(transition("batch-decay", agent, FactStatus.DECAYED)), false);
        MemoryFact user = createFact("batch-user", context(SESSION),
                draft(SESSION, MemoryScope.USER, FactStatus.PENDING_CONFIRM,
                        NOW.plusNanos(1), Optional.empty(), "user", 0.7));
        PortCallContext reader = context(new SessionRef(TENANT, "memory-reader"), new AgentId("8002"));
        var reads = List.of(
                new CurrentFactRead(context(SESSION), session.ref()),
                new CurrentFactRead(context(HISTORICAL), session.ref()),
                new CurrentFactRead(context(SESSION), decayed.ref()),
                new CurrentFactRead(reader, user.ref()),
                new CurrentFactRead(reader, decayed.ref()),
                new CurrentFactRead(context(SESSION), new MemoryFactRef(TENANT, "missing")),
                new CurrentFactRead(context(HISTORICAL), session.ref()));
        var expected = List.of(Optional.<MemoryFact>empty(), Optional.of(session), Optional.of(decayed),
                Optional.of(user), Optional.<MemoryFact>empty(), Optional.<MemoryFact>empty(), Optional.of(session));
        assertEquals(expected, port.findCurrent(new CurrentFactReadBatch(reads)));
        assertEquals(expected, recreatePort().findCurrent(new CurrentFactReadBatch(reads)));
        assertEquals(List.of(), port.findCurrent(new CurrentFactReadBatch(List.of())));
    }

    @Test
    protected final void createReplaysOriginalAcrossRebuildAndConflictsOnBodyDrift() {
        MemoryCommand.Create command = create("create-replay", context(SESSION),
                draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.minusSeconds(60), Optional.empty(), "likes tea", 0.8));
        MemoryFact original = applied(port.submit(command), false);
        assertEquals(1, original.revision());
        assertStoreTime(original.createdAt());
        assertEquals(0d, original.decayScore());

        MemoryPort rebuilt = recreatePort();
        assertEquals(original, applied(rebuilt.submit(command), true));
        MemoryCommand.Create drift = create("create-replay", context(SESSION),
                draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.minusSeconds(60), Optional.empty(), "likes coffee", 0.8));
        MemoryMutationResult.Conflict conflict = assertInstanceOf(
                MemoryMutationResult.Conflict.class, rebuilt.submit(drift));
        assertEquals(original.ref(), conflict.fact());
        assertEquals(1, conflict.currentRevision());
        assertEquals(Optional.of(original), findCurrent(rebuilt, context(SESSION), original.ref()));
    }

    @Test
    protected final void queryAppliesValidityAndBudgetWithoutWrites() {
        MemoryFact strongest = createFact("query-strong", context(SESSION),
                draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.minusSeconds(60), Optional.empty(), "project alpha plan", 0.9));
        MemoryFact partial = createFact("query-partial", context(SESSION),
                draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.minusSeconds(30), Optional.empty(), "project notes", 0.7));
        createFact("query-expired", context(SESSION),
                draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.minusSeconds(60), Optional.of(NOW), "project alpha plan", 1.0));
        createFact("query-future", context(SESSION),
                draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.plusSeconds(1), Optional.empty(), "project alpha plan", 1.0));

        MemoryQueryOutcome.Retrieved result = retrieved(port.query(new MemoryQueryCall(
                context(SESSION), query(SESSION, Set.of(MemoryScope.SESSION),
                        MemoryQueryProfile.DEFAULT, "project alpha", 10, 10_000, 10_000))));
        assertEquals(Set.of(strongest.ref(), partial.ref()), result.result().candidates().stream()
                .map(MemoryFact::ref).collect(java.util.stream.Collectors.toSet()));
        assertStoreTime(result.result().evaluatedAt());

        MemoryQueryOutcome.Retrieved empty = retrieved(port.query(new MemoryQueryCall(
                context(SESSION), query(SESSION, Set.of(MemoryScope.SESSION),
                        MemoryQueryProfile.DEFAULT, "project alpha", 10, 1, 1))));
        assertTrue(empty.result().candidates().isEmpty());
        assertEquals(Optional.of(strongest), findCurrent(port, context(SESSION), strongest.ref()),
                "ordinary query must not mutate facts or usage counters");
    }

    @Test
    protected final void queryKeepsFactsWhoseOrderedMeaningDiffers() {
        MemoryFact first = createFact("query-order-first", context(SESSION),
                draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.minusSeconds(60), Optional.empty(), "alpha before beta", 0.8));
        MemoryFact second = createFact("query-order-second", context(SESSION),
                draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.minusSeconds(30), Optional.empty(), "beta before alpha", 0.8));

        MemoryQueryOutcome.Retrieved result = retrieved(port.query(new MemoryQueryCall(
                context(SESSION), query(SESSION, Set.of(MemoryScope.SESSION),
                        MemoryQueryProfile.DEFAULT, "alpha beta",
                        10, 10_000, 10_000))));
        assertEquals(Set.of(first.ref(), second.ref()), result.result().candidates().stream()
                .map(MemoryFact::ref).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    protected final void currentReadsPreserveDifferentJsonShapesAndSignedNumbers() {
        MemoryFact negative = createFact("query-json-negative", context(SESSION),
                draftObject(SESSION, "signed amount", "{\"value\":-1}"));
        MemoryFact positive = createFact("query-json-positive", context(SESSION),
                draftObject(SESSION, "signed amount", "{\"value\":1}"));
        MemoryFact stringValue = createFact("query-json-string", context(SESSION),
                draftObject(SESSION, "structured alpha beta", "{\"value\":\"a b\"}"));
        MemoryFact arrayValue = createFact("query-json-array", context(SESSION),
                draftObject(SESSION, "structured alpha beta", "{\"value\":[\"a\",\"b\"]}"));

        for (MemoryFact fact : List.of(negative, positive, stringValue, arrayValue)) {
            assertEquals(Optional.of(fact), findCurrent(recreatePort(), context(SESSION), fact.ref()));
        }
    }

    @Test
    protected final void recentEpisodicUsesUserAgentOnlyThirtyDayWindow() {
        createFact("recent-old", context(HISTORICAL),
                draft(HISTORICAL, MemoryScope.USER_AGENT, FactStatus.ACTIVE,
                        NOW.minusSeconds(31L * 24 * 60 * 60), Optional.empty(), "project alpha", 1.0));
        MemoryFact recent = createFact("recent-hit", context(HISTORICAL),
                draft(HISTORICAL, MemoryScope.USER_AGENT, FactStatus.ACTIVE,
                        NOW.minusSeconds(24 * 60 * 60), Optional.empty(), "project alpha", 1.0));

        MemoryQueryOutcome.Retrieved result = retrieved(port.query(new MemoryQueryCall(
                context(SESSION), query(SESSION, Set.of(MemoryScope.USER_AGENT),
                        MemoryQueryProfile.RECENT_EPISODIC, "project alpha",
                        10, 10_000, 10_000))));
        assertEquals(List.of(recent.ref()), result.result().candidates().stream()
                .map(MemoryFact::ref).toList());

        MemoryQuery invalid = new MemoryQuery("memory-query-invalid", new MemoryOwner.Session(SESSION), Optional.empty(), "project alpha", Optional.empty(), Set.of(MemoryScope.SESSION), Set.of(TYPE),
                MemoryQueryProfile.RECENT_EPISODIC, NOW, MemoryRetrievalPolicy.lexicalV1(), 10, 10_000, 10_000, true);
        assertInstanceOf(MemoryQueryOutcome.Failed.class,
                port.query(new MemoryQueryCall(context(SESSION), invalid)));
    }

    @Test
    protected final void scopeAwareCurrentAndQuerySeparateSessionButAllowHistoricalUserAgent() {
        MemoryFact session = createFact("scope-session", context(HISTORICAL),
                draft(HISTORICAL, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.minusSeconds(1), Optional.empty(), "project alpha", 1.0));
        MemoryFact userAgent = createFact("scope-user-agent", context(HISTORICAL),
                draft(HISTORICAL, MemoryScope.USER_AGENT, FactStatus.ACTIVE,
                        NOW.minusSeconds(1), Optional.empty(), "project alpha", 1.0));

        assertTrue(findCurrent(port, context(SESSION), session.ref()).isEmpty());
        assertEquals(Optional.of(userAgent), findCurrent(port, context(SESSION), userAgent.ref()));
        MemoryQueryOutcome.Retrieved result = retrieved(port.query(new MemoryQueryCall(
                context(SESSION), query(SESSION, Set.of(MemoryScope.SESSION, MemoryScope.USER_AGENT),
                        MemoryQueryProfile.DEFAULT, "project alpha", 10, 10_000, 10_000))));
        assertEquals(List.of(userAgent.ref()), result.result().candidates().stream()
                .map(MemoryFact::ref).toList());
    }

    @Test
    protected final void supersedeAtomicallyArchivesOldHeadAndCreatesDerivedReplacement() {
        MemoryFact current = createFact("supersede-current", context(SESSION),
                draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.minusSeconds(60), Optional.empty(), "project alpha", 0.8));
        MemoryFactDraft replacement = draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                NOW, Optional.empty(), "project alpha corrected", 0.9, Optional.of(current.ref()));
        MemoryCommand.Supersede command = new MemoryCommand.Supersede(context(SESSION),
                new IdempotencyKey("supersede-operation"), current.ref(), current.revision(), replacement);
        MemoryMutationResult.Applied first = assertInstanceOf(MemoryMutationResult.Applied.class,
                port.submit(command));
        assertEquals(Optional.of(current), first.before());
        MemoryFact applied = applied(first, false);

        assertEquals(Optional.of(current.ref()), applied.derivedFrom());
        assertEquals(1, applied.revision());
        MemoryFact oldHead = findCurrent(port, context(SESSION), current.ref()).orElseThrow();
        assertEquals(2, oldHead.revision());
        assertEquals(FactStatus.ARCHIVED, oldHead.status());
        assertEquals(Optional.of(applied.ref()), oldHead.supersededBy());
        assertEquals(Optional.of(applied.createdAt()), oldHead.validTo());
        MemoryMutationResult.Applied replay = assertInstanceOf(MemoryMutationResult.Applied.class,
                recreatePort().submit(command));
        assertEquals(Optional.of(current), replay.before());
        assertEquals(applied, applied(replay, true));
    }

    @Test
    protected final void acceptRevisionAtomicallyActivatesExistingProposalAndReplaysOriginalPair() {
        MemoryFact current = createFact("accept-current", context(SESSION), draft(SESSION, MemoryScope.SESSION,
                FactStatus.ACTIVE, NOW.minusSeconds(60), Optional.empty(), "original", 0.8));
        MemoryFact proposal = createFact("accept-proposal", context(SESSION), draft(SESSION, MemoryScope.SESSION,
                FactStatus.PENDING_CONFIRM, NOW.minusSeconds(10), Optional.empty(), "corrected", 0.8, Optional.of(current.ref())));
        MemoryMutationResult.Rejected singleSided = assertInstanceOf(MemoryMutationResult.Rejected.class,
                port.submit(transition("accept-single-sided-forbidden", proposal, FactStatus.ACTIVE)));
        assertEquals("memory_revision_acceptance_required", singleSided.failure().reasonCode());
        assertEquals(current, findCurrent(port, context(SESSION), current.ref()).orElseThrow());
        assertEquals(proposal, findCurrent(port, context(SESSION), proposal.ref()).orElseThrow());
        var command = new MemoryCommand.AcceptRevision(context(SESSION), new IdempotencyKey("accept-pair"),
                current.ref(), current.revision(), proposal.ref(), proposal.revision());
        var first = assertInstanceOf(MemoryMutationResult.Applied.class, port.submit(command));
        MemoryFact activated = applied(first, false);
        assertEquals(Optional.of(current), first.before());
        assertEquals(proposal.ref(), activated.ref());
        assertEquals(2, activated.revision());
        assertEquals(FactStatus.ACTIVE, activated.status());
        assertEquals(proposal.object(), activated.object());
        assertEquals(proposal.sourceEventIds(), activated.sourceEventIds());
        assertEquals(proposal.sourceSessionRef(), activated.sourceSessionRef());
        assertEquals(proposal.createdAt(), activated.createdAt());
        assertEquals(Optional.of(current.ref()), activated.derivedFrom());
        MemoryFact archived = findCurrent(port, context(SESSION), current.ref()).orElseThrow();
        assertEquals(FactStatus.ARCHIVED, archived.status());
        assertEquals(2, archived.revision());
        assertEquals(Optional.of(proposal.ref()), archived.supersededBy());
        assertTrue(archived.validTo().isPresent());
        var replay = assertInstanceOf(MemoryMutationResult.Applied.class, recreatePort().submit(command));
        assertEquals(Optional.of(current), replay.before());
        assertEquals(activated, applied(replay, true));
        assertInstanceOf(MemoryMutationResult.Conflict.class, port.submit(new MemoryCommand.AcceptRevision(
                context(SESSION), command.idempotencyKey(), current.ref(), 1, proposal.ref(), 2)));
        assertEquals(archived, findCurrent(port, context(SESSION), current.ref()).orElseThrow());
        assertEquals(activated, findCurrent(port, context(SESSION), proposal.ref()).orElseThrow());
    }

    @Test
    protected final void acceptRevisionStaleProposalNeverArchivesCurrentAndWrongOwnerNeverActivates() {
        MemoryFact current = createFact("accept-stale-current", context(SESSION), draft(SESSION, MemoryScope.SESSION,
                FactStatus.ACTIVE, NOW.minusSeconds(60), Optional.empty(), "original", 0.8));
        MemoryFact proposal = createFact("accept-stale-proposal", context(SESSION), draft(SESSION, MemoryScope.SESSION,
                FactStatus.PENDING_CONFIRM, NOW.minusSeconds(10), Optional.empty(), "corrected", 0.8, Optional.of(current.ref())));
        MemoryFact rejected = applied(port.submit(transition("reject-before-accept", proposal, FactStatus.REJECTED)), false);
        var conflict = assertInstanceOf(MemoryMutationResult.Conflict.class, port.submit(new MemoryCommand.AcceptRevision(
                context(SESSION), new IdempotencyKey("accept-stale-proposal-operation"), current.ref(), 1, proposal.ref(), 1)));
        assertEquals(proposal.ref(), conflict.fact());
        assertEquals(2, conflict.currentRevision());
        assertEquals(current, findCurrent(port, context(SESSION), current.ref()).orElseThrow());
        assertEquals(rejected, findCurrent(port, context(SESSION), proposal.ref()).orElseThrow());
        MemoryFact otherOwner = createFact("accept-owner-mismatch", context(SESSION), draft(SESSION, MemoryScope.USER_AGENT,
                FactStatus.PENDING_CONFIRM, NOW.minusSeconds(10), Optional.empty(), "wrong owner", 0.8, Optional.of(current.ref())));
        assertInstanceOf(MemoryMutationResult.Rejected.class, port.submit(new MemoryCommand.AcceptRevision(context(SESSION),
                new IdempotencyKey("accept-wrong-owner-operation"), current.ref(), 1, otherOwner.ref(), 1)));
        assertEquals(current, findCurrent(port, context(SESSION), current.ref()).orElseThrow());
        assertEquals(otherOwner, findCurrent(port, context(SESSION), otherOwner.ref()).orElseThrow());
    }

    /** 提案尚未生效、到期或失效时拒绝原子接受，两个head都保持提交前状态。 */
    @Test
    protected final void acceptRevisionRejectsFutureEndedAndExpiredProposalsWithoutChangingEitherHead() {
        MemoryFact current = createFact("accept-temporal-current", context(SESSION), draft(SESSION, MemoryScope.SESSION,
                FactStatus.ACTIVE, NOW.minusSeconds(60), Optional.empty(), "original", 0.8));
        record Window(String name, Instant from, Optional<Instant> to, Optional<Instant> expires) { }
        List<Window> windows = List.of(
                new Window("future", Instant.parse("2100-01-01T00:00:00Z"), Optional.empty(), Optional.empty()),
                new Window("ended", NOW.minusSeconds(60), Optional.of(NOW), Optional.empty()),
                new Window("expired", NOW.minusSeconds(60), Optional.empty(), Optional.of(NOW)));
        for (Window window : windows) {
            MemoryFactDraft base = draft(SESSION, MemoryScope.SESSION, FactStatus.PENDING_CONFIRM,
                    window.from(), window.to(), "corrected-" + window.name(), 0.8, Optional.of(current.ref()));
            MemoryFactDraft proposalDraft = new MemoryFactDraft(base.owner(), base.type(), base.retentionClass(),
                    base.schemaVersion(), base.subject(), base.predicate(), base.object(), base.sourceEventIds(),
                    base.sourceSessionRef(), base.sourceExecutionRef(), base.exportPath(), base.extractorVersion(),
                    base.validFrom(), base.validTo(), window.expires(), base.confidence(), base.importance(),
                    base.reviewRequired(), base.derivedFrom(), base.initialStatus());
            MemoryFact proposal = createFact("accept-temporal-proposal-" + window.name(), context(SESSION), proposalDraft);

            var rejected = assertInstanceOf(MemoryMutationResult.Rejected.class, port.submit(new MemoryCommand.AcceptRevision(
                    context(SESSION), new IdempotencyKey("accept-temporal-" + window.name()), current.ref(), 1, proposal.ref(), 1)));

            assertEquals("memory_revision_not_effective", rejected.failure().reasonCode(), window.name());
            assertEquals(current, findCurrent(port, context(SESSION), current.ref()).orElseThrow(), window.name());
            assertEquals(proposal, findCurrent(port, context(SESSION), proposal.ref()).orElseThrow(), window.name());
        }
    }

    @Test
    protected final void competingAcceptedRevisionsHaveOneAtomicWinner() throws Exception {
        MemoryFact current = createFact("accept-race-current", context(SESSION), draft(SESSION, MemoryScope.SESSION,
                FactStatus.ACTIVE, NOW.minusSeconds(60), Optional.empty(), "original", 0.8));
        MemoryFact first = createFact("accept-race-first", context(SESSION), draft(SESSION, MemoryScope.SESSION,
                FactStatus.PENDING_CONFIRM, NOW.minusSeconds(10), Optional.empty(), "first proposal", 0.8, Optional.of(current.ref())));
        MemoryFact second = createFact("accept-race-second", context(SESSION), draft(SESSION, MemoryScope.SESSION,
                FactStatus.PENDING_CONFIRM, NOW.minusSeconds(10), Optional.empty(), "second proposal", 0.8, Optional.of(current.ref())));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<MemoryMutationResult> outcomes = new ArrayList<>();
        try (var executor = ContractTestExecutors.fixedPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<MemoryMutationResult>>();
            for (MemoryFact proposal : List.of(first, second)) futures.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return port.submit(new MemoryCommand.AcceptRevision(context(SESSION), new IdempotencyKey("accept-race-" + proposal.ref().factId()),
                        current.ref(), 1, proposal.ref(), 1));
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) outcomes.add(future.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, outcomes.stream().filter(MemoryMutationResult.Applied.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter(MemoryMutationResult.Conflict.class::isInstance).count());
        MemoryFact oldHead = findCurrent(port, context(SESSION), current.ref()).orElseThrow();
        MemoryFact firstHead = findCurrent(port, context(SESSION), first.ref()).orElseThrow();
        MemoryFact secondHead = findCurrent(port, context(SESSION), second.ref()).orElseThrow();
        assertEquals(FactStatus.ARCHIVED, oldHead.status());
        assertEquals(1, List.of(firstHead, secondHead).stream().filter(fact -> fact.status() == FactStatus.ACTIVE).count());
        assertEquals(1, List.of(firstHead, secondHead).stream().filter(fact -> fact.status() == FactStatus.PENDING_CONFIRM).count());
        assertEquals(List.of(firstHead, secondHead).stream().filter(fact -> fact.status() == FactStatus.ACTIVE).findFirst().orElseThrow().ref(),
                oldHead.supersededBy().orElseThrow());
    }

    @Test
    protected final void transitionUsesRevisionCasAndRejectsIllegalStateEdges() {
        MemoryFact current = createFact("transition-current", context(SESSION),
                draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.minusSeconds(10), Optional.empty(), "project alpha", 0.8));
        MemoryCommand.Transition decay = transition("transition-decay", current,
                FactStatus.DECAYED);
        MemoryMutationResult.Applied first = assertInstanceOf(MemoryMutationResult.Applied.class,
                port.submit(decay));
        assertEquals(Optional.of(current), first.before());
        MemoryFact decayed = applied(first, false);
        assertEquals(2, decayed.revision());
        assertEquals(FactStatus.DECAYED, decayed.status());

        MemoryMutationResult.Conflict stale = assertInstanceOf(MemoryMutationResult.Conflict.class,
                port.submit(new MemoryCommand.Transition(context(SESSION),
                        new IdempotencyKey("transition-stale"), current.ref(), 1,
                        FactStatus.FORGOTTEN, "stale")));
        assertEquals(2, stale.currentRevision());
        assertInstanceOf(MemoryMutationResult.Rejected.class,
                port.submit(new MemoryCommand.Transition(context(SESSION),
                        new IdempotencyKey("transition-illegal"), decayed.ref(), 2,
                        FactStatus.ARCHIVED, "illegal")));
        MemoryFact active = applied(port.submit(new MemoryCommand.Transition(context(SESSION),
                new IdempotencyKey("transition-reactivate"), decayed.ref(), 2,
                FactStatus.ACTIVE, "restated")), false);
        assertEquals(3, active.revision());
        MemoryMutationResult.Applied replay = assertInstanceOf(MemoryMutationResult.Applied.class,
                recreatePort().submit(decay));
        assertEquals(Optional.of(current), replay.before());
        assertEquals(decayed, applied(replay, true));
    }

    @Test
    protected final void concurrentSameKeyHasOneFirstWinnerAndOneReplay() throws Exception {
        MemoryCommand.Create command = create("concurrent-create", context(SESSION),
                draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.minusSeconds(1), Optional.empty(), "project alpha", 0.8));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = ContractTestExecutors.fixedPool(2)) {
            List<java.util.concurrent.Future<MemoryMutationResult>> results = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return port.submit(command);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<MemoryMutationResult.Applied> applied = results.stream().map(future -> {
                try {
                    return assertInstanceOf(MemoryMutationResult.Applied.class, future.get());
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }).toList();
            assertEquals(1, applied.stream().filter(value -> !value.replayed()).count());
            assertEquals(1, applied.stream().filter(MemoryMutationResult.Applied::replayed).count());
            assertEquals(1, applied.stream().map(value -> value.fact().ref()).distinct().count());
        }
    }

    @Test
    protected final void preservesNanosecondTimesAndRejectsCancelledCalls() {
        MemoryFact fact = createFact("nano-create", context(SESSION),
                draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                        NOW.minusNanos(123), Optional.of(NOW.plusNanos(456)),
                        "project alpha", 0.8));
        assertStoreTime(fact.createdAt());
        assertEquals(NOW.minusNanos(123), fact.validFrom());
        assertEquals(Optional.of(NOW.plusNanos(456)), fact.validTo());

        PortCallContext cancelled = context(SESSION, () -> true);
        assertInstanceOf(MemoryMutationResult.Rejected.class,
                port.submit(create("cancelled-create", cancelled,
                        draft(SESSION, MemoryScope.SESSION, FactStatus.ACTIVE,
                                NOW, Optional.empty(), "project alpha", 0.8))));
        assertInstanceOf(MemoryQueryOutcome.Failed.class,
                port.query(new MemoryQueryCall(cancelled,
                        query(SESSION, Set.of(MemoryScope.SESSION), MemoryQueryProfile.DEFAULT,
                                "project", 10, 10_000, 10_000))));
    }

    private MemoryFact createFact(String key, PortCallContext context, MemoryFactDraft draft) {
        return applied(port.submit(create(key, context, draft)), false);
    }

    private static MemoryCommand.Create create(
            String key, PortCallContext context, MemoryFactDraft draft) {
        return new MemoryCommand.Create(context, new IdempotencyKey(key), draft);
    }

    private static MemoryCommand.Transition transition(
            String key, MemoryFact fact, FactStatus target) {
        return new MemoryCommand.Transition(context(fact.sourceSessionRef()),
                new IdempotencyKey(key), fact.ref(), fact.revision(), target, key);
    }

    private static MemoryFact applied(MemoryMutationResult result, boolean replayed) {
        MemoryMutationResult.Applied applied = assertInstanceOf(
                MemoryMutationResult.Applied.class, result);
        assertEquals(replayed, applied.replayed());
        return applied.fact();
    }

    private static MemoryQueryOutcome.Retrieved retrieved(MemoryQueryOutcome result) {
        return assertInstanceOf(MemoryQueryOutcome.Retrieved.class, result);
    }

    private static MemoryQuery query(SessionRef session, Set<MemoryScope> scopes,
            MemoryQueryProfile profile, String intent, int maxItems,
            int tokenBudget, int characterBudget) {
        return new MemoryQuery("memory-query-" + intent.replace(' ', '-'), new MemoryOwner.Session(session),
                Optional.empty(), intent, Optional.empty(), scopes, Set.of(TYPE), profile, NOW, MemoryRetrievalPolicy.lexicalV1(),
                maxItems, tokenBudget, characterBudget, true);
    }

    private static MemoryFactDraft draft(SessionRef source, MemoryScope scope, FactStatus status,
            Instant validFrom, Optional<Instant> validTo, String value, double importance) {
        return draft(source, scope, status, validFrom, validTo, value, importance, Optional.empty());
    }

    private static MemoryFactDraft draft(SessionRef source, MemoryScope scope, FactStatus status,
            Instant validFrom, Optional<Instant> validTo, String value, double importance,
            Optional<MemoryFactRef> derivedFrom) {
        return new MemoryFactDraft(switch (scope) {
                    case SESSION -> new MemoryOwner.Session(source);
                    case USER_AGENT -> new MemoryOwner.UserAgent(TENANT, USER, AGENT);
                    case USER -> new MemoryOwner.User(TENANT, USER);
                    case GROUP, GROUP_MEMBER -> throw new IllegalArgumentException("personal fixture scope");
                }, TYPE, new RetentionClassRef("standard"), "1",
                value, "remembers", new TypedPayload("memory.preference.v1",
                        "{\"value\":\"" + value + "\"}"),
                List.of(new EventId("source-" + source.sessionId())), source,
                Optional.empty(), Optional.empty(), "extractor-v1", validFrom, validTo,
                Optional.empty(), 0.9, importance, status == FactStatus.PENDING_CONFIRM,
                derivedFrom, status);
    }

    private static MemoryFactDraft draftObject(
            SessionRef source, String subject, String objectCanonicalJson) {
        return new MemoryFactDraft(new MemoryOwner.Session(source), TYPE, new RetentionClassRef("standard"), "1",
                subject, "remembers", new TypedPayload("memory.preference.v1", objectCanonicalJson),
                List.of(new EventId("source-" + source.sessionId())), source,
                Optional.empty(), Optional.empty(), "extractor-v1", NOW.minusSeconds(60),
                Optional.empty(), Optional.empty(), 0.9, 0.8, false, Optional.empty(),
                FactStatus.ACTIVE);
    }

    protected static MemoryQuery groupQuery(GroupBindingRef binding,
            Optional<EndUserId> subject, Set<MemoryScope> scopes) {
        return new MemoryQuery("memory-group-query-" + binding.groupBindingId(),
                new MemoryOwner.Group(binding), subject, "prefers tea", Optional.empty(), scopes, Set.of(TYPE),
                MemoryQueryProfile.DEFAULT, NOW, MemoryRetrievalPolicy.lexicalV1(), 10, 10_000, 10_000, true);
    }

    protected static MemoryFactDraft groupDraft(MemoryOwner owner, SessionRef source,
            FactStatus status, String value, Optional<MemoryFactRef> derivedFrom) {
        return new MemoryFactDraft(owner, TYPE, new RetentionClassRef("standard"), "1",
                value, "remembers", new TypedPayload("memory.preference.v1", "{\"value\":\"" + value + "\"}"),
                List.of(new EventId("source-" + source.sessionId())), source,
                Optional.empty(), Optional.empty(), "extractor-v1", NOW.minusSeconds(60),
                Optional.empty(), Optional.empty(), 0.9, 0.8,
                status == FactStatus.PENDING_CONFIRM, derivedFrom, status);
    }

    protected static PortCallContext groupContext(SessionRef session, GroupBindingRef binding,
            EndUserId requester, AgentId agent) {
        SessionOwner owner = new SessionOwner(session, SessionSubject.group(binding), SessionTarget.group(binding));
        TraceEnvelope trace = new TraceEnvelope("memory-group-trace", "memory-group-span",
                new InputId("memory-group-input-" + session.sessionId()), Optional.empty());
        RunIdentity run = new RunIdentity(owner, agent, trace.rootInputId(),
                new RunRef(TENANT, "memory-group-run-" + session.sessionId()), trace,
                new ResultRef("memory-group-causation-" + session.sessionId()),
                new RunRequester.EndUser(requester), Optional.empty(),
                new RunOriginRef(TENANT, trace.rootInputId().value()));
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(TENANT, requester), owner,
                Set.of("runtime:memory"), 1, new ResultRef("memory-group-verification-" + session.sessionId()));
        return new PortCallContext(run, identity,
                new InvocationControl(Instant.parse("2100-01-01T00:00:00Z"), () -> false));
    }

    protected static PortCallContext context(SessionRef session) {
        return context(session, AGENT, () -> false);
    }

    protected static PortCallContext context(SessionRef session, AgentId agent) {
        return context(session, agent, () -> false);
    }

    private static PortCallContext context(SessionRef session,
            com.skillforge.runtime.api.port.CancellationSignal cancellation) {
        return context(session, AGENT, cancellation);
    }

    private static PortCallContext context(SessionRef session, AgentId agent,
            com.skillforge.runtime.api.port.CancellationSignal cancellation) {
        SessionOwner owner = SessionOwner.user(session, agent, USER);
        TraceEnvelope trace = new TraceEnvelope("memory-trace", "memory-span",
                new InputId("memory-input-" + session.sessionId()), Optional.empty());
        RunIdentity run = new RunIdentity(owner, owner.target().requireAgent(), trace.rootInputId(), new RunRef(TENANT, "memory-run-" + session.sessionId()), trace, new ResultRef("memory-causation-" + session.sessionId()), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), trace.rootInputId().value()));
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(TENANT, USER), owner,
                Set.of("runtime:memory"), 1,
                new ResultRef("memory-verification-" + session.sessionId()));
        return new PortCallContext(run, identity,
                new InvocationControl(Instant.parse("2100-01-01T00:00:00Z"), cancellation));
    }
}
