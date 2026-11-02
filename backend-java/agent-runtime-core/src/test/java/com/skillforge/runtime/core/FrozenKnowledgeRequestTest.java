package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeHandle;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 当前执行请求的独立 wire、冻结范围和结果来源约束，不构造旧准备或 permit。 */
class FrozenKnowledgeRequestTest {
    private static final Instant NOW = Instant.parse("2026-10-22T01:02:03.123456789Z");
    private static final CodecLimits LIMITS = new CodecLimits(20_000, 2_000, 16);
    private static final KnowledgeBudget BUDGET = new KnowledgeBudget(1, 2, 8, 100, 5_000);
    private static final Set<KnowledgeSourceVersion> SOURCES = Set.of(
            new KnowledgeSourceVersion("z", "v2"), new KnowledgeSourceVersion("a", "v1"));
    // 手写 wire 固定整数精度、纳秒、Unicode、集合规范顺序和完整字段，不用生产 encoder 生成预期。
    private static final String GOLDEN = """
            {"request":{"agentId":"agent","allowedOperators":["KEYWORD","VECTOR"],"allowedSourceIds":["a","z"],"allowedSourceVersions":[{"sourceId":"a","sourceVersion":"v1"},{"sourceId":"z","sourceVersion":"v2"}],"asOf":"2026-10-22T01:02:03.123456789Z","budget":{"maxCharacters":"100","maxItems":"2","maxQueries":"1","maxTokens":"8","timeoutMs":"5000"},"citationRequired":true,"freshnessAfter":"2026-10-22T01:02:02.123456789Z","intent":"answer","knowledgeRequestId":"request","mode":"GROUNDING","operation":{"query":"知识😀","type":"search"},"requester":{"endUserId":"9007199254740993","kind":"END_USER"},"sessionRef":{"sessionId":"session","tenantId":"tenant"},"tenantId":"tenant","trace":{"causedByEventId":"cause","rootInputId":"input","spanId":"span","traceId":"trace"}},"target":{"allowedOperators":["KEYWORD","VECTOR"],"capability":{"capabilityId":"knowledge","type":"KNOWLEDGE_SOURCE","versionId":"v1"},"descriptorId":"descriptor","descriptorRevision":"9007199254740993","handle":"knowledge:lookup","sourceVersions":[{"sourceId":"a","sourceVersion":"v1"},{"sourceId":"z","sourceVersion":"v2"}],"timeout":"PT5S"}}
            """.stripTrailing();

    @Test void independentWireRetainsCompleteFrozenRequestAndTarget() {
        var value = new FrozenKnowledgeRequest(target(), request(KnowledgeMode.GROUNDING,
                Set.of("z", "a"), SOURCES, Set.of(KnowledgeOperatorType.VECTOR, KnowledgeOperatorType.KEYWORD), BUDGET));
        var payload = new TypedPayload(FrozenKnowledgeRequest.SCHEMA, GOLDEN);
        assertEquals(payload, value.encode(LIMITS));
        assertEquals(value, FrozenKnowledgeRequest.decode(payload, LIMITS));
        assertEquals("FrozenKnowledgeRequest[REDACTED]", value.toString());
        assertFalse(value.toString().contains("知识"));
    }

    @Test void wireRejectsUnknownFieldsAliasesAndExactResourceOverflow() {
        var payload = new TypedPayload(FrozenKnowledgeRequest.SCHEMA, GOLDEN);
        var value = FrozenKnowledgeRequest.decode(payload, LIMITS);
        var exact = new CodecLimits(GOLDEN.length(), 2_000, 16);
        assertEquals(payload, value.encode(exact));
        assertEquals(value, FrozenKnowledgeRequest.decode(payload, exact));
        var tooSmall = new CodecLimits(GOLDEN.length() - 1, 2_000, 16);
        assertThrows(IllegalArgumentException.class, () -> value.encode(tooSmall));
        assertThrows(IllegalArgumentException.class, () -> FrozenKnowledgeRequest.decode(payload, tooSmall));
        assertThrows(IllegalArgumentException.class, () -> value.encode(new CodecLimits(20_000, 2_000, 2)));
        assertThrows(IllegalArgumentException.class, () -> FrozenKnowledgeRequest.decode(
                new TypedPayload("application/json", GOLDEN), LIMITS));
        for (String changed : List.of(
                GOLDEN.replace("\"query\":\"知识😀\"", "\"query\":\"知识😀\",\"unexpected\":true"),
                GOLDEN.replace("[\"a\",\"z\"]", "[\"z\",\"a\"]"),
                GOLDEN.replace("[\"a\",\"z\"]", "[\"a\",\"a\"]"),
                GOLDEN.replace("\"descriptorRevision\":\"9007199254740993\"", "\"descriptorRevision\":\"09007199254740993\""))) {
            assertThrows(IllegalArgumentException.class, () -> FrozenKnowledgeRequest.decode(
                    new TypedPayload(FrozenKnowledgeRequest.SCHEMA, changed), LIMITS));
        }
    }

