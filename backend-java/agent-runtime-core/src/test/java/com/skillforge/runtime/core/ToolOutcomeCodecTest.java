package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.PortalOutcomeSnapshot;
import com.skillforge.runtime.api.port.PortalResolution;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputRequest;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 独立规范字节与拒绝矩阵只证明结构边界，不冒充真实 Tool 提交或安全投影。 */
class ToolOutcomeCodecTest {
    private static final CodecLimits LIMITS = new CodecLimits(100000, 10000, 32);
    private static final TypedPayload OUTPUT = new TypedPayload("tool.v1", "{\"answer\":\"你好🌍\"}");
    private static final String SUCCESS = "{\"observation\":{\"canonicalJson\":\"{\\\"answer\\\":\\\"你好🌍\\\"}\",\"schemaId\":\"tool.v1\"},\"outcomeType\":\"Succeeded\",\"resultRef\":\"business\",\"usageRefs\":[\"z\",\"a\"]}";

    /** 四个分支均从手写字节解码，再独立比较原 API 值和编码结果。 */
    @Test
    void exactGoldenForAllFourSubtypes() {
        assertGolden(success(), "Succeeded", SUCCESS);
        assertGolden(inputRequired(), "InputRequired",
                "{\"inputRequests\":{\"request-1\":{\"method\":\"elicitation/create\",\"params\":{\"canonicalJson\":\"{\\\"message\\\":\\\"继续\\\"}\",\"schemaId\":\"elicitation.v1\"}}},\"outcomeType\":\"InputRequired\",\"requestState\":\"opaque-state\"}");
        var failure = failure(RetryMode.NONE);
        assertGolden(new ToolOutcome.Failed(failure), "Failed", failureBody("Failed", "NONE"));
        assertGolden(new ToolOutcome.OutcomeUnknown(failure), "OutcomeUnknown", failureBody("OutcomeUnknown", "NONE"));
    }

    /** Failed 保留全部 API 模式，unknown 只接受 Tool 允许的两种模式，不能继承 Model 的 NONE 限制。 */
    @Test
    void preservesRetryMatrixAndUsageOrder() {
        for (var mode : RetryMode.values()) {
            assertGolden(new ToolOutcome.Failed(failure(mode)), "Failed", failureBody("Failed", mode.name()));
            if (mode == RetryMode.NONE || mode == RetryMode.IDEMPOTENT_BY_STABLE_KEY) {
                assertGolden(new ToolOutcome.OutcomeUnknown(failure(mode)), "OutcomeUnknown", failureBody("OutcomeUnknown", mode.name()));
            } else {
                assertRejected(snapshot("OutcomeUnknown", failureBody("OutcomeUnknown", mode.name())));
            }
        }
        var empty = new ToolOutcome.Succeeded(OUTPUT, new ResultRef("business"), List.of());
        assertEquals(empty, codec().decode(codec().encode(PortalCallKind.TOOL_INVOKE, empty), PortalCallKind.TOOL_INVOKE));
        var reversed = new ToolOutcome.Succeeded(OUTPUT, new ResultRef("business"), List.of(new ResultRef("a"), new ResultRef("z")));
        assertNotEquals(codec().encode(PortalCallKind.TOOL_INVOKE, success()), codec().encode(PortalCallKind.TOOL_INVOKE, reversed));
    }

