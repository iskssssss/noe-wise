package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.ResultRef;

/** 表示 Provider adapter 在任何底层访问前无法消费 invocation permit。 */
public final class ProviderInvocationException extends IllegalStateException {

    private final String reasonCode;
    private final ResultRef auditRef;

    /** 使用稳定原因和脱敏审计引用构造 fail-closed 异常。 */
    public ProviderInvocationException(String reasonCode, ResultRef auditRef) {
        super(PortContracts.requireText(reasonCode, "reasonCode"));
        this.reasonCode = reasonCode;
        this.auditRef = PortContracts.requireNonNull(auditRef, "auditRef");
    }

    /** 返回不含供应商或凭据文本的稳定原因码。 */
    public String reasonCode() {
        return reasonCode;
    }

    /** 返回 permit 消费拒绝对应的不可变审计事实。 */
    public ResultRef auditRef() {
        return auditRef;
    }
}
