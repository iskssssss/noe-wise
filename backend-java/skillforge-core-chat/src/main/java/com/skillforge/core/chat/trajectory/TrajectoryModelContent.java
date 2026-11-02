package com.skillforge.core.chat.trajectory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.runtime.api.port.ModelPort;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

/** 原始模型结果的明确读取投影；保留模型内容，不序列化执行绑定、连接凭据或结果能力引用。 */
final class TrajectoryModelContent {
    static final String INPUT_SCHEMA = "skillforge.trajectory.actual-model-request.v1";
    static final String OUTPUT_SCHEMA = "skillforge.trajectory.actual-model-outcome.v1";

    private TrajectoryModelContent() { }

    static Outcome outcome(ModelPort.ModelResult result) {
        if (result instanceof ModelPort.ModelResult.Completed completed) {
            List<AssistantPart> parts = completed.assistantTurn().map(turn -> turn.contents().stream().map(part -> {
                if (part instanceof ModelPort.AssistantContent.Text text)
                    return new AssistantPart("TEXT", text.text(), null, null, null);
                var call = (ModelPort.AssistantContent.ToolCall) part;
                return new AssistantPart("TOOL_CALL", null, call.callId(), call.wireName(), call.argumentsJson());
            }).toList()).orElse(null);
            var usage = completed.usage();
            return new Outcome("SUCCEEDED", completed.effectiveModelId(), completed.finishReasonCode(),
                    new Payload(completed.output().schemaId(), completed.output().canonicalJson()),
                    parts, completed.reasoningContent().orElse(null),
                    new Usage(decimal(usage.inputTokens()), decimal(usage.outputTokens()), decimal(usage.cachedInputTokens()),
                            decimal(usage.cacheWriteInputTokens()), decimal(usage.reasoningOutputTokens())),
                    null, null, null);
        }
        if (result instanceof ModelPort.ModelResult.Failed failed)
            return new Outcome("FAILED", null, null, null, null, null, null,
                    failed.failure().reasonCode(), failed.failure().retryMode().name(), failed.retryAt().orElse(null));
        var unknown = (ModelPort.ModelResult.OutcomeUnknown) result;
        return new Outcome("OUTCOME_UNKNOWN", null, null, null, null, null, null,
                unknown.failure().reasonCode(), unknown.failure().retryMode().name(), null);
    }

    private static String decimal(OptionalLong value) { return value.isPresent() ? Long.toString(value.getAsLong()) : null; }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Outcome(String status, String model, @JsonProperty("finish_reason") String finishReason,
                   Payload output, @JsonProperty("assistant_turn") List<AssistantPart> assistantTurn,
                   @JsonProperty("reasoning_content") String reasoningContent, Usage usage,
                   @JsonProperty("reason_code") String reasonCode, @JsonProperty("retry_mode") String retryMode,
                   @JsonProperty("retry_at") Instant retryAt) {
        @Override public String toString() { return "TrajectoryModelOutcome[REDACTED]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Payload(@JsonProperty("schema_id") String schemaId, @JsonProperty("canonical_json") String canonicalJson) {
        @Override public String toString() { return "TrajectoryModelPayload[REDACTED]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record AssistantPart(String type, String text, @JsonProperty("call_id") String callId,
                         @JsonProperty("wire_name") String wireName, @JsonProperty("arguments_json") String argumentsJson) {
        @Override public String toString() { return "TrajectoryModelAssistantPart[REDACTED]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Usage(@JsonProperty("input_tokens") String inputTokens, @JsonProperty("output_tokens") String outputTokens,
                 @JsonProperty("cached_input_tokens") String cachedInputTokens,
                 @JsonProperty("cache_write_input_tokens") String cacheWriteInputTokens,
                 @JsonProperty("reasoning_output_tokens") String reasoningOutputTokens) { }
}
