package com.skillforge.runtime.adapter.inmemory.execution;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.DelegationRef;
import com.skillforge.runtime.api.port.ExecutionLedgerPort;
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
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import com.skillforge.runtime.api.port.StateEventClaim;
import com.skillforge.runtime.api.port.StateEventPublication;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 仅供契约测试使用的 Workflow Execution 权威账本参考实现。
 *
 * <p>所有领域写、幂等 journal 与 publication 变更共享一个线性化临界区，用来精确表达
 * production JDBC 事务应保持的不变量；本实现不提供进程重启后的耐久性。
 */
public final class InMemoryExecutionLedgerPort implements ExecutionLedgerPort {


    private static final String STATE_SCHEMA =
            "skillforge.runtime.execution-state.v1";
    private static final String MUTATION_SCHEMA =
            "skillforge.runtime.execution-ledger-mutation.v1";
    private static final String CANCEL_SCHEMA =
            "skillforge.runtime.execution-cancel-requested.v1";
    private static final String CANCEL_MUTATION_SCHEMA =
            "skillforge.runtime.execution-ledger-cancel.v1";

    private final Object monitor = new Object();
    private final Clock clock;
    private final Map<ExecutionKey, StoredExecution> executions = new LinkedHashMap<>();
    private final Map<PublicationKey, StoredPublication> publications = new LinkedHashMap<>();
    private long nextFencingToken = 1;

    /** 显式注入权威存储时钟，便于 profile 装配和确定性验证。 */
    public InMemoryExecutionLedgerPort(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 使用 UTC 系统时钟创建 development-only 实例。 */
    public InMemoryExecutionLedgerPort() {
        this(Clock.systemUTC());
    }

    @Override
    public ExecutionWriteResult create(CreateExecution command) {
        Objects.requireNonNull(command, "command");
        synchronized (monitor) {
            ExecutionKey key = ExecutionKey.from(command.executionRef());
            StoredExecution existing = executions.get(key);
            if (existing != null) {
                if (existing.createCommand.equals(command)) {
                    return existing.current.equals(existing.initialOutput)
                            ? new ExecutionWriteResult.Replayed(existing.initialOutput)
                            : new ExecutionWriteResult.Conflict(existing.current);
                }
                return new ExecutionWriteResult.Rejected(
                        failure("execution_mutation_conflict", key));
            }

            Instant now = clock.instant();
            ExecutionRecord initial = new ExecutionRecord(
                    command.executionRef(), command.owner(), command.executionUnitId(),
                    command.definitionVersion(), command.mode(), command.causativeActionRef(),
                    command.delegationRef(), WorkflowExecutionState.QUEUED, 1,
                    command.initialCheckpoint(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    now, now);
            if (!validStateMatrix(initial)
                    || !matchesStateEvent(command.queuedEvent(), initial, command)) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_event_mismatch", key));
            }
            if (!publicationAvailable(initial, command.queuedEvent())) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_publication_event_conflict", key));
            }

            StoredExecution stored = new StoredExecution(command, initial);
            StoredPublication publication = newPublication(
                    initial, command.queuedEvent(), now);
            executions.put(key, stored);
            putPublication(publication);
            return new ExecutionWriteResult.Applied(initial);
        }
    }

    @Override
    public Optional<ExecutionRecord> find(ExecutionLookup query) {
        Objects.requireNonNull(query, "query");
        synchronized (monitor) {
            StoredExecution stored = executions.get(ExecutionKey.from(query.executionRef()));
            if (stored == null
                    || !stored.current.owner().owner().equals(query.identity().owner())) {
                return Optional.empty();
            }
            return Optional.of(stored.current);
        }
    }

    @Override
    public ExecutionLeaseResult acquireLease(ExecutionLeaseRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (monitor) {
            StoredExecution stored = executions.get(ExecutionKey.from(request.executionRef()));
            if (stored == null
                    || !stored.current.owner().owner().equals(request.identity().owner())) {
                return new ExecutionLeaseResult.Rejected(
                        failure("execution_not_found", request.executionRef()));
            }
            if (terminal(stored.current.state())) {
                return new ExecutionLeaseResult.Rejected(
                        failure("execution_terminal", request.executionRef()));
            }
            Instant now = clock.instant();
            if (stored.current.lease().isPresent()
                    && now.isBefore(stored.current.lease().orElseThrow().leaseUntil())) {
                return new ExecutionLeaseResult.Busy(stored.current);
            }

            long token = nextFence();
            LeaseFence fence = new LeaseFence(
                    request.executionRef().tenantId(),
                    "execution-claim-" + token,
                    request.workerId(), token, now.plus(request.leaseDuration()));
            stored.current = copyExecution(
                    stored.current, stored.current.state(), stored.current.executionRevision(),
                    stored.current.checkpoint(), stored.current.waiting(),
                    stored.current.resultRef(), stored.current.reasonCode(), Optional.of(fence),
                    stored.current.cancelRequestId(), stored.current.cancelRequestedAt(), now);
            stored.leaseExecutionRevision = stored.current.executionRevision();
            stored.lastRenewal = null;
            stored.lastRelease = null;
            ExecutionLease lease = new ExecutionLease(
                    stored.current.executionRef(), fence, stored.current.executionRevision());
            return new ExecutionLeaseResult.Acquired(stored.current, lease);
        }
    }

