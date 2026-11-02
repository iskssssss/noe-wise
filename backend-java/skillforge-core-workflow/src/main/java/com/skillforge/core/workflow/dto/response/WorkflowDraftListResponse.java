package com.skillforge.core.workflow.dto.response;

import java.util.List;

/** 当前租户可见的 Builder 容器列表。 */
public record WorkflowDraftListResponse(int total, List<WorkflowDraftListItemResponse> items) {
}
