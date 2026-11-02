package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.capability.connector.PlatformToolInteractionResult;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 无 Spring、数据库或模型调用的交互夹具契约回归。 */
class LivePublicInteractiveFixtureTest {
    @Test void continuationUsesTheRuntimeRegisteredJsonSchemaRatherThanAnUnregisteredFixtureSchema() {
        var registry = new com.skillforge.runtime.session.SessionRuntimePayloads();
        var limits = new com.skillforge.runtime.json.CodecLimits(4096, 256, 32);
        String body = "{\"action\":\"accept\",\"content\":{\"quantity\":27}}";
        assertThrows(IllegalArgumentException.class,
                () -> registry.validate(new TypedPayload("mcp.elicitation-result.v1", body), limits));
        assertDoesNotThrow(() -> registry.validate(new TypedPayload("application/json", body), limits));
    }

    @Test void acceptedQuantityResumesTheActualWaitStateAndProducesDeclaredToolResult() throws Exception {
        var fixture = new LivePublicInteractiveFixture();
        var arguments = Map.<String, Object>of("template", LivePublicInteractiveFixture.TEMPLATE, "data", Map.of());
        var wait = assertInstanceOf(PlatformToolInteractionResult.InputRequired.class, fixture.invoke(arguments, null));
        assertEquals(java.util.Set.of("quantity"), wait.inputRequests().keySet());
        assertEquals(ToolInputMethod.ELICITATION_CREATE, wait.inputRequests().get("quantity").method());
        var response = new ToolInputResponse(ToolInputMethod.ELICITATION_CREATE,
                new TypedPayload("application/json", "{\"action\":\"accept\",\"content\":{\"quantity\":27}}"));
        var result = assertInstanceOf(PlatformToolInteractionResult.Completed.class,
                fixture.resume(arguments, Map.of("quantity", response), wait.requestState(), null));
        assertEquals(Map.of("success", true, "rendered", "验收补参数量27"), result.output());
    }

    @Test void rejectedInputCannotPretendToBeSuccessfulContinuation() throws Exception {
        var fixture = new LivePublicInteractiveFixture();
        var arguments = Map.<String, Object>of("template", LivePublicInteractiveFixture.TEMPLATE, "data", Map.of());
        var wait = assertInstanceOf(PlatformToolInteractionResult.InputRequired.class, fixture.invoke(arguments, null));
        var response = new ToolInputResponse(ToolInputMethod.ELICITATION_CREATE,
                new TypedPayload("application/json", "{\"action\":\"decline\"}"));
        var result = assertInstanceOf(PlatformToolInteractionResult.Rejected.class,
                fixture.resume(arguments, Map.of("quantity", response), wait.requestState(), null));
        assertEquals(false, result.output().get("success"));
    }
}
