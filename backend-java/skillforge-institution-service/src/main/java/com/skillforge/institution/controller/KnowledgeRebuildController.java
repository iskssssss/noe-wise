package com.skillforge.institution.controller;
import com.skillforge.core.knowledge.KnowledgeRebuildService;
import com.skillforge.institution.service.KnowledgeManagementService;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
/** 管理员显式全库重建；租户身份来自已认证上下文，调用方不能传tenant。 */
@RestController @RequestMapping("/api/v1/knowledge/bases/rebuilds")
public class KnowledgeRebuildController {
 private final KnowledgeRebuildService rebuilds;
 private final KnowledgeManagementService management;
 public KnowledgeRebuildController(KnowledgeRebuildService rebuilds, KnowledgeManagementService management){this.rebuilds=rebuilds;this.management=management;}
 @Operation(summary="重建完整知识库",description="冻结全部未删除文档当前来源；旧版本继续可查，准备完成后仍需手动发布")
 @RequirePermission("foundation.knowledge.create")
    @PostMapping("/create") public ApiResponse<KnowledgeRebuildService.Snapshot> create(@Valid @RequestBody Create request){
  management.requireBase(TenantIds.requireTenantId(),request.kbId(),"manage"); return ApiResponses.wrap(rebuilds.create(TenantIds.requireTenantId(),request.kbId(),request.targetModelId(),request.targetModelRevision(),request.idempotencyKey(),request.expectedBaseRevision()));}
 @RequirePermission("foundation.knowledge.read")
    @GetMapping("/detail") public ApiResponse<KnowledgeRebuildService.Snapshot> latest(@RequestParam long kbId){management.requireBase(TenantIds.requireTenantId(),kbId,"read"); return ApiResponses.wrap(rebuilds.latest(TenantIds.requireTenantId(),kbId));}
 @RequirePermission("foundation.knowledge.cancel")
    @PostMapping("/cancel") public ApiResponse<KnowledgeRebuildService.Snapshot> cancel(@Valid @RequestBody Command request){management.requireBase(TenantIds.requireTenantId(),request.kbId(),"manage"); return ApiResponses.wrap(rebuilds.cancel(TenantIds.requireTenantId(),request.kbId(),request.jobId(),request.expectedRevision()));}
 @Operation(summary="显式重试重建",description="成功批次不重算；未知结果批次须确认可能重复费用后才能重算；源集合变化拒绝重试")
 @RequirePermission("foundation.knowledge.run")
    @PostMapping("/retry") public ApiResponse<KnowledgeRebuildService.Snapshot> retry(@Valid @RequestBody Retry request){management.requireBase(TenantIds.requireTenantId(),request.kbId(),"manage"); return ApiResponses.wrap(rebuilds.retry(TenantIds.requireTenantId(),request.kbId(),request.jobId(),request.expectedRevision(),request.confirmUnknownCost()));}
 @Operation(summary="发布并切换重建版本",description="原子发布Catalog和知识库revision并切换文档指针及默认Embedding身份；必须显式提交调用治理策略")
 @RequirePermission("foundation.knowledge.publish")
    @PostMapping("/publish") public ApiResponse<KnowledgeRebuildService.Snapshot> publish(@Valid @RequestBody Publish request){management.requireBase(TenantIds.requireTenantId(),request.kbId(),"manage"); return ApiResponses.wrap(rebuilds.publish(TenantIds.requireTenantId(),request.kbId(),request.jobId(),request.expectedRevision(),request.expectedBaseRevision(),request.policy()));}
 public record Create(@Positive long kbId,@NotBlank String targetModelId,@Positive long targetModelRevision,@NotBlank @Size(max=128) String idempotencyKey,@NotNull @Min(0) Long expectedBaseRevision){}
 public record Command(@Positive long kbId,@Positive long jobId,@Positive long expectedRevision){}
 public record Retry(@Positive long kbId,@Positive long jobId,@Positive long expectedRevision,boolean confirmUnknownCost){}
 public record Publish(@Positive long kbId,@Positive long jobId,@Positive long expectedRevision,@NotNull @Min(0) Long expectedBaseRevision,@NotNull @Valid KnowledgeCapabilityPolicyRequest policy){}
}
