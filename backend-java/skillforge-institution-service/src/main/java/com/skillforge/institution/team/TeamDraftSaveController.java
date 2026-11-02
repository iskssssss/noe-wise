package com.skillforge.institution.team;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/teams/save-with-receipt")
public class TeamDraftSaveController {
    private final TeamDraftSaveService service;
    public TeamDraftSaveController(TeamDraftSaveService service) { this.service = service; }
    @PostMapping
    public ApiResponse<TeamDraftSaveService.Receipt> save(@RequestBody TeamDraftSaveService.Command command) {
        try { return ApiResponses.wrap(service.save(Long.parseLong(TenantIds.requireTenantId()), command)); }
        catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408) throw failure;
            throw new ApiException(failure.getStatus(), "TEAM_DRAFT_SAVE_REJECTED", String.valueOf(failure.getDetail()), false);
        }
    }
    @RequirePermission("foundation.teams.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<TeamDraftSaveService.Receipt>> result(@RequestParam("request_id") UUID requestId) {
        var result = service.result(Long.parseLong(TenantIds.requireTenantId()), requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }
}
