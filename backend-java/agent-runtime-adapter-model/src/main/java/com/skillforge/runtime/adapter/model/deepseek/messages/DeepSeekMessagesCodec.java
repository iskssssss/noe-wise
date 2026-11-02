package com.skillforge.runtime.adapter.model.deepseek.messages;

import com.skillforge.runtime.adapter.model.provider.ModelProvider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.*;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.NativeProtocol;
import com.skillforge.runtime.api.port.ModelPort.NativeProtocolState;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import java.io.IOException;
import java.util.*;

/** DeepSeek Messages 的有序图文/工具投影；累计 SYSTEM 是完整快照，不能被末尾时间事实覆盖。 */
final class DeepSeekMessagesCodec {
    static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static final String DIALECT = DeepSeekMessagesProjection.DIALECT;
    static final String BLOCK_SCHEMA = "deepseek.messages.content-block.v1";
    private static final long MAXIMUM_REQUEST_BYTES = 48L * 1024 * 1024;
    private static final long MAXIMUM_IMAGE_BYTES = 32L * 1024 * 1024;
    private static final int MAXIMUM_IMAGES = 600;
    private static final int MANY_IMAGES = 15;
    private static final int MAXIMUM_IMAGE_DIMENSION = 8192;
    private static final int MANY_IMAGES_MAXIMUM_DIMENSION = 4096;
    private static final Set<String> IMAGE_MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private record ImageSource(String type, @JsonProperty("media_type") String mediaType, String data) { }
    private record ImageBlock(String type, ImageSource source) { }

    private DeepSeekMessagesCodec() { }