    /** 结构错误在业务 registry 之前拒绝，消息和 cause 不泄露私有输入。 */
    @Test
    void rejectsMalformedUnknownMissingAndNonCanonicalFields() {
        for (String body : List.of(
                " " + SUCCESS, SUCCESS + "x", SUCCESS.replace("你好", "\\u4f60\\u597d"),
                SUCCESS.replace("\"z\",\"a\"", "\"z\",\"z\""),
                SUCCESS.replace("\"z\",\"a\"", "null"), SUCCESS.replace("\"business\"", "{}"),
                SUCCESS.replace("\"business\"", "null"), SUCCESS.replace("\"resultRef\":\"business\",", ""),
                SUCCESS.replace("{\"observation\"", "{\"extra\":\"SECRET\",\"observation\""),
                SUCCESS.replace("Succeeded", "InputRequired"), SUCCESS.replace("Succeeded", "SECRET"),
                SUCCESS.replace("\"schemaId\":\"tool.v1\"", "\"schemaId\":\"tool.v1\",\"schemaId\":\"tool.v1\""))) {
            assertRejected(snapshot("Succeeded", body));
        }
        assertRejected(snapshot("Failed", failureBody("Failed", "SECRET")));
        assertRejected(snapshot("Failed", failureBody("Failed", "NONE").replace("\"resultRef\":\"audit\",", "")));
        assertRejected(snapshot("InputRequired", "{\"outcomeType\":\"InputRequired\"}"));
        assertRejected(snapshot("InputRequired", "{\"outcomeType\":\"InputRequired\",\"requestState\":null}"));
    }

    /** kind 由调用方显式提供，合法的其他 Portal 封套也不能借 Tool codec 读取。 */
    @Test
    void rejectsCrossCallAndSchemaVersion() {
        for (var kind : PortalCallKind.values()) {
            if (kind == PortalCallKind.TOOL_INVOKE) { continue; }
            assertThrows(IllegalArgumentException.class, () -> codec().encode(kind, success()));
            assertThrows(IllegalArgumentException.class, () -> codec().decode(snapshot("Succeeded", SUCCESS), kind));
        }
        assertRejected(new PortalOutcomeSnapshot(PortalCallKind.MODEL_GENERATE, PortalResolution.KNOWN_FAILURE, "Failed",
                new TypedPayload(ModelOutcomeCodec.SCHEMA, failureBody("Failed", "NONE"))));
        assertThrows(IllegalArgumentException.class, () -> new PortalOutcomeSnapshot(PortalCallKind.TOOL_INVOKE,
                PortalResolution.KNOWN_RESULT, "Succeeded", new TypedPayload("skillforge.runtime.portal.tool-outcome.v1", SUCCESS)));
    }

    /** registry 每次入口只调用一次；所有非成功分支完全不消费 observation 校验。 */
    @Test
    void registryInvocationAndTechnicalErrorsRemainExact() {
        var calls = new AtomicInteger();
        var codec = new ToolOutcomeCodec(LIMITS, (payload, limits) -> { assertEquals(OUTPUT, payload); assertEquals(LIMITS, limits); calls.incrementAndGet(); });
        var snapshot = codec.encode(PortalCallKind.TOOL_INVOKE, success());
        codec.decode(snapshot, PortalCallKind.TOOL_INVOKE);
        assertEquals(2, calls.get());
        codec.encode(PortalCallKind.TOOL_INVOKE, inputRequired());
        codec.encode(PortalCallKind.TOOL_INVOKE, new ToolOutcome.Failed(failure(RetryMode.NONE)));
        codec.encode(PortalCallKind.TOOL_INVOKE, new ToolOutcome.OutcomeUnknown(failure(RetryMode.NONE)));
        assertEquals(2, calls.get());
        for (var exception : List.of(new IllegalStateException("unavailable", new IllegalStateException("cause")), new IllegalArgumentException("external schema rejection"))) {
            var broken = new ToolOutcomeCodec(LIMITS, (payload, limits) -> { throw exception; });
            assertSame(exception, assertThrows(RuntimeException.class, () -> broken.encode(PortalCallKind.TOOL_INVOKE, success())));
            assertSame(exception, assertThrows(RuntimeException.class, () -> broken.decode(snapshot, PortalCallKind.TOOL_INVOKE)));
        }
    }

