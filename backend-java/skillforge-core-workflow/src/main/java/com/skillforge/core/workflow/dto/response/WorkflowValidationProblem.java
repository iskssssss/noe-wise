package com.skillforge.core.workflow.dto.response;

/** Builder 服务端校验问题，字段定位必须稳定，供 Problems 面板点击聚焦。 */
public record WorkflowValidationProblem(
        String code,
        String severity,
        String objectRef,
        String fieldPath,
        String message) {

    public boolean isError() {
        return "error".equals(severity);
    }
}
