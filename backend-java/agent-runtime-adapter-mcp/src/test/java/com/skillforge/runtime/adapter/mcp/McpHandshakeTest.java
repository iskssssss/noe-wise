package com.skillforge.runtime.adapter.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.TypedPayload;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 握手协议转换不得改变业务参数数值或替协议失败伪造完成。 */
class McpHandshakeTest {
    @Test void removingModernMetadataPreservesExactNumericArguments() throws Exception {
        String arguments = "{\"value\":0.12345678901234567890123456789}";
        byte[] request = McpProtocol.call("request", "tool", new TypedPayload("application/json", arguments));
        var converted = McpProtocol.parse(new String(McpHandshake.business(request), StandardCharsets.UTF_8));
        assertEquals(new java.math.BigDecimal("0.12345678901234567890123456789"),
                converted.path("params").path("arguments").path("value").decimalValue());
        assertFalse(converted.path("params").has("_meta"));
    }
    @Test void legacyModeDoesNotAcceptModernContinuationResult() throws Exception {
        var result = McpProtocol.parse("{\"resultType\":\"input_required\",\"content\":[]}");
        assertThrows(IllegalArgumentException.class, () -> McpHandshake.result(result));
    }
}
