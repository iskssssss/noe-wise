package com.skillforge.core.workflow.dto.response;

import java.util.List;

/** 当前租户的 Builder 工作流实例列表。 */
public record BuilderWorkflowInstanceListResponse(
        int total,
        List<BuilderWorkflowInstanceResponse> items) {
}
