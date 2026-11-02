package com.skillforge.core.workflow.dto.query;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 工作流市场搜索查询对象。
 *
 * <p>GET /api/v1/marketplace/search 的查询参数封装；
 * 缺省值在控制器侧按 {@code MarketplaceConstants} 归一化。
 *
 * @param q         搜索关键字，可空
 * @param workflowType 工作流类型筛选，可空
 * @param category  分类筛选，可空
 * @param sortBy    排序方式，缺省按发布时间
 * @param page      页码，缺省 1
 * @param pageSize  每页条数，缺省 10
 */
public record MarketplaceSearchQuery(
        @Schema(description = "搜索关键字，可空") String q,
        @Schema(description = "工作流类型筛选，可空") String workflowType,
        @Schema(description = "分类筛选，可空") String category,
        @Schema(description = "排序方式，缺省按发布时间") String sortBy,
        @Schema(description = "页码，缺省 1") Integer page,
        @Schema(description = "每页条数，缺省 10") Integer pageSize) {
}
