package com.skillforge.institution.controller;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.knowledge.KnowledgeRebuildPublishReceiptService;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.service.KnowledgeManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 只核对或封存原重建发布请求，不从任务当前状态推断请求结果。 */
@RestController
@RequestMapping("/api/v1/knowledge/bases/rebuilds")
public class KnowledgeRebuildPublishReceiptController {
    private final FoundationAccess access;
    private final KnowledgeManagementService management;
    private final KnowledgeRebuildPublishReceiptService receipts;

    public KnowledgeRebuildPublishReceiptController(FoundationAccess access, KnowledgeManagementService management,
            KnowledgeRebuildPublishReceiptService receipts) {
        this.access=access; this.management=management; this.receipts=receipts;
    }

    @RequirePermission("foundation.knowledge.publish")
    @PostMapping("/publish-with-receipt")
    public ApiResponse<KnowledgeRebuildPublishReceiptService.Resolution> publish(@Valid @RequestBody Publish command) {
        String tenant=TenantIds.requireTenantId(); String actor=actor(tenant);
        management.requireBase(tenant,command.kbId(),"manage");
        return ApiResponses.wrap(receipts.publish(tenant,actor,command.toCommand()));
    }

    @RequirePermission("foundation.knowledge.read")
    @PostMapping("/publish-fence")
    public ApiResponse<KnowledgeRebuildPublishReceiptService.Resolution> fence(@Valid @RequestBody Publish command) {
        String tenant=TenantIds.requireTenantId(); String actor=actor(tenant);
        management.requireBase(tenant,command.kbId(),"read");
        return ApiResponses.wrap(receipts.fence(tenant,actor,command.toCommand()));
    }

    @RequirePermission("foundation.knowledge.read")
    @GetMapping("/publish-resolution")
    public ApiResponse<KnowledgeRebuildPublishReceiptService.Resolution> resolution(
            @RequestParam("kb_id") long kbId, @RequestParam("job_id") long jobId, @RequestParam("request_id") UUID requestId) {
        String tenant=TenantIds.requireTenantId(); String actor=actor(tenant);
        management.requireBase(tenant,kbId,"read");
        return ApiResponses.wrap(receipts.resolve(tenant,actor,kbId,jobId,requestId));
    }

    private String actor(String tenant) {
        // 该管理路径尚无 ServicePrincipal scope 与资源授权契约，不能仅凭外部认证放行。
        if (ServicePrincipalContext.get()!=null) throw forbidden();
        var subject=access.currentSubject();
        if (subject.platform() || !tenant.equals(subject.tenantId())) throw forbidden();
        return "tenant:"+tenant+":member:"+subject.memberId();
    }
    private static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN,"机构身份与当前租户不匹配"); }

    public record Publish(@NotNull UUID requestId, @Positive long kbId, @Positive long jobId,
            @Positive long expectedRevision, @NotNull @Min(0) Long expectedBaseRevision,
            @NotNull @Valid KnowledgeCapabilityPolicyRequest policy) {
        KnowledgeRebuildPublishReceiptService.Command toCommand() {
            return new KnowledgeRebuildPublishReceiptService.Command(requestId,kbId,jobId,expectedRevision,expectedBaseRevision,policy);
        }
    }
}
