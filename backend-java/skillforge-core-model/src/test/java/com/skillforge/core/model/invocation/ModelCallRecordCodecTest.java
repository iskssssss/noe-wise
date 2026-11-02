package com.skillforge.core.model.invocation;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.media.ImageDetail;
import com.skillforge.runtime.api.port.ModelPort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** 核实际语义字段完整往返，不将凭据或执行函数变成材料。 */
class ModelCallRecordCodecTest {
    private final ModelCallRecordCodec codec = new ModelCallRecordCodec();

    @Test void preservesTheActualRequestAndOpenArgumentsWithoutEndpointCredentialOrFunctions() {
        var actual = actualRequest();
        var material = codec.request("deepseek", actual);
        var value = codec.decodeRequest(material);
        assertEquals("model-x", value.modelId());
        assertEquals(actual.profile(), value.profile());
        assertEquals(actual.timeout().toString(), value.timeout());
        assertEquals(123, value.generation().maxOutputTokens());
        assertEquals(0.4, value.generation().temperature());
        assertEquals(ModelPort.ReasoningPolicy.DEFAULT, value.reasoning());
        assertEquals(actual.tools(), value.tools());
        assertEquals(ModelProvider.ToolChoice.AUTO, value.toolChoice());
        assertEquals("系统完整原文", value.messages().getFirst().contents().getFirst().text());
        assertEquals("7", value.messages().get(1).contents().get(1).media().owner().tenantId().value());
        assertEquals("IMAGE", value.messages().get(1).contents().get(1).type());
        assertEquals("{ invalid arguments", value.messages().get(2).toolCalls().getFirst().argumentsJson());
        assertEquals("前序推理", value.messages().get(2).reasoningContent());
        assertFalse(material.canonicalJson().contains("private-credential"));
        assertFalse(material.canonicalJson().contains("private-endpoint"));
        assertFalse(material.canonicalJson().contains("mediaReader"));
        assertFalse(material.canonicalJson().contains("cancelled"));
        assertEquals("ModelCallRequest[REDACTED]", value.toString());
    }

    @Test void keepsCompleteReturnedTextReasoningToolsFinishAndUnknownVersusZeroUsage() {
        String text = "完整返回".repeat(40_000);
        var actual = new ModelProvider.InvocationResult.Completed(text,
                List.of(new ModelProvider.ToolCall("call", "actual-tool", "{bad")), "model-x",
                new ModelPort.ModelUsage(OptionalLong.of(9_007_199_254_740_993L), OptionalLong.of(0),
                        OptionalLong.empty(), OptionalLong.of(0), OptionalLong.of(19)), "length", "供应商完整推理");
        var value = codec.decodeOutcome(codec.outcome(actual));
        assertEquals(ModelCallRecordCodec.OutcomeKind.COMPLETED, value.kind());
        assertEquals(text, value.content());
        assertEquals("供应商完整推理", value.reasoningContent());
        assertEquals("length", value.finishReasonCode());
        assertEquals(actual.toolCalls(), value.toolCalls());
        assertEquals(9_007_199_254_740_993L, value.usage().inputTokens());
        assertEquals(0L, value.usage().outputTokens());
        assertNull(value.usage().cachedInputTokens());
        assertEquals(0L, value.usage().cacheWriteInputTokens());
        assertEquals(19L, value.usage().reasoningOutputTokens());
        assertEquals("ModelCallOutcome[REDACTED]", value.toString());
    }

    @Test void preservesRejectedAndUnknownWithTheirOwnUsageAndRetryFacts() {
        var usage = new ModelPort.ModelUsage(OptionalLong.of(2), OptionalLong.empty());
        var retryAt = Instant.parse("2026-10-25T02:00:00Z");
        var rejected = codec.decodeOutcome(codec.outcome(new ModelProvider.InvocationResult.Rejected(
                "rate_limited", java.util.Optional.of(retryAt), usage)));
        assertEquals(ModelCallRecordCodec.OutcomeKind.REJECTED, rejected.kind());
        assertEquals("rate_limited", rejected.reasonCode());
        assertEquals(retryAt.toString(), rejected.retryAt());
        assertNull(rejected.content());
        assertEquals(2L, rejected.usage().inputTokens());
        assertNull(rejected.usage().outputTokens());
        var unknown = codec.decodeOutcome(codec.outcome(new ModelProvider.InvocationResult.OutcomeUnknown("transport_unknown", usage)));
        assertEquals(ModelCallRecordCodec.OutcomeKind.OUTCOME_UNKNOWN, unknown.kind());
        assertEquals("transport_unknown", unknown.reasonCode());
        assertNull(unknown.finishReasonCode());
        assertNull(unknown.reasoningContent());
    }

    @Test void rejectsUnknownSchemaUnknownFieldsAndNoncanonicalMaterialWithoutEchoingContent() {
        var valid = codec.request("deepseek", actualRequest());
        var schema = assertThrows(IllegalArgumentException.class,
                () -> codec.decodeRequest(new TypedPayload("old-schema", valid.canonicalJson())));
        assertEquals("model_call_material_invalid", schema.getMessage());
        assertThrows(IllegalArgumentException.class, () -> codec.decodeRequest(new TypedPayload(valid.schemaId(),
                valid.canonicalJson().replaceFirst("\\{", "{\"private-unexpected\":true,"))));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeRequest(new TypedPayload(valid.schemaId(), " " + valid.canonicalJson())));
    }

    static ModelProvider.InvocationRequest actualRequest() {
        var media = new MediaRef(new SessionRef(new TenantId("7"), "session"), "image", "a".repeat(64), "image/png", 10, 1, 1);
        return new ModelProvider.InvocationRequest("https://private-endpoint", "private-credential", "model-x",
                new ModelProvider.InvocationProfile("service", "profile-1", ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                        ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY, "dialect-1"),
                List.of(new ModelProvider.Message(ModelProvider.Role.SYSTEM, "系统完整原文", null, List.of()),
                        new ModelProvider.Message(ModelProvider.Role.USER,
                                List.of(new ModelContent.Text("用户原问题"), new ModelContent.Image(media, ImageDetail.AUTO)), null, List.of(), null),
                        new ModelProvider.Message(ModelProvider.Role.ASSISTANT, (String) null, null,
                                List.of(new ModelProvider.ToolCall("call", "actual-tool", "{ invalid arguments")), "前序推理")),
                new ModelPort.ResponseContract(new ModelPort.ResponseFormat.JsonSchema(new TypedPayload("application/json", "{\"type\":\"object\"}")),
                        ModelPort.EnforcementLevel.STRICT, "supported"),
                new ModelPort.GenerationOptions(OptionalInt.of(123), OptionalDouble.of(0.4)), ModelPort.ReasoningPolicy.DEFAULT,
                List.of(new ModelProvider.ToolDefinition("actual-tool", "原始工具说明", new TypedPayload("application/json", "{\"type\":\"object\"}"),
                        true, ModelPort.ModelToolKind.TOOL_CALL, true)), ModelProvider.ToolChoice.AUTO,
                Duration.ofSeconds(3, 123), () -> false, () -> { });
    }
}
