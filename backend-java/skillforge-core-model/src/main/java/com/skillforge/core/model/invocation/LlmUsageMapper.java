package com.skillforge.core.model.invocation;

import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.UsageSource;
import java.util.Map;

/** 将 Runtime 或供应商协议用量无损映射为计费计量，协议解析仅由模型边界拥有。 */
public final class LlmUsageMapper {
    private LlmUsageMapper() {
    }

    /** Runtime 到宿主计量的无损转换，部分报告不能因另一字段缺失而被丢弃。 */
    public static LlmUsage fromModel(com.skillforge.runtime.api.port.ModelPort.ModelUsage value) {
        Long input = nullable(value.inputTokens());
        Long output = nullable(value.outputTokens());
        Long cached = nullable(value.cachedInputTokens());
        Long written = nullable(value.cacheWriteInputTokens());
        Long reasoning = nullable(value.reasoningOutputTokens());
        Long total = null;
        if (input != null && output != null) {
            try { total = Math.addExact(input, output); } catch (ArithmeticException overflow) { /* 子项保持精确，总量交给高精度聚合。 */ }
        }
        return new LlmUsage(input, cached, written, output, reasoning, total,
                input == null && output == null && cached == null && written == null && reasoning == null
                        ? UsageSource.UNKNOWN : UsageSource.PROVIDER_REPORTED, null);
    }

    private static Long nullable(java.util.OptionalLong value) {
        return value.isPresent() ? value.getAsLong() : null;
    }

    /**
     * 从响应 usage JSON 解析。
     *
     * <p>只解析供应商实际报告的字段。只有输入和输出均报告时，才补出
     * {@code total_tokens}；部分响应不以零填补未知字段。缓存/推理 token 是对应输入或
     * 输出 token 的子集，不能再参与总量求和。非法结构返回未知用量。
     *
     * @param usage 响应 usage 字段（Map 或 null）
     * @return 解析后的用量
     */
    @SuppressWarnings("unchecked")
    public static LlmUsage fromJson(Object usage) {
        if (!(usage instanceof Map<?, ?> map)) {
            return LlmUsage.empty();
        }
        Long prompt = nonNegativeLong(map.get("prompt_tokens"));
        Long completion = nonNegativeLong(map.get("completion_tokens"));
        Long total = nonNegativeLong(map.get("total_tokens"));
        Long cachedInput = nestedToken(map, "prompt_tokens_details", "cached_tokens");
        if (cachedInput == null) cachedInput = nonNegativeLong(map.get("prompt_cache_hit_tokens"));
        if (cachedInput == null) {
            cachedInput = nestedToken(map, "input_tokens_details", "cache_read_input_tokens");
        }
        Long cacheWriteInput = nestedToken(map, "prompt_tokens_details", "cache_creation_tokens");
        if (cacheWriteInput == null) {
            cacheWriteInput = nestedToken(map, "input_tokens_details", "cache_creation_input_tokens");
        }
        Long reasoningOutput = nestedToken(map, "completion_tokens_details", "reasoning_tokens");
        if (reasoningOutput == null) {
            reasoningOutput = nestedToken(map, "output_tokens_details", "reasoning_tokens");
        }
        if (total == null && prompt != null && completion != null) {
            try { total = Math.addExact(prompt, completion); } catch (ArithmeticException overflow) { total = null; }
        }
        if (prompt == null && completion == null && total == null
                && cachedInput == null && cacheWriteInput == null && reasoningOutput == null) {
            return LlmUsage.empty();
        }
        return new LlmUsage(prompt, cachedInput, cacheWriteInput, completion, reasoningOutput, total,
                UsageSource.PROVIDER_REPORTED, null);
    }

    /**
     * 读取嵌套 token 明细。
     *
     * @param map 父对象
     * @param field 嵌套对象字段
     * @param tokenField token 字段
     * @return 合法非负整数，否则 null
     */
    private static Long nestedToken(Map<?, ?> map, String field, String tokenField) {
        Object nested = map.get(field);
        return nested instanceof Map<?, ?> nestedMap ? nonNegativeLong(nestedMap.get(tokenField)) : null;
    }

    /** 安全转换为非负整数；非法值保持未知。 */
    private static Long nonNegativeLong(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            long parsed = new java.math.BigDecimal(number.toString()).longValueExact();
            return parsed < 0 ? null : parsed;
        } catch (ArithmeticException | NumberFormatException invalid) {
            return null;
        }
    }
}
