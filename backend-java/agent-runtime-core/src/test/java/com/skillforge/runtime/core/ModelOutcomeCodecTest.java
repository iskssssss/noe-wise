package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.ModelResult;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.ModelPort.AssistantTurn;
import com.skillforge.runtime.api.port.ModelPort.AssistantContent;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.PortalOutcomeSnapshot;
import com.skillforge.runtime.api.port.PortalResolution;
import com.skillforge.runtime.api.port.RetryMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 独立预期字节与负向矩阵验证模型结果 codec；registry 替身只证明校验边界被调用。 */
class ModelOutcomeCodecTest {
    @Test void preservesPrivateNativeItemsAndUsageWithoutLeakingThroughToString() {
        var responses = new ModelPort.NativeProtocolState(ModelPort.NativeProtocol.RESPONSES, "responses-v1", "model", List.of(
                new TypedPayload("responses.output-item.v1", "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"PRIVATE-CIPHERTEXT\"}")));
        var messages = new ModelPort.NativeProtocolState(ModelPort.NativeProtocol.MESSAGES, "deepseek-messages-v1", "model", List.of(
                new TypedPayload("deepseek.messages.content-block.v1", "{\"type\":\"thinking\",\"thinking\":\"PRIVATE-THOUGHT\",\"signature\":\"PRIVATE-SIGNATURE\"}")));
        for (var state : List.of(responses, messages)) {
            var result = new ModelResult.Completed(OUTPUT, "model", new ModelUsage(OptionalLong.of(19), OptionalLong.of(7),
                    OptionalLong.of(3), OptionalLong.empty(), OptionalLong.of(5)), "stop", new ResultRef("native-result"),
                    Optional.empty(), Optional.empty(), Optional.of(state));
            for (var kind : List.of(PortalCallKind.MODEL_GENERATE, PortalCallKind.MODEL_STREAM)) {
                var restored = assertInstanceOf(ModelResult.Completed.class, codec().decode(codec().encode(kind, result), kind));
                assertEquals(result, restored); assertEquals(state.items(), restored.protocolState().orElseThrow().items());
                assertEquals(result.usage(), restored.usage()); assertTrue(restored.reasoningContent().isEmpty());
                assertFalse(restored.toString().contains("PRIVATE-"));
                assertFalse(state.toString().contains("PRIVATE-"));
            }
        }
    }
    private static final CodecLimits LIMITS = new CodecLimits(100000, 10000, 32);
    private static final TypedPayload OUTPUT = new TypedPayload("output.v1", "{\"answer\":\"你好🌍\"}");
    private static final String COMPLETED = "{\"assistantTurn\":null,\"effectiveModelId\":\"model\",\"finishReasonCode\":\"stop\",\"outcomeType\":\"Completed\",\"output\":{\"canonicalJson\":\"{\\\"answer\\\":\\\"你好🌍\\\"}\",\"schemaId\":\"output.v1\"},\"protocolState\":null,\"reasoningContent\":null,\"resultRef\":\"business-result\",\"usage\":{\"cacheWriteInputTokens\":null,\"cachedInputTokens\":\"0\",\"inputTokens\":\"9007199254740993\",\"outputTokens\":null,\"reasoningOutputTokens\":\"9007199254740994\"}}";

    /** 手写预期字段顺序、转义和大整数，避免同一 encoder 自证互操作正确。 */
    @Test
    void exactGoldenBytesForAllSubtypesAndBothCallKinds() {
        var codec = codec();
        var failure = new PortFailure("declined", RetryMode.NONE, new ResultRef("failure-audit"));
        for (var kind : List.of(PortalCallKind.MODEL_GENERATE, PortalCallKind.MODEL_STREAM)) {
            var success = completed();
            assertEquals(COMPLETED, codec.encode(kind, success).outcome().canonicalJson());
            assertEquals(success, codec.decode(snapshot(kind, "Completed", COMPLETED), kind));
            for (ModelResult result : List.of(new ModelResult.Failed(failure), new ModelResult.OutcomeUnknown(failure))) {
                String type = result instanceof ModelResult.Failed ? "Failed" : "OutcomeUnknown";
                String expected = type.equals("Failed")
                        ? "{\"failure\":{\"reasonCode\":\"declined\",\"resultRef\":\"failure-audit\",\"retryMode\":\"NONE\"},\"outcomeType\":\"Failed\",\"retryAt\":null}"
                        : "{\"failure\":{\"reasonCode\":\"declined\",\"resultRef\":\"failure-audit\",\"retryMode\":\"NONE\"},\"outcomeType\":\"OutcomeUnknown\"}";
                assertEquals(expected, codec.encode(kind, result).outcome().canonicalJson());
                assertEquals(result, codec.decode(snapshot(kind, type, expected), kind));
            }
        }
    }

