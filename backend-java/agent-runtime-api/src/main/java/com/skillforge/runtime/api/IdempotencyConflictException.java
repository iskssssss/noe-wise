package com.skillforge.runtime.api;

/**
 * 表示同一 InputId 的新请求指纹与首次有效 envelope 冲突，且不会覆盖原 receipt。
 */
public final class IdempotencyConflictException extends IllegalArgumentException {

    private final InputId inputId;
    private final ResultRef auditRef;

    /** 使用稳定消息并只保留输入身份与冲突审计引用。 */
    public IdempotencyConflictException(InputId inputId, ResultRef auditRef) {
        super("idempotency key conflicts with the original request fingerprint");
        this.inputId = ApiContracts.requireNonNull(inputId, "inputId");
        this.auditRef = ApiContracts.requireNonNull(auditRef, "auditRef");
    }

    /** 返回发生指纹冲突的输入身份。 */
    public InputId inputId() {
        return inputId;
    }

    /** 返回追加的稳定审计事实引用。 */
    public ResultRef auditRef() {
        return auditRef;
    }
}
