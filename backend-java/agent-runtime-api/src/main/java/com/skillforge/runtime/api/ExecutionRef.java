package com.skillforge.runtime.api;

/** 表示 Workflow Execution 的租户内稳定身份。 */
public record ExecutionRef(TenantId tenantId, String executionId) {

    /** 拒绝缺失租户或空白执行身份。 */
    public ExecutionRef {
        tenantId = ApiContracts.requireNonNull(tenantId, "tenantId");
        executionId = ApiContracts.requireText(executionId, "executionId");
    }
}
