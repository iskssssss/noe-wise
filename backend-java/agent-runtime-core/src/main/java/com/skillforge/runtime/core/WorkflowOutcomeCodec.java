package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.PortalOutcomeSnapshot;
import com.skillforge.runtime.api.port.PortalResolution;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.InvocationBarrierProof;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowControlResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowSnapshot;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowRecoveredResult;
import com.skillforge.runtime.core.CanonicalJson.Field;
import com.skillforge.runtime.core.CanonicalJson.Value;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Workflow 私有结果的唯一结构编码器；完整保存公开 Port 联合类型，但不产生 observation、
 * 调用许可、提交证明或任何业务副作用。
 */
final class WorkflowOutcomeCodec {
    static final String INVOKE_SCHEMA = "skillforge.runtime.portal.workflow-invoke-outcome.v2";
    static final String RECOVER_SCHEMA = "skillforge.runtime.portal.workflow-recover-outcome.v2";
    static final String CONTROL_SCHEMA = "skillforge.runtime.portal.workflow-control-outcome.v1";

    private final CodecLimits limits;
    private final PayloadValidator outputs;

    /** Workflow output registry 必须由宿主显式提供，不能借用模型或 Tool 的默认实现。 */
    WorkflowOutcomeCodec(CodecLimits limits, PayloadValidator outputs) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.outputs = Objects.requireNonNull(outputs, "outputs");
    }

    /** 组合协议在进入业务 output registry 前比对完整 dispatch 事实。 */
    PortalOutcomeSnapshot structureInvokeSnapshot(PortalCallKind kind, WorkflowResult result) {
        requireInvokeKind(kind);
        requireResult(result);
        preflightInvoke(result, 1);
        return invokeSnapshot(kind, result);
    }

    /** 组合协议只做 recover 结构闭包；不会把 Absent/Rejected 当成原执行结果。 */
    PortalOutcomeSnapshot structureRecoverSnapshot(WorkflowRecoveredResult result) {
        requireResult(result);
        preflightRecover(result);
        return recoverSnapshot(result);
    }

    /** dispatch/resume 在构造 JSON 树前精确预检，并经同一解码路径校验一次业务 output。 */
    PortalOutcomeSnapshot encodeInvoke(PortalCallKind kind, WorkflowResult result) {
        PortalOutcomeSnapshot snapshot;
        try {
            requireInvokeKind(kind);
            requireResult(result);
            preflightInvoke(result, 1);
            snapshot = invokeSnapshot(kind, result);
        } catch (IllegalArgumentException exception) {
            throw invalidInvoke();
        }
        decodeInvoke(snapshot, kind);
        return snapshot;
    }

    /** 外层 tuple、内部 discriminator、精确字段和 canonical 重编码全部匹配后才调用 output registry。 */
    WorkflowResult decodeInvoke(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        WorkflowResult result = structureDecodeInvoke(snapshot, expectedKind);
        validateOutput(result);
        return result;
    }

    /** 只闭合 invoke 的 wire 结构；调用方可在全部本地校验完成后再进入可扩展 output registry。 */
    WorkflowResult structureDecodeInvoke(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        WorkflowResult result;
        try {
            requireInvokeKind(expectedKind);
            requireSnapshot(snapshot, expectedKind, INVOKE_SCHEMA);
            Value root = CanonicalJson.parse(snapshot.outcome().canonicalJson(), limits);
            result = readInvoke(root);
            if (!snapshot.equals(invokeSnapshot(expectedKind, result))) {
                throw invalidInvoke();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidInvoke();
        }
        return result;
    }

    /** 已完成本地结构闭包后单独进入可扩展 registry，使调用方能保留技术异常身份。 */
    void validateInvokeOutput(WorkflowResult result) {
        validateOutput(result);
    }

    /** recover 固定使用只读查询 kind，并在分配前计量 wrapper 与完整内部 invoke body。 */
    PortalOutcomeSnapshot encodeRecover(PortalCallKind kind, WorkflowRecoveredResult result) {
        PortalOutcomeSnapshot snapshot;
        try {
            requireRecoverKind(kind);
            requireResult(result);
            preflightRecover(result);
            snapshot = recoverSnapshot(result);
        } catch (IllegalArgumentException exception) {
            throw invalidRecover();
        }
        decodeRecover(snapshot, kind);
        return snapshot;
    }

    /** recover 保留挂起与四个终态 wrapper，Absent 屏障和 Rejected 查询失败均不冒充 invoke 结果。 */
    /** 已确认的恢复结果用于同一 resume 投影；Absent/Rejected 不是业务终态。 */
    WorkflowResult decodeResume(PortalOutcomeSnapshot snapshot) {
        if (snapshot.callKind() == PortalCallKind.WORKFLOW_RESUME)
            return decodeInvoke(snapshot, PortalCallKind.WORKFLOW_RESUME);
        return recoveredResult(decodeRecover(snapshot, PortalCallKind.WORKFLOW_RECOVER_RESULT));
    }

    WorkflowResult structureDecodeResume(PortalOutcomeSnapshot snapshot) {
        if (snapshot.callKind() == PortalCallKind.WORKFLOW_RESUME)
            return structureDecodeInvoke(snapshot, PortalCallKind.WORKFLOW_RESUME);
        return recoveredResult(structureDecodeRecover(snapshot, PortalCallKind.WORKFLOW_RECOVER_RESULT));
    }

    private static WorkflowResult recoveredResult(WorkflowRecoveredResult result) {
        return switch (result) {
            case WorkflowRecoveredResult.Completed value -> value.result();
            case WorkflowRecoveredResult.Suspended value -> value.result();
            case WorkflowRecoveredResult.Failed value -> value.result();
            case WorkflowRecoveredResult.Cancelled value -> value.result();
            case WorkflowRecoveredResult.Terminated value -> value.result();
            case WorkflowRecoveredResult.Absent ignored -> throw new IllegalArgumentException("resume recovery absent");
            case WorkflowRecoveredResult.Rejected ignored -> throw new IllegalArgumentException("resume recovery pending");
        };
    }

    WorkflowRecoveredResult decodeRecover(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        WorkflowRecoveredResult result = structureDecodeRecover(snapshot, expectedKind);
        validateOutput(result);
        return result;
    }

    /** 只闭合 recover wrapper 与内部终态结构，不提前调用 completed output registry。 */
    WorkflowRecoveredResult structureDecodeRecover(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        WorkflowRecoveredResult result;
        try {
            requireRecoverKind(expectedKind);
            requireSnapshot(snapshot, expectedKind, RECOVER_SCHEMA);
            Value root = CanonicalJson.parse(snapshot.outcome().canonicalJson(), limits);
            String type = root.field("outcomeType").stringValue();
            result = switch (type) {
                case "Suspended" -> {
                    root.requireFields("outcomeType", "result");
                    WorkflowResult nested = readInvoke(root.field("result"));
                    if (!(nested instanceof WorkflowResult.Suspended suspended)) throw invalidRecover();
                    yield new WorkflowRecoveredResult.Suspended(suspended);
                }
                case "Completed" -> {
                    root.requireFields("outcomeType", "result");
                    WorkflowResult nested = readInvoke(root.field("result"));
                    if (!(nested instanceof WorkflowResult.Completed completed)) {
                        throw invalidRecover();
                    }
                    yield new WorkflowRecoveredResult.Completed(completed);
                }
                case "Failed" -> {
                    root.requireFields("outcomeType", "result");
                    WorkflowResult nested = readInvoke(root.field("result"));
                    if (!(nested instanceof WorkflowResult.Failed failed)) {
                        throw invalidRecover();
                    }
                    yield new WorkflowRecoveredResult.Failed(failed);
                }
                case "Terminated" -> {
                    root.requireFields("outcomeType", "result");
                    WorkflowResult nested = readInvoke(root.field("result"));
                    if (!(nested instanceof WorkflowResult.Terminated terminated)) {
                        throw invalidRecover();
                    }
                    yield new WorkflowRecoveredResult.Terminated(terminated);
                }
                case "Cancelled" -> {
                    root.requireFields("outcomeType", "result");
                    WorkflowResult nested = readInvoke(root.field("result"));
                    if (!(nested instanceof WorkflowResult.Cancelled cancelled)) {
                        throw invalidRecover();
                    }
                    yield new WorkflowRecoveredResult.Cancelled(cancelled);
                }
                case "Absent" -> {
                    root.requireFields("outcomeType", "executionRef", "barrierProof");
                    yield new WorkflowRecoveredResult.Absent(
                            readExecutionRef(root.field("executionRef")),
                            readBarrierProof(root.field("barrierProof")));
                }
                case "Rejected" -> {
                    root.requireFields("outcomeType", "failure");
                    yield new WorkflowRecoveredResult.Rejected(readFailure(root.field("failure")));
                }
                default -> throw invalidRecover();
            };
            if (!snapshot.equals(recoverSnapshot(result))) {
                throw invalidRecover();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidRecover();
        }
        return result;
    }

    /** recover 仅 Completed 消费业务 registry；异常不得被本地 codec 脱敏规则改写。 */
    void validateRecoverOutput(WorkflowRecoveredResult result) {
        validateOutput(result);
    }

    /** cancel 固定使用控制 kind，完整快照和失败事实均在构造 JSON 树前精确计量。 */
    PortalOutcomeSnapshot encodeControl(PortalCallKind kind, WorkflowControlResult result) {
        PortalOutcomeSnapshot snapshot;
        try {
            requireControlKind(kind);
            requireResult(result);
            preflightControl(result);
            snapshot = controlSnapshot(result);
        } catch (IllegalArgumentException exception) {
            throw invalidControl();
        }
        decodeControl(snapshot, kind);
        return snapshot;
    }

    /** cancel 解码保留完整 current/execution，API 构造器继续拥有 CANCELLED 与 RetryMode 矩阵。 */
    WorkflowControlResult decodeControl(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        WorkflowControlResult result;
        try {
            requireControlKind(expectedKind);
            requireSnapshot(snapshot, expectedKind, CONTROL_SCHEMA);
            Value root = CanonicalJson.parse(snapshot.outcome().canonicalJson(), limits);
            String type = root.field("outcomeType").stringValue();
            result = switch (type) {
                case "Applied" -> {
                    root.requireFields("outcomeType", "execution");
                    yield new WorkflowControlResult.Applied(readSnapshot(root.field("execution")));
                }
                case "Replayed" -> {
                    root.requireFields("outcomeType", "execution");
                    yield new WorkflowControlResult.Replayed(readSnapshot(root.field("execution")));
                }
                case "Conflict" -> {
                    root.requireFields("outcomeType", "current", "failure");
                    yield new WorkflowControlResult.Conflict(
                            readSnapshot(root.field("current")), readFailure(root.field("failure")));
                }
                case "Rejected" -> {
                    root.requireFields("outcomeType", "failure");
                    yield new WorkflowControlResult.Rejected(readFailure(root.field("failure")));
                }
                case "OutcomeUnknown" -> {
                    root.requireFields("outcomeType", "failure");
                    yield new WorkflowControlResult.OutcomeUnknown(readFailure(root.field("failure")));
                }
                default -> throw invalidControl();
            };
            if (!snapshot.equals(controlSnapshot(result))) {
                throw invalidControl();
            }
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalidControl();
        }
        return result;
    }

    /** invoke body 的计量深度可复用于 recover 的嵌套 result，字段与 API record 一一对应。 */
    private void preflightInvoke(WorkflowResult result, int depth) {
        OutcomeEncodingBudget budget = new OutcomeEncodingBudget(limits);
        preflightInvoke(budget, result, depth);
    }

    /** 在同一 recover 预算中计量 wrapper 与内部 invoke body，避免嵌套重新从零开始计数。 */
    private static void preflightInvoke(
            OutcomeEncodingBudget budget, WorkflowResult result, int depth) {
        switch (result) {
            case WorkflowResult.Accepted value -> {
                budget.object(depth, "outcomeType", "executionRef", "revision");
                budget.string(depth + 1, "Accepted");
                preflightExecutionRef(budget, value.executionRef(), depth + 1);
                budget.string(depth + 1, Long.toString(value.revision()));
            }
            case WorkflowResult.Suspended value -> {
                budget.object(depth, "outcomeType", "executionRef", "suspendRef", "revision", "resultRef", "reason");
                budget.string(depth + 1, value.reason().name());
                budget.string(depth + 1, "Suspended");
                preflightExecutionRef(budget, value.executionRef(), depth + 1);
                budget.string(depth + 1, value.suspendRef());
                budget.string(depth + 1, Long.toString(value.revision()));
                budget.string(depth + 1, value.resultRef().value());
            }
            case WorkflowResult.Completed value -> {
                budget.object(depth, "outcomeType", "executionRef", "output", "revision", "resultRef");
                budget.string(depth + 1, "Completed");
                preflightExecutionRef(budget, value.executionRef(), depth + 1);
                budget.payload(depth + 1, value.output());
                budget.string(depth + 1, Long.toString(value.revision()));
                budget.string(depth + 1, value.resultRef().value());
            }
            case WorkflowResult.Failed value -> preflightFailureResult(
                    budget, "Failed", value.executionRef(), value.revision(), value.failure(), depth);
            case WorkflowResult.Terminated value -> {
                budget.object(depth, "outcomeType", "executionRef", "revision", "reason", "resultRef");
                budget.string(depth + 1, "Terminated");
                preflightExecutionRef(budget, value.executionRef(), depth + 1);
                budget.string(depth + 1, Long.toString(value.revision()));
                budget.string(depth + 1, value.reason().name());
                budget.string(depth + 1, value.resultRef().value());
            }
            case WorkflowResult.Cancelled value -> {
                budget.object(depth, "outcomeType", "executionRef", "revision", "resultRef");
                budget.string(depth + 1, "Cancelled");
                preflightExecutionRef(budget, value.executionRef(), depth + 1);
                budget.string(depth + 1, Long.toString(value.revision()));
                budget.string(depth + 1, value.resultRef().value());
            }
            case WorkflowResult.OutcomeUnknown value -> preflightFailureResult(
                    budget, "OutcomeUnknown", value.executionRef(), value.revision(), value.failure(), depth);
        }
    }

    /** revision 的 OptionalLong 总是占一个值节点，空值不能通过省略字段改变 presence。 */
    private static void preflightFailureResult(
            OutcomeEncodingBudget budget,
            String type,
            ExecutionRef executionRef,
            OptionalLong revision,
            PortFailure failure,
            int depth) {
        budget.object(depth, "outcomeType", "executionRef", "revision", "failure");
        budget.string(depth + 1, type);
        preflightExecutionRef(budget, executionRef, depth + 1);
        preflightOptionalLong(budget, revision, depth + 1);
        budget.failure(depth + 1, failure);
    }

    /** recover 六种分支共享一份预算，四个 wrapper 计入完整 result 对象。 */
    private void preflightRecover(WorkflowRecoveredResult result) {
        OutcomeEncodingBudget budget = new OutcomeEncodingBudget(limits);
        switch (result) {
            case WorkflowRecoveredResult.Suspended value -> preflightWrapped(budget, "Suspended", value.result());
            case WorkflowRecoveredResult.Completed value -> preflightWrapped(
                    budget, "Completed", value.result());
            case WorkflowRecoveredResult.Failed value -> preflightWrapped(
                    budget, "Failed", value.result());
            case WorkflowRecoveredResult.Terminated value -> preflightWrapped(
                    budget, "Terminated", value.result());
            case WorkflowRecoveredResult.Cancelled value -> preflightWrapped(
                    budget, "Cancelled", value.result());
            case WorkflowRecoveredResult.Absent value -> {
                budget.object(1, "outcomeType", "executionRef", "barrierProof");
                budget.string(2, "Absent");
                preflightExecutionRef(budget, value.executionRef(), 2);
                preflightBarrierProof(budget, value.barrierProof(), 2);
            }
            case WorkflowRecoveredResult.Rejected value -> {
                budget.object(1, "outcomeType", "failure");
                budget.string(2, "Rejected");
                budget.failure(2, value.failure());
            }
        }
    }

    /** wrapper 的 result 从深度二开始，不能按独立 invoke root 少算深度。 */
    private static void preflightWrapped(
            OutcomeEncodingBudget budget, String type, WorkflowResult result) {
        budget.object(1, "outcomeType", "result");
        budget.string(2, type);
        preflightInvoke(budget, result, 2);
    }

    /** 屏障证明完整保存 attempt identity、fingerprint 与两类权威引用。 */
    private static void preflightBarrierProof(
            OutcomeEncodingBudget budget, InvocationBarrierProof proof, int depth) {
        budget.object(depth, "actionRef", "invocationId", "blockedExecuteAttemptId",
                "blockedExecuteRequestFingerprint", "reconciliationRef", "invocationBarrierRef");
        preflightActionRef(budget, proof.actionRef(), depth + 1);
        budget.string(depth + 1, proof.invocationId());
        budget.string(depth + 1, proof.blockedExecuteAttemptId());
        budget.string(depth + 1, proof.blockedExecuteRequestFingerprint());
        budget.string(depth + 1, proof.reconciliationRef().value());
        budget.string(depth + 1, proof.invocationBarrierRef().value());
    }

    /** control 五种分支逐字段计量，快照中的 target/capability 不得缩成引用。 */
    private void preflightControl(WorkflowControlResult result) {
        OutcomeEncodingBudget budget = new OutcomeEncodingBudget(limits);
        switch (result) {
            case WorkflowControlResult.Applied value -> preflightExecution(
                    budget, "Applied", "execution", value.execution());
            case WorkflowControlResult.Replayed value -> preflightExecution(
                    budget, "Replayed", "execution", value.execution());
            case WorkflowControlResult.Conflict value -> {
                budget.object(1, "outcomeType", "current", "failure");
                budget.string(2, "Conflict");
                preflightSnapshot(budget, value.current(), 2);
                budget.failure(2, value.failure());
            }
            case WorkflowControlResult.Rejected value -> preflightControlFailure(
                    budget, "Rejected", value.failure());
            case WorkflowControlResult.OutcomeUnknown value -> preflightControlFailure(
                    budget, "OutcomeUnknown", value.failure());
        }
    }

    /** Applied/Replayed 的字段名不同于 Conflict.current，分别按真实 wire 计量。 */
    private static void preflightExecution(
            OutcomeEncodingBudget budget, String type, String field, WorkflowSnapshot snapshot) {
        budget.object(1, "outcomeType", field);
        budget.string(2, type);
        preflightSnapshot(budget, snapshot, 2);
    }

    /** 失败型控制结果保留 API RetryMode，不套用模型 NONE 规则。 */
    private static void preflightControlFailure(
            OutcomeEncodingBudget budget, String type, PortFailure failure) {
        budget.object(1, "outcomeType", "failure");
        budget.string(2, type);
        budget.failure(2, failure);
    }

    /** WorkflowSnapshot 的六个组件全部写入，Optional<ResultRef> 用显式 null。 */
    private static void preflightSnapshot(
            OutcomeEncodingBudget budget, WorkflowSnapshot snapshot, int depth) {
        budget.object(depth, "executionRef", "target", "state", "revision", "resultRef", "updatedAt");
        preflightExecutionRef(budget, snapshot.executionRef(), depth + 1);
        preflightTarget(budget, snapshot.target(), depth + 1);
        budget.string(depth + 1, snapshot.state().name());
        budget.string(depth + 1, Long.toString(snapshot.revision()));
        if (snapshot.resultRef().isPresent()) {
            budget.string(depth + 1, snapshot.resultRef().orElseThrow().value());
        } else {
            budget.nil(depth + 1);
        }
        budget.string(depth + 1, snapshot.updatedAt().toString());
    }

    /** 冻结目标保存句柄、实例 revision、完整能力版本与规范 Duration。 */
    private static void preflightTarget(
            OutcomeEncodingBudget budget, FrozenWorkflowTarget target, int depth) {
        budget.object(depth, "handle", "instanceId", "instanceRevision", "capability", "timeout", "installationRevision");
        budget.string(depth + 1, target.handle().value());
        budget.string(depth + 1, target.instanceId());
        budget.string(depth + 1, Long.toString(target.instanceRevision()));
        budget.string(depth + 1, Long.toString(target.installationRevision()));
        preflightCapability(budget, target.capability(), depth + 1);
        budget.string(depth + 1, target.timeout().toString());
    }

    /** 能力引用按公开 record 的三个组件编码，不能只保留 capabilityId。 */
    private static void preflightCapability(
            OutcomeEncodingBudget budget, CapabilityRef capability, int depth) {
        budget.object(depth, "type", "capabilityId", "versionId");
        budget.string(depth + 1, capability.type().name());
        budget.string(depth + 1, capability.capabilityId());
        budget.string(depth + 1, capability.versionId());
    }

    /** ExecutionRef 是租户复合引用，tenantId 本身仍按单值 wrapper 字符串保存。 */
    private static void preflightExecutionRef(
            OutcomeEncodingBudget budget, ExecutionRef reference, int depth) {
        budget.object(depth, "tenantId", "executionId");
        budget.string(depth + 1, reference.tenantId().value());
        budget.string(depth + 1, reference.executionId());
    }

    /** ActionRef 也是租户复合引用，避免 Absent 屏障跨租户歧义。 */
    private static void preflightActionRef(
            OutcomeEncodingBudget budget, ActionRef reference, int depth) {
        budget.object(depth, "tenantId", "actionId");
        budget.string(depth + 1, reference.tenantId().value());
        budget.string(depth + 1, reference.actionId());
    }

    /** OptionalLong 存在时使用十进制字符串，缺失时保留 JSON null。 */
    private static void preflightOptionalLong(
            OutcomeEncodingBudget budget, OptionalLong value, int depth) {
        if (value.isPresent()) {
            budget.string(depth, Long.toString(value.getAsLong()));
        } else {
            budget.nil(depth);
        }
    }

    /** invoke 外层 resolution 只由公开 subtype 决定，dispatch/resume 仍保留调用方 kind。 */
    private static PortalOutcomeSnapshot invokeSnapshot(
            PortalCallKind kind, WorkflowResult result) {
        requireInvokeKind(kind);
        String type = invokeType(result);
        PortalResolution resolution = switch (result) {
            case WorkflowResult.Accepted ignored -> PortalResolution.KNOWN_RESULT;
            case WorkflowResult.Suspended ignored -> PortalResolution.KNOWN_RESULT;
            case WorkflowResult.Completed ignored -> PortalResolution.KNOWN_RESULT;
            case WorkflowResult.Terminated ignored -> PortalResolution.KNOWN_RESULT;
            case WorkflowResult.Cancelled ignored -> PortalResolution.KNOWN_RESULT;
            case WorkflowResult.Failed ignored -> PortalResolution.KNOWN_FAILURE;
            case WorkflowResult.OutcomeUnknown ignored -> PortalResolution.OUTCOME_UNKNOWN;
        };
        return snapshot(kind, resolution, type, INVOKE_SCHEMA, invokeBody(result));
    }

    /** invoke body 包含 discriminator 和公开 record 的全部组件，不携带 call context 或 attempt 元数据。 */
    private static Value invokeBody(WorkflowResult result) {
        return switch (result) {
            case WorkflowResult.Accepted value -> body("Accepted", List.of(
                    new Field("executionRef", executionRefValue(value.executionRef())),
                    new Field("revision", CanonicalJson.integer(value.revision()))));
            case WorkflowResult.Suspended value -> body("Suspended", List.of(
                    new Field("executionRef", executionRefValue(value.executionRef())),
                    new Field("suspendRef", CanonicalJson.string(value.suspendRef())),
                    new Field("reason", CanonicalJson.string(value.reason().name())),
                    new Field("revision", CanonicalJson.integer(value.revision())),
                    new Field("resultRef", CanonicalJson.string(value.resultRef().value()))));
            case WorkflowResult.Completed value -> body("Completed", List.of(
                    new Field("executionRef", executionRefValue(value.executionRef())),
                    new Field("output", payloadValue(value.output())),
                    new Field("revision", CanonicalJson.integer(value.revision())),
                    new Field("resultRef", CanonicalJson.string(value.resultRef().value()))));
            case WorkflowResult.Failed value -> failureBody(
                    "Failed", value.executionRef(), value.revision(), value.failure());
            case WorkflowResult.Terminated value -> body("Terminated", List.of(
                    new Field("executionRef", executionRefValue(value.executionRef())),
                    new Field("revision", CanonicalJson.integer(value.revision())),
                    new Field("reason", CanonicalJson.string(value.reason().name())),
                    new Field("resultRef", CanonicalJson.string(value.resultRef().value()))));
            case WorkflowResult.Cancelled value -> body("Cancelled", List.of(
                    new Field("executionRef", executionRefValue(value.executionRef())),
                    new Field("revision", CanonicalJson.integer(value.revision())),
                    new Field("resultRef", CanonicalJson.string(value.resultRef().value()))));
            case WorkflowResult.OutcomeUnknown value -> failureBody(
                    "OutcomeUnknown", value.executionRef(), value.revision(), value.failure());
        };
    }

    /** Failed/OutcomeUnknown 共享字段形状，但保留各自 discriminator 与 API presence 矩阵。 */
    private static Value failureBody(
            String type, ExecutionRef executionRef, OptionalLong revision, PortFailure failure) {
        return body(type, List.of(
                new Field("executionRef", executionRefValue(executionRef)),
                new Field("revision", optionalLongValue(revision)),
                new Field("failure", failureValue(failure))));
    }

    /** recover 的挂起与四种真实终态继续嵌入完整 invoke body，不能扁平化或丢失 wrapper。 */
    private static PortalOutcomeSnapshot recoverSnapshot(WorkflowRecoveredResult result) {
        return switch (result) {
            case WorkflowRecoveredResult.Suspended value -> recoverWrapped(PortalResolution.KNOWN_RESULT, "Suspended", value.result());
            case WorkflowRecoveredResult.Completed value -> recoverWrapped(
                    PortalResolution.KNOWN_RESULT, "Completed", value.result());
            case WorkflowRecoveredResult.Failed value -> recoverWrapped(
                    PortalResolution.KNOWN_FAILURE, "Failed", value.result());
            case WorkflowRecoveredResult.Terminated value -> recoverWrapped(
                    PortalResolution.KNOWN_RESULT, "Terminated", value.result());
            case WorkflowRecoveredResult.Cancelled value -> recoverWrapped(
                    PortalResolution.KNOWN_RESULT, "Cancelled", value.result());
            case WorkflowRecoveredResult.Absent value -> {
                Value absentBody = body("Absent", List.of(
                        new Field("executionRef", executionRefValue(value.executionRef())),
                        new Field("barrierProof", barrierProofValue(value.barrierProof()))));
                yield snapshot(
                        PortalCallKind.WORKFLOW_RECOVER_RESULT,
                        PortalResolution.KNOWN_RESULT,
                        "Absent",
                        RECOVER_SCHEMA,
                        absentBody);
            }
            case WorkflowRecoveredResult.Rejected value -> snapshot(
                    PortalCallKind.WORKFLOW_RECOVER_RESULT,
                    PortalResolution.KNOWN_FAILURE,
                    "Rejected",
                    RECOVER_SCHEMA,
                    body("Rejected", List.of(new Field("failure", failureValue(value.failure())))));
        };
    }

    /** wrapper type 必须和内部 invoke discriminator 一致，编码器不接受交叉子类。 */
    private static PortalOutcomeSnapshot recoverWrapped(
            PortalResolution resolution, String type, WorkflowResult result) {
        if (!type.equals(invokeType(result))) {
            throw invalidRecover();
        }
        return snapshot(
                PortalCallKind.WORKFLOW_RECOVER_RESULT,
                resolution,
                type,
                RECOVER_SCHEMA,
                body(type, List.of(new Field("result", invokeBody(result)))));
    }

    /** control 外层 resolution 与 §8.2 注册表逐项绑定。 */
    private static PortalOutcomeSnapshot controlSnapshot(WorkflowControlResult result) {
        return switch (result) {
            case WorkflowControlResult.Applied value -> controlExecution(
                    "Applied", value.execution());
            case WorkflowControlResult.Replayed value -> controlExecution(
                    "Replayed", value.execution());
            case WorkflowControlResult.Conflict value -> snapshot(
                    PortalCallKind.WORKFLOW_CANCEL,
                    PortalResolution.KNOWN_FAILURE,
                    "Conflict",
                    CONTROL_SCHEMA,
                    body("Conflict", List.of(
                            new Field("current", snapshotValue(value.current())),
                            new Field("failure", failureValue(value.failure())))));
            case WorkflowControlResult.Rejected value -> controlFailure(
                    PortalResolution.KNOWN_FAILURE, "Rejected", value.failure());
            case WorkflowControlResult.OutcomeUnknown value -> controlFailure(
                    PortalResolution.OUTCOME_UNKNOWN, "OutcomeUnknown", value.failure());
        };
    }

    /** Applied/Replayed 使用 execution 字段保存取消后的完整权威快照。 */
    private static PortalOutcomeSnapshot controlExecution(
            String type, WorkflowSnapshot execution) {
        return snapshot(
                PortalCallKind.WORKFLOW_CANCEL,
                PortalResolution.KNOWN_RESULT,
                type,
                CONTROL_SCHEMA,
                body(type, List.of(new Field("execution", snapshotValue(execution)))));
    }

    /** Rejected 与 OutcomeUnknown 均完整保存 failure，约束由各自 API 构造器执行。 */
    private static PortalOutcomeSnapshot controlFailure(
            PortalResolution resolution, String type, PortFailure failure) {
        return snapshot(
                PortalCallKind.WORKFLOW_CANCEL,
                resolution,
                type,
                CONTROL_SCHEMA,
                body(type, List.of(new Field("failure", failureValue(failure)))));
    }

    /** 从封闭 invoke body 构造公开联合类型，任何未知或缺失字段都在对应分支拒绝。 */
    private static WorkflowResult readInvoke(Value root) {
        String type = root.field("outcomeType").stringValue();
        return switch (type) {
            case "Accepted" -> {
                root.requireFields("outcomeType", "executionRef", "revision");
                yield new WorkflowResult.Accepted(
                        readExecutionRef(root.field("executionRef")),
                        root.field("revision").longValue());
            }
            case "Suspended" -> {
                root.requireFields("outcomeType", "executionRef", "suspendRef", "revision", "resultRef", "reason");
                yield new WorkflowResult.Suspended(
                        readExecutionRef(root.field("executionRef")),
                        root.field("suspendRef").stringValue(),
                        root.field("revision").longValue(),
                        new ResultRef(root.field("resultRef").stringValue()),
                        com.skillforge.runtime.api.LoopSuspendReason.valueOf(root.field("reason").stringValue()));
            }
            case "Completed" -> {
                root.requireFields("outcomeType", "executionRef", "output", "revision", "resultRef");
                yield new WorkflowResult.Completed(
                        readExecutionRef(root.field("executionRef")),
                        readPayload(root.field("output")),
                        root.field("revision").longValue(),
                        new ResultRef(root.field("resultRef").stringValue()));
            }
            case "Failed" -> {
                root.requireFields("outcomeType", "executionRef", "revision", "failure");
                yield new WorkflowResult.Failed(
                        readExecutionRef(root.field("executionRef")),
                        readOptionalLong(root.field("revision")),
                        readFailure(root.field("failure")));
            }
            case "Terminated" -> {
                root.requireFields("outcomeType", "executionRef", "revision", "reason", "resultRef");
                yield new WorkflowResult.Terminated(
                        readExecutionRef(root.field("executionRef")),
                        root.field("revision").longValue(),
                        com.skillforge.runtime.api.LoopTerminationReason.valueOf(
                                root.field("reason").stringValue()),
                        new ResultRef(root.field("resultRef").stringValue()));
            }
            case "Cancelled" -> {
                root.requireFields("outcomeType", "executionRef", "revision", "resultRef");
                yield new WorkflowResult.Cancelled(
                        readExecutionRef(root.field("executionRef")),
                        root.field("revision").longValue(),
                        new ResultRef(root.field("resultRef").stringValue()));
            }
            case "OutcomeUnknown" -> {
                root.requireFields("outcomeType", "executionRef", "revision", "failure");
                yield new WorkflowResult.OutcomeUnknown(
                        readExecutionRef(root.field("executionRef")),
                        readOptionalLong(root.field("revision")),
                        readFailure(root.field("failure")));
            }
            default -> throw invalidInvoke();
        };
    }

    /** ExecutionRef 严格要求 tenantId 与 executionId 两个字段。 */
    private static ExecutionRef readExecutionRef(Value value) {
        value.requireFields("tenantId", "executionId");
        return new ExecutionRef(
                new TenantId(value.field("tenantId").stringValue()),
                value.field("executionId").stringValue());
    }

    /** ActionRef 严格要求 tenantId 与 actionId 两个字段。 */
    private static ActionRef readActionRef(Value value) {
        value.requireFields("tenantId", "actionId");
        return new ActionRef(
                new TenantId(value.field("tenantId").stringValue()),
                value.field("actionId").stringValue());
    }

    /** TypedPayload 只恢复双字段封套，业务内容在完整 wire 校验后交给 registry。 */
    private static TypedPayload readPayload(Value value) {
        value.requireFields("schemaId", "canonicalJson");
        return new TypedPayload(
                value.field("schemaId").stringValue(),
                value.field("canonicalJson").stringValue());
    }

    /** PortFailure 精确保留 reason、RetryMode 和业务结果引用。 */
    private static PortFailure readFailure(Value value) {
        value.requireFields("reasonCode", "retryMode", "resultRef");
        return new PortFailure(
                value.field("reasonCode").stringValue(),
                RetryMode.valueOf(value.field("retryMode").stringValue()),
                new ResultRef(value.field("resultRef").stringValue()));
    }

    /** InvocationBarrierProof 的六个组件逐一恢复，公开构造器继续验证同租户与 identity 差异。 */
    private static InvocationBarrierProof readBarrierProof(Value value) {
        value.requireFields("actionRef", "invocationId", "blockedExecuteAttemptId",
                "blockedExecuteRequestFingerprint", "reconciliationRef", "invocationBarrierRef");
        return new InvocationBarrierProof(
                readActionRef(value.field("actionRef")),
                value.field("invocationId").stringValue(),
                value.field("blockedExecuteAttemptId").stringValue(),
                value.field("blockedExecuteRequestFingerprint").stringValue(),
                new ResultRef(value.field("reconciliationRef").stringValue()),
                new ResultRef(value.field("invocationBarrierRef").stringValue()));
    }

    /** WorkflowSnapshot 恢复全部六个组件，时间文本随后通过重编码检查规范形式。 */
    private static WorkflowSnapshot readSnapshot(Value value) {
        value.requireFields("executionRef", "target", "state", "revision", "resultRef", "updatedAt");
        Value resultRef = value.field("resultRef");
        return new WorkflowSnapshot(
                readExecutionRef(value.field("executionRef")),
                readTarget(value.field("target")),
                WorkflowExecutionState.valueOf(value.field("state").stringValue()),
                value.field("revision").longValue(),
                resultRef.isNull()
                        ? Optional.empty()
                        : Optional.of(new ResultRef(resultRef.stringValue())),
                Instant.parse(value.field("updatedAt").stringValue()));
    }

    /** FrozenWorkflowTarget 恢复完整挂载、实例版本、能力与超时，不读取当前 registry。 */
    private static FrozenWorkflowTarget readTarget(Value value) {
        value.requireFields("handle", "instanceId", "instanceRevision", "capability", "timeout", "installationRevision");
        return new FrozenWorkflowTarget(
                new WorkflowHandle(value.field("handle").stringValue()),
                value.field("instanceId").stringValue(),
                value.field("instanceRevision").longValue(),
                readCapability(value.field("capability")),
                Duration.parse(value.field("timeout").stringValue()), value.field("installationRevision").longValue());
    }

    /** CapabilityRef 的类型、稳定身份与版本必须全部精确存在。 */
    private static CapabilityRef readCapability(Value value) {
        value.requireFields("type", "capabilityId", "versionId");
        return new CapabilityRef(
                CapabilityType.valueOf(value.field("type").stringValue()),
                value.field("capabilityId").stringValue(),
                value.field("versionId").stringValue());
    }

    /** root discriminator 作为普通字段加入后交给 CanonicalJson 统一排序。 */
    private static Value body(String type, List<Field> fields) {
        java.util.ArrayList<Field> all = new java.util.ArrayList<>(fields);
        all.add(new Field("outcomeType", CanonicalJson.string(type)));
        return CanonicalJson.object(all);
    }

    /** ExecutionRef 结构与解码严格同形。 */
    private static Value executionRefValue(ExecutionRef reference) {
        return CanonicalJson.object(List.of(
                new Field("tenantId", CanonicalJson.string(reference.tenantId().value())),
                new Field("executionId", CanonicalJson.string(reference.executionId()))));
    }

    /** ActionRef 结构只用于 recover Absent 的 barrierProof。 */
    private static Value actionRefValue(ActionRef reference) {
        return CanonicalJson.object(List.of(
                new Field("tenantId", CanonicalJson.string(reference.tenantId().value())),
                new Field("actionId", CanonicalJson.string(reference.actionId()))));
    }

    /** TypedPayload 作为不透明字符串封套嵌入，不把内部 JSON 注入外围 JSON 树。 */
    private static Value payloadValue(TypedPayload payload) {
        return CanonicalJson.object(List.of(
                new Field("schemaId", CanonicalJson.string(payload.schemaId())),
                new Field("canonicalJson", CanonicalJson.string(payload.canonicalJson()))));
    }

    /** PortFailure 结构与 OutcomeEncodingBudget.failure 精确同形。 */
    private static Value failureValue(PortFailure failure) {
        return CanonicalJson.object(List.of(
                new Field("reasonCode", CanonicalJson.string(failure.reasonCode())),
                new Field("retryMode", CanonicalJson.string(failure.retryMode().name())),
                new Field("resultRef", CanonicalJson.string(failure.resultRef().value()))));
    }

    /** barrierProof 完整保存六个字段，嵌套 ActionRef 不退化为单字符串。 */
    private static Value barrierProofValue(InvocationBarrierProof proof) {
        return CanonicalJson.object(List.of(
                new Field("actionRef", actionRefValue(proof.actionRef())),
                new Field("invocationId", CanonicalJson.string(proof.invocationId())),
                new Field("blockedExecuteAttemptId", CanonicalJson.string(proof.blockedExecuteAttemptId())),
                new Field("blockedExecuteRequestFingerprint", CanonicalJson.string(proof.blockedExecuteRequestFingerprint())),
                new Field("reconciliationRef", CanonicalJson.string(proof.reconciliationRef().value())),
                new Field("invocationBarrierRef", CanonicalJson.string(proof.invocationBarrierRef().value()))));
    }

    /** WorkflowSnapshot 结构保存 target、状态、revision、可选结果引用和固定更新时间。 */
    private static Value snapshotValue(WorkflowSnapshot snapshot) {
        return CanonicalJson.object(List.of(
                new Field("executionRef", executionRefValue(snapshot.executionRef())),
                new Field("target", targetValue(snapshot.target())),
                new Field("state", CanonicalJson.string(snapshot.state().name())),
                new Field("revision", CanonicalJson.integer(snapshot.revision())),
                new Field("resultRef", snapshot.resultRef()
                        .map(reference -> CanonicalJson.string(reference.value()))
                        .orElseGet(CanonicalJson::nil)),
                new Field("updatedAt", CanonicalJson.string(snapshot.updatedAt().toString()))));
    }

    /** FrozenWorkflowTarget 的 Duration 使用 JDK 规范字符串，解码后必须逐字节重编码相同。 */
    private static Value targetValue(FrozenWorkflowTarget target) {
        return CanonicalJson.object(List.of(
                new Field("handle", CanonicalJson.string(target.handle().value())),
                new Field("instanceId", CanonicalJson.string(target.instanceId())),
                new Field("instanceRevision", CanonicalJson.integer(target.instanceRevision())),
                new Field("installationRevision", CanonicalJson.integer(target.installationRevision())),
                new Field("capability", capabilityValue(target.capability())),
                new Field("timeout", CanonicalJson.string(target.timeout().toString()))));
    }

    /** CapabilityRef 结构保存公开 record 的三个精确组件。 */
    private static Value capabilityValue(CapabilityRef capability) {
        return CanonicalJson.object(List.of(
                new Field("type", CanonicalJson.string(capability.type().name())),
                new Field("capabilityId", CanonicalJson.string(capability.capabilityId())),
                new Field("versionId", CanonicalJson.string(capability.versionId()))));
    }

    /** OptionalLong 的空与零不同，后者也会由公开 API 构造器拒绝。 */
    private static Value optionalLongValue(OptionalLong value) {
        return value.isPresent() ? CanonicalJson.integer(value.getAsLong()) : CanonicalJson.nil();
    }

    /** invoke subtype 名称只由封闭 API 联合决定。 */
    private static String invokeType(WorkflowResult result) {
        return switch (result) {
            case WorkflowResult.Accepted ignored -> "Accepted";
            case WorkflowResult.Suspended ignored -> "Suspended";
            case WorkflowResult.Completed ignored -> "Completed";
            case WorkflowResult.Failed ignored -> "Failed";
            case WorkflowResult.Terminated ignored -> "Terminated";
            case WorkflowResult.Cancelled ignored -> "Cancelled";
            case WorkflowResult.OutcomeUnknown ignored -> "OutcomeUnknown";
        };
    }

    /** API PortalOutcomeSnapshot 构造器提供第二层 tuple 注册表检查。 */
    private static PortalOutcomeSnapshot snapshot(
            PortalCallKind kind,
            PortalResolution resolution,
            String type,
            String schema,
            Value body) {
        return new PortalOutcomeSnapshot(
                kind,
                resolution,
                type,
                new TypedPayload(schema, CanonicalJson.encode(body)));
    }

    /** 解码先绑定调用方预期 kind 与唯一 schema，再读取任何业务字段。 */
    private static void requireSnapshot(
            PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind, String schema) {
        if (snapshot == null
                || snapshot.callKind() != expectedKind
                || !schema.equals(snapshot.outcome().schemaId())) {
            throw new IllegalArgumentException("workflow outcome tuple mismatch");
        }
    }

    /** dispatch/resume 共用 body schema，但不能互相替代外层调用种类。 */
    private static void requireInvokeKind(PortalCallKind kind) {
        if (kind != PortalCallKind.WORKFLOW_DISPATCH
                && kind != PortalCallKind.WORKFLOW_RESUME) {
            throw new IllegalArgumentException("not a workflow invoke kind");
        }
    }

    /** recover 的 schema 不允许从 invoke 或 cancel 入口读取。 */
    private static void requireRecoverKind(PortalCallKind kind) {
        if (kind != PortalCallKind.WORKFLOW_RECOVER_RESULT) {
            throw new IllegalArgumentException("not a workflow recover kind");
        }
    }

    /** control 的 schema 只登记于 WORKFLOW_CANCEL。 */
    private static void requireControlKind(PortalCallKind kind) {
        if (kind != PortalCallKind.WORKFLOW_CANCEL) {
            throw new IllegalArgumentException("not a workflow control kind");
        }
    }

    /** 空联合实例在进入 switch 前转换为固定本地协议错误。 */
    private static void requireResult(WorkflowResult result) {
        if (result == null) {
            throw new IllegalArgumentException("workflow outcome result is null");
        }
    }

    /** 空 recover 联合实例在进入 switch 前转换为固定本地协议错误。 */
    private static void requireResult(WorkflowRecoveredResult result) {
        if (result == null) {
            throw new IllegalArgumentException("workflow recover result is null");
        }
    }

    /** 空 control 联合实例在进入 switch 前转换为固定本地协议错误。 */
    private static void requireResult(WorkflowControlResult result) {
        if (result == null) {
            throw new IllegalArgumentException("workflow control result is null");
        }
    }

    /** OptionalLong 只把真实 JSON null 解释为空；字符串仍按规范十进制精确读取。 */
    private static OptionalLong readOptionalLong(Value value) {
        return value.isNull() ? OptionalLong.empty() : OptionalLong.of(value.longValue());
    }

    /** invoke/recover 的唯一业务 payload 是 Completed.output，外部异常实例与 cause 原样传播。 */
    private void validateOutput(WorkflowResult result) {
        if (result instanceof WorkflowResult.Completed completed) {
            outputs.validate(completed.output(), limits);
        }
    }

    /** recover wrapper 只在 Completed 分支消费 output registry。 */
    private void validateOutput(WorkflowRecoveredResult result) {
        if (result instanceof WorkflowRecoveredResult.Completed completed) {
            outputs.validate(completed.result().output(), limits);
        }
    }

    /** invoke 的所有本地结构错误统一脱敏，不保留可能含载荷的 cause。 */
    private static IllegalArgumentException invalidInvoke() {
        return new IllegalArgumentException("invalid workflow invoke outcome");
    }

    /** recover 的所有本地结构错误统一脱敏，不保留可能含载荷的 cause。 */
    private static IllegalArgumentException invalidRecover() {
        return new IllegalArgumentException("invalid workflow recover outcome");
    }

    /** control 的所有本地结构和时间解析错误统一脱敏。 */
    private static IllegalArgumentException invalidControl() {
        return new IllegalArgumentException("invalid workflow control outcome");
    }
}
