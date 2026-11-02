package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EvidenceRef;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.LoopTerminationReason;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** 隔离 Core 与 Workflow Engine，并以冻结挂载身份驱动创建、恢复、查询和取消。 */
public interface WorkflowPortal {

    /** 创建或幂等重放一个 Workflow Execution。 */
    WorkflowResult dispatch(WorkflowDispatch command);

    /** 使用挂起引用、动作键与 revision CAS 恢复 Workflow。 */
    WorkflowResult resume(WorkflowResume command);

    /** 只读取已提交的 Workflow 终态，不重新执行任何节点。 */
    WorkflowRecoveredResult recoverResult(WorkflowResultQuery query);

    /** 按授权身份读取 Workflow 当前快照。 */
    Optional<WorkflowSnapshot> query(WorkflowQuery query);

    /** 使用动作键与 revision CAS 取消 Workflow。 */
    WorkflowControlResult cancel(WorkflowCancel command);

    /** 表示 `workflow:<mountId>` 格式的模型可见挂载句柄。 */
    record WorkflowHandle(String value) {

        /** 拒绝非 Workflow 前缀或缺失 mountId 的句柄。 */
        public WorkflowHandle {
            value = requireHandle(value, "workflow:");
        }
    }

    /** 固定 Workflow 挂载、实例版本、能力版本与单次超时。 */
    record FrozenWorkflowTarget(
            WorkflowHandle handle,
            String instanceId,
            long instanceRevision,
            CapabilityRef capability,
            Duration timeout,
            long installationRevision) {

        /** 拒绝错类型能力、不完整实例身份与非正超时。 */
        public FrozenWorkflowTarget {
            handle = PortContracts.requireNonNull(handle, "handle");
            instanceId = PortContracts.requireText(instanceId, "instanceId");
            instanceRevision = PortContracts.requirePositive(instanceRevision, "instanceRevision");
            installationRevision = PortContracts.requirePositive(installationRevision, "installationRevision");
            capability = requireCapability(capability);
            timeout = PortContracts.requirePositive(timeout, "timeout");
        }
    }

    /** 描述一次绑定当前能力快照的新 Workflow 调度。 */
    record WorkflowDispatch(
            ProviderCallContext context,
            CapabilitySnapshotRef snapshot,
            FrozenWorkflowTarget target,
            ExecutionRef executionRef,
            TypedPayload parameters,
            List<EvidenceRef> evidenceRefs,
            IdempotencyKey idempotencyKey) {

        /** 拒绝跨租户 Execution、跨 Session/重复 Evidence 与快照漂移。 */
        public WorkflowDispatch {
            context = PortContracts.requireNonNull(context, "context");
            snapshot = PortContracts.requireNonNull(snapshot, "snapshot");
            target = PortContracts.requireNonNull(target, "target");
            executionRef = requireCurrentTenant(context, executionRef, "executionRef");
            parameters = PortContracts.requireNonNull(parameters, "parameters");
            evidenceRefs = PortContracts.immutableDistinctList(evidenceRefs, "evidenceRefs");
            idempotencyKey = PortContracts.requireNonNull(idempotencyKey, "idempotencyKey");
            requireCurrentSnapshot(context, snapshot);
            // EvidenceRef 只能解析当前 Session 已提交的 Observation，不能跨会话注入。
            for (EvidenceRef evidenceRef : evidenceRefs) {
                if (!evidenceRef.sessionRef().equals(
                        context.callContext().runIdentity().owner().sessionRef())) {
                    throw new IllegalArgumentException(
                            "evidenceRef.sessionRef must match current session");
                }
            }
        }
    }

    /** 描述一次对既有挂起 Workflow 的幂等恢复。 */
    record WorkflowResume(
            ProviderCallContext context,
            CapabilitySnapshotRef snapshot,
            FrozenWorkflowTarget target,
            ExecutionRef executionRef,
            String suspendRef,
            String actionKey,
            TypedPayload response,
            long expectedRevision,
            IdempotencyKey idempotencyKey) {

        /** 拒绝跨租户 Execution、空白恢复身份与非正 CAS revision。 */
        public WorkflowResume {
            context = PortContracts.requireNonNull(context, "context");
            snapshot = PortContracts.requireNonNull(snapshot, "snapshot");
            target = PortContracts.requireNonNull(target, "target");
            executionRef = requireCurrentTenant(context, executionRef, "executionRef");
            suspendRef = PortContracts.requireText(suspendRef, "suspendRef");
            actionKey = PortContracts.requireText(actionKey, "actionKey");
            response = PortContracts.requireNonNull(response, "response");
            expectedRevision = PortContracts.requirePositive(expectedRevision, "expectedRevision");
            idempotencyKey = PortContracts.requireNonNull(idempotencyKey, "idempotencyKey");
            requireCurrentSnapshot(context, snapshot);
        }
    }

