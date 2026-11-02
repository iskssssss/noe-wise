package com.skillforge.platform.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.workflow.WorkflowReviewService;
import com.skillforge.core.workflow.dto.response.MarketplaceReviewDecisionResponse;
import com.skillforge.core.workflow.dto.response.MarketplaceReviewListResponse;
import com.skillforge.core.workflow.dto.response.MarketplaceReviewDetailResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;

/** 平台工作流审核协议入口；权限校验后交由审核服务完成事务。 */
@RestController
@RequestMapping("/api/v1/marketplace")
public class PlatformMarketplaceController {
    private final WorkflowReviewService reviews;
    public PlatformMarketplaceController(WorkflowReviewService reviews) { this.reviews = reviews; }

    @RequirePermission("foundation.marketplace.read")
    @GetMapping("/reviews")
    public ApiResponse<MarketplaceReviewListResponse> listReviews(
            @RequestParam(defaultValue = "pending") String status,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponses.wrap(reviews.list(status, search, page, limit));
    }

    @RequirePermission("foundation.marketplace.read")
    @GetMapping("/reviews/detail")
    public ApiResponse<MarketplaceReviewDetailResponse> reviewDetail(
            @RequestParam String workflowId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant expectedUpdatedAt) {
        return ApiResponses.wrap(reviews.detail(workflowId, expectedUpdatedAt));
    }

    @RequirePermission("foundation.marketplace.publish")
    @PostMapping("/reviews/approve")
    public ApiResponse<MarketplaceReviewDecisionResponse> approve(@Valid @RequestBody ApproveCommand command) {
        return ApiResponses.wrap(reviews.decide(command.workflowId(), command.expectedUpdatedAt(), true, null, StpUtil.getLoginIdAsString()));
    }

    @RequirePermission("foundation.marketplace.update")
    @PostMapping("/reviews/reject")
    public ApiResponse<MarketplaceReviewDecisionResponse> reject(@Valid @RequestBody RejectCommand command) {
        return ApiResponses.wrap(reviews.decide(command.workflowId(), command.expectedUpdatedAt(), false, command.reason(), StpUtil.getLoginIdAsString()));
    }

    public record ApproveCommand(
            @JsonProperty("workflow_id") @NotBlank String workflowId,
            @JsonProperty("expected_updated_at") @NotNull Instant expectedUpdatedAt) {}
    public record RejectCommand(
            @JsonProperty("workflow_id") @NotBlank String workflowId,
            @JsonProperty("expected_updated_at") @NotNull Instant expectedUpdatedAt,
            @NotBlank @Size(max = 1000) String reason) {}
}
