package com.skillforge.runtime.adapter.model.openai.chat;

import com.skillforge.runtime.adapter.model.provider.ModelProvider;

import com.skillforge.runtime.adapter.model.protocol.CanonicalConversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.protocol.CanonicalConversation.TextContent;
import com.skillforge.runtime.adapter.model.protocol.CanonicalConversation.ToolCall;
import com.skillforge.runtime.adapter.model.protocol.CanonicalConversation.ToolResult;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ChatCompletionsPolicy;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Message;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Role;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiChatConversationCodecTest {
    @Test void preservesPureImageAndOrderedMixedParts() throws Exception {
        var image = new CanonicalConversation.ImageContent("image/png", new byte[]{1, 2, 3},
                com.skillforge.runtime.api.media.ImageDetail.HIGH);
        var encoded = codec.encode(new CanonicalConversation(List.of(
                new CanonicalConversation.Message("one", CanonicalConversation.Actor.USER, List.of(image)),
                new CanonicalConversation.Message("two", CanonicalConversation.Actor.USER,
                        List.of(new TextContent("before"), image, new TextContent("after"))))), ChatCompletionsPolicy.standard());
        assertEquals("image_url", encoded.get(0).path("content").get(0).path("type").asText());
        assertEquals("data:image/png;base64,AQID", encoded.get(0).path("content").get(0).path("image_url").path("url").asText());
        assertEquals("high", encoded.get(0).path("content").get(0).path("image_url").path("detail").asText());
        assertEquals("before", encoded.get(1).path("content").get(0).path("text").asText());
        assertEquals("image_url", encoded.get(1).path("content").get(1).path("type").asText());
        assertEquals("after", encoded.get(1).path("content").get(2).path("text").asText());
    }

    @Test void bridgesImagesOnlyAfterAllToolResultsWithoutMutatingFrozenItems() throws Exception {
        var image = new CanonicalConversation.ImageContent("image/webp", new byte[]{1},
                com.skillforge.runtime.api.media.ImageDetail.LOW);
        var result = new ToolResult("result", "call-1", List.of(new TextContent("first"), image, new TextContent("last")));
        var conversation = new CanonicalConversation(List.of(
                new ToolCall("call1", "call-1", "lookup", "{}"),
                new ToolCall("call2", "call-2", "lookup2", "{}"), result,
                new ToolResult("result2", "call-2", List.of(new TextContent("done")))));
        var encoded = codec.encode(conversation, ChatCompletionsPolicy.standard());
        assertEquals(List.of("assistant", "tool", "tool", "user"),
                java.util.stream.StreamSupport.stream(encoded.spliterator(), false).map(node -> node.path("role").asText()).toList());
        assertEquals("call-2", encoded.get(2).path("tool_call_id").asText());
        assertEquals("firstlast", encoded.get(1).path("content").asText());
        var bridged = encoded.get(3).path("content");
        assertEquals("Tool result images for tool_call_id: call-1\n", bridged.get(0).path("text").asText());
        assertEquals("first", bridged.get(1).path("text").asText());
        assertEquals("image_url", bridged.get(2).path("type").asText());
        assertEquals("last", bridged.get(3).path("text").asText());
        assertEquals(result, conversation.items().get(2));
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OpenAiChatConversationCodec codec =
            new OpenAiChatConversationCodec();

    @Test
    void encodesFrozenCallReasoningAndToolResult() throws Exception {
        var history = List.of(new Message(Role.ASSISTANT, "调用前说明", null,
                        List.of(new ModelProvider.ToolCall("frozen-call", "frozen_wire", "{\"q\":\"杭州\"}"),
                                new ModelProvider.ToolCall("frozen-call-2", "frozen_weather", "{\"q\":\"宁波\"}")),
                        "先分析\n再调用"),
                new Message(Role.TOOL, "结果", "frozen-call", List.of()),
                new Message(Role.TOOL, "结果二", "frozen-call-2", List.of()));
        var conversation = CanonicalConversation.from(history);
        assertInstanceOf(CanonicalConversation.Message.class, conversation.items().get(0));
        assertInstanceOf(CanonicalConversation.Reasoning.class, conversation.items().get(1));
        assertInstanceOf(ToolCall.class, conversation.items().get(2));
        var messages = codec.encode(conversation, ChatCompletionsPolicy.standard());
        assertEquals(3, messages.size());
        assertEquals("调用前说明", messages.get(0).path("content").asText());
        assertEquals("先分析\n再调用", messages.get(0).path("reasoning_content").asText());
        var call = messages.get(0).path("tool_calls").get(0);
        assertEquals("frozen-call", call.path("id").asText());
        assertEquals("frozen_wire", call.path("function").path("name").asText());
        assertEquals("{\"q\":\"杭州\"}", call.path("function").path("arguments").asText());
        var second = messages.get(0).path("tool_calls").get(1);
        assertEquals("frozen-call-2", second.path("id").asText());
        assertEquals("frozen_weather", second.path("function").path("name").asText());
        assertEquals("{\"q\":\"宁波\"}", second.path("function").path("arguments").asText());
        assertEquals("tool", messages.get(1).path("role").asText());
        assertEquals("frozen-call", messages.get(1).path("tool_call_id").asText());
        assertEquals("结果", messages.get(1).path("content").asText());
        assertEquals("tool", messages.get(2).path("role").asText());
        assertEquals("frozen-call-2", messages.get(2).path("tool_call_id").asText());
        assertEquals("结果二", messages.get(2).path("content").asText());
    }

    @Test
    void mapsToolCallsAndResultsWithoutTreatingThemAsCanonicalMessages()
            throws Exception {
        var encoded = codec.encode(new CanonicalConversation(List.of(
                new ToolCall("tool-item", "call-1", "lookup", "{\"id\":1}"),
                new ToolResult("result-item", "call-1",
                        List.of(new TextContent("{\"ok\":true}"))))), ChatCompletionsPolicy.standard());

        assertEquals("assistant", encoded.get(0).path("role").textValue());
        assertEquals("call-1", encoded.get(0).path("tool_calls").get(0)
                .path("id").textValue());
        assertEquals("tool", encoded.get(1).path("role").textValue());
        assertEquals("call-1", encoded.get(1).path("tool_call_id").textValue());

        var decoded = codec.decodeAssistant(JSON.readTree("""
                {"role":"assistant","content":null,"tool_calls":[
                  {"id":"call-2","type":"function","function":{
                    "name":"search","arguments":"{\\\"q\\\":\\\"x\\\"}"}}
                ]}
                """), true, true);
        assertNull(decoded.assistantText());
        var call = decoded.toolCalls().getFirst();
        assertEquals("call-2", call.callId());
        assertEquals("search", call.name());
    }

    @Test
    void rejectsOpaqueReasoningUntilAProviderQuirkCanReplayItExactly() {
        var reasoning = new CanonicalConversation.Reasoning(
                "reasoning", "private", "signed-state",
                CanonicalConversation.ReplayPolicy.PRESERVE_EXACT);

        assertThrows(IOException.class,
                () -> codec.encode(new CanonicalConversation(List.of(reasoning)), ChatCompletionsPolicy.standard()));
    }

    @Test
    void decodesReasoningSeparatelyFromAssistantTextAndStreamingDelta()
            throws Exception {
        var decoded = codec.decodeAssistant(JSON.readTree("""
                {"role":"assistant","reasoning_content":"先分析\\n再回答","content":"答案"}
                """), true, true);

        assertEquals("答案", decoded.assistantText());
        assertEquals("先分析\n再回答", decoded.reasoningText().orElseThrow());
        var mixed = JSON.readTree("""
                {"reasoning_content":"思考分片","content":"正文分片"}
                """);
        assertEquals("思考分片", codec.decodeReasoningDelta(mixed).orElseThrow());
        assertEquals("正文分片", codec.decodeTextDelta(mixed).orElseThrow());
        assertTrue(codec.decodeReasoningDelta(JSON.readTree("{}" )).isEmpty());
    }

    @Test
    void preservesNativeInvalidArgumentsAndIgnoresDisabledReasoningFields() throws Exception {
        var response = JSON.readTree("""
                {"content":null,"reasoning_content":{},"tool_calls":[
                  {"id":"call-1","type":"function","function":{"name":"lookup","arguments":{}}},
                  {"id":"call-2","type":"function","function":{"name":"lookup","arguments":"{} trailing"}}
                ]}
                """);
        var decoded = codec.decodeAssistant(response, true, false);
        assertEquals("", decoded.toolCalls().get(0).argumentsJson());
        assertEquals("{} trailing", decoded.toolCalls().get(1).argumentsJson());
        assertTrue(decoded.reasoningText().isEmpty());
        assertThrows(IOException.class, () -> codec.decodeAssistant(response, false, false));
        assertThrows(IOException.class, () -> codec.decodeAssistant(response, true, true));
    }

    @Test
    void nullableAndEmptyAssistantFieldsKeepTheirProtocolMeaning() throws Exception {
        var absent = codec.decodeAssistant(JSON.readTree("{\"content\":null}"), true, true);
        assertNull(absent.assistantText());
        var empty = codec.decodeAssistant(JSON.readTree("{\"content\":\"\",\"reasoning_content\":\"\"}"), true, true);
        assertEquals("", empty.assistantText());
        assertEquals("", empty.reasoningText().orElseThrow());
    }
}
