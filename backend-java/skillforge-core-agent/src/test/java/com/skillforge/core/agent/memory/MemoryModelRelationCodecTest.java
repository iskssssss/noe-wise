package com.skillforge.core.agent.memory;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.ExtractionInput;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.ExtractionOutput;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Relation;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Source;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 三份真实原始结果只在模型边界进行结构归一，不修补证据、目标或业务含义。 */
class MemoryModelRelationCodecTest {
    private final MemoryExtractionCandidates contract = new MemoryExtractionCandidates();
    private final ObjectMapper json = new ObjectMapper();

    @Test void capturedOutputsBecomeCanonicalRelationsWithoutChangingOriginalEvidence() throws Exception {
        for (String sample : List.of("CD03", "CD10", "CH10")) {
            CapturedOutput fixture = fixture(sample);
            assertEquals(fixture.rawOutputSha256(), sha256(fixture.rawOutput()));
            var candidates = contract.parse(fixture.rawOutput(), fixture.input());
            assertEquals("CD03".equals(sample) ? 1 : 2, candidates.size());
            assertEquals(new Relation("new", null), candidates.getFirst().relation());
            if (!"CD03".equals(sample)) {
                assertEquals(new Relation("similar", "CD10".equals(sample) ? "f-cny" : "f-remote"), candidates.getLast().relation());
            }
            String canonical = MemoryJsonCodec.encode(new ExtractionOutput(MemoryExtractionCandidates.SCHEMA_VERSION, candidates));
            assertEquals(canonical, MemoryJsonCodec.encode(MemoryJsonCodec.decode(canonical, ExtractionOutput.class,
                    "memory_extraction_json_invalid")));
            assertEquals(fixture.rawOutputSha256(), sha256(fixture.rawOutput()));
            // 正式持久结构仍严格，只有专属模型输出入口接受 flat relation。
            assertThrows(IllegalArgumentException.class, () -> MemoryJsonCodec.decode(fixture.rawOutput(),
                    ExtractionOutput.class, "memory_extraction_json_invalid"));
        }
    }

    @Test void doubleTargetsUnknownFieldsKindsAndMissingSimilarTargetRemainRejected() throws Exception {
        CapturedOutput fixture = fixture("CD10");
        for (String secondTarget : List.of("f-cny", "another-target")) {
            ObjectNode output = (ObjectNode) json.readTree(fixture.rawOutput());
            ObjectNode candidate = (ObjectNode) output.get(MemoryJsonCodec.ModelFields.CANDIDATES).get(1);
            candidate.set(MemoryJsonCodec.ModelFields.RELATION, MemoryJsonCodec.node(new Relation("similar", "f-cny")));
            candidate.put(MemoryJsonCodec.ModelFields.TARGET, secondTarget);
            assertThrows(IllegalArgumentException.class, () -> contract.parse(output.toString(), fixture.input()));
        }
        ObjectNode unknownKind = (ObjectNode) json.readTree(fixture.rawOutput());
        ((ObjectNode) unknownKind.get(MemoryJsonCodec.ModelFields.CANDIDATES).get(1))
                .put(MemoryJsonCodec.ModelFields.RELATION, "unknown_kind");
        assertThrows(IllegalArgumentException.class, () -> contract.parse(unknownKind.toString(), fixture.input()));
        ObjectNode missingTarget = (ObjectNode) json.readTree(fixture.rawOutput());
        ((ObjectNode) missingTarget.get(MemoryJsonCodec.ModelFields.CANDIDATES).get(1)).remove(MemoryJsonCodec.ModelFields.TARGET);
        assertThrows(IllegalArgumentException.class, () -> contract.parse(missingTarget.toString(), fixture.input()));
        ObjectNode unknownField = (ObjectNode) json.readTree(fixture.rawOutput());
        ((ObjectNode) unknownField.get(MemoryJsonCodec.ModelFields.CANDIDATES).get(1)).put("unrecognized_field", true);
        assertThrows(IllegalArgumentException.class, () -> contract.parse(unknownField.toString(), fixture.input()));
    }

    @Test void normalizedRelationStillRequiresTheOriginalSourceAndExactQuote() throws Exception {
        CapturedOutput fixture = fixture("CD10");
        ExtractionInput differentSource = contract.typedInput(fixture.input().userId(), fixture.input().sources().stream()
                .map(source -> new Source(source.id(), "unrelated source", source.occurredAt())).toList(), fixture.input().existingFacts());
        assertEquals("memory_extraction_evidence_invalid", assertThrows(IllegalArgumentException.class,
                () -> contract.parse(fixture.rawOutput(), differentSource)).getMessage());
    }

    private CapturedOutput fixture(String sample) throws Exception {
        try (var input = getClass().getResourceAsStream("/memory-model-output/" + sample + ".json")) {
            assertNotNull(input);
            return MemoryJsonCodec.decode(new String(input.readAllBytes(), StandardCharsets.UTF_8),
                    CapturedOutput.class, "memory_captured_fixture_invalid");
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** 固定测试材料从原 live-02 记录复制，SHA 对齐原 rawOutput，不依赖未跟踪运行目录。 */
    private record CapturedOutput(
            @JsonProperty(required = true) ExtractionInput input,
            @JsonProperty(required = true) String rawOutput,
            @JsonProperty(required = true) String rawOutputSha256) { }
}