    @Override
    public LeaseMutationStatus renew(ExecutionLease lease, Duration extension) {
        Objects.requireNonNull(lease, "lease");
        requirePositive(extension, "extension");
        synchronized (monitor) {
            StoredExecution stored = executions.get(ExecutionKey.from(lease.executionRef()));
            if (stored == null) {
                return LeaseMutationStatus.NOT_FOUND;
            }
            if (terminal(stored.current.state())) {
                return LeaseMutationStatus.TERMINAL;
            }
            if (stored.current.lease().isEmpty()
                    || stored.leaseExecutionRevision == null
                    || lease.executionRevision() != stored.leaseExecutionRevision) {
                return LeaseMutationStatus.STALE_LEASE;
            }
            LeaseFence current = stored.current.lease().orElseThrow();
            Instant now = clock.instant();
            Instant target = lease.fence().leaseUntil().plus(extension);
            if (current.equals(lease.fence())) {
                if (!now.isBefore(current.leaseUntil())) {
                    return LeaseMutationStatus.STALE_LEASE;
                }
                LeaseFence renewed = withExpiry(current, target);
                stored.current = copyExecution(
                        stored.current, stored.current.state(), stored.current.executionRevision(),
                        stored.current.checkpoint(), stored.current.waiting(),
                        stored.current.resultRef(), stored.current.reasonCode(), Optional.of(renewed),
                        stored.current.cancelRequestId(), stored.current.cancelRequestedAt(), now);
                stored.lastRenewal = new ExecutionRenewal(lease, extension, renewed);
                return LeaseMutationStatus.APPLIED;
            }
            if (sameLeaseIdentity(current, lease.fence())
                    && current.leaseUntil().equals(target)
                    && now.isBefore(current.leaseUntil())) {
                return LeaseMutationStatus.ALREADY_APPLIED;
            }
            return LeaseMutationStatus.STALE_LEASE;
        }
    }

    @Override
    public LeaseMutationStatus release(ExecutionLease lease) {
        Objects.requireNonNull(lease, "lease");
        synchronized (monitor) {
            StoredExecution stored = executions.get(ExecutionKey.from(lease.executionRef()));
            if (stored == null) {
                return LeaseMutationStatus.NOT_FOUND;
            }
            if (terminal(stored.current.state())) {
                return LeaseMutationStatus.TERMINAL;
            }
            if (stored.current.lease().isEmpty()) {
                return stored.lastRelease != null
                                && stored.lastRelease.lease.equals(lease)
                                && stored.current.equals(stored.lastRelease.output)
                        ? LeaseMutationStatus.ALREADY_APPLIED
                        : LeaseMutationStatus.STALE_LEASE;
            }
            Instant now = clock.instant();
            if (!hasCurrentLease(stored, lease, now)) {
                return LeaseMutationStatus.STALE_LEASE;
            }
            ExecutionRecord output = copyExecution(
                    stored.current, stored.current.state(), stored.current.executionRevision(),
                    stored.current.checkpoint(), stored.current.waiting(),
                    stored.current.resultRef(), stored.current.reasonCode(), Optional.empty(),
                    stored.current.cancelRequestId(), stored.current.cancelRequestedAt(), now);
            stored.current = output;
            stored.leaseExecutionRevision = null;
            stored.lastRelease = new ExecutionRelease(lease, output);
            return LeaseMutationStatus.APPLIED;
        }
    }

    @Override
    public ExecutionCancelResult requestCancel(RequestExecutionCancel command) {
        Objects.requireNonNull(command, "command");
        synchronized (monitor) {
            StoredExecution stored = executions.get(ExecutionKey.from(command.executionRef()));
            if (stored == null
                    || !stored.current.owner().owner().equals(command.identity().owner())) {
                return new ExecutionCancelResult.NotFound(
                        failure("execution_not_found", command.executionRef()));
            }
            CancelOperationKey operationKey = CancelOperationKey.from(command);
            CancelOperation prior = stored.cancelOperations.get(operationKey);
            if (prior != null) {
                if (prior.command.equals(command)) {
                    return stored.current.equals(prior.output)
                            ? new ExecutionCancelResult.Replayed(prior.output)
                            : new ExecutionCancelResult.Conflict(stored.current);
                }
                return new ExecutionCancelResult.Rejected(
                        failure("execution_mutation_conflict", operationKey));
            }
            if (terminal(stored.current.state())) {
                return new ExecutionCancelResult.Rejected(
                        failure("execution_terminal", command.executionRef()));
            }
            if (stored.current.executionRevision() != command.expectedRevision()
                    || stored.current.cancelRequestId().isPresent()) {
                return new ExecutionCancelResult.Conflict(stored.current);
            }
            if (stored.current.executionRevision() == Long.MAX_VALUE) {
                return new ExecutionCancelResult.Rejected(
                        failure("execution_mutation_conflict", operationKey));
            }

            Instant now = clock.instant();
            long revision = stored.current.executionRevision() + 1;
            ExecutionRecord output = copyExecution(
                    stored.current, stored.current.state(), revision,
                    stored.current.checkpoint(), stored.current.waiting(),
                    stored.current.resultRef(), stored.current.reasonCode(), stored.current.lease(),
                    Optional.of(command.cancelRequestId()), Optional.of(now), now);
            if (!matchesCancelEvent(command.cancelEvent(), output, command)) {
                return new ExecutionCancelResult.Rejected(
                        failure("execution_event_mismatch", operationKey));
            }
            if (!publicationAvailable(output, command.cancelEvent())) {
                return new ExecutionCancelResult.Rejected(
                        failure("execution_publication_event_conflict", operationKey));
            }

            StoredPublication publication = newPublication(output, command.cancelEvent(), now);
            stored.current = output;
            stored.cancelOperations.put(operationKey, new CancelOperation(command, output));
            putPublication(publication);
            return new ExecutionCancelResult.Applied(output);
        }
    }

    @Override
    public ExecutionWriteResult progress(ProgressExecution command) {
        Objects.requireNonNull(command, "command");
        return mutate(Mutation.progress(command));
    }

