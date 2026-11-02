package com.skillforge.runtime.adapter.model.openai.responses;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.ResponseCreateParams;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.*;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import com.skillforge.runtime.api.port.ModelPort.NativeProtocol;
import com.skillforge.runtime.api.port.ModelPort.NativeProtocolState;
import com.skillforge.runtime.api.TypedPayload;
import java.io.IOException;
import java.util.*;

/** Responses 有序图文、原生函数和私有推理项协议；完整回放密文，不将其转换为公开正文。 */
final class OpenAiResponsesCodec {
    static final String DIALECT = "openai-responses-v1";
    static final String OUTPUT_ITEM_SCHEMA = "openai.responses.output-item.v1";
    static final ObjectMapper JSON = com.openai.core.ObjectMappers.jsonMapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> IMAGE_MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private OpenAiResponsesCodec() { }

    static byte[] encode(InvocationRequest request, boolean stream) throws IOException {
        return JSON.writeValueAsBytes(parameters(request, stream)._body());
    }

    static ResponseCreateParams parameters(InvocationRequest request, boolean stream) throws IOException {
        if (request.reasoning() == ReasoningPolicy.ENABLED) throw rejected("model_responses_reasoning_unsupported");
        validateImages(request);
        String effort = request.reasoning() == ReasoningPolicy.DEFAULT ? null
                : request.reasoning() == ReasoningPolicy.DISABLED ? "none" : request.reasoning().name().toLowerCase(Locale.ROOT);
        var input = JSON.createArrayNode(); var pending = new HashSet<String>(); var ids = new HashSet<String>();
        for (var message : request.messages()) {
            if (message.protocolState().isPresent()) {
                if (message.role() != Role.ASSISTANT || !pending.isEmpty() || message.toolCallId() != null)
                    throw rejected("model_responses_protocol_state_invalid");
                var state = message.protocolState().orElseThrow();
                if (state.protocol() != NativeProtocol.RESPONSES || !DIALECT.equals(state.dialectVersion()) || !request.modelId().equals(state.modelId()))
                    throw rejected("model_responses_protocol_state_invalid");
                var raw = JSON.createArrayNode();
                for (var item : state.items()) {
                    if (!OUTPUT_ITEM_SCHEMA.equals(item.schemaId())) throw rejected("model_responses_protocol_state_invalid");
                    raw.add(parse(item.canonicalJson()));
                }
                var replay = output(raw);
                if (!Objects.equals(replay.text().isEmpty() ? null : replay.text(), message.textContent().isEmpty() ? null : message.textContent())
                        || !replay.calls().equals(message.toolCalls())
                        || !Objects.equals(replay.reasoning().isEmpty() ? null : replay.reasoning(), message.reasoningContent()))
                    throw rejected("model_responses_protocol_state_invalid");
                for (var call : replay.calls()) {
                    if (!ids.add(call.callId())) throw rejected("model_responses_tool_history_invalid");
                    pending.add(call.callId());
                }
                for (var item : raw) input.add(item);
                continue;
            }
            if (message.reasoningContent() != null) throw rejected("model_responses_reasoning_history_unsupported");
            String text = message.textContent();
            if (message.role() == Role.TOOL) {
                if (message.toolCallId() == null || !pending.remove(message.toolCallId()) || !message.toolCalls().isEmpty())
                    throw rejected("model_responses_tool_history_invalid");
                var output = input.addObject().put("type", "function_call_output").put("call_id", message.toolCallId());
                if (message.contents().stream().anyMatch(ModelContent.Image.class::isInstance)) output.set("output", contents(request, message));
                else output.put("output", text);
                continue;
            }
            if (message.toolCallId() != null || !pending.isEmpty() && message.role() != Role.SYSTEM)
                throw rejected("model_responses_tool_history_invalid");
            if (!message.toolCalls().isEmpty() && message.role() != Role.ASSISTANT)
                throw rejected("model_responses_tool_history_invalid");
            if (!text.isEmpty() || message.contents().stream().anyMatch(ModelContent.Image.class::isInstance)) {
                String role = message.role().name().toLowerCase(Locale.ROOT);
                input.addObject().put("type", "message").put("role", role).set("content", contents(request, message));
            }
            for (var call : message.toolCalls()) {
                if (!ids.add(call.callId()) || !parse(call.argumentsJson()).isObject())
                    throw rejected("model_responses_tool_history_invalid");
                pending.add(call.callId());
                input.addObject().put("type", "function_call").put("call_id", call.callId()).put("name", call.name()).put("arguments", call.argumentsJson());
            }
        }
        if (!pending.isEmpty() || input.isEmpty()) throw rejected("model_responses_tool_history_invalid");
        var tools = JSON.createArrayNode(); var names = new HashSet<String>();
        for (var tool : request.tools()) {
            if (!names.add(tool.name())) throw rejected("model_responses_tools_invalid");
            var schema = parse(tool.inputSchema().canonicalJson());
            if (!schema.isObject()) throw rejected("model_responses_tools_invalid");
            // strict 精确透传；不改写 required、additionalProperties，也不偷偷降级。
            tools.addObject().put("type", "function").put("name", tool.name()).put("description", tool.description())
                    .put("strict", tool.strict()).set("parameters", schema);
        }
        var format = JSON.createObjectNode();
        switch (request.response().format()) {
            case ResponseFormat.Text ignored -> format.put("type", "text");
            case ResponseFormat.NativeConversation ignored -> format.put("type", "text");
            case ResponseFormat.JsonObject ignored -> format.put("type", "json_object");
            case ResponseFormat.JsonSchema schema -> {
                format.put("type", "json_schema").put("name", "runtime_result")
                        .put("strict", request.response().enforcement() == EnforcementLevel.STRICT)
                        .set("schema", parse(schema.schema().canonicalJson()));
            }
        }
        var body = JSON.createObjectNode().put("model", request.modelId()).put("store", false).put("stream", stream)
                .put("tool_choice", request.toolChoice().name().toLowerCase(Locale.ROOT))
                .put("parallel_tool_calls", request.response().format() instanceof ResponseFormat.NativeConversation
                        && request.tools().stream().anyMatch(ToolDefinition::batchEligible));
        body.set("input", input);
        request.generation().maxOutputTokens().ifPresent(value -> body.put("max_output_tokens", value));
        request.generation().temperature().ifPresent(value -> body.put("temperature", value));
        if (effort != null) body.putObject("reasoning").put("effort", effort);
        else body.putObject("reasoning");
        body.putObject("text").set("format", format);
        if (!tools.isEmpty()) body.set("tools", tools);
        request.promptCacheScope().ifPresent(scope -> body.put("prompt_cache_key", scope.key()));
        body.putArray("include").add("reasoning.encrypted_content");
        // SDK DTO 负责线协议结构；原生项的额外字段由 SDK 联合类型保留。
        var sdkBody = JSON.treeToValue(body, ResponseCreateParams.Body.class);
        if (JSON.writeValueAsBytes(sdkBody).length > request.maximumRequestBytes()) throw rejected("model_request_bytes_exceeded");
        return ResponseCreateParams.builder().body(sdkBody).build();
    }

