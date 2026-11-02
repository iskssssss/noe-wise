package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ModelBindingRef;
import com.skillforge.runtime.api.port.ModelPort.ModelCapabilitySnapshot;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ModelTool;
import com.skillforge.runtime.api.port.ModelPort.ToolChoice;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.RequestedModelStrategy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证完整规范模型请求的冻结与精确编码；不以这些测试证明策略或持久化已完成。 */
class FrozenModelRequestTest {
    @Test void freezesPrivateNativeStateAndRejectsCrossModelOrUserReplay() {
        var base = request(new ResponseFormat.JsonObject(), options());
        var responses = new com.skillforge.runtime.api.port.ModelPort.NativeProtocolState(
                com.skillforge.runtime.api.port.ModelPort.NativeProtocol.RESPONSES, "responses-v1", base.target().effectiveModelId(), List.of(
                new TypedPayload("responses.output-item.v1", "{\"type\":\"reasoning\",\"encrypted_content\":\"PRIVATE-ENCRYPTED\"}")));
        var messages = new com.skillforge.runtime.api.port.ModelPort.NativeProtocolState(
                com.skillforge.runtime.api.port.ModelPort.NativeProtocol.MESSAGES, "deepseek-messages-v1", base.target().effectiveModelId(), List.of(
                new TypedPayload("deepseek.messages.content-block.v1", "{\"type\":\"thinking\",\"thinking\":\"PRIVATE-THOUGHT\",\"signature\":\"PRIVATE-SIGNATURE\"}")));
        for (var state : List.of(responses, messages)) {
            var assistant = new ModelMessage("native-assistant", ModelRole.ASSISTANT, "answer", List.of(), java.util.Optional.empty(),
                    java.util.Optional.empty(), java.util.Optional.of(state));
            var nativeRequest = new FrozenModelRequest(base.requestedStrategy(), base.target(), List.of(assistant), base.tools(), base.toolChoice(),
                    base.response(), base.generation(), base.reasoning());
            var restored = decode(CanonicalJson.encode(nativeRequest.canonicalValue()));
            assertEquals(nativeRequest, restored);
            assertEquals(state.items(), restored.messages().getFirst().protocolState().orElseThrow().items());
            assertTrue(assistant.accountingText().contains(state.items().getFirst().canonicalJson()));
            assertFalse(assistant.toString().contains("PRIVATE-"));
            assertThrows(IllegalArgumentException.class, () -> new ModelMessage("invalid-user", ModelRole.USER, "question", List.of(),
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(state)));
            var wrong = new com.skillforge.runtime.api.port.ModelPort.NativeProtocolState(state.protocol(), state.dialectVersion(), "other-model", state.items());
            var changed = new ModelMessage("wrong-model", ModelRole.ASSISTANT, "answer", List.of(), java.util.Optional.empty(),
                    java.util.Optional.empty(), java.util.Optional.of(wrong));
            assertThrows(IllegalArgumentException.class, () -> new FrozenModelRequest(base.requestedStrategy(), base.target(), List.of(changed),
                    base.tools(), base.toolChoice(), base.response(), base.generation(), base.reasoning()));
        }
    }

    /** 能力和保留声明一并参与规范编码；恢复不查询当前服务能力。 */
    @Test void freezesPrefixCapabilitiesAndRetainedNoneDeclarations() {
        var base = request(new ResponseFormat.NativeConversation(), options());
        var prefix = new com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities(
                com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus.UNVERIFIED,
                com.skillforge.runtime.api.port.ModelPort.ToolUpdateMode.NONE,
                com.skillforge.runtime.api.port.ModelPort.CapabilitySupportStatus.SUPPORTED);
        var target = new FrozenModelTarget(base.target().binding(), base.target().effectiveModelId(),
                new ModelCapabilitySnapshot(base.target().capabilities().responseFormats(), true, true,
                        new ResultRef("capability"), prefix), base.target().timeout(), base.target().resolutionRef());
        var tools = List.of(new ModelTool("wire", "opaque", "冻结声明", new TypedPayload("schema.v1", "{}"), false, com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL, false));
        var retained = new FrozenModelRequest(base.requestedStrategy(), target, base.messages(), tools, ToolChoice.NONE,
                base.response(), base.generation(), base.reasoning());
        var encoded = CanonicalJson.encode(retained.canonicalValue());
        assertEquals(retained, decode(encoded));
        assertEquals(prefix, decode(encoded).target().capabilities().prefixCapabilities());
        assertFalse(decode(encoded).tools().getFirst().batchEligible());
        assertTrue(encoded.contains("\"kind\":\"NATIVE_CONVERSATION\""));
        assertRejected(encoded.replace("NATIVE_CONVERSATION", "NATIVE_DECISION"));
        assertEquals("skillforge.runtime.execution.model-request.v2", retained.encode().schemaId());
        assertThrows(IllegalArgumentException.class, () -> FrozenModelRequest.decode(
                new TypedPayload("skillforge.runtime.execution.model-request.v1", encoded),
                new CodecLimits(100000, 10000, 32)));
        assertRejected(encoded.replace("\"batchEligible\":false,", ""));
        assertRejected(encoded.replace("\"toolUpdates\":\"NONE\"", "\"toolUpdates\":\"IN_HISTORY\""));
        assertRejected(encoded.replace("\"systemUpdatesInHistory\":\"UNVERIFIED\",", ""));
        for (var choice : List.of(ToolChoice.AUTO, ToolChoice.REQUIRED))
            assertThrows(IllegalArgumentException.class, () -> new FrozenModelRequest(base.requestedStrategy(),
                    target, base.messages(), List.of(), choice, base.response(), base.generation(), base.reasoning()));
    }

