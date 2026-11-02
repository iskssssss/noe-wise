package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.skillforge.runtime.api.EvidenceBundle;
import com.skillforge.runtime.api.EvidenceItem;
import com.skillforge.runtime.api.EvidenceSufficiency;
import com.skillforge.runtime.api.KnowledgeBudget;
import com.skillforge.runtime.api.KnowledgeOperatorType;
import com.skillforge.runtime.api.KnowledgePlan;
import com.skillforge.runtime.api.KnowledgeStep;
import com.skillforge.runtime.api.KnowledgeTraceStep;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.PortalOutcomeSnapshot;
import com.skillforge.runtime.api.port.PortalResolution;
import com.skillforge.runtime.api.port.RetryMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 独立手写 fixture 固定 Knowledge 私有 wire，不把生产 encoder 或 API 契约测试当作预期生成器。 */
class KnowledgeOutcomeCodecTest {
    private static final String SCHEMA = "skillforge.runtime.portal.knowledge-outcome.v2";
    private static final CodecLimits LIMITS = new CodecLimits(2_000_000, 200_000, 32);
    private static final Instant CREATED_AT = Instant.parse("2026-09-05T01:02:03.123456789Z");
    private static final TypedPayload CONTENT_B =
            new TypedPayload("content.v1", "{\"text\":\"B\"}");
    private static final TypedPayload CONTENT_A =
            new TypedPayload("content.v1", "{\"text\":\"A\"}");

    private static final String SOURCE_IDS_JSON =
            "[\"A\",\"\\\"\",\"\\u0000\",\"é\",\"\",\"𐀀\"]";
    private static final String BUDGET_ONE_JSON =
            "{\"maxCharacters\":\"4\",\"maxItems\":\"2\",\"maxQueries\":\"1\","
                    + "\"maxTokens\":\"3\",\"timeoutMs\":\"9223372036854775807\"}";
    private static final String BUDGET_TWO_JSON =
            "{\"maxCharacters\":\"8\",\"maxItems\":\"6\",\"maxQueries\":\"5\","
                    + "\"maxTokens\":\"7\",\"timeoutMs\":\"9\"}";
    private static final String STEP_TWO_JSON =
            "{\"budget\":" + BUDGET_TWO_JSON
                    + ",\"dependsOn\":[\"step-1\"],\"operator\":\"GRAPH\","
                    + "\"query\":\"second\",\"sourceIds\":[],\"stepId\":\"step-2\"}";
    private static final String STEP_ONE_JSON =
            "{\"budget\":" + BUDGET_ONE_JSON
                    + ",\"dependsOn\":[],\"operator\":\"VECTOR\",\"query\":\"first\","
                    + "\"sourceIds\":" + SOURCE_IDS_JSON + ",\"stepId\":\"step-1\"}";
    private static final String PLAN_JSON =
            "{\"knowledgeRequestId\":\"request\",\"planId\":\"plan\","
                    + "\"plannerVersion\":\"planner-v1\",\"steps\":[" + STEP_TWO_JSON + ","
                    + STEP_ONE_JSON + "],\"stopCondition\":\"enough\"}";
    private static final String CONTENT_B_JSON =
            "{\"canonicalJson\":\"{\\\"text\\\":\\\"B\\\"}\",\"schemaId\":\"content.v1\"}";
    private static final String CONTENT_A_JSON =
            "{\"canonicalJson\":\"{\\\"text\\\":\\\"A\\\"}\",\"schemaId\":\"content.v1\"}";
    private static final String ITEM_B_JSON =
            "{\"content\":" + CONTENT_B_JSON
                    + ",\"graphPath\":[\"g2\",\"g1\"],\"itemId\":\"item-b\","
                    + "\"operator\":\"KEYWORD\",\"provenanceRefs\":[\"p2\",\"p1\"],"
                    + "\"relevance\":\"0.75\",\"sourceId\":\"source-b\","
                    + "\"sourceVersion\":\"v2\",\"trustLevel\":\"verified\"}";
    private static final String ITEM_A_JSON =
            "{\"content\":" + CONTENT_A_JSON
                    + ",\"graphPath\":[],\"itemId\":\"item-a\",\"operator\":\"STRUCTURED\","
                    + "\"provenanceRefs\":[],\"relevance\":\"0\",\"sourceId\":\"source-a\","
                    + "\"sourceVersion\":\"v1\",\"trustLevel\":\"derived\"}";
    private static final String TRACE_TWO_JSON =
            "{\"candidateItemIds\":[\"item-a\",\"item-b\"],\"elapsedMs\":\"0\","
                    + "\"phase\":\"select\",\"selectedItemIds\":[\"item-b\"],"
                    + "\"stepId\":\"step-2\",\"tokenCost\":\"9\"}";
    private static final String TRACE_ONE_JSON =
            "{\"candidateItemIds\":[\"item-b\"],\"elapsedMs\":\"9223372036854775807\","
                    + "\"phase\":\"retrieve\",\"selectedItemIds\":[\"item-b\"],"
                    + "\"stepId\":\"step-1\",\"tokenCost\":\"10\"}";
    private static final String BUNDLE_JSON =
            "{\"bundleId\":\"bundle\",\"conflictItemIds\":[[\"item-b\",\"item-a\"]],"
                    + "\"createdAt\":\"2026-09-05T01:02:03.123456789Z\",\"documentResult\":null,\"items\":["
                    + ITEM_B_JSON + "," + ITEM_A_JSON + "],\"knowledgeRequestId\":\"request\","
                    + "\"plan\":" + PLAN_JSON + ",\"sufficiency\":\"PARTIAL\",\"trace\":["
                    + TRACE_TWO_JSON + "," + TRACE_ONE_JSON
                    + "],\"version\":\"9223372036854775807\"}";
    private static final String RETRIEVED_JSON =
            "{\"bundle\":" + BUNDLE_JSON
                    + ",\"outcomeType\":\"Retrieved\",\"resultRef\":\"knowledge-result\"}";
    private static final String FAILURE_NONE_JSON =
            "{\"reasonCode\":\"declined\",\"resultRef\":\"audit\",\"retryMode\":\"NONE\"}";
    private static final String EMPTY_PLAN_JSON =
            "{\"knowledgeRequestId\":\"request-empty\",\"planId\":\"plan-empty\","
                    + "\"plannerVersion\":\"planner-empty\",\"steps\":[],\"stopCondition\":\"stop\"}";
    private static final String EMPTY_BUNDLE_JSON =
            "{\"bundleId\":\"bundle-empty\",\"conflictItemIds\":[],"
                    + "\"createdAt\":\"2026-09-05T01:02:03Z\",\"documentResult\":null,\"items\":[],"
                    + "\"knowledgeRequestId\":\"request-empty\",\"plan\":" + EMPTY_PLAN_JSON
                    + ",\"sufficiency\":\"INSUFFICIENT\",\"trace\":[],\"version\":\"1\"}";
    private static final String EMPTY_RETRIEVED_JSON =
            "{\"bundle\":" + EMPTY_BUNDLE_JSON
                    + ",\"outcomeType\":\"Retrieved\",\"resultRef\":\"empty-result\"}";

