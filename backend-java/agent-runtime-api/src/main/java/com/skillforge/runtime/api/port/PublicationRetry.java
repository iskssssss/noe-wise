package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.TenantId;
import java.time.Instant;

/** 在匹配 fence 与 revision 时重新安排领域事件投影。 */
public record PublicationRetry(
        TenantId tenantId,
        String sourceKind,
        String sourceId,
        EventId eventId,
        LeaseFence fence,
        long expectedPublicationRevision,
        Instant availableAt,
        PortFailure failure) {
    /** 拒绝不完整身份、跨租户 fence、非正 revision 或缺失失败事实。 */
    public PublicationRetry {
        tenantId = PortContracts.requireNonNull(tenantId, "tenantId");
        sourceKind = PortContracts.requireSourceKind(sourceKind);
        sourceId = PortContracts.requireText(sourceId, "sourceId");
        eventId = PortContracts.requireNonNull(eventId, "eventId");
        fence = PortContracts.requireNonNull(fence, "fence");
        expectedPublicationRevision = PortContracts.requirePositive(
                expectedPublicationRevision, "expectedPublicationRevision");
        availableAt = PortContracts.requireNonNull(availableAt, "availableAt");
        failure = PortContracts.requireNonNull(failure, "failure");
        PortContracts.requireSameTenant(tenantId, fence.tenantId(), "fence.tenantId");
    }
}