    /** 已确认限流失败的 retryAt 在 generate 与 stream 两种调用中都按原值往返。 */
    @Test
    void roundTripsConfirmedRateLimitRetryAt() {
        var retryAt = Instant.parse("2026-09-05T00:00:30Z");
        var failure = new PortFailure("model_rate_limited", RetryMode.NONE,
                new ResultRef("rate-limit-audit"));
        var result = new ModelResult.Failed(failure, Optional.of(retryAt));
        var expected = "{\"failure\":{\"reasonCode\":\"model_rate_limited\",\"resultRef\":\"rate-limit-audit\",\"retryMode\":\"NONE\"},\"outcomeType\":\"Failed\",\"retryAt\":\"2026-09-05T00:00:30Z\"}";
        for (var kind : List.of(PortalCallKind.MODEL_GENERATE, PortalCallKind.MODEL_STREAM)) {
            var snapshot = codec().encode(kind, result);
            assertEquals(expected, snapshot.outcome().canonicalJson());
            assertEquals(result, codec().decode(snapshot, kind));
        }
    }

    /** 五个 usage 字段独立保留未知、零与超过 JavaScript 安全整数的值。 */
    @Test
    void preservesEveryUsagePresenceCombination() {
        var usage = new ModelUsage(OptionalLong.empty(), OptionalLong.of(0),
                OptionalLong.of(9007199254740993L), OptionalLong.empty(),
                OptionalLong.of(Long.MAX_VALUE));
        var result = new ModelResult.Completed(OUTPUT, "model", usage, "stop", new ResultRef("result"));
        assertEquals(result, codec().decode(codec().encode(PortalCallKind.MODEL_GENERATE, result),
                PortalCallKind.MODEL_GENERATE));
    }

    @Test
    void preservesReasoningOutsideDecisionOutput() {
        var original = new ModelResult.Completed(OUTPUT, "model",
                new ModelUsage(OptionalLong.of(3), OptionalLong.of(2)), "stop",
                new ResultRef("result"), java.util.Optional.of("先分析\n再回答"));
        var restored = assertInstanceOf(ModelResult.Completed.class,
                codec().decode(codec().encode(PortalCallKind.MODEL_GENERATE, original),
                        PortalCallKind.MODEL_GENERATE));
        assertEquals(OUTPUT, restored.output());
        assertEquals("先分析\n再回答", restored.reasoningContent().orElseThrow());
    }

