package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.core.CanonicalJson.Field;
import com.skillforge.runtime.core.CanonicalJson.Value;
import com.skillforge.runtime.core.FrozenPolicySnapshot.Decision;
import com.skillforge.runtime.core.FrozenPolicySnapshot.Outcome;
import com.skillforge.runtime.core.FrozenPolicySnapshot.Stage;
import com.skillforge.runtime.core.FrozenPolicySnapshot.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** context/model 两种注册策略 schema 的严格 codec；策略事件不包含上下文正文。 */
final class PolicySnapshotCodec {
    static final String CONTEXT_SCHEMA = "skillforge.runtime.agent.context-policy.v1";
    static final String MODEL_SCHEMA = "skillforge.runtime.agent.model-policy.v1";

    /** 无可变配置，不隐式读取当前策略或系统默认值。 */
    private PolicySnapshotCodec() { }

    /** 按阶段选择唯一 schema；model 专有引用不以 context 的 null 字段伪装存在。 */
    static TypedPayload encode(FrozenPolicySnapshot snapshot) {
        var fields = new ArrayList<Field>(List.of(
                new Field("operation", snapshot.operation().canonicalValue()),
                new Field("contextSnapshotRef", CanonicalJson.string(snapshot.contextSnapshotRef().value())),
                new Field("decisions", CanonicalJson.array(snapshot.decisions().stream().map(PolicySnapshotCodec::decision).toList())),
                new Field("verdict", CanonicalJson.string(snapshot.verdict().name()))));
        if (snapshot.stage() == Stage.MODEL) {
            fields.add(new Field("invocationSnapshotRef", CanonicalJson.string(snapshot.invocationSnapshotRef().orElseThrow().value())));
        }
        return new TypedPayload(snapshot.stage() == Stage.CONTEXT ? CONTEXT_SCHEMA : MODEL_SCHEMA,
                CanonicalJson.encode(CanonicalJson.object(fields)));
    }

    /** schema 是阶段权威，载荷内的任何不同阶段或末项漂移均拒绝；不回退未知版本。 */
    static FrozenPolicySnapshot decode(TypedPayload payload, CodecLimits limits) {
        try {
            Stage stage = switch (payload.schemaId()) {
                case CONTEXT_SCHEMA -> Stage.CONTEXT;
                case MODEL_SCHEMA -> Stage.MODEL;
                default -> throw new IllegalArgumentException("unknown policy schema");
            };
            var root = CanonicalJson.parse(payload.canonicalJson(), limits);
            if (stage == Stage.CONTEXT) {
                root.requireFields("operation", "contextSnapshotRef", "decisions", "verdict");
            } else {
                root.requireFields("operation", "contextSnapshotRef", "invocationSnapshotRef", "decisions", "verdict");
            }
            var snapshot = new FrozenPolicySnapshot(ModelPreparationIdentity.decode(root.field("operation")), stage,
                    new ResultRef(root.field("contextSnapshotRef").stringValue()),
                    stage == Stage.CONTEXT ? Optional.empty() : Optional.of(new ResultRef(root.field("invocationSnapshotRef").stringValue())),
                    root.field("decisions").arrayValue().stream().map(PolicySnapshotCodec::decodeDecision).toList(),
                    Verdict.valueOf(root.field("verdict").stringValue()));
            if (!payload.equals(encode(snapshot))) {
                throw new IllegalArgumentException("noncanonical policy snapshot");
            }
            return snapshot;
        } catch (IllegalArgumentException exception) {
            // 拒绝消息只描述协议错误，不能沿 JDK valueOf 异常链泄露原始输入。
            throw new IllegalArgumentException("invalid policy snapshot");
        }
    }

    /** 只投影注册决策字段，保持审计顺序而非按 policyId 排序。 */
    private static Value decision(Decision decision) {
        return CanonicalJson.object(List.of(new Field("policyId", CanonicalJson.string(decision.policyId())),
                new Field("stage", CanonicalJson.string(decision.stage().name())),
                new Field("outcome", CanonicalJson.string(decision.outcome().name())),
                new Field("reasonCode", CanonicalJson.string(decision.reasonCode())),
                new Field("auditEventId", CanonicalJson.string(decision.auditEventId().value()))));
    }

    /** 单个决策没有可省略字段，transform、终判与审计身份均必须显式存在。 */
    private static Decision decodeDecision(Value value) {
        value.requireFields("policyId", "stage", "outcome", "reasonCode", "auditEventId");
        return new Decision(value.field("policyId").stringValue(), Stage.valueOf(value.field("stage").stringValue()),
                Outcome.valueOf(value.field("outcome").stringValue()), value.field("reasonCode").stringValue(),
                new EventId(value.field("auditEventId").stringValue()));
    }
}
