package com.skillforge.runtime.adapter.jdbc.execution;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionLedgerPort;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.CreateExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionCancelResult;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLease;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLeaseRequest;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLeaseResult;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLookup;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecoveryCandidate;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecoveryScan;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ProgressExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.RequestExecutionCancel;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ResumeExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.TransitionExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionWriteResult;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.api.port.LeaseMutationStatus;
import com.skillforge.runtime.api.port.LedgerEvent;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PublicationAcknowledge;
import com.skillforge.runtime.api.port.PublicationClaimRequest;
import com.skillforge.runtime.api.port.PublicationMutationResult;
import com.skillforge.runtime.api.port.PublicationRetry;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import com.skillforge.runtime.api.port.StateEventClaim;
import com.skillforge.runtime.api.port.StateEventPublication;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.annotations.Param;

/** PostgreSQL/MyBatis 的 Workflow Execution 权威账本实现。 */
public final class JdbcExecutionLedgerPort implements ExecutionLedgerPort {

    private static final String MAPPER_RESOURCE =
            "mapper/AgentRuntimeExecutionLedgerMapper.xml";
    private final SqlSessionFactory sessionFactory;
    private final WorkflowTransactionScope workflowScope;

    public JdbcExecutionLedgerPort(DataSource dataSource) {
        sessionFactory = buildSessionFactory(Objects.requireNonNull(dataSource, "dataSource"));
        workflowScope = null;
    }

    /** 仅域内协调器借用同事务；实例不离开 operation 方法，不能独立提交或跨租户使用。 */
    JdbcExecutionLedgerPort(WorkflowTransactionScope scope) {
        sessionFactory = null;
        workflowScope = Objects.requireNonNull(scope);
    }

    /** 在工厂创建时注册原账本映射；子Run来源核验借用Store事务，不建立第二连接。 */
    public static void configure(Configuration configuration) {
        Objects.requireNonNull(configuration);
        if (configuration.hasMapper(ExecutionLedgerMapper.class)) return;
        try (InputStream xml = JdbcExecutionLedgerPort.class.getClassLoader().getResourceAsStream(MAPPER_RESOURCE)) {
            if (xml == null) throw new IllegalStateException("execution_ledger_mapper_missing");
            new XMLMapperBuilder(xml, configuration, MAPPER_RESOURCE, configuration.getSqlFragments()).parse();
        } catch (IOException failure) { throw new IllegalStateException("execution_ledger_initialization_failed", failure); }
    }

    /** 返回锁内真实Execution及同连接数据库时钟；不提交、切租户或获取别的事务。 */
    public static LockedExecution lockBorrowed(SqlSession session, ExecutionRef reference) {
        Objects.requireNonNull(session); Objects.requireNonNull(reference);
        var mapper = session.getMapper(ExecutionLedgerMapper.class);
        var row = mapper.lockExecution(reference.tenantId().value(), reference.executionId());
        if (row == null) throw new IllegalArgumentException("workflow_execution_source_missing");
        var actual = row.toRecord();
        if (!actual.executionRef().equals(reference)) throw new IllegalStateException("workflow_execution_source_mismatch");
        return new LockedExecution(actual, mapper.storageNow().toInstant());
    }

    /** 真实行由既有账本codec逐字段校验，不以任意record_data快照替代来源。 */
    public record LockedExecution(ExecutionRecord execution, Instant storageNow) {
        public LockedExecution { Objects.requireNonNull(execution); Objects.requireNonNull(storageNow); }
    }

    @Override
    public ExecutionWriteResult create(CreateExecution command) {
        Objects.requireNonNull(command, "command");
        TenantId tenantId = command.executionRef().tenantId();
        return inTransaction(tenantId, mapper -> {
            mapper.lockKey("execution:create:" + tenantId.value() + ":"
                    + command.executionRef().executionId());
            ExecutionLedgerRow stored = mapper.lockExecution(
                    tenantId.value(), command.executionRef().executionId());
            String key = "create";
            String commandFingerprint = fingerprint(command);
            if (stored != null) {
                ExecutionRecord current = stored.toRecord();
                ExecutionMutationRow prior = mapper.findMutation(
                        tenantId.value(), command.executionRef().executionId(), key);
                if (prior == null) {
                    throw new IllegalStateException("execution_create_journal_missing");
                }
                validateMutationIdentity(prior, "CREATE", 0, null);
                CreateExecution frozen = prior.command(CreateExecution.class);
                ExecutionRecord priorOutput = prior.output();
                if (frozen.equals(command)
                        && prior.getCommandFingerprint().equals(commandFingerprint)) {
                    if (priorOutput.equals(current)
                            && prior.getOutputFingerprint().equals(fingerprint(current))) {
                        return new ExecutionWriteResult.Replayed(priorOutput);
                    }
                    return new ExecutionWriteResult.Conflict(current);
                }
                return new ExecutionWriteResult.Rejected(
                        failure("execution_mutation_conflict", key));
            }

            Instant now = mapper.storageNow().toInstant();
            ExecutionRecord output = new ExecutionRecord(
                    command.executionRef(), command.owner(), command.executionUnitId(),
                    command.definitionVersion(), command.mode(),
                    command.causativeActionRef(), command.delegationRef(),
                    WorkflowExecutionState.QUEUED, 1, command.initialCheckpoint(),
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), now, now);
            if (!ExecutionLedgerEventCodec.validCreate(command, output)) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_event_mismatch", key));
            }
            if (publicationConflict(mapper, output, command.queuedEvent())) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_publication_event_conflict", key));
            }

