package com.skillforge.institution.channel;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/channels/onboarding")
public class ChannelOnboardingController {
    private final ChannelOnboardingService service;
    public ChannelOnboardingController(ChannelOnboardingService service) { this.service = service; }

    @PostMapping
    public ApiResponse<ChannelOnboardingService.Result> create(@RequestBody ChannelOnboardingService.Command command) {
        return ApiResponses.wrap(service.create(Long.parseLong(TenantIds.requireTenantId()), command));
    }

    /** 不可见或尚未提交均返回 204，不表示创建失败。 */
    @RequirePermission("foundation.channels.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<ChannelOnboardingService.Result>> result(@RequestParam("request_id") UUID requestId) {
        var result = service.result(Long.parseLong(TenantIds.requireTenantId()), requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }
}
