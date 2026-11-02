package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.ResultRef;

/** 表示 Port 边界稳定、可审计且不泄露供应商异常的失败。 */
public record PortFailure(String reasonCode, RetryMode retryMode, ResultRef resultRef) {
    /** 拒绝缺失原因、重试语义或结果引用。 */
    public PortFailure {
        reasonCode = PortContracts.requireText(reasonCode, "reasonCode");
        retryMode = PortContracts.requireNonNull(retryMode, "retryMode");
        resultRef = PortContracts.requireNonNull(resultRef, "resultRef");
    }
}
