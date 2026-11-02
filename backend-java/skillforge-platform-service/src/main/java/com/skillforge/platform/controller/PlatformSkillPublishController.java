package com.skillforge.platform.controller;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/capabilities/skill-drafts/save-and-publish")
public class PlatformSkillPublishController {
    public static final String PRE_DISPATCH_REJECTED = "SKILL_PUBLISH_PRE_DISPATCH_REJECTED";
    private final PlatformSkillPublishService service;
    public PlatformSkillPublishController(PlatformSkillPublishService service) { this.service = service; }

    @PostMapping
    public ApiResponse<PlatformSkillPublishService.Receipt> publish(@RequestBody PlatformSkillPublishService.Command command) {
        return ApiResponses.wrap(service.publish(command));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException failure) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(
                PRE_DISPATCH_REJECTED, "技能发布请求格式或文件无效", false, Map.of()));
    }

    @RequirePermission("foundation.skills.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<PlatformSkillPublishService.Receipt>> result(@RequestParam("request_id") UUID requestId) {
        var receipt = service.result(requestId);
        return receipt == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(receipt));
    }
}