    @Test
    void freezesOrderedImagesAndRejectsDisabledVision() {
        var base=request(new ResponseFormat.Text(),options());
        var policy=new com.skillforge.runtime.api.media.ModelImagePolicy(true,true,4,100000,200000,2048,512,85,170,"vision.v1");
        var target=new FrozenModelTarget(base.target().binding(),base.target().effectiveModelId(),
                new ModelCapabilitySnapshot(base.target().capabilities().responseFormats(),true,true,new ResultRef("vision"),policy),
                base.target().timeout(),base.target().resolutionRef());
        var media=new com.skillforge.runtime.api.media.MediaRef(new com.skillforge.runtime.api.SessionRef(new TenantId("tenant"),"session"),
                "image","a".repeat(64),"image/png",1234,512,512);
        var contents=List.<com.skillforge.runtime.api.media.ModelContent>of(new com.skillforge.runtime.api.media.ModelContent.Text("前"),
                new com.skillforge.runtime.api.media.ModelContent.Image(media,com.skillforge.runtime.api.media.ImageDetail.HIGH),
                new com.skillforge.runtime.api.media.ModelContent.Text("后"));
        var message=new ModelMessage("image",ModelRole.USER,contents);
        var request=new FrozenModelRequest(base.requestedStrategy(),target,List.of(message),base.response(),base.generation(),base.reasoning());
        assertEquals(request,decode(CanonicalJson.encode(request.canonicalValue())));
        assertEquals("前后",message.textContent());
        assertThrows(IllegalArgumentException.class,()->new FrozenModelRequest(base.requestedStrategy(),base.target(),
                List.of(message),base.response(),base.generation(),base.reasoning()));
    }

    /** Golden bytes 手写，覆盖所有 ModelCall 稳定参数、纳秒 timeout 与超大 revision。 */
    @Test
    void matchesCompleteGoldenModelRequest() {
        var request = request(new ResponseFormat.JsonObject(), options());
        String expected = "{\"budgetCategory\":\"BUSINESS_MODEL\",\"generation\":{\"maxOutputTokens\":\"64\",\"temperature\":\"0.25\"},"
                + "\"messages\":[{\"contents\":[{\"text\":\"问\",\"type\":\"text\"}],\"messageId\":\"m\",\"protocolState\":null,\"reasoningContent\":null,"
                + "\"role\":\"USER\",\"toolCallId\":null,\"toolCalls\":[]}],"
                + "\"reasoning\":\"DEFAULT\",\"requestedStrategy\":{\"strategyId\":\"strategy\",\"strategyVersion\":\"1\"},"
                + "\"response\":{\"enforcement\":\"STRICT\",\"format\":{\"kind\":\"JSON_OBJECT\",\"schema\":null},\"negotiationReasonCode\":\"supported\"},"
                + "\"target\":{\"binding\":{\"bindingId\":\"binding\",\"revision\":\"9223372036854775807\",\"tenantId\":\"tenant\"},"
                + "\"capabilities\":{\"capabilityFactRef\":\"capability\",\"imagePolicy\":{\"baseTokens\":\"0\",\"enabled\":false,\"maxDimension\":\"0\",\"maxImageBytes\":\"0\",\"maxImages\":\"0\",\"maxRequestBytes\":\"0\",\"tileSize\":\"0\",\"tileTokens\":\"0\",\"toolImages\":false,\"version\":\"text-only.v1\"},\"prefixCapabilities\":{\"retainToolsWithToolChoiceNone\":\"UNVERIFIED\",\"systemUpdatesInHistory\":\"UNVERIFIED\",\"toolUpdates\":\"UNVERIFIED\"},\"reasoning\":true,"
                + "\"responseFormats\":[\"JSON_OBJECT\",\"JSON_SCHEMA\",\"TEXT\"],\"streaming\":true},"
                + "\"effectiveModelId\":\"model\",\"resolutionRef\":\"resolution\",\"timeout\":\"PT1.000000001S\"},"
                + "\"toolChoice\":\"NONE\",\"tools\":[]}";
        assertEquals(expected, CanonicalJson.encode(request.canonicalValue()));
        assertEquals(request, decode(expected));
        assertTrue(decode(expected).messages().getFirst().protocolState().isEmpty());
        assertRejected(expected.replace("\"protocolState\":null,", ""));
        assertEquals("FrozenModelRequest[REDACTED]", request.toString());
    }

