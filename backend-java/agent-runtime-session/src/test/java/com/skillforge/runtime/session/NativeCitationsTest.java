package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.KnowledgePortal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 正文引用仅从原已注入知识生成；Markdown 示例和结构化正文不产生引用。 */
class NativeCitationsTest {
    private static final RunRef RUN = SessionFixtures.IDENTITY.runRef();
    private static final EvidenceItem FIRST = item("first");
    private static final EvidenceItem SECOND = item("second");
    private static final EvidenceItem NOT_INJECTED = item("not-injected");

    @Test
    void bindsOnlyActuallyInjectedSourcesAndRendersLocalAnchorsInFirstOccurrenceOrder() {
        var observation = observation("actual", List.of(FIRST, SECOND, NOT_INJECTED), List.of("first", "second"));
        String second = marker(SECOND); String first = marker(FIRST);
        String raw = "第二条事实😀" + second + "，第一条事实" + first + "。再次引用" + second;
        var resolved = resolved(raw, List.of(observation));
        assertEquals(List.of("second", "first"), resolved.evidenceItemIds());
        assertSame(observation, resolved.evidence().getFirst().observation());
        assertSame(SECOND, resolved.evidence().getFirst().item());
        assertEquals(List.of(1, 2, 1), resolved.markers().stream().map(NativeCitations.Marker::ordinal).toList());
        for (var marker : resolved.markers()) {
            assertEquals("[[cite:" + marker.citationId() + "]]", raw.substring(marker.start(), marker.end()));
            assertTrue(resolved.output().contains("[知识依据 " + marker.ordinal() + "](#citation-" + marker.citationId() + ")"));
        }
        assertThrows(UnsupportedOperationException.class, () -> resolved.evidence().clear());
        assertThrows(UnsupportedOperationException.class, () -> resolved.markers().clear());
        assertThrows(UnsupportedOperationException.class, () -> resolved.evidenceItemIds().clear());
    }

    @Test
    void leavesCodeQuotedLinksImagesEscapedAndHtmlAttributeExamplesUntouched() {
        String marker = marker(FIRST);
        var examples = List.of("```text\n" + marker + "\n```", "~~~\n" + marker + "\n~~~", "    " + marker,
                "`" + marker + "`", "``code ` " + marker + "``", "> 用户示例 " + marker,
                "> - 引用列表 " + marker, "[" + marker + "](https://example.test)",
                "![" + marker + "](https://example.test/x.png)", "\\" + marker,
                "<span data-example=\"" + marker + "\"></span>", "<!-- " + marker + " -->");
        for (String example : examples) {
            var resolved = resolved(example, List.of(observation("actual", List.of(FIRST), List.of("first"))));
            assertEquals(example, resolved.output(), example); assertTrue(resolved.evidence().isEmpty(), example);
        }
    }

    @Test
    void doesNotCountAnUnknownOrMalformedMarkerInsideAnExcludedExample() {
        for (String example : List.of("`[[cite:unknown]]`", "> [[cite:kbad]]", "```\n[[cite:broken\n```")) {
            var resolved = resolved(example, List.of());
            assertTrue(resolved.evidenceItemIds().isEmpty()); assertEquals(example, resolved.output());
        }
    }

    @Test
    void parsesProseAfterExamplesAndCountsEvenNumberOfBackslashesAsAnUnescapedMarker() {
        String example = "`" + marker(FIRST) + "`\n\n> 用户例子 " + marker(FIRST) + "\n\n实际事实 " + marker(FIRST);
        var resolved = resolved(example, List.of(observation("actual", List.of(FIRST), List.of("first"))));
        assertEquals(1, resolved.markers().size());
        assertEquals(example.lastIndexOf(marker(FIRST)), resolved.markers().getFirst().start());
        assertEquals(1, resolved("\\\\" + marker(FIRST), List.of(observation("actual", List.of(FIRST), List.of("first")))).markers().size());
    }

    @Test
    void keepsStrictJsonValuesAndNestedExampleMarkersIntact() {
        var observations = List.of(observation("actual", List.of(FIRST), List.of("first")));
        for (String json : List.of("{\"text\":\"" + marker(FIRST) + "\"}", "[\"" + marker(FIRST) + "\"]", "\"" + marker(FIRST) + "\"", "null", "42")) {
            var resolved = resolved(json, observations);
            assertEquals(json, resolved.output()); assertTrue(resolved.evidence().isEmpty());
        }
    }

    @Test
    void rejectsForgedUninjectedCrossRunAndMalformedMarkers() {
        var observations = List.of(observation("actual", List.of(FIRST, NOT_INJECTED), List.of("first")));
        String otherRun = NativeCitations.citationId(new RunRef(RUN.tenantId(), "different-run"), FIRST);
        for (String invalid : List.of("[[cite:k0000000000000000]]", marker(NOT_INJECTED), "[[cite:" + otherRun + "]]",
                "[[cite:unknown]]", "[[cite:k0123456789ABCDEF]]", "[[cite:k0123456789abcdef]", "[[cite:", "[[cite:`k0123456789abcdef`]]")) {
            assertEquals("knowledge_citation_invalid", assertInstanceOf(NativeCitations.Rejected.class,
                    NativeCitations.resolve(RUN, invalid, observations, SessionFixtures.LIMITS)).code(), invalid);
        }
    }

