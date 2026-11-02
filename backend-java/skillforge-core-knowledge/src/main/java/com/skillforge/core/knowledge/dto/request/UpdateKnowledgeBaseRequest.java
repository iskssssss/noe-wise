package com.skillforge.core.knowledge.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 更新知识库请求 DTO。
 *
 * <p>全部字段可空，空值表示不修改对应属性。
 *
 * @param name                 知识库名称
 * @param description          知识库描述
 * @param status               知识库状态：active 恢复 / archived 归档
 * @param chunkSize            分段长度（字符）
 * @param chunkOverlap         分段重叠（字符）
 * @param indexingMode         索引方式：high_quality / economical
 * @param retrievalMode        检索模式：semantic / full_text / hybrid
 * @param topK                 检索默认条数
 * @param scoreThresholdEnabled 相关度阈值开关
 * @param scoreThreshold       相关度阈值（0-1）
 * @param vectorWeight         混合检索向量权重（0-1）
 * @param rerankEnabled        Rerank 重排开关
 */
public record UpdateKnowledgeBaseRequest(
        @Schema(description = "知识库名称，空值不修改") String name,
        @Schema(description = "知识库描述，空值不修改") String description,
        @Schema(description = "知识库状态：active 恢复 / archived 归档，空值不修改") String status,
        @Schema(description = "分段长度（字符），空值不修改") Integer chunkSize,
        @Schema(description = "分段重叠（字符），空值不修改") Integer chunkOverlap,
        @Schema(description = "索引方式：high_quality / economical，空值不修改") String indexingMode,
        @Schema(description = "检索模式：semantic / full_text / hybrid，空值不修改") String retrievalMode,
        @Schema(description = "检索默认条数，空值不修改") Integer topK,
        @Schema(description = "相关度阈值开关，空值不修改") Boolean scoreThresholdEnabled,
        @Schema(description = "相关度阈值（0-1），空值不修改") Double scoreThreshold,
        @Schema(description = "混合检索向量权重（0-1），空值不修改") Double vectorWeight,
        @Schema(description = "Rerank 重排开关，空值不修改") Boolean rerankEnabled) {
}
