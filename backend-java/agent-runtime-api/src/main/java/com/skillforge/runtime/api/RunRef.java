package com.skillforge.runtime.api;

/** 以租户和运行 ID 共同定位一个 AgentRun。 */
public record RunRef(TenantId tenantId, String runId) {

    /** 固定非空租户身份并拒绝空白运行 ID。 */
    public RunRef {
        tenantId = ApiContracts.requireNonNull(tenantId, "tenantId");
        runId = ApiContracts.requireText(runId, "runId");
    }
}
