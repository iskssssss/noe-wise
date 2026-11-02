package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.runtime.AgentRuntimeInboundGateway;
import com.skillforge.institution.service.SessionDeliveryStatusService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 查询已授权对话 Run 的持久投递摘要；查询本身不重发或创建通知。 */
@RestController
@RequestMapping("/api/v1/chat/runs")
public class ChatDeliveryController {

    private final SessionService sessions;
    private final AgentRuntimeInboundGateway runtime;
    private final SessionDeliveryStatusService deliveries;

    public ChatDeliveryController(
            SessionService sessions, AgentRuntimeInboundGateway runtime,
            SessionDeliveryStatusService deliveries) {
        this.sessions = sessions;
        this.runtime = runtime;
        this.deliveries = deliveries;
    }

    @Operation(summary = "查询对话结果投递状态",
            description = "先验证租户、会话和 Run 归属；SSE 的 replay_available 仅表示持久事件可重放")
    @GetMapping("/delivery")
    public ApiResponse<SessionDeliveryStatusService.DeliveryStatus> delivery(
            @RequestParam("session_id") @NotBlank String sessionId,
            @RequestParam("run_id") @NotBlank String runId) {
        String tenantId = TenantIds.requireTenantId();
        SessionView session = sessions.require(tenantId, sessionId);
        ChatResult runtimeStatus = runtime.chatRunStatus(session, runId);
        return ApiResponses.wrap(deliveries.status(tenantId, session.getSessionId(), runId, runtimeStatus.status()));
    }
}
