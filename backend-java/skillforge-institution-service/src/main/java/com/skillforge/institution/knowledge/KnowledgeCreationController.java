package com.skillforge.institution.knowledge;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge/bases/create-with-receipt")
public class KnowledgeCreationController {
    private final KnowledgeCreationService service;
    public KnowledgeCreationController(KnowledgeCreationService service) { this.service = service; }
    @PostMapping
    public ApiResponse<KnowledgeCreationService.Result> create(@RequestBody KnowledgeCreationService.Command command) {
        try { return ApiResponses.wrap(service.create(Long.parseLong(TenantIds.requireTenantId()), command)); }
        catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408) throw failure;
            throw new ApiException(failure.getStatus(), "KNOWLEDGE_WRITE_REJECTED", String.valueOf(failure.getDetail()), false);
        }
    }
    /** 204 表示尚不可见，不证明原请求失败。 */
    @RequirePermission("foundation.knowledge.read")
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<KnowledgeCreationService.Result>> result(@RequestParam("request_id") UUID requestId) {
        var result = service.result(Long.parseLong(TenantIds.requireTenantId()), requestId);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }
}