    @Test
    void rejectsAmbiguousObservationIdentityInsteadOfPickingTheFirstBundle() {
        var first = observation("one", List.of(FIRST), List.of("first"));
        var second = observation("two", List.of(FIRST), List.of("first"));
        assertInstanceOf(NativeCitations.Rejected.class, NativeCitations.resolve(RUN, marker(FIRST), List.of(first, second), SessionFixtures.LIMITS));
    }

    @Test
    void neverInfersReferencesFromRetrievalWithoutBodyMarkers() {
        var resolved = resolved("普通回答没有声称引用知识。", List.of(observation("actual", List.of(FIRST), List.of("first"))));
        assertTrue(resolved.evidenceItemIds().isEmpty()); assertTrue(resolved.markers().isEmpty());
    }

    @Test
    void scopesAliasesToTenantRunAndSourceVersionWhileRemainingDeterministic() {
        String id = NativeCitations.citationId(RUN, FIRST);
        assertTrue(id.matches("k[0-9a-f]{16}")); assertEquals(id, NativeCitations.citationId(RUN, FIRST));
        assertNotEquals(id, NativeCitations.citationId(new RunRef(new TenantId("another-tenant"), RUN.runId()), FIRST));
        assertNotEquals(id, NativeCitations.citationId(new RunRef(RUN.tenantId(), "another-run"), FIRST));
        var changed = new EvidenceItem(FIRST.itemId(), FIRST.sourceId(), "version-2", FIRST.operator(), FIRST.content(),
                FIRST.provenanceRefs(), FIRST.graphPath(), FIRST.relevance(), FIRST.trustLevel());
        assertNotEquals(id, NativeCitations.citationId(RUN, changed));
    }

    private static NativeCitations.Resolved resolved(String text, List<AgentExecutionProgram.KnowledgeObservation> observations) {
        return assertInstanceOf(NativeCitations.Resolved.class, NativeCitations.resolve(RUN, text, observations, SessionFixtures.LIMITS));
    }

    private static String marker(EvidenceItem item) { return "[[cite:" + NativeCitations.citationId(RUN, item) + "]]"; }

    private static EvidenceItem item(String id) {
        return new EvidenceItem(id, "source", "version-1", KnowledgeOperatorType.KEYWORD,
                new TypedPayload("skillforge.runtime.knowledge.chunk.v1", "{\"text\":\"真实知识\"}"), List.of(), List.of(), 1, "tenant");
    }

    private static AgentExecutionProgram.KnowledgeObservation observation(String suffix, List<EvidenceItem> items, List<String> injected) {
        var budget = new KnowledgeBudget(1, 10, 10_000, 10_000, 1_000);
        var versions = Set.of(new KnowledgeSourceVersion("source", "version-1"));
        var request = new KnowledgeRequest("request:" + suffix, RUN.tenantId(), SessionFixtures.IDENTITY.requester(), SessionFixtures.IDENTITY.agentId(),
                SessionFixtures.OWNER.sessionRef(), SessionFixtures.IDENTITY.trace(), KnowledgeMode.GROUNDING, "answer", new com.skillforge.runtime.api.KnowledgeOperation.Search("原始查询"),
                Set.of("source"), versions, Set.of(KnowledgeOperatorType.KEYWORD), budget, SessionFixtures.NOW, Optional.empty(), true);
        var target = new KnowledgePortal.FrozenKnowledgeTarget(new KnowledgePortal.KnowledgeHandle("knowledge:docs"),
                new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "docs", "version-1"), "docs", 1, versions, Set.of(KnowledgeOperatorType.KEYWORD), Duration.ofSeconds(1));
        var call = new AgentExecutionCodec.KnowledgeCallRequest(target, request, SessionFixtures.CAPABILITY, new EventId("event:" + suffix),
                "call:" + suffix, "knowledge_query", new TypedPayload("application/json", "{}"));
        var step = new KnowledgeStep("step", "原始查询", Set.of("source"), KnowledgeOperatorType.KEYWORD, List.of(), budget);
        var bundle = new EvidenceBundle("bundle:" + suffix, request.knowledgeRequestId(),
                new KnowledgePlan("plan:" + suffix, request.knowledgeRequestId(), List.of(step), "budget", "planner"), items, List.of(),
                EvidenceSufficiency.PARTIAL, List.of(new KnowledgeTraceStep("step", "selection", items.stream().map(EvidenceItem::itemId).toList(), injected, 1, 1)), 1, SessionFixtures.NOW, java.util.Optional.empty());
        return new AgentExecutionProgram.KnowledgeObservation(call, new ResultRef("result:" + suffix), bundle, injected);
    }
}
