package com.skillforge.institution.tool;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.capability.CapabilityCatalogErrors;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/capabilities/deployments/create-with-receipt")
public class ToolDeploymentCreationController {
    private final ToolDeploymentCreationService service;
    public ToolDeploymentCreationController(ToolDeploymentCreationService service) { this.service = service; }

    @RequirePermission("foundation.capabilities.create")
    @PostMapping
    public ApiResponse<ToolDeploymentCreationService.Result> create(@Valid @RequestBody ToolDeploymentCreationService.Command command) {
        long tenant = Long.parseLong(TenantIds.requireTenantId());
        try {
            return ApiResponses.wrap(service.create(tenant, command));
        } catch (ApiException failure) {
            boolean invalid = failure.getStatus().value() == 422 && Set.of(
                    CapabilityCatalogErrors.DEPLOYMENT_INVALID,
                    CapabilityCatalogErrors.VERSION_NOT_PUBLISHED,
                    CapabilityCatalogErrors.INVALID_ARGUMENT).contains(failure.getCode());
            boolean duplicate = failure.getStatus().value() == 409
                    && CapabilityCatalogErrors.DUPLICATE.equals(failure.getCode());
            if (!invalid && !duplicate) throw failure;
            return ApiResponses.wrap(service.reject(tenant, command));
        }
    }

    /** 204 仅表示原回执当前不可见。 */
    @RequirePermission("foundation.capabilities.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<ToolDeploymentCreationService.Result>> result(@RequestParam("request_id") UUID requestId) {
        var result = service.result(Long.parseLong(TenantIds.requireTenantId()), requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }
}