    /** 三个公开 subtype 均从手写 canonical bytes 双向核对完整外层 tuple。 */
    @Test
    void exactGoldenForAllThreeSubtypes() {
        assertGolden(retrieved(), "Retrieved", RETRIEVED_JSON);
        assertGolden(
                new KnowledgeOutcome.Failed(failure(RetryMode.COORDINATED)),
                "Failed",
                "{\"failure\":{\"reasonCode\":\"declined\",\"resultRef\":\"audit\","
                        + "\"retryMode\":\"COORDINATED\"},\"outcomeType\":\"Failed\"}");
        assertGolden(
                new KnowledgeOutcome.OutcomeUnknown(failure(RetryMode.NONE)),
                "OutcomeUnknown",
                "{\"failure\":" + FAILURE_NONE_JSON
                        + ",\"outcomeType\":\"OutcomeUnknown\"}");
    }

    /** List 保持调用顺序，空集合保留显式数组；sourceIds 只按规范字符串 UTF-8 无符号字节序排序。 */
    @Test
    void preservesListsAndSortsSourceSetsByCanonicalUtf8Bytes() {
        PortalOutcomeSnapshot encoded = codec().encode(
                PortalCallKind.KNOWLEDGE_EXECUTE, retrieved());
        assertEquals(RETRIEVED_JSON, encoded.outcome().canonicalJson());

        // BMP U+E000 必须排在补充字符 U+10000 前；转义值在 ASCII A 后且按转义字节比较。
        assertTrue(encoded.outcome().canonicalJson().contains(
                "\"sourceIds\":" + SOURCE_IDS_JSON));
        String utf16Order = "[\"A\",\"\\\"\",\"\\u0000\",\"é\",\"𐀀\",\"\"]";
        assertKnowledgeRejected(manual("Retrieved", RETRIEVED_JSON.replace(
                SOURCE_IDS_JSON, utf16Order)));
        assertKnowledgeRejected(manual("Retrieved", RETRIEVED_JSON.replace(
                SOURCE_IDS_JSON, "[\"A\",\"A\"]")));

        // item、step、dependsOn、conflict、trace 内层列表均不得被按身份或依赖顺序重排。
        EvidenceBundle reversedItems = fullBundle(List.of(itemA(0.0d), itemB()));
        String reversed = codec().encode(
                PortalCallKind.KNOWLEDGE_EXECUTE,
                new KnowledgeOutcome.Retrieved(reversedItems, new ResultRef("knowledge-result")))
                .outcome().canonicalJson();
        assertNotEquals(RETRIEVED_JSON, reversed);
        assertTrue(reversed.indexOf("\"itemId\":\"item-a\"")
                < reversed.indexOf("\"itemId\":\"item-b\""));

        // 空 steps/items/conflicts/trace 仍完整保存 bundle/plan 字段，不省略任何集合。
        KnowledgeOutcome.Retrieved empty = new KnowledgeOutcome.Retrieved(
                emptyBundle(), new ResultRef("empty-result"));
        assertEquals(EMPTY_RETRIEVED_JSON,
                codec().encode(PortalCallKind.KNOWLEDGE_EXECUTE, empty)
                        .outcome().canonicalJson());
        assertEquals(empty, codec().decode(
                manual("Retrieved", EMPTY_RETRIEVED_JSON),
                PortalCallKind.KNOWLEDGE_EXECUTE));
    }

