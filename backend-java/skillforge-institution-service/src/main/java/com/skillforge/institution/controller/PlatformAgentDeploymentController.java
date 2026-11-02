package com.skillforge.institution.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.agent.deployment.AgentTemplateGrantService;
import com.skillforge.core.agent.deployment.AgentDeploymentContracts;
import com.skillforge.core.agent.dto.response.AgentTemplateListResponse;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.service.AgentDeploymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;
import static com.skillforge.core.agent.deployment.AgentDeploymentContracts.*;

/** 平台代部署复用机构唯一部署服务；网关仅将此精确管理路径路由到机构宿主。 */
@RestController
@RequestMapping("/api/v1/platform/agent-deployments")
public class PlatformAgentDeploymentController {
    private final AgentDeploymentService deployments;
    private final AgentTemplateGrantService grants;
    private final PlatformManagementService management;
    public PlatformAgentDeploymentController(AgentDeploymentService deployments,AgentTemplateGrantService grants,PlatformManagementService management) {
        this.deployments=deployments;this.grants=grants;this.management=management;
    }
    @GetMapping public ApiResponse<Listing> list(@RequestParam("tenant_id")long tenant) {return ApiResponses.wrap(deployments.list(tenant));}
    @GetMapping("/detail") public ApiResponse<View> detail(@RequestParam("tenant_id")long tenant,@RequestParam("deployment_id")long id) {
        return ApiResponses.wrap(deployments.detail(tenant,id));
    }
    @GetMapping("/definitions") public ApiResponse<AgentTemplateListResponse> definitions(@RequestParam("tenant_id")long tenant) {
        management.requireTenant("platform.agent_deployments.read",tenant);return ApiResponses.wrap(grants.available(tenant));
    }
    @GetMapping("/grants") public ApiResponse<Grants> grants(@RequestParam("tenant_id")long tenant) {
        management.requireTenant("platform.agent_deployments.read",tenant);return ApiResponses.wrap(grants.targetGrants(tenant));
    }
    @PostMapping public ApiResponse<View> deploy(@Valid @RequestBody TargetDeployment command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(deployments.deploy(command.tenantId(),command.payload(),key));
    }
    @PostMapping("/receipt/query") public ApiResponse<Receipt<View>> receipt(@Valid @RequestBody TargetDeployment command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(deployments.deployReceipt(command.tenantId(),command.payload(),key));
    }
    @PutMapping("/bindings") public ApiResponse<View> bind(@Valid @RequestBody TargetBindings command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(deployments.bind(command.tenantId(),command.payload(),key));
    }
    @PostMapping("/authorization") public ApiResponse<View> switchAuthorization(@Valid @RequestBody TargetAuthorization command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(deployments.switchAuthorization(command.tenantId(),command.payload(),key));
    }
    /** 平台明确目标与所属部署DTO分离，目标不形成执行身份。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TargetDeployment(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @NotNull @Positive Long tenantId,@NotNull @Valid AgentDeploymentContracts.Deploy payload) { }
    /** 平台绑定仍要求目标动作、部署CAS和本地资源合法。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TargetBindings(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @NotNull @Positive Long tenantId,@NotNull @Valid AgentDeploymentContracts.Bindings payload) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TargetAuthorization(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.DecimalStringIdDeserializer.class) @NotNull @Positive Long tenantId,@NotNull @Valid AgentDeploymentContracts.SwitchAuthorization payload) { }
}
