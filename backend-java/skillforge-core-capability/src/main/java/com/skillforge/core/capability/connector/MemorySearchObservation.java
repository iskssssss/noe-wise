package com.skillforge.core.capability.connector;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.session.ExecutionCapabilityInspector.Availability;
import com.skillforge.runtime.session.ExecutionCapabilityInspector.Inspection;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import java.util.Optional;
import java.util.Set;

/** 内置记忆检索的历史观测不授予当前 Memory 来源权限，只公开有限元数据。 */
public final class MemorySearchObservation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> MODES = Set.of("lexical", "hybrid");
    private static final Set<String> RETRIEVAL_STATUSES = Set.of("normal", "degraded");
    private static final Set<String> OUTCOME_STATUSES = Set.of("FAILED", "OUTCOME_UNKNOWN", "INPUT_REQUIRED");

    private MemorySearchObservation() { }

    /**
     * 只消费 Runtime 已严格解码的真实冻结绑定及安全业务结果，不解析 Runtime 私有封套。
     * 会话读取资格不代表当前事实来源资格；遗忘、撤权后的历史事实也不能经此入口回放。
     */
    public static Inspection restrictHistoricalOutput(Inspection inspection) {
        if (inspection.toolBinding().filter(MemorySearchObservation::isBuiltin).isEmpty()
                || inspection.output().isEmpty()) return inspection;
        try {
            var payload = inspection.output().orElseThrow();
            JsonNode envelope = JSON.readTree(payload.canonicalJson());
            if (envelope == null || !envelope.isObject()) throw invalid();
            JsonNode recorded = switch (payload.schemaId()) {
                case SessionRuntimePayloads.TOOL_RESULT_SCHEMA -> envelope.path("structuredContent");
                case "application/json" -> envelope;
                default -> throw invalid();
            };
            var success = recorded.path(MemorySearchToolContract.SUCCESS);
            var facts = recorded.path(MemorySearchToolContract.FACTS);
            var metadata = new Output(success.isBoolean() ? success.booleanValue() : null,
                    known(recorded, MemorySearchToolContract.RETRIEVAL_MODE, MODES),
                    known(recorded, MemorySearchToolContract.RETRIEVAL_STATUS, RETRIEVAL_STATUSES),
                    facts.isArray() ? facts.size() : null,
                    known(recorded, "status", OUTCOME_STATUSES),
                    envelope.path("isError").isBoolean() ? envelope.path("isError").booleanValue() : null,
                    "HISTORICAL_METADATA", "CURRENT_SOURCE_AUTHORIZATION_REQUIRED");
            var output = new TypedPayload("application/json", JSON.writeValueAsString(metadata));
            return new Inspection(inspection.callId(), inspection.title(), inspection.input(), Optional.of(output),
                    inspection.inputAvailability(), Availability.PARTIAL, inspection.redactedFields(),
                    inspection.knowledgeAccess(), inspection.toolBinding());
        } catch (com.fasterxml.jackson.core.JsonProcessingException rejected) {
            throw invalid();
        }
    }

    private static boolean isBuiltin(ToolBinding binding) {
        return binding.providerType() == ToolBinding.ProviderType.PLATFORM
                && MemorySearchToolContract.NAME.equals(binding.providerRef())
                && MemorySearchToolContract.NAME.equals(binding.toolName());
    }

    private static String known(JsonNode value, String field, Set<String> allowed) {
        var candidate = value.path(field);
        return candidate.isTextual() && allowed.contains(candidate.textValue()) ? candidate.textValue() : null;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("memory_search_observation_invalid");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Output(Boolean success, @JsonProperty("retrieval_mode") String retrievalMode,
            @JsonProperty("retrieval_status") String retrievalStatus,
            @JsonProperty("recorded_fact_count") Integer recordedFactCount,
            @JsonProperty("outcome_status") String outcomeStatus,
            @JsonProperty("tool_error") Boolean toolError,
            @JsonProperty("content_scope") String contentScope,
            @JsonProperty("facts_availability") String factsAvailability) { }
}
