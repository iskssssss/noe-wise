package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.memory.MemoryGovernanceService;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryScope;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 机构管理员的最小 Memory 查询与治理 API。 */
@RestController
@RequestMapping("/api/v1/memories")
public final class MemoryGovernanceController {
    private final MemoryGovernanceService service;

    public MemoryGovernanceController(MemoryGovernanceService service) {
        this.service = service;
    }

    @RequirePermission("foundation.memory.read")
    @GetMapping
    @Operation(summary = "查询当前 Memory 事实")
    public ApiResponse<List<MemoryFact>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String type,
            @RequestParam(name = "agent_id", required = false) String agentId,
            @RequestParam(name = "end_user_id", required = false) Long endUserId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return ApiResponses.wrap(service.list(TenantIds.requireTenantId(),
                new MemoryGovernanceService.ListQuery(status, scope, type, agentId, endUserId, limit)));
    }

    @RequirePermission("foundation.memory.read")
    @GetMapping("/page")
    @Operation(summary = "分页查询当前 Memory 事实")
    public ApiResponse<MemoryGovernanceService.Page> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String type,
            @RequestParam(name = "agent_id", required = false) String agentId,
            @RequestParam(name = "end_user_id", required = false) Long endUserId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return ApiResponses.wrap(service.page(TenantIds.requireTenantId(),
                new MemoryGovernanceService.ListQuery(status, scope, type, agentId, endUserId, limit), q, cursor));
    }

    @RequirePermission("foundation.memory.read")
    @GetMapping("/reviews")
    @Operation(summary = "查询待审核 Memory")
    public ApiResponse<List<MemoryFact>> reviews(
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return ApiResponses.wrap(service.list(TenantIds.requireTenantId(), FactStatus.PENDING_CONFIRM.name(), limit));
    }

    @RequirePermission("foundation.memory.update")
    @PostMapping("/review")
    @Operation(summary = "审核 Memory 候选")
    public ApiResponse<MemoryFact> reviewEndpoint(
            @Valid @RequestBody ReviewCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponses.wrap(review(command.factId(), command.payload(), key));
    }

    public MemoryFact review(String factId, @Valid ReviewRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.review(TenantIds.requireTenantId(), factId, request.expectedRevision(),
                request.approved(), request.confirmedScope(), key);
    }

    @RequirePermission("foundation.memory.update")
    @PostMapping("/correct")
    @Operation(summary = "纠正并取代 Memory 事实")
    public ApiResponse<MemoryFact> correctEndpoint(
            @Valid @RequestBody CorrectCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponses.wrap(correct(command.factId(), command.payload(), key));
    }

    public MemoryFact correct(String factId, @Valid CorrectionRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.correct(TenantIds.requireTenantId(), factId, request.expectedRevision(),
                request.subject(), request.predicate(), request.objectJson(),
                request.confidence(), request.importance(), key);
    }

    @RequirePermission("foundation.memory.update")
    @PostMapping("/forget")
    @Operation(summary = "遗忘 Memory 事实")
    public ApiResponse<MemoryFact> forgetEndpoint(
            @Valid @RequestBody ForgetCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponses.wrap(forget(command.factId(), command.payload(), key));
    }

    public MemoryFact forget(String factId, @Valid RevisionRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.transition(TenantIds.requireTenantId(), factId, request.expectedRevision(),
                FactStatus.FORGOTTEN, "memory_forgotten_by_admin", key);
    }

    @RequirePermission("foundation.memory.update")
    @PostMapping("/restore")
    @Operation(summary = "恢复已遗忘或衰减 Memory")
    public ApiResponse<MemoryFact> restoreEndpoint(
            @Valid @RequestBody RestoreCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ApiResponses.wrap(restore(command.factId(), command.payload(), key));
    }

    public MemoryFact restore(String factId, @Valid RevisionRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.transition(TenantIds.requireTenantId(), factId, request.expectedRevision(),
                FactStatus.ACTIVE, "memory_restored_by_admin", key);
    }

    /**
     * 审核结论与明确确认的共享范围；批准 USER 必须显式传 USER，拒绝或助手级审核可省略。
     * @param expectedRevision 正在审核的事实版本
     * @param approved 是否批准候选
     * @param confirmedScope 操作者明确确认的目标范围，不从模型 scope 自动填充
     */
    public record ReviewRequest(@Positive long expectedRevision, boolean approved,
            @JsonProperty("confirmed_scope") MemoryScope confirmedScope) { }

    public record RevisionRequest(@Positive long expectedRevision) { }

    public record CorrectionRequest(
            @Positive long expectedRevision,
            String subject,
            String predicate,
            @NotBlank String objectJson,
            @Min(0) @Max(1) double confidence,
            @Min(0) @Max(1) double importance) { }

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record ReviewCommand(
            @JsonProperty("fact_id") @NotNull String factId,
            @JsonProperty("payload") @NotNull @Valid ReviewRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record CorrectCommand(
            @JsonProperty("fact_id") @NotNull String factId,
            @JsonProperty("payload") @NotNull @Valid CorrectionRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record ForgetCommand(
            @JsonProperty("fact_id") @NotNull String factId,
            @JsonProperty("payload") @NotNull @Valid RevisionRequest payload) {}

    /** HTTP 写操作请求：标识与业务数据统一由请求体承载。 */
    public record RestoreCommand(
            @JsonProperty("fact_id") @NotNull String factId,
            @JsonProperty("payload") @NotNull @Valid RevisionRequest payload) {}
}
