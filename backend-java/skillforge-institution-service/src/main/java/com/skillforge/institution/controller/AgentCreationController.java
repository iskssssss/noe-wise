package com.skillforge.institution.controller;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.service.AgentCreationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agents/create-with-receipt")
public class AgentCreationController {
    private final AgentCreationService service;
    public AgentCreationController(AgentCreationService service) { this.service = service; }
    @PostMapping
    public ApiResponse<AgentCreationService.Result> create(@Valid @RequestBody AgentCreationService.Command command) {
        try { return ApiResponses.wrap(service.create(command)); }
        catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408 || "AGENT_CREATE_IDENTITY_CONFLICT".equals(failure.getCode())) throw failure;
            throw new ApiException(failure.getStatus(), "AGENT_CREATE_REJECTED", String.valueOf(failure.getDetail()), false);
        }
    }
    @RequirePermission("foundation.agents.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<AgentCreationService.Result>> result(@RequestParam("request_id") UUID requestId) {
        var result = service.result(requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }
}
