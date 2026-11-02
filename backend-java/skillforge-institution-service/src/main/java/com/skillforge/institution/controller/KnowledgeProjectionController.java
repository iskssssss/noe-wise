package com.skillforge.institution.controller;
import com.skillforge.core.knowledge.KnowledgeService;
import com.skillforge.institution.service.KnowledgeManagementService;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
/** 管理员显式修复精确来源版本的可重建投影，PG仍是发布事实源。 */
@RestController @RequestMapping("/api/v1/knowledge/bases/projections")
public class KnowledgeProjectionController {
    private final KnowledgeService knowledge;
    private final KnowledgeManagementService management;
    public KnowledgeProjectionController(KnowledgeService knowledge, KnowledgeManagementService management) { this.knowledge = knowledge; this.management = management; }
    @Operation(summary="修复知识来源ES投影", description="只重放本租户完整PG来源分块和向量，不调用模型、不修改发布指针；未确认全部写入返回503")
    @RequirePermission("foundation.knowledge.run")
    @PostMapping("/repair") public ApiResponse<KnowledgeService.ProjectionRepair> repair(@Valid @RequestBody Repair request) {
        management.requireBase(TenantIds.requireTenantId(), request.kbId(), "manage");
        return ApiResponses.wrap(knowledge.repairProjection(TenantIds.requireTenantId(), request.kbId(), request.sourceVersion()));
    }
    public record Repair(@Positive long kbId, @NotBlank @Size(max=128) String sourceVersion) {}
}
