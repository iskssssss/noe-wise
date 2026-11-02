package com.skillforge.runtime.adapter.jdbc.execution;

import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.CreateExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionWait;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ProgressExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.RequestExecutionCancel;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ResumeExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.TransitionExecution;
import com.skillforge.runtime.api.port.LedgerEvent;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.StateEventPublication;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 数据库权威时钟一次采样的纳秒拆分值。 */
final class ExecutionStorageTimeRow {
    private long epochSecond;
    private int nano;

    ExecutionStorageTimeRow() {}

    Instant toInstant() {
        return Instant.ofEpochSecond(epochSecond, nano);
    }

    public long getEpochSecond() { return epochSecond; }
    public void setEpochSecond(long value) { epochSecond = value; }
    public int getNano() { return nano; }
    public void setNano(int value) { nano = value; }
}

/** Workflow Execution 当前权威状态及其完整 API 快照。 */
class ExecutionLedgerRow {
    private String tenantId;
    private String executionId;
    private String sessionId;
    private String targetKind;
    private String targetId;
    private String agentId;
    private Long endUserId;
    private String runId;
    private String causationInputId;
    private String executionUnitId;
    private String definitionVersion;
    private String executionMode;
    private String causativeActionId;
    private String delegationId;
    private String state;
    private long executionRevision;
    private String checkpointSchemaId;
    private String checkpointCanonicalJson;
    private String waitingSuspendRef;
    private String waitingActionKey;
    private String resultRef;
    private String reasonCode;
    private String leaseClaimId;
    private String leaseWorkerId;
    private Long leaseFencingToken;
    private Long leaseExecutionRevision;
    private Long leaseUntilEpochSecond;
    private Integer leaseUntilNano;
    private Long lastRequestedEpochSecond;
    private Integer lastRequestedNano;
    private String cancelRequestId;
    private Long cancelRequestedEpochSecond;
    private Integer cancelRequestedNano;
    private String recordData;
    private long createdEpochSecond;
    private int createdNano;
    private long updatedEpochSecond;
    private int updatedNano;

    ExecutionLedgerRow() {}

    static ExecutionLedgerRow from(ExecutionRecord record) {
        ExecutionLedgerRow row = new ExecutionLedgerRow();
        row.tenantId = record.executionRef().tenantId().value();
        row.executionId = record.executionRef().executionId();
        row.sessionId = record.owner().owner().sessionRef().sessionId();
        row.targetKind = record.owner().owner().target().kind().name();
        row.targetId = record.owner().owner().target().id();
        row.agentId = record.owner().agentId().value();
        row.endUserId = ExecutionOwnerColumns.user(record.owner().owner());
        row.runId = record.owner().runRef().runId();
        row.causationInputId = record.owner().causationInputId().value();
        row.executionUnitId = record.executionUnitId();
        row.definitionVersion = record.definitionVersion();
        row.executionMode = record.mode().name();
        row.causativeActionId = record.causativeActionRef()
                .map(value -> value.actionId()).orElse(null);
        row.delegationId = record.delegationRef()
                .map(value -> value.delegationId()).orElse(null);
        row.state = record.state().name();
        row.executionRevision = record.executionRevision();
        row.checkpointSchemaId = record.checkpoint().schemaId();
        row.checkpointCanonicalJson = record.checkpoint().canonicalJson();
        record.waiting().ifPresent(value -> {
            row.waitingSuspendRef = value.suspendRef();
            row.waitingActionKey = value.actionKey();
        });
        row.resultRef = record.resultRef().map(ResultRef::value).orElse(null);
        row.reasonCode = record.reasonCode().orElse(null);
        record.lease().ifPresent(row::setLease);
        row.cancelRequestId = record.cancelRequestId().map(InputId::value).orElse(null);
        record.cancelRequestedAt().ifPresent(value -> {
            row.cancelRequestedEpochSecond = value.getEpochSecond();
            row.cancelRequestedNano = value.getNano();
        });
        row.recordData = ExecutionLedgerCodec.encode(record);
        row.createdEpochSecond = record.createdAt().getEpochSecond();
        row.createdNano = record.createdAt().getNano();
        row.updatedEpochSecond = record.updatedAt().getEpochSecond();
        row.updatedNano = record.updatedAt().getNano();
        return row;
    }

    ExecutionRecord toRecord() {
        ExecutionRecord record = ExecutionLedgerCodec.decode(
                recordData, ExecutionRecord.class);
        if (!record.executionRef().tenantId().value().equals(tenantId)
                || !record.executionRef().executionId().equals(executionId)
                || !record.owner().owner().sessionRef().sessionId().equals(sessionId)
                || !record.owner().owner().target().kind().name().equals(targetKind)
                || !record.owner().owner().target().id().equals(targetId)
                || !record.owner().agentId().value().equals(agentId)
                || !Objects.equals(ExecutionOwnerColumns.user(record.owner().owner()), endUserId)
                || !record.owner().runRef().runId().equals(runId)
                || !record.owner().causationInputId().value().equals(causationInputId)
                || !record.executionUnitId().equals(executionUnitId)
                || !record.definitionVersion().equals(definitionVersion)
                || !record.mode().name().equals(executionMode)
                || !Objects.equals(record.causativeActionRef()
                        .map(value -> value.actionId()).orElse(null), causativeActionId)
                || !Objects.equals(record.delegationRef()
                        .map(value -> value.delegationId()).orElse(null), delegationId)
                || !record.state().name().equals(state)
                || record.executionRevision() != executionRevision
                || !record.checkpoint().schemaId().equals(checkpointSchemaId)
                || !record.checkpoint().canonicalJson().equals(checkpointCanonicalJson)
                || !record.waiting().equals(waiting())
                || !Objects.equals(record.resultRef().map(ResultRef::value).orElse(null), resultRef)
                || !Objects.equals(record.reasonCode().orElse(null), reasonCode)
                || !record.lease().equals(lease())
                || !Objects.equals(record.cancelRequestId()
                        .map(InputId::value).orElse(null), cancelRequestId)
                || !record.cancelRequestedAt().equals(cancelRequestedAt())
                || !record.createdAt().equals(createdAt())
                || !record.updatedAt().equals(updatedAt())) {
            throw new IllegalStateException("execution_row_mismatch");
        }
        return record;
    }

