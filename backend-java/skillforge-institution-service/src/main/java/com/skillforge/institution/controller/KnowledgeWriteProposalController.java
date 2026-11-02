package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.knowledge.dto.knowledge.CreateKnowledgeWriteProposalRequest;
import com.skillforge.core.knowledge.dto.knowledge.ReviewKnowledgeWriteProposalRequest;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposalReviewResult;
import com.skillforge.core.knowledge.application.KnowledgeWriteProposalService;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.service.KnowledgeProposalManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Knowledge 写回提案治理 API；审批后返回事实源投影结果。 */
@Tag(name = "Knowledge 写回提案", description = "追加提案、人工审核并执行幂等事实投影")
@RestController
@RequestMapping("/api/v1/knowledge/write-proposals")
public class KnowledgeWriteProposalController {

    private final KnowledgeWriteProposalService proposalService;
    private final KnowledgeProposalManagementService management;

    public KnowledgeWriteProposalController(KnowledgeWriteProposalService proposalService, KnowledgeProposalManagementService management) {
        this.proposalService = proposalService;
        this.management = management;
    }

    @Operation(summary = "创建写回提案", description = "仅创建 pending_confirm 提案，不直接修改事实源")
    @RequirePermission("foundation.knowledge.create")
    @PostMapping
    public ApiResponse<KnowledgeWriteProposal> create(@RequestBody CreateKnowledgeWriteProposalRequest request) {
        var proposal = new KnowledgeWriteProposal(
                request.proposalId(), request.target(), request.sourceEventId(), request.bundleId(),
                request.payload(), KnowledgeWriteProposal.Status.PENDING_CONFIRM);
        management.require(TenantIds.requireTenantId(), proposal, "manage");
        return ApiResponses.wrap(proposalService.create(TenantIds.requireTenantId(), proposal));
    }

    @Operation(summary = "列出待审核提案")
    @RequirePermission("foundation.knowledge.read")
    @GetMapping("/pending")
    public ApiResponse<List<KnowledgeWriteProposal>> pending() {
        return ApiResponses.wrap(management.pending(TenantIds.requireTenantId()));
    }

    @Operation(summary = "批准写回提案", description = "批准 MEMORY 提案后幂等投影为 active 事实")
    @RequirePermission("foundation.knowledge.publish")
    @PostMapping("/approve")
    public ApiResponse<KnowledgeWriteProposalReviewResult> approveEndpoint(
            @Valid @RequestBody ApproveCommand command) {
        return ApiResponses.wrap(approve(command.proposalId(), command.payload()));
    }

    public KnowledgeWriteProposalReviewResult approve(String proposalId, ReviewKnowledgeWriteProposalRequest request) {
        management.require(TenantIds.requireTenantId(), proposalId, "manage");
        return proposalService.approve(TenantIds.requireTenantId(), proposalId,
                management.reviewer(), request.note());
    }

    @Operation(summary = "驳回写回提案")
    @RequirePermission("foundation.knowledge.update")
    @PostMapping("/reject")
    public ApiResponse<KnowledgeWriteProposal> rejectEndpoint(
            @Valid @RequestBody RejectCommand command) {
        return ApiResponses.wrap(reject(command.proposalId(), command.payload()));
    }

    public KnowledgeWriteProposal reject(String proposalId, ReviewKnowledgeWriteProposalRequest request) {
        management.require(TenantIds.requireTenantId(), proposalId, "manage");
        return proposalService.reject(TenantIds.requireTenantId(), proposalId,
                management.reviewer(), request.note());
    }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record ApproveCommand(
            @JsonProperty("proposal_id") @NotNull String proposalId,
            @JsonProperty("payload") @NotNull @Valid ReviewKnowledgeWriteProposalRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record RejectCommand(
            @JsonProperty("proposal_id") @NotNull String proposalId,
            @JsonProperty("payload") @NotNull @Valid ReviewKnowledgeWriteProposalRequest payload) {}
}
