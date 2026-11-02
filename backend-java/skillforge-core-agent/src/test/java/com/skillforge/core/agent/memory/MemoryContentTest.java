package com.skillforge.core.agent.memory;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryContentTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void explicitContentPreservesStructuredValuesAndTypeSpecificTimeNature() throws Exception {
        var value = json.readTree("{\"API\":\"Records.JSON\",\"order\":[\"B\",\"a\"],\"enabled\":true}");
        var preference = MemoryContent.explicit(value, "preference");
        assertEquals("stable", MemoryContent.semantics(preference).temporal().kind());
        assertEquals(List.of(), MemoryContent.semantics(preference).conditions());
        assertEquals(value, preference.path("value"));
        assertEquals("unknown", MemoryContent.semantics(MemoryContent.explicit(value, "episode")).temporal().kind());
    }

    @Test void revisionProposalIsHostOnlyAndExcludedFromSemanticText() throws Exception {
        var content = MemoryContent.explicit(json.readTree("{\"text\":\"Markdown\"}"), "preference");
        var proposed = MemoryContent.withRevisionProposal(content, "123456789", 3);
        assertEquals(new MemoryContent.RevisionProposal("123456789", 3), MemoryContent.revisionProposal(proposed).orElseThrow());
        assertEquals(MemoryContent.contentText(content), MemoryContent.contentText(proposed));
        assertFalse(MemoryContent.contentText(proposed).contains("123456789"));
        assertFalse(content.has("revisionProposal"));
        assertEquals(MemoryContent.semantics(content), MemoryContent.semantics(proposed));
    }

    @Test void interpretedContentExplicitlyKeepsEmptyConditions() {
        var candidate = new MemoryExtractionCandidates.Candidate("preference", "user:7", "format",
                json.createObjectNode().put("text", "Markdown"), "explicit_save", false, "user_agent",
                new MemoryExtractionCandidates.Temporal("stable", null, null), List.of(),
                List.of(new MemoryExtractionCandidates.Evidence("s0", "Markdown")),
                new MemoryExtractionCandidates.Relation("new", null), List.of());
        var content = MemoryContent.interpreted(candidate);
        assertTrue(content.path("conditions").isArray());
        assertTrue(content.path("conditions").isEmpty());
    }

    @Test void envelopeUnknownFieldsMalformedRevisionAndUnboundedValuesAreRejected() throws Exception {
        var content = MemoryContent.explicit(json.createObjectNode().put("text", "value"), "preference");
        content.put("sourceId", "forged");
        assertThrows(IllegalArgumentException.class, () -> MemoryContent.validate(content));
        content.remove("sourceId");
        content.putObject("revisionProposal").put("factId", "1").put("expectedRevision", "3");
        assertThrows(IllegalArgumentException.class, () -> MemoryContent.validate(content));
        assertThrows(IllegalArgumentException.class, () -> MemoryContent.withRevisionProposal(
                MemoryContent.explicit(json.createObjectNode(), "preference"), "1", 0));
        var oversized = json.createObjectNode().put("text", "x".repeat(16_384));
        assertThrows(IllegalArgumentException.class, () -> MemoryContent.explicit(oversized, "preference"));
    }
}