    /** 无参格式必须由 kind 区分，Schema 正文编码为字符串而不是直接拼接进外围对象。 */
    @Test
    void preservesAllResponseVariantsAndOpaqueSchemaText() {
        String text = CanonicalJson.encode(request(new ResponseFormat.Text(), options()).canonicalValue());
        String json = CanonicalJson.encode(request(new ResponseFormat.JsonObject(), options()).canonicalValue());
        String schema = CanonicalJson.encode(request(new ResponseFormat.JsonSchema(
                new TypedPayload("decision.v1", "{\"type\":\"object\"}")), options()).canonicalValue());
        assertTrue(text.contains("\"format\":{\"kind\":\"TEXT\",\"schema\":null}"));
        assertNotEquals(text, json);
        assertTrue(schema.contains("\"format\":{\"kind\":\"JSON_SCHEMA\",\"schema\":{"
                + "\"canonicalJson\":\"{\\\"type\\\":\\\"object\\\"}\",\"schemaId\":\"decision.v1\"}}"));
        for (String encoded : List.of(text, json, schema)) {
            assertEquals(encoded, CanonicalJson.encode(decode(encoded).canonicalValue()));
        }
    }

    /** JSON 合法不代表请求规范：拒绝别名、溢出和目标能力集合的非规范顺序。 */
    @Test
    void rejectsNoncanonicalTypedValues() {
        String golden = CanonicalJson.encode(request(new ResponseFormat.JsonObject(), options()).canonicalValue());
        for (String temperature : List.of("0.250", "2.5e-1", "NaN", "Infinity", "-0", "0x1.0p-2", "3", "secret-invalid")) {
            assertRejected(golden.replace("\"temperature\":\"0.25\"", "\"temperature\":\"" + temperature + "\""));
        }
        for (String changed : List.of(
                golden.replace("PT1.000000001S", "PT0H1.000000001S"),
                golden.replace("PT1.000000001S", "not-a-duration"),
                golden.replace("\"maxOutputTokens\":\"64\"", "\"maxOutputTokens\":\"2147483648\""),
                golden.replace("9223372036854775807", "9223372036854775808"),
                golden.replace("[\"JSON_OBJECT\",\"JSON_SCHEMA\",\"TEXT\"]", "[\"TEXT\",\"JSON_OBJECT\",\"JSON_SCHEMA\"]"),
                golden.replace("[\"JSON_OBJECT\",\"JSON_SCHEMA\",\"TEXT\"]", "[\"JSON_OBJECT\",\"JSON_OBJECT\",\"TEXT\"]"),
                golden.replace("[\"JSON_OBJECT\",\"JSON_SCHEMA\",\"TEXT\"]", "[\"TEXT\"]"))) {
            assertRejected(changed);
        }
    }

