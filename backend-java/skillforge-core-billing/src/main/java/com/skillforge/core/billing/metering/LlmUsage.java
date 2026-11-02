package com.skillforge.core.billing.metering;

/**
 * 模型调用的计费用量事实。可空 token 表示未报告，不能当成零；缓存和推理 token 是子集。
 * 该类型不解析供应商协议，不依赖模型实现或 Runtime。
 */
public record LlmUsage(
        Long promptTokens,
        Long cachedInputTokens,
        Long cacheWriteInputTokens,
        Long completionTokens,
        Long reasoningOutputTokens,
        Long totalTokens,
        UsageSource usageSource,
        String estimatorVersion) {

    /**
     * 保留构造完整服务商计量的简洁入口。
     *
     * @param promptTokens 输入 token
     * @param completionTokens 输出 token
     * @param totalTokens 总 token
     */
    public LlmUsage(long promptTokens, long completionTokens, long totalTokens) {
        this(promptTokens, null, null, completionTokens, null, totalTokens,
                UsageSource.PROVIDER_REPORTED, null);
    }

    /**
     * 未知用量：调用失败或服务商未返回 usage 时使用。
     *
     * @return token 均为 null 的未知用量
     */
    public static LlmUsage empty() {
        return new LlmUsage(null, null, null, null, null, null, UsageSource.UNKNOWN, null);
    }

}
