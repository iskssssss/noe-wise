package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.model.ModelSelections;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.institution.common.TenantIds;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 机构只选择平台已配置模型，不接收任何模型连接或凭据。 */
@RestController
@RequestMapping("/api/v1/settings")
public class ModelSelectionController {
    private final PlatformModelCatalogService models;
    public ModelSelectionController(PlatformModelCatalogService models) { this.models = models; }
    @RequirePermission("foundation.models.read")
    @GetMapping("/available-models")
    public ApiResponse<List<PlatformModelCatalogService.ModelSummary>> available() {
        TenantIds.requireTenantId(); return ApiResponses.wrap(models.available());
    }
    @RequirePermission("foundation.models.read")
    @GetMapping("/model-selections")
    public ApiResponse<ModelSelections> get() { return ApiResponses.wrap(models.selections(TenantIds.requireTenantId())); }
    @RequirePermission("foundation.models.update")
    @PutMapping("/model-selections")
    public ApiResponse<ModelSelections> save(@RequestBody ModelSelections selections) {
        return ApiResponses.wrap(models.select(TenantIds.requireTenantId(), selections));
    }
}
