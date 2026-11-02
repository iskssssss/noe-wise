package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.TenantId;
import java.time.Instant;

/** 固定一次租约领取的租户、claim、worker、fencing token 与到期时间。 */
public record LeaseFence(
        TenantId tenantId,
        String claimId,
        String workerId,
        long fencingToken,
        Instant leaseUntil) {
    /** 拒绝缺失租约身份与非正 fencing token。 */
    public LeaseFence {
        tenantId = PortContracts.requireNonNull(tenantId, "tenantId");
        claimId = PortContracts.requireText(claimId, "claimId");
        workerId = PortContracts.requireText(workerId, "workerId");
        fencingToken = PortContracts.requirePositive(fencingToken, "fencingToken");
        leaseUntil = PortContracts.requireNonNull(leaseUntil, "leaseUntil");
    }
}
