package com.skillforge.platform.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.agent.dto.response.*;
import com.skillforge.core.agent.dto.request.CreateAgentTemplateRequest;
import com.skillforge.core.agent.dto.query.AgentTemplateQuery;
import com.skillforge.core.agent.dto.request.UpdateAgentTemplateRequest;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.platform.service.AgentTemplateAdminService;
import com.skillforge.platform.service.AgentDefinitionImpactService;
import com.skillforge.core.agent.dto.response.AgentDefinitionImpactResponse;
import com.skillforge.core.agent.dto.request.AgentDefinitionCommands.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 平台定义的当前动作及精确草稿CAS入口；旧模板管理路径不保留。 */
@RestController
@RequestMapping("/api/v1/platform/agent-definitions")
public class PlatformAgentTemplateController {
    private final AgentTemplateAdminService definitions;
    private final AgentDefinitionImpactService impacts;
    public PlatformAgentTemplateController(AgentTemplateAdminService definitions,AgentDefinitionImpactService impacts) {
        this.definitions=definitions;this.impacts=impacts;
    }
    @GetMapping public ApiResponse<AdminAgentTemplateListResponse> listTemplates(@ModelAttribute AgentTemplateQuery query) {return ApiResponses.wrap(definitions.list(query));}
    @GetMapping("/detail") public ApiResponse<AdminAgentTemplateDetailResponse> getTemplate(@RequestParam("definition_id")Long id) {return ApiResponses.wrap(definitions.detail(id));}
    @GetMapping("/history") public ApiResponse<List<AdminAgentTemplateDetailResponse>> history(@RequestParam("definition_id")Long id) {return ApiResponses.wrap(definitions.history(id));}
    @GetMapping("/impact") public ApiResponse<AgentDefinitionImpactResponse> impact(@RequestParam("definition_id")Long id,@RequestParam("draft_revision")long revision) {
        return ApiResponses.wrap(impacts.impact(id,revision));
    }
    @PostMapping("/preview-content") public ApiResponse<AgentDefinitionImpactService.CandidatePreview> previewContent(@Valid @RequestBody PreviewContent command) {
        return ApiResponses.wrap(impacts.preview(command.definitionId(),command.expectedRevision(),command.definition()));
    }
    @PostMapping("/preview") public ApiResponse<AgentDefinitionImpactService.CandidatePreview> preview(@Valid @RequestBody DefinitionCommand command) {
        return ApiResponses.wrap(impacts.previewSaved(command.definitionId(),command.expectedRevision()));
    }
    @PostMapping public ApiResponse<AdminAgentTemplateItemResponse> createTemplate(@Valid @RequestBody CreateAgentTemplateRequest command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(definitions.create(command,key));
    }
    @PutMapping("/update") public ApiResponse<AdminAgentTemplateItemResponse> updateTemplateEndpoint(@Valid @RequestBody UpdateDefinition command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(definitions.update(command.definitionId(),command.expectedRevision(),command.payload(),key));
    }
    @PostMapping("/validate") public ApiResponse<AgentTemplateValidationResponse> validateTemplate(@Valid @RequestBody DefinitionCommand command) {
        return ApiResponses.wrap(definitions.validate(command.definitionId(),command.expectedRevision()));
    }
    @PostMapping("/publish") public ApiResponse<AdminAgentTemplateItemResponse> publishTemplateEndpoint(@Valid @RequestBody DefinitionCommand command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(definitions.publish(command.definitionId(),command.expectedRevision(),key));
    }
    @PostMapping("/deprecate") public ApiResponse<AdminAgentTemplateItemResponse> deprecateTemplateEndpoint(@Valid @RequestBody DefinitionCommand command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(definitions.deprecate(command.definitionId(),command.expectedRevision(),key));
    }
    @PostMapping("/restore") public ApiResponse<AdminAgentTemplateItemResponse> restore(@Valid @RequestBody RestoreDefinition command,@RequestHeader("Idempotency-Key")String key) {
        return ApiResponses.wrap(definitions.restore(command.definitionId(),command.expectedRevision(),command.publishedRevision(),key));
    }
}
