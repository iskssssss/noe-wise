package com.skillforge.runtime.api;

/** 以租户和会话 ID 共同定位一个 Session。 */
public record SessionRef(TenantId tenantId, String sessionId) {

    /** 固定非空租户身份并拒绝空白会话 ID。 */
    public SessionRef {
        tenantId = ApiContracts.requireNonNull(tenantId, "tenantId");
        sessionId = ApiContracts.requireText(sessionId, "sessionId");
    }
}
