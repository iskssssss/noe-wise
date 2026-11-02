package com.skillforge.institution.controller;

import com.skillforge.core.chat.access.OpenPlatformSessionAccessService;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.runtime.AgentRuntimeExecutionGateway;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 丢失消息受理响应后的原操作查询，不提交消息或触发执行。 */
@RestController
@RequestMapping(value = "/api/v1/open-platform/sessions/messages", produces = "application/json")
public final class OpenPlatformMessageReceiptController {
    private final OpenPlatformSessionAccessService access;
    private final AgentRuntimeExecutionGateway runtime;

    public OpenPlatformMessageReceiptController(OpenPlatformSessionAccessService access, AgentRuntimeExecutionGateway runtime) {
        this.access = access; this.runtime = runtime;
    }

    @GetMapping("/receipt")
    public ApiResponse<Observation> receipt(@RequestParam("sessionId") String sessionId,
            @Parameter(schema = @Schema(maxLength = 256)) @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var authorized = access.require("sessions.read");
        if (sessionId.isBlank() || idempotencyKey.isBlank() || idempotencyKey.length() > 256)
            throw new ApiException(HttpStatus.BAD_REQUEST, "sessionId 和原 Idempotency-Key 无效");
        var session = access.session(authorized, sessionId);
        String inputId = OpenPlatformInputIds.create(authorized.principal(), session.getSessionId(), "message", idempotencyKey);
        var receipt = runtime.findOpenPlatformMessageReceipt(authorized.principal(), session, inputId);
        return ApiResponses.wrap(receipt.map(value -> new Observation(State.FOUND, value))
                .orElseGet(() -> new Observation(State.NOT_OBSERVED, null)));
    }

    public enum State { FOUND, NOT_OBSERVED }

    /** NOT_OBSERVED 不能证明未发送，不能清除 UNKNOWN 或允许重新提交。FOUND 只证明受理。 */
    public record Observation(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) State observation,
            @Schema(nullable = true, description = "FOUND 为原 accepted 回执；NOT_OBSERVED 时为空，不授予重试权")
            OpenPlatformInboundReceiptResponse receipt) { }
}
