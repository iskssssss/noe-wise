package com.skillforge.platform.controller;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/system-agents/create-with-receipt")
public class SystemAgentCreationController {
    private final SystemAgentCreationService service;
    public SystemAgentCreationController(SystemAgentCreationService service) { this.service = service; }
    @PostMapping
    public ApiResponse<SystemAgentCreationService.Result> create(@RequestBody SystemAgentCreationService.Command command) {
        try { return ApiResponses.wrap(service.create(command)); }
        catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408) throw failure;
            throw new ApiException(failure.getStatus(), "SYSTEM_AGENT_CREATE_REJECTED", String.valueOf(failure.getDetail()), false);
        }
    }
    @RequirePermission("foundation.system_agents.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<SystemAgentCreationService.Result>> result(@RequestParam("request_id") UUID requestId) {
        var result = service.result(requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }
}
