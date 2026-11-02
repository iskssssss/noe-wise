package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.knowledge.PlatformKnowledgeModelProbe;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.model.embedding.EmbeddingClient;
import com.skillforge.core.model.embedding.PlatformModelHttpSupport;
import com.skillforge.core.shared.resilience.ResilienceFixtures;
import com.skillforge.core.foundation.application.CryptoService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 显式供应商样本采集；真实 HTTP/解码与用量，账本和预算接线保持受控并如实标注。 */
class SemanticEmbeddingProviderCapture {
    private final ObjectMapper json = new ObjectMapper();
    private static final String MODEL = "qwen3.7-text-embedding-flash";

    @Test
    void capturesOnlyTheApprovedArtificialRetrievalCorpus() throws Exception {
        String key = System.getenv("SEMANTIC_B_EMBEDDING_API_KEY");
        assertNotNull(key, "需要显式提供本轮供应商凭据");
        assertFalse(key.isBlank());
        Path input = Path.of(System.getenv("SEMANTIC_B_EMBEDDING_INPUT"));
        Path output = Path.of(System.getenv("SEMANTIC_B_EMBEDDING_OUTPUT"));
        Files.createDirectories(output);
        byte[] raw = Files.readAllBytes(input);
        String inputHash = digest(raw);
        JsonNode manifest = json.readTree(raw);
        var texts = manifest.path("texts");
        assertTrue(texts.isArray() && texts.size() <= 40, "采集仅限已批准的小样本");
        var crypto = mock(CryptoService.class);
        when(crypto.decrypt("capture-only-credential-reference")).thenReturn(key);
        var http = new PlatformModelHttpSupport(crypto);
        Path probePath = output.resolve("probe.json");
        JsonNode probe;
        if (Files.exists(probePath)) {
            probe = json.readTree(Files.readAllBytes(probePath));
            assertEquals("READY", probe.path("state").asText(), "原探测未确认完成，不自动重发");
            assertEquals(inputHash, probe.path("inputManifestSha256").asText());
        } else {
            writeNew(probePath, Map.of("state", "STARTED", "inputManifestSha256", inputHash));
            var result = new PlatformKnowledgeModelProbe(http).test(model(null));
            var resultValue = Map.of("state", result.ok() ? "READY" : "REJECTED",
                    "inputManifestSha256", inputHash, "dimensions", result.dimensions() == null ? 0 : result.dimensions(),
                    "latencyMs", result.latencyMs(), "reason", result.error() == null ? "" : result.error(),
                    "usage", "NOT_REPORTED_BY_EXISTING_MODEL_PROBE");
            Files.writeString(probePath, json.writeValueAsString(resultValue), StandardCharsets.UTF_8);
            assertTrue(result.ok(), () -> "供应商连接探测失败: " + result.error());
            probe = json.valueToTree(resultValue);
        }
        int dimensions = probe.path("dimensions").asInt();
        assertTrue(dimensions > 0);
        var vectors = new ArrayList<Object>();
        var usages = new ArrayList<Object>();
        for (int start = 0; start < texts.size(); start += EmbeddingClient.MAX_BATCH_SIZE) {
            int end = Math.min(start + EmbeddingClient.MAX_BATCH_SIZE, texts.size());
            int batch = start / EmbeddingClient.MAX_BATCH_SIZE;
            Path receipt = output.resolve("batch-" + batch + ".json");
            JsonNode saved;
            if (Files.exists(receipt)) {
                saved = json.readTree(Files.readAllBytes(receipt));
                assertEquals(inputHash, saved.path("inputManifestSha256").asText());
                assertEquals("READY", saved.path("state").asText(), "原调用未确认完成，不自动重发");
            } else {
                String invocation = "semantic-b-provider:" + inputHash + ":" + batch;
                writeNew(receipt, Map.of("state", "STARTED", "invocationId", invocation, "inputManifestSha256", inputHash));
                var guard = mock(ExternalInvocationGuard.class);
                when(guard.invokeModel(anyString(), any(), any(), any())).thenAnswer(call -> {
                    ExternalInvocationGuard.ExternalCall<List<float[]>> action = call.getArgument(3);
                    return action.call();
                });
                var costs = mock(CostRecorder.class);
                var usage = new ArrayList<LlmUsage>();
                doAnswer(call -> { usage.add(call.getArgument(4)); return null; })
                        .when(costs).recordLlm(anyString(), any(), anyString(), anyString(), any(), anyInt(), any(), any());
                var client = new EmbeddingClient(http, ResilienceFixtures.withDefaultSettings(), costs, guard);
                var bodies = new ArrayList<String>();
                for (int i = start; i < end; i++) bodies.add(texts.get(i).path("text").asText());
                var actual = client.embedBatch("semantic-b-live", model(dimensions), invocation,
                        bodies, Duration.ofSeconds(30), () -> false);
                var captured = new ArrayList<Object>();
                if (actual instanceof EmbeddingClient.Outcome.Available available) {
                    var values = available.vectors();
                    assertEquals(bodies.size(), values.size());
                    for (int i = start; i < end; i++) {
                        var row = texts.get(i);
                        assertEquals(row.path("textSha256").asText(), digest(bodies.get(i - start).getBytes(StandardCharsets.UTF_8)));
                        captured.add(Map.of("textId", row.path("textId").asText(), "textSha256", row.path("textSha256").asText(), "vector", values.get(i - start)));
                    }
                }
                String state = actual instanceof EmbeddingClient.Outcome.Available ? "READY"
                        : actual instanceof EmbeddingClient.Outcome.OutcomeUnknown ? "UNKNOWN" : "REJECTED";
                var result = new LinkedHashMap<String, Object>();
                result.put("state", state); result.put("inputManifestSha256", inputHash);
                result.put("invocationId", invocation); result.put("vectors", captured);
                result.put("usage", usage); result.put("supplier", MODEL);
                Files.writeString(receipt, json.writeValueAsString(result), StandardCharsets.UTF_8);
                assertEquals("READY", state, "供应商未返回可采纳向量；原调用保留，不自动重试");
                saved = json.valueToTree(result);
            }
            saved.path("vectors").forEach(vectors::add);
            saved.path("usage").forEach(usages::add);
        }
        writeNew(output.resolve("vectors.json"), Map.of(
                "schemaVersion", 1, "inputManifestSha256", inputHash,
                "model", Map.of("provider", "bailian", "modelId", MODEL,
                        "modelConfigId", "semantic-b-live-bailian", "modelRevision", 1, "dimensions", dimensions),
                "evidence", Map.of("lane", "REAL_PROVIDER_HTTP_SHARED_EMBEDDING_CLIENT",
                        "http", "REAL", "decoding", "PRODUCTION_SHARED_CLIENT", "budgetAndLedger", "CONTROLLED_NOT_DATABASE",
                        "dimensionProbe", "EXISTING_PLATFORM_MODEL_PROBE", "newBusinessDeployment", false),
                "usage", Map.of("batches", usages, "probeUsage", "UNKNOWN_NOT_EXPOSED_BY_EXISTING_PROBE"),
                "vectors", vectors));
    }

    private static PlatformModelConfig model(Integer dimensions) {
        return new PlatformModelConfig("semantic-b-live-bailian", 1, MODEL, "EMBEDDING", true,
                "bailian", null, null, "openai-embeddings", "bearer-api-key", null,
                "https://dashscope.aliyuncs.com/compatible-mode/v1", MODEL, "capture-only-credential-reference",
                null, null, null, null, null, 30, dimensions, "/embeddings", null, null, null, "CNY");
    }
    private void writeNew(Path path, Object value) throws Exception {
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    private static String digest(byte[] raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    }
}
