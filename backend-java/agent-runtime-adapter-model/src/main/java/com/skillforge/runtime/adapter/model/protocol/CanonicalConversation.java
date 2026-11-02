package com.skillforge.runtime.adapter.model.protocol;

import com.skillforge.runtime.adapter.model.provider.ModelProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ImageDetail;

/**
 * 供应商无关的对话中间表示；协议 Codec 决定各 Item 在线上传输格式中的位置。
 *
 * <p>冻结的 assistant 消息同时保留正文、函数调用与推理文本；不透明协议状态未经实现不得丢弃。
 */
public record CanonicalConversation(List<ConversationItem> items) {

    public CanonicalConversation {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    /** Provider 内部直接转为 IR，不通过 Runtime ModelPort 建立第二条调用路径。 */
    public static CanonicalConversation from(List<ModelProvider.Message> messages) {
        return from(messages, ref -> { throw new IllegalStateException("model_media_reader_missing"); });
    }

    public static CanonicalConversation from(List<ModelProvider.Message> messages,
            java.util.function.Function<MediaRef, byte[]> mediaReader) {
        List<ConversationItem> items = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            var message = messages.get(index);
            String id = "message-" + index;
            var contents = message.contents().stream().map(content -> {
                if (content instanceof ModelContent.Text text) return (Content) new TextContent(text.text());
                var image = (ModelContent.Image) content;
                return (Content) new ImageContent(image.media().mimeType(), mediaReader.apply(image.media()), image.detail());
            }).toList();
            switch (message.role()) {
                case SYSTEM -> items.add(new Instruction(id, contents));
                case USER -> items.add(new Message(id, Actor.USER, contents));
                case ASSISTANT -> {
                    // 空正文工具回合仍保留 Message 边界，使相邻 assistant 回合不会被拼接。
                    items.add(new Message(id, Actor.ASSISTANT,
                            contents));
                    if (message.reasoningContent() != null) {
                        items.add(new Reasoning(id + "-reasoning", message.reasoningContent(), null,
                                ReplayPolicy.PRESERVE));
                    }
                    for (int callIndex = 0; callIndex < message.toolCalls().size(); callIndex++) {
                        var call = message.toolCalls().get(callIndex);
                        items.add(new ToolCall(id + "-tool-" + callIndex,
                                call.callId(), call.name(), call.argumentsJson()));
                    }
                }
                case TOOL -> items.add(new ToolResult(id, message.toolCallId(),
                        contents));
            }
        }
        return new CanonicalConversation(items);
    }

    public String assistantText() throws java.io.IOException {
        var messages = items.stream().filter(Message.class::isInstance)
                .map(Message.class::cast).toList();
        if (messages.isEmpty()) return null;
        if (messages.size() != 1 || messages.getFirst().actor() != Actor.ASSISTANT) throw invalid();
        if (messages.getFirst().contents().isEmpty()) return null;
        if (messages.getFirst().contents().size() != 1
                || !(messages.getFirst().contents().getFirst() instanceof TextContent text)) {
            throw invalid();
        }
        return text.text();
    }

    public java.util.Optional<String> reasoningText() throws java.io.IOException {
        var reasoning = items.stream().filter(Reasoning.class::isInstance)
                .map(Reasoning.class::cast).toList();
        if (reasoning.size() > 1) throw invalid();
        return reasoning.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of(reasoning.getFirst().text());
    }

    public List<ToolCall> toolCalls() {
        return items.stream().filter(ToolCall.class::isInstance).map(ToolCall.class::cast).toList();
    }

    public sealed interface ConversationItem permits Instruction, Message, ToolCall,
            ToolResult, Reasoning, ProtocolState {
        String itemId();
    }

    public record Instruction(String itemId, List<Content> contents)
            implements ConversationItem {
        public Instruction {
            itemId = requireText(itemId);
            contents = requireContents(contents);
        }
    }

    public record Message(String itemId, Actor actor, List<Content> contents)
            implements ConversationItem {
        public Message {
            itemId = requireText(itemId);
            actor = Objects.requireNonNull(actor, "actor");
            contents = actor == Actor.ASSISTANT ? List.copyOf(contents) : requireContents(contents);
        }
    }

    public record ToolCall(String itemId, String callId, String name,
            String argumentsJson) implements ConversationItem {
        public ToolCall {
            itemId = requireText(itemId);
            callId = requireText(callId);
            name = requireText(name);
            // 原生协议保留无效/空参数给决策 decoder，不能提前改成结果未知。
            argumentsJson = Objects.requireNonNull(argumentsJson, "argumentsJson");
        }
    }

    public record ToolResult(String itemId, String callId, List<Content> contents)
            implements ConversationItem {
        public ToolResult {
            itemId = requireText(itemId);
            callId = requireText(callId);
            contents = requireContents(contents);
        }
    }

    public record Reasoning(String itemId, String text, String opaqueState,
            ReplayPolicy replayPolicy) implements ConversationItem {
        public Reasoning {
            itemId = requireText(itemId);
            text = Objects.requireNonNull(text, "text");
            replayPolicy = Objects.requireNonNull(replayPolicy, "replayPolicy");
            if (replayPolicy == ReplayPolicy.PRESERVE_EXACT) {
                opaqueState = requireText(opaqueState);
            }
        }
    }

    public record ProtocolState(String itemId, String mediaType, String opaqueState,
            ReplayPolicy replayPolicy) implements ConversationItem {
        public ProtocolState {
            itemId = requireText(itemId);
            mediaType = requireText(mediaType);
            opaqueState = requireText(opaqueState);
            replayPolicy = Objects.requireNonNull(replayPolicy, "replayPolicy");
        }
    }

    public sealed interface Content permits TextContent, ImageContent {
    }

    /** 图片原字节仅在供应商发送准备内存在，Base64 由 wire Codec 临时生成。 */
    public record ImageContent(String mimeType, byte[] bytes, ImageDetail detail) implements Content {
        public ImageContent {
            if (!java.util.Set.of("image/png", "image/jpeg", "image/webp").contains(mimeType)
                    || bytes == null || bytes.length == 0) throw new IllegalArgumentException("model_image_invalid");
            bytes = bytes.clone();
            Objects.requireNonNull(detail);
        }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public String toString() { return "ImageContent[REDACTED]"; }
    }

    public record TextContent(String text) implements Content {
        public TextContent {
            text = Objects.requireNonNull(text, "text");
        }
    }

    public enum Actor {
        USER,
        ASSISTANT
    }

    public enum ReplayPolicy {
        DROP,
        NORMALIZE,
        PRESERVE,
        PRESERVE_EXACT
    }

    private static List<Content> requireContents(List<Content> contents) {
        contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
        if (contents.isEmpty() || contents.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("model_conversation_content_invalid");
        }
        return contents;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("model_conversation_value_invalid");
        }
        return value;
    }

    private static java.io.IOException invalid() {
        return new java.io.IOException("model_protocol_invalid");
    }
}
