package com.skillforge.runtime.api;

/** 表示租户内受治理 MemoryFact 的稳定身份。 */
public record MemoryFactRef(
        TenantId tenantId,
        String factId) {

    /** 拒绝缺失租户或空白事实身份。 */
    public MemoryFactRef {
        tenantId = ApiContracts.requireNonNull(tenantId, "tenantId");
        factId = ApiContracts.requireText(factId, "factId");
    }
}
