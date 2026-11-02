package com.skillforge.runtime.adapter.model.openai.chat;

import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.protocol.CanonicalConversation;
import com.skillforge.runtime.adapter.model.protocol.ConversationCodec;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.skillforge.runtime.adapter.model.protocol.CanonicalConversation.Instruction;
import com.skillforge.runtime.adapter.model.protocol.CanonicalConversation.Message;
import com.skillforge.runtime.adapter.model.protocol.CanonicalConversation.Reasoning;
import com.skillforge.runtime.adapter.model.protocol.CanonicalConversation.TextContent;
import com.skillforge.runtime.adapter.model.protocol.CanonicalConversation.ImageContent;
import com.skillforge.runtime.adapter.model.protocol.CanonicalConversation.ToolCall;
import com.skillforge.runtime.adapter.model.protocol.CanonicalConversation.ToolResult;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** OpenAI Chat Completions 的 messages、assistant 和流式字段双向映射。 */
public final class OpenAiChatConversationCodec implements ConversationCodec {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    @Override
    public ModelProtocol protocol() {
        return ModelProtocol.OPENAI_CHAT_COMPLETIONS;
    }

    @Override
    public ArrayNode encode(CanonicalConversation conversation,
            ModelProvider.ChatCompletionsPolicy policy) throws IOException {
        ArrayNode messages = JSON.createArrayNode();
        List<ToolResult> imageResults = new ArrayList<>();
        for (int index = 0; index < conversation.items().size(); index++) {
            var item = conversation.items().get(index);
            if (!(item instanceof ToolResult)) flushToolImages(messages, imageResults);
            if (item instanceof Instruction instruction) {
                messages.addObject().put("role", "system")
                        .put("content", text(instruction.contents()));
            } else if (item instanceof Message message
                    && message.actor() == CanonicalConversation.Actor.USER) {
                var encoded = messages.addObject().put("role", "user");
                encodeContents(encoded, message.contents());
            } else if (item instanceof Message || item instanceof ToolCall) {
                // IR 的正文、推理和工具调用是同级 Item；一个 assistant 回合在线上合并为一条消息。
                var encoded = messages.addObject().put("role", "assistant");
                if (item instanceof Message message && !message.contents().isEmpty()) {
                    encoded.put("content", text(message.contents()));
                } else {
                    encoded.putNull("content");
                }
                if (item instanceof ToolCall call) encodeToolCall(encoded.putArray("tool_calls"), call);
                while (index + 1 < conversation.items().size()) {
                    var next = conversation.items().get(index + 1);
                    if (next instanceof ToolCall call) {
                        ArrayNode calls = encoded.has("tool_calls")
                                ? (ArrayNode) encoded.get("tool_calls") : encoded.putArray("tool_calls");
                        encodeToolCall(calls, call);
                    } else if (next instanceof Reasoning reasoning) {
                        if (reasoning.opaqueState() != null
                                || reasoning.replayPolicy() != CanonicalConversation.ReplayPolicy.PRESERVE
                                || encoded.has("reasoning_content")) throw invalid();
                        encoded.put("reasoning_content", reasoning.text());
                    } else {
                        break;
                    }
                    index++;
                }
                if (policy.requireAssistantReasoning() && !encoded.has("reasoning_content")) {
                    // Quirk 区分字段缺失与空思考，绝不改写已冻结的推理证据。
                    encoded.put("reasoning_content", "");
                }
            } else if (item instanceof ToolResult result) {
                messages.addObject().put("role", "tool")
                        .put("tool_call_id", result.callId())
                        .put("content", result.contents().stream().filter(TextContent.class::isInstance)
                                .map(TextContent.class::cast).map(TextContent::text).collect(java.util.stream.Collectors.joining()));
                if (result.contents().stream().anyMatch(ImageContent.class::isInstance)) imageResults.add(result);
            } else {
                // 不透明 Reasoning/ProtocolState 未有精确回传 Quirk 时拒绝，不能静默丢弃。
                throw invalid();
            }
        }
        flushToolImages(messages, imageResults);
        return messages;
    }

    /** 全批 tool results 完成后才派生 user 图片消息，产品对话与冻结来源保持原样。 */
    private static void flushToolImages(ArrayNode messages, List<ToolResult> pending) throws IOException {
        for (var result : pending) {
            var contents = new ArrayList<CanonicalConversation.Content>();
            contents.add(new TextContent(toolImageAnchor(result.callId())));
            contents.addAll(result.contents());
            encodeContents(messages.addObject().put("role", "user"), contents);
        }
        pending.clear();
    }

    static String toolImageAnchor(String callId) {
        return com.skillforge.runtime.adapter.model.ModelImageAccounting.toolImageAnchor(callId);
    }

