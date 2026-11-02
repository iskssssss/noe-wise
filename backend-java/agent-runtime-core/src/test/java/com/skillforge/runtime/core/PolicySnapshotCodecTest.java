package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.core.FrozenPolicySnapshot.Decision;
import com.skillforge.runtime.core.FrozenPolicySnapshot.Outcome;
import com.skillforge.runtime.core.FrozenPolicySnapshot.Stage;
import com.skillforge.runtime.core.FrozenPolicySnapshot.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 冻结策略链的值域、顺序和末项约束矩阵；不宣称策略已提交或 transform 已真实应用。 */
class PolicySnapshotCodecTest {
    private static final CodecLimits LIMITS = new CodecLimits(10000, 1000, 20);
    static final String OPERATION = """
            {"iteration":"1","loopId":"loop","runIdentity":{"agentId":"agent","causationIdentityRef":"verified","causationInputId":"input","originRef":{"originId":"input","tenantId":"tenant"},"owner":{"sessionRef":{"sessionId":"session","tenantId":"tenant"},"subject":{"endUserId":"9007199254740993","kind":"USER"},"target":{"id":"agent","kind":"AGENT"}},"requester":{"endUserId":"9007199254740993","kind":"END_USER"},"runRef":{"runId":"run","tenantId":"tenant"},"sourceMessageRef":null,"trace":{"causedByEventId":null,"rootInputId":"input","spanId":"span","traceId":"trace"}}}
            """.stripTrailing();

    /** 手写字段与排序，分别覆盖 context 无调用引用和 model 必填调用引用。 */
    @Test
    void matchesIndependentPolicyGoldenBytesForBothStages() {
        for (Stage stage : Stage.values()) {
            var snapshot = policy(stage, List.of(decision("p", stage, Outcome.ALLOW)), Verdict.ALLOW);
            String expected = "{\"contextSnapshotRef\":\"context-ref\",\"decisions\":[{\"auditEventId\":\"audit-p\",\"outcome\":\"ALLOW\","
                    + "\"policyId\":\"p\",\"reasonCode\":\"supported\",\"stage\":\"" + stage.name() + "\"}],"
                    + (stage == Stage.MODEL ? "\"invocationSnapshotRef\":\"invocation-ref\"," : "")
                    + "\"operation\":" + OPERATION + ",\"verdict\":\"ALLOW\"}";
            var encoded = PolicySnapshotCodec.encode(snapshot);
            assertEquals(expected, encoded.canonicalJson());
            assertEquals(stage == Stage.MODEL ? PolicySnapshotCodec.MODEL_SCHEMA : PolicySnapshotCodec.CONTEXT_SCHEMA, encoded.schemaId());
            assertEquals(snapshot, PolicySnapshotCodec.decode(new TypedPayload(encoded.schemaId(), expected), LIMITS));
        }
    }

    /** 枚举三个决策的全部组合，拒绝 DENY 复活、transform 终判及与末项不符的 verdict。 */
    @Test
    void exhaustivelyChecksDenyMonotonicityAndFinalVerdict() {
        for (Stage stage : Stage.values()) {
            for (Outcome first : Outcome.values()) {
                for (Outcome middle : Outcome.values()) {
                    for (Outcome last : Outcome.values()) {
                        for (Verdict verdict : Verdict.values()) {
                            var decisions = List.of(decision("p1", stage, first), decision("p2", stage, middle), decision("p3", stage, last));
                            boolean valid = verdict == Verdict.DENY ? last == Outcome.DENY
                                    : last == Outcome.ALLOW && first != Outcome.DENY && middle != Outcome.DENY;
                            if (valid) {
                                var snapshot = policy(stage, decisions, verdict);
                                assertEquals(snapshot, PolicySnapshotCodec.decode(PolicySnapshotCodec.encode(snapshot), LIMITS));
                            } else {
                                assertThrows(IllegalArgumentException.class, () -> policy(stage, decisions, verdict));
                            }
                        }
                    }
                }
            }
        }
    }

