package com.skillforge.core.model;

/** 机构仅保存平台模型引用，空值表示该能力尚未选择。 */
public record ModelSelections(String llmModelConfigId, String embeddingModelConfigId,
        String rerankModelConfigId) {
    public String forKind(String kind) {
        return switch (kind) {
            case "LLM" -> llmModelConfigId;
            case "EMBEDDING" -> embeddingModelConfigId;
            case "RERANK" -> rerankModelConfigId;
            default -> throw new IllegalArgumentException("model_kind_invalid");
        };
    }
}
