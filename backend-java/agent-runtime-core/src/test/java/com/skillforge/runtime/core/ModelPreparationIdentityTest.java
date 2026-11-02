package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 将模型准备 identity 与独立规范字节/hash、全部身份隔离分量逐项比对。 */
class ModelPreparationIdentityTest {

    private static final String EXPECTED_KEY = "422380cc912111fd9f10e02d43a94980baeef0b8a7c3c5ba3f233839540c7462";

    /** Golden JSON 手写；预期 SHA-256 由独立 Python 对 schema+NUL+这些字节计算。 */
    @Test
    void matchesIndependentGoldenOperationAndDerivedIdentities() {
        var identity = identity("unchanged");
        String expected = """
                {"iteration":"1","loopId":"loop","runIdentity":{"agentId":"agent","causationIdentityRef":"verified","causationInputId":"input","originRef":{"originId":"input","tenantId":"tenant"},"owner":{"sessionRef":{"sessionId":"session","tenantId":"tenant"},"subject":{"endUserId":"9007199254740993","kind":"USER"},"target":{"id":"agent","kind":"TEAM"}},"requester":{"endUserId":"9007199254740993","kind":"END_USER"},"runRef":{"runId":"run","tenantId":"tenant"},"sourceMessageRef":null,"trace":{"causedByEventId":null,"rootInputId":"root","spanId":"span","traceId":"trace"}}}
                """.stripTrailing();
        assertEquals(expected, CanonicalJson.encode(identity.canonicalValue()));
        assertEquals(EXPECTED_KEY, identity.key());
        assertEquals(identity, ModelPreparationIdentity.decode(CanonicalJson.parse(expected, new CodecLimits(2000, 100, 8))));
        assertEquals("model-action:" + EXPECTED_KEY, identity.actionRef().actionId());
        assertEquals(new TenantId("tenant"), identity.actionRef().tenantId());
        assertEquals("model-action:" + EXPECTED_KEY, identity.idempotencyKey().value());
        assertEquals("model-invocation:" + EXPECTED_KEY, identity.invocationId());
        assertEquals("model-context-policy:" + EXPECTED_KEY, identity.contextPolicyEventId().value());
        assertEquals("model-context-snapshot:" + EXPECTED_KEY, identity.contextSnapshotEventId().value());
        assertEquals("model-model-policy:" + EXPECTED_KEY, identity.modelPolicyEventId().value());
        assertEquals(identity, identity("unchanged"));
    }

    /** tenant/user/session target/实际 Agent/run/input/trace/causation/Loop 不能任一漏出稳定身份。 */
    @Test
    void bindsEveryIdentityDimensionIncludingOptionalCausation() {
        for (String dimension : List.of("tenant", "session", "target", "target-kind", "agent", "user", "run", "input", "verified",
                "trace", "span", "root", "cause", "loop", "iteration", "requester", "source", "origin")) {
            assertNotEquals(EXPECTED_KEY, identity(dimension).key(), dimension);
        }
    }

    /** 无效 Loop 和 malformed Unicode 必须在建立候选时拒绝，而不是在外调之后暴露。 */
    @Test
    void rejectsIncompleteOrUnencodableIdentity() {
        RunIdentity run = identity("unchanged").runIdentity();
        assertThrows(NullPointerException.class, () -> new ModelPreparationIdentity(null, "loop", 1));
        assertThrows(IllegalArgumentException.class, () -> new ModelPreparationIdentity(run, " ", 1));
        assertThrows(IllegalArgumentException.class, () -> new ModelPreparationIdentity(run, "loop", 0));
        assertThrows(IllegalArgumentException.class, () -> new ModelPreparationIdentity(run, "loop", -1));
        assertThrows(IllegalArgumentException.class, () -> new ModelPreparationIdentity(run, "\ud800", 1));
        assertEquals(Integer.MAX_VALUE, new ModelPreparationIdentity(run, "loop", Integer.MAX_VALUE).iteration());
    }

