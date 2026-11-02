package com.skillforge.platform.controller;

import com.skillforge.core.agent.deployment.AgentTemplateGrantService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static com.skillforge.core.agent.deployment.AgentDeploymentContracts.*;

/** 平台当前动作/目标授权在服务中检查；旧应用授权路径不保留。 */
@RestController
@RequestMapping("/api/v1/platform/agent-grants")
public class AgentTemplateGrantController {
    private final AgentTemplateGrantService grants;
    public AgentTemplateGrantController(AgentTemplateGrantService grants) { this.grants=grants; }
    /** 目标机构范围由当前管理权限决定。 */
    @GetMapping
    public ApiResponse<Grants> list(@RequestParam("target_tenant_id")long tenant) { return ApiResponses.wrap(grants.list(tenant)); }
    /** 稳定幂等键绑定完整直授内容，不修改定义分发策略。 */
    @PostMapping
    public ApiResponse<GrantView> grant(@Valid @RequestBody Grant command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(grants.grant(command,key));
    }
    @PostMapping("/receipt/query")
    public ApiResponse<Receipt<GrantView>> receipt(@Valid @RequestBody Grant command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(grants.grantReceipt(command,key));
    }
    /** 只缩小原授权边界；不改变目标、源和父链。 */
    @PostMapping("/narrow")
    public ApiResponse<GrantView> narrow(@Valid @RequestBody Narrow command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(grants.narrow(command,key));
    }
    /** CAS撤销保留可重读的原授权及撤销回执。 */
    @PostMapping("/revoke")
    public ApiResponse<GrantView> revoke(@Valid @RequestBody Revoke command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(grants.revoke(command,key));
    }
}
