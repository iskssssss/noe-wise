package com.skillforge.platform.controller;

import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementContracts.Create;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementContracts.Grant;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementContracts.Page;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementContracts.Revoke;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台显式授予和撤销总部目标部署管理权，双机构目标由领域服务统一检查。 */
@RestController
@RequestMapping("/api/v1/platform/institution-management-grants")
public class PlatformInstitutionManagementGrantController {
    private final InstitutionDeploymentManagementService grants;

    /** 使用唯一许可领域服务，不由 Controller 推导总部身份或机构权限。 */
    public PlatformInstitutionManagementGrantController(InstitutionDeploymentManagementService grants) { this.grants = grants; }

    /** 仅查询当前平台同时可管理的总部和目标许可。 */
    @GetMapping
    public ApiResponse<Page> list(@RequestParam(name = "actor_tenant_id", required = false) Long actorTenantId,
            @RequestParam(name = "target_tenant_id", required = false) Long targetTenantId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponses.wrap(grants.platformList(actorTenantId, targetTenantId, page, pageSize));
    }

    /** 同键同内容重读原安全回执；新许可和审计同事务提交。 */
    @PostMapping
    public ApiResponse<Grant> create(@Valid @RequestBody Create command, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponses.wrap(grants.create(command, key));
    }

    /** 仅按当前修订撤销，不支持激活已撤销记录。 */
    @PostMapping("/revoke")
    public ApiResponse<Grant> revoke(@Valid @RequestBody Revoke command, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponses.wrap(grants.revoke(command, key));
    }
}
