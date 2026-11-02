package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.tasks.ConversationTaskManagement;
import com.skillforge.runtime.api.port.ConversationTask;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.skillforge.common.context.RequestContext;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 机构端任务管理；继续执行仍由 Chat 的受信用户入口重新鉴权。 */
@RestController
@RequestMapping("/api/v1/conversation-tasks")
public class ConversationTaskController {
    private final ConversationTaskManagement service;
    public ConversationTaskController(ConversationTaskManagement service) { this.service = service; }
    @RequirePermission("foundation.sessions.read")
    @GetMapping("/list")
    @Operation(summary = "查询当前会话所属用户与助手的跨会话任务")
    public ApiResponse<List<TaskView>> list(@RequestParam("session_id") String session,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(name = "include_closed", defaultValue = "false") boolean closed,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
        return ApiResponses.wrap(service.list(service.owner(TenantIds.requireTenantId(), session), query, closed, offset, limit).stream().map(TaskView::from).toList());
    }
    @RequirePermission("foundation.sessions.read")
    @GetMapping("/detail")
    @Operation(summary = "查看任务进展")
    public ApiResponse<TaskView> detail(@RequestParam("session_id") String session, @RequestParam("task_id") String task) {
        return ApiResponses.wrap(TaskView.from(service.detail(service.owner(TenantIds.requireTenantId(), session), task)));
    }
    @RequirePermission("foundation.sessions.read")
    @GetMapping("/receipt")
    @Operation(summary = "只读核对原任务操作结果")
    public ApiResponse<Receipt> receipt(@RequestParam("session_id") String session,
            @RequestParam("task_id") String task, @RequestParam("operation_id") String operationId) {
        var result = service.receipt(service.owner(TenantIds.requireTenantId(), session), task, operationId, RequestContext.getUserId());
        return ApiResponses.wrap(result.map(value -> new Receipt("SUCCEEDED", TaskView.from(value)))
                .orElseGet(() -> new Receipt("UNKNOWN", null)));
    }
    @RequirePermission("foundation.sessions.update")
    @PostMapping("/update")
    @Operation(summary = "纠正或改变任务生命周期")
    public ApiResponse<TaskView> update(@Valid @RequestBody Update command) {
        return ApiResponses.wrap(TaskView.from(service.update(service.owner(TenantIds.requireTenantId(), command.sessionId()), command.taskId(),
                command.expectedRevision(), command.operation(), command.text(), command.operationId(), RequestContext.getUserId())));
    }
    @RequirePermission("foundation.sessions.update")
    @PostMapping("/select")
    @Operation(summary = "选择当前会话要继续的任务")
    public ApiResponse<TaskView> select(@Valid @RequestBody Selection command) {
        return ApiResponses.wrap(TaskView.from(service.update(service.owner(TenantIds.requireTenantId(), command.sessionId()), command.taskId(),
                command.expectedRevision(), "select", null, command.operationId(), RequestContext.getUserId())));
    }
    /** 浏览器身份始终使用字符串，避免 Snowflake 用户 ID 精度损失；不公开内部来源会话全文。 */
    public record TaskView(String taskId, Id tenantId, Id endUserId, Id agentId, long revision,
            ConversationTask.Status status, String title, String summary,
            com.skillforge.runtime.api.TypedPayload state, List<ConversationTask.Source> sources, java.time.Instant updatedAt) {
        static TaskView from(ConversationTask task) {
            return new TaskView(task.taskId(), new Id(task.tenantId().value()), new Id(Long.toString(task.endUserId().value())),
                    new Id(task.agentId().value()), task.revision(), task.status(), task.title(), task.summary(),
                    task.state(), task.sources(), task.updatedAt());
        }
    }
    public record Id(String value) { }
    public record Receipt(String status, TaskView task) { }
    public record Update(@JsonProperty("session_id") @NotBlank String sessionId,
            @JsonProperty("task_id") @NotBlank String taskId,
            @JsonProperty("expected_revision") @Positive long expectedRevision,
            @Pattern(regexp = "correct|complete|cancel|reopen") @NotBlank String operation,
            @Size(max = 4000) String text, @JsonProperty("operation_id") @NotBlank @Size(max = 200) String operationId) { }
    public record Selection(@JsonProperty("session_id") @NotBlank String sessionId,
            @JsonProperty("task_id") @NotBlank String taskId,
            @JsonProperty("expected_revision") @Positive long expectedRevision,
            @JsonProperty("operation_id") @NotBlank @Size(max = 200) String operationId) { }
}
