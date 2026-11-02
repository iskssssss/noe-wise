package com.skillforge.institution.execution;

import com.skillforge.execution.ExecutionAdmissionRejection;
import java.util.Objects;

/** 只承接认证 Sandbox 在提交前返回的确定拒绝，不代表执行后失败或未知结果。 */
public final class ExecutionSubmissionRejectedException extends IllegalStateException {
    public static final String REASON_CODE = "sandbox_execution_not_submitted";

    private final ExecutionAdmissionRejection rejection;

    public ExecutionSubmissionRejectedException(ExecutionAdmissionRejection rejection) {
        super(REASON_CODE);
        this.rejection = Objects.requireNonNull(rejection, "rejection");
        if (rejection.submission() != ExecutionAdmissionRejection.Submission.NOT_SUBMITTED) {
            throw new IllegalArgumentException("sandbox_submission_receipt_invalid");
        }
    }

    public ExecutionAdmissionRejection rejection() {
        return rejection;
    }
}
