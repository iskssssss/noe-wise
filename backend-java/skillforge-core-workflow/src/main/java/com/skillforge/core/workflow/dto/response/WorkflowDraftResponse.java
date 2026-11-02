package com.skillforge.core.workflow.dto.response;

import java.util.List;
import java.util.Map;

/** Builder 当前草稿的完整受控响应。 */
public record WorkflowDraftResponse(
        String workflowId,
        String status,
        long revision,
        Map<String, Object> definition,
        List<WorkflowValidationProblem> problems,
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long activeDefinitionVersionId) {
}