    /** 内容顺序、原调用身份与非法参数原文完整往返，kind 不作为冗余持久字段。 */
    @Test
    void preservesNativeAssistantTurnWithStrictCurrentFields() {
        var turn = new AssistantTurn(List.of(new AssistantContent.Text("正文😀".repeat(240)),
                new AssistantContent.ToolCall("call-1", "read_file", "{\"path\":"),
                new AssistantContent.Text("调用后的正文"),
                new AssistantContent.ToolCall("call-2", "read_file", "{ \"path\": \"readme.md\" }")));
        var output = new TypedPayload(ModelPort.TEXT_OUTPUT_SCHEMA,
                CanonicalJson.encode(CanonicalJson.string(turn.content())));
        var original = new ModelResult.Completed(output, "model", completed().usage(),
                "length", new ResultRef("business-result"), Optional.empty(), Optional.of(turn));
        var codec = new ModelOutcomeCodec(LIMITS, (payload, limits) -> assertEquals(output, payload));
        for (var callKind : List.of(PortalCallKind.MODEL_GENERATE, PortalCallKind.MODEL_STREAM)) {
            var stored = codec.encode(callKind, original);
            assertEquals(original, codec.decode(stored, callKind));
            for (String malformed : List.of(stored.outcome().canonicalJson().replace("\"type\":\"text\"", "\"type\":\"UNKNOWN\""),
                    stored.outcome().canonicalJson().replace("\"assistantTurn\":{", "\"assistantTurn\":{\"extra\":null,"))) {
                var rejected = assertThrows(IllegalArgumentException.class,
                        () -> codec.decode(snapshot(callKind, "Completed", malformed), callKind));
                assertEquals("invalid model outcome", rejected.getMessage());
                assertNull(rejected.getCause());
            }
            var malformed = stored.outcome().canonicalJson().replace("\"schemaId\":\"" + ModelPort.TEXT_OUTPUT_SCHEMA + "\"",
                    "\"schemaId\":\"output.v1\"");
            assertThrows(IllegalArgumentException.class, () -> codec.decode(snapshot(callKind, "Completed", malformed), callKind));
        }
        var mismatched = new ModelResult.Completed(OUTPUT, "model", completed().usage(), "stop",
                new ResultRef("business-result"), Optional.empty(), Optional.of(turn));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(PortalCallKind.MODEL_GENERATE, mismatched));
        var bounded = new ModelOutcomeCodec(new CodecLimits(1000, 10000, 32), (payload, limits) -> fail("budget precedes registry"));
        assertThrows(IllegalArgumentException.class, () -> bounded.encode(PortalCallKind.MODEL_GENERATE, original));
    }

    /** 即使外层封套合法，body 的未知/缺失字段、数字类型、非规范转义与 discriminator 仍必须拒绝。 */
    @Test
    void rejectsMalformedAndNonCanonicalBodiesWithoutLeakingInput() {
        for (String body : List.of(COMPLETED.replace("\"model\"", "\"SECRET-MODEL\"").replace("\"stop\"", "null"),
                COMPLETED.replace("\"inputTokens\":\"9007199254740993\"", "\"inputTokens\":9007199254740993"),
                COMPLETED.replace("9007199254740993", "09007199254740993"), COMPLETED.replace("9007199254740993", "-1"),
                COMPLETED.replace("9007199254740993", "9223372036854775808"), COMPLETED.replace("Completed", "Failed"),
                COMPLETED.replace("\"cachedInputTokens\":\"0\",", ""),
                COMPLETED.replace("\"resultRef\":\"business-result\",", ""), COMPLETED.replace("{\"assistantTurn\"", "{\"extra\":null,\"assistantTurn\""),
                COMPLETED.replace("\"assistantTurn\":null,", ""),
                COMPLETED.replace("\"protocolState\":null,", ""),
                " " + COMPLETED, COMPLETED.replace("你好", "\\u4f60\\u597d"))) {
            assertNotEquals(COMPLETED, body, "负向夹具必须实际改变规范正文");
            var error = assertThrows(IllegalArgumentException.class, () -> codec().decode(snapshot(PortalCallKind.MODEL_GENERATE, "Completed", body), PortalCallKind.MODEL_GENERATE));
            assertEquals("invalid model outcome", error.getMessage());
            assertNull(error.getCause());
        }
    }

    /** stream 与 generate 不能互换，存储态 Failed 不接受任何自动重试保证。 */
    @Test
    void rejectsWrongKindRetryModeAndVersion() {
        assertThrows(IllegalArgumentException.class, () -> codec().decode(snapshot(PortalCallKind.MODEL_STREAM, "Completed", COMPLETED), PortalCallKind.MODEL_GENERATE));
        for (RetryMode mode : RetryMode.values()) {
            if (mode == RetryMode.NONE) { continue; }
            assertThrows(IllegalArgumentException.class, () -> codec().encode(PortalCallKind.MODEL_GENERATE,
                    new ModelResult.Failed(new PortFailure("failure", mode, new ResultRef("audit")))));
        }
        assertThrows(IllegalArgumentException.class, () -> new PortalOutcomeSnapshot(PortalCallKind.MODEL_GENERATE, PortalResolution.KNOWN_RESULT,
                "Completed", new TypedPayload("skillforge.runtime.portal.model-outcome.v999", COMPLETED)));
        assertThrows(IllegalArgumentException.class, () -> new PortalOutcomeSnapshot(PortalCallKind.MODEL_GENERATE, PortalResolution.KNOWN_RESULT,
                "Completed", new TypedPayload("skillforge.runtime.portal.model-outcome.v4", COMPLETED)));
    }

    /** OutcomeUnknown 不得借 retryAt 扩展未知结果的重试语义。 */
    @Test
    void rejectsRetryAtOnOutcomeUnknown() {
        var body = "{\"failure\":{\"reasonCode\":\"declined\",\"resultRef\":\"failure-audit\",\"retryMode\":\"NONE\"},\"outcomeType\":\"OutcomeUnknown\",\"retryAt\":null}";
        var error = assertThrows(IllegalArgumentException.class,
                () -> codec().decode(snapshot(PortalCallKind.MODEL_GENERATE, "OutcomeUnknown", body),
                        PortalCallKind.MODEL_GENERATE));
        assertEquals("invalid model outcome", error.getMessage());
    }

    /** 首次编码和恢复都触发 registry；技术故障不得伪装为本地 schema 错误或吞掉 cause。 */
    @Test
    void requiresOutputRegistryOnEncodeAndDecode() {
        var calls = new AtomicInteger();
        var codec = new ModelOutcomeCodec(LIMITS, (payload, limits) -> { assertEquals(OUTPUT, payload); assertEquals(LIMITS, limits); calls.incrementAndGet(); });
        var snapshot = codec.encode(PortalCallKind.MODEL_GENERATE, completed());
        codec.decode(snapshot, PortalCallKind.MODEL_GENERATE);
        assertEquals(2, calls.get());
        var unavailable = new IllegalStateException("registry unavailable", new IllegalStateException("technical cause"));
        var broken = new ModelOutcomeCodec(LIMITS, (payload, limits) -> { throw unavailable; });
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> broken.decode(snapshot, PortalCallKind.MODEL_GENERATE)));
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> broken.encode(PortalCallKind.MODEL_GENERATE, completed())));
        var denied = new ModelOutcomeCodec(LIMITS, (payload, limits) -> { throw new IllegalArgumentException("schema rejected"); });
        assertThrows(IllegalArgumentException.class, () -> denied.encode(PortalCallKind.MODEL_GENERATE, completed()));
    }

    /** encode/decode 共用长度、节点与深度上限，不允许生成路径绕过解析资源限制。 */
    @Test
    void enforcesResourceLimitsInBothDirections() {
        for (var limits : List.of(new CodecLimits(10, 1000, 32), new CodecLimits(10000, 1, 32), new CodecLimits(10000, 1000, 1))) {
            var bounded = new ModelOutcomeCodec(limits, (payload, ignored) -> fail("registry must follow structure checks"));
            assertThrows(IllegalArgumentException.class, () -> bounded.encode(PortalCallKind.MODEL_GENERATE, completed()));
            assertThrows(IllegalArgumentException.class, () -> bounded.decode(snapshot(PortalCallKind.MODEL_GENERATE, "Completed", COMPLETED), PortalCallKind.MODEL_GENERATE));
        }
    }

    /** 编码前预算必须与已有 parser 精确同义，不把安全预检变成额外业务限制。 */
    @Test
    void exactPreflightBoundaryMatchesCanonicalParser() {
        // protocolState 的字段名和显式 null 各占一个节点，当前精确边界为 33；值的最大深度仍为 3。
        var exactLimits = new CodecLimits(COMPLETED.length(), 33, 3);
        assertEquals(COMPLETED, CanonicalJson.encode(CanonicalJson.parse(COMPLETED, exactLimits)));
        var exact = new ModelOutcomeCodec(exactLimits, (payload, limits) -> assertEquals(OUTPUT, payload));
        for (var kind : List.of(PortalCallKind.MODEL_GENERATE, PortalCallKind.MODEL_STREAM)) {
            assertEquals(COMPLETED, exact.encode(kind, completed()).outcome().canonicalJson());
            assertEquals(completed(), exact.decode(snapshot(kind, "Completed", COMPLETED), kind));
        }
        for (var limits : List.of(new CodecLimits(COMPLETED.length() - 1, 33, 3), new CodecLimits(COMPLETED.length(), 32, 3), new CodecLimits(COMPLETED.length(), 33, 2))) {
            assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse(COMPLETED, limits));
            var bounded = new ModelOutcomeCodec(limits, (payload, ignored) -> fail("preflight precedes registry"));
            for (var kind : List.of(PortalCallKind.MODEL_GENERATE, PortalCallKind.MODEL_STREAM)) {
                assertThrows(IllegalArgumentException.class, () -> bounded.encode(kind, completed()));
                assertThrows(IllegalArgumentException.class, () -> bounded.decode(snapshot(kind, "Completed", COMPLETED), kind));
            }
        }
    }

    /** 独立构造期望结果，未使用生产 codec 生成预期 canonical 文本。 */
    private static ModelResult.Completed completed() { return new ModelResult.Completed(OUTPUT, "model", new ModelUsage(OptionalLong.of(9007199254740993L), OptionalLong.empty(), OptionalLong.of(0), OptionalLong.empty(), OptionalLong.of(9007199254740994L)), "stop", new ResultRef("business-result")); }

    /** 只有测试使用固定 registry 断言，生产没有 allow-all 默认实现。 */
    private static ModelOutcomeCodec codec() { return new ModelOutcomeCodec(LIMITS, (payload, limits) -> assertEquals(OUTPUT, payload)); }

    /** 测试允许故意构造错误 body，API 封套本身仍遵守真实注册 subtype。 */
    private static PortalOutcomeSnapshot snapshot(PortalCallKind kind, String type, String body) {
        return new PortalOutcomeSnapshot(kind, type.equals("Completed") ? PortalResolution.KNOWN_RESULT : type.equals("Failed") ? PortalResolution.KNOWN_FAILURE : PortalResolution.OUTCOME_UNKNOWN,
                type, new TypedPayload(ModelOutcomeCodec.SCHEMA, body));
    }
}
