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
import com.skillforge.runtime.api.ExecutionMode;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.DelegationRef;
import com.skillforge.runtime.api.port.ExecutionLedgerPort;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.CreateExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionCancelResult;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLease;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLeaseRequest;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLeaseResult;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLookup;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecoveryScan;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionWait;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionWriteResult;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ProgressExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.RequestExecutionCancel;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ResumeExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.TransitionExecution;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.api.port.LeaseMutationStatus;
import com.skillforge.runtime.api.port.LedgerEvent;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.PublicationAcknowledge;
import com.skillforge.runtime.api.port.PublicationClaimRequest;
import com.skillforge.runtime.api.port.PublicationMutationResult;
import com.skillforge.runtime.api.port.PublicationRetry;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.StateEventClaim;
import com.skillforge.runtime.api.port.StateEventPublication;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 所有 {@link ExecutionLedgerPort} 实现共享的状态、租约、幂等与 publication 契约。 */
public abstract class ExecutionLedgerPortContract {

    protected static final Instant INITIAL_TIME =
            Instant.parse("2026-09-09T10:11:12.123456789Z");
    protected static final Duration LEASE_DURATION = Duration.ofSeconds(2);

    private static final Instant CALLER_EVENT_TIME =
            Instant.parse("2024-01-02T03:04:05.987654321Z");
    private static final String STATE_SCHEMA =
            "skillforge.runtime.execution-state.v1";
    private static final String MUTATION_SCHEMA =
            "skillforge.runtime.execution-ledger-mutation.v1";
    private static final String CANCEL_SCHEMA =
            "skillforge.runtime.execution-cancel-requested.v1";
    private static final String CANCEL_MUTATION_SCHEMA =
            "skillforge.runtime.execution-ledger-cancel.v1";

    private ExecutionLedgerFixture fixture;
    private ExecutionLedgerPort ledger;

    /** 每项测试返回全新空账本，并暴露真实存储时间及向前推进能力。 */
    protected abstract ExecutionLedgerFixture createFixture(Instant initialTime);

    /** 组合被测 Port 与其权威存储时钟。 */
    protected interface ExecutionLedgerFixture {
        ExecutionLedgerPort ledger();
        /** 为需要复合外键核验的持久适配器建立只读 owner Run；内存实现可为空操作。 */
        void establishRun(RunIdentity owner);
        Instant storageTime();
        void advance(Duration duration);
    }

    @BeforeEach
    protected final void createEmptyLedger() {
        fixture = Objects.requireNonNull(createFixture(INITIAL_TIME));
        ledger = Objects.requireNonNull(fixture.ledger());
        TenantId emptyTenant = new TenantId("tenant-empty");
        assertTrue(ledger.scanRecoverable(
                new ExecutionRecoveryScan(emptyTenant, 10, Optional.empty())).isEmpty());
        assertTrue(ledger.claimStateEvents(new PublicationClaimRequest(
                emptyTenant, "empty-worker", 10, LEASE_DURATION)).isEmpty());
    }

    @Test
    protected final void createUsesStorageClockAndFreezesCompleteProjection() {
        CreateExecution command = createCommand("create-完整-🧭");
        Instant before = fixture.storageTime();

        ExecutionRecord created = applied(create(command));

        Instant after = fixture.storageTime();
        assertEquals(command.executionRef(), created.executionRef());
        assertEquals(command.owner(), created.owner());
        assertEquals(WorkflowExecutionState.QUEUED, created.state());
        assertEquals(1, created.executionRevision());
        assertEquals(command.initialCheckpoint(), created.checkpoint());
        assertTrue(created.waiting().isEmpty());
        assertTrue(created.resultRef().isEmpty());
        assertTrue(created.reasonCode().isEmpty());
        assertTrue(created.lease().isEmpty());
        assertTrue(created.cancelRequestId().isEmpty());
        assertEquals(created.createdAt(), created.updatedAt());
        assertFalse(created.createdAt().isBefore(before));
        assertFalse(created.createdAt().isAfter(after));
        assertNotEquals(command.queuedEvent().sessionEvent().occurredAt(), created.createdAt());
        assertEquals(Optional.of(created), ledger.find(new ExecutionLookup(
                identity(created, "find-create"), command.executionRef())));

        StateEventClaim claim = onlyClaim(created.executionRef().tenantId(), "projector-create");
        StateEventPublication publication = claim.publication();
        assertEquals("execution", publication.sourceKind());
        assertEquals(created.executionRef().executionId(), publication.sourceId());
        assertEquals(1, publication.sourceRevision());
        assertEquals(2, publication.publicationRevision());
        assertEquals(Set.of(), publication.runtimeEventDestinationIds());
        assertEquals(created.owner().owner(), publication.projectionTarget().owner());
        assertEquals(created.owner().causationInputId(),
                publication.projectionTarget().inputId());
        assertEquals(Optional.of(created.owner().runRef()),
                publication.projectionTarget().runRef());
        assertEquals(Optional.of(created.executionRef()),
                publication.projectionTarget().executionRef());
        assertEquals(created.causativeActionRef(), publication.projectionTarget().actionRef());
        assertEquals(created.delegationRef(), publication.projectionTarget().delegationRef());
        assertEquals(command.queuedEvent(), publication.event());
        assertEquals(
                "{\"cancelRequestId\":null,\"executionId\":\"execution-create-完整-🧭\","
                        + "\"reasonCode\":null,\"resultRef\":null,\"revision\":\"1\","
                        + "\"state\":\"QUEUED\",\"waiting\":null}",
                publication.event().sessionEvent().payload().canonicalJson());
    }

    @Test
    protected final void createReplayRequiresSameCommandAndCurrentOutput() {
        CreateExecution original = createCommand("create-replay");
        ExecutionRecord queued = applied(create(original));

        assertEquals(queued, replayed(create(original)));
        CreateExecution drift = recreateWithDefinition(
                original, "definition-drift", "create-replay-drift");
        assertFailure(
                assertInstanceOf(ExecutionWriteResult.Rejected.class,
                        create(drift)).failure(),
                "execution_mutation_conflict");

        LeasedExecution running = startExisting(queued, "create-replay");
        ExecutionWriteResult.Conflict advanced = assertInstanceOf(
                ExecutionWriteResult.Conflict.class, create(original));
        assertEquals(running.record(), advanced.current());
    }

    @Test
    protected final void supportsEveryFrozenOrdinaryStateEdgeAndFieldMatrix() {
        ExecutionRecord queuedCancelled = transitionApplied(
                leasedQueued("queued-cancelled"), WorkflowExecutionState.CANCELLED,
                "queued-cancelled");
        assertTerminal(queuedCancelled, WorkflowExecutionState.CANCELLED, false);

        for (WorkflowExecutionState target : List.of(
                WorkflowExecutionState.WAITING_HITL,
                WorkflowExecutionState.WAITING_EXTERNAL,
                WorkflowExecutionState.COMPLETED,
                WorkflowExecutionState.FAILED,
                WorkflowExecutionState.LOOP_TERMINATED,
                WorkflowExecutionState.CANCELLED)) {
            String label = "running-to-" + target.name().toLowerCase(java.util.Locale.ROOT);
            LeasedExecution running = start(label);
            ExecutionRecord output = transitionApplied(running, target, label);
            assertEquals(target, output.state());
            assertEquals(running.record().executionRevision() + 1,
                    output.executionRevision());
            if (target.isWaiting()) {
                assertTrue(output.waiting().isPresent());
                assertTrue(output.resultRef().isPresent());
                assertTrue(output.reasonCode().isEmpty());
                assertEquals(running.record().lease(), output.lease());
            } else {
                assertTerminal(output, target,
                        target == WorkflowExecutionState.FAILED
                                || target == WorkflowExecutionState.LOOP_TERMINATED);
            }
        }

        LeasedExecution waiting = waitForHitl("waiting-cancelled");
        ExecutionRecord cancelled = transitionApplied(
                waiting, WorkflowExecutionState.CANCELLED, "waiting-cancelled");
        assertTerminal(cancelled, WorkflowExecutionState.CANCELLED, false);
    }

