package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformInteractiveToolHandler;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolInteractionResult;
import com.skillforge.core.capability.connector.TemplateRenderToolHandler;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputRequest;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse;
import java.util.Map;
import java.util.Optional;

/** 显式交互夹具只对指定模板请求补参；普通只读场景仍执行原 Handler。 */
final class LivePublicInteractiveFixture extends TemplateRenderToolHandler implements PlatformInteractiveToolHandler {
    static final String TEMPLATE = "验收补参数量{{quantity}}";
    private final ObjectMapper json = new ObjectMapper();
    @Override public PlatformToolInteractionResult invoke(Map<String, Object> arguments, PlatformToolExecutionContext context) throws Exception {
        if (!TEMPLATE.equals(arguments.get("template"))) return new PlatformToolInteractionResult.Completed(super.execute(arguments, context));
        var schema = Map.of("type", "object", "properties",
                Map.of("quantity", Map.of("type", "integer", "minimum", 1)),
                "required", java.util.List.of("quantity"));
        var payload = new TypedPayload("skillforge.runtime.tool.mcp-input-request.v1",
                json.writeValueAsString(Map.of("message", "请提供本次验收订单数量", "requestedSchema", schema)));
        var request = new ToolInputRequest(ToolInputMethod.ELICITATION_CREATE, payload);
        return new PlatformToolInteractionResult.InputRequired(Map.of("quantity", request),
                Optional.of("live-public-interactive-fixture-v1"));
    }
    @Override public PlatformToolInteractionResult resume(Map<String, Object> arguments, Map<String, ToolInputResponse> responses,
            Optional<String> state, PlatformToolExecutionContext context) throws Exception {
        if (!Optional.of("live-public-interactive-fixture-v1").equals(state) || !responses.keySet().equals(java.util.Set.of("quantity")))
            return new PlatformToolInteractionResult.Rejected(Map.of("success", false, "error", "fixture_response_mismatch"));
        var response = responses.get("quantity");
        if (response.method() != ToolInputMethod.ELICITATION_CREATE || !"application/json".equals(response.result().schemaId()))
            return new PlatformToolInteractionResult.Rejected(Map.of("success", false, "error", "fixture_response_mismatch"));
        var body = json.readTree(response.result().canonicalJson());
        if (!"accept".equals(body.path("action").asText()) || !body.path("content").path("quantity").isIntegralNumber()
                || body.path("content").path("quantity").asLong() != 27)
            return new PlatformToolInteractionResult.Rejected(Map.of("success", false, "error", "fixture_quantity_invalid"));
        return new PlatformToolInteractionResult.Completed(super.execute(Map.of("template", TEMPLATE, "data", Map.of("quantity", 27)), context));
    }
}
