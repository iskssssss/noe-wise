package com.skillforge.institution.team;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams/save-and-publish")
public class TeamPublishController {
    public static final String PRE_DISPATCH_REJECTED = "TEAM_PUBLISH_PRE_DISPATCH_REJECTED";
    private final TeamPublishService service;
    public TeamPublishController(TeamPublishService service) { this.service = service; }

    @PostMapping
    public ApiResponse<TeamPublishService.Receipt> publish(@RequestBody TeamPublishService.Command command) {
        return ApiResponses.wrap(service.publish(Long.parseLong(TenantIds.requireTenantId()), command));
    }

    /** 204 仅表示原回执不可见或尚未查到，不证明操作失败。 */
    @RequirePermission("foundation.teams.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<TeamPublishService.Receipt>> result(@RequestParam UUID requestId) {
        var receipt = service.result(Long.parseLong(TenantIds.requireTenantId()), requestId);
        return receipt == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(receipt));
    }
}
