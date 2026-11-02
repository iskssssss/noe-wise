package com.skillforge.runtime.adapter.jdbc.execution;

import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.CreateExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLease;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionWriteResult;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import com.skillforge.runtime.api.port.WorkflowNodeCallIdentity;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/**
 * Workflow 组合节点对子 Execution 创建命令的不可变来源 journal。
 *
 * <p>准备只在父 Run 和父 Execution 的当前写租约下发生；所有子 Execution 的 QUEUED
 * 创建、父准备和逐子映射在同一事务提交。读取只证明来源，不授予子 Execution 的运行或写权。
 */
public final class JdbcWorkflowChildExecutions {

    private static final String EXECUTION_MAPPER_RESOURCE =
            "mapper/AgentRuntimeExecutionLedgerMapper.xml";
    private static final String MAPPER_RESOURCE =
            "mapper/AgentRuntimeWorkflowChildExecutionsMapper.xml";
    private static final String RECORD_SCHEMA =
            "skillforge.runtime.workflow-child-executions-preparation.v1";
    private static final int MAX_CHILDREN = 32;

    private final SqlSessionFactory sessions;
    private final SourceGuard sourceGuard;

    public JdbcWorkflowChildExecutions(
            DataSource dataSource, SourceGuard sourceGuard) {
        this.sessions = buildSessions(Objects.requireNonNull(dataSource, "dataSource"));
        this.sourceGuard = Objects.requireNonNull(sourceGuard, "sourceGuard");
    }

    /**
     * 在固定 Run → 父 Execution 锁序下首次创建全部子 Execution 并冻结来源。
     * 既有准备只接受完整准备和首次父 revision 均精确相同的重放。
     */
    public Preparation prepare(
            VerifiedIdentity identity,
            ExecutionLease parentLease,
            long expectedRevision,
            Preparation candidate) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(parentLease, "parentLease");
        Objects.requireNonNull(candidate, "candidate");
        if (expectedRevision <= 0) {
            throw invalid("workflow_child_executions_revision_invalid");
        }

        WorkflowNodeCallIdentity key = candidate.parent();
        if (!identity.owner().equals(key.owner().owner())
                || !parentLease.executionRef().equals(key.executionRef())) {
            throw invalid("workflow_child_executions_owner_mismatch");
        }

        String tenantId = key.executionRef().tenantId().value();
        try (SqlSession session = sessions.openSession(false);
                WorkflowTransactionScope scope = new WorkflowTransactionScope(
                        session, key.executionRef().tenantId())) {
            try {
                WorkflowChildExecutionsMapper mapper = mapper(session, tenantId);
                LockedParent locked = lockParent(
                        mapper, identity, parentLease, expectedRevision, key);
                WorkflowChildPreparationRow existing = findPreparation(mapper, key);
                if (existing != null) {
                    Preparation original = restore(
                            existing, findSources(mapper, key));
                    if (existing.preparedExecutionRevision != expectedRevision
                            || !original.equals(candidate)) {
                        throw rejected("workflow_child_executions_preparation_conflict");
                    }
                    sourceGuard.require(identity, locked.execution(), original);
                    session.commit();
                    return original;
                }

                if (!locked.execution().checkpoint().equals(
                        candidate.sourceCheckpoint())) {
                    throw rejected("workflow_child_executions_checkpoint_conflict");
                }
                // 宿主游标、定义和安装来源必须在父 Execution 行锁内重验。
                sourceGuard.require(identity, locked.execution(), candidate);

                JdbcExecutionLedgerPort ledger = new JdbcExecutionLedgerPort(scope);
                for (Child child : candidate.children()) {
                    ExecutionWriteResult result = ledger.create(child.command());
                    if (!(result instanceof ExecutionWriteResult.Applied applied)
                            || !createdFrom(applied.execution(), child.command())) {
                        throw rejected("workflow_child_executions_child_create_rejected");
                    }
                }

                WorkflowChildPreparationRow preparationRow = row(
                        candidate, expectedRevision);
                if (mapper.insertPreparation(preparationRow) != 1) {
                    throw rejected("workflow_child_executions_preparation_not_applied");
                }
                for (int index = 0; index < candidate.children().size(); index++) {
                    WorkflowChildExecutionSourceRow sourceRow = sourceRow(
                            candidate, candidate.children().get(index), index);
                    if (mapper.insertSource(sourceRow) != 1) {
                        throw rejected("workflow_child_executions_source_not_applied");
                    }
                }

                Preparation stored = restore(
                        findPreparationRequired(mapper, key), findSources(mapper, key));
                if (!stored.equals(candidate)) {
                    throw rejected("workflow_child_executions_prepare_mismatch");
                }
                session.commit();
                return stored;
            } catch (RuntimeException failure) {
                try {
                    session.rollback();
                } catch (RuntimeException rollbackFailure) {
                    if (rollbackFailure != failure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                throw failure;
            }
        }
    }

    /** 按已验证的完整父 owner 读取原准备；返回值不包含任何当前写权限。 */
    public Optional<Preparation> find(
            VerifiedIdentity identity, WorkflowNodeCallIdentity parentKey) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(parentKey, "parentKey");
        if (!identity.owner().equals(parentKey.owner().owner())) {
            return Optional.empty();
        }

        String tenantId = parentKey.executionRef().tenantId().value();
        try (SqlSession session = sessions.openSession(false)) {
            WorkflowChildExecutionsMapper mapper = mapper(session, tenantId);
            WorkflowChildPreparationRow row = findPreparation(mapper, parentKey);
            if (row == null) {
                return Optional.empty();
            }
            Preparation stored = restore(row, findSources(mapper, parentKey));
            return stored.parent().equals(parentKey)
                    ? Optional.of(stored) : Optional.empty();
        }
    }

