package com.skillforge.sandbox.api;

import com.skillforge.execution.ExecutionAdmissionRejection;

/** 不保留可能含正文的解析异常；只携带提交执行前形成的安全拒绝事实。 */
public final class ExecutionAdmissionException extends RuntimeException {
    private final ExecutionAdmissionRejection rejection;

    public ExecutionAdmissionException(ExecutionAdmissionRejection.Code code) {
        super(code.message());
        rejection = ExecutionAdmissionRejection.notSubmitted(code);
    }

    public ExecutionAdmissionRejection rejection() { return rejection; }
}