    /** relevance 的正负零、极小值与 1 使用唯一 plain decimal；整数保持 Long.MAX 精度。 */
    @Test
    void canonicalizesRelevanceAndRejectsNumericAliases() {
        // API 负零只在 wire 上规范为 0，解码结果必须是正零。
        KnowledgeOutcome.Retrieved negativeZero = singleItemOutcome(-0.0d);
        PortalOutcomeSnapshot negativeZeroSnapshot = codec().encode(
                PortalCallKind.KNOWLEDGE_EXECUTE, negativeZero);
        assertTrue(negativeZeroSnapshot.outcome().canonicalJson().contains(
                "\"relevance\":\"0\""));
        KnowledgeOutcome.Retrieved decodedNegativeZero = (KnowledgeOutcome.Retrieved) codec().decode(
                negativeZeroSnapshot, PortalCallKind.KNOWLEDGE_EXECUTE);
        assertEquals(Double.doubleToRawLongBits(0.0d), Double.doubleToRawLongBits(
                decodedNegativeZero.bundle().items().getFirst().relevance()));

        // Double.MIN_VALUE 展开为非指数 plain decimal，1 不保留小数别名。
        String minimum = BigDecimal.valueOf(Double.MIN_VALUE)
                .stripTrailingZeros().toPlainString();
        PortalOutcomeSnapshot minimumSnapshot = codec().encode(
                PortalCallKind.KNOWLEDGE_EXECUTE, singleItemOutcome(Double.MIN_VALUE));
        assertTrue(minimumSnapshot.outcome().canonicalJson().contains(
                "\"relevance\":\"" + minimum + "\""));
        assertEquals(Double.MIN_VALUE,
                ((KnowledgeOutcome.Retrieved) codec().decode(
                        minimumSnapshot, PortalCallKind.KNOWLEDGE_EXECUTE))
                        .bundle().items().getFirst().relevance());
        assertTrue(codec().encode(
                PortalCallKind.KNOWLEDGE_EXECUTE, singleItemOutcome(1.0d))
                .outcome().canonicalJson().contains("\"relevance\":\"1\""));

        // wire 别名、越界值、非有限值与转 double 后下溢为零的 plain decimal 都拒绝。
        String underflow = "0." + "0".repeat(399) + "1";
        for (String alias : List.of(
                "-0", "0.0", "1.0", "1e0", "0x1.0p0", "00.5",
                " 0.75", "0.75 ", "0.75000000000000001", underflow,
                "-0.1", "1.1", "NaN", "Infinity")) {
            assertKnowledgeRejected(manual("Retrieved", RETRIEVED_JSON.replace(
                    "\"relevance\":\"0.75\"",
                    "\"relevance\":\"" + alias + "\"")));
        }
        assertKnowledgeRejected(manual("Retrieved", RETRIEVED_JSON.replace(
                "\"version\":\"9223372036854775807\"",
                "\"version\":\"9223372036854775808\"")));
        for (String version : List.of("01", "0", "-1")) {
            assertKnowledgeRejected(manual("Retrieved", RETRIEVED_JSON.replace(
                    "\"version\":\"9223372036854775807\"",
                    "\"version\":\"" + version + "\"")));
        }
        assertKnowledgeRejected(manual("Retrieved", RETRIEVED_JSON.replace(
                "\"maxQueries\":\"1\"", "\"maxQueries\":\"0\"")));
        assertKnowledgeRejected(manual("Retrieved", RETRIEVED_JSON.replace(
                "\"maxQueries\":\"1\"", "\"maxQueries\":\"2147483648\"")));
        assertKnowledgeRejected(manual("Retrieved", RETRIEVED_JSON.replace(
                "\"elapsedMs\":\"0\"", "\"elapsedMs\":\"-1\"")));
        assertKnowledgeRejected(manual("Retrieved", RETRIEVED_JSON.replace(
                "\"timeoutMs\":\"9223372036854775807\"",
                "\"timeoutMs\":9223372036854775807")));
        assertKnowledgeRejected(manual("Retrieved", RETRIEVED_JSON.replace(
                "2026-09-05T01:02:03.123456789Z",
                "2026-09-05T01:02:03.123456789+00:00")));
        assertKnowledgeRejected(manual("Retrieved", RETRIEVED_JSON.replace(
                "2026-09-05T01:02:03.123456789Z", "SECRET")));
    }