    /**
     * 按已验证身份和子 ExecutionRef 读取原父准备与创建命令，并核对当前子 Execution
     * 的固定身份。该入口不取得 lease，也不授予运行或写权。
     */
    public Optional<ChildSource> source(
            VerifiedIdentity identity, ExecutionRef childRef) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(childRef, "childRef");
        if (!identity.owner().sessionRef().tenantId().equals(childRef.tenantId())) {
            return Optional.empty();
        }

        try (SqlSession session = sessions.openSession(false)) {
            WorkflowChildExecutionsMapper mapper = mapper(
                    session, childRef.tenantId().value());
            Optional<StoredSource> stored = storedSource(mapper, childRef);
            if (stored.isEmpty()
                    || !stored.orElseThrow().preparation().parent().owner().owner()
                            .equals(identity.owner())) {
                return Optional.empty();
            }
            requireCurrentChild(mapper, stored.orElseThrow().child().command());
            return Optional.of(stored.orElseThrow().toSource());
        }
    }

    /**
     * 从数据库恢复原来源并核对传入 Execution 的全部固定身份字段；运行中的 checkpoint、
     * state、revision 与 lease 不参与来源判定。
     */
    public Optional<ChildSource> source(ExecutionRecord child) {
        Objects.requireNonNull(child, "child");
        ExecutionRef childRef = child.executionRef();
        try (SqlSession session = sessions.openSession(false)) {
            WorkflowChildExecutionsMapper mapper = mapper(
                    session, childRef.tenantId().value());
            Optional<StoredSource> stored = storedSource(mapper, childRef);
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            CreateExecution command = stored.orElseThrow().child().command();
            requireCurrentChild(mapper, command);
            return sameFixedIdentity(child, command)
                    ? Optional.of(stored.orElseThrow().toSource())
                    : Optional.empty();
        }
    }

    /** 宿主来源守卫；必须核验父节点游标、定义 revision、安装 revision 与组合调度许可。 */
    @FunctionalInterface
    public interface SourceGuard {
        void require(
                VerifiedIdentity identity,
                ExecutionRecord parent,
                Preparation preparation);
    }

    /** 首次准备后逐字段不可变的父节点来源、版本和全部子创建命令。 */
    public record Preparation(
            WorkflowNodeCallIdentity parent,
            long definitionRevision,
            long installationRevision,
            TypedPayload sourceCheckpoint,
            List<Child> children) {

        public Preparation {
            Objects.requireNonNull(parent, "parent");
            Objects.requireNonNull(sourceCheckpoint, "sourceCheckpoint");
            children = List.copyOf(Objects.requireNonNull(children, "children"));
            if (definitionRevision <= 0 || installationRevision <= 0
                    || children.isEmpty() || children.size() > MAX_CHILDREN) {
                throw invalid("workflow_child_executions_preparation_invalid");
            }

            Set<String> branches = new HashSet<>();
            Set<ExecutionRef> childRefs = new HashSet<>();
            for (Child child : children) {
                Objects.requireNonNull(child, "child");
                CreateExecution command = child.command();
                if (!branches.add(child.branchId())
                        || !childRefs.add(command.executionRef())
                        || command.executionRef().equals(parent.executionRef())
                        || !command.executionRef().tenantId().equals(
                                parent.executionRef().tenantId())
                        || !command.owner().equals(parent.owner())
                        || command.causativeActionRef().isPresent()
                        || command.delegationRef().isPresent()) {
                    throw invalid("workflow_child_executions_child_invalid");
                }
            }
        }

        @Override
        public String toString() {
            return "WorkflowChildExecutionsPreparation[parent=" + parent
                    + ",definitionRevision=" + definitionRevision
                    + ",installationRevision=" + installationRevision
                    + ",sourceCheckpoint=REDACTED,children=" + children.size() + "]";
        }
    }

    /** 父节点分支标识及其原始子 Execution 创建命令。 */
    public record Child(String branchId, CreateExecution command) {
        public Child {
            Objects.requireNonNull(branchId, "branchId");
            Objects.requireNonNull(command, "command");
            if (branchId.isBlank()) {
                throw invalid("workflow_child_executions_branch_invalid");
            }
        }

        @Override
        public String toString() {
            return "WorkflowChild[branchId=" + branchId
                    + ",executionRef=" + command.executionRef() + "]";
        }
    }

    /** 已从不可变 journal 恢复并与当前子 Execution 固定身份闭合的来源。 */
    public record ChildSource(Preparation preparation, Child child) {
        public ChildSource {
            Objects.requireNonNull(preparation, "preparation");
            Objects.requireNonNull(child, "child");
            if (!preparation.children().contains(child)) {
                throw invalid("workflow_child_executions_source_invalid");
            }
        }
    }

    /** Run 行锁只暴露来源校验需要的物理 owner、状态和取消事实。 */
    public static final class RunSource {
        public String tenantId;
        public String sessionId;
        public String targetKind;
        public String targetId;
        public String agentId;
        public Long endUserId;
        public String runId;
        public String causationInputId;
        public String status;
        public String cancelRequestId;
        public Long cancelRequestedEpochSecond;
        public Integer cancelRequestedNano;

        public RunSource() {}

        boolean matches(RunIdentity owner) {
            return tenantId.equals(owner.runRef().tenantId().value())
                    && sessionId.equals(owner.owner().sessionRef().sessionId())
                    && targetKind.equals(owner.owner().target().kind().name())
                    && targetId.equals(owner.owner().target().id())
                    && agentId.equals(owner.agentId().value())
                    && Objects.equals(endUserId, ExecutionOwnerColumns.user(owner.owner()))
                    && runId.equals(owner.runRef().runId())
                    && causationInputId.equals(owner.causationInputId().value());
        }

        boolean active() {
            return !List.of("COMPLETED", "TERMINATED", "FAILED", "CANCELLED")
                    .contains(status)
                    && cancelRequestId == null
                    && cancelRequestedEpochSecond == null
                    && cancelRequestedNano == null;
        }
    }

    private LockedParent lockParent(
            WorkflowChildExecutionsMapper mapper,
            VerifiedIdentity identity,
            ExecutionLease parentLease,
            long expectedRevision,
            WorkflowNodeCallIdentity key) {
        String tenantId = key.executionRef().tenantId().value();
        RunSource run = mapper.lockRun(tenantId, key.owner().runRef().runId());
        if (run == null || !run.matches(key.owner())) {
            throw rejected("workflow_child_executions_run_missing_or_mismatched");
        }
        if (!run.active()) {
            throw rejected("workflow_child_executions_run_inactive");
        }

        ExecutionLedgerRow parentRow = mapper.lockExecution(
                tenantId, key.executionRef().executionId());
        if (parentRow == null) {
            throw rejected("workflow_child_executions_parent_missing");
        }
        ExecutionRecord parent = parentRow.toRecord();
        Instant now = mapper.storageNow().toInstant();
        if (!parent.executionRef().equals(key.executionRef())
                || !parent.owner().equals(key.owner())
                || !parent.owner().owner().equals(identity.owner())
                || !parentLease.executionRef().equals(parent.executionRef())) {
            throw rejected("workflow_child_executions_owner_mismatch");
        }
        if (parent.state() != WorkflowExecutionState.RUNNING) {
            throw rejected("workflow_child_executions_parent_not_running");
        }
        if (parent.cancelRequestId().isPresent()
                || parent.cancelRequestedAt().isPresent()) {
            throw rejected("workflow_child_executions_parent_cancelled");
        }
        if (parent.executionRevision() != expectedRevision) {
            throw rejected("workflow_child_executions_revision_conflict");
        }
        if (parentRow.getLeaseExecutionRevision() == null
                || parentRow.getLeaseExecutionRevision()
                        != parentLease.executionRevision()
                || parent.lease().isEmpty()
                || !parent.lease().orElseThrow().equals(parentLease.fence())
                || !now.isBefore(parentLease.fence().leaseUntil())) {
            throw rejected("workflow_child_executions_lease_lost");
        }
        return new LockedParent(parent);
    }

    private static Optional<StoredSource> storedSource(
            WorkflowChildExecutionsMapper mapper, ExecutionRef childRef) {
        WorkflowChildExecutionSourceRow source = mapper.findSource(
                childRef.tenantId().value(), childRef.executionId());
        if (source == null) {
            return Optional.empty();
        }
        WorkflowChildPreparationRow preparation = mapper.findPreparation(
                source.tenantId, source.parentExecutionId, source.nodeId,
                source.visit, source.callIndex);
        if (preparation == null) {
            throw rejected("workflow_child_executions_parent_journal_missing");
        }
        WorkflowNodeCallIdentity parentKey = restorePreparation(preparation).parent();
        Preparation restored = restore(
                preparation, findSources(mapper, parentKey));
        if (source.childOrdinal < 0
                || source.childOrdinal >= restored.children().size()) {
            throw rejected("workflow_child_executions_source_corrupted");
        }
        Child child = restored.children().get(source.childOrdinal);
        Child mapped = restoreSource(source);
        if (!child.equals(mapped)
                || !child.command().executionRef().equals(childRef)) {
            throw rejected("workflow_child_executions_source_corrupted");
        }
        return Optional.of(new StoredSource(restored, child));
    }

    private static void requireCurrentChild(
            WorkflowChildExecutionsMapper mapper, CreateExecution command) {
        ExecutionRef ref = command.executionRef();
        ExecutionLedgerRow row = mapper.findExecution(
                ref.tenantId().value(), ref.executionId());
        if (row == null || !sameFixedIdentity(row.toRecord(), command)) {
            throw rejected("workflow_child_executions_child_identity_corrupted");
        }
    }

    private static boolean sameFixedIdentity(
            ExecutionRecord child, CreateExecution command) {
        return child.executionRef().equals(command.executionRef())
                && child.owner().equals(command.owner())
                && child.executionUnitId().equals(command.executionUnitId())
                && child.definitionVersion().equals(command.definitionVersion())
                && child.mode() == command.mode()
                && child.causativeActionRef().equals(command.causativeActionRef())
                && child.delegationRef().equals(command.delegationRef());
    }

    private static boolean createdFrom(
            ExecutionRecord child, CreateExecution command) {
        return sameFixedIdentity(child, command)
                && child.state() == WorkflowExecutionState.QUEUED
                && child.executionRevision() == 1
                && child.checkpoint().equals(command.initialCheckpoint())
                && child.waiting().isEmpty()
                && child.resultRef().isEmpty()
                && child.reasonCode().isEmpty()
                && child.lease().isEmpty()
                && child.cancelRequestId().isEmpty()
                && child.cancelRequestedAt().isEmpty();
    }

    private static WorkflowChildPreparationRow row(
            Preparation value, long preparedExecutionRevision) {
        WorkflowNodeCallIdentity key = value.parent();
        String preparationData = encode(value);
        WorkflowChildPreparationRow row = new WorkflowChildPreparationRow();
        row.tenantId = key.executionRef().tenantId().value();
        row.parentExecutionId = key.executionRef().executionId();
        row.sessionId = key.owner().owner().sessionRef().sessionId();
        row.targetKind = key.owner().owner().target().kind().name();
        row.targetId = key.owner().owner().target().id();
        row.agentId = key.owner().agentId().value();
        row.endUserId = ExecutionOwnerColumns.user(key.owner().owner());
        row.runId = key.owner().runRef().runId();
        row.nodeId = key.nodeId();
        row.visit = key.visit();
        row.callIndex = key.call();
        row.definitionRevision = value.definitionRevision();
        row.installationRevision = value.installationRevision();
        row.preparedExecutionRevision = preparedExecutionRevision;
        row.sourceCheckpointFingerprint = fingerprint(
                ExecutionLedgerCodec.encode(value.sourceCheckpoint()));
        row.preparationFingerprint = fingerprint(preparationData);
        row.preparationData = preparationData;
        return row;
    }

    private static WorkflowChildExecutionSourceRow sourceRow(
            Preparation preparation, Child child, int childOrdinal) {
        WorkflowNodeCallIdentity parent = preparation.parent();
        CreateExecution command = child.command();
        String commandData = ExecutionLedgerCodec.encode(command);
        WorkflowChildExecutionSourceRow row = new WorkflowChildExecutionSourceRow();
        row.tenantId = parent.executionRef().tenantId().value();
        row.childExecutionId = command.executionRef().executionId();
        row.parentExecutionId = parent.executionRef().executionId();
        row.nodeId = parent.nodeId();
        row.visit = parent.visit();
        row.callIndex = parent.call();
        row.childOrdinal = childOrdinal;
        row.branchId = child.branchId();
        row.sessionId = parent.owner().owner().sessionRef().sessionId();
        row.targetKind = parent.owner().owner().target().kind().name();
        row.targetId = parent.owner().owner().target().id();
        row.agentId = parent.owner().agentId().value();
        row.endUserId = ExecutionOwnerColumns.user(parent.owner().owner());
        row.runId = parent.owner().runRef().runId();
        row.commandFingerprint = fingerprint(commandData);
        row.commandData = commandData;
        return row;
    }

    private static Preparation restore(
            WorkflowChildPreparationRow row,
            List<WorkflowChildExecutionSourceRow> sources) {
        Preparation preparation = restorePreparation(row);
        if (sources.size() != preparation.children().size()) {
            throw rejected("workflow_child_executions_source_count_mismatch");
        }
        for (int index = 0; index < sources.size(); index++) {
            WorkflowChildExecutionSourceRow source = sources.get(index);
            if (source.childOrdinal != index
                    || !preparation.children().get(index).equals(
                            restoreSource(source))) {
                throw rejected("workflow_child_executions_source_corrupted");
            }
            requireSourceParent(source, preparation.parent());
        }
        return preparation;
    }

    private static Preparation restorePreparation(
            WorkflowChildPreparationRow row) {
        Preparation value = decode(row.preparationData);
        WorkflowNodeCallIdentity key = value.parent();
        if (!row.tenantId.equals(key.executionRef().tenantId().value())
                || !row.parentExecutionId.equals(key.executionRef().executionId())
                || !row.sessionId.equals(key.owner().owner().sessionRef().sessionId())
                || !row.targetKind.equals(key.owner().owner().target().kind().name())
                || !row.targetId.equals(key.owner().owner().target().id())
                || !row.agentId.equals(key.owner().agentId().value())
                || !Objects.equals(row.endUserId, ExecutionOwnerColumns.user(key.owner().owner()))
                || !row.runId.equals(key.owner().runRef().runId())
                || !row.nodeId.equals(key.nodeId())
                || row.visit != key.visit()
                || row.callIndex != key.call()
                || row.definitionRevision != value.definitionRevision()
                || row.installationRevision != value.installationRevision()
                || row.preparedExecutionRevision <= 0
                || !row.sourceCheckpointFingerprint.equals(fingerprint(
                        ExecutionLedgerCodec.encode(value.sourceCheckpoint())))
                || !row.preparationFingerprint.equals(
                        fingerprint(row.preparationData))
                || !row.preparationData.equals(encode(value))) {
            throw rejected("workflow_child_executions_preparation_corrupted");
        }
        return value;
    }

    private static Child restoreSource(WorkflowChildExecutionSourceRow row) {
        CreateExecution command = ExecutionLedgerCodec.decode(
                row.commandData, CreateExecution.class);
        Child child = new Child(row.branchId, command);
        RunIdentity owner = command.owner();
        if (!row.tenantId.equals(command.executionRef().tenantId().value())
                || !row.childExecutionId.equals(
                        command.executionRef().executionId())
                || !row.sessionId.equals(owner.owner().sessionRef().sessionId())
                || !row.targetKind.equals(owner.owner().target().kind().name())
                || !row.targetId.equals(owner.owner().target().id())
                || !row.agentId.equals(owner.agentId().value())
                || !Objects.equals(row.endUserId, ExecutionOwnerColumns.user(owner.owner()))
                || !row.runId.equals(owner.runRef().runId())
                || !row.commandFingerprint.equals(fingerprint(row.commandData))
                || !row.commandData.equals(ExecutionLedgerCodec.encode(command))) {
            throw rejected("workflow_child_executions_source_corrupted");
        }
        return child;
    }

    private static void requireSourceParent(
            WorkflowChildExecutionSourceRow row,
            WorkflowNodeCallIdentity parent) {
        if (!row.tenantId.equals(parent.executionRef().tenantId().value())
                || !row.parentExecutionId.equals(
                        parent.executionRef().executionId())
                || !row.nodeId.equals(parent.nodeId())
                || row.visit != parent.visit()
                || row.callIndex != parent.call()
                || !row.sessionId.equals(
                        parent.owner().owner().sessionRef().sessionId())
                || !row.targetKind.equals(
                        parent.owner().owner().target().kind().name())
                || !row.targetId.equals(parent.owner().owner().target().id())
                || !row.agentId.equals(parent.owner().agentId().value())
                || !Objects.equals(row.endUserId, ExecutionOwnerColumns.user(parent.owner().owner()))
                || !row.runId.equals(parent.owner().runRef().runId())) {
            throw rejected("workflow_child_executions_source_parent_mismatch");
        }
    }

    private static String encode(Preparation value) {
        List<List<Object>> children = value.children().stream()
                .map(child -> List.<Object>of(
                        child.branchId(), child.command()))
                .toList();
        return ExecutionLedgerCodec.encode(List.of(
                RECORD_SCHEMA,
                value.parent(),
                value.definitionRevision(),
                value.installationRevision(),
                value.sourceCheckpoint(),
                children));
    }

    static Preparation decode(String encoded) {
        List<?> values = ExecutionLedgerCodec.decode(encoded, List.class);
        if (values.size() != 6 || !RECORD_SCHEMA.equals(values.get(0))) {
            throw rejected("workflow_child_executions_record_invalid");
        }
        try {
            List<?> encodedChildren = (List<?>) values.get(5);
            List<Child> children = new ArrayList<>(encodedChildren.size());
            for (Object encodedChild : encodedChildren) {
                List<?> fields = (List<?>) encodedChild;
                if (fields.size() != 2) {
                    throw rejected("workflow_child_executions_record_invalid");
                }
                children.add(new Child(
                        (String) fields.get(0),
                        (CreateExecution) fields.get(1)));
            }
            Preparation value = new Preparation(
                    (WorkflowNodeCallIdentity) values.get(1),
                    (Long) values.get(2),
                    (Long) values.get(3),
                    (TypedPayload) values.get(4),
                    children);
            if (!encoded.equals(encode(value))) {
                throw rejected("workflow_child_executions_record_noncanonical");
            }
            return value;
        } catch (ClassCastException failure) {
            throw new IllegalStateException(
                    "workflow_child_executions_record_invalid", failure);
        }
    }

    private static String fingerprint(String encoded) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    "workflow_child_executions_fingerprint_unavailable", failure);
        }
    }

    private static WorkflowChildExecutionsMapper mapper(
            SqlSession session, String tenantId) {
        WorkflowChildExecutionsMapper mapper = session.getMapper(
                WorkflowChildExecutionsMapper.class);
        mapper.bindTenant(tenantId);
        return mapper;
    }

    private static WorkflowChildPreparationRow findPreparation(
            WorkflowChildExecutionsMapper mapper,
            WorkflowNodeCallIdentity key) {
        return mapper.findPreparation(
                key.executionRef().tenantId().value(),
                key.executionRef().executionId(), key.nodeId(),
                key.visit(), key.call());
    }

    private static WorkflowChildPreparationRow findPreparationRequired(
            WorkflowChildExecutionsMapper mapper,
            WorkflowNodeCallIdentity key) {
        WorkflowChildPreparationRow row = findPreparation(mapper, key);
        if (row == null) {
            throw rejected("workflow_child_executions_preparation_missing");
        }
        return row;
    }

    private static List<WorkflowChildExecutionSourceRow> findSources(
            WorkflowChildExecutionsMapper mapper,
            WorkflowNodeCallIdentity key) {
        return mapper.findSources(
                key.executionRef().tenantId().value(),
                key.executionRef().executionId(), key.nodeId(),
                key.visit(), key.call());
    }

    private static SqlSessionFactory buildSessions(DataSource dataSource) {
        Configuration configuration = new Configuration(new Environment(
                "workflow-child-executions",
                new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        for (String resource : List.of(
                EXECUTION_MAPPER_RESOURCE, MAPPER_RESOURCE)) {
            try (InputStream xml = JdbcWorkflowChildExecutions.class
                    .getClassLoader().getResourceAsStream(resource)) {
                if (xml == null) {
                    throw new IllegalStateException(
                            "workflow_child_executions_mapper_missing");
                }
                new XMLMapperBuilder(
                        xml, configuration, resource,
                        configuration.getSqlFragments()).parse();
            } catch (Exception failure) {
                throw new IllegalStateException(
                        "workflow_child_executions_initialization_failure", failure);
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }

    private static IllegalStateException rejected(String reason) {
        return new IllegalStateException(reason);
    }

    private record LockedParent(ExecutionRecord execution) {}

    private record StoredSource(Preparation preparation, Child child) {
        ChildSource toSource() {
            return new ChildSource(preparation, child);
        }
    }
}

/** SQL 仅由 AgentRuntimeWorkflowChildExecutionsMapper.xml 维护。 */
interface WorkflowChildExecutionsMapper {
    String bindTenant(@Param("tenantId") String tenantId);

    ExecutionStorageTimeRow storageNow();

    JdbcWorkflowChildExecutions.RunSource lockRun(
            @Param("tenantId") String tenantId,
            @Param("runId") String runId);

    ExecutionLedgerRow lockExecution(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId);

    ExecutionLedgerRow findExecution(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId);

    WorkflowChildPreparationRow findPreparation(
            @Param("tenantId") String tenantId,
            @Param("parentExecutionId") String parentExecutionId,
            @Param("nodeId") String nodeId,
            @Param("visit") int visit,
            @Param("callIndex") int callIndex);

    List<WorkflowChildExecutionSourceRow> findSources(
            @Param("tenantId") String tenantId,
            @Param("parentExecutionId") String parentExecutionId,
            @Param("nodeId") String nodeId,
            @Param("visit") int visit,
            @Param("callIndex") int callIndex);

    WorkflowChildExecutionSourceRow findSource(
            @Param("tenantId") String tenantId,
            @Param("childExecutionId") String childExecutionId);

    int insertPreparation(WorkflowChildPreparationRow row);

    int insertSource(WorkflowChildExecutionSourceRow row);
}

/** 父组合节点的一次完整不可变准备。 */
final class WorkflowChildPreparationRow {
    public String tenantId;
    public String parentExecutionId;
    public String sessionId;
    public String targetKind;
    public String targetId;
    public String agentId;
    public Long endUserId;
    public String runId;
    public String nodeId;
    public int visit;
    public int callIndex;
    public long definitionRevision;
    public long installationRevision;
    public long preparedExecutionRevision;
    public String sourceCheckpointFingerprint;
    public String preparationFingerprint;
    public String preparationData;
}

/** 单个子 Execution 到父准备和原创建命令的不可变映射。 */
final class WorkflowChildExecutionSourceRow {
    public String tenantId;
    public String childExecutionId;
    public String parentExecutionId;
    public String nodeId;
    public int visit;
    public int callIndex;
    public int childOrdinal;
    public String branchId;
    public String sessionId;
    public String targetKind;
    public String targetId;
    public String agentId;
    public Long endUserId;
    public String runId;
    public String commandFingerprint;
    public String commandData;
}
