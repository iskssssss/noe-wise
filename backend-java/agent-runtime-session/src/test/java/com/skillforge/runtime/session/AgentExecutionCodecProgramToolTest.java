package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.FrozenModelRequest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Session 只读取精确冻结请求中的公开工具契约，不授予宿主来源证明或派发权。 */
class AgentExecutionCodecProgramToolTest {
    private static final AgentExecutionCodec CODEC = new AgentExecutionCodec(new CodecLimits(100_000, 10_000, 32));
    private static final ModelTool VISIBLE = new ModelTool("sf_visible", "tool:clock:input", "clock",
            new TypedPayload("json-schema", "{}"), true, ModelToolKind.TOOL_CALL, false);

    @Test
    void programDeclarationsAreImmutableAndUseTheSameChoiceNameAndUniqueToolRules() {
        var source=new java.util.ArrayList<ModelTool>();source.add(VISIBLE);
        var declarations=new AgentExecutionCodec.ProgramTools(ToolChoice.AUTO,source);source.clear();
        assertEquals(VISIBLE,CODEC.requireProgramTool(declarations,"sf_visible"));
        assertThrows(UnsupportedOperationException.class,()->declarations.tools().clear());
        assertEquals("program_tool_not_available",assertThrows(AgentExecutionCodec.ProgramToolRejected.class,
                ()->CODEC.requireProgramTool(new AgentExecutionCodec.ProgramTools(ToolChoice.AUTO,List.of(VISIBLE,VISIBLE)),"sf_visible")).reasonCode());
        assertEquals("program_tool_requires_direct_call",assertThrows(AgentExecutionCodec.ProgramToolRejected.class,
                ()->CODEC.requireProgramTool(new AgentExecutionCodec.ProgramTools(ToolChoice.NONE,List.of(VISIBLE)),"sf_visible")).reasonCode());
    }

    @Test
    void visibleNameResolvesTheExactDeclarationFromActualEncodedRequest() {
        assertEquals(VISIBLE, CODEC.requireProgramTool(request(List.of(VISIBLE), ToolChoice.AUTO), "sf_visible"));
    }

    @Test
    void opaqueAliasesUnknownNamesAndControlDeclarationsRemainUnavailable() {
        var original = request(List.of(VISIBLE), ToolChoice.AUTO);
        for (String name : List.of("sf_unknown", "tool:clock:input", "tool:clock")) {
            assertEquals("program_tool_not_available", assertThrows(AgentExecutionCodec.ProgramToolRejected.class,
                    () -> CODEC.requireProgramTool(original, name)).reasonCode());
        }
        var control = new ModelTool("sf_control", "runtime:agent-action", "control",
                new TypedPayload("json-schema", "{}"), true, ModelToolKind.AGENT_ACTION, false);
        assertEquals("program_tool_not_available", assertThrows(AgentExecutionCodec.ProgramToolRejected.class,
                () -> CODEC.requireProgramTool(request(List.of(control), ToolChoice.AUTO), "sf_control")).reasonCode());
    }

    @Test
    void noToolChoicePreservesTheDirectCallRejection() {
        assertEquals("program_tool_requires_direct_call", assertThrows(AgentExecutionCodec.ProgramToolRejected.class,
                () -> CODEC.requireProgramTool(request(List.of(VISIBLE), ToolChoice.NONE), "sf_visible")).reasonCode());
    }

    @Test
    void originalPreparedRequestMustPassStrictSchemaAndEnvelopeValidation() {
        var original = request(List.of(VISIBLE), ToolChoice.AUTO);
        assertThrows(IllegalArgumentException.class,
                () -> CODEC.requireProgramTool(new TypedPayload("application/json", original.canonicalJson()), "sf_visible"));
        assertThrows(IllegalArgumentException.class,
                () -> CODEC.requireProgramTool(new TypedPayload(original.schemaId(),
                        original.canonicalJson().replace("\"output\":null", "\"output\":null,\"unknown\":true")), "sf_visible"));
    }

    private static TypedPayload request(List<ModelTool> declarations, ToolChoice choice) {
        var target = new FrozenModelTarget(new ModelBindingRef(new TenantId("1"), "model", 1), "model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), true, true, new ResultRef("capability")),
                Duration.ofSeconds(30), new ResultRef("resolution"));
        var request = new FrozenModelRequest(new RequestedModelStrategy("strategy", "1"), target,
                List.of(new ModelMessage("user", ModelRole.USER, "run")), declarations, choice,
                new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "supported"),
                new GenerationOptions(OptionalInt.empty(), OptionalDouble.empty()), ReasoningPolicy.DEFAULT);
        return CODEC.encodeModelRequest(new AgentExecutionCodec.ModelCallRequest(request, List.of(), 0, Optional.empty()));
    }
}