    Instant createdAt() {
        return Instant.ofEpochSecond(createdEpochSecond, createdNano);
    }

    Instant updatedAt() {
        return Instant.ofEpochSecond(updatedEpochSecond, updatedNano);
    }

    void setLease(LeaseFence value) {
        leaseClaimId = value.claimId();
        leaseWorkerId = value.workerId();
        leaseFencingToken = value.fencingToken();
        leaseUntilEpochSecond = value.leaseUntil().getEpochSecond();
        leaseUntilNano = value.leaseUntil().getNano();
    }

    void setLeaseExecutionRevision(long value) {
        leaseExecutionRevision = value;
    }

    void copyLeaseExecutionRevisionFrom(ExecutionLedgerRow source) {
        if (leaseClaimId != null) {
            leaseExecutionRevision = source.leaseExecutionRevision;
        }
    }

    void setLastRequestedAt(Instant value) {
        lastRequestedEpochSecond = value == null ? null : value.getEpochSecond();
        lastRequestedNano = value == null ? null : value.getNano();
    }

    void copyLastRequestedAtFrom(ExecutionLedgerRow source) {
        lastRequestedEpochSecond = source.lastRequestedEpochSecond;
        lastRequestedNano = source.lastRequestedNano;
    }

    private Optional<ExecutionWait> waiting() {
        if (waitingSuspendRef == null && waitingActionKey == null) {
            return Optional.empty();
        }
        if (waitingSuspendRef == null || waitingActionKey == null) {
            throw new IllegalStateException("execution_waiting_columns_mismatch");
        }
        return Optional.of(new ExecutionWait(waitingSuspendRef, waitingActionKey));
    }

    private Optional<LeaseFence> lease() {
        if (leaseClaimId == null && leaseWorkerId == null
                && leaseFencingToken == null && leaseExecutionRevision == null
                && leaseUntilEpochSecond == null && leaseUntilNano == null) {
            return Optional.empty();
        }
        if (leaseClaimId == null || leaseWorkerId == null
                || leaseFencingToken == null || leaseExecutionRevision == null
                || leaseUntilEpochSecond == null || leaseUntilNano == null) {
            throw new IllegalStateException("execution_lease_columns_mismatch");
        }
        return Optional.of(new LeaseFence(
                new TenantId(tenantId), leaseClaimId, leaseWorkerId,
                leaseFencingToken,
                Instant.ofEpochSecond(leaseUntilEpochSecond, leaseUntilNano)));
    }