            ExecutionMutationRow journal = mutationRow(
                    output, key, "CREATE", 0, null,
                    commandFingerprint, command, output, Optional.empty());
            StateEventPublication publication = newPublication(
                    output, command.queuedEvent(), now);
            if (mapper.insertExecution(ExecutionLedgerRow.from(output)) != 1
                    || mapper.insertMutation(journal) != 1
                    || mapper.insertPublication(
                            ExecutionPublicationRow.initial(publication, now)) != 1) {
                throw new IllegalStateException("execution_create_not_applied");
            }
            return new ExecutionWriteResult.Applied(output);
        });
    }

    @Override
    public Optional<ExecutionRecord> find(ExecutionLookup query) {
        Objects.requireNonNull(query, "query");
        return inTransaction(query.executionRef().tenantId(), mapper -> {
            ExecutionLedgerRow row = mapper.findExecution(
                    query.executionRef().tenantId().value(),
                    query.executionRef().executionId());
            if (row == null) return Optional.empty();
            var record = row.toRecord();
            return record.owner().owner().equals(query.identity().owner())
                    ? Optional.of(record) : Optional.empty();
        });
    }


    /** 按原 Run 与挂起引用发现精确等待；返回值仍需由当前身份和 Workflow 策略核验。 */
    public Optional<ExecutionRecord> findExternalWait(RunIdentity run, String suspendRef) {
        Objects.requireNonNull(run); Objects.requireNonNull(suspendRef);
        return inTransaction(run.runRef().tenantId(), mapper -> {
            var rows = mapper.findExternalWait(run.runRef().tenantId().value(), run.runRef().runId(), suspendRef);
            if (rows.size() > 1) throw new IllegalStateException("workflow_wait_ambiguous");
            return rows.stream().map(ExecutionLedgerRow::toRecord).filter(record -> record.owner().equals(run)).findFirst();
        });
    }

    @Override
    public ExecutionLeaseResult acquireLease(ExecutionLeaseRequest request) {
        Objects.requireNonNull(request, "request");
        TenantId tenantId = request.executionRef().tenantId();
        return inTransaction(tenantId, mapper -> {
            ExecutionLedgerRow row = mapper.lockExecution(
                    tenantId.value(), request.executionRef().executionId());
            if (row == null
                    || !row.toRecord().owner().owner().equals(request.identity().owner())) {
                return new ExecutionLeaseResult.Rejected(
                        failure("execution_not_found", request.executionRef()));
            }
            ExecutionRecord before = row.toRecord();
            if (terminal(before.state())) {
                return new ExecutionLeaseResult.Rejected(
                        failure("execution_terminal", request.executionRef()));
            }
            Instant now = mapper.storageNow().toInstant();
            if (before.lease().isPresent()
                    && now.isBefore(before.lease().orElseThrow().leaseUntil())) {
                return new ExecutionLeaseResult.Busy(before);
            }
            long token = mapper.nextFence();
            LeaseFence fence = new LeaseFence(
                    tenantId, "execution-claim-" + token, request.workerId(), token,
                    now.plus(request.leaseDuration()));
            ExecutionRecord output = copyExecution(
                    before, before.state(), before.executionRevision(),
                    before.checkpoint(), before.waiting(), before.resultRef(),
                    before.reasonCode(), Optional.of(fence),
                    before.cancelRequestId(), before.cancelRequestedAt(), now);
            ExecutionLedgerRow update = ExecutionLedgerRow.from(output);
            update.setLeaseExecutionRevision(before.executionRevision());
            update.setLastRequestedAt(request.requestedAt());
            if (mapper.updateExecution(update, before.executionRevision()) != 1) {
                throw new IllegalStateException("execution_claim_not_applied");
            }
            return new ExecutionLeaseResult.Acquired(
                    output, new ExecutionLease(
                            output.executionRef(), fence, output.executionRevision()));
        });
    }

    @Override
    public LeaseMutationStatus renew(ExecutionLease lease, Duration extension) {
        Objects.requireNonNull(lease, "lease");
        requirePositive(extension, "extension");
        TenantId tenantId = lease.executionRef().tenantId();
        return inTransaction(tenantId, mapper -> {
            ExecutionLedgerRow row = mapper.lockExecution(
                    tenantId.value(), lease.executionRef().executionId());
            if (row == null) {
                return LeaseMutationStatus.NOT_FOUND;
            }
            ExecutionRecord before = row.toRecord();
            if (terminal(before.state())) {
                return LeaseMutationStatus.TERMINAL;
            }
            if (before.lease().isEmpty()
                    || row.getLeaseExecutionRevision() == null
                    || lease.executionRevision()
                            != row.getLeaseExecutionRevision()) {
                return LeaseMutationStatus.STALE_LEASE;
            }
            Instant now = mapper.storageNow().toInstant();
            LeaseFence authority = before.lease().orElseThrow();
            Instant target = lease.fence().leaseUntil().plus(extension);
            if (authority.equals(lease.fence())) {
                if (!now.isBefore(authority.leaseUntil())) {
                    return LeaseMutationStatus.STALE_LEASE;
                }
                ExecutionRecord output = copyExecution(
                        before, before.state(), before.executionRevision(),
                        before.checkpoint(), before.waiting(), before.resultRef(),
                        before.reasonCode(), Optional.of(withExpiry(authority, target)),
                        before.cancelRequestId(), before.cancelRequestedAt(), now);
                ExecutionLedgerRow update = ExecutionLedgerRow.from(output);
                update.copyLeaseExecutionRevisionFrom(row);
                update.copyLastRequestedAtFrom(row);
                if (mapper.updateExecution(update, before.executionRevision()) != 1) {
                    throw new IllegalStateException("execution_renew_not_applied");
                }
                return LeaseMutationStatus.APPLIED;
            }
            if (sameLeaseIdentity(authority, lease.fence())
                    && authority.leaseUntil().equals(target)
                    && now.isBefore(authority.leaseUntil())) {
                return LeaseMutationStatus.ALREADY_APPLIED;
            }
            return LeaseMutationStatus.STALE_LEASE;
        });
    }

    @Override
    public LeaseMutationStatus release(ExecutionLease lease) {
        Objects.requireNonNull(lease, "lease");
        TenantId tenantId = lease.executionRef().tenantId();
        return inTransaction(tenantId, mapper -> {
            ExecutionLedgerRow row = mapper.lockExecution(
                    tenantId.value(), lease.executionRef().executionId());
            if (row == null) {
                return LeaseMutationStatus.NOT_FOUND;
            }
            ExecutionRecord current = row.toRecord();
            String key = releaseKey(lease);
            String commandFingerprint = fingerprint(lease);
            ExecutionMutationRow prior = mapper.findMutation(
                    tenantId.value(), lease.executionRef().executionId(), key);
            if (prior != null) {
                validateMutationIdentity(
                        prior, "RELEASE", lease.executionRevision(), null);
                ExecutionLease frozen = prior.command(ExecutionLease.class);
                ExecutionRecord priorOutput = prior.output();
                if (frozen.equals(lease)
                        && prior.getCommandFingerprint().equals(commandFingerprint)
                        && priorOutput.equals(current)
                        && prior.getOutputFingerprint().equals(fingerprint(current))) {
                    return LeaseMutationStatus.ALREADY_APPLIED;
                }
                return LeaseMutationStatus.STALE_LEASE;
            }
            if (terminal(current.state())) {
                return LeaseMutationStatus.TERMINAL;
            }
            if (current.lease().isEmpty()) {
                return LeaseMutationStatus.STALE_LEASE;
            }
            Instant now = mapper.storageNow().toInstant();
            LeaseFence authority = current.lease().orElseThrow();
            if (row.getLeaseExecutionRevision() == null
                    || lease.executionRevision()
                            != row.getLeaseExecutionRevision()
                    || !sameLeaseIdentity(authority, lease.fence())
                    || lease.fence().leaseUntil().isAfter(authority.leaseUntil())
                    || !now.isBefore(authority.leaseUntil())) {
                return LeaseMutationStatus.STALE_LEASE;
            }
            ExecutionRecord output = copyExecution(
                    current, current.state(), current.executionRevision(),
                    current.checkpoint(), current.waiting(), current.resultRef(),
                    current.reasonCode(), Optional.empty(), current.cancelRequestId(),
                    current.cancelRequestedAt(), now);
            ExecutionMutationRow journal = mutationRow(
                    output, key, "RELEASE", lease.executionRevision(),
                    null, commandFingerprint, lease, output,
                    Optional.empty());
            ExecutionLedgerRow update = ExecutionLedgerRow.from(output);
            update.copyLastRequestedAtFrom(row);
            if (mapper.insertMutation(journal) != 1
                    || mapper.updateExecution(update, current.executionRevision()) != 1) {
                throw new IllegalStateException("execution_release_not_applied");
            }
            return LeaseMutationStatus.APPLIED;
        });
    }

    @Override
    public ExecutionCancelResult requestCancel(RequestExecutionCancel command) {
        Objects.requireNonNull(command, "command");
        TenantId tenantId = command.executionRef().tenantId();
        return inTransaction(tenantId, mapper -> {
            ExecutionLedgerRow row = mapper.lockExecution(
                    tenantId.value(), command.executionRef().executionId());
            if (row == null
                    || !row.toRecord().owner().owner().equals(command.identity().owner())) {
                return new ExecutionCancelResult.NotFound(
                        failure("execution_not_found", command.executionRef()));
            }
            ExecutionRecord current = row.toRecord();
            String key = cancelKey(command);
            String commandFingerprint = fingerprint(command);
            ExecutionMutationRow prior = mapper.findMutation(
                    tenantId.value(), command.executionRef().executionId(), key);
            if (prior != null) {
                validateMutationIdentity(
                        prior, "CANCEL", command.expectedRevision(),
                        currentStateAtCommand(prior));
                RequestExecutionCancel frozen = prior.command(RequestExecutionCancel.class);
                ExecutionRecord priorOutput = prior.output();
                if (frozen.equals(command)
                        && prior.getCommandFingerprint().equals(commandFingerprint)) {
                    if (priorOutput.equals(current)
                            && prior.getOutputFingerprint().equals(fingerprint(current))) {
                        return new ExecutionCancelResult.Replayed(priorOutput);
                    }
                    return new ExecutionCancelResult.Conflict(current);
                }
                return new ExecutionCancelResult.Rejected(
                        failure("execution_mutation_conflict", key));
            }
            if (terminal(current.state())) {
                return new ExecutionCancelResult.Rejected(
                        failure("execution_terminal", command.executionRef()));
            }
            if (current.executionRevision() != command.expectedRevision()
                    || current.cancelRequestId().isPresent()) {
                return new ExecutionCancelResult.Conflict(current);
            }
            Instant now = mapper.storageNow().toInstant();
            ExecutionRecord output = copyExecution(
                    current, current.state(),
                    Math.incrementExact(current.executionRevision()),
                    current.checkpoint(), current.waiting(), current.resultRef(),
                    current.reasonCode(), current.lease(),
                    Optional.of(command.cancelRequestId()), Optional.of(now), now);
            if (!ExecutionLedgerEventCodec.validCancel(command, output)) {
                return new ExecutionCancelResult.Rejected(
                        failure("execution_event_mismatch", key));
            }
            if (publicationConflict(mapper, output, command.cancelEvent())) {
                return new ExecutionCancelResult.Rejected(
                        failure("execution_publication_event_conflict", key));
            }
            ExecutionMutationRow journal = mutationRow(
                    output, key, "CANCEL", command.expectedRevision(), current.state(),
                    commandFingerprint, command, output, Optional.empty());
            StateEventPublication publication = newPublication(
                    output, command.cancelEvent(), now);
            ExecutionLedgerRow update = ExecutionLedgerRow.from(output);
            update.copyLeaseExecutionRevisionFrom(row);
            update.copyLastRequestedAtFrom(row);
            if (mapper.insertMutation(journal) != 1
                    || mapper.updateExecution(update, current.executionRevision()) != 1
                    || mapper.insertPublication(
                            ExecutionPublicationRow.initial(publication, now)) != 1) {
                throw new IllegalStateException("execution_cancel_not_applied");
            }
            return new ExecutionCancelResult.Applied(output);
        });
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

    @Override
    public ExecutionWriteResult resume(ResumeExecution command) {
        Objects.requireNonNull(command, "command");
        ExecutionLease lease = command.lease();
        TenantId tenantId = lease.executionRef().tenantId();
        return inTransaction(tenantId, mapper -> {
            ExecutionLedgerRow row = mapper.lockExecution(
                    tenantId.value(), lease.executionRef().executionId());
            if (row == null) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_not_found", lease.executionRef()));
            }
            ExecutionRecord current = row.toRecord();
            String key = resumeKey(command);
            String commandFingerprint = fingerprint(command);
            ExecutionMutationRow prior = mapper.findMutation(
                    tenantId.value(), lease.executionRef().executionId(), key);
            if (prior != null) {
                validateMutationIdentity(
                        prior, "RESUME", command.expectedRevision(),
                        command.expectedState());
                ResumeExecution frozen = prior.command(ResumeExecution.class);
                ExecutionRecord priorOutput = prior.output();
                ExecutionRecord intermediate = prior.intermediate().orElseThrow(
                        () -> new IllegalStateException(
                                "execution_resume_intermediate_missing"));
                if (intermediate.executionRevision() + 1 != priorOutput.executionRevision()) {
                    throw new IllegalStateException("execution_resume_journal_mismatch");
                }
                if (frozen.equals(command)
                        && prior.getCommandFingerprint().equals(commandFingerprint)) {
                    if (priorOutput.equals(current)
                            && prior.getOutputFingerprint().equals(fingerprint(current))) {
                        return new ExecutionWriteResult.Replayed(priorOutput);
                    }
                    return new ExecutionWriteResult.Conflict(current);
                }
                return new ExecutionWriteResult.Rejected(
                        failure("execution_mutation_conflict", key));
            }
            if (terminal(current.state())) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_terminal", lease.executionRef()));
            }
            if (current.state() != command.expectedState()
                    || current.executionRevision() != command.expectedRevision()
                    || !current.waiting().orElseThrow().suspendRef()
                            .equals(command.expectedSuspendRef())
                    || !current.waiting().orElseThrow().actionKey()
                            .equals(command.expectedActionKey())) {
                return new ExecutionWriteResult.Conflict(current);
            }
            Instant now = mapper.storageNow().toInstant();
            if (!hasCurrentLease(row, current, lease, now)) {
                return new ExecutionWriteResult.LeaseLost(
                        failure("execution_stale_lease", key));
            }
            long intermediateRevision = Math.incrementExact(current.executionRevision());
            ExecutionRecord intermediate = copyExecution(
                    current, WorkflowExecutionState.RESUMING, intermediateRevision,
                    command.checkpoint(), Optional.empty(), Optional.empty(),
                    Optional.empty(), current.lease(), current.cancelRequestId(),
                    current.cancelRequestedAt(), now);
            ExecutionRecord output = copyExecution(
                    intermediate, WorkflowExecutionState.RUNNING,
                    Math.incrementExact(intermediateRevision), command.checkpoint(),
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    intermediate.lease(), intermediate.cancelRequestId(),
                    intermediate.cancelRequestedAt(), now);
            if (!ExecutionLedgerEventCodec.validResume(
                    command, intermediate, output)) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_event_mismatch", key));
            }
            if (publicationConflict(mapper, intermediate, command.resumedEvent())
                    || publicationConflict(mapper, output, command.startedEvent())) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_publication_event_conflict", key));
            }
            ExecutionMutationRow journal = mutationRow(
                    output, key, "RESUME", command.expectedRevision(),
                    command.expectedState(), commandFingerprint,
                    command, output, Optional.of(intermediate));
            ExecutionLedgerRow intermediateRow = ExecutionLedgerRow.from(intermediate);
            intermediateRow.copyLeaseExecutionRevisionFrom(row);
            intermediateRow.copyLastRequestedAtFrom(row);
            ExecutionLedgerRow outputRow = ExecutionLedgerRow.from(output);
            outputRow.copyLeaseExecutionRevisionFrom(row);
            outputRow.copyLastRequestedAtFrom(row);
            StateEventPublication resumed = newPublication(
                    intermediate, command.resumedEvent(), now);
            StateEventPublication started = newPublication(
                    output, command.startedEvent(), now);
            if (mapper.insertMutation(journal) != 1
                    || mapper.updateExecution(
                            intermediateRow, current.executionRevision()) != 1
                    || mapper.updateExecution(outputRow, intermediateRevision) != 1
                    || mapper.insertPublication(
                            ExecutionPublicationRow.initial(resumed, now)) != 1
                    || mapper.insertPublication(
                            ExecutionPublicationRow.initial(started, now)) != 1) {
                throw new IllegalStateException("execution_resume_not_applied");
            }
            return new ExecutionWriteResult.Applied(output);
        });
    }

    @Override
    public List<ExecutionRecoveryCandidate> scanRecoverable(
            ExecutionRecoveryScan query) {
        Objects.requireNonNull(query, "query");
        return inTransaction(query.tenantId(), mapper -> mapper.scanRecoverable(
                        query.tenantId().value(), query.limit(), query.afterExecutionId().orElse(null)).stream()
                .map(ExecutionLedgerRow::toRecord)
                .map(execution -> new ExecutionRecoveryCandidate(
                        execution.executionRef(), execution.owner(), execution.state(),
                        execution.executionRevision(),
                        execution.lease().map(LeaseFence::leaseUntil),
                        execution.cancelRequestId()))
                .toList());
    }

    @Override
    public List<StateEventClaim> claimStateEvents(PublicationClaimRequest request) {
        Objects.requireNonNull(request, "request");
        return inTransaction(request.tenantId(), mapper -> {
            List<ExecutionPublicationRow> eligible = mapper.lockEligiblePublications(
                    request.tenantId().value(), request.limit());
            if (eligible.isEmpty()) {
                return List.of();
            }
            Instant now = mapper.storageNow().toInstant();
            List<StateEventClaim> claims = new ArrayList<>(eligible.size());
            for (ExecutionPublicationRow row : eligible) {
                StateEventPublication before = row.toPublication();
                long token = mapper.nextFence();
                LeaseFence fence = new LeaseFence(
                        request.tenantId(), "execution-publication-claim-" + token,
                        request.workerId(), token,
                        now.plus(request.leaseDuration()));
                StateEventPublication output = copyPublication(
                        before, Math.incrementExact(before.publicationRevision()),
                        before.availableAt());
                ExecutionPublicationRow update = ExecutionPublicationRow.from(
                        output, "CLAIMED", Math.incrementExact(row.getAttempts()),
                        row.createdAt(), now);
                update.setLease(fence);
                update.setProjectionRef(row.getProjectionRef());
                update.setFailureData(row.getFailureData());
                if (mapper.updatePublication(
                        update, before.publicationRevision()) != 1) {
                    throw new IllegalStateException(
                            "execution_publication_claim_not_applied");
                }
                claims.add(new StateEventClaim(output, fence));
            }
            return List.copyOf(claims);
        });
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

    private ExecutionWriteResult mutate(Mutation mutation) {
        ExecutionLease lease = mutation.lease();
        TenantId tenantId = lease.executionRef().tenantId();
        return inTransaction(tenantId, mapper -> {
            ExecutionLedgerRow row = mapper.lockExecution(
                    tenantId.value(), lease.executionRef().executionId());
            if (row == null) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_not_found", lease.executionRef()));
            }
            ExecutionRecord current = row.toRecord();
            String key = mutation.key();
            String commandFingerprint = fingerprint(mutation.command);
            ExecutionMutationRow prior = mapper.findMutation(
                    tenantId.value(), lease.executionRef().executionId(), key);
            if (prior != null) {
                validateMutationIdentity(
                        prior, mutation.kind.name(), mutation.expectedRevision(),
                        mutation.expectedState());
                Object frozen = mutation.kind == MutationKind.PROGRESS
                        ? prior.command(ProgressExecution.class)
                        : prior.command(TransitionExecution.class);
                ExecutionRecord priorOutput = prior.output();
                if (frozen.equals(mutation.command)
                        && prior.getCommandFingerprint().equals(commandFingerprint)) {
                    if (priorOutput.equals(current)
                            && prior.getOutputFingerprint().equals(fingerprint(current))) {
                        return new ExecutionWriteResult.Replayed(priorOutput);
                    }
                    return new ExecutionWriteResult.Conflict(current);
                }
                return new ExecutionWriteResult.Rejected(
                        failure("execution_mutation_conflict", key));
            }
            if (terminal(current.state())) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_terminal", lease.executionRef()));
            }
            if (current.executionRevision() != mutation.expectedRevision()
                    || current.state() != mutation.expectedState()) {
                return new ExecutionWriteResult.Conflict(current);
            }
            Instant now = mapper.storageNow().toInstant();
            if (!hasCurrentLease(row, current, lease, now)) {
                return new ExecutionWriteResult.LeaseLost(
                        failure("execution_stale_lease", key));
            }
            long revision = Math.incrementExact(current.executionRevision());
            ExecutionRecord output = mutation.output(current, revision, now);
            boolean valid = mutation.kind == MutationKind.PROGRESS
                    ? ExecutionLedgerEventCodec.validProgress(
                            (ProgressExecution) mutation.command, output)
                    : ExecutionLedgerEventCodec.validTransition(
                            (TransitionExecution) mutation.command, output);
            if (!valid) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_event_mismatch", key));
            }
            if (publicationConflict(mapper, output, mutation.event())) {
                return new ExecutionWriteResult.Rejected(
                        failure("execution_publication_event_conflict", key));
            }
            ExecutionMutationRow journal = mutationRow(
                    output, key, mutation.kind.name(), mutation.expectedRevision(),
                    mutation.expectedState(), commandFingerprint, mutation.command,
                    output, Optional.empty());
            ExecutionLedgerRow update = ExecutionLedgerRow.from(output);
            update.copyLeaseExecutionRevisionFrom(row);
            update.copyLastRequestedAtFrom(row);
            StateEventPublication publication = newPublication(
                    output, mutation.event(), now);
            if (mapper.insertMutation(journal) != 1
                    || mapper.updateExecution(update, current.executionRevision()) != 1
                    || mapper.insertPublication(
                            ExecutionPublicationRow.initial(publication, now)) != 1) {
                throw new IllegalStateException("execution_mutation_not_applied");
            }
            return new ExecutionWriteResult.Applied(output);
        });
    }

    private PublicationMutationResult mutatePublication(
            PublicationMutation mutation) {
        return inTransaction(mutation.tenantId(), mapper -> {
            if (!mutation.sourceKind().equals("execution")) {
                return new PublicationMutationResult.NotFound(
                        failure("execution_publication_not_found",
                                mutation.sourceId(), mutation.eventId()));
            }
            ExecutionPublicationRow row = mapper.lockPublication(
                    mutation.tenantId().value(), mutation.sourceId(),
                    mutation.eventId().value());
            if (row == null) {
                return new PublicationMutationResult.NotFound(
                        failure("execution_publication_not_found",
                                mutation.sourceId(), mutation.eventId()));
            }
            StateEventPublication current = row.toPublication();
            String key = mutation.key();
            String commandFingerprint = fingerprint(mutation.command);
            ExecutionPublicationMutationRow prior =
                    mapper.findPublicationMutation(
                            mutation.tenantId().value(), mutation.sourceId(),
                            mutation.eventId().value(), key);
            if (prior != null) {
                if (!prior.getMutationKind().equals(mutation.kind.name())) {
                    throw new IllegalStateException(
                            "execution_publication_journal_kind_mismatch");
                }
                Object frozen = mutation.kind == PublicationMutationKind.ACKNOWLEDGE
                        ? prior.command(PublicationAcknowledge.class)
                        : prior.command(PublicationRetry.class);
                StateEventPublication priorOutput = prior.output();
                if (!frozen.equals(mutation.command)
                        || !prior.getCommandFingerprint().equals(commandFingerprint)) {
                    return new PublicationMutationResult.StaleLease(
                            current, failure(
                                    "execution_publication_mutation_conflict", key));
                }
                if (priorOutput.equals(current)
                        && prior.getOutputFingerprint().equals(
                                publicationStateFingerprint(row))) {
                    return new PublicationMutationResult.Replayed(priorOutput);
                }
                return "ACKNOWLEDGED".equals(row.getStatus())
                        ? new PublicationMutationResult.Terminal(current)
                        : new PublicationMutationResult.StaleLease(
                                current, failure(
                                        "execution_publication_stale_lease", key));
            }
            if ("ACKNOWLEDGED".equals(row.getStatus())) {
                return new PublicationMutationResult.Terminal(current);
            }
            Instant now = mapper.storageNow().toInstant();
            if (!"CLAIMED".equals(row.getStatus())
                    || row.getLeaseFencingToken() == null
                    || current.publicationRevision() != mutation.expectedRevision()
                    || !publicationFence(row).equals(mutation.fence())
                    || !now.isBefore(publicationFence(row).leaseUntil())) {
                return new PublicationMutationResult.StaleLease(
                        current, failure(
                                "execution_publication_stale_lease", key));
            }
            Instant availableAt = current.availableAt();
            String status;
            String projectionRef = row.getProjectionRef();
            String failureData = row.getFailureData();
            if (mutation.kind == PublicationMutationKind.ACKNOWLEDGE) {
                status = "ACKNOWLEDGED";
                projectionRef = ((PublicationAcknowledge) mutation.command)
                        .projectionRef().value();
            } else {
                PublicationRetry retry = (PublicationRetry) mutation.command;
                status = "PENDING";
                availableAt = retry.availableAt();
                failureData = ExecutionLedgerCodec.encode(retry.failure());
            }
            StateEventPublication output = copyPublication(
                    current, Math.incrementExact(current.publicationRevision()),
                    availableAt);
            ExecutionPublicationRow update = ExecutionPublicationRow.from(
                    output, status, row.getAttempts(), row.createdAt(), now);
            update.setProjectionRef(projectionRef);
            update.setFailureData(failureData);
            update.clearLease();
            ExecutionPublicationMutationRow journal = publicationMutationRow(
                    mutation, key, commandFingerprint, output, status,
                    publicationStateFingerprint(update));
            if (mapper.insertPublicationMutation(journal) != 1
                    || mapper.updatePublication(
                            update, current.publicationRevision()) != 1) {
                throw new IllegalStateException(
                        "execution_publication_mutation_not_applied");
            }
            return new PublicationMutationResult.Applied(output);
        });
    }

    private StateEventPublication newPublication(
            ExecutionRecord execution, LedgerEvent event, Instant availableAt) {
        RunIdentity owner = execution.owner();
        SessionProjectionTarget target = new SessionProjectionTarget(
                owner.owner(), owner.agentId(), owner.causationInputId(), Optional.of(owner.runRef()),
                Optional.of(execution.executionRef()),
                execution.causativeActionRef(), execution.delegationRef());
        ResultRef factRef = ExecutionLedgerEventCodec.committedFactRef(
                execution, event);
        return new StateEventPublication(
                execution.executionRef().tenantId(), "execution",
                execution.executionRef().executionId(),
                execution.executionRevision(), factRef, target, event,
                Set.of(), 1, availableAt);
    }

    private static ExecutionRecord copyExecution(
            ExecutionRecord source,
            WorkflowExecutionState state,
            long revision,
            TypedPayload checkpoint,
            Optional<ExecutionLedgerPort.ExecutionWait> waiting,
            Optional<ResultRef> resultRef,
            Optional<String> reasonCode,
            Optional<LeaseFence> lease,
            Optional<InputId> cancelRequestId,
            Optional<Instant> cancelRequestedAt,
            Instant updatedAt) {
        return new ExecutionRecord(
                source.executionRef(), source.owner(), source.executionUnitId(),
                source.definitionVersion(), source.mode(),
                source.causativeActionRef(), source.delegationRef(), state, revision,
                checkpoint, waiting, resultRef, reasonCode, lease, cancelRequestId,
                cancelRequestedAt, source.createdAt(), updatedAt);
    }

    private static StateEventPublication copyPublication(
            StateEventPublication source, long revision, Instant availableAt) {
        return new StateEventPublication(
                source.tenantId(), source.sourceKind(), source.sourceId(),
                source.sourceRevision(), source.committedFactRef(),
                source.projectionTarget(), source.event(),
                source.runtimeEventDestinationIds(), revision, availableAt);
    }

    private static boolean hasCurrentLease(
            ExecutionLedgerRow row,
            ExecutionRecord current,
            ExecutionLease supplied,
            Instant now) {
        if (current.lease().isEmpty()
                || row.getLeaseExecutionRevision() == null
                || supplied.executionRevision()
                        != row.getLeaseExecutionRevision()) {
            return false;
        }
        LeaseFence authority = current.lease().orElseThrow();
        return sameLeaseIdentity(authority, supplied.fence())
                && !supplied.fence().leaseUntil().isAfter(authority.leaseUntil())
                && now.isBefore(authority.leaseUntil());
    }

    private static LeaseFence publicationFence(ExecutionPublicationRow row) {
        return new LeaseFence(
                new TenantId(row.getTenantId()), row.getLeaseClaimId(),
                row.getLeaseWorkerId(), row.getLeaseFencingToken(),
                Instant.ofEpochSecond(
                        row.getLeaseUntilEpochSecond(), row.getLeaseUntilNano()));
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

    private static boolean terminal(WorkflowExecutionState state) {
        return state == WorkflowExecutionState.COMPLETED
                || state == WorkflowExecutionState.FAILED
                || state == WorkflowExecutionState.LOOP_TERMINATED
                || state == WorkflowExecutionState.CANCELLED;
    }

    private static boolean publicationConflict(
            ExecutionLedgerMapper mapper,
            ExecutionRecord output,
            LedgerEvent event) {
        String tenantId = output.executionRef().tenantId().value();
        String executionId = output.executionRef().executionId();
        ExecutionPublicationRow byEvent = mapper.findPublication(
                tenantId, executionId, event.sessionEvent().eventId().value());
        if (byEvent != null) {
            byEvent.toPublication();
            return true;
        }
        ExecutionPublicationRow byRevision = mapper.findPublicationByRevision(
                tenantId, executionId, output.executionRevision());
        if (byRevision != null) {
            byRevision.toPublication();
            return true;
        }
        return false;
    }

    private static ExecutionMutationRow mutationRow(
            ExecutionRecord owner,
            String key,
            String kind,
            long expectedRevision,
            WorkflowExecutionState expectedState,
            String commandFingerprint,
            Object command,
            ExecutionRecord output,
            Optional<ExecutionRecord> intermediate) {
        ExecutionMutationRow row = new ExecutionMutationRow();
        row.setTenantId(owner.executionRef().tenantId().value());
        row.setExecutionId(owner.executionRef().executionId());
        row.setSessionId(owner.owner().owner().sessionRef().sessionId());
        row.setTargetKind(owner.owner().owner().target().kind().name());
        row.setTargetId(owner.owner().owner().target().id());
        row.setAgentId(owner.owner().agentId().value());
        row.setEndUserId(ExecutionOwnerColumns.user(owner.owner().owner()));
        row.setRunId(owner.owner().runRef().runId());
        row.setMutationKey(key);
        row.setMutationKind(kind);
        row.setExpectedRevision(expectedRevision);
        row.setExpectedState(expectedState == null ? null : expectedState.name());
        row.setCommandFingerprint(commandFingerprint);
        row.setCommandData(ExecutionLedgerCodec.encode(command));
        row.setOutputRevision(output.executionRevision());
        row.setOutputState(output.state().name());
        row.setOutputFingerprint(fingerprint(output));
        row.setOutputData(ExecutionLedgerCodec.encode(output));
        intermediate.ifPresent(value -> {
            row.setIntermediateRevision(value.executionRevision());
            row.setIntermediateState(value.state().name());
            row.setIntermediateFingerprint(fingerprint(value));
            row.setIntermediateData(ExecutionLedgerCodec.encode(value));
        });
        return row;
    }

    private static ExecutionPublicationMutationRow publicationMutationRow(
            PublicationMutation mutation,
            String key,
            String commandFingerprint,
            StateEventPublication output,
            String status,
            String outputFingerprint) {
        ExecutionPublicationMutationRow row = new ExecutionPublicationMutationRow();
        row.setTenantId(mutation.tenantId().value());
        row.setSourceId(mutation.sourceId());
        row.setEventId(mutation.eventId().value());
        row.setMutationKey(key);
        row.setMutationKind(mutation.kind.name());
        row.setCommandFingerprint(commandFingerprint);
        row.setCommandData(ExecutionLedgerCodec.encode(mutation.command));
        row.setOutputRevision(output.publicationRevision());
        row.setOutputStatus(status);
        row.setOutputFingerprint(outputFingerprint);
        row.setOutputData(ExecutionLedgerCodec.encode(output));
        return row;
    }

    private static void validateMutationIdentity(
            ExecutionMutationRow row,
            String kind,
            long expectedRevision,
            WorkflowExecutionState expectedState) {
        if (!row.getMutationKind().equals(kind)
                || row.getExpectedRevision() != expectedRevision
                || !Objects.equals(row.getExpectedState(),
                        expectedState == null ? null : expectedState.name())) {
            throw new IllegalStateException("execution_mutation_journal_mismatch");
        }
    }

    private static WorkflowExecutionState currentStateAtCommand(
            ExecutionMutationRow row) {
        if (row.getExpectedState() == null) {
            throw new IllegalStateException("execution_mutation_expected_state_missing");
        }
        return WorkflowExecutionState.valueOf(row.getExpectedState());
    }

    private static String releaseKey(ExecutionLease lease) {
        return "release:sha256:" + sha256(
                lease.fence().claimId(),
                Long.toString(lease.fence().fencingToken()),
                Long.toString(lease.executionRevision()));
    }

    private static String cancelKey(RequestExecutionCancel command) {
        return "cancel:sha256:" + sha256(
                command.cancelRequestId().value(),
                command.cancelEvent().sessionEvent().eventId().value(),
                Long.toString(command.expectedRevision()));
    }

    private static String resumeKey(ResumeExecution command) {
        return "resume:sha256:" + sha256(
                command.resumedEvent().sessionEvent().eventId().value(),
                command.startedEvent().sessionEvent().eventId().value(),
                Long.toString(command.expectedRevision()));
    }

    static String fingerprint(Object value) {
        return sha256(ExecutionLedgerCodec.encode(value));
    }

    private static String publicationStateFingerprint(ExecutionPublicationRow row) {
        return sha256(
                row.getTenantId(), row.getSourceKind(), row.getSourceId(),
                row.getEventId(), Long.toString(row.getSourceRevision()),
                Long.toString(row.getPublicationRevision()),
                Long.toString(row.getAvailableEpochSecond()),
                Integer.toString(row.getAvailableNano()), row.getStatus(),
                Integer.toString(row.getAttempts()), row.getLeaseClaimId(),
                row.getLeaseWorkerId(), Objects.toString(row.getLeaseFencingToken(), null),
                Objects.toString(row.getLeaseUntilEpochSecond(), null),
                Objects.toString(row.getLeaseUntilNano(), null),
                row.getProjectionRef(), row.getFailureData(), row.getPublicationData());
    }

    private static PortFailure failure(String reason, Object... facts) {
        String[] values = new String[facts.length + 1];
        values[0] = reason;
        for (int index = 0; index < facts.length; index++) {
            values[index + 1] = Objects.toString(facts[index]);
        }
        return new PortFailure(
                reason, RetryMode.NONE,
                new ResultRef("execution-ledger:sha256:" + sha256(values)));
    }

    private static String sha256(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                if (value == null) {
                    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
                } else {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(Integer.BYTES)
                            .putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    private <T> T inTransaction(TenantId tenantId, MapperWork<T> work) {
        if (workflowScope != null) return work.execute(workflowScope.mapper(tenantId));
        try (SqlSession session = sessionFactory.openSession(false)) {
            try {
                ExecutionLedgerMapper mapper = session.getMapper(
                        ExecutionLedgerMapper.class);
                mapper.bindTenant(tenantId.value());
                T result = work.execute(mapper);
                session.commit();
                return result;
            } catch (RuntimeException failure) {
                try {
                    session.rollback();
                } catch (RuntimeException rollbackFailure) {
                    if (rollbackFailure != failure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                throw new ExecutionLedgerStorageException(failure);
            }
        } catch (RuntimeException failure) {
            if (failure instanceof ExecutionLedgerStorageException) {
                throw failure;
            }
            throw new ExecutionLedgerStorageException(failure);
        }
    }

    private static SqlSessionFactory buildSessionFactory(DataSource dataSource) {
        Environment environment = new Environment(
                "agent-runtime-execution-ledger",
                new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream mapperXml = JdbcExecutionLedgerPort.class.getClassLoader()
                .getResourceAsStream(MAPPER_RESOURCE)) {
            if (mapperXml == null) {
                throw new IllegalStateException("execution_ledger_mapper_missing");
            }
            new XMLMapperBuilder(
                    mapperXml, configuration, MAPPER_RESOURCE,
                    configuration.getSqlFragments()).parse();
            return new SqlSessionFactoryBuilder().build(configuration);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(
                    "execution_ledger_adapter_initialization_failure", failure);
        }
    }

    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    @FunctionalInterface
    private interface MapperWork<T> {
        T execute(ExecutionLedgerMapper mapper);
    }

    private enum MutationKind { PROGRESS, TRANSITION }

    private static final class Mutation {
        private final MutationKind kind;
        private final Object command;

        private Mutation(MutationKind kind, Object command) {
            this.kind = kind;
            this.command = command;
        }

        static Mutation progress(ProgressExecution command) {
            return new Mutation(MutationKind.PROGRESS, command);
        }

        static Mutation transition(TransitionExecution command) {
            return new Mutation(MutationKind.TRANSITION, command);
        }

        ExecutionLease lease() {
            return kind == MutationKind.PROGRESS
                    ? ((ProgressExecution) command).lease()
                    : ((TransitionExecution) command).lease();
        }

        long expectedRevision() {
            return kind == MutationKind.PROGRESS
                    ? ((ProgressExecution) command).expectedRevision()
                    : ((TransitionExecution) command).expectedRevision();
        }

        WorkflowExecutionState expectedState() {
            return kind == MutationKind.PROGRESS
                    ? WorkflowExecutionState.RUNNING
                    : ((TransitionExecution) command).expectedState();
        }

        LedgerEvent event() {
            return kind == MutationKind.PROGRESS
                    ? ((ProgressExecution) command).stepEvent()
                    : ((TransitionExecution) command).event();
        }

        String key() {
            return kind.name().toLowerCase(java.util.Locale.ROOT) + ":sha256:"
                    + sha256(event().sessionEvent().eventId().value(),
                            Long.toString(expectedRevision()));
        }

        ExecutionRecord output(
                ExecutionRecord current, long revision, Instant now) {
            if (kind == MutationKind.PROGRESS) {
                ProgressExecution progress = (ProgressExecution) command;
                return copyExecution(
                        current, WorkflowExecutionState.RUNNING, revision,
                        progress.checkpoint(), Optional.empty(), Optional.empty(),
                        Optional.empty(), current.lease(), current.cancelRequestId(),
                        current.cancelRequestedAt(), now);
            }
            TransitionExecution transition = (TransitionExecution) command;
            Optional<LeaseFence> lease = terminal(transition.targetState())
                    ? Optional.empty() : current.lease();
            return copyExecution(
                    current, transition.targetState(), revision,
                    transition.checkpoint(), transition.waiting(),
                    transition.resultRef(), transition.reasonCode(), lease,
                    current.cancelRequestId(), current.cancelRequestedAt(), now);
        }
    }

    private enum PublicationMutationKind { ACKNOWLEDGE, RETRY }

    private static final class PublicationMutation {
        private final PublicationMutationKind kind;
        private final Object command;

        private PublicationMutation(PublicationMutationKind kind, Object command) {
            this.kind = kind;
            this.command = command;
        }

        static PublicationMutation acknowledge(PublicationAcknowledge command) {
            return new PublicationMutation(
                    PublicationMutationKind.ACKNOWLEDGE, command);
        }

        static PublicationMutation retry(PublicationRetry command) {
            return new PublicationMutation(PublicationMutationKind.RETRY, command);
        }

        TenantId tenantId() {
            return kind == PublicationMutationKind.ACKNOWLEDGE
                    ? ((PublicationAcknowledge) command).tenantId()
                    : ((PublicationRetry) command).tenantId();
        }

        String sourceKind() {
            return kind == PublicationMutationKind.ACKNOWLEDGE
                    ? ((PublicationAcknowledge) command).sourceKind()
                    : ((PublicationRetry) command).sourceKind();
        }

        String sourceId() {
            return kind == PublicationMutationKind.ACKNOWLEDGE
                    ? ((PublicationAcknowledge) command).sourceId()
                    : ((PublicationRetry) command).sourceId();
        }

        EventId eventId() {
            return kind == PublicationMutationKind.ACKNOWLEDGE
                    ? ((PublicationAcknowledge) command).eventId()
                    : ((PublicationRetry) command).eventId();
        }

        LeaseFence fence() {
            return kind == PublicationMutationKind.ACKNOWLEDGE
                    ? ((PublicationAcknowledge) command).fence()
                    : ((PublicationRetry) command).fence();
        }

        long expectedRevision() {
            return kind == PublicationMutationKind.ACKNOWLEDGE
                    ? ((PublicationAcknowledge) command).expectedPublicationRevision()
                    : ((PublicationRetry) command).expectedPublicationRevision();
        }

        String key() {
            return kind.name().toLowerCase(java.util.Locale.ROOT) + ":sha256:"
                    + sha256(sourceKind(), sourceId(), eventId().value(),
                            fence().claimId(),
                            Long.toString(fence().fencingToken()),
                            Long.toString(expectedRevision()));
        }
    }

    /** 数据库执行和提交结果未知时只暴露稳定码，同时保留原原因链。 */
    private static final class ExecutionLedgerStorageException
            extends IllegalStateException {
        private ExecutionLedgerStorageException(Throwable cause) {
            super("execution_ledger_storage_failure", cause);
        }
    }
}

/** SQL 唯一维护在 AgentRuntimeExecutionLedgerMapper.xml。 */
interface ExecutionLedgerMapper {
    List<ExecutionLedgerRow> findExternalWait(@Param("tenantId") String tenantId,
            @Param("runId") String runId, @Param("suspendRef") String suspendRef);
    String bindTenant(@Param("tenantId") String tenantId);
    ExecutionStorageTimeRow storageNow();
    long nextFence();
    void lockKey(@Param("key") String key);

    ExecutionLedgerRow findExecution(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId);
    ExecutionLedgerRow lockExecution(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId);
    int insertExecution(ExecutionLedgerRow row);
    int updateExecution(
            @Param("row") ExecutionLedgerRow row,
            @Param("expectedRevision") long expectedRevision);
    List<ExecutionLedgerRow> scanRecoverable(
            @Param("tenantId") String tenantId,
            @Param("limit") int limit, @Param("afterExecutionId") String afterExecutionId);


    ExecutionMutationRow findMutation(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId,
            @Param("mutationKey") String mutationKey);
    int insertMutation(ExecutionMutationRow row);

    ExecutionPublicationRow findPublication(
            @Param("tenantId") String tenantId,
            @Param("sourceId") String sourceId,
            @Param("eventId") String eventId);
    ExecutionPublicationRow findPublicationByRevision(
            @Param("tenantId") String tenantId,
            @Param("sourceId") String sourceId,
            @Param("sourceRevision") long sourceRevision);
    ExecutionPublicationRow lockPublication(
            @Param("tenantId") String tenantId,
            @Param("sourceId") String sourceId,
            @Param("eventId") String eventId);
    List<ExecutionPublicationRow> lockEligiblePublications(
            @Param("tenantId") String tenantId,
            @Param("limit") int limit);
    int insertPublication(ExecutionPublicationRow row);
    int updatePublication(
            @Param("row") ExecutionPublicationRow row,
            @Param("expectedRevision") long expectedRevision);

    ExecutionPublicationMutationRow findPublicationMutation(
            @Param("tenantId") String tenantId,
            @Param("sourceId") String sourceId,
            @Param("eventId") String eventId,
            @Param("mutationKey") String mutationKey);
    int insertPublicationMutation(ExecutionPublicationMutationRow row);
}
