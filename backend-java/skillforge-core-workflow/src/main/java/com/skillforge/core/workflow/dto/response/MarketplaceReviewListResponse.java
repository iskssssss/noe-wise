package com.skillforge.core.workflow.dto.response;
import java.util.List;
/**
 * 工作流审核队列响应 DTO。
 *
 * @param total 审核项总数
 * @param items 审核列表
 */
public record MarketplaceReviewListResponse(
        long total,
        int page,
        int limit,
        List<MarketplaceReviewItemResponse> items) {
}
