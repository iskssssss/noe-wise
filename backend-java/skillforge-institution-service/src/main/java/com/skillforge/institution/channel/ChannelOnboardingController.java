package com.skillforge.institution.channel;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    /** 请求尚未进入创建服务，允许修正输入后重新提交，不产生待核对操作。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("CHANNEL_ONBOARDING_REJECTED",
                "渠道参数无效，请检查接入类型等输入后重新保存", false, java.util.Map.of()));
    }

    /** 不可见或尚未提交均返回 204，不表示创建失败。 */
    @RequirePermission("foundation.channels.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<ChannelOnboardingService.Result>> result(@RequestParam("request_id") UUID requestId) {
        var result = service.result(Long.parseLong(TenantIds.requireTenantId()), requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }
}