    /** 严格字段闭包逐层执行；缺失与 null、错误类型及未知枚举不能获得默认值。 */
    @Test
    void rejectsNestedSchemaDriftWithoutLeakingInput() {
        String golden = CanonicalJson.encode(request(new ResponseFormat.JsonObject(), options()).canonicalValue());
        for (String changed : List.of(
                golden.replace("\"budgetCategory\":\"BUSINESS_MODEL\",", ""),
                golden.replace("\"budgetCategory\":\"BUSINESS_MODEL\"", "\"budgetCategory\":\"TOOL\""),
                golden.replace("\"strategyVersion\":\"1\"", "\"strategyVersion\":\"1\",\"unknown\":null"),
                golden.replace("\"strategyVersion\":\"1\"", "\"strategyVersion\":null"),
                golden.replace("\"maxOutputTokens\":\"64\",", ""),
                golden.replace("\"streaming\":true", "\"streaming\":\"true\""),
                golden.replace("\"role\":\"USER\"", "\"role\":\"secret-invalid\""),
                golden.replace("\"schema\":null", "\"schema\":{}"),
                golden.replace("\"messageId\":\"m\",", ""))) {
            assertRejected(changed);
        }
    }

    /** Optional 空值和边界小数仍须真正走 decoder，而非仅验证 encoder 的字符串。 */
    @Test
    void decodesExplicitAbsenceAndFiniteCanonicalTemperatures() {
        var absent = request(new ResponseFormat.Text(), new GenerationOptions(OptionalInt.empty(), OptionalDouble.empty()));
        assertEquals(absent, decode(CanonicalJson.encode(absent.canonicalValue())));
        for (double temperature : List.of(0.0, 0.0000001, 2.0)) {
            var request = request(new ResponseFormat.Text(), new GenerationOptions(OptionalInt.of(1), OptionalDouble.of(temperature)));
            assertEquals(request, decode(CanonicalJson.encode(request.canonicalValue())));
        }
    }

    /** 通过实际有界 parser 调用 decoder，避免直接节点构造绕过网络/持久载荷路径。 */
    private static FrozenModelRequest decode(String json) {
        return FrozenModelRequest.decode(CanonicalJson.parse(json, new CodecLimits(10000, 1000, 20)));
    }

    /** 失败仅暴露固定协议描述，不携带包含敏感原值的异常原因链。 */
    private static void assertRejected(String json) {
        var error = assertThrows(IllegalArgumentException.class, () -> decode(json));
        assertEquals("invalid frozen model request", error.getMessage());
        assertEquals(null, error.getCause());
    }

    /** Optional 缺省、负零和较小正数必须有确定编码，不能退回平台科学计数法。 */
    @Test
    void normalizesTemperatureAndPreservesExplicitAbsence() {
        String absent = CanonicalJson.encode(request(new ResponseFormat.Text(),
                new GenerationOptions(OptionalInt.empty(), OptionalDouble.empty())).canonicalValue());
        assertTrue(absent.startsWith("{\"budgetCategory\":\"BUSINESS_MODEL\",\"generation\":{\"maxOutputTokens\":null,\"temperature\":null}"));
        String zero = CanonicalJson.encode(request(new ResponseFormat.Text(),
                new GenerationOptions(OptionalInt.empty(), OptionalDouble.of(-0.0))).canonicalValue());
        assertTrue(zero.contains("\"temperature\":\"0\""));
        String tiny = CanonicalJson.encode(request(new ResponseFormat.Text(),
                new GenerationOptions(OptionalInt.empty(), OptionalDouble.of(0.0000001))).canonicalValue());
        assertTrue(tiny.contains("\"temperature\":\"0.0000001\""));
    }

    /** 消息 List 防御复制；相同 messageId 的不同内容不得进入冻结请求。 */
    @Test
    void freezesMessageOrderAndRejectsDuplicateIdentity() {
        var original = request(new ResponseFormat.Text(), options());
        var messages = new ArrayList<>(List.of(new ModelMessage("second", ModelRole.USER, "2"),
                new ModelMessage("first", ModelRole.ASSISTANT, "1")));
        var frozen = new FrozenModelRequest(original.requestedStrategy(), original.target(), messages,
                original.response(), original.generation(), original.reasoning());
        messages.clear();
        assertEquals(List.of("second", "first"), frozen.messages().stream().map(ModelMessage::messageId).toList());
        assertThrows(UnsupportedOperationException.class, () -> frozen.messages().clear());
        assertThrows(IllegalArgumentException.class, () -> new FrozenModelRequest(original.requestedStrategy(),
                original.target(), List.of(new ModelMessage("same", ModelRole.USER, "a"), new ModelMessage("same", ModelRole.USER, "b")),
                original.response(), original.generation(), original.reasoning()));
    }

