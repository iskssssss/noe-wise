package com.skillforge.runtime.adapter.jdbc.memory.search;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.api.MemoryRetrievalPolicy.EmbeddingModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 只验证文件协议的人工向量夹具；不生成或宣称真实 Provider 语义证据。 */
class SemanticMemoryLiveVectorsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EmbeddingModel MODEL = new EmbeddingModel("protocol-fixture", 7, 2);
    @TempDir Path temporary;

    @Test
    void loadsExactTextAndModelAndCopiesUnchangedEvidence() throws Exception {
        var fixture = fixture(); var vectors = fixture.load();
        assertEquals(MODEL, vectors.policy().embeddingModel().orElseThrow());
        assertEquals(List.of(0.25, 0.75), vectors.vector("DOCUMENT", "dev 正文\n原始空格 ", MODEL));
        assertEquals(List.of(0.25, 0.75), vectors.vector("QUERY", "heldout 查询", MODEL));
        Path destination = Files.createDirectory(temporary.resolve("evidence"));
        vectors.copyEvidence(destination);
        assertArrayEquals(Files.readAllBytes(fixture.output()), Files.readAllBytes(destination.resolve("live-provider-vectors.json")));
        assertArrayEquals(Files.readAllBytes(fixture.input()), Files.readAllBytes(destination.resolve("live-vector-input.json")));
        assertEquals(digest(Files.readAllBytes(fixture.output())), vectors.evidence().get("vectorFileSha256"));
    }

    @Test
    void rejectsDifferentManifestOrChangedCorpusEvenWhenDecodedTextMatches() throws Exception {
        var fixture = fixture(); ObjectNode output = fixture.outputJson();
        output.put("inputManifestSha256", "different"); fixture.saveOutput(output);
        rejected("input_manifest_digest", fixture);
        output.put("inputManifestSha256", digest(Files.readAllBytes(fixture.input()))); fixture.saveOutput(output);
        Files.writeString(fixture.suites().resolve("dev.json"), "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        rejected("suite_digest", fixture);
    }

    @Test
    void rejectsManifestTextMutationAndVectorDigestMutation() throws Exception {
        var fixture = fixture(); ObjectNode input = (ObjectNode) JSON.readTree(fixture.input().toFile());
        ((ObjectNode) input.withArray("texts").get(0)).put("text", "trimmed or changed");
        Files.write(fixture.input(), JSON.writeValueAsBytes(input));
        ObjectNode output = fixture.outputJson(); output.put("inputManifestSha256", digest(Files.readAllBytes(fixture.input())));
        fixture.saveOutput(output); rejected("manifest_text_changed", fixture);

        var second = fixture(); output = second.outputJson();
        ((ObjectNode) output.withArray("vectors").get(0)).put("textSha256", "changed");
        second.saveOutput(output); rejected("vector_text_digest", second);
    }

    @Test
    void rejectsMissingDuplicateAndUnknownVectorIdentity() throws Exception {
        var fixture = fixture(); ObjectNode output = fixture.outputJson(); output.withArray("vectors").remove(0);
        fixture.saveOutput(output); rejected("vector_count", fixture);
        var duplicate = fixture(); output = duplicate.outputJson();
        output.withArray("vectors").set(1, output.withArray("vectors").get(0).deepCopy());
        duplicate.saveOutput(output); rejected("vector_text_identity", duplicate);
        var unknown = fixture(); output = unknown.outputJson();
        ((ObjectNode) output.withArray("vectors").get(0)).put("textId", "dev:fact:unknown");
        unknown.saveOutput(output); rejected("vector_text_identity", unknown);
    }

    @Test
    void rejectsWrongDimensionNonfiniteAndZeroVectors() throws Exception {
        var fixture = fixture(); ObjectNode output = fixture.outputJson(); firstVector(output).remove(0);
        fixture.saveOutput(output); rejected("vector_dimensions", fixture);
        var nonfinite = fixture(); output = nonfinite.outputJson();
        firstVector(output).removeAll().add("NaN").add(1.0);
        nonfinite.saveOutput(output); rejected("vector_nonfinite", nonfinite);
        var zero = fixture(); output = zero.outputJson(); firstVector(output).removeAll().add(0.0).add(0.0);
        zero.saveOutput(output); rejected("vector_zero_norm", zero);
    }

    @Test
    void rejectsPerRowModelMismatchAndMissingProviderEvidence() throws Exception {
        var fixture = fixture(); ObjectNode output = fixture.outputJson();
        ((ObjectNode) output.withArray("vectors").get(0)).put("modelRevision", MODEL.revision() + 1);
        fixture.saveOutput(output); rejected("vector_model_revision", fixture);
        var missing = fixture(); output = missing.outputJson(); output.putObject("evidence");
        missing.saveOutput(output); rejected("provider_evidence_missing", missing);
    }

    @Test
    void rejectsChangedRequestModelKindOrTextInsteadOfSilentlyGeneratingVectors() throws Exception {
        var vectors = fixture().load();
        assertEquals("semantic_live_vectors_request_model_configuration_changed", assertThrows(IllegalArgumentException.class,
                () -> vectors.vector("QUERY", "dev 查询", new EmbeddingModel(MODEL.modelConfigId(), MODEL.revision() + 1, MODEL.dimensions()))).getMessage());
        assertEquals("semantic_live_vectors_request_text_missing_or_changed", assertThrows(IllegalArgumentException.class,
                () -> vectors.vector("QUERY", "dev 查询 ", MODEL)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> vectors.vector("DOCUMENT", "dev 查询", MODEL));
    }

    @Test
    void evidenceUsesTheValidatedBytesEvenIfSourceFileIsChangedLater() throws Exception {
        var fixture = fixture(); byte[] original = Files.readAllBytes(fixture.output()); var vectors = fixture.load();
        Files.writeString(fixture.output(), "changed after loading", StandardCharsets.UTF_8);
        Path destination = Files.createDirectory(temporary.resolve("captured-evidence")); vectors.copyEvidence(destination);
        assertArrayEquals(original, Files.readAllBytes(destination.resolve("live-provider-vectors.json")));
        assertEquals(digest(original), vectors.evidence().get("vectorFileSha256"));
    }

    private Fixture fixture() throws Exception {
        Path directory = Files.createTempDirectory(temporary, "protocol-");
        Path suites = Files.createDirectory(directory.resolve("suites")), input = directory.resolve("input.json"), output = directory.resolve("vectors.json");
        ObjectNode manifest = JSON.createObjectNode().put("schemaVersion", 1); ObjectNode suiteDigests = manifest.putObject("suites");
        ArrayNode texts = manifest.putArray("texts");
        for (String split : List.of("dev", "heldout")) {
            ObjectNode suite = JSON.createObjectNode();
            suite.putArray("facts").addObject().put("id", "fact").put("body", split + " 正文\n原始空格 ");
            suite.putArray("queries").addObject().put("id", "query").put("text", split + " 查询");
            byte[] bytes = JSON.writeValueAsBytes(suite); Files.write(suites.resolve(split + ".json"), bytes);
            suiteDigests.putObject(split).put("sha256", digest(bytes));
            for (String kind : List.of("fact", "query")) {
                String text = suite.path("fact".equals(kind) ? "facts" : "queries").get(0).path("fact".equals(kind) ? "body" : "text").asText();
                texts.addObject().put("textId", split + ":" + kind + ":" + kind).put("kind", "fact".equals(kind) ? "DOCUMENT" : "QUERY")
                        .put("text", text).put("textSha256", digest(text.getBytes(StandardCharsets.UTF_8)));
            }
        }
        Files.write(input, JSON.writeValueAsBytes(manifest));
        ObjectNode result = JSON.createObjectNode().put("schemaVersion", 1).put("inputManifestSha256", digest(Files.readAllBytes(input)));
        result.putObject("model").put("provider", "PROTOCOL_FIXTURE_NO_PROVIDER_CALL").put("modelId", "synthetic-test-values")
                .put("modelConfigId", MODEL.modelConfigId()).put("modelRevision", MODEL.revision()).put("dimensions", MODEL.dimensions());
        result.putObject("usage").put("status", "NOT_RUN"); result.putObject("evidence").put("purpose", "FILE_PROTOCOL_TEST_ONLY");
        ArrayNode rows = result.putArray("vectors");
        for (var text : texts) rows.addObject().put("textId", text.path("textId").asText()).put("textSha256", text.path("textSha256").asText())
                .putArray("vector").add(0.25).add(0.75);
        Files.write(output, JSON.writeValueAsBytes(result)); return new Fixture(output, input, suites);
    }

    private static ArrayNode firstVector(ObjectNode output) { return (ArrayNode) output.withArray("vectors").get(0).path("vector"); }
    private static void rejected(String reason, Fixture fixture) {
        assertEquals("semantic_live_vectors_" + reason, assertThrows(IllegalArgumentException.class, fixture::load).getMessage());
    }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private record Fixture(Path output, Path input, Path suites) {
        SemanticMemoryLiveVectors load() throws Exception { return SemanticMemoryLiveVectors.load(output, input, suites, JSON); }
        ObjectNode outputJson() throws Exception { return (ObjectNode) JSON.readTree(output.toFile()); }
        void saveOutput(ObjectNode json) throws Exception { Files.write(output, JSON.writeValueAsBytes(json)); }
    }
}