    static byte[] encode(InvocationRequest request, boolean streaming) throws IOException {
        if (!(request.response().format() instanceof ResponseFormat.Text)
                && !(request.response().format() instanceof ResponseFormat.NativeConversation))
            throw rejected("model_messages_response_unsupported");
        if (request.generation().maxOutputTokens().isEmpty()) throw rejected("model_messages_output_limit_required");
        validateImages(request);
        var root = JSON.createObjectNode().put("model", request.modelId()).put("stream", streaming)
                .put("max_tokens", request.generation().maxOutputTokens().orElseThrow());
        // DeepSeek thinking_mode: Anthropic thinking toggle and output_config.effort; budget_tokens is ignored.
        // https://api-docs.deepseek.com/guides/thinking_mode
        root.putObject("thinking").put("type", request.reasoning() == ReasoningPolicy.DISABLED ? "disabled" : "enabled");
        String effort = switch (request.reasoning()) {
            case DEFAULT, ENABLED, DISABLED -> null;
            case MINIMAL, LOW -> "low";
            case MEDIUM, HIGH, XHIGH -> "high";
            case MAX -> "max";
        };
        if (effort != null) root.putObject("output_config").put("effort", effort);
        request.generation().temperature().ifPresent(value -> root.put("temperature", value));
        var messages = root.putArray("messages");
        var projected = DeepSeekMessagesProjection.project(request.messages(), request.prefixCapabilities());
        if (projected.leadingSystem() != null) root.put("system", projected.leadingSystem());
        for (var message : projected.messages()) {
            if (message.reasoningContent() != null && message.protocolState().isEmpty())
                throw rejected("model_messages_reasoning_history_unsupported");
            if (message.role() == Role.SYSTEM) {
                appendSystem(messages, text(message));
                continue;
            }
            var blocks = JSON.createArrayNode();
            if (message.protocolState().isPresent()) {
                var state = message.protocolState().orElseThrow();
                if (message.role() != Role.ASSISTANT || state.protocol() != NativeProtocol.MESSAGES
                        || !DIALECT.equals(state.dialectVersion()) || !request.modelId().equals(state.modelId()))
                    throw rejected("model_messages_protocol_state_mismatch");
                for (var item : state.items()) {
                    if (!BLOCK_SCHEMA.equals(item.schemaId())) throw rejected("model_messages_protocol_state_invalid");
                    blocks.add(parse(item.canonicalJson()));
                }
                var projectedResponse = JSON.createObjectNode().put("type", "message").put("role", "assistant")
                        .put("model", request.modelId()).put("stop_reason", message.toolCalls().isEmpty() ? "end_turn" : "tool_use");
                projectedResponse.set("content", blocks); projectedResponse.putObject("usage");
                var decoded = decode(request, projectedResponse);
                if (!Objects.equals(decoded.content(), message.textContent().isEmpty() ? null : message.textContent())
                        || !sameToolCalls(decoded.toolCalls(), message.toolCalls())
                        || !Objects.equals(decoded.reasoningContent(), message.reasoningContent()))
                    throw rejected("model_messages_protocol_state_mismatch");
            } else if (message.role() == Role.TOOL) {
                var result = blocks.addObject().put("type", "tool_result").put("tool_use_id", message.toolCallId());
                if (message.contents().stream().anyMatch(ModelContent.Image.class::isInstance))
                    result.set("content", contents(request, message));
                else result.put("content", text(message));
            } else {
                blocks.addAll(contents(request, message));
                for (var call : message.toolCalls()) {
                    JsonNode input = parse(call.argumentsJson());
                    if (!input.isObject()) throw rejected("model_messages_tool_history_invalid");
                    blocks.addObject().put("type", "tool_use").put("id", call.callId())
                            .put("name", call.name()).set("input", input);
                }
            }
            if (blocks.isEmpty()) continue;
            String role = message.role() == Role.ASSISTANT ? "assistant" : "user";
            if (!messages.isEmpty() && role.equals(messages.get(messages.size() - 1).path("role").asText()))
                ((ArrayNode) messages.get(messages.size() - 1).get("content")).addAll(blocks);
            else messages.addObject().put("role", role).set("content", blocks);
        }
        validateHistory(messages);
        if (messages.isEmpty() || !"user".equals(messages.get(0).path("role").asText()))
            throw rejected("model_messages_history_invalid");
        if (!request.tools().isEmpty()) {
            var tools = root.putArray("tools");
            var names = new HashSet<String>();
            for (var tool : request.tools()) {
                if (!names.add(tool.name())) throw rejected("model_messages_tools_invalid");
                JsonNode schema = parse(tool.inputSchema().canonicalJson());
                if (!schema.isObject()) throw rejected("model_messages_tools_invalid");
                // 官方 Messages tools 只支持 name/description/input_schema；strict 生成偏好不在线上传输。
                // required/additionalProperties 等完整 schema 仍保留，ResponseContract 强制 JSON 契约已在入口拒绝。
                tools.addObject().put("name", tool.name()).put("description", tool.description())
                        .set("input_schema", schema);
            }
            root.putObject("tool_choice").put("type", switch (request.toolChoice()) {
                case NONE -> "none";
                case AUTO -> "auto";
                case REQUIRED -> "any";
            });
        }
        byte[] bytes = JSON.writeValueAsBytes(root);
        if (bytes.length > Math.min(request.maximumRequestBytes(), MAXIMUM_REQUEST_BYTES))
            throw rejected("model_request_bytes_exceeded");
        return bytes;
    }

    /** 原生 tool_result 可以直接承载图片，不派生 Chat 专用的 user 桥接消息。 */
    private static ArrayNode contents(InvocationRequest request, Message message) {
        var blocks = JSON.createArrayNode();
        for (var content : message.contents()) {
            if (content instanceof ModelContent.Text text) {
                if (!text.text().isEmpty()) blocks.addObject().put("type", "text").put("text", text.text());
            } else if (content instanceof ModelContent.Image image) {
                byte[] bytes;
                try {
                    bytes = request.mediaReader().apply(image.media());
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException("model_messages_image_unavailable", failure);
                }
                if (bytes == null || bytes.length != image.media().byteLength())
                    throw rejected("model_messages_image_unavailable");
                blocks.add(JSON.valueToTree(new ImageBlock("image", new ImageSource("base64", image.media().mimeType(),
                        Base64.getEncoder().encodeToString(bytes)))));
            }
        }
        return blocks;
    }

