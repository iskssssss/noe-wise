package com.skillforge.platform.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.agent.systemagent.PlatformAgentResources;
import com.skillforge.core.agent.systemagent.SystemAgentExecution;
import com.skillforge.core.agent.systemagent.SystemAgentService;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.*;
import com.skillforge.core.foundation.access.RequirePermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 平台系统智能体维护入口；机构 API 不暴露该配置域。 */
@RestController
@RequestMapping("/api/v1/admin/system-agents")
public class SystemAgentController {
    private final SystemAgentService agents;
    private final SystemAgentExecution execution;
    private final PlatformAgentResources resources;
    public SystemAgentController(SystemAgentService agents,SystemAgentExecution execution,PlatformAgentResources resources) {
        this.agents=agents; this.execution=execution; this.resources=resources;
    }
    @RequirePermission("foundation.system_agents.read")
    @GetMapping public ApiResponse<List<View>> list(){return ApiResponses.wrap(agents.list());}
    @RequirePermission("foundation.system_agents.read")
    @GetMapping("/detail") public ApiResponse<View> detail(@RequestParam String agentId){return ApiResponses.wrap(agents.detail(agentId));}
    @RequirePermission("foundation.system_agents.read")
    @GetMapping("/versions") public ApiResponse<List<Version>> versions(@RequestParam String agentId){return ApiResponses.wrap(agents.versions(agentId));}
    @RequirePermission("foundation.system_agents.read")
    @GetMapping("/runs") public ApiResponse<List<RunView>> runs(@RequestParam String agentId){return ApiResponses.wrap(agents.runs(agentId));}
    @RequirePermission("foundation.system_agents.read")
    @GetMapping("/debug-result") public ApiResponse<RunView> debugResult(@RequestParam String agentId,@RequestParam String idempotencyKey){return ApiResponses.wrap(agents.debugRun(agentId,idempotencyKey));}
    @RequirePermission("foundation.system_agents.read")
    @GetMapping("/mount-options") public ApiResponse<PlatformAgentResources.MountOptions> options(){return ApiResponses.wrap(resources.options());}
    @RequirePermission("foundation.system_agents.create")
    @PostMapping public ApiResponse<View> create(@Valid @RequestBody Create command){return ApiResponses.wrap(agents.create(command.agentKey(),command.name(),command.description(),command.executionMode(),actor()));}
    @RequirePermission("foundation.system_agents.update")
    @PutMapping("/update") public ApiResponse<View> update(@Valid @RequestBody Update command){return ApiResponses.wrap(agents.update(command.agentId(),command.expectedRevision(),command.config(),actor()));}
    @RequirePermission("foundation.system_agents.publish")
    @PostMapping("/publish") public ApiResponse<View> publish(@Valid @RequestBody Change command){return ApiResponses.wrap(change(command,"publish"));}
    @RequirePermission("foundation.system_agents.update")
    @PostMapping("/disable") public ApiResponse<View> disable(@Valid @RequestBody Change command){return ApiResponses.wrap(change(command,"disable"));}
    @RequirePermission("foundation.system_agents.update")
    @PostMapping("/enable") public ApiResponse<View> enable(@Valid @RequestBody Change command){return ApiResponses.wrap(change(command,"enable"));}
    @RequirePermission("foundation.system_agents.update")
    @PostMapping("/archive") public ApiResponse<View> archive(@Valid @RequestBody Change command){return ApiResponses.wrap(change(command,"archive"));}
    @RequirePermission("foundation.system_agents.delete")
    @DeleteMapping("/delete") public void delete(@Valid @RequestBody Change command){agents.delete(command.agentId(),command.expectedRevision(),actor());}
    @RequirePermission("foundation.system_agents.run")
    @PostMapping("/debug") public ApiResponse<RunView> debug(@Valid @RequestBody Debug command){return ApiResponses.wrap(execution.debug(command.agentId(),command.source(),command.expectedRevision(),command.idempotencyKey(),command.input(),actor()));}
    private View change(Change command,String action){return agents.transition(command.agentId(),command.expectedRevision(),action,actor());}
    private String actor(){return StpUtil.getLoginIdAsString();}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Create(@NotBlank String agentKey,@NotBlank String name,String description,@NotBlank String executionMode){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Update(@NotBlank String agentId,@Min(1) long expectedRevision,@NotNull Config config){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Change(@NotBlank String agentId,@Min(1) long expectedRevision){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Debug(@NotBlank String agentId,@NotBlank String source,@Min(1) long expectedRevision,@NotBlank String input,@NotBlank String idempotencyKey){}
}
