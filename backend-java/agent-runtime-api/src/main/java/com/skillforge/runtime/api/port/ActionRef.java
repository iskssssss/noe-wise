package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.TenantId;

/** 表示一次逻辑 Action 的租户内稳定身份。 */
public record ActionRef(TenantId tenantId, String actionId) {
    /** 拒绝缺失租户或空白动作身份。 */
    public ActionRef {
        tenantId = PortContracts.requireNonNull(tenantId, "tenantId");
        actionId = PortContracts.requireText(actionId, "actionId");
    }
}
