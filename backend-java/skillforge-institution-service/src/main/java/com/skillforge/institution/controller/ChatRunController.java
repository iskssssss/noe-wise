package com.skillforge.institution.controller;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse.Collaboration;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.runtime.AgentRuntimeInboundGateway;
import com.skillforge.institution.runtime.AgentRuntimeRunControls;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

/** 页面运行控制与 SSE 连接生命周期分离，取消和查询均验证租户会话归属。 */
@RestController
@RequestMapping("/api/v1/chat/runs")
public class ChatRunController {
    private final SessionService sessions;
    private final AgentRuntimeInboundGateway runtime;
    private final AgentRuntimeRunControls controls;

    public ChatRunController(SessionService sessions, AgentRuntimeInboundGateway runtime, AgentRuntimeRunControls controls) {
        this.sessions = sessions;
        this.runtime = runtime;
        this.controls = controls;
    }

    @Operation(summary = "取消对话运行", description = "持久化取消意图；cancelling 表示已请求，cancelled 表示本轮已停止调度。已执行工具的成功、失败或未知结果独立保留，取消不撤销外部效果")
    @PostMapping("/cancel")
@RequirePermission("foundation.sessions.cancel")
    public ApiResponse<ChatResult> cancel(@Valid @RequestBody CancelRequest request) {
        return ApiResponses.wrap(controls.cancelChat(sessions.requireWritable(TenantIds.requireTenantId(), request.sessionId()), request.runId()));
    }

    @Operation(summary = "查询对话运行状态", description = "只返回当前租户指定会话的运行状态，不恢复或重发请求")
    @GetMapping("/status")
@RequirePermission("foundation.sessions.read")
    public ApiResponse<ChatResult> status(@RequestParam("session_id") String sessionId, @RequestParam("run_id") String runId) {
        return ApiResponses.wrap(controls.chatRunStatus(sessions.require(TenantIds.requireTenantId(), sessionId), runId));
    }

    @Operation(summary = "读取对话协作状态", description = "返回当前 Plan 模式选择与 Todo 整表快照")
    @GetMapping("/collaboration")
@RequirePermission("foundation.sessions.read")
    public ApiResponse<Collaboration> collaboration(@RequestParam("session_id") String sessionId) {
        return ApiResponses.wrap(runtime.collaboration(
                sessions.require(TenantIds.requireTenantId(), sessionId)));
    }

    @Operation(summary = "选择 Plan 模式", description = "活动运行中选择在下一次已接受准备时生效")
    @PostMapping("/plan-mode")
@RequirePermission("foundation.sessions.update")
    public ApiResponse<Collaboration> selectPlan(@Valid @RequestBody PlanModeRequest request) {
        return ApiResponses.wrap(runtime.selectPlan(
                sessions.requireWritable(TenantIds.requireTenantId(), request.sessionId()),
                request.commandId(), request.active()));
    }

    @Operation(summary = "补充运行中指引", description = "持久保存用户纠正；PENDING、INCLUDED、CONSUMED 分别表示等待、进入冻结请求和模型结果已消费")
    @PostMapping("/steer")
@RequirePermission("foundation.sessions.run")
    public ApiResponse<SteeringResponse> steer(
            @Valid @RequestBody SteeringRequest request) {
        return ApiResponses.wrap(SteeringResponse.from(controls.steerChat(sessions.requireWritable(TenantIds.requireTenantId(), request.sessionId()), request.runId(), request.commandId(), request.text())));
    }

    @Operation(summary = "查询运行指引消费状态", description = "终态保留未消费内容，查询不创建新运行")
    @GetMapping("/steering")
@RequirePermission("foundation.sessions.read")
    public ApiResponse<SteeringResponse> steering(
            @RequestParam("session_id") String sessionId, @RequestParam("run_id") String runId) {
        return ApiResponses.wrap(SteeringResponse.from(controls.steeringChat(sessions.require(TenantIds.requireTenantId(), sessionId), runId)));
    }

    public record SteeringItemResponse(@JsonProperty("command_id") String commandId, String text, String status) { }
    public record SteeringResponse(@JsonProperty("run_id") String runId, boolean accepting, java.util.List<SteeringItemResponse> items) {
        static SteeringResponse from(com.skillforge.institution.runtime.AgentRuntimeExecutionSteering.Snapshot value) {
            return new SteeringResponse(value.runId(), value.accepting(), value.items().stream()
                    .map(item -> new SteeringItemResponse(item.commandId(), item.text(), item.status().name())).toList());
        }
    }

    public record SteeringRequest(@JsonProperty("session_id") @NotBlank String sessionId,
            @JsonProperty("run_id") @NotBlank String runId,
            @JsonProperty("command_id") @NotBlank @jakarta.validation.constraints.Size(max = 200) String commandId,
            @NotBlank @jakarta.validation.constraints.Size(max = 8192) String text) { }

    public record CancelRequest(@JsonProperty("session_id") @NotBlank String sessionId,
                                @JsonProperty("run_id") @NotBlank String runId) {}

    public record PlanModeRequest(
            @JsonProperty("session_id") @NotBlank String sessionId,
            @JsonProperty("command_id") @NotBlank String commandId,
            @JsonProperty("active") @NotNull Boolean active) {}
}
