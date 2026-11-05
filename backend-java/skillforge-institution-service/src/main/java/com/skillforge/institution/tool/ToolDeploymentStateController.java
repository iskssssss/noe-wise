package com.skillforge.institution.tool;

import com.skillforge.core.capability.CapabilityCatalogErrors;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/capabilities/deployments/state-with-receipt")
public class ToolDeploymentStateController {
    private final ToolDeploymentStateService service;
    public ToolDeploymentStateController(ToolDeploymentStateService service) { this.service = service; }

    /** 写权限由服务按明确目标状态分别检查，不能要求启停双权限。 */
    @PostMapping
    public ApiResponse<ToolDeploymentStateService.Result> change(@Valid @RequestBody ToolDeploymentStateService.Command command) {
        long tenant = Long.parseLong(TenantIds.requireTenantId());
        try { return ApiResponses.wrap(service.change(tenant, command)); }
        catch (ApiException rejected) {
            if (rejected.getStatus().value() != 409 || !CapabilityCatalogErrors.REVISION_CONFLICT.equals(rejected.getCode())) throw rejected;
            return ApiResponses.wrap(service.reject(tenant, command));
        }
    }

    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<ToolDeploymentStateService.Result>> result(@RequestParam("request_id") UUID requestId) {
        var result = service.result(Long.parseLong(TenantIds.requireTenantId()), requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }
}
