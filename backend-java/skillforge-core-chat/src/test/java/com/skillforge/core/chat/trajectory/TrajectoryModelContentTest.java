package com.skillforge.core.chat.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ModelBindingRef;
import com.skillforge.runtime.api.port.ModelPort.ModelCapabilitySnapshot;
import com.skillforge.runtime.api.port.ModelPort.RequestedModelStrategy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.core.FrozenModelRequest;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrajectoryModelContentTest {
    private static final CodecLimits LIMITS = new CodecLimits(1_000_000, 10_000, 128);
    private static final String RAW_OUTPUT = "{\"value\":9007199254740993,\"precise\":0.123456789012345678901234567890,\"answer\":\""
            + "长正文".repeat(10_000) + "password=业务原文\"}";
    private static final String RAW_TEXT_PAYLOAD = new ObjectMapper().valueToTree(RAW_OUTPUT).toString();

    @Test
    void preservesRecordedTextReasoningToolIntentUsageAndFinishWithoutExecutionReferences() throws Exception {
        var value = TrajectoryModelContent.outcome(completed());
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(value);
        assertEquals("SUCCEEDED", json.path("status").asText());
        assertEquals("tool_calls", json.path("finish_reason").asText());
        assertEquals("完整供应商推理", json.path("reasoning_content").asText());
        assertEquals(RAW_OUTPUT, json.path("assistant_turn").get(0).path("text").asText());
        assertEquals("{\"value\":17}", json.path("assistant_turn").get(1).path("arguments_json").asText());
        assertEquals("9007199254740993", json.path("usage").path("input_tokens").asText());
        assertTrue(json.toString().contains("password=业务原文"));
        assertFalse(json.toString().contains("private-result-reference"));
        assertEquals(RAW_TEXT_PAYLOAD, json.path("output").path("canonical_json").asText());
        assertEquals(RAW_OUTPUT, new ObjectMapper().readTree(json.path("output").path("canonical_json").asText()).asText());
    }

    static TypedPayload request() {
        var target = new FrozenModelTarget(new ModelBindingRef(new TenantId("7"), "private-binding", 1), "model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false, true, new ResultRef("private-capability")),
                Duration.ofSeconds(30), new ResultRef("private-resolution"));
        var request = new FrozenModelRequest(new RequestedModelStrategy("strategy", "1"), target,
                List.of(new ModelPort.ModelMessage("user", ModelPort.ModelRole.USER, "原始用户输入")),
                new ResponseContract(new ResponseFormat.Text(), ModelPort.EnforcementLevel.STRICT, "supported"),
                new GenerationOptions(OptionalInt.of(100), OptionalDouble.empty()), ModelPort.ReasoningPolicy.DEFAULT);
        return new AgentExecutionCodec(LIMITS).encodeModelRequest(
                new AgentExecutionCodec.ModelCallRequest(request, List.of(), 0, Optional.empty()));
    }

    static TypedPayload outcome() {
        var payloads = new SessionRuntimePayloads();
        return new AgentExecutionCodec(LIMITS).encodeOutcome(new CoreRuntime(LIMITS, payloads)
                .portalResults(payloads, payloads, payloads).encodeModel(PortalCallKind.MODEL_GENERATE, completed()));
    }

    private static ModelPort.ModelResult.Completed completed() {
        return new ModelPort.ModelResult.Completed(new TypedPayload(ModelPort.TEXT_OUTPUT_SCHEMA, RAW_TEXT_PAYLOAD),
                "model", new ModelPort.ModelUsage(OptionalLong.of(9007199254740993L), OptionalLong.of(9)), "tool_calls",
                new ResultRef("private-result-reference"), Optional.of("完整供应商推理"),
                Optional.of(new ModelPort.AssistantTurn(List.of(new ModelPort.AssistantContent.Text(RAW_OUTPUT),
                        new ModelPort.AssistantContent.ToolCall("call-1", "read", "{\"value\":17}")))));
    }
}
