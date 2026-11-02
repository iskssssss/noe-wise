package com.skillforge.core.workflow.dto.response;

import java.util.List;

/** 草稿服务端验证结果。 */
public record WorkflowDraftValidationResponse(long revision, List<WorkflowValidationProblem> problems) {
}
