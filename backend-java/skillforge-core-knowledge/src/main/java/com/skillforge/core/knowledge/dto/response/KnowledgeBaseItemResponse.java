package com.skillforge.core.knowledge.dto.response;

import com.skillforge.core.knowledge.enums.KnowledgeBaseStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 知识库列表项响应 DTO。
 *
 * @param id            知识库主键
 * @param name          知识库名称
 * @param description   知识库描述，可空
 * @param status        知识库状态
 * @param documentCount 文档数
 * @param chunkCount    分块数
 */
public record KnowledgeBaseItemResponse(
        @Schema(type = "string", description = "知识库主键（雪花 ID）", example = "834824179306501")
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long id,
        @Schema(description = "知识库名称", example = "产品知识库") String name,
        @Schema(description = "知识库描述，可空") String description,
        @Schema(description = "知识库状态：active 可用 / archived 已归档") KnowledgeBaseStatus status,
        @Schema(description = "文档数") long documentCount,
        @Schema(description = "分块数") long chunkCount) {
}
