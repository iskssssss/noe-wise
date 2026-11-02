package com.skillforge.institution.controller;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.knowledge.KnowledgeUploadReceiptService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.service.KnowledgeManagementService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 知识文件上传的原请求回执入口。 */
@RestController
@RequestMapping("/api/v1/knowledge/bases/documents/upload")
public class KnowledgeUploadController {
    private final FoundationAccess access;
    private final KnowledgeManagementService management;
    private final KnowledgeUploadReceiptService uploads;

    public KnowledgeUploadController(FoundationAccess access, KnowledgeManagementService management,
            KnowledgeUploadReceiptService uploads) {
        this.access = access;
        this.management = management;
        this.uploads = uploads;
    }

    @PostMapping(consumes = "multipart/form-data")
    @Operation(summary = "上传知识文档", description = "使用请求 ID 登记文件上传；相同请求返回原状态，成功回执与文档共同提交。")
    public ApiResponse<KnowledgeUploadReceiptService.Receipt> upload(@RequestParam("kb_id") long kbId,
            @RequestParam("request_id") UUID requestId, @RequestPart("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title) {
        String tenant = TenantIds.requireTenantId();
        FoundationAccess.Subject subject;
        try {
            access.require("foundation.knowledge.create");
            subject = access.currentSubject();
            if (subject.platform() || !tenant.equals(subject.tenantId()))
                throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
            management.requireBase(tenant, kbId, "manage");
        } catch (ApiException failure) {
            throw new ApiException(failure.getStatus(), "KNOWLEDGE_UPLOAD_REJECTED", String.valueOf(failure.getDetail()), false);
        }
        return ApiResponses.wrap(uploads.upload(tenant, kbId, subject.memberId(), requestId, title, file));
    }

    /** 204 仅表示当前无可见回执，不能证明原请求结束。 */
    @GetMapping("/result")
    @Operation(summary = "查询知识文档上传回执", description = "按原请求 ID 查询；无回执不代表请求失败。")
    public ResponseEntity<ApiResponse<KnowledgeUploadReceiptService.Receipt>> result(
            @RequestParam("kb_id") long kbId, @RequestParam("request_id") UUID requestId) {
        String tenant = TenantIds.requireTenantId();
        access.require("foundation.knowledge.read");
        var subject = access.currentSubject();
        if (subject.platform() || !tenant.equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        management.requireBase(tenant, kbId, "read");
        var receipt = uploads.result(tenant, kbId, subject.memberId(), requestId);
        return receipt == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(receipt));
    }
}