    private static void encodeContents(com.fasterxml.jackson.databind.node.ObjectNode target,
            List<CanonicalConversation.Content> contents) throws IOException {
        if (contents.stream().allMatch(TextContent.class::isInstance)) {
            target.put("content", text(contents));
            return;
        }
        var parts = target.putArray("content");
        for (var content : contents) {
            if (content instanceof TextContent text) parts.addObject().put("type", "text").put("text", text.text());
            else if (content instanceof ImageContent image) {
                parts.addObject().put("type", "image_url").putObject("image_url")
                        .put("url", "data:" + image.mimeType() + ";base64,"
                                + java.util.Base64.getEncoder().encodeToString(image.bytes()))
                        .put("detail", image.detail().name().toLowerCase(Locale.ROOT));
            } else throw invalid();
        }
    }

    @Override
    public CanonicalConversation decodeAssistant(JsonNode message,
            boolean preserveArguments, boolean includeReasoning) throws IOException {
        requireObject(message);
        List<CanonicalConversation.ConversationItem> items = new ArrayList<>();
        String content = nullableText(message.get("content"));
        items.add(new Message("assistant-response", CanonicalConversation.Actor.ASSISTANT,
                content == null ? List.of() : List.of(new TextContent(content))));
        if (includeReasoning) {
            String reasoning = nullableText(message.get("reasoning_content"));
            if (reasoning != null) {
                items.add(new Reasoning("assistant-reasoning", reasoning, null,
                        CanonicalConversation.ReplayPolicy.PRESERVE));
            }
        }
        JsonNode calls = message.get("tool_calls");
        if (calls != null && !calls.isNull()) {
            if (!calls.isArray()) throw invalid();
            for (int index = 0; index < calls.size(); index++) {
                JsonNode call = calls.get(index);
                JsonNode function = call.get("function");
                if (!call.isObject() || function == null || !function.isObject()
                        || !"function".equals(text(call.get("type")))) throw invalid();
                JsonNode rawArguments = function.get("arguments");
                String arguments = preserveArguments && (rawArguments == null || !rawArguments.isTextual())
                        ? "" : text(rawArguments);
                if (!preserveArguments) {
                    JsonNode parsed = JSON.readTree(arguments);
                    if (parsed == null || !parsed.isObject()) throw invalid();
                    arguments = JSON.writeValueAsString(parsed);
                }
                items.add(new ToolCall("assistant-tool-" + index, text(call.get("id")),
                        text(function.get("name")), arguments));
            }
        }
        // 空响应仍由 transport 的 finish/结构化响应规则判定，codec 不改变可知性。
        return new CanonicalConversation(items);
    }

    @Override
    public Optional<String> decodeTextDelta(JsonNode delta) throws IOException {
        requireObject(delta);
        return Optional.ofNullable(nullableText(delta.get("content")));
    }

    @Override
    public Optional<String> decodeReasoningDelta(JsonNode delta) throws IOException {
        requireObject(delta);
        return Optional.ofNullable(nullableText(delta.get("reasoning_content")));
    }

    @Override
    public Iterable<JsonNode> toolCallDeltas(JsonNode delta) throws IOException {
        requireObject(delta);
        JsonNode calls = delta.get("tool_calls");
        if (calls == null || calls.isNull()) return List.of();
        if (!calls.isArray()) throw invalid();
        return calls;
    }

    @Override
    public ToolCallFragment decodeToolCallDelta(JsonNode call, boolean preserveArguments) throws IOException {
        requireObject(call);
        String callId = nullableText(call.get("id"));
        String name = null;
        String arguments = null;
        boolean invalidArguments = false;
        JsonNode function = call.get("function");
        if (function != null && !function.isNull()) {
            requireObject(function);
            name = nullableText(function.get("name"));
            JsonNode rawArguments = function.get("arguments");
            invalidArguments = preserveArguments && rawArguments != null && !rawArguments.isNull()
                    && !rawArguments.isTextual();
            if (!invalidArguments) arguments = nullableText(rawArguments);
        }
        return new ToolCallFragment(call.path("index").asInt(-1), callId, name, arguments, invalidArguments);
    }

    private static void encodeToolCall(ArrayNode target, ToolCall call) throws IOException {
        JsonNode arguments = JSON.readTree(call.argumentsJson());
        if (arguments == null || !arguments.isObject()) throw invalid();
        var encoded = target.addObject().put("id", call.callId()).put("type", "function");
        // 冻结历史参数只校验形状，保留原始精度和字节。
        encoded.putObject("function").put("name", call.name()).put("arguments", call.argumentsJson());
    }

    private static String text(List<CanonicalConversation.Content> contents) throws IOException {
        if (contents.stream().anyMatch(content -> !(content instanceof TextContent))) throw invalid();
        return contents.stream().map(TextContent.class::cast).map(TextContent::text)
                .collect(java.util.stream.Collectors.joining());
    }

    private static void requireObject(JsonNode value) throws IOException {
        if (value == null || !value.isObject()) throw invalid();
    }

    private static String text(JsonNode value) throws IOException {
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static String nullableText(JsonNode value) throws IOException {
        return value == null || value.isNull() ? null : text(value);
    }

    private static IOException invalid() {
        return new IOException("model_protocol_invalid");
    }
}
