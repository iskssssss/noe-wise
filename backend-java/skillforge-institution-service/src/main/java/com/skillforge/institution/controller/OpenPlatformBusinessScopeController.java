package com.skillforge.institution.controller;

import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.IntegrationContracts.*;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前机构执行身份声明或停用范围；Tenant/integration只取可信ServicePrincipal。 */
@RestController
@RequestMapping(value="/api/v1/open-platform/business-scopes",produces="application/json")
public class OpenPlatformBusinessScopeController {
    private final InstitutionIntegrationService integrations;
    /** 注入固定执行域授权与范围CAS事务。 */
    public OpenPlatformBusinessScopeController(InstitutionIntegrationService integrations) { this.integrations = integrations; }
    /** 同参ensure重读原回执，外部门店键不会扩大资源访问。 */
    @PostMapping("/ensure")
    public ApiResponse<BusinessScope> ensure(@RequestBody EnsureBusinessScope request,
            @RequestHeader("Idempotency-Key") String key) { return ApiResponses.wrap(integrations.ensureBusinessScope(request, key)); }
    /** 当前范围状态显式CAS，撤销终态不恢复。 */
    @PostMapping("/status")
    public ApiResponse<BusinessScope> status(@RequestBody BusinessScopeStatus request,
            @RequestHeader("Idempotency-Key") String key) { return ApiResponses.wrap(integrations.setBusinessScopeStatus(request, key)); }
}
