package com.skillforge.institution.team;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已保存团队修订发布及原请求回执。 */
@RestController
@RequestMapping("/api/v1/teams/publish-with-receipt")
public class TeamSavedPublishController {
    private final TeamPublishService service;
    public TeamSavedPublishController(TeamPublishService service) { this.service=service; }
    @PostMapping
    public ApiResponse<TeamPublishService.Receipt> publish(@RequestBody TeamPublishService.SavedCommand command) {
        return ApiResponses.wrap(service.publishSaved(Long.parseLong(TenantIds.requireTenantId()),command));
    }
}
