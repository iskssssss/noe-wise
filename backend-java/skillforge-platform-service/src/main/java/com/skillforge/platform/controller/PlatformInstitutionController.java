package com.skillforge.platform.controller;

import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.IntegrationContracts.*;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台直管机构入口；业务服务验证当前人员/管理机器动作及目标，路由不授予权限。 */
@RestController
@RequestMapping("/api/v1/platform/institutions")
public class PlatformInstitutionController {
    private final InstitutionIntegrationService institutions;
    /** 复用同库开通与对象授权事务。 */
    public PlatformInstitutionController(InstitutionIntegrationService institutions) { this.institutions = institutions; }
    /** 目录在当前目标过滤后分页。 */
    @GetMapping
    public ApiResponse<Page<Institution>> list(@RequestParam(required=false) String namespace,
            @RequestParam(name="tenant_id",required=false) String tenantId,
            @RequestParam(defaultValue="1") int page, @RequestParam(name="page_size",defaultValue="20") int pageSize) {
        return ApiResponses.wrap(institutions.institutions(namespace, tenantId, page, pageSize));
    }
    /** 开通与默认策略、映射和回执原子提交，不自动签发凭证。 */
    @PostMapping("/provision")
    public ApiResponse<Institution> provision(@RequestBody ProvisionInstitution request,
            @RequestHeader("Idempotency-Key") String key) { return ApiResponses.wrap(institutions.provision(request, key)); }
    /** 认领既有Tenant必须有明确目标管理权。 */
    @PostMapping("/associate")
    public ApiResponse<Institution> associate(@RequestBody AssociateInstitution request,
            @RequestHeader("Idempotency-Key") String key) { return ApiResponses.wrap(institutions.associate(request, key)); }
}
