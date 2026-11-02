package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.TenantId;
import java.time.Duration;

/** 请求按租户领取有限批次的待投影领域事件。 */
public record PublicationClaimRequest(
        TenantId tenantId, String workerId, int limit, Duration leaseDuration) {
    /** 拒绝缺失 worker、非正 limit 或非正租期。 */
    public PublicationClaimRequest {
        tenantId = PortContracts.requireNonNull(tenantId, "tenantId");
        workerId = PortContracts.requireText(workerId, "workerId");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        leaseDuration = PortContracts.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }
}