    /** 描述一次按冻结目标与完整 Execution 身份进行的等待或终态恢复查询。 */
    record WorkflowResultQuery(
            ProviderCallContext context,
            CapabilitySnapshotRef snapshot,
            FrozenWorkflowTarget target,
            ExecutionRef executionRef) {

        /** 拒绝缺失上下文、目标或跨租户 Execution。 */
        public WorkflowResultQuery {
            context = PortContracts.requireNonNull(context, "context");
            snapshot = PortContracts.requireNonNull(snapshot, "snapshot");
            target = PortContracts.requireNonNull(target, "target");
            executionRef = requireCurrentTenant(context, executionRef, "executionRef");
            requireCurrentSnapshot(context, snapshot);
        }
    }

    /** 保存 Provider 已线性化“旧 execute 不存在且此后不可提交”的 attempt 级证明。 */
    record InvocationBarrierProof(
            ActionRef actionRef,
            String invocationId,
            String blockedExecuteAttemptId,
            String blockedExecuteRequestFingerprint,
            ResultRef reconciliationRef,
            ResultRef invocationBarrierRef) {

        /** 拒绝缺失 action、空白 attempt/fingerprint 或缺失权威证明引用。 */
        public InvocationBarrierProof {
            actionRef = PortContracts.requireNonNull(actionRef, "actionRef");
            invocationId = PortContracts.requireText(invocationId, "invocationId");
            blockedExecuteAttemptId = PortContracts.requireText(
                    blockedExecuteAttemptId, "blockedExecuteAttemptId");
            blockedExecuteRequestFingerprint = PortContracts.requireText(
                    blockedExecuteRequestFingerprint, "blockedExecuteRequestFingerprint");
            reconciliationRef = PortContracts.requireNonNull(
                    reconciliationRef, "reconciliationRef");
            invocationBarrierRef = PortContracts.requireNonNull(
                    invocationBarrierRef, "invocationBarrierRef");
            if (invocationId.equals(blockedExecuteAttemptId)) {
                throw new IllegalArgumentException(
                        "blockedExecuteAttemptId must differ from invocationId");
            }
        }
    }

    /** 描述授权主体对租户内 Workflow Execution 的只读查询。 */
    record WorkflowQuery(VerifiedIdentity identity, ExecutionRef executionRef) {

        /** 拒绝缺失身份或跨租户 Execution。 */
        public WorkflowQuery {
            identity = PortContracts.requireNonNull(identity, "identity");
            executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
            PortContracts.requireSameTenant(
                    identity.owner().sessionRef().tenantId(),
                    executionRef.tenantId(),
                    "executionRef.tenantId");
        }
    }

    /** 描述一次对 Workflow Execution 的幂等 CAS 取消。 */
    record WorkflowCancel(
            ProviderCallContext context,
            CapabilitySnapshotRef snapshot,
            FrozenWorkflowTarget target,
            ExecutionRef executionRef,
            String actionKey,
            long expectedRevision,
            String reasonCode,
            IdempotencyKey idempotencyKey) {

        /** 拒绝跨租户 Execution、空白动作/原因与非正 CAS revision。 */
        public WorkflowCancel {
            context = PortContracts.requireNonNull(context, "context");
            snapshot = PortContracts.requireNonNull(snapshot, "snapshot");
            target = PortContracts.requireNonNull(target, "target");
            executionRef = requireCurrentTenant(context, executionRef, "executionRef");
            actionKey = PortContracts.requireText(actionKey, "actionKey");
            expectedRevision = PortContracts.requirePositive(expectedRevision, "expectedRevision");
            reasonCode = PortContracts.requireText(reasonCode, "reasonCode");
            idempotencyKey = PortContracts.requireNonNull(idempotencyKey, "idempotencyKey");
            requireCurrentSnapshot(context, snapshot);
        }
    }

