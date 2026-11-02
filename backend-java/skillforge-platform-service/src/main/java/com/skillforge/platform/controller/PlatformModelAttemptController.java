package com.skillforge.platform.controller;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.platform.sessionmodelattempt.ModelAttemptDetail;
import com.skillforge.platform.sessionmodelattempt.ModelAttemptDiagnosticAuthorizer;
import com.skillforge.platform.sessionmodelattempt.ModelAttemptDiagnosticService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员调用详情独立入口；不改变公开消息或 trace 内容策略。 */
@RestController
public class PlatformModelAttemptController {
    private final ModelAttemptDiagnosticAuthorizer authorizer;
    private final ModelAttemptDiagnosticService diagnostics;

    /** 注入专用授权器与只读诊断服务。 */
    public PlatformModelAttemptController(ModelAttemptDiagnosticAuthorizer authorizer,
            ModelAttemptDiagnosticService diagnostics) {
        this.authorizer = authorizer;
        this.diagnostics = diagnostics;
    }

    /** 根输入可省略以诊断独立辅助调用；如果提供，服务必须精确核对归属。 */
    @RequirePermission("foundation.sessions.read")
    @GetMapping("/api/v1/platform/sessions/model-attempt/detail")
    public ResponseEntity<ApiResponse<ModelAttemptDetail>> detail(
            @RequestParam("tenant_id") String tenantId,
            @RequestParam("session_id") String sessionId,
            @RequestParam("attempt_id") String attemptId,
            @RequestParam(name = "root_input_id", required = false) String rootInputId,
            @RequestParam(name = "parent_session_id", required = false) String parentSessionId,
            @RequestParam(name = "parent_root_input_id", required = false) String parentRootInputId) {
        var scope = authorizer.platformAdmin();
        long tenant;
        try {
            tenant = Long.parseLong(tenantId);
            if (tenant <= 0) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "tenant_id 必须是正整数");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponses.wrap(
                diagnostics.detail(scope, tenant, sessionId, rootInputId, attemptId,
                        parentSessionId, parentRootInputId)));
    }
}
