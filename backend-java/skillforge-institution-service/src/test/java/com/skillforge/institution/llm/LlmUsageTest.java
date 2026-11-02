package com.skillforge.institution.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.model.invocation.LlmUsageMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * LLM token 用量解析单元测试。
 *
 * <p>覆盖 OpenAI 兼容 usage JSON 的完整解析、部分字段的未知语义、
 * total 补全与非法结构回退未知用量。
 */
class LlmUsageTest {

    /**
     * 完整 usage 字段应原样解析。
     */
    @Test
    void fromJsonParsesFullUsage() {
        LlmUsage usage = LlmUsageMapper.fromJson(Map.of(
                "prompt_tokens", 120,
                "completion_tokens", 80,
                "total_tokens", 200));
        assertEquals(120, usage.promptTokens());
        assertEquals(80, usage.completionTokens());
        assertEquals(200, usage.totalTokens());
        assertEquals(UsageSource.PROVIDER_REPORTED, usage.usageSource());
    }

    /**
     * 部分响应不以零填补未知输出或总量。
     */
    @Test
    void fromJsonKeepsMissingFieldsUnknown() {
        LlmUsage usage = LlmUsageMapper.fromJson(Map.of("prompt_tokens", 30));
        assertEquals(30, usage.promptTokens());
        assertNull(usage.completionTokens());
        assertNull(usage.totalTokens());
    }

    /**
     * 非 Map / null 输入返回未知用量，而非零用量。
     */
    @Test
    void fromJsonInvalidReturnsEmpty() {
        assertNull(LlmUsageMapper.fromJson(null).totalTokens());
        assertNull(LlmUsageMapper.fromJson("not-a-map").totalTokens());
        assertNull(LlmUsage.empty().totalTokens());
        assertEquals(UsageSource.UNKNOWN, LlmUsage.empty().usageSource());
    }
}
