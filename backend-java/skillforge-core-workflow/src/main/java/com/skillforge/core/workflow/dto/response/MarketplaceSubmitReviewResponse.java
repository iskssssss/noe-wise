package com.skillforge.core.workflow.dto.response;
/**
 * 提交工作流审核响应 DTO。
 *
 * @param workflowId  工作流业务 ID
 * @param status   审核后状态
 * @param checksum 安全扫描校验和
 */
public record MarketplaceSubmitReviewResponse(
        String workflowId,
        String status,
        String checksum) {
}
