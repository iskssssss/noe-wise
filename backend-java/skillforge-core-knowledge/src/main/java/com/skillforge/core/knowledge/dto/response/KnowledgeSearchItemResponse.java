package com.skillforge.core.knowledge.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 知识检索结果项 DTO。
 *
 * @param content     分块正文
 * @param score       检索得分（经 Rerank 时为重排相关度，否则为向量/融合分）
 * @param vectorScore 重排前的相似度分数（未 Rerank 时为 null）
 * @param chunkId     分块 ID
 * @param documentId  所属文档 ID
 */
public record KnowledgeSearchItemResponse(
        @Schema(description = "分块正文") String content,
        @Schema(description = "检索得分：经 Rerank 时为重排相关度（0-1），否则为向量相似度/混合融合分", example = "0.8581") double score,
        @Schema(description = "重排前的相似度分数（未开 Rerank 时为 null；与 score 标尺不同，勿直接比较）", nullable = true, example = "0.6034") Double vectorScore,
        @Schema(description = "分块 ID") String chunkId,
        @Schema(description = "所属文档 ID") String documentId,
        @Schema(description = "命中的精确来源版本") String sourceVersion) {
}