    @Test
    protected final void progressIsRunningLeaseCasAndEventAtomic() {
        LeasedExecution running = start("progress");
        ProgressExecution command = progressCommand(running, "progress-event", "after");

        ExecutionRecord progressed = applied(ledger.progress(command));

        assertEquals(WorkflowExecutionState.RUNNING, progressed.state());
        assertEquals(running.record().executionRevision() + 1,
                progressed.executionRevision());
        assertEquals(checkpoint("after"), progressed.checkpoint());
        assertEquals(running.record().lease(), progressed.lease());
        assertEquals(progressed, replayed(ledger.progress(command)));

        ProgressExecution drift = progressCommand(
                new LeasedExecution(running.identity(), running.lease(), running.record()),
                "progress-event", "drift");
        assertFailure(assertInstanceOf(
                ExecutionWriteResult.Rejected.class, ledger.progress(drift)).failure(),
                "execution_mutation_conflict");

        ProgressExecution invalid = progressCommand(
                new LeasedExecution(running.identity(), running.lease(), progressed),
                "progress-invalid", "invalid");
        ProgressExecution invalidPayload = new ProgressExecution(
                invalid.lease(), invalid.expectedRevision(), invalid.checkpoint(),
                withPrivatePayload(invalid.stepEvent(), MUTATION_SCHEMA, "{\"drift\":true}"));
        assertFailure(assertInstanceOf(
                ExecutionWriteResult.Rejected.class,
                ledger.progress(invalidPayload)).failure(), "execution_event_mismatch");
        assertEquals(Optional.of(progressed), ledger.find(new ExecutionLookup(
                running.identity(), progressed.executionRef())));

        List<StateEventPublication> publications = drainPublications(
                progressed.executionRef().tenantId(), "progress-drain");
        assertEquals(List.of(1L, 2L, 3L), publications.stream()
                .map(StateEventPublication::sourceRevision).toList());
        assertEquals(List.of("execution.queued", "execution.started", "execution.step"),
                publications.stream().map(value -> value.event().sessionEvent().eventType())
                        .toList());
        assertTrue(ledger.claimStateEvents(new PublicationClaimRequest(
                progressed.executionRef().tenantId(), "after-invalid", 10,
                LEASE_DURATION)).isEmpty());
    }

    @Test
    protected final void leaseLifecycleUsesStorageTimeWithoutDomainMutationOrPublication() {
        ExecutionRecord queued = created("lease");
        VerifiedIdentity identity = identity(queued, "lease");
        Instant before = fixture.storageTime();
        ExecutionLeaseRequest request = new ExecutionLeaseRequest(
                identity, queued.executionRef(), "worker-a", LEASE_DURATION,
                CALLER_EVENT_TIME);
        ExecutionLeaseResult.Acquired first = assertInstanceOf(
                ExecutionLeaseResult.Acquired.class, ledger.acquireLease(request));
        Instant after = fixture.storageTime();
        assertEquals(queued.executionRevision(), first.execution().executionRevision());
        assertEquals(queued.state(), first.execution().state());
        assertEquals(queued.checkpoint(), first.execution().checkpoint());
        assertFalse(first.lease().fence().leaseUntil().isBefore(before.plus(LEASE_DURATION)));
        assertFalse(first.lease().fence().leaseUntil().isAfter(after.plus(LEASE_DURATION)));
        assertNotEquals(request.requestedAt().plus(LEASE_DURATION),
                first.lease().fence().leaseUntil());
        assertInstanceOf(ExecutionLeaseResult.Busy.class, ledger.acquireLease(
                new ExecutionLeaseRequest(identity, queued.executionRef(), "worker-b",
                        LEASE_DURATION, CALLER_EVENT_TIME.plusSeconds(1))));

        assertEquals(LeaseMutationStatus.APPLIED,
                ledger.renew(first.lease(), LEASE_DURATION));
        assertEquals(LeaseMutationStatus.ALREADY_APPLIED,
                ledger.renew(first.lease(), LEASE_DURATION));
        ExecutionRecord renewed = ledger.find(
                new ExecutionLookup(identity, queued.executionRef())).orElseThrow();
        assertEquals(queued.executionRevision(), renewed.executionRevision());
        assertEquals(first.lease().fence().leaseUntil().plus(LEASE_DURATION),
                renewed.lease().orElseThrow().leaseUntil());

        assertEquals(LeaseMutationStatus.APPLIED, ledger.release(first.lease()));
        assertEquals(LeaseMutationStatus.ALREADY_APPLIED, ledger.release(first.lease()));
        ExecutionRecord released = ledger.find(
                new ExecutionLookup(identity, queued.executionRef())).orElseThrow();
        assertTrue(released.lease().isEmpty());
        assertEquals(queued.executionRevision(), released.executionRevision());

        ExecutionLease second = acquired(ledger.acquireLease(new ExecutionLeaseRequest(
                identity, queued.executionRef(), "worker-b", LEASE_DURATION,
                CALLER_EVENT_TIME))).lease();
        assertEquals(LeaseMutationStatus.STALE_LEASE, ledger.release(first.lease()));
        fixture.advance(LEASE_DURATION.plusMillis(100));
        ExecutionLease takeover = acquired(ledger.acquireLease(new ExecutionLeaseRequest(
                identity, queued.executionRef(), "worker-c", LEASE_DURATION,
                CALLER_EVENT_TIME))).lease();
        assertNotEquals(second.fence().fencingToken(), takeover.fence().fencingToken());
        assertEquals(LeaseMutationStatus.STALE_LEASE, ledger.release(second));
        assertEquals(queued.executionRevision(), ledger.find(
                new ExecutionLookup(identity, queued.executionRef())).orElseThrow()
                .executionRevision());

        List<StateEventClaim> claims = ledger.claimStateEvents(new PublicationClaimRequest(
                queued.executionRef().tenantId(), "lease-publication", 10, LEASE_DURATION));
        assertEquals(1, claims.size());
    }

    @Test
    protected final void leaseBindsExactClaimRevisionAcrossDomainProgress() {
        LeasedExecution running = start("lease-claim-revision");
        ExecutionRecord progressed = applied(ledger.progress(
                progressCommand(running, "lease-claim-progress", "progressed")));

        assertEquals(LeaseMutationStatus.APPLIED, ledger.release(running.lease()));
        assertEquals(LeaseMutationStatus.ALREADY_APPLIED, ledger.release(running.lease()));

        ExecutionLeaseResult.Acquired reacquired = assertInstanceOf(
                ExecutionLeaseResult.Acquired.class,
                ledger.acquireLease(new ExecutionLeaseRequest(
                        running.identity(), progressed.executionRef(), "revision-worker",
                        LEASE_DURATION, CALLER_EVENT_TIME)));
        ExecutionLease valid = reacquired.lease();
        ExecutionLease forged = new ExecutionLease(
                valid.executionRef(), valid.fence(), valid.executionRevision() - 1);

        assertEquals(LeaseMutationStatus.STALE_LEASE,
                ledger.renew(forged, LEASE_DURATION));
        assertEquals(LeaseMutationStatus.STALE_LEASE, ledger.release(forged));
        ProgressExecution forgedProgress = progressCommand(
                new LeasedExecution(running.identity(), forged, reacquired.execution()),
                "forged-lease-progress", "forged");
        assertInstanceOf(ExecutionWriteResult.LeaseLost.class,
                ledger.progress(forgedProgress));
        assertEquals(Optional.of(reacquired.execution()), ledger.find(
                new ExecutionLookup(running.identity(), progressed.executionRef())));
        assertEquals(LeaseMutationStatus.APPLIED, ledger.release(valid));
    }

