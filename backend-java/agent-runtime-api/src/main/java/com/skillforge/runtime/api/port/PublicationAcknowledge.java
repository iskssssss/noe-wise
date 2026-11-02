package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;

/** 确认指定 fence 已把领域事件投影为持久 Session 事实。 */
public record PublicationAcknowledge(
        TenantId tenantId,
        String sourceKind,
        String sourceId,
        EventId eventId,
        LeaseFence fence,
        long expectedPublicationRevision,
        ResultRef projectionRef) {
    /** 拒绝不完整身份、跨租户 fence 或非正预期 revision。 */
    public PublicationAcknowledge {
        tenantId = PortContracts.requireNonNull(tenantId, "tenantId");
        sourceKind = PortContracts.requireSourceKind(sourceKind);
        sourceId = PortContracts.requireText(sourceId, "sourceId");
        eventId = PortContracts.requireNonNull(eventId, "eventId");
        fence = PortContracts.requireNonNull(fence, "fence");
        expectedPublicationRevision = PortContracts.requirePositive(
                expectedPublicationRevision, "expectedPublicationRevision");
        projectionRef = PortContracts.requireNonNull(projectionRef, "projectionRef");
        PortContracts.requireSameTenant(tenantId, fence.tenantId(), "fence.tenantId");
    }
}
