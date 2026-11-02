package com.skillforge.core.knowledge.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 文档分段列表项响应 DTO。
 *
 * @param id           分段主键
 * @param chunkIndex   分段序号（从 0 起）
 * @param content      分段内容
 * @param hasEmbedding 是否已有向量（false 表示 embedding 生成失败，仅关键词可命中）
 * @param sourceVersion 所属不可变来源版本
 * @param createdAt    创建时间
 */
public record ChunkItemResponse(
        @Schema(description = "分段主键（雪花 ID）") Long id,
        @Schema(description = "分段序号，从 0 起") Integer chunkIndex,
        @Schema(description = "分段内容") String content,
        @Schema(description = "是否已有向量（false 表示 embedding 生成失败，仅关键词可命中）") boolean hasEmbedding,
        @Schema(description = "所属不可变 source_version") String sourceVersion,
        @Schema(description = "创建时间") Instant createdAt) {
}