    /** decoder 不接受缺失/额外字段、跨租户 Run 或整数窄化溢出。 */
    @Test
    void rejectsIdentitySchemaAndOwnershipDrift() {
        String golden = CanonicalJson.encode(identity("unchanged").canonicalValue());
        for (String changed : List.of(golden.replace("\"iteration\":\"1\"", "\"iteration\":\"2147483648\""),
                golden.replace("\"iteration\":\"1\",", ""), golden.replace("\"loopId\":\"loop\"", "\"loopId\":null"),
                golden.replace("\"runId\":\"run\",\"tenantId\":\"tenant\"", "\"runId\":\"run\",\"tenantId\":\"other\""),
                golden.replace("\"owner\":", "\"newField\":null,\"owner\":"))) {
            assertThrows(IllegalArgumentException.class, () -> ModelPreparationIdentity.decode(
                    CanonicalJson.parse(changed, new CodecLimits(2000, 100, 8))));
        }
    }

    /** 群请求者和原消息在准备、结果与恢复中完整往返，不把群读成个人。 */
    @Test void groupSystemRequesterSourceAndOriginRoundTripWithExactPreflightCost() {
        var tenant = new TenantId("tenant");
        var binding = new com.skillforge.runtime.api.GroupBindingRef(tenant, "group");
        var owner = new SessionOwner(new SessionRef(tenant, "child-session"), com.skillforge.runtime.api.SessionSubject.group(binding), SessionTarget.group(binding));
        var run = new RunIdentity(owner, new AgentId("actual-agent"), new InputId("input"), new RunRef(tenant, "run"),
                new TraceEnvelope("trace", "span", new InputId("root"), Optional.empty()), new ResultRef("verified"),
                new com.skillforge.runtime.api.RunRequester.System(new com.skillforge.runtime.api.PrincipalRef.SystemPrincipal(tenant, "system")),
                Optional.of(new com.skillforge.runtime.api.SourceMessageRef(new SessionRef(tenant, "public-group-session"), "message")),
                new com.skillforge.runtime.api.RunOriginRef(tenant, "trigger"));
        var operation = new ModelPreparationIdentity(run, "loop", 1);
        String wire = CanonicalJson.encode(operation.canonicalValue());
        assertEquals(operation, ModelPreparationIdentity.decode(CanonicalJson.parse(wire, new CodecLimits(10_000, 1_000, 16))));
        new OutcomeEncodingBudget(new CodecLimits(wire.length(), 1_000, 16)).operation(1, operation);
        assertThrows(IllegalArgumentException.class, () -> new OutcomeEncodingBudget(new CodecLimits(wire.length()-1, 1_000, 16)).operation(1, operation));
        assertThrows(IllegalArgumentException.class, () -> ModelPreparationIdentity.decode(CanonicalJson.parse(
                wire.replace("\"kind\":\"SYSTEM\"", "\"kind\":\"UNKNOWN\""), new CodecLimits(10_000, 1_000, 16))));
    }

    /** 一次只改变一个分量，以便测试可定位漏扫的实际 identity 字段。 */
    private static ModelPreparationIdentity identity(String changed) {
        TenantId tenant = new TenantId(value("tenant", changed));
        SessionOwner owner = SessionOwner.user(new SessionRef(tenant, value("session", changed)),
                new SessionTarget(changed.equals("target-kind") ? SessionTarget.Kind.AGENT : SessionTarget.Kind.TEAM,
                        changed.equals("target") ? "other-target" : "agent"),
                new EndUserId(changed.equals("user") ? 9007199254740994L : 9007199254740993L));
        TraceEnvelope trace = new TraceEnvelope(value("trace", changed), value("span", changed),
                new InputId(value("root", changed)), changed.equals("cause") ? Optional.of(new EventId("cause")) : Optional.empty());
        RunIdentity run = new RunIdentity(owner, new AgentId(value("agent", changed)), new InputId(value("input", changed)), new RunRef(tenant, value("run", changed)), trace, new ResultRef(value("verified", changed)), changed.equals("requester")
                        ? new com.skillforge.runtime.api.RunRequester.System(new com.skillforge.runtime.api.PrincipalRef.SystemPrincipal(tenant, "background"))
                        : new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()),
                changed.equals("source") ? Optional.of(new com.skillforge.runtime.api.SourceMessageRef(owner.sessionRef(), "source-message")) : Optional.empty(),
                new com.skillforge.runtime.api.RunOriginRef(tenant, changed.equals("origin") ? "other-origin" : value("input", changed)));
        return new ModelPreparationIdentity(run, value("loop", changed), changed.equals("iteration") ? 2 : 1);
    }

    /** 为目标维度选择不同的合法测试值，不复用被测 encoder 生成预期。 */
    private static String value(String name, String changed) {
        return name.equals(changed) ? name + "-other" : name;
    }
}
