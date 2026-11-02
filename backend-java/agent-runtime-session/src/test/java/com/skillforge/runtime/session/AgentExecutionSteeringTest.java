package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.json.CodecLimits;
import org.junit.jupiter.api.Test;

class AgentExecutionSteeringTest {
    private final AgentExecutionSteering codec = new AgentExecutionSteering(new CodecLimits(100_000, 10_000, 32));

    @Test
    void originalTextRoundTripsAsUserContentWithStableInputBoundIdentity() {
        var input = new InputId("delegation-send:actual-action"); String text = "  补充原文\n不要修改原因果身份  ";
        var payload = codec.encode(input, text); var message = codec.message(input, payload);
        assertEquals(input, codec.decode(payload).input()); assertEquals(text, message.textContent());
        assertEquals(ModelRole.USER, message.role()); assertEquals("execution-supplement:" + input.value(), message.messageId());
        assertTrue(message.toolCalls().isEmpty()); assertTrue(message.toolCallId().isEmpty()); assertTrue(message.reasoningContent().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> codec.message(new InputId("other"), payload));
    }

    @Test
    void rejectsMalformedUnknownAndOversizedInputsWithoutNormalizingOriginalText() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new InputId("input"), " "));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new InputId("input"), "a".repeat(8193)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new TypedPayload(AgentExecutionSteering.SCHEMA, "{\"inputId\":\"input\",\"text\":\"text\",\"role\":\"SYSTEM\"}")));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new TypedPayload("skillforge.runtime.execution.human-response.v1", "{\"response\":\"text\"}")));
    }
}
