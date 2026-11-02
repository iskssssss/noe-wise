package com.skillforge.core.knowledge.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 文档分段分页列表响应 DTO。
 *
 * @param total 分段总数
 * @param items 分段列表
 */
public record ChunkListResponse(
        @Schema(description = "分段总数") long total,
        @Schema(description = "分段列表") List<ChunkItemResponse> items) {
}
