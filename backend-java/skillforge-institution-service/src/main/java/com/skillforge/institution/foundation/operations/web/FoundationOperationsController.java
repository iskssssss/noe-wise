package com.skillforge.institution.foundation.operations.web;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.operations.FoundationAuditService;
import com.skillforge.core.foundation.operations.FoundationRetentionService;
import com.skillforge.core.foundation.operations.FoundationOperationsStatus;
import com.skillforge.core.foundation.operations.OperationsContracts.*;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 机构安全审计、保留策略与真实运维入口；服务层统一执行当前授权和作用域校验。 */
@RestController
@RequestMapping("/api/v1/tenant/foundation/operations")
public class FoundationOperationsController {
    private final FoundationAuditService audits;
    private final FoundationRetentionService retention;
    private final FoundationOperationsStatus status;
    private final FoundationAccess access;

    /** 注入公开应用服务。 */
    public FoundationOperationsController(FoundationAuditService audits, FoundationRetentionService retention,
            FoundationOperationsStatus status, FoundationAccess access) {
        this.audits = audits; this.retention = retention; this.status = status; this.access = access;
    }

    /** 查询本账号体系审计，分页和过滤越界在服务层明确拒绝。 */
    @GetMapping("/audit")
    public ApiResponse<AuditPage> audit(
            @RequestParam(required=false) String action, @RequestParam(name="actor_id", required=false) String actorId,
            @RequestParam(required=false) String category, @RequestParam(required=false) String outcome,
            @RequestParam(name="resource_type", required=false) String resourceType,
            @RequestParam(name="trace_id", required=false) String traceId,
            @RequestParam(required=false) Instant from, @RequestParam(required=false) Instant to,
            @RequestParam(required=false) Integer page, @RequestParam(name="page_size", required=false) Integer pageSize) {
        audience();
        return ApiResponses.wrap(audits.query(new AuditQuery(action,actorId,category,outcome,resourceType,traceId,from,to,page,pageSize)));
    }

    /** 查询审计目录，授权与分页范围由同一应用服务执行。 */
    @GetMapping("/audit/summaries")
    public ApiResponse<AuditSummaryPage> summaries(
            @RequestParam(required=false) String action, @RequestParam(name="actor_id", required=false) String actorId,
            @RequestParam(required=false) String category, @RequestParam(required=false) String outcome,
            @RequestParam(name="resource_type", required=false) String resourceType,
            @RequestParam(name="trace_id", required=false) String traceId,
            @RequestParam(required=false) Instant from, @RequestParam(required=false) Instant to,
            @RequestParam(required=false) Integer page, @RequestParam(name="page_size", required=false) Integer pageSize) {
        audience();
        return ApiResponses.wrap(audits.summaries(new AuditQuery(action,actorId,category,outcome,resourceType,traceId,from,to,page,pageSize)));
    }

    /** 二进制CSV出口保留附件头，不能包装为JSON。 */
    @PostMapping("/audit/export")
    public ResponseEntity<byte[]> export(@RequestBody AuditQuery query) {
        audience();
        AuditExport file = audits.export(query);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .header("X-Export-Rows", Integer.toString(file.rows())).body(file.content());
    }

    /** 查看当前审计保留策略。 */
    @GetMapping("/retention") public ApiResponse<RetentionPolicy> policy() { audience(); return ApiResponses.wrap(retention.policy()); }

    /** 全量替换策略，CAS版本必填。 */
    @PutMapping("/retention") public ApiResponse<RetentionPolicy> update(@Valid @RequestBody PolicyUpdate body) {
        audience(); return ApiResponses.wrap(retention.update(body.retentionDays(),body.enabled(),body.expectedVersion()));
    }

    /** 冻结清理候选，返回保护数量和15分钟期限。 */
    @PostMapping("/retention/preview") public ApiResponse<CleanupRun> preview() { audience(); return ApiResponses.wrap(retention.preview()); }

    /** 仅执行服务端冻结预览，不接纳任意SQL或ID列表。 */
    @PostMapping("/retention/execute") public ApiResponse<CleanupRun> execute(@Valid @RequestBody Identifier body) { audience(); return ApiResponses.wrap(retention.execute(body.id())); }

    /** 查看已登记的执行回执。 */
    @GetMapping("/retention/runs") public ApiResponse<List<CleanupRun>> runs() { audience(); return ApiResponses.wrap(retention.runs()); }

    /** 查看具体审计保护清单。 */
    @GetMapping("/retention/holds") public ApiResponse<List<AuditHold>> holds() { audience(); return ApiResponses.wrap(retention.holds()); }

    /** 具体事实的调查引用保护。 */
    @PostMapping("/retention/holds") public ApiResponse<AuditHold> hold(@Valid @RequestBody HoldCreate body) { audience(); return ApiResponses.wrap(retention.hold(body.auditId(),body.reason(),body.expiresAt())); }

    /** 显式解除具体保护。 */
    @PostMapping("/retention/holds/release") public ApiResponse<Void> release(@Valid @RequestBody Identifier body) { audience(); retention.release(body.id()); return ApiResponses.wrap((Void)null); }

    /** 真实依赖探测，down与unavailable保留原始含义。 */
    @GetMapping("/status") public ApiResponse<RuntimeStatus> status() { audience(); return ApiResponses.wrap(status.status()); }

    /** 端点账号体系不可由跨体系JWT混用。 */
    private void audience() {
        if (access.currentSubject().platform() != false)
            throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "operations.audience_mismatch", "账号体系不匹配", false);
    }

    /** @param retentionDays 完整天数 @param enabled 是否启用 @param expectedVersion 读取到的CAS版本 */
    public record PolicyUpdate(@NotNull Integer retentionDays, @NotNull Boolean enabled, @NotNull Long expectedVersion) { }
    /** @param id 服务端登记的预览或保护ID */
    public record Identifier(@NotBlank String id) { }
    /** @param auditId 本作用域具体事实 @param reason 无凭据的调查依据 @param expiresAt 可选截止时间 */
    public record HoldCreate(@NotBlank String auditId, @NotBlank String reason, Instant expiresAt) { }
}
