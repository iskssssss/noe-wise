package com.skillforge.core.model.invocation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** 实际Provider协议材料的唯一codec；从字段白名单采集，不序列化凭据、端点或执行函数。 */
public final class ModelCallRecordCodec {
    public static final String REQUEST_SCHEMA = "skillforge.model.actual-request.v1";
    public static final String OUTCOME_SCHEMA = "skillforge.model.actual-outcome.v1";
    private final ObjectMapper json = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public enum ResponseKind { TEXT, JSON_OBJECT, JSON_SCHEMA, NATIVE_CONVERSATION }
    public enum OutcomeKind { COMPLETED, REJECTED, OUTCOME_UNKNOWN }

    public record Content(String type, String text, MediaRef media, String detail) { }
    public record Message(ModelProvider.Role role, List<Content> contents, String toolCallId,
                          List<ModelProvider.ToolCall> toolCalls, String reasoningContent) {
        public Message { contents = List.copyOf(contents); toolCalls = List.copyOf(toolCalls); }
        @Override public String toString() { return "ModelCallMessage[REDACTED]"; }
    }
    public record Response(ResponseKind format, TypedPayload schema, ModelPort.EnforcementLevel enforcement,
                           String negotiationReasonCode) { }
    public record Generation(Integer maxOutputTokens, Double temperature) { }
    public record Request(String providerId, String modelId, ModelProvider.InvocationProfile profile,
                          List<Message> messages, Response response, Generation generation,
                          ModelPort.ReasoningPolicy reasoning, List<ModelProvider.ToolDefinition> tools,
                          ModelProvider.ToolChoice toolChoice, String timeout, long maximumRequestBytes,
                          String promptCacheScope) {
        public Request {
            Objects.requireNonNull(providerId); Objects.requireNonNull(modelId); Objects.requireNonNull(profile);
            messages = List.copyOf(messages); tools = List.copyOf(tools);
            Objects.requireNonNull(response); Objects.requireNonNull(generation); Objects.requireNonNull(reasoning);
            Objects.requireNonNull(toolChoice); Objects.requireNonNull(timeout);
        }
        @Override public String toString() { return "ModelCallRequest[REDACTED]"; }
    }
    public record Usage(Long inputTokens, Long outputTokens, Long cachedInputTokens,
                        Long cacheWriteInputTokens, Long reasoningOutputTokens) { }
    public record Outcome(OutcomeKind kind, String content, List<ModelProvider.ToolCall> toolCalls,
                          String modelId, Usage usage, String finishReasonCode, String reasoningContent,
                          String reasonCode, String retryAt) {
        public Outcome { Objects.requireNonNull(kind); toolCalls = List.copyOf(toolCalls); Objects.requireNonNull(usage); }
        @Override public String toString() { return "ModelCallOutcome[REDACTED]"; }
    }

    /** 原实际请求形成后、网络调用前采集；不从系统Run配置重建。 */
    public TypedPayload request(String providerId, ModelProvider.InvocationRequest actual) {
        var messages = actual.messages().stream().map(message -> new Message(message.role(),
                message.contents().stream().map(ModelCallRecordCodec::content).toList(), message.toolCallId(),
                message.toolCalls(), message.reasoningContent())).toList();
        var generation = actual.generation();
        return encode(REQUEST_SCHEMA, new Request(providerId, actual.modelId(), actual.profile(), messages,
                response(actual.response()), new Generation(generation.maxOutputTokens().isPresent()
                ? generation.maxOutputTokens().getAsInt() : null,
                generation.temperature().isPresent() ? generation.temperature().getAsDouble() : null),
                actual.reasoning(), actual.tools(), actual.toolChoice(), actual.timeout().toString(),
                actual.maximumRequestBytes(), actual.promptCacheScope().map(ModelProvider.PromptCacheScope::key).orElse(null)));
    }

    /** 保存实际返回联合，先于宿主对length、工具或领域内容的裁决。 */
    public TypedPayload outcome(ModelProvider.InvocationResult actual) {
        Outcome value;
        if (actual instanceof ModelProvider.InvocationResult.Completed completed) {
            value = new Outcome(OutcomeKind.COMPLETED, completed.content(), completed.toolCalls(), completed.modelId(),
                    usage(completed.usage()), completed.finishReasonCode(), completed.reasoningContent(), null, null);
        } else if (actual instanceof ModelProvider.InvocationResult.Rejected rejected) {
            value = new Outcome(OutcomeKind.REJECTED, null, List.of(), null, usage(rejected.usage()), null, null,
                    rejected.reasonCode(), rejected.retryAt().map(java.time.Instant::toString).orElse(null));
        } else {
            var unknown = (ModelProvider.InvocationResult.OutcomeUnknown) actual;
            value = new Outcome(OutcomeKind.OUTCOME_UNKNOWN, null, List.of(), null, usage(unknown.usage()), null, null,
                    unknown.reasonCode(), null);
        }
        return encode(OUTCOME_SCHEMA, value);
    }

    public Request decodeRequest(TypedPayload payload) { return decode(payload, REQUEST_SCHEMA, Request.class); }
    public Outcome decodeOutcome(TypedPayload payload) { return decode(payload, OUTCOME_SCHEMA, Outcome.class); }

    private static Content content(ModelContent part) {
        if (part instanceof ModelContent.Text text) return new Content("TEXT", text.text(), null, null);
        var image = (ModelContent.Image) part;
        return new Content("IMAGE", null, image.media(), image.detail().name());
    }
    private static Response response(ModelPort.ResponseContract actual) {
        var format = actual.format();
        ResponseKind kind = switch (format) {
            case ModelPort.ResponseFormat.Text ignored -> ResponseKind.TEXT;
            case ModelPort.ResponseFormat.JsonObject ignored -> ResponseKind.JSON_OBJECT;
            case ModelPort.ResponseFormat.JsonSchema ignored -> ResponseKind.JSON_SCHEMA;
            case ModelPort.ResponseFormat.NativeConversation ignored -> ResponseKind.NATIVE_CONVERSATION;
        };
        return new Response(kind, format instanceof ModelPort.ResponseFormat.JsonSchema schema ? schema.schema() : null,
                actual.enforcement(), actual.negotiationReasonCode());
    }
    private static Usage usage(ModelPort.ModelUsage actual) {
        return new Usage(number(actual.inputTokens()), number(actual.outputTokens()), number(actual.cachedInputTokens()),
                number(actual.cacheWriteInputTokens()), number(actual.reasoningOutputTokens()));
    }
    private static Long number(OptionalLong value) { return value.isPresent() ? value.getAsLong() : null; }
    private TypedPayload encode(String schema, Object value) {
        try { return new TypedPayload(schema, json.writeValueAsString(value)); }
        catch (Exception failure) { throw invalid(); }
    }
    private <T> T decode(TypedPayload payload, String schema, Class<T> type) {
        if (!schema.equals(payload.schemaId())) throw invalid();
        try {
            T value = json.readValue(payload.canonicalJson(), type);
            if (!encode(schema, value).equals(payload)) throw invalid();
            return value;
        } catch (Exception failure) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("model_call_material_invalid"); }
}
