package com.skillforge.runtime.adapter.model.discovery;

import java.util.List;

/** 采用 DSH ListingEntry/ListedFacts 的声明事实形状；null 表示未报告，不授权执行能力。 */
public record ModelDiscoveryMetadata(String name, Context context, List<String> inputModalities,
        List<String> outputModalities, Reasoning reasoning, List<String> capabilities,
        List<String> features, Messages messages) {
    public ModelDiscoveryMetadata {
        inputModalities = copy(inputModalities); outputModalities = copy(outputModalities);
        capabilities = copy(capabilities); features = copy(features);
    }
    public record Context(Long contextWindow, Long maxInputTokens, Long maxOutputTokens,
            Long maxReasoningTokens, Long reasoningMaxInputTokens, Long reasoningMaxOutputTokens) { }
    public record Reasoning(List<String> supportedLevels, String defaultLevel) {
        public Reasoning { supportedLevels = copy(supportedLevels); }
    }
    public record Messages(String systemPromptUpdate, String toolUpdate) { }
    private static List<String> copy(List<String> values) { return values == null ? null : List.copyOf(values); }
}