    /** bundle/plan/item/conflict/trace/dependsOn 的引用闭包均由真实 API 构造器在解码时重建验证。 */
    @Test
    void rejectsEveryNestedReferenceMismatchBeforeRegistry() {
        List<String> malformed = List.of(
                RETRIEVED_JSON.replace(
                        "\"plan\":{\"knowledgeRequestId\":\"request\"",
                        "\"plan\":{\"knowledgeRequestId\":\"other\""),
                RETRIEVED_JSON.replace(
                        "\"itemId\":\"item-a\"", "\"itemId\":\"item-b\""),
                RETRIEVED_JSON.replace(
                        "\"conflictItemIds\":[[\"item-b\",\"item-a\"]]",
                        "\"conflictItemIds\":[[\"item-b\",\"missing\"]]"),
                RETRIEVED_JSON.replace(
                        "\"stepId\":\"step-2\",\"tokenCost\":\"9\"",
                        "\"stepId\":\"missing\",\"tokenCost\":\"9\""),
                RETRIEVED_JSON.replace(
                        "\"candidateItemIds\":[\"item-a\",\"item-b\"],\"elapsedMs\":\"0\"",
                        "\"candidateItemIds\":[\"missing\"],\"elapsedMs\":\"0\""),
                RETRIEVED_JSON.replace(
                        "\"candidateItemIds\":[\"item-a\",\"item-b\"]",
                        "\"candidateItemIds\":[\"item-a\"]"),
                RETRIEVED_JSON.replace(
                        "\"dependsOn\":[\"step-1\"]", "\"dependsOn\":[\"missing\"]"),
                RETRIEVED_JSON.replace(
                        "\"dependsOn\":[\"step-1\"]", "\"dependsOn\":[\"step-1\",\"step-1\"]"),
                RETRIEVED_JSON.replace(
                        "\"dependsOn\":[],\"operator\":\"VECTOR\"",
                        "\"dependsOn\":[\"step-2\"],\"operator\":\"VECTOR\""));
        for (String body : malformed) {
            assertKnowledgeRejected(manual("Retrieved", body));
        }

        // 后一 item 的结构损坏时，前一合法 content 也不能提前进入 registry。
        AtomicInteger calls = new AtomicInteger();
        KnowledgeOutcomeCodec codec = new KnowledgeOutcomeCodec(
                LIMITS, (payload, limits) -> calls.incrementAndGet());
        PortalOutcomeSnapshot malformedSecondItem = manual(
                "Retrieved",
                RETRIEVED_JSON.replace(
                        "\"schemaId\":\"content.v1\"},\"graphPath\":[],\"itemId\":\"item-a\"",
                        "\"extra\":\"SECRET\",\"schemaId\":\"content.v1\"},"
                                + "\"graphPath\":[],\"itemId\":\"item-a\""));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(malformedSecondItem, PortalCallKind.KNOWLEDGE_EXECUTE));
        assertEquals(0, calls.get());
    }

    /** 扁平 DAG 的长依赖链只增加数组长度，不应消耗 JSON graph-depth 或 Java 递归栈。 */
    @Test
    void roundTripsLongShallowKnowledgePlanWithoutRecursiveGraphDepth() {
        ArrayList<KnowledgeStep> steps = new ArrayList<>();
        for (int index = 0; index < 2_000; index++) {
            String stepId = "step-" + index;
            List<String> dependencies = index == 0
                    ? List.of() : List.of("step-" + (index - 1));
            steps.add(new KnowledgeStep(
                    stepId,
                    "query-" + index,
                    Set.of(),
                    KnowledgeOperatorType.KEYWORD,
                    dependencies,
                    new KnowledgeBudget(1, 1, 1, 1, 1)));
        }
        KnowledgePlan plan = new KnowledgePlan(
                "long-plan", "long-request", steps, "stop", "planner");
        EvidenceBundle bundle = new EvidenceBundle(
                "long-bundle",
                "long-request",
                plan,
                List.of(),
                List.of(),
                EvidenceSufficiency.INSUFFICIENT,
                List.of(),
                1,
                Instant.parse("2026-09-05T01:02:03Z"), java.util.Optional.empty());
        KnowledgeOutcome.Retrieved value =
                new KnowledgeOutcome.Retrieved(bundle, new ResultRef("long-result"));
        KnowledgeOutcomeCodec codec = new KnowledgeOutcomeCodec(
                new CodecLimits(5_000_000, 500_000, 8),
                (payload, limits) -> fail("long fixture has no items"));
        PortalOutcomeSnapshot snapshot = codec.encode(
                PortalCallKind.KNOWLEDGE_EXECUTE, value);
        assertEquals(value, codec.decode(snapshot, PortalCallKind.KNOWLEDGE_EXECUTE));
    }

    /** 手写空 bundle 固定 35 节点/四层深度，长度、节点或深度减一均双向拒绝。 */
    @Test
    void enforcesExactAggregateLengthNodeAndDepthBudgets() {
        // 完整手写 fixture 独立计得 174 节点、七层深度，新增documentResult字段名及null各占一节点。
        KnowledgeOutcome.Retrieved fullValue = retrieved();
        CodecLimits fullLimits = new CodecLimits(RETRIEVED_JSON.length(), 174, 7);
        KnowledgeOutcomeCodec exactFull = new KnowledgeOutcomeCodec(fullLimits, (payload, limits) -> {
            assertTrue(Set.of(CONTENT_A, CONTENT_B).contains(payload));
            assertEquals(fullLimits, limits);
        });
        PortalOutcomeSnapshot fullEncoded = exactFull.encode(
                PortalCallKind.KNOWLEDGE_EXECUTE, fullValue);
        assertEquals(RETRIEVED_JSON, fullEncoded.outcome().canonicalJson());
        assertEquals(fullValue, exactFull.decode(
                fullEncoded, PortalCallKind.KNOWLEDGE_EXECUTE));
        for (CodecLimits bounded : List.of(
                new CodecLimits(RETRIEVED_JSON.length() - 1, 174, 7),
                new CodecLimits(RETRIEVED_JSON.length(), 173, 7),
                new CodecLimits(RETRIEVED_JSON.length(), 174, 6))) {
            KnowledgeOutcomeCodec codec = new KnowledgeOutcomeCodec(
                    bounded, (payload, limits) -> fail("must reject before registry"));
            assertThrows(IllegalArgumentException.class,
                    () -> codec.encode(PortalCallKind.KNOWLEDGE_EXECUTE, fullValue));
            assertThrows(IllegalArgumentException.class,
                    () -> codec.decode(
                            manual("Retrieved", RETRIEVED_JSON),
                            PortalCallKind.KNOWLEDGE_EXECUTE));
        }

        // 空 bundle 另行固定 37 节点/四层深度，包含documentResult:null，防止空集合漏计。
        KnowledgeOutcome.Retrieved value = new KnowledgeOutcome.Retrieved(
                emptyBundle(), new ResultRef("empty-result"));
        CodecLimits exactLimits = new CodecLimits(EMPTY_RETRIEVED_JSON.length(), 37, 4);
        KnowledgeOutcomeCodec exact = new KnowledgeOutcomeCodec(
                exactLimits, (payload, limits) -> fail("empty fixture has no items"));
        PortalOutcomeSnapshot encoded = exact.encode(PortalCallKind.KNOWLEDGE_EXECUTE, value);
        assertEquals(EMPTY_RETRIEVED_JSON, encoded.outcome().canonicalJson());
        assertEquals(value, exact.decode(encoded, PortalCallKind.KNOWLEDGE_EXECUTE));

        for (CodecLimits bounded : List.of(
                new CodecLimits(EMPTY_RETRIEVED_JSON.length() - 1, 37, 4),
                new CodecLimits(EMPTY_RETRIEVED_JSON.length(), 36, 4),
                new CodecLimits(EMPTY_RETRIEVED_JSON.length(), 37, 3))) {
            KnowledgeOutcomeCodec codec = new KnowledgeOutcomeCodec(
                    bounded, (payload, limits) -> fail("must reject before registry"));
            assertThrows(IllegalArgumentException.class,
                    () -> codec.encode(PortalCallKind.KNOWLEDGE_EXECUTE, value));
            assertThrows(IllegalArgumentException.class,
                    () -> codec.decode(
                            manual("Retrieved", EMPTY_RETRIEVED_JSON),
                            PortalCallKind.KNOWLEDGE_EXECUTE));
        }
    }

    /** registry 在完整结构验证后按 items 顺序逐项调用一次；外部异常实例与 cause 原样传播。 */
    @Test
    void validatesEvidenceContentOnceInItemOrderAndPreservesExternalErrors() {
        AtomicInteger calls = new AtomicInteger();
        KnowledgeOutcomeCodec codec = new KnowledgeOutcomeCodec(LIMITS, (payload, limits) -> {
            int call = calls.getAndIncrement();
            assertEquals(call % 2 == 0 ? CONTENT_B : CONTENT_A, payload);
            assertEquals(LIMITS, limits);
        });
        PortalOutcomeSnapshot encoded = codec.encode(
                PortalCallKind.KNOWLEDGE_EXECUTE, retrieved());
        assertEquals(2, calls.get());
        codec.decode(encoded, PortalCallKind.KNOWLEDGE_EXECUTE);
        assertEquals(4, calls.get());
        codec.encode(PortalCallKind.KNOWLEDGE_EXECUTE,
                new KnowledgeOutcome.Failed(failure(RetryMode.RESULT_QUERYABLE)));
        codec.encode(PortalCallKind.KNOWLEDGE_EXECUTE,
                new KnowledgeOutcome.OutcomeUnknown(failure(RetryMode.NONE)));
        assertEquals(4, calls.get());

        for (RuntimeException exception : List.of(
                new IllegalStateException("registry unavailable", new IllegalStateException("cause")),
                new IllegalArgumentException("external content rejection"))) {
            AtomicInteger externalCalls = new AtomicInteger();
            KnowledgeOutcomeCodec broken = new KnowledgeOutcomeCodec(LIMITS, (payload, limits) -> {
                if (externalCalls.incrementAndGet() == 2) {
                    throw exception;
                }
            });
            assertSame(exception, assertThrows(RuntimeException.class,
                    () -> broken.encode(PortalCallKind.KNOWLEDGE_EXECUTE, retrieved())));
            assertEquals(2, externalCalls.get());

            externalCalls.set(0);
            assertSame(exception, assertThrows(RuntimeException.class,
                    () -> broken.decode(encoded, PortalCallKind.KNOWLEDGE_EXECUTE)));
            assertEquals(2, externalCalls.get());
        }
    }

    /** only KNOWLEDGE_EXECUTE、schema、字段与 discriminator 全部精确匹配，错误必须脱敏且无 cause。 */
    @Test
    void rejectsWrongKindSchemaMissingExtraAndDiscriminator() {
        PortalOutcomeSnapshot valid = manual("Retrieved", RETRIEVED_JSON);
        for (PortalCallKind kind : PortalCallKind.values()) {
            if (kind == PortalCallKind.KNOWLEDGE_EXECUTE) {
                continue;
            }
            assertThrows(IllegalArgumentException.class,
                    () -> codec().encode(kind, retrieved()));
            assertKnowledgeRejected(valid, kind);
        }
        assertThrows(IllegalArgumentException.class, () -> new PortalOutcomeSnapshot(
                PortalCallKind.KNOWLEDGE_EXECUTE,
                PortalResolution.KNOWN_RESULT,
                "Retrieved",
                new TypedPayload("skillforge.runtime.portal.knowledge-outcome.v1", RETRIEVED_JSON)));

        for (String body : List.of(
                " " + RETRIEVED_JSON,
                RETRIEVED_JSON + " ",
                RETRIEVED_JSON.replace("{\"bundle\"", "{\"extra\":\"SECRET\",\"bundle\""),
                RETRIEVED_JSON.replace("\"bundleId\":\"bundle\",", ""),
                RETRIEVED_JSON.replace(
                        "\"content\":" + CONTENT_B_JSON,
                        "\"content\":{\"schemaId\":\"content.v1\"}"),
                RETRIEVED_JSON.replace(
                        "\"outcomeType\":\"Retrieved\"",
                        "\"outcomeType\":\"SECRET\""))) {
            assertKnowledgeRejected(manual("Retrieved", body));
        }
        assertKnowledgeRejected(manual(
                "Retrieved",
                "{\"failure\":" + FAILURE_NONE_JSON
                        + ",\"outcomeType\":\"OutcomeUnknown\"}"));
        assertKnowledgeRejected(manual(
                "OutcomeUnknown",
                "{\"failure\":{\"reasonCode\":\"declined\",\"resultRef\":\"audit\","
                        + "\"retryMode\":\"RESULT_QUERYABLE\"},"
                        + "\"outcomeType\":\"OutcomeUnknown\"}"));

        // Failed 保留 API 全部 retry mode，不能被 unknown 的 NONE 规则过度收窄。
        for (RetryMode mode : RetryMode.values()) {
            KnowledgeOutcome.Failed failed = new KnowledgeOutcome.Failed(failure(mode));
            assertEquals(failed, codec().decode(
                    codec().encode(PortalCallKind.KNOWLEDGE_EXECUTE, failed),
                    PortalCallKind.KNOWLEDGE_EXECUTE));
        }
    }

    /** golden 双向核对生产 encode 与独立手写 snapshot。 */
    private static void assertGolden(KnowledgeOutcome result, String type, String golden) {
        assertEquals(manual(type, golden),
                codec().encode(PortalCallKind.KNOWLEDGE_EXECUTE, result));
        assertEquals(result, codec().decode(
                manual(type, golden), PortalCallKind.KNOWLEDGE_EXECUTE));
    }

    /** 手写外层 tuple 只依据冻结 subtype 注册表选择 resolution。 */
    private static PortalOutcomeSnapshot manual(String type, String body) {
        PortalResolution resolution = switch (type) {
            case "Retrieved" -> PortalResolution.KNOWN_RESULT;
            case "Failed" -> PortalResolution.KNOWN_FAILURE;
            case "OutcomeUnknown" -> PortalResolution.OUTCOME_UNKNOWN;
            default -> throw new IllegalArgumentException("unsupported test tuple");
        };
        return new PortalOutcomeSnapshot(
                PortalCallKind.KNOWLEDGE_EXECUTE,
                resolution,
                type,
                new TypedPayload(SCHEMA, body));
    }

    /** 默认 validator 只接受完整 fixture 的两个 content 或单项数值 fixture。 */
    private static KnowledgeOutcomeCodec codec() {
        return new KnowledgeOutcomeCodec(LIMITS, (payload, limits) -> {
            if (!Set.of(CONTENT_A, CONTENT_B).contains(payload)) {
                fail("unexpected evidence content");
            }
            assertEquals(LIMITS, limits);
        });
    }

    /** malformed 输入不得触发 registry，错误消息不得泄露正文且 cause 必须为空。 */
    private static void assertKnowledgeRejected(PortalOutcomeSnapshot snapshot) {
        assertKnowledgeRejected(snapshot, PortalCallKind.KNOWLEDGE_EXECUTE);
    }

    /** expectedKind 错配也必须归一化为脱敏本地协议错误。 */
    private static void assertKnowledgeRejected(
            PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        KnowledgeOutcomeCodec codec = new KnowledgeOutcomeCodec(
                LIMITS, (payload, limits) -> fail("malformed wire must not reach registry"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> codec.decode(snapshot, expectedKind));
        assertEquals("invalid knowledge outcome", error.getMessage());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("SECRET"));
    }

    /** 正常 Retrieved fixture 保留非依赖排序的 step/item/trace 顺序。 */
    private static KnowledgeOutcome.Retrieved retrieved() {
        return new KnowledgeOutcome.Retrieved(
                fullBundle(List.of(itemB(), itemA(0.0d))),
                new ResultRef("knowledge-result"));
    }

    /** 完整 bundle 构造器只参数化 item 顺序，用于证明 List 不排序。 */
    private static EvidenceBundle fullBundle(List<EvidenceItem> items) {
        return new EvidenceBundle(
                "bundle",
                "request",
                fullPlan(),
                items,
                List.of(List.of("item-b", "item-a")),
                EvidenceSufficiency.PARTIAL,
                List.of(
                        new KnowledgeTraceStep(
                                "step-2",
                                "select",
                                List.of("item-a", "item-b"),
                                List.of("item-b"),
                                9,
                                0),
                        new KnowledgeTraceStep(
                                "step-1",
                                "retrieve",
                                List.of("item-b"),
                                List.of("item-b"),
                                10,
                                Long.MAX_VALUE)),
                Long.MAX_VALUE,
                CREATED_AT, java.util.Optional.empty());
    }

    /** plan 的 steps 故意把依赖者放在被依赖者前，固定 List 顺序不等于执行拓扑序。 */
    private static KnowledgePlan fullPlan() {
        KnowledgeStep first = new KnowledgeStep(
                "step-1",
                "first",
                Set.of("A", "\"", "\u0000", "é", "", "𐀀"),
                KnowledgeOperatorType.VECTOR,
                List.of(),
                new KnowledgeBudget(1, 2, 3, 4, Long.MAX_VALUE));
        KnowledgeStep second = new KnowledgeStep(
                "step-2",
                "second",
                Set.of(),
                KnowledgeOperatorType.GRAPH,
                List.of("step-1"),
                new KnowledgeBudget(5, 6, 7, 8, 9));
        return new KnowledgePlan(
                "plan", "request", List.of(second, first), "enough", "planner-v1");
    }

    /** 第一项带非平凡 List 顺序与普通小数 relevance。 */
    private static EvidenceItem itemB() {
        return new EvidenceItem(
                "item-b",
                "source-b",
                "v2",
                KnowledgeOperatorType.KEYWORD,
                CONTENT_B,
                List.of("p2", "p1"),
                List.of("g2", "g1"),
                0.75d,
                "verified");
    }

    /** 第二项参数化 relevance，用于负零、极小值和上界 fixture。 */
    private static EvidenceItem itemA(double relevance) {
        return new EvidenceItem(
                "item-a",
                "source-a",
                "v1",
                KnowledgeOperatorType.STRUCTURED,
                CONTENT_A,
                List.of(),
                List.of(),
                relevance,
                "derived");
    }

    /** 单项数值 fixture 移除冲突/trace 对第二 item 的引用，避免测试 API 外部契约。 */
    private static KnowledgeOutcome.Retrieved singleItemOutcome(double relevance) {
        KnowledgePlan plan = new KnowledgePlan(
                "single-plan",
                "single-request",
                List.of(new KnowledgeStep(
                        "single-step",
                        "query",
                        Set.of(),
                        KnowledgeOperatorType.KEYWORD,
                        List.of(),
                        new KnowledgeBudget(1, 1, 1, 1, 1))),
                "stop",
                "planner");
        EvidenceBundle bundle = new EvidenceBundle(
                "single-bundle",
                "single-request",
                plan,
                List.of(itemA(relevance)),
                List.of(),
                EvidenceSufficiency.SUFFICIENT,
                List.of(new KnowledgeTraceStep(
                        "single-step",
                        "select",
                        List.of("item-a"),
                        List.of("item-a"),
                        1,
                        0)),
                1,
                Instant.parse("2026-09-05T01:02:03Z"), java.util.Optional.empty());
        return new KnowledgeOutcome.Retrieved(bundle, new ResultRef("single-result"));
    }

    /** 空集合 fixture 用于独立手算聚合资源边界。 */
    private static EvidenceBundle emptyBundle() {
        return new EvidenceBundle(
                "bundle-empty",
                "request-empty",
                new KnowledgePlan(
                        "plan-empty",
                        "request-empty",
                        List.of(),
                        "stop",
                        "planner-empty"),
                List.of(),
                List.of(),
                EvidenceSufficiency.INSUFFICIENT,
                List.of(),
                1,
                Instant.parse("2026-09-05T01:02:03Z"), java.util.Optional.empty());
    }

    /** 失败事实只参数化 RetryMode，resultRef 保持业务审计引用。 */
    private static PortFailure failure(RetryMode mode) {
        return new PortFailure("declined", mode, new ResultRef("audit"));
    }
}
