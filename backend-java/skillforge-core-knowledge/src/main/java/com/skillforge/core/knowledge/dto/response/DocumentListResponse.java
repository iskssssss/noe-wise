package com.skillforge.core.knowledge.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 文档分页列表响应 DTO。
 *
 * @param total 总数
 * @param items 文档列表
 */
public record DocumentListResponse(
        @Schema(description = "文档总数") long total,
        @Schema(description = "文档列表") List<DocumentItemResponse> items) {
}