    /** DeepSeek Vision 的协议物理上限；更紧的发布策略已由可信宿主在读取媒体前核验。 */
    private static void validateImages(InvocationRequest request) {
        long count = request.messages().stream().flatMap(message -> message.contents().stream())
                .filter(ModelContent.Image.class::isInstance).count();
        if (count > MAXIMUM_IMAGES) throw rejected("model_messages_images_exceeded");
        int maximumDimension = count >= MANY_IMAGES ? MANY_IMAGES_MAXIMUM_DIMENSION : MAXIMUM_IMAGE_DIMENSION;
        long encodedImageBytes = 0;
        for (var message : request.messages()) for (var content : message.contents()) {
            if (!(content instanceof ModelContent.Image image)) continue;
            var media = image.media();
            if (!IMAGE_MEDIA_TYPES.contains(media.mimeType()) || media.byteLength() > MAXIMUM_IMAGE_BYTES
                    || media.width() > maximumDimension || media.height() > maximumDimension)
                throw rejected("model_messages_image_invalid");
            encodedImageBytes = Math.addExact(encodedImageBytes, Math.multiplyExact(4, (media.byteLength() + 2) / 3));
            if (encodedImageBytes > Math.min(request.maximumRequestBytes(), MAXIMUM_REQUEST_BYTES))
                throw rejected("model_request_bytes_exceeded");
        }
    }

    /** 同一原生调用的派生投影按身份及JSON值核对，SSE参数中的空白和对象键顺序不构成变化。 */
    private static boolean sameToolCalls(List<ToolCall> nativeCalls, List<ToolCall> projectedCalls) throws IOException {
        if (nativeCalls.size() != projectedCalls.size()) return false;
        for (int index = 0; index < nativeCalls.size(); index++) {
            var original = nativeCalls.get(index); var projected = projectedCalls.get(index);
            if (!original.callId().equals(projected.callId()) || !original.name().equals(projected.name())
                    || !parse(original.argumentsJson()).equals(parse(projected.argumentsJson()))) return false;
        }
        return true;
    }

    private static void appendSystem(ArrayNode messages, String snapshot) {
        if (messages.isEmpty() || !"user".equals(messages.get(messages.size() - 1).path("role").asText()))
            throw rejected("model_messages_system_position_invalid");
        messages.addObject().put("role", "system").putArray("content").addObject()
                .put("type", "text").put("text", snapshot);
    }

    private static String text(Message message) {
        if (message.contents().stream().anyMatch(part -> !(part instanceof ModelContent.Text)))
            throw rejected("model_messages_image_unsupported");
        return message.textContent();
    }

    private static void validateHistory(ArrayNode messages) {
        Set<String> pending = new HashSet<>();
        Set<String> all = new HashSet<>();
        for (var message : messages) {
            String role = message.path("role").asText();
            if ("system".equals(role)) {
                if (!pending.isEmpty()) throw rejected("model_messages_tool_history_invalid");
                continue;
            }
            if ("assistant".equals(role)) {
                if (!pending.isEmpty()) throw rejected("model_messages_tool_history_invalid");
                for (var block : message.path("content")) if ("tool_use".equals(block.path("type").asText())) {
                    String id = block.path("id").asText();
                    if (!all.add(id) || !pending.add(id)) throw rejected("model_messages_tool_history_invalid");
                }
            } else {
                var content = (ArrayNode) message.get("content");
                var reordered = JSON.createArrayNode();
                for (var block : content) if ("tool_result".equals(block.path("type").asText())) {
                    if (!pending.remove(block.path("tool_use_id").asText())) throw rejected("model_messages_tool_history_invalid");
                    reordered.add(block);
                }
                if (!pending.isEmpty()) throw rejected("model_messages_tool_history_invalid");
                for (var block : content) if (!"tool_result".equals(block.path("type").asText())) reordered.add(block);
                ((ObjectNode) message).set("content", reordered);
            }
        }
        if (!pending.isEmpty()) throw rejected("model_messages_tool_history_invalid");
    }

