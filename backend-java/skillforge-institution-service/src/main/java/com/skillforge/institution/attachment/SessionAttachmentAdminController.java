package com.skillforge.institution.attachment;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前机构的受权管理观察入口，租户归属不接受请求参数。 */
@RestController
@RequestMapping("/api/v1/tenant/storage/chat")
public class SessionAttachmentAdminController {
    private final SessionAttachmentCapacityService capacity;
    public SessionAttachmentAdminController(SessionAttachmentCapacityService capacity) { this.capacity = capacity; }
    @GetMapping("/capacity")
    public ApiResponse<SessionAttachmentBodyStore.TenantStorageBudget> capacity() {
        return ApiResponses.wrap(capacity.currentTenantCapacity());
    }
}
