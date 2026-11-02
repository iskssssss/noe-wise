package com.skillforge.core.workflow.dto.response;
/**
 * 工作流审核处理响应 DTO。
 *
 * @param workflowId 工作流业务 ID
 * @param status  处理后的模板状态
 */
public record MarketplaceReviewDecisionResponse(
        String workflowId,
        String status) {
}
