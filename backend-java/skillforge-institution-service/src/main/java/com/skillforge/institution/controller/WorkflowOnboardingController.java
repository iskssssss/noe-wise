package com.skillforge.institution.controller;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.tool.WorkflowOnboardingService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agents/workflows/onboard")
public class WorkflowOnboardingController {
    private final WorkflowOnboardingService service;
    public WorkflowOnboardingController(WorkflowOnboardingService service) { this.service = service; }

    @RequirePermission("foundation.workflows.create")
    @PostMapping
    public ApiResponse<WorkflowOnboardingService.Receipt> onboard(@RequestBody WorkflowOnboardingService.Command command) {
        return ApiResponses.wrap(service.onboard(Long.parseLong(TenantIds.requireTenantId()), command));
    }

    /** 204 仅表示原回执尚未查到，不能推断写入失败。 */
    @RequirePermission("foundation.agents.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<WorkflowOnboardingService.Receipt>> result(@RequestParam UUID requestId) {
        var receipt = service.result(Long.parseLong(TenantIds.requireTenantId()), requestId);
        return receipt == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(receipt));
    }
}