    /** 每个阶段必须非空且身份唯一；相同 policyId 的不同决策不能后写覆盖。 */
    @Test
    void rejectsStageIdentityAndPresenceDrift() {
        var context = policy(Stage.CONTEXT, List.of(decision("p", Stage.CONTEXT, Outcome.ALLOW)), Verdict.ALLOW);
        assertThrows(IllegalArgumentException.class, () -> policy(Stage.CONTEXT, List.of(), Verdict.ALLOW));
        assertThrows(IllegalArgumentException.class, () -> policy(Stage.CONTEXT, List.of(decision("p", Stage.MODEL, Outcome.ALLOW)), Verdict.ALLOW));
        assertThrows(IllegalArgumentException.class, () -> policy(Stage.CONTEXT, List.of(decision("p", Stage.CONTEXT, Outcome.REDACT), decision("p", Stage.CONTEXT, Outcome.ALLOW)), Verdict.ALLOW));
        assertThrows(IllegalArgumentException.class, () -> new FrozenPolicySnapshot(context.operation(), Stage.CONTEXT, context.contextSnapshotRef(), Optional.of(new ResultRef("invocation-ref")), context.decisions(), Verdict.ALLOW));
        assertThrows(IllegalArgumentException.class, () -> new FrozenPolicySnapshot(context.operation(), Stage.MODEL, context.contextSnapshotRef(), Optional.empty(), List.of(decision("p", Stage.MODEL, Outcome.ALLOW)), Verdict.ALLOW));
    }

    /** JSON 字段闭包不接受未知版本、额外字段、缺失审计或非法枚举，且错误不泄露原值。 */
    @Test
    void rejectsMalformedPolicyPayloadAndRedactsFailures() {
        var payload = PolicySnapshotCodec.encode(policy(Stage.CONTEXT, List.of(decision("p", Stage.CONTEXT, Outcome.ALLOW)), Verdict.ALLOW));
        for (String changed : List.of(payload.canonicalJson().replace("\"stage\":\"CONTEXT\"", "\"stage\":\"MODEL\""),
                payload.canonicalJson().replace("\"outcome\":\"ALLOW\"", "\"outcome\":\"secret-invalid\""),
                payload.canonicalJson().replace("\"reasonCode\":\"supported\",", ""),
                payload.canonicalJson().replace("\"policyId\":\"p\"", "\"policyId\":\"p\",\"private\":null"),
                payload.canonicalJson().replace("\"auditEventId\":\"audit-p\"", "\"auditEventId\":null"))) {
            var error = assertThrows(IllegalArgumentException.class, () -> PolicySnapshotCodec.decode(new TypedPayload(payload.schemaId(), changed), LIMITS));
            assertEquals("invalid policy snapshot", error.getMessage());
            assertEquals(null, error.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> PolicySnapshotCodec.decode(new TypedPayload("unknown.v1", payload.canonicalJson()), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> PolicySnapshotCodec.decode(payload, new CodecLimits(1, 1000, 20)));
    }

    /** 冻结不能受原集合修改影响，也不能通过嵌套 toString 释放内部原因内容。 */
    @Test
    void freezesDecisionOrderAndRedactsNestedOutput() {
        var decisions = new ArrayList<>(List.of(decision("secret-p", Stage.CONTEXT, Outcome.REDACT), decision("p", Stage.CONTEXT, Outcome.ALLOW)));
        var snapshot = policy(Stage.CONTEXT, decisions, Verdict.ALLOW);
        decisions.clear();
        assertEquals(2, snapshot.decisions().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.decisions().clear());
        assertFalse(snapshot.decisions().toString().contains("secret-p"));
        assertEquals("FrozenPolicySnapshot[REDACTED]", snapshot.toString());
    }

    /** 使用显式假引用测试结构 codec；真实摘要绑定由准备包测试负责。 */
    private static FrozenPolicySnapshot policy(Stage stage, List<Decision> decisions, Verdict verdict) {
        return new FrozenPolicySnapshot(ContextSnapshotCodecTest.fixture().operation(), stage, new ResultRef("context-ref"),
                stage == Stage.MODEL ? Optional.of(new ResultRef("invocation-ref")) : Optional.empty(), decisions, verdict);
    }

    /** 每个测试决策有可区分的审计身份，不能隐式复用最终决策作为整条链。 */
    private static Decision decision(String id, Stage stage, Outcome outcome) {
        return new Decision(id, stage, outcome, "supported", new EventId("audit-" + id));
    }
}