    /** 精确长度/节点/深度边界同时通过两条入口；减一即失败，防止预检过度约束。 */
    @Test
    void exactResourceBoundaryAndLargeCollection() {
        var exact = new CodecLimits(SUCCESS.length(), 15, 3);
        var codec = new ToolOutcomeCodec(exact, (payload, limits) -> assertEquals(OUTPUT, payload));
        assertEquals(success(), codec.decode(codec.encode(PortalCallKind.TOOL_INVOKE, success()), PortalCallKind.TOOL_INVOKE));
        for (var limits : List.of(new CodecLimits(SUCCESS.length() - 1, 15, 3), new CodecLimits(SUCCESS.length(), 14, 3), new CodecLimits(SUCCESS.length(), 15, 2))) {
            var bounded = new ToolOutcomeCodec(limits, (payload, ignored) -> fail("must reject before registry"));
            assertThrows(IllegalArgumentException.class, () -> bounded.encode(PortalCallKind.TOOL_INVOKE, success()));
            assertThrows(IllegalArgumentException.class, () -> bounded.decode(snapshot("Succeeded", SUCCESS), PortalCallKind.TOOL_INVOKE));
        }
        var many = new ToolOutcome.Succeeded(OUTPUT, new ResultRef("business"), IntStream.range(0, 20000).mapToObj(i -> new ResultRef("r" + i)).toList());
        assertThrows(IllegalArgumentException.class, () -> codec.encode(PortalCallKind.TOOL_INVOKE, many));
    }

    /** 原字符数不等于转义后长度，控制字符和非 BMP 边界不能按 code point 少算。 */
    @Test
    void countsEscapedStringsBeforeAllocatingJson() {
        String content = "\u0000\b\f\n\r\t\"\\🌍";
        var result = new ToolOutcome.Succeeded(new TypedPayload("opaque.v1", content), new ResultRef("business"), List.of());
        String golden = "{\"observation\":{\"canonicalJson\":\"\\u0000\\b\\f\\n\\r\\t\\\"\\\\🌍\",\"schemaId\":\"opaque.v1\"},\"outcomeType\":\"Succeeded\",\"resultRef\":\"business\",\"usageRefs\":[]}";
        var exact = new ToolOutcomeCodec(new CodecLimits(golden.length(), 13, 3), (payload, limits) -> assertEquals(result.observation(), payload));
        assertEquals(golden, exact.encode(PortalCallKind.TOOL_INVOKE, result).outcome().canonicalJson());
        var bounded = new ToolOutcomeCodec(new CodecLimits(golden.length() - 1, 13, 3), (payload, limits) -> fail());
        assertThrows(IllegalArgumentException.class, () -> bounded.encode(PortalCallKind.TOOL_INVOKE, result));
    }

    /** 完整私有事实精确往返；空事实、畸形和容量失败不能互相混淆。 */
    @Test void preservesPrivateFailureFactAndDistinguishesInvalidFromCapacity() {
        var detail = new TypedPayload("failure.v1", "{\"message\":\"private-provider-secret\"}");
        var failed = new ToolOutcome.Failed(failure(RetryMode.NONE), Optional.of(detail));
        var snapshot = codec().encode(PortalCallKind.TOOL_INVOKE, failed);
        assertEquals(failed, codec().decode(snapshot, PortalCallKind.TOOL_INVOKE));
        assertFalse(failed.toString().contains("private-provider-secret"));
        assertNotEquals(snapshot, codec().encode(PortalCallKind.TOOL_INVOKE, new ToolOutcome.Failed(failure(RetryMode.NONE))));
        var bounded = new ToolOutcomeCodec(new CodecLimits(150, 100, 10), (payload, limits) -> fail());
        assertThrows(com.skillforge.runtime.codec.spi.ToolOutcomeCapacityExceeded.class,
                () -> bounded.encode(PortalCallKind.TOOL_INVOKE, failed));
        var nodeBounded = new ToolOutcomeCodec(new CodecLimits(1000, 20, 32), (payload, limits) -> fail());
        var manyNodes = new ToolOutcome.Failed(failure(RetryMode.NONE), Optional.of(new TypedPayload("failure.v1", "[" + "0,".repeat(49) + "0]")));
        assertThrows(com.skillforge.runtime.codec.spi.ToolOutcomeCapacityExceeded.class,
                () -> nodeBounded.encode(PortalCallKind.TOOL_INVOKE, manyNodes));
        var malformed = new ToolOutcome.Failed(failure(RetryMode.NONE), Optional.of(new TypedPayload("failure.v1", "{\"message\":\"" + "x".repeat(200) + "\\q\"}")));
        var invalid = assertThrows(IllegalArgumentException.class, () -> bounded.encode(PortalCallKind.TOOL_INVOKE, malformed));
        assertEquals("invalid tool outcome", invalid.getMessage());
        assertNull(invalid.getCause());
        assertRejected(snapshot("Failed", failureBody("Failed", "NONE").replace("\"comparisonFact\":null,", "")));
    }

