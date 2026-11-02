package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.billing.reconciliation.TenantUsageReconciliationConfig;
import com.skillforge.core.billing.reconciliation.TenantUsageReconciliationConfigRequest;
import com.skillforge.core.billing.reconciliation.TenantUsageReconciliationConfigService;
import com.skillforge.institution.common.TenantIds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前租户第三方账单 Provider 配置管理；凭据只在写入时接收，不返回明文。 */
@Tag(name = "账单对账 Provider", description = "租户级第三方账单凭据与响应 schema 管理")
@RestController
@RequestMapping("/api/v1/billing/reconciliation/provider")
public class UsageReconciliationConfigController {

    private final TenantUsageReconciliationConfigService configService;

    public UsageReconciliationConfigController(TenantUsageReconciliationConfigService configService) {
        this.configService = configService;
    }

    @Operation(summary = "读取账单 Provider 配置", description = "只返回 endpoint、header、schema 与 api_key_configured，不返回任何凭据")
    @RequirePermission("foundation.billing.read")
    @GetMapping
    public ApiResponse<TenantUsageReconciliationConfig> get() {
        return ApiResponses.wrap(configService.get(TenantIds.requireTenantId()));
    }

    @Operation(summary = "保存账单 Provider 配置", description = "API key 仅用于本次加密写入；provider 为空表示清除租户覆盖配置")
    @RequirePermission("foundation.billing.update")
    @PutMapping
    public ApiResponse<TenantUsageReconciliationConfig> save(@RequestBody TenantUsageReconciliationConfigRequest request) {
        return ApiResponses.wrap(configService.save(TenantIds.requireTenantId(), request));
    }
}
