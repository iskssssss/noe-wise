package com.skillforge.institution.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.knowledge.KnowledgeBaseRevisionService;
import com.skillforge.core.knowledge.dto.request.MarkKnowledgeBaseRevisionReadyRequest;
import com.skillforge.core.knowledge.dto.response.KnowledgeBaseRevisionResponse;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.service.KnowledgeManagementService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 将显式文档范围与治理策略一次准备为 ready，发布仍由独立入口确认。 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeRevisionPreparationController {
    private final KnowledgeBaseRevisionService revisions;
    private final KnowledgeManagementService management;
    private final FoundationAccess access;

    public KnowledgeRevisionPreparationController(KnowledgeBaseRevisionService revisions,
            KnowledgeManagementService management, FoundationAccess access) {
        this.revisions = revisions;
        this.management = management;
        this.access = access;
    }

    @Operation(summary = "准备知识库发布版本", description = "原子创建或复用草稿并固定显式 source_version manifest 与治理策略；仅返回 ready，不发布")
    @RequirePermission("foundation.knowledge.create")
    @PostMapping("/bases/revisions/prepare")
    public ApiResponse<KnowledgeBaseRevisionResponse> prepare(@Valid @RequestBody PrepareCommand command) {
        access.require("foundation.knowledge.update");
        String tenant = TenantIds.requireTenantId();
        management.requireBase(tenant, command.kbId(), "manage");
        return ApiResponses.wrap(revisions.prepare(tenant, command.kbId(),
                command.payload().sourceVersions(), command.payload().policy()));
    }

    public record PrepareCommand(
            @JsonProperty("kb_id") @NotNull Long kbId,
            @JsonProperty("payload") @NotNull @Valid MarkKnowledgeBaseRevisionReadyRequest payload) {}
}
