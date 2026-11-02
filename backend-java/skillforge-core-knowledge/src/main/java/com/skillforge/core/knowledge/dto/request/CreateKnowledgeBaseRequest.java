package com.skillforge.core.knowledge.dto.request;

import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;

/** 建库即确定索引语义；经济索引不解析或冻结不存在的 Embedding 模型。 */
public record CreateKnowledgeBaseRequest(
        @NotBlank String name,
        String description,
        @Schema(description = "索引方式 high_quality/economical；缺失沿用运营默认") String indexingMode,
        @Schema(description = "检索方式 semantic/full_text/hybrid；经济索引仅允许full_text，缺失自动采用full_text") String retrievalMode,
        @Schema(description = "重排开关，缺失为false；经济索引仅允许false") Boolean rerankEnabled,
        Integer chunkSize, Integer chunkOverlap, Integer topK,
        Boolean scoreThresholdEnabled, Double scoreThreshold, Double vectorWeight) { }
