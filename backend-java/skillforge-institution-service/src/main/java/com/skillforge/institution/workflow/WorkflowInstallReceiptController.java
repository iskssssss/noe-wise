package com.skillforge.institution.workflow;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/builder/workflows/install-with-receipt")
public class WorkflowInstallReceiptController {
    private final WorkflowInstallReceiptService service;
    public WorkflowInstallReceiptController(WorkflowInstallReceiptService service) { this.service = service; }

    @PostMapping
    public ApiResponse<WorkflowInstallReceiptService.Receipt> install(@RequestBody WorkflowInstallReceiptService.Command command) {
        try { return ApiResponses.wrap(service.install(Long.parseLong(TenantIds.requireTenantId()), command)); }
        catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408) throw failure;
            throw new ApiException(failure.getStatus(), "WORKFLOW_INSTALL_REJECTED", String.valueOf(failure.getDetail()), false);
        }
    }
    @RequirePermission("foundation.workflows.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<WorkflowInstallReceiptService.Receipt>> result(
            @RequestParam("workflow_id") String workflowId, @RequestParam("request_id") UUID requestId) {
        var receipt = service.result(Long.parseLong(TenantIds.requireTenantId()), workflowId, requestId);
        return receipt == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(receipt));
    }
}
