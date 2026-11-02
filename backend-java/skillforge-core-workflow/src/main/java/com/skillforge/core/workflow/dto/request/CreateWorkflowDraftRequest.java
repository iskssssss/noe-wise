package com.skillforge.core.workflow.dto.request;

import java.util.Map;

/** 创建 Builder 工作流容器及其首个草稿。 */
public record CreateWorkflowDraftRequest(String workflowId, Map<String, Object> definition) {
}
