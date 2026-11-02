package com.skillforge.core.knowledge.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 文档列表项响应 DTO。
 *
 * @param id         文档主键
 * @param title      文档标题
 * @param sourceType 来源类型：file 文件导入 / projection 机构知识投影
 * @param status     处理状态：ready 已就绪 / processing 处理中 / failed 处理失败
 * @param sourceVersion 摄取版本，用于知识库 Revision ready/publish
 * @param chunkCount 分块数
 * @param createdAt  创建时间
 */
public record DocumentItemResponse(
        @Schema(description = "文档主键（雪花 ID）") Long id,
        @Schema(description = "文档标题") String title,
        @Schema(description = "来源类型：file 文件导入 / projection 机构知识投影") String sourceType,
        @Schema(description = "处理状态：ready 已就绪 / processing 处理中 / failed 处理失败") String status,
        @Schema(description = "摄取版本，用于知识库 Revision ready/publish") String sourceVersion,
        @Schema(description = "分块数") int chunkCount,
        @Schema(description = "创建时间") Instant createdAt) {
}
