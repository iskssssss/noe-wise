package com.skillforge.runtime.api;

/** 引用宿主已经保存的可信入口来源；Runtime 不解释其业务结构。 */
public record RunOriginRef(TenantId tenantId, String originId) {
    public RunOriginRef {
        tenantId = ApiContracts.requireNonNull(tenantId, "tenantId");
        originId = ApiContracts.requireText(originId, "originId");
    }
}
