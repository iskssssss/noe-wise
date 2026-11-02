package com.skillforge.runtime.adapter.model;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import java.io.IOException;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** 供应商 number 写法在入站边界一次性归一化，下游按字节比较的 wire 才不会拒绝合法响应。 */
class OpenAiModelProtocolNumberTest {

    @Test void normalizesProviderNumberWritingsIntoPlainDecimal() throws Exception {
        var output = OpenAiModelProtocol.output(new ResponseFormat.JsonObject(),
                "{\"b\":1.50,\"a\":1E+2,\"d\":-0,\"c\":1e1,\"e\":1e-7,\"f\":9007199254740993123456789}");
        assertEquals(OpenAiModelProtocol.JSON_SCHEMA, output.schemaId());
        assertEquals("{\"a\":100,\"b\":1.5,\"c\":10,\"d\":0,\"e\":0.0000001,\"f\":9007199254740993123456789}",
                output.canonicalJson());
    }

    /** 精度不得先落到 double 再重建；巨量指数只拒绝，不展开成巨串。 */
    @Test void keepsExactDecimalPrecisionAndRejectsUnboundedExpansion() throws Exception {
        assertEquals("{\"v\":0.1234567890123456789}", OpenAiModelProtocol.output(
                new ResponseFormat.JsonObject(), "{\"v\":0.1234567890123456789}").canonicalJson());
        for (String content : List.of("{\"v\":1e1000000}", "{\"v\":1e-1000000}")) {
            assertThrows(RuntimeException.class,
                    () -> OpenAiModelProtocol.output(new ResponseFormat.JsonObject(), content), content);
        }
    }

    /** 非对象响应仍按协议错误拒绝，归一化不放宽既有形状约束。 */
    @Test void stillRejectsNonobjectJsonResponses() {
        assertThrows(IOException.class,
                () -> OpenAiModelProtocol.output(new ResponseFormat.JsonObject(), "[1]"));
    }

    @Test void mergedUsageRevalidatesSubsetsAgainstTotalsFromEarlierFrames() {
        var earlier = new com.skillforge.runtime.api.port.ModelPort.ModelUsage(
                OptionalLong.of(4), OptionalLong.of(2), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty());
        var later = new com.skillforge.runtime.api.port.ModelPort.ModelUsage(
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.of(5),
                OptionalLong.of(5), OptionalLong.of(3));

        var merged = OpenAiModelProtocol.mergeUsage(earlier, later);

        assertEquals(4, merged.inputTokens().orElseThrow());
        assertEquals(2, merged.outputTokens().orElseThrow());
        assertTrue(merged.cachedInputTokens().isEmpty());
        assertTrue(merged.cacheWriteInputTokens().isEmpty());
        assertTrue(merged.reasoningOutputTokens().isEmpty());
    }
}
