package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.ExecutionMode;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 保存 Workflow Execution 的权威状态、checkpoint、租约、取消事实与待投影状态事件。
 *
 * <p>该 Port 只管理 {@link ExecutionRef} 标识的 Workflow Execution，不兼任 façade Run
 * 存储，也不负责 Action、Delegation 或异步回调账本。
 */
public interface ExecutionLedgerPort {

    /** 原子创建 QUEUED Execution 及其领域状态事件。 */
    ExecutionWriteResult create(CreateExecution command);

    /** 按已验证身份与完整 ExecutionRef 查询权威记录。 */
    Optional<ExecutionRecord> find(ExecutionLookup query);

    /** 为指定 Execution 申请带 fencing token 的执行租约。 */
    ExecutionLeaseResult acquireLease(ExecutionLeaseRequest request);

    /** 使用当前 Execution lease 续期；过期判断只使用存储端权威时钟。 */
    LeaseMutationStatus renew(ExecutionLease lease, Duration extension);

    /** 释放当前 Execution lease，且不改变领域状态。 */
    LeaseMutationStatus release(ExecutionLease lease);

    /** 以控制面 expected-revision CAS 保存取消意图，但不抢占 worker lease。 */
    ExecutionCancelResult requestCancel(RequestExecutionCancel command);

    /** 在 RUNNING 原状态下原子推进 checkpoint 与 execution.step 事件。 */
    ExecutionWriteResult progress(ProgressExecution command);

    /** 使用当前 lease 与 expected state/revision 原子推进合法状态边。 */
    ExecutionWriteResult transition(TransitionExecution command);

    /** 原子完成 WAITING_HITL → RESUMING → RUNNING 与两条状态事件。 */
    ExecutionWriteResult resume(ResumeExecution command);

    /** 按租户发现可恢复 Execution；候选本身不授予写权。 */
    List<ExecutionRecoveryCandidate> scanRecoverable(ExecutionRecoveryScan query);

    /** 领取本领域尚未投影的 Execution 状态事件。 */
    List<StateEventClaim> claimStateEvents(PublicationClaimRequest request);

    /** 确认 Execution 状态事件已经形成持久 Session 投影。 */
    PublicationMutationResult acknowledgeStateEvent(PublicationAcknowledge command);

    /** 使用当前 publication lease 重新安排 Execution 状态事件。 */
    PublicationMutationResult retryStateEvent(PublicationRetry command);