    /** 独立字节和真实结果双向核对。 */
    private static void assertGolden(ToolOutcome result, String type, String golden) {
        assertEquals(golden, codec().encode(PortalCallKind.TOOL_INVOKE, result).outcome().canonicalJson());
        assertEquals(result, codec().decode(snapshot(type, golden), PortalCallKind.TOOL_INVOKE));
    }

    /** 构造完整合法封套，使测试能专门破坏内部 body。 */
    private static PortalOutcomeSnapshot snapshot(String type, String body) {
        return new PortalOutcomeSnapshot(PortalCallKind.TOOL_INVOKE,
                type.equals("Failed") ? PortalResolution.KNOWN_FAILURE
                        : type.equals("OutcomeUnknown") ? PortalResolution.OUTCOME_UNKNOWN
                        : type.equals("InputRequired") ? PortalResolution.INPUT_REQUIRED
                        : PortalResolution.KNOWN_RESULT,
                type, new TypedPayload(ToolOutcomeCodec.SCHEMA, body));
    }

    /** 错误正文不允许触发 registry，也不通过异常 cause 输出。 */
    private static void assertRejected(PortalOutcomeSnapshot snapshot) {
        var codec = new ToolOutcomeCodec(LIMITS, (payload, limits) -> fail("must not validate malformed body"));
        var error = assertThrows(IllegalArgumentException.class, () -> codec.decode(snapshot, PortalCallKind.TOOL_INVOKE));
        assertEquals("invalid tool outcome", error.getMessage());
        assertNull(error.getCause());
    }

    /** 固定替身只用于结构测试，不作为生产 allow-all 默认值。 */
    private static ToolOutcomeCodec codec() { return new ToolOutcomeCodec(LIMITS, (payload, limits) -> assertEquals(OUTPUT, payload)); }

    /** 成功用量故意非排序，锁定 List 而非 Set 的语义。 */
    private static ToolOutcome.Succeeded success() { return new ToolOutcome.Succeeded(OUTPUT, new ResultRef("business"), List.of(new ResultRef("z"), new ResultRef("a"))); }

    /** 等待输入同时携带协议请求与 opaque continuation 状态，锁定完整 round-trip。 */
    private static ToolOutcome.InputRequired inputRequired() {
        return new ToolOutcome.InputRequired(Map.of("request-1",
                new ToolInputRequest(ToolInputMethod.ELICITATION_CREATE,
                        new TypedPayload("elicitation.v1", "{\"message\":\"继续\"}"))),
                Optional.of("opaque-state"));
    }

    /** 私有审计引用不得被替换为 attempt fact。 */
    private static PortFailure failure(RetryMode mode) { return new PortFailure("declined", mode, new ResultRef("audit")); }

    /** 手写失败字段顺序，不调用生产编码器构造预期。 */
    private static String failureBody(String type, String mode) { return "{" + (type.equals("Failed") ? "\"comparisonFact\":null," : "") + "\"failure\":{\"reasonCode\":\"declined\",\"resultRef\":\"audit\",\"retryMode\":\"" + mode + "\"},\"outcomeType\":\"" + type + "\"}"; }
}