    @Test
    protected final void cancelCoexistsWithWorkerLeaseAndUsesCompleteIdentityClosure() {
        ExecutionRecord queued = created("cancel");
        VerifiedIdentity identity = identityWithUtf8Scopes(queued, "cancel");
        ExecutionLeaseResult.Acquired acquired = acquired(ledger.acquireLease(
                new ExecutionLeaseRequest(
                        identity, queued.executionRef(), "cancel-worker", LEASE_DURATION,
                        CALLER_EVENT_TIME)));
        ExecutionLease lease = acquired.lease();
        RequestExecutionCancel command = cancelCommand(
                queued, identity, "cancel-request", "cancel-event");
        RequestExecutionCancel invalidEvent = new RequestExecutionCancel(
                command.identity(), command.executionRef(), command.expectedRevision(),
                command.cancelRequestId(), withPrivatePayload(
                        command.cancelEvent(), CANCEL_MUTATION_SCHEMA, "{\"drift\":true}"));
        assertFailure(assertInstanceOf(
                ExecutionCancelResult.Rejected.class,
                ledger.requestCancel(invalidEvent)).failure(), "execution_event_mismatch");
        assertEquals(Optional.of(acquired.execution()), ledger.find(
                new ExecutionLookup(identity, queued.executionRef())));
        Instant before = fixture.storageTime();

        ExecutionRecord cancelledIntent = cancelApplied(ledger.requestCancel(command));

        Instant after = fixture.storageTime();
        assertEquals(WorkflowExecutionState.QUEUED, cancelledIntent.state());
        assertEquals(2, cancelledIntent.executionRevision());
        assertEquals(Optional.of(command.cancelRequestId()),
                cancelledIntent.cancelRequestId());
        assertEquals(Optional.of(lease.fence()), cancelledIntent.lease());
        assertFalse(cancelledIntent.cancelRequestedAt().orElseThrow().isBefore(before));
        assertFalse(cancelledIntent.cancelRequestedAt().orElseThrow().isAfter(after));
        assertEquals(cancelledIntent,
                cancelReplayed(ledger.requestCancel(command)));
        assertTrue(command.cancelEvent().privatePayload().canonicalJson().contains(
                "\"scopes\":[\"scope-a\",\"scope-\",\"scope-𐀀\"]"));

        RequestExecutionCancel drift = cancelCommand(
                queued, identity, "cancel-request", "cancel-event",
                new ResultRef("verification-drift"));
        assertFailure(assertInstanceOf(
                ExecutionCancelResult.Rejected.class,
                ledger.requestCancel(drift)).failure(), "execution_mutation_conflict");

        List<StateEventPublication> cancelPublications = drainPublications(
                queued.executionRef().tenantId(), "cancel-publication");
        assertEquals(List.of(1L, 2L), cancelPublications.stream()
                .map(StateEventPublication::sourceRevision).toList());
        assertEquals(List.of("execution.queued", "execution.cancel_requested"),
                cancelPublications.stream()
                        .map(value -> value.event().sessionEvent().eventType()).toList());
        assertEquals(command.cancelEvent(), cancelPublications.get(1).event());

        TransitionExecution start = transitionCommand(
                cancelledIntent, lease, WorkflowExecutionState.RUNNING,
                "cancel-worker-start");
        ExecutionRecord running = applied(ledger.transition(start));
        assertEquals(Optional.of(command.cancelRequestId()), running.cancelRequestId());
        assertEquals(Optional.of(lease.fence()), running.lease());
        ExecutionCancelResult.Conflict oldReplay = assertInstanceOf(
                ExecutionCancelResult.Conflict.class, ledger.requestCancel(command));
        assertEquals(running, oldReplay.current());
    }

