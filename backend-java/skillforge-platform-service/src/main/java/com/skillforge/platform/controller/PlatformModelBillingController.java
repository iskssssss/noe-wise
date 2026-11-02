package com.skillforge.platform.controller;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.application.ModelBillingService;
import com.skillforge.core.billing.dto.billing.ModelBillingContracts.Monthly;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台按明确机构读取模型月费用，不维护独立销售价格或出账状态。 */
@RestController
@RequestMapping("/api/v1/platform/billing")
public class PlatformModelBillingController {
    private final ModelBillingService billing;

    public PlatformModelBillingController(ModelBillingService billing) { this.billing = billing; }

    @RequirePermission("foundation.billing.read")
    @GetMapping("/monthly")
    public ApiResponse<Monthly> monthly(@RequestParam("tenant_id") long tenant,
            @RequestParam(value = "month", required = false) String month,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "page_size", required = false) Integer pageSize) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(Long.toString(tenant));
            return ApiResponses.wrap(billing.monthly(tenant, month, page, pageSize));
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }
}