    /** 保存 Execution 固定身份、状态、checkpoint、结果、租约与取消事实。 */
    record ExecutionRecord(
            ExecutionRef executionRef,
            RunIdentity owner,
            String executionUnitId,
            String definitionVersion,
            ExecutionMode mode,
            Optional<ActionRef> causativeActionRef,
            Optional<DelegationRef> delegationRef,
            WorkflowExecutionState state,
            long executionRevision,
            TypedPayload checkpoint,
            Optional<ExecutionWait> waiting,
            Optional<ResultRef> resultRef,
            Optional<String> reasonCode,
            Optional<LeaseFence> lease,
            Optional<InputId> cancelRequestId,
            Optional<Instant> cancelRequestedAt,
            Instant createdAt,
            Instant updatedAt) {

        /**
         * 拒绝不完整、跨租户、无效 revision、错误状态字段矩阵、取消字段半缺失或倒置时间。
         */
        public ExecutionRecord {
            executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
            owner = PortContracts.requireNonNull(owner, "owner");
            executionUnitId = PortContracts.requireText(executionUnitId, "executionUnitId");
            definitionVersion = PortContracts.requireText(definitionVersion, "definitionVersion");
            mode = PortContracts.requireNonNull(mode, "mode");
            causativeActionRef = PortContracts.requireOptional(
                    causativeActionRef, "causativeActionRef");
            delegationRef = PortContracts.requireOptional(delegationRef, "delegationRef");
            state = PortContracts.requireNonNull(state, "state");
            executionRevision = PortContracts.requirePositive(
                    executionRevision, "executionRevision");
            checkpoint = PortContracts.requireNonNull(checkpoint, "checkpoint");
            waiting = PortContracts.requireOptional(waiting, "waiting");
            resultRef = PortContracts.requireOptional(resultRef, "resultRef");
            reasonCode = PortContracts.requireOptional(reasonCode, "reasonCode")
                    .map(value -> PortContracts.requireText(value, "reasonCode"));
            lease = PortContracts.requireOptional(lease, "lease");
            cancelRequestId = PortContracts.requireOptional(cancelRequestId, "cancelRequestId");
            cancelRequestedAt = PortContracts.requireOptional(
                    cancelRequestedAt, "cancelRequestedAt");
            createdAt = PortContracts.requireNonNull(createdAt, "createdAt");
            updatedAt = PortContracts.requireNonNull(updatedAt, "updatedAt");

            requireExecutionOwner(executionRef, owner);
            TenantId tenantId = executionRef.tenantId();
            causativeActionRef.ifPresent(ref -> PortContracts.requireSameTenant(
                    tenantId, ref.tenantId(), "causativeActionRef.tenantId"));
            delegationRef.ifPresent(ref -> PortContracts.requireSameTenant(
                    tenantId, ref.tenantId(), "delegationRef.tenantId"));
            lease.ifPresent(fence -> PortContracts.requireSameTenant(
                    tenantId, fence.tenantId(), "lease.tenantId"));

            requireStateFields(state, waiting, resultRef, reasonCode);
            if (isTerminal(state) && lease.isPresent()) {
                throw new IllegalArgumentException("terminal execution must not carry lease");
            }
            if (cancelRequestId.isPresent() != cancelRequestedAt.isPresent()) {
                throw new IllegalArgumentException(
                        "cancelRequestId and cancelRequestedAt must be present together");
            }
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt must not be before createdAt");
            }
        }
    }

    /** 描述创建 Execution 所需的固定身份、初始 checkpoint、幂等指纹与 QUEUED 事件。 */
    record CreateExecution(
            ExecutionRef executionRef,
            RunIdentity owner,
            String executionUnitId,
            String definitionVersion,
            ExecutionMode mode,
            Optional<ActionRef> causativeActionRef,
            Optional<DelegationRef> delegationRef,
            TypedPayload initialCheckpoint,
            String requestFingerprint,
            LedgerEvent queuedEvent) {

        /** 拒绝不完整身份、跨租户引用、空白定义字段或非 execution.queued 初始事件。 */
        public CreateExecution {
            executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
            owner = PortContracts.requireNonNull(owner, "owner");
            executionUnitId = PortContracts.requireText(executionUnitId, "executionUnitId");
            definitionVersion = PortContracts.requireText(definitionVersion, "definitionVersion");
            mode = PortContracts.requireNonNull(mode, "mode");
            causativeActionRef = PortContracts.requireOptional(
                    causativeActionRef, "causativeActionRef");
            delegationRef = PortContracts.requireOptional(delegationRef, "delegationRef");
            initialCheckpoint = PortContracts.requireNonNull(
                    initialCheckpoint, "initialCheckpoint");
            requestFingerprint = PortContracts.requireText(
                    requestFingerprint, "requestFingerprint");
            queuedEvent = requireSessionEventType(
                    queuedEvent, "execution.queued", "queuedEvent");

            requireExecutionOwner(executionRef, owner);
            TenantId tenantId = executionRef.tenantId();
            causativeActionRef.ifPresent(ref -> PortContracts.requireSameTenant(
                    tenantId, ref.tenantId(), "causativeActionRef.tenantId"));
            delegationRef.ifPresent(ref -> PortContracts.requireSameTenant(
                    tenantId, ref.tenantId(), "delegationRef.tenantId"));
            if (!queuedEvent.sessionEvent().trace().equals(owner.trace())) {
                throw new IllegalArgumentException("queuedEvent trace must match execution owner");
            }
        }
    }

    /** 标识 WAITING_HITL 的稳定 suspend 与 action 路由键。 */
    record ExecutionWait(String suspendRef, String actionKey) {

        /** 拒绝空白 suspend 或 action 路由键。 */
        public ExecutionWait {
            suspendRef = PortContracts.requireText(suspendRef, "suspendRef");
            actionKey = PortContracts.requireText(actionKey, "actionKey");
        }
    }

    /** 使用本次已验证身份定位同 tenant 的 Workflow Execution。 */
    record ExecutionLookup(VerifiedIdentity identity, ExecutionRef executionRef) {

        /** 拒绝缺失身份、ExecutionRef 或跨租户读取。 */
        public ExecutionLookup {
            identity = PortContracts.requireNonNull(identity, "identity");
            executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
            PortContracts.requireSameTenant(
                    identity.owner().sessionRef().tenantId(),
                    executionRef.tenantId(),
                    "executionRef.tenantId");
        }
    }

    /** 描述按已验证身份、ExecutionRef、worker 与租期申请租约的审计事实。 */
    record ExecutionLeaseRequest(
            VerifiedIdentity identity,
            ExecutionRef executionRef,
            String workerId,
            Duration leaseDuration,
            Instant requestedAt) {

        /** 拒绝缺失身份、跨租户目标、空白 worker、非正租期或缺失审计时间。 */
        public ExecutionLeaseRequest {
            identity = PortContracts.requireNonNull(identity, "identity");
            executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
            workerId = PortContracts.requireText(workerId, "workerId");
            leaseDuration = PortContracts.requirePositive(leaseDuration, "leaseDuration");
            requestedAt = PortContracts.requireNonNull(requestedAt, "requestedAt");
            PortContracts.requireSameTenant(
                    identity.owner().sessionRef().tenantId(),
                    executionRef.tenantId(),
                    "executionRef.tenantId");
        }
    }

    /** 将 ExecutionRef、当前 fence 与领取时 execution revision 绑定为写凭据。 */
    record ExecutionLease(
            ExecutionRef executionRef,
            LeaseFence fence,
            long executionRevision) {

        /** 拒绝缺失引用/fence、非正 revision 或跨租户 lease。 */
        public ExecutionLease {
            executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
            fence = PortContracts.requireNonNull(fence, "fence");
            executionRevision = PortContracts.requirePositive(
                    executionRevision, "executionRevision");
            PortContracts.requireSameTenant(
                    executionRef.tenantId(), fence.tenantId(), "fence.tenantId");
        }
    }

    /** 表达 Execution 租约已取得、正在被占用或被确定拒绝。 */
    sealed interface ExecutionLeaseResult
            permits ExecutionLeaseResult.Acquired,
                    ExecutionLeaseResult.Busy,
                    ExecutionLeaseResult.Rejected {

        /** 返回已带当前 fence 的 ExecutionRecord 与精确写租约。 */
        record Acquired(ExecutionRecord execution, ExecutionLease lease)
                implements ExecutionLeaseResult {

            /** 拒绝缺失或 execution/ref/revision/fence 不闭合的领取结果。 */
            public Acquired {
                execution = PortContracts.requireNonNull(execution, "execution");
                lease = PortContracts.requireNonNull(lease, "lease");
                if (!execution.executionRef().equals(lease.executionRef())
                        || execution.executionRevision() != lease.executionRevision()
                        || execution.lease().isEmpty()
                        || !execution.lease().get().equals(lease.fence())) {
                    throw new IllegalArgumentException(
                            "acquired execution and lease must match");
                }
            }
        }

        /** 返回当前仍被其他有效租约占用的 ExecutionRecord。 */
        record Busy(ExecutionRecord execution) implements ExecutionLeaseResult {

            /** 拒绝缺失 ExecutionRecord。 */
            public Busy {
                execution = PortContracts.requireNonNull(execution, "execution");
            }
        }

        /** 返回无法申请租约的稳定失败。 */
        record Rejected(PortFailure failure) implements ExecutionLeaseResult {

            /** 拒绝缺失稳定失败。 */
            public Rejected {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }
    }

    /** 描述 RUNNING 原状态下受 lease/revision 保护的 checkpoint 进度写入。 */
    record ProgressExecution(
            ExecutionLease lease,
            long expectedRevision,
            TypedPayload checkpoint,
            LedgerEvent stepEvent) {

        /** 拒绝缺失 lease/checkpoint、非正 revision 或非 execution.step 事件。 */
        public ProgressExecution {
            lease = PortContracts.requireNonNull(lease, "lease");
            expectedRevision = PortContracts.requirePositive(
                    expectedRevision, "expectedRevision");
            if (expectedRevision == Long.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "expectedRevision must allow one increment");
            }
            checkpoint = PortContracts.requireNonNull(checkpoint, "checkpoint");
            stepEvent = requireSessionEventType(
                    stepEvent, "execution.step", "stepEvent");
        }
    }

    /** 描述一次携带状态事件的受 lease/state/revision 保护的 Execution 迁移。 */
    record TransitionExecution(
            ExecutionLease lease,
            WorkflowExecutionState expectedState,
            long expectedRevision,
            WorkflowExecutionState targetState,
            TypedPayload checkpoint,
            Optional<ExecutionWait> waiting,
            Optional<ResultRef> resultRef,
            Optional<String> reasonCode,
            LedgerEvent event) {

        /** 拒绝非法状态边、错误 waiting 矩阵、无效 revision 或不匹配的公开状态事件。 */
        public TransitionExecution {
            lease = PortContracts.requireNonNull(lease, "lease");
            expectedState = PortContracts.requireNonNull(expectedState, "expectedState");
            expectedRevision = PortContracts.requirePositive(
                    expectedRevision, "expectedRevision");
            if (expectedRevision == Long.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "expectedRevision must allow one increment");
            }
            targetState = PortContracts.requireNonNull(targetState, "targetState");
            checkpoint = PortContracts.requireNonNull(checkpoint, "checkpoint");
            waiting = PortContracts.requireOptional(waiting, "waiting");
            resultRef = PortContracts.requireOptional(resultRef, "resultRef");
            reasonCode = PortContracts.requireOptional(reasonCode, "reasonCode")
                    .map(value -> PortContracts.requireText(value, "reasonCode"));
            event = PortContracts.requireNonNull(event, "event");

            if (!isAllowedTransition(expectedState, targetState)) {
                throw new IllegalArgumentException(
                        "execution state transition is not allowed");
            }
            requireStateFields(targetState, waiting, resultRef, reasonCode);
            requireSessionEventType(
                    event, eventTypeForTarget(targetState), "event");
        }
    }

    /** 描述一次原子 WAITING_HITL 恢复所需的旧 wait 身份、checkpoint 与两条事件。 */
    record ResumeExecution(
            ExecutionLease lease,
            long expectedRevision,
            String expectedSuspendRef,
            String expectedActionKey,
            TypedPayload checkpoint,
            LedgerEvent resumedEvent,
            LedgerEvent startedEvent,
            WorkflowExecutionState expectedState) {

        /**
         * 拒绝缺失字段、空白旧 wait 身份、无法安全递增两次的 revision 或事件类型错配。
         * 两条事件的幂等身份、schema 和事务原子性由适配器验证。
         */
        public ResumeExecution {
            if (expectedState == null || !expectedState.isWaiting()) {
                throw new IllegalArgumentException("resume requires an exact waiting state");
            }
            lease = PortContracts.requireNonNull(lease, "lease");
            expectedRevision = PortContracts.requirePositive(
                    expectedRevision, "expectedRevision");
            if (expectedRevision > Long.MAX_VALUE - 2) {
                throw new IllegalArgumentException(
                        "expectedRevision must allow two increments");
            }
            expectedSuspendRef = PortContracts.requireText(
                    expectedSuspendRef, "expectedSuspendRef");
            expectedActionKey = PortContracts.requireText(
                    expectedActionKey, "expectedActionKey");
            checkpoint = PortContracts.requireNonNull(checkpoint, "checkpoint");
            resumedEvent = requireSessionEventType(
                    resumedEvent, "execution.resumed", "resumedEvent");
            startedEvent = requireSessionEventType(
                    startedEvent, "execution.started", "startedEvent");
            if (resumedEvent.sessionEvent().eventId()
                    .equals(startedEvent.sessionEvent().eventId())) {
                throw new IllegalArgumentException(
                        "resume events must use distinct eventIds");
            }
        }
    }

    /** 描述无需获取 worker lease 的控制面取消请求。 */
    record RequestExecutionCancel(
            VerifiedIdentity identity,
            ExecutionRef executionRef,
            long expectedRevision,
            InputId cancelRequestId,
            LedgerEvent cancelEvent) {

        /**
         * 拒绝缺失身份/取消事实、不可递增 revision、跨租户目标或非
         * execution.cancel_requested 取消意图事件。
         */
        public RequestExecutionCancel {
            identity = PortContracts.requireNonNull(identity, "identity");
            executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
            expectedRevision = PortContracts.requirePositive(
                    expectedRevision, "expectedRevision");
            if (expectedRevision == Long.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "expectedRevision must allow one increment");
            }
            cancelRequestId = PortContracts.requireNonNull(cancelRequestId, "cancelRequestId");
            cancelEvent = requireSessionEventType(
                    cancelEvent, "execution.cancel_requested", "cancelEvent");
            PortContracts.requireSameTenant(
                    identity.owner().sessionRef().tenantId(),
                    executionRef.tenantId(),
                    "executionRef.tenantId");
        }
    }

    /** 表达取消意图已应用、重放、冲突、不存在或被确定拒绝。 */
    sealed interface ExecutionCancelResult
            permits ExecutionCancelResult.Applied,
                    ExecutionCancelResult.Replayed,
                    ExecutionCancelResult.Conflict,
                    ExecutionCancelResult.NotFound,
                    ExecutionCancelResult.Rejected {

        /** 返回首次保存取消意图后的 ExecutionRecord。 */
        record Applied(ExecutionRecord execution) implements ExecutionCancelResult {

            /** 拒绝缺失 ExecutionRecord。 */
            public Applied {
                execution = PortContracts.requireNonNull(execution, "execution");
            }
        }

        /** 返回相同取消意图已经保存后的 ExecutionRecord。 */
        record Replayed(ExecutionRecord execution) implements ExecutionCancelResult {

            /** 拒绝缺失 ExecutionRecord。 */
            public Replayed {
                execution = PortContracts.requireNonNull(execution, "execution");
            }
        }

        /** 返回 expected revision 竞争时的当前 ExecutionRecord。 */
        record Conflict(ExecutionRecord current) implements ExecutionCancelResult {

            /** 拒绝缺失当前记录。 */
            public Conflict {
                current = PortContracts.requireNonNull(current, "current");
            }
        }

        /** 表示目标 Execution 不存在。 */
        record NotFound(PortFailure failure) implements ExecutionCancelResult {

            /** 拒绝缺失稳定失败。 */
            public NotFound {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }

        /** 表示取消意图被策略或领域规则确定拒绝。 */
        record Rejected(PortFailure failure) implements ExecutionCancelResult {

            /** 拒绝缺失稳定失败。 */
            public Rejected {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }
    }

    /** 表达受 lease/revision 保护的写入已应用、重放、冲突、失租或拒绝。 */
    sealed interface ExecutionWriteResult
            permits ExecutionWriteResult.Applied,
                    ExecutionWriteResult.Replayed,
                    ExecutionWriteResult.Conflict,
                    ExecutionWriteResult.LeaseLost,
                    ExecutionWriteResult.Rejected {

        /** 返回首次提交 mutation 后的 ExecutionRecord。 */
        record Applied(ExecutionRecord execution) implements ExecutionWriteResult {

            /** 拒绝缺失 ExecutionRecord。 */
            public Applied {
                execution = PortContracts.requireNonNull(execution, "execution");
            }
        }

        /** 返回相同 mutation 已经提交后的 ExecutionRecord。 */
        record Replayed(ExecutionRecord execution) implements ExecutionWriteResult {

            /** 拒绝缺失 ExecutionRecord。 */
            public Replayed {
                execution = PortContracts.requireNonNull(execution, "execution");
            }
        }

        /** 返回 expected state/revision 竞争时的当前 ExecutionRecord。 */
        record Conflict(ExecutionRecord current) implements ExecutionWriteResult {

            /** 拒绝缺失当前记录。 */
            public Conflict {
                current = PortContracts.requireNonNull(current, "current");
            }
        }

        /** 返回 fence 已过期或被取代的稳定失败。 */
        record LeaseLost(PortFailure failure) implements ExecutionWriteResult {

            /** 拒绝缺失稳定失败。 */
            public LeaseLost {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }

        /** 返回 mutation 被策略或领域规则确定拒绝的稳定失败。 */
        record Rejected(PortFailure failure) implements ExecutionWriteResult {

            /** 拒绝缺失稳定失败。 */
            public Rejected {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }
    }

    /** 按 executionId 排序分页发现活动 Execution；游标避免长期等待项阻塞后续恢复。 */
    record ExecutionRecoveryScan(TenantId tenantId, int limit, Optional<String> afterExecutionId) {

        /** 拒绝缺失租户或非正扫描上限。 */
        public ExecutionRecoveryScan {
            tenantId = PortContracts.requireNonNull(tenantId, "tenantId");
            afterExecutionId = PortContracts.requireOptional(afterExecutionId, "afterExecutionId");
            afterExecutionId.ifPresent(value -> PortContracts.requireText(value, "afterExecutionId"));
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
        }
    }

    /** 返回足以再次精确 claim 的 Execution 身份、状态、revision 与恢复事实。 */
    record ExecutionRecoveryCandidate(
            ExecutionRef executionRef,
            RunIdentity owner,
            WorkflowExecutionState state,
            long executionRevision,
            Optional<Instant> leaseUntil,
            Optional<InputId> cancelRequestId) {

        /** 拒绝缺失/跨租户身份、非正 revision、缺失 Optional 或终态候选。 */
        public ExecutionRecoveryCandidate {
            executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
            owner = PortContracts.requireNonNull(owner, "owner");
            state = PortContracts.requireNonNull(state, "state");
            executionRevision = PortContracts.requirePositive(
                    executionRevision, "executionRevision");
            leaseUntil = PortContracts.requireOptional(leaseUntil, "leaseUntil");
            cancelRequestId = PortContracts.requireOptional(cancelRequestId, "cancelRequestId");
            requireExecutionOwner(executionRef, owner);
            if (isTerminal(state)) {
                throw new IllegalArgumentException(
                        "terminal execution is not recoverable");
            }
        }
    }

    /** 验证 ExecutionRef 与 Workflow owner 属于同一 tenant。 */
    private static void requireExecutionOwner(
            ExecutionRef executionRef, RunIdentity owner) {
        PortContracts.requireSameTenant(
                owner.owner().sessionRef().tenantId(),
                executionRef.tenantId(),
                "executionRef.tenantId");
    }

    /** 判断 expected→target 是否属于事实源冻结且不由 progress/resume 专属的八条状态边。 */
    private static boolean isAllowedTransition(
            WorkflowExecutionState expected, WorkflowExecutionState target) {
        return switch (expected) {
            case QUEUED -> target == WorkflowExecutionState.RUNNING
                    || target == WorkflowExecutionState.CANCELLED;
            case RUNNING -> target == WorkflowExecutionState.WAITING_HITL
                    || target == WorkflowExecutionState.WAITING_EXTERNAL
                    || target == WorkflowExecutionState.COMPLETED
                    || target == WorkflowExecutionState.FAILED
                    || target == WorkflowExecutionState.LOOP_TERMINATED
                    || target == WorkflowExecutionState.CANCELLED;
            case WAITING_HITL, WAITING_EXTERNAL -> target == WorkflowExecutionState.CANCELLED;
            case RESUMING, COMPLETED, FAILED, LOOP_TERMINATED, CANCELLED -> false;
        };
    }

    /** 把合法 transition 的目标状态绑定到唯一公开 Session eventType。 */
    private static String eventTypeForTarget(WorkflowExecutionState target) {
        return switch (target) {
            case RUNNING -> "execution.started";
            case WAITING_HITL -> "execution.hitl";
            case WAITING_EXTERNAL -> "execution.waiting";
            case COMPLETED -> "execution.completed";
            case FAILED -> "execution.failed";
            case LOOP_TERMINATED -> "execution.loop_terminated";
            case CANCELLED -> "execution.cancelled";
            case QUEUED, RESUMING -> throw new IllegalArgumentException(
                    "target state has no standalone transition event");
        };
    }

    /** 冻结可持久 Execution 状态的 waiting/result/reason 闭包。 */
    private static void requireStateFields(
            WorkflowExecutionState state,
            Optional<ExecutionWait> waiting,
            Optional<ResultRef> resultRef,
            Optional<String> reasonCode) {
        boolean needsWaiting = state.isWaiting();
        boolean needsResult = switch (state) {
            case WAITING_HITL, WAITING_EXTERNAL, COMPLETED, FAILED, LOOP_TERMINATED, CANCELLED -> true;
            case QUEUED, RUNNING, RESUMING -> false;
        };
        boolean needsReason = state == WorkflowExecutionState.FAILED
                || state == WorkflowExecutionState.LOOP_TERMINATED;
        if (waiting.isPresent() != needsWaiting) {
            throw new IllegalArgumentException(
                    "waiting must be present exactly for waiting states");
        }
        if (resultRef.isPresent() != needsResult) {
            throw new IllegalArgumentException(
                    "resultRef presence must match execution state");
        }
        if (reasonCode.isPresent() != needsReason) {
            throw new IllegalArgumentException(
                    "reasonCode presence must match execution state");
        }
    }

    /** 只冻结已明确命名的公开 Session eventType；私有账本 schema 由适配器判定。 */
    private static LedgerEvent requireSessionEventType(
            LedgerEvent event, String expectedType, String field) {
        event = PortContracts.requireNonNull(event, field);
        if (!event.sessionEvent().eventType().equals(expectedType)) {
            throw new IllegalArgumentException(
                    field + " session eventType must be " + expectedType);
        }
        return event;
    }

    /** 判断状态是否已经进入不可恢复的四类终态。 */
    private static boolean isTerminal(WorkflowExecutionState state) {
        return state == WorkflowExecutionState.COMPLETED
                || state == WorkflowExecutionState.FAILED
                || state == WorkflowExecutionState.LOOP_TERMINATED
                || state == WorkflowExecutionState.CANCELLED;
    }
}
