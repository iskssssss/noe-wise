package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 知识库检索请求 DTO。
 *
 * @param kbId                 目标知识库 ID，必填
 * @param query                查询文本，必填
 * @param topK                 返回条数上限，可选，默认取知识库设置
 * @param retrievalMode        检索模式覆盖：semantic / full_text / hybrid，空值使用知识库设置
 * @param scoreThresholdEnabled 相关度阈值开关覆盖，空值使用知识库设置（仅 semantic / hybrid 生效）
 * @param scoreThreshold       相关度阈值覆盖（0-1），空值使用知识库设置
 * @param vectorWeight         混合检索向量权重覆盖（0-1，关键词权重 = 1 - 该值），空值使用知识库设置
 * @param rerankEnabled        Rerank 重排开关覆盖，空值使用知识库设置
 * @param sourceVersion        精确 source_version 钉扎，可空
 */
public record KnowledgeSearchRequest(
        @NotBlank
        @Schema(description = "目标知识库 ID，必填") String kbId,
        @NotBlank
        @Schema(description = "查询文本，必填") String query,
        @Min(1)
        @Schema(description = "返回条数上限，可选，默认取知识库设置") Integer topK,
        @Schema(description = "检索模式覆盖：semantic 向量 / full_text 全文 / hybrid 混合，空值使用知识库设置") String retrievalMode,
        @Schema(description = "相关度阈值开关覆盖，空值使用知识库设置（仅 semantic / hybrid 生效）") Boolean scoreThresholdEnabled,
        @Schema(description = "相关度阈值覆盖（0-1），空值使用知识库设置") Double scoreThreshold,
        @Schema(description = "混合检索向量权重覆盖（0-1，关键词权重 = 1 - 该值），空值使用知识库设置") Double vectorWeight,
        @Schema(description = "Rerank 重排开关覆盖，空值使用知识库设置") Boolean rerankEnabled,
        @Schema(description = "精确 source_version 钉扎，可空") String sourceVersion) {

    public KnowledgeSearchRequest(String kbId, String query, Integer topK,
                                  String retrievalMode, Boolean scoreThresholdEnabled,
                                  Double scoreThreshold, Double vectorWeight, Boolean rerankEnabled) {
        this(kbId, query, topK, retrievalMode, scoreThresholdEnabled, scoreThreshold,
                vectorWeight, rerankEnabled, null);
    }
}