    @Override
    public ExecutionWriteResult transition(TransitionExecution command) {
        Objects.requireNonNull(command, "command");
        return mutate(Mutation.transition(command));
    }

    private ExecutionWriteResult mutate(Mutation mutation) {
        synchronized (monitor) {
            ExecutionLease lease = mutation.lease();
            StoredExecution stored = executions.get(ExecutionKey.from(lease.executionRef()));
            if (stored == null) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_not_found", lease.executionRef()));
            }
            MutationOperationKey operationKey = mutation.key();
            MutationOperation prior = stored.mutations.get(operationKey);
            if (prior != null) {
                if (prior.command.equals(mutation.command)) {
                    return stored.current.equals(prior.output)
                            ? new ExecutionWriteResult.Replayed(prior.output)
                            : new ExecutionWriteResult.Conflict(stored.current);
                }
                return new ExecutionWriteResult.Rejected(
                        failure("execution_mutation_conflict", operationKey));
            }
            if (terminal(stored.current.state())) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_terminal", lease.executionRef()));
            }
            if (stored.current.executionRevision() != mutation.expectedRevision()
                    || stored.current.state() != mutation.expectedState()) {
                return new ExecutionWriteResult.Conflict(stored.current);
            }
            Instant now = clock.instant();
            if (!hasCurrentLease(stored, lease, now)) {
                return new ExecutionWriteResult.LeaseLost(
                        failure("execution_stale_lease", operationKey));
            }
            if (stored.current.executionRevision() == Long.MAX_VALUE) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_mutation_conflict", operationKey));
            }

            long revision = stored.current.executionRevision() + 1;
            WorkflowExecutionState target = mutation.targetState();
            Optional<LeaseFence> outputLease = terminal(target)
                    ? Optional.empty() : stored.current.lease();
            ExecutionRecord output = copyExecution(
                    stored.current, target, revision, mutation.checkpoint(), mutation.waiting(),
                    mutation.resultRef(), mutation.reasonCode(), outputLease,
                    stored.current.cancelRequestId(), stored.current.cancelRequestedAt(), now);
            if (!validStateMatrix(output)
                    || !matchesStateEvent(mutation.event(), output, mutation.command)) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_event_mismatch", operationKey));
            }
            if (!publicationAvailable(output, mutation.event())) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_publication_event_conflict", operationKey));
            }

            StoredPublication publication = newPublication(output, mutation.event(), now);
            stored.current = output;
            if (outputLease.isEmpty()) {
                stored.leaseExecutionRevision = null;
            }
            stored.mutations.put(
                    operationKey, new MutationOperation(mutation.command, output));
            putPublication(publication);
            return new ExecutionWriteResult.Applied(output);
        }
    }

    @Override
    public ExecutionWriteResult resume(ResumeExecution command) {
        Objects.requireNonNull(command, "command");
        synchronized (monitor) {
            ExecutionLease lease = command.lease();
            StoredExecution stored = executions.get(ExecutionKey.from(lease.executionRef()));
            if (stored == null) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_not_found", lease.executionRef()));
            }
            ResumeOperationKey operationKey = ResumeOperationKey.from(command);
            ResumeOperation prior = stored.resumes.get(operationKey);
            if (prior != null) {
                if (prior.command.equals(command)) {
                    return stored.current.equals(prior.output)
                            ? new ExecutionWriteResult.Replayed(prior.output)
                            : new ExecutionWriteResult.Conflict(stored.current);
                }
                return new ExecutionWriteResult.Rejected(
                        failure("execution_mutation_conflict", operationKey));
            }
            if (terminal(stored.current.state())) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_terminal", lease.executionRef()));
            }
            if (stored.current.executionRevision() != command.expectedRevision()
                    || stored.current.state() != command.expectedState()) {
                return new ExecutionWriteResult.Conflict(stored.current);
            }
            Instant now = clock.instant();
            if (!hasCurrentLease(stored, lease, now)) {
                return new ExecutionWriteResult.LeaseLost(
                        failure("execution_stale_lease", operationKey));
            }
            ExecutionWait wait = stored.current.waiting().orElseThrow();
            if (!wait.suspendRef().equals(command.expectedSuspendRef())
                    || !wait.actionKey().equals(command.expectedActionKey())) {
                return new ExecutionWriteResult.Conflict(stored.current);
            }
            if (command.resumedEvent().sessionEvent().eventId()
                    .equals(command.startedEvent().sessionEvent().eventId())) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_event_mismatch", operationKey));
            }

            long resumingRevision = command.expectedRevision() + 1;
            long runningRevision = command.expectedRevision() + 2;
            ExecutionRecord resuming = copyExecution(
                    stored.current, WorkflowExecutionState.RESUMING, resumingRevision,
                    command.checkpoint(), Optional.empty(), Optional.empty(), Optional.empty(),
                    stored.current.lease(), stored.current.cancelRequestId(),
                    stored.current.cancelRequestedAt(), now);
            ExecutionRecord running = copyExecution(
                    resuming, WorkflowExecutionState.RUNNING, runningRevision,
                    command.checkpoint(), Optional.empty(), Optional.empty(), Optional.empty(),
                    resuming.lease(), resuming.cancelRequestId(), resuming.cancelRequestedAt(), now);
            if (!validStateMatrix(resuming) || !validStateMatrix(running)
                    || !matchesStateEvent(command.resumedEvent(), resuming, command)
                    || !matchesStateEvent(command.startedEvent(), running, command)) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_event_mismatch", operationKey));
            }
            if (!publicationAvailable(resuming, command.resumedEvent())
                    || !publicationAvailable(running, command.startedEvent())) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_publication_event_conflict", operationKey));
            }

            StoredPublication resumingPublication = newPublication(
                    resuming, command.resumedEvent(), now);
            StoredPublication runningPublication = newPublication(
                    running, command.startedEvent(), now);
            stored.current = running;
            stored.resumes.put(operationKey, new ResumeOperation(command, running));
            putPublication(resumingPublication);
            putPublication(runningPublication);
            return new ExecutionWriteResult.Applied(running);
        }
    }

    @Override
    public List<ExecutionRecoveryCandidate> scanRecoverable(ExecutionRecoveryScan query) {
        Objects.requireNonNull(query, "query");
        synchronized (monitor) {
            return executions.values().stream()
                    .map(value -> value.current)
                    .filter(value -> value.executionRef().tenantId().equals(query.tenantId()))
                    .filter(value -> !terminal(value.state()))
                    .filter(value -> query.afterExecutionId().map(after -> value.executionRef().executionId().compareTo(after) > 0).orElse(true))
                    .sorted(Comparator.comparing(value -> value.executionRef().executionId()))
                    .limit(query.limit())
                    .map(value -> new ExecutionRecoveryCandidate(
                            value.executionRef(), value.owner(), value.state(),
                            value.executionRevision(), value.lease().map(LeaseFence::leaseUntil),
                            value.cancelRequestId()))
                    .toList();
        }
    }

    @Override
    public List<StateEventClaim> claimStateEvents(PublicationClaimRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (monitor) {
            Instant now = clock.instant();
            Map<ExecutionKey, Long> heads = new HashMap<>();
            for (StoredPublication value : publications.values()) {
                if (value.status != PublicationStatus.ACKNOWLEDGED
                        && value.base.tenantId().equals(request.tenantId())) {
                    ExecutionKey source = new ExecutionKey(
                            value.base.tenantId(), value.base.sourceId());
                    heads.merge(source, value.base.sourceRevision(), Math::min);
                }
            }
            List<StoredPublication> eligible = publications.values().stream()
                    .filter(value -> value.base.tenantId().equals(request.tenantId()))
                    .filter(value -> heads.getOrDefault(
                            new ExecutionKey(value.base.tenantId(), value.base.sourceId()), -1L)
                            == value.base.sourceRevision())
                    .filter(value -> value.status == PublicationStatus.PENDING
                                    && !now.isBefore(value.availableAt)
                            || value.status == PublicationStatus.CLAIMED
                                    && value.lease != null
                                    && !now.isBefore(value.lease.leaseUntil()))
                    .sorted(Comparator
                            .comparing(StoredPublication::effectiveAvailableAt)
                            .thenComparing(value -> value.base.sourceId())
                            .thenComparingLong(value -> value.base.sourceRevision())
                            .thenComparing(value -> value.base.event()
                                    .sessionEvent().eventId().value()))
                    .limit(request.limit())
                    .toList();

            List<StateEventClaim> claimed = new ArrayList<>(eligible.size());
            for (StoredPublication value : eligible) {
                long token = nextFence();
                LeaseFence fence = new LeaseFence(
                        request.tenantId(), "execution-publication-claim-" + token,
                        request.workerId(), token, now.plus(request.leaseDuration()));
                value.status = PublicationStatus.CLAIMED;
                value.revision = Math.incrementExact(value.revision);
                value.attempts = Math.incrementExact(value.attempts);
                value.lease = fence;
                value.updatedAt = now;
                claimed.add(new StateEventClaim(value.snapshot(), fence));
            }
            return List.copyOf(claimed);
        }
    }

    @Override
    public PublicationMutationResult acknowledgeStateEvent(
            PublicationAcknowledge command) {
        Objects.requireNonNull(command, "command");
        return mutatePublication(PublicationMutation.acknowledge(command));
    }

    @Override
    public PublicationMutationResult retryStateEvent(PublicationRetry command) {
        Objects.requireNonNull(command, "command");
        return mutatePublication(PublicationMutation.retry(command));
    }

    private PublicationMutationResult mutatePublication(PublicationMutation mutation) {
        synchronized (monitor) {
            PublicationKey key = new PublicationKey(
                    mutation.tenantId(), mutation.sourceId(), mutation.eventId());
            StoredPublication stored = publications.get(key);
            if (stored == null || !mutation.sourceKind().equals("execution")) {
                return new PublicationMutationResult.NotFound(
                        failure("execution_publication_not_found", key));
            }
            PublicationOperationKey operationKey = mutation.key();
            PublicationOperation prior = stored.operations.get(operationKey);
            if (prior != null) {
                if (!prior.mutation.equals(mutation)) {
                    return new PublicationMutationResult.StaleLease(
                            stored.snapshot(),
                            failure("execution_publication_mutation_conflict", operationKey));
                }
                return stored.state().equals(prior.outputState)
                        ? new PublicationMutationResult.Replayed(prior.output)
                        : new PublicationMutationResult.StaleLease(
                                stored.snapshot(),
                                failure("execution_publication_stale_lease", operationKey));
            }
            if (stored.status == PublicationStatus.ACKNOWLEDGED) {
                return new PublicationMutationResult.Terminal(stored.snapshot());
            }
            Instant now = clock.instant();
            if (stored.status != PublicationStatus.CLAIMED || stored.lease == null
                    || stored.revision != mutation.expectedRevision()
                    || !stored.lease.equals(mutation.fence())
                    || !now.isBefore(stored.lease.leaseUntil())) {
                return new PublicationMutationResult.StaleLease(
                        stored.snapshot(),
                        failure("execution_publication_stale_lease", operationKey));
            }

            if (mutation.kind == PublicationMutationKind.ACKNOWLEDGE) {
                stored.status = PublicationStatus.ACKNOWLEDGED;
                stored.projectionRef = ((PublicationAcknowledge) mutation.command)
                        .projectionRef();
            } else {
                PublicationRetry retry = (PublicationRetry) mutation.command;
                stored.status = PublicationStatus.PENDING;
                stored.availableAt = retry.availableAt();
                stored.lastFailure = retry.failure();
            }
            stored.revision = Math.incrementExact(stored.revision);
            stored.lease = null;
            stored.updatedAt = now;
            StateEventPublication output = stored.snapshot();
            PublicationState outputState = stored.state();
            stored.operations.put(
                    operationKey,
                    new PublicationOperation(mutation, output, outputState));
            return new PublicationMutationResult.Applied(output);
        }
    }

    private boolean publicationAvailable(ExecutionRecord execution, LedgerEvent event) {
        PublicationKey key = PublicationKey.from(
                execution.executionRef(), event.sessionEvent().eventId());
        if (publications.containsKey(key)) {
            return false;
        }
        return publications.values().stream().noneMatch(value ->
                value.base.tenantId().equals(execution.executionRef().tenantId())
                        && value.base.sourceId().equals(execution.executionRef().executionId())
                        && value.base.sourceRevision() == execution.executionRevision());
    }

    private StoredPublication newPublication(
            ExecutionRecord execution, LedgerEvent event, Instant availableAt) {
        SessionProjectionTarget target = new SessionProjectionTarget(
                execution.owner().owner(), execution.owner().causationInputId(),
                Optional.of(execution.owner().runRef()), Optional.of(execution.executionRef()),
                execution.causativeActionRef(), execution.delegationRef());
        ResultRef factRef = new ResultRef(
                "execution-fact:sha256:" + hashParts(
                        execution.executionRef().tenantId().value(),
                        execution.executionRef().executionId(),
                        Long.toString(execution.executionRevision()),
                        event.sessionEvent().eventId().value(), event.ledgerFingerprint()));
        StateEventPublication publication = new StateEventPublication(
                execution.executionRef().tenantId(), "execution",
                execution.executionRef().executionId(), execution.executionRevision(),
                factRef, target, event, Set.of(), 1, availableAt);
        return new StoredPublication(publication, availableAt);
    }

    private void putPublication(StoredPublication publication) {
        publications.put(PublicationKey.from(
                publication.base.projectionTarget().executionRef().orElseThrow(),
                publication.base.event().sessionEvent().eventId()), publication);
    }

    private static boolean matchesStateEvent(
            LedgerEvent event, ExecutionRecord output, Object command) {
        String eventType = switch (command) {
            case CreateExecution ignored -> "execution.queued";
            case ProgressExecution ignored -> "execution.step";
            case TransitionExecution value -> switch (value.targetState()) {
                case RUNNING -> "execution.started";
                case WAITING_HITL -> "execution.hitl";
                case WAITING_EXTERNAL -> "execution.waiting";
                case COMPLETED -> "execution.completed";
                case FAILED -> "execution.failed";
                case LOOP_TERMINATED -> "execution.loop_terminated";
                case CANCELLED -> "execution.cancelled";
                case QUEUED, RESUMING -> throw new IllegalStateException(
                        "unsupported_execution_transition_target");
            };
            case ResumeExecution ignored -> output.state() == WorkflowExecutionState.RESUMING
                    ? "execution.resumed" : "execution.started";
            default -> throw new IllegalArgumentException("unsupported_execution_command");
        };
        return matchesEvent(
                event, eventType, output.owner().trace(), STATE_SCHEMA,
                stateBody(output), MUTATION_SCHEMA, mutationBody(command));
    }

    private static boolean matchesCancelEvent(
            LedgerEvent event, ExecutionRecord output, RequestExecutionCancel command) {
        return matchesEvent(
                event, "execution.cancel_requested", output.owner().trace(),
                CANCEL_SCHEMA, cancelBody(output), CANCEL_MUTATION_SCHEMA,
                cancelMutationBody(command));
    }

    private static boolean matchesEvent(
            LedgerEvent event,
            String eventType,
            TraceEnvelope trace,
            String publicSchema,
            String publicBody,
            String privateSchema,
            String privateBody) {
        SessionEventDraft session = event.sessionEvent();
        return session.eventType().equals(eventType)
                && session.eventVersion().equals("1")
                && session.trace().equals(trace)
                && session.payload().equals(new TypedPayload(publicSchema, publicBody))
                && session.eventFingerprint().equals(hashPayload(publicSchema, publicBody))
                && event.ledgerEventType().equals(eventType)
                && event.privatePayload().equals(new TypedPayload(privateSchema, privateBody))
                && event.ledgerFingerprint().equals(hashPayload(privateSchema, privateBody));
    }

    private static String stateBody(ExecutionRecord output) {
        return json(object(
                "cancelRequestId", output.cancelRequestId().map(InputId::value).orElse(null),
                "executionId", output.executionRef().executionId(),
                "reasonCode", output.reasonCode().orElse(null),
                "resultRef", output.resultRef().map(ResultRef::value).orElse(null),
                "revision", Long.toString(output.executionRevision()),
                "state", output.state().name(),
                "waiting", output.waiting().map(
                        InMemoryExecutionLedgerPort::waitValue).orElse(null)));
    }

    private static String cancelBody(ExecutionRecord output) {
        return json(object(
                "cancelRequestId", output.cancelRequestId().orElseThrow().value(),
                "executionId", output.executionRef().executionId(),
                "revision", Long.toString(output.executionRevision()),
                "state", output.state().name()));
    }

    private static String mutationBody(Object command) {
        String commandType;
        Object commandValue;
        if (command instanceof CreateExecution value) {
            commandType = "CREATE";
            commandValue = createValue(value);
        } else if (command instanceof ProgressExecution value) {
            commandType = "PROGRESS";
            commandValue = object(
                    "checkpoint", payloadValue(value.checkpoint()),
                    "expectedRevision", Long.toString(value.expectedRevision()),
                    "lease", leaseValue(value.lease()));
        } else if (command instanceof TransitionExecution value) {
            commandType = "TRANSITION";
            commandValue = object(
                    "checkpoint", payloadValue(value.checkpoint()),
                    "expectedRevision", Long.toString(value.expectedRevision()),
                    "expectedState", value.expectedState().name(),
                    "lease", leaseValue(value.lease()),
                    "reasonCode", value.reasonCode().orElse(null),
                    "resultRef", value.resultRef().map(ResultRef::value).orElse(null),
                    "targetState", value.targetState().name(),
                    "waiting", value.waiting().map(
                            InMemoryExecutionLedgerPort::waitValue).orElse(null));
        } else if (command instanceof ResumeExecution value) {
            commandType = "RESUME";
            commandValue = object(
                    "checkpoint", payloadValue(value.checkpoint()),
                    "expectedActionKey", value.expectedActionKey(),
                    "expectedRevision", Long.toString(value.expectedRevision()),
                    "expectedSuspendRef", value.expectedSuspendRef(),
                    "expectedState", value.expectedState().name(),
                    "lease", leaseValue(value.lease()));
        } else {
            throw new IllegalArgumentException("unsupported_execution_command");
        }
        return json(object("command", commandValue, "commandType", commandType));
    }

    private static Object createValue(CreateExecution value) {
        return object(
                "causativeActionRef", value.causativeActionRef()
                        .map(InMemoryExecutionLedgerPort::actionRefValue).orElse(null),
                "definitionVersion", value.definitionVersion(),
                "delegationRef", value.delegationRef()
                        .map(InMemoryExecutionLedgerPort::delegationRefValue).orElse(null),
                "executionRef", executionRefValue(value.executionRef()),
                "executionUnitId", value.executionUnitId(),
                "initialCheckpoint", payloadValue(value.initialCheckpoint()),
                "mode", value.mode().name(),
                "owner", runIdentityValue(value.owner()),
                "requestFingerprint", value.requestFingerprint());
    }

    private static String cancelMutationBody(RequestExecutionCancel command) {
        return json(object(
                "command", object(
                        "cancelRequestId", command.cancelRequestId().value(),
                        "executionRef", executionRefValue(command.executionRef()),
                        "expectedRevision", Long.toString(command.expectedRevision()),
                        "identity", verifiedIdentityValue(command.identity())),
                "commandType", "CANCEL"));
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
                        .sorted(InMemoryExecutionLedgerPort::compareUtf8Unsigned)
                        .toList(),
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
                "spanId", trace.spanId(),
                "traceId", trace.traceId());
    }

    private static Object leaseValue(ExecutionLease lease) {
        return object(
                "executionRef", executionRefValue(lease.executionRef()),
                "executionRevision", Long.toString(lease.executionRevision()),
                "fence", fenceValue(lease.fence()));
    }

    private static Object fenceValue(LeaseFence fence) {
        return object(
                "claimId", fence.claimId(),
                "fencingToken", Long.toString(fence.fencingToken()),
                "leaseUntil", fence.leaseUntil().toString(),
                "tenantId", fence.tenantId().value(),
                "workerId", fence.workerId());
    }

    private static Object executionRefValue(ExecutionRef executionRef) {
        return object(
                "executionId", executionRef.executionId(),
                "tenantId", executionRef.tenantId().value());
    }

    private static Object actionRefValue(ActionRef actionRef) {
        return object(
                "actionId", actionRef.actionId(),
                "tenantId", actionRef.tenantId().value());
    }

    private static Object delegationRefValue(DelegationRef delegationRef) {
        return object(
                "delegationId", delegationRef.delegationId(),
                "tenantId", delegationRef.tenantId().value());
    }

    private static Object payloadValue(TypedPayload payload) {
        return object(
                "canonicalJson", payload.canonicalJson(),
                "schemaId", payload.schemaId());
    }

    private static Object waitValue(ExecutionWait wait) {
        return object("actionKey", wait.actionKey(), "suspendRef", wait.suspendRef());
    }

    private static boolean validStateMatrix(ExecutionRecord value) {
        boolean waiting = value.waiting().isPresent();
        boolean result = value.resultRef().isPresent();
        boolean reason = value.reasonCode().isPresent();
        return switch (value.state()) {
            case QUEUED, RUNNING, RESUMING -> !waiting && !result && !reason;
            case WAITING_HITL, WAITING_EXTERNAL -> waiting && result && !reason;
            case COMPLETED, CANCELLED -> !waiting && result && !reason;
            case FAILED, LOOP_TERMINATED -> !waiting && result && reason;
        } && (!terminal(value.state()) || value.lease().isEmpty());
    }

    private static boolean terminal(WorkflowExecutionState state) {
        return state == WorkflowExecutionState.COMPLETED
                || state == WorkflowExecutionState.FAILED
                || state == WorkflowExecutionState.LOOP_TERMINATED
                || state == WorkflowExecutionState.CANCELLED;
    }

    private static boolean hasCurrentLease(
            StoredExecution stored, ExecutionLease supplied, Instant now) {
        ExecutionRecord current = stored.current;
        if (current.lease().isEmpty()
                || stored.leaseExecutionRevision == null
                || supplied.executionRevision() != stored.leaseExecutionRevision) {
            return false;
        }
        LeaseFence authoritative = current.lease().orElseThrow();
        return sameLeaseIdentity(authoritative, supplied.fence())
                && !supplied.fence().leaseUntil().isAfter(authoritative.leaseUntil())
                && now.isBefore(authoritative.leaseUntil());
    }

    private static boolean sameLeaseIdentity(LeaseFence first, LeaseFence second) {
        return first.tenantId().equals(second.tenantId())
                && first.claimId().equals(second.claimId())
                && first.workerId().equals(second.workerId())
                && first.fencingToken() == second.fencingToken();
    }

    private static LeaseFence withExpiry(LeaseFence source, Instant expiry) {
        return new LeaseFence(
                source.tenantId(), source.claimId(), source.workerId(),
                source.fencingToken(), expiry);
    }

    private static ExecutionRecord copyExecution(
            ExecutionRecord source,
            WorkflowExecutionState state,
            long revision,
            TypedPayload checkpoint,
            Optional<ExecutionWait> waiting,
            Optional<ResultRef> resultRef,
            Optional<String> reasonCode,
            Optional<LeaseFence> lease,
            Optional<InputId> cancelRequestId,
            Optional<Instant> cancelRequestedAt,
            Instant updatedAt) {
        return new ExecutionRecord(
                source.executionRef(), source.owner(), source.executionUnitId(),
                source.definitionVersion(), source.mode(), source.causativeActionRef(),
                source.delegationRef(), state, revision, checkpoint, waiting, resultRef,
                reasonCode, lease, cancelRequestId, cancelRequestedAt,
                source.createdAt(), updatedAt);
    }

    private long nextFence() {
        return nextFencingToken++;
    }

    private static Duration requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static PortFailure failure(String code, Object... identity) {
        String[] parts = new String[identity.length + 1];
        parts[0] = code;
        for (int index = 0; index < identity.length; index++) {
            parts[index + 1] = String.valueOf(identity[index]);
        }
        return new PortFailure(
                code, RetryMode.NONE,
                new ResultRef("execution-ledger-failure:sha256:" + hashParts(parts)));
    }

    private static String hashPayload(String schema, String body) {
        return sha256(schema + "\n" + body);
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

    private static String hashParts(String... parts) {
        StringBuilder framed = new StringBuilder();
        for (String part : parts) {
            framed.append(part.length()).append(':').append(part);
        }
        return sha256(framed.toString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
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
                        throw new IllegalArgumentException("invalid_execution_event_json");
                    }
                    output.append(current).append(text.charAt(index));
                } else if (Character.isLowSurrogate(current)) {
                    throw new IllegalArgumentException("invalid_execution_event_json");
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
            throw new IllegalArgumentException("invalid_execution_event_json");
        }
    }

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new TreeMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private record ExecutionKey(TenantId tenantId, String executionId) {
        private static ExecutionKey from(ExecutionRef ref) {
            return new ExecutionKey(ref.tenantId(), ref.executionId());
        }
    }

    private static final class StoredExecution {
        private final CreateExecution createCommand;
        private final ExecutionRecord initialOutput;
        private final Map<MutationOperationKey, MutationOperation> mutations =
                new HashMap<>();
        private final Map<ResumeOperationKey, ResumeOperation> resumes = new HashMap<>();
        private final Map<CancelOperationKey, CancelOperation> cancelOperations =
                new HashMap<>();
        private ExecutionRecord current;
        private Long leaseExecutionRevision;
        private ExecutionRenewal lastRenewal;
        private ExecutionRelease lastRelease;

        private StoredExecution(CreateExecution createCommand, ExecutionRecord initial) {
            this.createCommand = createCommand;
            this.initialOutput = initial;
            this.current = initial;
        }
    }

    private record ExecutionRenewal(
            ExecutionLease lease, Duration extension, LeaseFence outputFence) {
    }

    private record ExecutionRelease(ExecutionLease lease, ExecutionRecord output) {
    }

    private enum MutationKind {
        PROGRESS,
        TRANSITION
    }

    private static final class Mutation {
        private final MutationKind kind;
        private final Object command;

        private Mutation(MutationKind kind, Object command) {
            this.kind = kind;
            this.command = command;
        }

        private static Mutation progress(ProgressExecution command) {
            return new Mutation(MutationKind.PROGRESS, command);
        }

        private static Mutation transition(TransitionExecution command) {
            return new Mutation(MutationKind.TRANSITION, command);
        }

        private ExecutionLease lease() {
            return kind == MutationKind.PROGRESS
                    ? ((ProgressExecution) command).lease()
                    : ((TransitionExecution) command).lease();
        }

        private long expectedRevision() {
            return kind == MutationKind.PROGRESS
                    ? ((ProgressExecution) command).expectedRevision()
                    : ((TransitionExecution) command).expectedRevision();
        }

        private WorkflowExecutionState expectedState() {
            return kind == MutationKind.PROGRESS
                    ? WorkflowExecutionState.RUNNING
                    : ((TransitionExecution) command).expectedState();
        }

        private WorkflowExecutionState targetState() {
            return kind == MutationKind.PROGRESS
                    ? WorkflowExecutionState.RUNNING
                    : ((TransitionExecution) command).targetState();
        }

        private TypedPayload checkpoint() {
            return kind == MutationKind.PROGRESS
                    ? ((ProgressExecution) command).checkpoint()
                    : ((TransitionExecution) command).checkpoint();
        }

        private Optional<ExecutionWait> waiting() {
            return kind == MutationKind.PROGRESS
                    ? Optional.empty() : ((TransitionExecution) command).waiting();
        }

        private Optional<ResultRef> resultRef() {
            return kind == MutationKind.PROGRESS
                    ? Optional.empty() : ((TransitionExecution) command).resultRef();
        }

        private Optional<String> reasonCode() {
            return kind == MutationKind.PROGRESS
                    ? Optional.empty() : ((TransitionExecution) command).reasonCode();
        }

        private LedgerEvent event() {
            return kind == MutationKind.PROGRESS
                    ? ((ProgressExecution) command).stepEvent()
                    : ((TransitionExecution) command).event();
        }

        private MutationOperationKey key() {
            return new MutationOperationKey(
                    kind, event().sessionEvent().eventId(), expectedRevision());
        }
    }

    private record MutationOperationKey(
            MutationKind kind, EventId eventId, long expectedRevision) {
    }

    private record MutationOperation(Object command, ExecutionRecord output) {
    }

    private record ResumeOperationKey(
            EventId resumedEventId, EventId startedEventId, long expectedRevision) {
        private static ResumeOperationKey from(ResumeExecution command) {
            return new ResumeOperationKey(
                    command.resumedEvent().sessionEvent().eventId(),
                    command.startedEvent().sessionEvent().eventId(),
                    command.expectedRevision());
        }
    }

    private record ResumeOperation(ResumeExecution command, ExecutionRecord output) {
    }

    private record CancelOperationKey(
            InputId cancelRequestId, EventId eventId, long expectedRevision) {
        private static CancelOperationKey from(RequestExecutionCancel command) {
            return new CancelOperationKey(
                    command.cancelRequestId(),
                    command.cancelEvent().sessionEvent().eventId(),
                    command.expectedRevision());
        }
    }

    private record CancelOperation(RequestExecutionCancel command, ExecutionRecord output) {
    }

    private record PublicationKey(TenantId tenantId, String sourceId, EventId eventId) {
        private static PublicationKey from(ExecutionRef ref, EventId eventId) {
            return new PublicationKey(ref.tenantId(), ref.executionId(), eventId);
        }
    }

    private enum PublicationStatus {
        PENDING,
        CLAIMED,
        ACKNOWLEDGED
    }

    private static final class StoredPublication {
        private final StateEventPublication base;
        private final Map<PublicationOperationKey, PublicationOperation> operations =
                new HashMap<>();
        private PublicationStatus status = PublicationStatus.PENDING;
        private long revision = 1;
        private int attempts;
        private Instant availableAt;
        private LeaseFence lease;
        private ResultRef projectionRef;
        private PortFailure lastFailure;
        private Instant updatedAt;

        private StoredPublication(StateEventPublication base, Instant createdAt) {
            this.base = base;
            this.availableAt = base.availableAt();
            this.updatedAt = createdAt;
        }

        private Instant effectiveAvailableAt() {
            return status == PublicationStatus.CLAIMED && lease != null
                    ? lease.leaseUntil() : availableAt;
        }

        private StateEventPublication snapshot() {
            return new StateEventPublication(
                    base.tenantId(), base.sourceKind(), base.sourceId(),
                    base.sourceRevision(), base.committedFactRef(),
                    base.projectionTarget(), base.event(), base.runtimeEventDestinationIds(),
                    revision, availableAt);
        }

        private PublicationState state() {
            return new PublicationState(
                    status, revision, attempts, availableAt,
                    Optional.ofNullable(lease), Optional.ofNullable(projectionRef),
                    Optional.ofNullable(lastFailure));
        }
    }

    private record PublicationState(
            PublicationStatus status,
            long revision,
            int attempts,
            Instant availableAt,
            Optional<LeaseFence> lease,
            Optional<ResultRef> projectionRef,
            Optional<PortFailure> lastFailure) {
    }

    private enum PublicationMutationKind {
        ACKNOWLEDGE,
        RETRY
    }

    private static final class PublicationMutation {
        private final PublicationMutationKind kind;
        private final Object command;

        private PublicationMutation(PublicationMutationKind kind, Object command) {
            this.kind = kind;
            this.command = command;
        }

        private static PublicationMutation acknowledge(PublicationAcknowledge command) {
            return new PublicationMutation(PublicationMutationKind.ACKNOWLEDGE, command);
        }

        private static PublicationMutation retry(PublicationRetry command) {
            return new PublicationMutation(PublicationMutationKind.RETRY, command);
        }

        private TenantId tenantId() {
            return kind == PublicationMutationKind.ACKNOWLEDGE
                    ? ((PublicationAcknowledge) command).tenantId()
                    : ((PublicationRetry) command).tenantId();
        }

        private String sourceKind() {
            return kind == PublicationMutationKind.ACKNOWLEDGE
                    ? ((PublicationAcknowledge) command).sourceKind()
                    : ((PublicationRetry) command).sourceKind();
        }

        private String sourceId() {
            return kind == PublicationMutationKind.ACKNOWLEDGE
                    ? ((PublicationAcknowledge) command).sourceId()
                    : ((PublicationRetry) command).sourceId();
        }

        private EventId eventId() {
            return kind == PublicationMutationKind.ACKNOWLEDGE
                    ? ((PublicationAcknowledge) command).eventId()
                    : ((PublicationRetry) command).eventId();
        }

        private LeaseFence fence() {
            return kind == PublicationMutationKind.ACKNOWLEDGE
                    ? ((PublicationAcknowledge) command).fence()
                    : ((PublicationRetry) command).fence();
        }

        private long expectedRevision() {
            return kind == PublicationMutationKind.ACKNOWLEDGE
                    ? ((PublicationAcknowledge) command).expectedPublicationRevision()
                    : ((PublicationRetry) command).expectedPublicationRevision();
        }

        private PublicationOperationKey key() {
            return new PublicationOperationKey(
                    kind, fence().claimId(), fence().fencingToken(), expectedRevision());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PublicationMutation value
                    && kind == value.kind && command.equals(value.command);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, command);
        }
    }

    private record PublicationOperationKey(
            PublicationMutationKind kind,
            String claimId,
            long fencingToken,
            long expectedRevision) {
    }

    private record PublicationOperation(
            PublicationMutation mutation,
            StateEventPublication output,
            PublicationState outputState) {
    }
}