    /** 只编码宿主已准备的图片字节；不下载 URL、不推测文件位置，也不派生用户桥接消息。 */
    private static com.fasterxml.jackson.databind.node.ArrayNode contents(InvocationRequest request, Message message) {
        var content = JSON.createArrayNode();
        for (var part : message.contents()) {
            if (part instanceof ModelContent.Text text) {
                boolean assistant = message.role() == Role.ASSISTANT;
                var value = content.addObject().put("type", assistant ? "output_text" : "input_text").put("text", text.text());
                if (assistant) value.putArray("annotations");
            } else if (part instanceof ModelContent.Image image) {
                byte[] bytes;
                try { bytes = request.mediaReader().apply(image.media()); }
                catch (RuntimeException unavailable) {
                    throw new IllegalArgumentException("model_responses_image_unavailable", unavailable);
                }
                if (bytes == null || bytes.length != image.media().byteLength()) throw rejected("model_responses_image_unavailable");
                content.addObject().put("type", "input_image").put("image_url", "data:" + image.media().mimeType() + ";base64,"
                        + Base64.getEncoder().encodeToString(bytes)).put("detail", image.detail().name().toLowerCase(Locale.ROOT));
            }
        }
        return content;
    }

    /** 已知格式和 Base64 最小字节预算在读取媒体前检查，完整 JSON 仍由最终发送预算核验。 */
    private static void validateImages(InvocationRequest request) {
        long encodedImageBytes = 0;
        long maximumBytes = Math.min(request.maximumRequestBytes(), Integer.MAX_VALUE);
        for (var message : request.messages()) for (var part : message.contents()) {
            if (!(part instanceof ModelContent.Image image)) continue;
            if (message.role() != Role.USER && message.role() != Role.TOOL) throw rejected("model_responses_image_unsupported");
            if (!IMAGE_MEDIA_TYPES.contains(image.media().mimeType())) throw rejected("model_responses_image_invalid");
            long bytes = image.media().byteLength();
            if (bytes > maximumBytes) throw rejected("model_request_bytes_exceeded");
            encodedImageBytes += 4 * ((bytes + 2) / 3);
            if (encodedImageBytes > maximumBytes) throw rejected("model_request_bytes_exceeded");
        }
    }

    static String requireIdentity(InvocationRequest request, JsonNode root) throws IOException {
        if (!"response".equals(required(root, "object"))) throw invalid();
        String id = required(root, "id");
        if (id.isBlank()) throw invalid();
        if (!request.modelId().equals(required(root, "model"))) throw invalid();
        return id;
    }

