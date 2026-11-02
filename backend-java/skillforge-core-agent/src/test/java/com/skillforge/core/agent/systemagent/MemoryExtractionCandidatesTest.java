package com.skillforge.core.agent.systemagent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.agent.memory.MemoryContent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryExtractionCandidatesTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MemoryExtractionCandidates parser = new MemoryExtractionCandidates(json);
    private final Instant observedAt = Instant.parse("2026-10-23T00:00:00Z");

    @Test void observedPreferenceAndTaskWordingRemainCandidatesWithoutHeuristicLoss() throws Exception {
        ObjectNode candidate = candidate();
        candidate.put("signal", "observed");
        candidate.set("object", json.readTree("{\"API\":\"Records.JSON\",\"items\":[\"B\",\"a\"],\"text\":\"这次沿用长期偏好\"}"));
        var parsed = parser.parse(output(candidate), input());
        assertEquals(1, parsed.size());
        assertEquals("observed", parsed.getFirst().signal());
        assertEquals("Records.JSON", parsed.getFirst().object().path("API").textValue());
        assertEquals(List.of("B", "a"), json.convertValue(parsed.getFirst().object().path("items"), List.class));
        assertEquals("stable", parsed.getFirst().temporal().kind());
        ((ObjectNode) parsed.getFirst().object()).put("API", "mutated");
        assertEquals("Records.JSON", parsed.getFirst().object().path("API").textValue());
        assertEquals(candidate.path("object").size(), parser.toNode(parsed.getFirst()).path("object").size());
    }

    @Test void foreignSourceRewrittenQuoteAndOldFactCannotBecomeNewEvidence() throws Exception {
        for (String source : List.of("other", "f0")) {
            var candidate = candidate();
            ((ObjectNode) candidate.path("evidence").get(0)).put("sourceId", source);
            assertThrows(IllegalArgumentException.class, () -> parser.parse(output(candidate), input()));
        }
        var candidate = candidate();
        ((ObjectNode) candidate.path("evidence").get(0)).put("quote", "source does not contain this");
        assertThrows(IllegalArgumentException.class, () -> parser.parse(output(candidate), input()));
    }

    @Test void relationReferencesOnlyFrozenAliasesAndDoesNotAcceptNewWithTarget() throws Exception {
        for (String kind : List.of("similar", "possible_conflict", "correction")) {
            var candidate = candidate();
            candidate.putObject("relation").put("kind", kind).put("target", "f0");
            assertEquals("f0", parser.parse(output(candidate), input()).getFirst().relation().target());
            candidate.putObject("relation").put("kind", kind).put("target", "foreign");
            assertThrows(IllegalArgumentException.class, () -> parser.parse(output(candidate), input()));
        }
        var candidate = candidate();
        candidate.putObject("relation").put("kind", "new").put("target", "f0");
        assertThrows(IllegalArgumentException.class, () -> parser.parse(output(candidate), input()));
    }

    @Test void openIntervalIsAllowedButUnorderedOrFabricatedIntervalsAreRejected() throws Exception {
        var candidate = candidate();
        candidate.putObject("temporal").put("kind", "interval").put("from", observedAt.toString()).putNull("to");
        assertNull(parser.parse(output(candidate), input()).getFirst().temporal().to());
        candidate.withObject("temporal").put("to", observedAt.plusSeconds(1).toString());
        assertEquals(observedAt.plusSeconds(1), parser.parse(output(candidate), input()).getFirst().temporal().to());
        candidate.withObject("temporal").put("to", observedAt.toString());
        assertThrows(IllegalArgumentException.class, () -> parser.parse(output(candidate), input()));
        candidate.withObject("temporal").put("kind", "stable");
        assertThrows(IllegalArgumentException.class, () -> parser.parse(output(candidate), input()));
    }

    @Test void strictVersionFieldsSubjectAndSignalRejectModelAuthorityClaims() throws Exception {
        for (String field : List.of("tenantId", "revisionProposal", "delete", "admin")) {
            var candidate = candidate(); candidate.put(field, "forged");
            assertThrows(IllegalArgumentException.class, () -> parser.parse(output(candidate), input()));
        }
        for (String field : List.of("scope", "subject", "signal")) {
            var candidate = candidate(); candidate.put(field, "admin");
            assertThrows(IllegalArgumentException.class, () -> parser.parse(output(candidate), input()));
        }
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"candidates\":[]}", input()));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"schemaVersion\":4294967298,\"candidates\":[]}", input()));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"schemaVersion\":2,\"schemaVersion\":2,\"candidates\":[]}", input()));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"schemaVersion\":2,\"candidates\":[]} {}", input()));
    }

    @Test void exactDuplicatesCollapseButConditionsAndCaseRemainDistinct() throws Exception {
        var first = candidate(); var same = candidate();
        same.set("object", json.readTree("{\"text\":\"Markdown\"}"));
        var conditioned = candidate(); conditioned.putArray("conditions").add("仅文档交付");
        var root = json.createObjectNode().put("schemaVersion", 2);
        root.putArray("candidates").add(first).add(same).add(conditioned);
        assertEquals(2, parser.parse(root.toString(), input()).size());
    }

    @Test void frozenInputRejectsDuplicateAliasesAndDeepValues() throws Exception {
        ObjectNode input = (ObjectNode) input();
        input.withArray("sources").add(input.path("sources").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> parser.validateInput(input));
        var candidate = candidate(); var nested = candidate.putObject("object");
        for (int i = 0; i < MemoryExtractionCandidates.MAX_JSON_DEPTH + 1; i++) nested = nested.putObject("child");
        String output = output(candidate);
        assertThrows(IllegalArgumentException.class, () -> parser.parse(output, input()));
    }

    @Test void existingFactSnapshotDoesNotShareMutableJsonWithCaller() {
        var original = json.createObjectNode().put("text", "Markdown");
        var snapshot = new MemoryExtractionCandidates.ExistingFact("f0", "preference", "user_agent", "user:7",
                "format", MemoryContent.explicitContent(original, "preference"), 3, observedAt, null);
        original.put("text", "changed-before-use");
        ((ObjectNode) snapshot.object().value()).put("text", "changed-through-accessor");
        assertEquals("Markdown", snapshot.object().value().path("text").textValue());
    }

    @Test void publishedPromptExampleUsesTheSameTypedCandidateProtocol() {
        String example = com.skillforge.prompts.PromptDefaults.systemPurpose("memory-extraction").lines()
                .filter(line -> line.startsWith("{\"type\""))
                .findFirst().orElseThrow();
        var input = parser.typedInput(7,
                List.of(new MemoryExtractionCandidates.Source("s0", "以后请记住我偏好 Markdown", observedAt)), List.of());
        var candidate = parser.decodeCandidate(example, input);
        assertEquals("explicit_save", candidate.signal());
        assertEquals("new", candidate.relation().kind());
        assertEquals(List.of(), candidate.conditions());
        assertEquals(parser.encodeCandidate(candidate), parser.encodeCandidate(parser.decodeCandidate(parser.encodeCandidate(candidate), input)));
    }

    private JsonNode input() throws Exception {
        return parser.input(7, List.of(new MemoryExtractionCandidates.Source("s0", "以后请记住我偏好 Markdown", observedAt)),
                List.of(new MemoryExtractionCandidates.ExistingFact("f0", "preference", "user_agent", "user:7", "format",
                        MemoryContent.explicitContent(json.readTree("{\"text\":\"HTML\"}"), "preference"),
                        3, observedAt.minusSeconds(1), null)));
    }
    private ObjectNode candidate() throws Exception {
        return (ObjectNode) json.readTree("""
                {"type":"preference","subject":"user:7","predicate":"format","object":{"text":"Markdown"},
                 "signal":"explicit_save","sensitive":false,"scope":"user_agent",
                 "temporal":{"kind":"stable","from":null,"to":null},"conditions":[],
                 "evidence":[{"sourceId":"s0","quote":"偏好 Markdown"}],
                 "relation":{"kind":"new","target":null},"uncertainties":[]}
                """);
    }
    private String output(JsonNode candidate) {
        var root = json.createObjectNode().put("schemaVersion", 2); root.putArray("candidates").add(candidate); return root.toString();
    }
}