    @Test
    protected final void resumeCommitsTwoRevisionsAndTwoOrderedEventsAtomically() {
        LeasedExecution waiting = waitForHitl("resume");
        ResumeExecution command = resumeCommand(waiting, "resume", "resumed-checkpoint");

        ExecutionRecord running = applied(ledger.resume(command));

        assertEquals(WorkflowExecutionState.RUNNING, running.state());
        assertEquals(waiting.record().executionRevision() + 2,
                running.executionRevision());
        assertEquals(checkpoint("resumed-checkpoint"), running.checkpoint());
        assertTrue(running.waiting().isEmpty());
        assertTrue(running.resultRef().isEmpty());
        assertTrue(running.reasonCode().isEmpty());
        assertEquals(waiting.record().lease(), running.lease());
        assertEquals(command.resumedEvent().privatePayload(),
                command.startedEvent().privatePayload());
        assertEquals(command.resumedEvent().ledgerFingerprint(),
                command.startedEvent().ledgerFingerprint());
        assertEquals(running, replayed(ledger.resume(command)));

        List<StateEventPublication> publications = drainPublications(
                running.executionRef().tenantId(), "resume-drain");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), publications.stream()
                .map(StateEventPublication::sourceRevision).toList());
        assertEquals(List.of(
                "execution.queued", "execution.started", "execution.hitl",
                "execution.resumed", "execution.started"),
                publications.stream().map(value -> value.event().sessionEvent().eventType())
                        .toList());
        assertEquals(command.resumedEvent().sessionEvent().eventId(),
                publications.get(3).event().sessionEvent().eventId());
        assertEquals(command.startedEvent().sessionEvent().eventId(),
                publications.get(4).event().sessionEvent().eventId());
    }

    @Test
    protected final void invalidResumeSecondEventAndWaitDriftRollBackEverything() {
        LeasedExecution waiting = waitForHitl("resume-atomic");
        ResumeExecution valid = resumeCommand(waiting, "resume-atomic", "resume-after");
        LedgerEvent invalidStarted = withPrivatePayload(
                valid.startedEvent(), MUTATION_SCHEMA, "{\"drift\":true}");
        ResumeExecution invalid = new ResumeExecution(
                valid.lease(), valid.expectedRevision(), valid.expectedSuspendRef(),
                valid.expectedActionKey(), valid.checkpoint(), valid.resumedEvent(),
                invalidStarted, valid.expectedState());

        assertFailure(assertInstanceOf(
                ExecutionWriteResult.Rejected.class, ledger.resume(invalid)).failure(),
                "execution_event_mismatch");
        assertEquals(Optional.of(waiting.record()), ledger.find(new ExecutionLookup(
                waiting.identity(), waiting.record().executionRef())));
        ResumeExecution waitDrift = resumeCommand(
                waiting, "resume-wait-drift", "resume-after",
                "different-suspend", waiting.record().waiting().orElseThrow().actionKey());
        assertInstanceOf(ExecutionWriteResult.Conflict.class, ledger.resume(waitDrift));

        List<StateEventPublication> publications = drainPublications(
                waiting.record().executionRef().tenantId(), "resume-atomic-drain");
        assertEquals(List.of(1L, 2L, 3L), publications.stream()
                .map(StateEventPublication::sourceRevision).toList());
    }

    @Test
    protected final void publicationIsStrictHeadOfLineAcrossTwoWorkersAndTakeover() throws Exception {
        LeasedExecution running = start("publication-hol");
        ExecutionRecord progressed = applied(ledger.progress(progressCommand(
                running, "publication-step", "publication-step")));
        TenantId tenantId = progressed.executionRef().tenantId();
        PublicationClaimRequest workerA = new PublicationClaimRequest(
                tenantId, "projector-a", 10, LEASE_DURATION);
        PublicationClaimRequest workerB = new PublicationClaimRequest(
                tenantId, "projector-b", 10, LEASE_DURATION);

        List<List<StateEventClaim>> raced = raceSuppliers(List.of(
                () -> ledger.claimStateEvents(workerA),
                () -> ledger.claimStateEvents(workerB)));
        List<StateEventClaim> winners = raced.stream().flatMap(List::stream).toList();
        assertEquals(1, winners.size());
        StateEventClaim first = winners.getFirst();
        assertEquals(1, first.publication().sourceRevision());
        assertTrue(ledger.claimStateEvents(workerA).isEmpty());
        acknowledge(first, "hol-first");

        StateEventClaim second = onlyClaim(tenantId, "projector-b");
        assertEquals(2, second.publication().sourceRevision());
        fixture.advance(LEASE_DURATION.plusMillis(100));
        StateEventClaim takeover = onlyClaim(tenantId, "projector-a");
        assertEquals(second.publication().sourceRevision(),
                takeover.publication().sourceRevision());
        assertNotEquals(second.fence().fencingToken(), takeover.fence().fencingToken());

        PublicationMutationResult.StaleLease stale = assertInstanceOf(
                PublicationMutationResult.StaleLease.class,
                ledger.acknowledgeStateEvent(acknowledgeCommand(second, "stale")));
        assertFailure(stale.failure(), "execution_publication_stale_lease");
        acknowledge(takeover, "hol-second");
        StateEventClaim third = onlyClaim(tenantId, "projector-b");
        assertEquals(3, third.publication().sourceRevision());
    }

    @Test
    protected final void publicationRetryAndAcknowledgeUseFencedCasAndExactReplay() {
        ExecutionRecord queued = created("publication-mutation");
        StateEventClaim first = onlyClaim(
                queued.executionRef().tenantId(), "projector-retry");
        Instant availableAt = fixture.storageTime().plus(LEASE_DURATION);
        PublicationRetry retry = new PublicationRetry(
                first.publication().tenantId(), "execution",
                first.publication().sourceId(),
                first.publication().event().sessionEvent().eventId(), first.fence(),
                first.publication().publicationRevision(), availableAt,
                failureValue("projection_transient"));

        StateEventPublication retried = assertInstanceOf(
                PublicationMutationResult.Applied.class,
                ledger.retryStateEvent(retry)).publication();
        assertEquals(3, retried.publicationRevision());
        assertEquals(availableAt, retried.availableAt());
        assertEquals(retried, assertInstanceOf(
                PublicationMutationResult.Replayed.class,
                ledger.retryStateEvent(retry)).publication());

        PublicationRetry drift = new PublicationRetry(
                retry.tenantId(), retry.sourceKind(), retry.sourceId(), retry.eventId(),
                retry.fence(), retry.expectedPublicationRevision(),
                availableAt.plusSeconds(1), retry.failure());
        PublicationMutationResult.StaleLease conflict = assertInstanceOf(
                PublicationMutationResult.StaleLease.class,
                ledger.retryStateEvent(drift));
        assertFailure(conflict.failure(), "execution_publication_mutation_conflict");
        assertTrue(ledger.claimStateEvents(new PublicationClaimRequest(
                queued.executionRef().tenantId(), "too-early", 10,
                LEASE_DURATION)).isEmpty());

        fixture.advance(LEASE_DURATION.plusMillis(100));
        StateEventClaim reclaimed = onlyClaim(
                queued.executionRef().tenantId(), "projector-ack");
        PublicationMutationResult.StaleLease advancedReplay = assertInstanceOf(
                PublicationMutationResult.StaleLease.class,
                ledger.retryStateEvent(retry));
        assertFailure(advancedReplay.failure(), "execution_publication_stale_lease");
        PublicationAcknowledge acknowledge = acknowledgeCommand(reclaimed, "projection-ok");
        StateEventPublication acknowledged = assertInstanceOf(
                PublicationMutationResult.Applied.class,
                ledger.acknowledgeStateEvent(acknowledge)).publication();
        assertEquals(5, acknowledged.publicationRevision());
        assertEquals(acknowledged, assertInstanceOf(
                PublicationMutationResult.Replayed.class,
                ledger.acknowledgeStateEvent(acknowledge)).publication());
        assertInstanceOf(PublicationMutationResult.Terminal.class,
                ledger.retryStateEvent(new PublicationRetry(
                        reclaimed.publication().tenantId(), "execution",
                        reclaimed.publication().sourceId(),
                        reclaimed.publication().event().sessionEvent().eventId(),
                        reclaimed.fence(), reclaimed.publication().publicationRevision(),
                        fixture.storageTime(), failureValue("late-retry"))));
    }

    @Test
    protected final void stableMissingTerminalAndPublicationFailuresDoNotLeakData() {
        CreateExecution command = createCommand("failures");
        ExecutionRecord queued = applied(create(command));
        VerifiedIdentity wrongOwner = identity(created("other-owner"), "wrong-owner");
        assertTrue(ledger.find(new ExecutionLookup(
                wrongOwner, command.executionRef())).isEmpty());

        ExecutionRef missing = new ExecutionRef(
                command.executionRef().tenantId(), "execution-missing");
        ExecutionLeaseResult.Rejected missingLease = assertInstanceOf(
                ExecutionLeaseResult.Rejected.class,
                ledger.acquireLease(new ExecutionLeaseRequest(
                        identity(queued, "missing"), missing, "worker",
                        LEASE_DURATION, CALLER_EVENT_TIME)));
        assertFailure(missingLease.failure(), "execution_not_found");

        LeaseFence fakeFence = new LeaseFence(
                command.executionRef().tenantId(), "missing-claim", "worker", 999,
                fixture.storageTime().plus(LEASE_DURATION));
        PublicationMutationResult.NotFound missingPublication = assertInstanceOf(
                PublicationMutationResult.NotFound.class,
                ledger.acknowledgeStateEvent(new PublicationAcknowledge(
                        command.executionRef().tenantId(), "execution",
                        command.executionRef().executionId(), new EventId("missing-event"),
                        fakeFence, 1, new ResultRef("missing-projection"))));
        assertFailure(missingPublication.failure(), "execution_publication_not_found");

        LeasedExecution leased = startExisting(queued, "failures");
        ExecutionRecord terminal = transitionApplied(
                leased, WorkflowExecutionState.COMPLETED, "failures-completed");
        assertEquals(LeaseMutationStatus.TERMINAL, ledger.renew(
                leased.lease(), LEASE_DURATION));
        ExecutionLeaseResult.Rejected terminalLease = assertInstanceOf(
                ExecutionLeaseResult.Rejected.class,
                ledger.acquireLease(new ExecutionLeaseRequest(
                        leased.identity(), terminal.executionRef(), "terminal-worker",
                        LEASE_DURATION, CALLER_EVENT_TIME)));
        assertFailure(terminalLease.failure(), "execution_terminal");
    }

    @Test
    protected final void recoveryScanIsTenantBoundedAndExcludesTerminalExecutions() {
        ExecutionRecord first = created("recovery-first");
        ExecutionRecord second = created("recovery-second");
        LeasedExecution terminalPath = start("recovery-terminal");
        transitionApplied(terminalPath, WorkflowExecutionState.COMPLETED,
                "recovery-terminal");
        created("recovery-other-tenant", new TenantId("tenant-other"));

        RequestExecutionCancel cancel = cancelCommand(
                first, identity(first, "recovery-cancel"),
                "recovery-cancel", "recovery-cancel-event");
        ExecutionRecord cancelledIntent = cancelApplied(ledger.requestCancel(cancel));
        List<ExecutionLedgerPort.ExecutionRecoveryCandidate> candidates =
                ledger.scanRecoverable(new ExecutionRecoveryScan(
                        first.executionRef().tenantId(), 10, Optional.empty()));

        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().noneMatch(value ->
                value.executionRef().equals(terminalPath.record().executionRef())));
        assertTrue(candidates.stream().anyMatch(value ->
                value.executionRef().equals(cancelledIntent.executionRef())
                        && value.cancelRequestId().equals(cancelledIntent.cancelRequestId())
                        && value.executionRevision() == cancelledIntent.executionRevision()));
        assertTrue(candidates.stream().anyMatch(value ->
                value.executionRef().equals(second.executionRef())));
        assertEquals(1, ledger.scanRecoverable(new ExecutionRecoveryScan(
                first.executionRef().tenantId(), 1, Optional.empty())).size());
        assertEquals(candidates.subList(1, 2), ledger.scanRecoverable(new ExecutionRecoveryScan(
                first.executionRef().tenantId(), 10, Optional.of(candidates.getFirst().executionRef().executionId()))));
    }

    private ExecutionRecord created(String label) {
        return applied(create(createCommand(label)));
    }

    private ExecutionRecord created(String label, TenantId tenantId) {
        return applied(create(createCommand(label, tenantId)));
    }

    private ExecutionWriteResult create(CreateExecution command) {
        fixture.establishRun(command.owner());
        return ledger.create(command);
    }

    private LeasedExecution leasedQueued(String label) {
        ExecutionRecord queued = created(label);
        VerifiedIdentity identity = identity(queued, label);
        ExecutionLeaseResult.Acquired acquired = acquired(ledger.acquireLease(
                new ExecutionLeaseRequest(
                        identity, queued.executionRef(), "worker-" + label,
                        LEASE_DURATION, CALLER_EVENT_TIME)));
        return new LeasedExecution(identity, acquired.lease(), acquired.execution());
    }

    private LeasedExecution start(String label) {
        return startExisting(created(label), label);
    }

    private LeasedExecution startExisting(ExecutionRecord queued, String label) {
        VerifiedIdentity identity = identity(queued, label);
        ExecutionLeaseResult.Acquired acquired = acquired(ledger.acquireLease(
                new ExecutionLeaseRequest(
                        identity, queued.executionRef(), "worker-" + label,
                        LEASE_DURATION, CALLER_EVENT_TIME)));
        LeasedExecution leased = new LeasedExecution(
                identity, acquired.lease(), acquired.execution());
        ExecutionRecord running = transitionApplied(
                leased, WorkflowExecutionState.RUNNING, label + "-started");
        return new LeasedExecution(identity, acquired.lease(), running);
    }

    @Test
    protected final void externalWaitResumesOnceAndCannotBeReinterpretedAsHumanInput() {
        LeasedExecution running = start("external-resume");
        ExecutionRecord record = transitionApplied(running, WorkflowExecutionState.WAITING_EXTERNAL, "external-wait");
        LeasedExecution waiting = new LeasedExecution(running.identity(), running.lease(), record);
        ResumeExecution command = resumeCommand(waiting, "external-resume", "external-completed");
        ResumeExecution wrongState = new ResumeExecution(command.lease(), command.expectedRevision(),
                command.expectedSuspendRef(), command.expectedActionKey(), command.checkpoint(),
                command.resumedEvent(), command.startedEvent(), WorkflowExecutionState.WAITING_HITL);
        assertFalse(ledger.resume(wrongState) instanceof ExecutionWriteResult.Applied);
        assertEquals(Optional.of(record), ledger.find(new ExecutionLookup(waiting.identity(), record.executionRef())));
        ExecutionRecord resumed = applied(ledger.resume(command));
        assertEquals(WorkflowExecutionState.RUNNING, resumed.state());
        assertEquals(record.executionRevision() + 2, resumed.executionRevision());
        assertEquals(resumed, replayed(ledger.resume(command)));
    }

    private LeasedExecution waitForHitl(String label) {
        LeasedExecution running = start(label);
        ExecutionRecord waiting = transitionApplied(
                running, WorkflowExecutionState.WAITING_HITL, label + "-waiting");
        return new LeasedExecution(running.identity(), running.lease(), waiting);
    }

    private ExecutionRecord transitionApplied(
            LeasedExecution source, WorkflowExecutionState target, String label) {
        return applied(ledger.transition(transitionCommand(
                source.record(), source.lease(), target, label)));
    }

    private StateEventClaim onlyClaim(TenantId tenantId, String workerId) {
        List<StateEventClaim> claims = ledger.claimStateEvents(
                new PublicationClaimRequest(
                        tenantId, workerId, 10, LEASE_DURATION));
        assertEquals(1, claims.size());
        return claims.getFirst();
    }

    private List<StateEventPublication> drainPublications(
            TenantId tenantId, String workerPrefix) {
        List<StateEventPublication> drained = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            List<StateEventClaim> claims = ledger.claimStateEvents(
                    new PublicationClaimRequest(
                            tenantId, workerPrefix + '-' + index, 100,
                            LEASE_DURATION));
            if (claims.isEmpty()) {
                return List.copyOf(drained);
            }
            for (StateEventClaim claim : claims) {
                drained.add(claim.publication());
                acknowledge(claim, workerPrefix + '-' + index);
            }
        }
        throw new AssertionError("publication drain did not terminate");
    }

    private void acknowledge(StateEventClaim claim, String label) {
        assertInstanceOf(PublicationMutationResult.Applied.class,
                ledger.acknowledgeStateEvent(acknowledgeCommand(claim, label)));
    }

    private static PublicationAcknowledge acknowledgeCommand(
            StateEventClaim claim, String label) {
        StateEventPublication publication = claim.publication();
        return new PublicationAcknowledge(
                publication.tenantId(), publication.sourceKind(), publication.sourceId(),
                publication.event().sessionEvent().eventId(), claim.fence(),
                publication.publicationRevision(), new ResultRef("projection-" + label));
    }

    private static CreateExecution createCommand(String label) {
        return createCommand(label, new TenantId("tenant-execution-contract"));
    }

    private static CreateExecution createCommand(String label, TenantId tenantId) {
        ExecutionRef executionRef = new ExecutionRef(tenantId, "execution-" + label);
        SessionOwner sessionOwner = SessionOwner.user(
                new SessionRef(tenantId, "session-" + label),
                new AgentId("agent-" + label), new EndUserId(101));
        TraceEnvelope trace = new TraceEnvelope(
                "trace-" + label, "span-" + label,
                new InputId("root-" + label),
                Optional.of(new EventId("cause-" + label)));
        RunIdentity owner = new RunIdentity(sessionOwner, sessionOwner.target().requireAgent(), new InputId("input-" + label), new RunRef(tenantId, "run-" + label), trace, new ResultRef("causation-identity-" + label), new com.skillforge.runtime.api.RunRequester.EndUser(sessionOwner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(sessionOwner.sessionRef().tenantId(), new InputId("input-" + label).value()));
        String executionUnitId = "workflow-unit-" + label;
        String definitionVersion = "definition-" + label;
        Optional<ActionRef> action = Optional.of(
                new ActionRef(tenantId, "action-" + label));
        Optional<DelegationRef> delegation = Optional.of(
                new DelegationRef(tenantId, "delegation-" + label));
        TypedPayload checkpoint = checkpoint("initial-" + label);
        String requestFingerprint = "request-fingerprint-" + label;
        String privateBody = createMutationBody(
                executionRef, owner, executionUnitId, definitionVersion,
                ExecutionMode.BACKGROUND, action, delegation, checkpoint,
                requestFingerprint);
        LedgerEvent event = event(
                "queued-" + label, "execution.queued", trace,
                STATE_SCHEMA,
                stateBody(executionRef, 1, WorkflowExecutionState.QUEUED,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                MUTATION_SCHEMA, privateBody);
        return new CreateExecution(
                executionRef, owner, executionUnitId, definitionVersion,
                ExecutionMode.BACKGROUND, action, delegation, checkpoint,
                requestFingerprint, event);
    }

    private static CreateExecution recreateWithDefinition(
            CreateExecution source, String definitionVersion, String eventLabel) {
        String privateBody = createMutationBody(
                source.executionRef(), source.owner(), source.executionUnitId(),
                definitionVersion, source.mode(), source.causativeActionRef(),
                source.delegationRef(), source.initialCheckpoint(),
                source.requestFingerprint());
        LedgerEvent event = event(
                "queued-" + eventLabel, "execution.queued", source.owner().trace(),
                STATE_SCHEMA,
                stateBody(source.executionRef(), 1, WorkflowExecutionState.QUEUED,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                MUTATION_SCHEMA, privateBody);
        return new CreateExecution(
                source.executionRef(), source.owner(), source.executionUnitId(),
                definitionVersion, source.mode(), source.causativeActionRef(),
                source.delegationRef(), source.initialCheckpoint(),
                source.requestFingerprint(), event);
    }

    private static TransitionExecution transitionCommand(
            ExecutionRecord source,
            ExecutionLease lease,
            WorkflowExecutionState target,
            String label) {
        Optional<ExecutionWait> waiting = target.isWaiting()
                ? Optional.of(new ExecutionWait(
                        "suspend-" + label, "action-key-" + label))
                : Optional.empty();
        Optional<ResultRef> result = switch (target) {
            case WAITING_HITL, WAITING_EXTERNAL, COMPLETED, FAILED, LOOP_TERMINATED, CANCELLED ->
                    Optional.of(new ResultRef("result-" + label));
            case QUEUED, RUNNING, RESUMING -> Optional.empty();
        };
        Optional<String> reason = target == WorkflowExecutionState.FAILED
                        || target == WorkflowExecutionState.LOOP_TERMINATED
                ? Optional.of("reason-" + label) : Optional.empty();
        TypedPayload checkpoint = checkpoint("transition-" + label);
        String eventType = switch (target) {
            case RUNNING -> "execution.started";
            case WAITING_HITL -> "execution.hitl";
            case WAITING_EXTERNAL -> "execution.waiting";
            case COMPLETED -> "execution.completed";
            case FAILED -> "execution.failed";
            case LOOP_TERMINATED -> "execution.loop_terminated";
            case CANCELLED -> "execution.cancelled";
            case QUEUED, RESUMING -> throw new IllegalArgumentException(
                    "target has no ordinary transition event");
        };
        long outputRevision = source.executionRevision() + 1;
        String privateBody = transitionMutationBody(
                lease, source.state(), source.executionRevision(), target,
                checkpoint, waiting, result, reason);
        LedgerEvent event = event(
                "transition-" + label, eventType, source.owner().trace(),
                STATE_SCHEMA,
                stateBody(source.executionRef(), outputRevision, target, waiting,
                        result, reason, source.cancelRequestId()),
                MUTATION_SCHEMA, privateBody);
        return new TransitionExecution(
                lease, source.state(), source.executionRevision(), target,
                checkpoint, waiting, result, reason, event);
    }

    private static ProgressExecution progressCommand(
            LeasedExecution source, String eventLabel, String checkpointLabel) {
        TypedPayload checkpoint = checkpoint(checkpointLabel);
        long outputRevision = source.record().executionRevision() + 1;
        String privateBody = progressMutationBody(
                source.lease(), source.record().executionRevision(), checkpoint);
        LedgerEvent event = event(
                eventLabel, "execution.step", source.record().owner().trace(),
                STATE_SCHEMA,
                stateBody(source.record().executionRef(), outputRevision,
                        WorkflowExecutionState.RUNNING, Optional.empty(), Optional.empty(),
                        Optional.empty(), source.record().cancelRequestId()),
                MUTATION_SCHEMA, privateBody);
        return new ProgressExecution(
                source.lease(), source.record().executionRevision(), checkpoint, event);
    }

    private static RequestExecutionCancel cancelCommand(
            ExecutionRecord source,
            VerifiedIdentity identity,
            String cancelRequestLabel,
            String eventLabel) {
        return cancelCommand(
                source, identity, cancelRequestLabel, eventLabel,
                identity.verificationRef());
    }

    private static RequestExecutionCancel cancelCommand(
            ExecutionRecord source,
            VerifiedIdentity identity,
            String cancelRequestLabel,
            String eventLabel,
            ResultRef verificationRef) {
        VerifiedIdentity effectiveIdentity = new VerifiedIdentity(
                identity.principal(), identity.owner(), identity.scopes(),
                identity.policyRevision(), verificationRef);
        InputId cancelRequestId = new InputId(cancelRequestLabel);
        long outputRevision = source.executionRevision() + 1;
        String privateBody = cancelMutationBody(
                effectiveIdentity, source.executionRef(), source.executionRevision(),
                cancelRequestId);
        LedgerEvent event = event(
                eventLabel, "execution.cancel_requested", source.owner().trace(),
                CANCEL_SCHEMA,
                cancelBody(source.executionRef(), outputRevision, source.state(),
                        cancelRequestId),
                CANCEL_MUTATION_SCHEMA, privateBody);
        return new RequestExecutionCancel(
                effectiveIdentity, source.executionRef(), source.executionRevision(),
                cancelRequestId, event);
    }

    private static ResumeExecution resumeCommand(
            LeasedExecution waiting, String eventLabel, String checkpointLabel) {
        ExecutionWait expected = waiting.record().waiting().orElseThrow();
        return resumeCommand(
                waiting, eventLabel, checkpointLabel,
                expected.suspendRef(), expected.actionKey());
    }

    private static ResumeExecution resumeCommand(
            LeasedExecution waiting,
            String eventLabel,
            String checkpointLabel,
            String expectedSuspendRef,
            String expectedActionKey) {
        TypedPayload checkpoint = checkpoint(checkpointLabel);
        String privateBody = resumeMutationBody(
                waiting.lease(), waiting.record().executionRevision(),
                expectedSuspendRef, expectedActionKey, checkpoint, waiting.record().state());
        LedgerEvent resumed = event(
                eventLabel + "-resumed", "execution.resumed",
                waiting.record().owner().trace(), STATE_SCHEMA,
                stateBody(waiting.record().executionRef(),
                        waiting.record().executionRevision() + 1,
                        WorkflowExecutionState.RESUMING, Optional.empty(), Optional.empty(),
                        Optional.empty(), waiting.record().cancelRequestId()),
                MUTATION_SCHEMA, privateBody);
        LedgerEvent started = event(
                eventLabel + "-started", "execution.started",
                waiting.record().owner().trace(), STATE_SCHEMA,
                stateBody(waiting.record().executionRef(),
                        waiting.record().executionRevision() + 2,
                        WorkflowExecutionState.RUNNING, Optional.empty(), Optional.empty(),
                        Optional.empty(), waiting.record().cancelRequestId()),
                MUTATION_SCHEMA, privateBody);
        return new ResumeExecution(
                waiting.lease(), waiting.record().executionRevision(),
                expectedSuspendRef, expectedActionKey, checkpoint, resumed, started, waiting.record().state());
    }

    private static VerifiedIdentity identity(ExecutionRecord execution, String label) {
        SessionOwner owner = execution.owner().owner();
        return new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(
                        execution.executionRef().tenantId(), owner.requireUser()),
                owner, Set.of("execution.read", "execution.write"), 7,
                new ResultRef("verification-" + label));
    }

    private static VerifiedIdentity identityWithUtf8Scopes(
            ExecutionRecord execution, String label) {
        SessionOwner owner = execution.owner().owner();
        return new VerifiedIdentity(
                new PrincipalRef.ServicePrincipal(
                        execution.executionRef().tenantId(), "service-" + label,
                        "integration-" + label, 3),
                owner, Set.of("scope-a", "scope-\uE000", "scope-\uD800\uDC00"), 9,
                new ResultRef("verification-" + label));
    }

    private static String createMutationBody(
            ExecutionRef executionRef,
            RunIdentity owner,
            String executionUnitId,
            String definitionVersion,
            ExecutionMode mode,
            Optional<ActionRef> action,
            Optional<DelegationRef> delegation,
            TypedPayload checkpoint,
            String requestFingerprint) {
        return mutationBody("CREATE", object(
                "causativeActionRef", action.map(
                        ExecutionLedgerPortContract::actionRefValue).orElse(null),
                "definitionVersion", definitionVersion,
                "delegationRef", delegation.map(
                        ExecutionLedgerPortContract::delegationRefValue).orElse(null),
                "executionRef", executionRefValue(executionRef),
                "executionUnitId", executionUnitId,
                "initialCheckpoint", payloadValue(checkpoint),
                "mode", mode.name(),
                "owner", runIdentityValue(owner),
                "requestFingerprint", requestFingerprint));
    }

    private static String progressMutationBody(
            ExecutionLease lease, long expectedRevision, TypedPayload checkpoint) {
        return mutationBody("PROGRESS", object(
                "checkpoint", payloadValue(checkpoint),
                "expectedRevision", Long.toString(expectedRevision),
                "lease", leaseValue(lease)));
    }

    private static String transitionMutationBody(
            ExecutionLease lease,
            WorkflowExecutionState expectedState,
            long expectedRevision,
            WorkflowExecutionState targetState,
            TypedPayload checkpoint,
            Optional<ExecutionWait> waiting,
            Optional<ResultRef> result,
            Optional<String> reason) {
        return mutationBody("TRANSITION", object(
                "checkpoint", payloadValue(checkpoint),
                "expectedRevision", Long.toString(expectedRevision),
                "expectedState", expectedState.name(),
                "lease", leaseValue(lease),
                "reasonCode", reason.orElse(null),
                "resultRef", result.map(ResultRef::value).orElse(null),
                "targetState", targetState.name(),
                "waiting", waiting.map(
                        ExecutionLedgerPortContract::waitValue).orElse(null)));
    }

    private static String resumeMutationBody(
            ExecutionLease lease,
            long expectedRevision,
            String expectedSuspendRef,
            String expectedActionKey,
            TypedPayload checkpoint, WorkflowExecutionState expectedState) {
        return mutationBody("RESUME", object(
                "checkpoint", payloadValue(checkpoint),
                "expectedActionKey", expectedActionKey,
                "expectedRevision", Long.toString(expectedRevision),
                "expectedSuspendRef", expectedSuspendRef,
                "expectedState", expectedState.name(),
                "lease", leaseValue(lease)));
    }

    private static String cancelMutationBody(
            VerifiedIdentity identity,
            ExecutionRef executionRef,
            long expectedRevision,
            InputId cancelRequestId) {
        return json(object(
                "command", object(
                        "cancelRequestId", cancelRequestId.value(),
                        "executionRef", executionRefValue(executionRef),
                        "expectedRevision", Long.toString(expectedRevision),
                        "identity", verifiedIdentityValue(identity)),
                "commandType", "CANCEL"));
    }

    private static String mutationBody(String commandType, Object command) {
        return json(object("command", command, "commandType", commandType));
    }

    private static String stateBody(
            ExecutionRef executionRef,
            long revision,
            WorkflowExecutionState state,
            Optional<ExecutionWait> waiting,
            Optional<ResultRef> result,
            Optional<String> reason,
            Optional<InputId> cancelRequestId) {
        return json(object(
                "cancelRequestId", cancelRequestId.map(InputId::value).orElse(null),
                "executionId", executionRef.executionId(),
                "reasonCode", reason.orElse(null),
                "resultRef", result.map(ResultRef::value).orElse(null),
                "revision", Long.toString(revision),
                "state", state.name(),
                "waiting", waiting.map(
                        ExecutionLedgerPortContract::waitValue).orElse(null)));
    }

    private static String cancelBody(
            ExecutionRef executionRef,
            long revision,
            WorkflowExecutionState state,
            InputId cancelRequestId) {
        return json(object(
                "cancelRequestId", cancelRequestId.value(),
                "executionId", executionRef.executionId(),
                "revision", Long.toString(revision),
                "state", state.name()));
    }

    private static LedgerEvent event(
            String eventId,
            String eventType,
            TraceEnvelope trace,
            String publicSchema,
            String publicBody,
            String privateSchema,
            String privateBody) {
        SessionEventDraft sessionEvent = new SessionEventDraft(
                new EventId(eventId), eventType, "1",
                hashPayload(publicSchema, publicBody), trace,
                new TypedPayload(publicSchema, publicBody), CALLER_EVENT_TIME);
        return new LedgerEvent(
                sessionEvent, eventType, hashPayload(privateSchema, privateBody),
                new TypedPayload(privateSchema, privateBody));
    }

    private static LedgerEvent withPrivatePayload(
            LedgerEvent source, String schema, String body) {
        return new LedgerEvent(
                source.sessionEvent(), source.ledgerEventType(),
                hashPayload(schema, body), new TypedPayload(schema, body));
    }

    private static Object runIdentityValue(RunIdentity identity) {
        return object(
                "causationIdentityRef", identity.causationIdentityRef().value(),
                "causationInputId", identity.causationInputId().value(),
                "agentId", identity.agentId().value(),
                "requester", identity.requester() instanceof com.skillforge.runtime.api.RunRequester.EndUser user
                        ? object("kind", "END_USER", "endUserId", Long.toString(user.endUserId().value()))
                        : object("kind", "SYSTEM", "principal", principalValue(((com.skillforge.runtime.api.RunRequester.System) identity.requester()).principal())),
                "sourceMessageRef", identity.sourceMessageRef().map(source -> object("sessionId", source.sessionRef().sessionId(),
                        "tenantId", source.sessionRef().tenantId().value(), "messageId", source.messageId())).orElse(null),
                "originRef", object("tenantId", identity.originRef().tenantId().value(), "originId", identity.originRef().originId()),
                "owner", ownerValue(identity.owner()),
                "runRef", object(
                        "runId", identity.runRef().runId(),
                        "tenantId", identity.runRef().tenantId().value()),
                "trace", traceValue(identity.trace()));
    }

    private static Object verifiedIdentityValue(VerifiedIdentity identity) {
        return object(
                "owner", ownerValue(identity.owner()),
                "policyRevision", Long.toString(identity.policyRevision()),
                "principal", principalValue(identity.principal()),
                "scopes", identity.scopes().stream()
                        .sorted(ExecutionLedgerPortContract::compareUtf8Unsigned).toList(),
                "verificationRef", identity.verificationRef().value());
    }

    private static Object principalValue(PrincipalRef principal) {
        return switch (principal) {
            case PrincipalRef.EndUserPrincipal value -> object(
                    "endUserId", Long.toString(value.endUserId().value()),
                    "kind", "END_USER", "tenantId", value.tenantId().value());
            case PrincipalRef.ServicePrincipal value -> object(
                    "credentialVersion", Long.toString(value.credentialVersion()),
                    "integrationId", value.integrationId(), "kind", "SERVICE",
                    "principalId", value.principalId(),
                    "tenantId", value.tenantId().value());
            case PrincipalRef.SystemPrincipal value -> object(
                    "kind", "SYSTEM", "principalId", value.principalId(),
                    "tenantId", value.tenantId().value());
        };
    }

    private static Object ownerValue(SessionOwner owner) {
        return object(
                "targetKind", owner.target().kind().name(),
                "targetId", owner.target().id(),
                "subject", owner.subject() instanceof com.skillforge.runtime.api.SessionSubject.User user
                        ? object("kind", "USER", "endUserId", Long.toString(user.endUserId().value()))
                        : object("kind", "GROUP", "groupBindingId", ((com.skillforge.runtime.api.SessionSubject.Group) owner.subject()).groupBindingRef().groupBindingId()),
                "sessionId", owner.sessionRef().sessionId(),
                "tenantId", owner.sessionRef().tenantId().value());
    }

    private static Object traceValue(TraceEnvelope trace) {
        return object(
                "causedByEventId", trace.causedByEventId().map(EventId::value).orElse(null),
                "rootInputId", trace.rootInputId().value(),
                "spanId", trace.spanId(), "traceId", trace.traceId());
    }

    private static Object leaseValue(ExecutionLease lease) {
        return object(
                "executionRef", executionRefValue(lease.executionRef()),
                "executionRevision", Long.toString(lease.executionRevision()),
                "fence", object(
                        "claimId", lease.fence().claimId(),
                        "fencingToken", Long.toString(lease.fence().fencingToken()),
                        "leaseUntil", lease.fence().leaseUntil().toString(),
                        "tenantId", lease.fence().tenantId().value(),
                        "workerId", lease.fence().workerId()));
    }

    private static Object executionRefValue(ExecutionRef ref) {
        return object("executionId", ref.executionId(),
                "tenantId", ref.tenantId().value());
    }

    private static Object actionRefValue(ActionRef ref) {
        return object("actionId", ref.actionId(),
                "tenantId", ref.tenantId().value());
    }

    private static Object delegationRefValue(DelegationRef ref) {
        return object("delegationId", ref.delegationId(),
                "tenantId", ref.tenantId().value());
    }

    private static Object payloadValue(TypedPayload payload) {
        return object("canonicalJson", payload.canonicalJson(),
                "schemaId", payload.schemaId());
    }

    private static Object waitValue(ExecutionWait waiting) {
        return object("actionKey", waiting.actionKey(),
                "suspendRef", waiting.suspendRef());
    }

    private static TypedPayload checkpoint(String label) {
        return new TypedPayload(
                "skillforge.runtime.execution-checkpoint.v1",
                "{\"emoji\":\"🧭\",\"label\":\"" + label + "\"}");
    }

    private static int compareUtf8Unsigned(String first, String second) {
        byte[] left = first.getBytes(StandardCharsets.UTF_8);
        byte[] right = second.getBytes(StandardCharsets.UTF_8);
        int common = Math.min(left.length, right.length);
        for (int index = 0; index < common; index++) {
            int compared = Integer.compare(
                    Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static String hashPayload(String schema, String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((schema + '\n' + body).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    private static String json(Object value) {
        StringBuilder output = new StringBuilder();
        writeJson(value, output);
        return output.toString();
    }

    private static void writeJson(Object value, StringBuilder output) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            output.append('"');
            for (int index = 0; index < text.length(); index++) {
                char current = text.charAt(index);
                if (Character.isHighSurrogate(current)) {
                    if (++index == text.length()
                            || !Character.isLowSurrogate(text.charAt(index))) {
                        throw new IllegalArgumentException("invalid_execution_contract_json");
                    }
                    output.append(current).append(text.charAt(index));
                } else if (Character.isLowSurrogate(current)) {
                    throw new IllegalArgumentException("invalid_execution_contract_json");
                } else if (current == '"' || current == '\\') {
                    output.append('\\').append(current);
                } else if ("\b\t\n\f\r".indexOf(current) >= 0) {
                    output.append('\\').append(
                            "btnfr".charAt("\b\t\n\f\r".indexOf(current)));
                } else if (current < 32) {
                    output.append(String.format(
                            java.util.Locale.ROOT, "\\u%04x", (int) current));
                } else {
                    output.append(current);
                }
            }
            output.append('"');
        } else if (value instanceof List<?> list) {
            output.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                writeJson(item, output);
            }
            output.append(']');
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                writeJson(entry.getKey(), output);
                output.append(':');
                writeJson(entry.getValue(), output);
            }
            output.append('}');
        } else {
            throw new IllegalArgumentException("invalid_execution_contract_json");
        }
    }

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new TreeMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static void assertTerminal(
            ExecutionRecord value, WorkflowExecutionState state, boolean reason) {
        assertEquals(state, value.state());
        assertTrue(value.waiting().isEmpty());
        assertTrue(value.resultRef().isPresent());
        assertEquals(reason, value.reasonCode().isPresent());
        assertTrue(value.lease().isEmpty());
    }

    private static void assertFailure(PortFailure failure, String reasonCode) {
        assertEquals(reasonCode, failure.reasonCode());
        assertEquals(RetryMode.NONE, failure.retryMode());
        assertNotNull(failure.resultRef());
    }

    private static PortFailure failureValue(String reasonCode) {
        return new PortFailure(
                reasonCode, RetryMode.IDEMPOTENT_BY_STABLE_KEY,
                new ResultRef("diagnostic-" + reasonCode));
    }

    private static ExecutionRecord applied(ExecutionWriteResult result) {
        return assertInstanceOf(ExecutionWriteResult.Applied.class, result).execution();
    }

    private static ExecutionRecord replayed(ExecutionWriteResult result) {
        return assertInstanceOf(ExecutionWriteResult.Replayed.class, result).execution();
    }

    private static ExecutionLeaseResult.Acquired acquired(ExecutionLeaseResult result) {
        return assertInstanceOf(ExecutionLeaseResult.Acquired.class, result);
    }

    private static ExecutionRecord cancelApplied(ExecutionCancelResult result) {
        return assertInstanceOf(ExecutionCancelResult.Applied.class, result).execution();
    }

    private static ExecutionRecord cancelReplayed(ExecutionCancelResult result) {
        return assertInstanceOf(ExecutionCancelResult.Replayed.class, result).execution();
    }

    private static <T> List<T> raceSuppliers(List<Supplier<T>> operations)
            throws Exception {
        ExecutorService executor = ContractTestExecutors.fixedPool(operations.size());
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Supplier<T> operation : operations) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return operation.get();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> output = new ArrayList<>();
            for (Future<T> future : futures) {
                output.add(future.get(20, TimeUnit.SECONDS));
            }
            return List.copyOf(output);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private record LeasedExecution(
            VerifiedIdentity identity, ExecutionLease lease, ExecutionRecord record) {
    }
}