    static InvocationResult decode(JsonNode root, String actualModel, ModelUsage usage) throws IOException {
        String status = required(root, "status");
        if ("incomplete".equals(status)) return new InvocationResult.Rejected("model_response_incomplete", Optional.empty(), usage);
        if ("failed".equals(status)) return new InvocationResult.Rejected("model_response_failed", Optional.empty(), usage);
        if (!"completed".equals(status) || root.hasNonNull("error") || root.hasNonNull("incomplete_details")) throw invalid();
        var rawOutput = root.get("output");
        final Output output;
        try { output = output(rawOutput); }
        catch (Refusal refusal) { return new InvocationResult.Rejected("model_content_rejected", Optional.empty(), usage); }
        var items = new ArrayList<TypedPayload>();
        for (var item : rawOutput) items.add(new TypedPayload(OUTPUT_ITEM_SCHEMA, JSON.writeValueAsString(item)));
        var state = items.isEmpty() ? Optional.<NativeProtocolState>empty()
                : Optional.of(new NativeProtocolState(NativeProtocol.RESPONSES, DIALECT, actualModel, items));
        return new InvocationResult.Completed(output.text().isEmpty() ? null : output.text(), output.calls(), actualModel, usage,
                output.calls().isEmpty() ? "stop" : "tool_calls", output.reasoning().isEmpty() ? null : output.reasoning(), state);
    }

    private record Output(String text, List<ToolCall> calls, String reasoning) { }
    private static final class Refusal extends IOException { }
    private static Output output(JsonNode output) throws IOException {
        if (output == null || !output.isArray()) throw invalid();
        var text = new StringBuilder(); var reasoning = new StringBuilder(); var calls = new ArrayList<ToolCall>(); var ids = new HashSet<String>();
        for (var item : output) {
            if (item.hasNonNull("status") && !"completed".equals(required(item, "status"))) throw invalid();
            switch (required(item, "type")) {
                case "message" -> {
                    if (!"assistant".equals(required(item, "role")) || !item.path("content").isArray()) throw invalid();
                    for (var content : item.path("content")) {
                        if ("refusal".equals(required(content, "type")))
                            throw new Refusal();
                        if (!"output_text".equals(required(content, "type"))) throw invalid();
                        text.append(required(content, "text"));
                    }
                }
                case "function_call" -> {
                    String id = required(item, "call_id");
                    if (id.isBlank() || !ids.add(id) || required(item, "name").isBlank()) throw invalid();
                    calls.add(new ToolCall(id, required(item, "name"), required(item, "arguments")));
                }
                case "reasoning" -> {
                    if (required(item, "id").isBlank() || item.hasNonNull("summary") && !item.path("summary").isArray()
                            || item.hasNonNull("encrypted_content") && !item.path("encrypted_content").isTextual()) throw invalid();
                    String raw = reasoningText(item);
                    if (raw.isEmpty() && item.path("encrypted_content").asText().isBlank()) throw invalid();
                    reasoning.append(raw);
                    for (var summary : item.path("summary"))
                        if (!"summary_text".equals(required(summary, "type")) || !summary.path("text").isTextual()) throw invalid();
                }
                default -> throw invalid();
            }
        }
        return new Output(text.toString(), List.copyOf(calls), reasoning.toString());
    }

    /** 原生 reasoning_text 才是思考明文；summary_text 不参与该通道。 */
    static String reasoningText(JsonNode item) throws IOException {
        if (!item.hasNonNull("content")) return "";
        if (!item.path("content").isArray()) throw invalid();
        var result = new StringBuilder();
        for (var part : item.path("content")) {
            if (!"reasoning_text".equals(required(part, "type"))) throw invalid();
            result.append(required(part, "text"));
        }
        return result.toString();
    }

    static ModelUsage usage(JsonNode raw) throws IOException {
        if (!raw.isObject()) throw invalid();
        var input = tokens(raw, "input_tokens"); var output = tokens(raw, "output_tokens");
        var cached = tokens(raw.path("input_tokens_details"), "cached_tokens");
        var written = tokens(raw.path("input_tokens_details"), "cache_write_tokens");
        var reasoning = tokens(raw.path("output_tokens_details"), "reasoning_tokens");
        // Responses 的输入总量已包含缓存读取和写入；ModelUsage 保留总量，不再次相加或减除。
        if (input.isPresent() && (written.orElse(0) > input.getAsLong() || cached.orElse(0) > input.getAsLong() - written.orElse(0))
                || output.isPresent() && reasoning.orElse(0) > output.getAsLong()) throw invalid();
        return new ModelUsage(input, output, cached, written, reasoning);
    }
    private static OptionalLong tokens(JsonNode raw, String field) throws IOException {
        if (!raw.has(field)) return OptionalLong.empty();
        var value = raw.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid();
        return OptionalLong.of(value.longValue());
    }
    static ModelUsage emptyUsage() { return new ModelUsage(OptionalLong.empty(), OptionalLong.empty()); }
    static JsonNode parse(String text) throws IOException { return parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    static JsonNode parse(byte[] bytes) throws IOException {
        try (var parser = JSON.getFactory().createParser(bytes)) {
            JsonNode result = JSON.readTree(parser);
            if (result == null || parser.nextToken() != null) throw invalid();
            return result;
        }
    }
    static String required(JsonNode value, String field) throws IOException {
        var result = value.get(field); if (result == null || !result.isTextual()) throw invalid(); return result.textValue();
    }
    static IOException invalid() { return new IOException("model_responses_invalid"); }
    static IllegalArgumentException rejected(String code) { return new IllegalArgumentException(code); }
}
