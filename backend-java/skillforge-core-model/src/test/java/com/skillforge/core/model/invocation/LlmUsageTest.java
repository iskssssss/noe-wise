package com.skillforge.core.model.invocation;

import com.skillforge.core.billing.metering.LlmUsage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.skillforge.core.billing.metering.UsageSource;
import java.util.Map;
import java.util.OptionalLong;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import org.junit.jupiter.api.Test;

/** Token 计量的已知/未知与子集解析契约。 */
class LlmUsageTest {

    @Test
    void parsesReportedTokensAndDoesNotDoubleCountSubsets() {
        LlmUsage usage = LlmUsageMapper.fromJson(Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50,
                "total_tokens", 150,
                "prompt_tokens_details", Map.of("cached_tokens", 40, "cache_creation_tokens", 10),
                "completion_tokens_details", Map.of("reasoning_tokens", 20)));

        assertEquals(100, usage.promptTokens());
        assertEquals(40, usage.cachedInputTokens());
        assertEquals(10, usage.cacheWriteInputTokens());
        assertEquals(50, usage.completionTokens());
        assertEquals(20, usage.reasoningOutputTokens());
        assertEquals(150, usage.totalTokens());
        assertEquals(UsageSource.PROVIDER_REPORTED, usage.usageSource());
    }

    @Test
    void partialResponsePreservesUnknownFields() {
        LlmUsage usage = LlmUsageMapper.fromJson(Map.of("prompt_tokens", 25));

        assertEquals(25, usage.promptTokens());
        assertNull(usage.completionTokens());
        assertNull(usage.totalTokens());
        assertEquals(UsageSource.PROVIDER_REPORTED, usage.usageSource());
    }

    @Test
    void invalidResponseIsUnknownRatherThanZero() {
        LlmUsage usage = LlmUsageMapper.fromJson(null);

        assertNull(usage.promptTokens());
        assertNull(usage.totalTokens());
        assertEquals(UsageSource.UNKNOWN, usage.usageSource());
    }

    @Test
    void preservesProviderZeroAndDeepSeekCacheSubset() {
        LlmUsage usage = LlmUsageMapper.fromJson(Map.of("prompt_tokens", 100,
                "completion_tokens", 0, "prompt_cache_hit_tokens", 80,
                "prompt_cache_miss_tokens", 20));
        assertEquals(100, usage.totalTokens());
        assertEquals(0, usage.completionTokens());
        assertEquals(80, usage.cachedInputTokens());
    }

    @Test
    void rejectsFractionalNegativeAndOverflowCountsWithoutTruncating() {
        for (Number value : java.util.List.of(1.5, -1, new java.math.BigInteger("9223372036854775808"), Double.NaN)) {
            assertEquals(UsageSource.UNKNOWN,
                    LlmUsageMapper.fromJson(Map.of("prompt_tokens", value)).usageSource());
        }
        assertEquals(4294967296L, LlmUsageMapper.fromJson(Map.of("prompt_tokens", 4294967296L)).promptTokens());
        assertNull(LlmUsageMapper.fromJson(Map.of("prompt_tokens", Long.MAX_VALUE,
                "completion_tokens", 1)).totalTokens());
    }
    @Test void cachedOnlyReportIsNotDiscardedAndOverflowDoesNotDiscardComponents() {
        var cached = LlmUsageMapper.fromModel(new ModelUsage(OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.of(5), OptionalLong.empty(), OptionalLong.empty()));
        assertEquals(UsageSource.PROVIDER_REPORTED, cached.usageSource());
        assertEquals(5L, cached.cachedInputTokens());
        var overflow = LlmUsageMapper.fromModel(new ModelUsage(OptionalLong.of(Long.MAX_VALUE), OptionalLong.of(1)));
        assertEquals(Long.MAX_VALUE, overflow.promptTokens());
        assertEquals(1L, overflow.completionTokens());
        assertNull(overflow.totalTokens());
    }

}