    static InvocationResult.Completed decode(InvocationRequest request, JsonNode root) throws IOException {
        if (!"message".equals(required(root, "type")) || !"assistant".equals(required(root, "role"))
                || !request.modelId().equals(required(root, "model"))) throw invalid();
        String finish = finish(required(root, "stop_reason"));
        var content = root.get("content");
        if (content == null || !content.isArray()) throw invalid();
        var text = new StringBuilder();
        var calls = new ArrayList<ToolCall>();
        var reasoning = new StringBuilder();
        boolean hasThinking = false;
        var ids = new HashSet<String>();
        for (var block : content) {
            switch (required(block, "type")) {
                case "text" -> text.append(required(block, "text"));
                case "thinking" -> {
                    hasThinking = true;
                    reasoning.append(required(block, "thinking"));
                    required(block, "signature");
                }
                case "tool_use" -> {
                    String id = required(block, "id");
                    if (id.isBlank() || !ids.add(id) || !block.path("input").isObject()) throw invalid();
                    calls.add(new ToolCall(id, required(block, "name"), JSON.writeValueAsString(block.get("input"))));
                }
                default -> throw invalid();
            }
        }
        return new InvocationResult.Completed(text.isEmpty() ? null : text.toString(), calls,
                request.modelId(), usage(root.get("usage")), finish,
                hasThinking ? reasoning.toString() : null, content.isEmpty() ? Optional.empty() : Optional.of(protocolState(request, (ArrayNode) content)));
    }

    static NativeProtocolState protocolState(InvocationRequest request, ArrayNode blocks) throws IOException {
        var items = new ArrayList<TypedPayload>();
        for (var block : blocks) items.add(new TypedPayload(BLOCK_SCHEMA, JSON.writeValueAsString(block)));
        return new NativeProtocolState(NativeProtocol.MESSAGES, DIALECT, request.modelId(), items);
    }

    static ModelUsage usage(JsonNode raw) throws IOException {
        if (raw == null || !raw.isObject()) throw invalid();
        var input = tokens(raw, "input_tokens");
        var read = tokens(raw, "cache_read_input_tokens");
        var write = tokens(raw, "cache_creation_input_tokens");
        OptionalLong total = input.isPresent() && read.isPresent() && write.isPresent()
                ? OptionalLong.of(Math.addExact(Math.addExact(input.getAsLong(), read.getAsLong()), write.getAsLong()))
                : OptionalLong.empty();
        return new ModelUsage(total, tokens(raw, "output_tokens"), read, write, OptionalLong.empty());
    }

    static OptionalLong tokens(JsonNode raw, String field) throws IOException {
        if (!raw.has(field)) return OptionalLong.empty();
        var value = raw.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid();
        return OptionalLong.of(value.longValue());
    }

    static String finish(String value) throws IOException {
        return switch (value) {
            case "end_turn", "stop_sequence" -> "stop";
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            default -> throw invalid();
        };
    }

    static String required(JsonNode node, String field) throws IOException {
        if (node == null || !node.path(field).isTextual()) throw invalid();
        return node.get(field).textValue();
    }

    static JsonNode parse(String text) throws IOException {
        try (var parser = JSON.createParser(text)) {
            JsonNode result = JSON.readTree(parser);
            if (result == null || parser.nextToken() != null) throw invalid();
            return result;
        }
    }

    static JsonNode parse(byte[] bytes) throws IOException {
        return parse(java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString());
    }

    static IllegalArgumentException rejected(String reason) { return new IllegalArgumentException(reason); }
    static IOException invalid() { return new IOException("model_messages_protocol_invalid"); }
}
