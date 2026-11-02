package com.skillforge.core.capability.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.session.ExecutionCapabilityInspector.Availability;
import com.skillforge.runtime.session.ExecutionCapabilityInspector.Inspection;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemorySearchObservationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void builtinKeepsOnlyHistoricalMetadataAndNeverReplaysFactsOrArbitraryStrings() throws Exception {
        var original = inspection(builtin(), """
                {"success":true,"query_id":"PRIVATE_QUERY_ID","retrieval_event_id":"PRIVATE_SOURCE",
                 "as_of":"PRIVATE_TIMESTAMP","retrieval_mode":"hybrid","retrieval_status":"normal",
                 "facts":[{"fact_id":"PRIVATE_FACT","revision":7,"scope":"USER","subject":"PRIVATE_SUBJECT",
                   "predicate":"PRIVATE_PREDICATE","object":{"text":"PRIVATE_FORGOTTEN_BODY"}},
                  {"subject":"PRIVATE_REVOKED_SUBJECT","object":{"text":"PRIVATE_REVOKED_BODY"}}],
                 "notice":"PRIVATE_NOTICE","unexpected":"PRIVATE_EXTRA"}
                """);

        var projected = MemorySearchObservation.restrictHistoricalOutput(original);
        var output = json.readTree(projected.output().orElseThrow().canonicalJson());

        assertEquals(Availability.PARTIAL, projected.outputAvailability());
        assertEquals(original.input(), projected.input());
        assertEquals(Availability.FULL, projected.inputAvailability());
        assertEquals("当前受控查询", json.readTree(projected.input().canonicalJson()).path("query").textValue());
        assertTrue(output.path("success").booleanValue());
        assertEquals("hybrid", output.path("retrieval_mode").textValue());
        assertEquals("normal", output.path("retrieval_status").textValue());
        assertEquals(2, output.path("recorded_fact_count").intValue());
        assertEquals("HISTORICAL_METADATA", output.path("content_scope").textValue());
        assertEquals("CURRENT_SOURCE_AUTHORIZATION_REQUIRED", output.path("facts_availability").textValue());
        assertFalse(output.toString().contains("PRIVATE"));
        assertFalse(output.has("facts"));
        assertFalse(output.has("subject"));
        assertFalse(output.has("predicate"));
        assertFalse(output.has("object"));
    }

    @Test
    void logicalFailureAndRuntimeUnknownRemainRecordedWithoutInventingSuccessOrZeroHits() throws Exception {
        var failed = json.readTree(MemorySearchObservation.restrictHistoricalOutput(
                inspection(builtin(), "{\"success\":false,\"error\":\"PRIVATE_FAILURE\"}"))
                .output().orElseThrow().canonicalJson());
        assertFalse(failed.path("success").booleanValue());
        assertFalse(failed.has("recorded_fact_count"));
        assertFalse(failed.has("error"));
        for (String status : List.of("FAILED", "OUTCOME_UNKNOWN", "INPUT_REQUIRED")) {
            var controlled = json.readTree(MemorySearchObservation.restrictHistoricalOutput(
                    statusInspection("{\"status\":\"" + status + "\",\"reason_code\":\"PRIVATE_FAILURE\"}"))
                    .output().orElseThrow().canonicalJson());
            assertEquals(status, controlled.path("outcome_status").textValue());
            assertFalse(controlled.has("success"));
            assertFalse(controlled.has("recorded_fact_count"));
            assertFalse(controlled.toString().contains("PRIVATE"));
        }
    }

    @Test
    void unrecognizedValuesAreOmittedRatherThanConvertedIntoSuccessfulMetadata() throws Exception {
        var output = json.readTree(MemorySearchObservation.restrictHistoricalOutput(inspection(builtin(), """
                {"success":"true","retrieval_mode":"PRIVATE_MODE","retrieval_status":"PRIVATE_STATUS",
                 "status":"PRIVATE_OUTCOME","facts":"PRIVATE_FACTS"}
                """)).output().orElseThrow().canonicalJson());
        assertFalse(output.has("success"));
        assertFalse(output.has("retrieval_mode"));
        assertFalse(output.has("retrieval_status"));
        assertFalse(output.has("outcome_status"));
        assertFalse(output.has("recorded_fact_count"));
        assertFalse(output.toString().contains("PRIVATE"));
    }

    @Test
    void exactFrozenBindingPreservesOtherToolsIncludingMcpWithTheSameWireName() {
        for (var binding : List.of(new ToolBinding(ToolBinding.ProviderType.MCP, "search_memory", "search_memory"),
                new ToolBinding(ToolBinding.ProviderType.PLATFORM, "other", "search_memory"),
                new ToolBinding(ToolBinding.ProviderType.PLATFORM, "search_memory", "other"),
                new ToolBinding(ToolBinding.ProviderType.PLATFORM, "other", "other"))) {
            var original = inspection(binding, "{\"answer\":\"普通工具的已授权正文\"}");
            assertSame(original, MemorySearchObservation.restrictHistoricalOutput(original));
        }
    }

    @Test
    void missingResultStaysNotRecorded() {
        var original = new Inspection("call", "search_memory", new TypedPayload("application/json", "{}"),
                Optional.empty(), Availability.FULL, Availability.NOT_RECORDED, List.of(), Optional.empty(), Optional.of(builtin()));
        assertSame(original, MemorySearchObservation.restrictHistoricalOutput(original));
    }

    private static ToolBinding builtin() {
        return new ToolBinding(ToolBinding.ProviderType.PLATFORM, "search_memory", "search_memory");
    }

    private static Inspection inspection(ToolBinding binding, String output) {
        return new Inspection("call", "search_memory", new TypedPayload("application/json", "{\"query\":\"当前受控查询\"}"),
                Optional.of(new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                        "{\"content\":[{\"type\":\"text\",\"text\":\"PRIVATE_FREE_TEXT\"}],\"structuredContent\":" + output + "}")), Availability.FULL, Availability.FULL,
                List.of(), Optional.empty(), Optional.of(binding));
    }

    private static Inspection statusInspection(String output) {
        return new Inspection("call", "search_memory", new TypedPayload("application/json", "{\"query\":\"当前受控查询\"}"),
                Optional.of(new TypedPayload("application/json", output)), Availability.FULL, Availability.FULL,
                List.of(), Optional.empty(), Optional.of(builtin()));
    }
}
