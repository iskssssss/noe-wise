package com.skillforge.runtime.adapter.jdbc.execution;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLease;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import com.skillforge.runtime.api.port.WorkflowNodeCallIdentity;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * Workflow 脚本节点的单次提交 journal。
 *
 * <p>PREPARED 首次转为 DISPATCHED 后永不回退；即使提交结果未知，恢复也只能查询原 taskId，
 * 不能再次授予 submit。所有状态写入都遵循 Run → Execution → script call 的锁顺序。
 */
public final class JdbcWorkflowScriptCalls {

    private static final String MAPPER_RESOURCE =
            "mapper/AgentRuntimeWorkflowScriptCallsMapper.xml";
    private static final String PREPARATION_SCHEMA =
            "skillforge.runtime.workflow-script-preparation.v1";
    private static final String TERMINAL_SCHEMA =
            "skillforge.runtime.workflow-script-terminal.v1";
    private static final String JSON_SCHEMA = "application/json";

    private final SqlSessionFactory sessions;
    private final SourceGuard sourceGuard;

    public JdbcWorkflowScriptCalls(DataSource dataSource, SourceGuard sourceGuard,
            java.util.function.Consumer<Configuration> mapperConfigurer) {
        this.sessions = buildSessions(Objects.requireNonNull(dataSource, "dataSource"), Objects.requireNonNull(mapperConfigurer, "mapperConfigurer"));
        this.sourceGuard = Objects.requireNonNull(sourceGuard, "sourceGuard");
    }
    public static void configure(Configuration configuration) {
        if(configuration.hasMapper(WorkflowScriptCallsMapper.class))return;
        try(InputStream xml=JdbcWorkflowScriptCalls.class.getClassLoader().getResourceAsStream(MAPPER_RESOURCE)) {
            if(xml==null)throw new IllegalStateException("workflow_script_call_mapper_missing");
            new XMLMapperBuilder(xml,configuration,MAPPER_RESOURCE,configuration.getSqlFragments()).parse();
        } catch(java.io.IOException failure){throw new IllegalStateException("workflow_script_call_initialization_failure",failure);}
    }
    public static Optional<Entry> findBorrowed(SqlSession session,WorkflowNodeCallIdentity key) {
        var mapper=session.getMapper(WorkflowScriptCallsMapper.class);var row=find(mapper,key);
        if(row==null)return Optional.empty();var entry=restore(row);
        return entry.preparation().identity().equals(key)?Optional.of(entry):Optional.empty();
    }

