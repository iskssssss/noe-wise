package com.skillforge.core.capability.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MediaToolDefinitionsTest {
    private final ToolSchemaContributor schemas = new ToolSchemaContributor() {
        @Override public Map<String, Object> codePublicPresentationSchema() { return Map.of("type", "object"); }
        @Override public PlatformToolCatalog.Entry contextReferenceEntry() { throw new AssertionError("not used"); }
    };

    @Test void creationToolsDeclarePaidEffectsAndKeepTrustedModelIdentityOutOfArguments() {
        var json = new ObjectMapper();
        for (var entry : List.of(MediaToolDefinitions.generateImage(schemas), MediaToolDefinitions.generateVideo(schemas),
                MediaToolDefinitions.transcribeAudio(schemas), MediaToolDefinitions.synthesizeSpeech(schemas))) {
            assertEquals(ToolEffect.NON_IDEMPOTENT_WRITE, entry.effect());
            assertEquals(ToolCallIdentityPolicy.HANDLE, entry.callIdentityPolicy());
            assertFalse(entry.idempotent());
            assertFalse(entry.parallelSafe());
            var fields = json.valueToTree(entry.parametersSchema()).path("properties");
            for (String forbidden : List.of("model", "model_config_id", "api_key", "operation_id", "tenant_id", "run_id"))
                assertFalse(fields.has(forbidden), forbidden);
            assertTrue(entry.description().contains("UNKNOWN"));
        }
        var query = MediaToolDefinitions.queryJob(schemas);
        assertEquals(ToolEffect.IDEMPOTENT_WRITE, query.effect());
        assertTrue(query.idempotent());
        assertTrue(query.description().contains("不重新生成"));
    }

    @Test void sourceIdentityCannotMixAnExternalUrlWithAnOwnedAsset() throws Exception {
        var json = new ObjectMapper();
        var source = json.readValue("{\"job_id\":\"101\",\"asset_id\":\"102\"}", MediaToolDefinitions.Source.class);
        assertEquals("101", source.jobId());
        assertNull(source.url());
        assertThrows(IllegalArgumentException.class, () -> new MediaToolDefinitions.Source("https://example.cn/a.png", "101", "102"));
        assertThrows(IllegalArgumentException.class, () -> new MediaToolDefinitions.Source(null, "101", null));
        assertThrows(IllegalArgumentException.class, () -> new MediaToolDefinitions.Source(null, null, null));
    }

    @Test void sourceSchemaOffersOnlyCompleteMutuallyExclusiveFormsAndRejectsWithNullableJobId() {
        var json = new ObjectMapper();
        var entry = MediaToolDefinitions.generateImage(schemas);
        var alternatives = json.valueToTree(entry.parametersSchema()).path("properties")
                .path("reference_images").path("items").path("oneOf");
        assertEquals(2, alternatives.size());
        assertEquals(List.of("url"), json.convertValue(alternatives.get(0).path("required"), List.class));
        assertFalse(alternatives.get(0).path("properties").has("job_id"));
        assertFalse(alternatives.get(0).path("additionalProperties").asBoolean());
        assertEquals(List.of("job_id", "asset_id"), json.convertValue(alternatives.get(1).path("required"), List.class));
        assertFalse(alternatives.get(1).path("properties").has("url"));
        assertEquals(List.of("string", "null"), json.convertValue(
                json.valueToTree(entry.outputSchema()).path("properties").path("job_id").path("type"), List.class));
    }
}
