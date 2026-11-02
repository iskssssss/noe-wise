package com.skillforge.runtime.core;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ResultRef;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 冻结已完成评估的策略链，不将候选 ALLOW 当成已提交事件或外调授权。 */
public record FrozenPolicySnapshot(ModelPreparationIdentity operation, Stage stage, ResultRef contextSnapshotRef,
                            Optional<ResultRef> invocationSnapshotRef, List<Decision> decisions, Verdict verdict) {
    /** 强制阶段、身份、末项判定与 DENY 单调性；transform 的实际应用仍由准备边界核对。 */
    public FrozenPolicySnapshot {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(contextSnapshotRef, "contextSnapshotRef");
        Objects.requireNonNull(invocationSnapshotRef, "invocationSnapshotRef");
        Objects.requireNonNull(verdict, "verdict");
        decisions = List.copyOf(decisions);
        if ((stage == Stage.MODEL) != invocationSnapshotRef.isPresent() || decisions.isEmpty()) {
            throw new IllegalArgumentException("policy stage requires exact references and nonempty decisions");
        }
        var policyIds = new HashSet<String>();
        boolean denied = false;
        for (Decision decision : decisions) {
            if (decision.stage() != stage || !policyIds.add(decision.policyId())) {
                throw new IllegalArgumentException("policy stages must match and policy ids must be unique");
            }
            denied |= decision.outcome() == Outcome.DENY;
        }
        Outcome last = decisions.getLast().outcome();
        if ((verdict == Verdict.ALLOW && (denied || last != Outcome.ALLOW))
                || (verdict == Verdict.DENY && last != Outcome.DENY)) {
            throw new IllegalArgumentException("final policy verdict must preserve deny and match final decision");
        }
    }

    /** 模型准备仅消费两类强制阶段，不接受其他阶段决策冒充 context/model 授权。 */
    public enum Stage { CONTEXT, MODEL }

    /** 中间变换可保留审计记录，但不能成为最终放行判定。 */
    public enum Outcome { ALLOW, DENY, REDACT, DEGRADE }

    /** 最终冻结包只允许明确放行或拒绝，不能持久化半完成策略评估。 */
    public enum Verdict { ALLOW, DENY }

    /** 冻结单个策略的稳定身份、审计引用与非正文原因码。 */
    public record Decision(String policyId, Stage stage, Outcome outcome, String reasonCode, EventId auditEventId) {
        /** 字符串必须有效且非空；事件引用的真实性留给 Session 审计边界。 */
        public Decision {
            requireText(policyId);
            requireText(reasonCode);
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(auditEventId, "auditEventId");
            CanonicalJson.string(auditEventId.value());
        }

        /** 内部身份和原因不通过默认 record 输出进入普通日志。 */
        @Override
        public String toString() {
            return "PolicyDecision[REDACTED]";
        }
    }

    /** 校验协议标签，不修改空白或 Unicode 以制造另一个冻结值。 */
    private static void requireText(String value) {
        Objects.requireNonNull(value, "policy text");
        if (value.isBlank()) {
            throw new IllegalArgumentException("policy text must be nonblank");
        }
        CanonicalJson.string(value);
    }

    /** 策略快照含运行身份与引用，整体输出仅保留类型。 */
    @Override
    public String toString() {
        return "FrozenPolicySnapshot[REDACTED]";
    }
}
