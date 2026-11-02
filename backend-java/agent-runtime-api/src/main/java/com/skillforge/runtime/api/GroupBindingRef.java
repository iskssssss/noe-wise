package com.skillforge.runtime.api;

/**
 * 精确机构内的群生命周期引用，不携带外部群号、成员或服务 Agent。
 * @param tenantId 群所属机构，不能借群 ID 跨机构使用
 * @param groupBindingId 内部不透明生命周期 ID；关闭后不得复用此引用建立新群
 */
public record GroupBindingRef(TenantId tenantId, String groupBindingId) {
    /** 拒绝没有机构或没有内部生命周期身份的群引用。 */
    public GroupBindingRef {
        tenantId = ApiContracts.requireNonNull(tenantId, "tenantId");
        groupBindingId = ApiContracts.requireText(groupBindingId, "groupBindingId");
    }
}
