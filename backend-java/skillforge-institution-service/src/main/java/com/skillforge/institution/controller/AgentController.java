package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.core.shared.exception.ApiException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.agent.dto.request.*;
import com.skillforge.core.agent.dto.response.*;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.dto.resource.DeleteResponse;
import com.skillforge.institution.service.AgentManagementService;
import com.skillforge.institution.service.AgentTemplateInstallationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 机构 Agent HTTP 边界；配置、安装、发布的事务由应用服务拥有。 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {
    private final AgentManagementService agents;
    private final AgentTemplateInstallationService installations;
    public AgentController(AgentManagementService agents, AgentTemplateInstallationService installations) {
        this.agents = agents; this.installations = installations;
    }
    @RequirePermission("foundation.agents.read")
    @GetMapping("/templates")
    public ApiResponse<AgentTemplateListResponse> listTemplates() { return ApiResponses.wrap(agents.listTemplates()); }
    @RequirePermission("foundation.agents.read")
    @GetMapping("/templates/detail")
    public ApiResponse<AgentTemplateItemResponse> templateDetail(@RequestParam("templateId") Long templateId) { return ApiResponses.wrap(agents.templateDetail(templateId)); }
    @RequirePermission("foundation.agents.read")
    @GetMapping
    public ApiResponse<AgentListResponse> listAgents() { return ApiResponses.wrap(agents.listAgents()); }
    @RequirePermission("foundation.agents.read")
    @GetMapping("/detail")
    public ApiResponse<AgentDetailResponse> getAgent(@RequestParam("agentId") String agentId) { return ApiResponses.wrap(agents.getAgent(agentId)); }
    @RequirePermission("foundation.agents.read")
    @GetMapping("/revisions")
    public ApiResponse<AgentRevisionListResponse> revisions(@RequestParam("agentId") String agentId) { return ApiResponses.wrap(agents.revisions(agentId)); }
    @RequirePermission("foundation.agents.read")
    @GetMapping("/revisions/detail")
    public ApiResponse<AgentDetailResponse> revisionDetail(@RequestParam("agentId") String agentId,
            @RequestParam("revision") int revision) { return ApiResponses.wrap(agents.revisionDetail(agentId, revision)); }
    @RequirePermission("foundation.agents.create")
    @PostMapping
    public ApiResponse<AgentItemResponse> createAgent(@Valid @RequestBody CreateAgentRequest request) { return ApiResponses.wrap(agents.createAgent(request)); }
    @RequirePermission("foundation.agents.create")
    @PostMapping("/install-template")
    public ApiResponse<AgentTemplateInstallationResponse> install(@Valid @RequestBody InstallAgentTemplateRequest request) {
        try { return ApiResponses.wrap(installations.install(request)); }
        catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408 || failure.getStatus().value() == 409) throw failure;
            throw new ApiException(failure.getStatus(), "AGENT_INSTALL_REJECTED", String.valueOf(failure.getDetail()), false);
        }
    }
    @RequirePermission("foundation.agents.read")
    @GetMapping("/install-template/result")
    public ResponseEntity<ApiResponse<AgentTemplateInstallationService.InstallationReceipt>> installationResult(@RequestParam("request_id") String requestId) {
        var result = installations.result(requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }
    @RequirePermission("foundation.agents.update")
    @PatchMapping("/update")
    public ApiResponse<AgentItemResponse> updateAgentEndpoint(@Valid @RequestBody UpdateAgentCommand command) { return ApiResponses.wrap(agents.updateAgent(command.agentId(), command.payload())); }
    @RequirePermission("foundation.agents.update")
    @PostMapping("/save-and-publish")
    public ApiResponse<AgentSaveAndPublishResponse> saveAndPublish(
            @Valid @RequestBody SaveAndPublishCommand command) {
        return ApiResponses.wrap(agents.saveAndPublish(command.agentId(), command.requestId(), command.payload()));
    }
    /** 无回执只代表暂未查到，不能作为原写入确定失败的依据。 */
    @RequirePermission("foundation.agents.read")
    @GetMapping("/save-and-publish/result")
    public ResponseEntity<ApiResponse<AgentSaveAndPublishResponse>> saveAndPublishResult(
            @RequestParam("agentId") String agentId, @RequestParam("requestId") UUID requestId) {
        var receipt = agents.saveAndPublishResult(agentId, requestId);
        return receipt == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(receipt));
    }
    @RequirePermission("foundation.agents.delete")
    @DeleteMapping("/delete")
    public ApiResponse<DeleteResponse> deleteAgentEndpoint(@Valid @RequestBody DeleteAgentCommand command) { return ApiResponses.wrap(agents.deleteAgent(command.agentId())); }
    @RequirePermission("foundation.agents.publish")
    @PostMapping("/publish")
    public ApiResponse<AgentPublishResponse> publishEndpoint(@Valid @RequestBody PublishCommand command) { return ApiResponses.wrap(agents.publish(command.agentId())); }
    @RequirePermission("foundation.agents.create")
    @PostMapping("/revise")
    public ApiResponse<AgentItemResponse> reviseEndpoint(@Valid @RequestBody PublishCommand command) { return ApiResponses.wrap(agents.revise(command.agentId())); }
    @RequirePermission("foundation.agents.update")
    @PostMapping("/restore-revision")
    public ApiResponse<AgentItemResponse> restoreRevision(@Valid @RequestBody RestoreRevisionCommand command) {
        return ApiResponses.wrap(agents.restoreRevision(command.agentId(), command.revision()));
    }
    public record SaveAndPublishCommand(
            @JsonProperty("agent_id") @NotNull String agentId,
            @JsonProperty("request_id") @NotNull UUID requestId,
            @NotNull @Valid UpdateAgentRequest payload) { }
    public record UpdateAgentCommand(@JsonProperty("agent_id") @NotNull String agentId, @NotNull @Valid UpdateAgentRequest payload) { }
    public record DeleteAgentCommand(@JsonProperty("agent_id") @NotNull String agentId) { }
    public record PublishCommand(@JsonProperty("agent_id") @NotNull String agentId) { }
    public record RestoreRevisionCommand(@JsonProperty("agent_id") @NotNull String agentId,
            @jakarta.validation.constraints.Min(1) int revision) { }
}
