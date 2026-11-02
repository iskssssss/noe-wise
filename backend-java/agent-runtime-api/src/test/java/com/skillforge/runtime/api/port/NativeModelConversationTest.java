package com.skillforge.runtime.api.port;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ModelToolCall;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NativeModelConversationTest {
    /** 多调用生成资格只放行普通工具与准确原生资源动作；名称本身不能提升其他控制。 */
    @Test void multiCallGenerationEligibilityAllowsOnlyOrdinaryToolsAndExactNativeResourceAction() {
        var schema = new TypedPayload("json-schema", "{}");
        for (var kind : ModelPort.ModelToolKind.values()) for (String wire : List.of("read_skill_resource", "ask_user", "read_skill_resource_extra", "Read_skill_resource")) {
            boolean eligible = kind == ModelPort.ModelToolKind.TOOL_CALL || wire.equals("read_skill_resource");
            if (eligible) assertTrue(new ModelPort.ModelTool(wire, "opaque", "fixture", schema, false, kind, true).batchEligible());
            else assertThrows(IllegalArgumentException.class, () -> new ModelPort.ModelTool(wire, "opaque", "fixture", schema, false, kind, true));
            assertDoesNotThrow(() -> new ModelPort.ModelTool(wire, "opaque", "fixture", schema, false, kind, false));
        }
    }

    /** 完整回合保留内容顺序和非法参数，不因字段像动作JSON而改变消息语义。 */
    @Test
    void assistantTurnKeepsImmutableOrderedRawContent() {
        var text = new ModelPort.AssistantContent.Text("{\"type\":\"respond\"}");
        var call = new ModelPort.AssistantContent.ToolCall("provider-call", "read_file", "{\"path\":");
        var source = new java.util.ArrayList<ModelPort.AssistantContent>(List.of(text, call,
                new ModelPort.AssistantContent.Text("尾部正文")));
        var turn = new ModelPort.AssistantTurn(source);
        source.clear();
        assertEquals(List.of(text, call, new ModelPort.AssistantContent.Text("尾部正文")), turn.contents());
        assertEquals("{\"type\":\"respond\"}尾部正文", turn.content());
        assertEquals(List.of(call), turn.toolCalls());
        assertEquals(ModelPort.AssistantTurnKind.TOOL_CALLS, turn.kind());
        assertThrows(UnsupportedOperationException.class, () -> turn.contents().clear());
        assertEquals(ModelPort.AssistantTurnKind.TEXT, new ModelPort.AssistantTurn(List.of()).kind());
    }

    @Test
    void nativeHistoryRequiresImmediateUniqueMatchingResults() {
        var call = call("a", "call-1");
        var result = result("r", "call-1");
        assertDoesNotThrow(() -> ModelPort.validateConversation(List.of(call, result)));
        assertThrows(IllegalArgumentException.class, () -> ModelPort.validateConversation(List.of(call)));
        assertThrows(IllegalArgumentException.class, () -> ModelPort.validateConversation(List.of(result)));
        assertThrows(IllegalArgumentException.class, () -> ModelPort.validateConversation(List.of(call, result("bad", "other"))));
        assertThrows(IllegalArgumentException.class, () -> ModelPort.validateConversation(List.of(call,
                new ModelMessage("interleaved", ModelRole.USER, "pretend success"), result)));
        assertThrows(IllegalArgumentException.class, () -> ModelPort.validateConversation(List.of(call, result,
                call("again", "call-1"), result("again-result", "call-1"))));
    }

    @Test
    void nativeBatchRequiresAllResultsInFrozenCallOrder() {
        var batch = calls("batch", "call-1", "call-2");
        var first = result("result-1", "call-1");
        var second = result("result-2", "call-2");
        assertDoesNotThrow(() -> ModelPort.validateConversation(List.of(batch, first, second)));
        assertThrows(IllegalArgumentException.class,
                () -> ModelPort.validateConversation(List.of(batch, second, first)));
        assertThrows(IllegalArgumentException.class,
                () -> ModelPort.validateConversation(List.of(batch, first)));
        assertThrows(IllegalArgumentException.class,
                () -> ModelPort.validateConversation(List.of(batch, first,
                        new ModelMessage("interleaved", ModelRole.USER, "next"), second)));
        assertThrows(IllegalArgumentException.class,
                () -> calls("duplicate", "call-1", "call-1"));
        assertThrows(IllegalArgumentException.class,
                () -> calls("too-many", java.util.stream.IntStream.rangeClosed(1, com.skillforge.runtime.api.ToolCallLimits.READ_ONLY_BATCH + 1)
                        .mapToObj(index -> "call-" + index).toArray(String[]::new)));
    }

    @Test
    void rolesCannotSmuggleCallsOrResultsAndBudgetIncludesNativeFields() {
        var call = call("a", "call-1");
        assertThrows(IllegalArgumentException.class, () -> new ModelMessage("u", ModelRole.USER, "",
                call.toolCalls(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ModelMessage("u", ModelRole.TOOL, "result"));
        assertThrows(IllegalArgumentException.class, () -> new ModelMessage("u", ModelRole.USER, "result",
                List.of(), Optional.of("call-1"), Optional.empty()));
        assertTrue(call.accountingText().contains("Asia/Shanghai"));
        assertTrue(call.accountingText().contains("sf_tool_time"));
        assertTrue(call.accountingText().contains("retained reasoning"));
    }

    private static ModelMessage call(String id, String callId) {
        return new ModelMessage(id, ModelRole.ASSISTANT, "", List.of(new ModelToolCall(callId,
                "sf_tool_time", new TypedPayload("application/json", "{\"timezone\":\"Asia/Shanghai\"}"))),
                Optional.empty(), Optional.of("retained reasoning"));
    }

    private static ModelMessage calls(String id, String... callIds) {
        return new ModelMessage(id, ModelRole.ASSISTANT, "",
                java.util.Arrays.stream(callIds).map(callId -> new ModelToolCall(callId,
                        "sf_tool_time", new TypedPayload("application/json", "{\"timezone\":\"Asia/Shanghai\"}")))
                        .toList(), Optional.empty(), Optional.empty());
    }

    private static ModelMessage result(String id, String callId) {
        return new ModelMessage(id, ModelRole.TOOL, "tool result", List.of(), Optional.of(callId), Optional.empty());
    }
}
