package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.json.CodecLimits;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentExecutionToolOutcomeCapacityTest {
    private static final CodecLimits LARGE = new CodecLimits(1_000_000, 100_000, 128);
    private static final PortFailure FAILURE = new PortFailure("mcp_tool_rejected", RetryMode.NONE, new ResultRef("original-receipt"));

    @Test
    void outerEnvelopeKeepsExactFitAndDropsOnlyFactWhenOneUnitTooSmall() {
        var failed = failure("{\"text\":\"" + "\\\\".repeat(30_000) + "你好🌍\"}");
        var wide = new AgentExecutionCodec(LARGE);
        var raw = portals(LARGE).encodeTool(PortalCallKind.TOOL_INVOKE, failed);
        var full = wide.encodeOutcome(raw);
        int outerLength = full.canonicalJson().length();
        assertTrue(raw.outcome().canonicalJson().length() < outerLength - 1);

        var exactLimits = new CodecLimits(outerLength, 100_000, 128);
        var exact = new AgentExecutionCodec(exactLimits).encodeToolOutcome(portals(exactLimits), failed);
        assertEquals(failed, exact.outcome());
        assertEquals(full, exact.payload());
        assertEquals("EncodedToolOutcome[REDACTED]", exact.toString());

        var tightLimits = new CodecLimits(outerLength - 1, 100_000, 128);
        var tightCodec = new AgentExecutionCodec(tightLimits);
        var tight = tightCodec.encodeToolOutcome(portals(tightLimits), failed);
        assertEquals(new ToolOutcome.Failed(FAILURE), tight.outcome());
        assertEquals(tight.outcome(), tightCodec.decodeToolOutcome(tight.payload()));
    }

    @Test
    void coreLimitDropsFactButKeepsOriginalFailureAndInvalidFactsStillFail() {
        var limits = new CodecLimits(1024, 100_000, 128);
        var codec = new AgentExecutionCodec(limits);
        var failed = failure("{\"text\":\"" + "a".repeat(2000) + "\"}");
        var encoded = codec.encodeToolOutcome(portals(limits), failed);
        assertEquals(new ToolOutcome.Failed(FAILURE), encoded.outcome());
        assertEquals(encoded.outcome(), codec.decodeToolOutcome(encoded.payload()));

        var malformed = failure("{\"text\":\"" + "a".repeat(2000) + "\uD800\"}");
        assertThrows(IllegalArgumentException.class, () -> codec.encodeToolOutcome(portals(limits), malformed));
        var tooSmall = new CodecLimits(10, 100, 10);
        assertThrows(IllegalArgumentException.class, () -> new AgentExecutionCodec(tooSmall)
                .encodeToolOutcome(portals(tooSmall), new ToolOutcome.Failed(FAILURE)));
    }

    private static ToolOutcome.Failed failure(String json) {
        return new ToolOutcome.Failed(FAILURE, Optional.of(new TypedPayload("test.failure.v1", json)));
    }

    private static CoreRuntime.PortalResults portals(CodecLimits limits) {
        var payloads = new SessionRuntimePayloads();
        return new CoreRuntime(limits, payloads).portalResults(payloads, payloads, payloads);
    }
}
