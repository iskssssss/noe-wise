package com.skillforge.core.agent.memory;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Temporal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** wire 精度、必需 null 与可省略字段在同一正式 codec 中闭合。 */
class MemoryJsonCodecTest {
    private final MemoryExtractionCandidates contract = new MemoryExtractionCandidates(new ObjectMapper());

    @Test void numericAndIsoCanonicalEncodingSurvivesJsonbRepresentationChanges() {
        String scientific = """
                {"value":{"amount":1.0E20,"unit":"CNY"},"conditions":[],
                 "temporal":{"kind":"stable","from":null,"to":null}}
                """;
        String expanded = scientific.replace("1.0E20", "100000000000000000000");
        var first = MemoryContent.decode(scientific);
        var second = MemoryContent.decode(expanded);
        assertEquals(MemoryContent.encode(first), MemoryContent.encode(second));
        assertTrue(MemoryContent.encode(first).contains("100000000000000000000"));
        assertFalse(MemoryContent.encode(first).contains("E+"));
        var input = contract.typedInput(7, List.of(new MemoryExtractionCandidates.Source("s0", "amount",
                Instant.parse("2026-10-23T00:00:00.123456789Z"))), List.of());
        String encoded = contract.encodeInput(input);
        assertEquals(encoded, contract.encodeInput(contract.decodeInput(encoded)));
        assertTrue(encoded.contains("2026-10-23T00:00:00.123456789Z"));
    }

    @Test void optionalRevisionIsOmittedButRequiredTemporalNullsArePresent() {
        var content = MemoryContent.explicitContent(new ObjectMapper().createObjectNode().put("text", "Markdown"), "preference");
        String encoded = MemoryContent.encode(content);
        assertFalse(encoded.contains("revisionProposal"));
        assertTrue(encoded.contains("\"from\":null"));
        assertTrue(encoded.contains("\"to\":null"));
        assertEquals(content, MemoryContent.decode(encoded));
        assertThrows(IllegalArgumentException.class,
                () -> MemoryContent.decode(encoded.replace("\"from\":null,", "")));
        assertThrows(IllegalArgumentException.class,
                () -> MemoryContent.decode(encoded.substring(0, encoded.length() - 1) + ",\"revisionProposal\":null}"));
    }

    @Test void fixedCodecRejectsUnknownDuplicateTrailingAndScalarCoercion() {
        String valid = "{\"value\":{},\"conditions\":[],\"temporal\":{\"kind\":\"unknown\",\"from\":null,\"to\":null}}";
        for (String invalid : List.of(
                valid.replace("\"value\":{}", "\"value\":{},\"extra\":true"),
                valid.replace("\"conditions\":[]", "\"conditions\":[],\"conditions\":[]"),
                valid + " {}",
                valid.replace("\"conditions\":[]", "\"conditions\":[1]"),
                valid.replace("\"kind\":\"unknown\"", "\"kind\":1"))) {
            assertThrows(IllegalArgumentException.class, () -> MemoryContent.decode(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> MemoryJsonCodec.decode(
                "{\"kind\":\"interval\",\"from\":123,\"to\":null}", Temporal.class, "memory_extraction_temporal_invalid"));
        assertThrows(IllegalArgumentException.class, () -> contract.decodeInput("""
                {"schemaVersion":2,"userId":7.0,"sources":[{"id":"s0","text":"x","occurredAt":"2026-10-23T00:00:00Z"}],"existingFacts":[]}
                """));
    }
}
