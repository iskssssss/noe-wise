package com.skillforge.platform.controller;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.foundation.integration.ManagementContracts;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台人员专属的管理机器身份入口；服务层仍验证当前账号、权限与目标范围。 */
@RestController
@RequestMapping("/api/v1/platform/management-principals")
public class PlatformManagementPrincipalController {
    private final PlatformManagementService management;
    /** 只依赖公开应用服务，不从 Controller 写凭据表。 */
    public PlatformManagementPrincipalController(PlatformManagementService management) { this.management = management; }

    /** 当前人员数据范围内的安全目录，不包含密钥摘要或密文。 */
    @RequirePermission("platform.management_principals.read")
    @GetMapping
    public ApiResponse<ManagementContracts.Page> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponses.wrap(management.list(page, pageSize));
    }

    /** 明确固定 scope/targets，一次明文交付由服务端持久安全回执约束。 */
    @RequirePermission("platform.management_principals.create")
    @PostMapping
    public ApiResponse<ManagementContracts.Issued> create(@RequestBody ManagementContracts.Create request,
            @RequestHeader("Idempotency-Key") String key) { return ApiResponses.wrap(management.create(request, key)); }

    /** 带 expected_revision 轮换，同键重读只有原安全版本，不返回第二份秘密。 */
    @RequirePermission("platform.management_principals.rotate")
    @PostMapping("/rotate")
    public ApiResponse<ManagementContracts.Issued> rotate(@RequestBody ManagementContracts.Command request,
            @RequestHeader("Idempotency-Key") String key) { return ApiResponses.wrap(management.rotate(request, key)); }

    /** 撤销终态保留安全回执，当前 Key 随事务提交失效。 */
    @RequirePermission("platform.management_principals.revoke")
    @PostMapping("/revoke")
    public ApiResponse<ManagementContracts.Issued> revoke(@RequestBody ManagementContracts.Command request,
            @RequestHeader("Idempotency-Key") String key) { return ApiResponses.wrap(management.revoke(request, key)); }
}
