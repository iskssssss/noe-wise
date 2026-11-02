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

/** 平台安全管理固定Tenant/integration的执行凭证；管理机器不会因目标参数获得执行身份。 */
@RestController
@RequestMapping("/api/v1/platform/institution-credentials")
public class PlatformInstitutionCredentialController {
    private final InstitutionIntegrationService institutions;
    /** 注入当前授权与一次秘密交付的统一服务。 */
    public PlatformInstitutionCredentialController(InstitutionIntegrationService institutions) { this.institutions = institutions; }
    /** 有权固定执行域内的安全目录，不包含API key或签名秘密。 */
    @GetMapping
    public ApiResponse<Page<ExecutionCredential>> list(@RequestParam("tenant_id") String tenant,
            @RequestParam("integration_id") String integration, @RequestParam(defaultValue="1") int page,
            @RequestParam(name="page_size",defaultValue="20") int pageSize) {
        return ApiResponses.wrap(institutions.credentials(tenant, integration, page, pageSize));
    }
    /** 显式签发与开通解耦，秘密只在第一次结果中出现。 */
    @PostMapping("/issue")
    public ApiResponse<IssuedExecutionCredential> issue(@RequestBody IssueExecutionCredential request,
            @RequestHeader("Idempotency-Key") String key) { return ApiResponses.wrap(institutions.issue(request, key)); }
    /** CAS轮换，同键重读安全回执而不重发秘密。 */
    @PostMapping("/rotate")
    public ApiResponse<IssuedExecutionCredential> rotate(@RequestBody ExecutionCredentialCommand request,
            @RequestHeader("Idempotency-Key") String key) { return ApiResponses.wrap(institutions.rotate(request, key)); }
    /** CAS撤销保留安全回执供原操作查证。 */
    @PostMapping("/revoke")
    public ApiResponse<ExecutionCredential> revoke(@RequestBody ExecutionCredentialCommand request,
            @RequestHeader("Idempotency-Key") String key) { return ApiResponses.wrap(institutions.revoke(request, key)); }
}
