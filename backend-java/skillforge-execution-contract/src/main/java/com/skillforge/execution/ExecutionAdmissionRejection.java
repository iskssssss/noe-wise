package com.skillforge.execution;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;

/** 仅执行入口在提交前可证明的拒绝；普通 HTTP 失败不具有未执行语义。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ExecutionAdmissionRejection(Code code, String message, Submission submission) {
    public ExecutionAdmissionRejection {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(submission, "submission");
    }

    public static ExecutionAdmissionRejection notSubmitted(Code code) {
        return new ExecutionAdmissionRejection(code, code.message(), Submission.NOT_SUBMITTED);
    }

    public enum Code {
        INVALID_ARTIFACT_ARCHIVE("执行文件包缺少合法元数据或无法读取"),
        INVALID_EXECUTION_MANIFEST("执行元数据缺失或格式非法"),
        INVALID_EXECUTION_POLICY("执行请求不满足沙箱输入或执行策略"),
        EXECUTION_DEADLINE_EXPIRED("执行截止时间已过期，任务未提交");

        private final String message;
        Code(String message) { this.message = message; }
        public String message() { return message; }
    }

    public enum Submission { NOT_SUBMITTED }
}
