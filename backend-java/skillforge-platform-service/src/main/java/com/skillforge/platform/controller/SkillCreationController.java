package com.skillforge.platform.controller;

import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.SkillCreationService;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/capabilities/skill-drafts/create-with-receipt")
public class SkillCreationController {
    private final SkillCreationService service;
    public SkillCreationController(SkillCreationService service) { this.service = service; }
    @PostMapping
    public ApiResponse<SkillCreationService.Result> create(@RequestBody SkillCreationService.Command command) {
        try { return ApiResponses.wrap(service.create(CapabilityCatalogScope.platform(), command)); }
        catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408 || "SKILL_CREATE_IDENTITY_CONFLICT".equals(failure.getCode())) throw failure;
            throw new ApiException(failure.getStatus(), "SKILL_CREATE_REJECTED", String.valueOf(failure.getDetail()), false);
        }
    }
    @RequirePermission("foundation.skills.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<SkillCreationService.Result>> result(@RequestParam("request_id") UUID requestId) {
        var result = service.result(CapabilityCatalogScope.platform(), requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }
}