    @Test
    void freezesNativeToolWireMappingAndRejectsAmbiguousIdentity() {
        var original = request(new ResponseFormat.Text(), options());
        var schema = new TypedPayload("json-schema.v1", "{\"type\":\"object\"}");
        var tools = new ArrayList<>(List.of(
                new ModelTool("sf_tool_0", "opaque-handle", "描述", schema, true, com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL, false)));
        var frozen = new FrozenModelRequest(original.requestedStrategy(), original.target(),
                original.messages(), tools, ToolChoice.AUTO, original.response(),
                original.generation(), original.reasoning());
        tools.clear();
        assertEquals("opaque-handle", frozen.tools().getFirst().toolHandle());
        assertEquals(frozen, decode(CanonicalJson.encode(frozen.canonicalValue())));
        assertThrows(IllegalArgumentException.class, () -> new FrozenModelRequest(
                original.requestedStrategy(), original.target(), original.messages(),
                List.of(new ModelTool("same", "one", "", schema, false, com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL, false),
                        new ModelTool("same", "two", "", schema, false, com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL, false)),
                ToolChoice.AUTO, original.response(), original.generation(), original.reasoning()));
        assertThrows(IllegalArgumentException.class, () -> new FrozenModelRequest(
                original.requestedStrategy(), original.target(), original.messages(),
                List.of(), ToolChoice.AUTO, original.response(), original.generation(), original.reasoning()));
    }

    /** 不支持的响应格式和 reasoning 必须在准备阶段拒绝，无需伪造 permit 才能校验。 */
    @Test
    void rejectsUnsupportedTargetCapabilitiesWithoutCreatingModelCall() {
        var original = request(new ResponseFormat.JsonObject(), options());
        var restricted = new FrozenModelTarget(original.target().binding(), "model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false, false, new ResultRef("capability")),
                Duration.ofSeconds(1), new ResultRef("resolution"));
        assertThrows(IllegalArgumentException.class, () -> new FrozenModelRequest(original.requestedStrategy(), restricted,
                original.messages(), original.response(), options(), ReasoningPolicy.DISABLED));
        var text = new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "supported");
        assertThrows(IllegalArgumentException.class, () -> new FrozenModelRequest(original.requestedStrategy(), restricted,
                original.messages(), text, options(), ReasoningPolicy.DEFAULT));
        var allowed = new FrozenModelRequest(original.requestedStrategy(), restricted, original.messages(), text,
                options(), ReasoningPolicy.DISABLED);
        assertEquals(ReasoningPolicy.DISABLED, allowed.reasoning());
    }

    /** targetHandle 只定位冻结 binding，其他请求变化不能被误解释为模型绑定 identity 变化。 */
    @Test
    void bindsTargetHandleToExactBindingAndRevision() {
        var request = request(new ResponseFormat.Text(), options());
        assertEquals("{\"bindingId\":\"binding\",\"revision\":\"9223372036854775807\",\"tenantId\":\"tenant\"}",
                CanonicalJson.encode(request.bindingValue()));
        assertEquals(request.targetHandle(), request(new ResponseFormat.JsonObject(), options()).targetHandle());
        var target = request.target();
        var differentRevision = new FrozenModelTarget(new ModelBindingRef(new TenantId("tenant"), "binding", 1),
                target.effectiveModelId(), target.capabilities(), target.timeout(), target.resolutionRef());
        var changed = new FrozenModelRequest(request.requestedStrategy(), differentRevision, request.messages(),
                request.response(), request.generation(), request.reasoning());
        assertNotEquals(request.targetHandle(), changed.targetHandle());
    }

    /** 创建不含任何 endpoint/secret 的纯 API 目标，全部参数显式给出。 */
    private static FrozenModelRequest request(ResponseFormat format, GenerationOptions generation) {
        var target = new FrozenModelTarget(new ModelBindingRef(new TenantId("tenant"), "binding", Long.MAX_VALUE),
                "model", new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT, ResponseFormatKind.JSON_OBJECT,
                        ResponseFormatKind.JSON_SCHEMA), true, true, new ResultRef("capability")),
                Duration.ofSeconds(1, 1), new ResultRef("resolution"));
        return new FrozenModelRequest(new RequestedModelStrategy("strategy", "1"), target,
                List.of(new ModelMessage("m", ModelRole.USER, "问")),
                new ResponseContract(format, EnforcementLevel.STRICT, "supported"), generation, ReasoningPolicy.DEFAULT);
    }

    /** 显式生成参数用于 golden bytes，不从环境或测试框架读取默认配置。 */
    private static GenerationOptions options() {
        return new GenerationOptions(OptionalInt.of(64), OptionalDouble.of(0.25));
    }
}
