package com.skillforge.institution.controller;

import com.skillforge.core.agent.deployment.AgentTemplateGrantService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static com.skillforge.core.agent.deployment.AgentDeploymentContracts.*;

/** 当前机构原授/逐级转授；来源消费许可不授予源维护或目标业务管理权限。 */
@RestController
@RequestMapping("/api/v1/tenant/agent-grants")
public class InstitutionAgentGrantController {
    private final AgentTemplateGrantService grants;
    public InstitutionAgentGrantController(AgentTemplateGrantService grants){this.grants=grants;}
    @GetMapping
    public ApiResponse<Grants> list(){return ApiResponses.wrap(grants.tenantList());}
    @PostMapping
    public ApiResponse<GrantView> grant(@Valid @RequestBody Grant command,@RequestHeader("Idempotency-Key")String key){return ApiResponses.wrap(grants.tenantGrant(command,key));}
    @PostMapping("/narrow")
    public ApiResponse<GrantView> narrow(@Valid @RequestBody Narrow command,@RequestHeader("Idempotency-Key")String key){return ApiResponses.wrap(grants.tenantNarrow(command,key));}
    @PostMapping("/revoke")
    public ApiResponse<GrantView> revoke(@Valid @RequestBody Revoke command,@RequestHeader("Idempotency-Key")String key){return ApiResponses.wrap(grants.tenantRevoke(command,key));}
}