    /** 表达调度或恢复后的非终态、终态与结果未知状态。 */
    sealed interface WorkflowResult
            permits WorkflowResult.Accepted, WorkflowResult.Suspended,
                    WorkflowResult.Completed, WorkflowResult.Failed,
                    WorkflowResult.Terminated, WorkflowResult.Cancelled,
                    WorkflowResult.OutcomeUnknown {

        /** 表示 Execution 已被受理。 */
        record Accepted(ExecutionRef executionRef, long revision) implements WorkflowResult {

            /** 拒绝缺失 Execution 或非正 revision。 */
            public Accepted {
                executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
                revision = PortContracts.requirePositive(revision, "revision");
            }
        }

        /** 表示 Execution 已提交挂起事实。 */
        record Suspended(
                ExecutionRef executionRef,
                String suspendRef,
                long revision,
                ResultRef resultRef,
                com.skillforge.runtime.api.LoopSuspendReason reason) implements WorkflowResult {

            /** 拒绝缺失挂起身份、Execution、revision 或结果引用。 */
            public Suspended {
                if (reason != com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_HITL
                        && reason != com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_EXTERNAL)
                    throw new IllegalArgumentException("invalid_workflow_suspend_reason");
                executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
                suspendRef = PortContracts.requireText(suspendRef, "suspendRef");
                revision = PortContracts.requirePositive(revision, "revision");
                resultRef = PortContracts.requireNonNull(resultRef, "resultRef");
            }
        }

        /** 表示 Execution 已成功提交输出。 */
        record Completed(
                ExecutionRef executionRef,
                TypedPayload output,
                long revision,
                ResultRef resultRef) implements WorkflowResult {

            /** 拒绝缺失 Execution、输出、revision 或结果引用。 */
            public Completed {
                executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
                output = PortContracts.requireNonNull(output, "output");
                revision = PortContracts.requirePositive(revision, "revision");
                resultRef = PortContracts.requireNonNull(resultRef, "resultRef");
            }
        }

        /** 表示 Execution 已确定失败。 */
        record Failed(
                ExecutionRef executionRef,
                OptionalLong revision,
                PortFailure failure) implements WorkflowResult {

            /** 拒绝缺失 Execution/Optional、存在时非正 revision 或缺失失败事实。 */
            public Failed {
                executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
                revision = requireOptionalRevision(revision, "revision");
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }

        /** 表示 Execution 因 Loop 治理原因终止。 */
        record Terminated(
                ExecutionRef executionRef,
                long revision,
                LoopTerminationReason reason,
                ResultRef resultRef) implements WorkflowResult {

            /** 拒绝缺失 Execution、终止原因、revision 或结果引用。 */
            public Terminated {
                executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
                revision = PortContracts.requirePositive(revision, "revision");
                reason = PortContracts.requireNonNull(reason, "reason");
                resultRef = PortContracts.requireNonNull(resultRef, "resultRef");
            }
        }

        /** 表示 Execution 已提交取消终态。 */
        record Cancelled(
                ExecutionRef executionRef,
                long revision,
                ResultRef resultRef) implements WorkflowResult {

            /** 拒绝缺失 Execution、revision 或结果引用。 */
            public Cancelled {
                executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
                revision = PortContracts.requirePositive(revision, "revision");
                resultRef = PortContracts.requireNonNull(resultRef, "resultRef");
            }
        }

        /** 表示 Execution 结果未知且只能查询、协调或停止自动动作。 */
        record OutcomeUnknown(
                ExecutionRef executionRef,
                OptionalLong revision,
                PortFailure failure) implements WorkflowResult {

            /** 拒绝稳定键直接重放这一 Workflow 未知结果语义。 */
            public OutcomeUnknown {
                executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
                revision = requireOptionalRevision(revision, "revision");
                failure = requireRetryMode(
                        failure,
                        Set.of(RetryMode.NONE, RetryMode.RESULT_QUERYABLE));
            }
        }
    }

    /** 表达只读恢复查询的挂起、四个真实终态、线性化 Absent 或确定查询拒绝。 */
    sealed interface WorkflowRecoveredResult
            permits WorkflowRecoveredResult.Suspended, WorkflowRecoveredResult.Completed, WorkflowRecoveredResult.Failed,
                    WorkflowRecoveredResult.Terminated, WorkflowRecoveredResult.Cancelled,
                      WorkflowRecoveredResult.Absent, WorkflowRecoveredResult.Rejected {

        /** 原调用的响应丢失时恢复已提交的等待边界；不是重新执行或伪造 Execution 终态。 */
        record Suspended(WorkflowResult.Suspended result) implements WorkflowRecoveredResult {
            public Suspended { result = PortContracts.requireNonNull(result, "result"); }
        }

        /** 包装已提交的成功终态。 */
        record Completed(WorkflowResult.Completed result) implements WorkflowRecoveredResult {

            /** 拒绝缺失的成功终态。 */
            public Completed {
                result = PortContracts.requireNonNull(result, "result");
            }
        }

        /** 包装已提交的失败终态。 */
        record Failed(WorkflowResult.Failed result) implements WorkflowRecoveredResult {

            /** 拒绝缺失的失败终态。 */
            public Failed {
                result = PortContracts.requireNonNull(result, "result");
                if (result.revision().isEmpty()
                        || result.failure().retryMode() != RetryMode.NONE) {
                    throw new IllegalArgumentException(
                            "recovered failure must have revision and RetryMode.NONE");
                }
            }
        }

        /** 包装已提交的 Loop 终止态。 */
        record Terminated(WorkflowResult.Terminated result) implements WorkflowRecoveredResult {

            /** 拒绝缺失的终止态。 */
            public Terminated {
                result = PortContracts.requireNonNull(result, "result");
            }
        }

        /** 包装已提交的 Workflow 取消终态。 */
        record Cancelled(WorkflowResult.Cancelled result) implements WorkflowRecoveredResult {

            /** 拒绝缺失的取消终态。 */
            public Cancelled {
                result = PortContracts.requireNonNull(result, "result");
            }
        }

        /** 表示权威查询已建立旧 execute 不存在且此后不可提交的线性化屏障。 */
        record Absent(
                ExecutionRef executionRef,
                InvocationBarrierProof barrierProof) implements WorkflowRecoveredResult {

            /** 拒绝缺失 Execution、证明或跨租户 action。 */
            public Absent {
                executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
                barrierProof = PortContracts.requireNonNull(barrierProof, "barrierProof");
                PortContracts.requireSameTenant(
                        executionRef.tenantId(),
                        barrierProof.actionRef().tenantId(),
                        "barrierProof.actionRef.tenantId");
            }
        }

        /** 表示只读恢复查询被确定拒绝。 */
        record Rejected(PortFailure failure) implements WorkflowRecoveredResult {

            /** 拒绝缺失的稳定失败事实。 */
            public Rejected {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }
    }

    /** 保存 Workflow 当前状态、冻结目标、revision 与可选结果引用。 */
    record WorkflowSnapshot(
            ExecutionRef executionRef,
            FrozenWorkflowTarget target,
            WorkflowExecutionState state,
            long revision,
            Optional<ResultRef> resultRef,
            Instant updatedAt) {

        /** 拒绝缺失状态、空 Optional、非正 revision 与缺失更新时间。 */
        public WorkflowSnapshot {
            executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
            target = PortContracts.requireNonNull(target, "target");
            state = PortContracts.requireNonNull(state, "state");
            revision = PortContracts.requirePositive(revision, "revision");
            resultRef = PortContracts.requireOptional(resultRef, "resultRef");
            updatedAt = PortContracts.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /** 表达取消操作已应用、幂等重放、CAS 冲突或确定拒绝。 */
    sealed interface WorkflowControlResult
            permits WorkflowControlResult.Applied, WorkflowControlResult.Replayed,
                    WorkflowControlResult.Conflict, WorkflowControlResult.Rejected,
                    WorkflowControlResult.OutcomeUnknown {

        /** 返回取消后新快照。 */
        record Applied(WorkflowSnapshot execution) implements WorkflowControlResult {

            /** 拒绝缺失的已应用快照。 */
            public Applied {
                execution = requireCancelledSnapshot(execution, "execution");
            }
        }

        /** 返回同一幂等动作已产生的快照。 */
        record Replayed(WorkflowSnapshot execution) implements WorkflowControlResult {

            /** 拒绝缺失的重放快照。 */
            public Replayed {
                execution = requireCancelledSnapshot(execution, "execution");
            }
        }

        /** 返回 CAS 冲突时的当前快照和稳定失败事实。 */
        record Conflict(WorkflowSnapshot current, PortFailure failure)
                implements WorkflowControlResult {

            /** 拒绝缺失快照/失败或任何非 NONE 重试模式。 */
            public Conflict {
                current = PortContracts.requireNonNull(current, "current");
                failure = requireNoneFailure(failure, "Workflow cancel Conflict");
            }
        }

        /** 表示取消请求被确定拒绝。 */
        record Rejected(PortFailure failure) implements WorkflowControlResult {

            /** 拒绝缺失的稳定失败事实。 */
            public Rejected {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }

        /** 表示取消调用结果未知，只允许停止或走结果查询。 */
        record OutcomeUnknown(PortFailure failure) implements WorkflowControlResult {

            /** 拒绝稳定键直重放与跨账本协调模式。 */
            public OutcomeUnknown {
                failure = requireRetryMode(
                        failure, Set.of(RetryMode.NONE, RetryMode.RESULT_QUERYABLE));
            }
        }
    }

    /** 校验 Workflow 挂载只引用 WORKFLOW 类型能力。 */
    private static CapabilityRef requireCapability(CapabilityRef capability) {
        capability = PortContracts.requireNonNull(capability, "capability");
        if (capability.type() != CapabilityType.WORKFLOW) {
            throw new IllegalArgumentException("capability type must be WORKFLOW");
        }
        return capability;
    }

    /** 校验挂载句柄有精确前缀和非空白 mountId。 */
    private static String requireHandle(String value, String prefix) {
        value = PortContracts.requireText(value, "value");
        if (!value.startsWith(prefix) || value.length() == prefix.length()
                || value.substring(prefix.length()).isBlank()) {
            throw new IllegalArgumentException("handle must use " + prefix + "<mountId>");
        }
        return value;
    }

    /** 校验能力快照属于当前 Session 与当前因果输入。 */
    private static void requireCurrentSnapshot(
            ProviderCallContext context,
            CapabilitySnapshotRef snapshot) {
        PortCallContext callContext = context.callContext();
        if (!snapshot.sessionRef().equals(callContext.runIdentity().owner().sessionRef())
                || !snapshot.inputId().equals(callContext.runIdentity().causationInputId())) {
            throw new IllegalArgumentException("snapshot must match current session and causation input");
        }
    }

    /** 校验 Execution 属于调用上下文的租户。 */
    private static ExecutionRef requireCurrentTenant(
            ProviderCallContext context,
            ExecutionRef executionRef,
            String field) {
        executionRef = PortContracts.requireNonNull(executionRef, field);
        PortContracts.requireSameTenant(
                context.callContext().runIdentity().owner().sessionRef().tenantId(),
                executionRef.tenantId(),
                field + ".tenantId");
        return executionRef;
    }

    /** 校验 Workflow 结果未知分支只声明可恢复查询或协调的模式。 */
    private static PortFailure requireRetryMode(PortFailure failure, Set<RetryMode> allowedModes) {
        failure = PortContracts.requireNonNull(failure, "failure");
        if (!allowedModes.contains(failure.retryMode())) {
            throw new IllegalArgumentException("Workflow OutcomeUnknown retry mode is not supported");
        }
        return failure;
    }

    /** 校验 OptionalLong 存在时只承载正数权威 revision。 */
    private static OptionalLong requireOptionalRevision(OptionalLong revision, String field) {
        revision = PortContracts.requireNonNull(revision, field);
        if (revision.isPresent() && revision.getAsLong() <= 0) {
            throw new IllegalArgumentException(field + " must be positive when present");
        }
        return revision;
    }

    /** 校验取消冲突只能表达确定失败，不能诱导自动重试。 */
    private static PortFailure requireNoneFailure(PortFailure failure, String outcome) {
        failure = PortContracts.requireNonNull(failure, "failure");
        if (failure.retryMode() != RetryMode.NONE) {
            throw new IllegalArgumentException(outcome + " retry mode must be NONE");
        }
        return failure;
    }

    /** 校验 cancel Applied/Replayed 只能携带已经提交的 CANCELLED 快照。 */
    private static WorkflowSnapshot requireCancelledSnapshot(
            WorkflowSnapshot snapshot,
            String field) {
        snapshot = PortContracts.requireNonNull(snapshot, field);
        if (snapshot.state() != WorkflowExecutionState.CANCELLED) {
            throw new IllegalArgumentException(field + " state must be CANCELLED");
        }
        return snapshot;
    }
}
