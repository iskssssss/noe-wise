package com.skillforge.core.workflow.dto.response;
import com.skillforge.core.workflow.enums.ReviewStatus;
import java.time.Instant;
/**
 * 工作流审核队列项 DTO。
 *
 * @param id          工作流业务 ID
 * @param workflowName   工作流名称
 * @param author      作者
 * @param status      审核 UI 状态
 * @param revisionAt 提交时间
 */
public record MarketplaceReviewItemResponse(
        String id,
        String workflowName,
        String author,
        ReviewStatus status,
        Instant revisionAt,
        String version,
        Instant updatedAt,
        String reason,
        String reviewerId,
        Instant reviewedAt) {
}