    @Test void requestMayOnlyNarrowFrozenSourcesVersionsOperatorsAndTime() {
        assertDoesNotThrow(() -> new FrozenKnowledgeRequest(target(), request(KnowledgeMode.GROUNDING,
                Set.of("a"), Set.of(new KnowledgeSourceVersion("a", "v1")), Set.of(KnowledgeOperatorType.VECTOR), BUDGET)));
        for (var request : List.of(
                request(KnowledgeMode.NONE, Set.of("a"), Set.of(new KnowledgeSourceVersion("a", "v1")), Set.of(KnowledgeOperatorType.VECTOR), BUDGET),
                request(KnowledgeMode.GROUNDING, Set.of("other"), Set.of(new KnowledgeSourceVersion("other", "v1")), Set.of(KnowledgeOperatorType.VECTOR), BUDGET),
                request(KnowledgeMode.GROUNDING, Set.of("a"), Set.of(new KnowledgeSourceVersion("a", "v9")), Set.of(KnowledgeOperatorType.VECTOR), BUDGET),
                request(KnowledgeMode.GROUNDING, Set.of("a"), Set.of(new KnowledgeSourceVersion("a", "v1")), Set.of(KnowledgeOperatorType.GRAPH), BUDGET),
                request(KnowledgeMode.GROUNDING, Set.of("a"), Set.of(new KnowledgeSourceVersion("a", "v1")), Set.of(KnowledgeOperatorType.VECTOR), new KnowledgeBudget(1, 2, 8, 100, 5_001)))) {
            assertThrows(IllegalArgumentException.class, () -> new FrozenKnowledgeRequest(target(), request));
        }
    }

    @Test void emptyRetrievalRemainsValidButForeignPlanAndActualUsageOverflowAreRejected() {
        var value = new FrozenKnowledgeRequest(target(), request(KnowledgeMode.GROUNDING,
                Set.of("a"), Set.of(new KnowledgeSourceVersion("a", "v1")), Set.of(KnowledgeOperatorType.VECTOR), BUDGET));
        assertDoesNotThrow(() -> value.requireResult(bundle("request", "a", 8, 5_000)));
        assertThrows(IllegalArgumentException.class, () -> value.requireResult(bundle("other-request", "a", 8, 5_000)));
        assertThrows(IllegalArgumentException.class, () -> value.requireResult(bundle("request", "other-source", 8, 5_000)));
        assertThrows(IllegalArgumentException.class, () -> value.requireResult(bundle("request", "a", 9, 5_000)));
        assertThrows(IllegalArgumentException.class, () -> value.requireResult(bundle("request", "a", 8, 5_001)));
    }

    @Test void systemKnowledgeRequesterRoundTripsWithoutAnEndUser() {
        var personal = request(KnowledgeMode.GROUNDING, Set.of("a"), Set.of(new KnowledgeSourceVersion("a", "v1")), Set.of(KnowledgeOperatorType.VECTOR), BUDGET);
        var system = new com.skillforge.runtime.api.RunRequester.System(new com.skillforge.runtime.api.PrincipalRef.SystemPrincipal(personal.tenantId(), "background"));
        var request = new KnowledgeRequest(personal.knowledgeRequestId(), personal.tenantId(), system, personal.agentId(), personal.sessionRef(), personal.trace(),
                personal.mode(), personal.intent(), personal.operation(), personal.allowedSourceIds(), personal.allowedSourceVersions(), personal.allowedOperators(),
                personal.budget(), personal.asOf(), personal.freshnessAfter(), personal.citationRequired());
        var frozen = new FrozenKnowledgeRequest(target(), request);
        assertEquals(frozen, FrozenKnowledgeRequest.decode(frozen.encode(LIMITS), LIMITS));
        assertFalse(frozen.encode(LIMITS).canonicalJson().contains("endUserId"));
    }

    private static FrozenKnowledgeTarget target() {
        return new FrozenKnowledgeTarget(new KnowledgeHandle("knowledge:lookup"),
                new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "knowledge", "v1"), "descriptor", 9_007_199_254_740_993L,
                SOURCES, Set.of(KnowledgeOperatorType.VECTOR, KnowledgeOperatorType.KEYWORD), Duration.ofSeconds(5));
    }

    private static KnowledgeRequest request(KnowledgeMode mode, Set<String> sourceIds, Set<KnowledgeSourceVersion> versions,
            Set<KnowledgeOperatorType> operators, KnowledgeBudget budget) {
        var tenant = new TenantId("tenant");
        return new KnowledgeRequest("request", tenant, new com.skillforge.runtime.api.RunRequester.EndUser(new EndUserId(9_007_199_254_740_993L)), new AgentId("agent"), new SessionRef(tenant, "session"), new TraceEnvelope("trace", "span", new InputId("input"), Optional.of(new EventId("cause"))), mode, "answer", new com.skillforge.runtime.api.KnowledgeOperation.Search("知识😀"), sourceIds, versions, operators, budget, NOW, Optional.of(NOW.minusSeconds(1)), true);
    }

    private static EvidenceBundle bundle(String request, String source, int tokens, long elapsed) {
        var step = new KnowledgeStep("step", "知识😀", Set.of(source), KnowledgeOperatorType.VECTOR, List.of(), BUDGET);
        var plan = new KnowledgePlan("plan", request, List.of(step), "enough", "planner-v1");
        return new EvidenceBundle("bundle", request, plan, List.of(), List.of(), EvidenceSufficiency.INSUFFICIENT,
                List.of(new KnowledgeTraceStep("step", "retrieve", List.of(), List.of(), tokens, elapsed)), 1, NOW, java.util.Optional.empty());
    }
}
