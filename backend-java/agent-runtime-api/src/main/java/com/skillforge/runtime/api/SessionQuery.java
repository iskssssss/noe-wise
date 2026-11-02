package com.skillforge.runtime.api;

/** 将完整访问身份与待读取的 SessionRef 绑定。 */
public record SessionQuery(AccessEnvelope access, SessionRef sessionRef) {

    /** 拒绝缺失访问上下文以及跨租户会话引用。 */
    public SessionQuery {
        access = ApiContracts.requireNonNull(access, "access");
        sessionRef = ApiContracts.requireNonNull(sessionRef, "sessionRef");
        if (!access.tenantId().equals(sessionRef.tenantId())) {
            throw new IllegalArgumentException("access and sessionRef tenantId must match");
        }
    }
}
