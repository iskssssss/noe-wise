package com.skillforge.core.knowledge.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 知识库详情响应 DTO。
 *
 * @param id                    知识库主键
 * @param name                  知识库名称
 * @param description           知识库描述，可空
 * @param status                知识库状态：active / archived
 * @param activeRevisionId      当前已发布 revision 主键，可空
 * @param chunkSize             分段长度（字符），null 表示默认
 * @param chunkOverlap          分段重叠（字符），null 表示默认
 * @param indexingMode          索引方式：high_quality / economical
 * @param retrievalMode         检索模式：semantic / full_text / hybrid
 * @param topK                  检索默认条数
 * @param scoreThresholdEnabled 相关度阈值开关
 * @param scoreThreshold        相关度阈值（0-1）
 * @param vectorWeight          混合检索向量权重（0-1）
 * @param rerankEnabled         Rerank 重排开关
 * @param documentCount         文档数
 * @param chunkCount            分块数
 * @param createdAt             创建时间
 */
public record KnowledgeBaseDetailResponse(
        @Schema(type = "string", description = "知识库主键（雪花 ID）", example = "834824179306501")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        @Schema(description = "知识库名称", example = "产品知识库") String name,
        @Schema(description = "知识库描述，可空") String description,
        @Schema(description = "知识库状态：active 可用 / archived 已归档") String status,
        @Schema(type = "string", description = "当前 active published revision 主键，可空")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long activeRevisionId,
        @Schema(description = "分段长度（字符），null 表示默认") Integer chunkSize,
        @Schema(description = "分段重叠（字符），null 表示默认") Integer chunkOverlap,
        @Schema(description = "索引方式：high_quality / economical") String indexingMode,
        @Schema(description = "检索模式：semantic / full_text / hybrid") String retrievalMode,
        @Schema(description = "检索默认条数") Integer topK,
        @Schema(description = "相关度阈值开关") Boolean scoreThresholdEnabled,
        @Schema(description = "相关度阈值（0-1）") Double scoreThreshold,
        @Schema(description = "混合检索向量权重（0-1）") Double vectorWeight,
        @Schema(description = "Rerank 重排开关") Boolean rerankEnabled,
        @Schema(description = "文档数") long documentCount,
        @Schema(description = "分块数") long chunkCount,
        @Schema(description = "创建时间") Instant createdAt) {
}
