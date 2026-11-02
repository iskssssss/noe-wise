package com.skillforge.runtime.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证 Capability 与 Knowledge 唯一公开值对象的字段闭包和构造期不变量。 */
class CapabilityAndKnowledgeContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final EndUserId USER = new EndUserId(7);
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    /** 验证 Capability 的字段和四值枚举与 §5.4 精确一致。 */
    @Test
    void freezesCapabilityContract() {
        assertEquals(
                List.of("SKILL", "TOOL", "WORKFLOW", "KNOWLEDGE_SOURCE"),
                Arrays.stream(CapabilityType.values()).map(Enum::name).toList());
        assertEquals(
                List.of("READ_ONLY", "IDEMPOTENT_WRITE", "NON_IDEMPOTENT_WRITE", "DESTRUCTIVE"),
                Arrays.stream(ToolEffect.values()).map(Enum::name).toList());
        assertEquals(
                List.of("type:CapabilityType", "capabilityId:String", "versionId:String"),
                componentSignatures(CapabilityRef.class));

        CapabilityRef ref = new CapabilityRef(CapabilityType.TOOL, "tool-1", "version-1");
        assertEquals(CapabilityType.TOOL, ref.type());
        assertThrows(NullPointerException.class, () -> new CapabilityRef(null, "tool-1", "version-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new CapabilityRef(CapabilityType.TOOL, " ", "version-1"));
    }

    /** 验证 Knowledge 根类型、字段顺序和枚举值与 §5.9 精确一致。 */
    @Test
    void freezesKnowledgeFieldClosure() {
        assertEquals(
                List.of("NONE", "GROUNDING", "RESEARCH"),
                Arrays.stream(KnowledgeMode.values()).map(Enum::name).toList());
        assertEquals(
                List.of("KEYWORD", "VECTOR", "GRAPH", "LINK", "STRUCTURED", "DOCUMENT_LIST", "DOCUMENT_READ"),
                Arrays.stream(KnowledgeOperatorType.values()).map(Enum::name).toList());
        assertEquals(
                List.of("SUFFICIENT", "PARTIAL", "INSUFFICIENT"),
                Arrays.stream(EvidenceSufficiency.values()).map(Enum::name).toList());
        assertEquals(
                List.of(
                        "maxQueries:int", "maxItems:int", "maxTokens:int",
                        "maxCharacters:int", "timeoutMs:long"),
                componentSignatures(KnowledgeBudget.class));
        assertEquals(
                List.of("sourceId:String", "sourceVersion:String"),
                componentSignatures(KnowledgeSourceVersion.class));
        assertEquals(
                List.of(
                        "knowledgeRequestId:String", "tenantId:TenantId", "requester:RunRequester",
                        "agentId:AgentId", "sessionRef:SessionRef", "trace:TraceEnvelope",
                        "mode:KnowledgeMode", "intent:String", "operation:KnowledgeOperation",
                        "allowedSourceIds:java.util.Set<String>",
                        "allowedSourceVersions:java.util.Set<KnowledgeSourceVersion>",
                        "allowedOperators:java.util.Set<KnowledgeOperatorType>",
                        "budget:KnowledgeBudget", "asOf:java.time.Instant",
                        "freshnessAfter:java.util.Optional<java.time.Instant>",
                        "citationRequired:boolean"),
                componentSignatures(KnowledgeRequest.class));
        assertEquals(
                List.of(
                        "stepId:String", "query:String", "sourceIds:java.util.Set<String>",
                        "operator:KnowledgeOperatorType", "dependsOn:java.util.List<String>",
                        "budget:KnowledgeBudget"),
                componentSignatures(KnowledgeStep.class));
        assertEquals(
                List.of(
                        "planId:String", "knowledgeRequestId:String",
                        "steps:java.util.List<KnowledgeStep>", "stopCondition:String",
                        "plannerVersion:String"),
                componentSignatures(KnowledgePlan.class));
        assertEquals(
                List.of(
                        "itemId:String", "sourceId:String", "sourceVersion:String",
                        "operator:KnowledgeOperatorType", "content:TypedPayload",
                        "provenanceRefs:java.util.List<String>",
                        "graphPath:java.util.List<String>", "relevance:double", "trustLevel:String"),
                componentSignatures(EvidenceItem.class));
        assertEquals(
                List.of(
                        "stepId:String", "phase:String",
                        "candidateItemIds:java.util.List<String>",
                        "selectedItemIds:java.util.List<String>",
                        "tokenCost:int", "elapsedMs:long"),
                componentSignatures(KnowledgeTraceStep.class));
        assertEquals(
                List.of(
                        "bundleId:String", "knowledgeRequestId:String", "plan:KnowledgePlan",
                        "items:java.util.List<EvidenceItem>",
                        "conflictItemIds:java.util.List<java.util.List<String>>",
                        "sufficiency:EvidenceSufficiency",
                        "trace:java.util.List<KnowledgeTraceStep>", "version:long",
                        "createdAt:java.time.Instant", "documentResult:java.util.Optional<KnowledgeDocumentResult>"),
                componentSignatures(EvidenceBundle.class));
        assertEquals(
                List.of(
                        "sessionRef:SessionRef", "bundleId:String",
                        "itemIds:java.util.List<String>", "version:long", "sourceEventId:EventId"),
                componentSignatures(EvidenceRef.class));
    }

    /** 验证 Knowledge 请求对身份、预算、Optional 和集合执行 fail closed。 */
    @Test
    void validatesKnowledgeRequestAndBudget() {
        HashSet<String> sourceIds = new HashSet<>(Set.of("source-1"));
        HashSet<KnowledgeSourceVersion> versions = new HashSet<>(
                Set.of(new KnowledgeSourceVersion("source-1", "v1")));
        HashSet<KnowledgeOperatorType> operators = new HashSet<>(Set.of(KnowledgeOperatorType.VECTOR));
        KnowledgeRequest request = knowledgeRequest(sourceIds, versions, operators);
        sourceIds.add("source-2");
        versions.add(new KnowledgeSourceVersion("source-2", "v2"));
        operators.add(KnowledgeOperatorType.GRAPH);

        assertEquals(Set.of("source-1"), request.allowedSourceIds());
        assertEquals(1, request.allowedSourceVersions().size());
        assertEquals(Set.of(KnowledgeOperatorType.VECTOR), request.allowedOperators());
        assertNotSame(sourceIds, request.allowedSourceIds());
        assertThrows(UnsupportedOperationException.class,
                () -> request.allowedSourceIds().add("source-3"));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeBudget(0, 1, 1, 1, 1));
        assertThrows(NullPointerException.class,
                () -> new KnowledgeRequest("request-1", TENANT, new com.skillforge.runtime.api.RunRequester.EndUser(USER), AGENT, SESSION, trace(), KnowledgeMode.GROUNDING, "intent", new com.skillforge.runtime.api.KnowledgeOperation.Search("query"), Set.of(), Set.of(), Set.of(), budget(), NOW, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeRequest("request-1", TENANT, new com.skillforge.runtime.api.RunRequester.EndUser(USER), AGENT, new SessionRef(new TenantId("tenant-2"), "session-2"), trace(), KnowledgeMode.GROUNDING, "intent", new com.skillforge.runtime.api.KnowledgeOperation.Search("query"), Set.of(), Set.of(), Set.of(), budget(), NOW, Optional.empty(), true));
        assertThrows(IllegalArgumentException.class,
                () -> knowledgeRequest(
                        Set.of("source-1"),
                        Set.of(new KnowledgeSourceVersion("source-2", "v1")),
                        Set.of(KnowledgeOperatorType.VECTOR)));
        KnowledgeRequest multiVersionRequest = knowledgeRequest(
                Set.of("source-1"),
                Set.of(
                        new KnowledgeSourceVersion("source-1", "v1"),
                        new KnowledgeSourceVersion("source-1", "v2")),
                Set.of(KnowledgeOperatorType.VECTOR));
        assertEquals(2, multiVersionRequest.allowedSourceVersions().size());
    }

    /** 验证 Knowledge plan、Evidence 与 trace 的闭包、去重和深度不可变性。 */
    @Test
    void validatesKnowledgeEvidenceClosure() {
        ArrayList<String> dependencies = new ArrayList<>();
        KnowledgeStep step = new KnowledgeStep(
                "step-1", "query", Set.of("source-1"), KnowledgeOperatorType.KEYWORD,
                dependencies, budget());
        ArrayList<KnowledgeStep> steps = new ArrayList<>(List.of(step));
        KnowledgePlan plan = new KnowledgePlan(
                "plan-1", "request-1", steps, "enough", "planner-v1");
        EvidenceItem item = evidenceItem("item-1");
        ArrayList<EvidenceItem> items = new ArrayList<>(List.of(item));
        ArrayList<String> conflictGroup = new ArrayList<>(List.of("item-1"));
        ArrayList<List<String>> conflicts = new ArrayList<>(List.of(conflictGroup));
        KnowledgeTraceStep traceStep = new KnowledgeTraceStep(
                "step-1", "retrieval", List.of("item-1"), List.of("item-1"), 1, 0);
        EvidenceBundle bundle = new EvidenceBundle(
                "bundle-1", "request-1", plan, items, conflicts,
                EvidenceSufficiency.SUFFICIENT, List.of(traceStep), 1, NOW, java.util.Optional.empty());
        steps.clear();
        items.clear();
        conflictGroup.add("unknown");
        conflicts.clear();

        assertEquals(1, plan.steps().size());
        assertEquals(1, bundle.items().size());
        assertEquals(List.of(List.of("item-1")), bundle.conflictItemIds());
        assertThrows(UnsupportedOperationException.class,
                () -> bundle.conflictItemIds().get(0).add("item-2"));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeStep(
                        "step-1", "query", Set.of(), KnowledgeOperatorType.KEYWORD,
                        List.of("step-1"), budget()));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgePlan(
                        "plan-1", "request-1", List.of(step, step), "enough", "planner-v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgePlan(
                        "plan-1", "request-1",
                        List.of(new KnowledgeStep(
                                "step-1", "query", Set.of(), KnowledgeOperatorType.KEYWORD,
                                List.of("missing-step"), budget())),
                        "enough", "planner-v1"));
        KnowledgeStep cycleStep1 = new KnowledgeStep(
                "cycle-1", "query-1", Set.of(), KnowledgeOperatorType.KEYWORD,
                List.of("cycle-2"), budget());
        KnowledgeStep cycleStep2 = new KnowledgeStep(
                "cycle-2", "query-2", Set.of(), KnowledgeOperatorType.KEYWORD,
                List.of("cycle-1"), budget());
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgePlan(
                        "plan-1", "request-1", List.of(cycleStep1, cycleStep2),
                        "enough", "planner-v1"));
        KnowledgeStep cycleStep3 = new KnowledgeStep(
                "cycle-3", "query-3", Set.of(), KnowledgeOperatorType.KEYWORD,
                List.of("cycle-1"), budget());
        KnowledgeStep threeCycleStep2 = new KnowledgeStep(
                "cycle-2", "query-2", Set.of(), KnowledgeOperatorType.KEYWORD,
                List.of("cycle-3"), budget());
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgePlan(
                        "plan-1", "request-1", List.of(cycleStep1, threeCycleStep2, cycleStep3),
                        "enough", "planner-v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeTraceStep(
                        "step-1", "retrieval", List.of("item-1"), List.of("item-2"), 1, 0));
        assertEquals(0, new KnowledgeTraceStep(
                "step-1", "retrieval", List.of("item-1"), List.of(), 0, 0).tokenCost());
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeTraceStep(
                        "step-1", "retrieval", List.of("item-1"), List.of(), -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceBundle(
                        "bundle-1", "other-request", plan, List.of(item), List.of(),
                        EvidenceSufficiency.PARTIAL, List.of(), 1, NOW, java.util.Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceBundle(
                        "bundle-1", "request-1", plan, List.of(item), List.of(List.of("missing")),
                        EvidenceSufficiency.PARTIAL, List.of(), 1, NOW, java.util.Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceBundle(
                        "bundle-1", "request-1", plan, List.of(item, item), List.of(),
                        EvidenceSufficiency.PARTIAL, List.of(), 1, NOW, java.util.Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceBundle(
                        "bundle-1", "request-1", plan, List.of(item), List.of(),
                        EvidenceSufficiency.PARTIAL,
                        List.of(new KnowledgeTraceStep(
                                "unknown-step", "retrieval", List.of("item-1"),
                                List.of(), 1, 0)),
                        1, NOW, java.util.Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceBundle(
                        "bundle-1", "request-1", plan, List.of(item), List.of(),
                        EvidenceSufficiency.PARTIAL, List.of(traceStep, traceStep), 1, NOW, java.util.Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceBundle(
                        "bundle-1", "request-1", plan, List.of(item), List.of(),
                        EvidenceSufficiency.PARTIAL,
                        List.of(new KnowledgeTraceStep(
                                "step-1", "retrieval", List.of("unknown-item"),
                                List.of(), 1, 0)),
                        1, NOW, java.util.Optional.empty()));
    }

    /** 验证 Evidence 值对象拒绝坏评分、重复引用、空 Optional 替代和无效版本。 */
    @Test
    void validatesEvidenceLeafValues() {
        EvidenceRef ref = new EvidenceRef(
                SESSION, "bundle-1", List.of("item-1"), 1, new EventId("event-1"));
        assertEquals(SESSION, ref.sessionRef());
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceItem(
                        "item-1", "source-1", "v1", KnowledgeOperatorType.VECTOR, payload(),
                        List.of(), List.of(), Double.NaN, "trusted"));
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceRef(
                        SESSION, "bundle-1", List.of("item-1", "item-1"), 1,
                        new EventId("event-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceRef(
                        SESSION, "bundle-1", List.of("item-1"), 0, new EventId("event-1")));
    }

    /** 扁平步骤列表可承载很长的依赖路径，合法链不应被 JVM 栈深度限制。 */
    @Test
    void acceptsLongDependencyChainWithoutStackRecursion() {
        var steps = longGraph(false);
        var plan = assertDoesNotThrow(() -> graphPlan(steps));
        assertEquals(steps, plan.steps());
        var reversed = new ArrayList<>(steps.reversed());
        assertEquals(reversed, assertDoesNotThrow(() -> graphPlan(reversed)).steps());
    }

    /** 环必须以协议错误拒绝，不能在走到回边之前先发生 StackOverflowError。 */
    @Test
    void rejectsLongDependencyCycleWithStableProtocolError() {
        var error = assertThrows(IllegalArgumentException.class, () -> graphPlan(longGraph(true)));
        assertEquals("steps must not contain dependency cycles", error.getMessage());
    }

    /** 独立传递闭包 oracle 穷举四节点有向图，不用生产拓扑算法自证判环正确。 */
    @Test
    void exhaustiveSmallGraphsMatchIndependentReachabilityOracle() {
        for (int mask = 0; mask < 4096; mask++) {
            boolean[][] reachable = new boolean[4][4];
            var steps = new ArrayList<KnowledgeStep>();
            int bit = 0;
            for (int from = 0; from < 4; from++) {
                var dependencies = new ArrayList<String>();
                for (int to = 0; to < 4; to++) {
                    if (from == to) { continue; }
                    if ((mask & (1 << bit)) != 0) { reachable[from][to] = true; dependencies.add("s" + to); }
                    bit++;
                }
                steps.add(new KnowledgeStep("s" + from, "query", Set.of(), KnowledgeOperatorType.KEYWORD, dependencies, budget()));
            }
            for (int via = 0; via < 4; via++) {
                for (int from = 0; from < 4; from++) {
                    for (int to = 0; to < 4; to++) { reachable[from][to] |= reachable[from][via] && reachable[via][to]; }
                }
            }
            boolean cyclic = false;
            for (int node = 0; node < 4; node++) { cyclic |= reachable[node][node]; }
            if (cyclic) { assertThrows(IllegalArgumentException.class, () -> graphPlan(steps), "graph " + mask); }
            else { assertEquals(steps, assertDoesNotThrow(() -> graphPlan(steps), "graph " + mask).steps()); }
        }
        assertEquals(List.of(), graphPlan(List.of()).steps());
    }

    /** 最后节点回到零才是真环；合法链的末项不再依赖任何节点。 */
    private static List<KnowledgeStep> longGraph(boolean cycle) {
        int count = 20000;
        var steps = new ArrayList<KnowledgeStep>(count);
        for (int i = 0; i < count; i++) {
            var dependencies = i + 1 < count ? List.of("s" + (i + 1)) : cycle ? List.of("s0") : List.<String>of();
            steps.add(new KnowledgeStep("s" + i, "query", Set.of(), KnowledgeOperatorType.KEYWORD, dependencies, budget()));
        }
        return steps;
    }

    /** 图结构校验不执行查询；业务执行预算不被错误用作计划图大小限制。 */
    private static KnowledgePlan graphPlan(List<KnowledgeStep> steps) { return new KnowledgePlan("plan", "request", steps, "enough", "v1"); }

    /** 构造完整 KnowledgeRequest，便于各测试只突出单个不变量。 */
    private static KnowledgeRequest knowledgeRequest(
            Set<String> sourceIds,
            Set<KnowledgeSourceVersion> versions,
            Set<KnowledgeOperatorType> operators) {
        return new KnowledgeRequest("request-1", TENANT, new com.skillforge.runtime.api.RunRequester.EndUser(USER), AGENT, SESSION, trace(), KnowledgeMode.GROUNDING, "answer", new com.skillforge.runtime.api.KnowledgeOperation.Search("question"), sourceIds, versions, operators, budget(), NOW, Optional.empty(), true);
    }

    /** 构造正数 Knowledge 预算。 */
    private static KnowledgeBudget budget() {
        return new KnowledgeBudget(2, 10, 100, 1_000, 30_000);
    }

    /** 构造单项完整 Evidence。 */
    private static EvidenceItem evidenceItem(String itemId) {
        return new EvidenceItem(
                itemId, "source-1", "v1", KnowledgeOperatorType.VECTOR, payload(),
                List.of("document-1"), List.of(), 0.8, "trusted");
    }

    /** 构造公开类型化载荷。 */
    private static TypedPayload payload() {
        return new TypedPayload("schema-1", "{}");
    }

    /** 构造完整 W3C trace 与因果输入引用。 */
    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-1", "span-1", new InputId("input-1"), Optional.of(new EventId("event-1")));
    }

    /** 返回 record component 的名称与完整泛型类型，冻结公开字段级 ABI。 */
    private static List<String> componentSignatures(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ":" + stableTypeName(component))
                .toList();
    }

    /** 只压缩稳定根包与 java.lang 前缀，保留原始类型和全部泛型参数。 */
    private static String stableTypeName(RecordComponent component) {
        return component.getGenericType().getTypeName()
                .replace("java.lang.", "")
                .replace("com.skillforge.runtime.api.", "");
    }
}
