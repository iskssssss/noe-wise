package com.skillforge.runtime.core;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import java.util.Objects;
import java.util.Optional;

/** Loop 已登记动作的类型化恢复快照；只保存安全引用，不复制能力私有状态或授予执行权。 */
public record AgentActionSnapshot(ModelPreparationIdentity operation,
                                  FrozenContextSnapshot.ContextScope contextScope,
                                  Optional<CapabilitySnapshotRef> capabilitySnapshot,
                                  AgentAction action,
                                  Optional<ResultRef> requestJournalRef,
                                  Outcome outcome, int childOrdinal) {
    public AgentActionSnapshot(ModelPreparationIdentity operation, FrozenContextSnapshot.ContextScope contextScope,
            Optional<CapabilitySnapshotRef> capabilitySnapshot, AgentAction action,
            Optional<ResultRef> requestJournalRef, Outcome outcome) {
        this(operation, contextScope, capabilitySnapshot, action, requestJournalRef, outcome, 0);
    }

    /** 构造只证明结构闭合；原决策、journal、事件与当前恢复资格仍由 Session 验证。 */
    public AgentActionSnapshot {
        Objects.requireNonNull(operation, "operation");
        new ToolChildIdentity(operation, childOrdinal);
        if (childOrdinal != 0 && !(action instanceof AgentAction.ToolCall))
            throw new IllegalArgumentException("only_tool_has_child_ordinal");
        Objects.requireNonNull(contextScope, "contextScope");
        capabilitySnapshot = Objects.requireNonNull(capabilitySnapshot, "capabilitySnapshot");
        Objects.requireNonNull(action, "action");
        requestJournalRef = Objects.requireNonNull(requestJournalRef, "requestJournalRef");
        Objects.requireNonNull(outcome, "outcome");

        boolean external = external(action);
        if (contextScope == FrozenContextSnapshot.ContextScope.AGENT_INPUT) {
            var snapshot = capabilitySnapshot.orElseThrow(
                    () -> new IllegalArgumentException("agent action snapshot requires capability snapshot"));
            if (!snapshot.sessionRef().equals(operation.runIdentity().owner().sessionRef())
                    || !snapshot.inputId().equals(operation.runIdentity().causationInputId())) {
                throw new IllegalArgumentException("action snapshot capability scope mismatch");
            }
        } else if (capabilitySnapshot.isPresent()
                || !(action instanceof AgentAction.Respond || action instanceof AgentAction.AskUser)) {
            throw new IllegalArgumentException("restricted action snapshot shape mismatch");
        }
        if (external != requestJournalRef.isPresent()) {
            throw new IllegalArgumentException("action snapshot journal shape mismatch");
        }
        if (outcome instanceof Failed failed && !(action instanceof AgentAction.KnowledgeAccess)
                && failed.resultEventId().isEmpty()) {
            throw new IllegalArgumentException("action snapshot failure event missing");
        }
        requestJournalRef.ifPresent(ref -> CanonicalJson.string(ref.value()));
    }

    /** 只有可能产生外部副作用或委派子执行的动作拥有独立请求 journal。 */
    static boolean external(AgentAction action) {
        return action instanceof AgentAction.ToolCall || action instanceof AgentAction.Dispatch
                || action instanceof AgentAction.KnowledgeAccess || action instanceof AgentAction.Delegate || action instanceof AgentAction.Plan;
    }

    /** 封闭 outcome 保留不同收敛语义，避免 Optional 字段组合成非法状态。 */
    public sealed interface Outcome permits Pending, Accepted, Suspended, Completed, Failed,
            Rejected, Cancelled, OutcomeUnknown {
        /** 确定结果可复用原结果；非终态只能协调原请求。 */
        default boolean resolved() {
            return this instanceof Completed || this instanceof Failed
                    || this instanceof Rejected || this instanceof Cancelled;
        }
    }

    /** 请求已登记但尚无可消费结果。 */
    public record Pending() implements Outcome { }

    /** Provider 已受理；pendingRef 仅在协议真实返回时存在。 */
    public record Accepted(Optional<ResultRef> pendingRef) implements Outcome {
        public Accepted { pendingRef = Objects.requireNonNull(pendingRef, "pendingRef"); }
        @Override public String toString() { return "ActionAccepted[REDACTED]"; }
    }

    /** 能力已提交挂起事实；它不表示 planned Loop 自身进入 SUSPENDED。 */
    public record Suspended(String suspendRef, ResultRef resultRef,
            com.skillforge.runtime.api.LoopSuspendReason reason) implements Outcome {
        public Suspended {
            if (reason != com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_HITL
                    && reason != com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_EXTERNAL)
                throw new IllegalArgumentException("invalid_workflow_suspend_reason");
            suspendRef = text(suspendRef, "suspendRef");
            Objects.requireNonNull(resultRef, "resultRef");
        }
        @Override public String toString() { return "ActionSuspended[REDACTED]"; }
    }

    /** 确定成功必须绑定已提交结果事件。 */
    public record Completed(ResultRef resultRef, EventId resultEventId) implements Outcome {
        public Completed {
            Objects.requireNonNull(resultRef, "resultRef");
            Objects.requireNonNull(resultEventId, "resultEventId");
        }
        @Override public String toString() { return "ActionCompleted[REDACTED]"; }
    }

    /** Knowledge 的确定失败没有 Agent Observation，因此 resultEventId 条件存在。 */
    public record Failed(ResultRef resultRef, Optional<EventId> resultEventId) implements Outcome {
        public Failed {
            Objects.requireNonNull(resultRef, "resultRef");
            resultEventId = Objects.requireNonNull(resultEventId, "resultEventId");
        }
        @Override public String toString() { return "ActionFailed[REDACTED]"; }
    }

    /** 确定拒绝来自已提交安全事件，不创建能力账本结果。 */
    public record Rejected(ResultRef resultRef, EventId resultEventId) implements Outcome {
        public Rejected {
            Objects.requireNonNull(resultRef, "resultRef");
            Objects.requireNonNull(resultEventId, "resultEventId");
        }
        @Override public String toString() { return "ActionRejected[REDACTED]"; }
    }

    /** 已取消结果保持原结果与事件身份，不允许改写为失败或重新登记。 */
    public record Cancelled(ResultRef resultRef, EventId resultEventId) implements Outcome {
        public Cancelled {
            Objects.requireNonNull(resultRef, "resultRef");
            Objects.requireNonNull(resultEventId, "resultEventId");
        }
        @Override public String toString() { return "ActionCancelled[REDACTED]"; }
    }

    /** 结果未知只保存权威事实引用，不能转成重试许可。 */
    public record OutcomeUnknown(ResultRef factRef) implements Outcome {
        public OutcomeUnknown { Objects.requireNonNull(factRef, "factRef"); }
        @Override public String toString() { return "ActionOutcomeUnknown[REDACTED]"; }
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) { throw new IllegalArgumentException(field + " must not be blank"); }
        CanonicalJson.string(value);
        return value;
    }

    /** 快照包含动作正文和归属，普通日志固定脱敏。 */
    @Override public String toString() { return "AgentActionSnapshot[REDACTED]"; }
}
