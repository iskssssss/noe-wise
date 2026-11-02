package com.skillforge.institution.attachment;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 机构正文累计观察仅授予当前机构成员的授权全范围，渠道及集成主体不借用此后台能力。 */
@Service
public class SessionAttachmentCapacityService {
    private final FoundationAccess access;
    private final SessionAttachmentBodyStore bodies;
    public SessionAttachmentCapacityService(FoundationAccess access, SessionAttachmentBodyStore bodies) {
        this.access = access;
        this.bodies = bodies;
    }

    /** 不接收租户参数；当前成员、当前配额读取权限和全数据范围都重新由 FoundationAccess 校验。 */
    @Transactional(readOnly = true)
    public SessionAttachmentBodyStore.TenantStorageBudget currentTenantCapacity() {
        if (ChannelContext.getChannelId() != null || ServicePrincipalContext.get() != null) throw denied();
        var actor = access.currentSubject();
        if (actor.platform() || !actor.tenantId().equals(TenantContext.getTenantId())) throw denied();
        access.require(actor, "foundation.entitlements.read");
        if (!access.dataScope(actor).all()) throw denied();
        return bodies.inspectTenant(actor.tenantId());
    }

    private static ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN, "chat_storage_capacity_scope_denied", "需要当前机构的配额读取权限及全部数据范围", false);
    }
}