    /** 同一调用身份只暴露给已验证的完整 Session owner，不取得写锁或授予外调权。 */
    public Optional<Entry> find(
            VerifiedIdentity identity, WorkflowNodeCallIdentity key) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(key, "key");
        if (!identity.owner().equals(key.owner().owner())) {
            return Optional.empty();
        }
        String tenantId = key.executionRef().tenantId().value();
        try (SqlSession session = sessions.openSession(false)) {
            WorkflowScriptCallsMapper mapper = session.getMapper(
                    WorkflowScriptCallsMapper.class);
            mapper.bindTenant(tenantId);
            WorkflowScriptCallRow row = find(mapper, key);
            if (row == null) {
                return Optional.empty();
            }
            Entry entry = restore(row);
            return entry.preparation().identity().equals(key)
                    ? Optional.of(entry) : Optional.empty();
        }
    }

    /**
     * 无锁读取指定 Execution 尚未收敛的调用；返回值只证明原任务存在，不授予 cancel 或其他写权。
     */
    public List<Entry> active(
            VerifiedIdentity identity, ExecutionRecord execution) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(execution, "execution");
        if (!identity.owner().equals(execution.owner().owner())) {
            return List.of();
        }
        RunIdentity owner = execution.owner();
        String tenantId = execution.executionRef().tenantId().value();
        try (SqlSession session = sessions.openSession(false)) {
            WorkflowScriptCallsMapper mapper = mapper(session, tenantId);
            List<Entry> entries = mapper.active(tenantId,
                            execution.executionRef().executionId(),
                            owner.owner().sessionRef().sessionId(),
                            owner.owner().target().kind().name(),
                            owner.owner().target().id(), owner.agentId().value(),
                            ExecutionOwnerColumns.user(owner.owner()),
                            owner.runRef().runId()).stream()
                    .map(JdbcWorkflowScriptCalls::restore)
                    .toList();
            for (Entry entry : entries) {
                WorkflowNodeCallIdentity key = entry.preparation().identity();
                if ((entry.state() != State.PREPARED
                        && entry.state() != State.DISPATCHED)
                        || !key.executionRef().equals(execution.executionRef())
                        || !key.owner().equals(owner)) {
                    throw rejected("workflow_script_call_active_identity_mismatch");
                }
                // 只枚举原 owner 的事实；取消权限在逐项 currentCancelling 的锁内重验。
            }
            return entries;
        }
    }

    /**
     * 在当前 Run/Execution 锁和 lease 下首次冻结脚本请求；既有值只允许逐字段精确重放。
     * 首次冻结校验 sourceCheckpoint，重放由宿主守卫核验仍指向相同节点和版本。
     */
    public Entry prepare(
            VerifiedIdentity identity,
            ExecutionLease lease,
            long expectedRevision,
            Preparation candidate) {
        Objects.requireNonNull(candidate, "candidate");
        requireMutationIdentity(identity, lease, expectedRevision,
                candidate.identity());
        String tenantId = candidate.identity().executionRef().tenantId().value();
        try (SqlSession session = sessions.openSession(false)) {
            WorkflowScriptCallsMapper mapper = mapper(session, tenantId);
            LockedSource locked = lockSource(mapper, identity, lease,
                    expectedRevision, candidate.identity(), SourceMode.RUNNING);
            WorkflowScriptCallRow existing = find(mapper, candidate.identity());
            if (existing != null) {
                Entry entry = restore(existing);
                if (!entry.preparation().equals(candidate)) {
                    throw rejected("workflow_script_call_preparation_conflict");
                }
                requireBeforeDeadline(locked.now(), entry.preparation());
                sourceGuard.require(session,identity, locked.execution(),
                        entry.preparation());
                session.commit();
                return entry;
            }

            if (!locked.execution().checkpoint().equals(
                    candidate.sourceCheckpoint())) {
                throw rejected("workflow_script_call_checkpoint_conflict");
            }
            requireBeforeDeadline(locked.now(), candidate);
            sourceGuard.require(session,identity, locked.execution(), candidate);
            WorkflowScriptCallRow row = row(candidate, expectedRevision);
            if (mapper.insert(row) != 1) {
                throw rejected("workflow_script_call_prepare_not_applied");
            }
            Entry inserted = restore(findRequired(mapper, candidate.identity()));
            Entry expected = new Entry(candidate, State.PREPARED, Optional.empty());
            if (!inserted.equals(expected)) {
                throw rejected("workflow_script_call_prepare_mismatch");
            }
            session.commit();
            return inserted;
        }
    }

    /**
     * 仅首次 PREPARED → DISPATCHED 返回 true；任何已 dispatch 或终态重放均返回 false，
     * 调用方从此只能 query 原 taskId。
     */
    public boolean dispatch(
            VerifiedIdentity identity,
            ExecutionLease lease,
            long expectedRevision,
            WorkflowNodeCallIdentity key) {
        requireMutationIdentity(identity, lease, expectedRevision, key);
        String tenantId = key.executionRef().tenantId().value();
        try (SqlSession session = sessions.openSession(false)) {
            WorkflowScriptCallsMapper mapper = mapper(session, tenantId);
            LockedSource locked = lockSource(mapper, identity, lease,
                    expectedRevision, key, SourceMode.RUNNING);
            Entry entry = restore(findRequired(mapper, key));
            requireBeforeDeadline(locked.now(), entry.preparation());
            sourceGuard.require(session,identity, locked.execution(),
                    entry.preparation());
            if (entry.state() != State.PREPARED) {
                session.commit();
                return false;
            }
            if (mapper.dispatch(tenantId, key.executionRef().executionId(),
                    key.nodeId(), key.visit(), key.call()) != 1) {
                throw rejected("workflow_script_call_dispatch_not_applied");
            }
            Entry dispatched = restore(findRequired(mapper, key));
            if (dispatched.state() != State.DISPATCHED
                    || dispatched.result().isPresent()) {
                throw rejected("workflow_script_call_dispatch_mismatch");
            }
            session.commit();
            return true;
        }
    }

    /** 当前授权和 Execution fence 下读取稳定状态；业务 deadline 过期不妨碍查询收敛。 */
    public Entry current(
            VerifiedIdentity identity,
            ExecutionLease lease,
            long expectedRevision,
            WorkflowNodeCallIdentity key) {
        return current(identity, lease, expectedRevision, key,
                SourceMode.RUNNING);
    }

    /** WAITING_EXTERNAL 只查询原已提交任务的稳定状态，不授予 dispatch 或延长业务 deadline。 */
    public Entry currentWaiting(
            VerifiedIdentity identity,
            ExecutionLease lease,
            long expectedRevision,
            WorkflowNodeCallIdentity key) {
        return current(identity, lease, expectedRevision, key,
                SourceMode.WAITING_EXTERNAL);
    }

    /** 持久取消事实下只查询原任务；PREPARED 证明从未 dispatch，不取得沙箱 cancel 权。 */
    public Entry currentCancelling(
            VerifiedIdentity identity,
            ExecutionLease lease,
            long expectedRevision,
            WorkflowNodeCallIdentity key) {
        return current(identity, lease, expectedRevision, key,
                SourceMode.CANCELLING);
    }

    private Entry current(
            VerifiedIdentity identity,
            ExecutionLease lease,
            long expectedRevision,
            WorkflowNodeCallIdentity key,
            SourceMode mode) {
        requireMutationIdentity(identity, lease, expectedRevision, key);
        String tenantId = key.executionRef().tenantId().value();
        try (SqlSession session = sessions.openSession(false)) {
            WorkflowScriptCallsMapper mapper = mapper(session, tenantId);
            LockedSource locked = lockSource(mapper, identity, lease,
                    expectedRevision, key, mode);
            Entry entry = restore(findRequired(mapper, key));
            sourceGuard.require(session,identity, locked.execution(),
                    entry.preparation());
            session.commit();
            return entry;
        }
    }

    /**
     * 仅 DISPATCHED 可首次写入终态；同终态和同结果逐字节重放返回原值，其他组合冲突拒绝。
     * deadline 过期后仍允许保存 timeout、cancel 或迟到的权威终态。
     */
    public Entry record(
            VerifiedIdentity identity,
            ExecutionLease lease,
            long expectedRevision,
            WorkflowNodeCallIdentity key,
            State terminalState,
            TypedPayload result) {
        return record(identity, lease, expectedRevision, key, terminalState,
                result, SourceMode.RUNNING);
    }

    /** WAITING_EXTERNAL 仅把已 DISPATCHED 任务收敛为权威终态，精确终态重放保持幂等。 */
    public Entry recordWaiting(
            VerifiedIdentity identity,
            ExecutionLease lease,
            long expectedRevision,
            WorkflowNodeCallIdentity key,
            State terminalState,
            TypedPayload result) {
        return record(identity, lease, expectedRevision, key, terminalState,
                result, SourceMode.WAITING_EXTERNAL);
    }

    /** 取消清理只允许原 DISPATCHED 调用收敛到确定终态，或精确重放既有终态。 */
    public Entry recordCancelling(
            VerifiedIdentity identity,
            ExecutionLease lease,
            long expectedRevision,
            WorkflowNodeCallIdentity key,
            State terminalState,
            TypedPayload result) {
        return record(identity, lease, expectedRevision, key, terminalState,
                result, SourceMode.CANCELLING);
    }

    private Entry record(
            VerifiedIdentity identity,
            ExecutionLease lease,
            long expectedRevision,
            WorkflowNodeCallIdentity key,
            State terminalState,
            TypedPayload result,
            SourceMode mode) {
        requireMutationIdentity(identity, lease, expectedRevision, key);
        Objects.requireNonNull(terminalState, "terminalState");
        Objects.requireNonNull(result, "result");
        if (!terminal(terminalState)) {
            throw invalid("workflow_script_call_terminal_state_invalid");
        }
        String terminalData = encodeTerminal(terminalState, result);
        String tenantId = key.executionRef().tenantId().value();
        try (SqlSession session = sessions.openSession(false)) {
            WorkflowScriptCallsMapper mapper = mapper(session, tenantId);
            LockedSource locked = lockSource(mapper, identity, lease,
                    expectedRevision, key, mode);
            Entry existing = restore(findRequired(mapper, key));
            sourceGuard.require(session,identity, locked.execution(),
                    existing.preparation());
            if (terminal(existing.state())) {
                if (existing.state() != terminalState
                        || !existing.result().orElseThrow().equals(result)) {
                    throw rejected("workflow_script_call_terminal_conflict");
                }
                session.commit();
                return existing;
            }
            if (existing.state() != State.DISPATCHED) {
                throw rejected("workflow_script_call_not_dispatched");
            }
            if (mapper.record(tenantId, key.executionRef().executionId(),
                    key.nodeId(), key.visit(), key.call(), terminalState.name(),
                    fingerprint(terminalData), terminalData) != 1) {
                throw rejected("workflow_script_call_terminal_not_applied");
            }
            Entry saved = restore(findRequired(mapper, key));
            Entry expected = new Entry(existing.preparation(), terminalState,
                    Optional.of(result));
            if (!saved.equals(expected)) {
                throw rejected("workflow_script_call_terminal_mismatch");
            }
            session.commit();
            return saved;
        }
    }

    /** 宿主来源守卫在同一 Run/Execution 锁内验证当前身份、授权、游标和安装绑定。 */
    @FunctionalInterface
    public interface SourceGuard {
        void require(SqlSession session,VerifiedIdentity identity, ExecutionRecord current,
                Preparation prepared);
    }

    /** 脚本调用只允许一次提交，再由查询收敛到一个终态。 */
    public enum State {
        PREPARED,
        DISPATCHED,
        COMPLETED,
        FAILED,
        CANCELLED,
        TIMED_OUT
    }

    /** 首次准备后逐字段不可变的脚本任务来源和规范请求。 */
    public record Preparation(
            WorkflowNodeCallIdentity identity,
            long definitionRevision,
            long installationRevision,
            TypedPayload sourceCheckpoint,
            String taskId,
            TypedPayload request,
            Instant deadlineAt) {

        public Preparation {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(sourceCheckpoint, "sourceCheckpoint");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(deadlineAt, "deadlineAt");
            if (definitionRevision <= 0 || installationRevision <= 0
                    || !taskId.matches("[0-9a-f]{64}")
                    || !JSON_SCHEMA.equals(request.schemaId())) {
                throw invalid("workflow_script_call_preparation_invalid");
            }
        }

        @Override
        public String toString() {
            return "WorkflowScriptPreparation[identity=" + identity
                    + ",taskId=" + taskId
                    + ",sourceCheckpoint=REDACTED,request=REDACTED]";
        }
    }

    /** Journal 当前状态；只有终态保存规范沙箱结果。 */
    public record Entry(
            Preparation preparation,
            State state,
            Optional<TypedPayload> result) {

        public Entry {
            Objects.requireNonNull(preparation, "preparation");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(result, "result");
            if (terminal(state) != result.isPresent()) {
                throw invalid("workflow_script_call_entry_invalid");
            }
        }

        @Override
        public String toString() {
            return "WorkflowScriptCall[preparation=" + preparation
                    + ",state=" + state + ",result=REDACTED]";
        }
    }

    /** MyBatis 需要公开可构造类型；完整 Execution 反校验仍复用账本行实现。 */
    public static final class ExecutionSource extends ExecutionLedgerRow {
        public ExecutionSource() {}
    }

    /** Run 行锁只暴露脚本来源校验所需的物理 owner、状态和取消事实。 */
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

    private LockedSource lockSource(
            WorkflowScriptCallsMapper mapper,
            VerifiedIdentity identity,
            ExecutionLease lease,
            long expectedRevision,
            WorkflowNodeCallIdentity key,
            SourceMode mode) {
        String tenantId = key.executionRef().tenantId().value();
        RunSource run = mapper.lockRun(tenantId, key.owner().runRef().runId());
        if (run == null || !run.matches(key.owner())) {
            throw rejected("workflow_script_call_run_missing_or_mismatched");
        }
        if (mode.requiresActiveRun() && !run.active()) {
            throw rejected("workflow_script_call_run_inactive");
        }

        ExecutionSource source = mapper.lockExecution(
                tenantId, key.executionRef().executionId());
        if (source == null) {
            throw rejected("workflow_script_call_execution_missing");
        }
        ExecutionRecord current = source.toRecord();
        Instant now = mapper.storageNow().toInstant();
        if (!current.executionRef().equals(key.executionRef())
                || !current.owner().equals(key.owner())
                || !current.owner().owner().equals(identity.owner())
                || !lease.executionRef().equals(current.executionRef())) {
            throw rejected("workflow_script_call_owner_mismatch");
        }
        if (!mode.accepts(current)) {
            throw rejected("workflow_script_call_execution_inactive");
        }
        if (current.executionRevision() != expectedRevision) {
            throw rejected("workflow_script_call_revision_conflict");
        }
        if (source.getLeaseExecutionRevision() == null
                || source.getLeaseExecutionRevision()
                        != lease.executionRevision()
                || current.lease().isEmpty()
                || !current.lease().orElseThrow().equals(lease.fence())
                || !now.isBefore(lease.fence().leaseUntil())) {
            throw rejected("workflow_script_call_lease_lost");
        }
        return new LockedSource(current, now);
    }

    private static void requireMutationIdentity(
            VerifiedIdentity identity,
            ExecutionLease lease,
            long expectedRevision,
            WorkflowNodeCallIdentity key) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(key, "key");
        if (expectedRevision <= 0) {
            throw invalid("workflow_script_call_revision_invalid");
        }
        if (!identity.owner().equals(key.owner().owner())
                || !lease.executionRef().equals(key.executionRef())) {
            throw invalid("workflow_script_call_owner_mismatch");
        }
    }

    private static void requireBeforeDeadline(
            Instant now, Preparation preparation) {
        if (!now.isBefore(preparation.deadlineAt())) {
            throw rejected("workflow_script_call_deadline_expired");
        }
    }

    private static WorkflowScriptCallRow row(
            Preparation value, long preparedExecutionRevision) {
        WorkflowNodeCallIdentity key = value.identity();
        RunIdentity owner = key.owner();
        String preparationData = encodePreparation(value);
        WorkflowScriptCallRow row = new WorkflowScriptCallRow();
        row.tenantId = key.executionRef().tenantId().value();
        row.executionId = key.executionRef().executionId();
        row.sessionId = owner.owner().sessionRef().sessionId();
        row.targetKind = owner.owner().target().kind().name();
        row.targetId = owner.owner().target().id();
        row.agentId = owner.agentId().value();
        row.endUserId = ExecutionOwnerColumns.user(owner.owner());
        row.runId = owner.runRef().runId();
        row.nodeId = key.nodeId();
        row.visit = key.visit();
        row.callIndex = key.call();
        row.definitionRevision = value.definitionRevision();
        row.installationRevision = value.installationRevision();
        row.preparedExecutionRevision = preparedExecutionRevision;
        row.taskId = value.taskId();
        row.requestSchemaId = value.request().schemaId();
        row.deadlineEpochSecond = value.deadlineAt().getEpochSecond();
        row.deadlineNano = value.deadlineAt().getNano();
        row.sourceCheckpointFingerprint = fingerprint(
                ExecutionLedgerCodec.encode(value.sourceCheckpoint()));
        row.requestFingerprint = fingerprint(
                ExecutionLedgerCodec.encode(value.request()));
        row.preparationFingerprint = fingerprint(preparationData);
        row.preparationData = preparationData;
        row.state = State.PREPARED.name();
        return row;
    }

    private static Entry restore(WorkflowScriptCallRow row) {
        if (row == null) {
            throw rejected("workflow_script_call_missing");
        }
        Preparation preparation = decodePreparation(row.preparationData);
        WorkflowNodeCallIdentity key = preparation.identity();
        if (!row.tenantId.equals(key.executionRef().tenantId().value())
                || !row.executionId.equals(key.executionRef().executionId())
                || !row.sessionId.equals(key.owner().owner()
                        .sessionRef().sessionId())
                || !row.targetKind.equals(key.owner().owner()
                        .target().kind().name())
                || !row.targetId.equals(key.owner().owner().target().id())
                || !row.agentId.equals(key.owner().agentId().value())
                || !Objects.equals(row.endUserId, ExecutionOwnerColumns.user(key.owner().owner()))
                || !row.runId.equals(key.owner().runRef().runId())
                || !row.nodeId.equals(key.nodeId())
                || row.visit != key.visit()
                || row.callIndex != key.call()
                || row.definitionRevision != preparation.definitionRevision()
                || row.installationRevision
                        != preparation.installationRevision()
                || row.preparedExecutionRevision <= 0
                || !row.taskId.equals(preparation.taskId())
                || !row.requestSchemaId.equals(preparation.request().schemaId())
                || row.deadlineEpochSecond
                        != preparation.deadlineAt().getEpochSecond()
                || row.deadlineNano != preparation.deadlineAt().getNano()
                || !row.sourceCheckpointFingerprint.equals(fingerprint(
                        ExecutionLedgerCodec.encode(
                                preparation.sourceCheckpoint())))
                || !row.requestFingerprint.equals(fingerprint(
                        ExecutionLedgerCodec.encode(preparation.request())))
                || !row.preparationFingerprint.equals(
                        fingerprint(row.preparationData))
                || !row.preparationData.equals(
                        encodePreparation(preparation))) {
            throw rejected("workflow_script_call_corrupted");
        }

        State state;
        try {
            state = State.valueOf(row.state);
        } catch (IllegalArgumentException failure) {
            throw corrupted(failure);
        }
        Optional<TypedPayload> result;
        if (terminal(state)) {
            if (row.terminalData == null || row.terminalFingerprint == null) {
                throw rejected("workflow_script_call_corrupted");
            }
            TypedPayload decoded = decodeTerminal(row.terminalData, state);
            if (!row.terminalFingerprint.equals(fingerprint(row.terminalData))
                    || !row.terminalData.equals(
                            encodeTerminal(state, decoded))) {
                throw rejected("workflow_script_call_corrupted");
            }
            result = Optional.of(decoded);
        } else {
            if (row.terminalData != null || row.terminalFingerprint != null) {
                throw rejected("workflow_script_call_corrupted");
            }
            result = Optional.empty();
        }
        return new Entry(preparation, state, result);
    }

    private static String encodePreparation(Preparation value) {
        return ExecutionLedgerCodec.encode(List.of(
                PREPARATION_SCHEMA,
                value.identity(),
                value.definitionRevision(),
                value.installationRevision(),
                value.sourceCheckpoint(),
                value.taskId(),
                value.request(),
                value.deadlineAt()));
    }

    private static Preparation decodePreparation(String encoded) {
        List<?> values = ExecutionLedgerCodec.decode(encoded, List.class);
        if (values.size() != 8
                || !PREPARATION_SCHEMA.equals(values.get(0))) {
            throw rejected("workflow_script_call_preparation_record_invalid");
        }
        try {
            Preparation value = new Preparation(
                    (WorkflowNodeCallIdentity) values.get(1),
                    (Long) values.get(2),
                    (Long) values.get(3),
                    (TypedPayload) values.get(4),
                    (String) values.get(5),
                    (TypedPayload) values.get(6),
                    (Instant) values.get(7));
            if (!encoded.equals(encodePreparation(value))) {
                throw rejected(
                        "workflow_script_call_preparation_record_noncanonical");
            }
            return value;
        } catch (ClassCastException failure) {
            throw corrupted(failure);
        }
    }

    private static String encodeTerminal(State state, TypedPayload result) {
        return ExecutionLedgerCodec.encode(List.of(
                TERMINAL_SCHEMA, state.name(), result));
    }

    private static TypedPayload decodeTerminal(String encoded, State state) {
        List<?> values = ExecutionLedgerCodec.decode(encoded, List.class);
        if (values.size() != 3
                || !TERMINAL_SCHEMA.equals(values.get(0))
                || !state.name().equals(values.get(1))) {
            throw rejected("workflow_script_call_terminal_record_invalid");
        }
        try {
            return (TypedPayload) values.get(2);
        } catch (ClassCastException failure) {
            throw corrupted(failure);
        }
    }

    private static String fingerprint(String encoded) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    "workflow_script_call_fingerprint_unavailable", failure);
        }
    }

    private static boolean terminal(State state) {
        return state != State.PREPARED && state != State.DISPATCHED;
    }

    private static boolean terminalExecution(WorkflowExecutionState state) {
        return state == WorkflowExecutionState.COMPLETED
                || state == WorkflowExecutionState.FAILED
                || state == WorkflowExecutionState.LOOP_TERMINATED
                || state == WorkflowExecutionState.CANCELLED;
    }

    /** 同一 journal 事务按 Execution 阶段区分执行、等待查询和持久取消清理。 */
    private enum SourceMode {
        RUNNING(WorkflowExecutionState.RUNNING, true),
        WAITING_EXTERNAL(WorkflowExecutionState.WAITING_EXTERNAL, true),
        CANCELLING(null, false);

        private final WorkflowExecutionState executionState;
        private final boolean activeRun;

        SourceMode(WorkflowExecutionState executionState, boolean activeRun) {
            this.executionState = executionState;
            this.activeRun = activeRun;
        }

        boolean requiresActiveRun() {
            return activeRun;
        }

        boolean accepts(ExecutionRecord execution) {
            if (this == CANCELLING) {
                return !terminalExecution(execution.state())
                        && execution.cancelRequestId().isPresent()
                        && execution.cancelRequestedAt().isPresent();
            }
            return (execution.state() == executionState || this==RUNNING&&execution.state()==WorkflowExecutionState.WAITING_EXTERNAL)
                    && execution.cancelRequestId().isEmpty()
                    && execution.cancelRequestedAt().isEmpty();
        }
    }

    private static WorkflowScriptCallsMapper mapper(
            SqlSession session, String tenantId) {
        WorkflowScriptCallsMapper mapper = session.getMapper(
                WorkflowScriptCallsMapper.class);
        mapper.bindTenant(tenantId);
        return mapper;
    }

    private static WorkflowScriptCallRow find(
            WorkflowScriptCallsMapper mapper, WorkflowNodeCallIdentity key) {
        return mapper.find(key.executionRef().tenantId().value(),
                key.executionRef().executionId(), key.nodeId(), key.visit(),
                key.call());
    }

    private static WorkflowScriptCallRow findRequired(
            WorkflowScriptCallsMapper mapper, WorkflowNodeCallIdentity key) {
        WorkflowScriptCallRow row = find(mapper, key);
        if (row == null) {
            throw rejected("workflow_script_call_missing");
        }
        return row;
    }

    private static SqlSessionFactory buildSessions(DataSource dataSource,
            java.util.function.Consumer<Configuration> mapperConfigurer) {
        Configuration configuration = new Configuration(new Environment(
                "workflow-script-calls",
                new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        mapperConfigurer.accept(configuration);
        try (InputStream xml = JdbcWorkflowScriptCalls.class.getClassLoader()
                .getResourceAsStream(MAPPER_RESOURCE)) {
            if (xml == null) {
                throw new IllegalStateException(
                        "workflow_script_call_mapper_missing");
            }
            new XMLMapperBuilder(xml, configuration, MAPPER_RESOURCE,
                    configuration.getSqlFragments()).parse();
            return new SqlSessionFactoryBuilder().build(configuration);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "workflow_script_call_initialization_failure", failure);
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }

    private static IllegalStateException rejected(String reason) {
        return new IllegalStateException(reason);
    }

    private static IllegalStateException corrupted(Throwable cause) {
        return new IllegalStateException("workflow_script_call_corrupted", cause);
    }

    private record LockedSource(ExecutionRecord execution, Instant now) {}
}

/** SQL 仅由 AgentRuntimeWorkflowScriptCallsMapper.xml 维护。 */
interface WorkflowScriptCallsMapper {
    String bindTenant(@Param("tenantId") String tenantId);

    ExecutionStorageTimeRow storageNow();

    JdbcWorkflowScriptCalls.RunSource lockRun(
            @Param("tenantId") String tenantId,
            @Param("runId") String runId);

    JdbcWorkflowScriptCalls.ExecutionSource lockExecution(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId);

    WorkflowScriptCallRow find(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId,
            @Param("nodeId") String nodeId,
            @Param("visit") int visit,
            @Param("callIndex") int callIndex);

    List<WorkflowScriptCallRow> active(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId,
            @Param("sessionId") String sessionId,
            @Param("targetKind") String targetKind,
            @Param("targetId") String targetId,
            @Param("agentId") String agentId,
            @Param("endUserId") Long endUserId,
            @Param("runId") String runId);

    int insert(WorkflowScriptCallRow row);

    int dispatch(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId,
            @Param("nodeId") String nodeId,
            @Param("visit") int visit,
            @Param("callIndex") int callIndex);

    int record(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId,
            @Param("nodeId") String nodeId,
            @Param("visit") int visit,
            @Param("callIndex") int callIndex,
            @Param("state") String state,
            @Param("terminalFingerprint") String terminalFingerprint,
            @Param("terminalData") String terminalData);
}

/** 脚本调用准备、单向状态和终态结果的持久化行。 */
final class WorkflowScriptCallRow {
    public String tenantId;
    public String executionId;
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
    public String taskId;
    public String requestSchemaId;
    public long deadlineEpochSecond;
    public int deadlineNano;
    public String sourceCheckpointFingerprint;
    public String requestFingerprint;
    public String preparationFingerprint;
    public String preparationData;
    public String state;
    public String terminalFingerprint;
    public String terminalData;
}
