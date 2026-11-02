package com.skillforge.core.workflow.dto.response;
import java.util.List;
/**
 * 市场搜索分页响应 DTO。
 *
 * @param total    命中总数
 * @param page     当前页码
 * @param pageSize 每页条数
 * @param items    工作流列表
 */
public record MarketplaceSearchResponse(
        long total,
        int page,
        int pageSize,
        List<MarketplaceWorkflowItemResponse> items) {
}
