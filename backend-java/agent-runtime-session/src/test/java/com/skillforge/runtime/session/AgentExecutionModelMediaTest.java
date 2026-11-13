package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.media.ImageDetail;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.FrozenModelRequest;
import com.skillforge.runtime.session.AgentExecutionModelMedia.MediaTransformation;
import com.skillforge.runtime.session.AgentExecutionModelMedia.Prepared;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 图片准备恢复是纯对应校验；不能用允许替换MediaRef扩大为修改历史消息。 */
class AgentExecutionModelMediaTest {
    private static final SessionRef OWNER = new SessionRef(new TenantId("1"), "session");
    private static final MediaRef SOURCE = image("source", 2400, 1600);
    private static final MediaRef VARIANT = image("variant", 1024, 683);
    private static final List<ModelMessage> ORIGINALS = List.of(message("original text", SOURCE, ImageDetail.HIGH));
    private static final List<MediaTransformation> MAPPINGS = List.of(new MediaTransformation(0, 1, SOURCE, VARIANT));
    private static final CodecLimits LIMITS = new CodecLimits(1_000_000, 100_000, 128);

    @Test
    void permitsOnlyTheExplicitImageSubstitutionAndRoundTripsWithoutReadingMedia() {
        var prepared = new Prepared(List.of(message("original text", VARIANT, ImageDetail.HIGH)), MAPPINGS);
        assertDoesNotThrow(() -> prepared.requireCorrespondence(ORIGINALS));
        var codec = new AgentExecutionCodec(LIMITS);
        var request = new AgentExecutionCodec.ModelCallRequest(request(prepared.messages()), MAPPINGS, 0, Optional.empty());
        var actual = codec.encodeModelRequest(request);
        assertEquals("skillforge.runtime.execution.prepared-model-request.v4", actual.schemaId());
        assertEquals(request, codec.decodeModelRequest(actual));
        assertDoesNotThrow(() -> codec.decodeModelRequest(actual).requireCorrespondence(ORIGINALS));
        var bundle=codec.encodeModelRequestMaterials(request);
        var parts=bundle.parts().stream().collect(java.util.stream.Collectors.toMap(
                com.skillforge.runtime.session.spi.ExecutionStore.Material::reference,java.util.function.Function.identity()));
        var restored=codec.decodeModelRequest(bundle.root().content(),refs->refs.stream()
                .map(ref->Optional.ofNullable(parts.get(ref))).toList());
        assertEquals(request,restored);
        assertDoesNotThrow(()->restored.requireCorrespondence(ORIGINALS));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeModelRequest(request.request().encode()),
                "原raw FrozenModelRequest不属于新封套，也不能作为兼容回退");
    }

    @Test
    void freezesKnowledgeSourcesWithTheRequestWhileLegacyMaterialsRemainExplicitlyUndeclared() {
        var codec = new AgentExecutionCodec(LIMITS);
        var source = new com.skillforge.runtime.api.KnowledgeSourceVersion("10", "source-version");
        var messages = List.of(new ModelMessage("user", ModelRole.USER, List.of(new ModelContent.Text("knowledge query")),
                List.of(), Optional.empty(), Optional.empty()));
        var current = new AgentExecutionCodec.ModelCallRequest(request(messages), List.of(), 0,
                Optional.empty(), Optional.empty(), List.of(source));
        var materials = codec.encodeModelRequestMaterials(current);
        var parts = materials.parts().stream().collect(java.util.stream.Collectors.toMap(
                com.skillforge.runtime.session.spi.ExecutionStore.Material::reference, java.util.function.Function.identity()));
        var restored = codec.decodeModelRequest(materials.root().content(), refs -> refs.stream().map(ref -> Optional.ofNullable(parts.get(ref))).toList());
        assertEquals(current, restored);
        assertTrue(restored.knowledgeSourcesDeclared());
        assertEquals(List.of(source), restored.knowledgeSources());
        var full = codec.encodeModelRequest(current);
        var missing = (com.fasterxml.jackson.databind.node.ObjectNode) codec.parse(full, full.schemaId());
        missing.remove("knowledgeSources");
        assertThrows(IllegalArgumentException.class, () -> codec.decodeModelRequest(codec.encode(full.schemaId(), missing)));
        var legacy = new AgentExecutionCodec.ModelCallRequest(request(messages), List.of(), 0,
                Optional.empty(), Optional.empty(), List.of(), false);
        var legacyPayload = codec.encodeModelRequest(legacy);
        assertEquals("skillforge.runtime.execution.prepared-model-request.v3", legacyPayload.schemaId());
        assertEquals(legacyPayload, codec.encodeModelRequest(codec.decodeModelRequest(legacyPayload)));
        var oldMaterials = codec.encodeModelRequestMaterials(legacy);
        assertEquals("skillforge.runtime.execution.model-request-manifest.v2", oldMaterials.root().content().schemaId());
        var oldParts = oldMaterials.parts().stream().collect(java.util.stream.Collectors.toMap(
                com.skillforge.runtime.session.spi.ExecutionStore.Material::reference, java.util.function.Function.identity()));
        var oldRead = codec.decodeModelRequest(oldMaterials.root().content(), refs -> refs.stream().map(ref -> Optional.ofNullable(oldParts.get(ref))).toList());
        assertFalse(oldRead.knowledgeSourcesDeclared());
        assertEquals(oldMaterials.root(), codec.encodeModelRequestMaterials(oldRead).root());
    }

    @Test
    void rejectsChangedTextMetadataImageDetailAndFalseSource() {
        assertThrows(IllegalArgumentException.class, () -> new Prepared(List.of(message("tampered", VARIANT, ImageDetail.HIGH)), MAPPINGS).requireCorrespondence(ORIGINALS));
        assertThrows(IllegalArgumentException.class, () -> new Prepared(List.of(message("original text", VARIANT, ImageDetail.LOW)), MAPPINGS).requireCorrespondence(ORIGINALS));
        var original = ORIGINALS.getFirst();
        var renamed = new ModelMessage("other-id", original.role(), message("original text", VARIANT, ImageDetail.HIGH).contents(),
                original.toolCalls(), original.toolCallId(), original.reasoningContent());
        assertThrows(IllegalArgumentException.class, () -> new Prepared(List.of(renamed), MAPPINGS).requireCorrespondence(ORIGINALS));
        var falseSource = List.of(new MediaTransformation(0, 1, image("another-source", 2400, 1600), VARIANT));
        assertThrows(IllegalArgumentException.class, () -> new Prepared(List.of(message("original text", VARIANT, ImageDetail.HIGH)), falseSource).requireCorrespondence(ORIGINALS));
    }

    @Test
    void rejectsMissingExtraDuplicateOrMispositionedMappingsAndCrossOwnerReferences() {
        var messages = List.of(message("original text", VARIANT, ImageDetail.HIGH));
        assertThrows(IllegalArgumentException.class, () -> new Prepared(messages, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Prepared(messages, List.of(MAPPINGS.getFirst(), MAPPINGS.getFirst())));
        assertThrows(IllegalArgumentException.class, () -> new Prepared(messages, List.of(new MediaTransformation(0, 0, SOURCE, VARIANT))));
        assertThrows(IllegalArgumentException.class, () -> new Prepared(messages, List.of(new MediaTransformation(1, 1, SOURCE, VARIANT))));
        var other = new MediaRef(new SessionRef(new TenantId("1"), "other-session"), "variant", "0".repeat(64), "image/png", 100, 10, 10);
        assertThrows(IllegalArgumentException.class, () -> new MediaTransformation(0, 1, SOURCE, other));
    }

    @Test
    void preservesToolPairingReasoningAndOrdering() {
        var call = new ModelToolCall("call", "native-tool", new com.skillforge.runtime.api.TypedPayload("application/json", "{}"));
        var assistant = new ModelMessage("assistant", ModelRole.ASSISTANT, List.of(), List.of(call), Optional.empty(), Optional.of("reasoning"));
        var tool = new ModelMessage("tool", ModelRole.TOOL, List.of(new ModelContent.Text("tool text"), new ModelContent.Image(SOURCE, ImageDetail.HIGH)),
                List.of(), Optional.of("call"), Optional.empty());
        var preparedTool = new ModelMessage("tool", ModelRole.TOOL, List.of(new ModelContent.Text("tool text"), new ModelContent.Image(VARIANT, ImageDetail.HIGH)),
                List.of(), Optional.of("call"), Optional.empty());
        var mappings = List.of(new MediaTransformation(1, 1, SOURCE, VARIANT));
        var prepared = new Prepared(List.of(assistant, preparedTool), mappings);
        assertDoesNotThrow(() -> prepared.requireCorrespondence(List.of(assistant, tool)));
        var alteredAssistant = new ModelMessage("assistant", ModelRole.ASSISTANT, List.of(), List.of(call), Optional.empty(), Optional.of("changed"));
        assertThrows(IllegalArgumentException.class, () -> new Prepared(List.of(alteredAssistant, preparedTool), mappings).requireCorrespondence(List.of(assistant, tool)));
        var alteredPair = new ModelMessage("tool", ModelRole.TOOL, preparedTool.contents(), List.of(), Optional.of("other-call"), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> new Prepared(List.of(assistant, alteredPair), mappings).requireCorrespondence(List.of(assistant, tool)));
    }

    private static MediaRef image(String id, int width, int height) {
        return new MediaRef(OWNER, id, "0".repeat(64), "image/png", 100, width, height);
    }

    private static ModelMessage message(String text, MediaRef image, ImageDetail detail) {
        return new ModelMessage("user", ModelRole.USER, List.of(new ModelContent.Text(text), new ModelContent.Image(image, detail)),
                List.of(), Optional.empty(), Optional.empty());
    }

    private static FrozenModelRequest request(List<ModelMessage> messages) {
        var target = new FrozenModelTarget(new ModelBindingRef(OWNER.tenantId(), "binding", 1), "model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false, false, new ResultRef("capabilities"),
                        new ModelImagePolicy(true, true, 4, 1_048_576, 4_194_304, 1024, 512, 85, 170, "test-image.v1")),
                Duration.ofSeconds(30), new ResultRef("resolution"));
        return new FrozenModelRequest(new RequestedModelStrategy("strategy", "1"), target, messages,
                new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "text"),
                new GenerationOptions(OptionalInt.of(100), OptionalDouble.empty()), ReasoningPolicy.DISABLED);
    }
}
