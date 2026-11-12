package com.skillforge.institution.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.service.AgentRevisionRestoreService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agents/restore-revision")
public class AgentRevisionRestoreController {
    private final AgentRevisionRestoreService restores;
    public AgentRevisionRestoreController(AgentRevisionRestoreService restores) { this.restores=restores; }
    @PostMapping
    @RequirePermission("foundation.agents.update")
    public ApiResponse<AgentRevisionRestoreService.Result> restore(@Valid @RequestBody Command command) {
        return ApiResponses.wrap(restores.restore(command.agentId(),command.revision(),command.requestId()));
    }
    @GetMapping("/result")
    @RequirePermission("foundation.agents.read")
    public ResponseEntity<ApiResponse<AgentRevisionRestoreService.Result>> result(@RequestParam String agentId,@RequestParam UUID requestId) {
        var result=restores.result(agentId,requestId);
        return result==null?ResponseEntity.noContent().build():ResponseEntity.ok(ApiResponses.wrap(result));
    }
    public record Command(@JsonProperty("agent_id") @NotNull @Pattern(regexp="[1-9][0-9]*") String agentId,
            @Positive int revision,@JsonProperty("request_id") @NotNull UUID requestId) {}
}
