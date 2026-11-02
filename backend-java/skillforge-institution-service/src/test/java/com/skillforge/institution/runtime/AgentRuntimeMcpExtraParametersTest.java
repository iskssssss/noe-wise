package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.TypedPayload;
import org.junit.jupiter.api.Test;

/** 新frame只投影已保存原输入，不依赖旧source facts或模型参数。 */
class AgentRuntimeMcpExtraParametersTest {
    /** 原聊天额外参数对象完整保留，arguments中的同名字段不参与可信上下文。 */
    @Test void projectsOnlyOriginalInboundExtraParametersObject() throws Exception {
        var input = new TypedPayload("input.v1", "{\"arguments\":{\"extraParameters\":{\"device_id\":\"forged\"}},\"extraParameters\":{\"device_id\":\"original\",\"nested\":{\"enabled\":true}}}");
        var extra = AgentRuntimeMcpExtraParameters.fromInbound(input).orElseThrow();
        assertEquals("application/json", extra.schemaId());
        var parsed = new ObjectMapper().readTree(extra.canonicalJson());
        assertEquals("original", parsed.path("device_id").asText());
        assertTrue(parsed.path("nested").path("enabled").asBoolean());
        assertEquals(2, parsed.size());
    }

    /** 缺失、不属于原输入schema或非对象字段均不猜测成额外元数据。 */
    @Test void absentOrNonObjectExtraParametersRemainAbsent() {
        for (var json : new String[]{"{}", "{\"extraParameters\":null}", "{\"extraParameters\":[]}", "{\"extraParameters\":\"forged\"}"})
            assertTrue(AgentRuntimeMcpExtraParameters.fromInbound(new TypedPayload("input.v1", json)).isEmpty());
        assertTrue(AgentRuntimeMcpExtraParameters.fromInbound(new TypedPayload("application/json", "{\"extraParameters\":{}}" )).isEmpty());
    }
}
