package com.skillforge.runtime.adapter.jdbc.memory.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.MemoryRetrievalPolicy.EmbeddingModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 只读取已经采集的真实向量文件，不持有凭据、不调用供应商，也不按相关标签生成向量。 */
final class SemanticMemoryLiveVectors {
    private final byte[] vectorBytes;
    private final byte[] inputBytes;
    private final String vectorsHash;
    private final String inputHash;
    private final JsonNode modelEvidence;
    private final JsonNode usage;
    private final JsonNode providerEvidence;
    private final EmbeddingModel model;
    private final Map<Text, List<Double>> byText;

    private SemanticMemoryLiveVectors(byte[] vectorBytes, byte[] inputBytes, String vectorsHash, String inputHash,
            JsonNode modelEvidence, JsonNode usage, JsonNode providerEvidence, EmbeddingModel model, Map<Text, List<Double>> byText) {
        this.vectorBytes = vectorBytes.clone(); this.inputBytes = inputBytes.clone(); this.vectorsHash = vectorsHash; this.inputHash = inputHash;
        this.modelEvidence = modelEvidence; this.usage = usage; this.providerEvidence = providerEvidence;
        this.model = model; this.byText = Map.copyOf(byText);
    }

    static Optional<SemanticMemoryLiveVectors> configured(Path repository, ObjectMapper json) throws Exception {
        String file = System.getenv("SEMANTIC_B_LIVE_EMBEDDINGS_FILE");
        if (file == null || file.isBlank()) return Optional.empty();
        return Optional.of(load(Path.of(file).toAbsolutePath().normalize(),
                repository.resolve(".runtime-logs/semantic-b-live-vector-input.json"),
                repository.resolve("tools/memory_evaluation/semantic-b"), json));
    }

