package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.TenantId;

/** 表示一次 Agent 委派的租户内稳定身份。 */
public record DelegationRef(TenantId tenantId, String delegationId) {
    /** 拒绝缺失租户或空白委派身份。 */
    public DelegationRef {
        tenantId = PortContracts.requireNonNull(tenantId, "tenantId");
        delegationId = PortContracts.requireText(delegationId, "delegationId");
    }
}
