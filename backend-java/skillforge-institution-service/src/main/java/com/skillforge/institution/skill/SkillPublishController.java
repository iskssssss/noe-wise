package com.skillforge.institution.skill;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/capabilities/skill-drafts/save-and-publish")
public class SkillPublishController {
    public static final String PRE_DISPATCH_REJECTED = "SKILL_PUBLISH_PRE_DISPATCH_REJECTED";
    private final SkillPublishService service;
    public SkillPublishController(SkillPublishService service) { this.service = service; }

    @PostMapping
    public ApiResponse<SkillPublishService.Receipt> publish(@RequestBody SkillPublishService.Command command) {
        return ApiResponses.wrap(service.publish(Long.parseLong(TenantIds.requireTenantId()), command));
    }

    /** 请求体无法反序列化发生在命令派发前；只映射此入口的明确拒绝。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException failure) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(
                PRE_DISPATCH_REJECTED, "技能发布请求格式或文件无效", false, java.util.Map.of()));
    }

    /** 204 表示回执不可见或尚未查到，不证明原操作失败。 */
    @RequirePermission("foundation.skills.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<SkillPublishService.Receipt>> result(@RequestParam("request_id") UUID requestId) {
        var receipt = service.result(Long.parseLong(TenantIds.requireTenantId()), requestId);
        return receipt == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(receipt));
    }
}