    /** 冻结原始manifest、当前语料、逐文本摘要、精确模型版本和每条向量维度。 */
    static SemanticMemoryLiveVectors load(Path vectorsFile, Path inputFile, Path suites, ObjectMapper json) throws Exception {
        byte[] inputBytes = Files.readAllBytes(inputFile), vectorBytes = Files.readAllBytes(vectorsFile);
        JsonNode input = json.readTree(inputBytes), output = json.readTree(vectorBytes);
        require(input.path("schemaVersion").isIntegralNumber() && input.path("schemaVersion").canConvertToInt() && input.path("schemaVersion").asInt() == 1
                && output.path("schemaVersion").isIntegralNumber() && output.path("schemaVersion").canConvertToInt() && output.path("schemaVersion").asInt() == 1, "schema_version");
        String inputHash = digest(inputBytes);
        require(inputHash.equals(output.path("inputManifestSha256").asText()), "input_manifest_digest");
        var expected = new LinkedHashMap<String, Text>();
        for (String split : List.of("dev", "heldout")) {
            byte[] bytes = Files.readAllBytes(suites.resolve(split + ".json"));
            require(digest(bytes).equals(input.path("suites").path(split).path("sha256").asText()), "suite_digest");
            JsonNode suite = json.readTree(bytes);
            for (String kind : List.of("fact", "query")) {
                String collection = "fact".equals(kind) ? "facts" : "queries", field = "fact".equals(kind) ? "body" : "text";
                for (var item : suite.path(collection)) {
                    require(item.path(field).isTextual(), "corpus_text_missing");
                    String id = split + ":" + kind + ":" + item.path("id").asText();
                    require(expected.putIfAbsent(id, new Text("fact".equals(kind) ? "DOCUMENT" : "QUERY", item.path(field).asText())) == null,
                            "corpus_text_identity_duplicate");
                }
            }
        }
        require(input.path("texts").isArray() && input.path("texts").size() == expected.size(), "manifest_text_count");
        var manifestIds = new HashSet<String>();
        for (var item : input.path("texts")) {
            String id = item.path("textId").asText(); Text text = expected.get(id);
            require(text != null && manifestIds.add(id), "manifest_text_identity");
            require(text.kind().equals(item.path("kind").asText()) && text.value().equals(item.path("text").asText()), "manifest_text_changed");
            require(digest(text.value().getBytes(StandardCharsets.UTF_8)).equals(item.path("textSha256").asText()), "manifest_text_digest");
        }
        JsonNode metadata = output.path("model");
        require(metadata.isObject() && text(metadata, "provider") && text(metadata, "modelId") && text(metadata, "modelConfigId"), "model_identity");
        require(metadata.path("modelRevision").isIntegralNumber() && metadata.path("modelRevision").canConvertToLong()
                && metadata.path("modelRevision").asLong() > 0,
                "model_revision");
        require(metadata.path("dimensions").isIntegralNumber() && metadata.path("dimensions").canConvertToInt()
                && metadata.path("dimensions").asInt() > 0, "model_dimensions");
        var model = new EmbeddingModel(metadata.path("modelConfigId").asText(), metadata.path("modelRevision").asLong(),
                metadata.path("dimensions").asInt());
        require(output.path("usage").isObject() && !output.path("usage").isEmpty(), "usage_evidence_missing");
        require(output.path("evidence").isObject() && !output.path("evidence").isEmpty(), "provider_evidence_missing");
        require(output.path("vectors").isArray() && output.path("vectors").size() == expected.size(), "vector_count");
        var vectorIds = new HashSet<String>(); var byText = new HashMap<Text, List<Double>>();
        for (var item : output.path("vectors")) {
            String id = item.path("textId").asText(); Text text = expected.get(id);
            require(text != null && vectorIds.add(id), "vector_text_identity");
            require(digest(text.value().getBytes(StandardCharsets.UTF_8)).equals(item.path("textSha256").asText()), "vector_text_digest");
            if (item.has("modelConfigId")) require(item.path("modelConfigId").isTextual()
                    && model.modelConfigId().equals(item.path("modelConfigId").asText()), "vector_model_identity");
            if (item.has("modelRevision")) require(item.path("modelRevision").isIntegralNumber()
                    && item.path("modelRevision").canConvertToLong() && model.revision() == item.path("modelRevision").asLong(), "vector_model_revision");
            JsonNode raw = item.path("vector"); require(raw.isArray() && raw.size() == model.dimensions(), "vector_dimensions");
            var vector = new ArrayList<Double>(); boolean nonzero = false;
            for (var value : raw) {
                require(value.isNumber() && Double.isFinite(value.asDouble()), "vector_nonfinite");
                double coordinate = value.asDouble(); vector.add(coordinate); nonzero |= coordinate != 0;
            }
            require(nonzero, "vector_zero_norm");
            var previous = byText.putIfAbsent(text, List.copyOf(vector));
            require(previous == null || previous.equals(vector), "same_text_vector_changed");
        }
        return new SemanticMemoryLiveVectors(vectorBytes, inputBytes, digest(vectorBytes), inputHash,
                metadata.deepCopy(), output.path("usage").deepCopy(), output.path("evidence").deepCopy(), model, byText);
    }

    MemoryRetrievalPolicy policy() {
        return new MemoryRetrievalPolicy("memory-real-provider-vector-evaluation", "1", MemorySearchDocument.VERSION,
                MemorySearchAnalyzer.VERSION, 64, 60, 0.7, Optional.of(model), MemoryRetrievalPolicy.VectorFailurePolicy.FAIL);
    }

    List<Double> vector(String kind, String exactText, EmbeddingModel requestedModel) {
        require(model.equals(requestedModel), "request_model_configuration_changed");
        List<Double> result = byText.get(new Text(kind, exactText));
        require(result != null, "request_text_missing_or_changed"); return result;
    }

    Map<String, Object> evidence() {
        return Map.of("vectorFileSha256", vectorsHash, "inputManifestSha256", inputHash, "model", modelEvidence,
                "usage", usage, "providerEvidence", providerEvidence,
                "queryEmbeddingInvocation", "PRECOMPUTED_FILE_NO_PROVIDER_CALL_IN_JDBC_TEST");
    }
    void copyEvidence(Path destination) throws Exception {
        Files.write(destination.resolve("live-provider-vectors.json"), vectorBytes, java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.write(destination.resolve("live-vector-input.json"), inputBytes, java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    private record Text(String kind, String value) { }
    private static boolean text(JsonNode node, String field) { return node.path(field).isTextual() && !node.path(field).asText().isBlank(); }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void require(boolean valid, String code) {
        if (!valid) throw new IllegalArgumentException("semantic_live_vectors_" + code);
    }
}