    private Optional<Instant> cancelRequestedAt() {
        if (cancelRequestedEpochSecond == null && cancelRequestedNano == null) {
            return Optional.empty();
        }
        if (cancelRequestedEpochSecond == null || cancelRequestedNano == null) {
            throw new IllegalStateException("execution_cancel_columns_mismatch");
        }
        return Optional.of(Instant.ofEpochSecond(
                cancelRequestedEpochSecond, cancelRequestedNano));
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String value) { tenantId = value; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String value) { executionId = value; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String value) { sessionId = value; }
    public String getTargetKind() { return targetKind; }
    public void setTargetKind(String value) { targetKind = value; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String value) { targetId = value; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String value) { agentId = value; }
    public Long getEndUserId() { return endUserId; }
    public void setEndUserId(Long value) { endUserId = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public String getCausationInputId() { return causationInputId; }
    public void setCausationInputId(String value) { causationInputId = value; }
    public String getExecutionUnitId() { return executionUnitId; }
    public void setExecutionUnitId(String value) { executionUnitId = value; }
    public String getDefinitionVersion() { return definitionVersion; }
    public void setDefinitionVersion(String value) { definitionVersion = value; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String value) { executionMode = value; }
    public String getCausativeActionId() { return causativeActionId; }
    public void setCausativeActionId(String value) { causativeActionId = value; }
    public String getDelegationId() { return delegationId; }
    public void setDelegationId(String value) { delegationId = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public long getExecutionRevision() { return executionRevision; }
    public void setExecutionRevision(long value) { executionRevision = value; }
    public String getCheckpointSchemaId() { return checkpointSchemaId; }
    public void setCheckpointSchemaId(String value) { checkpointSchemaId = value; }
    public String getCheckpointCanonicalJson() { return checkpointCanonicalJson; }
    public void setCheckpointCanonicalJson(String value) { checkpointCanonicalJson = value; }
    public String getWaitingSuspendRef() { return waitingSuspendRef; }
    public void setWaitingSuspendRef(String value) { waitingSuspendRef = value; }
    public String getWaitingActionKey() { return waitingActionKey; }
    public void setWaitingActionKey(String value) { waitingActionKey = value; }
    public String getResultRef() { return resultRef; }
    public void setResultRef(String value) { resultRef = value; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String value) { reasonCode = value; }
    public String getLeaseClaimId() { return leaseClaimId; }
    public void setLeaseClaimId(String value) { leaseClaimId = value; }
    public String getLeaseWorkerId() { return leaseWorkerId; }
    public void setLeaseWorkerId(String value) { leaseWorkerId = value; }
    public Long getLeaseFencingToken() { return leaseFencingToken; }
    public void setLeaseFencingToken(Long value) { leaseFencingToken = value; }
    public Long getLeaseExecutionRevision() { return leaseExecutionRevision; }
    public void setLeaseExecutionRevision(Long value) { leaseExecutionRevision = value; }
    public Long getLeaseUntilEpochSecond() { return leaseUntilEpochSecond; }
    public void setLeaseUntilEpochSecond(Long value) { leaseUntilEpochSecond = value; }
    public Integer getLeaseUntilNano() { return leaseUntilNano; }
    public void setLeaseUntilNano(Integer value) { leaseUntilNano = value; }
    public Long getLastRequestedEpochSecond() { return lastRequestedEpochSecond; }
    public void setLastRequestedEpochSecond(Long value) { lastRequestedEpochSecond = value; }
    public Integer getLastRequestedNano() { return lastRequestedNano; }
    public void setLastRequestedNano(Integer value) { lastRequestedNano = value; }
    public String getCancelRequestId() { return cancelRequestId; }
    public void setCancelRequestId(String value) { cancelRequestId = value; }
    public Long getCancelRequestedEpochSecond() { return cancelRequestedEpochSecond; }
    public void setCancelRequestedEpochSecond(Long value) { cancelRequestedEpochSecond = value; }
    public Integer getCancelRequestedNano() { return cancelRequestedNano; }
    public void setCancelRequestedNano(Integer value) { cancelRequestedNano = value; }
    public String getRecordData() { return recordData; }
    public void setRecordData(String value) { recordData = value; }
    public long getCreatedEpochSecond() { return createdEpochSecond; }
    public void setCreatedEpochSecond(long value) { createdEpochSecond = value; }
    public int getCreatedNano() { return createdNano; }
    public void setCreatedNano(int value) { createdNano = value; }
    public long getUpdatedEpochSecond() { return updatedEpochSecond; }
    public void setUpdatedEpochSecond(long value) { updatedEpochSecond = value; }
    public int getUpdatedNano() { return updatedNano; }
    public void setUpdatedNano(int value) { updatedNano = value; }
}

/** Execution mutation 的 append-only 原命令与精确响应快照。 */
final class ExecutionMutationRow {
    private String tenantId;
    private String executionId;
    private String sessionId;
    private String targetKind;
    private String targetId;
    private String agentId;
    private Long endUserId;
    private String runId;
    private String mutationKey;
    private String mutationKind;
    private long expectedRevision;
    private String expectedState;
    private String commandFingerprint;
    private String commandData;
    private long outputRevision;
    private String outputState;
    private String outputFingerprint;
    private String outputData;
    private Long intermediateRevision;
    private String intermediateState;
    private String intermediateFingerprint;
    private String intermediateData;

    ExecutionMutationRow() {}

    <T> T command(Class<T> type) {
        T command = ExecutionLedgerCodec.decode(commandData, type);
        if (!JdbcExecutionLedgerPort.fingerprint(command).equals(commandFingerprint)
                || !commandIdentityMatches(command)) {
            throw new IllegalStateException("execution_mutation_command_mismatch");
        }
        return command;
    }

    ExecutionRecord output() {
        ExecutionRecord output = ExecutionLedgerCodec.decode(
                outputData, ExecutionRecord.class);
        if (output.executionRevision() != outputRevision
                || !output.state().name().equals(outputState)
                || !JdbcExecutionLedgerPort.fingerprint(output).equals(outputFingerprint)
                || !executionIdentityMatches(output)) {
            throw new IllegalStateException("execution_mutation_output_mismatch");
        }
        return output;
    }

    Optional<ExecutionRecord> intermediate() {
        boolean absent = intermediateRevision == null && intermediateState == null
                && intermediateFingerprint == null && intermediateData == null;
        if (absent) {
            return Optional.empty();
        }
        if (intermediateRevision == null || intermediateState == null
                || intermediateFingerprint == null || intermediateData == null) {
            throw new IllegalStateException("execution_mutation_intermediate_mismatch");
        }
        ExecutionRecord value = ExecutionLedgerCodec.decode(
                intermediateData, ExecutionRecord.class);
        if (value.executionRevision() != intermediateRevision
                || !value.state().name().equals(intermediateState)
                || !JdbcExecutionLedgerPort.fingerprint(value)
                        .equals(intermediateFingerprint)
                || !executionIdentityMatches(value)) {
            throw new IllegalStateException("execution_mutation_intermediate_mismatch");
        }
        return Optional.of(value);
    }

    private boolean executionIdentityMatches(ExecutionRecord record) {
        return record.executionRef().tenantId().value().equals(tenantId)
                && record.executionRef().executionId().equals(executionId)
                && record.owner().owner().sessionRef().sessionId().equals(sessionId)
                && record.owner().owner().target().kind().name().equals(targetKind)
                && record.owner().owner().target().id().equals(targetId)
                && record.owner().agentId().value().equals(agentId)
                && Objects.equals(ExecutionOwnerColumns.user(record.owner().owner()), endUserId)
                && record.owner().runRef().runId().equals(runId);
    }

    private boolean commandIdentityMatches(Object command) {
        if (command instanceof com.skillforge.runtime.api.port.ExecutionLedgerPort.CreateExecution value) {
            return value.executionRef().tenantId().value().equals(tenantId)
                    && value.executionRef().executionId().equals(executionId)
                    && value.owner().owner().sessionRef().sessionId().equals(sessionId)
                    && value.owner().owner().target().kind().name().equals(targetKind)
                    && value.owner().owner().target().id().equals(targetId)
                    && value.owner().agentId().value().equals(agentId)
                    && Objects.equals(ExecutionOwnerColumns.user(value.owner().owner()), endUserId)
                    && value.owner().runRef().runId().equals(runId);
        }
        if (command instanceof com.skillforge.runtime.api.port.ExecutionLedgerPort.RequestExecutionCancel value) {
            return value.executionRef().tenantId().value().equals(tenantId)
                    && value.executionRef().executionId().equals(executionId)
                    && value.identity().owner().sessionRef().sessionId().equals(sessionId)
                    && value.identity().owner().target().kind().name().equals(targetKind)
                    && value.identity().owner().target().id().equals(targetId)
                    && Objects.equals(ExecutionOwnerColumns.user(value.identity().owner()), endUserId);
        }
        com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLease lease =
                switch (command) {
                    case com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLease value -> value;
                    case com.skillforge.runtime.api.port.ExecutionLedgerPort.ProgressExecution value -> value.lease();
                    case com.skillforge.runtime.api.port.ExecutionLedgerPort.TransitionExecution value -> value.lease();
                    case com.skillforge.runtime.api.port.ExecutionLedgerPort.ResumeExecution value -> value.lease();
                    default -> null;
                };
        return lease != null
                && lease.executionRef().tenantId().value().equals(tenantId)
                && lease.executionRef().executionId().equals(executionId);
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String value) { tenantId = value; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String value) { executionId = value; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String value) { sessionId = value; }
    public String getTargetKind() { return targetKind; }
    public void setTargetKind(String value) { targetKind = value; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String value) { targetId = value; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String value) { agentId = value; }
    public Long getEndUserId() { return endUserId; }
    public void setEndUserId(Long value) { endUserId = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public String getMutationKey() { return mutationKey; }
    public void setMutationKey(String value) { mutationKey = value; }
    public String getMutationKind() { return mutationKind; }
    public void setMutationKind(String value) { mutationKind = value; }
    public long getExpectedRevision() { return expectedRevision; }
    public void setExpectedRevision(long value) { expectedRevision = value; }
    public String getExpectedState() { return expectedState; }
    public void setExpectedState(String value) { expectedState = value; }
    public String getCommandFingerprint() { return commandFingerprint; }
    public void setCommandFingerprint(String value) { commandFingerprint = value; }
    public String getCommandData() { return commandData; }
    public void setCommandData(String value) { commandData = value; }
    public long getOutputRevision() { return outputRevision; }
    public void setOutputRevision(long value) { outputRevision = value; }
    public String getOutputState() { return outputState; }
    public void setOutputState(String value) { outputState = value; }
    public String getOutputFingerprint() { return outputFingerprint; }
    public void setOutputFingerprint(String value) { outputFingerprint = value; }
    public String getOutputData() { return outputData; }
    public void setOutputData(String value) { outputData = value; }
    public Long getIntermediateRevision() { return intermediateRevision; }
    public void setIntermediateRevision(Long value) { intermediateRevision = value; }
    public String getIntermediateState() { return intermediateState; }
    public void setIntermediateState(String value) { intermediateState = value; }
    public String getIntermediateFingerprint() { return intermediateFingerprint; }
    public void setIntermediateFingerprint(String value) { intermediateFingerprint = value; }
    public String getIntermediateData() { return intermediateData; }
    public void setIntermediateData(String value) { intermediateData = value; }
}

/** Execution-owned state publication 的持久状态与完整冻结 API 值。 */
final class ExecutionPublicationRow {
    private String tenantId;
    private String sourceKind;
    private String sourceId;
    private String eventId;
    private long sourceRevision;
    private long publicationRevision;
    private long availableEpochSecond;
    private int availableNano;
    private String status;
    private int attempts;
    private String leaseClaimId;
    private String leaseWorkerId;
    private Long leaseFencingToken;
    private Long leaseUntilEpochSecond;
    private Integer leaseUntilNano;
    private String projectionRef;
    private String failureData;
    private String publicationData;
    private long createdEpochSecond;
    private int createdNano;
    private long updatedEpochSecond;
    private int updatedNano;
    private String sourceRecordData;
    private Long sourceMutationMatchCount;
    private String sourceMutationTenantId;
    private String sourceMutationExecutionId;
    private String sourceMutationSessionId;
    private String sourceMutationTargetKind;
    private String sourceMutationTargetId;
    private String sourceMutationAgentId;
    private Long sourceMutationEndUserId;
    private String sourceMutationRunId;
    private String sourceMutationMutationKey;
    private String sourceMutationMutationKind;
    private Long sourceMutationExpectedRevision;
    private String sourceMutationExpectedState;
    private String sourceMutationCommandFingerprint;
    private String sourceMutationCommandData;
    private Long sourceMutationOutputRevision;
    private String sourceMutationOutputState;
    private String sourceMutationOutputFingerprint;
    private String sourceMutationOutputData;
    private Long sourceMutationIntermediateRevision;
    private String sourceMutationIntermediateState;
    private String sourceMutationIntermediateFingerprint;
    private String sourceMutationIntermediateData;

    ExecutionPublicationRow() {}

    static ExecutionPublicationRow initial(
            StateEventPublication publication, Instant now) {
        return from(publication, "PENDING", 0, now, now);
    }

    static ExecutionPublicationRow from(
            StateEventPublication publication,
            String status,
            int attempts,
            Instant createdAt,
            Instant updatedAt) {
        ExecutionPublicationRow row = new ExecutionPublicationRow();
        row.tenantId = publication.tenantId().value();
        row.sourceKind = publication.sourceKind();
        row.sourceId = publication.sourceId();
        row.eventId = publication.event().sessionEvent().eventId().value();
        row.sourceRevision = publication.sourceRevision();
        row.publicationRevision = publication.publicationRevision();
        row.availableEpochSecond = publication.availableAt().getEpochSecond();
        row.availableNano = publication.availableAt().getNano();
        row.status = status;
        row.attempts = attempts;
        row.publicationData = ExecutionLedgerCodec.encode(publication);
        row.createdEpochSecond = createdAt.getEpochSecond();
        row.createdNano = createdAt.getNano();
        row.updatedEpochSecond = updatedAt.getEpochSecond();
        row.updatedNano = updatedAt.getNano();
        return row;
    }

    StateEventPublication toPublication() {
        StateEventPublication publication = ExecutionLedgerCodec.decode(
                publicationData, StateEventPublication.class);
        ExecutionRecord source = ExecutionLedgerCodec.decode(
                sourceRecordData, ExecutionRecord.class);
        ExecutionRecord historicalSource = validateSourceFact(publication);
        var target = publication.projectionTarget();
        validateStorageState();
        if (!publication.tenantId().value().equals(tenantId)
                || !publication.sourceKind().equals(sourceKind)
                || !publication.sourceId().equals(sourceId)
                || !publication.event().sessionEvent().eventId().value().equals(eventId)
                || publication.sourceRevision() != sourceRevision
                || publication.publicationRevision() != publicationRevision
                || !publication.availableAt().equals(availableAt())
                || !"execution".equals(sourceKind)
                || !source.executionRef().tenantId().value().equals(tenantId)
                || !source.executionRef().executionId().equals(sourceId)
                || !target.owner().equals(historicalSource.owner().owner())
                || !target.inputId().equals(
                        historicalSource.owner().causationInputId())
                || !target.runRef().equals(
                        Optional.of(historicalSource.owner().runRef()))
                || !target.executionRef().equals(
                        Optional.of(historicalSource.executionRef()))
                || !target.actionRef().equals(
                        historicalSource.causativeActionRef())
                || !target.delegationRef().equals(historicalSource.delegationRef())
                || !publication.runtimeEventDestinationIds().isEmpty()) {
            throw new IllegalStateException("execution_publication_row_mismatch");
        }
        return publication;
    }

    private ExecutionRecord validateSourceFact(
            StateEventPublication publication) {
        try {
            ExecutionMutationRow mutation = sourceMutation();
            ExecutionRecord historical;
            LedgerEvent frozenEvent;
            boolean registered;
            switch (mutation.getMutationKind()) {
                case "CREATE" -> {
                    CreateExecution command = mutation.command(CreateExecution.class);
                    historical = mutation.output();
                    requireSourceFact(mutation.getExpectedRevision() == 0
                            && mutation.getExpectedState() == null
                            && historical.executionRevision() == 1
                            && historical.state().name().equals("QUEUED"));
                    frozenEvent = command.queuedEvent();
                    registered = ExecutionLedgerEventCodec.validCreate(
                            command, historical);
                }
                case "PROGRESS" -> {
                    ProgressExecution command = mutation.command(
                            ProgressExecution.class);
                    historical = mutation.output();
                    requireSourceFact(mutation.getExpectedRevision()
                                    == command.expectedRevision()
                            && Objects.equals(mutation.getExpectedState(), "RUNNING")
                            && historical.executionRevision()
                                    == Math.incrementExact(command.expectedRevision())
                            && historical.state().name().equals("RUNNING"));
                    frozenEvent = command.stepEvent();
                    registered = ExecutionLedgerEventCodec.validProgress(
                            command, historical);
                }
                case "TRANSITION" -> {
                    TransitionExecution command = mutation.command(
                            TransitionExecution.class);
                    historical = mutation.output();
                    requireSourceFact(mutation.getExpectedRevision()
                                    == command.expectedRevision()
                            && Objects.equals(mutation.getExpectedState(),
                                    command.expectedState().name())
                            && historical.executionRevision()
                                    == Math.incrementExact(command.expectedRevision())
                            && historical.state() == command.targetState());
                    frozenEvent = command.event();
                    registered = ExecutionLedgerEventCodec.validTransition(
                            command, historical);
                }
                case "CANCEL" -> {
                    RequestExecutionCancel command = mutation.command(
                            RequestExecutionCancel.class);
                    historical = mutation.output();
                    requireSourceFact(mutation.getExpectedRevision()
                                    == command.expectedRevision()
                            && Objects.equals(mutation.getExpectedState(),
                                    historical.state().name())
                            && historical.executionRevision()
                                    == Math.incrementExact(command.expectedRevision()));
                    frozenEvent = command.cancelEvent();
                    registered = ExecutionLedgerEventCodec.validCancel(
                            command, historical);
                }
                case "RESUME" -> {
                    ResumeExecution command = mutation.command(ResumeExecution.class);
                    ExecutionRecord intermediate = mutation.intermediate()
                            .orElseThrow(() -> new IllegalStateException(
                                    "execution_publication_resume_intermediate_missing"));
                    ExecutionRecord output = mutation.output();
                    requireSourceFact(mutation.getExpectedRevision()
                                    == command.expectedRevision()
                            && Objects.equals(mutation.getExpectedState(),
                                    command.expectedState().name())
                            && intermediate.executionRevision()
                                    == Math.incrementExact(command.expectedRevision())
                            && intermediate.state().name().equals("RESUMING")
                            && output.executionRevision()
                                    == Math.addExact(command.expectedRevision(), 2)
                            && output.state().name().equals("RUNNING"));
                    boolean isIntermediate = sourceRevision
                            == intermediate.executionRevision();
                    boolean isOutput = sourceRevision == output.executionRevision();
                    requireSourceFact(isIntermediate != isOutput);
                    historical = isIntermediate ? intermediate : output;
                    frozenEvent = isIntermediate
                            ? command.resumedEvent() : command.startedEvent();
                    registered = ExecutionLedgerEventCodec.validResume(
                            command, intermediate, output);
                }
                default -> throw new IllegalStateException(
                        "execution_publication_source_mutation_kind_mismatch");
            }
            requireSourceFact(registered
                    && ExecutionLedgerEventCodec.validStored(
                            publication, historical, frozenEvent));
            return historical;
        } catch (RuntimeException mismatch) {
            throw new IllegalStateException(
                    "execution_publication_source_fact_mismatch", mismatch);
        }
    }

    private ExecutionMutationRow sourceMutation() {
        requireSourceFact(Long.valueOf(1).equals(sourceMutationMatchCount));
        ExecutionMutationRow mutation = new ExecutionMutationRow();
        mutation.setTenantId(sourceMutationTenantId);
        mutation.setExecutionId(sourceMutationExecutionId);
        mutation.setSessionId(sourceMutationSessionId);
        mutation.setTargetKind(sourceMutationTargetKind);
        mutation.setTargetId(sourceMutationTargetId);
        mutation.setAgentId(sourceMutationAgentId);
        mutation.setEndUserId(sourceMutationEndUserId);
        mutation.setRunId(sourceMutationRunId);
        mutation.setMutationKey(sourceMutationMutationKey);
        mutation.setMutationKind(sourceMutationMutationKind);
        mutation.setExpectedRevision(
                Objects.requireNonNull(sourceMutationExpectedRevision));
        mutation.setExpectedState(sourceMutationExpectedState);
        mutation.setCommandFingerprint(sourceMutationCommandFingerprint);
        mutation.setCommandData(sourceMutationCommandData);
        mutation.setOutputRevision(
                Objects.requireNonNull(sourceMutationOutputRevision));
        mutation.setOutputState(sourceMutationOutputState);
        mutation.setOutputFingerprint(sourceMutationOutputFingerprint);
        mutation.setOutputData(sourceMutationOutputData);
        mutation.setIntermediateRevision(sourceMutationIntermediateRevision);
        mutation.setIntermediateState(sourceMutationIntermediateState);
        mutation.setIntermediateFingerprint(sourceMutationIntermediateFingerprint);
        mutation.setIntermediateData(sourceMutationIntermediateData);
        return mutation;
    }

    private static void requireSourceFact(boolean condition) {
        if (!condition) {
            throw new IllegalStateException(
                    "execution_publication_source_mutation_mismatch");
        }
    }

    private void validateStorageState() {
        boolean hasLease = leaseClaimId != null || leaseWorkerId != null
                || leaseFencingToken != null || leaseUntilEpochSecond != null
                || leaseUntilNano != null;
        if (!(status.equals("PENDING") || status.equals("CLAIMED")
                        || status.equals("ACKNOWLEDGED"))
                || (status.equals("CLAIMED") != hasLease)
                || (status.equals("ACKNOWLEDGED") != (projectionRef != null))) {
            throw new IllegalStateException("execution_publication_state_mismatch");
        }
        if (hasLease && (leaseClaimId == null || leaseWorkerId == null
                || leaseFencingToken == null || leaseUntilEpochSecond == null
                || leaseUntilNano == null)) {
            throw new IllegalStateException("execution_publication_lease_mismatch");
        }
        if (failureData != null) {
            ExecutionLedgerCodec.decode(failureData, PortFailure.class);
        }
    }

    Instant availableAt() {
        return Instant.ofEpochSecond(availableEpochSecond, availableNano);
    }

    Instant createdAt() {
        return Instant.ofEpochSecond(createdEpochSecond, createdNano);
    }

    void setLease(LeaseFence fence) {
        leaseClaimId = fence.claimId();
        leaseWorkerId = fence.workerId();
        leaseFencingToken = fence.fencingToken();
        leaseUntilEpochSecond = fence.leaseUntil().getEpochSecond();
        leaseUntilNano = fence.leaseUntil().getNano();
    }

    void clearLease() {
        leaseClaimId = null;
        leaseWorkerId = null;
        leaseFencingToken = null;
        leaseUntilEpochSecond = null;
        leaseUntilNano = null;
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String value) { tenantId = value; }
    public String getSourceKind() { return sourceKind; }
    public void setSourceKind(String value) { sourceKind = value; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String value) { sourceId = value; }
    public String getEventId() { return eventId; }
    public void setEventId(String value) { eventId = value; }
    public long getSourceRevision() { return sourceRevision; }
    public void setSourceRevision(long value) { sourceRevision = value; }
    public long getPublicationRevision() { return publicationRevision; }
    public void setPublicationRevision(long value) { publicationRevision = value; }
    public long getAvailableEpochSecond() { return availableEpochSecond; }
    public void setAvailableEpochSecond(long value) { availableEpochSecond = value; }
    public int getAvailableNano() { return availableNano; }
    public void setAvailableNano(int value) { availableNano = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int value) { attempts = value; }
    public String getLeaseClaimId() { return leaseClaimId; }
    public void setLeaseClaimId(String value) { leaseClaimId = value; }
    public String getLeaseWorkerId() { return leaseWorkerId; }
    public void setLeaseWorkerId(String value) { leaseWorkerId = value; }
    public Long getLeaseFencingToken() { return leaseFencingToken; }
    public void setLeaseFencingToken(Long value) { leaseFencingToken = value; }
    public Long getLeaseUntilEpochSecond() { return leaseUntilEpochSecond; }
    public void setLeaseUntilEpochSecond(Long value) { leaseUntilEpochSecond = value; }
    public Integer getLeaseUntilNano() { return leaseUntilNano; }
    public void setLeaseUntilNano(Integer value) { leaseUntilNano = value; }
    public String getProjectionRef() { return projectionRef; }
    public void setProjectionRef(String value) { projectionRef = value; }
    public String getFailureData() { return failureData; }
    public void setFailureData(String value) { failureData = value; }
    public String getPublicationData() { return publicationData; }
    public void setPublicationData(String value) { publicationData = value; }
    public long getCreatedEpochSecond() { return createdEpochSecond; }
    public void setCreatedEpochSecond(long value) { createdEpochSecond = value; }
    public int getCreatedNano() { return createdNano; }
    public void setCreatedNano(int value) { createdNano = value; }
    public long getUpdatedEpochSecond() { return updatedEpochSecond; }
    public void setUpdatedEpochSecond(long value) { updatedEpochSecond = value; }
    public int getUpdatedNano() { return updatedNano; }
    public void setUpdatedNano(int value) { updatedNano = value; }
    public String getSourceRecordData() { return sourceRecordData; }
    public void setSourceRecordData(String value) { sourceRecordData = value; }
    public Long getSourceMutationMatchCount() { return sourceMutationMatchCount; }
    public void setSourceMutationMatchCount(Long value) {
        sourceMutationMatchCount = value;
    }
    public String getSourceMutationTenantId() { return sourceMutationTenantId; }
    public void setSourceMutationTenantId(String value) {
        sourceMutationTenantId = value;
    }
    public String getSourceMutationExecutionId() { return sourceMutationExecutionId; }
    public void setSourceMutationExecutionId(String value) {
        sourceMutationExecutionId = value;
    }
    public String getSourceMutationSessionId() { return sourceMutationSessionId; }
    public void setSourceMutationSessionId(String value) {
        sourceMutationSessionId = value;
    }
    public String getSourceMutationTargetKind() { return sourceMutationTargetKind; }
    public void setSourceMutationTargetKind(String value) {
        sourceMutationTargetKind = value;
    }
    public String getSourceMutationTargetId() { return sourceMutationTargetId; }
    public void setSourceMutationTargetId(String value) {
        sourceMutationTargetId = value;
    }
    public String getSourceMutationAgentId() { return sourceMutationAgentId; }
    public void setSourceMutationAgentId(String value) {
        sourceMutationAgentId = value;
    }
    public Long getSourceMutationEndUserId() { return sourceMutationEndUserId; }
    public void setSourceMutationEndUserId(Long value) {
        sourceMutationEndUserId = value;
    }
    public String getSourceMutationRunId() { return sourceMutationRunId; }
    public void setSourceMutationRunId(String value) { sourceMutationRunId = value; }
    public String getSourceMutationMutationKey() { return sourceMutationMutationKey; }
    public void setSourceMutationMutationKey(String value) {
        sourceMutationMutationKey = value;
    }
    public String getSourceMutationMutationKind() { return sourceMutationMutationKind; }
    public void setSourceMutationMutationKind(String value) {
        sourceMutationMutationKind = value;
    }
    public Long getSourceMutationExpectedRevision() {
        return sourceMutationExpectedRevision;
    }
    public void setSourceMutationExpectedRevision(Long value) {
        sourceMutationExpectedRevision = value;
    }
    public String getSourceMutationExpectedState() { return sourceMutationExpectedState; }
    public void setSourceMutationExpectedState(String value) {
        sourceMutationExpectedState = value;
    }
    public String getSourceMutationCommandFingerprint() {
        return sourceMutationCommandFingerprint;
    }
    public void setSourceMutationCommandFingerprint(String value) {
        sourceMutationCommandFingerprint = value;
    }
    public String getSourceMutationCommandData() { return sourceMutationCommandData; }
    public void setSourceMutationCommandData(String value) {
        sourceMutationCommandData = value;
    }
    public Long getSourceMutationOutputRevision() { return sourceMutationOutputRevision; }
    public void setSourceMutationOutputRevision(Long value) {
        sourceMutationOutputRevision = value;
    }
    public String getSourceMutationOutputState() { return sourceMutationOutputState; }
    public void setSourceMutationOutputState(String value) {
        sourceMutationOutputState = value;
    }
    public String getSourceMutationOutputFingerprint() {
        return sourceMutationOutputFingerprint;
    }
    public void setSourceMutationOutputFingerprint(String value) {
        sourceMutationOutputFingerprint = value;
    }
    public String getSourceMutationOutputData() { return sourceMutationOutputData; }
    public void setSourceMutationOutputData(String value) {
        sourceMutationOutputData = value;
    }
    public Long getSourceMutationIntermediateRevision() {
        return sourceMutationIntermediateRevision;
    }
    public void setSourceMutationIntermediateRevision(Long value) {
        sourceMutationIntermediateRevision = value;
    }
    public String getSourceMutationIntermediateState() {
        return sourceMutationIntermediateState;
    }
    public void setSourceMutationIntermediateState(String value) {
        sourceMutationIntermediateState = value;
    }
    public String getSourceMutationIntermediateFingerprint() {
        return sourceMutationIntermediateFingerprint;
    }
    public void setSourceMutationIntermediateFingerprint(String value) {
        sourceMutationIntermediateFingerprint = value;
    }
    public String getSourceMutationIntermediateData() {
        return sourceMutationIntermediateData;
    }
    public void setSourceMutationIntermediateData(String value) {
        sourceMutationIntermediateData = value;
    }
}

/** Publication ack/retry 的 append-only operation journal。 */
final class ExecutionPublicationMutationRow {
    private String tenantId;
    private String sourceId;
    private String eventId;
    private String mutationKey;
    private String mutationKind;
    private String commandFingerprint;
    private String commandData;
    private long outputRevision;
    private String outputStatus;
    private String outputFingerprint;
    private String outputData;

    ExecutionPublicationMutationRow() {}

    <T> T command(Class<T> type) {
        T command = ExecutionLedgerCodec.decode(commandData, type);
        if (!JdbcExecutionLedgerPort.fingerprint(command).equals(commandFingerprint)
                || !commandIdentityMatches(command)) {
            throw new IllegalStateException("execution_publication_command_mismatch");
        }
        return command;
    }

    StateEventPublication output() {
        StateEventPublication output = ExecutionLedgerCodec.decode(
                outputData, StateEventPublication.class);
        if (output.publicationRevision() != outputRevision
                || !output.tenantId().value().equals(tenantId)
                || !output.sourceKind().equals("execution")
                || !output.sourceId().equals(sourceId)
                || !output.event().sessionEvent().eventId().value().equals(eventId)) {
            throw new IllegalStateException("execution_publication_output_mismatch");
        }
        return output;
    }

    private boolean commandIdentityMatches(Object command) {
        if (command instanceof com.skillforge.runtime.api.port.PublicationAcknowledge value) {
            return value.tenantId().value().equals(tenantId)
                    && value.sourceKind().equals("execution")
                    && value.sourceId().equals(sourceId)
                    && value.eventId().value().equals(eventId);
        }
        if (command instanceof com.skillforge.runtime.api.port.PublicationRetry value) {
            return value.tenantId().value().equals(tenantId)
                    && value.sourceKind().equals("execution")
                    && value.sourceId().equals(sourceId)
                    && value.eventId().value().equals(eventId);
        }
        return false;
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String value) { tenantId = value; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String value) { sourceId = value; }
    public String getEventId() { return eventId; }
    public void setEventId(String value) { eventId = value; }
    public String getMutationKey() { return mutationKey; }
    public void setMutationKey(String value) { mutationKey = value; }
    public String getMutationKind() { return mutationKind; }
    public void setMutationKind(String value) { mutationKind = value; }
    public String getCommandFingerprint() { return commandFingerprint; }
    public void setCommandFingerprint(String value) { commandFingerprint = value; }
    public String getCommandData() { return commandData; }
    public void setCommandData(String value) { commandData = value; }
    public long getOutputRevision() { return outputRevision; }
    public void setOutputRevision(long value) { outputRevision = value; }
    public String getOutputStatus() { return outputStatus; }
    public void setOutputStatus(String value) { outputStatus = value; }
    public String getOutputFingerprint() { return outputFingerprint; }
    public void setOutputFingerprint(String value) { outputFingerprint = value; }
    public String getOutputData() { return outputData; }
    public void setOutputData(String value) { outputData = value; }
}
