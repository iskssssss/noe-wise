package com.skillforge.institution.controller;

import com.skillforge.core.agent.deployment.AgentTemplateGrantService;
import com.skillforge.core.agent.dto.response.AgentTemplateListResponse;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.service.AgentDeploymentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static com.skillforge.core.agent.deployment.AgentDeploymentContracts.*;

/** 本机构JWT与固定Tenant/integration的SP入口；当前授权由部署服务统一检查。 */
@RestController
@RequestMapping(value={"/api/v1/tenant/agent-deployments","/api/v1/open-platform/agent-deployments"},produces="application/json")
public class AgentDeploymentController {
    private final AgentDeploymentService deployments;
    private final AgentTemplateGrantService grants;
    public AgentDeploymentController(AgentDeploymentService deployments,AgentTemplateGrantService grants) {this.deployments=deployments;this.grants=grants;}
    /** 目录过滤当前机构及外部执行域。 */
    @GetMapping public ApiResponse<Listing> list(@RequestParam(value="tenant_id",required=false)String tenant) {
        return ApiResponses.wrap(tenant==null?deployments.list():deployments.listTarget(id(tenant)));
    }
    @GetMapping("/detail") public ApiResponse<View> detail(@RequestParam("deployment_id")String deployment,
            @RequestParam(value="tenant_id",required=false)String tenant) {
        return ApiResponses.wrap(tenant==null?deployments.detail(id(deployment)):deployments.detailTarget(id(tenant),id(deployment)));
    }
    /** 只展示当前机构获准消费的发布，不经过开放安装目录。 */
    @GetMapping("/definitions") public ApiResponse<AgentTemplateListResponse> definitions(@RequestParam(value="tenant_id",required=false)String tenant) {
        if(tenant!=null)return ApiResponses.wrap(deployments.definitionsTarget(id(tenant)));
        deployments.requireDirectoryAccess();return ApiResponses.wrap(grants.available(Long.parseLong(TenantIds.requireTenantId())));
    }
    /** 授权撤销状态可读回，实例创建仍须当前有效依据。 */
    @GetMapping("/grants") public ApiResponse<Grants> grants(@RequestParam(value="tenant_id",required=false)String tenant) {
        if(tenant!=null)return ApiResponses.wrap(deployments.grantsTarget(id(tenant)));
        deployments.requireDirectoryAccess();return ApiResponses.wrap(grants.targetGrants(Long.parseLong(TenantIds.requireTenantId())));
    }
    @PostMapping public ApiResponse<View> deploy(@Valid @RequestBody Deploy command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(deployments.deploy(command,key));
    }
    @PutMapping("/bindings") public ApiResponse<View> bind(@Valid @RequestBody Bindings command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(deployments.bind(command,key));
    }
    @PostMapping("/authorization") public ApiResponse<View> switchAuthorization(@Valid @RequestBody SwitchAuthorization command,
            @RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(deployments.switchAuthorization(command,key));
    }
    private static long id(String value) {
        try {if(value==null||!value.matches("[1-9][0-9]*"))throw new NumberFormatException();return Long.parseLong(value);}
        catch(NumberFormatException invalid){throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"标识必须是正十进制字符串");}
    }
}
