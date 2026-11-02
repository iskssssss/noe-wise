package com.skillforge.runtime.api;

/** 表示 façade 在认证或授权阶段 fail closed，并只公开稳定原因与安全审计引用。 */
public final class RuntimeAccessException extends SecurityException {

    private final String reasonCode;
    private final ResultRef auditRef;

    /** 使用稳定消息构造访问拒绝，避免泄露认证引用、主体或资源存在性。 */
    public RuntimeAccessException(String reasonCode, ResultRef auditRef) {
        super("runtime access verification or authorization failed");
        this.reasonCode = ApiContracts.requireText(reasonCode, "reasonCode");
        this.auditRef = ApiContracts.requireNonNull(auditRef, "auditRef");
    }

    /** 返回适合跨边界处理的稳定失败原因码。 */
    public String reasonCode() {
        return reasonCode;
    }

    /** 返回由身份边界独立提交的安全审计事实引用。 */
    public ResultRef auditRef() {
        return auditRef;
    }
}
