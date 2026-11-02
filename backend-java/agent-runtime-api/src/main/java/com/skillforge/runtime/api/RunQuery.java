package com.skillforge.runtime.api;

/** 将完整访问身份与待读取的持久 RunRef 绑定。 */
public record RunQuery(AccessEnvelope access, RunRef runRef) {

    /** 拒绝缺失访问上下文以及跨租户运行引用。 */
    public RunQuery {
        access = ApiContracts.requireNonNull(access, "access");
        runRef = ApiContracts.requireNonNull(runRef, "runRef");
        if (!access.tenantId().equals(runRef.tenantId())) {
            throw new IllegalArgumentException("access and runRef tenantId must match");
        }
    }
}
